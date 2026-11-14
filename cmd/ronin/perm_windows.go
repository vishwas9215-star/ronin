//go:build windows

package main

import (
	"fmt"
	"strings"
	"time"
	"unsafe"

	"roninbrowser/internal/containers"
	"roninbrowser/internal/safemoney"
)

// ---- site permissions: Ronin asks, and remembers the answer ------------------

// ICoreWebView2PermissionRequestedEventArgs
const (
	prUri         = 3
	prKind        = 4
	prSetState    = 7
	prGetDeferral = 8

	permAllow = 1
	permDeny  = 2
)

// permission kinds Ronin asks about, with the words shown to the user
var permNames = map[int32]struct{ key, words string }{
	1: {"microphone", "use your microphone"},
	2: {"camera", "use your camera"},
	3: {"location", "know your location"},
	6: {"clipboard", "read what you copied (clipboard)"},
}

type permAsk struct {
	args, deferral uintptr
	host, key      string
	tab            *Tab
}

var (
	permPending = map[int]*permAsk{}
	permNextID  int
)

// onPermission handles a site's request. It returns false to leave the
// request to the fixed rules (Safe Money and Kids refuse everything).
func (t *Tab) onPermission(args uintptr) bool {
	c := t.w.container
	if c != containers.Personal && c != containers.Private && c != containers.Trading && c != containers.Calls {
		return false
	}
	var kind int32
	call(args, prKind, uintptr(unsafe.Pointer(&kind)))
	p, ok := permNames[kind]
	if !ok {
		return false
	}
	host := hostOf(getString(args, prUri))
	if host == "" {
		call(args, prSetState, permDeny)
		return true
	}
	if autoAllowCall(c, host, p.key) {
		// Video call window + a known call site: camera and microphone
		// without asking every time (the user opened a call on purpose).
		call(args, prSetState, permAllow)
		return true
	}
	switch store.SitePerm(host, p.key) {
	case "allow":
		call(args, prSetState, permAllow)
		return true
	case "block":
		call(args, prSetState, permDeny)
		return true
	}
	// ask the user, without blocking the engine
	var def uintptr
	if failed(call(args, prGetDeferral, uintptr(unsafe.Pointer(&def)))) || def == 0 {
		call(args, prSetState, permDeny)
		return true
	}
	call(args, 1) // AddRef: the args are used after this event returns
	permNextID++
	id := permNextID
	permPending[id] = &permAsk{args: args, deferral: def, host: host, key: p.key, tab: t}
	runOnUI(func() {
		ids := fmt.Sprint(id)
		t.w.bar(t, "warn", host+" wants to "+p.words+".", []map[string]string{
			{"label": "Allow", "cmd": "permanswer", "url": ids + "|allow"},
			{"label": "Block", "cmd": "permanswer", "url": ids + "|block"},
		})
	})
	time.AfterFunc(2*time.Minute, func() { runOnUI(func() { answerPermission(fmt.Sprint(id) + "|block") }) })
	return true
}

// autoAllowCall: camera and microphone are allowed at once for known video
// call sites, only inside the Video call window, unless the user blocked it.
func autoAllowCall(c containers.Container, host, key string) bool {
	if c != containers.Calls || (key != "camera" && key != "microphone") {
		return false
	}
	return safemoney.IsCallHost(host) && store.SitePerm(host, key) != "block"
}

// answerPermission is the user's choice ("<id>|allow" or "<id>|block").
func answerPermission(v string) {
	ids, answer, _ := strings.Cut(v, "|")
	var id int
	fmt.Sscan(ids, &id)
	a := permPending[id]
	if a == nil {
		return
	}
	delete(permPending, id)
	state := uintptr(permDeny)
	if answer == "allow" {
		state = permAllow
	}
	call(a.args, prSetState, state)
	call(a.deferral, 3) // Complete
	release(a.deferral)
	release(a.args)
	if a.tab.w.container != containers.Private { // private windows remember nothing
		store.SetSitePerm(a.host, a.key, answer)
	}
}

// sitePermsData lists the remembered answers for the Settings panel.
func sitePermsData() []map[string]string {
	var out []map[string]string
	for k, v := range store.Get().SitePerms {
		host, kind, _ := strings.Cut(k, "|")
		out = append(out, map[string]string{"host": host, "kind": kind, "answer": v, "id": k})
	}
	return out
}
