package com.ronin.browser.agent

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Ronin Agent on Android: carries out Claude's tools on the Claude tab's page with real input, and enforces
 * every rule ON THE DEVICE (AgentRules). Call [run] from a background thread; page work hops to the main thread.
 * Logs only metadata (tool, site, ok) under the tag "RoninAgent" — never page text or typed text.
 */
class AgentDriver(
    val web: AgentWebView,
    private val journal: SharedPreferences,
    private val agentJs: String,
    private val isMoney: (String) -> Boolean = { false },
) : AgentRunner {
    private val main = Handler(Looper.getMainLooper())

    // ---- state the owner controls (banner / notification buttons) ----
    @Volatile override var stopped = false
    @Volatile var paused = false          // the owner pressed PAUSE: stays paused until he taps RESUME
    @Volatile var ownerHolding = false    // ask_owner: the owner has the screen
    @Volatile private var ownerResult: String? = null
    @Volatile var pace = "normal"         // "slow" or "normal", as the owner says in chat

    // ---- what the rules need ----
    @Volatile private var claudeUntil = 0L
    val blocked = CopyOnWriteArrayList<String>()
    @Volatile var taint: String? = null
    val idHosts: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val readPages = ConcurrentHashMap<String, MutableSet<String>>()
    val allowIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val blockedActs: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val privateCache = ConcurrentHashMap<String, Boolean>()
    private val done = object : LinkedHashMap<String, JSONObject>(16, 0.75f, true) { override fun removeEldestEntry(e: MutableMap.MutableEntry<String, JSONObject>?) = size > 100 }
    @Volatile private var pageLatch = CountDownLatch(1)
    @Volatile var currentUrl = ""
    @Volatile private var lastTarget = ""
    private val checks = ConcurrentHashMap<String, MutableList<Long>>()

    /** Tests only: pages served for made-up hosts ("host/path" or "host"), so hostile pages run without a server. */
    val testPages = ConcurrentHashMap<String, String>()

    init { journal.getString("lastTarget", "")?.let { lastTarget = it } }

    fun log(msg: String) = Log.i("RoninAgent", msg)

    // ================= page hooks =================
    @SuppressLint("SetJavaScriptEnabled")
    fun attach() {
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        // owner, 28 Nov: "a tap by mistake must not pause the work; only my PAUSE / STOP buttons". A touch or scroll
        // only makes Claude's next tap / typing wait until his finger is off the page (no fight over the screen).
        web.onOwnerTouch = { log("owner touched the page (work goes on)") }
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? = intercept(req)
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val url = req.url.toString()
                val scheme = req.url.scheme?.lowercase() ?: ""
                if (scheme != "http" && scheme != "https") return true // intent:, tel:, market: ... never from the Claude tab
                if (ownerHolding || paused) return false
                val why = AgentRules.guard("GET", url, req.isForMainFrame, claudeActive(), isMoney, taint, AgentRules.host(currentUrl))
                    ?: if (claudeActive() && isPrivate(AgentRules.host(url))) "private address" else null
                if (why != null) { block(why); return true }
                return false
            }
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) { currentUrl = url }
            override fun onPageFinished(view: WebView, url: String) { currentUrl = url; pageLatch.countDown() }
        }
        // service workers have their own requests: the same guard (Android needs this separate hook)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST)) {
            ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(object : ServiceWorkerClientCompat() {
                override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? = intercept(request)
            })
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try { androidx.webkit.WebViewCompat.addDocumentStartJavaScript(web, agentJs, setOf("*")) } catch (e: Exception) { log("doc-start script: ${e.javaClass.simpleName}") }
        }
    }

    private fun claudeActive() = SystemClock.uptimeMillis() <= claudeUntil
    private fun block(why: String) { blocked.add(why); log("blocked: ${why.take(80)}") }

    private fun intercept(req: WebResourceRequest): WebResourceResponse? {
        val url = req.url.toString()
        val h = AgentRules.host(url)
        if (!ownerHolding) {
            val why = AgentRules.guard(req.method ?: "GET", url, req.isForMainFrame, claudeActive(), isMoney, taint, AgentRules.host(currentUrl))
                ?: if (testPages.isEmpty() && isPrivate(h)) "private address $h" else null
            if (why != null) {
                block(why)
                return WebResourceResponse("text/plain", "utf-8", 403, "Blocked by Ronin", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream("Blocked by Ronin".toByteArray()))
            }
        }
        // tests: made-up hosts answered from memory (like a real site, same rules above)
        if (testPages.isNotEmpty() && (req.url.scheme == "https" || req.url.scheme == "http")) {
            val page = testPages[h + (req.url.path ?: "/")] ?: testPages[h] ?: if (testPages.containsKey("*")) "<!doctype html><title>page</title><h1>${req.url.path}</h1>" else null
            if (page != null) {
                val type = if ((req.url.path ?: "").endsWith(".js")) "application/javascript" else "text/html"
                return WebResourceResponse(type, "utf-8", 200, "OK", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(page.toByteArray()))
            }
        }
        return null
    }

    /** A host is private if its NAME or any address it resolves to is on the home network / loopback / CGNAT. */
    fun isPrivate(h: String): Boolean {
        if (h.isEmpty()) return false
        if (AgentRules.isPrivateHost(h)) return true
        return privateCache.getOrPut(h) {
            try {
                InetAddress.getAllByName(h).any { a ->
                    val b = a.address
                    a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress || a.isAnyLocalAddress ||
                        (b.size == 4 && (b[0].toInt() and 0xff) == 100 && (b[1].toInt() and 0xc0) == 64)
                }
            } catch (e: Exception) { false }
        }
    }

    // ================= small helpers =================
    @Suppress("UNCHECKED_CAST")
    private fun <T> onMain(timeoutMs: Long = 15_000, f: () -> T): T = onThread(main, timeoutMs, f)

    /** Runs f on the given thread (the page's input connection lives on the engine's own IME thread, not the UI thread). */
    @Suppress("UNCHECKED_CAST")
    private fun <T> onThread(handler: Handler, timeoutMs: Long = 15_000, f: () -> T): T {
        val main = handler
        if (Looper.myLooper() == handler.looper) return f()
        val latch = CountDownLatch(1)
        var r: Any? = null; var err: Throwable? = null
        main.post { try { r = f() } catch (t: Throwable) { err = t } finally { latch.countDown() } }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) throw RuntimeException("the page did not answer in time")
        err?.let { throw it }
        return r as T
    }

    private fun js(expr: String, timeoutMs: Long = 15_000): String? {
        val latch = CountDownLatch(1)
        var out: String? = null
        main.post { web.evaluateJavascript(expr) { v -> out = v; latch.countDown() } }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return out
    }

    /** Tests only: read something from the page (e.g. "window.saves"). */
    fun pageValue(expr: String): String? = js(expr)

    private fun ensureHelper() { if (js("!!window.__roninAgent") != "true") js(agentJs) }

    /** Runs helper code that returns an object; it comes back as JSON. */
    private fun call(expr: String): JSONObject {
        ensureHelper()
        val raw = js("JSON.stringify($expr)") ?: return JSONObject().put("error", "the page did not answer")
        val text = try { JSONTokener(raw).nextValue() as? String } catch (e: Exception) { null } ?: return JSONObject().put("error", "no answer from the page")
        return try { JSONObject(text) } catch (e: Exception) { JSONObject().put("error", "bad answer from the page") }
    }

    private fun q(s: String) = JSONObject.quote(s)
    // the page's address as the engine has committed it (the page-start / page-finish events can arrive late or out of
    // order: a late about:blank once hid a broker page from the read-only check, CI 28 Nov)
    private fun pageUrl(): String = try { onMain(3000) { web.url } } catch (e: Exception) { null } ?: currentUrl
    private fun host() = AgentRules.host(pageUrl())
    private fun money(h: String = host()) = AgentRules.mode(h, isMoney) == AgentRules.Mode.MONEY
    /** read-only if ANY view of where the page is says money: the committed address, the last event, the page itself */
    private fun moneyAnywhere(): Boolean {
        if (money() || money(AgentRules.host(currentUrl))) return true
        val loc = js("location.href")?.trim('"') ?: return false
        return money(AgentRules.host(loc))
    }
    private fun err(m: String) = JSONObject().put("error", m)
    private fun slow() = pace == "slow"

    // ================= input =================
    private fun ratio(): Float {
        val v = call("window.__roninAgent.view()")
        val w = v.optDouble("w", 0.0)
        val vw = onMain { web.width }
        return if (w > 0) (vw / w).toFloat() else web.resources.displayMetrics.density
    }

    /** One real tap: a matched DOWN / UP pair on the UI thread, with a natural press time. */
    private fun tap(cssX: Double, cssY: Double) {
        val r = ratio()
        val x = (cssX * r).toFloat() + (Math.random().toFloat() - 0.5f)
        val y = (cssY * r).toFloat() + (Math.random().toFloat() - 0.5f)
        claudeUntil = Long.MAX_VALUE
        try {
            val down = SystemClock.uptimeMillis()
            onMain {
                web.claudeTouching = true
                val e = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0); e.source = InputDevice.SOURCE_TOUCHSCREEN
                web.dispatchTouchEvent(e); e.recycle()
                web.claudeTouching = false
            }
            Thread.sleep(if (slow()) 140L + (Math.random() * 120).toLong() else 50L + (Math.random() * 60).toLong())
            onMain {
                web.claudeTouching = true
                val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0); e.source = InputDevice.SOURCE_TOUCHSCREEN
                web.dispatchTouchEvent(e); e.recycle()
                web.claudeTouching = false
            }
            Thread.sleep(if (slow()) 700 else 300)
        } finally { claudeUntil = SystemClock.uptimeMillis() + 2000 }
    }

    private fun key(code: Int) {
        claudeUntil = Long.MAX_VALUE
        try {
            onMain {
                val t = SystemClock.uptimeMillis()
                web.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
                web.dispatchKeyEvent(KeyEvent(t, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0))
            }
            Thread.sleep(if (slow()) 400 else 150)
        } finally { claudeUntil = SystemClock.uptimeMillis() + 2000 }
    }

    /** Text into the focused box: input connection first, key events next, then (last) the page's own setter. */
    private fun commit(ref: String, text: String): String {
        claudeUntil = Long.MAX_VALUE
        try {
            val ic = web.connection
            if (ic != null) {
                // Chromium's input connection must be called on ITS thread (ic.handler), never the UI thread
                val h = (if (android.os.Build.VERSION.SDK_INT >= 24) ic.handler else null) ?: main
                val ok = try {
                    onThread(h) {
                        ic.beginBatchEdit()
                        ic.performContextMenuAction(android.R.id.selectAll)
                        val c = if (!slow()) ic.commitText(text, 1) else ic.commitText("", 1)
                        ic.endBatchEdit() // returns "still in a batch?", not success
                        c
                    }
                } catch (t: Throwable) { log("input connection: ${t.javaClass.simpleName}"); false }
                if (ok && slow()) for (ch in text) { onThread(h) { ic.commitText(ch.toString(), 1) }; Thread.sleep(60L + (Math.random() * 90).toLong()) }
                if (ok) return "input-connection"
            }
            val events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray())
            if (events != null) {
                onMain { web.dispatchKeyEvent(KeyEvent(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0, KeyEvent.META_CTRL_ON)) }
                for (e in events) { onMain { web.dispatchKeyEvent(e) }; if (slow()) Thread.sleep(40) }
                return "key-events"
            }
            js("(function(){var r=window.__roninAgent;return 0})()")
            return "none"
        } finally { claudeUntil = SystemClock.uptimeMillis() + 2000 }
    }

    // ================= tools =================
    private val INPUT = setOf("click", "type", "select", "upload", "press")
    private val READ = setOf("snapshot", "screenshot", "get_text", "scroll", "back", "tabs", "wait_for", "ask_owner", "open", "downloads", "set_pace")

    /** One command from Claude. [id] makes it idempotent: the same id never runs twice. */
    /** Called when a command from Claude arrives (the screen wakes up for the work). */
    var onCommand: (() -> Unit)? = null

    /** The last thing Claude did, in one line (shown in the notification so the owner sees it from any app). */
    var onDone: ((String) -> Unit)? = null

    override fun run(id: String, tool: String, args: JSONObject): JSONObject {
        val r = runInner(id, tool, args)
        try { r?.optString("done")?.takeIf { it.isNotEmpty() && tool != "snapshot" }?.let { onDone?.invoke(it.take(80)) } } catch (_: Exception) {}
        return r
    }

    private fun runInner(id: String, tool: String, args: JSONObject): JSONObject {
        onCommand?.invoke()
        try { onMain(3000) { web.keepAwake() } } catch (_: Exception) {}
        synchronized(done) { done[id] }?.let { return it }
        if (stopped) return err("the owner pressed STOP on this device. Claude must wait until he turns Claude on again.")
        if ((paused || ownerHolding) && tool != "ask_owner") return err(if (paused) "the owner pressed PAUSE. Claude waits until he taps RESUME." else "the owner is using the screen right now: wait and try again")
        // his finger is on the page (scrolling to watch): wait for it to lift, at most 8 s, then go on
        val until = SystemClock.uptimeMillis() + 8000
        while ((web.ownerFingerDown || SystemClock.uptimeMillis() - web.ownerFingerAt < 400) && SystemClock.uptimeMillis() < until) Thread.sleep(100)
        if (web.ownerFingerDown && tool !in setOf("snapshot", "get_text", "screenshot", "tabs", "downloads", "wait_for", "devices", "ask_owner")) return err("the owner is touching the screen right now: wait a moment and try again")
        // write-ahead journal: if Ronin died during this command, it is NEVER pressed again blindly
        // done before (the answer was lost on the way, then the app restarted): the same answer again, never a second tap
        if (journal.getString("j_$id", null) == "done") return journal.getString("r_$id", null)?.let { try { JSONObject(it) } catch (e: Exception) { null } }
            ?: JSONObject().put("done", "this command already ran (answer kept on the device)")
        if (journal.getString("j_$id", null) == "started") {
            val t = journal.getString("lastTarget", "") ?: ""
            if (t.isNotEmpty()) blockedActs.add(t)
            return JSONObject().put("uncertain", "the last $tool was interrupted (app restarted). It may or may not have happened. Take a snapshot and check the page; the same action stays blocked until the owner taps CHECKED.")
        }
        if (tool in INPUT && moneyAnywhere()) return err("${host()} is READ-ONLY for Claude (money / trading / bank site): no taps or typing at all. Tell the owner what to press.")
        if (tool !in READ) {
            val c = try { call("{c: window.__roninAgent.challenge()}").optString("c") } catch (e: Exception) { "" }
            if (c.isNotEmpty()) return err("$c: Claude does not solve human checks. Call ask_owner.")
        }
        if (tool in INPUT) journal.edit().putString("j_$id", "started").putString("lastTarget", "").commit() // on disk BEFORE the tap
        val r = try { doTool(tool, args) } catch (e: Throwable) { err((e.message ?: e.javaClass.simpleName).take(200)) }
        if (tool in INPUT) {
            val ids = (journal.getString("ids", "") ?: "").split(',').filter { it.isNotEmpty() }.toMutableList()
            ids.add(id)
            val e = journal.edit().putString("j_$id", "done").putString("r_$id", r.toString().take(4000))
            while (ids.size > 300) { val old = ids.removeAt(0); e.remove("j_$old").remove("r_$old") } // the last 300 inputs are kept
            e.putString("ids", ids.joinToString(",")).commit()
        }
        if (tool != "ask_owner") synchronized(done) { done[id] = r }
        log("$tool on ${host()}: ${if (r.has("error")) "refused" else "ok"}")
        if (slow() && tool in INPUT) Thread.sleep(600)
        return r
    }

    private fun doTool(tool: String, a: JSONObject): JSONObject = when (tool) {
        "open" -> open(a.optString("url"))
        "snapshot" -> snapshot()
        "get_text" -> call("window.__roninAgent.getText(${q(a.optString("ref"))}, ${host() in allowIds})")
        "click" -> click(a.optString("ref"))
        "type" -> type(a.optString("ref"), a.optString("text"), a.optBoolean("submit"))
        "select" -> call("window.__roninAgent.selectOption(${q(a.optString("ref"))}, ${q(a.optString("option"))})")
        "press" -> press(a.optString("key"))
        "scroll" -> { js("window.scrollBy(0, ${if (a.optString("direction") == "up") "-" else ""}Math.round(innerHeight*0.8))"); JSONObject().put("done", "scrolled") }
        "back" -> { onMain { if (web.canGoBack()) web.goBack() }; Thread.sleep(800); JSONObject().put("url", pageUrl()) }
        "tabs" -> JSONObject().put("tabs", org.json.JSONArray().put(JSONObject().put("index", 0).put("url", pageUrl()).put("claude", true)))
        "screenshot" -> screenshot()
        "wait_for" -> waitFor(a.optString("text"), a.optInt("seconds", 10))
        "ask_owner" -> askOwner(a.optString("reason"))
        "upload" -> err("upload comes in the next pilot step: ask the owner to attach the file")
        "downloads" -> JSONObject().put("downloads", org.json.JSONArray()).put("note", "downloads are not used in the pilot")
        "set_pace" -> { pace = if (a.optString("pace") == "slow") "slow" else "normal"; JSONObject().put("done", "pace $pace") }
        else -> err("unknown tool $tool")
    }

    private fun open(url: String): JSONObject {
        AgentRules.openRefusal(url, isMoney, readPages, taint)?.let { return err("$it: refused") }
        val h = AgentRules.host(url)
        if (testPages.isEmpty() && isPrivate(h)) return err("home-network / server addresses are never opened by Claude")
        val before = pageUrl()
        pageLatch = CountDownLatch(1)
        onMain { web.loadUrl(url) }
        pageLatch.await(30, TimeUnit.SECONDS)
        // a late "finished" of the PREVIOUS page can open the latch early: wait until the engine is really on the new site
        val until = SystemClock.uptimeMillis() + 5000
        while (pageUrl().let { it == before || it == "about:blank" } && before != url && SystemClock.uptimeMillis() < until) Thread.sleep(100)
        Thread.sleep(if (slow()) 1200 else 300)
        val s = snapshot()
        val ch = s.optString("challenge")
        if (ch.isNotEmpty()) {
            val list = checks.getOrPut(h) { CopyOnWriteArrayList() }
            val now = System.currentTimeMillis(); list.removeAll { now - it > 600_000 }; list.add(now)
            if (list.size >= 3) return err("$h asked for a human check ${list.size} times in 10 minutes: stopping here. Tell the owner.")
        }
        return JSONObject().put("url", pageUrl()).put("title", s.optString("title")).put("challenge", ch.ifEmpty { null })
            .put("hint", if (ch.isNotEmpty()) "human check: call ask_owner" else "call snapshot to read it")
    }

    private fun snapshot(): JSONObject {
        val h = host()
        val s = call("window.__roninAgent.snapshot({allowIds: ${h in allowIds}})")
        if (s.optString("page_text_untrusted").contains(" hidden]")) idHosts.add(h)
        if (AgentRules.sensitive(h, isMoney, idHosts)) taint = h
        return s
    }

    /** The owner is using another app (Claude app, WhatsApp...): Ronin is not on the screen. */
    fun background(): Boolean = forceBackground || !onMain { web.hasWindowFocus() && web.isShown }
    @Volatile var forceBackground = false // device tests only: act as if the owner were in another app

    /** Typing while Ronin is in the background: the page's own setter + input / change events, then read back. */
    private fun typeInBackground(ref: String, name: String, text: String, submit: Boolean): JSONObject {
        claudeUntil = Long.MAX_VALUE
        try {
            val r = call("window.__roninAgent.fill(${q(ref)}, ${q(text)})")
            if (r.has("error")) return err(if (r.optString("error") == "secret box") "password / OTP box: only the owner types it." else r.optString("error"))
            Thread.sleep(if (slow()) 600 else 150)
            val v = call("window.__roninAgent.value(${q(ref)})").optString("value")
            val result = when { v == text -> "verified"; v.contains(text) -> "verified (box had more text)"; else -> "unverified" }
            if (submit && result.startsWith("verified")) { call("window.__roninAgent.submitBox(${q(ref)})"); Thread.sleep(400) }
            return JSONObject().put("done", "typed into \"$name\"").put("check", result).put("method", "background-fill").put("editorinfo", "background")
                .also { if (submit && !result.startsWith("verified")) it.put("note", "not submitted: the box does not show exactly the text") }
        } finally { claudeUntil = SystemClock.uptimeMillis() + 2000 }
    }

    private fun click(ref: String): JSONObject {
        val at = call("window.__roninAgent.locate(${q(ref)})")
        if (at.has("error")) return at
        val name = at.optString("name")
        if (at.optBoolean("captcha")) return err("this is a human check: call ask_owner")
        if (at.optString("kind") == "a" && AgentRules.scriptLink(at.optString("href"))) return err("script link: refused")
        if (at.optBoolean("money")) return err("\"$name\" is a money / order action: Claude may not press it. Tell the owner.")
        if (at.optBoolean("covered")) return err("something else covers \"$name\" (${at.optString("coveredBy")}): refused (possible trick). Take a new snapshot.")
        val act = "click|${host()}|${at.optString("fp")}"
        if (act in blockedActs) return err("\"$name\" was interrupted earlier and its result is unknown. The owner must check the page and tap CHECKED first.")
        lastTarget = act; journal.edit().putString("lastTarget", act).commit()
        val b0 = blocked.size
        val bg = background()
        if (bg) { // the owner is in another app: the same checks were made above; the element is pressed directly
            claudeUntil = Long.MAX_VALUE
            try { call("window.__roninAgent.press(${q(ref)})"); Thread.sleep(if (slow()) 700 else 400) } finally { claudeUntil = SystemClock.uptimeMillis() + 2000 }
        } else tap(at.optDouble("x"), at.optDouble("y"))
        if (blocked.size > b0) return err("tapped \"$name\", but the page then tried ${blocked.subList(b0, blocked.size).joinToString("; ")}. BLOCKED on the device. Tell the owner.")
        return JSONObject().put("done", "tapped ${at.optString("kind")} \"$name\"").put("url", pageUrl()).put("method", if (bg) "background-press" else "tap")
    }

    private fun type(ref: String, text: String, submit: Boolean): JSONObject {
        if (text.length > 20_000) return err("too much text in one go")
        val at = call("window.__roninAgent.locate(${q(ref)})")
        if (at.has("error")) return at
        val name = at.optString("name")
        if (at.optBoolean("password")) return err("password box: only the owner types passwords. Call ask_owner.")
        if (at.optBoolean("otp") || AgentRules.secretLabel(name) && AgentRules.looksLikeOtp(text)) return err("OTP box: the owner types the OTP. Call ask_owner.")
        if (at.optBoolean("captcha")) return err("CAPTCHA box: call ask_owner")
        if (at.optBoolean("covered")) return err("\"$name\" is covered by something else: refused")
        if (AgentRules.numberNeedsOwner(name, text, at.optBoolean("otpPage"))) return err("\"${text.replace(Regex("\\D"), "").length}-digit number\" looks like an OTP / PIN (this page asks for one): the owner types it. Call ask_owner.")
        val before = call("window.__roninAgent.value(${q(ref)})")
        if (before.optString("value") == text) return JSONObject().put("done", "already matched \"$name\"")
        if (background()) return typeInBackground(ref, name, text, submit)
        val t0 = SystemClock.uptimeMillis()
        tap(at.optDouble("x"), at.optDouble("y"))
        // native check (outside the page's JavaScript): what the engine told the keyboard about this box
        var waited = 0
        while (web.lastInputAt < t0 && waited < 1500) { Thread.sleep(50); waited += 50 }
        val native = if (web.lastInputAt >= t0) (if (AgentRules.secretInputType(web.lastInputType)) "secret" else "normal") else "unknown"
        if (native == "secret") return err("the engine says \"$name\" is a password box: only the owner types it.")
        val how = commit(ref, text)
        Thread.sleep(150)
        val after = call("window.__roninAgent.value(${q(ref)})")
        val v = after.optString("value")
        val result = when { after.optBoolean("secret") -> "typed (secret box: not read back)"; v == text -> "verified"; v.contains(text) -> "verified (box had more text)"; else -> "unverified" }
        if (submit) key(KeyEvent.KEYCODE_ENTER)
        return JSONObject().put("done", "typed into \"$name\"").put("check", result).put("method", how).put("editorinfo", native)
    }

    private fun press(k: String): JSONObject {
        val code = when (k.lowercase()) {
            "enter" -> KeyEvent.KEYCODE_ENTER; "tab" -> KeyEvent.KEYCODE_TAB; "escape" -> KeyEvent.KEYCODE_ESCAPE
            "pagedown" -> KeyEvent.KEYCODE_PAGE_DOWN; "pageup" -> KeyEvent.KEYCODE_PAGE_UP
            "arrowdown" -> KeyEvent.KEYCODE_DPAD_DOWN; "arrowup" -> KeyEvent.KEYCODE_DPAD_UP
            "arrowleft" -> KeyEvent.KEYCODE_DPAD_LEFT; "arrowright" -> KeyEvent.KEYCODE_DPAD_RIGHT
            "backspace" -> KeyEvent.KEYCODE_DEL; "space" -> KeyEvent.KEYCODE_SPACE
            else -> return err("unknown key $k")
        }
        val b0 = blocked.size
        key(code)
        if (blocked.size > b0) return err("pressed $k, but the page then tried ${blocked.subList(b0, blocked.size).joinToString("; ")}. BLOCKED.")
        return JSONObject().put("done", "pressed $k")
    }

    private fun screenshot(): JSONObject {
        if (AgentRules.sensitive(host(), isMoney, idHosts)) return err("screenshot of a money / government / ID page is refused (pixels cannot be hidden). Use snapshot: ID numbers are hidden there.")
        val bytes = onMain {
            val bmp = Bitmap.createBitmap(maxOf(1, web.width), maxOf(1, web.height), Bitmap.Config.ARGB_8888)
            web.draw(Canvas(bmp))
            val out = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.JPEG, 55, out); bmp.recycle(); out.toByteArray()
        }
        return JSONObject().put("image", Base64.encodeToString(bytes, Base64.NO_WRAP)).put("note", currentUrl)
    }

    private fun waitFor(text: String, seconds: Int): JSONObject {
        val end = SystemClock.uptimeMillis() + minOf(20, maxOf(1, seconds)) * 1000L
        while (SystemClock.uptimeMillis() < end) {
            if (text.isEmpty() || call("{t: window.__roninAgent.hasText(${q(text)})}").optBoolean("t")) return JSONObject().put("done", "seen")
            Thread.sleep(250)
        }
        return err("not seen in time")
    }

    private fun askOwner(reason: String): JSONObject {
        ownerResult?.let { ownerResult = null; return if (it == "stop") err("the owner stopped Claude here") else JSONObject().put("done", "the owner finished. Take a new snapshot.") }
        if (!ownerHolding) {
            ownerHolding = true
            onOwnerAsked?.invoke(reason.take(120))
            return JSONObject().put("with_owner", "handed to the owner. Call ask_owner again to wait for his Done (each call waits up to 15 s).")
        }
        val end = SystemClock.uptimeMillis() + 15_000
        while (ownerHolding && SystemClock.uptimeMillis() < end) Thread.sleep(200)
        val r = ownerResult ?: return JSONObject().put("waiting", "still with the owner. Call ask_owner again (do not press anything).")
        ownerResult = null
        return if (r == "stop") err("the owner stopped Claude here") else JSONObject().put("done", "the owner finished. Take a new snapshot.")
    }

    // ================= the owner's buttons =================
    var onOwnerAsked: ((String) -> Unit)? = null
    fun ownerDone() { ownerResult = "done"; ownerHolding = false }
    fun ownerStop() { ownerResult = "stop"; ownerHolding = false; stopped = true }
    fun resume() { paused = false; stopped = false }
    fun ownerPause() { paused = true; log("owner pressed PAUSE") }
    fun checked() { blockedActs.clear() }
}
