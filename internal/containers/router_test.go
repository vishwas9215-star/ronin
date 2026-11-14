package containers

import (
	"strings"
	"testing"

	"roninbrowser/internal/safemoney"
)

func TestRoute(t *testing.T) {
	user := func(u string) Nav { return Nav{URL: u, UserInitiated: true} }
	redirect := func(u string) Nav { return Nav{URL: u, Redirected: true} }
	script := func(u string) Nav { return Nav{URL: u} }

	cases := []struct {
		name   string
		cur    Container
		nav    Nav
		action Action
		target Container
		pay    bool
	}{
		{"bank from google moves to Safe Money", Personal, user("https://sbi.bank.in/"), Move, Banking, false},
		{"bank via redirect stays (payment OTP)", Personal, redirect("https://acs.icicibank.com/otp"), Stay, Personal, true},
		{"bank via script stays (payment OTP)", Personal, script("https://acs.hdfcbank.com/otp"), Stay, Personal, true},
		{"gateway stays with protect", Personal, user("https://api.razorpay.com/pay"), Stay, Personal, true},
		{"fake bank warned", Personal, user("https://sbi-kyc-update.com/"), Warn, Personal, false},
		{"fake bank warned even by redirect", Personal, redirect("https://hdfcbank-login.net/"), Warn, Personal, false},
		{"normal site stays", Personal, user("https://www.youtube.com/"), Stay, Personal, false},
		{"dhan moves to trading", Personal, user("https://web.dhan.co/"), Move, Trading, false},
		{"terminal moves to trading", Personal, user("http://127.0.0.1:8791/"), Move, Trading, false},
		{"bank in banking stays", Banking, user("https://netbanking.hdfcbank.com/x"), Stay, Banking, false},
		{"other link from bank goes back", Banking, user("https://www.youtube.com/"), Move, Personal, false},
		{"dhan link from bank goes to trading", Banking, user("https://web.dhan.co/"), Move, Trading, false},
		{"bank redirect to merchant stays protected", Banking, redirect("https://shop.example.com/done"), Stay, Banking, true},
		{"fake site from banking warned", Banking, user("https://onlinesbi-kyc.xyz/"), Warn, Banking, false},
		{"google sign-in stays in trading", Trading, user("https://accounts.google.com/x"), Stay, Trading, false},
		{"bank from trading moves", Trading, user("https://www.icicibank.com/"), Move, Banking, false},
		{"news from trading goes personal", Trading, user("https://news.example.com/"), Move, Personal, false},
		{"meet moves to video call window", Personal, user("https://meet.google.com/abc-defg-hij"), Move, Calls, false},
		{"zoom sub-domain moves to calls", Personal, user("https://us05web.zoom.us/j/123"), Move, Calls, false},
		{"meet in private window stays private", Private, user("https://meet.google.com/x"), Stay, Private, false},
		{"meet by script stays", Personal, script("https://meet.google.com/x"), Stay, Personal, false},
		{"google sign-in stays in calls", Calls, user("https://accounts.google.com/x"), Stay, Calls, false},
		{"call stays in calls", Calls, user("https://web.whatsapp.com/"), Stay, Calls, false},
		{"news from calls goes personal", Calls, user("https://news.example.com/"), Move, Personal, false},
		{"bank from calls goes safe money", Calls, user("https://sbi.bank.in/"), Move, Banking, false},
		{"dhan from calls goes trading", Calls, user("https://web.dhan.co/"), Move, Trading, false},
		{"meet from trading goes calls", Trading, user("https://meet.google.com/x"), Move, Calls, false},
		{"meet from bank goes calls", Banking, user("https://meet.google.com/x"), Move, Calls, false},
		{"kids cannot open bank", Kids, user("https://sbi.bank.in/"), Block, Kids, false},
		{"kids cannot open trading", Kids, user("https://web.dhan.co/"), Block, Kids, false},
		{"kids normal site stays in kids", Kids, user("https://diksha.gov.in/"), Stay, Kids, false},
		{"private bank moves to banking", Private, user("https://sbi.bank.in/"), Move, Banking, false},
		{"bank via google redirect after click moves", Personal, Nav{URL: "https://sbi.bank.in/", UserInitiated: true, Redirected: true, ViaRedirector: true}, Move, Banking, false},
		{"about:blank stays", Banking, user("about:blank"), Stay, Banking, false},
	}
	for _, c := range cases {
		d := Route(c.cur, c.nav, safemoney.Lists{})
		if d.Action != c.action || d.Target != c.target || d.PaymentProtect != c.pay {
			t.Errorf("%s: got action=%v target=%v pay=%v, want %v %v %v",
				c.name, d.Action, d.Target, d.PaymentProtect, c.action, c.target, c.pay)
		}
	}
}

func TestValid(t *testing.T) {
	if !Banking.Valid() || Container("X").Valid() {
		t.Fatal("Valid() wrong")
	}
}

func TestSensitiveSitesOpenPrivate(t *testing.T) {
	Sensitive = func(u string) bool { return strings.Contains(u, "xvideos") }
	defer func() { Sensitive = nil }()
	for _, c := range []Container{Personal, Trading, Calls, Banking} {
		if d := Route(c, Nav{URL: "https://www.xvideos.com/", UserInitiated: true}, safemoney.Lists{}); d.Action != Move || d.Target != Private {
			t.Fatalf("%s: %+v", c, d)
		}
	}
	if d := Route(Private, Nav{URL: "https://www.xvideos.com/", UserInitiated: true}, safemoney.Lists{}); d.Action == Move {
		t.Fatal("private window moves again")
	}
	if d := Route(Personal, Nav{URL: "https://www.wikipedia.org/", UserInitiated: true}, safemoney.Lists{}); d.Action == Move {
		t.Fatal("normal site moved")
	}
}
