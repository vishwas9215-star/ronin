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
  // header rows (THEAD rows, or rows of only <th> at the top) are merged column by
  // column, with colspan / rowspan spread out, so "Marks" over "Maths | Hindi" gives
  // "Marks Maths" and "Marks Hindi"
  function headerNames(hrows) {
    var grid = [];
    hrows.forEach(function (cells, r) {
      grid[r] = grid[r] || [];
      var c = 0;
      cells.forEach(function (cell) {
        while (grid[r][c] !== undefined) c++;
        var cs = Math.max(1, parseInt(cell.getAttribute("colspan") || "1", 10) || 1);
        var rs = Math.max(1, parseInt(cell.getAttribute("rowspan") || "1", 10) || 1);
        for (var y = 0; y < rs && r + y < hrows.length; y++) {
          grid[r + y] = grid[r + y] || [];
          for (var x = 0; x < cs; x++) grid[r + y][c + x] = txt(cell);
        }
        c += cs;
      });
    });
    var n = 0; grid.forEach(function (g) { n = Math.max(n, g.length); });
    var names = [];
    for (var c = 0; c < n; c++) {
      var parts = [];
      grid.forEach(function (g) { var v = g[c]; if (v && parts[parts.length - 1] !== v) parts.push(v); });
      names.push(parts.join(" "));
    }
    return names;
  }
  function read(tb) {
    var hrows = [], rows = [];
    if (tb.kind === "table") {
      var trs = [].slice.call(tb.el.querySelectorAll("tr")).filter(function (tr) { return tr.closest("table") === tb.el; });
      trs.forEach(function (tr) {
        var cells = [].slice.call(tr.children).filter(function (c) { return c.tagName === "TD" || c.tagName === "TH"; });
        if (!cells.length) return;
        var isHead = !rows.length && (tr.parentNode.tagName === "THEAD" || cells.every(function (c) { return c.tagName === "TH"; }));
        if (isHead) hrows.push(cells);
        else { var vals = cells.map(txt); if (vals.join("").length) rows.push(vals); }
      });
    } else {
      var rs = tb.el.querySelectorAll("[role=row],mat-row,mat-header-row,tr");
      [].forEach.call(rs, function (r) {
        var cells = [].slice.call(r.querySelectorAll("[role=columnheader],[role=cell],[role=gridcell],mat-cell,mat-header-cell,td,th"));
        if (!cells.length) return;
        var isHead = !rows.length && r.querySelector("[role=columnheader],mat-header-cell,th");
        if (isHead) hrows.push(cells);
        else { var vals = cells.map(txt); if (vals.join("").length) rows.push(vals); }
      });
    }
    var head = headerNames(hrows), width = head.length;
    rows.forEach(function (r) { width = Math.max(width, r.length); });
    for (var i = head.length; i < width; i++) head.push("Column " + (i + 1));
    return { head: head, rows: rows };
  }
  function best() {
    var b = null, n = -1;
    tables().forEach(function (t) { var r = read(t).rows.length; if (r > n) { n = r; b = t; } });
    return b;
  }

  // ---- paging -----------------------------------------------------------------------------
  var NEXT = /^(next|next\s*page|›|>|अगला|आगे|अगले)$/i, LAST = /^(»|>>|last|अंतिम|last\s*page)$/i;
  function disabled(el) {
    for (var e = el, i = 0; e && i < 3; e = e.parentElement, i++) {
      if (e.disabled || e.getAttribute("aria-disabled") === "true" || /(^|\s)(disabled|ui-state-disabled)(\s|$)/.test(e.className || "")) return true;
    }
    return false;
  }
  // a link that would load a whole new page (all collected rows would be lost)
  function leavesPage(e) {
    var a = e.closest ? e.closest("a[href]") : null;
    if (!a) return false;
    var h = a.getAttribute("href") || "";
    if (!h || h.charAt(0) === "#" || /^javascript:/i.test(h)) return false;
    try { var u = new URL(a.href, location.href); return !(u.origin === location.origin && u.pathname === location.pathname && u.search === location.search); } catch (x) { return false; }
  }
  function nextButton(tb) {
    var scope = tb.el.parentElement;
    for (var up = 0; up < 6 && scope; up++, scope = scope.parentElement) {
      var exact = [], loose = [];
      [].forEach.call(scope.querySelectorAll("a,button,li,span,[role=button]"), function (e) {
        if (!visible(e) || tb.el.contains(e)) return;
        var t = txt(e), l = (e.getAttribute("aria-label") || e.getAttribute("title") || "").trim();
        if (LAST.test(t) || /last/i.test(l)) return;
        if (NEXT.test(t) || /^next( page)?$/i.test(l)) exact.push(e);
        else if (/(^|[\s_-])(next|paginate_button next)([\s_-]|$)/i.test(e.className || "")) loose.push(e);
      });
      var c = exact.length ? exact : loose;
      // the innermost one is the real button (<li><a>Next</a></li>: the <a>)
      c = c.filter(function (e) { return !c.some(function (o) { return o !== e && e.contains(o); }); });
      if (c.length) {
        var e = c[0];
        if (disabled(e)) return null;
        e.__roninLeaves = leavesPage(e); // a separate web page: carried on after it loads
        return e;
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
    for (var i = 0; i < 300 && !stopNow; i++) { // up to 45 s: school portals can be slow
      await sleep(150);
      var t = best();
      if (t && JSON.stringify(read(t).rows) !== sig) { await sleep(300); return best(); }
    }
    return null;
  }

  // how much there is: "Showing 1 to 10 of 53 entries", "1-10 of 53", or the highest page number
  function totals(tb) {
    var out = { rows: 0, pages: 0 };
    var scope = tb.el.parentElement;
    for (var up = 0; up < 6 && scope && !out.rows; up++, scope = scope.parentElement) {
      var text = (scope.innerText || "").replace(/,/g, "");
      var pg = text.match(/page\s+\d+\s+of\s+(\d+)/i) || text.match(/पेज\s+\d+\s*\/\s*(\d+)/);
      if (pg && !out.pages) out.pages = +pg[1];
      var m = text.match(/\d+\s*(?:to|-|–)\s*\d+\s+of\s+(\d+)/i) || text.match(/\bof\s+(\d+)\s+(?:entries|records|rows|items|results)/i) ||
        text.match(/(?:कुल|total)\s*(?:records?|entries|रिकॉर्ड|पंक्तियाँ|छात्र)?\s*[:：-]\s*(\d+)/i);
      if (m && +m[1] > 0 && +m[1] < 1000000) out.rows = +m[1];
      if (!out.pages) {
        var nums = [].map.call(scope.querySelectorAll("a,button,li,span"), function (e) { var t = txt(e); return /^\d{1,4}$/.test(t) && !tb.el.contains(e) ? +t : 0; });
        var mx = Math.max.apply(null, nums.concat([0]));
        if (mx > 1) out.pages = mx;
      }
    }
    return out;
  }

  // a small progress box on the page, so it is clear that work is going on and how far
  function badge(text, pct, done) {
    var b = document.getElementById("ronin-data-progress");
    if (!b) {
      if (!document.body) return;
      b = document.createElement("div");
      b.id = "ronin-data-progress";
      b.style.cssText = "position:fixed;right:16px;bottom:16px;z-index:2147483647;min-width:240px;max-width:340px;padding:12px 14px;border-radius:14px;background:#111827;color:#fff;font:600 15px sans-serif;box-shadow:0 4px 18px rgba(0,0,0,.4)";
      var t = document.createElement("div"); t.className = "t"; t.style.whiteSpace = "pre-line";
      var bar = document.createElement("div");
      bar.style.cssText = "height:8px;border-radius:4px;background:#374151;margin-top:8px;overflow:hidden";
      var fill = document.createElement("div"); fill.className = "f";
      fill.style.cssText = "height:100%;width:0;background:#22c55e;transition:width .4s";
      bar.appendChild(fill); b.appendChild(t); b.appendChild(bar);
      document.body.appendChild(b);
    }
    var btn = document.getElementById("ronin-data-btn");
    if (btn) btn.style.display = done ? "" : "none";
    b.querySelector(".t").textContent = text;
    var f = b.querySelector(".f");
    if (pct == null) { f.style.width = "100%"; f.style.opacity = ".35"; } else { f.style.opacity = "1"; f.style.width = Math.max(3, Math.min(100, pct)) + "%"; }
    if (done) setTimeout(function () { if (b.parentNode) b.parentNode.removeChild(b); }, 6000);
  }
  function progress() {
    var pct = st.total ? Math.round(st.rows * 100 / st.total) : st.pages ? Math.round(st.page * 100 / st.pages) : null;
    if (pct != null) pct = Math.min(99, pct); // 100 only when the files are made
    st.pct = pct;
    badge("📊 Data पढ़ रहा हूँ" + (pct != null ? ": " + pct + "%" : "...") + "\nपेज " + st.page + (st.pages ? " / " + st.pages : "") + " · " + st.rows + (st.total ? " / " + st.total : "") + " पंक्तियाँ", pct);
  }

  // When "Next" loads a whole new web page, what was read so far waits in this tab's
  // sessionStorage; Ronin puts this script back on the new page and it carries on (resume).
  var KEY = "ronin.export:" + location.origin + location.pathname;
  function hash(s) { var h = 5381; for (var i = 0; i < s.length; i++) h = (h * 33 + s.charCodeAt(i)) | 0; return String(h); }
  function saved() { try { var x = JSON.parse(sessionStorage.getItem(KEY) || "null"); return x && Date.now() - x.at < 15 * 60000 ? x : null; } catch (e) { return null; } }
  function keep(x) { try { x.at = Date.now(); sessionStorage.setItem(KEY, JSON.stringify(x)); return true; } catch (e) { return false; } }
  function forget() { try { sessionStorage.removeItem(KEY); } catch (e) {} }
  async function waitTable() {
    for (var i = 0; i < 300 && !stopNow; i++) { var t = best(); if (t && read(t).rows.length) return t; await sleep(150); }
    return null;
  }

  var stopNow = false;
  async function collect(resume) {
    var back = resume ? saved() : null;
    if (resume && !back) throw new Error("nothing to continue");
    st = { phase: "reading", page: back ? back.page : 1, rows: 0, error: "", partial: "", total: back ? back.total : 0, pages: back ? back.pages : 0, pct: null };
    stopNow = false;
    if (!back) { forget(); badge("📊 Data पढ़ना शुरू... (रुकिए, page बंद न करें)", null); }
    var tb = back ? await waitTable() : best();
    if (!tb || !read(tb).rows.length) { forget(); badge("⚠ इस page पर table नहीं मिली", 100, true); throw new Error("No table found on this page."); }
    if (!back) { var tot = totals(tb); st.total = tot.rows; st.pages = tot.pages; }
    var sig = JSON.stringify(read(tb).rows);
    if (!back && showAll(tb)) { var t2 = await waitChange(sig); if (t2) tb = t2; }
    var first = read(tb), head = back ? back.head : first.head, seenPages = {}, rows = back ? back.rows : [];
    var perPage = back ? back.perPage : first.rows.length;
    if (back) back.seen.forEach(function (k) { seenPages[k] = 1; });
    var add = function (rs) {
      var k = hash(JSON.stringify(rs));
      if (seenPages[k]) return false; // the same page came back: paging is finished
      seenPages[k] = 1;
      rows.push.apply(rows, rs);
      return true;
    };
    var fresh = add(first.rows);
    if (back) { st.page = back.page + (fresh ? 1 : 0); }
    st.rows = rows.length; progress();
    for (var p = st.page + 1; p <= 1000 && !stopNow && (fresh || !back); p++) {
      var nb = nextButton(tb);
      if (!nb) break;
      sig = JSON.stringify(read(tb).rows);
      // ready for a whole new page: if this one goes away, the next one carries on from here
      keep({ head: head, rows: rows, seen: Object.keys(seenPages), page: st.page, total: st.total, pages: st.pages, perPage: perPage });
      if (nb.__roninLeaves) { st.phase = "navigating"; nb.click(); return new Promise(function () {}); }
      nb.click();
      var t = await waitChange(sig);
      if (!t) { if (!stopNow) st.partial = "page " + p + " did not load"; break; }
      tb = t;
      var got = read(tb).rows;
      if (!add(got)) break;
      st.page = p; st.rows = rows.length; progress();
      if (got.length < perPage) break; // a shorter page is the last one
    }
    forget();
    if (stopNow) st.partial = "stopped at page " + st.page;
    badge("📊 " + rows.length + " पंक्तियाँ पढ़ लीं (" + (st.partial ? "अधूरा" : "100%") + ")\nExcel + PDF बन रही है...", st.partial ? st.pct : 100);
    var w = head.length;
    rows.forEach(function (r) { w = Math.max(w, r.length); });
    for (var i = head.length; i < w; i++) head.push("Column " + (i + 1));
    return { title: document.title || location.hostname, site: location.hostname, head: head, rows: rows, partial: st.partial };
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
    var nameCols = [], PARENT = /father|mother|guardian|parent|husband|पिता|माता|अभिभावक|पति/i;
    head.forEach(function (h, c) { if (/name|नाम/i.test(h) && !/user\s*name|school|विद्यालय|स्कूल|block|district|class|bank|branch|village|ग्राम/i.test(h)) nameCols.push(c); });
    var personCol = -1, fatherCol = -1, dobCol = -1;
    nameCols.forEach(function (c) { if (personCol < 0 && !PARENT.test(head[c])) personCol = c; if (fatherCol < 0 && /father|पिता/i.test(head[c])) fatherCol = c; });
    head.forEach(function (h, c) { if (dobCol < 0 && /dob|birth|जन्म/i.test(h)) dobCol = c; });

    // whole rows that repeat (serial number left out)
    var rowKey = {}, dupRows = 0;
    rows.forEach(function (r, i) {
      var k = r.filter(function (_, c) { return !serial[c]; }).map(norm).join("|");
      if (rowKey[k] !== undefined) {
        dupRows++;
        r.forEach(function (_, c) { mark(i, c, "dup", "पीला: पूरी पंक्ति दो बार (row " + (rowKey[k] + 1) + " जैसी)"); });
        r.forEach(function (_, c) { mark(rowKey[k], c, "dup", "पीला: पूरी पंक्ति दो बार (row " + (i + 1) + " जैसी)"); });
      } else rowKey[k] = i;
    });

    // names
    var spell = 0, similar = 0, dupNames = 0;
    // the same child entered twice: same name AND same father's name (or date of birth)
    if (personCol >= 0) {
      var seenKid = {};
      rows.forEach(function (r, i) {
        var n = norm(r[personCol]);
        if (!n) return;
        var extra = fatherCol >= 0 ? norm(r[fatherCol]) : dobCol >= 0 ? norm(r[dobCol]) : "";
        if (fatherCol < 0 && dobCol < 0) return; // only a name: two children can share it
        var k = n + "|" + extra;
        if (seenKid[k] !== undefined) {
          var j = seenKid[k], how = fatherCol >= 0 ? "नाम और पिता का नाम" : "नाम और जन्म तिथि";
          mark(i, personCol, "dupname", "पीला: यही बच्चा row " + (j + 1) + " में भी (" + how + " एक जैसे)");
          mark(j, personCol, "dupname", "पीला: यही बच्चा row " + (i + 1) + " में भी (" + how + " एक जैसे)");
          dupNames++;
        } else seenKid[k] = i;
      });
    }
    nameCols.forEach(function (c) {
      var byName = {}, list = [];
      rows.forEach(function (r, i) {
        var v = String(r[c] || ""), n = norm(v);
        if (!n) return;
        var why = ""; // small / capital letters are NOT a mistake
        if (/\d/.test(v)) why = "लाल: नाम में अंक (0-9) है";
        else if (/[^\p{L}\p{M}\s.'\-]/u.test(v)) why = "लाल: नाम में अजीब चिह्न है";
        else if (/\s{2,}/.test(v) || v !== v.trim()) why = "लाल: दो space एक साथ";
        else if (/[a-z]/i.test(v) && /[\u0900-\u097f]/.test(v)) why = "लाल: हिंदी और English मिले हुए";
        else if (/(.)\1\1/i.test(v)) why = "लाल: एक अक्षर तीन बार";
        if (why) { mark(i, c, "spell", why); spell++; }
        if (byName[n] === undefined) { byName[n] = i; list.push({ n: n, i: i }); }
      });
      // the same name typed two ways: same words, one long word (6+ letters) differs by
      // one letter ("Rahul Kumar" / "Rahul Kumr"). "Ram Kumar" / "Raj Kumar" are different people.
      var buckets = {}, freq = {};
      rows.forEach(function (r) { norm(r[c]).split(" ").forEach(function (w) { if (w) freq[w] = (freq[w] || 0) + 1; }); });
      list.forEach(function (x) { var w = x.n.split(" "); (buckets[w.length] = buckets[w.length] || []).push({ n: x.n, i: x.i, w: w }); });
      Object.keys(buckets).forEach(function (k) {
        var b = buckets[k], byRest = {};
        b.forEach(function (x) {
          x.w.forEach(function (_, j) {
            var key = j + ":" + x.w.filter(function (_, q) { return q !== j; }).join(" ");
            (byRest[key] = byRest[key] || []).push(x);
          });
        });
        Object.keys(byRest).forEach(function (key) {
          var g = byRest[key], j = +key.split(":")[0];
          if (g.length < 2 || g.length > 30) return;
          for (var a = 0; a < g.length; a++) for (var z = a + 1; z < g.length; z++) {
            var A = g[a].w[j], Z = g[z].w[j];
            if (A === Z || Math.min(A.length, Z.length) < 4 || dist(A, Z, 1) !== 1) continue;
            // a letter missing or extra ("Kumr" / "Kumar") is a typing slip; a changed letter
            // ("Rakesh" / "Ramesh") is usually another name, unless one form is common and the other rare
            var fa = freq[A] || 0, fz = freq[Z] || 0;
            if (fa >= 2 && fz >= 2) continue; // both spellings are used often: two real names (Kumar / Kumari)
            if (A.length === Z.length && !((fa >= 3 && fz === 1) || (fz >= 3 && fa === 1))) continue;
            var ia = g[a].i, iz = g[z].i;
            mark(ia, c, "similar", "नारंगी: spelling जाँचें, row " + (iz + 1) + " में \"" + rows[iz][c] + "\" लिखा है");
            mark(iz, c, "similar", "नारंगी: spelling जाँचें, row " + (ia + 1) + " में \"" + rows[ia][c] + "\" लिखा है");
            similar++;
          }
        });
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
    // after "Next" loaded a new web page: carry on with what was read before ("none" = nothing saved)
    resume: function () {
      if (st.phase === "reading") return "busy";
      if (!saved()) return "none";
      out = null;
      st.phase = "reading";
      collect(true).then(function (d) { d.check = check(d); out = d; st.phase = "done"; },
        function (e) { st.phase = "error"; st.error = String(e && e.message || e); });
      return "resumed";
    },
    state: function () { return JSON.stringify(st); },
    stop: function () { stopNow = true; return "stopping"; },
    badge: function (text, pct, done) { badge(text, pct, done); return "ok"; }, // Ronin says when the files are saved
    result: function () { return out ? JSON.stringify(out) : ""; },
    _check: check // for tests
  };
})();
