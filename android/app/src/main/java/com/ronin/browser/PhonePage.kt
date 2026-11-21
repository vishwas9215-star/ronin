package com.ronin.browser

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Ronin Messages and Ronin Calls are pages of Ronin's own (ui/tools/messages.html, calls.html)
 * in their own window with their own icon. The page talks to the phone through one bridge
 * ("roninPhone"), open only to Ronin's own pages (https://ronin.home); every request is one of a
 * fixed list, answered here. Nothing goes to the internet.
 */
abstract class PhonePage : AppCompatActivity() {
    abstract val page: String
    protected lateinit var web: WebView
    private var reply: JavaScriptReplyProxy? = null
    private var pendingRole: ((Boolean) -> Unit)? = null
    private val roleAsk = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        pendingRole?.invoke(r.resultCode == RESULT_OK); pendingRole = null
    }
    private var pendingSpeech: ((String?) -> Unit)? = null
    private val speech = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        pendingSpeech?.invoke(r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()?.ifEmpty { null }); pendingSpeech = null
    }
    /** The phone's own voice typing (Hindi first); null when it is not there or nothing was said. */
    companion object { const val NO_VOICE = "__no_voice__" } // this phone has no voice typing: the page asks by keyboard

    protected fun listen(prompt: String, then: (String?) -> Unit) {
        val i = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "hi-IN").putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, prompt)
        runOnUiThread {
            pendingSpeech?.invoke(null) // an earlier question still open: it ends empty, never hangs
            try { pendingSpeech = then; speech.launch(i) } catch (e: Exception) { pendingSpeech = null; then(NO_VOICE) }
        }
    }
    private var pendingPerm: (() -> Unit)? = null
    private val permAsk = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { pendingPerm?.invoke(); pendingPerm = null }

    /** One request from the page: answer with done(data) (any thread). */
    abstract fun handle(op: String, a: JSONObject, done: (Any?) -> Unit)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onConfigurationChanged(c: android.content.res.Configuration) {
        super.onConfigurationChanged(c)
        // the phone switched day / night while this screen is open: the page changes with it
        if (::web.isInitialized) {
            web.setBackgroundColor(Color.parseColor(if (systemDark()) "#121212" else "#F4F6F9"))
            web.evaluateJavascript("window.__roninDark=" + systemDark() + ";document.documentElement.classList.toggle('light'," + !systemDark() + ")", null)
        }
    }

    /** Messages / Calls follow the PHONE's own day / night (owner, 19 Nov 2026); the browser has its own switch. */
    fun systemDark(): Boolean = (android.content.res.Resources.getSystem().configuration.uiMode and
        android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES

    override fun onCreate(saved: Bundle?) {
        delegate.localNightMode = androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        super.onCreate(saved)
        web = WebView(this)
        web.setBackgroundColor(Color.parseColor(if (systemDark()) "#121212" else "#F4F6F9"))
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        val rules = RoninApp.instance.rules
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(web, Lang.pageScript(this) + "window.__roninDark=" + systemDark() + ";document.documentElement.classList.toggle('light'," + !systemDark() + ");window.__roninLinkLists=" + rules.safeLinks.json + ";window.__roninScamRules=" + rules.scamRulesJson + ";", setOf("https://ronin.home"))
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(web, "roninPhone", setOf("https://ronin.home")) { _, msg, origin, main, proxy ->
                if (!main || origin.toString().trimEnd('/') != "https://ronin.home") return@addWebMessageListener
                reply = proxy
                val m = try { JSONObject(msg.data ?: "{}") } catch (e: Exception) { return@addWebMessageListener }
                val id = m.optInt("id")
                        try {
                    handle(m.optString("op"), m) { data -> runOnUiThread { try { proxy.postMessage(JSONObject().put("id", id).put("data", data ?: JSONObject.NULL).toString()) } catch (e: Exception) { } } }
                } catch (t: Throwable) { proxy.postMessage(JSONObject().put("id", id).put("data", JSONObject.NULL).toString()) }
            }
        }
        // the page's own questions ("Delete this conversation?", "Block ...?"): without this Android answers
        // "no" silently and Block / Delete / Remove never happened on a real phone
        web.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onJsAlert(view: WebView, url: String, message: String, result: android.webkit.JsResult): Boolean {
                android.app.AlertDialog.Builder(this@PhonePage).setMessage(message).setCancelable(false)
                    .setPositiveButton("OK") { _, _ -> result.confirm() }.show()
                return true
            }
            override fun onJsConfirm(view: WebView, url: String, message: String, result: android.webkit.JsResult): Boolean {
                android.app.AlertDialog.Builder(this@PhonePage).setMessage(message)
                    .setPositiveButton(L("हाँ", "Yes")) { _, _ -> result.confirm() }
                    .setNegativeButton(L("नहीं", "No")) { _, _ -> result.cancel() }
                    .setOnCancelListener { result.cancel() }.show()
                return true
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                val u = req.url
                if (u.host != "ronin.home") return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), "".byteInputStream())
                val name = (u.path ?: "").removePrefix("/tools/")
                if (name !in setOf(page, page.replace(".html", ".js"), "scamcheck.js", "senders.js", "smsfacts.js")) return WebResourceResponse("text/plain", "utf-8", 404, "No", emptyMap(), "".byteInputStream())
                val mime = if (name.endsWith(".js")) "text/javascript" else "text/html"
                return try { WebResourceResponse(mime, "utf-8", assets.open("ronin/tools/$name")) } catch (e: Exception) { null }
            }
            /** Android ended the page engine (low memory): this screen opens again instead of Ronin crashing. */
            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                (view.parent as? android.view.ViewGroup)?.removeView(view)
                view.destroy(); webGone = true
                finish()
                // once: a second crash within 30 s only closes the screen (no endless restart)
                if (System.currentTimeMillis() - lastCrash > 30_000) { lastCrash = System.currentTimeMillis(); startActivity(intent) }
                return true
            }
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                // links in messages open in Ronin itself (the browser checks them), never in this window
                val u = req.url.toString()
                if (u.startsWith("https://ronin.home/")) return false
                openOutside(u)
                return true
            }
        }
        // Android 15+ draws under the bars. A WebView ignores its own padding (the page still drew under
        // the clock and the ⚙ was hidden), so the page sits in a frame that keeps it between the bars
        // and above the keyboard.
        val frame = android.widget.FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#0f1218")); addView(web) }
        setContentView(frame)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(frame) { v, ins ->
            val b = ins.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout() or androidx.core.view.WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom); androidx.core.view.WindowInsetsCompat.CONSUMED
        }
        web.loadUrl("https://ronin.home/tools/$page" + startQuery(intent))
        // the phone's Back works inside the page first (a conversation -> the list), then leaves the app
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("window.__roninBack?!!window.__roninBack():false") { r -> if (r != "true") finish() }
            }
        })
    }

    open fun startQuery(i: Intent?): String = ""
    override fun onDestroy() { if (!webGone) try { web.destroy() } catch (e: Exception) { }; super.onDestroy() }
    override fun onStart() { super.onStart(); if (::web.isInitialized && !webGone) web.resumeTimers() } // the browser may have rested all page timers
    private var webGone = false
    companion object { @Volatile private var lastCrash = 0L }
    fun webForTest(): WebView = web

    /** The page is told something happened (a new SMS, a call ended). */
    protected fun event(name: String) = runOnUiThread { reply?.postMessage(JSONObject().put("event", name).toString()) }

    protected fun openOutside(u: String) {
        try {
            when {
                u.startsWith("tel:") -> startActivity(Intent(Intent.ACTION_DIAL, Uri.parse(u)))
                // links open in Ronin Browser (its own app since 3.14.3); without it, in the phone's browser
                u.startsWith("http") -> try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).setPackage("com.ronin.browser")) }
                    catch (e: Exception) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) }
            }
        } catch (e: Exception) { }
    }

    protected fun requestRole(role: String, done: (Boolean) -> Unit) {
        if (android.os.Build.VERSION.SDK_INT < 29) { done(false); return }
        val rm = getSystemService(android.app.role.RoleManager::class.java)
        if (rm == null || !rm.isRoleAvailable(role)) { done(false); return }
        if (rm.isRoleHeld(role)) { done(true); return }
        pendingRole = done
        try { roleAsk.launch(rm.createRequestRoleIntent(role)) } catch (e: Exception) { pendingRole = null; done(false) }
    }
    protected fun roleHeld(role: String) = android.os.Build.VERSION.SDK_INT >= 29 && getSystemService(android.app.role.RoleManager::class.java)?.isRoleHeld(role) == true

    protected fun askPermissions(perms: Array<String>, done: () -> Unit) {
        val need = perms.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (need.isEmpty()) { done(); return }
        pendingPerm = done
        permAsk.launch(need.toTypedArray())
    }
    protected fun has(p: String) = checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Common requests of both pages. */
    protected fun common(op: String, a: JSONObject, done: (Any?) -> Unit): Boolean {
        when (op) {
            "open" -> { openOutside(a.optString("url")); done(true) }
            // 3.14.3: Ronin Browser opens the Chakshu form already filled with this scam SMS
            "chakshu" -> { Chakshu.open(this, Chakshu.data("sms", a.optString("address"), a.optString("body"), a.optLong("date"))); done(true) }
            "appInfo" -> { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))); done(true) }
            "defaultApps" -> { try { startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) } catch (e: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }; done(true) }
            "copy" -> { val c = a.optString("text").filter { it.isDigit() }.take(8); if (c.length >= 4) Otp.copy(this, c); done(c.length >= 4) }
            "close" -> { done(true); runOnUiThread { finish() } }
            "icon" -> bg { done(SenderIcons.get(this, a.optString("domain"))) }
            "lang" -> done(Lang.choice(this))
            "setLang" -> { Lang.set(this, a.optString("value")); done(true); runOnUiThread { recreate() } }
            "check" -> { val r = RoninApp.instance.rules.scam.text(a.optString("text")); done(JSONObject().put("level", r.level).put("reasons", org.json.JSONArray(r.reasons))) }
            else -> return false
        }
        return true
    }

    /** Phone work off the screen thread; an error never ends the app (it may be the phone app). */
    protected fun bg(f: () -> Unit) = thread(name = "ronin-phone") { try { f() } catch (t: Throwable) { android.util.Log.w("Ronin", "phone page: $t") } }
}
