//go:build windows

package main

import (
	"strings"
	"sync"
	"unsafe"

	"golang.org/x/sys/windows"

	"roninbrowser/internal/containers"
)

var (
	user32   = windows.NewLazySystemDLL("user32.dll")
	kernel32 = windows.NewLazySystemDLL("kernel32.dll")
	gdi32    = windows.NewLazySystemDLL("gdi32.dll")

	pRegisterClassExW           = user32.NewProc("RegisterClassExW")
	pCreateWindowExW            = user32.NewProc("CreateWindowExW")
	pInvalidateRect             = user32.NewProc("InvalidateRect")
	pDefWindowProcW             = user32.NewProc("DefWindowProcW")
	pDestroyWindow              = user32.NewProc("DestroyWindow")
	pShowWindow                 = user32.NewProc("ShowWindow")
	pUpdateWindow               = user32.NewProc("UpdateWindow")
	pGetMessageW                = user32.NewProc("GetMessageW")
	pTranslateMessage           = user32.NewProc("TranslateMessage")
	pDispatchMessageW           = user32.NewProc("DispatchMessageW")
	pPostQuitMessage            = user32.NewProc("PostQuitMessage")
	pPostMessageW               = user32.NewProc("PostMessageW")
	pGetClientRect              = user32.NewProc("GetClientRect")
	pSetWindowTextW             = user32.NewProc("SetWindowTextW")
	pLoadCursorW                = user32.NewProc("LoadCursorW")
	pLoadImageW                 = user32.NewProc("LoadImageW")
	pMessageBoxW                = user32.NewProc("MessageBoxW")
	pSetWindowDisplayAffinity   = user32.NewProc("SetWindowDisplayAffinity")
	pGetLastInputInfo           = user32.NewProc("GetLastInputInfo")
	pSetForegroundWindow        = user32.NewProc("SetForegroundWindow")
	pGetForegroundWindow        = user32.NewProc("GetForegroundWindow")
	pGetKeyState                = user32.NewProc("GetKeyState")
	pGetAsyncKeyState           = user32.NewProc("GetAsyncKeyState")
	pGetDpiForWindow            = user32.NewProc("GetDpiForWindow")
	pSetProcessDpiAwarenessCtx  = user32.NewProc("SetProcessDpiAwarenessContext")
	pOpenClipboard              = user32.NewProc("OpenClipboard")
	pEmptyClipboard             = user32.NewProc("EmptyClipboard")
	pCloseClipboard             = user32.NewProc("CloseClipboard")
	pGetClipboardSequenceNumber = user32.NewProc("GetClipboardSequenceNumber")
	pSetTimer                   = user32.NewProc("SetTimer")
	pKillTimer                  = user32.NewProc("KillTimer")
	pIsIconic                   = user32.NewProc("IsIconic")
	pSetWindowPos               = user32.NewProc("SetWindowPos")
	pGetTickCount64             = kernel32.NewProc("GetTickCount64")
	pGetModuleHandleW           = kernel32.NewProc("GetModuleHandleW")
	pCreateSolidBrush           = gdi32.NewProc("CreateSolidBrush")
	pFillRect                   = user32.NewProc("FillRect")
	pGetSystemMetrics           = user32.NewProc("GetSystemMetrics")
	pSendInput                  = user32.NewProc("SendInput")
)

const (
	wmDestroy    = 0x0002
	wmMove       = 0x0003
	wmSize       = 0x0005
	wmActivate   = 0x0006
	wmClose      = 0x0010
	wmTimer      = 0x0113
	wmDpiChanged = 0x02E0
	wmApp        = 0x8000

	wsOverlappedWindow = 0x00CF0000
	wsClipChildren     = 0x02000000
	cwUseDefault       = ^uintptr(0x7FFFFFFF) // 0x80000000 as uintptr
	swShow             = 5
	swShowMaximized    = 3
	idcArrow           = 32512
	mbOK               = 0x00000000
	mbIconWarning      = 0x00000030
	mbIconError        = 0x00000010
	wdaExcludeCapture  = 0x00000011
	wdaMonitor         = 0x00000001
	vkControl          = 0x11
	vkShift            = 0x10
	vkMenu             = 0x12 // Alt
	imageIcon          = 1
	lrDefaultSize      = 0x00000040
	lrShared           = 0x00008000
	hwndMessage        = ^uintptr(2) // (HWND)-3
	swpNoZOrder        = 0x0004
	swpNoActivate      = 0x0010
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

type lastInputInfo struct {
	cbSize uint32
	dwTime uint32
}

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

func setTitle(hwnd uintptr, s string) { pSetWindowTextW.Call(hwnd, uintptr(unsafe.Pointer(utf16(s)))) }

func messageBox(hwnd uintptr, title, text string, flags uintptr) {
	pMessageBoxW.Call(hwnd, uintptr(unsafe.Pointer(utf16(text))), uintptr(unsafe.Pointer(utf16(title))), flags)
}

// keyDown reports whether a modifier key is held. Key presses inside a web
// page are handled by WebView2's own process, so this thread's key state can
// be out of date; the system-wide state is checked as well.
func keyDown(vk uintptr) bool {
	r, _, _ := pGetKeyState.Call(vk)
	if int16(r) < 0 {
		return true
	}
	a, _, _ := pGetAsyncKeyState.Call(vk)
	return a&0x8000 != 0
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

func idleMillis() uint64 {
	li := lastInputInfo{cbSize: uint32(unsafe.Sizeof(lastInputInfo{}))}
	r, _, _ := pGetLastInputInfo.Call(uintptr(unsafe.Pointer(&li)))
	if r == 0 {
		return 0
	}
	now, _, _ := pGetTickCount64.Call()
	// dwTime is a 32-bit tick count; compare in 32 bits to survive wrap-around.
	return uint64(uint32(now) - li.dwTime)
}

func clipboardSeq() uint32 {
	r, _, _ := pGetClipboardSequenceNumber.Call()
	return uint32(r)
}

func clearClipboard(hwnd uintptr) bool {
	r, _, _ := pOpenClipboard.Call(hwnd)
	if r == 0 {
		return false
	}
	pEmptyClipboard.Call()
	pCloseClipboard.Call()
	return true
}

// ---- running work on the UI thread ----------------------------------------

// WebView2 must only be used from the UI thread. Background goroutines call
// runOnUI; a hidden message-only window receives WM_APP and runs the queue.
var (
	uiMu       sync.Mutex
	uiQueue    []func()
	msgHwnd    uintptr
	wndProcCb  uintptr
	appClass   = "RoninBrowserWindow"
	msgClass   = "RoninBrowserMsg"
	hInstance  uintptr
	appIcon    uintptr
	darkBrush  uintptr
	lightBrush uintptr
	greenBrush uintptr
)

func runOnUI(f func()) {
	uiMu.Lock()
	uiQueue = append(uiQueue, f)
	uiMu.Unlock()
	pPostMessageW.Call(msgHwnd, wmApp, 0, 0)
}

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

func rgb(r, g, b byte) uintptr { return uintptr(r) | uintptr(g)<<8 | uintptr(b)<<16 }

func initWin32() error {
	if pSetProcessDpiAwarenessCtx.Find() == nil {
		pSetProcessDpiAwarenessCtx.Call(^uintptr(3)) // PER_MONITOR_AWARE_V2 = (DPI_AWARENESS_CONTEXT)-4
	}
	h, _, _ := pGetModuleHandleW.Call(0)
	hInstance = h
	cursor, _, _ := pLoadCursorW.Call(0, idcArrow)
	appIcon, _, _ = pLoadImageW.Call(hInstance, 2, imageIcon, 0, 0, lrDefaultSize|lrShared) // icon group resource #2 (1 is the manifest)
	darkBrush, _, _ = pCreateSolidBrush.Call(rgb(0x14, 0x17, 0x1f))
	lightBrush, _, _ = pCreateSolidBrush.Call(rgb(0xee, 0xf0, 0xf4))
	greenBrush, _, _ = pCreateSolidBrush.Call(rgb(0x16, 0xa3, 0x4a))

	wndProcCb = windows.NewCallback(wndProc)
	wc := wndClassEx{
		cbSize:        uint32(unsafe.Sizeof(wndClassEx{})),
		style:         0x0003, // CS_HREDRAW|CS_VREDRAW
		lpfnWndProc:   wndProcCb,
		hInstance:     hInstance,
		hIcon:         appIcon,
		hCursor:       cursor,
		hbrBackground: darkBrush,
		lpszClassName: utf16(appClass),
		hIconSm:       appIcon,
	}
	if r, _, err := pRegisterClassExW.Call(uintptr(unsafe.Pointer(&wc))); r == 0 {
		return err
	}
	mc := wndClassEx{
		cbSize:        uint32(unsafe.Sizeof(wndClassEx{})),
		lpfnWndProc:   wndProcCb,
		hInstance:     hInstance,
		lpszClassName: utf16(msgClass),
	}
	if r, _, err := pRegisterClassExW.Call(uintptr(unsafe.Pointer(&mc))); r == 0 {
		return err
	}
	msgHwnd, _, _ = pCreateWindowExW.Call(0, uintptr(unsafe.Pointer(utf16(msgClass))), 0, 0, 0, 0, 0, 0, hwndMessage, 0, hInstance, 0)
	return nil
}

func createAppWindow(title string) uintptr {
	hwnd, _, _ := pCreateWindowExW.Call(
		0,
		uintptr(unsafe.Pointer(utf16(appClass))),
		uintptr(unsafe.Pointer(utf16(title))),
		wsOverlappedWindow|wsClipChildren,
		cwUseDefault, cwUseDefault, cwUseDefault, cwUseDefault,
		0, 0, hInstance, 0,
	)
	return hwnd
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

func wndProc(hwnd, msg, wparam, lparam uintptr) uintptr {
	if hwnd == msgHwnd && msgHwnd != 0 {
		if msg == wmApp {
			drainUIQueue()
			return 0
		}
		if msg == wmCopyData && lparam != 0 {
			cd := (*copyData)(unsafe.Pointer(lparam))
			if cd.dwData == 0x524F4E31 && cd.cbData > 0 && cd.cbData < 8192 && cd.lpData != 0 {
				text := string(unsafe.Slice((*byte)(unsafe.Pointer(cd.lpData)), cd.cbData))
				var args []string
				for _, a := range strings.Split(text, "\n") {
					if a = strings.TrimSpace(a); a != "" && a != "--restore" && !strings.HasPrefix(a, "--wait-pid") {
						args = append(args, a)
					}
				}
				runOnUI(func() { onSecondStart(args) })
				return 1
			}
			return 0
		}
		r, _, _ := pDefWindowProcW.Call(hwnd, msg, wparam, lparam)
		return r
	}
	w := windowsByHwnd[hwnd]
	switch msg {
	case wmSize:
		if w != nil {
			w.layout()
		}
		return 0
	case wmMove:
		if w != nil {
			w.notifyMoved()
		}
	case wmDpiChanged:
		if w != nil {
			r := (*rect)(unsafe.Pointer(lparam))
			pSetWindowPos.Call(hwnd, 0, uintptr(r.Left), uintptr(r.Top), uintptr(r.Right-r.Left), uintptr(r.Bottom-r.Top), swpNoZOrder|swpNoActivate)
			w.layout()
		}
		return 0
	case wmActivate:
		if w != nil && wparam&0xFFFF != 0 {
			w.focusActive()
		}
	case wmTimer:
		if w != nil {
			w.onTimer()
		}
		return 0
	case 0x0014: // WM_ERASEBKGND: Safe Money windows get a green frame
		if w != nil {
			r := clientRect(hwnd)
			bg := darkBrush
			if themeName() == "light" {
				bg = lightBrush
			}
			pFillRect.Call(wparam, uintptr(unsafe.Pointer(&r)), bg)
			if w.container == containers.Banking {
				// only the thin frame is green (no green flash while the page loads)
				f := w.scale(4)
				top := w.scale(w.uiHeight)
				for _, e := range []rect{
					{Left: r.Left, Top: top, Right: r.Left + f, Bottom: r.Bottom},
					{Left: r.Right - f, Top: top, Right: r.Right, Bottom: r.Bottom},
					{Left: r.Left, Top: r.Bottom - f, Right: r.Right, Bottom: r.Bottom},
				} {
					e := e
					pFillRect.Call(wparam, uintptr(unsafe.Pointer(&e)), greenBrush)
				}
			}
			return 1
		}
	case wmClose:
		if w != nil {
			if w.float {
				floatCloseRequested(w)
				return 0
			}
			logf("window closed by the user or Windows (%s, %d tabs)", w.container, len(w.tabs))
			if beforeLastWindowCloses(w) {
				w.hiddenDL = true
				pShowWindow.Call(hwnd, 0) // hidden until its download ends
				return 0
			}
			w.close()
			return 0
		}
	case wmDestroy:
		if w != nil {
			delete(windowsByHwnd, hwnd)
			if len(windowsByHwnd) == 0 {
				pPostQuitMessage.Call(0)
			}
		}
		return 0
	}
	r, _, _ := pDefWindowProcW.Call(hwnd, msg, wparam, lparam)
	return r
}

// keyInput is the Windows INPUT structure for one key (40 bytes on 64-bit).
type keyInput struct {
	typ   uint32
	_     uint32
	vk    uint16
	scan  uint16
	flags uint32
	time  uint32
	_     uint32
	extra uintptr
	_     [8]byte
}

// startVoiceTyping presses Win+H: Windows' own voice typing opens and writes
// what the user says into the box that has the focus (Hindi or English,
// whatever keyboard language is chosen in Windows).
func startVoiceTyping() {
	const keyUp, vkLWin = 0x0002, 0x5B
	in := []keyInput{
		{typ: 1, vk: vkLWin}, {typ: 1, vk: 'H'},
		{typ: 1, vk: 'H', flags: keyUp}, {typ: 1, vk: vkLWin, flags: keyUp},
	}
	pSendInput.Call(uintptr(len(in)), uintptr(unsafe.Pointer(&in[0])), unsafe.Sizeof(in[0]))
}
