// Ronin Calls: keypad, recent calls (unknown / foreign / telemarketing callers marked), contacts.
// The phone side (CallsActivity, Calls.kt) answers through window.roninPhone. Calls themselves are
// placed by Android (112 and other emergency numbers always work).
(function () {
  'use strict';
  const app = document.getElementById('app');
  const P = window.roninPhone;
  let seq = 0; const waits = {};
  const ask = (op, a) => new Promise(r => { if (!P) return r(null); const id = ++seq; waits[id] = r; P.postMessage(JSON.stringify(Object.assign({ op, id }, a || {}))); });
  if (P) P.onmessage = e => { let m; try { m = JSON.parse(e.data); } catch (x) { return; } if (waits[m.id]) { waits[m.id](m.data); delete waits[m.id]; } };
  const h = (tag, attrs, ...kids) => {
    const e = document.createElement(tag);
    Object.entries(attrs || {}).forEach(([k, v]) => { if (k === 'on') Object.entries(v).forEach(([ev, f]) => e.addEventListener(ev, f)); else if (k === 'cls') e.className = v; else if (v != null && v !== false) e.setAttribute(k, v); });
    kids.flat().forEach(c => e.append(c && c.nodeType ? c : document.createTextNode(c == null ? '' : String(c))));
    return e;
  };
  let tab = 'pad', st = {}, number = '';
  const when = ms => { const d = new Date(ms), n = new Date(); return d.toDateString() === n.toDateString() ? d.toTimeString().slice(0, 5) : d.getDate() + '/' + (d.getMonth() + 1); };
  const TYPE = { 1: '↙ आई', 2: '↗ की', 3: '✖ छूटी', 5: '⛔ काटी', 6: '🚫 रोकी' };
  // codes with * or # (e.g. *401*...) can send your calls and OTPs to someone else: ask first
  const call = n => {
    if (/[*#]/.test(n) && !confirm('⚠ सावधान: यह code आपकी calls / OTP किसी दूसरे नंबर पर भेज सकता है (ठगी का तरीका)। किसी के कहने पर dial न करें।\n\nफिर भी dial करें?')) return null;
    return ask('call', { number: n });
  };

  function top() {
    return [h('div', { cls: 'top' }, h('h1', {}, '📞 Ronin Calls'), h('button', { cls: 'pill', on: { click: settings } }, '⚙')),
      st.isDefault ? '' : h('div', { cls: 'card', id: 'setup' }, h('h2', {}, '📞 Ronin Calls चालू करें'),
        h('p', { cls: 'note' }, 'चालू करने पर calls Ronin की बड़ी screen पर आएँगी, अनजान / विदेशी / telemarketing नंबर पर चेतावनी दिखेगी, और block किए नंबर की call नहीं आएगी। 112 जैसी emergency calls हमेशा चलेंगी।'),
        h('button', { cls: 'pill green', id: 'makeDefault', on: { click: makeDefault } }, '✅ Ronin Calls चालू करें'), h('div', { id: 'restricted' })),
      st.inCall ? h('button', { cls: 'pill green', style: 'width:100%;margin:6px 0', on: { click: () => ask('inCall') } }, '📞 चल रही call पर लौटें') : '',
      h('div', { cls: 'tabs' }, [['pad', '⌨ Keypad'], ['recent', '🕘 Recent'], ['contacts', '👥 Contacts']].map(([id, n]) =>
        h('button', { cls: 'pill' + (tab === id ? ' on' : ''), 'data-tab': id, on: { click: () => { tab = id; draw(); } } }, n)))];
  }
  async function makeDefault() {
    const r = await ask('makeDefault');
    st = (await ask('state')) || st;
    if (r && r.ok) return draw();
    const box = document.getElementById('restricted');
    if (box) box.replaceChildren(h('div', { cls: 'card' }, h('b', {}, 'Android ने अनुमति रोक दी। एक बार यह करें:'),
      h('ol', {}, h('li', {}, '"App info खोलें" दबाएँ'), h('li', {}, 'ऊपर ⋮ → "Allow restricted settings" → PIN / fingerprint'), h('li', {}, 'वापस आकर फिर "✅ Ronin Calls चालू करें"')),
      h('button', { cls: 'pill blue', on: { click: () => ask('appInfo') } }, '⚙ App info खोलें')));
  }

  async function draw() {
    st = (await ask('state')) || {};
    if (tab === 'pad') return pad();
    const rows = tab === 'recent' ? ((await ask('recent')) || []) : ((await ask('contacts')) || []);
    const search = h('input', { cls: 'txt', placeholder: '🔍 नाम या नंबर', id: 'search' });
    const list = h('div', { id: 'list' });
    const fill = () => {
      const q = search.value.trim().toLowerCase();
      const mine = rows.filter(r => !q || (r.name || '').toLowerCase().includes(q) || (r.number || '').includes(q)).slice(0, 300);
      list.replaceChildren(...(mine.length ? mine.map(r => h('div', { cls: 'th', 'data-number': r.number },
        h('div', { cls: 'av', style: r.label ? 'background:#7c5d12' : '' }, ((r.name || r.number || '?')[0] || '?').toUpperCase()),
        h('div', { cls: 'mid', on: { click: () => call(r.number) } }, h('b', {}, r.name || r.number), h('small', {}, (r.name ? r.number + ' ' : '') + (TYPE[r.type] || '') + (r.date ? ' • ' + when(r.date) : '')), r.label ? h('span', { cls: 'lbl' }, r.label) : ''),
        h('button', { cls: 'pill', on: { click: () => ask('sms', { number: r.number }) } }, '💬'),
        r.label ? h('button', { cls: 'pill', on: { click: async () => { if (confirm(r.number + ' block करें? इनकी calls और SMS नहीं आएँगे।')) alert((await ask('block', { number: r.number })) ? '🚫 Block कर दिया' : 'Block नहीं हुआ'); } } }, '🚫') : '',
        h('button', { cls: 'pill green', on: { click: () => call(r.number) } }, '📞')))
        : [h('div', { cls: 'empty' }, st.isDefault || st.log || st.contacts ? 'कुछ नहीं मिला' : 'देखने के लिए ऊपर "चालू करें" दबाएँ')]));
    };
    search.addEventListener('input', fill);
    app.replaceChildren(...top(), search, list);
    fill();
  }

  async function pad() {
    const shown = h('input', { id: 'num', inputmode: 'none', readonly: 'readonly', value: number });
    const lbl = h('div', { cls: 'note', style: 'text-align:center;min-height:22px', id: 'lbl' });
    const upd = async () => { shown.value = number; lbl.textContent = number.length >= 10 ? ((await ask('label', { number })) || '') : ''; };
    const keys = [['1', ''], ['2', 'ABC'], ['3', 'DEF'], ['4', 'GHI'], ['5', 'JKL'], ['6', 'MNO'], ['7', 'PQRS'], ['8', 'TUV'], ['9', 'WXYZ'], ['*', ''], ['0', '+'], ['#', '']];
    app.replaceChildren(...top(), shown, lbl,
      h('div', { cls: 'pad' }, keys.map(([k, s]) => h('button', { 'data-key': k, on: { click: () => { number += k; upd(); } } }, k, s ? h('small', {}, s) : ''))),
      h('div', { cls: 'callbtn' },
        h('button', { cls: 'pill', on: { click: () => { number = number.slice(0, -1); upd(); } } }, '⌫'),
        h('button', { cls: 'pill green', id: 'call', on: { click: () => number && call(number) } }, '📞 Call'),
        h('button', { cls: 'pill', on: { click: () => number && ask('sms', { number }) } }, '💬')));
    upd();
  }

  async function settings() {
    st = (await ask('state')) || {};
    const sw = (key, title, sub) => h('div', { cls: 'row' }, h('div', {}, title, h('small', { cls: 'note', style: 'display:block' }, sub)),
      h('button', { cls: 'pill' + (st[key] ? ' green' : ''), 'data-set': key, on: { click: async e => { st[key] = !st[key]; await ask('set', { key, on: st[key] }); e.target.className = 'pill' + (st[key] ? ' green' : ''); e.target.textContent = st[key] ? 'चालू' : 'बंद'; } } }, st[key] ? 'चालू' : 'बंद'));
    app.replaceChildren(h('div', { cls: 'top' }, h('button', { cls: 'pill', on: { click: draw } }, '← वापस'), h('h1', {}, '⚙ Calls Settings'), h('span')),
      h('div', { cls: 'card' }, h('h2', {}, '🔕 चुप कराएँ (call आएगी, घंटी नहीं बजेगी)'),
        sw('silenceUnknown', 'अनजान नंबर (contacts में नहीं)', 'बच्चों / बुज़ुर्गों के phone के लिए अच्छा। 112 जैसी emergency calls कभी चुप नहीं होतीं।'),
        sw('silenceForeign', 'विदेश के नंबर (+91 के अलावा)', 'Digital arrest / sextortion की बहुत-सी calls विदेशी नंबर से आती हैं।')),
      await recCard(),
      h('div', { cls: 'card' }, h('h2', {}, '📢 Telemarketing'), h('p', { cls: 'note' }, 'TRAI: promotional calls 140 से शुरू होने वाले नंबरों से आती हैं; Ronin इन्हें पहचानकर दिखाता है। सब promotional calls / SMS बंद करने के लिए 1909 पर "BLOCK PROMO" SMS भेजें।')),
      h('div', { cls: 'card' }, h('h2', {}, '↩ पुराना phone app वापस'), h('p', { cls: 'note' }, 'कभी भी: Default apps → Phone app।'),
        h('button', { cls: 'pill', on: { click: () => ask('defaultApps') } }, 'Default apps खोलें')));
  }

  // 🎙 automatic recording: every call except the people the family picks
  async function recCard() {
    const r = (await ask('rec')) || {};
    const contacts = (await ask('contacts')) || [];
    const last10 = n => String(n || '').replace(/\D/g, '').slice(-10);
    const nameOf = n => (contacts.find(c => last10(c.number) === last10(n)) || {}).name || n;
    const skipBox = h('div', { id: 'skipList' });
    const drawSkip = list => skipBox.replaceChildren(...(list.length ? list.map(n => h('div', { cls: 'row' }, h('span', {}, '🚫🎙 ' + nameOf(n)),
      h('button', { cls: 'pill', on: { click: async () => drawSkip((await ask('skip', { number: n, on: false })) || []) } }, 'हटाएँ'))) : [h('p', { cls: 'note' }, 'कोई नहीं: सबकी call record होगी')]));
    drawSkip(r.skip || []);
    const pick = h('div');
    const search = h('input', { cls: 'txt', placeholder: '🔍 नाम खोजें, फिर "record न करें" दबाएँ', id: 'skipSearch' });
    search.addEventListener('input', () => {
      const q = search.value.trim().toLowerCase();
      pick.replaceChildren(...(q ? contacts.filter(c => c.name.toLowerCase().includes(q) || c.number.includes(q)).slice(0, 20).map(c => h('div', { cls: 'row' }, h('span', {}, c.name + ' ', h('small', { cls: 'note' }, c.number)),
        h('button', { cls: 'pill', 'data-skip': c.number, on: { click: async () => { drawSkip((await ask('skip', { number: c.number, on: true })) || []); search.value = ''; pick.replaceChildren(); } } }, 'record न करें'))) : []));
    });
    const recs = h('div', { id: 'recs' });
    const showRecs = async () => { const l = (await ask('recordings')) || []; recs.replaceChildren(...(l.length ? l.slice(0, 50).map(x => h('div', { cls: 'row' }, h('span', {}, x.name, h('small', { cls: 'note', style: 'display:block' }, when(x.date) + ' • ' + Math.round(x.size / 1024) + ' KB')), h('button', { cls: 'pill', on: { click: () => ask('play', { id: x.id }) } }, '▶'))) : [h('p', { cls: 'note' }, 'अभी कोई recording नहीं')])); };
    return h('div', { cls: 'card', id: 'recCard' }, h('h2', {}, '🎙 Call recording (अपने-आप)'),
      h('div', { cls: 'row' }, h('div', {}, 'हर call record करें', h('small', { cls: 'note', style: 'display:block' }, 'नीचे चुने लोगों को छोड़कर। Recordings phone में "Recordings / Ronin Calls" में।')),
        h('button', { cls: 'pill' + (r.auto ? ' green' : ''), id: 'recAuto', on: { click: async e => { r.auto = !r.auto; await ask('set', { key: 'recAuto', on: r.auto }); e.target.className = 'pill' + (r.auto ? ' green' : ''); e.target.textContent = r.auto ? 'चालू' : 'बंद'; } } }, r.auto ? 'चालू' : 'बंद')),
      r.access ? h('p', { cls: 'note' }, '✔ Recording की अनुमति चालू है।') : h('div', {}, h('p', { cls: 'note' }, 'सामने वाले की आवाज़ के लिए एक बार: Accessibility → "Ronin Calls: call recording" चालू करें (न दिखे तो पहले App info → ⋮ → Allow restricted settings)। ध्यान दें: Android कुछ phones में सामने वाले की आवाज़ धीमी कर देता है: एक test call करके सुन लें।'),
        h('button', { cls: 'pill blue', on: { click: () => ask('accessibility') } }, '⚙ Accessibility खोलें'), ' ', h('button', { cls: 'pill', on: { click: () => ask('appInfo') } }, 'App info')),
      h('h2', { style: 'margin-top:12px' }, '🚫 इनकी call record न करें'), skipBox, search, pick,
      h('h2', { style: 'margin-top:12px' }, '📂 Recordings'), h('button', { cls: 'pill', id: 'showRecs', on: { click: showRecs } }, 'दिखाएँ'), recs);
  }

  window.__roninDial = n => { number = String(n || ''); tab = 'pad'; draw(); };
  if (location.hash.startsWith('#num=')) number = decodeURIComponent(location.hash.slice(5));
  draw();
})();
