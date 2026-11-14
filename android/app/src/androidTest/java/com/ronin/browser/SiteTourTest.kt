package com.ronin.browser

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runs after DeviceTest: real sites of every kind the owner uses, with screenshots. */
@RunWith(AndroidJUnit4::class)
class SiteTourTest {
    private val inst = InstrumentationRegistry.getInstrumentation()

    private fun shot(name: String) {
        Thread.sleep(700)
        fun sh(c: String) = inst.uiAutomation.executeShellCommand(c).use { pfd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
        }
        sh("mkdir -p /data/local/tmp/qa")
        sh("screencap -p /data/local/tmp/qa/$name.png")
    }

    /** A tour of real sites of every kind the owner uses: each must open (or be stopped on purpose) without a crash. */
    @Test
    fun siteTour() {
        val sites = listOf(
            "music" to "https://music.youtube.com/", "youtube" to "https://m.youtube.com/", "gmail" to "https://mail.google.com/",
            "sheets" to "https://docs.google.com/spreadsheets/", "whatsapp" to "https://web.whatsapp.com/",
            "manav-sampada" to "https://ehrms.upsdc.gov.in/", "prerna" to "https://prernaup.in/", "udise" to "https://udiseplus.gov.in/#/en/page/oldlink/109",
            "diksha" to "https://diksha.gov.in/", "wikipedia" to "https://hi.wikipedia.org/", "fyers" to "https://trade.fyers.in/",
            "news" to "https://www.amarujala.com/", "downloads" to "https://archive.org/", "chat" to "https://chatgpt.com/",
        )
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            Thread.sleep(1500)
            sites.forEachIndexed { i, (name, url) ->
                sc.onActivity { a -> a.go(url) }
                Thread.sleep(7000)
                var alive = false
                sc.onActivity { a -> alive = !a.isFinishing }
                assertTrue("app closed on $name", alive)
                shot("tour-%02d-%s".format(i + 1, name))
                sc.onActivity { a -> a.goHomeForTest() } // let the page go before the next one (the test phone has little memory)
                Thread.sleep(1000)
            }
        }
    }
}
