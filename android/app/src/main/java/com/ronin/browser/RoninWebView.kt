package com.ronin.browser

import android.annotation.SuppressLint
import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView

/**
 * A WebView that can keep playing while the app is in the background: when
 * keepPlaying is on, the engine is never told that the window went away.
 */
@SuppressLint("ViewConstructor")
class RoninWebView(context: Context) : WebView(context) {
    @Volatile var keepPlaying = false
    /** When his finger last touched this page (Claude's Paste button may read the clipboard only right after a tap). */
    @Volatile var lastTouchAt = 0L

    override fun onWindowVisibilityChanged(visibility: Int) {
        if (keepPlaying && visibility != View.VISIBLE) {
            super.onWindowVisibilityChanged(View.VISIBLE)
        } else {
            super.onWindowVisibilityChanged(visibility)
        }
    }

    // When the app goes to the background Android also hides the view itself;
    // the engine then holds back the next song until the app is opened again.
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        if (keepPlaying && visibility != View.VISIBLE) {
            super.onVisibilityChanged(changedView, View.VISIBLE)
        } else {
            super.onVisibilityChanged(changedView, visibility)
        }
    }

    // ---- pull down at the top of the page to reload (one finger, no button to find) ----
    /** armed = far enough to reload when the finger lifts; done = the finger lifted while armed. */
    var onPull: ((armed: Boolean, done: Boolean) -> Unit)? = null
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var startedAtTop = false
    private var overTop = false
    private var armed = false
    private var fingerDown = false

    private fun farEnough() = lastY - downY > resources.displayMetrics.density * 110 && lastY - downY > 2 * Math.abs(lastX - downX)

    // the page itself (not a box inside it) is at its top and is pulled further down
    override fun onOverScrolled(scrollX: Int, scrollY: Int, clampedX: Boolean, clampedY: Boolean) {
        if (clampedY && scrollY <= 0) {
            overTop = true
            if (fingerDown && startedAtTop && onPull != null) setArmed(farEnough())
        }
        super.onOverScrolled(scrollX, scrollY, clampedX, clampedY)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_UP) lastTouchAt = android.os.SystemClock.uptimeMillis()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; lastX = e.x; lastY = e.y; fingerDown = true; startedAtTop = scrollY <= 0; overTop = false; setArmed(false) }
            MotionEvent.ACTION_POINTER_DOWN -> { startedAtTop = false; setArmed(false) } // two fingers = zoom, never reload
            MotionEvent.ACTION_MOVE -> { lastX = e.x; lastY = e.y; if (startedAtTop && overTop && onPull != null) setArmed(farEnough()) }
            MotionEvent.ACTION_UP -> {
                fingerDown = false; lastX = e.x; lastY = e.y
                val go = armed
                setArmed(false)
                if (go) onPull?.invoke(false, true)
                // the engine can report "pulled past the top" a moment after the finger lifts
                else if (startedAtTop && onPull != null && farEnough()) postDelayed({ if (overTop && !fingerDown) onPull?.invoke(false, true) }, 150)
            }
            MotionEvent.ACTION_CANCEL -> { fingerDown = false; setArmed(false) }
        }
        return super.onTouchEvent(e)
    }

    private fun setArmed(a: Boolean) {
        if (a == armed) return
        armed = a
        if (a) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        onPull?.invoke(a, false)
    }
}
