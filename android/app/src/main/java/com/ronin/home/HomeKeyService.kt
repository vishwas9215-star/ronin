package com.ronin.home

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import java.io.File

/**
 * Google TV's own home (launcherx) stays installed and ENABLED, so Gemini / voice / "open movie" keep working.
 * This service only catches the remote's HOME button and shows Ronin Home instead.
 * Nothing else is touched: Gemini (assist/search), Settings, Back, volume and every other key pass through.
 */
class HomeKeyService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        note("connected")
        // Just after power-on the Google home shows first: replace it once.
        if (SystemClock.elapsedRealtime() < 3 * 60_000) showHome("boot")
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_HOME) return false
        if (event.action == KeyEvent.ACTION_UP) showHome("home-key")
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Diagnostics only (which screen is on top), kept short; helps if a TV build behaves differently.
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) note("window ${event.packageName}/${event.className}")
    }

    override fun onInterrupt() {}

    private fun showHome(why: String) {
        note("show $why")
        try {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        } catch (e: Exception) { Log.w(TAG, "start: $e") }
    }

    private fun note(s: String) {
        Log.i(TAG, s)
        try {
            val f = File(getExternalFilesDir(null), "home-log.txt")
            if (f.length() > 64_000) f.writeText("")
            f.appendText("${System.currentTimeMillis()} $s\n")
        } catch (e: Exception) { }
    }

    companion object { const val TAG = "RoninHome" }
}
