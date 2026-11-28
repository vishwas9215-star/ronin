package com.ronin.census

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandsTest {
    // the labels of the Census app's "जनगणना मकान की गणना" screen (owner's photos, 28 Nov)
    private val labels = listOf("परिवार के मुखिया को खोजें", "टिप्पणियाँ", "मुहल्ला/बस्ती", "परिवार के मुखिया का नाम")
    private val options = listOf("नया BN", "आगे बढ़ें", "दोहराएँ", "आवासीय", "आवासीय-सह-अन्य उपयोग", "खाली")

    @Test fun boxAndValue() {
        assertEquals(Command.Fill("मुहल्ला/बस्ती", "पूरब टोला"), Commands.parse("मुहल्ला बस्ती पूरब टोला", labels, options))
        assertEquals(Command.Fill("टिप्पणियाँ", "कोई नहीं"), Commands.parse("टिप्पणियां कोई नहीं", labels, options))
        // the end of a long label is enough
        assertEquals(Command.Fill("परिवार के मुखिया का नाम", "राम प्रसाद"), Commands.parse("मुखिया का नाम राम प्रसाद", labels, options))
    }

    @Test fun movingOnNeedsYes() {
        assertEquals(Command.Press("आगे बढ़ें"), Commands.parse("आगे बढ़ो", labels, options))
        assertEquals(Command.Press("आगे बढ़ें"), Commands.parse("अगला", labels, options))
        assertTrue(Commands.needsConfirm("आगे बढ़ें"))
        assertTrue(Commands.needsConfirm("सबमिट करें"))
        assertEquals(Command.Confirm, Commands.parse("हाँ", labels, options))
        assertEquals(Command.Confirm, Commands.parse("हां", labels, options))
        assertEquals(Command.Cancel, Commands.parse("नहीं", labels, options))
    }

    @Test fun choices() {
        assertEquals(Command.Choose("खाली"), Commands.parse("खाली", labels, options))
        assertEquals(Command.Choose("आवासीय"), Commands.parse("आवासीय", labels, options))
        assertTrue(!Commands.needsConfirm("आवासीय"))
    }

    @Test fun nothingGuessed() {
        assertTrue(Commands.parse("कुछ भी", labels, options) is Command.Unknown)
        assertTrue(Commands.parse("", labels, options) is Command.Unknown)
        // a label with no value is not a fill
        assertTrue(Commands.parse("टिप्पणियाँ", labels, options) !is Command.Fill)
    }

    @Test fun numbers() {
        assertEquals("35", Commands.digits("पैंतीस"))
        assertEquals("250", Commands.digits("दो सौ पचास"))
        assertEquals("5", Commands.digits("पांच"))
        assertEquals("5", Commands.digits("पाँच"))
        assertEquals("1985", Commands.digits("एक हज़ार नौ सौ पचासी"))
        assertEquals("35", Commands.digits("३५"))
        assertEquals("राम प्रसाद", Commands.digits("राम प्रसाद"))
        assertEquals("दो भाई", Commands.digits("दो भाई"))
    }

    @Test fun undo() { assertEquals(Command.Undo, Commands.parse("गलत", labels, options)) }

    @Test fun yesWithTheAction() {
        assertEquals(Command.Confirm, Commands.parse("हाँ आगे बढ़ो", labels, options))
        assertEquals(Command.Confirm, Commands.parse("हां सबमिट", labels, options))
    }
}
