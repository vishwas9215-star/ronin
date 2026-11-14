// NODE_PATH=$(npm root -g) node tools/uitest/tableexport.js — Data -> Excel: reads every page of a table and checks names.
const { chromium } = require('playwright'); const fs = require('fs');
(async () => {
  const js = fs.readFileSync('cmd/ronin/ui/table_export.js', 'utf8');
  const kids = ['Rahul Kumar', 'Priya Singh', 'Rahul Kumr', 'Anita Devi', 'Sunil Yadav', 'Mohd Arif', 'Pooja  Verma', 'Ravi2 Kumar',
    'Anita Devi', 'Sita Ram', 'Geeta Kumari', 'aman verma', 'Neha Gupta', 'Karan Singh', 'Deepak Maurya', 'Rani Devi', 'Ajay Pal', 'राहुल Kumar',
    'Sunita Devi', 'Vikas Kumar', 'Sunil Yadav'];
  const html = `<html><head><title>Student list - UDISE+</title></head><body>
  <table id=t><thead><tr><th>S.No.</th><th>Student Name</th><th>Father Name</th><th>Class</th></tr></thead><tbody></tbody></table>
  <ul class=pagination><li><a href="#" id=prev>Previous</a></li><li id=nextli><a href="#" id=next>Next</a></li></ul>
  <script>
   var kids = ${JSON.stringify(kids)}, fathers = ['Ramesh Kumar','Suresh Singh','Dinesh Kumar','Mohan Lal','Ram Yadav','Abdul Karim','Harish Verma','Mukesh Kumar','Mohan Lal','Shyam Lal','Gopal Prasad','Rakesh Verma','Anil Gupta','Baldev Singh','Shiv Maurya','Kallu Ram','Jagdish Pal','Naresh Kumar','Pappu Ram','Vinod Kumar','Ram Yadav'];
   var page = 0, per = 8;
   function draw(){ var tb = document.querySelector('#t tbody'); tb.innerHTML='';
     for (var i = page*per; i < Math.min(kids.length,(page+1)*per); i++) tb.insertAdjacentHTML('beforeend','<tr><td>'+(i+1)+'</td><td>'+kids[i]+'</td><td>'+fathers[i]+'</td><td>5</td></tr>');
     document.getElementById('nextli').className = (page+1)*per >= kids.length ? 'disabled' : ''; }
   document.getElementById('next').onclick = function(e){ e.preventDefault(); if ((page+1)*per < kids.length) { setTimeout(function(){ page++; draw(); }, 300); } };
   draw();
  </script></body></html>`;
  const b = await chromium.launch(); const p = await b.newPage(); let fail = 0; const bad = m => { console.log('FAIL', m); fail++; };
  const errs = []; p.on('pageerror', e => errs.push(e.message));
  await p.setContent(html); await p.evaluate(js);
  await p.evaluate(() => window.__roninTable.start());
  let s; for (let i = 0; i < 100; i++) { s = JSON.parse(await p.evaluate(() => window.__roninTable.state())); if (s.phase !== 'reading') break; await p.waitForTimeout(200); }
  if (s.phase !== 'done') bad('state ' + JSON.stringify(s));
  const r = JSON.parse(await p.evaluate(() => window.__roninTable.result()));
  if (r.rows.length !== 21) bad('rows ' + r.rows.length + ' (all 3 pages should be read)');
  if (r.head.join('|') !== 'S.No.|Student Name|Father Name|Class') bad('head ' + r.head);
  const at = (row, col) => r.check.cells.filter(c => c[0] === row && c[1] === col).map(c => c[2] + ':' + c[3]).join(';');
  if (!at(2, 1).startsWith('similar')) bad('Rahul Kumr not marked similar: ' + at(2, 1));
  if (!at(6, 1).startsWith('spell')) bad('double space not marked: ' + at(6, 1));
  if (!at(7, 1).startsWith('spell')) bad('digit in name not marked: ' + at(7, 1));
  if (!at(17, 1).startsWith('spell')) bad('mixed script not marked: ' + at(17, 1));
  if (at(11, 1) !== '') bad('a name in small letters must not be marked: ' + at(11, 1));
  if (r.check.cells.some(c => c[1] === 2 && c[2] === 'dupname')) bad('a repeated father name was marked (brothers and sisters share parents)');
  if (!r.check.cells.every(c => /^(पीला|नारंगी|लाल):/.test(c[3]))) bad('every reason must start with its colour');
  if (!at(8, 1).startsWith('dupname')) bad('repeated Anita Devi not marked: ' + at(8, 1));
  if (!at(20, 1).startsWith('dup')) bad('repeated row (Sunil Yadav / Ram Yadav) not marked: ' + at(20, 1));
  if (at(0, 3) !== '') bad('class column marked: ' + at(0, 3));
  if (at(1, 1) !== '') bad('good name marked: ' + at(1, 1));
  console.log(JSON.stringify(r.check.counts));
  if (errs.length) bad('JS errors ' + errs);
  fs.writeFileSync('/tmp/table-result.json', JSON.stringify(r));
  console.log(fail ? fail + ' FAILED' : 'table export OK');
  await b.close(); process.exit(fail ? 1 : 0);
})();
