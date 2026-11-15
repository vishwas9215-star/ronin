// Ronin scam check: the engine of the "क्या यह ठगी है?" tool (and of the tests).
// - text(t): does a message look like a scam? Decides on a SCORE: one heavy sign (asking for the
//   OTP / PIN, "pay first", "scan to receive money", "stay on the video call") or two ordinary
//   signs together; advice ("OTP किसी को मत बताना") lowers it. Never one word alone.
// - link(url): must this link stay closed? (same rules as internal/safelinks and SafeLinks.kt)
// - mail(m): a whole email: text + who sent it (a "CBI / SBI" name from a gmail.com address) +
//   risky attachments.
// Everything runs on this device; nothing is sent anywhere.
(function (root) {
  'use strict';
  if (root.RoninScam) return;

  // ---- text clean-up: same spelling for ज़/ज, फ़/फ, joiners, curly quotes
  function norm(s) {
    return String(s || '').normalize('NFC').replace(/[़​-‍﻿]/g, '').replace(/ँ/g, 'ं')
      .replace(/[‘’]/g, "'").replace(/[“”]/g, '"').toLowerCase().replace(/\s+/g, ' ');
  }
  const RX = src => new RegExp(norm(src).replace(/\\s\+/g, '\\s*'), 'iu');

  // the rules (internal/safelinks/scamrules.json) come from Ronin, the same for Android and the tests
  const RULES = root.__roninScamRules || { groups: [], rumour: [] };
  const NONE = /(?!)/;
  // reasons in the phone's language (Ronin sets __roninLang; English unless the device is Hindi)
  const LANG = String(root.__roninLang || (typeof navigator !== 'undefined' && navigator.language) || 'hi').toLowerCase();
  const EN = LANG.indexOf('hi') !== 0;
  const T = (hi, en) => EN ? en : hi;
  const WHY_EN = RULES.whyEn || {};
  const G = RULES.groups.map(([id, w, src, why]) => ({ id, w, re: RX(src), why: EN && WHY_EN[id] ? WHY_EN[id] : why }));
  // short Latin names written in capitals only ("ED" inside "need" must not count)
  const CAPS = RULES.caps ? new RegExp(RULES.caps) : NONE;
  // advice / awareness / news about scams lowers the score (never removes it)
  const ADVICE = RULES.advice ? RX(RULES.advice) : NONE;
  // things that already happened ("आ गया", "जमा हो गया") are not requests
  const DONE = RULES.done ? RX(RULES.done) : NONE;
  // ordinary school / family words (UP teachers): lower the score a little
  const NORMAL = RULES.normal ? RX(RULES.normal) : NONE;
  // postal PIN codes are not the UPI PIN
  const PINCODE = RULES.pincode ? RX(RULES.pincode) : NONE;
  // forwarded rumours: the FORM of a rumour, never its topic or side
  const RUMOUR = (RULES.rumour || []).map(RX);

  // ---- 1. a message
  function text(t0) {
    const t = norm(t0).slice(0, 6000);
    const res = { level: '', score: 0, reasons: [], ids: [], rumour: false };
    if (t.length < 8) return res;
    const tt = t.replace(PINCODE, ' ');
    let score = 0, heavy = false;
    for (const g of G) {
      if (g.re.test(tt)) {
        score += g.w; res.ids.push(g.id);
        if (g.w >= 3) heavy = true;
        if (g.id !== 'link' && g.id !== 'urgent') res.reasons.push(g.why);
      }
    }
    // a link in the message that Ronin counts as dangerous is a heavy sign itself
    const urls = String(t0 || '').match(/(https?:\/\/[^\s<>"')]+|www\.[^\s<>"')]+|\b[a-z0-9-]+(\.[a-z0-9-]+)*\.(web\.app|netlify\.app|vercel\.app|xyz|top|click|link|online|site|live|shop|icu|sbs|cfd|buzz|me|ly|gd|gl|ee)\b[^\s<>"')]*)/gi) || [];
    for (const u0 of urls.slice(0, 10)) {
      const v = check(/^https?:/i.test(u0) ? u0 : 'https://' + u0);
      if (v) { score += 3; heavy = true; res.ids.push('badlink'); res.reasons.push(T('message में ऐसा link है जो Ronin कभी नहीं खोलता: ', 'The message has a link Ronin never opens: ') + LINK_WHY[v][0]); break; }
    }
    if (!res.ids.includes('auth') && CAPS.test(String(t0 || ''))) { score += 2; res.ids.push('auth'); res.reasons.push(T('खुद को पुलिस / CBI / कोर्ट बताना', 'Says they are police / CBI / a court')); }
    // "link" and "urgent" only add to real signs, never make a warning on their own
    const real = res.ids.filter(i => i !== 'link' && i !== 'urgent').length;
    if (!real) score = 0;
    // advice / news about scams with nothing asked of the reader is information, not a scam
    const REQ = RULES.requests || [];
    const advice = ADVICE.test(t);
    if (advice && !res.ids.some(i => REQ.includes(i))) { res.ids.push('advice'); score = Math.min(score, 2); }
    if (advice) score -= 2;
    if (DONE.test(t) && !heavy) score -= 1;
    if (NORMAL.test(t)) score -= 1;
    res.score = score;
    if (score >= 5 || (heavy && score >= 4)) res.level = 'stop';
    else if (score >= 3) res.level = 'warn';
    if (!res.level) {
      res.reasons = [];
      res.rumour = RUMOUR.some(r => r.test(t));
    }
    return res;
  }

  // ---- 2. a link (same as internal/safelinks Check; lists from Ronin or the test)
  const L = root.__roninLinkLists || {};
  const list = k => L[k] || [];
  const hostIs = (h, d) => h === d || h.endsWith('.' + d);
  const hostPathIn = (h, p, l) => l.some(d => { const j = d.indexOf('/'); return j > 0 ? hostIs(h, d.slice(0, j)) && p.startsWith(d.slice(j)) : hostIs(h, d); });
  const toks = s => s.split(/[.\-_0-9]+/).filter(Boolean);
  const BRANDS = ['sbi', 'hdfc', 'icici', 'axis', 'pnb', 'kotak', 'canara', 'bob', 'paytm', 'phonepe', 'gpay', 'uidai', 'aadhaar', 'aadhar', 'epfo', 'incometax', 'irctc', 'uppcl', 'npci', 'rbi', 'sebi'];
  const VERBS = ['kyc', 'ekyc', 'login', 'verify', 'verification', 'update', 'secure', 'reward', 'rewards', 'refund', 'cashback', 'bonus', 'support', 'care', 'helpline', 'otp', 'pan', 'link', 'points', 'redeem', 'block', 'unblock'];
  const ADULT = /(porn|xxx|xvideos|xnxx|xhamster|hentai|onlyfans|chaturbate|stripchat|escort|nude|camgirl|casino|betting|satta|matka|1xbet|dafabet|parimatch|rummy|teenpatti|antarvasna|kamukta|spankbang|eporner)/i;
  function check(raw0) {
    const raw = String(raw0 || '').trim(), low = raw.toLowerCase();
    const i = low.indexOf(':');
    if (i <= 0) return '';
    const scheme = low.slice(0, i);
    if (list('paySchemes').includes(scheme)) return 'pay';
    if (scheme === 'tel') return /[*#]|%2a|%23/.test(low) ? 'ussd' : '';
    if (scheme === 'whatsapp') return /code=|invite/.test(low) ? 'invite' : '';
    if (list('badSchemes').includes(scheme)) return 'scheme';
    if (scheme !== 'http' && scheme !== 'https') return '';
    let u;
    try { u = new URL(raw); } catch (e) { return ''; }
    if (u.username || u.password || /^https?:\/\/[^/?#]*@/i.test(raw)) return 'at';
    const host = u.hostname.toLowerCase().replace(/\.$/, '').replace(/^\[|\]$/g, '');
    if (!host) return '';
    if (/:/.test(host) || /^0x/.test(host) || /^[\d.]+$/.test(host)) return 'ip';
    if (/(^|\.)xn--/.test(host)) return 'puny';
    const path = u.pathname.toLowerCase();
    if (hostPathIn(host, path, list('telegram'))) return 'telegram';
    if (hostPathIn(host, path, list('invites'))) return 'invite';
    if ((hostIs(host, 'wa.me') || hostIs(host, 'whatsapp.com')) && /invite|\/join/.test(path)) return 'invite';
    if (hostPathIn(host, path, list('remote'))) return 'remote';
    if (hostPathIn(host, path, list('short'))) return 'short';
    if (hostPathIn(host, path, list('apkHosts'))) return 'apk';
    for (const e of list('fileExt')) if (path.endsWith(e)) return e.includes('apk') ? 'apk' : 'file';
    const tok = toks(host);
    if (tok.some(t => list('apkWords').includes(t))) return 'apk';
    if (ADULT.test(host)) return 'adult';
    if (list('officialEnds').some(e => hostIs(host, e))) return '';
    let name = tok.length > 1 ? tok.slice(0, -1) : tok;
    if (name.length > 1 && ['co', 'com', 'org', 'net', 'gov', 'ac', 'edu'].includes(name[name.length - 1])) name = name.slice(0, -1);
    for (const t of name) for (const w of list('moneyWords')) if (t === w || (w.length >= 5 && (t.startsWith(w) || t.endsWith(w)))) return 'money';
    let free = false, words = name;
    for (const d of list('freeHosts')) {
      if (hostIs(host, d) && host !== d) free = true;
      else if (host === d && d === 'sites.google.com') { free = true; words = toks(path); }
    }
    if (free && words.some(t => list('lureWords').includes(t))) return 'fake';
    if (name.some(t => BRANDS.includes(t)) && name.some(t => VERBS.includes(t))) return 'fake';
    return '';
  }
  // the real address behind the sites' own "you are leaving" links
  function realTarget(href, base) {
    try {
      const u = new URL(href, base || (root.location && root.location.href) || undefined);
      const p = u.searchParams;
      if (/^l\.(facebook|instagram)\.com$|^lm\.facebook\.com$|^l\.messenger\.com$/.test(u.hostname) && p.get('u')) return p.get('u');
      if (/youtube\.com$/.test(u.hostname) && u.pathname === '/redirect' && p.get('q')) return p.get('q');
      if (/(^|\.)google\.[a-z.]+$/.test(u.hostname) && u.pathname === '/url' && (p.get('q') || p.get('url'))) return p.get('q') || p.get('url');
      if (/^(www\.)?google\.com$/.test(u.hostname) && u.pathname === '/amp/s/') return 'https://' + u.pathname.slice(7);
      return u.href;
    } catch (e) { return String(href || ''); }
  }
  const LINK_WHY_HI = {
    pay: ['पैसे भेजने / UPI app का link', 'पैसा लेने के लिए कभी PIN डालना या QR scan करना नहीं पड़ता। अनजान के कहने पर पैसा न भेजें।'],
    ussd: ['Call-forwarding वाला code (*401*, *21*...)', 'इसे dial करने से आपकी calls और OTP ठग के फ़ोन पर चले जाते हैं।'],
    scheme: ['छुपा हुआ / खतरनाक तरह का link', 'ऐसे link से फ़ोन में कुछ भी चल सकता है।'],
    invite: ['अनजान group में जोड़ने वाला link', 'अनजान group में निवेश, नौकरी, लॉटरी के नाम पर पैसे माँगे जाते हैं।'],
    at: ['नकली पता (@ वाली चाल)', 'यह link असली site का नाम दिखाकर किसी दूसरी site पर ले जाता है।'],
    ip: ['नाम की जगह सिर्फ़ नंबर वाला पता', 'असली बैंक / सरकारी साइटें ऐसे पते पर नहीं होतीं।'],
    puny: ['मिलते-जुलते अक्षरों वाला नकली पता', 'यह पता असली जैसा दिखता है पर उसमें दूसरी भाषा के अक्षर हैं।'],
    telegram: ['Telegram पर ले जाने वाला link', 'अनजान group / channel में निवेश या task के लिए पैसे माँगना जोखिम भरा है।'],
    remote: ['Screen share / remote control app (AnyDesk जैसा)', 'इससे सामने वाला आपका पूरा फ़ोन चला सकता है। कोई customer care ऐसा app नहीं डलवाता।'],
    short: ['छोटा (छुपा हुआ) link', 'यह link असली पता छुपाता है।'],
    apk: ['App file (.apk) / mod app', 'ऐसी app से फ़ोन hack हो सकता है। app सिर्फ़ Play Store से लें।'],
    file: ['चलने वाली file (.exe / .bat...)', 'ऐसी file से कंप्यूटर में वायरस आ सकता है।'],
    adult: ['गंदी / जुआ वाली साइट', 'यह साइट सुरक्षित नहीं है।'],
    money: ['पैसे / इनाम / निवेश वाली साइट', 'ऐसी साइटें अक्सर ठगी के लिए बनती हैं।'],
    fake: ['बैंक / सरकार / इनाम के नाम की नकली साइट', 'असली सरकारी साइट .gov.in / .nic.in पर और बैंक की अपनी साइट पर होती है।'],
  };  const LINK_WHY_EN = {
    pay: ['A link to send money / a UPI app', 'You never enter a PIN or scan a QR to receive money. Never send money because a stranger asks.'],
    ussd: ['A call-forwarding code (*401*, *21*...)', 'Dialling it sends your calls and OTPs to the crook\'s phone.'],
    scheme: ['A hidden / dangerous kind of link', 'A link like this can run anything on your phone.'],
    invite: ['A link that adds you to an unknown group', 'Unknown groups ask for money in the name of investment, jobs or lotteries.'],
    at: ['A fake address (the @ trick)', 'This link shows a real site\'s name but takes you to another site.'],
    ip: ['An address that is only numbers', 'Real banks and government sites are never at such addresses.'],
    puny: ['A fake address with look-alike letters', 'This address looks real but uses letters from another alphabet.'],
    telegram: ['A link that goes to Telegram', 'Unknown groups / channels asking money for investment or tasks are risky.'],
    remote: ['A screen share / remote control app (like AnyDesk)', 'With it the other person can run your whole phone. No customer care asks you to install it.'],
    short: ['A short (hidden) link', 'This link hides the real address.'],
    apk: ['An app file (.apk) / mod app', 'Such an app can hack your phone. Install apps only from the Play Store.'],
    file: ['A program file (.exe / .bat...)', 'Such a file can put a virus on the computer.'],
    adult: ['A dirty / gambling site', 'This site is not safe.'],
    money: ['A money / prize / investment site', 'Such sites are often made for scams.'],
    fake: ['A fake site in the name of a bank / government / prize', 'Real government sites are on .gov.in / .nic.in, and banks on their own site.'],
  };
  const LINK_WHY = EN ? LINK_WHY_EN : LINK_WHY_HI;

  function link(href, base) {
    const target = realTarget(href, base), why = check(target);
    return why ? { target, why, title: LINK_WHY[why][0], text: LINK_WHY[why][1] } : null;
  }

  // ---- 3. an email: text + sender + attachments
  const FREE_MAIL = /(^|\.)(gmail\.com|googlemail\.com|yahoo\.[a-z.]+|ymail\.com|outlook\.[a-z.]+|hotmail\.[a-z.]+|live\.[a-z.]+|msn\.com|rediffmail\.com|rediff\.com|proton\.me|protonmail\.[a-z]+|aol\.com|mail\.com|gmx\.[a-z.]+|yandex\.[a-z.]+|icloud\.com|zohomail\.in|tutanota\.com|mail\.ru)$/;
  const AUTH_NAME = RX('(cbi|सीबीआई|police|पुलिस|cyber|साइबर|court|न्यायालय|कोर्ट|income\\s*tax|आयकर|\\bbank\\b|बैंक|sbi|hdfc|icici|axis|pnb|uidai|aadhaar|आधार|epfo|ministry|मंत्रालय|government|govt|सरकार|ifso|\\bncb\\b|narcotics|customs|कस्टम|fedex|\\bdhl\\b|india\\s*post|\\brbi\\b|reserve\\s*bank|sebi|trai|electricity|बिजली|uppcl|interpol|इंटरपोल|crime\\s*branch|enforcement|प्रवर्तन|investigation|जाँच\\s*एजेंसी|commissioner|आयुक्त)');
  const BAD_FILE = /\.(apk|apks|xapk|exe|msi|scr|bat|cmd|vbs|js|jse|wsf|ps1|lnk|jar|iso|img|hta)$/i;
  const ODD_FILE = /\.(html?|shtml|svg|zip|rar|7z)$/i;
  function mail(m) {
    m = m || {};
    const res = text([m.subject, m.body].filter(Boolean).join(' \n '));
    let score = res.score;
    const from = String(m.from || '').toLowerCase().trim(), dom = from.split('@')[1] || '';
    const who = [m.name, m.subject].filter(Boolean).join(' ');
    const official = /(\.gov\.in|\.nic\.in|\.bank\.in|\.sbi|\.ac\.in)$/.test(dom);
    // "Cyber Crime Cell" from xyz@gmail.com, or a made-up "government-looking" address (cybercell-gov.in.net)
    if (dom && !official && AUTH_NAME.test(norm(who)) && (FREE_MAIL.test(dom) || /(gov|police|cbi|court|customs|ncb|cyber|crime|interpol)/.test(dom))) {
      score += 3; res.ids.push('sender');
      res.reasons.unshift(EN ? 'The name / subject says police / bank / government, but the email came from an ordinary address like ' + dom + ': real departments never write from such addresses' : 'नाम / विषय में पुलिस-बैंक-सरकार लिखा है, पर email ' + dom + ' जैसे आम पते से आया है: असली विभाग ऐसे पते से नहीं लिखते');
    }
    for (const f of (m.files || [])) {
      const n = String(f || '').toLowerCase().trim();
      if (BAD_FILE.test(n)) { score += 3; res.ids.push('file'); res.reasons.push(T('खतरनाक attachment: ' + n + ' (इसे न खोलें)', 'Dangerous attachment: ' + n + ' (do not open it)')); }
      else if (ODD_FILE.test(n)) { score += 1; res.ids.push('file'); res.reasons.push(T('ध्यान दें: attachment ' + n + ' में नकली login page हो सकता है', 'Careful: attachment ' + n + ' may hold a fake login page')); }
    }
    res.score = score;
    const heavy = res.ids.some(i => ['otp', 'payfirst', 'qr', 'arrest', 'stay', 'sender'].includes(i)) || res.ids.includes('file') && score >= 3;
    res.level = score >= 5 || (heavy && score >= 4) ? 'stop' : score >= 3 ? 'warn' : '';
    if (res.level) res.rumour = false;
    return res;
  }

  root.RoninScam = { text, link, check, realTarget, mail, norm, LINK_WHY };
  if (typeof module !== 'undefined' && module.exports) module.exports = root.RoninScam;
})(typeof window !== 'undefined' ? window : globalThis);
