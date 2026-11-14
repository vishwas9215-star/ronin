//go:build windows

package main

import (
	"bufio"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"roninbrowser/internal/fastdl"
	"roninbrowser/internal/videodl"
	"roninbrowser/internal/vpn"
)

// dlJob is one video download.
type dlJob struct {
	ID      int     `json:"id"`
	Title   string  `json:"title"`
	URL     string  `json:"url"`
	Quality string  `json:"quality"`
	Percent float64 `json:"percent"`
	State   string  `json:"state"` // preparing, downloading, done, failed, cancelled
	File    string  `json:"file,omitempty"`
	Error   string  `json:"error,omitempty"`

	Kind string `json:"kind"` // "video" or "file"
	Size string `json:"size,omitempty"`
	Scan string `json:"scan,omitempty"` // virus check: clean, virus, skipped

	Speed    string `json:"speed,omitempty"`
	ETA      string `json:"eta,omitempty"`
	Note     string `json:"note,omitempty"`
	Stalled  bool   `json:"stalled,omitempty"`
	CanPause bool   `json:"canPause,omitempty"`
	VPN      bool   `json:"vpn,omitempty"` // downloading through the VPN

	bytes, total     int64
	prevBytes        int64
	prevAt, lastMove time.Time
	rate             float64
	retries          int
	resumeNoted      bool
	fd               *fastdl.Download // Ronin's own fast downloader (nil: engine or video tool)
	srcURL, referrer string
	tab              *Tab

	cancel func()
	op     uintptr // file downloads: the engine's download (kept until it ends)
	w      *Window
	media  []string // video file addresses seen on the page (second try)
	src    string   // address the last saved file came from
}

var (
	dlMu     sync.Mutex
	dlJobs   []*dlJob
	dlNextID int
)

func videoDir() string { return filepath.Join(downloadsDir(), "Ronin Videos") }

// startVideoDownload begins a download in the background.
func startVideoDownload(w *Window, pageURL, title string, q videodl.Quality, media []string) {
	if !q.Valid() || !strings.HasPrefix(pageURL, "http") {
		return
	}
	dlMu.Lock()
	dlNextID++
	if len(title) > 90 {
		title = title[:90] + "..."
	}
	j := &dlJob{ID: dlNextID, Title: title, URL: pageURL, Quality: string(q), State: "preparing", w: w, media: media, Kind: "video"}
	dlJobs = append(dlJobs, j)
	if len(dlJobs) > 30 {
		dlJobs = dlJobs[len(dlJobs)-30:]
	}
	dlMu.Unlock()
	notifyJob(j)
	go runVideoDownload(j, q)
}

func notifyJob(j *dlJob) {
	dlMu.Lock()
	cp := *j
	dlMu.Unlock()
	runOnUI(func() {
		if dlFloat != nil && !dlFloat.closing {
			dlFloat.overlay("downloads", downloadsData(dlFloat))
		}
		w := cp.w
		if w == nil || w.closing || w.hiddenDL {
			return
		}
		if w.panelKind == "downloads" { // keep the open Downloads panel up to date
			w.overlay("downloads", downloadsData(w))
		}
		if cp.Kind == "file" {
			switch {
			case cp.State == "done" && w.panelKind != "downloads":
				w.bar(nil, "ok", "Downloaded: "+filepath.Base(cp.File),
					[]map[string]string{{"label": "Show in folder", "cmd": "dlshow", "url": fmt.Sprint(cp.ID)}})
			case cp.State == "failed":
				w.bar(nil, "error", "Download failed: "+cp.Title, nil)
			case cp.State == "blocked":
				w.bar(nil, "danger", cp.Title+": "+cp.Error, nil)
			}
			return
		}
		switch cp.State {
		case "preparing":
			w.bar(nil, "info", "Video download: "+cp.Title+" - getting ready...", nil)
		case "downloading":
			w.bar(nil, "info", fmt.Sprintf("Downloading video: %s - %.0f%%", cp.Title, cp.Percent),
				[]map[string]string{{"label": "Cancel", "cmd": "dlcancel", "url": fmt.Sprint(cp.ID)}})
		case "scanning":
			w.bar(nil, "info", "Video downloaded - checking it for viruses...", nil)
		case "blocked":
			w.bar(nil, "danger", cp.Title+": "+cp.Error, nil)
		case "done":
			w.bar(nil, "ok", "Video saved: "+filepath.Base(cp.File),
				[]map[string]string{{"label": "Show in folder", "cmd": "dlshow", "url": fmt.Sprint(cp.ID)}})
		case "failed":
			w.bar(nil, "error", "Video download failed: "+cp.Error, nil)
		case "cancelled":
			w.bar(nil, "warn", "Video download cancelled.", nil)
		}
	})
}

func setJob(j *dlJob, f func(*dlJob)) {
	dlMu.Lock()
	f(j)
	dlMu.Unlock()
}

func runVideoDownload(j *dlJob, q videodl.Quality) {
	defer func() {
		if r := recover(); r != nil {
			logf("download panic: %v", r)
		}
	}()
	status := func(s string) {
		runOnUI(func() {
			if j.w != nil && !j.w.closing {
				j.w.bar(nil, "info", s+" This happens only once.", nil)
			}
		})
	}
	for _, t := range []toolSpec{ytdlpTool, denoTool, ffmpegTool} {
		if err := ensureTool(t, status); err != nil {
			logf("tool: %v", err)
			setJob(j, func(j *dlJob) {
				j.State, j.Error = "failed", "could not get the video tools ("+t.name+"). Check the internet and try again."
			})
			notifyJob(j)
			return
		}
	}
	_ = os.MkdirAll(videoDir(), 0o755)
	// First the normal way (the downloader knows thousands of sites), then the
	// video files seen playing on the page, for sites it does not know.
	tries := [][]string{videodl.Args(j.URL, q, videoDir(), toolsDir())}
	for _, m := range j.media {
		tries = append(tries, videodl.DirectArgs(m, j.URL, j.Title, q, videoDir(), toolsDir()))
	}
	var lastErr string
	for i, args := range tries {
		if i > 0 {
			setJob(j, func(j *dlJob) { j.State, j.Percent = "downloading", 0 })
			notifyJob(j)
		}
		ok, cancelled, errLine := runYtdlp(j, args)
		if ok && !cancelled {
			// Some sites call their stream list "video.mp4": then a 1 KB list was
			// saved instead of the video. Fetch the real stream from that list.
			if next, isList := streamFromSavedList(j, q); isList {
				if next == "" {
					ok, errLine = false, "ERROR: the video is a stream list Ronin could not read"
				} else {
					ok, cancelled, errLine = runYtdlp(j, videodl.DirectArgs(next, j.URL, j.Title, q, videoDir(), toolsDir()))
					if ok {
						if _, again := streamFromSavedList(j, q); again {
							ok, errLine = false, "ERROR: the video is a stream list Ronin could not read"
						}
					}
				}
			}
		}
		if cancelled {
			setJob(j, func(j *dlJob) { j.cancel, j.State = nil, "cancelled" })
			notifyJob(j)
			return
		}
		if ok {
			setJob(j, func(j *dlJob) { j.cancel, j.State, j.Percent = nil, "scanning", 100 })
			notifyJob(j)
			scanJob(j)
			return
		}
		if errLine != "" {
			logf("yt-dlp (try %d): %s", i+1, errLine)
			if lastErr == "" {
				lastErr = errLine
			}
		}
	}
	setJob(j, func(j *dlJob) {
		j.cancel, j.State = nil, "failed"
		j.Error = videodl.FriendlyError(lastErr)
	})
	notifyJob(j)
}

// runYtdlp runs one download attempt and reports how it ended.
func runYtdlp(j *dlJob, args []string) (ok, cancelled bool, lastErr string) {
	if j.w != nil && usesVPN(j.w.container) {
		// the window uses the VPN, so the download does too (same address as the page)
		args = videodl.WithProxy(args, fmt.Sprintf("socks5h://127.0.0.1:%d", vpn.Port))
	}
	cmd := hiddenCmd(filepath.Join(toolsDir(), "yt-dlp.exe"), args...)
	out, err := cmd.StdoutPipe()
	if err != nil {
		return false, false, "could not start the downloader"
	}
	cmd.Stderr = cmd.Stdout
	if err := cmd.Start(); err != nil {
		return false, false, "could not start the downloader"
	}
	attachToJob(cmd.Process)
	var stopped atomic.Bool
	setJob(j, func(j *dlJob) {
		j.State, j.File, j.src = "downloading", "", ""
		j.cancel = func() { stopped.Store(true); _ = cmd.Process.Kill() }
	})
	notifyJob(j)

	lastShown := time.Time{}
	sc := bufio.NewScanner(io.LimitReader(out, 64<<20))
	sc.Buffer(make([]byte, 64*1024), 1<<20)
	for sc.Scan() {
		line := sc.Text()
		if p, ok := videodl.Progress(line); ok {
			setJob(j, func(j *dlJob) { j.Percent = p })
			if time.Since(lastShown) > 700*time.Millisecond {
				lastShown = time.Now()
				notifyJob(j)
			}
			continue
		}
		if f, ok := videodl.FinalFile(line); ok {
			setJob(j, func(j *dlJob) { j.File = f })
			continue
		}
		if u, ok := videodl.SourceURL(line); ok {
			setJob(j, func(j *dlJob) { j.src = u })
			continue
		}
		if strings.HasPrefix(line, "ERROR") {
			lastErr = line
		}
	}
	err = cmd.Wait()
	dlMu.Lock()
	file := j.File
	dlMu.Unlock()
	return err == nil && file != "", stopped.Load(), lastErr
}

func jobByID(id string) *dlJob {
	dlMu.Lock()
	defer dlMu.Unlock()
	for _, j := range dlJobs {
		if fmt.Sprint(j.ID) == id {
			return j
		}
	}
	return nil
}

func cancelJob(id string) {
	if j := jobByID(id); j != nil {
		dlMu.Lock()
		c := j.cancel
		dlMu.Unlock()
		if c != nil {
			c()
		}
	}
}

// showJobFile opens Explorer with the saved file selected.
func showJobFile(id string) {
	j := jobByID(id)
	if j == nil {
		return
	}
	dlMu.Lock()
	f := j.File
	dlMu.Unlock()
	target := videoDir()
	if f != "" {
		if _, err := os.Stat(f); err == nil {
			openExplorer(f, true)
			return
		}
	}
	if j.Kind == "file" {
		target = downloadsDir()
	}
	openExplorer(target, false)
}

func jobsData() []map[string]any {
	dlMu.Lock()
	defer dlMu.Unlock()
	var out []map[string]any
	for i := len(dlJobs) - 1; i >= 0; i-- {
		j := dlJobs[i]
		out = append(out, map[string]any{"id": j.ID, "title": j.Title, "state": j.State,
			"percent": j.Percent, "file": filepath.Base(j.File), "error": j.Error, "kind": j.Kind, "size": j.Size, "scan": j.Scan,
			"speed": j.Speed, "eta": j.ETA, "note": j.Note, "stalled": j.Stalled, "canPause": j.CanPause, "vpn": j.VPN})
	}
	return out
}

// openExplorer shows a folder or a selected file. Explorer needs its own
// quoting, so the command line is written by hand.
func openExplorer(path string, selectFile bool) {
	if path == "" || strings.ContainsAny(path, "\"\r\n") {
		return
	}
	arg := `"` + path + `"`
	if selectFile {
		arg = `/select,"` + path + `"`
	}
	exe := filepath.Join(os.Getenv("WINDIR"), "explorer.exe")
	cmd := exec.Command(exe)
	cmd.SysProcAttr = &syscall.SysProcAttr{CmdLine: `"` + exe + `" ` + arg}
	_ = cmd.Start()
}

// streamFromSavedList checks the file just saved. When it is a stream list
// (not a video) it is deleted, and the address of the stream to download
// instead is returned ("" if none could be found).
func streamFromSavedList(j *dlJob, q videodl.Quality) (next string, isList bool) {
	dlMu.Lock()
	file, src := j.File, j.src
	dlMu.Unlock()
	f, err := os.Open(file)
	if err != nil {
		return "", false
	}
	st, _ := f.Stat()
	head := make([]byte, 64)
	n, _ := io.ReadFull(f, head)
	if !videodl.IsPlaylist(head[:n]) || st == nil || st.Size() > 2<<20 {
		f.Close()
		return "", false
	}
	_, _ = f.Seek(0, io.SeekStart)
	text, _ := io.ReadAll(io.LimitReader(f, 2<<20))
	f.Close()
	_ = os.Remove(file)
	setJob(j, func(j *dlJob) { j.File = "" })
	next = videodl.PickStream(string(text), src, q)
	if next == "" && strings.HasPrefix(src, "http") && strings.Contains(string(text), "#EXTINF") {
		next = src // the list is itself the one stream
	}
	logf("video: saved file was a stream list; trying its stream")
	return next, true
}

// ---- normal file downloads (shown in the same Downloads panel) -------------

// startFileJob records a file the engine is downloading and opens the
// Downloads panel. op is kept (AddRef) so the download can be cancelled.
func startFileJob(w *Window, name string, op uintptr) *dlJob {
	call(op, 1) // AddRef: released when the download ends
	dlMu.Lock()
	dlNextID++
	j := &dlJob{ID: dlNextID, Title: name, State: "downloading", Percent: -1, Kind: "file", w: w, op: op, CanPause: true, lastMove: time.Now()}
	startDownloadTicker()
	j.cancel = func() {
		runOnUI(func() {
			dlMu.Lock()
			o := j.op
			if o != 0 {
				j.State = "cancelled"
			}
			dlMu.Unlock()
			if o != 0 {
				call(o, doCancel)
			}
		})
	}
	dlJobs = append(dlJobs, j)
	if len(dlJobs) > 30 {
		dlJobs = dlJobs[len(dlJobs)-30:]
	}
	dlMu.Unlock()
	runOnUI(func() {
		if w != nil && !w.closing {
			w.overlay("downloads", downloadsData(w))
		}
	})
	return j
}

func finishFileJob(j *dlJob, state, file string) {
	var op uintptr
	setJob(j, func(j *dlJob) {
		if j.State == "cancelled" {
			state = "cancelled"
		}
		j.State, op, j.op, j.cancel = state, j.op, 0, nil
		if file != "" {
			j.File = file
		}
		if state == "done" {
			j.Percent = 100
			state = "scanning"
			j.State = state
		}
		if state == "failed" && j.Error == "" {
			j.Error = "The download stopped (the site or the internet connection broke it off)."
		}
	})
	release(op)
	notifyJob(j)
	if state == "scanning" {
		go scanJob(j)
	}
}

// scanJob runs the virus check on a finished download.
func scanJob(j *dlJob) {
	dlMu.Lock()
	f := j.File
	dlMu.Unlock()
	res := scanFile(f)
	setJob(j, func(j *dlJob) {
		j.Scan = res
		if res == scanVirus {
			j.State, j.Error = "blocked", "Windows Defender found a virus in this file. Ronin removed it."
		} else {
			j.State = "done"
		}
	})
	notifyJob(j)
}

func byteSize(n int64) string {
	switch {
	case n >= 1<<30:
		return fmt.Sprintf("%.1f GB", float64(n)/(1<<30))
	case n >= 1<<20:
		return fmt.Sprintf("%.1f MB", float64(n)/(1<<20))
	case n >= 1<<10:
		return fmt.Sprintf("%.0f KB", float64(n)/(1<<10))
	}
	return fmt.Sprintf("%d bytes", n)
}
