// Package session saves the open tabs of normal and trading windows so they
// come back the next time Ronin starts. Safe Money and Private windows are
// never saved.
package session

import (
	"encoding/json"
	"os"
	"strings"
)

// Window is one saved window.
type Window struct {
	Container string   `json:"container"`
	URLs      []string `json:"urls"`
	Active    int      `json:"active"`
	Grid      int      `json:"grid,omitempty"`
}

// Session is the whole file.
type Session struct {
	Windows []Window `json:"windows"`
}

const maxTabs = 40

// Clean keeps only windows and addresses that are safe to reopen.
func Clean(s Session) Session {
	var out Session
	for _, w := range s.Windows {
		if w.Container != "PERSONAL" && w.Container != "TRADING" {
			continue
		}
		var urls []string
		for _, u := range w.URLs {
			if (strings.HasPrefix(u, "https://") || strings.HasPrefix(u, "http://")) && len(u) < 4096 {
				urls = append(urls, u)
			}
			if len(urls) == maxTabs {
				break
			}
		}
		if len(urls) == 0 {
			continue
		}
		if w.Active < 0 || w.Active >= len(urls) {
			w.Active = 0
		}
		if w.Grid != 2 && w.Grid != 4 {
			w.Grid = 0
		}
		w.URLs = urls
		out.Windows = append(out.Windows, w)
		if len(out.Windows) == 8 {
			break
		}
	}
	return out
}

// Load reads the file; any problem gives an empty session.
func Load(path string) Session {
	b, err := os.ReadFile(path)
	if err != nil {
		return Session{}
	}
	var s Session
	if json.Unmarshal(b, &s) != nil {
		return Session{}
	}
	return Clean(s)
}

// Save writes the file atomically.
func Save(path string, s Session) error {
	b, err := json.MarshalIndent(Clean(s), "", " ")
	if err != nil {
		return err
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, b, 0o644); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}
