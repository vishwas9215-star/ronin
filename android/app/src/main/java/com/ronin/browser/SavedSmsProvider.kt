package com.ronin.browser

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import org.json.JSONArray

/**
 * Ronin Browser (3.14.3): hands the family's ⭐ saved SMS copies (kept from the time Messages was inside the browser)
 * to the new Ronin Phone app. Only an app signed with Ronin's own key may read it (signature permission).
 */
class SavedSmsProvider : ContentProvider() {
    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        val ctx = context!!
        val c = MatrixCursor(arrayOf("json"))
        c.addRow(arrayOf<Any>(SmsStore.saved(ctx).toString()))
        // row 2: the family's Calls / Messages settings (silence unknown callers, recording, OTP clean-up, language)
        val prefs = org.json.JSONObject()
        for (name in listOf("ronin_phone", "ronin_sms")) {
            val o = org.json.JSONObject()
            for ((k, v) in ctx.getSharedPreferences(name, Context.MODE_PRIVATE).all) when (v) {
                // numbers keep their kind (a Long read back as an Int would crash the setting)
                is Boolean, is String -> o.put(k, v)
                is Int -> o.put(k, "\u0000I" + v)
                is Long -> o.put(k, "\u0000L" + v)
                is Set<*> -> o.put(k, org.json.JSONArray(v.toList()))
                else -> {}
            }
            prefs.put(name, o)
        }
        prefs.put("lang", Lang.choice(ctx))
        c.addRow(arrayOf<Any>(prefs.toString()))
        return c
    }
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}

/** Ronin Phone, first start: take the ⭐ saved SMS over from Ronin Browser once. */
object SavedSmsImport {
    fun once(ctx: Context) {
        val p = ctx.getSharedPreferences("ronin_sms", Context.MODE_PRIVATE)
        if (p.getBoolean("savedImported", false) || ctx.packageName == "com.ronin.browser") return
        val browserThere = try { ctx.packageManager.getPackageInfo("com.ronin.browser", 0); true } catch (e: Exception) { false }
        if (!browserThere) { p.edit().putBoolean("savedImported", true).apply(); return }
        try {
            ctx.contentResolver.query(Uri.parse("content://com.ronin.browser.savedsms/saved"), null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val a = JSONArray(c.getString(0))
                    for (i in 0 until a.length()) SmsStore.save(ctx, a.getJSONObject(i))
                    if (c.moveToNext()) importPrefs(ctx, org.json.JSONObject(c.getString(0)))
                    p.edit().putBoolean("savedImported", true).apply()
                }
            }
        } catch (e: Exception) { } // an older browser without the provider: try again next time
    }

    private fun importPrefs(ctx: Context, o: org.json.JSONObject) {
        for (name in listOf("ronin_phone", "ronin_sms")) {
            val src = o.optJSONObject(name) ?: continue
            val e = ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
            for (k in src.keys()) {
                if (name == "ronin_sms" && k == "savedImported") continue
                when (val v = src.get(k)) {
                    is Boolean -> e.putBoolean(k, v)
                    is String -> when {
                        v.startsWith("\u0000I") -> v.substring(2).toIntOrNull()?.let { e.putInt(k, it) }
                        v.startsWith("\u0000L") -> v.substring(2).toLongOrNull()?.let { e.putLong(k, it) }
                        else -> e.putString(k, v)
                    }
                    is JSONArray -> e.putStringSet(k, (0 until v.length()).map { v.getString(it) }.toSet())
                }
            }
            e.apply()
        }
        o.optString("lang").takeIf { it.isNotEmpty() }?.let { Lang.set(ctx, it) }
    }
}
