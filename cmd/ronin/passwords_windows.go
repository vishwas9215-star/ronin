//go:build windows

package main

// Passwords: Ronin's own encrypted password store (Windows DPAPI), "Save
// password?" after a login, "Fill" on the same site only, CSV import/export.
// Never in Safe Money or Kids; private windows can fill but never save.

import (
	_ "embed"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
	"time"
	"unsafe"

	"roninbrowser/internal/containers"
	"roninbrowser/internal/settings"
	"roninbrowser/internal/vault"
)

//go:embed ui/inject_pass.js
var passJS string

//go:embed ui/fill_pass.js
var fillPassJS string

// ---- DPAPI: the file can only be opened by this Windows user on this PC ----------

var (
	crypt32                 = syscall.NewLazyDLL("crypt32.dll")
	pCryptProtectData       = crypt32.NewProc("CryptProtectData")
	pCryptUnprotectData     = crypt32.NewProc("CryptUnprotectData")
	pLocalFree              = kernel32.NewProc("LocalFree")
	vaultEntropy            = []byte("Ronin-Vault-1")
	errDPAPI                = errors.New("windows could not encrypt the data")
	cryptprotectUIForbidden = 0x1
)

type dataBlob struct {
	n uint32
	p *byte
}

func blobOf(b []byte) *dataBlob {
	if len(b) == 0 {
		return &dataBlob{}
	}
	return &dataBlob{n: uint32(len(b)), p: &b[0]}
}

func (d *dataBlob) bytes() []byte {
	out := make([]byte, d.n)
	copy(out, unsafe.Slice(d.p, d.n))
	return out
}

type dpapi struct{}

func (dpapi) Seal(b []byte) ([]byte, error) {
	var out dataBlob
	r, _, _ := pCryptProtectData.Call(uintptr(unsafe.Pointer(blobOf(b))), 0, uintptr(unsafe.Pointer(blobOf(vaultEntropy))),
		0, 0, uintptr(cryptprotectUIForbidden), uintptr(unsafe.Pointer(&out)))
	if r == 0 {
		return nil, errDPAPI
	}
	defer pLocalFree.Call(uintptr(unsafe.Pointer(out.p)))
	return out.bytes(), nil
}

func (dpapi) Open(b []byte) ([]byte, error) {
	var out dataBlob
	r, _, _ := pCryptUnprotectData.Call(uintptr(unsafe.Pointer(blobOf(b))), 0, uintptr(unsafe.Pointer(blobOf(vaultEntropy))),
		0, 0, uintptr(cryptprotectUIForbidden), uintptr(unsafe.Pointer(&out)))
	if r == 0 {
		return nil, errDPAPI
	}
	defer pLocalFree.Call(uintptr(unsafe.Pointer(out.p)))
	return out.bytes(), nil
}

var pwStore *vault.Vault

// passwords opens the store once (nil when it cannot be opened).
func passwords() *vault.Vault {
	if pwStore != nil {
		return pwStore
	}
	v, err := vault.Open(filepath.Join(dataDir, "passwords.dat"), dpapi{})
	if err != nil {
		logf("passwords: %v", err)
		return nil
	}
	pwStore = v
	return v
}

// pwFills: windows where saved passwords may be filled; pwSaves: may be saved.
func pwFills(c containers.Container) bool {
	return c == containers.Personal || c == containers.Private || c == containers.Trading || c == containers.Calls
}
func pwSaves(c containers.Container) bool { return pwFills(c) && c != containers.Private }

type pwCapture struct {
	page, user, pass string
	id               int
	at               time.Time
}

// pwTake asks the page for a login it just sent, then offers to save it.
// The site always comes from Ronin's own record of the tab, never from the page.
func (t *Tab) pwTake() {
	if t.destroyed || !pwSaves(t.w.container) || t.isNewTab {
		return
	}
	origin := vault.Origin(t.wv.Source()) // the page the script runs in (still the old one at NavigationStarting)
	if origin == "" {
		return
	}
	t.runScript(`window.__roninPw?window.__roninPw.take():null`, func(r string) {
		var got []string
		if json.Unmarshal([]byte(r), &got) != nil || len(got) != 2 || got[1] == "" || t.destroyed {
			return
		}
		if vault.Origin(t.wv.Source()) != origin {
			return // the tab moved to another site meanwhile: do not guess
		}
		if p := t.pwPending; p != nil && p.page == origin && p.user == got[0] && p.pass == got[1] {
			return // already asking about this login
		}
		v := passwords()
		if v == nil {
			return
		}
		st := v.Status(origin, got[0], got[1])
		if st != "new" && st != "update" {
			return
		}
		t.pwSeq++
		t.pwPending = &pwCapture{page: origin, user: got[0], pass: got[1], id: t.pwSeq, at: time.Now()}
		t.pwBar = false // a save prompt stays while the site moves on to its next page
		site := strings.TrimPrefix(origin, "https://")
		who := ""
		if got[0] != "" {
			who = " (" + got[0] + ")"
		}
		text := "Save the password for " + site + who + " in Ronin?"
		if st == "update" {
			text = "Update the saved password for " + site + who + "?"
		}
		id := fmt.Sprint(t.pwSeq)
		t.w.bar(t, "info", text, []map[string]string{
			{"label": map[bool]string{true: "Update", false: "Save"}[st == "update"], "cmd": "pwsave", "url": id},
			{"label": "Never for this site", "cmd": "pwnever", "url": id},
		})
	})
}

// pwOffer shows "Fill" when the page has a password box and Ronin has a
// password for exactly this site.
func (t *Tab) pwOffer() {
	if t.destroyed || !pwFills(t.w.container) || t.isNewTab || vault.Origin(t.url) == "" ||
		(t.pwPending != nil && time.Since(t.pwPending.at) < 2*time.Minute) {
		return
	}
	v := passwords()
	if v == nil {
		return
	}
	found := v.Find(t.url)
	if len(found) == 0 {
		return
	}
	t.runScript(`document.querySelectorAll("input[type=password]").length>0`, func(r string) {
		if r != "true" || t.destroyed {
			return
		}
		var acts []map[string]string
		for i, e := range found {
			if i == 3 {
				break
			}
			name := e.Username
			if name == "" {
				name = "saved password"
			}
			acts = append(acts, map[string]string{"label": "Fill: " + name, "cmd": "pwfill", "url": fmt.Sprint(i)})
		}
		t.pwBar = true
		t.w.bar(t, "info", "Ronin has a saved password for this site.", acts)
	})
}

// pwClearBar removes the Fill bar when the tab moves on (it belonged to the old page).
func (t *Tab) pwClearBar() {
	if t.pwBar {
		t.pwBar = false
		t.w.bar(t, "", "", nil)
	}
}

// pwFill fills the chosen login, only if the page is still the same site
// (checked here with the engine's current address, and again inside the page).
func (t *Tab) pwFill(index int) {
	v := passwords()
	src := t.wv.Source()
	if v == nil || vault.Origin(src) == "" {
		return
	}
	found := v.Find(src)
	if index < 0 || index >= len(found) {
		return
	}
	e := found[index]
	if vault.Origin(src) != e.Origin {
		return
	}
	scheme, host, _ := strings.Cut(e.Origin, "://")
	args, _ := json.Marshal([]string{e.Origin, scheme + "://www." + host, e.Username, e.Password})
	t.runScript(fillPassJS+"("+string(args)+")", func(r string) {
		if r != "true" {
			t.w.bar(t, "warn", "Ronin did not fill: no login box on this page, or the site changed.", nil)
		}
	})
}

// ---- Windows Hello before showing or exporting passwords -----------------------

// verifyUser asks Windows (fingerprint, face or Windows PIN) that the person
// at the computer is the owner. Returns "" when verified, else the reason.
func verifyUser(why string) string {
	ps := `$ErrorActionPreference='Stop';Add-Type -AssemblyName System.Runtime.WindowsRuntime;` +
		`$m=([System.WindowsRuntimeSystemExtensions].GetMethods()|?{$_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation` + "`" + `1'})[0];` +
		`[void][Windows.Security.Credentials.UI.UserConsentVerifier,Windows.Security.Credentials.UI,ContentType=WindowsRuntime];` +
		`$op=[Windows.Security.Credentials.UI.UserConsentVerifier]::RequestVerificationAsync('` + strings.ReplaceAll(why, "'", "") + `');` +
		`$t=$m.MakeGenericMethod([Windows.Security.Credentials.UI.UserConsentVerificationResult]).Invoke($null,@($op));` +
		`[void]$t.Wait(-1);[Console]::Out.Write($t.Result)`
	cmd := exec.Command("powershell.exe", "-NoProfile", "-NonInteractive", "-STA", "-Command", ps)
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: createNoWindow}
	out, err := runWithTimeout(cmd, 3*time.Minute)
	res := strings.TrimSpace(string(out))
	switch {
	case res == "Verified":
		return ""
	case err != nil && res == "":
		return "Windows could not ask for your PIN or fingerprint."
	case res == "DeviceNotPresent" || res == "NotConfiguredForUser" || res == "DisabledByPolicy":
		return "Set up a Windows Hello PIN first: Settings - Accounts - Sign-in options - PIN."
	case res == "Canceled":
		return "Cancelled."
	}
	return "Not verified (" + res + ")."
}

// pwVerified remembers a verification for 2 minutes (open list, then export).
var pwVerifiedAt time.Time

func (w *Window) withVerify(why string, then func()) {
	if time.Since(pwVerifiedAt) < 2*time.Minute {
		then()
		return
	}
	go func() {
		reason := verifyUser(why)
		runOnUI(func() {
			if reason != "" {
				w.bar(nil, "warn", "Passwords stay locked: "+reason, nil)
				return
			}
			pwVerifiedAt = time.Now()
			then()
		})
	}()
}

func passwordsData(reveal bool) map[string]any {
	v := passwords()
	if v == nil {
		return map[string]any{"error": "The password file could not be opened on this Windows account."}
	}
	var list []map[string]string
	for _, e := range v.List() {
		m := map[string]string{"origin": e.Origin, "user": e.Username, "note": e.Note}
		if reveal {
			m["pass"] = e.Password
		}
		list = append(list, m)
	}
	return map[string]any{"list": list, "count": v.Len()}
}

func pickCSV() (string, error) {
	return pickFile("Choose the passwords file (.csv) to import", "Passwords (*.csv)|*.csv|All files (*.*)|*.*")
}

// pickFile shows the Windows "Open" window and returns the chosen file ("" = cancelled).
func pickFile(title, filter string) (string, error) {
	ps := `Add-Type -AssemblyName System.Windows.Forms; $d = New-Object System.Windows.Forms.OpenFileDialog; ` +
		`$d.Title = '` + title + `'; $d.Filter = '` + filter + `'; ` +
		`if ($d.ShowDialog() -eq 'OK') { [Console]::Out.Write($d.FileName) }`
	cmd := exec.Command("powershell.exe", "-NoProfile", "-NonInteractive", "-STA", "-Command", ps)
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: createNoWindow}
	out, err := runWithTimeout(cmd, 10*time.Minute)
	if err != nil {
		return "", errors.New("the file window could not be opened")
	}
	return strings.TrimSpace(string(out)), nil
}

// onPasswordMessage handles the toolbar's password messages.
func (w *Window) onPasswordMessage(m uiMsg) bool {
	switch m.Cmd {
	case "pwsave", "pwnever":
		t := w.tabByID(m.ID)
		if t == nil || t.pwPending == nil || fmt.Sprint(t.pwPending.id) != m.URL {
			return true // an older prompt: the user did not see this login
		}
		p := t.pwPending
		t.pwPending = nil
		v := passwords()
		if v == nil {
			return true
		}
		if m.Cmd == "pwnever" {
			_ = v.Never(p.page)
			return true
		}
		if err := v.Put(p.page, p.user, p.pass, ""); err != nil {
			w.bar(t, "error", "Password not saved: "+err.Error(), nil)
		} else {
			w.bar(t, "ok", "Password saved in Ronin (encrypted). Menu - Passwords to see it.", nil)
		}
	case "pwfill":
		var i int
		fmt.Sscan(m.URL, &i)
		if t := w.tabByID(m.ID); t != nil {
			t.pwFill(i)
		}
	case "passwords":
		if !pwFills(w.container) {
			return true
		}
		w.overlay("passwords", passwordsData(false))
	case "pwshow":
		if !pwFills(w.container) {
			return true
		}
		w.withVerify("Ronin Browser: show saved passwords", func() { w.overlay("passwords", passwordsData(true)) })
	case "pwremove":
		if !pwFills(w.container) {
			return true
		}
		w.withVerify("Ronin Browser: delete a saved password", func() {
			if v := passwords(); v != nil {
				o, u, _ := strings.Cut(m.URL, "\n")
				_ = v.Remove(o, u)
			}
			w.overlay("passwords", passwordsData(true))
		})
	case "pwimport":
		if !pwFills(w.container) {
			return true
		}
		w.withVerify("Ronin Browser: import passwords", func() {
			go func() {
				path, err := pickCSV()
				runOnUI(func() {
					if err != nil || path == "" {
						return
					}
					v := passwords()
					f, err := os.Open(path)
					if st, serr := os.Stat(path); err == nil && (serr != nil || st.Size() > 20<<20) {
						f.Close()
						err = errors.New("too big")
					}
					if v == nil || err != nil {
						w.bar(nil, "error", "Passwords not imported: the file could not be read.", nil)
						return
					}
					defer f.Close()
					added, skipped, err := v.ImportCSV(f)
					if err != nil {
						w.bar(nil, "error", "Passwords not imported: "+err.Error(), nil)
						return
					}
					w.bar(nil, "ok", fmt.Sprintf("%d passwords imported (%d skipped: no secure address or no password). Now delete the CSV file - it is not encrypted.", added, skipped), nil)
					w.overlay("passwords", passwordsData(true))
				})
			}()
		})
	case "pwexport":
		if !pwFills(w.container) {
			return true
		}
		w.withVerify("Ronin Browser: export passwords", func() {
			v := passwords()
			if v == nil {
				return
			}
			p := filepath.Join(downloadsDir(), "Ronin-passwords-"+time.Now().Format("2006-01-02")+".csv")
			f, err := os.OpenFile(p, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o600)
			if err == nil {
				err = v.ExportCSV(f)
				if cerr := f.Close(); err == nil {
					err = cerr
				}
			}
			if err != nil {
				w.bar(nil, "error", "Export failed: "+err.Error(), nil)
				return
			}
			w.bar(nil, "warn", "Passwords exported to Downloads\\"+filepath.Base(p)+". This file is NOT encrypted and should not be opened in Excel: import it on the other device, then delete it.",
				[]map[string]string{{"label": "Show in folder", "cmd": "pwexportshow", "url": p}})
		})
	case "bmexport":
		p := filepath.Join(downloadsDir(), "Ronin-bookmarks-"+time.Now().Format("2006-01-02")+".html")
		if err := os.WriteFile(p, []byte(settings.BookmarksHTML(store.Get().Bookmarks)), 0o644); err != nil {
			w.bar(nil, "error", "Bookmarks not exported: "+err.Error(), nil)
			return true
		}
		w.bar(nil, "ok", "Bookmarks saved as Downloads\\"+filepath.Base(p)+" - any browser (also Ronin on your phone) can import this file.",
			[]map[string]string{{"label": "Show in folder", "cmd": "pwexportshow", "url": p}})
	case "bmimport":
		go func() {
			path, err := pickFile("Choose a bookmarks file (.html) from any browser", "Bookmarks (*.html;*.htm)|*.html;*.htm|All files (*.*)|*.*")
			runOnUI(func() {
				if err != nil || path == "" {
					return
				}
				b, err := os.ReadFile(path)
				if err != nil || len(b) > 20<<20 {
					w.bar(nil, "error", "Bookmarks not imported: the file could not be read.", nil)
					return
				}
				n := store.AddBookmarks(settings.ParseBookmarksHTML(string(b)))
				w.bar(nil, "ok", fmt.Sprintf("%d bookmarks imported.", n), nil)
				w.send(jsCall("overlay", map[string]any{"kind": "bookmarks", "list": store.Get().Bookmarks}))
			})
		}()
	case "pwexportshow":
		if p := filepath.Clean(m.URL); strings.HasPrefix(p, filepath.Clean(downloadsDir())+string(os.PathSeparator)) {
			openExplorer(p, true)
		}
	default:
		return false
	}
	return true
}
