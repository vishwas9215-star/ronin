package com.ronin.browser.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Keeps Ronin Agent Lab alive while the owner uses other apps (the Claude app, WhatsApp...): a small notification
 * "C के लिए Ronin चालू है" and a CPU wake lock, so the link to the relay and the Claude tab keep working in the
 * background and he can switch back any time to watch. Tapping the notification opens the Claude tab.
 */
class AgentKeepAlive : Service() {
    private var cpu: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { // STOP in the notification: Claude stops at once on this device
            AgentActivity.driver?.ownerStop(); android.util.Log.i("RoninAgent", "owner pressed STOP in the notification")
            getSystemService(NotificationManager::class.java).notify(7, note(this, "⏹ आपने STOP दबाया: C रुका है (Ronin खोलकर RESUME)"))
            return START_STICKY
        }
        val n = note(this, "देखने के लिए दबाएँ · रोकना हो तो STOP")
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(7, n)
        } catch (e: Exception) { android.util.Log.i("RoninAgent", "keep-alive: ${e.javaClass.simpleName}") }
        if (cpu == null) cpu = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ronin:link").apply { setReferenceCounted(false); acquire() }
        android.util.Log.i("RoninAgent", "keep-alive: on")
        return START_STICKY
    }

    override fun onDestroy() { try { cpu?.release() } catch (e: Exception) {}; super.onDestroy() }

    companion object {
        fun note(c: Context, line: String): Notification {
            val nm = c.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("agent", "Ronin Agent", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(c, 0, Intent(c, AgentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT), PendingIntent.FLAG_IMMUTABLE)
            val stop = PendingIntent.getService(c, 1, Intent(c, AgentKeepAlive::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, "agent") else @Suppress("DEPRECATION") Notification.Builder(c)
            return b.setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle("🤖 C के लिए Ronin चालू है")
                .setContentText(line).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .addAction(Notification.Action.Builder(null, "STOP", stop).build())
                .addAction(Notification.Action.Builder(null, "देखें", open).build()).build()
        }

        /** the notification shows Claude's last step, e.g. "typed into क्रम" */
        fun update(c: Context, line: String) {
            try { c.getSystemService(NotificationManager::class.java).notify(7, note(c, "आख़िरी काम: $line")) } catch (_: Exception) {}
        }

        fun start(c: Context) {
            val i = Intent(c, AgentKeepAlive::class.java)
            try { if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i) } catch (e: Exception) {
                android.util.Log.i("RoninAgent", "keep-alive: ${e.javaClass.simpleName}")
            }
        }
    }
}
