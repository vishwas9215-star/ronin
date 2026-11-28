package com.ronin.census

import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The helper switched on for real (Accessibility) over a form shaped like the Census app's. */
@RunWith(AndroidJUnit4::class)
class CensusServiceTest {
    private lateinit var sc: ActivityScenario<TestFormActivity>
    // a plain UiAutomation switches every other accessibility service off while it is connected
    private val ua get() = InstrumentationRegistry.getInstrumentation().getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private fun sh(c: String) = ua.executeShellCommand(c).close()
    private fun onMain(f: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(f)

    @Before fun on() {
        // an earlier test may have turned the screen off: wake it and take the lock screen away
        sh("input keyevent KEYCODE_WAKEUP"); sh("wm dismiss-keyguard"); Thread.sleep(500)
        if (CensusService.instance == null) {
            sh("settings put secure enabled_accessibility_services com.ronin.census/com.ronin.census.CensusService")
            sh("settings put secure accessibility_enabled 1")
        }
        var w = 0; while (CensusService.instance == null && w < 150) { Thread.sleep(100); w++ }
        assertTrue("the helper started", CensusService.instance != null)
        sc = ActivityScenario.launch(TestFormActivity::class.java)
        onMain { CensusService.instance!!.setOn(true) }; Thread.sleep(300)
        // the form is on screen and the helper sees it
        var n = 0; w = 0
        while (n < 3 && w < 80) { onMain { n = CensusService.instance?.boxes()?.size ?: 0 }; if (n < 3) Thread.sleep(100); w++ }
    }

    @After fun off() { if (::sc.isInitialized) sc.close() } // the helper stays on between tests (switching it costs seconds)

    private fun say(t: String): String { var m = ""; onMain { m = CensusService.instance!!.handle(t) }; Thread.sleep(400); Log.i("RoninAgent", "CENSUS \"$t\" -> $m"); return m }

    @Test fun readsTheBoxesWithoutAPicture() {
        var labels = listOf<String>()
        var w = 0
        while (w < 50) { onMain { labels = CensusService.instance!!.boxes().map { it.label } }; if (labels.size >= 3) break; Thread.sleep(100); w++ }
        assertTrue("sees the boxes: $labels", labels.containsAll(listOf("परिवार के मुखिया का नाम", "टिप्पणियाँ", "मुहल्ला/बस्ती")))
    }

    @Test fun fillsWhatIsSaid() {
        assertTrue(say("मुहल्ला बस्ती पूरब टोला").startsWith("✅"))
        assertTrue(say("मुखिया का नाम राम प्रसाद").startsWith("✅"))
        var m = ""; var h = ""
        sc.onActivity { m = it.mohalla.text.toString(); h = it.head.text.toString() }
        assertEquals("पूरब टोला", m)
        assertEquals("राम प्रसाद", h)
    }

    @Test fun choosesAnOption() {
        say("खाली")
        var c = ""; sc.onActivity { c = it.chosen }
        assertEquals("खाली", c)
    }

    @Test fun movesOnOnlyAfterYes() {
        assertTrue(say("आगे बढ़ो").contains("दबाऊँ"))
        var n = -1; sc.onActivity { n = it.nextPressed }
        assertEquals("not pressed before yes", 0, n)
        say("नहीं")
        sc.onActivity { n = it.nextPressed }
        assertEquals("not pressed after no", 0, n)
        say("आगे बढ़ो"); say("हाँ")
        sc.onActivity { n = it.nextPressed }
        assertEquals("pressed once after yes", 1, n)
    }

    @Test fun hasNoInternetPermission() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val perms = ctx.packageManager.getPackageInfo(ctx.packageName, android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions?.toList() ?: emptyList()
        assertTrue("no INTERNET: $perms", "android.permission.INTERNET" !in perms)
    }

    @Test fun numberWordsBecomeDigitsAndWrongIsUndone() {
        assertTrue(say("सदस्यों की संख्या पांच").startsWith("✅"))
        var v = ""; sc.onActivity { v = it.members.text.toString() }
        assertEquals("5", v)
        say("गलत")
        sc.onActivity { v = it.members.text.toString() }
        assertEquals("undone", "", v)
    }

    // the family saying "हाँ" to a question must never move the form on while the helper listens hands-free
    @Test fun aYesInTheRoomDoesNotMoveOn() {
        onMain { CensusService.instance!!.handsFree = true }
        try {
            say("आगे बढ़ो"); say("हाँ")
            var n = -1; sc.onActivity { n = it.nextPressed }
            assertEquals("plain yes ignored hands-free", 0, n)
            say("हाँ आगे बढ़ो")
            sc.onActivity { n = it.nextPressed }
            assertEquals("yes with the action presses once", 1, n)
        } finally { onMain { CensusService.instance!!.handsFree = false } }
    }

    // C (Claude) through Ronin Agent: read the screen, fill, choose, move on; submit / sync only with the owner's yes
    @Test fun bridgeForClaude() {
        fun b(tool: String, vararg kv: Pair<String, Any>): org.json.JSONObject {
            val a = org.json.JSONObject(); kv.forEach { (k, v) -> a.put(k, v) }
            var r = org.json.JSONObject(); onMain { r = CensusService.instance!!.bridge(tool, a) }; Thread.sleep(300)
            Log.i("RoninAgent", "BRIDGE $tool -> ${r.toString().take(160)}"); return r
        }
        val s = b("app_screen")
        val labels = (0 until s.getJSONArray("boxes").length()).map { s.getJSONArray("boxes").getJSONObject(it).getString("label") }
        assertTrue("sees the boxes: $labels", "मुहल्ला/बस्ती" in labels)
        assertTrue(b("app_fill", "label" to "मुहल्ला/बस्ती", "value" to "दक्षिण टोला").has("done"))
        var m = ""; sc.onActivity { m = it.mohalla.text.toString() }
        assertEquals("दक्षिण टोला", m)
        val after = b("app_screen").getJSONArray("boxes")
        assertTrue("the filled value shows", (0 until after.length()).any { after.getJSONObject(it).optString("value") == "दक्षिण टोला" })
        assertTrue(b("app_fill", "label" to "कोई और", "value" to "x").has("error"))
        assertTrue(b("app_choose", "text" to "खाली").has("done"))
        assertTrue("moving on via choose refused", b("app_choose", "text" to "आगे बढ़ें").has("error"))
        assertTrue("आगे बढ़ें after the summary", b("app_press", "text" to "आगे बढ़ें").has("done"))
        var n = -1; sc.onActivity { n = it.nextPressed }; assertEquals(1, n)
    }

    // owner, 28 Nov: "dropdown खोल सके, खुद tap करके चुने"
    @Test fun opensADropdownAndChoosesFromItsList() {
        val sv = CensusService.instance!!
        fun b(tool: String, vararg kv: Pair<String, Any>): org.json.JSONObject {
            val a = org.json.JSONObject(); kv.forEach { (k, v) -> a.put(k, v) }
            var r = org.json.JSONObject(); onMain { r = sv.bridge(tool, a) }; Thread.sleep(800); return r
        }
        assertTrue("opens the dropdown", b("app_choose", "text" to "मकान का वर्तमान उपयोग").has("done"))
        val s = b("app_screen")
        val all = (0 until s.getJSONArray("options").length()).map { s.getJSONArray("options").getJSONObject(it).getString("text") } + (0 until s.getJSONArray("texts").length()).map { s.getJSONArray("texts").getString(it) }
        assertTrue("the open list is seen: $all", "दुकान/कार्यालय" in all)
        assertTrue("chooses from it", b("app_choose", "text" to "दुकान/कार्यालय").has("done"))
        var u = ""; sc.onActivity { u = it.use }
        assertEquals("दुकान/कार्यालय", u)
        Log.i("RoninAgent", "CENSUS dropdown PASS")
    }

    // owner, 28 Nov: "अपने मन से भरो" -> "समझ में नहीं आया". Now what is heard goes to C, who fills and asks; रुको stops him
    @Test fun liveWhatIsHeardGoesToCAndStopStops() {
        val sv = CensusService.instance!!
        val got = java.util.Collections.synchronizedList(ArrayList<org.json.JSONObject>())
        fun listen(sec: Int) = onMain { sv.bridgeAsync("app_listen", org.json.JSONObject().put("seconds", sec)) { got.add(it) } }
        fun waitFor(n: Int) { val end = System.currentTimeMillis() + 8000; while (got.size < n && System.currentTimeMillis() < end) Thread.sleep(100) }
        listen(30); Thread.sleep(500)
        assertTrue("waits until something is said", got.isEmpty()); assertTrue(sv.live)
        onMain { sv.onHeard("इनका नाम रामलाल है और घर में चार लोग हैं") }; waitFor(1)
        assertEquals("इनका नाम रामलाल है और घर में चार लोग हैं", got[0].getJSONArray("heard").getString(0))
        var m = "x"; sc.onActivity { m = it.mohalla.text.toString() }
        assertEquals("ordinary talk is not treated as a command", "", m)
        var said = org.json.JSONObject(); onMain { sv.bridgeAsync("app_say", org.json.JSONObject().put("text", "उम्र कितनी है?").put("question", true)) { said = it } }; Thread.sleep(300)
        assertTrue(said.has("done")); var pt = ""; onMain { pt = sv.panelText }
        assertTrue("the question is on the panel: $pt", "उम्र कितनी है?" in pt)
        listen(1); waitFor(2)
        assertEquals("nobody spoke", 0, got[1].getJSONArray("heard").length())
        listen(30); Thread.sleep(300); onMain { sv.onHeard("रुको") }; waitFor(3)
        assertTrue("रुको reaches C", got[2].optBoolean("stopped")); assertTrue(!sv.live)
        listen(5); waitFor(4)
        assertTrue("C may not listen again by himself", got[3].has("error"))
        onMain { sv.toggleLive() }; listen(1); waitFor(5)
        assertTrue("after the owner's 🤖 he may", got[4].has("heard") && !got[4].has("error"))
        Log.i("RoninAgent", "CENSUS live: listen, say, stop PASS")
    }

    // owner, 28 Nov: the helper stays on every screen. Now: off until turned on by hand; off by itself when he leaves the app
    @Test fun onOnlyByHandAndOffWhenHeLeaves() {
        onMain { CensusService.instance!!.setOn(false) }; Thread.sleep(300)
        var on = true; onMain { on = CensusService.instance!!.isOn }
        assertTrue("off", !on)
        var r = org.json.JSONObject(); onMain { r = CensusService.instance!!.bridge("app_screen", org.json.JSONObject()) }
        assertTrue("C can not fill while it is off", r.has("error"))
        onMain { CensusService.instance!!.setOn(true) }; Thread.sleep(300)
        onMain { on = CensusService.instance!!.isOn }
        assertTrue("on by hand", on)
        // he goes to another app, then home: the helper goes off by itself
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        Thread.sleep(1500)
        sh("input keyevent KEYCODE_HOME"); Thread.sleep(1500)
        onMain { on = CensusService.instance!!.isOn }
        assertTrue("off after leaving the app", !on)
    }
}
