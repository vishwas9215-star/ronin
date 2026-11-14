// Ronin fills one saved login, only when the page is still the same site.
// Ronin runs it as: fill_pass.js + "(" + JSON [origin, www-origin, user, password] + ")".
// The site check comes first and uses only location.origin and ===, which a
// page cannot change.
(function (a) {
  "use strict";
  if (location.origin !== a[0] && location.origin !== a[1]) return false;
  var u = a[2], p = a[3];
  function vis(e) { return !!(e.offsetWidth || e.offsetHeight || e.getClientRects().length); }
  function set(e, v) {
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value").set.call(e, v);
    e.dispatchEvent(new Event("input", { bubbles: true }));
    e.dispatchEvent(new Event("change", { bubbles: true }));
  }
  var pw = Array.prototype.filter.call(document.querySelectorAll("input[type=password]"), vis)[0];
  var scope = pw ? (pw.form || document) : document;
  var ins = Array.prototype.filter.call(scope.querySelectorAll("input"), vis), user = null;
  var i = pw ? ins.indexOf(pw) : ins.length;
  for (var j = i - 1; j >= 0; j--) {
    var t = (ins[j].type || "text").toLowerCase();
    if (t === "text" || t === "email" || t === "tel") { user = ins[j]; break; }
  }
  if (user && u) set(user, u);
  if (pw) { set(pw, p); pw.focus(); }
  return !!(pw || user);
})
