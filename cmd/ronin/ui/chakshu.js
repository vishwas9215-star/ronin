// Ronin: fills the government's Chakshu (Sanchar Saathi) "suspected fraud" form from the scam SMS / call the owner
// tapped "Report on Chakshu" on. Owner, 21 Nov 2026 (both hands fractured): "सारे कॉलम अपने आप भर जाएँ, बस कैप्चा डालकर
// सबमिट करना रहे". Ronin never types the CAPTCHA and never presses Submit: the owner checks the preview and submits.
// Kotlin starts it with window.__roninChakshu.start({medium, address, body, date, mobile}), reads .state() every 2 s and
// gives the OTP from Ronin Phone with .otp(code).
(function () {
  if (window.__roninChakshu) return;
  const ALLOWED = /[^-*#A-Za-z0-9,.!:/%"{}+()&@?\s]/g; // the form's own rule for "complaint details"
  const st = { phase: 'new', otpSince: 0, needOtp: false, mobile: '', note: '' };
  const $id = (id) => document.getElementById(id);
  const pad = (n) => (n < 10 ? '0' : '') + n;

  function clean(s) { return String(s || '').replace(ALLOWED, ' ').replace(/\s+/g, ' ').trim(); }

  // which of the form's categories fits the message (values are the form's own option values)
  function category(medium, body) {
    const b = String(body || '').toLowerCase();
    if (/sextort|nude|video call record|अश्लील/.test(b)) return '5';
    if (/\b(cbi|police|customs|narcotics|aadhaar|aadhar|rbi|ed officer|arrest|warrant|पुलिस|गिरफ्तार)\b/.test(b)) return '2';
    if (/\b(trai|dot|department of telecom)\b|sim (will be )?(block|deactivat)|number (will be )?(block|deactivat)/.test(b)) return '10';
    if (/\b(kyc|pan card|account (will be )?(block|suspend|freez)|electricity|bijli|gas connection|insurance|policy|refund)\b|बिजली/.test(b)) return '1';
    if (/\b(invest|stock|share market|trading|ipo|crypto|forex|returns? of|profit daily)\b/.test(b)) return '9';
    if (/\b(loan|lottery|lucky draw|prize|gift|reward|winner|job|work from home|part.?time|salary|earn)\b|लोन|इनाम|नौकरी/.test(b)) return '4';
    if (/customer care|helpline|toll.?free/.test(b)) return '3';
    if (/\b(mummy|papa|beta|bhai|uncle|hospital|urgent(ly)? need)\b|अस्पताल/.test(b)) return '11';
    if (/https?:\/\/|bit\.ly|tinyurl|\bwww\./.test(b)) return '7';
    return '30';
  }

  function sender(address) {
    const a = String(address || '').trim();
    const digits = a.replace(/[^\d]/g, '');
    if (/^[+\d\s()-]+$/.test(a) && digits.length >= 10) return { number: digits.slice(-10) };
    return { header: a.replace(/[^A-Za-z0-9-]/g, '').toUpperCase().slice(0, 14) };
  }

  function numbersIn(body) {
    const out = [];
    (String(body || '').match(/(?:\+?91[\s-]?)?[6-9]\d{4}[\s-]?\d{5}\b|\b1800[\s-]?\d{3}[\s-]?\d{4}\b/g) || []).forEach((m) => {
      const d = m.replace(/[^\d]/g, ''); const n = d.length > 10 && !d.startsWith('1800') ? d.slice(-10) : d;
      if (out.indexOf(n) < 0) out.push(n);
    });
    return out.slice(0, 5).join(', ');
  }

  function urlsIn(body) {
    const out = [];
    (String(body || '').match(/https?:\/\/[^\s,]+|\b(?:www\.|bit\.ly\/|tinyurl\.com\/)[^\s,]+/g) || []).forEach((u) => {
      u = u.replace(/[.)\]]+$/, ''); if (out.indexOf(u) < 0) out.push(u);
    });
    return out.slice(0, 5).join(', ');
  }

  function when(ms) {
    const d = new Date(ms || Date.now());
    const h = d.getHours(), h12 = h % 12 || 12;
    return { date: d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()), time: pad(h12) + ':' + pad(d.getMinutes()) + ' ' + (h < 12 ? 'AM' : 'PM'), old: Date.now() - d.getTime() > 29 * 864e5 };
  }

  function remarks(data, w) {
    const who = clean(data.address) || 'unknown sender';
    if (data.medium === 'call') return clean('Suspected fraud call received from ' + who + ' on ' + w.date + ' at ' + w.time + '. The caller seemed to be a fraudster. Please take action against this number.').slice(0, 500);
    let text = clean(data.body);
    const head = 'Suspected fraud SMS from ' + who + ' on ' + w.date + ' at ' + w.time + '. SMS text: ';
    if (text.replace(/\s/g, '').length < 10) text = '(message is not in English) ' + text;
    return (head + text).slice(0, 500);
  }

  function set(id, v) {
    const e = $id(id); if (!e || v == null) return false;
    e.value = v;
    e.dispatchEvent(new Event('input', { bubbles: true }));
    e.dispatchEvent(new Event('change', { bubbles: true }));
    return true;
  }
  function pick(id) { const e = $id(id); if (e && !e.checked) e.click(); }
  function shown(id) { const e = $id(id); return !!(e && e.offsetParent !== null); }

  // a picture of the message: the form needs a screenshot (jpeg / png, at most 2 MB)
  function picture(data, w) {
    const c = document.createElement('canvas'), W = 1080, x = c.getContext('2d');
    const font = (px, bold) => (bold ? 'bold ' : '') + px + 'px sans-serif';
    x.font = font(40);
    const words = String(data.medium === 'call' ? 'Incoming call from ' + data.address : data.body || '').split(/\s+/);
    const lines = []; let line = '';
    words.forEach((wd) => { const t = line ? line + ' ' + wd : wd; if (x.measureText(t).width > W - 160 && line) { lines.push(line); line = wd; } else line = t; });
    if (line) lines.push(line);
    const H = Math.min(2400, 360 + lines.length * 56);
    c.width = W; c.height = H;
    x.fillStyle = '#f4f6fa'; x.fillRect(0, 0, W, H);
    x.fillStyle = '#111827'; x.font = font(48, true); x.fillText(String(data.address || ''), 60, 100);
    x.fillStyle = '#6b7280'; x.font = font(32); x.fillText((data.medium === 'call' ? 'Call' : 'SMS') + ' • ' + w.date + ' ' + w.time, 60, 150);
    x.fillStyle = '#ffffff'; x.fillRect(40, 190, W - 80, H - 230);
    x.fillStyle = '#111827'; x.font = font(40);
    lines.forEach((l, i) => { if (250 + i * 56 < H - 50) x.fillText(l, 80, 260 + i * 56); });
    return new Promise((res) => c.toBlob((b) => res(b), 'image/png'));
  }

  async function attach(data, w) {
    const inp = $id('user-sfc-file'); if (!inp || !window.DataTransfer) return false;
    const blob = await picture(data, w); if (!blob) return false;
    const dt = new DataTransfer();
    dt.items.add(new File([blob], (data.medium === 'call' ? 'call' : 'sms') + '-screenshot.png', { type: 'image/png' }));
    inp.files = dt.files;
    inp.dispatchEvent(new Event('change', { bubbles: true }));
    return true;
  }

  // the form reads the picture by itself (its own text reading) and writes its guess into the boxes: wait for it, then
  // write Ronin's exact values over the guess
  function afterReading(ms) {
    return new Promise((res) => {
      let done = false; const end = () => { if (!done) { done = true; res(); } };
      try {
        const $ = window.jQuery;
        if ($) { const f = (e, x, o) => { if (o && /jocr/i.test(o.url || '')) { $(document).off('ajaxComplete', f); setTimeout(end, 300); } }; $(document).on('ajaxComplete', f); }
      } catch (e) {}
      setTimeout(end, ms);
    });
  }

  function hideModals() {
    ['ocrFailureModal', 'ocrSenderFailureModal'].forEach((id) => {
      try { const el = $id(id); const m = el && window.bootstrap && window.bootstrap.Modal.getInstance(el); if (m) m.hide(); } catch (e) {}
    });
  }

  function banner(text) {
    let b = $id('ronin-chakshu');
    if (!b) {
      b = document.createElement('div'); b.id = 'ronin-chakshu';
      b.style.cssText = 'position:fixed;left:8px;right:8px;bottom:8px;z-index:2147483647;background:#14532d;color:#fff;font:600 17px/1.4 sans-serif;padding:12px 14px;border-radius:14px;box-shadow:0 4px 18px rgba(0,0,0,.35)';
      b.onclick = () => b.remove();
      document.body.appendChild(b);
    }
    b.textContent = text;
  }

  function fillFields(data, w) {
    set('sfc-medium', data.medium === 'call' ? '1' : '2');
    set('sfc-category', category(data.medium, data.body));
    const s = sender(data.address);
    if (data.medium === 'call') set('suspected-number', s.number || '');
    else if (s.number) { pick('sms-without-shortcode'); set('suspected-number', s.number); }
    else { pick('sms-with-shortcode'); set('suspected-shortcode', s.header); }
    set('sfc-date-input', w.date);
    set('sfc-time-input', w.time);
    set('sfc-remarks', remarks(data, w));
    if (data.medium !== 'call') { set('sms-cta', numbersIn(data.body)); set('sms-cta-url', urlsIn(data.body)); }
    if (/^[1-9]\d{9}$/.test(data.mobile || '')) set('mobile', data.mobile);
  }

  async function start(data) {
    if (st.phase !== 'new') return;
    st.phase = 'filling';
    const w = when(data.date);
    // essential cookies only: the banner covers the form
    [].forEach.call(document.querySelectorAll('button'), (b) => { if (/^accept essential cookies$/i.test(b.textContent.trim())) b.click(); });
    set('sfc-medium', data.medium === 'call' ? '1' : '2'); // the picture is read only for an SMS complaint
    const attached = await attach(data, w).catch(() => false);
    if (attached) await afterReading(9000);
    fillFields(data, w);
    hideModals();
    setTimeout(() => { fillFields(data, w); hideModals(); }, 1500); // a late answer of the form's reader must not win
    const sendOtp = $id('send-otp');
    if (sendOtp) sendOtp.addEventListener('click', () => { st.otpSince = Date.now() - 5000; st.needOtp = true; }, true);
    st.phase = 'filled';
    const noMobile = !/^[1-9]\d{9}$/.test(data.mobile || '');
    st.note = (w.old ? '⚠ यह संदेश 30 दिन से पुराना है, चक्षु तारीख़ नहीं लेगा। ' : '') + (attached ? '' : '⚠ Screenshot खुद जोड़ें। ');
    banner('🛡️ Ronin ने शिकायत भर दी। ' + st.note + (noMobile ? 'अपना mobile number और ' : '') + 'CAPTCHA लिखें → "Verify Mobile via OTP" दबाएँ। OTP अपने-आप भरेगा, फिर Submit दबाएँ।');
    const cap = $id('captcha');
    if (cap) { cap.scrollIntoView({ block: 'center' }); setTimeout(() => cap.focus(), 300); }
    watch();
  }

  // after the OTP is right: tick the declaration and open the preview; the owner reads it and presses Submit himself
  function watch() {
    let n = 0;
    const t = setInterval(() => {
      if (++n > 600) return clearInterval(t);
      st.mobile = ($id('mobile') || {}).value || '';
      if (st.phase === 'otp' && shown('declaration')) {
        pick('declaration');
        if (shown('preview-button')) { $id('preview-button').click(); st.phase = 'preview'; banner('✅ सब भर गया। एक बार देख लें, फिर नीचे "Submit" दबाएँ।'); clearInterval(t); }
      }
    }, 1000);
  }

  function otp(code) {
    if (!st.needOtp || !/^\d{4,8}$/.test(code)) return false;
    if (!set('mobile-otp', code)) return false;
    st.needOtp = false; st.phase = 'otp';
    const v = $id('verify-otp'); if (v) setTimeout(() => v.click(), 400);
    banner('🔑 OTP भर दिया (' + code + '), जाँच हो रही है...');
    return true;
  }

  function state() { return JSON.stringify({ phase: st.phase, needOtp: st.needOtp, otpSince: st.otpSince, mobile: st.mobile }); }

  window.__roninChakshu = { start, state, otp, _t: { category, sender, numbersIn, urlsIn, when, remarks, clean } };
})();
