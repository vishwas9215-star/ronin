//go:build windows

package main

// Small COM helpers for the WebView2 events the library does not wrap:
// NavigationStarting, NewWindowRequested, SourceChanged, HistoryChanged,
// DocumentTitleChanged, DownloadStarting and download StateChanged.
// Vtable indices were checked against Microsoft's WebView2 metadata.

import (
	"errors"
	"fmt"
	"sync"
	"syscall"
	"unsafe"

	"golang.org/x/sys/windows"
)

// ---- vtable indices -------------------------------------------------------

const (
	// ICoreWebView2
	wvSource                  = 4
	wvNavigate                = 5
	wvNavigateToString        = 6
	wvAddNavigationStarting   = 7
	wvAddSourceChanged        = 11
	wvAddHistoryChanged       = 13
	wvReload                  = 31
	wvCanGoBack               = 38
	wvCanGoForward            = 39
	wvGoBack                  = 40
	wvGoForward               = 41
	wvStop                    = 43
	wvAddNewWindowRequested   = 44
	wvAddDocumentTitleChanged = 46
	wvDocumentTitle           = 48
	// ICoreWebView2_4 (inherits _3, _2, ICoreWebView2)
	wv4AddDownloadStarting = 75

	// ICoreWebView2NavigationStartingEventArgs
	nsUri           = 3
	nsUserInitiated = 4
	nsRedirected    = 5
	nsSetCancel     = 8
	nsNavigationId  = 9

	// ICoreWebView2NewWindowRequestedEventArgs
	nwUri           = 3
	nwSetHandled    = 6
	nwUserInitiated = 8

	// ICoreWebView2DownloadStartingEventArgs
	dsDownloadOperation = 3
	dsSetCancel         = 5
	dsResultFilePath    = 6
	dsSetResultFilePath = 7
	dsPutHandled        = 9

	// ICoreWebView2DownloadOperation
	doAddBytesReceived = 3
	doTotalBytes       = 12
	doBytesReceived    = 13
	doCancel           = 18
	doPause            = 19
	doResume           = 20
	doCanResume        = 21
	doAddStateChanged  = 7
	doUri              = 9
	doResultFilePath   = 15
	doState            = 16

	downloadCompleted   = 2
	downloadInterrupted = 1

	sOK          = 0
	eNoInterface = 0x80004002
)

var iidICoreWebView2_4 = windows.GUID{Data1: 0x20d02d59, Data2: 0x6df2, Data3: 0x42dc, Data4: [8]byte{0xbd, 0x06, 0xf9, 0x8a, 0x69, 0x4b, 0x13, 0x02}}

var ole32 = windows.NewLazySystemDLL("ole32.dll")
var pCoTaskMemFree = ole32.NewProc("CoTaskMemFree")

// call invokes method number idx of the COM object obj.
func call(obj uintptr, idx int, args ...uintptr) uintptr {
	if obj == 0 {
		return 0x80004003 // E_POINTER
	}
	vtbl := *(*uintptr)(unsafe.Pointer(obj))
	fn := *(*uintptr)(unsafe.Pointer(vtbl + uintptr(idx)*unsafe.Sizeof(uintptr(0))))
	all := append([]uintptr{obj}, args...)
	r, _, _ := syscall.SyscallN(fn, all...)
	return r
}

func failed(hr uintptr) bool { return int32(hr) < 0 }

func hrErr(hr uintptr) error {
	if !failed(hr) {
		return nil
	}
	return fmt.Errorf("COM error 0x%08x", uint32(hr))
}

// getString calls a method that returns an LPWSTR the caller must free.
func getString(obj uintptr, idx int) string {
	var p *uint16
	if failed(call(obj, idx, uintptr(unsafe.Pointer(&p)))) || p == nil {
		return ""
	}
	s := windows.UTF16PtrToString(p)
	pCoTaskMemFree.Call(uintptr(unsafe.Pointer(p)))
	return s
}

func getBool(obj uintptr, idx int) bool {
	var b int32
	if failed(call(obj, idx, uintptr(unsafe.Pointer(&b)))) {
		return false
	}
	return b != 0
}

func boolArg(b bool) uintptr {
	if b {
		return 1
	}
	return 0
}

func putString(obj uintptr, idx int, s string) error {
	p, err := windows.UTF16PtrFromString(s)
	if err != nil {
		return err
	}
	return hrErr(call(obj, idx, uintptr(unsafe.Pointer(p))))
}

func release(obj uintptr) {
	if obj != 0 {
		call(obj, 2)
	}
}

func queryInterface(obj uintptr, iid *windows.GUID) uintptr {
	var out uintptr
	if failed(call(obj, 0, uintptr(unsafe.Pointer(iid)), uintptr(unsafe.Pointer(&out)))) {
		return 0
	}
	return out
}

// ---- event handler objects -----------------------------------------------

// handler is a minimal COM object with one Invoke(sender, args) method.
// Objects stay referenced in liveHandlers for the life of the program
// (Go's garbage collector does not move heap objects).
type handler struct {
	vtbl   *handlerVtbl
	invoke func(sender, args uintptr) uintptr
}

type handlerVtbl struct {
	QueryInterface, AddRef, Release, Invoke uintptr
}

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
				return sOK
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
				return sOK
			}),
		}
	})
	h := &handler{vtbl: sharedVtbl, invoke: fn}
	liveHandlers = append(liveHandlers, h)
	return h
}

// addEvent registers h on obj using the add_XXX method at idx.
func addEvent(obj uintptr, idx int, h *handler) error {
	var token int64
	return hrErr(call(obj, idx, uintptr(unsafe.Pointer(h)), uintptr(unsafe.Pointer(&token))))
}

// ---- typed helpers for ICoreWebView2 --------------------------------------

type webview uintptr

func (w webview) Navigate(u string) error {
	if w == 0 {
		return errors.New("no webview")
	}
	return putString(uintptr(w), wvNavigate, u)
}

func (w webview) NavigateToString(html string) error {
	if w == 0 {
		return errors.New("no webview")
	}
	return putString(uintptr(w), wvNavigateToString, html)
}

func (w webview) Source() string    { return getString(uintptr(w), wvSource) }
func (w webview) Title() string     { return getString(uintptr(w), wvDocumentTitle) }
func (w webview) CanGoBack() bool   { return getBool(uintptr(w), wvCanGoBack) }
func (w webview) CanGoFwd() bool    { return getBool(uintptr(w), wvCanGoForward) }
func (w webview) GoBack()           { call(uintptr(w), wvGoBack) }
func (w webview) GoForward()        { call(uintptr(w), wvGoForward) }
func (w webview) Reload()           { call(uintptr(w), wvReload) }
func (w webview) Stop()             { call(uintptr(w), wvStop) }
func (w webview) webview4() uintptr { return queryInterface(uintptr(w), &iidICoreWebView2_4) }
