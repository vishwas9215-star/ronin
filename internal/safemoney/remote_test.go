package safemoney

import (
	"reflect"
	"testing"
)

func TestRemoteToolsRunning(t *testing.T) {
	got := RemoteToolsRunning([]string{"explorer.exe", "AnyDesk.exe", `C:\Program Files\TeamViewer\TeamViewer.exe`, "anydesk.exe", "QuickAssist.exe", ""})
	want := []string{"AnyDesk", "TeamViewer", "Windows Quick Assist"}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("got %v want %v", got, want)
	}
	if n := RemoteToolsRunning([]string{"chrome.exe", "svchost.exe"}); len(n) != 0 {
		t.Fatalf("false alarm: %v", n)
	}
}

func TestAntivirusEnabled(t *testing.T) {
	// 397568 = 0x061100 (Defender on), 393472 = 0x060100 (off), 266240 = 0x041000 (on)
	if on, known := AntivirusEnabled([]uint32{397568}); !on || !known {
		t.Fatal("defender on not detected")
	}
	if on, known := AntivirusEnabled([]uint32{393472}); on || !known {
		t.Fatal("off reported as on")
	}
	if on, _ := AntivirusEnabled([]uint32{393472, 266240}); !on {
		t.Fatal("second product on not detected")
	}
	if _, known := AntivirusEnabled(nil); known {
		t.Fatal("empty list must be unknown")
	}
}
