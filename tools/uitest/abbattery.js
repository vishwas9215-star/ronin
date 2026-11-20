// NODE_PATH=$(npm root -g) node tools/uitest/abbattery.js — battery: Ronin's ad layer must not wake a quiet page
// again and again (3.14: no 1.5 s page walks for ever), yet still hides an "AD" box that arrives later.
const { chromium } = require('playwright');
const fs = require('fs'), path = require('path');
const src = fs.readFileSync(path.join(__dirname, '../../cmd/ronin/ui/inject_adblock.js'), 'utf8');
const count = `window.__fired=0;(function(){var si=window.setInterval,st=window.setTimeout;
  window.setInterval=function(f,ms){return si(function(){window.__fired++;f()},ms)};
  window.setTimeout=function(f,ms){return st(function(){window.__fired++;f()},ms)};})();`;
(async () => {
  const b = await chromium.launch(); let fail = 0; const bad = m => { console.log('FAIL', m); fail++; };
  const p = await b.newPage();
  await p.route('**/*', r => r.fulfill({ contentType: 'text/html', body: '<body><h1>News</h1><p>Quiet page, no video.</p><div id=box></div></body>' }));
  await p.addInitScript(count); await p.addInitScript(src);
  await p.goto('https://news.example.in/');
  await p.waitForTimeout(800);
  const before = await p.evaluate(() => window.__fired);
  await p.waitForTimeout(4000);
  const idle = (await p.evaluate(() => window.__fired)) - before;
  if (idle > 1) bad('quiet page woke ' + idle + ' times in 4 s');
  // an advert box that arrives later is still hidden
  await p.evaluate(() => { document.getElementById('box').innerHTML = '<div id=promo><a href="https://x.example/">Win a phone</a> <sup>AD</sup></div>'; });
  await p.waitForTimeout(2200);
  if (await p.evaluate(() => document.getElementById('promo').offsetHeight > 0)) bad('late AD box not hidden');
  await b.close();
  console.log(fail ? fail + ' problem(s)' : 'ad layer battery rules OK');
  process.exit(fail ? 1 : 0);
})();
