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
import android.text.format.DateFormat
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import com.ronin.home.Ui.MATCH
import com.ronin.home.Ui.WRAP
import com.ronin.home.Ui.dp
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var store: Store
    private lateinit var scroll: ScrollView
    private lateinit var column: LinearLayout
    private var demo = false
    private var built = ""
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        demo = intent?.getBooleanExtra("demo", false) == true
        val root = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Ui.BG_TOP, Ui.BG_BOTTOM))
        }
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // TV safe area (overscan): 48 dp sides, 27 dp top
            setPadding(dp(48), dp(27), dp(48), dp(40))
            clipChildren = false; clipToPadding = false
        }
        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false; isFillViewport = true; clipChildren = false
            isSmoothScrollingEnabled = true
            addView(column, FrameLayout.LayoutParams(MATCH, WRAP))
        }
        root.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.getBooleanExtra("demo", false) == true) { demo = true; built = "" }
        // Home pressed while already home: back to the top, first tile.
        scroll.smoothScrollTo(0, 0)
        column.findViewWithTag<View>("first")?.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        val shelf = currentShelf()
        val sig = listOf(store.subscribed().sortedBy { it.name }, shelf.hashCode(), demo, installedSig(), DateFormat.is24HourFormat(this)).toString()
        if (sig != built) { build(shelf); built = sig }
        refreshMovies()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // We are Home: Back has nowhere to go.
        if (keyCode == KeyEvent.KEYCODE_BACK) { scroll.smoothScrollTo(0, 0); column.findViewWithTag<View>("first")?.requestFocus(); return true }
        return super.onKeyDown(keyCode, event)
    }

    // ---------- data ----------

    private fun installed(pkg: String) = demo || launchIntent(pkg) != null

    private fun installedSig() = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0).size

    private fun currentShelf(): Shelf = when {
        demo -> Demo.shelf
        else -> (store.cachedShelf() ?: Shelf.EMPTY)
    }.forServices(store.subscribed())

    private fun refreshMovies() {
        val key = store.tmdbKey
        if (demo || key.isEmpty() || store.shelfFresh() || loading) return
        loading = true
        thread(name = "tmdb") {
            try {
                store.saveShelf(Tmdb.fetch(key))
                runOnUiThread { loading = false; onResume() }
            } catch (e: Exception) {
                Log.w("RoninHome", "TMDB: $e")
                store.lastError = e.message ?: e.toString()
                runOnUiThread { loading = false }
            }
        }
    }

    private fun launchIntent(pkg: String): Intent? =
        packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: packageManager.getLaunchIntentForPackage(pkg)

    private fun open(pkg: String, profile: String? = null) {
        if (demo) { Toast.makeText(this, "Opens $pkg", Toast.LENGTH_SHORT).show(); return }
        val i = launchIntent(pkg)
        if (i == null) { Toast.makeText(this, "App is not installed", Toast.LENGTH_SHORT).show(); return }
        if (profile != null) i.putExtra("ronin.profile", profile)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { startActivity(i) } catch (e: Exception) { Toast.makeText(this, "Could not open", Toast.LENGTH_SHORT).show() }
    }

    private fun allTvApps(): List<Pair<String, String>> {
        if (demo) return Demo.otherApps
        val pm = packageManager
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }.distinctBy { it.first }
    }

    // ---------- screen ----------

    private fun build(shelf: Shelf) {
        column.removeAllViews()
        val subs = store.subscribed()
        column.addView(header())
        column.addView(hero())

        val mine = Catalog.visible(Catalog.myApps, subs, ::installed)
        row("My Apps", mine.map { appTile(it) }, first = true)
        row("Kids", Catalog.visible(Catalog.kidsApps, subs, ::installed).map { appTile(it) })

        if (!demo && store.tmdbKey.isEmpty()) {
            row("Movies & Shows", listOf(infoTile("Movies & Shows", "Add your free TMDB key to see Top 10 and new releases") {
                startActivity(Intent(this, SetupActivity::class.java))
            }))
        } else {
            // Movies & Shows are for the parents (horror allowed). The Kids row above has apps only, never posters.
            row("New Releases", shelf.newReleases.map { posterTile(it, 0) })
            for (l in Lang.values()) row(l.title, shelf.byLang[l].orEmpty().map { posterTile(it, 0) })
            row("Top 10 Movies", shelf.topMovies.mapIndexed { i, t -> posterTile(t, i + 1) })
            row("Top 10 Shows", shelf.topShows.mapIndexed { i, t -> posterTile(t, i + 1) })
            row("Coming Soon", shelf.comingSoon.map { posterTile(it, 0) })
        }
        row("All Apps", Catalog.others(allTvApps(), subs).map { appTile(it, small = true) })
        column.post { column.findViewWithTag<View>("first")?.requestFocus() }
    }

    private fun header(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(Ui.pill(context, "Family", 0x24FFFFFF, Ui.TEXT, 14f))
        addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        addView(Ui.text(context, "RONIN HOME", 12f, 0x66FFFFFF, Typeface.BOLD, "sans-serif-medium").apply { letterSpacing = 0.25f })
    }

    private fun hero(): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            layoutParams = Ui.lp(MATCH, dp(196), t = dp(2))
        }
        // Left: the clock the owner liked
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        val timeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        timeRow.addView(TextClock(this).apply {
            format12Hour = "h:mm"; format24Hour = "HH:mm"
            textSize = 104f; setTextColor(Ui.TEXT); typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
            includeFontPadding = false; letterSpacing = -0.02f
        })
        timeRow.addView(TextClock(this).apply {
            format12Hour = "a"; format24Hour = ""
            textSize = 26f; setTextColor(Ui.SUB); typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            includeFontPadding = false
        }, Ui.lp(WRAP, WRAP, l = dp(10), b = dp(16)))
        left.addView(timeRow)
        left.addView(TextClock(this).apply {
            format12Hour = "EEEE, d MMMM"; format24Hour = "EEEE, d MMMM"
            textSize = 22f; setTextColor(Ui.SUB); typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        }, Ui.lp(WRAP, WRAP, t = dp(2)))
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        buttons.addView(Ui.button(this, "Castle — New") { open(Service.CASTLE.pkg) })
        buttons.addView(Ui.button(this, "My subscriptions") { startActivity(Intent(this, SubscriptionsActivity::class.java)) },
            Ui.lp(WRAP, WRAP, l = dp(14)))
        wrap.addView(left, LinearLayout.LayoutParams(0, WRAP, 1f))
        // Right: the two buttons (calm; no promo card)
        wrap.addView(buttons, Ui.lp(WRAP, WRAP, t = dp(60)))
        return wrap
    }

    private fun row(title: String, tiles: List<View>, first: Boolean = false) {
        if (tiles.isEmpty()) return
        column.addView(Ui.text(this, title, 17f, 0xFFD5DCE8.toInt(), Typeface.BOLD, "sans-serif-medium"), Ui.lp(WRAP, WRAP, t = dp(22), b = dp(10)))
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false
            setPadding(dp(6), dp(8), dp(48), dp(10))
        }
        tiles.forEachIndexed { i, v ->
            if (first && i == 0) v.tag = "first"
            line.addView(v, (v.layoutParams as? LinearLayout.LayoutParams ?: Ui.lp(WRAP, WRAP)).apply { if (i > 0) leftMargin = dp(16) })
        }
        val hs = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false
            addView(line)
        }
        column.addView(hs, Ui.lp(MATCH, WRAP, l = -dp(6)))
    }

    private fun appTile(e: AppEntry, small: Boolean = false): View {
        val w = dp(if (small) 168 else 208); val h = dp(if (small) 94 else 117)
        val f = FrameLayout(this)
        f.layoutParams = Ui.lp(w, h)
        f.setBackgroundColor(Ui.CARD)
        val img = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_XY }
        img.setImageBitmap(banner(e, w, h))
        f.addView(img, FrameLayout.LayoutParams(MATCH, MATCH))
        if (e.kids) {
            f.addView(Ui.text(this, "KIDS", 11f, Color.BLACK, Typeface.BOLD, "sans-serif-medium").apply {
                letterSpacing = 0.1f
                setPadding(dp(8), dp(3), dp(8), dp(3))
                background = GradientDrawable().apply { cornerRadius = dp(4).toFloat(); setColor(0xFFFFD36B.toInt()) }
            }, Ui.flp(WRAP, WRAP, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(8), dp(8), 0) })
            f.addView(Ui.text(this, e.label, 13f, Color.WHITE, Typeface.BOLD, "sans-serif-medium").apply {
                setPadding(dp(10), dp(14), dp(10), dp(7))
                background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xE0000000.toInt(), 0x00000000))
            }, Ui.flp(MATCH, WRAP, Gravity.BOTTOM))
        }
        Ui.round(f, 12)
        Ui.focusable(f, 12)
        f.contentDescription = e.label
        f.setOnClickListener { open(e.pkg, e.extraKey) }
        return f
    }

    /** The app's own TV banner (what the Google home shows); else its icon on a dark card. */
    private fun banner(e: AppEntry, w: Int, h: Int): Bitmap {
        val svc = Service.forPackage(e.pkg)
        if (demo) return Ui.placeholder(w, h, e.label.removeSuffix(" Kids"), svc?.color ?: Demo.color(e.pkg))
        val pm = packageManager
        var d: Drawable? = try { pm.getApplicationBanner(e.pkg) } catch (x: PackageManager.NameNotFoundException) { null }
        if (d == null) d = launchIntent(e.pkg)?.let { i -> pm.resolveActivity(i, 0)?.activityInfo?.loadBanner(pm) }
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        if (d != null) { d.setBounds(0, 0, w, h); d.draw(c); return b }
        c.drawColor(Ui.CARD)
        val icon = try { pm.getApplicationIcon(e.pkg) } catch (x: Exception) { null }
        val s = (h * 0.5).toInt()
        icon?.setBounds((w - s) / 2, (h - s) / 2 - dp(8), (w + s) / 2, (h + s) / 2 - dp(8)); icon?.draw(c)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = h / 8.5f; textAlign = android.graphics.Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        c.drawText(e.label, w / 2f, h - h / 9f, p)
        return b
    }

    private fun posterTile(t: Title, rank: Int): View {
        val w = dp(132); val h = dp(198)
        val f = FrameLayout(this)
        f.layoutParams = Ui.lp(w, h)
        f.setBackgroundColor(Ui.CARD)
        val img = Ui.imageView(this)
        f.addView(img, FrameLayout.LayoutParams(MATCH, MATCH))
        if (t.poster != null && !demo) Images.load(this, Tmdb.IMG + "w342" + t.poster, img)
        else img.setImageBitmap(Ui.placeholder(w, h, t.name, Demo.color(t.name)))
        f.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xB0000000.toInt(), 0x00000000))
        }, Ui.flp(MATCH, dp(56), Gravity.BOTTOM))
        f.addView(Ui.badge(this, t.service), Ui.flp(WRAP, WRAP, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(8), 0, 0, dp(8)) })
        if (rank > 0) {
            f.addView(Ui.text(this, rank.toString(), 28f, Color.WHITE, Typeface.BOLD, "sans-serif-black").apply {
                setShadowLayer(dp(6).toFloat(), 0f, 0f, Color.BLACK)
            }, Ui.flp(WRAP, WRAP, Gravity.TOP or Gravity.START).apply { setMargins(dp(8), dp(4), 0, 0) })
        }
        Ui.round(f, 10)
        Ui.focusable(f, 10)
        f.contentDescription = t.name
        f.setOnClickListener { open(t.service.pkg) }
        return f
    }

    private fun infoTile(title: String, sub: String, onClick: () -> Unit): View {
        val f = FrameLayout(this)
        f.layoutParams = Ui.lp(dp(430), dp(117))
        f.setBackgroundColor(Ui.CARD)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(16), dp(18), dp(16)) }
        col.addView(Ui.text(this, title, 18f, Ui.TEXT, Typeface.BOLD, "sans-serif-medium"))
        col.addView(Ui.text(this, sub, 14f, Ui.SUB).apply { maxLines = 2 }, Ui.lp(WRAP, WRAP, t = dp(8)))
        f.addView(col)
        Ui.round(f, 12)
        Ui.focusable(f, 12, 1.04f)
        f.setOnClickListener { onClick() }
        return f
    }
}
