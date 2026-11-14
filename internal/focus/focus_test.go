package focus

import (
	"testing"
	"time"
)

func at(h, m int) time.Time { return time.Date(2026, 11, 14, h, m, 0, 0, time.Local) }

func TestActive(t *testing.T) {
	p := Plan{On: true, From: "18:00", To: "21:00"}
	for _, c := range []struct {
		h, m int
		want bool
	}{{17, 59, false}, {18, 0, true}, {20, 59, true}, {21, 0, false}, {3, 0, false}} {
		if got, _ := Active(p, at(c.h, c.m)); got != c.want {
			t.Errorf("%02d:%02d = %v", c.h, c.m, got)
		}
	}
	if _, end := Active(p, at(19, 0)); !end.Equal(at(21, 0)) {
		t.Errorf("end %v", end)
	}
	night := Plan{On: true, From: "22:00", To: "01:30"}
	for _, c := range []struct {
		h, m int
		want bool
	}{{21, 59, false}, {23, 0, true}, {0, 30, true}, {1, 30, false}} {
		if got, _ := Active(night, at(c.h, c.m)); got != c.want {
			t.Errorf("night %02d:%02d = %v", c.h, c.m, got)
		}
	}
	if _, end := Active(night, at(23, 0)); !end.Equal(time.Date(2026, 11, 15, 1, 30, 0, 0, time.Local)) {
		t.Errorf("night end %v", end)
	}
	off := Plan{On: false, From: "18:00", To: "21:00"}
	if got, _ := Active(off, at(19, 0)); got {
		t.Error("switched off but active")
	}
	off.Until = at(10, 0).Add(time.Hour).Unix()
	if got, end := Active(off, at(10, 30)); !got || !end.Equal(at(11, 0)) {
		t.Error("1 hour now not active")
	}
	if got, _ := Active(off, at(11, 1)); got {
		t.Error("1 hour now did not end")
	}
	if got, _ := Active(Plan{On: true, From: "bad", To: "21:00"}, at(19, 0)); got {
		t.Error("bad time active")
	}
}

func TestPaused(t *testing.T) {
	p := Plan{}
	yes := []string{"https://www.instagram.com/reel/x", "https://m.facebook.com/", "https://www.youtube.com/shorts/abc",
		"https://m.youtube.com/shorts/abc", "https://x.com/home"}
	no := []string{"https://www.youtube.com/watch?v=1", "https://www.youtube.com/", "https://drishtiias.com/",
		"https://notinstagram.com/", "https://box.com/", "ronin://newtab"}
	for _, u := range yes {
		if !Paused(p, u) {
			t.Error("not paused:", u)
		}
	}
	for _, u := range no {
		if Paused(p, u) {
			t.Error("paused by mistake:", u)
		}
	}
	own := Plan{Sites: []string{"https://www.cricbuzz.com"}}
	if !Paused(own, "https://www.cricbuzz.com/live") || Paused(own, "https://www.instagram.com/") {
		t.Error("own list not used")
	}
}
