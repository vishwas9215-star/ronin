//go:build windows

package main

import (
	"os"
	"path/filepath"
	"time"
	"unsafe"

	"roninbrowser/internal/containers"
	"roninbrowser/internal/downloadguard"

	"golang.org/x/sys/windows"
)

// ---- save the page as PDF, or a picture of what is on screen ----------------

var (
	iidICoreWebView2_7 = windows.GUID{Data1: 0x79c24d83, Data2: 0x09a3, Data3: 0x45ae, Data4: [8]byte{0x94, 0x18, 0x48, 0x7f, 0x32, 0xa5, 0x87, 0x40}}

	shlwapi                 = windows.NewLazySystemDLL("shlwapi.dll")
	pSHCreateStreamOnFileEx = shlwapi.NewProc("SHCreateStreamOnFileEx")
)

const (
	wv7PrintToPdf     = 80
	wvCapturePreview  = 30
	stgmCreateWrite   = 0x00001000 | 0x00000001 // STGM_CREATE | STGM_WRITE
	fileAttributeNorm = 0x80
)

// pageFileName makes a safe, new file name in the Downloads folder.
func pageFileName(t *Tab, ext string) string {
	name := downloadguard.SanitizeName(t.displayTitle())
	if name == "" || name == "." {
		name = "Page"
	}
	if r := []rune(name); len(r) > 80 {
		name = string(r[:80])
	}
	dir := downloadsDir()
	stamp := time.Now().Format("2006-01-02 15.04.05")
	return filepath.Join(dir, name+" "+stamp+ext)
}

// savePageFile records a saved page in the Downloads panel.
func savePageFile(w *Window, path string, ok bool) {
	dlMu.Lock()
	dlNextID++
	j := &dlJob{ID: dlNextID, Title: filepath.Base(path), Kind: "file", w: w, File: path, Percent: 100, State: "done"}
	if !ok {
		j.State, j.Error = "failed", "The page could not be saved."
	}
	dlJobs = append(dlJobs, j)
	dlMu.Unlock()
	w.overlay("downloads", map[string]any{"jobs": jobsData(), "folder": videoDir()})
}

func (t *Tab) savePDF() {
	if t.destroyed || t.isNewTab || t.w.container == containers.Banking {
		return
	}
	w7 := queryInterface(uintptr(t.wv), &iidICoreWebView2_7)
	if w7 == 0 {
		t.w.bar(t, "error", "Saving as PDF needs a newer browser engine. Ronin updates it automatically; try again later.", nil)
		return
	}
	defer release(w7)
	path := pageFileName(t, ".pdf")
	p, err := windows.UTF16PtrFromString(path)
	if err != nil {
		return
	}
	w := t.w
	t.w.bar(t, "info", "Saving this page as PDF...", nil)
	h := newHandler(func(errCode, success uintptr) uintptr {
		ok := uint32(errCode) == 0 && uint32(success) != 0
		runOnUI(func() {
			if !t.destroyed {
				w.bar(t, "", "", nil) // remove "Saving..."
			}
			savePageFile(w, path, ok)
		})
		return sOK
	})
	call(w7, wv7PrintToPdf, uintptr(unsafe.Pointer(p)), 0, uintptr(unsafe.Pointer(h)))
}

func (t *Tab) screenshot() {
	if t.destroyed || t.w.container == containers.Banking {
		return
	}
	path := pageFileName(t, ".png")
	p, err := windows.UTF16PtrFromString(path)
	if err != nil {
		return
	}
	var stream uintptr
	r, _, _ := pSHCreateStreamOnFileEx.Call(uintptr(unsafe.Pointer(p)), stgmCreateWrite, fileAttributeNorm, 1, 0, uintptr(unsafe.Pointer(&stream)))
	if r != 0 || stream == 0 {
		t.w.bar(t, "error", "The picture could not be saved.", nil)
		return
	}
	w := t.w
	h := newHandler(func(errCode, _ uintptr) uintptr {
		release(stream) // closes the file
		ok := uint32(errCode) == 0
		if st, err := os.Stat(path); err != nil || st.Size() == 0 {
			ok = false
			_ = os.Remove(path)
		}
		runOnUI(func() { savePageFile(w, path, ok) })
		return sOK
	})
	if failed(call(uintptr(t.wv), wvCapturePreview, 0, stream, uintptr(unsafe.Pointer(h)))) {
		release(stream)
		_ = os.Remove(path)
	}
}
