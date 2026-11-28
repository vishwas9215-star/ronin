package agentcore

import (
	"encoding/json"
	"errors"
	"fmt"
	"math/rand"
	"net"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"time"
)

// Page is the browser under the driver: on Windows a WebView2 tab driven through its DevTools protocol (trusted
// input, the helper in an isolated world the page can not touch). Tests use a fake.
type Page interface {
	// Call runs helper code (window.__roninAgent is present) and returns its result as JSON.
	Call(expr string) (string, error)
	Navigate(url string) error // returns when the page has loaded (or after 30 s)
	URL() string
	Back() error
	Tap(x, y float64, slow bool) error // CSS pixels of the viewport; a real press + release
	SelectAll() error                  // select everything in the focused box
	Insert(text string) error          // type text at the cursor (real text input)
	Key(name string) error             // enter, tab, escape, pagedown, pageup, arrow*, backspace, space
	Scroll(up bool) error
	Screenshot() ([]byte, error) // JPEG
	FocusedSecret() string       // "secret" / "normal" / "unknown": checked outside the page's own JavaScript
	FocusedText() string         // the label / text of the focused element (for Enter / Space)
}

// Driver carries out Claude's tools with every rule enforced on the device. One command at a time.
type Driver struct {
	// OwnerBusy: the owner is using the page this moment (his click / key): Claude's next step waits for it
	OwnerBusy func() bool
	P         Page
	IsMoney   IsMoney
	Journal   string // file: the write-ahead journal (an input that was cut off is never pressed again blindly)
	Log       func(string)

	mu           sync.Mutex
	Stopped      bool
	Paused       bool // the owner used the window: stays paused until RESUME
	OwnerHolding bool // ask_owner: the owner has the screen
	ownerResult  string
	Pace         string
	claudeUntil  time.Time
	blocked      []string
	Taint        string
	idHosts      map[string]bool
	ReadPages    map[string]map[string]bool
	AllowIDs     map[string]bool
	blockedActs  map[string]bool
	privateCache map[string]privEntry
	checks       map[string][]time.Time
	done         map[string]map[string]any
	doneOrder    []string
	OnOwnerAsked func(reason string)
	curID        string
	recovered    bool
	Resolve      func(host string) ([]net.IP, error) // DNS; nil = net.LookupIP
}

var (
	inputTools = map[string]bool{"click": true, "type": true, "select": true, "upload": true, "press": true}
	readTools  = map[string]bool{"snapshot": true, "screenshot": true, "get_text": true, "scroll": true, "back": true, "tabs": true, "wait_for": true, "ask_owner": true, "open": true, "downloads": true, "set_pace": true}
	nonDigit   = regexp.MustCompile(`\D`)
)

func (d *Driver) init() {
	if d.idHosts == nil {
		d.idHosts, d.blockedActs, d.privateCache, d.checks, d.done = map[string]bool{}, map[string]bool{}, map[string]privEntry{}, map[string][]time.Time{}, map[string]map[string]any{}
		if d.ReadPages == nil {
			d.ReadPages = map[string]map[string]bool{}
		}
		if d.AllowIDs == nil {
			d.AllowIDs = map[string]bool{}
		}
	}
}

func (d *Driver) log(m string) {
	if d.Log != nil {
		d.Log(m)
	}
}

func e(m string) map[string]any { return map[string]any{"error": m} }

func (d *Driver) host() string { return Host(d.P.URL()) }
func (d *Driver) slow() bool   { return d.Pace == "slow" }

// ---------------- the network guard (called by the browser glue for every request) ----------------

// GuardRequest: the reason to block this request of the Claude tab, or "".
func (d *Driver) GuardRequest(method, url string, mainFrame bool) string {
	d.mu.Lock()
	d.init()
	if d.OwnerHolding {
		d.mu.Unlock()
		return ""
	}
	claude := time.Now().Before(d.claudeUntil)
	taint := d.Taint
	d.mu.Unlock()
	why := Guard(method, url, mainFrame, claude, d.IsMoney, taint, d.host())
	if why == "" && d.IsPrivate(Host(url)) {
		why = "private address " + Host(url)
	}
	if why != "" {
		d.mu.Lock()
		d.blocked = append(d.blocked, why)
		d.mu.Unlock()
		d.log("blocked: " + trim(why, 80))
	}
	return why
}

// GuardNoDNS: the same rules as GuardRequest but without asking DNS (for the browser's request event, which must
// not wait). The glue then calls PrivateKnown and, for a new name, holds the request until IsPrivate answered.
func (d *Driver) GuardNoDNS(method, url string, mainFrame bool) string {
	d.mu.Lock()
	d.init()
	if d.OwnerHolding {
		d.mu.Unlock()
		return ""
	}
	claude := time.Now().Before(d.claudeUntil)
	taint := d.Taint
	d.mu.Unlock()
	why := Guard(method, url, mainFrame, claude, d.IsMoney, taint, d.host())
	if why != "" {
		d.Block(why)
	}
	return why
}

// Holding: the owner has the window (ask_owner): the guard is off.
func (d *Driver) Holding() bool { d.mu.Lock(); defer d.mu.Unlock(); return d.OwnerHolding }

// PrivateKnown: what is already known about a name (no DNS).
func (d *Driver) PrivateKnown(h string) (private, known bool) {
	if h == "" {
		return false, true
	}
	if IsPrivateHost(h) {
		return true, true
	}
	d.mu.Lock()
	defer d.mu.Unlock()
	d.init()
	c, ok := d.privateCache[h]
	if !ok || time.Now().After(c.until) {
		return false, false
	}
	return c.private, true
}

type privEntry struct {
	private bool
	until   time.Time // a name is asked again after a minute (its address may change)
}

// Block records a blocked request (the click / key that caused it reports it to Claude).
func (d *Driver) Block(why string) {
	d.mu.Lock()
	d.blocked = append(d.blocked, why)
	d.mu.Unlock()
	d.log("blocked: " + trim(why, 80))
}

// IsPrivate: the NAME or any address it resolves to is on the home network / loopback / CGNAT.
func (d *Driver) IsPrivate(h string) bool {
	if h == "" {
		return false
	}
	if IsPrivateHost(h) {
		return true
	}
	if v, known := d.PrivateKnown(h); known {
		return v
	}
	res := d.Resolve
	if res == nil {
		res = net.LookupIP
	}
	ips, err := res(h)
	if err != nil || len(ips) == 0 {
		return true // not answered = not allowed (not kept: asked again next time)
	}
	v := false
	for _, ip := range ips {
		if PrivateAddress(ip) {
			v = true
		}
	}
	d.mu.Lock()
	d.privateCache[h] = privEntry{private: v, until: time.Now().Add(time.Minute)}
	d.mu.Unlock()
	return v
}

// ---------------- the owner's buttons ----------------

func (d *Driver) OwnerDone() {
	d.mu.Lock()
	d.ownerResult, d.OwnerHolding = "done", false
	d.mu.Unlock()
}
func (d *Driver) OwnerStop() {
	d.mu.Lock()
	d.ownerResult, d.OwnerHolding, d.Stopped = "stop", false, true
	d.mu.Unlock()
}
func (d *Driver) Resume() { d.mu.Lock(); d.Paused, d.Stopped = false, false; d.mu.Unlock() }

// Checked: the owner looked at the page: interrupted actions may be tried again, and the "sensitive page was read"
// mark is cleared (the next site may receive data again).
func (d *Driver) Checked() {
	d.mu.Lock()
	d.blockedActs = map[string]bool{}
	d.Taint = ""
	d.mu.Unlock()
}
func (d *Driver) OwnerTouched() {
	d.mu.Lock()
	if !d.OwnerHolding && !d.Paused {
		d.Paused = true
		d.log("owner used the window: paused")
	}
	d.mu.Unlock()
}

// ---------------- journal ----------------

type journal struct {
	State      map[string]string          `json:"state"`  // id -> started / done
	Result     map[string]json.RawMessage `json:"result"` // id -> answer
	Target     map[string]string          `json:"target"` // id -> what it pressed (blocked until CHECKED if cut off)
	IDs        []string                   `json:"ids"`
	LastTarget string                     `json:"lastTarget"`
}

func (d *Driver) readJournal() *journal {
	j := &journal{State: map[string]string{}, Result: map[string]json.RawMessage{}}
	if d.Journal == "" {
		return j
	}
	if b, err := os.ReadFile(d.Journal); err == nil {
		json.Unmarshal(b, j)
	}
	if j.State == nil {
		j.State = map[string]string{}
	}
	if j.Result == nil {
		j.Result = map[string]json.RawMessage{}
	}
	if j.Target == nil {
		j.Target = map[string]string{}
	}
	return j
}

func (d *Driver) writeJournal(j *journal) error {
	if d.Journal == "" {
		return nil
	}
	b, err := json.Marshal(j)
	if err != nil {
		return err
	}
	tmp := d.Journal + ".tmp"
	os.MkdirAll(filepath.Dir(d.Journal), 0o700)
	f, err := os.OpenFile(tmp, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o600)
	if err != nil {
		return err
	}
	if _, err = f.Write(b); err == nil {
		err = f.Sync() // on disk BEFORE the input
	}
	if cerr := f.Close(); err == nil {
		err = cerr
	}
	if err != nil {
		return err
	}
	for i := 0; i < 5; i++ { // Windows: a scanner may hold the file for a moment
		if err = os.Rename(tmp, d.Journal); err == nil {
			return nil
		}
		time.Sleep(time.Duration(50*(i+1)) * time.Millisecond)
	}
	return err
}

// setTarget: what this input is about to press, on disk BEFORE it is pressed.
func (d *Driver) setTarget(act string) error {
	d.mu.Lock()
	id := d.curID
	bl := d.blockedActs[act]
	d.mu.Unlock()
	if bl {
		return errBlockedAct
	}
	j := d.readJournal()
	j.Target[id], j.LastTarget = act, act
	return d.writeJournal(j)
}

var errBlockedAct = errors.New("interrupted earlier")

// halted: the owner took over / pressed STOP while a command was running: no further input.
func (d *Driver) halted() bool {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.Stopped || d.Paused || d.OwnerHolding
}

var errHalted = errors.New("the owner took over the window: stopped in the middle")

// IsStopped / IsPaused: read under the lock (for the window).
func (d *Driver) IsStopped() bool { d.mu.Lock(); defer d.mu.Unlock(); return d.Stopped }
func (d *Driver) IsPaused() bool  { d.mu.Lock(); defer d.mu.Unlock(); return d.Paused }

// ---------------- one command ----------------

// Run: one command from Claude. id makes it idempotent: the same id never runs twice.
func (d *Driver) Run(id, tool string, args map[string]any) (out map[string]any) {
	d.mu.Lock()
	d.init()
	if r, ok := d.done[id]; ok {
		d.mu.Unlock()
		return r
	}
	stopped, paused, holding := d.Stopped, d.Paused, d.OwnerHolding
	d.mu.Unlock()
	// the owner is clicking / pressing on the page right now: wait for him to finish (max 8 s), never pause
	// (owner, 28 Nov: only PAUSE / STOP stop Claude)
	if d.OwnerBusy != nil && tool != "ask_owner" {
		for end := time.Now().Add(8 * time.Second); d.OwnerBusy() && time.Now().Before(end); {
			time.Sleep(100 * time.Millisecond)
		}
	}
	// the laptop has one Claude tab (t1) for now: a call meant for another tab never lands on this one
	if t, _ := args["tab"].(string); t != "" && t != "t1" {
		return e("this device has only Claude's main tab (t1): tab " + t + " does not exist here")
	}
	if nt, _ := args["new_tab"].(bool); nt {
		return e("new tabs are not on the laptop yet: use the main tab (no tab argument)")
	}
	if stopped {
		return e("the owner pressed STOP on this device. Claude must wait until he turns Claude on again.")
	}
	if (paused || holding) && tool != "ask_owner" {
		if paused {
			return e("the owner took over the window. Claude waits until he presses RESUME.")
		}
		return e("the owner is using the window right now: wait and try again")
	}
	j := d.readJournal()
	if j.State[id] == "done" {
		var r map[string]any
		if json.Unmarshal(j.Result[id], &r) == nil && r != nil {
			return r
		}
		return map[string]any{"done": "this command already ran (answer kept on the device)"}
	}
	// after a restart: every input that was cut off stays blocked (whatever id Claude uses next) until CHECKED
	d.mu.Lock()
	first := !d.recovered
	d.recovered = true
	d.mu.Unlock()
	if first {
		changed := false
		for oid, st := range j.State {
			if st == "started" {
				if t := j.Target[oid]; t != "" {
					d.mu.Lock()
					d.blockedActs[t] = true
					d.mu.Unlock()
				}
				j.State[oid], changed = "interrupted", true
			}
		}
		if changed {
			d.writeJournal(j)
		}
	}
	if st := j.State[id]; st == "started" || st == "interrupted" {
		return map[string]any{"uncertain": "the last " + tool + " was interrupted (Ronin restarted). It may or may not have happened. Take a snapshot and check the page; the same action stays blocked until the owner presses CHECKED."}
	}
	if inputTools[tool] {
		h := d.host()
		if h == "" {
			return e("this page has no web address (data: / blank page): Claude does not press anything here")
		}
		if Money(h, d.IsMoney) {
			return e(h + " is READ-ONLY for Claude (money / trading / bank site): no clicks or typing at all. Tell the owner what to press.")
		}
	}
	if !readTools[tool] {
		if c := d.call("({c: window.__roninAgent.challenge()})"); c["c"] != nil && c["c"] != "" {
			return e(fmt.Sprint(c["c"]) + ": Claude does not solve human checks. Call ask_owner.")
		}
	}
	if inputTools[tool] {
		j.State[id] = "started"
		if err := d.writeJournal(j); err != nil { // no durable record = no input
			return e("could not write the safety journal (" + trim(err.Error(), 80) + "): nothing was pressed")
		}
	}
	d.mu.Lock()
	d.curID = id
	d.mu.Unlock()
	panicked := false
	func() {
		defer func() {
			if p := recover(); p != nil {
				panicked = true
				out = map[string]any{"uncertain": "the " + tool + " stopped in the middle (" + trim(fmt.Sprint(p), 120) + "). Take a snapshot and check the page; the same action stays blocked until the owner presses CHECKED."}
			}
		}()
		out = d.tool(tool, args)
	}()
	if inputTools[tool] {
		j = d.readJournal()
		if panicked && j.Target[id] != "" {
			d.mu.Lock()
			d.blockedActs[j.Target[id]] = true
			d.mu.Unlock()
		}
		j.LastTarget = ""
		b, _ := json.Marshal(out)
		if len(b) > 4000 {
			b, _ = json.Marshal(map[string]any{"done": "ran (long answer not kept)"})
		}
		j.State[id], j.Result[id] = "done", b
		j.IDs = append(j.IDs, id)
		for len(j.IDs) > 300 { // the last 300 inputs are kept
			delete(j.State, j.IDs[0])
			delete(j.Result, j.IDs[0])
			delete(j.Target, j.IDs[0])
			j.IDs = j.IDs[1:]
		}
		d.writeJournal(j)
	}
	if tool != "ask_owner" {
		d.mu.Lock()
		d.done[id] = out
		d.doneOrder = append(d.doneOrder, id)
		if len(d.doneOrder) > 100 {
			delete(d.done, d.doneOrder[0])
			d.doneOrder = d.doneOrder[1:]
		}
		d.mu.Unlock()
	}
	ok := "ok"
	if out["error"] != nil {
		ok = "refused"
	}
	d.log(tool + " on " + d.host() + ": " + ok)
	if d.slow() && inputTools[tool] {
		time.Sleep(600 * time.Millisecond)
	}
	return out
}

func str(a map[string]any, k string) string { s, _ := a[k].(string); return s }

func (d *Driver) tool(tool string, a map[string]any) map[string]any {
	switch tool {
	case "open":
		return d.open(str(a, "url"))
	case "snapshot":
		return d.snapshot()
	case "get_text":
		return d.call(fmt.Sprintf("window.__roninAgent.getText(%s, %v)", q(str(a, "ref")), d.allowIDs()))
	case "click":
		return d.click(str(a, "ref"))
	case "type":
		sub, _ := a["submit"].(bool)
		return d.typeText(str(a, "ref"), str(a, "text"), sub)
	case "select":
		return d.selectOption(str(a, "ref"), str(a, "option"))
	case "press":
		return d.press(str(a, "key"))
	case "scroll":
		d.P.Scroll(str(a, "direction") == "up")
		return map[string]any{"done": "scrolled"}
	case "back":
		d.P.Back()
		time.Sleep(800 * time.Millisecond)
		return map[string]any{"url": d.P.URL()}
	case "tabs":
		return map[string]any{"tabs": []any{map[string]any{"index": 0, "url": d.P.URL(), "claude": true}}}
	case "screenshot":
		return d.screenshot()
	case "wait_for":
		s, _ := a["seconds"].(float64)
		if s == 0 {
			s = 10
		}
		return d.waitFor(str(a, "text"), int(s))
	case "ask_owner":
		return d.askOwner(str(a, "reason"))
	case "upload":
		return e("upload comes in the next pilot step: ask the owner to attach the file")
	case "downloads":
		return map[string]any{"downloads": []any{}, "note": "downloads are not used in the pilot"}
	case "set_pace":
		d.mu.Lock()
		d.Pace = "normal"
		if str(a, "pace") == "slow" {
			d.Pace = "slow"
		}
		p := d.Pace
		d.mu.Unlock()
		return map[string]any{"done": "pace " + p}
	}
	return e("unknown tool " + tool)
}

func q(s string) string { b, _ := json.Marshal(s); return string(b) }

func (d *Driver) allowIDs() bool {
	h := d.host() // never ask the browser while holding the lock
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.AllowIDs[h]
}

// call runs helper code and returns its object.
func (d *Driver) call(expr string) map[string]any {
	raw, err := d.P.Call(expr)
	if err != nil {
		return e("the page did not answer: " + trim(err.Error(), 100))
	}
	var m map[string]any
	if json.Unmarshal([]byte(raw), &m) != nil || m == nil {
		return e("bad answer from the page")
	}
	return m
}

func num(v any) float64 { f, _ := v.(float64); return f }

func (d *Driver) claudeInput(f func() error) error {
	d.mu.Lock()
	d.claudeUntil = time.Now().Add(time.Hour)
	d.mu.Unlock()
	defer func() { d.mu.Lock(); d.claudeUntil = time.Now().Add(2 * time.Second); d.mu.Unlock() }()
	return f()
}

func (d *Driver) blockedSince(n int) []string {
	d.mu.Lock()
	defer d.mu.Unlock()
	if len(d.blocked) <= n {
		return nil
	}
	return append([]string(nil), d.blocked[n:]...)
}

func (d *Driver) nBlocked() int { d.mu.Lock(); defer d.mu.Unlock(); return len(d.blocked) }

func (d *Driver) open(u string) map[string]any {
	d.mu.Lock()
	why := OpenRefusal(u, d.IsMoney, d.ReadPages, d.Taint)
	d.mu.Unlock()
	if why != "" {
		return e(why + ": refused")
	}
	h := Host(u)
	if d.IsPrivate(h) {
		return e("home-network / server addresses are never opened by Claude")
	}
	if err := d.P.Navigate(u); err != nil {
		return e("could not open: " + trim(err.Error(), 100))
	}
	if d.slow() {
		time.Sleep(1200 * time.Millisecond)
	} else {
		time.Sleep(300 * time.Millisecond)
	}
	s := d.snapshot()
	ch, _ := s["challenge"].(string)
	out := map[string]any{"url": d.P.URL(), "title": s["title"], "hint": "call snapshot to read it"}
	if ch != "" {
		d.mu.Lock()
		now := time.Now()
		var keep []time.Time
		for _, t := range d.checks[h] {
			if now.Sub(t) < 10*time.Minute {
				keep = append(keep, t)
			}
		}
		keep = append(keep, now)
		d.checks[h] = keep
		d.mu.Unlock()
		if len(keep) >= 3 {
			return e(fmt.Sprintf("%s asked for a human check %d times in 10 minutes: stopping here. Tell the owner.", h, len(keep)))
		}
		out["challenge"], out["hint"] = ch, "human check: call ask_owner"
	}
	return out
}

func (d *Driver) snapshot() map[string]any {
	h := d.host()
	s := d.call(fmt.Sprintf("window.__roninAgent.snapshot({allowIds: %v})", d.allowIDs()))
	t, _ := s["page_text_untrusted"].(string)
	d.mu.Lock()
	if strings.Contains(t, " hidden]") {
		d.idHosts[h] = true
	}
	if Sensitive(h, d.IsMoney, d.idHosts) {
		d.Taint = h
	}
	d.mu.Unlock()
	return s
}

// checkTarget: the common checks before anything is pressed on an element (click, type, select).
func (d *Driver) checkTarget(at map[string]any) map[string]any {
	name := str(at, "name")
	switch {
	case at["captcha"] == true:
		return e("this is a human check: call ask_owner")
	case str(at, "kind") == "a" && ScriptLink(str(at, "href")):
		return e("script link: refused")
	case at["money"] == true:
		return e(`"` + name + `" is a money / order action: Claude may not press it. Tell the owner.`)
	case at["covered"] == true:
		return e(`something else covers "` + name + `" (` + str(at, "coveredBy") + `): refused (possible trick). Take a new snapshot.`)
	}
	return nil
}

// sameTarget: just before the press, the element is still the one that was checked, in the same place
// (a page that slides another button under the pointer is caught).
func (d *Driver) sameTarget(ref string, at map[string]any) bool {
	now := d.call("window.__roninAgent.locate(" + q(ref) + ")")
	if now["error"] != nil || now["covered"] == true || str(now, "fp") != str(at, "fp") {
		return false
	}
	dx, dy := num(now["x"])-num(at["x"]), num(now["y"])-num(at["y"])
	return dx*dx+dy*dy <= 9
}

func (d *Driver) interrupted(name string) map[string]any {
	return e(`"` + name + `" was interrupted earlier and its result is unknown. The owner must check the page and press CHECKED first.`)
}

func (d *Driver) click(ref string) map[string]any {
	at := d.call("window.__roninAgent.locate(" + q(ref) + ")")
	if at["error"] != nil {
		return at
	}
	if r := d.checkTarget(at); r != nil {
		return r
	}
	name := str(at, "name")
	if err := d.setTarget("click|" + d.host() + "|" + str(at, "fp")); err != nil {
		if err == errBlockedAct {
			return d.interrupted(name)
		}
		return e("could not write the safety journal: nothing was pressed")
	}
	if !d.sameTarget(ref, at) {
		return e(`"` + name + `" moved or changed just before the click: refused (possible trick). Take a new snapshot.`)
	}
	b0 := d.nBlocked()
	if err := d.claudeInput(func() error { return d.tap(num(at["x"]), num(at["y"])) }); err != nil {
		return e("the click did not go through: " + trim(err.Error(), 100))
	}
	if b := d.blockedSince(b0); b != nil {
		return e(`clicked "` + name + `", but the page then tried ` + strings.Join(b, "; ") + `. BLOCKED on the device. Tell the owner.`)
	}
	return map[string]any{"done": "clicked " + str(at, "kind") + ` "` + name + `"`, "url": d.P.URL()}
}

func (d *Driver) tap(x, y float64) error {
	if d.halted() {
		return errHalted
	}
	err := d.P.Tap(x+rand.Float64()-0.5, y+rand.Float64()-0.5, d.slow())
	if d.slow() {
		time.Sleep(700 * time.Millisecond)
	} else {
		time.Sleep(300 * time.Millisecond)
	}
	return err
}

func (d *Driver) selectOption(ref, option string) map[string]any {
	at := d.call("window.__roninAgent.locate(" + q(ref) + ")")
	if at["error"] != nil {
		return at
	}
	if r := d.checkTarget(at); r != nil {
		return r
	}
	if err := d.setTarget("select|" + d.host() + "|" + str(at, "fp")); err != nil {
		if err == errBlockedAct {
			return d.interrupted(str(at, "name"))
		}
		return e("could not write the safety journal: nothing was chosen")
	}
	if d.halted() {
		return e(errHalted.Error())
	}
	b0 := d.nBlocked()
	var r map[string]any
	d.claudeInput(func() error {
		r = d.call(fmt.Sprintf("window.__roninAgent.selectOption(%s, %s)", q(ref), q(option)))
		return nil
	})
	time.Sleep(300 * time.Millisecond)
	if b := d.blockedSince(b0); b != nil {
		return e("chose an option, but the page then tried " + strings.Join(b, "; ") + ". BLOCKED.")
	}
	return r
}

func (d *Driver) typeText(ref, text string, submit bool) map[string]any {
	if len([]rune(text)) > 2000 {
		return e("too much text in one go (2000 letters at most)")
	}
	at := d.call("window.__roninAgent.locate(" + q(ref) + ")")
	if at["error"] != nil {
		return at
	}
	name := str(at, "name")
	switch {
	case at["password"] == true:
		return e("password box: only the owner types passwords. Call ask_owner.")
	case at["otp"] == true || SecretLabel(name) && LooksLikeOtp(text):
		return e("OTP box: the owner types the OTP. Call ask_owner.")
	case at["captcha"] == true:
		return e("CAPTCHA box: call ask_owner")
	case at["covered"] == true:
		return e(`"` + name + `" is covered by something else: refused`)
	case NumberNeedsOwner(name, text, at["otpPage"] == true):
		return e(fmt.Sprintf(`"%d-digit number" looks like an OTP / PIN (this page asks for one): the owner types it. Call ask_owner.`, len(nonDigit.ReplaceAllString(text, ""))))
	}
	if before := d.call("window.__roninAgent.value(" + q(ref) + ")"); before["value"] == text && !submit {
		return map[string]any{"done": `already matched "` + name + `"`}
	}
	if err := d.setTarget("type|" + d.host() + "|" + str(at, "fp")); err != nil {
		if err == errBlockedAct {
			return d.interrupted(name)
		}
		return e("could not write the safety journal: nothing was typed")
	}
	native := "unknown"
	err := d.claudeInput(func() error {
		if err := d.tap(num(at["x"]), num(at["y"])); err != nil {
			return err
		}
		// checked outside the page's JavaScript: what the focused box really is. Only a box known to be a normal
		// text box gets text; "unknown" (another frame, nothing focused...) is refused too.
		native = d.P.FocusedSecret()
		if native != "normal" {
			return errSecret
		}
		if d.halted() {
			return errHalted
		}
		if err := d.P.SelectAll(); err != nil {
			return err
		}
		if !d.slow() {
			return d.P.Insert(text)
		}
		for _, r := range text { // slow: letter by letter; the owner's touch / STOP stops it at once
			if d.halted() {
				return errHalted
			}
			if err := d.P.Insert(string(r)); err != nil {
				return err
			}
			time.Sleep(time.Duration(60+rand.Intn(90)) * time.Millisecond)
		}
		return nil
	})
	if errors.Is(err, errSecret) {
		if native == "secret" {
			return e(`the engine says "` + name + `" is a password / code box: only the owner types it.`)
		}
		return e(`could not confirm that "` + name + `" is a normal text box (focus went elsewhere): nothing was typed. Take a new snapshot.`)
	}
	if err != nil {
		return e("typing did not go through: " + trim(err.Error(), 100))
	}
	time.Sleep(150 * time.Millisecond)
	after := d.call("window.__roninAgent.value(" + q(ref) + ")")
	v, _ := after["value"].(string)
	check := "unverified"
	switch {
	case after["secret"] == true:
		check = "typed (secret box: not read back)"
	case v == text:
		check = "verified"
	case strings.Contains(v, text):
		check = "verified (box had more text)"
	}
	out := map[string]any{"done": `typed into "` + name + `"`, "check": check, "method": "devtools-insert", "editorinfo": native}
	if submit {
		if check != "verified" {
			out["submitted"] = false
			out["note"] = "not submitted: the box does not show exactly the text. Check it with a snapshot."
			return out
		}
		b0 := d.nBlocked()
		kerr := d.claudeInput(func() error {
			if d.halted() {
				return errHalted
			}
			return d.P.Key("enter")
		})
		time.Sleep(300 * time.Millisecond)
		if b := d.blockedSince(b0); b != nil {
			return e(`typed into "` + name + `" and pressed Enter, but the page then tried ` + strings.Join(b, "; ") + `. BLOCKED on the device. Tell the owner.`)
		}
		if kerr != nil {
			return e("typed, but Enter did not go through: " + trim(kerr.Error(), 100))
		}
		out["submitted"] = true
	}
	return out
}

var errSecret = errors.New("secret box")

var keys = map[string]bool{"enter": true, "tab": true, "escape": true, "pagedown": true, "pageup": true, "arrowdown": true, "arrowup": true, "arrowleft": true, "arrowright": true, "backspace": true, "space": true}

// moneyWord: the same words as the page helper's MONEY_WORD (a focused "Pay now" is never activated by a key).
var moneyWord = regexp.MustCompile(`(?i)\b(buy|sell|place order|confirm order|modify order|square ?off|exit( all| position)?|pay( now)?|transfer|withdraw|send money|proceed to pay|submit order|checkout|confirm payment|invest now)\b|खरीद|बेच|भुगतान|ऑर्डर|पैसे भेज|निकासी`)

func (d *Driver) press(k string) map[string]any {
	k = strings.ToLower(k)
	if !keys[k] {
		return e("unknown key " + k)
	}
	if k == "enter" || k == "space" { // a key can press the focused button: the same money check as a click
		if t := d.P.FocusedText(); moneyWord.MatchString(t) {
			return e(`the focused element "` + trim(t, 40) + `" is a money / order action: Claude may not press it. Tell the owner.`)
		}
		if err := d.setTarget("press|" + d.host() + "|" + k + "|" + trim(d.P.FocusedText(), 40)); err != nil {
			if err == errBlockedAct {
				return d.interrupted(k)
			}
			return e("could not write the safety journal: nothing was pressed")
		}
	}
	b0 := d.nBlocked()
	err := d.claudeInput(func() error {
		if d.halted() {
			return errHalted
		}
		return d.P.Key(k)
	})
	if d.slow() {
		time.Sleep(400 * time.Millisecond)
	} else {
		time.Sleep(150 * time.Millisecond)
	}
	if b := d.blockedSince(b0); b != nil {
		return e("pressed " + k + ", but the page then tried " + strings.Join(b, "; ") + ". BLOCKED.")
	}
	if err != nil {
		return e("the key did not go through: " + trim(err.Error(), 100))
	}
	return map[string]any{"done": "pressed " + k}
}

func (d *Driver) screenshot() map[string]any {
	h := d.host()
	d.mu.Lock()
	sens := Sensitive(h, d.IsMoney, d.idHosts)
	d.mu.Unlock()
	if sens {
		return e("screenshot of a money / government / ID page is refused (pixels cannot be hidden). Use snapshot: ID numbers are hidden there.")
	}
	b, err := d.P.Screenshot()
	if err != nil {
		return e("no screenshot: " + trim(err.Error(), 100))
	}
	return map[string]any{"image": b, "note": d.P.URL()}
}

func (d *Driver) waitFor(text string, seconds int) map[string]any {
	if seconds < 1 {
		seconds = 1
	}
	if seconds > 20 {
		seconds = 20
	}
	end := time.Now().Add(time.Duration(seconds) * time.Second)
	for time.Now().Before(end) {
		if text == "" || d.call("({t: window.__roninAgent.hasText(" + q(text) + ")})")["t"] == true {
			return map[string]any{"done": "seen"}
		}
		time.Sleep(250 * time.Millisecond)
	}
	return e("not seen in time")
}

func (d *Driver) askOwner(reason string) map[string]any {
	finish := func(r string) map[string]any {
		if r == "stop" {
			return e("the owner stopped Claude here")
		}
		return map[string]any{"done": "the owner finished. Take a new snapshot."}
	}
	d.mu.Lock()
	if r := d.ownerResult; r != "" {
		d.ownerResult = ""
		d.mu.Unlock()
		return finish(r)
	}
	if !d.OwnerHolding {
		d.OwnerHolding = true
		cb := d.OnOwnerAsked
		d.mu.Unlock()
		if cb != nil {
			cb(trim(reason, 120))
		}
		return map[string]any{"with_owner": "handed to the owner. Call ask_owner again to wait for his Done (each call waits up to 15 s)."}
	}
	d.mu.Unlock()
	end := time.Now().Add(15 * time.Second)
	for time.Now().Before(end) {
		d.mu.Lock()
		h := d.OwnerHolding
		d.mu.Unlock()
		if !h {
			break
		}
		time.Sleep(200 * time.Millisecond)
	}
	d.mu.Lock()
	r := d.ownerResult
	d.ownerResult = ""
	d.mu.Unlock()
	if r == "" {
		return map[string]any{"waiting": "still with the owner. Call ask_owner again (do not press anything)."}
	}
	return finish(r)
}

func trim(s string, n int) string {
	if len(s) > n {
		return s[:n]
	}
	return s
}
