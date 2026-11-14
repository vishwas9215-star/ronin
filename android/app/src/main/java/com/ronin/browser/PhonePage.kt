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
    private var pendingPerm: (() -> Unit)? = null
    private val permAsk = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { pendingPerm?.invoke(); pendingPerm = null }

    /** One request from the page: answer with done(data) (any thread). */
    abstract fun handle(op: String, a: JSONObject, done: (Any?) -> Unit)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#0f1218"))
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        val rules = RoninApp.instance.rules
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(web, Lang.pageScript(this) + "window.__roninLinkLists=" + rules.safeLinks.json + ";window.__roninScamRules=" + rules.scamRulesJson + ";", setOf("https://ronin.home"))
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
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                val u = req.url
                if (u.host != "ronin.home") return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), "".byteInputStream())
                val name = (u.path ?: "").removePrefix("/tools/")
                if (name !in setOf(page, page.replace(".html", ".js"), "scamcheck.js")) return WebResourceResponse("text/plain", "utf-8", 404, "No", emptyMap(), "".byteInputStream())
                val mime = if (name.endsWith(".js")) "text/javascript" else "text/html"
                return try { WebResourceResponse(mime, "utf-8", assets.open("ronin/tools/$name")) } catch (e: Exception) { null }
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
    }

    open fun startQuery(i: Intent?): String = ""
    override fun onDestroy() { try { web.destroy() } catch (e: Exception) { }; super.onDestroy() }
    fun webForTest(): WebView = web

    /** The page is told something happened (a new SMS, a call ended). */
    protected fun event(name: String) = runOnUiThread { reply?.postMessage(JSONObject().put("event", name).toString()) }

    protected fun openOutside(u: String) {
        try {
            when {
                u.startsWith("tel:") -> startActivity(Intent(Intent.ACTION_DIAL, Uri.parse(u)))
                u.startsWith("http") -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u), this, MainActivity::class.java))
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
            "appInfo" -> { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))); done(true) }
            "defaultApps" -> { try { startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) } catch (e: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }; done(true) }
            "notifyAccess" -> { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); done(true) }
            "watcher" -> done(NotifyWatch.enabled(this))
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
