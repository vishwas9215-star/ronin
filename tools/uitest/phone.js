// NODE_PATH=$(npm root -g) node tools/uitest/phone.js [outdir] — Ronin Messages and Ronin Calls pages with a
// made-up phone: tabs sort SMS, scam SMS warned, setup / restricted-settings help, send, block, keypad.
const { chromium } = require('playwright'); const fs = require('fs'); const path = require('path');
(async () => {
  const out = process.argv[2] || '/tmp/ui'; fs.mkdirSync(out, { recursive: true });
  const dir = 'cmd/ronin/ui/tools';
  const lists = fs.readFileSync('internal/safelinks/lists.json', 'utf8'), rules = fs.readFileSync('internal/safelinks/scamrules.json', 'utf8');
  const b = await chromium.launch({ args: ['--lang=hi-IN'] }); let fail = 0; const bad = m => { console.log('FAIL', m); fail++; };
  const serve = async p => p.route('https://ronin.home/tools/**', r => { const f = path.join(dir, new URL(r.request().url()).pathname.replace('/tools/', '')); r.fulfill({ body: fs.readFileSync(f), contentType: /\.js$/.test(f) ? 'text/javascript' : 'text/html; charset=utf-8' }); });
  // the phone side, made up: answers like MessagesActivity / CallsActivity
  const phone = (state) => `window.__roninLang=${JSON.stringify(state.lang || 'hi')};window.__roninLinkLists=${lists};window.__roninScamRules=${rules};
    window.__log=[]; const ST=${JSON.stringify(state)};
    const T=ST.threads||[{thread:1,address:'+919812300011',name:'',snippet:'Mujhe urgent 10000 rupaye ki zarurat hai',date:Date.now(),unread:1,count:1,cat:'scam',level:'stop',reasons:['need']},
      {thread:2,address:'JM-SBIINB-S',name:'',snippet:'482913 is your OTP',date:Date.now()-1000,unread:1,count:2,cat:'work',level:'',reasons:[]},
      {thread:3,address:'VM-SHOPPR-P',name:'',snippet:'Big sale',date:Date.now()-2000,unread:0,count:1,cat:'ads',level:'',reasons:[]},
      {thread:4,address:'+919800000000',name:'माँ',snippet:'खाना खा लिया?',date:Date.now()-3000,unread:0,count:5,cat:'work',level:'',reasons:[]}];
    const M=ST.msgs||{1:[{id:1,body:'Mujhe urgent 10000 rupaye ki zarurat hai, hospital me hoon, is QR par bhej do',date:Date.now(),type:1,level:'stop',reasons:['किसी जानने वाले के नाम से पैसे']}],
      4:[{id:3,body:'हाँ, खा लिया',date:Date.now()-5000,type:2,level:'',reasons:[]},{id:2,body:'खाना खा लिया?',date:Date.now()-9000,type:1,level:'',reasons:[]}],
      2:[{id:5,body:'482913 is your OTP for login. Check https://kotk.in/KOTAKD/abc or kotk.in/x',date:Date.now()-1000,type:1,level:'',reasons:[]},
         {id:4,body:'111222 is your OTP for login',date:Date.now()-60000,type:1,level:'',reasons:[]}]};
    window.roninPhone={postMessage(s){const m=JSON.parse(s);window.__log.push(m);let d=null;
      switch(m.op){case 'state':d=Object.assign({},ST,window.__sos||{});break;case 'save':window.__sos=Object.assign(window.__sos||{},{contacts:m.contacts,name:m.name});d=true;break;
      case 'start':d=!!(window.__sos&&window.__sos.contacts&&window.__sos.contacts.length);if(d)window.__sos.running=true;break;case 'stop':window.__sos.running=false;d=true;break;case 'threads':d=T;break;case 'messages':d=M[m.thread]||[];break;case 'makeDefault':d={ok:ST.grant};if(ST.grant)ST.isDefault=true;break;
      case 'savedSms':d=window.__saved||[];break;case 'saveSms':window.__saved=(window.__saved||[]).concat([m.msg]);d=window.__saved;break;case 'unsaveSms':window.__saved=(window.__saved||[]).filter(x=>x.key!==m.key);d=window.__saved;break;case 'send':d=true;break;case 'block':d=true;break;case 'blocked':d=['+91999'];break;case 'watcher':d=false;break;case 'thread':d=9;break;
      case 'recent':d=[{number:'+923001234567',name:'',date:Date.now(),type:3,label:ST.lang==='en'?'🌍 Foreign number':'🌍 विदेश का नंबर',level:'risk'},{number:'+919876501234',name:'',date:Date.now(),type:1,label:'🛠 This number was in your Airtel SMS',level:'ok'},{number:'+918045671234',name:'',date:Date.now(),type:1,label:'👤 Not in contacts',level:'info'},{number:'+919984923355',name:'',sim:'Atal Mishra',date:Date.now(),type:2,label:'📞 You called them before',level:'known'},{number:'+919800000000',name:ST.lang==='en'?'Mom':'माँ',date:Date.now(),type:1,label:''},{number:'+919811111112',name:'Ravi',date:Date.now(),type:2,label:''}];break;
      case 'contacts':d=[{name:'माँ',number:'+919800000000'},{name:'Ramesh',number:'+919811111111'}];break;case 'label':d='👤 अनजान नंबर';break;case 'nameVoice':d='Airtel wale bhaiya';break;
      case 'rec':d={auto:false,access:false,skip:window.__skip||[],mic:true};break;case 'skip':window.__skip=(window.__skip||[]).filter(x=>x!==m.number);if(m.on)window.__skip.push(m.number);d=window.__skip;break;
      case 'list':d=[{app:'WhatsApp',chat:'Family',who:'Mama',text:'Kal aa rahe ho?',t:Date.now()-9000},{app:'WhatsApp',chat:'Rahul',who:'Rahul',text:'Sorry, galat message',t:Date.now()-5000,gone:true}];break;
      case 'recordings':d=[{id:1,name:'Call_Ramesh_919811111111_20261114_2110.m4a',date:Date.now(),size:204800}];break;
      default:d=true}
      setTimeout(()=>this.onmessage&&this.onmessage({data:JSON.stringify({id:m.id,data:d})}),5);}};`;
  // 1. not yet the SMS app, Android refuses (restricted settings): the steps are shown
  const p = await b.newPage({ viewport: { width: 412, height: 860 } });
  await serve(p); await p.addInitScript(phone({ isDefault: false, read: false, grant: false }));
  await p.goto('https://ronin.home/tools/messages.html'); await p.waitForSelector('#setup');
  await p.click('#makeDefault'); await p.waitForTimeout(200);
  if (!await p.$('text=Allow restricted settings')) bad('restricted-settings help missing');
  await p.screenshot({ path: path.join(out, 'messages-setup.png') });
  // 2. the SMS app: tabs sort the messages
  const p2 = await b.newPage({ viewport: { width: 412, height: 860 } });
  await serve(p2); await p2.addInitScript(phone({ isDefault: true, read: true, contacts: true }));
  await p2.goto('https://ronin.home/tools/messages.html'); await p2.waitForSelector('.th');
  if (!await p2.$('#readAll')) bad('Mark all read missing');
  await p2.click('#readAll'); await p2.waitForTimeout(100);
  if (!(await p2.evaluate(() => window.__log.some(m => m.op === 'readAll')))) bad('Mark all read not asked');
  const work = await p2.$$eval('.th', a => a.map(x => x.dataset.thread));
  if (!(await p2.$eval('.th[data-thread="2"] b', e => e.textContent)).startsWith('State Bank of India')) bad('SBI name');
  if (work.join() !== '2,4') bad('काम के tab: ' + work);
  // 3.13: the four boxes are a strip at the TOP; the newest OTP is pinned above the list, one tap copies it
  { const nt = await p2.$eval('.nav', n => n.getBoundingClientRect().top); if (nt > 160) bad('message boxes are not at the top: ' + nt); }
  if (!await p2.$('#otpCard:has-text("482913")')) bad('new OTP not pinned on top');
  else { await p2.click('#otpCopy'); await p2.waitForTimeout(100); if (!(await p2.evaluate(() => window.__log.some(m => m.op === 'copy' && m.text === '482913')))) bad('pinned OTP copy'); }
  { const hgt = await p2.$eval('.th', e => e.getBoundingClientRect().height); if (hgt < 72) bad('message rows too small: ' + hgt); }
  await p2.screenshot({ path: path.join(out, 'messages-work.png') });
  { const av = await p2.$$eval('.av:not(.brand)', a => a.map(x => [getComputedStyle(x).color, getComputedStyle(x).backgroundColor, x.textContent]));
    if (av.some(([c, bg, t]) => c !== 'rgb(255, 255, 255)' || bg === 'rgb(51, 65, 85)' || t === '+')) bad('dark photo circle in Messages ' + JSON.stringify(av)); }
  await p2.click('[data-tab=scam]'); await p2.waitForTimeout(100);
  if ((await p2.$$eval('.th', a => a.map(x => x.dataset.thread))).join() !== '1') bad('ठगी tab');
  await p2.click('.th[data-thread="1"]'); await p2.waitForSelector('.warn');
  if (!await p2.$('a[href="tel:1930"]')) bad('no 1930 on a scam conversation');
  // owner 21 Nov 2026: "Report on Chakshu" sends the scam SMS itself (Ronin Browser fills the form with it), not a bare link
  await p2.click('#chakshu'); await p2.waitForTimeout(100);
  { const c = await p2.evaluate(() => window.__log.filter(m => m.op === 'chakshu').pop());
    if (!c || c.address !== '+919812300011' || !/urgent 10000/.test(c.body || '') || !(c.date > 0)) bad('Chakshu report without the message ' + JSON.stringify(c)); }
  if (await p2.$('.b a')) bad('a link inside an SMS is tappable');
  await p2.screenshot({ path: path.join(out, 'messages-scam.png') });
  p2.on('dialog', d => d.accept());
  await p2.click('#block'); await p2.waitForTimeout(100);
  if (!(await p2.evaluate(() => window.__log.some(m => m.op === 'block' && m.address === '+919812300011')))) bad('block not asked');
  // OTP big and blue, one tap copies only the digits; links in a normal SMS open in Ronin
  await p2.click('text=← वापस'); await p2.click('[data-tab=work]'); await p2.click('.th[data-thread="2"]'); await p2.waitForSelector('.b');
  if ((await p2.$$('.latest')).length !== 1 || (await p2.$$('.b.oldotp')).length !== 1) bad('latest OTP not marked');
  const lt = await p2.$eval('.latest', e => e.nextElementSibling.textContent); if (!lt.includes('482913')) bad('Latest is on the wrong OTP: ' + lt);
  const otp = await p2.$('.b:not(.oldotp) button.otp'); if (!otp) bad('OTP not marked');
  else { await otp.click(); await p2.waitForTimeout(100); if (!(await p2.evaluate(() => window.__log.some(m => m.op === 'copy' && m.text === '482913')))) bad('OTP copy'); }
  const lnks = await p2.$$('a.lnk'); if (lnks.length !== 2) bad('links not tappable: ' + lnks.length);
  else { await lnks[1].click(); await p2.waitForTimeout(100); if (!(await p2.evaluate(() => window.__log.some(m => m.op === 'open' && m.url === 'https://kotk.in/x')))) bad('link open'); }
  await p2.screenshot({ path: path.join(out, 'messages-otp.png') });
  // ⭐ save an SMS: it shows in Saved, and is removed only there
  await p2.click('[data-star="5"]'); await p2.waitForTimeout(100);
  if (!(await p2.$('[data-star="5"].on'))) bad('star not on');
  await p2.click('#back'); await p2.click('[data-tab=saved]'); await p2.waitForSelector('#savedList');
  if ((await p2.$$('[data-saved]')).length !== 1) bad('Saved list');
  await p2.screenshot({ path: path.join(out, 'messages-saved.png') });
  await p2.click('[data-unsave]'); await p2.waitForTimeout(200);
  if ((await p2.$$('[data-saved]')).length !== 0) bad('remove from Saved');
  await p2.click('[data-tab=work]'); await p2.waitForSelector('.th[data-thread="2"]'); await p2.click('.th[data-thread="2"]'); await p2.waitForSelector('.b');
  // the phone's Back: conversation -> list
  if (!(await p2.evaluate(() => window.__roninBack()))) bad('Back inside Messages');
  await p2.waitForSelector('.nav');
  await p2.click('[data-tab=work]'); await p2.click('.th[data-thread="4"]'); await p2.waitForSelector('#text');
  await p2.fill('#text', 'ठीक है'); await p2.click('#send'); await p2.waitForTimeout(100);
  if (!(await p2.evaluate(() => window.__log.some(m => m.op === 'send' && m.to === '+919800000000' && m.body === 'ठीक है')))) bad('send');
  // ads are hidden: no Ads box at the bottom; Settings can show them
  await p2.click('text=← वापस'); await p2.waitForSelector('.nav');
  if (await p2.$('.nav [data-tab=ads]')) bad('ads box shown');
  await p2.click('#settings'); await p2.click('#showAds'); await p2.waitForTimeout(200);
  if (!await p2.$('button:has-text("1909 पर भेजें")')) { bad('1909 BLOCK PROMO tip missing'); await p2.screenshot({ path: path.join(out, 'messages-ads.png') }); }
  // 3. Ronin Calls: keypad, recent with warnings, contacts
  const p3 = await b.newPage({ viewport: { width: 412, height: 860 } });
  await serve(p3); await p3.addInitScript(phone({ isDefault: true, log: true, contacts: true }));
  await p3.goto('https://ronin.home/tools/calls.html'); await p3.waitForSelector('.th');
  if (!await p3.$('[data-tab=recent].on')) bad('Recent is not the first page');
  // owner 21 Nov 2026 "Truecaller जैसी पहचान": the SIM name is the title; 🔍 searches the number; 🎤 names it once
  { const t = await p3.$eval('.th[data-number="+919984923355"] b', x => x.textContent); if (t !== '📛 Atal Mishra') bad('SIM name not the title: ' + t);
    if (await p3.$('.th[data-number="+919800000000"] .chips')) bad('Who-is chips on a contact');
    await p3.click('.th[data-number="+918045671234"] [data-act=who]'); await p3.waitForTimeout(100);
    const o = await p3.evaluate(() => window.__log.filter(m => m.op === 'open').pop());
    if (!o || o.url !== 'https://www.google.com/search?q=' + encodeURIComponent('"8045671234"')) bad('Who-is search ' + JSON.stringify(o));
    if (await p3.evaluate(() => window.__log.some(m => m.op === 'call'))) bad('Who-is chip placed a call');
    await p3.click('.th[data-number="+918045671234"] [data-act=name]'); await p3.waitForTimeout(150);
    const t2 = await p3.$eval('.th[data-number="+918045671234"] b', x => x.textContent); if (t2 !== '✏️ Airtel wale bhaiya') bad('spoken name not shown: ' + t2); }
  // owner 21 Nov 2026: photo circles were near-black (dark letter on slate): white letter on a bright colour, no "+"
  { const av = await p3.$$eval('.av', a => a.map(x => [getComputedStyle(x).color, getComputedStyle(x).backgroundColor, x.textContent]));
    if (av.some(([c, bg, t]) => c !== 'rgb(255, 255, 255)' || bg === 'rgb(51, 65, 85)' || t === '+')) bad('dark photo circle ' + JSON.stringify(av)); }
  // owner 21 Nov 2026: only a real risk is red; the Airtel-SMS number and a plain unknown stay calm
  { const fl = await p3.$$eval('.th.flag', a => a.map(x => x.dataset.number)); if (fl.join() !== '+923001234567') bad('flagged caller not tinted: ' + fl); }
  await p3.click('[data-tab=pad]'); await p3.waitForSelector('.pad button');
  if ((await p3.$$('.pad button')).length !== 12) bad('keypad');
  for (const k of '9811111111') await p3.click(`[data-key="${k}"]`);
  await p3.click('#call'); await p3.waitForTimeout(100);
  if (!(await p3.evaluate(() => window.__log.some(m => m.op === 'call' && m.number === '9811111111')))) bad('call not placed');
  await p3.screenshot({ path: path.join(out, 'calls-pad.png') });
  if (await p3.$('a:has-text("112"), a:has-text("1930")')) bad('emergency / fraud numbers shown all the time');
  await p3.click('[data-tab=recent]'); await p3.waitForSelector('.th');
  // direction at a glance: a coloured arrow on the photo (outgoing blue, missed red)
  { const chips = await p3.$$eval('.th .dir', a => a.map(x => x.className + '|' + getComputedStyle(x).backgroundColor));
    if (!chips.some(c => /out\|/.test(c)) || !chips.some(c => /miss\|/.test(c))) bad('no call direction chips ' + chips);
    for (const c of chips) { if (/out\|/.test(c) && !/37, 99, 235/.test(c)) bad('outgoing not blue ' + c); if (/miss\|/.test(c) && !/220, 38, 38/.test(c)) bad('missed not red ' + c); } }
  // a tap on a name must NOT call: it only puts the number on the keypad
  { const before = await p3.evaluate(() => window.__log.filter(m => m.op === 'call').length);
    await p3.click('.th .mid b'); await p3.waitForTimeout(200);
    if ((await p3.evaluate(() => window.__log.filter(m => m.op === 'call').length)) !== before) bad('tap on a name placed a call');
    if (!(await p3.$('#num')) || !(await p3.inputValue('#num'))) bad('tap on a name did not fill the keypad');
    await p3.click('[data-tab=recent]'); await p3.waitForSelector('.th'); }
  if (!await p3.$('.lbl:has-text("विदेश")')) bad('foreign caller not marked');
  await p3.screenshot({ path: path.join(out, 'calls-recent.png') });
  await p3.click('[data-tab=contacts]'); await p3.waitForSelector('.th[data-number="+919811111111"]'); await p3.fill('#search', 'ram');
  if ((await p3.$$('.th')).length !== 1) bad('contact search');
  // 🎙 recording settings: on, and "do not record" a chosen person
  await p3.click('text=⚙'); await p3.waitForSelector('#recCard');
  await p3.click('#recAuto'); await p3.waitForTimeout(100);
  if (!(await p3.evaluate(() => window.__log.some(m => m.op === 'set' && m.key === 'recAuto' && m.on === true)))) bad('recording not switched on');
  await p3.fill('#skipSearch', 'मा'); await p3.click('[data-skip="+919800000000"]'); await p3.waitForTimeout(100);
  if (!await p3.$('#skipList:has-text("माँ")')) bad('do-not-record person not added');
  await p3.click('#showRecs'); await p3.waitForTimeout(100);
  if (!await p3.$('#recs:has-text("Call_Ramesh")')) bad('recordings not listed');
  await p3.screenshot({ path: path.join(out, 'calls-settings.png'), fullPage: true });
  // 6. English phone: everything English, one row per company, the tabs at the top (3.13)
  const hd = (id, a, t) => ({ thread: id, address: a, name: '', snippet: 'HDFC ' + id, date: Date.now() - t, unread: 1, count: 1, cat: 'work', level: '', reasons: [] });
  const p6 = await b.newPage({ viewport: { width: 412, height: 860 } });
  await serve(p6); await p6.addInitScript(phone({ lang: 'en', isDefault: true, read: true,
    threads: [hd(11, 'VM-HDFCBK-S', 1000), hd(12, 'AD-HDFCBK-S', 2000), hd(13, 'VD-HDFCBK-S', 3000), hd(14, 'JX-KOTAKB-S', 4000)],
    msgs: { 11: [{ id: 1, body: 'Rs 2500 debited from A/c XX1234. Avl Bal Rs 12,450.50', date: Date.now() - 1000, type: 1 }], 12: [{ id: 2, body: 'Biometric login on', date: Date.now() - 2000, type: 1 }], 13: [{ id: 3, body: 'IMPS sent', date: Date.now() - 3000, type: 1 }] } }));
  await p6.goto('https://ronin.home/tools/messages.html'); await p6.waitForSelector('.th');
  const rows = await p6.$$eval('.th b', a => a.map(x => x.textContent));
  if (rows.join() !== 'HDFC Bank,Kotak Bank') bad('not one row per company: ' + rows);
  if ((await p6.$$('.av.brand')).length !== 2) bad('company badges missing');
  const hindi = await p6.evaluate(() => /[\u0900-\u097F]/.test(document.body.innerText));
  if (hindi) bad('Hindi text on an English phone: ' + (await p6.evaluate(() => document.body.innerText.match(/.*[\u0900-\u097F].*/)[0])));
  const nav = await p6.$eval('.nav', n => ({ bottom: n.getBoundingClientRect().bottom, top: n.getBoundingClientRect().top }));
  if (nav.top > 160) bad('message boxes are not at the top: ' + JSON.stringify(nav));
  if (!await p6.$('.nav button:has-text("Important")')) bad('English tab names');
  await p6.screenshot({ path: path.join(out, 'messages-english.png') });
  await p6.click('.th >> nth=0'); await p6.waitForSelector('.b');
  if ((await p6.$$('.b')).length !== 3) bad('company conversation does not show all its SMS');
  // 3.13.1: the main point stands out (balance, money out) without reading the whole SMS
  if (!await p6.$('.fact.bal') || !await p6.$('.b .hl.bal')) bad('balance not highlighted');
  await p6.screenshot({ path: path.join(out, 'messages-facts.png') });
  p6.on('dialog', d => d.accept());
  await p6.click('#del'); await p6.waitForTimeout(200);
  const dels = await p6.evaluate(() => window.__log.filter(m => m.op === 'delete').map(m => m.thread).sort().join());
  if (dels !== '11,12,13') bad('delete did not cover the whole company: ' + dels);
  const p7 = await b.newPage({ viewport: { width: 412, height: 860 } });
  await serve(p7); await p7.addInitScript(phone({ lang: 'en', isDefault: true, log: true, contacts: true }));
  await p7.goto('https://ronin.home/tools/calls.html'); await p7.waitForSelector('.nav');
  const cnav = await p7.$eval('.nav', n => n.getBoundingClientRect().bottom);
  if (cnav < 850) bad('Keypad / Recent / Contacts not at the bottom');
  if (await p7.evaluate(() => /[\u0900-\u097F]/.test(document.body.innerText))) bad('Hindi on the English Calls page');
  await p7.screenshot({ path: path.join(out, 'calls-english.png') });
  await p7.click('#settings'); await p7.waitForSelector('#langCard');
  await p7.click('[data-lang=hi]'); await p7.waitForTimeout(100);
  if (!(await p7.evaluate(() => window.__log.some(m => m.op === 'setLang' && m.value === 'hi')))) bad('language not changed');
  // 3.13: Messages / Calls follow the PHONE's day / night (Ronin sets html.light on a day-mode phone)
  for (const pg of ['messages', 'calls']) {
    const p8 = await b.newPage({ viewport: { width: 412, height: 860 } });
    await serve(p8); await p8.addInitScript(phone({ isDefault: true, read: true, log: true, contacts: true }));
    await p8.addInitScript(() => { window.__roninDark = false; });
    await p8.goto('https://ronin.home/tools/' + pg + '.html'); await p8.waitForSelector('.th');
    const bg = await p8.evaluate(() => getComputedStyle(document.body).backgroundColor);
    if (!/244, 246, 249/.test(bg)) bad(pg + ' not light on a day-mode phone: ' + bg);
    const fg = await p8.$eval('.th b', e => getComputedStyle(e).color);
    if (!/17, 24, 39|185, 28, 28/.test(fg)) bad(pg + ' names not dark on light: ' + fg);
    await p8.screenshot({ path: path.join(out, pg + '-day.png') });
  }
  console.log(fail ? fail + ' FAILED' : 'phone pages OK');
  await b.close(); process.exit(fail ? 1 : 0);
})();
