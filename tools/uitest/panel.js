// NODE_PATH=$(npm root -g) node tools/uitest/panel.js out.png — draws the side panel (Downloads) and checks it talks to Ronin correctly.
const { chromium } = require('playwright'); const fs = require('fs');
(async () => {
  let html = fs.readFileSync('cmd/ronin/ui/toolbar.html', 'utf8').replace(/{{CONTAINER}}/g, 'PERSONAL').replace(/{{PANEL}}/g, '1').replace('<script>', '<script>if(!window.chrome)window.chrome={};window.chrome.webview={postMessage:m=>window.__post(m)};</script><script>');
  const b = await chromium.launch(); const p = await b.newPage({ viewport: { width: 400, height: 640 }, deviceScaleFactor: 1 });
  const got = []; await p.exposeFunction('__post', m => got.push(JSON.parse(m).cmd)); await p.addInitScript(() => { Object.defineProperty(window, 'chrome', { value: { webview: { postMessage: m => window.__post(m) } }, configurable: true }); });
  await p.setContent(html);
  await p.evaluate(() => ronin.overlay({ kind: "aimenu" })); await p.screenshot({ path: process.argv[2].replace('.png','-ai.png') }); await p.evaluate(() => ronin.overlay({ kind: "downloads", folder: "x", jobs: [
    { id: 3, kind: "file", title: "class5-hindi-chapter2.pdf", state: "downloading", percent: 42, size: "1.3 MB of 3.1 MB" },
    { id: 2, kind: "video", title: "Asking Strangers to Celebrate Diwali", state: "done", percent: 100 },
    { id: 1, kind: "file", title: "setup.exe", state: "failed", percent: -1, error: "The download stopped." }] }));
  console.log('body class:', await p.evaluate(() => document.body.className + ' PANEL=' + PANEL)); await p.screenshot({ path: process.argv[2] });
  const sent = await p.evaluate(() => { document.querySelector('.pclose').click(); return JSON.stringify(window.sent); });
  await p.waitForTimeout(300); console.log('messages:', got.join(',')); if (got.join(',') !== 'panelready,panelclose') { console.log('FAIL side panel messages'); process.exit(1); } console.log('side panel OK');
  await b.close();
})();
