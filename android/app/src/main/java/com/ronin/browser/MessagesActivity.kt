package com.ronin.browser

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import org.json.JSONObject

/** Ronin Messages: the phone's SMS app with the scam check built in (page: ui/tools/messages.html). */
class MessagesActivity : PhonePage() {
    override val page = "messages.html"
    private val watch = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(self: Boolean) = event("changed")
    }

    override fun onStart() {
        super.onStart()
        try { contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, watch) } catch (e: Exception) { }
    }
    override fun onStop() { super.onStop(); try { contentResolver.unregisterContentObserver(watch) } catch (e: Exception) { } }

    /** "sms:98765..." from another app, or a tapped notification: open that conversation. */
    override fun startQuery(i: Intent?): String {
        val to = i?.getStringExtra("address") ?: i?.data?.takeIf { it.scheme in setOf("sms", "smsto", "mms", "mmsto") }?.schemeSpecificPart?.substringBefore('?') ?: ""
        val body = i?.getStringExtra("sms_body") ?: i?.getStringExtra(Intent.EXTRA_TEXT) ?: ""
        return if (to.isEmpty() && body.isEmpty()) "" else "#to=" + Uri.encode(to) + "&body=" + Uri.encode(body)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val q = startQuery(intent)
        if (q.isNotEmpty()) web.evaluateJavascript("window.__roninOpen&&window.__roninOpen(" + JSONObject.quote(q.removePrefix("#")) + ")", null)
    }

    override fun handle(op: String, a: JSONObject, done: (Any?) -> Unit) {
        if (common(op, a, done)) return
        when (op) {
            "state" -> done(JSONObject().put("isDefault", SmsStore.isDefault(this)).put("read", has(Manifest.permission.READ_SMS))
                .put("contacts", has(Manifest.permission.READ_CONTACTS)).put("watcher", NotifyWatch.enabled(this)))
            "makeDefault" -> requestRole(RoleManager.ROLE_SMS) { ok ->
                askPermissions(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.POST_NOTIFICATIONS)) { done(JSONObject().put("ok", ok || SmsStore.isDefault(this))) }
            }
            "permissions" -> askPermissions(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.READ_CONTACTS)) { done(true) }
            "threads" -> bg { done(SmsStore.threads(this)) }
            "messages" -> bg { val t = a.optLong("thread"); done(SmsStore.messages(this, t)); SmsStore.markRead(this, t) }
            "send" -> bg { done(SmsStore.send(this, a.optString("to"), a.optString("body"), a.optInt("sub", -1))) }
            "delete" -> bg { done(SmsStore.deleteThread(this, a.optLong("thread"))) }
            "block" -> bg { done(SmsStore.block(this, a.optString("address"))) }
            "unblock" -> bg { done(SmsStore.unblock(this, a.optString("address"))) }
            "blocked" -> bg { done(SmsStore.blocked(this)) }
            "thread" -> bg { done(try { Telephony.Threads.getOrCreateThreadId(this, a.optString("address")) } catch (e: Exception) { -1L }) }
            else -> done(null)
        }
    }
}
