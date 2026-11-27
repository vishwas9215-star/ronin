package agentcore

import (
	"bytes"
	"crypto/ecdh"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// PeerNames: the other devices whose keys this one learns (only while the owner has "Claude जोड़ें" open here).
var PeerNames = map[string]bool{"phone": true, "tablet": true, "laptop": true}

// Store keeps the link's small state (device key, name tag, peers, epoch) in one file. On Windows Seal / Open are
// DPAPI (the file only opens for this Windows user on this PC).
type Store struct {
	Path  string
	Seal  func([]byte) ([]byte, error)
	Open  func([]byte) ([]byte, error)
	mu    sync.Mutex
	state map[string]string
}

func (s *Store) load() {
	if s.state != nil {
		return
	}
	s.state = map[string]string{}
	b, err := os.ReadFile(s.Path)
	if err != nil {
		return
	}
	if s.Open != nil {
		if b, err = s.Open(b); err != nil {
			return
		}
	}
	json.Unmarshal(b, &s.state)
}

func (s *Store) Get(k string) string { s.mu.Lock(); defer s.mu.Unlock(); s.load(); return s.state[k] }

func (s *Store) Set(kv ...string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.load()
	for i := 0; i+1 < len(kv); i += 2 {
		if kv[i+1] == "" {
			delete(s.state, kv[i])
		} else {
			s.state[kv[i]] = kv[i+1]
		}
	}
	b, _ := json.Marshal(s.state)
	if s.Seal != nil {
		var err error
		if b, err = s.Seal(b); err != nil {
			return
		}
	}
	os.MkdirAll(filepath.Dir(s.Path), 0o700)
	tmp := s.Path + ".tmp"
	if os.WriteFile(tmp, b, 0o600) == nil {
		os.Rename(tmp, s.Path)
	}
}

// Link: this device's side of the relay link (the same protocol as the Android AgentLink).
type Link struct {
	Base, Name, RelayPub string
	Store                *Store
	Driver               *Driver
	Approve              func(text string) bool // the one-time card: true only if the owner held Allow
	Log                  func(string)
	HTTP                 *http.Client

	running      atomic.Bool
	online       atomic.Bool
	generation   atomic.Int64
	connectUntil atomic.Int64 // unix ms: "Claude जोड़ें" pressed here a moment ago
	mu           sync.Mutex   // sid, k, seq
	postMu       sync.Mutex   // one POST at a time
	sid          string
	k            []byte
	seqIn        int64
	seqOut       int64
	work         chan func()
	key          *ecdsa.PrivateKey
}

func (l *Link) log(m string) {
	if l.Log != nil {
		l.Log("link: " + m)
	}
}

func now() int64 { return time.Now().UnixMilli() }

// Online: linked to the relay right now.
func (l *Link) Online() bool { return l.online.Load() }

// ---------------- keys ----------------

func (l *Link) signingKey() *ecdsa.PrivateKey {
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.key != nil {
		return l.key
	}
	if der, err := base64.StdEncoding.DecodeString(l.Store.Get("key")); err == nil && len(der) > 0 {
		if k, err := x509.ParseECPrivateKey(der); err == nil {
			l.key = k
			return k
		}
	}
	k, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	der, _ := x509.MarshalECPrivateKey(k)
	l.Store.Set("key", base64.StdEncoding.EncodeToString(der))
	l.key = k
	return k
}

func (l *Link) PublicPEM() string {
	der, _ := x509.MarshalPKIXPublicKey(&l.signingKey().PublicKey)
	return string(pem.EncodeToMemory(&pem.Block{Type: "PUBLIC KEY", Bytes: der}))
}

func (l *Link) sign(text string) string {
	h := sha256.Sum256([]byte(text))
	s, _ := ecdsa.SignASN1(rand.Reader, l.signingKey(), h[:])
	return base64.StdEncoding.EncodeToString(s)
}

// Verify: an ECDSA P-256 / SHA-256 signature (base64 DER) by a PEM public key.
func Verify(pubPEM, text, sigB64 string) bool {
	blk, _ := pem.Decode([]byte(pubPEM))
	if blk == nil {
		return false
	}
	k, err := x509.ParsePKIXPublicKey(blk.Bytes)
	if err != nil {
		return false
	}
	pub, ok := k.(*ecdsa.PublicKey)
	sig, err := base64.StdEncoding.DecodeString(sigB64)
	if !ok || err != nil {
		return false
	}
	h := sha256.Sum256([]byte(text))
	return ecdsa.VerifyASN1(pub, h[:], sig)
}

func hmacB64(k []byte, s string) string {
	m := hmac.New(sha256.New, k)
	m.Write([]byte(s))
	return base64.StdEncoding.EncodeToString(m.Sum(nil))
}

func randHex(n int) string {
	b := make([]byte, n)
	rand.Read(b)
	return fmt.Sprintf("%x", b)
}

// the device's name tag at the relay. Not enough on its own: every hello is signed by the device key.
func (l *Link) secret() string {
	if s := l.Store.Get("deviceKey"); s != "" {
		return s
	}
	b := make([]byte, 32)
	rand.Read(b)
	s := base64.RawURLEncoding.EncodeToString(b)
	l.Store.Set("deviceKey", s)
	return s
}

// ---------------- HTTP ----------------

func (l *Link) http(method, path string, body any, timeout time.Duration, auth bool) (int, []byte, error) {
	var rd io.Reader
	if body != nil {
		b, _ := json.Marshal(body)
		rd = bytes.NewReader(b)
	}
	q, err := http.NewRequest(method, l.Base+path, rd)
	if err != nil {
		return 0, nil, err
	}
	if auth {
		q.Header.Set("Authorization", "Device "+l.secret())
	}
	if body != nil {
		q.Header.Set("Content-Type", "application/json")
	}
	c := l.HTTP
	if c == nil {
		c = &http.Client{}
	}
	cc := *c
	cc.Timeout = timeout
	res, err := cc.Do(q)
	if err != nil {
		return 0, nil, err
	}
	defer res.Body.Close()
	b, _ := io.ReadAll(io.LimitReader(res.Body, 8<<20))
	return res.StatusCode, b, nil
}

// Pair registers this device while the server's owner has opened pairing. It checks first that this is OUR relay.
func (l *Link) Pair() bool {
	c, b, err := l.http("GET", "/.well-known/ronin-relay.json", nil, 15*time.Second, false)
	var wk map[string]any
	if err != nil || c != 200 || json.Unmarshal(b, &wk) != nil || strings.TrimSpace(fmt.Sprint(wk["pub"])) != strings.TrimSpace(l.RelayPub) {
		l.log("pair: NOT our relay")
		return false
	}
	c, b, err = l.http("POST", "/agent/pair", map[string]string{"name": l.Name, "key": l.secret(), "pub": l.PublicPEM()}, 15*time.Second, true)
	if err != nil || c != 200 {
		l.log(fmt.Sprintf("pair refused (%d)", c))
		return false
	}
	var m map[string]any
	if json.Unmarshal(b, &m) != nil || strings.TrimSpace(fmt.Sprint(m["relay_pub"])) != strings.TrimSpace(l.RelayPub) {
		l.log("pair: NOT our relay")
		return false
	}
	if p, ok := m["peers"].(map[string]any); ok {
		for n, v := range p {
			if s, _ := v.(string); n != l.Name && PeerNames[n] && l.Store.Get("peer_"+n) == "" && s != "" {
				l.Store.Set("peer_"+n, s)
			}
		}
	}
	l.Store.Set("paired", "1")
	return true
}

func (l *Link) learnPeers(reply []byte) {
	if now() >= l.connectUntil.Load() {
		return
	}
	var m map[string]any
	if json.Unmarshal(reply, &m) != nil {
		return
	}
	p, _ := m["peers"].(map[string]any)
	for n, v := range p {
		if s, _ := v.(string); n != l.Name && PeerNames[n] && l.Store.Get("peer_"+n) == "" && s != "" {
			l.Store.Set("peer_"+n, s)
			l.log("learnt peer " + n)
		}
	}
}

func (l *Link) hello(gen int64) bool {
	eph, _ := ecdh.P256().GenerateKey(rand.Reader)
	nonce := randHex(16)
	my := base64.StdEncoding.EncodeToString(eph.PublicKey().Bytes())
	c, b, err := l.http("POST", "/agent/hello", map[string]string{"nonce": nonce, "ecdh": my, "sig": l.sign("devhello|" + nonce + "|" + my)}, 15*time.Second, true)
	if err != nil || c != 200 {
		l.log(fmt.Sprintf("hello refused (%d)", c))
		return false
	}
	var m map[string]any
	if json.Unmarshal(b, &m) != nil {
		return false
	}
	theirs, _ := m["ecdh"].(string)
	proof, _ := m["proof"].(string)
	if !Verify(l.RelayPub, strings.Join([]string{"relay", nonce, my, theirs}, "|"), proof) {
		l.log("FAKE relay: stopping")
		l.running.Store(false)
		return false
	}
	raw, err := base64.StdEncoding.DecodeString(theirs)
	if err != nil {
		return false
	}
	rp, err := ecdh.P256().NewPublicKey(raw)
	if err != nil {
		return false
	}
	shared, err := eph.ECDH(rp)
	if err != nil {
		return false
	}
	k := sha256.Sum256(append(shared, []byte(nonce)...))
	l.learnPeers(b)
	if ev, ok := m["epoch"].(map[string]any); ok {
		l.applyEpoch(fmt.Sprint(ev["by"]), int64(numAny(ev["epoch"])), fmt.Sprint(ev["sig"]))
	}
	l.mu.Lock()
	if gen != l.generation.Load() {
		l.mu.Unlock()
		return false
	}
	l.sid, _ = m["session"].(string)
	l.k, l.seqIn, l.seqOut = k[:], 0, 0
	l.mu.Unlock()
	if _, ok := l.send(map[string]any{"hello": map[string]any{"kind": l.Name, "app": "Ronin", "foreground": true, "rendering": true, "key": "DPAPI software key"}}); !ok {
		return false
	}
	if p := l.Store.Get("pendingDisconnect"); p != "" {
		var msg map[string]any
		json.Unmarshal([]byte(p), &msg)
		if _, ok := l.send(msg); !ok {
			return false // kept until really delivered
		}
		l.Store.Set("pendingDisconnect", "")
	}
	if now() < l.connectUntil.Load() {
		if r, ok := l.send(map[string]any{"connectOpen": true}); ok {
			l.learnPeers(r)
		}
	}
	l.online.Store(true)
	l.log("online")
	return true
}

func numAny(v any) float64 {
	switch x := v.(type) {
	case float64:
		return x
	case json.Number:
		f, _ := x.Float64()
		return f
	}
	return 0
}

// send: one POST at a time, in sequence order; a failed POST is sent again unchanged ("done already" if it arrived).
func (l *Link) send(m map[string]any) ([]byte, bool) {
	l.postMu.Lock()
	defer l.postMu.Unlock()
	f, _ := json.Marshal(m)
	l.mu.Lock()
	l.seqOut++
	sid := l.sid
	body := map[string]any{"f": string(f), "seq": l.seqOut, "mac": hmacB64(l.k, fmt.Sprintf("%d|%s", l.seqOut, f))}
	l.mu.Unlock()
	for attempt := 0; attempt < 2; attempt++ {
		c, b, err := l.http("POST", "/agent/out?s="+sid, body, 15*time.Second, true)
		if err == nil && c >= 200 && c < 300 {
			return b, true
		}
		if err == nil && (c == 400 || c == 401) {
			break
		}
	}
	l.online.Store(false)
	return nil, false
}

// ---------------- the loop ----------------

// Start links to the relay and keeps the link up until Stop.
func (l *Link) Start() {
	if l.running.Swap(true) {
		return
	}
	gen := l.generation.Add(1)
	if l.work == nil {
		l.work = make(chan func(), 64)
		go func() {
			for f := range l.work {
				f()
			}
		}()
	}
	go func() {
		backoff := time.Second
		for l.running.Load() && gen == l.generation.Load() {
			if !l.online.Load() {
				if !l.hello(gen) {
					time.Sleep(backoff)
					if backoff < 30*time.Second {
						backoff *= 2
					}
					continue
				}
			}
			backoff = time.Second
			l.mu.Lock()
			sid := l.sid
			l.mu.Unlock()
			c, b, err := l.http("GET", "/agent/next?s="+sid, nil, 35*time.Second, true)
			if !l.running.Load() || gen != l.generation.Load() {
				break // STOP while waiting: nothing that arrived runs
			}
			switch {
			case err != nil:
				l.online.Store(false)
				time.Sleep(backoff)
			case c == 200:
				l.handle(b)
			case c == 204:
			default:
				l.online.Store(false)
			}
		}
	}()
}

// Stop: never waits on the network.
func (l *Link) Stop() { l.running.Store(false); l.generation.Add(1); l.online.Store(false) }

// OpenConnectWindow: the owner pressed "Claude जोड़ें": for 3 minutes ONE Connect card may show here.
func (l *Link) OpenConnectWindow() {
	l.connectUntil.Store(now() + 180_000)
	if l.online.Load() {
		go func() {
			if r, ok := l.send(map[string]any{"connectOpen": true}); ok {
				l.learnPeers(r)
			}
		}()
	}
}

func (l *Link) handle(raw []byte) {
	var fr struct {
		F   string `json:"f"`
		Seq int64  `json:"seq"`
		Mac string `json:"mac"`
	}
	if json.Unmarshal(raw, &fr) != nil {
		return
	}
	var c map[string]any
	l.mu.Lock()
	if fr.Seq <= l.seqIn || !hmac.Equal([]byte(fr.Mac), []byte(hmacB64(l.k, fmt.Sprintf("%d|%s", fr.Seq, fr.F)))) {
		l.mu.Unlock()
		l.log("bad frame dropped")
		return
	}
	if json.Unmarshal([]byte(fr.F), &c) != nil || str(c, "id") == "" || str(c, "tool") == "" {
		l.mu.Unlock()
		l.log("bad command dropped")
		return
	}
	l.seqIn = fr.Seq // only a well-formed, authentic frame uses up its number
	l.mu.Unlock()
	id, tool := str(c, "id"), str(c, "tool")
	args, _ := c["args"].(map[string]any)
	answer := func(r map[string]any) { l.send(map[string]any{"id": id, "result": r}) }
	switch tool {
	case "__stop": // the owner's STOP and a new epoch never wait behind a running command
		l.Driver.OwnerStop()
		answer(map[string]any{"ok": true})
	case "__epoch":
		ok := l.applyEpoch(str(args, "by"), int64(numAny(args["epoch"])), str(args, "sig"))
		r := map[string]any{"ok": ok}
		if !ok {
			r["error"] = "unknown or wrong signer: press Claude जोड़ें on this device once"
		}
		answer(r)
	case "__approve":
		l.work <- func() { answer(l.approveCard(args)) }
	default:
		grant, _ := c["grant"].(map[string]any)
		l.work <- func() {
			var r map[string]any
			if !l.running.Load() {
				r = e("the owner stopped Claude on this device")
			} else if why := l.GrantProblem(grant); why != "" {
				r = e(why)
			} else {
				r = l.Driver.Run(id, tool, args)
			}
			answer(r)
		}
	}
}

func (l *Link) epoch() int64 {
	var e int64
	fmt.Sscan(l.Store.Get("epoch"), &e)
	return e
}

// approveCard: never shown unless the owner opened "Claude जोड़ें" here a moment ago (one card per press).
func (l *Link) approveCard(a map[string]any) map[string]any {
	if l.Driver.Stopped || now() >= l.connectUntil.Load() {
		l.log(`Connect card refused: "Claude जोड़ें" was not open here`)
		return map[string]any{"ok": false}
	}
	l.connectUntil.Store(0)
	text := "Claude को आपके Ronin से जोड़ें? Code " + str(a, "code") + " (Claude के page पर भी यही code होना चाहिए). Devices: " + str(a, "scope")
	if l.Approve == nil || !l.Approve(text) {
		return map[string]any{"ok": false}
	}
	ep, issued := l.epoch(), now()
	sig := l.sign(strings.Join([]string{"approve", str(a, "nonce"), str(a, "client"), str(a, "redirect"), str(a, "challenge"), str(a, "scope"), str(a, "code"), fmt.Sprint(ep), fmt.Sprint(issued)}, "|"))
	return map[string]any{"ok": true, "epoch": ep, "issued": issued, "sig": sig}
}

func (l *Link) applyEpoch(by string, ep int64, sig string) bool {
	pub := l.Store.Get("peer_" + by)
	if by == l.Name {
		pub = l.PublicPEM()
	}
	if pub == "" {
		// a "Disconnect Claude" from a device this one does not know yet: obeyed as a revocation anyway (the worst
		// a hostile relay can do with it is make the owner press Connect again); reported as unknown
		if ep > l.epoch() {
			l.Store.Set("epoch", fmt.Sprint(ep))
			l.log(fmt.Sprintf("epoch %d from unknown %s: obeyed (revocation only)", ep, by))
		}
		return false
	}
	if !Verify(pub, fmt.Sprintf("epoch|%d", ep), sig) {
		return false
	}
	if ep > l.epoch() {
		l.Store.Set("epoch", fmt.Sprint(ep))
		l.log(fmt.Sprintf("epoch %d from %s", ep, by))
	}
	return true
}

// GrantProblem: the owner's grant must be signed by one of HIS devices, name this device, and not be revoked.
func (l *Link) GrantProblem(g map[string]any) string {
	if g == nil {
		return "no owner grant"
	}
	dev := str(g, "dev")
	pub := l.Store.Get("peer_" + dev)
	if dev == l.Name {
		pub = l.PublicPEM()
	}
	if pub == "" {
		return "grant from an unknown device"
	}
	in := false
	for _, n := range strings.Split(str(g, "scope"), ", ") {
		if n == l.Name {
			in = true
		}
	}
	if !in {
		return "this device is not in the owner's grant"
	}
	ep := int64(numAny(g["epoch"]))
	if ep < l.epoch() {
		return "Claude was disconnected by the owner: connect again"
	}
	text := strings.Join([]string{"approve", str(g, "nonce"), str(g, "client"), str(g, "redirect"), str(g, "challenge"), str(g, "scope"), str(g, "code"), fmt.Sprint(ep), fmt.Sprint(int64(numAny(g["issued"])))}, "|")
	if !Verify(pub, text, str(g, "sig")) {
		return "the grant's signature is wrong"
	}
	return ""
}

// DisconnectClaude: "Claude हटाएँ" here: a new epoch at once (no network needed), told to the others, signed.
func (l *Link) DisconnectClaude() {
	ep := l.epoch() + 1
	if n := now(); n > ep {
		ep = n
	}
	l.Store.Set("epoch", fmt.Sprint(ep))
	msg := map[string]any{"disconnectClaude": true, "epoch": ep, "sig": l.sign(fmt.Sprintf("epoch|%d", ep))}
	b, _ := json.Marshal(msg)
	l.Store.Set("pendingDisconnect", string(b)) // sent again at the next hello if this fails
	go func() {
		if l.online.Load() {
			if _, ok := l.send(msg); ok {
				l.Store.Set("pendingDisconnect", "")
			}
		}
	}()
}
