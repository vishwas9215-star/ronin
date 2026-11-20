package com.ronin.browser

/** Small browser decisions without Android parts (so the plain unit tests can check them). */
object Nav {
    /** Router pages and home servers: plain http is allowed and never turned into https. */
    fun isLocalHost(host: String?): Boolean {
        val h = host?.lowercase()?.trim('[', ']') ?: return false
        if (h == "localhost" || h.endsWith(".local")) return true
        val ip = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.\\d{1,3}\\.\\d{1,3}$").find(h) ?: return false
        val a = ip.groupValues[1].toInt(); val b = ip.groupValues[2].toInt()
        return a == 127 || a == 10 || (a == 192 && b == 168) || (a == 172 && b in 16..31)
    }

    const val OPEN = 0
    const val ASK = 1
    const val DROP = 2

    /**
     * A link to another app (upi:, tel:, market:, intent:): opens only after a tap on the page itself;
     * a page that tries it by itself is asked about; an advert frame trying it without a tap is ignored.
     */
    fun outsideAction(mainFrame: Boolean, gesture: Boolean): Int = when {
        mainFrame && gesture -> OPEN
        !mainFrame && !gesture -> DROP
        else -> ASK
    }

    /** A data: address (a file the page made itself): its type and bytes, or null. */
    fun decodeDataUrl(url: String): Pair<String, ByteArray>? {
        if (!url.startsWith("data:", ignoreCase = true)) return null
        val comma = url.indexOf(',')
        if (comma < 0) return null
        val head = url.substring(5, comma)
        val body = url.substring(comma + 1)
        val mime = head.substringBefore(';').trim().ifEmpty { "text/plain" }
        return try {
            val bytes = if (head.split(';').any { it.trim().equals("base64", ignoreCase = true) })
                java.util.Base64.getMimeDecoder().decode(java.net.URLDecoder.decode(body.replace("+", "%2B"), "UTF-8"))
            else java.net.URLDecoder.decode(body.replace("+", "%2B"), "UTF-8").toByteArray(Charsets.UTF_8)
            mime.lowercase() to bytes
        } catch (e: Exception) { null }
    }

    /** A file name from Content-Disposition (filename*= or filename=), or "". */
    fun nameFromDisposition(cd: String?): String {
        if (cd.isNullOrBlank()) return ""
        Regex("filename\\*\\s*=\\s*(?:UTF-8|utf-8)?''([^;]+)", RegexOption.IGNORE_CASE).find(cd)?.let {
            return try { java.net.URLDecoder.decode(it.groupValues[1].trim(), "UTF-8") } catch (e: Exception) { it.groupValues[1] }
        }
        return Regex("filename\\s*=\\s*\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(cd)?.groupValues?.get(1)?.trim() ?: ""
    }

    /** A safe file name for Downloads/Ronin (no folders, no odd signs), with an extension that fits the type. */
    fun cleanFileName(name: String, mime: String): String {
        var n = name.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1f]+"), " ").trim().take(80)
        val ext = extFor(mime)
        if (n.isEmpty() || n.startsWith(".")) n = "Ronin_download_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        if (!n.contains('.') && ext.isNotEmpty()) n += ".$ext"
        return n
    }

    fun extFor(mime: String): String = when (mime.lowercase().substringBefore(';').trim()) {
        "application/pdf" -> "pdf"
        "text/csv" -> "csv"
        "text/plain" -> "txt"
        "text/html" -> "html"
        "application/json" -> "json"
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "application/vnd.ms-excel" -> "xls"
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx"
        "application/msword" -> "doc"
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
        "application/zip" -> "zip"
        else -> ""
    }

    const val RONIN_REPO_PATH = "/vishwas9215-star/ronin/"

    /**
     * An app file (.apk) needs no warning only when it is Ronin's own: from the Ronin repository on GitHub
     * (GitHub hands the file over from its file server, so the page it came from counts too), or F-Droid.
     */
    fun trustedApk(url: String, pageUrl: String): Boolean {
        fun fromRepo(u: String): Boolean {
            val p = Rules.parse(u)
            return p.scheme == "https" && p.host == "github.com" && p.path.lowercase().startsWith(RONIN_REPO_PATH)
        }
        val host = Rules.hostOf(url)
        if (Rules.matchAny(host, listOf("f-droid.org"))) return true
        if (fromRepo(url)) return true
        return (host.endsWith(".githubusercontent.com")) && fromRepo(pageUrl)
    }

    /** Text size steps for A− / A+ (per cent), kept between 50 % and 250 %. */
    fun nextZoom(current: Int, up: Boolean): Int = (if (up) current + 15 else current - 15).coerceIn(50, 250)

    /** Claude's own browser on his server: the live view page Ronin's "🤖 Claude" button opens. */
    const val CLAUDE_VIEW_DEFAULT = "https://16-4-152-212.sslip.io/view"

    /**
     * A one-time pairing link of Claude's browser (https://<host>/k/<48 hex>) -> that host, else null. Only free
     * name services that point at his own server (sslip.io, nip.io, duckdns.org) are accepted, so a look-alike link
     * from somewhere else can never become "Claude".
     */
    fun claudePairingHost(url: String): String? {
        val m = Regex("^https://([a-z0-9.-]+)/k/[0-9a-f]{48}/?$").find(url.trim().lowercase()) ?: return null
        val host = m.groupValues[1]
        return if (Regex("\\.(sslip\\.io|nip\\.io|duckdns\\.org)$").containsMatchIn(host) && !host.startsWith(".")) host else null
    }

    data class Hit(val title: String, val url: String)

    /**
     * Address-bar suggestions while typing: quick links and bookmarks first, then history; an address
     * that starts with the typed letters comes before one that only contains them. Two letters are enough.
     */
    fun suggest(typed: String, first: List<Hit>, history: List<Hit>, limit: Int = 5): List<Hit> {
        val q = typed.trim().lowercase()
        if (q.length < 2) return emptyList()
        fun bare(u: String) = u.lowercase().removePrefix("https://").removePrefix("http://").removePrefix("www.").removePrefix("m.")
        fun score(h: Hit): Int {
            val u = bare(h.url); val t = h.title.lowercase()
            return when {
                u.startsWith(q) -> 0
                t.startsWith(q) || t.split(' ', '-', '|', ':').any { it.startsWith(q) } -> 1
                u.contains(q) || t.contains(q) -> 2
                else -> 9
            }
        }
        val seen = HashSet<String>()
        val out = ArrayList<Pair<Int, Hit>>()
        (first.map { it to 0 } + history.map { it to 1 }).forEachIndexed { i, (h, src) ->
            if (!h.url.startsWith("http")) return@forEachIndexed
            val s = score(h)
            if (s == 9 || !seen.add(bare(h.url).trimEnd('/'))) return@forEachIndexed
            out.add((s * 100000 + src * 50000 + i) to h)
        }
        return out.sortedBy { it.first }.take(limit).map { it.second }
    }
}
