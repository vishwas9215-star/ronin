package com.ronin.home

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import com.ronin.home.Ui.MATCH
import com.ronin.home.Ui.WRAP
import com.ronin.home.Ui.dp

/** Kids screen (opened from the "Kids" button, top right): kids apps only, big tiles, no posters. */
class KidsActivity : Activity() {
    private var demo = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        demo = intent?.getBooleanExtra("demo", false) == true
        val store = Store(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(40), dp(48), dp(40))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Ui.BG_TOP, Ui.BG_BOTTOM))
            clipChildren = false
        }
        col.addView(Ui.text(this, "Kids", 34f, Ui.TEXT, Typeface.NORMAL, "sans-serif-light"))
        col.addView(Ui.text(this, "Choose the Kids profile inside each app. Press Back to return.", 16f, Ui.SUB), Ui.lp(WRAP, WRAP, t = dp(8), b = dp(28)))
        val grid = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false; setPadding(dp(6), dp(12), dp(6), dp(12)) }
        val apps = Catalog.visible(Catalog.kidsApps, store.subscribed()) { demo || launch(it) != null }
        apps.forEachIndexed { i, e -> grid.addView(tile(e), Ui.lp(dp(200), dp(112), l = if (i > 0) dp(16) else 0)) }
        col.addView(grid, Ui.lp(WRAP, WRAP, l = -dp(6)))
        setContentView(col)
        grid.post { grid.getChildAt(0)?.requestFocus() }
    }

    private fun launch(pkg: String): Intent? = packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: packageManager.getLaunchIntentForPackage(pkg)

    private fun tile(e: AppEntry): View {
        val w = dp(200); val h = dp(112)
        val f = FrameLayout(this)
        f.setBackgroundColor(Ui.CARD)
        f.addView(ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_XY; setImageBitmap(banner(e, w, h)) }, FrameLayout.LayoutParams(MATCH, MATCH))
        f.addView(View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xCC000000.toInt(), 0)) }, Ui.flp(MATCH, dp(44), Gravity.BOTTOM))
        f.addView(Ui.text(this, e.label, 13f, Color.WHITE, Typeface.NORMAL, "sans-serif-medium"), Ui.flp(WRAP, WRAP, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(10), 0, 0, dp(8)) })
        Ui.round(f, 10)
        Ui.focusable(f, 10)
        f.contentDescription = e.label
        f.setOnClickListener {
            if (demo) { Toast.makeText(this, "Opens ${e.label}", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            val i = launch(e.pkg) ?: return@setOnClickListener
            if (e.extraKey != null) i.putExtra("ronin.profile", e.extraKey)
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        return f
    }

    private fun banner(e: AppEntry, w: Int, h: Int): Bitmap {
        if (demo) return Ui.placeholder(w, h, e.label.removeSuffix(" Kids"), Service.forPackage(e.pkg)?.color ?: Demo.color(e.pkg))
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val c = Canvas(b)
        var d: Drawable? = try { packageManager.getApplicationBanner(e.pkg) } catch (x: PackageManager.NameNotFoundException) { null }
        if (d == null) d = launch(e.pkg)?.let { packageManager.resolveActivity(it, 0)?.activityInfo?.loadBanner(packageManager) }
        if (d == null) d = try { packageManager.getApplicationIcon(e.pkg) } catch (x: Exception) { null }
        c.drawColor(Ui.CARD); d?.setBounds(0, 0, w, h); d?.draw(c)
        return b
    }
}
