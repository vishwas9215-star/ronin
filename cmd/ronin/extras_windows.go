//go:build windows

package main

// 3.5.0 extras: History, page size (zoom) per site, Study time (Focus).

import (
	"math"
	"strings"
	"time"
	"unsafe"

	"roninbrowser/internal/containers"
	"roninbrowser/internal/focus"
	"roninbrowser/internal/history"
)

// ---- History -------------------------------------------------------------------

var hist *history.Store

func initHistory() {
	hist = history.Open(dataDir)
	go func() {
		for range time.Tick(time.Minute) {
			_ = hist.Save()
		}
	}()
}

// keepsHistory: only normal, Trading and Video call windows. Private, Safe
// Money and Kids never write anything.
func keepsHistory(c containers.Container) bool {
	return (c == containers.Personal || c == containers.Trading || c == containers.Calls) && !store.Get().NoHistory
}

func (t *Tab) recordVisit() {
	if hist != nil && keepsHistory(t.w.container) && !t.isNewTab && !t.isTools && t.errURL == "" {
		hist.Add(t.title, t.url)
	}
}

func (t *Tab) recordTitle() {
	if hist != nil && keepsHistory(t.w.container) {
		hist.SetTitle(t.url, t.title)
	}
}

func historyData(q string) map[string]any {
	var list []map[string]any
	for _, e := range hist.Search(q, 300) {
		list = append(list, map[string]any{"title": e.Title, "url": e.URL,
			"when": time.Unix(e.When, 0).Format("02 Jan 15:04")})
	}
	return map[string]any{"list": list, "q": q, "off": store.Get().NoHistory}
}

// ---- page size (zoom), kept per site ------------------------------------------

const (
	ctrlGetZoomFactor        = 7
	ctrlPutZoomFactor        = 8
	ctrlAddZoomFactorChanged = 9
)

func (t *Tab) controller() uintptr {
	if t.c == nil {
		return 0
	}
	ctrl := t.c.GetController()
	if ctrl == nil {
		return 0
	}
	return uintptr(unsafe.Pointer(ctrl))
}

func (t *Tab) zoom() int {
	var f float64
	if c := t.controller(); c == 0 || failed(call(c, ctrlGetZoomFactor, uintptr(unsafe.Pointer(&f)))) || f <= 0 {
		return 100
	}
	return int(math.Round(f * 100))
}

func (t *Tab) setZoom(pct int) {
	if pct < 25 {
		pct = 25
	}
	if pct > 500 {
		pct = 500
	}
	if c := t.controller(); c != 0 {
		call(c, ctrlPutZoomFactor, uintptr(math.Float64bits(float64(pct)/100)))
	}
}

// hookZoom keeps the size the user chose (Ctrl + / Ctrl - / Ctrl+wheel) for the site.
func (t *Tab) hookZoom() {
	c := t.controller()
	if c == 0 {
		return
	}
	if err := addEvent(c, ctrlAddZoomFactorChanged, newHandler(func(_, _ uintptr) uintptr {
		runOnUI(t.zoomChanged)
		return sOK
	})); err != nil {
		logf("zoom: event not available: %v", err)
	}
}

// zoomChanged keeps a size the user chose and shows it. It runs from the
// engine's event and also from the 5-second timer (Ctrl+wheel), so it never
// depends on one of them alone.
func (t *Tab) zoomChanged() {
	if t.destroyed {
		return
	}
	pct := t.zoom()
	if pct == t.zoomSeen {
		return
	}
	t.zoomSeen = pct
	if t.zoomHost != "" && t.w.container != containers.Private {
		store.SetZoom(t.zoomHost, pct)
	}
	if t.w.active == t {
		t.w.sendState()
	}
}

// applySiteZoom sets the kept size when the tab moves to another site.
func (t *Tab) applySiteZoom() {
	host := ""
	if strings.HasPrefix(t.url, "http") {
		host = strings.TrimPrefix(hostOf(t.url), "www.")
	}
	if host == t.zoomHost {
		return
	}
	t.zoomHost = host
	want := 100
	if host != "" && t.w.container != containers.Private {
		want = store.Zoom(host)
	}
	if t.zoom() != want {
		t.setZoom(want)
	}
	t.zoomSeen = want
}

// zoomSteps are the sizes Ctrl + / Ctrl - and the menu buttons go through.
var zoomSteps = []int{50, 67, 75, 80, 90, 100, 110, 125, 150, 175, 200, 250, 300}

func nextZoom(cur, dir int) int {
	if dir == 0 {
		return 100
	}
	if dir > 0 {
		for _, s := range zoomSteps {
			if s > cur {
				return s
			}
		}
		return zoomSteps[len(zoomSteps)-1]
	}
	for i := len(zoomSteps) - 1; i >= 0; i-- {
		if zoomSteps[i] < cur {
			return zoomSteps[i]
		}
	}
	return zoomSteps[0]
}

// ---- Study time (Focus) ------------------------------------------------------------

// focusAllowed: "Open for 5 minutes" answers, per site.
var focusAllowed = map[string]time.Time{}

// focusStops reports whether study time pauses this top-level address here.
func focusStops(c containers.Container, uri string) (bool, time.Time) {
	if c != containers.Personal && c != containers.Private {
		return false, time.Time{}
	}
	p := store.FocusPlan()
	on, end := focus.Active(p, time.Now())
	if !on || !focus.Paused(p, uri) {
		return false, time.Time{}
	}
	if time.Now().Before(focusAllowed[hostOf(uri)]) {
		return false, time.Time{}
	}
	return true, end
}

func (t *Tab) showFocusPage(uri string, end time.Time) {
	t.w.overlay("focus", map[string]any{"tab": t.id, "host": hostOf(uri), "url": uri, "until": end.Format("15:04")})
}

// focusCheckSource catches sites that change pages without loading
// (YouTube Shorts): back to the site's start page.
func (t *Tab) focusCheckSource() {
	if stop, end := focusStops(t.w.container, t.url); stop {
		u := t.url
		home := "https://" + hostOf(u) + "/"
		_ = t.wv.Navigate(home)
		if t.w.active == t {
			t.showFocusPage(u, end)
		}
	}
}

// focusMenuState is shown in the menu ("Study time until 21:00").
func focusMenuState() map[string]any {
	p := store.FocusPlan()
	on, end := focus.Active(p, time.Now())
	m := map[string]any{"on": on, "manual": p.Until > time.Now().Unix()}
	if on {
		m["until"] = end.Format("15:04")
	}
	return m
}

// onExtraMessage handles the toolbar messages of the 3.5.0 extras.
func (w *Window) onExtraMessage(m uiMsg) bool {
	switch m.Cmd {
	case "history":
		if hist != nil && (w.container == containers.Personal || w.container == containers.Trading || w.container == containers.Calls) {
			w.overlay("history", historyData(""))
		}
	case "histsearch":
		w.send(jsCall("historyList", historyData(m.Text)))
	case "histopen":
		if strings.HasPrefix(m.URL, "http://") || strings.HasPrefix(m.URL, "https://") {
			w.newTab(m.URL, true)
			w.send("window.ronin&&window.ronin.closeOverlay()")
		}
	case "histremove":
		hist.Remove(m.URL)
		w.send(jsCall("historyList", historyData(m.Text)))
	case "histclear":
		since := time.Time{}
		switch m.Text {
		case "hour":
			since = time.Now().Add(-time.Hour)
		case "day":
			since = time.Now().Add(-24 * time.Hour)
		}
		_ = hist.Clear(since)
		w.send(jsCall("historyList", historyData("")))
	case "nohistory":
		_ = store.Update(func(s *settingsT) { s.NoHistory = m.Value })
		if m.Value {
			_ = hist.Clear(time.Time{})
		}
		w.send(jsCall("historyList", historyData("")))
	case "zoom":
		if t := w.active; t != nil && !t.isNewTab {
			t.setZoom(nextZoom(t.zoom(), m.Number))
			time.AfterFunc(120*time.Millisecond, func() { runOnUI(t.zoomChanged) })
		}
	case "focusnow":
		mins := m.Number
		_ = store.Update(func(s *settingsT) {
			s.FocusUntil = 0
			if mins > 0 {
				s.FocusUntil = time.Now().Add(time.Duration(mins) * time.Minute).Unix()
			}
		})
		if mins > 0 {
			for _, t := range w.tabs {
				t.focusCheckSource()
			}
			w.bar(w.active, "ok", "Study time started. Time-wasting sites are paused for "+itoa(mins)+" minutes.", nil)
		} else {
			w.bar(w.active, "info", "Study time stopped.", nil)
		}
		w.sendState()
	case "focusset":
		parts := strings.SplitN(m.Text, "|", 3) // "18:00|21:00|site1\nsite2"
		if len(parts) == 3 {
			var sites []string
			for _, s := range strings.FieldsFunc(parts[2], func(r rune) bool { return r == '\n' || r == ',' || r == ' ' }) {
				if s = strings.TrimSpace(s); s != "" && len(sites) < 200 {
					sites = append(sites, s)
				}
			}
			_ = store.Update(func(s *settingsT) {
				s.FocusOn, s.FocusFrom, s.FocusTo, s.FocusSites = m.Value, parts[0], parts[1], sites
			})
		}
		w.overlay("settings", settingsData())
	case "focusallow":
		if h := hostOf(m.URL); h != "" {
			focusAllowed[h] = time.Now().Add(5 * time.Minute)
			if t := w.tabByID(m.ID); t != nil {
				_ = t.wv.Navigate(m.URL)
			}
		}
	default:
		return false
	}
	return true
}

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	s := ""
	for n > 0 {
		s = string(rune('0'+n%10)) + s
		n /= 10
	}
	return s
}
