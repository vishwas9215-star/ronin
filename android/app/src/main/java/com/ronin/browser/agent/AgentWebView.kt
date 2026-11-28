package com.ronin.browser.agent

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.ronin.browser.RoninWebView

/**
 * The Claude tab's page view. It remembers what the engine told the keyboard about the focused box
 * (EditorInfo.inputType: password or not, outside the page's JavaScript) and tells Claude's own
 * touches apart from the owner's.
 */
@SuppressLint("ViewConstructor")
class AgentWebView(context: Context) : RoninWebView(context) {
    @Volatile var lastInputType = 0
    @Volatile var lastInputAt = 0L
    @Volatile var connection: InputConnection? = null
    /** set while Claude's own MotionEvents are being sent: any other touch is the owner's */
    @Volatile var claudeTouching = false
    var onOwnerTouch: (() -> Unit)? = null
    /** the owner's finger is on the page right now (Claude's tap / typing waits for it to lift) */
    @Volatile var ownerFingerDown = false; @Volatile var ownerFingerAt = 0L

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val ic = super.onCreateInputConnection(outAttrs)
        lastInputType = outAttrs.inputType
        lastInputAt = SystemClock.uptimeMillis()
        connection = ic
        return ic
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!claudeTouching) when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { ownerFingerDown = true; ownerFingerAt = SystemClock.uptimeMillis(); onOwnerTouch?.invoke() }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { ownerFingerDown = false; ownerFingerAt = SystemClock.uptimeMillis() }
        }
        return super.onTouchEvent(e)
    }
}
