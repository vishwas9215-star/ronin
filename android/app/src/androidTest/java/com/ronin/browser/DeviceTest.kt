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
import org.junit.Assert.assertFalse
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

    init {
        // the "which parts?" question of the first start does not cover the tests
        InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("ronin_parts", Context.MODE_PRIVATE).edit().putBoolean("asked", true).putBoolean("adultAsked", true).commit()
        // 3.14 keeps the open tabs for the next start: every test starts with one fresh tab
        InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("ronin", Context.MODE_PRIVATE).edit().remove("savedTabs").remove("claudeView").commit()
    }

    /** The emulator's own "Pixel Launcher isn't responding" box can cover the screen and eat a real tap / swipe. */
    private fun noSystemDialogs() {
        val d = androidx.test.uiautomator.UiDevice.getInstance(inst)
        repeat(3) { d.findObject(androidx.test.uiautomator.By.text("Wait"))?.let { it.click(); Thread.sleep(600) } }
    }

    private fun findTag(v: android.view.View?, tag: String): android.view.View? {
        if (v == null) return null
        if (v.tag == tag) return v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) findTag(v.getChildAt(i), tag)?.let { return it }
        return null
    }

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
    fun addressBarIsAtTheBottomAndTheMenuSlidesUp() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            Thread.sleep(1500)
            var barTop = 0; var webBottom = 0; var screen = 0
            sc.onActivity { a ->
                val loc = IntArray(2)
                a.addressForTest().getLocationOnScreen(loc); barTop = loc[1]
                val w = a.webForTest(); w.getLocationOnScreen(loc); webBottom = loc[1] + w.height
                screen = a.window.decorView.height
            }
            assertTrue("address bar ($barTop) must be below the page ($webBottom)", barTop >= webBottom - 2)
            assertTrue("address bar must be in the bottom part of the screen ($barTop of $screen)", barTop > screen / 2)
            sc.onActivity { a -> a.showMenuForTest() }
            Thread.sleep(800)
            var bottom = 0; var h = 0; var rows = 0
            sc.onActivity { a ->
                val d = a.menuSheetForTest()!!
                assertTrue("menu not showing", d.isShowing)
                val v = d.window!!.decorView
                val loc = IntArray(2); v.getLocationOnScreen(loc); bottom = loc[1] + v.height; h = v.height
                fun count(x: android.view.View): Int = if (x is android.view.ViewGroup) (0 until x.childCount).sumOf { count(x.getChildAt(it)) } + (if ((x.tag as? String)?.startsWith("m-") == true) 1 else 0) else 0
                rows = count(v)
                // every row / button is at least 56 dp tall: easy to hit
                fun small(x: android.view.View): Boolean = if (x is android.view.ViewGroup) (0 until x.childCount).any { small(x.getChildAt(it)) } || ((x.tag as? String)?.startsWith("m-") == true && x.height < a.resources.displayMetrics.density * 56) else false
                assertTrue("a menu row is smaller than 56 dp", !small(v))
            }
            assertTrue("menu must sit on the bottom edge ($bottom of $screen)", bottom >= screen * 0.85)
            assertTrue("menu has too few items ($rows)", rows >= 8)
            shot("05b-menu-sheet")
            sc.onActivity { a -> a.menuSheetForTest()!!.dismiss() }
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
            assertTrue("danger site not stopped", waitFor(15) { pageText(sc).contains(L("खतरनाक साइट रोकी गई", "Dangerous site blocked")) })
            assertTrue(pageText(sc).contains("ronin-phishing-test.com"))
            // the only button goes back to safety
            eval(sc, "document.getElementById('back').click(); 1")
            assertTrue("go back did not leave the red page", waitFor(10) { !pageText(sc).contains(L("खतरनाक", "Dangerous")) })

            sc.onActivity { a -> a.go("https://sbi-kyc-update.xyz/") }
            assertTrue("fake bank site not stopped", waitFor(15) { pageText(sc).contains(L("नकली bank साइट", "Fake bank site")) })
            assertTrue(pageText(sc).contains("SBI"))
        }
    }

    @Test
    fun tabsSitSideBySideWithANewTabButton() {
        // phones have no tab strip any more (3.13): the tab count at the bottom opens them; tablets keep the strip
        org.junit.Assume.assumeTrue(app.resources.configuration.smallestScreenWidthDp >= 600)
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
                val lp = IntArray(2); val lc = IntArray(2)
                a.newTabButtonForTest().getLocationOnScreen(lp); strip.getChildAt(0).getLocationOnScreen(lc)
                assertTrue("+ should be on the left of the tabs", lp[0] < lc[0])
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
            waitFor(15) { pageText(sc).contains(L("खतरनाक", "Dangerous")) }
            shot("03-danger-page")
            sc.onActivity { a -> a.go("https://sbi-kyc-update.xyz/") }
            waitFor(15) { pageText(sc).contains(L("नकली", "Fake")) }
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
    fun dataMatchJoinsTwoPortalsIntoExcelAndPdf() {
        // two small made-up lists, like Prerna and UDISE+ (one child's mother name differs, one child only in Prerna)
        val prerna = "Sr.No,Student Name,Father Name,Mother Name,Student DOB,Gender\\n1,Asha Devi,Ram Lal,Sita,01/02/2016,Female\\n2,Vinod Kumar,Shyam Lal,Gita,05/06/2015,Male\\n3,Ravi,Mohan,Kamla,07/08/2017,Male\\n"
        val udise = "S.No.,Student Name,Father's Name,Mother's Name,Date of Birth,Gender\\n1,ASHA DEVI,RAM LAL,SITA,2016-02-01,F\\n2,VINOD KUMAR,SHYAM LAL,GEETA DEVI,2015-06-05,M\\n"
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            Thread.sleep(1500)
            sc.onActivity { a -> a.openToolsForTest() }
            assertTrue("tools page did not open", waitFor(20) { eval(sc, "document.querySelectorAll('.tool').length") == "20" })
            eval(sc, "window.__roninToolsOpen('datamatch'),1")
            Thread.sleep(800)
            shot("10-datamatch-page")
            val before = System.currentTimeMillis()
            // the file picker is the system's: hand the two files to the page's own picker instead
            eval(sc, "(function(){var dt=new DataTransfer();dt.items.add(new File([\"$prerna\"],'prerna_list.csv',{type:'text/csv'}));" +
                "dt.items.add(new File([\"$udise\"],'udise_list.csv',{type:'text/csv'}));var i=document.getElementById('pick');" +
                "i.click=function(){i.files=dt.files;i.dispatchEvent(new Event('change'))};document.querySelector('button.big').click();return 1})()")
            assertTrue("files not read", waitFor(15) { eval(sc, "document.querySelector('button.big.go').disabled") == "false" })
            shot("11-datamatch-files")
            eval(sc, "document.querySelector('button.big.go').click(),1")
            var found = emptyList<Pair<String, Long>>()
            assertTrue("matched Excel + PDF not saved", waitFor(40) {
                found = recentDownloads(before, "Prerna_UDISE")
                found.any { it.first.endsWith(".xlsx") && it.second > 500 } && found.any { it.first.endsWith(".pdf") && it.second > 500 }
            })
            Thread.sleep(800)
            shot("12-datamatch-saved")
        }
    }

    private fun shell(c: String): String = inst.uiAutomation.executeShellCommand(c).use { pfd ->
        android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { String(it.readBytes()) }
    }
    @Test
    fun scamCheckOnThePhoneMatchesThePage() {
        // Android's own regex engine (ICU) must judge the texts exactly like the tests on the computer
        val rules = (app.applicationContext as RoninApp).rules
        val bad = ArrayList<String>()
        for (f in listOf("scam_corpus.json", "scam_heldout.json")) {
            val c = org.json.JSONObject(inst.context.assets.open(f).bufferedReader().readText())
            val scam = c.getJSONArray("scam"); val normal = c.getJSONArray("normal")
            for (i in 0 until scam.length()) if (rules.scam.text(scam.getString(i)).level.isEmpty()) bad.add("missed: " + scam.getString(i))
            for (i in 0 until normal.length()) if (rules.scam.text(normal.getString(i)).level.isNotEmpty()) bad.add("false alarm: " + normal.getString(i))
        }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun instagramAndFacebookVideosNeverPlayInTheBackground() {
        // Instagram / Facebook videos never keep playing (and the phone awake) in the background; songs do
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                assertTrue(a.noBackgroundPlayForTest("https://www.instagram.com/reels/abc/"))
                assertTrue(a.noBackgroundPlayForTest("https://m.facebook.com/watch"))
                assertTrue(!a.noBackgroundPlayForTest("https://m.youtube.com/watch?v=x"))
                assertTrue(!a.noBackgroundPlayForTest("https://music.youtube.com/"))
            }
        }
    }

    @Test
    fun theBrowserIsOnlyABrowser() {
        // 3.12: Social / Mail / SOS are gone for good. 3.13: Messages / Calls are back (off until the family
        // turns them on), but never location, never the WhatsApp watcher, never Mail on a phone / tablet
        val pm = app.packageManager
        val info = pm.getPackageInfo(app.packageName, android.content.pm.PackageManager.GET_PERMISSIONS or android.content.pm.PackageManager.GET_ACTIVITIES or android.content.pm.PackageManager.GET_SERVICES or android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS)
        val perms = info.requestedPermissions?.toList() ?: emptyList()
        for (p in listOf("android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION", "android.permission.FOREGROUND_SERVICE_LOCATION"))
            assertTrue("still asks for $p", p !in perms)
        val parts = (info.activities?.map { it.name } ?: emptyList()) + (info.services?.map { it.name } ?: emptyList())
        for (c in listOf("SosActivity", "MailActivity", "SocialActivity", "NotifyWatch"))
            assertTrue("$c is still in the app", parts.none { it.endsWith(".$c") })
        for (c in listOf("MessagesActivity", "CallsActivity"))
            assertTrue("$c is missing", parts.any { it.endsWith(".$c") })
        // switched off until chosen: no Messages / Calls icon on a fresh install
        val st = pm.getComponentEnabledSetting(android.content.ComponentName(app, "com.ronin.browser.MessagesLauncher"))
        if (!Parts.isOn(app, Parts.MESSAGES)) assertTrue("Messages icon shows before it was chosen", st != android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            Thread.sleep(2000)
            assertEquals("Safe Social row still on the new tab page", "0", eval(sc, "document.querySelectorAll('#ql .chips[data-s=\\\"Safe Social\\\"]').length"))
        }
    }

    private fun <A : PhonePage> evalPage(sc: ActivityScenario<A>, js: String): String {
        val latch = CountDownLatch(1); var out = ""
        sc.onActivity { a -> a.webForTest().evaluateJavascript(js) { out = it ?: ""; latch.countDown() } }
        latch.await(15, TimeUnit.SECONDS); return out
    }

    @Test
    fun roninMessagesSortsScamAndWorkSms() {
        if (!Parts.hasPhone(app)) return // a tablet without SIM never gets Ronin Messages
        Parts.set(app, Parts.MESSAGES, true)
        // (the role stays with Ronin afterwards: taking it away revokes the SMS permissions,
        // and Android then ends Ronin's process, which would end this test run too)
        val ops = shell("cmd appops set ${app.packageName} ACCESS_RESTRICTED_SETTINGS allow")
        Thread.sleep(1000)
        val out = shell("cmd role add-role-holder android.app.role.SMS ${app.packageName} 0") + shell("cmd role get-role-holders android.app.role.SMS")
        assertTrue("Ronin did not become the SMS app: $ops | $out", waitFor(15) { SmsStore.isDefault(app) })
        val now = System.currentTimeMillis()
        fun inbox(from: String, body: String) = app.contentResolver.insert(android.provider.Telephony.Sms.Inbox.CONTENT_URI, android.content.ContentValues().apply {
            put("address", from); put("body", body); put("date", now); put("read", 0); put("type", 1) })
        inbox("+919812300011", "Mujhe urgent 10000 rupaye ki zarurat hai, hospital me hoon, is QR par bhej do")
        inbox("JM-SBIINB-S", "482913 is your OTP for login. Never share it with anyone.")
        inbox("VM-SHOPPR-P", "Big sale! Flat 70% off today only, shop now")
        ActivityScenario.launch(MessagesActivity::class.java).use { sc ->
            assertTrue("messages page did not load", waitFor(20) { evalPage(sc, "window.__roninMessages?window.__roninMessages.list().length:0").toIntOrNull()?.let { it >= 3 } == true })
            val cats = evalPage(sc, "JSON.stringify(window.__roninMessages.list().map(t=>t.address+':'+t.cat))")
            assertTrue("scam SMS not in ⛔: $cats", cats.contains("+919812300011:scam"))
            assertTrue("bank OTP not in काम के: $cats", cats.contains("JM-SBIINB-S:work"))
            assertTrue("ad not in विज्ञापन: $cats", cats.contains("VM-SHOPPR-P:ads"))
            // 3.13: the new OTP is pinned on top, the four boxes are at the top
            assertEquals("new OTP not pinned", "1", evalPage(sc, "document.querySelectorAll('#otpCard').length"))
            shot("16a-ronin-messages")
            evalPage(sc, "document.querySelector('[data-tab=scam]').click(),1")
            shot("16-ronin-messages-scam")
        }
    }

    @Test
    fun simNameIsKeptForRecentAndThreatTipShown() {
        // CNAP: the network's KYC name of an unknown caller is kept for Recent (the fake "Daroga ji" case)
        CallInfo.keepCnap(app, "+91 98765 43210", "RAMESH KUMAR")
        assertEquals("RAMESH KUMAR", CallInfo.cnapOf(app, "9876543210"))
        assertTrue(CallInfo.threatTip().contains("Digital arrest") || CallInfo.threatTip().contains("digital arrest", ignoreCase = true))
    }

    @Test
    fun callButtonsShowWhatWasTappedAtOnce() {
        // 3.13.3 (owner: Speaker needed 2-3 taps): a tap shows its result at once, before Android confirms,
        // and on speaker the screen must not go dark near the face (no proximity lock)
        CallHub.pending = true
        assertTrue(CallHub.speakerOn()); assertFalse(CallHub.atEar())
        CallHub.pending = false
        assertFalse(CallHub.speakerOn())
        CallHub.pending = null
        CallHub.muted = true; assertTrue(CallHub.isMuted())
        CallHub.muted = false; assertFalse(CallHub.isMuted())
        CallHub.muted = null
    }

    @Test
    fun roninCallsOpensOnRecentAndHasAKeypad() {
        if (!Parts.hasPhone(app)) return
        Parts.set(app, Parts.CALLS, true)
        ActivityScenario.launch(CallsActivity::class.java).use { sc ->
            assertTrue("calls page did not load", waitFor(20) { evalPage(sc, "document.querySelectorAll('[data-tab=recent].on').length") == "1" })
            shot("17a-ronin-calls-recent")
            // Block / Delete ask "are you sure?": the page's questions must reach the screen (3.11 fix)
            var chrome = false
            sc.onActivity { a -> chrome = a.webForTest().webChromeClient != null }
            assertTrue("phone pages cannot ask questions (no WebChromeClient)", chrome)
            evalPage(sc, "document.querySelector('[data-tab=pad]').click(),1")
            assertTrue("calls keypad did not load", waitFor(10) { evalPage(sc, "document.querySelectorAll('.pad button').length") == "12" })
            evalPage(sc, "document.querySelector('[data-key=\"1\"]').click(),1")
            shot("17-ronin-calls")
        }
    }

    @Test
    fun scamCheckPageGivesAVerdict() {
        // 3.12: the page used to borrow its engine from Safe Social; it must work on its own
        val msg = "Mujhe urgent 10000 rupaye ki zarurat hai, hospital me hoon, is QR par bhej do"
        val diag = "location.href.slice(0,60)+' | '+document.readyState+' | scam='+!!window.RoninScam+' rules='+!!window.__roninScamRules+' | res='+performance.getEntriesByType('resource').map(function(e){return e.name.split('/').pop().slice(0,20)+':'+Math.round(e.duration)}).join(',')"
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            Thread.sleep(1500)
            sc.onActivity { a -> a.openToolsForTest() }
            assertTrue("tools page did not open: " + eval(sc, diag), waitFor(30) { eval(sc, "document.querySelectorAll('.tool').length") == "20" })
            // inside the page (the menu's "Is this a scam?")
            eval(sc, "window.__errs=[];addEventListener('error',function(e){__errs.push(e.message)});addEventListener('unhandledrejection',function(e){__errs.push(String(e.reason))});1")
            val engine = eval(sc, "(function(){try{return RoninScam.text(" + org.json.JSONObject.quote(msg) + ").level||'none'}catch(e){return 'throws '+e.message}})()")
            eval(sc, "location.hash='scamcheck:'+encodeURIComponent(" + org.json.JSONObject.quote(msg) + "),1")
            assertTrue("no verdict inside the page: engine=$engine | " + eval(sc, diag + "+' | hash='+location.hash.slice(0,30)+' | errs='+__errs.join(';')+' | text='+document.body.innerText.slice(0,150)"), waitFor(20) { eval(sc, "document.getElementById('verdict')?1:0") == "1" })
            shot("16-scam-check")
            // a message shared to Ronin from WhatsApp / SMS
            sc.onActivity { a -> a.openTools("scamcheck:" + android.net.Uri.encode("Congratulations! You won an iPhone. Pay 499 delivery charge to claim, send OTP")) }
            assertTrue("no verdict for a shared message: " + eval(sc, diag), waitFor(30) { eval(sc, "(document.getElementById('verdict')&&document.querySelector('textarea').value.indexOf('iPhone')>=0)?1:0") == "1" })
        }
    }

    @Test
    fun aMessageSharedToRoninIsChecked() {
        // WhatsApp / SMS -> Share -> Ronin: the real way in, Ronin starting fresh
        val i = android.content.Intent(app, MainActivity::class.java).setAction(android.content.Intent.ACTION_SEND).setType("text/plain")
            .putExtra(android.content.Intent.EXTRA_TEXT, "Your KYC is blocked. Update now at http://sbi-kyc-update.top or account closed today")
        ActivityScenario.launch<MainActivity>(i).use { sc ->
            val diag = "location.href.slice(0,40)+' | '+document.readyState+' | res='+performance.getEntriesByType('resource').length"
            assertTrue("shared message not checked: " + eval(sc, diag), waitFor(75) { eval(sc, "document.getElementById('verdict')?1:0") == "1" })
        }
    }

    @Test
    fun adultSitesOpenInAPrivateTabWithoutHistory() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            Thread.sleep(1500)
            sc.onActivity { a -> a.go("https://www.freeporn-ronin-test.com/") }
            Thread.sleep(2500)
            var priv = false; var inHistory = true
            sc.onActivity { a -> priv = a.currentIsPrivateForTest(); inHistory = a.historyHasForTest("freeporn-ronin-test") }
            assertTrue("adult site did not open in a private tab", priv)
            assertTrue("adult site was written to the history", !inHistory)
        }
    }

    @Test
    fun pdfToolsWorkOnThisDevice() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            Thread.sleep(1500)
            var strip = 0
            sc.onActivity { a -> strip = a.tabStripForTest().childCount }
            sc.onActivity { a -> a.openToolsForTest() }
            assertTrue("tools page did not open", waitFor(20) { eval(sc, "document.querySelectorAll('.tool').length") == "20" })
            var after = 0
            sc.onActivity { a -> after = a.tabStripForTest().childCount }
            assertEquals("Ronin Tools must not show as a tab", strip, after)
            shot("13-tools")
            val before = System.currentTimeMillis()
            eval(sc, "window.__roninToolsSelfTest().then(function(r){window.__selfTest=r},function(e){window.__selfTest='error '+e.message}),1")
            assertTrue("self test: " + eval(sc, "window.__selfTest"), waitFor(60) { eval(sc, "window.__selfTest||''").contains("ok 2") })
            var found = emptyList<Pair<String, Long>>()
            assertTrue("tools file not saved", waitFor(20) { found = recentDownloads(before, "Ronin_tools_selftest"); found.any { it.first.endsWith(".pdf") && it.second > 1000 } })
            Thread.sleep(800)
            shot("14-tools-saved")
        }
    }

    private fun recentDownloads(since: Long, prefix: String = "Student_list_test"): List<Pair<String, Long>> {
        if (Build.VERSION.SDK_INT < 29) return emptyList()
        val out = ArrayList<Pair<String, Long>>()
        app.contentResolver.query(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, android.provider.MediaStore.MediaColumns.SIZE, android.provider.MediaStore.MediaColumns.DATE_ADDED),
            null, null, null)?.use { c ->
            while (c.moveToNext()) if (c.getLong(2) * 1000 >= since - 2000 && c.getString(0).startsWith(prefix)) out.add(c.getString(0) to c.getLong(1))
        }
        return out
    }


    // ---- 3.14: review fixes, battery, easier use ------------------------------------------------

    @Test
    fun aCrashedPageEngineReloadsTheTabInsteadOfClosingRonin() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            Thread.sleep(2500)
            var before: android.webkit.WebView? = null
            sc.onActivity { a -> before = a.webForTest(); a.crashPageForTest() }
            assertTrue("tab did not get a new page engine", waitFor(25) { var now: android.webkit.WebView? = null; sc.onActivity { a -> now = a.webForTest() }; now != null && now !== before })
            assertTrue("new tab page did not come back", waitFor(20) { pageText(sc).contains("Ronin Browser") })
            assertEquals(Lifecycle.State.RESUMED, sc.state)
        }
    }

    @Test
    fun openTabsComeBackAfterRoninIsClosed() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            Thread.sleep(1500)
            sc.onActivity { a -> a.newTab("https://example.com/", select = false, lazy = true); a.newTab("https://example.org/", select = false, lazy = true) }
        }
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            Thread.sleep(1500)
            var urls = emptyList<String>()
            sc.onActivity { a -> urls = a.tabUrlsForTest() }
            assertTrue("tabs not restored: $urls", urls.size == 3 && "https://example.com/" in urls && "https://example.org/" in urls)
        }
    }

    @Test
    fun aClosedTabComesBackWithUndo() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            Thread.sleep(1500)
            sc.onActivity { a -> a.newTab("https://example.net/", select = false, lazy = true); a.closeTabForTest("https://example.net/") }
            var undo: android.view.View? = null
            sc.onActivity { a -> undo = findTag(a.undoBarForTest(), "undo"); assertTrue("no Undo bar", a.undoBarForTest().visibility == android.view.View.VISIBLE) }
            shot("21-undo-close-tab")
            sc.onActivity { undo!!.performClick() }
            var urls = emptyList<String>()
            sc.onActivity { a -> urls = a.tabUrlsForTest() }
            assertTrue("Undo did not bring the tab back: $urls", "https://example.net/" in urls)
        }
    }

    @Test
    fun noInternetOrNoHttpsShowsReloadAndOpenAnyway() {
        assertTrue("router pages (http) are blocked", android.security.NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted("192.168.1.1"))
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            Thread.sleep(1500)
            sc.onActivity { a -> a.go("ronin-no-such-site.invalid") }
            assertTrue("no Ronin error page: " + pageText(sc).take(200), waitFor(25) { eval(sc, "document.getElementById('reload')?1:0") == "1" })
            assertEquals("'Open anyway' offered for a site that does not exist at all", "0", eval(sc, "document.getElementById('http')?1:0"))
            // Reload goes back to the same address (and fails again here)
            eval(sc, "document.getElementById('reload').click(),1")
            assertTrue(waitFor(25) { eval(sc, "document.getElementById('reload')?1:0") == "1" })
            var url = ""
            sc.onActivity { a -> url = a.currentUrlForTest() }
            assertTrue("reload lost the address: $url", url.contains("ronin-no-such-site.invalid"))
            // a site without https: "Open anyway (not secure)" opens the plain http address
            sc.onActivity { a -> a.httpErrorPageForTest("https://ronin-old-portal.invalid/form") }
            assertTrue("no 'Open anyway' for a site without https", waitFor(15) { eval(sc, "document.getElementById('http')?1:0") == "1" })
            shot("20-error-page")
            eval(sc, "document.getElementById('http').click(),1")
            assertTrue("'Open anyway' did not open the http address", waitFor(10) { sc.onActivity { a -> url = a.currentUrlForTest() }; url == "http://ronin-old-portal.invalid/form" })
        }
    }

    @Test
    fun aPageCannotOpenAnotherAppByItself() {
        val filter = android.content.IntentFilter(Intent.ACTION_VIEW).apply { addDataScheme("tel") }
        val mon = inst.addMonitor(filter, null, true)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { sc ->
                Thread.sleep(1500)
                sc.onActivity { a -> a.loadHtmlForTest("<html><body><script>setTimeout(function(){location.href='tel:1234567890'},300)</script>ad</body></html>", "https://ads.example.com/") }
                var asked = false
                assertTrue("Ronin did not ask before opening another app", waitFor(15) { sc.onActivity { a -> asked = a.lastAskForTest()?.isShowing == true }; asked })
                assertEquals("another app was opened without a tap", 0, mon.hits)
                sc.onActivity { a -> a.lastAskForTest()?.dismiss() }
            }
        } finally { inst.removeMonitor(mon) }
    }

    @Test
    fun filesMadeInsideThePageAreSaved() {
        val csv = Base64.encodeToString("Name,Class\nAsha,5\n".toByteArray(), Base64.NO_WRAP)
        val page = "<html><body style='font:20px sans-serif'><a id=d href='data:text/csv;base64,$csv' download='ronin_dl_data.csv'>data file</a><br>" +
            "<a id=b download='ronin_dl_blob.csv'>blob file</a><script>document.getElementById('b').href=URL.createObjectURL(new Blob(['Name,Class\\nRavi,4\\n'],{type:'text/csv'}))</script></body></html>"
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            Thread.sleep(1500)
            sc.onActivity { a -> a.loadHtmlForTest(page, "https://portal.example.gov.in/") }
            Thread.sleep(1500)
            val before = System.currentTimeMillis()
            eval(sc, "document.getElementById('d').click(),1")
            assertTrue("data: file not saved", waitFor(20) { recentDownloads(before, "ronin_dl_data").any { it.second > 5 } })
            Thread.sleep(1000)
            eval(sc, "document.getElementById('b').click(),1")
            assertTrue("blob: file not saved", waitFor(25) { recentDownloads(before, "ronin_dl_blob").any { it.second > 5 } })
            // an app file (.apk) made inside a page never gets past the app warning: not saved at all
            eval(sc, "var x=document.createElement('a');x.href='data:application/octet-stream;base64,UEsDBA==';x.download='ronin_dl_app.apk';document.body.appendChild(x);x.click();" +
                "var y=document.createElement('a');y.href=URL.createObjectURL(new Blob(['PK'],{type:'application/vnd.android.package-archive'}));y.download='ronin_dl_app2.apk';document.body.appendChild(y);setTimeout(function(){y.click()},1500);1")
            Thread.sleep(8000)
            assertTrue("an .apk from inside a page was saved", recentDownloads(before, "ronin_dl_app").isEmpty())
        }
    }

    @Test
    fun longPressMenuOpensALinkInANewTab() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            Thread.sleep(1500)
            var n = 0
            sc.onActivity { a -> n = a.tabUrlsForTest().size; a.showLinkMenu(null, "https://example.com/long-press", null) }
            Thread.sleep(800)
            shot("22-link-menu")
            sc.onActivity { a ->
                val d = a.linkSheetForTest()!!
                assertTrue(d.isShowing)
                for (t in listOf("l-newtab", "l-copy", "l-share")) assertNotNull("$t missing", findTag(d.window!!.decorView, t))
                findTag(d.window!!.decorView, "l-newtab")!!.performClick()
            }
            var urls = emptyList<String>()
            sc.onActivity { a -> urls = a.tabUrlsForTest() }
            assertEquals(n + 1, urls.size)
            assertTrue("https://example.com/long-press" in urls)
        }
    }

    @Test
    fun menuHasTextSizeFindShareAndDesktopSite() {
        val store = (app.applicationContext as RoninApp).store
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            Thread.sleep(1500)
            sc.onActivity { a -> a.loadHtmlForTest("<html><head><title>Text size test</title></head><body><p>Ronin text size test. Find me.</p></body></html>", "https://textsize.example/") }
            Thread.sleep(1200)
            sc.onActivity { a -> a.showMenuForTest() }
            Thread.sleep(800)
            sc.onActivity { a ->
                val v = a.menuSheetForTest()!!.window!!.decorView
                for (t in listOf("m-share", "m-find", "m-desktop", "m-textdown", "m-textup")) assertNotNull("$t missing in the menu", findTag(v, t))
                findTag(v, "m-textup")!!.performClick(); findTag(v, "m-textup")!!.performClick()
                assertTrue("A+ closed the menu", a.menuSheetForTest()!!.isShowing)
                assertEquals(130, a.webForTest().settings.textZoom)
            }
            assertEquals("text size not remembered for the site", 130, store.textZoom("textsize.example"))
            shot("23-menu-text-size")
            sc.onActivity { a ->
                findTag(a.menuSheetForTest()!!.window!!.decorView, "m-find")!!.performClick()
                assertEquals(android.view.View.VISIBLE, a.findBarForTest().visibility)
            }
            Thread.sleep(800)
            shot("24-find-in-page")
            store.setTextZoom("textsize.example", 100)
        }
    }

    @Test
    fun addressBarSuggestsBookmarksAndHistory() {
        val store = (app.applicationContext as RoninApp).store
        if (!store.isBookmarked("https://suggest-test.example/page")) store.toggleBookmark("Ronin suggest test", "https://suggest-test.example/page")
        try {
            ActivityScenario.launch(MainActivity::class.java).use { sc ->
                Thread.sleep(1500)
                sc.onActivity { a -> a.typeForTest("sugg") }
                Thread.sleep(800)
                var rows = 0
                sc.onActivity { a -> val b = a.suggestBoxForTest(); rows = (0 until b.childCount).count { b.getChildAt(it).tag == "sug" } }
                assertTrue("no suggestion for a bookmark", rows >= 1)
                shot("25-suggestions")
            }
        } finally { store.removeBookmark("https://suggest-test.example/page") }
    }

    @Test
    fun myBankAsksFirstAndSafeSearchCoversTypedSearches() {
        val store = (app.applicationContext as RoninApp).store
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            Thread.sleep(1500)
            sc.onActivity { a -> a.loadHtmlForTest("<html><body>not a bank</body></html>", "https://notabank.example/") }
            Thread.sleep(800)
            sc.onActivity { a -> a.showMenuForTest() }
            Thread.sleep(800)
            sc.onActivity { a -> findTag(a.menuSheetForTest()!!.window!!.decorView, "m-bank")!!.performClick() }
            Thread.sleep(500)
            sc.onActivity { a -> assertTrue("no question before 'my bank'", a.lastAskForTest()?.isShowing == true); a.lastAskForTest()?.dismiss() }
            assertFalse("added as a bank with one tap", "notabank.example" in store.userBanks)
            // Family Filter: a typed search is SafeSearch too
            val fam = app.getSharedPreferences("ronin_parts", Context.MODE_PRIVATE)
            val was = fam.getBoolean("adultBlock", false)
            fam.edit().putBoolean("adultBlock", true).commit()
            try {
                var url = ""
                sc.onActivity { a -> a.go("ronin safe search test"); url = a.currentUrlForTest() }
                assertTrue("typed search without SafeSearch: $url", url.contains("safe=active"))
            } finally { fam.edit().putBoolean("adultBlock", was).commit() }
        }
    }

    @Test
    fun closingRoninEndsTheSongCard() {
        val song = Base64.encodeToString(inst.context.assets.open("song.webm").readBytes(), Base64.NO_WRAP)
        val sc = ActivityScenario.launch(MainActivity::class.java)
        sc.onActivity { a -> a.loadHtmlForTest("<html><head><title>Close test - YouTube</title></head><body><video id=v loop playsinline src='data:video/webm;base64,$song'></video></body></html>", "https://m.youtube.com/ronin-close-test") }
        Thread.sleep(3000)
        eval(sc, "document.getElementById('v').play(); 1")
        assertTrue("no 'playing' notification", waitFor(15) { ourNotification() })
        sc.close() // like a swipe-away: the page is gone
        assertTrue("player card (and its wake lock) left behind after Ronin closed", waitFor(10) { !ourNotification() })
    }

    @Test
    fun historyIsWrittenWithoutStoppingTheScreen() {
        val store = (app.applicationContext as RoninApp).store
        var took = 0L
        inst.runOnMainSync {
            val t0 = System.nanoTime()
            for (i in 0 until 40) store.addHistory("Ronin history test $i", "https://history-test.example/$i")
            took = (System.nanoTime() - t0) / 1_000_000
        }
        assertTrue("adding history blocked the screen for $took ms", took < 150)
        assertTrue("history not saved", waitFor(10) { store.history.any { it.url == "https://history-test.example/39" } })
        store.removeHistory { it.startsWith("https://history-test.example/") }
    }

    @Test
    fun updateCarriesOnByItselfAfterInstallPermission() {
        // note: taking "install unknown apps" AWAY kills the app (and this test), so the test only ever allows it
        val f = java.io.File(java.io.File(app.cacheDir, "updates").apply { mkdirs() }, "Ronin.apk").apply { writeBytes(ByteArray(16)) }
        val filter = android.content.IntentFilter(Intent.ACTION_VIEW).apply { addDataType("application/vnd.android.package-archive") }
        val mon = inst.addMonitor(filter, null, true)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { sc ->
                Thread.sleep(1000)
                Updater.waitingApk = f
                var allowed = false
                sc.onActivity { a -> allowed = a.packageManager.canRequestPackageInstalls(); Updater.resume(a) }
                if (!allowed) {
                    assertEquals("installer opened without the permission", 0, mon.hits)
                    assertNotNull("the waiting update was forgotten", Updater.waitingApk)
                    shell("appops set ${app.packageName} REQUEST_INSTALL_PACKAGES allow")
                    assertTrue("permission not given", waitFor(5) { var ok = false; sc.onActivity { a -> ok = a.packageManager.canRequestPackageInstalls() }; ok })
                    sc.onActivity { a -> Updater.resume(a) }
                }
                assertTrue("update did not carry on by itself", waitFor(5) { mon.hits == 1 })
                assertTrue(Updater.waitingApk == null)
            }
        } finally {
            inst.removeMonitor(mon); Updater.waitingApk = null; f.delete()
        }
    }

    @Test
    fun pullingThePageDownReloadsIt() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            Thread.sleep(1500)
            sc.onActivity { a -> a.loadHtmlForTest("<html><body style='margin:0'><div style='height:3000px;font:22px sans-serif'>Pull test</div><script>window.mark=1</script></body></html>", "https://pull.example/") }
            Thread.sleep(1500)
            assertEquals("1", eval(sc, "window.mark"))
            eval(sc, "window.notReloaded=5,1") // a reload starts the page again: this mark is then gone
            val loc = IntArray(2); var w = 0; var h = 0
            sc.onActivity { a -> a.webForTest().getLocationOnScreen(loc); w = a.webForTest().width; h = a.webForTest().height }
            noSystemDialogs()
            val d = androidx.test.uiautomator.UiDevice.getInstance(inst)
            d.swipe(loc[0] + w / 2, loc[1] + 40, loc[0] + w / 2, loc[1] + minOf(h - 20, 900), 60)
            assertTrue("pulling down at the top did not reload the page", waitFor(10) { eval(sc, "window.notReloaded===5?1:0") == "0" })
        }
    }

    @Test
    fun safeMoneyAnswersBankQuestionsAndSendsOtherSitesOut() {
        val mon = inst.addMonitor(MainActivity::class.java.name, null, true)
        try {
            ActivityScenario.launch(SafeMoneyActivity::class.java).use { sc ->
                Thread.sleep(1500)
                sc.onActivity { a -> assertNotNull("bank pages cannot ask (alert / confirm) or upload", a.webForTest().webChromeClient) }
                // a redirect by itself (no tap) to a normal site leaves the green frame
                sc.onActivity { a -> a.webForTest().loadDataWithBaseURL("https://onlinesbi.sbi/", "<script>setTimeout(function(){location.href='https://example.com/after-bank'},300)</script>", "text/html", "utf-8", null) }
                assertTrue("a non-bank redirect stayed inside Safe Money", waitFor(10) { mon.hits >= 1 })
                // Back on the first page asks before logging out
                sc.onActivity { a -> a.webForTest().clearHistory(); a.onBackPressedDispatcher.onBackPressed() }
                Thread.sleep(500)
                sc.onActivity { a -> assertTrue("Back closed Safe Money without asking", a.closeAskForTest()?.isShowing == true); a.closeAskForTest()?.dismiss() }
                assertEquals(Lifecycle.State.RESUMED, sc.state)
            }
        } finally { inst.removeMonitor(mon) }
    }

    @Test
    fun coloursOrLanguageAskBeforeLosingATypedForm() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            Thread.sleep(1500)
            sc.onActivity { a -> a.loadHtmlForTest("<html><body><input id=n value=''></body></html>", "https://form.example.gov.in/") }
            Thread.sleep(1200)
            eval(sc, "document.getElementById('n').value='Asha Devi',1")
            var changed = false
            sc.onActivity { a -> a.safeRecreateForTest { changed = true } }
            assertTrue("Ronin did not ask before redrawing over a typed form", waitFor(10) { var s = false; sc.onActivity { a -> s = a.lastAskForTest()?.isShowing == true }; s })
            assertFalse("changed without asking", changed)
            sc.onActivity { a -> a.lastAskForTest()?.dismiss() }
            // battery: sound starts by itself only on the song sites
            var news = false; var yt = true
            sc.onActivity { a -> news = a.webForTest().settings.mediaPlaybackRequiresUserGesture }
            sc.onActivity { a -> a.loadHtmlForTest("<html><body>song</body></html>", "https://m.youtube.com/ronin-gesture-test") }
            sc.onActivity { a -> yt = a.webForTest().settings.mediaPlaybackRequiresUserGesture }
            assertTrue("a normal site may start sound without a tap", news)
            assertFalse("YouTube cannot start the next song by itself", yt)
        }
    }

    @Test
    fun aLinkThatOpensANewWindowOpensANewTab() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            Thread.sleep(1500)
            sc.onActivity { a -> a.loadHtmlForTest("<html><body style='margin:0'><a id=x target=_blank href='https://example.com/receipt' style='display:block;height:400px;background:#2563eb;color:#fff;font:30px sans-serif'>Print receipt</a></body></html>", "https://portal.example.gov.in/form") }
            Thread.sleep(1500)
            var n = 0; val loc = IntArray(2); var w = 0
            sc.onActivity { a -> n = a.tabUrlsForTest().size; a.webForTest().getLocationOnScreen(loc); w = a.webForTest().width }
            noSystemDialogs()
            androidx.test.uiautomator.UiDevice.getInstance(inst).click(loc[0] + w / 2, loc[1] + 150) // a real tap
            var urls = emptyList<String>()
            assertTrue("no new tab for a new-window link", waitFor(10) { sc.onActivity { a -> urls = a.tabUrlsForTest() }; urls.size == n + 1 })
            assertTrue("the form page was replaced: $urls", urls.any { it.startsWith("https://portal.example.gov.in/form") })
        }
    }

    // ---- 🤖 Claude tab ----------------------------------------------------------------------

    private val claudeTest = "https://claude-test.invalid/view" // a name that never resolves: quick, no real server needed

    @Test
    fun claudeButtonOpensTheClaudeTab() {
        val store = (app.applicationContext as RoninApp).store
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            Thread.sleep(1500)
            if (app.resources.configuration.smallestScreenWidthDp >= 600) sc.onActivity { a -> assertNotNull("no 🤖 Claude button on the tablet bar", findTag(a.window.decorView, "claude-top")) }
            // not paired yet: no server is guessed, the tab says how to pair
            sc.onActivity { a -> a.showMenuForTest() }
            Thread.sleep(800)
            sc.onActivity { a -> findTag(a.menuSheetForTest()!!.window!!.decorView, "m-claude")!!.performClick() }
            sc.onActivity { a -> assertTrue(a.claudeIsCurrentForTest()) }
            assertTrue("no 'not paired' page: " + pageText(sc).take(200), waitFor(15) { pageText(sc).contains(L("जुड़ा नहीं", "not paired")) })
            shot("26-claude-not-paired")
            var n = 0
            sc.onActivity { a -> n = a.tabUrlsForTest().size }
            // paired, server unreachable: one simple Hindi / English page with Reload, still one Claude tab
            store.claudeViewUrl = claudeTest
            try {
                sc.onActivity { a -> a.openClaude() }
                var url: String? = null
                sc.onActivity { a -> url = a.claudeUrlForTest(); assertEquals("a moved server left an old Claude tab", n, a.tabUrlsForTest().size) }
                assertEquals(claudeTest, url)
                assertTrue("no 'not on' page: " + pageText(sc).take(200), waitFor(25) { pageText(sc).contains(L("Claude का browser अभी चालू नहीं", "Claude's browser is not on right now")) })
                assertEquals("1", eval(sc, "document.getElementById('reload')?1:0"))
                shot("26-claude-not-on")
                sc.onActivity { a -> a.openClaude() }
                sc.onActivity { a -> assertEquals("a second press made a second Claude tab", n, a.tabUrlsForTest().size) }
                // the Claude tab never loads another site: that opens in a normal tab
                sc.onActivity { a -> a.go("https://example.com/from-claude-tab") }
                var urls = emptyList<String>()
                sc.onActivity { a -> urls = a.tabUrlsForTest(); url = a.claudeUrlForTest() }
                assertTrue("other site not in its own tab: $urls", urls.contains("https://example.com/from-claude-tab"))
                assertEquals("the Claude tab was taken over", claudeTest, url)
            } finally { store.claudeViewUrl = "" }
        }
    }

    @Test
    fun claudePairingLinkHostIsRemembered() {
        val store = (app.applicationContext as RoninApp).store
        val key = "ab".repeat(24)
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            Thread.sleep(1500)
            // typed by him: a new host is asked about once
            sc.onActivity { a -> a.go("https://1-2-3-4.sslip.io/k/$key") }
            Thread.sleep(500)
            sc.onActivity { a -> val d = a.lastAskForTest(); assertTrue("no question for a new Claude host", d?.isShowing == true); d!!.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick() }
            // a dialog button answers a moment later (Android posts it): wait for it
            assertTrue("new Claude host not remembered: " + store.claudeViewUrl, waitFor(5) { store.claudeViewUrl == "https://1-2-3-4.sslip.io/view" })
            Thread.sleep(500)
            var url: String? = null
            sc.onActivity { a -> url = a.claudeUrlForTest() }
            assertTrue("pairing link not opened in the Claude tab: $url", url!!.startsWith("https://1-2-3-4.sslip.io/"))
            // a link inside a web page can never re-point Claude (anyone can make a *.sslip.io name)
            sc.onActivity { a -> a.newTab("https://example.com/", select = true, lazy = true) }
            sc.onActivity { a -> a.pageLinkForTest("https://5-6-7-8.sslip.io/k/$key") }
            Thread.sleep(1500)
            sc.onActivity { a -> assertFalse("a page link asked to change Claude", a.lastAskForTest()?.isShowing == true) }
            assertEquals("https://1-2-3-4.sslip.io/view", store.claudeViewUrl)
            // a look-alike link from elsewhere never becomes Claude
            sc.onActivity { a -> a.go("https://evil.example/k/$key") }
            Thread.sleep(500)
            assertEquals("https://1-2-3-4.sslip.io/view", store.claudeViewUrl)
            // a link from another app (Telegram) may name a new server: asked first
            app.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://9-9-9-9.sslip.io/k/$key"), app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            assertTrue("a link from another app was not asked about", waitFor(10) { var s = false; sc.onActivity { a -> s = a.lastAskForTest()?.isShowing == true }; s })
            sc.onActivity { a -> a.lastAskForTest()?.dismiss() }
            assertEquals("https://1-2-3-4.sslip.io/view", store.claudeViewUrl)
        }
        store.claudeViewUrl = ""
    }

    @Test
    fun claudeTabRestsWhenRoninIsInTheBackground() {
        val store = (app.applicationContext as RoninApp).store
        store.claudeViewUrl = claudeTest
        try {
            ActivityScenario.launch(MainActivity::class.java).use { sc ->
                Thread.sleep(1500)
                sc.onActivity { a -> a.loadClaudeHtmlForTest("<html><body><h1>Claude view test</h1><script>window.__vis=[];document.addEventListener('visibilitychange',function(){__vis.push(document.visibilityState)})</script></body></html>") }
                Thread.sleep(1500)
                assertEquals("false", eval(sc, "document.hidden"))
                androidx.test.uiautomator.UiDevice.getInstance(inst).pressHome()
                Thread.sleep(2500)
                var paused = false; var media = true
                sc.onActivity { a -> paused = a.timersPausedForTest(); media = a.mediaTabIsClaudeForTest() }
                assertTrue("page timers still running with Ronin in the background", paused)
                assertFalse("the Claude tab became the background player", media)
                // back to Ronin the way he does it (its icon): the same screen comes to the front
                app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                assertTrue("Ronin did not come back", waitFor(15) { sc.state == Lifecycle.State.RESUMED })
                Thread.sleep(1500)
                assertTrue("the page was never told it is hidden: " + eval(sc, "JSON.stringify(window.__vis)"), eval(sc, "JSON.stringify(window.__vis)").contains("hidden"))
            }
        } finally { store.claudeViewUrl = "" }
    }

    @Test
    fun claudePasteReadsTheClipboardOnlyAfterATap() {
        val store = (app.applicationContext as RoninApp).store
        store.claudeViewUrl = claudeTest
        inst.runOnMainSync { (app.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("t", "Ronin paste test")) }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { sc ->
                Thread.sleep(1500)
                sc.onActivity { a -> a.loadClaudeHtmlForTest("<html><body style='margin:0'><button id=p style='width:100%;height:400px;font-size:30px' onclick=\"navigator.clipboard.readText().then(function(s){window.__got=s},function(){window.__got='no'})\">Paste</button></body></html>") }
                Thread.sleep(1500)
                // the page by itself (no tap): nothing
                eval(sc, "navigator.clipboard.readText().then(function(s){window.__self=s},function(){window.__self='no'}),1")
                assertTrue(waitFor(5) { eval(sc, "window.__self||''") == "\"no\"" })
                // his tap on the page's Paste button: the text arrives
                noSystemDialogs()
                val loc = IntArray(2); var w = 0
                sc.onActivity { a -> a.webForTest().getLocationOnScreen(loc); w = a.webForTest().width }
                androidx.test.uiautomator.UiDevice.getInstance(inst).click(loc[0] + w / 2, loc[1] + 200)
                assertTrue("Paste after a tap got: " + eval(sc, "window.__got||''"), waitFor(8) { eval(sc, "window.__got||''") == "\"Ronin paste test\"" })
            }
        } finally { store.claudeViewUrl = "" }
    }

    // ---- QA round (21 Nov) ----------------------------------------------------------------------

    @Test
    fun pullToReloadAsksBeforeLosingATypedForm() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            Thread.sleep(1500)
            sc.onActivity { a -> a.loadHtmlForTest("<html><body><input id=n></body></html>", "https://form2.example.gov.in/") }
            Thread.sleep(1200)
            eval(sc, "document.getElementById('n').value='Ravi Kumar',1")
            sc.onActivity { a -> a.pullReloadForTest() }
            assertTrue("pull-to-reload threw a typed form away without asking", waitFor(8) { var s = false; sc.onActivity { a -> s = a.lastAskForTest()?.isShowing == true }; s })
            assertEquals("\"Ravi Kumar\"", eval(sc, "document.getElementById('n').value"))
            sc.onActivity { a -> a.lastAskForTest()?.dismiss() }
        }
    }

    @Test
    fun backLeavesRoninsErrorPageAndLinksGoHttpsFirst() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            Thread.sleep(1500)
            sc.onActivity { a -> a.go("ronin-back-test.invalid") }
            assertTrue(waitFor(25) { eval(sc, "document.getElementById('reload')?1:0") == "1" })
            sc.onActivity { a -> a.onBackPressedDispatcher.onBackPressed() }
            assertTrue("Back is stuck on the error page", waitFor(10) { eval(sc, "document.getElementById('reload')?1:0") != "1" || sc.state != Lifecycle.State.RESUMED })
            // a plain-http address from a bookmark / history / link is opened https first too
            var url = ""
            sc.onActivity { a -> a.loadForTest("http://ronin-https-first.invalid/page"); url = a.currentUrlForTest() }
            assertEquals("https://ronin-https-first.invalid/page", url)
            // the router stays plain http
            sc.onActivity { a -> a.loadForTest("http://192.168.1.1/"); url = a.currentUrlForTest() }
            assertEquals("http://192.168.1.1/", url)
        }
    }
}
