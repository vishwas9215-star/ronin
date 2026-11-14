// NODE_PATH=$(npm root -g) node tools/uitest/newtab.js <outdir> — small quick links on the new tab page.
const { chromium } = require('playwright'); const fs = require('fs');
const out = process.argv[2] || '/tmp';
(async () => {
  const data = { k: 'KEY123', sections: [
    { id: 'Quick links', title: 'Quick links', links: [{ n: 'YouTube', u: 'https://www.youtube.com/' }, { n: 'YouTube Music', u: 'https://music.youtube.com/' }, { n: 'Gmail', u: 'https://mail.google.com/' }, { n: 'DIKSHA', u: 'https://diksha.gov.in/' }, { n: 'Wikipedia', u: 'https://hi.wikipedia.org/' }] },
    { id: 'School', title: 'स्कूल / School', links: [{ n: 'मानव संपदा', u: 'https://ehrms.upsdc.gov.in/' }, { n: 'प्रेरणा', u: 'https://prernaup.in/' }, { n: 'UDISE+ All Modules', u: 'https://udiseplus.gov.in/#/en/page/oldlink/109' }] },
    { id: 'Trading', title: 'Trading', links: [{ n: 'Ronin Terminal', u: 'http://127.0.0.1:8791/' }, { n: 'Dhan', u: 'https://web.dhan.co/' }, { n: 'TradingView', u: 'https://in.tradingview.com/chart/' }, { n: 'Fyers', u: 'https://trade.fyers.in/' }] },
    { id: 'Video calls', title: 'Video calls', links: [{ n: 'Google Meet', u: 'https://meet.google.com/' }, { n: 'WhatsApp Web', u: 'https://web.whatsapp.com/' }] }] };
  const js = fs.readFileSync('cmd/ronin/ui/quicklinks.js', 'utf8');
  const html = fs.readFileSync('cmd/ronin/ui/newtab.html', 'utf8').replace(/{{CONTAINER}}/g, 'PERSONAL').replace(/{{NONCE}}/g, 'N1').replace(/{{THEME}}/g, '')
    .replace('{{HEADING}}', 'Ronin Browser').replace('{{SUBTITLE}}', 'Safe, private and fast.').replace('{{NOTE}}', '')
    .replace('{{SECTIONS}}', '<div id="ql"></div><script nonce="N1">window.RONIN_QL=' + JSON.stringify(data) + ';</script><script nonce="N1">' + js + '</script>');
  const b = await chromium.launch(); let fail = 0; const bad = m => { console.log('FAIL', m); fail++; };
  for (const [w, h, name] of [[1366, 768, 'laptop'], [800, 1280, 'tablet-portrait']]) {
    const p = await b.newPage({ viewport: { width: w, height: h } }); const errs = []; p.on('pageerror', e => errs.push(e.message));
    let nav = null; await p.route('https://ronin.newtab/**', r => { nav = r.request().url(); r.abort(); });
    await p.setContent(html);
    const chips = await p.$$('#ql .chip');
    if (chips.length !== 14) bad(name + ' chips ' + chips.length);
    for (const c of chips) { const bb = await c.boundingBox(); if (bb.height > 48 || bb.width > 260) bad(name + ' chip too big ' + JSON.stringify(bb)); if (bb.height < 40) bad(name + ' chip too small to tap'); }
    if ((await p.textContent('#ql')).includes('https://')) bad('addresses shown');
    if (await p.isVisible('#ql .x')) bad('remove buttons shown before Edit');
    // whole page fits without scrolling on the laptop
    const full = await p.evaluate(() => document.documentElement.scrollHeight);
    if (name === 'laptop' && full > h) bad('laptop page needs scrolling: ' + full);
    await p.screenshot({ path: out + '/newtab-' + name + '.png' });
    await p.click('text=Edit links');
    if (!(await p.isVisible('#ql .x'))) bad('edit shows no remove');
    await p.click('#ql .chips[data-s="Trading"] .add');
    await p.fill('#ql form.new input >> nth=0', 'Zerodha'); await p.fill('#ql form.new input >> nth=1', 'kite.zerodha.com');
    await p.screenshot({ path: out + '/newtab-' + name + '-edit.png' });
    await p.click('#ql form.new button'); await p.waitForTimeout(300);
    if (nav !== 'https://ronin.newtab/add?k=KEY123&s=Trading&n=Zerodha&u=kite.zerodha.com') bad('add url ' + nav);
    nav = null; await p.setContent(html); await p.click('text=Edit links');
    await p.click('#ql .chip:has-text("Fyers") .x'); await p.waitForTimeout(300);
    if (nav !== 'https://ronin.newtab/del?k=KEY123&u=' + encodeURIComponent('https://trade.fyers.in/')) bad('del url ' + nav);
    if (errs.length) bad('JS errors ' + errs);
    await p.close();
  }
  console.log(fail ? fail + ' FAILED' : 'new tab quick links UI OK');
  await b.close(); process.exit(fail ? 1 : 0);
})();
