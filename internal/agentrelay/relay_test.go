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
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"testing"
	"time"
)

// fakeDevice speaks the device side of the protocol (what the Android / Windows agent does).
type fakeDevice struct {
	t            *testing.T
	base, name   string
	secret       string
	key          *ecdsa.PrivateKey
	relayPub     string
	sid          string
	k            []byte
	seqOut       int64
	seqIn        int64
	handle       func(tool string, args map[string]any) map[string]any
	forgeApprove bool
	helloReply   map[string]any
	stop         chan struct{}
	wg           sync.WaitGroup
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
	res, b := d.req("POST", "/agent/hello", map[string]string{"nonce": nonce, "ecdh": my, "sig": d.sign("devhello|" + nonce + "|" + my)})
	if res.StatusCode != 200 {
		return res.StatusCode
	}
	var mm map[string]any
	json.Unmarshal(b, &mm)
	d.helloReply = mm
	m := map[string]string{}
	for k, v := range mm {
		if s, ok := v.(string); ok {
			m[k] = s
		}
	}
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
	r        *Relay
	srv      *httptest.Server
	now      time.Time
	mu       sync.Mutex
	devs     map[string]*fakeDevice
	lastPage string
}

func (e *env) device(t *testing.T, name string) *fakeDevice {
	d := newDevice(t, e.srv.URL, name)
	e.mu.Lock()
	e.devs[name] = d
	e.mu.Unlock()
	return d
}

func newEnv(t *testing.T) *env {
	e := &env{now: time.Now(), devs: map[string]*fakeDevice{}}
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

// connect: the owner taps "Claude जोड़ें" on the tablet, then Claude's whole sign-in runs; returns the tokens.
func (e *env) connect(t *testing.T) (string, string, string) {
	e.openOn(t, "tablet")
	return e.signIn(t)
}

func (e *env) openOn(t *testing.T, name string) {
	e.mu.Lock()
	d := e.devs[name]
	e.mu.Unlock()
	if d == nil || d.send(map[string]any{"connectOpen": true}) != 204 {
		t.Fatal("connectOpen")
	}
}

// signIn runs Claude's sign-in: register, authorize (the page waits for the owner), token.
func (e *env) signIn(t *testing.T) (string, string, string) {
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
	body, _ := io.ReadAll(a.Body)
	for i := 0; a.StatusCode == 200 && i < 100; i++ { // the waiting page refreshes itself (meta refresh) every 2 s
		mm := regexp.MustCompile(`url=(/authorize/wait\?t=[^"]+)`).FindStringSubmatch(string(body))
		if mm == nil {
			break
		}
		time.Sleep(50 * time.Millisecond)
		a, _ = noRedirect().Get(e.srv.URL + mm[1])
		body, _ = io.ReadAll(a.Body)
	}
	e.lastPage = string(body)
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
	tab := e.device(t, "tablet")
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
	tab := e.device(t, "tablet")
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

// Review 28 Nov (5 models): every bug they found gets a permanent test.
func TestStrangerClaudeCannotPopACard(t *testing.T) {
	e := newEnv(t)
	e.r.OpenPairing(time.Minute)
	tab := e.device(t, "tablet")
	asked := 0
	tab.handle = func(tool string, _ map[string]any) map[string]any { return map[string]any{"done": tool} }
	if tab.pairAndHello() != 200 {
		t.Fatal("pair")
	}
	tab.loop()
	defer tab.close()
	_ = asked
	// no "Claude जोड़ें" on any device: the sign-in gets a page, the device is never asked
	if at, _, _ := e.signIn(t); at != "" || !strings.Contains(e.lastPage, "Claude जोड़ें") {
		t.Fatal("no window = no card, no token")
	}
	// with the window: the page shows the same code as the card, and the window is used up by one sign-in
	if at, _, _ := e.connect(t); at == "" {
		t.Fatal("owner opened Connect: should work")
	}
	if at, _, _ := e.signIn(t); at != "" {
		t.Fatal("one card per tap")
	}
}

func TestPairingCannotTakeOverANameAndHelloNeedsTheDeviceKey(t *testing.T) {
	e := newEnv(t)
	e.r.OpenPairing(time.Minute)
	tab := e.device(t, "tablet")
	if tab.pairAndHello() != 200 {
		t.Fatal("pair")
	}
	thief := newDevice(t, e.srv.URL, "tablet")
	if c := thief.pairAndHello(); c != 409 {
		t.Fatalf("a second 'tablet' must be refused: %d", c)
	}
	// the secret alone (e.g. from a backup) is not enough: hello must be signed by the device's own key
	copyCat := newDevice(t, e.srv.URL, "tablet")
	copyCat.secret, copyCat.relayPub = tab.secret, tab.relayPub
	if c := copyCat.hello(); c != 401 {
		t.Fatalf("hello with a stolen secret but another key: %d", c)
	}
	// the server owner can allow one re-pair by name
	os.WriteFile(filepath.Join(e.r.cfg.DataDir, "repair"), []byte("tablet\n"), 0o600)
	if c := thief.pairAndHello(); c != 200 {
		t.Fatalf("allowed re-pair: %d", c)
	}
	if c := newDevice(t, e.srv.URL, "tablet").pairAndHello(); c != 409 {
		t.Fatal("the repair permission is used once")
	}
}

func TestCodeReuseAndDisconnect(t *testing.T) {
	e := newEnv(t)
	e.r.OpenPairing(time.Minute)
	tab := e.device(t, "tablet")
	if tab.pairAndHello() != 200 {
		t.Fatal("pair")
	}
	tab.loop()
	defer tab.close()
	// a code used twice revokes what it gave
	e.openOn(t, "tablet")
	res, _ := http.Post(e.srv.URL+"/register", "application/json", strings.NewReader(`{"redirect_uris":["https://claude.ai/api/mcp/auth_callback"]}`))
	var reg map[string]any
	json.NewDecoder(res.Body).Decode(&reg)
	client := reg["client_id"].(string)
	verifier := rnd(32) + rnd(8)
	q := url.Values{"response_type": {"code"}, "client_id": {client}, "redirect_uri": {Redirects[0]}, "code_challenge": {sha(verifier)}, "code_challenge_method": {"S256"}, "state": {"s"}}
	a, _ := noRedirect().Get(e.srv.URL + "/authorize?" + q.Encode())
	body, _ := io.ReadAll(a.Body)
	var loc *url.URL
	for i := 0; i < 100; i++ {
		mm := regexp.MustCompile(`url=(/authorize/wait\?t=[^"]+)`).FindStringSubmatch(string(body))
		if mm == nil {
			break
		}
		time.Sleep(50 * time.Millisecond)
		a, _ = noRedirect().Get(e.srv.URL + mm[1])
		body, _ = io.ReadAll(a.Body)
		if a.StatusCode == 302 {
			loc, _ = url.Parse(a.Header.Get("Location"))
			break
		}
	}
	if loc == nil || !strings.Contains(string(e.r.cfg.Public), "http") {
		t.Fatal("no redirect")
	}
	code := loc.Query().Get("code")
	form := url.Values{"grant_type": {"authorization_code"}, "code": {code}, "client_id": {client}, "redirect_uri": {Redirects[0]}, "code_verifier": {verifier}}
	r1, _ := http.PostForm(e.srv.URL+"/token", form)
	var tok map[string]any
	json.NewDecoder(r1.Body).Decode(&tok)
	at, _ := tok["access_token"].(string)
	if c, _ := e.mcp(at, "tools/list", nil); c != 200 {
		t.Fatal("token works")
	}
	if r2, _ := http.PostForm(e.srv.URL+"/token", form); r2.StatusCode != 400 {
		t.Fatal("second use of a code")
	}
	if c, _ := e.mcp(at, "tools/list", nil); c != 401 {
		t.Fatal("a reused code revokes the tokens it gave")
	}
	// a wrong client never destroys someone's refresh token
	at, rt, client := e.connect(t)
	if w, _ := http.PostForm(e.srv.URL+"/token", url.Values{"grant_type": {"refresh_token"}, "refresh_token": {rt}, "client_id": {"c_other"}}); w.StatusCode != 400 {
		t.Fatal("wrong client")
	}
	if ok, _ := http.PostForm(e.srv.URL+"/token", url.Values{"grant_type": {"refresh_token"}, "refresh_token": {rt}, "client_id": {client}}); ok.StatusCode != 200 {
		t.Fatal("the real client's refresh still works")
	}
	// Disconnect: tokens die, and the signed epoch is given to every device at its next hello
	tab.send(map[string]any{"disconnectClaude": true, "epoch": 1795800000001, "sig": tab.sign("epoch|1795800000001")})
	if c, _ := e.mcp(at, "tools/list", nil); c != 401 {
		t.Fatal("disconnect kills tokens")
	}
	ph := e.device(t, "phone")
	if ph.pairAndHello() != 200 {
		t.Fatal("phone")
	}
	ev, _ := ph.helloReply["epoch"].(map[string]any)
	if ev == nil || ev["by"] != "tablet" || !verifySig(tab.pubPEM(), "epoch|1795800000001", fmt.Sprint(ev["sig"])) {
		t.Fatalf("epoch at hello: %v", ph.helloReply["epoch"])
	}
	if _, ok := ph.helloReply["peers"].(map[string]any)["tablet"]; !ok {
		t.Fatal("peers at hello")
	}
}

func TestBadFramesNeverFreezeTheRelay(t *testing.T) {
	e := newEnv(t)
	e.r.OpenPairing(time.Minute)
	tab := e.device(t, "tablet")
	if tab.pairAndHello() != 200 {
		t.Fatal("pair")
	}
	if c := tab.send(map[string]any{"anchor": "not an object"}); c != 400 {
		t.Fatalf("bad anchor: %d", c)
	}
	done := make(chan bool)
	go func() { e.r.DevicePubs(); done <- true }()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("the relay lock was left held")
	}
}

func TestForwardedForUsesTheFrontsAddress(t *testing.T) {
	q, _ := http.NewRequest("GET", "/", nil)
	q.RemoteAddr = "127.0.0.1:5555"
	q.Header.Set("X-Forwarded-For", "1.1.1.1, 9.9.9.9")
	if ip(q) != "9.9.9.9" {
		t.Fatal(ip(q))
	}
	q.RemoteAddr = "8.8.8.8:1"
	if ip(q) != "8.8.8.8" {
		t.Fatal("not behind the front: header ignored")
	}
}

func TestClaudeRegistrationSurvivesRestart(t *testing.T) {
	dir := t.TempDir()
	r1, _ := New(Config{DataDir: dir, Public: "https://x"})
	srv := httptest.NewServer(r1.Handler())
	res, _ := http.Post(srv.URL+"/register", "application/json", strings.NewReader(`{"redirect_uris":["https://claude.ai/api/mcp/auth_callback"]}`))
	var reg map[string]any
	json.NewDecoder(res.Body).Decode(&reg)
	srv.Close()
	r2, _ := New(Config{DataDir: dir, Public: "https://x"})
	if _, ok := r2.clients[reg["client_id"].(string)]; !ok {
		t.Fatal("client lost on restart")
	}
}
