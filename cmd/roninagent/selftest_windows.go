//go:build windows

package main

// -selftest: the real window, the real WebView2 and the real driver on made-up pages (served from memory, the
// same guard in front). Each check prints PASS / FAIL into data\selftest.txt. Used by CI on a Windows machine
// and on the owner's laptop.

import (
	"context"
	"fmt"
	"net"
	"regexp"
	"strings"
	"time"

	"roninbrowser/internal/agentcore"
)

func selfTestPages() map[string]string {
	head := `<!doctype html><html><head><meta charset="utf-8"><title>%s</title></head><body style="font:16px sans-serif">`
	return map[string]string{
		"epds.ronin.example/": fmt.Sprintf(head, "EPDS प्रपत्र-2 (test)") + `
<h3>प्रपत्र-2</h3>
<label>Employee name <input id="name"></label><br>
<label>Portal password <input id="pw" type="password"></label><br>
<label>Remarks <input id="trick" style="-webkit-text-security:disc"></label><br>
<label>Pin code <input id="pin" inputmode="numeric" maxlength="6"></label><br>
<button id="save" onclick="document.body.dataset.saves=(+(document.body.dataset.saves||0)+1)">Save</button>
<button id="order" onclick="fetch('/api/placeOrder',{method:'POST',body:'x'}).catch(()=>{})">Send</button>
<div style="position:relative;height:40px"><button id="under" onclick="document.body.dataset.under=1">Download</button>
<div style="position:absolute;left:0;top:0;width:200px;height:40px;background:rgba(0,0,0,0.01)"></div></div>
</body></html>`,
		"web.dhan.co/": fmt.Sprintf(head, "Dhan (test copy)") + `<button onclick="1">Positions</button></body></html>`,
	}
}

var refRe = regexp.MustCompile(`\[(e\d+)\] ([a-z:]+) "([^"]*)"`)

func (a *app) selfTest(data string) {
	var out strings.Builder
	pass, fail := 0, 0
	check := func(ok bool, name string, detail any) {
		if ok {
			pass++
			fmt.Fprintf(&out, "PASS %s\n", name)
		} else {
			fail++
			fmt.Fprintf(&out, "FAIL %s: %v\n", name, detail)
		}
	}
	defer func() {
		if r := recover(); r != nil {
			fail++
			fmt.Fprintf(&out, "FAIL panic: %v\n", r)
		}
		fmt.Fprintf(&out, "TOTAL %d passed, %d failed\n", pass, fail)
		writeResult(data, out.String())
		logf("selftest: %d passed, %d failed", pass, fail)
		time.Sleep(500 * time.Millisecond)
		pPostMessageW.Call(a.hwnd, wmClose, 0, 0)
	}()
	time.Sleep(3 * time.Second) // the views start
	d := a.drv
	d.Resolve = func(string) ([]net.IP, error) { return []net.IP{net.ParseIP("8.8.8.8")}, nil }
	id := 0
	run := func(tool string, args map[string]any) map[string]any {
		id++
		return d.Run(fmt.Sprintf("st-%d", id), tool, args)
	}
	r := run("open", map[string]any{"url": "https://epds.ronin.example/"})
	check(r["error"] == nil && strings.Contains(fmt.Sprint(r["title"]), "EPDS"), "open + isolated helper", r)
	s := run("snapshot", nil)
	refs := map[string]string{}
	els, _ := s["elements"].([]any)
	for _, l := range els {
		if m := refRe.FindStringSubmatch(fmt.Sprint(l)); m != nil {
			refs[strings.TrimSpace(m[3])] = m[1]
		}
	}
	check(refs["Employee name"] != "" && refs["Save"] != "", "snapshot finds the form", s["elements"])
	r = run("type", map[string]any{"ref": refs["Employee name"], "text": "सीता देवी"})
	check(r["check"] == "verified", "Hindi typed and read back (trusted input)", r)
	r = run("type", map[string]any{"ref": refs["Portal password"], "text": "x"})
	check(r["error"] != nil, "password box refused", r)
	r = run("type", map[string]any{"ref": refs["Remarks"], "text": "abc"})
	check(r["error"] != nil && strings.Contains(fmt.Sprint(r["error"]), "engine"), "box that hides its text (CSS) refused by the engine check", r)
	r = run("type", map[string]any{"ref": refs["Pin code"], "text": "228001"})
	check(r["error"] == nil, "postal pincode typed", r)
	saves := func() string {
		v, _ := a.page.Call("({s: document.body.dataset.saves || '0'})")
		return v
	}
	id++
	sid := fmt.Sprintf("st-%d", id)
	d.Run(sid, "click", map[string]any{"ref": refs["Save"]})
	d.Run(sid, "click", map[string]any{"ref": refs["Save"]})
	check(strings.Contains(saves(), `"1"`), "Save pressed once for one id (real click)", saves())
	r = run("click", map[string]any{"ref": refs["Send"]})
	time.Sleep(500 * time.Millisecond)
	check(r["error"] != nil && strings.Contains(fmt.Sprint(r["error"]), "BLOCKED"), "an order request after a click is blocked", r)
	r = run("click", map[string]any{"ref": refs["Download"]})
	check(r["error"] != nil, "a covered button is refused", r)
	r = run("screenshot", nil)
	b, _ := r["image"].([]byte)
	check(len(b) > 1000, "screenshot", len(b))
	r = run("open", map[string]any{"url": "http://192.168.1.1/"})
	check(r["error"] != nil, "home network refused", r)
	run("open", map[string]any{"url": "https://web.dhan.co/"})
	r = run("click", map[string]any{"ref": "e1"})
	check(r["error"] != nil && strings.Contains(fmt.Sprint(r["error"]), "READ-ONLY"), "money site read-only", r)
	check(d.GuardRequest("POST", "https://web.dhan.co/api/x", false) != "", "guard: POST to a money site", nil)
	// a page script opening a WebSocket / fetch to this PC never reaches it (the proxy checks the real address)
	run("open", map[string]any{"url": "https://epds.ronin.example/"})
	b0 := a.proxyBlocked.Load()
	a.page.Call(`(function(){try{new WebSocket('wss://127.0.0.1:8443/x')}catch(e){};try{new WebSocket('wss://localhost:8443/x')}catch(e){};return {}})()`)
	time.Sleep(2 * time.Second)
	check(a.proxyBlocked.Load() > b0, "WebSocket to this PC refused by the proxy", a.proxyBlocked.Load()-b0)
	// the proxy also stands alone: a direct check of its refusal (same code the browser goes through)
	if c, err := a.proxy.Dial(context.Background(), "127.0.0.1:8443"); err == nil {
		c.Close()
		check(false, "proxy dial to this PC refused", "connected")
	} else {
		check(true, "proxy dial to this PC refused", nil)
	}
	r = run("press", map[string]any{"key": "tab"})
	check(r["error"] == nil, "a key press", r)
	// the owner clicks on the page: C waits a moment, never pauses (only PAUSE / STOP do)
	a.busyUntil.Store(time.Now().Add(700 * time.Millisecond).UnixMilli())
	t0 := time.Now()
	r = run("snapshot", nil)
	check(r["error"] == nil && time.Since(t0) >= 500*time.Millisecond && !d.IsPaused(), "owner's click: C waits, does not pause", r)
	d.OwnerTouched() // what PAUSE does
	r = run("snapshot", nil)
	check(r["error"] != nil, "PAUSE pauses C", r)
	d.Resume()
	// another tab than t1 never lands on the laptop's only tab
	r = run("snapshot", map[string]any{"tab": "t2"})
	check(r["error"] != nil, "tab t2 refused on the laptop", r)
	// minimized: the page's timers keep their pace (EPDS AJAX behind other windows)
	a.page.Call("window.beats=0;setInterval(function(){window.beats++},1000);0")
	runOnUI(func() { pShowWindow.Call(a.hwnd, 6) }) // SW_MINIMIZE
	time.Sleep(1500 * time.Millisecond)
	beat0, _ := a.page.Call("window.beats")
	time.Sleep(8 * time.Second)
	beat1, _ := a.page.Call("window.beats")
	n0, n1 := 0, 0
	fmt.Sscan(beat0, &n0)
	fmt.Sscan(beat1, &n1)
	check(n1-n0 >= 6, "timers keep their pace while minimized", fmt.Sprintf("%d beats in 8 s", n1-n0))
	runOnUI(func() { pShowWindow.Call(a.hwnd, swShow) })
	_ = agentcore.Host
}
