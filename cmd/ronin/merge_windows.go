package main

import (
	"crypto/rand"
	_ "embed"
	"encoding/hex"
	"encoding/json"
	"strings"
)

// "डेटा मिलाओ": several Excel / CSV files (Prerna + UDISE+, teachers' forms + seniority
// list) become one table with the differences marked. The page reads the files on this
// computer; nothing is sent anywhere. The result is saved like the Data button's.

//go:embed ui/merge.html
var mergeHTML string

//go:embed ui/merge.js
var mergeJS string

// mergeKey proves that "save the result" came from Ronin's own merge page.
var mergeKey = func() string {
	b := make([]byte, 16)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}()

func mergePage() string {
	dark := "1"
	if themeName() == "light" {
		dark = "0"
	}
	return strings.NewReplacer("{{MERGEJS}}", mergeJS, "{{NONCE}}", newTabKey, "{{KEY}}", mergeKey, "{{DARK}}", dark).Replace(mergeHTML)
}

// showMerge opens the merge page in a new tab.
func (w *Window) showMerge() {
	t := w.newTab("", true)
	if t == nil {
		return
	}
	t.isNewTab = false
	t.isMerge = true
	t.title = "डेटा मिलाओ"
	_ = t.wv.NavigateToString(mergePage())
	w.sendState()
}

// saveMerge takes the merged table from the page and saves Excel + PDF.
func (t *Tab) saveMerge() {
	if t.destroyed || !t.isMerge || t.exporting {
		return
	}
	t.exporting = true
	t.runScript("window.__roninMerge?window.__roninMerge.result():''", func(res string) {
		var body string
		_ = json.Unmarshal([]byte(res), &body)
		t.saveData(body)
	})
}
