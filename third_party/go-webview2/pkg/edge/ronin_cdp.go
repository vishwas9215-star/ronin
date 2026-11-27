//go:build windows
// +build windows

package edge

// Ronin patch: the DevTools protocol (trusted input, isolated worlds, screenshots) for the Ronin Agent.
// ICoreWebView2::CallDevToolsProtocolMethod with a Go completion handler. Call it on the UI thread; the callback
// also comes on the UI thread.

import (
	"sync"
	"unsafe"

	"golang.org/x/sys/windows"
)

type _ICoreWebView2CallDevToolsProtocolMethodCompletedHandlerVtbl struct {
	_IUnknownVtbl
	Invoke ComProc
}

type iCoreWebView2CallDevToolsProtocolMethodCompletedHandler struct {
	vtbl *_ICoreWebView2CallDevToolsProtocolMethodCompletedHandlerVtbl
	done func(errorCode uintptr, json string)
}

var (
	cdpKeep   = map[*iCoreWebView2CallDevToolsProtocolMethodCompletedHandler]bool{} // alive until answered
	cdpKeepMu sync.Mutex
)

func cdpQI(this *iCoreWebView2CallDevToolsProtocolMethodCompletedHandler, refiid, object uintptr) uintptr {
	return 0x80004002 // E_NOINTERFACE
}
func cdpAddRef(this *iCoreWebView2CallDevToolsProtocolMethodCompletedHandler) uintptr  { return 1 }
func cdpRelease(this *iCoreWebView2CallDevToolsProtocolMethodCompletedHandler) uintptr { return 1 }
func cdpInvoke(this *iCoreWebView2CallDevToolsProtocolMethodCompletedHandler, errorCode uintptr, result *uint16) uintptr {
	s := ""
	if result != nil {
		s = windows.UTF16PtrToString(result)
	}
	cdpKeepMu.Lock()
	delete(cdpKeep, this)
	cdpKeepMu.Unlock()
	if this.done != nil {
		this.done(errorCode, s)
	}
	return 0
}

var cdpVtbl = _ICoreWebView2CallDevToolsProtocolMethodCompletedHandlerVtbl{
	_IUnknownVtbl{NewComProc(cdpQI), NewComProc(cdpAddRef), NewComProc(cdpRelease)},
	NewComProc(cdpInvoke),
}

// CallDevTools runs one DevTools protocol method; done gets the HRESULT and the result JSON.
func (e *Chromium) CallDevTools(method, paramsJSON string, done func(hr uintptr, resultJSON string)) error {
	if e.webview == nil {
		return windows.Errno(windows.E_POINTER)
	}
	m, err := windows.UTF16PtrFromString(method)
	if err != nil {
		return err
	}
	p, err := windows.UTF16PtrFromString(paramsJSON)
	if err != nil {
		return err
	}
	h := &iCoreWebView2CallDevToolsProtocolMethodCompletedHandler{vtbl: &cdpVtbl, done: done}
	cdpKeepMu.Lock()
	cdpKeep[h] = true
	cdpKeepMu.Unlock()
	hr, _, _ := e.webview.vtbl.CallDevToolsProtocolMethod.Call(uintptr(unsafe.Pointer(e.webview)), uintptr(unsafe.Pointer(m)), uintptr(unsafe.Pointer(p)), uintptr(unsafe.Pointer(h)))
	if windows.Handle(hr) != windows.S_OK {
		cdpKeepMu.Lock()
		delete(cdpKeep, h)
		cdpKeepMu.Unlock()
		return windows.Errno(hr)
	}
	return nil
}

// Source: the current address of the page.
func (e *Chromium) Source() string {
	if e.webview == nil {
		return ""
	}
	s, _ := e.webview.GetSource()
	return s
}
