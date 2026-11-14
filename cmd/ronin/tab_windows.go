//go:build windows

package main

import (
	"golang.org/x/sys/windows"
	"html"
	"net/url"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"time"
	"unsafe"

	"github.com/wailsapp/go-webview2/pkg/edge"

	"roninbrowser/internal/containers"
	"roninbrowser/internal/downloadguard"
	"roninbrowser/internal/httpsonly"
	"roninbrowser/internal/omnibox"
	"roninbrowser/internal/quicklinks"
	"roninbrowser/internal/safemoney"
	"roninbrowser/internal/videodl"
	"roninbrowser/internal/vpn"
)

// Tab is one web page area with its own WebView2.
type Tab struct {
	afterLoad    func()    // run once when the next page has loaded (data report -> PDF)
	exporting    bool      // "Data -> Excel + PDF" is reading the table
	lastDownload time.Time // when a download last started in this tab
	navUser      bool      // the latest navigation was started by the user
	navPage      bool      // ... and it ended as a normal page
	navAt        time.Time
	navID        uint64
	upgradedAt   map[string]time.Time // HTTPS-only: when a site was last switched to https
	upgradedURL  string               // the last address switched to https
	errShownAt   time.Time            // when Ronin's error page was last shown
	errURL       string               // the address that failed, while Ronin's error page is shown
	id           int
	w            *Window
	c            *edge.Chromium
	wv           webview
	visible      bool

	url, title      string
	loading         bool
	canBack, canFwd bool
	protect         string // "", "payment", "bank" (Payment Protect reason)
	isNewTab        bool
	userURL         string            // address the user just asked for
	chainStart      map[uint64]string // first address of each navigation chain
	chainUser       map[uint64]bool   // whether that chain was started by the user
	allowMu         sync.Mutex
	allowOnce       map[string]bool // downloads the user approved
	destroyed       bool
	adPaused        bool      // ad-block paused for this site
	lastShown       time.Time // for sleeping tabs
	asleep          bool
	top             atomic.Value // current top-level address (string)
	lastNavURL      string       // address of the latest navigation (for the error page)
	hasVideo        bool         // a <video> was found on the page
	kidsHeaders     bool         // KIDS: YouTube Restricted Mode header on every request
	zoomHost        string       // site whose page size is applied now
	zoomSeen        int          // last size shown / kept
	pwPending       *pwCapture   // a login waiting for "Save password?"
	pwSeq           int          // number of the latest save prompt
	pwBar           bool         // the "Fill" bar is shown for this tab
}

func browserArgs(c containers.Container) []string {
	var extra []string
	if c == containers.Personal || c == containers.Private || c == containers.Kids {
		// advertisers may not follow you from site to site (third-party cookies)
		extra = append(extra, "--test-third-party-cookie-phaseout")
	}
	if usesVPN(c) {
		if vpnAll || c == containers.Private || pacURL == "" {
			return append(vpn.BrowserArgs(), extra...) // every site through the VPN
		}
		return append(vpn.SmartBrowserArgs(pacURL), extra...) // only unknown and sensitive sites
	}
	if c == containers.Trading || c == containers.Calls {
		// Charts and video calls in background tabs and minimised windows keep
		// running live. Video calls also get no VPN and no WebRTC limit
		// (direct UDP = the best picture and voice).
		return []string{
			"--disable-background-timer-throttling",
			"--disable-renderer-backgrounding",
			"--disable-backgrounding-occluded-windows",
			"--disable-features=CalculateNativeWinOcclusion,IntensiveWakeUpThrottling",
		}
	}
	return extra
}

func (t *Tab) create() bool {
	c := edge.NewChromium()
	c.DataPath = profileDir(t.w.container)
	c.AdditionalBrowserArgs = browserArgs(t.w.container)
	c.SetErrorCallback(func(err error) { logf("tab: %v", err) })
	c.AcceleratorKeyCallback = t.w.onKey
	// Permissions: notifications and location are refused everywhere.
	// In Safe Money and Kids, camera and microphone are refused too;
	// elsewhere WebView2 asks the user.
	c.SetPermission(edge.CoreWebView2PermissionKindNotifications, edge.CoreWebView2PermissionStateDeny)
	c.SetPermission(edge.CoreWebView2PermissionKindGeolocation, edge.CoreWebView2PermissionStateDeny)
	if t.w.container == containers.Trading || t.w.container == containers.Calls {
		// TradingView price alerts, Dhan notices, incoming WhatsApp/Meet calls
		c.SetPermission(edge.CoreWebView2PermissionKindNotifications, edge.CoreWebView2PermissionStateAllow)
	}
	if t.w.container == containers.Banking || t.w.container == containers.Kids {
		c.SetPermission(edge.CoreWebView2PermissionKindMicrophone, edge.CoreWebView2PermissionStateDeny)
		c.SetPermission(edge.CoreWebView2PermissionKindCamera, edge.CoreWebView2PermissionStateDeny)
		c.SetPermission(edge.CoreWebView2PermissionKindClipboardRead, edge.CoreWebView2PermissionStateDeny)
	}
	c.PermissionCallback = func(args uintptr) bool { return t.onPermission(args) }
	if !c.Embed(t.w.hwnd) || c.Failed() {
		return false
	}
	defer t.setPageScheme() // Day / Night mode for the sites
	t.c = c
	ctrl := c.GetController()
	if ctrl == nil {
		return false
	}
	core, err := ctrl.GetCoreWebView2()
	if err != nil || core == nil {
		return false
	}
	t.wv = webview(uintptr(unsafe.Pointer(core)))
	t.chainStart = map[uint64]string{}
	t.chainUser = map[uint64]bool{}
	t.allowOnce = map[string]bool{}

	if s, err := c.GetSettings(); err == nil {
		strict := t.w.container == containers.Banking || t.w.container == containers.Kids
		_ = s.PutIsWebMessageEnabled(false) // web pages can never talk to Ronin
		_ = s.PutAreHostObjectsAllowed(false)
		_ = s.PutAreDevToolsEnabled(!strict)
		_ = s.PutIsStatusBarEnabled(true)
		_ = s.PutIsBuiltInErrorPageEnabled(false) // Ronin shows its own page (no other browser's name)
		requireSmartScreen(uintptr(unsafe.Pointer(s)))
	}
	_ = c.PutIsPasswordAutosaveEnabled(false) // passwords belong in Bitwarden
	_ = c.PutIsGeneralAutofillEnabled(t.w.container != containers.Banking && t.w.container != containers.Kids)
	t.setBase(false) // dark until a web site loads: no white flash when a new tab opens
	c.NavigationCompletedCallback = func(_ *edge.ICoreWebView2, args *edge.ICoreWebView2NavigationCompletedEventArgs) {
		t.loading = false
		if args != nil {
			a := uintptr(unsafe.Pointer(args))
			var status int32
			var doneID uint64
			call(a, 5, uintptr(unsafe.Pointer(&doneID))) // NavigationId
			if getBool(a, 3) {
				t.pwTake()
				t.pwOffer()
			}
			if getBool(a, 3) {
				t.showDataButton()
				if f := t.afterLoad; f != nil && strings.HasPrefix(t.wv.Source(), "file:") {
					t.afterLoad = nil
					runOnUI(f)
				}
			}
			if getBool(a, 3) && doneID == t.navID {
				// the user's latest navigation ended as a page: later downloads
				// are not "from the click" (Ronin's own pages do not count)
				t.navPage = true
			}
			if !getBool(a, 3) && !failed(call(a, 4, uintptr(unsafe.Pointer(&status)))) {
				// a link that turned into a file download also "fails": no error page then
				if msg := netErrorText(status); msg != "" && time.Since(t.lastDownload) > 5*time.Second {
					failedURL := t.lastNavURL
					// wait a moment: the download may be announced just after
					time.AfterFunc(400*time.Millisecond, func() {
						runOnUI(func() {
							if time.Since(t.lastDownload) > 5*time.Second && t.lastNavURL == failedURL {
								t.showErrorPage(failedURL, msg)
							}
						})
					})
				}
			}
		}
		t.refresh()
	}
	t.hookEvents()
	t.hookZoom()
	t.hookAdblock()
	t.hookContainerRules()
	// clicking inside a chart cell makes it the active tab
	_ = addEvent(uintptr(unsafe.Pointer(ctrl)), 15, newHandler(func(_, _ uintptr) uintptr {
		runOnUI(func() { t.w.tabFocused(t) })
		return sOK
	}))
	t.visible = true
	t.lastShown = time.Now()
	return true
}

func (t *Tab) hookEvents() {
	w := uintptr(t.wv)
	_ = addEvent(w, wvAddNavigationStarting, newHandler(func(_, args uintptr) uintptr {
		t.onNavigationStarting(args)
		return sOK
	}))
	_ = addEvent(w, wvAddSourceChanged, newHandler(func(_, _ uintptr) uintptr {
		src := t.wv.Source()
		if t.errURL != "" && !strings.HasPrefix(src, "http") {
			src = t.errURL // the error page keeps the address that failed in the bar
		} else {
			t.errURL = ""
		}
		t.url = src
		t.top.Store(t.url)
		t.hasVideo = false
		t.adPaused = store.AdblockPaused(hostOf(t.url))
		if t.isNewTab && !strings.HasPrefix(t.url, "about:") && !strings.HasPrefix(t.url, "data:") {
			t.isNewTab = false
		}
		t.pwClearBar()
		t.applySiteZoom()
		t.recordVisit()
		t.focusCheckSource()
		t.refresh()
		return sOK
	}))
	_ = addEvent(w, wvAddHistoryChanged, newHandler(func(_, _ uintptr) uintptr {
		t.refresh()
		return sOK
	}))
	_ = addEvent(w, wvAddDocumentTitleChanged, newHandler(func(_, _ uintptr) uintptr {
		t.title = t.wv.Title()
		t.recordTitle()
		t.refresh()
		return sOK
	}))
	_ = addEvent(w, wvAddNewWindowRequested, newHandler(func(_, args uintptr) uintptr {
		uri := getString(args, nwUri)
		user := getBool(args, nwUserInitiated)
		call(args, nwSetHandled, 1)
		// Pop-up blocker: windows a page opens by itself, and windows that
		// lead to an advertising server, are not opened.
		strict := t.w.container != containers.Trading && t.w.container != containers.Banking
		if uri == "" || uri == "about:blank" {
			if strict {
				return sOK
			}
		}
		if e := blocker.Load(); e != nil && !t.adPaused && e.BlockedFrom(uri, hostOf(t.topURL())) {
			return sOK
		}
		if strict && !user && !t.adPaused {
			runOnUI(func() { t.w.bar(t, "info", "Ronin blocked a pop-up window from this site.", nil) })
			return sOK
		}
		if uri != "" {
			runOnUI(func() {
				if store.Get().NewTabFront {
					t.w.newTab(uri, user)
				} else {
					t.w.newTabBehind(uri, user) // stay on the page; the new tab waits in the tab bar
				}
			})
		}
		return sOK
	}))
	if w4 := t.wv.webview4(); w4 != 0 {
		_ = addEvent(w4, wv4AddDownloadStarting, newHandler(func(_, args uintptr) uintptr {
			t.onDownloadStarting(args)
			return sOK
		}))
		release(w4)
	}
}

func (t *Tab) refresh() {
	if t.destroyed {
		return
	}
	t.canBack, t.canFwd = t.wv.CanGoBack(), t.wv.CanGoFwd()
	t.w.sendState()
}

func (t *Tab) displayTitle() string {
	if t.isNewTab {
		return "New Tab"
	}
	if strings.TrimSpace(t.title) != "" {
		return t.title
	}
	if h := hostOf(t.url); h != "" {
		return h
	}
	return "New Tab"
}

// userNavigate opens an address the user asked for (typed, clicked in Ronin).
func (t *Tab) userNavigate(u string) {
	if t.w.container == containers.Kids {
		if r := kidsCheck(u); r != "" {
			if safe, ok := strings.CutPrefix(r, "safe:"); ok {
				u = safe
			} else {
				t.w.overlay("blocked", map[string]any{"tab": t.id, "host": hostOf(u), "mode": "KIDS", "reason": r})
				return
			}
		}
	}
	d := containers.Route(t.w.container, containers.Nav{URL: u, UserInitiated: true}, store.Lists())
	if t.apply(d, u) {
		return
	}
	t.userURL = u
	t.isNewTab = false
	if err := t.wv.Navigate(u); err != nil {
		t.w.bar(t, "error", "This address could not be opened.", nil)
	}
}

// scriptNavigate opens an address a page asked for (window.open without a click).
func (t *Tab) scriptNavigate(u string) {
	d := containers.Route(t.w.container, containers.Nav{URL: u}, store.Lists())
	if t.apply(d, u) {
		return
	}
	t.isNewTab = false
	_ = t.wv.Navigate(u)
}

// apply carries out a router decision made before navigating.
// It returns true when the navigation must not happen in this tab.
func (t *Tab) apply(d containers.Decision, u string) bool {
	switch d.Action {
	case containers.Move:
		openContainer(d.Target, u)
		if t.isNewTab || t.url == "" || t.url == "about:blank" {
			if len(t.w.tabs) > 1 {
				t.w.closeTab(t)
			} else if !t.isNewTab {
				t.showNewTab()
			}
		}
		return true
	case containers.Warn:
		t.w.overlay("fake", map[string]any{"tab": t.id, "url": u, "host": d.Verdict.Host, "brand": strings.ToUpper(d.Verdict.Brand)})
		return true
	case containers.Block:
		t.w.overlay("blocked", map[string]any{"tab": t.id, "host": d.Verdict.Host, "mode": string(t.w.container)})
		return true
	}
	t.setProtect(d)
	return false
}

func (t *Tab) setProtect(d containers.Decision) {
	old := t.protect
	t.protect = ""
	if d.PaymentProtect {
		t.protect = "payment"
		if d.Verdict.Kind == safemoney.Bank && t.w.container != containers.Banking {
			t.protect = "bank"
		}
	}
	if t.protect != old && t.protect != "" {
		if t.protect == "bank" {
			t.w.bar(t, "protect", "This bank page opened as part of a payment, so it stays here with Payment Protect. For net banking, use Ronin Safe Money.",
				[]map[string]string{{"label": "Open in Safe Money", "cmd": "safemoney"}})
		} else {
			t.w.bar(t, "protect", "Payment page: Payment Protect is on.", nil)
		}
	}
}

func (t *Tab) onNavigationStarting(args uintptr) {
	uri := getString(args, nsUri)
	user := getBool(args, nsUserInitiated)
	redirected := getBool(args, nsRedirected)
	var navID uint64
	call(args, nsNavigationId, uintptr(unsafe.Pointer(&navID)))

	if !redirected {
		if len(t.chainStart) > 64 {
			t.chainStart = map[uint64]string{}
			t.chainUser = map[uint64]bool{}
		}
		if t.userURL != "" && uri == t.userURL {
			user = true
		}
		t.userURL = ""
		t.chainStart[navID] = uri
		t.chainUser[navID] = user
	} else if t.chainUser[navID] {
		user = true
	}
	// the page's "Data -> Excel + PDF" button
	if strings.HasPrefix(uri, "https://ronin.data/") {
		call(args, nsSetCancel, 1)
		if u, err := url.Parse(uri); err == nil && u.Query().Get("k") == newTabKey {
			runOnUI(func() { t.exportData() })
		}
		return
	}
	// a change to the quick links, made on Ronin's own new tab page
	if strings.HasPrefix(uri, "https://ronin.newtab/") {
		call(args, nsSetCancel, 1)
		if t.isNewTab {
			if quickLinkChange(uri) {
				runOnUI(func() { t.showNewTab() })
			}
		}
		return
	}
	// for the "did the user ask for this download?" check
	if strings.HasPrefix(uri, "http") { // Ronin's own pages (new tab, error page) do not count
		t.navUser, t.navPage, t.navAt, t.navID = user, false, time.Now(), navID
	}
	start := t.chainStart[navID]
	// A redirect still counts as the user's own visit when the chain began at
	// a link forwarder (google.com/url) or at a bank / trading site itself
	// (hdfcbank.com -> hdfc.bank.in). Payment flows start at a shop or a
	// payment gateway, so they keep Payment Protect instead.
	startKind := safemoney.Classify(start, store.Lists()).Kind
	nav := containers.Nav{URL: uri, UserInitiated: user, Redirected: redirected,
		ViaRedirector: redirected && (safemoney.IsLinkRedirector(start) ||
			startKind == safemoney.Bank || startKind == safemoney.Trading)}
	// An address typed into the New Tab page's search box opens the site itself.
	if t.isNewTab && !redirected && strings.HasPrefix(uri, "https://www.google.com/search?q=") {
		if pu, err := url.Parse(uri); err == nil {
			if dest := omnibox.Resolve(pu.Query().Get("q")); dest != "" && !strings.HasPrefix(dest, "https://www.google.com/search") {
				call(args, nsSetCancel, 1)
				runOnUI(func() { t.userNavigate(dest) })
				return
			}
		}
	}
	t.pwTake() // a login form was just sent: offer to save the password
	if t.httpsOnly(args, uri, redirected, user) {
		return
	}
	if t.w.container == containers.Kids {
		if r := kidsCheck(uri); r != "" {
			call(args, nsSetCancel, 1)
			if safe, ok := strings.CutPrefix(r, "safe:"); ok {
				runOnUI(func() { _ = t.wv.Navigate(safe) })
			} else {
				runOnUI(func() {
					t.w.overlay("blocked", map[string]any{"tab": t.id, "host": hostOf(uri), "mode": "KIDS", "reason": r})
				})
			}
			return
		}
	}
	if host, bad := dangerous(uri); bad {
		call(args, nsSetCancel, 1)
		runOnUI(func() {
			t.w.overlay("blocked", map[string]any{"tab": t.id, "host": host, "mode": "DANGER"})
		})
		return
	}
	if stop, end := focusStops(t.w.container, uri); stop {
		call(args, nsSetCancel, 1)
		runOnUI(func() { t.showFocusPage(uri, end) })
		return
	}
	d := containers.Route(t.w.container, nav, store.Lists())
	if d.Action != containers.Stay {
		call(args, nsSetCancel, 1)
		runOnUI(func() { t.apply(d, uri) })
		return
	}
	t.setBase(!strings.HasPrefix(uri, "data:") && !strings.HasPrefix(uri, "about:")) // Ronin's own pages stay dark
	t.loading = true
	t.lastNavURL = uri
	t.top.Store(uri) // so the page's own first request is not treated as an advert
	t.setProtect(d)
	t.w.sendState()
}

// ---- downloads -------------------------------------------------------------

func (t *Tab) allowDownloadOnce(u string) {
	t.allowMu.Lock()
	t.allowOnce[u] = true
	t.allowMu.Unlock()
	_ = t.wv.Navigate(u)
}

func (t *Tab) downloadMode() downloadguard.Mode {
	switch t.w.container {
	case containers.Banking:
		return downloadguard.BankingMode
	case containers.Kids:
		return downloadguard.KidsMode
	}
	return downloadguard.Normal
}

func (t *Tab) onDownloadStarting(args uintptr) {
	var op uintptr
	call(args, dsDownloadOperation, uintptr(unsafe.Pointer(&op)))
	if op == 0 {
		return
	}
	t.lastDownload = time.Now()
	if t.errURL != "" && time.Since(t.errShownAt) < 10*time.Second {
		// the "could not open" page came first, but the link was a file:
		// take the page away again
		runOnUI(func() {
			if t.errURL == "" || t.destroyed {
				return
			}
			t.errURL = ""
			if t.canBack {
				t.wv.GoBack()
			} else {
				t.showNewTab()
			}
		})
	}
	uri := getString(op, doUri)
	path := getString(args, dsResultFilePath)
	name := filepath.Base(path)
	res := downloadguard.Check(name, t.downloadMode())

	t.allowMu.Lock()
	approved := t.allowOnce[uri]
	delete(t.allowOnce, uri)
	t.allowMu.Unlock()

	if t.protect != "" && res.Level != downloadguard.Safe {
		res.Level = downloadguard.Blocked
		res.Reason = "Only safe documents can be saved from a payment page."
	}
	switch {
	case res.Level == downloadguard.Blocked:
		call(args, dsSetCancel, 1)
		release(op)
		runOnUI(func() { t.w.bar(t, "block", "Download stopped: "+res.SafeName+". "+res.Reason, nil) })
		return
	case res.Level == downloadguard.Dangerous && !approved:
		call(args, dsSetCancel, 1)
		release(op)
		runOnUI(func() {
			t.w.bar(t, "danger", "Download stopped: "+res.SafeName+". "+res.Reason,
				[]map[string]string{{"label": "Download anyway", "cmd": "dlallow", "url": uri}})
		})
		return
	}
	// A download the user did not ask for (a site that starts one by itself,
	// or a program file from a site Ronin does not know) waits for a yes.
	var total int64
	call(op, doTotalBytes, uintptr(unsafe.Pointer(&total)))
	if !approved && t.w.container != containers.Banking {
		if why := t.unaskedDownload(res.SafeName, uri); why != "" {
			call(args, dsSetCancel, 1)
			release(op)
			size := ""
			if total > 0 {
				size = " (" + byteSize(total) + ")"
			}
			runOnUI(func() {
				t.w.bar(t, "warn", why+": "+shortName(res.SafeName, 48)+size,
					[]map[string]string{{"label": "Download", "cmd": "dlallow", "url": uri}, {"label": "Block", "cmd": "barclose"}})
			})
			return
		}
	}
	if res.SafeName != name && path != "" {
		path = filepath.Join(filepath.Dir(path), res.SafeName)
		_ = putString(args, dsSetResultFilePath, path)
	}
	// Big files: Ronin's own fast downloader (several connections, pause and
	// resume, goes on when the window is closed).
	if t.useFastDownload(uri, total) && path != "" {
		call(args, dsSetCancel, 1)
		release(op)
		if res.Level == downloadguard.Caution {
			runOnUI(func() { t.w.bar(t, "warn", res.SafeName+": "+res.Reason, nil) })
		}
		referrer := t.url
		runOnUI(func() { t.startFastDownload(uri, path, referrer) })
		return
	}
	if res.Level == downloadguard.Caution {
		runOnUI(func() { t.w.bar(t, "warn", res.SafeName+": "+res.Reason, nil) })
	}
	// Ronin shows the download in its own Downloads panel (not the engine's box).
	call(args, dsPutHandled, 1)
	job := startFileJob(t.w, res.SafeName, op)
	referrer := t.url
	var lastNote time.Time
	_ = addEvent(op, doAddBytesReceived, newHandler(func(sender, _ uintptr) uintptr {
		var got, total int64
		call(sender, doBytesReceived, uintptr(unsafe.Pointer(&got)))
		call(sender, doTotalBytes, uintptr(unsafe.Pointer(&total)))
		setJob(job, func(j *dlJob) { j.bytes, j.total = got, total })
		if time.Since(lastNote) > 700*time.Millisecond {
			lastNote = time.Now()
			notifyJob(job)
		}
		return sOK
	}))
	_ = addEvent(op, doAddStateChanged, newHandler(func(sender, _ uintptr) uintptr {
		var state int32
		call(sender, doState, uintptr(unsafe.Pointer(&state)))
		p := getString(sender, doResultFilePath)
		switch state {
		case downloadCompleted:
			if p != "" {
				if err := downloadguard.MarkOfTheWeb(p, uri, referrer); err != nil {
					logf("mark of the web: %v", err)
				}
			}
			finishFileJob(job, "done", p)
		case downloadInterrupted:
			// a broken connection: try again a few times before giving up
			dlMu.Lock()
			paused := job.State == "paused"
			job.retries++
			n := job.retries
			dlMu.Unlock()
			if paused {
				break
			}
			if getBool(sender, doCanResume) && n <= 6 {
				setJob(job, func(j *dlJob) { j.Note = "Connection broke - trying again" })
				time.AfterFunc(4*time.Second, func() {
					runOnUI(func() {
						dlMu.Lock()
						o, st := job.op, job.State
						dlMu.Unlock()
						if o != 0 && st == "downloading" {
							call(o, doResume)
						}
					})
				})
				break
			}
			finishFileJob(job, "failed", p)
		}
		return sOK
	}))
	release(op)
}

// ---- new tab page, cleanup -------------------------------------------------

// setBase sets what shows before a page paints (and behind pages without a
// background): Ronin's own colour for the new tab page, white for web sites.
func (t *Tab) setBase(web bool) {
	if t.c == nil {
		return
	}
	if web {
		t.c.SetBackgroundColour(0xff, 0xff, 0xff, 255)
		return
	}
	r, g, b := newTabBackground(t.w.container)
	t.c.SetBackgroundColour(r, g, b, 255)
}

func (t *Tab) showNewTab() {
	t.setBase(false)
	t.isNewTab = true
	t.title = ""
	t.protect = ""
	_ = t.wv.NavigateToString(newTabHTML(t.w.container))
	t.w.sendState()
	t.w.send("window.ronin&&window.ronin.focusAddress()")
}

func (t *Tab) deleteCookies() {
	if t.c == nil {
		return
	}
	if cm, err := t.c.GetCookieManager(); err == nil && cm != nil {
		_ = cm.DeleteAllCookies()
	}
}

func (t *Tab) destroy() {
	if t.destroyed {
		return
	}
	t.destroyed = true
	if t.c != nil {
		t.c.ShuttingDown()
		if ctrl := t.c.GetController(); ctrl != nil {
			call(uintptr(unsafe.Pointer(ctrl)), 24) // Close: frees the page and its memory
		}
	}
	t.c = nil
}

// ---- sleeping tabs ---------------------------------------------------------------

var iidICoreWebView2_3 = windows.GUID{Data1: 0xa0d6df20, Data2: 0x3b92, Data3: 0x416d, Data4: [8]byte{0xaa, 0x0c, 0x43, 0x7a, 0x9c, 0x72, 0x78, 0x57}}

const wv3TrySuspend = 68

var iidICoreWebView2_8 = windows.GUID{Data1: 0xe9632730, Data2: 0x6e1e, Data3: 0x43ab, Data4: [8]byte{0xb7, 0xb8, 0x7b, 0x2c, 0x9e, 0x62, 0xe0, 0x94}}

const wv8GetIsDocumentPlayingAudio = 87

// ICoreWebView2Settings8: Microsoft SmartScreen (known phishing and malware
// sites and downloads; Microsoft keeps its lists up to date by itself).
var iidICoreWebView2Settings8 = windows.GUID{Data1: 0x9e6b0e8f, Data2: 0x86ad, Data3: 0x4e81, Data4: [8]byte{0x81, 0x47, 0xa9, 0xb5, 0xed, 0xb6, 0x86, 0x50}}

const settings8PutIsReputationCheckingRequired = 36

// requireSmartScreen keeps SmartScreen on for every Ronin page.
func requireSmartScreen(settings uintptr) {
	s8 := queryInterface(settings, &iidICoreWebView2Settings8)
	if s8 == 0 {
		return
	}
	defer release(s8)
	call(s8, settings8PutIsReputationCheckingRequired, 1)
}

// playingAudio reports whether the page is playing sound right now (a song,
// a video, a call). Unknown (old engine) counts as playing, to be safe.
func (t *Tab) playingAudio() bool {
	w8 := queryInterface(uintptr(t.wv), &iidICoreWebView2_8)
	if w8 == 0 {
		return true
	}
	defer release(w8)
	var on int32
	if failed(call(w8, wv8GetIsDocumentPlayingAudio, uintptr(unsafe.Pointer(&on)))) {
		return true
	}
	return on != 0
}

// sleep pauses a hidden tab that has not been looked at for a while, to save
// memory. It wakes up by itself when it is shown again. Trading tabs never sleep.
func (t *Tab) sleep() {
	if t.asleep || t.visible || t.destroyed || t.w.container == containers.Trading || t.w.container == containers.Banking ||
		t.w.container == containers.Calls || t.playingAudio() {
		return // songs, videos and calls in background tabs keep playing
	}
	w3 := queryInterface(uintptr(t.wv), &iidICoreWebView2_3)
	if w3 == 0 {
		return
	}
	defer release(w3)
	if !failed(call(w3, wv3TrySuspend, uintptr(unsafe.Pointer(newHandler(func(_, _ uintptr) uintptr { return sOK }))))) {
		t.asleep = true
	}
}

// canDownload reports whether the video Download button applies to this tab.
func (t *Tab) canDownload(needVideo bool) bool {
	c := t.w.container
	if c == containers.Banking || c == containers.Kids || t.isNewTab || !strings.HasPrefix(t.url, "http") {
		return false
	}
	switch safemoney.Classify(t.url, store.Lists()).Kind {
	case safemoney.Bank, safemoney.Payment, safemoney.Auth, safemoney.Local:
		return false
	}
	return !needVideo || t.hasVideo || videodl.IsVideoPage(t.url)
}

// checkForVideo looks for a video player on the page (for the Download button).
func (t *Tab) checkForVideo() {
	if t.destroyed || t.isNewTab || !strings.HasPrefix(t.url, "http") {
		return
	}
	t.runScript(`(function(){var v=document.querySelectorAll("video");for(var i=0;i<v.length;i++){if(v[i].offsetWidth>200&&v[i].offsetHeight>120)return true}`+
		`var f=document.querySelectorAll("iframe");for(var j=0;j<f.length;j++){var s=(f[j].src||"").toLowerCase();if(f[j].offsetWidth>300&&f[j].offsetHeight>170&&/embed|player|video/.test(s)&&!/doubleclick|googlesyndication|ads/.test(s))return true}return false})()`, func(r string) {
		has := r == "true"
		if has != t.hasVideo && !t.destroyed {
			t.hasVideo = has
			t.w.sendState()
		}
	})
}

// topURL is the page the tab is on (or is going to), safe to read from the
// request filter.
func (t *Tab) topURL() string {
	if v, ok := t.top.Load().(string); ok {
		return v
	}
	return ""
}

// netErrorText gives a plain message for network failures ("" = not a
// network failure, show nothing).
func netErrorText(status int32) string {
	switch status {
	case 1, 2, 3, 4, 5:
		return "This site's security certificate is not valid, so Ronin did not open it. Someone may be trying to copy the real site."
	case 13:
		return "This address was not found. Check the spelling, or your internet connection."
	case 7:
		return "The site took too long to answer."
	case 6, 9, 10, 11, 12, 15:
		return "The site could not be reached. Check your internet connection."
	}
	return ""
}

// showErrorPage shows Ronin's own "cannot open" page.
func (t *Tab) showErrorPage(u, msg string) {
	if t.destroyed || !strings.HasPrefix(u, "http") {
		return
	}
	extra := ""
	if u == t.upgradedURL && strings.HasPrefix(u, "https://") {
		msg = "This site has no secure (https) version, or it is not working right now."
		if t.w.container != containers.Banking && t.w.container != containers.Kids {
			insecure := "http://" + strings.TrimPrefix(u, "https://")
			if i := strings.IndexByte(insecure, '#'); i >= 0 {
				insecure = insecure[:i]
			}
			extra = `<p>Ronin opens every site in its secure form. You can still open the insecure version, but do not type passwords or card numbers there.</p>` +
				`<p><a style="background:#7c2d12" href="` + html.EscapeString(insecure+httpsonly.AllowMark) + `">Open anyway (not secure)</a></p>`
		}
	} else if vpnForURL(t.w.container, u) {
		extra = "<p>This site uses the VPN. If no site opens, the VPN server may be busy: open Menu &rarr; Settings &rarr; VPN and add another server or turn the VPN off.</p>"
	}
	page := `<!doctype html><meta charset="utf-8"><title>Cannot open page</title>
<style>body{margin:0;background:#14171f;color:#e8eaf0;font:16px/1.5 "Segoe UI",system-ui,sans-serif}
main{max-width:620px;margin:14vh auto;padding:0 24px}h1{font-size:26px;margin:0 0 10px}
p{color:#b6bdca}.u{font-family:Consolas,monospace;font-size:13px;color:#9aa3b2;word-break:break-all}
a{display:inline-block;margin-top:14px;padding:10px 18px;border-radius:8px;background:#3b82f6;color:#fff;text-decoration:none;font-weight:600}
@media (prefers-color-scheme: light){body{background:#eef0f4;color:#151922}p{color:#3d4554}.u{color:#5a6375}}</style>
<main><h1>Ronin could not open this page</h1><p>` + html.EscapeString(msg) + `</p>` + extra +
		`<p class="u">` + html.EscapeString(u) + `</p><a href="` + html.EscapeString(u) + `">Try again</a></main>`
	t.errURL = u
	t.errShownAt = time.Now()
	_ = t.wv.NavigateToString(page)
}

// ---- HTTPS-only ----------------------------------------------------------------

// httpAllowed: sites the user chose to open insecurely (this run only).
var httpAllowed sync.Map

func isHTTPAllowed(host string) bool { _, ok := httpAllowed.Load(host); return ok }

// httpsOnly opens insecure addresses in their secure form. It returns true
// when it cancelled this navigation.
func (t *Tab) httpsOnly(args uintptr, uri string, redirected, user bool) bool {
	if host, ok := httpsonly.AllowRequest(uri); ok {
		if t.w.container == containers.Banking || t.w.container == containers.Kids {
			call(args, nsSetCancel, 1)
			return true
		}
		httpAllowed.Store(host, true)
		runOnUI(func() {
			t.w.bar(t, "warn", "Not secure: "+host+" has no https. Do not type passwords or card numbers here.", nil)
		})
		return false
	}
	secure, ok := httpsonly.Upgrade(uri, isHTTPAllowed)
	if !ok {
		return false
	}
	host := hostOf(uri)
	if t.upgradedAt == nil {
		t.upgradedAt = map[string]time.Time{}
	}
	if redirected && time.Since(t.upgradedAt[host]) < 15*time.Second {
		// the secure site sends us straight back to http: it has no secure
		// version. Let it load, but say so.
		httpAllowed.Store(host, true)
		runOnUI(func() {
			t.w.bar(t, "warn", "Not secure: "+host+" has no https. Do not type passwords or card numbers here.", nil)
		})
		return false
	}
	t.upgradedAt[host] = time.Now()
	t.upgradedURL = secure
	call(args, nsSetCancel, 1)
	runOnUI(func() {
		if user {
			t.userURL = secure // still the user's own visit
		}
		_ = t.wv.Navigate(secure)
	})
	return true
}

// quickLinkChange applies https://ronin.newtab/add?... or /del?... from the
// new tab page. The key proves the page is Ronin's own.
func quickLinkChange(raw string) bool {
	u, err := url.Parse(raw)
	if err != nil || u.Host != "ronin.newtab" {
		return false
	}
	q := u.Query()
	if q.Get("k") != newTabKey {
		return false
	}
	switch u.Path {
	case "/add":
		return store.AddQuickLink(quicklinks.Link{Section: q.Get("s"), Name: q.Get("n"), URL: q.Get("u")})
	case "/del":
		store.RemoveQuickLink(q.Get("u"))
		return true
	}
	return false
}
