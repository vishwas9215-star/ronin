// Chat संदेश: WhatsApp (and other chat) messages Ronin saw in notifications, kept 7 days on this
// phone only, so a message "deleted for everyone" can still be read. Text only.
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
  const when = ms => { const d = new Date(ms), n = new Date(); return (d.toDateString() === n.toDateString() ? '' : d.getDate() + '/' + (d.getMonth() + 1) + ' ') + d.toTimeString().slice(0, 5); };
  let onlyGone = true, chat = null;

  async function draw() {
    const st = (await ask('state')) || {};
    const all = ((await ask('list')) || []).slice().reverse();
    const chats = [...new Set(all.map(m => m.chat))];
    const list = chat ? all.filter(m => m.chat === chat) : onlyGone ? all.filter(m => m.gone) : all;
    app.replaceChildren(
      h('div', { cls: 'top' }, chat ? h('button', { cls: 'pill', on: { click: () => { chat = null; draw(); } } }, '← वापस') : '', h('h1', {}, chat || '💬 Chat संदेश'), h('button', { cls: 'pill', on: { click: settings } }, '⚙')),
      st.watcher ? '' : h('div', { cls: 'card', id: 'setup' }, h('h2', {}, '🔔 पहले चालू करें'),
        h('p', { cls: 'note' }, 'Ronin को WhatsApp की notifications देखने की अनुमति दें: तब हर आया संदेश 7 दिन यहाँ रहेगा, और भेजने वाले ने delete किया तो भी आप पढ़ सकेंगे। सब कुछ सिर्फ़ इसी phone पर।'),
        h('ol', {}, h('li', {}, 'नीचे बटन दबाएँ → list में "Ronin" चालू करें'), h('li', {}, 'न चले तो पहले App info → ⋮ → Allow restricted settings')),
        h('button', { cls: 'pill blue', on: { click: () => ask('notifyAccess') } }, '🔔 चालू करें'), ' ', h('button', { cls: 'pill', on: { click: () => ask('appInfo') } }, '⚙ App info')),
      chat ? '' : h('div', { cls: 'tabs' },
        h('button', { cls: 'pill' + (onlyGone ? ' on' : ''), id: 'gone', on: { click: () => { onlyGone = true; draw(); } } }, '🗑 Delete हुए'),
        h('button', { cls: 'pill' + (!onlyGone ? ' on' : ''), id: 'allMsgs', on: { click: () => { onlyGone = false; draw(); } } }, 'सभी'),
        chats.slice(0, 30).map(c => h('button', { cls: 'pill', on: { click: () => { chat = c; draw(); } } }, c))),
      h('div', { cls: 'msgs', id: 'list' }, list.length ? list.slice(0, 500).map(m => h('div', { cls: 'b in' + (m.gone ? ' gone' : ''), 'data-gone': m.gone ? '1' : '' },
        h('div', {}, h('span', { cls: 'who' }, m.who), m.chat !== m.who ? ' · ' + m.chat : ''), m.text,
        h('small', {}, when(m.t) + ' · ' + m.app + (m.gone ? ' · 🗑 delete हुआ (या दूसरी जगह पढ़ा)' : ''))))
        : h('div', { cls: 'empty' }, onlyGone && !chat ? 'अभी कोई delete हुआ संदेश नहीं' : 'कोई संदेश नहीं')));
  }

  async function settings() {
    const st = (await ask('state')) || {};
    app.replaceChildren(h('div', { cls: 'top' }, h('button', { cls: 'pill', on: { click: draw } }, '← वापस'), h('h1', {}, '⚙ Chat संदेश'), h('span')),
      h('div', { cls: 'card' }, h('div', { cls: 'row' }, h('div', {}, 'संदेश 7 दिन रखें', h('small', { cls: 'note', style: 'display:block' }, 'बंद करने पर सब रखे संदेश भी मिट जाते हैं।')),
        h('button', { cls: 'pill' + (st.on ? ' green' : ''), id: 'keep', on: { click: async e => { st.on = !st.on; await ask('set', { on: st.on }); e.target.className = 'pill' + (st.on ? ' green' : ''); e.target.textContent = st.on ? 'चालू' : 'बंद'; } } }, st.on ? 'चालू' : 'बंद'))),
      h('div', { cls: 'card' }, h('button', { cls: 'pill red', id: 'clear', on: { click: async () => { if (confirm('सब रखे संदेश अभी मिटा दें?')) { await ask('clear'); draw(); } } } }, '🗑 सब मिटाएँ'),
        h('p', { cls: 'note' }, 'सिर्फ़ लिखे संदेश रखे जाते हैं (फ़ोटो / वीडियो नहीं), सिर्फ़ इसी phone पर, 7 दिन बाद अपने-आप मिटते हैं।')));
  }
  window.__roninChatLog = { draw }; // for the tests
  draw();
})();
