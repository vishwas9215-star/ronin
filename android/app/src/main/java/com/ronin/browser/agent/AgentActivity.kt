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
    companion object { @Volatile var driver: AgentDriver? = null; @Volatile var link: AgentLink? = null }

    private lateinit var status: TextView
    private lateinit var web: AgentWebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // the pilot app (com.ronin.agentlab) wakes the screen and runs above the lock screen for the night's tests;
        // it never unlocks the phone. The real Ronin will ask the owner before doing this.
        if (packageName.endsWith(".agentlab") && android.os.Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        val d = resources.displayMetrics.density
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(13, 118, 128)); setPadding((8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt())
        }
        status = TextView(this).apply { setTextColor(Color.WHITE); textSize = 16f; text = "🤖 Claude तैयार है" }
        bar.addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fun btn(label: String, tag: String, f: () -> Unit) = Button(this).apply {
            text = label; this.tag = tag; isAllCaps = false; textSize = 16f; minHeight = (52 * d).toInt(); minWidth = (88 * d).toInt()
            setOnClickListener { f(); refresh() }
        }
        bar.addView(btn("DONE", "agent-done") { driver?.ownerDone() })
        bar.addView(btn("RESUME", "agent-resume") { driver?.resume() })
        bar.addView(btn("STOP", "agent-stop") { driver?.ownerStop() })
        root.addView(bar)
        // the owner's two Claude buttons: "Claude जोड़ें" opens a 3-minute window for ONE Connect card on this device
        // (a stranger's Claude can never show a card); "Claude हटाएँ" disconnects Claude from all his devices.
        val bar2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.rgb(9, 90, 98)) }
        bar2.addView(btn("Claude जोड़ें", "agent-connect") {
            link?.openConnectWindow()
            status.post { status.text = "🔗 3 मिनट: अब Claude में Connect दबाएँ, फिर यहाँ Allow दबाकर रखें" }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar2.addView(btn("Claude हटाएँ", "agent-disconnect") {
            link?.disconnectClaude()
            status.post { status.text = "⛔ Claude हटा दिया गया (सभी devices से)" }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(bar2)
        web = AgentWebView(this)
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        val js = assets.open("agent/agent.js").bufferedReader().use { it.readText() }
        val rules = (application as? com.ronin.browser.RoninApp)?.rules
        val dr = AgentDriver(web, getSharedPreferences("agent-journal", MODE_PRIVATE), js) { h -> rules?.isBankHost(h) == true }
        dr.attach()
        dr.onOwnerAsked = { reason -> runOnUiThread { status.text = "✋ आपकी बारी: ${reason.ifBlank { "काम पूरा करके DONE दबाएँ" }}" } }
        driver = dr
        web.loadUrl("about:blank")
        refresh()
        startLink(dr)
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
        val l = AgentLink(url, pub, if (tablet) "tablet" else "phone", prefs, dr) { text -> autoApprove || askApproval(text) }
        link = l
        kotlin.concurrent.thread {
            if (!prefs.getBoolean("paired", false)) l.pair()
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
        status.text = when {
            dr.stopped -> "⏹ Claude रुका है (RESUME से चालू)"
            dr.paused -> "⏸ आपने छुआ: Claude रुका है (RESUME दबाएँ)"
            dr.ownerHolding -> "✋ आपकी बारी: काम करके DONE दबाएँ"
            else -> "🤖 Claude काम कर सकता है"
        }
    }

    override fun onStop() { super.onStop(); CookieManager.getInstance().flush() }
    override fun onDestroy() { if (driver?.web === web) { driver = null; link?.stop(); link = null }; web.destroy(); super.onDestroy() }
}
