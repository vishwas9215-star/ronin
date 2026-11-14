// Ronin Safe Social launcher: the social apps this family uses, each opening full screen in
// Ronin with its protection on (inject_social.js). Settings sit behind an optional PIN.
(function () {
  'use strict';
  const q = new URLSearchParams(location.search);
  const KEY = q.get('k') || '';
  if (q.get('dark') === '0') document.body.classList.add('light');
  const app = document.getElementById('app');
  // every app Ronin knows; the family picks which ones show (Settings)
  const CATALOG = [
    ['facebook', 'Facebook', 'f', '#1877f2', 'https://www.facebook.com/'],
    ['instagram', 'Instagram', '◎', '#c13584', 'https://www.instagram.com/'],
    ['youtube', 'YouTube', '▶', '#e00000', 'https://www.youtube.com/'],
    ['whatsapp', 'WhatsApp', '✆', '#1fa855', 'https://web.whatsapp.com/'],
    ['x', 'X (Twitter)', '𝕏', '#222222', 'https://x.com/'],
    ['threads', 'Threads', '@', '#333333', 'https://www.threads.net/'],
    ['sharechat', 'ShareChat', 'S', '#f59e0b', 'https://sharechat.com/'],
    ['moj', 'Moj', 'M', '#f43f5e', 'https://mojapp.in/'],
    ['linkedin', 'LinkedIn', 'in', '#0a66c2', 'https://www.linkedin.com/'],
    ['pinterest', 'Pinterest', 'P', '#bd081c', 'https://www.pinterest.com/'],
    ['snapchat', 'Snapchat', '👻', '#d4c300', 'https://web.snapchat.com/'],
    ['telegram', 'Telegram', '✈', '#229ed9', 'https://web.telegram.org/'],
  ];
  const DEFAULT = { apps: ['facebook', 'instagram', 'youtube', 'whatsapp'], family: false, reels: false, ytRestrict: true, pin: '' };
  const load = () => { try { return Object.assign({}, DEFAULT, JSON.parse(localStorage.getItem('ronin.social') || '{}')); } catch (e) { return Object.assign({}, DEFAULT); } };
  const keep = c => { try { localStorage.setItem('ronin.social', JSON.stringify(c)); } catch (e) { /* this device only */ } };
  let cfg = load();
  const h = (tag, attrs, ...kids) => {
    const e = document.createElement(tag);
    Object.entries(attrs || {}).forEach(([k, v]) => { if (k === 'on') Object.entries(v).forEach(([ev, f]) => e.addEventListener(ev, f)); else if (k === 'cls') e.className = v; else if (v != null && v !== false) e.setAttribute(k, v); });
    kids.flat().forEach(c => e.append(c && c.nodeType ? c : document.createTextNode(c == null ? '' : String(c))));
    return e;
  };
  // what Ronin needs to know (no PIN): which apps, family mode, reels, YouTube restricted
  const shared = () => btoa(unescape(encodeURIComponent(JSON.stringify({ apps: cfg.apps, family: cfg.family, reels: cfg.reels, ytRestrict: cfg.ytRestrict }))));
  const go = (what, extra) => { if (KEY) location.href = 'https://ronin.data/social?do=' + what + '&k=' + KEY + '&cfg=' + encodeURIComponent(shared()) + (extra || ''); };
  window.__roninSocialPage = { cfg: () => cfg, catalog: CATALOG }; // for the tests

  function home() {
    app.innerHTML = '';
    const mine = CATALOG.filter(a => cfg.apps.includes(a[0]));
    app.append(
      h('div', { cls: 'top' }, h('h1', {}, '🛡️ Safe Social'),
        h('div', {}, h('button', { cls: 'round', title: 'Settings', on: { click: askPin } }, '⚙'), ' ', h('button', { cls: 'round', title: 'बंद करें', on: { click: () => go('close') } }, '✕'))),
      h('p', { cls: 'note' }, 'यहाँ से खोलने पर गंदे ads हटते हैं, अनजान Telegram/WhatsApp group, पैसे और ठगी वाले link पूरी तरह रुकते हैं, और ठगी / अफवाह वाले messages पर चेतावनी आती है।'),
      h('div', { cls: 'apps' }, mine.length ? mine.map(([id, name, icon, color, url]) =>
        h('button', { cls: 'app', style: 'background:' + color, 'data-app': id, on: { click: () => go('open', '&url=' + encodeURIComponent(url)) } }, h('span', {}, icon), name))
        : h('p', { cls: 'note' }, 'कोई app नहीं चुना: ⚙ Settings में चुनें।')),
      h('div', { cls: 'card' }, h('h2', {}, '🧠 याद रखें'), h('ul', {},
        h('li', {}, 'अनजान video call न उठाएँ। उठा लिया और कोई धमकाए ("video viral कर देंगे", "police case"): पैसे न दें, call काटें, 1930 पर शिकायत करें।'),
        h('li', {}, 'पैसा लेने के लिए कभी QR scan या UPI PIN नहीं डालना पड़ता।'),
        h('li', {}, '"पक्का मुनाफ़ा", "शेयर tips group", "task job", "लॉटरी जीती": ये सब ठगी के तरीके हैं।'),
        h('li', {}, 'OTP, PIN, password किसी को न बताएँ, चाहे वह बैंक / पुलिस / रिश्तेदार बनकर बोले।'),
        h('li', {}, 'साइबर ठगी हो जाए तो तुरंत 1930 पर call करें या cybercrime.gov.in पर शिकायत करें।'))));
  }

  function askPin() {
    if (!cfg.pin) return settings();
    app.innerHTML = '';
    const inp = h('input', { type: 'password', inputmode: 'numeric', maxlength: 8, autocomplete: 'off' });
    const err = h('div', { cls: 'err' });
    const ok = () => { if (inp.value === cfg.pin) settings(); else { err.textContent = 'PIN ग़लत है'; inp.value = ''; } };
    inp.addEventListener('keydown', e => { if (e.key === 'Enter') ok(); });
    app.append(h('div', { cls: 'top' }, h('h1', {}, '🔒 Settings का PIN'), h('button', { cls: 'round', on: { click: home } }, '‹')),
      h('p', { cls: 'note' }, 'Settings बदलने के लिए PIN डालें।'), inp, err, h('button', { cls: 'big', on: { click: ok } }, 'खोलें'));
    setTimeout(() => inp.focus(), 50);
  }

  function settings() {
    app.innerHTML = '';
    const sw = (on, flip) => { const b = h('button', { cls: 'sw' + (on ? ' on' : '') }); b.onclick = () => { flip(); b.classList.toggle('on'); }; return b; };
    const row = (title, sub, on, flip) => h('div', { cls: 'row' }, h('div', {}, title, sub ? h('small', {}, sub) : ''), sw(on, flip));
    const pinIn = h('input', { type: 'password', inputmode: 'numeric', maxlength: 8, placeholder: cfg.pin ? '••••' : 'नया PIN', autocomplete: 'off' });
    app.append(
      h('div', { cls: 'top' }, h('h1', {}, '⚙ Safe Social Settings'), h('button', { cls: 'round', on: { click: () => { keep(cfg); go('cfg'); home(); } } }, '‹')),
      h('div', { cls: 'card' }, h('h2', {}, 'कौन-से apps दिखें'), CATALOG.map(([id, name]) => row(name, '', cfg.apps.includes(id), () => {
        cfg.apps = cfg.apps.includes(id) ? cfg.apps.filter(x => x !== id) : cfg.apps.concat(id);
      }))),
      h('div', { cls: 'card' }, h('h2', {}, 'सुरक्षा'),
        row('🎞️ Reels / Shorts छुपाएँ', 'Facebook, Instagram, YouTube में', cfg.reels, () => { cfg.reels = !cfg.reels; }),
        row('▶ YouTube Restricted mode', 'गंदी videos छुपें', cfg.ytRestrict, () => { cfg.ytRestrict = !cfg.ytRestrict; })),
      h('div', { cls: 'card' }, h('h2', {}, '🔒 Settings का PIN'),
        h('p', { cls: 'note' }, 'PIN रखने पर कोई और ये settings नहीं बदल पाएगा। खाली छोड़ें = PIN नहीं।'), pinIn,
        h('button', { cls: 'big', on: { click: () => { cfg.pin = pinIn.value.trim(); keep(cfg); pinIn.value = ''; pinIn.placeholder = cfg.pin ? '••••' : 'नया PIN'; } } }, cfg.pin ? 'PIN बदलें / हटाएँ' : 'PIN रखें')),
      h('button', { cls: 'big', style: 'background:var(--ok)', on: { click: () => { keep(cfg); go('cfg'); home(); } } }, '✔ Save'));
  }
  home();
})();
