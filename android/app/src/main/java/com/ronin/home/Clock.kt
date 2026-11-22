package com.ronin.home

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextClock
import com.ronin.home.Ui.dp

/** Big calm clock on black; moves a little every minute so the TV panel never burns in. */
class ClockView(ctx: Context) : FrameLayout(ctx) {
    private val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
    private val h = Handler(Looper.getMainLooper())
    private val move = object : Runnable {
        override fun run() {
            val dx = width - box.width; val dy = height - box.height
            if (dx > 0 && dy > 0) {
                box.animate().x((dx * (0.2 + Math.random() * 0.6)).toFloat()).y((dy * (0.2 + Math.random() * 0.6)).toFloat()).setDuration(2500).start()
            }
            h.postDelayed(this, 60_000)
        }
    }

    init {
        // near-black with a faint centre lift, so it does not look switched off
        background = android.graphics.drawable.GradientDrawable().apply {
            gradientType = android.graphics.drawable.GradientDrawable.RADIAL_GRADIENT; gradientRadius = ctx.dp(700).toFloat()
            colors = intArrayOf(0xFF0A0E1A.toInt(), Color.BLACK)
        }
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        box.addView(row)
        row.addView(TextClock(ctx).apply {
            format12Hour = "h:mm"; format24Hour = "HH:mm"; textSize = 150f
            isSingleLine = true; gravity = Gravity.END or Gravity.BOTTOM
            setTextColor(0xFFE6E9EF.toInt()); typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL); includeFontPadding = true
        }, LinearLayout.LayoutParams(ctx.dp(440), -2))   // fixed width: the time never gets clipped as digits change
        row.addView(TextClock(ctx).apply {
            format12Hour = "a"; format24Hour = ""; textSize = 24f; setTextColor(0xFF6B7280.toInt())
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = ctx.dp(10); bottomMargin = ctx.dp(34) })
        box.addView(TextClock(ctx).apply {
            format12Hour = "EEEE, d MMMM"; format24Hour = "EEEE, d MMMM"; textSize = 26f
            setTextColor(0xFF9AA3B2.toInt()); typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ctx.dp(8) })
        addView(box, LayoutParams(-2, -2, Gravity.CENTER))
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); h.postDelayed(move, 60_000) }
    override fun onDetachedFromWindow() { h.removeCallbacks(move); super.onDetachedFromWindow() }
}

/** The screensaver ("Ronin Clock"). */
class ClockDream : DreamService() {
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false; isFullscreen = true; isScreenBright = false
        setContentView(ClockView(this))
    }
}

/** Shows the screensaver on demand (for checking it on the emulator). */
class ClockPreviewActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContentView(ClockView(this)) }
}
