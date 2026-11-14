// NODE_PATH=$(npm root -g) node tools/uitest/listen.js out.png — Reader "Listen" (read aloud).
// The Windows voices are replaced by a fake speaker that records what would be spoken.
const { chromium } = require('playwright'); const fs = require('fs');
const reader = fs.readFileSync('cmd/ronin/ui/reader.js', 'utf8');
const article = '<html><body><nav>menu</nav><article><h1>PCS नोट्स</h1>' +
  '<p>' + 'भारत का संविधान 26 जनवरी 1950 को लागू हुआ। यह दुनिया का सबसे बड़ा लिखित संविधान है। '.repeat(4) + '</p>' +
  '<p>' + 'The Preamble describes India as a sovereign socialist secular democratic republic. '.repeat(3) + '</p>' +
  '<p>' + 'मौलिक अधिकार भाग तीन में दिए गए हैं और ये न्यायालय द्वारा लागू कराए जा सकते हैं। '.repeat(3) + '</p>' +
  '</article></body></html>';
const fake = withHindi => `(() => {
  const said = []; window.__said = said; let queue = [], busy = false;
  const voices = [{ name: 'English India', lang: 'en-IN' }].concat(${withHindi} ? [{ name: 'Hindi', lang: 'hi-IN' }] : []);
  function pump() { if (busy || !queue.length || S.paused) return; busy = true; const u = queue.shift();
    said.push({ text: u.text, lang: u.lang, voice: u.voice && u.voice.name, rate: u.rate });
    setTimeout(() => { busy = false; if (u.__dead) return; u.onend && u.onend(); pump(); }, 120); }
  const S = { paused: false, speaking: false, getVoices: () => voices, onvoiceschanged: null,
    speak(u) { queue.push(u); pump(); }, cancel() { queue.forEach(u => u.__dead = true); queue = []; busy = false; },
    pause() { this.paused = true; }, resume() { this.paused = false; pump(); } };
  window.SpeechSynthesisUtterance = function (t) { this.text = t; };
  Object.defineProperty(window, 'speechSynthesis', { value: S });
})()`;
(async () => {
  const b = await chromium.launch(); let fail = 0; const bad = m => { console.log('FAIL', m); fail++; };
  let p = await b.newPage({ viewport: { width: 1000, height: 700 } });
  const errs = []; p.on('pageerror', e => errs.push(e.message));
  await p.setContent(article); await p.evaluate(fake(true));
  await p.evaluate('window.__roninListen=1;' + reader);
  await p.waitForTimeout(300);
  await p.screenshot({ path: process.argv[2] });
  await p.waitForTimeout(2600);
  let said = await p.evaluate(() => window.__said);
  if (!said.length) bad('nothing spoken');
  else {
    if (said[0].lang !== 'hi-IN' || said[0].voice !== 'Hindi') bad('title not in Hindi voice ' + JSON.stringify(said[0]));
    const en = said.find(s => /Preamble/.test(s.text));
    if (!en || en.lang !== 'en-IN') bad('English paragraph not in English voice');
    if (said.some(s => s.text.length > 260)) bad('a piece is too long for the voice');
    if (!said.some(s => /मौलिक/.test(s.text))) bad('did not read to the end');
  }
  if ((await p.textContent('#ronin-reader .say .main')).indexOf('Listen') < 0) bad('button not back to Listen at the end');
  // tap a paragraph -> starts there; speed button changes rate
  await p.evaluate(() => { window.__said.length = 0; });
  await p.click('#ronin-reader .say button:nth-child(3)'); // 1.25x
  await p.click('text=The Preamble describes'); await p.waitForTimeout(200);
  said = await p.evaluate(() => window.__said);
  if (!said.length || !/Preamble/.test(said[0].text) || said[0].rate !== 1.25) bad('tap paragraph / speed: ' + JSON.stringify(said[0]));
  // pause stops new speech
  await p.click('#ronin-reader .say .main'); const n1 = (await p.evaluate(() => window.__said.length)); await p.waitForTimeout(400);
  if ((await p.evaluate(() => window.__said.length)) > n1 + 1) bad('pause did not stop');
  if (errs.length) bad('JS errors ' + errs);
  // no Hindi voice -> clear note how to add it
  p = await b.newPage(); await p.setContent(article); await p.evaluate(fake(false));
  await p.evaluate('window.__roninListen=1;' + reader); await p.waitForTimeout(200);
  if (!/Add voices/.test(await p.textContent('#ronin-reader .say .note'))) bad('no note about the missing Hindi voice');
  console.log(fail ? fail + ' FAILED' : 'reader listen OK');
  await b.close(); process.exit(fail ? 1 : 0);
})();
