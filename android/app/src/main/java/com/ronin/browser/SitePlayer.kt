package com.ronin.browser

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream

/**
 * Ronin YouTube / Ronin Music (phone): ONE page per app that keeps playing with the screen off, without ads.
 * The page lives here (not in the screen), so the car (Android Auto) can start a song even when the app is closed.
 */
object SitePlayer {
    @SuppressLint("StaticFieldLeak") private var web: RoninWebView? = null
    private val ui = Handler(Looper.getMainLooper())
    private var title = ""; private var artist = ""; private var playing = false
    private var retries = 0
    /** Car screen (Ronin Music): gets every change of song / play state. */
    @Volatile var onState: ((title: String, artist: String, playing: Boolean) -> Unit)? = null

    fun start(ctx: Context): String = ctx.getString(R.string.ronin_site_url)
    private fun hostOk(h: String) = TvActivity.YT_HOST.containsMatchIn(h)

    /** The page, made once per app process (main thread). */
    @SuppressLint("SetJavaScriptEnabled")
    fun page(ctx: Context): RoninWebView {
        web?.let { return it }
        val app = ctx.applicationContext
        (app as? RoninApp)?.startLists()
        val w = RoninWebView(app).apply {
            setBackgroundColor(0xFF0F0F0F.toInt())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            // Google refuses sign-in inside "web views": the same plain phone name the Ronin browser uses
            settings.userAgentString = WebSettings.getDefaultUserAgent(app).replace("; wv", "").replace(Regex("Version/\\d+(\\.\\d+)* "), "")
            keepPlaying = true
        }
        CookieManager.getInstance().setAcceptCookie(true)
        // Restricted Mode from the very first page (YouTube keeps it in the PREF cookie, flag f2=8000000)
        if (CookieManager.getInstance().getCookie("https://m.youtube.com")?.contains("PREF=") != true)
            CookieManager.getInstance().setCookie("https://m.youtube.com", "PREF=f2=8000000; Domain=.youtube.com; Path=/; Max-Age=63072000; Secure")
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(w, PageScripts.BACKGROUND_PLAY + TvActivity.NO_ADS_JS + HIDE_AD_SLOTS + SAFE_FAMILY, setOf("https://m.youtube.com", "https://www.youtube.com", "https://music.youtube.com"))
        }
        w.webChromeClient = object : WebChromeClient() {
            // no grey "play" picture flashing between songs (WebView's own placeholder)
            override fun getDefaultVideoPoster(): android.graphics.Bitmap = BLANK
        }
        w.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                val u = req.url.toString()
                val listed = !hostOk(req.url.host ?: "") && try { (app as RoninApp).blocker.blocked(u) } catch (e: Exception) { false }
                if (TvActivity.AD_URL.containsMatchIn(u) || listed)
                    return WebResourceResponse("text/plain", "utf-8", 204, "No Content", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                return null
            }
            // YouTube / Google sign-in stay inside; any other link (an advert page) is not opened
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest) = !hostOk(req.url.host ?: "")
            override fun onPageFinished(view: WebView, url: String?) {
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) view.evaluateJavascript(PageScripts.BACKGROUND_PLAY + TvActivity.NO_ADS_JS + HIDE_AD_SLOTS + SAFE_FAMILY, null)
            }
            // no internet yet (phone just woke up / Wi-Fi switching): try again by itself, no error page left on screen
            override fun onReceivedError(view: WebView, req: WebResourceRequest, err: android.webkit.WebResourceError) {
                if (!req.isForMainFrame) return
                retries++
                if (retries <= 20) ui.postDelayed({ if (web === view) view.loadUrl(req.url.toString()) }, (2000L * retries).coerceAtMost(15000L))
            }
            override fun onPageCommitVisible(view: WebView, url: String?) { if (url?.startsWith("https://") == true) retries = 0 }
            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                (view.parent as? android.view.ViewGroup)?.removeView(view)
                view.destroy(); web = null; MediaPlaybackService.stop(app)
                return true
            }
        }
        w.loadUrl(start(app))
        web = w
        PlayerBridge.control = { a -> ui.post { command(if (a == "stop") "pause" else a) } }
        PlayerBridge.playId = { id -> ui.post { play(app, CarMusicService.ITEMS.firstOrNull { it.first == id }?.third ?: id) } }
        ui.removeCallbacks(poll); ui.postDelayed(poll, 1000)
        return w
    }

    /** Car / list item: open that list and let it play. */
    fun play(ctx: Context, url: String) {
        val w = page(ctx)
        if (url == "resume" || w.url == url) command("play") else w.loadUrl(url)
    }

    /**
     * Play / pause / next / previous from the car, the notification or the lock screen.
     * The page's own buttons first (YouTube Music knows its queue); the car shows the new state at once
     * instead of waiting for the next check; "previous" always goes to the previous song, never restarts it.
     */
    fun command(a: String) {
        val w = web ?: return
        if (a == "play" || a == "pause") {
            val p = a == "play"
            if (p != playing) { playing = p; MediaPlaybackService.update(w.context, title.ifBlank { w.context.getString(R.string.ronin_site_name) }, artist, p); onState?.invoke(title, artist, p) }
        }
        w.evaluateJavascript(siteCommand(a), null)
        ui.removeCallbacks(poll); ui.postDelayed(poll, 400)
    }

    fun siteCommand(a: String): String = """
(function(a){
  var v = document.querySelector('video,audio');
  var h = window.__roninMS && window.__roninMS[a];
  if (a === 'previoustrack' && v) { try { v.currentTime = 0; } catch (e) {} }
  if (h) { try { h({action:a}); return 'page'; } catch (e) {} }
  var sel = {play:'#play-pause-button[aria-label*="Play"]', pause:'#play-pause-button[aria-label*="Pause"]', nexttrack:'.next-button,button[aria-label^="Next"]', previoustrack:'.previous-button,button[aria-label^="Previous"]'}[a];
  var b = sel && document.querySelector(sel);
  if (b) { b.click(); return 'button'; }
  return 'x';
})(${org.json.JSONObject.quote(a)}) === 'x' && """ + PageScripts.mediaCommand(a)

    private val poll = object : Runnable {
        override fun run() {
            val w = web ?: return
            w.evaluateJavascript(PageScripts.MEDIA_STATE) { raw -> onMedia(w.context, raw) }
            ui.postDelayed(this, if (playing) 1000 else 2000)
        }
    }

    private fun onMedia(ctx: Context, raw: String?) {
        val o = try { JSONObject(JSONArray("[$raw]").getString(0)) } catch (e: Exception) { return }
        val p = o.optBoolean("v") && o.optBoolean("p")
        val t = if (o.optBoolean("v")) o.optString("t", "") else title
        val a = o.optString("a", artist)
        if (!o.optBoolean("v") && !playing) return
        if (p != playing || t != title || a != artist) {
            playing = p; title = t; artist = a
            MediaPlaybackService.update(ctx, t.ifBlank { ctx.getString(R.string.ronin_site_name) }, a, p)
            onState?.invoke(title, artist, playing)
        }
    }

    val hasPage get() = web != null
    /** Tests: run a script on the page. */
    fun eval(js: String, done: (String?) -> Unit) { web?.evaluateJavascript(js, done) ?: done(null) }
    private val BLANK: android.graphics.Bitmap by lazy { android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888) }
    val isPlaying get() = playing
    val nowTitle get() = title
    val nowArtist get() = artist

    /**
     * Family-safe (owner 25-Nov): YouTube Restricted Mode always on (kept on even if the page changes it).
     * Shorts stay (owner 16:39: Shorts back, but only filtered ones) - Restricted Mode filters them too.
     */
    const val SAFE_FAMILY = """
(function(){ if (window.__roninSafe) return; window.__roninSafe = 1;
  var RM = 0x8000000;
  var keepRestricted = function(){
    try {
      var m = document.cookie.match(/(?:^|; )PREF=([^;]*)/), pref = m ? m[1] : '', parts = pref ? pref.split('&') : [], found = false, changed = false;
      for (var i = 0; i < parts.length; i++) if (parts[i].indexOf('f2=') === 0) {
        found = true; var f = parseInt(parts[i].slice(3), 16) || 0;
        if (!(f & RM)) { parts[i] = 'f2=' + (f | RM).toString(16); changed = true; }
      }
      if (!found) { parts.push('f2=8000000'); changed = true; }
      if (changed) {
        document.cookie = 'PREF=' + parts.join('&') + '; domain=.youtube.com; path=/; max-age=63072000; secure';
        return true;
      }
    } catch (e) {}
    return false;
  };
  if (keepRestricted() && !sessionStorage.__roninRM) { sessionStorage.__roninRM = 1; location.reload(); return; }
  setInterval(keepRestricted, 700);
})();
"""

    /** Advert boxes on the phone pages are hidden (the player itself is never touched). */
    private const val HIDE_AD_SLOTS = """
(function(){ if (window.__roninSite) return; window.__roninSite = 1;
  var css = 'ytm-promoted-sparkles-web-renderer,ytm-companion-ad-renderer,ad-slot-renderer,ytm-ad-slot-renderer,ytd-ad-slot-renderer,ytm-promoted-video-renderer,.ytp-ad-overlay-container,.ytp-ad-module,ytmusic-mealbar-promo-renderer,ytm-statement-banner-renderer{display:none!important}';
  var add = function(){ try { var s = document.createElement('style'); s.textContent = css; (document.head||document.documentElement).appendChild(s); } catch(e){} };
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', add); else add();
  setInterval(function(){ var v = document.querySelector('.ad-showing video'); if (v && v.duration) { v.currentTime = v.duration; } var b = document.querySelector('.ytp-ad-skip-button,.ytp-skip-ad-button,.ytp-ad-skip-button-modern'); if (b) b.click(); }, 600);
})();
"""
}
