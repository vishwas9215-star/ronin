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
// short, plain file names that every portal accepts: letters, digits and _ only, one real extension
// ("मेरी रिपोर्ट (final).v2.PDF" -> "file_small.pdf"; "Leave Docs 14-11.pdf" -> "Leave_Docs_14_11_small.pdf")
function fileName(original, tag, ext) {
  let b = baseName(String(original || '')).normalize('NFKD').replace(/[^A-Za-z0-9]+/g, '_').replace(/^_+|_+$/g, '');
  b = b.slice(0, 24).replace(/_+$/, '') || 'file';
  const t = String(tag || '').replace(/[^A-Za-z0-9]+/g, '_').replace(/^_+|_+$/g, '');
  return (t ? b + '_' + t : b) + '.' + ext;
}
const kbTag = n => n < 1048576 ? Math.round(n / 1024) + 'kb' : (n / 1048576).toFixed(1).replace('.', '_') + 'mb';
let msgEl = null;
function msg(text, bad) {
  if (!msgEl) return;
  msgEl.textContent = text;
  msgEl.className = bad ? 'err' : '';
  msgEl.style.display = text ? 'block' : 'none';
}
const wait = ms => new Promise(r => setTimeout(r, ms));
const nextFrame = () => new Promise(r => requestAnimationFrame(() => r()));

function pickFiles(accept, multiple, camera) {
  const inp = document.getElementById('pick');
  inp.accept = accept;
  inp.multiple = !!multiple;
  if (camera) inp.setAttribute('capture', 'environment'); else inp.removeAttribute('capture'); // phone / tablet camera
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
      const err = new Error(password ? 'Password ग़लत है' : 'इस PDF पर password है: पहले "🔓 Unlock PDF" से खोलें');
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
    if (/encrypt/i.test(String(e && e.message))) throw new Error(file.name + ': इस PDF पर password है, पहले "🔓 Unlock PDF" से खोलें');
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

// where to draw a picture so that it shows upright at (fx, fy) of the page as seen (0..1 from the
// top left), w x h points as seen: turned pages (/Rotate) and the crop box are taken care of
function placeOnPage(pg, fx, fy, w, hh) {
  const box = pg.getCropBox ? pg.getCropBox() : Object.assign({ x: 0, y: 0 }, pg.getSize());
  const rot = ((pg.getRotation().angle % 360) + 360) % 360;
  const W = box.width, H = box.height;
  let cx, cy;
  if (rot === 90) { cx = fy * W; cy = fx * H; } else if (rot === 180) { cx = W - fx * W; cy = fy * H; } else if (rot === 270) { cx = W - fy * W; cy = H - fx * H; } else { cx = fx * W; cy = H - fy * H; }
  const t = rot * Math.PI / 180, cos = Math.cos(t), sin = Math.sin(t);
  // drawImage turns around its corner: move the corner so the centre lands on (cx, cy)
  const x = box.x + cx - (w / 2) * cos + (hh / 2) * sin, y = box.y + cy - (w / 2) * sin - (hh / 2) * cos;
  return { x, y, width: w, height: hh, rotate: degrees(rot) };
}
function seenSize(pg) {
  const box = pg.getCropBox ? pg.getCropBox() : pg.getSize();
  const rot = ((pg.getRotation().angle % 360) + 360) % 360;
  return rot % 180 ? { width: box.height, height: box.width } : { width: box.width, height: box.height };
}

// ------------------------------------------------------------------ screens
function closeTools() { if (KEY) location.href = 'https://ronin.data/close?k=' + KEY; }
function topBar(back) {
  return h('div', { style: 'display:flex;justify-content:space-between;align-items:center;gap:8px' },
    back ? h('button', { cls: 'back', on: { click: home } }, '‹ All tools') : h('span'),
    h('button', { cls: 'chip', on: { click: closeTools } }, '✕ Back to browser'));
}
// every tool once: [category, id, icon, name, what it does]. A new tool = one line here + T[id].
const CATS = [['data', '📊 Data'], ['calc', '🧮 Calculators'], ['pdf', '📄 PDF'], ['photo', '🖼️ Photo']];
const TOOLS = [
  ['data', 'datamatch', '🔀', 'Data Match', 'कोई भी 2 या ज़्यादा Excel / CSV: अंतर और छूटे हुए'],
  ['calc', 'datecalc', '📅', 'Date Calculator', 'उम्र, प्रवेश आयु, सेवा, सेवानिवृत्ति, छुट्टी के दिन, उम्र का अनुमान'],
  ['calc', 'words', '₹', 'Amount in Words', 'रकम शब्दों में: Hindi और English (bill, चेक, form)'],
  ['pdf', 'pdfedit', '🖊️', 'Edit PDF', 'लिखा हुआ बदलो (जैसे score), नया लिखो, छुपाओ, sign'],
  ['pdf', 'fillform', '📝', 'Fill Form', 'sheet / format की photo या PDF भरो, बोलकर लिखो, sign block'],
  ['pdf', 'docpdf', '📎', 'Documents to PDF', 'camera / photo / PDF → 1 PDF, 1-2 MB में (छुट्टी के काग़ज़)'],
  ['pdf', 'pdfsmall', '📉', 'Compress PDF', '50 KB, 100 KB... जितनी चाहिए'],
  ['pdf', 'merge', '➕', 'Merge PDF', 'कई PDF / photo एक PDF में'],
  ['pdf', 'pages', '📄', 'Organize Pages', 'पेज हटाओ, क्रम बदलो, घुमाओ'],
  ['pdf', 'numbers', '🔢', 'Page Numbers', 'हर पेज पर नंबर'],
  ['pdf', 'pdfsign', '✍️', 'Sign PDF', 'sign, नाम, तारीख'],
  ['pdf', 'unlock', '🔓', 'Unlock PDF', 'जिसका password आपको पता है'],
  ['pdf', 'topdf', '🖼️', 'Photos to PDF', 'कई photo, एक PDF'],
  ['pdf', 'convert', '🔁', 'Convert', 'Excel → PDF, PDF → Excel, PDF → JPG, Word → PDF'],
  ['photo', 'photoedit', '✂️', 'Edit Photo', 'crop, घुमाओ, passport size, scan जैसा साफ़, JPG / PNG'],
  ['photo', 'photosmall', '📉', 'Compress Photo', '20 KB, 50 KB... form के लिए'],
  ['photo', 'photosign', '✍️', 'Sign Photo', 'sign, नाम, तारीख'],
  ['photo', 'collage', '🧩', 'Collage', '2-4 photo एक में'],
  ['photo', 'whatsapp', '📱', 'WhatsApp Size', 'DP और Status'],
];
function home() {
  msgEl = null;
  app.innerHTML = '';
  app.append(
    h('div', { cls: 'head' }, h('h1', {}, '🧰 Ronin Tools'), h('button', { cls: 'chip', on: { click: closeTools } }, '✕ Back to browser')),
    h('p', { cls: 'note' }, '🔒 सब कुछ इसी device पर होता है: कोई file internet पर नहीं जाती।'),
    h('div', { cls: 'cats' }, CATS.map(([c, title]) => h('section', { cls: 'cat' }, h('h2', {}, title),
      h('div', { cls: 'list' }, TOOLS.filter(t => t[0] === c).map(([, id, icon, name, sub]) =>
        h('button', { cls: 'tool', title: sub, on: { click: () => open(id) } }, h('span', { cls: 'ic' }, icon), h('span', {}, h('b', {}, name), h('small', {}, sub)))))))));
  window.scrollTo(0, 0);
}
function open(id) {
  const t = T[id];
  app.innerHTML = '';
  msgEl = h('div', { id: 'msg' });
  app.append(topBar(true), h('h1', {}, t.title), h('p', { cls: 'sub' }, t.sub));
  const body = h('div');
  app.append(body, msgEl);
  t.show(body);
  window.scrollTo(0, 0);
}
// a "choose files" button with a list (↑ ↓ ✕), used by every tool
function fileList(accept, multiple, onChange, label, camera) {
  const files = [];
  const box = h('div');
  const add = async cam => {
    const got = await pickFiles(cam ? 'image/*' : accept, multiple, cam);
    if (!multiple) files.length = 0;
    files.push(...got);
    draw(); onChange(files);
  };
  const draw = () => {
    box.innerHTML = '';
    const pick = h('button', { cls: 'big', on: { click: () => add(false) } }, files.length ? (multiple ? '📂 और files जोड़ें' : '📂 दूसरी file चुनें') : (label || '📂 File चुनें'));
    box.append(camera ? h('div', { cls: 'two' }, pick, h('button', { cls: 'big cam', on: { click: () => add(true) } }, '📷 Camera से खींचें')) : pick);
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

// ---- Data Match: Prerna + UDISE+ lists, or teachers' forms + seniority list (merge.js)
T.datamatch = {
  title: '🔀 Data Match',
  sub: 'कोई भी 2 या ज़्यादा Excel / CSV files चुनें: दो portal की lists, पुराना और नया form, इस महीने और पिछले महीने की list... कुछ भी। एक जैसी चीज़ एक पंक्ति में आएगी (नाम, code या number से मिलाकर); जहाँ अंतर है वह रंग और कारण के साथ, और जो किसी file में नहीं है वह भी दिखेगा।',
  show(body) {
    const read = [];
    const box = h('ul', { cls: 'files' });
    let result = null;
    window.__roninMerge = { result: () => JSON.stringify(result) };
    const run = runButton('🔀 Match and make Excel + PDF', async () => {
      result = RoninMerge.merge(read.filter(f => f.data).map(f => f.data));
      window.__lastMerge = result;
      const lines = result.info.map(x => x[0] + ': ' + x[1]).join('\n');
      msg((result.sources.length < 2 ? '⚠ अभी सब files एक ही list के हिस्से लगती हैं, इसलिए सब एक साथ जोड़ दी हैं। तुलना के लिए दूसरी list की file भी जोड़ें।\n\n' : '') + lines + '\n\n📊 Excel + PDF बन रही है... (Downloads में save होगी)');
      if (KEY) location.href = 'https://ronin.data/merge?k=' + KEY;
    });
    const draw = () => {
      box.innerHTML = '';
      read.forEach((f, i) => box.append(h('li', {}, h('b', {}, f.name + ' · ' + (f.error ? '⚠ ' + f.error : f.data ? f.data.tables.reduce((a, t) => a + t.rows.length, 0) + ' पंक्तियाँ' + (f.data.site ? ' · ' + f.data.site : '') : 'पढ़ रहा हूँ...')),
        h('button', { title: 'हटाओ', on: { click: () => { read.splice(i, 1); draw(); } } }, '✕'))));
      run.disabled = !read.some(f => f.data) || read.some(f => !f.data && !f.error);
      pick.textContent = read.length ? '📂 और files जोड़ें' : '📂 Excel / CSV files चुनें';
    };
    const pick = h('button', { cls: 'big', on: { click: async () => {
      const got = await pickFiles('.xlsx,.xlsm,.csv,.xls,.htm,.html,.txt,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,text/csv', true);
      for (const file of got) {
        if (read.some(f => f.name === file.name && f.size === file.size)) continue;
        const f = { name: file.name, size: file.size };
        read.push(f); draw();
        try { f.data = await RoninMerge.readFile(file.name, await file.arrayBuffer()); } catch (e) { f.error = e.message || String(e); }
        draw();
      }
    } } });
    body.append(pick, box, run);
    draw();
  },
};

// ---- Date Calculator (datecalc.js): answers change as soon as a date is chosen
T.datecalc = {
  title: '📅 Date Calculator', sub: 'तारीख़ चुनते ही जवाब आ जाता है।',
  show(body) {
    const D = window.RoninDate, today = D.today();
    const st = { mode: 'age', on: 'today', onDate: null, min: 6, retAge: 62, session: true, estOn: 'today' };
    const out = h('div');
    const dateIn = (label, key) => {
      const i = h('input', { type: 'date', max: '2100-12-31' });
      if (st[key]) i.value = D.iso(st[key]); // keeps what was chosen when the screen changes
      i.addEventListener('input', () => { st[key] = D.parse(i.value); draw(); });
      return h('div', {}, h('label', { cls: 'opt' }, label), i);
    };
    const numIn = (label, key, ph) => {
      const i = h('input', { type: 'number', min: 0, max: 130, inputmode: 'numeric', placeholder: ph || '' });
      if (st[key] !== undefined) i.value = st[key];
      i.addEventListener('input', () => { st[key] = i.value === '' ? undefined : +i.value; draw(); });
      return h('div', {}, h('label', { cls: 'opt' }, label), i);
    };
    const april = y => ({ y, m: 4, d: 1 });
    const sessionYear = today.m >= 8 ? today.y + 1 : today.y; // after July the next admissions are next April
    const onDate = () => st.on === 'today' ? today : st.on === 'april' ? april(sessionYear) : st.onDate;
    const res = (cls, big, text) => h('div', { cls: 'res ' + cls }, h('big', {}, big), text || '');
    const forms = {
      age: () => [dateIn('जन्मतिथि', 'dob'), h('label', { cls: 'opt' }, 'किस तारीख़ को'),
        chips([['today', 'आज'], ['april', '1 अप्रैल ' + sessionYear], ['other', 'दूसरी तारीख़']], st.on, v => { st.on = v; drawForm(); }),
        st.on === 'other' ? dateIn('तारीख़', 'onDate') : ''],
      admission: () => [dateIn('बच्चे की जन्मतिथि (आधार / जन्म प्रमाण पत्र से)', 'dob'),
        h('label', { cls: 'opt' }, 'कितने वर्ष पूरे होने चाहिए'),
        chips([[6, 'कक्षा 1: 6 वर्ष'], [5, '5 वर्ष'], [4, '4 वर्ष'], [3, 'बालवाटिका: 3 वर्ष']], st.min, v => { st.min = v; draw(); }),
        h('label', { cls: 'opt' }, 'किस तारीख़ तक'),
        chips([['april', '1 अप्रैल ' + sessionYear], ['today', 'आज'], ['other', 'दूसरी तारीख़']], st.on === 'today' || st.on === 'other' ? st.on : 'april', v => { st.on = v; drawForm(); }),
        st.on === 'other' ? dateIn('तारीख़', 'onDate') : '',
        h('p', { cls: 'note' }, 'UP में कक्षा 1 के लिए 1 अप्रैल तक 6 वर्ष पूरे होने चाहिए (विभाग का निर्देश बदले तो ऊपर तारीख़ बदल लें)।')],
      service: () => [dateIn('नियुक्ति / joining की तारीख़', 'join'), h('label', { cls: 'opt' }, 'किस तारीख़ तक'),
        chips([['today', 'आज'], ['other', 'दूसरी तारीख़']], st.on === 'other' ? 'other' : 'today', v => { st.on = v; drawForm(); }),
        st.on === 'other' ? dateIn('तारीख़', 'onDate') : ''],
      retire: () => [dateIn('जन्मतिथि (सेवा पुस्तिका के अनुसार)', 'dob'), h('label', { cls: 'opt' }, 'सेवानिवृत्ति की आयु'),
        chips([[62, '62 (UP बेसिक शिक्षक)'], [60, '60'], [58, '58'], [65, '65']], st.retAge, v => { st.retAge = v; draw(); }),
        chips([[true, 'सत्र लाभ: 31 मार्च तक'], [false, 'सत्र लाभ नहीं']], st.session, v => { st.session = v; draw(); })],
      days: () => [chips([['between', 'दो तारीख़ों के बीच कितने दिन'], ['after', 'इतने दिन बाद कौन-सी तारीख़']], st.dmode || 'between', v => { st.dmode = v; drawForm(); }),
        dateIn((st.dmode || 'between') === 'between' ? 'पहली तारीख़' : 'शुरू की तारीख़ (जैसे छुट्टी का पहला दिन)', 'from'),
        (st.dmode || 'between') === 'between' ? dateIn('दूसरी तारीख़', 'to') : numIn('कितने दिन (जैसे 180)', 'ndays')],
      guess: () => [h('p', { cls: 'note' }, 'जिनकी जन्मतिथि का कोई काग़ज़ नहीं और याद भी नहीं: जो बातें पता हों वही भरें, बाक़ी खाली छोड़ दें। जितनी ज़्यादा बातें, उतना पक्का अनुमान।'),
        h('label', { cls: 'opt' }, 'किस तारीख़ की उम्र'),
        chips([['today', 'आज'], ['other', 'दूसरी तारीख़']], st.estOn, v => { st.estOn = v; drawForm(); }),
        st.estOn === 'other' ? dateIn('तारीख़', 'estDate') : '',
        h('div', { cls: 'row2' },
          numIn('सबसे बड़े बच्चे की उम्र अभी (वर्ष)', 'childAge'), numIn('पहला बच्चा होते समय इनकी उम्र (लगभग)', 'ageAtFirstChild', 'महिला ~20, पुरुष ~24'),
          numIn('शादी को कितने वर्ष हुए', 'yearsMarried'), numIn('शादी के समय इनकी उम्र (लगभग)', 'ageAtMarriage', 'महिला ~18, पुरुष ~22')),
        h('label', { cls: 'opt' }, 'कोई पुरानी घटना याद है?'),
        (() => {
          const sel = h('select', {}, h('option', { value: '' }, '— कोई नहीं —'), D.EVENTS.map(([y, n]) => h('option', { value: y }, y + ': ' + n)));
          sel.value = st.eventYear || '';
          sel.addEventListener('change', () => { st.eventYear = +sel.value || undefined; draw(); });
          return sel;
        })(),
        numIn('उस समय इनकी उम्र (लगभग, वर्ष)', 'ageAtEvent')],
    };
    const answer = () => {
      const on = onDate();
      if (st.mode === 'age') {
        if (!st.dob || !on) return '';
        const r = D.diff(st.dob, on);
        return r ? res('ok', D.ymd(r), D.show(on) + ' को उम्र (कुल ' + r.total.toLocaleString('en-IN') + ' दिन)') : res('bad', 'यह तारीख़ जन्म से पहले की है');
      }
      if (st.mode === 'admission') {
        const day = st.on === 'today' ? today : st.on === 'other' ? st.onDate : april(sessionYear);
        if (!st.dob || !day) return '';
        const a = D.admission(st.dob, day, st.min);
        if (!a.age) return res('bad', 'यह तारीख़ जन्म से पहले की है');
        return a.ok ? res('ok', '✔ हाँ, ' + st.min + ' वर्ष पूरे हैं', D.show(day) + ' को उम्र: ' + D.ymd(a.age))
          : res('bad', '✘ नहीं, ' + a.short + ' दिन कम हैं', D.show(day) + ' को उम्र: ' + D.ymd(a.age) + '\n' + st.min + ' वर्ष पूरे होंगे: ' + D.show(a.turns));
      }
      if (st.mode === 'service') {
        const day = st.on === 'other' ? st.onDate : today;
        if (!st.join || !day) return '';
        const r = D.diff(st.join, day);
        return r ? res('ok', D.ymd(r), D.show(st.join) + ' से ' + D.show(day) + ' तक सेवा (कुल ' + r.total.toLocaleString('en-IN') + ' दिन)') : res('bad', 'joining की तारीख़ बाद की है');
      }
      if (st.mode === 'retire') {
        if (!st.dob) return '';
        const r = D.retirement(st.dob, st.retAge, st.session);
        const left = D.cmp(r.end, today) >= 0 ? D.diff(today, r.end) : null;
        return res('ok', 'सेवानिवृत्ति: ' + D.show(r.end),
          st.retAge + ' वर्ष पूरे: ' + D.show(r.reach) + '\nमहीने की आखिरी तारीख़: ' + D.show(r.retire) +
          (r.extended ? '\nसत्र लाभ से: ' + D.show(r.end) + ' तक' : '') +
          (left ? '\nअभी बाकी: ' + D.ymd(left) : '\n(यह तारीख़ बीत चुकी है)') +
          '\nनियम: जिस महीने आयु पूरी हो उसकी आखिरी तारीख़; जन्मतिथि महीने की 1 तारीख़ हो तो पिछले महीने की आखिरी तारीख़।');
      }
      if (st.mode === 'days') {
        if (!st.from) return '';
        if ((st.dmode || 'between') === 'between') {
          if (!st.to) return '';
          const [a, b] = D.cmp(st.from, st.to) <= 0 ? [st.from, st.to] : [st.to, st.from];
          const n = D.days(a, b);
          return res('ok', n + ' दिन का अंतर', 'दोनों दिन गिनकर: ' + (n + 1) + ' दिन\n' + D.ymd(D.diff(a, b)));
        }
        if (!(st.ndays > 0)) return '';
        return res('ok', st.ndays + ' दिन बाद: ' + D.show(D.addDays(st.from, st.ndays)),
          'छुट्टी हो तो आखिरी दिन (पहला दिन गिनकर ' + st.ndays + ' दिन): ' + D.show(D.addDays(st.from, st.ndays - 1)) + '\nकाम पर वापसी: ' + D.show(D.addDays(st.from, st.ndays)));
      }
      const y = st.estOn === 'other' && st.estDate ? st.estDate.y : today.y;
      const e = D.estimate({ childAge: st.childAge, ageAtFirstChild: st.ageAtFirstChild, yearsMarried: st.yearsMarried, ageAtMarriage: st.ageAtMarriage, eventYear: st.eventYear, ageAtEvent: st.ageAtEvent }, y);
      if (!e) return res('', 'ऊपर कोई एक बात भरें', 'जैसे सबसे बड़े बच्चे की उम्र और पहला बच्चा होते समय इनकी उम्र।');
      return res(e.agree ? 'ok' : 'bad', 'लगभग ' + e.age + ' वर्ष' + (e.from !== e.to ? ' (' + e.from + ' से ' + e.to + ')' : ''),
        'जन्म का वर्ष लगभग: ' + e.birthYear + '\n' + e.got.map(g => g.how + ': ' + g.age + ' वर्ष').join('\n') +
        (e.agree ? '' : '\n⚠ अलग-अलग बातों से उम्र में 5 वर्ष से ज़्यादा अंतर है: दोबारा पूछ लें।') + '\nयह सिर्फ़ अनुमान है।');
    };
    const formBox = h('div');
    function draw() { out.innerHTML = ''; const a = answer(); if (a) out.append(a); }
    function drawForm() { formBox.innerHTML = ''; formBox.append(...forms[st.mode]()); draw(); }
    body.append(
      chips([['age', '🎂 उम्र'], ['admission', '🏫 प्रवेश आयु'], ['service', '💼 सेवा अवधि'], ['retire', '🏁 सेवानिवृत्ति'], ['days', '📆 दिन गिनो / छुट्टी'], ['guess', '👴 उम्र का अनुमान']], st.mode,
        v => { st.mode = v; st.on = v === 'admission' ? 'april' : 'today'; st.onDate = null; drawForm(); }),
      formBox, out);
    drawForm();
  },
};

// ---- amount in words
T.words = {
  title: '₹ Amount in Words', sub: 'रकम लिखें (जैसे 125000 या 1,25,000.50): शब्दों में तुरंत, Hindi और English दोनों।',
  show(body) {
    const D = window.RoninDate;
    const inp = h('input', { type: 'text', inputmode: 'decimal', placeholder: 'जैसे 125000' });
    const out = h('div');
    const line = (label, text) => h('div', { cls: 'res ok' }, h('small', {}, label), h('big', { style: 'font-size:19px;user-select:all' }, text),
      h('button', { cls: 'chip', on: { click: async e => { try { await navigator.clipboard.writeText(text); e.target.textContent = '✔ Copied'; } catch (x) { e.target.textContent = 'ऊपर दबाकर चुनें'; } } } }, '📋 Copy'));
    inp.addEventListener('input', () => {
      out.innerHTML = '';
      if (!inp.value.trim()) return;
      const w = D.words(inp.value);
      if (!w) { out.append(h('div', { cls: 'res bad' }, 'सिर्फ़ अंक लिखें (पैसे के लिए दशमलव, जैसे 1500.50)')); return; }
      out.append(line('अंकों में', w.figure), line('हिन्दी', w.hi), line('English', w.en));
    });
    body.append(inp, out);
    setTimeout(() => inp.focus(), 50);
  },
};

// ---- PDF smaller: every page becomes a picture, as sharp as the size allows
T.pdfsmall = {
  title: '📉 Compress PDF', sub: 'Portal पर upload के लिए PDF को चुने हुए KB में लाता है।',
  show(body) {
    const st = {};
    const run = runButton('📉 छोटी करो और save करो', async () => {
      const f = list.files[0];
      const limit = st.kb * 1024;
      if (f.size <= limit) { msg('यह PDF पहले से ' + kb(f.size) + ' की है, छोटी करने की ज़रूरत नहीं।'); return; }
      const orig = new Uint8Array(await f.arrayBuffer());
      const signedNote = /\/ByteRange/.test(new TextDecoder('latin1').decode(orig)) ? '\n⚠ इस PDF पर digital signature था (जैसे e-Aadhaar): नई PDF में वह मान्य नहीं रहेगा।' : '';
      const done = async (bytes, how) => {
        await save(fileName(f.name, kbTag(bytes.length), 'pdf'), 'application/pdf', bytes);
        msg('✔ बन गई: ' + kb(f.size) + ' → ' + kb(bytes.length) + '। Downloads में save है।\n' + how + signedNote);
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
      throw new Error(st.kb + ' KB में यह PDF (' + n + ' पेज) पढ़ने लायक नहीं बन सकती: अक्षर धुंधले हो जाते। लगभग ' + need + ' KB तक साफ़ बनेगी। या "📄 Organize Pages" से ज़रूरी पेज ही रखें।');
    });
    const list = fileList('application/pdf,.pdf', false, fs => { run.disabled = !fs.length; });
    body.append(list, sizePicker(st, 200), run);
  },
};

// ---- join PDFs (and photos) in the order shown
T.merge = {
  title: '➕ Merge PDF', sub: 'कई PDF और photo एक PDF में, ऊपर से नीचे के क्रम में। ↑ से क्रम बदलें।',
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
      await save(fileName(list.files[0].name, 'merged', 'pdf'), 'application/pdf', bytes);
      msg('✔ ' + list.files.length + ' files जुड़ गईं: ' + out.getPageCount() + ' पेज, ' + kb(bytes.length) + '। Downloads में save है।');
    });
    const list = fileList('application/pdf,.pdf,image/*', true, fs => { run.disabled = fs.length < 2; }, '📂 PDF / photo चुनें (कई एक साथ)');
    body.append(list, run);
  },
};

// make the photos inside a PDF (JPEG scans) lighter until the file fits; the text is not touched
async function shrinkImages(orig, limit) {
  const { PDFName, PDFNumber, PDFRawStream } = window.PDFLib;
  const probe = await PDFDocument.load(orig, { updateMetadata: false });
  const masks = new Set(); // pictures used as see-through masks of other pictures stay as they are
  for (const [, obj] of probe.context.enumerateIndirectObjects()) {
    const d = obj && obj.dict;
    if (!d || !d.get) continue;
    for (const k of ['SMask', 'Mask']) { const m = d.get(PDFName.of(k)); if (m && m.toString) masks.add(m.toString()); }
  }
  for (const [maxSide, q0] of [[2000, 0.75], [1600, 0.6], [1400, 0.5], [1200, 0.42], [1100, 0.35], [1000, 0.3]]) { // below ~1000 px a scanned page is hard to read
    const doc = await PDFDocument.load(orig, { updateMetadata: false });
    let changed = 0;
    for (const [ref, obj] of doc.context.enumerateIndirectObjects()) {
      if (!(obj instanceof PDFRawStream)) continue;
      const d = obj.dict;
      if (d.get(PDFName.of('Subtype')) !== PDFName.of('Image')) continue;
      const filter = d.get(PDFName.of('Filter'));
      if (filter !== PDFName.of('DCTDecode')) continue; // only plain JPEG pictures
      if (obj.contents.length < 20000 || masks.has(ref.toString())) continue;
      if (d.get(PDFName.of('Decode')) || d.get(PDFName.of('Mask')) || d.get(PDFName.of('SMask')) || d.get(PDFName.of('DecodeParms')) || d.get(PDFName.of('ImageMask'))) continue;
      const cs = d.get(PDFName.of('ColorSpace'));
      if (cs !== PDFName.of('DeviceRGB') && cs !== PDFName.of('DeviceGray')) continue; // CMYK, indexed, special colours: left alone
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
  title: '📄 Organize Pages', sub: 'जिस पेज को हटाना है उस पर ✕ दबाएँ; ◀ ▶ से क्रम बदलें, ⟳ से घुमाएँ।',
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
      await save(fileName(file.name, keep.length + 'pages', 'pdf'), 'application/pdf', bytes);
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
    };
    let loading = 0;
    const list = fileList('application/pdf,.pdf', false, async fs => {
      const mine = ++loading; // a newer choice stops this one
      file = fs[0]; order = []; draw(); run.disabled = true;
      if (!file) return;
      try {
        const doc = await openPdf(file);
        msg('पेज दिखा रहा हूँ...');
        const pages = [];
        for (let i = 1; i <= doc.numPages; i++) {
          const c = (await renderPage(doc, i, 0.35)).canvas;
          if (mine !== loading) return;
          pages.push({ i: i - 1, on: true, rot: 0, canvas: c });
          if (i % 4 === 0 || i === doc.numPages) { order = pages.slice(); draw(); }
        }
        run.disabled = false;
        msg('');
      } catch (e) { if (mine === loading) msg('⚠ ' + e.message, true); }
    });
    body.append(list, grid, run);
  },
};

// ---- page numbers
T.numbers = {
  title: '🔢 Page Numbers', sub: 'हर पेज पर नंबर लगाता है।',
  show(body) {
    const st = { pos: 'bc', fmt: 'n' };
    const run = runButton('🔢 नंबर लगाओ और save करो', async () => {
      const f = list.files[0], doc = await loadLib(f), n = doc.getPageCount();
      for (let i = 0; i < n; i++) {
        const label = st.fmt === 'n' ? String(i + 1) : st.fmt === 'nof' ? (i + 1) + ' / ' + n : 'पृष्ठ ' + (i + 1);
        const png = await doc.embedPng(await canvasBytes(textImage(label, 48), 'image/png'));
        const pg = doc.getPage(i), { width, height } = seenSize(pg);
        const hh = 11, w = hh * png.width / png.height, m = 18;
        const cx = st.pos === 'br' || st.pos === 'tr' ? width - w / 2 - m : width / 2;
        const cy = st.pos === 'tr' ? m + hh / 2 : height - m - hh / 2;
        pg.drawImage(png, placeOnPage(pg, cx / width, cy / height, w, hh));
      }
      const bytes = await doc.save({ useObjectStreams: true });
      await save(fileName(f.name, 'numbered', 'pdf'), 'application/pdf', bytes);
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
    title: kind === 'pdf' ? '✍️ Sign PDF' : '✍️ Sign Photo',
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
            const pg = out.getPage(i), { width } = seenSize(pg);
            const w = st.w * width, hh = w * stamp.height / stamp.width;
            pg.drawImage(img, placeOnPage(pg, st.x, st.y, w, hh));
          }
          const bytes = await out.save({ useObjectStreams: true });
          await save(fileName(file.name, 'signed', 'pdf'), 'application/pdf', bytes);
          msg('✔ ' + (st.all ? 'सब पेज' : 'पेज ' + st.page) + ' पर लग गया। Downloads में save है।');
        } else {
          const c = scaled(photo, 3000), ctx = c.getContext('2d');
          const w = st.w * c.width, hh = w * stamp.height / stamp.width;
          ctx.drawImage(stamp, st.x * c.width - w / 2, st.y * c.height - hh / 2, w, hh);
          const bytes = await canvasBytes(c, 'image/jpeg', 0.9);
          await save(fileName(file.name, 'signed', 'jpg'), 'image/jpeg', bytes);
          msg('✔ Photo पर लग गया (' + kb(bytes.length) + ')। Downloads में save है। छोटी करनी हो तो "📉 Compress Photo"।');
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
  title: '🔓 Unlock PDF', sub: 'जिस PDF का password आपको पता है, उसकी बिना password वाली copy बनाता है।',
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
      await save(fileName(f.name, 'unlocked', 'pdf'), 'application/pdf', bytes);
      msg('✔ बिना password वाली PDF Downloads में save है (' + doc.numPages + ' पेज, ' + kb(bytes.length) + ')।');
    });
    const list = fileList('application/pdf,.pdf', false, fs => { run.disabled = !fs.length; });
    body.append(list, h('label', { cls: 'opt' }, 'Password'), pw, h('p', { cls: 'note' }, 'Aadhaar PDF का password: नाम के पहले 4 अक्षर CAPITAL में + जन्म का साल (जैसे RAME1985)।'), run);
  },
};

// ---- photos -> one PDF
T.topdf = {
  title: '🖼️ Photos to PDF', sub: 'कई photo एक PDF में, हर photo एक पेज (A4)।',
  show(body) {
    const run = runButton('🖼️ PDF बनाओ और save करो', async () => {
      const out = await PDFDocument.create();
      for (const f of list.files) await addPhotoPage(out, f, 'a4');
      const bytes = await out.save({ useObjectStreams: true });
      await save(fileName(list.files[0].name, 'photos', 'pdf'), 'application/pdf', bytes);
      msg('✔ ' + list.files.length + ' पेज की PDF (' + kb(bytes.length) + ') Downloads में save है। छोटी करनी हो तो "📉 Compress PDF"।');
    });
    const list = fileList('image/*', true, fs => { run.disabled = !fs.length; }, '📂 Photos चुनें (कई एक साथ)');
    body.append(list, run);
  },
};

// ---- photo smaller
T.photosmall = {
  title: '📉 Compress Photo', sub: 'Form / portal के लिए photo या sign को चुने हुए KB में लाता है। कई photo एक साथ चुन सकते हैं।',
  show(body) {
    const st = {};
    const run = runButton('📉 छोटी करो और save करो', async () => {
      const lines = [];
      for (const f of list.files) {
        const r = await jpegUnder(scaled(await loadImage(f), 2400), st.kb * 1024);
        await save(fileName(f.name, kbTag(r.bytes.length), 'jpg'), 'image/jpeg', r.bytes);
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
      await save(fileName('collage', n + 'photos', 'jpg'), 'image/jpeg', bytes);
      msg('✔ Collage बन गया (' + c.width + '×' + c.height + ', ' + kb(bytes.length) + ')। Downloads में save है।');
    });
    const list = fileList('image/*', true, fs => { run.disabled = fs.length < 2; }, '📂 2-4 Photos चुनें');
    body.append(list, h('label', { cls: 'opt' }, 'कैसे जोड़ें'), chips([['row', 'साथ-साथ'], ['col', 'ऊपर-नीचे'], ['grid', '2 × 2']], 'row', v => { st.layout = v; }), run);
  },
};

// ---- WhatsApp DP / Status
T.whatsapp = {
  title: '📱 WhatsApp Size', sub: 'DP (गोल photo) के लिए चौकोर, Status के लिए लंबी photo, बिना कटे।',
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
      await save(fileName(f.name, st.kind === 'dp' ? 'dp' : 'status', 'jpg'), 'image/jpeg', bytes);
      msg('✔ ' + W + '×' + H + ' photo Downloads में save है। WhatsApp में इसे चुनें, कुछ नहीं कटेगा।');
    });
    const list = fileList('image/*', false, fs => { run.disabled = !fs.length; });
    body.append(list, chips([['dp', 'DP (profile photo)'], ['status', 'Status']], 'dp', v => { st.kind = v; }), run);
  },
};

// ================================================================== shared for the new tools
const A4 = [595.28, 841.89];
// a canvas with one of the "scan" looks: colour, grey, or clean black-and-white paper
function scanLook(src, look) {
  if (!look || look === 'color') return src;
  const c = document.createElement('canvas');
  c.width = src.width; c.height = src.height;
  const ctx = c.getContext('2d');
  ctx.drawImage(src, 0, 0);
  const d = ctx.getImageData(0, 0, c.width, c.height), p = d.data;
  if (look === 'gray') {
    for (let i = 0; i < p.length; i += 4) { const g = 0.299 * p[i] + 0.587 * p[i + 1] + 0.114 * p[i + 2]; p[i] = p[i + 1] = p[i + 2] = g; }
  } else { // 'bw': paper white, writing dark (shadows and yellow paper go away)
    let sum = 0;
    for (let i = 0; i < p.length; i += 4) sum += 0.299 * p[i] + 0.587 * p[i + 1] + 0.114 * p[i + 2];
    const mean = sum / (p.length / 4), cut = Math.min(200, mean * 0.82);
    for (let i = 0; i < p.length; i += 4) {
      const g = 0.299 * p[i] + 0.587 * p[i + 1] + 0.114 * p[i + 2];
      const v = g > cut ? 255 : Math.max(0, Math.round((g / cut) * 110));
      p[i] = p[i + 1] = p[i + 2] = v;
    }
  }
  ctx.putImageData(d, 0, 0);
  return c;
}
function rotateCanvas(src, deg) {
  if (!deg) return src;
  const c = document.createElement('canvas');
  const turn = deg % 180 !== 0;
  c.width = turn ? src.height : src.width; c.height = turn ? src.width : src.height;
  const ctx = c.getContext('2d');
  ctx.translate(c.width / 2, c.height / 2);
  ctx.rotate(deg * Math.PI / 180);
  ctx.drawImage(src, -src.width / 2, -src.height / 2);
  return c;
}
// pictures (canvases) -> one PDF that fits in `limit` bytes (0 = no limit); each picture on an
// A4 page (turned to landscape when the picture is wide)
async function picturesToPdf(canvases, limit, onStep) {
  const pageOf = c => (c.width > c.height ? [A4[1], A4[0]] : A4);
  const build = async (list, q) => {
    const out = await PDFDocument.create();
    for (const c of list) {
      const jpg = await canvasBytes(c, 'image/jpeg', q);
      const im = await out.embedJpg(jpg);
      const [W, H] = pageOf(c), m = 14;
      const s = Math.min((W - 2 * m) / c.width, (H - 2 * m) / c.height);
      out.addPage([W, H]).drawImage(im, { x: (W - c.width * s) / 2, y: (H - c.height * s) / 2, width: c.width * s, height: c.height * s });
    }
    return out.save({ useObjectStreams: true });
  };
  if (!limit) return build(canvases.map(c => resizeCanvas(c, Math.min(1, 2000 / Math.max(c.width, c.height)))), 0.82);
  // sharpest first, then smaller pictures, never below a readable size (about 1000 px a page)
  for (const side of [2000, 1700, 1450, 1250, 1100, 1000]) {
    const list = canvases.map(c => resizeCanvas(c, Math.min(1, side / Math.max(c.width, c.height))));
    let lo = 0.3, hi = 0.85, best = null;
    for (let k = 0; k < 6; k++) {
      const q = (lo + hi) / 2;
      if (onStep) onStep(side, q);
      const b = await build(list, q);
      if (b.length <= limit) { best = b; lo = q; } else hi = q;
    }
    if (best) return best;
  }
  return null;
}
// any chosen file (photo or PDF) -> canvases, one per page
async function filesToCanvases(files, rotations, look) {
  const out = [];
  for (let i = 0; i < files.length; i++) {
    const f = files[i];
    if (/pdf$/i.test(f.type) || /\.pdf$/i.test(f.name)) {
      const doc = await openPdf(f);
      for (let n = 1; n <= doc.numPages; n++) out.push(scanLook(rotateCanvas((await renderPage(doc, n, 2)).canvas, rotations[i] || 0), look));
    } else {
      out.push(scanLook(rotateCanvas(scaled(await loadImage(f), 3000), rotations[i] || 0), look));
    }
  }
  return out;
}

// ---- Documents to PDF: leave papers, certificates... camera, photos and PDFs -> one small PDF
T.docpdf = {
  title: '📎 Documents to PDF',
  sub: 'Camera से काग़ज़ों की photo खींचें या photo / PDF चुनें: सब एक PDF में, जितने MB में चाहिए (जैसे छुट्टी के काग़ज़ portal पर upload के लिए)।',
  show(body) {
    const st = { look: 'color', kb: 1024 };
    const rotations = [];
    const run = runButton('📎 PDF बनाओ और save करो', async () => {
      const files = list.files;
      msg('पेज तैयार कर रहा हूँ...');
      const pages = await filesToCanvases(files, rotations, st.look);
      const limit = st.kb ? st.kb * 1024 : 0;
      const bytes = await picturesToPdf(pages, limit, side => msg('छोटा कर रहा हूँ... (' + pages.length + ' पेज)'));
      if (!bytes) throw new Error(st.kb + ' KB में ' + pages.length + ' पेज पढ़ने लायक नहीं बन सकते। बड़ा size चुनें (जैसे 2 MB) या पेज कम करें।');
      await save(fileName(files[0].name, 'docs', 'pdf'), 'application/pdf', bytes);
      msg('✔ ' + pages.length + ' पेज की PDF बन गई: ' + kb(bytes.length) + '। Downloads में save है।');
    });
    const list = fileList('image/*,application/pdf,.pdf', true, fs => { run.disabled = !fs.length; rotations.length = fs.length; drawTurns(); }, '📂 Photo / PDF चुनें', true);
    const turns = h('div', { cls: 'chips' });
    function drawTurns() {
      turns.innerHTML = '';
      list.files.forEach((f, i) => {
        if (/\.pdf$/i.test(f.name)) return;
        turns.append(h('button', { cls: 'chip', title: 'इस photo को घुमाओ', on: { click: () => { rotations[i] = ((rotations[i] || 0) + 90) % 360; drawTurns(); } } }, '⟳ ' + (i + 1) + (rotations[i] ? ' (' + rotations[i] + '°)' : '')));
      });
      if (turns.children.length) turns.prepend(h('span', { cls: 'note', style: 'align-self:center' }, 'उल्टी photo घुमाएँ:'));
    }
    body.append(list, turns,
      h('label', { cls: 'opt' }, 'काग़ज़ कैसा दिखे'),
      chips([['color', 'जैसा है (रंगीन)'], ['bw', 'Scan जैसा साफ़ (B&W)'], ['gray', 'Grey']], st.look, v => { st.look = v; }),
      h('label', { cls: 'opt' }, 'PDF ज़्यादा से ज़्यादा'),
      chips([[500, '500 KB'], [1024, '1 MB'], [2048, '2 MB'], [5120, '5 MB'], [0, 'कोई सीमा नहीं']], st.kb, v => { st.kb = v; }),
      run);
  },
};

// ---- Edit Photo: crop by hand, turn, size (passport...), scan look, JPG / PNG, KB limit
T.photoedit = {
  title: '✂️ Edit Photo',
  sub: 'Photo काटें (crop), घुमाएँ, size बदलें (passport, sign...), scan जैसा साफ़ करें, JPG / PNG और KB सीमा।',
  show(body) {
    let img = null, file = null;
    const st = { rot: 0, look: 'color', ratio: 0, size: 'same', fmt: 'jpg', kb: 0, crop: { x: 0, y: 0, w: 1, h: 1 } };
    const wrap = h('div', { cls: 'crop' }), cv = h('canvas');
    wrap.append(cv);
    let base = null; // the turned photo
    const SIZES2 = { same: null, passport: [413, 531], stamp: [295, 354], sign: [413, 177], dp: [1080, 1080] };
    const run = runButton('✂️ बनाओ और save करो', async () => {
      const c = cropped(true);
      let bytes, ext = st.fmt;
      if (st.fmt === 'png') bytes = await canvasBytes(c, 'image/png');
      else if (st.kb) { const r = await jpegUnder(c, st.kb * 1024); bytes = r.bytes; } else bytes = await canvasBytes(c, 'image/jpeg', 0.9);
      await save(fileName(file.name, 'edited', ext), ext === 'png' ? 'image/png' : 'image/jpeg', bytes);
      msg('✔ ' + c.width + '×' + c.height + ' px, ' + kb(bytes.length) + '। Downloads में save है।');
    });
    function cropped(full) {
      const b = base, cr = st.crop;
      const sx = Math.round(cr.x * b.width), sy = Math.round(cr.y * b.height), sw = Math.max(1, Math.round(cr.w * b.width)), sh = Math.max(1, Math.round(cr.h * b.height));
      const target = SIZES2[st.size];
      const c = document.createElement('canvas');
      if (target && full) { c.width = target[0]; c.height = target[1]; } else { c.width = sw; c.height = sh; }
      const ctx = c.getContext('2d');
      ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, c.width, c.height);
      ctx.imageSmoothingQuality = 'high';
      ctx.drawImage(b, sx, sy, sw, sh, 0, 0, c.width, c.height);
      return scanLook(c, st.look);
    }
    function setBase() {
      base = rotateCanvas(scaled(img, 3000), st.rot);
      fitRatio();
      paint();
    }
    function fitRatio() { // the crop box keeps the chosen shape (passport 3.5 x 4.5...)
      const want = st.size !== 'same' && SIZES2[st.size] ? SIZES2[st.size][0] / SIZES2[st.size][1] : st.ratio;
      if (!want || !base) return;
      const cr = st.crop, pr = want * base.height / base.width; // width fraction / height fraction
      let w = cr.w, hh = w / pr;
      if (hh > 1) { hh = 1; w = pr; }
      if (w > 1) { w = 1; hh = 1 / pr; }
      st.crop = { x: Math.min(cr.x, 1 - w), y: Math.min(cr.y, 1 - hh), w, h: hh };
    }
    function paint() {
      if (!base) return;
      const view = resizeCanvas(base, Math.min(1, 900 / Math.max(base.width, base.height)));
      cv.width = view.width; cv.height = view.height;
      const ctx = cv.getContext('2d');
      ctx.drawImage(view, 0, 0);
      const c = st.crop, x = c.x * cv.width, y = c.y * cv.height, w = c.w * cv.width, hh = c.h * cv.height;
      ctx.fillStyle = 'rgba(0,0,0,.5)';
      ctx.fillRect(0, 0, cv.width, y); ctx.fillRect(0, y + hh, cv.width, cv.height - y - hh);
      ctx.fillRect(0, y, x, hh); ctx.fillRect(x + w, y, cv.width - x - w, hh);
      ctx.strokeStyle = '#22c55e'; ctx.lineWidth = 3; ctx.strokeRect(x, y, w, hh);
      ctx.fillStyle = '#22c55e';
      [[x, y], [x + w, y], [x, y + hh], [x + w, y + hh]].forEach(([a, b]) => ctx.fillRect(a - 9, b - 9, 18, 18));
    }
    // drag inside = move the box, drag a corner = change its size
    let drag = null;
    const at = e => { const r = cv.getBoundingClientRect(); return [(e.clientX - r.left) / r.width, (e.clientY - r.top) / r.height]; };
    cv.addEventListener('pointerdown', e => {
      if (!base) return;
      const [px, py] = at(e), c = st.crop, near = 0.05;
      const corner = [['tl', c.x, c.y], ['tr', c.x + c.w, c.y], ['bl', c.x, c.y + c.h], ['br', c.x + c.w, c.y + c.h]].find(([, a, b]) => Math.abs(px - a) < near && Math.abs(py - b) < near);
      const whole = c.w > 0.99 && c.h > 0.99; // nothing cut yet: a drag draws the box
      drag = { kind: corner && !whole ? corner[0] : (!whole && px > c.x && px < c.x + c.w && py > c.y && py < c.y + c.h ? 'move' : 'new'), px, py, c: Object.assign({}, c) };
      cv.setPointerCapture(e.pointerId);
    });
    cv.addEventListener('pointermove', e => {
      if (!drag) return;
      const [px, py] = at(e), o = drag.c, dx = px - drag.px, dy = py - drag.py, cl = v => Math.min(1, Math.max(0, v));
      let { x, y, w, h: hh } = o;
      if (drag.kind === 'move') { x = Math.min(1 - w, Math.max(0, x + dx)); y = Math.min(1 - hh, Math.max(0, y + dy)); }
      else if (drag.kind === 'new') { x = Math.min(drag.px, cl(px)); y = Math.min(drag.py, cl(py)); w = Math.abs(cl(px) - drag.px); hh = Math.abs(cl(py) - drag.py); }
      else {
        const l = drag.kind[1] === 'l', t = drag.kind[0] === 't';
        const x2 = l ? cl(o.x + dx) : o.x, y2 = t ? cl(o.y + dy) : o.y;
        const r2 = l ? o.x + o.w : cl(o.x + o.w + dx), b2 = t ? o.y + o.h : cl(o.y + o.h + dy);
        x = Math.min(x2, r2); y = Math.min(y2, b2); w = Math.abs(r2 - x2); hh = Math.abs(b2 - y2);
      }
      st.crop = { x, y, w: Math.max(0.03, w), h: Math.max(0.03, hh) };
      if (drag.kind !== 'move') fitRatio();
      paint();
    });
    cv.addEventListener('pointerup', () => { drag = null; });
    const list = fileList('image/*', false, async fs => {
      file = fs[0]; img = null; base = null; run.disabled = true;
      if (!file) return;
      try { img = await loadImage(file); st.crop = { x: 0, y: 0, w: 1, h: 1 }; setBase(); run.disabled = false; } catch (e) { msg('⚠ ' + e.message, true); }
    }, '📂 Photo चुनें', true);
    body.append(list,
      h('div', { cls: 'chips' },
        h('button', { cls: 'chip', on: { click: () => { if (!img) return; st.rot = (st.rot + 270) % 360; st.crop = { x: 0, y: 0, w: 1, h: 1 }; setBase(); } } }, '⟲ बाएँ घुमाओ'),
        h('button', { cls: 'chip', on: { click: () => { if (!img) return; st.rot = (st.rot + 90) % 360; st.crop = { x: 0, y: 0, w: 1, h: 1 }; setBase(); } } }, '⟳ दाएँ घुमाओ'),
        h('button', { cls: 'chip', on: { click: () => { st.crop = { x: 0, y: 0, w: 1, h: 1 }; fitRatio(); paint(); } } }, '↺ पूरी photo')),
      h('p', { cls: 'note' }, 'हरे डिब्बे को खिसकाएँ, कोने खींचकर छोटा-बड़ा करें, या photo पर नया डिब्बा खींचें।'),
      wrap,
      h('label', { cls: 'opt' }, 'आकार (shape)'),
      chips([[0, 'जैसा चाहें'], [1, 'चौकोर 1:1'], [3.5 / 4.5, 'Passport 3.5×4.5'], [4 / 3, '4:3'], [16 / 9, '16:9']], 0, v => { st.ratio = v; fitRatio(); paint(); }),
      h('label', { cls: 'opt' }, 'Size (px)'),
      chips([['same', 'जैसा काटा'], ['passport', 'Passport 413×531'], ['stamp', 'Stamp 295×354'], ['sign', 'Sign 413×177'], ['dp', '1080×1080']], 'same', v => { st.size = v; fitRatio(); paint(); }),
      h('label', { cls: 'opt' }, 'दिखावट'),
      chips([['color', 'रंगीन'], ['bw', 'Scan जैसा साफ़ (B&W)'], ['gray', 'Grey']], 'color', v => { st.look = v; }),
      h('label', { cls: 'opt' }, 'File'),
      chips([['jpg', 'JPG'], ['png', 'PNG']], 'jpg', v => { st.fmt = v; }),
      chips([[0, 'KB सीमा नहीं'], [20, '20 KB'], [50, '50 KB'], [100, '100 KB'], [200, '200 KB']], 0, v => { st.kb = v; }),
      run);
  },
};

// ---- people who sign (name, post, office, signature): kept on this device, as many as needed
const SIGNERS_KEY = 'ronin.signers';
function signers() { try { return JSON.parse(localStorage.getItem(SIGNERS_KEY) || '[]'); } catch (e) { return []; } }
function keepSigners(list) { try { localStorage.setItem(SIGNERS_KEY, JSON.stringify(list)); } catch (e) { /* this device only */ } }
async function signBlockCanvas(p, withDate) {
  const parts = [];
  if (p.sign) { const im = new Image(); im.src = p.sign; await im.decode(); const c = document.createElement('canvas'); c.width = im.width; c.height = im.height; c.getContext('2d').drawImage(im, 0, 0); parts.push(resizeCanvas(c, Math.min(1, 520 / c.width))); }
  if (p.name) parts.push(textImage(p.name, 46));
  if (p.post) parts.push(textImage(p.post, 40));
  if (p.office) parts.push(textImage(p.office, 36));
  if (withDate) { const d = new Date(), q = x => String(x).padStart(2, '0'); parts.push(textImage('दिनांक: ' + q(d.getDate()) + '/' + q(d.getMonth() + 1) + '/' + d.getFullYear(), 36)); }
  if (!parts.length) return null;
  const c = document.createElement('canvas');
  c.width = Math.max(...parts.map(x => x.width)); c.height = parts.reduce((a, x) => a + x.height, 0);
  let y = 0;
  parts.forEach(x => { c.getContext('2d').drawImage(x, (c.width - x.width) / 2, y); y += x.height; });
  return c;
}
// choose or make a signer; onPick(signer or null)
function signerPicker(onPick) {
  const box = h('div', { cls: 'panel' });
  // only suggestions: the post is typed freely (any office), and posts used before come first
  const POSTS = () => [...new Set(signers().map(x => x.post).filter(Boolean).concat(['प्रधानाध्यापक', 'सहायक अध्यापक', 'शिक्षामित्र', 'अनुदेशक', 'खंड शिक्षा अधिकारी', 'ARP', 'SRG']))].slice(0, 10);
  function draw(sel) {
    box.innerHTML = '';
    const list = signers();
    box.append(h('b', {}, 'Sign करने वाला (जो भी हो: अपना नाम, पद, कार्यालय खुद लिखें)'));
    const row = h('div', { cls: 'chips' });
    list.forEach((p, i) => row.append(h('button', { cls: 'chip' + (sel === i ? ' on' : ''), on: { click: () => { draw(i); onPick(list[i]); } } }, (p.name || 'बिना नाम') + (p.post ? ' · ' + p.post : ''))));
    row.append(h('button', { cls: 'chip', on: { click: () => edit(null) } }, '➕ नया व्यक्ति'));
    if (sel != null && list[sel]) row.append(h('button', { cls: 'chip', on: { click: () => edit(sel) } }, '✏️ बदलें'), h('button', { cls: 'chip', on: { click: () => { list.splice(sel, 1); keepSigners(list); draw(); onPick(null); } } }, '🗑 हटाएँ'));
    box.append(row);
  }
  function edit(i) {
    const list = signers(), p = i == null ? { name: '', post: '', office: '', sign: '' } : Object.assign({}, list[i]);
    box.innerHTML = '';
    const name = h('input', { type: 'text', placeholder: 'नाम (जैसे राम कुमार)', value: p.name });
    const post = h('input', { type: 'text', placeholder: 'पद: कोई भी लिखें (जैसे खंड शिक्षा अधिकारी, प्रधानाध्यापक...)', value: p.post });
    const office = h('input', { type: 'text', placeholder: 'विद्यालय / कार्यालय / विभाग (जो भी हो)', value: p.office });
    let sign = p.sign;
    const signBox = h('div');
    const drawSign = () => {
      signBox.innerHTML = '';
      if (sign) { signBox.append(h('img', { src: sign, cls: 'signprev' }), ' ', h('button', { cls: 'chip', on: { click: () => { sign = ''; drawSign(); } } }, 'नया sign')); return; }
      const pad = h('canvas', { id: 'pad' }), ctx = pad.getContext('2d');
      let on = false, any = false;
      const pos = e => { const r = pad.getBoundingClientRect(); return [(e.clientX - r.left) * pad.width / r.width, (e.clientY - r.top) * pad.height / r.height]; };
      setTimeout(() => { pad.width = pad.clientWidth * 2; pad.height = pad.clientHeight * 2; ctx.lineWidth = 5; ctx.lineCap = 'round'; ctx.lineJoin = 'round'; ctx.strokeStyle = '#0b2a8a'; }, 0);
      pad.addEventListener('pointerdown', e => { on = true; any = true; pad.setPointerCapture(e.pointerId); ctx.beginPath(); ctx.moveTo(...pos(e)); });
      pad.addEventListener('pointermove', e => { if (on) { ctx.lineTo(...pos(e)); ctx.stroke(); } });
      pad.addEventListener('pointerup', () => { on = false; });
      signBox.append(h('p', { cls: 'note' }, 'उँगली / mouse से sign करें, या सफ़ेद काग़ज़ पर किए sign की photo दें।'), pad,
        h('div', { cls: 'chips' },
          h('button', { cls: 'chip', on: { click: () => { ctx.clearRect(0, 0, pad.width, pad.height); any = false; } } }, '🧽 साफ़'),
          h('button', { cls: 'chip on', on: { click: () => { if (!any) return; const im = new Image(); im.onload = () => { sign = cleanSignature(im); drawSign(); }; im.src = pad.toDataURL('image/png'); } } }, '✔ यह sign'),
          h('button', { cls: 'chip', on: { click: async () => { const [f] = await pickFiles('image/*', false); if (!f) return; try { sign = cleanSignature(await loadImage(f)); drawSign(); } catch (e) { msg(e.message, true); } } } }, '📷 sign की photo')));
    };
    drawSign();
    box.append(h('b', {}, i == null ? 'नया व्यक्ति' : 'बदलें'), name,
      post, h('div', { cls: 'chips' }, h('span', { cls: 'note', style: 'align-self:center' }, 'जल्दी भरें:'), POSTS().map(x => h('button', { cls: 'chip', on: { click: () => { post.value = x; } } }, x))), office, signBox,
      h('div', { cls: 'chips' },
        h('button', { cls: 'chip on', on: { click: () => {
          const np = { name: name.value.trim(), post: post.value.trim(), office: office.value.trim(), sign };
          const all = signers();
          if (i == null) all.push(np); else all[i] = np;
          keepSigners(all);
          const at = i == null ? all.length - 1 : i;
          draw(at); onPick(np);
        } } }, '💾 Save'),
        h('button', { cls: 'chip', on: { click: () => draw() } }, 'रद्द')));
  }
  draw();
  return box;
}

// ---- Edit / Fill PDF: tap a word to change it, write anything anywhere, white-out, sign block.
// Works on a PDF or on a photo of a sheet; the result is a PDF.
function editorTool(fill) {
  return {
    title: fill ? '📝 Fill Form' : '🖊️ Edit PDF',
    sub: fill ? 'विभाग की sheet / format की photo या PDF खोलें, जहाँ लिखना है वहाँ tap करें और लिखें या keyboard के 🎤 से बोलें। नीचे sign block (नाम, पद, sign) लगाकर PDF बनाएँ और भेजें।'
      : 'PDF के किसी लिखे शब्द / अंक पर tap करें और बदल दें, कहीं भी नया लिखें, कुछ छुपाएँ (white-out) या sign लगाएँ।',
    show(body) {
      const st = { mode: 'text', page: 0, sel: -1, size: 12, signer: null, date: true };
      let file = null, doc = null, photo = null, pages = []; // pages: {W, H (seen points), bg canvas, items: [], words: []}
      const wrap = h('div', { cls: 'ed-wrap' }), cv = h('canvas');
      wrap.append(cv);
      const panel = h('div');
      const pageBox = h('div', { cls: 'chips' });
      const run = runButton('💾 PDF बनाओ और save करो', async () => {
        const out = doc ? await loadLib(file) : await PDFDocument.create();
        for (let i = 0; i < pages.length; i++) {
          const P = pages[i];
          let pg;
          if (doc) pg = out.getPage(i);
          else {
            pg = out.addPage([P.W, P.H]);
            const im = await out.embedJpg(await canvasBytes(P.bg, 'image/jpeg', 0.88));
            pg.drawImage(im, { x: 0, y: 0, width: P.W, height: P.H });
          }
          for (const it of P.items) {
            if (it.type === 'white') {
              pg.drawRectangle(Object.assign(placeOnPage(pg, it.x + it.w / 2, it.y + it.h / 2, it.w * P.W, it.h * P.H), { color: window.PDFLib.rgb(1, 1, 1), borderWidth: 0 }));
              continue;
            }
            const pic = await itemCanvas(it);
            if (!pic) continue;
            const wPt = it.type === 'text' ? pic.width / pic.height * it.size * 1.4 * lines(it) : it.w * P.W;
            const hPt = wPt * pic.height / pic.width;
            if (it.cover) pg.drawRectangle(Object.assign(placeOnPage(pg, it.cover.x + it.cover.w / 2, it.cover.y + it.cover.h / 2, it.cover.w * P.W, it.cover.h * P.H), { color: window.PDFLib.rgb(1, 1, 1), borderWidth: 0 }));
            const png = await out.embedPng(await canvasBytes(pic, 'image/png'));
            pg.drawImage(png, placeOnPage(pg, it.x + wPt / P.W / 2, it.y + hPt / P.H / 2, wPt, hPt));
          }
        }
        const bytes = await out.save({ useObjectStreams: true });
        await save(fileName(file.name, fill ? 'filled' : 'edited', 'pdf'), 'application/pdf', bytes);
        msg('✔ PDF बन गई (' + kb(bytes.length) + ')। Downloads में save है' + (KEY ? '; Tablet पर "Share" से WhatsApp पर भेजें।' : '।'));
      });
      const lines = it => Math.max(1, String(it.text || '').split('\n').length);
      // what an item looks like (text is drawn by the browser, so Hindi joins correctly)
      async function itemCanvas(it) {
        if (it.type === 'text') {
          const ls = String(it.text || '').split('\n'), px = 64;
          if (!ls.join('').trim()) return null;
          const font = (it.bold ? '700 ' : '400 ') + px + 'px "Nirmala UI","Noto Sans Devanagari","Mangal",Arial,sans-serif';
          const m = document.createElement('canvas').getContext('2d');
          m.font = font;
          const c = document.createElement('canvas');
          c.width = Math.max(8, Math.ceil(Math.max(...ls.map(l => m.measureText(l).width))) + 6);
          c.height = Math.ceil(px * 1.4 * ls.length);
          const ctx = c.getContext('2d');
          ctx.font = font; ctx.fillStyle = it.color || '#111'; ctx.textBaseline = 'top';
          ls.forEach((l, i) => ctx.fillText(l, 3, i * px * 1.4 + px * 0.15));
          return c;
        }
        if (it.type === 'sign') return it.pic;
        return null;
      }
      // the words already on a PDF page, where they are (for "tap a word to change it")
      async function wordsOf(n) {
        const page = await doc.getPage(n + 1), vp = page.getViewport({ scale: 1 });
        const tc = await page.getTextContent();
        return tc.items.filter(t => t.str && t.str.trim()).map(t => {
          const m = pdfjs.Util.transform(vp.transform, t.transform), fh = Math.hypot(m[2], m[3]) || 10;
          const w = (t.width || t.str.length * fh * 0.5) * (Math.hypot(t.transform[0], t.transform[1]) ? 1 : 1);
          return { str: t.str, x: m[4] / vp.width, y: (m[5] - fh) / vp.height, w: Math.min(1, w / vp.width), h: (fh * 1.25) / vp.height, size: fh };
        });
      }
      async function loadPages() {
        pages = [];
        if (doc) {
          for (let i = 1; i <= doc.numPages; i++) {
            const r = await renderPage(doc, i, 1.6), pg = await doc.getPage(i), vp = pg.getViewport({ scale: 1 });
            pages.push({ W: vp.width, H: vp.height, bg: r.canvas, items: [], words: await wordsOf(i - 1) });
          }
        } else {
          const c = scaled(photo, 2200);
          pages.push({ W: A4[0], H: A4[0] * c.height / c.width, bg: c, items: [], words: [] });
        }
        st.page = 0; st.sel = -1;
      }
      async function paint() {
        const P = pages[st.page];
        if (!P) return;
        const scale = Math.min(1, 900 / P.bg.width);
        cv.width = Math.round(P.bg.width * scale); cv.height = Math.round(P.bg.height * scale);
        const ctx = cv.getContext('2d');
        ctx.drawImage(P.bg, 0, 0, cv.width, cv.height);
        for (let i = 0; i < P.items.length; i++) {
          const it = P.items[i];
          const box = await itemBox(it, P);
          if (it.type === 'white' || it.cover) { const c = it.cover || it; ctx.fillStyle = '#fff'; ctx.fillRect(c.x * cv.width, c.y * cv.height, c.w * cv.width, c.h * cv.height); }
          const pic = await itemCanvas(it);
          if (pic) ctx.drawImage(pic, box.x * cv.width, box.y * cv.height, box.w * cv.width, box.h * cv.height);
          if (i === st.sel) { ctx.strokeStyle = '#2563eb'; ctx.setLineDash([6, 4]); ctx.lineWidth = 2; ctx.strokeRect(box.x * cv.width - 3, box.y * cv.height - 3, box.w * cv.width + 6, box.h * cv.height + 6); ctx.setLineDash([]); }
        }
        drawPanel();
      }
      async function itemBox(it, P) {
        if (it.type === 'white') return it;
        const pic = await itemCanvas(it);
        if (!pic) return { x: it.x, y: it.y, w: 0.02, h: it.size * 1.4 / P.H };
        if (it.type === 'text') { const hPt = it.size * 1.4 * lines(it), wPt = hPt * pic.width / pic.height; return { x: it.x, y: it.y, w: wPt / P.W, h: hPt / P.H }; }
        return { x: it.x, y: it.y, w: it.w, h: it.w * P.W * pic.height / pic.width / P.H };
      }
      const at = e => { const r = cv.getBoundingClientRect(); return [(e.clientX - r.left) / r.width, (e.clientY - r.top) / r.height]; };
      let drag = null;
      cv.addEventListener('pointerdown', async e => {
        const P = pages[st.page];
        if (!P) return;
        const [px, py] = at(e);
        cv.setPointerCapture(e.pointerId);
        // an item already placed: select it (and drag it)
        for (let i = P.items.length - 1; i >= 0; i--) {
          const b = await itemBox(P.items[i], P);
          if (px >= b.x - 0.01 && px <= b.x + b.w + 0.01 && py >= b.y - 0.01 && py <= b.y + b.h + 0.01) {
            st.sel = i; drag = { i, px, py, x: P.items[i].x, y: P.items[i].y }; paint(); return;
          }
        }
        if (st.mode === 'white') { P.items.push({ type: 'white', x: px, y: py, w: 0.001, h: 0.001 }); st.sel = P.items.length - 1; drag = { i: st.sel, px, py, newBox: true }; paint(); return; }
        if (st.mode === 'sign') {
          if (!st.signer) { msg('पहले नीचे "Sign करने वाला" चुनें या नया व्यक्ति जोड़ें', true); return; }
          const pic = await signBlockCanvas(st.signer, st.date);
          if (!pic) return;
          P.items.push({ type: 'sign', pic, x: Math.max(0, px - 0.13), y: Math.max(0, py - 0.03), w: 0.26 });
          st.sel = P.items.length - 1; paint(); return;
        }
        // text: on a word of the PDF -> change it; elsewhere -> new writing
        const wd = P.words.find(w => px >= w.x - 0.004 && px <= w.x + w.w + 0.004 && py >= w.y - w.h * 0.2 && py <= w.y + w.h * 1.3);
        if (wd) P.items.push({ type: 'text', text: wd.str, x: wd.x, y: wd.y + wd.h * 0.05, size: Math.max(6, Math.round(wd.size * 0.95)), cover: { x: wd.x - 0.002, y: wd.y, w: wd.w + 0.004, h: wd.h } });
        else P.items.push({ type: 'text', text: '', x: px, y: py - st.size * 0.7 / P.H, size: st.size });
        st.sel = P.items.length - 1;
        await paint();
        const ta = panel.querySelector('textarea');
        if (ta) { ta.focus(); ta.select(); }
      });
      cv.addEventListener('pointermove', e => {
        if (!drag) return;
        const P = pages[st.page], it = P.items[drag.i], [px, py] = at(e);
        if (drag.newBox) { it.x = Math.min(drag.px, px); it.y = Math.min(drag.py, py); it.w = Math.abs(px - drag.px); it.h = Math.abs(py - drag.py); }
        else { it.x = drag.x + px - drag.px; it.y = drag.y + py - drag.py; if (it.cover) { /* moved writing: the white-out stays where the old word was */ } }
        paint();
      });
      cv.addEventListener('pointerup', () => {
        const P = pages[st.page];
        if (drag && drag.newBox && P.items[drag.i].w < 0.01) P.items.splice(drag.i, 1);
        drag = null;
      });
      function drawPanel() {
        panel.innerHTML = '';
        const P = pages[st.page], it = P && P.items[st.sel];
        if (!it) { panel.append(h('p', { cls: 'note' }, st.mode === 'text' ? 'page पर tap करें: लिखे हुए शब्द पर tap = उसे बदलें, खाली जगह पर tap = नया लिखें।' : st.mode === 'white' ? 'जो छुपाना है उस पर उँगली से डिब्बा खींचें।' : 'जहाँ sign block चाहिए वहाँ tap करें।')); return; }
        const del = h('button', { cls: 'chip', on: { click: () => { P.items.splice(st.sel, 1); st.sel = -1; paint(); } } }, '🗑 हटाओ');
        if (it.type === 'text') {
          const ta = h('textarea', { placeholder: 'यहाँ लिखें (keyboard के 🎤 से बोलकर भी)' });
          ta.value = it.text;
          ta.addEventListener('input', () => { it.text = ta.value; paint().then(() => { const t = panel.querySelector('textarea'); if (t !== ta) { /* keep focus */ } }); });
          const sz = (d) => () => { it.size = Math.max(5, Math.min(72, it.size + d)); st.size = it.size; paint(); };
          panel.append(h('div', { cls: 'panel' }, ta, h('div', { cls: 'chips' },
            h('button', { cls: 'chip', on: { click: sz(-1) } }, 'A−'), h('span', { style: 'align-self:center' }, it.size + ' pt'), h('button', { cls: 'chip', on: { click: sz(1) } }, 'A+'),
            h('button', { cls: 'chip' + (it.bold ? ' on' : ''), on: { click: () => { it.bold = !it.bold; paint(); } } }, 'B मोटा'),
            h('button', { cls: 'chip', on: { click: () => { it.color = it.color === '#0b2a8a' ? '#111' : '#0b2a8a'; paint(); } } }, it.color === '#0b2a8a' ? '● नीला' : '● काला'),
            del, h('button', { cls: 'chip', on: { click: () => { st.sel = -1; paint(); } } }, '✔ ठीक'))));
          // typing into the box repaints the page but keeps the box (and the keyboard) as it is
          panel.firstChild.__keep = true;
        } else {
          panel.append(h('div', { cls: 'panel' }, h('div', { cls: 'chips' },
            it.type === 'sign' ? h('button', { cls: 'chip', on: { click: () => { it.w = Math.max(0.08, it.w - 0.03); paint(); } } }, '− छोटा') : '',
            it.type === 'sign' ? h('button', { cls: 'chip', on: { click: () => { it.w = Math.min(0.9, it.w + 0.03); paint(); } } }, '+ बड़ा') : '',
            del, h('button', { cls: 'chip', on: { click: () => { st.sel = -1; paint(); } } }, '✔ ठीक'))));
        }
      }
      // repainting while typing must not rebuild the text box
      const origDraw = drawPanel;
      drawPanel = function () {
        const ta = panel.querySelector('textarea'), P = pages[st.page], it = P && P.items[st.sel];
        if (ta && document.activeElement === ta && it && it.type === 'text') return;
        origDraw();
      };
      function drawPages() {
        pageBox.innerHTML = '';
        if (pages.length < 2) return;
        pageBox.append(h('button', { cls: 'chip', on: { click: () => { if (st.page > 0) { st.page--; st.sel = -1; drawPages(); paint(); } } } }, '◀ पिछला पेज'),
          h('span', { style: 'align-self:center' }, ' पेज ' + (st.page + 1) + ' / ' + pages.length + ' '),
          h('button', { cls: 'chip', on: { click: () => { if (st.page < pages.length - 1) { st.page++; st.sel = -1; drawPages(); paint(); } } } }, 'अगला पेज ▶'));
      }
      const list = fileList('application/pdf,.pdf,image/*', false, async fs => {
        file = fs[0]; doc = null; photo = null; pages = []; run.disabled = true;
        if (!file) return;
        try {
          if (/\.pdf$/i.test(file.name) || /pdf/.test(file.type)) doc = await openPdf(file); else photo = await loadImage(file);
          msg('खोल रहा हूँ...');
          await loadPages();
          msg('');
          drawPages(); paint(); run.disabled = false;
        } catch (e) { msg('⚠ ' + e.message, true); }
      }, fill ? '📂 Sheet / format की photo या PDF' : '📂 PDF चुनें', true);
      const signerBox = signerPicker(p => { st.signer = p; st.mode = 'sign'; drawModes(); drawPanel(); });
      const modes = h('div', { cls: 'ed-tools' });
      function drawModes() {
        modes.innerHTML = '';
        modes.append(chips([['text', '✏️ लिखो / बदलो'], ['white', '⬜ छुपाओ (white-out)'], ['sign', '✍️ Sign block']], st.mode, v => { st.mode = v; st.sel = -1; paint(); drawPanel(); }),
          chips([[true, 'तारीख़ के साथ'], [false, 'बिना तारीख़']], st.date, v => { st.date = v; }));
      }
      drawModes();
      body.append(list, modes, pageBox, wrap, panel, signerBox, run);
    },
  };
}
T.pdfedit = editorTool(false);
T.fillform = editorTool(true);

// ---- Convert: Excel / CSV -> PDF, PDF -> Excel, PDF -> photos, Word -> PDF
// a small Excel writer (no packing: stored zip) for PDF -> Excel
const CRC = (() => { const t = []; for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; t[n] = c >>> 0; } return t; })();
function crc32(b) { let c = 0xffffffff; for (let i = 0; i < b.length; i++) c = CRC[(c ^ b[i]) & 255] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; }
function zipStore(files) { // [{name, data: Uint8Array}]
  const enc = new TextEncoder(), parts = [], dir = [];
  let off = 0;
  for (const f of files) {
    const name = enc.encode(f.name), crc = crc32(f.data), lh = new DataView(new ArrayBuffer(30));
    lh.setUint32(0, 0x04034b50, true); lh.setUint16(4, 20, true); lh.setUint16(8, 0, true);
    lh.setUint32(14, crc, true); lh.setUint32(18, f.data.length, true); lh.setUint32(22, f.data.length, true); lh.setUint16(26, name.length, true);
    parts.push(new Uint8Array(lh.buffer), name, f.data);
    const cd = new DataView(new ArrayBuffer(46));
    cd.setUint32(0, 0x02014b50, true); cd.setUint16(4, 20, true); cd.setUint16(6, 20, true);
    cd.setUint32(16, crc, true); cd.setUint32(20, f.data.length, true); cd.setUint32(24, f.data.length, true); cd.setUint16(28, name.length, true); cd.setUint32(42, off, true);
    dir.push(new Uint8Array(cd.buffer), name);
    off += 30 + name.length + f.data.length;
  }
  const dsize = dir.reduce((a, b) => a + b.length, 0), end = new DataView(new ArrayBuffer(22));
  end.setUint32(0, 0x06054b50, true); end.setUint16(8, files.length, true); end.setUint16(10, files.length, true); end.setUint32(12, dsize, true); end.setUint32(16, off, true);
  const all = parts.concat(dir, [new Uint8Array(end.buffer)]), total = all.reduce((a, b) => a + b.length, 0), out = new Uint8Array(total);
  let p = 0;
  all.forEach(b => { out.set(b, p); p += b.length; });
  return out;
}
function xlsxOf(sheets) { // [{name, rows: [[..]]}]
  const enc = new TextEncoder(), esc = v => String(v).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]).replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f]/g, '');
  const col = i => { let s = ''; for (i++; i > 0; i = Math.floor((i - 1) / 26)) s = String.fromCharCode(65 + (i - 1) % 26) + s; return s; };
  const files = [
    { name: '[Content_Types].xml', text: '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>' + sheets.map((_, i) => '<Override PartName="/xl/worksheets/sheet' + (i + 1) + '.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>').join('') + '</Types>' },
    { name: '_rels/.rels', text: '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>' },
    { name: 'xl/workbook.xml', text: '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>' + sheets.map((s, i) => '<sheet name="' + esc(s.name).slice(0, 31) + '" sheetId="' + (i + 1) + '" r:id="rId' + (i + 1) + '"/>').join('') + '</sheets></workbook>' },
    { name: 'xl/_rels/workbook.xml.rels', text: '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">' + sheets.map((_, i) => '<Relationship Id="rId' + (i + 1) + '" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet' + (i + 1) + '.xml"/>').join('') + '</Relationships>' },
  ];
  sheets.forEach((s, i) => files.push({ name: 'xl/worksheets/sheet' + (i + 1) + '.xml', text: '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>' +
    s.rows.map((r, ri) => '<row r="' + (ri + 1) + '">' + r.map((v, ci) => v === '' || v == null ? '' : /^-?\d+(\.\d+)?$/.test(String(v)) && String(v).length < 12 && !/^0\d/.test(String(v))
      ? '<c r="' + col(ci) + (ri + 1) + '"><v>' + v + '</v></c>' : '<c r="' + col(ci) + (ri + 1) + '" t="inlineStr"><is><t xml:space="preserve">' + esc(v) + '</t></is></c>').join('') + '</row>').join('') + '</sheetData></worksheet>' }));
  return zipStore(files.map(f => ({ name: f.name, data: enc.encode(f.text) })));
}
// the words of a PDF page put back into rows and columns
async function pdfPageTable(doc, n) {
  const page = await doc.getPage(n), vp = page.getViewport({ scale: 1 }), tc = await page.getTextContent();
  const items = tc.items.filter(t => t.str && t.str.trim()).map(t => { const m = pdfjs.Util.transform(vp.transform, t.transform); return { s: t.str.trim(), x: m[4], y: m[5], w: t.width || 0, fh: Math.hypot(m[2], m[3]) || 8 }; });
  if (!items.length) return [];
  items.sort((a, b) => a.y - b.y || a.x - b.x);
  const rows = [];
  items.forEach(it => { const r = rows.find(r => Math.abs(r.y - it.y) < it.fh * 0.5); if (r) r.items.push(it); else rows.push({ y: it.y, items: [it] }); });
  // column starts: x positions shared by many rows
  const starts = [];
  rows.forEach(r => r.items.forEach(it => { const s = starts.find(s => Math.abs(s.x - it.x) < 6); if (s) { s.n++; } else starts.push({ x: it.x, n: 1 }); }));
  const cols = starts.filter(s => s.n >= Math.max(2, rows.length * 0.15)).map(s => s.x).sort((a, b) => a - b);
  return rows.map(r => {
    const out = new Array(Math.max(1, cols.length)).fill('');
    r.items.sort((a, b) => a.x - b.x).forEach(it => {
      let ci = 0;
      cols.forEach((c, i) => { if (it.x >= c - 6) ci = i; });
      out[ci] = out[ci] ? out[ci] + ' ' + it.s : it.s;
    });
    return out;
  });
}
// a table drawn onto A4 pages (pictures), for Excel / Word -> PDF
function tableToCanvases(title, head, rows) {
  const S = 2, W = 842 * S, H = 595 * S, m = 24 * S, fs = 9 * S, lh = fs * 1.5;
  const all = [head].concat(rows), ncol = all.reduce((a, r) => Math.max(a, r.length), 0);
  const meas = document.createElement('canvas').getContext('2d');
  meas.font = fs + 'px "Nirmala UI","Noto Sans Devanagari",Arial,sans-serif';
  const want = Array.from({ length: ncol }, (_, c) => Math.min(260 * S, Math.max(30 * S, ...all.slice(0, 300).map(r => meas.measureText(String(r[c] || '')).width + 10 * S))));
  const tot = want.reduce((a, b) => a + b, 0), widths = want.map(w => w * Math.min(1, (W - 2 * m) / tot));
  const wrap = (t, w) => { const words = String(t || '').split(/\s+/), out = []; let line = ''; words.forEach(x => { const tryL = line ? line + ' ' + x : x; if (meas.measureText(tryL).width > w - 6 * S && line) { out.push(line); line = x; } else line = tryL; }); out.push(line); return out; };
  const pages = [];
  let c = null, ctx = null, y = 0;
  const newPage = () => {
    c = document.createElement('canvas'); c.width = W; c.height = H; ctx = c.getContext('2d');
    ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, W, H); ctx.font = meas.font; ctx.textBaseline = 'top'; ctx.strokeStyle = '#999'; ctx.lineWidth = 1;
    y = m;
    if (!pages.length && title) { ctx.font = 'bold ' + (13 * S) + 'px "Nirmala UI","Noto Sans Devanagari",Arial,sans-serif'; ctx.fillStyle = '#111'; ctx.fillText(title, m, y); y += 13 * S * 1.8; ctx.font = meas.font; }
    pages.push(c);
  };
  const drawRow = (r, isHead) => {
    const cells = Array.from({ length: ncol }, (_, i) => wrap(r[i], widths[i]));
    const rh = Math.max(...cells.map(l => l.length)) * lh + 4 * S;
    if (y + rh > H - m) { newPage(); if (!isHead) drawRow(head, true); }
    let x = m;
    cells.forEach((l, i) => {
      if (isHead) { ctx.fillStyle = '#dde6f5'; ctx.fillRect(x, y, widths[i], rh); }
      ctx.strokeRect(x, y, widths[i], rh);
      ctx.fillStyle = '#111';
      l.forEach((t, k) => ctx.fillText(t, x + 3 * S, y + 2 * S + k * lh));
      x += widths[i];
    });
    y += rh;
  };
  newPage();
  drawRow(head, true);
  rows.forEach(r => drawRow(r, false));
  return pages;
}
// Word (.docx): paragraphs and tables as plain text
async function docxBlocks(buf) {
  const u8 = new Uint8Array(buf);
  if (!(u8[0] === 0x50 && u8[1] === 0x4b)) throw new Error('यह .docx file नहीं है (पुरानी .doc हो तो Word में .docx में save करें)');
  const xml = await window.RoninMerge.unzipText(buf, 'word/document.xml');
  if (!xml) throw new Error('Word file खुल नहीं रही');
  const d = new DOMParser().parseFromString(xml, 'application/xml'), W = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main';
  const body = d.getElementsByTagNameNS(W, 'body')[0], out = [];
  const text = el => Array.from(el.getElementsByTagNameNS(W, 't')).map(t => t.textContent).join('');
  Array.from(body.children).forEach(el => {
    if (el.localName === 'p') out.push({ p: text(el) });
    else if (el.localName === 'tbl') out.push({ table: Array.from(el.getElementsByTagNameNS(W, 'tr')).map(tr => Array.from(tr.getElementsByTagNameNS(W, 'tc')).map(text)) });
  });
  return out;
}
function textPagesToCanvases(blocks) {
  const S = 2, W = 595 * S, H = 842 * S, m = 40 * S, fs = 11 * S, lh = fs * 1.6;
  const meas = document.createElement('canvas').getContext('2d');
  meas.font = fs + 'px "Nirmala UI","Noto Sans Devanagari",Arial,sans-serif';
  const pages = [];
  let ctx = null, y = H;
  const line = t => {
    if (y + lh > H - m) { const c = document.createElement('canvas'); c.width = W; c.height = H; ctx = c.getContext('2d'); ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, W, H); ctx.fillStyle = '#111'; ctx.font = meas.font; ctx.textBaseline = 'top'; pages.push(c); y = m; }
    ctx.fillText(t, m, y); y += lh;
  };
  const para = t => { const words = String(t).split(/\s+/); let l = ''; words.forEach(w => { const tl = l ? l + ' ' + w : w; if (meas.measureText(tl).width > W - 2 * m && l) { line(l); l = w; } else l = tl; }); line(l); };
  blocks.forEach(b => { if (b.p !== undefined) para(b.p || ' '); else b.table.forEach(r => para(r.join('  |  '))); });
  if (!pages.length) line(' ');
  return pages;
}
T.convert = {
  title: '🔁 Convert',
  sub: 'Excel / CSV → PDF, PDF → Excel, PDF → Photos (JPG), Word (.docx) → PDF.',
  show(body) {
    const st = { kind: 'x2p' };
    const area = h('div');
    const KINDS = {
      x2p: ['Excel / CSV → PDF', '.xlsx,.xlsm,.csv,.xls,.htm,.html', async f => {
        const data = await window.RoninMerge.readFile(f.name, await f.arrayBuffer());
        const pages = [];
        data.tables.forEach(t => pages.push(...tableToCanvases(baseName(f.name), t.head, t.rows)));
        const bytes = await picturesToPdf(pages, 0);
        await save(fileName(f.name, '', 'pdf'), 'application/pdf', bytes);
        return pages.length + ' पेज की PDF';
      }],
      p2x: ['PDF → Excel', 'application/pdf,.pdf', async f => {
        const doc = await openPdf(f), sheets = [];
        let rows = 0;
        for (let i = 1; i <= doc.numPages; i++) { const t = await pdfPageTable(doc, i); rows += t.length; sheets.push({ name: 'Page ' + i, rows: t }); }
        if (!rows) throw new Error('इस PDF में टाइप किया हुआ text नहीं है (scan / photo वाली PDF): इसे Excel में नहीं बदला जा सकता');
        await save(fileName(f.name, '', 'xlsx'), 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', xlsxOf(sheets));
        return rows + ' पंक्तियाँ, ' + sheets.length + ' sheet (हर पेज अलग sheet)। खोलकर देख लें: कॉलम कहीं-कहीं खिसक सकते हैं।';
      }],
      p2j: ['PDF → Photos (JPG)', 'application/pdf,.pdf', async f => {
        const doc = await openPdf(f);
        for (let i = 1; i <= doc.numPages; i++) {
          msg('पेज ' + i + ' / ' + doc.numPages + '...');
          const r = await renderPage(doc, i, 2);
          await save(fileName(f.name, 'page' + i, 'jpg'), 'image/jpeg', await canvasBytes(r.canvas, 'image/jpeg', 0.88));
        }
        return doc.numPages + ' photo';
      }],
      w2p: ['Word (.docx) → PDF', '.docx', async f => {
        const pages = textPagesToCanvases(await docxBlocks(await f.arrayBuffer()));
        const bytes = await picturesToPdf(pages, 0);
        await save(fileName(f.name, '', 'pdf'), 'application/pdf', bytes);
        return pages.length + ' पेज की PDF (सिर्फ़ लिखावट और tables; design, photo नहीं)';
      }],
    };
    function draw() {
      area.innerHTML = '';
      const [, accept, work] = KINDS[st.kind];
      const run = runButton('🔁 बदलो और save करो', async () => { const r = await work(list.files[0]); msg('✔ ' + r + '। Downloads में save है।'); });
      const list = fileList(accept, false, fs => { run.disabled = !fs.length; });
      area.append(list, run);
    }
    body.append(chips(Object.entries(KINDS).map(([k, v]) => [k, v[0]]), st.kind, v => { st.kind = v; msg(''); draw(); }), area);
    draw();
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
  await save('Ronin_tools_selftest.pdf', 'application/pdf', await out.save());
  return 'ok ' + doc.numPages;
};

// start: #tool opens a tool straight away (used by the menu and the tests)
const start = location.hash.slice(1);
if (T[start]) open(start); else home();
window.__roninToolsOpen = open;
