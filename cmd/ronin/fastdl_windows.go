//go:build windows

package main

import (
	"bytes"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
	"unsafe"

	"roninbrowser/internal/containers"
	"roninbrowser/internal/downloadguard"
	"roninbrowser/internal/fastdl"
	"roninbrowser/internal/vpn"

	"golang.org/x/sys/windows"
)

// ---- did the user ask for this download? -------------------------------------

// program files: always confirmed when they come from a site Ronin does not know
var programExt = map[string]bool{".exe": true, ".msi": true, ".msix": true, ".apk": true, ".bat": true, ".cmd": true,
	".scr": true, ".ps1": true, ".vbs": true, ".js": true, ".jar": true, ".com": true, ".pif": true, ".lnk": true,
	".hta": true, ".reg": true, ".dll": true, ".iso": true, ".img": true}

var (
	trustOnce sync.Once
	trustSet  *vpn.Smart
)

// trustedSite: well-known sites (the same list the smart VPN uses).
func trustedSite(host string) bool {
	if host == "" {
		return false
	}
	if s := smartVPN.Load(); s != nil {
		return !s.UsesVPN(host)
	}
	trustOnce.Do(func() {
		var top []string
		if b, err := os.ReadFile(topSitesPath()); err == nil {
			top = vpn.ParseTopList(bytes.NewReader(b), 10000)
		}
		trustSet = vpn.NewSmart(top, nil)
	})
	return !trustSet.UsesVPN(host)
}

// unaskedDownload returns why a download must wait for a yes ("" = go ahead).
func (t *Tab) unaskedDownload(name, uri string) string {
	_ = uri // a trusted file host is not enough: a bad site could point at a file there
	if trustedSite(hostOf(t.topURL())) {
		return ""
	}
	if programExt[strings.ToLower(filepath.Ext(name))] {
		return "This site wants to download a program file"
	}
	// The user's own click on a download link: the latest navigation was
	// theirs, recent, and did not end as a normal page.
	if t.navUser && !t.navPage && time.Since(t.navAt) < 2*time.Minute {
		return ""
	}
	return "This site started a download by itself"
}

func shortName(s string, n int) string {
	r := []rune(s)
	if len(r) <= n {
		return s
	}
	ext := filepath.Ext(s)
	if len([]rune(ext)) > 8 {
		ext = ""
	}
	keep := n - len([]rune(ext)) - 1
	if keep < 4 {
		keep = 4
	}
	return string(r[:keep]) + "…" + ext
}

// ---- Ronin's own fast downloader ----------------------------------------------

var engineOnly sync.Map // addresses the fast downloader could not fetch: the engine does these

func (t *Tab) useFastDownload(uri string, total int64) bool {
	c := t.w.container
	if c == containers.Banking || c == containers.Kids || !strings.HasPrefix(uri, "http") {
		return false
	}
	if _, ok := engineOnly.Load(uri); ok {
		return false
	}
	return total >= 8<<20
}

var browserUA string

const fallbackUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

// cookieHeader reads the cookies the engine would send to uri.
func (t *Tab) cookieHeader(uri string, done func(string)) {
	if t.c == nil {
		done("")
		return
	}
	cm, err := t.c.GetCookieManager()
	p, perr := windows.UTF16PtrFromString(uri)
	if err != nil || cm == nil || perr != nil {
		done("")
		return
	}
	answered := false
	h := newHandler(func(hr, list uintptr) uintptr {
		var parts []string
		if !failed(hr) && list != 0 {
			var n uint32
			call(list, 3, uintptr(unsafe.Pointer(&n)))
			for i := uint32(0); i < n && i < 300; i++ {
				var ck uintptr
				if failed(call(list, 4, uintptr(i), uintptr(unsafe.Pointer(&ck)))) || ck == 0 {
					continue
				}
				name, val := getString(ck, 3), getString(ck, 4)
				release(ck)
				if name != "" && !strings.ContainsAny(name+val, "\r\n") {
					parts = append(parts, name+"="+val)
				}
			}
		}
		runOnUI(func() {
			if !answered {
				answered = true
				done(strings.Join(parts, "; "))
			}
		})
		return sOK
	})
	if failed(call(uintptr(unsafe.Pointer(cm)), 5, uintptr(unsafe.Pointer(p)), uintptr(unsafe.Pointer(h)))) {
		done("")
		return
	}
	time.AfterFunc(3*time.Second, func() { // never wait for ever
		runOnUI(func() {
			if !answered {
				answered = true
				done("")
			}
		})
	})
}

// uniquePath avoids overwriting an existing file ("name (2).ext").
func uniquePath(p string) string {
	exists := func(x string) bool {
		if _, err := os.Stat(x); err == nil {
			return true
		}
		_, err := os.Stat(x + ".ronin-part")
		return err == nil
	}
	if !exists(p) {
		return p
	}
	ext := filepath.Ext(p)
	base := strings.TrimSuffix(p, ext)
	for i := 2; i < 1000; i++ {
		if c := fmt.Sprintf("%s (%d)%s", base, i, ext); !exists(c) {
			return c
		}
	}
	return p
}

// startFastDownload runs on the UI thread.
func (t *Tab) startFastDownload(uri, path, referrer string) {
	w := t.w
	begin := func(ua string) {
		t.cookieHeader(uri, func(cookie string) {
			path := path
			if fastdl.HasPartial(path) {
				// an unfinished download of this file is waiting (the link had
				// expired, or Ronin was closed): go on from there
				dlMu.Lock()
				kept := dlJobs[:0]
				for _, x := range dlJobs {
					if !(x.File == path && x.fd == nil && x.State != "done") {
						kept = append(kept, x)
					}
				}
				dlJobs = kept
				dlMu.Unlock()
			} else {
				path = uniquePath(path)
			}
			proxy := ""
			if vpnForURL(w.container, uri) {
				proxy = fmt.Sprintf("socks5://127.0.0.1:%d", vpn.Port)
			}
			fd := fastdl.New(fastdl.Options{URL: uri, Path: path, UserAgent: ua, Referer: referrer, Cookie: cookie, Proxy: proxy, Conns: 8})
			dlMu.Lock()
			dlNextID++
			j := &dlJob{ID: dlNextID, Title: filepath.Base(path), State: "downloading", Percent: -1, Kind: "file", w: w,
				File: path, fd: fd, CanPause: true, VPN: proxy != "", srcURL: uri, referrer: referrer, tab: t, lastMove: time.Now()}
			j.cancel = func() {
				go func() {
					fd.Cancel()
					setJob(j, func(j *dlJob) { j.State, j.fd = "cancelled", nil })
					refreshDownloadViews()
				}()
			}
			dlJobs = append(dlJobs, j)
			if len(dlJobs) > 30 {
				dlJobs = dlJobs[len(dlJobs)-30:]
			}
			dlMu.Unlock()
			fd.Start()
			startDownloadTicker()
			if !w.closing {
				w.overlay("downloads", downloadsData(w))
			}
		})
	}
	if browserUA != "" {
		begin(browserUA)
		return
	}
	t.runScript("navigator.userAgent", func(js string) {
		ua := strings.Trim(js, `"`)
		if !strings.HasPrefix(ua, "Mozilla/") || strings.ContainsAny(ua, "\r\n\\") {
			ua = fallbackUA
		} else {
			browserUA = ua
		}
		begin(ua)
	})
}

// ---- pause, resume, without VPN -------------------------------------------------

func pauseJob(id string) {
	j := jobByID(id)
	if j == nil {
		return
	}
	dlMu.Lock()
	fd, op, st := j.fd, j.op, j.State
	if st == "downloading" {
		j.State = "paused"
	}
	dlMu.Unlock()
	if st != "downloading" {
		return
	}
	if fd != nil {
		go func() { fd.Pause(); refreshDownloadViews() }()
	} else if op != 0 {
		call(op, doPause)
	}
	refreshDownloadViews()
}

func resumeJob(id string) {
	j := jobByID(id)
	if j == nil {
		return
	}
	dlMu.Lock()
	fd, op, st := j.fd, j.op, j.State
	if st == "paused" {
		j.State, j.lastMove, j.retries = "downloading", time.Now(), 0
	}
	dlMu.Unlock()
	if st != "paused" {
		return
	}
	if fd != nil {
		fd.Resume()
	} else if op != 0 {
		call(op, doResume)
	}
	refreshDownloadViews()
}

// directJob switches a download from the VPN to the direct connection.
func directJob(id string) {
	j := jobByID(id)
	if j == nil {
		return
	}
	dlMu.Lock()
	fd := j.fd
	j.VPN = false
	dlMu.Unlock()
	if fd != nil {
		go func() { fd.SetProxy(""); refreshDownloadViews() }()
	}
}

// ---- once a second: speed, time left, "nothing is arriving" -----------------------

var tickerOnce sync.Once

func startDownloadTicker() {
	tickerOnce.Do(func() {
		go func() {
			for range time.Tick(time.Second) {
				downloadTick()
			}
		}()
	})
}

func activeJob(j *dlJob) bool {
	return j.State == "downloading" || j.State == "paused" || j.State == "preparing" || j.State == "scanning"
}

func activeJobs() int {
	dlMu.Lock()
	defer dlMu.Unlock()
	n := 0
	for _, j := range dlJobs {
		if activeJob(j) {
			n++
		}
	}
	return n
}

func downloadTick() {
	type ended struct {
		j  *dlJob
		st fastdl.Status
	}
	var finished []ended
	any := false
	now := time.Now()
	dlMu.Lock()
	for _, j := range dlJobs {
		if !activeJob(j) {
			continue
		}
		any = true
		if j.fd != nil {
			st := j.fd.Status()
			j.bytes, j.total = st.Bytes, st.Total
			j.Stalled = st.Stalled
			if st.Stalled && st.Problem != "" {
				j.Note = ""
			}
			if st.State == fastdl.Downloading {
				j.CanPause = st.Ranged
				if n := j.fd.Resumed(); n > 0 && !j.resumeNoted {
					j.resumeNoted = true
					j.Note = "continued from " + byteSize(n)
				} else if !st.Ranged && j.Note == "" {
					j.Note = "this server cannot pause or continue"
				}
			}
			switch st.State {
			case fastdl.Done, fastdl.Failed, fastdl.Unsupported, fastdl.NeedLink:
				finished = append(finished, ended{j, st})
				j.fd = nil
				if st.State == fastdl.Done {
					j.State, j.Percent = "scanning", 100
				}
			}
		}
		if j.Kind != "file" || j.State == "scanning" {
			continue
		}
		// speed (smoothed), time left
		if !j.prevAt.IsZero() {
			dt := now.Sub(j.prevAt).Seconds()
			if dt > 0.2 {
				inst := float64(j.bytes-j.prevBytes) / dt
				if inst < 0 {
					inst = 0
				}
				if j.rate == 0 {
					j.rate = inst
				} else {
					j.rate = j.rate*0.7 + inst*0.3
				}
				if j.bytes != j.prevBytes {
					j.lastMove = now
					if j.fd == nil {
						j.Note = ""
					}
				}
				j.prevBytes, j.prevAt = j.bytes, now
			}
		} else {
			j.prevBytes, j.prevAt = j.bytes, now
		}
		if j.fd == nil && j.State == "downloading" {
			j.Stalled = now.Sub(j.lastMove) > 10*time.Second
		}
		j.Size = byteSize(j.bytes)
		j.Percent = -1
		if j.total > 0 {
			j.Percent = float64(j.bytes) * 100 / float64(j.total)
			j.Size += " of " + byteSize(j.total)
		}
		j.Speed, j.ETA = "", ""
		if j.State == "downloading" && !j.Stalled && j.rate > 1024 {
			j.Speed = byteSize(int64(j.rate)) + "/s"
			if j.total > 0 {
				j.ETA = shortDuration(time.Duration(float64(j.total-j.bytes)/j.rate) * time.Second)
			}
		}
	}
	dlMu.Unlock()
	for _, e := range finished {
		finishFastJob(e.j, e.st)
	}
	if any || len(finished) > 0 {
		refreshDownloadViews()
	}
	runOnUI(closeIdleHiddenWindows)
}

func shortDuration(d time.Duration) string {
	s := int(d.Seconds())
	switch {
	case s >= 3600:
		return fmt.Sprintf("%d h %d min", s/3600, s%3600/60)
	case s >= 60:
		return fmt.Sprintf("%d min", (s+30)/60)
	}
	return fmt.Sprintf("%d sec", s)
}

func finishFastJob(j *dlJob, st fastdl.Status) {
	switch st.State {
	case fastdl.Done:
		dlMu.Lock()
		f, src, ref := j.File, j.srcURL, j.referrer
		dlMu.Unlock()
		if err := downloadguard.MarkOfTheWeb(f, src, ref); err != nil {
			logf("mark of the web: %v", err)
		}
		go scanJob(j)
	case fastdl.NeedLink:
		setJob(j, func(j *dlJob) {
			j.State = "needlink"
			j.Error = "The download link expired. Start the same download again on the site: Ronin continues from " +
				byteSize(st.Bytes) + ", not from zero."
		})
	case fastdl.Failed:
		_ = os.Remove(j.File + ".ronin-part")
		_ = os.Remove(j.File + ".ronin-state")
		setJob(j, func(j *dlJob) { j.State, j.Error = "failed", "Download failed: "+st.Err+"." })
	case fastdl.Unsupported:
		// this server wants the browser itself: hand the download back to the engine
		_ = os.Remove(j.File + ".ronin-part")
		dlMu.Lock()
		for i, x := range dlJobs {
			if x == j {
				dlJobs = append(dlJobs[:i], dlJobs[i+1:]...)
				break
			}
		}
		t, src := j.tab, j.srcURL
		dlMu.Unlock()
		engineOnly.Store(src, true)
		runOnUI(func() {
			if t != nil && !t.destroyed && !t.w.closing {
				t.allowDownloadOnce(src)
			}
		})
	}
}

// ---- views: side panels and the small floating window ------------------------------

func downloadsData(w *Window) map[string]any {
	return map[string]any{"jobs": jobsData(), "folder": videoDir(), "float": w != nil && w.float}
}

// refreshDownloadViews updates every place that shows the downloads.
func refreshDownloadViews() {
	runOnUI(func() {
		for _, w := range windowsSnapshot() {
			if w.closing {
				continue
			}
			if w.float || w.panelKind == "downloads" {
				w.overlay("downloads", downloadsData(w))
			}
		}
	})
}

var dlFloat *Window

// showDownloadFloat opens the small always-on-top downloads window (used
// when the last Ronin window is closed while something is downloading).
func showDownloadFloat() {
	if dlFloat != nil && !dlFloat.closing {
		return
	}
	hwnd := createAppWindow("Ronin downloads")
	if hwnd == 0 {
		return
	}
	w := &Window{hwnd: hwnd, container: containers.Personal, uiHeight: 84, lastActive: time.Now(), created: time.Now(), float: true}
	windowsByHwnd[hwnd] = w
	s := int32(dpiOf(hwnd))
	wd, ht := 400*s/96, 360*s/96
	sw, _, _ := pGetSystemMetrics.Call(16)
	sh, _, _ := pGetSystemMetrics.Call(17)
	x, y := int32(sw)-wd-16, int32(sh)-ht-16
	if x < 0 {
		x = 0
	}
	if y < 0 {
		y = 0
	}
	pSetWindowPos.Call(hwnd, ^uintptr(0), uintptr(x), uintptr(y), uintptr(wd), uintptr(ht), 0x0040) // topmost, shown
	pUpdateWindow.Call(hwnd)
	if !w.createToolbar() {
		pDestroyWindow.Call(hwnd)
		return
	}
	dlFloat = w
	w.overlay("downloads", downloadsData(w))
	logf("downloads continue in the small window")
}

// floatCloseRequested: with downloads running the small window only
// minimizes (the downloads go on); otherwise it closes.
func floatCloseRequested(w *Window) {
	if activeJobs() > 0 {
		pShowWindow.Call(w.hwnd, 6) // SW_MINIMIZE
		return
	}
	dlFloat = nil
	w.close()
}

// beforeLastWindowCloses is called when the user closes a browser window.
// It returns true when the window must only be hidden (it still owns a
// running engine download).
func beforeLastWindowCloses(w *Window) (hideOnly bool) {
	if w.float || quitting {
		return false
	}
	others := 0
	for _, o := range windowsByHwnd {
		if o != w && !o.closing && !o.float && !o.hiddenDL {
			others++
		}
	}
	dlMu.Lock()
	engine, active := false, 0
	for _, j := range dlJobs {
		if activeJob(j) {
			active++
			if j.op != 0 && j.w == w {
				engine = true
			}
		}
	}
	dlMu.Unlock()
	if active == 0 {
		return false
	}
	if others == 0 {
		showDownloadFloat()
	}
	return engine
}

// closeIdleHiddenWindows really closes hidden windows whose downloads ended.
func closeIdleHiddenWindows() {
	for _, w := range windowsSnapshot() {
		if !w.hiddenDL || w.closing {
			continue
		}
		busy := false
		dlMu.Lock()
		for _, j := range dlJobs {
			if activeJob(j) && j.op != 0 && j.w == w {
				busy = true
			}
		}
		dlMu.Unlock()
		if !busy {
			w.hiddenDL = false
			w.close()
		}
	}
}
