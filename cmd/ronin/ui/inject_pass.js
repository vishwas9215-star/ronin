// Ronin passwords: notices a login (to offer "Save password?") and tells
// Ronin whether the page has a password box (to offer "Fill"). It never fills
// anything by itself: Ronin fills only after the user clicks Fill.
(function () {
  "use strict";
  if (window.top !== window || window.__roninPw) return;
  var K = "__ronin_pw";
  function visible(e) { return !!(e.offsetWidth || e.offsetHeight || e.getClientRects().length); }
  function pwFields() {
    return Array.prototype.filter.call(document.querySelectorAll("input[type=password]"), visible);
  }
  function userBefore(pw) {
    var scope = pw.form || document, ins = scope.querySelectorAll("input"), i = Array.prototype.indexOf.call(ins, pw);
    for (var j = i - 1; j >= 0; j--) {
      var t = (ins[j].type || "text").toLowerCase();
      if ((t === "text" || t === "email" || t === "tel") && visible(ins[j]) && ins[j].value) return ins[j].value;
    }
    try { return sessionStorage.getItem(K + "u") || ""; } catch (e) { return ""; } // step 1 of a two-step login
  }
  // The login is kept only in this page's memory (never written anywhere):
  // Ronin takes it at once when the page sends the form.
  var cap = null;
  function capture() {
    var f = pwFields().filter(function (x) { return x.value; });
    if (!f.length) return;
    cap = [userBefore(f[0]), f[0].value, Date.now()];
  }
  document.addEventListener("change", function (e) {
    var t = e.target;
    if (t && t.tagName === "INPUT" && /^(email|text|tel)$/i.test(t.type || "text") &&
        /user|mail|login|ident|phone|mobile|account/i.test((t.name || "") + " " + (t.id || "") + " " + (t.autocomplete || ""))) {
      try { sessionStorage.setItem(K + "u", t.value); } catch (err) {}
    }
  }, true);
  document.addEventListener("submit", capture, true);
  document.addEventListener("keydown", function (e) { if (e.key === "Enter" && e.target && e.target.type === "password") capture(); }, true);
  document.addEventListener("click", function (e) {
    var b = e.target && e.target.closest && e.target.closest("button,input[type=submit],[role=button]");
    if (b && pwFields().some(function (x) { return x.value; })) capture();
  }, true);
  Object.defineProperty(window, "__roninPw", { value: Object.freeze({
    take: function () {
      var r = cap; cap = null;
      if (!r || Date.now() - r[2] > 180000) return null;
      try { sessionStorage.removeItem(K + "u"); } catch (e) {}
      return [String(r[0] || ""), String(r[1] || "")]; // Ronin decides the site itself
    },
    has: function () { return pwFields().length > 0; }
  }) });
})();
