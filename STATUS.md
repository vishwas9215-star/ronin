# STATUS.md — Ronin Project (28 Nov 2026)

_Source: HANDOFF.md, TV-HANDOFF.md (branch claude/v3.14.3), GitHub releases/tags जाँचे।_
_Dev branch: `claude/v3.14.3` | Main = v3.14.4 (published)_

---

## Ronin Browser + Ronin Phone (Android + Windows)

### Last published: **3.14.4** (21 Nov 2026, 15:49 IST)
GitHub tag `v3.14.4`, run `44875517945` (CI all green).

**3.14.4 में क्या बना था:**
- दो अलग apps एक ही code से: **Ronin Browser** (`com.ronin.browser`, SMS/call permissions नहीं) + **Ronin Phone** (`com.ronin.phone`, Messages + Calls)।
- WhoIs.kt: एक tap "🔍 कौन है?" DDG से caller name खोजता है (online, bina Truecaller के)।
- Smart caller labels: red = real risk; green = bank/company SMS; blue = recent known call।
- Chakshu auto-fill: पूरा government form भरता है, सिर्फ़ CAPTCHA + Submit मालिक का।
- Photo circles: white letter, bright hash colour (dark circle bug fix)।
- Caller-ID QA: order calls "maybe" only, UPI handles excluded, only answered calls = known।

**Published trail (GitHub confirmed):**
3.12.0 (15 Nov) → 3.13.0–3.13.3 (19 Nov) → 3.14.0 (21 Nov 07:00) → 3.14.1 (08:40) → 3.14.2 (10:21) → **3.14.4 (15:49)** → 3.14.3 published नहीं हुआ था (skip)।

### Unpublished: **3.14.5** (branch `claude/v3.14.3`, NOT published)

**3.14.5 में क्या बना है:**
- Favourites speed-dial row (phone/tablet)।
- Contact photos — call screen पर भी बड़ी।
- Recent calls list automatically refresh होती है call के बाद।
- TV: "Ronin YouTube" नाम, red play-shield banner, clean play screen (no Like/Dislike/Comments/Save), no white screen before songs, 1080p quality request।

**अगला version:** 3.14.6 (VERSION file बढ़ानी होगी — 3.14.5 पर device already है तो same code = no update नहीं चलता)।

### अगले 3 काम (Browser/Phone)
1. **TV का CC/Settings bug ठीक करो** (नीचे TV section में details) — यही 3.14.5 का open bug है।
2. **3.14.6 publish** — TV bug fix के बाद, मालिक "हाँ, V3.14.6 publish करो" बोलें।
3. **offer/transaction/OTP regex** — Kotlin + JS में duplicate regexes; `scamrules.json` में move करना बाकी।

---

## Ronin YouTube (TV app, TCL 65" Google TV)

### क्या बना है (3.14.5, unpublished)
- TvActivity उसी APK में (`com.ronin.browser`); YouTube TV full-screen, Cobalt UA।
- `NO_ADS_JS`: ads JSON से remove, `best()` = 1080p request, `tidy()` = Like/Dislike/Comments/Save/Subscribe/Description hidden।
- App का नाम: "Ronin YouTube", red shield banner (`tv_banner.png`)।
- Back = YouTube का back; screen on only while video plays; Updater (remote से dialog काम करता है)।
- Tests: `TvTest.kt` (CI Android TV 14 emulator) + `tools/uitest/tvtidy/t.js`।

### OPEN BUG (TV) — fix ज़रूरी
**समस्या:** play screen पर remote Previous / Play / Next तक पहुँचता है, **CC और ⚙ Settings नहीं** पहुँचता।

**कारण:** `tidy()` ने Like/Dislike/Comments/Save को `display:none` किया — YouTube का focus system अभी भी उन्हीं hidden buttons पर जाता है, CC तक नहीं पहुँचता।

**Fix का plan (TV-HANDOFF.md से):**
1. _(सबसे अच्छा)_ `JSON.parse`/`Response.json` hook से watch-screen renderers drop करो — बटन DOM में आएंगे ही नहीं।
2. _(fallback)_ Android से `dispatchKeyEvent(KEYCODE_DPAD_RIGHT)` JS bridge के ज़रिए।
3. _(अंतिम उपाय)_ सिर्फ़ Subscribe/Description hide करो, icon buttons रहने दो।

**Real TV पर जाँचना बाकी है:**
- CC और ⚙ Settings remote से पहुँचते हैं?
- `best()` से 1080p मिलती है?

### अगले 3 काम (TV)
1. **CC/Settings focus bug fix** — JSON hook approach पहले try करो।
2. **3.14.6 build + install**: fix के बाद `C:\Ronin Ecosystem\scrcpy\5-install-on-tv.bat` (मालिक एक double-click)।
3. **3-tap TV check**: (a) remote CC तक पहुँचा? (b) ⚙ Quality = 1080p? (c) Like/Subscribe दिखा?

---

## Extra branches (HANDOFF के बाहर, जानकारी के लिए)
- `ronin-media` → pre-release "Ronin YouTube + Ronin Music (test builds)" (25 Nov)
- `ronin-home` → pre-release "Ronin Home" (21 Nov)
- `agent-lab` → pre-release "Ronin Agent Lab (pilot test build)" (27 Nov)

---

## Secrets जाँच (28 Nov 2026)

| क्या | Status |
|------|--------|
| `keys/ronin-keys.tar.enc` | ✅ AES-256 encrypted, plain keys commit नहीं हुए |
| Gemini API key | ✅ Windows Credential Manager में, source में नहीं |
| `RONIN_KEYS_PASS` | ✅ GitHub Secret (`${{ secrets.RONIN_KEYS_PASS }}`), hardcoded नहीं |
| `.pem`/`.p12`/`.key` plain files | ✅ git history में कोई नहीं |
| Source code में hardcoded secret | ✅ कोई नहीं मिला |
