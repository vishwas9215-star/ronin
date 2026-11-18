package com.ronin.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LogicTest {
    private val rules = Rules(File("src/main/assets/ronin/rules.json").readText())

    @Test fun safeLinksLikeWindows() {
        // the same cases as internal/safelinks (Go) and tools/uitest/scamcheck.js (page)
        val a = org.json.JSONArray(File("../../internal/safelinks/cases.json").readText())
        for (i in 0 until a.length()) {
            val c = a.getJSONArray(i)
            assertEquals(c.getString(0), c.getString(1), rules.safeLinks.check(c.getString(0)))
        }
    }

    @Test fun scamCheckLikeThePage() {
        // the same corpus as tools/uitest/scamcheck.js: every scam warns, no normal message does
        val missed = ArrayList<String>(); val alarms = ArrayList<String>()
        for (f in listOf("scam_corpus.json", "scam_heldout.json")) {
            val c = org.json.JSONObject(File("../../tools/uitest/$f").readText())
            val scam = c.getJSONArray("scam"); val normal = c.getJSONArray("normal")
            missed += (0 until scam.length()).map { scam.getString(it) }.filter { rules.scam.text(it).level.isEmpty() }
            alarms += (0 until normal.length()).map { normal.getString(it) }.filter { rules.scam.text(it).level.isNotEmpty() }
        }
        assertTrue("missed: $missed", missed.isEmpty())
        assertTrue("false alarms: $alarms", alarms.isEmpty())
        assertEquals("stop", rules.scam.text("Mujhe urgent 10000 rupaye ki zarurat hai, hospital me hoon, is QR par bhej do").level)
        assertEquals("", rules.scam.text("OTP किसी को मत बताना, बैंक कभी OTP नहीं माँगता").level)
    }

    @Test fun safeSearchForced() {
        assertEquals("https://www.google.com/search?q=x&safe=active", Family.safeSearch("https://www.google.com/search?q=x"))
        assertNull(Family.safeSearch("https://www.google.com/search?q=x&safe=active"))
        assertNull(Family.safeSearch("https://www.google.com/maps"))
    }

    @Test fun smsSorting() {
        assertEquals("ads", SmsSort.category("VM-SHOPPR-P", "Big sale! 70% off today", false, ""))
        assertEquals("work", SmsSort.category("JM-SBIINB-S", "Your OTP is 123456", false, ""))
        assertEquals("work", SmsSort.category("AD-UIDAI-G", "Aadhaar updated", false, ""))
        assertEquals("unknown", SmsSort.category("+919812345678", "Hello sir, kal milte hain", false, ""))
        assertEquals("work", SmsSort.category("+919812345678", "Hello sir, kal milte hain", true, ""))
        // 3.11: loan / selling offers are hidden as ads even from service senders; transactions never are
        assertEquals("ads", SmsSort.category("JD-PIRFIN-S", "Dear customer, Check the latest details of your loan approval for Rs 5 lakh. Apply now", false, ""))
        assertEquals("ads", SmsSort.category("AD-HDFCBK-S", "Pre-approved personal loan of Rs 3,00,000 is ready for you", false, ""))
        assertEquals("work", SmsSort.category("VD-HDFCBK-S", "IMPS INR 6,900.00 sent from HDFC Bank A/c XX6490 on 13-11-26", false, ""))
        assertEquals("work", SmsSort.category("JX-KOTAKD-S", "Dear customer, your Kotak A/C XXXX747132 is blocked. To unblock, visit https://kotk.in/x", false, ""))
        assertEquals("work", SmsSort.category("JK-lichfl-S", "Dear Sir/Madam, The NACH for Rs. 19296 will be presented on 15-11-2026", false, ""))
        assertEquals("work", SmsSort.category("AD-HDFCBK-S", "Congratulations! Your Personal Loan of Rs 2 lakh is sanctioned and disbursed to A/c XX12", false, ""))
        assertEquals("work", SmsSort.category("AD-EPFOHO-G", "Your PF claim is settled", false, ""))
        // only registered promotional senders are ever auto-deleted
        assertTrue(SmsSort.isPromoSender("VM-SHOPPR-P")); assertFalse(SmsSort.isPromoSender("VD-HDFCBK-S")); assertFalse(SmsSort.isPromoSender("+919812345678"))
        // the OTP alone, for the big blue copy button and the notification's Copy OTP
        assertEquals("554581", Otp.find("554581 is your OTP for Dhan. Please do not share it with anyone"))
        assertEquals("6038", Otp.find("Your OTP for Login is 6038This OTP is valid ony for 20 minutes"))
        assertEquals("630753", Otp.find("Enter the code 630753 to sign in to your FYERS account"))
        assertEquals(null, Otp.find("IMPS INR 6,900.00 sent from HDFC Bank A/c XX6490 on 13-11-26"))
        assertEquals(null, Otp.find("Your shopping order to pincode 228001 is shipped"))
        assertEquals("scam", SmsSort.category("+919812345678", "x", false, "stop"))
        assertEquals("scam", SmsSort.category("+919812345678", "x", true, "warn"))
        assertEquals("ads", SmsSort.category("+919812345678", "Limited offer! Get 50% cashback, shop now", false, ""))
        assertEquals("work", SmsSort.category("57575", "Recharge done", false, ""))
    }
    // 3.13: "delete old OTPs" (the family's choice) takes only a plain OTP SMS, never a booking / PNR / order code
    // owner, 18 Nov 2026: a short OTP goes after 24 hours; a delivery / gas OTP only 24 hours after "delivered".
    // Every non-OTP below came from the independent QA (3.13): none may ever be deleted.
    @Test fun oldOtpsAreCleanedUpSafely() {
        val S = Otp.Kind.SHORT; val D = Otp.Kind.DELIVERY; val N = Otp.Kind.NONE
        assertEquals(S, Otp.kind("JM-DHANHQ-S", "554581 is your OTP for Dhan. Please do not share it with anyone"))
        assertEquals(S, Otp.kind("VM-AMAZON-T", "Your One Time Password is 908172 for login"))
        assertEquals(S, Otp.kind("JD-FYERSS-S", "Your OTP for Login is 6038. This OTP is valid only for 20 minutes"))
        for ((a, b) in listOf(
            "+919812345678" to "554581 is your OTP for Dhan",
            "VM-HDFCBK-S" to "OTP 482913 to pay Rs 500 at Amazon. Never share it",
            "VM-IRCTCI-S" to "Your PNR 4512345678 booking code 482913, train 12230",
            "VD-SBIINB-S" to "Rs 2500 debited from A/c XX1234 on 12-11. Call 1800123. Never share OTP",
            "JD-SBIINB-S" to "Rs.5000 credited to your A/c XX1234 Avl Bal Rs 12000. Do not share OTP with anyone - SBI",
            "AD-SCHOOL-S" to "Dear Parent, school fees Rs 1200 due on 2026. Never share OTP.",
            "VM-SERVCE-S" to "Your OTP 4512 is valid for 7 days",
            "AD-UIDAIG-G" to "Aadhaar XX1234 mobile number updated successfully on 12-11-2026. Do not share OTP with anyone. Call 1947",
            "VM-ICICIB-S" to "INR 2,499.00 spent on ICICI Bank Card XX4321 on 18-Nov-26 at FLIPKART. Never share OTP. Not you? Call 18001080",
            "VM-LICIND-S" to "Payment of Rs 2500 to LIC successful, authorised via OTP. Txn ID 81234567",
            "VM-DIGILK-S" to "Your DigiLocker account is created. Never share your OTP. Helpline 1800-3001-3434",
            "VM-NPCIUP-S" to "Dear Customer, your UPI ID is active. Never share UPI PIN/OTP. Report fraud: 1930",
            "VM-PAYTMB-S" to "Thank you for your payment of Rs 1200. Ref 4512. OTP generated earlier is now invalid",
        )) assertEquals(b, N, Otp.kind(a, b))
        for ((a, b) in listOf(
            "VM-FLPKRT-S" to "OTP 123456 for your order 98765 delivery. Share only with the delivery agent",
            "VM-HPGASL-S" to "Your HP Gas refill booking no 1234567 is confirmed. DAC for delivery is 4821",
            "JD-INDANE-S" to "Indane cylinder: delivery code 7731. Cash memo will be generated on delivery",
            "VM-MEESHO-S" to "Meesho: Share OTP 4821 with Valmo agent to receive your item",
            "VM-AMAZON-S" to "Your Amazon return pickup is scheduled for 22 Nov. Share OTP 482913 with the associate",
            "VM-OYOROO-S" to "Your OYO stay is confirmed, check-in 25 Nov. Check-in OTP: 5678",
        )) assertEquals(b, D, Otp.kind(a, b))
        assertTrue(Otp.isDelivered("Your order 98765 has been delivered. Thank you"))
        assertTrue(Otp.isDelivered("HP Gas refill booking 1234567 was delivered on 18-11-2026"))
        for (b in listOf("Your order will be delivered today", "Out for delivery: your package", "Your parcel could not be delivered",
            "Your order will get delivered today", "Your shipment is expected to get delivered by 20 Nov", "Your order is being delivered by Ekart agent",
            "Your order hasn't been delivered yet", "Your parcel has not yet been delivered", "Share DAC only when the cylinder is delivered",
            "1 of 2 items delivered. Remaining item arriving tomorrow", "Your package was delivered? If not, call us", "आपका ऑर्डर कल डिलीवर कर दिया जाएगा",
            "Your package with tracking no 1234567 was delivered to the delivery hub, Sultanpur", "Your parcel has been delivered to Amazon Locker",
            "Your item from order OD330123456789 has been delivered", "क्या आपका ऑर्डर 12345678 डिलीवर हो गया, रेटिंग दें"))
            assertFalse(b, Otp.isDelivered(b))
        val h = 3600_000L; val now = 100 * 24 * h
        val inbox = listOf(
            Otp.Sms(1, "JM-DHANHQ-S", "554581 is your OTP for Dhan", now - 25 * h),                       // short, 25 h: goes
            Otp.Sms(2, "JM-DHANHQ-S", "112233 is your OTP for Dhan", now - 5 * h),                         // short, 5 h: stays
            Otp.Sms(3, "VM-FLPKRT-S", "OTP 123456 for your order OD98765 delivery", now - 72 * h),           // its order delivered 48 h ago: goes
            Otp.Sms(4, "JD-FLPKRT-S", "Your order OD98765 has been delivered", now - 48 * h),
            Otp.Sms(5, "VM-HPGASL-S", "HP Gas refill booking no 1234567: DAC for delivery is 4821", now - 240 * h), // never delivered: stays
            Otp.Sms(6, "VM-AMAZON-S", "OTP 778899 for your order 403-1111111 delivery", now - 72 * h),      // delivered 5 h ago: stays
            Otp.Sms(7, "VM-AMAZON-S", "Your order 403-1111111 has been delivered", now - 5 * h),
            Otp.Sms(8, "VD-SBIINB-S", "Rs 2500 debited from A/c XX1234. Never share OTP", now - 900 * h),  // record: stays
            Otp.Sms(9, "VM-FLPKRT-S", "OTP 445566 for your order OD55555 delivery", now - 72 * h),          // ANOTHER order delivered: stays
            Otp.Sms(10, "VM-MEESHO-S", "Share OTP 4821 with Valmo agent to receive your item", now - 72 * h), // no order number: stays
            Otp.Sms(11, "VM-MEESHO-S", "Your order has been delivered", now - 48 * h),
            // QA round 2: parts of one order, and the gas IVRS number printed on every SMS
            Otp.Sms(12, "VM-MEESHO-S", "Meesho: Order ID 143852093476_2 is out for delivery. OTP is 4821", now - 72 * h),
            Otp.Sms(13, "VM-MEESHO-S", "Meesho: Order ID 143852093476_1 has been delivered. Thank you", now - 48 * h),
            Otp.Sms(14, "JD-INDANE-S", "Indane: Booking No. 1234567 confirmed. DAC is 4821. For refill booking: 7718955555", now - 72 * h),
            Otp.Sms(15, "JD-INDANE-S", "Indane: Cylinder for Booking No. 1234999 has been delivered. For refill booking: 7718955555", now - 48 * h),
            Otp.Sms(16, "VM-AMZNIN-S", "OTP 111222 for your order 404-2222222 delivery", now - 72 * h),   // two OTPs, one order number:
            Otp.Sms(17, "VM-AMZNIN-S", "OTP 333444 for your order 404-2222222 delivery", now - 70 * h),   // both stay
            Otp.Sms(18, "VM-AMZNIN-S", "Your order 404-2222222 has been delivered", now - 48 * h),
        )
        assertEquals(listOf(1L, 3L), Otp.toDelete(inbox, now))
    }

    @Test fun classifiesLikeWindows() {
        assertEquals(Kind.BANK, rules.classify("https://sbi.bank.in/"))
        assertEquals(Kind.BANK, rules.classify("https://netbanking.hdfcbank.com/x"))
        assertEquals(Kind.TRADING, rules.classify("https://web.dhan.co/"))
        assertEquals(Kind.CALL, rules.classify("https://meet.google.com/abc"))
        assertEquals(Kind.AUTH, rules.classify("https://accounts.google.com/x"))
        assertEquals(Kind.GENERAL, rules.classify("https://www.youtube.com/"))
        assertEquals(Kind.GENERAL, rules.classify("https://sbi.bank.in.evil.com/"))
        assertEquals(Kind.BANK, rules.classify("https://ib.mycoop.co.in/", setOf("mycoop.co.in")))
        assertEquals(Kind.LOCAL, rules.classify("about:blank"))
    }

    @Test fun parsesMessyAddresses() {
        val p = Rules.parse("https://user@Ads.Example.com:8443/a b|c?x=1#y")
        assertEquals("https", p.scheme); assertEquals("ads.example.com", p.host); assertEquals("/a b|c", p.path)
        assertEquals("", Rules.parse("not a url").host)
    }

    @Test fun adBlockMatchesWindowsRules() {
        val b = AdBlocker(rules)
        assertTrue(b.blocked("https://securepubads.g.doubleclick.net/tag/js/gpt.js"))
        assertTrue(b.blocked("https://www.youtube.com/pagead/viewthroughconversion/1"))
        assertTrue(b.blocked("https://www.youtube.com/api/stats/ads?x"))
        assertFalse(b.blocked("https://www.youtube.com/watch?v=1"))
        assertFalse(b.blocked("https://rr3---sn-x.googlevideo.com/videoplayback?x"))
        assertFalse(b.blocked("https://sbi.bank.in/"))
        // the big downloaded list (hashes) + allow lines
        val n = b.loadList("# test\nads.badnetwork.example\ntracker.example.org\n@@good.tracker.example.org\n".reader().buffered())
        assertEquals(2, n)
        assertTrue(b.blocked("https://x.ads.badnetwork.example/a"))
        assertTrue(b.blocked("https://tracker.example.org/p"))
        assertFalse(b.blocked("https://good.tracker.example.org/p"))
        assertFalse(b.blocked("https://badnetwork.example/"))
    }

    @Test fun omnibox() {
        assertEquals("https://hi.wikipedia.org", Omnibox.resolve("hi.wikipedia.org"))
        assertEquals("https://example.com/", Omnibox.resolve("http://example.com/"))
        assertEquals("http://192.168.1.1/", Omnibox.resolve("http://192.168.1.1/"))
        assertEquals("https://hi.wikipedia.org/wiki/हिंदी", Omnibox.resolve("hi.wikipedia.org/wiki/हिंदी"))
        assertTrue(Omnibox.resolve("हिंदी समाचार")!!.startsWith("https://www.google.com/search?q="))
        assertTrue(Omnibox.resolve("what is 2.5 percent")!!.startsWith("https://www.google.com/search?q="))
        assertNull(Omnibox.resolve("javascript:alert(1)"))
        assertNull(Omnibox.resolve("   "))
    }

    @Test fun updateInfo() {
        val ok = Updater.parse("""{"versionCode":30601,"versionName":"3.6.1","url":"https://github.com/x/Ronin.apk","sha256":"${"a".repeat(64)}","size":5}""")
        assertEquals(30601, ok!!.code)
        assertNull(Updater.parse("""{"versionCode":1,"versionName":"x","url":"http://evil/Ronin.apk","sha256":"${"a".repeat(64)}"}"""))
        assertNull(Updater.parse("""{"versionCode":1,"versionName":"x","url":"https://x/Ronin.apk","sha256":"short"}"""))
        assertNull(Updater.parse("not json"))
    }

    @Test fun readAloudPieces() {
        assertTrue(ReadAloud.isHindi("भारत का संविधान"))
        assertFalse(ReadAloud.isHindi("The Preamble of India"))
        val long = "यह पहला वाक्य है। ".repeat(40)
        val ps = ReadAloud.pieces(long)
        assertTrue(ps.size > 1)
        assertTrue(ps.all { it.length <= 240 })
        assertEquals(long.trim().replace(Regex("\\s+"), " "), ps.joinToString(" ").replace(Regex("\\s+"), " "))
    }

    // same cases as internal/safemoney/classify_test.go (Windows)
    @Test fun fakeBankSitesLikeWindows() {
        val bad = listOf("https://onlinesbi-kyc.xyz/", "https://sbi-kyc-update.com/", "https://sbionline.co/",
            "https://yonosbi-rewards.in/", "https://hdfcbank-login.net/", "https://hdfcbamk.com/",
            "https://1cici-netbanking.in/", "https://lcicibank.co.in/", "https://axisbank.secure-login.top/",
            "https://pnb-kyc.online/", "https://kotak-reward-points.com/", "https://xn--onlinesb-0hh.com/",
            "https://xn--hdfcbnk-6fg.in/", "https://canarabank-help.in/", "https://dhan-login.co/")
        for (u in bad) assertTrue("$u should be a fake bank site", rules.fakeBank(u) != null)
        val good = listOf("https://www.google.com/", "https://www.sbicard.com/", "https://www.hdfclife.com/",
            "https://www.icicidirect.com/", "https://www.hdfcsky.com/", "https://www.axis-design.com/",
            "https://kotakcollege.edu.in/", "https://www.barodatimes.com/", "https://sbi.example.com/",
            "https://www.youtube.com/watch?v=hdfc", "https://en.wikipedia.org/wiki/State_Bank_of_India",
            "https://www.dhanbank.com/", "https://www.onlinemarket.in/", "https://sbi.bank.in/",
            "https://netbanking.hdfcbank.com/", "https://www.icicisecurities.com/", "https://www.hdfccredila.com/",
            "https://www.indusindnipponlife.com/", "https://www.yesbankltd.in/", "https://www.axisbankfoundation.org/",
            "https://www.hdfcergo.com/", "https://www.hdfcfund.com/", "https://www.icicipruamc.com/", "https://www.iciciprulife.com/", "https://www.icicilombard.com/")
        for (u in good) assertNull("$u wrongly flagged", rules.fakeBank(u))
        assertNull(rules.fakeBank("https://sbi-pension-help.org/", trusted = setOf("sbi-pension-help.org")))
        assertEquals("sbi", rules.fakeBank("https://sbi-pension-help.org/"))
    }

    @Test fun dangerListStopsWholeSites() {
        val b = AdBlocker(rules)
        assertTrue("test site", b.dangerous("ronin-phishing-test.com"))
        assertTrue(b.dangerous("www.ronin-phishing-test.com"))
        assertFalse(b.dangerous("www.google.com"))
        val n = b.loadThreats("# danger\nphish.example\nmalware.example.org\n".reader().buffered())
        assertEquals(2, n)
        assertTrue(b.dangerous("login.phish.example"))
        assertTrue(b.dangerous("MALWARE.example.org."))
        assertFalse(b.dangerous("notphish.example"))
        assertTrue("danger sites are blocked as page parts too", b.blocked("https://phish.example/x.js"))
        assertTrue(b.dangerous("ronin-phishing-test.com")) // the test site stays after a list update
    }

    @Test fun quickLinksLikeWindows() {
        assertEquals("https://kite.zerodha.com/", QuickLink.clean("Trading", " Zerodha ", "kite.zerodha.com", rules.quickSections)?.url)
        assertNull(QuickLink.clean("School", "x", "javascript:alert(1)", rules.quickSections))
        assertNull(QuickLink.clean("School", "x", "notasite", rules.quickSections))
        assertEquals("Quick links", QuickLink.clean("Nope", "", "https://www.example.com", rules.quickSections)?.section)
        assertTrue(rules.quickLinks.any { it.name == "UDISE+ All Modules" && it.section == "School" })
        assertTrue(rules.quickLinks.any { it.name == "Fyers" && it.section == "Trading" })
    }

    @Test fun sensitiveSites() {
        assertTrue(Sensitive.site("https://www.pornhub.com/x") && Sensitive.site("https://freeporn.example/") && Sensitive.site("https://chaturbate.com/"))
        assertTrue(!Sensitive.site("https://www.wikipedia.org/") && !Sensitive.site("https://essex.ac.uk/") && !Sensitive.site("https://prernaup.in/"))
    }

    @Test fun mergeResultExcel() {
        val t = TableExport(File("../../internal/tablexport/testdata/merge.json").readText())
        assertTrue(t.info.any { it.first.startsWith("अंतर वाले") } && t.legend.any { it.first == "diff" } && t.printCols.isNotEmpty())
        val kinds = t.rows.indices.mapNotNull { t.rowKind(it) }.toSet()
        assertTrue("kinds: $kinds", kinds.containsAll(listOf("diff", "only", "dup")))
        assertTrue(t.printColumns().first.size <= t.printCols.size)
        val z = java.util.zip.ZipInputStream(t.xlsx().inputStream())
        val parts = HashMap<String, String>()
        while (true) { val e = z.nextEntry ?: break; parts[e.name] = z.readBytes().toString(Charsets.UTF_8) }
        assertTrue(parts["xl/worksheets/sheet1.xml"]!!.contains("s=\"5\"") && parts["xl/worksheets/sheet1.xml"]!!.contains("s=\"6\""))
        parts.values.forEach { javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it.byteInputStream()) }
    }

    @Test fun tableExportExcelLikeWindows() {
        val t = TableExport(File("../../internal/tablexport/testdata/result.json").readText())
        assertEquals(21, t.rows.size)
        assertEquals("AA", TableExport.colName(26))
        assertTrue(TableExport.isNumber("40") && TableExport.isNumber("38.5") && !TableExport.isNumber("0123") && !TableExport.isNumber("9876543210") && !TableExport.isNumber(".5"))
        val x = t.xlsx()
        val z = java.util.zip.ZipInputStream(x.inputStream())
        val parts = HashMap<String, String>()
        while (true) { val e = z.nextEntry ?: break; parts[e.name] = z.readBytes().toString(Charsets.UTF_8) }
        val sheet = parts["xl/worksheets/sheet1.xml"]!!
        assertTrue(sheet.contains("Rahul Kumr") && sheet.contains("s=\"4\"") && sheet.contains("s=\"3\"") && sheet.contains("s=\"2\""))
        assertEquals(22, Regex("<row ").findAll(sheet).count())
        assertTrue(parts.keys.containsAll(listOf("[Content_Types].xml", "xl/workbook.xml", "xl/styles.xml", "xl/worksheets/sheet2.xml")))
        // well-formed XML
        parts.values.forEach { javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it.byteInputStream()) }
        java.io.File("build/test-table.xlsx").writeBytes(x)
    }
}
