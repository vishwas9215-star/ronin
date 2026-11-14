// Ronin "डेटा मिलाओ" (merge data): reads several Excel / CSV files, for example the
// student lists from Prerna and UDISE+, finds the same child in each and builds one
// table: every child once, every column from every portal, differences marked with
// their reason. Everything happens on this device; nothing is sent anywhere.
// The result has the same shape as table_export.js, so the existing Excel / PDF
// writers (Windows: internal/tablexport, Android: TableExport.kt) save it.
(function (root) {
  'use strict';
  const M = {};
  const td = new TextDecoder();

  // ---------------------------------------------------------------- reading files
  async function unzip(buf) {
    const u8 = new Uint8Array(buf), dv = new DataView(buf);
    let eocd = -1;
    for (let i = u8.length - 22; i >= Math.max(0, u8.length - 65557); i--) {
      if (dv.getUint32(i, true) === 0x06054b50) { eocd = i; break; }
    }
    if (eocd < 0) throw new Error('यह Excel file खुल नहीं रही (टूटी हुई है)');
    const n = dv.getUint16(eocd + 10, true);
    let p = dv.getUint32(eocd + 16, true);
    const files = {};
    for (let k = 0; k < n && dv.getUint32(p, true) === 0x02014b50; k++) {
      const nlen = dv.getUint16(p + 28, true), xlen = dv.getUint16(p + 30, true), clen = dv.getUint16(p + 32, true);
      files[td.decode(u8.subarray(p + 46, p + 46 + nlen))] = { method: dv.getUint16(p + 10, true), size: dv.getUint32(p + 20, true), off: dv.getUint32(p + 42, true) };
      p += 46 + nlen + xlen + clen;
    }
    const get = async (name) => {
      const key = Object.keys(files).find(k => k.toLowerCase() === name.toLowerCase());
      if (!key) return null;
      const f = files[key], h = f.off;
      const start = h + 30 + dv.getUint16(h + 26, true) + dv.getUint16(h + 28, true);
      const data = u8.subarray(start, start + f.size);
      if (f.method === 0) return td.decode(data);
      const out = await new Response(new Blob([data]).stream().pipeThrough(new DecompressionStream('deflate-raw'))).arrayBuffer();
      return td.decode(out);
    };
    return { get };
  }

  const xmlDoc = s => new DOMParser().parseFromString(s, 'application/xml');
  const tags = (el, name) => Array.from(el.getElementsByTagName(name));
  function colIndex(ref) {
    let n = 0;
    for (const ch of ref.replace(/[0-9]/g, '')) n = n * 26 + ch.charCodeAt(0) - 64;
    return n - 1;
  }
  function excelDate(v) {
    const d = new Date(Math.round((v - 25569) * 86400000));
    const p = x => String(x).padStart(2, '0');
    return p(d.getUTCDate()) + '/' + p(d.getUTCMonth() + 1) + '/' + d.getUTCFullYear();
  }
  function plainNumber(v) {
    const x = Number(v);
    if (!isFinite(x)) return v;
    if (Number.isInteger(x)) return x.toLocaleString('fullwide', { useGrouping: false });
    return String(+x.toPrecision(12));
  }

  async function readXlsx(buf) {
    const z = await unzip(buf);
    const wbXml = await z.get('xl/workbook.xml');
    if (!wbXml) throw new Error('यह Excel file खुल नहीं रही');
    const wb = xmlDoc(wbXml), rels = xmlDoc((await z.get('xl/_rels/workbook.xml.rels')) || '<r/>');
    const target = {};
    tags(rels, 'Relationship').forEach(r => { target[r.getAttribute('Id')] = r.getAttribute('Target'); });
    const ssXml = await z.get('xl/sharedStrings.xml');
    const shared = ssXml ? tags(xmlDoc(ssXml), 'si').map(si => tags(si, 't').map(t => t.textContent).join('')) : [];
    // which cell styles are dates
    const dateStyle = [];
    const stXml = await z.get('xl/styles.xml');
    if (stXml) {
      const st = xmlDoc(stXml), custom = {};
      tags(st, 'numFmt').forEach(f => { custom[f.getAttribute('numFmtId')] = f.getAttribute('formatCode') || ''; });
      const xfs = st.getElementsByTagName('cellXfs')[0];
      if (xfs) tags(xfs, 'xf').forEach((xf, i) => {
        const id = +xf.getAttribute('numFmtId') || 0;
        dateStyle[i] = (id >= 14 && id <= 22) || (id >= 45 && id <= 47) || (custom[id] !== undefined && /[dy]/i.test(custom[id].replace(/"[^"]*"|\[[^\]]*\]/g, '')));
      });
    }
    const out = { tables: [], site: '', title: '' };
    for (const sh of tags(wb, 'sheet')) {
      const name = sh.getAttribute('name') || '';
      const rid = sh.getAttribute('r:id') || sh.getAttributeNS('http://schemas.openxmlformats.org/officeDocument/2006/relationships', 'id');
      let path = (target[rid] || '').replace(/^\//, '');
      if (!path) continue;
      if (!path.startsWith('xl/')) path = 'xl/' + path;
      const xml = await z.get(path);
      if (!xml) continue;
      const grid = [];
      tags(xmlDoc(xml), 'row').forEach((row, ri) => {
        const r = (+row.getAttribute('r') || ri + 1) - 1;
        const cells = grid[r] = [];
        let next = 0;
        tags(row, 'c').forEach(c => {
          const ref = c.getAttribute('r');
          const ci = ref ? colIndex(ref) : next;
          next = ci + 1;
          const t = c.getAttribute('t'), v = c.getElementsByTagName('v')[0];
          let val = '';
          if (t === 's') val = v ? (shared[+v.textContent] || '') : '';
          else if (t === 'inlineStr') val = tags(c, 't').map(x => x.textContent).join('');
          else if (t === 'b') val = v && v.textContent === '1' ? 'TRUE' : 'FALSE';
          else if (v) val = (t === 'str' || t === 'e') ? v.textContent : (dateStyle[+c.getAttribute('s') || 0] ? excelDate(+v.textContent) : plainNumber(v.textContent));
          cells[ci] = val;
        });
      });
      for (let i = 0; i < grid.length; i++) grid[i] = Array.from(grid[i] || [], x => x == null ? '' : String(x));
      if (/^(summary|सारांश)$/i.test(name.trim())) {
        // a file Ronin made: its Summary sheet says which site it came from
        grid.forEach(r => {
          if (/^site$/i.test((r[0] || '').trim())) out.site = (r[1] || '').trim();
          if (/^page/i.test((r[0] || '').trim())) out.title = (r[1] || '').trim();
        });
        continue;
      }
      out.tables.push({ sheet: name, grid });
    }
    return out;
  }

  function readCsv(text) {
    text = text.replace(/^﻿/, '');
    const first = text.split(/\r?\n/, 1)[0];
    const sep = [',', ';', '\t'].map(s => [s, first.split(s).length]).sort((a, b) => b[1] - a[1])[0][0];
    const grid = [];
    let row = [], cell = '', q = false;
    for (let i = 0; i < text.length; i++) {
      const ch = text[i];
      if (q) {
        if (ch === '"' && text[i + 1] === '"') { cell += '"'; i++; } else if (ch === '"') q = false; else cell += ch;
      } else if (ch === '"') q = true;
      else if (ch === sep) { row.push(cell); cell = ''; }
      else if (ch === '\n' || ch === '\r') {
        if (ch === '\r' && text[i + 1] === '\n') i++;
        row.push(cell); grid.push(row); row = []; cell = '';
      } else cell += ch;
    }
    if (cell || row.length) { row.push(cell); grid.push(row); }
    return { tables: [{ sheet: '', grid }], site: '', title: '' };
  }

  function readHtml(text) {
    const doc = new DOMParser().parseFromString(text, 'text/html');
    const tables = Array.from(doc.querySelectorAll('table')).map(t => ({
      sheet: '', grid: Array.from(t.rows).map(r => Array.from(r.cells).flatMap(c => {
        const v = c.textContent.replace(/\s+/g, ' ').trim();
        return [v].concat(Array(Math.max(0, (c.colSpan || 1) - 1)).fill(''));
      })),
    })).filter(t => t.grid.length > 1);
    return { tables, site: '', title: '' };
  }

  // readFile: {name, bytes(ArrayBuffer)} -> {tables:[{head, rows}], site, title}
  M.readFile = async function (name, buf) {
    const u8 = new Uint8Array(buf.slice(0, 8));
    let got;
    if (u8[0] === 0x50 && u8[1] === 0x4b) got = await readXlsx(buf);
    else if (u8[0] === 0xd0 && u8[1] === 0xcf) throw new Error('यह पुरानी .xls file है: इसे portal से फिर "Excel (.xlsx)" या Ronin के 📊 Data बटन से download करें');
    else if (u8[0] === 0x25 && u8[1] === 0x50) throw new Error('PDF अभी नहीं पढ़ी जाती: उसी list की Excel file डालें (portal पर Ronin का 📊 Data बटन Excel बना देता है)');
    else {
      const text = td.decode(buf);
      got = /<table/i.test(text.slice(0, 200000)) ? readHtml(text) : readCsv(text);
    }
    const tables = got.tables.map(t => toTable(t.grid)).filter(Boolean);
    if (!tables.length) throw new Error('इसमें कोई table नहीं मिली');
    return { name, tables, site: got.site, title: got.title };
  };

  // the header row is the first full-looking row with words like "name" in it
  function toTable(grid) {
    grid = grid.filter(r => r && r.some(c => String(c).trim()));
    if (grid.length < 2) return null;
    let hi = -1, best = 0;
    for (let i = 0; i < Math.min(grid.length, 25); i++) {
      const cells = grid[i].map(c => String(c).trim()).filter(Boolean);
      if (cells.length < 2 || cells.every(c => /^[\d\s.\/:-]+$/.test(c))) continue;
      let s = cells.length + (cells.some(c => /name|नाम/i.test(c)) ? 6 : 0) - cells.filter(c => /\d{3,}/.test(c)).length * 2;
      if (s > best) { best = s; hi = i; }
    }
    if (hi < 0) return null;
    let head = grid[hi].map(c => String(c).replace(/\s+/g, ' ').trim());
    let start = hi + 1;
    // a second header line ("Name" under "Father") has no digits at all
    const nx = grid[start];
    if (nx && !nx.some(c => /\d/.test(c)) && nx.filter(c => String(c).trim()).length >= 2 && grid.length > start + 1) {
      head = head.map((h, i) => [h, String(nx[i] || '').trim()].filter(Boolean).join(' '));
      start++;
    }
    const width = Math.max(head.length, ...grid.slice(start).map(r => r.length));
    head = Array.from({ length: width }, (_, i) => head[i] || '');
    const hk = head.map(h => h.toLowerCase()).join('|');
    let rows = grid.slice(start)
      .map(r => Array.from({ length: width }, (_, i) => String(r[i] == null ? '' : r[i]).replace(/\s+/g, ' ').trim()))
      .filter(r => r.join('|').toLowerCase() !== hk && r.filter(Boolean).length >= 2);
    // columns: drop Ronin's own Check column and empty ones; name the nameless
    const keep = head.map((h, i) => !/^जाँच \/ Check$/.test(h) && (h || rows.some(r => r[i])));
    head = head.map((h, i) => h || 'Column ' + (i + 1)).filter((_, i) => keep[i]);
    rows = rows.map(r => r.filter((_, i) => keep[i]));
    return rows.length ? { head, rows } : null;
  }

  // ---------------------------------------------------------------- fields
  const FIELDS = [
    ['serial', /^(s\.?\s*no|sr\.?\s*no|sl\.?\s*no|serial(\s*no)?|क्रम(\s*सं.*)?|क्र\.?\s*सं\.?|#)\.?$/i],
    ['skip', /status|verif|request|change|remark|action|edit|delete|view/i],
    ['time', /^(timestamp|time ?stamp|टाइमस्टैम्प|समय)$/i],
    ['email', /e-?mail|ईमेल/i],
    ['father', /father|husband|पिता|पति/i],
    ['mother', /mother|माता/i],
    ['guardian', /guardian|अभिभावक|संरक्षक/i],
    ['dob', /\bd\.?\s?o\.?\s?b\b|date of birth|birth\s*date|जन्म/i],
    ['admdate', /ad+mis+ion.*date|date.*ad+mis+ion|प्रवेश.*(तिथि|दिनांक)/i],
    ['admno', /ad+mis+ion.*(no|number|num)|प्रवेश.*(सं|क्रमांक)/i],
    ['pen', /^(student\s*)?pen(\s*(no|number|id))?$/i],
    ['pran', /\bpran\b|प्रान/i],
    ['empid', /(employee|emp|teacher|staff|ehrms|e-hrms|कर्मचारी|शिक्षक|मानव\s*सम्पदा|मानव\s*संपदा).*(id|code|कोड|आईडी|no\b|number|संख्या)|^(ehrms|manav sampada)/i],
    ['joining', /joining|appointment|नियुक्ति|कार्यभार|\bdoj\b/i],
    ['designation', /designation|^post$|^पद(नाम)?$/i],
    ['aadhaar', /aadha?a?r|आधार/i],
    ['mobile', /mobile|phone|contact|मोबाइल|फ़ोन|फोन/i],
    ['gender', /gender|^sex$|लिंग/i],
    ['class', /^(student\s*|current\s*)?(class|grade|std|standard|कक्षा)(\s*(name|no))?$/i],
    ['section', /^(section|sec|अनुभाग)$/i],
    ['religion', /religion|धर्म/i],
    ['category', /^(social\s*)?category$|जाति|वर्ग/i],
    ['address', /address|पता|village|ग्राम/i],
    ['name', /(student|child|pupil|teacher|employee|staff|छात्र|विद्यार्थी|बच्चे|बालक|शिक्षक|अध्यापक|कर्मचारी).*(name|नाम)|name of (the )?(student|child|teacher|employee)|^(full name|name|नाम|पूरा नाम|student|teacher)$/i],
  ];
  const ORDER = ['name', 'empid', 'father', 'mother', 'guardian', 'dob', 'gender', 'designation', 'joining', 'class', 'section', 'admno', 'admdate', 'pen', 'pran', 'aadhaar', 'mobile', 'email', 'category', 'religion', 'address', 'time'];
  const HINDI = { name: 'नाम', father: 'पिता का नाम', mother: 'माता का नाम', guardian: 'अभिभावक', dob: 'जन्मतिथि', gender: 'लिंग', class: 'कक्षा', section: 'अनुभाग', admno: 'प्रवेश संख्या', admdate: 'प्रवेश तिथि', empid: 'कर्मचारी कोड', pran: 'PRAN', joining: 'नियुक्ति तिथि', designation: 'पद', email: 'ईमेल', time: 'भरने का समय', pen: 'PEN', aadhaar: 'आधार', mobile: 'मोबाइल', category: 'वर्ग', religion: 'धर्म', address: 'पता' };

  function fieldOf(h) {
    const x = h.trim();
    for (const [f, re] of FIELDS) {
      if (re.test(x)) {
        if (f === 'aadhaar' && /father|mother|guardian|पिता|माता|अभिभावक/i.test(x)) return 'key:' + x.toLowerCase();
        return f === 'skip' ? 'key:' + x.toLowerCase().replace(/[^a-z0-9ऀ-ॿ]+/g, ' ').trim() : f;
      }
    }
    return 'key:' + x.toLowerCase().replace(/[^a-z0-9ऀ-ॿ]+/g, ' ').trim();
  }

  const EMPTY = /^(-+|na|n\/a|nil|null|none|not available|\.|0)$/i;
  function personName(v) {
    return v.normalize('NFC').toUpperCase()
      .replace(/[^A-Zऀ-ॿ ]+/g, ' ')
      .replace(/^(SHRI|SRI|SMT|MR|MRS|MS|LATE|KM|KU)\s+/, '')
      .replace(/\s+/g, ' ').trim();
  }
  const MONTHS = { JAN: 1, FEB: 2, MAR: 3, APR: 4, MAY: 5, JUN: 6, JUL: 7, AUG: 8, SEP: 9, OCT: 10, NOV: 11, DEC: 12 };
  function dateOf(v) {
    const s = v.trim().toUpperCase();
    const p = x => String(x).padStart(2, '0');
    let m;
    if (/^\d{5}(\.\d+)?$/.test(s)) { const d = excelDate(+s).split('/'); return d[2] + '-' + d[1] + '-' + d[0]; }
    if ((m = s.match(/^(\d{4})[-\/.](\d{1,2})[-\/.](\d{1,2})/))) return m[1] + '-' + p(m[2]) + '-' + p(m[3]);
    if ((m = s.match(/^(\d{1,2})[-\/. ](\d{1,2})[-\/. ](\d{2,4})/))) {
      const y = m[3].length === 2 ? (+m[3] > 50 ? '19' : '20') + m[3] : m[3];
      return y + '-' + p(m[2]) + '-' + p(m[1]);
    }
    if ((m = s.match(/^(\d{1,2})[-\/ ]([A-Z]{3})[A-Z]*[-\/ ,]+(\d{4})/)) && MONTHS[m[2]]) return m[3] + '-' + p(MONTHS[m[2]]) + '-' + p(m[1]);
    return s;
  }
  const ROMAN = { I: 1, II: 2, III: 3, IV: 4, V: 5, VI: 6, VII: 7, VIII: 8, IX: 9, X: 10, XI: 11, XII: 12 };
  function norm(field, v) {
    v = String(v || '').replace(/\s+/g, ' ').trim();
    if (!v || EMPTY.test(v)) return '';
    switch (field) {
      case 'name': case 'father': case 'mother': case 'guardian': return personName(v);
      case 'dob': case 'admdate': case 'joining': return dateOf(v);
      case 'time': { const d = dateOf(v), t = (v.match(/(\d{1,2}):(\d{2})(?::(\d{2}))?\s*(AM|PM)?/i) || []); let h = +(t[1] || 0); if (/pm/i.test(t[4] || '') && h < 12) h += 12; return d + ' ' + String(h).padStart(2, '0') + ':' + (t[2] || '00') + ':' + (t[3] || '00'); }
      case 'email': return v.toLowerCase().replace(/\s/g, '');
      case 'designation': return v.toUpperCase().replace(/[^A-Z\u0900-\u097f]+/g, ' ').trim();
      case 'gender': {
        const g = v.toUpperCase();
        if (/^(M|MALE|BOY|B|बालक|पुरुष|लड़का|लडका)$/.test(g)) return 'M';
        if (/^(F|FEMALE|GIRL|G|बालिका|महिला|लड़की|लडकी)$/.test(g)) return 'F';
        if (/^(T|TRANS)/.test(g)) return 'T';
        return g;
      }
      case 'class': {
        const c = v.toUpperCase().replace(/CLASS|GRADE|STD|कक्षा|[-_.]/g, ' ').trim();
        let m = c.match(/\b(\d{1,2})\b/);
        if (m) return String(+m[1]);
        m = c.match(/\b(XII|XI|X|IX|VIII|VII|VI|V|IV|III|II|I)\b/);
        if (m) return String(ROMAN[m[1]]);
        return c.replace(/\s+/g, ' ');
      }
      case 'mobile': { const d = v.replace(/\D/g, ''); return d.length >= 10 ? d.slice(-10) : d; }
      case 'aadhaar': case 'pen': case 'admno': case 'empid': case 'pran': return v.toUpperCase().replace(/[^0-9A-Z]/g, '');
      case 'address': return v.toUpperCase().replace(/[^A-Z0-9ऀ-ॿ]+/g, ' ').trim();
      default: return v.toUpperCase().replace(/[^A-Z0-9ऀ-ॿ]+/g, ' ').trim();
    }
  }
  M.norm = norm;
  M.fieldOf = fieldOf;

  function same(field, a, b) {
    if (a === b) return true;
    if (field === 'aadhaar' && (/X/.test(a) || /X/.test(b))) return a.slice(-4) === b.slice(-4); // masked numbers
    if (field === 'address') {
      const ta = new Set(a.split(' ')), tb = new Set(b.split(' '));
      let both = 0;
      ta.forEach(t => { if (tb.has(t)) both++; });
      return both / Math.min(ta.size, tb.size) >= 0.6; // "BARSOMA" and "VILLAGE BARSOMA POST ..." are the same place
    }
    return false;
  }

  function lev(a, b) {
    if (a === b) return 0;
    let prev = Array.from({ length: b.length + 1 }, (_, i) => i);
    for (let i = 1; i <= a.length; i++) {
      const cur = [i];
      for (let j = 1; j <= b.length; j++) cur[j] = Math.min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
      prev = cur;
    }
    return prev[b.length];
  }
  function nameSim(a, b) {
    if (!a || !b) return 0;
    if (a === b) return 1;
    const r = (x, y) => 1 - lev(x, y) / Math.max(x.length, y.length);
    const sa = a.split(' ').sort().join(' '), sb = b.split(' ').sort().join(' ');
    let s = Math.max(r(a, b), r(sa, sb), r(a.replace(/ /g, ''), b.replace(/ /g, '')));
    // "ANSH" and "ANSH KUMAR": the same first name, one is longer
    const fa = a.split(' '), fb = b.split(' ');
    if (fa[0] === fb[0] && fa[0].length >= 3 && (fa.every(t => fb.includes(t)) || fb.every(t => fa.includes(t)))) s = Math.max(s, 0.85);
    return s;
  }
  M.nameSim = nameSim;
  function dobNear(a, b) { // a typo or day / month swapped
    const x = a.split('-'), y = b.split('-');
    if (x.length !== 3 || y.length !== 3) return false;
    if (x[0] === y[0] && x[1] === y[2] && x[2] === y[1]) return true;
    return x.filter((v, i) => v !== y[i]).length === 1;
  }

  function score(a, b) {
    const n = nameSim(a.name, b.name);
    const sameId = a.empid && a.empid === b.empid;
    if (n < 0.6 && !sameId) return null;
    let s = n * 50, max = 50;
    if (a.father && b.father) { const f = nameSim(a.father, b.father); max += 25; s += f * 25 - (f < 0.5 ? 20 : 0); }
    if (a.mother && b.mother) { const f = nameSim(a.mother, b.mother); max += 10; s += f * 10 - (f < 0.5 ? 8 : 0); }
    if (a.dob && b.dob) { max += 20; s += a.dob === b.dob ? 20 : dobNear(a.dob, b.dob) ? 8 : -20; }
    if (a.gender && b.gender) { max += 5; s += a.gender === b.gender ? 5 : -10; }
    if (a.class && b.class) { max += 3; s += a.class === b.class ? 3 : 0; }
    if (a.empid && b.empid) { max += 40; s += a.empid === b.empid ? 40 : -30; }
    if (a.mobile && b.mobile && a.mobile.length === 10) { max += 10; s += a.mobile === b.mobile ? 10 : 0; }
    if (a.email && b.email) { max += 10; s += a.email === b.email ? 10 : 0; }
    return { s, ratio: s / max };
  }
  const SURE = 0.78, MAYBE = 0.62;

  // ---------------------------------------------------------------- merging
  // files: [{name, tables, site}] (from readFile). Returns the Data JSON object.
  M.merge = function (files) {
    // 1. group tables into sources (portals): by site / file name, else by similar columns
    const sources = [];
    let tablesRead = 0;
    for (const f of files) for (const t of f.tables) {
      tablesRead++;
      const hint = (f.site + ' ' + f.name + ' ' + (f.title || '')).toLowerCase();
      const label = /prerna/.test(hint) ? 'Prerna' : /udise/.test(hint) ? 'UDISE+' : '';
      const keys = new Set(t.head.map(fieldOf));
      // a Google Form download (has "Timestamp") is its own source: old form and new form stay apart
      const form = keys.has('time');
      let src = form ? null : sources.find(s => !s.form && (label ? s.label === label : jaccard(s.keys, keys) >= 0.7));
      if (!src) {
        src = { label: label || f.name.replace(/\.[a-z0-9]+$/i, '').replace(/\s*\((responses|form responses[^)]*|उत्तर|जवाब)\)\s*/ig, ' ').replace(/\s+/g, ' ').trim().slice(0, 30), form, keys: new Set(), head: [], fieldIndex: {}, rows: [], files: new Set(), skipped: 0, seen: new Set() };
        sources.push(src);
      }
      keys.forEach(k => src.keys.add(k));
      src.files.add(f.name);
      const map = t.head.map(h => {
        const k = fieldOf(h);
        if (k === 'serial') return -1;
        let i = src.head.findIndex(x => x.key === k);
        if (i < 0) { src.head.push({ key: k, text: h }); i = src.head.length - 1; }
        return i;
      });
      for (const r of t.rows) {
        const row = [];
        map.forEach((i, c) => { if (i >= 0 && !row[i]) row[i] = r[c]; });
        const sig = src.head.map((_, i) => norm('', row[i] || '')).join('|');
        if (src.seen.has(sig)) { src.skipped++; continue; } // the same row twice (two files, or a page read twice)
        src.seen.add(sig);
        src.rows.push(row);
      }
    }
    sources.forEach((s, i) => { if (sources.findIndex(x => x.label === s.label) !== i) s.label += ' (' + (i + 1) + ')'; });

    // 2. each row's comparable values
    const recs = [];
    sources.forEach((s, si) => s.rows.forEach(row => {
      const f = {};
      s.head.forEach((h, i) => { if (!h.key.startsWith('key:') && !(h.key in f)) f[h.key] = norm(h.key, row[i]); });
      recs.push({ si, row, f });
    }));

    // 3. children: the first source's rows, then every other source matched in
    const children = [];
    const childFeat = c => c.feat;
    const index = new Map();
    const addIndex = (c, f) => {
      const keys = [];
      if (f.name) keys.push('n' + f.name.replace(/ /g, '').slice(0, 3));
      if (f.name) f.name.split(' ').forEach(t => { if (t.length >= 3) keys.push('t' + t.slice(0, 4)); });
      if (f.dob) keys.push('d' + f.dob);
      if (f.father) keys.push('f' + f.father.replace(/ /g, '').slice(0, 4));
      keys.forEach(k => { if (!index.has(k)) index.set(k, new Set()); index.get(k).add(c); });
    };
    const candidates = f => {
      const out = new Set();
      const keys = [];
      if (f.name) keys.push('n' + f.name.replace(/ /g, '').slice(0, 3));
      if (f.name) f.name.split(' ').forEach(t => { if (t.length >= 3) keys.push('t' + t.slice(0, 4)); });
      if (f.dob) keys.push('d' + f.dob);
      if (f.father) keys.push('f' + f.father.replace(/ /g, '').slice(0, 4));
      keys.forEach(k => (index.get(k) || []).forEach(c => out.add(c)));
      return out;
    };
    const strongKey = f => f.empid ? 'e' + f.empid : f.name && (f.dob || f.father) ? f.name + '|' + (f.dob || '') + '|' + (f.father || '') : f.name && f.mobile ? f.name + '|m' + f.mobile : f.name && f.email ? f.name + '|' + f.email : '';
    const latestFirst = g => g.sort((a, b) => (b.f.time || '').localeCompare(a.f.time || ''));

    sources.forEach((s, si) => {
      const mine = recs.filter(r => r.si === si);
      // the same child twice inside one portal
      const groups = [], byKey = new Map();
      mine.forEach(r => {
        const k = strongKey(r.f);
        if (k && byKey.has(k)) byKey.get(k).push(r);
        else { const g = [r]; groups.push(g); if (k) byKey.set(k, g); }
      });
      groups.forEach(latestFirst); // a form filled twice: the newest answer counts
      if (si === 0 || !children.length) {
        groups.forEach(g => { const c = { recs: g, feat: Object.assign({}, g[0].f), weak: '' }; children.push(c); addIndex(c, c.feat); });
        return;
      }
      const pairs = [];
      groups.forEach(g => candidates(g[0].f).forEach(c => {
        if (c.recs.some(r => r.si === si)) return;
        const sc = score(childFeat(c), g[0].f);
        if (sc && sc.ratio >= MAYBE) pairs.push({ c, g, s: sc.s, ratio: sc.ratio });
      }));
      pairs.sort((a, b) => b.s - a.s);
      const usedC = new Set(), usedG = new Set();
      for (const p of pairs) {
        if (usedC.has(p.c) || usedG.has(p.g)) continue;
        // another child (or row) almost as close: not sure which one is right
        const rival = pairs.find(q => q !== p && q.ratio >= MAYBE && p.s - q.s < 4 &&
          ((q.c === p.c && !usedG.has(q.g)) || (q.g === p.g && !usedC.has(q.c))));
        usedC.add(p.c); usedG.add(p.g);
        p.c.recs.push(...p.g);
        if (p.ratio < SURE) p.c.weak = 'नारंगी: मिलान पक्का नहीं, जाँचें';
        else if (rival) p.c.weak = 'नारंगी: एक और मिलता-जुलता नाम है, मिलान जाँचें';
        Object.keys(p.g[0].f).forEach(k => { if (!p.c.feat[k]) p.c.feat[k] = p.g[0].f[k]; });
        addIndex(p.c, p.g[0].f);
      }
      groups.filter(g => !usedG.has(g)).forEach(g => { const c = { recs: g, feat: Object.assign({}, g[0].f), weak: '' }; children.push(c); addIndex(c, c.feat); });
    });

    // 4. the columns: known fields first, then everything else
    const cols = [];
    const colOf = {};
    const allKeys = [];
    sources.forEach(s => s.head.forEach(h => { if (!allKeys.includes(h.key)) allKeys.push(h.key); }));
    allKeys.sort((a, b) => {
      const ia = ORDER.indexOf(a), ib = ORDER.indexOf(b);
      return (ia < 0 ? 99 : ia) - (ib < 0 ? 99 : ib);
    });
    allKeys.forEach(k => {
      const has = sources.map((s, si) => [si, s.head.findIndex(h => h.key === k)]).filter(x => x[1] >= 0);
      const text = sources[has[0][0]].head[has[0][1]].text;
      let title = HINDI[k] || text; // short Hindi names for the known fields
      if (sources.length > 1 && has.length === 1) title += ' (' + sources[has[0][0]].label + ')';
      colOf[k] = cols.length;
      cols.push({ key: k, title, has });
    });

    // 5. rows and marks
    const multi = sources.length > 1;
    const teachers = allKeys.some(k => k === 'empid' || k === 'designation' || k === 'joining' || k === 'pran');
    const who = teachers ? 'शिक्षक' : 'बच्चा', whoMany = teachers ? 'शिक्षक' : 'बच्चे';
    const head = ['क्रम', 'किसमें मिला / Found in'].concat(cols.map(c => c.title));
    const out = [];
    let nDiff = 0, nBoth = 0, nWeak = 0, nDup = 0;
    const only = sources.map(() => 0), missing = sources.map(() => 0);
    children.forEach(c => {
      const issues = {};
      const mark = (col, kind, note) => {
        const rank = { dup: 1, only: 2, similar: 3, diff: 4 };
        const o = issues[col];
        if (!o) issues[col] = [kind, note];
        else issues[col] = [rank[kind] > rank[o[0]] ? kind : o[0], o[1] + '; ' + note];
      };
      const inSrc = sources.map((_, si) => c.recs.filter(r => r.si === si));
      const present = inSrc.map((r, si) => r.length ? si : -1).filter(x => x >= 0);
      inSrc.forEach((r, si) => { if (!r.length) missing[si]++; });
      const row = ['', present.map(si => sources[si].label).join(' + ')];
      if (multi && present.length < sources.length) {
        const missing = sources.filter((_, si) => !inSrc[si].length).map(s => s.label).join(', ');
        mark(1, 'only', 'धूसर: सिर्फ़ ' + present.map(si => sources[si].label).join(' + ') + ' में है, ' + missing + ' में यह ' + who + ' नहीं मिला' + (sources.some((s, si) => !inSrc[si].length && s.form) ? ' (form नहीं भरा)' : ''));
        if (present.length === 1) only[present[0]]++;
      } else if (multi) nBoth++;
      if (c.weak) { mark(1, 'similar', c.weak); nWeak++; }
      inSrc.forEach((rs, si) => {
        if (rs.length < 2) return;
        const t = rs[0].f.time;
        mark(1, 'dup', 'पीला: ' + sources[si].label + ' में यह ' + who + ' ' + rs.length + ' बार है' + (t ? ' — सबसे नया जवाब (' + rs[0].row[sources[si].head.findIndex(h => h.key === 'time')] + ') माना' : ''));
        nDup++;
      });
      let differs = false;
      cols.forEach((col, ci) => {
        const vals = col.has.filter(([si]) => inSrc[si].length).map(([si, hi]) => ({ si, raw: inSrc[si][0].row[hi] || '', n: norm(col.key.startsWith('key:') ? '' : col.key, inSrc[si][0].row[hi] || '') }));
        const filled = vals.filter(v => v.n);
        let shown = filled.length ? filled[0].raw : (vals[0] ? vals[0].raw : '');
        if (col.key === 'time') { // when each form was filled: shown, never a "difference"
          row.push(filled.length > 1 ? filled.map(v => sources[v.si].label + ': ' + v.raw).join(' | ') : shown);
          return;
        }
        if (filled.length > 1 && filled.some(v => !same(col.key, v.n, filled[0].n))) {
          shown = filled.map(v => sources[v.si].label + ': ' + v.raw).join(' | ');
          // three or more files: what most of them say is probably right
          const votes = {};
          filled.forEach(v => { votes[v.n] = (votes[v.n] || []).concat(v); });
          const top = Object.values(votes).sort((a, b) => b.length - a.length);
          const hint = top[0].length >= 2 && top[0].length > (top[1] || []).length ? ' — शायद सही: "' + top[0][0].raw + '" (' + top[0].map(v => sources[v.si].label).join(', ') + ' में एक जैसा)' : '';
          mark(ci + 2, 'diff', 'नीला: अलग' + hint);
          differs = true;
        } else if (filled.length && filled.length < vals.length) {
          const empty = vals.filter(v => !v.n).map(v => sources[v.si].label).join(', ');
          mark(ci + 2, 'diff', 'नीला: ' + empty + ' में खाली');
          differs = true;
        }
        row.push(shown);
      });
      if (differs) nDiff++;
      const rank = Object.values(issues).reduce((m, [k]) => Math.max(m, { diff: 4, similar: 3, only: 2, dup: 1 }[k] || 0), 0);
      out.push({ row, issues, rank, cls: +(c.feat.class || 99) || 99, name: c.feat.name || '' });
    });
    out.sort((a, b) => b.rank - a.rank || a.cls - b.cls || a.name.localeCompare(b.name));
    // a column with one value for everyone (or none) says nothing about a child: listed once above
    const sameForAll = [];
    const keepCol = head.map((h, c) => {
      if (c < 2 || out.some(o => o.issues[c])) return true;
      const vals = new Set(out.map(o => o.row[c]).filter(Boolean));
      if (vals.size === 1) sameForAll.push([h, [...vals][0]]);
      return vals.size > 1;
    });
    const newIndex = [];
    let ni = 0;
    keepCol.forEach((k, c) => { newIndex[c] = k ? ni++ : -1; });
    const rows = [], cells = [];
    out.forEach((o, r) => {
      o.row[0] = String(r + 1);
      rows.push(o.row.filter((_, c) => keepCol[c]));
      Object.keys(o.issues).forEach(c => cells.push([r, newIndex[+c], o.issues[c][0], o.issues[c][1]]));
    });
    const finalHead = head.filter((_, c) => keepCol[c]);
    const finalKeys = ['#', 'found'].concat(cols.map(c => c.key)).filter((_, c) => keepCol[c]);
    // the PDF: the known fields first, at most 15 columns (Excel has them all)
    const prio = c => c < 2 ? -1 : ORDER.indexOf(finalKeys[c]) < 0 ? 50 + c : ORDER.indexOf(finalKeys[c]);
    const printCols = finalHead.map((_, c) => c).sort((a, b) => prio(a) - prio(b)).slice(0, 15).sort((a, b) => a - b);

    const info = [
      ['Files / फ़ाइलें', files.length + ' (' + sources.map(s => s.label + ' ' + s.files.size).join(', ') + ')'],
    ];
    sources.forEach(s => info.push([s.label + ' में पंक्तियाँ', String(s.rows.length)]));
    info.push(['कुल ' + whoMany + ' / ' + (teachers ? 'Teachers' : 'Children'), String(rows.length)]);
    if (multi) {
      info.push(['सब में मिले / Found in all', String(nBoth)]);
      info.push(['अंतर वाले ' + whoMany + ' / With differences', String(nDiff)]);
      sources.forEach((s, si) => { if (only[si] || sources.length === 2) info.push(['सिर्फ़ ' + s.label + ' में', String(only[si])]); });
      sources.forEach((s, si) => { if (s.form) info.push([s.label + ': form नहीं भरा', String(missing[si])]); });
      info.push(['मिलान पक्का नहीं / Check the match', String(nWeak)]);
    }
    info.push(['एक file / portal में दो बार / Twice in one file', String(nDup)]);
    sameForAll.forEach(([h, v]) => info.push([h + ' (सभी के लिए)', v.length > 60 ? v.slice(0, 57) + '...' : v]));
    info.push(['एक जैसी पंक्ति दोबारा थी, छोड़ी / Same row repeated, skipped', String(sources.reduce((a, s) => a + s.skipped, 0))]);
    return {
      title: multi ? sources.map(s => s.label).join(' + ') + ' मिलान' : sources[0].label + ' (सब files एक साथ)',
      site: '',
      head: finalHead, rows, partial: '', printCols,
      printNote: printCols.length < finalHead.length ? 'PDF में जगह कम है; ये कॉलम सिर्फ़ Excel में: ' + finalHead.filter((_, c) => !printCols.includes(c)).join(', ') : '',
      info,
      legend: [['diff', 'नीला: portal में अलग या एक में खाली'], ['only', 'धूसर: सिर्फ़ एक portal में'], ['similar', 'नारंगी: मिलान पक्का नहीं'], ['dup', 'पीला: एक file / portal में दो बार']],
      check: { cells, counts: { diff: nDiff, only: only.reduce((a, b) => a + b, 0), weak: nWeak, dup: nDup, both: nBoth } },
      sources: sources.map(s => s.label),
    };
  };

  function jaccard(a, b) {
    let both = 0;
    a.forEach(x => { if (b.has(x)) both++; });
    return both / (a.size + b.size - both || 1);
  }
  root.RoninMerge = M;
  if (typeof module !== 'undefined') module.exports = M;
})(typeof window !== 'undefined' ? window : globalThis);
