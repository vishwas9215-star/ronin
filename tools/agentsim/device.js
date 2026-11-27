// Simulated Ronin device v2 (design prototype). In the real apps this is Ronin's "Agent" part:
// Android: Kotlin foreground service (type specialUse) + the Claude tab's WebView; taps by WebView.dispatchTouchEvent,
// Windows: Go + WebView2; clicks by CallDevToolsProtocolMethod("Input.dispatchMouseEvent").
// Here a Chromium page stands in for that WebView. ALL safety rules run HERE, on the device.
const WebSocket = require('ws');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const AGENT = fs.readFileSync(path.join(__dirname, 'agent.js'), 'utf8');

const FINANCE = /(^|\.)(dhan\.co|zerodha\.com|kite\.zerodha\.com|fyers\.in|upstox\.com|angelone\.in|groww\.in|5paisa\.com|icicidirect\.com|hdfcsec\.com|sharekhan\.com|paytm\.com|phonepe\.com|onlinesbi\.sbi|hdfcbank\.com|icicibank\.com|axisbank\.com|kotak\.com|razorpay\.com|payu\.in|billdesk\.com|ccavenue\.com)$/i;
const READ_TOOLS = ['snapshot', 'screenshot', 'get_text', 'scroll', 'back', 'tabs', 'wait_for', 'ask_owner', 'downloads'];
const GOV = /(\.gov\.in|\.nic\.in)$/i;
const MONEY_PATH = /order|payment|\bpay|transfer|withdraw|checkout|fund|trade|neft|imps|upi/i;
// registrable domain, enough for the taint rule (a.b.co.in -> b.co.in, x.y.com -> y.com)
const reg = (h) => { const p = h.split('.'); return p.slice(/^(co|gov|nic|org|net|ac|edu)$/.test(p[p.length - 2]) && p.length > 2 ? -3 : -2).join('.'); };
const sha = (x) => crypto.createHash('sha256').update(x).digest('hex');
// the owner's home network / the server itself are never reachable from the Claude tab
const PRIVATE = /^(localhost|127\.|10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.|169\.254\.|0\.|\[?::1\]?$|\[?f[cd])/i;
const ACTION_LINK = /delete|remove|unsubscribe|confirm|cancel|logout|log-out|signout|approve|submit|order|pay|transfer|update|save|create|edit|vote/i;
const GRANT_HOURS = 12; // a grant is accepted for 12 h at most: the longest a hidden "Disconnect" could be delayed

function createDevice({ name, url, key, page, kind, approver, relayPub }) {
  // relayPub: the relay's public key BAKED INTO the signed app at build time (not learned on first use)
  let peers = {}; // name -> public key of every paired device (to check the owner's signed grant)
  // this device's own signing key: ECDSA P-256. Real app: NON-EXPORTABLE key in Android Keystore (StrongBox / TEE,
  // user-presence required) and a TPM key via Windows CNG (Microsoft Platform Crypto Provider). DPAPI is not used for it.
  const me = crypto.generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  const sign = (text) => crypto.sign('sha256', Buffer.from(text), me.privateKey).toString('base64');
  let K = null, seqIn = 0, seqOut = 0; // per-connection session key (ECDH, authenticated by the pinned relay key)
  const mac = (k, x) => crypto.createHmac('sha256', k).update(x).digest('base64');
  // modes per site: 'free' (Claude may fill / press), 'ask' (first time: owner approves once), 'read' (read only)
  const st = { access: true, stopped: false, ownerUntil: 0, human: null, events: [], modes: {}, allowIds: new Set(), seen: new Set(), checks: {}, done: new Map(), busy: false,
    claudeUntil: 0, blocked: [], taint: '', okDest: new Set(), outBytes: 0, budget: 2000, count: 0, day: '', log: [], downloads: [], relayOk: false, idHosts: new Set(), freeUntil: {}, readPaths: {}, journal: new Map(), ownerResult: null, epoch: 0, blockedActs: new Set(), skew: 0, lastSeen: 0, digitOk: new Set() };
  const emit = (e) => st.events.push(e); // app: notification + banner
  const host = () => { try { return new URL(page.url()).hostname; } catch (e) { return ''; } };
  // an "Allow on this site" lasts 45 minutes (one tap per work session, not one per field)
  const mode = (h) => st.modes[h] || ((st.freeUntil[h] || 0) > Date.now() ? 'free' : (FINANCE.test(h) ? 'read' : 'ask'));
  const INPUT = ['click', 'type', 'select', 'upload', 'press'];
  const sensitive = (h) => FINANCE.test(h) || GOV.test(h) || mode(h) === 'read' || st.idHosts.has(h); // + any page where ID numbers were seen
  let ws, alive = true;

  // ---- network guard (final authority; Android shouldInterceptRequest, Windows WebResourceRequested) ----
  // while Claude's own tap / key is in effect, money-like or state-changing requests are refused even when no
  // <form> or money word is visible (fetch / XHR from a JS handler, GET links like /order?confirm=1).
  page.route('**/*', (route) => {
    const req = route.request(); let u; try { u = new URL(req.url()); } catch (e) { return route.fallback(); }
    // OFF only while the owner himself holds the Claude tab (take-over / ask_owner). Money-like requests are refused at
    // ANY time otherwise, so a timer that fires seconds after Claude's tap is caught too.
    if (PRIVATE.test(u.hostname)) { st.blocked.push('private address ' + u.hostname); return route.abort('blockedbyclient'); } // always
    if (Date.now() < st.ownerUntil || !/^https?:$/.test(u.protocol)) return route.fallback();
    const m = req.method(), h = u.hostname, claude = Date.now() <= st.claudeUntil; let why = '';
    if (claude && m !== 'GET' && mode(h) === 'read') why = `${m} to read-only site ${h}`;
    else if (m !== 'GET' && MONEY_PATH.test(u.pathname + u.search)) why = `${m} ${u.pathname} (money / order request)`;
    else if (m === 'GET' && MONEY_PATH.test(u.pathname) && /confirm|place|submit|execute|pay/i.test(u.search)) why = `GET ${u.pathname}${u.search} (money / order request)`;
    else if (claude && req.isNavigationRequest() && st.taint && reg(h) !== reg(st.taint) && !st.okDest.has(h) && h !== host()) why = `leaving ${st.taint} (sensitive data was read) for ${h}: needs the owner`;
    if (!why) return route.fallback();
    st.blocked.push(why); emit({ type: 'blocked', why });
    // a blocked page change leaves the current page as it is (Android: shouldOverrideUrlLoading returns true; here: 204)
    return req.isNavigationRequest() ? route.fulfill({ status: 204, body: '' }) : route.abort('blockedbyclient');
  });
  // downloads: the owner decides each one (real app: size from Content-Length, max 50 MB)
  page.on('download', async (d) => {
    const item = { file: d.suggestedFilename(), state: 'asking owner' }; st.downloads.push(item);
    if (await approve(`Claude's page wants to download "${item.file}". Allow?`)) { item.state = 'saved to Ronin/Downloads'; await d.path().catch(() => {}); }
    else { item.state = 'blocked (owner did not allow)'; await d.cancel().catch(() => {}); }
  });

  async function helper() { await page.evaluate(AGENT); }
  async function snap() { await helper(); const s = await page.evaluate((o) => window.__roninAgent.snapshot(o), { allowIds: st.allowIds.has(host()) }); st.seen.add(host()); if (/ hidden\]/.test(s.page_text_untrusted || '')) st.idHosts.add(host()); if (sensitive(host())) st.taint = host(); return s; }
  // the owner answers on the device (a notification with big Allow / Deny)
  async function approve(question) {
    emit({ type: 'approve', question });
    const ok = await Promise.race([Promise.resolve(approver ? approver(question) : false), new Promise(r => setTimeout(() => r(false), 120_000))]);
    return ok === true;
  }
  async function stateChange(h, what) {
    const m = mode(h);
    if (m === 'read') return `${h} is READ-ONLY for Claude (money / trading / bank site). ${what} refused: tell the owner what to press, or ask_owner.`;
    if (m === 'ask') {
      if (!(await approve(`Add ${h} to Claude's write list for 45 minutes? (Claude wants to ${what})`))) return `${h} is read-only for Claude until the owner adds it (he did not)`;
      st.freeUntil[h] = Date.now() + 45 * 60_000;
    }
    return '';
  }

  const tools = {
    async open({ url: u }) {
      let x; try { x = new URL(u); } catch (e) { return { error: 'not a web address' }; }
      if (!/^https?:$/.test(x.protocol)) return { error: x.protocol + ' addresses are not allowed (only http / https)' };
      if (PRIVATE.test(x.hostname)) return { error: 'home-network / server addresses are never opened by Claude' };
      // data leaving for a new site in the address (taint rule): the owner decides
      const payload = (x.search + x.hash).length;
      if (!st.seen.has(x.hostname) && payload > 100 && !(await approve(`Claude wants to open ${x.hostname} with ${payload} characters of data in the address. Allow?`)))
        return { error: 'opening a new site with data in its address needs the owner: refused' };
      // money sites: Claude may only open the pages the owner listed as "read pages" (e.g. /positions); never /order...
      if (mode(x.hostname) === 'read' && !['/', ...(st.readPaths[x.hostname] || [])].includes(x.pathname)) return { error: `${x.hostname}${x.pathname} is not one of the owner's read pages for this money site: refused` };
      if (mode(x.hostname) === 'read' && x.search) return { error: 'money site: no query strings for Claude: refused' };
      if (st.taint && reg(x.hostname) !== reg(st.taint) && !st.okDest.has(x.hostname)) {
        if (!(await approve(`Claude read ${st.taint} (sensitive). Now it wants to open ${x.hostname}. Allow?`))) return { error: `Claude read ${st.taint}; opening another site (${x.hostname}) needs the owner: refused` };
        st.okDest.add(x.hostname);
      }
      if ((st.outBytes += payload) > 20000 && !(await approve('Claude has sent a lot of data out today. Allow more?'))) return { error: 'outgoing data limit: owner did not allow' };
      await page.goto(u, { waitUntil: 'domcontentloaded' }).catch((e) => { if (!/ERR_BLOCKED_BY_CLIENT|aborted/i.test(String(e))) throw e; });
      const s = await snap();
      if (s.challenge) {
        const c = st.checks[x.hostname] = (st.checks[x.hostname] || []).filter(t => Date.now() - t < 600_000).concat(Date.now());
        if (c.length >= 3) return { error: `${x.hostname} asked for a human check ${c.length} times in 10 minutes: stopping here. Tell the owner (the site may not accept this browser right now).` };
      }
      return { url: s.url, title: s.title, challenge: s.challenge || undefined, hint: s.challenge ? 'human check: call ask_owner' : 'call snapshot to read it' };
    },
    async snapshot() { return snap(); },
    async get_text({ ref }) { await helper(); const r = await page.evaluate(([x, a]) => window.__roninAgent.getText(x, a), [ref, st.allowIds.has(host())]); if (sensitive(host())) st.taint = host(); return r; },
    async click({ ref }) {
      await helper();
      const at = await page.evaluate((r) => window.__roninAgent.locate(r), ref);
      if (at.error) return at;
      if (at.captcha) return { error: 'this is a human check: call ask_owner' };
      if (at.covered) return { error: `something else covers "${at.name}" (${at.coveredBy}): refused (possible trick). Take a new snapshot or ask_owner.` };
      const h = host();
      if (at.money) return { error: `"${at.name}" is a money / order action: Claude may not press it. Tell the owner, or ask_owner.` };
      // unknown sites are READ-ONLY until the owner adds them: only plain links may be followed; any button / form needs him
      if (at.kind === 'a' && /^\s*(javascript|vbscript):/i.test(at.href)) return { error: 'script link: refused' };
      // action-looking links (a heuristic, not a guarantee): an action word as the LAST path part, an action-like query key, or an /api/ path
      let linkWrites = false; if (at.kind === 'a') { try { const lu = new URL(at.href, page.url()); const last = lu.pathname.split('/').filter(Boolean).pop() || '';
        linkWrites = ACTION_LINK.test(last) || /\/api\//i.test(lu.pathname) || [...lu.searchParams.keys()].some(k => /action|confirm|^do$|cmd|^op$|delete|remove|unsub|approve|vote|pay|order|token/i.test(k)); } catch (e) { linkWrites = true; } }
      if (at.submits || at.kind !== 'a' || linkWrites) { const why = await stateChange(h, at.submits ? 'submit a form' : linkWrites ? 'follow an action link' : 'press buttons'); if (why) return { error: why }; }
      const act = 'click|' + h + '|' + at.fp; if (st.blockedActs.has(act)) return { error: `"${at.name}" was interrupted earlier and its result is unknown. The owner must check the page and tap CHECKED first.` };
      if (at.submits && at.formMethod === 'post' && FINANCE.test(h)) return { error: 'form sending to a money site: owner only' };
      st.lastTarget = act; const b0 = st.blocked.length; await claudeInput(() => tap(at.x, at.y));
      if (st.blocked.length > b0) return { error: `tapped "${at.name}", but the page then tried ${st.blocked.slice(b0).join('; ')}. BLOCKED on the device. Tell the owner.` };
      return { done: `tapped ${at.kind} "${at.name}"`, url: page.url() };
    },
    async type({ ref, text, submit }) {
      if (String(text).length > 2000) return { error: 'too much text in one go (max 2000 characters)' };
      await helper();
      const at = await page.evaluate((r) => window.__roninAgent.locate(r), ref);
      if (at.error) return at;
      if (at.password) return { error: 'password box: only the owner types passwords. Call ask_owner.' };
      if (at.otp) return { error: 'OTP box: the owner types the OTP. Call ask_owner.' };
      if (at.captcha) return { error: 'CAPTCHA box: call ask_owner' };
      if (at.covered) return { error: `"${at.name}" is covered by something else: refused` };
      // 4-8 digits only looks like an OTP / PIN: owner types it, unless he allowed digits in this exact field (e.g. "Pincode")
      if (/^\d{4,8}$/.test(String(text).replace(/[\s\-.,_]/g, '')) && !st.digitOk.has(host() + '|' + at.name)) return { error: `"${text.length}-digit number" looks like an OTP / PIN: the owner types it, or he allows digits in "${at.name}" on this site.` };
      const why = await stateChange(host(), 'type into forms'); if (why) return { error: why };
      if ((st.outBytes += String(text).length) > 20000 && !(await approve('Claude has typed a lot of text out today. Allow more?'))) return { error: 'outgoing data limit: owner did not allow' };
      const b0 = st.blocked.length;
      await claudeInput(async () => { await tap(at.x, at.y); await page.keyboard.press('Control+A'); await page.keyboard.type(String(text), { delay: 6 + Math.random() * 10 }); if (submit) await page.keyboard.press('Enter'); });
      if (st.blocked.length > b0) return { error: 'typed, but the page then tried ' + st.blocked.slice(b0).join('; ') + '. BLOCKED on the device.' };
      return { done: `typed into "${at.name}"` };
    },
    async select({ ref, option }) {
      const why = await stateChange(host(), 'choose in a form'); if (why) return { error: why };
      await helper(); return page.evaluate(([r, o]) => window.__roninAgent.selectOption(r, o), [ref, option]);
    },
    async upload({ ref, file }) {
      // files only from the owner's "Ronin/For Claude" folder; the app answers the page's file chooser with it
      if (!/^[\w .()-]+\.(pdf|jpg|jpeg|png|xlsx|csv|docx)$/i.test(file)) return { error: 'only a file name from the "Ronin/For Claude" folder' };
      const full = path.join(__dirname, 'for-claude', file); if (!fs.existsSync(full)) return { error: 'no such file in "Ronin/For Claude": ask_owner' };
      const why = await stateChange(host(), 'upload ' + file); if (why) return { error: why };
      await helper(); const at = await page.evaluate((r) => window.__roninAgent.locate(r), ref); if (at.error) return at;
      const [chooser] = await Promise.all([page.waitForEvent('filechooser'), tap(at.x, at.y)]);
      await chooser.setFiles(full); return { done: 'attached ' + file };
    },
    async press({ key: k }) {
      if (k === 'Enter') { const why = await stateChange(host(), 'press Enter'); if (why) return { error: why }; }
      const b0 = st.blocked.length; await claudeInput(() => page.keyboard.press(k));
      if (st.blocked.length > b0) return { error: 'pressed ' + k + ', but the page then tried ' + st.blocked.slice(b0).join('; ') + '. BLOCKED.' };
      return { done: 'pressed ' + k };
    },
    async scroll({ direction }) { await page.mouse.wheel(0, direction === 'up' ? -600 : 600); return { done: 'scrolled ' + direction }; },
    async downloads() { return { downloads: st.downloads }; },
    async screenshot() {
      // pixels cannot be redacted reliably: on money / government sites the owner taps Allow first
      if (sensitive(host()) && !(await approve(`Claude wants a screenshot of ${host()} (it may show ID numbers or balances). Allow?`))) return { error: 'screenshot of a sensitive site: the owner did not allow. Use snapshot (IDs hidden).' };
      if (sensitive(host())) st.taint = host();
      const b = await page.screenshot({ type: 'jpeg', quality: 55 }); return { image: b.toString('base64'), note: page.url() }; },
    async back() { await page.goBack(); return { url: page.url() }; },
    async tabs() { return { tabs: [{ index: 0, url: page.url(), title: await page.title(), claude: true }] }; },
    async wait_for({ text, seconds }) { // max 20 s per call: Claude's tool calls must stay short
      const end = Date.now() + Math.min(20, seconds || 10) * 1000;
      while (Date.now() < end) { if (!text || (await page.content()).includes(text)) return { done: 'seen' }; await page.waitForTimeout(250); }
      return { error: 'not seen in time' };
    },
    async ask_owner({ reason }) {
      // first call: hands the screen over and returns AT ONCE. Later calls wait up to 15 s for his Done.
      // The card on the device is a fixed native template; Claude's reason is shown only as a small note.
      const finished = (a) => { st.ownerResult = null; return a === 'stop' ? { error: 'the owner stopped Claude here' } : { done: 'the owner finished. Take a new snapshot.' }; };
      if (st.ownerResult) return finished(st.ownerResult);
      if (!st.human) {
        emit({ type: 'ask_owner', card: 'Claude needs you here. Tap DONE when finished.', note: String(reason || '').slice(0, 120) });
        st.ownerUntil = Infinity;
        st.human = new Promise((res) => { st.humanDone = (a) => { st.ownerResult = a; st.human = null; st.ownerUntil = 0; res(a); }; });
        return { with_owner: 'handed to the owner. Call ask_owner again to wait for his Done (each call waits up to 15 s).' };
      }
      const a = await Promise.race([st.human, new Promise((r) => setTimeout(() => r('wait'), 15_000))]);
      return a === 'wait' ? { waiting: 'still with the owner. Call ask_owner again (do not press anything).' } : finished(a);
    },
  };
  // Claude's own input opens a short window in which the network guard is active (and its events carry a tag)
  async function claudeInput(fn) { st.claudeUntil = Infinity; try { await fn(); await page.waitForTimeout(250); } finally { st.claudeUntil = Date.now() + 2000; } await page.waitForTimeout(150); }
  // append-only "what Claude did" list, each line chained to the one before by a hash (a later edit breaks the chain)
  function record(tool, args, result) {
    const prev = st.log.length ? st.log[st.log.length - 1].hash : 'start';
    const e = { t: Date.now(), tool, host: host(), ref: args && args.ref, ok: !(result && result.error) };
    st.log.push({ ...e, hash: sha(prev + JSON.stringify(e)) });
    if (st.log.length % anchorEvery === 0 || INPUT.includes(tool)) out({ anchor: { n: st.log.length, hash: st.log[st.log.length - 1].hash } });
  }
  const anchorEvery = 10; // every 10 lines the chain head is also sent to the relay, so a rewritten chain is caught
  const verifyLog = () => st.log.every((l, i) => { const { hash, ...e } = l; return hash === sha((i ? st.log[i - 1].hash : 'start') + JSON.stringify(e)); });
  // Claude's own taps carry a tag; any untagged touch is the owner's (he takes over)
  async function tap(x, y) { await page.mouse.move(x + (Math.random() * 2 - 1), y + (Math.random() * 2 - 1)); await page.mouse.down(); await page.waitForTimeout(40 + Math.random() * 60); await page.mouse.up(); }

  async function run(id, tool, args, grant) {
    if (st.done.has(id)) return st.done.get(id); // the same command again after a reconnect: same answer, never twice
    if (tool === '__approve') { // "Connect Claude to your Ronin?": the DEVICE signs the owner's Allow, so a hacked relay cannot fake it
      const text = `Connect Claude to your Ronin? Code ${args.code}. Devices: ${args.scope}`; // fixed native template
      const ok = !st.stopped && await approve(text);
      const issued = Date.now() + st.skew;
      return ok ? { ok, epoch: st.epoch, issued, sig: sign(['approve', args.nonce, args.client, args.redirect, args.challenge, args.scope, args.code, st.epoch, issued].join('|')) } : { ok: false };
    }
    if (tool === '__epoch') { // "Disconnect Claude" pressed on another device: signed by that device, the relay only carries it
      let ok = false; try { ok = !!peers[args.by] && crypto.verify('sha256', Buffer.from('epoch|' + args.epoch), peers[args.by], Buffer.from(args.sig, 'base64')); } catch (e) {}
      if (ok && args.epoch > st.epoch) st.epoch = args.epoch; return { ok };
    }
    if (tool === '__stop') { st.stopped = true; emit({ type: 'stopped-remotely', by: args.by }); return { ok: true }; }
    // the relay must attach the grant the owner SIGNED on one of his devices; old grants die when he disconnects Claude
    const g = grant; let gok = false;
    try { gok = !!g && !!peers[g.dev] && g.scope.split(', ').includes(name) && g.epoch >= st.epoch && crypto.verify('sha256', Buffer.from(['approve', g.nonce, g.client, g.redirect, g.challenge, g.scope, g.code, g.epoch, g.issued].join('|')), peers[g.dev], Buffer.from(g.sig, 'base64')); } catch (e) {}
    if (!gok) return { error: 'no valid owner grant for Claude on this device (Claude was disconnected, or the grant is forged). The owner must connect Claude again.' };
    const t = Date.now() + st.skew; // real app: wall clock + a monotonic "last seen" kept on disk
    if (t < st.lastSeen - 300_000) return { error: 'the device clock went backwards: Claude is stopped until the owner connects Claude again.' };
    if (t > g.issued + GRANT_HOURS * 3600_000) return { error: `the owner's grant is older than ${GRANT_HOURS} hours: he must connect Claude again (one press-and-hold).` };
    st.lastSeen = Math.max(st.lastSeen, t);
    if (st.stopped) return { error: 'the owner pressed STOP on this device. Claude must wait until he turns access on again.' };
    if (!st.access) return { error: `Claude access is OFF on the ${name} (owner's switch). Ask him to turn it on.` };
    if (Date.now() < st.ownerUntil && tool !== 'ask_owner') return { error: st.paused ? 'the owner took over the screen. Claude waits until he taps RESUME.' : 'the owner is using the screen right now: wait and try again' };
    if (st.busy) return { error: 'one action at a time on this device' };
    const today = new Date().toISOString().slice(0, 10); if (st.day !== today) { st.day = today; st.count = 0; }
    if (++st.count > st.budget) return { error: `daily limit of ${st.budget} Claude actions on the ${name} reached. The owner can raise it in Ronin.` };
    const f = tools[tool]; if (!f) return { error: 'unknown tool ' + tool };
    // money sites: Claude gets NO input tools at all (only snapshot / get_text / scroll / screenshot / read pages)
    if (INPUT.includes(tool) && mode(host()) === 'read') return { error: `${host()} is READ-ONLY for Claude (money / trading / bank site): no taps or typing at all. Tell the owner what to press, or ask_owner.` };
    // write-ahead journal: if Ronin died during this command, never run it again blindly
    if (st.journal.get(id) === 'started') { const at = st.lastTarget; if (at) st.blockedActs.add(at); return { uncertain: `the last ${tool} was interrupted (app restarted). It may or may not have happened. Take a snapshot and check the page; the same action stays blocked until the owner taps CHECKED.` }; }
    if (!READ_TOOLS.includes(tool) && tool !== 'open') {
      await helper(); const c = await page.evaluate(() => window.__roninAgent.challenge()).catch(() => '');
      if (c) return { error: `${c}: Claude does not solve human checks. Call ask_owner.` };
    }
    if (tool === 'ask_owner') { try { return await f(args || {}); } catch (e) { return { error: String(e.message || e).slice(0, 200) }; } } // never blocks the device
    st.busy = true;
    if (INPUT.includes(tool)) { st.journal.set(id, 'started'); st.lastTarget = null; } // real app: written to disk BEFORE the tap
    try { const r = await f(args || {});
      if (st.crashAfter === tool) { st.crashAfter = null; st.busy = false; ws.terminate(); return new Promise(() => {}); } // test: Ronin killed right after the tap
      st.journal.set(id, 'done'); record(tool, args, r); st.done.set(id, r); if (st.done.size > 100) st.done.delete(st.done.keys().next().value); return r; }
    catch (e) { return { error: String(e.message || e).slice(0, 200) }; }
    finally { st.busy = false; }
  }

  // every frame after the handshake carries a sequence number and an HMAC with the session key
  function out(obj) { if (!ws || ws.readyState !== 1 || !K) return; const f = JSON.stringify(obj); ws.send(JSON.stringify({ f, seq: ++seqOut, mac: mac(K, seqOut + '|' + f) })); }
  function connect() {
    return new Promise((resolve) => {
      const nonce = crypto.randomBytes(16).toString('hex'); st.relayOk = false; K = null; seqIn = 0; seqOut = 0;
      const eph = crypto.generateKeyPairSync('x25519'); const ephPub = eph.publicKey.export({ type: 'spki', format: 'der' }).toString('base64');
      ws = new WebSocket(url, { headers: { authorization: 'Device ' + key, 'x-ronin-nonce': nonce, 'x-ronin-ecdh': ephPub } });
      const sock = ws;
      ws.on('open', () => resolve());
      ws.on('message', async (m) => {
        let msg; try { msg = JSON.parse(m); } catch (e) { return; }
        // 1) the relay proves it is OUR relay (key pinned at pairing) and agrees a session key; until then nothing runs
        if (msg.relayProof) {
          let ok = false; try { ok = !!relayPub && !!msg.ecdh && crypto.verify('sha256', Buffer.from(['relay', nonce, ephPub, msg.ecdh].join('|')), relayPub, Buffer.from(msg.relayProof, 'base64')); } catch (e) {}
          st.relayOk = ok;
          if (!ok) { emit({ type: 'fake relay' }); alive = false; sock.close(); return; }
          const shared = crypto.diffieHellman({ privateKey: eph.privateKey, publicKey: crypto.createPublicKey({ key: Buffer.from(msg.ecdh, 'base64'), format: 'der', type: 'spki' }) });
          K = crypto.createHash('sha256').update(Buffer.concat([shared, Buffer.from(nonce)])).digest();
          out({ hello: { kind, app: 'Ronin', claude_access: st.access, foreground: true, rendering: true } });
          return;
        }
        if (!st.relayOk) { emit({ type: 'fake relay' }); alive = false; sock.close(); return; }
        // 2) every command must carry a valid HMAC and a NEW sequence number (no injected or replayed frames)
        if (!msg.f || !(msg.seq > seqIn) || msg.mac !== mac(K, msg.seq + '|' + msg.f)) { emit({ type: 'bad frame' }); return; }
        seqIn = msg.seq;
        const { id, tool, args, grant } = JSON.parse(msg.f); const result = await run(id, tool, args, grant);
        if (st.dropAfter === tool) { st.dropAfter = null; sock.terminate(); return; } // test: the network breaks before the answer
        if (sock === ws) out({ id, result });
      });
      ws.on('close', (c) => { emit({ type: 'closed', code: c }); if (alive && c !== 4001 && sock === ws) setTimeout(() => connect(), 300); }); // reconnect with back-off in the app
      ws.on('error', () => {});
    });
  }
  return {
    setPeers: (p) => { peers = p; }, connect, st, publicKey: me.publicKey.export({ type: 'spki', format: 'pem' }), verifyLog,
    owner: {
      done: () => st.humanDone && st.humanDone('done'), stop: () => st.humanDone && st.humanDone('stop'),
      setAccess: (on) => { st.access = on; }, stopAll: () => { st.stopped = true; }, resume: () => { st.stopped = false; },
      // untagged input = the owner: Claude PAUSES and stays paused until he taps the big RESUME (no silent auto-resume)
      touch: () => { st.ownerUntil = Infinity; st.paused = true; emit({ type: 'owner-touch' }); },
      resumeClaude: () => { st.ownerUntil = 0; st.paused = false; },
      allowRead: (h, p) => { (st.readPaths[h] = st.readPaths[h] || []).push(p); },
      crashAfter: (t) => { st.crashAfter = t; },
      checked: () => st.blockedActs.clear(),
      allowDigits: (h, field) => st.digitOk.add(h + '|' + field), // owner looked at the page after an "uncertain" and taps CHECKED
      // "Disconnect Claude": this device moves to a new epoch at once (no network needed) and tells the others, signed
      disconnectClaude: () => { st.epoch += 1; out({ disconnectClaude: true, epoch: st.epoch, sig: sign('epoch|' + st.epoch) }); },
      allowIds: (h) => st.allowIds.add(h), setBudget: (n) => { st.budget = n; }, setMode: (h, m) => { st.modes[h] = m; }, page,
    },
    drop: () => ws && ws.terminate(), // network lost (relay keeps the command and sends it again)
    tell: (msg) => out(msg), // owner's buttons that reach the relay (stop another device, disconnect Claude)
    close: () => { alive = false; ws && ws.close(); },
  };
}
module.exports = { createDevice };
