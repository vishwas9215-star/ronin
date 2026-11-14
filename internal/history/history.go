// Package history keeps the list of visited pages (normal windows only).
// It lives in history.json in the Ronin data folder, at most 20,000 pages,
// nothing older than 90 days.
package history

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

const (
	MaxEntries = 20000
	MaxAge     = 90 * 24 * time.Hour
)

// Entry is one visited page.
type Entry struct {
	Title string `json:"t"`
	URL   string `json:"u"`
	When  int64  `json:"w"` // unix seconds
}

// Store is the history file. Pages are kept oldest first; a page visited
// again is simply added once more and the older copy is dropped when the
// list is tidied (so adding a visit is always quick).
type Store struct {
	mu    sync.Mutex
	path  string
	list  []Entry // oldest first, may hold older copies of a page
	dirty bool
	now   func() time.Time
}

// Open reads dir/history.json (missing or broken = empty).
func Open(dir string) *Store {
	s := &Store{path: filepath.Join(dir, "history.json"), now: time.Now}
	if b, err := os.ReadFile(s.path); err == nil {
		_ = json.Unmarshal(b, &s.list)
	}
	s.tidy()
	return s
}

func okURL(u string) bool {
	return (strings.HasPrefix(u, "https://") || strings.HasPrefix(u, "http://")) && len(u) < 4096
}

// Skip, when set, keeps pages out of the history (adult and gambling sites).
var Skip func(url string) bool

// Add records a visit (the same page again moves to the top).
func (s *Store) Add(title, u string) {
	if !okURL(u) || Skip != nil && Skip(u) {
		return
	}
	title = strings.TrimSpace(title)
	if r := []rune(title); len(r) > 150 {
		title = string(r[:150])
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if title == "" {
		for i := len(s.list) - 1; i >= 0 && i >= len(s.list)-500; i-- {
			if s.list[i].URL == u {
				title = s.list[i].Title
				break
			}
		}
	}
	s.list = append(s.list, Entry{Title: title, URL: u, When: s.now().Unix()})
	if len(s.list) > MaxEntries+2000 {
		s.tidy()
	}
	s.dirty = true
}

// SetTitle gives the newest entry for u its page title (titles arrive late).
func (s *Store) SetTitle(u, title string) {
	title = strings.TrimSpace(title)
	if title == "" {
		return
	}
	if r := []rune(title); len(r) > 150 {
		title = string(r[:150])
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	for i := len(s.list) - 1; i >= 0; i-- {
		if s.list[i].URL == u {
			if s.list[i].Title != title {
				s.list[i].Title = title
				s.dirty = true
			}
			return
		}
	}
}

// tidy drops older copies of a page, pages older than 90 days and the
// oldest pages beyond MaxEntries. The caller holds the lock.
func (s *Store) tidy() {
	cut := s.now().Add(-MaxAge).Unix()
	seen := make(map[string]bool, len(s.list))
	keep := make([]Entry, 0, len(s.list))
	for i := len(s.list) - 1; i >= 0 && len(keep) < MaxEntries; i-- {
		e := s.list[i]
		if e.When < cut || seen[e.URL] || !okURL(e.URL) {
			continue
		}
		seen[e.URL] = true
		keep = append(keep, e)
	}
	for i, j := 0, len(keep)-1; i < j; i, j = i+1, j-1 {
		keep[i], keep[j] = keep[j], keep[i]
	}
	if len(keep) != len(s.list) {
		s.dirty = true
	}
	s.list = keep
}

// Search returns up to max pages whose title or address contains every
// word of q (all pages for an empty q), newest first.
func (s *Store) Search(q string, max int) []Entry {
	words := strings.Fields(strings.ToLower(q))
	s.mu.Lock()
	defer s.mu.Unlock()
	cut := s.now().Add(-MaxAge).Unix()
	seen := map[string]bool{}
	var out []Entry
	for i := len(s.list) - 1; i >= 0 && len(out) < max; i-- {
		e := s.list[i]
		if seen[e.URL] || e.When < cut {
			continue
		}
		seen[e.URL] = true
		hay := strings.ToLower(e.Title + " " + e.URL)
		ok := true
		for _, w := range words {
			if !strings.Contains(hay, w) {
				ok = false
				break
			}
		}
		if ok {
			out = append(out, e)
		}
	}
	return out
}

// RemoveWhere deletes every visit whose address matches and tells how many went.
func (s *Store) RemoveWhere(match func(url string) bool) int {
	s.mu.Lock()
	defer s.mu.Unlock()
	keep := s.list[:0]
	for _, e := range s.list {
		if !match(e.URL) {
			keep = append(keep, e)
		}
	}
	n := len(s.list) - len(keep)
	if n > 0 {
		s.dirty = true
	}
	s.list = keep
	return n
}

// Remove deletes one page (every visit of it) from the history.
func (s *Store) Remove(u string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	keep := s.list[:0]
	for _, e := range s.list {
		if e.URL != u {
			keep = append(keep, e)
		}
	}
	if len(keep) != len(s.list) {
		s.dirty = true
	}
	s.list = keep
}

// Clear deletes every visit since the given time (zero = all) and saves.
func (s *Store) Clear(since time.Time) error {
	s.mu.Lock()
	keep := s.list[:0]
	if !since.IsZero() {
		for _, e := range s.list {
			if e.When < since.Unix() {
				keep = append(keep, e)
			}
		}
	}
	s.list = keep
	s.dirty = true
	s.mu.Unlock()
	return s.Save()
}

// Len is the number of different pages kept.
func (s *Store) Len() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.tidy()
	return len(s.list)
}

// Save writes the file if something changed (safely: temp file + rename).
func (s *Store) Save() error {
	s.mu.Lock()
	if !s.dirty {
		s.mu.Unlock()
		return nil
	}
	s.tidy()
	b, err := json.Marshal(s.list)
	s.dirty = false
	s.mu.Unlock()
	if err != nil {
		return err
	}
	tmp := s.path + ".tmp"
	if err := os.WriteFile(tmp, b, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, s.path)
}
