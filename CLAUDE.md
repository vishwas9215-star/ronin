# CLAUDE.md — Ronin Browser (vishwas9215-star/ronin)

## मालिक के नियम (हर session शुरू करने से पहले पढ़ो)

**मालिक के बारे में:**
- दोनों हाथों में fracture है → उनसे कभी copy-paste, कोई manual step, कोई click नहीं करवाना।
- Coding / maths / software नहीं आता → सब Claude खुद करे, instructions simple Hindi में दे।
- हर जवाब **हिंदी में**, technical words **English** में।
- जो भी करना हो, **आगे क्या करना है** यह Claude बताए, मालिक को सोचने पर मजबूर न करे।

## काम के नियम

| नियम | विवरण |
|------|-------|
| **HANDOFF पहले** | हर नए session में `HANDOFF.md` पढ़ो। जो बन चुका है दोबारा मत बनाओ। |
| **Commit पहले** | हर बदलाव से पहले मौजूदा काम commit करो (ताकि rollback हो सके)। |
| **Test ज़रूरी** | बिना खुद test किए कोई delivery नहीं। Build: `./build.sh` (Go + JS + Playwright). |
| **Bug test** | हर fixed bug का एक permanent test बनाओ — वही गलती दोबारा न आए। |
| **Live publish** | सिर्फ़ तब publish करो जब मालिक version का नाम लेकर बोलें, जैसे "हाँ, V3.14.5 publish करो"। |
| **Short answer** | अंतिम जवाब 5–6 पंक्ति: क्या बना, क्या जाँचा, आगे क्या। बीच में कहानी नहीं। |
| **दूसरे chat का काम** | दूसरे session / PR का काम मत छेड़ो। |
| **Secrets** | `algo-key.pem`, `Algo-key.pem old`, `Dhan-Algo-Server.rdp` कभी read/stage मत करो। Gemini key, RONIN_KEYS_PASS, Proton .conf private keys कभी print/show मत करो। |
| **Passwords** | कभी password / OTP type मत करो — मालिक खुद करते हैं। |
| **Orders** | कोई order placement नहीं। |

## Dev branch
```
claude/determined-brown-uevep4
```
इसी branch पर काम करो। Main पर सीधे push नहीं।

## Build और publish
```bash
# Build (Linux — Windows .exe + Android APK CI पर):
./build.sh

# Publish (CI पर, मालिक की अनुमति के बाद):
gh workflow run build.yml --ref main -f publish=true
```

## TV APK
- TvActivity उसी APK में है (अलग नहीं)।
- TV APK manually copy होता है: `C:\Ronin Ecosystem\TV\Ronin-TV-<version>.apk`

## Keys
- `keys/ronin-keys.tar.enc` = encrypted (AES-256), committed करना ठीक है।
- Password सिर्फ़ GitHub secret `RONIN_KEYS_PASS` में और मालिक के backup में।
- Plain `.pem` / `.p12` / `.key` कभी commit नहीं होने चाहिए।

## QA नियम
- हर release से पहले independent QA review।
- Phone + tablet emulator screenshots CI पर। Real device check: 3-tap list delivery के साथ।
- CI green होने के बाद ही publish।
