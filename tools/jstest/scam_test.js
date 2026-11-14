// node tools/jstest/scam_test.js  — checks Scam Shield rules.
const path = require("path");
const fs = require("fs");
const vm = require("vm");
const src = fs.readFileSync(path.join(__dirname, "../../cmd/ronin/ui/inject_scam.js"), "utf8");
const mod = { exports: {} };
vm.runInNewContext(src, { module: mod, window: {}, document: {} });
const { score, THRESHOLD } = mod.exports;

const scams = [
  "Aapka paisa return ke liye ready hai. Aapne 5000 bheje the, 30,900 ho gaye. Profit nikalne ke liye 20% charge pehle bhejiye.",
  "आपका प्रॉफिट तैयार है, निकालने से पहले 20% टैक्स जमा करें। UPI ID: abc@ybl",
  "Send me money, I will invest and give you 5x return in 2 hours. Join VIP group now!",
  "Guaranteed profit 10% daily return. Limited seats. Pay registration fee to join.",
  "पैसे भेजो, मैं निवेश करके रिटर्न दूँगा। पैसा डबल पक्का।",
  "Withdrawal is locked. Pay commission of 20% to release your profit.",
];
const safe = [
  "NIFTY closed 0.5% higher today as banks gained. Analysts expect volatility ahead of RBI policy.",
  "Your SBI account statement for October is ready. Login to net banking to view.",
  "Join our free Telegram channel for market news and charts.",
  "Mutual fund investments are subject to market risks, read all scheme related documents carefully.",
  "Withdrawal from your demat account takes T+1 days. There is no fee for UPI withdrawal.",
  "How to double your productivity: 10 tips for students.",
];
let fail = 0;
for (const t of scams) {
  const s = score(t);
  if (s < THRESHOLD) { console.log("MISSED scam (" + s + "): " + t); fail++; }
}
for (const t of safe) {
  const s = score(t);
  if (s >= THRESHOLD) { console.log("FALSE ALARM (" + s + "): " + t); fail++; }
}
console.log(fail ? fail + " problem(s)" : "scam rules OK");
process.exit(fail ? 1 : 0);
