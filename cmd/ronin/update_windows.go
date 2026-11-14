//go:build windows

package main

import (
	"context"
	"crypto/ed25519"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
	"time"

	"roninbrowser/internal/updater"
)

// releasePublicKey verifies Ronin updates. Only files signed with the
// matching private key are ever installed.
const releasePublicKey = "1sMBFc7MJJjpIS98PAV3LAvsgqdWjAiDV94YG/dA61k="

const maxDownload = 300 << 20 // 300 MB safety limit

// backgroundUpdater checks for a new Ronin version once a day, silently.
// The browser engine itself is updated by Microsoft and needs nothing here.
func backgroundUpdater() {
	defer func() {
		if r := recover(); r != nil {
			logf("updater panic: %v", r)
		}
	}()
	time.Sleep(60 * time.Second) // let the browser start first
	for {
		checkForUpdate()
		time.Sleep(15 * time.Minute)
	}
}

// checkUpdateNow is Menu -> "Check for updates": checks at once and says what happened.
func checkUpdateNow(w *Window) {
	runOnUI(func() { w.bar(nil, "info", "⟳ नया Ronin देख रहा हूँ... (Checking for updates)", nil) })
	go func() {
		channel := store.Get().UpdateChannel
		if channel == "" {
			runOnUI(func() { w.bar(nil, "info", "Updates बंद हैं (Settings).", nil) })
			return
		}
		stateMu.Lock()
		st := updater.LoadState(statePath())
		stateMu.Unlock()
		err := tryUpdate(channel, &st)
		stateMu.Lock()
		fresh := updater.LoadState(statePath())
		fresh.LastCheck = time.Now()
		fresh.AppVersion = Version
		fresh.PendingNote = st.PendingNote
		fresh.LastError = ""
		if err != nil {
			fresh.LastError = err.Error()
		}
		_ = updater.SaveState(statePath(), fresh)
		stateMu.Unlock()
		runOnUI(func() {
			switch {
			case err != nil:
				w.bar(nil, "error", "Update नहीं हो सका: "+err.Error(), nil)
			case st.PendingNote != "":
				w.bar(nil, "ok", "✔ "+st.PendingNote, []map[string]string{{"label": "अभी Restart करें", "cmd": "restart"}})
			default:
				w.bar(nil, "ok", "✔ Ronin "+Version+" सबसे नया है (up to date).", nil)
			}
		})
	}()
}

// tellRestart shows "new Ronin is ready - Restart" in every window (after a background update).
func tellRestart(note string) {
	runOnUI(func() {
		for _, w := range windowsByHwnd {
			if !w.closing {
				w.bar(nil, "ok", "✔ "+note, []map[string]string{{"label": "अभी Restart करें", "cmd": "restart"}})
			}
		}
	})
}

func checkForUpdate() {
	updateFilterLists()
	updateKidsLists()
	updateTopSites()
	if vpnActive && !vpnAll {
		buildSmartVPN() // fresh lists take effect from the next start of the engine
	}
	updateTools()
	channel := store.Get().UpdateChannel
	if channel == "" {
		return
	}
	stateMu.Lock()
	st := updater.LoadState(statePath())
	stateMu.Unlock()
	if !updater.Due(st, time.Now()) {
		return
	}
	before := st.PendingNote
	err := tryUpdate(channel, &st)
	if err == nil && st.PendingNote != "" && st.PendingNote != before {
		tellRestart(st.PendingNote)
	}
	stateMu.Lock()
	defer stateMu.Unlock()
	fresh := updater.LoadState(statePath())
	fresh.LastCheck = time.Now()
	fresh.AppVersion = Version
	fresh.PendingNote = st.PendingNote
	fresh.LastError = ""
	if err != nil {
		fresh.LastError = err.Error()
		logf("update check: %v", err)
	}
	_ = updater.SaveState(statePath(), fresh)
}

func tryUpdate(channel string, st *updater.State) error {
	pub, err := base64.StdEncoding.DecodeString(releasePublicKey)
	if err != nil {
		return err
	}
	manifest, err := fetch(channel, 1<<20)
	if err != nil {
		return err
	}
	sig, err := fetch(channel+".sig", 4096)
	if err != nil {
		return err
	}
	m, err := updater.VerifyManifest(manifest, string(sig), ed25519.PublicKey(pub))
	if err != nil {
		return err
	}
	if m.App != "ronin" || !updater.Newer(m.Version, Version) {
		st.PendingNote = ""
		return nil
	}
	if strings.Contains(st.PendingNote, " "+m.Version+" ") {
		return nil // already installed, waiting for a restart
	}
	exe, err := os.Executable()
	if err != nil {
		return err
	}
	dir := filepath.Dir(exe)
	for _, f := range m.Files {
		data, err := fetch(f.URL, maxDownload)
		if err != nil {
			return err
		}
		var test updater.SelfTest
		if filepath.Ext(f.Name) == ".exe" {
			test = runSelfTest
		}
		if err := updater.Install(filepath.Join(dir, f.Name), data, f, test); err != nil {
			return err
		}
	}
	st.PendingNote = fmt.Sprintf("नया Ronin %s आ गया (installed). Restart करते ही खुल जाएगा, आपके tabs वैसे ही रहेंगे।", m.Version)
	logf("updated to %s", m.Version)
	return nil
}

func fetch(u string, limit int64) ([]byte, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Minute)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", "RoninBrowser-Updater/"+Version)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return nil, errors.New("no internet connection")
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("server answered %d", resp.StatusCode)
	}
	b, err := io.ReadAll(io.LimitReader(resp.Body, limit+1))
	if err != nil {
		return nil, err
	}
	if int64(len(b)) > limit {
		return nil, errors.New("download too large")
	}
	return b, nil
}

func runSelfTest(path string) error {
	cmd := exec.Command(path, "--self-test")
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x08000000}
	_, err := runWithTimeout(cmd, 30*time.Second)
	return err
}
