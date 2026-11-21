// NODE_PATH=$(npm root -g) node tools/uitest/tvtidy/t.js — Ronin TV play screen (owner, 21 Nov 2026): no Like / Dislike /
// Comments / Ask / Save / Subscribe / Description; CC, settings, play controls and the video tiles stay; best quality.
const { chromium } = require('playwright');
const fs = require('fs'), path = require('path');
const kt = fs.readFileSync(path.join(__dirname, '../../../android/app/src/main/java/com/ronin/browser/TvActivity.kt'), 'utf8');
const js = kt.split('NO_ADS_JS = """')[1].split('\n"""')[0];
let fail = 0; const bad = m => { console.log('FAIL', m); fail++; };
(async () => {
  const b = await chromium.launch(); const p = await b.newPage({ viewport: { width: 1280, height: 720 } });
  await p.goto('file://' + path.join(__dirname, 'page.html') + '#/watch?v=x');
  await p.addScriptTag({ content: js }); await p.evaluate(() => window.__roninTvTidy());
  const vis = await p.evaluate(() => [...document.querySelectorAll('[aria-label],.pill')].map(e => [(e.getAttribute('aria-label') || e.textContent).trim().split(' ')[0], e.getBoundingClientRect().width > 0]));
  const shown = Object.fromEntries(vis.filter(([n]) => n !== 'Like').concat(vis.filter(([n]) => n === 'Like').slice(0, 1)));
  for (const n of ['Like', 'Dislike', 'Comments', 'Ask', 'Save', 'Description', 'Subscribe']) if (shown[n]) bad(n + ' still on the play screen');
  for (const n of ['Previous', 'Pause', 'Next', 'Captions', 'Settings', 'Saaun']) if (!shown[n]) bad(n + ' was hidden');
  if (!shown['Like']) { /* ok */ } if (!vis.some(([n, v]) => n === 'Like' && v === false)) bad('Like button not found hidden');
  if (!(await p.evaluate(() => document.querySelector('.tile[aria-label^="Like a Prayer"]').getBoundingClientRect().width > 0))) bad('the song "Like a Prayer" was hidden');
  const q = await p.evaluate(() => window.asked); if (q[q.length - 1] !== 'hd1080') bad('quality not 1080p: ' + q);
  // the remote: OK on a hidden button (focus landed there) does nothing
  await p.evaluate(() => document.querySelector('[aria-label^="Like this"]').focus());
  await p.keyboard.press('Enter');
  if (await p.evaluate(() => window.liked)) bad('OK on the hidden Like button liked the video');
  // not a play screen: nothing hidden (Subscribe on a channel page stays)
  await p.evaluate(() => { location.hash = '#/channel/x'; document.querySelectorAll('[data-ronin-hidden]').forEach(e => { e.removeAttribute('data-ronin-hidden'); e.style.display = ''; }); });
  await p.evaluate(() => window.__roninTvTidy());
  if (await p.evaluate(() => !!document.querySelector('[data-ronin-hidden]'))) bad('buttons hidden outside the play screen');
  await p.goto('file://' + path.join(__dirname, 'page.html') + '#/watch?v=x'); await p.addScriptTag({ content: js }); await p.evaluate(() => window.__roninTvTidy());
  await p.screenshot({ path: '/tmp/ui/tv-play-screen.png' });
  await b.close();
  console.log(fail ? fail + ' problem(s)' : 'TV play screen OK'); process.exit(fail ? 1 : 0);
})();
