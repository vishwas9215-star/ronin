package com.ronin.browser

import android.content.Context
import android.util.Base64
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The company's own small website icon (like a browser tab shows) for Ronin Messages: fetched once
 * over https from the company's own site (owner allowed this, 15 Nov), kept in the app's files, and
 * re-tried once a day when a site has none (or the phone was offline). Only the domain goes out, never an SMS.
 */
object SenderIcons {
    private val domainRe = Regex("^[a-z0-9-]+(\\.[a-z0-9-]+)+$")
    private const val MAX = 200 * 1024

    /** A data: URL for the page, or null (no icon / not yet). Call off the screen thread. */
    fun get(ctx: Context, domain: String): String? {
        val d = domain.lowercase().trim()
        if (!domainRe.matches(d) || d.length > 80) return null
        val dir = File(ctx.filesDir, "sender-icons").apply { mkdirs() }
        val f = File(dir, "$d.img"); val none = File(dir, "$d.none")
        if (f.exists() && f.length() > 0) return dataUrl(f.readBytes())
        if (none.exists() && System.currentTimeMillis() - none.lastModified() < 24L * 3600_000) return null
        val hosts = if (d.startsWith("www.")) listOf(d) else listOf(d, "www.$d")
        for (h in hosts) for (path in listOf("/apple-touch-icon.png", "/favicon.ico")) {
            val b = fetch("https://$h$path") ?: continue
            f.writeBytes(b)
            return dataUrl(b)
        }
        none.writeText("")
        return null
    }

    private fun fetch(u: String): ByteArray? = try {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 6000; c.readTimeout = 6000; c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) Ronin")
        try {
            if (c.responseCode != 200 || c.url.protocol != "https") null
            else c.inputStream.use { s ->
                val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192)
                while (out.size() <= MAX) { val n = s.read(buf); if (n < 0) break; out.write(buf, 0, n) }
                val b = out.toByteArray()
                if (b.size in 64..MAX && kind(b) != null) b else null
            }
        } finally { c.disconnect() }
    } catch (e: Exception) { null }

    /** Only real pictures (PNG, ICO, JPEG, GIF, WebP), never a web page. */
    private fun kind(b: ByteArray): String? = when {
        b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() -> "image/png"
        b[0] == 0.toByte() && b[1] == 0.toByte() && b[2] == 1.toByte() && b[3] == 0.toByte() -> "image/x-icon"
        b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "image/jpeg"
        b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() -> "image/gif"
        b.size > 12 && String(b, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
        else -> null
    }

    private fun dataUrl(b: ByteArray): String? = kind(b)?.let { "data:$it;base64," + Base64.encodeToString(b, Base64.NO_WRAP) }
}
