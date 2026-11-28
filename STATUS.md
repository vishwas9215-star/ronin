# STATUS.md — Ronin Project (28 Nov 2026)

_Source: HANDOFF.md + VERSION file. Dev branch: `claude/determined-brown-uevep4`._

---

## Ronin Browser (Android + Windows)

### क्या बना है (last published)
- **Last published: 3.14.2** (21 Nov 2026 10:21 IST, run 44866848465)
  - Call screen: InCallActivity excludeFromRecents; in-call keypad (12 big keys, tone, buzz).

### क्या बना है (unpublished — branch `claude/v3.14.3`)
- **3.14.3** (CI green, run 44875517945, waiting for publish word):
  - Two apps from one code: **Ronin Browser** (`com.ronin.browser`, no SMS/call permissions) + **Ronin Phone** (`com.ronin.phone`, Messages + Calls).
  - Chakshu auto-fill: पूरा government form भरता है, सिर्फ़ CAPTCHA + Submit मालिक का।
  - Smart caller labels (red only real risk, green for bank/company, blue for known).
  - Adult video site ad-skip + cam widget blocks.
  - WhoIs.kt: एक tap "🔍 कौन है?" DDG से caller name खोजता है।
- **3.14.4** (branch `claude/v3.14.3`, VERSION = 3.14.4, NOT published):
  - 3.14.3 के ऊपर: photo circles bright (white letter, hash colour), Sanchar Saathi OTP fix, WhoIs QA fixes.
  - Caller-ID QA: order calls only "maybe", UPI handles excluded, only answered calls = known.

### क्या publish हुआ है (trail)
3.12.0 (pure browser) → 3.13.0–3.13.3 (Nov 19) → 3.14.2 (Nov 21) → **अब 3.14.3 / 3.14.4 pending**

### अगले 3 काम (Browser)
1. **3.14.4 publish** — मालिक "हाँ, V3.14.4 publish करो" बोलें, तब `gh workflow run build.yml --ref main -f publish=true`।
2. **offer/transaction/OTP regex** — Kotlin + JS में duplicate regexes हैं; `scamrules.json` में move करना बाकी है (HANDOFF में noted)।
3. **Photo → Excel OCR** — offline ML Kit / Windows OCR, Gemini free only on press with warning (agreed for 3.9, not started)।

---

## Ronin TV

### क्या बना है
- TvActivity उसी APK में (अलग app नहीं); YouTube TV full-screen, Cobalt UA, NO_ADS_JS, D-pad keys, Updater.
- **Last TV APK**: `Ronin-TV-3.14.2.apk` (or whichever was last manually copied to `C:\Ronin Ecosystem\TV\`).
  - HANDOFF says: 3.11.1 के साथ TV APK copied; 3.12.0 APK = `Ronin-TV-3.12.0.apk`; 3.13.0 = `Ronin-TV-3.13.0.apk`.
  - 3.14.3 CI green था → RoninPhone.apk copied था; TV APK की latest copy confirm करनी है।
- CI device "tv" (android-tv API 34): TvTest home-row, prune check, D-pad keys — continue-on-error (YouTube बदलता रहता है)।

### क्या publish हुआ है
TV एक अलग publish नहीं होता — browser APK के साथ build होता है। Manual copy: `C:\Ronin Ecosystem\TV\`.

### क्या बाकी है
- **Real TV पर जाँच** नहीं हुई: ads block, video quality, sign-in, remote keys — मालिक घर पर TV से जाँचें।
- YouTube के bot-check (data-centre IP) की वजह से CI में video नहीं चला — घर पर sign-in से ठीक होगा।
- TV screen on/off (RoninTv.playing) और Back/finish logic OK है।

### अगले 3 काम (TV)
1. **TV APK latest copy** — 3.14.3/3.14.4 publish के बाद `C:\Ronin Ecosystem\TV\` में copy करना।
2. **Real TV जाँच (3-tap list)**: (a) YouTube खुला? (b) Ad आया? (c) Remote Back काम किया?
3. **TV CI flake fix** — TvTest continue-on-error है; YouTube page DOM बदलने पर script update।

---

## Secrets जाँच (28 Nov 2026)

| क्या मिला | Status |
|-----------|--------|
| `keys/ronin-keys.tar.enc` | ✅ Encrypted (AES-256), commit ठीक है। Plain keys commit नहीं हुए। |
| Gemini API key | ✅ Windows Credential Manager में, source code में नहीं। |
| `RONIN_KEYS_PASS` | ✅ GitHub Secret के रूप में, `${{ secrets.RONIN_KEYS_PASS }}` — hardcoded नहीं। |
| `.pem` / `.p12` / `.key` files | ✅ कोई plain key git history में नहीं मिली। |
| Source code में hardcoded secret | ✅ कोई नहीं मिला। |

**सब clear है।** कोई secret leak नहीं है।
