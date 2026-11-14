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

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    @SuppressLint("SetJavaScriptEnabled", "SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        val close = Button(this).apply { text = "Close"; setOnClickListener { finish() } }
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
                if (k == Kind.BANK || k == Kind.PAYMENT || k == Kind.AUTH || !req.hasGesture()) return false
                // a link the user clicked to a normal site: back to the normal window
                startActivity(Intent(this@SafeMoneyActivity, MainActivity::class.java).setData(req.url))
                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                addressView.text = url
            }
        }
        root.addView(bar); root.addView(web)
        keepClearOfSystemBars(root)
        setContentView(root)
        val start = intent?.dataString
        if (start != null && start.startsWith("https://")) web.loadUrl(start) else web.loadDataWithBaseURL(
            "https://ronin.safemoney/", home(), "text/html", "utf-8", null
        )
    }

    private fun home(): String {
        val links = listOf(
            "SBI" to "https://onlinesbi.sbi/", "HDFC Bank" to "https://netbanking.hdfcbank.com/",
            "ICICI Bank" to "https://www.icicibank.com/", "Axis Bank" to "https://www.axisbank.com/",
            "Bank of Baroda" to "https://www.bankofbaroda.in/", "Punjab National Bank" to "https://www.pnbindia.in/",
            "Kotak" to "https://www.kotak.com/", "India Post Payments Bank" to "https://www.ippbonline.com/"
        ) + app.store.userBanks.map { it to "https://$it/" }
        val a = links.joinToString("") { "<a href=\"${it.second}\">${it.first}</a>" }
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
        web.clearCache(true)
        web.destroy()
        super.onDestroy()
    }

    companion object { const val PROFILE = "safemoney" }
}
