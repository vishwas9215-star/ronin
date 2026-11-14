//go:build windows

package main

import (
	"bytes"
	"crypto/rand"
	"encoding/hex"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"time"

	"roninbrowser/internal/adblock"
	"roninbrowser/internal/containers"
	"roninbrowser/internal/kids"
	"roninbrowser/internal/updater"
)

// Adult and gambling site lists used only in KIDS mode.
var kidsLists = []filterList{
	{"kids-adult", "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/porn-only/hosts", 10000, false},
	{"kids-gambling", "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/gambling-only/hosts", 1000, false},
}

var kidsBlocker atomic.Pointer[adblock.Engine]

// kidsLevel is the KIDS lock of this Windows account ("" when off).
func kidsLevel() kids.Level { return kids.Level(store.Get().KidsLevel) }

func loadKidsLists() {
	e := adblock.New()
	for _, l := range kidsLists {
		if b, err := os.ReadFile(listPath(l.name)); err == nil {
			e.LoadList(bytes.NewReader(b))
		}
	}
	kidsBlocker.Store(e)
}

func updateKidsLists() {
	if kidsLevel() == kids.Off && !store.Get().VPNOn {
		return // these lists are for Kids mode and for the smart VPN
	}
	_ = os.MkdirAll(filepath.Join(dataDir, "lists"), 0o755)
	stateMu.Lock()
	st := updater.LoadState(statePath())
	stateMu.Unlock()
	changed := false
	for _, l := range kidsLists {
		key := "list:" + l.name
		if c := st.Components[key]; !c.Updated.IsZero() && time.Since(c.Updated) < 24*time.Hour {
			continue
		}
		b, err := fetch(l.url, 30<<20)
		ok := false
		if err == nil {
			blk, alw := adblock.ParseList(bytes.NewReader(b))
			if len(blk)+len(alw) >= l.min && os.WriteFile(listPath(l.name), b, 0o644) == nil {
				ok, changed = true, true
			}
		}
		recordComponent(key, func(c *updater.Component) { c.Updated, c.OK = time.Now(), ok })
	}
	if changed {
		loadKidsLists()
	}
}

// kidsCheck decides a top-level page in KIDS mode: "" = allowed,
// "safe:<url>" = open the SafeSearch version instead, otherwise a reason.
func kidsCheck(u string) string {
	if !strings.HasPrefix(u, "http") {
		return ""
	}
	if s := kids.SafeSearchURL(u); s != "" {
		return "safe:" + s
	}
	switch kidsLevel() {
	case kids.Junior:
		if !kids.JuniorAllowed(u, store.Get().KidsSites) {
			return "This site is not on the Kids list. A parent can add it in Settings."
		}
	default:
		if kids.LooksAdult(u) {
			return "This site is not suitable for children."
		}
		if e := kidsBlocker.Load(); e != nil && e.Blocked(u) {
			return "This site is not suitable for children."
		}
	}
	return ""
}

// hookContainerRules adds rules that depend on the window type.
func (t *Tab) hookContainerRules() {
	if t.w.container != containers.Kids {
		return
	}
	// YouTube Restricted Mode and SafeSearch for every YouTube request.
	t.kidsHeaders = true
}

// ---- parent PIN -----------------------------------------------------------------

func newSalt() string {
	b := make([]byte, 8)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

// setKidsMode switches this Windows account to KIDS mode (needs a new PIN).
func setKidsMode(level kids.Level, pin string) bool {
	if level == kids.Off || !level.Valid() || !kids.ValidPIN(pin) {
		return false
	}
	salt := newSalt()
	return store.Update(func(s *settingsT) {
		s.KidsLevel = string(level)
		s.KidsPIN = salt + ":" + kids.HashPIN(pin, salt)
	}) == nil
}

// checkPIN verifies the parent PIN.
func checkPIN(pin string) bool {
	stored := store.Get().KidsPIN
	salt, hash, ok := strings.Cut(stored, ":")
	return ok && kids.ValidPIN(pin) && kids.HashPIN(pin, salt) == hash
}

func clearKidsMode(pin string) bool {
	if !checkPIN(pin) {
		return false
	}
	return store.Update(func(s *settingsT) { s.KidsLevel, s.KidsPIN = "", "" }) == nil
}

// ---- wrong-PIN protection -----------------------------------------------------

var (
	pinFails     int
	pinLockUntil time.Time
)

func pinFailed() {
	pinFails++
	if pinFails >= 3 {
		pinFails = 0
		pinLockUntil = time.Now().Add(time.Minute)
	}
}

func pinLocked() bool { return time.Now().Before(pinLockUntil) }
