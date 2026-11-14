package com.ronin.browser

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ronin SOS: "मैं मुसीबत में हूँ". One press (held 2 s) sends the family an SMS with the exact place
 * (a Maps link), the battery and the time, then a new place every 5 minutes for an hour or until
 * "मैं ठीक हूँ". SMS works without internet. No server; the numbers stay on this phone.
 */
object SosStore {
    fun prefs(ctx: Context) = ctx.getSharedPreferences("ronin_sos", Context.MODE_PRIVATE)
    fun contacts(ctx: Context): JSONArray = try { JSONArray(prefs(ctx).getString("contacts", "[]")) } catch (e: Exception) { JSONArray() }
    fun name(ctx: Context) = prefs(ctx).getString("name", "") ?: ""
    var running = false

    fun battery(ctx: Context): Int {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return -1
        val l = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val s = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        return if (l < 0) -1 else l * 100 / s
    }

    fun message(ctx: Context, loc: Location?, first: Boolean, stop: Boolean = false): String {
        val who = name(ctx).ifEmpty { "मैं" }
        val time = SimpleDateFormat("HH:mm", Locale.US).format(Date())
        if (stop) return "✅ $who अब ठीक है। (Ronin SOS बंद, $time)"
        val place = if (loc != null) "https://maps.google.com/?q=%.6f,%.6f (±%dm)".format(Locale.US, loc.latitude, loc.longitude, loc.accuracy.toInt()) else "जगह अभी नहीं मिली"
        val b = battery(ctx)
        return (if (first) "🆘 $who मुसीबत में है, तुरंत संपर्क करें। " else "🆘 $who की नई जगह: ") + place + (if (b >= 0) " | Battery $b%" else "") + " | $time"
    }
}

class SosService : Service() {
    private val ui = Handler(Looper.getMainLooper())
    private var until = 0L
    private var first = true
    private val tick = object : Runnable {
        override fun run() {
            if (System.currentTimeMillis() > until) { stopSelf(); return }
            sendNow()
            ui.postDelayed(this, 5 * 60 * 1000L)
        }
    }

    override fun onBind(i: Intent?): IBinder? = null
    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        if (i?.action == "stop") {
            if (SosStore.running) send(SosStore.message(this, null, false, stop = true))
            stopSelf(); return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("ronin_sos", "SOS", NotificationManager.IMPORTANCE_HIGH))
        val stop = PendingIntent.getService(this, 1, Intent(this, SosService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 2, Intent(this, SosActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, "ronin_sos").setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("🆘 SOS चालू: परिवार को हर 5 मिनट जगह जा रही है").setOngoing(true).setContentIntent(open)
            .addAction(0, "✅ मैं ठीक हूँ (बंद करें)", stop).build()
        try {
            if (Build.VERSION.SDK_INT >= 30) startForeground(7301, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) else startForeground(7301, n)
        } catch (e: Exception) { try { startForeground(7301, n) } catch (x: Exception) { } }
        SosStore.running = true
        first = true
        until = System.currentTimeMillis() + 60 * 60 * 1000L
        ui.removeCallbacks(tick); ui.post(tick)
        return START_NOT_STICKY
    }

    override fun onDestroy() { ui.removeCallbacks(tick); SosStore.running = false; super.onDestroy() }

    private fun send(text: String) {
        val list = SosStore.contacts(this)
        for (k in 0 until list.length()) list.optJSONObject(k)?.optString("number")?.takeIf { it.isNotBlank() }?.let { SmsStore.send(this, it, text) }
    }

    /** The newest place we can get: a fresh GPS / network fix, else the last known one. */
    @SuppressLint("MissingPermission")
    private fun sendNow() {
        val lm = getSystemService(LocationManager::class.java)
        val ok = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val last = if (ok) listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (e: Exception) { null } }.maxByOrNull { it.time } else null
        val wasFirst = first; first = false
        if (!ok || Build.VERSION.SDK_INT < 30) { send(SosStore.message(this, last, wasFirst)); return }
        var done = false
        val finish = { l: Location? -> if (!done) { done = true; send(SosStore.message(this, l ?: last, wasFirst)) } }
        try {
            val provider = if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
            lm.getCurrentLocation(provider, null, mainExecutor) { l -> finish(l) }
            ui.postDelayed({ finish(null) }, 25_000) // no fix in time: the last known place, so nothing waits too long
        } catch (e: Exception) { finish(null) }
    }

    companion object {
        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, SosService::class.java))
        fun stop(ctx: Context) = ctx.startService(Intent(ctx, SosService::class.java).setAction("stop"))
    }
}

/** The SOS page: family numbers, your name, the big button (ui/tools/sos.html). */
class SosActivity : PhonePage() {
    override val page = "sos.html"
    override fun handle(op: String, a: JSONObject, done: (Any?) -> Unit) {
        if (common(op, a, done)) return
        when (op) {
            "state" -> done(JSONObject().put("contacts", SosStore.contacts(this)).put("name", SosStore.name(this)).put("running", SosStore.running)
                .put("sms", has(Manifest.permission.SEND_SMS)).put("loc", has(Manifest.permission.ACCESS_FINE_LOCATION)).put("phone", Parts.hasPhone(this)))
            "save" -> { SosStore.prefs(this).edit().putString("contacts", a.optJSONArray("contacts")?.toString() ?: "[]").putString("name", a.optString("name")).apply(); done(true) }
            "contacts" -> askPermissions(arrayOf(Manifest.permission.READ_CONTACTS)) { bg { done(CallInfo.contacts(this)) } }
            "permissions" -> askPermissions(arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS)) { done(true) }
            "start" -> askPermissions(arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) {
                if (SosStore.contacts(this).length() == 0) done(false) else { SosService.start(this); done(true) }
            }
            "stop" -> { SosService.stop(this); done(true) }
            "call" -> done(CallInfo.call(this, a.optString("number")))
            else -> done(null)
        }
    }
}
