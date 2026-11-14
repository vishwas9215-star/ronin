package com.ronin.browser

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * "Data -> Excel + PDF" (same as Ronin for Windows, internal/tablexport): the table read by
 * table_export.js becomes an Excel file and a coloured PDF report.
 */
class TableExport(json: String) {
    val title: String
    val site: String
    val head: List<String>
    val rows: List<List<String>>
    private val issues = HashMap<Pair<Int, Int>, Pair<String, String>>()
    val counts = HashMap<String, Int>()
    /** Why not every page was read ("page 7 did not load"); empty = complete. */
    val partial: String
    /** "डेटा मिलाओ" sends its own summary lines, colour legend and the columns for the PDF. */
    val info: List<Pair<String, String>>
    private val legendIn: List<Pair<String, String>>
    val printCols: Set<Int>
    val printNote: String
    val legend get() = legendIn.ifEmpty { listOf("dup" to "पीला: दोहराया", "similar" to "नारंगी: spelling जाँचें", "spell" to "लाल: लिखावट में ग़लती") }

    init {
        val o = JSONObject(json)
        title = o.optString("title")
        site = o.optString("site")
        head = o.getJSONArray("head").let { a -> (0 until a.length()).map { a.getString(it) } }
        rows = o.getJSONArray("rows").let { a -> (0 until a.length()).map { i -> a.getJSONArray(i).let { r -> (0 until r.length()).map { r.optString(it) } } } }
        require(rows.isNotEmpty()) { "the table is empty" }
        val ch = o.optJSONObject("check") ?: JSONObject()
        ch.optJSONArray("cells")?.let { a ->
            for (i in 0 until a.length()) {
                val c = a.getJSONArray(i)
                issues[c.getInt(0) to c.getInt(1)] = c.getString(2) to c.getString(3)
            }
        }
        ch.optJSONObject("counts")?.let { c -> c.keys().forEach { counts[it] = c.optInt(it) } }
        partial = o.optString("partial")
        fun pairs(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getJSONArray(it).let { p -> p.optString(0) to p.optString(1) } } } ?: emptyList()
        info = pairs("info"); legendIn = pairs("legend")
        printCols = o.optJSONArray("printCols")?.let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() } ?: emptySet()
        printNote = o.optString("printNote")
    }

    fun issue(r: Int, c: Int) = issues[r to c]

    /** The most important mark in a row (red, orange, yellow) - colours the Check cell. */
    fun rowKind(r: Int): String? {
        val rank = mapOf("dup" to 1, "dupname" to 1, "only" to 2, "similar" to 3, "diff" to 4, "spell" to 5)
        var best: String? = null
        for (c in head.indices) issue(r, c)?.first?.let { if ((rank[it] ?: 0) > (rank[best ?: ""] ?: 0)) best = it }
        return best
    }

    /** Columns worth printing; a column with the same value in every row is printed once above the table. */
    fun printColumns(): Pair<List<Int>, List<Pair<String, String>>> {
        val keep = ArrayList<Int>(); val same = ArrayList<Pair<String, String>>()
        head.forEachIndexed { c, h ->
            if (printCols.isNotEmpty() && c !in printCols) return@forEachIndexed
            val first = rows.first().getOrElse(c) { "" }.trim()
            val constant = rows.size > 1 && rows.all { it.getOrElse(c) { "" }.trim() == first }
            if (constant) { if (first.isNotEmpty() && first != "-") same.add(h to first) } else keep.add(c)
        }
        return keep to same
    }

    fun rowNotes(r: Int): String {
        val out = LinkedHashSet<String>()
        for (c in head.indices) issue(r, c)?.let { (kind, note) -> out.add(if (kind != "dup" && head[c].isNotEmpty()) "${head[c]}: $note" else note) }
        return out.joinToString("; ")
    }

    fun summary(): List<Pair<String, String>> = if (info.isNotEmpty()) listOf(
        "Page / पेज" to title,
        "Made / बनाया" to SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.US).format(Date()),
    ) + info else listOf(
        "Page / पेज" to title,
        "Site" to site,
        "Made / बनाया" to SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.US).format(Date()),
        "Rows / कुल पंक्तियाँ" to rows.size.toString(),
        "Repeated rows / दोहराई पंक्तियाँ" to (counts["dupRows"] ?: 0).toString(),
        "Repeated names / दोहराए नाम" to (counts["dupNames"] ?: 0).toString(),
        "Look-alike names / मिलते-जुलते नाम" to (counts["similar"] ?: 0).toString(),
        "Possible spelling mistakes / संभावित ग़लतियाँ" to (counts["spell"] ?: 0).toString(),
        "Complete / पूरा" to (if (partial.isEmpty()) "हाँ, सारे पेज पढ़े गए / yes, every page" else "अधूरा! / NOT complete: $partial"),
    )

    // ---- Excel -------------------------------------------------------------------------

    private fun styleOf(kind: String?) = when (kind) { "dup", "dupname" -> 2; "spell" -> 3; "similar" -> 4; "diff" -> 5; "only" -> 6; else -> 0 }

    private fun esc(s: String): String {
        val b = StringBuilder()
        for (ch in s) {
            when {
                ch == '&' -> b.append("&amp;"); ch == '<' -> b.append("&lt;"); ch == '>' -> b.append("&gt;")
                ch == '"' -> b.append("&quot;")
                ch == '\t' || ch == '\n' || ch == '\r' || ch.code >= 0x20 -> b.append(ch)
            }
        }
        return b.toString()
    }

    fun xlsx(): ByteArray {
        val n = head.size
        val sh = StringBuilder("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews><cols>""")
        sh.append("""<col min="1" max="1" width="48" customWidth="1"/>""")
        for (c in 0 until n) {
            var w = maxOf(10, minOf(24, head[c].length + 2))
            rows.forEach { r -> if (c < r.size && r[c].length + 2 > w) w = r[c].length + 2 }
            if (w > 50) w = 50
            sh.append("""<col min="${c + 2}" max="${c + 2}" width="$w" customWidth="1"/>""")
        }
        sh.append("</cols><sheetData>")
        fun cell(row: Int, col: Int, style: Int, v: String, data: Boolean) {
            if (row > 1 && data && isNumber(v)) { sh.append("""<c r="${colName(col)}$row" s="$style"><v>$v</v></c>"""); return }
            sh.append("""<c r="${colName(col)}$row" t="inlineStr" s="$style"><is><t xml:space="preserve">${esc(v)}</t></is></c>""")
        }
        sh.append("""<row r="1">"""); cell(1, 0, 1, "जाँच / Check", false); head.forEachIndexed { c, h -> cell(1, c + 1, 1, h, false) }; sh.append("</row>")
        rows.forEachIndexed { r, row ->
            sh.append("""<row r="${r + 2}">""")
            cell(r + 2, 0, styleOf(rowKind(r)), rowNotes(r), false)
            for (c in 0 until n) cell(r + 2, c + 1, styleOf(issue(r, c)?.first), row.getOrElse(c) { "" }, true)
            sh.append("</row>")
        }
        sh.append("""</sheetData><autoFilter ref="A1:${colName(n)}${rows.size + 1}"/></worksheet>""")

        val sum = StringBuilder("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><cols><col min="1" max="1" width="44" customWidth="1"/><col min="2" max="2" width="70" customWidth="1"/></cols><sheetData>""")
        var sr = 0
        fun line(a: String, b: String, style: Int) {
            sr++
            sum.append("""<row r="$sr"><c r="A$sr" t="inlineStr" s="$style"><is><t>${esc(a)}</t></is></c><c r="B$sr" t="inlineStr"><is><t>${esc(b)}</t></is></c></row>""")
        }
        summary().forEach { line(it.first, it.second, 1) }
        legend.forEach { line(it.second, "", styleOf(it.first)) }
        sum.append("</sheetData></worksheet>")

        val files = linkedMapOf(
            "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>""",
            "_rels/.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""",
            "xl/workbook.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Data" sheetId="1" r:id="rId1"/><sheet name="Summary" sheetId="2" r:id="rId2"/></sheets><definedNames><definedName name="_xlnm._FilterDatabase" localSheetId="0" hidden="1">Data!${'$'}A${'$'}1:${'$'}${colName(n)}${'$'}${rows.size + 1}</definedName></definedNames></workbook>""",
            "xl/_rels/workbook.xml.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/><Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""",
            "xl/styles.xml" to STYLES,
            "xl/worksheets/sheet1.xml" to sh.toString(),
            "xl/worksheets/sheet2.xml" to sum.toString(),
        )
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z -> files.forEach { (name, body) -> z.putNextEntry(ZipEntry(name)); z.write(body.toByteArray(Charsets.UTF_8)); z.closeEntry() } }
        return bos.toByteArray()
    }

    // ---- PDF (landscape A4, the same colours) -----------------------------------------------

    fun pdf(): ByteArray {
        val pw = 842; val ph = 595; val m = 24f
        val doc = PdfDocument()
        val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 7.5f; color = Color.BLACK }
        val bold = TextPaint(text).apply { isFakeBoldText = true }
        val big = TextPaint(bold).apply { textSize = 12f }
        val line = Paint().apply { color = Color.parseColor("#999999"); strokeWidth = 0.5f; style = Paint.Style.STROKE }
        val fill = Paint().apply { style = Paint.Style.FILL }
        val (keep, same) = printColumns()
        // the Check column comes first (it says why a cell is coloured), then the useful columns
        val cols = keep.size + 1
        var checkW = 8f
        rows.indices.forEach { r -> checkW = maxOf(checkW, rowNotes(r).length / 3f) }
        checkW = minOf(checkW, 22f)
        val weights = FloatArray(cols) { i ->
            if (i == 0) checkW else {
                val c = keep[i - 1]
                var w = maxOf(7f, head[c].split(" ").maxOf { it.length }.toFloat())
                rows.take(300).forEach { r -> w = maxOf(w, r.getOrElse(c) { "" }.length.toFloat()) }
                w.coerceAtMost(28f)
            }
        }
        val total = weights.sum()
        val widths = FloatArray(cols) { (pw - 2 * m) * weights[it] / total }
        fun layout(s: String, p: TextPaint, w: Float) =
            StaticLayout.Builder.obtain(s, 0, s.length, p, maxOf(10, (w - 6).toInt())).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
        fun color(kind: String?) = when (kind) { "dup", "dupname" -> "#FFF2A8"; "spell" -> "#F8B4B4"; "similar" -> "#FFD3A1"; "diff" -> "#BDD7EE"; "only" -> "#DCDCDC"; else -> null }

        var pageNo = 0
        var page: PdfDocument.Page? = null
        var y = 0f
        fun drawRow(cells: List<String>, kinds: List<String?>, p: TextPaint, headBg: Boolean) {
            val ls = cells.mapIndexed { c, s -> layout(s, p, widths[c]) }
            val h = (ls.maxOf { it.height } + 6).toFloat()
            if (page == null || y + h > ph - m) {
                page?.let { doc.finishPage(it) }
                pageNo++
                page = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, pageNo).create())
                y = m
                val cv = page!!.canvas
                if (pageNo == 1) {
                    val t = layout(title, big, pw - 2 * m); cv.save(); cv.translate(m, y); t.draw(cv); cv.restore(); y += t.height + 4
                    var s = summary().drop(1).joinToString("  ·  ") { "${it.first}: ${it.second}" } +
                        "\n" + legend.joinToString(" · ") { it.second } + ". पहले कॉलम \"जाँच\" में हर रंग का कारण लिखा है।" +
                        (if (printNote.isNotEmpty()) "\n$printNote" else "")
                    if (same.isNotEmpty()) s += "\nसभी पंक्तियों में एक जैसा: " + same.joinToString(" · ") { "${it.first}: ${it.second}" }
                    val sl = layout(s, text, pw - 2 * m); cv.save(); cv.translate(m, y); sl.draw(cv); cv.restore(); y += sl.height + 8
                }
                if (!headBg) drawHeader()
            }
            val cv = page!!.canvas
            var x = m
            cells.indices.forEach { c ->
                val bg = if (headBg) "#DDE6F5" else color(kinds[c])
                if (bg != null) { fill.color = Color.parseColor(bg); cv.drawRect(x, y, x + widths[c], y + h, fill) }
                cv.drawRect(x, y, x + widths[c], y + h, line)
                cv.save(); cv.translate(x + 3, y + 3); ls[c].draw(cv); cv.restore()
                x += widths[c]
            }
            y += h
        }
        fun header() = drawRow(listOf("जाँच / Check") + keep.map { head[it] }, List(cols) { null }, bold, true)
        drawHeaderRef = { header() }
        rows.forEachIndexed { r, row ->
            drawRow(listOf(rowNotes(r)) + keep.map { row.getOrElse(it) { "" } }, listOf(rowKind(r)) + keep.map { issue(r, it)?.first }, text, false)
        }
        page?.let { doc.finishPage(it) }
        val bos = ByteArrayOutputStream()
        doc.writeTo(bos); doc.close()
        return bos.toByteArray()
    }

    private var drawHeaderRef: () -> Unit = {}
    private fun drawHeader() = drawHeaderRef()

    companion object {
        /** Marks and amounts become numbers; Aadhaar, phone and codes (long or with a leading 0) stay text. Same as Windows. */
        fun isNumber(v: String): Boolean {
            if (v.isEmpty() || v.length > 9 || (v.length > 1 && v[0] == '0' && v[1] != '.')) return false
            var dot = false
            v.forEachIndexed { i, c ->
                when {
                    c in '0'..'9' -> {}
                    c == '.' && !dot && i > 0 && i < v.length - 1 -> dot = true
                    c == '-' && i == 0 && v.length > 1 -> {}
                    else -> return false
                }
            }
            return true
        }

        fun colName(i0: Int): String {
            var i = i0 + 1; var s = ""
            while (i > 0) { s = ('A' + (i - 1) % 26).toString() + s; i = (i - 1) / 26 }
            return s
        }

        private const val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts><fills count="8"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FFDDE6F5"/></patternFill></fill><fill><patternFill patternType="solid"><fgColor rgb="FFFFF2A8"/></patternFill></fill><fill><patternFill patternType="solid"><fgColor rgb="FFF8B4B4"/></patternFill></fill><fill><patternFill patternType="solid"><fgColor rgb="FFFFD3A1"/></patternFill></fill><fill><patternFill patternType="solid"><fgColor rgb="FFBDD7EE"/></patternFill></fill><fill><patternFill patternType="solid"><fgColor rgb="FFDCDCDC"/></patternFill></fill></fills><borders count="2"><border><left/><right/><top/><bottom/><diagonal/></border><border><left style="thin"><color rgb="FFBFBFBF"/></left><right style="thin"><color rgb="FFBFBFBF"/></right><top style="thin"><color rgb="FFBFBFBF"/></top><bottom style="thin"><color rgb="FFBFBFBF"/></bottom><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="7"><xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1"/><xf numFmtId="0" fontId="1" fillId="2" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1"/><xf numFmtId="0" fontId="0" fillId="3" borderId="1" xfId="0" applyFill="1" applyBorder="1"/><xf numFmtId="0" fontId="0" fillId="4" borderId="1" xfId="0" applyFill="1" applyBorder="1"/><xf numFmtId="0" fontId="0" fillId="5" borderId="1" xfId="0" applyFill="1" applyBorder="1"/><xf numFmtId="0" fontId="0" fillId="6" borderId="1" xfId="0" applyFill="1" applyBorder="1"/><xf numFmtId="0" fontId="0" fillId="7" borderId="1" xfId="0" applyFill="1" applyBorder="1"/></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>"""
    }
}
