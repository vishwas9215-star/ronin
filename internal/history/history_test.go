package history

import (
	"fmt"
	"strings"
	"testing"
	"time"
)

func TestHistory(t *testing.T) {
	dir := t.TempDir()
	now := time.Date(2026, 11, 14, 9, 0, 0, 0, time.UTC)
	s := Open(dir)
	s.now = func() time.Time { return now }
	s.Add("YouTube", "https://www.youtube.com/")
	s.Add("", "https://drishtiias.com/hindi")
	s.SetTitle("https://drishtiias.com/hindi", "दृष्टि IAS करेंट अफेयर्स")
	s.Add("ignored", "ronin://newtab")
	s.Add("ignored", "javascript:alert(1)")
	s.Add("YouTube again", "https://www.youtube.com/")
	got := s.Search("", 10)
	if len(got) != 2 || got[0].Title != "YouTube again" || got[1].Title != "दृष्टि IAS करेंट अफेयर्स" {
		t.Fatalf("list %+v", got)
	}
	if r := s.Search("करेंट drishti", 10); len(r) != 1 {
		t.Errorf("hindi search %+v", r)
	}
	if err := s.Save(); err != nil {
		t.Fatal(err)
	}
	s2 := Open(dir)
	s2.now = s.now
	if s2.Len() != 2 {
		t.Errorf("reload len %d", s2.Len())
	}
	s2.Remove("https://www.youtube.com/")
	if s2.Len() != 1 {
		t.Error("remove")
	}
	// old pages go away after 90 days
	now = now.Add(91 * 24 * time.Hour)
	s2.Add("new", "https://example.com/")
	if s2.Len() != 1 || s2.Search("", 5)[0].URL != "https://example.com/" {
		t.Errorf("not pruned %+v", s2.Search("", 5))
	}
	// clear the last hour only
	s2.Add("a", "https://a.com/")
	if err := s2.Clear(now.Add(-time.Hour)); err != nil || s2.Len() != 0 {
		t.Errorf("clear last hour: %d", s2.Len())
	}
	// size limit
	for i := 0; i < MaxEntries+50; i++ {
		s2.Add("p", fmt.Sprintf("https://x.com/%d", i))
	}
	if s2.Len() != MaxEntries {
		t.Errorf("cap %d", s2.Len())
	}
	if err := s2.Clear(time.Time{}); err != nil || Open(dir).Len() != 0 {
		t.Error("clear all not saved")
	}
}

func TestSkipAndRemoveWhere(t *testing.T) {
	s := Open(t.TempDir())
	s.Add("Wiki", "https://www.wikipedia.org/")
	s.Add("Old", "https://www.pornhub.com/x")
	Skip = func(u string) bool { return strings.Contains(u, "porn") }
	defer func() { Skip = nil }()
	s.Add("New", "https://www.pornhub.com/y")
	if n := s.RemoveWhere(Skip); n != 1 {
		t.Fatalf("removed %d", n)
	}
	for _, e := range s.Search("", 10) {
		if strings.Contains(e.URL, "porn") {
			t.Fatal("still in history: " + e.URL)
		}
	}
}
