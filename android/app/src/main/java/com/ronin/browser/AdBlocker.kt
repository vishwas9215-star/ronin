package com.ronin.browser

import java.io.BufferedReader

/**
 * Network ad-block. The big public lists (several lakh domains) are kept as a
 * sorted array of 64-bit hashes: a few MB instead of tens of MB of text.
 */
class AdBlocker(rules: Rules) {
    private val builtinBlock = HashSet(rules.adBlock)
    private val allow = HashSet(rules.adAllow)
    private val thirdParty = rules.thirdParty
    private val paths = rules.adPaths
    @Volatile private var hashes = LongArray(0)
    @Volatile private var threats = LongArray(0)
    private val testThreat = rules.testThreat

    val threatSize: Int get() = threats.size

    /** Reads a phishing / malware list (one domain per line) without using it yet. */
    fun parseThreats(reader: BufferedReader): LongArray {
        val tmp = LongArrayBuilder()
        reader.forEachLine { line ->
            val d = line.trim().lowercase()
            if (d.isNotEmpty() && !d.startsWith("#")) tmp.add(hash(d))
        }
        val arr = tmp.toArray()
        arr.sort()
        return arr
    }

    fun setThreats(arr: LongArray) { threats = arr }

    /** Reads and uses a phishing / malware list. */
    fun loadThreats(reader: BufferedReader): Int = parseThreats(reader).also { setThreats(it) }.size

    /** host (or a parent domain) is a known phishing or malware site. */
    fun dangerous(host0: String): Boolean {
        var h = Rules.normalize(host0)
        val a = threats
        while (h.isNotEmpty()) {
            if (h == testThreat || (a.isNotEmpty() && a.binarySearch(hash(h)) >= 0)) return true
            val i = h.indexOf('.')
            if (i < 0) return false
            h = h.substring(i + 1)
        }
        return false
    }

    val listSize: Int get() = hashes.size

    /** Loads the downloaded list: one domain per line, "@@domain" = allow, "#" = comment. */
    fun loadList(reader: BufferedReader): Int {
        val tmp = LongArrayBuilder()
        val extraAllow = ArrayList<String>()
        reader.forEachLine { line ->
            val d = line.trim().lowercase()
            when {
                d.isEmpty() || d.startsWith("#") -> {}
                d.startsWith("@@") -> extraAllow.add(d.substring(2))
                else -> tmp.add(hash(d))
            }
        }
        val arr = tmp.toArray()
        arr.sort()
        synchronized(allow) { allow.addAll(extraAllow) }
        hashes = arr
        return arr.size
    }

    private fun listed(h: String): Boolean {
        if (builtinBlock.contains(h)) return true
        val t = threats
        if (t.isNotEmpty() && t.binarySearch(hash(h)) >= 0) return true
        val a = hashes
        return a.isNotEmpty() && a.binarySearch(hash(h)) >= 0
    }

    /** Same order as Windows: the most specific rule (allow or block) wins. */
    fun blocked(url: String): Boolean {
        val u = Rules.parse(url)
        if (u.scheme != "http" && u.scheme != "https" && u.scheme != "ws" && u.scheme != "wss") return false
        val host = u.host
        if (host.isEmpty()) return false
        val path = u.path
        for ((ph, prefix) in paths) {
            if ((host == ph || host.endsWith(".$ph")) && path.startsWith(prefix)) return true
        }
        var h = host
        while (true) {
            if (synchronized(allow) { allow.contains(h) }) return false
            if (listed(h)) return true
            val i = h.indexOf('.')
            if (i < 0) return false
            h = h.substring(i + 1)
        }
    }

    /** blocked() plus "advert networks inside other sites". */
    fun blockedFrom(url: String, topHost: String): Boolean {
        if (blocked(url)) return true
        val host = Rules.hostOf(url)
        val top = Rules.normalize(topHost)
        // on one of these sites itself (opened on purpose), their picture / stream servers must load (same as Windows)
        if (thirdParty.any { top == it || top.endsWith(".$it") }) return false
        for (d in thirdParty) {
            if (host == d || host.endsWith(".$d")) return !(top == d || top.endsWith(".$d"))
        }
        return false
    }

    companion object {
        /** FNV-1a 64-bit hash of the domain text. */
        fun hash(s: String): Long {
            var h = -0x340d631b7bdddcdbL
            for (b in s.toByteArray(Charsets.UTF_8)) {
                h = h xor (b.toLong() and 0xff)
                h *= 0x100000001b3L
            }
            return h
        }
    }

    private class LongArrayBuilder {
        private var a = LongArray(1 shl 16)
        private var n = 0
        fun add(v: Long) {
            if (n == a.size) a = a.copyOf(a.size * 2)
            a[n++] = v
        }
        fun toArray(): LongArray = a.copyOf(n)
    }
}
