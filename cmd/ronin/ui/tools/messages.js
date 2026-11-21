// Ronin Messages: the phone's SMS app with the scam check built in. Every SMS is sorted into
// ✅ Important (banks, OTP, school, saved contacts), 👤 Unknown, 📢 Ads and ⛔ Scam; only Important rings.
// One row per company: all of "VM-HDFCBK-S", "AD-HDFCBK-S"... together. English or Hindi as the phone.
// The phone side (MessagesActivity.kt) answers through window.roninPhone; no SMS ever goes to the internet
// (only a company's website is asked once for its small icon).
(function () {
  'use strict';
  // the phone's own day / night (Ronin tells the page); the browser's switch does not change this
  document.documentElement.classList.toggle('light', window.__roninDark === false);
  const app = document.getElementById('app');
  const P = window.roninPhone;
  let seq = 0; const waits = {};
  const ask = (op, a) => new Promise(r => {
    if (!P) return r(null);
    const id = ++seq; waits[id] = r;
    P.postMessage(JSON.stringify(Object.assign({ op, id }, a || {})));
  });
  if (P) P.onmessage = e => {
    let m; try { m = JSON.parse(e.data); } catch (x) { return; }
    if (m.event === 'changed') { if (view === 'list') list(); else if (view === 'thread' && cur) thread(cur, true); return; }
    if (waits[m.id]) { waits[m.id](m.data); delete waits[m.id]; }
  };
  const h = (tag, attrs, ...kids) => {
    const e = document.createElement(tag);
    Object.entries(attrs || {}).forEach(([k, v]) => { if (k === 'on') Object.entries(v).forEach(([ev, f]) => e.addEventListener(ev, f)); else if (k === 'cls') e.className = v; else if (v != null && v !== false) e.setAttribute(k, v); });
    kids.flat().forEach(c => e.append(c && c.nodeType ? c : document.createTextNode(c == null ? '' : String(c))));
    return e;
  };
  // the phone's language (Ronin tells the page; Settings can change it)
  const LANG = window.__roninLang || ((navigator.language || 'en').toLowerCase().indexOf('hi') === 0 ? 'hi' : 'en');
  const L = (hi, en) => LANG === 'hi' ? hi : en;
  const TABS = [['work', '✅', L('काम के', 'Important')], ['unknown', '👤', L('अनजान', 'Unknown')], ['ads', '📢', L('विज्ञापन', 'Ads')], ['scam', '⛔', L('ठगी', 'Scam')]];
  // ads / loan offers are hidden (Settings can show them); ⭐ Saved = the family's own kept SMS
  const NAV = TABS.filter(t => t[0] !== 'ads').concat([['saved', '⭐', L('सहेजे', 'Saved')]]);
  // the newest OTP of the last 15 minutes, pinned on top of Important: one tap copies it
  function otpCard() {
    if (tab !== 'work') return '';
    const t = threads.filter(x => x.cat === 'work' && Date.now() - x.date < 15 * 60000 && otpOf(x.snippet)).sort((a, b) => b.date - a.date)[0];
    if (!t) return '';
    const code = otpOf(t.snippet);
    return h('div', { cls: 'otpcard', id: 'otpCard' }, h('div', { cls: 'mid' }, h('small', {}, L('🔑 नया OTP • ', '🔑 New OTP • ') + who(t) + ' • ' + when(t.date)), h('div', { cls: 'code' }, code)),
      h('button', { cls: 'pill', id: 'otpCopy', on: { click: async e => { const b = e.currentTarget; await ask('copy', { text: code }); b.classList.add('done'); b.textContent = L('✓ Copy हुआ', '✓ Copied'); } } }, L('📋 Copy', '📋 Copy')));
  }
  let savedIds = new Set();
  let tab = 'work', view = 'list', cur = null, threads = [], st = {};
  const when = ms => { const d = new Date(ms), n = new Date(); return d.toDateString() === n.toDateString() ? d.toTimeString().slice(0, 5) : d.getDate() + '/' + (d.getMonth() + 1); };
  // sender names ("VM-HDFCBK-S", "AD-HDFCBK-S") are one company: "HDFCBK"
  const isName = a => /[a-z]/i.test(a || '');
  const core = a => (a || '').toUpperCase().replace(/^[A-Z]{2}-/, '').replace(/-[PSTG]$/, '');
  // the company behind a sender code (senders.js): name, colour, short letters for the badge
  const SND = window.RONIN_SENDERS || { exact: {}, prefix: [] };
  const brand = a => {
    if (!isName(a)) return null;
    const c = core(a), e = SND.exact[c];
    if (e) return { name: e[0], color: e[1], short: e[2], site: e[3] || '', exact: true };
    // a prefix match (e.g. "SBICRD") may be another company of the group: its colour, its own code as name,
    // no website icon, and its own row
    const p = SND.prefix.find(x => c.indexOf(x[0]) === 0);
    return p ? { name: c, color: p[2], short: p[3], site: '', exact: false } : null;
  };
  const who = t => t.name || (brand(t.address) || {}).name || (isName(t.address) ? core(t.address) : t.address);
  // the company's own website icon (fetched once by the phone from that site, then kept)
  const icons = {};
  const icon = site => icons[site] || (icons[site] = ask('icon', { domain: site }).then(x => (typeof x === 'string' && x.indexOf('data:image/') === 0) ? x : null));
  // photo circles: a bright colour of its own per name with a white letter; a bare number its first digit, never a dark "+" / "9"
  // on slate (owner 21 Nov 2026: "गोल में... पूरा ब्लैक कलर में कर रखा है, ये गलत है")
  const AV = ['#2563eb', '#7c3aed', '#db2777', '#ea580c', '#0891b2', '#16a34a', '#b45309', '#4f46e5', '#0d9488', '#c026d3'];
  const avColor = k => { let x = 0; for (const c of String(k || '')) x = (x * 31 + c.codePointAt(0)) >>> 0; return AV[x % AV.length]; };
  const avText = (name, company) => { const n = String(name || '').trim(); if (!n) return '#';
    if (/^[+\d\s()-]+$/.test(n)) return (n.replace(/\D/g, '').slice(-10)[0] || '#'); // a number: its first digit, in white
    return company ? n.replace(/[^A-Za-z0-9]/g, '').slice(0, 2).toUpperCase() : [...n][0].toUpperCase(); };
  const badge = t => {
    const b = !t.name && brand(t.address);
    if (t.cat === 'scam') return h('div', { cls: 'av', style: 'background:#dc2626' }, '⛔');
    if (b) {
      const el = h('div', { cls: 'av brand', style: 'background:' + b.color + (b.short.length > 2 ? ';font-size:' + (b.short.length > 3 ? 12 : 14) + 'px' : '') }, b.short);
      if (b.site) icon(b.site).then(src => { if (src) { el.classList.add('pic'); el.style.background = '#fff'; el.replaceChildren(h('img', { src, alt: '' })); } });
      return el;
    }
    return h('div', { cls: 'av', style: 'background:' + avColor(t.address || who(t)) }, avText(who(t), !t.name && isName(t.address)));
  };
  // the OTP / code in a message (same rule as Otp.kt): shown big and blue, one tap copies only the digits
  // an OTP only when the SMS says OTP (smsfacts.js; same rule as Otp.kt)
  const otpOf = t => (window.RoninFacts ? window.RoninFacts.otp(t) : '');
  const URL_RE = /(https?:\/\/[^\s<>"']+|\b(?:[a-z0-9-]+\.)+[a-z]{2,}\/[^\s<>"']*)/gi;
  // the text of one SMS: links open in Ronin (it checks every site) except in the Scam box; the OTP is a copy button
  // the main point of an SMS (smsfacts.js): money coloured by what happened to it, PNR, delivery, expiry
  const FX = window.RoninFacts;
  function marked(str) {
    if (!FX || !str) return [str];
    const out = []; let at = 0;
    for (const a of FX.amounts(str)) { out.push(str.slice(at, a.start), h('b', { cls: 'hl ' + a.kind }, str.slice(a.start, a.end))); at = a.end; }
    out.push(str.slice(at));
    return out;
  }
  function factsLine(text, small) {
    const f = FX ? FX.facts(text, L) : [];
    if (!f.length) return '';
    return h('div', { cls: small ? 'facts small' : 'facts' }, (small ? f.slice(0, 2) : f).map(x => h('span', { cls: 'fact ' + x.kind }, x.text)));
  }
  function smsBody(text, links) {
    const out = [], code = otpOf(text);
    let rest = String(text || '');
    const parts = links ? rest.split(URL_RE) : [rest];
    parts.forEach((p, i) => {
      if (!p) return;
      if (links && i % 2 === 1) {
        const url = /^https?:/i.test(p) ? p.replace(/[.,)]+$/, '') : 'https://' + p.replace(/[.,)]+$/, '');
        out.push(h('a', { cls: 'lnk', href: '#', on: { click: e => { e.preventDefault(); ask('open', { url }); } } }, p));
        return;
      }
      if (code && p.indexOf(code) >= 0) {
        const at = p.indexOf(code);
        out.push(...marked(p.slice(0, at)), h('button', { cls: 'otp', 'data-otp': code, on: { click: async e => { e.stopPropagation(); await ask('copy', { text: code }); e.currentTarget.classList.add('done'); } } }, code, h('span', {}, ' 📋')), ...marked(p.slice(at + code.length)));
        return;
      }
      out.push(...marked(p));
    });
    return out;
  }
  // one row per company (or per person) in each tab: all of HDFC's SMS together
  function group(list) {
    const by = new Map();
    list.forEach(t => {
      const b = brand(t.address);
      const key = t.cat + '|' + (b && b.exact ? 'b:' + b.name : isName(t.address) ? core(t.address) : 'n' + t.thread);
      const g = by.get(key);
      if (!g) { by.set(key, Object.assign({}, t, { threads: [t.thread], addresses: [t.address] })); return; }
      g.threads.push(t.thread); g.addresses.push(t.address);
      g.unread = (g.unread || 0) + (t.unread || 0); g.count = (g.count || 0) + (t.count || 0);
      if (t.date > g.date) Object.assign(g, { thread: t.thread, address: t.address, snippet: t.snippet, date: t.date, sub: t.sub, name: t.name || g.name });
    });
    return [...by.values()].sort((a, b) => b.date - a.date);
  }

  function setup() {
    if (st.isDefault) return null;
    return h('div', { cls: 'card', id: 'setup' }, h('h2', {}, L('📩 Ronin Messages चालू करें', '📩 Turn on Ronin Messages')),
      h('p', { cls: 'note' }, L('चालू करने पर आपके SMS Ronin में आएँगे: ठगी वाले ⛔ में, विज्ञापन 📢 में अलग, और सिर्फ़ काम के SMS की घंटी बजेगी। Block किए नंबर / sender के SMS और calls आने बंद।',
        'Your SMS come into Ronin: scams go to ⛔, ads to 📢, and only important SMS ring. Blocked numbers / senders cannot message or call you.')),
      h('button', { cls: 'pill green', id: 'makeDefault', on: { click: makeDefault } }, L('✅ Ronin Messages चालू करें', '✅ Turn on Ronin Messages')),
      h('div', { id: 'restricted' }));
  }
  async function makeDefault() {
    const r = await ask('makeDefault');
    st = (await ask('state')) || st;
    if (r && r.ok) return list();
    const box = document.getElementById('restricted');
    if (box) box.replaceChildren(h('div', { cls: 'card' }, h('b', {}, L('Android ने अनुमति रोक दी (बाहर से install की app के लिए सुरक्षा)। एक बार यह करें:', 'Android blocked this (a safety rule for apps installed from outside the Play Store). Do this once:')),
      h('ol', {}, h('li', {}, L('नीचे "App info खोलें" दबाएँ', 'Tap "Open App info" below')), h('li', {}, L('ऊपर दाएँ ⋮ दबाएँ → "Allow restricted settings" → अपना PIN / fingerprint', 'Tap ⋮ at the top right → "Allow restricted settings" → your PIN / fingerprint')),
        h('li', {}, L('वापस आकर फिर "✅ Ronin Messages चालू करें" दबाएँ', 'Come back and tap "✅ Turn on Ronin Messages" again'))),
      h('button', { cls: 'pill blue', on: { click: () => ask('appInfo') } }, L('⚙ App info खोलें', '⚙ Open App info'))));
  }

  async function list() {
    if (tab === 'saved') return savedList();
    view = 'list'; cur = null;
    st = (await ask('state')) || {};
    threads = group((st.isDefault || st.read) ? ((await ask('threads')) || []) : []);
    const count = c => threads.filter(t => t.cat === c).reduce((n, t) => n + (t.unread ? 1 : 0), 0);
    app.replaceChildren(
      h('div', { cls: 'top' }, h('h1', {}, '📩 Ronin Messages'),
        // small: every unread SMS read at once (no need to open each one)
        threads.some(t => t.unread) ? h('button', { cls: 'mini', id: 'readAll', on: { click: async () => { await ask('readAll'); list(); } } }, L('✓ सब पढ़ लिया', '✓ Mark all read')) : '',
        h('button', { cls: 'pill', id: 'settings', 'aria-label': 'Settings', on: { click: settings } }, '⚙')),
      nav(count),
      setup() || '',
      otpCard(),
      tab === 'ads' ? h('p', { cls: 'note' }, L('📢 विज्ञापन SMS हमेशा के लिए रोकने का सरकारी तरीका: 1909 पर "BLOCK PROMO" भेजें (OTP / बैंक के SMS आते रहेंगे)।', '📢 To stop ad SMS for good (government service): send "BLOCK PROMO" to 1909. OTP and bank SMS keep coming.'), ' ', h('button', { cls: 'pill', on: { click: () => compose('1909', 'BLOCK PROMO') } }, L('1909 पर भेजें', 'Send to 1909'))) : '',
      tab === 'scam' ? h('p', { cls: 'note' }, L('⛔ ये SMS ठगी जैसे हैं। इनमें दिए link न खोलें, call न करें, पैसे / OTP न दें। शिकायत: 📞 1930, या sancharsaathi.gov.in (चक्षु)।', '⛔ These SMS look like scams. Do not open their links, do not call back, never give money or an OTP. Report: 📞 1930 or sancharsaathi.gov.in (Chakshu).')) : '',
      h('div', { id: 'list' }, (() => {
        const mine = threads.filter(t => t.cat === tab);
        if (!mine.length) return h('div', { cls: 'empty' }, st.isDefault || st.read ? L('यहाँ कोई संदेश नहीं', 'No messages here') : L('संदेश देखने के लिए ऊपर "चालू करें" दबाएँ', 'Tap "Turn on" above to see your messages'));
        return mine.map(t => h('div', { cls: 'th' + (t.cat === 'scam' ? ' scam' : '') + (t.unread ? ' unread' : ''), 'data-thread': t.thread, on: { click: () => thread(t) } },
          badge(t),
          h('div', { cls: 'mid' }, h('b', {}, who(t), t.cat !== 'work' ? h('span', { cls: 'tag ' + t.cat }, TABS.find(x => x[0] === t.cat)[2]) : ''), h('small', {}, t.snippet), t.cat !== 'scam' ? factsLine(t.snippet, true) : ''),
          h('div', { cls: 'tm' }, when(t.date), t.unread ? h('div', {}, h('span', { cls: 'dot' }, t.unread)) : '')));
      })()),
      // ✏ new message and the four boxes sit at the bottom, where the thumb is
      h('button', { cls: 'pill blue fab', id: 'new', on: { click: () => compose('', '') } }, L('✏ नया', '✏ New')));
  }

  function nav(count) {
    return h('nav', { cls: 'nav' }, NAV.map(([id, icon, name]) => h('button', { cls: tab === id ? 'on' : '', 'data-tab': id, on: { click: () => { tab = id; list(); } } },
      h('span', { cls: 'ic' }, icon), h('span', {}, name, count && count(id) ? h('b', { cls: 'n' }, count(id)) : ''))));
  }

  // ⭐ Saved: copies kept by Ronin; removed only here (never by deleting a conversation or the ad clean-up)
  async function savedList() {
    view = 'saved'; cur = null;
    const all = ((await ask('savedSms')) || []).slice().sort((a, b) => b.date - a.date);
    savedIds = new Set(all.map(m => m.key));
    app.replaceChildren(
      h('div', { cls: 'top' }, h('h1', {}, L('⭐ सहेजे संदेश', '⭐ Saved messages')), h('button', { cls: 'pill', id: 'settings', on: { click: settings } }, '⚙')),
      nav(null),
      h('p', { cls: 'note' }, L('यहाँ रखे SMS कहीं और से नहीं मिटते: बातचीत मिटाने या पुराने SMS अपने-आप हटने पर भी ये बने रहते हैं। हटाना हो तो यहीं से 🗑 दबाएँ।',
        'SMS kept here are never deleted from anywhere else: not when a conversation is deleted, not by any clean-up. To remove one, tap 🗑 here.')),
      h('div', { id: 'savedList', cls: 'msgs' }, all.length ? all.map(m => {
        const t = { address: m.address, name: '', cat: 'work' };
        return h('div', { cls: 'saved', 'data-saved': m.key },
          h('div', { cls: 'shead' }, badge(t), h('b', {}, who(t)), h('small', {}, when(m.date)),
            h('button', { cls: 'pill', 'data-unsave': m.key, on: { click: async () => {
              if (!confirm(L('यह सहेजा SMS हटा दें?', 'Remove this saved SMS?'))) return;
              await ask('unsaveSms', { key: m.key }); savedList();
            } } }, '🗑')),
          h('div', { cls: 'b in' }, smsBody(m.body, !(window.RoninScam && window.RoninScam.text(m.body).level))));
      }) : h('div', { cls: 'empty' }, L('अभी कुछ नहीं। किसी SMS के नीचे ☆ दबाकर उसे यहाँ रखें।', 'Nothing yet. Tap ☆ under any SMS to keep it here.'))));
  }

  function warnBox(level, reasons) {
    return h('div', { cls: 'warn' + (level === 'stop' ? '' : ' amber') }, h('b', {}, level === 'stop' ? L('⛔ यह ठगी का संदेश हो सकता है', '⛔ This may be a scam message') : L('⚠ सावधान: ठगी के संकेत', '⚠ Careful: signs of a scam')),
      h('div', {}, (reasons || []).slice(0, 3).join(' • ')),
      h('div', {}, L('इसमें दिया link न खोलें, call न करें, पैसे / OTP / PIN न दें। जानने वाले के नाम से हो तो उन्हें फ़ोन करके पक्का करें।', 'Do not open its link, do not call back, never give money / OTP / PIN. If it uses the name of someone you know, phone them yourself to check.')));
  }

  async function thread(t, again) {
    view = 'thread'; cur = t;
    // a company's SMS from all its sender names, oldest first
    const parts = await Promise.all((t.threads || [t.thread]).map(id => ask('messages', { thread: id })));
    savedIds = new Set(((await ask('savedSms')) || []).map(m => m.key));
    const keyOf = m => (m.address || t.address) + '|' + m.date + '|' + String(m.body || '').length;
    const msgs = [].concat(...parts.map(x => x || [])).sort((a, b) => a.date - b.date);
    // several OTPs: the newest one says "Latest", the older ones are dimmed (moves on when a new one comes)
    let latest = -1;
    msgs.forEach((m, i) => { if (m.type === 1 && otpOf(m.body)) latest = i; });
    const body = h('div', { cls: 'msgs', id: 'msgs' }, msgs.map((m, i) => h('div', {},
      m.type === 1 && m.level ? warnBox(m.level, m.reasons) : '',
      i === latest ? h('div', { cls: 'latest' }, L('🆕 सबसे नया OTP', '🆕 Latest OTP')) : '',
      h('div', { cls: 'b ' + (m.type === 1 ? 'in' : 'out') + (m.type === 1 && otpOf(m.body) && i !== latest ? ' oldotp' : ''), 'data-level': m.level || '' }, m.type === 1 && t.cat !== 'scam' && !m.level ? factsLine(m.body) : '', smsBody(m.body, t.cat !== 'scam' && !m.level),
        h('button', { cls: 'star' + (savedIds.has(keyOf(m)) ? ' on' : ''), 'data-star': m.id, title: L('सहेजें', 'Save'), on: { click: async e => {
          e.stopPropagation();
          const b = e.currentTarget, k = keyOf(m), on = savedIds.has(k);
          const all = (await ask(on ? 'unsaveSms' : 'saveSms', on ? { key: k } : { msg: { key: k, address: m.address || t.address, body: m.body, date: m.date } })) || [];
          savedIds = new Set(all.map(x => x.key)); b.classList.toggle('on', savedIds.has(k)); b.textContent = savedIds.has(k) ? '⭐' : '☆';
        } } }, savedIds.has(keyOf(m)) ? '⭐' : '☆'), h('small', {}, when(m.date) + (m.type === 5 ? L(' • ✖ नहीं गया', ' • ✖ not sent') : m.type === 4 ? L(' • भेज रहा...', ' • sending...') : ''))))));
    // a new SMS arriving while you type must not wipe what you are writing
    const old = again && document.getElementById('text');
    const box = h('textarea', { placeholder: st.isDefault ? L('संदेश लिखें...', 'Type a message...') : L('भेजने के लिए Ronin Messages चालू करें', 'Turn on Ronin Messages to send'), id: 'text' });
    if (old) box.value = old.value;
    if (!st.isDefault) box.disabled = true;
    const isNumber = /^\+?\d{7,}$/.test(t.address.replace(/[\s-]/g, ''));
    app.replaceChildren(
      h('div', { cls: 'top' }, h('button', { cls: 'pill', id: 'back', on: { click: list } }, L('← वापस', '← Back')), badge(t), h('h1', {}, who(t)),
        h('div', {}, isNumber ? h('button', { cls: 'pill', on: { click: () => ask('open', { url: 'tel:' + t.address }) } }, '📞') : '', ' ',
          h('button', { cls: 'pill', id: 'block', on: { click: () => block(t) } }, '🚫'), ' ',
          h('button', { cls: 'pill', id: 'del', on: { click: () => del(t) } }, '🗑'))),
      t.cat === 'scam' ? h('div', { cls: 'card' }, L('🆘 ठगी हो गई? ', '🆘 Lost money? '), h('a', { cls: 'pill red', href: 'tel:1930' }, '📞 1930'), ' ', h('button', { cls: 'pill', id: 'chakshu', on: { click: () => { const m = msgs.filter(x => x.type === 1).pop() || msgs[msgs.length - 1] || {}; ask('chakshu', { address: m.address || t.address, body: m.body || '', date: m.date || 0 }); } } }, L('चक्षु पर शिकायत', 'Report on Chakshu'))) : '',
      body,
      // a company sender ("HDFCBK") cannot get a reply: no send box there
      isName(t.address) ? h('p', { cls: 'note', style: 'text-align:center' }, L('इस sender को जवाब नहीं भेजा जा सकता।', 'You cannot reply to this sender.')) :
      h('div', { cls: 'compose' }, box, h('button', { cls: 'pill blue', id: 'send', on: { click: async () => {
        const text = box.value.trim(); if (!text || !st.isDefault) return;
        box.value = '';
        await ask('send', { to: t.address, body: text, sub: t.sub || -1 });
        setTimeout(() => { if (view === 'thread' && cur === t) thread(t, true); }, 600);
      } } }, L('भेजें', 'Send'))));
    if (!again) window.scrollTo(0, document.body.scrollHeight);
    if (old && document.activeElement !== box && old === document.activeElement) box.focus();
  }

  async function block(t) {
    if (!confirm(L(who(t) + ' को block करें? इनके SMS और calls नहीं आएँगे।', 'Block ' + who(t) + '? Their SMS and calls will stop.'))) return;
    let ok = true;
    for (const a of (t.addresses || [t.address])) ok = (await ask('block', { address: a })) && ok;
    alert(ok ? L('🚫 Block कर दिया', '🚫 Blocked') : L('Block नहीं हो पाया: पहले Ronin Messages चालू करें', 'Could not block: turn on Ronin Messages first'));
  }
  async function del(t) {
    if (!confirm(L('यह पूरी बातचीत हमेशा के लिए मिटा दें?', 'Delete this whole conversation for good?'))) return;
    for (const id of (t.threads || [t.thread])) await ask('delete', { thread: id });
    list();
  }

  function compose(to, text) {
    view = 'compose';
    const num = h('input', { cls: 'txt', id: 'to', placeholder: L('नंबर', 'Number'), inputmode: 'tel', value: to || '' });
    const box = h('textarea', { id: 'text', placeholder: L('संदेश लिखें...', 'Type a message...') }); box.value = text || '';
    const check = h('div');
    box.addEventListener('input', () => {
      // the same check for what you are about to send (e.g. a forwarded rumour)
      check.replaceChildren();
      const r = window.RoninScam && window.RoninScam.text(box.value);
      if (r && r.rumour) check.append(h('div', { cls: 'warn amber' }, L('ℹ इसमें "सबको भेजें / 100% सच" जैसी बातें हैं: आगे भेजने से पहले जाँच लें।', 'ℹ This says things like "send to everyone / 100% true": check it before forwarding.')));
    });
    app.replaceChildren(h('div', { cls: 'top' }, h('button', { cls: 'pill', id: 'back', on: { click: list } }, L('← वापस', '← Back')), h('h1', {}, L('✏ नया संदेश', '✏ New message')), h('span')),
      num, check,
      h('div', { cls: 'compose' }, box, h('button', { cls: 'pill blue', id: 'send', on: { click: async () => {
        if (!st.isDefault) { alert(L('भेजने के लिए पहले Ronin Messages चालू करें', 'Turn on Ronin Messages first to send')); return; }
        const n = num.value.trim(), t = box.value.trim();
        if (!n || !t) return;
        await ask('send', { to: n, body: t });
        const id = await ask('thread', { address: n });
        if (id > 0) thread({ thread: id, address: n, name: '', cat: 'work' }); else list();
      } } }, L('भेजें', 'Send'))));
    (to ? box : num).focus();
  }

  async function settings() {
    view = 'settings';
    const blocked = (await ask('blocked')) || [];
    app.replaceChildren(h('div', { cls: 'top' }, h('button', { cls: 'pill', id: 'back', on: { click: list } }, L('← वापस', '← Back')), h('h1', {}, '⚙ Settings'), h('span')),
      await langCard(),
      h('div', { cls: 'card' }, h('h2', {}, L('🚫 Block किए नंबर / sender (', '🚫 Blocked numbers / senders (') + blocked.length + ')'),
        blocked.length ? blocked.map(b => h('div', { cls: 'row' }, h('span', {}, b), h('button', { cls: 'pill', on: { click: async () => { await ask('unblock', { address: b }); settings(); } } }, L('हटाएँ', 'Unblock')))) : h('p', { cls: 'note' }, L('कोई नहीं', 'None'))),
      h('div', { cls: 'card', id: 'otpCleanCard' }, h('h2', {}, L('🔑 पुराने OTP अपने-आप मिटें', '🔑 Old OTPs delete themselves')),
        h('p', { cls: 'note' }, L('Login / payment वाले OTP 24 घंटे बाद मिट जाते हैं। Delivery / gas (DAC) वाले OTP तब तक रहते हैं जब तक उसी company का उसी order / booking नंबर वाला "delivered" SMS न आ जाए (उसके 24 घंटे बाद मिटते हैं)। बैंक का हिसाब, Ticket / PNR, फ़ीस और ⭐ सहेजे SMS कभी नहीं मिटते।',
          'Login / payment OTPs are deleted after 24 hours. Delivery / gas (DAC) OTPs stay until the same company sends a "delivered" SMS with the same order / booking number (then 24 hours later). Bank records, tickets / PNR, fees and ⭐ saved SMS are never deleted.')),
        h('button', { cls: 'pill' + (st.otpClean ? ' green' : ''), id: 'otpClean', on: { click: async () => { await ask('setOtpClean', { on: !st.otpClean }); st.otpClean = !st.otpClean; settings(); } } }, st.otpClean ? L('✔ चालू (बंद करें)', '✔ On (turn off)') : L('चालू करें', 'Turn on'))),
      h('div', { cls: 'card', id: 'adsCard' }, h('h2', {}, L('📢 विज्ञापन / loan वाले SMS', '📢 Ads / loan offer SMS')),
        h('p', { cls: 'note' }, L('ये छिपे रहते हैं, घंटी नहीं बजती, और 30 दिन बाद अपने-आप मिट जाते हैं। कोई काम का SMS गलती से यहाँ गया हो तो देख लें।', 'They stay hidden, never ring, and are deleted by themselves after 30 days. Look here if an important SMS went missing.')),
        h('button', { cls: 'pill', id: 'showAds', on: { click: () => { tab = 'ads'; list(); } } }, L('📢 छिपे विज्ञापन देखें', '📢 Show hidden ads'))),
      h('div', { cls: 'card' }, h('h2', {}, L('📢 विज्ञापन SMS बंद करें (सरकारी तरीका)', '📢 Stop ad SMS (government service)')),
        h('p', { cls: 'note' }, L('TRAI: 1909 पर "BLOCK PROMO" भेजने से सभी promotional SMS / calls रुकते हैं; OTP, बैंक और सरकारी SMS आते रहते हैं।', 'TRAI: sending "BLOCK PROMO" to 1909 stops all promotional SMS / calls; OTP, bank and government SMS keep coming.')),
        h('button', { cls: 'pill', on: { click: () => compose('1909', 'BLOCK PROMO') } }, L('1909 पर BLOCK PROMO', 'BLOCK PROMO to 1909'))),
      h('div', { cls: 'card' }, h('h2', {}, L('↩ Google Messages वापस', '↩ Back to Google Messages')),
        h('p', { cls: 'note' }, L('Ronin Messages में MMS (फ़ोटो वाले संदेश) और Google की RCS chat नहीं दिखती। कभी भी पुरानी SMS app वापस कर सकते हैं: Default apps → SMS app।', 'Ronin Messages does not show MMS (picture messages) or Google RCS chat. You can switch back to your old SMS app any time: Default apps → SMS app.')),
        h('button', { cls: 'pill', on: { click: () => ask('defaultApps') } }, L('Default apps खोलें', 'Open Default apps'))));
  }

  // Language: the phone's own (default), or always English / always Hindi
  async function langCard() {
    const cur = (await ask('lang')) || 'auto';
    const opt = (v, name) => h('button', { cls: 'pill' + (cur === v ? ' blue' : ''), 'data-lang': v, on: { click: () => ask('setLang', { value: v }) } }, name);
    return h('div', { cls: 'card', id: 'langCard' }, h('h2', {}, '🌐 Language / भाषा'),
      h('div', { cls: 'tabs' }, opt('auto', L('फ़ोन जैसी', 'Same as phone')), opt('en', 'English'), opt('hi', 'हिंदी')));
  }

  // a conversation asked for from outside ("sms:9876...", a tapped notification)
  window.__roninOpen = async q => {
    const p = new URLSearchParams(q), to = p.get('to') || '', body = p.get('body') || '';
    await list();
    // sender names ("JD-SBIOTP") compare as names, phone numbers by their last 10 digits
    const core = a => a.toUpperCase().replace(/^[A-Z]{2}-/, '').replace(/-[PSTG]$/, '');
    const same = a => /[a-z]/i.test(to) ? core(a) === core(to) : a.replace(/\D/g, '').slice(-10) === to.replace(/\D/g, '').slice(-10);
    const t = to && threads.find(x => (x.addresses || [x.address]).some(same));
    if (t && !body) thread(t); else compose(to, body);
  };
  // the phone's Back: inside the app first (conversation -> list -> Important), then out of the app
  window.__roninBack = () => {
    if (view !== 'list' && view !== 'saved') { list(); return true; }
    if (tab !== 'work') { tab = 'work'; list(); return true; }
    return false;
  };
  window.__roninMessages = { list: () => threads, view: () => view }; // for the tests
  if (location.hash.length > 1) window.__roninOpen(location.hash.slice(1)); else list();
})();
