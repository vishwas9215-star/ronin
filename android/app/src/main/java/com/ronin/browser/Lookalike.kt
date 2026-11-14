package com.ronin.browser

import java.net.IDN

/**
 * Fake bank sites ("sbi-kyc-update.xyz", "hdfcbank-login.top", "1cici.in").
 * Same rules as Ronin for Windows (internal/safemoney/lookalike.go); the word
 * lists come from rules.json.
 */
class Lookalike(
    private val brands: List<String>,
    private val shortBrands: Set<String>,
    private val bankingWords: List<String>,
) {
    /** The bank the address imitates, or null. Call only for sites that are not real banks. */
    fun brand(host0: String): String? {
        var labels = Rules.normalize(host0).split('.')
        if (labels.size < 2) return null
        // drop the ending (".com", ".co.in") so "in" or "co" never count as words
        labels = labels.dropLast(1)
        if (labels.size >= 2 && labels.last() in secondLevel) labels = labels.dropLast(1)
        val tokens = ArrayList<String>()
        for (lab0 in labels) {
            var lab = lab0
            if (lab.startsWith("xn--")) {
                lab = try { IDN.toUnicode(lab, IDN.ALLOW_UNASSIGNED) } catch (e: Exception) { lab }
            }
            lab = skeleton(lab)
            for (t in lab.split('-', '_')) if (t.isNotEmpty()) tokens.add(t)
        }
        val context = tokens.any { isBankingWord(it) }
        for (t in tokens) {
            val n = fold(t)
            for (b in brands) {
                val fb = fold(b)
                if (b in shortBrands) {
                    if (n == fb && context) return b
                    if (n.startsWith(fb) && n.length > fb.length && isBankingWord(n.substring(fb.length))) return b
                    if (n.endsWith(fb) && n.length > fb.length && isBankingWord(n.substring(0, n.length - fb.length))) return b
                    continue
                }
                if (n.contains(fb)) return b
                if (fb.length >= 5 && oneEditAway(n, fb)) return b
            }
        }
        return null
    }

    private fun isBankingWord(t0: String): Boolean {
        val t = fold(t0)
        if (bankingWords.any { t == fold(it) }) return true
        // two banking words glued together: "netbankinglogin", "kycupdate"
        for (w in bankingWords) {
            val fw = fold(w)
            if (t.startsWith(fw) && t.length > fw.length) {
                val rest = t.substring(fw.length)
                if (bankingWords.any { rest == fold(it) }) return true
            }
        }
        return false
    }

    companion object {
        private val secondLevel = setOf("co", "net", "org", "gov", "ac", "edu", "gen", "firm", "ind", "res", "com")

        private val swaps = listOf("0" to "o", "1" to "i", "l" to "i", "3" to "e", "5" to "s", "4" to "a",
            "@" to "a", "$" to "s", "rn" to "m", "vv" to "w")

        /** Characters scammers swap for each other map onto one form ("1cici" = "icici"). */
        fun fold(s: String): String {
            // like Go's strings.Replacer: one left-to-right pass, earlier pairs first
            val src = s.lowercase()
            val out = StringBuilder()
            var i = 0
            outer@ while (i < src.length) {
                for ((from, to) in swaps) {
                    if (src.startsWith(from, i)) { out.append(to); i += from.length; continue@outer }
                }
                out.append(src[i]); i++
            }
            return out.toString()
        }

        private val confusables: Map<Char, Char> = mapOf(
            'а' to 'a', 'е' to 'e', 'о' to 'o', 'р' to 'p', 'с' to 'c', 'у' to 'y', 'х' to 'x', 'і' to 'i', 'ј' to 'j',
            'ѕ' to 's', 'һ' to 'h', 'ԁ' to 'd', 'ӏ' to 'l', 'к' to 'k', 'м' to 'm', 'н' to 'h', 'т' to 't', 'в' to 'b',
            'α' to 'a', 'ο' to 'o', 'ρ' to 'p', 'ν' to 'v', 'κ' to 'k', 'ι' to 'i', 'τ' to 't', 'υ' to 'u', 'β' to 'b',
            'ı' to 'i', 'ɡ' to 'g', 'ɑ' to 'a', 'ⅰ' to 'i', 'ｓ' to 's', 'ｂ' to 'b', 'ｉ' to 'i',
            'á' to 'a', 'à' to 'a', 'â' to 'a', 'ä' to 'a', 'ã' to 'a', 'å' to 'a', 'é' to 'e', 'è' to 'e', 'ê' to 'e',
            'ë' to 'e', 'í' to 'i', 'ì' to 'i', 'î' to 'i', 'ï' to 'i', 'ó' to 'o', 'ò' to 'o', 'ô' to 'o', 'ö' to 'o',
            'õ' to 'o', 'ú' to 'u', 'ù' to 'u', 'û' to 'u', 'ü' to 'u', 'ç' to 'c', 'ñ' to 'n', 'ý' to 'y',
        )

        fun skeleton(s: String): String =
            if (s.all { it.code < 128 }) s else buildString { for (c in s) append(confusables[c] ?: c) }

        /** a and b differ by exactly one letter (changed, added or removed). */
        fun oneEditAway(a: String, b: String): Boolean {
            if (a == b) return false
            val la = a.length; val lb = b.length
            if (la - lb > 1 || lb - la > 1) return false
            var i = 0; var j = 0; var edits = 0
            while (i < la && j < lb) {
                if (a[i] == b[j]) { i++; j++; continue }
                edits++
                if (edits > 1) return false
                when {
                    la > lb -> i++
                    lb > la -> j++
                    else -> { i++; j++ }
                }
            }
            edits += (la - i) + (lb - j)
            return edits == 1
        }
    }
}
