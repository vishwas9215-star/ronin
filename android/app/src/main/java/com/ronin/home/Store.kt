package com.ronin.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Saved settings: subscription switches, TMDB key, last movie list. */
class Store(ctx: Context) {
    private val app = ctx.applicationContext
    private val p = app.getSharedPreferences("ronin_home", Context.MODE_PRIVATE)

    fun subscribed(): Set<Service> = Service.values().filter { it.alwaysOn || p.getBoolean("sub_" + it.name, true) }.toSet()
    fun setSubscribed(s: Service, on: Boolean) { if (!s.alwaysOn) p.edit().putBoolean("sub_" + s.name, on).apply() }

    var tmdbKey: String
        get() = p.getString("tmdb_key", "") ?: ""
        set(v) { p.edit().putString("tmdb_key", v.trim()).remove("shelf_time").apply() }

    var lastError: String
        get() = p.getString("last_error", "") ?: ""
        set(v) { p.edit().putString("last_error", v).apply() }

    private val shelfFile = File(app.filesDir, "shelf.json")

    fun cachedShelf(): Shelf? = try {
        if (shelfFile.exists()) Shelf.fromJson(JSONObject(shelfFile.readText())) else null
    } catch (e: Exception) { null }

    fun shelfFresh(): Boolean = System.currentTimeMillis() - p.getLong("shelf_time", 0) < 6 * 3600_000L

    fun saveShelf(s: Shelf) {
        shelfFile.writeText(s.toJson().toString())
        p.edit().putLong("shelf_time", System.currentTimeMillis()).putString("last_error", "").apply()
    }
}

/** Small poster loader: memory + disk cache, 3 threads, posts bitmaps on the main thread. */
object Images {
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private val mem = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(k: String, v: Bitmap) = v.byteCount
    }

    private fun hash(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    fun load(ctx: Context, url: String, into: ImageView) {
        into.tag = url
        mem.get(url)?.let { into.setImageBitmap(it); return }
        val dir = File(ctx.cacheDir, "img").apply { mkdirs() }
        pool.execute {
            val f = File(dir, hash(url))
            val bmp = try {
                if (!f.exists()) {
                    val c = URL(url).openConnection() as HttpURLConnection
                    c.connectTimeout = 8000; c.readTimeout = 15000
                    c.inputStream.use { inp -> f.outputStream().use { inp.copyTo(it) } }
                    c.disconnect()
                }
                BitmapFactory.decodeFile(f.path)
            } catch (e: Exception) { f.delete(); null }
            if (bmp != null) {
                mem.put(url, bmp)
                main.post { if (into.tag == url) into.setImageBitmap(bmp) }
            }
        }
    }
}
