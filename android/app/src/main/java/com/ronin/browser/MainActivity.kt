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

open class MainActivity : AppCompatActivity() {
    /** "" = the browser; "mail" / "social" = the Ronin Mail / Ronin Social icons: their own app window
     *  (own task in Recents), only the guarded Safe Social tab, Back stays inside, ✕ leaves the app. */
    protected open val appMode: String = ""
    private val isApp get() = appMode.isNotEmpty()


    inner class Tab(val web: RoninWebView) {
        @Volatile var dangerHost = ""
        var uaFixedFor = "" // the last address reloaded with another browser version // the fake-bank red page now showing (its "I know this site" link works only for it)
        var title = ""
        @Volatile var url = ""
        @Volatile var topHost = ""
        @Volatile var isTools = false // Ronin's own PDF / Photo tools page is open in this tab
        var isPrivate = false // adult / gambling site: own cookies, no history, wiped when closed
        @Volatile var isSocial = false // a Safe Social tab: full screen, guarded (inject_social.js)
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
    private lateinit var toolsButton: Button
    private lateinit var bar: LinearLayout
    private lateinit var stripRow: LinearLayout
    private var lastStripTab: Tab? = null
    private lateinit var progress: ProgressBar
    private lateinit var holder: FrameLayout
    private lateinit var readBar: LinearLayout
    private lateinit var socialBar: LinearLayout
    private lateinit var shield: Button
    private var shieldCounts = IntArray(5)
    private val shieldTick = object : Runnable {
        override fun run() {
            val t = current
            if (!::socialBar.isInitialized || socialBar.visibility != View.VISIBLE || t == null || !t.isSocial) return
            t.web.evaluateJavascript("(function(c){return c?[c.ads,c.links,c.scam,c.rumour,c.mail].join(','):''})(window.__roninSocialCounts)") { v ->
                val p = v.trim('"').split(',').mapNotNull { it.toIntOrNull() }
                if (p.size == 5) { shieldCounts = p.toIntArray(); val n = p.sum(); shield.text = if (n == 0) L("🛡 चालू", "🛡 On") else "🛡 $n" }
            }
            ui.postDelayed(this, 3000)
        }
    }
    /** What Ronin did on this page, in plain words. */
    private fun showShield() {
        val c = shieldCounts
        AlertDialog.Builder(this).setTitle(L("🛡 Ronin आपकी रक्षा कर रहा है", "🛡 Ronin is protecting you"))
            .setMessage(L("इस page पर:\n• गंदे / धोखे वाले विज्ञापन हटाए: ${c[0]}\n• ख़तरनाक links रोके: ${c[1]}\n• ठगी वाले संदेश पर चेतावनी: ${c[2]}\n• \"जाँचें\" वाली ख़बरें: ${c[3]}\n• ठगी वाली emails: ${c[4]}\n\nहर संदेश, comment और reel की caption अपने-आप जाँची जाती है। पैसे / OTP / video call माँगने वाले अनजान से बात न करें। ठगी हो तो 1930।",
                "On this page:\n• Dirty / fake ads removed: ${c[0]}\n• Dangerous links stopped: ${c[1]}\n• Scam messages warned: ${c[2]}\n• News to check first: ${c[3]}\n• Scam emails: ${c[4]}\n\nEvery message, comment and reel caption is checked by itself. Never deal with a stranger who asks for money, an OTP or a video call. Cheated? Call 1930."))
            .setPositiveButton("OK", null).show()
    }
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
        // several files at once ("डेटा मिलाओ") come as a list
        val clip = r.data?.clipData
        val many = if (r.resultCode == RESULT_OK && clip != null && clip.itemCount > 0) Array(clip.itemCount) { clip.getItemAt(it).uri } else null
        fileCallback?.onReceiveValue(many ?: WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
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
        app.store.removeHistory { Sensitive.site(it) } // old visits of adult / gambling sites go away
        if (!isApp) wipePrivate() // left over if Ronin was closed with a private tab open (never while the browser has one)
        if (!isApp) PlayerBridge.control = { a -> ui.post { mediaControl(a) } }

        val restore = savedInstanceState?.getStringArrayList("tabs")
        val start = intent?.dataString
        when {
            appMode == "mail" -> openMail()
            appMode == "social" -> openSocial()
            !restore.isNullOrEmpty() -> {
                restore.forEach { newTab(it, select = false) }
                select(tabs[savedInstanceState.getInt("active", 0).coerceIn(0, tabs.size - 1)])
            }
            start != null && start.startsWith("http") -> newTab(start)
            else -> newTab(HOME)
        }
        handleSpecial(intent)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val t = current
                when {
                    fullView != null -> fullCallback?.onCustomViewHidden()
                    t != null && t.isTools && !t.isSocial -> leaveTools()
                    t != null && t.isSocial -> socialBack(t)
                    t != null && !t.isSocial && t.web.canGoBack() -> t.web.goBack()
                    browsingTabs().size > 1 && t != null -> closeTab(t)
                    else -> moveTaskToBack(true) // the song keeps playing
                }
            }
        })
        if (isApp) return
        Parts.sync(this)
        ui.postDelayed(mediaPoll, 1500)
        Updater.check(this, quiet = true)
        // first start of this version: which parts (Mail / Messages / Calls icons) the family wants
        if (!Parts.prefs(this).getBoolean("asked", false)) ui.postDelayed({ if (!isFinishing) Parts.choose(this, first = true) }, 1200)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val u = intent.dataString
        if (u != null && u.startsWith("http")) newTab(u)
        handleSpecial(intent)
    }

    /** "Ronin Mail" icon, and a WhatsApp / SMS message shared to Ronin ("क्या यह ठगी है?"). */
    private fun handleSpecial(i: Intent?) {
        i ?: return
        if (isApp) return // the Mail / Social window opens its own page once, and keeps it after that
        if (i.component?.className?.endsWith(".MailLauncher") == true) { ui.post { openMail() }; return }
        if (i.action == Intent.ACTION_SEND && i.type?.startsWith("text/") == true) {
            val text = (i.getStringExtra(Intent.EXTRA_TEXT) ?: "").take(4000)
            if (text.isNotBlank()) ui.post { openTools("scamcheck:" + Uri.encode(text)) }
        }
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        val list = browsingTabs().filter { !it.isPrivate } // Ronin Tools and private tabs are not kept
        out.putStringArrayList("tabs", ArrayList(list.map { it.url.ifEmpty { HOME } }))
        out.putInt("active", list.indexOf(current).coerceAtLeast(0))
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
        bar = LinearLayout(this).apply {
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
        // Home (Google) and "+" sit at the left edge, before the tabs, so they never scroll out of sight
        val homeButton = Button(this).apply {
            text = "⌂"
            textSize = 24f
            contentDescription = "Home: Google"
            tag = "home"
            setTextColor(Color.parseColor("#E8EAF0"))
            setBackgroundColor(Color.TRANSPARENT)
            minWidth = 0; minimumWidth = 0
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(50))
            setOnClickListener {
                val t = current
                if (t == null || t.isTools) newTab(GOOGLE) else load(t, GOOGLE)
            }
        }
        newTabButton = Button(this).apply {
            text = "+"
            textSize = 24f
            contentDescription = "New tab"
            tag = "newtab"
            setTextColor(Color.parseColor("#E8EAF0"))
            setBackgroundColor(Color.TRANSPARENT)
            minWidth = 0; minimumWidth = 0
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(50))
            setOnClickListener {
                newTab(HOME)
                address.requestFocus()
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(address, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        // Ronin Tools (Data Match, PDF, Photo): a tool, not a tab
        toolsButton = Button(this).apply {
            text = "🧰 Tools"
            textSize = 14f
            isAllCaps = false
            contentDescription = "Ronin Tools"
            tag = "tools"
            setTextColor(Color.parseColor("#E8EAF0"))
            setBackgroundColor(Color.TRANSPARENT)
            minWidth = 0; minimumWidth = 0
            setPadding(dp(12), 0, dp(12), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { marginEnd = dp(8) }
            setOnClickListener { openTools() }
        }
        stripRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#0E1016"))
            addView(homeButton); addView(newTabButton); addView(stripScroll); addView(toolsButton)
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
        // Safe Social: Back / My apps / Close at the bottom, under the page (never on top of the site's own buttons)
        socialBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
            setBackgroundColor(Color.parseColor("#111827"))
            visibility = View.GONE
            tag = "socialBar"
        }
        fun socialButton(label: String, tagName: String, act: () -> Unit) = Button(this).apply {
            text = label; isAllCaps = false; textSize = 16f; tag = tagName
            setTextColor(Color.WHITE); setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f)
            setOnClickListener { act() }
        }
        socialBar.addView(socialButton(L("← पीछे", "← Back"), "socialBack") { current?.let { t -> if (t.isSocial) socialBack(t) } })
        if (appMode != "mail") socialBar.addView(socialButton(L("⊞ मेरे ऐप", "⊞ My apps"), "socialApps") { current?.let { load(it, SOCIAL) } })
        socialBar.addView(socialButton(L("✕ बंद", "✕ Close"), "socialClose") { current?.let { if (it.isSocial) leaveSocial(it) } })
        // the protection is visible: a green shield with what Ronin stopped on this page
        shield = socialButton(L("🛡 चालू", "🛡 On"), "socialShield") { showShield() }.apply { setTextColor(Color.parseColor("#4ADE80")) }
        socialBar.addView(shield)
        root.addView(bar); root.addView(progress); root.addView(holder); root.addView(readBar); root.addView(socialBar)
        keepClearOfSystemBars(root)
        setContentView(root)
    }

    // ---- tabs -----------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun makeWebView(t: () -> Tab, private: Boolean = false, social: Boolean = false): RoninWebView {
        val w = RoninWebView(this)
        // a private tab keeps its cookies and storage apart from everything else
        if (private && WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            try { androidx.webkit.ProfileStore.getInstance().getOrCreateProfile(PRIVATE_PROFILE); androidx.webkit.WebViewCompat.setProfile(w, PRIVATE_PROFILE) } catch (e: Exception) { }
        }
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
            // Safe Social shows the apps in their own colours (their own dark theme, never Ronin's forced one)
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(w.settings, app.store.night && !social)
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, false)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(w, PageScripts.BACKGROUND_PLAY, PageScripts.backgroundOrigins)
            WebViewCompat.addDocumentStartJavaScript(w, adblockJs, setOf("*"))
            WebViewCompat.addDocumentStartJavaScript(w, socialBaseJs() + socialJs, setOf("*")) // Safe Social protection
            if (social) WebViewCompat.addDocumentStartJavaScript(w, "window.__roninSocialCfg&&(window.__roninSocialCfg.social=true);", setOf("*"))
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            // the only thing a page may ask: "say this warning" (a fixed sentence from Ronin's own list)
            WebViewCompat.addWebMessageListener(w, "roninNative", setOf("*")) { _, m, _, main, _ ->
                val d = m.data ?: ""
                if (main && d.startsWith("speak:")) speakWarning(d.removePrefix("speak:"))
            }
        }
        w.webViewClient = Client(t)
        w.webChromeClient = Chrome(t)
        w.setDownloadListener(downloads)
        w.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        return w
    }

    private val adblockJs: String by lazy { assets.open("ronin/inject_adblock.js").bufferedReader().readText() }
    private val socialJs: String by lazy {
        assets.open("ronin/tools/scamcheck.js").bufferedReader().readText() + "\n" + assets.open("ronin/inject_social.js").bufferedReader().readText()
    }
    private fun socialSettings(): JSONObject = try { JSONObject(app.store.social) } catch (e: Exception) {
        JSONObject().put("apps", JSONArray(listOf("facebook", "instagram", "youtube", "whatsapp"))).put("ytRestrict", true)
    }
    private fun socialBaseJs(): String {
        val c = socialSettings()
        val mail = JSONArray()
        c.optJSONArray("custom")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.takeIf { it.optBoolean("mail") }?.let { x -> Uri.parse(x.optString("url")).host?.let { mail.put(it.lowercase()) } } }
        // socialKey: websites can read it; it only allows "back to my apps" and "close"
        return Lang.pageScript(this) + "window.__roninSocialCfg=" + JSONObject().put("k", socialKey).put("reels", c.optBoolean("reels")).put("mail", mail).put("nativeBar", true) + ";" +
            "window.__roninLinkLists=" + app.rules.safeLinks.json + ";window.__roninScamRules=" + app.rules.scamRulesJson + ";"
    }

    /** Why Safe Social never opens this address ("" = it may). Checked here for every navigation of a Safe Social tab. */
    private fun socialStop(url: String): String {
        if (url.startsWith("https://ronin.") || url.startsWith("about:")) return ""
        app.rules.safeLinks.check(url).let { if (it.isNotEmpty()) return it }
        if (Sensitive.site(url)) return "adult"
        if (url.startsWith("http") && app.rules.fakeBank(url) != null) return "fake"
        return ""
    }
    private fun socialWarn(view: WebView, url: String) = view.evaluateJavascript("window.__roninSocialWarn&&window.__roninSocialWarn(" + JSONObject.quote(url) + ")", null)

    private var tts: android.speech.tts.TextToSpeech? = null
    private val warnings get() = if (Lang.hindi(this)) mapOf(
        "link" to "सावधान! यह लिंक खतरनाक है। रोनिन ने इसे रोक दिया है।",
        "scam" to "सावधान! यह संदेश ठगी का हो सकता है। पैसे, ओटीपी या पिन किसी को न दें।",
        "mail" to "सावधान! यह ईमेल ठगी का हो सकता है। इसे डिलीट कर दें, और कोई पैसा न भेजें।",
        "call" to "अनजान व्यक्ति से वीडियो कॉल न करें।") else mapOf(
        "link" to "Careful! This link is dangerous. Ronin has stopped it.",
        "scam" to "Careful! This message may be a scam. Never give money, an OTP or a PIN to anyone.",
        "mail" to "Careful! This email may be a scam. Delete it, and do not send any money.",
        "call" to "Do not video call a stranger.")
    private val spoke = HashMap<String, Long>()
    private fun speakWarning(id: String) {
        val text = warnings[id] ?: return
        val now = System.currentTimeMillis()
        if (now - (spoke[id] ?: 0L) < 15_000) return // a page cannot make the phone talk again and again
        spoke[id] = now
        val t = tts
        if (t == null) {
            tts = android.speech.tts.TextToSpeech(applicationContext) { st ->
                if (st == android.speech.tts.TextToSpeech.SUCCESS) { tts?.language = if (Lang.hindi(this)) java.util.Locale("hi", "IN") else java.util.Locale("en", "IN"); tts?.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "w") }
            }
        } else t.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "w")
    }

    fun newTab(url: String, select: Boolean = true, private: Boolean = false, social: Boolean = false): Tab {
        lateinit var tab: Tab
        val w = makeWebView({ tab }, private, social)
        tab = Tab(w)
        tab.isPrivate = private
        tab.isSocial = social
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
        socialScreen()
    }

    private fun closeTab(t: Tab) {
        val i = tabs.indexOf(t)
        tabs.remove(t)
        holder.removeView(t.web)
        if (t == mediaTab) { mediaTab = null; mediaPlaying = false }
        if (t.isPrivate) { t.web.clearCache(true); t.web.clearHistory() }
        t.web.destroy()
        if (t.isPrivate && tabs.none { it.isPrivate }) wipePrivate()
        if (exporting) { exporting = false; readBar.visibility = View.GONE }
        val rest = browsingTabs()
        if (rest.isEmpty()) newTab(HOME) else if (current == t) select(rest[(minOf(i, rest.size) - 1).coerceAtLeast(0)])
        updateTabsButton()
    }

    private fun updateTabsButton() {
        val list = browsingTabs()
        tabsButton.text = list.size.toString()
        if (!::tabStrip.isInitialized) return
        val inTools = current?.isTools == true
        toolsButton.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(Color.parseColor(if (inTools) "#2563EB" else "#1B1F29"))
        }
        tabStrip.removeAllViews()
        val wide = resources.configuration.smallestScreenWidthDp >= 600
        for (t in list) {
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
                text = (if (t.isPrivate) "🕶 " else "") + t.title.ifBlank { if (t.url == HOME || t.url.isEmpty()) "New tab" else display(t.url) }
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
        val i = list.indexOf(current)
        if (current == lastStripTab) return // a title changed: do not jump while the user is swiping the tabs
        lastStripTab = current
        if (i >= 0) tabStrip.post { (tabStrip.parent as? android.widget.HorizontalScrollView)?.smoothScrollTo(tabStrip.getChildAt(i)?.left ?: 0, 0) }
    }

    fun tabStripForTest(): LinearLayout = tabStrip
    fun gmailAdvertForTest(u: String) = gmailAdvert(u)
    fun newTabButtonForTest(): View = newTabButton
    fun goHomeForTest() { current?.let { load(it, HOME) } }
    fun exportDataForTest() { current?.let { exportData(it) } }

    private fun showTabs() {
        val list = browsingTabs()
        val names = list.map { (if (it == current) "▶ " else "") + it.title.ifBlank { display(it.url) } }.toMutableList()
        names.add("+ New tab")
        AlertDialog.Builder(this).setTitle("Tabs")
            .setItems(names.toTypedArray()) { _, i -> if (i == list.size) newTab(HOME) else { select(list[i]); updateTabsButton() } }
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

    /** An adult / gambling site opens in a private tab (the empty tab it was typed in goes away). */
    private fun openPrivate(url: String, from: Tab?) {
        val blank = from != null && !from.isPrivate && (from.url.isEmpty() || from.url == HOME)
        newTab(url, select = true, private = true)
        if (blank && from != null && from in tabs) closeTab(from)
        Toast.makeText(this, L("🕶 Private tab: इसकी history और cookies save नहीं होंगी", "🕶 Private tab: its history and cookies are not saved"), Toast.LENGTH_SHORT).show()
    }

    fun load(t: Tab, url: String) {
        if (Sensitive.site(url) && Parts.adultBlocked(this)) { blockAdult(t, url); return }
        if (!t.isPrivate && Sensitive.site(url)) { openPrivate(url, t); return }
        if (url.startsWith(SOCIAL)) {
            t.isTools = true
            t.url = SOCIAL
            t.title = "Safe Social"
            t.web.loadUrl(SOCIAL + "?k=" + toolsKey + "&dark=" + (if (app.store.night) "1" else "0"))
            return
        }
        if (url.startsWith(TOOLS)) {
            t.isTools = true
            t.url = TOOLS
            t.title = "PDF / Photo tools"
            val frag = url.substringAfter('#', "")
            t.web.loadUrl(TOOLS + "?k=" + toolsKey + "&dark=" + (if (app.store.night) "1" else "0") + (if (frag.isNotEmpty()) "#$frag" else ""))
            return
        }
        if (url == HOME) {
            t.url = HOME
            t.web.loadDataWithBaseURL("https://ronin.home/", homeHtml(), "text/html", "utf-8", null)
            return
        }
        if (openSafeMoneyIfBank(url)) return
        if (!neutralUa(url)) t.web.settings.userAgentString = uaFor(url)
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
            if (url.startsWith(TOOLS)) return toolsFile(t, req.url.path ?: "")
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
            if (tab().isSocial && (req.isForMainFrame || (scheme != "http" && scheme != "https")) && socialStop(url).isNotEmpty()) {
                socialWarn(view, url) // Telegram, group invites, UPI apps, .apk, short links, fake bank sites: never from Safe Social
                return true
            }
            if (scheme == "http" && req.isForMainFrame && !isLocal(req.url.host)) {
                view.loadUrl("https" + url.substring(4)) // secure version first (HTTPS-only)
                return true
            }
            if (url == BACK_TO_SAFETY) { leaveDangerPage(tab()); return true }
            // Ronin Mail signed out: Gmail sends you to its advert page; go straight to Google's own sign-in instead
            // once per half minute: if sign-in does not stick, the advert page stays (no loop)
            if (tab().isSocial && req.isForMainFrame && gmailAdvert(url) && System.currentTimeMillis() - gmailSent > 30_000) { gmailSent = System.currentTimeMillis(); view.loadUrl(GMAIL_SIGNIN); return true }
            if (req.isForMainFrame && Sensitive.site(url) && Parts.adultBlocked(this@MainActivity)) { blockAdult(tab(), url); return true }
            if (req.isForMainFrame && Parts.adultBlocked(this@MainActivity) && Parts.safeSearch(url) != null) { view.loadUrl(Parts.safeSearch(url)!!); return true }
            if (req.isForMainFrame && !tab().isPrivate && Sensitive.site(url)) { openPrivate(url, tab()); return true }
            if (url.startsWith("https://ronin.newtab/social")) {
                if (req.url.getQueryParameter("k") == pageKey && tab().url == HOME) openSocial(socialUrl(req.url.getQueryParameter("app")))
                return true
            }
            if (url.startsWith("https://ronin.newtab/")) { quickLinkChange(tab(), req.url); return true }
            if (url.startsWith("https://ronin.data/save")) {
                val t = tab()
                if (req.url.getQueryParameter("k") == toolsKey && t.isTools) saveToolFile(t)
                return true
            }
            if (url.startsWith("https://ronin.data/social")) {
                val k = req.url.getQueryParameter("k"); val d = req.url.getQueryParameter("do")
                // Ronin's own launcher page may do everything; a website only "my apps" / "close"
                val launcher = k == toolsKey && (view.url ?: "").startsWith(SOCIAL)
                if (tab().isSocial && (launcher || (k == socialKey && (d == "home" || d == "close")))) socialRequest(tab(), req.url)
                return true
            }
            if (url.startsWith("https://ronin.data/close")) {
                if (req.url.getQueryParameter("k") == toolsKey && tab().isTools) leaveTools()
                return true
            }
            if (url.startsWith("https://ronin.data/merge")) {
                if (req.url.getQueryParameter("k") == toolsKey && tab().isTools && !exporting) {
                    exporting = true
                    view.evaluateJavascript("window.__roninMerge?window.__roninMerge.result():''") { res ->
                        val json = jsString(res)
                        if (json.isEmpty() || json == "null") finishExport(null, "nothing to save") else finishExport(json, null)
                    }
                }
                return true
            }
            if (url.startsWith("https://ronin.data/")) {
                val t = tab()
                if (req.url.getQueryParameter("k") == dataKey && app.store.isDataSite(t.topHost)) exportData(t)
                return true
            }
            if (url.startsWith(TRUST_PREFIX)) { trustFromRedPage(tab(), req.url); return true }
            if (req.isForMainFrame && (scheme == "http" || scheme == "https") && !neutralUa(url) && view.settings.userAgentString != uaFor(url)) {
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
                Toast.makeText(this@MainActivity, L("इस page का एक ख़तरनाक हिस्सा रोका गया", "A dangerous part of this page was blocked"), Toast.LENGTH_SHORT).show()
                return
            }
            val host = request.url.host ?: ""
            view.post { view.loadDataWithBaseURL("https://ronin.home/danger", dangerHtml(host, null), "text/html", "utf-8", null) }
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            val t = tab()
            if (!url.startsWith(TOOLS)) t.isTools = false
            view.setBackgroundColor(if (url.startsWith("http") && !url.startsWith("https://ronin.home")) Color.WHITE else homeBg())
            if (url.startsWith("http") && !url.startsWith("https://ronin.home") && !neutralUa(url) && view.settings.userAgentString != uaFor(url) && t.uaFixedFor != url) {
                t.uaFixedFor = url // once per address: never a reload loop
                // Back / Reload into a site that needs the other version: load it again with the right one
                view.settings.userAgentString = uaFor(url)
                view.stopLoading(); view.loadUrl(url)
                return
            }
            if (!url.startsWith("https://ronin.home")) { t.url = url; t.topHost = Rules.hostOf(url) }
            if (t == current && !address.hasFocus()) address.setText(display(url))
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                view.evaluateJavascript(adblockJs, null)
                view.evaluateJavascript(socialBaseJs() + (if (tab().isSocial) "window.__roninSocialCfg.social=true;" else "") + socialJs, null)
                if (PageScripts.backgroundOrigins.any { url.startsWith(it) }) view.evaluateJavascript(PageScripts.BACKGROUND_PLAY, null)
            }
        }

        override fun onPageFinished(view: WebView, url: String) {
            val t = tab()
            t.title = view.title ?: ""
            updateTabsButton()
            showDataButton(t)
            if (url.startsWith("http") && !url.startsWith("https://ronin.home") && !t.title.startsWith("⛔") && !t.isPrivate && !Sensitive.site(url)) app.store.addHistory(t.title, url)
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
            // "open in the app" links inside Safe Social stay here (Instagram -> Threads in the same screen)
            if (url.startsWith("intent:") && current?.isSocial == true) {
                val i = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                val web = i.getStringExtra("browser_fallback_url")?.takeIf { it.startsWith("https://") }
                    ?: i.data?.takeIf { it.scheme == "https" || url.contains("scheme=https") }?.let { "https://" + it.host + (it.encodedPath ?: "") + (it.encodedQuery?.let { q -> "?$q" } ?: "") }
                if (web != null) { if (socialStop(web).isNotEmpty()) socialWarn(view, web) else view.loadUrl(web) }
                return true
            }
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

    /** This phone was set "adult sites fully off" (child / elderly): a plain stop page, no way around it. */
    private fun blockAdult(t: Tab, url: String) {
        val html = "<!DOCTYPE html><html><head><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'></head>" +
            "<body style='margin:0;background:#0f1218;color:#f1f3f7;font-family:sans-serif;display:flex;align-items:center;justify-content:center;min-height:100vh;text-align:center;padding:20px'>" +
            "<div><div style='font-size:64px'>⛔</div><h2>This site is blocked</h2><p style='color:#a3acbd'>Family Filter is on for this device.</p>" +
            "<a href='$BACK_TO_SAFETY' style='display:inline-block;margin-top:14px;padding:14px 22px;border-radius:14px;background:#16a34a;color:#fff;text-decoration:none;font-weight:700'>✔ Go back</a></div></body></html>"
        t.web.loadDataWithBaseURL("https://ronin.home/blocked", html, "text/html", "utf-8", null)
    }

    private fun dangerHtml(host: String, brand: String?): String {
        val h = android.text.TextUtils.htmlEncode(host)
        // a fake-bank warning can be wrong for a real company of a bank group: one tap (with a question) opens it
        val trust = if (brand == null) "" else
            "<p class=\"m\" style=\"margin-top:26px\"><a id=\"trust\" style=\"background:none;padding:0;color:#c9b4b8;text-decoration:underline;font-weight:normal\" href=\"$TRUST_PREFIX?h=${Uri.encode(host)}&k=$pageKey\">${L("मैं इस साइट को जानता हूँ", "I know this site")}</a></p>"
        val (title, why) = if (brand == null)
            L("खतरनाक साइट रोकी गई", "Dangerous site blocked") to L("यह साइट धोखाधड़ी (phishing) या virus फैलाने वाली साइटों की सूची में है। Ronin ने इसे नहीं खोला।", "This site is on the list of fraud (phishing) or virus sites. Ronin did not open it.")
        else
            L("नकली bank साइट", "Fake bank site") to L("यह पता ${android.text.TextUtils.htmlEncode(brand.uppercase())} जैसा दिखता है, पर असली bank नहीं है। ऐसी साइटें password, OTP और card नंबर चुराती हैं।", "This address looks like ${android.text.TextUtils.htmlEncode(brand.uppercase())}, but it is not the real bank. Sites like this steal passwords, OTPs and card numbers.")
        return """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>⛔ $title</title><style>body{margin:0;background:#1a0f12;color:#f3e9ea;font:17px sans-serif;display:flex;min-height:100vh;align-items:center;justify-content:center}
main{max-width:560px;padding:28px}.i{width:56px;height:56px;border-radius:50%;background:#d93025;color:#fff;font:bold 34px sans-serif;display:flex;align-items:center;justify-content:center}
h1{font-size:24px;margin:18px 0 10px}code{display:block;background:#2a1a1e;padding:10px;border-radius:8px;word-break:break-all;margin:12px 0}
p{line-height:1.5}.m{color:#c9b4b8;font-size:14px}a{display:inline-block;margin-top:18px;background:#3b82f6;color:#fff;text-decoration:none;padding:14px 26px;border-radius:10px;font-weight:bold}</style></head>
<body><main><div class="i">!</div><h1>$title</h1><code>$h</code><p>$why</p>
<p class="m">Dangerous site blocked by Ronin. The list updates by itself twice a day. If you are sure this site is safe: Menu → Safe sites.</p>
<a id="back" href="$BACK_TO_SAFETY">${L("वापस जाएँ", "Go back")}</a>$trust</main></body></html>"""
    }

    /** The engine's own name without "wv" (Google refuses sign-in in apps that show it). */
    private var gmailSent = 0L
    private val GMAIL_SIGNIN = "https://accounts.google.com/ServiceLogin?service=mail&continue=https%3A%2F%2Fmail.google.com%2Fmail%2F"

    private val phoneUa: String by lazy {
        WebSettings.getDefaultUserAgent(this).replace("; wv", "").replace(Regex("Version/\\d+(\\.\\d+)* "), "")
    }

    /** Google Sheets / Docs on a phone or tablet are view-only; the computer version lets you fill them. */
    private val desktopUa: String by lazy {
        val ver = Regex("Chrome/([\\d.]+)").find(phoneUa)?.groupValues?.get(1) ?: "130.0.0.0"
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$ver Safari/537.36"
    }

    /** Sign-in pages keep whichever version the site that sent you there uses (no back-and-forth). */
    private fun neutralUa(url: String): Boolean {
        val h = Rules.hostOf(url)
        return h == "accounts.google.com" || h.endsWith(".accounts.google.com") || h == "accounts.youtube.com"
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
        AlertDialog.Builder(this).setTitle(L("क्या आप पक्का हैं?", "Are you sure?"))
            .setMessage("$host\n\n" + L("Banks कभी SMS, WhatsApp या call में भेजे link से login नहीं करवाते। सिर्फ़ तभी खोलें जब आप इस साइट को अच्छी तरह जानते हों।", "Banks never ask you to log in through a link sent by SMS, WhatsApp or a call. Open it only if you know this site well."))
            .setPositiveButton(L("नहीं, वापस जाएँ", "No, go back"), { _, _ -> leaveDangerPage(t) })
            .setNegativeButton(L("हाँ, मैं जानता हूँ", "Yes, I know it")) { _, _ ->
                app.store.trustedSites = app.store.trustedSites + host.removePrefix("www.")
                load(t, "https://$host/")
            }.show()
    }

    /** Add / remove a quick link from the new tab page (only Ronin's own page can do this). */
    private fun quickLinkChange(t: Tab, u: Uri) {
        if (t.url != HOME || u.getQueryParameter("k") != pageKey) return
        when (u.path) {
            "/social" -> { openSocial(socialUrl(u.getQueryParameter("app"))); return } // a Safe Social chip opens that app, never "edit links"
            "/add" -> {
                val ok = app.store.addQuickLink(u.getQueryParameter("s") ?: "", u.getQueryParameter("n") ?: "", u.getQueryParameter("u") ?: "")
                if (!ok) Toast.makeText(this, L("यह पता सही नहीं है", "This address is not right"), Toast.LENGTH_SHORT).show()
            }
            "/del" -> app.store.removeQuickLink(u.getQueryParameter("u") ?: "")
            else -> return
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

    // ---- Ronin Tools: not a browsing tab; the 🧰 button and "Back to browser" switch to and from it
    private var beforeTools: Tab? = null
    private fun browsingTabs() = tabs.filter { !it.isTools && !it.isSocial }

    // ---- Safe Social: the family's social apps, full screen, guarded
    private var beforeSocial: Tab? = null
    private val socialAppUrls = mapOf("facebook" to "https://www.facebook.com/", "instagram" to "https://www.instagram.com/?variant=following", "youtube" to "https://m.youtube.com/",
        "whatsapp" to "https://web.whatsapp.com/", "x" to "https://x.com/", "threads" to "https://www.threads.net/", "sharechat" to "https://sharechat.com/",
        "moj" to "https://mojapp.in/", "linkedin" to "https://www.linkedin.com/", "pinterest" to "https://www.pinterest.com/", "snapchat" to "https://web.snapchat.com/",
        "telegram" to "https://web.telegram.org/", "gmail" to "https://mail.google.com/", "outlook" to "https://outlook.live.com/mail/",
        "yahoo" to "https://mail.yahoo.com/", "rediff" to "https://mail.rediff.com/")
    private val socialAppNames = mapOf("facebook" to "Facebook", "instagram" to "Instagram", "youtube" to "YouTube", "whatsapp" to "WhatsApp", "x" to "X",
        "threads" to "Threads", "sharechat" to "ShareChat", "moj" to "Moj", "linkedin" to "LinkedIn", "pinterest" to "Pinterest", "snapchat" to "Snapchat", "telegram" to "Telegram",
        "gmail" to "Gmail", "outlook" to "Outlook", "yahoo" to "Yahoo Mail", "rediff" to "Rediffmail")
    /** The family's own apps / email sites from Safe Social settings ("c0", "c1"...). */
    private fun customApps(): List<Pair<String, String>> {
        val a = socialSettings().optJSONArray("custom") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { x -> x.optString("name") to x.optString("url") }?.takeIf { it.second.startsWith("https://") } }
    }
    private fun socialUrl(app: String?): String {
        socialAppUrls[app]?.let { return it }
        val i = app?.removePrefix("c")?.toIntOrNull() ?: return ""
        return if (app.startsWith("c")) customApps().getOrNull(i)?.second ?: "" else ""
    }
    private fun socialName(app: String): String? = socialAppNames[app] ?: app.removePrefix("c").toIntOrNull()?.let { if (app.startsWith("c")) customApps().getOrNull(it)?.first else null }
    /** The "Ronin Mail" icon: the family's email, full screen, Safe Mail on. */
    private fun gmailAdvert(url: String): Boolean {
        val u = android.net.Uri.parse(url); val h = u.host ?: return false; val p = u.path ?: ""
        return (h == "workspace.google.com" || h == "www.google.com" || h == "google.com") && Regex("^(/intl/[^/]+)?/gmail(/about)?/?$").matches(p)
    }
    fun openMail() = openSocial(socialUrl(socialSettings().optString("mailApp", "gmail")).ifEmpty { "https://mail.google.com/" })

    /** WhatsApp on a phone that has the real app: open that app. WhatsApp Web wants a QR code scanned BY the phone,
     *  which cannot be done on the same phone. (Ronin still guards it: scam warnings on its notifications + calls.) */
    private fun whatsappApp(url: String): Boolean {
        if (android.net.Uri.parse(url).host != "web.whatsapp.com") return false
        val i = listOf("com.whatsapp", "com.whatsapp.w4b").firstNotNullOfOrNull { packageManager.getLaunchIntentForPackage(it) } ?: return false
        return try { startActivity(i); true } catch (e: Exception) { false }
    }

    fun openSocial(open: String = "") {
        if (whatsappApp(open)) return
        val t = tabs.firstOrNull { it.isSocial }
        youtubeRestricted(socialSettings().optBoolean("ytRestrict", true))
        if (t != null) {
            if (t != current) { beforeSocial = current; select(t) }
            if (open.isNotEmpty()) load(t, open)
        } else {
            beforeSocial = current
            newTab(open.ifEmpty { SOCIAL }, select = true, social = true)
        }
        socialScreen()
    }

    private fun socialRequest(t: Tab, u: Uri) {
        u.getQueryParameter("cfg")?.let { c ->
            try { val j = String(android.util.Base64.decode(c, android.util.Base64.DEFAULT), Charsets.UTF_8); JSONObject(j); app.store.social = j } catch (e: Exception) { }
        }
        when (u.getQueryParameter("do")) {
            "open" -> u.getQueryParameter("url")?.takeIf { it.startsWith("https://") && !whatsappApp(it) }?.let { youtubeRestricted(socialSettings().optBoolean("ytRestrict", true)); load(t, it) }
            "home" -> load(t, SOCIAL)
            "close" -> leaveSocial(t)
        }
    }

    /** Back in Safe Social: back inside the site; never back to the app list by surprise. */
    private fun socialBack(t: Tab) {
        val list = t.web.copyBackForwardList()
        val prev = if (list.currentIndex > 0) list.getItemAtIndex(list.currentIndex - 1)?.url else null
        when {
            t.url.startsWith(SOCIAL) || (t.web.url ?: "").startsWith(SOCIAL) -> leaveSocial(t) // on the app list: out (no loop)
            prev != null && !prev.startsWith(SOCIAL) -> t.web.goBack()
            appMode == "social" && !t.url.startsWith(SOCIAL) -> load(t, SOCIAL) // the Social app's own home
            else -> leaveSocial(t)
        }
    }

    private fun leaveSocial(t: Tab) {
        if (isApp) { moveTaskToBack(true); return } // the Mail / Social app goes away like any app
        val back = beforeSocial?.takeIf { it in tabs && !it.isSocial && !it.isTools }
        closeTab(t)
        if (back != null) select(back)
        socialScreen()
    }

    /** YouTube's own Restricted mode (its PREF cookie): dirty videos and shorts stay hidden. */
    private fun youtubeRestricted(on: Boolean) {
        val cm = android.webkit.CookieManager.getInstance()
        for (h in listOf("https://www.youtube.com", "https://m.youtube.com")) cm.setCookie(h, "PREF=f2=" + (if (on) "8000000" else "0") + "; domain=.youtube.com; path=/; max-age=31536000")
    }

    /** A Safe Social tab fills the screen: no address bar, no tab strip, no status bar. */
    private fun socialScreen() {
        if (!::bar.isInitialized) return
        val on = current?.isSocial == true
        bar.visibility = if (on) View.GONE else View.VISIBLE
        stripRow.visibility = if (on) View.GONE else View.VISIBLE
        socialBar.visibility = if (on) View.VISIBLE else View.GONE
        ui.removeCallbacks(shieldTick); if (on) { shieldCounts = IntArray(5); shield.text = L("🛡 चालू", "🛡 On"); ui.postDelayed(shieldTick, 1500) }
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).let { c ->
            if (on) { c.hide(androidx.core.view.WindowInsetsCompat.Type.statusBars()); c.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE }
            else c.show(androidx.core.view.WindowInsetsCompat.Type.statusBars())
        }
    }
    fun currentIsSocialForTest() = current?.isSocial == true
    fun socialBarForTest(): View = socialBar

    /** Nothing of the private tabs stays: cookies and site storage of their own profile are deleted. */
    private fun wipePrivate() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return
        try {
            androidx.webkit.ProfileStore.getInstance().getProfile(PRIVATE_PROFILE)?.let { p ->
                p.cookieManager.removeAllCookies(null)
                p.webStorage.deleteAllData()
            }
        } catch (e: Exception) { }
    }

    fun openTools(start: String = "") {
        val t = tabs.firstOrNull { it.isTools && !it.isSocial }
        if (t == current && start.isEmpty()) return
        if (t != current) beforeTools = current
        val url = TOOLS + (if (start.isNotEmpty()) "#$start" else "")
        if (t != null) { select(t); if (start.isNotEmpty()) load(t, url) } else newTab(url)
        updateTabsButton()
    }

    private fun leaveTools() {
        val back = beforeTools?.takeIf { it in tabs && !it.isTools } ?: browsingTabs().lastOrNull()
        if (back != null) select(back) else newTab(HOME)
        updateTabsButton()
    }

    fun openToolsForTest() = openTools()
    fun currentIsPrivateForTest() = current?.isPrivate == true
    fun historyHasForTest(part: String) = app.store.history.any { it.url.contains(part) }

    /** A file of the tools page, from inside the app (only for a tab Ronin opened the tools in). */
    private fun toolsFile(t: Tab, path: String): WebResourceResponse {
        val name = path.removePrefix("/tools/").ifEmpty { "index.html" }
        val ok = (t.isTools || t.isSocial) && !name.contains("..") && Regex("[A-Za-z0-9_./-]+").matches(name)
        val mime = when {
            name.endsWith(".js") || name.endsWith(".mjs") -> "text/javascript"
            name.endsWith(".html") -> "text/html"
            else -> "text/plain"
        }
        return try {
            if (!ok) throw java.io.FileNotFoundException(name)
            WebResourceResponse(mime, "utf-8", 200, "OK", mapOf("Cache-Control" to "no-store"), assets.open("ronin/tools/$name"))
        } catch (e: Exception) {
            WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }
    }

    /** The tools page made a file: read it in pieces, save it in Downloads/Ronin. */
    private fun saveToolFile(t: Tab) {
        t.web.evaluateJavascript("window.__roninSave?JSON.stringify({name:window.__roninSave.name,mime:window.__roninSave.mime,n:window.__roninSave.n}):''") { raw ->
            val head = try { JSONObject(jsString(raw)) } catch (e: Exception) { return@evaluateJavascript }
            val n = head.optInt("n"); val name = head.optString("name").replace(Regex("[\\\\/:*?\"<>|]+"), " ").trim().ifEmpty { "Ronin file" }.let { if (Regex("(?i).*\\.(pdf|jpe?g|png)$").matches(it)) it else "$it.pdf" } // the tools only ever make these
            val mime = head.optString("mime").ifEmpty { "application/octet-stream" }
            if (n <= 0 || n > 400) return@evaluateJavascript
            val b64 = StringBuilder()
            fun done(ok: Boolean) = t.web.evaluateJavascript("if(window.__roninSave)window.__roninSave.state='${if (ok) "saved" else "failed"}'", null)
            fun part(i: Int) {
                if (i == n) {
                    kotlin.concurrent.thread(name = "ronin-tool-save") {
                        val uri = try { saveToDownloads(name, mime, android.util.Base64.decode(b64.toString(), android.util.Base64.DEFAULT)) } catch (e: Exception) { null }
                        runOnUiThread {
                            done(uri != null)
                            if (uri == null) Toast.makeText(this, L("File save नहीं हुई", "The file was not saved"), Toast.LENGTH_LONG).show()
                            else savedNote(name, uri, mime)
                        }
                    }
                    return
                }
                t.web.evaluateJavascript("window.__roninSave?window.__roninSave.part($i):''") { res ->
                    val s = jsString(res)
                    if (s.isEmpty()) done(false) else { b64.append(s); part(i + 1) }
                }
            }
            part(0)
        }
    }

    private var savedDialog: AlertDialog? = null

    /** "Saved" note with an Open button (one at a time: several photos can follow each other). */
    private fun savedNote(name: String, uri: Uri, mime: String) {
        savedDialog?.dismiss()
        savedDialog = AlertDialog.Builder(this).setMessage(L("✔ Downloads → Ronin में save:\n$name", "✔ Saved in Downloads → Ronin:\n$name"))
            .setPositiveButton(L("खोलें", "Open")) { _, _ -> openFile(uri, mime) }
            .setNeutralButton("Share / WhatsApp") { _, _ -> shareFile(uri, mime) }
            .setNegativeButton("OK", null).show()
    }

    private val tableJs: String by lazy { assets.open("ronin/table_export.js").bufferedReader().readText() }
    private var exporting = false
    private var exportTab: Tab? = null

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
        exportTab = t
        Toast.makeText(this, L("📊 Table पढ़ रहा हूँ...", "📊 Reading the table..."), Toast.LENGTH_SHORT).show()
        val started = System.currentTimeMillis()
        var lastSeen = started
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // he may walk away: the screen stays on
        t.web.evaluateJavascript("$tableJs;window.__roninTable.start()") {
            val poll = object : Runnable {
                override fun run() {
                    if (t !in tabs) { finishExport(null, "tab closed"); return }
                    t.web.evaluateJavascript("window.__roninTable?window.__roninTable.state():''") { raw ->
                        val st = try { JSONObject(jsString(raw)) } catch (e: Exception) { JSONObject() }
                        val phase = st.optString("phase")
                        if (phase.isNotEmpty()) lastSeen = System.currentTimeMillis()
                        when {
                            // "Next" opened a new web page: put the script back, it carries on from sessionStorage
                            phase.isEmpty() && System.currentTimeMillis() - lastSeen < 90_000 -> {
                                t.web.evaluateJavascript("if(document.readyState==='complete'&&!window.__roninTable){$tableJs;window.__roninTable.resume()}", null)
                                ui.postDelayed(this, 1000)
                            }
                            phase == "navigating" -> ui.postDelayed(this, 600)
                            phase == "reading" -> {
                                if (System.currentTimeMillis() - started > 60 * 60_000) t.web.evaluateJavascript("window.__roninTable.stop()", null) // keep what was read
                                val pct = if (st.isNull("pct")) "" else "${st.optInt("pct")}% · "
                                readNote.text = L("📊 ${pct}पेज ${st.optInt("page")} · ${st.optInt("rows")} पंक्तियाँ पढ़ीं...", "📊 ${pct}page ${st.optInt("page")} · ${st.optInt("rows")} rows read...")
                                readBar.visibility = View.VISIBLE
                                ui.postDelayed(this, 600)
                            }
                            phase == "done" -> t.web.evaluateJavascript("window.__roninTable.result()") { res -> finishExport(jsString(res), null) }
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
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (json == null) {
            exporting = false
            Toast.makeText(this, L("📊 Table नहीं मिली: $error", "📊 Could not read the table: $error"), Toast.LENGTH_LONG).show(); return
        }
        kotlin.concurrent.thread(name = "ronin-export") {
            try {
                val te = TableExport(json)
                // short, plain names every portal accepts: Teacher_Level_20261114_1530.xlsx
                fun clean(x: String) = java.text.Normalizer.normalize(x, java.text.Normalizer.Form.NFKD).replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').take(24).trimEnd('_')
                val base = clean(te.title).ifEmpty { clean(te.site.removePrefix("www.")) }.ifEmpty { "data" } +
                    "_" + java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.US).format(java.util.Date())
                val x = saveToDownloads("$base.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", te.xlsx())
                val p = saveToDownloads("$base.pdf", "application/pdf", te.pdf())
                runOnUiThread {
                    exporting = false
                    exportTab?.web?.evaluateJavascript("window.__roninTable&&window.__roninTable.badge('" + L("✔ Excel + PDF बन गई (Downloads → Ronin)", "✔ Excel + PDF ready (Downloads → Ronin)") + "',100,true)", null)
                    val c = te.counts
                    AlertDialog.Builder(this).setTitle(L("📊 Excel + PDF बन गई", "📊 Excel + PDF ready"))
                        .setMessage(if (te.info.isNotEmpty()) te.info.joinToString("\n") { "${it.first}: ${it.second}" }.take(1500) + L("\n\nDownloads → Ronin में save है।", "\n\nSaved in Downloads → Ronin.")
                            else (if (te.partial.isNotEmpty()) L("⚠ अधूरा: ", "⚠ NOT complete: ") + te.partial + "\n\n" else "") + L("${te.rows.size} पंक्तियाँ\nदोहराए: ${(c["dupRows"] ?: 0) + (c["dupNames"] ?: 0)} · मिलते-जुलते नाम: ${c["similar"] ?: 0} · संभावित ग़लतियाँ: ${c["spell"] ?: 0}\n\nDownloads → Ronin में save है।", "${te.rows.size} rows\nRepeated: ${(c["dupRows"] ?: 0) + (c["dupNames"] ?: 0)} · Look-alike names: ${c["similar"] ?: 0} · Possible mistakes: ${c["spell"] ?: 0}\n\nSaved in Downloads → Ronin."))
                        .setPositiveButton(L("PDF खोलें", "Open PDF")) { _, _ -> openFile(p, "application/pdf") }
                        .setNeutralButton(L("Excel खोलें", "Open Excel")) { _, _ -> openFile(x, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet") }
                        .setNegativeButton("PDF Share") { _, _ -> shareFile(p, "application/pdf") }.show()
                }
            } catch (e: Exception) {
                runOnUiThread { exporting = false; Toast.makeText(this, L("📊 File नहीं बन सकी: ${e.message}", "📊 Could not make the file: ${e.message}"), Toast.LENGTH_LONG).show() }
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
                put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1) // hidden until the file is complete
            }
            val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return null
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            contentResolver.update(uri, android.content.ContentValues().apply { put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return uri
        }
        val f = java.io.File(getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), name)
        f.writeBytes(bytes)
        return androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", f)
    }

    /** Sends a saved file to WhatsApp, Gmail... (Android's share list). */
    private fun shareFile(uri: Uri?, mime: String) {
        uri ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { startActivity(Intent.createChooser(send, L("भेजें", "Share"))) } catch (e: Exception) { Toast.makeText(this, L("Share नहीं हो सका", "Could not share"), Toast.LENGTH_SHORT).show() }
    }

    private fun openFile(uri: Uri?, mime: String) {
        if (uri == null) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, L("इसे खोलने वाला app नहीं मिला (Downloads → Ronin में file है)", "No app can open this (the file is in Downloads → Ronin)"), Toast.LENGTH_LONG).show()
        }
    }

    // ---- downloads ----------------------------------------------------------------------

    private val downloads = DownloadListener { url, ua, cd, mime, _ ->
        val name = URLUtil.guessFileName(url, cd, mime)
        val host = Rules.hostOf(url)
        val isApp = name.lowercase().endsWith(".apk") || mime == "application/vnd.android.package-archive"
        if (isApp && !Rules.matchAny(host, trustedAppHosts) && !Rules.matchAny(host, app.store.trustedSites)) {
            AlertDialog.Builder(this).setTitle("App file (.apk) from an unknown site")
                .setMessage("$name\n$host\n\n" + L("अनजान साइट से app install करना ख़तरनाक है — इनमें अक्सर virus या पैसे चुराने वाले apps होते हैं। Apps सिर्फ़ Play Store से लें।", "Installing an app from an unknown site is dangerous: such apps often carry viruses or steal money. Get apps only from the Play Store."))
                .setPositiveButton(L("रद्द करें", "Cancel"), null)
                .setNeutralButton(L("फिर भी download करें", "Download anyway")) { _, _ -> startDownload(url, ua, cd, mime) }
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
            mediaTab?.let { if (!mediaPlaying && System.currentTimeMillis() - lastPlayingAt > 30 * 60_000) releaseMedia() }
            if (mediaTab == null && !onScreen) return // closed and nothing to play: stop checking
            val candidates = listOfNotNull(mediaTab, current).distinct()
            for (t in candidates) {
                t.web.evaluateJavascript(PageScripts.MEDIA_STATE) { r -> onMediaState(t, r) }
            }
            if (mediaTab != null || onScreen) ui.postDelayed(this, 1000) // closed and nothing playing: stop checking
        }
    }

    private fun onMediaState(t: Tab, raw: String?) {
        val o = try { JSONObject(JSONArray("[$raw]").getString(0)) } catch (e: Exception) { return }
        // background play is for songs / talks: short-video feeds (Instagram, Facebook...) and silent (muted)
        // videos never keep the phone awake when Ronin is closed - they ate the battery
        // (YouTube inside Safe Social is a song player too: only the feed sites in noPlaySites are left out)
        if (o.optBoolean("v") && (noBackgroundPlay(t.web.url ?: "") || o.optBoolean("m"))) {
            if (t == mediaTab) releaseMedia()
            return
        }
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

    private val onScreen get() = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)

    private fun releaseMedia() {
        mediaTab?.web?.keepPlaying = false
        if (!onScreen) mediaTab?.web?.onPause() // Ronin is closed: that page rests like the others
        mediaTab = null; mediaPlaying = false
        MediaPlaybackService.stop(this)
    }

    private val noPlaySites = Regex("(^|\\.)(instagram\\.com|facebook\\.com|threads\\.(net|com)|x\\.com|twitter\\.com|sharechat\\.com|moj(app)?\\.(in|app|com)|snapchat\\.com|tiktok\\.com)$")
    private fun noBackgroundPlay(url: String): Boolean = noPlaySites.containsMatchIn(android.net.Uri.parse(url).host ?: "")
    fun noBackgroundPlayForTest(u: String) = noBackgroundPlay(u)

    private fun mediaControl(a: String) {
        val t = mediaTab ?: return
        val action = if (a == "stop") "pause" else a
        t.web.evaluateJavascript(PageScripts.mediaCommand(action), null)
        if (a == "stop") { mediaPlaying = false; t.web.keepPlaying = false; mediaTab = null }
    }

    override fun onStop() {
        super.onStop()
        ui.removeCallbacks(shieldTick) // no polling while Ronin is not on screen
        if (mediaTab == null) ui.removeCallbacks(mediaPoll) // nothing playing: no checks every second in the background
        // tabs that do not play are paused to save battery; the playing one goes on
        for (t in tabs) if (t != mediaTab) t.web.onPause()
    }

    override fun onStart() {
        super.onStart()
        if (::socialBar.isInitialized && socialBar.visibility == View.VISIBLE) { ui.removeCallbacks(shieldTick); ui.postDelayed(shieldTick, 1500) }
        for (t in tabs) t.web.onResume()
        if (!isApp) { ui.removeCallbacks(mediaPoll); ui.postDelayed(mediaPoll, 1500) }
        kotlin.concurrent.thread(name = "ronin-danger") { app.loadAndRefreshThreats() }
    }

    override fun onDestroy() {
        ui.removeCallbacks(mediaPoll); ui.removeCallbacks(shieldTick)
        if (!isApp && PlayerBridge.control != null && !isChangingConfigurations) PlayerBridge.control = null
        readAloud?.shutdown()
        for (t in tabs) t.web.destroy()
        super.onDestroy()
    }

    // ---- menu --------------------------------------------------------------------------

    /** Language: the phone's own (default), English or Hindi. The whole app follows it. */
    private fun chooseLanguage() {
        val values = arrayOf("auto", "en", "hi")
        val names = arrayOf(L("फ़ोन जैसी (अपने-आप)", "Same as the phone (automatic)"), "English", "हिंदी")
        AlertDialog.Builder(this).setTitle("🌐 Language / भाषा")
            .setSingleChoiceItems(names, values.indexOf(Lang.choice(this)).coerceAtLeast(0)) { d, i -> Lang.set(this, values[i]); d.dismiss(); recreate() }
            .setNegativeButton(L("रद्द करें", "Cancel"), null).show()
    }

    private fun showMenu(anchor: View) {
        val t = current
        val web = t != null && t.url.startsWith("http")
        val m = PopupMenu(this, anchor)
        val items = linkedMapOf<String, () -> Unit>()
        items[L("➕ नया tab", "➕ New tab")] = { newTab(HOME) }
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
        if (web) items[L("🏦 यह मेरा bank है (Safe Money में खोलें)", "🏦 This is my bank (open in Safe Money)")] = {
            val h = t!!.topHost.removePrefix("www.")
            if (h.isNotEmpty()) { app.store.userBanks = app.store.userBanks + h; load(t, t.url) }
        }
        if (web) {
            val on = app.store.isDataSite(t!!.topHost)
            if (on) items[L("📊 Data → Excel + PDF (इस page की पूरी table)", "📊 Data → Excel + PDF (the whole table on this page)")] = { exportData(t) }
            items[if (on) L("📊 Data बटन इस साइट पर बंद करें", "📊 Data button off for this site") else L("📊 Data बटन इस साइट पर चालू करें (Excel + PDF)", "📊 Data button on for this site (Excel + PDF)")] = {
                if (app.store.toggleDataSite(t.topHost)) {
                    Toast.makeText(this, L("📊 Data बटन चालू: page के नीचे दाईं ओर हरा बटन", "📊 Data button on: the green button at the bottom right"), Toast.LENGTH_LONG).show()
                    showDataButton(t)
                } else t.web.evaluateJavascript("(function(){var b=document.getElementById('ronin-data-btn');if(b)b.remove()})()", null)
            }
        }
        items["🧰 Ronin Tools (Data Match, PDF, Photo)"] = { openTools() }
        items["🛡️ Safe Social (Facebook, Instagram, YouTube...)"] = { openSocial() }
        items["📧 Safe Mail (Gmail...)"] = { openMail() }
        items[L("📱 Ronin के हिस्से (Mail / Messages / Calls)", "📱 Ronin parts (Mail / Messages / Calls)")] = { Parts.choose(this, first = false) }
        items[if (Parts.adultBlocked(this)) "🛡 Family Filter: ON" else "🛡 Family Filter: OFF"] = { Parts.chooseAdult(this, first = false) }
        items[L("💬 Delete हुए WhatsApp संदेश", "💬 Deleted WhatsApp messages")] = { ChatLogActivity.open(this) }
        if (Parts.isOn(this, Parts.MESSAGES)) items["📩 Ronin Messages"] = { startActivity(Intent(this, MessagesActivity::class.java)) }
        if (Parts.isOn(this, Parts.SOS)) items["🆘 Ronin SOS"] = { startActivity(Intent(this, SosActivity::class.java)) }
        if (Parts.isOn(this, Parts.CALLS)) items["📞 Ronin Calls"] = { startActivity(Intent(this, CallsActivity::class.java)) }
        items[L("🔎 क्या यह ठगी है? (Scam Check)", "🔎 Is this a scam? (Scam Check)")] = { openTools("scamcheck") }
        items[L("🔎 Search widget home screen पर लगाएँ", "🔎 Put the search widget on the home screen")] = {
            if (!SearchWidget.pin(this)) Toast.makeText(this, L("Home screen पर खाली जगह दबाकर रखें → Widgets → Ronin Search", "Press and hold an empty spot on the home screen → Widgets → Ronin Search"), Toast.LENGTH_LONG).show()
        }
        items[L("🛡 Safe sites / ग़लती से रुकी साइटें", "🛡 Safe sites (stopped by mistake)")] = { showSafeSites() }
        items["🏦 Ronin Safe Money (banking)"] = { startActivity(Intent(this, SafeMoneyActivity::class.java)) }
        items[L("⟳ Update देखें", "⟳ Check for updates")] = { Updater.check(this, quiet = false) }
        items["🌐 Language / भाषा"] = { chooseLanguage() }
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
        val titles = mapOf("Quick links" to "Quick links", "School" to L("स्कूल / School", "School"), "Trading" to "Trading", "Video calls" to "Video calls")
        val all = app.store.quickLinks(app.rules.quickLinks).filter { !it.url.startsWith("http://127.") } // Ronin Terminal runs on the laptop
        val sections = JSONArray()
        for (sec in app.rules.quickSections) {
            val links = JSONArray()
            all.filter { it.section == sec }.forEach { links.put(JSONObject().put("n", it.name).put("u", it.url)) }
            sections.put(JSONObject().put("id", sec).put("title", titles[sec] ?: sec).put("links", links))
        }
        // Safe Social row first: the family's apps (opens full screen, guarded)
        val soc = JSONArray().put(JSONObject().put("n", L("🛡️ सभी apps", "🛡️ All apps")).put("u", "https://ronin.newtab/social?k=$pageKey"))
        val chosen = socialSettings().optJSONArray("apps")
        val ids = ArrayList<String>()
        if (chosen != null) for (i in 0 until chosen.length()) ids.add(chosen.optString(i))
        customApps().indices.forEach { ids.add("c$it") }
        for (id in ids) socialName(id)?.let { soc.put(JSONObject().put("n", it).put("u", "https://ronin.newtab/social?app=$id&k=$pageKey")) }
        val withSocial = JSONArray().put(JSONObject().put("id", "Safe Social").put("title", "🛡️ Safe Social").put("links", soc).put("fixed", true))
        for (i in 0 until sections.length()) withSocial.put(sections.get(i))
        val data = JSONObject().put("k", pageKey).put("lang", Lang.code(this)).put("edit", homeEditing).put("sections", withSocial).toString().replace("</", "<\\/")
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
        const val TOOLS = "https://ronin.home/tools/"
        const val PRIVATE_PROFILE = "ronin-private"
        const val SOCIAL = "https://ronin.home/tools/social.html"
        const val GOOGLE = "https://www.google.com/"
        /** Proves "save this file" came from the tools page. */
        val toolsKey: String = java.util.UUID.randomUUID().toString().replace("-", "")
        val socialKey: String = java.util.UUID.randomUUID().toString().replace("-", "")

        /** Proves a link came from Ronin's own page (new tab page, red page). New each start. */
        val pageKey: String = java.util.UUID.randomUUID().toString().replace("-", "")
        /** A separate key for the Data button (it sits inside web pages; it can only start an export). */
        val dataKey: String = java.util.UUID.randomUUID().toString().replace("-", "")
        /** App files from these sites need no warning (Ronin's own updates come from GitHub). */
        val trustedAppHosts = listOf("github.com", "githubusercontent.com", "f-droid.org")
    }
}
