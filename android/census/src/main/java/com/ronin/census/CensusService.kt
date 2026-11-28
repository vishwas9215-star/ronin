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
        showPanel()
        Log.i(TAG, "census helper: on")
    }

    override fun onDestroy() { instance = null; recognizer?.destroy(); super.onDestroy() }
    override fun onInterrupt() {}
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}

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

    /** the label of a box: its hint, else its description, else the text shown just before it */
    fun boxes(): List<Box> {
        val out = ArrayList<Box>()
        for (r in roots()) {
            var lastText = ""
            walk(r) { n ->
                val hint = if (Build.VERSION.SDK_INT >= 26) n.hintText?.toString()?.trim().orEmpty() else ""
                if (n.isEditable) {
                    val label = hint.ifEmpty { n.contentDescription?.toString()?.trim().orEmpty() }.ifEmpty { lastText }
                    if (label.isNotEmpty()) out.add(Box(label.lines().first(), n))
                } else {
                    val t = textOf(n)
                    if (t.isNotEmpty() && t.length < 60) lastText = t
                }
            }
        }
        return out
    }

    /** tappable things with a text (list entries, dropdown items, buttons) */
    fun options(): List<Box> {
        val out = ArrayList<Box>()
        for (r in roots()) walk(r) { n ->
            if ((n.isClickable || n.isCheckable) && !n.isEditable) {
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
            is Command.Fill -> fill(b.first { it.label == c.label }, c.value)
            is Command.Choose -> o.first { it.label == c.text }.let { press(it, c.text) }
            is Command.Press -> o.firstOrNull { Commands.norm(it.label).contains(Commands.norm(c.text)) }?.let { press(it, it.label) }
                ?: "\"${c.text}\" बटन screen पर नहीं दिखा"
            Command.Confirm -> confirm()
            Command.Cancel -> { clearPending(); "ठीक है, नहीं दबाया" }
            Command.Scan -> scan()
            is Command.Unknown -> "समझ नहीं आया: \"${c.heard}\" — खाने का नाम पहले बोलें, जैसे \"मोहल्ला पूरब टोला\""
        }
        lastMessage = m
        show(m)
        return m
    }

    private fun fill(box: Box, value: String): String {
        val n = box.node
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
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
        return if (done) "✅ ${box.label}: $value" else if (ok) "⚠ ${box.label} में लिखा, पर पढ़कर मिला नहीं: एक बार देख लें" else "❌ ${box.label} में नहीं लिख पाया"
    }

    private fun press(o: Box, text: String): String {
        if (Commands.needsConfirm(text)) {
            pending = o.node; pendingText = text
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

    private fun confirm(): String {
        val p = pending ?: return "अभी कुछ दबाने के लिए नहीं पूछा था"
        val t = pendingText
        clearPending()
        val ok = clickUp(p)
        Log.i(TAG, "census press \"${t.take(30)}\": ${if (ok) "ok" else "refused"}")
        return if (ok) "✅ दबाया: $t" else "❌ \"$t\" नहीं दबा"
    }

    private fun clearPending() { pending = null; pendingText = ""; showConfirm(false) }

    // ---------------- the small panel on the right ----------------

    private fun showPanel() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val d = resources.displayMetrics.density
        fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; textSize = 18f; minWidth = 0; minimumWidth = 0; setPadding((6 * d).toInt(), 0, (6 * d).toInt(), 0); setOnClickListener { f() } }
        val p = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.argb(200, 13, 118, 128)); setPadding((4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt()) }
        said = TextView(this).apply { setTextColor(Color.WHITE); textSize = 13f; maxLines = 4; maxWidth = (160 * d).toInt(); visibility = android.view.View.GONE }
        p.addView(btn("🎤") { listen() }.apply { tag = "census-mic" })
        p.addView(btn("📋") { handle("स्क्रीन पढ़ो") }.apply { tag = "census-scan" })
        yes = btn("✓") { handle("हाँ") }.apply { visibility = android.view.View.GONE; setBackgroundColor(Color.rgb(27, 127, 58)); setTextColor(Color.WHITE) }
        no = btn("✕") { handle("नहीं") }.apply { visibility = android.view.View.GONE }
        p.addView(yes); p.addView(no); p.addView(said)
        val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.END or Gravity.CENTER_VERTICAL
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

    private fun listen() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            show("माइक की अनुमति नहीं: 'जनगणना सहायक' app खोलकर अनुमति दें"); return
        }
        val onDevice = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        if (!onDevice) { show("इस phone पर offline Hindi आवाज़ पहचान नहीं मिली: 'जनगणना सहायक' app खोलकर देखें"); Log.i(TAG, "census speech: no on-device recogniser"); return }
        recognizer?.destroy()
        val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(b: Bundle?) {
                val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (t.isNotEmpty()) handle(t) else show("कुछ सुनाई नहीं दिया")
            }
            override fun onError(error: Int) {
                show(if (error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE || error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)
                    "Hindi का offline pack नहीं है: 'जनगणना सहायक' app में कदम देखें" else "फिर से 🎤 दबाकर बोलें (त्रुटि $error)")
                Log.i(TAG, "census speech error $error")
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
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        r.startListening(i)
    }
}
