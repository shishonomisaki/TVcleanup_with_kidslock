#!/bin/sh
# pre-commit 出口檢查的自我檢測。
# 執行方式（Windows）：點兩下 scripts\selftest.bat
# 執行方式（手動）  ：git -c "alias.selftest=!sh scripts/hook-selftest.sh" selftest
# 做的事：開暫存 repo，真的丟「該擋的」和「該放行的」案例給 hook，逐項比對，
#         結果印在畫面並寫到 scripts/selftest-result.txt（這個檔就是「測過」的證據）。
# 注意：案例裡的假密碼、假金鑰都是執行時才拼出來的，所以這支腳本本身不會被 hook 擋。

HOOKDIR="$(cd "$(dirname "$0")" && pwd)"
HOOK="$HOOKDIR/pre-commit"
OUT="$HOOKDIR/selftest-result.txt"
[ -f "$HOOK" ] || { echo "找不到 $HOOK"; exit 2; }

PASS=0; FAIL=0; LOG=""
NL='
'
G="git -c user.email=selftest@example.invalid -c user.name=selftest -c commit.gpgsign=false -c core.hooksPath=.git/hooks -c core.autocrlf=false"

newrepo() {
  R="$(mktemp -d)" || exit 2
  cd "$R" || exit 2
  git init -q . >/dev/null 2>&1
  cp "$HOOK" .git/hooks/pre-commit; chmod +x .git/hooks/pre-commit 2>/dev/null
}
judge() { # judge <代號> <BLOCK|PASS> <說明>
  out="$($G commit -q -m selftest 2>&1)"; rc=$?
  if [ $rc -eq 0 ]; then got=PASS; else got=BLOCK; fi
  case "$out" in *"檢查腳本本身出錯"*|*"syntax error"*|*"not found"*|*"cannot run"*) got="ERROR" ;; esac
  if [ "$got" = "$2" ]; then PASS=$((PASS+1)); m="OK  "; else FAIL=$((FAIL+1)); m="FAIL"; fi
  LOG="$LOG$m $1 應$2 實際$got  $3$NL"
  cd /; rm -rf "$R"
}
case1() { # case1 <代號> <期望> <說明> <檔名> <內容>
  newrepo; d="$(dirname "$4")"; [ "$d" = "." ] || mkdir -p "$d"
  printf '%s\n' "$5" > "$4"; git add -A >/dev/null 2>&1; judge "$1" "$2" "$3"
}

# 執行時才拼出來的假資料
PW="pass""word"; MM="密""碼"; V="Abc""12345"
IP1="10"".1.2.3"; IP2="203"".66.14.25"; IPT="100"".101.102.103"
KG="AIza""SyA1234567890abcdefghijklmnopqrstuvw"
KT="123456789"":AAEhBP0av28FghIjkLmnOpQrStUvWxYz012"
KJ="eyJ""hbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.""eyJ""yb2xlIjoic2VydmljZV9yb2xlIn0.abcDEF123"
KA="sk-""ant-api03-AbCdEfGhIjKlMnOpQrStUvWxYz0123456789AbCd"
ID="A1""23456789"

# ---- 應該擋下 ----
case1 S01 BLOCK "內網 IP"                 a.md "主機 $IP1 是後台"
case1 S02 BLOCK "主機對外 IP"             a.md "正式機 IP：$IP2"
case1 S03 BLOCK "Tailscale IP"            a.md "pinas $IPT"
case1 S04 BLOCK "英文密碼欄位"            a.txt "$PW=$V"
case1 S05 BLOCK "中文密碼欄位"            a.txt "$MM：$V"
case1 S06 BLOCK "JSON 裡的密碼"           a.json "{\"user\":\"admin\",\"$PW\":\"$V\"}"
case1 S07 BLOCK "口語寫法的密碼"          a.md "後台${MM}是 $V"
case1 S08 BLOCK "中文檔名"                "資料倉儲_討論.md" "$PW=$V"
case1 S09 BLOCK "檔名含空白"              "my notes.md" "$PW=$V"
case1 S10 BLOCK "空白資料夾＋中文檔名"    "Claude outputs/上線清單.md" "主機 $IP1"
case1 S11 BLOCK "Gemini API key"          a.md "$KG"
case1 S12 BLOCK "Telegram bot token"      a.md "$KT"
case1 S13 BLOCK "Supabase JWT"            a.md "$KJ"
case1 S14 BLOCK "Anthropic API key"       a.md "$KA"
case1 S15 BLOCK "身分證字號"              a.md "申請人 $ID"
AT="@"
case1 S16 BLOCK "email 名單（6 筆）"      a.csv "a1${AT}gmail.com${NL}b2${AT}yahoo.com.tw${NL}c3${AT}hotmail.com${NL}d4${AT}gmail.com${NL}e5${AT}outlook.com${NL}f6${AT}gmail.com"
newrepo; printf '\377\376' > u16.txt; printf 'p\000a\000s\000s\000w\000o\000r\000d\000=\000A\000b\000c\0001\0002\0003\0004\0005\000\r\000\n\000' >> u16.txt
git add -A >/dev/null 2>&1; judge S17 BLOCK "UTF-16 文字檔（PowerShell 的 > 會產生）"
newrepo; printf 'PK\003\004\000\000\000\000binary\000\001\002' > doc.docx; git add -A >/dev/null 2>&1; judge S18 BLOCK "Office／二進位檔"
newrepo; printf 'l1\nl2\nl3\nl4\nl5\nl6\nl7\nl8\n' > old.md; git add -A >/dev/null 2>&1; $G commit -q --no-verify -m base >/dev/null 2>&1
git mv old.md new.md; printf '%s\n' "$PW=$V" >> new.md; git add -A >/dev/null 2>&1; judge S19 BLOCK "改名同時加入密碼"

# ---- 應該放行 ----
case1 N01 PASS "一般筆記"                 a.md "今天完成上線確認"
case1 N02 PASS "談密碼規則但沒有密碼值"   a.md "${MM}：不可寫入 repo，請放 private 資料夾"
case1 N03 PASS "本機位址"                 a.md "curl http://127.0.0.1:3000/health"
case1 N04 PASS "文件保留位址"             a.md "範例請用 192.0.2.10"
case1 N05 PASS "四段版本號"               a.md "Chrome 120.0.6099.130"
case1 N06 PASS "行尾允許標記"             a.md "範例：$PW=$V <!-- scan:""allow -->"
case1 N07 PASS "提到稽核字眼（只提醒）"   a.md "資安稽核""發現的具體內容不可進 repo"
case1 N08 PASS "檢查碼不符的編號"         a.md "案號 A1""23456780"
newrepo; printf '*.png\n' > .scan-allow-binary; printf '\211PNG\r\n\032\n\000\000' > shot.png; git add -A >/dev/null 2>&1; judge N09 PASS "列入 .scan-allow-binary 的圖片"

# ---- 環境檢查 ----
CR="$(printf '\r')"
if grep -q "$CR" "$HOOK"; then FAIL=$((FAIL+1)); LOG="${LOG}FAIL E01 hook 檔含 CRLF 換行（會讓 hook 失效；請確認 .gitattributes 後重新 checkout）$NL"
else PASS=$((PASS+1)); LOG="${LOG}OK   E01 hook 檔是 LF 換行$NL"; fi
if git -C "$HOOKDIR/.." rev-parse --git-dir >/dev/null 2>&1; then
  HP="$(git -C "$HOOKDIR/.." config --get core.hooksPath 2>/dev/null)"
  if [ "$HP" = "scripts" ]; then PASS=$((PASS+1)); LOG="${LOG}OK   E02 這個 repo 的 core.hooksPath = scripts（hook 已生效）$NL"
  else FAIL=$((FAIL+1)); LOG="${LOG}FAIL E02 這個 repo 的 core.hooksPath 是「$HP」，hook 沒有生效；請執行 scripts\\setup-git.bat$NL"; fi
else
  LOG="${LOG}--   E02 這個資料夾還不是 git repo，略過（先執行 scripts\\setup-git.bat）$NL"
fi

{
  echo "pre-commit 自我檢測結果"
  echo "時間: $(date '+%Y-%m-%d %H:%M:%S %z')"
  echo "環境: $(git --version 2>/dev/null) / $(uname -s 2>/dev/null)"
  echo "結果: 通過 $PASS、不通過 $FAIL"
  echo "----"
  printf '%s' "$LOG"
} > "$OUT" 2>/dev/null
cat "$OUT" 2>/dev/null || { echo "結果: 通過 $PASS、不通過 $FAIL"; printf '%s' "$LOG"; }
echo "----"
if [ "$FAIL" -eq 0 ]; then echo "全部通過。結果已寫到 scripts/selftest-result.txt"; exit 0
else echo "有 $FAIL 項不通過。請把 scripts/selftest-result.txt 的內容貼給 Claude。"; exit 1; fi
