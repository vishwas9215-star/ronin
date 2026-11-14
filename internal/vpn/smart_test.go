package vpn

import (
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

var smartCases = map[string]bool{ // host -> uses VPN
	"www.google.com": false, "mail.google.com": false, "www.youtube.com": false, "rr3---sn-x.googlevideo.com": false,
	"onlinesbi.sbi": false, "hdfc.bank.in": false, "tv.dhan.co": false, "in.tradingview.com": false,
	"www.w3schools.com": false, // from the top-sites list
	"localhost":         false, "127.0.0.1": false, "[::1]": false,
	"www.pornhat.com": true, "nvms9.cdn.privatehost.com": true, "xhamster.com": true, "www.pornhub.com": true,
	"1337x.to": true, "some-unknown-blog.xyz": true, "203.0.113.5": true, "s.pemsrv.com": true,
	"betting.example.com": true, "mega-sexy.example": true, "tiny-adult-site.net": true,
}

func testSmart() *Smart {
	sens := func(h string) bool { return h == "tiny-adult-site.net" || h == "pornhub.com" }
	return NewSmart([]string{"w3schools.com", "pornhub.com", "xhamster.com"}, sens)
}

func TestSmartGo(t *testing.T) {
	s := testSmart()
	for h, want := range smartCases {
		if got := s.UsesVPN(h); got != want {
			t.Errorf("%s: UsesVPN=%v want %v", h, got, want)
		}
	}
	if _, ok := s.trusted["pornhub.com"]; ok {
		t.Error("sensitive site became trusted")
	}
}

// The PAC script (run by node, like the engine runs it) must decide exactly as Go does.
func TestSmartPAC(t *testing.T) {
	if _, err := exec.LookPath("node"); err != nil {
		t.Skip("node not installed")
	}
	s := testSmart()
	dir := t.TempDir()
	pac := filepath.Join(dir, "p.js")
	var hosts []string
	for h := range smartCases {
		hosts = append(hosts, `"`+h+`"`)
	}
	js := s.PAC() + "\nvar H=[" + strings.Join(hosts, ",") + "];H.forEach(function(h){console.log(h+' '+FindProxyForURL('https://'+h+'/',h))});\n"
	if err := os.WriteFile(pac, []byte(js), 0o644); err != nil {
		t.Fatal(err)
	}
	out, err := exec.Command("node", pac).CombinedOutput()
	if err != nil {
		t.Fatalf("PAC failed: %v\n%s", err, out)
	}
	for _, line := range strings.Split(strings.TrimSpace(string(out)), "\n") {
		h, res, _ := strings.Cut(line, " ")
		viaVPN := res == "SOCKS5 127.0.0.1:40877"
		if !viaVPN && res != "DIRECT" {
			t.Errorf("%s: bad answer %q", h, res)
		}
		if viaVPN != s.UsesVPN(h) {
			t.Errorf("%s: PAC %q but Go says %v", h, res, s.UsesVPN(h))
		}
	}
}

func TestParseTopList(t *testing.T) {
	got := ParseTopList(strings.NewReader("1,google.com\r\n2,facebook.com\nbad line\n3,\"evil\".com\n4,x/y.com\n5,amazon.com\n"), 3)
	if strings.Join(got, " ") != "google.com facebook.com amazon.com" {
		t.Fatalf("%v", got)
	}
	if len(ParseTopList(strings.NewReader("1,a.com\n2,b.com\n"), 1)) != 1 {
		t.Fatal("max ignored")
	}
}
