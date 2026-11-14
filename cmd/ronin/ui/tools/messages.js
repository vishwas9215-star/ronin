// Ronin Messages: the phone's SMS app with the scam check built in. Every SMS is sorted into
// ✅ Important (banks, OTP, school, saved contacts), 👤 Unknown, 📢 Ads and ⛔ Scam; only Important rings.
// One row per company: all of "VM-HDFCBK-S", "AD-HDFCBK-S"... together. English or Hindi as the phone.
// The phone side (MessagesActivity.kt) answers through window.roninPhone; nothing goes to the internet.
(function () {
  'use strict';
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
  let tab = 'work', view = 'list', cur = null, threads = [], st = {};
  const when = ms => { const d = new Date(ms), n = new Date(); return d.toDateString() === n.toDateString() ? d.toTimeString().slice(0, 5) : d.getDate() + '/' + (d.getMonth() + 1); };
  // sender names ("VM-HDFCBK-S", "AD-HDFCBK-S") are one company: "HDFCBK"
  const isName = a => /[a-z]/i.test(a || '');
  const core = a => (a || '').toUpperCase().replace(/^[A-Z]{2}-/, '').replace(/-[PSTG]$/, '');
  const who = t => t.name || (isName(t.address) ? core(t.address) : t.address);
  // one row per company (or per person) in each tab: all of HDFC's SMS together
  function group(list) {
    const by = new Map();
    list.forEach(t => {
      const key = t.cat + '|' + (isName(t.address) ? core(t.address) : 'n' + t.thread);
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
    view = 'list'; cur = null;
    st = (await ask('state')) || {};
    threads = group((st.isDefault || st.read) ? ((await ask('threads')) || []) : []);
    const count = c => threads.filter(t => t.cat === c).reduce((n, t) => n + (t.unread ? 1 : 0), 0);
    app.replaceChildren(
      h('div', { cls: 'top' }, h('h1', {}, '📩 Ronin Messages'), h('button', { cls: 'pill', id: 'settings', 'aria-label': 'Settings', on: { click: settings } }, '⚙')),
      setup() || '',
      tab === 'ads' ? h('p', { cls: 'note' }, L('📢 विज्ञापन SMS हमेशा के लिए रोकने का सरकारी तरीका: 1909 पर "BLOCK PROMO" भेजें (OTP / बैंक के SMS आते रहेंगे)।', '📢 To stop ad SMS for good (government service): send "BLOCK PROMO" to 1909. OTP and bank SMS keep coming.'), ' ', h('button', { cls: 'pill', on: { click: () => compose('1909', 'BLOCK PROMO') } }, L('1909 पर भेजें', 'Send to 1909'))) : '',
      tab === 'scam' ? h('p', { cls: 'note' }, L('⛔ ये SMS ठगी जैसे हैं। इनमें दिए link न खोलें, call न करें, पैसे / OTP न दें। शिकायत: 📞 1930, या sancharsaathi.gov.in (चक्षु)।', '⛔ These SMS look like scams. Do not open their links, do not call back, never give money or an OTP. Report: 📞 1930 or sancharsaathi.gov.in (Chakshu).')) : '',
      h('div', { id: 'list' }, (() => {
        const mine = threads.filter(t => t.cat === tab);
        if (!mine.length) return h('div', { cls: 'empty' }, st.isDefault || st.read ? L('यहाँ कोई संदेश नहीं', 'No messages here') : L('संदेश देखने के लिए ऊपर "चालू करें" दबाएँ', 'Tap "Turn on" above to see your messages'));
        return mine.map(t => h('div', { cls: 'th', 'data-thread': t.thread, on: { click: () => thread(t) } },
          h('div', { cls: 'av', style: t.cat === 'scam' ? 'background:#7f1d1d' : '' }, (who(t)[0] || '?').toUpperCase()),
          h('div', { cls: 'mid' }, h('b', {}, who(t), t.cat !== 'work' ? h('span', { cls: 'tag ' + t.cat }, TABS.find(x => x[0] === t.cat)[2]) : ''), h('small', {}, t.snippet)),
          h('div', { cls: 'tm' }, when(t.date), t.unread ? h('div', {}, h('span', { cls: 'dot' }, t.unread)) : '')));
      })()),
      // ✏ new message and the four boxes sit at the bottom, where the thumb is
      h('button', { cls: 'pill blue fab', id: 'new', on: { click: () => compose('', '') } }, L('✏ नया', '✏ New')),
      h('nav', { cls: 'nav' }, TABS.map(([id, icon, name]) => h('button', { cls: tab === id ? 'on' : '', 'data-tab': id, on: { click: () => { tab = id; list(); } } },
        h('span', { cls: 'ic' }, icon), h('span', {}, name + (count(id) ? ' (' + count(id) + ')' : ''))))));
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
    const msgs = [].concat(...parts.map(x => x || [])).sort((a, b) => a.date - b.date);
    const body = h('div', { cls: 'msgs', id: 'msgs' }, msgs.map(m => h('div', {},
      m.type === 1 && m.level ? warnBox(m.level, m.reasons) : '',
      h('div', { cls: 'b ' + (m.type === 1 ? 'in' : 'out'), 'data-level': m.level || '' }, m.body, h('small', {}, when(m.date) + (m.type === 5 ? L(' • ✖ नहीं गया', ' • ✖ not sent') : m.type === 4 ? L(' • भेज रहा...', ' • sending...') : ''))))));
    // a new SMS arriving while you type must not wipe what you are writing
    const old = again && document.getElementById('text');
    const box = h('textarea', { placeholder: st.isDefault ? L('संदेश लिखें...', 'Type a message...') : L('भेजने के लिए Ronin Messages चालू करें', 'Turn on Ronin Messages to send'), id: 'text' });
    if (old) box.value = old.value;
    if (!st.isDefault) box.disabled = true;
    const isNumber = /^\+?\d{7,}$/.test(t.address.replace(/[\s-]/g, ''));
    app.replaceChildren(
      h('div', { cls: 'top' }, h('button', { cls: 'pill', id: 'back', on: { click: list } }, L('← वापस', '← Back')), h('h1', {}, who(t)),
        h('div', {}, isNumber ? h('button', { cls: 'pill', on: { click: () => ask('open', { url: 'tel:' + t.address }) } }, '📞') : '', ' ',
          h('button', { cls: 'pill', id: 'block', on: { click: () => block(t) } }, '🚫'), ' ',
          h('button', { cls: 'pill', id: 'del', on: { click: () => del(t) } }, '🗑'))),
      t.cat === 'scam' ? h('div', { cls: 'card' }, L('🆘 ठगी हो गई? ', '🆘 Lost money? '), h('a', { cls: 'pill red', href: 'tel:1930' }, '📞 1930'), ' ', h('button', { cls: 'pill', on: { click: () => ask('open', { url: 'https://sancharsaathi.gov.in/sfc/' }) } }, L('चक्षु पर शिकायत', 'Report on Chakshu'))) : '',
      body,
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
    const watcher = await ask('watcher');
    app.replaceChildren(h('div', { cls: 'top' }, h('button', { cls: 'pill', id: 'back', on: { click: list } }, L('← वापस', '← Back')), h('h1', {}, '⚙ Settings'), h('span')),
      await langCard(),
      h('div', { cls: 'card' }, h('h2', {}, L('🔔 WhatsApp संदेश भी जाँचें', '🔔 Check WhatsApp messages too')),
        h('p', { cls: 'note' }, L('किसी जानने वाले का WhatsApp hack होकर "पैसे की ज़रूरत है, इस QR पर भेजो" आए, तो Ronin तुरंत चेतावनी देगा। जाँच इसी phone पर होती है, कुछ भी बाहर नहीं जाता।',
          'If a friend\'s hacked WhatsApp asks "I need money, pay this QR", Ronin warns you at once. The check runs on this phone; nothing is sent anywhere.')),
        watcher ? h('b', {}, L('✔ चालू है', '✔ On')) : h('div', {}, h('ol', {}, h('li', {}, L('नीचे बटन दबाएँ, list में "Ronin" चालू करें', 'Tap the button below and turn on "Ronin" in the list')), h('li', {}, L('न चले तो पहले App info → ⋮ → Allow restricted settings', 'If it will not turn on: App info → ⋮ → Allow restricted settings first'))),
          h('button', { cls: 'pill blue', on: { click: () => ask('notifyAccess') } }, L('🔔 चालू करें', '🔔 Turn on')), ' ', h('button', { cls: 'pill', on: { click: () => ask('appInfo') } }, '⚙ App info'))),
      h('div', { cls: 'card' }, h('h2', {}, L('🚫 Block किए नंबर / sender (', '🚫 Blocked numbers / senders (') + blocked.length + ')'),
        blocked.length ? blocked.map(b => h('div', { cls: 'row' }, h('span', {}, b), h('button', { cls: 'pill', on: { click: async () => { await ask('unblock', { address: b }); settings(); } } }, L('हटाएँ', 'Unblock')))) : h('p', { cls: 'note' }, L('कोई नहीं', 'None'))),
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
    const t = to && threads.find(x => /[a-z]/i.test(to) ? core(x.address) === core(to) : x.address.replace(/\D/g, '').slice(-10) === to.replace(/\D/g, '').slice(-10));
    if (t && !body) thread(t); else compose(to, body);
  };
  window.__roninMessages = { list: () => threads, view: () => view }; // for the tests
  if (location.hash.length > 1) window.__roninOpen(location.hash.slice(1)); else list();
})();
