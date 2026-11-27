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
    companion object { @Volatile var driver: AgentDriver? = null }

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
    override fun onDestroy() { if (driver?.web === web) driver = null; web.destroy(); super.onDestroy() }
}
