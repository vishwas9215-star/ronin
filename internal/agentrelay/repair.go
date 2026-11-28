package agentrelay

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"net/http"
	"regexp"
	"sort"
	"time"
)

// Re-pair after a format: the phone's Keystore key is gone, so the new install has a new key. Its name can never be
// taken over by a stranger, so the new device waits with a random 6-digit code shown on its own screen, and the owner
// allows it on ANOTHER of his paired devices (it shows the same code; press and hold). That device signs
//   handover|<name>|sha(new key)|sha(old key)|<code>|<time ms>
// and every device that knows the signer takes the new key for that name (no server step needed).

var reRepairCode = regexp.MustCompile(`^[0-9]{6}$`)

type repairReq struct {
	Name, KeyHash, Pub, Code string
	Exp                      time.Time
}

// repairList: the waiting re-pairs another device may allow (never its own name).
func (r *Relay) repairList(dev string) []map[string]any {
	r.mu.Lock()
	defer r.mu.Unlock()
	out := []map[string]any{}
	for n, q := range r.repairs {
		if r.cfg.Now().After(q.Exp) {
			delete(r.repairs, n)
			continue
		}
		if n == dev {
			continue
		}
		old := ""
		for _, d := range r.devices {
			if d.Name == n {
				old = d.Pub
			}
		}
		out = append(out, map[string]any{"name": n, "pub": q.Pub, "code": q.Code, "old_pub": old})
	}
	sort.Slice(out, func(i, j int) bool { return fmt.Sprint(out[i]["name"]) < fmt.Sprint(out[j]["name"]) })
	return out
}

// approveRepair: the owner allowed a waiting re-pair on device `by`. Everything is checked against what the relay
// holds: the waiting key and code, the old key, a fresh time, and the signature of the approving device.
func (r *Relay) approveRepair(by string, a map[string]any) string {
	name, _ := a["name"].(string)
	newH, _ := a["new"].(string)
	oldH, _ := a["old"].(string)
	code, _ := a["code"].(string)
	sig, _ := a["sig"].(string)
	ts := num(a["ts"])
	r.mu.Lock()
	defer r.mu.Unlock()
	now := r.cfg.Now()
	q := r.repairs[name]
	switch {
	case name == "" || name == by:
		return "a device can not hand over its own name"
	case q == nil || now.After(q.Exp):
		return "no new device is waiting"
	case hexSha(q.Pub) != newH || q.Code != code:
		return "the waiting device changed: look at the code again"
	case ts < now.Add(-10*time.Minute).UnixMilli() || ts > now.Add(2*time.Minute).UnixMilli():
		return "old approval"
	}
	var approver, old string
	var oldHash string
	for h, d := range r.devices {
		if d.Name == by {
			approver = d.Pub
		}
		if d.Name == name {
			old, oldHash = d.Pub, h
		}
	}
	if hexSha(old) != oldH { // a new name: old is "" on both sides
		return "the old key is not the one the relay has"
	}
	text := fmt.Sprintf("handover|%s|%s|%s|%s|%d", name, newH, oldH, code, ts)
	if !verifySig(approver, text, sig) {
		return "the approval is not signed by the device"
	}
	if oldHash != "" {
		delete(r.devices, oldHash)
	}
	if s := r.byDev[name]; s != nil { // the old key's session ends now
		delete(r.sessions, s.id)
		delete(r.byDev, name)
	}
	delete(r.connect, name)
	r.devices[q.KeyHash] = &device{Name: name, KeyHash: q.KeyHash, Pub: q.Pub}
	delete(r.repairs, name)
	r.handover[name] = map[string]any{"by": by, "name": name, "new": newH, "old": oldH, "code": code, "ts": ts, "sig": sig, "new_pub": q.Pub, "old_pub": old}
	r.save()
	r.note(name, "re-paired: allowed by the owner on "+by)
	return ""
}

func (r *Relay) handoversLocked() []map[string]any {
	out := []map[string]any{}
	for _, h := range r.handover {
		out = append(out, h)
	}
	sort.Slice(out, func(i, j int) bool { return fmt.Sprint(out[i]["name"]) < fmt.Sprint(out[j]["name"]) })
	return out
}

// hexSha: the key hashes inside a handover (the same on the relay, Android and the laptop).
func hexSha(s string) string {
	h := sha256.Sum256([]byte(s))
	return hex.EncodeToString(h[:])
}

// getPage: the owner's other phones download the two apps from here (Hindi, two big buttons). The files are the
// public test builds of the release "agent-lab"; nothing secret is in them.
func (r *Relay) getPage(w http.ResponseWriter, _ *http.Request) {
	const base = "https://github.com/vishwas9215-star/ronin/releases/download/agent-lab/"
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	fmt.Fprint(w, `<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Ronin: दूसरा phone</title><body style="font:20px sans-serif;padding:24px;max-width:560px;margin:auto">
<h2>दूसरा phone जोड़ें</h2>
<p>1. नीचे के दोनों app लगाएँ (Android पूछे तो "इस स्रोत से अनुमति" दें)।</p>
<p><a href="`+base+`RoninAgentLab.apk" style="display:block;padding:18px;background:#0d7680;color:#fff;border-radius:10px;text-align:center;text-decoration:none">① Ronin Agent</a></p>
<p><a href="`+base+`RoninCensus.apk" style="display:block;padding:18px;background:#0d7680;color:#fff;border-radius:10px;text-align:center;text-decoration:none">② जनगणना सहायक</a></p>
<p>2. Ronin Agent खोलें → यह कौन सा phone है चुनें → 6 अंक का code दिखेगा।</p>
<p>3. अपने मुख्य phone या tablet पर 🤖 → "नया device जोड़ें" → वही code देखकर दबाकर रखें।</p>
<p>4. नए phone पर जनगणना सहायक खोलकर उसकी 3 चीज़ें चालू करें।</p></body>`)
}
