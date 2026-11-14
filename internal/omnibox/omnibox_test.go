package omnibox

import (
	"strings"
	"testing"
)

func TestResolve(t *testing.T) {
	cases := map[string]string{
		"sbi.bank.in":              "https://sbi.bank.in",
		"  https://web.dhan.co/  ": "https://web.dhan.co/",
		"localhost:8791":           "http://localhost:8791",
		"127.0.0.1:8791/desk":      "http://127.0.0.1:8791/desk",
		"youtube.com/watch?v=1":    "https://youtube.com/watch?v=1",
		"about:blank":              "about:blank",
		"":                         "",
		"nifty 50 chart":           SearchURL + "nifty+50+chart",
		"hello":                    SearchURL + "hello",
		"javascript:alert(1)":      SearchURL + "javascript%3Aalert%281%29",
		"file:///C:/Windows":       SearchURL + "file%3A%2F%2F%2FC%3A%2FWindows",
		"1.5":                      SearchURL + "1.5",
		"http://":                  SearchURL + "http%3A%2F%2F",
		"https://exa mple.com":     SearchURL + "https%3A%2F%2Fexa+mple.com",
		"192.168.1.1":              "https://192.168.1.1",
	}
	for in, want := range cases {
		if got := Resolve(in); got != want {
			t.Errorf("Resolve(%q) = %q want %q", in, got, want)
		}
	}
	if got := Resolve("example.c"); !strings.HasPrefix(got, SearchURL) {
		t.Errorf("one-letter ending should search: %q", got)
	}
}
