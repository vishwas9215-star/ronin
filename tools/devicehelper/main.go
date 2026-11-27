// Ronin device helper (made by Claude for the owner's laptop).
//
// The owner double-clicks "6-claude-device-helper.bat" once. This program then keeps his phone and tablet
// connected to the laptop over Wi-Fi (adb) and runs ONLY the small, fixed list of jobs below, which Claude
// drops as files into qa\jobs\. Results go to qa\results\. It never runs any other command, never changes
// a phone setting, never uninstalls, never touches the TV, and logs every job it runs (qa\helper-log.txt).
//
// Jobs (one JSON file each):
//   {"id":"t1","do":"info","device":"phone"}
//   {"id":"t2","do":"install","device":"tablet","apk":"Ronin-agent-test.apk"}      (apk only from qa\apks\)
//   {"id":"t3","do":"test","device":"tablet","pkg":"com.ronin.browser","class":"com.ronin.browser.AgentDeviceTest"}
//   {"id":"t4","do":"screenshot","device":"phone"}
//   {"id":"t5","do":"log","device":"phone"}           (only Ronin's own "RoninAgent" log lines)
//   {"id":"t6","do":"launch","device":"tablet","pkg":"com.ronin.browser"}
//   {"id":"t7","do":"uninstall","device":"tablet","pkg":"com.ronin.agentlab"}  (only the pilot's own test app)
package main

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"time"
)

const tabletModel = "OPD2415" // the OnePlus Pad; anything else that is not a TV is "phone"

type Job struct {
	ID     string `json:"id"`
	Do     string `json:"do"`
	Device string `json:"device"`
	APK    string `json:"apk"`
	Pkg    string `json:"pkg"`
	Class  string `json:"class"`
}

var (
	reID    = regexp.MustCompile(`^[A-Za-z0-9_-]{1,40}$`)
	reAPK   = regexp.MustCompile(`^[A-Za-z0-9._-]{1,80}\.apk$`)
	reClass = regexp.MustCompile(`^com\.ronin\.[A-Za-z0-9_.]+(#[A-Za-z0-9_]+)?$`)
	pkgs    = map[string]bool{"com.ronin.browser": true, "com.ronin.phone": true, "com.ronin.agentlab": true}
)

// Validate checks a job against the fixed allow-list. Anything else is refused.
func (j Job) Validate() error {
	if !reID.MatchString(j.ID) {
		return errors.New("bad id")
	}
	if j.Device != "phone" && j.Device != "tablet" {
		return errors.New("device must be phone or tablet")
	}
	switch j.Do {
	case "info", "screenshot", "log":
		return nil
	case "install":
		if !reAPK.MatchString(j.APK) {
			return errors.New("bad apk name")
		}
		return nil
	case "uninstall": // ONLY the pilot test app (never Ronin Browser / Ronin Phone or anything else)
		if j.Pkg != "com.ronin.agentlab" {
			return errors.New("only the lab test app may be removed")
		}
		return nil
	case "launch":
		if !pkgs[j.Pkg] {
			return errors.New("unknown package")
		}
		return nil
	case "test":
		if !pkgs[j.Pkg] || !reClass.MatchString(j.Class) {
			return errors.New("bad test package or class")
		}
		return nil
	}
	return errors.New("job type not allowed")
}

// Args builds the adb arguments for a job (serial already chosen). apkDir is qa\apks.
func (j Job) Args(serial, apkDir string) [][]string {
	s := []string{"-s", serial}
	switch j.Do {
	case "info":
		return [][]string{
			append(append([]string{}, s...), "shell", "getprop", "ro.product.model"),
			append(append([]string{}, s...), "shell", "getprop", "ro.build.version.release"),
			append(append([]string{}, s...), "shell", "dumpsys", "package", "com.google.android.webview"),
			append(append([]string{}, s...), "shell", "dumpsys", "package", "com.ronin.browser"),
			append(append([]string{}, s...), "shell", "dumpsys", "battery"),
		}
	case "install":
		return [][]string{append(append([]string{}, s...), "install", "-r", "-t", filepath.Join(apkDir, j.APK))}
	case "uninstall":
		return [][]string{append(append([]string{}, s...), "uninstall", "com.ronin.agentlab")}
	case "launch":
		return [][]string{append(append([]string{}, s...), "shell", "monkey", "-p", j.Pkg, "-c", "android.intent.category.LAUNCHER", "1")}
	case "test":
		return [][]string{append(append([]string{}, s...), "shell", "am", "instrument", "-w", "-r", "-e", "class", j.Class, j.Pkg+".test/androidx.test.runner.AndroidJUnitRunner")}
	case "screenshot":
		return [][]string{append(append([]string{}, s...), "exec-out", "screencap", "-p")}
	case "log":
		return [][]string{append(append([]string{}, s...), "logcat", "-d", "-v", "time", "-s", "RoninAgent:*", "TestRunner:*")}
	}
	return nil
}

// Keep only the lines of "dumpsys" output that matter (version names), so no other app data is written.
func trimInfo(out string) string {
	var keep []string
	for _, l := range strings.Split(out, "\n") {
		t := strings.TrimSpace(l)
		if strings.HasPrefix(t, "versionName=") || strings.HasPrefix(t, "level:") || strings.HasPrefix(t, "AC powered:") || strings.HasPrefix(t, "status:") {
			keep = append(keep, t)
		}
	}
	if len(keep) > 3 {
		keep = keep[:3]
	}
	return strings.Join(keep, "\n")
}

// ParseDevices reads "adb devices" output: serial -> state.
func ParseDevices(out string) map[string]string {
	m := map[string]string{}
	for _, l := range strings.Split(out, "\n") {
		f := strings.Fields(l)
		if len(f) >= 2 && f[0] != "List" {
			m[f[0]] = f[1]
		}
	}
	return m
}

// ParseMdns reads "adb mdns services": returns connect addresses and pairing addresses (ip:port).
func ParseMdns(out string) (connect, pair []string) {
	re := regexp.MustCompile(`(_adb-tls-(connect|pairing)\._tcp)\.?\s+(\d+\.\d+\.\d+\.\d+:\d+)`)
	for _, l := range strings.Split(out, "\n") {
		if m := re.FindStringSubmatch(l); m != nil {
			if m[2] == "connect" {
				connect = append(connect, m[3])
			} else {
				pair = append(pair, m[3])
			}
		}
	}
	return
}

// Kind: "tablet", "tv" or "phone".
func Kind(model, characteristics string) string {
	if strings.Contains(strings.ToLower(characteristics), "tv") {
		return "tv"
	}
	if strings.EqualFold(strings.TrimSpace(model), tabletModel) {
		return "tablet"
	}
	return "phone"
}

// WlanIP reads "ip -f inet addr show wlan0".
func WlanIP(out string) string {
	m := regexp.MustCompile(`inet (\d+\.\d+\.\d+\.\d+)/`).FindStringSubmatch(out)
	if m == nil {
		return ""
	}
	return m[1]
}

type helper struct {
	dir, adb, qa string
	log          *os.File
	tcpDone      map[string]bool
	asked        bool
}

func (h *helper) run(timeout time.Duration, args ...string) (string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	cmd := exec.CommandContext(ctx, h.adb, args...)
	cmd.Env = append(os.Environ(), "ADB_MDNS_OPENSCREEN=1")
	hideWindow(cmd)
	var b bytes.Buffer
	cmd.Stdout, cmd.Stderr = &b, &b
	err := cmd.Run()
	return b.String(), err
}

func (h *helper) logf(f string, a ...any) {
	line := time.Now().Format("2006-01-02 15:04:05 ") + fmt.Sprintf(f, a...)
	fmt.Println(line)
	if h.log != nil {
		fmt.Fprintln(h.log, line)
	}
}

// devices returns role -> serial for the phone and tablet that are connected right now.
func (h *helper) devices() map[string]string {
	out, _ := h.run(15*time.Second, "devices")
	roles := map[string]string{}
	for serial, state := range ParseDevices(out) {
		if state != "device" {
			continue
		}
		model, _ := h.run(10*time.Second, "-s", serial, "shell", "getprop", "ro.product.model")
		ch, _ := h.run(10*time.Second, "-s", serial, "shell", "getprop", "ro.build.characteristics")
		k := Kind(model, ch)
		if k == "tv" {
			continue
		}
		// a device on USB: switch it to Wi-Fi adb once (port 5555), then use Wi-Fi
		if !strings.Contains(serial, ":") && !h.tcpDone[serial] {
			ipOut, _ := h.run(10*time.Second, "-s", serial, "shell", "ip", "-f", "inet", "addr", "show", "wlan0")
			if ip := WlanIP(ipOut); ip != "" {
				h.run(15*time.Second, "-s", serial, "tcpip", "5555")
				time.Sleep(3 * time.Second)
				h.run(15*time.Second, "connect", ip+":5555")
				h.logf("%s %s: Wi-Fi adb on %s:5555 (the cable can be removed now)", k, strings.TrimSpace(model), ip)
				h.remember(ip + ":5555")
			}
			h.tcpDone[serial] = true
		}
		if cur, ok := roles[k]; !ok || (strings.Contains(serial, ":") && !strings.Contains(cur, ":")) {
			roles[k] = serial
		}
	}
	return roles
}

func (h *helper) remember(addr string) {
	p := filepath.Join(h.qa, "known-devices.txt")
	b, _ := os.ReadFile(p)
	if !strings.Contains(string(b), addr) {
		os.WriteFile(p, append(b, []byte(addr+"\n")...), 0o644)
	}
}

// reconnect: remembered addresses + anything that announces itself on this Wi-Fi (already paired devices).
func (h *helper) reconnect(stdin *bufio.Reader, roles map[string]string) {
	b, _ := os.ReadFile(filepath.Join(h.qa, "known-devices.txt"))
	for _, a := range strings.Fields(string(b)) {
		h.run(8*time.Second, "connect", a)
	}
	out, _ := h.run(10*time.Second, "mdns", "services")
	conn, pair := ParseMdns(out)
	for _, a := range conn {
		if o, _ := h.run(8*time.Second, "connect", a); strings.Contains(o, "connected") {
			h.remember(a)
		}
	}
	// pairing code from qa\pair.txt (Claude writes the 6 digits the owner says in chat): "123456" or "ip:port 123456"
	if b, err := os.ReadFile(filepath.Join(h.qa, "pair.txt")); err == nil {
		os.Remove(filepath.Join(h.qa, "pair.txt"))
		f := strings.Fields(string(b))
		addr, code := "", ""
		for _, x := range f {
			if regexp.MustCompile(`^\d{6}$`).MatchString(x) {
				code = x
			} else if regexp.MustCompile(`^\d+\.\d+\.\d+\.\d+:\d+$`).MatchString(x) {
				addr = x
			}
		}
		if addr == "" && len(pair) > 0 {
			addr = pair[0]
		}
		if addr != "" && code != "" {
			o, _ := h.run(20*time.Second, "pair", addr, code)
			h.logf("pair %s: %s", addr, strings.TrimSpace(o))
			time.Sleep(2 * time.Second)
			out, _ = h.run(10*time.Second, "mdns", "services")
			conn, _ = ParseMdns(out)
			for _, a := range conn {
				if o, _ := h.run(8*time.Second, "connect", a); strings.Contains(o, "connected") {
					h.remember(a)
				}
			}
		} else {
			h.logf("pair.txt: no pairing address found (open Pair device with pairing code on the device)")
		}
	}
	os.WriteFile(filepath.Join(h.qa, "adb-status.txt"), []byte(time.Now().Format(time.RFC3339)+"\n"+out), 0o644)
	// pairing (only when the owner opened "Pair device with pairing code" on a device): ask for the 6 digits once
	if len(pair) > 0 && (roles["phone"] == "" || roles["tablet"] == "") && stdin != nil {
		fmt.Printf("\nDevice par pairing code dikh raha hai (%s). Woh 6 ank yahan likhkar Enter dabayein: ", pair[0])
		code, _ := stdin.ReadString('\n')
		code = strings.TrimSpace(code)
		if regexp.MustCompile(`^\d{6}$`).MatchString(code) {
			o, _ := h.run(20*time.Second, "pair", pair[0], code)
			h.logf("pair %s: %s", pair[0], strings.TrimSpace(o))
		}
	}
}

func (h *helper) writeStatus(roles map[string]string) {
	var b strings.Builder
	fmt.Fprintf(&b, "updated %s\n", time.Now().Format(time.RFC3339))
	keys := []string{"phone", "tablet"}
	for _, k := range keys {
		if s, ok := roles[k]; ok {
			fmt.Fprintf(&b, "%s: connected (%s)\n", k, s)
		} else {
			fmt.Fprintf(&b, "%s: NOT connected\n", k)
		}
	}
	os.WriteFile(filepath.Join(h.qa, "devices.txt"), []byte(b.String()), 0o644)
}

func (h *helper) jobs(roles map[string]string) {
	jd := filepath.Join(h.qa, "jobs")
	files, _ := filepath.Glob(filepath.Join(jd, "*.job"))
	sort.Strings(files)
	for _, f := range files {
		raw, _ := os.ReadFile(f)
		var j Job
		res := ""
		if err := json.Unmarshal(raw, &j); err != nil {
			res = "REFUSED: not a job file"
		} else if err := j.Validate(); err != nil {
			res = "REFUSED: " + err.Error()
		} else if serial, ok := roles[j.Device]; !ok {
			continue // that device is not connected yet: keep the job for later
		} else {
			res = h.do(j, serial)
		}
		os.MkdirAll(filepath.Join(jd, "done"), 0o755)
		os.Rename(f, filepath.Join(jd, "done", filepath.Base(f)))
		if res != "" {
			os.WriteFile(filepath.Join(h.qa, "results", safeName(j.ID, f)+".txt"), []byte(res), 0o644)
		}
	}
}

func safeName(id, f string) string {
	if reID.MatchString(id) {
		return id
	}
	return strings.TrimSuffix(filepath.Base(f), ".job")
}

func (h *helper) do(j Job, serial string) string {
	h.logf("job %s: %s on %s", j.ID, j.Do, j.Device)
	timeout := 2 * time.Minute
	if j.Do == "test" {
		timeout = 25 * time.Minute
	}
	var out strings.Builder
	fmt.Fprintf(&out, "job %s %s on %s (%s) at %s\n", j.ID, j.Do, j.Device, serial, time.Now().Format(time.RFC3339))
	for _, a := range j.Args(serial, filepath.Join(h.qa, "apks")) {
		o, err := h.run(timeout, a...)
		if j.Do == "screenshot" {
			os.WriteFile(filepath.Join(h.qa, "results", j.ID+".png"), []byte(o), 0o644)
			o = fmt.Sprintf("%d bytes -> %s.png", len(o), j.ID)
		}
		if j.Do == "info" && a[2] == "shell" && a[3] == "dumpsys" { // getprop lines are kept as they are
			o = trimInfo(o)
		}
		out.WriteString(o)
		if err != nil {
			fmt.Fprintf(&out, "\nERROR: %v\n", err)
		}
	}
	h.logf("job %s finished", j.ID)
	return out.String()
}

func main() {
	exe, _ := os.Executable()
	dir := filepath.Dir(exe)
	h := &helper{dir: dir, adb: filepath.Join(dir, "adb.exe"), qa: filepath.Join(dir, "qa"), tcpDone: map[string]bool{}}
	for _, d := range []string{h.qa, filepath.Join(h.qa, "jobs"), filepath.Join(h.qa, "results"), filepath.Join(h.qa, "apks")} {
		os.MkdirAll(d, 0o755)
	}
	h.log, _ = os.OpenFile(filepath.Join(h.qa, "helper-log.txt"), os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o644)
	fmt.Println("Ronin device helper chal raha hai. Is window ko khula rehne dein (minimize kar sakte hain).")
	fmt.Println("Phone / tablet: ek baar USB se jodein (ya Wireless debugging > Pair device with pairing code).")
	stdin := bufio.NewReader(os.Stdin)
	for {
		roles := h.devices()
		if roles["phone"] == "" || roles["tablet"] == "" {
			h.reconnect(stdin, roles)
			roles = h.devices()
		}
		h.writeStatus(roles)
		h.jobs(roles)
		time.Sleep(10 * time.Second)
	}
}
