//go:build windows

package main

// cdpPage: the Claude tab (WebView2) driven through its DevTools protocol. Clicks and keys are real browser
// input; the helper runs in an isolated world, where the page's own scripts can not change what it sees.

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"math/rand"
	"strings"
	"sync"
	"time"

	"github.com/wailsapp/go-webview2/pkg/edge"

	"roninbrowser/internal/agentcore"
)

type cdpPage struct {
	c      *edge.Chromium
	mu     sync.Mutex
	ctxID  int
	loader string
	loaded chan struct{}
}

type cdpResult struct {
	hr   uintptr
	json string
	err  error
}

func (p *cdpPage) cdp(method string, params any) (map[string]any, error) {
	b := []byte("{}")
	if params != nil {
		b, _ = json.Marshal(params)
	}
	ch := make(chan cdpResult, 1)
	call := func() {
		if err := p.c.CallDevTools(method, string(b), func(hr uintptr, s string) { ch <- cdpResult{hr: hr, json: s} }); err != nil {
			ch <- cdpResult{err: err}
		}
	}
	if onUIThread() {
		return nil, errors.New("cdp on the UI thread") // would wait forever: the answer comes through the UI loop
	}
	runOnUI(call)
	select {
	case r := <-ch:
		if r.err != nil {
			return nil, r.err
		}
		var m map[string]any
		json.Unmarshal([]byte(r.json), &m)
		if r.hr != 0 {
			return m, fmt.Errorf("%s: %s", method, trim(r.json, 160))
		}
		return m, nil
	case <-time.After(20 * time.Second):
		return nil, errors.New(method + ": no answer in time")
	}
}

func trim(s string, n int) string {
	if len(s) > n {
		return s[:n]
	}
	return s
}

// world: the helper's isolated world for the current document (made again after every page load).
func (p *cdpPage) world() (int, error) {
	ft, err := p.cdp("Page.getFrameTree", nil)
	if err != nil {
		return 0, err
	}
	fr, _ := ft["frameTree"].(map[string]any)
	f, _ := fr["frame"].(map[string]any)
	id, _ := f["id"].(string)
	loader, _ := f["loaderId"].(string)
	p.mu.Lock()
	if p.ctxID != 0 && loader == p.loader {
		c := p.ctxID
		p.mu.Unlock()
		return c, nil
	}
	p.mu.Unlock()
	w, err := p.cdp("Page.createIsolatedWorld", map[string]any{"frameId": id, "worldName": "ronin-agent", "grantUniveralAccess": false})
	if err != nil {
		return 0, err
	}
	cf, ok := w["executionContextId"].(float64)
	if !ok {
		return 0, errors.New("no isolated world")
	}
	ctx := int(cf)
	if _, err := p.eval(ctx, agentcore.AgentJS); err != nil {
		return 0, err
	}
	p.mu.Lock()
	p.ctxID, p.loader = ctx, loader
	p.mu.Unlock()
	return ctx, nil
}

func (p *cdpPage) eval(ctx int, expr string) (any, error) {
	r, err := p.cdp("Runtime.evaluate", map[string]any{"expression": expr, "contextId": ctx, "returnByValue": true})
	if err != nil {
		return nil, err
	}
	if ex, ok := r["exceptionDetails"].(map[string]any); ok {
		return nil, fmt.Errorf("page helper: %v", trim(fmt.Sprint(ex["text"]), 120))
	}
	res, _ := r["result"].(map[string]any)
	return res["value"], nil
}

func (p *cdpPage) Call(expr string) (string, error) {
	for attempt := 0; attempt < 2; attempt++ {
		ctx, err := p.world()
		if err == nil {
			var v any
			v, err = p.eval(ctx, "JSON.stringify("+expr+")")
			if err == nil {
				s, _ := v.(string)
				return s, nil
			}
		}
		p.mu.Lock()
		p.ctxID = 0 // the document changed under us: make the world again
		p.mu.Unlock()
		if attempt == 1 {
			return "", err
		}
	}
	return "", errors.New("no page")
}

func (p *cdpPage) Navigate(u string) error {
	for len(p.loaded) > 0 {
		<-p.loaded
	}
	r, err := p.cdp("Page.navigate", map[string]any{"url": u})
	if err != nil {
		return err
	}
	if t, _ := r["errorText"].(string); t != "" {
		return errors.New(t)
	}
	select {
	case <-p.loaded:
	case <-time.After(30 * time.Second):
	}
	return nil
}

func (p *cdpPage) URL() string {
	if onUIThread() {
		return p.c.Source()
	}
	ch := make(chan string, 1)
	runOnUI(func() { ch <- p.c.Source() })
	select {
	case s := <-ch:
		return s
	case <-time.After(5 * time.Second):
		return ""
	}
}

func (p *cdpPage) Back() error {
	ctx, err := p.world()
	if err != nil {
		return err
	}
	_, err = p.eval(ctx, "history.back()")
	return err
}

func (p *cdpPage) mouse(typ string, x, y float64, buttons int) error {
	_, err := p.cdp("Input.dispatchMouseEvent", map[string]any{"type": typ, "x": x, "y": y, "button": "left", "buttons": buttons, "clickCount": 1})
	return err
}

func (p *cdpPage) Tap(x, y float64, slow bool) error {
	p.cdp("Input.dispatchMouseEvent", map[string]any{"type": "mouseMoved", "x": x, "y": y})
	if err := p.mouse("mousePressed", x, y, 1); err != nil {
		return err
	}
	if slow {
		time.Sleep(time.Duration(140+rand.Intn(120)) * time.Millisecond)
	} else {
		time.Sleep(time.Duration(50+rand.Intn(60)) * time.Millisecond)
	}
	return p.mouse("mouseReleased", x, y, 0)
}

type keyDef struct {
	key, code, text string
	vk              int
}

var keyDefs = map[string]keyDef{
	"enter": {"Enter", "Enter", "\r", 13}, "tab": {"Tab", "Tab", "", 9}, "escape": {"Escape", "Escape", "", 27},
	"pagedown": {"PageDown", "PageDown", "", 34}, "pageup": {"PageUp", "PageUp", "", 33}, "arrowdown": {"ArrowDown", "ArrowDown", "", 40},
	"arrowup": {"ArrowUp", "ArrowUp", "", 38}, "arrowleft": {"ArrowLeft", "ArrowLeft", "", 37}, "arrowright": {"ArrowRight", "ArrowRight", "", 39},
	"backspace": {"Backspace", "Backspace", "", 8}, "space": {" ", "Space", " ", 32},
}

func (p *cdpPage) Key(name string) error {
	k, ok := keyDefs[strings.ToLower(name)]
	if !ok {
		return errors.New("unknown key " + name)
	}
	down := map[string]any{"type": "rawKeyDown", "key": k.key, "code": k.code, "windowsVirtualKeyCode": k.vk, "nativeVirtualKeyCode": k.vk}
	if k.text != "" {
		down["type"], down["text"], down["unmodifiedText"] = "keyDown", k.text, k.text
	}
	if _, err := p.cdp("Input.dispatchKeyEvent", down); err != nil {
		return err
	}
	_, err := p.cdp("Input.dispatchKeyEvent", map[string]any{"type": "keyUp", "key": k.key, "code": k.code, "windowsVirtualKeyCode": k.vk, "nativeVirtualKeyCode": k.vk})
	return err
}

func (p *cdpPage) SelectAll() error {
	if _, err := p.cdp("Input.dispatchKeyEvent", map[string]any{"type": "rawKeyDown", "key": "a", "code": "KeyA", "windowsVirtualKeyCode": 65, "modifiers": 2, "commands": []string{"selectAll"}}); err != nil {
		return err
	}
	_, err := p.cdp("Input.dispatchKeyEvent", map[string]any{"type": "keyUp", "key": "a", "code": "KeyA", "windowsVirtualKeyCode": 65, "modifiers": 2})
	if err != nil {
		return err
	}
	// a page that swallows Ctrl+A: select the box's text directly (isolated world, the real element)
	if ctx, err := p.world(); err == nil {
		p.eval(ctx, `(function(){var a=document.activeElement;while(a&&a.shadowRoot&&a.shadowRoot.activeElement)a=a.shadowRoot.activeElement;
if(a&&(a.tagName==='INPUT'||a.tagName==='TEXTAREA')){try{a.select()}catch(e){}}})()`)
	}
	return nil
}

func (p *cdpPage) Insert(text string) error {
	if text == "" {
		return p.Key("backspace")
	}
	_, err := p.cdp("Input.insertText", map[string]any{"text": text})
	return err
}

func (p *cdpPage) Scroll(up bool) error {
	ctx, err := p.world()
	if err != nil {
		return err
	}
	s := ""
	if up {
		s = "-"
	}
	_, err = p.eval(ctx, "window.scrollBy(0, "+s+"Math.round(innerHeight*0.8))")
	return err
}

func (p *cdpPage) Screenshot() ([]byte, error) {
	r, err := p.cdp("Page.captureScreenshot", map[string]any{"format": "jpeg", "quality": 55})
	if err != nil {
		return nil, err
	}
	d, _ := r["data"].(string)
	return base64.StdEncoding.DecodeString(d)
}

// focusedJS: what the focused box really is, read in the isolated world (the page can not fake it there),
// through shadow roots. Only a confirmed plain text box is "normal"; anything not understood is "unknown".
const focusedJS = `(function(){var a=document.activeElement;
while(a&&a.shadowRoot&&a.shadowRoot.activeElement)a=a.shadowRoot.activeElement;
if(!a||a===document.body||a===document.documentElement)return 'unknown';
var tag=a.tagName;if(tag==='IFRAME'||tag==='FRAME'||tag==='OBJECT'||tag==='EMBED')return 'unknown';
var ac=String(a.getAttribute('autocomplete')||'').toLowerCase();if(/current-password|new-password|one-time-code/.test(ac))return 'secret';
var s=getComputedStyle(a);var ts=s.webkitTextSecurity||s.getPropertyValue('-webkit-text-security');if(ts&&ts!=='none')return 'secret';
if(tag==='INPUT'){var t=String(a.type).toLowerCase();if(t==='password')return 'secret';
 return ['text','search','email','tel','url','number','date','datetime-local','month','week','time',''].indexOf(t)>=0?'normal':'unknown';}
if(tag==='TEXTAREA')return 'normal';
if(a.isContentEditable||String(a.getAttribute('role')||'').toLowerCase()==='textbox')return 'normal';
return 'unknown'})()`

func (p *cdpPage) FocusedSecret() string {
	ctx, err := p.world()
	if err != nil {
		return "unknown"
	}
	v, err := p.eval(ctx, focusedJS)
	if s, ok := v.(string); ok && err == nil {
		return s
	}
	return "unknown"
}

// FocusedText: the focused element's label / text (Enter or Space would press it).
func (p *cdpPage) FocusedText() string {
	ctx, err := p.world()
	if err != nil {
		return ""
	}
	v, _ := p.eval(ctx, `(function(){var a=document.activeElement;while(a&&a.shadowRoot&&a.shadowRoot.activeElement)a=a.shadowRoot.activeElement;
if(!a)return '';return String(a.getAttribute('aria-label')||a.value||a.innerText||a.textContent||'').trim().slice(0,80)})()`)
	s, _ := v.(string)
	return s
}
