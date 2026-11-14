// Package safelinks decides which links Safe Social never opens: Telegram and group
// invites, UPI / payment app links, call-forwarding codes, app files, short links,
// remote-control apps, addresses with tricks (user@host, bare IP, punycode) and fake
// "bank / KYC / reward" sites. The same rules run in the page (ui/tools/scamcheck.js)
// and on Android (SafeLinks.kt); the word lists are lists.json, the cases cases.json.
package safelinks

import (
	_ "embed"
	"encoding/json"
	"net/url"
	"strings"
)

//go:embed lists.json
var ListsJSON []byte

// ScamRulesJSON: the scam-message rules for the page script and Android (scamrules.json).
//
//go:embed scamrules.json
var ScamRulesJSON []byte

type lists struct {
	PaySchemes   []string `json:"paySchemes"`
	BadSchemes   []string `json:"badSchemes"`
	Telegram     []string `json:"telegram"`
	Invites      []string `json:"invites"`
	Short        []string `json:"short"`
	Remote       []string `json:"remote"`
	ApkHosts     []string `json:"apkHosts"`
	ApkWords     []string `json:"apkWords"`
	FileExt      []string `json:"fileExt"`
	MoneyWords   []string `json:"moneyWords"`
	FreeHosts    []string `json:"freeHosts"`
	LureWords    []string `json:"lureWords"`
	OfficialEnds []string `json:"officialEnds"`
}

var L = func() lists {
	var l lists
	if err := json.Unmarshal(ListsJSON, &l); err != nil {
		panic(err)
	}
	return l
}()

// brands and verbs for fake bank / payment / government sites ("sbi-kyc-update.xyz")
var brands = []string{"sbi", "hdfc", "icici", "axis", "pnb", "kotak", "canara", "bob", "paytm", "phonepe", "gpay", "uidai", "aadhaar", "aadhar", "epfo", "incometax", "irctc", "uppcl", "npci", "rbi", "sebi"}
var verbs = []string{"kyc", "ekyc", "login", "verify", "verification", "update", "secure", "reward", "rewards", "refund", "cashback", "bonus", "support", "care", "helpline", "otp", "pan", "link", "points", "redeem", "block", "unblock"}

func in(list []string, s string) bool {
	for _, x := range list {
		if x == s {
			return true
		}
	}
	return false
}

// hostIs: the host itself or a sub-domain of it ("web.telegram.org" is telegram.org).
func hostIs(host, d string) bool { return host == d || strings.HasSuffix(host, "."+d) }

func hostPathIn(host, path string, list []string) bool {
	for _, d := range list {
		if j := strings.Index(d, "/"); j > 0 {
			if hostIs(host, d[:j]) && strings.HasPrefix(path, d[j:]) {
				return true
			}
			continue
		}
		if hostIs(host, d) {
			return true
		}
	}
	return false
}

func isIP(host string) bool {
	if strings.Contains(host, ":") || strings.HasPrefix(host, "[") {
		return true // IPv6
	}
	if strings.HasPrefix(host, "0x") {
		return true
	}
	dig := true
	for _, r := range host {
		if !(r >= '0' && r <= '9') && r != '.' {
			dig = false
			break
		}
	}
	return dig && host != ""
}

// Tokens of a host name without its ending: "sbi-kyc-update.web.app" -> sbi kyc update web.
func tokens(host string) []string {
	f := func(r rune) bool { return r == '.' || r == '-' || r == '_' || (r >= '0' && r <= '9') }
	return strings.FieldsFunc(host, f)
}

// Check gives the reason a link must not open from Safe Social, or "".
// Reasons: pay, ussd, scheme, invite, at, ip, puny, telegram, remote, short, apk, file, money, fake.
// Adult sites and look-alike banks are checked by the callers' own lists.
func Check(raw string) string {
	raw = strings.TrimSpace(raw)
	low := strings.ToLower(raw)
	i := strings.Index(low, ":")
	if i <= 0 {
		return ""
	}
	scheme := low[:i]
	switch {
	case in(L.PaySchemes, scheme):
		return "pay"
	case scheme == "tel":
		if strings.ContainsAny(low, "*#") || strings.Contains(low, "%2a") || strings.Contains(low, "%23") {
			return "ussd"
		}
		return ""
	case scheme == "whatsapp":
		if strings.Contains(low, "code=") || strings.Contains(low, "invite") {
			return "invite"
		}
		return ""
	case in(L.BadSchemes, scheme):
		return "scheme"
	case scheme != "http" && scheme != "https":
		return ""
	}
	u, err := url.Parse(raw)
	if err != nil {
		return "" // such an address does not load anyway
	}
	if u.User != nil {
		return "at"
	}
	host := strings.TrimSuffix(strings.ToLower(u.Hostname()), ".")
	if host == "" {
		return ""
	}
	if isIP(host) {
		return "ip"
	}
	if strings.HasPrefix(host, "xn--") || strings.Contains(host, ".xn--") {
		return "puny"
	}
	path := strings.ToLower(u.EscapedPath())
	switch {
	case hostPathIn(host, path, L.Telegram):
		return "telegram"
	case hostPathIn(host, path, L.Invites):
		return "invite"
	case (hostIs(host, "wa.me") || hostIs(host, "whatsapp.com")) && (strings.Contains(path, "invite") || strings.Contains(path, "/join")):
		return "invite"
	case hostPathIn(host, path, L.Remote):
		return "remote"
	case hostPathIn(host, path, L.Short):
		return "short"
	case hostPathIn(host, path, L.ApkHosts):
		return "apk"
	}
	for _, e := range L.FileExt {
		if strings.HasSuffix(path, e) {
			if strings.Contains(e, "apk") {
				return "apk"
			}
			return "file"
		}
	}
	tok := tokens(host)
	for _, t := range tok {
		if in(L.ApkWords, t) {
			return "apk"
		}
	}
	for _, e := range L.OfficialEnds {
		if hostIs(host, e) {
			return ""
		}
	}
	// the site's own name: everything before the ending (".com", ".co.in", ".web.app")
	name := tok
	if len(name) > 1 {
		name = name[:len(name)-1]
	}
	if len(name) > 1 && in([]string{"co", "com", "org", "net", "gov", "ac", "edu"}, name[len(name)-1]) {
		name = name[:len(name)-1]
	}
	for _, t := range name {
		for _, w := range L.MoneyWords {
			if t == w || (len(w) >= 5 && (strings.HasPrefix(t, w) || strings.HasSuffix(t, w))) {
				return "money"
			}
		}
	}
	// free web hosting with a bank / KYC / reward name: "sbi-kyc.web.app", sites.google.com/view/pm-yojana
	free := false
	words := name
	for _, d := range L.FreeHosts {
		if hostIs(host, d) && host != d {
			free = true
		} else if host == d && d == "sites.google.com" {
			free, words = true, tokens(path)
		}
	}
	lure := false
	for _, t := range words {
		if in(L.LureWords, t) {
			lure = true
		}
	}
	if free && lure {
		return "fake"
	}
	hasBrand, hasVerb := false, false
	for _, t := range name {
		if in(brands, t) {
			hasBrand = true
		}
		if in(verbs, t) {
			hasVerb = true
		}
	}
	if hasBrand && hasVerb {
		return "fake"
	}
	return ""
}
