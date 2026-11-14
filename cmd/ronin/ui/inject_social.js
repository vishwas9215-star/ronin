// Ronin Safe Social: protection inside Facebook, Instagram, YouTube, WhatsApp Web, X, ShareChat...
// - hides "Sponsored" posts and side ads (where the dirty animated ads come from)
// - optional: hides Reels / Shorts
// - checks every link before it opens: Telegram, unknown WhatsApp groups, UPI / payment links,
//   app files (.apk), short links, adult sites -> a warning (Family mode: stopped)
// - marks messages / posts that look like a scam (investment, lottery, KYC, QR refund, task jobs...)
// - in a Safe Social tab: small "←" (one page back), "⊞" (other apps) and "✕" (close) buttons;
//   links between social sites (Instagram -> Threads) stay in the same full-screen tab
// Settings come from Ronin in window.__roninSocialCfg (set before this script).
(function () {
  "use strict";
  if (window.__roninSocialDone) return;
  window.__roninSocialDone = true;
  var host = location.hostname.replace(/^www\.|^m\.|^mobile\.|^web\./, "");
  var SITES = /(^|\.)(facebook\.com|instagram\.com|youtube\.com|whatsapp\.com|x\.com|twitter\.com|threads\.net|sharechat\.com|moj\.com|linkedin\.com|snapchat\.com|pinterest\.com|telegram\.org|koo\.in|tiktok\.com)$/;
  // settings are read when needed: Ronin may add the "this is a Safe Social tab" mark later
  function C() { return window.__roninSocialCfg || {}; }
  function active() { return SITES.test(host) || !!C().social; }

  // ---- styles (constructed sheets: allowed on strict CSP pages)
  function sheet(css) {
    try { var s = new CSSStyleSheet(); s.replaceSync(css); document.adoptedStyleSheets = document.adoptedStyleSheets.concat([s]); return; } catch (e) {}
    var st = document.createElement("style"); st.textContent = css; (document.head || document.documentElement).appendChild(st);
  }
  var base = "#ronin-soc-btns{position:fixed;z-index:2147483647;top:8px;right:8px;display:flex;gap:6px}" +
    "#ronin-soc-btns a{display:flex;align-items:center;justify-content:center;width:38px;height:38px;border-radius:19px;background:rgba(17,24,39,.78);color:#fff;font:700 20px sans-serif;text-decoration:none;box-shadow:0 2px 8px rgba(0,0,0,.35)}" +
    ".ronin-rumour{background:#e0edff!important;color:#123a7a!important;border-color:#9cc0f5!important}.ronin-scam{display:block;margin:4px 0;padding:6px 10px;border-radius:8px;background:#fff3cd;color:#7a4b00;border:1px solid #f0c36d;font:600 13px sans-serif}" +
    "#ronin-soc-warn{position:fixed;inset:0;z-index:2147483647;background:rgba(0,0,0,.6);display:flex;align-items:center;justify-content:center;padding:16px}" +
    "#ronin-soc-warn .box{max-width:460px;background:#fff;color:#111;border-radius:16px;padding:18px;font:16px sans-serif;line-height:1.5;box-shadow:0 8px 30px rgba(0,0,0,.4)}" +
    "#ronin-soc-warn b{display:block;font-size:19px;margin-bottom:6px;color:#b91c1c}#ronin-soc-warn .u{word-break:break-all;color:#555;font-size:13px;margin:6px 0}" +
    "#ronin-soc-warn button{margin:10px 8px 0 0;padding:10px 16px;border-radius:10px;border:0;font:600 15px sans-serif;cursor:pointer}" +
    "#ronin-soc-warn .safe{background:#16a34a;color:#fff}#ronin-soc-warn .go{background:#e5e7eb;color:#111}";
  function styles() {
  var cfg = C(), adCss = "";
  if (/facebook\.com$/.test(host)) adCss += "[data-pagelet='RightRail'] [aria-label*='Sponsored'],[data-pagelet='RightRail']>div>div:first-child:has(a[href*='/ads/']){display:none!important}";
  if (/youtube\.com$/.test(host)) adCss += "ytd-ad-slot-renderer,ytd-in-feed-ad-layout-renderer,ytm-promoted-sparkles-web-renderer,ytd-promoted-sparkles-web-renderer,ytd-banner-promo-renderer,ytm-companion-ad-renderer,#masthead-ad{display:none!important}";
  if (cfg.reels) {
    adCss += "ytd-reel-shelf-renderer,ytd-rich-shelf-renderer[is-shorts],ytm-reel-shelf-renderer,ytm-shorts-lockup-view-model,a[href^='/shorts']," +
      "ytd-guide-entry-renderer:has(a[title='Shorts']),ytd-mini-guide-entry-renderer[aria-label='Shorts'],ytm-pivot-bar-item-renderer:has(.pivot-shorts)," +
      "a[href*='/reels/'],a[href='/reels/'],div[aria-label='Reels'],[aria-label='Reels']{display:none!important}";
  }
  sheet(base + adCss);
  }

  // ---- 1. sponsored posts away (Facebook / Instagram write "Sponsored" in many hidden ways)
  var SPONSOR = /^(sponsored|प्रायोजित|sponsorisé|patrocinado|ప్రాయోజిత|প্রযোজিত|ਸਪਾਂਸਰ ਕੀਤਾ)$/i;
  function sponsoredIn(el) {
    if (el.querySelector("a[href*='/ads/about'],a[href*='ads/about'],[aria-label='Sponsored'],[aria-label='प्रायोजित']")) return true;
    var bits = el.querySelectorAll("a span, span[dir], div[dir] > span");
    for (var i = 0; i < bits.length && i < 60; i++) {
      var t = (bits[i].innerText || "").replace(/\s+/g, "");
      if (t.length > 3 && t.length < 25 && SPONSOR.test(t)) return true;
    }
    return false;
  }
  function hideAds(root) {
    if (!/facebook\.com$|instagram\.com$|threads\.net$/.test(host)) return;
    var units = (root.querySelectorAll ? root : document).querySelectorAll("[role=article], article, div[data-pagelet^='FeedUnit'], div[data-pagelet^='TimelineFeedUnit']");
    for (var i = 0; i < units.length; i++) {
      var u = units[i];
      if (u.__roninAd) continue;
      u.__roninAd = 1;
      if (sponsoredIn(u)) { u.style.setProperty("display", "none", "important"); counts.ads++; }
    }
  }

  // ---- 2. links checked before they open
  var SHORT = /^(bit\.ly|tinyurl\.com|cutt\.ly|rb\.gy|is\.gd|shorturl\.at|t\.ly|tiny\.cc|ow\.ly|rebrand\.ly|shorte\.st|adf\.ly|linktr\.ee|lnkd\.in|goo\.su|v\.gd|s\.id)$/;
  var ADULT = /(porn|xxx|xvideos|xnxx|xhamster|hentai|onlyfans|chaturbate|stripchat|escort|nude|camgirl|casino|betting|satta|matka|1xbet|dafabet|parimatch|rummy|teenpatti)/i;
  var MONEY = /(invest|trading|forex|crypto|bitcoin|usdt|profit|earn|loan|kyc|refund|cashback|lottery|prize|reward|giftcard|recharge|free-?money|task|job|work-?from-?home)/i;
  function realTarget(a) {
    var href = a.href || "";
    try {
      var u = new URL(href, location.href);
      // the sites' own "you are leaving" redirects
      if (/^l\.(facebook|instagram)\.com$|^lm\.facebook\.com$/.test(u.hostname) && u.searchParams.get("u")) return u.searchParams.get("u");
      if (/youtube\.com$/.test(u.hostname) && u.pathname === "/redirect" && u.searchParams.get("q")) return u.searchParams.get("q");
      return u.href;
    } catch (e) { return href; }
  }
  function verdict(target) {
    var u;
    try { u = new URL(target); } catch (e) { return null; }
    var h = u.hostname.replace(/^www\./, ""), full = h + u.pathname + u.search;
    if (/^(upi|phonepe|paytmmp|gpay|tez|bhim)/i.test(u.protocol)) return ["पैसे भेजने / UPI का link", "पैसा लेने के लिए कभी PIN डालना या QR scan करना नहीं पड़ता। अनजान के कहने पर पैसा न भेजें।"];
    if (/(^|\.)t\.me$|telegram\.(me|org|dog)$/.test(h)) return ["Telegram पर ले जाने वाला link", "ज़्यादातर ठगी (शेयर बाज़ार tips, task job, निवेश) Telegram groups से होती है। अनजान group join न करें।"];
    if (/^chat\.whatsapp\.com$/.test(h) || (/whatsapp\.com$|wa\.me$/.test(h) && /invite|join/.test(full))) return ["अनजान WhatsApp group का link", "अनजान group में निवेश, नौकरी, लॉटरी के नाम पर ठगी होती है।"];
    if (/\.apk(\?|$)/i.test(u.pathname)) return ["App file (.apk) download", "ऐसी app से फ़ोन hack हो सकता है। app सिर्फ़ Play Store से लें।"];
    if (ADULT.test(h)) return ["गंदी / जुआ वाली साइट", "यह साइट सुरक्षित नहीं है।"];
    if (SHORT.test(h)) return ["छोटा (छुपा हुआ) link: " + h, "यह link असली पता छुपाता है। भेजने वाले पर भरोसा न हो तो न खोलें।"];
    if (MONEY.test(h)) return ["पैसे / इनाम / निवेश वाली साइट: " + h, "ऐसी साइटें अक्सर ठगी के लिए बनती हैं।"];
    return null;
  }
  function warn(target, v, open) {
    var old = document.getElementById("ronin-soc-warn");
    if (old) old.remove();
    var w = document.createElement("div"); w.id = "ronin-soc-warn";
    var box = document.createElement("div"); box.className = "box";
    var b = document.createElement("b"); b.textContent = "⚠ सावधान: " + v[0];
    var p = document.createElement("div"); p.textContent = v[1];
    var u = document.createElement("div"); u.className = "u"; u.textContent = target.slice(0, 200);
    var safe = document.createElement("button"); safe.className = "safe"; safe.textContent = "✔ वापस (सुरक्षित)";
    safe.onclick = function () { w.remove(); };
    box.appendChild(b); box.appendChild(p); box.appendChild(u); box.appendChild(safe);
    // no "open anyway": a risky link never opens from here, not even by a slip of the finger
    var f = document.createElement("div"); f.style.cssText = "margin-top:8px;font-size:13px;color:#555"; f.textContent = "Ronin ने यह link रोक दिया है, यह यहाँ से नहीं खुलेगा।";
    box.appendChild(f);
    w.appendChild(box);
    w.addEventListener("click", function (e) { if (e.target === w) w.remove(); });
    (document.body || document.documentElement).appendChild(w);
    counts.links++;
  }
  document.addEventListener("click", function (e) {
    if (!active()) return;
    var a = e.target && e.target.closest ? e.target.closest("a[href]") : null;
    if (!a) return;
    var target = realTarget(a), v = verdict(target);
    if (!v) return;
    e.preventDefault(); e.stopImmediatePropagation();
    warn(target, v, function () { location.href = target; });
  }, true);

  // ---- 3. messages / posts that look like a scam get a warning above them
  var SCAM = [
    [/(guarante+d|पक्का|fixed|daily|रोज़?)\s*(profit|return|मुनाफ़?ा|कमाई|income)|double\s*(money|पैसा)|पैसा\s*डबल/i, "पक्का मुनाफ़ा / पैसा डबल का वादा: यह ठगी का सबसे आम तरीका है"],
    [/(stock|share|शेयर)\s*(market|बाज़?ार)?\s*(tips?|टिप्स|call|group)|(ipo|trading)\s*(tips|group|class)|vip\s*group/i, "शेयर बाज़ार tips / VIP group: Telegram-WhatsApp निवेश ठगी"],
    [/(task|टास्क|like\s*and\s*earn|youtube\s*like|review\s*job|part[\s-]*time\s*job|work\s*from\s*home|घर\s*बैठे\s*कमाएँ?)/i, "Task / part-time job: पहले थोड़ा पैसा देकर फिर बड़ा पैसा फँसाने वाली ठगी"],
    [/(lottery|लॉटरी|you\s*(have\s*)?won|आपने\s*जीत|इनाम|prize|lucky\s*draw|kbc|gift\s*card|गिफ्ट)/i, "लॉटरी / इनाम जीतने का message: असली इनाम के लिए पैसे नहीं माँगे जाते"],
    [/(kyc|केवाईसी|account\s*(will\s*be\s*)?(block|band|बंद|suspend)|खाता\s*बंद|pan\s*(update|link)|sim\s*(block|बंद))/i, "KYC / खाता बंद होने की धमकी: बैंक कभी message से ऐसा नहीं कहता"],
    [/(refund|रिफ़?ंड|cashback|कैशबैक|पैसा\s*वापस|scan\s*(the\s*)?qr|qr\s*(code)?\s*scan|qr\s*कोड)/i, "QR scan / पैसा वापस: पैसा लेने के लिए कभी QR scan या PIN नहीं डालते"],
    [/(otp|ओटीपी|pin|पिन)\s*(bata|batao|बताओ|बताएँ|share|भेजो|send)/i, "OTP / PIN माँगना: इसे कभी किसी को न बताएँ"],
    [/(video\s*call|वीडियो\s*कॉल).{0,40}(lady|girl|लड़की|अकेली|private|nude)|sextortion|cyber\s*police.{0,40}(case|fir)/i, "अनजान video call / धमकी: ब्लैकमेल ठगी हो सकती है, call न उठाएँ, पैसे न दें"],
    [/(crypto|bitcoin|usdt|forex)\s*(invest|trading|profit|earn|कमाई)/i, "Crypto / forex निवेश: इसमें ठगी बहुत होती है"],
  ];
  // forwarded rumours: Ronin cannot know if news is true, but these signs are typical of fake
  // news; it says so and gives a one-tap fact check (neutral: about the form, not about any side)
  var RUMOUR = [
    /(forward|फॉरवर्ड|शेयर|share)\s*(करें|करो|kare|karo|karein|this|zaroor|ज़रूर|जरूर|to\s*all|in\s*all\s*groups)|सभी\s*ग्रुप्स?\s*में\s*(भेजें|डालें)|10\s*(लोगों|groups)/i,
    /(media|मीडिया)\s*(नहीं\s*दिखाएगा|won.?t\s*show|is\s*hiding|छुपा\s*रहा)|(viral|वायरल)\s*(सच|truth)|100\s*%\s*(सच|true|confirmed)/i,
    /(सरकार|govt|government|मोदी|pm|yojana|योजना).{0,40}(free|मुफ़?्त|फ्री).{0,40}(recharge|laptop|scooty|स्कूटी|लैपटॉप|पैसे|₹|rs\.?\s*\d)/i,
    /(कैंसर|cancer|diabetes|शुगर|corona|covid|heart\s*attack).{0,40}(पक्का\s*इलाज|cure|ठीक\s*हो\s*जाएगा|गायब)|(miracle|चमत्कारी)\s*(cure|इलाज|नुस्खा)/i,
    /(breaking|ब्रेकिंग).{0,60}(अफवाह|सच\s*जानें)|(अभी\s*अभी|just\s*now).{0,40}(बंद|ban|cancel)\s*(होंगे|होगा|हो\s*जाएँगे)/i,
  ];
  function factButton(text) {
    var a = document.createElement("a");
    a.textContent = "🔎 सच्चाई जाँचें";
    a.href = "https://www.google.com/search?q=" + encodeURIComponent(text.replace(/\s+/g, " ").slice(0, 90) + " fact check");
    a.style.cssText = "margin-left:8px;color:#1d4ed8;text-decoration:underline;font-weight:700";
    return a;
  }
  function scanText(root) {
    var nodes = (root.querySelectorAll ? root : document).querySelectorAll("div[dir=auto], span[dir=auto], div[role=row] span, [data-testid='tweetText'], #content-text, .copyable-text span, h1, p");
    for (var i = 0; i < nodes.length && i < 400; i++) {
      var n = nodes[i];
      if (n.__roninScam || n.closest(".ronin-scam,#ronin-soc-warn")) continue;
      n.__roninScam = 1;
      var t = n.innerText || "";
      if (t.length < 12 || t.length > 3000) continue;
      var hit = false;
      for (var k = 0; k < SCAM.length; k++) {
        if (SCAM[k][0].test(t)) {
          var tag = document.createElement("span"); tag.className = "ronin-scam"; tag.textContent = "⚠ Ronin: " + SCAM[k][1];
          try { n.parentNode.insertBefore(tag, n); } catch (e) {}
          counts.scam++; hit = true;
          break;
        }
      }
      if (hit) continue;
      for (var r = 0; r < RUMOUR.length; r++) {
        if (RUMOUR[r].test(t)) {
          var tg = document.createElement("span"); tg.className = "ronin-scam ronin-rumour";
          tg.textContent = "ℹ Ronin: ऐसी खबरें अक्सर झूठी / अधूरी होती हैं। आगे भेजने से पहले जाँच लें।";
          tg.appendChild(factButton(t));
          try { n.parentNode.insertBefore(tg, n); } catch (e) {}
          counts.rumour++;
          break;
        }
      }
    }
  }

  // ---- watch the page as it scrolls / loads more
  var counts = { ads: 0, links: 0, scam: 0, rumour: 0 };
  window.__roninSocialCounts = counts;
  var queued = false;
  function work() { queued = false; try { hideAds(document); scanText(document); } catch (e) {} }
  function later() { if (!queued) { queued = true; setTimeout(work, 400); } }
  function googleFactCheck() {
    if (!/(^|\.)google\.[a-z.]+$/.test(location.hostname) || location.pathname !== "/search") return;
    var q = new URLSearchParams(location.search).get("q") || "";
    if (!q || /fact\s*check/i.test(q) || document.getElementById("ronin-fact")) return;
    var bar = document.createElement("div"); bar.id = "ronin-fact"; bar.className = "ronin-scam ronin-rumour";
    bar.style.cssText = "position:fixed;left:8px;bottom:8px;z-index:2147483646;max-width:360px";
    bar.textContent = "ℹ कोई खबर खोज रहे हैं? सच है या अफवाह:";
    bar.appendChild(factButton(q));
    (document.body || document.documentElement).appendChild(bar);
  }
  function start() {
    googleFactCheck();
    if (!active()) return;
    var cfg = C();
    styles();
    work();
    new MutationObserver(later).observe(document.documentElement, { childList: true, subtree: true });
    if (cfg.social) buttons();
  }

  // ---- Safe Social tab: ‹ other apps, ✕ close
  function buttons() {
    var cfg = C();
    if (!cfg.k || document.getElementById("ronin-soc-btns")) return;
    var box = document.createElement("div"); box.id = "ronin-soc-btns";
    // ← one page back (Threads opened from Instagram -> back to Instagram), ⊞ other apps, ✕ close
    var prev = document.createElement("a"); prev.textContent = "←"; prev.title = "पीछे"; prev.href = "#";
    prev.addEventListener("click", function (e) { e.preventDefault(); e.stopPropagation(); history.back(); }, true);
    var apps = document.createElement("a"); apps.textContent = "⊞"; apps.title = "दूसरा app"; apps.href = "https://ronin.data/social?do=home&k=" + cfg.k;
    var close = document.createElement("a"); close.textContent = "✕"; close.title = "बंद करें"; close.href = "https://ronin.data/social?do=close&k=" + cfg.k;
    box.appendChild(prev); box.appendChild(apps); box.appendChild(close);
    (document.body || document.documentElement).appendChild(box);
    // sites rebuild the page: put the buttons back if they go
    setInterval(function () { if (!document.getElementById("ronin-soc-btns") && document.body) document.body.appendChild(box); }, 2000);
  }
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", start); else start();
  window.__roninSocialCheck = { verdict: verdict, scan: function (t) { for (var k = 0; k < SCAM.length; k++) if (SCAM[k][0].test(t)) return SCAM[k][1]; return ""; } }; // for tests
})();
