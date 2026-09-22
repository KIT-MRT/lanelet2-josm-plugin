// Load a whole .osm map into the viewer (headless) and report timings.
//   node testdata/viewer3d/perf_viewer.mjs path/to/lanelet2_map.osm [--shots DIR]
//
// Converts the map like Viewer3dFeatures.featureForWay does (protocol v2; ENU around the
// bbox centre of all way nodes, `ele` as z, type/subtype/participant:bicycle
// tags), streams it as one snapshot, and measures how long the page takes to
// show it, plus draw calls and frame time. Software GL (SwiftShader): frame
// times are CPU-bound and only comparable between runs on this machine.
import fs from "node:fs";
import readline from "node:readline";
import { openViewer, sleep } from "./harness.mjs";

const mapPath = process.argv[2];
if (!mapPath) {
  console.error("usage: perf_viewer.mjs map.osm [--shots DIR]");
  process.exit(2);
}
const shotsIdx = process.argv.indexOf("--shots");
const shotsDir = shotsIdx > 0 ? process.argv[shotsIdx + 1] : null;

const R = 6378137.0;
const rad = (d) => (d * Math.PI) / 180;
const attr = (s, k) => {
  const m = s.match(new RegExp(`\\b${k}=["']([^"']*)["']`));
  return m ? m[1] : null;
};

async function readOsm(path) {
  const nodes = new Map(); // id -> [lat, lon, ele]
  const ways = [];
  const lanelets = [];     // { id, left, right, tags }
  let cur = null;
  let curKind = null;
  const rl = readline.createInterface({ input: fs.createReadStream(path), crlfDelay: Infinity });
  for await (const raw of rl) {
    const s = raw.trim();
    if (s.startsWith("<node")) {
      cur = [Number(attr(s, "lat")), Number(attr(s, "lon")), 0];
      curKind = "node";
      nodes.set(Number(attr(s, "id")), cur);
      if (s.endsWith("/>")) curKind = null;
    } else if (s.startsWith("<way")) {
      cur = { id: Number(attr(s, "id")), nds: [], tags: {} };
      curKind = "way";
      ways.push(cur);
    } else if (s.startsWith("<relation")) {
      cur = { id: Number(attr(s, "id")), left: null, right: null, tags: {} };
      curKind = "rel";
      lanelets.push(cur);
    } else if (s.startsWith("<member") && curKind === "rel") {
      const role = attr(s, "role");
      if (attr(s, "type") === "way" && (role === "left" || role === "right")) cur[role] = Number(attr(s, "ref"));
    } else if (s.startsWith("<nd ") && curKind === "way") {
      cur.nds.push(Number(attr(s, "ref")));
    } else if (s.startsWith("<tag")) {
      const k = attr(s, "k");
      const v = attr(s, "v");
      if (curKind === "node" && k === "ele") cur[2] = Number(v) || 0;
      else if (curKind === "way" || curKind === "rel") cur.tags[k] = v;
    } else if (s.startsWith("</")) {
      curKind = null;
    }
  }
  return { nodes, ways, lanelets: lanelets.filter((r) => r.tags.type === "lanelet" && r.left && r.right) };
}

const t0 = Date.now();
const { nodes, ways, lanelets } = await readOsm(mapPath);
let minLat = Infinity, maxLat = -Infinity, minLon = Infinity, maxLon = -Infinity;
for (const w of ways) {
  for (const id of w.nds) {
    const n = nodes.get(id);
    if (!n) continue;
    minLat = Math.min(minLat, n[0]); maxLat = Math.max(maxLat, n[0]);
    minLon = Math.min(minLon, n[1]); maxLon = Math.max(maxLon, n[1]);
  }
}
const lat0 = (minLat + maxLat) / 2;
const lon0 = (minLon + maxLon) / 2;
const cos0 = Math.cos(rad(lat0));
const r3 = (v) => Math.round(v * 1000) / 1000;
const features = [];
let nPts = 0;
for (const w of ways) {
  const points = [];
  const nds = [];
  for (const id of w.nds) {
    const n = nodes.get(id);
    if (!n) continue;
    points.push(r3(rad(n[1] - lon0) * cos0 * R), r3(rad(n[0] - lat0) * R), r3(n[2]));
    nds.push(id);
  }
  if (nds.length < 2) continue;
  const tags = {};
  for (const k of ["type", "subtype", "participant:bicycle"]) if (w.tags[k] !== undefined) tags[k] = w.tags[k];
  features.push({ id: `way/${w.id}`, kind: "line", tags, pts: points, nodes: nds });
  nPts += nds.length;
}
// Lanelets like Viewer3dFeatures.featureForLanelet sends them, minus the
// lanelet2 alignment and centerline (not ported to JS): bounds as stored and
// an arrow at 35 % of the left bound. Enough to load surfaces and arrows.
const wayPts = new Map(features.map((f) => [f.id, f.pts]));
let nLanelets = 0;
for (const r of lanelets) {
  const lp = wayPts.get(`way/${r.left}`);
  if (!lp || !wayPts.get(`way/${r.right}`)) continue;
  const k = Math.min(Math.floor((lp.length / 3) * 0.35), lp.length / 3 - 2) * 3;
  const dx = lp[k + 3] - lp[k], dy = lp[k + 4] - lp[k + 1];
  const len = Math.hypot(dx, dy) || 1;
  const tags = {};
  for (const key of ["subtype", "one_way"]) if (r.tags[key] !== undefined) tags[key] = r.tags[key];
  features.push({ id: `relation/${r.id}`, kind: "lanelet", tags, pts: [lp[k], lp[k + 1], lp[k + 2]],
    left: `way/${r.left}`, right: `way/${r.right}`, lrev: false, rrev: false,
    two: ["no", "false", "0"].includes(r.tags.one_way), arrow: [lp[k], lp[k + 1], lp[k + 2], dx / len, dy / len, 0, 3.5] });
  nLanelets++;
}
const snapshot = JSON.stringify({ type: "snapshot", anchor: { lat: lat0, lon: lon0 }, features });
console.log(`map: ${features.length - nLanelets} ways, ${nLanelets} lanelets, ${nPts} points, snapshot ${(snapshot.length / 1e6).toFixed(1)} MB` +
  ` (read+convert ${((Date.now() - t0) / 1000).toFixed(1)} s)`);

const v = await openViewer({ shotsDir, query: "profile=1" });
try {
  console.log(`renderer: ${await v.evaluate("(() => { const gl = document.createElement('canvas').getContext('webgl2'); const d = gl && gl.getExtension('WEBGL_debug_renderer_info'); return d ? gl.getParameter(d.UNMASKED_RENDERER_WEBGL) : 'unknown'; })()")}`);
  const tSend = Date.now();
  v.sendScene(JSON.parse(snapshot));
  const ok = await v.waitForFeatures(features.length, 180000);
  const tShown = Date.now();
  console.log(`page shows all features: ${ok} after ${((tShown - tSend) / 1000).toFixed(2)} s`);
  console.log(`page apply: ${await v.hud("perf")}`);
  // The first frames build every tile (and the arrows); wait them out so the
  // frame numbers below are steady state.
  await sleep(6000);
  for (const [label, key] of [["overview (F)", "f"], ["top-down (B)", "b"]]) {
    await v.key(key);
    await sleep(3000);
    const stats = await v.evaluate("window.__ll2test.stats ? { ...window.__ll2test.stats(), lanelets: window.__ll2test.lanelets ? window.__ll2test.lanelets() : null } : null");
    console.log(`${label}: ${JSON.stringify(stats)} | ${await v.hud("render")}`);
    await v.shot(`perf_${key}.png`);
  }
  // Street level: from the top-down view, wheel in on the middle of the
  // screen to ~60 m, then tilt to an oblique view (the editing situation).
  await v.wheel(640, 400, -300, 12);
  await v.drag("right", 640, 400, 0, -250);
  await sleep(3000);
  const cam = await v.camera();
  const stats = await v.evaluate("window.__ll2test.stats()");
  console.log(`street level (${cam.pos[2].toFixed(0)} m up, pitch ${(cam.pitch * 180 / Math.PI).toFixed(0)}°): ` +
    `${stats.drawCalls} draws | ${await v.hud("render")}`);
  await v.shot("perf_street.png");
  await v.key("l");
  await sleep(2500);
  console.log(`street level, lanelets hidden: ${(await v.evaluate("window.__ll2test.stats()")).drawCalls} draws | ${await v.hud("render")}`);
  await v.key("l");
  const buildLog = (await v.evaluate("window.__perfLog ? window.__perfLog.join('\\n') : ''"));
  if (buildLog) console.log(buildLog);
  console.log(`page errors: ${v.errors.length ? v.errors.join(" | ") : "none"}`);
} finally {
  await v.close();
}
