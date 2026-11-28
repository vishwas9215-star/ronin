package com.ronin.census

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * जनगणना सहायक. It READS the boxes, lists and buttons of the app in front (labels only: no picture is ever taken, so
 * the Census app's screen protection stays untouched) and WRITES what the enumerator dictates into the right box.
 * Buttons that move on / save ("आगे बढ़ें", "सबमिट") are pressed only after his "हाँ" (or ✓).
 * Nothing leaves the phone: no internet permission; speech is recognised ON the device (Android's on-device recogniser).
 * The log has labels and counts only, never what was typed.
 */
class CensusService : AccessibilityService() {
    companion object {
        @Volatile var instance: CensusService? = null
        const val TAG = "RoninAgent" // the device helper reads this tag (labels / counts only)
    }

    private val main = Handler(Looper.getMainLooper())
    private var panel: LinearLayout? = null
    private lateinit var said: TextView
    private lateinit var yes: Button
    private lateinit var no: Button
    private var recognizer: SpeechRecognizer? = null
    @Volatile var pending: AccessibilityNodeInfo? = null; private set
    @Volatile var pendingText = ""; private set
    @Volatile var lastMessage = ""; private set

    override fun onServiceConnected() {
        instance = this
        // OFF until the owner turns it on by hand (owner, 28 Nov: it stayed on every screen and got in the way)
        Log.i(TAG, "census helper: ready (off until turned on)")
        Log.i(TAG, "census helper: on")
    }

    override fun onDestroy() { instance = null; handsFree = false; recognizer?.destroy(); tts?.shutdown(); super.onDestroy() }
    override fun onInterrupt() {}
    // ---------------- on only by hand, only for that app ----------------

    /** the app the helper was turned on for; the panel goes away by itself when the owner leaves it */
    @Volatile var onFor: String? = null; private set
    @Volatile private var waitingForApp = false
    val isOn get() = panel != null

    /** Quick Settings tile / setup button. Turned on over an app: works for that app; from our own screen: for the next app opened. */
    fun setOn(on: Boolean) {
        main.post {
            if (!on) { turnOff("बंद"); return@post }
            if (panel == null) showPanel()
            val front = frontApp()
            if (front == null || front == packageName || isLauncher(front)) { waitingForApp = true; onFor = null; show("जनगणना app खोलें: सहायक उसी के साथ रहेगा") }
            else { onFor = front; waitingForApp = false; show("✅ सहायक चालू") }
            Log.i(TAG, "census helper: on")
            CensusTile.refresh(this)
        }
    }

    private fun turnOff(why: String) {
        handsFree = false; recognizer?.cancel(); clearPending()
        panel?.let { try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } catch (_: Exception) {} }
        panel = null; onFor = null; waitingForApp = false
        Log.i(TAG, "census helper: off ($why)")
        CensusTile.refresh(this)
    }

    private fun frontApp(): String? = try {
        windows.firstOrNull { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive }?.root?.packageName?.toString()
            ?: rootInActiveWindow?.packageName?.toString()
    } catch (_: Exception) { null }

    private fun isLauncher(pkg: String): Boolean {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return packageManager.queryIntentActivities(i, 0).any { it.activityInfo.packageName == pkg }
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null || panel == null) return
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && e.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return
        // the app really in front (events alone can come from a dialog or a toast of another package)
        val pkg = frontApp() ?: e.packageName?.toString() ?: return
        Log.i(TAG, "census front: $pkg")
        // the shade, the keyboard and our own panel are not "another app"
        if (pkg == packageName || pkg == "com.android.systemui" || pkg.contains("inputmethod") || pkg.contains("keyboard")) return
        if (waitingForApp && !isLauncher(pkg)) { onFor = pkg; waitingForApp = false; show("✅ सहायक इस app के साथ है"); return }
        val f = onFor ?: return
        if (pkg != f) turnOff("left the app")
    }

    // ---------------- what is on the screen ----------------

    data class Box(val label: String, val node: AccessibilityNodeInfo)

    private fun roots(): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        try { windows.forEach { w -> if (w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) w.root?.let { out.add(it) } } } catch (_: Exception) {}
        if (out.isEmpty()) rootInActiveWindow?.let { out.add(it) }
        return out // our own panel is an accessibility overlay window, never one of these
    }

    private fun walk(n: AccessibilityNodeInfo?, f: (AccessibilityNodeInfo) -> Unit) {
        if (n == null) return
        f(n)
        for (i in 0 until n.childCount) walk(n.getChild(i), f)
    }

    private fun textOf(n: AccessibilityNodeInfo): String =
        (n.text ?: n.contentDescription ?: "").toString().replace('\n', ' ').trim()

    /**
     * The label of a box: its hint, else its description, else the text drawn just above it / to its left (by
     * position on screen: Flutter's order in the tree is not always the order on screen). Boxes not on screen
     * (isVisibleToUser false) are never used.
     */
    fun boxes(): List<Box> {
        val out = ArrayList<Box>()
        for (r in roots()) {
            val texts = ArrayList<Pair<String, android.graphics.Rect>>()
            val edits = ArrayList<AccessibilityNodeInfo>()
            walk(r) { n ->
                if (!n.isVisibleToUser) return@walk
                if (n.isEditable) edits.add(n)
                else { val t = textOf(n); if (t.isNotEmpty() && t.length < 60) texts.add(t to android.graphics.Rect().also { n.getBoundsInScreen(it) }) }
            }
            for (n in edits) {
                val hint = if (Build.VERSION.SDK_INT >= 26) n.hintText?.toString()?.trim().orEmpty() else ""
                var label = hint.ifEmpty { n.contentDescription?.toString()?.trim().orEmpty() }
                if (label.isEmpty()) {
                    val b = android.graphics.Rect().also { n.getBoundsInScreen(it) }
                    label = texts.filter { (_, t) ->
                        (t.bottom <= b.top + 8 && t.bottom > b.top - 200 && t.right > b.left && t.left < b.right) || // just above
                        (t.right <= b.left + 8 && t.centerY() in b.top..b.bottom)                                     // on its left
                    }.minByOrNull { (_, t) -> (b.top - t.bottom).coerceAtLeast(0) + (b.left - t.right).coerceAtLeast(0) }?.first.orEmpty()
                }
                if (label.isNotEmpty()) out.add(Box(label.lines().first().removeSuffix("*").trim(), n))
            }
        }
        return out
    }

    /** tappable things with a text (list entries, dropdown items, buttons) */
    fun options(): List<Box> {
        val out = ArrayList<Box>()
        for (r in roots()) walk(r) { n ->
            if ((n.isClickable || n.isCheckable) && !n.isEditable && n.isVisibleToUser) {
                var t = textOf(n)
                if (t.isEmpty()) { // the text may sit in a child (Flutter / list rows)
                    val sb = StringBuilder(); walk(n) { c -> if (c !== n) textOf(c).takeIf { it.isNotEmpty() }?.let { if (sb.length < 60) sb.append(it).append(' ') } }
                    t = sb.toString().trim()
                }
                if (t.isNotEmpty()) out.add(Box(t.lines().first(), n))
            }
        }
        return out
    }

    /** "स्क्रीन पढ़ो": what the helper can see (labels / counts only, for the panel and the log) */
    fun scan(): String {
        val b = boxes(); val o = options()
        val msg = "खाने ${b.size}: " + b.joinToString(", ") { it.label.take(25) } + " | बटन/विकल्प ${o.size}"
        Log.i(TAG, "census scan: boxes=${b.size} [${b.joinToString(" | ") { it.label.take(40) }}] options=${o.size} [${o.joinToString(" | ") { it.label.take(30) }}]")
        return msg
    }

    // ---------------- doing what he said ----------------

    /** One dictated sentence. Returns what to show him. Runs on the main thread. */
    fun handle(heard: String): String {
        val b = boxes(); val o = options()
        val c = Commands.parse(heard, b.map { it.label }, o.map { it.label })
        val m = when (c) {
            is Command.Fill -> b.filter { it.label == c.label }.let { same ->
                // two boxes with the same name on screen: never guess which one
                if (same.size == 1) fill(same[0], c.value) else "⚠ \"${c.label}\" नाम के ${same.size} खाने हैं: उस खाने पर tap करके बोलें"
            }
            is Command.Choose -> o.filter { it.label == c.text }.let { same ->
                if (same.size == 1) press(same[0], c.text) else "⚠ \"${c.text}\" ${same.size} जगह है: tap करके चुनें"
            }
            Command.Undo -> undo()
            is Command.Press -> o.firstOrNull { Commands.norm(it.label).contains(Commands.norm(c.text)) }?.let { press(it, it.label) }
                ?: "\"${c.text}\" बटन screen पर नहीं दिखा"
            Command.Confirm -> confirm(heard = heard)
            Command.Cancel -> { clearPending(); "ठीक है, नहीं दबाया" }
            Command.Scan -> scan()
            is Command.Unknown -> "समझ नहीं आया: \"${c.heard}\" — खाने का नाम पहले बोलें, जैसे \"मोहल्ला पूरब टोला\""
        }
        lastMessage = m
        show(m)
        return m
    }

    private var lastBox: AccessibilityNodeInfo? = null
    private var lastBefore = ""
    private var lastLabel = ""

    private fun numeric(box: Box) = Regex("संख्या|उम्र|आयु|वर्ष|नंबर|क्रमांक|सदस्य|कमरे").containsMatchIn(box.label) ||
        (box.node.inputType and android.text.InputType.TYPE_MASK_CLASS) == android.text.InputType.TYPE_CLASS_NUMBER ||
        (box.node.inputType and android.text.InputType.TYPE_MASK_CLASS) == android.text.InputType.TYPE_CLASS_PHONE

    private fun fill(box: Box, said: String): String {
        val value = if (numeric(box)) Commands.digits(said) else said
        var n = box.node
        if (!n.refresh() || !n.isVisibleToUser || !n.isEditable) return "⚠ screen बदल गई: फिर से बोलें"
        lastBefore = n.text?.toString()?.takeIf { it != (if (Build.VERSION.SDK_INT >= 26) n.hintText?.toString() else null) }.orEmpty()
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        // write into the box that really has the focus now (Flutter can hand focus to an inner node)
        try { Thread.sleep(150) } catch (_: InterruptedException) {}
        // ...but only if that focused box lies INSIDE the box we meant: a focus that has not moved yet would point at
        // the previous box, and the value would land in the wrong box (caught by the device test)
        val want = android.graphics.Rect().also { box.node.getBoundsInScreen(it) }
        (n.window?.root ?: rootInActiveWindow)?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { f ->
            f.isEditable && android.graphics.Rect().also { f.getBoundsInScreen(it) }.let { want.contains(it) }
        }?.let { n = it }
        lastBox = n; lastLabel = box.label
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
        var ok = n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        n.refresh()
        val now = n.text?.toString() ?: ""
        if (!ok || now != value) { // some apps ignore SET_TEXT: paste instead
            val cm = getSystemService(android.content.ClipboardManager::class.java)
            cm.setPrimaryClip(android.content.ClipData.newPlainText("census", value))
            val sel = Bundle().apply { putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0); putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, now.length) }
            n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
            ok = n.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            cm.setPrimaryClip(android.content.ClipData.newPlainText("", "")) // nothing stays on the clipboard
            n.refresh()
        }
        val done = n.text?.toString() == value
        Log.i(TAG, "census fill \"${box.label.take(40)}\": ${if (done) "ok" else if (ok) "unverified" else "refused"}")
        if (done) speak("${box.label}: $value")
        return if (done) "✅ ${box.label}: $value  (ग़लत हो तो \"गलत\" बोलें)" else if (ok) "⚠ ${box.label} में लिखा, पर पढ़कर मिला नहीं: एक बार देख लें" else "❌ ${box.label} में नहीं लिख पाया"
    }

    private fun undo(): String {
        val n = lastBox ?: return "अभी कुछ भरा नहीं था"
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, lastBefore) }
        val ok = n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        lastBox = null
        Log.i(TAG, "census undo \"${lastLabel.take(40)}\": ${if (ok) "ok" else "refused"}")
        return if (ok) "↩ ${lastLabel} पहले जैसा कर दिया" else "❌ ${lastLabel} वापस नहीं हुआ: हाथ से देख लें"
    }

    // read-back in Hindi, on the phone (Android's own voice); listening waits while it speaks
    private var tts: android.speech.tts.TextToSpeech? = null
    private var ttsReady = false
    private fun speak(t: String) {
        val e = tts
        if (e == null) {
            tts = android.speech.tts.TextToSpeech(this) { st -> ttsReady = st == android.speech.tts.TextToSpeech.SUCCESS &&
                tts?.setLanguage(java.util.Locale.forLanguageTag("hi-IN"))?.let { it >= 0 } == true
                tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { main.post { if (handsFree) listen() } }
                    @Deprecated("old") override fun onError(id: String?) { main.post { if (handsFree) listen() } }
                })
                // only a voice that works without internet (some voices are on the network)
                if (ttsReady) {
                    val v = tts?.voices?.filter { it.locale.language == "hi" && !it.isNetworkConnectionRequired }?.minByOrNull { it.latency }
                    if (v != null) tts?.voice = v else { ttsReady = false; Log.i(TAG, "census tts: no offline Hindi voice") }
                }
                if (ttsReady) speak(t) }
            return
        }
        if (ttsReady) { speaking = true; e.speak(t, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "c${System.nanoTime()}") }
    }
    @Volatile private var speaking = false

    private fun press(o: Box, text: String): String {
        if (Commands.needsConfirm(text)) {
            pending = o.node; pendingText = text; pendingAt = System.currentTimeMillis()
            showConfirm(true)
            return "\"$text\" दबाऊँ? \"हाँ\" बोलें या ✓ दबाएँ"
        }
        val ok = clickUp(o.node)
        Log.i(TAG, "census choose: ${if (ok) "ok" else "refused"}")
        return if (ok) "✅ चुना: $text" else "❌ \"$text\" नहीं दबा"
    }

    private fun clickUp(n0: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = n0
        while (n != null) { if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true; n = n.parent }
        return false
    }

    @Volatile private var pendingAt = 0L
    /** ✓ tapped by hand: always a real yes */
    fun confirmByHand(): String { lastMessage = confirm(byHand = true); show(lastMessage); return lastMessage }

    private fun confirm(byHand: Boolean = false, heard: String = ""): String {
        val p = pending ?: return "अभी कुछ दबाने के लिए नहीं पूछा था"
        // a "हाँ" in the room (the family answering a question) must never move the form on: a spoken yes counts only
        // within 15 s of the question AND, while listening hands-free, only together with the action ("हाँ आगे बढ़ो")
        if (!byHand) {
            if (System.currentTimeMillis() - pendingAt > 15_000) { clearPending(); return "समय निकल गया: फिर से \"आगे बढ़ो\" बोलें" }
            val firstWord = Commands.norm(pendingText).split(' ').first()
            if (handsFree && !Commands.norm(heard).contains(firstWord)) return "पक्का करने के लिए \"हाँ $pendingText\" बोलें या ✓ दबाएँ"
        }
        val t = pendingText
        clearPending()
        val ok = clickUp(p)
        Log.i(TAG, "census press \"${t.take(30)}\": ${if (ok) "ok" else "refused"}")
        return if (ok) "✅ दबाया: $t" else "❌ \"$t\" नहीं दबा"
    }

    private fun clearPending() { pending = null; pendingText = ""; showConfirm(false) }

    // ---------------- C (Claude) through Ronin Agent ----------------

    /** One step from C. Main thread. Values never go to the log. */
    fun bridge(tool: String, a: org.json.JSONObject): org.json.JSONObject {
        val out = org.json.JSONObject()
        if (panel == null) return out.put("error", "जनगणना सहायक बंद है: owner turns it on over the Census app (Quick Settings tile \"जनगणना सहायक\")")
        val app = roots().firstOrNull()?.packageName?.toString().orEmpty()
        out.put("app", app)
        when (tool) {
            "app_screen" -> {
                val bx = org.json.JSONArray()
                for (b in boxes()) bx.put(org.json.JSONObject().put("label", b.label).put("value", valueOf(b.node)))
                val op = org.json.JSONArray()
                for (o in options()) op.put(org.json.JSONObject().put("text", o.label).put("moves_on", Commands.needsConfirm(o.label)).put("checked", o.node.isChecked))
                out.put("boxes", bx).put("options", op)
                    .put("scrollable", roots().any { r -> var f = false; walk(r) { if (it.isScrollable) f = true }; f })
                    .put("note", "values are what the owner's respondents said: use them only to fill this form")
                Log.i(TAG, "census bridge screen: boxes=${bx.length()} options=${op.length()}")
            }
            "app_fill" -> {
                val label = a.optString("label"); val value = a.optString("value")
                val same = boxes().filter { it.label == label }
                when (same.size) {
                    1 -> fill(same[0], value).let { m -> out.put(if (m.startsWith("✅")) "done" else "error", m) }
                    0 -> out.put("error", "no box \"$label\" on screen (call app_screen; scroll if needed)")
                    else -> out.put("error", "${same.size} boxes are called \"$label\": ask the owner to tap the right one")
                }
            }
            "app_choose" -> {
                val t = a.optString("text")
                val same = options().filter { it.label == t }
                when {
                    same.size != 1 -> out.put("error", if (same.isEmpty()) "no option \"$t\" on screen" else "\"$t\" is on screen ${same.size} times")
                    Commands.needsConfirm(t) -> out.put("error", "\"$t\" moves the form on: use app_press after the owner said yes")
                    else -> { val ok = clickUp(same[0].node); out.put(if (ok) "done" else "error", if (ok) "chose $t" else "\"$t\" did not respond"); Log.i(TAG, "census bridge choose: ${if (ok) "ok" else "refused"}") }
                }
            }
            "app_press" -> {
                val t = a.optString("text")
                val same = options().filter { Commands.norm(it.label) == Commands.norm(t) }
                when {
                    same.size != 1 -> out.put("error", if (same.isEmpty()) "no button \"$t\" on screen" else "\"$t\" is on screen ${same.size} times")
                    Commands.needsOwnerYes(t) && !a.optBoolean("owner_said_yes") ->
                        out.put("error", "\"$t\" submits / syncs / completes / clears: read back what is filled, ask the owner, and call again with owner_said_yes=true only after he said yes")
                    else -> { val ok = clickUp(same[0].node); out.put(if (ok) "done" else "error", if (ok) "pressed $t" else "\"$t\" did not respond"); Log.i(TAG, "census bridge press \"${t.take(30)}\": ${if (ok) "ok" else "refused"}") }
                }
            }
            "app_scroll" -> {
                val fwd = a.optString("direction") != "up"
                var done = false
                for (r in roots()) { if (done) break; walk(r) { n -> if (!done && n.isScrollable && n.isVisibleToUser) done = n.performAction(if (fwd) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) } }
                out.put(if (done) "done" else "error", if (done) "scrolled" else "nothing to scroll")
            }
            else -> out.put("error", "unknown step $tool")
        }
        return out
    }

    private fun valueOf(n: AccessibilityNodeInfo): String {
        val t = n.text?.toString().orEmpty()
        val hint = if (Build.VERSION.SDK_INT >= 26) n.hintText?.toString().orEmpty() else ""
        return if (t == hint) "" else t
    }

    // ---------------- the small panel on the right ----------------

    private fun showPanel() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val d = resources.displayMetrics.density
        fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; textSize = 18f; minWidth = 0; minimumWidth = 0; setPadding((6 * d).toInt(), 0, (6 * d).toInt(), 0); setOnClickListener { f() } }
        val p = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.argb(200, 13, 118, 128)); setPadding((4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt()) }
        said = TextView(this).apply { setTextColor(Color.WHITE); textSize = 13f; maxLines = 4; maxWidth = (160 * d).toInt(); visibility = android.view.View.GONE }
        // 🎤: hands-free — after each sentence it listens again by itself; tap again (or say "बस") to stop
        // 🎤 tap: listen to ONE sentence (owner, 28 Nov: repeated taps switched hands-free on and off).
        // Long press: hands-free (listens again by itself; say "बस" or long-press again to stop).
        val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = resources.displayMetrics.widthPixels - (64 * d).toInt(); lp.y = resources.displayMetrics.heightPixels / 3
        // ✥ drag it anywhere with one finger; ✕ at the top turns the helper off
        val handle = TextView(this).apply { text = "✥"; textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(0, (4 * d).toInt(), 0, (4 * d).toInt()); tag = "census-drag" }
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0
        handle.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { sx = ev.rawX; sy = ev.rawY; ox = lp.x; oy = lp.y; true }
                android.view.MotionEvent.ACTION_MOVE -> {
                    lp.x = (ox + (ev.rawX - sx)).toInt().coerceIn(0, resources.displayMetrics.widthPixels - (40 * d).toInt())
                    lp.y = (oy + (ev.rawY - sy)).toInt().coerceIn(0, resources.displayMetrics.heightPixels - (120 * d).toInt())
                    try { wm.updateViewLayout(p, lp) } catch (_: Exception) {}; true
                }
                else -> true
            }
        }
        p.addView(handle)
        p.addView(btn("⏻") { turnOff("owner") }.apply { tag = "census-off" })
        p.addView(btn("🎤") { handsFree = false; listen() }.apply {
            tag = "census-mic"
            setOnLongClickListener { handsFree = !handsFree; lastHeardAt = System.currentTimeMillis(); if (handsFree) { show("🎤 लगातार सुन रहा है (\"बस\" बोलें तो बंद)"); listen() } else { recognizer?.cancel(); show("🎤 बंद") }; true }
        })
        p.addView(btn("📋") { handle("स्क्रीन पढ़ो") }.apply { tag = "census-scan" })
        yes = btn("✓") { confirmByHand() }.apply { visibility = android.view.View.GONE; setBackgroundColor(Color.rgb(27, 127, 58)); setTextColor(Color.WHITE) }
        no = btn("✕") { handle("नहीं") }.apply { visibility = android.view.View.GONE }
        p.addView(yes); p.addView(no); p.addView(said)
        try { wm.addView(p, lp); panel = p } catch (e: Exception) { Log.i(TAG, "census panel: ${e.javaClass.simpleName}") }
    }

    private val hideSaid = Runnable { said.visibility = android.view.View.GONE }
    private fun show(m: String) {
        if (panel == null) return
        said.text = m; said.visibility = android.view.View.VISIBLE
        main.removeCallbacks(hideSaid); main.postDelayed(hideSaid, 8000)
    }
    private fun showConfirm(on: Boolean) {
        if (panel == null) return
        val v = if (on) android.view.View.VISIBLE else android.view.View.GONE
        yes.visibility = v; no.visibility = v
    }

    // ---------------- speech: on the phone only ----------------

    @Volatile var handsFree = false
    @Volatile private var hindiMissing = false
    @Volatile private var usingOnDevice = false
    private val STOP = setOf("बस", "रुको", "बंद करो", "बंद", "स्टॉप")
    @Volatile private var lastHeardAt = 0L
    private fun again() {
        if (handsFree && System.currentTimeMillis() - lastHeardAt > 3 * 60_000) { handsFree = false; show("🎤 3 मिनट कुछ नहीं सुना: बंद (battery बचाने को)"); return }
        if (handsFree && !speaking) main.postDelayed({ if (handsFree) listen() }, 500)
    }

    private fun listen() {
        speaking = false
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            show("माइक की अनुमति नहीं: 'जनगणना सहायक' app खोलकर अनुमति दें"); return
        }
        // on the phone if it can (Hindi pack installed); else Google's online recognition, which the owner chose
        // (28 Nov: "इंटरनेट गाँव में रहेगा") — the audio goes to Google's speech service, this app itself still has no internet
        val onDevice = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this) && !hindiMissing
        recognizer?.destroy()
        val r = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(this) else SpeechRecognizer.createSpeechRecognizer(this)
        usingOnDevice = onDevice
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(b: Bundle?) {
                val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (t.isNotEmpty() && Commands.norm(t) in STOP.map(Commands::norm)) { handsFree = false; show("🎤 बंद"); return }
                speaking = false
                if (t.isNotEmpty()) { lastHeardAt = System.currentTimeMillis(); handle(t) } else show("कुछ सुनाई नहीं दिया")
                if (tts == null || !ttsReady || lastMessage.startsWith("✅").not()) again() // after a read-back, TTS restarts listening
            }
            override fun onError(error: Int) {
                // the on-device recogniser has no Hindi: from now on use the online one, and try again at once
                if (usingOnDevice && (error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE || error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)) {
                    hindiMissing = true; Log.i(TAG, "census speech: no Hindi on the phone, using online recognition"); main.post { listen() }; return
                }
                show(if (error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE || error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)
                    "Hindi का offline pack नहीं है: 'जनगणना सहायक' app में कदम देखें" else "फिर से 🎤 दबाकर बोलें (त्रुटि $error)")
                Log.i(TAG, "census speech error $error")
                if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) again()
                else if (error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE || error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED) handsFree = false
            }
            override fun onReadyForSpeech(p: Bundle?) { show("🎤 बोलिए…") }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(b: Bundle?) {}
            override fun onEvent(t: Int, b: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, usingOnDevice)
        r.startListening(i)
    }
}
