//go:build windows

package main

import (
	"os"
	"path/filepath"
	"sort"

	"roninbrowser/internal/containers"
	"roninbrowser/internal/session"
)

// quitting is set while Ronin closes all windows itself (restart), so the
// session is not cut down window by window.
var quitting bool

func sessionPath() string { return filepath.Join(dataDir, "session.json") }

// windowsSnapshot returns the open windows in a stable order.
func windowsSnapshot() []*Window {
	var ws []*Window
	for _, w := range windowsByHwnd {
		ws = append(ws, w)
	}
	sort.Slice(ws, func(i, j int) bool { return ws[i].created.Before(ws[j].created) })
	return ws
}

// saveSession writes the normal and trading windows; skip is a window that
// is closing and must be left out.
func saveSession(skip *Window) {
	var s session.Session
	for _, w := range windowsSnapshot() {
		if w == skip || w.closing || (w.container != containers.Personal && w.container != containers.Trading) || w.mini {
			continue
		}
		sw := session.Window{Container: string(w.container), Grid: w.grid}
		for i, t := range w.tabs {
			if t.isNewTab || t.url == "" {
				continue
			}
			if t == w.active {
				sw.Active = len(sw.URLs)
			}
			sw.URLs = append(sw.URLs, t.url)
			_ = i
		}
		s.Windows = append(s.Windows, sw)
	}
	if err := session.Save(sessionPath(), s); err != nil {
		logf("session save: %v", err)
	}
}

// restoreSession reopens saved windows; returns false when there was nothing.
func restoreSession() bool {
	s := session.Load(sessionPath())
	_ = os.Remove(sessionPath()) // used once; not kept on disk
	opened := false
	for _, sw := range s.Windows {
		c := containers.Container(sw.Container)
		if !c.Valid() {
			continue
		}
		if w := newWindowWith(c, sw.URLs, sw.Active, sw.Grid, false); w != nil {
			opened = true
		}
	}
	return opened
}

// onWindowClosing: closed tabs are not remembered (the user wants a fresh
// start every time), so nothing is saved here.
func onWindowClosing(w *Window) {}
