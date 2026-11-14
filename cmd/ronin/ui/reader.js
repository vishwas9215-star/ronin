// Ronin Reader mode: shows only the article (title, text, pictures).
(function () {
  "use strict";
  var wantListen = !!window.__roninListen; window.__roninListen = 0;
  if (document.getElementById("ronin-reader")) {
    if (wantListen && window.__roninReader) { window.__roninReader.listen(); return; }
    location.reload(); return;
  }
  var best = null, bestLen = 0;
  var cands = document.querySelectorAll("article, main, [role=main], div, section");
  for (var i = 0; i < cands.length; i++) {
    var el = cands[i], len = 0, ps = el.querySelectorAll(":scope > p, :scope > div > p, :scope > section > p");
    for (var j = 0; j < ps.length; j++) len += (ps[j].innerText || "").length;
    if (el.tagName === "ARTICLE") len *= 1.3;
    if (len > bestLen) { bestLen = len; best = el; }
  }
  if (!best || bestLen < 400) { alertless("Reader mode works on article pages."); return; }
  var title = (document.querySelector("h1") || {}).innerText || document.title;
  var parts = [];
  best.querySelectorAll("h2, h3, p, li, blockquote, img, figure figcaption").forEach(function (n) {
    if (n.closest("nav, aside, footer, form, [class*=share], [class*=related], [class*=comment]")) return;
    if (n.tagName === "IMG") {
      var src = n.currentSrc || n.src;
      if (src && /^https?:/.test(src) && (n.naturalWidth || 400) >= 200) parts.push({ t: "img", v: src });
      return;
    }
    var txt = (n.innerText || "").trim();
    if (txt.length < 2) return;
    parts.push({ t: n.tagName.toLowerCase(), v: txt });
  });
  var doc = document.createElement("div");
  doc.id = "ronin-reader";
  var style = document.createElement("style");
  style.textContent = "#ronin-reader{all:initial;display:block;min-height:100vh;background:#f7f3ea;color:#1f1f1f;font:20px/1.75 Georgia,'Nirmala UI','Mangal',serif}" +
    "#ronin-reader .w{max-width:720px;margin:0 auto;padding:48px 22px 80px}" +
    "#ronin-reader h1{font:700 34px/1.25 'Segoe UI','Nirmala UI',sans-serif;margin:0 0 24px}" +
    "#ronin-reader h2,#ronin-reader h3{font:700 24px/1.3 'Segoe UI','Nirmala UI',sans-serif;margin:32px 0 8px}" +
    "#ronin-reader p,#ronin-reader li,#ronin-reader blockquote{display:block;margin:0 0 18px}" +
    "#ronin-reader img{display:block;max-width:100%;height:auto;margin:20px auto;border-radius:8px}" +
    "#ronin-reader .bar{position:fixed;top:12px;right:16px;font:600 14px 'Segoe UI',sans-serif;background:#1f1f1f;color:#fff;border:0;border-radius:18px;padding:8px 16px;cursor:pointer}" +
    "#ronin-reader .say{position:fixed;left:50%;bottom:18px;transform:translateX(-50%);display:flex;gap:6px;align-items:center;background:#1f1f1f;color:#fff;border-radius:26px;padding:6px;font:600 15px 'Segoe UI','Nirmala UI',sans-serif;box-shadow:0 6px 24px #0005;z-index:2}" +
    "#ronin-reader .say button{all:unset;cursor:pointer;padding:10px 16px;border-radius:20px;background:#333;color:#fff;font:600 15px 'Segoe UI','Nirmala UI',sans-serif}" +
    "#ronin-reader .say button.main{background:#16a34a;min-width:96px;text-align:center}" +
    "#ronin-reader .say .note{padding:0 10px;font-weight:400;font-size:13px;max-width:340px}" +
    "#ronin-reader .speak{background:#fde68a;border-radius:6px;box-shadow:0 0 0 6px #fde68a}" +
    "#ronin-reader .w>p,#ronin-reader .w>h2,#ronin-reader .w>h3,#ronin-reader .w>blockquote{cursor:pointer}";
  var w = document.createElement("div");
  w.className = "w";
  var h = document.createElement("h1");
  h.textContent = title;
  w.appendChild(h);
  parts.forEach(function (p) {
    var e;
    if (p.t === "img") { e = document.createElement("img"); e.src = p.v; e.loading = "lazy"; }
    else { e = document.createElement(p.t === "li" ? "p" : (p.t === "figcaption" ? "p" : p.t)); e.textContent = (p.t === "li" ? "• " : "") + p.v; }
    w.appendChild(e);
  });
  var exit = document.createElement("button");
  exit.className = "bar";
  exit.textContent = "Exit Reader";
  exit.onclick = function () { location.reload(); };
  exit.onclick = function () { try { speechSynthesis.cancel(); } catch (e) {} location.reload(); };
  var say = player(w);
  doc.appendChild(style);
  doc.appendChild(w);
  doc.appendChild(exit);
  doc.appendChild(say.bar);
  document.body.innerHTML = "";
  document.body.style.cssText = "margin:0;background:#f7f3ea";
  document.body.appendChild(doc);
  window.scrollTo(0, 0);
  window.__roninReader = { listen: say.listen };
  if (wantListen) say.listen();

  // ---- Listen: reads the article aloud with the Windows voices, paragraph by
  // paragraph (Hindi text with a Hindi voice). Tap a paragraph to start there.
  function player(w) {
    var S = window.speechSynthesis, bar = document.createElement("div"), note = document.createElement("span");
    bar.className = "say"; note.className = "note";
    var main = button("\u25B6 Listen", "main"), stop = button("\u25A0", ""), rate = button("1x", "");
    var rates = [1, 1.25, 1.5, 0.8], ri = 0, i = -1, playing = false, cur = null;
    bar.appendChild(main); bar.appendChild(stop); bar.appendChild(rate); bar.appendChild(note);
    stop.title = "Stop"; rate.title = "Speed";
    function button(t, c) { var b = document.createElement("button"); b.textContent = t; b.className = c; return b; }
    function items() { return Array.prototype.filter.call(w.children, function (e) { return e.tagName !== "IMG" && (e.textContent || "").trim().length > 1; }); }
    function isHindi(t) { var d = (t.match(/[\u0900-\u097F]/g) || []).length, l = (t.match(/[A-Za-z]/g) || []).length; return d > 0 && d >= l; }
    function voiceFor(hindi) {
      var vs = S.getVoices(), want = hindi ? /^hi/i : /^en-IN/i, v = null;
      for (var k = 0; k < vs.length && !v; k++) if (want.test(vs[k].lang)) v = vs[k];
      if (!v && !hindi) for (k = 0; k < vs.length && !v; k++) if (/^en/i.test(vs[k].lang)) v = vs[k];
      return v;
    }
    function pieces(t) { // long paragraphs in sentences (some voices stop on very long text)
      var out = [], parts = t.replace(/^\u2022\s*/, "").split(/(?<=[.!?\u0964])\s+/);
      var buf = "";
      parts.forEach(function (x) { if ((buf + " " + x).length > 220 && buf) { out.push(buf); buf = x; } else buf = buf ? buf + " " + x : x; });
      if (buf) out.push(buf);
      return out;
    }
    function mark(e) { if (cur) cur.classList.remove("speak"); cur = e; if (e) { e.classList.add("speak"); e.scrollIntoView({ block: "center", behavior: "smooth" }); } }
    function show() { main.textContent = playing ? "\u275A\u275A Pause" : (i >= 0 ? "\u25B6 Continue" : "\u25B6 Listen"); }
    function speakFrom(n) {
      var list = items();
      if (n >= list.length) { end(); return; }
      i = n; mark(list[n]);
      var text = list[n].textContent, hindi = isHindi(text), v = voiceFor(hindi);
      note.textContent = (hindi && !v) ? "Hindi voice is not installed in Windows: Settings \u2192 Time & language \u2192 Speech \u2192 Add voices \u2192 Hindi" : "";
      var ps = pieces(text), k = 0, myRun = ++run;
      (function next() {
        if (myRun !== run || !playing) return;
        if (k >= ps.length) { speakFrom(n + 1); return; }
        var u = new SpeechSynthesisUtterance(ps[k++]);
        if (v) { u.voice = v; u.lang = v.lang; } else u.lang = hindi ? "hi-IN" : "en-IN";
        u.rate = rates[ri];
        u.onend = next; u.onerror = function (e) { if (e.error !== "interrupted" && e.error !== "canceled") next(); };
        S.speak(u);
      })();
    }
    var run = 0;
    function end() { playing = false; run++; S.cancel(); mark(null); i = -1; show(); }
    function listen() {
      if (!S) { note.textContent = "Reading aloud is not available here."; return; }
      if (S.getVoices().length === 0 && !listen.waited) { listen.waited = true; S.onvoiceschanged = function () { S.onvoiceschanged = null; listen(); }; setTimeout(function () { if (!playing) listen(); }, 800); return; }
      playing = true; show();
      if (S.paused && i >= 0) { S.resume(); return; }
      speakFrom(i >= 0 ? i : 0);
    }
    main.onclick = function () { if (playing) { playing = false; S.pause(); show(); } else listen(); };
    stop.onclick = end;
    rate.onclick = function () { ri = (ri + 1) % rates.length; rate.textContent = rates[ri] + "x"; if (playing) { S.cancel(); playing = true; speakFrom(i); } };
    w.addEventListener("click", function (e) {
      var list = items(), t = e.target;
      while (t && t.parentNode !== w) t = t.parentNode;
      var n = list.indexOf(t);
      if (n < 0) return;
      S.cancel(); playing = true; show(); speakFrom(n);
    });
    show();
    return { bar: bar, listen: listen };
  }
  function alertless(msg) {
    var n = document.createElement("div");
    n.textContent = msg;
    n.style.cssText = "position:fixed;top:14px;left:50%;transform:translateX(-50%);z-index:2147483647;background:#1f1f1f;color:#fff;padding:10px 18px;border-radius:20px;font:14px 'Segoe UI',sans-serif";
    document.documentElement.appendChild(n);
    setTimeout(function () { n.remove(); }, 2500);
  }
})();
