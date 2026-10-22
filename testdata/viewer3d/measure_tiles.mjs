// How many tiles typical views ask for, with and without the budget (no network: a local tile server).
//   node testdata/viewer3d/measure_tiles.mjs
import http from "node:http";
import { openViewer, sleep } from "./harness.mjs";
const PNG = Buffer.from("89504e470d0a1a0a0000000d4948445200000001000000010806000000" +
  "1f15c4890000000d49444154789c63f8cfc0f01f0005000201fe0d2b8ad30000000049454e44ae426082", "hex");
const tiles = http.createServer((req, res) => {
  res.writeHead(200, { "Content-Type": "image/png", "Access-Control-Allow-Origin": "*" });
  res.end(PNG);
});
await new Promise((r) => tiles.listen(0, "127.0.0.1", r));
const v = await openViewer({ width: 1920, height: 1080 });
const Z = 115;
v.sendScene({ type: "snapshot", anchor: { lat: 49.0094, lon: 8.4044 }, features: [
  { id: "way/1", kind: "line", tags: { type: "line_thin" }, pts: [-500, 0, Z, 500, 0, Z], nodes: [1, 2] }] });
await v.waitForFeatures(1);
await v.evaluate(`window.__ll2map.setSource({ type: "xyz", url: "http://127.0.0.1:${tiles.address().port}/{z}/{x}/{y}.png", maxZoom: 21 })`);
await v.evaluate("window.__ll2map.setTiles(true)");
const views = [
  ["street level, looking ahead", 2, 0], ["10 m up, -10°", 10, -10], ["promo flyover: 45 m, -14°", 45, -14],
  ["hop: 120 m, -35°", 120, -35], ["500 m, -60°", 500, -60], ["straight down from 150 m", 150, -89.9],
];
const rows = [];
for (const [name, h, pitch] of views) {
  const row = { name };
  for (const cap of [96, 160, 256, 100000]) {
    await v.evaluate(`window.__ll2map.setMaxTiles(${cap})`);
    await v.evaluate(`window.__ll2test.setView([0, -50, ${Z + h}], 0, ${(pitch * Math.PI) / 180})`);
    await sleep(400);
    const st = await v.evaluate("window.__ll2map.tiles()");
    const zs = Object.keys(st.zooms).map(Number);
    row[cap === 100000 ? "no limit" : `cap ${cap}`] = `${st.wanted} tiles, z${Math.min(...zs)}-${Math.max(...zs)}`;
  }
  rows.push(row);
}
console.table(rows);
await v.close();
tiles.close();
