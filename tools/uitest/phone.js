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
      2:[{id:5,body:'482913 is your OTP for login. Check https://kotk.in/KOTAKD/abc or kotk.in/x',date:Date.now()-1000,type:1,level:'',reasons:[]}]};
    window.roninPhone={postMessage(s){const m=JSON.parse(s);window.__log.push(m);let d=null;
      switch(m.op){case 'state':d=Object.assign({},ST,window.__sos||{});break;case 'save':window.__sos=Object.assign(window.__sos||{},{contacts:m.contacts,name:m.name});d=true;break;
      case 'start':d=!!(window.__sos&&window.__sos.contacts&&window.__sos.contacts.length);if(d)window.__sos.running=true;break;case 'stop':window.__sos.running=false;d=true;break;case 'threads':d=T;break;case 'messages':d=M[m.thread]||[];break;case 'makeDefault':d={ok:ST.grant};if(ST.grant)ST.isDefault=true;break;
      case 'send':d=true;break;case 'block':d=true;break;case 'blocked':d=['+91999'];break;case 'watcher':d=false;break;case 'thread':d=9;break;
      case 'recent':d=[{number:'+923001234567',name:'',date:Date.now(),type:3,label:'🌍 विदेश का नंबर'},{number:'+919800000000',name:'माँ',date:Date.now(),type:1,label:''}];break;
      case 'contacts':d=[{name:'माँ',number:'+919800000000'},{name:'Ramesh',number:'+919811111111'}];break;case 'label':d='👤 अनजान नंबर';break;
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
  const work = await p2.$$eval('.th', a => a.map(x => x.dataset.thread));
  if (work.join() !== '2,4') bad('काम के tab: ' + work);
  await p2.screenshot({ path: path.join(out, 'messages-work.png') });
  await p2.click('[data-tab=scam]'); await p2.waitForTimeout(100);
  if ((await p2.$$eval('.th', a => a.map(x => x.dataset.thread))).join() !== '1') bad('ठगी tab');
  await p2.click('.th[data-thread="1"]'); await p2.waitForSelector('.warn');
  if (!await p2.$('a[href="tel:1930"]')) bad('no 1930 on a scam conversation');
  if (await p2.$('.b a')) bad('a link inside an SMS is tappable');
  await p2.screenshot({ path: path.join(out, 'messages-scam.png') });
  p2.on('dialog', d => d.accept());
  await p2.click('#block'); await p2.waitForTimeout(100);
  if (!(await p2.evaluate(() => window.__log.some(m => m.op === 'block' && m.address === '+919812300011')))) bad('block not asked');
  // OTP big and blue, one tap copies only the digits; links in a normal SMS open in Ronin
  await p2.click('text=← वापस'); await p2.click('[data-tab=work]'); await p2.click('.th[data-thread="2"]'); await p2.waitForSelector('.b');
  const otp = await p2.$('button.otp'); if (!otp) bad('OTP not marked');
  else { await otp.click(); await p2.waitForTimeout(100); if (!(await p2.evaluate(() => window.__log.some(m => m.op === 'copy' && m.text === '482913')))) bad('OTP copy'); }
  const lnks = await p2.$$('a.lnk'); if (lnks.length !== 2) bad('links not tappable: ' + lnks.length);
  else { await lnks[1].click(); await p2.waitForTimeout(100); if (!(await p2.evaluate(() => window.__log.some(m => m.op === 'open' && m.url === 'https://kotk.in/x')))) bad('link open'); }
  await p2.screenshot({ path: path.join(out, 'messages-otp.png') });
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
  await p3.goto('https://ronin.home/tools/calls.html'); await p3.waitForSelector('.pad button');
  if ((await p3.$$('.pad button')).length !== 12) bad('keypad');
  for (const k of '9811111111') await p3.click(`[data-key="${k}"]`);
  await p3.click('#call'); await p3.waitForTimeout(100);
  if (!(await p3.evaluate(() => window.__log.some(m => m.op === 'call' && m.number === '9811111111')))) bad('call not placed');
  await p3.screenshot({ path: path.join(out, 'calls-pad.png') });
  if (await p3.$('a:has-text("112"), a:has-text("1930")')) bad('emergency / fraud numbers shown all the time');
  await p3.click('[data-tab=recent]'); await p3.waitForSelector('.th');
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
  // 4. deleted WhatsApp messages: kept text, deleted ones first
  const p4 = await b.newPage({ viewport: { width: 412, height: 860 } });
  await serve(p4); await p4.addInitScript(phone({ watcher: true, on: true }));
  await p4.goto('https://ronin.home/tools/chatlog.html'); await p4.waitForSelector('#list');
  if ((await p4.$$('[data-gone="1"]')).length !== 1 || (await p4.$$('#list .b')).length !== 1) bad('deleted messages view');
  await p4.click('#allMsgs'); await p4.waitForTimeout(100);
  if ((await p4.$$('#list .b')).length !== 2) bad('all messages view');
  await p4.screenshot({ path: path.join(out, 'chatlog.png') });
  // 5. SOS: choose family, then hold the button 2 s
  const p5 = await b.newPage({ viewport: { width: 412, height: 860 } });
  await serve(p5); await p5.addInitScript(phone({ phone: true, sms: true, loc: true }));
  await p5.goto('https://ronin.home/tools/sos.html'); await p5.waitForSelector('#search');
  await p5.fill('#name', 'Vishwas'); await p5.fill('#search', 'mा'.slice(0, 1) === 'm' ? 'मा' : 'मा'); await p5.click('[data-add="+919800000000"]');
  await p5.click('#save'); await p5.waitForSelector('#sosBtn');
  await p5.screenshot({ path: path.join(out, 'sos.png') });
  const box = await p5.$('#sosBtn'); const bb = await box.boundingBox();
  await p5.mouse.move(bb.x + bb.width / 2, bb.y + bb.height / 2); await p5.mouse.down(); await p5.waitForTimeout(600); await p5.mouse.up(); await p5.waitForTimeout(300);
  if (await p5.$('#running')) bad('SOS sent on a short tap');
  await p5.mouse.down(); await p5.waitForTimeout(2300); await p5.mouse.up(); await p5.waitForSelector('#running', { timeout: 3000 }).catch(() => {});
  if (!await p5.$('#running')) bad('SOS did not start after holding');
  if (!(await p5.evaluate(() => window.__log.some(m => m.op === 'save' && m.contacts.length === 1 && m.name === 'Vishwas')))) bad('SOS family not saved');
  await p5.click('#stop'); await p5.waitForTimeout(700);
  if (await p5.$('#running')) bad('SOS did not stop');
  // 6. English phone: everything English, one row per company, the tabs at the bottom (3.11)
  const hd = (id, a, t) => ({ thread: id, address: a, name: '', snippet: 'HDFC ' + id, date: Date.now() - t, unread: 1, count: 1, cat: 'work', level: '', reasons: [] });
  const p6 = await b.newPage({ viewport: { width: 412, height: 860 } });
  await serve(p6); await p6.addInitScript(phone({ lang: 'en', isDefault: true, read: true,
    threads: [hd(11, 'VM-HDFCBK-S', 1000), hd(12, 'AD-HDFCBK-S', 2000), hd(13, 'VD-HDFCBK-S', 3000), hd(14, 'JX-KOTAKB-S', 4000)],
    msgs: { 11: [{ id: 1, body: 'Bal Rs 100', date: Date.now() - 1000, type: 1 }], 12: [{ id: 2, body: 'Biometric login on', date: Date.now() - 2000, type: 1 }], 13: [{ id: 3, body: 'IMPS sent', date: Date.now() - 3000, type: 1 }] } }));
  await p6.goto('https://ronin.home/tools/messages.html'); await p6.waitForSelector('.th');
  const rows = await p6.$$eval('.th b', a => a.map(x => x.textContent));
  if (rows.join() !== 'HDFCBK,KOTAKB') bad('not one row per company: ' + rows);
  const hindi = await p6.evaluate(() => /[\u0900-\u097F]/.test(document.body.innerText));
  if (hindi) bad('Hindi text on an English phone: ' + (await p6.evaluate(() => document.body.innerText.match(/.*[\u0900-\u097F].*/)[0])));
  const nav = await p6.$eval('.nav', n => ({ bottom: n.getBoundingClientRect().bottom, top: n.getBoundingClientRect().top }));
  if (nav.bottom < 850 || nav.top < 700) bad('message boxes are not at the bottom: ' + JSON.stringify(nav));
  if (!await p6.$('.nav button:has-text("Important")')) bad('English tab names');
  await p6.screenshot({ path: path.join(out, 'messages-english.png') });
  await p6.click('.th >> nth=0'); await p6.waitForSelector('.b');
  if ((await p6.$$('.b')).length !== 3) bad('company conversation does not show all its SMS');
  p6.on('dialog', d => d.accept());
  await p6.click('#del'); await p6.waitForTimeout(200);
  const dels = await p6.evaluate(() => window.__log.filter(m => m.op === 'delete').map(m => m.thread).sort().join());
  if (dels !== '11,12,13') bad('delete did not cover the whole company: ' + dels);
  const p7 = await b.newPage({ viewport: { width: 412, height: 860 } });
  await serve(p7); await p7.addInitScript(phone({ lang: 'en', isDefault: true, log: true, contacts: true }));
  await p7.goto('https://ronin.home/tools/calls.html'); await p7.waitForSelector('.pad button');
  const cnav = await p7.$eval('.nav', n => n.getBoundingClientRect().bottom);
  if (cnav < 850) bad('Keypad / Recent / Contacts not at the bottom');
  if (await p7.evaluate(() => /[\u0900-\u097F]/.test(document.body.innerText))) bad('Hindi on the English Calls page');
  await p7.screenshot({ path: path.join(out, 'calls-english.png') });
  await p7.click('#settings'); await p7.waitForSelector('#langCard');
  await p7.click('[data-lang=hi]'); await p7.waitForTimeout(100);
  if (!(await p7.evaluate(() => window.__log.some(m => m.op === 'setLang' && m.value === 'hi')))) bad('language not changed');
  console.log(fail ? fail + ' FAILED' : 'phone pages OK');
  await b.close(); process.exit(fail ? 1 : 0);
})();
