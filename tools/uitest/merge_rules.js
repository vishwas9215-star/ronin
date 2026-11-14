// node tools/uitest/merge_rules.js — matching rules of "Data Match" on small made-up lists
// (cases found by the independent review: twins, siblings, same names, odd columns, form times).
const M = require('../../cmd/ronin/ui/tools/merge.js');
const F = (name, head, rows, extra) => Object.assign({ name, site: '', title: '', tables: [{ head, rows }] }, extra || {});
let fail = 0; const bad = m => { console.log('FAIL', m); fail++; };
const notes = (r, i) => r.check.cells.filter(c => c[0] === i).map(c => c[2] + ':' + c[3]).join(' ; ');
const H1 = ['Student Name', 'Gender', 'Student DOB', 'Student Class', 'Father Name', 'Mother Name'];
const H2 = ['Student Name', 'Gender', 'Date of Birth', 'Class', "Father's Name", "Mother's Name", 'Aadhaar Number'];
let r;
// brothers: other name AND other birthday -> two children
r = M.merge([F('prerna.xlsx', H1, [['Sonu', 'Male', '01/07/2015', '3', 'Ram Lal', 'Sita'], ['Asha', 'Female', '01/07/2016', '3', 'X', 'Y']]),
  F('udise.xlsx', H2, [['MONU', 'M', '01/07/2016', 'III', 'RAM LAL', 'SITA', ''], ['ASHA', 'F', '01/07/2016', 'III', 'X', 'Y', '']])]);
if (r.rows.length !== 3) bad('brothers merged: ' + r.rows.map(x => x[2]).join(','));
// twins (same birthday): one row, but never without an orange "check" mark
r = M.merge([F('prerna.xlsx', H1, [['Sonu', 'Male', '01/07/2016', '3', 'Ram Lal', 'Sita']]), F('udise.xlsx', H2, [['MONU', 'M', '01/07/2016', 'III', 'RAM LAL', 'SITA', '123412341234']])]);
if (r.rows.length === 1 && !/similar:/.test(notes(r, 0))) bad('twins merged without a warning: ' + notes(r, 0));
// different full Aadhaar: never the same child
r = M.merge([F('a.xlsx', H2, [['Ravi', 'M', '01/02/2016', '3', 'Mohan', 'Sita', '111122223333']]), F('b.xlsx', H2.concat('Mobile'), [['RAVI', 'M', '01/02/2016', '3', 'MOHAN', 'SITA', '111122229999', '9999999999']])]);
if (r.rows.length !== 2) bad('different Aadhaar merged');
// same name, other class: not sure
r = M.merge([F('prerna.xlsx', ['Student Name', 'Father Name', 'Gender', 'Class'], [['Rahul', 'Ram', 'Male', '3']]), F('udise.xlsx', ['Student Name', 'Gender', 'Class'], [['RAHUL', 'M', 'V']])]);
if (r.rows.length === 1 && !/similar:/.test(notes(r, 0))) bad('Rahul class 3 / class 5 merged as sure');
// inside one file: Aarti (mother Sita, class 2) and Aarti (mother Gita, class 3) are two children
r = M.merge([F('list.xlsx', ['Student Name', 'Mother Name', 'Student DOB', 'Class'], [['Aarti', 'Sita', '01/07/2016', '2'], ['Aarti', 'Gita', '01/07/2016', '3']])]);
if (r.rows.length !== 2) bad('two Aartis shown as one');
// Mother Tongue is not the mother
r = M.merge([F('prerna.xlsx', ['Student Name', 'Father Name', 'Mother Name', 'Student DOB'], [['Ravi', 'Mohan', 'Sita', '01/02/2016']]),
  F('udise.xlsx', ['Student Name', "Father's Name", 'Mother Tongue', "Mother's Name", 'Date of Birth'], [['RAVI', 'MOHAN', 'Hindi', 'GITA', '2016-02-01']])]);
if (!/diff/.test(notes(r, 0)) || r.rows[0].join('|').includes('Hindi') && !r.head.some(h => /tongue/i.test(h))) bad('mother tongue: ' + r.head + ' / ' + r.rows[0]);
// other village, same post office: a difference
r = M.merge([F('prerna.xlsx', ['Student Name', 'Father Name', 'Student DOB', 'Address'], [['Ravi', 'Mohan', '01/02/2016', 'Gram Nathaipur Post Jaisinghpur Sultanpur']]),
  F('udise.xlsx', ['Student Name', 'Father Name', 'Student DOB', 'Address'], [['RAVI', 'MOHAN', '01/02/2016', 'GRAM BARSOMA POST JAISINGHPUR SULTANPUR']])]);
if (!/diff/.test(notes(r, 0))) bad('another village not marked');
r = M.merge([F('prerna.xlsx', ['Student Name', 'Father Name', 'Student DOB', 'Address'], [['Ravi', 'Mohan', '01/02/2016', 'Barsoma Nathaipur']]),
  F('udise.xlsx', ['Student Name', 'Father Name', 'Student DOB', 'Address'], [['RAVI', 'MOHAN', '01/02/2016', 'VILLAGE BARSOMA NATHAIPUR POST JAISINGHPUR']])]);
if (/diff/.test(notes(r, 0))) bad('same village written longer marked as different');
// a column filled for one child is not "the same for everyone"
r = M.merge([F('list.xlsx', ['Student Name', 'Father Name', 'Student DOB', 'CWSN'], [['Ravi', 'Mohan', '01/02/2016', 'Yes'], ['Kiran', 'Sohan', '01/02/2015', '']])]);
if (r.info.some(x => /CWSN/.test(x[0])) || !r.head.includes('CWSN')) bad('CWSN reported for all');
// Google Form (CSV, month/day): 30 Nov then 1 Dec -> the 1 Dec answer counts
r = M.merge([F('Seniority.xlsx', ['Teacher Name', 'Employee Code', 'Date of Birth'], [['Sunita Devi', 'EH1', '03/03/1980']]),
  F('Form (Responses).csv', ['Timestamp', 'Name of Teacher', 'Employee Code', 'Date of Birth'], [['11/30/2026 09:00:00', 'Sunita Devi', 'EH1', '03/03/1981'], ['12/1/2026 10:00:00', 'Sunita Devi', 'EH1', '03/03/1980']], { dmy: false })]);
if (!/12\/1\/2026/.test(notes(r, 0)) || r.check.cells.some(c => c[2] === 'diff')) bad('newest form answer: ' + notes(r, 0));
// anything, not only people: two stock lists matched on the item code
r = M.merge([F('march.xlsx', ['Item Code', 'Item', 'Qty', 'Rate'], [['A1', 'Chalk box', '10', '25'], ['A2', 'Duster', '4', '40'], ['A3', 'Register', '12', '60']]),
  F('april.xlsx', ['Item Code', 'Item', 'Qty', 'Rate'], [['A1', 'Chalk box', '8', '25'], ['A2', 'Duster', '4', '40'], ['A4', 'Pen', '50', '5']])]);
const qtyCol = r.head.findIndex(h => /Qty/.test(h));
const a1 = r.rows.findIndex(x => /Chalk/.test(x.join('|')));
if (r.rows.length !== 4 || !r.check.cells.some(c => c[0] === a1 && c[1] === qtyCol && c[2] === 'diff')) bad('stock lists: ' + JSON.stringify(r.rows) + ' ' + JSON.stringify(r.check.cells));
if (!r.check.cells.some(c => /entry/.test(c[3])) || r.check.cells.some(c => /बच्चा|शिक्षक/.test(c[3]))) bad('words for a stock list: ' + JSON.stringify(r.check.cells.map(c => c[3])));
if (!r.info.some(x => /Matched on/.test(x[0]) && /Item Code/.test(x[1]))) bad('matched-on line: ' + JSON.stringify(r.info));
// people that are neither children nor teachers
r = M.merge([F('club.xlsx', ['Name', 'Mobile'], [['Ravi Kumar', '9000000001']]), F('list2.xlsx', ['Name', 'Mobile'], [['Sita Devi', '9000000002']])]);
if (r.check.cells.some(c => /बच्चा|शिक्षक/.test(c[3]))) bad('people called children: ' + JSON.stringify(r.check.cells));

// field names
const want = { 'Father Aadhaar No': /^key:/, 'Mother Tongue': /^key:/, 'Name as per Aadhaar': /^key:/, 'Teacher Mobile Number': /^mobile$/, 'Teacher Residential Address': /^address$/, 'Teacher Aadhaar No': /^aadhaar$/, 'Employee Code': /^empid$/, 'Manav Sampada ID': /^empid$/ };
Object.entries(want).forEach(([h, re]) => { if (!re.test(M.fieldOf(h))) bad(h + ' -> ' + M.fieldOf(h)); });
console.log(fail ? fail + ' FAILED' : 'merge rules OK');
process.exit(fail ? 1 : 0);
