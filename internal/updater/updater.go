// Package updater is Ronin Updater: it checks for new versions in the
// background, accepts only files whose signature and checksum are correct,
// tests the new file before using it, and puts the old one back if
// anything goes wrong.
package updater

import (
	"bytes"
	"crypto/ed25519"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

// File is one file of a release.
type File struct {
	Name   string `json:"name"`   // file name inside the Ronin folder, e.g. "Ronin.exe"
	URL    string `json:"url"`    // https download address
	SHA256 string `json:"sha256"` // hex checksum
	Size   int64  `json:"size"`
}

// Manifest describes one release. It is signed with Ronin's private key.
type Manifest struct {
	App     string    `json:"app"`
	Version string    `json:"version"`
	Date    time.Time `json:"date"`
	Files   []File    `json:"files"`
}

var (
	ErrBadSignature = errors.New("update signature is not valid")
	ErrBadChecksum  = errors.New("downloaded file checksum does not match")
	ErrBadManifest  = errors.New("update information is not valid")
)

// VerifyManifest checks the signature (base64 Ed25519) and parses the manifest.
func VerifyManifest(data []byte, sigB64 string, pub ed25519.PublicKey) (*Manifest, error) {
	if len(pub) != ed25519.PublicKeySize {
		return nil, ErrBadSignature
	}
	sig, err := base64.StdEncoding.DecodeString(strings.TrimSpace(sigB64))
	if err != nil || len(sig) != ed25519.SignatureSize || !ed25519.Verify(pub, data, sig) {
		return nil, ErrBadSignature
	}
	var m Manifest
	dec := json.NewDecoder(bytes.NewReader(data))
	dec.DisallowUnknownFields()
	if err := dec.Decode(&m); err != nil {
		return nil, ErrBadManifest
	}
	if m.App == "" || ParseVersion(m.Version) == nil || len(m.Files) == 0 {
		return nil, ErrBadManifest
	}
	for _, f := range m.Files {
		if !safeFileName(f.Name) || !strings.HasPrefix(f.URL, "https://") || len(f.SHA256) != 64 || f.Size <= 0 {
			return nil, ErrBadManifest
		}
		if _, err := hex.DecodeString(f.SHA256); err != nil {
			return nil, ErrBadManifest
		}
	}
	return &m, nil
}

// SignManifest is used only by the release tool (it holds the private key).
func SignManifest(data []byte, priv ed25519.PrivateKey) string {
	return base64.StdEncoding.EncodeToString(ed25519.Sign(priv, data))
}

func safeFileName(n string) bool {
	return n != "" && n == filepath.Base(n) && !strings.ContainsAny(n, `\/:*?"<>|`) && n != "." && n != ".."
}

// ParseVersion reads "3.1.2" into [3 1 2]; nil if invalid.
func ParseVersion(v string) []int {
	v = strings.TrimPrefix(strings.TrimSpace(v), "v")
	parts := strings.Split(v, ".")
	if len(parts) < 1 || len(parts) > 4 {
		return nil
	}
	out := make([]int, 0, len(parts))
	for _, p := range parts {
		n, err := strconv.Atoi(p)
		if err != nil || n < 0 || p == "" {
			return nil
		}
		out = append(out, n)
	}
	return out
}

// Newer reports whether version a is newer than b. Invalid versions are never newer.
func Newer(a, b string) bool {
	va, vb := ParseVersion(a), ParseVersion(b)
	if va == nil || vb == nil {
		return false
	}
	for i := 0; i < len(va) || i < len(vb); i++ {
		x, y := 0, 0
		if i < len(va) {
			x = va[i]
		}
		if i < len(vb) {
			y = vb[i]
		}
		if x != y {
			return x > y
		}
	}
	return false
}

// CheckFile verifies size and SHA-256 of downloaded bytes.
func CheckFile(data []byte, f File) error {
	if int64(len(data)) != f.Size {
		return ErrBadChecksum
	}
	sum := sha256.Sum256(data)
	if !strings.EqualFold(hex.EncodeToString(sum[:]), f.SHA256) {
		return ErrBadChecksum
	}
	return nil
}

// SelfTest runs a quick test of a new file (for a program: start it with
// "--self-test" and expect success). It returns nil when the file works.
type SelfTest func(newPath string) error

// Install puts verified bytes in place of dst safely:
//  1. writes dst.new and checks it again,
//  2. runs the self test on dst.new,
//  3. renames dst -> dst.old and dst.new -> dst,
//  4. if any step fails, the old file stays (or is put back).
//
// A running program on Windows cannot be overwritten but can be renamed,
// so this also works for Ronin's own exe (the new one starts next time).
func Install(dst string, data []byte, f File, test SelfTest) error {
	if err := CheckFile(data, f); err != nil {
		return err
	}
	newPath, oldPath := dst+".new", dst+".old"
	_ = os.Remove(newPath)
	if err := os.WriteFile(newPath, data, 0o755); err != nil {
		return fmt.Errorf("write new file: %w", err)
	}
	if back, err := os.ReadFile(newPath); err != nil || CheckFile(back, f) != nil {
		_ = os.Remove(newPath)
		return ErrBadChecksum
	}
	if test != nil {
		if err := test(newPath); err != nil {
			_ = os.Remove(newPath)
			return fmt.Errorf("new version failed its self test, kept the old one: %w", err)
		}
	}
	_ = os.Remove(oldPath)
	hadOld := false
	if _, err := os.Stat(dst); err == nil {
		if err := os.Rename(dst, oldPath); err != nil {
			_ = os.Remove(newPath)
			return fmt.Errorf("move old file: %w", err)
		}
		hadOld = true
	}
	if err := os.Rename(newPath, dst); err != nil {
		if hadOld {
			_ = os.Rename(oldPath, dst)
		}
		_ = os.Remove(newPath)
		return fmt.Errorf("put new file in place: %w", err)
	}
	return nil
}

// Rollback puts dst.old back (used when the new version fails to start).
func Rollback(dst string) error {
	oldPath := dst + ".old"
	if _, err := os.Stat(oldPath); err != nil {
		return errors.New("no previous version to restore")
	}
	bad := dst + ".bad"
	_ = os.Remove(bad)
	if _, err := os.Stat(dst); err == nil {
		if err := os.Rename(dst, bad); err != nil {
			return err
		}
	}
	if err := os.Rename(oldPath, dst); err != nil {
		_ = os.Rename(bad, dst)
		return err
	}
	_ = os.Remove(bad)
	return nil
}

// Due reports whether a check is due: every 6 hours (Ronin also checks once
// each time it starts). The check is one small signed file.
func Due(st State, now time.Time) bool {
	if st.LastCheck.IsZero() {
		return true
	}
	return now.Sub(st.LastCheck) >= 6*time.Hour
}

// State is saved to disk so the check runs once a day and the Health bar
// on the New Tab page can show green or red.
type State struct {
	LastCheck   time.Time            `json:"last_check"`
	LastError   string               `json:"last_error,omitempty"`
	Components  map[string]Component `json:"components"`
	AppVersion  string               `json:"app_version"`
	PendingNote string               `json:"pending_note,omitempty"` // e.g. "restart to finish update"
}

// Component is the health of one part (engine, filter lists, VPN, downloader).
type Component struct {
	Version string    `json:"version"`
	OK      bool      `json:"ok"`
	Updated time.Time `json:"updated"`
	Problem string    `json:"problem,omitempty"`
}

// LoadState reads the state file; a missing or broken file gives an empty state.
func LoadState(path string) State {
	st := State{Components: map[string]Component{}}
	b, err := os.ReadFile(path)
	if err != nil {
		return st
	}
	if json.Unmarshal(b, &st) != nil {
		return State{Components: map[string]Component{}}
	}
	if st.Components == nil {
		st.Components = map[string]Component{}
	}
	return st
}

// SaveState writes the state file atomically.
func SaveState(path string, st State) error {
	b, err := json.MarshalIndent(st, "", "  ")
	if err != nil {
		return err
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, b, 0o644); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}
