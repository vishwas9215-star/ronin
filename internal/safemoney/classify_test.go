package safemoney

import "testing"

func TestClassifyKinds(t *testing.T) {
	cases := []struct {
		url  string
		want Kind
	}{
		{"https://sbi.bank.in/login", Bank},
		{"https://retail.onlinesbi.sbi/retail/login.htm", Bank},
		{"https://anything.sbi/", Bank},
		{"https://netbanking.hdfcbank.com/netbanking/", Bank},
		{"https://www.icicibank.com", Bank},
		{"https://HDFC.Bank.In.", Bank},
		{"https://www.bankofbaroda.in/", Bank},
		{"https://web.dhan.co/", Trading},
		{"https://in.tradingview.com/chart/", Trading},
		{"https://kite.zerodha.com/chart/ext/tvc/NSE/NIFTY", Trading},
		{"https://pro.upstox.com/", Trading},
		{"https://trade.fyers.in/", Trading},
		{"https://www.angelone.in/trade", Trading},
		{"https://groww.in/charts/indices/nifty", Trading},
		{"http://127.0.0.1:8791/", Trading},
		{"http://localhost:8791/desk", Trading},
		{"http://127.0.0.1:9000/", Local},
		{"https://api.razorpay.com/v1/checkout", Payment},
		{"https://secure.payu.in/_payment", Payment},
		{"https://accounts.google.com/signin", Auth},
		{"about:blank", Local},
		{"data:text/html,hi", Local},
		{"https://www.google.com/search?q=sbi", General},
		{"https://bank.in.example.com/", General}, // .bank.in must be the real ending
		{"https://sbi.bank.in.evil.com/", General},
		{"https://evilbank.in/", General},
		{"ftp://x.com/", General},
		{"::not a url::", General},
	}
	for _, c := range cases {
		if got := Classify(c.url, Lists{}).Kind; got != c.want {
			t.Errorf("Classify(%q) = %v, want %v", c.url, got, c.want)
		}
	}
}

func TestUserBanksAndTrusted(t *testing.T) {
	l := Lists{UserBanks: []string{"mycoopbank.co.in"}, Trusted: []string{"sbi-pension-help.org"}}
	if k := Classify("https://ib.mycoopbank.co.in/", l).Kind; k != Bank {
		t.Fatalf("user bank not detected: %v", k)
	}
	if v := Classify("https://sbi-pension-help.org/", l); v.Lookalike {
		t.Fatalf("trusted site still flagged")
	}
}

func TestLookalikes(t *testing.T) {
	bad := []string{
		"https://onlinesbi-kyc.xyz/",
		"https://sbi-kyc-update.com/",
		"https://sbionline.co/",
		"https://yonosbi-rewards.in/",
		"https://hdfcbank-login.net/",
		"https://hdfcbamk.com/",        // one wrong letter
		"https://1cici-netbanking.in/", // digit for letter
		"https://lcicibank.co.in/",
		"https://axisbank.secure-login.top/",
		"https://pnb-kyc.online/",
		"https://kotak-reward-points.com/",
		"https://xn--onlinesb-0hh.com/", // "onlinesbі" with a Cyrillic і
		"https://xn--hdfcbnk-6fg.in/",   // "hdfcbаnk" with a Cyrillic а
		"https://canarabank-help.in/",
		"https://dhan-login.co/",
	}
	for _, u := range bad {
		if v := Classify(u, Lists{}); !v.Lookalike {
			t.Errorf("%s should be flagged as fake bank site (kind %v)", u, v.Kind)
		}
	}
	good := []string{
		"https://www.google.com/",
		"https://www.sbicard.com/",
		"https://www.hdfclife.com/",
		"https://www.icicidirect.com/",
		"https://www.hdfcsky.com/",
		"https://www.axis-design.com/", // "axis" alone without banking words
		"https://kotakcollege.edu.in/", // short brand without banking word
		"https://www.barodatimes.com/", // city name, no banking word
		"https://sbi.example.com/",     // short brand alone, no banking word
		"https://www.youtube.com/watch?v=hdfc",
		"https://en.wikipedia.org/wiki/State_Bank_of_India",
		"https://www.dhanbank.com/",
		"https://www.onlinemarket.in/",
		"https://www.icicisecurities.com/", "https://www.hdfccredila.com/", "https://www.indusindnipponlife.com/",
		"https://www.yesbankltd.in/", "https://www.axisbankfoundation.org/",
	}
	for _, u := range good {
		if v := Classify(u, Lists{}); v.Lookalike {
			t.Errorf("%s wrongly flagged as fake (brand %s)", u, v.Brand)
		}
	}
}

func TestPunyDecode(t *testing.T) {
	cases := map[string]string{
		"mnchen-3ya": "münchen",
		"bcher-kva":  "bücher",
		"wgv71a119e": "日本語",
		"e1afmkfd":   "пример",
	}
	for in, want := range cases {
		got, ok := punyDecode(in)
		if !ok || got != want {
			t.Errorf("punyDecode(%q) = %q,%v want %q", in, got, ok, want)
		}
	}
	if _, ok := punyDecode("!!"); ok {
		t.Errorf("bad input accepted")
	}
}

func TestOneEditAway(t *testing.T) {
	yes := [][2]string{{"hdfcbamk", "hdfcbank"}, {"icicii", "icici"}, {"iici", "icici"}, {"axisbnk", "axisbank"}}
	no := [][2]string{{"hdfcbank", "hdfcbank"}, {"abc", "xyz"}, {"hdfc", "hdfcbank"}}
	for _, p := range yes {
		if !oneEditAway(p[0], p[1]) {
			t.Errorf("%v should be one edit", p)
		}
	}
	for _, p := range no {
		if oneEditAway(p[0], p[1]) {
			t.Errorf("%v should not be one edit", p)
		}
	}
}

func TestNormalizeUserDomain(t *testing.T) {
	cases := map[string]string{
		"https://www.MyBank.com/login?x=1": "mybank.com",
		"ib.coop.co.in":                    "ib.coop.co.in",
		"localhost":                        "",
		"nodot":                            "",
		"  ":                               "",
	}
	for in, want := range cases {
		if got := NormalizeUserDomain(in); got != want {
			t.Errorf("NormalizeUserDomain(%q)=%q want %q", in, got, want)
		}
	}
}

func TestIsLinkRedirector(t *testing.T) {
	yes := []string{"https://www.google.com/url?q=https://sbi.bank.in", "https://l.instagram.com/?u=x", "https://t.co/abc", "https://www.youtube.com/redirect?q=x"}
	no := []string{"https://www.google.com/search?q=x", "https://evil.com/url", "https://sbi.bank.in/", "javascript:alert(1)"}
	for _, u := range yes {
		if !IsLinkRedirector(u) {
			t.Errorf("%s should be a redirector", u)
		}
	}
	for _, u := range no {
		if IsLinkRedirector(u) {
			t.Errorf("%s should not be a redirector", u)
		}
	}
}
