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
    private val ua get() = InstrumentationRegistry.getInstrumentation().uiAutomation
    private fun sh(c: String) = ua.executeShellCommand(c).close()
    private fun onMain(f: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(f)

    @Before fun on() {
        sh("settings put secure enabled_accessibility_services com.ronin.census/com.ronin.census.CensusService")
        sh("settings put secure accessibility_enabled 1")
        var w = 0; while (CensusService.instance == null && w < 100) { Thread.sleep(100); w++ }
        assertTrue("the helper started", CensusService.instance != null)
        sc = ActivityScenario.launch(TestFormActivity::class.java)
        Thread.sleep(1500)
    }

    @After fun off() { sc.close(); sh("settings put secure enabled_accessibility_services ''") }

    private fun say(t: String): String { var m = ""; onMain { m = CensusService.instance!!.handle(t) }; Thread.sleep(400); Log.i("RoninAgent", "CENSUS \"$t\" -> $m"); return m }

    @Test fun readsTheBoxesWithoutAPicture() {
        var labels = listOf<String>()
        onMain { labels = CensusService.instance!!.boxes().map { it.label } }
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
}
