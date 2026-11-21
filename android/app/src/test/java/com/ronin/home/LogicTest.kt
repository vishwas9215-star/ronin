package com.ronin.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LogicTest {
    private val all = Service.values().toSet()

    @Test fun castleNeverInKids() {
        val kids = Catalog.visible(Catalog.kidsApps + AppEntry(Service.CASTLE.pkg, "Castle Kids", kids = true), all) { true }
        assertFalse(kids.any { it.pkg == Service.CASTLE.pkg })
        assertEquals("YouTube Kids", kids.first().label)
    }

    @Test fun switchedOffServiceLeavesHomeAndKids() {
        val subs = all - Service.NETFLIX
        assertFalse(Catalog.visible(Catalog.myApps, subs) { true }.any { it.pkg == Service.NETFLIX.pkg })
        assertFalse(Catalog.visible(Catalog.kidsApps, subs) { true }.any { it.pkg == Service.NETFLIX.pkg })
        assertFalse(Catalog.others(listOf(Service.NETFLIX.pkg to "Netflix"), subs).any { it.pkg == Service.NETFLIX.pkg })
    }

    @Test fun castleAlwaysOnHome() {
        assertTrue(Catalog.visible(Catalog.myApps, emptySet()) { true }.any { it.pkg == Service.CASTLE.pkg })
    }

    @Test fun missingAppsHidden() {
        val v = Catalog.visible(Catalog.myApps, all) { it == Service.CASTLE.pkg }
        assertEquals(listOf(Service.CASTLE.pkg), v.map { it.pkg })
    }

    @Test fun allAppsHidesGoogleHomeAndTclChannel() {
        val o = Catalog.others(listOf(Catalog.LAUNCHER_X to "Google TV", "com.tcl.waterfall.overseas" to "TCL Channel",
            "org.videolan.vlc" to "VLC", "com.android.vending" to "Play Store", "a.b" to "apple"), all)
        assertEquals(listOf("apple", "Play Store"), o.map { it.label })
    }

    @Test fun providerNames() {
        assertEquals(Service.NETFLIX, Service.fromProviderName("Netflix"))
        assertNull(Service.fromProviderName("Netflix basic with Ads"))
        assertEquals(Service.PRIME, Service.fromProviderName("Amazon Prime Video"))
        assertEquals(Service.JIOHOTSTAR, Service.fromProviderName("JioHotstar"))
        assertEquals(Service.JIOHOTSTAR, Service.fromProviderName("Hotstar"))
        assertNull(Service.fromProviderName("ZEE5"))
        val p = Tmdb.parseProviders("""{"results":[{"provider_id":8,"provider_name":"Netflix"},{"provider_id":232,"provider_name":"Zee5"},
            {"provider_id":119,"provider_name":"Amazon Prime Video"},{"provider_id":2336,"provider_name":"JioHotstar"}]}""")
        assertEquals(mapOf(Service.NETFLIX to 8, Service.PRIME to 119, Service.JIOHOTSTAR to 2336), p)
    }

    @Test fun keyKinds() {
        assertTrue(Tmdb.looksValid("0123456789abcdef0123456789ABCDEF"))
        assertFalse(Tmdb.isBearer("0123456789abcdef0123456789ABCDEF"))
        assertTrue(Tmdb.isBearer("eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiJ4In0.c2lnbmF0dXJl"))
        assertTrue(Tmdb.looksValid("eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiJ4In0.c2lnbmF0dXJl"))
        assertFalse(Tmdb.looksValid("hello"))
    }

    @Test fun discoverUrlIndiaFlatrateNoAdult() {
        val u = Tmdb.discoverUrl(false, 8, "new", LocalDate.of(2026, 11, 21))
        assertTrue(u, u.contains("watch_region=IN") && u.contains("with_watch_providers=8") && u.contains("flatrate") && u.contains("include_adult=false"))
        assertTrue(u, u.contains("primary_release_date.gte=2026-09-22") && u.contains("primary_release_date.lte=2026-11-21"))
        assertTrue(Tmdb.discoverUrl(true, 8, "soon", LocalDate.of(2026, 11, 21)).contains("first_air_date.gte=2026-11-22"))
    }

    @Test fun parseAndMerge() {
        val m = Tmdb.parseDiscover("""{"results":[{"id":1,"title":"A","poster_path":"/a.jpg","popularity":5},
            {"id":2,"title":"Adult","poster_path":"/b.jpg","popularity":99,"adult":true},{"id":3,"title":"NoPoster","popularity":50}]}""", false, Service.NETFLIX)
        val p = Tmdb.parseDiscover("""{"results":[{"id":1,"title":"A","poster_path":"/a.jpg","popularity":5},{"id":4,"title":"B","poster_path":"/c.jpg","popularity":9}]}""", false, Service.PRIME)
        val merged = Tmdb.merge(listOf(m, p), 10)
        assertEquals(listOf("B", "A"), merged.map { it.name })
        val tv = Tmdb.parseDiscover("""{"results":[{"id":7,"name":"Show","poster_path":"/s.jpg","first_air_date":"2026-01-01"}]}""", true, Service.JIOHOTSTAR)
        assertEquals("Show", tv.single().name); assertTrue(tv.single().isTv)
    }

    @Test fun shelfFiltersAndRoundTrips() {
        val s = Demo.shelf
        val noNetflix = s.forServices(all - Service.NETFLIX)
        assertFalse(noNetflix.topMovies.any { it.service == Service.NETFLIX })
        assertTrue(s.topMovies.size <= 10 && noNetflix.byLang.getValue(Lang.KOREAN).none { it.service == Service.NETFLIX })
        assertEquals(s, Shelf.fromJson(s.toJson()))
    }

    @Test fun horrorOnlyInItsOwnRow() {
        val s = Demo.shelf.forServices(all)
        val upper = s.topMovies + s.topShows + s.newReleases + s.comingSoon + s.byLang.values.flatten()
        assertFalse(upper.any { it.horror })
        assertEquals(setOf("Night Market", "The Quiet House"), s.horror.map { it.name }.toSet())
        val h = Tmdb.parseDiscover("""{"results":[{"id":9,"title":"Scary","poster_path":"/x.jpg","genre_ids":[53,27]}]}""", false, Service.PRIME)
        assertTrue(h.single().horror)
    }

    @Test fun wikidataLinks() {
        val j = """{"results":{"bindings":[
          {"label":{"value":"Netflix ID"},"val":{"value":"70131314"},"fmt":{"value":"https://www.netflix.com/title/${'$'}1"}},
          {"label":{"value":"Amazon Prime Video ID"},"val":{"value":"amzn1.dv.gti.abc"}},
          {"label":{"value":"Disney+ Hotstar ID"},"val":{"value":"1260012345"},"fmt":{"value":"https://www.hotstar.com/in/${'$'}1"}}]}}"""
        assertEquals("https://www.netflix.com/title/70131314", Links.pick(j, Service.NETFLIX))
        assertEquals("https://app.primevideo.com/detail?gti=amzn1.dv.gti.abc", Links.pick(j, Service.PRIME))
        assertEquals("https://www.hotstar.com/in/1260012345", Links.pick(j, Service.JIOHOTSTAR))
        assertNull(Links.pick("""{"results":{"bindings":[]}}""", Service.NETFLIX))
        assertTrue(Links.sparql(27205, false).contains("P4947") && Links.sparql(1399, true).contains("P4983"))
        assertEquals("Inception 2010", Links.searchText(Title(1, false, "Inception", null, null, 1.0, "2010-07-15", Service.NETFLIX)))
    }

    @Test fun languageRows() {
        val u = Tmdb.discoverUrl(true, 8, "top", LocalDate.of(2026, 11, 21), Lang.SOUTH)
        assertTrue(u, u.contains("with_original_language=ta%7Cte%7Cml%7Ckn") && u.contains("/discover/tv?"))
        assertEquals(listOf("Hollywood", "Bollywood", "Korean", "South Indian"), Lang.values().map { it.title })
    }

    @Test fun onlyGoogleHomeMainScreenIsReplaced() {
        assertTrue(HomeKeyService.isGoogleHomeScreen("com.google.android.tvlauncher", "com.google.android.tvlauncher.MainActivity"))
        assertTrue(HomeKeyService.isGoogleHomeScreen(Catalog.LAUNCHER_X, "com.google.android.apps.tv.launcherx.home.HomeActivity"))
        assertFalse(HomeKeyService.isGoogleHomeScreen(Catalog.LAUNCHER_X, "com.google.android.apps.tv.launcherx.details.DetailsActivity"))
        assertFalse(HomeKeyService.isGoogleHomeScreen("com.netflix.ninja", "com.netflix.MainActivity"))
    }
}
