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
        running = true
        note("connected")
        // Just after power-on the Google home shows first: replace it once.
        if (SystemClock.elapsedRealtime() < 3 * 60_000) showHome("boot")
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_HOME) return false
        suppressUntil = 0
        if (event.action == KeyEvent.ACTION_UP) showHome("home-key")
        return true
    }

    private var lastAssistant = 0L
    private var lastShown = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString() ?: ""
        val text = event.text?.joinToString(" ")?.take(60) ?: ""
        note("window $pkg/$cls '$text'")
        val now = SystemClock.elapsedRealtime()
        if (pkg in ASSISTANT) { lastAssistant = now; return }
        // Some TVs do not hand the HOME button to us: then the Google home screen appears.
        // Replace ONLY its main screen, and never right after Gemini/voice (it may be showing a movie page).
        if (isGoogleHomeScreen(pkg, cls, text) && now > suppressUntil && now - lastAssistant > 20_000 && now - lastShown > 1_500) { lastShown = now; showHome("google-home") }
    }

    override fun onInterrupt() {}

    override fun onDestroy() { running = false; super.onDestroy() }

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
        /** Ronin Home itself opened a Google TV page (poster fallback): leave it alone until then. */
        @Volatile var suppressUntil = 0L
        @Volatile var running = false
        val ASSISTANT = setOf("com.google.android.katniss", "com.google.android.apps.bard", "com.google.android.googlequicksearchbox")
        private val HOMES = setOf(Catalog.LAUNCHER_X, "com.google.android.tvlauncher")

        /** Main screen of the Google home only (not its movie / details / search pages). */
        /** Google TV's home screen itself: its window is titled "Home" (seen on the family TCL), or a main activity class. */
        fun isGoogleHomeScreen(pkg: String, cls: String, text: String = ""): Boolean {
            if (pkg !in HOMES) return false
            val c = cls.substringAfterLast('.')
            val t = text.trim().lowercase()
            // seen on the family TCL: "Home", "Main user home screen"
            return c == "MainActivity" || c == "HomeActivity" || c == "LauncherActivity" || t == "home" || t.endsWith("home screen")
        }
    }
}
