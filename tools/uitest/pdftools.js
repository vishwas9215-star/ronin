// NODE_PATH=$(npm root -g) node tools/uitest/pdftools.js [outdir] — every PDF / photo tool, with
// made-up files (python3 tools/uitest/make_tool_fixtures.py /tmp/toolfx makes them).
const { chromium } = require('playwright'); const fs = require('fs'); const path = require('path');
(async () => {
  const out = process.argv[2] || '/tmp/ui', fx = '/tmp/toolfx', dir = path.join(__dirname, '../../cmd/ronin/ui/tools');
  fs.mkdirSync(out, { recursive: true });
  const b = await chromium.launch(); let fail = 0; const bad = m => { console.log('FAIL', m); fail++; };
  const p = await b.newPage({ viewport: { width: 820, height: 1100 } });
  const errs = []; p.on('pageerror', e => errs.push(e.message));
  await p.route('https://ronin.home/tools/**', r => {
    const f = path.join(dir, new URL(r.request().url()).pathname.replace('/tools/', '') || 'index.html');
    const file = fs.existsSync(f) && fs.statSync(f).isFile() ? f : path.join(dir, 'index.html');
    if (/\/tools\/rules\.js$/.test(r.request().url())) return r.fulfill({ contentType: 'text/javascript', body: 'window.__roninScamRules=' + fs.readFileSync(path.join(__dirname, '../../internal/safelinks/scamrules.json'), 'utf8') + ';window.__roninLinkLists=' + fs.readFileSync(path.join(__dirname, '../../internal/safelinks/lists.json'), 'utf8') + ';' });
    r.fulfill({ body: fs.readFileSync(file), contentType: /\.m?js$/.test(file) ? 'text/javascript' : 'text/html' });
  });
  async function tool(id) { await p.goto('https://ronin.home/tools/?t=' + Date.now() + '#' + id); await p.waitForSelector('main h1'); }
  async function choose(files, label) {
    const [fc] = await Promise.all([p.waitForEvent('filechooser'), p.click(label ? 'button.big:has-text("' + label + '")' : 'button.big >> nth=0')]);
    await fc.setFiles(files.map(f => path.join(fx, f)));
    await p.waitForTimeout(400);
  }
  async function run(timeout) {
    await p.evaluate(() => { window.__lastSaved = null; });
    await p.click('button.big.go');
    await p.waitForFunction(() => { const m = document.getElementById('msg'); return m && /✔|⚠/.test(m.textContent) && !/⏳/.test(document.querySelector('button.big.go').textContent); }, null, { timeout: timeout || 60000 });
    return p.evaluate(async () => {
      const s = window.__lastSaved, m = document.getElementById('msg').textContent;
      if (!s) return { msg: m };
      let pages = 0;
      if (s.mime === 'application/pdf') pages = (await window.PDFLib.PDFDocument.load(s.bytes)).getPageCount();
      let w = 0, h = 0;
      if (/image/.test(s.mime)) { const im = new Image(); im.src = URL.createObjectURL(new Blob([s.bytes])); await im.decode(); w = im.naturalWidth; h = im.naturalHeight; }
      return { name: s.name, size: s.bytes.length, pages, w, h, msg: m };
    });
  }
  const chip = t => p.click('button.chip:has-text("' + t + '")');
  let r;

  await p.goto('https://ronin.home/tools/'); await p.waitForSelector('.tool');
  await p.screenshot({ path: path.join(out, 'tools-home.png'), fullPage: true });
  await p.setViewportSize({ width: 1280, height: 700 }); await p.screenshot({ path: path.join(out, 'tools-home-wide.png') });
  await p.setViewportSize({ width: 820, height: 1100 });

  await tool('pdfsmall'); await choose(['report.pdf']); await chip('500 KB'); r = await run(120000);
  if (!r.size || r.size > 500 * 1024 || r.pages !== 6) bad('PDF smaller: ' + JSON.stringify(r));
  if (!/अक्षर पहले जैसे/.test(r.msg)) bad('scanned PDF should keep its writing: ' + r.msg);
  await tool('pdfsmall'); await choose(['report.pdf']); await chip('20 KB'); r = await run(120000);
  if (r.size || !/पढ़ने लायक नहीं बन सकती.*लगभग \d+ KB/.test(r.msg)) bad('too small should be refused with the readable size: ' + JSON.stringify(r));
  await tool('pdfsmall'); await choose(['textonly.pdf']); await chip('200 KB'); r = await run(120000);
  if (!r.size || r.size > 200 * 1024 || !/photo जैसे/.test(r.msg)) bad('text PDF to pictures: ' + JSON.stringify(r));

  await tool('merge'); await choose(['report.pdf', 'photo1.jpg', 'report.pdf']); r = await run();
  if (r.pages !== 13) bad('merge: ' + JSON.stringify(r));

  await tool('pages'); await choose(['report.pdf']);
  await p.waitForFunction(() => document.querySelectorAll('.th').length === 6, null, { timeout: 30000 });
  await p.click('.th >> nth=1 >> button[title="हटाओ"]');            // remove page 2
  await p.click('.th >> nth=4 >> button[title="पहले"]');             // page 5 before page 4
  await p.click('.th >> nth=0 >> button[title="घुमाओ"]');           // turn page 1
  await p.screenshot({ path: path.join(out, 'tools-pages.png'), fullPage: true });
  r = await run();
  if (r.pages !== 5) bad('pages: ' + JSON.stringify(r));

  await tool('numbers'); await choose(['report.pdf']); await chip('1 / 10'); r = await run();
  if (r.pages !== 6) bad('numbers: ' + JSON.stringify(r));

  await tool('unlock'); await choose(['locked.pdf']); await p.fill('input[type=password]', 'WRONG'); r = await run();
  if (!/ग़लत/.test(r.msg)) bad('wrong password not told: ' + r.msg);
  await p.fill('input[type=password]', 'RAME1985'); r = await run(120000);
  if (r.pages !== 6) bad('unlock: ' + JSON.stringify(r));
  await tool('pdfsmall'); await choose(['locked.pdf']); r = await run();
  if (!/password/.test(r.msg)) bad('locked PDF not explained: ' + r.msg);

  await tool('pdfsign'); await choose(['report.pdf']);
  await p.click('button.chip:has-text("sign की photo")').catch(() => {});
  const [fc] = await Promise.all([p.waitForEvent('filechooser'), p.click('button.chip:has-text("sign की photo")')]).catch(() => [null]);
  if (fc) await fc.setFiles(path.join(fx, 'sign.jpg'));
  await p.waitForSelector('img.signprev', { timeout: 10000 }).catch(() => bad('signature photo not taken'));
  await p.fill('input[type=text]', 'प्रधानाध्यापक'); await chip('आज की तारीख भी');
  await p.waitForTimeout(800);
  await p.click('.place canvas', { position: { x: 300, y: 600 } }); await p.waitForTimeout(500);
  await p.screenshot({ path: path.join(out, 'tools-sign.png'), fullPage: true });
  r = await run();
  if (r.pages !== 6) bad('pdf sign: ' + JSON.stringify(r));

  await tool('photosign'); await choose(['photo2.jpg']); await p.waitForTimeout(600);
  if (!(await p.$('img.signprev'))) bad('saved signature not reused');
  r = await run();
  if (!r.w || r.w !== 1600) bad('photo sign: ' + JSON.stringify(r));

  await tool('topdf'); await choose(['photo1.jpg', 'photo2.jpg', 'photo3.png']); r = await run();
  if (r.pages !== 3) bad('photos to pdf: ' + JSON.stringify(r));

  await tool('photosmall'); await choose(['photo1.jpg']); await chip('20 KB'); r = await run();
  if (!r.size || r.size > 20 * 1024) bad('photo 20 KB: ' + JSON.stringify(r));
  await tool('photosmall'); await choose(['photo2.jpg']); await chip('100 KB'); r = await run();
  if (!r.size || r.size > 100 * 1024 || r.size < 50 * 1024) bad('photo 100 KB (should use the room): ' + JSON.stringify(r));

  await tool('collage'); await choose(['photo1.jpg', 'photo2.jpg', 'photo3.png']); r = await run();
  if (!r.w || r.h !== 1024) bad('collage: ' + JSON.stringify(r));
  await tool('collage'); await choose(['photo1.jpg', 'photo2.jpg', 'photo3.png']); await chip('2 × 2'); r = await run();
  if (r.w !== r.h) bad('collage grid: ' + JSON.stringify(r));

  await tool('whatsapp'); await choose(['photo1.jpg']); r = await run();
  if (r.w !== 1080 || r.h !== 1080) bad('DP: ' + JSON.stringify(r));
  await tool('whatsapp'); await choose(['photo1.jpg']); await chip('Status'); r = await run();
  if (r.w !== 1080 || r.h !== 1920) bad('Status: ' + JSON.stringify(r));

  // Documents to PDF: photos + a PDF, scan look, under 1 MB
  await tool('docpdf'); await choose(['photo1.jpg', 'photo2.jpg', 'textonly.pdf']); await chip('Scan जैसा साफ़'); await chip('1 MB'); r = await run(180000);
  if (!r.size || r.size > 1024 * 1024 || r.pages !== 5 || !/^photo1_docs\.pdf$/.test(r.name)) bad('documents to PDF: ' + JSON.stringify(r));

  // Edit Photo: crop to passport size
  await tool('photoedit'); await choose(['photo1.jpg']); await p.waitForTimeout(500);
  await chip('Passport 413×531'); r = await run();
  if (r.w !== 413 || r.h !== 531 || !/_edited\.jpg$/.test(r.name)) bad('edit photo passport: ' + JSON.stringify(r));
  await tool('photoedit'); await choose(['photo2.jpg']); await p.waitForTimeout(500);
  const box = await p.$('.crop canvas'), bb = await box.boundingBox();
  await p.mouse.move(bb.x + bb.width * 0.2, bb.y + bb.height * 0.2); await p.mouse.down(); await p.mouse.move(bb.x + bb.width * 0.6, bb.y + bb.height * 0.5, { steps: 5 }); await p.mouse.up();
  await chip('PNG'); r = await run();
  if (!r.w || r.w > 1600 * 0.5 || !/\.png$/.test(r.name)) bad('hand crop: ' + JSON.stringify(r));

  // Edit PDF: tap a typed word, change it
  await tool('pdfedit'); await choose(['textonly.pdf']); await p.waitForTimeout(1500);
  const ed = await (await p.$('.ed-wrap canvas')).boundingBox();
  await p.mouse.click(ed.x + ed.width * 0.15, ed.y + ed.height * 38 / 842); await p.waitForTimeout(400);
  const before = await p.$eval('textarea', t => t.value).catch(() => '');
  if (!/Line 0 of page 1/.test(before)) bad('tap on a word did not pick it: ' + before);
  await p.fill('textarea', 'Line 0 CHANGED 95'); await p.click('button.chip:has-text("✔ ठीक")');
  r = await run();
  if (r.pages !== 3 || !/_edited\.pdf$/.test(r.name)) bad('edit pdf: ' + JSON.stringify(r));
  await p.screenshot({ path: path.join(out, 'tools-edit.png'), fullPage: true });

  // Fill Form: photo of a sheet, writing, a signer with a signature photo, sign block
  await tool('fillform'); await choose(['photo3.png']); await p.waitForTimeout(800);
  const fb = await (await p.$('.ed-wrap canvas')).boundingBox();
  await p.mouse.click(fb.x + fb.width * 0.3, fb.y + fb.height * 0.3); await p.waitForTimeout(300);
  await p.fill('textarea', 'कुल छात्र: 53'); await p.click('button.chip:has-text("✔ ठीक")');
  await p.click('button.chip:has-text("नया व्यक्ति")');
  await p.fill('input[placeholder^="नाम"]', 'Test Kumar'); await p.fill('input[placeholder^="पद"]', 'खंड शिक्षा अधिकारी');
  const [sfc] = await Promise.all([p.waitForEvent('filechooser'), p.click('button.chip:has-text("sign की photo")')]); await sfc.setFiles(path.join(fx, 'sign.jpg'));
  await p.waitForTimeout(500); await p.click('button.chip:has-text("💾 Save")'); await p.waitForTimeout(300);
  const fb2 = await (await p.$('.ed-wrap canvas')).boundingBox(); // the page may have scrolled
  await p.mouse.click(fb2.x + fb2.width * 0.7, fb2.y + fb2.height * 0.85); await p.waitForTimeout(500);
  if (!/बड़ा/.test(await p.evaluate(() => document.querySelector('.ed-wrap').nextSibling.textContent))) bad('sign block not placed');
  await p.screenshot({ path: path.join(out, 'tools-fill.png'), fullPage: true });
  r = await run();
  if (r.pages !== 1 || !/_filled\.pdf$/.test(r.name)) bad('fill form: ' + JSON.stringify(r));

  // Convert
  await tool('convert'); await choose(['textonly.pdf'].map(x => x)).catch(() => {});
  await chip('PDF → Excel'); await choose(['textonly.pdf']); r = await run();
  if (!/\.xlsx$/.test(r.name || '') || !r.size) bad('pdf to excel: ' + JSON.stringify(r));
  await chip('PDF → Photos'); await choose(['textonly.pdf']); r = await run();
  if (!/page3\.jpg$/.test(r.name || '')) bad('pdf to photos: ' + JSON.stringify(r));
  await chip('Word (.docx) → PDF'); await choose(['letter.docx']); r = await run();
  if (r.pages !== 1) bad('word to pdf: ' + JSON.stringify(r));
  await chip('Excel / CSV → PDF'); await p.waitForTimeout(200);
  const [xfc] = await Promise.all([p.waitForEvent('filechooser'), p.click('button.big >> nth=0')]); await xfc.setFiles(path.join(__dirname, 'fixtures', 'UDISE_student_list.xlsx')); await p.waitForTimeout(400);
  r = await run();
  if (!r.pages || !/^UDISE_student_list\.pdf$/.test(r.name)) bad('excel to pdf: ' + JSON.stringify(r));

  // Date Calculator
  await tool('datecalc');
  const answer = () => p.$eval('.res', e => e.textContent).catch(() => '');
  await p.fill('input[type=date] >> nth=0', '2017-01-25'); await p.waitForTimeout(150);
  if (!/वर्ष/.test(await answer())) bad('age: ' + await answer());
  await chip('प्रवेश आयु'); await p.fill('input[type=date] >> nth=0', '2020-04-02'); await chip('दूसरी तारीख़');
  await p.fill('input[type=date] >> nth=1', '2026-04-01'); await p.waitForTimeout(150);
  if (!/1 दिन कम/.test(await answer())) bad('admission: ' + await answer());
  await chip('सेवानिवृत्ति'); await p.fill('input[type=date] >> nth=0', '1970-07-15'); await p.waitForTimeout(150);
  if (!/31\/03\/2033/.test(await answer())) bad('retirement: ' + await answer());
  await chip('उम्र का अनुमान'); await chip('दूसरी तारीख़'); await p.fill('input[type=date] >> nth=0', '2027-03-01');
  await p.fill('input[type=number] >> nth=0', '40'); await p.fill('input[type=number] >> nth=1', '22'); await p.waitForTimeout(150);
  if (!/लगभग 62 वर्ष/.test(await answer())) bad('estimate: ' + await answer());
  await chip('दिन गिनो'); await chip('इतने दिन बाद');
  await p.fill('input[type=date] >> nth=0', '2026-11-15'); await p.fill('input[type=number] >> nth=0', '180'); await p.waitForTimeout(150);
  if (!/14\/05\/2027/.test(await answer()) || !/13\/05\/2027/.test(await answer())) bad('days after: ' + await answer());
  await p.screenshot({ path: path.join(out, 'tools-date.png'), fullPage: true });
  await tool('words'); await p.fill('input[type=text]', '125000');
  await p.waitForTimeout(150);
  const w = await p.$$eval('.res big', a => a.map(x => x.textContent).join(' | '));
  if (!/एक लाख पच्चीस हज़ार रुपये मात्र/.test(w) || !/One Lakh Twenty Five Thousand/.test(w)) bad('amount in words: ' + w);

  // a turned page (/Rotate 90): the signature lands where tapped, upright
  await tool('pdfsign');
  const rot = await p.evaluate(async () => {
    const { PDFDocument, degrees } = window.PDFLib;
    const d = await PDFDocument.create(); d.addPage([595, 842]).setRotation(degrees(90));
    return Array.from(await d.save());
  });
  const [fr] = await Promise.all([p.waitForEvent('filechooser'), p.click('button.big >> nth=0')]);
  await fr.setFiles({ name: 'rot.pdf', mimeType: 'application/pdf', buffer: Buffer.from(rot) });
  await p.waitForTimeout(800);
  await p.click('button.chip:has-text("नया sign")').catch(() => {});
  await p.fill('input[type=text]', 'TESTSIGN'); await p.waitForTimeout(400);
  r = await run();
  const ink = await p.evaluate(async () => {
    const pdfjs = await import('/tools/lib/pdf.min.mjs');
    pdfjs.GlobalWorkerOptions.workerSrc = '/tools/lib/pdf.worker.min.mjs';
    const doc = await pdfjs.getDocument({ data: window.__lastSaved.bytes.slice() }).promise;
    const page = await doc.getPage(1), vp = page.getViewport({ scale: 0.5 });
    const c = document.createElement('canvas'); c.width = vp.width; c.height = vp.height; const ctx = c.getContext('2d');
    ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, c.width, c.height);
    await page.render({ canvasContext: ctx, viewport: vp }).promise;
    const d = ctx.getImageData(0, 0, c.width, c.height).data; let x0 = 1e9, y0 = 1e9, x1 = -1, y1 = -1;
    for (let y = 0; y < c.height; y++) for (let x = 0; x < c.width; x++) { const i = (y * c.width + x) * 4; if (d[i] < 128) { x0 = Math.min(x0, x); y0 = Math.min(y0, y); x1 = Math.max(x1, x); y1 = Math.max(y1, y); } }
    return { cx: (x0 + x1) / 2 / c.width, cy: (y0 + y1) / 2 / c.height, wide: x1 - x0 > y1 - y0 };
  });
  if (Math.abs(ink.cx - 0.78) > 0.05 || Math.abs(ink.cy - 0.88) > 0.05 || !ink.wide) bad('turned page sign: ' + JSON.stringify(ink));

  // the hand-over to Ronin, as Ronin does it: read the pieces, say "saved"
  await p.route('https://ronin.data/**', r => r.fulfill({ status: 204, body: '' }));
  await p.goto('https://ronin.home/tools/?k=TESTKEY'); await p.waitForSelector('.tool');
  await p.evaluate(() => { window.__selfTest = ''; window.__roninToolsSelfTest().then(r => { window.__selfTest = r; }, e => { window.__selfTest = 'error ' + e.message; }); });
  await p.waitForFunction(() => window.__roninSave && window.__roninSave.state === 'waiting', null, { timeout: 30000 });
  const got = await p.evaluate(() => { const s = window.__roninSave; let b = ''; for (let i = 0; i < s.n; i++) b += s.part(i); s.state = 'saved'; return { name: s.name, len: atob(b).length }; });
  await p.waitForFunction(() => window.__selfTest, null, { timeout: 30000 });
  const st = await p.evaluate(() => window.__selfTest);
  if (st !== 'ok 2' || got.name !== 'Ronin_tools_selftest.pdf' || got.len < 1000) bad('hand-over: ' + st + ' ' + JSON.stringify(got));

  if (errs.length) bad('page errors: ' + errs.join(' | '));
  console.log(fail ? fail + ' FAILED' : 'pdf / photo tools OK');
  await b.close(); process.exit(fail ? 1 : 0);
})();
