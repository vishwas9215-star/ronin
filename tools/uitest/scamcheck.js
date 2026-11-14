// Scam engine test: every scam message warns, no normal family / school message does,
// links behave like internal/safelinks (Go) and SafeLinks.kt, emails are judged right.
const fs = require('fs'), path = require('path');
const root = path.join(__dirname, '..', '..');
globalThis.__roninLinkLists = JSON.parse(fs.readFileSync(path.join(root, 'internal/safelinks/lists.json'), 'utf8'));
globalThis.__roninScamRules = JSON.parse(fs.readFileSync(path.join(root, 'internal/safelinks/scamrules.json'), 'utf8'));
const S = require(path.join(root, 'cmd/ronin/ui/tools/scamcheck.js'));
const C = JSON.parse(fs.readFileSync(path.join(__dirname, 'scam_corpus.json'), 'utf8'));
let fail = 0;
for (const t of C.scam) { const r = S.text(t); if (!r.level) { console.log('MISSED', r.score, r.ids.join(','), '|', t); fail++; } }
for (const t of C.normal) { const r = S.text(t); if (r.level) { console.log('FALSE ALARM', r.score, r.ids.join(','), '|', t); fail++; } }
for (const m of C.mail) { const r = S.mail(m); const lv = r.level === 'stop' ? 'stop' : r.level ? 'warn' : ''; if ((m.expect === 'stop' && !lv) || (!m.expect && lv)) { console.log('MAIL', lv, r.score, r.ids.join(','), '|', m.subject); fail++; } }
const cases = JSON.parse(fs.readFileSync(path.join(root, 'internal/safelinks/cases.json'), 'utf8'));
for (const [u, want] of cases) { const got = S.check(u); if (got !== want) { console.log('LINK', u, 'got', got, 'want', want); fail++; } }
// the sites' "you are leaving" wrappers are unwrapped first
const wrapped = [['https://l.facebook.com/l.php?u=https%3A%2F%2Ft.me%2Fvip', 'telegram'], ['https://www.google.com/url?q=https://bit.ly/x', 'short'], ['https://www.youtube.com/redirect?q=https%3A%2F%2Fchat.whatsapp.com%2Fabc', 'invite']];
for (const [u, want] of wrapped) { const v = S.link(u); if (!v || v.why !== want) { console.log('WRAP', u, v && v.why); fail++; } }
if (S.check('https://www.facebook.com/') !== '' || S.text('ED notice: need help').level === undefined) fail++;
console.log(`scam ${C.scam.length}, normal ${C.normal.length}, mail ${C.mail.length}, links ${cases.length}`);
console.log(fail ? fail + ' FAILED' : 'scam check OK');
process.exit(fail ? 1 : 0);
