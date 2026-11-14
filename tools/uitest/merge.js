// NODE_PATH=$(npm root -g) node tools/uitest/merge.js [outdir] — "डेटा मिलाओ" with made-up files
// (tools/uitest/make_merge_fixtures.py): Prerna + UDISE+ children, and teachers' forms.
const { chromium } = require('playwright'); const fs = require('fs'); const path = require('path');
(async () => {
  const fx = path.join(__dirname, 'fixtures'), out = process.argv[2] || '/tmp/ui', dir = path.join(__dirname, '../../cmd/ronin/ui/tools');
  fs.mkdirSync(out, { recursive: true });
  const b = await chromium.launch(); let fail = 0; const bad = m => { console.log('FAIL', m); fail++; };
  async function run(names, shot) {
    const p = await b.newPage({ viewport: { width: 800, height: 1000 } });
    const errs = []; p.on('pageerror', e => errs.push(e.message));
    await p.route('https://ronin.home/tools/**', r => {
      const f = path.join(dir, new URL(r.request().url()).pathname.replace('/tools/', '') || 'index.html');
      const file = fs.existsSync(f) && fs.statSync(f).isFile() ? f : path.join(dir, 'index.html');
      r.fulfill({ body: fs.readFileSync(file), contentType: /\.m?js$/.test(file) ? 'text/javascript' : 'text/html' });
    });
    await p.goto('https://ronin.home/tools/#datamatch');
    await p.waitForSelector('button.big');
    const [fc] = await Promise.all([p.waitForEvent('filechooser'), p.click('button.big >> nth=0')]);
    await fc.setFiles(names.map(n => path.join(fx, n)));
    await p.waitForFunction(() => !document.querySelector('button.big.go').disabled, null, { timeout: 15000 });
    await p.click('button.big.go');
    await p.waitForFunction(() => window.__lastMerge, null, { timeout: 15000 });
    const r = await p.evaluate(() => JSON.parse(window.__roninMerge.result()));
    if (shot) await p.screenshot({ path: path.join(out, shot), fullPage: true });
    if (errs.length) bad('page error: ' + errs.join('; '));
    await p.close(); return r;
  }
  const col = (r, re) => r.head.findIndex(h => re.test(h));
  const rowOf = (r, re) => r.rows.findIndex(x => re.test(x[2].replace(/^[^:]*: /, '')));
  const notes = (r, i) => r.check.cells.filter(c => c[0] === i).map(c => c[2] + ':' + c[3]).join(' ; ');

  // children
  let r = await run(['Teacher_Level 2026-11-14.xlsx', 'prerna_class_list.xlsx', 'UDISE_student_list.xlsx'], 'merge-children.png');
  fs.writeFileSync(path.join(out, 'merge-children.json'), JSON.stringify(r));
  const info = Object.fromEntries(r.info);
  if (r.sources.join() !== 'Prerna,UDISE+') bad('sources: ' + r.sources);
  if (r.rows.length !== 15) bad('children: ' + r.rows.length + ' (want 15)');
  if (info['एक जैसी पंक्ति दोबारा थी, छोड़ी / Same row repeated, skipped'] !== '4') bad('repeated rows skipped: ' + JSON.stringify(info));
  const expect = [
    [/^Anika/i, null], [/^Ankush/i, null], [/^Aman/i, null], [/^Amar/i, null], [/^Naitri/i, null], [/^Kanishk/i, null],
    [/^Ruchi/i, /नाम diff/], [/^Divyanshi/i, /माता का नाम diff/], [/^Kavya/i, /जन्मतिथि diff/], [/^Madhu/i, /लिंग diff/],
    [/^Mayank/i, /diff:नीला: UDISE\+ में खाली/], [/^Bhumika/i, /dup:पीला: UDISE\+ में यह बच्चा 2 बार/],
    [/^Arpita/i, /only:धूसर: सिर्फ़ Prerna में/], [/^Jesika/i, /only:धूसर: सिर्फ़ Prerna में/], [/^PRACHI/i, /only:धूसर: सिर्फ़ UDISE\+ में/],
  ];
  for (const [who, want] of expect) {
    const i = rowOf(r, who);
    if (i < 0) { bad('missing ' + who); continue; }
    const n = r.check.cells.filter(c => c[0] === i).map(c => r.head[c[1]] + ' ' + c[2] + ':' + c[3]).join(' ; ');
    if (!want && n) bad(who + ' should have no mark: ' + n);
    if (want && !want.test(n)) bad(who + ' want ' + want + ' got: ' + n);
  }
  const ai = rowOf(r, /^Aman/i), am = rowOf(r, /^Amar/i);
  if (ai >= 0 && !/2016/.test(r.rows[ai][col(r, /^जन्मतिथि/)])) bad('Aman mixed up with his brother');
  if (ai >= 0 && !/PEN/.test(r.head.join('|'))) bad('UDISE+ columns missing: ' + r.head);
  if (r.rows[0][1] === '' || r.check.cells.some(c => c[2] === 'spell')) bad('odd marks');
  if (/Sr\.No|S\.No/.test(r.head.join('|'))) bad('serial columns kept: ' + r.head);

  // teachers
  r = await run(['Seniority list.xlsx', 'Seniority Form old (Responses).xlsx', 'Seniority Form new (Responses).xlsx'], 'merge-teachers.png');
  fs.writeFileSync(path.join(out, 'merge-teachers.json'), JSON.stringify(r));
  const ti = Object.fromEntries(r.info);
  if (r.sources.length !== 3) bad('teacher sources: ' + r.sources);
  if (r.rows.length !== 6) bad('teachers: ' + r.rows.length);
  if (ti['Seniority Form new: form नहीं भरा'] !== '2' || ti['Seniority Form old: form नहीं भरा'] !== '1') bad('not filled: ' + JSON.stringify(ti));
  let i = rowOf(r, /^Anil/);
  if (!/शायद सही: "10\/01\/2009"/.test(notes(r, i))) bad('Anil majority: ' + notes(r, i));
  i = rowOf(r, /^Sunita/);
  if (!/2 बार है — सबसे नया जवाब/.test(notes(r, i)) || /जन्म|Date of Birth[^;]*diff/.test(notes(r, i)) && /1981/.test(r.rows[i].join('|'))) bad('Sunita newest answer: ' + notes(r, i) + ' ' + r.rows[i].join('|'));
  i = rowOf(r, /^Pooja/);
  if (!/form नहीं भरा/.test(notes(r, i))) bad('Pooja not filled: ' + notes(r, i));
  console.log(fail ? fail + ' FAILED' : 'merge OK');
  await b.close(); process.exit(fail ? 1 : 0);
})();
