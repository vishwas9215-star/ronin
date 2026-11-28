package agentcore

import (
	"strings"
	"testing"
)

// The same cases as the Android AgentRulesTest: every one was a hole a reviewer found.

func TestMoneySitesAreReadOnlyAndOnlyListedPagesOpen(t *testing.T) {
	if !Money("web.dhan.co", nil) || !Money("kite.zerodha.com", nil) || Money("epds.example.gov.in", nil) {
		t.Fatal("FINANCE")
	}
	if !Money("mybank.in", func(h string) bool { return h == "mybank.in" }) {
		t.Fatal("Ronin's bank list")
	}
	pages := map[string]map[string]bool{"web.dhan.co": {"/positions": true}}
	for u, ok := range map[string]bool{"https://web.dhan.co/": true, "https://web.dhan.co/positions": true, "https://web.dhan.co/order": false, "https://web.dhan.co/positions?confirm=1": false} {
		if (OpenRefusal(u, nil, pages, "") == "") != ok {
			t.Fatal(u)
		}
	}
}

func TestAddressesThatAreNeverOpened(t *testing.T) {
	for _, u := range []string{"javascript:alert(1)", "file:///sdcard/x", "intent://x#Intent;end", "http://192.168.1.1/", "http://10.0.0.5/", "http://127.0.0.1:8080/", "http://localhost/", "http://172.20.1.1/", "http://100.64.0.1/", "http://169.254.1.1/", "http://[::1]/", "http://2130706433/", "http://[::ffff:127.0.0.1]/"} {
		if OpenRefusal(u, nil, nil, "") == "" {
			t.Fatal(u)
		}
	}
	if OpenRefusal("https://epds.example.gov.in/form?id=2", nil, nil, "") != "" || IsPrivateHost("172.32.0.1") {
		t.Fatal("normal")
	}
}

func TestDataDoesNotLeaveAfterSensitiveReads(t *testing.T) {
	a40 := strings.Repeat("A", 40)
	if OpenRefusal("https://paste.example.net/?d="+a40, nil, nil, "bank.example.in") == "" {
		t.Fatal("leak")
	}
	if OpenRefusal("https://www.google.com/", nil, nil, "bank.example.in") != "" || OpenRefusal("https://x.bank.example.in/?d="+a40, nil, nil, "bank.example.in") != "" {
		t.Fatal("fine")
	}
	if OpenRefusal("https://a.example/?"+strings.Repeat("x", 400), nil, nil, "") == "" {
		t.Fatal("too much data anywhere")
	}
}

func TestNetworkGuard(t *testing.T) {
	g := func(m, u string, claude bool) string { return Guard(m, u, true, claude, nil, "", "renew.example.com") }
	block := []string{g("POST", "https://renew.example.com/api/v2/placeOrder", false), g("POST", "https://x.example/checkout", false), g("GET", "https://renew.example.com/order?confirm=1", true),
		g("POST", "https://web.dhan.co/api/anything", true), g("POST", "https://web.dhan.co/api/anything", false), g("GET", "http://192.168.1.1/x", false),
		Guard("GET", "https://paste.example.net/?d="+strings.Repeat("A", 40), true, true, nil, "bank.example.in", "bank.example.in"),
		Guard("POST", "https://web.dhan.co/api/v3/exec?m=1", true, false, nil, "", "web.dhan.co"), Guard("PUT", "https://api.zerodha.com/x", false, false, nil, "", "kite.zerodha.com"),
		Guard("GET", "https://shop.example.com/execute?pay=1", true, false, nil, "", "shop.example.com"), Guard("GET", "http:///x", true, false, nil, "", "")}
	for i, b := range block {
		if b == "" {
			t.Fatalf("should block #%d", i)
		}
	}
	allow := []string{g("POST", "https://renew.example.com/api/notes", true), g("GET", "https://renew.example.com/orders", true), Guard("GET", "https://web.dhan.co/holdings", true, false, nil, "", "web.dhan.co"),
		Guard("GET", "https://www.google.com/search?q=how+to+pay+upi+confirm", true, false, nil, "", "www.google.com"), Guard("GET", "https://help.example.com/payments/faq", true, false, nil, "", "help.example.com"),
		Guard("GET", "data:text/plain,hi", false, false, nil, "", "")}
	for i, a := range allow {
		if a != "" {
			t.Fatalf("should allow #%d: %s", i, a)
		}
	}
}

func TestSecretsOtpsAndPincodes(t *testing.T) {
	for s, want := range map[string]bool{"482913": true, "48 29-13": true, "9876543210": false, "UP12345": false} {
		if LooksLikeOtp(s) != want {
			t.Fatal(s)
		}
	}
	for s, want := range map[string]bool{"Enter OTP": true, "ओटीपी डालें": true, "Pin code": false, "PIN Code of school": false, "पिनकोड": false, "पिन कोड": false, "UPI PIN": true, "Enter MPIN": true, "पिन डालें": true, "Employee name": false} {
		if SecretLabel(s) != want {
			t.Fatal(s)
		}
	}
	if NumberNeedsOwner("Pin code", "228001", false) || NumberNeedsOwner("Year of joining", "2014", false) || !NumberNeedsOwner("Remarks", "482913", true) || !NumberNeedsOwner("UPI PIN", "4829", false) {
		t.Fatal("numbers")
	}
}

func TestOddSpellingsOfHomeAddresses(t *testing.T) {
	for _, h := range []string{"2130706433", "0x7f.1", "0177.0.0.1", "127.1", "0x7f000001", "::ffff:127.0.0.1", "[::ffff:c0a8:0101]", "::1", "fd00::5", "fe80::1", "0.0.0.0", "192.168.1.1.", "router.local", "nas.home.arpa", "100.100.1.1", "224.0.0.1", "999.1.1.1", "", "::", "64:ff9b::7f00:1"} {
		if !IsPrivateHost(h) {
			t.Fatal(h)
		}
	}
	for _, h := range []string{"example-1.com", "8.8.8.8", "epds.example.gov.in", "2606:4700::1111", "fc.example.com", "dhan.co"} {
		if IsPrivateHost(h) {
			t.Fatal(h)
		}
	}
}

func TestLinksAndRedaction(t *testing.T) {
	if !ScriptLink("javascript:fetch('/x')") || !ActionLink("/unsubscribe?u=77", "https://news.example.com/") || ActionLink("/search?q=update+drivers", "https://news.example.com/") || ActionLink("/blog/how-to-edit-video", "https://news.example.com/") {
		t.Fatal("links")
	}
	if r := Redact("A 1234 5678 9012 P ABCDE1234F N 1234567890"); r != "A [aadhaar-like hidden] P [PAN hidden] N [number hidden]" {
		t.Fatal(r)
	}
	if Registrable("a.b.co.in") != "b.co.in" || Registrable("x.y.example.com") != "example.com" {
		t.Fatal("registrable")
	}
}

func TestPostbackLinksArePressedButNothingMore(t *testing.T) {
	ok := []string{`javascript:__doPostBack('ctl00$ContentPlaceHolder1$lnkIFSC','')`, `javascript:WebForm_DoPostBackWithOptions(new WebForm_PostBackOptions("ctl00$btnNext", "", true, "", "", false, true))`}
	bad := []string{`javascript:__doPostBack('a','');fetch('//evil')`, `javascript:__doPostBack('a',(function(){return 1})())`, `javascript:fetch('/x')`}
	for _, h := range ok {
		if ScriptLink(h) {
			t.Fatal("postback refused: " + h)
		}
	}
	for _, h := range bad {
		if !ScriptLink(h) {
			t.Fatal("script allowed: " + h)
		}
	}
}
