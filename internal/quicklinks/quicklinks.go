// Package quicklinks holds the small links on the new tab page (Windows and
// Android show the same ones). The user can add his own and hide any link.
package quicklinks

import (
	"net/url"
	"strings"
)

// Link is one quick link. Section is one of the section names below.
type Link struct {
	Section string `json:"section"`
	Name    string `json:"name"`
	URL     string `json:"url"`
}

// Sections in the order they are shown.
var Sections = []string{"Quick links", "School", "Trading", "Video calls"}

// Defaults are the built-in links.
var Defaults = []Link{
	{"Quick links", "YouTube", "https://www.youtube.com/"},
	{"Quick links", "YouTube Music", "https://music.youtube.com/"},
	{"Quick links", "Gmail", "https://mail.google.com/"},
	{"Quick links", "DIKSHA", "https://diksha.gov.in/"},
	{"Quick links", "Wikipedia", "https://hi.wikipedia.org/"},
	{"School", "मानव संपदा", "https://ehrms.upsdc.gov.in/"},
	{"School", "प्रेरणा", "https://prernaup.in/"},
	{"School", "UDISE+ All Modules", "https://udiseplus.gov.in/#/en/page/oldlink/109"},
	{"Trading", "Ronin Terminal", "http://127.0.0.1:8791/"},
	{"Trading", "Dhan", "https://web.dhan.co/"},
	{"Trading", "Dhan TradingView", "https://tv.dhan.co/"},
	{"Trading", "TradingView", "https://in.tradingview.com/chart/"},
	{"Trading", "Fyers", "https://trade.fyers.in/"},
	{"Video calls", "Google Meet", "https://meet.google.com/"},
	{"Video calls", "WhatsApp Web", "https://web.whatsapp.com/"},
	{"Video calls", "Zoom", "https://app.zoom.us/wc"},
	{"Video calls", "Microsoft Teams", "https://teams.live.com/"},
	{"Video calls", "Jitsi Meet", "https://meet.jit.si/"},
}

// Clean checks a link the user typed. It returns false when it cannot be used.
func Clean(l Link) (Link, bool) {
	l.Name = strings.TrimSpace(l.Name)
	u := strings.TrimSpace(l.URL)
	if u == "" {
		return l, false
	}
	if !strings.Contains(u, "://") {
		u = "https://" + u
	}
	p, err := url.Parse(u)
	if err != nil || (p.Scheme != "https" && p.Scheme != "http") || !strings.Contains(p.Hostname(), ".") && p.Hostname() != "127.0.0.1" {
		return l, false
	}
	if p.Path == "" {
		p.Path = "/"
	}
	l.URL = p.String()
	if l.Name == "" {
		l.Name = strings.TrimPrefix(p.Hostname(), "www.")
	}
	if len([]rune(l.Name)) > 30 {
		l.Name = string([]rune(l.Name)[:30])
	}
	ok := false
	for _, s := range Sections {
		if l.Section == s {
			ok = true
		}
	}
	if !ok {
		l.Section = Sections[0]
	}
	return l, true
}

// List gives the links to show: built-in ones the user did not hide, then his own.
func List(own []Link, hidden []string) []Link {
	hide := map[string]bool{}
	for _, h := range hidden {
		hide[h] = true
	}
	var out []Link
	for _, l := range append(append([]Link(nil), Defaults...), own...) {
		if !hide[l.URL] {
			out = append(out, l)
		}
	}
	return out
}

// In returns the links of one section.
func In(all []Link, section string) []Link {
	var out []Link
	for _, l := range all {
		if l.Section == section {
			out = append(out, l)
		}
	}
	return out
}
