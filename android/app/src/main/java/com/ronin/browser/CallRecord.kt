package com.ronin.browser

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Automatic call recording for Ronin Calls: every call except the people on the "do not record"
 * list. Android lets only the phone maker's own phone app hear the call line itself; Ronin records
 * with the "accessibility" way, which on many phones gets both voices, on some the other side
 * softer. The family switches it on themselves (Settings), and the files stay on the phone in
 * Recordings / Ronin Calls.
 */
object CallRecorder {
    private var rec: MediaRecorder? = null
    private var pfd: ParcelFileDescriptor? = null
    private var uri: Uri? = null
    var recordingNumber: String? = null; private set

    private fun prefs(ctx: Context) = CallInfo.prefs(ctx)
    fun auto(ctx: Context) = prefs(ctx).getBoolean("recAuto", false)
    fun skipList(ctx: Context): List<String> = try {
        JSONArray(prefs(ctx).getString("recSkip", "[]")).let { a -> (0 until a.length()).map { a.getString(it) } }
    } catch (e: Exception) { emptyList() }
    private fun key(n: String) = CallInfo.digits(n).takeLast(10)
    fun skip(ctx: Context, number: String) = key(number).let { k -> k.isNotEmpty() && skipList(ctx).any { key(it) == k } }
    fun setSkip(ctx: Context, number: String, on: Boolean) {
        val l = skipList(ctx).filter { key(it) != key(number) }.toMutableList()
        if (on) l.add(number)
        prefs(ctx).edit().putString("recSkip", JSONArray(l).toString()).apply()
    }
    fun accessOn(ctx: Context): Boolean = (Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
        .contains(ctx.packageName + "/" + RecordAccess::class.java.name)

    /** Should this call be recorded now? */
    fun wanted(ctx: Context, number: String) = auto(ctx) && !skip(ctx, number) && !CallInfo.isEmergency(number) &&
        ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED

    @Synchronized
    fun start(ctx: Context, number: String): Boolean {
        if (rec != null) return true
        val name = (SmsStore.contactName(ctx, number) ?: "").filter { it.isLetterOrDigit() && it.code < 128 }.take(20)
        val file = "Call_" + (if (name.isNotEmpty()) name + "_" else "") + CallInfo.digits(number).replace("+", "") + "_" +
            SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date()) + ".m4a"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file); put(MediaStore.MediaColumns.MIME_TYPE, "audio/mp4")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, (if (Build.VERSION.SDK_INT >= 31) Environment.DIRECTORY_RECORDINGS else Environment.DIRECTORY_MUSIC) + "/Ronin Calls")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val u = try { ctx.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) } catch (e: Exception) { null } ?: return false
        val fd = try { ctx.contentResolver.openFileDescriptor(u, "w") } catch (e: Exception) { null } ?: return false
        // the call line itself first (works only for the phone maker's app), then the ways that work for others
        for (src in listOf(MediaRecorder.AudioSource.VOICE_CALL, MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.MIC)) {
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
            try {
                r.setAudioSource(src)
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioSamplingRate(44100); r.setAudioEncodingBitRate(64000)
                r.setOutputFile(fd.fileDescriptor)
                r.prepare(); r.start()
                rec = r; pfd = fd; uri = u; recordingNumber = number
                return true
            } catch (e: Exception) { try { r.release() } catch (x: Exception) { } }
        }
        try { fd.close(); ctx.contentResolver.delete(u, null, null) } catch (e: Exception) { }
        return false
    }

    @Synchronized
    fun stop(ctx: Context) {
        val r = rec ?: return
        try { r.stop() } catch (e: Exception) { }
        try { r.release() } catch (e: Exception) { }
        try { pfd?.close() } catch (e: Exception) { }
        uri?.let { u -> if (Build.VERSION.SDK_INT >= 29) try { ctx.contentResolver.update(u, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) } catch (e: Exception) { } }
        rec = null; pfd = null; uri = null; recordingNumber = null
    }

    /** The recordings, newest first. */
    fun list(ctx: Context): JSONArray {
        val out = JSONArray()
        try {
            ctx.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.SIZE),
                MediaStore.MediaColumns.DISPLAY_NAME + " LIKE 'Call\\_%' ESCAPE '\\'", null, MediaStore.MediaColumns.DATE_ADDED + " DESC LIMIT 300")?.use { c ->
                while (c.moveToNext()) out.put(JSONObject().put("id", c.getLong(0)).put("name", c.getString(1)).put("date", c.getLong(2) * 1000).put("size", c.getLong(3)))
            }
        } catch (e: Exception) { }
        return out
    }
    fun play(ctx: Context, id: Long) {
        val u = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toString())
        try { ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, "audio/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
    }
}

/** Keeps the recording going while the screen is off or another app is open (Android needs this). */
class RecordService : Service() {
    override fun onBind(i: Intent?): IBinder? = null
    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        if (i?.action == "stop") { CallRecorder.stop(this); stopSelf(); return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("ronin_rec", "Call recording", NotificationManager.IMPORTANCE_LOW))
        val n: Notification = NotificationCompat.Builder(this, "ronin_rec").setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("🎙 Call record हो रही है").setOngoing(true).build()
        try {
            if (Build.VERSION.SDK_INT >= 30) startForeground(7201, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(7201, n)
        } catch (e: Exception) { stopSelf(); return START_NOT_STICKY }
        val num = i?.getStringExtra("number") ?: ""
        if (!CallRecorder.start(this, num)) stopSelf()
        return START_NOT_STICKY
    }
    override fun onDestroy() { CallRecorder.stop(this); super.onDestroy() }

    companion object {
        fun start(ctx: Context, number: String) = try { ctx.startForegroundService(Intent(ctx, RecordService::class.java).putExtra("number", number)) } catch (e: Exception) { null }
        fun stop(ctx: Context) = try { ctx.startService(Intent(ctx, RecordService::class.java).setAction("stop")) } catch (e: Exception) { CallRecorder.stop(ctx); null }
    }
}

/** Does nothing with the screen: Android lets an "accessibility" app keep the microphone during a call. */
class RecordAccess : AccessibilityService() {
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}
