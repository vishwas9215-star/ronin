package com.ronin.home

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import com.ronin.home.Ui.WRAP
import com.ronin.home.Ui.dp

/**
 * TMDB key for Movies & Shows. Can also be set from the laptop:
 * adb shell am start -n com.ronin.home/.SetupActivity --es tmdb_key KEY
 */
class SetupActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = Store(this)
        intent?.getStringExtra("tmdb_key")?.let { if (Tmdb.looksValid(it)) store.tmdbKey = it }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(96), dp(54), dp(96), dp(40))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Ui.BG_TOP, Ui.BG_BOTTOM))
            clipChildren = false
        }
        col.addView(Ui.text(this, "Movies & Shows setup", 32f, Ui.TEXT, Typeface.NORMAL, "sans-serif-light"))
        val status = Ui.text(this, "", 16f, Ui.SUB)
        fun paint() {
            status.text = when {
                store.tmdbKey.isEmpty() -> "No TMDB key yet. Movies & Shows rows are hidden."
                store.lastError.isNotEmpty() -> "Key saved, but TMDB said: ${store.lastError}"
                else -> "Key saved. Top 10, New Releases and Coming Soon refresh every 6 hours."
            }
        }
        paint()
        col.addView(status, Ui.lp(WRAP, WRAP, t = dp(12), b = dp(22)))
        val edit = EditText(this).apply {
            hint = "TMDB API key or Read Access Token"
            setHintTextColor(0x88FFFFFF.toInt()); setTextColor(Ui.TEXT); textSize = 18f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = true
        }
        col.addView(edit, Ui.lp(dp(640), WRAP))
        col.addView(Ui.button(this, "Save key") {
            val k = edit.text.toString().trim()
            if (Tmdb.looksValid(k)) { store.tmdbKey = k; store.lastError = ""; edit.setText("") } else status.text = "That does not look like a TMDB key."
            if (Tmdb.looksValid(k)) paint()
        }, Ui.lp(WRAP, WRAP, t = dp(18)))
        setContentView(col)
    }
}
