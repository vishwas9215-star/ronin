# Ronin YouTube (TV app) — handoff for a new chat / project

Written 21 Nov 2026, 21:45 IST, by the "calls / messages" chat. Read this whole file before touching anything.

## Owner
- Vishwas. Answer in Hindi, with technical words in English.
- Both his hands are hurt, so ask for as few taps and as little typing as possible.
- He is not a programmer: you decide the steps and tell him only what he must do.
- His rules, short version:
  - Plan first, then build. Test everything yourself.
  - Every fixed bug gets a permanent test.
  - Keep the previous version safe.
  - Live publish ONLY when he writes the version name, e.g. "हाँ, V3.14.6 publish करो".
  - End with a 3-tap check for the real device.
- Never touch these folders: `C:\Ronin Ecosystem\Ronin`, `EMERGENCY-BACKUP`, `Ronin PRO`, `Ronin PRO-backup-*`.
- Never read: `*.pem`, `*.rdp`, keys.
- Another chat (the "terminal" project) also works on this repo. Do not change its work.

## Where everything is
- **Code:** GitHub `vishwas9215-star/ronin`.
  - Work on branch `claude/v3.14.3`. It holds the unpublished 3.14.5, which also contains the Calls / Messages work.
  - `main` = the live 3.14.4. Start from the branch, not from main, or 3.14.5 is lost.
- **TV screen:** `android/app/src/main/java/com/ronin/browser/TvActivity.kt` (about 210 lines).
  - A WebView opens `https://www.youtube.com/tv` with a TV user-agent (`TV_UA`).
  - `NO_ADS_JS` is injected at document start. It does three things:
    - removes ads from YouTube's JSON;
    - YouTube's "Exit" closes the app;
    - (since 3.14.5) runs `best()` (asks for 1080p) and `tidy()` (hides the play-screen buttons by their label).
  - `getDefaultVideoPoster()` returns a 1 px see-through picture, so no white "▶" screen before songs.
- **Name and icon:** `android/app/src/browser/AndroidManifest.xml`, TvActivity label "Ronin YouTube".
  - Banner: `android/app/src/main/res/drawable-xhdpi/tv_banner.png`, an original red shield with a play triangle.
- **Tests:**
  - `android/app/src/androidTestBrowser/java/com/ronin/browser/TvTest.kt`, which runs on the CI Android TV 14 emulator.
  - `tools/uitest/tvtidy/` (page.html + t.js, run with `NODE_PATH=$(npm root -g) node tools/uitest/tvtidy/t.js`). It is a copy of the play screen.
- **Build:** GitHub Actions `build.yml`.
  - Test build: `gh workflow run build.yml --ref claude/v3.14.3 -f publish=false`. The APK appears as `Ronin-test.apk` in release `qa`.
  - Publish (only after the owner's words):
    1. Fetch main and check `git merge-base --is-ancestor FETCH_HEAD HEAD`.
    2. `git push origin HEAD:main`.
    3. `gh workflow run build.yml --ref main -f publish=true`.
  - The versionCode comes from the `VERSION` file. Any new build that goes on a device needs a new number (next: 3.14.6).
- **Install on the TV** (TCL Google TV, USB debugging ON, same Wi-Fi as the laptop):
  - The owner double-clicks `C:\Ronin Ecosystem\scrcpy\5-install-on-tv.bat`.
    - `find-tv.ps1` finds the TV on port 5555.
    - `adb install -r Ronin-3.14.5.apk`.
    - It then opens the app. The log goes to `C:\Ronin Ecosystem\scrcpy\qa\tv-log.txt`.
  - The Claude chat cannot run .bat files itself: Windows blocks typing into Run / Explorer. Put the new APK next to the .bat, change the file name inside it, and ask the owner for one double-click.
  - The app on the TV also updates itself from the GitHub release (Updater) after a publish.

## OPEN BUG (the reason for this handoff)
- **What the owner saw (21 Nov 21:41):** on the play screen the remote reaches only Previous / Play / Next. It can no longer reach **CC** or **⚙ Settings** (the quality menu).
- **Cause, almost certainly:** `tidy()` in `NO_ADS_JS` hides Like / Dislike / Comments / Ask / Save (they sit between Next and CC) with `display:none`.
  - YouTube TV's own focus system still sends the remote to those hidden buttons.
  - The "hop" code (it re-sends the arrow key as a synthetic `KeyboardEvent`) does not work: YouTube ignores untrusted keys. So focus stays on an invisible button and never reaches CC / Settings.
- **Ways to fix, in order of preference:**
  1. Remove the buttons from YouTube's JSON before the screen is built. `NO_ADS_JS` already hooks `JSON.parse` / `Response.json`. Find the watch-screen renderers (like / dislike / comments / save / ask / subscribe / description) and drop them from their arrays. Then YouTube's focus system never knows them.
  2. Or keep the buttons in the DOM but make the remote skip them for real: dispatch the key through Android, e.g. `web.dispatchKeyEvent(KEYCODE_DPAD_RIGHT)` from Kotlin via a JS bridge call, instead of a synthetic JS event.
  3. Or, if 1 and 2 fail, hide only Subscribe / Description (they are on another row) and leave the icon buttons, so CC / Settings work again.
- **To SEE the real DOM:** YouTube shows its TV page only to TV user-agents. A desktop browser and the cloud get a fallback page, and the CI emulator gets "confirm you're not a bot".
  - Enable `WebView.setWebContentsDebuggingEnabled(true)` in a debug build.
  - Connect the laptop's Chrome to the TV via `chrome://inspect` (adb over Wi-Fi, port 5555), open a video, press OK, and inspect the play-screen buttons and how focus moves.
- **Also still unchecked on the real TV:** does `best()` really get 1080p? It uses `movie_player.getAvailableQualityLevels / setPlaybackQualityRange`. If the TV player has no `movie_player`, look for the quality API the same way.
- **After the fix:**
  - Add the focus case to `tools/uitest/tvtidy/t.js` and a TvTest.
  - Bump `VERSION`.
  - Build, put the APK in the scrcpy folder, have the owner double-click the bat, and give him a 3-tap TV check: remote reaches CC, remote reaches ⚙ and the quality shows 1080p, no Like / Subscribe.

## What already works on the TV (do not break)
- No ads.
- The app opens from the TV home row as "Ronin YouTube".
- Back on the remote = YouTube's back.
- The screen stays on only while a video plays.
- The app restarts itself if the page engine crashes.
- No white "▶" screen before a song.
