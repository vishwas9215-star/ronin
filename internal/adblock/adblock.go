// Package adblock decides which web requests are ads, trackers or malware.
// It understands the common list formats ("||domain^", "@@||domain^",
// "0.0.0.0 domain", plain "domain") and a few built-in path rules.
// Matching walks the host's parent domains, so it is fast enough to run for
// every request.
package adblock

import (
	"bufio"
	"io"
	"net/url"
	"strings"
	"sync"
)

// Engine holds the block and allow sets. It is safe for concurrent use.
type Engine struct {
	mu    sync.RWMutex
	block map[string]struct{}
	allow map[string]struct{}
}

// New returns an engine with Ronin's built-in rules.
func New() *Engine {
	e := &Engine{block: map[string]struct{}{}, allow: map[string]struct{}{}}
	for _, d := range builtinBlock {
		e.block[d] = struct{}{}
	}
	for _, d := range builtinAllow {
		e.allow[d] = struct{}{}
	}
	return e
}

// Stats is only used by tests and the health page ("list loaded: yes/no").
func (e *Engine) Size() int {
	e.mu.RLock()
	defer e.mu.RUnlock()
	return len(e.block)
}

// LoadList reads one filter list and adds its rules. It returns how many
// usable rules were found.
func (e *Engine) LoadList(r io.Reader) int {
	block, allow := ParseList(r)
	e.mu.Lock()
	defer e.mu.Unlock()
	for d := range block {
		e.block[d] = struct{}{}
	}
	for d := range allow {
		e.allow[d] = struct{}{}
	}
	return len(block) + len(allow)
}

// ParseList parses a list without changing any engine (used to check a
// freshly downloaded list before it replaces the old one).
func ParseList(r io.Reader) (block, allow map[string]struct{}) {
	block, allow = map[string]struct{}{}, map[string]struct{}{}
	sc := bufio.NewScanner(r)
	sc.Buffer(make([]byte, 64*1024), 1024*1024)
	for sc.Scan() {
		line := strings.TrimSpace(sc.Text())
		if line == "" || line[0] == '!' || line[0] == '#' || line[0] == '[' {
			continue
		}
		if strings.Contains(line, "##") || strings.Contains(line, "#@#") || strings.Contains(line, "#$#") || strings.Contains(line, "#?#") {
			continue // page-hiding rules are handled by the cosmetic layer
		}
		isAllow := strings.HasPrefix(line, "@@")
		line = strings.TrimPrefix(line, "@@")
		d, ok := ruleDomain(line)
		if !ok {
			continue
		}
		if isAllow {
			allow[d] = struct{}{}
		} else {
			block[d] = struct{}{}
		}
	}
	return block, allow
}

// ruleDomain extracts the domain from one rule, or false for rules this
// engine does not handle (paths, regexes, rules limited by options).
func ruleDomain(line string) (string, bool) {
	// hosts file: "0.0.0.0 ads.example.com" or "127.0.0.1 ads.example.com"
	if f := strings.Fields(line); len(f) >= 2 && (f[0] == "0.0.0.0" || f[0] == "127.0.0.1" || f[0] == "::" || f[0] == "::1") {
		return cleanDomain(f[1])
	}
	if strings.HasPrefix(line, "||") {
		rest := line[2:]
		opts := ""
		if i := strings.IndexByte(rest, '$'); i >= 0 {
			rest, opts = rest[:i], rest[i+1:]
		}
		if !simpleOptions(opts) {
			return "", false
		}
		rest = strings.TrimSuffix(rest, "^|")
		if !strings.HasSuffix(rest, "^") {
			return "", false // has a path part: not a whole-domain rule
		}
		return cleanDomain(strings.TrimSuffix(rest, "^"))
	}
	if strings.ContainsAny(line, "/*^|$ \t") {
		return "", false
	}
	return cleanDomain(line) // plain domain per line
}

// simpleOptions accepts rules without options or with options that do not
// narrow them to particular pages.
func simpleOptions(opts string) bool {
	if opts == "" {
		return true
	}
	for _, o := range strings.Split(opts, ",") {
		switch strings.TrimSpace(o) {
		case "important", "third-party", "3p", "all", "document", "popup":
		default:
			return false
		}
	}
	return true
}

func cleanDomain(d string) (string, bool) {
	d = strings.ToLower(strings.TrimSuffix(strings.TrimSpace(d), "."))
	if d == "" || len(d) > 253 || !strings.Contains(d, ".") || strings.HasPrefix(d, ".") || strings.HasPrefix(d, "*") {
		return "", false
	}
	if d == "localhost" || d == "localhost.localdomain" || d == "local" || d == "broadcasthost" {
		return "", false
	}
	for _, r := range d {
		if !(r == '.' || r == '-' || r == '_' || (r >= 'a' && r <= 'z') || (r >= '0' && r <= '9')) {
			return "", false
		}
	}
	return d, true
}

// Blocked reports whether a request URL must be blocked.
func (e *Engine) Blocked(rawURL string) bool {
	u, err := url.Parse(rawURL)
	if err != nil || (u.Scheme != "http" && u.Scheme != "https" && u.Scheme != "ws" && u.Scheme != "wss") {
		return false
	}
	host := strings.ToLower(strings.TrimSuffix(u.Hostname(), "."))
	if host == "" {
		return false
	}
	if pathBlocked(host, u.Path) {
		return true
	}
	e.mu.RLock()
	defer e.mu.RUnlock()
	// The most specific match wins: an allow rule for a sub-domain beats a
	// block rule for its parent, and the other way round.
	for h := host; ; {
		if _, ok := e.allow[h]; ok {
			return false
		}
		if _, ok := e.block[h]; ok {
			return true
		}
		i := strings.IndexByte(h, '.')
		if i < 0 {
			return false
		}
		h = h[i+1:]
	}
}

// pathRules block ad and tracking paths on sites whose other content must load.
var pathRules = []struct{ host, prefix string }{
	{"youtube.com", "/pagead/"},
	{"youtube.com", "/api/stats/ads"},
	{"youtube.com", "/pcs/activeview"},
	{"google.com", "/pagead/"},
	{"google.com", "/adsense/"},
	{"facebook.com", "/tr"},
	{"instagram.com", "/logging/"},
}

func pathBlocked(host, path string) bool {
	for _, r := range pathRules {
		if (host == r.host || strings.HasSuffix(host, "."+r.host)) && strings.HasPrefix(path, r.prefix) {
			return true
		}
	}
	return false
}

// BlockedFrom is Blocked plus the "advert inside another site" rule: topHost
// is the site the user is on.
func (e *Engine) BlockedFrom(rawURL, topHost string) bool {
	if e.Blocked(rawURL) {
		return true
	}
	u, err := url.Parse(rawURL)
	if err != nil {
		return false
	}
	host := strings.ToLower(u.Hostname())
	topHost = strings.ToLower(topHost)
	for _, d := range thirdPartyBlock {
		if host == d || strings.HasSuffix(host, "."+d) {
			return !(topHost == d || strings.HasSuffix(topHost, "."+d))
		}
	}
	return false
}

// Lists are the public filter lists Ronin uses (Windows downloads them daily;
// for Android the build machine turns them into one compact domain file).
var Lists = []struct {
	Name, URL string
	Min       int // a download with fewer rules is treated as broken and ignored
}{
	{"adguard-dns", "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt", 10000},
	{"hagezi-pro", "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/pro.txt", 50000},
	{"popup-ads", "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/popupads.txt", 10000},
	{"peter-lowe", "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=adblockplus&showintro=0&mimetype=plaintext", 1000},
	{"malware-filter", "https://malware-filter.gitlab.io/malware-filter/urlhaus-filter-hosts.txt", 100},
	{"phishing-filter", "https://malware-filter.gitlab.io/malware-filter/phishing-filter-hosts.txt", 100},
}

// Snapshot returns the engine's rules for other Ronin apps (Android).
func (e *Engine) Snapshot() (block, allow []string) {
	e.mu.RLock()
	defer e.mu.RUnlock()
	for d := range e.block {
		block = append(block, d)
	}
	for d := range e.allow {
		allow = append(allow, d)
	}
	return block, allow
}

// Extra returns the rules that are not plain domains: "advert inside another
// site" domains and blocked paths (host, path prefix).
func Extra() (thirdParty []string, paths [][2]string) {
	for _, r := range pathRules {
		paths = append(paths, [2]string{r.host, r.prefix})
	}
	return append([]string(nil), thirdPartyBlock...), paths
}
