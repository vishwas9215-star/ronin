//go:build windows

package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"roninbrowser/internal/containers"
	"roninbrowser/internal/kids"
	"roninbrowser/internal/session"
	"roninbrowser/internal/updater"
	"roninbrowser/internal/vpn"

	"golang.org/x/sys/windows"
)

// vpnActive is decided once when Ronin starts: the engine's proxy setting
// cannot change while a window is open, so switching needs a restart.
var (
	vpnAll     bool                      // decided at start: every site through the VPN
	smartVPN   atomic.Pointer[vpn.Smart] // smart mode: which sites use the VPN
	pacURL     string                    // where the engine reads the smart rules
	vpnActive  bool
	vpnMu      sync.Mutex
	vpnState   = "off" // off, starting, connected, problem
	vpnProblem string
)

func vpnDir() string        { return filepath.Join(dataDir, "vpn") }
func wgConfPath() string    { return filepath.Join(vpnDir(), "wg.conf") }
func proxyConfPath() string { return filepath.Join(vpnDir(), "wireproxy.conf") }

func usesVPN(c containers.Container) bool {
	return vpnActive && (c == containers.Personal || c == containers.Private)
}

// initVPN runs at start-up.
func initVPN() {
	if !store.Get().VPNOn {
		return
	}
	if _, err := os.Stat(wgConfPath()); err != nil {
		return
	}
	vpnActive = true // kill switch: from now on these windows only go through the tunnel
	vpnAll = store.Get().VPNAll
	if !vpnAll {
		if kidsLevel() == "" {
			loadKidsLists() // the adult and betting lists mark sensitive sites
		}
		buildSmartVPN()
		if err := startPACServer(); err != nil {
			logf("vpn: smart rules could not start (%v); every site uses the VPN", err)
			vpnAll = true
		}
	}
	setVPNState("starting", "")
	go runVPN()
}

// ---- smart VPN: only unknown and sensitive sites use the VPN -----------------

func topSitesPath() string { return listPath("top-sites") }

// buildSmartVPN makes the rules from the built-in trusted sites, the list of
// the most visited sites and the adult/betting lists.
func buildSmartVPN() {
	var top []string
	if b, err := os.ReadFile(topSitesPath()); err == nil {
		top = vpn.ParseTopList(bytes.NewReader(b), 10000)
	}
	sens := func(h string) bool {
		if kids.LooksAdult("https://" + h + "/") {
			return true
		}
		if e := kidsBlocker.Load(); e != nil && e.Blocked("https://"+h+"/") {
			return true
		}
		return false
	}
	smartVPN.Store(vpn.NewSmart(top, sens))
}

// startPACServer serves the rules to the engine on this computer only.
func startPACServer() error {
	var ln net.Listener
	var err error
	for p := vpn.Port + 1; p <= vpn.Port+6; p++ {
		if ln, err = net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", p)); err == nil {
			break
		}
	}
	if err != nil {
		return err
	}
	pacURL = "http://" + ln.Addr().String() + "/ronin.pac"
	mux := http.NewServeMux()
	mux.HandleFunc("/ronin.pac", func(w http.ResponseWriter, r *http.Request) {
		s := smartVPN.Load()
		if s == nil {
			http.Error(w, "not ready", http.StatusServiceUnavailable)
			return
		}
		w.Header().Set("Content-Type", "application/x-ns-proxy-autoconfig")
		w.Header().Set("Cache-Control", "no-store")
		_, _ = w.Write([]byte(s.PAC()))
	})
	go func() { _ = http.Serve(ln, mux) }()
	return nil
}

// vpnForURL reports whether a page in container c goes through the VPN.
func vpnForURL(c containers.Container, u string) bool {
	if !usesVPN(c) {
		return false
	}
	if vpnAll || c == containers.Private {
		return true
	}
	s := smartVPN.Load()
	return s == nil || s.UsesVPN(hostOf(u))
}

// updateTopSites downloads the list of the world's most visited sites
// (Tranco, free) once a week.
func updateTopSites() {
	if !store.Get().VPNOn {
		return
	}
	stateMu.Lock()
	st := updater.LoadState(statePath())
	stateMu.Unlock()
	key := "list:top-sites"
	if c := st.Components[key]; !c.Updated.IsZero() && time.Since(c.Updated) < 7*24*time.Hour {
		return
	}
	problem := ""
	b, err := fetch("https://tranco-list.eu/api/lists/date/latest", 64<<10)
	var meta struct {
		ID string `json:"list_id"`
	}
	if err == nil {
		err = json.Unmarshal(b, &meta)
	}
	if err == nil && !validListID(meta.ID) {
		err = errors.New("unexpected answer")
	}
	if err == nil {
		b, err = fetch("https://tranco-list.eu/download/"+meta.ID+"/10000", 4<<20)
	}
	if err == nil {
		if n := len(vpn.ParseTopList(bytes.NewReader(b), 10000)); n < 5000 {
			err = errors.New("download looked incomplete; kept the previous list")
		} else if werr := os.WriteFile(topSitesPath()+".tmp", b, 0o644); werr == nil {
			_ = os.Rename(topSitesPath()+".tmp", topSitesPath())
		}
	}
	if err != nil {
		problem = err.Error()
	}
	recordComponent(key, func(c *updater.Component) { c.Updated, c.OK, c.Problem = time.Now(), problem == "", problem })
}

func validListID(id string) bool {
	if id == "" || len(id) > 20 {
		return false
	}
	for _, r := range id {
		if !(r >= 'A' && r <= 'Z' || r >= 'a' && r <= 'z' || r >= '0' && r <= '9') {
			return false
		}
	}
	return true
}

func setVPNState(s, problem string) {
	vpnMu.Lock()
	vpnState, vpnProblem = s, problem
	vpnMu.Unlock()
}

func vpnStatus() (string, string) {
	vpnMu.Lock()
	defer vpnMu.Unlock()
	return vpnState, vpnProblem
}

// runVPN keeps wireproxy running for as long as Ronin runs.
func runVPN() {
	defer func() {
		if r := recover(); r != nil {
			logf("vpn panic: %v", r)
		}
	}()
	if err := ensureTool(wireproxyTool, nil); err != nil {
		logf("vpn tool: %v", err)
		setVPNState("problem", "The VPN program could not be downloaded. Check the internet; Ronin will try again.")
		time.Sleep(time.Minute)
		go runVPN()
		return
	}
	idx, failures := 0, 0
	if b, err := os.ReadFile(filepath.Join(vpnDir(), "last-good")); err == nil {
		fmt.Sscan(string(b), &idx) // the server that worked last time goes first
	}
	if idx < 0 {
		idx = 0
	}
	for {
		confs := vpnConfigs()
		if len(confs) == 0 {
			setVPNState("problem", "No VPN config. Import one in Settings.")
			return
		}
		if idx >= len(confs) {
			idx = 0
		}
		// a copy with the safe packet size (the user's file is left as it is)
		active := filepath.Join(vpnDir(), "active.conf")
		raw, rerr := os.ReadFile(confs[idx])
		if rerr != nil || os.WriteFile(active, []byte(vpn.PrepareConf(string(raw))), 0o600) != nil {
			active = confs[idx]
		}
		if err := os.WriteFile(proxyConfPath(), []byte(vpn.WireproxyConfig(active)), 0o600); err != nil {
			setVPNState("problem", "Could not prepare the VPN.")
			return
		}
		cmd := hiddenCmd(filepath.Join(toolsDir(), "wireproxy.exe"), "-c", proxyConfPath())
		// the VPN program's own messages of the latest run, for finding problems
		lf, _ := os.OpenFile(filepath.Join(vpnDir(), "wireproxy.log"), os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o600)
		if lf != nil {
			cmd.Stdout, cmd.Stderr = lf, lf
		}
		if err := cmd.Start(); err != nil {
			setVPNState("problem", "The VPN program could not start.")
			return
		}
		attachToJob(cmd.Process)
		if lf != nil {
			lf.Close()
		}
		started := time.Now()
		stop := make(chan struct{})
		go watchVPN(cmd, stop, idx)
		_ = cmd.Wait()
		close(stop)
		if time.Since(started) > 10*time.Minute {
			failures = 0 // it worked for a good while; this is a fresh problem
		}
		failures++
		logf("vpn: server %d of %d stopped", idx+1, len(confs))
		if len(confs) > 1 {
			idx = vpn.Next(idx, len(confs))
			setVPNState("problem", "This VPN server stopped answering. Switching to your next server...")
		} else {
			setVPNState("problem", "The VPN stopped. Reconnecting... (pages in normal windows wait until it is back)")
		}
		wait := time.Duration(failures*3) * time.Second
		if wait > time.Minute {
			wait = time.Minute
		}
		time.Sleep(wait)
	}
}

// vpnConfigs lists the imported server configs: wg.conf, wg2.conf ... wg5.conf.
func vpnConfigs() []string {
	var out []string
	for i := 1; i <= maxVPNConfigs; i++ {
		p := vpnConfPath(i)
		if st, err := os.Stat(p); err == nil && st.Size() > 0 {
			out = append(out, p)
		}
	}
	return out
}

const maxVPNConfigs = 5

func vpnConfPath(i int) string {
	if i <= 1 {
		return wgConfPath()
	}
	return filepath.Join(vpnDir(), fmt.Sprintf("wg%d.conf", i))
}

// watchVPN marks the VPN connected once the tunnel really carries traffic,
// then checks it every 30 seconds. After three failed checks in a row it
// stops the program, and runVPN moves on to the next server.
func watchVPN(cmd *exec.Cmd, stop chan struct{}, idx int) {
	addr := fmt.Sprintf("127.0.0.1:%d", vpn.Port)
	bad := 0
	connected := false
	started := time.Now()
	for i := 0; ; i++ {
		delay := 30 * time.Second
		if !connected {
			delay = 2 * time.Second
		}
		select {
		case <-stop:
			return
		case <-time.After(delay):
		}
		// a secure (https) connection, so a tunnel that only lets small
		// packets through is found too
		err := vpn.SocksCheck(addr, "www.gstatic.com:443", 10*time.Second)
		if err != nil {
			err = vpn.SocksCheck(addr, "www.cloudflare.com:443", 10*time.Second)
		}
		if err == nil {
			bad = 0
			if !connected {
				connected = true
				// start with this server next time
				_ = os.WriteFile(filepath.Join(vpnDir(), "last-good"), []byte(fmt.Sprint(idx)), 0o600)
			}
			setVPNState("connected", "")
			continue
		}
		bad++
		if connected || bad >= 3 {
			setVPNState("problem", "The VPN server is not answering.")
		}
		// a server that never worked gets about 45 seconds; a working one
		// that stops answering is left after 3 failed checks
		if (connected && bad >= 3) || (!connected && time.Since(started) > 45*time.Second) {
			logf("vpn: tunnel check failed: %v", err)
			if cmd.Process != nil {
				_ = cmd.Process.Kill()
			}
			return
		}
	}
}

// importVPNConfig asks the user for a WireGuard .conf file and saves it.
func importVPNConfig() (string, error) {
	ps := `Add-Type -AssemblyName System.Windows.Forms; $d = New-Object System.Windows.Forms.OpenFileDialog; ` +
		`$d.Title = 'Choose your WireGuard config (.conf) file'; $d.Filter = 'WireGuard config (*.conf)|*.conf|All files (*.*)|*.*'; ` +
		`if ($d.ShowDialog() -eq 'OK') { [Console]::Out.Write($d.FileName) }`
	cmd := exec.Command("powershell.exe", "-NoProfile", "-NonInteractive", "-STA", "-Command", ps)
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: createNoWindow}
	out, err := runWithTimeout(cmd, 10*time.Minute)
	if err != nil {
		return "", errors.New("the file window could not be opened")
	}
	path := strings.TrimSpace(string(out))
	if path == "" {
		return "", nil // cancelled
	}
	st, err := os.Stat(path)
	if err != nil || st.Size() > 64*1024 {
		return "", errors.New("that file is not a WireGuard config")
	}
	b, err := os.ReadFile(path)
	if err != nil {
		return "", err
	}
	info, err := vpn.Check(string(b))
	if err != nil {
		return "", err
	}
	if err := os.MkdirAll(vpnDir(), 0o700); err != nil {
		return "", err
	}
	// Same server again replaces it; a new server is added (up to 5).
	slot := 0
	for i := 1; i <= maxVPNConfigs; i++ {
		old, err := os.ReadFile(vpnConfPath(i))
		if err != nil || len(old) == 0 {
			if slot == 0 {
				slot = i
			}
			continue
		}
		if oi, err := vpn.Check(string(old)); err == nil && oi.Endpoint == info.Endpoint {
			slot = i
			break
		}
	}
	if slot == 0 {
		return "", errors.New("you already have 5 VPN servers; that is the limit")
	}
	if err := os.WriteFile(vpnConfPath(slot), b, 0o600); err != nil {
		return "", err
	}
	n := len(vpnConfigs())
	name := fmt.Sprintf("%d server", n)
	if n != 1 {
		name += "s (Ronin switches automatically if one stops)"
	}
	_ = store.Update(func(s *settingsT) { s.VPNName = name })
	return name, nil
}

// ---- restart (needed after switching the VPN) --------------------------------

// restartRonin saves the open tabs, starts a new Ronin that waits for this
// one to finish, and closes this one.
func restartRonin() {
	saveSession(nil)
	exe, err := os.Executable()
	if err != nil {
		return
	}
	cmd := exec.Command(exe, "--restore", "--wait-pid", fmt.Sprint(os.Getpid()))
	if err := cmd.Start(); err != nil {
		return
	}
	quitting = true
	for _, w := range windowsSnapshot() {
		w.close()
	}
}

// waitForPid waits (up to 20 s) for an earlier Ronin to finish closing.
func waitForPid(pid uint32) {
	h, err := windows.OpenProcess(windows.SYNCHRONIZE, false, pid)
	if err != nil {
		return
	}
	defer windows.CloseHandle(h)
	_, _ = windows.WaitForSingleObject(h, 20000)
}

var _ = session.Session{}
