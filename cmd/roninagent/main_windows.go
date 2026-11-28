//go:build windows

// Ronin Agent Lab for the laptop: a separate Ronin window that Claude can drive (through the owner's relay), with
// every rule enforced here on the laptop. The owner's own Ronin is not touched. Data (logins, link key, journal)
// stays next to the exe in .\data.
package main

import (
	_ "embed"
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"time"
	"unsafe"

	"github.com/wailsapp/go-webview2/pkg/edge"
	"golang.org/x/sys/windows/registry"

	"roninbrowser/internal/agentcore"
)

//go:embed banner.html
var bannerHTML string

var (
	logMu   sync.Mutex
	logFile *os.File
)

// logf: metadata only (tool, site, ok / refused), never page text or typed text.
func logf(f string, a ...any) {
	logMu.Lock()
	defer logMu.Unlock()
	if logFile != nil {
		fmt.Fprintf(logFile, "%s %s\n", time.Now().Format("01-02 15:04:05"), fmt.Sprintf(f, a...))
	}
}

type app struct {
	hwnd         uintptr
	ui, web      *edge.Chromium
	page         *cdpPage
	drv          *agentcore.Driver
	link         *agentcore.Link
	cardOpen     bool
	approveCh    chan bool
	lastInput    uint32
	busyUntil    atomic.Int64 // the owner clicked / pressed on the page until then (unix ms)
	status       string
	asked        string
	testPages    map[string]string
	selftest     bool
	proxy        *agentcore.Proxy
	proxyBlocked atomic.Int32
	ownNav       int // our own start page (data:) may load; nothing else of that kind
	curX         int32
	curY         int32
}

func main() {
	selftest := flag.Bool("selftest", false, "run the built-in checks on made-up pages, write data\\selftest.txt, exit")
	flag.Parse()
	exe, _ := os.Executable()
	data := filepath.Join(filepath.Dir(exe), "data")
	os.MkdirAll(data, 0o700)
	logFile, _ = os.OpenFile(filepath.Join(data, "agent-log.txt"), os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o600)
	logf("start (selftest=%v)", *selftest)
	if err := initWin32(); err != nil {
		logf("win32: %v", err)
		return
	}
	a := &app{approveCh: make(chan bool, 1)}
	a.hwnd = createMainWindow("Ronin Agent Lab — C (laptop)")
	onMainMsg = a.msg
	if *selftest {
		pShowWindow.Call(a.hwnd, swShow)
	} else {
		pShowWindow.Call(a.hwnd, swShowMaximized)
	}
	if !a.makeViews(data) {
		logf("WebView2 did not start")
		writeResult(data, "FAIL webview2 did not start\n")
		return
	}
	a.drv = &agentcore.Driver{P: a.page, Journal: filepath.Join(data, "journal.json"), Log: logLine}
	a.drv.OnOwnerAsked = func(reason string) {
		runOnUI(func() { a.asked = reason; a.ui.Eval("showButtons()"); a.refresh() })
	}
	a.lastInput = lastInputTick() // only input AFTER this moment counts as the owner's
	a.drv.OwnerBusy = func() bool { return time.Now().UnixMilli() < a.busyUntil.Load() }
	a.selftest = *selftest
	pSetTimer.Call(a.hwnd, 1, 300, 0)
	if *selftest {
		a.testPages = selfTestPages()
		a.drv.Journal = filepath.Join(data, "selftest-journal.json")
		os.Remove(a.drv.Journal)
		go a.selfTest(data)
	} else {
		url, pub := agentcore.RelayConfig()
		store := &agentcore.Store{Path: filepath.Join(data, "link.bin"), Seal: dpapiSeal, Open: dpapiOpen}
		a.link = &agentcore.Link{Base: url, Name: "laptop", RelayPub: pub, Store: store, Driver: a.drv, Approve: a.approve, Log: logLine}
		go func() {
			if store.Get("paired") == "" && !a.link.Pair() {
				runOnUI(func() {
					a.status = "⚠ relay से जुड़ नहीं पाया (pairing बंद हो सकता है)"
					a.refresh()
				})
			}
			a.link.Start()
		}()
		go autoStart(exe)
	}
	runMessageLoop()
	if a.link != nil {
		a.link.Stop()
	}
}

func (a *app) makeViews(data string) bool {
	ui := edge.NewChromium()
	ui.DataPath = filepath.Join(data, "ui")
	ui.SetErrorCallback(func(err error) { logf("banner: %v", err) })
	ui.MessageCallback = func(msg string, _ *edge.ICoreWebView2, _ *edge.ICoreWebView2WebMessageReceivedEventArgs) {
		runOnUI(func() { a.onBanner(msg) })
	}
	if !ui.Embed(a.hwnd) || ui.Failed() {
		return false
	}
	if s, err := ui.GetSettings(); err == nil {
		s.PutAreDevToolsEnabled(false)
		s.PutAreDefaultContextMenusEnabled(false)
		s.PutIsStatusBarEnabled(false)
		s.PutIsZoomControlEnabled(false)
	}
	ui.NavigateToString(bannerHTML)
	a.ui = ui

	// every connection of the Claude tab goes through Ronin's local proxy, which checks the address it really
	// connects to (no home network / this PC, whatever DNS says, also for WebSockets and service workers)
	a.proxy = &agentcore.Proxy{Blocked: func(addr string) { a.proxyBlocked.Add(1); logf("blocked: private address (proxy)") }}
	if err := a.proxy.Start(); err != nil {
		logf("proxy: %v", err)
		return false
	}
	web := edge.NewChromium()
	web.DataPath = filepath.Join(data, "profile") // logins stay (cookies) like a normal browser
	web.AdditionalBrowserArgs = []string{"--proxy-server=http://" + a.proxy.Addr, "--proxy-bypass-list=<-loopback>",
		"--force-webrtc-ip-handling-policy=disable_non_proxied_udp", "--disable-quic",
		// Ronin minimized / behind other windows: the page's timers and AJAX keep their pace (owner, 28 Nov: EPDS)
		"--disable-background-timer-throttling", "--disable-renderer-backgrounding", "--disable-backgrounding-occluded-windows"}
	web.SetErrorCallback(func(err error) { logf("page: %v", err) })
	web.SetPermission(edge.CoreWebView2PermissionKindNotifications, edge.CoreWebView2PermissionStateDeny)
	web.SetPermission(edge.CoreWebView2PermissionKindGeolocation, edge.CoreWebView2PermissionStateDeny)
	web.SetPermission(edge.CoreWebView2PermissionKindClipboardRead, edge.CoreWebView2PermissionStateDeny)
	if !web.Embed(a.hwnd) || web.Failed() {
		return false
	}
	a.web = web
	a.page = &cdpPage{c: web, loaded: make(chan struct{}, 4)}
	web.NavigationCompletedCallback = func(_ *edge.ICoreWebView2, _ *edge.ICoreWebView2NavigationCompletedEventArgs) {
		a.ownNav = 0
		select {
		case a.page.loaded <- struct{}{}:
		default:
		}
		a.page.mu.Lock()
		a.page.ctxID = 0
		a.page.mu.Unlock()
	}
	web.WebResourceRequestedCallback = a.onRequest
	web.AddWebResourceRequestedFilter("*", edge.COREWEBVIEW2_WEB_RESOURCE_CONTEXT_ALL)
	if s, err := web.GetSettings(); err == nil {
		s.PutAreDevToolsEnabled(false)
		s.PutIsStatusBarEnabled(false)
	}
	if ctrl := web.GetController(); ctrl != nil {
		if core, err := ctrl.GetCoreWebView2(); err == nil && core != nil {
			hookPageEvents(uintptr(unsafe.Pointer(core)), func(u string) { web.Navigate(u) }, func() bool {
				if a.ownNav > 0 {
					a.ownNav--
					return true
				}
				return false
			})
		}
	}
	a.ownNav++
	web.NavigateToString(`<!doctype html><meta charset="utf-8"><body style="font:18px 'Nirmala UI',sans-serif;padding:40px;color:#333"><h2>Ronin Agent Lab</h2><p>यह window C के लिए है। C यहाँ काम करेगा; ऊपर 🤖 दबाकर रोक / चालू कर सकते हैं।</p>`)
	a.layout()
	return true
}

func (a *app) barHeight() int32 {
	h := int32(48)
	if a.cardOpen {
		h += 108
	}
	return h * int32(dpiOf(a.hwnd)) / 96
}

func (a *app) layout() {
	r := clientRect(a.hwnd)
	bh := a.barHeight()
	if a.ui != nil {
		a.ui.SetSize(edge.Rect{Left: r.Left, Top: r.Top, Right: r.Right, Bottom: r.Top + bh})
	}
	if a.web != nil {
		a.web.SetSize(edge.Rect{Left: r.Left, Top: r.Top + bh, Right: r.Right, Bottom: r.Bottom})
	}
}

func (a *app) msg(hwnd, msg, wparam, lparam uintptr) (uintptr, bool) {
	if hwnd != a.hwnd {
		return 0, false
	}
	switch msg {
	case wmSize:
		if wparam != 1 { // SIZE_MINIMIZED: keep the page's size (a 0 x 0 page is a hidden page: its timers stop)
			a.layout()
		}
		return 0, true
	case wmTimer:
		a.watchOwner()
		return 0, true
	case wmClose, wmDestroy:
		pPostQuitMessage.Call(0)
		return 0, msg == wmDestroy
	}
	return 0, false
}

// watchOwner: the owner uses the page (a click, a key, the wheel in this window, over the page): Claude's next step
// waits a moment (never pauses: owner, 28 Nov - only PAUSE / STOP stop Claude). Moving the mouse does not count.
// Claude's own input goes through the browser, not Windows, so it never counts here.
func (a *app) watchOwner() {
	t := lastInputTick()
	x, y := cursorInClient(a.hwnd)
	moved := x != a.curX || y != a.curY
	a.curX, a.curY = x, y
	clicked := false
	for _, vk := range []uintptr{1, 2, 4} { // left, right, middle button: pressed since the last look
		if r, _, _ := pGetAsyncKeyState.Call(vk); r&0x8001 != 0 {
			clicked = true
		}
	}
	if t != a.lastInput && !a.selftest { // (the self-test presses OwnerTouched itself)
		a.lastInput = t
		fg, _, _ := pGetForegroundWindow.Call()
		if fg == a.hwnd && y > a.barHeight() && a.drv != nil && (clicked || !moved) {
			a.busyUntil.Store(time.Now().Add(1500 * time.Millisecond).UnixMilli())
		}
	}
	a.refresh()
}

func (a *app) refresh() {
	if a.ui == nil || a.drv == nil {
		return
	}
	s := "🟢 C चालू"
	switch {
	case a.drv.IsStopped():
		s = "⛔ STOP: C रुका है (RESUME से चालू)"
	case a.drv.IsPaused():
		s = "⏸ आपने PAUSE दबाया: C रुका है। RESUME दबाएँ"
	case a.drv.Holding():
		// the real address of the page (from the browser, not the page): check it before typing a password
		s = "🙋 C कह रहा है: " + a.asked + " — कर लें, फिर DONE   |   site: " + siteOf(a.web.Source())
	case a.link != nil && !a.link.Online():
		s = "… relay से जुड़ रहा है"
	}
	if a.status != "" {
		s = a.status
	}
	b, _ := json.Marshal(s)
	a.ui.Eval("setStatus(" + string(b) + ")")
}

func (a *app) onBanner(m string) {
	a.status = ""
	switch m {
	case "done":
		a.drv.OwnerDone()
	case "resume":
		a.drv.Resume()
	case "pause":
		a.drv.OwnerTouched()
	case "stop":
		a.drv.OwnerStop()
	case "checked":
		a.drv.Checked()
	case "connect":
		if a.link != nil {
			a.link.OpenConnectWindow()
			a.status = "🔗 3 मिनट: अब C में Connect दबाएँ, फिर यहाँ Allow दबाकर रखें"
		}
	case "disconnect":
		if a.link != nil {
			a.link.DisconnectClaude()
			a.status = "⛔ C हटा दिया गया (सभी devices से)"
		}
	case "allow", "deny":
		if a.cardOpen {
			a.cardOpen = false
			a.ui.Eval("hideCard()")
			a.layout()
			select {
			case a.approveCh <- m == "allow":
			default:
			}
		}
	}
	a.refresh()
}

// approve: the one-time Connect card (2 minutes). True only if the owner held Allow.
func (a *app) approve(text string) bool {
	for len(a.approveCh) > 0 {
		<-a.approveCh
	}
	b, _ := json.Marshal(text)
	runOnUI(func() {
		a.cardOpen = true
		a.layout()
		a.ui.Eval("showCard(" + string(b) + ")")
	})
	select {
	case ok := <-a.approveCh:
		return ok
	case <-time.After(120 * time.Second):
		runOnUI(func() { a.cardOpen = false; a.ui.Eval("hideCard()"); a.layout() })
		return false
	}
}

// onRequest: the network guard for every request of the Claude tab (runs on the UI thread, must not wait).
func (a *app) onRequest(req *edge.ICoreWebView2WebResourceRequest, args *edge.ICoreWebView2WebResourceRequestedEventArgs) {
	uri, err := req.GetUri()
	if err != nil || uri == "" || a.drv == nil {
		a.respond(args, 403, "text/plain", "Blocked by Ronin") // not understood = not allowed
		return
	}
	method, _ := req.GetMethod()
	ctx, _ := args.GetResourceContext()
	main := ctx == edge.COREWEBVIEW2_WEB_RESOURCE_CONTEXT_DOCUMENT
	serve := func() {
		if a.testPages == nil {
			return
		}
		h := agentcore.Host(uri)
		path := "/"
		if i := strings.Index(uri, h); i >= 0 {
			path = strings.SplitN(strings.SplitN(uri[i+len(h):], "?", 2)[0], "#", 2)[0]
		}
		if p, ok := a.testPages[h+path]; ok || a.testPages[h] != "" {
			if !ok {
				p = a.testPages[h]
			}
			a.respond(args, 200, "text/html; charset=utf-8", p)
		} else if strings.HasSuffix(h, ".example") || h == "web.dhan.co" {
			a.respond(args, 200, "application/json", "{}")
		}
	}
	if a.drv.Holding() {
		serve()
		return
	}
	if why := a.drv.GuardNoDNS(method, uri, main); why != "" {
		a.respond(args, 403, "text/plain", "Blocked by Ronin")
		return
	}
	h := agentcore.Host(uri)
	if priv, known := a.drv.PrivateKnown(h); known || a.testPages != nil {
		if priv {
			a.drv.Block("private address " + h)
			a.respond(args, 403, "text/plain", "Blocked by Ronin")
			return
		}
		serve()
		return
	}
	// a name not seen before: the request waits until DNS said where it goes (never the home network)
	def, err := args.GetDeferral()
	if err != nil || def == nil {
		a.respond(args, 403, "text/plain", "Blocked by Ronin")
		return
	}
	args.AddRef()
	go func() {
		p := a.drv.IsPrivate(h)
		runOnUI(func() {
			if p {
				a.drv.Block("private address " + h)
				a.respond(args, 403, "text/plain", "Blocked by Ronin")
			}
			def.Complete()
			def.Release()
			args.Release()
		})
	}()
}

func (a *app) respond(args *edge.ICoreWebView2WebResourceRequestedEventArgs, code int, ctype, body string) {
	env := a.web.Environment()
	if env == nil {
		return
	}
	reason := "OK"
	if code != 200 {
		reason = "Blocked"
	}
	resp, err := env.CreateWebResourceResponse([]byte(body), code, reason, "Content-Type: "+ctype+"\r\nCache-Control: no-store")
	if err == nil && resp != nil {
		args.PutResponse(resp)
	}
}

// siteOf: the registrable site first (what to check before typing a password), e.g. "gov.in  (epds.up.gov.in)".
func siteOf(u string) string {
	h := agentcore.Host(u)
	if h == "" {
		return "(no address)"
	}
	return agentcore.Registrable(h) + "  (" + h + ")"
}

func writeResult(data, s string) { os.WriteFile(filepath.Join(data, "selftest.txt"), []byte(s), 0o600) }

func logLine(s string) { logf("%s", s) }

// autoStart: after a restart / sleep the laptop agent and the phone + tablet helper come back by themselves
// (owner, 28 Nov: both were found stopped). Current user only (HKCU Run); paths found next to this program.
func autoStart(exe string) {
	k, _, err := registry.CreateKey(registry.CURRENT_USER, `Software\Microsoft\Windows\CurrentVersion\Run`, registry.SET_VALUE|registry.QUERY_VALUE)
	if err != nil {
		logf("autostart: %v", err)
		return
	}
	defer k.Close()
	set := func(name, cmd string) {
		if old, _, _ := k.GetStringValue(name); old != cmd {
			if err := k.SetStringValue(name, cmd); err == nil {
				logf("autostart: %s on", name)
			}
		}
	}
	set("RoninAgentLab", `"`+exe+`"`)
	// the newest "N-claude-device-helper.bat" in ..\scrcpy
	dir := filepath.Join(filepath.Dir(filepath.Dir(exe)), "scrcpy")
	newest, at := "", time.Time{}
	m, _ := filepath.Glob(filepath.Join(dir, "*-claude-device-helper.bat"))
	for _, f := range m {
		if st, err := os.Stat(f); err == nil && st.ModTime().After(at) {
			newest, at = f, st.ModTime()
		}
	}
	if newest != "" {
		set("RoninDeviceHelper", `cmd /c start "" /min "`+newest+`"`)
	}
}
