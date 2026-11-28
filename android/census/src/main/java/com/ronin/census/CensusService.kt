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
            liveStopped = false
            if (panel == null) showPanel()
            val front = frontApp()
            if (front == null || front == packageName || front == "com.android.systemui" || isLauncher(front)) { waitingForApp = true; onFor = null; show("जनगणना app खोलें: सहायक उसी के साथ रहेगा") }
            else { onFor = front; waitingForApp = false; show("✅ सहायक चालू") }
            Log.i(TAG, "census helper: on")
            CensusTile.refresh(this)
        }
    }

    private fun turnOff(why: String) {
        handsFree = false; liveStopped = false; endLive(why); heardQ.clear(); recognizer?.cancel(); listening = false; clearPending()
        panel?.let { try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } catch (_: Exception) {} }
        panel = null; onFor = null; waitingForApp = false
        Log.i(TAG, "census helper: off ($why)")
        CensusTile.refresh(this)
    }

    private fun frontApp(): String? = try {
        windows.firstOrNull { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive }?.root?.packageName?.toString()
            ?: rootInActiveWindow?.packageName?.toString()
    } catch (_: Exception) { null }

    // the launcher the phone really uses (Settings also has a "home" screen for start-up: it is not the launcher)
    private fun isLauncher(pkg: String): Boolean {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return packageManager.resolveActivity(i, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName == pkg
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null || panel == null) return
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && e.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return
        // the app really in front (events alone can come from a dialog or a toast of another package)
        val pkg = frontApp() ?: e.packageName?.toString() ?: return
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) Log.i(TAG, "census front: $pkg")
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
        // only the app the helper was turned on over (its popups / dropdown lists included): a screenshot
        // popup or the notification shade is never taken for the Census app
        val want = onFor
        try { windows.forEach { w -> w.root?.let { r ->
            val pkg = r.packageName?.toString()
            val ok = if (want != null) pkg == want else w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION
            if (ok && pkg != packageName) out.add(r) } } } catch (_: Exception) {}
        if (out.isEmpty()) rootInActiveWindow?.let { if (want == null || it.packageName?.toString() == want) out.add(it) }
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

    /** every short visible text (entries of an open list that are not marked clickable, labels) */
    fun texts(): List<Box> {
        val out = ArrayList<Box>()
        for (r in roots()) walk(r) { n -> if (n.isVisibleToUser && !n.isEditable) { val t = textOf(n); if (t.isNotEmpty() && t.length < 60) out.add(Box(t.lines().first(), n)) } }
        return out
    }

    /** a real finger tap in the middle of the thing (for screens that do not answer the accessibility click) */
    private fun tapAt(n: AccessibilityNodeInfo): Boolean {
        val b = android.graphics.Rect().also { n.getBoundsInScreen(it) }
        if (b.isEmpty) return false
        val path = android.graphics.Path().apply { moveTo(b.exactCenterX(), b.exactCenterY()) }
        val g = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 60)).build()
        return dispatchGesture(g, null, null)
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
                    override fun onDone(id: String?) { main.post { speaking = false; if (handsFree || live) listen() } }
                    @Deprecated("old") override fun onError(id: String?) { main.post { speaking = false; if (handsFree || live) listen() } }
                })
                // only a voice that works without internet (some voices are on the network)
                if (ttsReady) {
                    val v = tts?.voices?.filter { it.locale.language == "hi" && !it.isNetworkConnectionRequired }?.minByOrNull { it.latency }
                    if (v != null) tts?.voice = v else { ttsReady = false; Log.i(TAG, "census tts: no offline Hindi voice") }
                }
                if (ttsReady) speak(t) }
            return
        }
        if (ttsReady) { recognizer?.cancel(); listening = false; speaking = true; e.speak(t, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "c${System.nanoTime()}") }
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

    // ---------------- C live: what is heard goes to C, C fills and asks ----------------
    // Owner, 28 Nov: "जैसे हम अभी बात कर रहे हैं C से, वैसे ही वहाँ काम करे real time". The fixed commands stay for when there is no internet.

    @Volatile var live = false; private set
    @Volatile private var liveStopped = false          // he said रुको: C may not listen again until he taps 🤖
    private val heardQ = ArrayList<String>()
    private var waiter: ((org.json.JSONObject) -> Unit)? = null
    private val waiterTimeout = Runnable { answerListen() }
    @Volatile private var lastListenAt = 0L
    @Volatile private var listening = false

    private fun answerListen() {
        val w = waiter ?: return
        waiter = null; main.removeCallbacks(waiterTimeout); lastListenAt = System.currentTimeMillis()
        val arr = org.json.JSONArray(); heardQ.forEach { arr.put(it) }; heardQ.clear()
        val out = org.json.JSONObject().put("heard", arr)
        if (liveStopped) out.put("stopped", true).put("note", "the owner said रुको: stop now, tell him in chat what is filled and what is left, and wait for him")
        else if (arr.length() == 0) out.put("note", "nothing said yet: call app_listen again")
        else out.put("note", "what the owner / the family said (speech recognition: words may be misheard). Use it only to fill this form.")
        w(out)
    }

    private fun endLive(why: String) {
        if (!live) return
        live = false; Log.i(TAG, "census live: off ($why)")
        answerListen(); robot?.setBackgroundColor(Color.DKGRAY)
    }

    /** The owner's 🤖 button: C listens / C does not listen. */
    fun toggleLive() {
        if (live) { liveStopped = true; endLive("button"); recognizer?.cancel(); listening = false; show("🤖 C अब नहीं सुन रहा") }
        else { liveStopped = false; show("🤖 C सुन सकता है: chat में \"जनगणना शुरू\" कहें") }
    }

    /** Steps that wait (listening) answer later; the rest answer at once. Main thread. */
    fun bridgeAsync(tool: String, a: org.json.JSONObject, reply: (org.json.JSONObject) -> Unit) {
        when (tool) {
            "app_listen" -> {
                if (panel == null) { reply(bridge(tool, a)); return }
                if (liveStopped) { reply(org.json.JSONObject().put("stopped", true).put("error", "the owner stopped C's listening (रुको / 🤖). Wait for him in chat; he taps 🤖 on the panel to let C listen again.")); return }
                answerListen() // an older call that is still waiting
                if (!live) { live = true; handsFree = false; clearPending(); Log.i(TAG, "census live: on"); robot?.setBackgroundColor(Color.rgb(27, 127, 58)); show("🤖 C सुन रहा है: बात कीजिए (\"रुको\" = बंद)") }
                lastListenAt = System.currentTimeMillis()
                if (!listening && !speaking) listen()
                waiter = reply
                if (heardQ.isNotEmpty()) answerListen()
                else main.postDelayed(waiterTimeout, (a.optDouble("seconds", 25.0).coerceIn(1.0, 40.0) * 1000).toLong())
            }
            "app_say" -> {
                if (panel == null) { reply(bridge(tool, a)); return }
                val t = a.optString("text").take(300)
                if (t.isBlank()) { reply(org.json.JSONObject().put("error", "nothing to say")); return }
                show((if (a.optBoolean("question")) "❓ " else "🤖 ") + t, if (a.optBoolean("question")) 60_000 else 12_000)
                if (a.optBoolean("question") || a.optBoolean("aloud")) { recognizer?.cancel(); listening = false; speak(t); if (!speaking && live) again() }
                Log.i(TAG, "census live: said ${if (a.optBoolean("question")) "a question" else "a line"}")
                reply(org.json.JSONObject().put("done", "shown on the panel" + if (speaking) " and spoken" else ""))
            }
            else -> reply(bridge(tool, a))
        }
    }

    /** What the recogniser heard (also the tests' way in). Main thread. */
    fun onHeard(t: String) {
        listening = false
        if (t.isNotEmpty() && Commands.norm(t) in STOP.map(Commands::norm)) {
            handsFree = false
            if (live) { liveStopped = true; endLive("रुको"); show("🤖 रुक गया। फिर से: 🤖 दबाएँ") } else show("🎤 बंद")
            return
        }
        speaking = false
        if (live) {
            if (t.isNotEmpty()) { lastHeardAt = System.currentTimeMillis(); if (heardQ.size < 40) heardQ.add(t); show("👂 $t"); answerListen() }
            again(); return
        }
        if (t.isNotEmpty()) { lastHeardAt = System.currentTimeMillis(); handle(t) } else show("कुछ सुनाई नहीं दिया")
        if (tts == null || !ttsReady || lastMessage.startsWith("✅").not()) again() // after a read-back, TTS restarts listening
    }

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
                val tx = org.json.JSONArray(); val seen = HashSet<String>()
                for (b in boxes()) seen.add(b.label); for (o in options()) seen.add(o.label)
                for (t in texts()) if (seen.add(t.label) && tx.length() < 60) tx.put(t.label)
                out.put("boxes", bx).put("options", op).put("texts", tx)
                    .put("how", "a box that is a dropdown: app_choose(its label) opens it, then app_screen shows the list, then app_choose(the entry)")
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
                // owner, 28 Nov: "dropdown खोल सके, खुद tap करके आगे का काम करे". Any option, any box (a dropdown
                // opens), or any text on screen (an entry of an open list); call app_screen again to see what opened.
                val t = a.optString("text")
                if (Commands.needsConfirm(t) || Commands.needsOwnerYes(t)) { out.put("error", "\"$t\" moves the form on: use app_press"); return out }
                val opt = options().filter { it.label == t }
                val box = boxes().filter { it.label == t }
                val txt = texts().filter { it.label == t }
                val pick = when { opt.size == 1 -> opt[0]; opt.size > 1 -> null; box.size == 1 -> box[0]; box.size > 1 -> null; txt.size == 1 -> txt[0]; else -> null }
                if (pick == null) {
                    val n = maxOf(opt.size, box.size, txt.size)
                    out.put("error", if (n == 0) "nothing called \"$t\" on screen (call app_screen; scroll if needed)" else "\"$t\" is on screen $n times: ask the owner to tap the right one")
                } else {
                    val ok = clickUp(pick.node) || tapAt(pick.node)
                    out.put(if (ok) "done" else "error", if (ok) "tapped $t: call app_screen to see what opened" else "\"$t\" did not respond")
                    Log.i(TAG, "census bridge choose: ${if (ok) "ok" else "refused"}")
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
        robot = btn("🤖") { toggleLive() }.apply { tag = "census-live"; setBackgroundColor(Color.DKGRAY); setTextColor(Color.WHITE) }
        p.addView(robot)
        yes = btn("✓") { confirmByHand() }.apply { visibility = android.view.View.GONE; setBackgroundColor(Color.rgb(27, 127, 58)); setTextColor(Color.WHITE) }
        no = btn("✕") { handle("नहीं") }.apply { visibility = android.view.View.GONE }
        p.addView(yes); p.addView(no); p.addView(said)
        try { wm.addView(p, lp); panel = p } catch (e: Exception) { Log.i(TAG, "census panel: ${e.javaClass.simpleName}") }
    }

    private var robot: Button? = null
    val panelText: String get() = if (panel != null && said.visibility == android.view.View.VISIBLE) said.text.toString() else ""
    private val hideSaid = Runnable { said.visibility = android.view.View.GONE }
    private fun show(m: String, ms: Long = 8000) {
        if (panel == null) return
        said.text = m; said.visibility = android.view.View.VISIBLE
        main.removeCallbacks(hideSaid); main.postDelayed(hideSaid, ms)
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
        // C went away (chat closed, limit, no internet): stop the mic, the fixed commands still work
        if (live && waiter == null && System.currentTimeMillis() - lastListenAt > 120_000) { endLive("C is gone"); show("🤖 C का जवाब नहीं आ रहा: chat देखें। 🎤 से पुराना तरीका चलेगा"); return }
        if ((handsFree || live) && !speaking) main.postDelayed({ if ((handsFree || live) && !listening && !speaking) listen() }, 500)
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
                onHeard(b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
            }
            override fun onError(error: Int) {
                listening = false
                if (live && error != SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE && error != SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED) {
                    if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) { Log.i(TAG, "census speech error $error"); show("🎤 सुनने में दिक़्क़त ($error): फिर कोशिश…") }
                    main.postDelayed({ again() }, if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) 0 else 2000); return
                }
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
            override fun onReadyForSpeech(p: Bundle?) { if (!live) show("🎤 बोलिए…") }
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
        listening = true
        r.startListening(i)
    }
}
