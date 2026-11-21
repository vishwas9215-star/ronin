// Ronin Ad-block page layer: hides empty ad boxes and removes YouTube ads.
// Runs at the start of every page in PERSONAL, TRADING and PRIVATE windows.
(function () {
  "use strict";
  if (window.__roninAB) return;
  window.__roninAB = true;
  try { if (localStorage.getItem("ronin_ab_off") === "1") return; } catch (e) {}
  var host = location.hostname;
  var isYT = /(^|\.)youtube\.com$/.test(host) || /(^|\.)youtube-nocookie\.com$/.test(host);

  // ---- 1. generic ad boxes (safe selectors only) ----
  var css = "div[class*='cams-wgt'],a[href^='https://go.xlirdr.com'],a[href^='https://go.xlviiirdr.com'],a[href^='https://go.xlviirdr.com'],a[href^='https://go.strpjmp.com'],a[href*='chaturbate.com/in/'],a[href*='chaturbate.com/affiliates/'],iframe[src*='creative.stripchat'],iframe[src*='/widgets/'][src*='creative.'],ins.adsbygoogle,iframe[id^='google_ads_iframe'],div[id^='div-gpt-ad'],[data-ad-slot],[data-google-query-id],.adsbygoogle,amp-ad,amp-embed[type='taboola'],.taboola-container,#taboola-below-article-thumbnails,.OUTBRAIN,[id^='taboola-'],[class^='trc_rbox']{display:none!important}";

  if (isYT) {
    css += "ytd-ad-slot-renderer,ytd-in-feed-ad-layout-renderer,ytd-promoted-sparkles-web-renderer,ytd-promoted-video-renderer,ytd-display-ad-renderer,ytd-compact-promoted-video-renderer,ytd-companion-slot-renderer,ytd-action-companion-ad-renderer,ytd-banner-promo-renderer,ytd-statement-banner-renderer,ytd-video-masthead-ad-v3-renderer,ytd-primetime-promo-renderer,ytd-merch-shelf-renderer,ytd-player-legacy-desktop-watch-ads-renderer,ytd-rich-item-renderer:has(ytd-ad-slot-renderer),#player-ads,#masthead-ad,#panels ytd-ads-engagement-panel-content-renderer,ytd-engagement-panel-section-list-renderer[target-id='engagement-panel-ads'],.ytp-ad-overlay-container,.ytp-ad-image-overlay,.ytp-featured-product,.ytp-suggested-action,ytm-promoted-sparkles-web-renderer,ad-slot-renderer{display:none!important}";
  }
  function addStyle() {
    try { // allowed even where the page forbids <style> tags (CSP)
      if (window.CSSStyleSheet && "adoptedStyleSheets" in document) {
        var sh = new CSSStyleSheet();
        sh.replaceSync(css);
        document.adoptedStyleSheets = document.adoptedStyleSheets.concat([sh]);
        return;
      }
    } catch (e) {}
    var s = document.createElement("style");
    s.id = "ronin-ab";
    s.textContent = css;
    (document.head || document.documentElement).appendChild(s);
  }
  if (document.documentElement) addStyle();
  else document.addEventListener("DOMContentLoaded", addStyle);

  // ---- pop-up guard: on download pages every click opened an advert ----
  // 1. A click on a real link that makes the page open a DIFFERENT site in a new
  //    window is a hijack: the new window is not opened (the link itself still works).
  // 2. Invisible links laid over the whole page that lead to another site are removed.
  // Sign-in and payment windows are never touched.
  try {
    var bare = function (h) { return (h || "").toLowerCase().replace(/^www\./, ""); };
    var me = bare(host);
    var hostOfUrl = function (u) { try { return bare(new URL(u, location.href).hostname); } catch (e) { return ""; } };
    var sameSite = function (a, b) { return a === b || (a && b && (a.slice(-b.length - 1) === "." + b || b.slice(-a.length - 1) === "." + a)); };
    var SAFE = /(^|\.)(google\.com|google\.co\.in|microsoftonline\.com|live\.com|apple\.com|facebook\.com|razorpay\.com|paytm\.com|paytm\.in|payu\.in|ccavenue\.com|billdesk\.com|cashfree\.com|phonepe\.com|stripe\.com|paypal\.com|bank\.in|gov\.in|nic\.in|sbi|onlinesbi\.com|icicibank\.com|hdfcbank\.com|axisbank\.com|kotak\.com|kotakbank\.com|pnbindia\.in|bankofbaroda\.in|canarabank\.com|unionbankofindia\.co\.in|npci\.org\.in|bhimupi\.org\.in|digilocker\.gov\.in|meripehchaan\.gov\.in|udiseplus\.gov\.in|prernaup\.in)$/;
    var last = { t: 0, href: "" };
    document.addEventListener("click", function (e) {
      var a = e.target && e.target.closest ? e.target.closest("a[href]") : null;
      var raw = a ? (a.getAttribute("href") || "") : "";
      last = { t: Date.now(), href: a && raw && raw.charAt(0) !== "#" && !/^javascript:/i.test(raw) ? a.href : "" };
      if (!a) return;
      var to = hostOfUrl(a.href);
      if (!to || sameSite(to, me) || SAFE.test(to) || !/^https?:/.test(a.href)) return;
      var r = a.getBoundingClientRect(), cs = getComputedStyle(a);
      var covers = r.width * r.height >= innerWidth * innerHeight * 0.4;
      var empty = !a.textContent.trim() && !a.querySelector("img,svg,video,picture");
      if (covers && (empty || parseFloat(cs.opacity) < 0.15)) {
        e.preventDefault(); e.stopImmediatePropagation();
        a.remove();
        window.__roninPopBlocked = (window.__roninPopBlocked || 0) + 1;
      }
    }, true);
    var realOpen = window.open;
    window.open = function (u) {
      var to = hostOfUrl(u || "");
      // only a click on a REAL link (an address, not "#" or "javascript:") can be hijacked:
      // buttons and "#" links that open a window are the page's own way of working
      var hijack = Date.now() - last.t < 2000 && /^https?:/i.test(last.href) && !/#$/.test(last.href.split("?")[0]) && u &&
        new URL(u, location.href).href !== last.href && to && !sameSite(to, me) && !SAFE.test(to);
      if (hijack) {
        window.__roninPopBlocked = (window.__roninPopBlocked || 0) + 1;
        return null;
      }
      return realOpen.apply(window, arguments);
    };
  } catch (e) {}

  // Battery: instead of checking the page every second for ever, Ronin looks again only when the page
  // changes (at most once per `gap` ms), and not at all while the page is hidden (another tab, app closed).
  function watchDom(fn, gap) {
    var timer = 0, last = 0;
    var run = function () { timer = 0; if (document.hidden) return; last = Date.now(); fn(); };
    var soon = function () { if (document.hidden || timer) return; timer = setTimeout(run, Math.max(500, last + gap - Date.now())); };
    try { new MutationObserver(soon).observe(document, { childList: true, subtree: true }); } catch (e) {}
    document.addEventListener("visibilitychange", soon);
    soon();
  }

  // ---- TradingView: close "Upgrade / Go Pro" pop-ups and hide upgrade buttons ----
  if (/(^|\.)tradingview\.com$/.test(host)) {
    var PROMO = /(upgrade now|upgrade your plan|go pro|try free for|start free trial|black friday|cyber monday|get started for free|unlock .* with|your plan has|several charts per tab|limit of|sale ends|% off)/i;
    var tvClean = function () {
      try {
        var nodes = document.querySelectorAll("[data-dialog-name='gopro'], #overlap-manager-root > div, [class*='toast-'], [data-role='toast-container'] > *");
        for (var i = 0; i < nodes.length; i++) {
          var n = nodes[i];
          var isGoPro = n.getAttribute && n.getAttribute("data-dialog-name") === "gopro";
          var txt = (n.textContent || "").slice(0, 1500);
          if (!isGoPro && !PROMO.test(txt)) continue;
          if (!isGoPro && n.querySelector("input, textarea, [class*='symbol-search'], [data-name='symbol-search-items-dialog']")) continue; // a real tool, not an ad
          var x = n.querySelector("[data-name='close'], button[aria-label='Close'], button[class*='close']");
          if (x) x.click();
          if (n.isConnected) n.remove();
        }
        var ups = document.querySelectorAll("button, a");
        for (var j = 0; j < ups.length && j < 400; j++) {
          var t = (ups[j].textContent || "").trim();
          if (t === "Upgrade" || t === "Upgrade now" || t === "Go Pro") ups[j].style.setProperty("display", "none", "important");
        }
      } catch (e) {}
    };
    watchDom(tvClean, 700);
    return;
  }

  if (!isYT) { genericPlayerAds(); return; }

  // ---- 2. YouTube: if an ad starts, mute it and run it out at high speed ----
  // (Changing YouTube's player data can stop videos from playing, so Ronin
  // only touches the ad while it is on screen.)
  var adOn = false, savedMuted = false, savedRate = 1;
  setInterval(function () {
    var p = document.querySelector(".html5-video-player");
    var vid = p && p.querySelector("video.html5-main-video, video");
    if (!p || !vid) return;
    var showing = p.classList.contains("ad-showing") || p.classList.contains("ad-interrupting");
    if (showing) {
      if (!adOn) { adOn = true; savedMuted = vid.muted; savedRate = vid.playbackRate || 1; }
      vid.muted = true;
      try { if (vid.playbackRate < 16) vid.playbackRate = 16; } catch (e) {}
      var b = document.querySelector(".ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-skip-ad-button,button[id^='skip-button'],.ytp-ad-skip-button-container button");
      if (b) b.click();
    } else if (adOn) {
      adOn = false;
      try { vid.playbackRate = savedRate === 16 ? 1 : savedRate; } catch (e) {}
      vid.muted = savedMuted;
    }
  }, 500);

  // ---- 3. Other sites: video players that play an advert first ----
  // While the player shows "Skip ad" / "Ad - 00:08", the advert is muted, run
  // to its end and skipped. Only the player that shows such a label is touched,
  // and it is put back to normal as soon as the label is gone.
  function genericPlayerAds() {
    // only words that really mean "this is an advert": a bare "Skip" (skip intro / skip 10 s) or a bare
    // "Advertisement" caption of a banner next to the player is NOT one (owner 21 Nov 2026: the main video
    // on a video site was run at 16x as if it were an ad)
    var AD_LABEL = /^(skip ad|skip ads|skip ads?\s*[›»>▶].*|skip ad in \d+.*|skip in \d+\s*s?|you can skip (the )?ad.*|ad\s*[-–:|]?\s*\d{1,2}:\d{2}|ad\s*\d{1,2}:\d{2}|advertisement\s*[-–:|]?\s*\d{1,2}:\d{2}|ad \d+ of \d+.*)$/i;
    var CAN_SKIP = /^(skip ad|skip ads|skip ads?\s*[›»>▶].*)$/i;
    function visible(e) { return e.offsetWidth > 0 && e.offsetHeight > 0 && getComputedStyle(e).visibility !== "hidden"; }
    // the ad labels on the page that are short, visible texts
    function findLabels() {
      var out = [], all = document.body ? document.body.getElementsByTagName("*") : [];
      for (var i = 0; i < all.length && i < 6000; i++) {
        var e = all[i];
        if (e.children.length > 2) continue;
        var t = e.textContent;
        if (!t || t.length > 60) continue;
        t = t.replace(/\s+/g, " ").trim();
        if (!t || t.length > 30 || !AD_LABEL.test(t) || !visible(e)) continue;
        out.push({ e: e, r: e.getBoundingClientRect(), skip: CAN_SKIP.test(t) });
      }
      return out;
    }
    // runs only while a video plays (or an advert is being put back to normal): no timer on pages without video
    var tick = 0;
    var startTick = function () { if (!tick) tick = setInterval(checkPlayers, 400); };
    document.addEventListener("play", startTick, true);
    document.addEventListener("playing", startTick, true);
    function checkPlayers() {
      try {
        var vids = document.getElementsByTagName("video"), list = [], busy = false;
        for (var i = 0; i < vids.length && i < 6; i++) {
          if (vids[i].__roninAd || !vids[i].paused && !vids[i].ended) busy = true;
          if (vids[i].offsetWidth > 200 && vids[i].offsetHeight > 110 || vids[i].__roninAd) list.push(vids[i]);
        }
        if (!busy) { clearInterval(tick); tick = 0; return; }
        if (!list.length || document.hidden) return;
        var labels = findLabels();
        // each label belongs to the one video it is on (or nearest to, within 40 px)
        var rects = list.map(function (v) { return v.getBoundingClientRect(); });
        var ads = [], skips = [];
        for (var k = 0; k < labels.length; k++) {
          // the label must sit ON the video (an advert's own countdown / skip button), not beside it
          var lr = labels[k].r, cx = (lr.left + lr.right) / 2, cy = (lr.top + lr.bottom) / 2, best = -1, bestD = 6;
          for (var m = 0; m < rects.length; m++) {
            var r = rects[m];
            var dx = Math.max(r.left - cx, 0, cx - r.right), dy = Math.max(r.top - cy, 0, cy - r.bottom);
            var dist = Math.sqrt(dx * dx + dy * dy);
            if (dist < bestD) { bestD = dist; best = m; }
          }
          if (best < 0) continue;
          ads[best] = true;
          if (labels[k].skip && !skips[best]) skips[best] = labels[k].e;
        }
        for (var j = 0; j < list.length; j++) {
          var v = list[j], ad = !!ads[j], skip = skips[j], dur = v.duration;
          // a long video is the film itself, never a pre-roll; and nothing is "an ad" for more than 75 s
          if (ad && (isFinite(dur) && dur > 180 || v.__roninNotAd)) ad = false;
          if (ad && v.__roninAd && Date.now() - v.__roninAd.t > 75000) { v.__roninNotAd = true; ad = false; }
          // some players (Fluid Player) play the advert in the SAME <video> and then switch back to the film:
          // a new source means the advert is over - normal speed and sound at once
          if (v.__roninAd && v.__roninAd.s && v.currentSrc && v.currentSrc !== v.__roninAd.s) ad = false;
          if (ad) {
            if (!v.__roninAd) v.__roninAd = { m: v.muted, r: v.playbackRate || 1, t: Date.now(), s: v.currentSrc };
            v.muted = true;
            try { if (v.playbackRate < 16) v.playbackRate = 16; } catch (e) {}
            var d = v.duration;
            if (isFinite(d) && d > 0 && d <= 180 && v.currentTime < d - 0.5) {
              try { v.currentTime = d - 0.1; } catch (e) {}
            }
            if (skip) skip.click();
          } else if (v.__roninAd) {
            var o = v.__roninAd;
            v.__roninAd = null;
            try { v.playbackRate = o.r === 16 ? 1 : o.r; } catch (e) {}
            v.muted = o.m;
          }
        }
      } catch (e) {}
    }
    startTick();

    // small "AD" labelled promotions and "Advertising" captions above blocked boxes
    watchDom(function () {
      try {
        if (!document.body) return;
        var all = document.body.getElementsByTagName("*");
        for (var i = 0; i < all.length && i < 5000; i++) {
          var e = all[i];
          if (e.children.length) continue;
          var t = (e.textContent || "").trim();
          var badge = t === "AD" || t === "Ad";
          if (!badge && t !== "Advertising" && t !== "Advertisement" && t !== "Sponsored") continue;
          if (e.tagName === "A" || e.tagName === "BUTTON" || (badge && e.offsetWidth > 60)) continue;
          var p = e.parentElement;
          for (var k = 0; p && k < 3; k++, p = p.parentElement) {
            var pt = (p.textContent || "").replace(/\s+/g, " ").trim();
            if (pt.length > 160 || p.getElementsByTagName("video").length) break;
            if (p.querySelector(badge ? "a[href]" : "iframe")) {
              p.style.setProperty("display", "none", "important");
              break;
            }
          }
        }
      } catch (e) {}
    }, 1500);
  }

})();

