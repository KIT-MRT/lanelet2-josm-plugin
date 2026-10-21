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

const FRAMES = 14;
const POINTS_PER_FRAME = 500;
// Frame i puts its cloud around x = 10 * i, so "did the frame change?" is a
// question about geometry, not about a counter.
const frameOrigin = (i) => [10 * i, 0, Z];

function encodeCloud(index, count = POINTS_PER_FRAME, spacing = 10) {
  const origin = [spacing * index, 0, Z];
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
let aggregateReadyAfter = 2;   // 202 with progress this many times, then ready
let aggregateAsked = 0;
let aggregateChunksServed = [];
const AGG_CHUNKS = 3;
const AGG_PER_CHUNK = 200;
let delayMs = 0;        // pretend a frame costs real decode time
let inFlight = 0;
let maxInFlight = 0;
const data = http.createServer((req, res) => {
  const cors = { "Access-Control-Allow-Origin": "*", "Cache-Control": "no-store" };
  const match = /^\/frame\/(\d+)\/points\.bin$/.exec(req.url);
  if (req.url === "/aggregate/meta.json") {
    aggregateAsked++;
    const ready = aggregateAsked > aggregateReadyAfter;
    res.writeHead(ready ? 200 : 202, { ...cors, "Content-Type": "application/json" });
    res.end(JSON.stringify(ready
      ? { ready: true,
          stats: { points: AGG_CHUNKS * AGG_PER_CHUNK, voxel_m: 0.15, min_frames: 3 },
          chunks: Array.from({ length: AGG_CHUNKS }, (_, i) => (
            { index: i, count: AGG_PER_CHUNK, centre: [i * 40, 0, 100], radius: 20 })) }
      : { ready: false, progress: { state: "scanning", done: aggregateAsked, total: 9 } }));
    return;
  }
  const aggChunk = /^\/aggregate\/(\d+)\.bin$/.exec(req.url);
  if (aggChunk) {
    const index = Number(aggChunk[1]);
    aggregateChunksServed.push(index);
    res.writeHead(200, { ...cors, "Content-Type": "application/octet-stream" });
    res.end(encodeCloud(index, AGG_PER_CHUNK, 40));
    return;
  }
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
    inFlight++;
    maxInFlight = Math.max(maxInFlight, inFlight);
    let done = false;
    const release = () => { if (!done) { done = true; inFlight--; } };
    // An aborted fetch closes the connection; the real server drops that work
    // at the gate, so it must not count as busy here either.
    res.on("close", release);
    const finish = () => {
      if (done) return;
      release();
      if (!res.writableEnded) {
        res.writeHead(200, { ...cors, "Content-Type": "application/octet-stream" });
        res.end(body);
      }
    };
    if (delayMs) setTimeout(finish, delayMs);
    else finish();
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
  cameras: {
    camera_ring_front: {
      fx: 1841, fy: 1841, cx: 1700, cy: 1100, width: 3504, height: 2272,
      // camera x = ego -y (right), camera y = ego -z (down), camera z = ego x
      // (forward), mounted 1.5 m up and 0.2 m ahead of the ego origin.
      T_cam_to_ego: [[0, 0, 1, 0.2], [-1, 0, 0, 0], [0, -1, 0, 1.5], [0, 0, 0, 1]],
    },
    camera_ring_rear: {
      fx: 1845, fy: 1845, cx: 1760, cy: 1140, width: 3504, height: 2272,
      T_cam_to_ego: [[0, 0, -1, -0.2], [1, 0, 0, 0], [0, -1, 0, 1.5], [0, 0, 0, 1]],
    },
  },
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
/** Click an element the way a user does: a real mouse press at its centre.
 *  `#leftCol` is pointer-events:none, so a panel that forgets to opt back in
 *  looks fine and reacts only to Tab+Enter. */
async function clickElement(id) {
  const box = await v.evaluate(`(() => { const b = document.getElementById(${JSON.stringify(id)})`
    + `.getBoundingClientRect(); return [b.left + b.width / 2, b.top + b.height / 2]; })()`);
  await v.click(box[0], box[1]);
  await sleep(120);
}
// Where the free camera looks, from its yaw/pitch (camera.js: yaw 0 is north
// along +y, counter-clockwise positive).
const lookDir = (cam) => [
  -Math.sin(cam.yaw) * Math.cos(cam.pitch),
  Math.cos(cam.yaw) * Math.cos(cam.pitch),
  Math.sin(cam.pitch),
];
const dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
const unit = (a) => { const n = Math.hypot(...a) || 1; return a.map((v) => v / n); };
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
  t.check("the frame label counts from 0", (await label()).startsWith(`0 / ${FRAMES - 1}`), await label());
  await v.key("b");
  await v.shot("scene_points.png");

  // ---- the bar reacts to the mouse, not just to the keyboard -----------------------
  await clickElement("scenePoints");
  t.check("a real mouse click on the scene bar hides the cloud", !(await points()).visible);
  await clickElement("scenePoints");
  t.check("and shows it again", (await points()).visible);
  await clickElement("sceneNext");
  t.check("the step button works with the mouse too", await waitLoaded(1));
  await clickElement("scenePrev");
  await waitLoaded(0);

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
  const atFrame = (await sceneState()).frame;
  await v.key("k");
  await sleep(150);
  const cam = await v.camera();
  const car = ego.pos[atFrame];
  const away = Math.hypot(cam.pos[0] - car[0], cam.pos[1] - car[1]);
  t.check("K puts the camera behind the car of the current frame",
    away > 5 && away < 40 && cam.pos[2] > car[2] && dist3(cam.pos, before.pos) > 1,
    `${away.toFixed(1)} m away, ${(cam.pos[2] - car[2]).toFixed(1)} m up`);
  // The car drives +x here, so the camera belongs west of it, aimed at it.
  t.check("standing behind the car, not beside it", cam.pos[0] < car[0] - 5
    && Math.abs(cam.pos[1] - car[1]) < 1, JSON.stringify(cam.pos.map((n) => +n.toFixed(2))));
  const atCar = unit([car[0] - cam.pos[0], car[1] - cam.pos[1], car[2] - cam.pos[2]]);
  t.check("and actually looking at it", dot(lookDir(cam), atCar) > 0.999,
    `dot ${dot(lookDir(cam), atCar).toFixed(4)}, yaw ${cam.yaw.toFixed(2)}`);
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

  // ---- stepping fast must not pile decodes on the server ---------------------------
  // Each frame costs the real server seconds of CPU; eight at once made every
  // answer ~6x slower, including the one being waited for.
  served = [];
  maxInFlight = 0;
  delayMs = 250;
  await v.evaluate("window.__ll2scene.show(6)");
  for (let i = 0; i < 6; i++) await v.key(".");
  const settled = await waitFor(async () => {
    const st = await sceneState();
    return !st.loading && st.frame === 12;
  }, 15000);
  await sleep(300);
  t.check("rapid stepping ends on the frame asked for", settled, JSON.stringify(await sceneState()));
  t.check("with only one request in flight at a time", maxInFlight === 1, `peak ${maxInFlight}`);
  t.check("and the frames stepped over are never fetched",
    served.length <= 4, `fetched ${JSON.stringify(served)}`);
  t.check("the cloud shown is the one for that frame",
    dist3((await points()).origin, frameOrigin(12)) < 1e-9, JSON.stringify((await points()).origin));
  // A look-ahead is dropped for a frame the user is actually waiting for.
  await v.evaluate("window.__ll2scene.show(13)");
  await waitLoaded(13);
  served = [];
  maxInFlight = 0;
  delayMs = 400;
  await sleep(600);           // the idle look-ahead starts (and will not finish)
  await v.evaluate("window.__ll2scene.show(5)");
  const jumped = await waitFor(async () => {
    const st = await sceneState();
    return st.frame === 5 && !st.loading;
  }, 10000);
  t.check("a jump during a look-ahead is served, not queued behind it", jumped,
    JSON.stringify(await sceneState()));
  t.check("and still only one request runs at a time", maxInFlight === 1, `peak ${maxInFlight}`);
  t.check("showing the frame that was asked for",
    dist3((await points()).origin, frameOrigin(5)) < 1e-9, JSON.stringify((await points()).origin));
  delayMs = 0;

  // ---- the whole scene as one cloud ------------------------------------------------
  const aggOf = () => v.evaluate("window.__ll2scene.aggregate()");
  const aggLabel = () => v.evaluate("document.getElementById('sceneAggregate').textContent");
  t.check("nothing is aggregated to begin with", (await aggOf()).points === 0);
  await v.key("P", { modifiers: ["shift"] });
  await sleep(300);
  t.check("Shift+P asks the server to build it", (await aggOf()).building,
    JSON.stringify(await aggOf()));
  t.check("and the button reports the progress", (await aggLabel()).startsWith("Building"),
    await aggLabel());
  const built = await waitFor(async () => {
    const a = await aggOf();
    return !a.building && a.points > 0;
  }, 15000);
  t.check("it arrives once the server says ready", built, JSON.stringify(await aggOf()));
  let agg = await aggOf();
  t.check("every chunk is loaded", agg.loaded === AGG_CHUNKS
    && agg.points === AGG_CHUNKS * AGG_PER_CHUNK, JSON.stringify(agg));
  t.check("chunks are fetched one at a time, in order",
    aggregateChunksServed.join() === "0,1,2", aggregateChunksServed.join());
  let stats = await points();
  t.check("the aggregate is drawn beside the single frame",
    stats.aggregate === AGG_CHUNKS * AGG_PER_CHUNK && stats.aggregateChunks === AGG_CHUNKS
    && stats.count === POINTS_PER_FRAME, JSON.stringify(stats));
  await v.key("b");
  await v.shot("scene_aggregate.png");

  await v.key("P", { modifiers: ["shift"] });
  await sleep(200);
  t.check("Shift+P again hides it without throwing it away",
    !(await points()).aggregateVisible && (await aggOf()).points > 0);
  await v.key("P", { modifiers: ["shift"] });
  await sleep(200);
  t.check("and shows it again without refetching",
    (await points()).aggregateVisible && aggregateChunksServed.length === AGG_CHUNKS,
    aggregateChunksServed.join());
  await v.key("p");
  await sleep(150);
  // Both live under one parent group; hiding the frame cloud by hiding that
  // parent took the aggregate with it, while the child's own flag still said
  // "visible". The hook reports effective visibility for that reason.
  let vis = await points();
  t.check("P hides the frame cloud but leaves the aggregate",
    !vis.visible && vis.aggregateVisible, JSON.stringify(vis));
  t.check("and the aggregate is still actually drawn",
    (await v.evaluate("window.__ll2test.stats().drawCalls")) > 0);
  await v.key("p");
  vis = await points();
  t.check("P brings the frame cloud back", vis.visible && vis.aggregateVisible);

  // ---- camera lock ---------------------------------------------------------------
  await v.evaluate("window.__ll2scene.show(2)");
  await waitLoaded(2);
  const rigOf = () => v.evaluate("window.__ll2scene.rig()");
  await clickElement("sceneLock");
  await sleep(200);
  let rigNow = await rigOf();
  t.check("the lock turns on with the front camera", rigNow.on && rigNow.camera === "camera_ring_front",
    JSON.stringify(rigNow));
  const lockCam = await v.camera();
  const mount = [ego.pos[2][0] + 0.2, ego.pos[2][1], ego.pos[2][2] + 1.5];
  t.check("the camera sits at the mount of that sensor", dist3(lockCam.pos, mount) < 1e-6,
    `${JSON.stringify(lockCam.pos.map((n) => +n.toFixed(3)))} vs ${JSON.stringify(mount)}`);
  t.check("the view is letterboxed to the image aspect",
    Math.abs(rigNow.viewport.width / rigNow.viewport.height - 3504 / 2272) < 0.01,
    JSON.stringify(rigNow.viewport));

  // The whole point of the lock: the render must reproduce the recorded
  // camera's pinhole projection, principal point and all.
  const K = sceneInfo().cameras.camera_ring_front;
  const worst = await v.evaluate(`(() => {
    const K = ${JSON.stringify(K)};
    const ego = ${JSON.stringify(ego.pos[2])};
    let worst = 0, sample = null;
    for (const [dx, dy, dz] of [[20,0,0],[35,6,2],[12,-4,-1],[60,15,5],[25,-9,3]]) {
      // camera frame: z forward (ego +x), x right (ego -y), y down (ego -z)
      const Xc = -dy, Yc = -(dz - 1.5), Zc = dx - 0.2;
      const want = { u: K.fx * Xc / Zc + K.cx, v: K.fy * Yc / Zc + K.cy };
      const got = window.__ll2scene.project([ego[0]+dx, ego[1]+dy, ego[2]+dz]);
      const d = Math.hypot(got.u - want.u, got.v - want.v);
      if (d > worst) { worst = d; sample = { want, got }; }
    }
    return { worst, sample };
  })()`);
  t.check("the render matches the recorded camera's pinhole projection",
    worst.worst < 0.01, `worst ${worst.worst.toFixed(4)} px — ${JSON.stringify(worst.sample)}`);

  t.check("the recorded image is blended over the render",
    rigNow.overlay && rigNow.imageSrc.endsWith("/frame/2/camera/camera_ring_front.jpg"), JSON.stringify(rigNow));
  await v.key("o");
  t.check("O takes the image away but keeps the lock",
    !(await rigOf()).overlay && (await rigOf()).on);
  await v.key("o");

  await v.key("]");
  await sleep(150);
  rigNow = await rigOf();
  t.check("] walks to the next ring camera", rigNow.camera === "camera_ring_rear", rigNow.camera);
  // view.yaw is the unlocked camera's state; while locked the orientation
  // lives in the camera matrix, so compare where it actually looks.
  const dirs = await v.evaluate(`(() => {
    const r = window.__ll2scene.rig();
    const p = window.__ll2scene.project;
    const ego = ${JSON.stringify(ego.pos[2])};
    // A point 20 m ahead of the car is behind a rear-facing camera.
    return { ahead: p([ego[0] + 20, ego[1], ego[2]]), rear: r.camera };
  })()`);
  t.check("and the rear camera looks the other way",
    dirs.ahead && dirs.ahead.behind === true, JSON.stringify(dirs));
  await v.key("[");

  await v.evaluate("window.__ll2scene.show(4)");
  await waitLoaded(4);
  t.check("stepping a frame moves the locked view and its image",
    (await rigOf()).imageSrc.endsWith("/frame/4/camera/camera_ring_front.jpg")
    && dist3((await v.camera()).pos, mount) > 1,
    (await rigOf()).imageSrc);

  // ---- releasing the lock leaves the view where it is ------------------------------
  const locked = await v.camera();
  const lockedDir = locked.dir;
  await v.key("L", { modifiers: ["shift"] });
  await sleep(200);
  t.check("Shift+L releases the lock", !(await rigOf()).on);
  const freed = await v.camera();
  t.check("the camera stays exactly where the lock left it",
    dist3(freed.pos, locked.pos) < 1e-9,
    `${JSON.stringify(locked.pos)} -> ${JSON.stringify(freed.pos)}`);
  t.check("still looking the same way", dot(freed.dir, unit(lockedDir)) > 0.9999,
    `${JSON.stringify(lockedDir.map((n) => +n.toFixed(4)))} -> `
    + `${JSON.stringify(freed.dir.map((n) => +n.toFixed(4)))}`);
  t.check("and yaw/pitch now describe that direction",
    dot(lookDir(freed), freed.dir) > 0.9999, `dot ${dot(lookDir(freed), freed.dir).toFixed(5)}`);
  t.check("and the ordinary projection is back", Math.abs(freed.fov - 55) < 1e-6
    && (await rigOf()).viewport === null, String(freed.fov));

  t.check("no page errors", v.errors.length === 0, v.errors.join(" | "));
} finally {
  await v.close();
  data.close();
}
console.log(t.failures ? `\n${t.failures} of ${t.count} FAILED` : `\nall ${t.count} passed`);
process.exit(t.failures ? 1 : 0);
