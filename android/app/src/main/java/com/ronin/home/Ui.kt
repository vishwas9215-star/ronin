package com.ronin.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** One look for everything: dark glass, white focus ring, gentle zoom. */
object Ui {
    const val BG_TOP = 0xFF0A0E16.toInt()
    const val BG_BOTTOM = 0xFF111A2B.toInt()
    const val TEXT = 0xFFF2F5FA.toInt()
    const val SUB = 0xFFA9B3C6.toInt()
    const val CARD = 0xFF1A2333.toInt()

    fun Context.dp(v: Number): Int = (v.toFloat() * resources.displayMetrics.density + 0.5f).toInt()

    fun text(ctx: Context, s: CharSequence, sp: Float, color: Int = TEXT, style: Int = Typeface.NORMAL, family: String = "sans-serif") =
        TextView(ctx).apply {
            text = s; textSize = sp; setTextColor(color); typeface = Typeface.create(family, style)
            includeFontPadding = false
        }

    fun round(v: View, radiusDp: Int) {
        val r = v.context.dp(radiusDp).toFloat()
        v.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, o: Outline) { o.setRoundRect(0, 0, view.width, view.height, r) }
        }
        v.clipToOutline = true
    }

    fun ring(ctx: Context, radiusDp: Int): Drawable = GradientDrawable().apply {
        cornerRadius = ctx.dp(radiusDp).toFloat(); setStroke(ctx.dp(3), Color.WHITE); setColor(Color.TRANSPARENT)
    }

    /** Focus: zoom 1.08, white ring, lift. Same for every tile and button. */
    fun focusable(v: View, radiusDp: Int, zoom: Float = 1.08f) {
        v.isFocusable = true
        v.isClickable = true
        val ringD = ring(v.context, radiusDp)
        v.setOnFocusChangeListener { view, has ->
            view.animate().scaleX(if (has) zoom else 1f).scaleY(if (has) zoom else 1f).translationZ(if (has) view.context.dp(12).toFloat() else 0f)
                .setDuration(140).start()
            if (view is FrameLayout) view.foreground = if (has) ringD else null
        }
    }

    fun pill(ctx: Context, label: String, bg: Int = 0x33FFFFFF, fg: Int = TEXT, sp: Float = 15f): TextView =
        text(ctx, label, sp, fg, Typeface.BOLD, "sans-serif-medium").apply {
            gravity = Gravity.CENTER
            setPadding(ctx.dp(18), ctx.dp(9), ctx.dp(18), ctx.dp(9))
            background = GradientDrawable().apply { cornerRadius = ctx.dp(40).toFloat(); setColor(bg) }
        }

    /** A button that looks like a pill and focuses like a tile. */
    fun button(ctx: Context, label: String, onClick: () -> Unit): FrameLayout = FrameLayout(ctx).apply {
        addView(pill(ctx, label, 0x2EFFFFFF, TEXT, 15f))
        round(this, 40)
        focusable(this, 40, 1.06f)
        setOnClickListener { onClick() }
    }

    fun badge(ctx: Context, s: Service): TextView = text(ctx, s.badge, 10f, Color.WHITE, Typeface.BOLD, "sans-serif-medium").apply {
        letterSpacing = 0.06f
        setPadding(ctx.dp(7), ctx.dp(3), ctx.dp(7), ctx.dp(3))
        background = GradientDrawable().apply { cornerRadius = ctx.dp(4).toFloat(); setColor(s.color) }
    }

    fun lp(w: Int, h: Int, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) =
        LinearLayout.LayoutParams(w, h).apply { setMargins(l, t, r, b) }

    fun flp(w: Int, h: Int, gravity: Int) = FrameLayout.LayoutParams(w, h, gravity)

    const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    /** Placeholder art (no picture yet / demo): soft gradient with the name. */
    fun placeholder(w: Int, h: Int, name: String, color: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), color, darken(color), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null; p.color = Color.WHITE; p.textAlign = Paint.Align.CENTER
        p.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        p.textSize = h / 7f
        val words = name.split(" ")
        val lines = mutableListOf<String>(); var cur = ""
        for (wd in words) { val t = if (cur.isEmpty()) wd else "$cur $wd"; if (p.measureText(t) > w * 0.85f && cur.isNotEmpty()) { lines += cur; cur = wd } else cur = t }
        if (cur.isNotEmpty()) lines += cur
        val lh = p.textSize * 1.2f
        var y = h / 2f - (lines.size - 1) * lh / 2 + p.textSize / 3
        for (l in lines) { c.drawText(l, w / 2f, y, p); y += lh }
        return b
    }

    fun darken(c: Int): Int = Color.rgb((Color.red(c) * 0.35).toInt(), (Color.green(c) * 0.35).toInt(), (Color.blue(c) * 0.35).toInt())

    fun imageView(ctx: Context) = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
}
