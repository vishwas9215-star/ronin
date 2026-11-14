//go:build windows

package main

import (
	"encoding/json"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"time"
	"unsafe"

	"github.com/wailsapp/go-webview2/pkg/edge"

	"roninbrowser/internal/ai"
	"roninbrowser/internal/containers"
	"roninbrowser/internal/kids"
	"roninbrowser/internal/omnibox"
	"roninbrowser/internal/safemoney"
	"roninbrowser/internal/videodl"
)

var windowsByHwnd = map[uintptr]*Window{}

const (
	timerID      = 1
	timerEveryMs = 5000
)

// Window is one top-level Ronin window. All tabs in it belong to one container.
type Window struct {
	hwnd      uintptr
	container containers.Container
	ui        *edge.Chromium
	pendingAI *aiRequest
	uiReady   bool
	uiHeight  int  // CSS pixels wanted by the toolbar
	uiFull    bool // the toolbar page covers the whole window (warning, settings)
	tabs      []*Tab
	active    *Tab
	nextID    int
	closing   bool
	pending   []func() // run when the toolbar page is ready

	// side panel (downloads, AI): opens at the right, the page stays usable
	panel        *edge.Chromium
	panelReady   bool
	panelPending []func()
	panelKind    string   // "" = closed
	beforeTools  *Tab     // the page that was open when Ronin Tools came up
	closedURLs   []string // closed tabs, for Ctrl+Shift+T (this run only, not saved)

	// Safe Money
	lastClipSeq     uint32
	clipCopiedAt    time.Time
	lastRemoteCheck time.Time
	remoteShown     string
	idleLocked      bool
	lastActive      time.Time // last time the user used this Safe Money window

	// Trading
	grid     int  // 0/1 = one chart, 2 = side by side, 4 = 2x2
	mini     bool // small always-on-top chart window
	float    bool // the small downloads window shown when Ronin is closed
	hiddenDL bool // closed by the user, kept (hidden) until its download ends
	dirty    bool // tabs changed since the session was last saved
	created  time.Time
}

// newWindow opens a window for container c and loads startURL in its first tab.
func newWindow(c containers.Container, startURL string) *Window {
	var urls []string
	if startURL != "" {
		urls = []string{startURL}
	}
	return newWindowWith(c, urls, 0, 0, false)
}

// newMiniWindow opens a small always-on-top Trading window for one chart.
func newMiniWindow(u string) *Window {
	return newWindowWith(containers.Trading, []string{u}, 0, 0, true)
}

// newWindowWith opens a window with several tabs (session restore, grid, mini chart).
func newWindowWith(c containers.Container, urls []string, active, grid int, mini bool) *Window {
	if kidsLevel() != "" {
		c, grid, mini = containers.Kids, 0, false
	}
	title := windowTitle(c, "")
	hwnd := createAppWindow(title)
	if hwnd == 0 {
		messageBox(0, "Ronin Browser", "Could not create a window.", mbIconError)
		return nil
	}
	w := &Window{hwnd: hwnd, container: c, uiHeight: 84, lastActive: time.Now(), created: time.Now(), mini: mini}
	if c == containers.Trading && (grid == 2 || grid == 4) {
		w.grid = grid
	}
	windowsByHwnd[hwnd] = w
	if dlFloat != nil && !dlFloat.closing { // a real window is back: the small one is not needed
		f := dlFloat
		dlFloat = nil
		f.close()
	}

	if c == containers.Banking {
		// Screenshots, screen recording and remote viewers see a black window.
		if r, _, _ := pSetWindowDisplayAffinity.Call(hwnd, wdaExcludeCapture); r == 0 {
			pSetWindowDisplayAffinity.Call(hwnd, wdaMonitor)
		}
		pSetWindowPos.Call(hwnd, 0, 0, 0, 0, 0, 0x0001|0x0002|swpNoZOrder|0x0020) // frame changed
	}
	if mini {
		// small window in the bottom-right corner, always above other windows
		s := int32(dpiOf(hwnd))
		wd, ht := 560*s/96, 400*s/96
		sw, _, _ := pGetSystemMetrics.Call(16) // SM_CXFULLSCREEN
		sh, _, _ := pGetSystemMetrics.Call(17)
		x, y := int32(sw)-wd-16, int32(sh)-ht-16
		if x < 0 {
			x = 0
		}
		if y < 0 {
			y = 0
		}
		pSetWindowPos.Call(hwnd, ^uintptr(0), uintptr(x), uintptr(y), uintptr(wd), uintptr(ht), 0x0040) // HWND_TOPMOST, SWP_SHOWWINDOW
	} else {
		pShowWindow.Call(hwnd, swShowMaximized)
	}
	pUpdateWindow.Call(hwnd)

	if !w.createToolbar() {
		messageBox(hwnd, "Ronin Browser", "The browser engine (Microsoft WebView2) could not start.\n\nPlease restart your computer. If this keeps happening, reinstall Ronin Browser.", mbIconError)
		pDestroyWindow.Call(hwnd)
		return nil
	}
	if c == containers.Banking || c == containers.Personal || c == containers.Trading || c == containers.Private {
		pSetTimer.Call(hwnd, timerID, timerEveryMs, 0)
	}
	if len(urls) == 0 {
		w.newTab("", true)
	}
	for _, u := range urls {
		w.newTab(u, true)
	}
	if active > 0 && active < len(w.tabs) {
		w.selectTab(w.tabs[active])
	}
	if !mini {
		pSetForegroundWindow.Call(hwnd) // a page moved to this window must be seen at once
	}
	if c == containers.Banking {
		w.lastClipSeq = clipboardSeq()
		w.checkRemoteTools(true)
		w.checkAntivirus()
	}
	return w
}

func windowTitle(c containers.Container, page string) string {
	name := "Ronin Browser"
	switch c {
	case containers.Banking:
		name = "Ronin Safe Money"
	case containers.Trading:
		name = "Ronin Trading"
	case containers.Calls:
		name = "Ronin Video Call"
	case containers.Private:
		name = "Ronin Private"
	case containers.Kids:
		name = "Ronin Kids"
	}
	_ = page // the frame shows only Ronin's name (the page title is in the tab)
	return name
}

// ---- toolbar (our own trusted page) ---------------------------------------

func (w *Window) createToolbar() bool {
	ui := edge.NewChromium()
	ui.DataPath = filepath.Join(dataDir, "profiles", "UI")
	ui.SetErrorCallback(func(err error) { logf("toolbar: %v", err) })
	ui.MessageCallback = func(msg string, _ *edge.ICoreWebView2, _ *edge.ICoreWebView2WebMessageReceivedEventArgs) {
		// Never do the work inside a WebView2 event: creating a new tab
		// there would wait forever. Run it from the message loop instead.
		runOnUI(func() { w.onUIMessage(msg) })
	}
	ui.AcceleratorKeyCallback = w.onKey
	if !ui.Embed(w.hwnd) || ui.Failed() {
		return false
	}
	w.ui = ui
	if s, err := ui.GetSettings(); err == nil {
		_ = s.PutAreDevToolsEnabled(false)
		_ = s.PutAreDefaultContextMenusEnabled(false)
		_ = s.PutIsStatusBarEnabled(false)
		_ = s.PutIsZoomControlEnabled(false)
	}
	_ = ui.PutIsSwipeNavigationEnabled(false)
	r0, g0, b0 := uiBackground()
	ui.SetBackgroundColour(r0, g0, b0, 255)
	if w.float {
		ui.NavigateToString(panelHTML(w.container))
	} else {
		ui.NavigateToString(toolbarHTML(w.container))
	}
	w.layout()
	return true
}

func (w *Window) send(js string) {
	if w.ui == nil || w.closing {
		return
	}
	if !w.uiReady {
		w.pending = append(w.pending, func() { w.ui.Eval(js) })
		return
	}
	w.ui.Eval(js)
}

func jsCall(fn string, v any) string {
	b, _ := json.Marshal(v)
	return "window.ronin&&window.ronin." + fn + "(" + string(b) + ")"
}

type tabState struct {
	ID       int    `json:"id"`
	Title    string `json:"title"`
	URL      string `json:"url"`
	Loading  bool   `json:"loading"`
	Active   bool   `json:"active"`
	Protect  string `json:"protect,omitempty"`
	CanBack  bool   `json:"canBack"`
	CanFwd   bool   `json:"canFwd"`
	Secure   bool   `json:"secure"`
	BankHint bool   `json:"bankHint"` // offer "This is my bank"
	Video    bool   `json:"video"`    // show the Download button
	AdPaused bool   `json:"adPaused"`
	InGrid   bool   `json:"inGrid"` // shown in a grid cell right now
	Zoom     int    `json:"zoom"`   // page size in per cent
	Tool     bool   `json:"tool"`   // Ronin Tools (not shown as a tab)
}

func (w *Window) sendState() {
	var list []tabState
	for _, t := range w.tabs {
		u := omnibox.Display(t.url)
		if t.isTools {
			u = "" // Ronin Tools: no web address (and never its key)
		}
		list = append(list, tabState{
			ID: t.id, Title: t.displayTitle(), URL: u, Tool: t.isTools, Loading: t.loading,
			Active: t == w.active, Protect: t.protect, CanBack: t.canBack, CanFwd: t.canFwd,
			Secure:   strings.HasPrefix(t.url, "https://"),
			BankHint: w.container == containers.Personal && strings.HasPrefix(t.url, "https://"),
			Video:    t.canDownload(true),
			AdPaused: t.adPaused,
			InGrid:   w.inGrid(t),
			Zoom:     map[bool]int{true: t.zoom(), false: 100}[t == w.active && !t.isNewTab],
		})
	}
	vs, _ := vpnStatus()
	here := false
	if w.active != nil && strings.HasPrefix(w.active.url, "http") {
		here = vpnForURL(w.container, w.active.url)
	}
	w.send(jsCall("state", map[string]any{"container": string(w.container), "tabs": list, "grid": w.grid,
		"mini": w.mini, "vpn": map[bool]string{true: vs, false: ""}[usesVPN(w.container)], "vpnHere": here,
		"vpnSmart":   usesVPN(w.container) && !vpnAll && w.container != containers.Private,
		"bookmarked": w.active != nil && store.HasBookmark(w.active.url), "focus": focusMenuState(),
		"dataSite": w.active != nil && store.IsDataSite(hostOf(w.active.url))}))
	w.dirty = true
	title := ""
	if w.active != nil {
		title = w.active.displayTitle()
	}
	setTitle(w.hwnd, windowTitle(w.container, title))
}

func (w *Window) overlay(kind string, data map[string]any) {
	if data == nil {
		data = map[string]any{}
	}
	data["kind"] = kind
	if w.float {
		w.send(jsCall("overlay", data))
		return
	}
	if sidePanelKind(kind) && !w.mini {
		w.openPanel(kind, data)
		return
	}
	w.send(jsCall("overlay", data))
}

// sidePanelKind: these panels open at the right side, next to the page,
// instead of covering the whole window.
func sidePanelKind(kind string) bool { return kind == "downloads" || kind == "ai" || kind == "aimenu" }

func (w *Window) openPanel(kind string, data map[string]any) {
	if w.panel == nil && !w.createPanel() {
		w.send(jsCall("overlay", data)) // fall back to the full-window panel
		return
	}
	js := jsCall("overlay", data)
	if w.panelReady {
		w.panel.Eval(js)
	} else {
		w.panelPending = append(w.panelPending, func() { w.panel.Eval(js) })
	}
	if w.uiFull {
		w.send("window.ronin&&window.ronin.closeOverlay()") // the side panel replaces a full-window panel
	}
	if w.panelKind == "" {
		w.panelKind = kind
		w.layout()
	}
	w.panelKind = kind
}

func (w *Window) closePanel() {
	if w.panelKind == "" {
		return
	}
	w.panelKind = ""
	w.layout()
	w.focusActive()
}

func (w *Window) createPanel() bool {
	p := edge.NewChromium()
	p.DataPath = filepath.Join(dataDir, "profiles", "UI")
	p.SetErrorCallback(func(err error) { logf("side panel: %v", err) })
	p.MessageCallback = func(msg string, _ *edge.ICoreWebView2, _ *edge.ICoreWebView2WebMessageReceivedEventArgs) {
		runOnUI(func() { w.onUIMessage(msg) })
	}
	p.AcceleratorKeyCallback = w.onKey
	if !p.Embed(w.hwnd) || p.Failed() {
		return false
	}
	w.panel = p
	if s, err := p.GetSettings(); err == nil {
		_ = s.PutAreDevToolsEnabled(false)
		_ = s.PutAreDefaultContextMenusEnabled(false)
		_ = s.PutIsStatusBarEnabled(false)
		_ = s.PutIsZoomControlEnabled(false)
	}
	_ = p.PutIsSwipeNavigationEnabled(false)
	r0, g0, b0 := uiBackground()
	p.SetBackgroundColour(r0, g0, b0, 255)
	p.NavigateToString(panelHTML(w.container))
	return true
}

func (w *Window) bar(tab *Tab, kind, text string, actions []map[string]string) {
	id := 0
	if tab != nil {
		id = tab.id
	}
	w.send(jsCall("bar", map[string]any{"tab": id, "kind": kind, "text": text, "actions": actions}))
}

type uiMsg struct {
	Cmd       string  `json:"cmd"`
	ID        int     `json:"id"`
	Text      string  `json:"text"`
	Height    float64 `json:"h"`
	Full      bool    `json:"full"`
	Container string  `json:"container"`
	Host      string  `json:"host"`
	URL       string  `json:"url"`
	Value     bool    `json:"value"`
	Number    int     `json:"number"`
	Question  string  `json:"question"`
	PIN       string  `json:"pin"`
	Key       string  `json:"key"`
	Provider  string  `json:"provider"`
	Model     string  `json:"model"`
	BaseURL   string  `json:"baseUrl"`
}

func (w *Window) onUIMessage(raw string) {
	var m uiMsg
	if json.Unmarshal([]byte(raw), &m) != nil {
		return
	}
	if w.float { // the small downloads window understands only these
		switch m.Cmd {
		case "panelready":
			w.uiReady = true
			for _, f := range w.pending {
				f()
			}
			w.pending = nil
		case "panelclose":
			floatCloseRequested(w)
		case "openronin":
			openContainer(containers.Personal, "")
		case "dlcancel":
			cancelJob(m.URL)
		case "dlpause":
			pauseJob(m.URL)
		case "dlresume":
			resumeJob(m.URL)
		case "dldirect":
			directJob(m.URL)
		case "dlshow":
			showJobFile(m.URL)
		case "dlfiles":
			openExplorer(downloadsDir(), false)
		}
		return
	}
	if w.onExtraMessage(m) || w.onPasswordMessage(m) {
		return
	}
	switch m.Cmd {
	case "dlpause":
		pauseJob(m.URL)
	case "dlresume":
		resumeJob(m.URL)
	case "dldirect":
		directJob(m.URL)
	case "ready":
		w.uiReady = true
		for _, f := range w.pending {
			f()
		}
		w.pending = nil
		w.sendState()
	case "aimenu":
		w.overlay("aimenu", nil)
	case "panelready":
		w.panelReady = true
		for _, f := range w.panelPending {
			f()
		}
		w.panelPending = nil
	case "panelclose":
		w.closePanel()
	case "tools":
		w.showTools()
	case "dataexport":
		if w.active != nil {
			w.active.exportData()
		}
	case "datasite":
		if t := w.active; t != nil && strings.HasPrefix(t.url, "http") {
			on := store.ToggleDataSite(hostOf(t.url))
			if on {
				w.bar(t, "ok", "📊 Data बटन इस साइट पर चालू: page के नीचे दाईं ओर हरा बटन (Excel + PDF).", nil)
				t.showDataButton()
			} else {
				w.bar(t, "info", "📊 Data बटन इस साइट पर बंद.", nil)
				t.runScript("(function(){var b=document.getElementById('ronin-data-btn');if(b)b.remove()})()", func(string) {})
			}
			w.sendState()
		}
	case "savepdf":
		if w.active != nil {
			w.active.savePDF()
		}
	case "screenshot":
		if w.active != nil {
			w.active.screenshot()
		}
	case "permanswer":
		answerPermission(m.URL)
	case "removeperm":
		if host, kind, ok := strings.Cut(m.URL, "|"); ok {
			store.SetSitePerm(host, kind, "")
		}
		w.overlay("settings", settingsData())
	case "reopentab":
		w.reopenClosedTab()
	case "dlfiles":
		openExplorer(downloadsDir(), false)
	case "layout":
		if m.Height >= 40 && m.Height <= 400 {
			w.uiHeight = int(m.Height + 0.5)
		}
		w.uiFull = m.Full
		w.layout()
	case "newtab":
		w.newTab("", true)
	case "close":
		if t := w.tabByID(m.ID); t != nil {
			w.closeTab(t)
		}
	case "select":
		if t := w.tabByID(m.ID); t != nil {
			w.selectTab(t)
		}
	case "voice":
		// the address box already has the focus; give it a moment, then open
		// Windows voice typing
		time.AfterFunc(250*time.Millisecond, startVoiceTyping)
	case "go":
		if w.active != nil {
			if u := omnibox.Resolve(m.Text); u != "" {
				w.active.userNavigate(u)
			}
		}
	case "back":
		if w.active != nil {
			w.active.wv.GoBack()
		}
	case "fwd":
		if w.active != nil {
			w.active.wv.GoForward()
		}
	case "reload":
		if t := w.active; t != nil {
			if t.errURL != "" {
				t.userNavigate(t.errURL) // try the failed address again, not the error page
			} else {
				t.wv.Reload()
			}
		}
	case "stop":
		if w.active != nil {
			w.active.wv.Stop()
		}
	case "home":
		if w.active != nil {
			w.active.showNewTab()
		}
	case "openwin":
		c := containers.Container(m.Container)
		switch {
		case c == containers.Personal || c == containers.Trading || c == containers.Private:
			newWindow(c, "") // a new window, so trading charts can sit side by side
		case c.Valid() && c != containers.Kids:
			openContainer(c, "")
		}
	case "mybank":
		if w.active != nil && store.AddBank(w.active.url) {
			u := w.active.url
			w.bar(w.active, "ok", "Added to My Banks. It will always open in Ronin Safe Money.", nil)
			w.active.userNavigate(u)
		}
	case "trust":
		if t := w.tabByID(m.ID); t != nil && m.URL != "" && store.Trust(m.URL) {
			t.userNavigate(m.URL)
		}
	case "addbank-open":
		if t := w.tabByID(m.ID); t != nil && m.URL != "" && store.AddBank(m.URL) {
			t.userNavigate(m.URL)
		}
	case "safemoney":
		if t := w.tabByID(m.ID); t != nil && t.url != "" {
			openContainer(containers.Banking, t.url)
		}
	case "dlallow":
		if t := w.tabByID(m.ID); t != nil && m.URL != "" {
			t.allowDownloadOnce(m.URL)
		}
	case "settings":
		if w.container == containers.Kids {
			w.overlay("pin", nil) // parents only
			return
		}
		w.overlay("settings", settingsData())
	case "kidspin":
		if pinLocked() {
			w.overlay("pin", map[string]any{"error": "Too many wrong tries. Wait one minute."})
		} else if checkPIN(m.PIN) {
			w.overlay("settings", settingsData())
		} else {
			pinFailed()
			w.overlay("pin", map[string]any{"error": "Wrong PIN."})
		}
	case "kidson":
		if w.container != containers.Kids && setKidsMode(kids.Level(m.Text), m.PIN) {
			restartRonin()
		} else {
			w.bar(nil, "error", "Kids mode not switched on: choose Junior or Teen and a PIN of 4 to 8 digits.", nil)
		}
	case "kidsoff":
		if !pinLocked() && clearKidsMode(m.PIN) {
			restartRonin()
		} else {
			pinFailed()
			w.bar(nil, "error", "Wrong PIN. Kids mode is still on.", nil)
		}
	case "kidsadd":
		if !pinLocked() && checkPIN(m.PIN) {
			if d := safemoney.NormalizeUserDomain(m.Host); d != "" {
				_ = store.Update(func(s *settingsT) { s.KidsSites = append(s.KidsSites, d) })
			}
			w.overlay("settings", settingsData())
		} else {
			pinFailed()
			w.bar(nil, "error", "Wrong PIN.", nil)
		}
	case "removekidssite":
		if checkPIN(m.PIN) {
			_ = store.Update(func(s *settingsT) { s.KidsSites = removeItem(s.KidsSites, m.Host) })
			w.overlay("settings", settingsData())
		}
	case "tradingsite":
		if t := w.active; t != nil && strings.HasPrefix(t.url, "http") {
			if d := safemoney.NormalizeUserDomain(t.url); d != "" {
				_ = store.Update(func(s *settingsT) { s.TradingSites = append(s.TradingSites, d) })
				u := t.url
				openContainer(containers.Trading, u)
			}
		}
	case "removetrading":
		_ = store.Update(func(s *settingsT) { s.TradingSites = removeItem(s.TradingSites, m.Host) })
		w.overlay("settings", settingsData())
	case "removeadoff":
		_ = store.SetAdblockPaused(m.Host, false)
		w.overlay("settings", settingsData())
	case "ai":
		w.prepareAI(ai.Task(m.Text), strings.TrimSpace(m.Question))
	case "aisend":
		w.sendAI()
	case "aikey":
		if m.Provider == "gemini" || m.Provider == "claude" || m.Provider == "custom" {
			if err := saveKey(m.Provider, m.Key); err != nil {
				w.bar(nil, "error", "Could not save the key in Windows Credential Manager.", nil)
			}
			geminiModelMu.Lock()
			geminiModelSel = ""
			geminiModelMu.Unlock()
		}
		w.overlay("settings", settingsData())
	case "aiprovider":
		_ = store.Update(func(s *settingsT) {
			s.AIProvider, s.AIModel, s.AIBaseURL = m.Provider, strings.TrimSpace(m.Model), strings.TrimSpace(m.BaseURL)
			if m.Number >= 0 {
				s.AIDailyCapRupees = m.Number
			}
		})
		geminiModelMu.Lock()
		geminiModelSel = ""
		geminiModelMu.Unlock()
		w.overlay("settings", settingsData())
	case "aitest":
		w.testAIKey()
	case "aigetkey":
		openContainer(containers.Personal, "https://aistudio.google.com/apikey")
	case "removebank":
		_ = store.Update(func(s *settingsT) { s.UserBanks = removeItem(s.UserBanks, m.Host) })
		w.overlay("settings", settingsData())
	case "datasiteremove":
		if store.IsDataSite(m.Host) {
			store.ToggleDataSite(m.Host)
		}
		w.overlay("settings", settingsData())
	case "trustadd": // Settings: a site wrongly stopped as dangerous
		store.Trust(m.URL)
		w.overlay("settings", settingsData())
	case "untrust":
		_ = store.Update(func(s *settingsT) { s.TrustedSites = removeItem(s.TrustedSites, m.Host) })
		w.overlay("settings", settingsData())
	case "remember":
		_ = store.Update(func(s *settingsT) { s.RememberBankLogin = m.Value })
		w.overlay("settings", settingsData())
	case "idlemins":
		_ = store.Update(func(s *settingsT) { s.SafeMoneyIdleMins = m.Number })
		w.overlay("settings", settingsData())
	case "health":
		w.overlay("health", healthData())
	case "download":
		if t := w.active; t != nil && t.canDownload(false) {
			pageURL, title, q := t.url, t.displayTitle(), videodl.Quality(m.Text)
			t.runScript(videodl.MediaJS, func(r string) {
				startVideoDownload(w, pageURL, title, q, videodl.MediaURLs(r))
			})
		}
	case "downloadmenu":
		if w.active != nil && w.active.canDownload(false) {
			w.overlay("download", map[string]any{"title": w.active.displayTitle()})
		}
	case "downloads":
		w.overlay("downloads", downloadsData(w))
	case "dlcancel":
		cancelJob(m.URL)
	case "dlshow":
		showJobFile(m.URL)
	case "dlfolder":
		_ = os.MkdirAll(videoDir(), 0o755)
		openExplorer(videoDir(), false)
	case "reader", "listen":
		if w.active != nil && w.container != containers.Banking && !w.active.isNewTab {
			js := readerJS
			if m.Cmd == "listen" {
				js = "window.__roninListen=1;" + js // Reader + start reading aloud
			}
			w.active.c.Eval(js)
		}
	case "adpause":
		if t := w.active; t != nil && strings.HasPrefix(t.url, "http") {
			if store.SetAdblockPaused(t.url, m.Value) {
				t.adPaused = m.Value
				js := `try{localStorage.removeItem("ronin_ab_off")}catch(e){};location.reload()`
				if m.Value {
					js = `try{localStorage.setItem("ronin_ab_off","1")}catch(e){};location.reload()`
				}
				t.c.Eval(js)
				w.sendState()
			}
		}
	case "grid":
		if w.container == containers.Trading && !w.mini {
			w.setGrid(m.Number)
		}
	case "mini":
		if w.container == containers.Trading && w.active != nil && strings.HasPrefix(w.active.url, "http") {
			newMiniWindow(w.active.url)
		}
	case "vpnimport":
		go func() {
			name, err := importVPNConfig()
			runOnUI(func() {
				switch {
				case err != nil:
					w.bar(nil, "error", "VPN config not imported: "+err.Error(), nil)
				case name != "":
					_ = store.Update(func(s *settingsT) { s.VPNOn = true })
					w.overlay("settings", settingsData())
				}
			})
		}()
	case "vpnon":
		if _, err := os.Stat(wgConfPath()); err == nil || !m.Value {
			_ = store.Update(func(s *settingsT) { s.VPNOn = m.Value })
		}
		w.overlay("settings", settingsData())
	case "theme":
		toggleTheme()
	case "darkoff":
		// Night mode off (or back on) for this one site
		if t := w.active; t != nil && strings.HasPrefix(t.url, "http") {
			js := `try{localStorage.setItem("ronin_dark_off","1")}catch(e){};window.__roninDarkApply&&window.__roninDarkApply()`
			if !m.Value {
				js = `try{localStorage.removeItem("ronin_dark_off")}catch(e){};window.__roninDarkApply&&window.__roninDarkApply()`
			}
			t.c.Eval(js)
		}
	case "bookmark":
		if t := w.active; t != nil && strings.HasPrefix(t.url, "http") &&
			(w.container == containers.Personal || w.container == containers.Trading) {
			if store.ToggleBookmark(t.displayTitle(), t.url) {
				w.bar(t, "ok", "Bookmark saved. Find it in Menu \u2192 Bookmarks.", nil)
			} else {
				w.bar(t, "info", "Bookmark removed.", nil)
			}
			w.sendState()
		}
	case "bookmarks":
		w.send(jsCall("overlay", map[string]any{"kind": "bookmarks", "list": store.Get().Bookmarks}))
	case "removebookmark":
		if store.HasBookmark(m.URL) {
			store.ToggleBookmark("", m.URL)
		}
		w.send(jsCall("overlay", map[string]any{"kind": "bookmarks", "list": store.Get().Bookmarks}))
		w.sendState()
	case "newtabfront":
		_ = store.Update(func(s *settingsT) { s.NewTabFront = m.Value })
		w.overlay("settings", settingsData())
	case "vpnall":
		_ = store.Update(func(s *settingsT) { s.VPNAll = m.Value })
		w.overlay("settings", settingsData())
	case "restart":
		restartRonin()
	case "checkupdate":
		checkUpdateNow(w)
	case "updatenow":
		installUpdateNow(w)
	case "vpncheck":
		openContainer(containers.Private, "https://www.cloudflare.com/cdn-cgi/trace") // private windows always use the VPN
	case "unlock":
		w.idleLocked = false
		w.lastActive = time.Now()
		if w.active != nil {
			w.active.showNewTab()
		}
	}
}

func removeItem(list []string, item string) []string {
	var out []string
	for _, x := range list {
		if x != item {
			out = append(out, x)
		}
	}
	return out
}

// onKey handles keyboard shortcuts from any web view in this window.
// It is called inside a WebView2 event, so the action itself runs later
// from the message loop.
func (w *Window) onKey(vk uint) bool {
	ctrl, shift, alt := keyDown(vkControl), keyDown(vkShift), keyDown(vkMenu)
	if act := w.shortcut(vk, ctrl, shift, alt); act != nil {
		runOnUI(act)
		return true
	}
	return false
}

func (w *Window) shortcut(vk uint, ctrl, shift, alt bool) func() {
	if w.float {
		return nil
	}
	back := func() {
		if w.active != nil {
			w.active.wv.GoBack()
		}
	}
	fwd := func() {
		if w.active != nil {
			w.active.wv.GoForward()
		}
	}
	switch {
	case ctrl && shift && vk == 'A':
		return func() {
			w.ui.Focus() // the search box must get the typing
			w.send("window.ronin&&window.ronin.tabSearch()")
		}
	case ctrl && vk == 'H':
		return func() { w.onExtraMessage(uiMsg{Cmd: "history"}) }
	case ctrl && (vk == 0xBB || vk == 0x6B): // Ctrl + (also on the number pad)
		return func() { w.onExtraMessage(uiMsg{Cmd: "zoom", Number: 1}) }
	case ctrl && (vk == 0xBD || vk == 0x6D): // Ctrl -
		return func() { w.onExtraMessage(uiMsg{Cmd: "zoom", Number: -1}) }
	case ctrl && (vk == '0' || vk == 0x60): // Ctrl 0: normal size
		return func() { w.onExtraMessage(uiMsg{Cmd: "zoom", Number: 0}) }
	case ctrl && vk == 'D':
		return func() { w.onUIMessage(`{"cmd":"bookmark"}`) }
	case ctrl && shift && vk == 'B':
		return func() {
			w.ui.Focus()
			w.onUIMessage(`{"cmd":"bookmarks"}`)
		}
	case ctrl && vk == 'P':
		return func() {
			if w.active != nil {
				w.active.savePDF()
			}
		}
	case ctrl && shift && vk == 'T':
		return func() { w.reopenClosedTab() }
	case ctrl && vk == 'T':
		return func() { w.newTab("", true) }
	case ctrl && shift && vk == 'J', ctrl && vk == 'J':
		return func() { w.overlay("downloads", downloadsData(w)) }
	case ctrl && vk == 'W':
		return func() {
			if w.active != nil {
				w.closeTab(w.active)
			}
		}
	case ctrl && (vk == 'L' || vk == 'E'):
		return func() { w.send("window.ronin&&window.ronin.focusAddress()") }
	case ctrl && vk >= '1' && vk <= '9':
		n := int(vk - '1')
		return func() {
			if len(w.tabs) == 0 {
				return
			}
			if vk == '9' || n >= len(w.tabs) {
				n = len(w.tabs) - 1
			}
			w.selectTab(w.tabs[n])
		}
	case ctrl && vk == 0x09: // Tab
		return func() { w.cycleTab(!shift) }
	case ctrl && vk == 'N' && shift:
		return func() { newWindow(containers.Private, "") }
	case ctrl && vk == 'N':
		return func() { newWindow(containers.Personal, "") }
	case (alt && vk == 0x25) || vk == 0xA6: // Alt+Left, Browser Back key
		return back
	case (alt && vk == 0x27) || vk == 0xA7:
		return fwd
	case vk == 0x74 || (ctrl && vk == 'R'): // F5
		return func() {
			if w.active != nil {
				w.active.wv.Reload()
			}
		}
	case vk == 0x1B && w.uiFull: // Esc closes a panel
		return func() { w.send("window.ronin&&window.ronin.closeOverlay()") }
	}
	return nil
}

// ---- layout ----------------------------------------------------------------

func (w *Window) scale(px int) int32 { return int32(px * dpiOf(w.hwnd) / 96) }

func (w *Window) layout() {
	if w.ui == nil {
		return
	}
	r := clientRect(w.hwnd)
	full := edge.Rect{Left: r.Left, Top: r.Top, Right: r.Right, Bottom: r.Bottom}
	if w.float {
		w.ui.SetSize(full)
		return
	}
	barH := w.scale(w.uiHeight)
	if barH > r.Bottom-r.Top {
		barH = r.Bottom - r.Top
	}
	if w.uiFull {
		w.ui.SetSize(full)
	} else {
		w.ui.SetSize(edge.Rect{Left: r.Left, Top: r.Top, Right: r.Right, Bottom: r.Top + barH})
	}
	frame := int32(0)
	if w.container == containers.Banking {
		frame = w.scale(4) // green frame around the bank page
	}
	content := edge.Rect{Left: r.Left + frame, Top: r.Top + barH, Right: r.Right - frame, Bottom: r.Bottom - frame}
	if content.Bottom < content.Top {
		content.Bottom = content.Top
	}
	if w.panel != nil {
		if w.panelKind != "" && !w.uiFull {
			pw := w.scale(400)
			if max := (content.Right - content.Left) / 2; pw > max {
				pw = max
			}
			content.Right -= pw
			w.panel.SetSize(edge.Rect{Left: content.Right, Top: r.Top + barH, Right: r.Right, Bottom: r.Bottom})
			_ = w.panel.Show()
		} else {
			_ = w.panel.Hide()
		}
	}
	keepLive := w.container == containers.Trading || w.container == containers.Calls
	for _, t := range w.tabs {
		if t.c == nil {
			continue
		}
		if keepLive {
			// TRADING: background tabs are never hidden or paused, so every
			// chart keeps its live price. They are only moved out of sight.
			r := content
			cell := w.cellOf(t)
			if cell >= 0 && !w.uiFull {
				r = w.cellRect(content, cell)
			} else if t != w.active || w.uiFull || w.grid > 1 {
				shift := (content.Right - content.Left) + 200
				r.Left -= shift + 10000
				r.Right -= shift + 10000
			}
			t.c.SetSize(r)
			if !t.visible {
				_ = t.c.Show()
				t.visible = true
			}
			continue
		}
		t.c.SetSize(content)
		visible := t == w.active && !w.uiFull
		if visible != t.visible {
			if visible {
				_ = t.c.Show()
			} else {
				_ = t.c.Hide()
			}
			t.visible = visible
		}
	}
}

func (w *Window) notifyMoved() {
	if w.ui != nil {
		_ = w.ui.NotifyParentWindowPositionChanged()
	}
	for _, t := range w.tabs {
		if t.c != nil {
			_ = t.c.NotifyParentWindowPositionChanged()
		}
	}
}

func (w *Window) focusActive() {
	if w.active != nil && w.active.c != nil && !w.uiFull {
		w.active.c.Focus()
	}
}

// ---- tabs ------------------------------------------------------------------

func (w *Window) tabByID(id int) *Tab {
	for _, t := range w.tabs {
		if t.id == id {
			return t
		}
	}
	return nil
}

func (w *Window) newTab(u string, userInitiated bool) *Tab {
	if w.closing {
		return nil
	}
	w.nextID++
	t := &Tab{id: w.nextID, w: w}
	if !t.create() {
		w.bar(nil, "error", "Could not open a new tab.", nil)
		return nil
	}
	w.tabs = append(w.tabs, t)
	w.selectTab(t)
	if u == "" {
		t.showNewTab()
	} else if userInitiated {
		t.userNavigate(u)
	} else {
		t.scriptNavigate(u)
	}
	return t
}

// newTabBehind opens a link in a new tab without leaving the current page.
func (w *Window) newTabBehind(u string, userInitiated bool) *Tab {
	if w.closing || w.active == nil || w.mini || w.grid > 1 {
		return w.newTab(u, userInitiated)
	}
	w.nextID++
	t := &Tab{id: w.nextID, w: w}
	if !t.create() {
		return nil
	}
	w.tabs = append(w.tabs, t)
	if userInitiated {
		t.userNavigate(u)
	} else {
		t.scriptNavigate(u)
	}
	w.layout() // keeps the new tab hidden behind the current one
	w.sendState()
	return t
}

func (w *Window) selectTab(t *Tab) {
	w.active = t
	if t != nil {
		t.lastShown = time.Now()
		t.asleep = false
	}
	w.layout()
	w.focusActive()
	w.sendState()
}

func (w *Window) cycleTab(forward bool) {
	if len(w.tabs) < 2 || w.active == nil {
		return
	}
	i := 0
	for k, t := range w.tabs {
		if t == w.active {
			i = k
		}
	}
	if forward {
		i = (i + 1) % len(w.tabs)
	} else {
		i = (i - 1 + len(w.tabs)) % len(w.tabs)
	}
	w.selectTab(w.tabs[i])
}

func (w *Window) closeTab(t *Tab) {
	idx := -1
	for i, x := range w.tabs {
		if x == t {
			idx = i
		}
	}
	if idx < 0 {
		return
	}
	if len(w.tabs) == 1 {
		logf("window closed: its last tab was closed (%s)", w.container)
		if beforeLastWindowCloses(w) {
			w.hiddenDL = true
			pShowWindow.Call(w.hwnd, 0)
			return
		}
		w.close()
		return
	}
	w.tabs = append(w.tabs[:idx], w.tabs[idx+1:]...)
	if strings.HasPrefix(t.url, "http") && w.container != containers.Banking {
		w.closedURLs = append(w.closedURLs, t.url)
		if len(w.closedURLs) > 15 {
			w.closedURLs = w.closedURLs[1:]
		}
	}
	t.destroy()
	if w.active == t {
		if idx >= len(w.tabs) {
			idx = len(w.tabs) - 1
		}
		w.active = w.tabs[idx]
	}
	w.selectTab(w.active)
}

// reopenClosedTab brings back the last tab closed in this window (Ctrl+Shift+T).
func (w *Window) reopenClosedTab() {
	if n := len(w.closedURLs); n > 0 {
		u := w.closedURLs[n-1]
		w.closedURLs = w.closedURLs[:n-1]
		w.newTab(u, true)
	}
}

func (w *Window) close() {
	if w.closing {
		return
	}
	onWindowClosing(w)
	w.closing = true
	pKillTimer.Call(w.hwnd, timerID)
	if w.container == containers.Banking || w.container == containers.Private {
		for _, t := range w.tabs {
			t.deleteCookies()
		}
	}
	for _, t := range w.tabs {
		t.destroy()
	}
	w.tabs = nil
	w.active = nil
	for _, v := range []*edge.Chromium{w.panel, w.ui} {
		if v == nil {
			continue
		}
		v.ShuttingDown()
		if c := v.GetController(); c != nil {
			call(uintptr(unsafe.Pointer(c)), 24) // ICoreWebView2Controller::Close
		}
	}
	pDestroyWindow.Call(w.hwnd)
	last := true
	for _, o := range windowsByHwnd {
		if o != w && o.container == w.container && !o.closing {
			last = false
		}
	}
	if last {
		newSession(w.container)
	}
}

// ---- timer: Safe Money protections -----------------------------------------

func (w *Window) onTimer() {
	if w.closing {
		return
	}
	if w.active != nil && w.container != containers.Banking {
		w.active.checkForVideo()
		w.active.pwTake() // logins that do not load a new page
		w.active.zoomChanged()
	}
	w.dirty = false // tabs are not kept on disk: a normal start begins fresh
	if w.container == containers.Personal || w.container == containers.Private {
		for _, t := range w.tabs {
			if t != w.active && !t.visible && time.Since(t.lastShown) > 15*time.Minute {
				t.sleep()
			}
		}
	}
	if w.container != containers.Banking {
		return
	}
	fg, _, _ := pGetForegroundWindow.Call()
	// 1. idle lock: the Safe Money window was not used for N minutes
	// (the user was away, or busy in other programs).
	if fg == w.hwnd && idleMillis() < 2*timerEveryMs {
		w.lastActive = time.Now()
	}
	mins := store.Get().SafeMoneyIdleMins
	if !w.idleLocked && time.Since(w.lastActive) > time.Duration(mins)*time.Minute {
		w.idleLocked = true
		for _, t := range w.tabs {
			t.deleteCookies()
			t.showNewTab()
		}
		w.overlay("locked", map[string]any{"mins": mins})
	}
	// 2. clear copied text after 30 seconds
	seq := clipboardSeq()
	if seq != w.lastClipSeq {
		w.lastClipSeq = seq
		if fg == w.hwnd {
			w.clipCopiedAt = time.Now()
		} else {
			w.clipCopiedAt = time.Time{}
		}
	} else if !w.clipCopiedAt.IsZero() && time.Since(w.clipCopiedAt) >= 30*time.Second {
		if clearClipboard(w.hwnd) {
			w.lastClipSeq = clipboardSeq()
		}
		w.clipCopiedAt = time.Time{}
	}
	// 3. remote-access programs, every minute
	if time.Since(w.lastRemoteCheck) >= time.Minute {
		w.checkRemoteTools(false)
	}
}

func (w *Window) checkRemoteTools(first bool) {
	w.lastRemoteCheck = time.Now()
	go func() {
		names := safemoney.RemoteToolsRunning(processNames())
		runOnUI(func() {
			key := strings.Join(names, ",")
			if len(names) == 0 {
				w.remoteShown = ""
				return
			}
			if key == w.remoteShown && !first {
				return
			}
			w.remoteShown = key
			w.overlay("remote", map[string]any{"tools": names})
		})
	}()
}

func (w *Window) checkAntivirus() {
	go func() {
		ok, known := antivirusOn()
		if known && !ok {
			runOnUI(func() {
				w.bar(nil, "warn", "No antivirus protection is switched on in Windows Security. Turn on Microsoft Defender before banking.", nil)
			})
		}
	}()
}

// ---- opening containers ----------------------------------------------------

// openContainer shows u in a window of container c (an existing one if open).
func openContainer(c containers.Container, u string) {
	if kidsLevel() != "" {
		c = containers.Kids // a Kids account can only open Kids windows
	}
	for _, w := range windowsByHwnd {
		if w.container == c && !w.closing {
			if w.idleLocked {
				w.idleLocked = false
				w.lastActive = time.Now()
				w.send("window.ronin&&window.ronin.closeOverlay()")
			}
			w.newTab(u, true)
			pShowWindow.Call(w.hwnd, 9) // SW_RESTORE if minimised
			pSetForegroundWindow.Call(w.hwnd)
			return
		}
	}
	newWindow(c, u)
}

func hostOf(raw string) string {
	u, err := url.Parse(raw)
	if err != nil {
		return ""
	}
	return strings.ToLower(u.Hostname())
}

func fmtBytes(n int64) string {
	if n < 1024*1024 {
		return fmt.Sprintf("%d KB", n/1024)
	}
	return fmt.Sprintf("%.1f MB", float64(n)/1024/1024)
}

// ---- trading chart grid ------------------------------------------------------

const chartURL = "https://in.tradingview.com/chart/"

// gridTabs are the tabs shown in the grid cells (the "page" with the active tab).
func (w *Window) gridTabs() []*Tab {
	if w.grid < 2 || len(w.tabs) == 0 {
		return nil
	}
	idx := 0
	for i, t := range w.tabs {
		if t == w.active {
			idx = i
		}
	}
	start := (idx / w.grid) * w.grid
	end := start + w.grid
	if end > len(w.tabs) {
		end = len(w.tabs)
	}
	return w.tabs[start:end]
}

func (w *Window) cellOf(t *Tab) int {
	for i, g := range w.gridTabs() {
		if g == t {
			return i
		}
	}
	return -1
}

func (w *Window) inGrid(t *Tab) bool { return w.cellOf(t) >= 0 }

func (w *Window) cellRect(c edge.Rect, i int) edge.Rect {
	gap := w.scale(2)
	wd, ht := c.Right-c.Left, c.Bottom-c.Top
	switch w.grid {
	case 2:
		half := wd / 2
		if i == 0 {
			return edge.Rect{Left: c.Left, Top: c.Top, Right: c.Left + half - gap/2, Bottom: c.Bottom}
		}
		return edge.Rect{Left: c.Left + half + gap/2, Top: c.Top, Right: c.Right, Bottom: c.Bottom}
	case 4:
		hw, hh := wd/2, ht/2
		col, row := int32(i%2), int32(i/2)
		r := edge.Rect{Left: c.Left + col*hw, Top: c.Top + row*hh, Right: c.Left + (col+1)*hw, Bottom: c.Top + (row+1)*hh}
		if col == 0 {
			r.Right -= gap / 2
		} else {
			r.Left += gap / 2
			r.Right = c.Right
		}
		if row == 0 {
			r.Bottom -= gap / 2
		} else {
			r.Top += gap / 2
			r.Bottom = c.Bottom
		}
		return r
	}
	return c
}

// setGrid switches between 1, 2 and 4 charts, opening extra chart tabs if needed.
func (w *Window) setGrid(n int) {
	if n != 2 && n != 4 {
		n = 0
	}
	w.grid = n
	for n > 1 && len(w.tabs) < n {
		keep := w.active
		if w.newTab(chartURL, true) == nil {
			break
		}
		if keep != nil {
			w.active = keep
		}
	}
	w.layout()
	w.sendState()
}

// tabFocused is called when the user clicks inside a chart cell.
func (w *Window) tabFocused(t *Tab) {
	if w.grid > 1 && w.active != t && w.inGrid(t) {
		w.active = t
		w.sendState()
	}
}
