// Load a whole .osm map into the viewer (headless) and report timings.
//   node testdata/viewer3d/perf_viewer.mjs path/to/lanelet2_map.osm [--shots DIR]
//
// Converts the map like Viewer3dFeatures.featureForWay does (ENU around the
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
      curKind = "rel";
    } else if (s.startsWith("<nd ") && curKind === "way") {
      cur.nds.push(Number(attr(s, "ref")));
    } else if (s.startsWith("<tag")) {
      const k = attr(s, "k");
      const v = attr(s, "v");
      if (curKind === "node" && k === "ele") cur[2] = Number(v) || 0;
      else if (curKind === "way") cur.tags[k] = v;
    } else if (s.startsWith("</")) {
      curKind = null;
    }
  }
  return { nodes, ways };
}

const t0 = Date.now();
const { nodes, ways } = await readOsm(mapPath);
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
    points.push([r3(rad(n[1] - lon0) * cos0 * R), r3(rad(n[0] - lat0) * R), r3(n[2])]);
    nds.push(`node/${id}`);
  }
  if (points.length < 2) continue;
  const tags = {};
  for (const k of ["type", "subtype", "participant:bicycle"]) if (w.tags[k] !== undefined) tags[k] = w.tags[k];
  features.push({ id: `way/${w.id}`, kind: "line", tags, points, nodes: nds });
  nPts += points.length;
}
const snapshot = JSON.stringify({ type: "snapshot", anchor: { lat: lat0, lon: lon0 }, features });
console.log(`map: ${features.length} ways, ${nPts} points, snapshot ${(snapshot.length / 1e6).toFixed(1)} MB` +
  ` (read+convert ${((Date.now() - t0) / 1000).toFixed(1)} s)`);

const v = await openViewer({ shotsDir, query: "profile=1" });
try {
  const tSend = Date.now();
  v.sendScene(JSON.parse(snapshot));
  const ok = await v.waitForFeatures(features.length, 180000);
  const tShown = Date.now();
  console.log(`page shows all features: ${ok} after ${((tShown - tSend) / 1000).toFixed(2)} s`);
  console.log(`page apply: ${await v.hud("perf")}`);
  await sleep(1500);
  for (const [label, key] of [["overview (F)", "f"], ["top-down (B)", "b"]]) {
    await v.key(key);
    await sleep(2500);
    const stats = await v.evaluate("window.__ll2test.stats ? window.__ll2test.stats() : null");
    console.log(`${label}: ${JSON.stringify(stats)} | ${await v.hud("render")}`);
    await v.shot(`perf_${key}.png`);
  }
  console.log(`page errors: ${v.errors.length ? v.errors.join(" | ") : "none"}`);
} finally {
  await v.close();
}
