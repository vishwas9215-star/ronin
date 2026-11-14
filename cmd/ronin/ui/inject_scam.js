// Ronin Scam Shield: warns when a page or chat asks for money with promises
// of fixed profit, or asks for a "fee" before profits can be withdrawn.
// Runs in PERSONAL and PRIVATE windows (never on bank sites).
(function () {
  "use strict";

  // "pay a fee before you can take out your profit" — checked per sentence,
  // and only when the sentence asks you to pay (not "there is no fee").
  var WD = /(withdraw|nikal|निकाल|निकासी|release|unlock|profit|प्रॉफिट|मुनाफ|return\s*ke|रिटर्न)/i;
  var FEE = /(fee|charge|tax|commission|शुल्क|चार्ज|टैक्स|कमीशन|\d+\s*%)/i;
  var PAY = /(pay|deposit|send|bhej|pehle|before|first|जमा|भेज|पहले|दीजिए|kariye|करें)/i;
  var NOFEE = /(no\s+(fee|charge)|free\s+of|without\s+(any\s+)?(fee|charge)|निःशुल्क|बिना\s*शुल्क|कोई\s*शुल्क\s*नहीं)/i;
  function feeBeforeWithdraw(text) {
    var parts = text.split(/[.!?\n।]+/);
    for (var i = 0; i < parts.length; i++) {
      var p = parts[i];
      if (WD.test(p) && FEE.test(p) && PAY.test(p) && !NOFEE.test(p)) return true;
    }
    return false;
  }
  var STRONG = [
    /(send|transfer|bhej|भेज)[\s\S]{0,40}(money|amount|paise|पैसे|पैसा|रुपये|₹)[\s\S]{0,60}(invest|return|profit|रिटर्न|प्रॉफिट|मुनाफ|निवेश)/i,
    /(पैसे|पैसा|money|amount)[\s\S]{0,40}(भेज|send|transfer)[\s\S]{0,60}(निवेश|invest|रिटर्न|return|प्रॉफिट|profit)/i,
    /(guaranteed|assured|fixed|100%\s*sure|pakka|पक्का|गारंटी|गारंटीड)[\s\S]{0,25}(return|profit|income|रिटर्न|प्रॉफिट|मुनाफ|कमाई)/i,
    /(double|triple|2x|3x|5x|10x|दोगुना|डबल|तिगुना)[\s\S]{0,25}(money|paise|पैसे|पैसा|amount|investment|निवेश)/i,
    /(money|paise|पैसे|पैसा|investment)[\s\S]{0,25}(double|डबल|दोगुना)/i,
    /\d+\s*%\s*(daily|per\s*day|weekly|रोज़?|रोजाना|प्रतिदिन|हर\s*दिन)[\s\S]{0,20}(return|profit|रिटर्न|प्रॉफिट|मुनाफ)/i,
    /(we|i|हम|मैं)\s*(will)?\s*(trade|invest|निवेश|ट्रेड)[\s\S]{0,40}(for you|on your behalf|आपकी\s*तरफ|आपके\s*लिए)/i
  ];
  var MEDIUM = [
    /vip\s*(group|channel|membership|ग्रुप)/i,
    /(100%|sure\s*shot|jackpot|accurate)\s*(call|tip|calls|tips|कॉल)/i,
    /(limited|only\s*\d+)\s*(seats?|slots?|सीट)/i,
    /\bupi\s*(id)?\b|@ybl|@paytm|@okaxis|@oksbi|@okhdfcbank|@ibl/i,
    /(registration|joining|activation|membership)\s*(fee|charge|शुल्क)/i,
    /(telegram|whatsapp)[\s\S]{0,30}(join|जुड़|link)/i,
    /(profit|प्रॉफिट|मुनाफ|return|रिटर्न|paisa|पैसा)[\s\S]{0,30}(ready|तैयार|credited|आ\s*गया)/i,
    /(sebi\s*registered|सेबी\s*रजिस्टर्ड)/i,
    /(withdrawal|account|fund|profit|wallet|पैसा|खाता)\s*(is\s*|has\s*been\s*)?(locked|frozen|on\s*hold|लॉक|रुका|फ्रीज)/i
  ];

  function score(text) {
    if (!text) return 0;
    var s = feeBeforeWithdraw(text) ? 3 : 0, i;
    for (i = 0; i < STRONG.length; i++) if (STRONG[i].test(text)) s += 3;
    for (i = 0; i < MEDIUM.length; i++) if (MEDIUM[i].test(text)) s += 1;
    return s;
  }
  var THRESHOLD = 4; // one strong sign plus one more sign

  if (typeof module !== "undefined" && module.exports) {
    module.exports = { score: score, THRESHOLD: THRESHOLD };
    return;
  }
  if (window.__roninScam) return;
  window.__roninScam = true;
  window.__roninScamScore = score; // used by Ronin's "Is this a scam?" check

  // Payment Guard: a UPI payment link on a page that talks about returns or profit
  document.addEventListener("click", function (e) {
    var a = e.target && e.target.closest ? e.target.closest("a[href^='upi:'],a[href^='UPI:']") : null;
    if (!a) return;
    var txt = (document.body && document.body.innerText || "").slice(-40000);
    if (score(txt) >= 2) {
      var ok = window.confirm("Ronin Scam Shield\n\nThis page asks you to pay by UPI and talks about returns or profit. " +
        "A SEBI-registered adviser never takes money to trade for you, and their UPI ID ends with @valid.\n\nPay anyway?");
      if (!ok) { e.preventDefault(); e.stopPropagation(); }
    }
  }, true);

  var shown = false, timer = null;
  function check() {
    timer = null;
    if (shown || !document.body) return;
    var t = document.body.innerText || "";
    if (t.length > 40000) t = t.slice(-40000); // newest chat messages are at the end
    if (score(t) >= THRESHOLD) show();
  }
  function show() {
    shown = true;
    var d = document.createElement("div");
    d.setAttribute("role", "alert");
    d.style.cssText = "position:fixed;left:12px;right:12px;top:12px;z-index:2147483647;background:#7f1d1d;color:#fff;" +
      "font:15px/1.45 'Segoe UI',system-ui,sans-serif;padding:14px 46px 14px 16px;border-radius:12px;box-shadow:0 10px 30px rgba(0,0,0,.45)";
    var b = document.createElement("b");
    b.textContent = "Ronin Scam Shield: possible investment scam. ";
    var t = document.createElement("span");
    t.textContent = "This page talks about sending money for fixed or guaranteed profit, or a fee before you can take out profit. " +
      "That is how investment scams work. Do not send money. A SEBI-registered adviser never takes your money to trade for you. " +
      "If you already paid, call 1930 or report at cybercrime.gov.in at once.";
    var x = document.createElement("button");
    x.textContent = "✕";
    x.title = "Close";
    x.style.cssText = "position:absolute;right:10px;top:10px;background:transparent;border:0;color:#fff;font-size:18px;cursor:pointer";
    x.onclick = function () { d.remove(); };
    d.appendChild(b); d.appendChild(t); d.appendChild(x);
    document.documentElement.appendChild(d);
  }
  function schedule() { if (!timer && !shown) timer = setTimeout(check, 2500); }
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", schedule);
  else schedule();
  new MutationObserver(schedule).observe(document.documentElement, { childList: true, subtree: true, characterData: true });
})();
