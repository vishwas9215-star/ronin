# Ronin Browser 3 — master checklist

Source: Ronin_Browser_3_Design.md (9 Nov 2026) + user requests (10–11 Nov).
Status: [x] built + tested on laptop · [b] built, waiting for laptop test · [ ] not built yet · [-] not in this version (reason)

## User rules
- [x] No Brave/other browser name anywhere — own name and icon (WebView2 engine)
- [x] Installs inside C:\Ronin Ecosystem\Ronin Browser; never touch C:\Ronin Ecosystem\Ronin
- [x] Everything updates by itself (engine by Microsoft; lists, yt-dlp, deno, ffmpeg, wireproxy by Ronin Updater)
- [x] No extra charges (paid AI off by default)
- [x] One single tested version delivered: 3.1.0 installed on the laptop 11 Nov

## Safe Money (banking)
- [x] Bank page opens Safe Money automatically (typed / clicked)
- [x] Bank → bank redirect (hdfcbank.com → hdfc.bank.in) also opens Safe Money (bug found 11 Nov)
- [x] Screenshot / screen-record block
- [b] Green frame, idle logout, clipboard clear 30 s, AnyDesk/TeamViewer warning, antivirus check
- [x] Fake (lookalike) bank site warning
- [b] Payment Protect for shopping OTP pages
- [b] Downloads only PDF/CSV/Excel in Safe Money

## Ad-block (no counter)
- [x] Network block: built-in rules + AdGuard DNS filter + Peter Lowe + URLhaus-filter + phishing-filter (daily update; old URLhaus feed retired)
- [b] Cosmetic: empty ad boxes hidden
- [x] YouTube ads removed / skipped (user screenshot 11 Nov)
- [x] Pause ad-block on one site
- [-] AdGuard DNS layer (WebView2 has no reliable DoH switch; the same lists are blocked inside the browser)

## Video downloader
- [x] Download button on YouTube / Instagram / Facebook / X video pages
- [b] Quality: Best, 1080p, 720p, 480p, MP3
- [x] Saves to Downloads\Ronin Videos; progress, cancel, show in folder
- [x] yt-dlp + deno + ffmpeg downloaded on first use, checksum-verified, auto-updated

## VPN
- [b] Import free Proton VPN WireGuard config (.conf)
- [b] Only normal + private windows use VPN; Safe Money + Trading always direct
- [b] Kill switch (pages wait if the tunnel is down), WebRTC leak blocked, "Check VPN" button
- [ ] VPN: needs your Proton config to test; wireproxy checksum check runs then (VPN stays off until it passes)

## Trading (all broker and chart sites: Zerodha, Upstox, Angel, Groww, Fyers, Dhan, TradingView, Investing, Chartink ... + "This is a trading site")
- [x] Background tabs stay live (no freezing), also when minimised
- [x] Chart grid 1 / 2 / 4 in one window
- [x] Mini chart window, always on top
- [b] TradingView alerts (notifications) allowed in Trading window
- [x] Session restore (tabs come back next time)
- [b] Ctrl+1..9 tab switch
- [x] Ronin Terminal, Dhan, TradingView open in Trading window automatically

## AI (Gemini, free)
- [b] Summary, ask about page, explain in Hindi, translate page to Hindi
- [x] Never from Safe Money / Trading / Kids; PAN, Aadhaar, card, OTP, phone, email hidden; preview before send
- [b] Key in Windows Credential Manager; Claude (paid) off with daily ₹ cap; any OpenAI-compatible slot

## Kids
- [b] Junior (allowed sites only) / Teen (adult + gambling blocked)
- [b] Google + Bing SafeSearch forced, YouTube Restricted Mode; only safe downloads; no private window
- [b] Locked for the children's Windows account (parent PIN); real lock needs a Standard Windows account

## Scam Shield
- [b] "Send money for return / fee before withdrawal" warning (tested rules)
- [x] "Is this a scam?" check (in the menu; Ronin rules + Gemini)
- [b] UPI payment-for-investment warning, 1930 shown; impersonation judged by the Gemini scam check

## Other
- [b] Reader mode
- [b] Sleeping tabs after 15 min (never Trading or Safe Money)
- [-] Bitwarden inside Ronin: WebView2 add-ons need an engine option this version cannot set → use Bitwarden desktop app + phone app (guide)
- [b] Download Guard (dangerous files, Mark of the Web)
- [b] Signed self-update framework (channel needs online hosting)
- [-] Mobile "Ronin Check" app — separate Android project, later

## Open work (saved 11 Nov 10:37) — resume here
Installed on laptop: 3.1.2 (C:\Ronin Ecosystem\Ronin Browser). Tested OK on laptop: VPN (IP showed US), Gemini Hindi summary, video download, YouTube without ads, live background charts, grid, mini chart, Safe Money for HDFC, fake-bank block.
1. BUG (found 10:36): no single-instance. An old Ronin process ("ronin.exe.old", probably an open Safe Money window) keeps wireproxy on port 40877, so a second Ronin's VPN program exits at once ("vpn: server 1 of 1 stopped" loop in ronin.log). FIX: named mutex + forward args (WM_COPYDATA to RoninBrowserMsg window) so a second launch opens a window in the first process; also make VPN start tolerate a port that already answers.
2. UNEXPLAINED: 3.1.2 window closed by itself at 10:36:05 right after two clicks (title bar, address bar). Reproduce after fix 1.
3. 3.1.2 changes NOT yet tested on laptop: TradingView upgrade pop-up auto-close, hidden Upgrade button, green/red price in tab titles, AI text without ** marks, VPN multi-server auto-switch (needs 2nd Proton config), "Dhan TradingView (live data)" link.
4. TradingView NSE data is 15 min delayed for guests: cannot be bypassed. Real fix = log in to tv.dhan.co (Dhan's TradingView, live with Dhan account) or a broker site; all tabs in the Trading window share one login. Verify with user's Dhan login.
5. Google passkey (Windows Hello) sign-in fails in Ronin ("problem signing in with your passkey") — workaround: Try another way → password. Investigate.
6. User asked: test everything fully on laptop; no TradingView ads at all.

## Saved state (12 Nov 07:22) — session limit, resume from here
- 3.1.4 built and all tests pass (build/RoninSetup.exe). Contains: single instance, pop-up blocker,
  hagezi-pro + popup-ads lists, thirdPartyBlock (adult ad networks), Download button on any page with a
  <video> + menu item, own error page (no Edge logo), HTTP-level VPN health check, wireproxy.log.
- 3.1.4 installer IS copied to laptop: C:\Ronin Ecosystem\RoninSetup.exe — NOT yet installed/tested.
  Laptop still has 3.1.3 installed; Ronin window was closed at 07:21.
- Next: run installer (Explorer dbl-click RoninSetup ~ (750,406) -> Yes -> Yes), then test:
  ad-block + popup block on adult/video sites, Download button on generic video page, error page,
  second-launch hand-over, VPN (read vpn\wireproxy.log + ronin.log; VPN traffic was failing 12 Nov:
  ERR_CONNECTION_CLOSED with badge "VPN on"; add 2 more Proton free servers, test auto-switch).
- Still open: Google passkey sign-in, self-closing window (11 Nov 10:36:05), untested [b] items above.

## 12 Nov 14:18 — 3.1.5 installed on laptop and tested
- [x] VPN working again (Cloudflare trace loc=US, badge "VPN on" green). wireproxy.log shows handshake OK.
- [x] Ad network address (s.pemsrv.com) blocked; shows Ronin's own "blocked" note (no Edge logo).
      Fixed library bug: response body was never sent (shadowed `stream` variable).
- [x] More adult/pop-under ad networks added (trafficjunky, juicyads, adsterra, propellerads, popads ...).
- [x] Download button appears on any page with a video (tested w3schools); video saved to Downloads\Ronin Videos.
- [b] Download fallback: if yt-dlp does not know a site, Ronin tries the video files playing on the page
      (with the page as referer). Unit-tested; needs a real unknown site.
- [b] Download button also for big video player frames (embed/player iframes).
- [x] Ronin's own error page; address bar keeps the failed address; Reload retries it.
- [x] Second launch (desktop shortcut while Ronin open) opens a window in the same Ronin; no 2nd process.
- [ ] User to check adult site himself (ads + Download button).
- [ ] Add 2 more Proton free servers (user logs in to Proton, downloads .conf; Settings → VPN → Import).
- [ ] Google passkey sign-in still open.

## 12 Nov 14:35 — 3.1.6 (built, all tests pass; installer copied to laptop, user installs)
- [x*] Download bug fixed: some sites call their stream list "video.mp4", so a 1 KB list was saved.
      Ronin now detects the list, picks the right quality stream and downloads the full video.
      (*reproduced and fixed here with the real downloader + a test site; to confirm on the user's site)
- [x*] Downloads from a VPN window also go through the VPN (socks5h, names resolved through the VPN).
- [x*] Advert inside any video player ("Skip ad in 2", "Ad - 00:08"): muted, run to the end, Skip clicked;
      the player goes back to normal after. Other players untouched. Real-browser test: tools/uitest/playerads.
- [x*] Small "AD"-badge promo links and "Advertising" captions above blocked boxes are hidden
      (normal links/text named "Advertising" stay).

## 12 Nov 14:41 — 3.1.7 (built, all tests pass; installed on laptop, then the laptop went offline)
- [b] Old tabs never come back at a normal start (fresh new tab). Tabs are not kept on disk.
      Only Ronin's own restart (after a VPN change) brings them back once.
- [b] Smart VPN (default): trusted well-known sites go direct; unknown and sensitive sites
      (adult, betting, piracy, never-seen sites) go through the VPN. Private windows: always VPN.
      Engine-tested here: PAC + real Chromium + SOCKS — google direct, unknown + adult via VPN with
      names resolved inside the VPN. Toolbar shows "VPN on" or grey "No VPN" per site.
      Settings → VPN → "Use VPN for every site" switches back (restart).
      Trusted list = built-in list + world top-10,000 sites (Tranco, weekly), minus adult/betting.
- [ ] To test on laptop: ad on the user's video page with 3.1.6/3.1.7 rules, smart VPN badge,
      fresh start, Check my IP (opens a private window).

## 12 Nov 16:30 — 3.1.9 installed and tested on the laptop
- [x] Fresh start: Ronin opens with one new tab, old tabs do not come back.
- [x] Smart VPN: YouTube/Wikipedia/Proton show grey "No VPN" (direct); unknown sites show green
      "VPN on" and go through the tunnel (seen in wireproxy.log). Private window: cloudflare trace loc=US.
- [x] VPN: safe packet size (MTU 1280, user's file untouched; Ronin uses vpn\active.conf);
      health check now does a real https handshake; a server that never works is left after ~45 s;
      the last working server is tried first next time.
- [x] 2 more free Proton servers created (CA-FREE#30, CA-FREE#33) and added (wg2.conf, wg3.conf) -> 3 servers.
- [x] Downloads open in a side panel at the right; the page stays visible and usable.
      All file downloads (not only videos) show there with progress/size/cancel; the engine's own
      download box is not used. Ctrl+J opens it, ✕ closes it.
- [x] A link that is a file no longer leaves a "could not open" page behind.
- [x] Ctrl+Shift+T / Menu → "Reopen closed tab" (this run only, nothing saved on disk).
- [x] Video download on the user's site worked with 3.1.7 (full 125 MB file).
- [x] Video file names now use the page name, not the long stream address (unit-tested).
- [b] AI answers also open in the side panel (same mechanism, not clicked through on laptop).
- [ ] User to confirm: in-player ad on his video site (not visited by Claude).

## 12 Nov 16:53 — 3.2.0 installed and tested on the laptop
- [x] Every download is checked by Windows Defender (MpCmdRun); panel shows "Checked by Windows Defender - no virus".
      Virus found -> file removed, "Virus removed" (code path not seen live: the EICAR test site is already
      blocked by Ronin's harmful-site list).
- [x] HTTPS-only: http addresses open as https; no secure version -> Ronin page with "Open anyway (not secure)"
      (not offered in Safe Money / Kids); insecure page shows a warning bar. Local/home-network addresses untouched.
- [x] Third-party cookies blocked in normal, private and Kids windows (engine-tested: tracker frame gets no cookie).
- [x] Site permissions: Ronin asks "site wants to use camera / microphone / location / clipboard - Allow / Block";
      answers kept (not in private windows), listed in Settings with Remove. Tested: location blocked + listed.
- [x] Save page as PDF (Ctrl+P / Menu) and screenshot (Menu) -> Downloads, shown in the panel. Both files checked.
- [x] Tab search (Ctrl+Shift+A / Menu "Find a tab") — UI-tested in a real browser.
- [x] AI tools open in the side panel next to the page; Gemini busy (503) -> retries with the next free models.
      Live: Google's free Gemini was overloaded at 16:51 (all tries failed) — worked yesterday with the same key.
- [x] crash.log (Go crash output) + reason for every window close in ronin.log.
- [x] Block page text: "advert, tracker or known harmful site".
- [b] Ronin self-update: tools/release signs manifest.json (+ .sig) with the release key (checked against the
      built-in public key). Hosting needs the user's GitHub login (Claude never types passwords): create repo
      "ronin-updates", then set update_channel in settings.json to
      https://github.com/<user>/ronin-updates/releases/latest/download/manifest.json
- [ ] Google passkey sign-in: not fixed (use "Try another way" -> password).
- [ ] Still untested live: Safe Money idle logout/clipboard clear/AnyDesk warning, Kids mode, Reader, sleeping tabs.

## 12 Nov 22:45 — 3.3.0 (built, all tests pass here; NOT yet tested on the laptop — it was offline)
- [b] New logo G4 (green shield, R with katana) for Ronin.exe, setup, shortcuts (res/ronin.ico, *.rc -> syso via llvm-rc).
- [b] Unasked downloads: a download a site starts by itself, or a program file (.exe/.msi/.apk ...) from an unknown
      site, waits for "Download / Block". Well-known sites and the user's own click on a download link go straight on.
- [b] Ronin's own fast downloader (internal/fastdl) for files >= 8 MB: 8 connections, Pause/Resume, retries for ever on
      broken connections, "Waiting... Ronin keeps trying" when nothing arrives, goes on after the window is closed.
      Uses the page's cookies + referer; if the server refuses, the engine downloads it as before.
      Unit-tested (broken connections, pause/resume, no-range server, dead link) + tested through a SOCKS proxy.
- [b] Small engine downloads: Pause/Resume too, automatic retry when the connection breaks.
- [b] Downloads panel: one-line short name (full name on hover), speed, time left, "Faster: without VPN".
- [b] Close Ronin while downloading -> small always-on-top "Ronin downloads" window; "Open Ronin" button;
      X only minimizes it while something is downloading.
- [b] Bookmarks: Menu -> Bookmark this page (Ctrl+D), Menu -> Bookmarks (Ctrl+Shift+B) with search and remove.
- [b] A link's new tab opens behind (Settings -> New tabs to change).
- [b] Safe Money: no green flash (dark background, thin green frame only).
- [b] "Force resume": progress of every big download is kept on disk (<file>.ronin-part + .ronin-state).
      Internet lost -> waits and continues by itself. Link expired -> job shows "Link expired"; starting the same
      download again (same name and size) continues from the saved point. Same after closing Ronin or a PC restart.
      Servers that cannot give parts of a file: no Pause button, note "this server cannot pause or continue"
      (no browser can force such a server). Unit-tested: dead link + fresh link, restart, different file not mixed up.

## SAVED 12 Nov 22:48 (weekly limit) — resume here
- Last step was interrupted: after adding "force resume" (resumeNoted field, needlink UI text) run
  `gofmt -w cmd/ronin/*.go && ./build.sh 3.3.0` and check build/Ronin.exe is new; then run tools/uitest/*.js.
- The RoninSetup.exe sent at 22:45 is 3.3.0 WITHOUT force resume. Nothing of 3.3.0 is installed/tested on the laptop.
- Next: rebuild, install on laptop, test: unasked-download prompt, fast download + pause/resume + float window,
  bookmarks, background tabs, green flash, new logo. Then GitHub update channel, passkey.

## 14 Nov 03:20 — 3.3.0 (with force resume) installed and tested on the laptop
- [x] New logo G4 in the taskbar.
- [x] Fast downloader: GitHub 111 MB zip in ~10 s; panel shows speed + time left ("8.2 MB/s · 11 sec left").
- [x] Pause -> stays at 92.2 MB; close Ronin -> small "Ronin downloads" window appears in the corner;
      Resume there -> finished; SHA256 equals GitHub's official hash (byte-for-byte correct).
- [x] Download Guard still stops installers (.msi) with "Download anyway".
- [x] Bookmark this page (Ctrl+D) -> "Bookmark saved". Bookmarks list UI-tested here (not clicked on laptop).
- Fixed after test: a download typed by the user was asked as "site started it by itself" (a late error page of an
  earlier address counted as a page). Now only the user's own navigation (same navigation id, real web address) counts.
- Small window made taller (buttons were cut).
- [ ] Not seen on laptop: Safe Money (invisible to screenshots by design), "unasked download" prompt on a real site,
      background new tabs. Proton free VPN could not reach ash-speed.hetzner.com (server side).

## 14 Nov 03:30 — 3.4.0 Day / Night mode, installed and tested on the laptop
- [x] Sun/moon button at the top (left of the menu) + Menu item; switches every window at once; choice is kept.
- [x] Day mode: light toolbar, new tab page, panels, error pages, window frame.
- [x] Night mode for web pages, in 3 layers:
      1. the engine tells sites "night" (ICoreWebView2_13 Profile PreferredColorScheme) -> sites with their own dark
         design use it (GitHub tested: its real dark theme, not inverted);
      2. plain white sites are inverted as a whole (text + background together = always readable), photos/videos/
         frames turned back to real colours; transparent logos get a light backing (Wikipedia logo + emblem visible);
      3. already-dark sites are left alone.
      Per-site escape: Menu -> "Night mode looks wrong on this site? Turn it off here" / "... on again".
      Not in Safe Money (no scripts in bank pages) and not in Trading (charts keep their own look).
      Tests: tools/uitest/theme.js, tools/uitest/night/t.js (white, own-dark, always-dark, live switch, site off).
- Fixed in 3.3.0 (now installed): own typed download no longer asked as "started by itself".

## 14 Nov 07:40 — "continue and fix all" check (3.4.0 on laptop)
- [x] crash.log: only start lines, no crash since 3.2.0. ronin.log: clean opens/closes.
- [x] Bookmarks list (Ctrl+Shift+B) opens on the laptop with search + saved bookmark.
- [x] Ctrl+click link opens a background tab; the current tab keeps focus (laptop).
- [ ] Unasked-download prompt on a real site: logic UI-tested here, not tried on a real auto-download site.
- [ ] Google passkey: engine-side limit; use "Try another way" -> password.
- [ ] GitHub update channel: needs the user's GitHub login once.
- [ ] Safe Money flash: user to confirm by eye.

## 14 Nov 10:00 — 3.5.0 (built here, all tests pass; laptop test below)
- [b] Video call window (CALLS, cyan): Meet, Zoom, Teams, WhatsApp Web, Jitsi, Webex, JioMeet ... open there from a
      normal window. No VPN and no WebRTC limit (smart-VPN windows forced calls onto slow TCP relays = poor quality),
      camera + mic allowed at once for known call sites, calls keep running in background tabs, never sleep,
      no night-mode inversion. Private windows keep calls private (stay, VPN). Router tests in internal/containers.
- [b] Mic button in the address box: Windows voice typing (Win+H, Hindi/English), opens by itself 1.8 s after the
      words stop (full stop / danda removed). Not in Safe Money. Test: tools/uitest/voice.js.
- [b] Read aloud: Menu -> "Read this page aloud" (Reader + Listen bar): Hindi text with a Hindi Windows voice,
      English with en-IN; tap a paragraph to start there; speed 1/1.25/1.5/0.8; note how to add a Hindi voice.
      Test: tools/uitest/listen.js.
- [b] Tabs playing sound (songs, videos, calls) never go to sleep (ICoreWebView2_8 IsDocumentPlayingAudio).
- [b] History (Ctrl+H, Menu): normal/Trading/Video call windows only, 90 days, 20,000 pages, search (Hindi too),
      remove one, delete last hour / today / all (all = tap twice), "Stop keeping history". internal/history.
- [b] Page size: Ctrl + / Ctrl - / Ctrl 0, Menu row (- + reset), % pill in the address box, kept per site
      (not in Private). settings.ZoomSites.
- [b] Study time (Focus): Menu "pause time-wasting sites for 1 hour" or daily hours in Settings (default
      18:00-21:00, off). Pauses Instagram, Facebook, X, Shorts, Netflix ... (YouTube itself stays for study videos);
      Ronin page with "Open for 5 minutes". Normal + private windows. internal/focus.
- [b] Passwords: Ronin's own store (passwords.dat, DPAPI = only this Windows user). "Save password?" after a login
      (two-step logins too), "Fill: <name>" only on the exact same site (https, www ignored), user clicks Fill.
      Never in Safe Money / Kids; Private fills but never saves. Show / remove / import / export need Windows Hello
      (PIN / fingerprint). CSV import (Chrome/Edge/Google/Bitwarden/Firefox columns) and export (plain text,
      warned). internal/vault, ui/inject_pass.js, ui/fill_pass.js, tools/uitest/passwords.js.
- [b] Bookmarks export / import (standard bookmarks .html, any browser) - base for laptop <-> phone later.
- Next phase (agreed with owner): Android phone + tablet app, TV app (YouTube only), YouTube background play with
  notification / lock-screen / car controls, one release for exe + apk (GitHub repo + build machines).

## 14 Nov 09:20 — 3.5.0 installed and tested on the laptop (C:\Ronin Ecosystem\Ronin Browser)
- [x] Installer updates in place (dialog showed 3.5.0 + C:\Ronin Ecosystem\Ronin Browser). Backup: RoninSetup-3.4.0.exe
      in Downloads\Ronin Browser 3 Handoff (install it again to go back).
- [x] New Tab page shows "Video calls"; Jitsi link opened the separate "Ronin Video Call" window (cyan badge, no VPN).
      (Window opened behind the normal one - Windows focus rule; cosmetic.)
- [x] History (Ctrl+H) lists pages incl. the Video call window page.
- [x] Read aloud: Reader + Listen bar, paragraph highlight. Laptop has NO Hindi Windows voice -> note shown. Owner to add.
- [x] Study time 1 hour: instagram.com stopped with the Study time page; "stop now" works.
- [x] Passwords panel opens ("0 saved"). Windows Hello prompt + save/fill on a real login: owner to try.
- [x] Mic: address box turns red "Speak now", Windows voice typing panel opens (Win+H).
- [x] Found + fixed during laptop test: page-size % did not update / was not kept (engine zoom event not seen) ->
      now also checked after each click and every 5 s; kept per site (new tab on hi.wikipedia opened at 125%).
- [x] Found + fixed: an address typed into the New Tab page's search box went to Google search -> opens the site.
- [x] Independent security review of passwords: 3 real bugs found and fixed (page could pick the site a password is
      saved under; page could fool the fill check; password copy in sessionStorage). Re-check passed.

## 14 Nov 09:26 — 3.5.1 (icon fix) installed on the laptop
- [x] Owner noticed: desktop / Start shortcuts still showed the OLD blue shield (Windows icon cache keyed by the
      unchanged Ronin.exe path). Fix in setup: each version writes <install>\icons\ronin-<version>.ico (older ones
      removed), shortcuts point to it, then SHChangeNotify + ie4uinit -show. Checked on the laptop desktop: both
      shortcuts now show the green R shield.

## 21 Nov — 3.14.0 Android (branch claude/v3.14.0, draft PR #1; built + emulator-tested, NOT published)
- [b] Tabs come back after an update / swipe-away / crash (only the front tab loads); "Tab closed — Undo"
- [b] A killed page engine reloads only that tab (Ronin no longer closes, forms elsewhere stay)
- [b] Day/Night and Language ask first when typed form text, private tabs or a song would be lost
- [b] Safe Money: bank pop-up questions + file upload work, PDF/Excel statements download, Back asks before logout,
      redirects to non-bank sites leave the green frame
- [b] Files made inside a page (blob: / data:) save to Downloads/Ronin
- [b] Router / LAN pages open on http; a site without https gets "Reload" and "Open anyway (not secure)" (never banks)
- [b] "This is my bank" asks; Remembered sites list (my banks, camera / mic) with ✕
- [b] Other apps (UPI, phone, Play Store) open only after a tap, else Ronin asks
- [b] Family Filter SafeSearch also for typed / spoken searches
- [b] Address-bar suggestions (quick links, bookmarks, history); A− / A+ text size per site; Find in page; Share;
      Desktop site; Forward; long-press link menu (new tab / copy / share / save picture); pull down to reload
- [b] Camera / mic asked once per site (remembered), Back = no; links that open a new window open a new tab
- [b] Battery: song in the background uses YouTube's smallest picture, page timers rest when nothing plays, ad layer
      sleeps on quiet pages, slower media checks, lists not read on SMS / call starts, TV screen sleeps when paused
- [b] Update continues by itself after "Allow from this source"
- [b] 🤖 Claude button (menu + tablet bar): one Claude tab with the live view of Claude's own browser on his server;
      the Telegram pairing link remembers the server; Paste works after his tap; the tab rests when not on screen;
      "Claude का browser अभी चालू नहीं" + Reload when the server is off (server part not deployed yet)
- [-] Two APKs / removing call recorder / permission changes / silent self-update: owner's decision (questions in HANDOFF)

## 21 Nov — 3.14.1 Android (branch claude/v3.14.1, draft PR #2; NOT published)
- [b] A YouTube / YouTube Music song keeps playing after Ronin is swiped away from Recents; opening Ronin brings the
      same tab back, still playing; the card's play / pause / ✕ work; paused 10 min or ✕ = everything let go

