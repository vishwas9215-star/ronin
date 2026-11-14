package adblock

import (
	"strings"
	"testing"
)

func TestBuiltin(t *testing.T) {
	e := New()
	block := []string{
		"https://securepubads.g.doubleclick.net/tag/js/gpt.js",
		"https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js",
		"https://www.youtube.com/pagead/viewthroughconversion/123",
		"https://www.youtube.com/api/stats/ads?ver=2",
		"https://www.google-analytics.com/analytics.js",
		"wss://ads.example.taboola.com/socket",
	}
	allow := []string{
		"https://www.youtube.com/watch?v=abc",
		"https://rr3---sn-abc.googlevideo.com/videoplayback?x=1",
		"https://in.tradingview.com/chart/",
		"https://sbi.bank.in/",
		"https://www.google.com/search?q=doubleclick",
		"data:text/html,ads",
		"not a url",
	}
	for _, u := range block {
		if !e.Blocked(u) {
			t.Errorf("should block %s", u)
		}
	}
	for _, u := range allow {
		if e.Blocked(u) {
			t.Errorf("should allow %s", u)
		}
	}
}

func TestParseList(t *testing.T) {
	list := `! comment
[Adblock Plus 2.0]
||ads.badsite.com^
||tracker.example.org^$third-party
||onlypage.example.com^$domain=foo.com
||pathrule.com/ads/
@@||good.ads.badsite.com^
0.0.0.0 malware.example.net
127.0.0.1 localhost
plain-domain.example
example.com##.ad-banner
/regex.*/
||ünicode.com^
`
	b, a := ParseList(strings.NewReader(list))
	for _, d := range []string{"ads.badsite.com", "tracker.example.org", "malware.example.net", "plain-domain.example"} {
		if _, ok := b[d]; !ok {
			t.Errorf("missing block %s", d)
		}
	}
	for _, d := range []string{"onlypage.example.com", "pathrule.com", "localhost", "example.com"} {
		if _, ok := b[d]; ok {
			t.Errorf("should skip %s", d)
		}
	}
	if _, ok := a["good.ads.badsite.com"]; !ok {
		t.Error("missing allow rule")
	}
	e := New()
	n := e.LoadList(strings.NewReader(list))
	if n != 5 {
		t.Errorf("loaded %d rules, want 5", n)
	}
	if !e.Blocked("https://x.ads.badsite.com/a.js") || e.Blocked("https://good.ads.badsite.com/ok.js") {
		t.Error("allow/block precedence wrong")
	}
}

func BenchmarkBlocked(b *testing.B) {
	e := New()
	var sb strings.Builder
	for i := 0; i < 60000; i++ {
		sb.WriteString("||ad")
		sb.WriteString(strings.Repeat("x", i%7))
		sb.WriteString(string(rune('a' + i%26)))
		sb.WriteString(".example")
		sb.WriteString(string(rune('a' + i%13)))
		sb.WriteString(".com^\n")
	}
	e.LoadList(strings.NewReader(sb.String()))
	for i := 0; i < b.N; i++ {
		e.Blocked("https://static.cdn.some-news-site.co.in/assets/app.js?v=1")
	}
}

func TestBlockedFrom(t *testing.T) {
	e := New()
	if !e.BlockedFrom("https://creatives.chaturbate.com/ad.mp4", "www.somevideosite.com") {
		t.Error("advert inside another site not blocked")
	}
	if e.BlockedFrom("https://chaturbate.com/", "chaturbate.com") || e.BlockedFrom("https://static.chaturbate.com/x.js", "chaturbate.com") {
		t.Error("visiting the site itself must work")
	}
	if !e.BlockedFrom("https://s.pemsrv.com/x", "anything.com") {
		t.Error("ad network not blocked")
	}
	if e.BlockedFrom("https://www.youtube.com/watch?v=1", "www.youtube.com") {
		t.Error("normal page blocked")
	}
}
