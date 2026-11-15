// Ronin Safe Social + Safe Mail: protection inside Facebook, Instagram, YouTube, WhatsApp Web, X,
// ShareChat... and inside Gmail, Outlook, Yahoo, Rediffmail (or any email site the family adds).
// - hides "Sponsored" posts and side ads (where the dirty animated ads come from); optional Reels / Shorts
// - every link is checked before it opens (RoninScam.link, ui/tools/scamcheck.js): Telegram, group
//   invites, UPI apps, call-forwarding codes, .apk, short links, AnyDesk, fake bank / KYC sites,
//   adult sites -> stopped, with NO "open anyway" (Ronin also stops them natively in Safe Social)
// - scam messages get a red / orange warning (score-based: never one word alone); forwarded-rumour
//   style gets a blue "check first" note with fact-check sites (about the form, never about a side)
// - Safe Mail: an opened email that looks like a scam / threat ("CBI digital arrest", "you watched
//   illegal videos, pay the fine") gets a red bar with [Delete] [Spam] [1930]
// - message requests (strangers) and video-call buttons get a calm, big warning first
// - in a Safe Social tab: big "← पीछे", "⊞ मेरे ऐप", "✕ बंद" buttons
// Settings come from Ronin in window.__roninSocialCfg (set before this script). Nothing leaves the device.
(function () {
  "use strict";
  if (window.__roninSocialDone) return;
  window.__roninSocialDone = true;
  var S = window.RoninScam;
  if (!S) return;
  // the phone's / computer's language (Android sets __roninLang); Hindi only on a Hindi device
  var EN = String(window.__roninLang || navigator.language || "en").toLowerCase().indexOf("hi") !== 0;
  function L(hi, en) { return EN ? en : hi; }
  var host = location.hostname.replace(/^www\.|^m\.|^mobile\.|^web\./, "");
  var SITES = /(^|\.)(facebook\.com|instagram\.com|youtube\.com|whatsapp\.com|x\.com|twitter\.com|threads\.net|threads\.com|sharechat\.com|mojapp\.in|moj\.com|linkedin\.com|snapchat\.com|pinterest\.com|telegram\.org|koo\.in|tiktok\.com|messenger\.com)$/;
  var MAIL = /(^|\.)(mail\.google\.com|outlook\.live\.com|outlook\.office\.com|outlook\.office365\.com|mail\.yahoo\.com|mail\.rediff\.com|rediffmail\.com|mail\.zoho\.com|mail\.zoho\.in|mail\.proton\.me|mail\.aol\.com)$/;
  function C() { return window.__roninSocialCfg || {}; }
  function isMail() {
    if (MAIL.test(location.hostname)) return true;
    var extra = C().mail || [];
    for (var i = 0; i < extra.length; i++) if (location.hostname === extra[i] || location.hostname.endsWith("." + extra[i])) return true;
    return false;
  }
  function active() { return SITES.test(host) || !!C().social || isMail(); }
  var counts = { ads: 0, links: 0, scam: 0, rumour: 0, mail: 0 };
  window.__roninSocialCounts = counts;

  // ---- styles (constructed sheets: allowed on strict CSP pages)
  function sheet(css) {
    try { var s = new CSSStyleSheet(); s.replaceSync(css); document.adoptedStyleSheets = document.adoptedStyleSheets.concat([s]); return; } catch (e) {}
    var st = document.createElement("style"); st.textContent = css; (document.head || document.documentElement).appendChild(st);
  }
  var F = "font-family:'Nirmala UI','Noto Sans Devanagari','Segoe UI',sans-serif;";
  var base = "#ronin-soc-btns{position:fixed;z-index:2147483646;top:6px;right:6px;display:flex;gap:6px}" +
    "#ronin-soc-btns a{display:flex;align-items:center;justify-content:center;min-height:44px;min-width:44px;padding:0 12px;border-radius:22px;background:rgba(17,24,39,.85);color:#fff;" + F + "font-weight:700;font-size:15px;text-decoration:none;box-shadow:0 2px 8px rgba(0,0,0,.35);white-space:nowrap}" +
    ".ronin-scam{display:block;margin:6px 0;padding:8px 12px;border-radius:10px;" + F + "font-weight:600;font-size:14px;line-height:1.45;background:#fff3cd;color:#6b3f00;border:2px solid #f0b43c}" +
    ".ronin-scam.ronin-stop{background:#fde2e2;color:#7f1010;border-color:#e04848}.ronin-rumour{background:#e0edff!important;color:#123a7a!important;border-color:#9cc0f5!important}" +
    ".ronin-scam a.ronin-fc{display:inline-block;margin:4px 6px 0 0;padding:4px 10px;border-radius:14px;background:#1d4ed8;color:#fff!important;text-decoration:none;font-size:13px}" +
    ".ronin-scam button,.ronin-bar button,#ronin-soc-warn button{" + F + "font-weight:700;cursor:pointer;border:0;border-radius:12px}" +
    ".ronin-age{display:inline-block;margin:6px 8px;padding:4px 12px;border-radius:12px;" + F + "font-weight:700;font-size:14px;background:#e5e7eb;color:#1f2937}.ronin-age.ronin-old{background:#fef3c7;color:#92400e;border:1px solid #f59e0b}" +
    ".ronin-pull{position:fixed;top:10px;left:50%;transform:translateX(-50%);z-index:2147483646;padding:8px 16px;border-radius:20px;background:#111827;color:#fff;" + F + "font-weight:700;font-size:15px;pointer-events:none}" +
    ".ronin-say{margin-left:6px;padding:3px 10px;background:#374151;color:#fff;font-size:13px}" +
    "#ronin-soc-warn{position:fixed;inset:0;z-index:2147483647;background:rgba(0,0,0,.65);display:flex;align-items:center;justify-content:center;padding:14px}" +
    "#ronin-soc-warn .box{max-width:480px;width:100%;background:#fff;color:#111;border-radius:18px;padding:20px;" + F + "font-size:18px;line-height:1.5;box-shadow:0 8px 30px rgba(0,0,0,.4);border-top:10px solid #dc2626}" +
    "#ronin-soc-warn .box.calm{border-top-color:#f59e0b}#ronin-soc-warn b{display:block;font-size:21px;margin-bottom:6px;color:#b91c1c}#ronin-soc-warn .calm b{color:#92400e}" +
    "#ronin-soc-warn .u{word-break:break-all;color:#555;font-size:13px;margin:8px 0}#ronin-soc-warn .safe{display:block;width:100%;min-height:56px;margin-top:12px;background:#16a34a;color:#fff;font-size:19px}" +
    "#ronin-soc-warn .small{display:block;margin:12px auto 0;background:none;color:#555;font-size:14px;text-decoration:underline}#ronin-soc-warn .help{background:#fee2e2;color:#7f1d1d;padding:8px 12px;margin-top:10px;font-size:15px}" +
    ".ronin-bar{position:fixed;left:8px;right:8px;top:56px;z-index:2147483645;padding:12px 14px;border-radius:14px;" + F + "font-size:16px;line-height:1.45;box-shadow:0 6px 24px rgba(0,0,0,.35);max-height:70vh;overflow:auto}" +
    ".ronin-bar.red{background:#fde2e2;color:#7f1010;border:3px solid #dc2626}.ronin-bar.amber{background:#fff3cd;color:#6b3f00;border:3px solid #f0b43c}.ronin-bar.info{background:#e0edff;color:#123a7a;border:2px solid #7aa7ef;top:auto;bottom:10px}" +
    ".ronin-bar b{display:block;font-size:18px;margin-bottom:4px}.ronin-bar ul{margin:4px 0 6px 18px;padding:0}.ronin-bar .row{display:flex;flex-wrap:wrap;gap:8px;margin-top:8px}" +
    ".ronin-bar .row button,.ronin-bar .row a{min-height:48px;padding:0 16px;font-size:16px;display:inline-flex;align-items:center;border-radius:12px;text-decoration:none;" + F + "font-weight:700}" +
    ".ronin-bar .del{background:#dc2626;color:#fff}.ronin-bar .spam{background:#7c2d12;color:#fff}.ronin-bar .call{background:#1d4ed8;color:#fff!important}.ronin-bar .hide{background:#e5e7eb;color:#111}";
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
  function el(tag, cls, txt) { var e = document.createElement(tag); if (cls) e.className = cls; if (txt != null) e.textContent = txt; return e; }
  function put(e) { (document.body || document.documentElement).appendChild(e); }

  // ---- voice: the short warning sentence only (never anyone's message)
  var SAY = EN ? {
    link: "Careful! This link is dangerous. Ronin has stopped it.",
    scam: "Careful! This message may be a scam. Never give money, an OTP or a PIN to anyone.",
    mail: "Careful! This email may be a scam. Delete it, and do not send any money.",
    call: "Do not video call a stranger.",
  } : {
    link: "सावधान! यह link खतरनाक है। Ronin ने इसे रोक दिया है।",
    scam: "सावधान! यह संदेश ठगी का हो सकता है। पैसे, ओटीपी या पिन किसी को न दें।",
    mail: "सावधान! यह ईमेल ठगी का हो सकता है। इसे डिलीट कर दें, और कोई पैसा न भेजें।",
    call: "अनजान व्यक्ति से वीडियो कॉल न करें।",
  };
  var lastSaid = {};
  function say(id, auto) {
    var now = Date.now();
    if (auto && now - (lastSaid[id] || 0) < 20000) return; // the same warning at most every 20 s by itself
    lastSaid[id] = now;
    try { if (window.roninNative && window.roninNative.postMessage) { window.roninNative.postMessage("speak:" + id); return; } } catch (e) {}
    try {
      var u = new SpeechSynthesisUtterance(SAY[id] || SAY.scam); u.lang = EN ? "en-IN" : "hi-IN";
      var v = speechSynthesis.getVoices().filter(function (x) { return EN ? /^en/i.test(x.lang) : /^hi/i.test(x.lang); });
      if (v.length) u.voice = v.filter(function (x) { return x.localService; })[0] || v[0];
      speechSynthesis.cancel(); speechSynthesis.speak(u);
    } catch (e) {}
  }
  function sayBtn(id) { var b = el("button", "ronin-say", L("🔊 सुनें", "🔊 Listen")); b.addEventListener("click", function (e) { e.preventDefault(); e.stopPropagation(); say(id); }, true); return b; }

  // ---- 1. sponsored posts away (Facebook / Instagram write "Sponsored" in many hidden ways)
  var SPONSOR = /^(sponsored|ad|ads|paidpartnership|प्रायोजित|विज्ञापन|sponsorisé|patrocinado|ప్రాయోజిత|প্রযোজিত|ਸਪਾਂਸਰ ਕੀਤਾ)$/i;
  // the button under an Instagram / Facebook ad (ordinary posts never have one)
  var CTA = /^(shop now|learn more|install now|book now|apply now|order now|buy now|get offer|get quote|play game|use app|अभी खरीदें|इंस्टॉल करें)$/i;
  function sponsoredIn(el0) {
    if (el0.querySelector("a[href*='/ads/about'],a[href*='ads/about'],[aria-label='Sponsored'],[aria-label='प्रायोजित']")) return true;
    // the label sits in the post's header, near the top: "Ad" / "Sponsored" (Facebook hides junk letters
    // inside it: what is SHOWN, innerText, is checked too)
    var bits = el0.querySelectorAll("span, a");
    for (var i = 0; i < bits.length && i < 80; i++) {
      var raw = bits[i].textContent || "";
      if (raw.length > 60) continue;
      var t = raw.replace(/\s+/g, "");
      if (t.length >= 2 && t.length < 25 && SPONSOR.test(t)) return true;
      if (raw.length < 60 && SPONSOR.test((bits[i].innerText || "").replace(/\s+/g, ""))) return true;
    }
    // Instagram / Facebook only: an ad's button is a link that LEAVES the site ("Shop now", "Install now")
    if (!/instagram\.com$|facebook\.com$/.test(host)) return false;
    var btns = el0.querySelectorAll("a[href]");
    for (var j = 0; j < btns.length && j < 80; j++) {
      var c = (btns[j].textContent || "").replace(/\s+/g, " ").trim();
      if (c.length < 20 && CTA.test(c) && /^https?:\/\/(l\.instagram\.com|l\.facebook\.com|lm\.facebook\.com)\/|^https?:\/\/(?!([a-z0-9-]+\.)*(instagram|facebook)\.com\/)/i.test(btns[j].href)) return true;
    }
    return false;
  }
  function hideAds(root) {
    if (!/facebook\.com$|instagram\.com$|threads\.(net|com)$/.test(host)) return;
    var units = (root.querySelectorAll ? root : document).querySelectorAll("[role=article], article, div[data-pagelet^='FeedUnit'], div[data-pagelet^='TimelineFeedUnit']");
    for (var i = 0; i < units.length; i++) {
      var u = units[i];
      // checked again on every change: the "Ad" label and the shop button arrive after the post
      // a post is looked at a few times only (its label comes within seconds), then left alone
      if (u.__roninAd || (u.__roninAdTries = (u.__roninAdTries || 0) + 1) > 6) continue;
      if (sponsoredIn(u)) { u.__roninAd = 1; u.style.setProperty("display", "none", "important"); counts.ads++; }
    }
  }

  // ---- 2. links checked before they open: no "open anyway"
  function helpBox() {
    var h = el("div", "help");
    h.appendChild(document.createTextNode(L("पैसा चला गया? तुरंत ", "Lost money? At once ")));
    var a = el("a", "", "📞 1930"); a.href = "tel:1930"; a.style.cssText = "font-weight:800;color:#1d4ed8";
    h.appendChild(a);
    h.appendChild(document.createTextNode(L(" पर call करें या cybercrime.gov.in पर शिकायत करें, और अपने बैंक को बताएँ।", " call, or report on cybercrime.gov.in, and tell your bank.")));
    return h;
  }
  function overlay(title, text, detail, calm, actions) {
    var old = document.getElementById("ronin-soc-warn");
    if (old) old.remove();
    var w = el("div"); w.id = "ronin-soc-warn";
    var box = el("div", "box" + (calm ? " calm" : ""));
    box.appendChild(el("b", "", title));
    box.appendChild(el("div", "", text));
    if (detail) box.appendChild(el("div", "u", detail));
    (actions || []).forEach(function (a) { box.appendChild(a); });
    w.appendChild(box);
    w.addEventListener("click", function (e) { if (e.target === w) w.remove(); });
    put(w);
    return w;
  }
  function warnLink(v) {
    var safe = el("button", "safe", L("✔ वापस (सुरक्षित)", "✔ Go back (safe)"));
    var w;
    safe.onclick = function () { w.remove(); };
    var note = el("div", "", L("Ronin ने यह link रोक दिया है, यह यहाँ से नहीं खुलेगा।", "Ronin has stopped this link; it will not open from here.")); note.style.cssText = "margin-top:8px;font-size:14px;color:#555";
    var row = el("div"); row.appendChild(sayBtn("link"));
    w = overlay("⛔ " + v.title, v.text, v.target.slice(0, 200), false, [safe, note, row, helpBox()]);
    counts.links++;
    say("link", true);
  }
  window.__roninSocialWarn = function (target) { var v = S.link(target); if (v) warnLink(v); }; // Ronin calls this after a native stop
  document.addEventListener("click", function (e) {
    if (!active()) return;
    var a = e.target && e.target.closest ? e.target.closest("a[href]") : null;
    if (!a) return;
    var v = S.link(a.getAttribute("href") || a.href, location.href);
    if (!v) return;
    e.preventDefault(); e.stopImmediatePropagation();
    warnLink(v);
  }, true);
  // window.open from page scripts goes through the same check
  try {
    var wopen = window.open;
    window.open = function (u) { if (active() && u) { var v = S.link(String(u), location.href); if (v) { warnLink(v); return null; } } return wopen.apply(window, arguments); };
  } catch (e) {}

  // ---- 3. messages / posts: red / orange warnings, blue "check first" for rumours
  var FACT = [["Vishvas News", "https://www.vishvasnews.com/?s="], ["BOOM", "https://www.boomlive.in/search?search_text="], ["Alt News", "https://www.altnews.in/?s="], ["Google", "https://www.google.com/search?q=fact+check+"]];
  function claim(text) {
    // a short claim line without phone numbers, ID numbers or email addresses
    return text.replace(/\S+@\S+/g, " ").replace(/\d{6,}/g, " ").replace(/https?:\/\/\S+/g, " ").replace(/[^\p{L}\p{M}\p{N}\s]/gu, " ").trim().split(/\s+/).slice(0, 9).join(" ");
  }
  function factLinks(text) {
    var span = el("span"), q = encodeURIComponent(claim(text));
    FACT.forEach(function (f) { var a = el("a", "ronin-fc", f[0]); a.href = f[1] + q; a.target = "_blank"; a.rel = "noopener"; span.appendChild(a); });
    return span;
  }
  function tag(n, r) {
    var t = el("div", "ronin-scam" + (r.level === "stop" ? " ronin-stop" : ""));
    t.appendChild(el("b", "", (r.level === "stop" ? L("⛔ Ronin: यह ठगी का संदेश हो सकता है", "⛔ Ronin: this may be a scam message") : L("⚠ Ronin: सावधान, ठगी के संकेत", "⚠ Ronin: careful, signs of a scam"))));
    t.appendChild(el("div", "", r.reasons.slice(0, 2).join(" • ")));
    t.appendChild(el("div", "", L("पैसे, OTP, PIN, फ़ोटो किसी अनजान को न दें। ", "Never give money, OTP, PIN or photos to a stranger. ")));
    t.lastChild.appendChild(sayBtn("scam"));
    try { n.parentNode.insertBefore(t, n); } catch (e) {}
    if (r.level === "stop") say("scam", true);
  }
  function rumourTag(n, text) {
    var t = el("div", "ronin-scam ronin-rumour");
    t.appendChild(el("div", "", L("ℹ जाँच ज़रूरी: इसमें \"सबको भेजें / 100% सच\" जैसी बातें हैं। आगे भेजने से पहले देखें, किसी fact-check वेबसाइट ने इसके बारे में क्या लिखा है:", "ℹ Check first: this says things like \"send to all / 100% true\". Before forwarding, see what a fact-check website says about it:")));
    t.appendChild(factLinks(text));
    try { n.parentNode.insertBefore(t, n); } catch (e) {}
  }
  function scanText(root) {
    var nodes = (root.querySelectorAll ? root : document).querySelectorAll("div[dir=auto], span[dir=auto], div[role=row] span.selectable-text, [data-testid='tweetText'], #content-text, .copyable-text span, h1, p");
    for (var i = 0, n; i < nodes.length && i < 500; i++) {
      n = nodes[i];
      if (n.__roninScam === 2 || n.closest(".ronin-scam,#ronin-soc-warn,.ronin-bar,[contenteditable=true]")) continue;
      // a caption that opens ("... more") or loads later is checked again; a warned one never twice.
      // the cheap length first: innerText (it makes the page measure itself) only when the text changed
      var tl = (n.textContent || "").length;
      if (n.__roninScamLen === tl) continue;
      n.__roninScamLen = tl;
      var t = n.innerText || "";
      if (t.length < 12 || t.length > 4000) continue;
      // the innermost text only: a parent holding the same text was or will be checked once
      if (n.querySelector("div[dir=auto], span[dir=auto]")) continue;
      var r = S.text(t);
      if (r.level) { n.__roninScam = 2; tag(n, r); counts.scam++; }
      else if (r.rumour) { n.__roninScam = 2; rumourTag(n, t); counts.rumour++; }
    }
  }

  // the words written ON a picture: Instagram / Facebook describe each photo in its alt text
  // ("May be an image of text that says 'Aviator hack 7 days ₹499, message me'"); a scam poster is caught too
  function scanPictures(root) {
    if (!/instagram\.com$|facebook\.com$|threads\.(net|com)$/.test(host)) return;
    var imgs = (root.querySelectorAll ? root : document).querySelectorAll("img[alt]");
    for (var i = 0, im; i < imgs.length && i < 200; i++) {
      im = imgs[i];
      var alt = im.getAttribute("alt") || "";
      if (im.__roninAlt === alt || alt.length < 20) continue;
      im.__roninAlt = alt;
      var said = alt.match(/(?:text that says|text which reads|लिखा है)\s*['"‘“]?([\s\S]+)/i);
      var r = S.text(said ? said[1] : alt);
      if (!r.level) continue;
      var box = im.closest("article") || im.parentNode && im.parentNode.parentNode;
      if (!box || box.__roninPicWarned) continue;
      box.__roninPicWarned = 1;
      var t = el("div", "ronin-scam" + (r.level === "stop" ? " ronin-stop" : ""));
      t.appendChild(el("b", "", L("⚠ Ronin: इस फ़ोटो / reel पर लिखी बात ठगी जैसी है", "⚠ Ronin: the words on this photo / reel look like a scam")));
      t.appendChild(el("div", "", r.reasons.slice(0, 2).join(" • ")));
      try { if (box.tagName === "ARTICLE") box.insertBefore(t, box.firstChild); else box.parentNode.insertBefore(t, box); } catch (e) {}
      counts.scam++;
    }
  }

  // ---- 4. strangers: message requests, unsaved numbers, video calls
  var strangerShown = "";
  function strangers() {
    var key = "", msg = "";
    var path = location.pathname + location.search;
    if (/facebook\.com$|messenger\.com$/.test(host) && /message_requests|\/requests|filtered/.test(path)) { key = path; msg = L("👤 ये Message Requests हैं: ये लोग आपके दोस्त नहीं हैं।", "👤 These are Message Requests: these people are not your friends."); }
    if (/instagram\.com$/.test(host) && /\/direct\/requests/.test(path)) { key = path; msg = L("👤 ये Message Requests हैं: ये लोग आपको follow नहीं करते / अनजान हैं।", "👤 These are Message Requests: these people do not follow you / are strangers."); }
    if (/whatsapp\.com$/.test(host)) {
      var h = document.querySelector("#main header span[title], #main header span[dir=auto]");
      var tt = h ? (h.getAttribute("title") || h.textContent || "").trim() : "";
      if (/^\+?\d[\d\s-]{8,}$/.test(tt)) { key = "wa:" + tt; msg = L("👤 यह नंबर (" + tt + ") आपके फ़ोन में सेव नहीं है: अनजान व्यक्ति है।", "👤 This number (" + tt + ") is not saved in your phone: a stranger."); }
    }
    var bar = document.getElementById("ronin-stranger");
    if (!key) { if (bar) bar.remove(); strangerShown = ""; return; }
    if (key === strangerShown && bar) return;
    strangerShown = key;
    if (bar) bar.remove();
    bar = el("div", "ronin-bar info"); bar.id = "ronin-stranger";
    bar.appendChild(el("b", "", msg));
    bar.appendChild(el("div", "", L("पैसे, OTP, फ़ोटो, घर का पता या video call की बात हो तो जवाब न दें। निवेश / नौकरी / गिफ़्ट / पुलिस की बात = ठगी।", "If they talk about money, OTP, photos, your address or a video call, do not reply. Investment / job / gift / police talk = scam.")));
    var row = el("div", "row"), ok = el("button", "hide", L("समझ गया", "Got it"));
    ok.onclick = function () { bar.remove(); };
    row.appendChild(ok); bar.appendChild(row);
    put(bar);
  }
  var CALL = /(video call|audio call|voice call|start a video|start a call|start call|वीडियो कॉल|ऑडियो कॉल|वॉइस कॉल|विडियो कॉल)/i;
  document.addEventListener("click", function (e) {
    if (!active() || isMail() || e.__roninOk) return;
    var b = e.target && e.target.closest ? e.target.closest("[aria-label],[title],[data-icon]") : null;
    if (!b) return;
    var lab = (b.getAttribute("aria-label") || b.getAttribute("title") || b.getAttribute("data-icon") || "");
    if (!CALL.test(lab) && !/^(video-call|voice-call)/.test(lab)) return;
    if (b.__roninCallOk && Date.now() - b.__roninCallOk < 60000) return;
    e.preventDefault(); e.stopImmediatePropagation();
    var no = el("button", "safe", L("📵 नहीं, call न करें", "📵 No, don't call")), yes = el("button", "small", L("हाँ, मैं इन्हें असल ज़िंदगी में जानता / जानती हूँ: call करें", "Yes, I know them in real life: call"));
    var w = overlay(L("📵 क्या आप इन्हें असल ज़िंदगी में जानते हैं?", "📵 Do you know this person in real life?"), L("अनजान लोग video call पर रिकॉर्ड करके ब्लैकमेल करते हैं। अनजान call न करें, न उठाएँ (कैमरा ढक लें)।", "Strangers record video calls and blackmail people. Do not call strangers or pick up (cover the camera)."), "", true, [no, yes]);
    no.onclick = function () { w.remove(); };
    yes.onclick = function () { w.remove(); b.__roninCallOk = Date.now(); b.click(); };
    say("call", true);
  }, true);

  // ---- 5. Safe Mail: the opened email
  function txt(sel, root) { var n = (root || document).querySelector(sel); return n ? (n.innerText || n.textContent || "").trim() : ""; }
  function readMail() {
    var m = { name: "", from: "", subject: "", body: "", files: [] }, main = document.querySelector("[role=main]") || document.body;
    if (/mail\.google\.com$/.test(location.hostname)) {
      m.subject = txt("h2.hP") || txt("h2[data-thread-perm-id]") || txt("[role=main] h2");
      var who = main.querySelector("span.gD[email], h3 span[email], span[email]");
      if (who) { m.from = who.getAttribute("email") || ""; m.name = who.getAttribute("name") || who.textContent || ""; }
      var bodies = main.querySelectorAll(".a3s");
      m.body = bodies.length ? (bodies[bodies.length - 1].innerText || "") : "";
      main.querySelectorAll("[download_url], .aV3, [aria-label^='Attachment']").forEach(function (a) {
        var d = a.getAttribute("download_url"), n = d ? d.split(":")[1] : (a.textContent || a.getAttribute("aria-label") || "").replace(/^Attachment:\s*/i, "");
        if (n) m.files.push(n.trim());
      });
    } else if (/outlook\./.test(location.hostname)) {
      m.subject = txt("[role=main] [role=heading][aria-level='2']") || txt("#ConversationReadingPaneContainer [role=heading]");
      var o = main.querySelector("[aria-label*='@'], span[title*='@']");
      if (o) { var s = o.getAttribute("title") || o.getAttribute("aria-label") || ""; m.from = (s.match(/[\w.+-]+@[\w-]+(\.[\w-]+)+/) || [""])[0]; m.name = (o.textContent || "").replace(m.from, "").replace(/[<>]/g, "").trim(); }
      m.body = txt("[aria-label='Message body']") || txt("div[role=document]");
      main.querySelectorAll("[role=listitem][title], [data-testid='AttachmentCard'] [title]").forEach(function (a) { if (/\.\w{2,4}$/.test(a.getAttribute("title"))) m.files.push(a.getAttribute("title")); });
    } else if (/yahoo\.com$/.test(location.hostname)) {
      m.subject = txt("[data-test-id=message-group-subject-text]");
      var y = main.querySelector("[data-test-id=message-from] [title], [data-test-id=email-pill] [title]");
      if (y) { m.from = y.getAttribute("title") || ""; m.name = y.textContent || ""; }
      m.body = txt("[data-test-id=message-view-body-content]");
      main.querySelectorAll("[data-test-id=attachment-name], [data-test-id=media-title]").forEach(function (a) { m.files.push(a.textContent.trim()); });
    }
    if (!m.body) {
      // any other email site: the reading area's text and the first address in it
      var area = document.querySelector("[role=main] [role=document], [role=main] article, [role=main]") || document.body;
      var all = (area.innerText || "").slice(0, 8000);
      if (all.length < 40) return null;
      m.body = all; m.subject = m.subject || document.title;
      m.from = m.from || (all.match(/[\w.+-]+@[\w-]+(\.[\w-]+)+/) || [""])[0];
    }
    if (!m.body && !m.subject) return null;
    return m;
  }
  function findButton(re, notRe) {
    var c = document.querySelectorAll("[role=button],button,[act],[data-tooltip],[aria-label]");
    for (var i = 0; i < c.length; i++) {
      var b = c[i], lab = (b.getAttribute("aria-label") || b.getAttribute("data-tooltip") || b.getAttribute("title") || "").trim();
      if (!lab || !re.test(lab) || (notRe && notRe.test(lab))) continue;
      if (b.closest(".ronin-bar")) continue;
      var r = b.getBoundingClientRect();
      if (r.width > 0 && r.height > 0) return b;
    }
    return null;
  }
  function press(b) {
    ["mousedown", "mouseup", "click"].forEach(function (t) { try { b.dispatchEvent(new MouseEvent(t, { bubbles: true, cancelable: true, view: window })); } catch (e) {} });
  }
  var DEL = /^(delete|हटाएं|हटाएँ|हटाएं|move to trash|trash|ट्रैश|मिटाएं)/i, SPAM = /^(report spam|spam|junk|report junk|report|स्पैम|move to junk|mark as spam)/i, NOTSPAM = /not spam|स्पैम नहीं/i;
  var mailSeen = "", mailTimer = 0;
  function mailCheck() {
    if (!isMail()) return;
    var bar = document.getElementById("ronin-mail");
    var m = readMail();
    var sig = m ? (m.from + "|" + m.subject + "|" + m.body.length) : "";
    if (!m) { if (bar) bar.remove(); mailSeen = ""; return; }
    if (sig === mailSeen) return;
    mailSeen = sig;
    if (bar) bar.remove();
    var r = S.mail(m);
    window.__roninMailLast = r; // for the tests
    if (!r.level) return;
    counts.mail++;
    bar = el("div", "ronin-bar " + (r.level === "stop" ? "red" : "amber")); bar.id = "ronin-mail";
    bar.appendChild(el("b", "", r.level === "stop" ? L("⛔ यह email ठगी / धमकी वाला हो सकता है", "⛔ This email may be a scam / threat") : L("⚠ सावधान: इस email में ठगी के संकेत हैं", "⚠ Careful: this email has signs of a scam")));
    var ul = el("ul");
    r.reasons.slice(0, 4).forEach(function (x) { ul.appendChild(el("li", "", x)); });
    bar.appendChild(ul);
    bar.appendChild(el("div", "", L("कोई जवाब न दें, कोई link या attachment न खोलें, कोई पैसा न भेजें। पुलिस / CBI / कोर्ट कभी email से जुर्माना नहीं माँगते।", "Do not reply, do not open any link or attachment, do not send money. Police / CBI / courts never ask for fines by email.")));
    var row = el("div", "row");
    var del = el("button", "del", L("🗑 Delete करें", "🗑 Delete")), spam = el("button", "spam", L("🚫 Spam में डालें", "🚫 Report spam"));
    var call = el("a", "call", "📞 1930"); call.href = "tel:1930";
    var hide = el("button", "hide", L("छिपाएँ", "Hide"));
    del.onclick = function () {
      var b = findButton(DEL);
      if (b) { press(b); bar.remove(); } else { del.textContent = L("ऊपर 🗑 (Delete) का निशान दबाएँ", "Tap the 🗑 (Delete) icon at the top"); }
    };
    spam.onclick = function () {
      var b = findButton(SPAM, NOTSPAM);
      if (b) { press(b); spam.textContent = L("✔ Spam में डाला (कुछ पूछे तो Report spam दबाएँ)", "✔ Reported as spam (if asked, tap Report spam)"); } else { spam.textContent = L("ऊपर ⋮ में Report spam दबाएँ", "Tap ⋮ at the top, then Report spam"); }
    };
    hide.onclick = function () { bar.remove(); };
    row.appendChild(del); row.appendChild(spam); row.appendChild(call); row.appendChild(hide); row.appendChild(sayBtn("mail"));
    bar.appendChild(row);
    put(bar);
    if (r.level === "stop") say("mail", true);
  }

  // ---- watch the page as it scrolls / loads more
  var queued = false;
  // "Open in the app" / "Use the Gmail app" banners: the family stays in the protected window
  var APP_TEXT = /^(open( in)?( the)? app|use (the )?app|get the app|open instagram|open facebook|open whatsapp|try the gmail app|use the gmail app|switch to the app|ऐप में खोलें|ऐप खोलें)$/i;
  function hideAppPromos(root) {
    var list = root.querySelectorAll("a[href*='play.google.com/store/apps'],a[href*='com.google.android.gm'],a[href^='intent:'],a,button,[role=button]");
    for (var i = 0; i < list.length && i < 400; i++) {
      var n = list[i];
      if (n.__roninPromo) continue;
      var href = n.getAttribute && (n.getAttribute("href") || "");
      var t = (n.textContent || "").replace(/\s+/g, " ").trim();
      if (!(/play\.google\.com\/store\/apps|com\.google\.android\.gm|^intent:/.test(href) || (t.length < 40 && APP_TEXT.test(t)))) continue;
      n.__roninPromo = true;
      // hide the small banner around it (not the page): climb while the box stays short
      var box = n;
      for (var up = 0; up < 3 && box.parentElement && box.parentElement !== document.body; up++) {
        var r = box.parentElement.getBoundingClientRect();
        if (r.height > 140 || r.height === 0) break;
        box = box.parentElement;
      }
      box.style.setProperty("display", "none", "important");
    }
  }
  function mailChip() {
    if (document.getElementById("ronin-mail-on") || !document.body) return;
    var c = el("div", "ronin-scam"); c.id = "ronin-mail-on";
    c.textContent = L("🛡 Safe Mail चालू: हर खोला गया email ठगी / धमकी के लिए जाँचा जा रहा है", "🛡 Safe Mail is on: every email you open is checked for scams / threats");
    c.style.cssText = "position:fixed;left:10px;right:10px;bottom:12px;z-index:2147483646;background:#dcfce7;color:#14532d;border-color:#16a34a;text-align:center;box-shadow:0 4px 14px rgba(0,0,0,.3)";
    put(c);
    setTimeout(function () { c.remove(); }, 6000);
  }
  function work() {
    queued = false;
    try { hideAds(document); hideAppPromos(document); if (!isMail()) { scanText(document); scanPictures(document); agePosts(document); } strangers(); } catch (e) {}
    if (isMail()) { clearTimeout(mailTimer); mailTimer = setTimeout(function () { try { mailCheck(); } catch (e) {} }, 300); }
  }
  // never in the way of the finger: the checks wait until scrolling stops, then run when the phone is idle
  // (Instagram changes the page all the time; checking during a scroll made it stutter)
  var lastMove = 0;
  function moved() { lastMove = Date.now(); }
  var adTimer = 0;
  function later() {
    // ads go at once (cheap); the heavier scam / picture checks wait for a still finger
    if (!adTimer) adTimer = setTimeout(function () { adTimer = 0; try { hideAds(document); } catch (e) {} }, 150);
    if (!queued) { queued = true; setTimeout(whenStill, 900); }
  }
  function whenStill() {
    if (Date.now() - lastMove < 400) { setTimeout(whenStill, 450); return; }
    if (window.requestIdleCallback) requestIdleCallback(work, { timeout: 2000 }); else work();
  }
  // old posts look like today's news: a clear date badge on anything older than 3 days
  function agePosts(root) {
    if (!/instagram\.com$|facebook\.com$|threads\.(net|com)$/.test(host)) return;
    var ts = root.querySelectorAll("article time[datetime]");
    for (var i = 0; i < ts.length && i < 60; i++) {
      var tm = ts[i], post = tm.closest("article");
      if (!post || post.__roninAge) continue;
      post.__roninAge = 1;
      var d = new Date(tm.getAttribute("datetime"));
      var days = Math.floor((Date.now() - d.getTime()) / 86400000);
      if (!(days >= 3)) continue;
      var y = Math.floor(days / 365), mo = Math.floor(days / 30);
      var ago = y >= 1 ? L(y + " साल पहले", y + (y > 1 ? " years" : " year") + " ago") : mo >= 1 ? L(mo + " महीने पहले", mo + (mo > 1 ? " months" : " month") + " ago") : L(days + " दिन पहले", days + " days ago");
      var b = el("div", "ronin-age" + (days > 30 ? " ronin-old" : ""), "📅 " + L("पुरानी पोस्ट: ", "Old post: ") + ago + " · " + d.toLocaleDateString(EN ? "en-IN" : "hi-IN", { day: "numeric", month: "short", year: "numeric" }));
      post.insertBefore(b, post.firstChild);
    }
  }
  // pull down at the top = fresh feed (like the real app)
  function pullToRefresh() {
    if (!C().social || window.__roninPull) return;
    window.__roninPull = 1;
    var y0 = -1, chip = null, dy = 0;
    // only on a feed page, only when nothing under the finger can scroll up itself (reels, comments, dialogs, chats)
    function feed() { return !/\/(reels?|shorts|watch|stories|direct|messages)(\/|$)/.test(location.pathname) && !/mail\.|web\.whatsapp|web\.telegram|outlook\./.test(host); }
    function innerScroll(n) {
      for (; n && n !== document.body && n !== document.documentElement; n = n.parentElement) {
        if (n.getAttribute && (n.getAttribute("role") === "dialog" || n.getAttribute("aria-modal") === "true")) return true;
        if (n.scrollTop > 0) return true;
        var oy = getComputedStyle(n).overflowY;
        if ((oy === "auto" || oy === "scroll") && n.scrollHeight > n.clientHeight + 4) return true;
      }
      return false;
    }
    addEventListener("touchstart", function (e) { y0 = (window.scrollY <= 0 && e.touches.length === 1 && feed() && !innerScroll(e.target)) ? e.touches[0].clientY : -1; dy = 0; }, { passive: true });
    addEventListener("touchmove", function (e) {
      if (y0 < 0) return;
      dy = e.touches[0].clientY - y0;
      if (dy > 30 && !chip) { chip = el("div", "ronin-pull", "↓"); put(chip); }
      if (chip) { chip.style.opacity = Math.min(1, dy / 120); chip.textContent = dy > 120 ? L("⟳ छोड़ें: नया लोड होगा", "⟳ Release to refresh") : "↓"; }
    }, { passive: true });
    addEventListener("touchend", function () {
      var go = y0 >= 0 && dy > 120 && window.scrollY <= 0;
      if (chip) { chip.remove(); chip = null; }
      y0 = -1;
      if (go) location.reload();
    }, { passive: true });
  }
  function googleFactCheck() {
    if (!/(^|\.)google\.[a-z.]+$/.test(location.hostname) || location.pathname !== "/search") return;
    var q = new URLSearchParams(location.search).get("q") || "";
    if (!q || /fact\s*check/i.test(q) || document.getElementById("ronin-fact")) return;
    var bar = el("div", "ronin-scam ronin-rumour"); bar.id = "ronin-fact";
    bar.style.cssText = "position:fixed;left:8px;bottom:8px;z-index:2147483646;max-width:380px";
    bar.appendChild(el("div", "", L("ℹ कोई खबर खोज रहे हैं? क्या यह सच है? जाँचें:", "ℹ Searching for a news story? Is it true? Check:")));
    bar.appendChild(factLinks(q));
    var x = el("button", "ronin-say", "✕"); x.onclick = function () { bar.remove(); }; bar.appendChild(x);
    put(bar);
  }
  function start() {
    googleFactCheck();
    if (!active()) return;
    styles();
    work();
    new MutationObserver(later).observe(document.documentElement, { childList: true, subtree: true });
    addEventListener("scroll", moved, { passive: true, capture: true });
    addEventListener("touchmove", moved, { passive: true });
    pullToRefresh();
    window.addEventListener("hashchange", later);
    if (C().social) buttons();
    if (isMail()) mailChip();
  }

  // ---- Safe Social tab: big labelled buttons
  function buttons() {
    var cfg = C();
    // Android has its own bar under the page (Back / My apps / Close); Ronin's own launcher page has its own buttons
    if (!cfg.k || cfg.nativeBar || host === "ronin.home" || document.getElementById("ronin-soc-btns")) return;
    var narrow = Math.min(window.innerWidth || 800, screen.width || 800) < 480;
    var box = el("div"); box.id = "ronin-soc-btns";
    var prev = el("a", "", L("← पीछे", "← Back")); prev.href = "#";
    prev.addEventListener("click", function (e) { e.preventDefault(); e.stopPropagation(); history.back(); }, true);
    var apps = el("a", "", narrow ? L("⊞ ऐप", "⊞ Apps") : L("⊞ मेरे ऐप", "⊞ My apps")); apps.href = "https://ronin.data/social?do=home&k=" + cfg.k;
    var close = el("a", "", L("✕ बंद", "✕ Close")); close.href = "https://ronin.data/social?do=close&k=" + cfg.k;
    box.appendChild(prev); box.appendChild(apps); box.appendChild(close);
    put(box);
    // sites rebuild the page: put the buttons back if they go
    setInterval(function () { if (!document.getElementById("ronin-soc-btns") && document.body) document.body.appendChild(box); }, 2000);
  }
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", start); else start();
  window.__roninSocialCheck = { verdict: function (u) { var v = S.link(u); return v ? [v.title, v.text] : null; }, scan: function (t) { var r = S.text(t); return r.level ? r.reasons.join(" • ") : ""; }, mail: mailCheck }; // for tests
})();
