// Ronin Date Calculator: age on a date, Class 1 admission age, years of service,
// retirement date (with the UP basic teachers' session benefit) and an age estimate for
// people who do not know their birth date (census). Plain date sums, no internet.
(function (root) {
  'use strict';
  const D = {};
  const pad = n => String(n).padStart(2, '0');

  // dates are {y, m, d} (m 1-12); no time zones involved
  D.parse = s => {
    if (!s) return null;
    let m = String(s).trim().match(/^(\d{4})-(\d{1,2})-(\d{1,2})$/);
    if (m) return D.make(+m[1], +m[2], +m[3]);
    m = String(s).trim().match(/^(\d{1,2})[\/.\-](\d{1,2})[\/.\-](\d{4})$/);
    if (m) return D.make(+m[3], +m[2], +m[1]);
    return null;
  };
  D.make = (y, m, d) => {
    if (!(y > 1800 && y < 2300 && m >= 1 && m <= 12 && d >= 1 && d <= D.daysIn(y, m))) return null;
    return { y, m, d };
  };
  D.daysIn = (y, m) => new Date(Date.UTC(y, m, 0)).getUTCDate();
  D.today = () => { const t = new Date(); return { y: t.getFullYear(), m: t.getMonth() + 1, d: t.getDate() }; };
  D.show = a => a ? pad(a.d) + '/' + pad(a.m) + '/' + a.y : '';
  D.iso = a => a ? a.y + '-' + pad(a.m) + '-' + pad(a.d) : '';
  D.cmp = (a, b) => (a.y - b.y) || (a.m - b.m) || (a.d - b.d);
  const serial = a => Date.UTC(a.y, a.m - 1, a.d) / 86400000;
  D.days = (a, b) => serial(b) - serial(a);
  D.fromSerial = n => { const t = new Date(n * 86400000); return { y: t.getUTCFullYear(), m: t.getUTCMonth() + 1, d: t.getUTCDate() }; };
  D.addDays = (a, n) => D.fromSerial(serial(a) + n);

  // a date some months later (31 Jan + 1 month = 28/29 Feb)
  D.addMonths = (a, k) => {
    const t = a.y * 12 + (a.m - 1) + k, y = Math.floor(t / 12), m = t % 12 + 1;
    return { y, m, d: Math.min(a.d, D.daysIn(y, m)) };
  };
  // completed years, months and days from a to b (b on or after a)
  D.diff = (a, b) => {
    if (D.cmp(b, a) < 0) return null;
    let k = (b.y - a.y) * 12 + (b.m - a.m);
    if (D.cmp(D.addMonths(a, k), b) > 0) k--;
    return { y: Math.floor(k / 12), m: k % 12, d: D.days(D.addMonths(a, k), b), total: D.days(a, b) };
  };
  D.ymd = r => r ? r.y + ' वर्ष ' + r.m + ' माह ' + r.d + ' दिन' : '';

  // the birthday in a given year (29 Feb -> 28 Feb in other years)
  D.birthdayIn = (dob, y) => ({ y, m: dob.m, d: Math.min(dob.d, D.daysIn(y, dob.m)) });

  // Class 1 (or any) admission: is the child at least `min` years old on `on`?
  D.admission = (dob, on, min, max) => {
    const age = D.diff(dob, on);
    const turns = D.birthdayIn(dob, dob.y + min);
    const ok = !!age && age.y >= min && (!max || age.y < max);
    return { age, ok, turns, short: ok || !age || age.y >= min ? 0 : D.days(on, turns), over: !!(max && age && age.y >= max) };
  };

  // retirement: the person reaches `age` on the birthday; he retires on the last day of that
  // month. Born on the 1st: the age is reached the day before, so the last day of the month
  // before (the usual service rule). Session benefit (UP basic teachers, session 1 April -
  // 31 March): a teacher whose retirement falls inside the session stays till 31 March.
  D.retirement = (dob, age, sessionBenefit) => {
    const bday = D.birthdayIn(dob, dob.y + age);
    const reach = D.addDays(bday, -1); // the day the age is completed
    const retire = { y: reach.y, m: reach.m, d: D.daysIn(reach.y, reach.m) };
    let end = retire;
    if (sessionBenefit && !(retire.m === 3 && retire.d === 31)) {
      end = { y: retire.m >= 4 ? retire.y + 1 : retire.y, m: 3, d: 31 };
    }
    return { reach, retire, end, extended: D.cmp(end, retire) !== 0 };
  };

  // an age estimate for someone with no birth date: each clue gives a number of years now
  // clues: { childAge, ageAtFirstChild } | { yearsMarried, ageAtMarriage } | { eventYear, ageAtEvent }
  D.estimate = (clues, onYear) => {
    const got = [];
    if (clues.childAge >= 0 && clues.ageAtFirstChild > 0) got.push({ how: 'सबसे बड़े बच्चे से', age: clues.childAge + clues.ageAtFirstChild });
    if (clues.yearsMarried >= 0 && clues.ageAtMarriage > 0) got.push({ how: 'शादी से', age: clues.yearsMarried + clues.ageAtMarriage });
    if (clues.eventYear > 1800 && clues.ageAtEvent >= 0) got.push({ how: 'याद की घटना से', age: onYear - clues.eventYear + clues.ageAtEvent });
    if (!got.length) return null;
    const ages = got.map(g => g.age);
    const avg = Math.round(ages.reduce((a, b) => a + b, 0) / ages.length);
    const spread = Math.max(...ages) - Math.min(...ages);
    return { age: avg, from: Math.min(...ages), to: Math.max(...ages), birthYear: onYear - avg, got, agree: spread <= 5 };
  };

  // well-known events people remember (year, Hindi name) for the estimate
  D.EVENTS = [
    [1947, 'देश की आज़ादी'], [1950, 'संविधान लागू, पहला गणतंत्र दिवस'], [1952, 'पहला आम चुनाव'], [1962, 'चीन से युद्ध'],
    [1964, 'नेहरू जी का निधन'], [1965, 'पाकिस्तान से युद्ध (शास्त्री जी)'], [1966, 'शास्त्री जी का निधन'], [1971, 'बांग्लादेश युद्ध'],
    [1975, 'आपातकाल (इमरजेंसी)'], [1977, 'जनता पार्टी की सरकार'], [1983, 'क्रिकेट विश्व कप जीत'], [1984, 'इंदिरा गांधी की हत्या'],
    [1991, 'राजीव गांधी की हत्या'], [1999, 'कारगिल युद्ध'], [2000, 'उत्तराखंड अलग राज्य बना'],
    [2011, '2011 की जनगणना'], [2016, 'नोटबंदी'], [2020, 'कोरोना लॉकडाउन'],
  ];

  // ---- amount in words (Indian system: thousand, lakh, crore), English and Hindi
  const EN = ['', 'One', 'Two', 'Three', 'Four', 'Five', 'Six', 'Seven', 'Eight', 'Nine', 'Ten', 'Eleven', 'Twelve', 'Thirteen', 'Fourteen', 'Fifteen', 'Sixteen', 'Seventeen', 'Eighteen', 'Nineteen'];
  const TENS = ['', '', 'Twenty', 'Thirty', 'Forty', 'Fifty', 'Sixty', 'Seventy', 'Eighty', 'Ninety'];
  const HI = ('शून्य एक दो तीन चार पाँच छह सात आठ नौ दस ग्यारह बारह तेरह चौदह पंद्रह सोलह सत्रह अठारह उन्नीस बीस इक्कीस बाईस तेईस चौबीस पच्चीस छब्बीस सत्ताईस अट्ठाईस उनतीस ' +
    'तीस इकतीस बत्तीस तैंतीस चौंतीस पैंतीस छत्तीस सैंतीस अड़तीस उनतालीस चालीस इकतालीस बयालीस तैंतालीस चवालीस पैंतालीस छियालीस सैंतालीस अड़तालीस उनचास ' +
    'पचास इक्यावन बावन तिरेपन चौवन पचपन छप्पन सत्तावन अट्ठावन उनसठ साठ इकसठ बासठ तिरेसठ चौंसठ पैंसठ छियासठ सड़सठ अड़सठ उनहत्तर ' +
    'सत्तर इकहत्तर बहत्तर तिहत्तर चौहत्तर पचहत्तर छिहत्तर सतहत्तर अठहत्तर उन्यासी अस्सी इक्यासी बयासी तिरासी चौरासी पचासी छियासी सत्तासी अट्ठासी नवासी ' +
    'नब्बे इक्यानवे बानवे तिरानवे चौरानवे पंचानवे छियानवे सत्तानवे अट्ठानवे निन्यानवे').split(' ');
  const en99 = n => n < 20 ? EN[n] : TENS[Math.floor(n / 10)] + (n % 10 ? ' ' + EN[n % 10] : '');
  function parts(n) { // crore, lakh, thousand, hundred, rest
    return [Math.floor(n / 1e7), Math.floor(n / 1e5) % 100, Math.floor(n / 1e3) % 100, Math.floor(n / 100) % 10, n % 100];
  }
  function enWords(n) {
    if (n === 0) return 'Zero';
    const [cr, lk, th, hu, re] = parts(n), out = [];
    if (cr) out.push((cr > 99 ? enWords(cr) : en99(cr)) + ' Crore');
    if (lk) out.push(en99(lk) + ' Lakh');
    if (th) out.push(en99(th) + ' Thousand');
    if (hu) out.push(EN[hu] + ' Hundred');
    if (re) out.push(en99(re));
    return out.join(' ');
  }
  function hiWords(n) {
    if (n === 0) return HI[0];
    const [cr, lk, th, hu, re] = parts(n), out = [];
    if (cr) out.push((cr > 99 ? hiWords(cr) : HI[cr]) + ' करोड़');
    if (lk) out.push(HI[lk] + ' लाख');
    if (th) out.push(HI[th] + ' हज़ार');
    if (hu) out.push(HI[hu] + ' सौ');
    if (re) out.push(HI[re]);
    return out.join(' ');
  }
  // "1,23,456.50" -> { en, hi, figure }
  D.words = v => {
    const s = String(v).replace(/[,\s₹]/g, '');
    if (!/^\d{1,13}(\.\d{1,2})?$/.test(s)) return null;
    const [r, p0] = s.split('.'), rupees = +r, paise = p0 ? +(p0 + '0').slice(0, 2) : 0;
    const en = 'Rupees ' + enWords(rupees) + (paise ? ' and ' + en99(paise) + ' Paise' : '') + ' Only';
    const hi = hiWords(rupees) + ' रुपये' + (paise ? ' ' + HI[paise] + ' पैसे' : '') + ' मात्र';
    return { en, hi, figure: '₹ ' + rupees.toLocaleString('en-IN') + (paise ? '.' + String(paise).padStart(2, '0') : '') };
  };

  root.RoninDate = D;
  if (typeof module !== 'undefined') module.exports = D;
})(typeof window !== 'undefined' ? window : globalThis);
