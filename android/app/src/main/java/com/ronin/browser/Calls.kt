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

    /** A short line for a caller not in contacts (3.14.3: smart, see CallerId; red only for a real risk). */
    fun label(ctx: Context, number: String, cnap: String = cnapOf(ctx, number)): String {
        val w = CallerId.judge(number, SmsStore.contactName(ctx, number) != null, cnap, CallerId.known(ctx)) ?: return ""
        if (webFraud(ctx, number)) return fraudLine()
        if (cnap.isEmpty()) return w.text
        // the judge's own "name from the SIM" line already says it: say the name once
        return L("📛 SIM पर नाम: ", "📛 Name on the SIM: ") + cnap + if (w.text.startsWith("📛")) L(" · contact में नहीं", " · not in contacts") else " · " + w.text
    }

    /** The name "🔍 कौन?" found on the web for a number (kept on the phone; "-" = searched, nothing found). */
    fun tag(ctx: Context, number: String): String = (prefs(ctx).getString("web_" + digits(number).takeLast(10), "") ?: "").takeIf { it != "-" } ?: ""
    fun searched(ctx: Context, number: String): Boolean = prefs(ctx).contains("web_" + digits(number).takeLast(10))
    fun complained(ctx: Context, number: String): Boolean = prefs(ctx).getBoolean("webbad_" + digits(number).takeLast(10), false)
    /** Web pages say a scammer used this number ("called saying my KYC expires"): red, never a brand name. */
    fun webFraud(ctx: Context, number: String): Boolean = prefs(ctx).getBoolean("webfraud_" + digits(number).takeLast(10), false)
    fun setTag(ctx: Context, number: String, name: String?, complaints: Boolean, fraud: Boolean = false) {
        val k = digits(number).takeLast(10); if (k.length < 6) return
        prefs(ctx).edit().putString("web_$k", name?.trim()?.take(40)?.ifEmpty { null } ?: "-").putBoolean("webbad_$k", complaints)
            .putBoolean("webfraud_$k", fraud).apply()
    }
    private fun fraudLine() = L("⛔ web पर लोगों ने इस नंबर को धोखेबाज़ बताया है: पैसे / OTP न दें", "⛔ People on the web report this number as a fraud: never give money / OTP")

    /** Who to show for a number: contact, then the SIM (CNAP, KYC) name, then "🔍 name" found on the web, then a name from an SMS. */
    fun display(ctx: Context, number: String, cnap: String = ""): String? =
        SmsStore.contactName(ctx, number) ?: cnap.ifEmpty { cnapOf(ctx, number) }.ifEmpty { null }?.let { "📛 $it" }
            ?: tag(ctx, number).ifEmpty { null }?.let { "🔍 $it" } ?: CallerId.known(ctx).people[CallerId.key(number)]?.let { "🛠 $it" }

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
            val k = CallerId.known(ctx)
            ctx.contentResolver.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.DATE, CallLog.Calls.TYPE, CallLog.Calls.DURATION, CallLog.Calls.CACHED_PHOTO_URI),
                null, null, CallLog.Calls.DATE + " DESC LIMIT 300")?.use { c ->
                while (c.moveToNext()) {
                    val num = c.getString(0) ?: ""
                    val name = c.getString(1) ?: SmsStore.contactName(ctx, num) ?: ""
                    val sim = if (name.isEmpty()) cnapOf(ctx, num) else ""
                    val w = if (name.isEmpty()) (if (webFraud(ctx, num)) CallerId.Who("risk", fraudLine()) else CallerId.judge(num, false, sim, k, c.getLong(2))) else null
                    // a name for a number not in contacts: your own name for it, the SIM name, a name from a company SMS
                    val tag = if (name.isEmpty()) tag(ctx, num) else ""
                    val searched = name.isEmpty() && searched(ctx, num); val bad = name.isEmpty() && complained(ctx, num)
                    val photo = if (name.isNotEmpty()) (try { c.getString(5) } catch (e: Exception) { null } ?: "") else ""
                    val guess = if (name.isEmpty()) k.people[CallerId.key(num)] ?: "" else ""
                    out.put(JSONObject().put("number", num).put("name", name).put("date", c.getLong(2)).put("type", c.getInt(3)).put("dur", c.getLong(4))
                        .put("label", w?.text ?: "").put("level", w?.level ?: "").put("tag", tag).put("sim", sim).put("guess", guess).put("searched", searched).put("complaints", bad).put("photo", photo))
                }
            }
        } catch (e: Exception) { }
        return out
    }

    fun contacts(ctx: Context): JSONArray {
        val out = JSONArray()
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return out
        val pins = pins(ctx)
        try {
            ctx.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI, ContactsContract.CommonDataKinds.Phone.STARRED), null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC")?.use { c ->
                val seen = HashSet<String>()
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: continue; val num = c.getString(1) ?: continue
                    if (seen.add(name + digits(num).takeLast(10))) out.put(JSONObject().put("name", name).put("number", num).put("photo", c.getString(2) ?: "")
                        .put("fav", c.getInt(3) == 1 || digits(num).takeLast(10) in pins))
                }
            }
        } catch (e: Exception) { }
        return out
    }

    // ---- favourites (owner 21 Nov 2026: "फेवरेट / फैमिली वाले कॉन्टैक्ट ऊपर, छोटे-छोटे, तुरंत फ़ोन") ----
    fun pins(ctx: Context): Set<String> = prefs(ctx).getStringSet("pins", emptySet()) ?: emptySet()
    fun pin(ctx: Context, number: String, on: Boolean) {
        val k = digits(number).takeLast(10); if (k.length < 6) return
        val s = HashSet(pins(ctx)); if (on) s.add(k) else s.remove(k)
        prefs(ctx).edit().putStringSet("pins", s).putStringSet("unpinned", HashSet(prefs(ctx).getStringSet("unpinned", emptySet()) ?: emptySet()).apply { if (on) remove(k) else add(k) }).apply()
    }

    /**
     * Speed-dial row: your ⭐ pins, the contacts starred in Google Contacts (your phone's favourites), then the contacts you
     * call most (last 30 days) - each with the photo from the contact. A contact you un-starred in Ronin stays off.
     */
    fun favorites(ctx: Context): JSONArray {
        val out = JSONArray()
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return out
        val pins = pins(ctx); val off = prefs(ctx).getStringSet("unpinned", emptySet()) ?: emptySet()
        val seen = LinkedHashMap<String, JSONObject>()
        fun add(name: String, num: String, photo: String, why: String) {
            val k = digits(num).takeLast(10); if (k.length < 6 || k in seen || (k in off && why != "pin")) return
            seen[k] = JSONObject().put("name", name).put("number", num).put("photo", photo).put("why", why)
        }
        try {
            ctx.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI, ContactsContract.CommonDataKinds.Phone.STARRED), null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC")?.use { c ->
                val starred = ArrayList<Triple<String, String, String>>()
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: continue; val num = c.getString(1) ?: continue; val ph = c.getString(2) ?: ""
                    if (digits(num).takeLast(10) in pins) add(name, num, ph, "pin")
                    else if (c.getInt(3) == 1) starred.add(Triple(name, num, ph))
                }
                starred.forEach { add(it.first, it.second, it.third, "star") }
            }
        } catch (e: Exception) { }
        if (ctx.checkSelfPermission(Manifest.permission.READ_CALL_LOG) == android.content.pm.PackageManager.PERMISSION_GRANTED) try {
            val count = HashMap<String, Int>(); val info = HashMap<String, Triple<String, String, String>>()
            ctx.contentResolver.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.CACHED_PHOTO_URI, CallLog.Calls.DURATION),
                CallLog.Calls.DATE + " > ?", arrayOf((System.currentTimeMillis() - 30 * 86_400_000L).toString()), CallLog.Calls.DATE + " DESC")?.use { c ->
                while (c.moveToNext()) {
                    val num = c.getString(0) ?: continue; val name = c.getString(1) ?: continue
                    if (c.getLong(3) <= 0) continue
                    val k = digits(num).takeLast(10)
                    count[k] = (count[k] ?: 0) + 1; info.putIfAbsent(k, Triple(name, num, c.getString(2) ?: ""))
                }
            }
            count.entries.filter { it.value >= 2 }.sortedByDescending { it.value }.take(12).forEach { e -> info[e.key]?.let { add(it.first, it.second, it.third, "often") } }
        } catch (e: Exception) { }
        seen.values.take(16).forEach { out.put(it) }
        return out
    }

    private val photoCache = object : android.util.LruCache<String, String>(80) {}

    /** A contact photo as a small picture for the page (data: URL), made on the phone; "" when there is none. */
    fun photoData(ctx: Context, uri: String, px: Int = 144): String {
        if (!uri.startsWith("content://")) return ""
        photoCache.get("$px|$uri")?.let { return it }
        val out = try {
            ctx.contentResolver.openInputStream(Uri.parse(uri))?.use { s ->
                val bmp = android.graphics.BitmapFactory.decodeStream(s) ?: return ""
                val scaled = android.graphics.Bitmap.createScaledBitmap(bmp, px, px * bmp.height / maxOf(1, bmp.width), true)
                val bo = java.io.ByteArrayOutputStream(); scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, bo)
                "data:image/jpeg;base64," + android.util.Base64.encodeToString(bo.toByteArray(), android.util.Base64.NO_WRAP)
            } ?: ""
        } catch (e: Exception) { "" }
        photoCache.put("$px|$uri", out)
        return out
    }

    /** The big photo of a caller in contacts (Google Contacts' full photo / calling-card picture), or null. */
    fun bigPhoto(ctx: Context, number: String): android.graphics.Bitmap? {
        if (number.isEmpty() || ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return null
        return try {
            var id = -1L
            ctx.contentResolver.query(Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)), arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)
                ?.use { c -> if (c.moveToFirst()) id = c.getLong(0) }
            if (id < 0) return null
            val u = android.content.ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id)
            ContactsContract.Contacts.openContactPhotoInputStream(ctx.contentResolver, u, true)?.use { android.graphics.BitmapFactory.decodeStream(it) }
        } catch (e: Exception) { null }
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
    private var resumes = 0
    // back from a call / another app: the page shows the newest calls without a manual refresh (owner 21 Nov 2026)
    override fun onResume() { super.onResume(); if (resumes++ > 0) event("changed") }
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
            "favorites" -> bg { done(CallInfo.favorites(this)) }
            "pin" -> bg { CallInfo.pin(this, a.optString("number"), a.optBoolean("on")); done(CallInfo.favorites(this)) }
            "photo" -> bg { done(CallInfo.photoData(this, a.optString("uri"), a.optInt("px", 144).coerceIn(48, 480))) }
            "call" -> done(CallInfo.call(this, a.optString("number")))
            "inCall" -> { if (CallHub.calls.isNotEmpty()) startActivity(Intent(this, InCallActivity::class.java)); done(CallHub.calls.isNotEmpty()) }
            // Ronin Messages when it is on; otherwise the phone's own SMS app (a switched-off screen cannot open)
            "sms" -> { val n = a.optString("number"); try {
                if (Parts.isOn(this, Parts.MESSAGES)) startActivity(Intent(this, MessagesActivity::class.java).putExtra("address", n))
                else startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(n))))
            } catch (e: Exception) { }; done(true) }
            "block" -> bg { done(SmsStore.block(this, a.optString("number"))) }
            "label" -> bg { done(CallInfo.label(this, a.optString("number"))) }
            // owner 21 Nov 2026 (both hands hurt): say a name for an unknown number once; kept on the phone, not a contact
            // owner 21 Nov 2026: one tap and Ronin itself finds the name on the web (only this number is searched)
            "whoIs" -> bg {
                try {
                    val n = a.optString("number")
                    val ans = WhoIs.lookUp(n)
                    if (ans != null) CallInfo.setTag(this, n, ans.name, ans.complaints, ans.fraud)
                    done(if (ans == null) JSONObject().put("ok", false) else JSONObject().put("ok", true).put("name", ans.name ?: "").put("complaints", ans.complaints)
                        .put("fraud", ans.fraud).put("source", ans.source).put("label", if (ans.fraud) CallInfo.label(this, n) else ""))
                } catch (t: Throwable) { done(JSONObject().put("ok", false)) } // the button never stays on "looking…"
            }
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
    // in a thread (QA 21 Nov): reading the call log + SMS for "is this number known?" must not hold the main thread;
    // respondToCall may come from any thread. It also fills CallerId's cache for the ring / call screens right after.
    override fun onScreenCall(d: Call.Details) {
        Thread {
            var silence = false
            try { silence = shouldSilence(d) } catch (e: Throwable) { }
            val b = CallResponse.Builder()
            if (Build.VERSION.SDK_INT >= 29) b.setSilenceCall(silence)
            try { respondToCall(d, b.build()) } catch (e: Throwable) { }
        }.start()
    }

    private fun shouldSilence(d: Call.Details): Boolean {
        val num = d.handle?.schemeSpecificPart ?: ""
        // nothing to silence: answer at once (the phone rings without waiting), then read for the ring screen
        val pp = CallInfo.prefs(this)
        if (!pp.getBoolean("silenceUnknown", false) && !pp.getBoolean("silenceForeign", false)) { CallerId.warm(this); return false }
        val p = CallInfo.prefs(this)
        val incoming = Build.VERSION.SDK_INT < 29 || d.callDirection == Call.Details.DIRECTION_INCOMING
        var silence = false
        // never silence without the contacts (everyone would look unknown), nor soon after an emergency call
        if (incoming && SmsStore.canReadContacts(this) && !CallInfo.recentEmergency(this) && !CallInfo.isEmergency(this, num) && SmsStore.contactName(this, num) == null) {
            val dg = CallInfo.digits(num)
            // a number this phone has reason to trust (your call, your order's company SMS, a 1600 bank line) still rings
            if (p.getBoolean("silenceUnknown", false) && !CallerId.trusted(CallerId.judge(num, false, "", CallerId.known(this)))) silence = true
            if (p.getBoolean("silenceForeign", false) && dg.startsWith("+") && !dg.startsWith("+91")) silence = true
        }
        return silence
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
        if (CallHub.calls.isEmpty()) { CallHub.muted = null; CallHub.pending = null } // the next call starts fresh
        CallHub.changed()
    }
    override fun onCallAudioStateChanged(s: CallAudioState?) = CallHub.changed()
    // Android 14+: the speaker / earpiece / Bluetooth "endpoint" and mute come here (3.13.3: the old
    // callAudioState was often stale, so Speaker needed 2-3 taps on the owner's phone)
    override fun onCallEndpointChanged(e: android.telecom.CallEndpoint) { CallHub.endpoint = e; CallHub.pending = null; CallHub.changed() }
    override fun onAvailableCallEndpointsChanged(l: List<android.telecom.CallEndpoint>) { CallHub.endpoints = l; CallHub.changed() }
    override fun onMuteStateChanged(m: Boolean) { CallHub.muted = m; CallHub.changed() }
    override fun onDestroy() { if (CallHub.service == this) CallHub.service = null; super.onDestroy() }
}

object CallHub {
    const val NOTE = 7101
    var service: InCallService? = null
    var endpoint: android.telecom.CallEndpoint? = null
    var endpoints: List<android.telecom.CallEndpoint> = emptyList()
    var muted: Boolean? = null
    /** what the owner just asked for (shown at once, before Android confirms): true = speaker */
    var pending: Boolean? = null

    fun speakerOn(): Boolean {
        pending?.let { return it }
        if (Build.VERSION.SDK_INT >= 34) endpoint?.let { return it.endpointType == android.telecom.CallEndpoint.TYPE_SPEAKER }
        return service?.callAudioState?.route == CallAudioState.ROUTE_SPEAKER
    }
    fun isMuted(): Boolean { muted?.let { return it }; return service?.callAudioState?.isMuted ?: false }
    /** at the ear (not speaker / Bluetooth / headset): only then may the screen go dark near the face */
    fun atEar(): Boolean {
        if (speakerOn()) return false
        if (Build.VERSION.SDK_INT >= 34) endpoint?.let { return it.endpointType == android.telecom.CallEndpoint.TYPE_EARPIECE }
        val r = service?.callAudioState?.route ?: return true
        return r == CallAudioState.ROUTE_EARPIECE || r == CallAudioState.ROUTE_WIRED_OR_EARPIECE && service?.callAudioState?.supportedRouteMask?.and(CallAudioState.ROUTE_WIRED_HEADSET) == 0
    }
    fun setSpeaker(on: Boolean) {
        val svc = service ?: return
        pending = on
        if (Build.VERSION.SDK_INT >= 34 && endpoints.isNotEmpty()) {
            val want = if (on) endpoints.firstOrNull { it.endpointType == android.telecom.CallEndpoint.TYPE_SPEAKER }
                else endpoints.firstOrNull { it.endpointType == android.telecom.CallEndpoint.TYPE_BLUETOOTH }
                    ?: endpoints.firstOrNull { it.endpointType == android.telecom.CallEndpoint.TYPE_WIRED_HEADSET }
                    ?: endpoints.firstOrNull { it.endpointType == android.telecom.CallEndpoint.TYPE_EARPIECE }
            if (want != null) {
                svc.requestCallEndpointChange(want, svc.mainExecutor, object : android.os.OutcomeReceiver<Void, android.telecom.CallEndpointException> {
                    override fun onResult(r: Void?) { pending = null; changed() }
                    override fun onError(e: android.telecom.CallEndpointException) { pending = null; changed() }
                })
                changed(); return
            }
        }
        svc.setAudioRoute(if (on) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_WIRED_OR_EARPIECE)
        // the old way has no answer: trust the request for a moment, then go back to what Android reports
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ pending = null; changed() }, 1500)
        changed()
    }
    fun setMute(m: Boolean) { muted = m; service?.setMuted(m); changed() }
    val calls = ArrayList<Call>()
    val incoming = HashMap<Call, Boolean>()

    /**
     * "Report (Chakshu)" opens Ronin Browser on the filled form straight from the notification (Android 12+ does not let
     * a receiver open a screen after a notification tap). Without Ronin Browser: the form in the phone's browser.
     */
    fun reportCall(ctx: Context, num: String, code: Int): PendingIntent {
        val i = if (Chakshu.hasBrowser(ctx)) Chakshu.entry(Chakshu.data("call", num, "", System.currentTimeMillis()))
            else Intent(Intent.ACTION_VIEW, Uri.parse(Chakshu.FORM)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(ctx, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** After a call from a number that is not saved: "Spam? Block / Report" in one tap. */
    fun afterUnknown(ctx: Context, num: String) { Thread { try { afterUnknownNow(ctx.applicationContext, num) } catch (e: Throwable) { } }.start() }

    private fun afterUnknownNow(ctx: Context, num: String) {
        if (num.isEmpty() || CallInfo.isEmergency(ctx, num) || SmsStore.contactName(ctx, num) != null) return
        if (!SmsStore.canReadContacts(ctx)) return // without contacts everyone looks unknown
        // your own call, your order's company, a bank's 1600 line: no "spam?" question after it (owner 21 Nov)
        if (CallerId.trusted(CallerId.judge(num, false, "", CallerId.known(ctx)))) return
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
            .addAction(0, L("📵 चक्षु पर शिकायत", "📵 Report (Chakshu)"), reportCall(ctx, num, id * 4 + 2)).build()
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
            .setContentTitle("📞 " + (CallInfo.display(ctx, num) ?: num)).setContentText(L("Call चल रही है: वापस जाने के लिए दबाएँ", "Call in progress: tap to go back"))
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
        val who = CallInfo.display(ctx, num, CallInfo.cnap(c))?.let { if (SmsStore.contactName(ctx, num) == null) "$it · $num" else it } ?: num.ifEmpty { "Private number" }
        val screen = PendingIntent.getActivity(ctx, 1, Intent(ctx, InCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        fun act(a: String, code: Int) = PendingIntent.getBroadcast(ctx, code, Intent(ctx, CallActionReceiver::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE)
        // "Answer" opens the call screen itself (Android 12+ does not let a receiver open a screen)
        val answer = PendingIntent.getActivity(ctx, 3, Intent(ctx, InCallActivity::class.java).putExtra("answer", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, "ronin_ring").setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle("📞 $who").setContentText(CallInfo.label(ctx, num, CallInfo.cnap(c).ifEmpty { CallInfo.cnapOf(ctx, num) }).ifEmpty { "Incoming call" })
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
                // older notifications: Ronin Browser on the filled Chakshu form
                Chakshu.open(ctx, Chakshu.data("call", num, "", System.currentTimeMillis()))
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
    private lateinit var ringRow: LinearLayout; private lateinit var talkRow: LinearLayout; private lateinit var keypad: LinearLayout
    private lateinit var second: TextView
    private lateinit var simName: TextView
    private lateinit var simRow: LinearLayout
    // the caller's big photo from contacts behind the call screen (owner 21 Nov 2026: "वाइफ की पूरी फ़ोटो रहती थी")
    private lateinit var photo: android.widget.ImageView
    private lateinit var shade: android.view.View
    private lateinit var rootLayout: LinearLayout
    private var photoFor = "\u0000"
    private var prox: PowerManager.WakeLock? = null
    // every second only the timer changes (a full redraw each second could swallow a tap)
    private val tick = Runnable { val c = top(); if (c != null && c.state == Call.STATE_ACTIVE) { state.text = "⏱ " + elapsed(c) + (if (CallRecorder.recordingNumber != null) "   🎙 Rec" else ""); who.postDelayed(tickRef, 1000) } }
    private val tickRef: Runnable get() = tick
    private lateinit var muteBtn: Button; private lateinit var speakerBtn: Button; private lateinit var keypadBtn: Button
    private var lastSims = -1
    /** ON buttons are coloured, so one tap is clearly seen to have worked */
    private fun paintButtons() {
        if (!::muteBtn.isInitialized) return
        val d = resources.displayMetrics.density
        fun paint(b: Button, on: Boolean, onColor: String, label: String, onLabel: String) {
            b.text = if (on) onLabel else label
            ((b.background as? android.graphics.drawable.RippleDrawable)?.getDrawable(0) as? GradientDrawable)?.setColor(Color.parseColor(if (on) onColor else "#334155"))
            b.contentDescription = b.text
        }
        paint(muteBtn, CallHub.isMuted(), "#b45309", "🔇 Mute", L("🔇 Mute चालू", "🔇 Muted"))
        paint(speakerBtn, CallHub.speakerOn(), "#16a34a", "🔊 Speaker", L("🔊 Speaker चालू", "🔊 Speaker ON"))
        paint(keypadBtn, keypad.visibility == android.view.View.VISIBLE, "#1d4ed8", "⌨ Keypad", "⌨ Keypad")
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val d = resources.displayMetrics.density
        fun btn(t: String, bg: String, f: () -> Unit) = Button(this).apply {
            text = t; textSize = 20f; setTextColor(Color.WHITE); isAllCaps = false
            // a visible "press" ripple + a small buzz, like a normal phone app (3.13.3)
            background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Color.parseColor("#55FFFFFF")),
                GradientDrawable().apply { setColor(Color.parseColor(bg)); cornerRadius = 40 * d }, null)
            stateListAnimator = null
            isHapticFeedbackEnabled = true
            minHeight = (72 * d).toInt(); setOnClickListener { performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY); f() }
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
            muteBtn = btn("🔇 Mute", "#334155") { CallHub.setMute(!CallHub.isMuted()) }
            speakerBtn = btn("🔊 Speaker", "#334155") { CallHub.setSpeaker(!CallHub.speakerOn()) }
            keypadBtn = btn("⌨ Keypad", "#334155") { keypad.visibility = if (keypad.visibility == android.view.View.VISIBLE) android.view.View.GONE else android.view.View.VISIBLE; paintButtons() }
            addView(muteBtn); addView(speakerBtn); addView(keypadBtn) }
        val r2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; addView(btn(L("📵 Call काटें", "📵 Hang up"), "#dc2626") { top()?.disconnect() }) }
        talkRow.addView(r1); talkRow.addView(r2)
        // the in-call keypad (customer care "press 1..."): big keys over the whole width, and the digits pressed
        // shown large above them (owner, 21 Nov 2026: the keys were tiny on the left and nothing showed what was pressed)
        val typed = TextView(this).apply {
            textSize = 34f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; letterSpacing = 0.12f
            setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.START; minHeight = (56 * d).toInt()
            contentDescription = L("दबाए गए नंबर", "Digits pressed")
        }
        val grid = GridLayout(this).apply { columnCount = 3; useDefaultMargins = false }
        val letters = mapOf("2" to "ABC", "3" to "DEF", "4" to "GHI", "5" to "JKL", "6" to "MNO", "7" to "PQRS", "8" to "TUV", "9" to "WXYZ", "0" to "+")
        for (k in listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#")) grid.addView(TextView(this).apply {
            val sub = letters[k]
            text = if (sub == null) k else android.text.SpannableString(k + "\n" + sub).apply {
                setSpan(android.text.style.RelativeSizeSpan(0.42f), k.length + 1, length, 0)
                setSpan(android.text.style.ForegroundColorSpan(Color.parseColor("#9AA3B2")), k.length + 1, length, 0)
            }
            textSize = 30f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; includeFontPadding = false
            isClickable = true; isFocusable = true; contentDescription = k
            background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Color.parseColor("#66FFFFFF")),
                GradientDrawable().apply { setColor(Color.parseColor("#1E2533")); cornerRadius = 36 * d }, null)
            layoutParams = GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply {
                width = 0; height = (68 * d).toInt(); setMargins((6 * d).toInt(), (5 * d).toInt(), (6 * d).toInt(), (5 * d).toInt())
            }
            // the tone sounds while the finger is down, like a normal phone
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        v.isPressed = true; v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                        top()?.playDtmfTone(k[0]); typed.append(k)
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        v.isPressed = false; top()?.stopDtmfTone(); if (e.actionMasked == android.view.MotionEvent.ACTION_UP) v.performClick()
                    }
                }
                true
            }
        })
        keypad = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = android.view.View.GONE; setPadding(0, 0, 0, (8 * d).toInt())
            addView(typed); addView(grid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        simRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // the buttons sit at the BOTTOM, where the thumb is (owner, 19 Nov 2026); the space in between stays empty
        val gap = android.view.View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 0, 1f) }
        root.addView(who); root.addView(state); root.addView(simName); root.addView(second); root.addView(gap)
        root.addView(simRow); root.addView(keypad); root.addView(ringRow); root.addView(talkRow)
        rootLayout = root
        photo = android.widget.ImageView(this).apply { scaleType = android.widget.ImageView.ScaleType.CENTER_CROP; visibility = android.view.View.GONE }
        // dark at the top (name) and bottom (buttons), the face shows in the middle
        shade = android.view.View(this).apply { visibility = android.view.View.GONE
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.parseColor("#cc0f1218"), Color.parseColor("#330f1218"), Color.parseColor("#ee0f1218"))) }
        val frame = android.widget.FrameLayout(this)
        val mp = android.widget.FrameLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
        frame.addView(photo, mp); frame.addView(shade, android.widget.FrameLayout.LayoutParams(mp)); frame.addView(root, android.widget.FrameLayout.LayoutParams(mp))
        frame.setBackgroundColor(Color.parseColor("#0f1218"))
        setContentView(frame)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, ins ->
            val b = ins.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding((16 * d).toInt() + b.left, (40 * d).toInt() + b.top, (16 * d).toInt() + b.right, (24 * d).toInt() + b.bottom); androidx.core.view.WindowInsetsCompat.CONSUMED
        }
        // Back leaves the call running and the screen reachable (Recents / the notification)
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (CallHub.calls.isEmpty()) finishAndRemoveTask() else moveTaskToBack(true) }
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
        if (c.state == lastSims && simRow.childCount > 0 == (c.state == Call.STATE_SELECT_PHONE_ACCOUNT)) return // nothing changed
        lastSims = c.state
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
        // the call is over: the call screen leaves Recents too (owner: two cards to swipe away after a call)
        if (c == null) { prox?.let { if (it.isHeld) it.release() }; finishAndRemoveTask(); return }
        val num = CallHub.number(c)
        if (num != photoFor) {
            photoFor = num
            Thread {
                val b = CallInfo.bigPhoto(applicationContext, num)
                runOnUiThread {
                    if (isFinishing || photoFor != num) return@runOnUiThread
                    if (b != null) { photo.setImageBitmap(b); photo.visibility = android.view.View.VISIBLE; shade.visibility = android.view.View.VISIBLE; rootLayout.setBackgroundColor(Color.TRANSPARENT) }
                    else { photo.setImageDrawable(null); photo.visibility = android.view.View.GONE; shade.visibility = android.view.View.GONE; rootLayout.setBackgroundColor(Color.parseColor("#0f1218")) }
                }
            }.start()
        }
        who.text = SmsStore.contactName(this, num) ?: CallInfo.tag(this, num).ifEmpty { null }?.takeIf { !CallInfo.webFraud(this, num) }?.let { "🔍 $it\n$num" } ?: num.ifEmpty { "Private number" }
        val saved = SmsStore.contactName(this, num) != null
        val sim = if (saved) "" else CallInfo.cnap(c).ifEmpty { CallInfo.cnapOf(this, num) }
        simName.text = L("📛 नेटवर्क से नाम (SIM का KYC): ", "📛 Name from the network (SIM KYC): ") + sim
        simName.visibility = if (sim.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        // no long warning text on the call screen any more (owner, 19 Nov 2026: "ये सब मत लिखो")
        warn.visibility = android.view.View.GONE
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
        // the screen goes dark near the face only at the ear; on speaker / headset it stays on for tapping
        val ear = active && CallHub.atEar()
        prox?.let { if (ear && !it.isHeld) it.acquire(3 * 60 * 60 * 1000L) else if (!ear && it.isHeld) it.release() }
        paintButtons()
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
