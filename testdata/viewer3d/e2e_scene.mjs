// End-to-end checks of recorded sensor scenes: the scene bar, point clouds,
// frame stepping, colouring and the jump to the car.
//
// The scene server is faked here the way the JOSM bridge is: a small HTTP
// server serving the same `/scene.json` and `/frame/<i>/points.bin` as
// tools/scene_server, so the browser side is tested without the 4.7 GB
// dataset. The binary layout below must stay in step with
// tools/scene_server/ll2_scene/points.py (there is a Python test for that
// side).
//   node testdata/viewer3d/e2e_scene.mjs [--shots DIR]
import http from "node:http";
import { openViewer, Checks, dist3, sleep } from "./harness.mjs";

const shotsIdx = process.argv.indexOf("--shots");
const shotsDir = shotsIdx > 0 ? process.argv[shotsIdx + 1] : null;
const t = new Checks();
const Z = 100;

const FRAMES = 6;
const POINTS_PER_FRAME = 500;
// Frame i puts its cloud around x = 10 * i, so "did the frame change?" is a
// question about geometry, not about a counter.
const frameOrigin = (i) => [10 * i, 0, Z];

function encodeCloud(index) {
  const count = POINTS_PER_FRAME;
  const origin = frameOrigin(index);
  const header = new ArrayBuffer(40);
  const view = new DataView(header);
  for (const [k, ch] of [..."LL2PC1"].entries()) view.setUint8(k, ch.charCodeAt(0));
  view.setUint32(8, count, true);
  view.setUint32(12, 1 | 2, true); // intensity + sensor id
  view.setFloat64(16, origin[0], true);
  view.setFloat64(24, origin[1], true);
  view.setFloat64(32, origin[2], true);
  const xyz = new Float32Array(count * 3);
  const intensity = new Uint8Array(count);
  const sensor = new Uint8Array(count);
  for (let p = 0; p < count; p++) {
    const a = (p / count) * Math.PI * 2;
    xyz[p * 3] = Math.cos(a) * 6;
    xyz[p * 3 + 1] = Math.sin(a) * 6;
    xyz[p * 3 + 2] = (p % 10) * 0.2;
    intensity[p] = p % 256;
    sensor[p] = p % 7;
  }
  return Buffer.concat([Buffer.from(header), Buffer.from(xyz.buffer),
                        Buffer.from(intensity), Buffer.from(sensor)]);
}

const ego = {
  pos: Array.from({ length: FRAMES }, (_, i) => [10 * i, 0, Z]),
  // Heading east, level: identity in the viewer's ENU convention.
  quat: Array.from({ length: FRAMES }, () => [0, 0, 0, 1]),
};

let served = [];
const data = http.createServer((req, res) => {
  const cors = { "Access-Control-Allow-Origin": "*", "Cache-Control": "no-store" };
  const match = /^\/frame\/(\d+)\/points\.bin$/.exec(req.url);
  if (req.url === "/scene.json") {
    res.writeHead(200, { ...cors, "Content-Type": "application/json" });
    res.end(JSON.stringify(sceneInfo()));
    return;
  }
  if (match) {
    const index = Number(match[1]);
    served.push(index);
    if (index >= FRAMES) {
      res.writeHead(404, cors);
      res.end("no such frame");
      return;
    }
    const body = encodeCloud(index);
    res.writeHead(200, { ...cors, "Content-Type": "application/octet-stream" });
    res.end(body);
    return;
  }
  res.writeHead(404, cors);
  res.end("not found");
});
await new Promise((r) => data.listen(0, "127.0.0.1", r));
const base = `http://127.0.0.1:${data.address().port}`;
const sceneInfo = () => ({
  id: "e2e-scene-0000", base, frames: FRAMES,
  times: Array.from({ length: FRAMES }, (_, i) => i * 0.1),
  lidars: ["lidar_top", "lidar_front"],
  ego,
  cameras: { camera_ring_front: { fx: 1841, fy: 1841, cx: 1752, cy: 1136, width: 3504, height: 2272 } },
  anchor: { lat: 49.0, lon: 8.4 },
});

const line = (id, nodes, pts) => ({ id: `way/${id}`, kind: "line", tags: { type: "line_thin" },
                                    pts: pts.flat(), nodes });
const features = [line(10, [1001, 1002], [[0, -20, Z], [60, -20, Z]])];

const v = await openViewer({ shotsDir });
const sceneState = () => v.evaluate("window.__ll2scene.state()");
const points = () => v.evaluate("window.__ll2scene.points()");
const barHidden = () => v.evaluate("document.getElementById('sceneBar').hidden");
const label = () => v.evaluate("document.getElementById('sceneFrameLabel').textContent");
async function waitFor(pred, ms = 5000) {
  const t0 = Date.now();
  while (Date.now() - t0 < ms) {
    if (await pred()) return true;
    await sleep(50);
  }
  return false;
}
const waitLoaded = (frame) => waitFor(async () => {
  const s = await sceneState();
  return s.frame === frame && !s.loading;
});

try {
  v.sendScene({ type: "snapshot", anchor: { lat: 49.0, lon: 8.4 }, features });
  t.check("map streamed", await v.waitForFeatures(1));
  t.check("no scene bar without a scene", await barHidden());

  v.sendScene({ type: "scene", scene: sceneInfo() });
  t.check("the scene announcement shows the scene bar", await waitFor(async () => !(await barHidden())));
  t.check("and loads frame 0 by itself", await waitLoaded(0));
  let p = await points();
  t.check("the point cloud is on screen", p.count === POINTS_PER_FRAME && p.visible, JSON.stringify(p));
  t.check("placed where the scene server put it",
    dist3(p.origin, frameOrigin(0)) < 1e-9, JSON.stringify(p.origin));
  t.check("the frame label counts from 0", (await label()).startsWith("0 / 5"), await label());
  await v.key("b");
  await v.shot("scene_points.png");

  // ---- stepping ------------------------------------------------------------------
  await v.evaluate("document.getElementById('sceneNext').click()");
  t.check("the next-frame button loads frame 1", await waitLoaded(1));
  p = await points();
  t.check("and the cloud moved with it", dist3(p.origin, frameOrigin(1)) < 1e-9, JSON.stringify(p.origin));
  await v.key(".");
  t.check("'.' steps forward too", await waitLoaded(2));
  await v.key(",");
  t.check("',' steps back", await waitLoaded(1));
  t.check("a frame already seen is not fetched again",
    served.filter((i) => i === 1).length === 1, JSON.stringify(served));

  await v.evaluate("(() => { const el = document.getElementById('sceneFrame');"
    + " el.value = '4'; el.dispatchEvent(new Event('input')); })()");
  t.check("the slider jumps to a frame", await waitLoaded(4));
  await v.evaluate("document.getElementById('scenePrev').click()");
  t.check("the previous-frame button goes back", await waitLoaded(3));

  // ---- colouring and size --------------------------------------------------------
  await v.evaluate("(() => { const el = document.getElementById('sceneMode');"
    + " el.value = 'height'; el.dispatchEvent(new Event('change')); })()");
  t.check("the colour mode switches without reloading the cloud",
    (await points()).mode === "height" && (await points()).count === POINTS_PER_FRAME);
  await v.evaluate("(() => { const el = document.getElementById('sceneSize');"
    + " el.value = '5'; el.dispatchEvent(new Event('input')); })()");
  t.check("the point size follows the slider", (await points()).size === 5);
  await v.shot("scene_height.png");

  // ---- jump to the car -------------------------------------------------------------
  const before = await v.camera();
  await v.key("k");
  await sleep(150);
  const cam = await v.camera();
  const car = ego.pos[3];
  const away = Math.hypot(cam.pos[0] - car[0], cam.pos[1] - car[1]);
  t.check("K puts the camera behind the car of the current frame",
    away > 5 && away < 40 && cam.pos[2] > car[2] && dist3(cam.pos, before.pos) > 1,
    `${away.toFixed(1)} m away, ${(cam.pos[2] - car[2]).toFixed(1)} m up`);
  t.check("looking the way it drives", Math.abs(cam.yaw) < 0.2 && cam.pitch < 0,
    `yaw ${cam.yaw.toFixed(2)} pitch ${cam.pitch.toFixed(2)}`);
  await v.shot("scene_car.png");

  // ---- hiding ----------------------------------------------------------------------
  await v.key("p");
  t.check("P hides the cloud", !(await points()).visible);
  await v.key("p");
  t.check("and shows it again", (await points()).visible);

  // ---- a refused frame is reported, not silently dropped ---------------------------
  await v.evaluate("window.__ll2scene.show(99)");
  await sleep(400);
  t.check("a frame out of range clamps instead of erroring",
    (await sceneState()).frame === FRAMES - 1 && !(await sceneState()).error,
    JSON.stringify(await sceneState()));

  t.check("no page errors", v.errors.length === 0, v.errors.join(" | "));
} finally {
  await v.close();
  data.close();
}
console.log(t.failures ? `\n${t.failures} of ${t.count} FAILED` : `\nall ${t.count} passed`);
process.exit(t.failures ? 1 : 0);
