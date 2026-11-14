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

func (w *Window) showTools() {
	t := w.newTab("", true)
	if t == nil {
		return
	}
	dark := "1"
	if themeName() == "light" {
		dark = "0"
	}
	t.isNewTab = false
	t.isTools = true
	_ = t.wv.Navigate(toolsPrefix + "?k=" + toolsKey + "&dark=" + dark)
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
				p := uniquePath(filepath.Join(downloadsDir(), name))
				if err := os.WriteFile(p, data, 0o644); err != nil {
					t.w.bar(t, "error", "File save नहीं हुई: "+err.Error(), nil)
					done(false)
					return
				}
				savePageFile(t.w, p, true)
				t.w.bar(t, "ok", fmt.Sprintf("✔ Downloads में save: %s (%d KB)", filepath.Base(p), (len(data)+1023)/1024), nil)
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
