package com.ronin.home

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Poster click → open THAT title, not just the app:
 *  1. Wikidata maps the TMDB id to the service's own title link (Netflix / Prime Video / Hotstar ids,
 *     with Wikidata's own URL pattern) → opened inside that app.
 *  2. Not found → Google TV's own search for "Title (year)" (the same page Gemini shows, with "Watch on …").
 *  3. Still nothing → the app's home.
 * Links are cached per title so the second click is instant.
 */
object Links {
    private const val TAG = "RoninHome"
    private val mem = HashMap<String, String>()

    fun sparql(tmdbId: Int, tv: Boolean): String = """
        SELECT ?label ?val ?fmt WHERE {
          ?item wdt:${if (tv) "P4983" else "P4947"} "$tmdbId" .
          ?item ?d ?val .
          ?p wikibase:directClaim ?d ; rdfs:label ?label .
          FILTER(LANG(?label) = "en")
          FILTER(CONTAINS(LCASE(?label), "netflix") || CONTAINS(LCASE(?label), "prime video") || CONTAINS(LCASE(?label), "hotstar"))
          OPTIONAL { ?p wdt:P1630 ?fmt }
        } LIMIT 20""".trimIndent()

    /** Pick the link for [service] from the Wikidata answer. */
    fun pick(json: String, service: Service): String? {
        val rows = JSONObject(json).optJSONObject("results")?.optJSONArray("bindings") ?: return null
        val want = when (service) {
            Service.NETFLIX -> "netflix"; Service.PRIME -> "prime video"; Service.JIOHOTSTAR -> "hotstar"; else -> return null
        }
        for (i in 0 until rows.length()) {
            val r = rows.getJSONObject(i)
            val label = r.optJSONObject("label")?.optString("value")?.lowercase() ?: continue
            if (!label.contains(want)) continue
            val v = r.optJSONObject("val")?.optString("value") ?: continue
            val fmt = r.optJSONObject("fmt")?.optString("value")
            val url = when {
                // Wikidata's Prime pattern points to amazon.com; the Prime Video TV app opens primevideo.com links
                service == Service.PRIME -> "https://www.primevideo.com/detail/$v"
                fmt != null && fmt.contains("$1") -> fmt.replace("$1", v)
                service == Service.NETFLIX -> "https://www.netflix.com/title/$v"
                else -> null
            }
            if (url != null && url.startsWith("https://")) return url
        }
        return null
    }

    /** Network (background thread). */
    fun lookup(t: Title): String? {
        val k = (if (t.isTv) "t" else "m") + t.id + t.service.name
        mem[k]?.let { return it.ifEmpty { null } }
        val url = try {
            val q = "https://query.wikidata.org/sparql?format=json&query=" + URLEncoder.encode(sparql(t.id, t.isTv), "UTF-8")
            val c = URL(q).openConnection() as HttpURLConnection
            c.connectTimeout = 5000; c.readTimeout = 7000
            c.setRequestProperty("User-Agent", "RoninHome/1.0 (family TV launcher; github.com/vishwas9215-star/ronin)")
            c.setRequestProperty("Accept", "application/sparql-results+json")
            val body = c.inputStream.bufferedReader().use { it.readText() }
            c.disconnect()
            pick(body, t.service)
        } catch (e: Exception) { Log.w(TAG, "wikidata: $e"); null }
        mem[k] = url ?: ""
        return url
    }

    fun searchText(t: Title) = if (t.date.length >= 4) "${t.name} ${t.date.take(4)}" else t.name

    /** Main thread: try each way in order; returns which one worked (for the log / emulator check). */
    fun open(ctx: Context, t: Title, link: String?): String {
        fun go(i: Intent): Boolean = try {
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
        } catch (e: ActivityNotFoundException) { false } catch (e: SecurityException) { false }

        if (link != null) {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(t.service.pkg)
            if (t.service == Service.NETFLIX) i.putExtra("source", "30")
            if (go(i)) return "deep-link"
        }
        val q = searchText(t)
        for (pkg in listOf("com.google.android.katniss", null)) {
            val s = Intent(Intent.ACTION_SEARCH).putExtra(SearchManager.QUERY, q)
            if (pkg != null) s.setPackage(pkg)
            if (go(s)) return "google-search"
        }
        if (go(Intent("android.search.action.GLOBAL_SEARCH").putExtra(SearchManager.QUERY, q))) return "global-search"
        val home = ctx.packageManager.getLeanbackLaunchIntentForPackage(t.service.pkg)
            ?: ctx.packageManager.getLaunchIntentForPackage(t.service.pkg)
        if (home != null && go(home)) return "app-home"
        return "none"
    }
}
