package com.ronin.browser

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Reads the page aloud with the phone's own voices: Hindi text with the Hindi
 * voice, English with the Indian English voice. Pause keeps the place.
 */
class ReadAloud(ctx: Context, private val onState: (playing: Boolean, index: Int, total: Int) -> Unit) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var parts: List<String> = emptyList()
    private var index = 0
    var playing = false; private set
    var rate = 1.0f; private set
    private var pendingStart = false

    init {
        tts = TextToSpeech(ctx.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {
                    index = id?.removeSuffix("!")?.toIntOrNull() ?: index
                    onState(true, index, parts.size)
                }
                override fun onDone(id: String?) {
                    if (id?.endsWith("!") == true) { playing = false; index = 0; onState(false, 0, parts.size) }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) {}
            })
            if (ready && pendingStart) { pendingStart = false; speakFrom(index) }
        }
    }

    fun start(paragraphs: List<String>) {
        parts = paragraphs.filter { it.isNotBlank() }
        index = 0
        play()
    }

    fun play() {
        if (parts.isEmpty()) return
        playing = true
        if (!ready) { pendingStart = true; return }
        speakFrom(index)
    }

    fun pause() {
        playing = false
        tts?.stop()
        onState(false, index, parts.size)
    }

    fun stop() {
        playing = false
        index = 0
        tts?.stop()
        onState(false, 0, 0)
    }

    fun nextRate(): Float {
        val rates = floatArrayOf(1.0f, 1.25f, 1.5f, 0.8f)
        rate = rates[(rates.indexOf(rate) + 1) % rates.size]
        if (playing) { tts?.stop(); speakFrom(index) }
        return rate
    }

    fun hindiMissing(): Boolean {
        val t = tts ?: return false
        return ready && t.isLanguageAvailable(Locale("hi", "IN")) < TextToSpeech.LANG_AVAILABLE
    }

    private fun speakFrom(from: Int) {
        val t = tts ?: return
        t.setSpeechRate(rate)
        var first = true
        for (i in from until parts.size) {
            val ps = pieces(parts[i])
            for ((k, piece) in ps.withIndex()) {
                val id = if (i == parts.size - 1 && k == ps.size - 1) "$i!" else i.toString()
                // the language is taken with each queued piece
                t.language = if (isHindi(piece)) Locale("hi", "IN") else Locale("en", "IN")
                t.speak(piece, if (first) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
                first = false
            }
        }
    }

    fun shutdown() { tts?.shutdown(); tts = null }

    companion object {
        fun isHindi(t: String): Boolean {
            val dev = t.count { it in 'ऀ'..'ॿ' }
            val lat = t.count { it in 'a'..'z' || it in 'A'..'Z' }
            return dev > 0 && dev >= lat
        }

        /** Long paragraphs in sentence-sized pieces (some voices stop on very long text). */
        fun pieces(t: String): List<String> {
            val out = ArrayList<String>()
            val sb = StringBuilder()
            for (s in t.split(Regex("(?<=[.!?।])\\s+"))) {
                if (sb.length + s.length > 220 && sb.isNotEmpty()) { out.add(sb.toString()); sb.setLength(0) }
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(s)
            }
            if (sb.isNotEmpty()) out.add(sb.toString())
            return out
        }
    }
}
