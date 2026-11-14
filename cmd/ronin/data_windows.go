//go:build windows

package main

import (
	"crypto/rand"
	_ "embed"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"math"
	"os"
	"path/filepath"
	"strings"
	"time"
	"unsafe"

	"golang.org/x/sys/windows"

	"roninbrowser/internal/containers"
	"roninbrowser/internal/tablexport"
)

// ---- "Data -> Excel + PDF": the biggest table on the page, every page of it ----------

//go:embed ui/table_export.js
var tableExportJS string

// dataKey proves a "start export" request came from Ronin's own button. It is a
// different key from the new tab page's, so a page that reads it can only start
// an export of its own table (into the user's Downloads), nothing else.
var dataKey = func() string {
	b := make([]byte, 16)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}()

// showDataButton puts the green "Excel + PDF" button on pages of sites where
// the user switched it on (Menu -> Data button on this site).
func (t *Tab) showDataButton() {
	if t.destroyed || t.w.container == containers.Banking || !strings.HasPrefix(t.url, "http") || !store.IsDataSite(hostOf(t.url)) {
		return
	}
	js := `(function(){if(document.getElementById('ronin-data-btn')||!document.body)return;var b=document.createElement('button');` +
		`b.id='ronin-data-btn';b.textContent='📊 Excel + PDF';b.title='Ronin: is page ki table ka sara data Excel aur PDF mein';` +
		`b.style.cssText='position:fixed;right:18px;bottom:18px;z-index:2147483647;padding:12px 20px;border-radius:24px;border:0;background:#16a34a;color:#fff;font:600 15px Segoe UI,sans-serif;box-shadow:0 4px 14px rgba(0,0,0,.35);cursor:pointer';` +
		`b.onclick=function(){location.href='https://ronin.data/go?k=` + dataKey + `'};document.body.appendChild(b);})()`
	t.runScript(js, func(string) {})
}

// exportData reads the table (all pages), then saves an Excel file and a PDF
// in Downloads and shows the coloured report in a new tab.
func (t *Tab) exportData() {
	if t.destroyed || t.w.container == containers.Banking || t.exporting {
		return
	}
	t.exporting = true
	w := t.w
	w.bar(t, "info", "📊 इस page की table पढ़ रहा हूँ... (Reading the table)", nil)
	t.runScript(tableExportJS+";window.__roninTable.start()", func(string) {
		start := time.Now()
		var poll func()
		poll = func() {
			if t.destroyed {
				return
			}
			t.runScript("window.__roninTable?window.__roninTable.state():''", func(raw string) {
				var js string
				_ = json.Unmarshal([]byte(raw), &js)
				var s struct {
					Phase, Error string
					Page, Rows   int
				}
				_ = json.Unmarshal([]byte(js), &s)
				switch {
				case s.Phase == "reading" && time.Since(start) >= 15*time.Minute:
					// too long: keep what was read (the file says it is not complete)
					t.runScript("window.__roninTable.stop()", func(string) {})
					time.AfterFunc(time.Second, func() { runOnUI(poll) })
				case s.Phase == "reading":
					w.bar(t, "info", fmt.Sprintf("📊 पेज %d पढ़ लिया, %d पंक्तियाँ... (Reading page %d, %d rows)", s.Page, s.Rows, s.Page, s.Rows), nil)
					time.AfterFunc(600*time.Millisecond, func() { runOnUI(poll) })
				case s.Phase == "done":
					t.runScript("window.__roninTable.result()", func(res string) {
						var body string
						_ = json.Unmarshal([]byte(res), &body)
						t.saveData(body)
					})
				default:
					t.exporting = false
					msg := s.Error
					if msg == "" {
						msg = "the page did not answer"
					}
					w.bar(t, "error", "📊 Table नहीं मिली / could not read the table: "+msg, nil)
				}
			})
		}
		poll()
	})
}

func (t *Tab) saveData(body string) {
	w := t.w
	t.exporting = false
	d, err := tablexport.Parse(body)
	if err != nil {
		w.bar(t, "error", "📊 Table खाली है / the table is empty.", nil)
		return
	}
	x, err := d.XLSX()
	if err != nil {
		w.bar(t, "error", "📊 Excel file नहीं बन सकी: "+err.Error(), nil)
		return
	}
	if t.isMerge {
		t.title = d.Title // the file is named after what was merged
	}
	base := strings.TrimSuffix(pageFileName(t, ".xlsx"), ".xlsx")
	xlsx := base + ".xlsx"
	if err := os.WriteFile(xlsx, x, 0o644); err != nil {
		w.bar(t, "error", "📊 Excel file save नहीं हुई: "+err.Error(), nil)
		return
	}
	savePageFile(w, xlsx, true)
	c := d.Check.Counts
	note := ""
	if d.Partial != "" {
		note = " ⚠ अधूरा / NOT complete: " + d.Partial + "."
	}
	if len(d.Info) > 0 {
		w.bar(t, "ok", fmt.Sprintf("🔀 मिलान की Excel बन गई: %d लोग · अंतर वाले %d · सिर्फ़ एक file में %d · मिलान जाँचें %d. PDF बन रही है...",
			len(d.Rows), c["diff"], c["only"], c["weak"]), nil)
	} else {
		w.bar(t, "ok", fmt.Sprintf("📊 Excel बन गई: %d पंक्तियाँ · दोहराए %d · मिलते-जुलते नाम %d · संभावित ग़लतियाँ %d.%s PDF बन रही है...",
			len(d.Rows), c["dupRows"]+c["dupNames"], c["similar"], c["spell"], note), nil)
	}

	// the coloured report opens in a new tab and is printed to PDF from there
	tmp := filepath.Join(os.TempDir(), "ronin-report-"+fmt.Sprint(time.Now().UnixNano())+".html")
	if err := os.WriteFile(tmp, []byte(d.ReportHTML()), 0o600); err != nil {
		return
	}
	rt := w.newTab("file:///"+filepath.ToSlash(tmp), false)
	if rt == nil {
		return
	}
	pdf := base + ".pdf"
	rt.afterLoad = func() {
		rt.savePDFTo(pdf, func(ok bool) {
			_ = os.Remove(tmp)
			savePageFile(w, pdf, ok)
		})
	}
}

var iidICoreWebView2Environment6 = windows.GUID{Data1: 0xe59ee362, Data2: 0xacbd, Data3: 0x4857, Data4: [8]byte{0x9a, 0x8e, 0xd3, 0x64, 0x4d, 0x94, 0x59, 0xa9}}

const (
	env6CreatePrintSettings = 14
	psPutOrientation        = 4
	psPutPageWidth          = 8
	psPutPageHeight         = 10
	psPutMarginTop          = 12
	psPutMarginBottom       = 14
	psPutMarginLeft         = 16
	psPutMarginRight        = 18
	psPutPrintBackgrounds   = 20
	psPutHeaderAndFooter    = 24
)

// a4Landscape makes print settings for the data report: A4 across, colours kept,
// no browser header / footer. 0 when the engine is too old (defaults are used then).
func (t *Tab) a4Landscape() uintptr {
	if t.c == nil {
		return 0
	}
	env := t.c.Environment()
	if env == nil {
		return 0
	}
	e6 := queryInterface(uintptr(unsafe.Pointer(env)), &iidICoreWebView2Environment6)
	if e6 == 0 {
		return 0
	}
	defer release(e6)
	var ps uintptr
	if failed(call(e6, env6CreatePrintSettings, uintptr(unsafe.Pointer(&ps)))) || ps == 0 {
		return 0
	}
	f := func(v float64) uintptr { return uintptr(math.Float64bits(v)) } // doubles go in XMM registers too
	call(ps, psPutOrientation, 1)                                        // landscape
	call(ps, psPutPageWidth, f(8.27))                                    // A4, inches
	call(ps, psPutPageHeight, f(11.69))
	for _, m := range []int{psPutMarginTop, psPutMarginBottom, psPutMarginLeft, psPutMarginRight} {
		call(ps, m, f(0.4))
	}
	call(ps, psPutPrintBackgrounds, 1)
	call(ps, psPutHeaderAndFooter, 0)
	return ps
}

// savePDFTo prints the tab to a PDF file.
func (t *Tab) savePDFTo(path string, done func(ok bool)) {
	w7 := queryInterface(uintptr(t.wv), &iidICoreWebView2_7)
	if w7 == 0 {
		done(false)
		return
	}
	defer release(w7)
	p, err := windows.UTF16PtrFromString(path)
	if err != nil {
		done(false)
		return
	}
	ps := t.a4Landscape()
	h := newHandler(func(errCode, success uintptr) uintptr {
		if ps != 0 {
			release(ps)
		}
		ok := uint32(errCode) == 0 && uint32(success) != 0
		runOnUI(func() { done(ok) })
		return sOK
	})
	call(w7, wv7PrintToPdf, uintptr(unsafe.Pointer(p)), ps, uintptr(unsafe.Pointer(h)))
}
