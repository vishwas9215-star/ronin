// Package agentcore is the Ronin Agent's device side in Go (the laptop / Windows Ronin): the same rules as the
// Android AgentRules, the tool driver and the link to the relay. Pure Go, so every rule and the whole link is
// tested on Linux; only the window and the WebView2 glue live in cmd/roninagent (Windows).
//
// The owner's choices (27 Nov 2026): no time limit, no taps for work; never passwords / OTPs, human checks,
// orders / payments, money sites (read only) or home-network addresses; where the old design asked the owner,
// the action is refused and Claude tells him in chat.
package agentcore

import (
	"fmt"
	"net"
	"net/url"
	"regexp"
	"strconv"
	"strings"
)

var (
	// FINANCE: broker / bank / payment sites. Claude may only read them.
	FINANCE      = regexp.MustCompile(`(?i)(^|\.)(dhan\.co|zerodha\.com|fyers\.in|upstox\.com|angelone\.in|groww\.in|5paisa\.com|icicidirect\.com|hdfcsec\.com|sharekhan\.com|kotaksecurities\.com|motilaloswal\.com|paytm\.com|phonepe\.com|onlinesbi\.sbi|sbi\.co\.in|hdfcbank\.com|icicibank\.com|axisbank\.com|kotak\.com|pnbindia\.in|bankofbaroda\.in|razorpay\.com|payu\.in|billdesk\.com|ccavenue\.com|paypal\.com|npci\.org\.in)$`)
	GOV          = regexp.MustCompile(`(?i)(\.gov\.in|\.nic\.in)$`)
	moneyPath    = regexp.MustCompile(`(?i)order|payment|\bpay|transfer|withdraw|checkout|fund|trade|neft|imps|upi`)
	confirmQuery = regexp.MustCompile(`(?i)confirm|place|submit|execute|pay`)
	actionWord   = regexp.MustCompile(`(?i)delete|remove|unsubscribe|confirm|cancel|logout|log-out|signout|approve|order|pay|transfer|vote`)
	actionKey    = regexp.MustCompile(`(?i)action|confirm|^do$|cmd|^op$|delete|remove|unsub|approve|vote|pay|order|token`)
	localName    = regexp.MustCompile(`(?i)(^|\.)(localhost|local|internal|lan|home|corp|localdomain|home\.arpa)$`)
	secretWords  = regexp.MustCompile(`(?i)otp|one.?time|verification code|passcode|password|ओटीपी|पासवर्ड`)
	pinWord      = regexp.MustCompile(`(?i)\b[mt]?pin\b`)
	pinCodeAfter = regexp.MustCompile(`(?i)^\s*-?\s*code`)
	scriptRe     = regexp.MustCompile(`(?i)^\s*(javascript|vbscript):`)
	otpRe        = regexp.MustCompile(`^\d{4,8}$`)
	otpStrip     = regexp.MustCompile(`[\s\-.,_]`)
	idAadhaar    = regexp.MustCompile(`\b\d{4}\s?\d{4}\s?\d{4}\b`)
	idPAN        = regexp.MustCompile(`\b[A-Z]{5}\d{4}[A-Z]\b`)
	idLong       = regexp.MustCompile(`\b\d{9,18}\b`)
	digitsOnly   = regexp.MustCompile(`^[0-9.]+$`)
	hexParts     = regexp.MustCompile(`^(0x[0-9a-f]+\.?)+$`)
)

// IsMoney is Ronin's own bank list (plus FINANCE).
type IsMoney func(host string) bool

func noMoney(string) bool { return false }

// Host of a URL, lower case, without brackets or a trailing dot.
func Host(raw string) string {
	u, err := url.Parse(strings.TrimSpace(raw))
	if err != nil {
		return ""
	}
	return strings.TrimSuffix(strings.ToLower(u.Hostname()), ".")
}

// Registrable: a.b.co.in -> b.co.in, x.y.com -> y.com (enough for "is it another site?").
func Registrable(h string) string {
	var p []string
	for _, s := range strings.Split(h, ".") {
		if s != "" {
			p = append(p, s)
		}
	}
	if len(p) <= 2 {
		return h
	}
	switch p[len(p)-2] {
	case "co", "gov", "nic", "org", "net", "ac", "edu", "res":
		return strings.Join(p[len(p)-3:], ".")
	}
	return strings.Join(p[len(p)-2:], ".")
}

// Money: a money site (read only for Claude).
func Money(h string, isMoney IsMoney) bool {
	if isMoney == nil {
		isMoney = noMoney
	}
	return FINANCE.MatchString(h) || isMoney(h)
}

// IsPrivateHost: home network / this machine / CGNAT by the address itself, in every spelling browsers accept
// (2130706433, 0x7f.1, 0177.0.0.1, 127.1, ::ffff:127.0.0.1). Unreadable = private.
func IsPrivateHost(h0 string) bool {
	h := strings.TrimSuffix(strings.Trim(strings.ToLower(strings.TrimSpace(h0)), "[]"), ".")
	if h == "" || localName.MatchString(h) {
		return true
	}
	if b := IPv4(h); b != nil {
		return privateV4(b)
	}
	if strings.Contains(h, ":") {
		ip := net.ParseIP(strings.SplitN(h, "%", 2)[0])
		if ip == nil {
			return true
		}
		return PrivateAddress(ip)
	}
	return digitsOnly.MatchString(h) || hexParts.MatchString(h)
}

// IPv4: a host written as an IPv4 address in any form browsers accept (1-4 parts, decimal / 0octal / 0xhex).
func IPv4(h string) []byte {
	parts := strings.Split(h, ".")
	if len(parts) == 0 || len(parts) > 4 {
		return nil
	}
	nums := make([]uint64, len(parts))
	for i, p := range parts {
		if p == "" {
			return nil
		}
		var v uint64
		var err error
		switch {
		case strings.HasPrefix(p, "0x"):
			if len(p) > 2 {
				v, err = strconv.ParseUint(p[2:], 16, 64)
			}
		case len(p) > 1 && p[0] == '0':
			v, err = strconv.ParseUint(p[1:], 8, 64)
		default:
			v, err = strconv.ParseUint(p, 10, 64)
		}
		if err != nil {
			return nil
		}
		nums[i] = v
	}
	var v uint64
	for i := 0; i < len(nums)-1; i++ {
		if nums[i] > 255 {
			return nil
		}
		v |= nums[i] << (24 - 8*uint(i))
	}
	room := 32 - 8*uint(len(nums)-1)
	last := nums[len(nums)-1]
	if last >= 1<<room {
		return nil
	}
	v |= last
	return []byte{byte(v >> 24), byte(v >> 16), byte(v >> 8), byte(v)}
}

func privateV4(b []byte) bool {
	a, c := b[0], b[1]
	return a == 0 || a == 10 || a == 127 || (a == 169 && c == 254) || (a == 172 && c >= 16 && c <= 31) || (a == 192 && c == 168) ||
		(a == 100 && c >= 64 && c <= 127) || a >= 224
}

// PrivateAddress: loopback, private, link-local, unique-local, CGNAT, IPv4-mapped private, multicast, NAT64.
func PrivateAddress(ip net.IP) bool {
	if v4 := ip.To4(); v4 != nil && (len(ip) == 4 || isMapped(ip)) {
		return privateV4(v4)
	}
	b := ip.To16()
	if b == nil {
		return true
	}
	allZero := true
	for i := 0; i < 12; i++ {
		if b[i] != 0 {
			allZero = false
		}
	}
	if allZero {
		return true // ::, ::1, ::a.b.c.d
	}
	return b[0]&0xfe == 0xfc || (b[0] == 0xfe && b[1]&0xc0 == 0x80) || b[0] == 0xff || (b[0] == 0 && b[1] == 0x64 && b[2] == 0xff && b[3] == 0x9b)
}

func isMapped(ip net.IP) bool {
	b := ip.To16()
	for i := 0; i < 10; i++ {
		if b[i] != 0 {
			return false
		}
	}
	return b[10] == 0xff && b[11] == 0xff
}

// SecretLabel: a box label that names a secret. A postal "PIN code" / "पिन कोड" is not one; MPIN / UPI PIN is.
func SecretLabel(label string) bool {
	if secretWords.MatchString(label) {
		return true
	}
	for _, m := range pinWord.FindAllStringIndex(label, -1) {
		if !pinCodeAfter.MatchString(label[m[1]:]) {
			return true
		}
	}
	for i := 0; ; {
		j := strings.Index(label[i:], "पिन")
		if j < 0 {
			break
		}
		rest := strings.TrimLeft(label[i+j+len("पिन"):], " \t")
		if !strings.HasPrefix(rest, "कोड") {
			return true
		}
		i += j + len("पिन")
	}
	return false
}

// LooksLikeOtp: 4-8 digits (spaces / dashes ignored).
func LooksLikeOtp(text string) bool { return otpRe.MatchString(otpStrip.ReplaceAllString(text, "")) }

// NumberNeedsOwner: 4-8 digits into a secret-looking box, or anywhere on a page that asks for an OTP.
func NumberNeedsOwner(label, text string, otpPage bool) bool {
	return LooksLikeOtp(text) && (SecretLabel(label) || otpPage)
}

// Sensitive: money, government or a page that showed ID numbers (no screenshots there; taint after reading).
func Sensitive(h string, isMoney IsMoney, idHosts map[string]bool) bool {
	return Money(h, isMoney) || GOV.MatchString(h) || idHosts[h]
}

// OpenRefusal: why Claude may not open this address ("" = fine).
func OpenRefusal(raw string, isMoney IsMoney, readPages map[string]map[string]bool, taint string) string {
	u, err := url.Parse(strings.TrimSpace(raw))
	if err != nil {
		return "not a web address"
	}
	scheme := strings.ToLower(u.Scheme)
	if scheme != "http" && scheme != "https" {
		return scheme + ": addresses are not allowed (only http / https)"
	}
	h := Host(raw)
	if h == "" {
		return "not a web address"
	}
	if IsPrivateHost(h) {
		return "home-network / server addresses are never opened by Claude"
	}
	if Money(h, isMoney) {
		path := u.EscapedPath()
		if path == "" {
			path = "/"
		}
		if u.RawQuery != "" || u.ForceQuery {
			return "money site: no query strings for Claude"
		}
		if path != "/" && !readPages[h][path] {
			return h + path + " is not one of the owner's read pages for this money site"
		}
	}
	payload := len(u.RawQuery) + len(u.EscapedFragment())
	if taint != "" && Registrable(h) != Registrable(taint) && payload > 30 {
		return "Claude read " + taint + " (sensitive); sending data to " + h + " in the address is refused"
	}
	if payload > 300 {
		return "too much data in the address"
	}
	return ""
}

// ActionLink: a link that looks like it DOES something (heuristic).
func ActionLink(href, base string) bool {
	if scriptRe.MatchString(href) {
		return true
	}
	b, err := url.Parse(base)
	if err != nil {
		return true
	}
	r, err := url.Parse(strings.TrimSpace(href))
	if err != nil {
		return true
	}
	u := b.ResolveReference(r)
	last := ""
	for _, s := range strings.Split(u.Path, "/") {
		if s != "" {
			last = s
		}
	}
	if actionWord.MatchString(last) || strings.Contains(strings.ToLower(u.Path), "/api/") {
		return true
	}
	for _, kv := range strings.Split(u.RawQuery, "&") {
		k := strings.SplitN(kv, "=", 2)[0]
		if k != "" && actionKey.MatchString(k) {
			return true
		}
	}
	return false
}

// ScriptLink: javascript: / vbscript:.
func ScriptLink(href string) bool { return scriptRe.MatchString(href) && !postbackRe.MatchString(href) }

// ASP.NET buttons drawn as links (government portals): they only POST the page's own form; the money / order
// checks still apply to that POST. The same rule as Android.
var postbackRe = regexp.MustCompile(`^\s*javascript:\s*(__doPostBack\(\s*'[^'()\\]*'\s*,\s*'[^'()\\]*'\s*\)|WebForm_DoPostBackWithOptions\(\s*new\s+WebForm_PostBackOptions\((\s|,|"[^"()\\]*"|'[^'()\\]*'|true|false)*\)\s*\))\s*;?\s*$`)

// Guard: the network guard for every request of the Claude tab while the owner is NOT holding the screen.
// claudeInput = Claude's own tap / key in the last 2 s. Returns the reason to block, or "".
func Guard(method, raw string, mainFrame, claudeInput bool, isMoney IsMoney, taint, currentHost string) string {
	scheme := strings.ToLower(strings.SplitN(raw, ":", 2)[0])
	switch scheme {
	case "http", "https", "ws", "wss":
	default:
		return "" // data:, blob:, about: never leave the device
	}
	u, err := url.Parse(raw)
	if err != nil {
		return "unreadable address"
	}
	h := Host(raw)
	if h == "" {
		return "address without a host"
	}
	if IsPrivateHost(h) {
		return "private address " + h
	}
	path, q, m := u.EscapedPath(), u.RawQuery, strings.ToUpper(method)
	if m != "GET" && m != "HEAD" && m != "OPTIONS" && Money(h, isMoney) {
		return m + " to money site " + h
	}
	if m != "GET" && moneyPath.MatchString(path+"?"+q) {
		return fmt.Sprintf("%s %s (money / order request)", m, path)
	}
	var keys []string
	for _, kv := range strings.Split(q, "&") {
		keys = append(keys, strings.SplitN(kv, "=", 2)[0])
	}
	ks := path + " " + strings.Join(keys, " ")
	if m == "GET" && q != "" && moneyPath.MatchString(ks) && confirmQuery.MatchString(ks) {
		return "GET " + path + "?" + q + " (money / order request)"
	}
	if claudeInput && mainFrame && taint != "" && h != currentHost && Registrable(h) != Registrable(taint) && len(q)+len(u.EscapedFragment()) > 30 {
		return "leaving " + taint + " (sensitive data was read) for " + h + " with data in the address"
	}
	return ""
}

// Redact hides ID numbers (Aadhaar-like, PAN, long account numbers).
func Redact(s string) string {
	s = idAadhaar.ReplaceAllString(s, "[aadhaar-like hidden]")
	s = idPAN.ReplaceAllString(s, "[PAN hidden]")
	return idLong.ReplaceAllString(s, "[number hidden]")
}
