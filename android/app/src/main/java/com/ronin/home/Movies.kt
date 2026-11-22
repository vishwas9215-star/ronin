package com.ronin.home

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate

/** A movie or show card. [service] = where the family can watch it (badge). */
data class Title(
    val id: Int,
    val isTv: Boolean,
    val name: String,
    val poster: String?,
    val backdrop: String?,
    val popularity: Double,
    val date: String,
    val service: Service,
    val horror: Boolean = false,
    val vote: Double = 0.0,
    val overview: String = "",
) {
    val year: String get() = date.take(4)
    fun toJson(): JSONObject = JSONObject().put("id", id).put("tv", isTv).put("name", name).put("horror", horror)
        .put("poster", poster ?: "").put("backdrop", backdrop ?: "").put("pop", popularity)
        .put("date", date).put("svc", service.name).put("vote", vote).put("ov", overview)

    companion object {
        fun fromJson(o: JSONObject) = Title(
            o.getInt("id"), o.getBoolean("tv"), o.getString("name"),
            o.optString("poster").ifEmpty { null }, o.optString("backdrop").ifEmpty { null },
            o.optDouble("pop", 0.0), o.optString("date"), Service.valueOf(o.getString("svc")), o.optBoolean("horror"),
            o.optDouble("vote", 0.0), o.optString("ov"),
        )
    }
}

/** Family taste (owner): Hollywood first; wife also Bollywood, Korean, South Indian. Movies + shows mixed. */
enum class Lang(val title: String, val codes: String) {
    HOLLYWOOD("Hollywood", "en"), BOLLYWOOD("Bollywood", "hi"), KOREAN("Korean", "ko"), SOUTH("South Indian", "ta|te|ml|kn")
}

/** Movies & Shows rows (no hero card: owner dropped "#1 in India"). */
data class Shelf(
    val topMovies: List<Title>,
    val topShows: List<Title>,
    val newReleases: List<Title>,
    val comingSoon: List<Title>,
    val byLang: Map<Lang, List<Title>> = emptyMap(),
    val horror: List<Title> = emptyList(),
) {
    /** Owner: horror only in its own row at the very bottom; no scary poster in the upper rows. */
    fun forServices(s: Set<Service>): Shelf {
        fun f(l: List<Title>, n: Int) = l.filter { it.service in s && !it.horror }.take(n)
        val allHorror = (horror + topMovies + newReleases + comingSoon + byLang.values.flatten()).filter { it.horror && it.service in s }
        return Shelf(f(topMovies, 10), f(topShows, 10), f(newReleases, 20), f(comingSoon, 20),
            byLang.mapValues { (_, v) -> f(v, 20) }, allHorror.distinctBy { it.id }.sortedByDescending { it.popularity }.take(20))
    }

    fun toJson(): JSONObject = JSONObject()
        .put("top_movies", JSONArray(topMovies.map { it.toJson() }))
        .put("top_shows", JSONArray(topShows.map { it.toJson() }))
        .put("new", JSONArray(newReleases.map { it.toJson() }))
        .put("soon", JSONArray(comingSoon.map { it.toJson() }))
        .put("horror", JSONArray(horror.map { it.toJson() }))
        .apply { for ((l, v) in byLang) put("lang_" + l.name, JSONArray(v.map { it.toJson() })) }

    companion object {
        val EMPTY = Shelf(emptyList(), emptyList(), emptyList(), emptyList())
        private fun list(a: JSONArray?) = (0 until (a?.length() ?: 0)).map { Title.fromJson(a!!.getJSONObject(it)) }
        fun fromJson(o: JSONObject) = Shelf(list(o.optJSONArray("top_movies")), list(o.optJSONArray("top_shows")),
            list(o.optJSONArray("new")), list(o.optJSONArray("soon")),
            Lang.values().associateWith { list(o.optJSONArray("lang_" + it.name)) }.filterValues { it.isNotEmpty() },
            list(o.optJSONArray("horror")))
    }
}

/** TMDB (themoviedb.org) client: India, only the family's subscribed services, English titles, no adult titles. */
object Tmdb {
    const val API = "https://api.themoviedb.org/3"
    const val IMG = "https://image.tmdb.org/t/p/"

    /** v4 "API Read Access Token" (long, starts eyJ) goes in a header; v3 "API key" (32 chars) in the URL. */
    fun isBearer(key: String) = key.trim().startsWith("eyJ") || key.trim().length > 40

    fun looksValid(key: String): Boolean {
        val k = key.trim()
        return if (isBearer(k)) k.count { it == '.' } == 2 else Regex("[0-9a-fA-F]{32}").matches(k)
    }

    const val HORROR = 27

    fun discoverUrl(tv: Boolean, providerId: Int, kind: String, today: LocalDate, lang: Lang? = null, genre: Int? = null): String {
        val dateField = if (tv) "first_air_date" else "primary_release_date"
        val p = linkedMapOf(
            "watch_region" to "IN", "with_watch_providers" to providerId.toString(),
            "with_watch_monetization_types" to "flatrate", "include_adult" to "false",
            "language" to "en-IN", "page" to "1",
        )
        when (kind) {
            "top" -> p["sort_by"] = "popularity.desc"
            "new" -> { p["sort_by"] = "popularity.desc"; p["$dateField.gte"] = today.minusDays(60).toString(); p["$dateField.lte"] = today.toString() }
            "soon" -> { p["sort_by"] = "popularity.desc"; p["$dateField.gte"] = today.plusDays(1).toString(); p["$dateField.lte"] = today.plusDays(90).toString() }
        }
        if (lang != null) p["with_original_language"] = lang.codes
        if (genre != null) p["with_genres"] = genre.toString()
        val q = p.entries.joinToString("&") { it.key + "=" + URLEncoder.encode(it.value, "UTF-8") }
        return "$API/discover/${if (tv) "tv" else "movie"}?$q"
    }

    /** /watch/providers/movie?watch_region=IN → our services with their TMDB ids. */
    fun parseProviders(json: String): Map<Service, Int> {
        val out = linkedMapOf<Service, Int>()
        val arr = JSONObject(json).optJSONArray("results") ?: return out
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val s = Service.fromProviderName(o.optString("provider_name")) ?: continue
            if (s !in out) out[s] = o.getInt("provider_id")
        }
        return out
    }

    fun parseDiscover(json: String, tv: Boolean, service: Service): List<Title> {
        val arr = JSONObject(json).optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            if (o.optBoolean("adult", false)) return@mapNotNull null
            val name = (if (tv) o.optString("name") else o.optString("title")).ifBlank { return@mapNotNull null }
            val genres = o.optJSONArray("genre_ids")
            val horror = genres != null && (0 until genres.length()).any { genres.optInt(it) == HORROR }
            Title(o.getInt("id"), tv, name,
                o.optString("poster_path").takeIf { it.startsWith("/") },
                o.optString("backdrop_path").takeIf { it.startsWith("/") },
                o.optDouble("popularity", 0.0),
                if (tv) o.optString("first_air_date") else o.optString("release_date"),
                service, horror, o.optDouble("vote_average", 0.0), o.optString("overview").take(400))
        }
    }

    /** Mix services by popularity, one card per title, keep [n]. */
    fun merge(lists: List<List<Title>>, n: Int): List<Title> {
        val seen = HashSet<String>()
        return lists.flatten().sortedByDescending { it.popularity }
            .filter { seen.add((if (it.isTv) "t" else "m") + it.id) && it.poster != null }
            .take(n)
    }

    private fun get(url: String, key: String): String {
        val full = if (isBearer(key)) url else url + (if ('?' in url) "&" else "?") + "api_key=" + key.trim()
        val c = URL(full).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 10000
        if (isBearer(key)) c.setRequestProperty("Authorization", "Bearer " + key.trim())
        c.setRequestProperty("Accept", "application/json")
        try {
            if (c.responseCode != 200) throw IllegalStateException("TMDB ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    /** Network: all four rows for every service we know (filtered later by the switches). */
    fun fetch(key: String, today: LocalDate = LocalDate.now()): Shelf {
        val ids = parseProviders(get("$API/watch/providers/movie?watch_region=IN&language=en-IN", key))
        val top = mutableListOf<List<Title>>(); val topTv = mutableListOf<List<Title>>()
        val new = mutableListOf<List<Title>>(); val soon = mutableListOf<List<Title>>()
        val horror = mutableListOf<List<Title>>()
        val langs = Lang.values().associateWith { mutableListOf<List<Title>>() }
        for ((s, id) in ids) {
            horror += parseDiscover(get(discoverUrl(false, id, "top", today, genre = HORROR), key), false, s)
            for (l in Lang.values()) for (tv in listOf(false, true))
                langs.getValue(l) += parseDiscover(get(discoverUrl(tv, id, "top", today, l), key), tv, s)
            top += parseDiscover(get(discoverUrl(false, id, "top", today), key), false, s)
            topTv += parseDiscover(get(discoverUrl(true, id, "top", today), key), true, s)
            new += parseDiscover(get(discoverUrl(false, id, "new", today), key), false, s)
            new += parseDiscover(get(discoverUrl(true, id, "new", today), key), true, s)
            soon += parseDiscover(get(discoverUrl(false, id, "soon", today), key), false, s)
            soon += parseDiscover(get(discoverUrl(true, id, "soon", today), key), true, s)
        }
        // keep extra so that switching a service off still leaves 10
        return Shelf(merge(top, 40), merge(topTv, 40), merge(new, 40), merge(soon, 40), langs.mapValues { merge(it.value, 40) }, merge(horror, 30))
    }
}
