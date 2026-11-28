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
    lateinit var members: EditText
    @Volatile var use = ""

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        l.addView(TextView(this).apply { text = "जनगणना मकान की गणना" })
        head = EditText(this).apply { hint = "परिवार के मुखिया का नाम" }; l.addView(head)
        remarks = EditText(this).apply { hint = "टिप्पणियाँ" }; l.addView(remarks)
        mohalla = EditText(this).apply { hint = "मुहल्ला/बस्ती" }; l.addView(mohalla)
        // a box with its label ABOVE it (no hint), as Flutter screens often draw it
        l.addView(TextView(this).apply { text = "सदस्यों की संख्या" })
        members = EditText(this).apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }; l.addView(members)
        // a dropdown like the Census app's "मकान का वर्तमान उपयोग": a box that opens a list in its own popup window
        // label on the left, box on its right: one row, so the form still fits a landscape tablet
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(TextView(this).apply { text = "मकान का वर्तमान उपयोग" })
        val drop = EditText(this).apply { isFocusable = false; isClickable = true; hint = "चुनें" }
        drop.setOnClickListener {
            val pop = android.widget.ListPopupWindow(this)
            val items = listOf("निवास", "दुकान/कार्यालय", "स्कूल/कॉलेज")
            pop.setAdapter(android.widget.ArrayAdapter(this, android.R.layout.simple_list_item_1, items))
            pop.anchorView = drop; pop.isModal = true
            pop.setOnItemClickListener { _, _, i, _ -> use = items[i]; drop.setText(items[i]); pop.dismiss() }
            pop.show()
        }
        row.addView(drop, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)); l.addView(row)
        for (o in listOf("आवासीय", "आवासीय-सह-अन्य उपयोग", "खाली")) l.addView(Button(this).apply { text = o; setOnClickListener { chosen = o } })
        l.addView(Button(this).apply { text = "आगे बढ़ें"; setOnClickListener { nextPressed++ } })
        setContentView(l)
    }
}
