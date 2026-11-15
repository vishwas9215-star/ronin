package com.ronin.browser

import android.content.Intent
import android.view.KeyEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Ronin TV on a Google TV / Android TV emulator: home-row entry, no-ads guard, remote keys, YouTube's TV screen. */
@RunWith(AndroidJUnit4::class)
class TvTest {
    private val inst = InstrumentationRegistry.getInstrumentation()

    private fun shot(name: String) {
        Thread.sleep(700)
        fun sh(c: String) = inst.uiAutomation.executeShellCommand(c).use { pfd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
        }
        sh("mkdir -p /data/local/tmp/qa")
        sh("screencap -p /data/local/tmp/qa/$name.png")
    }

    private fun js(sc: ActivityScenario<TvActivity>, code: String): String {
        val l = CountDownLatch(1); var out = ""
        sc.onActivity { a -> a.web.evaluateJavascript(code) { r -> out = r ?: ""; l.countDown() } }
        l.await(15, TimeUnit.SECONDS)
        return try { JSONArray("[$out]").getString(0) } catch (e: Exception) { out }
    }

    @Test
    fun tvHomeRowOpensRoninTv() {
        val pm = inst.targetContext.packageManager
        val found = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER).setPackage(inst.targetContext.packageName), 0)
        assertTrue("Ronin TV is not in the TV home row", found.any { it.activityInfo.name.endsWith(".TvActivity") })
    }

    @Test
    fun youtubeTvScreenWithoutAdsAndRemoteKeys() {
        ActivityScenario.launch(TvActivity::class.java).use { sc ->
            // the advert part of a player answer is removed, the video part stays
            js(sc, TvActivity.NO_ADS_JS)
            val pruned = js(sc, "JSON.stringify(JSON.parse('{\"adPlacements\":[1],\"playerAds\":[2],\"adSlots\":[3],\"videoDetails\":{\"title\":\"x\"},\"playerResponse\":{\"adPlacements\":[4],\"streamingData\":{}}}'))")
            assertTrue("ads left in the player answer: $pruned", !pruned.contains("adPlacements") && !pruned.contains("playerAds") && !pruned.contains("adSlots"))
            assertTrue("video part removed: $pruned", pruned.contains("videoDetails") && pruned.contains("streamingData"))

            // YouTube's TV screen (real network: reported by screenshot, never blocks a release)
            var where = ""
            for (i in 0 until 40) {
                Thread.sleep(1000)
                where = js(sc, "location.host + location.pathname + ' | ' + document.title + ' | ' + (document.body ? document.body.innerText.length : 0)")
                if (where.startsWith("www.youtube.com/tv") && !where.endsWith("| 0")) break
            }
            android.util.Log.i("RoninTV", "loaded: $where")
            shot("tv-01-youtube")
            // remote control: down, right, OK, back - the screen must stay alive
            for (k in listOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_RIGHT)) { inst.sendKeyDownUpSync(k); Thread.sleep(600) }
            shot("tv-02-remote")
            inst.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER); Thread.sleep(4000)
            shot("tv-03-opened")
            inst.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); Thread.sleep(1500)
            shot("tv-04-back")
            var alive = false
            sc.onActivity { a -> alive = !a.isFinishing }
            android.util.Log.i("RoninTV", "after back alive=$alive at " + js(sc, "location.href"))
        }
    }
}
