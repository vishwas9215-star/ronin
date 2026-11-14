//go:build windows

package main

import (
	"bytes"
	_ "embed"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"time"

	"github.com/wailsapp/go-webview2/pkg/edge"

	"roninbrowser/internal/adblock"
	"roninbrowser/internal/containers"
	"roninbrowser/internal/kids"
	"roninbrowser/internal/safemoney"
	"roninbrowser/internal/updater"
)

//go:embed ui/inject_adblock.js
var adblockJS string

//go:embed ui/inject_scam.js
var scamJS string

//go:embed ui/inject_dark.js
var darkJS string

//go:embed ui/reader.js
var readerJS string

type filterList struct {
	name   string
	url    string
	min    int  // a download with fewer rules is treated as broken and ignored
	threat bool // phishing / malware list: refreshed twice a day, red page
}

var filterLists = func() []filterList {
	var out []filterList
	for _, l := range adblock.Lists { // one list for Windows and Android
		out = append(out, filterList{l.Name, l.URL, l.Min, l.Threat})
	}
	return out
}()

var blocker atomic.Pointer[adblock.Engine]

func listPath(name string) string { return filepath.Join(dataDir, "lists", name+".txt") }

// loadFilterLists builds the engine from the built-in rules and the lists
// saved on disk (instant, no internet needed).
func loadFilterLists() {
	e := adblock.New()
	for _, l := range filterLists {
		if b, err := os.ReadFile(listPath(l.name)); err == nil {
			if l.threat {
				e.LoadThreat(bytes.NewReader(b))
			} else {
				e.LoadList(bytes.NewReader(b))
			}
		}
	}
	blocker.Store(e)
}

// updateFilterLists downloads fresh lists once a day. A list is only
// replaced when the new copy looks complete.
func updateFilterLists() {
	_ = os.MkdirAll(filepath.Join(dataDir, "lists"), 0o755)
	changed := false
	stateMu.Lock()
	st := updater.LoadState(statePath())
	stateMu.Unlock()
	for _, l := range filterLists {
		key := "list:" + l.name
		c := st.Components[key]
		every := 24 * time.Hour
		if l.threat {
			every = 12 * time.Hour // new phishing sites appear every day
		}
		if !c.Updated.IsZero() && time.Since(c.Updated) < every {
			continue
		}
		b, err := fetch(l.url, 30<<20)
		problem := ""
		if err == nil {
			blk, alw := adblock.ParseList(bytes.NewReader(b))
			if len(blk)+len(alw) >= l.min {
				if werr := os.WriteFile(listPath(l.name)+".tmp", b, 0o644); werr == nil {
					_ = os.Rename(listPath(l.name)+".tmp", listPath(l.name))
					changed = true
				}
			} else {
				problem = "download looked incomplete; kept the previous list"
			}
		} else {
			problem = err.Error()
		}
		recordComponent(key, func(cc *updater.Component) {
			cc.Updated = time.Now()
			cc.OK = problem == ""
			cc.Problem = problem
		})
	}
	if changed {
		loadFilterLists()
	}
}

// dangerous reports whether uri is a known phishing or malware site that must
// not open. Banks, payment pages and sites the user marked "I trust this
// site" are never stopped.
func dangerous(uri string) (string, bool) {
	e := blocker.Load()
	if e == nil || !strings.HasPrefix(uri, "http") {
		return "", false
	}
	v := safemoney.Classify(uri, store.Lists())
	if v.Host == "" || v.Kind != safemoney.General || trustedHost(v.Host) {
		return "", false
	}
	return v.Host, e.Dangerous(v.Host)
}

func trustedHost(h string) bool {
	for _, d := range store.Lists().Trusted {
		if h == d || strings.HasSuffix(h, "."+d) {
			return true
		}
	}
	return false
}

// hookAdblock connects the network blocker and page scripts to a tab.
func (t *Tab) hookAdblock() {
	c := t.c
	cont := t.w.container
	c.WebResourceRequestedCallback = func(req *edge.ICoreWebView2WebResourceRequest, args *edge.ICoreWebView2WebResourceRequestedEventArgs) {
		e := blocker.Load()
		if e == nil {
			return
		}
		uri, err := req.GetUri()
		if err != nil || uri == "" {
			return
		}
		if t.kidsHeaders {
			if kids.IsYouTube(uri) {
				if h, err := req.GetHeaders(); err == nil && h != nil {
					_ = h.SetHeader("YouTube-Restrict", "Strict")
					h.Release()
				}
			}
			if kb := kidsBlocker.Load(); kb != nil && (kb.Blocked(uri) || kids.LooksAdult(uri)) {
				block(c, args)
				return
			}
		}
		if t.adPaused {
			return
		}
		if !e.BlockedFrom(uri, hostOf(t.topURL())) {
			return
		}
		if trustedHost(strings.TrimPrefix(hostOf(uri), "www.")) {
			return // "a safe site Ronin stopped by mistake": the user said it is fine
		}
		debugBlocked(uri)
		// never break a bank or payment page, whatever a list says
		if v := safemoney.Classify(uri, store.Lists()); v.Kind == safemoney.Bank || v.Kind == safemoney.Payment || v.Kind == safemoney.Trading {
			return
		}
		block(c, args)
	}
	c.AddWebResourceRequestedFilter("*", edge.COREWEBVIEW2_WEB_RESOURCE_CONTEXT_ALL)

	if cont == containers.Banking {
		return // no scripts are ever added to bank pages
	}
	c.Init(adblockJS)
	if pwSaves(cont) { // not in Private: nothing about logins is noticed there
		c.Init(passJS)
	}
	if cont == containers.Personal || cont == containers.Private || cont == containers.Kids {
		c.Init(scamJS)
		c.Init(darkJS) // Night mode for plain white sites (charts in Trading keep their own look)
	}
}

// block answers a request with an empty "403 Blocked" so it never reaches the network.
func block(c *edge.Chromium, args *edge.ICoreWebView2WebResourceRequestedEventArgs) {
	env := c.Environment()
	if env == nil {
		return
	}
	var body []byte
	headers := "Content-Type: text/plain"
	if ctx, err := args.GetResourceContext(); err == nil && ctx == edge.COREWEBVIEW2_WEB_RESOURCE_CONTEXT_DOCUMENT {
		// a whole page (or a frame) was an advert: show Ronin's own short note
		body = []byte(blockedPageHTML)
		headers = "Content-Type: text/html; charset=utf-8"
	}
	resp, err := env.CreateWebResourceResponse(body, 403, "Blocked", headers)
	if err != nil || resp == nil {
		return
	}
	_ = args.PutResponse(resp)
	resp.Release()
}

// debugBlocked writes blocked addresses (without their query part) to
// adblock-debug.log, but only when the file "debug-adblock" exists in the
// Ronin data folder. It is off normally, so no browsing history is kept.
var debugCount atomic.Int32

func debugBlocked(uri string) {
	if debugCount.Load() > 400 {
		return
	}
	if b, err := os.ReadFile(filepath.Join(dataDir, "debug-adblock")); err != nil || strings.TrimSpace(string(b)) != "on" {
		return
	}
	debugCount.Add(1)
	if i := strings.IndexAny(uri, "?#"); i >= 0 {
		uri = uri[:i]
	}
	f, err := os.OpenFile(filepath.Join(dataDir, "adblock-debug.log"), os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o600)
	if err != nil {
		return
	}
	f.WriteString(time.Now().Format("15:04:05 ") + uri + "\n")
	f.Close()
}

const blockedPageHTML = `<!doctype html><html><head><meta charset="utf-8"><title>Blocked by Ronin</title>
<style>html,body{margin:0;height:100%;background:#15171c;color:#9aa3b2;font:13px Segoe UI,sans-serif}
div{display:flex;height:100%;align-items:center;justify-content:center;text-align:center}
@media (prefers-color-scheme: light){html,body{background:#eef0f4;color:#5a6375}}</style></head>
<body><div>Ronin blocked this page: it is an advert, a tracker or a known harmful site.</div></body></html>`
