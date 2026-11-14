//go:build windows

package main

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
	"sync"
	"time"
	"unsafe"

	"golang.org/x/sys/windows"

	"roninbrowser/internal/ai"
	"roninbrowser/internal/containers"
	"roninbrowser/internal/safemoney"
	"roninbrowser/internal/updater"
)

// ---- page text with a result (ExecuteScript + completion handler) -----------

const wvExecuteScript = 29

// runScript runs JavaScript in a tab and gives its JSON result to done
// (on the UI thread).
func (t *Tab) runScript(js string, done func(json string)) {
	h := newHandler(func(errCode, result uintptr) uintptr {
		out := ""
		if int32(errCode) >= 0 && result != 0 {
			out = windows.UTF16PtrToString((*uint16)(unsafe.Pointer(result)))
		}
		runOnUI(func() { done(out) })
		return sOK
	})
	p, err := windows.UTF16PtrFromString(js)
	if err != nil {
		done("")
		return
	}
	if failed(call(uintptr(t.wv), wvExecuteScript, uintptr(unsafe.Pointer(p)), uintptr(unsafe.Pointer(h)))) {
		done("")
	}
}

const pageTextJS = `(function(){var s=(window.getSelection&&String(window.getSelection()))||"";` +
	`var t=s.length>40?s:(document.body?document.body.innerText:"");` +
	`return {sel:s.length>40,title:document.title||"",text:t.slice(0,60000),` +
	`scam:(window.__roninScamScore?window.__roninScamScore(t.slice(-40000)):-1)};})()`

type pageText struct {
	Sel   bool   `json:"sel"`
	Title string `json:"title"`
	Text  string `json:"text"`
	Scam  int    `json:"scam"`
}

// ---- keys in Windows Credential Manager -----------------------------------------

var (
	advapi32    = windows.NewLazySystemDLL("advapi32.dll")
	pCredWriteW = advapi32.NewProc("CredWriteW")
	pCredReadW  = advapi32.NewProc("CredReadW")
	pCredDelete = advapi32.NewProc("CredDeleteW")
	pCredFree   = advapi32.NewProc("CredFree")
)

type credential struct {
	Flags              uint32
	Type               uint32
	TargetName         *uint16
	Comment            *uint16
	LastWritten        windows.Filetime
	CredentialBlobSize uint32
	CredentialBlob     *byte
	Persist            uint32
	AttributeCount     uint32
	Attributes         uintptr
	TargetAlias        *uint16
	UserName           *uint16
}

const credTypeGeneric = 1
const credPersistLocalMachine = 2

func credTarget(name string) *uint16 { return utf16("RoninBrowser/" + name) }

// saveKey stores an AI key in Windows Credential Manager (never in a file).
func saveKey(name, key string) error {
	key = strings.TrimSpace(key)
	if key == "" {
		pCredDelete.Call(uintptr(unsafe.Pointer(credTarget(name))), credTypeGeneric, 0)
		return nil
	}
	blob := []byte(key)
	c := credential{
		Type: credTypeGeneric, TargetName: credTarget(name), CredentialBlobSize: uint32(len(blob)),
		CredentialBlob: &blob[0], Persist: credPersistLocalMachine, UserName: utf16("ronin"),
	}
	r, _, err := pCredWriteW.Call(uintptr(unsafe.Pointer(&c)), 0)
	if r == 0 {
		return err
	}
	return nil
}

func loadKey(name string) string {
	var pc *credential
	r, _, _ := pCredReadW.Call(uintptr(unsafe.Pointer(credTarget(name))), credTypeGeneric, 0, uintptr(unsafe.Pointer(&pc)))
	if r == 0 || pc == nil {
		return ""
	}
	defer pCredFree.Call(uintptr(unsafe.Pointer(pc)))
	if pc.CredentialBlobSize == 0 || pc.CredentialBlob == nil {
		return ""
	}
	return string(unsafe.Slice(pc.CredentialBlob, pc.CredentialBlobSize))
}

func maskKey(k string) string {
	if len(k) < 8 {
		if k == "" {
			return ""
		}
		return "****"
	}
	return k[:4] + "..." + k[len(k)-4:]
}

// ---- the AI panel ------------------------------------------------------------------

var aiBusy sync.Mutex

// aiAllowed explains why AI may not be used on this tab ("" = allowed).
func (t *Tab) aiAllowed() string {
	switch t.w.container {
	case containers.Banking:
		return "AI is switched off in Ronin Safe Money, so your bank pages never leave this computer."
	case containers.Trading:
		return "AI is switched off in the Trading window, to keep your trading account private."
	case containers.Kids:
		return "AI is not available in Kids mode."
	case containers.Calls:
		return "AI is switched off in the Video call window."
	}
	if t.isNewTab || !strings.HasPrefix(t.url, "http") {
		return "Open a web page first."
	}
	switch safemoney.Classify(t.url, store.Lists()).Kind {
	case safemoney.Bank, safemoney.Payment, safemoney.Auth:
		return "AI is never used on bank, payment or sign-in pages."
	}
	return ""
}

func currentProvider() ai.Provider {
	s := store.Get()
	p := ai.Provider{Kind: s.AIProvider, Model: s.AIModel, BaseURL: s.AIBaseURL}
	p.Key = loadKey(p.Kind)
	return p
}

// prepareAI reads the page, hides private numbers and shows the preview.
func (w *Window) prepareAI(task ai.Task, question string) {
	t := w.active
	if t == nil {
		return
	}
	if why := t.aiAllowed(); why != "" {
		w.overlay("ai", map[string]any{"step": "blocked", "text": why})
		return
	}
	t.runScript(pageTextJS, func(js string) {
		var pt pageText
		if json.Unmarshal([]byte(js), &pt) != nil || strings.TrimSpace(pt.Text) == "" {
			w.overlay("ai", map[string]any{"step": "blocked", "text": "Ronin could not read text on this page."})
			return
		}
		clean := ai.Redact(pt.Text)
		if len(clean) > ai.MaxPageChars {
			clean = clean[:ai.MaxPageChars]
		}
		p := currentProvider()
		cost := ""
		if p.Kind == "claude" {
			in := ai.EstimateTokens(ai.Prompt(task, pt.Title, clean, question))
			cost = fmt.Sprintf("About ₹%.2f (paid). Today so far: ₹%.2f of your ₹%d limit.", ai.ClaudeCostRupees(in, 900), spentToday(), store.Get().AIDailyCapRupees)
		}
		w.pendingAI = &aiRequest{task: task, title: pt.Title, text: clean, question: question, scam: pt.Scam}
		w.overlay("ai", map[string]any{
			"step": "preview", "task": string(task), "provider": p.Kind, "haveKey": p.Key != "",
			"chars": len(clean), "sample": firstChars(clean, 600), "selection": pt.Sel, "cost": cost,
			"scam": pt.Scam,
		})
	})
}

type aiRequest struct {
	task     ai.Task
	title    string
	text     string
	question string
	scam     int
}

func firstChars(s string, n int) string {
	r := []rune(s)
	if len(r) > n {
		return string(r[:n]) + "..."
	}
	return s
}

// sendAI sends the previewed text after the user pressed Send.
func (w *Window) sendAI() {
	req := w.pendingAI
	w.pendingAI = nil
	if req == nil {
		return
	}
	p := currentProvider()
	if p.Key == "" {
		w.overlay("ai", map[string]any{"step": "nokey"})
		return
	}
	prompt := ai.Prompt(req.task, req.title, req.text, req.question)
	if p.Kind == "claude" {
		cap := float64(store.Get().AIDailyCapRupees)
		est := ai.ClaudeCostRupees(ai.EstimateTokens(prompt), 900)
		if cap <= 0 || spentToday()+est > cap {
			w.overlay("ai", map[string]any{"step": "blocked", "text": fmt.Sprintf("Your daily AI limit (₹%.0f) is reached. Claude is paid, so Ronin stopped here. Switch to free Gemini in Settings, or raise the limit.", cap)})
			return
		}
	}
	if !aiBusy.TryLock() {
		return
	}
	w.overlay("ai", map[string]any{"step": "working"})
	go func() {
		defer aiBusy.Unlock()
		ctx, cancel := context.WithTimeout(context.Background(), 100*time.Second)
		defer cancel()
		var res ai.Result
		var err error
		if p.Kind == "gemini" && p.Model == "" {
			// the free service is sometimes overloaded: wait a moment, then
			// try the next free model
			for i, m := range geminiCandidates(ctx, p.Key) {
				p.Model = m
				if res, err = ai.Run(ctx, p, prompt); err == nil || !ai.IsBusy(err) {
					break
				}
				logf("ai: %s busy (try %d)", m, i+1)
				time.Sleep(2 * time.Second)
			}
		} else {
			res, err = ai.Run(ctx, p, prompt)
		}
		if err == nil && p.Kind == "claude" {
			addSpent(ai.ClaudeCostRupees(res.InTokens, res.OutTokens))
		}
		runOnUI(func() {
			if err != nil {
				w.overlay("ai", map[string]any{"step": "error", "text": err.Error()})
				return
			}
			w.overlay("ai", map[string]any{"step": "answer", "task": string(req.task), "text": res.Text, "scam": req.scam})
		})
	}()
}

var (
	geminiModelMu  sync.Mutex
	geminiModelSel string
)

func geminiModel(ctx context.Context, key string) string {
	return geminiCandidates(ctx, key)[0]
}

var geminiCands []string

// geminiCandidates: the free models to try, best first (read once per run).
func geminiCandidates(ctx context.Context, key string) []string {
	geminiModelMu.Lock()
	defer geminiModelMu.Unlock()
	if geminiModelSel != "" && len(geminiCands) > 0 {
		return geminiCands
	}
	names, err := ai.GeminiModels(ctx, key)
	if err != nil {
		return ai.GeminiCandidates(nil)
	}
	geminiCands = ai.GeminiCandidates(names)
	geminiModelSel = geminiCands[0]
	return geminiCands
}

// testAIKey checks a key with a tiny request.
func (w *Window) testAIKey() {
	p := currentProvider()
	if p.Key == "" {
		w.bar(nil, "warn", "Add an AI key first.", nil)
		return
	}
	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), 40*time.Second)
		defer cancel()
		if p.Kind == "gemini" && p.Model == "" {
			p.Model = geminiModel(ctx, p.Key)
		}
		_, err := ai.Run(ctx, p, "Reply with the single word OK.")
		runOnUI(func() {
			if err != nil {
				w.bar(nil, "error", "AI test failed: "+err.Error(), nil)
			} else {
				w.bar(nil, "ok", "AI key works ("+p.Kind+").", nil)
			}
		})
	}()
}

// ---- daily spend for paid AI ---------------------------------------------------------

func spentToday() float64 {
	stateMu.Lock()
	defer stateMu.Unlock()
	st := updater.LoadState(statePath())
	c := st.Components["ai-spend"]
	if c.Version != time.Now().Format("2006-01-02") {
		return 0
	}
	var v float64
	fmt.Sscanf(c.Problem, "%f", &v)
	return v
}

func addSpent(rupees float64) {
	total := spentToday() + rupees
	day := time.Now().Format("2006-01-02")
	recordComponent("ai-spend", func(c *updater.Component) {
		c.Version, c.Problem, c.Updated, c.OK = day, fmt.Sprintf("%.4f", total), time.Now(), true
	})
}
