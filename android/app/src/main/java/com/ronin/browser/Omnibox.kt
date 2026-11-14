package com.ronin.browser

import java.net.URLEncoder

/** Address box: an address opens (secure https), anything else is a Google search. */
object Omnibox {
    private val domainLike = Regex("""^[\p{L}\p{N}-]+(\.[\p{L}\p{N}-]+)+(:\d{1,5})?([/?#].*)?$""")
    private val blocked = listOf("javascript:", "file:", "data:", "content:", "intent:", "vbscript:")

    fun resolve(input: String): String? {
        val t = input.trim()
        if (t.isEmpty()) return null
        val low = t.lowercase()
        if (blocked.any { low.startsWith(it) }) return null
        if (low.startsWith("https://")) return t
        if (low.startsWith("http://")) {
            val host = Rules.hostOf(t)
            return if (host == "localhost" || host.startsWith("127.") || host.startsWith("192.168.")) t else "https://" + t.substring(7)
        }
        if (low.startsWith("localhost") || low.startsWith("127.0.0.1")) return "http://$t"
        if (!t.contains(' ') && domainLike.matches(t)) {
            val tld = t.substringBefore('/').substringBefore('?').substringBefore(':').substringAfterLast('.')
            if (tld.length >= 2 && tld.none { it.isDigit() } || t.substringBefore('/').count { it == '.' } == 3) return "https://$t"
        }
        return "https://www.google.com/search?q=" + URLEncoder.encode(t, "UTF-8")
    }
}
