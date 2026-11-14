package com.ronin.browser

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RemoteViews

/** Ronin search on the home screen: the bar opens a search box, the round button listens. */
class SearchWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) mgr.updateAppWidget(id, views(ctx))
    }

    companion object {
        fun views(ctx: Context): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_search)
            fun open(voice: Boolean, code: Int) = PendingIntent.getActivity(ctx, code,
                Intent(ctx, SearchActivity::class.java).putExtra("voice", voice).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            v.setOnClickPendingIntent(R.id.widget_bar, open(false, 1))
            v.setOnClickPendingIntent(R.id.widget_text, open(false, 1))
            v.setOnClickPendingIntent(R.id.widget_mic, open(true, 2))
            return v
        }

        /** Menu -> "put the search bar on the home screen" (Android asks once, then it is there). */
        fun pin(ctx: Context): Boolean {
            val mgr = AppWidgetManager.getInstance(ctx)
            if (android.os.Build.VERSION.SDK_INT < 26 || !mgr.isRequestPinAppWidgetSupported) return false
            return mgr.requestPinAppWidget(ComponentName(ctx, SearchWidget::class.java), null, null)
        }
    }
}

/** The search box from the widget: the keyboard is up at once; Enter (or speech) opens Ronin. */
class SearchActivity : Activity() {
    private lateinit var box: EditText

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        window.setGravity(Gravity.TOP)
        val d = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((12 * d).toInt(), (8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt())
            background = GradientDrawable().apply { setColor(Color.parseColor("#1C2029")); cornerRadius = 28 * d }
        }
        box = EditText(this).apply {
            hint = "Ronin में खोजें या पता लिखें"
            setHintTextColor(Color.parseColor("#8A93A6"))
            setTextColor(Color.WHITE)
            textSize = 18f
            setSingleLine(true)
            background = null
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, id, ev ->
                if (id == EditorInfo.IME_ACTION_SEARCH || id == EditorInfo.IME_ACTION_GO || ev?.keyCode == KeyEvent.KEYCODE_ENTER) { go(text.toString()); true } else false
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val mic = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            contentDescription = "बोलकर खोजें"
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#2563EB")) }
            layoutParams = LinearLayout.LayoutParams((48 * d).toInt(), (48 * d).toInt())
            setOnClickListener { listen() }
        }
        row.addView(box); row.addView(mic)
        val outer = LinearLayout(this).apply { setPadding((10 * d).toInt(), (24 * d).toInt(), (10 * d).toInt(), 0); addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)) }
        setContentView(outer)
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        if (intent.getBooleanExtra("voice", false)) listen()
        else box.post { box.requestFocus(); (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(box, InputMethodManager.SHOW_IMPLICIT) }
    }

    private fun listen() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "बोलिए, क्या खोजना है")
        try { startActivityForResult(i, 7) } catch (e: Exception) { box.requestFocus() }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req != 7) return
        val said = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (res == RESULT_OK && !said.isNullOrBlank()) go(said) else box.requestFocus()
    }

    private fun go(text: String) {
        val url = Omnibox.resolve(text) ?: return
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url), this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}
