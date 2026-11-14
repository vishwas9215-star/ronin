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
  // email: Safe Mail checks every opened email (threats, fake "CBI / bank" mails) and offers Delete
  const MAILS = [
    ['gmail', 'Gmail', '✉', '#d93025', 'https://mail.google.com/'],
    ['outlook', 'Outlook', '✉', '#0f6cbd', 'https://outlook.live.com/mail/'],
    ['yahoo', 'Yahoo Mail', '✉', '#6001d2', 'https://mail.yahoo.com/'],
    ['rediff', 'Rediffmail', '✉', '#c8102e', 'https://mail.rediff.com/'],
  ];
  const DEFAULT = { apps: ['facebook', 'instagram', 'youtube', 'whatsapp', 'gmail'], reels: false, ytRestrict: true, pin: '', custom: [], mailApp: 'gmail' };
  const ALL = () => CATALOG.concat(MAILS, (cfg.custom || []).map((c, i) => ['c' + i, c.name, c.mail ? '✉' : '★', c.mail ? '#475569' : '#0e7490', c.url]));
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
  const shared = () => btoa(unescape(encodeURIComponent(JSON.stringify({ apps: cfg.apps, reels: cfg.reels, ytRestrict: cfg.ytRestrict, custom: cfg.custom || [], mailApp: cfg.mailApp || 'gmail' }))));
  const go = (what, extra) => { if (KEY) location.href = 'https://ronin.data/social?do=' + what + '&k=' + KEY + '&cfg=' + encodeURIComponent(shared()) + (extra || ''); };
  window.__roninSocialPage = { cfg: () => cfg, catalog: CATALOG, mails: MAILS }; // for the tests

  function home() {
    app.innerHTML = '';
    const mine = ALL().filter(a => cfg.apps.includes(a[0]) || a[0][0] === 'c');
    app.append(
      h('div', { cls: 'top' }, h('h1', {}, '🛡️ Safe Social'),
        h('div', {}, h('button', { cls: 'pill', on: { click: askPin } }, '⚙ Settings'), ' ', h('button', { cls: 'pill', on: { click: () => go('close') } }, '✕ बंद'))),
      h('p', { cls: 'note' }, 'यहाँ से खोलने पर गंदे ads हटते हैं, अनजान Telegram/WhatsApp group, पैसे और ठगी वाले link पूरी तरह रुकते हैं, ठगी / अफवाह वाले messages पर चेतावनी आती है, और ठगी / धमकी वाले email पर Delete का बटन आता है।'),
      h('div', { cls: 'sos' }, h('b', {}, '🆘 पैसा चला गया / ठगी हुई?'),
        h('div', { cls: 'row2' }, h('a', { cls: 'pill red', href: 'tel:1930' }, '📞 1930 पर call'), h('a', { cls: 'pill', href: 'https://cybercrime.gov.in/' }, '🌐 cybercrime.gov.in'),
          h('a', { cls: 'pill', href: 'https://sancharsaathi.gov.in/sfc/' }, '📵 ठगी वाला नंबर / SMS (चक्षु)')),
        h('small', {}, 'जितनी जल्दी 1930 पर बताएँगे, पैसा रुकने की उतनी उम्मीद। अपने बैंक को भी तुरंत बताएँ।')),
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

  function mailPick() {
    const sel = h('select', { cls: 'sel' }, MAILS.concat((cfg.custom || []).map((c, i) => ['c' + i, c.name]).filter((x, i) => (cfg.custom[i] || {}).mail)).map(([id, name]) => h('option', { value: id }, name)));
    sel.value = cfg.mailApp || 'gmail';
    sel.onchange = () => { cfg.mailApp = sel.value; };
    return sel;
  }
  function customList() {
    return h('div', {}, (cfg.custom || []).map((c, i) => h('div', { cls: 'row' }, h('div', {}, (c.mail ? '✉ ' : '★ ') + c.name, h('small', {}, c.url)),
      h('button', { cls: 'pill', on: { click: () => { cfg.custom.splice(i, 1); if (cfg.mailApp === 'c' + i) cfg.mailApp = 'gmail'; settings(); } } }, 'हटाएँ'))));
  }
  function addCustom() {
    const name = h('input', { cls: 'txt', placeholder: 'नाम', maxlength: 30 }), url = h('input', { cls: 'txt', placeholder: 'https://...', inputmode: 'url' });
    const mail = h('select', { cls: 'sel' }, h('option', { value: '' }, 'App / website'), h('option', { value: '1' }, 'Email'));
    const err = h('div', { cls: 'err' });
    return h('div', {}, name, url, mail, err, h('button', { cls: 'big', on: { click: () => {
      let u = url.value.trim();
      if (u && !/^https?:\/\//i.test(u)) u = 'https://' + u;
      let ok = false;
      try { ok = new URL(u).protocol === 'https:' && name.value.trim(); } catch (e) { ok = false; }
      if (!ok) { err.textContent = 'नाम और सही https:// पता लिखें'; return; }
      if (window.RoninScam && window.RoninScam.check(u)) { err.textContent = 'यह पता Ronin के हिसाब से ख़तरनाक है, नहीं जोड़ा'; return; }
      cfg.custom = (cfg.custom || []).concat({ name: name.value.trim(), url: u, mail: mail.value === '1' });
      settings();
    } } }, '➕ जोड़ें'));
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
      h('div', { cls: 'card' }, h('h2', {}, '📧 Email (Safe Mail)'), MAILS.map(([id, name]) => row(name, cfg.mailApp === id ? '"Ronin Mail" icon इसे खोलेगा' : '', cfg.apps.includes(id), () => {
        cfg.apps = cfg.apps.includes(id) ? cfg.apps.filter(x => x !== id) : cfg.apps.concat(id);
      })), h('div', { cls: 'row' }, h('div', {}, '"Ronin Mail" icon कौन-सा email खोले'), mailPick())),
      h('div', { cls: 'card' }, h('h2', {}, '➕ अपनी कोई और app / email site'), customList(),
        h('p', { cls: 'note' }, 'नाम और पता लिखें (जैसे: School Mail, https://mail.example.in). Email है तो "Email" चुनें: उस पर भी Safe Mail जाँच चलेगी।'),
        addCustom()),
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
