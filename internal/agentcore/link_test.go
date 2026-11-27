package agentcore_test

import (
	"bytes"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"path/filepath"
	"regexp"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"roninbrowser/internal/agentcore"
	"roninbrowser/internal/agentrelay"
)

// The laptop agent end to end: Claude -> the real relay -> this Link -> the driver (a fake page). The same
// checks as the Android AgentLinkE2ETest: owner-signed grant, typing verified, a Save once only, money read-only,
// "Claude हटाएँ" ends it, and no Connect card without "Claude जोड़ें".

type page struct {
	url   atomic.Value
	saves atomic.Int32
	value atomic.Value
}

func (p *page) Call(expr string) (string, error) {
	var v any
	switch {
	case strings.Contains(expr, ".challenge()"):
		v = map[string]any{"c": ""}
	case strings.Contains(expr, ".snapshot("):
		v = map[string]any{"url": p.URL(), "title": "EPDS", "elements": []string{`[e1] input:text "Employee name"`, `[e2] button "Save"`}}
	case strings.Contains(expr, `.locate("e1")`):
		v = map[string]any{"x": 5.0, "y": 5.0, "kind": "input:text", "name": "Employee name", "fp": "a"}
	case strings.Contains(expr, `.locate("e2")`):
		v = map[string]any{"x": 5.0, "y": 50.0, "kind": "button", "name": "Save", "fp": "b"}
	case strings.Contains(expr, ".value("):
		s, _ := p.value.Load().(string)
		v = map[string]any{"value": s}
	}
	b, _ := json.Marshal(v)
	return string(b), nil
}
func (p *page) Navigate(u string) error { p.url.Store(u); return nil }
func (p *page) URL() string             { s, _ := p.url.Load().(string); return s }
func (p *page) Back() error             { return nil }
func (p *page) Tap(x, y float64, slow bool) error {
	if y > 40 {
		p.saves.Add(1)
	}
	return nil
}
func (p *page) ReplaceText(t string, slow bool) error { p.value.Store(t); return nil }
func (p *page) Key(string) error                      { return nil }
func (p *page) Scroll(bool) error                     { return nil }
func (p *page) Screenshot() ([]byte, error)           { return nil, nil }
func (p *page) FocusedSecret() string                 { return "normal" }

func rnd(n int) string {
	b := make([]byte, n)
	rand.Read(b)
	return base64.RawURLEncoding.EncodeToString(b)
}
func sha(s string) string {
	h := sha256.Sum256([]byte(s))
	return base64.RawURLEncoding.EncodeToString(h[:])
}

func signIn(t *testing.T, base string) string {
	res, _ := http.Post(base+"/register", "application/json", strings.NewReader(`{"redirect_uris":["https://claude.ai/api/mcp/auth_callback"]}`))
	var reg map[string]any
	json.NewDecoder(res.Body).Decode(&reg)
	client, _ := reg["client_id"].(string)
	verifier := rnd(40)
	q := url.Values{"response_type": {"code"}, "client_id": {client}, "redirect_uri": {agentrelay.Redirects[0]}, "code_challenge": {sha(verifier)}, "code_challenge_method": {"S256"}, "state": {"s"}}
	nr := &http.Client{CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	a, _ := nr.Get(base + "/authorize?" + q.Encode())
	body, _ := io.ReadAll(a.Body)
	for i := 0; a.StatusCode == 200 && i < 200; i++ {
		m := regexp.MustCompile(`url=(/authorize/wait\?t=[^"]+)`).FindStringSubmatch(string(body))
		if m == nil {
			break
		}
		time.Sleep(50 * time.Millisecond)
		a, _ = nr.Get(base + m[1])
		body, _ = io.ReadAll(a.Body)
	}
	loc, _ := url.Parse(a.Header.Get("Location"))
	code := loc.Query().Get("code")
	if code == "" {
		return ""
	}
	tr, _ := http.PostForm(base+"/token", url.Values{"grant_type": {"authorization_code"}, "code": {code}, "client_id": {client}, "redirect_uri": {agentrelay.Redirects[0]}, "code_verifier": {verifier}})
	var tok map[string]any
	json.NewDecoder(tr.Body).Decode(&tok)
	at, _ := tok["access_token"].(string)
	return at
}

func tool(t *testing.T, base, tok, name string, args map[string]any) (int, map[string]any) {
	b, _ := json.Marshal(map[string]any{"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": map[string]any{"name": name, "arguments": args}})
	q, _ := http.NewRequest("POST", base+"/mcp", bytes.NewReader(b))
	q.Header.Set("Authorization", "Bearer "+tok)
	res, err := http.DefaultClient.Do(q)
	if err != nil {
		t.Fatal(err)
	}
	if res.StatusCode != 200 {
		return res.StatusCode, nil
	}
	var m map[string]any
	json.NewDecoder(res.Body).Decode(&m)
	c := m["result"].(map[string]any)["content"].([]any)[0].(map[string]any)
	var out map[string]any
	json.Unmarshal([]byte(c["text"].(string)), &out)
	return 200, out
}

func TestLaptopAgentEndToEnd(t *testing.T) {
	r, err := agentrelay.New(agentrelay.Config{DataDir: t.TempDir()})
	if err != nil {
		t.Fatal(err)
	}
	srv := httptest.NewServer(r.Handler())
	defer srv.Close()
	r.SetPublic(srv.URL)
	r.OpenPairing(time.Minute)

	pg := &page{}
	pg.url.Store("https://epds.example.gov.in/")
	approved := 0
	dir := t.TempDir()
	l := &agentcore.Link{Base: srv.URL, Name: "laptop", RelayPub: r.PublicKeyPEM(), Store: &agentcore.Store{Path: filepath.Join(dir, "link.json")},
		Driver:  &agentcore.Driver{P: pg, Journal: filepath.Join(dir, "journal.json"), Resolve: func(string) ([]net.IP, error) { return []net.IP{net.ParseIP("8.8.8.8")}, nil }},
		Approve: func(text string) bool { approved++; return strings.Contains(text, "Code ") }}
	if !l.Pair() {
		t.Fatal("pair")
	}
	l.Start()
	defer l.Stop()
	for i := 0; i < 100 && !l.Online(); i++ {
		time.Sleep(50 * time.Millisecond)
	}
	if !l.Online() {
		t.Fatal("online")
	}
	// no "Claude जोड़ें" pressed: a sign-in gets no card at all
	if at := signIn(t, srv.URL); at != "" || approved != 0 {
		t.Fatal("no card without the owner's press")
	}
	l.OpenConnectWindow()
	time.Sleep(300 * time.Millisecond)
	at := signIn(t, srv.URL)
	if at == "" || approved != 1 {
		t.Fatal("connect", approved)
	}
	if _, out := tool(t, srv.URL, at, "type", map[string]any{"device": "laptop", "ref": "e1", "text": "सीता देवी"}); out["check"] != "verified" {
		t.Fatalf("type: %v", out)
	}
	if _, out := tool(t, srv.URL, at, "click", map[string]any{"device": "laptop", "ref": "e2"}); out["done"] == nil || pg.saves.Load() != 1 {
		t.Fatalf("save: %v", out)
	}
	tool(t, srv.URL, at, "open", map[string]any{"device": "laptop", "url": "https://web.dhan.co/"})
	if _, out := tool(t, srv.URL, at, "click", map[string]any{"device": "laptop", "ref": "e2"}); out["error"] == nil || pg.saves.Load() != 1 {
		t.Fatalf("money site must be read-only: %v", out)
	}
	l.DisconnectClaude()
	time.Sleep(500 * time.Millisecond)
	if c, _ := tool(t, srv.URL, at, "snapshot", map[string]any{"device": "laptop"}); c != 401 {
		t.Fatalf("after Claude हटाएँ: %d", c)
	}
}
