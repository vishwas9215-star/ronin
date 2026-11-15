package main

import (
	"crypto/rand"
	"embed"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io/fs"
	"os"
	"path"
	"path/filepath"
	"strings"

	"github.com/wailsapp/go-webview2/pkg/edge"

	"roninbrowser/internal/containers"
	"roninbrowser/internal/downloadguard"
)

// PDF / Photo tools: Ronin's own page at https://ronin.home/tools/ (the files are inside
// Ronin and served from memory; the address never reaches the internet). Everything is
// done in the page; a finished file is handed over in pieces and saved in Downloads.

//go:embed ui/tools
var toolsFiles embed.FS

const toolsPrefix = "https://ronin.home/tools/"

// toolsKey proves "save this file" came from the tools page.
var toolsKey = func() string {
	b := make([]byte, 16)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}()

// showTools opens Ronin Tools over the page. It is not a browsing tab: the tab strip
// does not list it; the Tools button and "Back to browser" switch between them.
func (w *Window) showTools() {
	for _, t := range w.tabs {
		if t.isTools {
			if w.active != t {
				w.beforeTools = w.active
				w.selectTab(t)
			}
			return
		}
	}
	before := w.active
	t := w.newTab("", true)
	if t == nil {
		return
	}
	w.beforeTools = before
	dark := "1"
	if themeName() == "light" {
		dark = "0"
	}
	t.isNewTab = false
	t.isTools = true
	t.title = "Ronin Tools"
	_ = t.wv.Navigate(toolsPrefix + "?k=" + toolsKey + "&dark=" + dark)
	w.sendState()
}

// leaveTools goes back to the page that was open before Tools (Tools keeps its place).
func (w *Window) leaveTools() {
	for _, t := range w.tabs {
		if t == w.beforeTools && !t.isTools {
			w.selectTab(t)
			return
		}
	}
	for i := len(w.tabs) - 1; i >= 0; i-- {
		if !w.tabs[i].isTools {
			w.selectTab(w.tabs[i])
			return
		}
	}
	w.newTab("", true)
}

// saveMerge takes Data Match's table from the page and saves Excel + PDF.
func (t *Tab) saveMerge() {
	if t.destroyed || !t.isTools || t.exporting {
		return
	}
	t.exporting = true
	t.runScript("window.__roninMerge?window.__roninMerge.result():''", func(res string) {
		var body string
		_ = json.Unmarshal([]byte(res), &body)
		t.saveData(body)
	})
}

// serveTools answers a request for one of the tools page's files. It returns false
// for other addresses.
func (t *Tab) serveTools(uri string, args *edge.ICoreWebView2WebResourceRequestedEventArgs) bool {
	if !strings.HasPrefix(uri, toolsPrefix) {
		return false
	}
	env := t.c.Environment()
	if env == nil {
		return true
	}
	name := strings.TrimPrefix(uri, toolsPrefix)
	if i := strings.IndexAny(name, "?#"); i >= 0 {
		name = name[:i]
	}
	if name == "" {
		name = "index.html"
	}
	body, err := fs.ReadFile(toolsFiles, path.Join("ui/tools", path.Clean("/" + name)[1:]))
	status, reason := 200, "OK"
	if err != nil || !t.isTools || t.w.container == containers.Banking {
		body, status, reason = []byte("not found"), 404, "Not Found"
	}
	ct := "text/html; charset=utf-8"
	switch {
	case strings.HasSuffix(name, ".js"), strings.HasSuffix(name, ".mjs"):
		ct = "text/javascript; charset=utf-8"
	case strings.HasSuffix(name, ".txt"), strings.HasSuffix(name, ".md"):
		ct = "text/plain; charset=utf-8"
	}
	resp, err := env.CreateWebResourceResponse(body, status, reason, "Content-Type: "+ct+"\r\nCache-Control: no-store")
	if err == nil && resp != nil {
		_ = args.PutResponse(resp)
		resp.Release()
	}
	return true
}

// saveToolFile reads the finished file from the page (in pieces) and saves it in Downloads.
func (t *Tab) saveToolFile() {
	if t.destroyed || !t.isTools {
		return
	}
	t.runScript("window.__roninSave?JSON.stringify({name:window.__roninSave.name,n:window.__roninSave.n}):''", func(raw string) {
		var js string
		_ = json.Unmarshal([]byte(raw), &js)
		var head struct {
			Name string
			N    int
		}
		if json.Unmarshal([]byte(js), &head) != nil || head.N <= 0 || head.N > 400 {
			return
		}
		var b64 strings.Builder
		var part func(i int)
		done := func(ok bool) {
			state := "failed"
			if ok {
				state = "saved"
			}
			t.runScript("if(window.__roninSave)window.__roninSave.state='"+state+"'", func(string) {})
		}
		part = func(i int) {
			if i == head.N {
				data, err := base64.StdEncoding.DecodeString(b64.String())
				if err != nil {
					done(false)
					return
				}
				name := downloadguard.SanitizeName(head.Name)
				if name == "" || name == "." {
					name = "Ronin file"
				}
				switch strings.ToLower(filepath.Ext(name)) { // the tools only ever make these
				case ".pdf", ".jpg", ".jpeg", ".png":
				default:
					name += ".pdf"
				}
				p := uniquePath(filepath.Join(downloadsDir(), name))
				if err := os.WriteFile(p, data, 0o644); err != nil {
					t.w.bar(t, "error", L("File save नहीं हुई: ", "The file was not saved: ")+err.Error(), nil)
					done(false)
					return
				}
				savePageFile(t.w, p, true)
				t.w.bar(t, "ok", fmt.Sprintf(L("✔ Downloads में save: %s (%d KB)", "✔ Saved in Downloads: %s (%d KB)"), filepath.Base(p), (len(data)+1023)/1024), nil)
				done(true)
				return
			}
			t.runScript(fmt.Sprintf("window.__roninSave?window.__roninSave.part(%d):''", i), func(res string) {
				var s string
				if json.Unmarshal([]byte(res), &s) != nil || s == "" {
					done(false)
					return
				}
				b64.WriteString(s)
				part(i + 1)
			})
		}
		part(0)
	})
}
