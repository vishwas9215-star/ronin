package com.ronin.browser

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.CallLog
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.CallScreeningService
import android.telecom.InCallService
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * Ronin Calls: Ronin as the phone app (the family chose it). The call screen is plain Android views
 * (no web page), so a call never depends on anything that can break. Emergency numbers always go
 * straight through Android's own Telecom; Ronin never stops or silences them.
 */
object CallInfo {
    val EMERGENCY = setOf("112", "100", "101", "102", "108", "1091", "1098", "1930", "181", "14567")
    fun digits(n: String) = n.filter { it.isDigit() || it == '+' }
    fun isEmergency(n: String) = digits(n).removePrefix("+91") in EMERGENCY
    fun isEmergency(ctx: Context, n: String): Boolean {
        if (isEmergency(n)) return true
        return try { Build.VERSION.SDK_INT >= 29 && ctx.getSystemService(android.telephony.TelephonyManager::class.java).isEmergencyNumber(digits(n)) } catch (e: Exception) { false }
    }
    /** After an emergency call nobody is silenced for an hour: the control room calls back from ordinary numbers. */
    fun recentEmergency(ctx: Context) = System.currentTimeMillis() - prefs(ctx).getLong("lastEmergency", 0) < 60 * 60 * 1000L
    fun markEmergency(ctx: Context, n: String) { if (isEmergency(ctx, n)) prefs(ctx).edit().putLong("lastEmergency", System.currentTimeMillis()).apply() }

    /** A short warning for a caller: TRAI telemarketing series, a foreign number, not saved, blocked. */
    fun label(ctx: Context, number: String): String {
        val d = digits(number)
        if (d.isEmpty()) return L("छुपा हुआ नंबर (private): सावधान", "Hidden number (private): be careful")
        if (isEmergency(d)) return ""
        if (SmsStore.contactName(ctx, number) != null) return ""
        if (d.removePrefix("+91").startsWith("140")) return L("📢 Telemarketing (140 वाला नंबर)", "📢 Telemarketing (a 140 number)")
        if (d.startsWith("+") && !d.startsWith("+91")) return L("🌍 विदेश का नंबर: अनजान हो तो न उठाएँ, पैसे / OTP न दें", "🌍 Foreign number: if you do not know them, do not answer; never give money / OTP")
        return L("👤 अनजान नंबर: पुलिस / CBI / बैंक / कूरियर बनकर पैसे माँगे तो call काटें", "👤 Unknown number: if they claim to be police / CBI / bank / courier and ask money, cut the call")
    }

    fun prefs(ctx: Context) = ctx.getSharedPreferences("ronin_phone", Context.MODE_PRIVATE)

    /** CNAP (DoT, from 1 Nov 2026 on 4G/5G): the caller's KYC name from the SIM, sent by the network. */
    fun cnap(c: Call?): String {
        val d = c?.details ?: return ""
        // only a real SIM call carries the network's name (an internet-call app could put any name here)
        val sim = try { RoninApp.instance.getSystemService(TelecomManager::class.java).getPhoneAccount(d.accountHandle)?.hasCapabilities(android.telecom.PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION) == true } catch (e: Exception) { false }
        if (!sim) return ""
        val n = d.callerDisplayName?.toString()?.trim() ?: ""
        return if (n.isNotEmpty() && d.callerDisplayNamePresentation == TelecomManager.PRESENTATION_ALLOWED) n.take(60) else ""
    }
    fun keepCnap(ctx: Context, number: String, name: String) {
        val k = digits(number).takeLast(10)
        if (k.length >= 6 && name.isNotEmpty()) prefs(ctx).edit().putString("cnap_$k", name).apply()
    }
    fun cnapOf(ctx: Context, number: String): String = prefs(ctx).getString("cnap_" + digits(number).takeLast(10), "") ?: ""

    /** Under an unknown caller's warning: the "police / digital arrest" threat calls. */
    fun threatTip() = L("पुलिस / CBI / बैंक कभी फ़ोन या WhatsApp पर धमकाकर पैसे या OTP नहीं माँगते। \"Digital arrest\" नकली है: डराएँ तो call काटें।",
        "Police / CBI / banks never threaten you on a phone or WhatsApp call for money or an OTP. \"Digital arrest\" is fake: if they frighten you, cut the call.")

    fun recent(ctx: Context): JSONArray {
        val out = JSONArray()
        if (ctx.checkSelfPermission(Manifest.permission.READ_CALL_LOG) != android.content.pm.PackageManager.PERMISSION_GRANTED) return out
        try {
            ctx.contentResolver.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.DATE, CallLog.Calls.TYPE, CallLog.Calls.DURATION),
                null, null, CallLog.Calls.DATE + " DESC LIMIT 300")?.use { c ->
                while (c.moveToNext()) {
                    val num = c.getString(0) ?: ""
                    val name = c.getString(1) ?: SmsStore.contactName(ctx, num) ?: ""
                    val sim = if (name.isEmpty()) cnapOf(ctx, num) else ""
                    out.put(JSONObject().put("number", num).put("name", name).put("date", c.getLong(2)).put("type", c.getInt(3)).put("dur", c.getLong(4))
                        .put("label", if (name.isEmpty()) (if (sim.isNotEmpty()) L("📛 SIM पर नाम: ", "📛 Name on the SIM: ") + sim + " · " else "") + label(ctx, num) else ""))
                }
            }
        } catch (e: Exception) { }
        return out
    }

    fun contacts(ctx: Context): JSONArray {
        val out = JSONArray()
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return out
        try {
            ctx.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC")?.use { c ->
                val seen = HashSet<String>()
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: continue; val num = c.getString(1) ?: continue
                    if (seen.add(name + digits(num).takeLast(10))) out.put(JSONObject().put("name", name).put("number", num))
                }
            }
        } catch (e: Exception) { }
        return out
    }

    /** Places a call through Android (works for 112 at any time). */
    fun call(ctx: Context, number: String): Boolean {
        val n = number.trim(); if (n.isEmpty()) return false
        markEmergency(ctx, n)
        return try {
            val tm = ctx.getSystemService(TelecomManager::class.java)
            if (ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) == android.content.pm.PackageManager.PERMISSION_GRANTED) { tm.placeCall(Uri.fromParts("tel", n, null), Bundle()); true }
            else { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", n, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }
        } catch (e: Exception) { false }
    }
}

/** Ronin Calls page: keypad, recent calls with warnings, contacts (ui/tools/calls.html). */
class CallsActivity : PhonePage() {
    override val page = "calls.html"
    override fun startQuery(i: Intent?): String = i?.data?.takeIf { it.scheme == "tel" }?.schemeSpecificPart?.let { "#num=" + Uri.encode(it) } ?: ""
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val q = startQuery(intent)
        if (q.isNotEmpty()) web.evaluateJavascript("window.__roninDial&&window.__roninDial(" + JSONObject.quote(Uri.decode(q.removePrefix("#num="))) + ")", null)
    }
    override fun handle(op: String, a: JSONObject, done: (Any?) -> Unit) {
        if (common(op, a, done)) return
        when (op) {
            "state" -> done(JSONObject().put("isDefault", roleHeld(if (Build.VERSION.SDK_INT >= 29) RoleManager.ROLE_DIALER else "")) .put("log", has(Manifest.permission.READ_CALL_LOG))
                .put("contacts", has(Manifest.permission.READ_CONTACTS)).put("silenceUnknown", CallInfo.prefs(this).getBoolean("silenceUnknown", false))
                .put("silenceForeign", CallInfo.prefs(this).getBoolean("silenceForeign", false)).put("inCall", CallHub.calls.isNotEmpty()))
            "makeDefault" -> requestRole(RoleManager.ROLE_DIALER) { ok ->
                askPermissions(arrayOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE, Manifest.permission.POST_NOTIFICATIONS)) { done(JSONObject().put("ok", ok)) }
            }
            "permissions" -> askPermissions(arrayOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE)) { done(true) }
            "recent" -> bg { done(CallInfo.recent(this)) }
            "contacts" -> bg { done(CallInfo.contacts(this)) }
            "call" -> done(CallInfo.call(this, a.optString("number")))
            "inCall" -> { if (CallHub.calls.isNotEmpty()) startActivity(Intent(this, InCallActivity::class.java)); done(CallHub.calls.isNotEmpty()) }
            "sms" -> { startActivity(Intent(this, MessagesActivity::class.java).putExtra("address", a.optString("number"))); done(true) }
            "block" -> bg { done(SmsStore.block(this, a.optString("number"))) }
            "label" -> done(CallInfo.label(this, a.optString("number")))
            "set" -> {
                val k = a.optString("key")
                if (k in setOf("silenceUnknown", "silenceForeign", "recAuto")) CallInfo.prefs(this).edit().putBoolean(k, a.optBoolean("on")).apply()
                if (k == "recAuto" && a.optBoolean("on")) askPermissions(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS)) { done(true) } else done(true)
            }
            "rec" -> done(JSONObject().put("auto", CallRecorder.auto(this)).put("access", CallRecorder.accessOn(this)).put("skip", JSONArray(CallRecorder.skipList(this)))
                .put("mic", has(Manifest.permission.RECORD_AUDIO)))
            "skip" -> { CallRecorder.setSkip(this, a.optString("number"), a.optBoolean("on")); done(JSONArray(CallRecorder.skipList(this))) }
            "recordings" -> bg { done(CallRecorder.list(this)) }
            "play" -> { CallRecorder.play(this, a.optLong("rid")); done(true) }
            "accessibility" -> { startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)); done(true) }
            else -> done(null)
        }
    }
}

/** Incoming calls first pass: unknown / foreign callers can be silenced (never emergency numbers). */
class RoninCallScreen : CallScreeningService() {
    override fun onScreenCall(d: Call.Details) {
        val num = d.handle?.schemeSpecificPart ?: ""
        val p = CallInfo.prefs(this)
        val incoming = Build.VERSION.SDK_INT < 29 || d.callDirection == Call.Details.DIRECTION_INCOMING
        var silence = false
        // never silence without the contacts (everyone would look unknown), nor soon after an emergency call
        if (incoming && SmsStore.canReadContacts(this) && !CallInfo.recentEmergency(this) && !CallInfo.isEmergency(this, num) && SmsStore.contactName(this, num) == null) {
            val dg = CallInfo.digits(num)
            if (p.getBoolean("silenceUnknown", false)) silence = true
            if (p.getBoolean("silenceForeign", false) && dg.startsWith("+") && !dg.startsWith("+91")) silence = true
        }
        val b = CallResponse.Builder()
        if (Build.VERSION.SDK_INT >= 29) b.setSilenceCall(silence)
        respondToCall(d, b.build())
    }
}

/** The calls themselves: Android tells Ronin; Ronin shows its call screen. */
class RoninInCallService : InCallService() {
    override fun onCallAdded(call: Call) {
        CallHub.service = this
        CallHub.calls.add(call)
        call.registerCallback(CallHub.cb)
        CallInfo.markEmergency(this, CallHub.number(call))
        CallInfo.keepCnap(this, CallHub.number(call), CallInfo.cnap(call))
        CallHub.incoming[call] = CallHub.ringing(call)
        if (CallHub.ringing(call)) CallHub.ringNotice(this, call)
        startActivity(Intent(this, InCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        CallHub.changed()
    }
    override fun onCallRemoved(call: Call) {
        call.unregisterCallback(CallHub.cb)
        CallHub.calls.remove(call)
        if (CallHub.incoming.remove(call) == true) CallHub.afterUnknown(this, CallHub.number(call))
        if (CallHub.calls.none { it.state == Call.STATE_ACTIVE || it.state == Call.STATE_HOLDING }) RecordService.stop(this)
        getSystemService(NotificationManager::class.java).cancel(CallHub.NOTE)
        if (CallHub.calls.isEmpty()) getSystemService(NotificationManager::class.java).cancel(CallHub.NOTE + 1)
        CallHub.changed()
    }
    override fun onCallAudioStateChanged(s: CallAudioState?) = CallHub.changed()
    override fun onDestroy() { if (CallHub.service == this) CallHub.service = null; super.onDestroy() }
}

object CallHub {
    const val NOTE = 7101
    var service: InCallService? = null
    val calls = ArrayList<Call>()
    val incoming = HashMap<Call, Boolean>()

    /** After a call from a number that is not saved: "Spam? Block / Report" in one tap. */
    fun afterUnknown(ctx: Context, num: String) {
        if (num.isEmpty() || CallInfo.isEmergency(ctx, num) || SmsStore.contactName(ctx, num) != null) return
        if (!SmsStore.canReadContacts(ctx)) return // without contacts everyone looks unknown
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("ronin_spam", L("स्पैम call के बाद", "After a spam call"), NotificationManager.IMPORTANCE_LOW))
        val id = 7200 + (num.hashCode() and 0xff)
        fun act(a: String, code: Int) = PendingIntent.getBroadcast(ctx, id * 4 + code, Intent(ctx, CallActionReceiver::class.java).setAction(a).putExtra("number", num).putExtra("id", id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val sim = CallInfo.cnapOf(ctx, num)
        val n = NotificationCompat.Builder(ctx, "ronin_spam").setSmallIcon(android.R.drawable.sym_call_missed)
            .setContentTitle(L("अनजान call: ", "Unknown call: ") + num + (if (sim.isNotEmpty()) " ($sim)" else ""))
            .setContentText(L("स्पैम / धमकी वाली थी? एक tap में block या शिकायत करें।", "Spam or a threat? Block or report in one tap."))
            .setAutoCancel(true)
            .addAction(0, L("🚫 Block", "🚫 Block"), act("block", 1))
            .addAction(0, L("📵 चक्षु पर शिकायत", "📵 Report (Chakshu)"), act("report", 2)).build()
        try { nm.notify(id, n) } catch (e: SecurityException) { }
    }
    var screen: (() -> Unit)? = null
    val cb = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            if (!ringing(call)) service?.getSystemService(NotificationManager::class.java)?.cancel(NOTE)
            changed()
        }
    }
    fun changed() { screen?.invoke(); ongoing() }

    /** While a call is on, a notification leads back to it (and can end it), whatever screen is open. */
    private fun ongoing() {
        val ctx = service ?: return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val live = calls.firstOrNull { it.state != Call.STATE_RINGING && it.state != Call.STATE_DISCONNECTED }
        if (live == null) { nm.cancel(NOTE + 1); return }
        nm.createNotificationChannel(NotificationChannel("ronin_call", L("Call चल रही है", "Ongoing call"), NotificationManager.IMPORTANCE_LOW))
        val num = number(live)
        val screen = PendingIntent.getActivity(ctx, 4, Intent(ctx, InCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        val end = PendingIntent.getBroadcast(ctx, 5, Intent(ctx, CallActionReceiver::class.java).setAction("hangup"), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, "ronin_call").setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle("📞 " + (SmsStore.contactName(ctx, num) ?: num)).setContentText(L("Call चल रही है: वापस जाने के लिए दबाएँ", "Call in progress: tap to go back"))
            .setOngoing(true).setContentIntent(screen).setCategory(NotificationCompat.CATEGORY_CALL).addAction(0, L("📵 Call काटें", "📵 Hang up"), end).build()
        try { nm.notify(NOTE + 1, n) } catch (e: SecurityException) { }
    }
    fun number(c: Call) = c.details?.handle?.schemeSpecificPart ?: ""
    fun ringing(c: Call) = c.state == Call.STATE_RINGING || (Build.VERSION.SDK_INT >= 30 && c.state == Call.STATE_SIMULATED_RINGING)

    /** Incoming call: a full-screen ring (over the lock screen) with Answer / Decline. */
    fun ringNotice(ctx: Context, c: Call) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("ronin_ring", "Incoming calls", NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null) })
        val num = number(c)
        val who = SmsStore.contactName(ctx, num) ?: num.ifEmpty { "Private number" }
        val screen = PendingIntent.getActivity(ctx, 1, Intent(ctx, InCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        fun act(a: String, code: Int) = PendingIntent.getBroadcast(ctx, code, Intent(ctx, CallActionReceiver::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE)
        // "Answer" opens the call screen itself (Android 12+ does not let a receiver open a screen)
        val answer = PendingIntent.getActivity(ctx, 3, Intent(ctx, InCallActivity::class.java).putExtra("answer", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, "ronin_ring").setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle("📞 $who").setContentText((CallInfo.cnap(c).let { if (it.isNotEmpty() && SmsStore.contactName(ctx, num) == null) L("📛 SIM पर नाम: ", "📛 Name on the SIM: ") + it + " · " else "" }) + CallInfo.label(ctx, num).ifEmpty { "Incoming call" })
            .setCategory(NotificationCompat.CATEGORY_CALL).setPriority(NotificationCompat.PRIORITY_MAX).setOngoing(true)
            .setFullScreenIntent(screen, true).setContentIntent(screen)
            .addAction(0, L("❌ काटें", "❌ Decline"), act("decline", 2)).addAction(0, L("✅ उठाएँ", "✅ Answer"), answer).build()
        try { nm.notify(NOTE, n) } catch (e: SecurityException) { }
    }
}

class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val num = i.getStringExtra("number") ?: ""
        val id = i.getIntExtra("id", 0)
        when (i.action) {
            "block" -> if (num.isNotEmpty()) {
                val ok = try { ctx.contentResolver.insert(android.provider.BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                    android.content.ContentValues().apply { put(android.provider.BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER, num) }) != null } catch (e: Exception) { false }
                android.widget.Toast.makeText(ctx, if (ok) L("🚫 Block कर दिया: ", "🚫 Blocked: ") + num else L("Block नहीं हो पाया", "Could not block"), android.widget.Toast.LENGTH_LONG).show()
                if (id != 0) ctx.getSystemService(NotificationManager::class.java).cancel(id)
            }
            "report" -> {
                try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://sancharsaathi.gov.in/sfc/"), ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
                if (id != 0) ctx.getSystemService(NotificationManager::class.java).cancel(id)
            }
            "decline" -> CallHub.calls.lastOrNull { CallHub.ringing(it) }?.reject(false, null)
            "hangup" -> (CallHub.calls.firstOrNull { it.state == Call.STATE_ACTIVE } ?: CallHub.calls.firstOrNull { it.state != Call.STATE_DISCONNECTED })?.disconnect()
        }
    }
}

/** The call screen: big buttons, the caller's warning, mute / speaker / keypad, end. */
class InCallActivity : AppCompatActivity() {
    private lateinit var who: TextView; private lateinit var warn: TextView; private lateinit var state: TextView
    private lateinit var ringRow: LinearLayout; private lateinit var talkRow: LinearLayout; private lateinit var keypad: GridLayout
    private lateinit var second: TextView
    private lateinit var simName: TextView
    private lateinit var simRow: LinearLayout
    private var prox: PowerManager.WakeLock? = null
    private val tick = Runnable { refresh() }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val d = resources.displayMetrics.density
        fun btn(t: String, bg: String, f: () -> Unit) = Button(this).apply {
            text = t; textSize = 20f; setTextColor(Color.WHITE); isAllCaps = false
            background = GradientDrawable().apply { setColor(Color.parseColor(bg)); cornerRadius = 40 * d }
            minHeight = (72 * d).toInt(); setOnClickListener { f() }
            layoutParams = LinearLayout.LayoutParams(0, (76 * d).toInt(), 1f).apply { setMargins((8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt()) }
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#0f1218")); setPadding((16 * d).toInt(), (40 * d).toInt(), (16 * d).toInt(), (24 * d).toInt()); gravity = Gravity.CENTER_HORIZONTAL }
        who = TextView(this).apply { textSize = 30f; setTextColor(Color.WHITE); gravity = Gravity.CENTER }
        state = TextView(this).apply { textSize = 18f; setTextColor(Color.parseColor("#a3acbd")); gravity = Gravity.CENTER; setPadding(0, (6 * d).toInt(), 0, (10 * d).toInt()) }
        warn = TextView(this).apply { textSize = 18f; setTextColor(Color.parseColor("#fde68a")); gravity = Gravity.CENTER; setPadding((10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt())
            background = GradientDrawable().apply { setColor(Color.parseColor("#3a2a0a")); cornerRadius = 14 * d } }
        second = TextView(this).apply { textSize = 17f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(0, (10 * d).toInt(), 0, 0) }
        // CNAP: the name on the caller's SIM (government KYC) - a fake "Daroga ji" shows someone else's name
        simName = TextView(this).apply { textSize = 22f; setTextColor(Color.parseColor("#bfdbfe")); gravity = Gravity.CENTER; setPadding((10 * d).toInt(), (8 * d).toInt(), (10 * d).toInt(), (8 * d).toInt())
            background = GradientDrawable().apply { setColor(Color.parseColor("#1e3a8a")); cornerRadius = 14 * d }; visibility = android.view.View.GONE }
        ringRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL
            addView(btn(L("❌ काटें", "❌ Decline"), "#dc2626") { top()?.reject(false, null) }); addView(btn(L("✅ उठाएँ", "✅ Answer"), "#16a34a") { top()?.answer(VideoProfile.STATE_AUDIO_ONLY) }) }
        talkRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val r1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL
            addView(btn("🔇 Mute", "#334155") { CallHub.service?.let { it.setMuted(!(it.callAudioState?.isMuted ?: false)) } })
            addView(btn("🔊 Speaker", "#334155") { CallHub.service?.let { val sp = it.callAudioState?.route == CallAudioState.ROUTE_SPEAKER; it.setAudioRoute(if (sp) CallAudioState.ROUTE_WIRED_OR_EARPIECE else CallAudioState.ROUTE_SPEAKER) } })
            addView(btn("⌨ Keypad", "#334155") { keypad.visibility = if (keypad.visibility == android.view.View.VISIBLE) android.view.View.GONE else android.view.View.VISIBLE }) }
        val r2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; addView(btn(L("📵 Call काटें", "📵 Hang up"), "#dc2626") { top()?.disconnect() }) }
        talkRow.addView(r1); talkRow.addView(r2)
        keypad = GridLayout(this).apply { columnCount = 3; visibility = android.view.View.GONE
            for (k in listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#")) addView(Button(this@InCallActivity).apply { text = k; textSize = 24f
                setOnClickListener { top()?.let { c -> c.playDtmfTone(k[0]); c.stopDtmfTone() } } }) }
        simRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(who); root.addView(state); root.addView(simName); root.addView(warn); root.addView(second); root.addView(simRow); root.addView(keypad); root.addView(ringRow); root.addView(talkRow)
        setContentView(root)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, ins ->
            val b = ins.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding((16 * d).toInt() + b.left, (40 * d).toInt() + b.top, (16 * d).toInt() + b.right, (24 * d).toInt() + b.bottom); androidx.core.view.WindowInsetsCompat.CONSUMED
        }
        // Back leaves the call running and the screen reachable (Recents / the notification)
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (CallHub.calls.isEmpty()) finish() else moveTaskToBack(true) }
        })
        answerIf(intent)
        // the screen goes dark at the ear, so the cheek presses nothing
        try { prox = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "ronin:call") } catch (e: Exception) { }
        CallHub.screen = { runOnUiThread { refresh() } }
        refresh()
    }

    private fun answerIf(i: Intent?) {
        if (i?.getBooleanExtra("answer", false) == true) CallHub.calls.lastOrNull { CallHub.ringing(it) }?.answer(VideoProfile.STATE_AUDIO_ONLY)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); answerIf(intent); refresh() }

    /** The call this screen is about: a ringing one first, then the one talking, then one being placed, then one on hold. */
    private fun top(): Call? = CallHub.calls.firstOrNull { CallHub.ringing(it) } ?: CallHub.calls.firstOrNull { it.state == Call.STATE_ACTIVE }
        ?: CallHub.calls.firstOrNull { it.state == Call.STATE_DIALING || it.state == Call.STATE_CONNECTING || it.state == Call.STATE_SELECT_PHONE_ACCOUNT || it.state == Call.STATE_NEW }
        ?: CallHub.calls.firstOrNull { it.state != Call.STATE_DISCONNECTED }

    /** Dual SIM with "ask every time": Android waits for the SIM choice. */
    private fun sims(c: Call) {
        simRow.removeAllViews()
        if (c.state != Call.STATE_SELECT_PHONE_ACCOUNT) return
        val d = resources.displayMetrics.density
        @Suppress("DEPRECATION")
        val list: List<android.telecom.PhoneAccountHandle> = c.details?.extras?.getParcelableArrayList<android.telecom.PhoneAccountHandle>(Call.AVAILABLE_PHONE_ACCOUNTS)
            ?: try { getSystemService(TelecomManager::class.java).callCapablePhoneAccounts } catch (e: SecurityException) { emptyList() }
        val tm = getSystemService(TelecomManager::class.java)
        list.forEachIndexed { i, h ->
            val label = try { tm.getPhoneAccount(h)?.label?.toString() } catch (e: Exception) { null } ?: "SIM ${i + 1}"
            simRow.addView(Button(this).apply { text = L("📶 $label से call करें", "📶 Call with $label"); textSize = 20f; isAllCaps = false; minHeight = (64 * d).toInt()
                setOnClickListener { c.phoneAccountSelected(h, false) } })
        }
        simRow.addView(Button(this).apply { text = L("✖ रद्द करें", "✖ Cancel"); textSize = 18f; isAllCaps = false; setOnClickListener { c.disconnect() } })
    }

    private fun refresh() {
        who.removeCallbacks(tick)
        val c = top()
        if (c == null) { prox?.let { if (it.isHeld) it.release() }; finish(); return }
        val num = CallHub.number(c)
        who.text = SmsStore.contactName(this, num) ?: num.ifEmpty { "Private number" }
        val saved = SmsStore.contactName(this, num) != null
        val sim = if (saved) "" else CallInfo.cnap(c).ifEmpty { CallInfo.cnapOf(this, num) }
        simName.text = L("📛 नेटवर्क से नाम (SIM का KYC): ", "📛 Name from the network (SIM KYC): ") + sim
        simName.visibility = if (sim.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        val base = CallInfo.label(this, num)
        // unknown / foreign / private callers also get the "police threat" line
        val label = if (base.isNotEmpty() && !base.startsWith("📢")) base + "\n\n" + CallInfo.threatTip() else base
        warn.text = label; warn.visibility = if (label.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        val ringing = CallHub.ringing(c)
        val choosing = c.state == Call.STATE_SELECT_PHONE_ACCOUNT
        sims(c)
        ringRow.visibility = if (ringing) android.view.View.VISIBLE else android.view.View.GONE
        talkRow.visibility = if (ringing || choosing) android.view.View.GONE else android.view.View.VISIBLE
        state.text = when (c.state) {
            Call.STATE_RINGING -> L("📞 Call आ रही है", "📞 Incoming call"); Call.STATE_DIALING, Call.STATE_CONNECTING -> L("📞 लगा रहे हैं...", "📞 Calling..."); Call.STATE_SELECT_PHONE_ACCOUNT -> L("किस SIM से call करें?", "Which SIM to call with?")
            Call.STATE_HOLDING -> L("⏸ Hold पर", "⏸ On hold"); Call.STATE_ACTIVE -> "⏱ " + elapsed(c); else -> ""
        }
        val others = CallHub.calls.filter { it != c && it.state != Call.STATE_DISCONNECTED }
        second.text = if (others.isEmpty()) "" else L("दूसरी call: ", "Other call: ") + others.joinToString { SmsStore.contactName(this, CallHub.number(it)) ?: CallHub.number(it) }
        val active = c.state == Call.STATE_ACTIVE
        // automatic recording: every call except the "do not record" people (Settings)
        if (active && CallRecorder.recordingNumber == null && CallRecorder.wanted(this, num)) RecordService.start(this, num)
        state.text = state.text.toString() + if (CallRecorder.recordingNumber != null) "   🎙 Rec" else ""
        prox?.let { if (active && !it.isHeld) it.acquire(3 * 60 * 60 * 1000L) else if (!active && it.isHeld) it.release() }
        if (active) who.postDelayed(tick, 1000)
    }
    private fun elapsed(c: Call): String {
        val t = c.details?.connectTimeMillis ?: 0L
        if (t <= 0) return ""
        val s = (System.currentTimeMillis() - t) / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }
    override fun onDestroy() { if (CallHub.screen != null) CallHub.screen = null; prox?.let { if (it.isHeld) it.release() }; super.onDestroy() }
}
