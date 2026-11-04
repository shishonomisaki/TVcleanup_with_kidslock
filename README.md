# TVcleanup_with_kidslock

我家 Sharp AQUOS 電視的整理包：精簡清單、家長鎖 App、手機遙控的轉送程式，還有這台電視的筆記。
建立在 [cleanyourtv](https://github.com/Gathin23/cleanyourtv) 之上，它的工具以 git submodule 引用，不複製。

> ## ⚠ 安裝前請先讀：電視可能被鎖到解不開
>
> 家長鎖讓電視**預設是鎖著的**，平常只能用手機頁（經由 Tailscale）解鎖。手機連不到電視時，電視就會一直鎖著，例如：
> 電視的 Tailscale 斷線或登出、換了路由器或 Wi-Fi 密碼、忘記 PIN。電視鎖住時進不了設定，電視重開機後 ADB 也會關掉，
> **沒有準備的話，可能只能把電視恢復原廠設定**。
>
> 安裝後請馬上做好這三件事，步驟在 [`docs/RECOVERY.md`](docs/RECOVERY.md)：
>
> 1. 在手機頁產生**救援碼**（10 位數，用電視的實體遙控器就能解鎖），存進密碼管理器。
> 2. 在 Tailscale 管理頁關掉電視的**金鑰到期**（Disable key expiry）。
> 3. 確認家長鎖是 **v1.1.1 以後**的正式版。
>
> 這是個人專案，照現況提供，不保證適用你的電視。你要自己承擔電視被鎖、需要恢復原廠設定的風險。
>
> 用 AI（ChatGPT、Codex 等）幫忙安裝或更新的話，請它先讀 [`docs/UPDATING.md`](docs/UPDATING.md) 和 [`docs/RECOVERY.md`](docs/RECOVERY.md)。
> 兩份文件都要求 AI 在動手之前，先跟你完整說明風險，並取得你的同意。

| 資料夾 | 內容 |
|---|---|
| `parental-lock/` | 家長鎖 App：電視預設鎖定，用手機（Tailscale）給時間、遙控、開 App |
| `pi-relay/` | 跑在樹莓派上的轉送程式，讓手機頁面能送方向鍵到電視 |
| `tv/aquos-tvx19b/` | 這台電視的精簡清單 |
| `docs/` | 這台電視查過、測過的事（藍牙待機錯誤、音訊輸出、Tailscale ACL） |
| `upstream/cleanyourtv/` | 原作者的 repo（submodule）：`tools/`、啟動器 |

## 第一次取得

```sh
git clone --recurse-submodules <這個 repo>
cd upstream/cleanyourtv && python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt
```

## 家長鎖

```sh
TV_HOST=<tv ip> RELEASE=1 parental-lock/install.sh
```

`RELEASE=1` 會裝 GitHub 上最新的正式版，不用在電腦上建置。之後從手機頁「更多 → 更新」就能更新。
不加 `RELEASE=1` 則是在這台電腦上建置（要 JDK 17 和 Android SDK），那個版本不能從手機頁更新。

## 更新

看 [`docs/UPDATING.md`](docs/UPDATING.md)。自己建置過舊版的人，要先做一次「換成正式版」。
用 AI（ChatGPT 等）幫忙的話，把那份文件整份給它看，裡面有一節是寫給 AI 的。

細節看 [`parental-lock/README.md`](parental-lock/README.md)；遙控器要先裝好 [`pi-relay/`](pi-relay/README.md)。
**裝好後馬上照 [`docs/RECOVERY.md`](docs/RECOVERY.md) 產生救援碼。**

## 不進 git 的東西

電視備份（`backup*/`）、截圖、ADB 金鑰、轉送程式的 `token` 和配對憑證，`.gitignore` 都擋掉了。
發布用的簽章金鑰（`*.jks`、`keystore.properties`）也一樣不進 git，只存在 GitHub 的 secrets 和擁有者的電腦。

## 授權

cleanyourtv 是 MIT 授權。家長鎖最初是在 cleanyourtv 的 fork 裡寫的，歷史一起搬過來了。
