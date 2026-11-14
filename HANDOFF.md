# Ronin Browser 3 — HANDOFF 8 (14 Nov 2026, 3.8.0 published)

Owner: Vishwas (Hindi; both hands injured: fewest taps/typing). Not a coder: explain in Hindi, technical words in English.
Current version: **3.8.0** published (Windows + Android). Rollback: 3.7.1 / 3.7.0 / 3.6.1 releases on GitHub; Windows
RoninSetup-3.5.1 / 3.4.0 in Downloads\Ronin Browser 3 Handoff.
Source of truth: GitHub repo vishwas9215-star/ronin (public). Feature list + test history: FEATURES.md.

## Owner's standing rules (must follow)
1. Test EVERYTHING myself before the owner sees it (emulator phone + tablet screenshots, laptop UI tests, real sites);
   an independent QA agent reviews every release before asking to publish. He should never find bugs first.
2. No paid AI / no extra charges (Gemini free key only). No iPad.
3. Install only inside C:\Ronin Ecosystem. Never touch: `Ronin`, `EMERGENCY-BACKUP`, `Ronin PRO`, `Ronin PRO-backup-*`.
4. Never read/stage `algo-key.pem`, `Algo-key.pem old`, `Dhan-Algo-Server.rdp`.
5. No order placement. Never type passwords (owner logs in himself).
6. Never show Proton .conf private keys, the Gemini key or RONIN_KEYS_PASS.
7. No other browser brand names anywhere in the product.
8. Everything auto-updates; one version per release; live publish only when he names the version ("हाँ, V3.7.0 publish करो").
9. No features that help piracy (asked once for auto-following movie-site domains: declined).

## Build / release
- Linux: `./build.sh` = Go tests + JS tests + Playwright UI tests (passwords, voice, listen, extras, newtab, popguard,
  tableexport, tableexport2, darkcsp, merge_rules, toolsbutton, merge, pdftools) -> Ronin.exe + RoninSetup.exe. Android builds only on GitHub (SDK blocked here).
- Push to main -> build.yml: windows, android (unit tests + signed APK), android-device matrix phone (API 35) and
  tablet (pixel_tablet API 36): DeviceTest (blocking) then SiteTourTest (14 real sites, report only - the emulator
  can die on heavy sites). Screenshots -> prerelease "qa" (download: github.com/.../releases/download/qa/<name>.png).
  Crash log in the step summary.
- Publish: `gh workflow run build.yml --ref main -f publish=true` (tag pushes blocked by the git proxy).
- Keys: keys/ronin-keys.tar.enc, password only in secret RONIN_KEYS_PASS + owner's backup txt. Never read/print.
- lists.yml twice a day: release "lists" = adblock-domains.txt.gz + threat-domains.txt.gz (phishing/malware).
- Never push to main while a publish run is going: the concurrency group cancels it.
- Check YAML locally before pushing (`python3 -c "import yaml;..."`): a ": " in a step name broke CI once.

## Tablet (OnePlus Pad OPD2415, Android 16, adb 3b44b9f5)
- Screen on laptop: owner double-clicks `Downloads\Ronin Browser 3 Handoff\tablet\TABLET-SCREEN.bat`
  (scrcpy, `--no-audio` so sound stays on the tablet). Claude cannot run .bat files (classifier) - ask him once.
- Control via computer use, app "Scrcpy" (full tier), window "Ronin Tablet"; maximise it (double-click title).
  Screenshot coordinates: give frame coordinates (image px / scale). alt+h Home, alt+s recents, alt+n panel.
- Install new APK: unique file name each time (same-path commits were cached), verify size with device_list_dir.
  Better: Ronin menu -> "Update देखें" on the tablet, tap Install via scrcpy.
- Media card shows in OxygenOS "fluid cloud" pill at the top (tap it), not the notification list.

## New in 3.6.2 / 3.7.0 (code map)
- Background YouTube next song: RoninWebView overrides onVisibilityChanged; keepPlaying stays on for the media tab
  (rests after 30 min idle); rAF fallback in PageScripts.BACKGROUND_PLAY; wake lock kept 90 s between songs.
- Danger protection: adblock.Lists Threat=true (malware-filter, phishing-filter), Engine.LoadThreat/Dangerous,
  TestThreat "ronin-phishing-test.com". Windows: red overlay (toolbar blocked mode DANGER), SmartScreen forced
  (Settings8 idx 36). Android: stopPage/dangerHtml via shouldInterceptRequest + redirects, Safe Browsing on,
  Lookalike.kt (port of safemoney/lookalike.go), one-tap "I know this site" only for the red page showing it.
  Settings/Menu "Safe sites" = TrustedSites.
- Tabs (Android): strip with chips + pinned "+" (newTabButton). New tab dark background (both platforms).
- Windows frame: title = Ronin name only; icon group #1 (was #2 = generic icon); dark frame in Night mode.
- Quick links: internal/quicklinks (Defaults incl. School: Manav Sampada ehrms.upsdc.gov.in, Prerna prernaup.in,
  UDISE+ All Modules udiseplus.gov.in/#/en/page/oldlink/109; Trading: Ronin Terminal, Dhan, TradingView, Fyers),
  ui/quicklinks.js shared, add/remove via https://ronin.newtab/add|del?k=<key> (key per start, CSP nonce).
- Pop-up guard in ui/inject_adblock.js (link-click hijack + invisible overlay links; # / javascript: links and
  buttons never blocked; banks/payments/gov allowed).
- Data -> Excel + PDF: ui/table_export.js (shared collector: paging, colspan headers, checks), internal/tablexport
  (Go xlsx + report, A4 landscape PrintToPdf with print settings), data_windows.go; Android TableExport.kt
  (xlsx + PdfDocument), saved in Downloads/Ronin. Per-site switch (Menu / Settings DataSites); separate dataKey.
- CSP pages: dark mode and ad hiding use constructed style sheets (UDISE+ sign-in forbids <style>).
- Android UA: "wv" removed (Google sign-in); desktop UA for docs.google.com and web.whatsapp.com; accounts.google.com
  keeps the current one. targetSdk/compileSdk 36, AGP 8.11.1.

## 3.7.1 / 3.8.0 (both published)
- 3.7.1: Data export marks: no lowercase mark; child repeated only by name + father/DOB; reason in the first
  column "जाँच" (colour-prefixed); PDF drops constant columns (owner's Prerna list 29 -> 3 pages).
- 3.8.0 Ronin Tools = a 🧰 Tools button (NOT a tab: hidden from the tab strip, "✕ Back to browser" ->
  https://ronin.data/close?k=toolsKey). Categories Data / PDF / Photo, English tool names (owner asked).
- Data Match (cmd/ronin/ui/tools/merge.js, inside the tools page; saves via ronin.data/merge?k=toolsKey):
  several Excel/CSV files -> one row per child/teacher. Kinds: diff (blue), only (grey, "form नहीं भरा"),
  similar (orange, match not sure), dup (yellow, newest form answer used). Google Form files (Timestamp) are
  separate sources; 2-of-3 agreement -> "शायद सही". PDF input NOT supported (asks for Excel).
  Data JSON gained info / legend / printCols / printNote (Go tablexport + Kotlin TableExport).
- 3.8.0 PDF/Photo tools (cmd/ronin/ui/tools/, pdf.js 4.10 legacy + pdf-lib 1.17): https://ronin.home/tools/
  served from memory (Windows serveTools in WebResourceRequested; Android shouldInterceptRequest from assets).
  Save hand-over: page -> https://ronin.data/save?k=toolsKey -> native reads window.__roninSave.part(i).
- Data Match is generic: no name column -> matched on the shared most-unique column (code/number); same-column
  unlabelled files join only when their rows do not overlap (pieces), else compared (versions).
- Windows updates: checks at start + every 6 h, never installs silently: "नया Ronin X आया है [Update करें]" -> install ->
  "[अभी Restart करें]"; Menu "Check for updates". (Owner: no hourly checks, ask before updating.)
- Tests: tools/uitest/merge.js (fixtures in tools/uitest/fixtures, made-up), pdftools.js (fixtures from
  make_tool_fixtures.py), DeviceTest merge + tools self-test.
- Owner's real Prerna file checked locally only (never committed): fake UDISE with 6 planted changes -> all 6 found.

## Agreed for 3.9 (not started)
- Photo -> Excel in the department's format: offline OCR first (Android ML Kit / Windows OCR), Gemini free only
  when he presses it, with a warning that the photo goes to Google.
- Easy form-link for teachers (big buttons, Hindi, voice), answers into HIS Google Sheet; then डेटा मिलाओ.
- Declined: auto-answering training quiz Google Forms from the internet. Offered: voice ticking ("दूसरा विकल्प").

## Later
Android: VPN, own password vault (tablet now uses Google Password Manager via Android autofill - check it saves on
school sites), video download, Kids, study time, sync laptop <-> tablet. TV app (YouTube only, TCL 65").
Windows code signing ("Unknown publisher"): Microsoft Artifact Signing $9.99/month, India individual eligibility not
confirmed - owner's decision. Android developer verification: not in India yet (global 2027) - needs his identity.
- Publish note: the background-song notification device test can flake once on the phone emulator; `gh run rerun <id> --failed` re-runs it and then the release.
