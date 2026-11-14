package com.ronin.browser

/**
 * Adult and gambling sites (same words as Ronin for Windows, internal/kids LooksAdult).
 * They open in a private tab and are never written to the history.
 */
object Sensitive {
    private val words = listOf(
        "porn", "porno", "xxx", "xvideos", "xnxx", "xhamster", "redtube", "youporn", "pornhub", "sex", "sexy",
        "nude", "nudes", "hentai", "erotic", "escort", "escorts", "camgirl", "onlyfans", "brazzers", "milf",
        "casino", "casinos", "betting", "bet365", "1xbet", "dafabet", "parimatch", "satta", "matka",
        "rummy", "teenpatti", "poker", "lottery", "gambling", "slots", "dream11", "mpl",
        "chaturbate", "stripchat", "spankbang", "eporner", "beeg", "motherless", "rule34", "fapello", "bangbros",
        "livejasmin", "bongacams", "camsoda", "myfreecams", "antarvasna", "desitales", "kamukta", "nhentai",
        "tnaflix", "youjizz", "thumbzilla", "porntrex", "hqporner", "fapster", "xtube", "cam4", "camsex",
    )

    fun site(url: String): Boolean {
        if (!url.startsWith("http")) return false
        val host = try { java.net.URI(url).host?.lowercase() ?: return false } catch (e: Exception) { return false }
        val parts = host.split('.', '-', '_').filter { it.isNotEmpty() }
        for (p in parts) for (w in words) {
            if (p == w) return true
            // glued names like "freeporn", "pornhd", "bigcasino"
            if (w.length >= 4 && w != "slots" && w != "poker" && w != "sexy" && p.contains(w)) return true
        }
        return false
    }
}
