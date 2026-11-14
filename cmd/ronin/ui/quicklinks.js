// Small quick links on the new tab page (Windows and Android use this same file).
// Data: window.RONIN_QL = {k: "<nonce>", sections: [{id: "School", title: "School", links: [{n: "Name", u: "https://..."}]}]}
// Changes go to Ronin as https://ronin.newtab/add?... or /del?... (Ronin only accepts them from its own new tab page).
(function () {
  var d = window.RONIN_QL, root = document.getElementById("ql");
  if (!d || !root) return;
  if (d.edit) root.classList.add("edit"); // still editing after an add / remove
  var css = document.createElement("style");
  css.textContent =
    "#ql h2{font-size:12px;text-transform:uppercase;letter-spacing:.08em;color:var(--muted,#9aa3b2);margin:18px 0 8px;font-weight:600}" +
    "#ql .chips{display:flex;flex-wrap:wrap;gap:8px}" +
    "#ql .chip{display:inline-flex;align-items:center;gap:6px;min-height:40px;padding:0 14px;border-radius:20px;background:var(--card,#1c2029);" +
    "border:1px solid var(--line,#2e3441);color:var(--text,#e8eaf0);text-decoration:none;font-size:14px;white-space:nowrap}" +
    "#ql .chip:hover{border-color:var(--accent,#3b82f6)}" +
    "#ql .x{display:none;align-items:center;justify-content:center;min-width:40px;min-height:40px;margin:0 -10px 0 2px;font-size:22px;line-height:1;color:#f87171}" +
    "#ql.edit .x{display:inline-flex}#ql.edit .chip{pointer-events:none}#ql.edit .chip .x{pointer-events:auto}" +
    "#ql .add{display:none;min-height:40px;padding:0 14px;border-radius:20px;border:1px dashed var(--line,#2e3441);background:none;color:var(--muted,#9aa3b2);font:inherit;font-size:14px;cursor:pointer}" +
    "#ql.edit .add{display:inline-flex;align-items:center}" +
    "#ql form.new{display:flex;flex-wrap:wrap;gap:8px;margin-top:8px}" +
    "#ql form.new input{height:40px;flex:1 1 160px;min-width:0;padding:0 12px;border-radius:10px;border:1px solid var(--line,#2e3441);background:var(--card,#1c2029);color:var(--text,#e8eaf0);font:inherit}" +
    "#ql form.new button,#ql .edbtn{height:40px;padding:0 16px;border-radius:20px;border:0;background:var(--accent,#3b82f6);color:#fff;font:inherit;font-weight:600;cursor:pointer}" +
    "#ql .edbtn{margin-top:18px;background:none;border:1px solid var(--line,#2e3441);color:var(--muted,#9aa3b2);font-weight:400}";
  document.head.appendChild(css);
  var go = function (path, q) {
    var s = "https://ronin.newtab/" + path + "?k=" + encodeURIComponent(d.k);
    for (var key in q) s += "&" + key + "=" + encodeURIComponent(q[key]);
    location.href = s;
  };
  d.sections.forEach(function (sec) {
    var h = document.createElement("h2"); h.textContent = sec.title; root.appendChild(h);
    var box = document.createElement("div"); box.className = "chips"; box.dataset.s = sec.id; root.appendChild(box);
    sec.links.forEach(function (l) {
      var a = document.createElement("a"); a.className = "chip"; a.href = l.u; a.title = l.u; a.textContent = l.n;
      var x = document.createElement("span"); x.className = "x"; x.textContent = "×"; x.title = "Remove";
      x.onclick = function (e) { e.preventDefault(); e.stopPropagation(); go("del", {u: l.u}); };
      if (!sec.fixed) a.appendChild(x); // a fixed row (Safe Social) is changed in its own settings
      box.appendChild(a);
    });
    var add = document.createElement("button"); add.className = "add"; add.type = "button"; add.textContent = "+ जोड़ें / Add";
    add.onclick = function () {
      if (box.nextSibling && box.nextSibling.className === "new") return;
      var f = document.createElement("form"); f.className = "new";
      var n = document.createElement("input"); n.placeholder = "नाम / Name";
      var u = document.createElement("input"); u.placeholder = "पता / Address (e.g. kite.zerodha.com)"; u.inputMode = "url";
      var b = document.createElement("button"); b.textContent = "Save";
      f.onsubmit = function (e) { e.preventDefault(); if (u.value.trim()) go("add", {s: sec.id, n: n.value, u: u.value}); };
      f.append(n, u, b); box.parentNode.insertBefore(f, box.nextSibling); n.focus();
    };
    if (!sec.fixed) box.appendChild(add);
  });
  var ed = document.createElement("button"); ed.className = "edbtn"; ed.type = "button";
  var label = function () { ed.textContent = root.classList.contains("edit") ? "✓ Done" : "✎ Links बदलें / Edit links"; };
  ed.onclick = function () { root.classList.toggle("edit"); label(); };
  label(); root.appendChild(ed);
})();
