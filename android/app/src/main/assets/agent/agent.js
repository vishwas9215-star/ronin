// Ronin Agent page helper (27 Nov 2026). The SAME file runs inside Ronin's page engine on every
// device. Windows: in its own isolated world (Page.createIsolatedWorld), Android: at document start, before any page
// script, with every browser function it needs captured first, so a hostile page cannot fake what it reads.
// It only READS and LOCATES; taps / typing are real input events sent by the app.
(function () {
  if (window.__roninAgent) return;
  // ---- natives captured before the page can touch them ----
  const N = {
    qsa: Document.prototype.querySelectorAll, eqsa: Element.prototype.querySelectorAll, rect: Element.prototype.getBoundingClientRect,
    gcs: window.getComputedStyle, attr: Element.prototype.getAttribute, matches: Element.prototype.matches, closest: Element.prototype.closest,
    efp: Document.prototype.elementFromPoint, contains: Node.prototype.contains,
    innerText: Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'innerText').get,
    shadow: Object.getOwnPropertyDescriptor(Element.prototype, 'shadowRoot').get,
    value: Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').get,
    scroll: Element.prototype.scrollIntoView, tag: Object.getOwnPropertyDescriptor(Element.prototype, 'tagName').get,
  };
  const call = (f, o, ...a) => Function.prototype.call.call(f, o, ...a);
  let next = 1;
  const MAX = 220;
  const INTERACTIVE = 'a[href],button,input,select,textarea,summary,[role=button],[role=link],[role=tab],[role=menuitem],[role=checkbox],[role=radio],[role=option],[role=combobox],[contenteditable=true],[onclick],[tabindex]:not([tabindex="-1"])';
  // money / order words in English and Hindi (default-deny happens on the device; this only marks them)
  const MONEY_WORD = /\b(buy|sell|place order|confirm order|modify order|square ?off|exit( all| position)?|pay( now)?|transfer|withdraw|send money|proceed to pay|submit order|checkout|confirm payment|invest now)\b|खरीद|बेच|भुगतान|ऑर्डर|पैसे भेज|निकासी/i;
  const MONEY_FORM = /order|payment|pay\b|transfer|withdraw|checkout|fund|trade|neft|imps|upi/i;

  const S = (el) => call(N.gcs, window, el);
  const R = (el) => call(N.rect, el);
  function visible(el) {
    const r = R(el); if (r.width < 2 || r.height < 2) return false;
    const s = S(el); return s.visibility !== 'hidden' && s.display !== 'none' && +s.opacity > 0.05;
  }
  const A = (el, n) => call(N.attr, el, n);
  const TXT = (el) => { try { return call(N.innerText, el) || ''; } catch (e) { return el.textContent || ''; } };
  function label(el) {
    let t = A(el, 'aria-label') || A(el, 'title') || A(el, 'placeholder') || A(el, 'alt') || (el.labels && el.labels[0] && TXT(el.labels[0])) || TXT(el) || '';
    if (!t && el.tagName === 'INPUT' && /submit|button/.test(el.type)) t = el.value;
    if (!t) { const img = el.querySelector && el.querySelector('img[alt],svg title'); if (img) t = A(img, 'alt') || img.textContent; }
    return String(t || A(el, 'name') || '').replace(/\s+/g, ' ').trim().slice(0, 80);
  }
  function kind(el) { const tag = el.tagName.toLowerCase(); return tag === 'input' ? 'input:' + (el.type || 'text') : (A(el, 'role') || tag); }
  // an OTP box by its shape, not only its name
  function otpBox(el) {
    if (el.tagName !== 'INPUT') return false;
    if ((A(el, 'autocomplete') || '').includes('one-time-code')) return true;
    const ml = +A(el, 'maxlength') || 0, im = A(el, 'inputmode') || '';
    // split OTP: a row of 4-8 one-character boxes counts as ONE secret field (any one-time-code member taints all)
    if (ml === 1 && el.parentElement) { const sib = [...call(N.eqsa, el.parentElement, 'input')].filter(x => +A(x, 'maxlength') === 1); if (sib.length >= 4 && sib.length <= 8) return true; }
    return /otp|one.?time|verification code|ओटीपी/i.test(label(el) + ' ' + (A(el, 'name') || '') + ' ' + (el.id || '')) || ((im === 'numeric' || el.type === 'tel') && ml >= 4 && ml <= 8 && /code|pin/i.test(label(el)));
  }
  // where the click would send data: the form it submits and that form's address
  function formOf(el) { const f = el.form || (call(N.closest, el, 'form')); return f ? { action: (f.action || ''), method: (f.method || 'get').toLowerCase() } : null; }
  function moneyAction(el) {
    const f = formOf(el), submits = f && (el.type === 'submit' || el.tagName === 'BUTTON' && (el.type || 'submit') === 'submit');
    return MONEY_WORD.test(label(el)) || (submits && f.method === 'post' && MONEY_FORM.test(f.action));
  }
  // long numbers that identify people / accounts: hidden unless the owner allowed this site
  function redact(s) {
    return String(s).replace(/\b\d{4}\s?\d{4}\s?\d{4}\b/g, '[aadhaar-like hidden]').replace(/\b[A-Z]{5}\d{4}[A-Z]\b/g, '[PAN hidden]').replace(/\b\d{9,18}\b/g, '[number hidden]');
  }
  function* walk(root) {
    for (const el of call(root.nodeType === 9 ? N.qsa : N.eqsa, root, '*')) {
      yield el;
      const sr = call(N.shadow, el); if (sr) yield* walk(sr);
      if (el.tagName === 'IFRAME') { let d = null; try { d = el.contentDocument; } catch (e) {} if (d) yield* walk(d); else yield { crossFrame: el }; }
    }
  }
  const byRef = new Map(), finger = new Map();
  // fingerprint from stable things only: auto-generated ids (long numbers / hashes) are left out
  const stableId = (id) => id && !/\d{3,}|[a-f0-9]{8,}|^:r|^ember|^react/i.test(id) ? id : '';
  const fp = (el) => { const f = el.form; return kind(el) + '|' + label(el) + '|' + stableId(el.id || '') + '|' + (A(el, 'name') || '') + '|' + (f ? (A(f, 'action') || '') : ''); };

  function snapshot(opts) {
    const allowIds = opts && opts.allowIds;
    byRef.clear();
    const lines = [], seen = new Set(), notes = [];
    for (const el of walk(document)) {
      if (el.crossFrame) { if (visible(el.crossFrame)) notes.push('a frame from another site (' + (A(el.crossFrame, 'src') || '').slice(0, 60) + ') is not readable: ask_owner if needed'); continue; }
      if (lines.length >= MAX) break;
      if (!call(N.matches, el, INTERACTIVE) || seen.has(el) || !visible(el)) continue;
      seen.add(el);
      const ref = 'e' + (el.__roninRef || (el.__roninRef = next++));
      byRef.set(ref, el); finger.set(ref, fp(el));
      const k = kind(el); let extra = '';
      if (k === 'input:password') extra = ' (password: the owner types it)';
      else if (otpBox(el)) extra = ' (OTP: the owner types it)';
      else if (/^input|textarea/.test(k)) { let v; try { v = call(N.value, el); } catch (e) { v = el.value; } extra = v ? ' value="' + (allowIds ? v : redact(v)).slice(0, 40) + '"' : ''; }
      if (k === 'select') extra = ' options=[' + [...el.options].slice(0, 15).map(o => o.text.trim()).join(' | ') + ']' + (el.value ? ' chosen="' + el.options[el.selectedIndex].text.trim() + '"' : '');
      if (el.disabled) extra += ' (disabled)';
      if (moneyAction(el)) extra += ' (money / order action: Claude may not press)';
      lines.push('[' + ref + '] ' + k + ' "' + label(el) + '"' + extra);
    }
    const text = TXT(document.body || document.documentElement).replace(/\s+\n/g, '\n').replace(/\n{3,}/g, '\n\n').slice(0, 3000);
    return { url: location.href, title: document.title, challenge: challenge(), elements: lines, notes,
      headings: [...call(N.qsa, document, 'h1,h2,h3')].filter(visible).slice(0, 12).map(h => '# ' + TXT(h).trim().slice(0, 100)),
      page_text_untrusted: allowIds ? text : redact(text) };
  }
  function challenge() {
    const t = document.title.toLowerCase();
    if (/just a moment|attention required|verify you are human|are you a robot|security check/.test(t)) return 'human-check page';
    if (call(N.qsa, document, '.cf-turnstile,iframe[src*="challenges.cloudflare.com"],iframe[src*="recaptcha"],.g-recaptcha,.h-captcha,iframe[src*="hcaptcha"],#captcha,img[src*="captcha" i],input[name*="captcha" i]').length) return 'CAPTCHA on the page';
    return '';
  }
  function resolve(ref) {
    let el = byRef.get(ref);
    if (el && el.isConnected) return el;
    // re-rendered page: find the same element again by what it is (kind + name + id), never by position
    const want = finger.get(ref); if (!want) return null;
    for (const e of walk(document)) if (!e.crossFrame && call(N.matches, e, INTERACTIVE) && visible(e) && fp(e) === want) { byRef.set(ref, e); return e; }
    return null;
  }
  /** Where to press. Also says what is REALLY under that point (overlays / clickjacking) and what the press would do. */
  function locate(ref) {
    const el = resolve(ref);
    if (!el) return { error: 'ref ' + ref + ' is gone: take a new snapshot' };
    call(N.scroll, el, { block: 'center', inline: 'center' });
    const r = R(el); let x = r.left + r.width / 2, y = r.top + r.height / 2;
    const top = call(N.efp, document, x, y);
    const covered = !top || !(top === el || call(N.contains, el, top) || call(N.contains, top, el) && top.tagName === 'LABEL');
    let w = el.ownerDocument.defaultView;
    while (w && w.frameElement) { const fr = R(w.frameElement); x += fr.left; y += fr.top; w = w.parent; }
    const f = formOf(el);
    return { x: Math.round(x), y: Math.round(y), kind: kind(el), name: label(el), fp: fp(el), href: el.tagName === 'A' ? (A(el, 'href') || '') : '', covered, coveredBy: covered && top ? label(top) || top.tagName : '',
      password: kind(el) === 'input:password', otp: otpBox(el), money: moneyAction(el),
      submits: !!(f && (el.type === 'submit' || el.tagName === 'BUTTON' && (el.type || 'submit') === 'submit')), formAction: f ? f.action : '', formMethod: f ? f.method : '',
      captcha: !!call(N.closest, el, '.cf-turnstile,.g-recaptcha,.h-captcha') || /captcha/i.test(A(el, 'name') || '') };
  }
  function selectOption(ref, text) {
    const el = resolve(ref); if (!el || el.tagName !== 'SELECT') return { error: 'not a dropdown' };
    const i = [...el.options].findIndex(o => o.text.trim().toLowerCase() === String(text).trim().toLowerCase());
    if (i < 0) return { error: 'no option "' + text + '". Options: ' + [...el.options].map(o => o.text.trim()).join(' | ') };
    el.selectedIndex = i; el.dispatchEvent(new Event('input', { bubbles: true })); el.dispatchEvent(new Event('change', { bubbles: true }));
    return { done: 'chose "' + el.options[i].text.trim() + '"' };
  }
  function getText(ref, allowIds) { const el = resolve(ref); if (!el) return { error: "ref gone" }; const t = TXT(el).slice(0, 2000); return { text: allowIds ? t : redact(t) }; }
  // the value of a box after typing (read back to report "verified"); never for password boxes
  function value(ref) { const el = resolve(ref); if (!el) return { error: 'ref gone' }; if (kind(el) === 'input:password') return { secret: true }; let v; try { v = call(N.value, el); } catch (e) { v = el.value; } return { value: String(v ?? '') }; }
  function view() { return { w: window.innerWidth, h: window.innerHeight, dpr: window.devicePixelRatio }; }
  function hasText(t) { return TXT(document.body || document.documentElement).includes(t); }
  window.__roninAgent = Object.freeze({ snapshot, locate, challenge, selectOption, getText, value, view, hasText });
})();
