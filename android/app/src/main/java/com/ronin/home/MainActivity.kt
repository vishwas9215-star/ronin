package com.ronin.home

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
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
import java.util.WeakHashMap
import kotlin.concurrent.thread

/**
 * Layout v3 (5-AI review): a FIXED top area (clock, or the focused title's info over a soft blurred backdrop)
 * and the rows scrolling underneath; the focused row always settles at the same height.
 */
class MainActivity : Activity() {

    private lateinit var store: Store
    private lateinit var scroll: ScrollView
    private lateinit var column: LinearLayout
    private lateinit var clockBlock: View
    private lateinit var infoBlock: LinearLayout
    private lateinit var infoTitle: TextView
    private lateinit var infoMeta: TextView
    private lateinit var infoText: TextView
    private lateinit var backdrop: ImageView
    private lateinit var backdropScrim: View
    private val titles = WeakHashMap<View, Title>()
    private val main = Handler(Looper.getMainLooper())
    private var pendingInfo: Runnable? = null
    private var demo = false
    private var built = ""

    // ---- auto-fit (owner): at least 10 posters and 6 apps across the screen, whatever the TV size ----
    private val contentDp by lazy { resources.configuration.screenWidthDp - 96 - 6 }
    private val posterGap = 10
    private val posterW by lazy { (contentDp - 9 * posterGap) / 10 }            // 10 posters per screen
    private val posterH by lazy { posterW * 3 / 2 }
    private val appGap = 12
    private val appW by lazy { (contentDp - 5 * appGap) / 6 }                    // 6 apps per screen
    private val appH by lazy { appW * 9 / 16 }
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        demo = intent?.getBooleanExtra("demo", false) == true
        val root = FrameLayout(this).apply { setBackgroundColor(Ui.BG_TOP) }
        // faint glow, top-left
        root.addView(View(this).apply {
            background = GradientDrawable().apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT; gradientRadius = dp(700).toFloat()
                setGradientCenter(0.1f, 0f); colors = intArrayOf(Ui.GLOW, Ui.BG_TOP)
            }
        }, FrameLayout.LayoutParams(MATCH, MATCH))
        // backdrop of the focused title (top-right): one box = picture + left and bottom fades, so no hard edges
        val bd = MaskedFrame(this).apply { alpha = 0f }
        backdrop = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            if (Build.VERSION.SDK_INT >= 31) setRenderEffect(RenderEffect.createBlurEffect(dp(10).toFloat(), dp(10).toFloat(), Shader.TileMode.DECAL))
        }
        bd.addView(backdrop, FrameLayout.LayoutParams(MATCH, MATCH))
        bd.addView(View(this).apply { setBackgroundColor(0x330B0F17) }, FrameLayout.LayoutParams(MATCH, MATCH))
        backdropScrim = bd
        root.addView(bd, Ui.flp(dp(660), dp(300), Gravity.TOP or Gravity.END))

        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        page.addView(header(), LinearLayout.LayoutParams(MATCH, WRAP))
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(2), dp(48), dp(300))
            clipChildren = false; clipToPadding = false
        }
        scroll = ScrollView(this).apply {
            // clip: rows must never slide over the fixed top area
            isVerticalScrollBarEnabled = false; clipChildren = true; clipToPadding = true; isSmoothScrollingEnabled = true
            addView(column, FrameLayout.LayoutParams(MATCH, WRAP))
        }
        page.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
        // hard clip (elevated, zoomed tiles otherwise escape the scroll area and cover the top area)
        scroll.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> v.clipBounds = android.graphics.Rect(0, 0, v.width, v.height) }
        root.addView(page, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xFF0B0F17.toInt(), 0x000B0F17))
            isFocusable = false
        }, Ui.flp(MATCH, dp(24), Gravity.BOTTOM))
        setContentView(root)

        root.viewTreeObserver.addOnGlobalFocusChangeListener { _, now -> onFocusMoved(now) }
        // TCL blocks the Home helper at power-on; once Ronin Home is open (process alive) re-connecting works.
        if (!HomeKeyService.running) BootReceiver.reconnectHelper(this)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.getBooleanExtra("demo", false) == true) { demo = true; built = "" }
        toTop()
    }

    private fun toTop() {
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
        // We are Home: Back just returns to the top.
        if (keyCode == KeyEvent.KEYCODE_BACK) { toTop(); return true }
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

    /** Poster → that movie/show itself (details page), see Links. */
    private fun openTitle(t: Title) {
        Toast.makeText(this, "Opening ${t.name}…", Toast.LENGTH_SHORT).show()
        thread(name = "link") {
            val link = Links.lookup(this, t)
            runOnUiThread { Log.i("RoninHome", "open '${t.name}' via " + Links.open(this, t, link)) }
        }
    }

    private fun allTvApps(): List<Pair<String, String>> {
        if (demo) return Demo.otherApps
        val pm = packageManager
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }.distinctBy { it.first }
    }

    // ---------- top area ----------

    private fun header(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(12), dp(48), dp(2))
            clipChildren = false
        }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false }
        bar.addView(Ui.pill(this, "Family", 0x1FFFFFFF, Ui.TEXT, 14f).apply { setPadding(dp(16), dp(8), dp(16), dp(8)) })
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        // quiet hint that the remote's voice button (Gemini) works everywhere
        bar.addView(ImageView(this).apply { setImageResource(R.drawable.ic_mic) }, Ui.lp(dp(20), dp(20), r = dp(4)))
        bar.addView(Ui.text(this, "Ask Gemini", 14f, 0xFFA7B0C0.toInt(), Typeface.NORMAL, "sans-serif-medium"), Ui.lp(WRAP, WRAP, r = dp(20)))
        bar.addView(Ui.button(this, "Kids") { startActivity(Intent(this, KidsActivity::class.java).putExtra("demo", demo)) })
        bar.addView(Ui.button(this, "Castle") { open(Service.CASTLE.pkg) }, Ui.lp(WRAP, WRAP, l = dp(12)))
        bar.addView(Ui.button(this, "My subscriptions") { startActivity(Intent(this, SubscriptionsActivity::class.java)) }, Ui.lp(WRAP, WRAP, l = dp(12)))
        box.addView(bar)

        val stage = FrameLayout(this)
        // clock (shown when an app tile or button has focus)
        val clock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val timeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        timeRow.addView(TextClock(this).apply {
            format12Hour = "h:mm"; format24Hour = "HH:mm"; textSize = 56f; setTextColor(Ui.TEXT)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL); includeFontPadding = true; letterSpacing = -0.02f
            translationX = -dp(3).toFloat()   // optical alignment with the 48 dp edge
        })
        timeRow.addView(TextClock(this).apply {
            format12Hour = "a"; format24Hour = ""; textSize = 16f; setTextColor(Ui.SUB)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL); includeFontPadding = false
        }, Ui.lp(WRAP, WRAP, l = dp(8), b = dp(10)))
        clock.addView(timeRow)
        clock.addView(TextClock(this).apply {
            format12Hour = "EEEE, d MMMM"; format24Hour = "EEEE, d MMMM"; textSize = 16f; setTextColor(Ui.SUB)
        }, Ui.lp(WRAP, WRAP, t = -dp(6)))
        clockBlock = clock
        stage.addView(clock, Ui.flp(WRAP, WRAP, Gravity.BOTTOM or Gravity.START))
        // focused title info (shown when a poster has focus)
        infoBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; alpha = 0f }
        infoTitle = Ui.text(this, "", 26f, Color.WHITE, Typeface.BOLD, "sans-serif-medium").apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        infoMeta = Ui.text(this, "", 15f, 0xFFB8C0CC.toInt())
        infoText = Ui.text(this, "", 15f, 0xFFAEB6C4.toInt()).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setLineSpacing(0f, 1.4f) }
        infoBlock.addView(infoTitle)
        infoBlock.addView(infoMeta, Ui.lp(WRAP, WRAP, t = dp(6)))
        infoBlock.addView(infoText, Ui.lp(dp(540), WRAP, t = dp(10)))
        stage.addView(infoBlock, Ui.flp(dp(580), WRAP, Gravity.BOTTOM or Gravity.START))
        // fixed height for both states, so rows never jump
        box.addView(stage, Ui.lp(MATCH, dp(112), t = dp(2), b = dp(6)))
        return box
    }

    private fun onFocusMoved(now: View?) {
        if (now == null) return
        // the focused row settles just under the fixed top area
        var p: View? = now
        while (p != null && p !is HorizontalScrollView) p = p.parent as? View
        val label = (p as? HorizontalScrollView)?.tag as? View
        if (label != null) scroll.smoothScrollTo(0, (label.top - dp(10)).coerceAtLeast(0))

        val t = titles[now]
        pendingInfo?.let { main.removeCallbacks(it) }
        val r = Runnable { showInfo(t) }
        pendingInfo = r
        main.postDelayed(r, if (t == null) 0 else 220)   // short wait so fast scrolling stays smooth
    }

    private fun showInfo(t: Title?) {
        if (t == null) {
            clockBlock.animate().alpha(1f).setDuration(200).start()
            infoBlock.animate().alpha(0f).setDuration(160).start()
            backdropScrim.animate().alpha(0f).setDuration(300).start()
            return
        }
        infoTitle.text = t.name
        val parts = listOfNotNull(t.year.ifBlank { null }, if (t.vote > 0) "★ %.1f".format(t.vote) else null,
            if (t.isTv) "Series" else "Movie", t.service.label)
        val sb = android.text.SpannableStringBuilder()
        parts.forEachIndexed { i, x ->
            if (i > 0) { val st = sb.length; sb.append("   ·   "); sb.setSpan(android.text.style.ForegroundColorSpan(0x66B8C0CC), st, sb.length, 0) }
            sb.append(x)
        }
        infoMeta.text = sb
        infoText.text = t.overview
        infoText.visibility = if (t.overview.isBlank()) View.GONE else View.VISIBLE
        clockBlock.animate().alpha(0f).setDuration(160).start()
        infoBlock.alpha = 0f; infoBlock.animate().alpha(1f).setDuration(240).start()
        // owner: no scary picture in the background, so horror never changes it
        if (t.horror) { backdropScrim.animate().alpha(0f).setDuration(200).start(); return }
        if (t.backdrop != null && !demo) Images.load(this, Tmdb.IMG + "w780" + t.backdrop, backdrop)
        else backdrop.setImageBitmap(Ui.placeholder(dp(310), dp(175), "", Demo.color(t.name)))
        backdropScrim.animate().alpha(0.6f).setDuration(400).start()
    }

    // ---------- rows ----------

    private fun build(shelf: Shelf) {
        column.removeAllViews()
        titles.clear()
        val subs = store.subscribed()
        row("My Apps", Catalog.visible(Catalog.myApps, subs, ::installed).map { appTile(it) }, first = true)

        if (!demo && store.tmdbKey.isNotEmpty() && shelf.topMovies.isEmpty() && shelf.byLang.isEmpty()) {
            row("Movies & Shows", listOf(infoTile("Loading movies and shows…", "Hollywood, Bollywood, Korean, South Indian and Top 10 appear here in a few seconds") {
                startActivity(Intent(this, SetupActivity::class.java))
            }))
        } else if (!demo && store.tmdbKey.isEmpty()) {
            row("Movies & Shows", listOf(infoTile("Movies & Shows", "Add your free TMDB key to see Hollywood, Bollywood, Korean, South Indian and Top 10") {
                startActivity(Intent(this, SetupActivity::class.java))
            }))
        } else {
            // Movies & Shows are for the parents (horror only in the last row). The Kids row has apps only.
            val seen = HashSet<String>()
            fun once(l: List<Title>) = l.filter { seen.add((if (it.isTv) "t" else "m") + it.id) }
            // owner's order: New Releases, Top 10 Movies, Top 10 Shows, Hollywood, Korean, South Indian, Indian, then the rest
            row("New Releases", once(shelf.newReleases).map { posterTile(it) })
            row("Top 10 Movies", shelf.topMovies.mapIndexed { i, t -> rankedTile(t, i + 1) })
            row("Top 10 Shows", shelf.topShows.mapIndexed { i, t -> rankedTile(t, i + 1) })
            for (l in listOf(Lang.HOLLYWOOD, Lang.KOREAN, Lang.SOUTH, Lang.BOLLYWOOD)) row(l.title, once(shelf.byLang[l].orEmpty()).map { posterTile(it) })
            row("Coming Soon", shelf.comingSoon.map { posterTile(it) })
            row("Horror", shelf.horror.map { posterTile(it) })
        }
        row("All Apps", Catalog.others(allTvApps(), subs).map { appTile(it, small = true) })
        column.post { column.findViewWithTag<View>("first")?.requestFocus() }
    }

    private fun row(title: String, tiles: List<View>, first: Boolean = false) {
        if (tiles.isEmpty()) return
        val label = Ui.text(this, title, 16f, 0xFFE2E8F0.toInt(), Typeface.NORMAL, "sans-serif-medium").apply { letterSpacing = 0.01f }
        column.addView(label, Ui.lp(WRAP, WRAP, t = if (column.childCount == 0) dp(2) else dp(10), b = dp(0)))
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false
            gravity = Gravity.BOTTOM
            setPadding(dp(6), dp(8), dp(48), dp(10))   // room so the focus zoom + ring never get clipped
        }
        tiles.forEachIndexed { i, v ->
            if (first && i == 0) v.tag = "first"
            line.addView(v, (v.layoutParams as? LinearLayout.LayoutParams ?: Ui.lp(WRAP, WRAP)).apply { if (i > 0) leftMargin = dp(if (titles.containsKey(v)) posterGap else appGap) })
        }
        val hs = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false
            tag = label
            addView(line)
        }
        column.addView(hs, Ui.lp(MATCH, WRAP, l = -dp(6)))
    }

    internal fun appTile(e: AppEntry, small: Boolean = false): View {
        val w = dp(appW); val h = dp(appH)
        val f = FrameLayout(this)
        f.layoutParams = Ui.lp(w, h)
        f.setBackgroundColor(Ui.CARD)
        val img = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_XY }
        img.setImageBitmap(banner(e, w, h))
        f.addView(img, FrameLayout.LayoutParams(MATCH, MATCH))
        if (e.kids) {
            f.addView(View(this).apply {
                background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xCC000000.toInt(), 0x00000000))
            }, Ui.flp(MATCH, dp(44), Gravity.BOTTOM))
            f.addView(Ui.text(this, e.label, 11f, Color.WHITE, Typeface.NORMAL, "sans-serif-medium"),
                Ui.flp(WRAP, WRAP, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(8), 0, 0, dp(6)) })
            f.addView(Ui.text(this, "KIDS", 10f, 0xFFF2F4F7.toInt(), Typeface.BOLD, "sans-serif-medium").apply {
                letterSpacing = 0.12f
                setPadding(dp(7), dp(2), dp(7), dp(2))
                background = GradientDrawable().apply { cornerRadius = dp(4).toFloat(); setColor(0x73000000) }
            }, Ui.flp(WRAP, WRAP, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(8), dp(8), 0) })
        }
        Ui.round(f, 10)
        Ui.focusable(f, 10)
        f.contentDescription = e.label
        f.setOnClickListener { open(e.pkg, e.extraKey) }
        return f
    }

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

    private fun posterTile(t: Title): FrameLayout {
        val w = dp(posterW); val h = dp(posterH)
        val f = FrameLayout(this)
        f.layoutParams = Ui.lp(w, h)
        f.setBackgroundColor(Ui.CARD)
        val img = Ui.imageView(this)
        f.addView(img, FrameLayout.LayoutParams(MATCH, MATCH))
        if (t.poster != null && !demo) Images.load(this, Tmdb.IMG + "w342" + t.poster, img)
        else img.setImageBitmap(Ui.placeholder(w, h, t.name, Demo.color(t.name)))
        f.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0x99000000.toInt(), 0x00000000))
        }, Ui.flp(MATCH, dp(24), Gravity.BOTTOM))
        f.addView(Ui.badge(this, t.service).apply { textSize = 9f; setPadding(dp(5), dp(2), dp(5), dp(2)) },
            Ui.flp(WRAP, WRAP, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(5), 0, 0, dp(5)) })
        Ui.round(f, 6)
        Ui.focusable(f, 6, 1.08f)
        f.contentDescription = t.name
        titles[f] = t
        f.setOnClickListener { openTitle(t) }
        return f
    }

    /** Top 10: compact rank chip in the corner, so all ten fit across one screen (owner: auto-fit). */
    private fun rankedTile(t: Title, rank: Int): View {
        val poster = posterTile(t)
        poster.addView(Ui.text(this, rank.toString(), 13f, Color.WHITE, Typeface.BOLD, "sans-serif-black").apply {
            gravity = Gravity.CENTER; minWidth = dp(22)
            setPadding(dp(5), dp(2), dp(5), dp(2))
            background = GradientDrawable().apply { cornerRadius = dp(5).toFloat(); setColor(0xD90B0F17.toInt()); setStroke(dp(1), 0x66FFFFFF) }
        }, Ui.flp(WRAP, WRAP, Gravity.TOP or Gravity.START).apply { setMargins(dp(5), dp(5), 0, 0) })
        return poster
    }

    private fun infoTile(title: String, sub: String, onClick: () -> Unit): View {
        val f = FrameLayout(this)
        f.layoutParams = Ui.lp(dp(430), dp(112))
        f.setBackgroundColor(Ui.CARD)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(16), dp(18), dp(16)) }
        col.addView(Ui.text(this, title, 18f, Ui.TEXT, Typeface.BOLD, "sans-serif-medium"))
        col.addView(Ui.text(this, sub, 14f, Ui.SUB).apply { maxLines = 2 }, Ui.lp(WRAP, WRAP, t = dp(8)))
        f.addView(col)
        Ui.round(f, 10)
        Ui.focusable(f, 10, 1.03f)
        f.setOnClickListener { onClick() }
        return f
    }
}

/** Draws its children, then fades them out to the left and bottom with an alpha mask (no hard edges on any background). */
class MaskedFrame(ctx: android.content.Context) : FrameLayout(ctx) {
    private val maskPaint = Paint().apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN) }
    init { setWillNotDraw(false) }
    override fun dispatchDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val save = c.saveLayer(0f, 0f, w, h, null)
        super.dispatchDraw(c)
        maskPaint.shader = android.graphics.LinearGradient(0f, 0f, w, 0f, intArrayOf(0x00000000, 0x99000000.toInt(), 0xFF000000.toInt()),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, maskPaint)
        maskPaint.shader = android.graphics.LinearGradient(0f, h * 0.45f, 0f, h, 0xFF000000.toInt(), 0x00000000, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, maskPaint)
        c.restoreToCount(save)
    }
}
