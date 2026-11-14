package com.ronin.browser

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Base64
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Runs on an Android emulator on the build machine. */
@RunWith(AndroidJUnit4::class)
class DeviceTest {
    @get:Rule
    val notify: GrantPermissionRule = if (Build.VERSION.SDK_INT >= 33)
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else GrantPermissionRule.grant()

    private val inst = InstrumentationRegistry.getInstrumentation()
    private val app: Context get() = inst.targetContext

    private fun eval(sc: ActivityScenario<MainActivity>, js: String): String {
        val latch = CountDownLatch(1)
        var out = ""
        sc.onActivity { a -> a.webForTest().evaluateJavascript(js) { out = it ?: ""; latch.countDown() } }
        latch.await(15, TimeUnit.SECONDS)
        return out
    }

    private fun waitFor(seconds: Int, check: () -> Boolean): Boolean {
        repeat(seconds * 4) { if (check()) return true; Thread.sleep(250) }
        return check()
    }

    private fun ourNotification(): Boolean {
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.activeNotifications.any { it.id == MediaPlaybackService.NOTIFICATION_ID }
    }

    @Test
    fun songKeepsPlayingInBackgroundWithNotificationControls() {
        val song = Base64.encodeToString(inst.context.assets.open("song.webm").readBytes(), Base64.NO_WRAP)
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                a.loadHtmlForTest(
                    "<html><head><title>Test song - YouTube</title></head><body>" +
                        "<video id=v playsinline src='data:video/webm;base64,$song'></video></body></html>",
                    "https://m.youtube.com/ronin-test"
                )
            }
            Thread.sleep(3000)
            eval(sc, "document.getElementById('v').play(); 1")
            assertTrue("video did not start", waitFor(10) {
                eval(sc, "document.getElementById('v').currentTime").toDoubleOrNull()?.let { it > 0.5 } == true
            })
            assertTrue("no 'playing' notification", waitFor(10) { ourNotification() })

            // swipe away / screen off: the app goes to the background
            sc.moveToState(Lifecycle.State.CREATED)
            val t1 = eval(sc, "document.getElementById('v').currentTime").toDouble()
            Thread.sleep(6000)
            val t2 = eval(sc, "document.getElementById('v').currentTime").toDouble()
            assertTrue("song stopped in the background ($t1 -> $t2)", t2 > t1 + 3)
            assertEquals("page thinks it is hidden", "false", eval(sc, "document.hidden"))
            assertTrue("notification gone in background", ourNotification())

            // the notification's Pause button pauses the song
            app.startService(Intent(app, MediaPlaybackService::class.java).setAction(MediaPlaybackService.ACTION_PLAY_PAUSE))
            assertTrue("pause button did not pause", waitFor(8) { eval(sc, "document.getElementById('v').paused") == "true" })
            // and Play goes on
            app.startService(Intent(app, MediaPlaybackService::class.java).setAction(MediaPlaybackService.ACTION_PLAY_PAUSE))
            assertTrue("play button did not play", waitFor(8) { eval(sc, "document.getElementById('v').paused") == "false" })

            // Close: song pauses, notification goes away
            app.startService(Intent(app, MediaPlaybackService::class.java).setAction(MediaPlaybackService.ACTION_CLOSE))
            assertTrue("close did not pause", waitFor(8) { eval(sc, "document.getElementById('v').paused") == "true" })
            assertTrue("notification still there after Close", waitFor(8) { !ourNotification() })
        }
    }

    @Test
    fun bankOpensInSafeMoneyWithScreenshotsBlocked() {
        val monitor: Instrumentation.ActivityMonitor = inst.addMonitor(SafeMoneyActivity::class.java.name, null, false)
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a -> a.go("sbi.bank.in") }
            val safe: Activity? = inst.waitForMonitorWithTimeout(monitor, 10000)
            assertNotNull("bank did not open in Safe Money", safe)
            val flags = safe!!.window.attributes.flags
            assertTrue("screenshots not blocked", flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            safe.finish()
        }
    }

    @Test
    fun adsAreBlockedOnAPage() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                a.loadHtmlForTest(
                    "<html><body><script>window.r='wait';var s=document.createElement('script');" +
                        "s.onload=function(){window.r='loaded'};s.onerror=function(){window.r='blocked'};" +
                        "s.src='https://securepubads.g.doubleclick.net/tag/js/gpt.js';document.body.appendChild(s);</script></body></html>",
                    "https://news.example.com/"
                )
            }
            assertTrue("ad request was not blocked", waitFor(15) {
                val r = eval(sc, "window.r")
                r.contains("blocked")
            })
        }
    }
}
