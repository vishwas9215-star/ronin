// Package settings stores Ronin's small settings file and folder layout.
package settings

import (
	"encoding/json"
	"html"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"sync"

	"roninbrowser/internal/focus"
	"roninbrowser/internal/quicklinks"
	"roninbrowser/internal/safemoney"
)

// Settings is saved as settings.json in the Ronin data folder.
type Settings struct {
	HomePage          string            `json:"home_page"`
	UserBanks         []string          `json:"user_banks"`
	TrustedSites      []string          `json:"trusted_sites"`
	RememberBankLogin bool              `json:"remember_bank_login"` // keep Safe Money cookies between sessions
	SafeMoneyIdleMins int               `json:"safe_money_idle_minutes"`
	UpdateChannel     string            `json:"update_channel,omitempty"` // https URL of manifest.json
	AdblockOff        []string          `json:"adblock_off_sites"`        // "Pause ad-block on this site"
	TradingSites      []string          `json:"trading_sites"`            // "This is a trading site"
	KidsLevel         string            `json:"kids_level,omitempty"`     // "", "junior", "teen"
	KidsPIN           string            `json:"kids_pin,omitempty"`       // salted hash of the parent PIN
	KidsSites         []string          `json:"kids_sites"`               // extra sites a parent allowed
	AIProvider        string            `json:"ai_provider,omitempty"`    // "gemini" (default), "claude", "custom"
	AIModel           string            `json:"ai_model,omitempty"`
	AIBaseURL         string            `json:"ai_base_url,omitempty"`   // custom OpenAI-compatible
	AIDailyCapRupees  int               `json:"ai_daily_cap_rupees"`     // for paid providers
	VPNOn             bool              `json:"vpn_on"`                  // VPN for normal and private windows
	VPNName           string            `json:"vpn_name,omitempty"`      // shown in the panel
	VPNAll            bool              `json:"vpn_all,omitempty"`       // false: VPN only for unknown and sensitive sites
	SitePerms         map[string]string `json:"site_perms,omitempty"`    // "host|camera" -> "allow" / "block"
	NewTabFront       bool              `json:"new_tab_front,omitempty"` // true: a link's new tab comes to the front
	Theme             string            `json:"theme,omitempty"`         // "" = night (dark), "light" = day
	Bookmarks         []Bookmark        `json:"bookmarks,omitempty"`
	ZoomSites         map[string]int    `json:"zoom_sites,omitempty"`   // "site.com" -> 125 (per cent)
	FocusOn           bool              `json:"focus_on,omitempty"`     // daily study time
	FocusFrom         string            `json:"focus_from,omitempty"`   // "18:00"
	FocusTo           string            `json:"focus_to,omitempty"`     // "21:00"
	FocusSites        []string          `json:"focus_sites,omitempty"`  // empty = built-in list
	FocusUntil        int64             `json:"focus_until,omitempty"`  // "study 1 hour now" ends (unix)
	NoHistory         bool              `json:"no_history,omitempty"`   // do not keep visited pages
	QuickLinks        []quicklinks.Link `json:"quick_links,omitempty"`  // links the user added on the new tab page
	HiddenLinks       []string          `json:"hidden_links,omitempty"` // built-in quick links the user removed
}

// DefaultUpdateChannel is where Ronin looks for new versions (signed manifest).
const DefaultUpdateChannel = "https://github.com/vishwas9215-star/ronin/releases/latest/download/manifest.json"

// Defaults are used for a new install.
func Defaults() Settings {
	return Settings{HomePage: "ronin://newtab", SafeMoneyIdleMins: 5, AIProvider: "gemini", AIDailyCapRupees: 20,
		FocusFrom: "18:00", FocusTo: "21:00", UpdateChannel: DefaultUpdateChannel}
}

// Store loads and saves settings safely from several places.
type Store struct {
	mu   sync.Mutex
	path string
	s    Settings
}

// Open reads settings from dir (a missing or broken file gives defaults).
func Open(dir string) *Store {
	st := &Store{path: filepath.Join(dir, "settings.json"), s: Defaults()}
	if b, err := os.ReadFile(st.path); err == nil {
		var s Settings
		if json.Unmarshal(b, &s) == nil {
			st.s = fix(s)
		}
	}
	return st
}

func fix(s Settings) Settings {
	d := Defaults()
	if s.HomePage == "" {
		s.HomePage = d.HomePage
	}
	if s.SafeMoneyIdleMins < 1 || s.SafeMoneyIdleMins > 60 {
		s.SafeMoneyIdleMins = d.SafeMoneyIdleMins
	}
	if s.UpdateChannel == "" || !strings.HasPrefix(s.UpdateChannel, "https://") {
		s.UpdateChannel = DefaultUpdateChannel // the same GitHub release as Ronin for Android
	}
	s.UserBanks = cleanDomains(s.UserBanks)
	s.TrustedSites = cleanDomains(s.TrustedSites)
	s.AdblockOff = cleanDomains(s.AdblockOff)
	s.TradingSites = cleanDomains(s.TradingSites)
	s.KidsSites = cleanDomains(s.KidsSites)
	if s.KidsLevel != "junior" && s.KidsLevel != "teen" {
		s.KidsLevel = ""
	}
	switch s.AIProvider {
	case "gemini", "claude", "custom":
	default:
		s.AIProvider = "gemini"
	}
	if s.AIBaseURL != "" && !strings.HasPrefix(s.AIBaseURL, "https://") {
		s.AIBaseURL = ""
	}
	if s.AIDailyCapRupees < 0 || s.AIDailyCapRupees > 500 {
		s.AIDailyCapRupees = 20
	}
	if _, ok := focus.ParseClock(s.FocusFrom); !ok {
		s.FocusFrom = "18:00"
	}
	if _, ok := focus.ParseClock(s.FocusTo); !ok {
		s.FocusTo = "21:00"
	}
	for k, v := range s.ZoomSites {
		if v < 25 || v > 500 || k == "" {
			delete(s.ZoomSites, k)
		}
	}
	if len(s.VPNName) > 60 {
		s.VPNName = s.VPNName[:60]
	}
	return s
}

func cleanDomains(in []string) []string {
	seen := map[string]bool{}
	var out []string
	for _, d := range in {
		n := safemoney.NormalizeUserDomain(d)
		if n != "" && !seen[n] {
			seen[n] = true
			out = append(out, n)
		}
	}
	sort.Strings(out)
	return out
}

// Get returns a copy of the current settings.
func (st *Store) Get() Settings {
	st.mu.Lock()
	defer st.mu.Unlock()
	c := st.s
	c.UserBanks = append([]string(nil), st.s.UserBanks...)
	c.TrustedSites = append([]string(nil), st.s.TrustedSites...)
	c.AdblockOff = append([]string(nil), st.s.AdblockOff...)
	c.TradingSites = append([]string(nil), st.s.TradingSites...)
	c.KidsSites = append([]string(nil), st.s.KidsSites...)
	c.FocusSites = append([]string(nil), st.s.FocusSites...)
	c.QuickLinks = append([]quicklinks.Link(nil), st.s.QuickLinks...)
	c.HiddenLinks = append([]string(nil), st.s.HiddenLinks...)
	c.ZoomSites = make(map[string]int, len(st.s.ZoomSites))
	for k, v := range st.s.ZoomSites {
		c.ZoomSites[k] = v
	}
	return c
}

// Lists returns the user lists for the Safe Money checks.
func (st *Store) Lists() safemoney.Lists {
	s := st.Get()
	return safemoney.Lists{UserBanks: s.UserBanks, Trusted: s.TrustedSites, Trading: s.TradingSites}
}

// Update changes settings and saves them to disk.
func (st *Store) Update(change func(*Settings)) error {
	st.mu.Lock()
	defer st.mu.Unlock()
	s := st.s
	change(&s)
	s = fix(s)
	b, err := json.MarshalIndent(s, "", "  ")
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(st.path), 0o755); err != nil {
		return err
	}
	tmp := st.path + ".tmp"
	if err := os.WriteFile(tmp, b, 0o644); err != nil {
		return err
	}
	if err := os.Rename(tmp, st.path); err != nil {
		return err
	}
	st.s = s
	return nil
}

// AddBank adds a domain to "my banks"; returns false if it is not valid.
func (st *Store) AddBank(domain string) bool {
	d := safemoney.NormalizeUserDomain(domain)
	if d == "" {
		return false
	}
	return st.Update(func(s *Settings) { s.UserBanks = append(s.UserBanks, d) }) == nil
}

// Trust marks a site as trusted after a false fake-site warning.
func (st *Store) Trust(domain string) bool {
	d := safemoney.NormalizeUserDomain(domain)
	if d == "" {
		return false
	}
	return st.Update(func(s *Settings) { s.TrustedSites = append(s.TrustedSites, d) }) == nil
}

// AdblockPaused reports whether the user paused ad-block for host (or its parent).
func (st *Store) AdblockPaused(host string) bool {
	host = safemoney.NormalizeUserDomain(host)
	if host == "" {
		return false
	}
	st.mu.Lock()
	defer st.mu.Unlock()
	for _, d := range st.s.AdblockOff {
		if host == d || strings.HasSuffix(host, "."+d) {
			return true
		}
	}
	return false
}

// SetAdblockPaused pauses or resumes ad-block for a site.
func (st *Store) SetAdblockPaused(host string, paused bool) bool {
	d := safemoney.NormalizeUserDomain(host)
	if d == "" {
		return false
	}
	return st.Update(func(s *Settings) {
		var out []string
		for _, x := range s.AdblockOff {
			if x != d {
				out = append(out, x)
			}
		}
		if paused {
			out = append(out, d)
		}
		s.AdblockOff = out
	}) == nil
}

// SitePerm returns the remembered answer ("allow", "block" or "") for a site
// and a permission kind (camera, microphone, location, clipboard).
func (st *Store) SitePerm(host, kind string) string {
	return st.Get().SitePerms[strings.ToLower(host)+"|"+kind]
}

// SetSitePerm remembers (answer "allow"/"block") or forgets (answer "") a choice.
func (st *Store) SetSitePerm(host, kind, answer string) bool {
	host = strings.ToLower(strings.TrimSpace(host))
	if host == "" || strings.Contains(host, "|") || kind == "" {
		return false
	}
	return st.Update(func(s *Settings) {
		m := map[string]string{}
		for k, v := range s.SitePerms {
			m[k] = v
		}
		if answer == "" {
			delete(m, host+"|"+kind)
		} else if answer == "allow" || answer == "block" {
			m[host+"|"+kind] = answer
		}
		s.SitePerms = m
	}) == nil
}

// Bookmark is one saved page.
type Bookmark struct {
	Title string `json:"title"`
	URL   string `json:"url"`
}

// HasBookmark reports whether a page is bookmarked.
func (st *Store) HasBookmark(u string) bool {
	for _, b := range st.Get().Bookmarks {
		if b.URL == u {
			return true
		}
	}
	return false
}

// ToggleBookmark adds the page, or removes it when it is already there.
// It returns true when the page is bookmarked afterwards.
func (st *Store) ToggleBookmark(title, u string) bool {
	if !strings.HasPrefix(u, "http://") && !strings.HasPrefix(u, "https://") {
		return false
	}
	added := false
	_ = st.Update(func(s *Settings) {
		var out []Bookmark
		found := false
		for _, b := range s.Bookmarks {
			if b.URL == u {
				found = true
				continue
			}
			out = append(out, b)
		}
		if !found && len(out) < 500 {
			title = strings.TrimSpace(title)
			if title == "" {
				title = u
			}
			if r := []rune(title); len(r) > 120 {
				title = string(r[:120])
			}
			out = append([]Bookmark{{Title: title, URL: u}}, out...)
			added = true
		}
		s.Bookmarks = out
	})
	return added
}

// zoomKey is the site name zoom is kept for ("www.site.com" -> "site.com").
func zoomKey(host string) string {
	return strings.TrimPrefix(strings.ToLower(strings.TrimSpace(host)), "www.")
}

// Zoom is the page size kept for a site, in per cent (100 = normal).
func (st *Store) Zoom(host string) int {
	st.mu.Lock()
	defer st.mu.Unlock()
	if v, ok := st.s.ZoomSites[zoomKey(host)]; ok {
		return v
	}
	return 100
}

// SetZoom keeps the page size for a site (100 removes it).
func (st *Store) SetZoom(host string, pct int) {
	k := zoomKey(host)
	if k == "" || pct < 25 || pct > 500 || st.Zoom(k) == pct {
		return
	}
	_ = st.Update(func(s *Settings) {
		if pct == 100 {
			delete(s.ZoomSites, k)
			return
		}
		if s.ZoomSites == nil {
			s.ZoomSites = map[string]int{}
		}
		if len(s.ZoomSites) < 1000 {
			s.ZoomSites[k] = pct
		}
	})
}

// FocusPlan is the study-time plan.
func (st *Store) FocusPlan() focus.Plan {
	s := st.Get()
	return focus.Plan{On: s.FocusOn, From: s.FocusFrom, To: s.FocusTo, Sites: s.FocusSites, Until: s.FocusUntil}
}

// BookmarksHTML writes bookmarks in the common bookmarks file format that
// every browser can import (Netscape bookmark file).
func BookmarksHTML(list []Bookmark) string {
	var b strings.Builder
	b.WriteString("<!DOCTYPE NETSCAPE-Bookmark-file-1>\n<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">\n" +
		"<TITLE>Bookmarks</TITLE>\n<H1>Bookmarks</H1>\n<DL><p>\n    <DT><H3>Ronin Browser</H3>\n    <DL><p>\n")
	for _, x := range list {
		b.WriteString("        <DT><A HREF=\"" + html.EscapeString(x.URL) + "\">" + html.EscapeString(x.Title) + "</A>\n")
	}
	b.WriteString("    </DL><p>\n</DL><p>\n")
	return b.String()
}

var bookmarkLink = regexp.MustCompile(`(?is)<a\s[^>]*?href\s*=\s*"([^"]*)"[^>]*>(.*?)</a>`)
var anyTag = regexp.MustCompile(`(?s)<[^>]*>`)

// ParseBookmarksHTML reads a bookmarks file exported by any browser.
func ParseBookmarksHTML(s string) []Bookmark {
	var out []Bookmark
	for _, m := range bookmarkLink.FindAllStringSubmatch(s, 5000) {
		u := html.UnescapeString(strings.TrimSpace(m[1]))
		if !strings.HasPrefix(u, "http://") && !strings.HasPrefix(u, "https://") {
			continue
		}
		t := strings.TrimSpace(html.UnescapeString(anyTag.ReplaceAllString(m[2], "")))
		out = append(out, Bookmark{Title: t, URL: u})
	}
	return out
}

// AddBookmarks adds imported bookmarks that are not there yet; it returns how many.
func (st *Store) AddBookmarks(in []Bookmark) int {
	n := 0
	_ = st.Update(func(s *Settings) {
		have := map[string]bool{}
		for _, b := range s.Bookmarks {
			have[b.URL] = true
		}
		for _, b := range in {
			if have[b.URL] || len(s.Bookmarks) >= 2000 {
				continue
			}
			if b.Title == "" {
				b.Title = b.URL
			}
			if r := []rune(b.Title); len(r) > 120 {
				b.Title = string(r[:120])
			}
			have[b.URL] = true
			s.Bookmarks = append(s.Bookmarks, b)
			n++
		}
	})
	return n
}

// QuickLinks returns the new tab page links (built-in ones not hidden + the user's).
func (st *Store) QuickLinks() []quicklinks.Link {
	s := st.Get()
	return quicklinks.List(s.QuickLinks, s.HiddenLinks)
}

// AddQuickLink adds a link the user typed on the new tab page.
func (st *Store) AddQuickLink(l quicklinks.Link) bool {
	l, ok := quicklinks.Clean(l)
	if !ok {
		return false
	}
	return st.Update(func(s *Settings) {
		for _, h := range s.HiddenLinks {
			if h == l.URL { // adding a built-in link back un-hides it
				s.HiddenLinks = removeString(s.HiddenLinks, h)
				return
			}
		}
		s.QuickLinks = append(s.QuickLinks, l)
	}) == nil
}

// RemoveQuickLink removes the user's own link, or hides a built-in one.
func (st *Store) RemoveQuickLink(u string) {
	_ = st.Update(func(s *Settings) {
		var keep []quicklinks.Link
		mine := false
		for _, l := range s.QuickLinks {
			if l.URL == u {
				mine = true
				continue
			}
			keep = append(keep, l)
		}
		s.QuickLinks = keep
		if !mine {
			s.HiddenLinks = append(s.HiddenLinks, u)
		}
	})
}

func removeString(list []string, v string) []string {
	var out []string
	for _, x := range list {
		if x != v {
			out = append(out, x)
		}
	}
	return out
}
