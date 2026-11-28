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
 * "Claude के लिए Ronin चालू है" and a CPU wake lock, so the link to the relay and the Claude tab keep working in the
 * background and he can switch back any time to watch. Tapping the notification opens the Claude tab.
 */
class AgentKeepAlive : Service() {
    private var cpu: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("agent", "Ronin Agent", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, AgentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT), PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "agent") else @Suppress("DEPRECATION") Notification.Builder(this)
        val n = b.setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle("🤖 Claude के लिए Ronin चालू है")
            .setContentText("देखने के लिए दबाएँ · रोकना हो तो STOP").setContentIntent(open).setOngoing(true).build()
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(7, n)
        } catch (e: Exception) { android.util.Log.i("RoninAgent", "keep-alive: ${e.javaClass.simpleName}") }
        if (cpu == null) cpu = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ronin:link").apply { setReferenceCounted(false); acquire() }
        android.util.Log.i("RoninAgent", "keep-alive: on")
        return START_STICKY
    }

    override fun onDestroy() { try { cpu?.release() } catch (e: Exception) {}; super.onDestroy() }

    companion object {
        fun start(c: Context) {
            val i = Intent(c, AgentKeepAlive::class.java)
            try { if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i) } catch (e: Exception) {
                android.util.Log.i("RoninAgent", "keep-alive: ${e.javaClass.simpleName}")
            }
        }
    }
}
