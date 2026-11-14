package com.ronin.browser

import org.json.JSONArray
import org.json.JSONObject

/** What sort of site an address is (same rules as Ronin for Windows). */
enum class Kind { GENERAL, BANK, TRADING, PAYMENT, AUTH, CALL, LOCAL }

/**
 * Ronin's shared site rules. rules.json is written by tools/exportlists from the
 * Go code, so Windows and Android always use the same lists.
 */
class Rules(json: String) {
    private val sites = mutableMapOf<String, List<String>>()
    val adBlock: List<String>
    val adAllow: List<String>
    val thirdParty: List<String>
    val adPaths: List<Pair<String, String>>
    val focusSites: List<String>
    val lookalike: Lookalike
    val quickLinks: List<QuickLink>
    val quickSections: List<String>
    val testThreat: String

    init {
        val o = JSONObject(json)
        val s = o.getJSONObject("sites")
        for (k in s.keys()) sites[k] = strings(s.getJSONArray(k))
        adBlock = strings(o.optJSONArray("adBlock"))
        adAllow = strings(o.optJSONArray("adAllow"))
        thirdParty = strings(o.optJSONArray("thirdParty"))
        val p = o.optJSONArray("adPaths") ?: JSONArray()
        adPaths = (0 until p.length()).map { p.getJSONArray(it).let { a -> a.getString(0) to a.getString(1) } }
        focusSites = strings(o.optJSONArray("focusSites"))
        val la = o.optJSONObject("lookalike") ?: JSONObject()
        lookalike = Lookalike(strings(la.optJSONArray("brands")), strings(la.optJSONArray("shortBrands")).toSet(),
            strings(la.optJSONArray("bankingWords")))
        testThreat = o.optString("testThreat", "ronin-phishing-test.com")
        val ql = o.optJSONArray("quickLinks") ?: JSONArray()
        quickLinks = (0 until ql.length()).map { ql.getJSONObject(it).let { j -> QuickLink(j.getString("section"), j.getString("name"), j.getString("url")) } }
        quickSections = strings(o.optJSONArray("quickSections")).ifEmpty { listOf("Quick links", "School", "Trading", "Video calls") }
    }

    fun list(name: String): List<String> = sites[name] ?: emptyList()

    fun isBankHost(host: String, userBanks: Collection<String> = emptyList()): Boolean {
        val h = normalize(host)
        if (h.isEmpty()) return false
        return h.endsWith(".bank.in") || matchAny(h, list("bank")) || matchAny(h, userBanks)
    }

    fun classify(url: String, userBanks: Collection<String> = emptyList()): Kind {
        val u = parse(url)
        when (u.scheme) {
            "http", "https" -> {}
            "about", "data", "blob", "file", "javascript" -> return Kind.LOCAL
            else -> return Kind.GENERAL
        }
        val host = u.host
        if (host.isEmpty()) return Kind.GENERAL
        if (host == "localhost" || host.startsWith("127.")) return Kind.LOCAL
        return when {
            isBankHost(host, userBanks) -> Kind.BANK
            matchAny(host, list("trading")) -> Kind.TRADING
            matchAny(host, list("payment")) -> Kind.PAYMENT
            matchAny(host, list("auth")) -> Kind.AUTH
            matchAny(host, list("call")) -> Kind.CALL
            else -> Kind.GENERAL
        }
    }

    /** The bank a GENERAL site pretends to be ("sbi-kyc-update.xyz"), or null. Same as Windows. */
    fun fakeBank(url: String, trusted: Collection<String> = emptyList(), userBanks: Collection<String> = emptyList()): String? {
        if (classify(url, userBanks) != Kind.GENERAL) return null
        val host = hostOf(url)
        if (host.isEmpty() || matchAny(host, list("family")) || matchAny(host, trusted)) return null
        return lookalike.brand(host)
    }

    companion object {
        fun strings(a: JSONArray?): List<String> =
            if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }

        fun normalize(h: String): String = h.trim().lowercase().trimEnd('.').trim('[', ']')

        /** host equals a domain in the list or is a sub-domain of it. */
        fun matchAny(host: String, list: Collection<String>): Boolean {
            val h = normalize(host)
            for (d0 in list) {
                val d = normalize(d0)
                if (d.isNotEmpty() && (h == d || h.endsWith(".$d"))) return true
            }
            return false
        }

        fun hostOf(url: String): String = parse(url).host

        /** A forgiving address splitter (ad addresses often break strict parsers). */
        fun parse(url: String): Parts {
            val t = url.trim()
            val c = t.indexOf(':')
            if (c <= 0) return Parts("", "", "")
            val scheme = t.substring(0, c).lowercase()
            if (!t.startsWith("//", c + 1)) return Parts(scheme, "", "")
            val rest = t.substring(c + 3)
            var end = rest.length
            for (ch in charArrayOf('/', '?', '#')) {
                val i = rest.indexOf(ch)
                if (i in 0 until end) end = i
            }
            var auth = rest.substring(0, end)
            auth = auth.substring(auth.lastIndexOf('@') + 1)
            val host = if (auth.startsWith("[")) auth.substringBefore("]").trimStart('[')
                       else auth.substringBefore(':')
            var path = rest.substring(end)
            val q = path.indexOfAny(charArrayOf('?', '#'))
            if (q >= 0) path = path.substring(0, q)
            if (path.isEmpty()) path = "/"
            return Parts(scheme, normalize(host), path)
        }
    }

    data class Parts(val scheme: String, val host: String, val path: String)
}

/** A quick link on the new tab page (same built-in list as Ronin for Windows). */
data class QuickLink(val section: String, val name: String, val url: String) {
    companion object {
        /** Checks a link the user typed; null when it cannot be used. Same rules as Windows. */
        fun clean(section: String, name0: String, url0: String, sections: List<String>): QuickLink? {
            var u = url0.trim()
            if (u.isEmpty()) return null
            if (!u.contains("://")) u = "https://$u"
            val p = Rules.parse(u)
            if (p.scheme != "https" && p.scheme != "http") return null
            if (!p.host.contains('.')) return null
            val rest = u.substringAfter("://").let { r -> val i = r.indexOfAny(charArrayOf('/', '?', '#')); if (i < 0) "/" else r.substring(i) }
            val url = "${p.scheme}://${p.host}${if (rest.startsWith("/")) rest else "/$rest"}"
            var name = name0.trim().ifEmpty { p.host.removePrefix("www.") }
            if (name.length > 30) name = name.take(30)
            return QuickLink(if (section in sections) section else sections.first(), name, url)
        }
    }
}
