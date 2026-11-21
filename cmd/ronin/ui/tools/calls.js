// Ronin Calls: keypad, recent calls (unknown / foreign / telemarketing callers marked), contacts.
// The phone side (CallsActivity, Calls.kt) answers through window.roninPhone. Calls themselves are
// placed by Android (112 and other emergency numbers always work).
(function () {
  'use strict';
  // the phone's own day / night (Ronin tells the page); the browser's switch does not change this
  document.documentElement.classList.toggle('light', window.__roninDark === false);
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
  const LANG = window.__roninLang || ((navigator.language || 'en').toLowerCase().indexOf('hi') === 0 ? 'hi' : 'en');
  const L = (hi, en) => LANG === 'hi' ? hi : en;
  let tab = 'recent', st = {}, number = '', inSettings = false; // Recent first (what is used most)
  const when = ms => { const d = new Date(ms), n = new Date(); return d.toDateString() === n.toDateString() ? d.toTimeString().slice(0, 5) : d.getDate() + '/' + (d.getMonth() + 1); };
  const TYPE = { 1: L('आई', 'incoming'), 2: L('की', 'outgoing'), 3: L('छूटी', 'missed'), 5: L('काटी', 'rejected'), 6: L('रोकी', 'blocked') };
  const ARROW = { 1: '↙', 2: '↗', 3: '✖', 5: '⛔', 6: '🚫' };
  // photo circles: a bright colour of its own per name with a white letter; a bare number its first digit, never a dark "+" / "9"
  // on slate (owner 21 Nov 2026: "गोल में... पूरा ब्लैक कलर में कर रखा है, ये गलत है")
  const AV = ['#2563eb', '#7c3aed', '#db2777', '#ea580c', '#0891b2', '#16a34a', '#b45309', '#4f46e5', '#0d9488', '#c026d3'];
  const avColor = k => { let x = 0; for (const c of String(k || '')) x = (x * 31 + c.codePointAt(0)) >>> 0; return AV[x % AV.length]; };
  const avText = (name, company) => { const n = String(name || '').trim(); if (!n) return '#';
    if (/^[+\d\s()-]+$/.test(n)) return (n.replace(/\D/g, '').slice(-10)[0] || '#'); // a number: its first digit, in white
    return company ? n.replace(/[^A-Za-z0-9]/g, '').slice(0, 2).toUpperCase() : [...n][0].toUpperCase(); };
  // came in or went out, at a glance: outgoing blue ↗, incoming green ↙, missed red
  const DIR = { 1: 'in', 2: 'out', 3: 'miss', 5: 'rej', 6: 'blk' };
  // a coloured arrow on the photo circle + the word in the same colour (no grey chips)
  const dirChip = t => TYPE[t] ? h('span', { cls: 'dir ' + (DIR[t] || ''), title: TYPE[t] }, ARROW[t]) : '';
  const dirWord = t => TYPE[t] ? h('span', { cls: 'wd ' + (DIR[t] || '') }, TYPE[t]) : '';
  // codes with * or # (e.g. *401*...) can send your calls and OTPs to someone else: ask first
  const call = n => {
    if (/[*#]/.test(n) && !confirm(L('⚠ सावधान: यह code आपकी calls / OTP किसी दूसरे नंबर पर भेज सकता है (ठगी का तरीका)। किसी के कहने पर dial न करें।\n\nफिर भी dial करें?', '⚠ Careful: this code can send your calls / OTPs to another number (a scam trick). Never dial it because someone asked you to.\n\nDial anyway?'))) return null;
    return ask('call', { number: n });
  };

  function top() {
    return [h('div', { cls: 'top' }, h('h1', {}, '📞 Ronin Calls'), h('button', { cls: 'pill', id: 'settings', 'aria-label': 'Settings', on: { click: settings } }, '⚙')),
      st.isDefault ? '' : h('div', { cls: 'card', id: 'setup' }, h('h2', {}, L('📞 Ronin Calls चालू करें', '📞 Turn on Ronin Calls')),
        h('p', { cls: 'note' }, L('चालू करने पर calls Ronin की बड़ी screen पर आएँगी, अनजान / विदेशी / telemarketing नंबर पर चेतावनी दिखेगी, और block किए नंबर की call नहीं आएगी। 112 जैसी emergency calls हमेशा चलेंगी।',
          'Calls come on Ronin\'s big screen, unknown / foreign / telemarketing numbers are marked, and blocked numbers cannot call. Emergency calls like 112 always work.')),
        h('button', { cls: 'pill green', id: 'makeDefault', on: { click: makeDefault } }, L('✅ Ronin Calls चालू करें', '✅ Turn on Ronin Calls')), h('div', { id: 'restricted' })),
      st.inCall ? h('button', { cls: 'pill green', style: 'width:100%;margin:6px 0', on: { click: () => ask('inCall') } }, L('📞 चल रही call पर लौटें', '📞 Back to the call')) : '',
      // Keypad / Recent / Contacts at the bottom, where the thumb is
      h('nav', { cls: 'nav' }, [['pad', '⌨', 'Keypad'], ['recent', '🕘', L('Recent', 'Recent')], ['contacts', '👥', 'Contacts']].map(([id, ic, n]) =>
        h('button', { cls: tab === id ? 'on' : '', 'data-tab': id, on: { click: () => { tab = id; draw(); } } }, h('span', { cls: 'ic' }, ic), h('span', {}, n))))];
  }
  async function makeDefault() {
    const r = await ask('makeDefault');
    st = (await ask('state')) || st;
    if (r && r.ok) return draw();
    const box = document.getElementById('restricted');
    if (box) box.replaceChildren(h('div', { cls: 'card' }, h('b', {}, L('Android ने अनुमति रोक दी। एक बार यह करें:', 'Android blocked this. Do this once:')),
      h('ol', {}, h('li', {}, L('"App info खोलें" दबाएँ', 'Tap "Open App info"')), h('li', {}, L('ऊपर ⋮ → "Allow restricted settings" → PIN / fingerprint', '⋮ at the top → "Allow restricted settings" → PIN / fingerprint')), h('li', {}, L('वापस आकर फिर "✅ Ronin Calls चालू करें"', 'Come back and tap "✅ Turn on Ronin Calls" again'))),
      h('button', { cls: 'pill blue', on: { click: () => ask('appInfo') } }, L('⚙ App info खोलें', '⚙ Open App info'))));
  }

  async function draw() {
    inSettings = false;
    st = (await ask('state')) || {};
    if (tab === 'pad') return pad();
    const rows = tab === 'recent' ? ((await ask('recent')) || []) : ((await ask('contacts')) || []);
    const search = h('input', { cls: 'txt', placeholder: L('🔍 नाम या नंबर', '🔍 Name or number'), id: 'search' });
    const list = h('div', { id: 'list' });
    const fill = () => {
      const q = search.value.trim().toLowerCase();
      // a name for a number not in contacts (owner 21 Nov: "Truecaller जैसी पहचान"): the name 🔍 found on the web, the
      // SIM (CNAP) name 📛, a name from a company SMS 🛠; only then the bare number
      const title = r => r.name || (r.tag ? '🔍 ' + r.tag : r.sim ? '📛 ' + r.sim : r.guess ? '🛠 ' + r.guess : r.number);
      const named = r => !!(r.name || r.tag || r.sim || r.guess);
      const mine = rows.filter(r => !q || [r.name, r.tag, r.sim, r.guess].some(x => (x || '').toLowerCase().includes(q)) || (r.number || '').includes(q)).slice(0, 300);
      const chip = (txt, id, f) => h('button', { cls: 'chip', 'data-act': id, on: { click: (e) => { e.stopPropagation(); f(); } } }, txt);
      // one tap: Ronin searches only this number on the web and writes the name it finds here (no typing, no speaking)
      const whoIs = async (r, btn) => {
        btn.disabled = true; btn.textContent = L('⏳ खोज रहा हूँ…', '⏳ Looking…');
        const a = (await ask('whoIs', { number: r.number })) || {};
        if (!a.ok) { btn.disabled = false; btn.textContent = L('🔍 फिर कोशिश (internet?)', '🔍 Try again (internet?)'); return; }
        // the same number may be on several rows: all of them get the name
        rows.forEach(x => { if (x.number === r.number) { x.tag = a.name || ''; x.searched = true; x.complaints = !!a.complaints; } });
        fill();
      };
      // 3.14.3 (owner 21 Nov): red only for a real risk; your order's company / your own call / a 1600 bank line stay calm
      const risk = (r) => (r.level ? r.level === 'risk' : !!r.label);
      list.replaceChildren(...(mine.length ? mine.map(r => h('div', { cls: 'th' + (risk(r) ? ' flag' : ''), 'data-number': r.number, 'data-level': r.level || '' },
        h('div', { cls: 'avw' }, h('div', { cls: 'av', style: 'background:' + (risk(r) ? '#dc2626' : avColor(r.number || r.name)) }, avText(r.name || r.tag || r.sim || r.guess || r.number, false)), dirChip(r.type)),
        h('div', { cls: 'mid', on: { click: () => window.__roninDial(r.number) } }, // a tap on the name only puts the number on the keypad: only the green 📞 calls (a stray tap once called someone at night)
         h('b', { cls: r.type === 3 ? 'miss' : '' }, title(r)), h('small', {}, dirWord(r.type), (r.type ? ' • ' : '') + (named(r) ? r.number : '') + (r.date ? (named(r) ? ' • ' : '') + when(r.date) : '')), r.label ? h('span', { cls: 'lbl' + (r.level && r.level !== 'risk' ? ' ' + r.level : '') }, r.label) : '',
         // not a contact: who is it? (one tap: the number searched in Ronin Browser) / say a name once
         r.complaints ? h('span', { cls: 'lbl' }, L('⚠ web पर लोगों ने इस नंबर की शिकायत की है', '⚠ People complained about this number on the web')) : '',
         !r.name && r.number && tab === 'recent' && !r.tag ? h('div', { cls: 'chips' }, r.searched
           ? h('span', { cls: 'chip off' }, L('🔍 web पर नाम नहीं मिला', '🔍 No name on the web'))
           : (() => { const b = chip(L('🔍 कौन है?', '🔍 Who is it?'), 'who', () => whoIs(r, b)); return b; })()) : ''),
        h('button', { cls: 'pill', on: { click: () => ask('sms', { number: r.number }) } }, '💬'),
        r.label ? h('button', { cls: 'pill', on: { click: async () => { if (confirm(L(r.number + ' block करें? इनकी calls और SMS नहीं आएँगे।', 'Block ' + r.number + '? Their calls and SMS will stop.'))) alert((await ask('block', { number: r.number })) ? L('🚫 Block कर दिया', '🚫 Blocked') : L('Block नहीं हुआ', 'Could not block')); } } }, '🚫') : '',
        h('button', { cls: 'pill green', on: { click: () => call(r.number) } }, '📞')))
        : [h('div', { cls: 'empty' }, st.isDefault || st.log || st.contacts ? L('कुछ नहीं मिला', 'Nothing found') : L('देखने के लिए ऊपर "चालू करें" दबाएँ', 'Tap "Turn on" above to see this'))]));
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
    // the number and the keys sit low, above Keypad / Recent / Contacts, easy for the thumb
    app.replaceChildren(...top(), h('div', { cls: 'padwrap' }, shown, lbl,
      h('div', { cls: 'pad' }, keys.map(([k, s]) => h('button', { 'data-key': k, on: { click: () => { number += k; upd(); } } }, k, s ? h('small', {}, s) : ''))),
      h('div', { cls: 'callbtn' },
        h('button', { cls: 'pill', on: { click: () => { number = number.slice(0, -1); upd(); } } }, '⌫'),
        h('button', { cls: 'pill green', id: 'call', on: { click: () => number && call(number) } }, '📞 Call'),
        h('button', { cls: 'pill', on: { click: () => number && ask('sms', { number }) } }, '💬'))));
    upd();
  }

  async function settings() {
    inSettings = true;
    st = (await ask('state')) || {};
    const sw = (key, title, sub) => h('div', { cls: 'row' }, h('div', {}, title, h('small', { cls: 'note', style: 'display:block' }, sub)),
      h('button', { cls: 'pill' + (st[key] ? ' green' : ''), 'data-set': key, on: { click: async e => { st[key] = !st[key]; await ask('set', { key, on: st[key] }); e.target.className = 'pill' + (st[key] ? ' green' : ''); e.target.textContent = st[key] ? L('चालू', 'On') : L('बंद', 'Off'); } } }, st[key] ? L('चालू', 'On') : L('बंद', 'Off')));
    app.replaceChildren(h('div', { cls: 'top' }, h('button', { cls: 'pill', id: 'back', on: { click: draw } }, L('← वापस', '← Back')), h('h1', {}, '⚙ Calls Settings'), h('span')),
      await langCard(),
      h('div', { cls: 'card' }, h('h2', {}, L('🔕 चुप कराएँ (call आएगी, घंटी नहीं बजेगी)', '🔕 Silence (the call comes, but does not ring)')),
        sw('silenceUnknown', L('अनजान नंबर (contacts में नहीं)', 'Unknown numbers (not in contacts)'), L('बच्चों / बुज़ुर्गों के phone के लिए अच्छा। 112 जैसी emergency calls कभी चुप नहीं होतीं।', 'Good for children\'s / elders\' phones. Emergency calls like 112 are never silenced.')),
        sw('silenceForeign', L('विदेश के नंबर (+91 के अलावा)', 'Foreign numbers (not +91)'), L('Digital arrest / sextortion की बहुत-सी calls विदेशी नंबर से आती हैं।', 'Many "digital arrest" / sextortion calls come from foreign numbers.'))),
      await recCard(),
      h('div', { cls: 'card' }, h('h2', {}, '📢 Telemarketing'), h('p', { cls: 'note' }, L('TRAI: promotional calls 140 से शुरू होने वाले नंबरों से आती हैं; Ronin इन्हें पहचानकर दिखाता है। सब promotional calls / SMS बंद करने के लिए 1909 पर "BLOCK PROMO" SMS भेजें।', 'TRAI: promotional calls come from numbers starting with 140; Ronin marks them. To stop all promotional calls / SMS, send "BLOCK PROMO" to 1909.'))),
      h('div', { cls: 'card' }, h('h2', {}, L('↩ पुराना phone app वापस', '↩ Back to your old phone app')), h('p', { cls: 'note' }, L('कभी भी: Default apps → Phone app।', 'Any time: Default apps → Phone app.')),
        h('button', { cls: 'pill', on: { click: () => ask('defaultApps') } }, L('Default apps खोलें', 'Open Default apps'))));
  }

  // Language: the phone's own (default), or always English / always Hindi
  async function langCard() {
    const cur = (await ask('lang')) || 'auto';
    const opt = (v, name) => h('button', { cls: 'pill' + (cur === v ? ' blue' : ''), 'data-lang': v, on: { click: () => ask('setLang', { value: v }) } }, name);
    return h('div', { cls: 'card', id: 'langCard' }, h('h2', {}, '🌐 Language / भाषा'),
      h('div', { cls: 'tabs' }, opt('auto', L('फ़ोन जैसी', 'Same as phone')), opt('en', 'English'), opt('hi', 'हिंदी')));
  }

  // 🎙 automatic recording: every call except the people the family picks
  async function recCard() {
    const r = (await ask('rec')) || {};
    const contacts = (await ask('contacts')) || [];
    const last10 = n => String(n || '').replace(/\D/g, '').slice(-10);
    const nameOf = n => (contacts.find(c => last10(c.number) === last10(n)) || {}).name || n;
    const skipBox = h('div', { id: 'skipList' });
    const drawSkip = list => skipBox.replaceChildren(...(list.length ? list.map(n => h('div', { cls: 'row' }, h('span', {}, '🚫🎙 ' + nameOf(n)),
      h('button', { cls: 'pill', on: { click: async () => drawSkip((await ask('skip', { number: n, on: false })) || []) } }, L('हटाएँ', 'Remove')))) : [h('p', { cls: 'note' }, L('कोई नहीं: सबकी call record होगी', 'Nobody: every call is recorded'))]));
    drawSkip(r.skip || []);
    const pick = h('div');
    const search = h('input', { cls: 'txt', placeholder: L('🔍 नाम खोजें, फिर "record न करें" दबाएँ', '🔍 Find a name, then tap "Don\'t record"'), id: 'skipSearch' });
    search.addEventListener('input', () => {
      const q = search.value.trim().toLowerCase();
      pick.replaceChildren(...(q ? contacts.filter(c => c.name.toLowerCase().includes(q) || c.number.includes(q)).slice(0, 20).map(c => h('div', { cls: 'row' }, h('span', {}, c.name + ' ', h('small', { cls: 'note' }, c.number)),
        h('button', { cls: 'pill', 'data-skip': c.number, on: { click: async () => { drawSkip((await ask('skip', { number: c.number, on: true })) || []); search.value = ''; pick.replaceChildren(); } } }, L('record न करें', "Don't record")))) : []));
    });
    const recs = h('div', { id: 'recs' });
    const showRecs = async () => { const l = (await ask('recordings')) || []; recs.replaceChildren(...(l.length ? l.slice(0, 50).map(x => h('div', { cls: 'row' }, h('span', {}, x.name, h('small', { cls: 'note', style: 'display:block' }, when(x.date) + ' • ' + Math.round(x.size / 1024) + ' KB')), h('button', { cls: 'pill', on: { click: () => ask('play', { rid: x.id }) } }, '▶'))) : [h('p', { cls: 'note' }, L('अभी कोई recording नहीं', 'No recordings yet'))])); };
    return h('div', { cls: 'card', id: 'recCard' }, h('h2', {}, L('🎙 Call recording (अपने-आप)', '🎙 Call recording (automatic)')),
      h('div', { cls: 'row' }, h('div', {}, L('हर call record करें', 'Record every call'), h('small', { cls: 'note', style: 'display:block' }, L('नीचे चुने लोगों को छोड़कर। Recordings phone में "Recordings / Ronin Calls" में।', 'Except the people chosen below. Recordings are in "Recordings / Ronin Calls" on the phone.'))),
        h('button', { cls: 'pill' + (r.auto ? ' green' : ''), id: 'recAuto', on: { click: async e => { r.auto = !r.auto; await ask('set', { key: 'recAuto', on: r.auto }); e.target.className = 'pill' + (r.auto ? ' green' : ''); e.target.textContent = r.auto ? L('चालू', 'On') : L('बंद', 'Off'); } } }, r.auto ? L('चालू', 'On') : L('बंद', 'Off'))),
      r.access ? h('p', { cls: 'note' }, L('✔ Recording की अनुमति चालू है।', '✔ Recording permission is on.')) : h('div', {}, h('p', { cls: 'note' }, L('सामने वाले की आवाज़ के लिए एक बार: Accessibility → "Ronin Calls: call recording" चालू करें (न दिखे तो पहले App info → ⋮ → Allow restricted settings)। ध्यान दें: Android कुछ phones में सामने वाले की आवाज़ धीमी कर देता है: एक test call करके सुन लें।',
          'To record the other person\'s voice, once: Accessibility → turn on "Ronin Calls: call recording" (if it is not there: App info → ⋮ → Allow restricted settings first). Note: on some phones Android makes the other voice quiet, so make a test call and listen.')),
        h('button', { cls: 'pill blue', on: { click: () => ask('accessibility') } }, L('⚙ Accessibility खोलें', '⚙ Open Accessibility')), ' ', h('button', { cls: 'pill', on: { click: () => ask('appInfo') } }, 'App info')),
      h('h2', { style: 'margin-top:12px' }, L('🚫 इनकी call record न करें', "🚫 Don't record these people")), skipBox, search, pick,
      h('h2', { style: 'margin-top:12px' }, '📂 Recordings'), h('button', { cls: 'pill', id: 'showRecs', on: { click: showRecs } }, L('दिखाएँ', 'Show')), recs);
  }

  // the phone's Back: settings -> Recent / keypad or contacts -> Recent -> out of the app
  window.__roninBack = () => {
    if (inSettings) { draw(); return true; }
    if (tab !== 'recent') { tab = 'recent'; draw(); return true; }
    return false;
  };
  window.__roninDial = n => { number = String(n || ''); tab = 'pad'; draw(); };
  if (location.hash.startsWith('#num=')) { number = decodeURIComponent(location.hash.slice(5)); tab = 'pad'; }
  draw();
})();
