package agentcore

import (
	"path/filepath"
	"testing"
)

// A "Disconnect Claude" from a device this one does not know yet is still obeyed (as a revocation only).
func TestUnknownSignerDisconnectIsObeyed(t *testing.T) {
	l := &Link{Name: "laptop", Store: &Store{Path: filepath.Join(t.TempDir(), "s.json")}}
	if l.applyEpoch("phone", 1795800000001, "AAAA") {
		t.Fatal("reported as unknown")
	}
	if l.epoch() != 1795800000001 {
		t.Fatal("old grants must die", l.epoch())
	}
	if l.GrantProblem(map[string]any{"dev": "laptop", "scope": "laptop", "epoch": 0.0}) == "" {
		t.Fatal("an older grant is refused")
	}
}
