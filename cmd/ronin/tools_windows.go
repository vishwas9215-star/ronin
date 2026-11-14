//go:build windows

package main

// Helper programs Ronin downloads on first use and keeps up to date:
// yt-dlp (video downloader), ffmpeg (joins video and sound), deno (needed by
// yt-dlp for YouTube) and wireproxy (the VPN tunnel). Every file is checked
// against the checksum its makers publish; anything that does not match is
// never run.

import (
	"archive/tar"
	"archive/zip"
	"bytes"
	"compress/gzip"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"syscall"
	"time"
	"unsafe"

	"golang.org/x/sys/windows"

	"roninbrowser/internal/updater"
)

type toolSpec struct {
	name     string   // component name for health
	files    []string // files that must exist in toolsDir
	url      string   // download
	sumURLs  []string // checksum file(s); the first that answers is used
	sumName  string   // file name to look for inside a multi-line checksum file ("" = any single hash)
	archive  string   // "", "zip" or "tar.gz"
	members  []string // file names to take out of the archive (matched by base name)
	every    time.Duration
	selfTest []string // arguments for a quick run after updating
}

var (
	ytdlpTool = toolSpec{
		name: "yt-dlp", files: []string{"yt-dlp.exe"},
		url:     "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp.exe",
		sumURLs: []string{"https://github.com/yt-dlp/yt-dlp/releases/latest/download/SHA2-256SUMS"},
		sumName: "yt-dlp.exe", every: 24 * time.Hour, selfTest: []string{"--version"},
	}
	denoTool = toolSpec{
		name: "deno", files: []string{"deno.exe"},
		url:     "https://github.com/denoland/deno/releases/latest/download/deno-x86_64-pc-windows-msvc.zip",
		sumURLs: []string{"https://github.com/denoland/deno/releases/latest/download/deno-x86_64-pc-windows-msvc.zip.sha256sum"},
		archive: "zip", members: []string{"deno.exe"}, every: 7 * 24 * time.Hour, selfTest: []string{"--version"},
	}
	ffmpegTool = toolSpec{
		name: "ffmpeg", files: []string{"ffmpeg.exe", "ffprobe.exe"},
		url:     "https://github.com/yt-dlp/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-gpl.zip",
		sumURLs: []string{"https://github.com/yt-dlp/FFmpeg-Builds/releases/download/latest/checksums.sha256"},
		sumName: "ffmpeg-master-latest-win64-gpl.zip",
		archive: "zip", members: []string{"ffmpeg.exe", "ffprobe.exe"}, every: 30 * 24 * time.Hour, selfTest: []string{"-version"},
	}
	wireproxyTool = toolSpec{
		name: "wireproxy", files: []string{"wireproxy.exe"},
		url: "https://github.com/windtf/wireproxy/releases/latest/download/wireproxy_windows_amd64.tar.gz",
		sumURLs: []string{
			"https://github.com/windtf/wireproxy/releases/latest/download/checksums.txt",
			"https://github.com/windtf/wireproxy/releases/latest/download/wireproxy_checksums.txt",
			"https://github.com/windtf/wireproxy/releases/latest/download/sha256sums.txt",
		},
		sumName: "wireproxy_windows_amd64.tar.gz",
		archive: "tar.gz", members: []string{"wireproxy.exe"}, every: 30 * 24 * time.Hour, selfTest: []string{"--version"},
	}
)

var toolMu sync.Mutex

func toolsDir() string { return filepath.Join(dataDir, "tools") }

func toolReady(t toolSpec) bool {
	for _, f := range t.files {
		if st, err := os.Stat(filepath.Join(toolsDir(), f)); err != nil || st.Size() == 0 {
			return false
		}
	}
	return true
}

// ensureTool installs a tool if it is missing. status receives short
// English progress messages for the toolbar.
func ensureTool(t toolSpec, status func(string)) error {
	toolMu.Lock()
	defer toolMu.Unlock()
	if toolReady(t) {
		return nil
	}
	if status != nil {
		status("Getting " + t.name + " (first time only)...")
	}
	sum, err := installTool(t)
	if err == nil {
		recordComponent(t.name, func(c *updater.Component) {
			c.Version, c.Updated, c.OK, c.Problem = sum, time.Now(), true, ""
		})
	}
	return err
}

var stateMu sync.Mutex

func statePath() string { return filepath.Join(dataDir, "update-state.json") }

// recordComponent changes one component in the update state file.
func recordComponent(name string, change func(*updater.Component)) {
	stateMu.Lock()
	defer stateMu.Unlock()
	st := updater.LoadState(statePath())
	c := st.Components[name]
	change(&c)
	st.Components[name] = c
	_ = updater.SaveState(statePath(), st)
}

// installTool downloads, checks and puts a tool in place (also used to
// update). It returns the official checksum of what was installed.
func installTool(t toolSpec) (string, error) {
	if err := os.MkdirAll(toolsDir(), 0o755); err != nil {
		return "", err
	}
	want, err := fetchChecksum(t)
	if err != nil {
		return "", fmt.Errorf("%s: %w", t.name, err)
	}
	data, err := fetch(t.url, 400<<20)
	if err != nil {
		return "", fmt.Errorf("%s: %w", t.name, err)
	}
	got := sha256.Sum256(data)
	if !strings.EqualFold(hex.EncodeToString(got[:]), want) {
		return "", fmt.Errorf("%s: the downloaded file did not match its official checksum, so it was not installed", t.name)
	}
	files := map[string][]byte{}
	switch t.archive {
	case "":
		files[t.files[0]] = data
	case "zip":
		files, err = fromZip(data, t.members)
	case "tar.gz":
		files, err = fromTarGz(data, t.members)
	}
	if err != nil {
		return "", fmt.Errorf("%s: %w", t.name, err)
	}
	for name, b := range files {
		s := sha256.Sum256(b)
		f := updater.File{Name: name, URL: "https://local", SHA256: hex.EncodeToString(s[:]), Size: int64(len(b))}
		var test updater.SelfTest
		if len(t.selfTest) > 0 && (name == t.files[0]) {
			args := t.selfTest
			test = func(p string) error { return runHidden(p, args, 60*time.Second) }
		}
		if err := updater.Install(filepath.Join(toolsDir(), name), b, f, test); err != nil {
			return "", fmt.Errorf("%s: %w", t.name, err)
		}
	}
	logf("installed %s", t.name)
	return want, nil
}

var hexRe = regexp.MustCompile(`(?i)\b[0-9a-f]{64}\b`)

// fetchChecksum reads the official checksum for the tool.
func fetchChecksum(t toolSpec) (string, error) {
	var lastErr error = errors.New("no checksum published")
	for _, u := range t.sumURLs {
		b, err := fetch(u, 1<<20)
		if err != nil {
			lastErr = err
			continue
		}
		if h := pickChecksum(string(b), t.sumName); h != "" {
			return strings.ToLower(h), nil
		}
		lastErr = errors.New("checksum not found in the official list")
	}
	return "", lastErr
}

// pickChecksum finds the hash for name in a "hash  name" list, or the only
// hash in a single-file checksum.
func pickChecksum(text, name string) string {
	if name != "" {
		for _, line := range strings.Split(text, "\n") {
			f := strings.Fields(strings.TrimSpace(line))
			if len(f) >= 2 && strings.TrimPrefix(f[len(f)-1], "*") == name && hexRe.MatchString(f[0]) && len(f[0]) == 64 {
				return f[0]
			}
		}
		return ""
	}
	all := hexRe.FindAllString(text, -1)
	if len(all) == 1 {
		return all[0]
	}
	return ""
}

func fromZip(data []byte, members []string) (map[string][]byte, error) {
	zr, err := zip.NewReader(bytes.NewReader(data), int64(len(data)))
	if err != nil {
		return nil, err
	}
	out := map[string][]byte{}
	for _, f := range zr.File {
		base := filepath.Base(strings.ReplaceAll(f.Name, `\`, "/"))
		for _, m := range members {
			if strings.EqualFold(base, m) && f.UncompressedSize64 < 500<<20 {
				rc, err := f.Open()
				if err != nil {
					return nil, err
				}
				b, err := io.ReadAll(io.LimitReader(rc, 500<<20))
				rc.Close()
				if err != nil {
					return nil, err
				}
				out[m] = b
			}
		}
	}
	for _, m := range members {
		if _, ok := out[m]; !ok {
			return nil, errors.New(m + " was not found in the download")
		}
	}
	return out, nil
}

func fromTarGz(data []byte, members []string) (map[string][]byte, error) {
	gz, err := gzip.NewReader(bytes.NewReader(data))
	if err != nil {
		return nil, err
	}
	tr := tar.NewReader(gz)
	out := map[string][]byte{}
	for {
		h, err := tr.Next()
		if err == io.EOF {
			break
		}
		if err != nil {
			return nil, err
		}
		base := filepath.Base(h.Name)
		for _, m := range members {
			if strings.EqualFold(base, m) || strings.EqualFold(base+".exe", m) {
				b, err := io.ReadAll(io.LimitReader(tr, 200<<20))
				if err != nil {
					return nil, err
				}
				out[m] = b
			}
		}
	}
	for _, m := range members {
		if _, ok := out[m]; !ok {
			return nil, errors.New(m + " was not found in the download")
		}
	}
	return out, nil
}

// updateTools refreshes installed tools in the background when their time is due.
func updateTools() {
	stateMu.Lock()
	st := updater.LoadState(statePath())
	stateMu.Unlock()
	for _, t := range []toolSpec{ytdlpTool, denoTool, ffmpegTool, wireproxyTool} {
		if !toolReady(t) {
			continue // only tools the user actually uses are kept up to date
		}
		c := st.Components[t.name]
		if !c.Updated.IsZero() && time.Since(c.Updated) < t.every {
			continue
		}
		want, err := fetchChecksum(t)
		if err != nil {
			msg := err.Error()
			recordComponent(t.name, func(cc *updater.Component) { cc.Problem = msg })
			continue
		}
		toolMu.Lock()
		same := false
		if t.archive == "" {
			if b, err := os.ReadFile(filepath.Join(toolsDir(), t.files[0])); err == nil {
				s := sha256.Sum256(b)
				same = strings.EqualFold(hex.EncodeToString(s[:]), want)
			}
		} else {
			same = c.Version == want
		}
		if !same {
			_, err = installTool(t)
		}
		toolMu.Unlock()
		recordComponent(t.name, func(cc *updater.Component) {
			cc.Updated = time.Now()
			cc.OK = err == nil
			cc.Problem = ""
			if err != nil {
				cc.Problem = err.Error()
			} else {
				cc.Version = want
			}
		})
	}
}

// ---- running helper programs -------------------------------------------------

const createNoWindow = 0x08000000

var killJob windows.Handle

// childJob makes sure helper programs end when Ronin ends (even after a crash).
func childJob() windows.Handle {
	if killJob != 0 {
		return killJob
	}
	j, err := windows.CreateJobObject(nil, nil)
	if err != nil {
		return 0
	}
	info := windows.JOBOBJECT_EXTENDED_LIMIT_INFORMATION{}
	info.BasicLimitInformation.LimitFlags = windows.JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
	if _, err := windows.SetInformationJobObject(j, windows.JobObjectExtendedLimitInformation,
		uintptr(unsafe.Pointer(&info)), uint32(unsafe.Sizeof(info))); err != nil {
		windows.CloseHandle(j)
		return 0
	}
	killJob = j
	return j
}

func attachToJob(p *os.Process) {
	j := childJob()
	if j == 0 || p == nil {
		return
	}
	h, err := windows.OpenProcess(windows.PROCESS_SET_QUOTA|windows.PROCESS_TERMINATE, false, uint32(p.Pid))
	if err != nil {
		return
	}
	_ = windows.AssignProcessToJobObject(j, h)
	windows.CloseHandle(h)
}

func hiddenCmd(path string, args ...string) *exec.Cmd {
	cmd := exec.Command(path, args...)
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: createNoWindow}
	cmd.Env = append(os.Environ(), "PATH="+toolsDir()+";"+os.Getenv("PATH"))
	cmd.Dir = toolsDir()
	return cmd
}

func runHidden(path string, args []string, d time.Duration) error {
	cmd := hiddenCmd(path, args...)
	cmd.Dir = filepath.Dir(path)
	_, err := runWithTimeout(cmd, d)
	return err
}

func downloadsDir() string {
	if p, err := windows.KnownFolderPath(windows.FOLDERID_Downloads, 0); err == nil && p != "" {
		return p
	}
	return filepath.Join(os.Getenv("USERPROFILE"), "Downloads")
}
