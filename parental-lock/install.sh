#!/usr/bin/env bash
# Install Parental Lock on the TV and switch it on. The TV locks as soon as this ends.
#   TV_HOST=<tv ip> RELEASE=1 parental-lock/install.sh    the newest GitHub release (recommended: from then
#                                                          on it updates itself from the phone page)
#   TV_HOST=<tv ip> parental-lock/install.sh              build it here (needs the JDK and Android SDK)
# SWITCH=1 first removes a copy signed by another key (a self-built one, when moving to releases): the PIN
# and settings start over. SET_PORT=<your port> picks the phone page's port.
set -euo pipefail
: "${TV_HOST:?set TV_HOST to the TV IP address}"
cd "$(dirname "$0")"
TOOLS="$(cd ../upstream/cleanyourtv/tools && pwd)"
PY=$TOOLS/venv/bin/python
[ -x "$PY" ] || PY=$TOOLS/venv/Scripts/python.exe
export MSYS_NO_PATHCONV=1
PKG=io.github.shishonomisaki.parentlock
SVC=$PKG/$PKG.GuardService
# Script paths stay relative: with MSYS_NO_PATHCONV, Git Bash would hand Windows Python a /c/... path.
tv() { "$PY" -I ../upstream/cleanyourtv/tools/tv.py "$@"; }

[ -f local.properties ] || { [ -n "${ANDROID_HOME:-}" ] && echo "sdk.dir=${ANDROID_HOME//\\//}" > local.properties; } || true
if [ -n "${JAVA_HOME:-}" ]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
APK=app/build/outputs/apk/release/app-release.apk
if [ "${RELEASE:-}" = 1 ]; then
    API=https://api.github.com/repos/shishonomisaki/TVcleanup_with_kidslock/releases/latest
    URL=$(curl -fsSL "$API" | grep -o 'https://[^"]*/parental-lock-[0-9]*[.]apk' | head -n1)
    [ -n "$URL" ] || { echo "ERROR: the latest release has no parental-lock APK" >&2; exit 1; }
    mkdir -p "$(dirname "$APK")"
    curl -fsSL -o "$APK" "$URL"
    echo "downloaded $URL"
else
    ./gradlew assembleRelease -q --console=plain
fi
if [ "${SWITCH:-}" = 1 ]; then tv "pm uninstall $PKG >/dev/null 2>&1; echo old copy removed"; fi

"$PY" -I ../upstream/cleanyourtv/tools/b64push.py "$APK" /data/local/tmp/parental-lock.apk
OUT=$(tv 'pm install -r /data/local/tmp/parental-lock.apk; rm -f /data/local/tmp/parental-lock.apk')
echo "$OUT"
case "$OUT" in
    *Success*) ;;
    *UPDATE_INCOMPATIBLE*|*SIGNATURE*)
        echo "ERROR: the TV has a copy signed by another key. Run again with SWITCH=1 (removes it first;" \
            "the PIN and settings start over)." >&2
        exit 1 ;;
    *) echo "ERROR: pm install failed" >&2; exit 1 ;;
esac

tv "appops set $PKG SYSTEM_ALERT_WINDOW allow; echo overlay allowed"
# Updates from the phone page: Android still asks on the TV before each one.
tv "appops set $PKG REQUEST_INSTALL_PACKAGES allow; echo updates allowed"
# Tailscale reconnects after a reboot only as the always-on VPN; without it the phone can't reach a rebooted TV.
tv "pm path com.tailscale.ipn >/dev/null 2>&1 && settings put secure always_on_vpn_app com.tailscale.ipn && echo Tailscale always-on || echo 'WARNING: Tailscale is not installed on this TV; the phone page needs it'"
# Add the guard to the enabled services, keeping any others (a key helper of the launcher, say).
# Android may store it as $PKG/.GuardService or in full, so match either.
tv "s=\$(settings get secure enabled_accessibility_services); case \"\$s\" in *$PKG/*GuardService*) ;; null|'') s=$SVC ;; *) s=\$s:$SVC ;; esac; settings put secure enabled_accessibility_services \"\$s\"; settings put secure accessibility_enabled 1; echo guard on"
sleep 3
# SET_PORT=<1024-65535> picks the phone page's port; otherwise the app keeps the one it chose at random.
R=$PKG/.AdminReceiver
if [ -n "${SET_PORT:-}" ]; then tv "am broadcast -n $R -a SET_PORT --ei port $SET_PORT" >/dev/null; fi
PORT=$(tv "am broadcast -n $R -a PORT" | grep -o 'data="[0-9]*"' | tr -dc '0-9')
ADDR=$(tv "ip -4 addr show 2>/dev/null | grep -o 'inet 100\\.[0-9.]*' | head -n1 | cut -d' ' -f2" | tr -d '\r')
if [ -n "$ADDR" ]; then
    echo "Installed and locked. On the phone (with Tailscale on), open http://$ADDR:$PORT and choose the PIN."
else
    echo "Installed and locked. Connect Tailscale on the TV, then open http://<its 100.x address>:$PORT on the phone."
fi
