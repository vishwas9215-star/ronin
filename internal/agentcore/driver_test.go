package agentcore

import (
	"encoding/json"
	"net"
	"path/filepath"
	"strings"
	"sync"
	"testing"
)

// fakePage: a page with a few boxes; what the helper would answer is decided here.
type fakePage struct {
	mu        sync.Mutex
	url       string
	boxes     map[string]map[string]any // ref -> locate answer
	values    map[string]string
	taps      int
	typed     []string
	secret    string
	onTap     func()
	challenge string
	text      string
	onInsert  func()
	focusText string
}

func newFake() *fakePage {
	return &fakePage{url: "https://epds.example.gov.in/", values: map[string]string{}, secret: "normal", boxes: map[string]map[string]any{
		"e1": {"x": 10.0, "y": 10.0, "kind": "input:text", "name": "Employee name", "fp": "f1"},
		"e2": {"x": 10.0, "y": 40.0, "kind": "input:password", "name": "Portal password", "password": true, "fp": "f2"},
		"e3": {"x": 10.0, "y": 70.0, "kind": "button", "name": "Save", "fp": "f3"},
		"e4": {"x": 10.0, "y": 90.0, "kind": "input:text", "name": "Pin code", "fp": "f4"},
		"e5": {"x": 10.0, "y": 99.0, "kind": "button", "name": "Pay now", "money": true, "fp": "f5"},
	}}
}

func (p *fakePage) ref(expr string) string {
	i := strings.Index(expr, `("`)
	if i < 0 {
		return ""
	}
	return strings.SplitN(expr[i+2:], `"`, 2)[0]
}

func (p *fakePage) Call(expr string) (string, error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	var v any
	switch {
	case strings.Contains(expr, ".challenge()"):
		v = map[string]any{"c": p.challenge}
	case strings.Contains(expr, ".snapshot("):
		v = map[string]any{"url": p.url, "title": "EPDS", "challenge": p.challenge, "elements": []string{"[e1] input:text \"Employee name\""}, "page_text_untrusted": p.text}
	case strings.Contains(expr, ".locate("):
		b := p.boxes[p.ref(expr)]
		if b == nil {
			v = map[string]any{"error": "ref gone"}
		} else {
			v = b
		}
	case strings.Contains(expr, ".value("):
		r := p.ref(expr)
		if p.boxes[r]["password"] == true {
			v = map[string]any{"secret": true}
		} else {
			v = map[string]any{"value": p.values[r]}
		}
	case strings.Contains(expr, ".hasText("):
		v = map[string]any{"t": strings.Contains(p.text, p.ref(expr))}
	default:
		v = map[string]any{"error": "?"}
	}
	b, _ := json.Marshal(v)
	return string(b), nil
}
func (p *fakePage) Navigate(u string) error { p.mu.Lock(); p.url = u; p.mu.Unlock(); return nil }
func (p *fakePage) URL() string             { p.mu.Lock(); defer p.mu.Unlock(); return p.url }
func (p *fakePage) Back() error             { return nil }
func (p *fakePage) Tap(x, y float64, slow bool) error {
	p.mu.Lock()
	p.taps++
	f := p.onTap
	p.mu.Unlock()
	if f != nil {
		f()
	}
	return nil
}
func (p *fakePage) SelectAll() error { p.mu.Lock(); p.values["e1"] = ""; p.mu.Unlock(); return nil }
func (p *fakePage) Insert(t string) error {
	p.mu.Lock()
	p.typed = append(p.typed, t)
	p.values["e1"] += t
	f := p.onInsert
	p.mu.Unlock()
	if f != nil {
		f()
	}
	return nil
}
func (p *fakePage) FocusedText() string         { p.mu.Lock(); defer p.mu.Unlock(); return p.focusText }
func (p *fakePage) Key(string) error            { return nil }
func (p *fakePage) Scroll(bool) error           { return nil }
func (p *fakePage) Screenshot() ([]byte, error) { return []byte{1, 2}, nil }
func (p *fakePage) FocusedSecret() string       { p.mu.Lock(); defer p.mu.Unlock(); return p.secret }

func newDriver(t *testing.T, p *fakePage) *Driver {
	return &Driver{P: p, Journal: filepath.Join(t.TempDir(), "journal.json"), Resolve: func(string) ([]net.IP, error) { return []net.IP{net.ParseIP("8.8.8.8")}, nil }}
}

func TestDriverTypesVerifiesAndNeverTypesSecrets(t *testing.T) {
	p := newFake()
	d := newDriver(t, p)
	r := d.Run("1", "type", map[string]any{"ref": "e1", "text": "राम कुमार"})
	if r["check"] != "verified" {
		t.Fatal(r)
	}
	if r := d.Run("2", "type", map[string]any{"ref": "e2", "text": "x"}); r["error"] == nil {
		t.Fatal("password box")
	}
	if r := d.Run("3", "type", map[string]any{"ref": "e4", "text": "228001"}); r["error"] != nil {
		t.Fatal("a postal pincode must be typed", r)
	}
	p.mu.Lock()
	p.secret = "secret" // the page lies (says text) but the engine says password
	p.mu.Unlock()
	if r := d.Run("4", "type", map[string]any{"ref": "e1", "text": "abc"}); r["error"] == nil || !strings.Contains(r["error"].(string), "engine") {
		t.Fatal("engine check", r)
	}
}

func TestDriverMoneyAndPrivateAndScreenshots(t *testing.T) {
	p := newFake()
	d := newDriver(t, p)
	if r := d.Run("1", "click", map[string]any{"ref": "e5"}); r["error"] == nil {
		t.Fatal("money button")
	}
	if r := d.Run("2", "screenshot", nil); r["error"] == nil {
		t.Fatal("gov page: no screenshot")
	}
	d.Run("3", "open", map[string]any{"url": "https://web.dhan.co/"})
	if r := d.Run("4", "click", map[string]any{"ref": "e3"}); r["error"] == nil || !strings.Contains(r["error"].(string), "READ-ONLY") {
		t.Fatal("money site is read only", r)
	}
	d.Resolve = func(string) ([]net.IP, error) { return []net.IP{net.ParseIP("192.168.1.1")}, nil }
	if r := d.Run("5", "open", map[string]any{"url": "https://router.example.com/"}); r["error"] == nil {
		t.Fatal("a name that resolves to the home network")
	}
	if why := d.GuardRequest("POST", "https://web.dhan.co/api/x", false); why == "" {
		t.Fatal("guard")
	}
}

func TestDriverOnceOnlyEvenAfterARestart(t *testing.T) {
	p := newFake()
	d := newDriver(t, p)
	d.Run("save-1", "click", map[string]any{"ref": "e3"})
	d.Run("save-1", "click", map[string]any{"ref": "e3"})
	if p.taps != 1 {
		t.Fatal("same id twice", p.taps)
	}
	// Ronin restarted (a new driver, same journal): the answer is given again, no second tap
	d2 := &Driver{P: p, Journal: d.Journal}
	if r := d2.Run("save-1", "click", map[string]any{"ref": "e3"}); r["done"] == nil || p.taps != 1 {
		t.Fatal("after restart", r, p.taps)
	}
	// cut off in the middle (Ronin died during the press): after the restart the SAME button stays blocked for
	// ANY new id until CHECKED, and the old id answers "uncertain"
	j := d2.readJournal()
	j.State["save-2"], j.Target["save-2"] = "started", "click|epds.example.gov.in|f3"
	d2.writeJournal(j)
	d3 := &Driver{P: p, Journal: d.Journal}
	if r := d3.Run("save-3", "click", map[string]any{"ref": "e3"}); r["error"] == nil || p.taps != 1 {
		t.Fatal("blocked until CHECKED, whatever the id", r)
	}
	if r := d3.Run("save-2", "click", map[string]any{"ref": "e3"}); r["uncertain"] == nil {
		t.Fatal("uncertain", r)
	}
	d3.Checked()
	if r := d3.Run("save-4", "click", map[string]any{"ref": "e3"}); r["error"] != nil {
		t.Fatal(r)
	}
}

func TestDriverClickThatTriesAnOrderIsReported(t *testing.T) {
	p := newFake()
	d := newDriver(t, p)
	p.onTap = func() { d.GuardRequest("POST", "https://epds.example.gov.in/api/placeOrder", false) }
	if r := d.Run("1", "click", map[string]any{"ref": "e3"}); r["error"] == nil || !strings.Contains(r["error"].(string), "BLOCKED") {
		t.Fatal(r)
	}
}

func TestDriverOwnerControls(t *testing.T) {
	p := newFake()
	d := newDriver(t, p)
	asked := ""
	d.OnOwnerAsked = func(r string) { asked = r }
	if r := d.Run("1", "ask_owner", map[string]any{"reason": "login"}); r["with_owner"] == nil || asked != "login" {
		t.Fatal(r)
	}
	if r := d.Run("2", "click", map[string]any{"ref": "e3"}); r["error"] == nil {
		t.Fatal("owner holds the window")
	}
	d.OwnerDone()
	if r := d.Run("3", "ask_owner", nil); r["done"] == nil {
		t.Fatal(r)
	}
	d.OwnerTouched()
	if r := d.Run("4", "snapshot", nil); r["error"] == nil {
		t.Fatal("paused")
	}
	d.Resume()
	d.OwnerStop()
	if r := d.Run("5", "snapshot", nil); r["error"] == nil {
		t.Fatal("stopped")
	}
	d.Resume()
	p.challenge = "human-check page"
	if r := d.Run("6", "click", map[string]any{"ref": "e3"}); r["error"] == nil {
		t.Fatal("no clicks on a human check")
	}
}

// Review of the laptop agent (28 Nov): permanent tests.
func TestOnlyAConfirmedNormalBoxGetsText(t *testing.T) {
	p := newFake()
	d := newDriver(t, p)
	p.secret = "unknown" // focus went into another frame / shadow root the check could not see
	if r := d.Run("1", "type", map[string]any{"ref": "e1", "text": "abc"}); r["error"] == nil || len(p.typed) != 0 {
		t.Fatal("unknown must be refused", r, p.typed)
	}
}

func TestOwnerTouchStopsSlowTypingAtOnce(t *testing.T) {
	p := newFake()
	d := newDriver(t, p)
	d.Pace = "slow"
	n := 0
	p.onInsert = func() {
		n++
		if n == 2 {
			d.OwnerTouched()
		}
	}
	r := d.Run("1", "type", map[string]any{"ref": "e1", "text": "abcdefgh"})
	if r["error"] == nil || len(p.typed) != 2 {
		t.Fatal("must stop after the owner's touch", r, p.typed)
	}
}

func TestSubmitOnlyWhenVerifiedAndBlockedSubmitIsAnError(t *testing.T) {
	p := newFake()
	d := newDriver(t, p)
	p.onInsert = func() { p.values["e1"] = "something else" }
	if r := d.Run("1", "type", map[string]any{"ref": "e1", "text": "abc", "submit": true}); r["submitted"] != false {
		t.Fatal("no blind submit", r)
	}
	p.onInsert = nil
	d.P = &keyBlocker{fakePage: p, d: d}
	if r := d.Run("2", "type", map[string]any{"ref": "e1", "text": "xyz", "submit": true}); r["error"] == nil || !strings.Contains(r["error"].(string), "BLOCKED") {
		t.Fatal("a blocked submit is an error", r)
	}
}

type keyBlocker struct {
	*fakePage
	d *Driver
}

func (k *keyBlocker) Key(string) error {
	k.d.GuardRequest("POST", "https://shop.example.com/checkout", true)
	return nil
}

func TestEnterOnAFocusedMoneyButtonAndSelectChecks(t *testing.T) {
	p := newFake()
	d := newDriver(t, p)
	p.focusText = "Pay now"
	if r := d.Run("1", "press", map[string]any{"key": "enter"}); r["error"] == nil {
		t.Fatal("Enter on Pay now", r)
	}
	if r := d.Run("2", "select", map[string]any{"ref": "e5", "option": "x"}); r["error"] == nil {
		t.Fatal("select on a money element", r)
	}
}

func TestCheckedClearsTheSensitiveMark(t *testing.T) {
	p := newFake()
	d := newDriver(t, p)
	d.Run("1", "snapshot", nil) // a .gov.in page: sensitive
	if d.Taint == "" {
		t.Fatal("taint")
	}
	d.Checked()
	if d.Taint != "" {
		t.Fatal("CHECKED clears it")
	}
}
