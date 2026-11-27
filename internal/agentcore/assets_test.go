package agentcore

import (
	"os"
	"strings"
	"testing"
)

func TestSameHelperAndRelayAsAndroid(t *testing.T) {
	for _, f := range []string{"agent.js", "relay.json"} {
		a, err1 := os.ReadFile("assets/" + f)
		b, err2 := os.ReadFile("../../android/app/src/main/assets/agent/" + f)
		if err1 != nil || err2 != nil || string(a) != string(b) {
			t.Fatalf("%s differs from the Android copy: copy it again", f)
		}
	}
	u, pub := RelayConfig()
	if !strings.HasPrefix(u, "https://") || !strings.Contains(pub, "PUBLIC KEY") {
		t.Fatal(u)
	}
}
