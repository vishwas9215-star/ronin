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

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 60, 40, 40) }
        setContentView(ScrollView(this).apply { addView(box) })
    }

    override fun onResume() { super.onResume(); draw() }

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
        if (!offline) text("Google app → Settings → Voice → Offline speech recognition → हिन्दी download करें। तब तक Gboard के 🎤 से बोलकर लिखें।", 15f, Color.DKGRAY)

        val on = CensusService.instance != null
        text((if (on) "✅" else "3️⃣") + " सहायक चालू (Accessibility)")
        if (!on) {
            text("नीचे का बटन दबाएँ → 'जनगणना सहायक' चुनें → चालू करें → अनुमति दें।", 15f, Color.DKGRAY)
            button("Accessibility खोलें") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        if (mic && on) text("सब तैयार ✅ जनगणना app खोलें; दाईं तरफ़ 🎤 दबाकर बोलें, जैसे: \"मोहल्ला पूरब टोला\"। \"आगे बढ़ें\" बोलने पर सहायक पूछेगा, \"हाँ\" बोलें।", 16f, Color.rgb(27, 127, 58))
    }
}
