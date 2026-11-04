# 更新家長鎖

從 v1.1 起，家長鎖可以**從手機頁自己更新**：有新版時，手機頁「更多 → 更新」會顯示「有新版」，輸入 PIN，
電視會自己下載，然後跳出 Android 的安裝畫面，用遙控器選「安裝」就好。不用電腦，也不用開 ADB。

只有一個前提：電視上的家長鎖必須是**從 GitHub Release 裝的版本**（用這個 repo 的正式金鑰簽的）。
自己用電腦建置的版本是用你電腦的金鑰簽的，Android 不允許用別把金鑰的版本覆蓋它，所以要先換一次。

## 為什麼建議裝 v1.1.1 以後的版本

電視預設是鎖著的，只能用手機頁解鎖，而手機頁要靠 **Tailscale** 才連得到。舊版有一個風險：

1. 電視記憶體不夠時，Android 會把 Tailscale 關掉，而且它不會自己再連上。
2. 這時手機頁連不到電視，電視就一直鎖著。
3. 如果電視的 ADB 也已經關掉，電腦也救不回來；電視被鎖住時，又進不了設定去打開 ADB。
   最後可能只剩**把電視恢復原廠設定**這條路。

v1.1 起，家長鎖每分鐘會檢查 Tailscale，斷了約 2 分鐘內就自己接回來；v1.1.1 再修好了重新啟動後的倒數顯示和更新檢查。
**建議至少裝 v1.1.1，裝好之前不要關掉電視的 ADB。** v1.2.0 起還有**救援碼**：電視鎖住、手機又連不到時，
用電視的實體遙控器輸入就能解鎖，見 [`RECOVERY.md`](RECOVERY.md)。

另外在 Tailscale 管理頁面（login.tailscale.com → Machines → 電視那台 → ⋯ → **Disable key expiry**）關掉電視的金鑰到期。
不關的話，電視的 Tailscale 登入預設約 180 天後會失效，結果跟上面一樣：手機連不到，電視鎖住。

## 第一次換成正式版（只做一次）

自己建置過家長鎖的人，做一次下面的步驟。之後就都從手機頁更新。

**會重設的東西**：PIN、看電視的時間設定、App 按鈕。換完要在手機頁重新設定 PIN。

1. 電視打開 ADB（開發者選項 → ADB 偵錯 / 網路偵錯，port 5555）。
2. 電腦上拿到最新的 repo：
   - 用 git 下載的：在 repo 資料夾裡 `git pull --recurse-submodules`
   - 下載 ZIP 的：重新下載 ZIP（GitHub 頁面 → Code → Download ZIP）並解壓。`upstream/cleanyourtv`
     資料夾在 ZIP 裡是空的，要另外下載 [cleanyourtv](https://github.com/Gathin23/cleanyourtv) 的 ZIP 放進去，
     並照 README「第一次取得」建好 `tools/venv`。
3. 在 repo 資料夾裡執行（`<電視 IP>` 換成電視在家裡 Wi-Fi 的 IP）：
   ```sh
   TV_HOST=<電視 IP> RELEASE=1 SWITCH=1 parental-lock/install.sh
   ```
   - `RELEASE=1`：從 GitHub 下載最新的正式版，不用在電腦上建置（不需要 JDK、Android SDK）。
   - `SWITCH=1`：先移除電視上舊的自建版本。
4. 手機開啟手機頁，設定新的 PIN。
5. 確認手機頁「更多 → 更新」顯示的目前版本是 **1.1.1 或更新**，Tailscale 金鑰到期也關掉了，再關掉電視的 ADB。

## 之後的更新

1. 手機頁 → 時間分頁最下面「更多」→「更新」。有新版會顯示「有新版 1.x」。
2. 電視要先解鎖。輸入 PIN，按「輸入 PIN 更新」。
3. 電視上出現 Android 的安裝畫面，用遙控器選「安裝」。
4. 約 10 秒後家長鎖自己重新啟動，設定和 PIN 都會保留。

想收到新版通知：在 GitHub 這個 repo 頁面按 **Watch → Custom → Releases**。

## 樹莓派上的轉送程式（有裝才需要）

`pi-relay/relay.py` 不會跟著自動更新。Release 說明裡寫了「relay 有改」時，才需要：

```sh
git pull
scp pi-relay/relay.py <pi>:tvremote/relay.py && ssh <pi> 'systemctl --user restart tvremote-relay'
```

---

## 給協助更新的 AI 助理

這一節寫給幫使用者操作的 AI（ChatGPT、Codex 等）。照這裡做，不要自己發明步驟。

**開始任何操作之前，你必須做到這兩件事：**

1. **向使用者完整說明風險**，至少包括：
   - 家長鎖會讓電視**預設鎖定**，只能用手機頁解鎖；手機連不到電視時（電視的 Tailscale 斷線或登出、換路由器、
     忘記 PIN），電視會一直鎖著。電視鎖住時進不了設定，也就沒辦法去打開 ADB，
     沒有救援碼的話**可能只能恢復原廠設定**，電視上所有 App、帳號和設定都會清掉。
   - 「第一次換成正式版」（`SWITCH=1`）會先移除舊的家長鎖，**PIN、看電視的時間設定、App 按鈕會全部重設**。
   - 你會在使用者的電腦上執行指令，透過 ADB 直接修改電視：安裝或移除 App、改無障礙和權限設定。
2. **取得使用者明確的同意**（例如使用者回覆「我了解風險，繼續」）之後，才執行任何會改變電視的指令。
   每一個會改變電視的步驟（安裝、`SWITCH=1`、移除、恢復原廠設定）都要先說清楚會發生什麼，再確認一次。
   使用者沒有明確同意，就停下來，不要自己決定。

**操作完成後，你必須提醒使用者**做完 [`RECOVERY.md`](RECOVERY.md)「1. 事先準備」：產生救援碼並妥善保存、
關掉電視的 Tailscale 金鑰到期。這兩件事沒做完之前，不要建議使用者關掉電視的 ADB。

**事實**

- App 套件名稱 `io.github.shishonomisaki.parentlock`，裝在 Android TV 上，用 ADB（port 5555）安裝。
- Release 的 APK 名稱是 `parental-lock-<versionCode>.apk`，用這個 repo 的正式金鑰簽章。
- Android 只允許**同一把金鑰**簽的 APK 覆蓋安裝。使用者自己建置的 APK 用的是他電腦的 debug 金鑰，
  和 Release 版不同，所以第一次要 `SWITCH=1`（先移除，會清掉 PIN 和設定）。
- 換成 Release 版之後，更新在**手機頁**完成（更多 → 更新），不需要電腦和 ADB。
- `install.sh` 用的是 `upstream/cleanyourtv/tools/` 裡的 `tv.py`、`b64push.py` 和 `tools/venv`，
  不是 `adb` 指令本身。

**該跑的指令**

| 使用者的狀況 | 指令 |
|---|---|
| 自建版 → 換成正式版（第一次） | `TV_HOST=<ip> RELEASE=1 SWITCH=1 parental-lock/install.sh` |
| 已是正式版，但手機頁不能用（要重裝） | `TV_HOST=<ip> RELEASE=1 parental-lock/install.sh` |
| 已是正式版，一般更新 | 不用電腦：手機頁「更多 → 更新」 |

Windows 上用 Git Bash 執行；`install.sh` 會自己處理 Windows 的路徑。

**錯誤和處理**

| 看到的訊息 | 原因 | 處理 |
|---|---|---|
| `the TV has a copy signed by another key` | 電視上是自建版 | 加 `SWITCH=1` 再跑一次 |
| `set TV_HOST to the TV IP address` | 沒給電視 IP | 加 `TV_HOST=<ip>` |
| `refused the connection` / 連不到 5555 | 電視的 ADB 沒開 | 請使用者打開電視的開發者選項和 ADB |
| `the latest release has no parental-lock APK` | GitHub 上沒有 Release，或網路不通 | 確認 https://github.com/shishonomisaki/TVcleanup_with_kidslock/releases 有 APK |
| 手機頁「電視還不允許這個 App 安裝更新」 | 少了安裝權限 | 跑一次 `TV_HOST=<ip> RELEASE=1 parental-lock/install.sh` |
| 手機頁「新版的簽章和電視上的不同」 | 電視上還是自建版 | 做「第一次換成正式版」 |
| 手機頁「先解鎖電視」 | 電視鎖著時不能更新 | 先在手機頁解鎖，再按更新 |

**不要做**

- 不要改 `parental-lock/app/build.gradle` 的簽章設定，也不要叫使用者產生自己的金鑰：那樣就收不到正式版的更新。
- 不要用 `adb install` 直接裝使用者自己建置的 APK 去「更新」正式版：會因為金鑰不同而失敗。
- 不要把任何金鑰（`*.jks`、`keystore.properties`）、ADB 金鑰、`token` 放進 git。
- 電視上的版本還不到 v1.1.1、或 Tailscale 金鑰到期還沒關之前，不要叫使用者關掉電視的 ADB（理由見上面「為什麼建議裝 v1.1.1 以後的版本」）。
- 不要用 `am force-stop` 停掉家長鎖：Android 會把它的無障礙服務標成當機並停用，電視就沒有鎖了；要重啟就重新安裝一次 APK。
