// node tools/jstest/adblock_test.js — checks the YouTube ad skipper.
const fs = require("fs"), path = require("path"), vm = require("vm");
const src = fs.readFileSync(path.join(__dirname, "../../cmd/ronin/ui/inject_adblock.js"), "utf8");
let tick = null;
const classes = new Set(["html5-video-player"]);
const vid = { muted: false, playbackRate: 1 };
let skipped = 0;
const player = { classList: { contains: c => classes.has(c) }, querySelector: () => vid };
const ctx = {
  window: {}, location: { hostname: "www.youtube.com" }, localStorage: { getItem: () => null },
  document: { documentElement: { appendChild() {} }, head: { appendChild() {} }, createElement: () => ({}),
    addEventListener() {}, querySelector: s => s.startsWith(".html5-video-player") ? player : (s.includes("skip") ? { click: () => skipped++ } : null) },
  setInterval: f => { tick = f; return 1; }, JSON,
};
vm.runInNewContext(src, ctx);
let fail = 0;
tick();
if (vid.playbackRate !== 1 || vid.muted) { console.log("changed a normal video"); fail++; }
classes.add("ad-showing"); tick();
if (vid.playbackRate !== 16 || !vid.muted || skipped !== 1) { console.log("ad not sped up/skipped", vid, skipped); fail++; }
classes.delete("ad-showing"); tick();
if (vid.playbackRate !== 1 || vid.muted) { console.log("video not restored", vid); fail++; }
if (JSON.parse("[1]")[0] !== 1) { console.log("JSON changed"); fail++; }
console.log(fail ? fail + " problem(s)" : "youtube ad rules OK");
process.exit(fail ? 1 : 0);
