package httpsonly

import "testing"

func TestUpgrade(t *testing.T) {
	allowed := func(h string) bool { return h == "old-site.in" }
	cases := map[string]string{
		"http://example.com/a?b=1#c": "https://example.com/a?b=1#c",
		"http://Example.com:80/x":    "https://example.com/x",
		"HTTP://news.example.org":    "https://news.example.org",
		"https://example.com/":       "",
		"http://localhost:3000/":     "",
		"http://127.0.0.1/":          "",
		"http://192.168.1.1/":        "",
		"http://10.0.0.5/":           "",
		"http://router/":             "",
		"http://printer.local/":      "",
		"http://example.com:8080/":   "",
		"http://old-site.in/page":    "",
		"ftp://example.com/":         "",
		"ronin://newtab":             "",
		"http://8.8.8.8/":            "https://8.8.8.8/",
		"http://[2001:db8::1]/x":     "https://[2001:db8::1]/x",
		"http://[::1]/":              "",
	}
	for in, want := range cases {
		got, ok := Upgrade(in, allowed)
		if (want != "") != ok || got != want {
			t.Errorf("%s: got %q %v, want %q", in, got, ok, want)
		}
	}
}

func TestAllowRequest(t *testing.T) {
	if h, ok := AllowRequest("http://Old-Site.in/p" + AllowMark); !ok || h != "old-site.in" {
		t.Fatal(h, ok)
	}
	if _, ok := AllowRequest("https://a.com/" + AllowMark); ok {
		t.Fatal("https accepted")
	}
	if _, ok := AllowRequest("http://a.com/#other"); ok {
		t.Fatal("no mark accepted")
	}
}
