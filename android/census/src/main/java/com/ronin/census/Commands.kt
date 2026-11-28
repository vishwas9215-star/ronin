package com.ronin.census

/**
 * What the enumerator said, turned into one step on the Census app's screen. Pure logic (unit-tested on the JVM):
 * the service gives the labels it sees on the screen, this decides what to do.
 */
sealed class Command {
    /** write [value] into the box whose label is [label] */
    data class Fill(val label: String, val value: String) : Command()
    /** tap the list item / dropdown / option whose text is [text] */
    data class Choose(val text: String) : Command()
    /** a button that moves on or saves: never pressed without his "हाँ" */
    data class Press(val text: String) : Command()
    object Confirm : Command()
    object Cancel : Command()
    object Scan : Command()
    data class Unknown(val heard: String) : Command()
}

object Commands {
    private val YES = setOf("हाँ", "हां", "हा", "ठीक है", "ठीक", "दबाओ", "दबा दो", "कर दो", "ओके", "ok", "yes")
    private val NO = setOf("नहीं", "नही", "रद्द", "रहने दो", "मत करो", "कैंसल", "cancel", "no")
    private val SCAN = setOf("स्क्रीन पढ़ो", "पढ़ो", "क्या खुला है", "खाने बताओ")

    /** Buttons that move on / save / submit: his "हाँ" is always needed. */
    val CONFIRM_FIRST = listOf("आगे बढ़ें", "सबमिट करें", "सबमिट", "सहेजें", "सेव", "पूर्ण", "सिंक", "लॉगआउट", "साफ़ करें", "पुनः स्थापित करें")
    private val PRESS_WORDS = mapOf(
        "आगे बढ़ो" to "आगे बढ़ें", "आगे बढ़ें" to "आगे बढ़ें", "अगला" to "आगे बढ़ें", "आगे" to "आगे बढ़ें", "नेक्स्ट" to "आगे बढ़ें",
        "सबमिट" to "सबमिट", "सबमिट करो" to "सबमिट", "सेव करो" to "सहेजें", "सहेजो" to "सहेजें", "दोहराएँ" to "दोहराएँ",
    )

    /** spoken text, simplified for matching: no punctuation, single spaces, nukta / chandrabindu variants folded */
    fun norm(s: String): String = s.lowercase()
        .replace('ँ', 'ं').replace("़", "").replace(Regex("[।,.!?:;()\\[\\]/\\-]"), " ")
        .replace(Regex("\\s+"), " ").trim()

    /**
     * [labels]: the labels of the boxes on screen; [options]: texts of tappable items (list items, dropdown entries,
     * buttons). Longest label that the utterance starts with wins; the rest is the value.
     */
    fun parse(heard: String, labels: List<String>, options: List<String>): Command {
        val h = norm(heard)
        if (h.isEmpty()) return Command.Unknown(heard)
        if (h in YES.map(::norm)) return Command.Confirm
        if (h in NO.map(::norm)) return Command.Cancel
        if (h in SCAN.map(::norm)) return Command.Scan
        PRESS_WORDS.entries.firstOrNull { norm(it.key) == h }?.let { return Command.Press(it.value) }
        // "<label> <value>"
        var best: Pair<String, String>? = null
        var bestLen = 0
        for (l in labels) {
            val nl = norm(l)
            if (nl.isEmpty()) continue
            val words = nl.split(' ')
            // the whole label, or its first two words ("परिवार के मुखिया का नाम" -> "मुखिया का नाम" also counts)
            val keys = buildList {
                add(nl)
                if (words.size > 2) { add(words.takeLast(3).joinToString(" ")); add(words.takeLast(2).joinToString(" ")) }
                // the first word alone only if no other box starts with it ("भवन 12")
                if (labels.count { norm(it).split(' ').first() == words.first() } == 1) add(words.first())
            }.distinct().filter { it.length >= 3 }
            for (k in keys) if ((h.startsWith("$k ") || h == k) && k.length > bestLen) {
                bestLen = k.length; best = l to heard.trim().let { cutAfterWords(it, k.split(' ').size) }
            }
        }
        best?.let { (l, v) -> if (v.isNotBlank()) return Command.Fill(l, v.trim()) }
        // an option on screen ("पक्का", "खाली", a list entry)
        options.firstOrNull { norm(it) == h }?.let { return Command.Choose(it) }
        options.filter { norm(it).isNotEmpty() && (norm(it).startsWith(h) || h.startsWith(norm(it))) }
            .maxByOrNull { norm(it).length }?.let { return Command.Choose(it) }
        return Command.Unknown(heard)
    }

    private fun cutAfterWords(s: String, n: Int): String {
        val parts = s.replace(Regex("[।,!?:;()/\\-]"), " ").trim().split(Regex("\\s+"))
        return if (parts.size <= n) "" else parts.drop(n).joinToString(" ")
    }

    fun needsConfirm(buttonText: String) = CONFIRM_FIRST.any { norm(buttonText).contains(norm(it)) }
}
