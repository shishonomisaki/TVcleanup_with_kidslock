# pi-relay：手機頁面遙控器的轉送程式

電視的遙控服務會拒絕「從電視自己發出」的連線，所以家長鎖 App 沒辦法直接送按鍵。
這支小程式跑在家裡另一台一直開著的機器上（這裡是樹莓派），把家長鎖送來的按鍵轉給電視。

```
手機 ──Tailscale──> 電視上的家長鎖（PIN、鎖定規則）──區網──> 這支轉送程式 ──遙控協定──> 電視
```

- 只綁定這台機器的區網位址，只接受電視的 IP，每個請求都要帶密鑰（`token`）
- 只接受 `relay.py` 裡 `KEYS` 列出的按鍵

## 安裝（約 15 分鐘）

```sh
mkdir -p ~/tvremote && cd ~/tvremote
# 把 relay.py、pair.py、keytest.py、requirements.txt 複製進來
python3 -m venv venv
./venv/bin/pip install -r requirements.txt
(umask 077; ./venv/bin/python -c "import secrets;print(secrets.token_hex(24))" > token)
```

### 跟電視配對（電視要開著）

```sh
nohup ./venv/bin/python pair.py <電視IP> > pair.log 2>&1 &
# 電視上會出現 6 碼配對碼：
echo <配對碼> > pair-code
cat pair.log          # 看到 PAIRED 就成功了
chmod 600 cert.pem key.pem
./venv/bin/python keytest.py <電視IP> DPAD_RIGHT DPAD_LEFT
```

### 開機自動啟動

複製 `tvremote-relay.service` 到 `~/.config/systemd/user/`，填好兩個位址，再照檔案開頭的指令啟用。

### 告訴家長鎖轉送程式在哪裡

```sh
tools/tv.py "am broadcast -n io.github.shishonomisaki.parentlock/.AdminReceiver -a SET_RELAY \
  --es host <這台機器的區網IP> --ei port 6480 --es token <token 檔的內容>"
```

## HTTPS（語音、麥克風需要）

手機瀏覽器只在 HTTPS 頁面開放麥克風。用 Tailscale 自己的憑證，免費：

1. Tailscale 後台 DNS 頁開 MagicDNS 和 HTTPS Certificates
2. service 檔加上 `Environment=RELAY_PAGE=<電視的 100.x>:<手機頁面埠>`，重啟。`relay.py` 會在
   `127.0.0.1:6481` 把連線轉給電視的手機頁面
3. `sudo tailscale serve --bg --https=443 http://127.0.0.1:6481`
4. 手機開 `https://<這台機器>.<tailnet>.ts.net`

不能讓 `tailscale serve` 直接轉到電視的 100.x：tailscaled 自己發出的連線不走 Tailscale 路由，會卡住沒有回應。
憑證會出現在公開的憑證紀錄（CT log）裡，機器名稱和 tailnet 名稱會被看到。ACL 要把這台的 443 只開給家長。

## 不要提交的檔案

`token`、`cert.pem`、`key.pem`、`pair-code`、`*.log` 都只留在樹莓派上，`.gitignore` 已經擋掉。

## 已知行為

- 連線剛建立時，電視會吃掉第一個按鍵，所以 `relay.py` 連上後會先等 2 秒。之後一直保持連線，就不會再遇到
- 電視重設遙控配對後，程式會記錄 `run pair.py again` 並結束，重新配對就好
