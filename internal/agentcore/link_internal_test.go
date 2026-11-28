package agentcore

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/pem"
	"fmt"
	"path/filepath"
	"testing"
)

// A "Disconnect Claude" from a device this one does not know yet is still obeyed (as a revocation only).
func TestUnknownSignerDisconnectIsObeyed(t *testing.T) {
	l := &Link{Name: "laptop", Store: &Store{Path: filepath.Join(t.TempDir(), "s.json")}}
	if l.applyEpoch("phone", 1795800000001, "AAAA") {
		t.Fatal("reported as unknown")
	}
	if l.epoch() != 1795800000001 {
		t.Fatal("old grants must die", l.epoch())
	}
	if l.GrantProblem(map[string]any{"dev": "laptop", "scope": "laptop", "epoch": 0.0}) == "" {
		t.Fatal("an older grant is refused")
	}
}

// A formatted phone was allowed back on the tablet: the laptop takes the new key only from a known signer.
func TestHandoverFromAKnownDeviceOnly(t *testing.T) {
	l := &Link{Name: "laptop", Store: &Store{Path: filepath.Join(t.TempDir(), "s.json")}}
	tab, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	pemOf := func(k *ecdsa.PrivateKey) string {
		der, _ := x509.MarshalPKIXPublicKey(&k.PublicKey)
		return string(pem.EncodeToMemory(&pem.Block{Type: "PUBLIC KEY", Bytes: der}))
	}
	oldK, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	newK, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	l.Store.Set("peer_tablet", pemOf(tab))
	l.Store.Set("peer_phone", pemOf(oldK))
	mk := func(signer *ecdsa.PrivateKey, by string, ts int64) map[string]any {
		n, o := shaHex(pemOf(newK)), shaHex(pemOf(oldK))
		h := sha256.Sum256([]byte(fmt.Sprintf("handover|phone|%s|%s|123456|%d", n, o, ts)))
		s, _ := ecdsa.SignASN1(rand.Reader, signer, h[:])
		return map[string]any{"name": "phone", "by": by, "new": n, "old": o, "code": "123456", "ts": float64(ts), "sig": base64.StdEncoding.EncodeToString(s), "new_pub": pemOf(newK), "old_pub": pemOf(oldK)}
	}
	if l.ApplyHandover(mk(newK, "phone", 1000)) {
		t.Fatal("a name can not hand itself over")
	}
	if l.ApplyHandover(mk(oldK, "tablet", 1000)) {
		t.Fatal("signed by another key than the tablet's")
	}
	if !l.ApplyHandover(mk(tab, "tablet", 1000)) || l.Store.Get("peer_phone") != pemOf(newK) {
		t.Fatal("good handover")
	}
	if l.ApplyHandover(mk(tab, "tablet", 999)) {
		t.Fatal("older one again")
	}
}

// tabs: the laptop has only t1; a call for another tab must never land on it
func TestLaptopRefusesOtherTabs(t *testing.T) {
	d := &Driver{}
	if r := d.Run("x1", "snapshot", map[string]any{"tab": "t2"}); r["error"] == nil {
		t.Fatal("tab t2 accepted")
	}
	if r := d.Run("x2", "open", map[string]any{"url": "https://example.com/", "new_tab": true}); r["error"] == nil {
		t.Fatal("new_tab accepted")
	}
}
