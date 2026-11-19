// "Is this a scam?" page works on its own (3.12: it used to borrow the engine from Safe Social and broke when that went).
const { chromium } = require('playwright'); const fs = require('fs'); const path = require('path');
(async () => {
  const root = path.join(__dirname, '..', '..'), dir = path.join(root, 'cmd/ronin/ui/tools');
  const rules = 'window.__roninScamRules=' + fs.readFileSync(path.join(root, 'internal/safelinks/scamrules.json'), 'utf8') + ';window.__roninLinkLists=' + fs.readFileSync(path.join(root, 'internal/safelinks/lists.json'), 'utf8') + ';';
  const b = await chromium.launch(); const p = await b.newPage(); let fail = 0, errs = [];
  p.on('pageerror', e => errs.push(e.message));
  await p.route('https://ronin.home/tools/**', r => {
    const name = new URL(r.request().url()).pathname.replace('/tools/', '') || 'index.html';
    if (name === 'rules.js') return r.fulfill({ contentType: 'text/javascript', body: rules });
    const f = path.join(dir, name);
    if (!fs.existsSync(f)) return r.fulfill({ status: 404, body: '' });
    r.fulfill({ contentType: name.endsWith('.html') ? 'text/html' : 'text/javascript', body: fs.readFileSync(f) });
  });
  await p.goto('https://ronin.home/tools/#scamcheck:' + encodeURIComponent('Mujhe urgent 10000 rupaye ki zarurat hai, hospital me hoon, is QR par bhej do'));
  await p.waitForTimeout(2500);
  const html = await p.evaluate(() => document.body.innerText);
  if (/उपलब्ध नहीं|not available/i.test(html)) { fail++; console.log('scam engine missing on the page'); }
  if (!await p.$('#verdict')) { fail++; console.log('no verdict shown'); }
  if (!(await p.evaluate(() => !!window.RoninScam))) { fail++; console.log('RoninScam not loaded'); }
  if (errs.length) { fail++; console.log('errors: ' + errs.join(' | ')); }
  // the menu's way: tools page first, then the scam check by hash (like on the phone)
  await p.goto('https://ronin.home/tools/'); await p.waitForSelector('.tool');
  await p.evaluate(() => { location.hash = 'scamcheck:' + encodeURIComponent('Congratulations! You won an iPhone. Pay 499 delivery charge to claim, send OTP'); });
  await p.waitForTimeout(1500);
  if (!await p.$('#verdict')) { fail++; console.log('hash change: no verdict; page says: ' + (await p.evaluate(() => document.body.innerText)).slice(0, 200)); }
  // tablet 19 Nov 2026: the tools page was already open and Menu → Scam check only changed #scamcheck
  await p.goto('https://ronin.home/tools/'); await p.waitForSelector('.tool');
  await p.evaluate(() => { location.hash = 'scamcheck'; }); await p.waitForTimeout(800);
  if (await p.$$eval('.tool', a => a.length) > 3 && !(await p.evaluate(() => /scam|ठगी/i.test((document.querySelector('h1,h2') || {}).innerText || '')))) { fail++; console.log('#scamcheck on an open tools page did not open Scam Check: ' + (await p.evaluate(() => document.body.innerText)).slice(0, 120)); }
  await b.close();
  console.log(fail ? fail + ' FAILED' : 'scam check page OK');
  process.exit(fail ? 1 : 0);
})();
