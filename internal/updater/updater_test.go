package updater

import (
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func fileFor(data []byte) File {
	s := sha256.Sum256(data)
	return File{Name: "Ronin.exe", URL: "https://example.com/Ronin.exe", SHA256: hex.EncodeToString(s[:]), Size: int64(len(data))}
}

func TestManifestSignature(t *testing.T) {
	pub, priv, _ := ed25519.GenerateKey(rand.Reader)
	m := Manifest{App: "ronin", Version: "3.0.1", Files: []File{fileFor([]byte("hello"))}}
	data, _ := json.Marshal(m)
	sig := SignManifest(data, priv)

	got, err := VerifyManifest(data, sig, pub)
	if err != nil || got.Version != "3.0.1" {
		t.Fatalf("good manifest rejected: %v", err)
	}
	tampered := append([]byte{}, data...)
	tampered[len(tampered)-3] ^= 1
	if _, err := VerifyManifest(tampered, sig, pub); !errors.Is(err, ErrBadSignature) {
		t.Fatalf("tampered manifest accepted: %v", err)
	}
	otherPub, _, _ := ed25519.GenerateKey(rand.Reader)
	if _, err := VerifyManifest(data, sig, otherPub); !errors.Is(err, ErrBadSignature) {
		t.Fatal("wrong key accepted")
	}
	if _, err := VerifyManifest(data, "!!notbase64", pub); !errors.Is(err, ErrBadSignature) {
		t.Fatal("garbage signature accepted")
	}

	bad := Manifest{App: "ronin", Version: "3.0.1", Files: []File{{Name: `..\evil.exe`, URL: "https://x", SHA256: hex.EncodeToString(make([]byte, 32)), Size: 1}}}
	bd, _ := json.Marshal(bad)
	if _, err := VerifyManifest(bd, SignManifest(bd, priv), pub); !errors.Is(err, ErrBadManifest) {
		t.Fatal("path trick in file name accepted")
	}
	insecure := Manifest{App: "ronin", Version: "3.0.1", Files: []File{{Name: "a.exe", URL: "http://x", SHA256: hex.EncodeToString(make([]byte, 32)), Size: 1}}}
	idata, _ := json.Marshal(insecure)
	if _, err := VerifyManifest(idata, SignManifest(idata, priv), pub); !errors.Is(err, ErrBadManifest) {
		t.Fatal("http download accepted")
	}
}

func TestNewer(t *testing.T) {
	cases := []struct {
		a, b string
		want bool
	}{
		{"3.0.1", "3.0.0", true}, {"3.1", "3.0.9", true}, {"3.0.0", "3.0", false},
		{"v3.2.0", "3.1.9", true}, {"2.9", "3.0", false}, {"abc", "1.0", false}, {"3.0.0", "", false},
		{"10.0", "9.9", true},
	}
	for _, c := range cases {
		if got := Newer(c.a, c.b); got != c.want {
			t.Errorf("Newer(%q,%q)=%v want %v", c.a, c.b, got, c.want)
		}
	}
}

func TestInstallAndRollback(t *testing.T) {
	dir := t.TempDir()
	dst := filepath.Join(dir, "Ronin.exe")
	os.WriteFile(dst, []byte("old version"), 0o755)

	newData := []byte("new version")
	if err := Install(dst, newData, fileFor(newData), func(p string) error { return nil }); err != nil {
		t.Fatalf("install failed: %v", err)
	}
	if b, _ := os.ReadFile(dst); string(b) != "new version" {
		t.Fatalf("new file not in place: %q", b)
	}
	if b, _ := os.ReadFile(dst + ".old"); string(b) != "old version" {
		t.Fatalf("old copy missing: %q", b)
	}
	if err := Rollback(dst); err != nil {
		t.Fatal(err)
	}
	if b, _ := os.ReadFile(dst); string(b) != "old version" {
		t.Fatalf("rollback did not restore: %q", b)
	}
}

func TestInstallKeepsOldOnFailure(t *testing.T) {
	dir := t.TempDir()
	dst := filepath.Join(dir, "Ronin.exe")
	os.WriteFile(dst, []byte("old version"), 0o755)
	newData := []byte("new version")

	// wrong checksum
	f := fileFor(newData)
	f.SHA256 = hex.EncodeToString(make([]byte, 32))
	if err := Install(dst, newData, f, nil); !errors.Is(err, ErrBadChecksum) {
		t.Fatalf("bad checksum accepted: %v", err)
	}
	// self test fails
	if err := Install(dst, newData, fileFor(newData), func(string) error { return errors.New("crash") }); err == nil {
		t.Fatal("failed self test accepted")
	}
	if b, _ := os.ReadFile(dst); string(b) != "old version" {
		t.Fatalf("old version lost: %q", b)
	}
	if _, err := os.Stat(dst + ".new"); err == nil {
		t.Fatal("left-over .new file")
	}
}

func TestInstallFreshAndNoRollback(t *testing.T) {
	dir := t.TempDir()
	dst := filepath.Join(dir, "tool.exe")
	d := []byte("first")
	if err := Install(dst, d, fileFor(d), nil); err != nil {
		t.Fatal(err)
	}
	if err := Rollback(dst); err == nil {
		t.Fatal("rollback without old version should fail")
	}
}

func TestDueAndState(t *testing.T) {
	now := time.Date(2026, 11, 10, 22, 0, 0, 0, time.UTC)
	if !Due(State{}, now) {
		t.Fatal("first check should be due")
	}
	if Due(State{LastCheck: now.Add(-20 * time.Minute)}, now) {
		t.Fatal("checked 20 min ago, not due")
	}
	if !Due(State{LastCheck: now.Add(-2 * time.Hour)}, now) {
		t.Fatal("checked 2h ago: due (checks every hour)")
	}
	if !Due(State{LastCheck: now.Add(-25 * time.Hour)}, now) {
		t.Fatal("checked 25h ago, due")
	}
	if !Due(State{LastCheck: now.Add(-2 * time.Hour), LastError: "offline"}, now) {
		t.Fatal("after an error, retry after one hour")
	}

	p := filepath.Join(t.TempDir(), "state.json")
	st := LoadState(p)
	st.LastCheck = now
	st.Components["engine"] = Component{Version: "142.0", OK: true}
	if err := SaveState(p, st); err != nil {
		t.Fatal(err)
	}
	if got := LoadState(p); !got.LastCheck.Equal(now) || !got.Components["engine"].OK {
		t.Fatalf("state not saved: %+v", got)
	}
	os.WriteFile(p, []byte("{broken"), 0o644)
	if got := LoadState(p); got.Components == nil {
		t.Fatal("broken file should give empty state")
	}
}
