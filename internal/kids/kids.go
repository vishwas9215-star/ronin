// Package kids holds the rules for Ronin's KIDS mode: which sites a child
// may open, forced SafeSearch, and adult / gambling site detection.
package kids

import (
	"crypto/sha256"
	"encoding/hex"
	"net/url"
	"strings"
)

// Level is the strictness of KIDS mode.
type Level string

const (
	Off    Level = ""
	Junior Level = "junior" // only chosen learning sites
	Teen   Level = "teen"   // all sites except adult, gambling and dangerous ones
)

func (l Level) Valid() bool { return l == Off || l == Junior || l == Teen }

// juniorSites are allowed in Junior mode (and their sub-domains).
var juniorSites = []string{
	"diksha.gov.in", "ncert.nic.in", "epathshala.nic.in", "swayam.gov.in", "pmevidya.education.gov.in",
	"youtubekids.com", "khanacademy.org", "wikipedia.org", "wikimedia.org", "wiktionary.org",
	"britannica.com", "nationalgeographic.com", "kids.nationalgeographic.com", "pbskids.org",
	"storyweaver.org.in", "readalong.google", "code.org", "scratch.mit.edu", "duolingo.com",
	"google.com", "google.co.in", "gstatic.com", "googleapis.com", "ytimg.com", "googlevideo.com",
	"upsdc.gov.in", "basiceducation.up.gov.in",
}

// JuniorAllowed reports whether a page may open in Junior mode.
func JuniorAllowed(raw string, extra []string) bool {
	h := host(raw)
	if h == "" {
		return false
	}
	for _, d := range append(append([]string{}, juniorSites...), extra...) {
		d = strings.ToLower(strings.TrimSpace(d))
		if d != "" && (h == d || strings.HasSuffix(h, "."+d)) {
			// Google is allowed for search only, never for other Google services
			if (d == "google.com" || d == "google.co.in") && !isGoogleSearchHost(h) {
				continue
			}
			return true
		}
	}
	return false
}

func isGoogleSearchHost(h string) bool {
	return h == "google.com" || h == "www.google.com" || h == "google.co.in" || h == "www.google.co.in"
}

// adultWords are whole name-parts that mark adult or gambling sites.
var adultWords = []string{
	"porn", "porno", "xxx", "xvideos", "xnxx", "xhamster", "redtube", "youporn", "pornhub", "sex", "sexy",
	"nude", "nudes", "hentai", "erotic", "escort", "escorts", "camgirl", "onlyfans", "brazzers", "milf",
	"casino", "casinos", "betting", "bet365", "1xbet", "dafabet", "parimatch", "satta", "matka",
	"rummy", "teenpatti", "poker", "lottery", "gambling", "slots", "dream11", "mpl",
}

// LooksAdult reports whether a host name itself shows an adult or gambling site.
func LooksAdult(raw string) bool {
	h := host(raw)
	if h == "" {
		return false
	}
	parts := strings.FieldsFunc(h, func(r rune) bool { return r == '.' || r == '-' || r == '_' })
	for _, p := range parts {
		for _, w := range adultWords {
			if p == w {
				return true
			}
			// glued names like "freeporn", "pornhd", "bigcasino", "satta-king" (already split)
			if len(w) >= 4 && w != "slots" && w != "poker" && strings.Contains(p, w) && w != "sexy" && w != "sex" {
				return true
			}
		}
	}
	return false
}

// SafeSearchURL returns the address with SafeSearch switched on, or "" when
// nothing has to change.
func SafeSearchURL(raw string) string {
	u, err := url.Parse(raw)
	if err != nil {
		return ""
	}
	h := strings.ToLower(u.Hostname())
	q := u.Query()
	switch {
	case (strings.Contains(h, "google.") && (u.Path == "/search" || u.Path == "/webhp" || u.Path == "/imghp")):
		if q.Get("safe") == "active" {
			return ""
		}
		q.Set("safe", "active")
	case strings.HasSuffix(h, "bing.com") && strings.HasPrefix(u.Path, "/search") || strings.HasSuffix(h, "bing.com") && strings.HasPrefix(u.Path, "/images"):
		if q.Get("adlt") == "strict" {
			return ""
		}
		q.Set("adlt", "strict")
	case strings.HasSuffix(h, "duckduckgo.com") && u.Path == "/":
		if q.Get("q") == "" || q.Get("kp") == "1" {
			return ""
		}
		q.Set("kp", "1")
	default:
		return ""
	}
	u.RawQuery = q.Encode()
	return u.String()
}

// IsYouTube reports whether a request goes to YouTube (for the
// "YouTube-Restrict: Strict" header that turns on Restricted Mode).
func IsYouTube(raw string) bool {
	h := host(raw)
	for _, d := range []string{"youtube.com", "youtubei.googleapis.com", "youtube.googleapis.com", "youtube-nocookie.com"} {
		if h == d || strings.HasSuffix(h, "."+d) {
			return true
		}
	}
	return false
}

// HashPIN stores the parent PIN safely (never in plain text).
func HashPIN(pin, salt string) string {
	s := sha256.Sum256([]byte("ronin-kids|" + salt + "|" + pin))
	return hex.EncodeToString(s[:])
}

// ValidPIN accepts 4 to 8 digits.
func ValidPIN(pin string) bool {
	if len(pin) < 4 || len(pin) > 8 {
		return false
	}
	for _, r := range pin {
		if r < '0' || r > '9' {
			return false
		}
	}
	return true
}

func host(raw string) string {
	u, err := url.Parse(raw)
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") {
		return ""
	}
	return strings.ToLower(strings.TrimSuffix(u.Hostname(), "."))
}
