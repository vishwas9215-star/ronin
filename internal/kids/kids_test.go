package kids

import (
	"strings"
	"testing"
)

func TestJunior(t *testing.T) {
	yes := []string{"https://diksha.gov.in/explore", "https://en.wikipedia.org/wiki/Moon", "https://www.youtubekids.com/", "https://www.google.com/search?q=moon"}
	no := []string{"https://www.youtube.com/", "https://mail.google.com/", "https://www.instagram.com/", "https://maps.google.com/", "javascript:x"}
	for _, u := range yes {
		if !JuniorAllowed(u, nil) {
			t.Errorf("%s should be allowed", u)
		}
	}
	for _, u := range no {
		if JuniorAllowed(u, nil) {
			t.Errorf("%s should be blocked", u)
		}
	}
	if !JuniorAllowed("https://myschool.edu.in/", []string{"myschool.edu.in"}) {
		t.Error("parent-added site blocked")
	}
}

func TestLooksAdult(t *testing.T) {
	bad := []string{"https://www.pornhub.com/", "https://freeporn.example/", "https://satta-king.in/", "https://www.1xbet.com/", "https://rummycircle.com/", "https://xxx.example.com/"}
	good := []string{"https://www.essex.ac.uk/", "https://www.sussex.ac.uk/", "https://www.middlesex.ac.uk/", "https://www.google.com/", "https://diksha.gov.in/", "https://www.betterhelp.com/", "https://alphabet.com/", "https://www.slotsmachine-history.org/"}
	for _, u := range bad {
		if !LooksAdult(u) {
			t.Errorf("%s should be blocked", u)
		}
	}
	for _, u := range good {
		if LooksAdult(u) {
			t.Errorf("%s wrongly blocked", u)
		}
	}
}

func TestSafeSearch(t *testing.T) {
	g := SafeSearchURL("https://www.google.com/search?q=cats")
	if !strings.Contains(g, "safe=active") {
		t.Fatal(g)
	}
	if SafeSearchURL(g) != "" {
		t.Fatal("already safe should not change")
	}
	if !strings.Contains(SafeSearchURL("https://www.bing.com/search?q=x"), "adlt=strict") {
		t.Fatal("bing")
	}
	if SafeSearchURL("https://www.google.com/maps") != "" || SafeSearchURL("https://example.com/search?q=1") != "" {
		t.Fatal("changed a non-search page")
	}
	if !IsYouTube("https://www.youtube.com/watch?v=1") || !IsYouTube("https://youtubei.googleapis.com/x") || IsYouTube("https://notyoutube.com") {
		t.Fatal("IsYouTube")
	}
	if !ValidPIN("1234") || ValidPIN("12a4") || ValidPIN("12") {
		t.Fatal("ValidPIN")
	}
	if HashPIN("1234", "a") == HashPIN("1234", "b") {
		t.Fatal("salt ignored")
	}
}
