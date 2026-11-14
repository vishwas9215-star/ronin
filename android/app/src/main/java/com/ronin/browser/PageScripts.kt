package com.ronin.browser

/** Small page scripts Ronin runs inside pages (JavaScript text). */
object PageScripts {

    /** Sites where songs / videos keep playing when the app is in the background. */
    val backgroundOrigins = setOf(
        "https://m.youtube.com", "https://www.youtube.com", "https://youtube.com", "https://music.youtube.com"
    )

    /**
     * Runs at the very start of YouTube pages: the page always believes it is
     * visible (so it does not stop the song when the app is swiped away), and
     * Ronin remembers the page's own next / previous handlers.
     */
    const val BACKGROUND_PLAY = """
(function(){
  if (window.__roninBG) return; window.__roninBG = 1;
  try {
    Object.defineProperty(Document.prototype, 'hidden', {get: function(){ return false; }, configurable: true});
    Object.defineProperty(Document.prototype, 'webkitHidden', {get: function(){ return false; }, configurable: true});
    Object.defineProperty(Document.prototype, 'visibilityState', {get: function(){ return 'visible'; }, configurable: true});
    Object.defineProperty(Document.prototype, 'webkitVisibilityState', {get: function(){ return 'visible'; }, configurable: true});
  } catch (e) {}
  var stop = function(e){ e.stopImmediatePropagation(); };
  ['visibilitychange','webkitvisibilitychange','pagehide','freeze'].forEach(function(t){
    window.addEventListener(t, stop, true); document.addEventListener(t, stop, true);
  });
  // In the background no frames are drawn, so animation-frame callbacks stop and the
  // player cannot start the next song. Each callback also runs from a short timer if no
  // frame comes.
  try {
    var raf = window.requestAnimationFrame.bind(window), caf = window.cancelAnimationFrame.bind(window);
    var seq = 0, live = {};
    window.requestAnimationFrame = function(cb){
      var id = ++seq, done = false, r = 0, tm = 0;
      var run = function(ts){ if (done) return; done = true; delete live[id]; caf(r); clearTimeout(tm); cb(ts); };
      r = raf(run);
      tm = setTimeout(function(){ run(performance.now()); }, 250);
      live[id] = [r, tm]; return id;
    };
    window.cancelAnimationFrame = function(id){
      var e = live[id]; if (!e) return; delete live[id]; caf(e[0]); clearTimeout(e[1]);
    };
  } catch (e) {}
  window.__roninMS = {};
  try {
    var ms = navigator.mediaSession;
    if (ms && ms.setActionHandler) {
      var orig = ms.setActionHandler.bind(ms);
      ms.setActionHandler = function(a, h){ window.__roninMS[a] = h; try { orig(a, h); } catch (e) {} };
    }
  } catch (e) {}
})();
"""

    /** What is playing: {"v":hasVideo,"p":playing,"t":title,"a":artist}. */
    const val MEDIA_STATE = """
(function(){
  var vs = document.querySelectorAll('video,audio'), v = null;
  for (var i = 0; i < vs.length; i++) { if (!vs[i].paused) { v = vs[i]; break; } }
  if (!v && vs.length) v = vs[0];
  if (!v) return JSON.stringify({v:false});
  var m = navigator.mediaSession && navigator.mediaSession.metadata;
  var t = (m && m.title) || document.title.replace(/ - YouTube( Music)?$/, '');
  return JSON.stringify({v:true, p:!v.paused && !v.ended && v.readyState > 1, t:t || 'Ronin', a:(m && m.artist) || location.hostname.replace(/^(www|m)\./, ''), c:v.currentTime || 0});
})()
"""

    /** play / pause / nexttrack / previoustrack on the page. */
    fun mediaCommand(action: String): String = """
(function(a){
  var vs = document.querySelectorAll('video,audio'), v = vs.length ? vs[0] : null;
  for (var i = 0; i < vs.length; i++) { if (!vs[i].paused) { v = vs[i]; break; } }
  var h = window.__roninMS && window.__roninMS[a];
  if (a === 'play') { if (v) v.play(); return 'ok'; }
  if (a === 'pause') { if (v) v.pause(); return 'ok'; }
  if (h) { try { h({action:a}); return 'ok'; } catch (e) {} }
  if (a === 'nexttrack') {
    var b = document.querySelector('.ytp-next-button, button[aria-label^="Next"], [aria-label="Next video"]');
    if (b) { b.click(); return 'ok'; }
    if (v && v.duration) v.currentTime = v.duration - 0.1;
    return 'ok';
  }
  if (a === 'previoustrack') {
    if (v && v.currentTime > 3) { v.currentTime = 0; return 'ok'; }
    history.back(); return 'ok';
  }
  return 'no';
})(${org.json.JSONObject.quote(action)})
"""

    /** The article's paragraphs (for reading aloud), as a JSON array of text. */
    const val ARTICLE_TEXT = """
(function(){
  var best = null, bestLen = 0, c = document.querySelectorAll('article, main, [role=main], div, section');
  for (var i = 0; i < c.length; i++) {
    var len = 0, ps = c[i].querySelectorAll(':scope > p, :scope > div > p, :scope > section > p');
    for (var j = 0; j < ps.length; j++) len += (ps[j].innerText || '').length;
    if (c[i].tagName === 'ARTICLE') len *= 1.3;
    if (len > bestLen) { bestLen = len; best = c[i]; }
  }
  var out = [], h1 = document.querySelector('h1');
  if (h1 && h1.innerText.trim()) out.push(h1.innerText.trim());
  if (best && bestLen >= 200) {
    best.querySelectorAll('h2, h3, p, li, blockquote').forEach(function(n){
      if (n.closest('nav, aside, footer, form')) return;
      var t = (n.innerText || '').trim(); if (t.length > 1) out.push(t);
    });
  } else {
    (document.body ? document.body.innerText : '').split(/\n+/).forEach(function(t){ t = t.trim(); if (t.length > 30) out.push(t); });
  }
  return JSON.stringify(out.slice(0, 400));
})()
"""
}
