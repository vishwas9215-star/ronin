package com.ronin.browser

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Ronin's menus and choices, opened from the BOTTOM (where the thumb is), never a tall list on the right:
 * a row of big buttons for the everyday actions, then short labelled groups of 60 dp rows.
 * (Owner, 18 Nov 2026: the right-side panels "do not look good"; three AIs on AI Fiesta agreed on this shape.)
 */
class Sheet(private val ctx: Context, title: String? = null) {
    class Big(val icon: String, val label: String, val tag: String = "", val tint: String = "#2A3142", val click: () -> Unit)
    class Row(val icon: String, val label: String, val value: String = "", val tint: String = "#2A3142", val tag: String = "", val click: () -> Unit)

    private val dialog = Dialog(ctx)
    private val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(6), dp(16), dp(20)) }

    init {
        // the little handle that says "this slides up from the bottom"
        body.addView(View(ctx).apply {
            background = round("#4B5263", 2)
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(12) }
        })
        if (title != null) body.addView(TextView(ctx).apply {
            text = title; textSize = 18f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
            setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(4), 0, dp(4), dp(10))
        })
    }

    fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()
    private fun round(color: String, r: Int) = GradientDrawable().apply { setColor(Color.parseColor(color)); cornerRadius = dp(r).toFloat() }

    /** Everyday actions: big square buttons, 4 in a row (5 on a tablet). */
    fun bigs(items: List<Big>): Sheet {
        val cols = if (ctx.resources.configuration.smallestScreenWidthDp >= 600) 5 else 4
        val grid = GridLayout(ctx).apply { columnCount = cols }
        for (b in items) {
            val cell = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = round("#1E2330", 16)
                isClickable = true; isFocusable = true
                contentDescription = b.label
                if (b.tag.isNotEmpty()) tag = b.tag
                setOnClickListener { dialog.dismiss(); b.click() }
                layoutParams = GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply {
                    width = 0; height = dp(84); setMargins(dp(4), dp(4), dp(4), dp(4))
                }
            }
            cell.addView(TextView(ctx).apply {
                text = b.icon; textSize = 22f; gravity = Gravity.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor(b.tint)) }
                layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
            })
            cell.addView(TextView(ctx).apply {
                text = b.label; textSize = 12.5f; setTextColor(Color.parseColor("#D7DCE6")); gravity = Gravity.CENTER
                maxLines = 2; setPadding(dp(2), dp(4), dp(2), 0)
            })
            grid.addView(cell)
        }
        body.addView(grid)
        return this
    }

    /** A labelled group of rows (icon in a coloured circle, name, and the current value or ›). */
    fun group(name: String, rows: List<Row>): Sheet {
        if (rows.isEmpty()) return this
        if (name.isNotEmpty()) body.addView(TextView(ctx).apply {
            text = name.uppercase(); textSize = 12.5f; letterSpacing = 0.06f
            setTextColor(Color.parseColor("#8B93A5")); setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(6), dp(18), dp(6), dp(6))
        })
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; background = round("#1E2330", 16) }
        if (name.isEmpty()) box.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
        rows.forEachIndexed { i, r ->
            if (i > 0) box.addView(View(ctx).apply {
                setBackgroundColor(Color.parseColor("#2A3040"))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { marginStart = dp(64) }
            })
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(60)
                setPadding(dp(12), dp(8), dp(14), dp(8))
                isClickable = true; isFocusable = true
                contentDescription = r.label
                if (r.tag.isNotEmpty()) tag = r.tag
                setOnClickListener { dialog.dismiss(); r.click() }
            }
            row.addView(TextView(ctx).apply {
                text = r.icon; textSize = 18f; gravity = Gravity.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor(r.tint)) }
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) }
            })
            row.addView(TextView(ctx).apply {
                text = r.label; textSize = 16f; setTextColor(Color.parseColor("#F1F3F7"))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(ctx).apply {
                val on = r.value == "ON" || r.value == "चालू" || r.value == "✔"
                text = r.value.ifEmpty { "›" }
                textSize = if (r.value.isEmpty()) 22f else 14f
                setTextColor(Color.parseColor(if (on) "#4ADE80" else "#8B93A5"))
                if (r.value.isNotEmpty()) setTypeface(typeface, Typeface.BOLD)
            })
            box.addView(row)
        }
        body.addView(box)
        return this
    }

    fun note(text: String): Sheet {
        body.addView(TextView(ctx).apply { this.text = text; textSize = 13f; setTextColor(Color.parseColor("#8B93A5")); setPadding(dp(6), dp(12), dp(6), 0) })
        return this
    }

    fun show(): Dialog {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val sheet = FrameLayout(ctx).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#151922"))
                val r = dp(24).toFloat(); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            addView(ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; addView(body) })
        }
        dialog.setContentView(sheet)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            val wide = ctx.resources.configuration.smallestScreenWidthDp >= 600
            // a tablet gets a centred sheet, not one stretched over the whole width
            setLayout(if (wide) dp(560) else WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            attributes = attributes.apply { dimAmount = 0.55f; windowAnimations = android.R.style.Animation_InputMethod }
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            // never taller than 85 % of the screen: the rest scrolls
            val max = (ctx.resources.displayMetrics.heightPixels * 0.85).toInt()
            sheet.post { if (sheet.height > max) setLayout(if (wide) dp(560) else WindowManager.LayoutParams.MATCH_PARENT, max) }
        }
        dialog.show()
        return dialog
    }
}
