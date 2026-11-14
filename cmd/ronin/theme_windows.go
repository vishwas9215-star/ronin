//go:build windows

package main

import (
	"unsafe"

	"golang.org/x/sys/windows"

	"roninbrowser/internal/containers"
)

// ---- Day / Night mode ------------------------------------------------------------

var iidICoreWebView2_13 = windows.GUID{Data1: 0xF75F09A8, Data2: 0x667E, Data3: 0x4983, Data4: [8]byte{0x88, 0xD6, 0xC8, 0x77, 0x3F, 0x31, 0x5E, 0x84}}

const (
	wv13GetProfile              = 105
	profPutPreferredColorScheme = 9
	schemeLight                 = 1
	schemeDark                  = 2
)

// themeName is "light" in Day mode and "" in Night mode (the default).
func themeName() string {
	if store != nil && store.Get().Theme == "light" {
		return "light"
	}
	return ""
}

// uiBackground is the colour Ronin's own pages start with (no flash).
func uiBackground() (r, g, b uint8) {
	if themeName() == "light" {
		return 0xee, 0xf0, 0xf4
	}
	return 0x14, 0x17, 0x1f
}

// setPageScheme tells web sites which look the user prefers (sites that
// offer a dark design use it in Night mode).
func (t *Tab) setPageScheme() {
	if t.wv == 0 {
		return
	}
	w13 := queryInterface(uintptr(t.wv), &iidICoreWebView2_13)
	if w13 == 0 {
		return // older engine: pages keep their own look
	}
	defer release(w13)
	var prof uintptr
	if failed(call(w13, wv13GetProfile, uintptr(unsafe.Pointer(&prof)))) || prof == 0 {
		return
	}
	defer release(prof)
	scheme := uintptr(schemeDark)
	if themeName() == "light" {
		scheme = schemeLight
	}
	call(prof, profPutPreferredColorScheme, scheme)
}

// toggleTheme switches Day <-> Night in every window at once.
func toggleTheme() {
	next := "light"
	if themeName() == "light" {
		next = ""
	}
	_ = store.Update(func(s *settingsT) { s.Theme = next })
	r, g, b := uiBackground()
	js := jsCall("theme", themeName())
	for _, w := range windowsSnapshot() {
		if w.closing {
			continue
		}
		setDarkTitleBar(w.hwnd, next != "light")
		if w.ui != nil {
			w.ui.SetBackgroundColour(r, g, b, 255)
		}
		w.send(js)
		if w.panel != nil && w.panelReady {
			w.panel.SetBackgroundColour(r, g, b, 255)
			w.panel.Eval(js)
		}
		for _, t := range w.tabs {
			t.setPageScheme() // pages switch at once (they follow the engine's colour choice)
			if t.isNewTab && !t.destroyed {
				t.showNewTab()
			}
		}
		pInvalidateRect.Call(w.hwnd, 0, 1) // the window frame takes the new colour
	}
}

// newTabBackground is the new tab page's own colour (same as newtab.html), so
// opening a tab never flashes white.
func newTabBackground(c containers.Container) (r, g, b uint8) {
	light := themeName() == "light"
	switch {
	case c == containers.Banking && light:
		return 0xe9, 0xf5, 0xee
	case c == containers.Banking:
		return 0x0f, 0x1a, 0x14
	case c == containers.Private && light:
		return 0xf1, 0xee, 0xf9
	case c == containers.Private:
		return 0x16, 0x13, 0x1f
	}
	return uiBackground()
}
