package agentrelay

import (
	"fmt"
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
	case sha(q.Pub) != newH || q.Code != code:
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
	if old == "" || sha(old) != oldH {
		return "the old key is not the one the relay has"
	}
	text := fmt.Sprintf("handover|%s|%s|%s|%s|%d", name, newH, oldH, code, ts)
	if !verifySig(approver, text, sig) {
		return "the approval is not signed by the device"
	}
	delete(r.devices, oldHash)
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
