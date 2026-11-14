//go:build windows

// Ronin Browser 3 — a Windows browser built on Microsoft WebView2.
package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime/debug"
	"sort"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
	"unsafe"

	"github.com/wailsapp/go-webview2/webviewloader"
	"golang.org/x/sys/windows"

	"roninbrowser/internal/containers"
	"roninbrowser/internal/focus"
	"roninbrowser/internal/safemoney"
	"roninbrowser/internal/settings"
	"roninbrowser/internal/updater"
)

// Version is set at build time with -ldflags "-X main.Version=3.0.0".
var Version = "3.0.0"

type settingsT = settings.Settings

var (
	dataDir     string
	store       *settings.Store
	logMu       sync.Mutex
	sessionDirs = map[containers.Container]string{}
)

func main() {
	for _, a := range os.Args[1:] {
		if a == "--self-test" { // used by Ronin Updater before switching versions
			os.Exit(selfTest())
		}
		if a == "--version" {
			fmt.Println(Version)
			return
		}
	}

	// A restart (after switching the VPN) waits for the old Ronin to close.
	for i, a := range os.Args[1:] {
		if a == "--wait-pid" && i+2 < len(os.Args) {
			if pid, err := strconv.ParseUint(os.Args[i+2], 10, 32); err == nil {
				waitForPid(uint32(pid))
			}
		}
	}
	// Only one Ronin runs at a time: a second start hands its request to
	// the first one and ends.
	if handedOver := singleInstance(os.Args[1:]); handedOver {
		return
	}

	base := os.Getenv("LOCALAPPDATA")
	if base == "" {
		base = os.TempDir()
	}
	dataDir = filepath.Join(base, "RoninBrowser")
	_ = os.MkdirAll(filepath.Join(dataDir, "profiles"), 0o755)
	store = settings.Open(dataDir)
	initHistory()
	logf("Ronin Browser %s starting", Version)
	// If Ronin ever stops because of an error, the details are written here
	// (only on this computer, never sent anywhere).
	crashLog := filepath.Join(dataDir, "crash.log")
	if st, err := os.Stat(crashLog); err == nil && st.Size() > 256*1024 {
		_ = os.Remove(crashLog)
	}
	if f, err := os.OpenFile(crashLog, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o600); err == nil {
		fmt.Fprintf(f, "--- Ronin %s started %s\n", Version, time.Now().Format("2006-01-02 15:04:05"))
		if err := debug.SetCrashOutput(f, debug.CrashOptions{}); err != nil {
			f.Close()
		}
	}

	if err := initWin32(); err != nil {
		messageBox(0, "Ronin Browser", "Windows could not create the Ronin window: "+err.Error(), mbIconError)
		return
	}
	if v, err := webviewloader.GetAvailableCoreWebView2BrowserVersionString(""); err != nil || v == "" {
		messageBox(0, "Ronin Browser",
			"Ronin needs the Microsoft WebView2 engine, which is part of Windows 10 and 11.\n\n"+
				"It was not found on this computer. Please run Ronin Setup again: it installs the engine for you.",
			mbIconWarning)
		return
	}

	cleanupOldSessions()
	loadFilterLists()
	if kidsLevel() != "" {
		loadKidsLists()
	}
	initVPN()
	go backgroundUpdater()

	c, start, explicit := parseStart(os.Args[1:])
	restore := !explicit && wantRestore()
	if !restore {
		_ = os.Remove(sessionPath()) // old tabs from an earlier run are forgotten
	}
	switch {
	case kidsLevel() != "":
		openContainer(containers.Kids, "") // this Windows account is a Kids account
	case restore && restoreSession():
		// Ronin restarted itself (for example after a VPN change): the tabs are back.
		// A normal start always begins with a fresh tab.
	default:
		openContainer(c, start)
	}
	if len(windowsByHwnd) == 0 {
		return
	}
	runMessageLoop()
	if hist != nil {
		_ = hist.Save()
	}
	logf("Ronin Browser closed")
}

func selfTest() int {
	if toolbarHTML(containers.Personal) == "" || newTabHTML(containers.Banking) == "" {
		return 1
	}
	if containers.Route(containers.Personal, containers.Nav{URL: "https://sbi.bank.in/", UserInitiated: true}, safemoney.Lists{}).Target != containers.Banking {
		return 2
	}
	return 0
}

// logf writes a short line to ronin.log. Addresses are never written with
// their query part, and the file is kept small.
func logf(format string, args ...any) {
	logMu.Lock()
	defer logMu.Unlock()
	if dataDir == "" {
		return
	}
	p := filepath.Join(dataDir, "ronin.log")
	if st, err := os.Stat(p); err == nil && st.Size() > 512*1024 {
		_ = os.Rename(p, p+".1")
	}
	f, err := os.OpenFile(p, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o644)
	if err != nil {
		return
	}
	defer f.Close()
	line := fmt.Sprintf(format, args...)
	if i := strings.IndexAny(line, "?#"); i >= 0 && strings.Contains(line, "://") {
		line = line[:i] + " [...]"
	}
	fmt.Fprintf(f, "%s %s\n", time.Now().Format("2006-01-02 15:04:05"), line)
}

// ---- profile folders ---------------------------------------------------------

// profileDir is the data folder of a container. Safe Money (unless "remember
// my bank login" is on) and Private get a brand-new empty folder for every
// session, so nothing from an earlier session survives.
func profileDir(c containers.Container) string {
	if d, ok := sessionDirs[c]; ok {
		return d
	}
	name := string(c)
	fresh := c == containers.Private || (c == containers.Banking && !store.Get().RememberBankLogin)
	if fresh {
		name = fmt.Sprintf("%s-s%d", c, time.Now().UnixNano())
	}
	d := filepath.Join(dataDir, "profiles", name)
	sessionDirs[c] = d
	return d
}

// newSession forgets the current Safe Money / Private folder so the next
// window starts empty; called when the last such window closes.
func newSession(c containers.Container) {
	if c == containers.Banking || c == containers.Private {
		delete(sessionDirs, c)
		go cleanupOldSessions()
	}
}

// cleanupOldSessions deletes old session folders that are no longer in use.
func cleanupOldSessions() {
	root := filepath.Join(dataDir, "profiles")
	entries, err := os.ReadDir(root)
	if err != nil {
		return
	}
	inUse := map[string]bool{}
	runOnUIWait(func() {
		for _, d := range sessionDirs {
			inUse[filepath.Base(d)] = true
		}
	})
	for _, e := range entries {
		n := e.Name()
		if !e.IsDir() || inUse[n] {
			continue
		}
		if strings.HasPrefix(n, string(containers.Banking)+"-s") || strings.HasPrefix(n, string(containers.Private)+"-s") {
			_ = os.RemoveAll(filepath.Join(root, n)) // fails quietly while still in use; retried later
		}
	}
	if store.Get().RememberBankLogin == false {
		_ = os.RemoveAll(filepath.Join(root, string(containers.Banking)))
	}
}

// runOnUIWait runs f on the UI thread and waits (or runs directly before
// the message window exists).
func runOnUIWait(f func()) {
	if msgHwnd == 0 || windows.GetCurrentThreadId() == uiThreadID {
		f()
		return
	}
	done := make(chan struct{})
	runOnUI(func() { f(); close(done) })
	select {
	case <-done:
	case <-time.After(5 * time.Second):
	}
}

var uiThreadID = windows.GetCurrentThreadId()

// ---- system checks -----------------------------------------------------------

func processNames() []string {
	snap, err := windows.CreateToolhelp32Snapshot(windows.TH32CS_SNAPPROCESS, 0)
	if err != nil {
		return nil
	}
	defer windows.CloseHandle(snap)
	var e windows.ProcessEntry32
	e.Size = uint32(unsafe.Sizeof(e))
	var names []string
	for err = windows.Process32First(snap, &e); err == nil; err = windows.Process32Next(snap, &e) {
		names = append(names, windows.UTF16ToString(e.ExeFile[:]))
	}
	return names
}

// antivirusOn asks Windows Security Center whether any antivirus is on.
func antivirusOn() (on bool, known bool) {
	cmd := exec.Command("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
		"Get-CimInstance -Namespace root/SecurityCenter2 -ClassName AntivirusProduct | ForEach-Object { $_.productState }")
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x08000000} // CREATE_NO_WINDOW
	out, err := runWithTimeout(cmd, 20*time.Second)
	if err != nil {
		return false, false
	}
	var states []uint32
	for _, f := range strings.Fields(string(out)) {
		if n, err := strconv.ParseUint(f, 10, 32); err == nil {
			states = append(states, uint32(n))
		}
	}
	return safemoney.AntivirusEnabled(states)
}

func runWithTimeout(cmd *exec.Cmd, d time.Duration) ([]byte, error) {
	type res struct {
		b   []byte
		err error
	}
	ch := make(chan res, 1)
	go func() { b, err := cmd.Output(); ch <- res{b, err} }()
	select {
	case r := <-ch:
		return r.b, r.err
	case <-time.After(d):
		if cmd.Process != nil {
			_ = cmd.Process.Kill()
		}
		return nil, fmt.Errorf("timed out")
	}
}

// ---- data for the panels -------------------------------------------------------

func settingsData() map[string]any {
	s := store.Get()
	_, confErr := os.Stat(wgConfPath())
	vs, vp := vpnStatus()
	return map[string]any{
		"banks": s.UserBanks, "trusted": s.TrustedSites, "remember": s.RememberBankLogin, "dataSites": s.DataSites,
		"idle": s.SafeMoneyIdleMins, "version": Version,
		"tradingSites": s.TradingSites, "adblockOff": s.AdblockOff, "sitePerms": sitePermsData(), "newTabFront": s.NewTabFront,
		"vpn": map[string]any{
			"on": s.VPNOn, "name": fmt.Sprintf("%d server(s) - Ronin switches automatically if one stops", len(vpnConfigs())), "haveConf": confErr == nil, "active": vpnActive,
			"state": vs, "problem": vp, "all": s.VPNAll,
			"needRestart": (s.VPNOn != vpnActive && (confErr == nil || !s.VPNOn)) || (vpnActive && s.VPNAll != vpnAll),
		},
		"ai": map[string]any{
			"provider": s.AIProvider, "model": s.AIModel, "baseUrl": s.AIBaseURL, "cap": s.AIDailyCapRupees,
			"spent": fmt.Sprintf("%.2f", spentToday()),
			"keys":  map[string]string{"gemini": maskKey(loadKey("gemini")), "claude": maskKey(loadKey("claude")), "custom": maskKey(loadKey("custom"))},
		},
		"kids": map[string]any{"level": s.KidsLevel, "sites": s.KidsSites},
		"focus": map[string]any{"on": s.FocusOn, "from": s.FocusFrom, "to": s.FocusTo,
			"sites": strings.Join(map[bool][]string{true: s.FocusSites, false: focus.DefaultSites}[len(s.FocusSites) > 0], "\n")},
	}
}

func healthData() map[string]any {
	engine, _ := webviewloader.GetAvailableCoreWebView2BrowserVersionString("")
	st := updater.LoadState(filepath.Join(dataDir, "update-state.json"))
	upd := "Ronin app updates are not set up yet (they will be switched on with the first online release)."
	if store.Get().UpdateChannel != "" {
		switch {
		case st.LastCheck.IsZero():
			upd = "Waiting for the first update check."
		case st.LastError != "":
			upd = "Last update check failed (" + st.LastError + "). Ronin will try again within an hour."
		default:
			upd = "Up to date. Last checked " + st.LastCheck.Local().Format("02 Jan 15:04") + "."
		}
		if st.PendingNote != "" {
			upd = st.PendingNote
		}
	}
	stateMu.Lock()
	full := updater.LoadState(statePath())
	stateMu.Unlock()
	var parts []map[string]any
	known := map[string]bool{"yt-dlp": true, "deno": true, "ffmpeg": true, "wireproxy": true, "list:top-sites": true}
	for _, l := range append(append([]filterList{}, filterLists...), kidsLists...) {
		known["list:"+l.name] = true
	}
	for name, c := range full.Components {
		if !known[name] {
			continue // retired lists and internal records are not shown
		}
		when := ""
		if !c.Updated.IsZero() {
			when = c.Updated.Local().Format("02 Jan 15:04")
		}
		parts = append(parts, map[string]any{"name": name, "ok": c.OK, "when": when, "problem": c.Problem})
	}
	sort.Slice(parts, func(i, j int) bool { return parts[i]["name"].(string) < parts[j]["name"].(string) })
	vs, vp := vpnStatus()
	vpnText := "Off"
	if vpnActive {
		vpnText = vs
		if s := smartVPN.Load(); s != nil && !vpnAll {
			vpnText += fmt.Sprintf(" (smart: %d trusted sites go direct)", s.Size())
		}
		if vp != "" {
			vpnText += " - " + vp
		}
	}
	return map[string]any{
		"parts": parts, "vpn": vpnText,
		"version": Version, "engine": engine,
		"engineNote": "The engine is updated automatically by Microsoft (same security fixes as Microsoft Edge).",
		"updates":    upd,
	}
}

// parseStart reads which window and address a start request asks for.
func parseStart(args []string) (c containers.Container, start string, explicit bool) {
	c = containers.Personal
	for _, a := range args {
		if strings.HasPrefix(a, "http://") || strings.HasPrefix(a, "https://") {
			start = a
		}
	}
	explicit = start != ""
	for _, a := range args {
		switch a {
		case "--safemoney":
			c, explicit = containers.Banking, true
		case "--trading":
			c, explicit = containers.Trading, true
		case "--calls":
			c, explicit = containers.Calls, true
		case "--private":
			c, explicit = containers.Private, true
		}
	}
	if start != "" {
		// Links opened from other programs also go through Safe Money rules.
		d := containers.Route(containers.Personal, containers.Nav{URL: start, UserInitiated: true}, store.Lists())
		if d.Action == containers.Move {
			c = d.Target
		}
	}
	return c, start, explicit
}

// ---- single instance ---------------------------------------------------------

const wmCopyData = 0x004A

type copyData struct {
	dwData uintptr
	cbData uint32
	lpData uintptr
}

var (
	pFindWindowExW            = user32.NewProc("FindWindowExW")
	pSendMessageTimeoutW      = user32.NewProc("SendMessageTimeoutW")
	pAllowSetForegroundWindow = user32.NewProc("AllowSetForegroundWindow")
	pGetWindowThreadProcessId = user32.NewProc("GetWindowThreadProcessId")
	instanceMutex             windows.Handle
)

func findRoninMsgWindow() uintptr {
	h, _, _ := pFindWindowExW.Call(hwndMessage, 0, uintptr(unsafe.Pointer(utf16(msgClass))), 0)
	return h
}

// singleInstance returns true when another Ronin is already running and
// this start was handed over to it (or the user was told what to do).
func singleInstance(args []string) bool {
	name, _ := windows.UTF16PtrFromString("Local\\RoninBrowser3-SingleInstance")
	h, err := windows.CreateMutex(nil, false, name)
	if err == nil {
		instanceMutex = h
		// An older Ronin (before 3.1.3) does not hold the mutex but may still be open.
		if findRoninMsgWindow() != 0 {
			messageBox(0, "Ronin Browser", "An older Ronin window is still open (it may be a Ronin Safe Money window).\n\nClose all Ronin windows, then open Ronin again.", mbIconWarning)
			return true
		}
		return false
	}
	if err != windows.ERROR_ALREADY_EXISTS {
		return false // cannot tell; start normally
	}
	if h != 0 {
		windows.CloseHandle(h)
	}
	var target uintptr
	for i := 0; i < 40 && target == 0; i++ { // the first Ronin may still be starting
		if target = findRoninMsgWindow(); target == 0 {
			time.Sleep(250 * time.Millisecond)
		}
	}
	if target == 0 {
		messageBox(0, "Ronin Browser", "Ronin is already running but is not answering.\n\nOpen Task Manager (Ctrl+Shift+Esc), end \"Ronin\", then open Ronin again.", mbIconWarning)
		return true
	}
	var pid uint32
	pGetWindowThreadProcessId.Call(target, uintptr(unsafe.Pointer(&pid)))
	pAllowSetForegroundWindow.Call(uintptr(pid))
	payload := []byte(strings.Join(args, "\n"))
	if len(payload) == 0 {
		payload = []byte("\n")
	}
	cd := copyData{dwData: 0x524F4E31, cbData: uint32(len(payload)), lpData: uintptr(unsafe.Pointer(&payload[0]))}
	var result uintptr
	pSendMessageTimeoutW.Call(target, wmCopyData, 0, uintptr(unsafe.Pointer(&cd)), 0x0002, 10000, uintptr(unsafe.Pointer(&result)))
	return true
}

// onSecondStart runs in the first Ronin when the user starts Ronin again.
func onSecondStart(args []string) {
	if kidsLevel() != "" {
		openContainer(containers.Kids, "")
		return
	}
	c, start, explicit := parseStart(args)
	if !explicit {
		newWindow(containers.Personal, "")
		return
	}
	openContainer(c, start)
}

// wantRestore: old tabs come back only when Ronin restarted itself.
func wantRestore() bool {
	for _, a := range os.Args[1:] {
		if a == "--restore" {
			return true
		}
	}
	return false
}
