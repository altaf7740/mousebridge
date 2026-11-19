#!/bin/bash
# Stops Mouse Bridge and turns off auto-start at boot; the mouse goes back to the TV's normal
# (broken-click) handling. ./start.sh turns it back on.
# Usage: ./stop.sh [adb-serial]
ADB=(adb)
[ -n "${1:-}" ] && ADB=(adb -s "$1")
"${ADB[@]}" shell am broadcast -n com.tvtools.mousebridge/.BootReceiver -a com.tvtools.mousebridge.DISABLE >/dev/null 2>&1 || true
"${ADB[@]}" shell 'p=$(pidof mousebridge); [ -n "$p" ] && kill $p; true'
echo "Mouse Bridge stopped; auto-start at boot disabled."
