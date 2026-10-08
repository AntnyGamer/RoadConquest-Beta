#!/usr/bin/env bash
set -euo pipefail

apk="$1"
out="$2"
mkdir -p "$out"
adb install -r "$apk"
for launch in 1 2; do
  adb shell am force-stop com.roadconquest.app
  adb logcat -c
  adb shell am start -W -n com.roadconquest.app/.DefaultLauncher > "$out/launch-$launch.txt"
  cat "$out/launch-$launch.txt"
  sleep 10
  adb logcat -d -s AndroidRuntime:E > "$out/logcat-$launch.txt"
  cat "$out/logcat-$launch.txt"
  adb shell dumpsys activity activities > "$out/activities-$launch.txt"
  grep -q 'Status: ok' "$out/launch-$launch.txt"
  if grep -q 'Process: com.roadconquest.app,' "$out/logcat-$launch.txt"; then
    echo 'Release app crashed during cold startup.' >&2
    exit 1
  fi
  adb shell pidof com.roadconquest.app
done
