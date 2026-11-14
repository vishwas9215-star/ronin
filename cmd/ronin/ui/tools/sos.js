// Ronin SOS: press and hold the big button 2 seconds: the chosen family get an SMS with your exact
// place (Maps link), battery and time, then a new place every 5 minutes for an hour or until
// "मैं ठीक हूँ". The phone side is SosActivity / SosService (Sos.kt).
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
  let st = {}, inSetup = false;

  async function home() {
    inSetup = false;
    st = (await ask('state')) || {};
    const fam = st.contacts || [];
    if (!fam.length) return setup();
    if (st.running) return app.replaceChildren(top(), h('div', { cls: 'running', id: 'running' }, h('h2', {}, L('🆘 SOS चालू है', '🆘 SOS is on')),
      h('p', {}, L(fam.map(c => c.name).join(', ') + ' को हर 5 मिनट आपकी जगह और battery SMS से जा रही है (1 घंटे तक)।', 'Your place and battery go to ' + fam.map(c => c.name).join(', ') + ' by SMS every 5 minutes (for 1 hour).')),
      h('button', { cls: 'pill green', id: 'stop', style: 'min-height:64px;font-size:20px;width:100%', on: { click: async () => { await ask('stop'); setTimeout(home, 500); } } }, L('✅ मैं ठीक हूँ: बंद करें', '✅ I am safe: stop'))),
      h('div', { cls: 'callbtn', style: 'display:flex;justify-content:center;margin-top:14px' }, h('button', { cls: 'pill red', on: { click: () => ask('call', { number: '112' }) } }, L('📞 112 पर call', '📞 Call 112'))));
    const btn = h('button', { id: 'sosBtn' }, 'SOS', h('small', {}, L('2 सेकंड दबाए रखें', 'Hold for 2 seconds')));
    let timer = 0;
    const startHold = e => { e.preventDefault(); btn.classList.add('hold'); timer = setTimeout(fire, 2000); };
    const endHold = () => { btn.classList.remove('hold'); clearTimeout(timer); };
    btn.addEventListener('pointerdown', startHold); btn.addEventListener('pointerup', endHold); btn.addEventListener('pointerleave', endHold); btn.addEventListener('pointercancel', endHold);
    app.replaceChildren(top(), btn,
      h('p', { cls: 'note', style: 'text-align:center' }, L('दबाकर रखने पर ' + fam.map(c => c.name).join(', ') + ' को आपकी सही जगह (Maps link), battery और समय SMS से जाएगा, फिर हर 5 मिनट नई जगह। Internet न हो तब भी।', 'Hold it and ' + fam.map(c => c.name).join(', ') + ' get your exact place (Maps link), battery and time by SMS, then the new place every 5 minutes. Works without internet too.')),
      h('div', { style: 'text-align:center' }, h('button', { cls: 'pill red', on: { click: () => ask('call', { number: '112' }) } }, L('📞 112 पर call', '📞 Call 112'))));
  }
  async function fire() {
    const ok = await ask('start');
    if (!ok) { alert(L('पहले परिवार के नंबर चुनें', 'First choose the family numbers')); return setup(); }
    setTimeout(home, 600);
  }
  function top() {
    return h('div', { cls: 'top' }, h('h1', {}, '🆘 Ronin SOS'), h('button', { cls: 'pill', id: 'settings', on: { click: setup } }, '⚙'));
  }

  async function setup() {
    inSetup = true;
    st = (await ask('state')) || {};
    const fam = (st.contacts || []).slice();
    const name = h('input', { cls: 'txt', id: 'name', placeholder: L('आपका नाम (SMS में जाएगा)', 'Your name (goes in the SMS)'), value: st.name || '' });
    const chosen = h('div', { id: 'chosen' });
    const drawChosen = () => chosen.replaceChildren(...(fam.length ? fam.map((c, i) => h('div', { cls: 'row' }, h('span', {}, c.name + ' ', h('small', { cls: 'note' }, c.number)),
      h('button', { cls: 'pill', on: { click: () => { fam.splice(i, 1); drawChosen(); } } }, L('हटाएँ', 'Remove')))) : [h('p', { cls: 'note' }, L('अभी कोई नहीं: नीचे से 1-5 लोग चुनें', 'Nobody yet: choose 1-5 people below'))]));
    drawChosen();
    const all = (await ask('contacts')) || [];
    const search = h('input', { cls: 'txt', id: 'search', placeholder: L('🔍 परिवार वाले का नाम खोजें', '🔍 Find a family member') });
    const pick = h('div');
    search.addEventListener('input', () => {
      const q = search.value.trim().toLowerCase();
      pick.replaceChildren(...(q ? all.filter(c => c.name.toLowerCase().includes(q) || c.number.includes(q)).slice(0, 15).map(c => h('div', { cls: 'row' }, h('span', {}, c.name + ' ', h('small', { cls: 'note' }, c.number)),
        h('button', { cls: 'pill green', 'data-add': c.number, on: { click: () => { if (fam.length < 5 && !fam.some(x => x.number === c.number)) fam.push({ name: c.name, number: c.number }); search.value = ''; pick.replaceChildren(); drawChosen(); } } }, '➕'))) : []));
    });
    app.replaceChildren(h('div', { cls: 'top' }, h('button', { cls: 'pill', on: { click: () => (st.contacts || []).length ? home() : ask('close') } }, L('← वापस', '← Back')), h('h1', {}, L('⚙ SOS: किसे भेजें', '⚙ SOS: who gets it')), h('span')),
      h('div', { cls: 'card' }, h('h2', {}, L('🙂 आपका नाम', '🙂 Your name')), name),
      h('div', { cls: 'card' }, h('h2', {}, L('👨‍👩‍👧 परिवार (1-5 लोग)', '👨‍👩‍👧 Family (1-5 people)')), chosen, search, pick),
      h('button', { cls: 'pill green', id: 'save', style: 'width:100%;min-height:56px', on: { click: async () => {
        if (!fam.length) { alert(L('कम से कम एक नंबर चुनें', 'Choose at least one number')); return; }
        await ask('save', { contacts: fam, name: name.value.trim() });
        await ask('permissions');
        home();
      } } }, '✔ Save'));
  }
  window.__roninSos = { home }; // for the tests
  home();
  // Back: from the family list to the SOS button; with no family saved yet, out of the app
  window.__roninBack = () => { if (inSetup && (st.contacts || []).length) { home(); return true; } return false; };
})();
