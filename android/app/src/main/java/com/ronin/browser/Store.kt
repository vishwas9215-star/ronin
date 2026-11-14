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

    val history: List<Page> get() = read("history")

    fun addHistory(title: String, url: String) {
        if (!url.startsWith("http") || !keepHistory) return
        val cut = System.currentTimeMillis() - 90L * 24 * 3600 * 1000
        val list = read("history").filter { it.url != url && it.time > cut }.toMutableList()
        list.add(0, Page(title.take(150), url, System.currentTimeMillis()))
        write("history", list.take(3000))
    }

    fun clearHistory() = write("history", emptyList())

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
}
