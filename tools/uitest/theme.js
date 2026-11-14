// NODE_PATH=$(npm root -g) node tools/uitest/theme.js outdir — Day / Night button and look of the toolbar and new tab page.
const { chromium } = require('playwright'); const fs = require('fs');
(async () => {
  const out = process.argv[2] || '.'; let fail = 0; const bad = m => { console.log('FAIL ' + m); fail++; };
  const b = await chromium.launch();
  for (const theme of ['', 'light']) {
    const html = fs.readFileSync('cmd/ronin/ui/toolbar.html', 'utf8').replace(/{{CONTAINER}}/g, 'PERSONAL').replace(/{{PANEL}}/g, '0').replace(/{{THEME}}/g, theme)
      .replace('<script>', '<script>window.chrome={webview:{postMessage:m=>window.__post(m)}};</script><script>');
    const p = await b.newPage({ viewport: { width: 1100, height: 120 } }); const got = [];
    await p.exposeFunction('__post', m => got.push(JSON.parse(m))); await p.setContent(html);
    await p.evaluate(() => ronin.state({ container: "PERSONAL", tabs: [{ id: 1, title: "Wikipedia", url: "https://www.wikipedia.org/", active: true }, { id: 2, title: "NIFTY ▲ 0.4%", url: "https://in.tradingview.com/", up: true }], vpn: "connected", vpnSmart: true, vpnHere: false }));
    await p.screenshot({ path: `${out}/toolbar-${theme || 'night'}.png` });
    const bg = await p.evaluate(() => getComputedStyle(document.body).backgroundColor);
    if ((theme === 'light') !== (bg === 'rgb(238, 240, 244)')) bad(theme + ' background ' + bg);
    const tip = await p.evaluate(() => document.getElementById('themeBtn').title);
    if (!tip.includes(theme === 'light' ? 'Night' : 'Day')) bad('button title ' + tip);
    await p.click('#themeBtn'); await p.waitForTimeout(100);
    if (!got.some(m => m.cmd === 'theme')) bad('button sends nothing');
    await p.evaluate(t => ronin.theme(t), theme === 'light' ? '' : 'light');
    const bg2 = await p.evaluate(() => getComputedStyle(document.body).backgroundColor);
    if (bg2 === bg) bad('ronin.theme() did not switch');
  }
  for (const theme of ['', 'light']) {
    const html = fs.readFileSync('cmd/ronin/ui/newtab.html', 'utf8').replace(/{{CONTAINER}}/g, 'PERSONAL').replace(/{{THEME}}/g, theme)
      .replace(/{{HEADING}}/g, 'Ronin Browser').replace(/{{SUBTITLE}}/g, 'Safe, private and fast.').replace(/{{SECTIONS}}/g, '').replace(/{{NOTE}}/g, '');
    const p = await b.newPage({ viewport: { width: 900, height: 300 } }); await p.setContent(html);
    await p.screenshot({ path: `${out}/newtab-${theme || 'night'}.png` });
  }
  console.log(fail ? fail + ' problem(s)' : 'day/night toolbar OK'); await b.close(); process.exit(fail ? 1 : 0);
})();
