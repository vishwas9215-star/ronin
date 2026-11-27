package agentcore

import (
	_ "embed"
	"encoding/json"
)

// AgentJS: the page helper (the same file as the Android app's assets/agent/agent.js; a test keeps them equal).
//
//go:embed assets/agent.js
var AgentJS string

//go:embed assets/relay.json
var relayJSON []byte

// RelayConfig: the relay's address and its public key, baked into the app.
func RelayConfig() (url, pub string) {
	var m struct{ URL, Pub string }
	json.Unmarshal(relayJSON, &m)
	return m.URL, m.Pub
}
