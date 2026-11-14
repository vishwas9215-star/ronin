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
            androidx.test.uiautomator.UiDevice.getInstance(inst).pressHome()
            Thread.sleep(1500)
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

            // Next song while in the background: the player pauses for a moment, a new
            // video loads and must start without opening the app
            eval(sc, "document.getElementById('v').pause(); 1")
            Thread.sleep(3000)
            eval(sc, "var o=document.getElementById('v'),n=document.createElement('video');" +
                "n.setAttribute('playsinline','');n.src=o.src;o.remove();n.id='v';document.body.appendChild(n);n.play();1")
            assertTrue("next song did not play in the background", waitFor(15) {
                eval(sc, "document.getElementById('v').currentTime").toDoubleOrNull()?.let { it > 1.0 } == true
            })

            // Close: song pauses, notification goes away
            app.startService(Intent(app, MediaPlaybackService::class.java).setAction(MediaPlaybackService.ACTION_CLOSE))
            assertTrue("close did not pause", waitFor(8) { eval(sc, "document.getElementById('v').paused") == "true" })
            assertTrue("notification still there after Close", waitFor(8) { !ourNotification() })
        }
    }

    @Test
    fun addressBarIsBelowTheStatusBar() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            Thread.sleep(1500)
            var top = -1
            var bar = 0
            sc.onActivity { a ->
                val loc = IntArray(2)
                a.addressForTest().getLocationOnScreen(loc)
                top = loc[1]
                val ins = androidx.core.view.ViewCompat.getRootWindowInsets(a.window.decorView)
                bar = ins?.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars())?.top ?: 0
            }
            assertTrue("address bar ($top) is under the status bar ($bar)", top >= bar)
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

    private fun pageText(sc: ActivityScenario<MainActivity>) = eval(sc, "document.body ? document.body.innerText : ''")

    @Test
    fun dangerousAndFakeBankSitesAreStoppedWithARedPage() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            Thread.sleep(1500)
            sc.onActivity { a -> a.go("https://ronin-phishing-test.com/login") }
            assertTrue("danger site not stopped", waitFor(15) { pageText(sc).contains("खतरनाक साइट रोकी गई") })
            assertTrue(pageText(sc).contains("ronin-phishing-test.com"))
            // the only button goes back to safety
            eval(sc, "document.getElementById('back').click(); 1")
            assertTrue("go back did not leave the red page", waitFor(10) { !pageText(sc).contains("खतरनाक") })

            sc.onActivity { a -> a.go("https://sbi-kyc-update.xyz/") }
            assertTrue("fake bank site not stopped", waitFor(15) { pageText(sc).contains("नकली bank साइट") })
            assertTrue(pageText(sc).contains("SBI"))
        }
    }

    @Test
    fun tabsSitSideBySideWithANewTabButton() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            Thread.sleep(1500)
            var newTabBtn: android.view.View? = null
            sc.onActivity { a -> newTabBtn = a.newTabButtonForTest() }
            assertNotNull("no + (new tab) button on screen", newTabBtn)
            sc.onActivity { newTabBtn!!.performClick() }
            Thread.sleep(800)
            sc.onActivity { a ->
                val strip = a.tabStripForTest()
                val chips = (0 until strip.childCount).map { strip.getChildAt(it) }.filter { it.tag == "tab" }
                assertEquals("two tabs should show", 2, chips.size)
                assertTrue("tabs are not visible", chips.all { it.isShown && it.width > 0 })
                val l0 = IntArray(2); val l1 = IntArray(2)
                chips[0].getLocationOnScreen(l0); chips[1].getLocationOnScreen(l1)
                assertEquals("tabs are not on one row", l0[1], l1[1])
                assertTrue("tabs overlap", l1[0] >= l0[0] + chips[0].width)
                // tapping the first tab selects it
                chips[0].performClick()
            }
            Thread.sleep(500)
            sc.onActivity { a ->
                val strip = a.tabStripForTest()
                assertEquals(2, strip.childCount)
                assertTrue("+ is not visible", a.newTabButtonForTest().isShown)
            }
        }
    }

    /** A real screenshot of the emulator screen, kept for checking by eye (CI uploads them). */
    private fun shot(name: String) {
        Thread.sleep(700)
        fun sh(c: String) = inst.uiAutomation.executeShellCommand(c).use { pfd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
        }
        sh("mkdir -p /data/local/tmp/qa")
        sh("screencap -p /data/local/tmp/qa/$name.png")
    }

    @Test
    fun screensForCheckingByEye() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            Thread.sleep(2500)
            shot("01-home")
            var plus: android.view.View? = null
            sc.onActivity { a -> plus = a.newTabButtonForTest() }
            sc.onActivity { plus!!.performClick() }
            sc.onActivity { a -> a.loadHtmlForTest("<html><head><title>Wikipedia - test page</title></head><body style='font:20px sans-serif'><h1>Wikipedia</h1><p>Second tab</p></body></html>", "https://hi.wikipedia.org/") }
            Thread.sleep(1500)
            shot("02-two-tabs")
            sc.onActivity { a -> a.go("https://ronin-phishing-test.com/") }
            waitFor(15) { pageText(sc).contains("खतरनाक") }
            shot("03-danger-page")
            sc.onActivity { a -> a.go("https://sbi-kyc-update.xyz/") }
            waitFor(15) { pageText(sc).contains("नकली") }
            shot("04-fake-bank")
            sc.onActivity { a -> a.showMenuForTest() }
            shot("05-menu")
        }
    }

    @Test
    fun quickLinksAreSmallAndCanBeAddedFromTheNewTabPage() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            Thread.sleep(2000)
            assertTrue("school links missing", waitFor(10) { pageText(sc).contains("UDISE+ All Modules") && pageText(sc).contains("मानव संपदा") })
            assertTrue("addresses must not show", !pageText(sc).contains("https://"))
            val h = eval(sc, "Math.max.apply(null, [].map.call(document.querySelectorAll('#ql .chip'), function(c){return c.getBoundingClientRect().height}))").toDoubleOrNull() ?: 99.0
            assertTrue("quick links too big ($h)", h <= 48)
            shot("06-home-quick-links")
            eval(sc, "document.querySelector('.edbtn').click(); document.querySelector('#ql .chips[data-s=\"Trading\"] .add').click(); 1")
            eval(sc, "var f=document.querySelector('#ql form.new'); f.querySelectorAll('input')[0].value='Zerodha'; f.querySelectorAll('input')[1].value='kite.zerodha.com'; 1")
            shot("07-add-quick-link")
            eval(sc, "document.querySelector('#ql form.new button').click(); 1")
            assertTrue("added link not shown", waitFor(10) { pageText(sc).contains("Zerodha") })
            // a web page cannot add links (no key)
            sc.onActivity { a -> a.loadHtmlForTest("<a id=x href='https://ronin.newtab/add?k=bad&s=Trading&n=Evil&u=evil.example'>x</a>", "https://evil.example/") }
            Thread.sleep(1500)
            eval(sc, "document.getElementById('x').click(); 1")
            Thread.sleep(1500)
            sc.onActivity { a -> a.goHomeForTest() }
            Thread.sleep(1500)
            assertTrue("a web page added a link", !pageText(sc).contains("Evil"))
            // remove our test link again
            eval(sc, "document.querySelector('.edbtn').click(); [].filter.call(document.querySelectorAll('#ql .chip'), function(c){return c.textContent.indexOf('Zerodha')>=0})[0].querySelector('.x').click(); 1")
            assertTrue("remove did not work", waitFor(10) { !pageText(sc).contains("Zerodha") })
        }
    }

    @Test
    fun signInWorksAndSheetsOpenAsTheComputerVersion() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            Thread.sleep(1500)
            var phone = ""; var sheets = ""
            sc.onActivity { a -> phone = a.uaFor("https://mail.google.com/"); sheets = a.uaFor("https://docs.google.com/spreadsheets/d/x/edit") }
            assertTrue("Google refuses sign-in when the name says wv: $phone", !phone.contains("; wv") && phone.contains("Chrome/"))
            assertTrue("Sheets not the computer version: $sheets", sheets.contains("X11; Linux x86_64") && !sheets.contains("Mobile"))
            var wa = ""
            sc.onActivity { a -> wa = a.uaFor("https://web.whatsapp.com/") }
            assertTrue("WhatsApp Web shows the phone page: $wa", wa.contains("X11; Linux x86_64"))
            assertEquals("\"" + phone + "\"", eval(sc, "navigator.userAgent"))
        }
    }

    @Test
    fun dataButtonSavesExcelAndPdfOfEveryPage() {
        val names = listOf("Rahul Kumar", "Priya Singh", "Rahul Kumr", "Anita Devi", "Sunil Yadav", "Pooja  Verma", "Ravi2 Kumar", "Anita Devi", "Sita Ram", "Geeta Kumari", "Neha Gupta", "Karan Singh")
        val js = org.json.JSONArray(names).toString()
        val page = "<html><head><title>Student list test</title></head><body><table id=t><thead><tr><th>S.No.</th><th>Student Name</th><th>Class</th></tr></thead><tbody></tbody></table>" +
            "<ul class=pagination><li id=nl><a href='#' id=next>Next</a></li></ul><script>var k=$js,p=0;function d(){var b=document.querySelector('#t tbody');b.innerHTML='';" +
            "for(var i=p*5;i<Math.min(k.length,(p+1)*5);i++)b.insertAdjacentHTML('beforeend','<tr><td>'+(i+1)+'</td><td>'+k[i]+'</td><td>5</td></tr>');" +
            "document.getElementById('nl').className=(p+1)*5>=k.length?'disabled':''}document.getElementById('next').onclick=function(e){e.preventDefault();if((p+1)*5<k.length){p++;d()}};d();</script></body></html>"
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            Thread.sleep(1500)
            sc.onActivity { a -> a.loadHtmlForTest(page, "https://school.example/students") }
            Thread.sleep(1500)
            shot("08-data-page")
            val before = System.currentTimeMillis()
            sc.onActivity { a -> a.exportDataForTest() }
            var found = emptyList<Pair<String, Long>>()
            assertTrue("Excel + PDF not saved", waitFor(40) {
                found = recentDownloads(before)
                found.any { it.first.endsWith(".xlsx") && it.second > 500 } && found.any { it.first.endsWith(".pdf") && it.second > 500 }
            })
            assertTrue("files are empty: $found", found.all { it.second > 500 })
            Thread.sleep(800)
            shot("09-data-saved")
        }
    }

    @Test
    fun mergePageJoinsTwoPortalsIntoExcelAndPdf() {
        // two small made-up lists, like Prerna and UDISE+ (one child's mother name differs, one child only in Prerna)
        val prerna = "Sr.No,Student Name,Father Name,Mother Name,Student DOB,Gender\\n1,Asha Devi,Ram Lal,Sita,01/02/2016,Female\\n2,Vinod Kumar,Shyam Lal,Gita,05/06/2015,Male\\n3,Ravi,Mohan,Kamla,07/08/2017,Male\\n"
        val udise = "S.No.,Student Name,Father's Name,Mother's Name,Date of Birth,Gender\\n1,ASHA DEVI,RAM LAL,SITA,2016-02-01,F\\n2,VINOD KUMAR,SHYAM LAL,GEETA DEVI,2015-06-05,M\\n"
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            Thread.sleep(1500)
            sc.onActivity { a -> a.openMergeForTest() }
            Thread.sleep(2500)
            shot("10-merge-page")
            val before = System.currentTimeMillis()
            eval(sc, "(function(){var dt=new DataTransfer();dt.items.add(new File([\"$prerna\"],'prerna_list.csv',{type:'text/csv'}));" +
                "dt.items.add(new File([\"$udise\"],'udise_list.csv',{type:'text/csv'}));var i=document.getElementById('pick');i.files=dt.files;" +
                "i.dispatchEvent(new Event('change'));return 1})()")
            assertTrue("files not read", waitFor(15) { eval(sc, "document.getElementById('go').disabled") == "false" })
            shot("11-merge-files")
            eval(sc, "document.getElementById('go').click(),1")
            var found = emptyList<Pair<String, Long>>()
            assertTrue("merged Excel + PDF not saved", waitFor(40) {
                found = recentDownloads(before, "Prerna + UDISE+")
                found.any { it.first.endsWith(".xlsx") && it.second > 500 } && found.any { it.first.endsWith(".pdf") && it.second > 500 }
            })
            Thread.sleep(800)
            shot("12-merge-saved")
        }
    }

    @Test
    fun pdfToolsWorkOnThisDevice() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            Thread.sleep(1500)
            sc.onActivity { a -> a.openToolsForTest() }
            assertTrue("tools page did not open", waitFor(20) { eval(sc, "document.querySelectorAll('.tile').length") == "11" })
            shot("13-tools")
            val before = System.currentTimeMillis()
            eval(sc, "window.__roninToolsSelfTest().then(function(r){window.__selfTest=r},function(e){window.__selfTest='error '+e.message}),1")
            assertTrue("self test: " + eval(sc, "window.__selfTest"), waitFor(60) { eval(sc, "window.__selfTest||''").contains("ok 2") })
            var found = emptyList<Pair<String, Long>>()
            assertTrue("tools file not saved", waitFor(20) { found = recentDownloads(before, "Ronin tools self-test"); found.any { it.first.endsWith(".pdf") && it.second > 1000 } })
            Thread.sleep(800)
            shot("14-tools-saved")
        }
    }

    private fun recentDownloads(since: Long, prefix: String = "Student list test"): List<Pair<String, Long>> {
        if (Build.VERSION.SDK_INT < 29) return emptyList()
        val out = ArrayList<Pair<String, Long>>()
        app.contentResolver.query(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, android.provider.MediaStore.MediaColumns.SIZE, android.provider.MediaStore.MediaColumns.DATE_ADDED),
            null, null, null)?.use { c ->
            while (c.moveToNext()) if (c.getLong(2) * 1000 >= since - 2000 && c.getString(0).startsWith(prefix)) out.add(c.getString(0) to c.getLong(1))
        }
        return out
    }

}
