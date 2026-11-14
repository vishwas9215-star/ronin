// Package focus is "Study time": during the hours the user chose (or for an
// hour started from the menu) time-wasting sites are paused.
package focus

import (
	"fmt"
	"net/url"
	"strings"
	"time"
)

// DefaultSites are paused during study time. YouTube itself stays open
// (study videos); only its Shorts are paused.
var DefaultSites = []string{
	"youtube.com/shorts", "instagram.com", "facebook.com", "x.com", "twitter.com",
	"snapchat.com", "sharechat.com", "mojapp.in", "reddit.com", "netflix.com",
	"primevideo.com", "hotstar.com", "jiocinema.com", "mxplayer.in", "pinterest.com",
}

// Plan is what the user set.
type Plan struct {
	On    bool     // daily study time switched on
	From  string   // "18:00"
	To    string   // "21:00" (may be past midnight: "22:00" -> "01:00")
	Sites []string // empty = DefaultSites
	Until int64    // unix seconds: "Study for 1 hour now" ends then
}

// ParseClock reads "HH:MM" into minutes after midnight.
func ParseClock(s string) (int, bool) {
	var h, m int
	if n, _ := fmt.Sscanf(strings.TrimSpace(s), "%d:%d", &h, &m); n != 2 || h < 0 || h > 23 || m < 0 || m > 59 {
		return 0, false
	}
	return h*60 + m, true
}

// Active reports whether study time is running at now, and when it ends.
func Active(p Plan, now time.Time) (bool, time.Time) {
	if p.Until > now.Unix() {
		return true, time.Unix(p.Until, 0)
	}
	if !p.On {
		return false, time.Time{}
	}
	from, ok1 := ParseClock(p.From)
	to, ok2 := ParseClock(p.To)
	if !ok1 || !ok2 || from == to {
		return false, time.Time{}
	}
	mins := now.Hour()*60 + now.Minute()
	midnight := time.Date(now.Year(), now.Month(), now.Day(), 0, 0, 0, 0, now.Location())
	if from < to {
		if mins >= from && mins < to {
			return true, midnight.Add(time.Duration(to) * time.Minute)
		}
		return false, time.Time{}
	}
	// over midnight
	if mins >= from {
		return true, midnight.AddDate(0, 0, 1).Add(time.Duration(to) * time.Minute)
	}
	if mins < to {
		return true, midnight.Add(time.Duration(to) * time.Minute)
	}
	return false, time.Time{}
}

// Paused reports whether the address is one of the study-time sites.
// An entry "site.com" covers its sub-domains; "site.com/path" only that path.
func Paused(p Plan, raw string) bool {
	u, err := url.Parse(raw)
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") {
		return false
	}
	host := strings.TrimPrefix(strings.ToLower(u.Hostname()), "www.")
	host = strings.TrimPrefix(host, "m.")
	sites := p.Sites
	if len(sites) == 0 {
		sites = DefaultSites
	}
	for _, s := range sites {
		s = strings.ToLower(strings.TrimSpace(s))
		s = strings.TrimPrefix(strings.TrimPrefix(s, "https://"), "http://")
		s = strings.TrimPrefix(s, "www.")
		d, path, _ := strings.Cut(s, "/")
		if d == "" {
			continue
		}
		if host != d && !strings.HasSuffix(host, "."+d) {
			continue
		}
		if path == "" || strings.HasPrefix(strings.TrimPrefix(u.Path, "/"), path) {
			return true
		}
	}
	return false
}
