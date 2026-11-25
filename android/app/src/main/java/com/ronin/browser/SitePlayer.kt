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
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(w, TvActivity.NO_ADS_JS + HIDE_AD_SLOTS, setOf("https://m.youtube.com", "https://www.youtube.com", "https://music.youtube.com"))
        }
        w.webChromeClient = WebChromeClient()
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
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) view.evaluateJavascript(TvActivity.NO_ADS_JS + HIDE_AD_SLOTS, null)
            }
            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                (view.parent as? android.view.ViewGroup)?.removeView(view)
                view.destroy(); web = null; MediaPlaybackService.stop(app)
                return true
            }
        }
        w.loadUrl(start(app))
        web = w
        PlayerBridge.control = { a -> ui.post { web?.evaluateJavascript(PageScripts.mediaCommand(if (a == "stop") "pause" else a), null) } }
        PlayerBridge.playId = { id -> ui.post { play(app, CarMusicService.ITEMS.firstOrNull { it.first == id }?.third ?: id) } }
        ui.removeCallbacks(poll); ui.postDelayed(poll, 1000)
        return w
    }

    /** Car / list item: open that list and let it play. */
    fun play(ctx: Context, url: String) {
        val w = page(ctx)
        if (url == "resume") w.evaluateJavascript(PageScripts.mediaCommand("play"), null)
        else if (w.url != url) w.loadUrl(url) else w.evaluateJavascript(PageScripts.mediaCommand("play"), null)
    }

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
    val isPlaying get() = playing
    val nowTitle get() = title
    val nowArtist get() = artist

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
