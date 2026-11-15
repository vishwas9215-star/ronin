// Safe Social pull-to-refresh: refreshes the feed at the top; never a reel / inner scroller (QA 15 Nov: reels reloaded).
const { chromium } = require('playwright'); const fs = require('fs'); const path = require('path');
(async () => {
  const root = path.join(__dirname, '..', '..');
  const lists = fs.readFileSync(path.join(root, 'internal/safelinks/lists.json'), 'utf8'), rules = fs.readFileSync(path.join(root, 'internal/safelinks/scamrules.json'), 'utf8');
  const js = fs.readFileSync(path.join(root, 'cmd/ronin/ui/tools/scamcheck.js'), 'utf8') + '\n' + fs.readFileSync(path.join(root, 'cmd/ronin/ui/inject_social.js'), 'utf8');
  const init = 'window.__roninLang="hi";window.__roninSocialCfg={social:true,k:"K"};window.__roninLinkLists=' + lists + ';window.__roninScamRules=' + rules + ';window.roninNative={postMessage:()=>{}};' + js;
  const b = await chromium.launch(); let fail = 0;
  async function drag(url, body, before) {
    const ctx = await b.newContext({ hasTouch: true, viewport: { width: 400, height: 800 } });
    const p = await ctx.newPage(); let loads = 0; p.on('load', () => loads++);
    await p.route('https://www.instagram.com/**', r => r.fulfill({ contentType: 'text/html', body }));
    await p.addInitScript(init); await p.goto(url); await p.waitForTimeout(300);
    if (before) await p.evaluate(before);
    const cdp = await ctx.newCDPSession(p);
    const tp = (type, y) => cdp.send('Input.dispatchTouchEvent', { type, touchPoints: type === 'touchEnd' ? [] : [{ x: 200, y }] });
    await tp('touchStart', 200); for (let y = 220; y <= 400; y += 20) await tp('touchMove', y); await tp('touchEnd');
    await p.waitForTimeout(1500); await ctx.close(); return loads;
  }
  if (await drag('https://www.instagram.com/', '<html><body><main><article>post</article><div style="height:3000px"></div></main></body></html>') !== 2) { fail++; console.log('feed: pull down at the top did not refresh'); }
  if (await drag('https://www.instagram.com/reels/', '<html><body style="margin:0;overflow:hidden"><div id=sc style="height:800px;overflow-y:scroll">' + '<div style="height:800px">reel</div>'.repeat(5) + '</div></body></html>', () => document.getElementById('sc').scrollTop = 1600) !== 1) { fail++; console.log('reels: scrolling up reloaded the page'); }
  if (await drag('https://www.instagram.com/', '<html><body><div role=dialog style="height:600px;overflow-y:auto"><div style="height:2000px">comments</div></div></body></html>', () => document.querySelector('[role=dialog]').scrollTop = 500) !== 1) { fail++; console.log('dialog: scrolling comments up reloaded the page'); }
  await b.close();
  console.log(fail ? fail + ' FAILED' : 'pull to refresh OK');
  process.exit(fail ? 1 : 0);
})();
