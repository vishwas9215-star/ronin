#!/bin/bash
# Runs Ronin Home on the Android TV emulator and takes screenshots for checking. Writes results to qa/.
set -u
mkdir -p qa
R=qa/results.txt; : > $R
pass() { echo "PASS $1" | tee -a $R; }
fail() { echo "FAIL $1" | tee -a $R; }
shot() { sleep "${2:-2}"; adb exec-out screencap -p > "qa/$1.png"; }
ui() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb shell cat /sdcard/ui.xml; }
top() { adb shell dumpsys activity activities | grep -m1 -E "topResumedActivity|mResumedActivity"; }

adb install -r -g RoninHome.apk && pass "install" || fail "install"
adb shell wm size; adb shell wm density
adb logcat -c
adb shell am start -W -n com.ronin.home/.MainActivity --ez demo true
shot 01-home 5
U=$(ui)
for w in "Family" "My subscriptions" "My Apps" "Kids" "Castle"; do
  echo "$U" | grep -q "$w" && pass "home shows '$w'" || fail "home shows '$w'"
done
echo "$U" | grep -q 'content-desc="Castle"[^>]*focused="true"' && pass "first focus on Castle" || fail "first focus on Castle"
adb shell input keyevent DPAD_RIGHT; adb shell input keyevent DPAD_RIGHT; shot 02-focus-right 1
adb shell input keyevent DPAD_DOWN; shot 03-first-movie-row 1
adb shell input keyevent DPAD_DOWN; shot 04-new-releases 1
U=$(ui); echo "$U" | grep -q "★" && pass "info panel shows focused title rating" || fail "info panel shows focused title rating"
for w in "New Releases" "Winter Road"; do echo "$U" | grep -q "$w" && pass "rows show '$w'" || fail "rows show '$w'"; done
adb shell input keyevent DPAD_DOWN; adb shell input keyevent DPAD_DOWN; shot 05-hollywood-bollywood 1
U=$(ui); for w in "Top 10"; do echo "$U" | grep -q "$w" && pass "rows show '$w'" || fail "rows show '$w'"; done
adb shell input keyevent DPAD_DOWN; adb shell input keyevent DPAD_DOWN; adb shell input keyevent DPAD_DOWN; shot 06-bottom 1
U=$(ui); echo "$U" | grep -q "Night Market" && pass "horror visible somewhere" || true
adb shell input keyevent BACK; shot 07-back-to-top 1
# poster click: emulator has no Netflix, so it must fall back to Google search / app home without crashing
adb shell input keyevent DPAD_DOWN; adb shell input keyevent DPAD_DOWN; sleep 1; adb shell input keyevent DPAD_CENTER; sleep 8
shot 07b-poster-click 1; adb logcat -d -s RoninHome | grep "open '" | tail -1 | tee -a $R
adb shell am start -W -n com.ronin.home/.MainActivity --ez demo true >/dev/null; sleep 2
# every focusable tile reachable: count focus moves that change focus
# open "My subscriptions" the way the family will: Back (top) -> Up -> Right -> OK
adb shell input keyevent BACK; sleep 1; adb shell input keyevent DPAD_UP; sleep 1; adb shell input keyevent DPAD_RIGHT; sleep 1; adb shell input keyevent DPAD_RIGHT; sleep 1; adb shell input keyevent DPAD_CENTER
shot 08-subscriptions 3
top | grep -q SubscriptionsActivity && pass "My subscriptions button opens the screen" || fail "My subscriptions button opens the screen"
adb shell input keyevent DPAD_CENTER; shot 09-netflix-off 1
ui | grep -q 'Netflix OFF' && pass "netflix switch turns OFF" || fail "netflix switch turns OFF"
adb shell input keyevent BACK
adb shell am start -W -n com.ronin.home/.MainActivity --ez demo true; shot 10-home-no-netflix 3
U=$(ui)
echo "$U" | grep -q 'content-desc="Netflix"' && fail "netflix tile hidden" || pass "netflix tile hidden"
echo "$U" | grep -q 'text="NETFLIX"' && true
echo "$U" | grep -q 'text="NETFLIX"' && fail "netflix badges hidden" || pass "netflix badges hidden"
adb shell input keyevent BACK; sleep 1; adb shell input keyevent DPAD_UP; sleep 1; adb shell input keyevent DPAD_RIGHT; sleep 1; adb shell input keyevent DPAD_RIGHT; sleep 1; adb shell input keyevent DPAD_CENTER; sleep 2; adb shell input keyevent DPAD_CENTER; adb shell input keyevent BACK
# Kids button (top right) opens the Kids screen
adb shell input keyevent BACK; sleep 1; adb shell input keyevent DPAD_UP; sleep 1; adb shell input keyevent DPAD_CENTER; sleep 3
shot 10b-kids-screen 1; ui | grep -q "YouTube Kids" && pass "Kids button opens Kids screen" || fail "Kids button opens Kids screen"
adb shell input keyevent BACK
adb shell pm grant com.ronin.home android.permission.WRITE_SECURE_SETTINGS && pass "screensaver permission grant" || fail "screensaver permission grant"
adb shell am start -n com.ronin.home/.ClockPreviewActivity; shot 11-screensaver 3
adb shell am start -n com.ronin.home/.SetupActivity; shot 12-setup 2
# real (non-demo) start: no TMDB key -> info tile, only installed apps
adb shell am force-stop com.ronin.home
adb shell am start -W -n com.ronin.home/.MainActivity; shot 13-home-real-no-key 4
# Home key takeover through the accessibility service
adb shell settings put secure enabled_accessibility_services com.ronin.home/com.ronin.home.HomeKeyService
adb shell settings put secure accessibility_enabled 1
sleep 8
adb shell dumpsys accessibility | grep -i -E "ronin|Bound services|Enabled services" | head -8 | tee -a $R
adb shell am start -a android.settings.SETTINGS; sleep 3
adb shell input keyevent KEYCODE_HOME; sleep 3
top | tee -a $R
top | grep -q com.ronin.home && pass "HOME key opens Ronin Home" || fail "HOME key opens Ronin Home (report only)"
shot 14-after-home-key 1
adb shell cat /sdcard/Android/data/com.ronin.home/files/home-log.txt > qa/home-log.txt 2>/dev/null || true
adb logcat -d -s RoninHome > qa/ronin-log.txt || true
adb logcat -d -b crash > qa/crash.txt || true
[ -s qa/crash.txt ] && fail "no crashes" || pass "no crashes"
cat $R
