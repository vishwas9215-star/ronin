package com.ronin.census

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Device tests: boxes with hints (like the Census app), a choice list and an "आगे बढ़ें" button. */
class TestFormActivity : Activity() {
    @Volatile var nextPressed = 0
    @Volatile var chosen = ""
    lateinit var mohalla: EditText
    lateinit var remarks: EditText
    lateinit var head: EditText

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        l.addView(TextView(this).apply { text = "जनगणना मकान की गणना" })
        head = EditText(this).apply { hint = "परिवार के मुखिया का नाम" }; l.addView(head)
        remarks = EditText(this).apply { hint = "टिप्पणियाँ" }; l.addView(remarks)
        mohalla = EditText(this).apply { hint = "मुहल्ला/बस्ती" }; l.addView(mohalla)
        for (o in listOf("आवासीय", "आवासीय-सह-अन्य उपयोग", "खाली")) l.addView(Button(this).apply { text = o; setOnClickListener { chosen = o } })
        l.addView(Button(this).apply { text = "आगे बढ़ें"; setOnClickListener { nextPressed++ } })
        setContentView(l)
    }
}
