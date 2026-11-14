// NODE_PATH=$(npm root -g) node tools/uitest/passwords.js — password capture + fill scripts in a real engine.
const { chromium } = require('playwright'); const fs = require('fs');
const inject = fs.readFileSync('cmd/ronin/ui/inject_pass.js', 'utf8');
const fill = fs.readFileSync('cmd/ronin/ui/fill_pass.js', 'utf8');
const pages = {
  '/login': '<form action="/home" method="post"><input name="user" type="email"><input name="pw" type="password"><button>Sign in</button></form>',
  '/home': '<h1>Welcome</h1>',
  '/step1': '<input name="identifier" type="email"><button onclick="location.href=\'/step2\'">Next</button>',
  '/step2': '<form action="/home"><input type="password" name="Passwd"><button>Next</button></form>',
  '/evil': '<script>try{window.__roninPw={take:()=>"stolen"}}catch(e){}</script><input type="password">',
};
(async () => {
  const b = await chromium.launch(); const ctx = await b.newContext({ ignoreHTTPSErrors: true });
  await ctx.route('**/*', r => { const u = new URL(r.request().url()); r.fulfill({ contentType: 'text/html', body: '<html><body>' + (pages[u.pathname] || 'x') + '</body></html>' }); });
  await ctx.addInitScript(inject);
  let fail = 0; const bad = m => { console.log('FAIL', m); fail++; };
  // Ronin takes the login while the page is still open (at NavigationStarting); the test takes it just before sending.
const take = p => p.evaluate(() => window.__roninPw ? JSON.stringify(window.__roninPw.take()) : 'null');
  const p = await ctx.newPage();
  // 1. normal login: the page changes, Ronin asks the new page (same site)
  await p.goto('https://login.example.com/login');
  await p.fill('input[type=email]', 'vishwas@example.com'); await p.fill('input[type=password]', 'Pa ss"1');
  await p.evaluate(() => document.querySelector('form').addEventListener('submit', e => e.preventDefault()));
  await p.click('button');
  let r = JSON.parse(await take(p));
  if (!r || r[0] !== 'vishwas@example.com' || r[1] !== 'Pa ss"1') bad('login not noticed ' + JSON.stringify(r));
  if ((await take(p)) !== 'null') bad('taken twice');
  if (JSON.stringify(await p.evaluate(() => Object.assign({}, sessionStorage))).includes('Pa ss')) bad('password written to sessionStorage');
  // 2. two-step login (name first, password on the next page)
  await p.goto('https://accounts.example.org/step1');
  await p.fill('input[type=email]', 'me@example.org'); await p.press('input[type=email]', 'Tab');
  await Promise.all([p.waitForNavigation(), p.click('button')]);
  await p.evaluate(() => document.querySelector('form').addEventListener('submit', e => e.preventDefault()));
  await p.fill('input[type=password]', 'second'); await p.press('input[type=password]', 'Enter');
  r = JSON.parse(await take(p));
  if (!r || r[0] !== 'me@example.org' || r[1] !== 'second') bad('two-step login ' + JSON.stringify(r));
  // 3. a page cannot replace Ronin's helper
  await p.goto('https://bad.example.net/evil');
  if ((await take(p)) === '"stolen"') bad('page replaced the helper');
  // 4. fill only on the same site
  await p.goto('https://www.example.com/login');
  const run = (o, u, pw) => p.evaluate(s => eval(s), fill + '(' + JSON.stringify([o, o.replace('://', '://www.'), u, pw]) + ')');
  if (await run('https://evil.com', 'x', 'y')) bad('filled on another site');
  if ((await p.inputValue('input[type=password]')) !== '') bad('password box touched for another site');
  if (!(await run('https://example.com', 'vishwas', 'secret'))) bad('fill on www.example.com');
  if ((await p.inputValue('input[type=email]')) !== 'vishwas' || (await p.inputValue('input[type=password]')) !== 'secret') bad('fill values');
  // a page that hooks string functions still cannot pass the site check
  await p.goto('https://evil.example.net/login');
  await p.evaluate(() => { String.prototype.toLowerCase = () => 'https://bank.example'; String.prototype.replace = function () { return 'https://bank.example'; }; String.prototype.slice = () => 'https'; });
  if (await run('https://bank.example', 'v', 'BANKPW')) bad('hooked page passed the site check');
  await p.goto('http://www.example.com/login');
  if (await run('https://example.com', 'v', 's')) bad('filled an http page with an https password');
  console.log(fail ? fail + ' FAILED' : 'password capture + fill OK');
  await b.close(); process.exit(fail ? 1 : 0);
})();
