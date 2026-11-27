//go:build windows

package main

// WebView2 events the library does not wrap (same vtable indices as cmd/ronin/com_windows.go):
// pop-up windows, navigation to other apps (mailto:, ms-settings: ...) and downloads.

import (
	"strings"
	"sync"
	"syscall"
	"unsafe"

	"golang.org/x/sys/windows"
)

const (
	wvAddNavigationStarting = 7
	wvAddNewWindowRequested = 44
	wv4AddDownloadStarting  = 75
	nsUri                   = 3
	nsSetCancel             = 8
	nwUri                   = 3
	nwSetHandled            = 6
	dsSetCancel             = 5
)

var iidICoreWebView2_4 = windows.GUID{Data1: 0x20d02d59, Data2: 0x6df2, Data3: 0x42dc, Data4: [8]byte{0xbd, 0x06, 0xf9, 0x8a, 0x69, 0x4b, 0x13, 0x02}}

var pCoTaskMemFree = windows.NewLazySystemDLL("ole32.dll").NewProc("CoTaskMemFree")

func comCall(obj uintptr, idx int, args ...uintptr) uintptr {
	if obj == 0 {
		return 0x80004003
	}
	vtbl := *(*uintptr)(unsafe.Pointer(obj))
	fn := *(*uintptr)(unsafe.Pointer(vtbl + uintptr(idx)*unsafe.Sizeof(uintptr(0))))
	r, _, _ := syscall.SyscallN(fn, append([]uintptr{obj}, args...)...)
	return r
}

func comString(obj uintptr, idx int) string {
	var p *uint16
	if int32(comCall(obj, idx, uintptr(unsafe.Pointer(&p)))) < 0 || p == nil {
		return ""
	}
	s := windows.UTF16PtrToString(p)
	pCoTaskMemFree.Call(uintptr(unsafe.Pointer(p)))
	return s
}

type handler struct {
	vtbl   *handlerVtbl
	invoke func(sender, args uintptr) uintptr
}

type handlerVtbl struct{ QueryInterface, AddRef, Release, Invoke uintptr }

var (
	sharedVtbl   *handlerVtbl
	vtblOnce     sync.Once
	liveHandlers []*handler
)

func newHandler(fn func(sender, args uintptr) uintptr) *handler {
	vtblOnce.Do(func() {
		sharedVtbl = &handlerVtbl{
			QueryInterface: windows.NewCallback(func(this, riid, ppv uintptr) uintptr {
				if ppv == 0 {
					return 0x80004003
				}
				*(*uintptr)(unsafe.Pointer(ppv)) = this
				return 0
			}),
			AddRef:  windows.NewCallback(func(this uintptr) uintptr { return 1 }),
			Release: windows.NewCallback(func(this uintptr) uintptr { return 1 }),
			Invoke: windows.NewCallback(func(this, sender, args uintptr) uintptr {
				h := (*handler)(unsafe.Pointer(this))
				defer func() {
					if r := recover(); r != nil {
						logf("event handler panic: %v", r)
					}
				}()
				if h.invoke != nil {
					return h.invoke(sender, args)
				}
				return 0
			}),
		}
	})
	h := &handler{vtbl: sharedVtbl, invoke: fn}
	liveHandlers = append(liveHandlers, h)
	return h
}

func addEvent(obj uintptr, idx int, h *handler) {
	var token int64
	comCall(obj, idx, uintptr(unsafe.Pointer(h)), uintptr(unsafe.Pointer(&token)))
}

// hookPageEvents: pop-ups open in the same Claude tab (through the same guard); other apps are never launched;
// downloads are cancelled in the pilot.
func hookPageEvents(core uintptr, navigateSame func(string)) {
	addEvent(core, wvAddNewWindowRequested, newHandler(func(_, args uintptr) uintptr {
		uri := comString(args, nwUri)
		comCall(args, nwSetHandled, 1)
		if strings.HasPrefix(uri, "https://") || strings.HasPrefix(uri, "http://") {
			runOnUI(func() { navigateSame(uri) })
		}
		return 0
	}))
	addEvent(core, wvAddNavigationStarting, newHandler(func(_, args uintptr) uintptr {
		uri := strings.ToLower(comString(args, nsUri))
		if !(strings.HasPrefix(uri, "http://") || strings.HasPrefix(uri, "https://") || strings.HasPrefix(uri, "about:") || strings.HasPrefix(uri, "data:")) {
			comCall(args, nsSetCancel, 1)
			logf("blocked: opening another app (%s)", trim(strings.SplitN(uri, ":", 2)[0], 20))
		}
		return 0
	}))
	var w4 uintptr
	comCall(core, 0, uintptr(unsafe.Pointer(&iidICoreWebView2_4)), uintptr(unsafe.Pointer(&w4)))
	if w4 != 0 {
		addEvent(w4, wv4AddDownloadStarting, newHandler(func(_, args uintptr) uintptr {
			comCall(args, dsSetCancel, 1)
			logf("download cancelled (not used in the pilot)")
			return 0
		}))
	}
}
