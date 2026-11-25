#!/bin/bash
# Ronin YouTube + Ronin Music on a phone emulator: install, open, screenshots at the owner's phone size, car test.
set -u
mkdir -p qa; R=qa/results.txt; : > $R
pass() { echo "PASS $1" | tee -a $R; }; fail() { echo "FAIL $1" | tee -a $R; }
shot() { sleep "${2:-2}"; adb exec-out screencap -p > "qa/$1.png"; }
top() { adb shell dumpsys activity activities | grep -m1 topResumedActivity; }
SIZE="${PHONE_SIZE:-}"; DENS="${PHONE_DENSITY:-}"
[ -n "$SIZE" ] && adb shell wm size "$SIZE"; [ -n "$DENS" ] && adb shell wm density "$DENS"
adb shell wm size | tee -a $R; adb shell wm density | tee -a $R
for a in RoninYouTube RoninMusic; do adb install -r -g $a.apk && pass "install $a" || fail "install $a"; done
for p in com.ronin.youtube com.ronin.music; do
  adb shell am start -W -n $p/com.ronin.browser.SiteActivity >/dev/null
  shot "$p-open" 25
  top | grep -q "$p/" && pass "$p opens" || fail "$p opens"
  adb shell input keyevent 3; sleep 2
  adb shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER $p | tail -1 | tee -a $R
done
adb shell dumpsys package com.ronin.music | grep -q "android.media.browse.MediaBrowserService" && pass "music has car service" || fail "music has car service"
adb shell monkey -p com.ronin.music 1 >/dev/null 2>&1; sleep 3; shot launcher-icons 1
adb shell input keyevent 3; sleep 1; shot home 1
adb logcat -d -b crash > qa/crash.txt || true
[ -s qa/crash.txt ] && fail "no crashes" || pass "no crashes"
cat $R
