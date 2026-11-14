package com.ronin.browser

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.PermissionRequest
import android.webkit.SafeBrowsingResponse
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream

/**
 * Android 15+ draws apps under the status bar and the navigation bar: Ronin keeps
 * its address bar and buttons clear of them (and of the keyboard).
 */
fun keepClearOfSystemBars(root: View) {
    ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
        val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
        v.setPadding(b.left, b.top, b.right, b.bottom)
        WindowInsetsCompat.CONSUMED
    }
}

class MainActivity : AppCompatActivity() {

    inner class Tab(val web: RoninWebView) {
        var title = ""
        @Volatile var url = ""
        @Volatile var topHost = ""
    }

    private val app get() = application as RoninApp
    private val tabs = ArrayList<Tab>()
    private var current: Tab? = null
    private var mediaTab: Tab? = null
    private var mediaPlaying = false
    private var mediaTitle = ""
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var address: EditText
    private lateinit var tabsButton: Button
    private lateinit var tabStrip: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var holder: FrameLayout
    private lateinit var readBar: LinearLayout
    private lateinit var readPlay: Button
    private lateinit var readSpeed: Button
    private lateinit var readNote: TextView
    private var readAloud: ReadAloud? = null

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermission: PermissionRequest? = null
    private var fullView: View? = null
    private var fullCallback: WebChromeClient.CustomViewCallback? = null

    private val voice = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val text = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!text.isNullOrBlank()) go(text.trim().trimEnd('.', '।', '?', '!'))
    }
    private val chooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        fileCallback = null
    }
    private val askMedia = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val req = pendingPermission ?: return@registerForActivityResult
        pendingPermission = null
        val ok = req.resources.filter {
            (it == PermissionRequest.RESOURCE_AUDIO_CAPTURE && res[Manifest.permission.RECORD_AUDIO] != false) ||
                (it == PermissionRequest.RESOURCE_VIDEO_CAPTURE && res[Manifest.permission.CAMERA] != false)
        }
        if (ok.isEmpty()) req.deny() else req.grant(ok.toTypedArray())
    }
    // once allowed, show the player card at once (it may have been held back before)
    private val askNotify = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok && mediaTab != null) MediaPlaybackService.update(this, mediaTitle, "", mediaPlaying)
    }

    // ---- start ---------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(
            if (app.store.night) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
        super.onCreate(savedInstanceState)
        buildUi()
        PlayerBridge.control = { a -> ui.post { mediaControl(a) } }

        val restore = savedInstanceState?.getStringArrayList("tabs")
        val start = intent?.dataString
        when {
            !restore.isNullOrEmpty() -> {
                restore.forEach { newTab(it, select = false) }
                select(tabs[savedInstanceState.getInt("active", 0).coerceIn(0, tabs.size - 1)])
            }
            start != null && start.startsWith("http") -> newTab(start)
            else -> newTab(HOME)
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val t = current
                when {
                    fullView != null -> fullCallback?.onCustomViewHidden()
                    t != null && t.web.canGoBack() -> t.web.goBack()
                    tabs.size > 1 && t != null -> closeTab(t)
                    else -> moveTaskToBack(true) // the song keeps playing
                }
            }
        })
        ui.postDelayed(mediaPoll, 1500)
        Updater.check(this, quiet = true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val u = intent.dataString
        if (u != null && u.startsWith("http")) newTab(u)
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putStringArrayList("tabs", ArrayList(tabs.map { it.url.ifEmpty { HOME } }))
        out.putInt("active", tabs.indexOf(current).coerceAtLeast(0))
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun iconButton(icon: Int, desc: String, click: (View) -> Unit) = ImageButton(this).apply {
        setImageResource(icon)
        contentDescription = desc
        setBackgroundColor(Color.TRANSPARENT)
        setColorFilter(Color.parseColor("#E8EAF0"))
        setOnClickListener(click)
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
    }

    @SuppressLint("SetTextI18n")
    private fun buildUi() {
        val bg = Color.parseColor("#14171F")
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(2), dp(4))
            setBackgroundColor(bg)
        }
        address = EditText(this).apply {
            hint = "Search or type a web address"
            setSingleLine(true)
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setTextColor(Color.parseColor("#E8EAF0"))
            setHintTextColor(Color.parseColor("#9AA3B2"))
            textSize = 15f
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = ContextCompat.getDrawable(context, android.R.drawable.editbox_background_normal)
            background.setTint(Color.parseColor("#262B36"))
            setSelectAllOnFocus(true)
            setOnEditorActionListener { v, id, ev ->
                if (id == EditorInfo.IME_ACTION_GO || (ev != null && ev.keyCode == KeyEvent.KEYCODE_ENTER)) {
                    go(v.text.toString()); true
                } else false
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val mic = iconButton(android.R.drawable.ic_btn_speak_now, "Speak to search") { startVoice() }
        tabsButton = Button(this).apply {
            text = "1"
            contentDescription = "Tabs"
            setTextColor(Color.parseColor("#E8EAF0"))
            setTypeface(typeface, Typeface.BOLD)
            setBackgroundColor(Color.TRANSPARENT)
            minWidth = 0; minimumWidth = 0
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            setOnClickListener { showTabs() }
        }
        val menu = iconButton(android.R.drawable.ic_menu_more, "Menu") { showMenu(it) }
        bar.addView(address); bar.addView(mic); bar.addView(tabsButton); bar.addView(menu)
        // tabs side by side (like a computer browser), with + for a new tab
        tabStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), 0)
        }
        val stripScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(Color.parseColor("#0E1016"))
            addView(tabStrip)
        }
        root.addView(stripScroll)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3))
            progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#22C55E"))
        }
        holder = FrameLayout(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f) }
        readBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setBackgroundColor(Color.parseColor("#1C2029"))
            visibility = View.GONE
        }
        readPlay = Button(this).apply { text = "Pause"; setOnClickListener { readToggle() } }
        val readStop = Button(this).apply { text = "Stop"; setOnClickListener { readAloud?.stop(); readBar.visibility = View.GONE } }
        readSpeed = Button(this).apply { text = "1x"; setOnClickListener { text = "${readAloud?.nextRate() ?: 1f}x" } }
        readNote = TextView(this).apply {
            setTextColor(Color.parseColor("#E8EAF0")); textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp(8), 0, 0, 0)
        }
        readBar.addView(readPlay); readBar.addView(readStop); readBar.addView(readSpeed); readBar.addView(readNote)
        root.addView(bar); root.addView(progress); root.addView(holder); root.addView(readBar)
        keepClearOfSystemBars(root)
        setContentView(root)
    }

    // ---- tabs -----------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun makeWebView(t: () -> Tab): RoninWebView {
        val w = RoninWebView(this)
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false // the next song starts by itself
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
        }
        // Google Safe Browsing: Google keeps its phishing / malware list up to date by itself
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
            WebSettingsCompat.setSafeBrowsingEnabled(w.settings, true)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(w.settings, app.store.night)
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, false)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(w, PageScripts.BACKGROUND_PLAY, PageScripts.backgroundOrigins)
            WebViewCompat.addDocumentStartJavaScript(w, adblockJs, setOf("*"))
        }
        w.webViewClient = Client(t)
        w.webChromeClient = Chrome(t)
        w.setDownloadListener(downloads)
        w.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        return w
    }

    private val adblockJs: String by lazy { assets.open("ronin/inject_adblock.js").bufferedReader().readText() }

    fun newTab(url: String, select: Boolean = true): Tab {
        lateinit var tab: Tab
        val w = makeWebView { tab }
        tab = Tab(w)
        tabs.add(tab)
        holder.addView(w)
        w.visibility = View.GONE
        load(tab, url)
        if (select || current == null) select(tab)
        updateTabsButton()
        return tab
    }

    private fun select(t: Tab) {
        current?.web?.visibility = View.GONE
        current = t
        t.web.visibility = View.VISIBLE
        if (!address.hasFocus()) address.setText(display(t.url))
        updateTabsButton()
    }

    private fun closeTab(t: Tab) {
        val i = tabs.indexOf(t)
        tabs.remove(t)
        holder.removeView(t.web)
        if (t == mediaTab) { mediaTab = null; mediaPlaying = false }
        t.web.destroy()
        if (tabs.isEmpty()) newTab(HOME) else if (current == t) select(tabs[(i - 1).coerceAtLeast(0)])
        updateTabsButton()
    }

    private fun updateTabsButton() {
        tabsButton.text = tabs.size.toString()
        if (!::tabStrip.isInitialized) return
        tabStrip.removeAllViews()
        val wide = resources.configuration.smallestScreenWidthDp >= 600
        for (t in tabs) {
            val on = t == current
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), 0, dp(2), 0)
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadii = floatArrayOf(dp(10).toFloat(), dp(10).toFloat(), dp(10).toFloat(), dp(10).toFloat(), 0f, 0f, 0f, 0f)
                    setColor(Color.parseColor(if (on) "#14171F" else "#1B1F29"))
                }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)).apply { marginEnd = dp(4) }
                contentDescription = "Tab: " + t.title.ifBlank { "New tab" }
                tag = "tab"
                setOnClickListener { select(t) }
            }
            val name = TextView(this).apply {
                text = t.title.ifBlank { if (t.url == HOME || t.url.isEmpty()) "New tab" else display(t.url) }
                setTextColor(Color.parseColor(if (on) "#FFFFFF" else "#9AA3B2"))
                textSize = 14f
                setSingleLine(true)
                ellipsize = android.text.TextUtils.TruncateAt.END
                maxWidth = dp(if (wide) 220 else 140)
                if (on) setTypeface(typeface, Typeface.BOLD)
            }
            val x = Button(this).apply {
                text = "✕"
                contentDescription = "Close tab"
                setTextColor(Color.parseColor("#9AA3B2"))
                setBackgroundColor(Color.TRANSPARENT)
                minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
                setOnClickListener { closeTab(t) }
            }
            chip.addView(name); chip.addView(x)
            tabStrip.addView(chip)
        }
        val plus = Button(this).apply {
            text = "+"
            textSize = 22f
            contentDescription = "New tab"
            tag = "newtab"
            setTextColor(Color.parseColor("#E8EAF0"))
            setBackgroundColor(Color.TRANSPARENT)
            minWidth = 0; minimumWidth = 0
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(42))
            setOnClickListener { newTab(HOME); address.requestFocus() }
        }
        tabStrip.addView(plus)
        val i = tabs.indexOf(current)
        if (i >= 0) tabStrip.post { (tabStrip.parent as? android.widget.HorizontalScrollView)?.smoothScrollTo(tabStrip.getChildAt(i)?.left ?: 0, 0) }
    }

    fun tabStripForTest(): LinearLayout = tabStrip

    private fun showTabs() {
        val names = tabs.map { (if (it == current) "▶ " else "") + it.title.ifBlank { display(it.url) } }.toMutableList()
        names.add("+ New tab")
        AlertDialog.Builder(this).setTitle("Tabs")
            .setItems(names.toTypedArray()) { _, i -> if (i == tabs.size) newTab(HOME) else select(tabs[i]) }
            .setNeutralButton("Close this tab") { _, _ -> current?.let { closeTab(it) } }
            .show()
    }

    // ---- going places ---------------------------------------------------------------

    /** Typed or spoken text: an address opens, anything else is searched. */
    fun go(text: String) {
        hideKeyboard()
        val t = current ?: newTab(HOME)
        load(t, Omnibox.resolve(text) ?: return)
    }

    fun load(t: Tab, url: String) {
        if (url == HOME) {
            t.url = HOME
            t.web.loadDataWithBaseURL("https://ronin.home/", homeHtml(), "text/html", "utf-8", null)
            return
        }
        if (openSafeMoneyIfBank(url)) return
        t.url = url
        t.topHost = Rules.hostOf(url)
        t.web.loadUrl(url)
    }

    private fun openSafeMoneyIfBank(url: String): Boolean {
        if (app.rules.classify(url, app.store.userBanks) != Kind.BANK) return false
        startActivity(Intent(this, SafeMoneyActivity::class.java).setData(Uri.parse(url)))
        return true
    }

    private fun display(url: String) = if (url == HOME || url.startsWith("https://ronin.home")) "" else url

    private fun hideKeyboard() {
        address.clearFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(address.windowToken, 0)
    }

    private fun startVoice() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak - Hindi or English")
        try { voice.launch(i) } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Voice typing is not available on this phone.", Toast.LENGTH_LONG).show()
        }
    }

    // ---- web view events --------------------------------------------------------------

    private inner class Client(val tab: () -> Tab) : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
            val url = req.url.toString()
            val t = tab()
            if (req.isForMainFrame) {
                stopPage(url)?.let { html ->
                    return WebResourceResponse("text/html", "utf-8", 200, "OK", mapOf("Cache-Control" to "no-store"),
                        ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)))
                }
            }
            val kind = app.rules.classify(t.topHost.let { if (it.isEmpty()) url else "https://$it/" })
            if (kind == Kind.BANK || kind == Kind.PAYMENT) return null // never break a bank or payment page
            if (app.blocker.blockedFrom(url, t.topHost)) {
                return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
            }
            return null
        }

        override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
            val url = req.url.toString()
            val scheme = req.url.scheme?.lowercase() ?: ""
            if (scheme == "http" && req.isForMainFrame && !isLocal(req.url.host)) {
                view.loadUrl("https" + url.substring(4)) // secure version first (HTTPS-only)
                return true
            }
            if (url == BACK_TO_SAFETY) { leaveDangerPage(tab()); return true }
            if (scheme != "http" && scheme != "https") return openOutside(url, view)
            if (req.isForMainFrame && openSafeMoneyIfBank(url)) return true
            return false
        }

        @RequiresApi(27)
        override fun onSafeBrowsingHit(view: WebView, request: WebResourceRequest, threatType: Int, callback: SafeBrowsingResponse) {
            // Google says the site is dangerous: never open it, show Ronin's own red page
            callback.backToSafety(false)
            val host = request.url.host ?: ""
            view.post { view.loadDataWithBaseURL("https://ronin.home/danger", dangerHtml(host, null), "text/html", "utf-8", null) }
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            val t = tab()
            if (!url.startsWith("https://ronin.home")) { t.url = url; t.topHost = Rules.hostOf(url) }
            if (t == current && !address.hasFocus()) address.setText(display(url))
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                view.evaluateJavascript(adblockJs, null)
                if (PageScripts.backgroundOrigins.any { url.startsWith(it) }) view.evaluateJavascript(PageScripts.BACKGROUND_PLAY, null)
            }
        }

        override fun onPageFinished(view: WebView, url: String) {
            val t = tab()
            t.title = view.title ?: ""
            updateTabsButton()
            if (url.startsWith("http") && !url.startsWith("https://ronin.home")) app.store.addHistory(t.title, url)
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            val t = tab()
            if (url.startsWith("http") && !url.startsWith("https://ronin.home")) { t.url = url; t.topHost = Rules.hostOf(url) }
            if (t == current && !address.hasFocus()) address.setText(display(url))
        }
    }

    private fun isLocal(host: String?) = host == null || host == "localhost" || host.startsWith("127.") || host.startsWith("192.168.")

    /** upi:, tel:, mailto:, whatsapp:, intent: links open the right app. */
    private fun openOutside(url: String, view: WebView): Boolean {
        try {
            if (url.startsWith("intent:")) {
                val i = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                i.addCategory(Intent.CATEGORY_BROWSABLE); i.component = null; i.selector = null
                try { startActivity(i) } catch (e: ActivityNotFoundException) {
                    i.getStringExtra("browser_fallback_url")?.let { if (it.startsWith("https://")) view.loadUrl(it) }
                }
            } else if (!url.startsWith("javascript:") && !url.startsWith("file:")) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
            }
        } catch (e: Exception) {
            Toast.makeText(this, "No app can open this link.", Toast.LENGTH_SHORT).show()
        }
        return true
    }

    private inner class Chrome(val tab: () -> Tab) : WebChromeClient() {
        override fun onProgressChanged(view: WebView, p: Int) {
            if (tab() == current) { progress.progress = p; progress.visibility = if (p >= 100) View.INVISIBLE else View.VISIBLE }
        }

        override fun onReceivedTitle(view: WebView, title: String?) { tab().title = title ?: "" }

        override fun onShowFileChooser(w: WebView, cb: ValueCallback<Array<Uri>>, p: FileChooserParams): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = cb
            return try { chooser.launch(p.createIntent()); true } catch (e: Exception) { fileCallback = null; false }
        }

        /** Camera / microphone: video call sites get them, other sites ask. */
        override fun onPermissionRequest(req: PermissionRequest) {
            ui.post {
                val host = req.origin.host ?: ""
                val call = app.rules.classify("https://$host/") == Kind.CALL
                val ask = {
                    pendingPermission = req
                    askMedia.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA))
                }
                if (call) ask() else AlertDialog.Builder(this@MainActivity)
                    .setMessage("$host wants to use your camera / microphone.")
                    .setPositiveButton("Allow") { _, _ -> ask() }
                    .setNegativeButton("Block") { _, _ -> req.deny() }
                    .show()
            }
        }

        override fun onShowCustomView(view: View, cb: CustomViewCallback) {
            fullView = view; fullCallback = cb
            (window.decorView as FrameLayout).addView(view, FrameLayout.LayoutParams(-1, -1))
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }

        override fun onHideCustomView() {
            fullView?.let { (window.decorView as FrameLayout).removeView(it) }
            fullView = null; fullCallback = null
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
    }

    // ---- dangerous sites ------------------------------------------------------------------

    /** Ronin's red page for a known phishing / malware site or a fake bank site, else null. */
    fun stopPage(url: String): String? {
        if (!url.startsWith("http")) return null
        val store = app.store
        if (app.rules.classify(url, store.userBanks) != Kind.GENERAL) return null
        val host = Rules.hostOf(url)
        if (host.isEmpty() || Rules.matchAny(host, store.trustedSites)) return null
        if (app.blocker.dangerous(host)) return dangerHtml(host, null)
        val brand = app.rules.fakeBank(url, store.trustedSites, store.userBanks) ?: return null
        return dangerHtml(host, brand)
    }

    private fun dangerHtml(host: String, brand: String?): String {
        val h = android.text.TextUtils.htmlEncode(host)
        val (title, why) = if (brand == null)
            "खतरनाक साइट रोकी गई" to "यह साइट धोखाधड़ी (phishing) या virus फैलाने वाली साइटों की सूची में है। Ronin ने इसे नहीं खोला।"
        else
            "नकली bank साइट" to "यह पता ${android.text.TextUtils.htmlEncode(brand.uppercase())} जैसा दिखता है, पर असली bank नहीं है। ऐसी साइटें password, OTP और card नंबर चुराती हैं।"
        return """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>$title</title><style>body{margin:0;background:#1a0f12;color:#f3e9ea;font:17px sans-serif;display:flex;min-height:100vh;align-items:center;justify-content:center}
main{max-width:560px;padding:28px}.i{width:56px;height:56px;border-radius:50%;background:#d93025;color:#fff;font:bold 34px sans-serif;display:flex;align-items:center;justify-content:center}
h1{font-size:24px;margin:18px 0 10px}code{display:block;background:#2a1a1e;padding:10px;border-radius:8px;word-break:break-all;margin:12px 0}
p{line-height:1.5}.m{color:#c9b4b8;font-size:14px}a{display:inline-block;margin-top:18px;background:#3b82f6;color:#fff;text-decoration:none;padding:14px 26px;border-radius:10px;font-weight:bold}</style></head>
<body><main><div class="i">!</div><h1>$title</h1><code>$h</code><p>$why</p>
<p class="m">Dangerous site blocked by Ronin. The list updates by itself twice a day. If you are sure this site is safe: Menu → Safe sites.</p>
<a id="back" href="$BACK_TO_SAFETY">वापस जाएँ — Go back</a></main></body></html>"""
    }

    private fun leaveDangerPage(t: Tab) {
        if (t.web.canGoBack()) t.web.goBack() else load(t, HOME)
    }

    private fun showSafeSites() {
        val list = app.store.trustedSites.sorted()
        val input = EditText(this).apply { hint = "example.com"; inputType = InputType.TYPE_TEXT_VARIATION_URI; setSingleLine() }
        val b = AlertDialog.Builder(this).setTitle("Safe sites (Ronin stopped them by mistake)")
        if (list.isNotEmpty()) b.setItems(list.map { "✕  $it" }.toTypedArray()) { _, i ->
            app.store.trustedSites = app.store.trustedSites - list[i]
            Toast.makeText(this, "Removed ${list[i]}", Toast.LENGTH_SHORT).show()
        } else b.setMessage("None. Add a site only if you are completely sure it is safe.")
        b.setView(input).setPositiveButton("Add") { _, _ ->
            val txt = input.text.toString().trim()
            val d = Rules.hostOf(if (txt.contains("://")) txt else "https://$txt").removePrefix("www.")
            if (d.contains('.')) app.store.trustedSites = app.store.trustedSites + d
        }.setNegativeButton("Close", null).show()
    }

    // ---- downloads ----------------------------------------------------------------------

    private val downloads = DownloadListener { url, ua, cd, mime, _ ->
        val name = URLUtil.guessFileName(url, cd, mime)
        val host = Rules.hostOf(url)
        val isApp = name.lowercase().endsWith(".apk") || mime == "application/vnd.android.package-archive"
        if (isApp && !Rules.matchAny(host, trustedAppHosts) && !Rules.matchAny(host, app.store.trustedSites)) {
            AlertDialog.Builder(this).setTitle("App file (.apk) from an unknown site")
                .setMessage("$name\n$host\n\nअनजान साइट से app install करना ख़तरनाक है — इनमें अक्सर virus या पैसे चुराने वाले apps होते हैं। Apps सिर्फ़ Play Store से लें।")
                .setPositiveButton("रद्द करें — Cancel", null)
                .setNeutralButton("फिर भी download करें") { _, _ -> startDownload(url, ua, cd, mime) }
                .show()
            return@DownloadListener
        }
        startDownload(url, ua, cd, mime)
    }

    private fun startDownload(url: String, ua: String, cd: String?, mime: String?) {
        try {
            val name = URLUtil.guessFileName(url, cd, mime)
            val req = android.app.DownloadManager.Request(Uri.parse(url))
                .setMimeType(mime)
                .addRequestHeader("User-Agent", ua)
                .addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
                .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, name)
            (getSystemService(DOWNLOAD_SERVICE) as android.app.DownloadManager).enqueue(req)
            Toast.makeText(this, "Downloading $name", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Download could not start.", Toast.LENGTH_SHORT).show()
        }
    }

    // ---- songs and videos in the background ------------------------------------------------

    private val mediaPoll = object : Runnable {
        override fun run() {
            val candidates = listOfNotNull(mediaTab, current).distinct()
            for (t in candidates) {
                t.web.evaluateJavascript(PageScripts.MEDIA_STATE) { r -> onMediaState(t, r) }
            }
            ui.postDelayed(this, 1000)
        }
    }

    private fun onMediaState(t: Tab, raw: String?) {
        val o = try { JSONObject(JSONArray("[$raw]").getString(0)) } catch (e: Exception) { return }
        if (!o.optBoolean("v")) {
            if (t == mediaTab && !mediaPlaying) return
            if (t == mediaTab && mediaPlaying) {
                mediaPlaying = false
                MediaPlaybackService.update(this, mediaTitle, "", false)
            }
            return
        }
        val playing = o.optBoolean("p")
        val title = o.optString("t", "Ronin")
        if (playing && mediaTab != t) {
            mediaTab?.web?.keepPlaying = false
            mediaTab = t
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (t != mediaTab) return
        // stays on between songs (the player pauses for a moment while the next one loads)
        t.web.keepPlaying = true
        if (playing != mediaPlaying || title != mediaTitle) {
            mediaPlaying = playing; mediaTitle = title
            MediaPlaybackService.update(this, title, o.optString("a"), playing)
        }
    }

    private fun mediaControl(a: String) {
        val t = mediaTab ?: return
        val action = if (a == "stop") "pause" else a
        t.web.evaluateJavascript(PageScripts.mediaCommand(action), null)
        if (a == "stop") { mediaPlaying = false; t.web.keepPlaying = false; mediaTab = null }
    }

    override fun onStop() {
        super.onStop()
        // tabs that do not play are paused to save battery; the playing one goes on
        for (t in tabs) if (t != mediaTab) t.web.onPause()
    }

    override fun onStart() {
        super.onStart()
        for (t in tabs) t.web.onResume()
        kotlin.concurrent.thread(name = "ronin-danger") { app.loadAndRefreshThreats() }
    }

    override fun onDestroy() {
        ui.removeCallbacks(mediaPoll)
        if (PlayerBridge.control != null && !isChangingConfigurations) PlayerBridge.control = null
        readAloud?.shutdown()
        for (t in tabs) t.web.destroy()
        super.onDestroy()
    }

    // ---- menu --------------------------------------------------------------------------

    private fun showMenu(anchor: View) {
        val t = current
        val web = t != null && t.url.startsWith("http")
        val m = PopupMenu(this, anchor)
        val items = linkedMapOf<String, () -> Unit>()
        items["New tab"] = { newTab(HOME) }
        if (web) items[if (app.store.isBookmarked(t!!.url)) "★ Remove bookmark" else "☆ Bookmark this page"] = {
            val added = app.store.toggleBookmark(t.title, t.url)
            Toast.makeText(this, if (added) "Bookmark saved" else "Bookmark removed", Toast.LENGTH_SHORT).show()
        }
        items["Bookmarks"] = { showPages("Bookmarks", app.store.bookmarks, bookmarks = true) }
        items["History"] = { showPages("History", app.store.history, bookmarks = false) }
        if (web) items["🔊 Read this page aloud"] = { startReading() }
        if (web) items["Reload"] = { t!!.web.reload() }
        items[if (app.store.night) "☀ Day mode" else "☾ Night mode"] = {
            app.store.night = !app.store.night
            AppCompatDelegate.setDefaultNightMode(if (app.store.night) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
            recreate()
        }
        if (web) items["This is my bank (open in Safe Money)"] = {
            val h = t!!.topHost.removePrefix("www.")
            if (h.isNotEmpty()) { app.store.userBanks = app.store.userBanks + h; load(t, t.url) }
        }
        items["🛡 Safe sites (stopped by mistake)"] = { showSafeSites() }
        items["Ronin Safe Money (banking)"] = { startActivity(Intent(this, SafeMoneyActivity::class.java)) }
        items["Check for updates"] = { Updater.check(this, quiet = false) }
        items["About Ronin"] = {
            AlertDialog.Builder(this).setTitle("Ronin Browser ${BuildConfigCompat.versionName(this)}")
                .setMessage("Ad-block lists: ${app.blocker.listSize} sites.\nDanger list (phishing / malware): ${app.blocker.threatSize} sites, plus Google Safe Browsing.\nSongs and videos keep playing in the background; control them from the notification, the lock screen or your car.")
                .setPositiveButton("OK", null).show()
        }
        items.keys.forEachIndexed { i, k -> m.menu.add(0, i, i, k) }
        m.setOnMenuItemClickListener { mi -> items.values.elementAt(mi.itemId).invoke(); true }
        m.show()
    }

    private fun showPages(title: String, list: List<Store.Page>, bookmarks: Boolean) {
        if (list.isEmpty()) {
            Toast.makeText(this, if (bookmarks) "No bookmarks yet." else "No pages yet.", Toast.LENGTH_SHORT).show(); return
        }
        val b = AlertDialog.Builder(this).setTitle(title)
            .setItems(list.map { it.title.ifBlank { it.url } }.toTypedArray()) { _, i -> current?.let { load(it, list[i].url) } }
        if (!bookmarks) b.setNeutralButton("Delete history") { _, _ -> app.store.clearHistory() }
        b.show()
    }

    // ---- read aloud ----------------------------------------------------------------------

    private fun startReading() {
        val t = current ?: return
        if (readAloud == null) readAloud = ReadAloud(this) { playing, i, n ->
            runOnUiThread {
                readPlay.text = if (playing) "Pause" else "Play"
                if (n > 0) readNote.text = "${i + 1} / $n"
                if (readAloud?.hindiMissing() == true) readNote.text = "Hindi voice missing: Settings > Text-to-speech > install Hindi"
            }
        }
        t.web.evaluateJavascript(PageScripts.ARTICLE_TEXT) { r ->
            val parts = try {
                val a = JSONArray(JSONArray("[$r]").getString(0))
                (0 until a.length()).map { a.getString(it) }
            } catch (e: Exception) { emptyList() }
            if (parts.isEmpty()) { Toast.makeText(this, "Nothing to read on this page.", Toast.LENGTH_SHORT).show(); return@evaluateJavascript }
            readBar.visibility = View.VISIBLE
            readAloud?.start(parts)
        }
    }

    private fun readToggle() {
        val r = readAloud ?: return
        if (r.playing) r.pause() else r.play()
    }

    // ---- new tab page ----------------------------------------------------------------------

    private fun homeHtml(): String {
        fun link(n: String, u: String) = "<a href=\"$u\">$n</a>"
        val night = app.store.night
        val bg = if (night) "#14171f" else "#eef0f4"
        val fg = if (night) "#e8eaf0" else "#151922"
        val card = if (night) "#1c2029" else "#ffffff"
        return """<!DOCTYPE html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>body{margin:0;padding:28px 18px;background:$bg;color:$fg;font-family:sans-serif}
h1{margin:0 0 4px;font-size:26px}p{margin:0 0 22px;opacity:.7}h2{font-size:13px;letter-spacing:.08em;text-transform:uppercase;opacity:.7;margin:22px 0 8px}
.g{display:grid;grid-template-columns:repeat(auto-fill,minmax(130px,1fr));gap:10px}
a{display:block;padding:14px;border-radius:12px;background:$card;color:$fg;text-decoration:none;font-weight:600}</style></head><body>
<h1>Ronin Browser</h1><p>Safe, private and fast. Songs keep playing in the background.</p>
<h2>Quick links</h2><div class="g">${link("YouTube", "https://m.youtube.com/")}${link("YouTube Music", "https://music.youtube.com/")}${link("Gmail", "https://mail.google.com/")}${link("DIKSHA", "https://diksha.gov.in/")}${link("Wikipedia", "https://hi.wikipedia.org/")}</div>
<h2>Trading</h2><div class="g">${link("Dhan", "https://web.dhan.co/")}${link("TradingView", "https://in.tradingview.com/chart/")}</div>
<h2>Video calls</h2><div class="g">${link("Google Meet", "https://meet.google.com/")}${link("WhatsApp Web", "https://web.whatsapp.com/")}${link("Jitsi Meet", "https://meet.jit.si/")}</div>
<p style="margin-top:26px">Bank websites open in <b>Ronin Safe Money</b>: no screenshots, nothing else running.</p>
</body></html>"""
    }

    /** For the device tests: the address box (must sit below the status bar). */
    fun addressForTest(): View = address

    /** For the device tests: the visible tab's page. */
    fun showMenuForTest() = showMenu(tabsButton)

    fun webForTest(): WebView = current!!.web

    /** For the device tests: load a page as if it came from baseUrl. */
    fun loadHtmlForTest(html: String, baseUrl: String) {
        val t = current ?: newTab(HOME)
        t.url = baseUrl; t.topHost = Rules.hostOf(baseUrl)
        t.web.loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", null)
    }

    companion object {
        const val HOME = "ronin://home"
        const val BACK_TO_SAFETY = "https://ronin.home/back"
        /** App files from these sites need no warning (Ronin's own updates come from GitHub). */
        val trustedAppHosts = listOf("github.com", "githubusercontent.com", "f-droid.org")
    }
}
