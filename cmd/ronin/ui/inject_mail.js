// Ronin Safe Mail (laptop only): an opened email in Gmail, Outlook, Yahoo, Rediffmail... that looks like a
// scam / threat ("CBI digital arrest", "you watched illegal videos, pay the fine") gets a red bar with
// [Delete] [Spam] [1930]. The check is window.RoninScam.mail (ui/tools/scamcheck.js); nothing leaves the
// computer. Only the email sites run it (the Go side adds this script with the engine in front of it).
(function () {
  "use strict";
  if (window.__roninMailDone) return;
  window.__roninMailDone = true;
  var S = window.RoninScam;
  if (!S) return;
  var EN = String(window.__roninLang || navigator.language || "en").toLowerCase().indexOf("hi") !== 0;
  function L(hi, en) { return EN ? en : hi; }
  function sheet(css) {
    try { var s = new CSSStyleSheet(); s.replaceSync(css); document.adoptedStyleSheets = document.adoptedStyleSheets.concat([s]); return; } catch (e) {}
    var st = document.createElement("style"); st.textContent = css; (document.head || document.documentElement).appendChild(st);
  }
  var F = "font-family:'Nirmala UI','Noto Sans Devanagari','Segoe UI',sans-serif;";
  sheet(".ronin-bar{position:fixed;left:50%;transform:translateX(-50%);width:min(760px,calc(100% - 16px));top:64px;z-index:2147483645;padding:14px 16px;border-radius:14px;" + F + "font-size:16px;line-height:1.45;box-shadow:0 6px 24px rgba(0,0,0,.35);max-height:70vh;overflow:auto}" +
    ".ronin-bar.red{background:#fde2e2;color:#7f1010;border:3px solid #dc2626}.ronin-bar.amber{background:#fff3cd;color:#6b3f00;border:3px solid #f0b43c}" +
    ".ronin-bar b{display:block;font-size:18px;margin-bottom:4px}.ronin-bar ul{margin:4px 0 6px 18px;padding:0}.ronin-bar .row{display:flex;flex-wrap:wrap;gap:8px;margin-top:10px}" +
    ".ronin-bar .row button,.ronin-bar .row a{min-height:48px;padding:0 16px;font-size:16px;display:inline-flex;align-items:center;border:0;border-radius:12px;cursor:pointer;text-decoration:none;" + F + "font-weight:700}" +
    ".ronin-bar .del{background:#dc2626;color:#fff}.ronin-bar .spam{background:#7c2d12;color:#fff}.ronin-bar .call{background:#1d4ed8;color:#fff!important}.ronin-bar .hide{background:#e5e7eb;color:#111}" +
    "#ronin-mail-on{position:fixed;left:50%;transform:translateX(-50%);bottom:14px;z-index:2147483646;padding:10px 18px;border-radius:12px;" + F + "font-weight:600;font-size:15px;background:#dcfce7;color:#14532d;border:2px solid #16a34a;box-shadow:0 4px 14px rgba(0,0,0,.3)}");
  function el(tag, cls, txt) { var e = document.createElement(tag); if (cls) e.className = cls; if (txt != null) e.textContent = txt; return e; }
  function put(e) { (document.body || document.documentElement).appendChild(e); }
  function txt(sel, root) { var n = (root || document).querySelector(sel); return n ? (n.innerText || n.textContent || "").trim() : ""; }

  function readMail() {
    var m = { name: "", from: "", subject: "", body: "", files: [] }, main = document.querySelector("[role=main]") || document.body;
    if (/mail\.google\.com$/.test(location.hostname)) {
      m.subject = txt("h2.hP") || txt("h2[data-thread-perm-id]") || txt("[role=main] h2");
      var who = main.querySelector("span.gD[email], h3 span[email], span[email]");
      if (who) { m.from = who.getAttribute("email") || ""; m.name = who.getAttribute("name") || who.textContent || ""; }
      var bodies = main.querySelectorAll(".a3s");
      m.body = bodies.length ? (bodies[bodies.length - 1].innerText || "") : "";
      main.querySelectorAll("[download_url], .aV3, [aria-label^='Attachment']").forEach(function (a) {
        var d = a.getAttribute("download_url"), n = d ? d.split(":")[1] : (a.textContent || a.getAttribute("aria-label") || "").replace(/^Attachment:\s*/i, "");
        if (n) m.files.push(n.trim());
      });
      // Gmail's inbox list is not an opened email
      if (!m.body && !m.subject) return null;
    } else if (/outlook\./.test(location.hostname)) {
      m.subject = txt("[role=main] [role=heading][aria-level='2']") || txt("#ConversationReadingPaneContainer [role=heading]");
      var o = main.querySelector("[aria-label*='@'], span[title*='@']");
      if (o) { var s = o.getAttribute("title") || o.getAttribute("aria-label") || ""; m.from = (s.match(/[\w.+-]+@[\w-]+(\.[\w-]+)+/) || [""])[0]; m.name = (o.textContent || "").replace(m.from, "").replace(/[<>]/g, "").trim(); }
      m.body = txt("[aria-label='Message body']") || txt("div[role=document]");
      main.querySelectorAll("[role=listitem][title], [data-testid='AttachmentCard'] [title]").forEach(function (a) { if (/\.\w{2,4}$/.test(a.getAttribute("title"))) m.files.push(a.getAttribute("title")); });
    } else if (/yahoo\.com$/.test(location.hostname)) {
      m.subject = txt("[data-test-id=message-group-subject-text]");
      var y = main.querySelector("[data-test-id=message-from] [title], [data-test-id=email-pill] [title]");
      if (y) { m.from = y.getAttribute("title") || ""; m.name = y.textContent || ""; }
      m.body = txt("[data-test-id=message-view-body-content]");
      main.querySelectorAll("[data-test-id=attachment-name], [data-test-id=media-title]").forEach(function (a) { m.files.push(a.textContent.trim()); });
    }
    if (!m.body) {
      // any other email site: the reading area's text and the first address in it
      var area = document.querySelector("[role=main] [role=document], [role=main] article, [role=main]") || document.body;
      var all = (area.innerText || "").slice(0, 8000);
      if (all.length < 40) return null;
      m.body = all; m.subject = m.subject || document.title;
      m.from = m.from || (all.match(/[\w.+-]+@[\w-]+(\.[\w-]+)+/) || [""])[0];
    }
    return m;
  }
  function findButton(re, notRe) {
    var c = document.querySelectorAll("[role=button],button,[act],[data-tooltip],[aria-label]");
    for (var i = 0; i < c.length; i++) {
      var b = c[i], lab = (b.getAttribute("aria-label") || b.getAttribute("data-tooltip") || b.getAttribute("title") || "").trim();
      if (!lab || !re.test(lab) || (notRe && notRe.test(lab))) continue;
      if (b.closest(".ronin-bar")) continue;
      var r = b.getBoundingClientRect();
      if (r.width > 0 && r.height > 0) return b;
    }
    return null;
  }
  function press(b) {
    ["mousedown", "mouseup", "click"].forEach(function (t) { try { b.dispatchEvent(new MouseEvent(t, { bubbles: true, cancelable: true, view: window })); } catch (e) {} });
  }
  var DEL = /^(delete|हटाएं|हटाएँ|move to trash|trash|ट्रैश|मिटाएं)/i, SPAM = /^(report spam|spam|junk|report junk|स्पैम|move to junk|mark as spam)/i, NOTSPAM = /not spam|स्पैम नहीं/i;
  var seen = "", timer = 0;
  function check() {
    var bar = document.getElementById("ronin-mail");
    var m = readMail();
    var sig = m ? (m.from + "|" + m.subject + "|" + m.body.length) : "";
    if (!m) { if (bar) bar.remove(); seen = ""; return; }
    if (sig === seen) return;
    seen = sig;
    if (bar) bar.remove();
    var r = S.mail(m);
    window.__roninMailLast = r; // for the tests
    if (!r.level) return;
    bar = el("div", "ronin-bar " + (r.level === "stop" ? "red" : "amber")); bar.id = "ronin-mail";
    bar.appendChild(el("b", "", r.level === "stop" ? L("⛔ यह email ठगी / धमकी वाला हो सकता है", "⛔ This email may be a scam / threat") : L("⚠ सावधान: इस email में ठगी के संकेत हैं", "⚠ Careful: this email has signs of a scam")));
    var ul = el("ul");
    r.reasons.slice(0, 4).forEach(function (x) { ul.appendChild(el("li", "", x)); });
    bar.appendChild(ul);
    bar.appendChild(el("div", "", L("कोई जवाब न दें, कोई link या attachment न खोलें, कोई पैसा न भेजें। पुलिस / CBI / कोर्ट कभी email से जुर्माना नहीं माँगते।", "Do not reply, do not open any link or attachment, do not send money. Police / CBI / courts never ask for fines by email.")));
    var row = el("div", "row");
    var del = el("button", "del", L("🗑 Delete करें", "🗑 Delete")), spam = el("button", "spam", L("🚫 Spam में डालें", "🚫 Report spam"));
    var call = el("a", "call", "📞 1930"); call.href = "tel:1930";
    var hide = el("button", "hide", L("छिपाएँ", "Hide"));
    del.onclick = function () { var b = findButton(DEL); if (b) { press(b); bar.remove(); } else del.textContent = L("ऊपर 🗑 (Delete) का निशान दबाएँ", "Click the 🗑 (Delete) icon at the top"); };
    spam.onclick = function () { var b = findButton(SPAM, NOTSPAM); if (b) { press(b); spam.textContent = L("✔ Spam में डाला", "✔ Reported as spam"); } else spam.textContent = L("ऊपर ⋮ में Report spam दबाएँ", "Click ⋮ at the top, then Report spam"); };
    hide.onclick = function () { bar.remove(); };
    row.appendChild(del); row.appendChild(spam); row.appendChild(call); row.appendChild(hide);
    bar.appendChild(row);
    put(bar);
  }
  function later() { clearTimeout(timer); timer = setTimeout(function () { try { check(); } catch (e) {} }, 400); }
  function start() {
    var c = el("div", "", L("🛡 Safe Mail चालू: हर खोला गया email ठगी / धमकी के लिए जाँचा जा रहा है", "🛡 Safe Mail is on: every email you open is checked for scams / threats")); c.id = "ronin-mail-on";
    put(c); setTimeout(function () { c.remove(); }, 6000);
    later();
    new MutationObserver(later).observe(document.documentElement, { childList: true, subtree: true });
    addEventListener("hashchange", later);
  }
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", start); else start();
  window.__roninMailCheck = check; // for the tests
})();
