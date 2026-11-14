//go:build windows

package main

import (
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

// ---- virus check of every download (Windows Defender, already on the PC) ----

// defenderTool finds Windows Defender's command-line scanner.
func defenderTool() string {
	pd := os.Getenv("ProgramData")
	if pd == "" {
		pd = `C:\ProgramData`
	}
	if dirs, err := os.ReadDir(filepath.Join(pd, "Microsoft", "Windows Defender", "Platform")); err == nil {
		var names []string
		for _, d := range dirs {
			if d.IsDir() {
				names = append(names, d.Name())
			}
		}
		sort.Strings(names)
		for i := len(names) - 1; i >= 0; i-- {
			p := filepath.Join(pd, "Microsoft", "Windows Defender", "Platform", names[i], "MpCmdRun.exe")
			if _, err := os.Stat(p); err == nil {
				return p
			}
		}
	}
	pf := os.Getenv("ProgramFiles")
	if pf == "" {
		pf = `C:\Program Files`
	}
	p := filepath.Join(pf, "Windows Defender", "MpCmdRun.exe")
	if _, err := os.Stat(p); err == nil {
		return p
	}
	return ""
}

// Scan results.
const (
	scanClean   = "clean"
	scanVirus   = "virus"
	scanSkipped = "skipped" // Defender not available (for example another antivirus is used)
)

// scanFile checks one downloaded file. When Defender finds a virus it
// quarantines the file itself; Ronin removes anything left over.
func scanFile(path string) string {
	tool := defenderTool()
	if tool == "" || path == "" || strings.ContainsAny(path, "\"\r\n") {
		return scanSkipped
	}
	if _, err := os.Stat(path); err != nil {
		return scanSkipped
	}
	cmd := hiddenCmd(tool, "-Scan", "-ScanType", "3", "-File", path)
	cmd.Dir = filepath.Dir(tool)
	_, err := runWithTimeout(cmd, 3*time.Minute)
	var ee *exec.ExitError
	switch {
	case err == nil:
		return scanClean
	case errors.As(err, &ee) && ee.ExitCode() == 2: // threat found
		_ = os.Remove(path)
		logf("virus check: a threat was found in a download and removed")
		return scanVirus
	default:
		logf("virus check could not run: %v", err)
		return scanSkipped
	}
}
