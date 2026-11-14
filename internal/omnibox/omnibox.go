// Package omnibox turns what the user types in the address bar into an
// address: a web address stays an address, anything else becomes a search.
package omnibox

import (
	"net"
	"net/url"
	"strings"
)

// SearchURL is used for searches (Google with SafeSearch handled elsewhere).
const SearchURL = "https://www.google.com/search?q="

// Resolve returns the address to open, or "" when the text is empty or
// would be dangerous (javascript:, file: and similar are never opened).
func Resolve(text string) string {
	t := strings.TrimSpace(text)
	if t == "" {
		return ""
	}
	lower := strings.ToLower(t)
	for _, bad := range []string{"javascript:", "vbscript:", "file:", "data:", "blob:", "edge:", "chrome:", "view-source:", "ms-", "search-ms:"} {
		if strings.HasPrefix(lower, bad) {
			return search(t)
		}
	}
	if strings.HasPrefix(lower, "http://") || strings.HasPrefix(lower, "https://") {
		if u, err := url.Parse(t); err == nil && u.Host != "" && !strings.ContainsAny(t, " \t") {
			return u.String()
		}
		return search(t)
	}
	if lower == "about:blank" {
		return "about:blank"
	}
	if strings.ContainsAny(t, " \t") {
		return search(t)
	}
	if looksLikeHost(t) {
		scheme := "https://"
		h := hostPart(t)
		if isLocal(h) {
			scheme = "http://"
		}
		if u, err := url.Parse(scheme + t); err == nil && u.Host != "" {
			return u.String()
		}
	}
	return search(t)
}

func search(t string) string { return SearchURL + url.QueryEscape(t) }

func hostPart(t string) string {
	h := t
	if i := strings.IndexAny(h, "/?#"); i >= 0 {
		h = h[:i]
	}
	if hh, _, err := net.SplitHostPort(h); err == nil {
		h = hh
	}
	return strings.ToLower(h)
}

func isLocal(h string) bool {
	return h == "localhost" || strings.HasPrefix(h, "127.") || h == "[::1]" || h == "::1"
}

func looksLikeHost(t string) bool {
	h := hostPart(t)
	if h == "" {
		return false
	}
	if isLocal(h) || net.ParseIP(h) != nil {
		return true
	}
	if !strings.Contains(h, ".") || strings.HasPrefix(h, ".") || strings.HasSuffix(h, ".") || strings.Contains(h, "..") {
		return false
	}
	tld := h[strings.LastIndex(h, ".")+1:]
	if len(tld) < 2 {
		return false
	}
	for _, r := range tld {
		if (r < 'a' || r > 'z') && r < 0x80 && !(tld[:min(4, len(tld))] == "xn--") {
			return false
		}
	}
	for _, r := range h {
		if !(r == '.' || r == '-' || (r >= 'a' && r <= 'z') || (r >= '0' && r <= '9') || r >= 0x80) {
			return false
		}
	}
	return true
}

// Display returns the address to show in the bar (no "https://www." clutter
// is removed on purpose: the full host must stay visible for safety).
func Display(raw string) string {
	if raw == "about:blank" || strings.HasPrefix(raw, "data:") {
		return ""
	}
	return raw
}
