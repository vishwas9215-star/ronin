// NODE_PATH=$(npm root -g) node tools/uitest/tabsearch.js out.png — tab search (Ctrl+Shift+A) finds and selects a tab.
const { chromium } = require('playwright'); const fs = require('fs');
(async () => {
  const html = fs.readFileSync('cmd/ronin/ui/toolbar.html', 'utf8').replace(/{{CONTAINER}}/g, 'PERSONAL').replace(/{{PANEL}}/g, '0')
    .replace('<script>', '<script>window.chrome={webview:{postMessage:m=>window.__post(m)}};</script><script>');
  const b = await chromium.launch(); const p = await b.newPage({ viewport: { width: 1000, height: 500 } });
  const got = []; await p.exposeFunction('__post', m => got.push(JSON.parse(m)));
  await p.setContent(html);
  await p.evaluate(() => ronin.state({ container: "PERSONAL", tabs: [
    { id: 1, title: "YouTube", url: "youtube.com", active: true }, { id: 2, title: "Wikipedia", url: "wikipedia.org" },
    { id: 3, title: "NIFTY 50 chart", url: "in.tradingview.com/chart" }] }));
  await p.evaluate(() => ronin.tabSearch()); await p.waitForTimeout(150);
  await p.keyboard.type('wiki'); await p.waitForTimeout(200);
  await p.screenshot({ path: process.argv[2] });
  await p.keyboard.press('Enter'); await p.waitForTimeout(200);
  const sel = got.filter(m => m.cmd === 'select').map(m => m.id);
  console.log(sel.join(',') === '2' ? 'tab search OK' : 'FAIL tab search ' + JSON.stringify(got));
  await b.close();
})();
