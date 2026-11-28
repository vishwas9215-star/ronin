package com.ronin.browser.agent

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The Claude tab of the pilot ("Ronin Agent Lab"): one page view that Claude drives, with a banner
 * the owner can use without typing: Pause / Resume / Stop, and DONE when Claude handed him the screen.
 * Keeps the screen on while charging, so a tablet on its stand stays ready all day.
 */
class AgentActivity : Activity() {
    companion object {
        @Volatile var driver: AgentDriver? = null; @Volatile var link: AgentLink? = null
        /** Ronin's local proxy: every connection of this app's pages goes through it (one per process). */
        @Volatile var proxy: AgentProxy? = null
        @Volatile var proxyOn = false
    }

    private lateinit var status: TextView
    private lateinit var linkLine: TextView
    private lateinit var rows: LinearLayout
    private lateinit var toggle: Button
    private var buttonsShown = false
    private val autoHide = Runnable { showButtons(false) }

    private fun showButtons(on: Boolean) {
        buttonsShown = on
        rows.visibility = if (on) android.view.View.VISIBLE else android.view.View.GONE
        linkLine.visibility = rows.visibility
        toggle.text = if (on) "बंद ▴" else "बटन ▾"
        ticker.removeCallbacks(autoHide)
        if (on) ticker.postDelayed(autoHide, 30_000)
    }
    private var removedAt = 0L
    private var repairSaid = ""; private var repairAt = 0L
    private val ticker = android.os.Handler(android.os.Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { refresh(); ticker.postDelayed(this, 1000) } }
    private lateinit var web: AgentWebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // the screen stays on only while Claude is working (and 2 minutes after); otherwise it turns off normally.
        // A command from Claude wakes it again (above the lock screen; the phone is never unlocked).
        // the pilot app (com.ronin.agentlab) wakes the screen and runs above the lock screen for the night's tests;
        // it never unlocks the phone. The real Ronin will ask the owner before doing this.
        if (packageName.endsWith(".agentlab") && android.os.Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        val d = resources.displayMetrics.density
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(13, 118, 128)) }
        // Android 15+ draws apps under the status bar and the navigation bar: the strip starts BELOW the status bar
        // (owner, 28 Nov: the top bar mixed with the page), and the keyboard never covers the page either
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, ins ->
            val t = androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            val b = ins.getInsets(t); val ime = ins.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, b.top, b.right, maxOf(b.bottom, ime.bottom))
            androidx.core.view.WindowInsetsCompat.CONSUMED
        }
        // banner: a full-width status line (always readable on a phone), then the buttons in two rows.
        // Every press shows at once what happened (status + a short message), so a press never looks "dead".
        val top = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(13, 118, 128)); setPadding((8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt(), (4 * d).toInt()) }
        // a thin strip by default (the page gets the whole screen); "बटन ▾" opens the buttons, they close again by
        // themselves after 30 s. They open by themselves when Claude hands the screen to the owner (DONE needed).
        val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        status = TextView(this).apply { setTextColor(Color.WHITE); textSize = 13f; text = "🤖 Claude तैयार है"; maxLines = 3 }
        strip.addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        toggle = Button(this).apply {
            text = "बटन ▾"; tag = "agent-toggle"; isAllCaps = false; textSize = 13f; minHeight = (36 * d).toInt(); minimumHeight = (36 * d).toInt(); minWidth = 0
            setOnClickListener { showButtons(!buttonsShown) }
        }
        strip.addView(toggle)
        top.addView(strip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        linkLine = TextView(this).apply { setTextColor(Color.rgb(220, 240, 240)); textSize = 12f; visibility = android.view.View.GONE }
        top.addView(linkLine)
        top.setPadding((8 * d).toInt(), (2 * d).toInt(), (4 * d).toInt(), (2 * d).toInt())
        root.addView(top)
        fun btn(label: String, tag: String, said: String, f: () -> Unit) = Button(this).apply {
            text = label; this.tag = tag; isAllCaps = false; textSize = 16f; minHeight = (52 * d).toInt(); minWidth = 0
            setOnClickListener {
                f(); refresh(); showButtons(true)
                android.widget.Toast.makeText(this@AgentActivity, said, android.widget.Toast.LENGTH_SHORT).show()
                android.util.Log.i("RoninAgent", "owner pressed $tag")
            }
        }
        fun row(vararg b: Button) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.rgb(9, 90, 98))
            for (x in b) addView(x, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = android.view.View.GONE }
        root.addView(rows)
        rows.addView(row(
            btn("DONE", "agent-done", "✅ DONE: Claude को बता दिया") { driver?.ownerDone() },
            btn("PAUSE", "agent-pause", "⏸ Claude रुका: RESUME दबाने तक") { driver?.ownerPause() },
            btn("RESUME", "agent-resume", "▶ Claude फिर चालू") { driver?.resume() },
            btn("STOP", "agent-stop", "⏹ Claude रोक दिया") { driver?.ownerStop() }))
        // "Claude जोड़ें" opens a 3-minute window for ONE Connect card on this device (a stranger's Claude can never
        // show a card); "Claude हटाएँ" disconnects Claude from all his devices.
        rows.addView(row(
            btn("Claude जोड़ें", "agent-connect", "🔗 3 मिनट खुला: अब Claude में Connect करें") {
                if (link == null) android.widget.Toast.makeText(this, "relay से link अभी नहीं है", android.widget.Toast.LENGTH_SHORT).show()
                link?.openConnectWindow()
            },
            btn("Claude हटाएँ", "agent-disconnect", "⛔ Claude सभी devices से हटा दिया") {
                link?.disconnectClaude(); removedAt = System.currentTimeMillis()
            }))
        // after a format the phone waits with a code: the owner allows it HERE (compare the code, press and hold)
        rows.addView(row(
            btn("नया device जोड़ें", "agent-repair", "🔁 देख रहे हैं: कोई नया device इंतज़ार में है?") {
                val l = link
                if (l == null) { repairSaid = "relay से link अभी नहीं है"; repairAt = System.currentTimeMillis(); return@btn }
                repairSaid = "🔁 देख रहे हैं..."; repairAt = System.currentTimeMillis()
                kotlin.concurrent.thread { val m = l.approveRepair(); runOnUiThread { repairSaid = m; repairAt = System.currentTimeMillis(); refresh(); android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_LONG).show() } }
            }))
        web = AgentWebView(this)
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        val js = assets.open("agent/agent.js").bufferedReader().use { it.readText() }
        val rules = (application as? com.ronin.browser.RoninApp)?.rules
        val dr = AgentDriver(web, getSharedPreferences("agent-journal", MODE_PRIVATE), js) { h -> rules?.isBankHost(h) == true }
        dr.attach()
        dr.onOwnerAsked = { reason -> runOnUiThread { showButtons(true); ticker.removeCallbacks(autoHide); status.text = "✋ आपकी बारी: ${reason.ifBlank { "काम पूरा करके DONE दबाएँ" }}" } }
        driver = dr
        startProxy()
        dr.onCommand = { runOnUiThread { working() } }
        try { // pages that offer a dark look use it at night
            if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.ALGORITHMIC_DARKENING))
                androidx.webkit.WebSettingsCompat.setAlgorithmicDarkeningAllowed(web.settings, true)
        } catch (e: Throwable) {}
        showStartPage()
        refresh()
        startLink(dr)
    }

    /**
     * Every connection of the Claude tab through Ronin's local proxy, which checks the address it really connects
     * to: DNS rebinding, WebSockets, service workers and page scripts can never reach the home network / this phone.
     * Needs a WebView that supports proxy override (Android System WebView 72+); without it the guard stays alone.
     */
    private fun startProxy() {
        if (proxy != null) return
        val p = AgentProxy(blocked = { android.util.Log.i("RoninAgent", "blocked: private address (proxy)") })
        val port = try { p.start() } catch (e: Exception) { android.util.Log.i("RoninAgent", "proxy: ${e.javaClass.simpleName}"); return }
        proxy = p
        if (!androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.PROXY_OVERRIDE)) {
            android.util.Log.i("RoninAgent", "proxy: not supported by this WebView"); return
        }
        val cfg = androidx.webkit.ProxyConfig.Builder().addProxyRule("127.0.0.1:$port").removeImplicitRules().build()
        androidx.webkit.ProxyController.getInstance().setProxyOverride(cfg, { it.run() }) {
            proxyOn = true
            android.util.Log.i("RoninAgent", "proxy: on")
        }
    }

    /** The link to the owner's relay: address + relay public key baked into the app (assets/agent/relay.json). */
    private fun startLink(dr: AgentDriver) {
        val lab = packageName.endsWith(".agentlab") && (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        var url = ""; var pub = ""
        try { val j = org.json.JSONObject(assets.open("agent/relay.json").bufferedReader().use { it.readText() }); url = j.optString("url"); pub = j.optString("pub") } catch (_: Exception) {}
        if (lab) { intent.getStringExtra("relayUrl")?.let { url = it }; intent.getStringExtra("relayPub")?.let { pub = it } }
        if (lab && intent.getBooleanExtra("noLink", false)) return // device tests: never talk to the owner's real relay
        if (url.isEmpty() || pub.isEmpty()) return
        val autoApprove = lab && intent.getBooleanExtra("autoApprove", false) // CI end-to-end test only (debug lab app)
        val tablet = android.os.Build.MODEL.equals("OPD2415", true) || resources.configuration.smallestScreenWidthDp >= 600
        val prefs = getSharedPreferences("agent-link", MODE_PRIVATE)
        val l = AgentLink(url, pub, if (tablet) "tablet" else "phone", prefs, dr, approve = { text -> autoApprove || askApproval(text) })
        link = l
        if (!autoApprove) keepWorkingInBackground()
        kotlin.concurrent.thread {
            // not paired yet (or formatted): keep asking; while the name is taken it waits for the owner's OK on
            // another device, with its code on the screen
            var wait = 6000L
            while (!prefs.getBoolean("paired", false) && !isDestroyed) {
                if (l.pair()) break
                runOnUiThread { refresh() }
                Thread.sleep(wait); if (l.repairCode == null) wait = minOf(wait * 2, 60_000)
            }
            l.start()
            if (autoApprove) l.openConnectWindow() // CI: the test stands in for the owner's tap
        }
    }

    /** The one-time "Connect Claude?" card: a fixed text, PRESS AND HOLD to allow (palm-friendly), 2 minutes to answer. */
    private fun askApproval(text: String): Boolean {
        val latch = java.util.concurrent.CountDownLatch(1)
        var ok = false
        runOnUiThread {
            val hold = Button(this).apply { this.text = "दबाकर रखें: Allow"; textSize = 20f; minHeight = (72 * resources.displayMetrics.density).toInt() }
            val dlg = android.app.AlertDialog.Builder(this).setTitle(text).setView(hold)
                .setNegativeButton("Deny") { _, _ -> latch.countDown() }.setOnCancelListener { latch.countDown() }.create()
            hold.setOnLongClickListener { ok = true; dlg.dismiss(); latch.countDown(); true }
            dlg.show()
        }
        latch.await(120, java.util.concurrent.TimeUnit.SECONDS)
        return ok
    }

    private fun refresh() {
        val dr = driver ?: return
        val left = ((link?.connectUntil ?: 0L) - System.currentTimeMillis()) / 1000
        val code = link?.repairCode
        status.text = when {
            code != null -> "🔁 Code $code · यह device पहले से जुड़ा था: tablet पर Ronin Agent → बटन ▾ → \"नया device जोड़ें\" दबाएँ"
            System.currentTimeMillis() - repairAt < 15_000 && repairSaid.isNotEmpty() -> repairSaid
            left > 0 -> "🔗 Claude जोड़ें खुला है: ${left / 60}:${"%02d".format(left % 60)} बाकी। अब Claude में Connect करें, फिर यहाँ code मिलाकर Allow दबाकर रखें"
            System.currentTimeMillis() - removedAt < 10_000 -> "⛔ Claude हटा दिया गया (सभी devices से)"
            dr.stopped -> "⏹ Claude रुका है (RESUME से चालू)"
            dr.paused -> "⏸ आपने PAUSE दबाया: Claude रुका है (RESUME दबाएँ)"
            dr.ownerHolding -> "✋ आपकी बारी: काम करके DONE दबाएँ"
            else -> "🤖 Claude काम कर सकता है"
        }
        linkLine.text = when {
            link == null -> "relay link: बंद"
            link?.online == true -> "🟢 relay से जुड़ा" + if (proxyOn) " · सुरक्षा proxy चालू" else ""
            else -> "🔴 relay से जुड़ रहा है…"
        }
    }

    /** The page gets the whole screen: status / navigation bars hide (a swipe from the edge shows them). */
    private fun fullScreen() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                window.insetsController?.let {
                    it.hide(android.view.WindowInsets.Type.systemBars())
                    it.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
        } catch (e: Exception) {}
    }

    /**
     * Work goes on while the owner uses other apps: a small notification keeps Ronin alive, and once the phone is
     * asked (one Allow, only the first time) not to put Ronin Agent to sleep, its internet is not cut when idle.
     */
    private fun keepWorkingInBackground() {
        AgentKeepAlive.start(this)
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        val prefs = getSharedPreferences("agent-ui", MODE_PRIVATE)
        if (android.os.Build.VERSION.SDK_INT >= 23 && !pm.isIgnoringBatteryOptimizations(packageName) && !prefs.getBoolean("askedBattery", false)) {
            prefs.edit().putBoolean("askedBattery", true).apply()
            try {
                @android.annotation.SuppressLint("BatteryLife")
                val i = android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:$packageName"))
                startActivity(i)
            } catch (e: Exception) {}
        }
    }

    private fun night() = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES

    /** Ronin's own start page: dark at night, light by day (no white screen in a dark room). */
    private fun showStartPage() {
        val (bg, fg) = if (night()) "#121212" to "#d8d8d8" else "#ffffff" to "#333333"
        window.decorView.setBackgroundColor(Color.parseColor(bg))
        web.setBackgroundColor(Color.parseColor(bg))
        web.loadDataWithBaseURL(null, "<!doctype html><meta charset='utf-8'><meta name='viewport' content='width=device-width'>" +
            "<body style='background:$bg;color:$fg;font:18px sans-serif;padding:28px'><h2>🤖 Ronin Agent Lab</h2>" +
            "<p>यह screen Claude के लिए है। Claude जब काम करेगा, screen अपने आप जलेगी; काम न हो तो screen बंद हो जाएगी।</p>" +
            "<p>रोकना हो तो ऊपर STOP दबाएँ।</p></body>", "text/html", "utf-8", null)
    }

    override fun onConfigurationChanged(c: android.content.res.Configuration) {
        super.onConfigurationChanged(c)
        if (web.url == null || web.url == "about:blank") showStartPage() // day / night changed while idle
    }

    private var wake: android.os.PowerManager.WakeLock? = null
    private val idle = Runnable {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        try { wake?.let { if (it.isHeld) it.release() } } catch (e: Exception) {}
    }

    /** Claude is working: screen on (woken if it was off) until 2 minutes after the last command. */
    private fun working() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        if (!pm.isInteractive) {
            try {
                @Suppress("DEPRECATION")
                val w = pm.newWakeLock(android.os.PowerManager.SCREEN_DIM_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP, "ronin:agent")
                w.acquire(3 * 60_000L); wake = w
            } catch (e: Exception) {}
        }
        ticker.removeCallbacks(idle)
        ticker.postDelayed(idle, 2 * 60_000L)
    }

    override fun onResume() { super.onResume(); ticker.removeCallbacks(tick); ticker.post(tick) }
    override fun onPause() { ticker.removeCallbacks(tick); super.onPause() }

    override fun onStop() { super.onStop(); CookieManager.getInstance().flush() }
    override fun onDestroy() { if (driver?.web === web) { driver = null; link?.stop(); link = null }; web.destroy(); super.onDestroy() }
}
