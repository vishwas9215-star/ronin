// NODE_PATH=$(npm root -g) node tools/uitest/darkcsp.js — Night mode also on pages whose security rules forbid <style> (UDISE+ sign-in).
const { chromium } = require('playwright'); const fs = require('fs');
(async () => {
  const dark = fs.readFileSync('cmd/ronin/ui/inject_dark.js', 'utf8'), ab = fs.readFileSync('cmd/ronin/ui/inject_adblock.js', 'utf8');
  const b = await chromium.launch(); const ctx = await b.newContext({ colorScheme: 'dark' }); let fail = 0; const bad = m => { console.log('FAIL', m); fail++; };
  await ctx.addInitScript(ab); await ctx.addInitScript(dark);
  await ctx.route('**/*', r => r.fulfill({ contentType: 'text/html', headers: { 'content-security-policy': "default-src 'self'; style-src 'self'; script-src 'self'" },
    body: '<html><body style="margin:0"><main style="height:100vh"><section style="width:300px;margin:40px auto;padding:20px">Sign in<input placeholder=Username><ins class="adsbygoogle" style="display:block;height:50px">ad</ins></section></main></body></html>' }));
  const p = await ctx.newPage(); await p.goto('https://auth.example.gov.in/login'); await p.waitForTimeout(1500);
  const r = await p.evaluate(() => [document.documentElement.className, getComputedStyle(document.documentElement).filter, getComputedStyle(document.querySelector('ins')).display]);
  if (!r[0].includes('ronin-dark') || r[1] === 'none') bad('night mode not applied under CSP: ' + r);
  if (r[2] !== 'none') bad('ad box not hidden under CSP: ' + r[2]);
  console.log(fail ? fail + ' FAILED' : 'night mode + ad hiding under strict CSP OK');
  await b.close(); process.exit(fail ? 1 : 0);
})();
