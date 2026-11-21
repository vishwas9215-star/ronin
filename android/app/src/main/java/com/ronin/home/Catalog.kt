package com.ronin.home

/** Streaming services the family may subscribe to. Castle is always kept (owner's rule). */
enum class Service(val label: String, val badge: String, val color: Int, val pkg: String, val alwaysOn: Boolean = false) {
    NETFLIX("Netflix", "NETFLIX", 0xFFE50914.toInt(), "com.netflix.ninja"),
    PRIME("Prime Video", "PRIME", 0xFF1A98FF.toInt(), "com.amazon.amazonvideo.livingroom"),
    JIOHOTSTAR("JioHotstar", "JIOHOTSTAR", 0xFF6B3BFF.toInt(), "in.startv.hotstar"),
    CASTLE("Castle", "CASTLE", 0xFFF5A623.toInt(), "com.gxnet.castle.indiatv", alwaysOn = true);

    companion object {
        fun forPackage(pkg: String) = values().firstOrNull { it.pkg == pkg }
        /** TMDB provider name for India → our service (null = not one of ours). */
        fun fromProviderName(name: String): Service? {
            val n = name.trim().lowercase()
            return when {
                n == "netflix" -> NETFLIX
                n == "amazon prime video" -> PRIME
                n.contains("hotstar") -> JIOHOTSTAR
                else -> null
            }
        }
    }
}

/** One tile on the home screen. */
data class AppEntry(val pkg: String, val label: String, val kids: Boolean = false, val extraKey: String? = null)

object Catalog {
    const val LAUNCHER_X = "com.google.android.apps.tv.launcherx"
    const val RONIN_YOUTUBE = "com.ronin.browser"

    /** "My Apps" order (owner: Castle most important, then subscriptions, then Ronin YouTube, VLC...). */
    val myApps = listOf(
        AppEntry(Service.CASTLE.pkg, "Castle"),
        AppEntry(Service.NETFLIX.pkg, "Netflix"),
        AppEntry(Service.PRIME.pkg, "Prime Video"),
        AppEntry(Service.JIOHOTSTAR.pkg, "JioHotstar"),
        AppEntry(RONIN_YOUTUBE, "Ronin YouTube"),
        AppEntry("org.videolan.vlc", "VLC"),
        AppEntry("com.tcl.ui_mediaCenter", "Media Player"),
        AppEntry("com.tcl.magiconnectfree", "Magiconnect"),
    )

    /** Kids row: Ronin YouTube (Kids profile) first. Castle is never here (it cannot be filtered). */
    val kidsApps = listOf(
        AppEntry(RONIN_YOUTUBE, "YouTube Kids", kids = true, extraKey = "kids"),
        AppEntry(Service.JIOHOTSTAR.pkg, "JioHotstar Kids", kids = true),
        AppEntry(Service.NETFLIX.pkg, "Netflix Kids", kids = true),
        AppEntry(Service.PRIME.pkg, "Prime Video Kids", kids = true),
    )

    /** Never shown in "All Apps": the Google home itself, us, TCL Channel (disabled, ads). */
    val hidden = setOf(LAUNCHER_X, "com.ronin.home", "com.tcl.waterfall.overseas", "com.google.android.tvlauncher")

    /** A tile is shown only if its service is subscribed (Castle/non-service apps always). */
    fun visible(list: List<AppEntry>, subscribed: Set<Service>, installed: (String) -> Boolean): List<AppEntry> =
        list.filter { e ->
            val s = Service.forPackage(e.pkg)
            (s == null || s.alwaysOn || s in subscribed) && installed(e.pkg) &&
                !(e.kids && s == Service.CASTLE)
        }

    /** Other installed TV apps, alphabetic, without the ones already on the home rows. */
    fun others(all: List<Pair<String, String>>, subscribed: Set<Service>): List<AppEntry> {
        val shown = myApps.map { it.pkg }.toSet()
        return all.filter { (pkg, _) ->
            pkg !in shown && pkg !in hidden &&
                Service.forPackage(pkg).let { it == null || it.alwaysOn || it in subscribed }
        }.sortedBy { it.second.lowercase() }.map { AppEntry(it.first, it.second) }
    }
}
