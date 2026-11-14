// Ronin PDF / Photo tools. Everything runs inside this page, on this device: files are
// never uploaded. pdf.js (Apache-2.0) draws PDF pages, pdf-lib (MIT) writes PDFs, the
// browser's canvas does the photos. Results are handed to Ronin, which saves them in
// Downloads (see save()).
import * as pdfjs from './lib/pdf.min.mjs';

pdfjs.GlobalWorkerOptions.workerSrc = new URL('./lib/pdf.worker.min.mjs', import.meta.url).href;
const { PDFDocument, degrees } = window.PDFLib;
const q = new URLSearchParams(location.search);
const KEY = q.get('k') || '';
if (q.get('dark') === '1') document.body.classList.add('dark');
const app = document.getElementById('app');
const T = {}; // the tools, by id

// ------------------------------------------------------------------ small helpers
const h = (tag, attrs, ...kids) => {
  const e = document.createElement(tag);
  Object.entries(attrs || {}).forEach(([k, v]) => {
    if (k === 'on') Object.entries(v).forEach(([ev, f]) => e.addEventListener(ev, f));
    else if (k === 'cls') e.className = v;
    else if (v !== undefined && v !== null && v !== false) e.setAttribute(k, v === true ? '' : v);
  });
  kids.flat().forEach(c => e.append(c && c.nodeType ? c : document.createTextNode(c == null ? '' : String(c))));
  return e;
};
const kb = n => n < 1024 * 1024 ? Math.round(n / 1024) + ' KB' : (n / 1048576).toFixed(1) + ' MB';
const baseName = n => n.replace(/\.[a-z0-9]+$/i, '');
let msgEl = null;
function msg(text, bad) {
  if (!msgEl) return;
  msgEl.textContent = text;
  msgEl.className = bad ? 'err' : '';
  msgEl.style.display = text ? 'block' : 'none';
}
const wait = ms => new Promise(r => setTimeout(r, ms));
const nextFrame = () => new Promise(r => requestAnimationFrame(() => r()));

function pickFiles(accept, multiple) {
  const inp = document.getElementById('pick');
  inp.accept = accept;
  inp.multiple = !!multiple;
  inp.value = '';
  return new Promise(res => {
    inp.onchange = () => res(Array.from(inp.files || []));
    inp.click();
  });
}

function chips(options, value, onPick) {
  const box = h('div', { cls: 'chips' });
  const draw = () => {
    box.innerHTML = '';
    options.forEach(([v, label]) => box.append(h('button', { cls: 'chip' + (v === value ? ' on' : ''), on: { click: () => { value = v; draw(); onPick(v); } } }, label)));
  };
  draw();
  return box;
}

// ------------------------------------------------------------------ saving
// The page puts the file in window.__roninSave and opens https://ronin.data/save?k=KEY;
// Ronin reads it in pieces and writes it to Downloads, then sets state = 'saved'.
const saved = [];
async function save(name, mime, bytes) {
  saved.push({ name, mime, size: bytes.length });
  window.__lastSaved = { name, mime, bytes }; // for the tests
  if (!KEY) return;
  let b64 = '';
  for (let i = 0; i < bytes.length; i += 0x8000) b64 += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
  b64 = btoa(b64);
  const PART = 512 * 1024;
  window.__roninSave = { name, mime, n: Math.ceil(b64.length / PART), part: i => b64.slice(i * PART, (i + 1) * PART), state: 'waiting' };
  location.href = 'https://ronin.data/save?k=' + KEY;
  for (let i = 0; i < 600 && window.__roninSave.state === 'waiting'; i++) await wait(100);
  const ok = window.__roninSave.state === 'saved';
  window.__roninSave = null;
  if (!ok) throw new Error('file save नहीं हुई');
}
window.__roninTools = { saved };

// ------------------------------------------------------------------ PDF and image helpers
async function openPdf(file, password) {
  const data = new Uint8Array(await file.arrayBuffer());
  try {
    return await pdfjs.getDocument({ data: data.slice(), password, useSystemFonts: true, isEvalSupported: false }).promise;
  } catch (e) {
    if (e && e.name === 'PasswordException') {
      const err = new Error(password ? 'Password ग़लत है' : 'इस PDF पर password है: पहले "🔓 Password हटाओ" से खोलें');
      err.needPassword = true;
      throw err;
    }
    throw new Error('यह PDF खुल नहीं रही (' + (e && e.message || e) + ')');
  }
}
async function loadLib(file) {
  try {
    return await PDFDocument.load(await file.arrayBuffer());
  } catch (e) {
    if (/encrypt/i.test(String(e && e.message))) throw new Error(file.name + ': इस PDF पर password है, पहले "🔓 Password हटाओ" से खोलें');
    throw new Error(file.name + ': PDF खुल नहीं रही');
  }
}
async function renderPage(doc, n, scale) {
  const page = await doc.getPage(n);
  const vp = page.getViewport({ scale });
  const c = document.createElement('canvas');
  c.width = Math.max(1, Math.round(vp.width));
  c.height = Math.max(1, Math.round(vp.height));
  const ctx = c.getContext('2d');
  ctx.fillStyle = '#fff';
  ctx.fillRect(0, 0, c.width, c.height);
  await page.render({ canvasContext: ctx, viewport: vp }).promise;
  const base = page.getViewport({ scale: 1 });
  return { canvas: c, w: base.width, h: base.height };
}
function toBlob(canvas, type, quality) {
  return new Promise(r => canvas.toBlob(b => r(b), type, quality));
}
async function canvasBytes(canvas, type, quality) {
  return new Uint8Array(await (await toBlob(canvas, type, quality)).arrayBuffer());
}
async function loadImage(file) {
  const url = URL.createObjectURL(file);
  try {
    const img = new Image();
    img.src = url;
    await img.decode();
    return img;
  } catch (e) {
    throw new Error(file.name + ': यह photo खुल नहीं रही');
  } finally {
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
}
function scaled(img, maxSide) {
  const w0 = img.naturalWidth || img.width, h0 = img.naturalHeight || img.height;
  const s = Math.min(1, maxSide / Math.max(w0, h0));
  const c = document.createElement('canvas');
  c.width = Math.max(1, Math.round(w0 * s));
  c.height = Math.max(1, Math.round(h0 * s));
  const ctx = c.getContext('2d');
  ctx.fillStyle = '#fff';
  ctx.fillRect(0, 0, c.width, c.height);
  ctx.drawImage(img, 0, 0, c.width, c.height);
  return c;
}
function resizeCanvas(src, f) {
  const c = document.createElement('canvas');
  c.width = Math.max(1, Math.round(src.width * f));
  c.height = Math.max(1, Math.round(src.height * f));
  const ctx = c.getContext('2d');
  ctx.imageSmoothingQuality = 'high';
  ctx.drawImage(src, 0, 0, c.width, c.height);
  return c;
}
// text (Hindi too: the browser shapes it) drawn into a picture with a clear background
function textImage(text, px) {
  const font = '600 ' + px + 'px "Nirmala UI","Noto Sans Devanagari","Mangal","Segoe UI",sans-serif';
  const m = document.createElement('canvas').getContext('2d');
  m.font = font;
  const w = Math.ceil(m.measureText(text).width) + 8;
  const c = document.createElement('canvas');
  c.width = w;
  c.height = Math.ceil(px * 1.5);
  const ctx = c.getContext('2d');
  ctx.font = font;
  ctx.fillStyle = '#111';
  ctx.textBaseline = 'middle';
  ctx.fillText(text, 4, c.height / 2);
  return c;
}

// the smallest JPEG of a canvas that fits in `limit` bytes (quality first, then size)
async function jpegUnder(canvas, limit) {
  let c = canvas, best = null;
  for (let round = 0; round < 12; round++) {
    let lo = 0.3, hi = 0.92, fit = null;
    for (let i = 0; i < 7; i++) {
      const qy = (lo + hi) / 2;
      const b = await canvasBytes(c, 'image/jpeg', qy);
      if (b.length <= limit) { fit = b; lo = qy; } else hi = qy;
    }
    if (!fit) { const b = await canvasBytes(c, 'image/jpeg', 0.3); if (b.length <= limit) fit = b; }
    if (fit) return { bytes: fit, w: c.width, h: c.height };
    best = c;
    if (c.width < 60 || c.height < 60) break;
    c = resizeCanvas(c, 0.8);
  }
  return { bytes: await canvasBytes(best || c, 'image/jpeg', 0.3), w: c.width, h: c.height, over: true };
}

// ------------------------------------------------------------------ signature
const SIGN_KEY = 'ronin.signature';
function savedSign() { try { return localStorage.getItem(SIGN_KEY) || ''; } catch (e) { return ''; } }
function keepSign(url) { try { localStorage.setItem(SIGN_KEY, url); } catch (e) { /* this viewer only */ } }
// a photo of a signature on white paper: paper becomes clear, cut to the ink
function cleanSignature(img) {
  const c = scaled(img, 1200), ctx = c.getContext('2d');
  const d = ctx.getImageData(0, 0, c.width, c.height), p = d.data;
  let x0 = c.width, y0 = c.height, x1 = 0, y1 = 0;
  for (let y = 0; y < c.height; y++) for (let x = 0; x < c.width; x++) {
    const i = (y * c.width + x) * 4, lum = 0.299 * p[i] + 0.587 * p[i + 1] + 0.114 * p[i + 2];
    if (lum > 165) p[i + 3] = 0;
    else {
      p[i + 3] = Math.min(255, Math.round((165 - lum) * 3));
      if (x < x0) x0 = x; if (y < y0) y0 = y; if (x > x1) x1 = x; if (y > y1) y1 = y;
    }
  }
  ctx.putImageData(d, 0, 0);
  if (x1 <= x0 || y1 <= y0) throw new Error('photo में signature नहीं दिखा: सफ़ेद काग़ज़ पर गहरे pen से किया sign चुनें');
  const out = document.createElement('canvas');
  out.width = x1 - x0 + 9; out.height = y1 - y0 + 9;
  out.getContext('2d').drawImage(c, x0 - 4, y0 - 4, out.width, out.height, 0, 0, out.width, out.height);
  return out.toDataURL('image/png');
}
// the signature box: the saved one, or draw / photo a new one
function signatureBox(onChange) {
  const box = h('div');
  const draw = () => {
    box.innerHTML = '';
    const s = savedSign();
    if (s) {
      box.append(h('div', {}, h('img', { src: s, cls: 'signprev', alt: 'signature' }), ' ',
        h('button', { cls: 'chip', on: { click: () => { keepSign(''); draw(); onChange(''); } } }, 'नया sign')));
      onChange(s);
      return;
    }
    const pad = h('canvas', { id: 'pad' });
    const ctx = pad.getContext('2d');
    let drawing = false, any = false;
    const pos = e => { const r = pad.getBoundingClientRect(); return [(e.clientX - r.left) * pad.width / r.width, (e.clientY - r.top) * pad.height / r.height]; };
    setTimeout(() => { pad.width = pad.clientWidth * 2; pad.height = pad.clientHeight * 2; ctx.lineWidth = 5; ctx.lineCap = 'round'; ctx.lineJoin = 'round'; ctx.strokeStyle = '#0b2a8a'; }, 0);
    pad.addEventListener('pointerdown', e => { drawing = true; any = true; pad.setPointerCapture(e.pointerId); ctx.beginPath(); ctx.moveTo(...pos(e)); });
    pad.addEventListener('pointermove', e => { if (drawing) { ctx.lineTo(...pos(e)); ctx.stroke(); } });
    pad.addEventListener('pointerup', () => { drawing = false; });
    box.append(
      h('p', { cls: 'note' }, 'नीचे उँगली / mouse से sign करें, या सफ़ेद काग़ज़ पर किए sign की photo चुनें। sign एक बार रखने पर हर बार अपने-आप आएगा।'),
      pad,
      h('div', { cls: 'chips' },
        h('button', { cls: 'chip', on: { click: () => { ctx.clearRect(0, 0, pad.width, pad.height); any = false; } } }, '🧽 साफ़ करो'),
        h('button', { cls: 'chip on', on: { click: () => {
          if (!any) { msg('पहले sign करें', true); return; }
          const img = new Image();
          img.onload = () => { const tmp = scaled(img, 1200); const ctx2 = tmp.getContext('2d'); ctx2.fillStyle = '#fff'; keepSign(cleanSignature(tmp)); draw(); };
          img.src = pad.toDataURL('image/png');
        } } }, '✔ यह sign रखो'),
        h('button', { cls: 'chip', on: { click: async () => {
          const [f] = await pickFiles('image/*', false);
          if (!f) return;
          try { keepSign(cleanSignature(await loadImage(f))); draw(); } catch (e) { msg(e.message, true); }
        } } }, '📷 sign की photo')));
    onChange('');
  };
  draw();
  return box;
}

// a page / photo preview: tap to place the signature or text; the size slider
function placer(getPreview, getStamp, state) {
  const wrap = h('div');
  const area = h('div', { cls: 'place' });
  const cv = h('canvas');
  area.append(cv);
  const size = h('input', { type: 'range', min: 8, max: 60, value: Math.round(state.w * 100) });
  async function paint() {
    const prev = await getPreview();
    if (!prev) return;
    cv.width = prev.width; cv.height = prev.height;
    const ctx = cv.getContext('2d');
    ctx.drawImage(prev, 0, 0);
    const st = await getStamp();
    if (st) {
      const w = state.w * cv.width, hh = w * st.height / st.width;
      ctx.drawImage(st, state.x * cv.width - w / 2, state.y * cv.height - hh / 2, w, hh);
      ctx.strokeStyle = 'rgba(37,99,235,.7)'; ctx.setLineDash([6, 4]); ctx.lineWidth = 2;
      ctx.strokeRect(state.x * cv.width - w / 2, state.y * cv.height - hh / 2, w, hh);
    }
  }
  cv.addEventListener('pointerdown', e => {
    const r = cv.getBoundingClientRect();
    state.x = Math.min(0.98, Math.max(0.02, (e.clientX - r.left) / r.width));
    state.y = Math.min(0.98, Math.max(0.02, (e.clientY - r.top) / r.height));
    paint();
  });
  size.addEventListener('input', () => { state.w = size.value / 100; paint(); });
  wrap.append(h('p', { cls: 'note' }, 'जहाँ sign / लिखावट चाहिए वहाँ page पर tap करें। नीचे की पट्टी से छोटा-बड़ा करें।'), area, h('label', { cls: 'opt' }, 'आकार'), size);
  wrap.paint = paint;
  return wrap;
}
// the stamp: signature, text and / or date, one under the other
async function makeStamp(sign, text, date) {
  const parts = [];
  if (sign) { const img = new Image(); img.src = sign; await img.decode(); parts.push(img); }
  if (text) parts.push(textImage(text, 64));
  if (date) { const d = new Date(), p = x => String(x).padStart(2, '0'); parts.push(textImage('दिनांक: ' + p(d.getDate()) + '/' + p(d.getMonth() + 1) + '/' + d.getFullYear(), 52)); }
  if (!parts.length) return null;
  const w = Math.max(...parts.map(p => p.width)), hh = parts.reduce((a, p) => a + p.height, 0);
  const c = document.createElement('canvas');
  c.width = w; c.height = hh;
  let y = 0;
  parts.forEach(p => { c.getContext('2d').drawImage(p, (w - p.width) / 2, y); y += p.height; });
  return c;
}

// ------------------------------------------------------------------ screens
function home() {
  msgEl = null;
  app.innerHTML = '';
  const tile = (id, icon, name, sub) => h('button', { cls: 'tile', on: { click: () => open(id) } }, h('span', {}, icon), name, h('small', {}, sub));
  app.append(
    h('h1', {}, '🧰 PDF / Photo tools'),
    h('p', { cls: 'sub' }, '🔒 सब कुछ इसी device पर होता है: कोई file internet पर नहीं जाती। बनी हुई file Downloads में save होती है।'),
    h('h2', {}, 'PDF'),
    h('div', { cls: 'grid' },
      tile('pdfsmall', '📉', 'PDF छोटी करो', '50 KB, 100 KB... जितनी चाहिए'),
      tile('merge', '➕', 'PDF जोड़ो', 'कई PDF / photo एक PDF में'),
      tile('pages', '📄', 'पेज चुनो / हटाओ', 'निकालो, क्रम बदलो, घुमाओ'),
      tile('numbers', '🔢', 'पेज नंबर', 'हर पेज पर नंबर'),
      tile('pdfsign', '✍️', 'PDF पर sign / लिखो', 'sign, नाम, तारीख'),
      tile('unlock', '🔓', 'Password हटाओ', 'जिसका password आपको पता है'),
      tile('topdf', '🖼️', 'Photo से PDF', 'कई photo, एक PDF')),
    h('h2', {}, 'Photo'),
    h('div', { cls: 'grid' },
      tile('photosmall', '📉', 'Photo छोटी करो', '20 KB, 50 KB... form के लिए'),
      tile('photosign', '✍️', 'Photo पर sign', 'sign, नाम, तारीख'),
      tile('collage', '🧩', 'Collage', '2-4 photo एक में'),
      tile('whatsapp', '📱', 'WhatsApp size', 'DP और Status')));
  window.scrollTo(0, 0);
}
function open(id) {
  const t = T[id];
  app.innerHTML = '';
  msgEl = h('div', { id: 'msg' });
  app.append(h('button', { cls: 'back', on: { click: home } }, '‹ सब tools'), h('h1', {}, t.title), h('p', { cls: 'sub' }, t.sub));
  const body = h('div');
  app.append(body, msgEl);
  t.show(body);
  window.scrollTo(0, 0);
}
// a "choose files" button with a list (↑ ↓ ✕), used by every tool
function fileList(accept, multiple, onChange, label) {
  const files = [];
  const box = h('div');
  const draw = () => {
    box.innerHTML = '';
    box.append(h('button', { cls: 'big', on: { click: async () => {
      const got = await pickFiles(accept, multiple);
      if (!multiple) files.length = 0;
      files.push(...got);
      draw(); onChange(files);
    } } }, files.length ? (multiple ? '📂 और files जोड़ें' : '📂 दूसरी file चुनें') : (label || '📂 File चुनें')));
    if (files.length) {
      const ul = h('ul', { cls: 'files' });
      files.forEach((f, i) => ul.append(h('li', {}, h('b', {}, f.name + ' · ' + kb(f.size)),
        multiple ? h('button', { title: 'ऊपर', on: { click: () => { if (i) { [files[i - 1], files[i]] = [files[i], files[i - 1]]; draw(); onChange(files); } } } }, '↑') : '',
        h('button', { title: 'हटाओ', on: { click: () => { files.splice(i, 1); draw(); onChange(files); } } }, '✕'))));
      box.append(ul);
    }
  };
  draw();
  box.files = files;
  return box;
}
function runButton(label, work) {
  const b = h('button', { cls: 'big go', disabled: true }, label);
  b.onclick = async () => {
    b.disabled = true;
    const old = b.textContent;
    b.textContent = '⏳ बन रही है...';
    msg('');
    await nextFrame();
    try { await work(); } catch (e) { msg('⚠ ' + (e.message || e), true); }
    b.textContent = old;
    b.disabled = false;
  };
  return b;
}
const SIZES = [[20, '20 KB'], [50, '50 KB'], [100, '100 KB'], [200, '200 KB'], [500, '500 KB'], [1024, '1 MB']];
function sizePicker(state, def) {
  state.kb = def;
  const custom = h('input', { type: 'number', min: 5, placeholder: 'या KB लिखें', inputmode: 'numeric' });
  custom.addEventListener('input', () => { if (+custom.value >= 5) state.kb = +custom.value; });
  return h('div', {}, h('label', { cls: 'opt' }, 'कितनी बड़ी (ज़्यादा से ज़्यादा)'), chips(SIZES, def, v => { state.kb = v; custom.value = ''; }), custom);
}

// ---- PDF smaller: every page becomes a picture, as sharp as the size allows
T.pdfsmall = {
  title: '📉 PDF छोटी करो', sub: 'Portal पर upload के लिए PDF को चुने हुए KB में लाता है।',
  show(body) {
    const st = {};
    const run = runButton('📉 छोटी करो और save करो', async () => {
      const f = list.files[0];
      const limit = st.kb * 1024;
      if (f.size <= limit) { msg('यह PDF पहले से ' + kb(f.size) + ' की है, छोटी करने की ज़रूरत नहीं।'); return; }
      const orig = new Uint8Array(await f.arrayBuffer());
      const done = async (bytes, how) => {
        await save(baseName(f.name) + ' (' + kb(bytes.length).replace(' ', '') + ').pdf', 'application/pdf', bytes);
        msg('✔ बन गई: ' + kb(f.size) + ' → ' + kb(bytes.length) + '। Downloads में save है।\n' + how);
      };
      // 1. the same PDF written more tightly: nothing changes
      let lib = null;
      try { lib = await PDFDocument.load(orig, { updateMetadata: false }); } catch (e) { /* locked or odd: pictures below */ }
      if (lib) {
        const tight = await lib.save({ useObjectStreams: true });
        if (tight.length <= limit) return done(tight, 'अक्षर और photo पहले जैसे साफ़ हैं।');
        // 2. only the photos inside (scans) made lighter: the writing stays sharp
        msg('PDF के अंदर की photos छोटी कर रहा हूँ...');
        const lighter = await shrinkImages(orig, limit);
        if (lighter) return done(lighter, 'अक्षर पहले जैसे हैं, अंदर की photos हल्की की गई हैं।');
      }
      // 3. every page becomes a picture, only as far as it stays readable
      const doc = await openPdf(f);
      const n = doc.numPages;
      let smallest = 0;
      for (const scale of [2, 1.6, 1.3, 1.15]) { // smaller than this, small letters stop being readable
        msg('पेज बना रहा हूँ... (' + n + ' पेज)');
        const pages = [];
        for (let i = 1; i <= n; i++) pages.push(await renderPage(doc, i, scale));
        const per = Math.max(1, (limit - 2000 - n * 400) / n);
        let lo = 0.25, hi = 0.9, fit = null, low = 0;
        for (let k = 0; k < 7; k++) {
          const qy = (lo + hi) / 2;
          const jp = [];
          let tot = 0;
          for (const p of pages) { const b = await canvasBytes(p.canvas, 'image/jpeg', qy); jp.push(b); tot += b.length; }
          if (tot <= per * n) { fit = { jp, qy }; lo = qy; } else { hi = qy; low = tot; }
        }
        smallest = low;
        if (fit) {
          const out = await PDFDocument.create();
          for (let i = 0; i < n; i++) {
            const img = await out.embedJpg(fit.jp[i]);
            out.addPage([pages[i].w, pages[i].h]).drawImage(img, { x: 0, y: 0, width: pages[i].w, height: pages[i].h });
          }
          const bytes = await out.save({ useObjectStreams: true });
          if (bytes.length <= limit) return done(bytes, 'ध्यान दें: अब PDF के अक्षर photo जैसे हैं (copy नहीं होंगे); खोलकर देख लें कि साफ़ पढ़े जा रहे हैं।');
        }
      }
      const need = Math.ceil((smallest + 2000 + n * 400) / 1024 / 50) * 50;
      throw new Error(st.kb + ' KB में यह PDF (' + n + ' पेज) पढ़ने लायक नहीं बन सकती: अक्षर धुंधले हो जाते। लगभग ' + need + ' KB तक साफ़ बनेगी। या "📄 पेज चुनो / हटाओ" से ज़रूरी पेज ही रखें।');
    });
    const list = fileList('application/pdf,.pdf', false, fs => { run.disabled = !fs.length; });
    body.append(list, sizePicker(st, 200), run);
  },
};

// ---- join PDFs (and photos) in the order shown
T.merge = {
  title: '➕ PDF जोड़ो', sub: 'कई PDF और photo एक PDF में, ऊपर से नीचे के क्रम में। ↑ से क्रम बदलें।',
  show(body) {
    const run = runButton('➕ जोड़ो और save करो', async () => {
      const out = await PDFDocument.create();
      for (const f of list.files) {
        if (/^image\//.test(f.type) || /\.(jpe?g|png|webp)$/i.test(f.name)) await addPhotoPage(out, f, 'fit');
        else {
          const src = await loadLib(f);
          (await out.copyPages(src, src.getPageIndices())).forEach(p => out.addPage(p));
        }
      }
      const bytes = await out.save({ useObjectStreams: true });
      await save(baseName(list.files[0].name) + ' (जोड़ी गई).pdf', 'application/pdf', bytes);
      msg('✔ ' + list.files.length + ' files जुड़ गईं: ' + out.getPageCount() + ' पेज, ' + kb(bytes.length) + '। Downloads में save है।');
    });
    const list = fileList('application/pdf,.pdf,image/*', true, fs => { run.disabled = fs.length < 2; }, '📂 PDF / photo चुनें (कई एक साथ)');
    body.append(list, run);
  },
};

// make the photos inside a PDF (JPEG scans) lighter until the file fits; the text is not touched
async function shrinkImages(orig, limit) {
  const { PDFName, PDFNumber, PDFRawStream } = window.PDFLib;
  for (const [maxSide, q0] of [[2000, 0.75], [1600, 0.6], [1400, 0.5], [1200, 0.42], [1100, 0.35], [1000, 0.3]]) { // below ~1000 px a scanned page is hard to read
    const doc = await PDFDocument.load(orig, { updateMetadata: false });
    let changed = 0;
    for (const [ref, obj] of doc.context.enumerateIndirectObjects()) {
      if (!(obj instanceof PDFRawStream)) continue;
      const d = obj.dict;
      if (d.get(PDFName.of('Subtype')) !== PDFName.of('Image')) continue;
      const filter = d.get(PDFName.of('Filter'));
      if (filter !== PDFName.of('DCTDecode')) continue; // only plain JPEG pictures
      if (obj.contents.length < 20000) continue;
      try {
        const img = new Image();
        const url = URL.createObjectURL(new Blob([obj.contents], { type: 'image/jpeg' }));
        img.src = url;
        await img.decode();
        URL.revokeObjectURL(url);
        const c = scaled(img, maxSide);
        const jpg = await canvasBytes(c, 'image/jpeg', q0);
        if (jpg.length >= obj.contents.length) continue;
        const nd = d.clone(doc.context);
        nd.set(PDFName.of('Width'), PDFNumber.of(c.width));
        nd.set(PDFName.of('Height'), PDFNumber.of(c.height));
        nd.set(PDFName.of('ColorSpace'), PDFName.of('DeviceRGB'));
        nd.set(PDFName.of('BitsPerComponent'), PDFNumber.of(8));
        nd.delete(PDFName.of('Decode'));
        nd.delete(PDFName.of('DecodeParms'));
        nd.set(PDFName.of('Length'), PDFNumber.of(jpg.length));
        doc.context.assign(ref, PDFRawStream.of(nd, jpg));
        changed++;
      } catch (e) { /* a picture the browser cannot read stays as it is */ }
    }
    if (!changed) return null;
    const bytes = await doc.save({ useObjectStreams: true });
    if (bytes.length <= limit) return bytes;
  }
  return null;
}

async function addPhotoPage(out, f, mode) {
  const img = await loadImage(f);
  const c = scaled(img, 2200);
  const jpg = await canvasBytes(c, 'image/jpeg', 0.85);
  const im = await out.embedJpg(jpg);
  if (mode === 'fit') { // the page is the photo's own size (at 150 dpi)
    const w = c.width * 72 / 150, hh = c.height * 72 / 150;
    out.addPage([w, hh]).drawImage(im, { x: 0, y: 0, width: w, height: hh });
    return;
  }
  const A4 = c.width > c.height ? [842, 595] : [595, 842], m = 20;
  const s = Math.min((A4[0] - 2 * m) / c.width, (A4[1] - 2 * m) / c.height);
  const w = c.width * s, hh = c.height * s;
  out.addPage(A4).drawImage(im, { x: (A4[0] - w) / 2, y: (A4[1] - hh) / 2, width: w, height: hh });
}

// ---- pages: keep / remove, move, turn
T.pages = {
  title: '📄 पेज चुनो / हटाओ', sub: 'जिस पेज को हटाना है उस पर ✕ दबाएँ; ◀ ▶ से क्रम बदलें, ⟳ से घुमाएँ।',
  show(body) {
    let file = null, order = [];
    const grid = h('div', { cls: 'thumbs' });
    const run = runButton('📄 नई PDF save करो', async () => {
      const keep = order.filter(o => o.on);
      if (!keep.length) throw new Error('कम से कम एक पेज रखें');
      const src = await loadLib(file), out = await PDFDocument.create();
      const pages = await out.copyPages(src, keep.map(o => o.i));
      pages.forEach((p, k) => { if (keep[k].rot) p.setRotation(degrees((p.getRotation().angle + keep[k].rot) % 360)); out.addPage(p); });
      const bytes = await out.save({ useObjectStreams: true });
      await save(baseName(file.name) + ' (' + keep.length + ' पेज).pdf', 'application/pdf', bytes);
      msg('✔ ' + keep.length + ' पेज की नई PDF Downloads में save है।');
    });
    const draw = () => {
      grid.innerHTML = '';
      order.forEach((o, k) => {
        const th = h('div', { cls: 'th' + (o.on ? '' : ' off') });
        o.canvas.style.transform = 'rotate(' + o.rot + 'deg)';
        th.append(o.canvas, h('div', { cls: 'n' }, 'पेज ' + (o.i + 1) + (o.on ? '' : ' (हटेगा)')),
          h('div', { cls: 'row' },
            h('button', { title: 'पहले', on: { click: () => { if (k) { [order[k - 1], order[k]] = [order[k], order[k - 1]]; draw(); } } } }, '◀'),
            h('button', { title: 'घुमाओ', on: { click: () => { o.rot = (o.rot + 90) % 360; draw(); } } }, '⟳'),
            h('button', { title: o.on ? 'हटाओ' : 'रखो', on: { click: () => { o.on = !o.on; draw(); } } }, o.on ? '✕' : '↺'),
            h('button', { title: 'बाद में', on: { click: () => { if (k < order.length - 1) { [order[k + 1], order[k]] = [order[k], order[k + 1]]; draw(); } } } }, '▶')));
        grid.append(th);
      });
      run.disabled = !order.length;
    };
    const list = fileList('application/pdf,.pdf', false, async fs => {
      file = fs[0]; order = []; draw();
      if (!file) return;
      try {
        const doc = await openPdf(file);
        msg('पेज दिखा रहा हूँ...');
        for (let i = 1; i <= doc.numPages; i++) { order.push({ i: i - 1, on: true, rot: 0, canvas: (await renderPage(doc, i, 0.35)).canvas }); if (i % 4 === 0 || i === doc.numPages) draw(); }
        msg('');
      } catch (e) { msg('⚠ ' + e.message, true); }
    });
    body.append(list, grid, run);
  },
};

// ---- page numbers
T.numbers = {
  title: '🔢 पेज नंबर', sub: 'हर पेज पर नंबर लगाता है।',
  show(body) {
    const st = { pos: 'bc', fmt: 'n' };
    const run = runButton('🔢 नंबर लगाओ और save करो', async () => {
      const f = list.files[0], doc = await loadLib(f), n = doc.getPageCount();
      for (let i = 0; i < n; i++) {
        const label = st.fmt === 'n' ? String(i + 1) : st.fmt === 'nof' ? (i + 1) + ' / ' + n : 'पृष्ठ ' + (i + 1);
        const png = await doc.embedPng(await canvasBytes(textImage(label, 48), 'image/png'));
        const pg = doc.getPage(i), { width, height } = pg.getSize();
        const hh = 11, w = hh * png.width / png.height, m = 18;
        const x = st.pos === 'br' || st.pos === 'tr' ? width - w - m : (width - w) / 2;
        const y = st.pos === 'tr' ? height - hh - m : m;
        pg.drawImage(png, { x, y, width: w, height: hh });
      }
      const bytes = await doc.save({ useObjectStreams: true });
      await save(baseName(f.name) + ' (पेज नंबर).pdf', 'application/pdf', bytes);
      msg('✔ ' + n + ' पेज पर नंबर लग गए। Downloads में save है।');
    });
    const list = fileList('application/pdf,.pdf', false, fs => { run.disabled = !fs.length; });
    body.append(list,
      h('label', { cls: 'opt' }, 'कहाँ'), chips([['bc', 'नीचे बीच में'], ['br', 'नीचे दाएँ'], ['tr', 'ऊपर दाएँ']], st.pos, v => { st.pos = v; }),
      h('label', { cls: 'opt' }, 'कैसे'), chips([['n', '1, 2, 3'], ['nof', '1 / 10'], ['hi', 'पृष्ठ 1']], st.fmt, v => { st.fmt = v; }),
      run);
  },
};

// ---- sign / write on a PDF or a photo
function signTool(kind) {
  return {
    title: kind === 'pdf' ? '✍️ PDF पर sign / लिखो' : '✍️ Photo पर sign / लिखो',
    sub: 'Sign, कोई लिखावट (जैसे नाम या पद) और आज की तारीख, जहाँ tap करें वहाँ लगती है।',
    show(body) {
      const st = { x: 0.78, y: 0.88, w: 0.25, sign: '', text: '', date: false, page: 1, all: false };
      let file = null, doc = null, photo = null, prevCache = {};
      const getPreview = async () => {
        if (kind === 'pdf') {
          if (!doc) return null;
          if (!prevCache[st.page]) prevCache[st.page] = (await renderPage(doc, st.page, 1)).canvas;
          return prevCache[st.page];
        }
        return photo ? scaled(photo, 900) : null;
      };
      const getStamp = () => makeStamp(st.sign, st.text, st.date);
      const pl = placer(getPreview, getStamp, st);
      const pageBox = h('div');
      const run = runButton('✍️ लगाओ और save करो', async () => {
        const stamp = await getStamp();
        if (!stamp) throw new Error('sign करें, या कुछ लिखें, या तारीख चुनें');
        const png = await canvasBytes(stamp, 'image/png');
        if (kind === 'pdf') {
          const out = await loadLib(file), img = await out.embedPng(png);
          const which = st.all ? out.getPageIndices() : [st.page - 1];
          for (const i of which) {
            const pg = out.getPage(i), { width, height } = pg.getSize();
            const w = st.w * width, hh = w * stamp.height / stamp.width;
            pg.drawImage(img, { x: st.x * width - w / 2, y: height - st.y * height - hh / 2, width: w, height: hh });
          }
          const bytes = await out.save({ useObjectStreams: true });
          await save(baseName(file.name) + ' (signed).pdf', 'application/pdf', bytes);
          msg('✔ ' + (st.all ? 'सब पेज' : 'पेज ' + st.page) + ' पर लग गया। Downloads में save है।');
        } else {
          const c = scaled(photo, 3000), ctx = c.getContext('2d');
          const w = st.w * c.width, hh = w * stamp.height / stamp.width;
          ctx.drawImage(stamp, st.x * c.width - w / 2, st.y * c.height - hh / 2, w, hh);
          const bytes = await canvasBytes(c, 'image/jpeg', 0.9);
          await save(baseName(file.name) + ' (signed).jpg', 'image/jpeg', bytes);
          msg('✔ Photo पर लग गया (' + kb(bytes.length) + ')। Downloads में save है। छोटी करनी हो तो "📉 Photo छोटी करो"।');
        }
      });
      const drawPages = () => {
        pageBox.innerHTML = '';
        if (kind !== 'pdf' || !doc) return;
        pageBox.append(h('div', { cls: 'chips' },
          h('button', { cls: 'chip', on: { click: () => { if (st.page > 1) { st.page--; drawPages(); pl.paint(); } } } }, '◀ पिछला पेज'),
          h('span', { style: 'align-self:center' }, ' पेज ' + st.page + ' / ' + doc.numPages + ' '),
          h('button', { cls: 'chip', on: { click: () => { if (st.page < doc.numPages) { st.page++; drawPages(); pl.paint(); } } } }, 'अगला पेज ▶'),
          h('button', { cls: 'chip' + (st.all ? ' on' : ''), on: { click: () => { st.all = !st.all; drawPages(); } } }, st.all ? '✔ सब पेजों पर' : 'सब पेजों पर')));
      };
      const list = fileList(kind === 'pdf' ? 'application/pdf,.pdf' : 'image/*', false, async fs => {
        file = fs[0]; doc = null; photo = null; prevCache = {}; st.page = 1;
        run.disabled = !file;
        if (!file) return;
        try {
          if (kind === 'pdf') doc = await openPdf(file); else photo = await loadImage(file);
          drawPages(); pl.paint();
        } catch (e) { msg('⚠ ' + e.message, true); run.disabled = true; }
      });
      const text = h('input', { type: 'text', placeholder: 'जैसे: प्रधानाध्यापक, प्राथमिक विद्यालय ...' });
      text.addEventListener('input', () => { st.text = text.value.trim(); pl.paint(); });
      body.append(list,
        h('label', { cls: 'opt' }, 'Sign'), signatureBox(s => { st.sign = s; pl.paint(); }),
        h('label', { cls: 'opt' }, 'साथ में लिखावट (ज़रूरत हो तो)'), text,
        chips([[false, 'तारीख नहीं'], [true, 'आज की तारीख भी']], false, v => { st.date = v; pl.paint(); }),
        pageBox, pl, run);
    },
  };
}
T.pdfsign = signTool('pdf');
T.photosign = signTool('photo');

// ---- open a PDF with its password and save it without one
T.unlock = {
  title: '🔓 Password हटाओ', sub: 'जिस PDF का password आपको पता है, उसकी बिना password वाली copy बनाता है।',
  show(body) {
    const pw = h('input', { type: 'password', placeholder: 'PDF का password', autocomplete: 'off' });
    const run = runButton('🔓 खोलो और save करो', async () => {
      const f = list.files[0];
      const doc = await openPdf(f, pw.value);
      const out = await PDFDocument.create();
      for (let i = 1; i <= doc.numPages; i++) {
        msg('पेज ' + i + ' / ' + doc.numPages + '...');
        const p = await renderPage(doc, i, 2);
        const img = await out.embedJpg(await canvasBytes(p.canvas, 'image/jpeg', 0.85));
        out.addPage([p.w, p.h]).drawImage(img, { x: 0, y: 0, width: p.w, height: p.h });
      }
      const bytes = await out.save({ useObjectStreams: true });
      await save(baseName(f.name) + ' (बिना password).pdf', 'application/pdf', bytes);
      msg('✔ बिना password वाली PDF Downloads में save है (' + doc.numPages + ' पेज, ' + kb(bytes.length) + ')।');
    });
    const list = fileList('application/pdf,.pdf', false, fs => { run.disabled = !fs.length; });
    body.append(list, h('label', { cls: 'opt' }, 'Password'), pw, h('p', { cls: 'note' }, 'Aadhaar PDF का password: नाम के पहले 4 अक्षर CAPITAL में + जन्म का साल (जैसे RAME1985)।'), run);
  },
};

// ---- photos -> one PDF
T.topdf = {
  title: '🖼️ Photo से PDF', sub: 'कई photo एक PDF में, हर photo एक पेज (A4)।',
  show(body) {
    const run = runButton('🖼️ PDF बनाओ और save करो', async () => {
      const out = await PDFDocument.create();
      for (const f of list.files) await addPhotoPage(out, f, 'a4');
      const bytes = await out.save({ useObjectStreams: true });
      await save(baseName(list.files[0].name) + ' (' + list.files.length + ' photo).pdf', 'application/pdf', bytes);
      msg('✔ ' + list.files.length + ' पेज की PDF (' + kb(bytes.length) + ') Downloads में save है। छोटी करनी हो तो "📉 PDF छोटी करो"।');
    });
    const list = fileList('image/*', true, fs => { run.disabled = !fs.length; }, '📂 Photos चुनें (कई एक साथ)');
    body.append(list, run);
  },
};

// ---- photo smaller
T.photosmall = {
  title: '📉 Photo छोटी करो', sub: 'Form / portal के लिए photo या sign को चुने हुए KB में लाता है। कई photo एक साथ चुन सकते हैं।',
  show(body) {
    const st = {};
    const run = runButton('📉 छोटी करो और save करो', async () => {
      const lines = [];
      for (const f of list.files) {
        const r = await jpegUnder(scaled(await loadImage(f), 2400), st.kb * 1024);
        await save(baseName(f.name) + ' (' + kb(r.bytes.length).replace(' ', '') + ').jpg', 'image/jpeg', r.bytes);
        lines.push((r.over ? '⚠ ' : '✔ ') + f.name + ': ' + kb(f.size) + ' → ' + kb(r.bytes.length) + ' (' + r.w + '×' + r.h + ')');
      }
      msg(lines.join('\n') + '\nDownloads में save है।');
    });
    const list = fileList('image/*', true, fs => { run.disabled = !fs.length; }, '📂 Photo चुनें');
    body.append(list, sizePicker(st, 50), run);
  },
};

// ---- collage
T.collage = {
  title: '🧩 Collage', sub: '2 से 4 photo एक photo में।',
  show(body) {
    const st = { layout: 'row' };
    const run = runButton('🧩 Collage बनाओ और save करो', async () => {
      const imgs = [];
      for (const f of list.files.slice(0, 4)) imgs.push(scaled(await loadImage(f), 1600));
      const gap = 12, n = imgs.length;
      let c;
      if (st.layout === 'grid' && n >= 3) {
        const cell = 900;
        c = document.createElement('canvas');
        c.width = cell * 2 + gap * 3; c.height = cell * 2 + gap * 3;
        const ctx = c.getContext('2d');
        ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, c.width, c.height);
        imgs.forEach((im, i) => {
          const s = Math.min(cell / im.width, cell / im.height), w = im.width * s, hh = im.height * s;
          const x = gap + (i % 2) * (cell + gap) + (cell - w) / 2, y = gap + Math.floor(i / 2) * (cell + gap) + (cell - hh) / 2;
          ctx.drawImage(im, x, y, w, hh);
        });
      } else if (st.layout === 'col') {
        const W = 1200, parts = imgs.map(im => [W, im.height * W / im.width]);
        c = document.createElement('canvas');
        c.width = W + gap * 2; c.height = parts.reduce((a, p) => a + p[1], 0) + gap * (n + 1);
        const ctx = c.getContext('2d');
        ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, c.width, c.height);
        let y = gap;
        imgs.forEach((im, i) => { ctx.drawImage(im, gap, y, parts[i][0], parts[i][1]); y += parts[i][1] + gap; });
      } else {
        const H = 1000, parts = imgs.map(im => [im.width * H / im.height, H]);
        c = document.createElement('canvas');
        c.width = parts.reduce((a, p) => a + p[0], 0) + gap * (n + 1); c.height = H + gap * 2;
        const ctx = c.getContext('2d');
        ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, c.width, c.height);
        let x = gap;
        imgs.forEach((im, i) => { ctx.drawImage(im, x, gap, parts[i][0], parts[i][1]); x += parts[i][0] + gap; });
      }
      const bytes = await canvasBytes(c, 'image/jpeg', 0.88);
      await save('Collage ' + n + ' photo.jpg', 'image/jpeg', bytes);
      msg('✔ Collage बन गया (' + c.width + '×' + c.height + ', ' + kb(bytes.length) + ')। Downloads में save है।');
    });
    const list = fileList('image/*', true, fs => { run.disabled = fs.length < 2; }, '📂 2-4 Photos चुनें');
    body.append(list, h('label', { cls: 'opt' }, 'कैसे जोड़ें'), chips([['row', 'साथ-साथ'], ['col', 'ऊपर-नीचे'], ['grid', '2 × 2']], 'row', v => { st.layout = v; }), run);
  },
};

// ---- WhatsApp DP / Status
T.whatsapp = {
  title: '📱 WhatsApp size', sub: 'DP (गोल photo) के लिए चौकोर, Status के लिए लंबी photo, बिना कटे।',
  show(body) {
    const st = { kind: 'dp' };
    const run = runButton('📱 बनाओ और save करो', async () => {
      const f = list.files[0], im = scaled(await loadImage(f), 2400);
      const [W, H] = st.kind === 'dp' ? [1080, 1080] : [1080, 1920];
      const c = document.createElement('canvas');
      c.width = W; c.height = H;
      const ctx = c.getContext('2d');
      // the same photo, blurred, fills the empty space; the whole photo sits on top
      const cover = Math.max(W / im.width, H / im.height);
      ctx.filter = 'blur(28px) brightness(0.8)';
      ctx.drawImage(im, (W - im.width * cover) / 2, (H - im.height * cover) / 2, im.width * cover, im.height * cover);
      ctx.filter = 'none';
      const fit = Math.min(W / im.width, H / im.height) * (st.kind === 'dp' ? 0.86 : 1);
      ctx.drawImage(im, (W - im.width * fit) / 2, (H - im.height * fit) / 2, im.width * fit, im.height * fit);
      const bytes = await canvasBytes(c, 'image/jpeg', 0.9);
      await save(baseName(f.name) + (st.kind === 'dp' ? ' (WhatsApp DP).jpg' : ' (WhatsApp Status).jpg'), 'image/jpeg', bytes);
      msg('✔ ' + W + '×' + H + ' photo Downloads में save है। WhatsApp में इसे चुनें, कुछ नहीं कटेगा।');
    });
    const list = fileList('image/*', false, fs => { run.disabled = !fs.length; });
    body.append(list, chips([['dp', 'DP (profile photo)'], ['status', 'Status']], 'dp', v => { st.kind = v; }), run);
  },
};

// a quick check that everything works on this device (used by the device test): makes a
// PDF, draws it with pdf.js, shrinks the picture, saves "Ronin tools self-test.pdf"
window.__roninToolsSelfTest = async () => {
  const d = await PDFDocument.create();
  for (let i = 0; i < 2; i++) d.addPage([595, 842]).drawText('Ronin test page ' + (i + 1), { x: 60, y: 760, size: 28 });
  const bytes = await d.save();
  const doc = await openPdf(new File([bytes], 'test.pdf', { type: 'application/pdf' }));
  const p = await renderPage(doc, 1, 1);
  const j = await jpegUnder(p.canvas, 30 * 1024);
  const out = await PDFDocument.create();
  out.addPage([p.w, p.h]).drawImage(await out.embedJpg(j.bytes), { x: 0, y: 0, width: p.w, height: p.h });
  await save('Ronin tools self-test.pdf', 'application/pdf', await out.save());
  return 'ok ' + doc.numPages;
};

// start: #tool opens a tool straight away (used by the menu and the tests)
const start = location.hash.slice(1);
if (T[start]) open(start); else home();
window.__roninToolsOpen = open;
