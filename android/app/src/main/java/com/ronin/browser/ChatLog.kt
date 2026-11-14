package com.ronin.browser

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * "Deleted for everyone" WhatsApp (and other chat) messages: Ronin keeps the text of the chat
 * notifications it saw, on this phone only, for 7 days, so a message the sender deleted can still
 * be read. Text only (no photos). The family turns it on with the notification watcher and can
 * empty it any time.
 */
object ChatLog {
    private const val KEEP_MS = 7L * 24 * 3600 * 1000
    private const val MAX = 3000
    private val lock = Any()
    private fun file(ctx: Context) = File(ctx.filesDir, "chatlog.json")
    fun on(ctx: Context) = CallInfo.prefs(ctx).getBoolean("chatLog", true)

    private fun load(ctx: Context): JSONArray = try { JSONArray(file(ctx).readText()) } catch (e: Exception) { JSONArray() }
    private fun save(ctx: Context, a: JSONArray) {
        val now = System.currentTimeMillis()
        val keep = JSONArray()
        val start = maxOf(0, a.length() - MAX)
        for (i in start until a.length()) a.optJSONObject(i)?.let { if (now - it.optLong("t") < KEEP_MS) keep.put(it) }
        val tmp = File(ctx.filesDir, "chatlog.tmp"); tmp.writeText(keep.toString()); tmp.renameTo(file(ctx))
    }

    /** A message seen in a notification (the same one is kept once). */
    fun add(ctx: Context, app: String, chat: String, who: String, text: String, time: Long, key: String) {
        if (!on(ctx) || text.isBlank()) return
        synchronized(lock) {
            val a = load(ctx)
            for (i in maxOf(0, a.length() - 300) until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                if (o.optString("chat") == chat && o.optString("who") == who && o.optString("text") == text && kotlin.math.abs(o.optLong("t") - time) < 120_000) return
            }
            a.put(JSONObject().put("app", app).put("chat", chat).put("who", who).put("text", text).put("t", time).put("key", key))
            save(ctx, a)
        }
    }

    /** The sender deleted a message: WhatsApp then shows "This message was deleted" for that chat. */
    fun markDeleted(ctx: Context, chat: String, who: String) = mark(ctx) { it.optString("chat") == chat && (who.isEmpty() || it.optString("who") == who) }

    /** The app took its notification away by itself soon after (deleted, or read on another device). */
    fun markGone(ctx: Context, key: String) = mark(ctx) { it.optString("key") == key && System.currentTimeMillis() - it.optLong("t") < 10 * 60_000 }

    private fun mark(ctx: Context, match: (JSONObject) -> Boolean) {
        synchronized(lock) {
            val a = load(ctx)
            var changed = false
            for (i in a.length() - 1 downTo maxOf(0, a.length() - 300)) {
                val o = a.optJSONObject(i) ?: continue
                if (match(o) && !o.optBoolean("gone")) { o.put("gone", true); changed = true; break }
            }
            if (changed) save(ctx, a)
        }
    }

    fun all(ctx: Context): JSONArray = synchronized(lock) { load(ctx) }
    fun clear(ctx: Context) = synchronized(lock) { file(ctx).delete() }
}

/** The page of kept chat messages (ui/tools/chatlog.html), opened from Ronin's menu. */
class ChatLogActivity : PhonePage() {
    override val page = "chatlog.html"
    override fun handle(op: String, a: JSONObject, done: (Any?) -> Unit) {
        if (common(op, a, done)) return
        when (op) {
            "list" -> bg { done(ChatLog.all(this)) }
            "state" -> done(JSONObject().put("watcher", NotifyWatch.enabled(this)).put("on", ChatLog.on(this)))
            "set" -> { CallInfo.prefs(this).edit().putBoolean("chatLog", a.optBoolean("on")).apply(); if (!a.optBoolean("on")) ChatLog.clear(this); done(true) }
            "clear" -> { ChatLog.clear(this); done(true) }
            else -> done(null)
        }
    }
    companion object { fun open(ctx: Context) = ctx.startActivity(Intent(ctx, ChatLogActivity::class.java)) }
}
