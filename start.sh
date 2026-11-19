#!/bin/bash
# Installs Mouse Bridge on the TV, starts it, and turns on auto-start at boot.
# Usage: ./start.sh [adb-serial] [sensitivity]     e.g. ./start.sh 192.161.0.109:5555 1.2
# Needed once (and after changing the code or the sensitivity); the TV then starts it by itself
# on every boot.
set -euo pipefail
cd "$(dirname "$0")"
ADB=(adb)
[ -n "${1:-}" ] && ADB=(adb -s "$1")
SENS="${2:-1.0}"
APK=build/mousebridge.apk
PKG=com.tvtools.mousebridge
[ -f "$APK" ] || ./build.sh

"${ADB[@]}" install -r "$APK" >/dev/null
"${ADB[@]}" shell appops set $PKG SYSTEM_ALERT_WINDOW allow
# Options the TV uses when it starts the daemon at boot.
"${ADB[@]}" shell "echo '--sensitivity=$SENS' > /data/local/tmp/mousebridge.args"

# Start the daemon now (same command the app uses at boot).
"${ADB[@]}" shell 'p=$(pidof mousebridge); [ -n "$p" ] && kill $p; sleep 0.5; true'
"${ADB[@]}" shell 'A=$(cat /data/local/tmp/mousebridge.args); APK=$(pm path com.tvtools.mousebridge | head -1 | cut -d: -f2);
  setsid nohup app_process -Djava.class.path=$APK /system/bin --nice-name=mousebridge com.tvtools.mousebridge.Daemon $A \
  > /data/local/tmp/mousebridge.log 2>&1 < /dev/null &'
sleep 2
if ! "${ADB[@]}" shell pidof mousebridge >/dev/null; then
  echo "Mouse Bridge failed to start:"; "${ADB[@]}" shell cat /data/local/tmp/mousebridge.log; exit 1
fi
echo "Mouse Bridge running. Log: adb shell cat /data/local/tmp/mousebridge.log"

# Enable auto-start and make sure the app may use the TV's own adb (needed to start at boot).
"${ADB[@]}" logcat -c
"${ADB[@]}" shell am broadcast -n $PKG/.BootReceiver -a $PKG.ENABLE >/dev/null
echo "Checking boot auto-start... if the TV shows 'Allow network debugging?', tick"
echo "'Always allow from this computer' and choose Allow (needed only once)."
for i in $(seq 1 45); do
  line=$("${ADB[@]}" logcat -d -s MouseBridge:I MouseBridge:W | grep -E "self-adb (ok|failed) \(check\)" | tail -1 || true)
  if [ -n "$line" ]; then break; fi
  sleep 2
done
case "$line" in
  *"self-adb ok"*) echo "Auto-start at boot: enabled." ;;
  *) echo "Auto-start at boot: NOT confirmed (${line:-no answer}). Run ./start.sh again and accept the prompt on the TV." ;;
esac
