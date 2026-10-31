# TVcleanup_with_kidslock

我家 Sharp AQUOS 電視的整理包：精簡清單、家長鎖 App、手機遙控的轉送程式，還有這台電視的筆記。
建立在 [cleanyourtv](https://github.com/Gathin23/cleanyourtv) 之上，它的工具以 git submodule 引用，不複製。

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
TV_HOST=<tv ip> parental-lock/install.sh
```

細節看 [`parental-lock/README.md`](parental-lock/README.md)；遙控器要先裝好 [`pi-relay/`](pi-relay/README.md)。

## 不進 git 的東西

電視備份（`backup*/`）、截圖、ADB 金鑰、轉送程式的 `token` 和配對憑證，`.gitignore` 都擋掉了。
這個 repo 目前是 private；改成公開前要先檢查一遍有沒有個人資訊。

## 授權

cleanyourtv 是 MIT 授權。家長鎖最初是在 cleanyourtv 的 fork 裡寫的，歷史一起搬過來了。
