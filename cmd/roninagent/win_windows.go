//go:build windows

package main

// The few Win32 calls the agent window needs (the same patterns as cmd/ronin).

import (
	"sync"
	"unsafe"

	"golang.org/x/sys/windows"
)

var (
	user32   = windows.NewLazySystemDLL("user32.dll")
	kernel32 = windows.NewLazySystemDLL("kernel32.dll")
	crypt32  = windows.NewLazySystemDLL("crypt32.dll")

	pRegisterClassExW    = user32.NewProc("RegisterClassExW")
	pCreateWindowExW     = user32.NewProc("CreateWindowExW")
	pDefWindowProcW      = user32.NewProc("DefWindowProcW")
	pShowWindow          = user32.NewProc("ShowWindow")
	pGetMessageW         = user32.NewProc("GetMessageW")
	pTranslateMessage    = user32.NewProc("TranslateMessage")
	pDispatchMessageW    = user32.NewProc("DispatchMessageW")
	pPostQuitMessage     = user32.NewProc("PostQuitMessage")
	pPostMessageW        = user32.NewProc("PostMessageW")
	pGetClientRect       = user32.NewProc("GetClientRect")
	pLoadCursorW         = user32.NewProc("LoadCursorW")
	pGetLastInputInfo    = user32.NewProc("GetLastInputInfo")
	pGetForegroundWindow = user32.NewProc("GetForegroundWindow")
	pGetCursorPos        = user32.NewProc("GetCursorPos")
	pScreenToClient      = user32.NewProc("ScreenToClient")
	pSetTimer            = user32.NewProc("SetTimer")
	pGetDpiForWindow     = user32.NewProc("GetDpiForWindow")
	pSetProcessDpiCtx    = user32.NewProc("SetProcessDpiAwarenessContext")
	pGetModuleHandleW    = kernel32.NewProc("GetModuleHandleW")
	pGetCurrentThreadId  = kernel32.NewProc("GetCurrentThreadId")
	pLocalFree           = kernel32.NewProc("LocalFree")
	pCryptProtect        = crypt32.NewProc("CryptProtectData")
	pCryptUnprotect      = crypt32.NewProc("CryptUnprotectData")
)

const (
	wmDestroy = 0x0002
	wmSize    = 0x0005
	wmClose   = 0x0010
	wmTimer   = 0x0113
	wmApp     = 0x8000

	wsOverlappedWindow = 0x00CF0000
	wsClipChildren     = 0x02000000
	cwUseDefault       = ^uintptr(0x7FFFFFFF)
	swShowMaximized    = 3
	swShow             = 5
	swHide             = 0
	hwndMessage        = ^uintptr(2)
)

type wndClassEx struct {
	cbSize        uint32
	style         uint32
	lpfnWndProc   uintptr
	cbClsExtra    int32
	cbWndExtra    int32
	hInstance     uintptr
	hIcon         uintptr
	hCursor       uintptr
	hbrBackground uintptr
	lpszMenuName  *uint16
	lpszClassName *uint16
	hIconSm       uintptr
}

type msgT struct {
	hwnd    uintptr
	message uint32
	wParam  uintptr
	lParam  uintptr
	time    uint32
	ptX     int32
	ptY     int32
	private uint32
}

type rect struct{ Left, Top, Right, Bottom int32 }
type point struct{ X, Y int32 }

func utf16(s string) *uint16 {
	p, err := windows.UTF16PtrFromString(s)
	if err != nil {
		p, _ = windows.UTF16PtrFromString("?")
	}
	return p
}

func clientRect(hwnd uintptr) rect {
	var r rect
	pGetClientRect.Call(hwnd, uintptr(unsafe.Pointer(&r)))
	return r
}

func dpiOf(hwnd uintptr) int {
	if pGetDpiForWindow.Find() != nil {
		return 96
	}
	r, _, _ := pGetDpiForWindow.Call(hwnd)
	if r == 0 {
		return 96
	}
	return int(r)
}

func lastInputTick() uint32 {
	li := struct{ cbSize, dwTime uint32 }{cbSize: 8}
	pGetLastInputInfo.Call(uintptr(unsafe.Pointer(&li)))
	return li.dwTime
}

func threadID() uint32 { r, _, _ := pGetCurrentThreadId.Call(); return uint32(r) }

// ---- work on the UI thread (WebView2 may only be used there) ----

var (
	uiMu      sync.Mutex
	uiQueue   []func()
	msgHwnd   uintptr
	uiThread  uint32
	wndProcCb uintptr
	hInstance uintptr
	onMainMsg func(hwnd, msg, wparam, lparam uintptr) (uintptr, bool)
)

func runOnUI(f func()) {
	uiMu.Lock()
	uiQueue = append(uiQueue, f)
	uiMu.Unlock()
	pPostMessageW.Call(msgHwnd, wmApp, 0, 0)
}

func onUIThread() bool { return threadID() == uiThread }

func drainUIQueue() {
	for {
		uiMu.Lock()
		if len(uiQueue) == 0 {
			uiMu.Unlock()
			return
		}
		f := uiQueue[0]
		uiQueue = uiQueue[1:]
		uiMu.Unlock()
		func() {
			defer func() {
				if r := recover(); r != nil {
					logf("ui task panic: %v", r)
				}
			}()
			f()
		}()
	}
}

func wndProc(hwnd, msg, wparam, lparam uintptr) uintptr {
	if hwnd == msgHwnd && msgHwnd != 0 {
		if msg == wmApp {
			drainUIQueue()
		}
		r, _, _ := pDefWindowProcW.Call(hwnd, msg, wparam, lparam)
		return r
	}
	if onMainMsg != nil {
		if r, handled := onMainMsg(hwnd, msg, wparam, lparam); handled {
			return r
		}
	}
	r, _, _ := pDefWindowProcW.Call(hwnd, msg, wparam, lparam)
	return r
}

func initWin32() error {
	if pSetProcessDpiCtx.Find() == nil {
		pSetProcessDpiCtx.Call(^uintptr(3)) // PER_MONITOR_AWARE_V2
	}
	uiThread = threadID()
	hInstance, _, _ = pGetModuleHandleW.Call(0)
	cursor, _, _ := pLoadCursorW.Call(0, 32512)
	wndProcCb = windows.NewCallback(wndProc)
	for _, cls := range []string{"RoninAgentWindow", "RoninAgentMsg"} {
		wc := wndClassEx{cbSize: uint32(unsafe.Sizeof(wndClassEx{})), style: 3, lpfnWndProc: wndProcCb, hInstance: hInstance, hCursor: cursor, lpszClassName: utf16(cls)}
		if r, _, err := pRegisterClassExW.Call(uintptr(unsafe.Pointer(&wc))); r == 0 {
			return err
		}
	}
	msgHwnd, _, _ = pCreateWindowExW.Call(0, uintptr(unsafe.Pointer(utf16("RoninAgentMsg"))), 0, 0, 0, 0, 0, 0, hwndMessage, 0, hInstance, 0)
	return nil
}

func createMainWindow(title string) uintptr {
	h, _, _ := pCreateWindowExW.Call(0, uintptr(unsafe.Pointer(utf16("RoninAgentWindow"))), uintptr(unsafe.Pointer(utf16(title))),
		wsOverlappedWindow|wsClipChildren, cwUseDefault, cwUseDefault, cwUseDefault, cwUseDefault, 0, 0, hInstance, 0)
	return h
}

func runMessageLoop() {
	var m msgT
	for {
		r, _, _ := pGetMessageW.Call(uintptr(unsafe.Pointer(&m)), 0, 0, 0)
		if int32(r) <= 0 {
			return
		}
		pTranslateMessage.Call(uintptr(unsafe.Pointer(&m)))
		pDispatchMessageW.Call(uintptr(unsafe.Pointer(&m)))
	}
}

// ---- DPAPI: the link file opens only for this Windows user on this PC ----

type dataBlob struct {
	cb   uint32
	data *byte
}

func blob(b []byte) *dataBlob {
	if len(b) == 0 {
		return &dataBlob{}
	}
	return &dataBlob{cb: uint32(len(b)), data: &b[0]}
}

func (b *dataBlob) bytes() []byte {
	if b.cb == 0 {
		return nil
	}
	out := make([]byte, b.cb)
	copy(out, unsafe.Slice(b.data, b.cb))
	return out
}

var entropy = []byte("ronin-agent-lab-v1")

func dpapiSeal(b []byte) ([]byte, error) {
	var out dataBlob
	r, _, err := pCryptProtect.Call(uintptr(unsafe.Pointer(blob(b))), 0, uintptr(unsafe.Pointer(blob(entropy))), 0, 0, 0x1 /*UI_FORBIDDEN*/, uintptr(unsafe.Pointer(&out)))
	if r == 0 {
		return nil, err
	}
	defer pLocalFree.Call(uintptr(unsafe.Pointer(out.data)))
	return out.bytes(), nil
}

func dpapiOpen(b []byte) ([]byte, error) {
	var out dataBlob
	r, _, err := pCryptUnprotect.Call(uintptr(unsafe.Pointer(blob(b))), 0, uintptr(unsafe.Pointer(blob(entropy))), 0, 0, 0x1, uintptr(unsafe.Pointer(&out)))
	if r == 0 {
		return nil, err
	}
	defer pLocalFree.Call(uintptr(unsafe.Pointer(out.data)))
	return out.bytes(), nil
}

func cursorInClient(hwnd uintptr) (int32, int32) {
	var p point
	pGetCursorPos.Call(uintptr(unsafe.Pointer(&p)))
	pScreenToClient.Call(hwnd, uintptr(unsafe.Pointer(&p)))
	return p.X, p.Y
}
