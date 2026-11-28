package com.ronin.census

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.SpeechRecognizer
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** One screen: the three things to switch on once, each with one big button, and whether each is done. */
class SetupActivity : Activity() {
    private lateinit var box: LinearLayout
    private var hindi = "देख रहे हैं…"
    private var checked = false

    private fun hindiIntent() = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
        .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)

    private fun checkHindi() {
        if (checked || Build.VERSION.SDK_INT < 33) return
        checked = true
        val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        r.checkRecognitionSupport(hindiIntent(), mainExecutor, object : android.speech.RecognitionSupportCallback {
            override fun onSupportResult(s: android.speech.RecognitionSupport) {
                val hi = { l: List<String> -> l.any { it.lowercase().startsWith("hi") } }
                hindi = when {
                    hi(s.installedOnDeviceLanguages) -> "✅ इसी phone पर है"
                    hi(s.pendingOnDeviceLanguages) -> "⏳ download हो रहा है"
                    hi(s.supportedOnDeviceLanguages) -> "❌ अभी नहीं है (download कर सकते हैं)"
                    else -> "❌ इस phone का recogniser हिन्दी offline नहीं देता: Gboard 🎤 इस्तेमाल करें"
                }
                r.destroy(); draw()
            }
            override fun onError(e: Int) { hindi = "जाँच नहीं हो पाई ($e)"; r.destroy(); draw() }
        })
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 60, 40, 40) }
        setContentView(ScrollView(this).apply { addView(box) })
    }

    override fun onResume() { super.onResume(); checked = false; draw() }

    private fun draw() {
        box.removeAllViews()
        fun text(t: String, size: Float = 17f, color: Int = Color.BLACK) = box.addView(TextView(this).apply { text = t; textSize = size; setTextColor(color); setPadding(0, 16, 0, 16) })
        fun button(t: String, f: () -> Unit) = box.addView(Button(this).apply { text = t; textSize = 18f; isAllCaps = false; setOnClickListener { f() } }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        text("जनगणना सहायक", 24f)
        text("आप बोलें, सहायक जनगणना app के सही खाने में भरेगा। सब कुछ इसी phone पर रहता है: इस app को internet की अनुमति ही नहीं है।", 16f, Color.DKGRAY)

        val mic = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        text((if (mic) "✅" else "1️⃣") + " माइक की अनुमति")
        if (!mic) button("माइक की अनुमति दें") { requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 1) }

        val offline = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        text((if (offline) "✅" else "2️⃣") + " इसी phone पर आवाज़ पहचान (offline)")
        if (Build.VERSION.SDK_INT >= 33 && offline) {
            // is हिन्दी installed on the phone? (asked of Android's recogniser; nothing leaves the phone from this app)
            text("हिन्दी pack: $hindi", 15f, Color.DKGRAY)
            if (hindi != "✅ इसी phone पर है") button("हिन्दी pack download करें (Wi-Fi पर, एक बार)") {
                val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
                r.triggerModelDownload(hindiIntent()); hindi = "download शुरू: कुछ मिनट बाद यह screen फिर खोलें"; draw()
            }
            checkHindi()
        }
        if (!offline || hindi.startsWith("❌")) text("ठीक है: offline हिन्दी नहीं होने पर सहायक internet से (Google की आवाज़ पहचान) सुनेगा। 🎤 एक बार दबाएँ = एक वाक्य; देर तक दबाएँ = लगातार सुनना।", 15f, Color.DKGRAY)

        val on = CensusService.instance != null
        text((if (on) "✅" else "3️⃣") + " सहायक चालू (Accessibility)")
        if (!on) {
            text("नीचे का बटन दबाएँ → 'जनगणना सहायक' चुनें → चालू करें → अनुमति दें।", 15f, Color.DKGRAY)
            button("Accessibility खोलें") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        if (mic && on) text("सब तैयार ✅ जनगणना app खोलें; दाईं तरफ़ 🎤 दबाकर बोलें, जैसे: \"मोहल्ला पूरब टोला\"। \"आगे बढ़ें\" बोलने पर सहायक पूछेगा, \"हाँ\" बोलें।", 16f, Color.rgb(27, 127, 58))
    }
}
