// NODE_PATH=$(npm root -g) node tools/uitest/safemail.js — Safe Mail on the laptop (3.13): the script Ronin adds
// (built the same way as cmd/ronin/mail_windows.go) marks a scam email in Gmail and leaves a normal one alone,
// and does nothing at all on other sites.
const { chromium } = require('playwright'); const fs = require('fs');
(async () => {
  const go = fs.readFileSync('cmd/ronin/mail_windows.go', 'utf8');
  const hosts = go.match(/const mailHosts = `([^`]+)`/)[1];
  const js = '(function(){if(window.top!==window||!' + hosts + '.test(location.hostname))return;window.__roninLinkLists=' + fs.readFileSync('internal/safelinks/lists.json', 'utf8') +
    ';window.__roninScamRules=' + fs.readFileSync('internal/safelinks/scamrules.json', 'utf8') + ';\n' + fs.readFileSync('cmd/ronin/ui/tools/scamcheck.js', 'utf8') + '\n' +
    fs.readFileSync('cmd/ronin/ui/inject_mail.js', 'utf8') + '\n})();';
  const b = await chromium.launch(); let fail = 0; const bad = m => { console.log('FAIL', m); fail++; };
  const gmail = (from, name, subject, body) => `<html><body><div role="main"><h2 class="hP">${subject}</h2><span class="gD" email="${from}" name="${name}">${name}</span><div class="a3s">${body}</div></div></body></html>`;
  const open = async (url, html) => {
    const p = await b.newPage({ viewport: { width: 1280, height: 800 } });
    await p.route('**/*', r => r.fulfill({ body: html, contentType: 'text/html; charset=utf-8' }));
    await p.addInitScript(js); await p.goto(url); await p.waitForTimeout(900); return p;
  };
  const p1 = await open('https://mail.google.com/mail/u/0/#inbox/1', gmail('cybercell.delhi.police@gmail.com', 'CBI Cyber Crime Cell', 'Arrest warrant: digital arrest notice',
    'You watched illegal videos. A case is registered against you. Pay the fine of Rs 45,000 within 24 hours or you will be arrested. Do not tell anyone. Video call on Skype now.'));
  if (!await p1.$('#ronin-mail.red, #ronin-mail.amber')) bad('scam email not marked: ' + JSON.stringify(await p1.evaluate(() => window.__roninMailLast)));
  if (!await p1.$('#ronin-mail a[href="tel:1930"]')) bad('no 1930 on the scam bar');
  await p1.screenshot({ path: '/tmp/ronin-ui/safemail.png' });
  const p2 = await open('https://mail.google.com/mail/u/0/#inbox/2', gmail('ravi@gmail.com', 'Ravi', 'Sunday lunch', 'Hi, are you coming for lunch on Sunday? Bring the kids.'));
  if (await p2.$('#ronin-mail')) bad('normal email marked');
  if (!await p2.$('#ronin-mail-on')) bad('Safe Mail is on note missing');
  const p3 = await open('https://example.com/', '<html><body>Pay the fine of Rs 45,000 within 24 hours or you will be arrested</body></html>');
  if (await p3.evaluate(() => !!window.RoninScam || !!document.getElementById('ronin-mail-on'))) bad('Safe Mail ran on a site that is not email');
  console.log(fail ? fail + ' FAILED' : 'safe mail OK');
  await b.close(); process.exit(fail ? 1 : 0);
})();
