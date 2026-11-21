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

    private var lastAssistant = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString() ?: ""
        note("window $pkg/$cls")
        val now = SystemClock.elapsedRealtime()
        if (pkg in ASSISTANT) { lastAssistant = now; return }
        // Some TVs do not hand the HOME button to us: then the Google home screen appears.
        // Replace ONLY its main screen, and never right after Gemini/voice (it may be showing a movie page).
        if (isGoogleHomeScreen(pkg, cls) && now - lastAssistant > 8_000) showHome("google-home")
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

    companion object {
        const val TAG = "RoninHome"
        val ASSISTANT = setOf("com.google.android.katniss", "com.google.android.apps.bard", "com.google.android.googlequicksearchbox")
        private val HOMES = setOf(Catalog.LAUNCHER_X, "com.google.android.tvlauncher")

        /** Main screen of the Google home only (not its movie / details / search pages). */
        fun isGoogleHomeScreen(pkg: String, cls: String): Boolean {
            if (pkg !in HOMES) return false
            val c = cls.substringAfterLast('.')
            return c == "MainActivity" || c == "HomeActivity" || c == "LauncherActivity"
        }
    }
}
