// Ronin Messages: the phone's SMS app with the scam check built in. Every SMS is sorted into
// ✅ काम के (banks, OTP, school, saved contacts), 👤 अनजान, 📢 विज्ञापन and ⛔ ठगी; only काम के ring.
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
  const TABS = [['work', '✅ काम के'], ['unknown', '👤 अनजान'], ['ads', '📢 विज्ञापन'], ['scam', '⛔ ठगी']];
  let tab = 'work', view = 'list', cur = null, threads = [], st = {};
  const when = ms => { const d = new Date(ms), n = new Date(); return d.toDateString() === n.toDateString() ? d.toTimeString().slice(0, 5) : d.getDate() + '/' + (d.getMonth() + 1); };
  const who = t => t.name || t.address;

  function setup() {
    if (st.isDefault) return null;
    return h('div', { cls: 'card', id: 'setup' }, h('h2', {}, '📩 Ronin Messages चालू करें'),
      h('p', { cls: 'note' }, 'चालू करने पर आपके SMS Ronin में आएँगे: ठगी वाले ⛔ में, विज्ञापन 📢 में अलग, और सिर्फ़ काम के SMS की घंटी बजेगी। Block किए नंबर / sender के SMS और calls आने बंद।'),
      h('button', { cls: 'pill green', id: 'makeDefault', on: { click: makeDefault } }, '✅ Ronin Messages चालू करें'),
      h('div', { id: 'restricted' }));
  }
  async function makeDefault() {
    const r = await ask('makeDefault');
    st = (await ask('state')) || st;
    if (r && r.ok) return list();
    const box = document.getElementById('restricted');
    if (box) box.replaceChildren(h('div', { cls: 'card' }, h('b', {}, 'Android ने अनुमति रोक दी (बाहर से install की app के लिए सुरक्षा)। एक बार यह करें:'),
      h('ol', {}, h('li', {}, 'नीचे "App info खोलें" दबाएँ'), h('li', {}, 'ऊपर दाएँ ⋮ दबाएँ → "Allow restricted settings" → अपना PIN / fingerprint'),
        h('li', {}, 'वापस आकर फिर "✅ Ronin Messages चालू करें" दबाएँ')),
      h('button', { cls: 'pill blue', on: { click: () => ask('appInfo') } }, '⚙ App info खोलें')));
  }

  async function list() {
    view = 'list'; cur = null;
    st = (await ask('state')) || {};
    threads = (st.isDefault || st.read) ? ((await ask('threads')) || []) : [];
    const count = c => threads.filter(t => t.cat === c).reduce((n, t) => n + (t.unread ? 1 : 0), 0);
    app.replaceChildren(
      h('div', { cls: 'top' }, h('h1', {}, '📩 Ronin Messages'),
        h('div', {}, h('button', { cls: 'pill blue', id: 'new', on: { click: () => compose('', '') } }, '✏ नया'), ' ', h('button', { cls: 'pill', on: { click: settings } }, '⚙'))),
      setup() || '',
      h('div', { cls: 'tabs' }, TABS.map(([id, name]) => h('button', { cls: 'pill' + (tab === id ? ' on' : ''), 'data-tab': id, on: { click: () => { tab = id; list(); } } }, name + (count(id) ? ' (' + count(id) + ')' : '')))),
      tab === 'ads' ? h('p', { cls: 'note' }, '📢 विज्ञापन SMS हमेशा के लिए रोकने का सरकारी तरीका: 1909 पर "BLOCK PROMO" भेजें (OTP / बैंक के SMS आते रहेंगे)।', ' ', h('button', { cls: 'pill', on: { click: () => compose('1909', 'BLOCK PROMO') } }, '1909 पर भेजें')) : '',
      tab === 'scam' ? h('p', { cls: 'note' }, '⛔ ये SMS ठगी जैसे हैं। इनमें दिए link न खोलें, call न करें, पैसे / OTP न दें। शिकायत: 📞 1930, या sancharsaathi.gov.in (चक्षु)।') : '',
      h('div', { id: 'list' }, (() => {
        const mine = threads.filter(t => t.cat === tab);
        if (!mine.length) return h('div', { cls: 'empty' }, st.isDefault || st.read ? 'यहाँ कोई संदेश नहीं' : 'संदेश देखने के लिए ऊपर "चालू करें" दबाएँ');
        return mine.map(t => h('div', { cls: 'th', 'data-thread': t.thread, on: { click: () => thread(t) } },
          h('div', { cls: 'av', style: t.cat === 'scam' ? 'background:#7f1d1d' : '' }, (who(t)[0] || '?').toUpperCase()),
          h('div', { cls: 'mid' }, h('b', {}, who(t), t.cat !== 'work' ? h('span', { cls: 'tag ' + t.cat }, t.cat === 'scam' ? 'ठगी' : t.cat === 'ads' ? 'विज्ञापन' : 'अनजान') : ''), h('small', {}, t.snippet)),
          h('div', { cls: 'tm' }, when(t.date), t.unread ? h('div', {}, h('span', { cls: 'dot' }, t.unread)) : '')));
      })()));
  }

  function warnBox(level, reasons) {
    return h('div', { cls: 'warn' + (level === 'stop' ? '' : ' amber') }, h('b', {}, level === 'stop' ? '⛔ यह ठगी का संदेश हो सकता है' : '⚠ सावधान: ठगी के संकेत'),
      h('div', {}, (reasons || []).slice(0, 3).join(' • ')),
      h('div', {}, 'इसमें दिया link न खोलें, call न करें, पैसे / OTP / PIN न दें। जानने वाले के नाम से हो तो उन्हें फ़ोन करके पक्का करें।'));
  }

  async function thread(t, again) {
    view = 'thread'; cur = t;
    const msgs = ((await ask('messages', { thread: t.thread })) || []).slice().reverse();
    const body = h('div', { cls: 'msgs', id: 'msgs' }, msgs.map(m => h('div', {},
      m.type === 1 && m.level ? warnBox(m.level, m.reasons) : '',
      h('div', { cls: 'b ' + (m.type === 1 ? 'in' : 'out'), 'data-level': m.level || '' }, m.body, h('small', {}, when(m.date) + (m.type === 5 ? ' • ✖ नहीं गया' : m.type === 4 ? ' • भेज रहा...' : ''))))));
    // a new SMS arriving while you type must not wipe what you are writing
    const old = again && document.getElementById('text');
    const box = h('textarea', { placeholder: st.isDefault ? 'संदेश लिखें...' : 'भेजने के लिए Ronin Messages चालू करें', id: 'text' });
    if (old) box.value = old.value;
    if (!st.isDefault) box.disabled = true;
    const isNumber = /^\+?\d{7,}$/.test(t.address.replace(/[\s-]/g, ''));
    app.replaceChildren(
      h('div', { cls: 'top' }, h('button', { cls: 'pill', on: { click: list } }, '← वापस'), h('h1', {}, who(t)),
        h('div', {}, isNumber ? h('button', { cls: 'pill', on: { click: () => ask('open', { url: 'tel:' + t.address }) } }, '📞') : '', ' ',
          h('button', { cls: 'pill', id: 'block', on: { click: () => block(t) } }, '🚫'), ' ',
          h('button', { cls: 'pill', id: 'del', on: { click: () => del(t) } }, '🗑'))),
      t.cat === 'scam' ? h('div', { cls: 'card' }, '🆘 ठगी हो गई? ', h('a', { cls: 'pill red', href: 'tel:1930' }, '📞 1930'), ' ', h('button', { cls: 'pill', on: { click: () => ask('open', { url: 'https://sancharsaathi.gov.in/sfc/' }) } }, 'चक्षु पर शिकायत')) : '',
      body,
      h('div', { cls: 'compose' }, box, h('button', { cls: 'pill blue', id: 'send', on: { click: async () => {
        const text = box.value.trim(); if (!text || !st.isDefault) return;
        box.value = '';
        await ask('send', { to: t.address, body: text, sub: t.sub || -1 });
        setTimeout(() => { if (view === 'thread' && cur === t) thread(t, true); }, 600);
      } } }, 'भेजें')));
    if (!again) window.scrollTo(0, document.body.scrollHeight);
    if (old && document.activeElement !== box && old === document.activeElement) box.focus();
  }

  async function block(t) {
    if (!confirm(who(t) + ' को block करें? इनके SMS और calls नहीं आएँगे।')) return;
    const ok = await ask('block', { address: t.address });
    alert(ok ? '🚫 Block कर दिया' : 'Block नहीं हो पाया: पहले Ronin Messages चालू करें');
  }
  async function del(t) {
    if (!confirm('यह पूरी बातचीत हमेशा के लिए मिटा दें?')) return;
    await ask('delete', { thread: t.thread });
    list();
  }

  function compose(to, text) {
    view = 'compose';
    const num = h('input', { cls: 'txt', id: 'to', placeholder: 'नंबर', inputmode: 'tel', value: to || '' });
    const box = h('textarea', { id: 'text', placeholder: 'संदेश लिखें...' }); box.value = text || '';
    const check = h('div');
    box.addEventListener('input', () => {
      // the same check for what you are about to send (e.g. a forwarded rumour)
      check.replaceChildren();
      const r = window.RoninScam && window.RoninScam.text(box.value);
      if (r && r.rumour) check.append(h('div', { cls: 'warn amber' }, 'ℹ इसमें "सबको भेजें / 100% सच" जैसी बातें हैं: आगे भेजने से पहले जाँच लें।'));
    });
    app.replaceChildren(h('div', { cls: 'top' }, h('button', { cls: 'pill', on: { click: list } }, '← वापस'), h('h1', {}, '✏ नया संदेश'), h('span')),
      num, check,
      h('div', { cls: 'compose' }, box, h('button', { cls: 'pill blue', id: 'send', on: { click: async () => {
        if (!st.isDefault) { alert('भेजने के लिए पहले Ronin Messages चालू करें'); return; }
        const n = num.value.trim(), t = box.value.trim();
        if (!n || !t) return;
        await ask('send', { to: n, body: t });
        const id = await ask('thread', { address: n });
        if (id > 0) thread({ thread: id, address: n, name: '', cat: 'work' }); else list();
      } } }, 'भेजें')));
    (to ? box : num).focus();
  }

  async function settings() {
    view = 'settings';
    const blocked = (await ask('blocked')) || [];
    const watcher = await ask('watcher');
    app.replaceChildren(h('div', { cls: 'top' }, h('button', { cls: 'pill', on: { click: list } }, '← वापस'), h('h1', {}, '⚙ Settings'), h('span')),
      h('div', { cls: 'card' }, h('h2', {}, '🔔 WhatsApp संदेश भी जाँचें'),
        h('p', { cls: 'note' }, 'किसी जानने वाले का WhatsApp hack होकर "पैसे की ज़रूरत है, इस QR पर भेजो" आए, तो Ronin तुरंत चेतावनी देगा। जाँच इसी phone पर होती है, कुछ भी बाहर नहीं जाता।'),
        watcher ? h('b', {}, '✔ चालू है') : h('div', {}, h('ol', {}, h('li', {}, 'नीचे बटन दबाएँ, list में "Ronin" चालू करें'), h('li', {}, 'न चले तो पहले App info → ⋮ → Allow restricted settings')),
          h('button', { cls: 'pill blue', on: { click: () => ask('notifyAccess') } }, '🔔 चालू करें'), ' ', h('button', { cls: 'pill', on: { click: () => ask('appInfo') } }, '⚙ App info'))),
      h('div', { cls: 'card' }, h('h2', {}, '🚫 Block किए नंबर / sender (' + blocked.length + ')'),
        blocked.length ? blocked.map(b => h('div', { cls: 'row' }, h('span', {}, b), h('button', { cls: 'pill', on: { click: async () => { await ask('unblock', { address: b }); settings(); } } }, 'हटाएँ'))) : h('p', { cls: 'note' }, 'कोई नहीं')),
      h('div', { cls: 'card' }, h('h2', {}, '📢 विज्ञापन SMS बंद करें (सरकारी तरीका)'),
        h('p', { cls: 'note' }, 'TRAI: 1909 पर "BLOCK PROMO" भेजने से सभी promotional SMS / calls रुकते हैं; OTP, बैंक और सरकारी SMS आते रहते हैं।'),
        h('button', { cls: 'pill', on: { click: () => compose('1909', 'BLOCK PROMO') } }, '1909 पर BLOCK PROMO')),
      h('div', { cls: 'card' }, h('h2', {}, '↩ Google Messages वापस'),
        h('p', { cls: 'note' }, 'Ronin Messages में MMS (फ़ोटो वाले संदेश) और Google की RCS chat नहीं दिखती। कभी भी पुरानी SMS app वापस कर सकते हैं: Default apps → SMS app।'),
        h('button', { cls: 'pill', on: { click: () => ask('defaultApps') } }, 'Default apps खोलें')));
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
