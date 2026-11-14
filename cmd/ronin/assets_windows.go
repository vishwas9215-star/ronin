//go:build windows

package main

import (
	"crypto/rand"
	_ "embed"
	"encoding/hex"
	"encoding/json"
	"html"
	"strings"

	"roninbrowser/internal/quicklinks"

	"roninbrowser/internal/containers"
)

//go:embed ui/toolbar.html
var toolbarTemplate string

//go:embed ui/newtab.html
var newTabTemplate string

//go:embed ui/quicklinks.js
var quickLinksJS string

// newTabKey proves that a quick-link change came from Ronin's own new tab page.
var newTabKey = func() string {
	b := make([]byte, 16)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}()

var sectionTitles = map[string]string{"Quick links": "Quick links", "School": "स्कूल / School", "Trading": "Trading", "Video calls": "Video calls"}

// quickLinksBlock is the small, editable quick links for the given sections.
func quickLinksBlock(sections ...string) string {
	type jl struct {
		N string `json:"n"`
		U string `json:"u"`
	}
	type js struct {
		ID    string `json:"id"`
		Title string `json:"title"`
		Links []jl   `json:"links"`
	}
	all := quicklinks.List(nil, nil)
	if store != nil {
		all = store.QuickLinks()
	}
	data := struct {
		K        string `json:"k"`
		Sections []js   `json:"sections"`
	}{K: newTabKey}
	for _, sec := range sections {
		x := js{ID: sec, Title: sectionTitles[sec]}
		for _, l := range quicklinks.In(all, sec) {
			x.Links = append(x.Links, jl{l.Name, l.URL})
		}
		data.Sections = append(data.Sections, x)
	}
	b, _ := json.Marshal(data) // escapes <, > and & so it is safe inside <script>
	return `<div id="ql"></div><script nonce="` + newTabKey + `">window.RONIN_QL=` + string(b) + `;</script><script nonce="` + newTabKey + `">` + quickLinksJS + `</script>`
}

func toolbarHTML(c containers.Container) string {
	return strings.NewReplacer("{{CONTAINER}}", string(c), "{{PANEL}}", "0", "{{THEME}}", themeName()).Replace(toolbarTemplate)
}

// panelHTML is the same trusted page, drawn as the side panel.
func panelHTML(c containers.Container) string {
	return strings.NewReplacer("{{CONTAINER}}", string(c), "{{PANEL}}", "1", "{{THEME}}", themeName()).Replace(toolbarTemplate)
}

type link struct{ name, url string }

var bankLinks = []link{
	{"State Bank of India", "https://www.onlinesbi.sbi/"},
	{"HDFC Bank", "https://www.hdfcbank.com/"},
	{"ICICI Bank", "https://www.icicibank.com/"},
	{"Axis Bank", "https://www.axisbank.com/"},
	{"Kotak Mahindra Bank", "https://www.kotak.com/"},
	{"Punjab National Bank", "https://www.pnbindia.in/"},
	{"Bank of Baroda", "https://www.bankofbaroda.in/"},
	{"Canara Bank", "https://canarabank.com/"},
	{"Union Bank of India", "https://www.unionbankofindia.co.in/"},
	{"India Post Payments Bank", "https://www.ippbonline.com/"},
}

var tradingLinks = []link{
	{"Ronin Terminal", "http://127.0.0.1:8791/"},
	{"Dhan", "https://web.dhan.co/"},
	{"Dhan TradingView (live data)", "https://tv.dhan.co/"},
	{"TradingView", "https://in.tradingview.com/chart/"},
}

var callLinks = []link{
	{"Google Meet", "https://meet.google.com/"},
	{"WhatsApp Web", "https://web.whatsapp.com/"},
	{"Zoom", "https://app.zoom.us/wc"},
	{"Microsoft Teams", "https://teams.live.com/"},
	{"Jitsi Meet", "https://meet.jit.si/"},
}

var personalLinks = []link{
	{"YouTube", "https://www.youtube.com/"},
	{"Gmail", "https://mail.google.com/"},
	{"DIKSHA", "https://diksha.gov.in/"},
	{"Wikipedia", "https://www.wikipedia.org/"},
}

func linksSection(title string, links []link) string {
	var b strings.Builder
	b.WriteString("<h2>" + html.EscapeString(title) + "</h2><div class=\"grid\">")
	for _, l := range links {
		b.WriteString(`<a href="` + html.EscapeString(l.url) + `">` + html.EscapeString(l.name) +
			"<small>" + html.EscapeString(hostOf(l.url)) + "</small></a>")
	}
	b.WriteString("</div>")
	return b.String()
}

func newTabHTML(c containers.Container) string {
	heading, sub, note := "Ronin Browser", "Safe, private and fast.", ""
	var sections []string
	switch c {
	case containers.Banking:
		heading = "Ronin Safe Money"
		sub = "Protected banking window: no screenshots, no add-ons, nothing kept after you close it."
		var mine []link
		if store != nil {
			for _, d := range store.Get().UserBanks {
				mine = append(mine, link{d, "https://" + d + "/"})
			}
		}
		if len(mine) > 0 {
			sections = append(sections, linksSection("My Banks", mine))
		}
		sections = append(sections, linksSection("Banks", bankLinks))
		note = `<div class="note"><b>Safety rules:</b> Never share an OTP, PIN or password with anyone on a call or chat. Your bank will never ask you to install AnyDesk or TeamViewer. Real Indian bank addresses end in <b>.bank.in</b>.</div>`
	case containers.Trading:
		heading = "Ronin Trading"
		sub = "Ronin Terminal, Dhan and TradingView in one window."
		sections = append(sections, quickLinksBlock("Trading"))
		note = `<div class="note"><b>Remember:</b> A SEBI-registered adviser never takes your money to trade for you. Profit that needs a "fee" before withdrawal is always a scam.</div>`
	case containers.Kids:
		heading = "Ronin Kids"
		sub = "Safe learning: SafeSearch is always on, and adult or gambling sites are blocked."
		sections = append(sections, linksSection("Learn", []link{
			{"DIKSHA", "https://diksha.gov.in/"}, {"NCERT Books", "https://ncert.nic.in/textbook.php"},
			{"ePathshala", "https://epathshala.nic.in/"}, {"YouTube Kids", "https://www.youtubekids.com/"},
			{"Khan Academy", "https://www.khanacademy.org/"}, {"Wikipedia", "https://www.wikipedia.org/"},
			{"StoryWeaver", "https://storyweaver.org.in/"}, {"Scratch", "https://scratch.mit.edu/"},
		}))
	case containers.Calls:
		heading = "Ronin Video Call"
		sub = "Video calls at full quality: no VPN slow-down, camera and microphone ready, calls keep running in the background."
		sections = append(sections, quickLinksBlock("Video calls"))
		note = `<div class="note"><b>Tip:</b> Sign in once here (Google, WhatsApp). This window keeps its own sign-in. Never share an OTP or screen with a stranger on a call.</div>`
	case containers.Private:
		heading = "Private window"
		sub = "Nothing from this window is kept after you close it."
	default:
		sections = append(sections, quickLinksBlock(quicklinks.Sections...))
		note = `<div class="note">Bank websites open automatically in <b>Ronin Safe Money</b>, the protected banking window.</div>`
	}
	r := strings.NewReplacer(
		"{{CONTAINER}}", string(c),
		"{{NONCE}}", newTabKey,
		"{{THEME}}", themeName(),
		"{{HEADING}}", html.EscapeString(heading),
		"{{SUBTITLE}}", html.EscapeString(sub),
		"{{SECTIONS}}", strings.Join(sections, "\n"),
		"{{NOTE}}", note,
	)
	return r.Replace(newTabTemplate)
}
