package fastdl

import (
	"bytes"
	"crypto/rand"
	"crypto/sha256"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func data(n int) []byte {
	b := make([]byte, n)
	rand.Read(b)
	return b
}

// rangeServer serves content with Range support. breakEvery > 0 cuts every
// n-th response in the middle; slow adds a pause per response.
func rangeServer(t *testing.T, content []byte, breakEvery int32, slow time.Duration, wantCookie string) (*httptest.Server, *atomic.Int32) {
	var hits atomic.Int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		n := hits.Add(1)
		if wantCookie != "" && r.Header.Get("Cookie") != wantCookie {
			w.Header().Set("Content-Type", "text/html")
			w.WriteHeader(403)
			return
		}
		rg := r.Header.Get("Range")
		start, end := 0, len(content)-1
		if rg != "" {
			fmt.Sscanf(rg, "bytes=%d-%d", &start, &end)
			if end >= len(content) {
				end = len(content) - 1
			}
			w.Header().Set("Content-Range", fmt.Sprintf("bytes %d-%d/%d", start, end, len(content)))
			w.Header().Set("Content-Length", strconv.Itoa(end-start+1))
			w.Header().Set("Content-Type", "video/x-matroska")
			w.WriteHeader(206)
		}
		body := content[start : end+1]
		if breakEvery > 0 && n%breakEvery == 0 && len(body) > 10 {
			w.Write(body[:len(body)/2])
			if f, ok := w.(http.Flusher); ok {
				f.Flush()
			}
			hj, _ := w.(http.Hijacker)
			c, _, _ := hj.Hijack()
			c.Close()
			return
		}
		for len(body) > 0 {
			k := 256 << 10
			if k > len(body) {
				k = len(body)
			}
			w.Write(body[:k])
			body = body[k:]
			if slow > 0 {
				if f, ok := w.(http.Flusher); ok {
					f.Flush()
				}
				time.Sleep(slow)
			}
		}
	}))
	t.Cleanup(srv.Close)
	return srv, &hits
}

func wait(t *testing.T, d *Download, want string, max time.Duration) Status {
	t.Helper()
	deadline := time.Now().Add(max)
	for time.Now().Before(deadline) {
		if s := d.Status(); s.State == want {
			return s
		} else if s.State == Failed && want != Failed {
			t.Fatalf("failed: %s", s.Err)
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatalf("state %q not reached; now %+v", want, d.Status())
	return Status{}
}

func same(t *testing.T, path string, content []byte) {
	t.Helper()
	got, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if sha256.Sum256(got) != sha256.Sum256(content) {
		t.Fatalf("file differs: %d bytes, want %d", len(got), len(content))
	}
	if _, err := os.Stat(path + ".ronin-part"); err == nil {
		t.Fatal("part file left behind")
	}
}

func TestParallelWithBrokenConnections(t *testing.T) {
	content := data(23<<20 + 12345)
	srv, hits := rangeServer(t, content, 3, 0, "sid=1")
	path := filepath.Join(t.TempDir(), "movie.mkv")
	d := New(Options{URL: srv.URL + "/f", Path: path, Cookie: "sid=1"})
	d.Start()
	s := wait(t, d, Done, 60*time.Second)
	if s.Bytes != int64(len(content)) || s.Total != int64(len(content)) || !s.Ranged {
		t.Fatalf("status %+v", s)
	}
	same(t, path, content)
	if hits.Load() < 7 {
		t.Fatalf("expected several connections, got %d", hits.Load())
	}
}

func TestPauseResumeCancel(t *testing.T) {
	content := data(20 << 20)
	srv, _ := rangeServer(t, content, 0, 15*time.Millisecond, "")
	path := filepath.Join(t.TempDir(), "a.bin")
	d := New(Options{URL: srv.URL, Path: path, Conns: 4})
	d.Start()
	wait(t, d, Downloading, 5*time.Second)
	time.Sleep(150 * time.Millisecond)
	d.Pause()
	b1 := d.Status().Bytes
	if d.Status().State != Paused || b1 == 0 || b1 >= int64(len(content)) {
		t.Fatalf("pause: %+v", d.Status())
	}
	time.Sleep(200 * time.Millisecond)
	if d.Status().Bytes != b1 {
		t.Fatal("bytes moved while paused")
	}
	d.Resume()
	wait(t, d, Done, 60*time.Second)
	if d.Status().Bytes != int64(len(content)) {
		t.Fatalf("bytes counted twice or lost: %d", d.Status().Bytes)
	}
	same(t, path, content)

	path2 := filepath.Join(t.TempDir(), "b.bin")
	d2 := New(Options{URL: srv.URL, Path: path2})
	d2.Start()
	wait(t, d2, Downloading, 5*time.Second)
	d2.Cancel()
	if _, err := os.Stat(path2 + ".ronin-part"); err == nil || d2.Status().State != Cancelled {
		t.Fatal("cancel left the part file or wrong state")
	}
}

func TestNoRangeServer(t *testing.T) {
	content := data(3 << 20)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/octet-stream")
		w.Write(content) // ignores Range
	}))
	defer srv.Close()
	path := filepath.Join(t.TempDir(), "c.bin")
	d := New(Options{URL: srv.URL, Path: path})
	d.Start()
	s := wait(t, d, Done, 30*time.Second)
	if s.Ranged || s.Bytes != int64(len(content)) {
		t.Fatalf("%+v", s)
	}
	same(t, path, content)
}

func TestUnsupportedAndDeadLink(t *testing.T) {
	html := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Write([]byte("<html>please log in</html>"))
	}))
	defer html.Close()
	d := New(Options{URL: html.URL, Path: filepath.Join(t.TempDir(), "x")})
	d.Start()
	wait(t, d, Unsupported, 10*time.Second)

	content := data(9 << 20)
	var n atomic.Int32
	dead := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if n.Add(1) > 2 {
			w.WriteHeader(403) // the signed link expired
			return
		}
		var s, e int
		fmt.Sscanf(r.Header.Get("Range"), "bytes=%d-%d", &s, &e)
		if e >= len(content) {
			e = len(content) - 1
		}
		w.Header().Set("Content-Range", fmt.Sprintf("bytes %d-%d/%d", s, e, len(content)))
		w.WriteHeader(206)
		w.Write(content[s : e+1])
	}))
	defer dead.Close()
	var d2 *Download
	pathY := filepath.Join(t.TempDir(), "y")
	d2 = New(Options{URL: dead.URL, Path: pathY})
	d2.Start()
	s := wait(t, d2, NeedLink, 20*time.Second)
	if !strings.Contains(s.Err, "stopped working") || !HasPartial(pathY) || s.Bytes == 0 {
		t.Fatalf("%+v", s)
	}
	kept := s.Bytes
	// the user starts the download again on the site: a fresh link, same file.
	// Ronin continues from what is on disk instead of starting again.
	fresh, hits := rangeServer(t, content, 0, 0, "")
	d3 := New(Options{URL: fresh.URL, Path: pathY})
	d3.Start()
	wait(t, d3, Done, 30*time.Second)
	if d3.Resumed() != kept || kept == 0 {
		t.Fatalf("resumed %d, kept %d", d3.Resumed(), kept)
	}
	same(t, pathY, content)
	if HasPartial(pathY) {
		t.Fatal("state left behind")
	}
	// only the missing parts were fetched (9 MB = 3 parts; at least one was already there)
	if hits.Load() > 4 {
		t.Fatalf("downloaded again from the start: %d requests", hits.Load())
	}
	_ = bytes.MinRead
}

// Ronin is closed (or the PC restarted) in the middle: a new download of the
// same file continues from the saved progress; a different size starts fresh.
func TestResumeAfterRestart(t *testing.T) {
	content := data(20 << 20)
	srv, _ := rangeServer(t, content, 0, 15*time.Millisecond, "")
	path := filepath.Join(t.TempDir(), "r.bin")
	d := New(Options{URL: srv.URL, Path: path, Conns: 4})
	d.Start()
	wait(t, d, Downloading, 5*time.Second)
	time.Sleep(200 * time.Millisecond)
	d.Pause() // like closing Ronin: connections stop, progress is on disk
	got := d.Status().Bytes
	d2 := New(Options{URL: srv.URL, Path: path})
	d2.Start()
	wait(t, d2, Done, 60*time.Second)
	if d2.Resumed() != got || got == 0 {
		t.Fatalf("resumed %d, want %d", d2.Resumed(), got)
	}
	same(t, path, content)

	other := data(10 << 20)
	srv2, _ := rangeServer(t, other, 0, 0, "")
	path2 := filepath.Join(t.TempDir(), "s.bin")
	os.WriteFile(path2+".ronin-part", content[:5<<20], 0o644)
	os.WriteFile(path2+".ronin-state", []byte(`{"total":20971520,"chunk":4194304,"done":[4194304,0,0,0,0]}`), 0o644)
	d3 := New(Options{URL: srv2.URL, Path: path2})
	d3.Start()
	wait(t, d3, Done, 30*time.Second)
	if d3.Resumed() != 0 {
		t.Fatal("took over progress of a different file")
	}
	same(t, path2, other)
}
