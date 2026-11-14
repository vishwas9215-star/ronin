// Ronin "Data -> Excel + PDF": collects the biggest table on the page, turns all its
// pages by itself, then checks it (repeated rows / names, names that look misspelt).
// Windows and Android run this same file; nothing leaves the device.
// Use: window.__roninTable.start(); poll window.__roninTable.state(); then window.__roninTable.result().
(function () {
  "use strict";
  if (window.__roninTable) return;
  var st = { phase: "idle", page: 0, rows: 0, error: "" }, out = null;

  var sleep = function (ms) { return new Promise(function (r) { setTimeout(r, ms); }); };
  // textContent keeps the spaces exactly as typed (a double space is a mistake to show)
  var txt = function (el) { return (el.textContent || "").replace(/[\r\n\t]+/g, " ").replace(/^ +| +$/g, ""); };
  var visible = function (el) { var r = el.getBoundingClientRect(); return r.width > 0 && r.height > 0; };

  // ---- find and read the table -------------------------------------------------------
  function tables() {
    var list = [];
    [].forEach.call(document.querySelectorAll("table"), function (t) {
      if (visible(t) && !t.closest("table table")) list.push({ el: t, kind: "table" });
    });
    [].forEach.call(document.querySelectorAll("[role=table],[role=grid],mat-table"), function (t) {
      if (visible(t) && t.tagName !== "TABLE") list.push({ el: t, kind: "role" });
    });
    return list;
  }
  function read(tb) {
    var head = [], rows = [];
    if (tb.kind === "table") {
      var trs = [].slice.call(tb.el.querySelectorAll("tr")).filter(function (tr) { return tr.closest("table") === tb.el; });
      trs.forEach(function (tr) {
        var cells = [].slice.call(tr.children).filter(function (c) { return c.tagName === "TD" || c.tagName === "TH"; });
        if (!cells.length) return;
        var vals = cells.map(txt);
        var isHead = !head.length && (tr.parentNode.tagName === "THEAD" || cells.every(function (c) { return c.tagName === "TH"; }));
        if (isHead) head = vals; else if (vals.join("").length) rows.push(vals);
      });
    } else {
      var rs = tb.el.querySelectorAll("[role=row],mat-row,mat-header-row,tr");
      [].forEach.call(rs, function (r) {
        var cells = r.querySelectorAll("[role=columnheader],[role=cell],[role=gridcell],mat-cell,mat-header-cell,td,th");
        var vals = [].map.call(cells, txt);
        if (!vals.length) return;
        var isHead = !head.length && r.querySelector("[role=columnheader],mat-header-cell,th");
        if (isHead) head = vals; else if (vals.join("").length) rows.push(vals);
      });
    }
    if (!head.length && rows.length) head = rows[0].map(function (_, i) { return "Column " + (i + 1); });
    return { head: head, rows: rows };
  }
  function best() {
    var b = null, n = -1;
    tables().forEach(function (t) { var r = read(t).rows.length; if (r > n) { n = r; b = t; } });
    return b;
  }

  // ---- paging -----------------------------------------------------------------------------
  var NEXT = /^(next|next\s*page|›|»|>|>>|अगला|आगे|अगले)$/i;
  function disabled(el) {
    for (var e = el, i = 0; e && i < 3; e = e.parentElement, i++) {
      if (e.disabled || e.getAttribute("aria-disabled") === "true" || /(^|\s)(disabled|ui-state-disabled)(\s|$)/.test(e.className || "")) return true;
    }
    return false;
  }
  function nextButton(tb) {
    var scope = tb.el.parentElement;
    for (var up = 0; up < 6 && scope; up++, scope = scope.parentElement) {
      var c = [].slice.call(scope.querySelectorAll("a,button,li,span,[role=button]")).filter(function (e) {
        if (!visible(e) || tb.el.contains(e)) return false;
        var l = (e.getAttribute("aria-label") || e.getAttribute("title") || "").trim();
        return NEXT.test(txt(e)) || /next page|^next$/i.test(l) || /(^|[\s_-])(next|paginate_button next)([\s_-]|$)/i.test(e.className || "");
      });
      if (c.length) {
        var e = c[c.length - 1];
        return disabled(e) ? null : e;
      }
    }
    return null;
  }
  function showAll(tb) {
    // DataTables: show every row at once
    try {
      var $ = window.jQuery;
      if ($ && $.fn && $.fn.dataTable && $.fn.dataTable.isDataTable(tb.el)) { $(tb.el).DataTable().page.len(-1).draw(); return true; }
    } catch (e) {}
    // a "rows per page" box: take the biggest choice
    var scope = tb.el.parentElement;
    for (var up = 0; up < 5 && scope; up++, scope = scope.parentElement) {
      var sel = scope.querySelector("select");
      if (sel && !tb.el.contains(sel)) {
        var bestOpt = null, max = 0;
        [].forEach.call(sel.options, function (o) {
          var v = /all|सभी/i.test(o.text) || o.value === "-1" ? 1e9 : parseInt(o.value || o.text, 10);
          if (v > max) { max = v; bestOpt = o; }
        });
        if (bestOpt && max >= 25 && sel.value !== bestOpt.value) {
          sel.value = bestOpt.value; sel.dispatchEvent(new Event("change", { bubbles: true })); return true;
        }
        return false;
      }
    }
    return false;
  }
  async function waitChange(sig) {
    for (var i = 0; i < 80; i++) {
      await sleep(150);
      var t = best();
      if (t && JSON.stringify(read(t).rows) !== sig) return t;
    }
    return null;
  }

  async function collect() {
    st = { phase: "reading", page: 1, rows: 0, error: "" };
    var tb = best();
    if (!tb || !read(tb).rows.length) throw new Error("No table found on this page.");
    var sig = JSON.stringify(read(tb).rows);
    if (showAll(tb)) { var t2 = await waitChange(sig); if (t2) tb = t2; }
    var first = read(tb), head = first.head, seen = {}, rows = [];
    var add = function (rs) { rs.forEach(function (r) { var k = r.join("\u0001"); if (!seen[k]) { seen[k] = 1; rows.push(r); } }); };
    add(first.rows); st.rows = rows.length;
    for (var p = 2; p <= 400; p++) {
      var nb = nextButton(tb);
      if (!nb) break;
      sig = JSON.stringify(read(tb).rows);
      nb.click();
      var t = await waitChange(sig);
      if (!t) break;
      tb = t; add(read(tb).rows);
      st.page = p; st.rows = rows.length;
    }
    return { title: document.title || location.hostname, site: location.hostname, head: head, rows: rows };
  }

  // ---- checking -----------------------------------------------------------------------------
  function norm(s) { return (s || "").toLowerCase().replace(/[.\s]+/g, " ").trim(); }
  function dist(a, b, max) {
    if (Math.abs(a.length - b.length) > max) return max + 1;
    var prev = [], cur = [], i, j;
    for (j = 0; j <= b.length; j++) prev[j] = j;
    for (i = 1; i <= a.length; i++) {
      cur = [i]; var low = i;
      for (j = 1; j <= b.length; j++) {
        cur[j] = Math.min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
        if (cur[j] < low) low = cur[j];
      }
      if (low > max) return max + 1;
      prev = cur;
    }
    return prev[b.length];
  }
  function check(d) {
    var head = d.head, rows = d.rows, issues = {}; // "r,c" -> {kind, note}
    var mark = function (r, c, kind, note) {
      var k = r + "," + c;
      if (issues[k] && issues[k].kind === "spell") return; // spelling stays red
      issues[k] = { kind: kind, note: note };
    };
    var serial = head.map(function (h, c) {
      if (/^(s\.?\s*no\.?|sr\.?\s*no\.?|sl\.?\s*no\.?|#|क्र|क्रम|क्रमांक|क्र\.?\s*सं)/i.test(h.trim())) return true;
      return rows.length > 2 && rows.every(function (r, i) { return String(r[c]).trim() === String(i + 1); });
    });
    var nameCols = [];
    head.forEach(function (h, c) { if (/name|नाम/i.test(h) && !/user\s*name|school|विद्यालय|स्कूल|block|district|class/i.test(h)) nameCols.push(c); });

    // whole rows that repeat (serial number left out)
    var rowKey = {}, dupRows = 0;
    rows.forEach(function (r, i) {
      var k = r.filter(function (_, c) { return !serial[c]; }).map(norm).join("|");
      if (rowKey[k] !== undefined) {
        dupRows++;
        r.forEach(function (_, c) { mark(i, c, "dup", "पूरी पंक्ति दोहराई गई (row " + (rowKey[k] + 1) + " जैसी)"); });
        r.forEach(function (_, c) { mark(rowKey[k], c, "dup", "पूरी पंक्ति दोहराई गई (row " + (i + 1) + " जैसी)"); });
      } else rowKey[k] = i;
    });

    // names
    var spell = 0, similar = 0, dupNames = 0;
    nameCols.forEach(function (c) {
      var byName = {}, list = [];
      rows.forEach(function (r, i) {
        var v = String(r[c] || ""), n = norm(v);
        if (!n) return;
        var why = "";
        if (/\d/.test(v)) why = "नाम में अंक है";
        else if (/[^\p{L}\p{M}\s.'\-]/u.test(v)) why = "नाम में अजीब चिह्न है";
        else if (/\s{2,}/.test(v) || v !== v.trim()) why = "दोहरी / फ़ालतू space";
        else if (/[a-z]/i.test(v) && /[ऀ-ॿ]/.test(v)) why = "हिंदी-English मिला हुआ";
        else if (/(.)\1\1/i.test(v)) why = "एक अक्षर तीन बार";
        else if (/[a-z]/.test(v) && v === v.toLowerCase() && /[A-Z]/.test(rows.map(function (x) { return x[c]; }).join(""))) why = "छोटे अक्षरों में (बाक़ी नाम बड़े अक्षर से)";
        if (why) { mark(i, c, "spell", why); spell++; }
        if (byName[n] !== undefined) { mark(i, c, "dupname", "यही नाम row " + (byName[n] + 1) + " में भी"); mark(byName[n], c, "dupname", "यही नाम row " + (i + 1) + " में भी"); dupNames++; }
        else { byName[n] = i; list.push({ n: n, i: i }); }
      });
      // names that differ by one or two letters: probably one of them is misspelt
      var buckets = {};
      list.forEach(function (x) { var k = x.n.charAt(0); (buckets[k] = buckets[k] || []).push(x); });
      Object.keys(buckets).forEach(function (k) {
        var b = buckets[k];
        for (var a = 0; a < b.length; a++) for (var z = a + 1; z < b.length; z++) {
          var A = b[a], Z = b[z];
          if (A.n.length < 5 || Z.n.length < 5) continue;
          var dd = dist(A.n, Z.n, 2);
          if (dd >= 1 && dd <= (A.n.length >= 9 ? 2 : 1)) {
            mark(A.i, c, "similar", "मिलता-जुलता नाम: " + rows[Z.i][c] + " (row " + (Z.i + 1) + ")");
            mark(Z.i, c, "similar", "मिलता-जुलता नाम: " + rows[A.i][c] + " (row " + (A.i + 1) + ")");
            similar++;
          }
        }
      });
    });
    var list = Object.keys(issues).map(function (k) { var p = k.split(","); return [+p[0], +p[1], issues[k].kind, issues[k].note]; });
    return { cells: list, nameCols: nameCols, counts: { rows: rows.length, dupRows: dupRows, dupNames: dupNames, spell: spell, similar: similar } };
  }

  window.__roninTable = {
    start: function () {
      if (st.phase === "reading") return "busy";
      out = null;
      collect().then(function (d) { d.check = check(d); out = d; st.phase = "done"; },
        function (e) { st.phase = "error"; st.error = String(e && e.message || e); });
      return "started";
    },
    state: function () { return JSON.stringify(st); },
    result: function () { return out ? JSON.stringify(out) : ""; },
    _check: check // for tests
  };
})();
