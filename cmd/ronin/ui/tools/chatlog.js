// Chat संदेश: WhatsApp (and other chat) messages Ronin saw in notifications, kept 7 days on this
// phone only, so a message "deleted for everyone" can still be read. Text only.
(function () {
  'use strict';
  const LANG = window.__roninLang || ((navigator.language || 'en').toLowerCase().indexOf('hi') === 0 ? 'hi' : 'en');
  const L = (hi, en) => LANG === 'hi' ? hi : en;
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
  const when = ms => { const d = new Date(ms), n = new Date(); return (d.toDateString() === n.toDateString() ? '' : d.getDate() + '/' + (d.getMonth() + 1) + ' ') + d.toTimeString().slice(0, 5); };
  let onlyGone = true, chat = null;

  async function draw() {
    const st = (await ask('state')) || {};
    const all = ((await ask('list')) || []).slice().reverse();
    const chats = [...new Set(all.map(m => m.chat))];
    const list = chat ? all.filter(m => m.chat === chat) : onlyGone ? all.filter(m => m.gone) : all;
    app.replaceChildren(
      h('div', { cls: 'top' }, chat ? h('button', { cls: 'pill', on: { click: () => { chat = null; draw(); } } }, L('← वापस', '← Back')) : '', h('h1', {}, chat || L('💬 Chat संदेश', '💬 Chat messages')), h('button', { cls: 'pill', on: { click: settings } }, '⚙')),
      st.watcher ? '' : h('div', { cls: 'card', id: 'setup' }, h('h2', {}, L('🔔 पहले चालू करें', '🔔 Turn it on first')),
        h('p', { cls: 'note' }, L('Ronin को WhatsApp की notifications देखने की अनुमति दें: तब हर आया संदेश 7 दिन यहाँ रहेगा, और भेजने वाले ने delete किया तो भी आप पढ़ सकेंगे। सब कुछ सिर्फ़ इसी phone पर।', 'Let Ronin see WhatsApp notifications: every message that comes stays here 7 days, and you can read it even if the sender deletes it. Everything stays on this phone only.')),
        h('ol', {}, h('li', {}, L('नीचे बटन दबाएँ → list में "Ronin" चालू करें', 'Tap the button below → turn on "Ronin" in the list')), h('li', {}, L('न चले तो पहले App info → ⋮ → Allow restricted settings', 'If it will not turn on: App info → ⋮ → Allow restricted settings first'))),
        h('button', { cls: 'pill blue', on: { click: () => ask('notifyAccess') } }, L('🔔 चालू करें', '🔔 Turn on')), ' ', h('button', { cls: 'pill', on: { click: () => ask('appInfo') } }, '⚙ App info')),
      chat ? '' : h('div', { cls: 'tabs' },
        h('button', { cls: 'pill' + (onlyGone ? ' on' : ''), id: 'gone', on: { click: () => { onlyGone = true; draw(); } } }, L('🗑 Delete हुए', '🗑 Deleted')),
        h('button', { cls: 'pill' + (!onlyGone ? ' on' : ''), id: 'allMsgs', on: { click: () => { onlyGone = false; draw(); } } }, L('सभी', 'All')),
        chats.slice(0, 30).map(c => h('button', { cls: 'pill', on: { click: () => { chat = c; draw(); } } }, c))),
      h('div', { cls: 'msgs', id: 'list' }, list.length ? list.slice(0, 500).map(m => h('div', { cls: 'b in' + (m.gone ? ' gone' : ''), 'data-gone': m.gone ? '1' : '' },
        h('div', {}, h('span', { cls: 'who' }, m.who), m.chat !== m.who ? ' · ' + m.chat : ''), m.text,
        h('small', {}, when(m.t) + ' · ' + m.app + (m.gone ? L(' · 🗑 delete हुआ (या दूसरी जगह पढ़ा)', ' · 🗑 deleted (or read elsewhere)') : ''))))
        : h('div', { cls: 'empty' }, onlyGone && !chat ? L('अभी कोई delete हुआ संदेश नहीं', 'No deleted messages yet') : L('कोई संदेश नहीं', 'No messages'))));
  }

  async function settings() {
    const st = (await ask('state')) || {};
    app.replaceChildren(h('div', { cls: 'top' }, h('button', { cls: 'pill', on: { click: draw } }, L('← वापस', '← Back')), h('h1', {}, L('⚙ Chat संदेश', '⚙ Chat messages')), h('span')),
      h('div', { cls: 'card' }, h('div', { cls: 'row' }, h('div', {}, L('संदेश 7 दिन रखें', 'Keep messages 7 days'), h('small', { cls: 'note', style: 'display:block' }, L('बंद करने पर सब रखे संदेश भी मिट जाते हैं।', 'Turning it off also deletes all kept messages.'))),
        h('button', { cls: 'pill' + (st.on ? ' green' : ''), id: 'keep', on: { click: async e => { st.on = !st.on; await ask('set', { on: st.on }); e.target.className = 'pill' + (st.on ? ' green' : ''); e.target.textContent = st.on ? L('चालू', 'On') : L('बंद', 'Off'); } } }, st.on ? L('चालू', 'On') : L('बंद', 'Off')))),
      h('div', { cls: 'card' }, h('button', { cls: 'pill red', id: 'clear', on: { click: async () => { if (confirm(L('सब रखे संदेश अभी मिटा दें?', 'Delete all kept messages now?'))) { await ask('clear'); draw(); } } } }, L('🗑 सब मिटाएँ', '🗑 Delete all')),
        h('p', { cls: 'note' }, L('सिर्फ़ लिखे संदेश रखे जाते हैं (फ़ोटो / वीडियो नहीं), सिर्फ़ इसी phone पर, 7 दिन बाद अपने-आप मिटते हैं।', 'Only text is kept (no photos / videos), only on this phone, deleted by itself after 7 days.'))));
  }
  window.__roninChatLog = { draw }; // for the tests
  draw();
})();
