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
	keepAwake(true) // the owner may walk away: the laptop must not sleep in the middle
	w.bar(t, "info", L("📊 इस page की table पढ़ रहा हूँ...", "📊 Reading the table on this page..."), nil)
	t.runScript(tableExportJS+";window.__roninTable.start()", func(string) {
		start := time.Now()
		lastSeen := time.Now() // the script answered (a new web page takes it away for a moment)
		var poll func()
		poll = func() {
			if t.destroyed {
				keepAwake(false)
				return
			}
			t.runScript("window.__roninTable?window.__roninTable.state():''", func(raw string) {
				var js string
				_ = json.Unmarshal([]byte(raw), &js)
				var s struct {
					Phase, Error string
					Page, Rows   int
					Pct          *int
				}
				_ = json.Unmarshal([]byte(js), &s)
				if s.Phase != "" {
					lastSeen = time.Now()
				}
				switch {
				case s.Phase == "" && time.Since(lastSeen) < 90*time.Second:
					// "Next" opened a new web page: put the script back, it carries on from sessionStorage
					t.runScript("if(document.readyState==='complete'&&!window.__roninTable){"+tableExportJS+";window.__roninTable.resume()}", func(string) {})
					time.AfterFunc(time.Second, func() { runOnUI(poll) })
				case s.Phase == "navigating":
					time.AfterFunc(600*time.Millisecond, func() { runOnUI(poll) })
				case s.Phase == "reading" && time.Since(start) >= 60*time.Minute:
					// too long: keep what was read (the file says it is not complete)
					t.runScript("window.__roninTable.stop()", func(string) {})
					time.AfterFunc(time.Second, func() { runOnUI(poll) })
				case s.Phase == "reading":
					pct := ""
					if s.Pct != nil {
						pct = fmt.Sprintf("%d%% · ", *s.Pct)
					}
					w.bar(t, "info", fmt.Sprintf(L("📊 %sपेज %d पढ़ लिया, %d पंक्तियाँ...", "📊 %sread page %d, %d rows..."), pct, s.Page, s.Rows), nil)
					time.AfterFunc(600*time.Millisecond, func() { runOnUI(poll) })
				case s.Phase == "done":
					t.runScript("window.__roninTable.result()", func(res string) {
						var body string
						_ = json.Unmarshal([]byte(res), &body)
						t.saveData(body)
					})
				default:
					t.exporting = false
					keepAwake(false)
					msg := s.Error
					if msg == "" {
						msg = "the page did not answer"
					}
					w.bar(t, "error", L("📊 Table नहीं मिली: ", "📊 Could not read the table: ")+msg, nil)
				}
			})
		}
		poll()
	})
}

// shortFileName makes a short, plain name every portal accepts: letters, digits and _
// ("Teacher_Level", "Prerna_UDISE_Match"), then the date and time, without an extension.
func shortFileName(title, site string) string {
	clean := func(s string) string {
		var b strings.Builder
		under := false
		for _, r := range s {
			if r < 128 && (r >= 'a' && r <= 'z' || r >= 'A' && r <= 'Z' || r >= '0' && r <= '9') {
				b.WriteRune(r)
				under = false
			} else if !under && b.Len() > 0 {
				b.WriteByte('_')
				under = true
			}
		}
		out := strings.Trim(b.String(), "_")
		if len(out) > 24 {
			out = strings.Trim(out[:24], "_")
		}
		return out
	}
	name := clean(title)
	if name == "" {
		name = clean(strings.TrimPrefix(site, "www."))
	}
	if name == "" {
		name = "data"
	}
	p := filepath.Join(downloadsDir(), name+"_"+time.Now().Format("20060102_1504"))
	return filepath.Base(uniqueBase(p))
}

// uniqueBase adds _2, _3... when name.xlsx is already there.
func uniqueBase(p string) string {
	if _, err := os.Stat(p + ".xlsx"); err != nil {
		return p
	}
	for i := 2; i < 1000; i++ {
		c := fmt.Sprintf("%s_%d", p, i)
		if _, err := os.Stat(c + ".xlsx"); err != nil {
			return c
		}
	}
	return p
}

// keepAwake stops Windows from sleeping (and the screen from going off) while data is read.
func keepAwake(on bool) {
	const esContinuous, esSystem, esDisplay = 0x80000000, 0x00000001, 0x00000002
	if on {
		_, _, _ = pSetThreadExecutionState.Call(esContinuous | esSystem | esDisplay)
	} else {
		_, _, _ = pSetThreadExecutionState.Call(esContinuous)
	}
}

var pSetThreadExecutionState = windows.NewLazySystemDLL("kernel32.dll").NewProc("SetThreadExecutionState")

func (t *Tab) saveData(body string) {
	w := t.w
	keepAwake(false)
	t.exporting = false
	d, err := tablexport.Parse(body)
	if err != nil {
		w.bar(t, "error", L("📊 Table खाली है।", "📊 The table is empty."), nil)
		return
	}
	x, err := d.XLSX()
	if err != nil {
		w.bar(t, "error", L("📊 Excel file नहीं बन सकी: ", "📊 Could not make the Excel file: ")+err.Error(), nil)
		return
	}
	if t.isTools {
		t.title = d.Title // the file is named after what was merged
	}
	base := filepath.Join(downloadsDir(), shortFileName(d.Title, d.Site))
	xlsx := base + ".xlsx"
	if err := os.WriteFile(xlsx, x, 0o644); err != nil {
		w.bar(t, "error", L("📊 Excel file save नहीं हुई: ", "📊 The Excel file was not saved: ")+err.Error(), nil)
		return
	}
	savePageFile(w, xlsx, true)
	t.runScript("window.__roninTable&&window.__roninTable.badge('"+L("✔ Excel बन गई (Downloads). PDF भी बन रही है...", "✔ Excel ready (Downloads). Making the PDF too...")+"',100,true)", func(string) {})
	c := d.Check.Counts
	note := ""
	if d.Partial != "" {
		note = L(" ⚠ अधूरा: ", " ⚠ NOT complete: ") + d.Partial + "."
	}
	if len(d.Info) > 0 {
		w.bar(t, "ok", fmt.Sprintf(L("🔀 मिलान की Excel बन गई: %d लोग · अंतर वाले %d · सिर्फ़ एक file में %d · मिलान जाँचें %d. PDF बन रही है...", "🔀 Match Excel ready: %d people · with differences %d · in one file only %d · check the match %d. Making the PDF..."),
			len(d.Rows), c["diff"], c["only"], c["weak"]), nil)
	} else {
		w.bar(t, "ok", fmt.Sprintf(L("📊 Excel बन गई: %d पंक्तियाँ · दोहराए %d · मिलते-जुलते नाम %d · संभावित ग़लतियाँ %d.%s PDF बन रही है...", "📊 Excel ready: %d rows · repeated %d · look-alike names %d · possible mistakes %d.%s Making the PDF..."),
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
