package safelinks

import (
	"encoding/json"
	"os"
	"testing"
)

func TestCases(t *testing.T) {
	b, err := os.ReadFile("cases.json")
	if err != nil {
		t.Fatal(err)
	}
	var cases [][2]string
	if err := json.Unmarshal(b, &cases); err != nil {
		t.Fatal(err)
	}
	for _, c := range cases {
		if got := Check(c[0]); got != c[1] {
			t.Errorf("Check(%q) = %q, want %q", c[0], got, c[1])
		}
	}
}
