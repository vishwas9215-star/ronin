// Package downloadguard decides whether a download is safe, needs a
// warning, or must be stopped, and writes the Windows "Mark of the Web"
// so SmartScreen and Defender check the file before it runs.
package downloadguard

import (
	"path/filepath"
	"strings"
	"unicode"
)

// Level is how risky a file is.
type Level int

const (
	Safe      Level = iota // documents, pictures, video
	Caution                // archives that may hide programs, macro documents
	Dangerous              // programs and scripts: stopped until the user confirms
	Blocked                // not allowed at all in this mode (BANKING, KIDS)
)

func (l Level) String() string {
	return [...]string{"safe", "caution", "dangerous", "blocked"}[l]
}

var dangerousExt = set(
	"exe", "msi", "msix", "msixbundle", "msp", "appx", "appxbundle", "bat", "cmd", "com",
	"scr", "pif", "cpl", "ps1", "psm1", "psd1", "ps1xml", "vbs", "vbe", "js", "jse", "wsf",
	"wsh", "wsc", "hta", "lnk", "url", "iso", "img", "vhd", "vhdx", "jar", "jnlp", "reg",
	"inf", "scf", "chm", "apk", "xapk", "dll", "sys", "drv", "ocx", "msc", "gadget",
	"application", "appref-ms", "library-ms", "settingcontent-ms", "search-ms", "xll",
	"one", "website", "mht", "mhtml", "svg", "hlp", "ins", "isp", "mde", "mdb", "accde",
	"msh", "msh1", "msh2", "mshxml", "py", "pyw", "pyc", "rb", "pl", "sh", "elf", "bin",
	"deb", "rpm", "dmg", "pkg", "cab",
)

var cautionExt = set(
	"zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "zst", "arj", "ace", "lzh",
	"docm", "dotm", "xlsm", "xltm", "xlam", "pptm", "potm", "ppam", "ppsm", "sldm",
	"html", "htm", "xhtml", "shtml", "torrent", "rtf",
)

// bankingAllowed are the only types Ronin Safe Money lets you save
// (bank statements and receipts).
var bankingAllowed = set("pdf", "csv", "xls", "xlsx", "txt", "jpg", "jpeg", "png")

// kidsAllowed are school-friendly types for the KIDS mode.
var kidsAllowed = set("pdf", "jpg", "jpeg", "png", "gif", "webp", "txt", "mp3", "mp4", "docx", "pptx")

// Mode changes the rules for one container.
type Mode int

const (
	Normal Mode = iota
	BankingMode
	KidsMode
)

// Result explains the decision in a way the UI can show.
type Result struct {
	Level    Level
	Ext      string // last extension, lower case, without the dot
	Reason   string // short English reason for the UI
	SafeName string // cleaned file name to save as
}

// Check decides how risky a download is.
func Check(name string, mode Mode) Result {
	safe := SanitizeName(name)
	ext := lastExt(safe)
	r := Result{Level: Safe, Ext: ext, SafeName: safe}

	switch {
	case hasHiddenTrick(name):
		r.Level, r.Reason = Dangerous, "The file name hides its real type (reversed or invisible characters)."
	case dangerousExt[ext]:
		r.Level, r.Reason = Dangerous, "This is a program or script. It can harm your computer."
		if fake := fakeDocExt(safe); fake != "" {
			r.Reason = "This looks like a ." + fake + " file but it is really a program (." + ext + ")."
		}
	case cautionExt[ext]:
		r.Level, r.Reason = Caution, "Archives and macro documents can hide harmful programs."
	case ext == "":
		r.Level, r.Reason = Caution, "This file has no type. Open it only if you trust the site."
	}

	switch mode {
	case BankingMode:
		if !bankingAllowed[ext] {
			r.Level, r.Reason = Blocked, "Ronin Safe Money only saves statements (PDF, CSV, Excel, pictures)."
		}
	case KidsMode:
		if !kidsAllowed[ext] {
			r.Level, r.Reason = Blocked, "This type of file cannot be downloaded in Kids mode."
		}
	}
	return r
}

// SanitizeName removes path parts and characters Windows does not allow,
// and stops reserved names like "CON".
func SanitizeName(name string) string {
	name = strings.ReplaceAll(name, `\`, "/")
	name = filepath.Base(name)
	var b strings.Builder
	for _, r := range name {
		switch {
		case r < 32, r == 0x7f, strings.ContainsRune(`<>:"/\|?*`, r):
			b.WriteRune('_')
		case isInvisible(r):
			// dropped
		default:
			b.WriteRune(r)
		}
	}
	s := strings.TrimRight(strings.TrimSpace(b.String()), ". ")
	if s == "" || s == "." || s == ".." {
		s = "download"
	}
	base := strings.ToUpper(strings.SplitN(s, ".", 2)[0])
	switch base {
	case "CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7",
		"COM8", "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9":
		s = "_" + s
	}
	if len(s) > 200 {
		ext := filepath.Ext(s)
		if len(ext) > 20 {
			ext = ""
		}
		s = string([]rune(s)[:150]) + ext
	}
	return s
}

func lastExt(name string) string {
	e := filepath.Ext(name)
	return strings.ToLower(strings.TrimPrefix(e, "."))
}

// fakeDocExt returns "pdf" for "invoice.pdf.exe".
func fakeDocExt(name string) string {
	parts := strings.Split(strings.ToLower(name), ".")
	if len(parts) < 3 {
		return ""
	}
	prev := parts[len(parts)-2]
	switch prev {
	case "pdf", "doc", "docx", "xls", "xlsx", "jpg", "jpeg", "png", "txt", "mp4", "mp3", "zip":
		return prev
	}
	return ""
}

func hasHiddenTrick(name string) bool {
	for _, r := range name {
		if isInvisible(r) {
			return true
		}
	}
	return false
}

// isInvisible covers right-to-left override and other format characters
// used to disguise "gpj.exe" as "exe.jpg".
func isInvisible(r rune) bool {
	switch r {
	case 0x202A, 0x202B, 0x202C, 0x202D, 0x202E, 0x2066, 0x2067, 0x2068, 0x2069, 0x200E, 0x200F, 0x061C:
		return true
	}
	return unicode.Is(unicode.Cf, r)
}

// ZoneIdentifier is the text of the Mark of the Web that Windows reads.
// Zone 3 = Internet. URLs are written without passwords or query strings.
func ZoneIdentifier(hostURL, referrerURL string) string {
	var b strings.Builder
	b.WriteString("[ZoneTransfer]\r\nZoneId=3\r\n")
	if r := cleanURL(referrerURL); r != "" {
		b.WriteString("ReferrerUrl=" + r + "\r\n")
	}
	if h := cleanURL(hostURL); h != "" {
		b.WriteString("HostUrl=" + h + "\r\n")
	}
	return b.String()
}

func cleanURL(u string) string {
	u = strings.TrimSpace(u)
	if i := strings.IndexAny(u, "?#"); i >= 0 {
		u = u[:i]
	}
	if i := strings.Index(u, "://"); i >= 0 {
		rest := u[i+3:]
		if at := strings.Index(rest, "@"); at >= 0 && (strings.Index(rest, "/") < 0 || at < strings.Index(rest, "/")) {
			u = u[:i+3] + rest[at+1:]
		}
	}
	if strings.ContainsAny(u, "\r\n") {
		return ""
	}
	return u
}

func set(items ...string) map[string]bool {
	m := make(map[string]bool, len(items))
	for _, i := range items {
		m[i] = true
	}
	return m
}
