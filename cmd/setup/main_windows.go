//go:build windows

// RoninSetup.exe installs (or with --uninstall removes) Ronin Browser for
// the current Windows user. No administrator rights are needed.
package main

import (
	_ "embed"
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
	"unsafe"

	"github.com/wailsapp/go-webview2/webviewloader"
	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/registry"
)

//go:embed payload/ronin.ico
var iconData []byte

//go:embed payload/Ronin.exe
var roninExe []byte

// Version is set at build time.
var Version = "3.0.0"

const (
	appName      = "Ronin Browser"
	uninstallKey = `Software\Microsoft\Windows\CurrentVersion\Uninstall\RoninBrowser`
	// Microsoft's official WebView2 Evergreen bootstrapper.
	webview2URL = "https://go.microsoft.com/fwlink/p/?LinkId=2124703"

	mbOK          = 0x0
	mbYesNo       = 0x4
	mbIconInfo    = 0x40
	mbIconWarning = 0x30
	mbIconError   = 0x10
	mbIconQ       = 0x20
	idYes         = 6
)

var pMessageBoxW = windows.NewLazySystemDLL("user32.dll").NewProc("MessageBoxW")

func box(text string, flags uintptr) int {
	t, _ := windows.UTF16PtrFromString(text)
	c, _ := windows.UTF16PtrFromString("Ronin Setup")
	r, _, _ := pMessageBoxW.Call(0, uintptr(unsafe.Pointer(t)), uintptr(unsafe.Pointer(c)), flags)
	return int(r)
}

// installFolderName is the only folder Ronin Setup ever creates or deletes.
// Other folders next to it (for example "Ronin") are never touched.
const installFolderName = "Ronin Browser"

func defaultInstallDir() string {
	return filepath.Join(os.Getenv("LOCALAPPDATA"), "Programs", installFolderName)
}

// besideSetupDir is "<folder that contains RoninSetup.exe>\Ronin Browser".
func besideSetupDir() string {
	self, err := os.Executable()
	if err != nil {
		return ""
	}
	parent := filepath.Dir(self)
	if strings.EqualFold(filepath.Base(parent), installFolderName) {
		return parent
	}
	return filepath.Join(parent, installFolderName)
}

// installedDir reads where Ronin was installed (for uninstall and updates).
func installedDir() string {
	k, err := registry.OpenKey(registry.CURRENT_USER, uninstallKey, registry.QUERY_VALUE)
	if err != nil {
		return ""
	}
	defer k.Close()
	v, _, err := k.GetStringValue("InstallLocation")
	if err != nil {
		return ""
	}
	return v
}

// safeToRemove guards the uninstaller: it only deletes a folder named
// exactly "Ronin Browser" that contains Ronin.exe.
func safeToRemove(dir string) bool {
	if dir == "" || !strings.EqualFold(filepath.Base(dir), installFolderName) {
		return false
	}
	_, err := os.Stat(filepath.Join(dir, "Ronin.exe"))
	return err == nil
}

func dataDir() string { return filepath.Join(os.Getenv("LOCALAPPDATA"), "RoninBrowser") }

func main() {
	for _, a := range os.Args[1:] {
		if a == "--uninstall" {
			uninstall()
			return
		}
	}
	install()
}

func install() {
	dir := defaultInstallDir()
	if here := besideSetupDir(); here != "" && !strings.EqualFold(filepath.Dir(here), filepath.Join(os.Getenv("USERPROFILE"), "Downloads")) {
		dir = here
	}
	if prev := installedDir(); prev != "" && safeToRemove(prev) {
		dir = prev // update an existing install in place
	}
	if box(fmt.Sprintf("Install %s %s?\n\nIt will be installed in this folder (only this folder is created or changed):\n%s", appName, Version, dir), mbYesNo|mbIconQ) != idYes {
		return
	}
	if err := ensureWebView2(); err != nil {
		box("Ronin needs the Microsoft WebView2 engine, and it could not be installed:\n\n"+err.Error()+
			"\n\nPlease connect to the internet and run Ronin Setup again.", mbOK|mbIconError)
		return
	}
	if err := os.MkdirAll(dir, 0o755); err != nil {
		box("Could not create the install folder:\n"+err.Error(), mbOK|mbIconError)
		return
	}
	exe := filepath.Join(dir, "Ronin.exe")
	if err := replaceFile(exe, roninExe); err != nil {
		box("Could not copy Ronin.exe. If Ronin is open, close all Ronin windows and try again.\n\n"+err.Error(), mbOK|mbIconError)
		return
	}
	// Keep a copy of this setup program for "Uninstall" in Windows Settings.
	uninst := filepath.Join(dir, "RoninUninstall.exe")
	if self, err := os.Executable(); err == nil && !strings.EqualFold(self, uninst) {
		if b, err := os.ReadFile(self); err == nil {
			_ = replaceFile(uninst, b)
		}
	}
	var problems []string
	startMenu := filepath.Join(os.Getenv("APPDATA"), `Microsoft\Windows\Start Menu\Programs`)
	desktop := desktopDir()
	shortcuts := []struct{ path, args, desc string }{
		{filepath.Join(startMenu, "Ronin Browser.lnk"), "", "Ronin Browser"},
		{filepath.Join(startMenu, "Ronin Safe Money.lnk"), "--safemoney", "Ronin Safe Money - protected banking"},
		{filepath.Join(desktop, "Ronin Browser.lnk"), "", "Ronin Browser"},
		{filepath.Join(desktop, "Ronin Safe Money.lnk"), "--safemoney", "Ronin Safe Money - protected banking"},
		// Ronin Mail (laptop only, back in 3.13): Gmail with Safe Mail (scam / threat emails marked)
		{filepath.Join(startMenu, "Ronin Mail.lnk"), "--mail", "Ronin Mail - email with Safe Mail (scam / threat emails marked)"},
		{filepath.Join(desktop, "Ronin Mail.lnk"), "--mail", "Ronin Mail - email with Safe Mail (scam / threat emails marked)"},
	}
	// Windows remembers a program's icon by file name, so after a logo change the
	// old picture stays on the desktop. Each version gets its own icon file, and
	// Windows is told that icons changed.
	icon := installIcon(dir)
	for _, s := range shortcuts {
		if err := makeShortcut(s.path, exe, s.args, s.desc, icon); err != nil {
			problems = append(problems, filepath.Base(s.path))
		}
	}
	if err := registerUninstall(dir, exe, uninst); err != nil {
		problems = append(problems, "uninstall entry")
	}
	refreshIcons(shortcuts[0].path, shortcuts[1].path, shortcuts[2].path, shortcuts[3].path)
	msg := appName + " is installed.\n\nOpen it now?"
	if len(problems) > 0 {
		msg = appName + " is installed, but these could not be created: " + strings.Join(problems, ", ") + ".\n\nOpen it now?"
	}
	if box(msg, mbYesNo|mbIconInfo) == idYes {
		cmd := exec.Command(exe)
		cmd.Dir = dir
		_ = cmd.Start()
	}
}

// replaceFile writes data to path; if the old file is in use (Ronin open),
// it is renamed out of the way first.
func replaceFile(path string, data []byte) error {
	tmp := path + ".new"
	if err := os.WriteFile(tmp, data, 0o755); err != nil {
		return err
	}
	if err := os.Rename(tmp, path); err == nil {
		return nil
	}
	old := path + ".old"
	_ = os.Remove(old)
	if err := os.Rename(path, old); err != nil {
		_ = os.Remove(tmp)
		return err
	}
	if err := os.Rename(tmp, path); err != nil {
		_ = os.Rename(old, path)
		return err
	}
	return nil
}

func ensureWebView2() error {
	if v, err := webviewloader.GetAvailableCoreWebView2BrowserVersionString(""); err == nil && v != "" {
		return nil
	}
	if box("Ronin needs the Microsoft WebView2 engine (free, from Microsoft). Download and install it now?", mbYesNo|mbIconQ) != idYes {
		return errors.New("the WebView2 engine is not installed")
	}
	f := filepath.Join(os.TempDir(), "MicrosoftEdgeWebview2Setup.exe")
	if err := download(webview2URL, f); err != nil {
		return err
	}
	defer os.Remove(f)
	if err := verifyMicrosoftSignature(f); err != nil {
		return err
	}
	cmd := exec.Command(f, "/silent", "/install")
	if err := cmd.Run(); err != nil {
		return fmt.Errorf("WebView2 installer failed: %w", err)
	}
	for i := 0; i < 30; i++ {
		if v, err := webviewloader.GetAvailableCoreWebView2BrowserVersionString(""); err == nil && v != "" {
			return nil
		}
		time.Sleep(time.Second)
	}
	return errors.New("WebView2 was installed but is not ready yet; restart the computer and run Ronin Setup again")
}

func download(u, dst string) error {
	c := &http.Client{Timeout: 10 * time.Minute}
	resp, err := c.Get(u)
	if err != nil {
		return errors.New("no internet connection")
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("download failed (%d)", resp.StatusCode)
	}
	f, err := os.Create(dst)
	if err != nil {
		return err
	}
	_, err = io.Copy(f, io.LimitReader(resp.Body, 50<<20))
	if cerr := f.Close(); err == nil {
		err = cerr
	}
	return err
}

// verifyMicrosoftSignature checks the downloaded file's Authenticode signature
// with Windows itself and that it was signed by Microsoft.
func verifyMicrosoftSignature(path string) error {
	ps := `$s = Get-AuthenticodeSignature -LiteralPath $env:RONIN_FILE; ` +
		`if ($s.Status -eq 'Valid' -and $s.SignerCertificate.Subject -like '*O=Microsoft Corporation*') { 'OK' } else { 'BAD' }`
	cmd := exec.Command("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", ps)
	cmd.Env = append(os.Environ(), "RONIN_FILE="+path)
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x08000000}
	out, err := cmd.Output()
	if err != nil || strings.TrimSpace(string(out)) != "OK" {
		return errors.New("the downloaded WebView2 installer is not signed by Microsoft, so it was not run")
	}
	return nil
}

func desktopDir() string {
	if p, err := windows.KnownFolderPath(windows.FOLDERID_Desktop, 0); err == nil && p != "" {
		return p
	}
	return filepath.Join(os.Getenv("USERPROFILE"), "Desktop")
}

// makeShortcut creates a .lnk file using Windows' own shortcut object.
func makeShortcut(lnk, target, args, desc, icon string) error {
	if icon == "" {
		icon = target
	}
	ps := `$w = New-Object -ComObject WScript.Shell; $s = $w.CreateShortcut($env:R_LNK); ` +
		`$s.TargetPath = $env:R_TARGET; $s.Arguments = $env:R_ARGS; $s.WorkingDirectory = $env:R_DIR; ` +
		`$s.IconLocation = $env:R_ICON + ',0'; $s.Description = $env:R_DESC; $s.Save()`
	cmd := exec.Command("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", ps)
	cmd.Env = append(os.Environ(), "R_LNK="+lnk, "R_TARGET="+target, "R_ARGS="+args, "R_DIR="+filepath.Dir(target), "R_DESC="+desc, "R_ICON="+icon)
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x08000000}
	return cmd.Run()
}

func registerUninstall(dir, exe, uninst string) error {
	k, _, err := registry.CreateKey(registry.CURRENT_USER, uninstallKey, registry.ALL_ACCESS)
	if err != nil {
		return err
	}
	defer k.Close()
	vals := map[string]string{
		"DisplayName":     appName,
		"DisplayVersion":  Version,
		"Publisher":       "Ronin",
		"DisplayIcon":     exe + ",0",
		"InstallLocation": dir,
		"UninstallString": `"` + uninst + `" --uninstall`,
	}
	for name, v := range vals {
		if err := k.SetStringValue(name, v); err != nil {
			return err
		}
	}
	_ = k.SetDWordValue("NoModify", 1)
	_ = k.SetDWordValue("NoRepair", 1)
	_ = k.SetDWordValue("EstimatedSize", uint32(len(roninExe)/1024))
	return nil
}

func uninstall() {
	if box("Remove Ronin Browser from this computer?\n\nClose all Ronin windows first.", mbYesNo|mbIconQ) != idYes {
		return
	}
	dir := installedDir()
	if dir == "" {
		self, _ := os.Executable()
		dir = filepath.Dir(self)
	}
	startMenu := filepath.Join(os.Getenv("APPDATA"), `Microsoft\Windows\Start Menu\Programs`)
	for _, p := range []string{
		filepath.Join(startMenu, "Ronin Browser.lnk"), filepath.Join(startMenu, "Ronin Safe Money.lnk"),
		filepath.Join(desktopDir(), "Ronin Browser.lnk"), filepath.Join(desktopDir(), "Ronin Safe Money.lnk"),
		filepath.Join(startMenu, "Ronin Mail.lnk"), filepath.Join(desktopDir(), "Ronin Mail.lnk"),
	} {
		_ = os.Remove(p)
	}
	_ = registry.DeleteKey(registry.CURRENT_USER, uninstallKey)
	for _, n := range []string{"Ronin.exe", "Ronin.exe.old", "Ronin.exe.new", "Ronin.exe.bad"} {
		_ = os.Remove(filepath.Join(dir, n))
	}
	if box("Also delete your Ronin data on this computer (settings, My Banks list, saved website logins)?", mbYesNo|mbIconQ) == idYes {
		_ = os.RemoveAll(dataDir())
	}
	// This program is running from the install folder, so let Windows delete
	// it (and the folder) a few seconds after it exits. Only a folder named
	// exactly "Ronin Browser" is ever removed.
	if strings.EqualFold(filepath.Base(dir), installFolderName) && !strings.ContainsAny(dir, `"&|<>^%`) {
		cmd := exec.Command("cmd.exe", "/C", "ping 127.0.0.1 -n 4 > nul & rmdir /S /Q \""+dir+"\"")
		cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x08000000}
		_ = cmd.Start()
	}
	box("Ronin Browser has been removed.", mbOK|mbIconInfo)
}

// installIcon writes this version's logo as "<install folder>\icons\ronin-<version>.ico"
// (older versions' icon files there are removed). "" = use Ronin.exe's own icon.
func installIcon(dir string) string {
	d := filepath.Join(dir, "icons")
	if err := os.MkdirAll(d, 0o755); err != nil {
		return ""
	}
	name := "ronin-" + strings.NewReplacer(".", "-", " ", "-").Replace(Version) + ".ico"
	if old, _ := filepath.Glob(filepath.Join(d, "ronin-*.ico")); len(old) > 0 {
		for _, f := range old {
			if filepath.Base(f) != name {
				_ = os.Remove(f)
			}
		}
	}
	p := filepath.Join(d, name)
	if err := os.WriteFile(p, iconData, 0o644); err != nil {
		return ""
	}
	return p
}

var pSHChangeNotify = windows.NewLazySystemDLL("shell32.dll").NewProc("SHChangeNotify")

// refreshIcons tells Explorer that icons changed, so the desktop shows the new logo at once.
func refreshIcons(paths ...string) {
	const shcneUpdateItem, shcneAssocChanged, shcnfPathW, shcnfIDList, shcnfFlush = 0x2000, 0x08000000, 0x0005, 0x0000, 0x1000
	for _, p := range paths {
		if u, err := windows.UTF16PtrFromString(p); err == nil {
			pSHChangeNotify.Call(shcneUpdateItem, shcnfPathW|shcnfFlush, uintptr(unsafe.Pointer(u)), 0)
		}
	}
	pSHChangeNotify.Call(shcneAssocChanged, shcnfIDList|shcnfFlush, 0, 0)
	cmd := exec.Command(filepath.Join(os.Getenv("SystemRoot"), "System32", "ie4uinit.exe"), "-show")
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x08000000}
	_ = cmd.Run()
}
