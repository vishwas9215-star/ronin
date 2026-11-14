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

    init {
        // the "which parts?" question of the first start does not cover the tests
        InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("ronin_parts", Context.MODE_PRIVATE).edit().putBoolean("asked", true).putBoolean("adultAsked", true).commit()
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
        // what the owner does once by hand ("Allow restricted settings"), then the SMS role
        val ops = shell("cmd appops set ${app.packageName} ACCESS_RESTRICTED_SETTINGS allow")
        Thread.sleep(1000)
        val out = shell("cmd role add-role-holder android.app.role.SMS ${app.packageName} 0") + shell("cmd role get-role-holders android.app.role.SMS")
        run {
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
                evalPage(sc, "document.querySelector('[data-tab=scam]').click(),1")
                shot("16-ronin-messages")
            }
        }
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
    fun simNameIsKeptForRecentAndThreatTipShown() {
        // CNAP: the network's KYC name of an unknown caller is kept for Recent (the fake "Daroga ji" case)
        CallInfo.keepCnap(app, "+91 98765 43210", "RAMESH KUMAR")
        assertEquals("RAMESH KUMAR", CallInfo.cnapOf(app, "9876543210"))
        assertTrue(CallInfo.threatTip().contains("Digital arrest") || CallInfo.threatTip().contains("digital arrest", ignoreCase = true))
    }

    @Test
    fun roninCallsKeypadOpens() {
        if (!Parts.hasPhone(app)) return
        Parts.set(app, Parts.CALLS, true)
        ActivityScenario.launch(CallsActivity::class.java).use { sc ->
            assertTrue("calls keypad did not load", waitFor(20) { evalPage(sc, "document.querySelectorAll('.pad button').length") == "12" })
            // Block / Delete ask "are you sure?": the page's questions must reach the screen (3.11 fix)
            var chrome = false
            sc.onActivity { a -> chrome = a.webForTest().webChromeClient != null }
            assertTrue("phone pages cannot ask questions (no WebChromeClient)", chrome)
            evalPage(sc, "document.querySelector('[data-key=\"1\"]').click(),1")
            shot("17-ronin-calls")
        }
    }

    @Test
    fun safeSocialChipOnTheHomePageOpensThatApp() {
        // bug in 3.10: an Instagram chip on the new tab page opened "Edit links" instead of Instagram
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            Thread.sleep(2000)
            assertTrue("Safe Social row missing", waitFor(10) { eval(sc, "document.querySelectorAll('#ql .chips[data-s=\"Safe Social\"] .chip').length").toIntOrNull() ?: 0 >= 2 })
            eval(sc, "[].filter.call(document.querySelectorAll('#ql .chips[data-s=\"Safe Social\"] .chip'), function(c){return c.href.indexOf('app=')>0})[0].click(); 1")
            var social = false
            assertTrue("chip did not open Safe Social", waitFor(15) { sc.onActivity { a -> social = a.currentIsSocialForTest() }; social })
            var at = ""
            sc.onActivity { a -> at = a.webForTest().url ?: "" }
            assertTrue("opened the launcher, not the app: $at", !at.startsWith(MainActivity.SOCIAL))
        }
    }

    @Test
    fun safeSocialOpensFullScreenAndCloses() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            Thread.sleep(1500)
            var strip = 0
            sc.onActivity { a -> strip = a.tabStripForTest().childCount; a.openSocial() }
            assertTrue("Safe Social launcher did not open", waitFor(20) { eval(sc, "document.querySelectorAll('.app').length") == "5" })
            // Ronin itself stops Telegram in Safe Social, whatever the page does
            eval(sc, "location.href='https://t.me/share_market_vip',1")
            Thread.sleep(2500)
            var at = ""
            sc.onActivity { a -> at = a.webForTest().url ?: "" }
            assertTrue("Safe Social opened Telegram: $at", !at.contains("t.me"))
            var social = false; var barShown = true
            sc.onActivity { a -> social = a.currentIsSocialForTest(); barShown = a.addressForTest().isShown }
            assertTrue("not a Safe Social tab", social)
            assertTrue("address bar still shown in Safe Social", !barShown)
            var bottomBar = false
            sc.onActivity { a -> bottomBar = a.socialBarForTest().isShown }
            assertTrue("Back / My apps / Close bar not shown at the bottom", bottomBar)
            // the green shield shows that Ronin is protecting this page
            var shieldShown = false
            sc.onActivity { a -> shieldShown = (a.socialBarForTest() as android.view.ViewGroup).findViewWithTag<android.view.View>("socialShield")?.isShown == true }
            assertTrue("protection shield not shown in Safe Social", shieldShown)
            // signed-out Gmail's advert page goes to Google's sign-in; the inbox itself is left alone
            sc.onActivity { a ->
                assertTrue(a.gmailAdvertForTest("https://workspace.google.com/intl/en-US/gmail/"))
                assertTrue(a.gmailAdvertForTest("https://www.google.com/intl/en/gmail/about/"))
                assertTrue(!a.gmailAdvertForTest("https://mail.google.com/mail/u/0/"))
                assertTrue(!a.gmailAdvertForTest("https://workspace.google.com/pricing"))
            }
            shot("15-safe-social")
            // ✕ closes it and the browser is back
            eval(sc, "location.href='https://ronin.data/social?do=close&k='+new URLSearchParams(location.search).get('k'),1")
            Thread.sleep(1500)
            sc.onActivity { a -> social = a.currentIsSocialForTest(); barShown = a.addressForTest().isShown }
            assertTrue("Safe Social did not close", !social && barShown)
            var after = 0
            sc.onActivity { a -> after = a.tabStripForTest().childCount }
            assertEquals("tabs changed", strip, after)
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

}
