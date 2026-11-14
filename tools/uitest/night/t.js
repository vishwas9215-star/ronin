// NODE_PATH=$(npm root -g) node tools/uitest/night/t.js cmd/ronin/ui/inject_dark.js — Night mode rules for web pages.
const { chromium } = require('playwright'); const fs = require('fs'); const path = require('path');
const js = fs.readFileSync(process.argv[2], 'utf8');
(async () => {
  const b = await chromium.launch(); let fail = 0;
  for (const scheme of ['dark', 'light']) {
    const ctx = await b.newContext({ colorScheme: scheme, viewport: { width: 700, height: 400 } });
    await ctx.addInitScript(js);
    const p = await ctx.newPage();
    for (const f of ['white', 'dark', 'alwaysdark']) {
      await p.goto('file://' + path.join(__dirname, f + '.html')); await p.waitForTimeout(1200);
      const inv = await p.evaluate(() => document.documentElement.classList.contains('ronin-dark'));
      const want = scheme === 'dark' && f === 'white';
      console.log(scheme, f, 'inverted=' + inv, inv === want ? 'ok' : 'FAIL'); if (inv !== want) fail++;
      if (scheme === 'dark') await p.screenshot({ path: path.join(__dirname, `${f}-night.png`) });
    }
    // live switch: Night -> Day on the white page
    if (scheme === 'dark') {
      await p.goto('file://' + path.join(__dirname, 'white.html')); await p.waitForTimeout(1200);
      await p.emulateMedia({ colorScheme: 'light' }); await p.waitForTimeout(300);
      const inv = await p.evaluate(() => document.documentElement.classList.contains('ronin-dark'));
      console.log('switch to day: inverted=' + inv, inv ? 'FAIL' : 'ok'); if (inv) fail++;
      await p.emulateMedia({ colorScheme: 'dark' }); await p.waitForTimeout(300);
      const inv2 = await p.evaluate(() => document.documentElement.classList.contains('ronin-dark'));
      console.log('switch back to night: inverted=' + inv2, inv2 ? 'ok' : 'FAIL'); if (!inv2) fail++;
      await p.evaluate(() => { localStorage.setItem('ronin_dark_off', '1'); window.__roninDarkApply(); });
      const inv3 = await p.evaluate(() => document.documentElement.classList.contains('ronin-dark'));
      console.log('site turned off: inverted=' + inv3, inv3 ? 'FAIL' : 'ok'); if (inv3) fail++;
    }
    await ctx.close();
  }
  console.log(fail ? fail + ' problem(s)' : 'night mode rules OK'); await b.close(); process.exit(fail ? 1 : 0);
})();
