// NODE_PATH=$(npm root -g) node tools/uitest/popguard.js — advert pop-ups on download pages are stopped.
const { chromium } = require('playwright'); const fs = require('fs');
(async () => {
  const guard = fs.readFileSync('cmd/ronin/ui/inject_adblock.js', 'utf8');
  const b = await chromium.launch(); const ctx = await b.newContext(); let fail = 0; const bad = m => { console.log('FAIL', m); fail++; };
  await ctx.addInitScript(guard);
  const pages = {
    '/download': `<a id=dl href="https://files.example/real.zip" onclick="event.preventDefault()">Download</a>
    <button id=login onclick="window.open('https://accounts.google.com/o/oauth2')">Sign in with Google</button>
    <button id=share onclick="window.open('https://moviesite.example/share')">Share</button>
    <script>document.addEventListener('click', function(){ window.open('https://ads.popnetwork.example/?z=1'); });</script>`,
    '/overlay': `<button id=real style="position:absolute;top:100px;left:100px" onclick="window.done=1">Real download</button>
    <a id=ov href="https://ads.example/" target=_blank style="position:fixed;inset:0;opacity:0;z-index:9"></a>` };
  await ctx.route('**/*', r => { const u = new URL(r.request().url()); r.fulfill({ contentType: 'text/html', body: (u.hostname === 'moviesite.example' && pages[u.pathname]) || '<p>page</p>' }); });
  const p = await ctx.newPage();
  await p.goto('https://moviesite.example/download');
  const before = ctx.pages().length;
  await p.click('#dl'); await p.waitForTimeout(500);
  const popups = ctx.pages().filter(x => x !== p);
  if (popups.some(x => x.url().includes('popnetwork'))) bad('advert pop-up opened on a link click');
  if ((await p.evaluate(() => window.__roninPopBlocked)) !== 1) bad('pop-up not counted as blocked');
  await p.click('#login'); await p.waitForTimeout(500);
  if (!ctx.pages().some(x => x.url().includes('accounts.google.com'))) bad('sign-in window was blocked');
  // invisible full-page link to another site is removed, the real button under it works
  await p.goto('https://moviesite.example/overlay');
  await p.mouse.click(120, 110); await p.waitForTimeout(300);
  if (await p.$('#ov')) bad('invisible overlay link still there');
  await p.mouse.click(120, 110); await p.waitForTimeout(300);
  if ((await p.evaluate(() => window.done)) !== 1) bad('real button did not work after overlay removed');
  if (ctx.pages().some(x => x.url().includes('ads.example'))) bad('overlay opened an advert');
  console.log(fail ? fail + ' FAILED' : 'pop-up guard OK (' + before + ' page)');
  await b.close(); process.exit(fail ? 1 : 0);
})();
