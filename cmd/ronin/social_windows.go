package main

import (
	_ "embed"
	"encoding/base64"
	"encoding/json"
	"html"
	"net/url"
	"strings"
)

// Safe Social: the family's social apps (Facebook, Instagram, YouTube, WhatsApp...) open full
// screen in a tab of their own, with ui/inject_social.js guarding them: sponsored posts away,
// risky links (Telegram, unknown groups, UPI, .apk, short links) stopped, scam messages marked.
// The launcher is ui/tools/social.html; its settings (and PIN) live in that page.

//go:embed ui/inject_social.js
var socialJS string

type socialCfg struct {
	Apps       []string `json:"apps"`
	Family     bool     `json:"family"`
	Reels      bool     `json:"reels"`
	YtRestrict bool     `json:"ytRestrict"`
}

var socialApps = map[string][2]string{ // id -> name, address (the launcher has the same list)
	"facebook": {"Facebook", "https://www.facebook.com/"}, "instagram": {"Instagram", "https://www.instagram.com/"},
	"youtube": {"YouTube", "https://www.youtube.com/"}, "whatsapp": {"WhatsApp", "https://web.whatsapp.com/"},
	"x": {"X", "https://x.com/"}, "threads": {"Threads", "https://www.threads.net/"}, "sharechat": {"ShareChat", "https://sharechat.com/"},
	"moj": {"Moj", "https://mojapp.in/"}, "linkedin": {"LinkedIn", "https://www.linkedin.com/"}, "pinterest": {"Pinterest", "https://www.pinterest.com/"},
	"snapchat": {"Snapchat", "https://web.snapchat.com/"}, "telegram": {"Telegram", "https://web.telegram.org/"},
}

func socialSettings() socialCfg {
	c := socialCfg{Apps: []string{"facebook", "instagram", "youtube", "whatsapp"}, YtRestrict: true}
	if store != nil && store.Get().Social != "" {
		_ = json.Unmarshal([]byte(store.Get().Social), &c)
	}
	return c
}

// socialBaseJS goes before inject_social.js in every tab: the family settings and the key.
func socialBaseJS() string {
	c := socialSettings()
	b, _ := json.Marshal(map[string]any{"k": toolsKey, "family": c.Family, "reels": c.Reels})
	return "window.__roninSocialCfg=" + string(b) + ";"
}

// showSocial opens Safe Social (the launcher, or straight an app) in its own full-screen tab.
func (w *Window) showSocial(open string) {
	for _, t := range w.tabs {
		if t.isSocial {
			if w.active != t {
				w.beforeSocial = w.active
				w.selectTab(t)
			}
			if open != "" {
				_ = t.wv.Navigate(open)
			}
			w.layout()
			return
		}
	}
	before := w.active
	t := w.newTab("", true)
	if t == nil {
		return
	}
	w.beforeSocial = before
	t.isNewTab = false
	t.isSocial = true
	t.isTools = true // the launcher page is one of Ronin's own pages
	t.title = "Safe Social"
	if t.c != nil {
		t.c.Init("window.__roninSocialCfg&&(window.__roninSocialCfg.social=true);")
	}
	if open == "" {
		open = socialHome()
	}
	_ = t.wv.Navigate(open)
	w.sendState()
	w.layout()
}

func socialHome() string {
	dark := "1"
	if themeName() == "light" {
		dark = "0"
	}
	return toolsPrefix + "social.html?k=" + toolsKey + "&dark=" + dark
}

// socialRequest handles https://ronin.data/social?do=open|home|close|cfg from a Safe Social tab.
func (t *Tab) socialRequest(u *url.URL) {
	q := u.Query()
	if q.Get("k") != toolsKey || !t.isSocial {
		return
	}
	if c := q.Get("cfg"); c != "" {
		if b, err := base64.StdEncoding.DecodeString(c); err == nil {
			var sc socialCfg
			if json.Unmarshal(b, &sc) == nil {
				_ = store.Update(func(s *settingsT) { s.Social = string(b) })
			}
		}
	}
	w := t.w
	switch q.Get("do") {
	case "open":
		if dest := q.Get("url"); strings.HasPrefix(dest, "https://") {
			_ = t.wv.Navigate(dest)
		}
	case "home":
		t.isTools = true
		_ = t.wv.Navigate(socialHome())
	case "close":
		back := w.beforeSocial
		w.closeTab(t)
		for _, x := range w.tabs {
			if x == back && !x.isSocial && !x.isTools {
				w.selectTab(x)
			}
		}
		w.layout()
	}
}

// socialBlock is the row of the family's apps on the new tab page.
func socialBlock() string {
	c := socialSettings()
	var b strings.Builder
	b.WriteString(`<h2>🛡️ Safe Social</h2><div class="grid">`)
	b.WriteString(`<a href="https://ronin.newtab/social?k=` + newTabKey + `">🛡️ सभी apps<small>सुरक्षित सोशल मीडिया</small></a>`)
	for _, id := range c.Apps {
		if a, ok := socialApps[id]; ok {
			b.WriteString(`<a href="https://ronin.newtab/social?app=` + url.QueryEscape(id) + `&k=` + newTabKey + `">` + html.EscapeString(a[0]) + `<small>Safe Social</small></a>`)
		}
	}
	b.WriteString(`</div>`)
	return b.String()
}
