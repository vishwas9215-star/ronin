// NODE_PATH=$(npm root -g) node tools/uitest/social.js [outdir] — Safe Social: launcher, settings PIN,
// sponsored posts hidden, risky links stopped, scam messages marked, buttons in a social tab.
const { chromium } = require('playwright'); const fs = require('fs'); const path = require('path');
(async () => {
  const out = process.argv[2] || '/tmp/ui'; fs.mkdirSync(out, { recursive: true });
  const js = fs.readFileSync('cmd/ronin/ui/inject_social.js', 'utf8'), dir = 'cmd/ronin/ui/tools';
  const b = await chromium.launch(); let fail = 0; const bad = m => { console.log('FAIL', m); fail++; };
  // a made-up Facebook-like feed
  const feed = `<html><body><div role=feed>
    <div role=article id=p1><span dir=auto>Ram Kumar</span><div dir=auto>Good morning friends</div></div>
    <div role=article id=ad1><a href="#"><span>S</span></a><a href="https://www.facebook.com/ads/about/?x=1"><span>Sponsored</span></a><div dir=auto>Hot video</div></div>
    <div role=article id=ad2><span dir=auto>Page</span><a><span>प्रायोजित</span></a></div>
    <div role=article id=p2><div dir=auto id=scam1>Join our VIP group for share market tips, guaranteed profit daily</div></div>
    <div role=article id=p3><div dir=auto id=scam2>Aapne lottery jeeti hai! KBC prize ke liye QR scan karein</div></div>
    <a id=tg href="https://l.facebook.com/l.php?u=https%3A%2F%2Ft.me%2Fearnfast">join</a>
    <a id=wa href="https://chat.whatsapp.com/AbCdEf">group</a>
    <a id=short href="https://bit.ly/3xyz">see</a>
    <a id=ok href="https://www.wikipedia.org/">wiki</a>
    <a id=shorts href="/shorts/abc">shorts</a>
  </div></body></html>`;
  const p = await b.newPage();
  await p.route('https://www.facebook.com/**', r => r.fulfill({ contentType: 'text/html; charset=utf-8', body: feed }));
  await p.addInitScript('window.__roninSocialCfg={social:true,k:"K1",family:false,reels:true};' + js);
  await p.goto('https://www.facebook.com/');
  await p.waitForTimeout(900);
  const vis = id => p.$eval('#' + id, e => getComputedStyle(e).display !== 'none');
  if (await vis('ad1') || await vis('ad2')) bad('sponsored posts still shown');
  if (!await vis('p1')) bad('normal post hidden');
  const tags = await p.$$eval('.ronin-scam', a => a.map(x => x.textContent));
  if (tags.length < 2) bad('scam messages not marked: ' + JSON.stringify(tags));
  if (await p.$eval('#shorts', e => getComputedStyle(e).display !== 'none')) bad('shorts link not hidden');
  if (!await p.$('#ronin-soc-btns')) bad('← ⊞ ✕ buttons missing');
  for (const id of ['tg', 'wa', 'short']) {
    await p.click('#' + id);
    if (!await p.$('#ronin-soc-warn')) bad(id + ' link not stopped');
    await p.click('#ronin-soc-warn .safe');
  }
  await p.screenshot({ path: path.join(out, 'social-feed.png') });
  await p.click('#tg');
  await p.screenshot({ path: path.join(out, 'social-warn.png') });
  const v = await p.evaluate(() => ['upi://pay?pa=x@y', 'https://www.wikipedia.org/', 'https://example.com/app.apk', 'https://xvideos.com/'].map(u => !!window.__roninSocialCheck.verdict(u)));
  if (v.join() !== 'true,false,true,true') bad('verdicts: ' + v);
  // never an "open anyway" button
  if (await p.$('#ronin-soc-warn .go')) bad('a risky link can still be opened');
  // rumours get a fact-check label; Google search gets a fact-check chip
  const p2 = await b.newPage();
  await p2.route('https://www.facebook.com/**', r => r.fulfill({ contentType: 'text/html; charset=utf-8', body: '<div role=article><div dir=auto>सरकार दे रही है सबको फ्री लैपटॉप, जल्दी सभी ग्रुप में भेजें, मीडिया नहीं दिखाएगा</div></div>' }));
  await p2.addInitScript('window.__roninSocialCfg={};' + js);
  await p2.goto('https://www.facebook.com/'); await p2.waitForTimeout(700);
  if (!await p2.$('.ronin-rumour a[href*="fact%20check"]')) bad('rumour not marked');
  await p2.route('https://www.google.com/**', r => r.fulfill({ contentType: 'text/html; charset=utf-8', body: '<html><body>results</body></html>' }));
  await p2.goto('https://www.google.com/search?q=free+laptop+yojana'); await p2.waitForTimeout(500);
  if (!await p2.$('#ronin-fact a')) bad('no fact check on Google');
  // a normal site is left alone
  const p3 = await b.newPage();
  await p3.route('https://news.example.com/**', r => r.fulfill({ contentType: 'text/html; charset=utf-8', body: feed }));
  await p3.addInitScript('window.__roninSocialCfg={};' + js);
  await p3.goto('https://news.example.com/'); await p3.waitForTimeout(500);
  if (await p3.$('.ronin-scam')) bad('works on a non-social site');
  // launcher + settings PIN
  const p4 = await b.newPage();
  await p4.route('https://ronin.home/tools/**', r => { const f = path.join(dir, new URL(r.request().url()).pathname.replace('/tools/', '')); r.fulfill({ body: fs.readFileSync(f), contentType: /\.js$/.test(f) ? 'text/javascript' : 'text/html' }); });
  await p4.route('https://ronin.data/**', r => r.fulfill({ status: 204, body: '' }));
  await p4.goto('https://ronin.home/tools/social.html?k=K2');
  if ((await p4.$$('.app')).length !== 4) bad('default apps: ' + (await p4.$$('.app')).length);
  await p4.screenshot({ path: path.join(out, 'social-home.png') });
  await p4.click('button[title=Settings]');
  await p4.click('.row:has-text("Threads") .sw'); await p4.click('.row:has-text("Reels") .sw');
  await p4.fill('input[type=password]', '4321'); await p4.click('button:has-text("PIN रखें")');
  await p4.click('button:has-text("✔ Save")');
  if ((await p4.$$('.app')).length !== 5) bad('Threads not added');
  await p4.click('button[title=Settings]');
  if (!await p4.$('text=Settings का PIN')) bad('PIN not asked');
  await p4.fill('input[type=password]', '0000'); await p4.click('button:has-text("खोलें")');
  if (!await p4.$('text=PIN ग़लत है')) bad('wrong PIN let in');
  await p4.fill('input[type=password]', '4321'); await p4.click('button:has-text("खोलें")');
  if (!await p4.$('text=कौन-से apps दिखें')) bad('right PIN did not open settings');
  const req = [];
  p4.on('request', r => { if (/ronin\.data/.test(r.url())) req.push(r.url()); });
  await p4.click('button:has-text("✔ Save")'); await p4.click('.app[data-app=instagram]'); await p4.waitForTimeout(300);
  const open = req.find(u => /do=open/.test(u));
  if (!open || !/instagram/.test(decodeURIComponent(open)) || !/k=K2/.test(open)) bad('open request: ' + req.join(' '));
  const cfg = JSON.parse(Buffer.from(decodeURIComponent(new URL(open).searchParams.get('cfg')), 'base64').toString());
  if (!cfg.reels || cfg.pin !== undefined || !cfg.apps.includes('threads')) bad('cfg sent to Ronin: ' + JSON.stringify(cfg));
  console.log(fail ? fail + ' FAILED' : 'safe social OK');
  await b.close(); process.exit(fail ? 1 : 0);
})();
