package safemoney

import (
	"net/url"
	"strings"
)

// Kind says what sort of site an address is.
type Kind int

const (
	General Kind = iota // any normal site
	Bank                // a real bank: opens in Ronin Safe Money
	Trading             // Dhan, TradingView, Ronin Terminal
	Payment             // payment gateway / OTP page: stays in its tab
	Auth                // sign-in page used by trading sites
	Local               // about:, data:, blob: and Ronin's own pages
	Call                // video call site (Meet, Zoom, WhatsApp Web ...): best call quality
)

func (k Kind) String() string {
	switch k {
	case Bank:
		return "bank"
	case Trading:
		return "trading"
	case Payment:
		return "payment"
	case Auth:
		return "auth"
	case Local:
		return "local"
	case Call:
		return "call"
	}
	return "general"
}

// Verdict is the result of checking one address.
type Verdict struct {
	Kind      Kind
	Host      string // lower-case host, punycode form (as the browser sends it)
	Lookalike bool   // looks like a bank but is not one: show the red warning
	Brand     string // which brand it imitates (for the warning text)
}

// Lists lets the user add their own banks and mark sites as trusted.
type Lists struct {
	UserBanks []string // "This is my bank" button
	Trusted   []string // "I trust this site" after a false warning
	Trading   []string // "This is a trading site" (charts stay live)
}

// Classify checks one top-level address.
func Classify(raw string, l Lists) Verdict {
	u, err := url.Parse(strings.TrimSpace(raw))
	if err != nil {
		return Verdict{Kind: General}
	}
	scheme := strings.ToLower(u.Scheme)
	switch scheme {
	case "http", "https":
	case "about", "data", "blob", "ronin", "edge", "devtools":
		return Verdict{Kind: Local}
	default:
		return Verdict{Kind: General}
	}
	host := normalizeHost(u.Hostname())
	v := Verdict{Kind: General, Host: host}
	if host == "" {
		return v
	}
	if isLoopback(host) {
		if u.Port() == terminalPort {
			v.Kind = Trading
		} else {
			v.Kind = Local
		}
		return v
	}
	switch {
	case IsBankHost(host, l.UserBanks):
		v.Kind = Bank
		return v
	case matchAny(host, tradingDomains) || matchAny(host, l.Trading):
		v.Kind = Trading
		return v
	case matchAny(host, paymentDomains):
		v.Kind = Payment
		return v
	case matchAny(host, authDomains):
		v.Kind = Auth
		return v
	case IsCallHost(host):
		v.Kind = Call
		return v
	}
	if matchAny(host, familyDomains) || matchAny(host, l.Trusted) {
		return v
	}
	if b, ok := lookalikeBrand(host); ok {
		v.Lookalike = true
		v.Brand = b
	}
	return v
}

// callDomains are video call sites. They open in Ronin's Video call window:
// no VPN and full-speed calls (direct UDP), so picture and voice stay sharp.
var callDomains = []string{
	"meet.google.com", "zoom.us", "zoom.com", "teams.microsoft.com", "teams.live.com",
	"teams.cloud.microsoft", "web.whatsapp.com", "meet.jit.si", "8x8.vc", "webex.com",
	"whereby.com", "web.skype.com", "talky.io", "jiomeet.jio.com", "vc.gov.in", "meet.nic.in",
}

// IsCallHost reports whether host is a video call site.
func IsCallHost(host string) bool {
	return matchAny(normalizeHost(host), callDomains)
}

// IsBankHost reports whether host is a real bank (*.bank.in, .sbi, the
// built-in list or one the user added).
func IsBankHost(host string, userBanks []string) bool {
	host = normalizeHost(host)
	if host == "" {
		return false
	}
	if strings.HasSuffix(host, ".bank.in") {
		return true
	}
	return matchAny(host, bankDomains) || matchAny(host, userBanks)
}

// NormalizeUserDomain turns what the user typed or the current page address
// into a plain domain for the user lists ("https://www.MyBank.com/x" -> "mybank.com").
func NormalizeUserDomain(s string) string {
	s = strings.TrimSpace(s)
	if !strings.Contains(s, "://") {
		s = "https://" + s
	}
	u, err := url.Parse(s)
	if err != nil {
		return ""
	}
	h := normalizeHost(u.Hostname())
	h = strings.TrimPrefix(h, "www.")
	if h == "" || !strings.Contains(h, ".") || isLoopback(h) {
		return ""
	}
	return h
}

func normalizeHost(h string) string {
	h = strings.ToLower(strings.TrimSpace(h))
	h = strings.TrimSuffix(h, ".")
	h = strings.Trim(h, "[]")
	return h
}

func isLoopback(h string) bool {
	return h == "localhost" || h == "127.0.0.1" || h == "::1" || strings.HasPrefix(h, "127.")
}

// matchAny is true when host equals a domain in the list or is a sub-domain of it.
func matchAny(host string, list []string) bool {
	for _, d := range list {
		d = normalizeHost(d)
		if d == "" {
			continue
		}
		if host == d || strings.HasSuffix(host, "."+d) {
			return true
		}
	}
	return false
}

// linkRedirectors are pages that only forward a click to another site.
var linkRedirectors = []struct{ host, path string }{
	{"google.com", "/url"}, {"google.co.in", "/url"}, {"l.instagram.com", "/"},
	{"l.facebook.com", "/"}, {"lm.facebook.com", "/"}, {"t.co", "/"},
	{"youtube.com", "/redirect"}, {"l.messenger.com", "/"}, {"out.reddit.com", "/"},
	{"bing.com", "/ck/"}, {"duckduckgo.com", "/l/"}, {"t.me", "/iv"},
}

// IsLinkRedirector reports whether raw is a click-forwarding page.
func IsLinkRedirector(raw string) bool {
	u, err := url.Parse(raw)
	if err != nil || (u.Scheme != "https" && u.Scheme != "http") {
		return false
	}
	h := strings.TrimPrefix(normalizeHost(u.Hostname()), "www.")
	for _, r := range linkRedirectors {
		if (h == r.host || strings.HasSuffix(h, "."+r.host)) && strings.HasPrefix(u.Path+"/", r.path) {
			return true
		}
	}
	return false
}
