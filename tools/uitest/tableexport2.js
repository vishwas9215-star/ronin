// NODE_PATH=$(npm root -g) node tools/uitest/tableexport2.js — harder tables (from the QA review).
const { chromium } = require('playwright'); const fs = require('fs');
(async () => {
  const js = fs.readFileSync('cmd/ronin/ui/table_export.js', 'utf8');
  const b = await chromium.launch(); let fail = 0; const bad = m => { console.log('FAIL', m); fail++; };
  async function run(html, url) {
    const p = await b.newPage();
    if (url) { await p.route('**/*', r => r.fulfill({ contentType: 'text/html', body: html })); await p.goto(url); } else await p.setContent(html);
    await p.evaluate(js); await p.evaluate(() => window.__roninTable.start());
    for (let i = 0; i < 400; i++) { const s = JSON.parse(await p.evaluate(() => window.__roninTable.state())); if (s.phase !== 'reading') break; await p.waitForTimeout(200); }
    const r = await p.evaluate(() => window.__roninTable.result()); await p.close(); return r ? JSON.parse(r) : null;
  }
  const pager = (n, per, extra) => `<table id=t><tbody></tbody></table><div class=pg>${extra}<a href="#" id=nx>›</a> <a href="#" id=last>»</a></div><script>
    var p=0;function d(){var b=document.querySelector('#t tbody');b.innerHTML='';for(var i=p*${per};i<Math.min(${n},(p+1)*${per});i++)b.insertAdjacentHTML('beforeend','<tr><td>Student '+i+'</td><td>'+(i%7)+'</td></tr>')}
    document.getElementById('nx').onclick=function(e){e.preventDefault();if((p+1)*${per}<${n}){p++;d()}};
    document.getElementById('last').onclick=function(e){e.preventDefault();p=Math.floor((${n}-1)/${per});d()};d();</script>`;
  let r = await run(pager(25, 10, ''));
  if (!r || r.rows.length !== 25) bad('› and »: read ' + (r && r.rows.length) + ' of 25');
  // two header rows with colspan
  r = await run(`<table><thead><tr><th rowspan=2>Name</th><th colspan=2>Marks</th></tr><tr><th>Maths</th><th>Hindi</th></tr></thead>
    <tbody><tr><td>Asha</td><td>40</td><td>35</td></tr><tr><td>Vinod</td><td>38</td><td>30</td></tr></tbody></table>`);
  if (!r || r.head.join('|') !== 'Name|Marks Maths|Marks Hindi' || r.rows[0].length !== 3) bad('two-row header: ' + JSON.stringify(r && r.head));
  // exact repeated rows (no serial number) are kept and marked
  r = await run(`<table><tr><th>Name</th><th>Father</th></tr><tr><td>Asha Devi</td><td>Ram Lal</td></tr><tr><td>Asha Devi</td><td>Ram Lal</td></tr><tr><td>Vinod Kumar</td><td>Shyam Lal</td></tr></table>`);
  if (!r || r.rows.length !== 3 || r.check.counts.dupRows !== 1) bad('repeated row: ' + JSON.stringify(r && r.check.counts));
  // a Next that loads another web page: Ronin puts the script back on the new page and it carries on
  {
    const p = await b.newPage();
    const pages = { 1: ['Asha Devi', 'Vinod Kumar'], 2: ['Ravi Verma', 'Sita Devi'], 3: ['Mohan Lal'] };
    await p.route('https://school.example/**', r => {
      const n = +new URL(r.request().url()).searchParams.get('page') || 1;
      const next = pages[n + 1] ? `<a href="/list?page=${n + 1}">Next</a>` : '';
      r.fulfill({ contentType: 'text/html', body: `<table><tr><th>Name</th></tr>${pages[n].map(x => '<tr><td>' + x + '</td></tr>').join('')}</table>${next}` });
    });
    await p.goto('https://school.example/list?page=1');
    await p.evaluate(js); await p.evaluate(() => window.__roninTable.start());
    let res = null;
    for (let i = 0; i < 200 && !res; i++) {
      await p.waitForTimeout(200);
      // what Ronin does: if the script is gone (a new page), put it back and resume
      const st = await p.evaluate(() => window.__roninTable ? window.__roninTable.state() : '').catch(() => 'nav');
      if (st === 'nav') continue;
      if (st === '') { await p.waitForLoadState(); await p.evaluate(js); await p.evaluate(() => window.__roninTable.resume()); continue; }
      const s2 = JSON.parse(st);
      if (s2.phase === 'done') res = JSON.parse(await p.evaluate(() => window.__roninTable.result()));
      if (s2.phase === 'error') { res = { rows: [], partial: s2.error }; }
    }
    if (!res || res.rows.length !== 5 || res.partial) bad('next is a separate page: ' + JSON.stringify(res && { n: res.rows.length, p: res.partial }));
    await p.close();
  }
  // 500 different realistic names: very few "look-alike" alarms
  const first = ['Ram', 'Raj', 'Rakesh', 'Ramesh', 'Rita', 'Riya', 'Suresh', 'Mahesh', 'Dinesh', 'Ganesh', 'Rahul', 'Rohit', 'Mohit', 'Sohan', 'Mohan', 'Sita', 'Gita', 'Anita', 'Sunita', 'Vinita', 'Pooja', 'Puja', 'Neha', 'Sneha', 'Amit', 'Sumit', 'Ankit', 'Ajay', 'Vijay', 'Sanjay'];
  const last = ['Kumar', 'Singh', 'Yadav', 'Verma', 'Gupta', 'Maurya', 'Pal', 'Devi', 'Kumari', 'Prasad', 'Lal', 'Ram', 'Sharma', 'Mishra', 'Pandey', 'Tiwari', 'Shukla'];
  const names = []; for (const f of first) for (const l of last) names.push(f + ' ' + l);
  r = await run('<table><tr><th>Student Name</th></tr>' + names.slice(0, 500).map(n => '<tr><td>' + n + '</td></tr>').join('') + '</table>');
  if (!r || r.check.counts.similar > 10) bad('too many look-alike alarms: ' + (r && r.check.counts.similar) + ' for ' + names.length + ' different names');
  console.log(fail ? fail + ' FAILED' : 'hard tables OK');
  await b.close(); process.exit(fail ? 1 : 0);
})();
