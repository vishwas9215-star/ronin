// NODE_PATH=$(npm root -g) node tools/uitest/extras.js <outdir> — History, page size, Study time panels.
const { chromium } = require('playwright'); const fs = require('fs');
const out = process.argv[2] || '/tmp';
(async () => {
  const html = fs.readFileSync('cmd/ronin/ui/toolbar.html', 'utf8').replace(/{{CONTAINER}}/g, 'PERSONAL').replace(/{{PANEL}}/g, '0')
    .replace('<script>', '<script>window.chrome={webview:{postMessage:m=>window.__post(m)}};</script><script>');
  const b = await chromium.launch(); const p = await b.newPage({ viewport: { width: 1100, height: 700 } });
  let fail = 0; const bad = m => { console.log('FAIL', m); fail++; };
  const got = []; const errs = []; p.on('pageerror', e => errs.push(e.message));
  await p.exposeFunction('__post', m => got.push(JSON.parse(m)));
  await p.setContent(html);
  const last = c => got.filter(m => m.cmd === c).pop();
  // page size pill
  await p.evaluate(() => ronin.state({ container: 'PERSONAL', tabs: [{ id: 1, title: 'Wikipedia', url: 'https://hi.wikipedia.org/', active: true, zoom: 125 }],
    focus: { on: false } }));
  if ((await p.textContent('#zoomPill')) !== '125%' || !(await p.isVisible('#zoomPill'))) bad('zoom pill');
  await p.click('#zoomPill'); if (!last('zoom') || last('zoom').number !== 0) bad('zoom pill click');
  // menu: size row, study time, history
  await p.click('#menuBtn');
  await p.click('.zoomrow button[title^="Bigger"]'); if (last('zoom').number !== 1) bad('menu bigger');
  await p.evaluate(() => ronin.state({ container: 'PERSONAL', tabs: [{ id: 1, title: 'Wikipedia', url: 'https://hi.wikipedia.org/', active: true, zoom: 150 }], focus: { on: false } }));
  if (!(await p.textContent('.zoomrow span')).includes('150%')) bad('menu size not updated while open');
  await p.screenshot({ path: out + '/menu.png' });
  await p.evaluate(() => ronin.closeOverlay()); await p.click('#menuBtn'); await p.screenshot({ path: out + '/menu.png' });
  await p.click('text=Study time: pause time-wasting sites for 1 hour');
  if (!last('focusnow') || last('focusnow').number !== 60) bad('study 1 hour');
  await p.evaluate(() => ronin.state({ container: 'PERSONAL', tabs: [{ id: 1, title: 'W', url: 'https://x/', active: true, zoom: 100 }],
    focus: { on: true, manual: true, until: '10:45' } }));
  if (await p.isVisible('#zoomPill')) bad('pill shown at 100%');
  await p.click('#menuBtn'); await p.click('text=Study time until 10:45 - stop now');
  if (last('focusnow').number !== 0) bad('stop study');
  await p.click('#menuBtn'); await p.click('text=History   (Ctrl+H)'); if (!last('history')) bad('history menu');
  // history panel
  await p.evaluate(() => ronin.overlay({ kind: 'history', q: '', off: false, list: [
    { title: 'दृष्टि IAS - करेंट अफेयर्स', url: 'https://www.drishtiias.com/hindi', when: '14 Nov 09:10' },
    { title: 'YouTube', url: 'https://www.youtube.com/', when: '14 Nov 08:55' }] }));
  await p.waitForTimeout(100); await p.screenshot({ path: out + '/history.png' });
  await p.keyboard.type('drishti'); await p.waitForTimeout(300);
  if (!last('histsearch') || last('histsearch').text !== 'drishti') bad('history search');
  await p.evaluate(() => ronin.historyList({ q: 'drishti', list: [{ title: 'दृष्टि IAS', url: 'https://www.drishtiias.com/hindi', when: '14 Nov 09:10' }] }));
  if ((await p.$$('#ov.show [data-hist="1"] > div')).length !== 1) bad('history list update');
  await p.click('#ov.show [data-hist="1"] button[title="Remove from history"]');
  if (!last('histremove') || last('histremove').url !== 'https://www.drishtiias.com/hindi' || last('histremove').text !== 'drishti') bad('remove');
  await p.click('text=Delete last hour'); if (last('histclear').text !== 'hour') bad('clear hour');
  await p.click('text=Stop keeping history'); if (last('nohistory').value !== true) bad('stop history');
  await p.click('#ov.show [data-hist="1"] button[title^="https://"]').catch(() => {});
  if (!last('histopen')) bad('open from history');
  await p.evaluate(() => ronin.closeOverlay());
  // study-time page
  await p.evaluate(() => ronin.overlay({ kind: 'focus', tab: 1, host: 'www.instagram.com', url: 'https://www.instagram.com/reel/1', until: '21:00' }));
  await p.screenshot({ path: out + '/focus.png' });
  await p.click('text=Open for 5 minutes');
  if (!last('focusallow') || last('focusallow').url !== 'https://www.instagram.com/reel/1' || last('focusallow').id !== 1) bad('focus allow');
  // settings: study time
  await p.evaluate(() => ronin.overlay({ kind: 'settings', banks: [], trusted: [], idle: 5, vpn: {}, ai: { keys: {} }, kids: {},
    focus: { on: false, from: '18:00', to: '21:00', sites: 'instagram.com\nyoutube.com/shorts' } }));
  await p.check('text=Study time every day');
  await p.fill('input[type=time] >> nth=0', '19:30');
  await p.click('text=Save study time');
  const fsx = last('focusset');
  if (!fsx || fsx.value !== true || fsx.text !== '19:30|21:00|instagram.com\nyoutube.com/shorts') bad('focus settings ' + JSON.stringify(fsx));
  // passwords panel
  await p.evaluate(() => ronin.overlay({ kind: 'passwords', count: 2, list: [] }));
  await p.click('text=Show passwords'); if (!last('pwshow')) bad('pwshow');
  await p.evaluate(() => ronin.overlay({ kind: 'passwords', count: 2, list: [
    { origin: 'https://drishtiias.com', user: 'vishwas', pass: 'Secret#1', note: '' },
    { origin: 'https://accounts.google.com', user: 'me@gmail.com', pass: 'g00gle', note: '' }] }));
  if ((await p.textContent('#ov.show')).includes('Secret#1')) bad('password visible before Show');
  await p.click('#ov.show li:has-text("drishtiias") >> text=Show');
  if (!(await p.textContent('#ov.show')).includes('Secret#1')) bad('Show did not reveal');
  await p.screenshot({ path: out + '/passwords.png' });
  await p.click('#ov.show li:has-text("drishtiias") >> text=Remove');
  if (!last('pwremove') || last('pwremove').url !== 'https://drishtiias.com\nvishwas') bad('pwremove ' + JSON.stringify(last('pwremove')));
  await p.click('text=Export CSV file'); if (!last('pwexport')) bad('export');
  await p.click('text=Import CSV file'); if (!last('pwimport')) bad('import');
  await p.evaluate(() => ronin.closeOverlay());
  await p.click('#menuBtn'); await p.click('text=Passwords (saved, import, export)'); if (!last('passwords')) bad('passwords menu');
  await p.evaluate(() => ronin.overlay({ kind: 'bookmarks', list: [{ title: 'Wiki', url: 'https://hi.wikipedia.org/' }] }));
  await p.click('text=Export bookmarks file'); if (!last('bmexport')) bad('bookmark export');
  await p.click('text=Import from a file'); if (!last('bmimport')) bad('bookmark import');
  // Data -> Excel + PDF in the menu (only after it is switched on for the site)
  await p.evaluate(() => ronin.closeOverlay());
  await p.evaluate(() => ronin.state({ container: 'PERSONAL', tabs: [{ id: 1, title: 'UDISE', url: 'https://udiseplus.gov.in/x', active: true, zoom: 100 }], focus: { on: false }, dataSite: false }));
  await p.click('#menuBtn');
  if (await p.isVisible('text=Data → Excel + PDF (')) bad('export item shown before switching on');
  await p.click('text=Data बटन इस साइट पर चालू करें'); if (!last('datasite')) bad('datasite toggle');
  await p.evaluate(() => ronin.state({ container: 'PERSONAL', tabs: [{ id: 1, title: 'UDISE', url: 'https://udiseplus.gov.in/x', active: true, zoom: 100 }], focus: { on: false }, dataSite: true }));
  await p.click('#menuBtn'); await p.click('text=Data → Excel + PDF ('); if (!last('dataexport')) bad('dataexport');
  await p.evaluate(() => ronin.closeOverlay());
  // dangerous site: red page, only "Go back" (no way to open it from here)
  await p.evaluate(() => ronin.overlay({ kind: 'blocked', tab: 1, host: 'ronin-phishing-test.com', mode: 'DANGER' }));
  const dt = await p.textContent('#ov.show');
  if (!dt.includes('ronin-phishing-test.com') || !dt.includes('खतरनाक') || !dt.includes('Go back')) bad('danger page text');
  if ((await p.$$('#ov.show button')).length !== 1) bad('danger page must have only Go back');
  await p.screenshot({ path: out + '/danger.png' });
  await p.click('#ov.show button'); if (await p.isVisible('#ov.show')) bad('go back did not close');
  // settings: add a safe site that was stopped by mistake
  await p.evaluate(() => ronin.overlay({ kind: 'settings', banks: [], trusted: [], idle: 5, vpn: {}, ai: { keys: {} }, kids: {}, focus: { on: false, from: '18:00', to: '21:00', sites: '' } }));
  await p.fill('input[placeholder^="A safe site"]', 'my-coop-site.in'); await p.click('#ov.show button:text-is("Add")');
  if (!last('trustadd') || last('trustadd').url !== 'my-coop-site.in') bad('trustadd ' + JSON.stringify(last('trustadd')));
  if (errs.length) bad('JS errors ' + errs);
  console.log(fail ? fail + ' FAILED' : 'history, page size, study time, danger page UI OK');
  await b.close(); process.exit(fail ? 1 : 0);
})();
