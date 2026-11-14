// Package fastdl downloads one file over several connections at once, can be
// paused and resumed, and keeps trying when the connection breaks. It does
// not depend on a browser window, so a download goes on after the window
// that started it is closed.
package fastdl

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// Options describe one download.
type Options struct {
	URL       string
	Path      string // final file; "<Path>.ronin-part" is used while downloading
	UserAgent string
	Referer   string
	Cookie    string
	Proxy     string // "" = direct, or e.g. "socks5://127.0.0.1:40877"
	Conns     int    // connections at once (default 8)
}

// States.
const (
	Probing     = "probing"
	Downloading = "downloading"
	Paused      = "paused"
	Done        = "done"
	Failed      = "failed"
	Cancelled   = "cancelled"
	Unsupported = "unsupported" // the server did not give the file to a plain request
	// NeedLink: the download link stopped working (many sites give links that
	// live only a few hours). Everything received is kept on disk; a new
	// download of the same file continues from there.
	NeedLink = "needlink"
)

const linkDead = "the download link stopped working"

const (
	chunkSize   = 4 << 20
	idleTimeout = 25 * time.Second
)

type chunk struct {
	start, end int64 // end is exclusive
	done       int64 // bytes written (atomic)
	busy       bool
}

// Download is one running download.
type Download struct {
	opt Options

	mu      sync.Mutex
	state   string
	err     string
	total   int64 // -1 = unknown
	ranged  bool
	chunks  []*chunk
	cancel  context.CancelFunc
	wg      sync.WaitGroup
	file    *os.File
	lastErr string
	resumed int64

	bytes        atomic.Int64
	lastProgress atomic.Int64 // unix nano of the last received byte
	client       *http.Client
}

// Status is a snapshot for the user interface.
type Status struct {
	State   string
	Err     string
	Bytes   int64
	Total   int64 // -1 = unknown
	Stalled bool  // downloading, but nothing arrived for a while (Ronin keeps trying)
	Problem string
	Ranged  bool
}

// New prepares a download; call Start.
func New(opt Options) *Download {
	if opt.Conns <= 0 {
		opt.Conns = 8
	}
	d := &Download{opt: opt, state: Probing, total: -1}
	d.client = newClient(opt.Proxy)
	return d
}

func newClient(proxy string) *http.Client {
	tr := &http.Transport{
		Proxy:                 nil,
		MaxIdleConnsPerHost:   32,
		MaxConnsPerHost:       0,
		ResponseHeaderTimeout: 30 * time.Second,
		TLSHandshakeTimeout:   20 * time.Second,
		DisableCompression:    true, // the file must arrive byte for byte
		ForceAttemptHTTP2:     false,
	}
	if proxy != "" {
		if u, err := url.Parse(proxy); err == nil {
			tr.Proxy = http.ProxyURL(u)
		}
	}
	return &http.Client{Transport: tr}
}

func (d *Download) partPath() string  { return d.opt.Path + ".ronin-part" }
func (d *Download) statePath() string { return d.opt.Path + ".ronin-state" }

// savedState is kept next to the unfinished file, so a download can go on
// after a dead link, after Ronin was closed, or after the PC was restarted.
type savedState struct {
	Total int64   `json:"total"`
	Chunk int64   `json:"chunk"`
	Done  []int64 `json:"done"`
}

func (d *Download) saveState() {
	d.mu.Lock()
	if !d.ranged || len(d.chunks) == 0 {
		d.mu.Unlock()
		return
	}
	st := savedState{Total: d.total, Chunk: chunkSize}
	for _, c := range d.chunks {
		st.Done = append(st.Done, atomic.LoadInt64(&c.done))
	}
	f := d.file
	d.mu.Unlock()
	if f != nil {
		_ = f.Sync()
	}
	if b, err := json.Marshal(st); err == nil {
		tmp := d.statePath() + ".tmp"
		if os.WriteFile(tmp, b, 0o644) == nil {
			_ = os.Rename(tmp, d.statePath())
		}
	}
}

// loadState continues an earlier unfinished download of the same file
// (same name and same size). Called with d.mu held, after the chunks exist.
func (d *Download) loadState() {
	b, err := os.ReadFile(d.statePath())
	if err != nil {
		return
	}
	var st savedState
	fi, ferr := os.Stat(d.partPath())
	if json.Unmarshal(b, &st) != nil || ferr != nil || st.Total != d.total || st.Chunk != chunkSize || len(st.Done) != len(d.chunks) {
		return
	}
	var sum int64
	for i, c := range d.chunks {
		n := st.Done[i]
		if n < 0 || n > c.end-c.start || c.start+n > fi.Size() {
			n = 0 // never trust more than is really in the file
		}
		atomic.StoreInt64(&c.done, n)
		sum += n
	}
	d.bytes.Store(sum)
	d.resumed = sum
}

// Resumed is how many bytes were taken over from an earlier, unfinished download.
func (d *Download) Resumed() int64 {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.resumed
}

// HasPartial reports whether an unfinished download of path is waiting on disk.
func HasPartial(path string) bool {
	_, e1 := os.Stat(path + ".ronin-part")
	_, e2 := os.Stat(path + ".ronin-state")
	return e1 == nil && e2 == nil
}

// Status returns where the download stands.
func (d *Download) Status() Status {
	d.mu.Lock()
	defer d.mu.Unlock()
	s := Status{State: d.state, Err: d.err, Bytes: d.bytes.Load(), Total: d.total, Ranged: d.ranged, Problem: d.lastErr}
	if d.state == Downloading {
		if last := d.lastProgress.Load(); last != 0 && time.Since(time.Unix(0, last)) > 8*time.Second {
			s.Stalled = true
		}
	}
	return s
}

func (d *Download) setState(state, errText string) {
	d.mu.Lock()
	d.state, d.err = state, errText
	d.mu.Unlock()
}

func (d *Download) request(ctx context.Context, rangeHdr string) (*http.Response, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, d.opt.URL, nil)
	if err != nil {
		return nil, err
	}
	if d.opt.UserAgent != "" {
		req.Header.Set("User-Agent", d.opt.UserAgent)
	}
	if d.opt.Referer != "" {
		req.Header.Set("Referer", d.opt.Referer)
	}
	if d.opt.Cookie != "" {
		req.Header.Set("Cookie", d.opt.Cookie)
	}
	req.Header.Set("Accept", "*/*")
	if rangeHdr != "" {
		req.Header.Set("Range", rangeHdr)
	}
	d.mu.Lock()
	c := d.client
	d.mu.Unlock()
	return c.Do(req)
}

// Start begins (or, after Pause, continues) the download.
func (d *Download) Start() {
	d.mu.Lock()
	if d.state == Downloading || d.state == Done || d.state == Cancelled {
		d.mu.Unlock()
		return
	}
	ctx, cancel := context.WithCancel(context.Background())
	d.cancel = cancel
	first := d.chunks == nil
	if !first {
		d.state = Downloading
	}
	d.mu.Unlock()
	d.lastProgress.Store(time.Now().UnixNano())
	d.wg.Add(1)
	go d.run(ctx, first)
}

// Resume continues a paused download.
func (d *Download) Resume() { d.Start() }

// Pause stops the connections; what was received is kept.
func (d *Download) Pause() {
	d.mu.Lock()
	if d.state != Downloading && d.state != Probing {
		d.mu.Unlock()
		return
	}
	d.state = Paused
	cancel := d.cancel
	d.mu.Unlock()
	if cancel != nil {
		cancel()
	}
	d.wg.Wait()
	d.saveState()
}

// Cancel stops the download and removes the unfinished file.
func (d *Download) Cancel() {
	d.mu.Lock()
	if d.state == Done || d.state == Cancelled {
		d.mu.Unlock()
		return
	}
	d.state = Cancelled
	cancel := d.cancel
	d.mu.Unlock()
	if cancel != nil {
		cancel()
	}
	d.wg.Wait()
	d.closeFile()
	_ = os.Remove(d.partPath())
	_ = os.Remove(d.statePath())
}

// SetProxy switches between VPN and direct while downloading. With a server
// that supports resuming nothing is lost.
func (d *Download) SetProxy(proxy string) {
	st := d.Status().State
	if st == Downloading {
		d.Pause()
	}
	d.mu.Lock()
	d.opt.Proxy = proxy
	d.client = newClient(proxy)
	d.mu.Unlock()
	if st == Downloading {
		d.Start()
	}
}

func (d *Download) closeFile() {
	d.mu.Lock()
	if d.file != nil {
		_ = d.file.Close()
		d.file = nil
	}
	d.mu.Unlock()
}

func (d *Download) run(ctx context.Context, first bool) {
	defer d.wg.Done()
	if first {
		if err := d.probe(ctx); err != nil {
			if ctx.Err() != nil {
				return // paused or cancelled
			}
			var un unsupportedError
			if errors.As(err, &un) {
				d.setState(Unsupported, err.Error())
			} else {
				d.setState(Failed, err.Error())
			}
			return
		}
		if ctx.Err() != nil {
			return
		}
		d.setState(Downloading, "")
	}
	d.mu.Lock()
	if d.file == nil {
		f, err := os.OpenFile(d.partPath(), os.O_CREATE|os.O_RDWR, 0o644)
		if err != nil {
			d.state, d.err = Failed, "the file could not be created in the Downloads folder"
			d.mu.Unlock()
			return
		}
		d.file = f
	}
	ranged, n := d.ranged, d.opt.Conns
	d.mu.Unlock()
	if !ranged {
		n = 1
	}
	fatal := make(chan string, n)
	saver := make(chan struct{})
	go func() { // remember the progress every few seconds
		t := time.NewTicker(3 * time.Second)
		defer t.Stop()
		for {
			select {
			case <-saver:
				return
			case <-t.C:
				d.saveState()
			}
		}
	}()
	defer close(saver)
	var workers sync.WaitGroup
	for i := 0; i < n; i++ {
		workers.Add(1)
		go func() {
			defer workers.Done()
			if msg := d.worker(ctx, ranged); msg != "" {
				select {
				case fatal <- msg:
				default:
				}
			}
		}()
	}
	workers.Wait()
	if ctx.Err() != nil {
		return // paused or cancelled: keep what we have
	}
	select {
	case msg := <-fatal:
		d.saveState()
		d.closeFile()
		if ranged && strings.Contains(msg, linkDead) {
			d.setState(NeedLink, msg) // what we have stays on disk
		} else {
			d.setState(Failed, msg)
		}
		return
	default:
	}
	// everything arrived
	d.closeFile()
	if err := os.Rename(d.partPath(), d.opt.Path); err != nil {
		d.setState(Failed, "the finished file could not be saved")
		return
	}
	_ = os.Remove(d.statePath())
	d.setState(Done, "")
}

type unsupportedError struct{ msg string }

func (e unsupportedError) Error() string { return e.msg }

// probe asks for the first byte to learn the size and whether the server
// can give parts of the file.
func (d *Download) probe(ctx context.Context) error {
	var resp *http.Response
	var err error
	for try := 0; try < 3; try++ {
		resp, err = d.request(ctx, "bytes=0-0")
		if err == nil || ctx.Err() != nil {
			break
		}
		time.Sleep(time.Duration(try+1) * time.Second)
	}
	if err != nil {
		return errors.New("the server did not answer")
	}
	defer resp.Body.Close()
	ct := strings.ToLower(resp.Header.Get("Content-Type"))
	if resp.StatusCode >= 400 || strings.HasPrefix(ct, "text/html") {
		return unsupportedError{fmt.Sprintf("server answered %d (%s)", resp.StatusCode, ct)}
	}
	d.mu.Lock()
	defer d.mu.Unlock()
	if resp.StatusCode == http.StatusPartialContent {
		// Content-Range: bytes 0-0/12345
		cr := resp.Header.Get("Content-Range")
		if i := strings.LastIndexByte(cr, '/'); i >= 0 {
			if t, err := strconv.ParseInt(strings.TrimSpace(cr[i+1:]), 10, 64); err == nil && t > 0 {
				d.total, d.ranged = t, true
				for s := int64(0); s < t; s += chunkSize {
					e := s + chunkSize
					if e > t {
						e = t
					}
					d.chunks = append(d.chunks, &chunk{start: s, end: e})
				}
				d.loadState()
				return nil
			}
		}
	}
	// no parts: one connection from the start
	d.ranged = false
	d.total = -1
	if resp.StatusCode == http.StatusOK && resp.ContentLength > 0 {
		d.total = resp.ContentLength
	}
	d.chunks = []*chunk{{start: 0, end: -1}}
	return nil
}

// nextChunk hands out an unfinished part.
func (d *Download) nextChunk() *chunk {
	d.mu.Lock()
	defer d.mu.Unlock()
	for _, c := range d.chunks {
		if !c.busy && (c.end < 0 || atomic.LoadInt64(&c.done) < c.end-c.start) {
			if c.end < 0 && atomic.LoadInt64(&c.done) == -1 {
				continue // single stream finished
			}
			c.busy = true
			return c
		}
	}
	return nil
}

// worker downloads parts until none is left. It returns a message only for
// a problem that trying again cannot fix.
func (d *Download) worker(ctx context.Context, ranged bool) string {
	fails := 0
	for {
		c := d.nextChunk()
		if c == nil {
			return ""
		}
		err, permanent := d.fetch(ctx, c, ranged)
		d.mu.Lock()
		c.busy = false
		if err != nil {
			d.lastErr = err.Error()
		}
		d.mu.Unlock()
		if ctx.Err() != nil {
			return ""
		}
		if err == nil {
			fails = 0
			continue
		}
		if permanent {
			return err.Error()
		}
		fails++
		if !ranged && fails >= 6 {
			return "the connection broke too often, and this server cannot continue a broken download"
		}
		wait := time.Duration(fails) * 2 * time.Second
		if wait > 20*time.Second {
			wait = 20 * time.Second
		}
		select {
		case <-ctx.Done():
			return ""
		case <-time.After(wait):
		}
	}
}

// fetch downloads the rest of one part.
func (d *Download) fetch(ctx context.Context, c *chunk, ranged bool) (err error, permanent bool) {
	cctx, cancel := context.WithCancel(ctx)
	defer cancel()
	idle := time.AfterFunc(idleTimeout, cancel) // nothing for a while: drop the connection and try again
	defer idle.Stop()

	var off int64
	rangeHdr := ""
	if ranged {
		off = c.start + atomic.LoadInt64(&c.done)
		rangeHdr = fmt.Sprintf("bytes=%d-%d", off, c.end-1)
	} else {
		// a server without parts starts again from the beginning
		d.bytes.Add(-atomic.SwapInt64(&c.done, 0))
	}
	resp, err := d.request(cctx, rangeHdr)
	if err != nil {
		return errors.New("connection problem"), false
	}
	defer resp.Body.Close()
	switch {
	case ranged && resp.StatusCode != http.StatusPartialContent:
		if resp.StatusCode == 403 || resp.StatusCode == 404 || resp.StatusCode == 410 {
			return fmt.Errorf(linkDead+" (server answered %d)", resp.StatusCode), true
		}
		return fmt.Errorf("server answered %d", resp.StatusCode), false
	case !ranged && resp.StatusCode != http.StatusOK:
		if resp.StatusCode == 403 || resp.StatusCode == 404 || resp.StatusCode == 410 {
			return fmt.Errorf(linkDead+" (server answered %d)", resp.StatusCode), true
		}
		return fmt.Errorf("server answered %d", resp.StatusCode), false
	}
	buf := make([]byte, 128<<10)
	for {
		n, rerr := resp.Body.Read(buf)
		if n > 0 {
			idle.Reset(idleTimeout)
			if ranged {
				if left := c.end - off; int64(n) > left {
					n = int(left)
				}
			}
			d.mu.Lock()
			f := d.file
			d.mu.Unlock()
			if f == nil {
				return errors.New("file closed"), false
			}
			if _, werr := f.WriteAt(buf[:n], off); werr != nil {
				return errors.New("the file could not be written (is the disk full?)"), true
			}
			off += int64(n)
			atomic.AddInt64(&c.done, int64(n))
			d.bytes.Add(int64(n))
			d.lastProgress.Store(time.Now().UnixNano())
			if ranged && off >= c.end {
				return nil, false
			}
		}
		if rerr == io.EOF {
			if ranged {
				return errors.New("connection ended early"), false
			}
			d.mu.Lock()
			total := d.total
			d.mu.Unlock()
			if total > 0 && off < total {
				return errors.New("connection ended early"), false
			}
			d.mu.Lock()
			d.total = off
			d.mu.Unlock()
			atomic.StoreInt64(&c.done, -1) // finished
			return nil, false
		}
		if rerr != nil {
			return errors.New("connection problem"), false
		}
	}
}
