// node tools/uitest/datecalc_rules.js — the Date Calculator's sums.
const D = require('../../cmd/ronin/ui/tools/datecalc.js');
let fail = 0; const eq = (a, b, m) => { if (JSON.stringify(a) !== JSON.stringify(b)) { console.log('FAIL', m, JSON.stringify(a), '!=', JSON.stringify(b)); fail++; } };
const p = D.parse;
// age
eq(D.ymd(D.diff(p('25/01/2017'), p('14/11/2026'))), '9 वर्ष 9 माह 20 दिन', 'age');
eq(D.ymd(D.diff(p('31/01/2020'), p('01/03/2020'))), '0 वर्ष 1 माह 1 दिन', 'month end');
eq(D.diff(p('29/02/2016'), p('27/02/2017')).y, 0, 'leap birthday not yet');
eq(D.diff(p('2026-11-14'), p('2026-11-13')), null, 'date before birth');
// Class 1: 6 years on 1 April 2026
eq(D.admission(p('01/04/2020'), p('01/04/2026'), 6).ok, true, 'exactly 6 on 1 April');
eq(D.admission(p('02/04/2020'), p('01/04/2026'), 6).ok, false, 'one day short');
eq(D.admission(p('02/04/2020'), p('01/04/2026'), 6).short, 1, 'days short');
eq(D.admission(p('01/03/2019'), p('01/04/2026'), 6, 7).over, true, 'over 7');
// retirement at 62, last day of the month; born on the 1st -> month before
eq(D.show(D.retirement(p('15/07/1970'), 62, false).retire), '31/07/2032', 'retire mid-month');
eq(D.show(D.retirement(p('01/07/1970'), 62, false).retire), '30/06/2032', 'born on the 1st');
eq(D.show(D.retirement(p('15/07/1970'), 62, true).end), '31/03/2033', 'session benefit');
eq(D.show(D.retirement(p('01/04/1970'), 62, true).end), '31/03/2032', 'retires on 31 March: no extension');
eq(D.retirement(p('01/04/1970'), 62, true).extended, false, 'no extension flag');
eq(D.show(D.retirement(p('20/02/1971'), 62, true).end), '31/03/2033', 'Feb -> same year 31 March');
// estimate
const e = D.estimate({ childAge: 40, ageAtFirstChild: 22, yearsMarried: 44, ageAtMarriage: 18 }, 2027);
eq([e.age, e.birthYear, e.agree], [62, 1965, true], 'estimate');
eq(D.estimate({ eventYear: 1971, ageAtEvent: 10 }, 2027).age, 66, 'event');
eq(D.estimate({}, 2027), null, 'no clue');
// amount in words
eq(D.words('123456').en, 'Rupees One Lakh Twenty Three Thousand Four Hundred Fifty Six Only', 'words en');
eq(D.words('1,23,456').hi, 'एक लाख तेईस हज़ार चार सौ छप्पन रुपये मात्र', 'words hi');
eq(D.words('10000000.5').en, 'Rupees One Crore and Fifty Paise Only', 'crore + paise');
eq(D.words('99').hi, 'निन्यानवे रुपये मात्र', '99');
eq(D.words('0').en, 'Rupees Zero Only', 'zero');
eq(D.words('abc'), null, 'not a number');
eq(D.words('2500075').figure, '₹ 25,00,075', 'figure');
// days between / after
eq(D.days(p('01/01/2026'), p('31/12/2026')), 364, 'days between');
eq(D.show(D.addDays(p('15/11/2026'), 180)), '14/05/2027', '180 days later');
console.log(fail ? fail + ' FAILED' : 'date calculator OK');
process.exit(fail ? 1 : 0);
