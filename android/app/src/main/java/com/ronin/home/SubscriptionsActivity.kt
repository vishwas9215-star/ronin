package com.ronin.home

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.ronin.home.Ui.WRAP
import com.ronin.home.Ui.dp

/** "My subscriptions": switch a service off and its tiles leave Home, Kids and Movies & Shows. */
class SubscriptionsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = Store(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(96), dp(54), dp(96), dp(40))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Ui.BG_TOP, Ui.BG_BOTTOM))
            clipChildren = false
        }
        col.addView(Ui.text(this, "My subscriptions", 32f, Ui.TEXT, Typeface.NORMAL, "sans-serif-light"))
        col.addView(Ui.text(this, "Turn off a service you no longer pay for. Its app and its movies disappear from Home and Kids.", 16f, Ui.SUB),
            Ui.lp(WRAP, WRAP, t = dp(10), b = dp(26)))
        var first = true
        for (s in Service.values()) {
            val row = FrameLayout(this)
            val inner = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(22), 0, dp(22), 0)
                background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(0x22FFFFFF) }
            }
            inner.addView(Ui.brandBadge(this, s))
            inner.addView(Ui.text(this, s.label, 20f, Ui.TEXT, Typeface.NORMAL, "sans-serif-medium"), LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(16) })
            val state = Ui.pill(this, "", 0x33FFFFFF, Ui.TEXT, 14f)
            inner.addView(state)
            row.addView(inner, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
            fun paint() {
                val on = s in store.subscribed()
                state.text = if (s.alwaysOn) "Always on" else if (on) "ON" else "OFF"
                (state.background as GradientDrawable).setColor(if (s.alwaysOn) 0x33FFFFFF else if (on) 0xFF2F9E44.toInt() else 0x55FF6B6B)
                row.contentDescription = s.label + " " + state.text
            }
            paint()
            Ui.round(row, 12)
            Ui.focusable(row, 12, 1.03f)
            row.setOnClickListener { store.setSubscribed(s, s !in store.subscribed()); paint() }
            col.addView(row, Ui.lp(dp(560), dp(64), b = dp(14)))
            if (first) { row.post { row.requestFocus() }; first = false }
        }
        col.addView(Ui.text(this, "Press Back when done.", 14f, Ui.SUB), Ui.lp(WRAP, WRAP, t = dp(10)))
        setContentView(col)
    }
}
