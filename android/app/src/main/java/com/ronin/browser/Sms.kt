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

    // banks and offices never go to ads, even when they write "cashback" or "free"
    private val bankish = Regex("(BK|BNK|BANK|SBI|HDFC|ICICI|AXIS|PNB|KOTAK|CANARA|UIDAI|EPFO|IRCTC|UPPCL|GOV|NIC)", RegexOption.IGNORE_CASE)
    // a real transaction / OTP / bill: never hidden as an ad, whatever else it says
    private val transaction = Regex("(\\botp\\b|one.?time|verification|\\bcode\\b|password|debited|credited|a/c|\\bacct|account no|\\bbal(ance)?\\b|txn|transaction|\\bupi\\b|\\bnach\\b|\\bemi\\b|\\bdue\\b|overdue|\\bpaid\\b|payment|received|\\bsent\\b|statement|policy no|premium|booking|\\bpnr\\b|ticket|order|delivered|refund|blocked|unblock|login|kyc|disburs|sanction|settled|approved|credited|selected|appointment|joining|claim|ओटीपी|खाता|खाते|जमा|निकासी|भुगतान|स्वीकृत|चयन)", RegexOption.IGNORE_CASE)
    // selling / loan offers (the owner does not want to see them at all)
    private val offer = Regex("(loan (approval|offer|eligib|limit|amount|up ?to|ready|sanction)|pre-?approved|instant loan|personal loan|get (up ?to|a loan)|apply now|apply today|lowest (rate|price|emi)|cashback|discount|% ?off|limited (time|period)|hurry|shop now|buy now|free trial|upgrade now|ऑफर)", RegexOption.IGNORE_CASE)
    /** An offer / loan / selling SMS that is not a transaction. */
    fun isOffer(body: String) = offer.containsMatchIn(body) && !transaction.containsMatchIn(body)

    /** A registered promotional sender ("VM-SHOPPR-P"). */
    fun isPromoSender(address: String) = header.find(address.trim())?.groupValues?.get(2)?.uppercase() == "P"

    /** A registered service / transaction / government sender ("-S", "-T", "-G"). */
    fun trustedHeader(address: String) = header.find(address.trim())?.groupValues?.get(2)?.uppercase() in setOf("S", "T", "G")

    fun category(address: String, body: String, inContacts: Boolean, scamLevel: String): String {
        if (scamLevel.isNotEmpty()) return "scam"
        val a = address.trim()
        header.find(a)?.let { m ->
            return when (m.groupValues[2].uppercase()) { "P" -> "ads"; "S", "T", "G" -> if (isOffer(body)) "ads" else "work"; else -> if (isOffer(body) || (promo.containsMatchIn(body) && !bankish.containsMatchIn(a))) "ads" else "work" }
        }
        val digits = a.filter { it.isDigit() }
        if (a.any { it.isLetter() }) return if (promo.containsMatchIn(body) && !bankish.containsMatchIn(a)) "ads" else "work" // old-style "JIOINF"
        if (digits.length in 3..6) return "work" // short codes (bank, operator)
        if (inContacts) return "work"
        return if (promo.containsMatchIn(body)) "ads" else "unknown"
    }
}

/** The OTP / code in a message: shown big and blue, one tap copies only the digits. */
object Otp {
    private val before = Regex("(otp|one.?time|\\bcode|\\bpin\\b(?! ?code)|password|passcode|verification|ओटीपी|कोड|पासवर्ड)[^0-9]{0,40}?(?<!\\d)(\\d{4,8})(?!\\d)", RegexOption.IGNORE_CASE)
    private val after = Regex("(?<!\\d)(\\d{4,8})(?!\\d)[^0-9]{0,30}?(is your|is the|is ur|as your|otp|one.?time|code|ओटीपी|कोड)", RegexOption.IGNORE_CASE)
    fun find(body: String): String? = before.find(body)?.groupValues?.get(2) ?: after.find(body)?.groupValues?.get(1)

    fun copy(ctx: Context, code: String) {
        val cm = ctx.getSystemService(android.content.ClipboardManager::class.java) ?: return
        cm.setPrimaryClip(android.content.ClipData.newPlainText("OTP", code).apply {
            // Android hides a "sensitive" copy from the clipboard preview
            if (Build.VERSION.SDK_INT >= 24) description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        })
        if (Build.VERSION.SDK_INT < 33) android.widget.Toast.makeText(ctx, L("📋 OTP copy हो गया: ", "📋 OTP copied: ") + code, android.widget.Toast.LENGTH_SHORT).show()
    }
}

/** "📋 Copy OTP" on the SMS notification. */
class OtpCopyReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val code = intent.getStringExtra("code")?.takeIf { it.all(Char::isDigit) && it.length in 4..8 } ?: return
        Otp.copy(ctx, code)
        val id = intent.getIntExtra("id", 0)
        if (id != 0) try { ctx.getSystemService(NotificationManager::class.java).cancel(id) } catch (e: Exception) { }
    }
}

object SmsStore {
    // names are looked up from many threads; a name saved later shows up within 10 minutes
    private val nameCache = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()

    fun isDefault(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 29) ctx.getSystemService(android.app.role.RoleManager::class.java)?.isRoleHeld(android.app.role.RoleManager.ROLE_SMS) == true
        else Telephony.Sms.getDefaultSmsPackage(ctx) == ctx.packageName

    fun canReadContacts(ctx: Context) = ctx.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED
    fun contactName(ctx: Context, address: String): String? {
        nameCache[address]?.let { (n, at) -> if (System.currentTimeMillis() - at < 600_000) return n.ifEmpty { null } }
        if (address.any { it.isLetter() } || ctx.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return null
        var name: String? = null
        try {
            ctx.contentResolver.query(Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address)),
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) name = c.getString(0) }
        } catch (e: Exception) { }
        nameCache[address] = (name ?: "") to System.currentTimeMillis()
        return name
    }

    fun categorize(ctx: Context, address: String, body: String): Pair<String, ScamCheck.Result> {
        val r = RoninApp.instance.rules.scam.text(body)
        // a bank / police / government sender's own awareness SMS keeps its place (the warning still shows inside)
        val level = if (SmsSort.trustedHeader(address) && r.ids.none { it in setOf("badlink", "payfirst", "qr", "card") }) "" else r.level
        return SmsSort.category(address, body, contactName(ctx, address) != null, level) to r
    }

    private fun Cursor.str(c: String) = getString(getColumnIndexOrThrow(c)) ?: ""
    private fun Cursor.lng(c: String) = getLong(getColumnIndexOrThrow(c))

    /** The conversations, newest first, each with its tab. */
    fun threads(ctx: Context): JSONArray {
        val out = JSONArray()
        val seen = HashMap<Long, JSONObject>()
        try {
            ctx.contentResolver.query(Telephony.Sms.CONTENT_URI, null, null, null, "date DESC")?.use { c ->
                var rows = 0
                while (c.moveToNext() && rows++ < 3000) {
                    val th = c.lng("thread_id")
                    val unread = c.getInt(c.getColumnIndexOrThrow("read")) == 0 && c.getInt(c.getColumnIndexOrThrow("type")) == Telephony.Sms.MESSAGE_TYPE_INBOX
                    val have = seen[th]
                    if (have != null) { if (unread) have.put("unread", have.optInt("unread") + 1); have.put("count", have.optInt("count") + 1); continue }
                    val addr = c.str("address"); val body = c.str("body")
                    val (cat, r) = categorize(ctx, addr, body)
                    val sub = c.getColumnIndex("sub_id").let { if (it >= 0) c.getInt(it) else -1 }
                    val o = JSONObject().put("thread", th).put("address", addr).put("name", contactName(ctx, addr) ?: "").put("sub", sub)
                        .put("snippet", body.take(140)).put("date", c.lng("date")).put("unread", if (unread) 1 else 0).put("count", 1)
                        .put("cat", cat).put("level", r.level).put("reasons", JSONArray(r.reasons.take(3)))
                    seen[th] = o; out.put(o)
                }
            }
        } catch (e: Exception) { }
        return out
    }

    fun messages(ctx: Context, thread: Long): JSONArray {
        val out = JSONArray()
        try {
            ctx.contentResolver.query(Telephony.Sms.CONTENT_URI, arrayOf("_id", "address", "body", "date", "type", "status"),
                "thread_id=?", arrayOf(thread.toString()), "date DESC")?.use { c ->
                var rows = 0
                while (c.moveToNext() && rows++ < 400) {
                    val body = c.str("body"); val type = c.getInt(c.getColumnIndexOrThrow("type"))
                    val r = if (type == Telephony.Sms.MESSAGE_TYPE_INBOX) RoninApp.instance.rules.scam.text(body) else null
                    out.put(JSONObject().put("id", c.lng("_id")).put("address", c.str("address")).put("body", body).put("date", c.lng("date")).put("type", type)
                        .put("level", r?.level ?: "").put("reasons", JSONArray(r?.reasons?.take(3) ?: emptyList<String>())))
                }
            }
        } catch (e: Exception) { }
        return out
    }

    fun markRead(ctx: Context, thread: Long) {
        if (!isDefault(ctx)) return
        try { ctx.contentResolver.update(Telephony.Sms.CONTENT_URI, ContentValues().apply { put("read", 1); put("seen", 1) }, "thread_id=? AND read=0", arrayOf(thread.toString())) } catch (e: Exception) { }
    }

    /** Ads / offers are hidden, and deleted by themselves after 30 days (the owner's choice). Once a day at most. */
    fun cleanAds(ctx: Context) {
        if (!isDefault(ctx)) return
        val p = ctx.getSharedPreferences("ronin_sms", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - p.getLong("adsCleaned", 0) < 20 * 3600_000L) return
        p.edit().putLong("adsCleaned", now).apply()
        val cut = (now - 30L * 24 * 3600_000L).toString()
        val gone = ArrayList<Long>()
        try {
            // only registered promotional senders ("-P"): a bank / office / person's SMS is never deleted by
            // itself, even when it looks like an offer (it stays hidden instead); locked SMS are never touched
            ctx.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, arrayOf("_id", "address", "body"), "date<? AND locked=0", arrayOf(cut), null)?.use { c ->
                while (c.moveToNext()) {
                    val addr = c.str("address"); val body = c.str("body")
                    if (SmsSort.isPromoSender(addr) && RoninApp.instance.rules.scam.text(body).level.isEmpty()) gone.add(c.lng("_id"))
                }
            }
            for (id in gone) ctx.contentResolver.delete(Uri.parse("content://sms/$id"), null, null)
        } catch (e: Exception) { }
    }

    /** "Mark all read": every unread SMS at once (the owner's many unread numbers). */
    fun markAllRead(ctx: Context): Boolean {
        if (!isDefault(ctx)) return false
        return try { ctx.contentResolver.update(Telephony.Sms.CONTENT_URI, ContentValues().apply { put("read", 1); put("seen", 1) }, "read=0", null); true } catch (e: Exception) { false }
    }

    // ---- ⭐ Saved: the family's own copies of important SMS (an OTP, a booking...). A copy, so deleting a
    // conversation, the 30-day ad clean-up or any other app never removes it; only "remove" in Saved does.
    private fun savedFile(ctx: Context) = android.util.AtomicFile(java.io.File(ctx.filesDir, "saved-sms.json"))
    /** null = the file could not be read: then nothing is written over it. */
    private fun readSaved(ctx: Context): JSONArray? {
        val f = savedFile(ctx)
        if (!f.baseFile.exists()) return JSONArray()
        return try { JSONArray(String(f.readFully(), Charsets.UTF_8)) } catch (e: Exception) { null }
    }
    private fun writeSaved(ctx: Context, a: JSONArray) {
        val f = savedFile(ctx); val out = f.startWrite()
        try { out.write(a.toString().toByteArray(Charsets.UTF_8)); f.finishWrite(out) } catch (e: Exception) { f.failWrite(out) }
    }
    @Synchronized fun saved(ctx: Context): JSONArray = readSaved(ctx) ?: JSONArray()
    /** A saved SMS is known by sender + time + text (the phone's own SMS numbers are reused after a delete). */
    @Synchronized fun save(ctx: Context, m: JSONObject): JSONArray {
        val all = readSaved(ctx) ?: return JSONArray()
        val key = m.optString("key").take(200)
        if (key.isEmpty()) return all
        for (i in 0 until all.length()) if (all.getJSONObject(i).optString("key") == key) return all
        all.put(JSONObject().put("key", key).put("address", m.optString("address").take(40)).put("body", m.optString("body").take(2000))
            .put("date", m.optLong("date")).put("saved", System.currentTimeMillis()))
        writeSaved(ctx, all)
        return all
    }
    @Synchronized fun unsave(ctx: Context, key: String): JSONArray {
        val all = readSaved(ctx) ?: return JSONArray(); val keep = JSONArray()
        for (i in 0 until all.length()) all.getJSONObject(i).let { if (it.optString("key") != key) keep.put(it) }
        writeSaved(ctx, keep)
        return keep
    }

    fun deleteThread(ctx: Context, thread: Long): Boolean {
        if (!isDefault(ctx)) return false
        return try { ctx.contentResolver.delete(Uri.parse("content://sms/conversations/$thread"), null, null) > 0 } catch (e: Exception) { false }
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

    /** Sends an SMS (long ones in parts) on the conversation's SIM and keeps it in the "sent" box. */
    fun send(ctx: Context, address: String, body: String, sub: Int = -1): Boolean {
        if (address.isBlank() || body.isBlank()) return false
        var uri: Uri? = null
        return try {
            var sm: SmsManager = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else @Suppress("DEPRECATION") SmsManager.getDefault()
            if (sub > 0) sm = if (Build.VERSION.SDK_INT >= 31) sm.createForSubscriptionId(sub) else @Suppress("DEPRECATION") SmsManager.getSmsManagerForSubscriptionId(sub)
            val values = ContentValues().apply {
                put("address", address); put("body", body); put("date", System.currentTimeMillis()); put("read", 1); put("seen", 1)
                put("type", Telephony.Sms.MESSAGE_TYPE_OUTBOX); if (sub > 0) put("sub_id", sub)
            }
            uri = if (isDefault(ctx)) ctx.contentResolver.insert(Telephony.Sms.Outbox.CONTENT_URI, values) else null
            val parts = sm.divideMessage(body)
            val base = (System.currentTimeMillis() % 100000).toInt() * 10
            val sent = ArrayList(parts.indices.map { i ->
                PendingIntent.getBroadcast(ctx, base + i, Intent(ctx, SmsSentReceiver::class.java).setData(uri ?: Uri.EMPTY).putExtra("parts", parts.size),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            })
            if (parts.size > 1) sm.sendMultipartTextMessage(address, null, parts, sent, null) else sm.sendTextMessage(address, null, body, sent[0], null)
            true
        } catch (e: Exception) {
            uri?.let { u -> try { ctx.contentResolver.update(u, ContentValues().apply { put("type", Telephony.Sms.MESSAGE_TYPE_FAILED) }, null, null) } catch (x: Exception) { } }
            false
        }
    }

    // ---- notifications
    const val CH_SMS = "ronin_sms"
    const val CH_WARN = "ronin_scam"
    const val CH_QUIET = "ronin_sms_quiet"
    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_SMS, L("SMS (काम के)", "SMS (important)"), NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_WARN, L("ठगी की चेतावनी", "Scam warnings"), NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CH_QUIET, L("अनजान नंबर के SMS (बिना घंटी)", "SMS from unknown numbers (no sound)"), NotificationManager.IMPORTANCE_LOW))
    }
    fun notify(ctx: Context, id: Int, channel: String, title: String, text: String, open: Intent, otp: String? = null) {
        channels(ctx)
        val pi = PendingIntent.getActivity(ctx, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val nb = NotificationCompat.Builder(ctx, channel).setSmallIcon(android.R.drawable.sym_action_email).setContentTitle(title)
            .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)).setAutoCancel(true).setContentIntent(pi)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            // on the lock screen only "a message": OTPs and names stay hidden until unlocked
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(NotificationCompat.Builder(ctx, channel).setSmallIcon(android.R.drawable.sym_action_email).setContentTitle(L("📩 नया संदेश", "📩 New message")).build())
        if (otp != null) {
            val copy = PendingIntent.getBroadcast(ctx, id, Intent(ctx, OtpCopyReceiver::class.java).putExtra("code", otp).putExtra("id", id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            nb.addAction(0, L("📋 OTP copy करें ($otp)", "📋 Copy OTP ($otp)"), copy)
        }
        val n = nb.build()
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
            val sub = intent.getIntExtra("subscription", intent.getIntExtra("android.telephony.extra.SUBSCRIPTION_INDEX", -1)); if (sub >= 0) put("sub_id", sub)
        }
        val pending = goAsync()
        Thread {
            try {
                var saved: Uri? = null
                for (attempt in 0..2) {
                    saved = try { ctx.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values) } catch (e: Exception) { null }
                    if (saved != null) break
                    Thread.sleep(400)
                }
                val who = SmsStore.contactName(ctx, address) ?: address
                val open = Intent(ctx, MessagesActivity::class.java).putExtra("address", address).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (saved == null) {
                    // could not be kept: show the whole message so nothing is lost
                    SmsStore.notify(ctx, address.hashCode(), SmsStore.CH_SMS, L("⚠ SMS सहेजा नहीं जा सका: $who", "⚠ Could not save the SMS: $who"), body, open)
                    return@Thread
                }
                val (cat, r) = try { SmsStore.categorize(ctx, address, body) } catch (e: Exception) { "work" to null }
                when (cat) {
                    "work" -> SmsStore.notify(ctx, address.hashCode(), SmsStore.CH_SMS, who, body, open, Otp.find(body))
                    "unknown" -> SmsStore.notify(ctx, address.hashCode(), SmsStore.CH_QUIET, "👤 $who", body, open)
                    "scam" -> SmsStore.notify(ctx, address.hashCode(), SmsStore.CH_WARN, L("⛔ ठगी वाला SMS: $who", "⛔ Scam SMS: $who"),
                        (r?.reasons?.firstOrNull() ?: L("ठगी के संकेत", "Signs of a scam")) + L("\nRonin ने इसे ⛔ ठगी में रखा है। पैसे, OTP, PIN न दें।", "\nRonin put it in ⛔ Scam. Never give money, OTP or PIN."), open)
                    else -> { } // ads: hidden, no sound
                }
                SmsStore.cleanAds(ctx)
            } finally { pending.finish() }
        }.start()
    }
}

/** MMS (photo messages): Ronin does not download them yet — it says so, so nothing is missed silently. */
class MmsReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val data = intent.getByteArrayExtra("data")
        if (data != null) try { MmsNotice.save(ctx, data) } catch (e: Exception) { }
        SmsStore.notify(ctx, 7001, SmsStore.CH_SMS, L("📷 एक MMS (फ़ोटो वाला संदेश) आया है", "📷 An MMS (picture message) came"),
            L("Ronin Messages अभी MMS नहीं दिखाता, पर उसे सँभालकर रखा है। देखने के लिए Ronin Messages → ⚙ → \"Google Messages वापस\" करें, वहाँ download हो जाएगा।", "Ronin Messages cannot show MMS yet, but kept it safe. To see it: Ronin Messages → ⚙ → \"Back to Google Messages\"; it downloads there."),
            Intent(ctx, MessagesActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Reads the few fields of an MMS notice (WAP push) and keeps it as a "to download" MMS. */
object MmsNotice {
    private fun text(d: ByteArray, at: Int): String { var e = at; while (e < d.size && d[e] != 0.toByte()) e++; return String(d, at, e - at, Charsets.ISO_8859_1) }
    fun save(ctx: Context, d: ByteArray) {
        var loc = ""; var tr = ""; var from = ""; var exp = 0L
        var i = 0
        while (i < d.size - 1) {
            when (d[i].toInt() and 0xff) {
                0x83 -> if (loc.isEmpty() && i + 5 < d.size && String(d, i + 1, 4, Charsets.ISO_8859_1) == "http") loc = text(d, i + 1)
                0x98 -> if (tr.isEmpty() && i + 1 < d.size && (d[i + 1].toInt() and 0xff) in 32..126) tr = text(d, i + 1)
                0x89 -> if (from.isEmpty() && i + 3 < d.size && (d[i + 2].toInt() and 0xff) == 0x80) from = text(d, i + 3).substringBefore("/TYPE")
                0x88 -> if (exp == 0L && i + 4 < d.size) exp = 7L * 24 * 3600
            }
            i++
        }
        if (loc.isEmpty()) return
        val now = System.currentTimeMillis() / 1000
        val thread = try { Telephony.Threads.getOrCreateThreadId(ctx, from.ifEmpty { "MMS" }) } catch (e: Exception) { 0L }
        val v = ContentValues().apply {
            put("thread_id", thread); put("date", now); put("read", 0); put("seen", 0); put("msg_box", 1)
            put("m_type", 130); put("ct_l", loc); if (tr.isNotEmpty()) put("tr_id", tr); put("exp", now + (if (exp > 0) exp else 7L * 24 * 3600)); put("v", 18)
        }
        val u = ctx.contentResolver.insert(Telephony.Mms.Inbox.CONTENT_URI, v) ?: return
        if (from.isNotEmpty()) ctx.contentResolver.insert(Uri.parse("content://mms/${u.lastPathSegment}/addr"),
            ContentValues().apply { put("address", from); put("type", 137); put("charset", 106) })
    }
}

/** "Sent" or "failed" for a message Ronin sent. */
class SmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val uri = intent.data ?: return
        if (uri == Uri.EMPTY) return
        val ok = resultCode == android.app.Activity.RESULT_OK
        // a long message: any failed part marks it failed; "sent" never overwrites "failed"
        try {
            if (ok) ctx.contentResolver.update(uri, ContentValues().apply { put("type", Telephony.Sms.MESSAGE_TYPE_SENT) }, "type!=?", arrayOf(Telephony.Sms.MESSAGE_TYPE_FAILED.toString()))
            else ctx.contentResolver.update(uri, ContentValues().apply { put("type", Telephony.Sms.MESSAGE_TYPE_FAILED) }, null, null)
        } catch (e: Exception) { }
    }
}

/** "Reply with a message" from an incoming call screen. */
class HeadlessSmsSendService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "android.intent.action.RESPOND_VIA_MESSAGE") {
            val to = intent.data?.schemeSpecificPart?.substringBefore('?') ?: ""
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
            if (text.isNotBlank()) to.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }.forEach { SmsStore.send(this, it, text) }
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
