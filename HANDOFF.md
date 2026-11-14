# Ronin Browser 3 — HANDOFF 6 (14 Nov 2026, after 3.6.0 = first Windows + Android release)

Owner: Vishwas (Hindi; hands injured: fewest clicks/typing). Not a coder: explain in Hindi, UI text in English.
Current version: **3.6.0** (Windows + Android, one release). Windows 3.6.0 installed on the laptop and its
update check says "Up to date" (reads the signed manifest from GitHub).
Source of truth: **GitHub repo vishwas9215-star/ronin (public)**. Rollback for Windows: RoninSetup-3.5.1.exe /
3.4.0 in Downloads\Ronin Browser 3 Handoff.
Feature list + test history: FEATURES.md (bottom = latest).

## Owner's standing rules (must follow)
1. Test before every hand-over; independent review for money/data/passwords work.
2. No paid AI / no extra charges (Gemini free key only). No iPad (Apple fee).
3. Install only inside C:\Ronin Ecosystem. Never touch: `Ronin`, `EMERGENCY-BACKUP`, `Ronin PRO`, `Ronin PRO-backup-*`.
4. Never read/stage `algo-key.pem`, `Algo-key.pem old`, `Dhan-Algo-Server.rdp`.
5. No order placement. Never type passwords (owner logs in himself).
6. Never show Proton .conf private keys or the Gemini key.
7. No other browser brand names anywhere in the product.
8. Everything auto-updates; one version per release; live publish only when he names the version.

## Build (Linux)
- Go 1.24, llvm-rc, Node + playwright. `./build.sh 3.5.1` runs Go tests, JS rule tests and the UI tests
  (passwords, voice, listen, extras) and makes build/Ronin.exe + build/RoninSetup.exe.
- Other UI tests: tools/uitest/run.js <dir>, downloads.js, tv.js, theme.js, panel.js, tabsearch.js,
  night/t.js, playerads/t.js (timing-flaky, rerun once).
- Signed updates: `go run ./tools/release` with the key from RoninBrowser3-release-key.zip.
- Laptop install via computer use: Explorer (click tier) -> double-click setup -> setup window may hide behind:
  click the green R in the taskbar -> grant "roninsetup-x.exe" -> Yes -> Yes. Close Ronin windows first.

## New in 3.5.0 (code map)
- Video call window: containers.Calls ("CALLS"), safemoney.Call + callDomains; no VPN / no WebRTC limit.
- extras_windows.go: History (internal/history), page size per site (settings.ZoomSites; zoomChanged also from
  timer because the engine's ZoomFactorChanged event did not arrive on the laptop), Study time (internal/focus).
- Mic: win32 startVoiceTyping (Win+H) + toolbar voice.* (auto-open 1.8 s after words stop).
- Read aloud: ui/reader.js player() (speechSynthesis, Hindi voice if Windows has one).
- Passwords: internal/vault (DPAPI file passwords.dat), passwords_windows.go, ui/inject_pass.js (capture, in memory
  only; not in Private/Safe Money/Kids), ui/fill_pass.js (location.origin check first). Windows Hello via
  PowerShell UserConsentVerifier before show/remove/import/export.
- Bookmarks .html export/import (settings.BookmarksHTML / ParseBookmarksHTML).

## Open items
- Owner to try: Windows Hello prompt on "Show passwords"; save + fill on one real login; camera/mic in a real call;
  add Windows Hindi voice (Settings -> Time & language -> Speech -> Add voices -> Hindi).
- Video call window opens behind the current window (Windows focus rule) - cosmetic.
- Google passkey sign-in: engine limit, use "Try another way".
- Not tried live: Safe Money idle logout / clipboard clear / remote-tool warning, Kids, sleeping tabs.

## Release system (since 3.6.0)
- Repo: https://github.com/vishwas9215-star/ronin. Claude GitHub App installed for this repo only (owner approved).
  The session's git proxy allows branch pushes but NOT tag pushes (HTTP 403).
- VERSION file = one version for Windows + Android. Push to main -> build.yml: Windows build.sh (all Go + JS + UI
  tests), Android unit tests + signed APK, Android 14 emulator device tests; test lists appear on the run page.
- Publish = Actions -> build -> Run workflow -> tick "Publish this VERSION" (only when the owner names the version,
  e.g. "हाँ, V3.6.1 publish करो"). The release job signs manifest.json (Windows) and writes android.json.
- Keys: keys/ronin-keys.tar.enc (AES-256, password only in secret RONIN_KEYS_PASS + owner's
  RONIN_KEYS_PASS-BACKUP.txt in the handoff folder). Never read/print the password.
- ad-lists.yml: daily public lists -> release "lists" (adblock-domains.txt.gz, ~357k domains) for Android.
- Android app (android/): Kotlin WebView app com.ronin.browser. Rules.kt + AdBlocker.kt use rules.json exported from
  the Go code (tools/exportlists) = same bank/trading/call/ad rules. YouTube player mode = PageScripts.BACKGROUND_PLAY
  + RoninWebView.keepPlaying + MediaPlaybackService (MediaSession notification / lock screen / car buttons).
  Safe Money = FLAG_SECURE + own WebView profile. Update = android.json from latest release (1 tap install).

## Open after 3.6.0
- Owner to install Ronin.apk on his Motorola phone + Android tablet and try: YouTube song, swipe app away, lock
  screen controls, car Bluetooth; voice search; Safe Money; Check for updates.
- Not yet in Android: VPN, passwords, video download, Kids, study time, page-size memory, sync, TV app.
- Owner's laptop has C:\Ronin Ecosystem\EMERGENCY-BACKUP\tablet-tools (scrcpy/adb from an earlier session) -
  rule 3: never touch that folder.

## NEXT PHASE (agreed with owner 14 Nov) — Android
1. GitHub: owner approved that Claude creates repo "ronin" in his account (vishwas9215-star) in the built-in browser
   (he is logged in; Claude never types passwords). GitHub Actions builds Ronin.exe + Ronin.apk with ONE version and
   publishes one release = update channel for both (Windows silent; Android shows 1-tap "Install" - Google rule).
   Android SDK cannot be downloaded in Claude's workspace (dl.google.com / maven blocked) -> build on GitHub.
2. Android app (phone Motorola + Android tablet, one APK, tablet layout): Android WebView shell (Kotlin) sharing the
   JS (adblock, dark, scam, reader, passwords) and Go core via gomobile where useful (adblock lists, vault rules, VPN).
   Native: voice search (SpeechRecognizer, Hindi), read aloud (TextToSpeech), FLAG_SECURE for Safe Money.
3. YouTube player mode (owner's top wish): background play after swipe / screen off, notification + lock-screen
   controls (prev/play-pause/next/close) via MediaSession + foreground service, car Bluetooth / Android Auto buttons,
   no ads, autoplay next. Honest note given: YouTube's terms disallow this without Premium; site changes may need
   rule updates.
4. TV app "Ronin TV" (YouTube only) for his 65" TCL TV - only if it is Google TV / Android TV (check Settings -> About).
   HDMI cannot install apps: install via pen drive / "Send Files to TV" / Wi-Fi debugging.
5. Sync laptop <-> phone: bookmarks (+ passwords, encrypted) through his Google Drive.
