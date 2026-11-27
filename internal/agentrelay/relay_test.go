package agentrelay

import (
	"bytes"
	"crypto/ecdh"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync"
	"testing"
	"time"
)

// fakeDevice speaks the device side of the protocol (what the Android / Windows agent does).
type fakeDevice struct {
	t           *testing.T
	base, name  string
	secret      string
	key         *ecdsa.PrivateKey
	relayPub    string
	sid         string
	k           []byte
	seqOut      int64
	seqIn       int64
	handle      func(tool string, args map[string]any) map[string]any
	forgeApprove bool
	stop        chan struct{}
	wg          sync.WaitGroup
}

func newDevice(t *testing.T, base, name string) *fakeDevice {
	k, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	return &fakeDevice{t: t, base: base, name: name, secret: rnd(32), key: k, stop: make(chan struct{})}
}

func (d *fakeDevice) pubPEM() string {
	der, _ := x509.MarshalPKIXPublicKey(&d.key.PublicKey)
	return string(pem.EncodeToMemory(&pem.Block{Type: "PUBLIC KEY", Bytes: der}))
}

func (d *fakeDevice) req(method, path string, body any) (*http.Response, []byte) {
	var rd io.Reader
	if body != nil {
		b, _ := json.Marshal(body)
		rd = bytes.NewReader(b)
	}
	q, _ := http.NewRequest(method, d.base+path, rd)
	q.Header.Set("Authorization", "Device "+d.secret)
	res, err := http.DefaultClient.Do(q)
	if err != nil {
		return &http.Response{StatusCode: 0}, nil
	}
	b, _ := io.ReadAll(res.Body)
	res.Body.Close()
	return res, b
}

func (d *fakeDevice) pairAndHello() int {
	res, b := d.req("POST", "/agent/pair", map[string]string{"name": d.name, "key": d.secret, "pub": d.pubPEM()})
	if res.StatusCode != 200 {
		return res.StatusCode
	}
	var m map[string]any
	json.Unmarshal(b, &m)
	d.relayPub, _ = m["relay_pub"].(string)
	return d.hello()
}

func (d *fakeDevice) hello() int {
	eph, _ := ecdh.P256().GenerateKey(rand.Reader)
	nonce := rnd(16)
	my := base64.StdEncoding.EncodeToString(eph.PublicKey().Bytes())
	res, b := d.req("POST", "/agent/hello", map[string]string{"nonce": nonce, "ecdh": my})
	if res.StatusCode != 200 {
		return res.StatusCode
	}
	var m map[string]string
	json.Unmarshal(b, &m)
	if !verifySig(d.relayPub, strings.Join([]string{"relay", nonce, my, m["ecdh"]}, "|"), m["proof"]) {
		return -1 // fake relay
	}
	raw, _ := base64.StdEncoding.DecodeString(m["ecdh"])
	rp, _ := ecdh.P256().NewPublicKey(raw)
	shared, _ := eph.ECDH(rp)
	k := sha256.Sum256(append(shared, []byte(nonce)...))
	d.k, d.sid, d.seqIn, d.seqOut = k[:], m["session"], 0, 0
	d.send(map[string]any{"hello": map[string]any{"kind": d.name, "foreground": true, "rendering": true}})
	return 200
}

func (d *fakeDevice) send(m map[string]any) int {
	f, _ := json.Marshal(m)
	d.seqOut++
	res, _ := d.req("POST", "/agent/out?s="+d.sid, map[string]any{"f": string(f), "seq": d.seqOut, "mac": mac(d.k, fmt.Sprintf("%d|%s", d.seqOut, f))})
	return res.StatusCode
}

func (d *fakeDevice) sign(text string) string {
	h := sha256.Sum256([]byte(text))
	s, _ := ecdsa.SignASN1(rand.Reader, d.key, h[:])
	return base64.StdEncoding.EncodeToString(s)
}

func (d *fakeDevice) loop() {
	d.wg.Add(1)
	go func() {
		defer d.wg.Done()
		for {
			select {
			case <-d.stop:
				return
			default:
			}
			res, b := d.req("GET", "/agent/next?s="+d.sid, nil)
			if res.StatusCode != 200 {
				time.Sleep(20 * time.Millisecond)
				continue
			}
			var fr struct {
				F   string `json:"f"`
				Seq int64  `json:"seq"`
				Mac string `json:"mac"`
			}
			json.Unmarshal(b, &fr)
			if fr.Seq <= d.seqIn || fr.Mac != mac(d.k, fmt.Sprintf("%d|%s", fr.Seq, fr.F)) {
				continue // injected / replayed frame
			}
			d.seqIn = fr.Seq
			var c map[string]any
			json.Unmarshal([]byte(fr.F), &c)
			tool, _ := c["tool"].(string)
			args, _ := c["args"].(map[string]any)
			var out map[string]any
			if tool == "__approve" {
				text := strings.Join([]string{"approve", fmt.Sprint(args["nonce"]), fmt.Sprint(args["client"]), fmt.Sprint(args["redirect"]), fmt.Sprint(args["challenge"]), fmt.Sprint(args["scope"]), fmt.Sprint(args["code"]), "0", "1795800000000"}, "|")
				sig := d.sign(text)
				if d.forgeApprove {
					sig = base64.StdEncoding.EncodeToString([]byte("forged"))
				}
				out = map[string]any{"ok": true, "epoch": 0, "issued": 1795800000000, "sig": sig}
			} else if d.handle != nil {
				out = d.handle(tool, args)
			} else {
				out = map[string]any{"done": tool}
			}
			d.send(map[string]any{"id": c["id"], "result": out})
		}
	}()
}

func (d *fakeDevice) close() { close(d.stop); d.wg.Wait() }

type env struct {
	r   *Relay
	srv *httptest.Server
	now time.Time
	mu  sync.Mutex
}

func newEnv(t *testing.T) *env {
	e := &env{now: time.Now()}
	r, err := New(Config{DataDir: t.TempDir(), Now: func() time.Time { e.mu.Lock(); defer e.mu.Unlock(); return e.now }})
	if err != nil {
		t.Fatal(err)
	}
	e.r = r
	e.srv = httptest.NewServer(r.Handler())
	r.cfg.Public = e.srv.URL
	t.Cleanup(e.srv.Close)
	return e
}

func (e *env) skip(d time.Duration) { e.mu.Lock(); e.now = e.now.Add(d); e.mu.Unlock() }

func noRedirect() *http.Client {
	return &http.Client{CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
}

// connect runs Claude's whole sign-in and returns the access + refresh tokens.
func (e *env) connect(t *testing.T) (string, string, string) {
	res, _ := http.Post(e.srv.URL+"/register", "application/json", strings.NewReader(`{"redirect_uris":["https://claude.ai/api/mcp/auth_callback","https://claude.ai/other"]}`))
	var reg map[string]any
	json.NewDecoder(res.Body).Decode(&reg)
	client := reg["client_id"].(string)
	if n := len(reg["redirect_uris"].([]any)); n != 1 {
		t.Fatalf("only the exact callback: %v", reg)
	}
	verifier := rnd(32)
	q := url.Values{"response_type": {"code"}, "client_id": {client}, "redirect_uri": {Redirects[0]}, "code_challenge": {sha(verifier)}, "code_challenge_method": {"S256"}, "state": {"s"}, "resource": {e.srv.URL + "/mcp"}}
	a, err := noRedirect().Get(e.srv.URL + "/authorize?" + q.Encode())
	if err != nil {
		t.Fatal(err)
	}
	loc, _ := url.Parse(a.Header.Get("Location"))
	code := loc.Query().Get("code")
	if code == "" {
		return "", "", client
	}
	tr, _ := http.PostForm(e.srv.URL+"/token", url.Values{"grant_type": {"authorization_code"}, "code": {code}, "client_id": {client}, "redirect_uri": {Redirects[0]}, "code_verifier": {verifier}, "resource": {e.srv.URL + "/mcp"}})
	var tok map[string]any
	json.NewDecoder(tr.Body).Decode(&tok)
	at, _ := tok["access_token"].(string)
	rt, _ := tok["refresh_token"].(string)
	return at, rt, client
}

func (e *env) mcp(tok, method string, params any) (int, map[string]any) {
	b, _ := json.Marshal(map[string]any{"jsonrpc": "2.0", "id": 1, "method": method, "params": params})
	q, _ := http.NewRequest("POST", e.srv.URL+"/mcp", bytes.NewReader(b))
	q.Header.Set("Authorization", "Bearer "+tok)
	res, err := http.DefaultClient.Do(q)
	if err != nil {
		return 0, nil
	}
	var m map[string]any
	json.NewDecoder(res.Body).Decode(&m)
	return res.StatusCode, m
}

func toolText(m map[string]any) map[string]any {
	c := m["result"].(map[string]any)["content"].([]any)[0].(map[string]any)
	var out map[string]any
	json.Unmarshal([]byte(c["text"].(string)), &out)
	return out
}

func TestSignInToolsAndSafety(t *testing.T) {
	e := newEnv(t)
	if c, _ := e.mcp("", "tools/list", nil); c != 401 {
		t.Fatal("no token must be 401")
	}
	// a device cannot pair while pairing is closed
	tab := newDevice(t, e.srv.URL, "tablet")
	if c := tab.pairAndHello(); c != 403 {
		t.Fatalf("pairing closed: %d", c)
	}
	e.r.OpenPairing(10 * time.Minute)
	if c := tab.pairAndHello(); c != 200 {
		t.Fatalf("pair+hello %d", c)
	}
	tab.loop()
	defer tab.close()
	at, rt, client := e.connect(t)
	if at == "" {
		t.Fatal("owner's signed Allow should connect Claude")
	}
	c, init := e.mcp(at, "initialize", map[string]any{})
	if c != 200 || !strings.Contains(fmt.Sprint(init), "Never type passwords") {
		t.Fatal("initialize")
	}
	_, list := e.mcp(at, "tools/list", nil)
	if n := len(list["result"].(map[string]any)["tools"].([]any)); n != len(tools) {
		t.Fatal("tools", n)
	}
	_, out := e.mcp(at, "tools/call", map[string]any{"name": "snapshot", "arguments": map[string]any{"device": "tablet"}})
	if toolText(out)["done"] != "snapshot" {
		t.Fatalf("round trip: %v", out)
	}
	_, out = e.mcp(at, "tools/call", map[string]any{"name": "__approve", "arguments": map[string]any{"device": "tablet"}})
	if toolText(out)["error"] == nil {
		t.Fatal("internal tools are not Claude's")
	}
	_, out = e.mcp(at, "tools/call", map[string]any{"name": "devices", "arguments": map[string]any{}})
	if !strings.Contains(fmt.Sprint(out), "tablet") {
		t.Fatal("devices", out)
	}
	// refresh: rotation, a retry within 2 minutes gets the same pair, theft kills the family
	r1, _ := http.PostForm(e.srv.URL+"/token", url.Values{"grant_type": {"refresh_token"}, "refresh_token": {rt}, "client_id": {client}})
	var p1 map[string]any
	json.NewDecoder(r1.Body).Decode(&p1)
	r2, _ := http.PostForm(e.srv.URL+"/token", url.Values{"grant_type": {"refresh_token"}, "refresh_token": {rt}, "client_id": {client}})
	var p2 map[string]any
	json.NewDecoder(r2.Body).Decode(&p2)
	if p1["access_token"] == nil || p1["access_token"] != p2["access_token"] {
		t.Fatal("retry within grace should give the same pair")
	}
	if c, _ := e.mcp(p1["access_token"].(string), "tools/list", nil); c != 200 {
		t.Fatal("new token works")
	}
	r3, _ := http.PostForm(e.srv.URL+"/token", url.Values{"grant_type": {"refresh_token"}, "refresh_token": {rt}, "client_id": {client}})
	if r3.StatusCode != 400 {
		t.Fatal("old refresh after the new pair was used = theft")
	}
	if c, _ := e.mcp(p1["access_token"].(string), "tools/list", nil); c != 401 {
		t.Fatal("whole family revoked")
	}
	// a stranger device and an injected frame
	st := newDevice(t, e.srv.URL, "phone")
	if res, _ := st.req("POST", "/agent/hello", map[string]string{"nonce": rnd(16), "ecdh": "x"}); res.StatusCode != 401 {
		t.Fatal("unpaired device")
	}
	if c := tab.send(map[string]any{"hello": map[string]any{}}); c != 204 {
		t.Fatal("good frame")
	}
	tab.seqOut-- // replay the same sequence number
	if c := tab.send(map[string]any{"hello": map[string]any{}}); c != 400 {
		t.Fatal("replayed frame must be refused")
	}
	tab.seqOut++
}

func TestForgedApprovalIsRefused(t *testing.T) {
	e := newEnv(t)
	e.r.OpenPairing(time.Minute)
	tab := newDevice(t, e.srv.URL, "tablet")
	tab.forgeApprove = true
	if c := tab.pairAndHello(); c != 200 {
		t.Fatal(c)
	}
	tab.loop()
	defer tab.close()
	if at, _, _ := e.connect(t); at != "" {
		t.Fatal("an Allow without the device's real signature must not connect Claude")
	}
}

func TestMetadataAndLimits(t *testing.T) {
	e := newEnv(t)
	res, _ := http.Get(e.srv.URL + "/.well-known/oauth-protected-resource")
	var m map[string]any
	json.NewDecoder(res.Body).Decode(&m)
	if m["resource"] != e.srv.URL+"/mcp" {
		t.Fatal(m)
	}
	q, _ := http.NewRequest("POST", e.srv.URL+"/mcp", strings.NewReader("{}"))
	r, _ := http.DefaultClient.Do(q)
	if !strings.Contains(r.Header.Get("WWW-Authenticate"), "oauth-protected-resource") {
		t.Fatal("401 must point to the metadata")
	}
	n429 := 0
	for i := 0; i < 7; i++ {
		r, _ := http.Post(e.srv.URL+"/register", "application/json", strings.NewReader("{}"))
		if r.StatusCode == 429 {
			n429++
		}
	}
	if n429 == 0 {
		t.Fatal("registration flood")
	}
}

func TestStateSurvivesRestart(t *testing.T) {
	dir := t.TempDir()
	r1, _ := New(Config{DataDir: dir})
	pub := r1.PublicKeyPEM()
	r1.OpenPairing(time.Minute)
	srv := httptest.NewServer(r1.Handler())
	d := newDevice(t, srv.URL, "phone")
	if d.pairAndHello() != 200 {
		t.Fatal("pair")
	}
	srv.Close()
	r2, _ := New(Config{DataDir: dir})
	if r2.PublicKeyPEM() != pub || r2.DevicePubs()["phone"] == "" {
		t.Fatal("relay key and paired devices must survive a restart")
	}
}
