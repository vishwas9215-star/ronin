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

    /** A short warning for a caller: TRAI telemarketing series, a foreign number, not saved, blocked. */
    fun label(ctx: Context, number: String): String {
        val d = digits(number)
        if (d.isEmpty()) return "छुपा हुआ नंबर (private): सावधान"
        if (isEmergency(d)) return ""
        if (SmsStore.contactName(ctx, number) != null) return ""
        if (d.removePrefix("+91").startsWith("140")) return "📢 Telemarketing (140 वाला नंबर)"
        if (d.startsWith("+") && !d.startsWith("+91")) return "🌍 विदेश का नंबर: अनजान हो तो न उठाएँ, पैसे / OTP न दें"
        return "👤 अनजान नंबर: पुलिस / CBI / बैंक / कूरियर बनकर पैसे माँगे तो call काटें"
    }

    fun prefs(ctx: Context) = ctx.getSharedPreferences("ronin_phone", Context.MODE_PRIVATE)

    fun recent(ctx: Context): JSONArray {
        val out = JSONArray()
        if (ctx.checkSelfPermission(Manifest.permission.READ_CALL_LOG) != android.content.pm.PackageManager.PERMISSION_GRANTED) return out
        try {
            ctx.contentResolver.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.DATE, CallLog.Calls.TYPE, CallLog.Calls.DURATION),
                null, null, CallLog.Calls.DATE + " DESC LIMIT 300")?.use { c ->
                while (c.moveToNext()) {
                    val num = c.getString(0) ?: ""
                    val name = c.getString(1) ?: SmsStore.contactName(ctx, num) ?: ""
                    out.put(JSONObject().put("number", num).put("name", name).put("date", c.getLong(2)).put("type", c.getInt(3)).put("dur", c.getLong(4))
                        .put("label", if (name.isEmpty()) label(ctx, num) else ""))
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
                .put("silenceForeign", CallInfo.prefs(this).getBoolean("silenceForeign", false)))
            "makeDefault" -> requestRole(RoleManager.ROLE_DIALER) { ok ->
                askPermissions(arrayOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE, Manifest.permission.POST_NOTIFICATIONS)) { done(JSONObject().put("ok", ok)) }
            }
            "permissions" -> askPermissions(arrayOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE)) { done(true) }
            "recent" -> bg { done(CallInfo.recent(this)) }
            "contacts" -> bg { done(CallInfo.contacts(this)) }
            "call" -> done(CallInfo.call(this, a.optString("number")))
            "sms" -> { startActivity(Intent(this, MessagesActivity::class.java).putExtra("address", a.optString("number"))); done(true) }
            "block" -> bg { done(SmsStore.block(this, a.optString("number"))) }
            "label" -> done(CallInfo.label(this, a.optString("number")))
            "set" -> { CallInfo.prefs(this).edit().putBoolean(a.optString("key"), a.optBoolean("on")).apply(); done(true) }
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
        if (incoming && !CallInfo.isEmergency(num) && SmsStore.contactName(this, num) == null) {
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
        if (call.state == Call.STATE_RINGING) CallHub.ringNotice(this, call)
        startActivity(Intent(this, InCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        CallHub.changed()
    }
    override fun onCallRemoved(call: Call) {
        call.unregisterCallback(CallHub.cb)
        CallHub.calls.remove(call)
        getSystemService(NotificationManager::class.java).cancel(CallHub.NOTE)
        CallHub.changed()
    }
    override fun onCallAudioStateChanged(s: CallAudioState?) = CallHub.changed()
    override fun onDestroy() { if (CallHub.service == this) CallHub.service = null; super.onDestroy() }
}

object CallHub {
    const val NOTE = 7101
    var service: InCallService? = null
    val calls = ArrayList<Call>()
    var screen: (() -> Unit)? = null
    val cb = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            if (state != Call.STATE_RINGING) service?.getSystemService(NotificationManager::class.java)?.cancel(NOTE)
            changed()
        }
    }
    fun changed() { screen?.invoke() }
    fun number(c: Call) = c.details?.handle?.schemeSpecificPart ?: ""

    /** Incoming call: a full-screen ring (over the lock screen) with Answer / Decline. */
    fun ringNotice(ctx: Context, c: Call) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("ronin_ring", "Incoming calls", NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null) })
        val num = number(c)
        val who = SmsStore.contactName(ctx, num) ?: num.ifEmpty { "Private number" }
        val screen = PendingIntent.getActivity(ctx, 1, Intent(ctx, InCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        fun act(a: String, code: Int) = PendingIntent.getBroadcast(ctx, code, Intent(ctx, CallActionReceiver::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, "ronin_ring").setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle("📞 $who").setContentText(CallInfo.label(ctx, num).ifEmpty { "Incoming call" })
            .setCategory(NotificationCompat.CATEGORY_CALL).setPriority(NotificationCompat.PRIORITY_MAX).setOngoing(true)
            .setFullScreenIntent(screen, true).setContentIntent(screen)
            .addAction(0, "❌ काटें", act("decline", 2)).addAction(0, "✅ उठाएँ", act("answer", 3)).build()
        try { nm.notify(NOTE, n) } catch (e: SecurityException) { }
    }
}

class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val c = CallHub.calls.lastOrNull { it.state == Call.STATE_RINGING } ?: return
        when (i.action) {
            "answer" -> { c.answer(VideoProfile.STATE_AUDIO_ONLY); ctx.startActivity(Intent(ctx, InCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            "decline" -> c.reject(false, null)
        }
    }
}

/** The call screen: big buttons, the caller's warning, mute / speaker / keypad, end. */
class InCallActivity : AppCompatActivity() {
    private lateinit var who: TextView; private lateinit var warn: TextView; private lateinit var state: TextView
    private lateinit var ringRow: LinearLayout; private lateinit var talkRow: LinearLayout; private lateinit var keypad: GridLayout
    private lateinit var second: TextView
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
        ringRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL
            addView(btn("❌ काटें", "#dc2626") { top()?.reject(false, null) }); addView(btn("✅ उठाएँ", "#16a34a") { top()?.answer(VideoProfile.STATE_AUDIO_ONLY) }) }
        talkRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val r1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL
            addView(btn("🔇 Mute", "#334155") { CallHub.service?.let { it.setMuted(!(it.callAudioState?.isMuted ?: false)) } })
            addView(btn("🔊 Speaker", "#334155") { CallHub.service?.let { val sp = it.callAudioState?.route == CallAudioState.ROUTE_SPEAKER; it.setAudioRoute(if (sp) CallAudioState.ROUTE_WIRED_OR_EARPIECE else CallAudioState.ROUTE_SPEAKER) } })
            addView(btn("⌨ Keypad", "#334155") { keypad.visibility = if (keypad.visibility == android.view.View.VISIBLE) android.view.View.GONE else android.view.View.VISIBLE }) }
        val r2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; addView(btn("📵 Call काटें", "#dc2626") { top()?.disconnect() }) }
        talkRow.addView(r1); talkRow.addView(r2)
        keypad = GridLayout(this).apply { columnCount = 3; visibility = android.view.View.GONE
            for (k in listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#")) addView(Button(this@InCallActivity).apply { text = k; textSize = 24f
                setOnClickListener { top()?.let { c -> c.playDtmfTone(k[0]); c.stopDtmfTone() } } }) }
        root.addView(who); root.addView(state); root.addView(warn); root.addView(second); root.addView(keypad); root.addView(ringRow); root.addView(talkRow)
        setContentView(root)
        // the screen goes dark at the ear, so the cheek presses nothing
        try { prox = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "ronin:call") } catch (e: Exception) { }
        CallHub.screen = { runOnUiThread { refresh() } }
        refresh()
    }

    private fun top(): Call? = CallHub.calls.firstOrNull { it.state == Call.STATE_RINGING } ?: CallHub.calls.firstOrNull { it.state != Call.STATE_DISCONNECTED }

    private fun refresh() {
        who.removeCallbacks(tick)
        val c = top()
        if (c == null) { prox?.let { if (it.isHeld) it.release() }; finish(); return }
        val num = CallHub.number(c)
        who.text = SmsStore.contactName(this, num) ?: num.ifEmpty { "Private number" }
        val label = CallInfo.label(this, num)
        warn.text = label; warn.visibility = if (label.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        val ringing = c.state == Call.STATE_RINGING
        ringRow.visibility = if (ringing) android.view.View.VISIBLE else android.view.View.GONE
        talkRow.visibility = if (ringing) android.view.View.GONE else android.view.View.VISIBLE
        state.text = when (c.state) {
            Call.STATE_RINGING -> "📞 Call आ रही है"; Call.STATE_DIALING, Call.STATE_CONNECTING -> "📞 लगा रहे हैं..."
            Call.STATE_HOLDING -> "⏸ Hold पर"; Call.STATE_ACTIVE -> "⏱ " + elapsed(c); else -> ""
        }
        val others = CallHub.calls.filter { it != c && it.state != Call.STATE_DISCONNECTED }
        second.text = if (others.isEmpty()) "" else "दूसरी call: " + others.joinToString { SmsStore.contactName(this, CallHub.number(it)) ?: CallHub.number(it) }
        val active = c.state == Call.STATE_ACTIVE
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
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (CallHub.calls.isEmpty()) super.onBackPressed() else moveTaskToBack(true) }
}
