// NODE_PATH=$(npm root -g) node tools/uitest/voice.js out.png — speak to search + Video call window.
// Windows voice typing writes into the address box like a keyboard; this test types like it does.
const { chromium } = require('playwright'); const fs = require('fs');
const page = async (b, cont, got) => {
  const html = fs.readFileSync('cmd/ronin/ui/toolbar.html', 'utf8').replace(/{{CONTAINER}}/g, cont).replace(/{{PANEL}}/g, '0')
    .replace('<script>', '<script>window.chrome={webview:{postMessage:m=>window.__post(m)}};</script><script>');
  const p = await b.newPage({ viewport: { width: 1000, height: 500 } });
  const errors = []; p.on('pageerror', e => errors.push(e.message));
  await p.exposeFunction('__post', m => got.push(JSON.parse(m)));
  await p.setContent(html);
  await p.evaluate(c => ronin.state({ container: c, tabs: [{ id: 1, title: "YouTube", url: "https://www.youtube.com/", active: true }] }), cont);
  p.errors = errors; return p;
};
(async () => {
  const b = await chromium.launch(); let fail = 0;
  const bad = m => { console.log('FAIL', m); fail++; };
  // 1. mic -> voice typing starts, the words open by themselves after a short pause
  let got = []; let p = await page(b, 'PERSONAL', got);
  await p.click('#micBtn'); await p.waitForTimeout(100);
  if (!got.some(m => m.cmd === 'voice')) bad('mic click did not start voice typing');
  if (!(await p.$eval('#addrwrap', e => e.classList.contains('listening')))) bad('no "listening" look');
  if ((await p.evaluate(() => document.activeElement.id)) !== 'addr') bad('address box has no focus');
  await p.keyboard.type('हिंदी समाचार।', { delay: 40 });
  await p.screenshot({ path: process.argv[2] });
  if (got.some(m => m.cmd === 'go')) bad('opened too early (while speaking)');
  await p.waitForTimeout(2100);
  const go = got.filter(m => m.cmd === 'go');
  if (go.length !== 1 || go[0].text !== 'हिंदी समाचार') bad('voice text not opened once without the full stop: ' + JSON.stringify(go));
  if (await p.$eval('#addrwrap', e => e.classList.contains('listening'))) bad('still listening after opening');
  // 2. Enter while speaking opens at once, only once
  got.length = 0; await p.click('#micBtn'); await p.keyboard.type('youtube'); await p.keyboard.press('Enter');
  await p.waitForTimeout(2100);
  if (got.filter(m => m.cmd === 'go').length !== 1) bad('Enter + pause opened twice');
  // 3. second mic click stops listening, nothing opens
  got.length = 0; await p.click('#micBtn'); await p.click('#micBtn'); await p.keyboard.type('abc'); await p.waitForTimeout(2100);
  if (got.some(m => m.cmd === 'go')) bad('stopped mic still opened text');
  if (p.errors.length) bad('JS errors ' + p.errors);
  // 4. Safe Money: no mic
  got = []; p = await page(b, 'BANKING', got);
  if (await p.isVisible('#micBtn')) bad('mic visible in Safe Money');
  // 5. Video call window: own badge, menu item in normal window
  got = []; p = await page(b, 'CALLS', got);
  if ((await p.textContent('#badge')).trim() !== 'Video call') bad('Video call badge');
  if (p.errors.length) bad('JS errors in CALLS ' + p.errors);
  got = []; p = await page(b, 'PERSONAL', got);
  await p.click('#menuBtn'); await p.click('text=Video call window (best quality)');
  if (!got.some(m => m.cmd === 'openwin' && m.container === 'CALLS')) bad('menu did not open Video call window');
  console.log(fail ? fail + ' FAILED' : 'voice + video call UI OK');
  await b.close(); process.exit(fail ? 1 : 0);
})();
