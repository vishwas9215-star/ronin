// Ronin Night mode for web pages.
// Sites with their own dark design use it (the engine tells them Night mode is
// on). Plain white sites are turned dark by inverting the whole page: text and
// background change together, so text always stays readable. Pictures and
// videos are turned back to their real colours. Pages that are already dark
// are left alone. Only the top page decides (frames are handled by it).
(function () {
  if (window.top !== window || window.__roninDark) return;
  window.__roninDark = true;
  var CLASS = "ronin-dark", STYLE_ID = "ronin-dark-style";
  var CSS =
    "html.ronin-dark{filter:invert(1) hue-rotate(180deg) brightness(.92) contrast(.96)!important;background:#fff!important}" +
    "html.ronin-dark img,html.ronin-dark video,html.ronin-dark picture,html.ronin-dark canvas," +
    "html.ronin-dark iframe,html.ronin-dark embed,html.ronin-dark object,html.ronin-dark svg image," +
    "html.ronin-dark [style*='background-image'],html.ronin-dark [data-ronin-bgimg]" +
    "{filter:invert(1) hue-rotate(180deg)!important}" +
    "html.ronin-dark picture img,html.ronin-dark picture video{filter:none!important}" +
    // black logos and drawings on a transparent background stay visible:
    // they get a light backing (photos are not transparent, so they do not change)
    "html.ronin-dark img{background-color:#fff!important}" +
    "html.ronin-dark ::-webkit-scrollbar{background:#e6e6e6}";
  var mq = window.matchMedia ? window.matchMedia("(prefers-color-scheme: dark)") : null;

  function off() {
    try { return localStorage.getItem("ronin_dark_off") === "1"; } catch (e) { return false; }
  }
  function lum(c) {
    var m = /rgba?\(([\d.]+),\s*([\d.]+),\s*([\d.]+)(?:,\s*([\d.]+))?\)/.exec(c || "");
    if (!m) return -1;
    if (m[4] !== undefined && parseFloat(m[4]) < 0.5) return -1; // (almost) transparent
    return (0.2126 * m[1] + 0.7152 * m[2] + 0.0722 * m[3]) / 255;
  }
  // the colour actually behind the page content
  function pageLight() {
    var de = document.documentElement, body = document.body;
    if (!body) return false;
    var pts = [[0.5, 0.5], [0.25, 0.35], [0.75, 0.65], [0.5, 0.15]], light = 0, seen = 0;
    for (var i = 0; i < pts.length; i++) {
      var el = document.elementFromPoint(innerWidth * pts[i][0], innerHeight * pts[i][1]);
      for (; el && el !== de; el = el.parentElement) {
        var l = lum(getComputedStyle(el).backgroundColor);
        if (l >= 0) { seen++; if (l > 0.6) light++; break; }
      }
      if (!el || el === de) {
        var l2 = lum(getComputedStyle(body).backgroundColor);
        if (l2 < 0) l2 = lum(getComputedStyle(de).backgroundColor);
        seen++;
        if (l2 < 0 || l2 > 0.6) light++; // nothing painted = white
      }
    }
    return seen > 0 && light * 2 > seen;
  }
  function ensureStyle() {
    if (document.getElementById(STYLE_ID)) return;
    var s = document.createElement("style");
    s.id = STYLE_ID; s.textContent = CSS;
    (document.head || document.documentElement).appendChild(s);
  }
  // The decision is checked again while the page loads (its own colours may
  // arrive late) and then kept, so the page never flickers afterwards.
  // (The colour check reads the page's own colours; Ronin's filter does not change them.)
  var settled = false;
  function apply() {
    var de = document.documentElement;
    if (!de) return;
    var want = mq && mq.matches && !off();
    if (!want) { de.classList.remove(CLASS); return; }
    if (!document.body) return;
    if (settled && de.classList.contains(CLASS)) return;
    if (pageLight()) { ensureStyle(); de.classList.add(CLASS); } else de.classList.remove(CLASS);
  }
  window.__roninDarkApply = function () { settled = false; apply(); settled = true; };
  // decide early (no white flash), then again when the page is complete
  var tries = 0;
  (function early() {
    apply();
    if (++tries < 60 && document.readyState !== "complete") setTimeout(early, 50);
  })();
  document.addEventListener("DOMContentLoaded", apply);
  window.addEventListener("load", function () { apply(); setTimeout(function () { apply(); settled = true; }, 800); });
  if (mq) {
    var onChange = function () { window.__roninDarkApply(); };
    if (mq.addEventListener) mq.addEventListener("change", onChange); else if (mq.addListener) mq.addListener(onChange);
  }
})();
