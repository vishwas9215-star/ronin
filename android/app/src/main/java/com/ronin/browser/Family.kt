package com.ronin.browser

import android.app.AlertDialog
import android.content.Context
import android.widget.Toast

/** Family Filter: adult / gambling sites on this device fully off (children, elderly) or opened in a private tab. */
object Family {
    /** Same store as before 3.12, so the family's choice and PIN stay after the update. */
    fun prefs(ctx: Context) = ctx.getSharedPreferences("ronin_parts", Context.MODE_PRIVATE)

    // ---- adult / gambling sites on this device: fully off (children, elderly) or in a private tab
    fun adultBlocked(ctx: Context) = prefs(ctx).getBoolean("adultBlock", false)
    private fun pinHash(p: String) = java.security.MessageDigest.getInstance("SHA-256").digest(("ronin-adult:" + p).toByteArray()).joinToString("") { "%02x".format(it) }

    /** Google / Bing / DuckDuckGo search with SafeSearch forced on (only when adult sites are off). */
    fun safeSearch(url: String): String? {
        val u = try { java.net.URI(url) } catch (e: Exception) { return null }
        val h = u.host ?: return null
        val q = u.rawQuery ?: ""
        return when {
            Regex("(^|\\.)google\\.[a-z.]+$").containsMatchIn(h) && u.path == "/search" && !q.contains("safe=active") -> "$url&safe=active"
            (h == "www.bing.com" || h == "bing.com") && u.path == "/search" && !q.contains("adlt=strict") -> "$url&adlt=strict"
            h == "duckduckgo.com" && q.contains("q=") && !q.contains("kp=1") -> "$url&kp=1"
            else -> null
        }
    }

    fun chooseAdult(ctx: Context, first: Boolean) {
        val pinSet = prefs(ctx).getString("adultPin", "").orEmpty().isNotEmpty()
        if (!first && adultBlocked(ctx) && pinSet) {
            // turning it back on needs the PIN set by the parent
            val box = android.widget.EditText(ctx).apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD; hint = "PIN" }
            AlertDialog.Builder(ctx).setTitle("🔒 PIN to change Family Filter").setView(box)
                .setPositiveButton(L("खोलें", "Open")) { _, _ -> if (pinHash(box.text.toString()) == prefs(ctx).getString("adultPin", "")) askAdult(ctx, false) else Toast.makeText(ctx, L("PIN ग़लत है", "Wrong PIN"), Toast.LENGTH_LONG).show() }
                .setNegativeButton(L("रद्द करें", "Cancel"), null).show()
            return
        }
        askAdult(ctx, first)
    }

    private fun askAdult(ctx: Context, first: Boolean) {
        val items = arrayOf("🛡 Family Filter ON: restricted sites blocked (child / elderly device)", "🕶 Family Filter OFF: they open in Private (no history)")
        var pick = if (adultBlocked(ctx)) 0 else 1
        AlertDialog.Builder(ctx).setTitle("🛡 Family Filter for this device")
            .setSingleChoiceItems(items, pick) { _, i -> pick = i }
            .setPositiveButton(L("✔ ठीक है", "✔ OK")) { _, _ ->
                prefs(ctx).edit().putBoolean("adultAsked", true).putBoolean("adultBlock", pick == 0).apply()
                if (pick == 0) {
                    val box = android.widget.EditText(ctx).apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD; hint = "4-digit PIN (empty = no PIN)" }
                    AlertDialog.Builder(ctx).setTitle("🔒 Set a PIN? (so nobody else can change it)").setView(box)
                        .setPositiveButton("✔ Set") { _, _ -> prefs(ctx).edit().putString("adultPin", box.text.toString().trim().let { if (it.isEmpty()) "" else pinHash(it) }).apply() }
                        .setNegativeButton("No PIN", null).show()
                } else prefs(ctx).edit().putString("adultPin", "").apply()
            }
            .setNegativeButton(if (first) L("बाद में", "Later") else L("रद्द करें", "Cancel")) { _, _ -> if (first) prefs(ctx).edit().putBoolean("adultAsked", true).apply() }
            .setCancelable(!first).show()
    }
}
