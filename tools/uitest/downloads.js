// NODE_PATH=$(npm root -g) node tools/uitest/downloads.js outdir — downloads panel: short names, speed, pause/resume,
// in-place update (no redraw), the small floating window, and the bookmarks list.
const { chromium } = require('playwright'); const fs = require('fs');
(async () => {
  const out = process.argv[2] || '.'; let fail = 0; const bad = m => { console.log('FAIL ' + m); fail++; };
  const page = async (panel, w, h) => {
    const html = fs.readFileSync('cmd/ronin/ui/toolbar.html', 'utf8').replace(/{{CONTAINER}}/g, 'PERSONAL').replace(/{{PANEL}}/g, panel)
      .replace('<script>', '<script>window.chrome={webview:{postMessage:m=>window.__post(m)}};</script><script>');
    const p = await b.newPage({ viewport: { width: w, height: h } }); const got = [];
    await p.exposeFunction('__post', m => got.push(JSON.parse(m))); await p.setContent(html); return { p, got };
  };
  const b = await chromium.launch();
  const jobs = pct => [
    { id: 5, kind: "file", title: "Ballerina.2023.1080p.NF.WEB-DL.Multi.AAC5.1.AV1-Example.Com.mkv", state: "downloading", percent: pct, size: "132.2 MB of 771.0 MB", speed: "6.4 MB/s", eta: "2 min", canPause: true, vpn: true },
    { id: 4, kind: "file", title: "notes.pdf", state: "paused", percent: 40, size: "2.0 MB of 5.0 MB", canPause: true },
    { id: 3, kind: "file", title: "big.zip", state: "downloading", percent: 10, size: "10 MB of 100 MB", stalled: true, canPause: true },
    { id: 2, kind: "video", title: "Some video", state: "done", percent: 100, scan: "clean" }];
  // side panel
  let { p, got } = await page('1', 400, 640);
  await p.evaluate(j => ronin.overlay({ kind: "downloads", jobs: j }), jobs(17));
  await p.screenshot({ path: out + '/dl-panel.png' });
  const nameBox = await p.evaluate(() => { const e = document.querySelector('.dlname'); return { h: e.offsetHeight, clipped: e.scrollWidth > e.clientWidth, tip: e.title.length }; });
  if (nameBox.h > 24 || !nameBox.clipped || nameBox.tip < 40) bad('long name not shortened to one line: ' + JSON.stringify(nameBox));
  const txt = await p.evaluate(() => document.body.innerText);
  for (const w of ['6.4 MB/s', '2 min left', 'Pause', 'Resume', 'Paused', 'Waiting...', 'Faster: without VPN', 'Ronin keeps trying']) if (!txt.includes(w)) bad('missing "' + w + '"');
  // in-place update keeps the same button element
  await p.evaluate(() => { window.__btn = [...document.querySelectorAll('button.link')].find(x => x.textContent === 'Pause'); });
  await p.evaluate(j => ronin.overlay({ kind: "downloads", jobs: j }), jobs(55));
  const same = await p.evaluate(() => document.body.contains(window.__btn) && document.querySelector('.dlst').textContent === '55%' && document.querySelector('.prog i').style.width === '55%');
  if (!same) bad('update redrew the list or did not change the numbers');
  await p.evaluate(() => window.__btn.click());
  await p.evaluate(() => [...document.querySelectorAll('button.link')].find(x => x.textContent === 'Resume').click());
  await p.evaluate(() => [...document.querySelectorAll('button.link')].find(x => x.textContent.startsWith('Faster')).click());
  await p.waitForTimeout(200);
  const cmds = got.map(m => m.cmd + ':' + (m.url || '')).join(',');
  if (!cmds.includes('dlpause:5') || !cmds.includes('dlresume:4') || !cmds.includes('dldirect:5')) bad('button messages: ' + cmds);
  // state change (pause) must redraw
  const j2 = jobs(55); j2[0].state = 'paused';
  await p.evaluate(j => ronin.overlay({ kind: "downloads", jobs: j }), j2);
  if (!(await p.evaluate(() => !document.body.contains(window.__btn)))) bad('state change did not redraw');
  // floating window
  ({ p, got } = await page('1', 400, 300));
  await p.evaluate(j => ronin.overlay({ kind: "downloads", float: true, jobs: j }), jobs(17).slice(0, 1));
  await p.screenshot({ path: out + '/dl-float.png' });
  const ft = await p.evaluate(() => document.body.innerText);
  if (!ft.includes('Ronin downloads') || !ft.includes('Open Ronin') || !ft.includes('Ronin is closed')) bad('float window text');
  await p.evaluate(() => [...document.querySelectorAll('button')].find(x => x.textContent === 'Open Ronin').click()); await p.waitForTimeout(100);
  if (!got.some(m => m.cmd === 'openronin')) bad('Open Ronin button');
  // bookmarks
  ({ p, got } = await page('0', 1000, 520));
  await p.evaluate(() => { ronin.state({ container: "PERSONAL", tabs: [{ id: 1, title: "Wikipedia", url: "https://www.wikipedia.org/", active: true }], bookmarked: false });
    ronin.overlay({ kind: "bookmarks", list: [{ title: "Wikipedia", url: "https://www.wikipedia.org/" }, { title: "DIKSHA - learning", url: "https://diksha.gov.in/" }, { title: "NIFTY chart", url: "https://in.tradingview.com/chart/" }] }); });
  await p.waitForTimeout(150); await p.keyboard.type('dik'); await p.waitForTimeout(100);
  await p.screenshot({ path: out + '/bookmarks.png' });
  await p.evaluate(() => [...document.querySelectorAll('.menu button')].find(x => x.textContent.startsWith('DIKSHA')).click()); await p.waitForTimeout(100);
  if (!got.some(m => m.cmd === 'go' && m.text === 'https://diksha.gov.in/')) bad('bookmark click: ' + JSON.stringify(got));
  await p.evaluate(() => ronin.overlay({ kind: "bookmarks", list: [{ title: "A", url: "https://a.com/" }] })); await p.waitForTimeout(100);
  await p.evaluate(() => document.querySelector('.menu button[title="Remove this bookmark"]').click()); await p.waitForTimeout(100);
  if (!got.some(m => m.cmd === 'removebookmark' && m.url === 'https://a.com/')) bad('bookmark remove');
  console.log(fail ? fail + ' problem(s)' : 'downloads panel + float + bookmarks OK');
  await b.close(); process.exit(fail ? 1 : 0);
})();
