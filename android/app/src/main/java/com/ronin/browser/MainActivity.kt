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
        @Volatile var dangerHost = "" // the fake-bank red page now showing (its "I know this site" link works only for it)
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
    private lateinit var newTabButton: Button
    private var lastStripTab: Tab? = null
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
            addView(tabStrip)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        // "+" is pinned at the right edge, so it never scrolls out of sight
        newTabButton = Button(this).apply {
            text = "+"
            textSize = 24f
            contentDescription = "New tab"
            tag = "newtab"
            setTextColor(Color.parseColor("#E8EAF0"))
            setBackgroundColor(Color.TRANSPARENT)
            minWidth = 0; minimumWidth = 0
            layoutParams = LinearLayout.LayoutParams(dp(60), dp(50))
            setOnClickListener {
                newTab(HOME)
                address.requestFocus()
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(address, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        val stripRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#0E1016"))
            addView(stripScroll); addView(newTabButton)
        }
        root.addView(stripRow)
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
        w.setBackgroundColor(homeBg()) // a new tab opens dark, like its page (no white flash)
        w.settings.userAgentString = phoneUa
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
        if (exporting) { exporting = false; readBar.visibility = View.GONE }
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
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)).apply { marginEnd = dp(4) }
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
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
                setOnClickListener { closeTab(t) }
            }
            chip.addView(name); chip.addView(x)
            tabStrip.addView(chip)
        }
        val i = tabs.indexOf(current)
        if (current == lastStripTab) return // a title changed: do not jump while the user is swiping the tabs
        lastStripTab = current
        if (i >= 0) tabStrip.post { (tabStrip.parent as? android.widget.HorizontalScrollView)?.smoothScrollTo(tabStrip.getChildAt(i)?.left ?: 0, 0) }
    }

    fun tabStripForTest(): LinearLayout = tabStrip
    fun newTabButtonForTest(): View = newTabButton
    fun goHomeForTest() { current?.let { load(it, HOME) } }
    fun exportDataForTest() { current?.let { exportData(it) } }

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
        t.web.settings.userAgentString = uaFor(url)
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
                stopPage(url, t)?.let { html ->
                    return WebResourceResponse("text/html", "utf-8", 200, "OK", mapOf("Cache-Control" to "no-store"),
                        ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)))
                }
                return null // the page itself is never treated as an advert (safe sites stay usable)
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
            if (url.startsWith("https://ronin.newtab/")) { quickLinkChange(tab(), req.url); return true }
            if (url.startsWith("https://ronin.data/")) {
                val t = tab()
                if (req.url.getQueryParameter("k") == dataKey && app.store.isDataSite(t.topHost)) exportData(t)
                return true
            }
            if (url.startsWith(TRUST_PREFIX)) { trustFromRedPage(tab(), req.url); return true }
            if (req.isForMainFrame && (scheme == "http" || scheme == "https") && view.settings.userAgentString != uaFor(url)) {
                // Google Sheets / Docs open as the full (computer) version, other sites as normal
                view.settings.userAgentString = uaFor(url)
                view.loadUrl(url)
                return true
            }
            if (req.isForMainFrame && (scheme == "http" || scheme == "https")) {
                // also short links and redirects that end at a dangerous site
                stopPage(url, tab())?.let { html ->
                    view.loadDataWithBaseURL(url, html, "text/html", "utf-8", null)
                    return true
                }
            }
            if (scheme != "http" && scheme != "https") return openOutside(url, view)
            if (req.isForMainFrame && openSafeMoneyIfBank(url)) return true
            return false
        }

        @RequiresApi(27)
        override fun onSafeBrowsingHit(view: WebView, request: WebResourceRequest, threatType: Int, callback: SafeBrowsingResponse) {
            // Google says the site is dangerous: never open it, show Ronin's own red page
            callback.backToSafety(false)
            if (!request.isForMainFrame) {
                Toast.makeText(this@MainActivity, "इस page का एक ख़तरनाक हिस्सा रोका गया", Toast.LENGTH_SHORT).show()
                return
            }
            val host = request.url.host ?: ""
            view.post { view.loadDataWithBaseURL("https://ronin.home/danger", dangerHtml(host, null), "text/html", "utf-8", null) }
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            val t = tab()
            view.setBackgroundColor(if (url.startsWith("http") && !url.startsWith("https://ronin.home")) Color.WHITE else homeBg())
            if (url.startsWith("http") && !url.startsWith("https://ronin.home") && view.settings.userAgentString != uaFor(url)) {
                // Back / Reload into a site that needs the other version: load it again with the right one
                view.settings.userAgentString = uaFor(url)
                view.stopLoading(); view.loadUrl(url)
                return
            }
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
            showDataButton(t)
            if (url.startsWith("http") && !url.startsWith("https://ronin.home") && !t.title.startsWith("⛔")) app.store.addHistory(t.title, url)
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

        override fun onReceivedTitle(view: WebView, title: String?) { tab().title = title ?: ""; updateTabsButton() }

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
    fun stopPage(url: String, t: Tab? = null): String? {
        if (!url.startsWith("http")) return null
        val store = app.store
        if (app.rules.classify(url, store.userBanks) != Kind.GENERAL) return null
        val host = Rules.hostOf(url)
        if (host.isEmpty() || Rules.matchAny(host, store.trustedSites)) return null
        if (app.blocker.dangerous(host)) return dangerHtml(host, null)
        val brand = app.rules.fakeBank(url, store.trustedSites, store.userBanks) ?: return null
        t?.dangerHost = host
        return dangerHtml(host, brand)
    }

    private fun dangerHtml(host: String, brand: String?): String {
        val h = android.text.TextUtils.htmlEncode(host)
        // a fake-bank warning can be wrong for a real company of a bank group: one tap (with a question) opens it
        val trust = if (brand == null) "" else
            "<p class=\"m\" style=\"margin-top:26px\"><a id=\"trust\" style=\"background:none;padding:0;color:#c9b4b8;text-decoration:underline;font-weight:normal\" href=\"$TRUST_PREFIX?h=${Uri.encode(host)}&k=$pageKey\">मैं इस साइट को जानता हूँ — I know this site</a></p>"
        val (title, why) = if (brand == null)
            "खतरनाक साइट रोकी गई" to "यह साइट धोखाधड़ी (phishing) या virus फैलाने वाली साइटों की सूची में है। Ronin ने इसे नहीं खोला।"
        else
            "नकली bank साइट" to "यह पता ${android.text.TextUtils.htmlEncode(brand.uppercase())} जैसा दिखता है, पर असली bank नहीं है। ऐसी साइटें password, OTP और card नंबर चुराती हैं।"
        return """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>⛔ $title</title><style>body{margin:0;background:#1a0f12;color:#f3e9ea;font:17px sans-serif;display:flex;min-height:100vh;align-items:center;justify-content:center}
main{max-width:560px;padding:28px}.i{width:56px;height:56px;border-radius:50%;background:#d93025;color:#fff;font:bold 34px sans-serif;display:flex;align-items:center;justify-content:center}
h1{font-size:24px;margin:18px 0 10px}code{display:block;background:#2a1a1e;padding:10px;border-radius:8px;word-break:break-all;margin:12px 0}
p{line-height:1.5}.m{color:#c9b4b8;font-size:14px}a{display:inline-block;margin-top:18px;background:#3b82f6;color:#fff;text-decoration:none;padding:14px 26px;border-radius:10px;font-weight:bold}</style></head>
<body><main><div class="i">!</div><h1>$title</h1><code>$h</code><p>$why</p>
<p class="m">Dangerous site blocked by Ronin. The list updates by itself twice a day. If you are sure this site is safe: Menu → Safe sites.</p>
<a id="back" href="$BACK_TO_SAFETY">वापस जाएँ — Go back</a>$trust</main></body></html>"""
    }

    /** The engine's own name without "wv" (Google refuses sign-in in apps that show it). */
    private val phoneUa: String by lazy {
        WebSettings.getDefaultUserAgent(this).replace("; wv", "").replace(Regex("Version/\\d+(\\.\\d+)* "), "")
    }

    /** Google Sheets / Docs on a phone or tablet are view-only; the computer version lets you fill them. */
    private val desktopUa: String by lazy {
        val ver = Regex("Chrome/([\\d.]+)").find(phoneUa)?.groupValues?.get(1) ?: "130.0.0.0"
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$ver Safari/537.36"
    }

    fun uaFor(url: String): String {
        val h = Rules.hostOf(url)
        // WhatsApp Web shows only "use the app" to phones and tablets
        return if (h == "docs.google.com" || h.endsWith(".docs.google.com") || h == "web.whatsapp.com") desktopUa else phoneUa
    }

    private fun homeBg() = Color.parseColor(if (app.store.night) "#14171F" else "#EEF0F4")

    /** "I know this site" on the fake-bank page: only from Ronin's own page (key), and only after a question. */
    private fun trustFromRedPage(t: Tab, u: Uri) {
        val host = u.getQueryParameter("h") ?: return
        if (u.getQueryParameter("k") != pageKey || host.isBlank() || t.dangerHost != host) return
        AlertDialog.Builder(this).setTitle("क्या आप पक्का हैं?")
            .setMessage("$host\n\nBanks कभी SMS, WhatsApp या call में भेजे link से login नहीं करवाते। सिर्फ़ तभी खोलें जब आप इस साइट को अच्छी तरह जानते हों।")
            .setPositiveButton("नहीं, वापस जाएँ", { _, _ -> leaveDangerPage(t) })
            .setNegativeButton("हाँ, मैं जानता हूँ") { _, _ ->
                app.store.trustedSites = app.store.trustedSites + host.removePrefix("www.")
                load(t, "https://$host/")
            }.show()
    }

    /** Add / remove a quick link from the new tab page (only Ronin's own page can do this). */
    private fun quickLinkChange(t: Tab, u: Uri) {
        if (t.url != HOME || u.getQueryParameter("k") != pageKey) return
        when (u.path) {
            "/add" -> {
                val ok = app.store.addQuickLink(u.getQueryParameter("s") ?: "", u.getQueryParameter("n") ?: "", u.getQueryParameter("u") ?: "")
                if (!ok) Toast.makeText(this, "यह पता सही नहीं है", Toast.LENGTH_SHORT).show()
            }
            "/del" -> app.store.removeQuickLink(u.getQueryParameter("u") ?: "")
        }
        homeEditing = true
        load(t, HOME)
        homeEditing = false
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

    // ---- Data -> Excel + PDF ---------------------------------------------------------------

    private val tableJs: String by lazy { assets.open("ronin/table_export.js").bufferedReader().readText() }
    private var exporting = false

    /** The green "Excel + PDF" button on sites where the user switched it on. */
    private fun showDataButton(t: Tab) {
        if (!t.url.startsWith("http") || !app.store.isDataSite(t.topHost)) return
        t.web.evaluateJavascript("(function(){if(document.getElementById('ronin-data-btn')||!document.body)return;var b=document.createElement('button');" +
            "b.id='ronin-data-btn';b.textContent='\uD83D\uDCCA Excel + PDF';" +
            "b.style.cssText='position:fixed;right:16px;bottom:16px;z-index:2147483647;min-height:52px;padding:0 22px;border-radius:26px;border:0;background:#16a34a;color:#fff;font:600 17px sans-serif;box-shadow:0 4px 14px rgba(0,0,0,.35)';" +
            "b.onclick=function(){location.href='https://ronin.data/go?k=$dataKey'};document.body.appendChild(b);})()", null)
    }

    private fun jsString(raw: String?): String = try { JSONArray("[$raw]").getString(0) } catch (e: Exception) { "" }

    /** Reads every page of the biggest table, then saves Excel + PDF in Downloads/Ronin. */
    fun exportData(t: Tab) {
        if (exporting) return
        exporting = true
        Toast.makeText(this, "📊 Table पढ़ रहा हूँ... (Reading the table)", Toast.LENGTH_SHORT).show()
        val started = System.currentTimeMillis()
        t.web.evaluateJavascript("$tableJs;window.__roninTable.start()") {
            val poll = object : Runnable {
                override fun run() {
                    if (t !in tabs) { finishExport(null, "tab closed"); return }
                    t.web.evaluateJavascript("window.__roninTable?window.__roninTable.state():''") { raw ->
                        val st = try { JSONObject(jsString(raw)) } catch (e: Exception) { JSONObject() }
                        when (st.optString("phase")) {
                            "reading" -> {
                                if (System.currentTimeMillis() - started > 15 * 60_000) t.web.evaluateJavascript("window.__roninTable.stop()", null) // keep what was read
                                readNote.text = "📊 पेज ${st.optInt("page")} · ${st.optInt("rows")} पंक्तियाँ पढ़ीं..."
                                readBar.visibility = View.VISIBLE
                                ui.postDelayed(this, 600)
                            }
                            "done" -> t.web.evaluateJavascript("window.__roninTable.result()") { res -> finishExport(jsString(res), null) }
                            else -> finishExport(null, st.optString("error").ifEmpty { "No table found on this page." })
                        }
                    }
                }
            }
            poll.run()
        }
    }

    private fun finishExport(json: String?, error: String?) {
        readBar.visibility = View.GONE
        if (json == null) {
            exporting = false
            Toast.makeText(this, "📊 Table नहीं मिली / could not read the table: $error", Toast.LENGTH_LONG).show(); return
        }
        kotlin.concurrent.thread(name = "ronin-export") {
            try {
                val te = TableExport(json)
                val base = (te.title.ifBlank { te.site }).replace(Regex("[\\\\/:*?\"<>|]+"), " ").trim().take(60).ifEmpty { "Table" } +
                    " " + java.text.SimpleDateFormat("yyyy-MM-dd HH.mm", java.util.Locale.US).format(java.util.Date())
                val x = saveToDownloads("$base.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", te.xlsx())
                val p = saveToDownloads("$base.pdf", "application/pdf", te.pdf())
                runOnUiThread {
                    exporting = false
                    val c = te.counts
                    AlertDialog.Builder(this).setTitle("📊 Excel + PDF बन गई")
                        .setMessage((if (te.partial.isNotEmpty()) "⚠ अधूरा / NOT complete: ${te.partial}\n\n" else "") + "${te.rows.size} पंक्तियाँ\nदोहराए: ${(c["dupRows"] ?: 0) + (c["dupNames"] ?: 0)} · मिलते-जुलते नाम: ${c["similar"] ?: 0} · संभावित ग़लतियाँ: ${c["spell"] ?: 0}\n\nDownloads → Ronin में save है।")
                        .setPositiveButton("PDF खोलें") { _, _ -> openFile(p, "application/pdf") }
                        .setNeutralButton("Excel खोलें") { _, _ -> openFile(x, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet") }
                        .setNegativeButton("ठीक है", null).show()
                }
            } catch (e: Exception) {
                runOnUiThread { exporting = false; Toast.makeText(this, "📊 File नहीं बन सकी: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    /** Saves a file in Downloads/Ronin (visible in the Files app). */
    private fun saveToDownloads(name: String, mime: String, bytes: ByteArray): Uri? {
        if (Build.VERSION.SDK_INT >= 29) {
            val cv = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime)
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/Ronin")
            }
            val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return null
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            return uri
        }
        val f = java.io.File(getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), name)
        f.writeBytes(bytes)
        return androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", f)
    }

    private fun openFile(uri: Uri?, mime: String) {
        if (uri == null) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "इसे खोलने वाला app नहीं मिला (Downloads → Ronin में file है)", Toast.LENGTH_LONG).show()
        }
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

    private var lastPlayingAt = 0L

    private val mediaPoll = object : Runnable {
        override fun run() {
            // nothing played for 30 minutes: that tab may rest in the background again (battery)
            mediaTab?.let { if (!mediaPlaying && System.currentTimeMillis() - lastPlayingAt > 30 * 60_000) it.web.keepPlaying = false }
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
        if (playing) lastPlayingAt = System.currentTimeMillis()
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
        items["➕ नया tab / New tab"] = { newTab(HOME) }
        if (web) items[if (app.store.isBookmarked(t!!.url)) "★ Remove bookmark" else "☆ Bookmark this page"] = {
            val added = app.store.toggleBookmark(t.title, t.url)
            Toast.makeText(this, if (added) "Bookmark saved" else "Bookmark removed", Toast.LENGTH_SHORT).show()
        }
        items["Bookmarks / बुकमार्क"] = { showPages("Bookmarks", app.store.bookmarks, bookmarks = true) }
        items["History / इतिहास"] = { showPages("History", app.store.history, bookmarks = false) }
        if (web) items["🔊 Read this page aloud"] = { startReading() }
        if (web) items["Reload"] = { t!!.web.reload() }
        items[if (app.store.night) "☀ Day mode" else "☾ Night mode"] = {
            app.store.night = !app.store.night
            AppCompatDelegate.setDefaultNightMode(if (app.store.night) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
            recreate()
        }
        if (web) items["🏦 यह मेरा bank है (Safe Money में खोलें)"] = {
            val h = t!!.topHost.removePrefix("www.")
            if (h.isNotEmpty()) { app.store.userBanks = app.store.userBanks + h; load(t, t.url) }
        }
        if (web) {
            val on = app.store.isDataSite(t!!.topHost)
            if (on) items["📊 Data → Excel + PDF (इस page की पूरी table)"] = { exportData(t) }
            items[if (on) "📊 Data बटन इस साइट पर बंद करें" else "📊 Data बटन इस साइट पर चालू करें (Excel + PDF)"] = {
                if (app.store.toggleDataSite(t.topHost)) {
                    Toast.makeText(this, "📊 Data बटन चालू: page के नीचे दाईं ओर हरा बटन", Toast.LENGTH_LONG).show()
                    showDataButton(t)
                } else t.web.evaluateJavascript("(function(){var b=document.getElementById('ronin-data-btn');if(b)b.remove()})()", null)
            }
        }
        items["🛡 Safe sites / ग़लती से रुकी साइटें"] = { showSafeSites() }
        items["🏦 Ronin Safe Money (banking)"] = { startActivity(Intent(this, SafeMoneyActivity::class.java)) }
        items["⟳ Update देखें / Check for updates"] = { Updater.check(this, quiet = false) }
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

    private var homeEditing = false // the new tab page stays in "edit links" after an add / remove

    private val quickLinksJs: String by lazy { assets.open("ronin/quicklinks.js").bufferedReader().readText() }

    private fun homeHtml(): String {
        val night = app.store.night
        val bg = if (night) "#14171f" else "#eef0f4"
        val fg = if (night) "#e8eaf0" else "#151922"
        val card = if (night) "#1c2029" else "#ffffff"
        val line = if (night) "#2e3441" else "#d3d8e1"
        val muted = if (night) "#9aa3b2" else "#5a6375"
        val titles = mapOf("Quick links" to "Quick links", "School" to "स्कूल / School", "Trading" to "Trading", "Video calls" to "Video calls")
        val all = app.store.quickLinks(app.rules.quickLinks).filter { !it.url.startsWith("http://127.") } // Ronin Terminal runs on the laptop
        val sections = JSONArray()
        for (sec in app.rules.quickSections) {
            val links = JSONArray()
            all.filter { it.section == sec }.forEach { links.put(JSONObject().put("n", it.name).put("u", it.url)) }
            sections.put(JSONObject().put("id", sec).put("title", titles[sec] ?: sec).put("links", links))
        }
        val data = JSONObject().put("k", pageKey).put("edit", homeEditing).put("sections", sections).toString().replace("</", "<\\/")
        return """<!DOCTYPE html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>New tab</title>
<style>:root{--bg:$bg;--card:$card;--line:$line;--text:$fg;--muted:$muted;--accent:#3b82f6}
body{margin:0;padding:24px 18px 40px;background:$bg;color:$fg;font-family:sans-serif}main{max-width:820px;margin:0 auto}
h1{margin:0 0 4px;font-size:24px}p.sub{margin:0 0 6px;opacity:.7}p.note{margin-top:24px;opacity:.7;font-size:14px}</style></head><body><main>
<h1>Ronin Browser</h1><p class="sub">Safe, private and fast. Songs keep playing in the background.</p>
<div id="ql"></div><script>window.RONIN_QL=$data;</script><script>$quickLinksJs</script>
<p class="note">Bank websites open in <b>Ronin Safe Money</b>: no screenshots, nothing else running.</p>
</main></body></html>"""
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
        const val TRUST_PREFIX = "https://ronin.home/trust"
        /** Proves a link came from Ronin's own page (new tab page, red page). New each start. */
        val pageKey: String = java.util.UUID.randomUUID().toString().replace("-", "")
        /** A separate key for the Data button (it sits inside web pages; it can only start an export). */
        val dataKey: String = java.util.UUID.randomUUID().toString().replace("-", "")
        /** App files from these sites need no warning (Ronin's own updates come from GitHub). */
        val trustedAppHosts = listOf("github.com", "githubusercontent.com", "f-droid.org")
    }
}
