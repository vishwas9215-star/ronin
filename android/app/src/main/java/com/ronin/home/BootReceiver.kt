package com.ronin.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log

/**
 * TV switched on, or Ronin Home updated: Android does not always reconnect the Home button helper by itself
 * (seen on the family TCL after a restart). Switch it off and on again (needs WRITE_SECURE_SETTINGS, granted once)
 * and, after power-on, open Ronin Home.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext
        val pending = goAsync()
        Log.i("RoninHome", "boot receiver: ${intent.action}")
        reconnectHelper(app) {
            if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
                try {
                    app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) { Log.w("RoninHome", "boot start: $e") }
            }
            pending.finish()
        }
    }

    companion object {
        const val SERVICE = "com.ronin.home/com.ronin.home.HomeKeyService"

        fun reconnectHelper(ctx: Context, done: () -> Unit = {}) {
            val cr = ctx.contentResolver
            try {
                val others = (Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
                    .split(':').filter { it.isNotBlank() && it != "null" && it != SERVICE }
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, others.joinToString(":"))
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (others + SERVICE).joinToString(":"))
                        Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                        Log.i("RoninHome", "helper reconnected")
                    } catch (e: Exception) { Log.w("RoninHome", "helper on: $e") }
                    done()
                }, 1500)
            } catch (e: SecurityException) {
                Log.w("RoninHome", "no WRITE_SECURE_SETTINGS: $e"); done()
            }
        }
    }
}
