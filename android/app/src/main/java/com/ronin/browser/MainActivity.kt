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

    inner class Tab(var web: RoninWebView) {
        var pending: String? = null // opened later: the page loads only when the tab is first shown (battery)
        var desktop = false // "Desktop site" switched on in the menu for this tab
        var httpFallback: String? = null // the plain-http address Ronin turned into https (offered if https fails)
        var sslBad: String? = null // a host whose certificate was refused: never fall back to http for it
        var failedUrl: String? = null // the page Ronin's error page stands for (its Reload button)
        @Volatile var dangerHost = ""
        var uaFixedFor = "" // the last address reloaded with another browser version // the fake-bank red page now showing (its "I know this site" link works only for it)
        var title = ""
        @Volatile var url = ""
        @Volatile var topHost = ""
        @Volatile var isTools = false // Ronin's own PDF / Photo tools page is open in this tab
        var isPrivate = false // adult / gambling site: own cookies, no history, wiped when closed
        var chakshu: String? = null // the scam SMS / call Ronin Phone sent: fills the Chakshu form in this tab
        var chakshuLoop = false
        var crashedAt = 0L // when its page engine was last ended (a second time within 30 s = no automatic reload)
        var claudeOrigin: String? = null // set = the "🤖 Claude" tab (his own server's live view; cookies kept, nothing injected)
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
    private lateinit var readPlay: Button
    private lateinit var readSpeed: Button
    private lateinit var readNote: TextView
    private var readAloud: ReadAloud? = null

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermission: PermissionRequest? = null
    private var fullView: View? = null
    private var fullCallback: WebChromeClient.CustomViewCallback? = null
    private lateinit var suggestBox: LinearLayout
    private lateinit var findBar: LinearLayout
    private lateinit var findBox: EditText
    private lateinit var findCount: TextView
    private lateinit var undoBar: LinearLayout
    private lateinit var pullBadge: TextView
    private var userTyping = false
    private var closedUrl: String? = null
    private var timersPaused = false
    private var lowered = false // the background player was set to its smallest picture (battery)
    private val httpAllowed = HashSet<String>() // sites he chose to open without https ("Open anyway"), until Ronin closes
    private var lastAsk: AlertDialog? = null
    private var restoring = true // the tabs of the last time are being put back (not saved again one by one)

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
        wipePrivate() // left over if Ronin was closed with a private tab open
        PlayerBridge.control = { a -> ui.post { mediaControl(a) } }

        app.startLists()
        liveScreens++
        val kept = KeptPlayer.take() // a song that kept playing after Ronin was swiped away
        val restore = savedInstanceState?.getStringArrayList("tabs")
        val start = intent?.dataString
        // the tabs of the last time come back (after an update, a swipe-away, a crash); only the front one loads now
        val saved = (if (restore.isNullOrEmpty()) savedTabs() else null)?.let { s ->
            if (kept == null) s else { val l = s.first.toMutableList(); l.remove(kept.url); Pair(l.toList(), s.second) } // no second copy of the song tab
        }
        when {
            !restore.isNullOrEmpty() -> {
                val keep = restore.take(40).toMutableList(); if (kept != null) keep.remove(kept.url) // no second copy of the song tab
                keep.forEach { newTab(it, select = false, lazy = true) }
                // only the song tab was open: it comes back just below (adoptKept), nothing else to show here
                if (tabs.isNotEmpty()) select(tabs[savedInstanceState.getInt("active", 0).coerceIn(0, tabs.size - 1)])
            }
            saved != null && saved.first.isNotEmpty() -> {
                saved.first.forEach { newTab(it, select = false, lazy = true) }
                if (tabs.isNotEmpty()) select(tabs[saved.second.coerceIn(0, tabs.size - 1)])
                if (start != null && start.startsWith("http")) openFromOutside(start)
            }
            start != null && start.startsWith("http") -> if (isClaudeLink(start) || Nav.claudePairingHost(start) != null) { newTab(HOME); openFromOutside(start) } else newTab(start)
            else -> if (kept == null) newTab(HOME)
        }
        if (kept != null) adoptKept(kept)
        if (tabs.isEmpty()) newTab(HOME)
        restoring = false
        handleSpecial(intent)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val t = current
                when {
                    fullView != null -> fullCallback?.onCustomViewHidden()
                    t != null && t.isTools -> leaveTools()
                    // Ronin's error page: one Back goes past the failed address too (never back into the same error)
                    t != null && t.failedUrl != null -> {
                        val steps = backPastError(t)
                        when {
                            steps < 0 -> { t.failedUrl = null; t.web.goBackOrForward(steps) }
                            browsingTabs().size > 1 -> closeTab(t)
                            else -> { t.failedUrl = null; load(t, HOME) }
                        }
                    }
                    t != null && t.web.canGoBack() -> t.web.goBack()
                    browsingTabs().size > 1 && t != null -> closeTab(t)
                    else -> moveTaskToBack(true) // the song keeps playing
                }
            }
        })
        ui.postDelayed(mediaPoll, 1500)
        Updater.check(this, quiet = true)
        upgradeTo312()
        // first start: Family Filter for this device (asked once), then on a SIM phone Messages / Calls
        ui.postDelayed({
            if (isFinishing) return@postDelayed
            if (!Family.prefs(this).getBoolean("adultAsked", false)) Family.chooseAdult(this, first = true)
            // Messages / Calls are the separate Ronin Phone app since 3.14.3: a family that used them hears it once
            else tellPhoneMoved()
        }, 1200)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val u = intent.dataString
        if (u != null && u.startsWith("http")) openFromOutside(u)
        handleSpecial(intent)
    }

    /** "Ronin Mail" icon, and a WhatsApp / SMS message shared to Ronin ("क्या यह ठगी है?"). */
    private fun handleSpecial(i: Intent?) {
        i ?: return
        // Ronin Phone's "Report on Chakshu": the form opening now gets the message (taken once, fresh only)
        i.getStringExtra(Chakshu.EXTRA)?.let { if (Chakshu.isForm(i.dataString)) pendingChakshu = it to System.currentTimeMillis(); i.removeExtra(Chakshu.EXTRA) }
        if (i.action == Intent.ACTION_SEND && i.type?.startsWith("text/") == true) {
            val text = (i.getStringExtra(Intent.EXTRA_TEXT) ?: "").take(4000)
            if (text.isNotBlank()) ui.post { openTools("scamcheck:" + Uri.encode(text)) }
        }
    }

    /** Open tabs of the last time: (addresses, the one in front). */
    private fun savedTabs(): Pair<List<String>, Int>? = try {
        val o = JSONObject(app.store.savedTabs)
        val a = o.getJSONArray("u")
        Pair((0 until a.length()).map { a.getString(it) }.filter { it == HOME || it.startsWith("http") }.take(40), o.optInt("a"))
    } catch (e: Exception) { null }

    private fun saveTabs() {
        val list = browsingTabs().filter { !it.isPrivate && it.claudeOrigin == null }
        app.store.savedTabs = JSONObject().put("u", JSONArray(list.map { it.url.ifEmpty { HOME } })).put("a", list.indexOf(current).coerceAtLeast(0)).toString()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        val list = browsingTabs().filter { !it.isPrivate && it.claudeOrigin == null } // Ronin Tools, private and Claude tabs are not kept
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
        // the address row sits at the BOTTOM, where the thumb is (owner's choice, 18 Nov 2026)
        bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(4), dp(8))
            setBackgroundColor(Color.parseColor("#0E1016"))
            tag = "addressbar"
        }
        address = EditText(this).apply {
            hint = "Search or type a web address"
            setSingleLine(true)
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setTextColor(Color.parseColor("#E8EAF0"))
            setHintTextColor(Color.parseColor("#9AA3B2"))
            textSize = 16f
            setPadding(dp(18), 0, dp(14), 0)
            minHeight = dp(48)
            background = android.graphics.drawable.GradientDrawable().apply { setColor(Color.parseColor("#262B36")); cornerRadius = dp(24).toFloat() }
            setSelectAllOnFocus(true)
            setOnEditorActionListener { v, id, ev ->
                if (id == EditorInfo.IME_ACTION_GO || (ev != null && ev.keyCode == KeyEvent.KEYCODE_ENTER)) {
                    go(v.text.toString()); true
                } else false
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
        }
        val mic = iconButton(android.R.drawable.ic_btn_speak_now, "Speak to search") { startVoice() }.apply { layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)) }
        tabsButton = Button(this).apply {
            text = "1"
            contentDescription = "Tabs"
            setTextColor(Color.parseColor("#E8EAF0"))
            setTypeface(typeface, Typeface.BOLD)
            setBackgroundColor(Color.TRANSPARENT)
            minWidth = 0; minimumWidth = 0
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            background = android.graphics.drawable.GradientDrawable().apply { setStroke(dp(2), Color.parseColor("#9AA3B2")); cornerRadius = dp(10).toFloat() }
            setOnClickListener { showTabs() }
        }
        val menu = iconButton(android.R.drawable.ic_menu_more, "Menu") { showMenu(it) }.apply { layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)); tag = "menu" }
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
            addView(homeButton); addView(newTabButton); addView(stripScroll) // Tools: in the menu now
            addView(Button(this@MainActivity).apply {
                text = "🤖 Claude"; textSize = 15f; isAllCaps = false; tag = "claude-top"
                contentDescription = L("Claude का browser", "Claude's browser")
                setTextColor(Color.WHITE); minWidth = 0; minimumWidth = 0; setPadding(dp(16), 0, dp(16), 0)
                background = android.graphics.drawable.GradientDrawable().apply { setColor(Color.parseColor("#C2410C")); cornerRadius = dp(22).toFloat() }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { marginEnd = dp(8); marginStart = dp(6) }
                setOnClickListener { openClaude() }
            })
            // phone: no tab strip at the top (the tab count button at the bottom opens the tabs); a tablet keeps it
            visibility = if (resources.configuration.smallestScreenWidthDp >= 600) View.VISIBLE else View.GONE
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
        buildHelpers()
        root.addView(progress); root.addView(holder); root.addView(readBar); root.addView(undoBar); root.addView(findBar); root.addView(suggestBox); root.addView(bar)
        keepClearOfSystemBars(root)
        setContentView(root)
    }

    private fun bigText(t: String, size: Float = 16f, click: () -> Unit) = Button(this).apply {
        text = t; textSize = size; isAllCaps = false
        setTextColor(Color.parseColor("#E8EAF0")); setBackgroundColor(Color.TRANSPARENT)
        minWidth = 0; minimumWidth = 0
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52))
        setOnClickListener { click() }
    }

    /** Address suggestions, Find in page, "Tab closed - Undo" and the pull-to-reload sign (all hidden until needed). */
    private fun buildHelpers() {
        suggestBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#1C2029")); visibility = View.GONE }
        address.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { if (address.hasFocus()) userTyping = true }
            override fun afterTextChanged(s: android.text.Editable?) { showSuggestions(s?.toString() ?: "") }
        })
        address.setOnFocusChangeListener { _, f -> if (!f) { userTyping = false; suggestBox.visibility = View.GONE } }

        findBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(4), dp(4)); setBackgroundColor(Color.parseColor("#1C2029")); visibility = View.GONE
        }
        findBox = EditText(this).apply {
            hint = L("इस page में खोजें", "Find in page"); setSingleLine(true); textSize = 16f
            setTextColor(Color.parseColor("#E8EAF0")); setHintTextColor(Color.parseColor("#9AA3B2"))
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) { current?.web?.findAllAsync(s?.toString() ?: "") }
            })
            setOnEditorActionListener { _, _, _ -> current?.web?.findNext(true); true }
        }
        findCount = TextView(this).apply { setTextColor(Color.parseColor("#9AA3B2")); textSize = 14f; setPadding(dp(6), 0, dp(6), 0) }
        findBar.addView(findBox); findBar.addView(findCount)
        findBar.addView(bigText("▲", 20f) { current?.web?.findNext(false) }.apply { contentDescription = L("पिछला", "Previous"); layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)) })
        findBar.addView(bigText("▼", 20f) { current?.web?.findNext(true) }.apply { contentDescription = L("अगला", "Next"); layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)) })
        findBar.addView(bigText("✕", 20f) { closeFind() }.apply { contentDescription = L("खोज बंद करें", "Close find"); tag = "find-close"; layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)) })

        undoBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(8), 0); setBackgroundColor(Color.parseColor("#262B36")); visibility = View.GONE
        }
        undoBar.addView(TextView(this).apply {
            text = L("Tab बंद हुआ", "Tab closed"); setTextColor(Color.parseColor("#E8EAF0")); textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        undoBar.addView(bigText(L("↶ वापस लाएँ", "↶ Undo"), 16f) {
            undoBar.visibility = View.GONE
            closedUrl?.let { closedUrl = null; newTab(it) }
        }.apply { tag = "undo"; setTextColor(Color.parseColor("#93C5FD")); setTypeface(typeface, Typeface.BOLD) })

        pullBadge = TextView(this).apply {
            text = L("⟳ छोड़ें: page फिर से खुलेगा", "⟳ Release to reload"); textSize = 15f; setTextColor(Color.WHITE)
            setPadding(dp(18), dp(10), dp(18), dp(10)); visibility = View.GONE; elevation = dp(8).toFloat()
            background = android.graphics.drawable.GradientDrawable().apply { setColor(Color.parseColor("#1D4ED8")); cornerRadius = dp(22).toFloat() }
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(16) }
        }
        holder.addView(pullBadge)
    }

    private fun showSuggestions(text: String) {
        suggestBox.removeAllViews()
        if (!address.hasFocus() || !userTyping) { suggestBox.visibility = View.GONE; return }
        val first = app.store.quickLinks(app.rules.quickLinks).map { Nav.Hit(it.name, it.url) } + app.store.bookmarks.map { Nav.Hit(it.title, it.url) }
        val hits = Nav.suggest(text, first, app.store.history.map { Nav.Hit(it.title, it.url) })
        for (h in hits) {
            suggestBox.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(56); setPadding(dp(18), dp(6), dp(12), dp(6)); tag = "sug"
                isClickable = true; isFocusable = true; contentDescription = h.title.ifBlank { h.url }
                background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Color.parseColor("#334155")), null, android.graphics.drawable.ColorDrawable(Color.WHITE))
                addView(TextView(this@MainActivity).apply { this.text = "🕘  " + h.title.ifBlank { h.url }; textSize = 16f; setTextColor(Color.parseColor("#F1F3F7")); setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END })
                addView(TextView(this@MainActivity).apply { this.text = h.url.removePrefix("https://").removePrefix("http://"); textSize = 12.5f; setTextColor(Color.parseColor("#93C5FD")); setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END })
                setOnClickListener { hideKeyboard(); val t = current ?: newTab(HOME); load(t, h.url) }
            })
        }
        suggestBox.visibility = if (hits.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun openFind() {
        findBar.visibility = View.VISIBLE
        findBox.requestFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(findBox, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeFind() {
        current?.web?.clearMatches()
        findBox.setText(""); findCount.text = ""
        findBar.visibility = View.GONE
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(findBox.windowToken, 0)
    }

    // ---- tabs -----------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun makeWebView(t: () -> Tab, private: Boolean = false, claude: String? = null): RoninWebView {
        // the screen can be swapped later (a song kept playing after Ronin was swiped away comes back into a new screen)
        val w = RoninWebView(android.content.MutableContextWrapper(this))
        // a private tab keeps its cookies and storage apart from everything else
        if (private && WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            try { androidx.webkit.ProfileStore.getInstance().getOrCreateProfile(PRIVATE_PROFILE); androidx.webkit.WebViewCompat.setProfile(w, PRIVATE_PROFILE) } catch (e: Exception) { }
        }
        w.setBackgroundColor(homeBg()) // a new tab opens dark, like its page (no white flash)
        w.settings.userAgentString = phoneUa
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // battery: sound / video starts by itself only on the song sites (next song); elsewhere after a tap
            mediaPlaybackRequiresUserGesture = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            // a link that opens a new window (receipt, print page) opens in a NEW tab: the form page stays
            setSupportMultipleWindows(!private)
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
        if (claude != null && claude.startsWith("https://")) {
            // Claude's live view: no ad layer, no song tricks; its Paste button may read the clipboard, only on its own
            // host, only in the tab on screen and only within 3 s of his own tap
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addWebMessageListener(w, "roninClip", setOf(claude)) { _, _, origin, main, reply ->
                    val ok = main && origin.toString().trimEnd('/') == claude && w === current?.web && onScreen &&
                        android.os.SystemClock.uptimeMillis() - w.lastTouchAt < 3000
                    try { reply.postMessage(if (ok) clipText() else "") } catch (e: Exception) { }
                }
                WebViewCompat.addDocumentStartJavaScript(w, PageScripts.CLAUDE_PASTE, setOf(claude))
            }
        } else if (claude == null && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(w, PageScripts.BACKGROUND_PLAY, PageScripts.backgroundOrigins)
            WebViewCompat.addDocumentStartJavaScript(w, adblockJs, setOf("*"))
            WebViewCompat.addDocumentStartJavaScript(w, PageScripts.KEEP_BLOBS, setOf("*"))
        }
        wireWebView(w, t)
        return w
    }

    /** This screen's handlers for a page (also for a kept song page that comes back into a new screen). */
    private fun wireWebView(w: RoninWebView, t: () -> Tab) {
        w.webViewClient = Client(t)
        w.webChromeClient = Chrome(t)
        w.setDownloadListener { url, ua, cd, mime, _ -> onDownload(t(), url, ua, cd, mime) }
        w.setOnLongClickListener { linkMenu(t()) }
        w.setFindListener { at, n, done -> if (done && w === current?.web) findCount.text = if (n == 0) "0/0" else "${at + 1}/$n" }
        w.onPull = { armed, done ->
            val tb = t()
            if (tb.isTools || tb.claudeOrigin != null) pullBadge.visibility = View.GONE // tools / Claude: never by a pull
            else if (done) { pullBadge.visibility = View.GONE; pullReload(tb) }
            else { pullBadge.visibility = if (armed) View.VISIBLE else View.GONE; if (armed) pullBadge.bringToFront() }
        }
        w.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    /** The song page kept after a swipe-away: back as a tab of this screen, still playing, in front. */
    private fun adoptKept(k: KeptPlayer.Kept) {
        lateinit var tab: Tab
        (k.web.context as? android.content.MutableContextWrapper)?.baseContext = this
        tab = Tab(k.web)
        tab.url = k.url; tab.topHost = Rules.hostOf(k.url); tab.title = k.title
        wireWebView(k.web, { tab })
        tabs.add(tab)
        holder.addView(k.web, holder.indexOfChild(pullBadge).coerceAtLeast(0))
        k.web.visibility = View.GONE
        k.web.keepPlaying = true
        mediaTab = tab; mediaPlaying = k.playing; mediaTitle = k.title; lastPlayingAt = k.lastPlayingAt
        mediaOwner = System.identityHashCode(this)
        lowered = true // onStart asks YouTube for its normal picture again
        PlayerBridge.control = { a -> ui.post { mediaControl(a) } }
        select(tab)
        updateTabsButton()
    }

    /** A song plays (or played a moment ago, between songs) on a song site: it may outlive this screen. */
    private fun canKeepPlaying(): Boolean {
        val t = mediaTab ?: return false
        return !t.isPrivate && t.claudeOrigin == null && PageScripts.backgroundOrigins.any { t.url.startsWith("$it/") || t.url == it } &&
            (mediaPlaying || System.currentTimeMillis() - lastPlayingAt < 15_000)
    }

    /** Pull-to-reload: a form with typed text is never thrown away without one question. */
    fun pullReload(tb: Tab) {
        if (tb.failedUrl != null) { load(tb, tb.failedUrl!!); return }
        tb.web.evaluateJavascript(PageScripts.HAS_TYPED_TEXT) { r ->
            if (r != "true") tb.web.reload()
            else showAsk(AlertDialog.Builder(this).setTitle(L("Page फिर से खोलें?", "Reload this page?"))
                .setMessage(L("इस page के form में आपका लिखा text मिट जाएगा।", "The text you typed into this page's form will be lost."))
                .setPositiveButton(L("हाँ, reload", "Yes, reload")) { _, _ -> tb.web.reload() }
                .setNegativeButton(L("नहीं", "No"), null))
        }
    }
    fun pullReloadForTest() { current?.let { pullReload(it) } }

    private val adblockJs: String by lazy { assets.open("ronin/inject_adblock.js").bufferedReader().readText() }
    private val chakshuJs: String by lazy { assets.open("ronin/chakshu.js").bufferedReader().readText() }
    private var pendingChakshu: Pair<String, Long>? = null

    /**
     * The Chakshu form finished loading in [t]: fill it with the message, then every 2 s bring the Sanchar Saathi OTP
     * from Ronin Phone once the owner pressed "Verify Mobile via OTP", and remember his mobile number for next time.
     */
    private fun fillChakshu(t: Tab) {
        val msg = t.chakshu ?: return
        val p = getSharedPreferences("ronin_chakshu", MODE_PRIVATE)
        val data = try { JSONObject(msg).put("mobile", p.getString("mobile", "")) } catch (e: Exception) { return }
        t.web.evaluateJavascript("$chakshuJs;window.__roninChakshu.start($data);1", null)
        if (t.chakshuLoop) return // a reload of the form: the running loop serves the new page too
        t.chakshuLoop = true
        val started = System.currentTimeMillis()
        var asking = false
        fun tick() {
            if (t !in tabs || !Chakshu.isForm(t.url) || System.currentTimeMillis() - started > 15 * 60_000) { t.chakshuLoop = false; return }
            t.web.evaluateJavascript("window.__roninChakshu?window.__roninChakshu.state():''") { raw ->
                val s = try { JSONObject(jsString(raw)) } catch (e: Exception) { null }
                if (s != null) {
                    val mobile = s.optString("mobile")
                    if (Regex("[1-9][0-9]{9}").matches(mobile) && p.getString("mobile", "") != mobile) p.edit().putString("mobile", mobile).apply()
                    if (s.optBoolean("needOtp") && !asking) {
                        asking = true
                        val since = s.optLong("otpSince")
                        Thread {
                            val code = Chakshu.otpFromPhone(this, since)
                            ui.post { asking = false; if (code != null) t.web.evaluateJavascript("window.__roninChakshu&&window.__roninChakshu.otp(" + JSONObject.quote(code) + ")", null) }
                        }.start()
                    }
                    if (s.optString("phase") == "preview") { t.chakshuLoop = false; return@evaluateJavascript }
                }
                ui.postDelayed({ tick() }, 2000)
            }
        }
        ui.postDelayed({ tick() }, 2000)
    }
    /** url "" = an empty tab (a page fills it: new window); lazy = the page loads when the tab is first shown. */
    fun newTab(url: String, select: Boolean = true, private: Boolean = false, lazy: Boolean = false, claude: String? = null): Tab {
        lateinit var tab: Tab
        val w = makeWebView({ tab }, private, claude)
        tab = Tab(w)
        tab.isPrivate = private
        tab.claudeOrigin = claude
        tabs.add(tab)
        holder.addView(w, holder.indexOfChild(pullBadge).coerceAtLeast(0))
        w.visibility = View.GONE
        if (lazy && url.isNotEmpty()) { tab.pending = url; tab.url = url; tab.topHost = Rules.hostOf(url) }
        else if (url.isNotEmpty()) load(tab, url)
        if (select || (current == null && !lazy)) select(tab)
        updateTabsButton()
        if (!restoring) saveTabs()
        return tab
    }

    private fun select(t: Tab) {
        if (::findBar.isInitialized && findBar.visibility == View.VISIBLE && current != t) closeFind()
        // the Claude tab rests as soon as another tab is in front (its page then closes its live stream)
        current?.let { if (it != t && it.claudeOrigin != null) it.web.onPause() }
        if (t.claudeOrigin != null) t.web.onResume()
        current?.web?.visibility = View.GONE
        current = t
        t.web.visibility = View.VISIBLE
        t.pending?.let { t.pending = null; load(t, it) }
        if (!address.hasFocus()) address.setText(display(t.url))
        updateTabsButton()
    }

    private fun closeTab(t: Tab, undo: Boolean = true) {
        val i = tabs.indexOf(t)
        tabs.remove(t)
        holder.removeView(t.web)
        if (t == mediaTab) { mediaTab = null; mediaPlaying = false; KeptPlayer.wantKeep = false; MediaPlaybackService.stop(this) }
        if (t.isPrivate) { t.web.clearCache(true); t.web.clearHistory() }
        t.web.destroy()
        if (t.isPrivate && tabs.none { it.isPrivate }) wipePrivate()
        // only the tab that was being read stops the Data export (another tab closing used to let a second one start)
        if (exporting && t == exportTab) { exporting = false; readBar.visibility = View.GONE }
        // a closed tab can come back with one tap for a few seconds (not private ones: nothing of them is kept)
        if (undo && !t.isPrivate && t.claudeOrigin == null && t.url.startsWith("http")) { // the Claude tab comes back from its own button
            closedUrl = t.url
            undoBar.visibility = View.VISIBLE
            ui.removeCallbacks(hideUndo); ui.postDelayed(hideUndo, 6000)
        }
        val rest = browsingTabs()
        if (rest.isEmpty()) newTab(HOME) else if (current == t) select(rest[(minOf(i, rest.size) - 1).coerceAtLeast(0)])
        saveTabs()
        updateTabsButton()
    }

    private val hideUndo = Runnable { undoBar.visibility = View.GONE }

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
                text = (if (t.isPrivate) "🕶 " else if (t.claudeOrigin != null) "🤖 " else "") + t.title.ifBlank { if (t.url == HOME || t.url.isEmpty()) "New tab" else display(t.url) }
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
    fun newTabButtonForTest(): View = newTabButton
    fun goHomeForTest() { current?.let { load(it, HOME) } }
    fun exportDataForTest() { current?.let { exportData(it) } }

    private fun showTabs() {
        val list = browsingTabs()
        Sheet(this, "Tabs (${list.size})")
            .bigs(listOf(Sheet.Big("＋", L("नया tab", "New tab"), "t-new", "#1D4ED8") { newTab(HOME) },
                Sheet.Big("✕", L("यह tab बंद", "Close this tab"), "t-close", "#B91C1C") { current?.let { closeTab(it) } }))
            .group("", list.map { tb ->
                Sheet.Row(if (tb.isPrivate) "🕶" else "🌐", tb.title.ifBlank { if (tb.url == HOME || tb.url.isEmpty()) L("नया tab", "New tab") else display(tb.url) },
                    if (tb == current) "✔" else "", if (tb == current) "#1D4ED8" else "#334155", "t-tab") { select(tb); updateTabsButton() }
            }).show()
    }

    // ---- going places ---------------------------------------------------------------

    /** Typed or spoken text: an address opens, anything else is searched. */
    fun go(text: String) {
        hideKeyboard()
        val t = current ?: newTab(HOME)
        val typed = text.trim()
        // a site he already chose to open without https ("Open anyway") stays http
        if (typed.startsWith("http://", ignoreCase = true) && httpOk(Rules.hostOf(typed))) { load(t, typed); return }
        val url = Omnibox.resolve(text) ?: return
        load(t, url, typed = true)
        // https first; if the site has no https, Ronin's error page offers the plain address (never for banks)
        if (url.startsWith("https://") && !typed.startsWith("https://", ignoreCase = true) && !url.startsWith("https://www.google.com/search?"))
            t.httpFallback = "http://" + url.substring(8)
    }

    /** An adult / gambling site opens in a private tab (the empty tab it was typed in goes away). */
    private fun openPrivate(url: String, from: Tab?) {
        val blank = from != null && !from.isPrivate && (from.url.isEmpty() || from.url == HOME)
        newTab(url, select = true, private = true)
        if (blank && from != null && from in tabs) closeTab(from)
        Toast.makeText(this, L("🕶 Private tab: इसकी history और cookies save नहीं होंगी", "🕶 Private tab: its history and cookies are not saved"), Toast.LENGTH_SHORT).show()
    }

    /** typed = he typed / spoke this address himself (only then may a pairing link name a NEW Claude server). */
    fun load(t: Tab, url0: String, typed: Boolean = false) {
        // the Claude tab shows only Claude's own pages: anything else opens in a normal tab with all checks
        if (t.claudeOrigin != null && !isUnderClaude(url0, t)) { newTab(url0); return }
        t.pending = null; t.httpFallback = null; t.failedUrl = null; t.sslBad = null
        // Family Filter: SafeSearch also for searches typed or spoken in the address bar (not only for links)
        var url = (if (Family.adultBlocked(this)) Family.safeSearch(url0) else null) ?: url0
        // Claude's browser: its pairing link and its pages always open in the Claude tab
        Nav.claudePairingHost(url)?.let { h ->
            if ("https://$h" == claudeOrigin()) { if (t.claudeOrigin == null) { openClaude(url); return } }
            else if (typed) { pairClaude(h, url); return } // a NEW server only from his own typing (or another app's link)
        }
        if (t.claudeOrigin == null && isClaudeLink(url)) { openClaude(url); return }
        // https first for every address (links, bookmarks, history too), unless he chose "Open anyway" for that site
        if (url.startsWith("http://", ignoreCase = true)) {
            val h = Rules.hostOf(url)
            if (!Nav.isLocalHost(h) && !httpOk(h)) { t.httpFallback = url; url = "https://" + url.substring(7) }
        }
        if (Sensitive.site(url) && Family.adultBlocked(this)) { blockAdult(t, url); return }
        if (!t.isPrivate && Sensitive.site(url)) { openPrivate(url, t); return }
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
        if (!neutralUa(url)) t.web.settings.userAgentString = uaForTab(t, url)
        val fallback = t.httpFallback
        t.url = url
        t.topHost = Rules.hostOf(url)
        siteSettings(t.web, url)
        t.web.loadUrl(url)
        t.httpFallback = fallback
    }

    /** Steps back to the last page that is neither the failed address nor Ronin's error page (0 = none). */
    private fun backPastError(t: Tab): Int {
        val list = t.web.copyBackForwardList()
        for (i in list.currentIndex - 1 downTo 0) {
            val u = list.getItemAtIndex(i)?.url ?: continue
            if (u != t.failedUrl && !u.startsWith(ERROR_PAGE)) return i - list.currentIndex
        }
        return 0
    }

    /** He chose "Open anyway" for this site (now, or before: remembered). */
    /** He chose "Open anyway" for this site in THIS session (https is still tried first for sites remembered from before). */
    private fun httpOk(host: String) = host in httpAllowed

    private fun isUnderClaude(url: String, t: Tab): Boolean {
        val o = t.claudeOrigin ?: return false
        return o.isNotEmpty() && url.startsWith("$o/")
    }

    /** Per site: text size (A− / A+) and whether sound / video may start without a tap (only the song sites). */
    private fun siteSettings(w: WebView, url: String) {
        if (!url.startsWith("http")) return
        w.settings.textZoom = app.store.textZoom(Rules.hostOf(url))
        w.settings.mediaPlaybackRequiresUserGesture = PageScripts.backgroundOrigins.none { url.startsWith(it) }
    }

    private fun uaForTab(t: Tab, url: String) = if (t.desktop) desktopUa else uaFor(url)

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
            if (t.claudeOrigin != null) return null // his own server: no ad-block, no danger list in the Claude tab
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
            if (scheme == "http" && req.isForMainFrame && !isLocal(req.url.host) && !httpOk(req.url.host ?: "")) {
                tab().httpFallback = url // if the site has no https, the error page offers this address
                view.loadUrl("https" + url.substring(4)) // secure version first (HTTPS-only)
                return true
            }
            if (url == BACK_TO_SAFETY) { leaveDangerPage(tab()); return true }
            if (url.startsWith(RELOAD_LINK) || url.startsWith(HTTP_LINK)) {
                val t = tab()
                if (req.url.getQueryParameter("k") != pageKey) return true
                val fb = t.httpFallback
                if (url.startsWith(HTTP_LINK) && fb != null) {
                    val fh = Rules.hostOf(fb)
                    httpAllowed.add(fh)
                    // remembered for good (his own servers ask only once); removable in Remembered sites. Never banks / payment / sign-in.
                    if (app.rules.classify(fb, app.store.userBanks).let { it != Kind.BANK && it != Kind.PAYMENT && it != Kind.AUTH }) app.store.httpSites = app.store.httpSites + fh
                    load(t, fb)
                    Toast.makeText(this@MainActivity, L("⚠ यह साइट सुरक्षित (https) नहीं है: password, OTP या card नंबर न डालें", "⚠ This site is not secure (no https): never type a password, OTP or card number"), Toast.LENGTH_LONG).show()
                } else if (t.claudeOrigin != null) {
                    // the Claude tab: the live view again (paired), else the "not paired" page
                    val v = app.store.claudeViewUrl
                    if (v.isEmpty() || t.claudeOrigin != claudeOrigin()) openClaude() else load(t, t.failedUrl ?: v)
                }
                else load(t, t.failedUrl ?: t.url)
                return true
            }
            if (req.isForMainFrame) {
                val t = tab()
                // only HIS saved Claude server goes to the Claude tab; an unknown /k/ link on a page is a normal link
                if (t.claudeOrigin == null && isClaudeLink(url)) { openClaude(url); return true }
                // a link to another site from Claude's view opens in a normal tab (with all of Ronin's checks)
                if (t.claudeOrigin != null && !isUnderClaude(url, t) && (scheme == "http" || scheme == "https")) { newTab(url); return true }
            }
            if (req.isForMainFrame && Sensitive.site(url) && Family.adultBlocked(this@MainActivity)) { blockAdult(tab(), url); return true }
            if (req.isForMainFrame && Family.adultBlocked(this@MainActivity) && Family.safeSearch(url) != null) { view.loadUrl(Family.safeSearch(url)!!); return true }
            if (req.isForMainFrame && !tab().isPrivate && Sensitive.site(url)) { openPrivate(url, tab()); return true }
            if (url.startsWith("https://ronin.newtab/")) { quickLinkChange(tab(), req.url); return true }
            if (url.startsWith("https://ronin.data/save")) {
                val t = tab()
                if (req.url.getQueryParameter("k") == toolsKey && t.isTools) saveToolFile(t)
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
            if (req.isForMainFrame && (scheme == "http" || scheme == "https") && !neutralUa(url) && view.settings.userAgentString != uaForTab(tab(), url)) {
                // Google Sheets / Docs open as the full (computer) version, other sites as normal
                view.settings.userAgentString = uaForTab(tab(), url)
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
            if (scheme == "javascript" || scheme == "file") return true
            if (scheme != "http" && scheme != "https") {
                // another app (UPI, phone, Play Store...): only after a tap on the page itself; else Ronin asks first
                when (Nav.outsideAction(req.isForMainFrame, req.hasGesture())) {
                    Nav.OPEN -> openOutside(url, view)
                    Nav.ASK -> askOutside(url, view)
                }
                return true
            }
            if (req.isForMainFrame) tab().httpFallback = null
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
            // a page opened in a new window comes here without the usual checks
            if (url.startsWith("http") && Sensitive.site(url) && Family.adultBlocked(this@MainActivity)) { view.stopLoading(); blockAdult(t, url); return }
            if (!url.startsWith(TOOLS)) t.isTools = false
            if (url.startsWith("http") && !url.startsWith("https://ronin.home")) { t.failedUrl = null; siteSettings(view, url) }
            view.setBackgroundColor(if (url.startsWith("http") && !url.startsWith("https://ronin.home")) Color.WHITE else homeBg())
            if (url.startsWith("http") && !url.startsWith("https://ronin.home") && !neutralUa(url) && view.settings.userAgentString != uaForTab(t, url) && t.uaFixedFor != url) {
                t.uaFixedFor = url // once per address: never a reload loop
                // Back / Reload into a site that needs the other version: load it again with the right one
                view.settings.userAgentString = uaForTab(t, url)
                view.stopLoading(); view.loadUrl(url)
                return
            }
            if (!url.startsWith("https://ronin.home")) { t.url = url; t.topHost = Rules.hostOf(url) }
            if (t == current && !address.hasFocus()) address.setText(display(if (url.startsWith(ERROR_PAGE)) t.failedUrl ?: url else url))
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
            if (url.startsWith("http") && !url.startsWith("https://ronin.home") && !t.title.startsWith("⛔") && !t.isPrivate && t.claudeOrigin == null && !Sensitive.site(url)) app.store.addHistory(t.title, url)
            if (Chakshu.isForm(url)) {
                pendingChakshu?.let { (m, at) -> if (System.currentTimeMillis() - at < 5 * 60_000) t.chakshu = m }
                pendingChakshu = null
                fillChakshu(t)
            }
        }

        /** Android ended the page engine (low memory, many tabs): only the pages reload, Ronin itself stays. */
        override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
            recoverTab(tab(), view)
            return true
        }

        /** No internet, no such site, or no https: Ronin's own page with a big Reload button. */
        override fun onReceivedError(view: WebView, req: WebResourceRequest, err: android.webkit.WebResourceError) {
            if (!req.isForMainFrame) return
            val url = req.url.toString()
            if (!url.startsWith("http") || url.startsWith("https://ronin.")) return
            mainFrameFailed(tab(), url, err.errorCode, err.description?.toString() ?: "")
        }

        /** Claude's server answers but its browser part is off (5xx), or this tablet is not paired yet (404). */
        override fun onReceivedHttpError(view: WebView, req: WebResourceRequest, res: WebResourceResponse) {
            val t = tab()
            if (!req.isForMainFrame || t.claudeOrigin == null) return
            if (res.statusCode >= 500 || res.statusCode == 404) showClaudeDown(t, req.url.toString(), notPaired = res.statusCode == 404)
        }

        override fun onReceivedSslError(view: WebView, handler: android.webkit.SslErrorHandler, error: android.net.http.SslError) {
            handler.cancel() // never past a broken certificate
            val t = tab(); val u = error.url ?: return
            if (Rules.hostOf(u) == Rules.hostOf(t.url)) t.sslBad = Rules.hostOf(u)
            val fb = t.httpFallback ?: return
            if (Rules.hostOf(u) == Rules.hostOf(fb) && Rules.hostOf(u) == Rules.hostOf(t.url)) view.post { certFailed(t, u) }
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            val t = tab()
            if (url.startsWith("http") && !url.startsWith("https://ronin.home")) { t.url = url; t.topHost = Rules.hostOf(url) }
            if (t == current && !address.hasFocus()) address.setText(display(if (url.startsWith(ERROR_PAGE)) t.failedUrl ?: url else url))
        }
    }

    private fun isLocal(host: String?) = host == null || Nav.isLocalHost(host)

    private var recoveredAt = 0L

    /** A tab whose page engine was ended gets a fresh one on the same address (other tabs load when shown). */
    private fun recoverTab(t: Tab, old: WebView) {
        val i = holder.indexOfChild(old)
        holder.removeView(old)
        old.destroy()
        if (t !in tabs || t.web !== old) return
        if (t == mediaTab) { mediaTab = null; mediaPlaying = false; KeptPlayer.wantKeep = false; MediaPlaybackService.stop(this) }
        val w = makeWebView({ t }, t.isPrivate, t.claudeOrigin)
        t.web = w
        holder.addView(w, if (i >= 0) i else holder.indexOfChild(pullBadge).coerceAtLeast(0))
        w.visibility = if (t == current) View.VISIBLE else View.GONE
        val url = if (t.isTools) TOOLS else t.url.ifEmpty { HOME }
        val again = System.currentTimeMillis() - t.crashedAt < 30_000
        t.crashedAt = System.currentTimeMillis()
        // the same page broke the engine twice in 30 s: no endless reload, Ronin's page with a Reload button instead
        if (again && url.startsWith("http")) { t.url = url; showErrorPage(t, url, L("यह page बार-बार बंद हो रहा है", "This page keeps closing"), offerHttp = false) }
        else if (t == current) load(t, url) else { t.pending = url; t.url = url }
        if (System.currentTimeMillis() - recoveredAt > 5000) {
            recoveredAt = System.currentTimeMillis()
            Toast.makeText(this, L("Memory कम थी: page फिर से खोला गया", "Memory was low: the page was opened again"), Toast.LENGTH_LONG).show()
        }
    }

    /**
     * The page did not open. A site he chose "Open anyway" for before: https is always tried first, and only when https
     * cannot connect does Ronin open the plain address by itself (no tap). After a certificate error: never.
     */
    private fun mainFrameFailed(t: Tab, url: String, code: Int, why: String) {
        if (t.claudeOrigin != null) { showClaudeDown(t, url, notPaired = false); return }
        if (code !in setOf(WebViewClient.ERROR_HOST_LOOKUP, WebViewClient.ERROR_CONNECT, WebViewClient.ERROR_TIMEOUT, WebViewClient.ERROR_FAILED_SSL_HANDSHAKE, WebViewClient.ERROR_IO)) return
        val host = Rules.hostOf(url)
        if (t.sslBad == host) { showErrorPage(t, url, "SSL", offerHttp = false); return }
        val fb = t.httpFallback
        if (fb != null && Rules.hostOf(fb) == host && host in app.store.httpSites && url.startsWith("https://") &&
            code in setOf(WebViewClient.ERROR_CONNECT, WebViewClient.ERROR_TIMEOUT, WebViewClient.ERROR_FAILED_SSL_HANDSHAKE) &&
            app.rules.classify(fb, app.store.userBanks).let { it != Kind.BANK && it != Kind.PAYMENT && it != Kind.AUTH }) {
            httpAllowed.add(host) // for this session; next time https is tried first again
            load(t, fb)
            return
        }
        // no such site (name not found): plain http would not help either, so no "Open anyway" then
        showErrorPage(t, url, why, offerHttp = code != WebViewClient.ERROR_HOST_LOOKUP)
    }

    /** A certificate that is not valid: only the warning, never "Open anyway", nothing remembered. */
    private fun certFailed(t: Tab, url: String) {
        t.sslBad = Rules.hostOf(url)
        showErrorPage(t, url, "SSL", offerHttp = false)
    }

    /** Ronin's error page: Reload (big), and "Open anyway" when the site has no https (never for a bank). */
    private fun showErrorPage(t: Tab, url: String, why: String, offerHttp: Boolean = true) {
        val fb = t.httpFallback?.takeIf { offerHttp && Rules.hostOf(it) == Rules.hostOf(url) && app.rules.classify(url, app.store.userBanks).let { k -> k != Kind.BANK && k != Kind.PAYMENT && k != Kind.AUTH } }
        t.failedUrl = url
        val h = android.text.TextUtils.htmlEncode(Rules.hostOf(url))
        val ssl = if (why != "SSL") "" else "<p id=cert class=m style=\"color:#fca5a5\">" + L("⚠ इस साइट का सुरक्षा certificate सही नहीं है। यह पुराना पता हो सकता है, या कोई बीच में बात सुन रहा हो। यहाँ password, OTP या card नंबर बिल्कुल न डालें।", "⚠ This site's security certificate is not valid. It may be an old address, or someone may be listening in. Never type a password, OTP or card number here.") + "</p>"
        val http = if (fb == null || why == "SSL") ssl else "<p class=m>" + L("यह साइट https (सुरक्षित तरीका) नहीं देती।", "This site does not offer https (the secure way).") +
            "</p><a id=http class=g href=\"$HTTP_LINK?k=$pageKey\">" + L("फिर भी खोलें (असुरक्षित)", "Open anyway (not secure)") + "</a>"
        val html = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${L("Page नहीं खुला", "Page did not open")}</title>
<style>body{margin:0;background:#14171f;color:#e8eaf0;font:17px sans-serif;display:flex;min-height:100vh;align-items:center;justify-content:center;text-align:center}
main{max-width:520px;padding:24px}code{display:block;background:#1c2029;padding:10px;border-radius:8px;word-break:break-all;margin:12px 0}.m{color:#9aa3b2;font-size:15px}
a{display:block;margin:16px auto 0;max-width:340px;padding:18px;border-radius:14px;background:#2563eb;color:#fff;text-decoration:none;font-weight:bold;font-size:19px}a.g{background:#374151;font-size:16px;font-weight:normal}</style></head>
<body><main><div style="font-size:52px">📡</div><h2>${L("Page नहीं खुला", "This page did not open")}</h2><code>$h</code>
<p class=m>${L("Internet चालू है? फिर Reload दबाएँ या page को ऊपर से नीचे खींचें।", "Is the internet on? Press Reload, or pull the page down.")}</p>
<a id=reload href="$RELOAD_LINK?k=$pageKey">⟳ ${L("फिर से खोलें", "Reload")}</a>$http<p class=m style="font-size:12px;margin-top:22px">${android.text.TextUtils.htmlEncode(why.take(80))}</p></main></body></html>"""
        t.web.loadDataWithBaseURL(ERROR_PAGE, html, "text/html", "utf-8", ERROR_PAGE) // its history entry is known: Back skips it
    }

    // ---- 🤖 Claude: Claude's own browser on his server, seen live in one tab ------------------------------

    /** "https://host" of his paired Claude server, or "" before the first pairing. */
    private fun claudeOrigin() = app.store.claudeViewUrl.let { if (it.isEmpty()) "" else "https://" + Rules.hostOf(it) }

    /** Opens (or shows) the one Claude tab; url = a page of Claude's host (pairing link), else the saved live view. */
    fun openClaude(url: String? = null) {
        val view = app.store.claudeViewUrl
        val origin = claudeOrigin()
        var t = tabs.firstOrNull { it.claudeOrigin != null }
        if (t != null && t.claudeOrigin != origin) { closeTab(t, undo = false); t = null } // the server moved
        if (t == null) {
            t = newTab("", select = true, claude = origin)
            t.title = "Claude"
            if (view.isEmpty()) showClaudeDown(t, "", notPaired = true) else load(t, url ?: view)
        } else {
            select(t)
            if (view.isEmpty()) showClaudeDown(t, "", notPaired = true)
            else if (url != null || t.failedUrl != null || !t.url.startsWith("$origin/")) load(t, url ?: t.failedUrl ?: view)
        }
        updateTabsButton()
    }

    /** A pairing link of Claude's browser: its host becomes "Claude" (a new host only after one question). */
    private fun pairClaude(host: String, url: String) {
        if ("https://$host" == claudeOrigin()) { openClaude(url); return }
        showAsk(AlertDialog.Builder(this).setTitle(L("यह Claude का browser है?", "Is this Claude's browser?"))
            .setMessage(L("$host\n\n🤖 Claude बटन अब यही खोलेगा। सिर्फ़ तभी हाँ कहें जब यह link आपके अपने Telegram से आया हो।", "$host\n\nThe 🤖 Claude button will open this from now on. Say yes only if this link came from your own Telegram."))
            .setPositiveButton(L("हाँ", "Yes")) { _, _ -> app.store.claudeViewUrl = "https://$host/view"; openClaude(url) }
            .setNegativeButton(L("नहीं", "No"), null))
    }

    /** A page of HIS saved Claude server (never of another host). */
    private fun isClaudeLink(u: String) = claudeOrigin().isNotEmpty() && u.startsWith(claudeOrigin() + "/")

    /** A link from another app (Telegram...): Claude's links go to the Claude tab, everything else to a new tab. */
    private fun openFromOutside(u: String) {
        val h = Nav.claudePairingHost(u)
        when {
            h != null -> pairClaude(h, u)
            u.startsWith(claudeOrigin() + "/") -> openClaude(u)
            else -> newTab(u)
        }
    }

    private fun clipText(): String = try {
        (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(this)?.toString()?.take(10000) ?: ""
    } catch (e: Exception) { "" }

    /** Claude's server is off / unreachable, or this device is not paired yet: one simple page with Reload. */
    private fun showClaudeDown(t: Tab, url: String, notPaired: Boolean) {
        t.failedUrl = (if (url.contains("/k/") || url.isEmpty()) app.store.claudeViewUrl else url).ifEmpty { null } // a pairing key works only once
        val why = if (notPaired) L("यह tablet अभी जुड़ा नहीं है: Telegram में आया 📺 live-view link Ronin में एक बार खोलें।", "This device is not paired yet: open the 📺 live-view link from Telegram in Ronin once.")
            else L("Server पर Claude का browser बंद है या पहुँच से बाहर है। Claude से काम कहने पर यह अपने-आप चालू होता है।", "Claude's browser on the server is off or cannot be reached. It starts by itself when you ask Claude for a task.")
        val html = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Claude</title>
<style>body{margin:0;background:#14171f;color:#e8eaf0;font:17px sans-serif;display:flex;min-height:100vh;align-items:center;justify-content:center;text-align:center}
main{max-width:520px;padding:24px}.m{color:#9aa3b2;font-size:16px;line-height:1.5}
a{display:block;margin:20px auto 0;max-width:340px;padding:18px;border-radius:14px;background:#c2410c;color:#fff;text-decoration:none;font-weight:bold;font-size:19px}</style></head>
<body><main><div style="font-size:56px">🤖</div><h2>${L("Claude का browser अभी चालू नहीं", "Claude's browser is not on right now")}</h2>
<p class=m>${android.text.TextUtils.htmlEncode(why)}</p><a id=reload href="$RELOAD_LINK?k=$pageKey">⟳ ${L("फिर से खोलें", "Reload")}</a></main></body></html>"""
        t.web.loadDataWithBaseURL(ERROR_PAGE, html, "text/html", "utf-8", ERROR_PAGE) // its history entry is known: Back skips it
    }

    fun claudeUrlForTest(): String? = tabs.firstOrNull { it.claudeOrigin != null }?.url
    fun claudeIsCurrentForTest() = current?.claudeOrigin != null
    fun timersPausedForTest() = timersPaused
    fun mediaTabIsClaudeForTest() = mediaTab?.claudeOrigin != null
    fun pageLinkForTest(url: String) { current?.let { it.web.evaluateJavascript("location.href=" + JSONObject.quote(url), null) } }
    fun loadClaudeHtmlForTest(html: String) {
        val origin = claudeOrigin()
        val t = tabs.firstOrNull { it.claudeOrigin == origin } ?: newTab("", select = true, claude = origin)
        select(t)
        t.url = app.store.claudeViewUrl
        t.web.loadDataWithBaseURL(app.store.claudeViewUrl, html, "text/html", "utf-8", null)
    }
    fun historyForTest(): String = current?.web?.copyBackForwardList()?.let { l -> (0 until l.size).joinToString(" | ") { i -> (if (i == l.currentIndex) "*" else "") + (l.getItemAtIndex(i)?.url ?: "").take(60) } } ?: ""
    /** Like a real page: its history entry carries its own address (loadHtmlForTest's do not). */
    fun currentWebForTest(): WebView? = current?.web
    fun loadPageForTest(html: String, baseUrl: String) {
        val t = current ?: newTab(HOME)
        t.url = baseUrl; t.topHost = Rules.hostOf(baseUrl)
        t.web.loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", baseUrl)
    }
    fun loadForTest(url: String) { current?.let { load(it, url) } }

    private fun askOutside(url: String, view: WebView) {
        val shown = url.substringBefore('#').take(70)
        showAsk(AlertDialog.Builder(this).setTitle(L("दूसरा app खोलें?", "Open another app?"))
            .setMessage(L("यह page एक app खोलना चाहता है:\n$shown\n\nअगर आपने खुद नहीं दबाया, तो \"नहीं\" चुनें।", "This page wants to open an app:\n$shown\n\nIf you did not tap anything, choose \"No\"."))
            .setPositiveButton(L("हाँ, खोलें", "Yes, open")) { _, _ -> openOutside(url, view) }
            .setNegativeButton(L("नहीं", "No"), null))
    }

    /** Shows a question, never on a closed screen; the tests can find the last one. */
    private fun showAsk(b: AlertDialog.Builder): AlertDialog? {
        if (isFinishing || isDestroyed) return null
        if (lastAsk?.isShowing == true) return null // one question at a time (a page cannot pile them up)
        return b.show().also { lastAsk = it }
    }
    fun lastAskForTest(): AlertDialog? = lastAsk

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

        /** Camera / microphone: video call sites and sites he allowed before get them, other sites ask once. */
        override fun onPermissionRequest(req: PermissionRequest) {
            ui.post {
                val host = req.origin.host ?: ""
                val wanted = req.resources.filter { it == PermissionRequest.RESOURCE_AUDIO_CAPTURE || it == PermissionRequest.RESOURCE_VIDEO_CAPTURE }
                if (wanted.isEmpty() || isFinishing) { req.deny(); return@post }
                // only what the site asked for (a voice site does not get the camera question)
                val perms = wanted.map { if (it == PermissionRequest.RESOURCE_AUDIO_CAPTURE) Manifest.permission.RECORD_AUDIO else Manifest.permission.CAMERA }.toTypedArray()
                val call = app.rules.classify("https://$host/") == Kind.CALL
                val ask = {
                    pendingPermission?.deny() // an older question nobody answered
                    pendingPermission = req
                    askMedia.launch(perms)
                }
                val what = if (wanted.size == 2) L("camera और microphone", "camera and microphone") else if (perms[0] == Manifest.permission.CAMERA) "camera" else "microphone"
                // remembered per site AND per device: "microphone yes" never means "camera yes"
                val keys = wanted.map { host + "|" + (if (it == PermissionRequest.RESOURCE_AUDIO_CAPTURE) "microphone" else "camera") }
                if (call || app.store.mediaSites.containsAll(keys)) ask() else showAsk(AlertDialog.Builder(this@MainActivity)
                    .setMessage(L("$host आपका $what इस्तेमाल करना चाहता है।\n(हाँ कहने पर Ronin इस साइट के लिए याद रखेगा)", "$host wants to use your $what.\n(If you allow it, Ronin remembers this site)"))
                    .setPositiveButton(L("हाँ", "Allow")) { _, _ -> app.store.mediaSites = app.store.mediaSites + keys; ask() }
                    .setNegativeButton(L("नहीं", "Block")) { _, _ -> req.deny() }
                    .setOnCancelListener { req.deny() }) // Back / a tap outside = no
                    ?: req.deny()
            }
        }

        /** A link or button that opens a new window (receipt, print page): a new tab, the form page stays. */
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
            val from = tab()
            if (!isUserGesture || from.isPrivate) return false // pop-ups nobody tapped stay closed
            val tr = resultMsg.obj as? WebView.WebViewTransport ?: return false
            val t = newTab("", select = true)
            tr.webView = t.web
            resultMsg.sendToTarget()
            Toast.makeText(this@MainActivity, L("नए tab में खुला (पुराना tab भी खुला है)", "Opened in a new tab (the old tab is still open)"), Toast.LENGTH_SHORT).show()
            return true
        }

        override fun onCloseWindow(window: WebView) {
            val t = tab()
            if (t in tabs && t.web === window) closeTab(t, undo = true)
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
    private fun browsingTabs() = tabs.filter { !it.isTools }

    /** 3.12 (once): Safe Social had switched on YouTube's Restricted mode cookie (switched off again); the removed
     *  Messages / Calls / SOS / chat log leave nothing behind (their files, settings and notification channels). */
    private fun upgradeTo312() {
        val p = Family.prefs(this)
        if (p.getBoolean("cleaned312", false)) return
        youtubeRestricted(false); android.webkit.CookieManager.getInstance().flush()
        app.store.social = ""
        // 3.13: Messages + Calls are back - their saved SMS, settings and channels stay; only the chat log and SOS go
        for (f in listOf("chatlog.json", "chatlog.tmp")) java.io.File(filesDir, f).delete()
        try { deleteSharedPreferences("ronin_sos") } catch (e: Exception) { }
        if (Build.VERSION.SDK_INT >= 26) getSystemService(android.app.NotificationManager::class.java)?.deleteNotificationChannel("ronin_sos")
        p.edit().putBoolean("cleaned312", true).apply()
    }

    private fun youtubeRestricted(on: Boolean) {
        val cm = android.webkit.CookieManager.getInstance()
        for (h in listOf("https://www.youtube.com", "https://m.youtube.com")) cm.setCookie(h, "PREF=f2=" + (if (on) "8000000" else "0") + "; domain=.youtube.com; path=/; max-age=31536000")
    }

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
        val t = tabs.firstOrNull { it.isTools }
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
    fun tabUrlsForTest(): List<String> = browsingTabs().map { it.url }
    fun currentUrlForTest(): String = current?.url ?: ""
    fun closeCurrentTabForTest() { current?.let { closeTab(it) } }
    fun undoBarForTest(): View = undoBar
    fun suggestBoxForTest(): LinearLayout = suggestBox
    fun findBarForTest(): View = findBar
    fun crashPageForTest() { current?.web?.loadUrl("chrome://crash") }
    fun failForTest(https: String, code: Int) { current?.let { it.url = https; it.httpFallback = "http://" + https.removePrefix("https://"); mainFrameFailed(it, https, code, "test") } }
    fun certFailForTest(https: String) { current?.let { it.url = https; it.httpFallback = "http://" + https.removePrefix("https://"); certFailed(it, https) } }
    fun httpErrorPageForTest(https: String) { current?.let { it.url = https; it.httpFallback = "http://" + https.removePrefix("https://"); showErrorPage(it, https, "test") } }
    fun safeRecreateForTest(change: () -> Unit) = safeRecreate(change)
    fun closeTabForTest(url: String) { tabs.firstOrNull { it.url == url }?.let { closeTab(it) } }
    fun typeForTest(text: String) { address.requestFocus(); address.setText(text) }
    fun currentIsPrivateForTest() = current?.isPrivate == true
    fun historyHasForTest(part: String) = app.store.history.any { it.url.contains(part) }

    /** A file of the tools page, from inside the app (only for a tab Ronin opened the tools in). */
    private fun toolsFile(t: Tab, path: String): WebResourceResponse {
        val name = path.removePrefix("/tools/").ifEmpty { "index.html" }
        val ok = t.isTools && !name.contains("..") && Regex("[A-Za-z0-9_./-]+").matches(name)
        val mime = when {
            name.endsWith(".js") || name.endsWith(".mjs") -> "text/javascript"
            name.endsWith(".html") -> "text/html"
            else -> "text/plain"
        }
        return try {
            if (!ok) throw java.io.FileNotFoundException(name)
            // the scam-check rules and link lists for "Is this a scam?" (made here, from Ronin's own rules)
            if (name == "rules.js") return WebResourceResponse(mime, "utf-8", 200, "OK", mapOf("Cache-Control" to "no-store"),
                ByteArrayInputStream(("window.__roninScamRules=" + app.rules.scamRulesJson + ";window.__roninLinkLists=" + app.rules.safeLinks.json + ";").toByteArray()))
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
        if (isFinishing || isDestroyed) return
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
                    if (isFinishing || isDestroyed) return@runOnUiThread // he left meanwhile: the files are saved, no dialog
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

    private fun onDownload(t: Tab, url: String, ua: String, cd: String?, mime: String?) {
        val name = Nav.nameFromDisposition(cd).ifEmpty { if (url.startsWith("http")) URLUtil.guessFileName(url, cd, mime) else "" }
        val host = Rules.hostOf(url)
        val isApp = name.lowercase().endsWith(".apk") || mime == "application/vnd.android.package-archive"
        val go = {
            when {
                url.startsWith("data:") -> if (name.isNotEmpty()) saveDataUrl(url, name) else {
                    // the name the page gave the file (<a download="...">)
                    t.web.evaluateJavascript("(function(u){var a=[].filter.call(document.querySelectorAll('a[download]'),function(x){return x.href===u})[0];return a?a.getAttribute('download')||'':''})(" + JSONObject.quote(url) + ")") { r -> saveDataUrl(url, jsString(r)) }
                }
                url.startsWith("blob:") -> saveBlob(t, url, name)
                url.startsWith("http") -> startDownload(url, ua, cd, mime)
                else -> Toast.makeText(this, L("यह file save नहीं हो सकती", "This file cannot be saved"), Toast.LENGTH_SHORT).show()
            }
        }
        // only Ronin's own app file (the Ronin repository) and F-Droid need no warning
        if (isApp && !Nav.trustedApk(url, t.url) && !Rules.matchAny(host, app.store.trustedSites)) {
            showAsk(AlertDialog.Builder(this).setTitle(L("अनजान साइट की app file (.apk)", "App file (.apk) from an unknown site"))
                .setMessage("$name\n$host\n\n" + L("अनजान साइट से app install करना ख़तरनाक है — इनमें अक्सर virus या पैसे चुराने वाले apps होते हैं। Apps सिर्फ़ Play Store से लें।", "Installing an app from an unknown site is dangerous: such apps often carry viruses or steal money. Get apps only from the Play Store."))
                .setPositiveButton(L("रद्द करें", "Cancel"), null)
                .setNeutralButton(L("फिर भी download करें", "Download anyway")) { _, _ -> go() })
            return
        }
        go()
    }

    /** A file the page made itself as a data: address (many government portals do this). */
    private fun saveDataUrl(url: String, name: String) {
        kotlin.concurrent.thread(name = "ronin-data-dl") {
            val d = try { Nav.decodeDataUrl(url) } catch (e: Throwable) { null }
            val file = Nav.cleanFileName(name, d?.first ?: "")
            if (Nav.isAppFile(file, d?.first ?: "")) { runOnUiThread { refuseApp() }; return@thread }
            val uri = try { if (d == null) null else saveToDownloads(file, d.first, d.second) } catch (e: Throwable) { null }
            runOnUiThread { downloadDone(file, uri, d?.first ?: "") }
        }
    }

    /** A blob: file lives only inside the page: the page reads it and hands it over in pieces. */
    private fun saveBlob(t: Tab, url: String, name: String) {
        Toast.makeText(this, L("File save हो रही है...", "Saving the file..."), Toast.LENGTH_SHORT).show()
        val q = JSONObject.quote(url)
        t.web.evaluateJavascript("(function(u,n){window.__roninDl={s:'reading'};if(!n){var a=[].filter.call(document.querySelectorAll('a[download]'),function(x){return x.href===u})[0];if(a)n=a.getAttribute('download')||''}" +
            "fetch(u).then(function(r){return r.blob()}).then(function(b){if(b.size>30*1024*1024)throw new Error('too big');var f=new FileReader();" +
            "f.onload=function(){var s=String(f.result),d=s.slice(s.indexOf(',')+1);window.__roninDl={s:'ok',m:b.type||'',f:n,d:d,n:Math.ceil(d.length/524288)}};" +
            "f.onerror=function(){window.__roninDl={s:'failed'}};f.readAsDataURL(b)}).catch(function(e){window.__roninDl={s:'failed',e:String(e)}});return 1})($q," + JSONObject.quote(name) + ")", null)
        val b64 = StringBuilder()
        fun fail() { t.web.evaluateJavascript("delete window.__roninDl", null); downloadDone(name, null, "") }
        fun part(i: Int, n: Int, file: String, mime: String) {
            if (i == n) {
                t.web.evaluateJavascript("delete window.__roninDl", null)
                kotlin.concurrent.thread(name = "ronin-blob-dl") {
                    val uri = try { saveToDownloads(file, mime, android.util.Base64.decode(b64.toString(), android.util.Base64.DEFAULT)) } catch (e: Throwable) { null } // also OutOfMemoryError
                    runOnUiThread { downloadDone(file, uri, mime) }
                }
                return
            }
            t.web.evaluateJavascript("window.__roninDl&&window.__roninDl.d?window.__roninDl.d.substr($i*524288,524288):''") { r ->
                val piece = jsString(r)
                if (piece.isEmpty()) fail() else { b64.append(piece); part(i + 1, n, file, mime) }
            }
        }
        fun poll(tries: Int) {
            if (t !in tabs) return
            t.web.evaluateJavascript("window.__roninDl?JSON.stringify({s:__roninDl.s,m:__roninDl.m,f:__roninDl.f,n:__roninDl.n}):''") { raw ->
                val o = try { JSONObject(jsString(raw)) } catch (e: Exception) { JSONObject() }
                when (o.optString("s")) {
                    "reading" -> if (tries < 240) ui.postDelayed({ poll(tries + 1) }, 500) else fail()
                    "ok" -> {
                        val mime = o.optString("m"); val file = Nav.cleanFileName(o.optString("f").ifEmpty { name }, mime)
                        // an app file made inside a page never skips the .apk warning: it is not saved at all
                        if (Nav.isAppFile(file, mime)) { t.web.evaluateJavascript("delete window.__roninDl", null); refuseApp() }
                        else part(0, o.optInt("n"), file, mime.ifEmpty { "application/octet-stream" })
                    }
                    else -> fail()
                }
            }
        }
        ui.postDelayed({ poll(0) }, 300)
    }

    private fun refuseApp() {
        if (isFinishing || isDestroyed) return
        Toast.makeText(this, L("⛔ Page के अंदर बनी app file (.apk) save नहीं होती। Apps सिर्फ़ Play Store से लें।", "⛔ An app file (.apk) made inside a page is not saved. Get apps only from the Play Store."), Toast.LENGTH_LONG).show()
    }

    private fun downloadDone(name: String, uri: Uri?, mime: String) {
        if (isFinishing || isDestroyed) return
        if (uri == null) Toast.makeText(this, L("File save नहीं हुई", "The file was not saved"), Toast.LENGTH_LONG).show()
        else savedNote(name, uri, mime.ifEmpty { "application/octet-stream" })
    }

    // ---- long press on a link or picture: open in a new tab, copy, share --------------------

    private fun linkMenu(t: Tab): Boolean {
        val h = t.web.hitTestResult
        when (h.type) {
            WebView.HitTestResult.SRC_ANCHOR_TYPE -> { showLinkMenu(t, h.extra ?: return false, null); return true }
            WebView.HitTestResult.IMAGE_TYPE -> { showLinkMenu(t, null, h.extra ?: return false); return true }
            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                val img = h.extra
                val msg = Handler(Looper.getMainLooper()) { m -> showLinkMenu(t, m.data.getString("url"), img ?: m.data.getString("src")); true }.obtainMessage()
                t.web.requestFocusNodeHref(msg)
                return true
            }
        }
        return false
    }

    private var linkSheet: android.app.Dialog? = null
    fun linkSheetForTest() = linkSheet

    fun showLinkMenu(t: Tab?, link: String?, image: String?) {
        if (isFinishing || isDestroyed) return
        val rows = ArrayList<Sheet.Row>()
        val l = link?.takeIf { it.startsWith("http") }
        val img = image?.takeIf { it.startsWith("http") || it.startsWith("data:image/") }
        if (l != null) {
            rows.add(Sheet.Row("＋", L("नए tab में खोलें", "Open in new tab"), "", "#1D4ED8", "l-newtab") {
                newTab(l, select = false, lazy = true)
                Toast.makeText(this, L("नए tab में खुला: Tabs बटन दबाएँ", "Opened in a new tab: press the Tabs button"), Toast.LENGTH_SHORT).show()
            })
            rows.add(Sheet.Row("📋", L("Link copy करें", "Copy link"), "", "#334155", "l-copy") { copyText(l) })
            rows.add(Sheet.Row("↗", L("Link भेजें (WhatsApp...)", "Share link (WhatsApp...)"), "", "#15803D", "l-share") { shareText(l) })
        }
        if (img != null) {
            if (img.startsWith("http")) rows.add(Sheet.Row("🖼", L("फ़ोटो नए tab में", "Open picture in new tab"), "", "#334155", "l-image") { newTab(img, select = false, lazy = true) })
            rows.add(Sheet.Row("⬇", L("फ़ोटो save करें", "Save picture"), "", "#334155", "l-saveimg") {
                val tb = t ?: current
                if (tb != null) onDownload(tb, img, tb.web.settings.userAgentString, null, null)
            })
        }
        if (rows.isEmpty()) return
        linkSheet = Sheet(this, (l ?: img ?: "").take(90)).group("", rows).show()
    }

    private fun copyText(text: String) {
        (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("link", text))
        Toast.makeText(this, L("Copy हो गया", "Copied"), Toast.LENGTH_SHORT).show()
    }

    private fun shareText(text: String, subject: String = "") {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        if (subject.isNotEmpty()) send.putExtra(Intent.EXTRA_SUBJECT, subject)
        try { startActivity(Intent.createChooser(send, L("भेजें", "Share"))) } catch (e: Exception) { }
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
            // nothing played for 10 minutes: that tab may rest in the background again (battery)
            mediaTab?.let { if (!mediaPlaying && System.currentTimeMillis() - lastPlayingAt > 10 * 60_000) releaseMedia() }
            if (mediaTab == null && !onScreen) return // closed and nothing to play: stop checking
            val candidates = listOfNotNull(mediaTab, current?.takeIf { it.claudeOrigin == null }).distinct() // never polls the Claude tab
            for (t in candidates) {
                t.web.evaluateJavascript(PageScripts.MEDIA_STATE) { r -> onMediaState(t, r) }
            }
            // battery: every 3 s on screen, 2 s while a song plays in the background, 5 s when it is paused
            // until a song is found: every 1 s on screen (its card shows quickly); then 3 s on screen, 2 s playing, 5 s paused
            val next = if (onScreen) (if (mediaTab == null) 1000L else 3000L) else if (mediaPlaying) 2000L else 5000L
            if (mediaTab != null || onScreen) ui.postDelayed(this, next) // closed and nothing playing: stop checking
        }
    }

    private fun onMediaState(t: Tab, raw: String?) {
        if (t.claudeOrigin != null) return // Claude's live view is never the background player
        val o = try { JSONObject(JSONArray("[$raw]").getString(0)) } catch (e: Exception) { return }
        // background play is for songs / talks: short-video feeds (Instagram, Facebook...) and silent (muted)
        // videos never keep the phone awake when Ronin is closed - they ate the battery
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
            mediaOwner = System.identityHashCode(this)
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
        KeptPlayer.wantKeep = canKeepPlaying() // the player service asks this when Ronin is swiped away
    }

    private val onScreen get() = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)

    private fun releaseMedia() {
        KeptPlayer.wantKeep = false
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
        if (a == "stop") { mediaPlaying = false; t.web.keepPlaying = false; mediaTab = null; KeptPlayer.wantKeep = false }
    }

    override fun onStop() {
        super.onStop()
        saveTabs()
        CookieManager.getInstance().flush() // Claude's pairing cookie (and every login) is on disk before Android may end Ronin
        if (mediaTab == null) ui.removeCallbacks(mediaPoll) // nothing playing: no checks every second in the background
        // tabs that do not play are paused to save battery; the playing one goes on
        for (t in tabs) if (t != mediaTab) t.web.onPause()
        // nothing plays, no export runs and no other Ronin screen is open: every page's timers rest too
        if (mediaTab == null && !exporting && app.visibleScreens == 0) { tabs.firstOrNull()?.web?.pauseTimers(); timersPaused = true }
        // a song with the screen off: YouTube's smallest picture after 20 s (a quick look at another app changes nothing)
        ui.removeCallbacks(lowerQuality); if (mediaTab != null) ui.postDelayed(lowerQuality, 20_000)
    }

    private val lowerQuality = Runnable {
        val t = mediaTab ?: return@Runnable
        if (onScreen || !Rules.hostOf(t.web.url ?: "").endsWith("youtube.com")) return@Runnable
        lowered = true
        t.web.evaluateJavascript(PageScripts.lowQuality(true), null)
    }

    override fun onResume() {
        super.onResume()
        Updater.resume(this) // "Allow from this source" was just switched on: the update installs without another tap
    }

    override fun onConfigurationChanged(c: android.content.res.Configuration) {
        super.onConfigurationChanged(c)
        // split screen / a floating window on the tablet: phone or tablet layout again
        if (::stripRow.isInitialized) stripRow.visibility = if (c.smallestScreenWidthDp >= 600) View.VISIBLE else View.GONE
        lastStripTab = null
        updateTabsButton()
    }

    override fun onStart() {
        super.onStart()
        KeptPlayer.take()?.let { adoptKept(it) } // kept a moment after this screen was made (reopen race)
        if (timersPaused || tabs.isNotEmpty()) { tabs.firstOrNull()?.web?.resumeTimers(); timersPaused = false }
        ui.removeCallbacks(lowerQuality)
        if (lowered) { lowered = false; mediaTab?.web?.evaluateJavascript(PageScripts.lowQuality(false), null) }
        for (t in tabs) t.web.onResume()
        ui.removeCallbacks(mediaPoll); ui.postDelayed(mediaPoll, 1500)
        kotlin.concurrent.thread(name = "ronin-danger") { app.loadAndRefreshThreats() }
    }

    override fun onDestroy() {
        ui.removeCallbacks(mediaPoll); ui.removeCallbacks(lowerQuality); ui.removeCallbacks(hideUndo)
        if (PlayerBridge.control != null && !isChangingConfigurations) PlayerBridge.control = null
        // Ronin swiped away / closed while a YouTube song plays: that ONE page keeps playing (KeptPlayer), all other
        // tabs are freed. Nothing playing: the player card and its wake lock go, as before.
        var keptWeb: WebView? = null
        liveScreens--
        if (!isChangingConfigurations && mediaTab != null) {
            val t = mediaTab!!
            // another browser screen already open (Ronin reopened before this one closed): stop, never an orphan page
            if (canKeepPlaying() && liveScreens == 0) {
                keptWeb = t.web
                KeptPlayer.hold(this, t.web, t.url, mediaTitle, mediaPlaying, lastPlayingAt)
            } else {
                KeptPlayer.wantKeep = false
                // the card belongs to whichever screen plays now: an older screen closing late never removes a newer one's
                if (mediaOwner == 0 || mediaOwner == System.identityHashCode(this)) { mediaOwner = 0; MediaPlaybackService.stop(this) }
            }
            mediaTab = null; mediaPlaying = false
        }
        readAloud?.shutdown()
        for (t in tabs) if (t.web !== keptWeb) t.web.destroy()
        super.onDestroy()
    }

    // ---- menu --------------------------------------------------------------------------

    /** Language: the phone's own (default), English or Hindi. The whole app follows it. */
    private fun chooseLanguage() {
        val values = arrayOf("auto", "en", "hi")
        val names = arrayOf(L("फ़ोन जैसी (अपने-आप)", "Same as the phone (automatic)"), "English", "हिंदी")
        val icons = arrayOf("📱", "A", "अ")
        Sheet(this, "🌐 Language / भाषा").group("", values.indices.map { i ->
            Sheet.Row(icons[i], names[i], if (Lang.choice(this) == values[i]) "✔" else "", "#334155", "lang-" + values[i]) { safeRecreate { Lang.set(this, values[i]) } }
        }).show()
    }

    /** The menu: slides up from the bottom (thumb), big buttons for every day, short groups for the rest. */
    private fun showMenu(@Suppress("UNUSED_PARAMETER") anchor: View) {
        val t = current
        val web = t != null && t.url.startsWith("http") && t.claudeOrigin == null // the Claude tab: no share / bookmark of its page
        val on = L("चालू", "ON"); val off = L("बंद", "OFF")
        val bigs = ArrayList<Sheet.Big>()
        bigs.add(Sheet.Big("＋", L("नया tab", "New tab"), "m-newtab", "#1D4ED8") { newTab(HOME) })
        bigs.add(Sheet.Big("G", "Google", "m-home", "#334155") { if (t == null || t.isTools) newTab(GOOGLE) else load(t, GOOGLE) })
        bigs.add(Sheet.Big("🤖", "Claude", "m-claude", "#C2410C") { openClaude() })
        if (web) {
            val marked = app.store.isBookmarked(t!!.url)
            bigs.add(Sheet.Big(if (marked) "★" else "☆", if (marked) L("Bookmark हटाएँ", "Unbookmark") else L("Bookmark", "Bookmark"), "m-bookmark", "#B45309") {
                val added = app.store.toggleBookmark(t.title, t.url)
                Toast.makeText(this, if (added) L("Bookmark बन गया", "Bookmark saved") else L("Bookmark हटा", "Bookmark removed"), Toast.LENGTH_SHORT).show()
            })
            bigs.add(Sheet.Big("🔊", L("पढ़कर सुनाएँ", "Read aloud"), "m-read", "#7C3AED") { startReading() })
            bigs.add(Sheet.Big("⟳", L("Reload", "Reload"), "m-reload", "#334155") { if (t.failedUrl != null) load(t, t.failedUrl!!) else t.web.reload() })
            if (t.web.canGoForward()) bigs.add(Sheet.Big("→", L("आगे", "Forward"), "m-forward", "#334155") { t.web.goForward() })
            bigs.add(Sheet.Big("↗", L("भेजें", "Share"), "m-share", "#15803D") { shareText(t.url, t.title) })
            bigs.add(Sheet.Big("🔍", L("Page में खोजें", "Find in page"), "m-find", "#334155") { openFind() })
            bigs.add(Sheet.Big("🖥", if (t.desktop) L("Mobile site", "Mobile site") else L("Desktop site", "Desktop site"), "m-desktop", if (t.desktop) "#1D4ED8" else "#334155") {
                t.desktop = !t.desktop
                t.web.settings.userAgentString = uaForTab(t, t.url)
                t.web.reload()
            })
            // text size for this site (remembered); the menu stays open so A+ can be pressed again
            bigs.add(Sheet.Big("A−", L("छोटे अक्षर", "Smaller text"), "m-textdown", "#334155", keepOpen = true) { textSize(t, up = false) })
            bigs.add(Sheet.Big("A+", L("बड़े अक्षर", "Bigger text"), "m-textup", "#334155", keepOpen = true) { textSize(t, up = true) })
        }
        bigs.add(Sheet.Big("🧰", "Tools", "m-tools", "#0F766E") { openTools() })
        bigs.add(Sheet.Big("🔎", L("ठगी जाँचें", "Scam check"), "m-scam", "#B91C1C") { openTools("scamcheck") })
        bigs.add(Sheet.Big("🕘", "History", "m-history", "#334155") { showPages(L("History", "History"), app.store.history, bookmarks = false) })
        bigs.add(Sheet.Big("📑", "Bookmarks", "m-bookmarks", "#334155") { showPages("Bookmarks", app.store.bookmarks, bookmarks = true) })

        val page = ArrayList<Sheet.Row>()
        if (web) {
            val h = t!!.topHost
            page.add(Sheet.Row("🏦", L("यह मेरा bank है (Safe Money में खोलें)", "This is my bank (open in Safe Money)"), "", "#1D4ED8", "m-bank") {
                val hh = h.removePrefix("www.")
                // one mis-tap must not send a normal site (Google...) to Safe Money for ever: ask first
                if (hh.isNotEmpty()) showAsk(AlertDialog.Builder(this).setTitle(L("क्या यह आपका bank है?", "Is this your bank?"))
                    .setMessage(L("$hh\n\nयह साइट हमेशा Ronin Safe Money में खुलेगी। बाद में Menu → सुरक्षा → याद रखी साइटें से हटा सकते हैं।", "$hh\n\nThis site will always open in Ronin Safe Money. Remove it any time: Menu → Safety → Remembered sites."))
                    .setPositiveButton(L("हाँ, मेरा bank", "Yes, my bank")) { _, _ -> app.store.userBanks = app.store.userBanks + hh; load(t, t.url) }
                    .setNegativeButton(L("नहीं", "No"), null))
            })
            val dataOn = app.store.isDataSite(h)
            if (dataOn) page.add(Sheet.Row("📊", L("इस page की table → Excel + PDF", "This page's table → Excel + PDF"), "", "#15803D", "m-export") { exportData(t) })
            page.add(Sheet.Row("📊", L("Data बटन इस साइट पर", "Data button on this site"), if (dataOn) on else off, "#15803D", "m-data") {
                if (app.store.toggleDataSite(h)) {
                    Toast.makeText(this, L("📊 Data बटन चालू: page के नीचे दाईं ओर हरा बटन", "📊 Data button on: the green button at the bottom right"), Toast.LENGTH_LONG).show()
                    showDataButton(t)
                } else t.web.evaluateJavascript("(function(){var b=document.getElementById('ronin-data-btn');if(b)b.remove()})()", null)
            })
        }
        val safety = arrayListOf(
            Sheet.Row("🛡", "Family Filter", if (Family.adultBlocked(this)) on else off, "#15803D", "m-family") { Family.chooseAdult(this, first = false) },
            Sheet.Row("🏦", "Ronin Safe Money", "", "#1D4ED8", "m-safemoney") { startActivity(Intent(this, SafeMoneyActivity::class.java)) },
            Sheet.Row("✅", L("ग़लती से रुकी साइटें", "Sites stopped by mistake"), "", "#334155", "m-safesites") { showSafeSites() },
            Sheet.Row("🗂", L("याद रखी साइटें (bank, camera, http)", "Remembered sites (bank, camera, http)"), "", "#334155", "m-remembered") { showRemembered() },
        )
        val phone = ArrayList<Sheet.Row>()
        if (Parts.hasPhone(this)) {
            // Messages + Calls live in the separate Ronin Phone app (3.14.3, Play Protect): open it, or get it
            if (Updater.phoneInstalled(this)) {
                phone.add(Sheet.Row("📩", "Ronin Messages", "", "#1D4ED8", "m-messages") { openPhone("com.ronin.browser.MessagesActivity") })
                phone.add(Sheet.Row("📞", "Ronin Calls", "", "#15803D", "m-calls") { openPhone("com.ronin.browser.CallsActivity") })
            } else phone.add(Sheet.Row("📱", L("Ronin Phone (Messages + Calls) लें", "Get Ronin Phone (Messages + Calls)"), "", "#1D4ED8", "m-getphone") { Updater.installPhone(this) })
        }
        val settings = arrayListOf(
            // the BROWSER's own day / night (Messages / Calls follow the phone's setting instead)
            Sheet.Row(if (app.store.night) "☾" else "☀", L("Browser का रंग (दबाकर बदलें)", "Browser colours (tap to switch)"), if (app.store.night) L("रात", "Night") else L("दिन", "Day"), "#334155", "m-night") {
                safeRecreate {
                    app.store.night = !app.store.night
                    AppCompatDelegate.setDefaultNightMode(if (app.store.night) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
                }
            },
            Sheet.Row("🌐", L("भाषा", "Language"), if (Lang.hindi(this)) "हिन्दी" else "English", "#334155", "m-lang") { chooseLanguage() },
            Sheet.Row("🔎", L("Search widget home screen पर", "Search widget on the home screen"), "", "#334155", "m-widget") {
                if (!SearchWidget.pin(this)) Toast.makeText(this, L("Home screen पर खाली जगह दबाकर रखें → Widgets → Ronin Search", "Press and hold an empty spot on the home screen → Widgets → Ronin Search"), Toast.LENGTH_LONG).show()
            },
            Sheet.Row("⟳", L("Update देखें", "Check for updates"), BuildConfigCompat.versionName(this), "#334155", "m-update") { Updater.check(this, quiet = false) },
            Sheet.Row("ⓘ", L("Ronin के बारे में", "About Ronin"), "", "#334155", "m-about") {
                AlertDialog.Builder(this).setTitle("Ronin Browser ${BuildConfigCompat.versionName(this)}")
                    .setMessage(L("विज्ञापन रोकने की सूची: ${app.blocker.listSize} साइटें।\nख़तरनाक साइटों की सूची: ${app.blocker.threatSize}, साथ में Google Safe Browsing।\nगाने / videos background में चलते रहते हैं; notification, lock screen या car से चलाएँ।",
                        "Ad-block lists: ${app.blocker.listSize} sites.\nDanger list (phishing / malware): ${app.blocker.threatSize} sites, plus Google Safe Browsing.\nSongs and videos keep playing in the background; control them from the notification, the lock screen or your car."))
                    .setPositiveButton("OK", null).show()
            },
        )
        menuSheet = Sheet(this, if (web) t!!.topHost.removePrefix("www.") else "Ronin")
            .bigs(bigs)
            .group(L("यह page", "This page"), page)
            .group(L("सुरक्षा", "Safety"), safety)
            .group(L("Phone", "Phone"), phone)
            .group("Settings", settings)
            .show()
    }
    private var menuSheet: android.app.Dialog? = null
    private fun tellPhoneMoved() {
        val p = Family.prefs(this)
        if (p.getBoolean("phoneMovedTold", false)) return
        val used = java.io.File(filesDir, "saved-sms.json").exists() || java.io.File(applicationInfo.dataDir, "shared_prefs/ronin_phone.xml").exists() ||
            java.io.File(applicationInfo.dataDir, "shared_prefs/ronin_sms.xml").exists()
        p.edit().putBoolean("phoneMovedTold", true).apply()
        if (!used || Updater.phoneInstalled(this) || !Parts.hasPhone(this)) return
        AlertDialog.Builder(this)
            .setTitle(L("📱 Messages और Calls अब अलग app में", "📱 Messages and Calls are now a separate app"))
            .setMessage(L("Play Protect की चेतावनी हटाने के लिए Ronin Messages और Ronin Calls अब \"Ronin Phone\" app में हैं। आपके SMS सुरक्षित हैं; ⭐ सहेजे संदेश और settings नए app में अपने-आप आ जाएँगे।",
                "To end the Play Protect warning, Ronin Messages and Ronin Calls now live in the \"Ronin Phone\" app. Your SMS are safe; ⭐ saved messages and settings move over by themselves."))
            .setPositiveButton(L("Ronin Phone लें", "Get Ronin Phone")) { _, _ -> Updater.installPhone(this) }
            .setNegativeButton(L("बाद में (Menu → Phone)", "Later (Menu → Phone)"), null).show()
    }
    private fun openPhone(cls: String) {
        try { startActivity(Intent().setClassName(Updater.PHONE_PACKAGE, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (e: Exception) { packageManager.getLaunchIntentForPackage(Updater.PHONE_PACKAGE)?.let { startActivity(it) } }
    }

    private fun textSize(t: Tab, up: Boolean) {
        val pct = Nav.nextZoom(t.web.settings.textZoom, up)
        t.web.settings.textZoom = pct
        app.store.setTextZoom(t.topHost, pct)
        Toast.makeText(this, L("अक्षर: $pct%", "Text: $pct%"), Toast.LENGTH_SHORT).show()
    }

    /**
     * Colours / language need the screen drawn again. Open tabs come back by themselves; Ronin asks first only
     * when something would be lost: text typed into a form, a private tab, or a song that plays.
     */
    private fun safeRecreate(change: () -> Unit) {
        val web = tabs.filter { (it.url.startsWith("http") || it.isTools) && it.pending == null }
        var left = web.size
        var typed = false
        val decide = {
            val lose = ArrayList<String>()
            if (typed) lose.add(L("form में लिखा text", "text typed into a form"))
            if (tabs.any { it.isPrivate }) lose.add(L("private tabs", "private tabs"))
            if (mediaPlaying) lose.add(L("चलता गाना", "the song that plays"))
            if (lose.isEmpty()) { releaseMedia(); change(); recreate() }
            else showAsk(AlertDialog.Builder(this).setTitle(L("Screen फिर से बनेगी", "Ronin redraws its screen"))
                .setMessage(L("Tabs वापस आ जाएँगे, पर ये खो जाएँगे: ", "Your tabs come back, but these are lost: ") + lose.joinToString(", ") + L("\n\nअभी बदलें?", "\n\nChange it now?"))
                .setPositiveButton(L("हाँ, बदलें", "Yes, change")) { _, _ -> releaseMedia(); change(); recreate() } // the song's card goes with its page
                .setNegativeButton(L("बाद में", "Later"), null))
        }
        if (left == 0) { decide(); return }
        for (t in web) t.web.evaluateJavascript(PageScripts.HAS_TYPED_TEXT) { r ->
            if (r == "true") typed = true
            if (--left == 0) decide()
        }
    }

    /** Sites Ronin remembers for him: "my bank" (Safe Money) and camera / microphone; ✕ removes one. */
    private fun showRemembered() {
        val banks = app.store.userBanks.sorted()
        val media = app.store.mediaSites.sorted()
        val http = app.store.httpSites.sorted()
        if (banks.isEmpty() && media.isEmpty() && http.isEmpty()) {
            Toast.makeText(this, L("अभी कोई साइट याद नहीं रखी गई", "No remembered sites yet"), Toast.LENGTH_SHORT).show(); return
        }
        Sheet(this, L("याद रखी साइटें: हटाने के लिए दबाएँ", "Remembered sites: tap to remove"))
            .group(L("मेरे bank (Safe Money)", "My banks (Safe Money)"), banks.map { h ->
                Sheet.Row("🏦", h, "✕", "#1D4ED8", "r-bank") { app.store.userBanks = app.store.userBanks - h; Toast.makeText(this, L("हटाया: $h", "Removed: $h"), Toast.LENGTH_SHORT).show() }
            })
            .group(L("बिना https (फिर भी खोलें)", "Without https (Open anyway)"), http.map { h ->
                Sheet.Row("⚠", h, "✕", "#B45309", "r-http") { app.store.httpSites = app.store.httpSites - h; httpAllowed.remove(h); Toast.makeText(this, L("हटाया: $h", "Removed: $h"), Toast.LENGTH_SHORT).show() }
            })
            .group(L("Camera / microphone", "Camera / microphone"), media.map { h ->
                Sheet.Row(if (h.endsWith("|camera")) "📷" else "🎙", h.replace("|", " · "), "✕", "#334155", "r-media") { app.store.mediaSites = app.store.mediaSites - h; Toast.makeText(this, L("हटाया: $h", "Removed: $h"), Toast.LENGTH_SHORT).show() }
            }).show()
    }
    fun menuSheetForTest() = menuSheet

    private fun showPages(title: String, list: List<Store.Page>, bookmarks: Boolean) {
        if (list.isEmpty()) {
            Toast.makeText(this, if (bookmarks) "No bookmarks yet." else "No pages yet.", Toast.LENGTH_SHORT).show(); return
        }
        val sh = Sheet(this, title)
        if (!bookmarks) sh.bigs(listOf(Sheet.Big("🗑", L("History मिटाएँ", "Delete history"), "h-clear", "#B91C1C") {
            showAsk(AlertDialog.Builder(this).setTitle(L("पूरी history मिटाएँ?", "Delete all history?"))
                .setPositiveButton(L("हाँ, मिटाएँ", "Yes, delete")) { _, _ -> app.store.clearHistory() }
                .setNegativeButton(L("नहीं", "No"), null))
        }))
        sh.group("", (if (bookmarks) list else list.take(150)).map { pg ->
            Sheet.Row(if (bookmarks) "★" else "🕘", pg.title.ifBlank { pg.url }, "", "#334155", "page") { current?.let { load(it, pg.url) } }
        }).show()
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
        val data = JSONObject().put("k", pageKey).put("lang", Lang.code(this)).put("edit", homeEditing).put("sections", sections).toString().replace("</", "<\\/")
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
        siteSettings(t.web, baseUrl)
        t.web.loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", null)
    }

    companion object {
        const val HOME = "ronin://home"
        const val BACK_TO_SAFETY = "https://ronin.home/back"
        const val TRUST_PREFIX = "https://ronin.home/trust"
        const val TOOLS = "https://ronin.home/tools/"
        const val PRIVATE_PROFILE = "ronin-private"
        const val GOOGLE = "https://www.google.com/"
        /** Proves "save this file" came from the tools page. */
        val toolsKey: String = java.util.UUID.randomUUID().toString().replace("-", "")

        /** Proves a link came from Ronin's own page (new tab page, red page). New each start. */
        val pageKey: String = java.util.UUID.randomUUID().toString().replace("-", "")
        /** A separate key for the Data button (it sits inside web pages; it can only start an export). */
        val dataKey: String = java.util.UUID.randomUUID().toString().replace("-", "")
        /** Ronin's own error page: Reload, and "Open anyway" without https (only with pageKey). */
        /** Browser screens alive right now: a song page is kept only when the last one closes (else it stops). */
        @Volatile var liveScreens = 0
        /** Which browser screen owns the player card now (0 = none / the kept page). */
        @Volatile var mediaOwner = 0
        const val RELOAD_LINK = "https://ronin.home/reload"
        const val ERROR_PAGE = "https://ronin.home/error"
        const val HTTP_LINK = "https://ronin.home/http"
    }
}
