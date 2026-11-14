package quicklinks

import "testing"

func TestCleanAndList(t *testing.T) {
	l, ok := Clean(Link{"Trading", " Zerodha ", "kite.zerodha.com"})
	if !ok || l.URL != "https://kite.zerodha.com/" || l.Name != "Zerodha" {
		t.Fatalf("%+v %v", l, ok)
	}
	if _, ok := Clean(Link{"School", "x", "javascript:alert(1)"}); ok {
		t.Fatal("javascript link accepted")
	}
	if _, ok := Clean(Link{"School", "x", "notasite"}); ok {
		t.Fatal("word accepted as a site")
	}
	if l, _ := Clean(Link{"Nope", "", "https://www.example.com"}); l.Section != "Quick links" || l.Name != "example.com" {
		t.Fatalf("%+v", l)
	}
	all := List([]Link{{"School", "Mine", "https://mine.example/"}}, []string{"https://trade.fyers.in/"})
	for _, l := range all {
		if l.Name == "Fyers" {
			t.Fatal("hidden link still shown")
		}
	}
	s := In(all, "School")
	if len(s) != 4 || s[3].Name != "Mine" || s[2].Name != "UDISE+ All Modules" {
		t.Fatalf("%+v", s)
	}
	if len(In(all, "Trading")) != 4 {
		t.Fatal("trading links")
	}
}
