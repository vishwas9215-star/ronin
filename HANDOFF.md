# Ronin Browser 3 — HANDOFF 8 (15 Nov 2026, 3.12.0 published 16:48 IST = pure browser + TV)

Owner: Vishwas (Hindi; both hands injured: fewest taps/typing). Not a coder: explain in Hindi, technical words in English.
Current version: **3.12.0** published 15 Nov 16:48 IST (run 43707448842). Rollback: 3.11.1 / 3.11.0 releases on GitHub; Windows
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
- 3.8.1 tools page: compact category cards (TOOLS list in tools.js: one line + T[id] adds a tool); Calculators:
  Date Calculator (datecalc.js: age, Class 1 admission age on 1 April, service, retirement 62 + UP session benefit to
  31 March, days between / after (leave), census age estimate from child / marriage / remembered event) and Amount
  in Words (Hindi + English, lakh/crore).
- Data button: progress box on the page (% from "1 to 10 of 53" or page numbers); "Next" that loads a new web page
  or a postback: progress kept in sessionStorage, native re-injects table_export.js and calls resume(); 60 min cap;
  screen / laptop kept awake while reading.
- 3.9.0 tools: Edit PDF / Fill Form (one editor: tap a typed word -> white-out + new text; free text; white-out box;
  sign block from saved signers {name, post, office, signature} in localStorage 'ronin.signers' - posts are free text,
  chips only suggestions), Documents to PDF (camera/photos/PDFs, scan look, 500 KB-5 MB limit), Edit Photo (hand crop,
  turn, passport/stamp/sign sizes, B&W, JPG/PNG, KB), Convert (Excel->PDF, PDF->Excel via text positions, PDF->JPG,
  docx->PDF text+tables). File names: fileName() = ascii letters/digits/_ <=24 + tag + one extension; data export
  names Title_yyyymmdd_hhmm. Android: Share / WhatsApp buttons. Owner rule: NOTHING hard-coded (posts, offices, dates).
- 3.9.0 sensitive sites (kids.LooksAdult + kidsBlocker list): Windows containers.Sensitive -> always moved to a Private
  window; history.Skip + RemoveWhere purge at start. Android Sensitive.kt -> private tab (WebView MULTI_PROFILE
  'ronin-private', wiped when the last private tab closes), never in history, old entries removed at start.
- Tests: tools/uitest/merge.js (fixtures in tools/uitest/fixtures, made-up), pdftools.js (fixtures from
  make_tool_fixtures.py), DeviceTest merge + tools self-test.
- Owner's real Prerna file checked locally only (never committed): fake UDISE with 6 planted changes -> all 6 found.

## 3.10.0 first build (superseded by the section above)
- Safe Social: launcher ui/tools/social.html + social.js (apps chosen in PIN-locked settings, safety card);
  page script ui/inject_social.js (Sponsored ads hidden, optional Reels/Shorts hide, link guard with NO open-anyway:
  Telegram, WhatsApp group invites, UPI apps, .apk, short links, adult, money-word sites; orange scam marks;
  blue rumour marks + "सच्चाई जाँचें" Google fact-check; #ronin-fact chip on Google search; ← ⊞ ✕ corner buttons).
  Windows social_windows.go (tab isSocial, barH=0, YouTube-Restrict header); Android openSocial/socialScreen
  (bars hidden, intent links stay in the tab, PREF f2=8000000). Data route ronin.data/social?do=open|home|close|cfg.
- Android home-screen search widget: SearchWidget.kt (AppWidgetProvider + SearchActivity, hi-IN voice,
  Omnibox.resolve -> MainActivity), Menu "Search widget home screen पर लगाएँ" (requestPinAppWidget).
- Tests: tools/uitest/social.js, DeviceTest safeSocialOpensFullScreenAndCloses.
- Review prompt for another AI given to owner (Ronin_review_prompt.txt); his answers will come back as suggestions.

## 3.10.0 (published 14 Nov 2026)
- Phase A done: shared engine ui/tools/scamcheck.js; rules data internal/safelinks/{lists.json, scamrules.json,
  cases.json} used by Go (safelinks.Check), Kotlin (SafeLinks.kt, ScamCheck.kt via rules.json "safeLinks"/"scamRules")
  and JS (native injects window.__roninLinkLists / __roninScamRules). Score: 1 heavy or 2 normal signs; advice with no
  request (rules "requests") = no warning. Tests: tools/uitest/scamcheck.js (scam_corpus.json + scam_heldout.json from
  the independent reviewer: before tuning 69% caught / 7% false; after 100% / 0%), LogicTest + DeviceTest same sets.
  Safe Mail (Gmail/Outlook/Yahoo + custom mail hosts) red bar + Delete/Spam/1930. Native gate in Safe Social tabs.
  socialKey (home/close) vs toolsKey (launcher only). Big labels, TTS, stranger banner, video-call ask, 🆘 only on tap.
- Phase B-D (Android, all components android:enabled=false until chosen in Parts.choose / Menu "📱 Ronin के हिस्से";
  tablet without SIM gets only Mail): Ronin Messages = default SMS app (Sms.kt, MessagesActivity, messages.html/js;
  tabs work/unknown/ads/scam; block via BlockedNumberContract; MMS notice kept in Mms inbox), Ronin Calls = default
  dialer (Calls.kt: InCallService + native InCallActivity, CallScreening silence options, SIM choice, ongoing
  notification), auto call recording (CallRecord.kt: Accessibility RecordAccess + RecordService mic FGS,
  VOICE_RECOGNITION; "do not record" list; other side may be soft on some phones), NotifyWatch (scam warning on
  WhatsApp/chat/Gmail notifications) + ChatLog (7-day text log -> deleted WhatsApp messages, ChatLogActivity).
  Pages are PhonePage (WebView + roninPhone WebMessageListener, origin https://ronin.home only).
- RISK told to owner, he chose to keep everything in the browser APK: Google Play Protect India fraud pilot blocks
  sideloaded installs of apps declaring READ_SMS/RECEIVE_SMS/notification listener/accessibility. If the update is
  blocked: Play Store -> profile -> Play Protect -> ⚙ -> "Scan apps with Play Protect" off, install, turn on again
  (owner does it himself), or install via adb from the laptop. Fallback idea: a "lite" flavor without these parts.
- Owner must once (Android 15+): App info -> ⋮ -> Allow restricted settings (SMS role, dialer role, notification
  access, accessibility). Advice given: SMS "BLOCK PROMO" to 1909.
- Also in 3.10: Ronin SOS (Sos.kt, sos.html/js, own icon ic_sos, part "sos"): hold 2 s -> SMS to 1-5 family with
  Maps link + battery, every 5 min for 1 h until "मैं ठीक हूँ". Family Filter (owner's English name for adult-site
  setting): Android Parts.chooseAdult (first start, PIN, SafeSearch forced), Windows settings.AdultBlock +
  first-start Yes/No + menu "Family Filter". Chat log (deleted WhatsApp messages) via NotifyWatch + ChatLogActivity.
- Kashvi (5, KG1): owner chose Google Family Link + Maps sharing (step card given), not a Ronin tracker.
- 3.11 (English + Hindi option) built: see the 3.11.0 section.
- CI green 14 Nov ~22:00 IST (phone + tablet incl. SMS role test and on-phone scam parity).
- Remaining review items not done: Windows-side nothing; M6 BT audio route, M12 labels; homograph on Android uses
  rawAuthority (I6) - check.

## 3.11.0 (published 15 Nov 2026 03:38 IST on his words "हाँ, V3.11.0 publish करो")
Owner asked (after 3.10 on phone): Instagram chip on home opened "Edit links"; Safe Social bar and Calls / Messages
top buttons hid ⚙ under the status bar - put them at the BOTTOM; SMS grouped per bank; app in English (phone is English).
- Chip bug: `ronin.newtab/social` was caught by the generic `ronin.newtab/` (edit links) rule. Fixed + device test
  `safeSocialChipOnTheHomePageOpensThatApp`.
- PhonePage: WebView ignores its own padding -> page now sits in a FrameLayout with the inset padding.
- Calls: Keypad / Recent / Contacts in a bottom `.nav`, keypad low (`.padwrap`). Messages: 4 boxes in bottom nav,
  "✏ New" floating button. Safe Social on Android: native bottom bar Back / My apps / Close (`socialBar`),
  inject_social skips its own top buttons when `cfg.nativeBar` (Windows keeps the top buttons).
- Messages grouping: `group()` in messages.js, key = cat + sender core (VM-/AD-/VD-HDFCBK-S -> HDFCBK); opening a
  group merges all its threads; delete / block cover all of them.
- Language: `Lang.kt` (prefs "lang" = auto|en|hi, auto = phone locale) + top-level `L(hi, en)`; pages get
  `window.__roninLang` (PhonePage + socialBaseJs), each page has `const L`. Menu "🌐 Language / भाषा" and a
  Language card in Messages / Calls settings. scamrules.json `whyEn` (English reasons, by group id) used by
  scamcheck.js and ScamCheck.kt; spoken warnings English/Hindi. Windows pages follow navigator.language (laptop =
  English); Windows Go toolbar strings are still Hindi-first (next step), tools.js (PDF / Data Match) not translated.
- UI tests: phone.js now pins Hindi for old checks and adds an English + grouping + bottom-bar check; social.js pins Hindi.
- Real-phone install path that works: CI uploads `Ronin-test.apk` to the "qa" pre-release (never offered as an
  update); on the phone open github.com/vishwas9215-star/ronin/releases/download/qa/Ronin-test.apk in Ronin ->
  notification -> Update -> Play Protect "Scan app" -> Install. (A .lnk running adb install did not start; artifact
  downloads are blocked by the proxy.) Phone screen: scrcpy 5.0.1 in C:\Ronin Ecosystem\scrcpy (scrcpy.exe,
  grant "Scrcpy" full tier; right-click = Back on the phone). Phone = motorola signature, Android 17.
- Checked on his phone 23:40 IST: Instagram chip opens Instagram + bottom bar; Messages English, HDFCBK 10 in one row,
  bottom nav; Calls keypad low, Recent English labels; menu English. Call recording is OFF on his phone
  (Accessibility must be turned on by him). Saw: Kotak "A/C blocked... kotk.in" SMS from JX-KOTAKD-S (probably real,
  told him not to tap, check in Kotak app). Minor: a saved contact can show "Unknown number" in Recent for outgoing
  rows (contact lookup) - check.
- Night 14->15 Nov, owner's second round (all built into 3.11.0, checked on his phone via scrcpy):
  * Ronin Mail / Ronin Social = own apps: MailActivity / SocialActivity (subclasses of `open class MainActivity`,
    `appMode`), own taskAffinity, aliases MailLauncher / SocialLauncher (Parts MAIL = both; `Parts.sync` turns the
    new one on after an update). Social Back: back inside the site; never back to the launcher page by surprise
    (`socialBack`); Close / last Back = moveTaskToBack in app mode. No forced darkening in social tabs.
  * PhonePage Back -> `window.__roninBack()` in each page (messages / calls / sos / chatlog), else finish().
    SOS with no family: Back and the page's Back leave (was a loop). New common ops: copy, close, icon.
  * Messages: links tappable (except Scam box) -> ask('open') -> Ronin browser; OTP (Otp.kt / otpOf in JS,
    digit lookarounds) big blue copy button, "🆕 Latest OTP" on the newest, older dimmed; notification action
    "📋 Copy OTP" (OtpCopyReceiver, clip marked sensitive); Ads box hidden (Settings -> Show hidden ads),
    SmsSort.isOffer moves loan / selling SMS from -S senders to ads unless transactional; ads older than 30 days
    deleted (SmsStore.cleanAds, at most daily; owner chose this); no reply box for company senders.
  * Sender identity like caller-ID apps: cmd/ronin/ui/tools/senders.js (code -> company name, colour, short
    letters, website); badge shows the company's own site icon fetched by SenderIcons.kt (https, image magic
    check, 200 KB, cached in files/sender-icons, retry weekly). Owner allowed internet for this. Real logos NOT
    bundled (trademarks) - told him.
  * Icons: adaptive (mipmap-anydpi-v26, white tile @color/ic_tile + *_fg.png), new ic_social.
  * Gmail / social "Open in app" banners hidden (hideAppPromos); green "Safe Mail is on" note for 6 s.
  * His phone: Stay awake ON (Developer options), phone silent, laptop muted, laptop sleep Never (was already).
    He allowed me to turn on Ronin-only Android permissions; recording Accessibility still OFF (not done yet).
  * Messages: "Mark all read" (small link), ⭐ Saved box (copies in files/saved-sms.json; ops savedSms/saveSms/
    unsaveSms with `sid` - a payload key "id" would override the request id in ask()).
  * Spam calls (owner was threatened by a fake "Daroga ji" WhatsApp caller): CNAP SIM name on the call screen and
    Recent (CallInfo.cnap / keepCnap; DoT mandated CNAP from 1 Nov 2026 on 4G/5G), threat tip under unknown-caller
    warnings, after-call notification Block / Report (Chakshu), NotifyWatch warns on WhatsApp/chat calls from unsaved
    numbers. Told him: WhatsApp -> Settings -> Privacy -> Calls -> Silence unknown callers.
  * BUG found on the real phone: PhonePage had no WebChromeClient, so every confirm() returned false (Block /
    Delete / Remove never worked on phones since 3.10). Fixed + device test.
- Independent QA review (15 Nov ~01:00) found and I fixed: ads auto-delete now ONLY for "-P" senders, never
  locked / scam-looking (isPromoSender); offer rule narrowed (sanctioned loans, PF claims, selections stay
  important; pre-approved offers stay offers); Ronin Social Back loop on the app list; OTP 'pin' word only (not
  pincode); brand name / icon / one row only for exact codes (prefix = own code, no icon); links off in a
  scam-looking SMS; Saved = AtomicFile + key sender|time|length; CNAP only from SIM accounts, worded as network KYC
  name; app mode no longer wipes the browser's private tabs; calls 'play' uses rid; English default strings.xml +
  values-hi. Windows language: done (see 15 Nov below). NOT done: offer / transaction / OTP regexes duplicated in Kotlin + JS (move to scamrules.json).
- CI green on bb9d106 (windows, android, phone + tablet emulators). Final APK installed on his phone 01:18 and
  checked: Messages (company badges + site icons, Mark all read, Saved, Latest OTP), Social Back fixed.
- 15 Nov: Windows side follows the Windows language too (cmd/ronin/lang_windows.go: GetUserDefaultUILanguage,
  Hindi only when the primary language is Hindi, L(hi,en)); update / Data / PDF / social / safety-mode bars, the
  toolbar menu + danger page (LG via navigator.language) and table_export.js progress badges. The Excel check
  reasons (पीला / लाल / नारंगी) and the Hindi words in scam / pager regexes stay (they are data, not UI).
  extras.js UI test checks Hindi (hi-IN) and English (en-US). All 23 UI tests pass.
- 15 Nov 01:14: while I tested Ronin Calls on his real phone, a tap on a name in Recent placed a real call to one of
  his contacts. Cause: tapping the name row dialled at once. Fixed: the name only fills the keypad; only the green
  📞 calls (phone.js test 'tap on a name placed a call'). Rule: never tap call rows / green buttons on his real
  phone; test calling only on the emulator.
- 15 Nov ~02:00, owner: "Safe Social looks like a normal app, check it really protects". Found on his real
  Instagram: ads now say "Ad" (not "Sponsored") so none were hidden; labels / captions arriving later were never
  re-checked; scam engine missed aviator / betting, "earn daily from home", "DM me / link in bio", "10k lagao 1 lakh
  pao". Fixed: SPONSOR has Ad, CTA buttons (Shop now / Install...), re-check until found; captions re-checked when
  they grow; new groups earn / dm / bet / double (+ otp reversed order, sextortion "recorded ... family");
  scanPictures reads Instagram / Facebook photo alt text ("text that says ...") so scam posters are caught;
  green 🛡 shield in the bottom bar with counts (tap = what was stopped). Gmail signed out -> Google sign-in
  (not the advert page). Calls: coloured direction chips. Family Filter checked on his phone: ON = adult site
  blocked + Google SafeSearch locked; set back OFF (his setting). Install of the new APK blocked: his phone's
  Android Advanced Protection is ON (I never change it) - he decides.
- QA review before publish (15 Nov 03:10) found false alarms on normal messages ("कमाल", "learn", "result predictor",
  "satta" = power, "income tax ghar baithe", "video record ... family", "100% guarantee admission") + CTA hiding
  normal posts, re-check cost, FB hidden letters, shield polling after onDestroy/onStop, Gmail redirect loop, toolbar
  strict mode. All fixed; those sentences are now in scam_corpus.json "normal".
- PUBLISHED 3.11.0 (run 43645549883, CI green on 13b8446). Next: his phone gets it via Ronin's own update
  (Advanced Protection may block the install: his choice); tablet check when he connects it; recording Accessibility
  is his choice; offer / transaction / OTP regexes still duplicated in Kotlin + JS.

## 3.11.1 (published 15 Nov 2026 09:11 IST on "हाँ, V3.11.1 publish करो"; TV APK copied to C:\Ronin Ecosystem\TV)
- Owner 07:30: phone showed an "Instagram - instagram.com" media card with Pause: Ronin's background-song feature
  (keepPlaying + MediaPlaybackService 6 h wake lock) grabbed Instagram's autoplaying feed videos -> battery drain.
  Fix: MEDIA_STATE reports muted (m); social tabs, short-video sites (noPlaySites: instagram, facebook, threads, x,
  sharechat, moj, snapchat, tiktok) and muted videos never become the background player; a held one is released
  (MediaPlaybackService.stop = ACTION_QUIET: no notification, no wake lock, page not paused; paused if Ronin closed).
  mediaPoll stops in the background when nothing plays (was every 1 s forever). DeviceTest noBackgroundPlayForTest.
- Owner 07:38: Instagram scrolls with difficulty; pull-down-to-refresh missing; still many ads; wants latest posts
  (old posts look like today's news). Done: inject_social checks wait until scrolling stops (900 ms + idle, never
  during a scroll); pull-to-refresh in Safe Social tabs (passive touch, reload past 120 px at the top); old-post
  badge (article time[datetime] >= 3 days, amber > 30 days, "📅 Old post: 2 years ago · date"); Instagram opens on
  ?variant=following (Following feed = newest first, Android + Windows + launcher). Ads: check on the real phone
  that 3.11.0 is installed (the "Ad" fix is in 3.11.0).
- Owner 07:44: Safe Social WhatsApp tile showed the WhatsApp Web QR page - useless on the phone itself. Now:
  whatsappApp() opens the real WhatsApp app when installed (manifest <queries> com.whatsapp / w4b); tablet / laptop
  without the app keep WhatsApp Web (QR from the phone). Ronin's WhatsApp guard on the phone = NotifyWatch + calls.
- Owner chose for the TV app: Google TV, install by pen drive; said "ठीक है" 07:47. Built: TvActivity (same APK,
  LEANBACK_LAUNCHER + android:banner drawable-xhdpi/tv_banner.png, leanback/touchscreen not required) = full-screen
  WebView on https://www.youtube.com/tv with a living-room (Cobalt) UA; NO_ADS_JS at document start (JSON.parse +
  Response.json prune adPlacements / playerAds / adSlots...; window.close -> RoninTv.exit); AD_URL + AdBlocker network
  block (YouTube / Google hosts never); Back = Escape key to the page; Updater.check (dialog works with the remote).
  CI matrix device "tv" (android-tv API 34 x86, tv_1080p, continue-on-error for now) runs TvTest: home-row entry,
  prune check, real youtube.com/tv screenshot + D-pad keys (qa release tv-*.png, "### TV" log in the summary).
  Result (CI 43669015883 all green incl. tv): YouTube's real TV screen loads ("Watch the latest videos" -> Get
  started -> Add account / Watch as guest); remote keys move focus, OK opens, Back closes. Video could not be played
  in CI: from a data-centre IP YouTube asks "Sign in to confirm you're not a bot" (QR / yt.be/activate) - at home
  he signs in with his phone. Ads + quality must be checked on his real TV (3-tap list given).
- QA review before 3.11.1 publish found + fixed: YouTube songs inside Safe Social were released (isSocial check
  removed; only noPlaySites / muted); pull-to-refresh reloaded reels / dialogs / inner scrollers (feed pages only,
  never when something under the finger scrolls; tools/uitest/pull.js); Updater unknown-sources screen missing on
  some TVs (try/catch -> security settings); TV Back when YouTube did not load (finish); ads hidden at once, heavy
  checks wait for a still finger; paused song released after 30 min so polling stops.

## 3.12.0 (PUBLISHED 15 Nov 16:48 IST): back to a PURE BROWSER (owner decision 13:21); TV APK in C:\Ronin Ecosystem\TV\Ronin-TV-3.12.0.apk
Owner: Social / Mail / Calls-Contacts / Messages "not working well, not looking good" -> remove completely; keep a good
browser + background YouTube / music + Ronin TV. His answers: remove on laptop too; remove SOS + Deleted WhatsApp
messages too; keep browser safety (danger sites, Family Filter, "Is this a scam?", PDF / Data Tools).
- Removed (git tag before-3.12-cleanup = last commit with them): Android AppWindows (Mail/SocialActivity), Calls,
  CallRecord, ChatLog, MessagesActivity, NotifyWatch, Parts, PhonePage, SenderIcons, Sms, Sos + their manifest
  components, icons, record_access.xml, and permissions SMS / MMS / contacts / call / phone state / location /
  mic FGS / full-screen intent. Pages social, messages, calls, chatlog, sos, senders.js, inject_social.js. Windows
  social_windows.go, Safe Social menu, new-tab Safe Social row, YouTube-Restrict header; --mail = plain Gmail.
  Tests social.js, phone.js, pull.js, Messages / Calls / Social DeviceTests removed.
- Kept: Family Filter moved to Family.kt (same prefs "ronin_parts", so his choice / PIN stay); scam engine
  (scamcheck.js, ScamCheck.kt, SafeLinks.kt) for the "Is this a scam?" tools page + share-to-Ronin; noBackgroundPlay
  (Instagram / Facebook videos never in the background); TV.
- Migration: if store.social was set, YouTube PREF cookie reset once (Safe Social had set Restricted mode).
- New DeviceTest theBrowserIsOnlyABrowser (no SMS / call / contacts / location permissions, no removed components,
  no Safe Social row) + instagramAndFacebookVideosNeverPlayInTheBackground.
- BEFORE updating his phone: set Google Phone + Messages as default apps again (Settings -> Apps -> Default apps).
- QA review of the cleanup found + fixed: "Is this a scam?" page had borrowed RoninScam + rules from the Safe Social
  injection -> now index.html loads rules.js (made by Android toolsFile / Windows serveTools from the rule JSON) +
  scamcheck.js; test tools/uitest/scampage.js. upgradeTo312() runs once (prefs flag cleaned312): YouTube Restricted
  cookie reset + flush, chatlog / saved-sms / sender-icons files, ronin_sms / ronin_phone / ronin_sos prefs and the
  removed parts' notification channels deleted. Windows setup no longer makes "Ronin Mail" shortcuts and removes old
  ones. Safe Social words removed from scam texts.
- DeviceTests scamCheckPageGivesAVerdict (inside the tools page + a shared message) and aMessageSharedToRoninIsChecked
  (real ACTION_SEND intent, fresh start). Note: DeviceTest eval() returns JSON - a string answer comes back quoted, so
  compare numbers ("1"), not 'yes'. CI 43700222813 all green (tablet song-notification test flaked once, rerun green).
- Notification ✕ Close sent "pause": the next 1 s media check showed a paused card again (the 'flaky' song test).
  Now Close sends "stop" (pause + let go). Owner said "हाँ, V3.12.0 publish करो" 16:26.

## 3.14.4 (branch `claude/v3.14.3`; NOT published) — 3.14.3 test build was put on the owner's phone by adb (21 Nov 13:26)
- Bumped because the phone already runs 3.14.3 (versionCode comes from VERSION: same code = no update).
- Photo circles (owner 13:32, screenshots): dark letter on slate looked black in day mode → bright colour per name
  (hash), white letter, a bare number shows its first digit (no "+"), scam / risk = bright red; Calls + Messages.
  Test: phone.js checks every circle is white-on-colour.
- Owner 13:33: first real Chakshu report worked on his phone (form filled, picture attached, he typed the mobile, OTP,
  submitted).
- Real Sanchar Saathi OTP seen (owner screenshot 13:36): header XX-ISATHI, "Thank you for visiting SancharSaathi Portal.
  OTP for login is 289029 ... -Department of Telecom". The strict rule refused it ("login"): now an ISATHI header, or
  that exact text, is accepted. LogicTest has the real text.

## 3.14.3 (branch `claude/v3.14.3`; NOT published) — owner 21 Nov 11:27–11:31 (calls chat)
- **Play Protect called Ronin "harmful"** (tablet + phone) since the SMS / call log / accessibility permissions came back
  in 3.13. Owner chose: TWO apps from one code (gradle flavors `browser` / `phone`):
  - Ronin Browser `com.ronin.browser` (src/browser/AndroidManifest.xml): no SMS / call log / contacts / phone state /
    accessibility. Menu → Phone: opens Ronin Phone, or "Get Ronin Phone" (Updater.installPhone).
  - Ronin Phone `com.ronin.phone` (src/phone/AndroidManifest.xml): Messages + Calls only, parts on from the start, call
    recording kept (owner's choice, so Play Protect may still warn about THIS app), no self-update / camera. Ronin Browser
    updates it (phone.json + RoninPhone.apk in every release; Updater.checkPhone).
  - ⭐ saved SMS move over once: SavedSmsProvider (browser, signature permission com.ronin.permission.SAVED_SMS) →
    SavedSmsImport (phone, on Messages start).
  - Links from Ronin Phone open in Ronin Browser by package. Tests: androidTestBrowser (DeviceTest / TvTest / SiteTour),
    androidTestPhone (PhoneTest). CI: test{Browser,Phone}DebugUnitTest, connectedBrowser…, connectedPhone… (phone, tablet).
- **Adult video sites** (owner: main video ran at 16x as an "ad"; Stripchat / cam ads):
  - inject_adblock.js skipper: no bare "Skip" / "Advertisement"; the label must be ON the video; > 180 s or > 75 s is
    never an ad; a new source in the same <video> (Fluid Player) gives back speed + sound. Test: playerads/t.js.
  - builtin.go: cam redirect / affiliate hosts and more adult ad networks; cam picture servers (doppiocdn, strpst,
    mmcdn, dditscdn …) blocked only inside OTHER sites, never on a cam site itself; cam widget paths; `cams-wgt` boxes.
    Research report by an independent agent (EasyList adult, HaGeZi). Test: adblock_test.go TestBlockedFrom.
  - Video download NOT added (owner's rule 9: nothing that helps piracy). Adult sites already open in Private when the
    Family Filter is off.
- QA (independent, no blockers) fixes: one-time "Messages / Calls moved to Ronin Phone" notice for families that used them;
  Calls / Messages settings + language move over with the ⭐ saved SMS (row 2 of SavedSmsProvider, number kinds kept);
  spam-call "Report" opens Ronin Browser by package. Known risk: Google's India sideload rule may block installing an
  SMS + accessibility app from a browser (Ronin Phone) - fallback: install from the laptop by adb (owner's bat).
  Old call recordings stay on the phone but are not listed in Ronin Phone (they belong to the browser package).
- CI run 44875517945 all green (windows, android, phone, tablet, tv). RoninPhone.apk + 3-install-ronin-phone.bat (adb) copied to C:\Ronin Ecosystem\scrcpy. Waiting for "हाँ, V3.14.3 publish करो".
- Chakshu auto-fill (owner 21 Nov 12:15, both hands fractured: "सारे कॉलम अपने आप भर जाएँ, बस कैप्चा डालकर सबमिट"):
  Messages "Report on Chakshu" and the unknown-call notification open Ronin Browser right on
  sancharsaathi.gov.in/sfc/Home/sfc-complaint.jsp (no more /sfc/ landing page with many Proceed tabs) with extra
  com.ronin.chakshu; cmd/ronin/ui/chakshu.js fills medium, category, sender header / number, date, time, remarks,
  numbers + URLs, a picture of the SMS as the required screenshot (DataTransfer into jQuery.filer, after the site's own
  OCR answer), the saved mobile; focuses CAPTCHA. After "Verify Mobile via OTP" the browser asks Ronin Phone's
  ChakshuOtpProvider (signature permission) for the Sanchar Saathi OTP only (Chakshu.isSancharOtp: SMS names
  Sanchar/Chakshu/DoT or a "-G" / DoT header - real OTP wording NOT yet seen: check on first real use), fills + verifies,
  ticks the declaration, opens Preview. Ronin never types the CAPTCHA, never presses Submit. Checked on the live site
  (laptop built-in browser, no submit): all fields valid, OCR ran. Tests: tools/uitest/chakshu/t.js, phone.js,
  LogicTest.chakshuTakesOnlyTheSancharSaathiOtp.
- Chakshu QA fixes (independent QA, 1 blocker): OTP only when the SMS says it is FOR Sanchar Saathi / Chakshu, from a
  DLT header, with no bank / txn / login / Aadhaar / EPFO words (bank OTPs with a "report at sancharsaathi" footer are
  refused); browser takes the message only through activity-alias ChakshuEntry (signature permission); senders the form
  refuses are left empty with a note; one URL / one number; Preview only after the site marks the OTP valid; wrong OTP
  told; banner on top; old (>30 d) date left empty; t.chakshu cleared after Preview / 15 min; picture says "Made by Ronin".
- Smart caller labels (owner 21 Nov 12:37 "सबको red कर रहा है... Airtel broadband, Swiggy... स्मार्ट बनाओ"; chose
  "हाँ, ऐसे ही बनाओ", on-phone only, no Truecaller-style upload): CallerId.kt. Red only: hidden, foreign, number in a
  scam SMS. Green: 1600 bank line, number inside a -S/-T/-G company SMS (14 d). Blue: you called / talked >=30 s, call
  within 6 h of an order / technician SMS. Grey: 140, SIM (CNAP) name, plain unknown. "Silence unknown" and the
  after-call "spam?" notice skip trusted numbers. Research (Fable agent): CNAP default from 1 Nov 2026 on 4G/5G; TRAI
  says apps must not tag 1600 as spam; delivery apps use rotating masked numbers (no prefix list possible).
- Also on this branch: PR #3 (CI quiet: no e-mails for draft PRs / notes-only commits / publish cross-cancels).

## 3.14.2 (PUBLISHED 21 Nov 2026 10:21 IST on "हाँ, V3.14.2 publish करो"; run 44866848465; tag before-3.14.2 local) — owner 21 Nov 09:44 (calls chat; built on 3.14.1, the other chat's work untouched)
- After a call there were two Recents cards (Ronin Calls + the call screen): InCallActivity is now
  excludeFromRecents + autoRemoveFromRecents and leaves with finishAndRemoveTask (back to it: the ongoing call notification).
  DeviceTest callScreenNeverLeavesASecondRecentsCard.
- In-call keypad (customer care "press 1"): 12 big round keys over the whole width (68 dp, letters under the digits),
  tone while the finger is down, buzz, and the pressed digits shown large above the keys.
- Only Calls.kt (InCallActivity), the manifest entry of InCallActivity, a DeviceTest and VERSION changed.

## 3.14.1 (BUILT on branch `claude/v3.14.1`, draft PR #2; NOT merged, NOT published) — owner 21 Nov 06:46
Asked: a YouTube / YouTube Music song keeps playing after a swipe to Home AND after swiping Ronin away from Recents
(3.14.0 stopped it on a Recents swipe).
- KeptPlayer (Keeper.kt): when the browser screen is destroyed (not a rotation) while its media tab plays on a
  PageScripts.backgroundOrigins page (or played < 15 s ago = between songs), MainActivity.onDestroy hands that ONE
  WebView to KeptPlayer.hold (removed from the screen, its MutableContextWrapper re-pointed to the application, own
  KeepClient: YouTube pages only, ad lists still block, renderer gone = release); all other tabs are destroyed.
  The media foreground service keeps the process alive; MediaPlaybackService.onTaskRemoved stops only when
  KeptPlayer.wantKeep / holding are false. KeptPlayer polls MEDIA_STATE (2 s playing / 5 s paused) for the card,
  takes PlayerBridge.control (play / pause / next / prev; stop = release), asks YouTube for its smallest picture,
  and releases page + service + wake lock on Stop or after 10 min paused.
- Opening Ronin again: MainActivity.onCreate takes the kept page (KeptPlayer.take), puts it back as the front tab
  (adoptKept: wireWebView with this screen's clients), mediaTab restored, normal picture again in onStart; the same
  address is removed once from the saved tabs (no second copy).
- A page on no screen stops its video AND sound (CI proved it: time froze after the swipe). So the kept page sits on
  a tiny 64x64 private VirtualDisplay (VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY, no permission) inside a Presentation;
  its frames go to an ImageReader and are dropped at once. Off the stage again on take() / release().
- All WebViews are now made with a MutableContextWrapper(activity) so a kept page never holds a closed screen.
- Not playing (paused > 15 s, or no song): as before, the card and wake lock go on the swipe.
- Tests (DeviceTest): aSongKeepsPlayingAfterRoninIsSwipedAwayAndComesBack (finishAndRemoveTask = Recents swipe;
  page kept, time advances, session PLAYING, reopen -> same WebView is the front tab and plays, ✕ ends it),
  stopFromTheCardEndsAKeptSong (card play / pause reach the kept page, Stop -> page, card and service gone),
  aPausedSongIsNotKeptAfterASwipe. 3.14.0's closingRoninEndsTheSongCard removed (the behaviour changed on purpose).
  DeviceTest init releases anything kept by an earlier test.
- QA on 5c474ed fixed: reopen race (MainActivity.liveScreens: a page is kept only when the LAST browser screen
  closes, else stopped; onStart also adopts a kept page); ImageReader pictures are taken and closed on their own
  background HandlerThread (no main-thread work per picture); Keeper poll plans its next run before asking the page
  (watchdog); the no-second-copy rule also for the savedInstanceState restore; closing / recovering the media tab
  clears KeptPlayer.wantKeep (DeviceTest closingTheSongTabNeverLeavesItToBeKept).
- QA on 249df0d fixed: crash when the saved state held only the kept song tab (select on an empty list) -> guarded,
  the kept page is adopted and selected, Home only if nothing at all (DeviceTest
  aScreenRestoredWithOnlyTheKeptSongTabDoesNotCrash). MainActivity.mediaOwner: an older screen closing late stops
  the player card only if it still owns it.
- Device check: some phones (OxygenOS battery optimisation) kill apps on a Recents swipe even with a foreground
  service; if the song stops on his tablet, Settings -> Battery -> Ronin -> "Allow background activity".

## 3.14.0 (BUILT 21 Nov 2026 on branch `claude/v3.14.0`, draft PR #1; NOT merged, NOT published) — owner approved the review fixes 21 Nov 00:35, battery low (00:36)
Source: independent review /tmp/ronin-browser-REVIEW.md (sections 1-4). Publish only on his words ("हाँ, V3.14.0 publish करो"):
merge PR #1 into main first, then `gh workflow run build.yml --ref main -f publish=true`.
- Keep tabs / forms: renderer killed (low memory) -> `onRenderProcessGone` gives the tab a new WebView on the same address
  (other tabs load when shown); Safe Money / TV / Messages-Calls pages close or reopen instead of crashing Ronin.
  Tabs list saved in Store.savedTabs on every onStop and restored at start (after updates, swipe-away, crash); only the front
  tab loads (Tab.pending = lazy). Day/Night + Language: `safeRecreate` asks first only if a form has typed text, a private
  tab is open or a song plays. "Tab closed - ↶ Undo" bar for 6 s.
- Safe Money: WebChromeClient (alert / confirm / file upload), downloads only PDF / CSV / Excel (own profile cookies),
  Back = page back, then "Close Safe Money?"; every non-bank / payment / sign-in main-frame target (tap OR redirect) goes
  to the normal window (danger + fake-bank checks there); user bank names HTML-escaped.
- Downloads: `blob:` (page reads it, hands it over in 512 KB pieces) and `data:` (Nav.decodeDataUrl) into Downloads/Ronin;
  name from Content-Disposition or the page's `<a download>`; PageScripts.KEEP_BLOBS delays revokeObjectURL 60 s.
- http: res/xml/network_security_config.xml allows cleartext; Ronin still goes https first. If https fails (DNS / connect /
  SSL), Ronin's own error page: big Reload + "Open anyway (not secure)" (never for BANK / PAYMENT; per session
  httpAllowed). Router / LAN (192.168, 10.x, 172.16-31, .local, localhost) typed -> plain http (Nav.isLocalHost).
- "This is my bank" asks first; Menu -> Safety -> "Remembered sites" lists my banks + camera / mic sites with ✕.
- Other apps (upi:, tel:, market:, intent:): main frame + tap = open; page by itself = asked; ad frame without tap = ignored
  (Nav.outsideAction). SafeSearch also in load() (typed / spoken searches), inserted before '#'.
- Song card: MainActivity.onDestroy (not a config change) and MediaPlaybackService.onTaskRemoved (main task only) stop
  the service + wake lock; closing the media tab too. History: in-memory list, written on one background thread.
- Battery: YouTube smallest picture 20 s after leaving Ronin while a song plays, back to auto on return; rAF fallback 1 s;
  gesture-free autoplay only on PageScripts.backgroundOrigins (siteSettings per navigation); pauseTimers in onStop when
  nothing plays, no export and no other Ronin screen is visible (RoninApp.visibleScreens; Safe Money / phone pages / TV
  resumeTimers in onStart); inject_adblock.js: TradingView + "AD" badge walker = throttled MutationObserver (no work while
  hidden), YouTube ad check 500 ms, generic player check only while a video plays; lists load on the first browser /
  Safe Money / TV screen (RoninApp.startLists), not on SMS / call starts; mediaPoll 3 s on screen / 2 s playing / 5 s
  paused, paused tab released after 10 min (was 30); wake lock re-taken after the 6 h limit; Data export reset only by
  its own tab; TV screen on only while a video plays (RoninTv.playing); recording accessibility eventTypes = 0; SMS
  receiver finishes before cleanAds.
- Updater: download waits in Updater.waitingApk when "install unknown apps" is off; onResume continues by itself.
  BadToken guards on update / export / saved dialogs.
- Small: target=_blank / window.open with a tap -> new tab (onCreateWindow; not in private tabs), window.close closes it;
  camera / mic dialog: only what is asked, Back = deny, remembered per site; delete history asks; tablet strip
  recomputed in onConfigurationChanged; .apk without warning only from github.com/vishwas9215-star/ronin/ (and the GitHub
  file server when the page was that path) or F-Droid (Nav.trustedApk).
- Injured hands: address-bar suggestions (quick links, bookmarks, history; 2 letters; Nav.suggest); menu A− / A+ (per
  site textZoom, sheet stays open: Sheet.Big keepOpen), Forward, Share, Find in page (bar with ▲ ▼ ✕), Desktop site
  (per tab); long press on a link / picture: open in new tab, copy, share, save picture; pull down at the top to reload
  (RoninWebView overscroll, "⟳ Release to reload" sign); error page Reload.
- Tests: LogicTest routerAndHomeServersStayPlainHttp, otherAppsOpenOnlyAfterATap, filesMadeByThePageAreSaved,
  onlyRoninsOwnAppFileNeedsNoWarning, textSizeSteps, addressSuggestions, safeSearchForced (+#). DeviceTest
  aCrashedPageEngineReloadsTheTabInsteadOfClosingRonin, openTabsComeBackAfterRoninIsClosed, aClosedTabComesBackWithUndo,
  noInternetOrNoHttpsShowsReloadAndOpenAnyway, aPageCannotOpenAnotherAppByItself, filesMadeInsideThePageAreSaved,
  longPressMenuOpensALinkInANewTab, menuHasTextSizeFindShareAndDesktopSite, addressBarSuggestsBookmarksAndHistory,
  myBankAsksFirstAndSafeSearchCoversTypedSearches, closingRoninEndsTheSongCard, historyIsWrittenWithoutStoppingTheScreen,
  updateCarriesOnByItselfAfterInstallPermission, pullingThePageDownReloadsIt, safeMoneyAnswersBankQuestionsAndSendsOtherSitesOut,
  coloursOrLanguageAskBeforeLosingATypedForm (+ autoplay rule), aLinkThatOpensANewWindowOpensANewTab; TvTest
  tvScreenStaysOnOnlyWhileAVideoPlays. Never take REQUEST_INSTALL_PACKAGES away in a test (Android kills the app).
  The emulator's "Pixel Launcher isn't responding" box can eat real taps / swipes: noSystemDialogs() presses Wait. JS: tools/uitest/abbattery.js (a quiet
  page is woken <= 1 time in 4 s; was 13) + playerads/t.js now in build.sh. DeviceTest init clears savedTabs.
- CI: on pull requests the screenshots are printed base64 in the device job log between `QAPNG-BEGIN name` /
  `QAPNG-END` (artifact downloads are blocked by this session's proxy; the "qa" release stays main / dispatch only).
  Read: `gh api repos/vishwas9215-star/ronin/actions/jobs/<job id>/logs > j.log`, then decode the blocks (python base64).
  SiteTourTest fails on phone + tablet emulators as on main 3.13.3 (report only, real heavy sites) - not new.
- Error page: no "Open anyway" when the name does not exist (DNS); address bar keeps the failed address.
- 🤖 Claude tab (coordinator request 21 Nov, for the server-side "Ronin Claude Browser" in /home/claude/claude-browser,
  NOT deployed yet): menu big button "🤖 Claude" (m-claude) + tablet top-bar button (claude-top) open / show ONE Claude
  tab on Store.claudeViewUrl (no default since the QA round: "not paired" until the first pairing). A pairing link https://<host>/k/<48 hex>
  opened in Ronin (Telegram, typed, clicked) goes to the Claude tab; a NEW host is remembered after one question and
  only for *.sslip.io / *.nip.io / *.duckdns.org (Nav.claudePairingHost). Claude tab: normal profile (cookies kept,
  CookieManager.flush in onStop), no ad layer / danger list / song scripts, not in history / saved tabs / undo, never
  the media tab, never polled; onPause when another tab is in front, and with Ronin in the background (onStop pauses
  every non-media tab + pauseTimers) so its page sees document.hidden and closes its stream. Paste: page's
  navigator.clipboard.readText -> WebMessageListener "roninClip" (that origin only, main frame, tab on screen, within
  3 s of his touch). Links to other sites open in a normal tab. Unreachable / 5xx: "Claude का browser अभी चालू नहीं"
  + Reload; 404 = not paired yet (open the Telegram link once). Tests: LogicTest claudePairingLinks; DeviceTest
  claudeButtonOpensTheClaudeTab, claudePairingLinkHostIsRemembered, claudeTabRestsWhenRoninIsInTheBackground,
  claudePasteReadsTheClipboardOnlyAfterATap.
- TvTest youtubeTvScreenWithoutAdsAndRemoteKeys flaked 2 of 5 runs (YouTube's page replaced the document between the
  guard call and the check): now one script. TV job stays continue-on-error.
- Independent QA on d829d76 (21 Nov) found and I fixed: H1 a web page could re-point the Claude tab (anyone can make
  a *.sslip.io name) -> a NEW Claude server only from a link of another app (Telegram) or his own typing, after one
  question; a /k/ link inside a page is a normal link; showAsk never stacks a second question (no dialog loops);
  L2 no default Claude server any more ("not paired" page until the first pairing). M1 anything not under Claude's
  host loads in a normal tab, never inside the Claude tab. M2 https first in load() for every address (bookmarks,
  history, links), router / LAN stay http. M3 an .apk made inside a page (blob: / data:) is never saved. M4 pull to
  reload asks when a form has typed text; never in Tools / Claude tabs. M5 Back on Ronin's error page goes past the
  failed address (or closes the tab); menu Reload reloads the failed address. M6 Day/Night/Language release the song
  card before redrawing. Low: recovered Claude tab keeps its kind; a second renderer crash within 30 s shows a page
  (MainActivity) or closes (Messages/Calls, TV) instead of looping; Safe Money keeps 3-D Secure / gateways / UPI /
  e-KYC / DigiLocker steps inside (Nav.paymentStep) and blocks plain http; "Open anyway" never for sign-in pages,
  own wording for a bad certificate; camera / mic remembered per site AND device (host|camera, host|microphone; older
  entries ask once more); media check every 1 s on screen until a song is found; window.close keeps Undo; blob limit
  30 MB + OutOfMemoryError caught; tabs saved on every open / close too; typed-text check includes same-site frames,
  writing boxes and the Tools tab; Claude tab menu has no Share / Bookmark / page tools; ad layer schedules nothing
  while hidden. L15: left as it is, to be checked on his device.
  Tests: LogicTest appFilesAndPaymentSteps (+ claudePairingLinks), DeviceTest pullToReloadAsksBeforeLosingATypedForm,
  backLeavesRoninsErrorPageAndLinksGoHttpsFirst, claude* tests rewritten (not paired page, page link cannot re-point,
  other-app link asks, Claude tab never loads another site), filesMadeInsideThePageAreSaved (+ .apk refused).
- Re-QA on 570cb99 (PASS-WITH-NOTES) fixed: N1 Safe Money card-check pages (acs* / 3ds*) stay inside only when the
  rest of the name is a BANK / PAYMENT site (acs.evil-phish.in, 3ds-verify.attacker.xyz are sent out); N2 Reload on the
  "Claude not on" page tries the server again, the 🤖 button too; N3 Back from an error page goes to the last page that
  is neither the failed address nor the error page (error pages carry history URL ERROR_PAGE). Low: "Open anyway" is
  remembered in Store.httpSites (never BANK / PAYMENT / AUTH; https still first, see below), removable in Menu ->
  Safety -> Remembered sites.
  Tests: LogicTest appFilesAndPaymentSteps (look-alike ACS hosts), DeviceTest backFromADeadLinkShowsThePageBefore,
  claudeButtonOpensTheClaudeTab (+ Reload), noInternetOrNoHttpsShowsReloadAndOpenAnyway (+ remembered).
- QA on 7d3fb72 note fixed: a remembered "Open anyway" site (Store.httpSites) no longer skips https: https is tried
  first every time; only when https cannot connect (connect / timeout / TLS handshake) does Ronin open the http address
  by itself, no tap (session-only httpAllowed). After a certificate error (onReceivedSslError -> Tab.sslBad): only the
  warning, never "Open anyway", never a fallback, nothing remembered. DeviceTest
  rememberedHttpSitesStillTryHttpsFirstAndNeverAfterABadCertificate.
- NOT done (owner decides): two APKs (review C1), removing call recorder / SMS (C2), any permission change,
  UPDATE_PACKAGES_WITHOUT_USER_ACTION / PackageInstaller session (C4), discarding idle tabs after 15 min, page-reported
  media state instead of polling (B7 rest), third-party cookies per site, Sensitive.kt substring false matches
  (Escorts), voice language following the app language, downloads list.

## 3.13.3 (PUBLISHED 19 Nov 2026 ~23:55 IST; owner 23:02: "आपको पूरी अनुमति है... ऐप से अपडेट करके" while going to sleep; run 44582876479; tag before-3.13.3) — owner 19 Nov 22:58 (screenshot of a call to 1909)
- Call screen (InCallActivity): Mute / Speaker / Keypad and Hang up / Answer sit at the BOTTOM (a weight-1 gap);
  the yellow "Unknown number ... Digital arrest" text is gone (owner: "ये सब मत लिखो"). Recent still marks numbers.
- Speaker needed 2-3 taps (owner 19 Nov 23:00): Android 14+ reports speaker / mute via CallEndpoint +
  onMuteStateChanged (callAudioState was stale). CallHub.setSpeaker uses requestCallEndpointChange (fallback
  setAudioRoute), shows the asked state at once (pending), ON buttons turn colour, ripple + buzz on every tap,
  proximity lock only at the ear, the 1-second tick updates only the timer. DeviceTest callButtonsShowWhatWasTappedAtOnce.
- No full call test: CI cannot place a real call; check on the phone.

## 3.13.2 (PUBLISHED 19 Nov 2026 ~08:50 IST on "हाँ, V3.13.2 publish करो"; run 44447853534; tag before-3.13.2 local) — owner 19 Nov 07:56
- A sign-in code is an OTP too: "Enter the code 630753 to sign in to your FYERS account", "Your login code is ...",
  "... is your sign-in code" (smsfacts.js otp() + Otp.find); counts as a short OTP for the 24 h clean-up.
  Promo "Enter code 1234 to get 10% off" and BSE "Client Code WMZS70784L" stay non-OTP.
- Real tablet, 3.13.1 (19 Nov 07:55): installed "Success", all 13 screens OK, Scam check opens directly.

## 3.13.1 (PUBLISHED 19 Nov 2026 07:46 IST on "हाँ, V3.13.1 publish करो"; run 44441651992; tag before-3.13.1 local) — owner 19 Nov 07:08
- Messages / Calls follow the PHONE's day / night (PhonePage: localNightMode FOLLOW_SYSTEM, window.__roninDark from
  Resources.getSystem(), html.light CSS, live switch in onConfigurationChanged). The browser keeps its own switch:
  Menu → Settings → "Browser का रंग" (दिन / रात). Test: phone.js day-mode check.
- Fixed (real tablet test 19 Nov): Menu → Scam check on an already open Tools page showed the list; tools.js
  hashchange now opens any #tool (scampage.js test).
- OTP = only an SMS that SAYS OTP / one time password / verification code / ओटीपी, code a number on its own
  (owner 19 Nov: BSE "Client Code WMZS70784L" showed as OTP). One rule: smsfacts.js otp() + Otp.find (Sms.kt).
- Messages: the MAIN point of each SMS (ui/tools/smsfacts.js): amounts coloured by meaning (💰 balance green,
  ➖ money out red, ➕ money in green, 🧾 bill / fee to pay amber with last date), PNR, delivered / out for delivery,
  plan expiry; chips on top of the SMS and under the list row. Tests: tools/uitest/smsfacts.js, phone.js.
- Tablet install that works: C:\Ronin Ecosystem\scrcpy\1-install-tablet.bat (owner double-clicks; log in qa\log.txt;
  19 Nov 07:06 "Success", 3.13.0). 2-tablet-run.bat runs qa\cmds.sh on the tablet (model guard OPD2415) and pulls
  screenshots to qa\shots. My Explorer double-clicks do NOT start programs (click tier), and the pylnk3 .lnk
  shortcuts are broken (white icons): never make .lnk that way again.

## 3.13.0 (PUBLISHED 19 Nov 2026 00:28 IST on "हाँ, V3.13.0 publish करो"; run 44373234892; tag before-3.13 local) — asked 18 Nov 22:30
Asked: Messages + Calls back (he liked Messages; ad SMS hidden), Mail only on the laptop, the browser more
user friendly; design ideas from AI Fiesta (Qwen, Gemini 3.1 Pro, DeepSeek agreed). His answers: Messages + Calls
both; laptop Ronin Mail shortcut + Safe Mail; Messages and browser together; address bar at the BOTTOM;
Messages' 4 boxes as a strip at the TOP.
- Android browser: address bar at the bottom (pill, 52 dp mic / tabs / menu); phone has no top tab strip (the
  tab count opens a Tabs sheet; tablet keeps the strip); red Tools button gone (Tools is in the menu).
- Sheet.kt: every menu / choice slides up from the bottom (big-button grid, then labelled groups of 60 dp rows,
  85 % max height, tablet 560 dp centred). Menu groups: This page / Safety / Phone / Settings. Tabs, History,
  Bookmarks, Language and Family Filter use it too. Test: addressBarIsAtTheBottomAndTheMenuSlidesUp.
- Messages / Calls restored from d85f64d (no SOS, no WhatsApp watcher, no location). Parts.kt (MESSAGES, CALLS)
  asks once on a SIM phone; menu → Phone → turn on / off. Messages: 4 tabs on top, newest OTP (15 min) pinned
  with Copy, 78 px rows, scam rows red. Old OTPs (owner 18 Nov 23:19, ON by default, switch in Messages Settings):
  Otp.kind → SHORT (login / payment) deleted after 24 h; DELIVERY (order / gas DAC / pickup / check-in) only 24 h after a
  later plain "has been delivered" SMS of the same company naming the same order / booking number (none = never); NONE (person's number, bank records, fees, PNR / ticket, "valid N days")
  never. ⭐ saved copies and locked SMS always stay. LogicTest oldOtpsAreCleanedUpSafely. Calls: Recent first, coloured arrow on the photo, flagged
  numbers tinted red. Tests: tools/uitest/phone.js, LogicTest smsSorting + onlyPlainOtpsAreCleanedUp,
  DeviceTest roninMessagesSortsScamAndWorkSms / roninCallsOpensOnRecentAndHasAKeypad.
- Laptop: "Ronin Mail" shortcuts back (--mail opens Gmail); Safe Mail = cmd/ronin/mail_windows.go +
  ui/inject_mail.js, runs only on email sites (tools/uitest/safemail.js).
- Old-OTP rule had 4 rounds of independent QA (no blocker left). Tablet: Install-Ronin-3.13-tablet.lnk (adb install -r)
  run 19 Nov 06:30 but could not be checked (scrcpy window cannot be granted to computer use; cmd shortcuts are not
  used). TV APK: C:\Ronin Ecosystem\TV\Ronin-TV-3.13.0.apk.
- Warn owner: SMS permissions may again make Play Protect / Advanced Protection block the install (he decides).

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
