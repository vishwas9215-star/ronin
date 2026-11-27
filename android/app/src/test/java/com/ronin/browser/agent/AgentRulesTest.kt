package com.ronin.browser.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The same rules as the protocol simulation (79 checks): every case here was a hole a reviewer found. */
class AgentRulesTest {
    private val noMoney: (String) -> Boolean = { false }

    @Test fun moneySitesAreReadOnlyAndOnlyListedPagesOpen() {
        assertEquals(AgentRules.Mode.MONEY, AgentRules.mode("web.dhan.co"))
        assertEquals(AgentRules.Mode.MONEY, AgentRules.mode("kite.zerodha.com"))
        assertEquals(AgentRules.Mode.NORMAL, AgentRules.mode("epds.example.gov.in"))
        assertEquals(AgentRules.Mode.MONEY, AgentRules.mode("mybank.in") { it == "mybank.in" })
        val pages = mapOf("web.dhan.co" to setOf("/positions"))
        assertNull(AgentRules.openRefusal("https://web.dhan.co/", noMoney, pages, null))
        assertNull(AgentRules.openRefusal("https://web.dhan.co/positions", noMoney, pages, null))
        assertNotNull(AgentRules.openRefusal("https://web.dhan.co/order", noMoney, pages, null))
        assertNotNull(AgentRules.openRefusal("https://web.dhan.co/positions?confirm=1", noMoney, pages, null))
    }

    @Test fun addressesThatAreNeverOpened() {
        for (u in listOf("javascript:alert(1)", "file:///sdcard/x", "intent://x#Intent;end", "http://192.168.1.1/", "http://10.0.0.5/", "http://127.0.0.1:8080/", "http://localhost/", "http://172.20.1.1/", "http://100.64.0.1/", "http://169.254.1.1/", "http://[::1]/"))
            assertNotNull(u, AgentRules.openRefusal(u, noMoney, emptyMap(), null))
        assertNull(AgentRules.openRefusal("https://epds.example.gov.in/form?id=2", noMoney, emptyMap(), null))
        assertFalse(AgentRules.isPrivateHost("172.32.0.1"))
    }

    @Test fun dataDoesNotLeaveAfterSensitiveReads() {
        assertNotNull(AgentRules.openRefusal("https://paste.example.net/?d=" + "A".repeat(40), noMoney, emptyMap(), "bank.example.in"))
        assertNull(AgentRules.openRefusal("https://www.google.com/", noMoney, emptyMap(), "bank.example.in"))
        assertNull("same site is fine", AgentRules.openRefusal("https://x.bank.example.in/?d=" + "A".repeat(40), noMoney, emptyMap(), "bank.example.in"))
        assertNotNull("too much data anywhere", AgentRules.openRefusal("https://a.example/?" + "x".repeat(400), noMoney, emptyMap(), null))
    }

    @Test fun networkGuard() {
        val g = { m: String, u: String, claude: Boolean -> AgentRules.guard(m, u, true, claude, noMoney, null, "renew.example.com") }
        assertNotNull(g("POST", "https://renew.example.com/api/v2/placeOrder", false))   // even long after Claude's tap
        assertNotNull(g("POST", "https://x.example/checkout", false))
        assertNotNull(g("GET", "https://renew.example.com/order?confirm=1", true))
        assertNull(g("POST", "https://renew.example.com/api/notes", true))
        assertNull(g("GET", "https://renew.example.com/orders", true))                      // reading the order book is fine
        assertNotNull(g("POST", "https://web.dhan.co/api/anything", true))                  // money site: no POST from Claude's input
        assertNotNull(g("GET", "http://192.168.1.1/x", false))
        assertNotNull(AgentRules.guard("GET", "https://paste.example.net/?d=" + "A".repeat(40), true, true, noMoney, "bank.example.in", "bank.example.in"))
    }

    @Test fun secretsAndOtps() {
        assertTrue(AgentRules.looksLikeOtp("482913"))
        assertTrue(AgentRules.looksLikeOtp("48 29-13"))
        assertFalse(AgentRules.looksLikeOtp("9876543210"))   // a mobile number is not an OTP
        assertFalse(AgentRules.looksLikeOtp("UP12345"))
        assertTrue(AgentRules.secretInputType(0x00000081))   // TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_PASSWORD
        assertTrue(AgentRules.secretInputType(0x000000e1))   // WEB_PASSWORD
        assertTrue(AgentRules.secretInputType(0x00000012))   // TYPE_CLASS_NUMBER | NUMBER_VARIATION_PASSWORD
        assertFalse(AgentRules.secretInputType(0x00000001))  // plain text
        assertFalse(AgentRules.secretInputType(0x00000003))  // phone
        assertTrue(AgentRules.secretLabel("Enter OTP"))
        assertTrue(AgentRules.secretLabel("ओटीपी डालें"))
    }

    @Test fun linksAndRedaction() {
        assertTrue(AgentRules.scriptLink("javascript:fetch('/x')"))
        assertTrue(AgentRules.actionLink("/unsubscribe?u=77", "https://news.example.com/"))
        assertFalse(AgentRules.actionLink("/search?q=update+drivers", "https://news.example.com/"))
        assertFalse(AgentRules.actionLink("/blog/how-to-edit-video", "https://news.example.com/"))
        assertEquals("A [aadhaar-like hidden] P [PAN hidden] N [number hidden]", AgentRules.redact("A 1234 5678 9012 P ABCDE1234F N 1234567890"))
        assertEquals("b.co.in", AgentRules.registrable("a.b.co.in"))
        assertEquals("example.com", AgentRules.registrable("x.y.example.com"))
    }
}
