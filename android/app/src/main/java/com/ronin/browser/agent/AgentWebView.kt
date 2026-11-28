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
    // Owner, 28 Nov (EPDS "IFSC Search", an ASP.NET UpdatePanel): with Ronin behind another app or the phone locked, the
    // page's AJAX never finished. Android lowers a hidden WebView's page engine (its renderer process can be frozen,
    // its timers throttled). The Claude tab keeps its page engine at full priority and always "visible" to the page,
    // so JavaScript timers and network requests go on in the background (the foreground service keeps the app alive).
    init {
        if (android.os.Build.VERSION.SDK_INT >= 26) setRendererPriorityPolicy(RENDERER_PRIORITY_IMPORTANT, false)
    }
    // Only while Claude works (10 min after his last command): an idle page may sleep again as Android wants
    // (battery; pages pause their own media). Reviewed by AI Fiesta (Qwen, Gemini, DeepSeek), 28 Nov.
    @Volatile var keepAwakeUntil = 0L
    @Volatile private var realWindowVisibility = android.view.View.VISIBLE
    private fun awake() = SystemClock.uptimeMillis() < keepAwakeUntil
    override fun onWindowVisibilityChanged(visibility: Int) {
        realWindowVisibility = visibility
        super.onWindowVisibilityChanged(if (awake()) android.view.View.VISIBLE else visibility)
    }
    override fun onVisibilityChanged(changedView: android.view.View, visibility: Int) {
        super.onVisibilityChanged(changedView, if (awake()) android.view.View.VISIBLE else visibility)
    }
    /** Claude sent a command: keep the page engine awake for 10 minutes, waking it now if Ronin is hidden. Main thread. */
    fun keepAwake() {
        val was = awake()
        keepAwakeUntil = SystemClock.uptimeMillis() + 10 * 60_000
        if (!was && realWindowVisibility != android.view.View.VISIBLE) super.onWindowVisibilityChanged(android.view.View.VISIBLE)
        onResume(); resumeTimers() // pauseTimers() is process-wide: never let anything have left the page asleep
    }

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
