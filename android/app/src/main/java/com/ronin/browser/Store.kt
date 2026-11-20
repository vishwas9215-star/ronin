package com.ronin.browser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Bookmarks, history and small settings, kept in the app's private storage. */
class Store(ctx: Context) {
    private val p = ctx.getSharedPreferences("ronin", Context.MODE_PRIVATE)

    data class Page(val title: String, val url: String, val time: Long = 0)

    private fun read(key: String): MutableList<Page> {
        val a = try { JSONArray(p.getString(key, "[]")) } catch (e: Exception) { JSONArray() }
        return (0 until a.length()).map { a.getJSONObject(it) }
            .map { Page(it.optString("t"), it.optString("u"), it.optLong("w")) }.toMutableList()
    }

    private fun write(key: String, list: List<Page>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("t", it.title).put("u", it.url).put("w", it.time)) }
        p.edit().putString(key, a.toString()).apply()
    }

    val bookmarks: List<Page> get() = read("bookmarks")
    fun isBookmarked(url: String) = bookmarks.any { it.url == url }

    /** Adds the page, or removes it if it is already there. Returns true when added. */
    fun toggleBookmark(title: String, url: String): Boolean {
        if (!url.startsWith("http")) return false
        val list = read("bookmarks")
        if (list.removeAll { it.url == url }) { write("bookmarks", list); return false }
        list.add(0, Page(title.ifBlank { url }.take(120), url, System.currentTimeMillis()))
        write("bookmarks", list.take(2000))
        return true
    }

    fun removeBookmark(url: String) = write("bookmarks", read("bookmarks").filter { it.url != url })

    // History lives in memory; the file is written on a background thread (a page load never waits for it).
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "ronin-history").apply { isDaemon = true } }
    @Volatile private var hist: List<Page>? = null

    val history: List<Page> get() = hist ?: synchronized(this) { hist ?: read("history").also { hist = it } }

    fun addHistory(title: String, url: String) {
        if (!url.startsWith("http") || !keepHistory) return
        val now = System.currentTimeMillis()
        io.execute {
            synchronized(this) {
                val cut = now - 90L * 24 * 3600 * 1000
                val list = history.filter { it.url != url && it.time > cut }.toMutableList()
                list.add(0, Page(title.take(150), url, now))
                val keep = list.take(3000)
                hist = keep
                write("history", keep)
            }
        }
    }

    fun clearHistory() { synchronized(this) { hist = emptyList(); write("history", emptyList()) } }

    /** Safe Social settings (JSON from the launcher page: apps, family, reels, ytRestrict). */
    var social: String
        get() = p.getString("social", "") ?: ""
        set(v) { p.edit().putString("social", v).apply() }

    /** Deletes every visit whose address matches (adult / gambling sites). */
    fun removeHistory(match: (String) -> Boolean) {
        synchronized(this) {
            val all = history
            val keep = all.filter { !match(it.url) }
            if (keep.size != all.size) { hist = keep; write("history", keep) }
        }
    }

    /** Open tabs (addresses + which one was in front), kept for the next start (after an update, a swipe-away...). */
    var savedTabs: String
        get() = p.getString("savedTabs", "") ?: ""
        set(v) { p.edit().putString("savedTabs", v).apply() }

    /** Text size (A− / A+) per site, in per cent; 100 = the site's own size. */
    fun textZoom(host: String): Int = try { JSONObject(p.getString("textZoom", "{}") ?: "{}").optInt(host.removePrefix("www."), 100) } catch (e: Exception) { 100 }
    fun setTextZoom(host: String, pct: Int) {
        val h = host.removePrefix("www.")
        if (h.isEmpty()) return
        val o = try { JSONObject(p.getString("textZoom", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
        if (pct == 100) o.remove(h) else o.put(h, pct)
        p.edit().putString("textZoom", o.toString()).apply()
    }

    /** Sites he allowed to use the camera / microphone once: they are not asked again. */
    var mediaSites: Set<String>
        get() = p.getStringSet("mediaSites", emptySet()) ?: emptySet()
        set(v) = p.edit().putStringSet("mediaSites", v).apply()

    var keepHistory: Boolean
        get() = p.getBoolean("keepHistory", true)
        set(v) { p.edit().putBoolean("keepHistory", v).apply(); if (!v) clearHistory() }

    var night: Boolean
        get() = p.getBoolean("night", true)
        set(v) = p.edit().putBoolean("night", v).apply()

    var listsUpdated: Long
        get() = p.getLong("listsUpdated", 0)
        set(v) = p.edit().putLong("listsUpdated", v).apply()

    var updateChecked: Long
        get() = p.getLong("updateChecked", 0)
        set(v) = p.edit().putLong("updateChecked", v).apply()

    var userBanks: Set<String>
        get() = p.getStringSet("userBanks", emptySet()) ?: emptySet()
        set(v) = p.edit().putStringSet("userBanks", v).apply()

    /** Safe sites the user added after Ronin stopped them by mistake. */
    var trustedSites: Set<String>
        get() = p.getStringSet("trustedSites", emptySet()) ?: emptySet()
        set(v) = p.edit().putStringSet("trustedSites", v).apply()

    var threatsUpdated: Long
        get() = p.getLong("threatsUpdated", 0)
        set(v) = p.edit().putLong("threatsUpdated", v).apply()

    // ---- quick links on the new tab page -------------------------------------------------

    private var hiddenLinks: Set<String>
        get() = p.getStringSet("hiddenLinks", emptySet()) ?: emptySet()
        set(v) = p.edit().putStringSet("hiddenLinks", v).apply()

    private fun ownLinks(): List<QuickLink> = try {
        val a = org.json.JSONArray(p.getString("quickLinks", "[]"))
        (0 until a.length()).map { a.getJSONObject(it).let { j -> QuickLink(j.getString("s"), j.getString("n"), j.getString("u")) } }
    } catch (e: Exception) { emptyList() }

    private fun saveOwn(list: List<QuickLink>) {
        val a = org.json.JSONArray()
        list.forEach { a.put(org.json.JSONObject().put("s", it.section).put("n", it.name).put("u", it.url)) }
        p.edit().putString("quickLinks", a.toString()).apply()
    }

    /** Built-in links the user did not remove, then his own. */
    fun quickLinks(defaults: List<QuickLink>): List<QuickLink> {
        val hide = hiddenLinks
        return (defaults + ownLinks()).filter { it.url !in hide }
    }

    fun addQuickLink(section: String, name: String, url: String, sections: List<String> = listOf("Quick links", "School", "Trading", "Video calls")): Boolean {
        val l = QuickLink.clean(section, name, url, sections) ?: return false
        if (l.url in hiddenLinks) { hiddenLinks = hiddenLinks - l.url; return true } // a built-in link comes back
        saveOwn(ownLinks() + l)
        return true
    }

    fun removeQuickLink(url: String) {
        val own = ownLinks()
        if (own.any { it.url == url }) saveOwn(own.filter { it.url != url }) else hiddenLinks = hiddenLinks + url
    }

    // ---- "Data -> Excel + PDF" button: sites where the user switched it on ---------------

    var dataSites: Set<String>
        get() = p.getStringSet("dataSites", emptySet()) ?: emptySet()
        set(v) = p.edit().putStringSet("dataSites", v).apply()

    fun isDataSite(host: String): Boolean {
        val h = host.lowercase().removePrefix("www.")
        return h.isNotEmpty() && dataSites.any { h == it || h.endsWith(".$it") }
    }

    /** Switches the Data button on / off for a site; returns the new state. */
    fun toggleDataSite(host: String): Boolean {
        val h = host.lowercase().removePrefix("www.")
        if (h.isEmpty()) return false
        val on = !isDataSite(h)
        dataSites = if (on) dataSites + h else dataSites.filter { !(h == it || h.endsWith(".$it")) }.toSet()
        return on
    }
}
