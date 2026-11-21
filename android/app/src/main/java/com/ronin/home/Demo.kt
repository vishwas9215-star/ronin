package com.ronin.home

/** Sample data for the emulator check only (started with --ez demo true). Names are made up. */
object Demo {
    private val palette = intArrayOf(0xFF3B5BDB.toInt(), 0xFF2B8A3E.toInt(), 0xFFC2255C.toInt(), 0xFF7048E8.toInt(),
        0xFFE8590C.toInt(), 0xFF0C8599.toInt(), 0xFF5C940D.toInt(), 0xFF862E9C.toInt())
    fun color(s: String) = palette[Math.floorMod(s.hashCode(), palette.size)]

    val otherApps = listOf("com.android.vending" to "Google Play Store", "com.google.android.youtube.tv" to "YouTube",
        "com.android.tv.settings" to "Settings", "com.ronin.tv" to "Ronin Terminal", "com.google.android.apps.tv.launcherx" to "Google TV")

    private fun t(i: Int, name: String, s: Service, tv: Boolean = false) =
        Title(i, tv, name, null, null, 1000.0 - i, "2026-11-01", s)

    private val n = Service.NETFLIX; private val p = Service.PRIME; private val j = Service.JIOHOTSTAR
    val shelf = Shelf(
        listOf(t(1, "Monsoon Express", n), t(2, "The Last Kingdom of Ice", p), t(3, "Cricket Dreams", j), t(4, "Night Market", n),
            t(5, "Silent River", p), t(6, "Big Day Out", j), t(7, "Code Red", n), t(8, "Desert Song", p), t(9, "Paper Boats", j), t(10, "Skyline", n)),
        listOf(t(11, "City of Lights", n, true), t(12, "Village Doctor", j, true), t(13, "The Heist Club", p, true), t(14, "Family Kitchen", j, true),
            t(15, "Space Cadets", n, true), t(16, "Court Room", p, true), t(17, "Hill Station", j, true), t(18, "Detective Rao", n, true),
            t(19, "Startup Days", p, true), t(20, "Ocean Deep", n, true)),
        listOf(t(21, "Winter Road", n), t(22, "Tiger Trail", j), t(23, "Second Innings", p, true), t(24, "Glass House", n, true), t(25, "Old Friends", j)),
        listOf(t(31, "Festival Nights", p), t(32, "Mountain Rescue", n), t(33, "School Days 2", j, true)),
        mapOf(
            Lang.HOLLYWOOD to listOf(t(41, "Steel Horizon", n), t(42, "The Quiet House", p), t(43, "Galaxy Run", j), t(44, "Last Train West", n, true), t(45, "Deep Blue", p)),
            Lang.BOLLYWOOD to listOf(t(51, "Dil Ki Baat", p), t(52, "Mumbai Nights", n, true), t(53, "Rang Barse", j), t(54, "Shaadi Season", p)),
            Lang.KOREAN to listOf(t(61, "Seoul Spring", n, true), t(62, "My Neighbour Chef", n, true), t(63, "Han River", p), t(64, "Office Love", n, true)),
            Lang.SOUTH to listOf(t(71, "Kaaval", j), t(72, "Simha", p), t(73, "Kerala Diaries", j, true), t(74, "Hyderabad Blues", n)),
        ),
    )
}
