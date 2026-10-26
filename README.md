# Parental Lock

TV time a parent hands out from a phone. The TV starts locked, on a black screen that says it isn't TV time
right now, and every remote key does nothing. On the phone, a parent picks 30, 60, 90 or 120 minutes, enters a
six-digit PIN and unlocks; when the time runs out the TV pauses what is playing, mutes and locks again. A window
that runs past midnight is not cut short, and a reboot or a power cut always comes back locked.

The phone page is served by the TV itself and reached over [Tailscale](https://tailscale.com), so it works from
home and from anywhere else, with no account or server of its own. English, or Traditional Chinese when the
phone is set to Chinese.

![Parental Lock on a TV](screenshot.jpg)

| On the phone | What it does |
|---|---|
| **Status** | Online or offline, a ring counting down the time left (green) or the lock (red), the app on screen, and today's viewing time and unlocks. |
| **1. Pick the viewing time** | 30, 60, 90 or 120 minutes. Unlock stays greyed out until a time is picked and the PIN is six digits. |
| **2. Enter the PIN** | Chosen on the phone on the first visit, so it never passes through adb. Five wrong PINs lock the page for ten minutes. |
| **Lock now** | No PIN: a second press within four seconds confirms. |
| **Open Settings 5 min** | Needs the PIN and an unlocked TV. Settings is otherwise closed, so the lock can't be switched off from the TV. Five minutes later Settings closes, even if someone is still in it. |

| On the TV | |
|---|---|
| **Lock screen** | Covers apps, Live TV and HDMI inputs, and comes back over whatever reaches the front. The power key still works; a voice search can open but not go anywhere. |
| **Countdown** | A small pill with the time left, on Home only, so it never sits over a film. One minute before the end a toast says so. |
| **Volume per app** | Optional: each app comes back at the level it was last left at, kept separately for the TV's speakers and a Bluetooth speaker, with starting levels set over adb (below). Nothing is changed until one is set. |

## Before you start

- Tailscale on the TV, signed in and set as the always-on VPN so it reconnects after a reboot (the install
  script does that), and Tailscale on the parent's phone, on the same tailnet.
- Keep network debugging (ADB) on. It is the way back from a forgotten PIN: with it off, Settings is locked and
  a factory reset is the only way out. The TV still asks before a new computer may connect.

## Install

JDK 17 and Android SDK 34, as for the launcher, and the `tools/venv` from the main README. Android 8.0 or later.

```bash
TV_HOST=<tv ip> addons/parental-lock/install.sh
```

The TV locks as soon as the script ends. Open the address the script prints (the TV's Tailscale address, port
8765) on the phone, choose the PIN, then unlock. The app's tile on the TV shows the address again.

## Over adb

For a forgotten PIN, recovery, tests and the volume levels, from the adb shell only:

```bash
R=io.github.shishonomisaki.parentlock/.AdminReceiver
tools/tv.py "am broadcast -n $R -a SET_PIN --es pin 123456"      # a new PIN
tools/tv.py "am broadcast -n $R -a UNLOCK --ei minutes 30"       # unlock without the phone
tools/tv.py "am broadcast -n $R -a LOCK"
tools/tv.py "am broadcast -n $R -a SET_VOLUME --es output tv --ei volume 13"   # TV speakers, on unlock and new apps
tools/tv.py "am broadcast -n $R -a SET_VOLUME --es output bt --ei volume 50"   # a Bluetooth speaker
tools/tv.py "am broadcast -n $R -a SET_VOLUME --es output tv --es app com.netflix.ninja --ei volume 16"
tools/tv.py "am broadcast -n $R -a RESET_VOLUMES"                # forget the levels learned per app
```

To take it off: `tools/tv.py "settings put secure enabled_accessibility_services ''"` (or remove just this
service from the list), then `pm uninstall io.github.shishonomisaki.parentlock`.

## Permissions

| Permission | Why |
|---|---|
| **Accessibility service** (`GuardService`), switched on by the install script | To see which app is on screen (package name only: it cannot retrieve window content), to eat the remote's keys while locked (`flagRequestFilterKeyEvents`), and to press Home when Settings closes. |
| **Display over other apps** (`SYSTEM_ALERT_WINDOW`), granted with `appops` | To bring the lock screen back from the background on Android 10 and later, and for the countdown pill on Home. |
| `INTERNET` | The phone page: a small HTTP server on port 8765 that answers Tailscale addresses (100.64.0.0/10) only and drops everything else, so a device on the home Wi-Fi gets nothing. Tailscale's WireGuard tunnel carries it encrypted. Nothing is sent anywhere. |
| **Package visibility** (`<queries>`) | The apps that declare Home (the countdown shows there), launcher and leanback apps (to name the app on screen), and the Live TV app. |

`AdminReceiver` is exported only to callers holding `WRITE_SECURE_SETTINGS`, which the adb shell has and ordinary
apps cannot get. State stays in the app's own storage, backups off: the PIN as a salted SHA-256 hash, the open
window against the uptime clock and boot count, today's totals. No accounts, telemetry, ads or libraries.

## Limits

- The lock is an app, not a system feature. Anyone with ADB, or the TV's recovery menu, can take it off.
- The power key and the voice assistant's overlay are system windows an accessibility service can't hold back.
- If the TV is offline (no Wi-Fi, or a router schedule that cuts it), the phone can't reach it.

Tested on a SHARP AQUOS-TVX19B (Android TV 11, MediaTek, 1.4 GB RAM).
