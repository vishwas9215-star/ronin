// NODE_PATH=$(npm root -g) node tools/uitest/playerads/t.js — checks the "advert inside a video player" skipper in a real browser.
const { chromium } = require('playwright');
const http = require('http'), fs = require('fs'), path = require('path');
const dir = __dirname;
const srv = http.createServer((q, r) => { const f = path.join(dir, q.url.split('?')[0]); if (!fs.existsSync(f)) { r.writeHead(404); return r.end(); }
  const size = fs.statSync(f).size, type = f.endsWith('.webm') ? 'video/webm' : 'text/html';
  const m = /bytes=(\d+)-(\d*)/.exec(q.headers.range || '');
  if (m) { const st = +m[1], en = m[2] ? +m[2] : size - 1; r.writeHead(206, { 'Content-Type': type, 'Accept-Ranges': 'bytes', 'Content-Range': `bytes ${st}-${en}/${size}`, 'Content-Length': en - st + 1 }); return fs.createReadStream(f, { start: st, end: en }).pipe(r); }
  r.writeHead(200, { 'Content-Type': type, 'Accept-Ranges': 'bytes', 'Content-Length': size }); fs.createReadStream(f).pipe(r); }).listen(8765);
(async () => {
  const b = await chromium.launch({ args: ['--autoplay-policy=no-user-gesture-required'] });
  const p = await b.newPage();
  await p.addInitScript({ path: path.join(dir, '../../../cmd/ronin/ui/inject_adblock.js') });
  await p.goto('http://localhost:8765/page.html');
  await p.waitForFunction(() => document.getElementById('va').duration > 0);
  await p.evaluate(() => { const v = document.getElementById('va'); v.muted = false; document.getElementById('vb').muted = false; });
  await p.waitForTimeout(1000);
  let s = await p.evaluate(() => { const a = va, b = vb; return { aRate: a.playbackRate, aMuted: a.muted, aT: a.currentTime, d: a.duration, bRate: b.playbackRate, bMuted: b.muted }; });
  console.log('during ad', s);
  let fail = 0;
  if (s.aRate !== 16 || !s.aMuted || s.aT < s.d - 1) { console.log('FAIL ad not handled'); fail++; }
  if (s.bRate !== 1 || s.bMuted) { console.log('FAIL normal player touched'); fail++; }
  await p.evaluate(() => { lab.textContent = 'Skip ad ›'; lab.onclick = () => window.clicks++; });
  await p.waitForTimeout(700);
  if (await p.evaluate(() => window.clicks) < 1) { console.log('FAIL skip not clicked'); fail++; }
  await p.evaluate(() => { document.querySelector('.ui').remove(); });
  await p.waitForTimeout(700);
  s = await p.evaluate(() => ({ r: va.playbackRate, m: va.muted }));
  console.log('after ad', s);
  if (s.r !== 1 || s.m) { console.log('FAIL not restored'); fail++; }
  await p.waitForTimeout(1800);
  const vis = await p.evaluate(() => ['promo', 'left', 'foot', 'news'].map(id => [id, getComputedStyle(document.getElementById(id).querySelector('*') ? document.getElementById(id) : document.getElementById(id)).display, document.getElementById(id).offsetHeight > 0]));
  console.log(JSON.stringify(vis));
  const h = Object.fromEntries(vis.map(x => [x[0], x[2]]));
  if (h.promo) { console.log('FAIL AD promo visible'); fail++; }
  if (h.left) { console.log('FAIL Advertising box visible'); fail++; }
  if (!h.foot || !h.news) { console.log('FAIL normal content hidden'); fail++; }
  console.log(fail ? fail + ' problem(s)' : 'generic player ad rules OK');
  await b.close(); srv.close(); process.exit(fail ? 1 : 0);
})();
