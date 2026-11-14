package com.ronin.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LogicTest {
    private val rules = Rules(File("src/main/assets/ronin/rules.json").readText())

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
}
