// Package tablexport turns a table read from a web page (by ui/table_export.js)
// into an Excel file and a printable report, with repeated rows / names in
// yellow, look-alike names in orange and likely spelling mistakes in red.
package tablexport

import (
	"archive/zip"
	"bytes"
	"encoding/json"
	"fmt"
	"html"
	"strings"
	"time"
)

// Data is what the page script returns.
type Data struct {
	Title string     `json:"title"`
	Site  string     `json:"site"`
	Head  []string   `json:"head"`
	Rows  [][]string `json:"rows"`
	Check struct {
		Cells  [][]any        `json:"cells"` // [row, col, kind, note]
		Counts map[string]int `json:"counts"`
	} `json:"check"`
}

// Issue is one marked cell.
type Issue struct{ Kind, Note string }

// Parse reads the page script's JSON.
func Parse(s string) (*Data, error) {
	var d Data
	if err := json.Unmarshal([]byte(s), &d); err != nil {
		return nil, err
	}
	if len(d.Rows) == 0 {
		return nil, fmt.Errorf("the table is empty")
	}
	return &d, nil
}

// Issues gives the marked cells by "row,col".
func (d *Data) Issues() map[[2]int]Issue {
	m := map[[2]int]Issue{}
	for _, c := range d.Check.Cells {
		if len(c) < 4 {
			continue
		}
		r, ok1 := c[0].(float64)
		col, ok2 := c[1].(float64)
		k, _ := c[2].(string)
		n, _ := c[3].(string)
		if ok1 && ok2 {
			m[[2]int{int(r), int(col)}] = Issue{k, n}
		}
	}
	return m
}

// RowNotes joins the notes of one row (for the "जाँच / Check" column).
func (d *Data) RowNotes(iss map[[2]int]Issue, r int) string {
	var notes []string
	seen := map[string]bool{}
	for c := range d.Head {
		if is, ok := iss[[2]int{r, c}]; ok {
			n := is.Note
			if c < len(d.Head) && d.Head[c] != "" && is.Kind != "dup" {
				n = d.Head[c] + ": " + n
			}
			if !seen[n] {
				seen[n] = true
				notes = append(notes, n)
			}
		}
	}
	return strings.Join(notes, "; ")
}

// style numbers in styles.xml: 0 normal, 1 header, 2 yellow, 3 red, 4 orange
func styleOf(kind string) int {
	switch kind {
	case "dup", "dupname":
		return 2
	case "spell":
		return 3
	case "similar":
		return 4
	}
	return 0
}

func colName(i int) string {
	s := ""
	for i++; i > 0; i = (i - 1) / 26 {
		s = string(rune('A'+(i-1)%26)) + s
	}
	return s
}

func xmlText(s string) string {
	var b strings.Builder
	for _, r := range s {
		if r == '\t' || r == '\n' || r == '\r' || r >= 0x20 && r != 0xFFFE && r != 0xFFFF {
			b.WriteRune(r)
		}
	}
	return html.EscapeString(b.String())
}

// XLSX builds the Excel file: sheet "Data" (all rows, colours, a Check column)
// and sheet "Summary".
func (d *Data) XLSX() ([]byte, error) {
	iss := d.Issues()
	var sheet strings.Builder
	sheet.WriteString(`<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews><cols>`)
	ncol := len(d.Head)
	for c := 0; c <= ncol; c++ {
		w := 14
		if c < ncol {
			for _, r := range d.Rows {
				if c < len(r) && len([]rune(r[c]))+2 > w {
					w = len([]rune(r[c])) + 2
				}
			}
		} else {
			w = 50
		}
		if w > 60 {
			w = 60
		}
		fmt.Fprintf(&sheet, `<col min="%d" max="%d" width="%d" customWidth="1"/>`, c+1, c+1, w)
	}
	sheet.WriteString(`</cols><sheetData>`)
	cell := func(row, col, style int, v string) {
		fmt.Fprintf(&sheet, `<c r="%s%d" t="inlineStr" s="%d"><is><t xml:space="preserve">%s</t></is></c>`, colName(col), row, style, xmlText(v))
	}
	sheet.WriteString(`<row r="1">`)
	for c, h := range d.Head {
		cell(1, c, 1, h)
	}
	cell(1, ncol, 1, "जाँच / Check")
	sheet.WriteString(`</row>`)
	for r, row := range d.Rows {
		fmt.Fprintf(&sheet, `<row r="%d">`, r+2)
		for c := 0; c < ncol; c++ {
			v := ""
			if c < len(row) {
				v = row[c]
			}
			cell(r+2, c, styleOf(iss[[2]int{r, c}].Kind), v)
		}
		cell(r+2, ncol, 0, d.RowNotes(iss, r))
		sheet.WriteString(`</row>`)
	}
	fmt.Fprintf(&sheet, `</sheetData><autoFilter ref="A1:%s%d"/></worksheet>`, colName(ncol), len(d.Rows)+1)

	var sum strings.Builder
	sum.WriteString(`<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><cols><col min="1" max="1" width="44" customWidth="1"/><col min="2" max="2" width="70" customWidth="1"/></cols><sheetData>`)
	srow := 0
	line := func(a, b string, style int) {
		srow++
		fmt.Fprintf(&sum, `<row r="%d"><c r="A%d" t="inlineStr" s="%d"><is><t>%s</t></is></c><c r="B%d" t="inlineStr"><is><t>%s</t></is></c></row>`, srow, srow, style, xmlText(a), srow, xmlText(b))
	}
	for _, l := range d.SummaryLines() {
		line(l[0], l[1], 1)
	}
	line("पीला / Yellow", "दोहराई गई पंक्ति या दोहराया नाम", 2)
	line("नारंगी / Orange", "मिलता-जुलता नाम (शायद एक की spelling ग़लत)", 4)
	line("लाल / Red", "spelling या लिखावट में ग़लती की संभावना", 3)
	sum.WriteString(`</sheetData></worksheet>`)

	files := map[string]string{
		"[Content_Types].xml": `<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>`,
		"_rels/.rels": `<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>`,
		"xl/workbook.xml": `<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Data" sheetId="1" r:id="rId1"/><sheet name="Summary" sheetId="2" r:id="rId2"/></sheets><definedNames><definedName name="_xlnm._FilterDatabase" localSheetId="0" hidden="1">Data!$A$1:$` + colName(ncol) + `$` + fmt.Sprint(len(d.Rows)+1) + `</definedName></definedNames></workbook>`,
		"xl/_rels/workbook.xml.rels": `<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/><Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>`,
		"xl/styles.xml": `<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts><fills count="6"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FFDDE6F5"/></patternFill></fill><fill><patternFill patternType="solid"><fgColor rgb="FFFFF2A8"/></patternFill></fill><fill><patternFill patternType="solid"><fgColor rgb="FFF8B4B4"/></patternFill></fill><fill><patternFill patternType="solid"><fgColor rgb="FFFFD3A1"/></patternFill></fill></fills><borders count="2"><border><left/><right/><top/><bottom/><diagonal/></border><border><left style="thin"><color rgb="FFBFBFBF"/></left><right style="thin"><color rgb="FFBFBFBF"/></right><top style="thin"><color rgb="FFBFBFBF"/></top><bottom style="thin"><color rgb="FFBFBFBF"/></bottom><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="5"><xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1"/><xf numFmtId="0" fontId="1" fillId="2" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1"/><xf numFmtId="0" fontId="0" fillId="3" borderId="1" xfId="0" applyFill="1" applyBorder="1"/><xf numFmtId="0" fontId="0" fillId="4" borderId="1" xfId="0" applyFill="1" applyBorder="1"/><xf numFmtId="0" fontId="0" fillId="5" borderId="1" xfId="0" applyFill="1" applyBorder="1"/></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>`,
		"xl/worksheets/sheet1.xml": sheet.String(),
		"xl/worksheets/sheet2.xml": sum.String(),
	}
	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	for _, name := range []string{"[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/_rels/workbook.xml.rels", "xl/styles.xml", "xl/worksheets/sheet1.xml", "xl/worksheets/sheet2.xml"} {
		w, err := zw.Create(name)
		if err != nil {
			return nil, err
		}
		if _, err := w.Write([]byte(files[name])); err != nil {
			return nil, err
		}
	}
	if err := zw.Close(); err != nil {
		return nil, err
	}
	return buf.Bytes(), nil
}

// SummaryLines are the counts in Hindi and English.
func (d *Data) SummaryLines() [][2]string {
	c := d.Check.Counts
	return [][2]string{
		{"Page / पेज", d.Title},
		{"Site", d.Site},
		{"Made / बनाया", time.Now().Format("02-01-2006 15:04")},
		{"Rows / कुल पंक्तियाँ", fmt.Sprint(len(d.Rows))},
		{"Repeated rows / दोहराई पंक्तियाँ", fmt.Sprint(c["dupRows"])},
		{"Repeated names / दोहराए नाम", fmt.Sprint(c["dupNames"])},
		{"Look-alike names / मिलते-जुलते नाम", fmt.Sprint(c["similar"])},
		{"Possible spelling mistakes / संभावित ग़लतियाँ", fmt.Sprint(c["spell"])},
	}
}

// ReportHTML is a printable page (landscape A4) for "Save as PDF".
func (d *Data) ReportHTML() string {
	iss := d.Issues()
	var b strings.Builder
	b.WriteString(`<!doctype html><html><head><meta charset="utf-8"><title>` + html.EscapeString(d.Title) + `</title><style>
@page{size:A4 landscape;margin:10mm}body{font:11px "Segoe UI","Nirmala UI","Noto Sans Devanagari",sans-serif;color:#111;margin:0}
h1{font-size:16px;margin:0 0 4px}.s{color:#444;margin:0 0 8px}table{border-collapse:collapse;width:100%}
th,td{border:1px solid #999;padding:3px 5px;text-align:left;vertical-align:top}th{background:#dde6f5}
tr{page-break-inside:avoid}thead{display:table-header-group}
.dup,.dupname{background:#fff2a8}.spell{background:#f8b4b4}.similar{background:#ffd3a1}.k{font-size:10px;color:#333}
.legend span{display:inline-block;padding:1px 6px;margin-right:6px;border:1px solid #999}</style></head><body>`)
	b.WriteString(`<h1>` + html.EscapeString(d.Title) + `</h1><p class="s">`)
	for i, l := range d.SummaryLines()[1:] {
		if i > 0 {
			b.WriteString(" · ")
		}
		b.WriteString(html.EscapeString(l[0] + ": " + l[1]))
	}
	b.WriteString(`</p><p class="legend"><span class="dup">पीला: दोहराया</span><span class="similar">नारंगी: मिलता-जुलता नाम</span><span class="spell">लाल: संभावित ग़लती</span></p><table><thead><tr>`)
	for _, h := range d.Head {
		b.WriteString("<th>" + html.EscapeString(h) + "</th>")
	}
	b.WriteString("<th>जाँच / Check</th></tr></thead><tbody>")
	for r, row := range d.Rows {
		b.WriteString("<tr>")
		for c := range d.Head {
			v := ""
			if c < len(row) {
				v = row[c]
			}
			cls := ""
			if is, ok := iss[[2]int{r, c}]; ok {
				cls = ` class="` + is.Kind + `"`
			}
			b.WriteString("<td" + cls + ">" + html.EscapeString(v) + "</td>")
		}
		b.WriteString(`<td class="k">` + html.EscapeString(d.RowNotes(iss, r)) + "</td></tr>")
	}
	b.WriteString("</tbody></table></body></html>")
	return b.String()
}
