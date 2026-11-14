package com.ronin.browser

import android.content.Context
import java.util.Locale

/**
 * The app speaks the phone's language: English on an English phone, Hindi on a Hindi phone.
 * Settings can fix it to one ("en" / "hi"); "auto" follows the phone. Ronin's pages get the same
 * answer as window.__roninLang, so the whole app always agrees.
 */
object Lang {
    private const val KEY = "lang"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("ronin", Context.MODE_PRIVATE)

    /** "auto", "en" or "hi". */
    fun choice(ctx: Context): String = prefs(ctx).getString(KEY, "auto")?.takeIf { it in setOf("auto", "en", "hi") } ?: "auto"
    fun set(ctx: Context, v: String) { if (v in setOf("auto", "en", "hi")) prefs(ctx).edit().putString(KEY, v).apply() }

    fun hindi(ctx: Context): Boolean = when (choice(ctx)) {
        "hi" -> true
        "en" -> false
        else -> Locale.getDefault().language == "hi"
    }
    /** Without a context (and in the plain unit tests) the phone's own language decides. */
    fun hindi(): Boolean = try { hindi(RoninApp.instance) } catch (e: Throwable) { Locale.getDefault().language == "hi" }
    fun code(ctx: Context) = if (hindi(ctx)) "hi" else "en"

    /** Script for Ronin's own pages, run before the page's own code. */
    fun pageScript(ctx: Context) = "window.__roninLang='" + code(ctx) + "';"
}

/** One sentence in both languages; the app shows the one that fits. */
fun L(hi: String, en: String): String = if (Lang.hindi()) hi else en
