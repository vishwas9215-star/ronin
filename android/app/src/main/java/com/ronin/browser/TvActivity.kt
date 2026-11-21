package com.ronin.browser

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream

/**
 * Ronin TV: YouTube's own TV screen (the one the remote control drives), without ads, full screen.
 * Opens from the Google TV home row (LEANBACK_LAUNCHER). Same APK as the phone, so one update reaches all.
 */
class TvActivity : AppCompatActivity() {
    lateinit var web: WebView
        private set

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (application as RoninApp).startLists()
        // the screen stays on only while a video plays (Bridge.playing); paused, the TV may sleep again
        web = WebView(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            isFocusable = true; isFocusableInTouchMode = true
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.userAgentString = TV_UA
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
        }
        CookieManager.getInstance().setAcceptCookie(true)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(web, NO_ADS_JS, setOf("https://www.youtube.com"))
        }
        web.addJavascriptInterface(Bridge(), "RoninTv")
        web.webChromeClient = object : WebChromeClient() {
            // owner 21 Nov 2026: the big white "▶ in a circle" before every song was Android's own video picture: none
            override fun getDefaultVideoPoster(): android.graphics.Bitmap = android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                val u = req.url.toString()
                val listed = !YT_HOST.containsMatchIn(req.url.host ?: "") && try { (application as RoninApp).blocker.blocked(u) } catch (e: Exception) { false }
                if (AD_URL.containsMatchIn(u) || listed) {
                    return WebResourceResponse("text/plain", "utf-8", 204, "No Content", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                }
                return null
            }
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                // YouTube and Google sign-in stay here; anything else (an advert page) is not opened on the TV
                return !YT_HOST.containsMatchIn(req.url.host ?: "")
            }
            /** Android ended the page engine (low memory on the TV): Ronin TV starts again instead of crashing. */
            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                (view.parent as? android.view.ViewGroup)?.removeView(view)
                view.destroy(); webGone = true
                finish()
                if (System.currentTimeMillis() - lastCrash > 30_000) { lastCrash = System.currentTimeMillis(); startActivity(android.content.Intent(this@TvActivity, TvActivity::class.java)) }
                return true
            }
            override fun onPageFinished(view: WebView, url: String?) {
                // without document-start scripts (old WebView): the same guard, a little later
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) view.evaluateJavascript(NO_ADS_JS, null)
            }
        }
        setContentView(web)
        hideBars()
        // Back on the remote = YouTube's own back (it asks before leaving at the home screen)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            private var last = 0L
            override fun handleOnBackPressed() {
                // YouTube not open (no internet / error page) or Back twice quickly with nothing happening: leave
                val now = System.currentTimeMillis()
                if (!(web.url ?: "").startsWith("https://www.youtube.com/tv") || now - last < 1500 && web.url == lastUrl) { finish(); return }
                last = now; lastUrl = web.url
                web.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE))
                web.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE))
            }
        })
        if (savedInstanceState == null) web.loadUrl(START) else web.restoreState(savedInstanceState)
        web.requestFocus()
        Updater.check(this, quiet = true)
    }

    private fun hideBars() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) hideBars() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); if (!webGone) web.saveState(outState) }
    override fun onPause() { super.onPause(); if (!webGone) web.onPause() }
    override fun onResume() { super.onResume(); if (!webGone) { web.resumeTimers(); web.onResume() }; Updater.resume(this) }
    override fun onDestroy() { if (!webGone) web.destroy(); super.onDestroy() }
    private var webGone = false

    private var lastUrl: String? = null

    inner class Bridge {
        /** YouTube's "Exit" on its home screen. */
        @JavascriptInterface fun exit() { runOnUiThread { finish() } }
        /** A video plays / stopped: the TV screen stays on only while one plays (battery, screen saver). */
        @JavascriptInterface fun playing(on: Boolean) {
            runOnUiThread { if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
    }

    companion object {
        const val START = "https://www.youtube.com/tv"
        /** A second page-engine crash within 30 s closes Ronin TV instead of restarting it again and again. */
        @Volatile private var lastCrash = 0L
        /** A living-room device name: YouTube then serves its TV screen (remote-control layout) instead of the phone site. */
        const val TV_UA = "Mozilla/5.0 (Linux; Android 12; Ronin TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 Cobalt/24.lts.30-gold v8/8.8.278.17-jit gles Starboard/15, Ronin_TV/3.11 (Google, TV, Wired)"
        val YT_HOST = Regex("(^|\\.)(youtube\\.com|youtu\\.be|ytimg\\.com|googlevideo\\.com|google\\.com|gstatic\\.com|googleapis\\.com|googleusercontent\\.com|ggpht\\.com|accounts\\.google\\.[a-z.]+)$")
        /** Advert calls of the TV player (the video and the app itself are never touched). */
        val AD_URL = Regex("://([a-z0-9-]+\\.)*(doubleclick\\.net|googlesyndication\\.com|googleadservices\\.com|google-analytics\\.com)/|youtube\\.com/(pagead/|api/stats/ads|ptracking|get_midroll_info|youtubei/v1/log_event)")
        /** The adverts' place in YouTube's answers is emptied before the player reads it; YouTube's "Exit" closes Ronin TV. */
        const val NO_ADS_JS = """
(function () {
  if (window.__roninTv) return; window.__roninTv = 1;
  var KEYS = ['adPlacements', 'playerAds', 'adSlots', 'adBreakHeartbeatParams', 'adBreakParams'];
  function prune(o, depth) {
    if (!o || typeof o !== 'object' || depth > 4) return o;
    for (var i = 0; i < KEYS.length; i++) if (KEYS[i] in o) { try { delete o[KEYS[i]]; } catch (e) {} }
    if (o.playerResponse) prune(o.playerResponse, depth + 1);
    if (Array.isArray(o)) for (var j = 0; j < o.length && j < 8; j++) prune(o[j], depth + 1);
    return o;
  }
  var P = JSON.parse;
  JSON.parse = function () { var r = P.apply(this, arguments); try { prune(r, 0); } catch (e) {} return r; };
  if (window.Response) { var RJ = Response.prototype.json; Response.prototype.json = function () { return RJ.apply(this, arguments).then(function (r) { try { prune(r, 0); } catch (e) {} return r; }); }; }
  window.close = function () { try { RoninTv.exit(); } catch (e) {} };
  var tell = function (on) { return function () { try { RoninTv.playing(on); } catch (e) {} }; };
  document.addEventListener('playing', tell(true), true);
  document.addEventListener('pause', tell(false), true);
  document.addEventListener('ended', tell(false), true);
  window.__roninTvPrune = function (o) { return prune(o, 0); };

  // ---- owner 21 Nov 2026: best picture by itself; a clean play screen without Like / Dislike / Comments / Ask (✦) /
  // Save / Subscribe / Description (CC, settings, previous / play / next stay; subscribing is done on the phone) ----
  var WANT = ['hd1080', 'hd1440', 'hd720', 'large', 'medium'];
  function best() {
    var p = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
    if (!p || typeof p.getAvailableQualityLevels !== 'function') return;
    var lv = p.getAvailableQualityLevels() || [], q = null;
    for (var i = 0; i < WANT.length && !q; i++) if (lv.indexOf(WANT[i]) >= 0) q = WANT[i];
    if (!q || (p.getPlaybackQuality && p.getPlaybackQuality() === q)) return;
    try { if (p.setPlaybackQualityRange) p.setPlaybackQualityRange(q, q); if (p.setPlaybackQuality) p.setPlaybackQuality(q); } catch (e) {}
  }
  var HIDE = /^(like|dislike|i like this|i dislike this|comments?|ask|ask about this video|save|save to playlist|subscribe|subscribed|unsubscribe|description|पसंद|नापसंद|टिप्पणी|टिप्पणियां|पूछें|सेव करें|सदस्यता लें|सदस्यता ली गई|विवरण)\b/i;
  function tidy() {
    if (!/watch/.test(location.hash + location.pathname)) return;
    var els = document.querySelectorAll('[aria-label],[title],button,[role=button],[tabindex],ytlr-button-renderer,ytlr-toggle-button-renderer');
    for (var i = 0; i < els.length && i < 600; i++) {
      var e = els[i];
      if (e.getAttribute('data-ronin-hidden')) continue;
      var name = (e.getAttribute('aria-label') || e.getAttribute('title') || e.textContent || '').replace(/\s+/g, ' ').trim();
      if (!name || name.length > 90 || !HIDE.test(name) || /views|ago|minutes|seconds|•/i.test(name)) continue;
      var r = e.getBoundingClientRect();
      // a button, never a video tile below the player ("Like a Prayer" is a song, not the Like button)
      if (!r.width || r.width > 420 || r.height > 160 || e.querySelector('img,video')) continue;
      var b = e;
      while (b.parentElement && b.parentElement !== document.body) {
        var pr = b.parentElement.getBoundingClientRect();
        if (pr.width - r.width > 12 || pr.height - r.height > 12) break;
        b = b.parentElement;
      }
      b.setAttribute('data-ronin-hidden', '1'); b.style.setProperty('display', 'none', 'important');
    }
  }
  function onHidden() {
    var a = document.activeElement;
    if (a && a.closest && a.closest('[data-ronin-hidden]')) return true;
    return !!document.querySelector('[data-ronin-hidden][class*=focused],[data-ronin-hidden] [class*=focused],[data-ronin-hidden][focused],[data-ronin-hidden] [focused]');
  }
  // the remote never lands on a hidden button: OK there does nothing; an arrow moves on once more
  var hops = 0;
  document.addEventListener('keydown', function (ev) {
    var k = ev.keyCode;
    if ((k === 13 || k === 23) && onHidden()) { ev.preventDefault(); ev.stopImmediatePropagation(); return; }
    if (k >= 37 && k <= 40 && ev.isTrusted !== false) {
      hops = 0;
      var again = function () {
        if (!onHidden() || hops++ > 7) return;
        var t = document.activeElement || document.body;
        ['keydown', 'keyup'].forEach(function (type) { try { t.dispatchEvent(new KeyboardEvent(type, { key: ev.key, code: ev.code, keyCode: k, which: k, bubbles: true })); } catch (e) {} });
        setTimeout(again, 60);
      };
      setTimeout(again, 60);
    }
  }, true);
  setInterval(function () { try { tidy(); best(); } catch (e) {} }, 1200);
  window.__roninTvTidy = function () { tidy(); best(); };
})();
"""
    }
}
