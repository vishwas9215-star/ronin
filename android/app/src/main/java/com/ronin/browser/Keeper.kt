package com.ronin.browser

import android.content.Context
import android.content.MutableContextWrapper
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream

/**
 * Owner 21 Nov 06:46: a YouTube / YouTube Music song keeps playing even when Ronin is swiped away from Recents.
 * The browser screen is closed then, but its ONE playing page is kept here (the media foreground service keeps the
 * app alive); every other tab is freed. Opening Ronin again puts the same page back as a tab, still playing.
 * Stop (notification ✕) or a pause of more than 10 minutes lets it go: no page, no service, no wake lock.
 */
object KeptPlayer {
    class Kept(val web: RoninWebView, val url: String, val title: String, val playing: Boolean, val lastPlayingAt: Long)

    @Volatile private var kept: Kept? = null
    /** Set by the browser screen: a song plays (or played a moment ago, between songs) on a song site. */
    @Volatile var wantKeep = false
    private val ui = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var playing = false
    private var title = ""
    private var lastPlayingAt = 0L

    val holding get() = kept != null
    fun webForTest(): WebView? = kept?.web

    /** The browser screen is closing for good: keep its playing page (already removed from the screen's views). */
    fun hold(ctx: Context, w: RoninWebView, url: String, title0: String, playing0: Boolean, lastPlayed: Long) {
        val a = ctx.applicationContext
        app = a
        (w.context as? MutableContextWrapper)?.baseContext = a // the closed screen is not kept in memory
        (w.parent as? ViewGroup)?.removeView(w)
        // the closed screen's handlers are not used any more: only YouTube pages, ads still blocked
        w.webViewClient = KeepClient(url)
        w.webChromeClient = WebChromeClient()
        w.setDownloadListener(null); w.setOnLongClickListener(null); w.setFindListener(null); w.onPull = null
        w.keepPlaying = true
        w.onResume(); w.resumeTimers()
        w.evaluateJavascript(PageScripts.lowQuality(true), null) // nobody looks: the smallest picture (battery)
        playing = playing0; title = title0; lastPlayingAt = if (playing0) System.currentTimeMillis() else lastPlayed
        kept = Kept(w, url, title0, playing0, lastPlayingAt)
        PlayerBridge.control = { c -> ui.post { control(c) } }
        ui.removeCallbacks(poll); ui.postDelayed(poll, 2000)
    }

    /** Ronin opens again: the kept page goes back into a tab (null = nothing kept). */
    fun take(): Kept? {
        ui.removeCallbacks(poll)
        val k = kept ?: return null
        kept = null
        return Kept(k.web, k.url, title, playing, lastPlayingAt)
    }

    /** Stop, a long pause or a broken page: the page, the player card and the wake lock all go. */
    fun release() {
        ui.removeCallbacks(poll)
        val k = kept
        kept = null; wantKeep = false
        k?.web?.let { w -> w.keepPlaying = false; try { w.evaluateJavascript(PageScripts.mediaCommand("pause"), null) } catch (e: Exception) { }; (w.parent as? ViewGroup)?.removeView(w); w.destroy() }
        if (k != null) PlayerBridge.control = null
        app?.let { MediaPlaybackService.stop(it) }
    }

    private fun control(a: String) {
        val w = kept?.web ?: return
        if (a == "stop") { release(); return }
        w.evaluateJavascript(PageScripts.mediaCommand(a), null)
        if (a == "play") { playing = true; lastPlayingAt = System.currentTimeMillis() }
        if (a == "pause") playing = false
    }

    /** Song title / play state for the notification, like the browser screen did (2 s playing, 5 s paused). */
    private val poll = object : Runnable {
        override fun run() {
            val w = kept?.web ?: return
            val ctx = app ?: return
            w.evaluateJavascript(PageScripts.MEDIA_STATE) { raw ->
                if (kept?.web !== w) return@evaluateJavascript
                val o = try { JSONObject(JSONArray("[$raw]").getString(0)) } catch (e: Exception) { JSONObject() }
                val now = System.currentTimeMillis()
                val p = o.optBoolean("v") && o.optBoolean("p") && !o.optBoolean("m")
                val t = o.optString("t", title).ifEmpty { title }
                if (p) lastPlayingAt = now
                if (p != playing || t != title) { playing = p; title = t; MediaPlaybackService.update(ctx, t, o.optString("a"), p) }
                if (!p && now - lastPlayingAt > 10 * 60_000) { release(); return@evaluateJavascript }
                ui.postDelayed(this, if (p) 2000L else 5000L)
            }
        }
    }

    /** While kept: YouTube pages only, ads blocked by the lists, a broken page lets everything go. */
    private class KeepClient(val url: String) : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
            if (!req.isForMainFrame) return false
            val u = req.url.toString()
            return PageScripts.backgroundOrigins.none { u.startsWith("$it/") || u == it }
        }

        override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
            if (req.isForMainFrame) return null
            val blocked = try { RoninApp.instance.blocker.blockedFrom(req.url.toString(), Rules.hostOf(url)) } catch (e: Exception) { false }
            return if (blocked) WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0))) else null
        }

        override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
            release() // (on the main thread: the broken page is removed and destroyed at once)
            return true
        }
    }
}
