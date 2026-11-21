// NODE_PATH=$(npm root -g) node tools/uitest/chakshu/t.js — "Report on Chakshu" fills the government form (owner, 21 Nov 2026).
// Ronin fills every box and brings the OTP; it never types the CAPTCHA and never presses Submit.
const { chromium } = require('playwright');
const path = require('path');
const js = path.join(__dirname, '../../../cmd/ronin/ui/chakshu.js');
const page = 'file://' + path.join(__dirname, 'page.html');
let fail = 0; const bad = (m) => { console.log('FAIL', m); fail++; };
(async () => {
  const b = await chromium.launch();
  async function run(data, check) {
    const p = await b.newPage(); await p.goto(page); await p.addScriptTag({ path: js });
    await p.evaluate((d) => window.__roninChakshu.start(d), data);
    await p.waitForFunction(() => JSON.parse(window.__roninChakshu.state()).phase === 'filled', null, { timeout: 15000 });
    await p.waitForTimeout(600);
    const v = await p.evaluate(() => { const q = (i) => document.getElementById(i); return { medium: q('sfc-medium').value, cat: q('sfc-category').value, withHdr: q('sms-with-shortcode').checked, noHdr: q('sms-without-shortcode').checked,
      hdr: q('suspected-shortcode').value, num: q('suspected-number').value, date: q('sfc-date-input').value, time: q('sfc-time-input').value, rem: q('sfc-remarks').value, cta: q('sms-cta').value, url: q('sms-cta-url').value,
      mobile: q('mobile').value, captcha: q('captcha').value, focus: document.activeElement && document.activeElement.id, files: window.changes, banner: (q('ronin-chakshu') || {}).textContent || '' }; });
    await check(v, p); await p.close();
  }
  const now = Date.now() - 3 * 60000, d = new Date(now), pad = (n) => (n < 10 ? '0' : '') + n;
  const date = d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate());
  // the owner's own scam SMS (21 Nov 2026)
  await run({ medium: 'sms', address: 'VM-LENPTE-S', body: 'Get a personal loan of up to Rs.250,000 with a quick online process. Complete your application now: https://bit.ly/-lendingplate - lendingplate', date: now, mobile: '9876543210' }, async (v, p) => {
    if (v.medium !== '2') bad('medium not SMS ' + v.medium);
    if (v.cat !== '4') bad('loan SMS not "loan offers" ' + v.cat);
    if (!v.withHdr || v.hdr !== 'VM-LENPTE-S') bad('header ' + JSON.stringify(v));
    if (v.date !== date) bad('date ' + v.date);
    if (!/^\d\d:\d\d (AM|PM)$/.test(v.time)) bad('time ' + v.time);
    if (v.rem.length < 30 || v.rem.length > 500 || /[^-*#A-Za-z0-9,.!:/%"{}+()&@?\s]/.test(v.rem) || !v.rem.includes('personal loan')) bad('remarks ' + v.rem);
    if (v.url !== 'https://bit.ly/-lendingplate') bad('url ' + v.url);
    if (v.mobile !== '9876543210') bad('saved mobile not filled');
    if (v.captcha !== '') bad('CAPTCHA typed by Ronin');
    if (v.focus !== 'captcha') bad('CAPTCHA box not ready for typing: ' + v.focus);
    if (!v.files.length || !/^1:sms-screenshot\.png:\d{4,}$/.test(v.files[0])) bad('screenshot not attached ' + v.files);
    if (!v.banner.includes('CAPTCHA')) bad('no help banner');
    // OTP: only after "Verify Mobile via OTP", then declaration + preview; Submit stays the owner's
    if (await p.evaluate(() => window.__roninChakshu.otp('123456'))) bad('OTP taken before it was asked for');
    await p.click('#send-otp');
    if (!JSON.parse(await p.evaluate(() => window.__roninChakshu.state())).needOtp) bad('OTP request not noticed');
    if (!await p.evaluate(() => window.__roninChakshu.otp('482913'))) bad('OTP not filled');
    await p.waitForFunction(() => window.previewed === true, null, { timeout: 6000 }).catch(() => bad('preview not opened'));
    const end = await p.evaluate(() => ({ verified: window.verified, decl: document.getElementById('declaration').checked, submitted: !!window.submitted, phase: JSON.parse(window.__roninChakshu.state()).phase }));
    if (end.verified !== '482913') bad('OTP not verified ' + end.verified);
    if (!end.decl) bad('declaration not ticked');
    if (end.submitted) bad('Ronin pressed Submit');
    if (end.phase !== 'preview') bad('phase ' + end.phase);
  });
  // a scam from a mobile number, in Hindi, with a phone number inside; no saved mobile yet
  await run({ medium: 'sms', address: '+91 98123 00011', body: 'मम्मी मैं hospital में हूँ, urgent 10000 भेजो 9876501234 पर', date: now, mobile: '' }, async (v) => {
    if (!v.noHdr || v.num !== '9812300011') bad('number sender ' + JSON.stringify([v.noHdr, v.num]));
    if (v.cat !== '11') bad('relative scam category ' + v.cat);
    if (v.rem.length < 30 || /[^-*#A-Za-z0-9,.!:/%"{}+()&@?\s]/.test(v.rem)) bad('Hindi remarks not cleaned ' + v.rem);
    if (v.cta !== '9876501234') bad('numbers in SMS ' + v.cta);
    if (v.mobile !== '') bad('mobile invented');
    if (!v.banner.includes('mobile number')) bad('banner does not ask for the mobile number');
  });
  // a spam call from the call notification
  await run({ medium: 'call', address: '9812300011', body: '', date: now, mobile: '9876543210' }, async (v) => {
    if (v.medium !== '1' || v.num !== '9812300011') bad('call ' + JSON.stringify([v.medium, v.num]));
    if (v.rem.length < 30) bad('call remarks short');
    if (!v.files.length || !/call-screenshot/.test(v.files[0])) bad('call picture ' + v.files);
  });
  // older than 30 days: the form refuses the date, the owner is told
  await run({ medium: 'sms', address: 'AD-KYCUPD-S', body: 'Your SBI KYC is pending, account will be blocked today, update at http://sbi-kyc.in', date: Date.now() - 40 * 864e5, mobile: '' }, async (v) => {
    if (v.cat !== '1') bad('KYC category ' + v.cat);
    if (!v.banner.includes('30 दिन')) bad('old message not told');
  });
  await b.close();
  console.log(fail ? fail + ' problem(s)' : 'chakshu form fill OK');
  process.exit(fail ? 1 : 0);
})();
