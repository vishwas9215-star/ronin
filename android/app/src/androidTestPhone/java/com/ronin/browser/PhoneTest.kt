package com.ronin.browser

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Ronin Phone (com.ronin.phone, 3.14.3): Messages + Calls on their own, on the phone emulator. */
@RunWith(AndroidJUnit4::class)
class PhoneTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val app: Context get() = inst.targetContext

    private fun waitFor(seconds: Int, check: () -> Boolean): Boolean {
        repeat(seconds * 4) { if (check()) return true; Thread.sleep(250) }
        return check()
    }
    private fun shell(c: String): String = inst.uiAutomation.executeShellCommand(c).use { pfd ->
        android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { String(it.readBytes()) }
    }
    private fun shot(name: String) {
        Thread.sleep(700)
        shell("mkdir -p /data/local/tmp/qa"); shell("screencap -p /data/local/tmp/qa/$name.png")
    }

    @Test
    fun phoneAppIsOnlyMessagesAndCalls() {
        assertEquals("com.ronin.phone", app.packageName)
        val pm = app.packageManager
        val info = pm.getPackageInfo(app.packageName, android.content.pm.PackageManager.GET_PERMISSIONS or android.content.pm.PackageManager.GET_ACTIVITIES)
        val perms = info.requestedPermissions?.toList() ?: emptyList()
        // no self-update, no camera, never location: Ronin Browser brings its updates
        for (p in listOf("android.permission.REQUEST_INSTALL_PACKAGES", "android.permission.CAMERA", "android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION"))
            assertTrue("Ronin Phone asks for $p", p !in perms)
        val acts = info.activities?.map { it.name } ?: emptyList()
        for (c in listOf("MainActivity", "TvActivity", "SafeMoneyActivity")) assertTrue("$c is in Ronin Phone", acts.none { it.endsWith(".$c") })
        for (c in listOf("MessagesActivity", "CallsActivity", "InCallActivity")) assertTrue("$c is missing", acts.any { it.endsWith(".$c") })
        assertTrue("Messages is not on", Parts.isOn(app, Parts.MESSAGES)); assertTrue("Calls is not on", Parts.isOn(app, Parts.CALLS))
    }

    private fun <A : PhonePage> evalPage(sc: ActivityScenario<A>, js: String): String {
        val latch = CountDownLatch(1); var out = ""
        sc.onActivity { a -> a.webForTest().evaluateJavascript(js) { out = it ?: ""; latch.countDown() } }
        latch.await(15, TimeUnit.SECONDS); return out
    }

    @Test
    fun roninMessagesSortsScamAndWorkSms() {
        if (!Parts.hasPhone(app)) return // a tablet without SIM: no SMS to test
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
    fun callScreenNeverLeavesASecondRecentsCard() {
        // owner 21 Nov 2026: after a call he had to swipe away two cards (Ronin Calls + the call screen)
        val info = app.packageManager.getActivityInfo(android.content.ComponentName(app, InCallActivity::class.java),
            android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS)
        assertTrue("call screen shows in Recents", info.flags and android.content.pm.ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS != 0)
        assertTrue("call screen card stays after the call", info.flags and android.content.pm.ActivityInfo.FLAG_AUTO_REMOVE_FROM_RECENTS != 0)
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

}
