// End-to-end checks of the map view: the orthographic camera and the web-map
// tiles under it (XYZ, TMS, WMS, and the viewer server's tile proxy for
// servers without CORS).
//
// Two local tile servers stand in for OpenStreetMap / a GeoServer: one sends
// CORS headers, one does not (WebGL cannot use its images directly).
//   node testdata/viewer3d/e2e_mapview.mjs [--shots DIR]
import http from "node:http";
import { openViewer, Checks, sleep } from "./harness.mjs";

const shotsIdx = process.argv.indexOf("--shots");
const shotsDir = shotsIdx > 0 ? process.argv[shotsIdx + 1] : null;
const t = new Checks();
const Z = 100;
const ANCHOR = { lat: 49.0, lon: 8.4 };
// A valid 1x1 PNG; WebGL is happy to stretch it over a tile.
const PNG = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFBQIAX8jx0gAAAABJRU5ErkJggg==", "base64");

const seen = { cors: [], wms: [], nocors: [] };
function tileServer(withCors, log) {
  const srv = http.createServer((req, res) => {
    log.push(req.url);
    const headers = { "Content-Type": "image/png", "Content-Length": PNG.length };
    if (withCors) headers["Access-Control-Allow-Origin"] = "*";
    res.writeHead(200, headers);
    res.end(PNG);
  });
  return new Promise((r) => srv.listen(0, "127.0.0.1", () => r(srv)));
}
const corsSrv = await tileServer(true, seen.cors);
const wmsLog = [];
const corsBase = `http://127.0.0.1:${corsSrv.address().port}`;
const nocorsSrv = await tileServer(false, seen.nocors);
const nocorsHost = `127.0.0.1:${nocorsSrv.address().port}`;

// Independent tile maths, so the viewer's is checked rather than reused.
const R = 6378137.0;
const MERC = Math.PI * R;
const rad = (d) => (d * Math.PI) / 180;
const deg = (r) => (r * 180) / Math.PI;
const enu = (lat, lon) => [rad(lon - ANCHOR.lon) * Math.cos(rad(ANCHOR.lat)) * R, rad(lat - ANCHOR.lat) * R];
const tileOf = (lat, lon, z) => {
  const n = 2 ** z;
  const x = Math.floor(((lon + 180) / 360) * n);
  const y = Math.floor(((1 - Math.log(Math.tan(rad(lat)) + 1 / Math.cos(rad(lat))) / Math.PI) / 2) * n);
  return [x, y];
};
const swCornerEnu = (z, x, y) => {
  const s = (2 * MERC) / 2 ** z;
  const lon = deg((-MERC + x * s) / R);
  const lat = deg(2 * Math.atan(Math.exp((MERC - (y + 1) * s) / R)) - Math.PI / 2);
  return enu(lat, lon);
};

const line = (id, nodes, pts) => ({ id: `way/${id}`, kind: "line", tags: { type: "line_thin" },
                                    pts: pts.flat(), nodes });
const features = [
  line(10, [1001, 1002], [[-100, -60, Z], [100, -60, Z]]),
  line(11, [1003, 1004], [[-100, 60, Z], [100, 60, Z]]),
];

const v = await openViewer({ shotsDir, serverArgs: ["--tile-proxy-host", nocorsHost] });
const cam = () => v.camera();
const map = (expr) => v.evaluate(`window.__ll2map.${expr}`);
async function waitFor(pred, ms = 8000) {
  const t0 = Date.now();
  while (Date.now() - t0 < ms) {
    if (await pred()) return true;
    await sleep(60);
  }
  return false;
}
async function clickElement(id) {
  const box = await v.evaluate(`(() => { const b = document.getElementById(${JSON.stringify(id)})`
    + `.getBoundingClientRect(); return [b.left + b.width / 2, b.top + b.height / 2]; })()`);
  await v.click(box[0], box[1]);
  await sleep(150);
}
const px = (a, b) => Math.hypot(a[0] - b[0], a[1] - b[1]);
const frame = () => v.evaluate("new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)))");

try {
  v.sendScene({ type: "snapshot", anchor: ANCHOR, features });
  t.check("map streamed", await v.waitForFeatures(2));
  await v.key("b");
  await frame();

  // ---- orthographic --------------------------------------------------------------
  const ground = [[0, -60, Z], [0, 60, Z]];
  let a = await v.project(ground[0]);
  let b = await v.project(ground[1]);
  const spanPersp = px(a, b);
  const lowP = await v.project([80, -60, Z]);
  const highP = await v.project([80, -60, Z + 30]);
  t.check("perspective: a point 30 m higher lands elsewhere", px(lowP, highP) > 5, px(lowP, highP).toFixed(1));

  await v.key("v");
  await frame();
  let c = await cam();
  t.check("V turns the ortho view on", (await map("ortho()")).on
    && await v.evaluate("document.getElementById('orthoBtn').classList.contains('on')"));
  t.check("looking straight down, north up", Math.abs(c.pitch + Math.PI / 2) < 1e-9 && Math.abs(c.yaw) < 1e-9,
    JSON.stringify([c.yaw, c.pitch]));
  const lowO = await v.project([80, -60, Z]);
  const highO = await v.project([80, -60, Z + 30]);
  t.check("ortho: height no longer moves a point on screen", px(lowO, highO) < 0.01, px(lowO, highO).toFixed(4));
  a = await v.project(ground[0]);
  b = await v.project(ground[1]);
  t.check("the ground keeps its scale across the switch", Math.abs(px(a, b) / spanPersp - 1) < 0.01,
    `${spanPersp.toFixed(1)} -> ${px(a, b).toFixed(1)} px`);
  t.check("the HUD gives the scale", (await v.hud("camAng")).includes("cm/px"), await v.hud("camAng"));

  // Picking is done on the CPU with its own projection maths.
  const mid = await v.project([30, 60, Z]);
  const hit = await v.evaluate(`window.__ll2test.pick(${mid[0]}, ${mid[1]})`);
  t.check("a click picks the line under it in ortho", hit && hit.id === "way/11", JSON.stringify(hit));
  const node = await v.project([100, -60, Z]);
  const hitNode = await v.evaluate(`window.__ll2test.pick(${node[0]}, ${node[1]})`);
  t.check("and a node", hitNode && hitNode.type === "node", JSON.stringify(hitNode));

  // The wheel zooms about the point under the cursor.
  const target = [60, 60, Z];
  const before = await v.project(target);
  await v.wheel(before[0], before[1], -100, 3);
  await frame();
  const after = await v.project(target);
  a = await v.project(ground[0]);
  b = await v.project(ground[1]);
  t.check("the wheel zooms in", px(a, b) > spanPersp * 1.3, `${spanPersp.toFixed(1)} -> ${px(a, b).toFixed(1)} px`);
  t.check("keeping the point under the cursor in place", px(before, after) < 1.5,
    `${before.map((n) => n.toFixed(1))} -> ${after.map((n) => n.toFixed(1))}`);
  t.check("and stays straight down", Math.abs((await cam()).pitch + Math.PI / 2) < 1e-9);

  // A drag turns the map about the grabbed point; it never tilts it.
  const grab = await v.project([0, 60, Z]);
  await v.drag("left", grab[0], grab[1], 120, 80);
  c = await cam();
  t.check("a drag turns the map", Math.abs(c.yaw) > 0.1, c.yaw.toFixed(3));
  t.check("but never tilts it", Math.abs(c.pitch + Math.PI / 2) < 1e-9, c.pitch.toFixed(6));
  await v.key("b");
  await frame();
  c = await cam();
  t.check("B is north up again, still ortho", Math.abs(c.yaw) < 1e-9 && (await map("ortho()")).on);
  await v.shot("mapview_ortho.png");

  // ---- tiles ----------------------------------------------------------------------
  t.check("tiles are off until asked for", !(await map("tiles()")).on && seen.cors.length === 0);
  const problems = await map(`setSource(${JSON.stringify({ type: "xyz", url: "not a url" })})`);
  t.check("a bad source is refused with a reason", problems.length > 0, problems.join("; "));
  await map(`setSource(${JSON.stringify({
    type: "xyz", url: `${corsBase}/xyz/{z}/{x}/{y}.png`, maxZoom: 19, attribution: "Test tiles",
  })})`);
  await clickElement("tilesBtn");
  t.check("the Tiles button turns them on", (await map("tiles()")).on);
  t.check("tiles arrive", await waitFor(async () => (await map("tiles()")).shown > 0));
  let st = await map("tiles()");
  t.check("one screen of them, at a zoom matching the view", st.zoom >= 16 && st.zoom <= 19 && st.wanted <= 40,
    JSON.stringify({ zoom: st.zoom, wanted: st.wanted }));
  const [tx, ty] = tileOf(ANCHOR.lat, ANCHOR.lon, st.zoom);
  t.check("including the tile under the map", seen.cors.some((u) => u === `/xyz/${st.zoom}/${tx}/${ty}.png`),
    `${st.zoom}/${tx}/${ty} in ${seen.cors.slice(0, 4).join(" ")}`);
  t.check("the grid steps aside", !(await map("gridVisible()")));
  t.check("and the credit is shown", (await v.hud("tileAttribution")) === "Test tiles"
    && !(await v.evaluate("document.getElementById('tileAttribution').hidden")));
  // Each tile corner goes through Mercator -> lat/lon -> the viewer's ENU.
  const key = `${st.zoom}/${tx}/${ty}`;
  await waitFor(async () => (await map("readyKeys()")).includes(key));
  const corner = await map(`tileCorner(${JSON.stringify(key)})`);
  const want = swCornerEnu(st.zoom, tx, ty);
  t.check("tiles sit where the map's own lat/lon put them", corner && px(corner, want) < 0.01,
    `${corner && corner.map((n) => n.toFixed(3))} vs ${want.map((n) => n.toFixed(3))}`);
  await v.shot("mapview_tiles.png");
  // What the screen shows: the map's white line on top of the (red) tiles.
  // Tiles drawn as transparent objects once covered the whole map, because
  // three draws transparent objects after every opaque one.
  const onLine = await v.project([0, 60, Z]);
  let lineSeen = null;
  for (let dy = -2; dy <= 2 && !lineSeen; dy++) {
    const p = await v.pixel(onLine[0], onLine[1] + dy);
    if (p[1] > 150 && p[2] > 150) lineSeen = p;
  }
  t.check("the map lines are drawn over the tiles", !!lineSeen, JSON.stringify(lineSeen));
  const offLine = await v.pixel(onLine[0], onLine[1] + 40);
  t.check("and the tiles fill the ground around them", offLine[0] > 150 && offLine[1] < 60,
    JSON.stringify(offLine));

  await v.evaluate("(() => { const el = document.getElementById('tileOpacity'); el.value = '40';"
    + " el.dispatchEvent(new Event('input')); })()");
  t.check("the opacity slider fades them", Math.abs((await map("tiles()")).opacity - 0.4) < 1e-9);
  await frame();
  const faded = await v.pixel(onLine[0], onLine[1] + 40);
  t.check("toward the background colour", faded[0] < offLine[0] - 60, `${offLine} -> ${faded}`);

  // TMS counts rows from the bottom.
  await map(`setSource(${JSON.stringify({ type: "tms", url: `${corsBase}/tms/{z}/{x}/{y}.png` })})`);
  t.check("TMS flips the row", (await map("tileUrl(3, 1, 2)")) === `${corsBase}/tms/3/1/5.png`,
    await map("tileUrl(3, 1, 2)"));

  // WMS: one GetMap per tile, in EPSG:3857, with the tile's own bounds.
  seen.cors.length = 0;
  await map(`setSource(${JSON.stringify({
    type: "wms", url: `${corsBase}/geoserver/wms`, layers: "ws:roads", format: "image/png",
    version: "1.3.0", transparent: true, maxZoom: 20,
  })})`);
  t.check("a WMS answers as tiles", await waitFor(async () => (await map("tiles()")).shown > 0));
  st = await map("tiles()");
  const req = new URL(corsBase + seen.cors.find((u) => u.startsWith("/geoserver/wms")));
  const q = Object.fromEntries(req.searchParams.entries());
  t.check("as GetMap requests", q.SERVICE === "WMS" && q.REQUEST === "GetMap" && q.LAYERS === "ws:roads"
    && q.CRS === "EPSG:3857" && q.WIDTH === "256" && q.TRANSPARENT === "TRUE", JSON.stringify(q));
  const bbox = q.BBOX.split(",").map(Number);
  const size = (2 * MERC) / 2 ** st.zoom;
  t.check("one tile's bounds each", Math.abs(bbox[2] - bbox[0] - size) < 0.01
    && Math.abs(bbox[3] - bbox[1] - size) < 0.01, `${(bbox[2] - bbox[0]).toFixed(3)} vs ${size.toFixed(3)}`);

  // The form: presets fill it, custom types open their fields.
  await clickElement("tilesMenuBtn");
  t.check("▾ opens the source panel", !(await v.evaluate("document.getElementById('tilePanel').hidden")));
  t.check("showing the WMS fields for a WMS", !(await v.evaluate("document.getElementById('tileWms').hidden")));
  await v.evaluate("(() => { const el = document.getElementById('tilePreset'); el.value = 'osm';"
    + " el.dispatchEvent(new Event('change')); })()");
  t.check("the OpenStreetMap preset fills in its URL", (await v.evaluate("document.getElementById('tileUrl').value"))
    === "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
    && await v.evaluate("document.getElementById('tileUrl').disabled")
    && await v.evaluate("document.getElementById('tileWms').hidden"));
  await clickElement("tilesMenuBtn");

  // A server without CORS: WebGL refuses its images...
  seen.nocors.length = 0;
  await map(`setSource(${JSON.stringify({ type: "xyz", url: `http://localhost:${nocorsSrv.address().port}/{z}/{x}/{y}.png` })})`);
  t.check("a server without CORS cannot be drawn directly",
    await waitFor(async () => (await map("tiles()")).failed > 0) && (await map("tiles()")).ready === 0);
  t.check("and the user is told what to do about it", await waitFor(async () => {
    const toast = await v.evaluate("window.__ll2test.toast()");
    return toast && toast.text.includes("--tile-proxy-host");
  }, 3000));
  // ...but through the viewer server, for a host the user allowed, it works.
  await map(`setSource(${JSON.stringify({ type: "xyz", url: `http://${nocorsHost}/{z}/{x}/{y}.png` })})`);
  t.check("an allowed host is fetched through the viewer server",
    await waitFor(async () => (await map("tiles()")).shown > 0), JSON.stringify(await map("tiles()")));
  t.check("which really asked that server", seen.nocors.length > 0);
  const refused = await v.evaluate("fetch('/tileproxy?url=' + encodeURIComponent('http://example.invalid/1/1/1.png'))"
    + ".then((r) => r.status)");
  t.check("the proxy refuses every other host", refused === 403, String(refused));
  const notHttp = await v.evaluate(`fetch('/tileproxy?url=' + encodeURIComponent('file:///etc/passwd')).then((r) => r.status)`);
  t.check("and anything that is not http(s)", notHttp === 403, String(notHttp));

  // Tiles in perspective too, laid on the ground.
  await v.key("v");
  await frame();
  t.check("V goes back to perspective", !(await map("ortho()")).on);
  await v.drag("left", 640, 400, 0, -120);  // tilt toward the horizon
  await sleep(300);
  t.check("tiles also cover the ground in perspective", await waitFor(async () => (await map("tiles()")).shown > 0));
  await v.shot("mapview_perspective_tiles.png");

  await clickElement("tilesBtn");
  t.check("Tiles off hides them and brings the grid back",
    !(await map("tiles()")).on && await map("gridVisible()")
    && await v.evaluate("document.getElementById('tileAttribution').hidden"));
  t.check("no page errors", v.errors.length === 0, v.errors.join(" | "));
} catch (err) {
  t.check(`no exception (${err && err.stack ? err.stack : err})`, false);
} finally {
  await v.close();
  corsSrv.close();
  nocorsSrv.close();
}
void wmsLog;
console.log(t.failures ? `\n${t.failures} of ${t.count} FAILED` : `\nall ${t.count} passed`);
process.exit(t.failures ? 1 : 0);
