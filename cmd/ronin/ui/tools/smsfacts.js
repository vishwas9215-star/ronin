// Ronin Messages: the MAIN point of an SMS at a glance (owner, 19 Nov 2026: "I should not have to read the
// whole message"). Money is coloured by what happened to it (balance, money gone, money come, a bill to pay),
// plus PNR, delivery and plan-expiry. Nothing leaves the phone; tests: tools/uitest/smsfacts.js.
(function (root) {
  'use strict';
  const AMT = /(?:rs\.?|inr|₹|रु\.?|रुपये)\s*([0-9][0-9,]*(?:\.\d{1,2})?)/gi;
  // what the money near a word means; the NEAREST word before the amount wins ("debited ... Avl Bal Rs 5")
  const KINDS = [
    ['bal', /avl\.?\s*bal|available\s*bal|\bbal(ance)?\b|\bbal\b|शेष|बैलेंस/gi],
    ['debit', /debited|deducted|\bspent\b|\bpaid\b|\bsent\b|withdrawn|withdrawal|purchase|\btxn\b|transferred to|\bdr\b|कटे|कट गए|निकासी|भुगतान किया/gi],
    ['credit', /credited|received|deposited|refund(ed)?|cashback|\bcr\b|जमा|प्राप्त/gi],
    ['due', /\bdue\b|payable|\bbill\b|\bemi\b|premium|outstanding|min(imum)?\.? amt|total amt|बकाया|देय|बिल/gi],
  ];
  function lastIndex(re, s) { re.lastIndex = 0; let m, at = -1; while ((m = re.exec(s))) at = m.index; return at; }
  function firstIndex(re, s) { re.lastIndex = 0; const m = re.exec(s); return m ? m.index : -1; }
  function kindAt(text, start, end) {
    const before = text.slice(Math.max(0, start - 60), start), after = text.slice(end, end + 35);
    let best = '', at = -1;
    for (const [k, re] of KINDS) { const i = lastIndex(re, before); if (i > at) { at = i; best = k; } }
    if (best) return best;
    let first = 1e9;
    for (const [k, re] of KINDS) { const i = firstIndex(re, after); if (i >= 0 && i < first) { first = i; best = k; } }
    return best;
  }
  /** every amount with its kind: [{start, end, kind, value}] */
  function amounts(text) {
    const s = String(text || ''), out = [];
    AMT.lastIndex = 0; let m;
    while ((m = AMT.exec(s))) {
      const kind = kindAt(s, m.index, m.index + m[0].length);
      if (kind) out.push({ start: m.index, end: m.index + m[0].length, kind, value: m[1] });
    }
    return out;
  }
  const DATE = /(\d{1,2}[-/.](?:\d{1,2}|[a-z]{3})[-/.]\d{2,4}|\d{1,2}\s*(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*(?:\s*,?\s*\d{2,4})?)/i;
  function facts(text, L) {
    L = L || ((hi, en) => en);
    const s = String(text || ''), out = [], seen = {};
    const name = { bal: L('💰 बैलेंस', '💰 Balance'), debit: L('➖ गए', '➖ Out'), credit: L('➕ आए', '➕ In'), due: L('🧾 भरना है', '🧾 To pay') };
    for (const a of amounts(s)) {
      if (seen[a.kind]) continue; // the first of each kind is enough
      seen[a.kind] = 1;
      let t = name[a.kind] + ' ₹' + a.value;
      if (a.kind === 'due') { const d = DATE.exec(s.slice(a.end, a.end + 60)) || DATE.exec(s); if (d) t += L(' • आख़िरी तारीख़ ', ' • by ') + d[1]; }
      out.push({ kind: a.kind, text: t });
    }
    const pnr = /\bpnr\s*(?:no\.?|number)?\s*[:\-]?\s*(\d{10})\b/i.exec(s);
    if (pnr) out.push({ kind: 'info', text: '🎫 PNR ' + pnr[1] });
    if (/\b(has been|have been|was|successfully|is now)\s+delivered\b/i.test(s) && !/\bnot\b|n't|\?/i.test(s)) out.push({ kind: 'credit', text: L('📦 डिलीवर हो गया', '📦 Delivered') });
    else if (/out for delivery|arriving today|will be delivered today/i.test(s)) out.push({ kind: 'info', text: L('🚚 आज आएगा', '🚚 Coming today') });
    else if (/\b(shipped|dispatched)\b/i.test(s)) out.push({ kind: 'info', text: L('📦 भेज दिया गया', '📦 Shipped') });
    const exp = /(expir(?:es|y|ed)|valid till|validity ends?|will expire|समाप्त)[^0-9a-z]{0,20}(?:on\s*)?/i.exec(s);
    if (exp) { const d = DATE.exec(s.slice(exp.index, exp.index + 60)); if (d) out.push({ kind: 'due', text: L('⏳ ख़त्म: ', '⏳ Ends: ') + d[1] }); }
    return out;
  }
  // an OTP only when the SMS SAYS OTP (owner, 19 Nov 2026: a BSE trade SMS "Client Code WMZS70784L" was shown as
  // an OTP). The code must be a number on its own, never digits inside a word like WMZS70784L.
  const W = '(?:\\botp\\b|one.?time.?password|verification code|ओटीपी)', C = '(?<![A-Za-z0-9])(\\d{4,8})(?!\\d)';
  const OTP = [new RegExp(W + '\\s*(?:is|:|-|=)?\\s*' + C, 'i'), new RegExp(W + '[^.\\n]{0,60}?\\b(?:is|are|:)\\s*' + C, 'i'),
    new RegExp(C + '[^0-9]{0,30}?' + W, 'i'), new RegExp(W + '[^0-9]{0,40}?' + C, 'i')];
  function otp(text) { const s = String(text || ''); for (const re of OTP) { const m = re.exec(s); if (m) return m[1]; } return ''; }
  root.RoninFacts = { amounts, facts, otp };
  if (typeof module !== 'undefined' && module.exports) module.exports = root.RoninFacts;
})(typeof window !== 'undefined' ? window : globalThis);
