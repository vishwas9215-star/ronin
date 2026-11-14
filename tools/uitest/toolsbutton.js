// NODE_PATH=$(npm root -g) node tools/uitest/toolsbutton.js out.png — Ronin Tools is a button, not a tab.
const { chromium } = require('playwright'); const fs = require('fs');
(async () => {
  const html = fs.readFileSync('cmd/ronin/ui/toolbar.html', 'utf8').replace(/{{CONTAINER}}/g, 'PERSONAL').replace(/{{PANEL}}/g, '0')
    .replace('<script>', '<script>window.chrome={webview:{postMessage:m=>window.__post(m)}};</script><script>');
  const b = await chromium.launch(); const p = await b.newPage({ viewport: { width: 1000, height: 300 } });
  const got = []; await p.exposeFunction('__post', m => got.push(JSON.parse(m)));
  await p.setContent(html);
  let fail = 0;
  await p.evaluate(() => ronin.state({ container: "PERSONAL", tabs: [
    { id: 1, title: "UDISE+", url: "udiseplus.gov.in" }, { id: 2, title: "Ronin Tools", url: "", tool: true, active: true }] }));
  await p.waitForTimeout(150);
  const tabs = await p.$$eval('#tabs .tab', a => a.map(x => x.textContent));
  if (tabs.length !== 1 || /Tools/.test(tabs.join())) { console.log('FAIL tools shown as a tab', tabs); fail++; }
  const on = await p.$eval('#toolsBtn', e => e.className);
  if (on !== 'on') { console.log('FAIL Tools button not lit while open'); fail++; }
  await p.screenshot({ path: process.argv[2] || '/tmp/toolsbutton.png' });
  await p.click('#toolsBtn'); await p.waitForTimeout(100);
  if (!got.some(m => m.cmd === 'tools')) { console.log('FAIL Tools button does nothing'); fail++; }
  console.log(fail ? fail + ' FAILED' : 'Tools button OK');
  await b.close(); process.exit(fail ? 1 : 0);
})();
