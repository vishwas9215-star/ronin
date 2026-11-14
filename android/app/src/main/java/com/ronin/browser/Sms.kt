package com.ronin.browser

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.BlockedNumberContract
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * Ronin Messages: Ronin as the phone's SMS app. Every SMS is sorted:
 *  work    = काम के (banks, OTP, school, government, saved contacts)
 *  unknown = अनजान (a number not in the contacts)
 *  ads     = विज्ञापन (promotional senders, "-P" headers, offer words)
 *  scam    = ठगी (the scam check says so)
 * Only "work" rings; "scam" gives a quiet warning; ads / unknown stay silent in their tab.
 */
object SmsSort {
    // Indian sender IDs (DLT): "VM-HDFCBK-S" — P promotional, S service, T transactional, G government
    private val header = Regex("^[A-Z]{2}-[A-Z0-9]{3,9}(-([PSTG]))?$", RegexOption.IGNORE_CASE)
    private val promo = Regex("(\\boffer|\\bsale\\b|discount|% ?off|cashback|\\bfree\\b|\\bwin\\b|lowest price|limited (time|period)|hurry|shop now|buy now|apply now|pre-?approved|\\bcoupon|\\bdeal|ऑफर|छूट|सेल|मुफ्त|मुफ़्त|जीतें)", RegexOption.IGNORE_CASE)

    fun category(address: String, body: String, inContacts: Boolean, scamLevel: String): String {
        if (scamLevel.isNotEmpty()) return "scam"
        val a = address.trim()
        header.find(a)?.let { m ->
            return when (m.groupValues[2].uppercase()) { "P" -> "ads"; "S", "T", "G" -> "work"; else -> if (promo.containsMatchIn(body)) "ads" else "work" }
        }
        val digits = a.filter { it.isDigit() }
        if (a.any { it.isLetter() }) return if (promo.containsMatchIn(body)) "ads" else "work" // old-style "JIOINF"
        if (digits.length in 3..6) return "work" // short codes (bank, operator)
        if (inContacts) return "work"
        return if (promo.containsMatchIn(body)) "ads" else "unknown"
    }
}

object SmsStore {
    private val nameCache = HashMap<String, String?>()

    fun isDefault(ctx: Context) = Telephony.Sms.getDefaultSmsPackage(ctx) == ctx.packageName

    fun contactName(ctx: Context, address: String): String? {
        nameCache[address]?.let { return it }
        if (address.any { it.isLetter() } || ctx.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return null
        var name: String? = null
        try {
            ctx.contentResolver.query(Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address)),
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) name = c.getString(0) }
        } catch (e: Exception) { }
        nameCache[address] = name
        return name
    }

    fun categorize(ctx: Context, address: String, body: String): Pair<String, ScamCheck.Result> {
        val r = RoninApp.instance.rules.scam.text(body)
        return SmsSort.category(address, body, contactName(ctx, address) != null, r.level) to r
    }

    private fun Cursor.str(c: String) = getString(getColumnIndexOrThrow(c)) ?: ""
    private fun Cursor.lng(c: String) = getLong(getColumnIndexOrThrow(c))

    /** The conversations, newest first, each with its tab. */
    fun threads(ctx: Context): JSONArray {
        val out = JSONArray()
        val seen = HashMap<Long, JSONObject>()
        try {
            ctx.contentResolver.query(Telephony.Sms.CONTENT_URI, arrayOf("_id", "thread_id", "address", "body", "date", "type", "read"),
                null, null, "date DESC LIMIT 3000")?.use { c ->
                while (c.moveToNext()) {
                    val th = c.lng("thread_id")
                    val unread = c.getInt(c.getColumnIndexOrThrow("read")) == 0 && c.getInt(c.getColumnIndexOrThrow("type")) == Telephony.Sms.MESSAGE_TYPE_INBOX
                    val have = seen[th]
                    if (have != null) { if (unread) have.put("unread", have.optInt("unread") + 1); have.put("count", have.optInt("count") + 1); continue }
                    val addr = c.str("address"); val body = c.str("body")
                    val (cat, r) = categorize(ctx, addr, body)
                    val o = JSONObject().put("thread", th).put("address", addr).put("name", contactName(ctx, addr) ?: "")
                        .put("snippet", body.take(140)).put("date", c.lng("date")).put("unread", if (unread) 1 else 0).put("count", 1)
                        .put("cat", cat).put("level", r.level).put("reasons", JSONArray(r.reasons.take(3)))
                    seen[th] = o; out.put(o)
                }
            }
        } catch (e: SecurityException) { }
        return out
    }

    fun messages(ctx: Context, thread: Long): JSONArray {
        val out = JSONArray()
        try {
            ctx.contentResolver.query(Telephony.Sms.CONTENT_URI, arrayOf("_id", "address", "body", "date", "type", "status"),
                "thread_id=?", arrayOf(thread.toString()), "date DESC LIMIT 400")?.use { c ->
                while (c.moveToNext()) {
                    val body = c.str("body"); val type = c.getInt(c.getColumnIndexOrThrow("type"))
                    val r = if (type == Telephony.Sms.MESSAGE_TYPE_INBOX) RoninApp.instance.rules.scam.text(body) else null
                    out.put(JSONObject().put("id", c.lng("_id")).put("body", body).put("date", c.lng("date")).put("type", type)
                        .put("level", r?.level ?: "").put("reasons", JSONArray(r?.reasons?.take(3) ?: emptyList<String>())))
                }
            }
        } catch (e: SecurityException) { }
        return out
    }

    fun markRead(ctx: Context, thread: Long) {
        if (!isDefault(ctx)) return
        try { ctx.contentResolver.update(Telephony.Sms.CONTENT_URI, ContentValues().apply { put("read", 1); put("seen", 1) }, "thread_id=? AND read=0", arrayOf(thread.toString())) } catch (e: Exception) { }
    }

    fun deleteThread(ctx: Context, thread: Long): Boolean {
        if (!isDefault(ctx)) return false
        return try { ctx.contentResolver.delete(Uri.parse("content://sms/conversations/$thread"), null, null) >= 0 } catch (e: Exception) { false }
    }

    /** Blocked numbers / senders: Android itself drops their SMS and calls (Ronin is the SMS app). */
    fun block(ctx: Context, address: String): Boolean = try {
        ctx.contentResolver.insert(BlockedNumberContract.BlockedNumbers.CONTENT_URI, ContentValues().apply { put(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER, address) }) != null
    } catch (e: Exception) { false }

    fun unblock(ctx: Context, address: String): Boolean = try { BlockedNumberContract.unblock(ctx, address) > 0 } catch (e: Exception) { false }

    fun blocked(ctx: Context): JSONArray {
        val out = JSONArray()
        try {
            ctx.contentResolver.query(BlockedNumberContract.BlockedNumbers.CONTENT_URI, arrayOf(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER), null, null, null)?.use { c ->
                while (c.moveToNext()) out.put(c.getString(0))
            }
        } catch (e: Exception) { }
        return out
    }

    fun isBlocked(ctx: Context, address: String) = try { BlockedNumberContract.isBlocked(ctx, address) } catch (e: Exception) { false }

    /** Sends an SMS (long ones in parts) and keeps it in the "sent" box. */
    fun send(ctx: Context, address: String, body: String): Boolean {
        if (address.isBlank() || body.isBlank()) return false
        return try {
            val sm: SmsManager = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else @Suppress("DEPRECATION") SmsManager.getDefault()
            val values = ContentValues().apply {
                put("address", address); put("body", body); put("date", System.currentTimeMillis()); put("read", 1); put("seen", 1)
                put("type", Telephony.Sms.MESSAGE_TYPE_OUTBOX)
            }
            val uri = if (isDefault(ctx)) ctx.contentResolver.insert(Telephony.Sms.Outbox.CONTENT_URI, values) else null
            val sent = PendingIntent.getBroadcast(ctx, (System.currentTimeMillis() % 100000).toInt(),
                Intent(ctx, SmsSentReceiver::class.java).setData(uri ?: Uri.EMPTY), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val parts = sm.divideMessage(body)
            if (parts.size > 1) sm.sendMultipartTextMessage(address, null, parts, ArrayList(parts.map { sent }), null)
            else sm.sendTextMessage(address, null, body, sent, null)
            true
        } catch (e: Exception) { false }
    }

    // ---- notifications
    const val CH_SMS = "ronin_sms"
    const val CH_WARN = "ronin_scam"
    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_SMS, "SMS (काम के)", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_WARN, "ठगी की चेतावनी", NotificationManager.IMPORTANCE_DEFAULT))
    }
    fun notify(ctx: Context, id: Int, channel: String, title: String, text: String, open: Intent) {
        channels(ctx)
        val pi = PendingIntent.getActivity(ctx, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, channel).setSmallIcon(android.R.drawable.sym_action_email).setContentTitle(title)
            .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)).setAutoCancel(true).setContentIntent(pi)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE).build()
        try { ctx.getSystemService(NotificationManager::class.java).notify(id, n) } catch (e: SecurityException) { }
    }
}

/** SMS for the phone's SMS app: kept in the phone's SMS store, sorted, and only "काम के" ring. */
class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (parts.isEmpty()) return
        val address = parts[0].displayOriginatingAddress ?: parts[0].originatingAddress ?: ""
        val body = parts.joinToString("") { it.displayMessageBody ?: "" }
        val values = ContentValues().apply {
            put("address", address); put("body", body); put("date", System.currentTimeMillis()); put("date_sent", parts[0].timestampMillis)
            put("read", 0); put("seen", 0); put("type", Telephony.Sms.MESSAGE_TYPE_INBOX)
            val sub = intent.getIntExtra("subscription", -1); if (sub >= 0) put("sub_id", sub)
        }
        try { ctx.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values) } catch (e: Exception) { }
        val (cat, r) = SmsStore.categorize(ctx, address, body)
        val who = SmsStore.contactName(ctx, address) ?: address
        val open = Intent(ctx, MessagesActivity::class.java).putExtra("address", address).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        when (cat) {
            "work" -> SmsStore.notify(ctx, address.hashCode(), SmsStore.CH_SMS, who, body, open)
            "scam" -> SmsStore.notify(ctx, address.hashCode(), SmsStore.CH_WARN, "⛔ ठगी वाला SMS: $who",
                (r.reasons.firstOrNull() ?: "ठगी के संकेत") + "\nRonin ने इसे ⛔ ठगी में रखा है। पैसे, OTP, PIN न दें।", open)
            else -> { } // ads / unknown: quietly in their tab
        }
    }
}

/** MMS (photo messages): Ronin does not download them yet — it says so, so nothing is missed silently. */
class MmsReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        SmsStore.notify(ctx, 7001, SmsStore.CH_SMS, "📷 एक MMS (फ़ोटो वाला संदेश) आया है",
            "Ronin Messages अभी MMS नहीं दिखाता। देखना हो तो Ronin Messages → Settings → \"Google Messages वापस\" दबाएँ।",
            Intent(ctx, MessagesActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** "Sent" or "failed" for a message Ronin sent. */
class SmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val uri = intent.data ?: return
        if (uri == Uri.EMPTY) return
        val ok = resultCode == android.app.Activity.RESULT_OK
        try { ctx.contentResolver.update(uri, ContentValues().apply { put("type", if (ok) Telephony.Sms.MESSAGE_TYPE_SENT else Telephony.Sms.MESSAGE_TYPE_FAILED) }, null, null) } catch (e: Exception) { }
    }
}

/** "Reply with a message" from an incoming call screen. */
class HeadlessSmsSendService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "android.intent.action.RESPOND_VIA_MESSAGE") {
            val to = intent.data?.schemeSpecificPart?.substringBefore('?') ?: ""
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
            if (to.isNotBlank() && text.isNotBlank()) SmsStore.send(this, to, text)
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
