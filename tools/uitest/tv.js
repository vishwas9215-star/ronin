// Checks the TradingView promo remover on a fake page.
const { chromium } = require('playwright');
const fs = require('fs'), path = require('path');
const src = fs.readFileSync(path.join(__dirname, '../../cmd/ronin/ui/inject_adblock.js'), 'utf8');
(async () => {
  const b = await chromium.launch();
  const p = await b.newPage();
  await p.route('**/*', r => r.fulfill({ contentType: 'text/html', body: `<body>
    <button id="up">Upgrade</button><button id="trade">Trade</button>
    <div id="overlap-manager-root">
      <div id="ad"><div class="backdrop"></div><h2>Several charts per tab</h2><p>Your plan has 1 chart per tab limit.</p><button>Upgrade now</button></div>
      <div id="search"><input placeholder="Search symbol"><p>Upgrade now to see more</p></div>
      <div id="menu"><p>Indicators, metrics and strategies</p></div>
    </div>
    <div data-dialog-name="gopro" id="gp">x</div></body>` }));
  await p.addInitScript(src);
  await p.goto('https://in.tradingview.com/chart/');
  await p.waitForTimeout(1600);
  const r = await p.evaluate(() => ({ ad: !!document.getElementById('ad'), gp: !!document.getElementById('gp'),
    search: !!document.getElementById('search'), menu: !!document.getElementById('menu'),
    up: getComputedStyle(document.getElementById('up')).display, trade: getComputedStyle(document.getElementById('trade')).display }));
  await b.close();
  const ok = !r.ad && !r.gp && r.search && r.menu && r.up === 'none' && r.trade !== 'none';
  console.log(ok ? 'tradingview promo rules OK' : 'PROBLEM ' + JSON.stringify(r));
  process.exit(ok ? 0 : 1);
})();
