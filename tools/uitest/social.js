// NODE_PATH=$(npm root -g) node tools/uitest/social.js [outdir] — Safe Social + Safe Mail: launcher, settings PIN,
// sponsored posts hidden, risky links stopped (no "open anyway"), scam messages marked (score-based),
// rumours get fact-check links, strangers / video calls warned, scam email -> Delete / Spam, big buttons.
const { chromium } = require('playwright'); const fs = require('fs'); const path = require('path');
(async () => {
  const out = process.argv[2] || '/tmp/ui'; fs.mkdirSync(out, { recursive: true });
  const lists = fs.readFileSync('internal/safelinks/lists.json', 'utf8'), rules = fs.readFileSync('internal/safelinks/scamrules.json', 'utf8');
  const js = fs.readFileSync('cmd/ronin/ui/tools/scamcheck.js', 'utf8') + '\n' + fs.readFileSync('cmd/ronin/ui/inject_social.js', 'utf8'), dir = 'cmd/ronin/ui/tools';
  const init = cfg => 'window.__roninLang="hi";window.__roninSocialCfg=' + JSON.stringify(cfg) + ';window.__roninLinkLists=' + lists + ';window.__roninScamRules=' + rules + ';' +
    'window.__said=[];window.roninNative={postMessage:m=>window.__said.push(m)};' + js;
  const b = await chromium.launch(); let fail = 0; const bad = m => { console.log('FAIL', m); fail++; };
  const html = body => ({ contentType: 'text/html; charset=utf-8', body });
  // a made-up Facebook-like feed
  const feed = `<html><body><div role=feed>
    <div role=article id=p1><span dir=auto>Ram Kumar</span><div dir=auto id=normal1>Good morning friends, kal school me puraskar vitaran hai</div></div>
    <div role=article id=ad1><a href="#"><span>S</span></a><a href="https://www.facebook.com/ads/about/?x=1"><span>Sponsored</span></a><div dir=auto>Hot video</div></div>
    <div role=article id=ad2><span dir=auto>Page</span><a><span>प्रायोजित</span></a></div>
    <div role=article id=p2><div dir=auto id=scam1>Join our VIP group for share market tips, guaranteed profit daily</div></div>
    <div role=article id=p3><div dir=auto id=scam2>Aapne lottery jeeti hai! KBC prize ke liye pehle processing fee bhejo</div></div>
    <div role=article id=p4><div dir=auto id=adv>OTP किसी को मत बताना, बैंक कभी OTP नहीं माँगता</div></div>
    <a id=tg href="https://l.facebook.com/l.php?u=https%3A%2F%2Ft.me%2Fearnfast">join</a>
    <a id=wa href="https://chat.whatsapp.com/AbCdEf">group</a>
    <a id=short href="https://bit.ly/3xyz">see</a>
    <a id=at href="https://www.sbi.co.in@evil.example/kyc">sbi</a>
    <a id=ussd href="tel:*401*9876543210">call</a>
    <a id=remote href="https://anydesk.com/en/downloads">help</a>
    <a id=ok href="https://www.wikipedia.org/">wiki</a>
    <a id=tel href="tel:1930">1930</a>
    <a id=shorts href="/shorts/abc">shorts</a>
    <div aria-label="Start a video call" role=button id=vcall>📹</div>
  </div></body></html>`;
  const p = await b.newPage();
  await p.route('https://www.facebook.com/**', r => r.fulfill(html(feed)));
  await p.addInitScript(init({ social: true, k: 'K1', reels: true }));
  await p.goto('https://www.facebook.com/');
  await p.waitForTimeout(900);
  const vis = id => p.$eval('#' + id, e => getComputedStyle(e).display !== 'none');
  if (await vis('ad1') || await vis('ad2')) bad('sponsored posts still shown');
  if (!await vis('p1')) bad('normal post hidden');
  const tags = await p.$$eval('.ronin-scam:not(.ronin-rumour)', a => a.map(x => x.nextElementSibling && x.nextElementSibling.id));
  if (!tags.includes('scam1') || !tags.includes('scam2')) bad('scam messages not marked: ' + JSON.stringify(tags));
  if (tags.includes('adv') || tags.includes('normal1')) bad('advice / normal post marked: ' + JSON.stringify(tags));
  if (await p.$eval('#shorts', e => getComputedStyle(e).display !== 'none')) bad('shorts link not hidden');
  const btns = await p.$$eval('#ronin-soc-btns a', a => a.map(x => x.textContent + ':' + Math.round(x.getBoundingClientRect().height)));
  if (btns.length !== 3 || !/पीछे/.test(btns[0]) || btns.some(x => +x.split(':')[1] < 44)) bad('big labelled buttons: ' + btns);
  for (const id of ['tg', 'wa', 'short', 'at', 'ussd', 'remote']) {
    await p.click('#' + id);
    if (!await p.$('#ronin-soc-warn')) { bad(id + ' link not stopped'); continue; }
    if (await p.$('#ronin-soc-warn .go')) bad(id + ': a risky link can still be opened');
    await p.click('#ronin-soc-warn .safe');
  }
  if (!(await p.evaluate(() => window.__said)).includes('speak:link')) bad('warning not spoken');
  const before = p.url();
  await p.evaluate(() => { const a = document.getElementById('tel'); a.addEventListener('click', e => e.preventDefault()); a.click(); });
  if (await p.$('#ronin-soc-warn')) bad('1930 call link stopped');
  await p.click('#tg');
  await p.screenshot({ path: path.join(out, 'social-warn.png') });
  await p.click('#ronin-soc-warn .safe');
  // video call: asks first, the call goes ahead only on "I know them"
  let called = 0; await p.exposeFunction('__called', () => { called++; });
  await p.evaluate(() => document.getElementById('vcall').addEventListener('click', () => window.__called()));
  await p.click('#vcall');
  if (!await p.$('#ronin-soc-warn .calm') || called) bad('video call not asked first');
  await p.click('#ronin-soc-warn .small'); await p.waitForTimeout(100);
  if (called !== 1) bad('video call did not go ahead after "I know them"');
  await p.screenshot({ path: path.join(out, 'social-feed.png') });
  // the same engine as the tests of scamcheck.js
  const v = await p.evaluate(() => ['upi://pay?pa=x@y', 'https://www.wikipedia.org/', 'https://example.com/app.apk', 'https://xvideos.com/'].map(u => !!window.__roninSocialCheck.verdict(u)));
  if (v.join() !== 'true,false,true,true') bad('verdicts: ' + v);
  // rumours: "check first" with fact-check sites; Google search gets them too
  const p2 = await b.newPage();
  await p2.route('https://www.facebook.com/**', r => r.fulfill(html('<div role=article><div dir=auto>सरकार दे रही है सबको फ्री लैपटॉप, जल्दी सभी ग्रुप में भेजें, मीडिया नहीं दिखाएगा</div></div>')));
  await p2.addInitScript(init({}));
  await p2.goto('https://www.facebook.com/'); await p2.waitForTimeout(700);
  if (!await p2.$('.ronin-rumour a[href*="vishvasnews.com"]')) bad('rumour not marked with fact-check sites');
  if (await p2.$('.ronin-rumour:has-text("झूठी")')) bad('rumour called false (must only say "check")');
  await p2.route('https://www.google.com/**', r => r.fulfill(html('<html><body>results</body></html>')));
  await p2.goto('https://www.google.com/search?q=free+laptop+yojana'); await p2.waitForTimeout(500);
  if (!await p2.$('#ronin-fact a[href*="boomlive"]')) bad('no fact check on Google');
  // Instagram (like the real one, Nov 2026): the "Ad" label and the "Shop now" button come AFTER the post,
  // and a caption opens with "... more"
  const ig = `<html><body><main>
    <article id=igold><header><a><span>news_page</span></a><time datetime="2023-03-12T10:00:00.000Z">Mar 12, 2023</time></header><div>flood photo</div></article>
    <article id=ignew><header><a><span>friend</span></a><time datetime="${new Date().toISOString()}">now</time></header></article>
    <article id=igok><header><a><span>friend_ram</span></a></header><span dir=auto id=igcap>Sunday walk with family</span><a role=button>Follow</a></article>
    <article id=igad><header><a><span>kasperskylab</span></a><div id=lbl></div></header><div>photo</div></article>
    <article id=igshop><header><a><span>brand</span></a></header><a href="https://l.instagram.com/?u=x" id=cta></a></article>
    <article id=igreel><span dir=auto id=reelcap>Trading tips...</span></article>
    <article id=igpic><div><div><img id=pic alt="Photo by aviator_hack on January 30, 2021. May be an image of text that says 'Aviator hack 7days ₹499 30days ₹999 Who wants to buy message me'"></div></div></article>
    <article id=igpicok><div><div><img alt="Photo by friend_ram on November 14, 2026. May be an image of 2 people, temple and text that says 'Happy Diwali'"></div></div></article>
  </main></body></html>`;
  await p2.route('https://www.instagram.com/**', r => r.fulfill(html(ig)));
  await p2.goto('https://www.instagram.com/'); await p2.waitForTimeout(600);
  await p2.evaluate(() => { document.getElementById('lbl').innerHTML = '<span>Ad</span>'; document.getElementById('cta').textContent = 'Shop now';
    document.getElementById('reelcap').textContent = 'Trading tips: invest 10k get 1 lakh in 7 days, 100% guarantee, join my Telegram channel, link in bio'; });
  await p2.waitForTimeout(1200);
  const igv = id => p2.$eval('#' + id, e => getComputedStyle(e).display !== 'none');
  if (await igv('igad')) bad('instagram "Ad" post shown');
  if (await igv('igshop')) bad('instagram "Shop now" ad shown');
  if (!await igv('igok')) bad('instagram normal post hidden');
  if (!await p2.$('.ronin-scam + #reelcap')) bad('reel caption that opened later not warned');
  if (await p2.$('.ronin-scam + #igcap')) bad('normal caption warned');
  if (!await p2.$('#igold > .ronin-age.ronin-old')) bad('old post has no date badge');
  if (await p2.$('#ignew > .ronin-age')) bad('new post got an old badge');
  if (!await p2.$('#igpic > .ronin-scam')) bad('scam words on a photo not warned');
  if (await p2.$('#igpicok > .ronin-scam')) bad('normal photo warned');
  if ((await p2.evaluate(() => window.__roninSocialCounts.ads)) !== 2) bad('ads count ' + (await p2.evaluate(() => JSON.stringify(window.__roninSocialCounts))));
  // message requests: a stranger banner
  await p2.route('https://www.instagram.com/**', r => r.fulfill(html('<div>requests</div>')));
  await p2.goto('https://www.instagram.com/direct/requests/'); await p2.waitForTimeout(600);
  if (!await p2.$('#ronin-stranger')) bad('message requests not marked as strangers');
  // a normal site is left alone
  const p3 = await b.newPage();
  await p3.route('https://news.example.com/**', r => r.fulfill(html(feed)));
  await p3.addInitScript(init({}));
  await p3.goto('https://news.example.com/'); await p3.waitForTimeout(500);
  if (await p3.$('.ronin-scam')) bad('works on a non-social site');
  // Safe Mail: a made-up Gmail with a threat mail; Delete presses Gmail's own button
  const gmail = (name, from, subject, body, files) => `<html><body><div gh=tm><div role=button act=10 aria-label="Delete" id=gdel>🗑</div><div role=button act=9 aria-label="Report spam" id=gspam>!</div></div>
    <div role=main><h2 class=hP>${subject}</h2><span class=gD email="${from}" name="${name}">${name}</span><div class=a3s>${body}</div>${files.map(f => `<span class=aV3>${f}</span>`).join('')}</div></body></html>`;
  const p5 = await b.newPage();
  let mailHtml = gmail('Cyber Crime Cell', 'cybercell.notice@gmail.com', 'Legal notice: child pornography case', 'Your IP address was found viewing child pornography. A case is registered under POCSO. Pay the fine within 24 hours to avoid arrest.', ['notice.pdf']);
  await p5.route('https://mail.google.com/**', r => r.fulfill(html(mailHtml)));
  await p5.addInitScript(init({ k: 'K5' }) + ';window.__del=0;document.addEventListener("click",e=>{if(e.target&&e.target.id==="gdel")window.__del++;},true);');
  await p5.goto('https://mail.google.com/mail/u/0/#inbox/abc'); await p5.waitForTimeout(1200);
  if (!await p5.$('#ronin-mail.red')) bad('threat email not marked red');
  await p5.screenshot({ path: path.join(out, 'safe-mail.png') });
  if (!(await p5.evaluate(() => window.__said)).includes('speak:mail')) bad('mail warning not spoken');
  await p5.click('#ronin-mail .del'); await p5.waitForTimeout(200);
  if (await p5.evaluate(() => window.__del) !== 1) bad('Delete did not press the mail site\'s Delete');
  mailHtml = gmail('Manav Sampada', 'noreply@upsdc.gov.in', 'Leave application approved', 'Your CL application has been approved.', []);
  await p5.goto('https://mail.google.com/mail/u/0/#inbox/def'); await p5.waitForTimeout(1200);
  if (await p5.$('#ronin-mail')) bad('normal email marked');
  // a family's own email site (custom) is checked too
  await p5.route('https://webmail.school.example/**', r => r.fulfill(html('<div role=main><h1>Prize</h1><p>From: lucky.draw@gmail.com</p><p>Congratulations! You have won KBC lottery of 25 lakh. Pay registration fee to claim prize. Share your OTP.</p></div>')));
  const p6 = await b.newPage();
  await p6.route('https://webmail.school.example/**', r => r.fulfill(html('<div role=main><h1>Prize</h1><p>From: lucky.draw@gmail.com</p><p>Congratulations! You have won KBC lottery of 25 lakh. Pay registration fee to claim prize. Share your OTP.</p></div>')));
  await p6.addInitScript(init({ mail: ['webmail.school.example'] }));
  await p6.goto('https://webmail.school.example/inbox'); await p6.waitForTimeout(1200);
  if (!await p6.$('#ronin-mail')) bad('custom email site not checked');
  // launcher + settings PIN + email + own apps
  const p4 = await b.newPage();
  await p4.route('https://ronin.home/tools/**', r => { const f = path.join(dir, new URL(r.request().url()).pathname.replace('/tools/', '')); r.fulfill({ body: fs.readFileSync(f), contentType: /\.js$/.test(f) ? 'text/javascript' : 'text/html' }); });
  await p4.route('https://ronin.data/**', r => r.fulfill({ status: 204, body: '' }));
  await p4.addInitScript(init({ k: 'S' }));
  await p4.goto('https://ronin.home/tools/social.html?k=K2');
  if ((await p4.$$('.app')).length !== 5) bad('default apps: ' + (await p4.$$('.app')).length);
  if (!await p4.$('.app[data-app=gmail]')) bad('Gmail not on the launcher');
  if (await p4.$('.sos')) bad('fraud help shown all the time');
  await p4.click('#sosBtn');
  if (!await p4.$('.sos a[href="tel:1930"]')) bad('no 1930 help behind 🆘');
  await p4.click('#sosBtn');
  await p4.screenshot({ path: path.join(out, 'social-home.png') });
  await p4.click('button:has-text("⚙ Settings")');
  await p4.click('.row:has-text("Threads") .sw'); await p4.click('.row:has-text("Reels") .sw');
  await p4.fill('input[placeholder="नाम"]', 'School Mail'); await p4.fill('input[placeholder="https://..."]', 'webmail.school.example');
  await p4.selectOption('.card:has-text("अपनी कोई") select', '1'); await p4.click('button:has-text("➕ जोड़ें")');
  await p4.fill('input[placeholder="नाम"]', 'Fake'); await p4.fill('input[placeholder="https://..."]', 'https://t.me/x'); await p4.click('button:has-text("➕ जोड़ें")');
  if (!await p4.$('text=ख़तरनाक है')) bad('a dangerous address could be added');
  await p4.fill('input[type=password]', '4321'); await p4.click('button:has-text("PIN रखें")');
  await p4.screenshot({ path: path.join(out, 'social-settings.png'), fullPage: true });
  await p4.click('button:has-text("✔ Save")');
  if ((await p4.$$('.app')).length !== 7) bad('Threads / own app not added: ' + (await p4.$$('.app')).length);
  await p4.click('button:has-text("⚙ Settings")');
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
  if (!cfg.reels || cfg.pin !== undefined || !cfg.apps.includes('threads') || !cfg.custom.length || !cfg.custom[0].mail) bad('cfg sent to Ronin: ' + JSON.stringify(cfg));
  // Scam Check tool (Share -> Ronin): a hacked friend's "need money, pay on this QR"
  const p7 = await b.newPage();
  await p7.route('https://ronin.home/tools/**', r => { const f = path.join(dir, new URL(r.request().url()).pathname.replace('/tools/', '') || 'index.html'); r.fulfill({ body: fs.readFileSync(f.endsWith('/') ? f + 'index.html' : f), contentType: /\.m?js$/.test(f) ? 'text/javascript' : 'text/html; charset=utf-8' }); });
  await p7.addInitScript(init({ k: 'S' }));
  await p7.goto('https://ronin.home/tools/index.html#scamcheck:' + encodeURIComponent('Mujhe urgent 10000 rupaye ki zarurat hai, hospital me hoon, is QR par bhej do'));
  await p7.waitForSelector('#verdict', { timeout: 15000 }).catch(() => {});
  if (await p7.$eval('#verdict', e => e.dataset.level).catch(() => '') !== 'stop') bad('shared message not judged a scam');
  await p7.screenshot({ path: path.join(out, 'scamcheck.png') });
  console.log(fail ? fail + ' FAILED' : 'safe social OK');
  await b.close(); process.exit(fail ? 1 : 0);
})();
