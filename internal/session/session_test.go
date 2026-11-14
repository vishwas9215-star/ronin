package session

import (
	"os"
	"path/filepath"
	"testing"
)

func TestSaveLoad(t *testing.T) {
	p := filepath.Join(t.TempDir(), "session.json")
	in := Session{Windows: []Window{
		{Container: "TRADING", URLs: []string{"https://in.tradingview.com/chart/", "about:blank", "https://web.dhan.co/"}, Active: 5, Grid: 4},
		{Container: "BANKING", URLs: []string{"https://sbi.bank.in/"}},
		{Container: "PERSONAL", URLs: []string{"javascript:alert(1)"}},
		{Container: "PERSONAL", URLs: []string{"https://www.youtube.com/"}, Grid: 3},
	}}
	if err := Save(p, in); err != nil {
		t.Fatal(err)
	}
	got := Load(p)
	if len(got.Windows) != 2 {
		t.Fatalf("windows = %+v", got.Windows)
	}
	w := got.Windows[0]
	if w.Container != "TRADING" || len(w.URLs) != 2 || w.Active != 0 || w.Grid != 4 {
		t.Fatalf("trading window = %+v", w)
	}
	if got.Windows[1].Grid != 0 {
		t.Fatal("bad grid kept")
	}
	os.WriteFile(p, []byte("{bad"), 0o644)
	if len(Load(p).Windows) != 0 {
		t.Fatal("broken file not ignored")
	}
}
