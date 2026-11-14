package safemoney

import (
	"path/filepath"
	"sort"
	"strings"
)

// remoteTools are programs that let another person see or control this
// computer. Bank fraud in India very often starts with "install AnyDesk".
var remoteTools = map[string]string{
	"anydesk.exe":                     "AnyDesk",
	"teamviewer.exe":                  "TeamViewer",
	"teamviewer_service.exe":          "TeamViewer",
	"teamviewerqs.exe":                "TeamViewer QuickSupport",
	"tv_w32.exe":                      "TeamViewer",
	"tv_x64.exe":                      "TeamViewer",
	"rustdesk.exe":                    "RustDesk",
	"ultraviewer_desktop.exe":         "UltraViewer",
	"ultraviewer_service.exe":         "UltraViewer",
	"aa_v3.exe":                       "Ammyy Admin",
	"ammyy_admin.exe":                 "Ammyy Admin",
	"aeroadmin.exe":                   "AeroAdmin",
	"supremo.exe":                     "Supremo",
	"supremoservice.exe":              "Supremo",
	"screenconnect.clientservice.exe": "ScreenConnect",
	"screenconnect.windowsclient.exe": "ScreenConnect",
	"connectwisecontrol.client.exe":   "ConnectWise Control",
	"remoting_host.exe":               "Chrome Remote Desktop",
	"za_connect.exe":                  "Zoho Assist",
	"zohoassist.exe":                  "Zoho Assist",
	"lmiguardiansvc.exe":              "LogMeIn",
	"logmein.exe":                     "LogMeIn",
	"srservice.exe":                   "Splashtop",
	"strwinclt.exe":                   "Splashtop",
	"quickassist.exe":                 "Windows Quick Assist",
	"msra.exe":                        "Windows Remote Assistance",
	"remotepc.exe":                    "RemotePC",
	"getscreen.exe":                   "Getscreen",
	"dwagent.exe":                     "DWService",
	"airdroid.exe":                    "AirDroid",
}

// RemoteToolsRunning returns the friendly names of remote-access programs
// found in a list of running process names (no duplicates, sorted).
func RemoteToolsRunning(processes []string) []string {
	seen := map[string]bool{}
	for _, p := range processes {
		name := strings.ToLower(filepath.Base(strings.ReplaceAll(strings.TrimSpace(p), `\`, "/")))
		if friendly, ok := remoteTools[name]; ok {
			seen[friendly] = true
		}
	}
	out := make([]string, 0, len(seen))
	for n := range seen {
		out = append(out, n)
	}
	sort.Strings(out)
	return out
}

// AntivirusEnabled reads Windows Security Center "productState" values
// (one per installed antivirus) and reports whether at least one is
// switched on. known is false when no product was reported at all.
// The second byte of productState is 0x10 or 0x11 when protection is on.
func AntivirusEnabled(states []uint32) (on bool, known bool) {
	for _, s := range states {
		known = true
		b := (s >> 8) & 0xFF
		if b == 0x10 || b == 0x11 {
			return true, true
		}
	}
	return false, known
}
