// Ronin SOS: press and hold the big button 2 seconds: the chosen family get an SMS with your exact
// place (Maps link), battery and time, then a new place every 5 minutes for an hour or until
// "मैं ठीक हूँ". The phone side is SosActivity / SosService (Sos.kt).
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
  let st = {};

  async function home() {
    st = (await ask('state')) || {};
    const fam = st.contacts || [];
    if (!fam.length) return setup();
    if (st.running) return app.replaceChildren(top(), h('div', { cls: 'running', id: 'running' }, h('h2', {}, '🆘 SOS चालू है'),
      h('p', {}, fam.map(c => c.name).join(', ') + ' को हर 5 मिनट आपकी जगह और battery SMS से जा रही है (1 घंटे तक)।'),
      h('button', { cls: 'pill green', id: 'stop', style: 'min-height:64px;font-size:20px;width:100%', on: { click: async () => { await ask('stop'); setTimeout(home, 500); } } }, '✅ मैं ठीक हूँ: बंद करें')),
      h('div', { cls: 'callbtn', style: 'display:flex;justify-content:center;margin-top:14px' }, h('button', { cls: 'pill red', on: { click: () => ask('call', { number: '112' }) } }, '📞 112 पर call')));
    const btn = h('button', { id: 'sosBtn' }, 'SOS', h('small', {}, '2 सेकंड दबाए रखें'));
    let timer = 0;
    const startHold = e => { e.preventDefault(); btn.classList.add('hold'); timer = setTimeout(fire, 2000); };
    const endHold = () => { btn.classList.remove('hold'); clearTimeout(timer); };
    btn.addEventListener('pointerdown', startHold); btn.addEventListener('pointerup', endHold); btn.addEventListener('pointerleave', endHold); btn.addEventListener('pointercancel', endHold);
    app.replaceChildren(top(), btn,
      h('p', { cls: 'note', style: 'text-align:center' }, 'दबाकर रखने पर ' + fam.map(c => c.name).join(', ') + ' को आपकी सही जगह (Maps link), battery और समय SMS से जाएगा, फिर हर 5 मिनट नई जगह। Internet न हो तब भी।'),
      h('div', { style: 'text-align:center' }, h('button', { cls: 'pill red', on: { click: () => ask('call', { number: '112' }) } }, '📞 112 पर call')));
  }
  async function fire() {
    const ok = await ask('start');
    if (!ok) { alert('पहले परिवार के नंबर चुनें'); return setup(); }
    setTimeout(home, 600);
  }
  function top() {
    return h('div', { cls: 'top' }, h('h1', {}, '🆘 Ronin SOS'), h('button', { cls: 'pill', id: 'settings', on: { click: setup } }, '⚙'));
  }

  async function setup() {
    st = (await ask('state')) || {};
    const fam = (st.contacts || []).slice();
    const name = h('input', { cls: 'txt', id: 'name', placeholder: 'आपका नाम (SMS में जाएगा)', value: st.name || '' });
    const chosen = h('div', { id: 'chosen' });
    const drawChosen = () => chosen.replaceChildren(...(fam.length ? fam.map((c, i) => h('div', { cls: 'row' }, h('span', {}, c.name + ' ', h('small', { cls: 'note' }, c.number)),
      h('button', { cls: 'pill', on: { click: () => { fam.splice(i, 1); drawChosen(); } } }, 'हटाएँ'))) : [h('p', { cls: 'note' }, 'अभी कोई नहीं: नीचे से 1-5 लोग चुनें')]));
    drawChosen();
    const all = (await ask('contacts')) || [];
    const search = h('input', { cls: 'txt', id: 'search', placeholder: '🔍 परिवार वाले का नाम खोजें' });
    const pick = h('div');
    search.addEventListener('input', () => {
      const q = search.value.trim().toLowerCase();
      pick.replaceChildren(...(q ? all.filter(c => c.name.toLowerCase().includes(q) || c.number.includes(q)).slice(0, 15).map(c => h('div', { cls: 'row' }, h('span', {}, c.name + ' ', h('small', { cls: 'note' }, c.number)),
        h('button', { cls: 'pill green', 'data-add': c.number, on: { click: () => { if (fam.length < 5 && !fam.some(x => x.number === c.number)) fam.push({ name: c.name, number: c.number }); search.value = ''; pick.replaceChildren(); drawChosen(); } } }, '➕'))) : []));
    });
    app.replaceChildren(h('div', { cls: 'top' }, h('button', { cls: 'pill', on: { click: home } }, '← वापस'), h('h1', {}, '⚙ SOS: किसे भेजें'), h('span')),
      h('div', { cls: 'card' }, h('h2', {}, '🙂 आपका नाम'), name),
      h('div', { cls: 'card' }, h('h2', {}, '👨‍👩‍👧 परिवार (1-5 लोग)'), chosen, search, pick),
      h('button', { cls: 'pill green', id: 'save', style: 'width:100%;min-height:56px', on: { click: async () => {
        if (!fam.length) { alert('कम से कम एक नंबर चुनें'); return; }
        await ask('save', { contacts: fam, name: name.value.trim() });
        await ask('permissions');
        home();
      } } }, '✔ Save'));
  }
  window.__roninSos = { home }; // for the tests
  home();
})();
