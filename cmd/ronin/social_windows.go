package main

import (
	"crypto/rand"
	_ "embed"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"html"
	"net/url"
	"strconv"
	"strings"

	"roninbrowser/internal/kids"
	"roninbrowser/internal/safelinks"
	"roninbrowser/internal/safemoney"
)

// Safe Social: the family's social apps (Facebook, Instagram, YouTube, WhatsApp...) open full
// screen in a tab of their own, with ui/inject_social.js guarding them: sponsored posts away,
// risky links (Telegram, unknown groups, UPI, .apk, short links) stopped, scam messages marked.
// The launcher is ui/tools/social.html; its settings (and PIN) live in that page.

//go:embed ui/inject_social.js
var socialPageJS string

//go:embed ui/tools/scamcheck.js
var scamCheckJS string

// socialJS: the scam engine, then the protection that uses it (every tab: Safe Mail works anywhere).
var socialJS = scamCheckJS + "\n" + socialPageJS

// socialKey lets a social page ask only "back to my apps" / "close" (websites can read it; it
// opens nothing and changes no setting). Settings and "open this app" need toolsKey, which only
// Ronin's own launcher page has.
var socialKey = func() string {
	b := make([]byte, 16)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}()

type customApp struct {
	Name string `json:"name"`
	URL  string `json:"url"`
	Mail bool   `json:"mail"`
}

type socialCfg struct {
	Apps       []string    `json:"apps"`
	Family     bool        `json:"family"`
	Reels      bool        `json:"reels"`
	YtRestrict bool        `json:"ytRestrict"`
	Custom     []customApp `json:"custom"`
	MailApp    string      `json:"mailApp"` // the email the "Ronin Mail" icon opens
}

var socialApps = map[string][2]string{ // id -> name, address (the launcher has the same list)
	"facebook": {"Facebook", "https://www.facebook.com/"}, "instagram": {"Instagram", "https://www.instagram.com/"},
	"youtube": {"YouTube", "https://www.youtube.com/"}, "whatsapp": {"WhatsApp", "https://web.whatsapp.com/"},
	"x": {"X", "https://x.com/"}, "threads": {"Threads", "https://www.threads.net/"}, "sharechat": {"ShareChat", "https://sharechat.com/"},
	"moj": {"Moj", "https://mojapp.in/"}, "linkedin": {"LinkedIn", "https://www.linkedin.com/"}, "pinterest": {"Pinterest", "https://www.pinterest.com/"},
	"snapchat": {"Snapchat", "https://web.snapchat.com/"}, "telegram": {"Telegram", "https://web.telegram.org/"},
	"gmail": {"Gmail", "https://mail.google.com/"}, "outlook": {"Outlook", "https://outlook.live.com/mail/"},
	"yahoo": {"Yahoo Mail", "https://mail.yahoo.com/"}, "rediff": {"Rediffmail", "https://mail.rediff.com/"},
}

// appURL: a known app id, or one of the family's own apps ("c0", "c1"...).
func appURL(id string) (name, address string) {
	if a, ok := socialApps[id]; ok {
		return a[0], a[1]
	}
	for i, x := range socialSettings().Custom {
		if id == "c"+strconv.Itoa(i) && strings.HasPrefix(x.URL, "https://") {
			return x.Name, x.URL
		}
	}
	return "", ""
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
	var mail []string
	for _, x := range c.Custom {
		if u, err := url.Parse(x.URL); err == nil && x.Mail && u.Hostname() != "" {
			mail = append(mail, strings.ToLower(u.Hostname()))
		}
	}
	b, _ := json.Marshal(map[string]any{"k": socialKey, "reels": c.Reels, "mail": mail})
	return "window.__roninSocialCfg=" + string(b) + ";window.__roninLinkLists=" + string(safelinks.ListsJSON) +
		";window.__roninScamRules=" + string(safelinks.ScamRulesJSON) + ";"
}

// socialStop: why Safe Social never opens this address ("" = it may open). Checked natively
// for every navigation of a Safe Social tab, so no page trick (redirect, script) gets past it.
func socialStop(uri string) string {
	if strings.HasPrefix(uri, "https://ronin.") || strings.HasPrefix(uri, "about:") {
		return ""
	}
	if r := safelinks.Check(uri); r != "" {
		return r
	}
	if strings.HasPrefix(uri, "http") && kids.LooksAdult(uri) {
		return "adult"
	}
	if strings.HasPrefix(uri, "http") && safemoney.Classify(uri, store.Lists()).Lookalike {
		return "fake"
	}
	return ""
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
	if !t.isSocial {
		return
	}
	// from Ronin's own launcher page: everything; from a website: only "my apps" and "close"
	launcher := q.Get("k") == toolsKey && strings.HasPrefix(t.wv.Source(), toolsPrefix+"social.html")
	if !launcher && !(q.Get("k") == socialKey && (q.Get("do") == "home" || q.Get("do") == "close")) {
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
	ids := append([]string{}, c.Apps...)
	for i := range c.Custom {
		ids = append(ids, "c"+strconv.Itoa(i))
	}
	for _, id := range ids {
		if name, _ := appURL(id); name != "" {
			b.WriteString(`<a href="https://ronin.newtab/social?app=` + url.QueryEscape(id) + `&k=` + newTabKey + `">` + html.EscapeString(name) + `<small>Safe Social</small></a>`)
		}
	}
	b.WriteString(`</div>`)
	return b.String()
}
