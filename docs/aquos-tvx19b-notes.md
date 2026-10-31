# AQUOS-TVX19B 筆記

這台電視查過、測過的事。數字和時間都是 2026-10-29～31 實測的結果。

## 藍牙：待機時晶片會一直出錯

- **現象：** 手機 Google TV App 遙控常要等、要求重新配對；裝置清單出現好幾台同名電視；藍牙接收器（1Mii B06Pro）在電視開機後不會自己連回來
- **原因：** 電視進入待機約 1 分鐘後，藍牙晶片開始回報 `Ctlr H/w error event - code:0xf8`，每 17～35 秒一次，`com.android.bluetooth` 跟著當掉重開；開機（螢幕亮）時就停。連續 3 次都對得上
- **不是精簡造成的：** 停用清單裡沒有藍牙或遙控相關套件
- **韌體：** RTOB.250807.002 已是最新版
- **查法：** `logcat -d -v time | grep -E "Ctlr H/w error|com.android.bluetooth \(pid"`
- **試過的：**
  - 拔電冷開機：只撐到下一次待機
  - 待機時 `svc bluetooth disable`：錯誤消失、喇叭會回來，但手機 Google TV App 叫不醒電視（它從待機喚醒時要靠電視藍牙）
  - 開機後重開藍牙：電視不會主動連喇叭（`autoConnect: most recently active a2dp device is null`），一般 App 在 Android 11 也沒有權限主動連 A2DP
- **現在的做法：** 看電視前把 B06Pro 關掉再打開
- **根治方向：** 光纖轉類比轉換器取代 B06Pro（電視光纖輸出 → 轉換器 → 喇叭 AUX）。B06Pro 是「Receiver Only」，所有孔都是輸出

## 手機 Google TV App 遙控

- 走 Wi-Fi（電視 6466／6467 埠），手機要連家裡 Wi-Fi；只開 Tailscale 在外面不行（靠區網廣播找電視）
- 電視從待機被叫醒時要用到電視的藍牙

## 遙控服務擋掉電視自己

從電視上的 App 連 127.0.0.1、電視的 Wi-Fi 位址、電視的 Tailscale 位址，連線都會被切斷；從其他機器連就可以。
所以家長鎖的遙控器要經過 `pi-relay/`。

## Tailscale 存取規則

家人裝置連手機頁面被擋時，電視上 Tailscale 的紀錄會出現 `Drop: TCP{<她的 100.x> > <電視>:<port>} no rules matched`。
要在 ACL 為她加一條，只開放電視的手機頁面埠：

```json
{ "src": ["<她的登入信箱>"], "dst": ["<電視的 100.x>"], "ip": ["tcp:<手機頁面埠>"] }
```

她不是 tailnet 成員，電視是用「分享」給她的。HTTPS 網址在樹莓派上，所以樹莓派也要分享給她（Machines → ⋯ → Share），
再加一條只開 `tcp:443` 的規則。分享出去的裝置一樣受 ACL 管，她碰不到樹莓派上的其他服務。
沒分享時的徵兆：樹莓派的 `tailscale status` 完全沒有她的手機，ACL 怎麼改都沒用。

## 音訊輸出設定

- 「喇叭選擇 ARC」只管 HDMI ARC；光纖輸出（SPDIF）一直跟內建喇叭同時輸出
- 「數位音訊輸出」選 PCM 相容性最好
