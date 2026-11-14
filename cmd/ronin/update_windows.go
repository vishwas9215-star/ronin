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
		time.Sleep(time.Hour) // each part keeps its own timetable; most hours nothing happens
	}
}

// A new Ronin is never installed behind the owner's back: Ronin asks once ("नया Ronin आया है")
// and installs only when he taps Update. The check itself is one small signed file, at start and
// then every 6 hours.

var (
	offeredVersion string // already shown in this run
	checkedThisRun bool
	updating       bool
)

// offerUpdate shows "new Ronin - Update" in every window.
func offerUpdate(version string) {
	runOnUI(func() {
		for _, w := range windowsByHwnd {
			if !w.closing {
				w.bar(nil, "ok", "🆕 नया Ronin "+version+" आया है।", []map[string]string{{"label": "Update करें", "cmd": "updatenow"}})
			}
		}
	})
}

// checkUpdateNow is Menu -> "Check for updates": checks at once and says what happened.
func checkUpdateNow(w *Window) {
	runOnUI(func() {
		w.bar(nil, "info", "⟳ नया Ronin देख रहा हूँ... (Checking for updates)", nil)
	})
	go func() {
		m, err := latestRelease()
		runOnUI(func() {
			switch {
			case err != nil:
				w.bar(nil, "error", "Update नहीं देख सका: "+err.Error(), nil)
			case m == nil:
				w.bar(nil, "ok", "✔ Ronin "+Version+" सबसे नया है (up to date).", nil)
			default:
				w.bar(nil, "ok", "🆕 नया Ronin "+m.Version+" आया है।", []map[string]string{{"label": "Update करें", "cmd": "updatenow"}})
			}
		})
	}()
}

// installUpdateNow is the "Update करें" button: download, check, install, then offer the restart.
func installUpdateNow(w *Window) {
	if updating {
		return
	}
	updating = true
	w.bar(nil, "info", "⬇ नया Ronin download हो रहा है... (कुछ मिनट, आप काम करते रहें)", nil)
	go func() {
		defer func() { updating = false }()
		m, err := latestRelease()
		if err == nil && m != nil {
			err = installRelease(m)
		}
		runOnUI(func() {
			switch {
			case err != nil:
				w.bar(nil, "error", "Update नहीं हो सका: "+err.Error(), []map[string]string{{"label": "फिर कोशिश", "cmd": "updatenow"}})
			case m == nil:
				w.bar(nil, "ok", "✔ Ronin "+Version+" सबसे नया है.", nil)
			default:
				for _, x := range windowsByHwnd {
					if !x.closing {
						x.bar(nil, "ok", "✔ नया Ronin "+m.Version+" तैयार है। Restart करते ही खुलेगा, आपके tabs वैसे ही रहेंगे।",
							[]map[string]string{{"label": "अभी Restart करें", "cmd": "restart"}})
					}
				}
			}
		})
	}()
}

func checkForUpdate() {
	updateFilterLists()
	updateKidsLists()
	updateTopSites()
	if vpnActive && !vpnAll {
		buildSmartVPN() // fresh lists take effect from the next start of the engine
	}
	updateTools()
	if store.Get().UpdateChannel == "" {
		return
	}
	stateMu.Lock()
	st := updater.LoadState(statePath())
	stateMu.Unlock()
	if checkedThisRun && !updater.Due(st, time.Now()) {
		return // once when Ronin starts, then every 6 hours
	}
	checkedThisRun = true
	m, err := latestRelease()
	if err == nil && m != nil && m.Version != offeredVersion {
		offeredVersion = m.Version
		offerUpdate(m.Version)
	}
	stateMu.Lock()
	defer stateMu.Unlock()
	fresh := updater.LoadState(statePath())
	fresh.LastCheck = time.Now()
	fresh.AppVersion = Version
	fresh.PendingNote = ""
	fresh.LastError = ""
	if err != nil {
		fresh.LastError = err.Error()
		logf("update check: %v", err)
	}
	_ = updater.SaveState(statePath(), fresh)
}

// latestRelease reads the signed release note; nil when this Ronin is already the newest.
func latestRelease() (*updater.Manifest, error) {
	channel := store.Get().UpdateChannel
	if channel == "" {
		return nil, errors.New("updates are off in Settings")
	}
	pub, err := base64.StdEncoding.DecodeString(releasePublicKey)
	if err != nil {
		return nil, err
	}
	manifest, err := fetch(channel, 1<<20)
	if err != nil {
		return nil, err
	}
	sig, err := fetch(channel+".sig", 4096)
	if err != nil {
		return nil, err
	}
	m, err := updater.VerifyManifest(manifest, string(sig), ed25519.PublicKey(pub))
	if err != nil {
		return nil, err
	}
	if m.App != "ronin" || !updater.Newer(m.Version, Version) {
		return nil, nil
	}
	return m, nil
}

// installRelease downloads, checks and installs the files of a release next to this Ronin.
func installRelease(m *updater.Manifest) error {
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
