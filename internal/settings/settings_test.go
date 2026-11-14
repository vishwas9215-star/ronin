package settings

import (
	"os"
	"path/filepath"
	"reflect"
	"testing"
)

func TestStore(t *testing.T) {
	dir := t.TempDir()
	st := Open(dir)
	if st.Get().SafeMoneyIdleMins != 5 {
		t.Fatal("defaults not applied")
	}
	if !st.AddBank("https://ib.MyCoop.co.in/login") || st.AddBank("not a domain") {
		t.Fatal("AddBank validation wrong")
	}
	st.AddBank("ib.mycoop.co.in") // duplicate
	if !st.Trust("sbi-pension-help.org") {
		t.Fatal("Trust failed")
	}
	again := Open(dir)
	if got := again.Get().UserBanks; !reflect.DeepEqual(got, []string{"ib.mycoop.co.in"}) {
		t.Fatalf("banks not saved: %v", got)
	}
	if l := again.Lists(); len(l.Trusted) != 1 {
		t.Fatalf("trusted not saved: %v", l)
	}
	if !st.SetAdblockPaused("https://www.news.example.com/a", true) || !st.AdblockPaused("news.example.com") || !st.AdblockPaused("m.news.example.com") || st.AdblockPaused("example.com") {
		t.Fatal("adblock pause wrong")
	}
	st.SetAdblockPaused("news.example.com", false)
	if st.AdblockPaused("news.example.com") {
		t.Fatal("resume failed")
	}
	// broken file -> defaults, no crash
	os.WriteFile(filepath.Join(dir, "settings.json"), []byte("{oops"), 0o644)
	if Open(dir).Get().HomePage == "" {
		t.Fatal("broken file not handled")
	}
	// bad values are corrected
	st.Update(func(s *Settings) { s.SafeMoneyIdleMins = 999; s.UpdateChannel = "http://insecure" })
	if g := st.Get(); g.SafeMoneyIdleMins != 5 || g.UpdateChannel != "" {
		t.Fatalf("bad values kept: %+v", g)
	}
}

func TestSitePerms(t *testing.T) {
	dir := t.TempDir()
	st := Open(dir)
	if st.SitePerm("meet.google.com", "camera") != "" {
		t.Fatal("not empty")
	}
	if !st.SetSitePerm("Meet.Google.com", "camera", "allow") || !st.SetSitePerm("x.com", "location", "block") {
		t.Fatal("set failed")
	}
	if st.SetSitePerm("a|b", "camera", "allow") || st.SetSitePerm("", "camera", "allow") {
		t.Fatal("bad host accepted")
	}
	again := Open(dir)
	if again.SitePerm("meet.google.com", "camera") != "allow" || again.SitePerm("x.com", "location") != "block" {
		t.Fatal("not kept")
	}
	again.SetSitePerm("meet.google.com", "camera", "")
	if Open(dir).SitePerm("meet.google.com", "camera") != "" {
		t.Fatal("not forgotten")
	}
}

func TestBookmarks(t *testing.T) {
	dir := t.TempDir()
	st := Open(dir)
	if !st.ToggleBookmark(" Wikipedia ", "https://www.wikipedia.org/") || !st.ToggleBookmark("", "https://a.com/x") {
		t.Fatal("add failed")
	}
	if st.ToggleBookmark("bad", "javascript:alert(1)") || st.ToggleBookmark("bad", "file:///c:/x") {
		t.Fatal("bad address accepted")
	}
	b := Open(dir).Get().Bookmarks
	if len(b) != 2 || b[0].URL != "https://a.com/x" || b[0].Title != "https://a.com/x" || b[1].Title != "Wikipedia" {
		t.Fatalf("%+v", b)
	}
	if st.ToggleBookmark("x", "https://a.com/x") || st.HasBookmark("https://a.com/x") || !st.HasBookmark("https://www.wikipedia.org/") {
		t.Fatal("remove failed")
	}
}

func TestZoomAndFocus(t *testing.T) {
	dir := t.TempDir()
	st := Open(dir)
	if st.Zoom("www.wikipedia.org") != 100 {
		t.Error("default zoom")
	}
	st.SetZoom("www.Wikipedia.org", 150)
	st.SetZoom("bad.com", 9000)
	if Open(dir).Zoom("wikipedia.org") != 150 || Open(dir).Zoom("bad.com") != 100 {
		t.Error("zoom not kept per site")
	}
	st.SetZoom("wikipedia.org", 100)
	if len(Open(dir).Get().ZoomSites) != 0 {
		t.Error("100% not removed")
	}
	p := st.FocusPlan()
	if p.On || p.From != "18:00" || p.To != "21:00" {
		t.Errorf("focus defaults %+v", p)
	}
	_ = st.Update(func(s *Settings) { s.FocusOn, s.FocusFrom, s.FocusTo = true, "25:99", "06:30" })
	if p := Open(dir).FocusPlan(); !p.On || p.From != "18:00" || p.To != "06:30" {
		t.Errorf("focus fix %+v", p)
	}
}

func TestBookmarksFile(t *testing.T) {
	st := Open(t.TempDir())
	st.ToggleBookmark(`Drishti "IAS" & more`, "https://www.drishtiias.com/hindi")
	st.ToggleBookmark("हिंदी विकिपीडिया", "https://hi.wikipedia.org/")
	out := BookmarksHTML(st.Get().Bookmarks)
	back := ParseBookmarksHTML(out)
	if len(back) != 2 || back[1].Title != `Drishti "IAS" & more` || back[0].Title != "हिंदी विकिपीडिया" {
		t.Fatalf("round trip %+v", back)
	}
	other := `<DL><p><DT><A HREF="https://www.youtube.com/" ADD_DATE="1" ICON="data:x">YouTube</A>
<DT><A HREF="javascript:alert(1)">bad</A><DT><a href="https://hi.wikipedia.org/">dup</a>`
	st2 := Open(t.TempDir())
	st2.ToggleBookmark("W", "https://hi.wikipedia.org/")
	if n := st2.AddBookmarks(ParseBookmarksHTML(other)); n != 1 || len(st2.Get().Bookmarks) != 2 {
		t.Errorf("import added %d", n)
	}
}
