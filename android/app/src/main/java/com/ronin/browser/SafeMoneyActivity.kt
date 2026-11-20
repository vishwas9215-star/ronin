package com.ronin.browser

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream

/**
 * Ronin Safe Money: bank sites only. No screenshots or screen recording
 * (FLAG_SECURE), no page scripts from Ronin, its own sign-in storage when the
 * phone's engine supports it, and every non-bank link goes back to the normal window.
 */
class SafeMoneyActivity : AppCompatActivity() {
    private val app get() = application as RoninApp
    private lateinit var web: WebView
    private lateinit var addressView: TextView
    var usesOwnProfile = false; private set
    private var fileCallback: android.webkit.ValueCallback<Array<android.net.Uri>>? = null
    private val chooser = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        fileCallback?.onReceiveValue(android.webkit.WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        fileCallback = null
    }
    private var webGone = false

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    @SuppressLint("SetJavaScriptEnabled", "SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app.startLists()
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        val green = Color.parseColor("#16A34A")
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#0F1A14")) }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(6), dp(6)); setBackgroundColor(Color.parseColor("#13231A"))
        }
        val badge = TextView(this).apply {
            text = "🛡 Safe Money"; setTextColor(green); setTypeface(typeface, Typeface.BOLD); textSize = 14f
        }
        addressView = TextView(this).apply {
            setTextColor(Color.parseColor("#BBF7D0")); textSize = 12f; maxLines = 1
            setPadding(dp(10), 0, dp(6), 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val close = Button(this).apply { text = L("बंद करें", "Close"); setOnClickListener { askClose() } }
        bar.addView(badge); bar.addView(addressView); bar.addView(close)
        web = WebView(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f) }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            ProfileStore.getInstance().getOrCreateProfile(PROFILE)
            WebViewCompat.setProfile(web, PROFILE)
            usesOwnProfile = true
        }
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
            setGeolocationEnabled(false)
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                val url = req.url.toString()
                val k = app.rules.classify(url, app.store.userBanks)
                if (k == Kind.BANK || k == Kind.PAYMENT || k == Kind.AUTH) return null
                return if (app.blocker.blocked(url))
                    WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                else null
            }

            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val url = req.url.toString()
                if (req.url.scheme != "https") return true // banks are always https; nothing else here
                if (!req.isForMainFrame) return false
                val k = app.rules.classify(url, app.store.userBanks)
                if (k == Kind.BANK || k == Kind.PAYMENT || k == Kind.AUTH) return false
                // any other site (a tap OR a redirect by itself) never sits under the green "protected" badge:
                // it goes to the normal window, which also runs the danger / fake-bank checks
                startActivity(Intent(this@SafeMoneyActivity, MainActivity::class.java).setData(req.url))
                return true
            }

            /** Android ended the page engine (low memory): Safe Money closes cleanly instead of Ronin crashing. */
            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                (view.parent as? ViewGroup)?.removeView(view)
                view.destroy(); webGone = true
                android.widget.Toast.makeText(this@SafeMoneyActivity, L("Memory कम थी: Safe Money फिर से खोलें", "Memory was low: please open Safe Money again"), android.widget.Toast.LENGTH_LONG).show()
                finish()
                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                addressView.text = url
            }
        }
        // the bank's own questions (alert / confirm) and file upload (KYC documents); statements can be downloaded
        web.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onShowFileChooser(w: WebView, cb: android.webkit.ValueCallback<Array<android.net.Uri>>, p: FileChooserParams): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = cb
                return try { chooser.launch(p.createIntent()); true } catch (e: Exception) { fileCallback = null; false }
            }
        }
        web.setDownloadListener { url, ua, cd, mime, _ -> download(url, ua, cd, mime) }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (!webGone && web.canGoBack()) web.goBack() else askClose() }
        })
        root.addView(bar); root.addView(web)
        keepClearOfSystemBars(root)
        setContentView(root)
        val start = intent?.dataString
        if (start != null && start.startsWith("https://")) web.loadUrl(start) else web.loadDataWithBaseURL(
            "https://ronin.safemoney/", home(), "text/html", "utf-8", null
        )
    }

    /** Closing Safe Money signs him out of the bank (nothing is kept): one question first. */
    private fun askClose() {
        if (webGone || isFinishing) { finish(); return }
        android.app.AlertDialog.Builder(this).setTitle(L("Safe Money बंद करें?", "Close Safe Money?"))
            .setMessage(L("बंद करने पर bank से logout हो जाएगा।", "Closing it signs you out of the bank."))
            .setPositiveButton(L("हाँ, बंद करें", "Yes, close")) { _, _ -> finish() }
            .setNegativeButton(L("नहीं", "No"), null).show()
    }

    /** Statements and receipts only (PDF / Excel / CSV), saved in Downloads. */
    private fun download(url: String, ua: String, cd: String?, mime: String?) {
        val name = android.webkit.URLUtil.guessFileName(url, cd, mime)
        val ok = Regex("(?i).*\\.(pdf|csv|xlsx?)$").matches(name) || (mime ?: "") in setOf("application/pdf", "text/csv", "application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
        if (!ok || !url.startsWith("https://")) {
            android.widget.Toast.makeText(this, L("Safe Money में सिर्फ़ PDF / Excel statement save होते हैं", "Safe Money saves only PDF / Excel statements"), android.widget.Toast.LENGTH_LONG).show()
            return
        }
        try {
            val cookies = (if (usesOwnProfile) ProfileStore.getInstance().getProfile(PROFILE)?.cookieManager else CookieManager.getInstance())?.getCookie(url) ?: ""
            val req = android.app.DownloadManager.Request(android.net.Uri.parse(url)).setMimeType(mime)
                .addRequestHeader("User-Agent", ua).addRequestHeader("Cookie", cookies)
                .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, name)
            (getSystemService(DOWNLOAD_SERVICE) as android.app.DownloadManager).enqueue(req)
            android.widget.Toast.makeText(this, L("Download हो रहा है: $name", "Downloading $name"), android.widget.Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, L("Download शुरू नहीं हुआ", "Download could not start"), android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    override fun onStart() { super.onStart(); if (!webGone) web.resumeTimers() } // the browser may have rested all page timers

    private fun home(): String {
        val links = listOf(
            "SBI" to "https://onlinesbi.sbi/", "HDFC Bank" to "https://netbanking.hdfcbank.com/",
            "ICICI Bank" to "https://www.icicibank.com/", "Axis Bank" to "https://www.axisbank.com/",
            "Bank of Baroda" to "https://www.bankofbaroda.in/", "Punjab National Bank" to "https://www.pnbindia.in/",
            "Kotak" to "https://www.kotak.com/", "India Post Payments Bank" to "https://www.ippbonline.com/"
        ) + app.store.userBanks.map { it to "https://$it/" }
        val a = links.joinToString("") { "<a href=\"${android.text.TextUtils.htmlEncode(it.second)}\">${android.text.TextUtils.htmlEncode(it.first)}</a>" }
        return """<!DOCTYPE html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>body{margin:0;padding:24px 16px;background:#0f1a14;color:#e8eaf0;font-family:sans-serif}
h1{color:#22c55e;font-size:24px;margin:0 0 6px}p{opacity:.8}.g{display:grid;grid-template-columns:repeat(auto-fill,minmax(140px,1fr));gap:10px}
a{display:block;padding:14px;border-radius:12px;background:#13231a;color:#e8eaf0;text-decoration:none;font-weight:600}</style></head><body>
<h1>Ronin Safe Money</h1><p>Protected banking: no screenshots, nothing else running here.</p><div class="g">$a</div>
<p><b>Never</b> share an OTP, PIN or password on a call or chat. Real Indian bank addresses end in <b>.bank.in</b>.</p></body></html>"""
    }

    override fun onDestroy() {
        // nothing of the bank visit is kept when Safe Money closes
        if (usesOwnProfile) {
            try { ProfileStore.getInstance().getProfile(PROFILE)?.cookieManager?.removeAllCookies(null) } catch (e: Exception) {}
        }
        if (!webGone) { web.clearCache(true); web.destroy() }
        super.onDestroy()
    }

    companion object { const val PROFILE = "safemoney" }
}
