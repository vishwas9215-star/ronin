package agentrelay

import (
	"encoding/json"
	"fmt"
	"testing"
	"time"
)

func (d *fakeDevice) sendBody(m map[string]any) (int, map[string]any) {
	f, _ := json.Marshal(m)
	d.seqOut++
	res, b := d.req("POST", "/agent/out?s="+d.sid, map[string]any{"f": string(f), "seq": d.seqOut, "mac": mac(d.k, fmt.Sprintf("%d|%s", d.seqOut, f))})
	var out map[string]any
	json.Unmarshal(b, &out)
	return res.StatusCode, out
}

func (d *fakeDevice) askRepair(code string) (int, map[string]any) {
	res, b := d.req("POST", "/agent/pair", map[string]string{"name": d.name, "key": d.secret, "pub": d.pubPEM(), "code": code})
	var m map[string]any
	json.Unmarshal(b, &m)
	if res.StatusCode == 200 {
		d.relayPub, _ = m["relay_pub"].(string)
	}
	return res.StatusCode, m
}

// approval as the tablet app makes it
func (d *fakeDevice) approval(name, newPub, oldPub, code string, ts int64) map[string]any {
	n, o := hexSha(newPub), hexSha(oldPub)
	return map[string]any{"repairApprove": map[string]any{"name": name, "new": n, "old": o, "code": code, "ts": ts,
		"sig": d.sign(fmt.Sprintf("handover|%s|%s|%s|%s|%d", name, n, o, code, ts))}}
}

func TestFormattedPhoneIsAllowedBackByTheTabletWithoutTheServer(t *testing.T) {
	e := newEnv(t)
	e.r.OpenPairing(time.Minute)
	tab, ph := e.device(t, "tablet"), e.device(t, "phone")
	if tab.pairAndHello() != 200 || ph.pairAndHello() != 200 {
		t.Fatal("pair")
	}
	e.mu.Lock()
	e.now = e.now.Add(2 * time.Hour) // pairing long closed
	e.mu.Unlock()
	if tab.hello() != 200 {
		t.Fatal("tablet hello")
	}
	oldPub := ph.pubPEM()
	// the phone is formatted: new key, new secret
	nw := newDevice(t, e.srv.URL, "phone")
	if c, _ := nw.askRepair(""); c != 409 {
		t.Fatal("without a code: plain refusal")
	}
	if c, m := nw.askRepair("482913"); c != 409 || m["repair"] != "waiting" {
		t.Fatalf("waits: %d %v", c, m)
	}
	// the tablet sees it (not its own name), with the code
	_, l := tab.sendBody(map[string]any{"repairList": true})
	list, _ := l["repairs"].([]any)
	if len(list) != 1 || list[0].(map[string]any)["code"] != "482913" || list[0].(map[string]any)["old_pub"] != oldPub {
		t.Fatalf("list: %v", l)
	}
	now := e.r.cfg.Now().UnixMilli()
	bad := []map[string]any{
		tab.approval("phone", nw.pubPEM(), oldPub, "111111", now),                      // other code
		tab.approval("phone", ph.pubPEM(), oldPub, "482913", now),                      // other key
		tab.approval("phone", nw.pubPEM(), nw.pubPEM(), "482913", now),                 // wrong old key
		tab.approval("phone", nw.pubPEM(), oldPub, "482913", now-int64(time.Hour/1e6)), // old
		ph.approval("phone", nw.pubPEM(), oldPub, "482913", now),                       // signed by someone else
	}
	for i, a := range bad {
		if _, r := tab.sendBody(a); r["ok"] != false {
			t.Fatalf("bad approval %d accepted", i)
		}
	}
	// a device can not hand over its own name (an old phone keeps nothing)
	if ph.hello() != 200 {
		t.Fatal("old phone still paired until the owner allows")
	}
	if _, r := ph.sendBody(map[string]any{"repairList": true}); len(r["repairs"].([]any)) != 0 {
		t.Fatal("own name listed")
	}
	if _, r := tab.sendBody(tab.approval("phone", nw.pubPEM(), oldPub, "482913", now)); r["ok"] != true {
		t.Fatalf("good approval: %v", r)
	}
	if ph.hello() != 401 {
		t.Fatal("the old key is out")
	}
	c, m := nw.askRepair("482913")
	if c != 200 || m["handover"] == nil || m["peers"].(map[string]any)["tablet"] != tab.pubPEM() {
		t.Fatalf("new phone pairs: %d %v", c, m)
	}
	h := m["handover"].(map[string]any)
	if !verifySig(tab.pubPEM(), fmt.Sprintf("handover|phone|%s|%s|482913|%d", hexSha(nw.pubPEM()), hexSha(oldPub), int64(num(h["ts"]))), h["sig"].(string)) {
		t.Fatal("handover signed by the tablet")
	}
	if nw.hello() != 200 {
		t.Fatal("new phone online")
	}
	if hs, _ := tab.helloAgain()["handovers"].([]any); len(hs) != 1 {
		t.Fatal("other devices learn the handover at hello")
	}
	// used once: the same approval again does nothing; a stranger later only waits
	if _, r := tab.sendBody(tab.approval("phone", nw.pubPEM(), oldPub, "482913", now)); r["ok"] != false {
		t.Fatal("replayed approval")
	}
	// survives a relay restart
	r2, _ := New(Config{DataDir: e.r.cfg.DataDir, Now: e.r.cfg.Now})
	if len(r2.handover) != 1 || len(r2.devices) != 2 {
		t.Fatal("not saved")
	}
}

func (d *fakeDevice) helloAgain() map[string]any {
	if d.hello() != 200 {
		d.t.Fatal("hello")
	}
	return d.helloReply
}

func TestRepairRequestsAreRateLimited(t *testing.T) {
	e := newEnv(t)
	e.r.OpenPairing(time.Minute)
	ph := e.device(t, "phone")
	ph.pairAndHello()
	nw := newDevice(t, e.srv.URL, "phone")
	limited := false
	for i := 0; i < 40; i++ {
		if c, _ := nw.askRepair("123456"); c == 429 {
			limited = true
		}
	}
	if !limited {
		t.Fatal("no limit")
	}
}
