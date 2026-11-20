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
    private var startedAtTop = false
    private var overTop = false
    private var armed = false

    // the page itself (not a box inside it) is at its top and is pulled further down
    override fun onOverScrolled(scrollX: Int, scrollY: Int, clampedX: Boolean, clampedY: Boolean) {
        if (clampedY && scrollY <= 0) overTop = true
        super.onOverScrolled(scrollX, scrollY, clampedX, clampedY)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; startedAtTop = scrollY <= 0; overTop = false; setArmed(false) }
            MotionEvent.ACTION_POINTER_DOWN -> { startedAtTop = false; setArmed(false) } // two fingers = zoom, never reload
            MotionEvent.ACTION_MOVE -> if (startedAtTop && overTop && onPull != null) {
                val dy = e.y - downY
                setArmed(dy > resources.displayMetrics.density * 110 && dy > 2 * Math.abs(e.x - downX))
            }
            MotionEvent.ACTION_UP -> { val go = armed; setArmed(false); if (go) onPull?.invoke(false, true) }
            MotionEvent.ACTION_CANCEL -> setArmed(false)
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
