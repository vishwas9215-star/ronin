// node tools/uitest/run.js <outdir> — renders the toolbar and every panel in Chromium.
const { chromium } = require('playwright');
const fs = require('fs'), path = require('path');
const out = process.argv[2];
const tpl = fs.readFileSync(path.join(__dirname, '../../cmd/ronin/ui/toolbar.html'), 'utf8');
(async () => {
  const b = await chromium.launch();
  let fail = 0;
  for (const cont of ['PERSONAL', 'BANKING', 'TRADING', 'KIDS', 'CALLS']) {
    const p = await b.newPage({ viewport: { width: 1280, height: 720 } });
    const errors = [], posted = [];
    p.on('pageerror', e => errors.push(e.message));
    await p.exposeFunction('__post', m => posted.push(JSON.parse(m)));
    await p.addInitScript(() => { Object.defineProperty(window, 'chrome', { value: { webview: { postMessage: m => window.__post(m) } }, configurable: true }); });
    await p.setContent(tpl.replace(/{{CONTAINER}}/g, cont).replace('<script>', '<script>if(!window.chrome)window.chrome={};window.chrome.webview={postMessage:m=>window.__post(m)};</script><script>'));
    const tabs = [
      { id: 1, title: 'Horror Short Film | ALTER - YouTube', url: 'https://www.youtube.com/watch?v=3_DQmfFjTIU', active: true, canBack: true, secure: true, video: true, bankHint: true },
      { id: 3, title: 'BANKNIFTY 59,741 ▼ −1.51%', url: 'https://in.tradingview.com/chart/', active: false, secure: true },
      { id: 2, title: 'NIFTY 25,044 ▲ +0.23%', url: 'https://in.tradingview.com/chart/', active: false, secure: true },
    ];
    await p.evaluate(([t, c]) => window.ronin.state({ container: c, tabs: t, grid: c === 'TRADING' ? 2 : 0, vpn: c === 'PERSONAL' ? 'connected' : '' }), [tabs, cont]);
    await p.evaluate(() => window.ronin.bar({ tab: 0, kind: 'info', text: 'Downloading video: Horror Short Film - 42%', actions: [{ label: 'Cancel', cmd: 'dlcancel', url: '1' }] }));
    await p.screenshot({ path: `${out}/${cont}-toolbar.png`, clip: { x: 0, y: 0, width: 1280, height: 130 } });
    const overlays = [
      ['menu', null],
      ['fake', { tab: 1, url: 'https://sbi-kyc-update.com/', host: 'sbi-kyc-update.com', brand: 'SBI' }],
      ['remote', { tools: ['AnyDesk', 'TeamViewer'] }],
      ['locked', { mins: 5 }],
      ['blocked', { tab: 1, host: 'example.xxx', mode: 'KIDS', reason: 'This site is not suitable for children.' }],
      ['download', { title: 'Horror Short Film "Backstroke" | ALTER' }],
      ['downloads', { jobs: [{ id: 3, title: 'BANKNIFTY 59,741 ▼ −1.51%', url: 'https://in.tradingview.com/chart/', active: false, secure: true },
      { id: 2, title: 'Song', state: 'downloading', percent: 42 }, { id: 1, title: 'Film', state: 'done', percent: 100, file: 'Film.mp4' }, { id: 3, title: 'X', state: 'failed', error: 'This video is private.' }] }],
      ['ai', { step: 'preview', task: 'scam', provider: 'gemini', haveKey: true, chars: 5400, sample: 'Aapka profit ready hai, 20% charge bhejiye...', scam: 6, cost: '' }],
      ['ai', { step: 'answer', task: 'summary', text: '• पहला बिंदु\n• दूसरा बिंदु' }],
      ['ai', { step: 'nokey' }],
      ['pin', { error: 'Wrong PIN.' }],
      ['settings', { banks: ['ib.mycoop.co.in'], trusted: [], remember: false, idle: 5, version: '3.1.0', tradingSites: ['broker.example.com'], adblockOff: [],
        vpn: { on: true, name: 'NL-FREE#12', haveConf: true, active: false, state: 'off', needRestart: true },
        ai: { provider: 'gemini', model: '', baseUrl: '', cap: 20, spent: '0.00', keys: { gemini: 'AIza...xyz1' } }, kids: { level: cont === 'KIDS' ? 'junior' : '', sites: [] } }],
      ['health', { version: '3.1.0', engine: '142.0.3595.53', engineNote: 'Updated by Microsoft.', updates: 'Not set up.', vpn: 'connected', parts: [{ name: 'list:adguard-dns', ok: true, when: '11 Nov 09:00' }, { name: 'yt-dlp', ok: false, problem: 'no internet' }] }],
    ];
    for (const [kind, data] of overlays) {
      if (kind === 'menu') await p.click('#menuBtn');
      else await p.evaluate(([k, d]) => window.ronin.overlay(Object.assign({ kind: k }, d)), [kind, data]);
      await p.waitForTimeout(80);
      const name = `${cont}-${kind}${data && data.step ? '-' + data.step : ''}.png`;
      if (cont === 'PERSONAL' || kind === 'menu' || kind === 'settings') await p.screenshot({ path: `${out}/${name}` });
      await p.evaluate(() => window.ronin.closeOverlay());
    }
    // a few clicks that must send messages
    await p.evaluate(() => window.ronin.overlay({ kind: 'download', title: 't' }));
    await p.click('text=720p (MP4)');
    if (cont === 'TRADING') await p.click('#gridBtn');
    const cmds = posted.map(m => m.cmd);
    if (!cmds.includes('ready')) { console.log(cont, 'no ready message'); fail++; }
    if (!cmds.includes('download')) { console.log(cont, 'download click not sent'); fail++; }
    if (cont === 'TRADING' && !cmds.includes('grid')) { console.log('grid click not sent'); fail++; }
    if (errors.length) { console.log(cont, 'JS errors:', errors); fail++; }
    await p.close();
  }
  await b.close();
  console.log(fail ? fail + ' problem(s)' : 'toolbar UI OK');
  process.exit(fail ? 1 : 0);
})();
