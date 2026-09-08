// Lanelet2 live 3D viewer (browser side).
//
// Connects to the viewer server's SSE stream (/events) and renders the live
// scene with Three.js. The server pushes a `snapshot` on connect, then
// `patch` / `clear` messages as JOSM data changes. We keep a Map of feature id
// -> THREE.Object3D so patches update only what changed.
//
// Coordinates arrive as local ENU metres relative to an anchor lat/lon, so the
// scene is centred near the origin. We render on the XY plane (Z up).

import * as THREE from "three";
import { OrbitControls } from "three/addons/controls/OrbitControls.js";
import { TransformControls } from "three/addons/controls/TransformControls.js";

// Profiling is opt-in via ?profile=1. When on, the browser logs a per-stage
// timing breakdown (parse / apply / index / pick-cloud) for every message and
// shows live render stats, so large-map slowness can be attributed to a stage.
const PROFILE = new URLSearchParams(location.search).get("profile") === "1";
function plog() {
  if (PROFILE) console.log.apply(console, ["[viewer][perf]"].concat([].slice.call(arguments)));
}
const now = () => (typeof performance !== "undefined" ? performance.now() : Date.now());
let perfPickMs = 0; // last rebuildPickCloud() cost, filled by the index rebuild

// Loose colour mapping by Lanelet2 tag. Not MapCSS parity, just orientation.
const TYPE_COLORS = {
  line_thin: 0xffffff,
  line_thick: 0xffffff,
  stop_line: 0xff3b30,
  pedestrian_marking: 0xffd23f,
  bike_marking: 0x9bd24f,
  virtual: 0x5b6b82,
  road_border: 0xff8c42,
  curbstone: 0xc9a14a,
  guard_rail: 0x8aa0bf,
  traffic_sign: 0x4fa3ff,
  traffic_light: 0xffd23f,
  traffic_light_bikes: 0xffd23f,
  traffic_light_pedestrians: 0xffd23f,
  traffic_light_misc: 0xffd23f,
  arrow: 0xffd23f,
  symbol: 0xffd23f,
};
const DEFAULT_COLOR = 0x8899aa;
const VIEWPORT_COLOR = 0x9aa3af; // gray outline of the JOSM viewport on the floor

function featureColor(feature) {
  if (feature.color) {
    try { return new THREE.Color(feature.color); } catch (_) { /* fall through */ }
  }
  const t = feature.tags && feature.tags.type;
  return new THREE.Color(TYPE_COLORS[t] !== undefined ? TYPE_COLORS[t] : DEFAULT_COLOR);
}

// --- traffic-element icons (style_images, served by the Python server) -----
const ARROW_ICONS = {
  straight: "pf-g.png",
  left: "pf-l.png",
  right: "pf-r.png",
  left_right: "pf-lr.png",
  straight_left: "pf-gl.png",
  straight_right: "pf-gr.png",
};
const SYMBOL_ICONS = {
  bicycle: "bike.png",
  bus: "bus.png",
  "30": "30.png",
  "50": "50.png",
  "70": "70.png",
};
const ICON_LIFT_M = 0.03;
const ICON_MIN_EXTENT_M = 0.08;
const ICON_BACK_COLOR = 0x808080; // 50% grey multiply on the reverse face

function isFacedIconType(type) {
  return type === "traffic_sign" || (type && type.indexOf("traffic_light") === 0);
}

function iconSpec(feature) {
  const tags = feature.tags || {};
  const t = tags.type || "";
  const st = tags.subtype || "";
  const bike = tags["participant:bicycle"];

  if (t === "arrow") {
    const file = ARROW_ICONS[st];
    return file ? { file } : null;
  }
  if (t === "symbol") {
    const file = SYMBOL_ICONS[st];
    return file ? { file } : null;
  }
  if (t === "traffic_light_bikes") {
    return { file: "traffic_light_bikes.svg" };
  }
  if (t === "traffic_light_pedestrians") {
    return { file: "traffic_light_pedestrians.svg" };
  }
  if (t === "traffic_light_misc") {
    return { file: "traffic_light_misc.svg" };
  }
  if (t === "traffic_light") {
    if (bike === "yes") {
      return { file: "traffic_light_bikes.svg" };
    }
    return { file: "traffic_light.png" };
  }
  if (t === "traffic_sign") {
    if (st && (st.indexOf("us") === 0 || st.indexOf("se") === 0)) {
      return { file: "traffic_sign.png" };
    }
    if (st && st.indexOf("de") === 0) {
      const id = st.slice(2).replace(/_/g, ".");
      if (id) return { file: id + ".png" };
    }
    return { file: "traffic_sign.png" };
  }
  return null;
}

const textureLoader = new THREE.TextureLoader();
// url -> { tex, ready, pending: [{onLoad, onError}] }
const textureCache = new Map();
const failedIcons = new Set();

function loadIconTexture(file, onLoad, onError) {
  const url = "/style_images/" + encodeURIComponent(file);
  if (failedIcons.has(url)) {
    if (onError) onError();
    return;
  }
  const cached = textureCache.get(url);
  if (cached) {
    if (cached.ready) onLoad(cached.tex);
    else cached.pending.push({ onLoad, onError });
    return;
  }
  const entry = { tex: null, ready: false, pending: [{ onLoad, onError }] };
  textureCache.set(url, entry);
  textureLoader.load(url, (tex) => {
    if (tex.colorSpace !== undefined) tex.colorSpace = THREE.SRGBColorSpace;
    tex.needsUpdate = true;
    entry.tex = tex;
    entry.ready = true;
    for (const p of entry.pending) p.onLoad(tex);
    entry.pending = [];
  }, undefined, () => {
    failedIcons.add(url);
    textureCache.delete(url);
    for (const p of entry.pending) {
      if (p.onError) p.onError();
    }
    entry.pending = [];
  });
}

function textureAspect(tex) {
  const img = tex && tex.image;
  if (img && img.width && img.height) return img.width / img.height;
  return 1;
}

function toVec3(p) {
  return new THREE.Vector3(p[0], p[1], p[2] !== undefined ? p[2] : 0);
}

function uniquePolyPoints(pts) {
  const out = [];
  for (let i = 0; i < pts.length; i++) {
    const v = toVec3(pts[i]);
    if (out.length && out[out.length - 1].distanceToSquared(v) < 1e-8) continue;
    out.push(v);
  }
  if (out.length >= 2 && out[0].distanceToSquared(out[out.length - 1]) < 1e-8) {
    out.pop();
  }
  return out;
}

// Fit an oriented rectangle to the way points. Image +Y follows world-up
// projected into the plane (or the start→end chord on the ground). Size is
// the actual extent of the polyline, so a 0.6 m sign stays 0.6 m.
function fitIconFrame(pts, aspect) {
  const verts = uniquePolyPoints(pts);
  if (verts.length < 2) return null;

  const center = new THREE.Vector3();
  for (let i = 0; i < verts.length; i++) center.add(verts[i]);
  center.multiplyScalar(1 / verts.length);

  const normal = new THREE.Vector3();
  if (verts.length >= 3) {
    for (let i = 0; i < verts.length; i++) {
      const a = verts[i].clone().sub(center);
      const b = verts[(i + 1) % verts.length].clone().sub(center);
      normal.add(a.cross(b));
    }
    if (normal.lengthSq() < 1e-10) normal.set(0, 0, 1);
    else normal.normalize();
  } else {
    const chord = verts[1].clone().sub(verts[0]);
    const horiz = Math.hypot(chord.x, chord.y);
    if (Math.abs(chord.z) > horiz * 0.5) {
      // Mostly vertical 2-point way: face sideways so the icon stands up.
      normal.set(-chord.y, chord.x, 0);
      if (normal.lengthSq() < 1e-10) normal.set(1, 0, 0);
      normal.normalize();
    } else {
      normal.set(0, 0, 1);
    }
  }

  const yAxis = new THREE.Vector3(0, 0, 1).addScaledVector(normal, -normal.z);
  if (yAxis.lengthSq() < 1e-8) {
    yAxis.copy(verts[verts.length - 1]).sub(verts[0]);
    if (yAxis.lengthSq() < 1e-8) yAxis.set(0, 1, 0);
  }
  yAxis.normalize();
  const xAxis = new THREE.Vector3().crossVectors(yAxis, normal);
  if (xAxis.lengthSq() < 1e-10) {
    xAxis.set(1, 0, 0);
  } else {
    xAxis.normalize();
  }
  yAxis.crossVectors(normal, xAxis).normalize();

  let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity;
  const tmp = new THREE.Vector3();
  for (let i = 0; i < verts.length; i++) {
    tmp.copy(verts[i]).sub(center);
    const x = tmp.dot(xAxis);
    const y = tmp.dot(yAxis);
    if (x < minX) minX = x;
    if (x > maxX) maxX = x;
    if (y < minY) minY = y;
    if (y > maxY) maxY = y;
  }
  let width = maxX - minX;
  let height = maxY - minY;
  const ar = (aspect > 0.05 && aspect < 20) ? aspect : 1;
  // A 2-point way has no width: fill the missing axis from the texture aspect.
  if (width < ICON_MIN_EXTENT_M && height >= ICON_MIN_EXTENT_M) {
    width = height * ar;
  } else if (height < ICON_MIN_EXTENT_M && width >= ICON_MIN_EXTENT_M) {
    height = width / ar;
  } else if (width < ICON_MIN_EXTENT_M && height < ICON_MIN_EXTENT_M) {
    height = 0.5;
    width = height * ar;
  }

  center.addScaledVector(normal, ICON_LIFT_M);
  return { center, xAxis, yAxis, normal, width, height };
}

// Lanelet2 sign/light quad, facing the "camera" on the front:
//   P1 bottom-left, P2 top-left, P3 top-right, P4 bottom-right.
// Front normal is right × up = (P4-P1) × (P2-P1). UVs put the icon upright
// on that face; two triangles are wound CCW from the front (P1-P4-P3, P1-P3-P2).
function signQuadGeometry(p1, p2, p3, p4) {
  const geom = new THREE.BufferGeometry();
  const positions = new Float32Array([
    p1.x, p1.y, p1.z, p4.x, p4.y, p4.z, p3.x, p3.y, p3.z,
    p1.x, p1.y, p1.z, p3.x, p3.y, p3.z, p2.x, p2.y, p2.z,
  ]);
  const uvs = new Float32Array([
    0, 0, 1, 0, 1, 1,
    0, 0, 1, 1, 0, 1,
  ]);
  geom.setAttribute("position", new THREE.BufferAttribute(positions, 3));
  geom.setAttribute("uv", new THREE.BufferAttribute(uvs, 2));
  geom.computeVertexNormals();
  return geom;
}

function addFacedSignIcon(group, p1, p2, p3, p4, tex) {
  const right = new THREE.Vector3().subVectors(p4, p1);
  const up = new THREE.Vector3().subVectors(p2, p1);
  const front = new THREE.Vector3().crossVectors(right, up);
  if (front.lengthSq() < 1e-12) return;
  front.normalize();
  const lift = front.multiplyScalar(ICON_LIFT_M);
  const a = p1.clone().add(lift);
  const b = p2.clone().add(lift);
  const c = p3.clone().add(lift);
  const d = p4.clone().add(lift);
  const geom = signQuadGeometry(a, b, c, d);
  const frontMat = new THREE.MeshBasicMaterial({
    map: tex, transparent: true, side: THREE.FrontSide, depthWrite: false,
  });
  const backMat = new THREE.MeshBasicMaterial({
    map: tex, color: ICON_BACK_COLOR, transparent: true,
    side: THREE.BackSide, depthWrite: false,
  });
  const frontMesh = new THREE.Mesh(geom, frontMat);
  const backMesh = new THREE.Mesh(geom.clone(), backMat);
  frontMesh.renderOrder = 20;
  backMesh.renderOrder = 19;
  group.add(frontMesh);
  group.add(backMesh);
}

function attachIcon(group, feature, spec) {
  loadIconTexture(spec.file, (tex) => {
    if (group.userData.disposed) return;
    const pts = feature.points || [];
    if (pts.length < 1) return;
    const tags = feature.tags || {};
    if (isFacedIconType(tags.type)) {
      const verts = uniquePolyPoints(pts);
      if (verts.length >= 4) {
        addFacedSignIcon(group, verts[0], verts[1], verts[2], verts[3], tex);
        return;
      }
    }
    const frame = fitIconFrame(pts, textureAspect(tex));
    if (!frame) return;
    const geom = new THREE.PlaneGeometry(frame.width, frame.height);
    const mat = new THREE.MeshBasicMaterial({
      map: tex, transparent: true, side: THREE.DoubleSide, depthWrite: false,
    });
    const mesh = new THREE.Mesh(geom, mat);
    mesh.position.copy(frame.center);
    const basis = new THREE.Matrix4();
    basis.makeBasis(frame.xAxis, frame.yAxis, frame.normal);
    mesh.quaternion.setFromRotationMatrix(basis);
    mesh.renderOrder = 20;
    group.add(mesh);
  });
}

// --- scene setup -----------------------------------------------------------
const appEl = document.getElementById("app");
const renderer = new THREE.WebGLRenderer({ antialias: true });
renderer.setPixelRatio(window.devicePixelRatio);
renderer.setSize(window.innerWidth, window.innerHeight);
appEl.appendChild(renderer.domElement);

const scene = new THREE.Scene();
scene.background = new THREE.Color(0x11151c);

const camera = new THREE.PerspectiveCamera(
  55, window.innerWidth / window.innerHeight, 0.1, 100000);
camera.up.set(0, 0, 1); // Z is up (ENU)
camera.position.set(40, -60, 50);

const controls = new OrbitControls(camera, renderer.domElement);
controls.enableDamping = true;
controls.dampingFactor = 0.08;
controls.enableKeys = false; // WASD / arrows are handled in the render loop

// Ground grid + axes for spatial reference.
const grid = new THREE.GridHelper(400, 40, 0x2a3340, 0x20262f);
grid.rotation.x = Math.PI / 2; // GridHelper is XZ by default; rotate to XY
scene.add(grid);
scene.add(new THREE.AxesHelper(5));

// Container for all map features so we can frame/clear them as a unit.
const featureGroup = new THREE.Group();
scene.add(featureGroup);

// id -> { object, feature }
const objects = new Map();

// --- HUD -------------------------------------------------------------------
const hud = {
  status: document.getElementById("status"),
  conn: document.getElementById("conn"),
  count: document.getElementById("count"),
  seq: document.getElementById("seq"),
  anchor: document.getElementById("anchor"),
  edit: document.getElementById("edit"),
  look: document.getElementById("look"),
  camPos: document.getElementById("camPos"),
  camTgt: document.getElementById("camTgt"),
  camAng: document.getElementById("camAng"),
  camUp: document.getElementById("camUp"),
  josmView: document.getElementById("josmView"),
  frameDebug: document.getElementById("frameDebug"),
  perf: document.getElementById("perf"),
  render: document.getElementById("render"),
};
if (PROFILE) {
  const pr = document.getElementById("perfRow");
  const rr = document.getElementById("renderRow");
  if (pr) pr.style.display = "";
  if (rr) rr.style.display = "";
}
function setConn(live, text) {
  hud.status.classList.toggle("live", !!live);
  hud.conn.textContent = text;
}
function refreshHud(seq, anchor) {
  // The viewport rectangle is an overlay, not map data: keep it out of the count.
  hud.count.textContent = String(objects.has("viewport") ? objects.size - 1 : objects.size);
  if (seq !== undefined && seq !== null) hud.seq.textContent = String(seq);
  if (anchor) hud.anchor.textContent = `${anchor.lat.toFixed(5)}, ${anchor.lon.toFixed(5)}`;
}

// --- feature build / update ------------------------------------------------
function buildLine(feature) {
  const pts = feature.points || [];
  const positions = new Float32Array(pts.length * 3);
  for (let i = 0; i < pts.length; i++) {
    positions[i * 3 + 0] = pts[i][0];
    positions[i * 3 + 1] = pts[i][1];
    positions[i * 3 + 2] = pts[i][2] !== undefined ? pts[i][2] : 0;
  }
  const geom = new THREE.BufferGeometry();
  geom.setAttribute("position", new THREE.BufferAttribute(positions, 3));
  const mat = new THREE.LineBasicMaterial({ color: featureColor(feature) });
  return new THREE.Line(geom, mat);
}

function buildViewportRect(feature) {
  // The JOSM viewport rectangle: a gray closed loop on the floor plane, drawn
  // on top of other geometry so it stays visible as an overlay.
  const obj = buildLine(feature);
  obj.material.dispose();
  obj.material = new THREE.LineBasicMaterial({
    color: VIEWPORT_COLOR, transparent: true, opacity: 0.85, depthTest: false,
  });
  obj.renderOrder = 999;
  return obj;
}

function buildObject(feature) {
  if (feature.kind === "viewport") return buildViewportRect(feature);
  const line = buildLine(feature);
  const spec = iconSpec(feature);
  if (!spec) return line;
  const group = new THREE.Group();
  group.add(line);
  group.userData.line = line;
  attachIcon(group, feature, spec);
  return group;
}

function featureLine(obj) {
  if (!obj) return null;
  if (obj.userData && obj.userData.line) return obj.userData.line;
  return obj;
}

function disposeObject(obj) {
  if (!obj) return;
  obj.userData.disposed = true;
  obj.traverse((child) => {
    if (child.geometry) child.geometry.dispose();
    const mat = child.material;
    if (!mat) return;
    const mats = Array.isArray(mat) ? mat : [mat];
    for (const m of mats) m.dispose();
  });
}

function upsertFeature(feature) {
  if (!feature || !feature.id) return;
  const existing = objects.get(feature.id);
  if (existing) {
    disposeObject(existing.object);
    featureGroup.remove(existing.object);
  }
  const obj = buildObject(feature);
  featureGroup.add(obj);
  objects.set(feature.id, { object: obj, feature });
  if (feature.id === "viewport") {
    applyViewportCameraFollow(feature);
    noteJosmViewport(feature);
  }
}

function removeFeature(id) {
  const existing = objects.get(id);
  if (!existing) return;
  disposeObject(existing.object);
  featureGroup.remove(existing.object);
  objects.delete(id);
}

function clearAll() {
  for (const id of Array.from(objects.keys())) removeFeature(id);
  resetViewportCameraFollow();
}

// --- JOSM view follow (pan camera with cull / map centre) ------------------
let lastFollowCenter = null;
let lastViewSyncXY = null;   // last 3D camera XY that we posted (or adopted)
let ignoreFollowUntil = 0;   // skip JOSM→3D follow after we drove the view
const VIEW_SYNC_MIN_M = 30;  // pan JOSM after this much ego travel (metres)
const VIEW_SYNC_MIN_MS = 200;
const VIEW_SYNC_TELEPORT_M = 80; // ignore F/B/frame jumps; do not pan JOSM
let lastViewSyncT = 0;

function resetViewportCameraFollow() {
  lastFollowCenter = null;
  lastViewSyncXY = null;
}

function applyViewportCameraFollow(feature) {
  const follow = feature.tags && feature.tags.follow_camera === "1";
  if (!feature.center || feature.center.length < 2) {
    lastFollowCenter = null;
    return;
  }
  const cx = feature.center[0];
  const cy = feature.center[1];
  const cz = feature.center.length > 2 ? feature.center[2] : 0;

  // Record the JOSM/cull centre even when we are not translating the 3D camera
  // (drive-view echo, follow off, or a gizmo drag).
  if (!follow || transform.dragging || now() < ignoreFollowUntil) {
    lastFollowCenter = [cx, cy, cz];
    return;
  }

  if (lastFollowCenter === null) {
    lastFollowCenter = [cx, cy, cz];
    return;
  }

  const dx = cx - lastFollowCenter[0];
  const dy = cy - lastFollowCenter[1];
  const dz = cz - lastFollowCenter[2];
  lastFollowCenter = [cx, cy, cz];
  if (Math.abs(dx) < 1e-4 && Math.abs(dy) < 1e-4 && Math.abs(dz) < 1e-4) return;

  // Translate target + camera together so orbit angle and height stay fixed.
  controls.target.x += dx;
  controls.target.y += dy;
  controls.target.z += dz;
  camera.position.x += dx;
  camera.position.y += dy;
  camera.position.z += dz;
  lastViewSyncXY = [camera.position.x, camera.position.y];
  controls.update();
  refreshCameraHud();
}

function noteJosmViewport(feature) {
  if (!feature || !feature.center || feature.center.length < 2) return;
  const cx = feature.center[0];
  const cy = feature.center[1];
  const d = Math.hypot(cx - camera.position.x, cy - camera.position.y);
  setJosmViewStatus(
    `JOSM centre (${cx.toFixed(1)}, ${cy.toFixed(1)})  Δcam ${d.toFixed(1)} m`,
  );
}

function setJosmViewStatus(text) {
  if (hud.josmView) hud.josmView.textContent = text;
  console.log("[viewer] josm view:", text);
}

function requestJosmRecenter(reason, force) {
  const x = round3(camera.position.x);
  const y = round3(camera.position.y);
  lastViewSyncXY = [camera.position.x, camera.position.y];
  lastViewSyncT = now();
  lastFollowCenter = [x, y, lastFollowCenter ? lastFollowCenter[2] : 0];
  ignoreFollowUntil = now() + 2500;
  const op = { op: "set_view", x: x, y: y };
  if (force) op.force = true;
  setJosmViewStatus(`${reason}: POST (${x}, ${y})…`);
  return sendCommand([op]).then((info) => {
    if (!info || info.error) {
      setJosmViewStatus(`${reason}: POST failed (${info && info.error ? info.error : "no response"})`);
      return info;
    }
    if (!info.delivered) {
      setJosmViewStatus(`${reason}: server got it, delivered=0 (JOSM hook not connected)`);
      return info;
    }
    setJosmViewStatus(`${reason}: delivered=${info.delivered} — watch JOSM map + gray rect`);
    return info;
  });
}

function maybeSyncJosmView() {
  const x = camera.position.x;
  const y = camera.position.y;
  if (lastViewSyncXY === null) {
    lastViewSyncXY = [x, y];
    return;
  }
  const dist = Math.hypot(x - lastViewSyncXY[0], y - lastViewSyncXY[1]);
  if (dist > VIEW_SYNC_TELEPORT_M) {
    lastViewSyncXY = [x, y];
    return;
  }
  if (dist < VIEW_SYNC_MIN_M) return;
  const t = now();
  if (t - lastViewSyncT < VIEW_SYNC_MIN_MS) return;
  requestJosmRecenter(`auto ${Math.round(dist)} m`, false);
}

// --- message handling ------------------------------------------------------
let lastSeq = -1;
let currentAnchor = null;
let needFrame = false;

function applyMessage(msg, meta) {
  if (typeof msg.seq === "number") {
    if (msg.type === "snapshot") {
      lastSeq = msg.seq; // snapshot resets the sequence baseline
    } else if (msg.seq <= lastSeq) {
      return; // stale/out-of-order patch
    } else {
      lastSeq = msg.seq;
    }
  }

  const t0 = PROFILE ? now() : 0;
  let nOps = 0;
  if (msg.type === "snapshot") {
    const wasEmpty = objects.size === 0;
    clearAll();
    resetViewportCameraFollow();
    if (msg.anchor) currentAnchor = msg.anchor;
    const feats = msg.features || [];
    nOps = feats.length;
    for (const f of feats) upsertFeature(f);
    // Only auto-frame the first time data appears; later snapshots (e.g. live
    // edits streamed from JOSM) must not yank the camera the user has set.
    if (wasEmpty) needFrame = true;
  } else if (msg.type === "patch") {
    const ops = msg.ops || [];
    nOps = ops.length;
    for (const op of ops) {
      if (op.op === "upsert") upsertFeature(op.feature);
      else if (op.op === "remove") removeFeature(op.id);
      else if (op.op === "anchor") currentAnchor = { lat: op.lat, lon: op.lon };
    }
  } else if (msg.type === "clear") {
    clearAll();
  }
  const t1 = PROFILE ? now() : 0;
  // The node index + pick cloud are only needed while editing. Building them on
  // every message is O(all nodes) and pure waste in the common view-only case,
  // so defer: mark stale now and rebuild lazily when edit mode needs them.
  nodeIndexDirty = true;
  if (editMode) ensureNodeIndex();
  const t2 = PROFILE ? now() : 0;
  refreshHud(msg.seq, currentAnchor);

  if (PROFILE) {
    const parseMs = meta && meta.parseMs ? meta.parseMs : 0;
    const bytes = meta && meta.bytes ? meta.bytes : 0;
    const line = `${msg.type} ops=${nOps} feats=${objects.size} nodes=${nodePos.size}` +
      ` bytes=${bytes} parse=${parseMs.toFixed(1)} apply=${(t1 - t0).toFixed(1)}` +
      ` index=${(t2 - t1).toFixed(1)} (pick=${perfPickMs.toFixed(1)}) total=${(t2 - t0 + parseMs).toFixed(1)}ms`;
    plog(line);
    if (hud.perf) hud.perf.textContent = line.replace(/ /g, " ");
  }
}

// --- camera framing --------------------------------------------------------
const FRAME_ELE_MIN = -200;
const FRAME_ELE_MAX = 5000;
const RAD2DEG = 180 / Math.PI;
const DEFAULT_BEV_HEIGHT_M = 100;

function fmt1(v) {
  return Number(v).toFixed(1);
}

function fmtVec3(v) {
  return `(${fmt1(v.x)}, ${fmt1(v.y)}, ${fmt1(v.z)})`;
}

function refreshCameraHud() {
  if (!hud.camPos) return;
  const az = controls.getAzimuthalAngle() * RAD2DEG;
  const pol = controls.getPolarAngle() * RAD2DEG;
  const dist = camera.position.distanceTo(controls.target);
  hud.camPos.textContent = fmtVec3(camera.position);
  hud.camTgt.textContent = fmtVec3(controls.target);
  hud.camAng.textContent =
    `az ${fmt1(az)}° pol ${fmt1(pol)}° dist ${fmt1(dist)} m fov ${fmt1(camera.fov)}°`;
  hud.camUp.textContent = fmtVec3(camera.up);
}

function setFrameDebug(lines) {
  if (!hud.frameDebug) return;
  hud.frameDebug.textContent = Array.isArray(lines) ? lines.join("\n") : String(lines);
}

function describeBoundsBox(box) {
  if (!box || box.isEmpty()) {
    return { empty: true, min: null, max: null, center: null, size: null };
  }
  const center = box.getCenter(new THREE.Vector3());
  const size = box.getSize(new THREE.Vector3());
  return { empty: false, min: box.min.clone(), max: box.max.clone(), center, size };
}

function reportFrameDebug(label, boxInfo, params) {
  if (boxInfo.empty) {
    setFrameDebug([`${label}: empty / invalid bounds`, "no geometry to frame"]);
    return;
  }
  setFrameDebug([
    label,
    `bbox min ${fmtVec3(boxInfo.min)} max ${fmtVec3(boxInfo.max)}`,
    `center ${fmtVec3(boxInfo.center)} size (${fmt1(boxInfo.size.x)}, ${fmt1(boxInfo.size.y)}, ${fmt1(boxInfo.size.z)}) m`,
    `frame radius ${fmt1(params.radius)} m dist ${fmt1(params.dist)} m`,
    `placed cam ${fmtVec3(params.camPos)} tgt ${fmtVec3(params.target)} up ${fmtVec3(params.up)}`,
  ]);
}

function applyClipPlanes(dist) {
  camera.near = Math.max(0.1, dist / 1000);
  camera.far = Math.max(camera.near + 1, dist * 1000);
  camera.updateProjectionMatrix();
}

function frameEle(z) {
  if (z === undefined || z === null || !Number.isFinite(z)) return 0;
  if (z < FRAME_ELE_MIN || z > FRAME_ELE_MAX) return 0;
  return z;
}

function mapBoundsBox() {
  const box = new THREE.Box3();
  let found = false;
  for (const [id, entry] of objects) {
    if (id === "viewport") continue;
    const pts = entry.feature && entry.feature.points;
    if (!pts || pts.length === 0) continue;
    for (const p of pts) {
      const x = p[0];
      const y = p[1];
      const z = frameEle(p[2]);
      if (!Number.isFinite(x) || !Number.isFinite(y)) continue;
      box.expandByPoint(new THREE.Vector3(x, y, z));
      found = true;
    }
  }
  // Fallback if features have no point arrays (shouldn't happen for lines).
  if (!found) {
    for (const [id, entry] of objects) {
      if (id === "viewport") continue;
      box.expandByObject(entry.object);
    }
  }
  return box;
}

function frameAll() {
  if (objects.size === 0) return;
  const tf = PROFILE ? now() : 0;
  const box = mapBoundsBox();
  const boxInfo = describeBoundsBox(box);
  if (boxInfo.empty) {
    reportFrameDebug("frameAll (F)", boxInfo, {
      radius: 0, dist: 0,
      camPos: camera.position.clone(),
      target: controls.target.clone(),
      up: camera.up.clone(),
    });
    refreshCameraHud();
    return;
  }
  const center = boxInfo.center;
  const radius = Math.max(boxInfo.size.x, boxInfo.size.y, boxInfo.size.z, 5) * 0.5;
  const dist = radius / Math.tan((camera.fov * Math.PI) / 180 / 2) * 1.6;
  const up = new THREE.Vector3(0, 0, 1);
  const camPos = new THREE.Vector3(
    center.x + dist * 0.4, center.y - dist * 0.8, center.z + dist * 0.6,
  );
  camera.up.copy(up);
  controls.target.copy(center);
  camera.position.copy(camPos);
  applyClipPlanes(dist);
  controls.update();
  reportFrameDebug("frameAll (F)", boxInfo, { radius, dist, camPos, target: center, up });
  refreshCameraHud();
  if (PROFILE) plog(`frameAll objects=${objects.size} ms=${(now() - tf).toFixed(1)}`);
}

// Bird's-eye view: camera straight above the map, north (+Y) toward screen top.
function setBevNorth() {
  if (objects.size === 0) return;
  const tf = PROFILE ? now() : 0;
  const box = mapBoundsBox();
  const boxInfo = describeBoundsBox(box);
  if (boxInfo.empty) {
    reportFrameDebug("BEV north (B)", boxInfo, {
      radius: 0, dist: 0,
      camPos: camera.position.clone(),
      target: controls.target.clone(),
      up: camera.up.clone(),
    });
    refreshCameraHud();
    return;
  }
  const center = boxInfo.center;
  const radius = Math.max(boxInfo.size.x, boxInfo.size.y, 5) * 0.5;
  const dist = radius / Math.tan((camera.fov * Math.PI) / 180 / 2) * 1.6;
  const up = new THREE.Vector3(0, 1, 0);
  const camPos = new THREE.Vector3(center.x, center.y, center.z + dist);
  camera.up.copy(up);
  controls.target.copy(center);
  camera.position.copy(camPos);
  camera.lookAt(center);
  applyClipPlanes(dist);
  controls.update();
  reportFrameDebug("BEV north (B)", boxInfo, { radius, dist, camPos, target: center, up });
  refreshCameraHud();
  if (PROFILE) plog(`setBevNorth objects=${objects.size} ms=${(now() - tf).toFixed(1)}`);
}

// Fixed sanity view when auto-framing fails or data is far from the origin.
function setDefaultBevView() {
  const target = new THREE.Vector3(0, 0, 0);
  const up = new THREE.Vector3(0, 1, 0);
  const camPos = new THREE.Vector3(0, 0, DEFAULT_BEV_HEIGHT_M);
  camera.up.copy(up);
  controls.target.copy(target);
  camera.position.copy(camPos);
  camera.lookAt(target);
  applyClipPlanes(DEFAULT_BEV_HEIGHT_M);
  controls.update();
  setFrameDebug([
    "default BEV @ origin",
    `fixed tgt (0.0, 0.0, 0.0) cam (0.0, 0.0, ${fmt1(DEFAULT_BEV_HEIGHT_M)}) m`,
    "north (+Y) toward screen top; use when F/B framing fails",
  ]);
  refreshCameraHud();
}

// --- manual camera: arrows pan, Ctrl+arrows look (FPS, around camera) ------
const CAM_PAN_STEP_M = 1;    // metres per pan / height tick (walk)
const CAM_FAST_STEP_M = 10;  // metres per tick with Shift (WASD / arrows / pad)
const CAM_ROT_STEP = 0.05;   // radians per step (~3°)
const CAM_HOLD_MS = 50;
const CAM_PITCH_MAX = 0.995; // |look·up| clamp (~85° from horizontal)
const _worldUp = new THREE.Vector3(0, 0, 1);
const _tmpLook = new THREE.Vector3();
const _tmpForward = new THREE.Vector3();
const _tmpRight = new THREE.Vector3();
const _tmpQuat = new THREE.Quaternion();
const moveKeys = new Set();
let altHeld = false;
let ctrlHeld = false;
let shiftHeld = false;

function moveStepM() {
  return shiftHeld ? CAM_FAST_STEP_M : CAM_PAN_STEP_M;
}

function cameraPan(dx, dy, meters) {
  const step = meters == null ? CAM_PAN_STEP_M : meters;
  _tmpForward.subVectors(controls.target, camera.position);
  _tmpForward.z = 0;
  if (_tmpForward.lengthSq() < 1e-8) {
    _tmpForward.set(-camera.up.x, -camera.up.y, 0);
    if (_tmpForward.lengthSq() < 1e-8) _tmpForward.set(0, 1, 0);
  }
  _tmpForward.normalize();
  _tmpRight.crossVectors(_tmpForward, _worldUp);
  if (_tmpRight.lengthSq() < 1e-8) _tmpRight.set(1, 0, 0);
  else _tmpRight.normalize();
  const tx = _tmpRight.x * dx + _tmpForward.x * dy;
  const ty = _tmpRight.y * dx + _tmpForward.y * dy;
  camera.position.x += tx * step;
  camera.position.y += ty * step;
  controls.target.x += tx * step;
  controls.target.y += ty * step;
  controls.update();
  refreshCameraHud();
}

function cameraElevate(dz, meters) {
  const step = (meters == null ? CAM_PAN_STEP_M : meters) * dz;
  camera.position.z += step;
  controls.target.z += step;
  controls.update();
  refreshCameraHud();
}

function cameraLook(yawRad, pitchRad) {
  // FPS look: rotate around the camera, keep world Z as up (no roll).
  const dist = Math.max(1, camera.position.distanceTo(controls.target));
  _tmpLook.subVectors(controls.target, camera.position);
  if (_tmpLook.lengthSq() < 1e-12) _tmpLook.set(0, 1, 0);
  _tmpLook.normalize();

  if (yawRad) {
    _tmpQuat.setFromAxisAngle(_worldUp, -yawRad);
    _tmpLook.applyQuaternion(_tmpQuat);
  }

  if (pitchRad) {
    _tmpRight.crossVectors(_tmpLook, _worldUp);
    if (_tmpRight.lengthSq() < 1e-12) _tmpRight.set(1, 0, 0);
    else _tmpRight.normalize();
    _tmpQuat.setFromAxisAngle(_tmpRight, pitchRad);
    const pitched = _tmpForward.copy(_tmpLook).applyQuaternion(_tmpQuat);
    if (Math.abs(pitched.dot(_worldUp)) < CAM_PITCH_MAX) {
      _tmpLook.copy(pitched).normalize();
    }
  }

  camera.up.copy(_worldUp);
  controls.target.copy(camera.position).addScaledVector(_tmpLook, dist);
  camera.lookAt(controls.target);
  controls.update();
  refreshCameraHud();
}

function cameraRotate(dYaw, dPitch) {
  cameraLook(dYaw * CAM_ROT_STEP, dPitch * CAM_ROT_STEP);
}

function bindHoldButton(btn, fn) {
  let timer = null;
  const stop = () => {
    if (timer !== null) {
      clearInterval(timer);
      timer = null;
    }
  };
  btn.addEventListener("pointerdown", (e) => {
    e.preventDefault();
    btn.setPointerCapture(e.pointerId);
    fn();
    timer = setInterval(fn, CAM_HOLD_MS);
  });
  btn.addEventListener("pointerup", stop);
  btn.addEventListener("pointercancel", stop);
  btn.addEventListener("lostpointercapture", stop);
}

function parseVecAttr(el, name) {
  const raw = el.getAttribute(name) || "0,0";
  const parts = raw.split(",");
  return [Number(parts[0]) || 0, Number(parts[1]) || 0];
}

document.querySelectorAll("#camPan [data-pan]").forEach((btn) => {
  const [dx, dy] = parseVecAttr(btn, "data-pan");
  bindHoldButton(btn, () => cameraPan(dx, dy, moveStepM()));
});
document.querySelectorAll("#camRot [data-rot]").forEach((btn) => {
  const [dTheta, dPhi] = parseVecAttr(btn, "data-rot");
  bindHoldButton(btn, () => cameraRotate(dTheta, dPhi));
});
document.querySelectorAll("#camElev [data-elev]").forEach((btn) => {
  const dz = Number(btn.getAttribute("data-elev")) || 0;
  bindHoldButton(btn, () => cameraElevate(dz, moveStepM()));
});

const CAM_MOUSE_SENS = 0.0025; // radians per pixel in FPS look
let fpsLookEnabled = false;

function isPointerLocked() {
  return document.pointerLockElement === renderer.domElement;
}

function refreshLookHud() {
  if (!hud.look) return;
  if (isPointerLocked()) hud.look.textContent = "FPS (mouse captured)";
  else if (fpsLookEnabled) hud.look.textContent = "FPS (click view)";
  else hud.look.textContent = "orbit";
}

function setFpsLook(on) {
  fpsLookEnabled = !!on;
  const btn = document.getElementById("fpsBtn");
  if (btn) btn.classList.toggle("on", fpsLookEnabled);
  if (!fpsLookEnabled && isPointerLocked()) {
    document.exitPointerLock();
  }
  if (fpsLookEnabled && editMode) setEditMode(false);
  refreshLookHud();
}

function requestFpsLock() {
  if (!fpsLookEnabled || editMode || isPointerLocked()) return;
  renderer.domElement.requestPointerLock();
}

document.addEventListener("pointerlockchange", () => {
  const locked = isPointerLocked();
  controls.enableRotate = !locked;
  controls.enablePan = !locked;
  refreshLookHud();
});

renderer.domElement.addEventListener("mousemove", (e) => {
  if (!isPointerLocked()) return;
  cameraLook(e.movementX * CAM_MOUSE_SENS, -e.movementY * CAM_MOUSE_SENS);
});

renderer.domElement.addEventListener("click", () => {
  if (fpsLookEnabled && !editMode && !isPointerLocked()) requestFpsLock();
});

const fpsBtn = document.getElementById("fpsBtn");
if (fpsBtn) {
  fpsBtn.addEventListener("click", () => {
    const next = !fpsLookEnabled;
    setFpsLook(next);
    if (next) requestFpsLock();
  });
}
const recenterJosmBtn = document.getElementById("recenterJosmBtn");
if (recenterJosmBtn) {
  recenterJosmBtn.addEventListener("click", () => {
    requestJosmRecenter("button", true);
  });
}
refreshLookHud();

function applyHeldCamera(dt) {
  if (!moveKeys.size || dt <= 0) return;
  let dx = 0, dy = 0, dz = 0;
  if (moveKeys.has("KeyW")) dy += 1;
  if (moveKeys.has("KeyS")) dy -= 1;
  if (moveKeys.has("KeyA")) dx -= 1;
  if (moveKeys.has("KeyD")) dx += 1;
  if (moveKeys.has("ArrowUp")) {
    if (altHeld) dz += 1;
    else if (!ctrlHeld) dy += 1;
  }
  if (moveKeys.has("ArrowDown")) {
    if (altHeld) dz -= 1;
    else if (!ctrlHeld) dy -= 1;
  }
  if (moveKeys.has("ArrowLeft") && !ctrlHeld && !altHeld) dx -= 1;
  if (moveKeys.has("ArrowRight") && !ctrlHeld && !altHeld) dx += 1;
  if (fpsLookEnabled) {
    if (moveKeys.has("Space")) dz += 1;
    if (moveKeys.has("KeyC")) dz -= 1;
  }
  const dist = moveStepM() * (dt / (CAM_HOLD_MS / 1000));
  if (dx || dy) {
    const len = Math.hypot(dx, dy) || 1;
    cameraPan(dx / len, dy / len, dist);
  }
  if (dz) cameraElevate(dz > 0 ? 1 : -1, dist);
}

function isTypingTarget(el) {
  if (!el || el === document.body || el === document.documentElement) return false;
  const tag = el.tagName;
  return tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT" || el.isContentEditable;
}

document.addEventListener("keydown", (e) => {
  if (e.code === "AltLeft" || e.code === "AltRight") altHeld = true;
  shiftHeld = e.shiftKey;
  if (e.code === "ControlLeft" || e.code === "ControlRight"
      || e.code === "MetaLeft" || e.code === "MetaRight") ctrlHeld = true;
  if (isTypingTarget(e.target)) return;

  if ((e.key === "f" || e.key === "F") && !e.repeat) {
    frameAll();
    return;
  }
  if ((e.key === "b" || e.key === "B") && !e.repeat) {
    setBevNorth();
    return;
  }

  // Hold-to-move: record keys here; applyHeldCamera() in the render loop so
  // mouse look (pointer lock) does not starve WASD of key-repeat events.
  if ((e.code === "KeyW" || e.code === "KeyA" || e.code === "KeyS" || e.code === "KeyD")
      && !e.ctrlKey && !e.metaKey && !e.altKey) {
    e.preventDefault();
    moveKeys.add(e.code);
    return;
  }
  if (fpsLookEnabled && (e.code === "Space" || e.code === "KeyC")
      && !e.ctrlKey && !e.metaKey && !e.altKey) {
    e.preventDefault();
    moveKeys.add(e.code);
    return;
  }
  if (e.key === "ArrowLeft" || e.key === "ArrowRight"
      || e.key === "ArrowUp" || e.key === "ArrowDown") {
    e.preventDefault();
    if (e.ctrlKey || e.metaKey) {
      const dx = e.key === "ArrowLeft" ? -1 : e.key === "ArrowRight" ? 1 : 0;
      const dy = e.key === "ArrowUp" ? 1 : e.key === "ArrowDown" ? -1 : 0;
      cameraRotate(dx, dy);
    } else {
      moveKeys.add(e.code);
    }
  }
}, true);

document.addEventListener("keyup", (e) => {
  if (e.code === "AltLeft" || e.code === "AltRight") altHeld = false;
  shiftHeld = e.shiftKey;
  if (e.code === "ControlLeft" || e.code === "ControlRight"
      || e.code === "MetaLeft" || e.code === "MetaRight") ctrlHeld = false;
  moveKeys.delete(e.code);
}, true);

window.addEventListener("blur", () => {
  moveKeys.clear();
  altHeld = false;
  ctrlHeld = false;
  shiftHeld = false;
});

const bevBtn = document.getElementById("bevBtn");
if (bevBtn) bevBtn.addEventListener("click", () => setBevNorth());
const defaultBevBtn = document.getElementById("defaultBevBtn");
if (defaultBevBtn) defaultBevBtn.addEventListener("click", () => setDefaultBevView());
refreshCameraHud();

// --- 3D editing: move JOSM nodes from the browser --------------------------
// Way features carry a `nodes` array parallel to `points` (node ids). We index
// every node so a vertex can be dragged in 3D and the change posted back to the
// JOSM bridge as a `move_node` command. JOSM applies it (undoable) and echoes
// the authoritative geometry back through the normal snapshot/patch path.

const EDIT_COLOR = 0x46c46a;
const SELECT_COLOR = 0xffd23f;

// nodeId -> [{ id: featureId, idx: vertexIndex }]
const nodeRefs = new Map();
// nodeId -> [x, y, z]
const nodePos = new Map();

let editMode = false;
let selectedNode = null;
let dragMoved = false; // true once a gizmo drag actually changed the position
let nodeIndexDirty = true; // node index is stale; rebuild lazily on demand

// Pickable point cloud of all node vertices (only shown in edit mode).
const pickGeom = new THREE.BufferGeometry();
pickGeom.setAttribute("position", new THREE.BufferAttribute(new Float32Array(0), 3));
const pickMat = new THREE.PointsMaterial({ color: EDIT_COLOR, size: 6, sizeAttenuation: false });
const pickPoints = new THREE.Points(pickGeom, pickMat);
pickPoints.visible = false;
pickPoints.renderOrder = 1000;
scene.add(pickPoints);
let pickIds = []; // pick vertex index -> nodeId

// Selected-node handle that TransformControls manipulates.
const marker = new THREE.Mesh(
  new THREE.SphereGeometry(0.6, 16, 12),
  new THREE.MeshBasicMaterial({ color: SELECT_COLOR, depthTest: false, transparent: true, opacity: 0.9 }),
);
marker.visible = false;
marker.renderOrder = 1001;
scene.add(marker);

const transform = new TransformControls(camera, renderer.domElement);
transform.setSize(0.9);
transform.addEventListener("dragging-changed", (e) => {
  controls.enabled = !e.value;
  if (!e.value) {
    const locked = isPointerLocked();
    controls.enableRotate = !locked;
    controls.enablePan = !locked;
  }
});
transform.addEventListener("mouseDown", () => { dragMoved = false; });
transform.addEventListener("objectChange", onGizmoChange);
transform.addEventListener("mouseUp", commitMove);
scene.add(transform);

function ensureNodeIndex() {
  if (nodeIndexDirty) rebuildNodeIndex();
}

function rebuildNodeIndex() {
  nodeIndexDirty = false;
  nodeRefs.clear();
  nodePos.clear();
  for (const [fid, entry] of objects) {
    if (fid === "viewport") continue;
    const f = entry.feature;
    const ids = f.nodes;
    const pts = f.points;
    if (!ids || !pts) continue;
    const n = Math.min(ids.length, pts.length);
    for (let i = 0; i < n; i++) {
      const nid = ids[i];
      if (!nid) continue;
      let refs = nodeRefs.get(nid);
      if (!refs) { refs = []; nodeRefs.set(nid, refs); }
      refs.push({ id: fid, idx: i });
      if (!nodePos.has(nid)) {
        const p = pts[i];
        nodePos.set(nid, [p[0], p[1], p[2] !== undefined ? p[2] : 0]);
      }
    }
  }
  rebuildPickCloud();
  // Keep the selection pinned to authoritative geometry (unless mid-drag).
  if (selectedNode && !transform.dragging) {
    if (nodePos.has(selectedNode)) {
      const p = nodePos.get(selectedNode);
      marker.position.set(p[0], p[1], p[2]);
    } else {
      deselect();
    }
  }
}

function rebuildPickCloud() {
  const tp = PROFILE ? now() : 0;
  const count = nodePos.size;
  const positions = new Float32Array(count * 3);
  pickIds = new Array(count);
  let i = 0;
  for (const [nid, p] of nodePos) {
    positions[i * 3 + 0] = p[0];
    positions[i * 3 + 1] = p[1];
    positions[i * 3 + 2] = p[2];
    pickIds[i] = nid;
    i++;
  }
  pickGeom.setAttribute("position", new THREE.BufferAttribute(positions, 3));
  pickGeom.attributes.position.needsUpdate = true;
  pickGeom.computeBoundingSphere();
  if (PROFILE) perfPickMs = now() - tp;
}

function setEditMode(on) {
  if (on && fpsLookEnabled) setFpsLook(false);
  editMode = !!on;
  if (editMode) ensureNodeIndex(); // build the (possibly stale) index on demand
  pickPoints.visible = editMode;
  if (!editMode) deselect();
  hud.edit.textContent = editMode ? "ON" : "off";
}

function selectNode(nid) {
  if (!nodePos.has(nid)) return;
  selectedNode = nid;
  const p = nodePos.get(nid);
  marker.position.set(p[0], p[1], p[2]);
  marker.visible = true;
  transform.attach(marker);
  hud.edit.textContent = `ON (${nid})`;
}

function deselect() {
  selectedNode = null;
  transform.detach();
  marker.visible = false;
  if (editMode) hud.edit.textContent = "ON";
}

// Drag in progress: move the node locally so connected ways follow live.
let lastGizmoLog = 0;
function onGizmoChange() {
  if (!selectedNode) return;
  dragMoved = true;
  const tg = PROFILE ? now() : 0;
  const x = marker.position.x, y = marker.position.y, z = marker.position.z;
  nodePos.set(selectedNode, [x, y, z]);
  const refs = nodeRefs.get(selectedNode) || [];
  for (const ref of refs) {
    const entry = objects.get(ref.id);
    if (!entry) continue;
    const lineObj = featureLine(entry.object);
    if (!lineObj || !lineObj.geometry) continue;
    const attr = lineObj.geometry.getAttribute("position");
    if (!attr || ref.idx >= attr.count) continue;
    attr.setXYZ(ref.idx, x, y, z);
    attr.needsUpdate = true;
    lineObj.geometry.computeBoundingSphere();
  }
  if (PROFILE) {
    const t = now();
    if (t - lastGizmoLog > 250) { // drag fires per frame; throttle the log
      lastGizmoLog = t;
      plog(`gizmo node=${selectedNode} ways=${refs.length} ms=${(t - tg).toFixed(2)}`);
    }
  }
}

// Drag finished: tell JOSM to apply the move (undoable, echoed back).
function commitMove() {
  if (!selectedNode || !dragMoved) return;
  dragMoved = false;
  const p = nodePos.get(selectedNode);
  if (!p) return;
  sendCommand([{ op: "move_node", id: selectedNode, x: round3(p[0]), y: round3(p[1]), z: round3(p[2]) }]);
}

function round3(v) { return Math.round(v * 1000) / 1000; }

function sendCommand(ops) {
  return fetch("/command", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ type: "command", ops }),
  }).then((r) => {
    if (!r.ok) throw new Error("HTTP " + r.status);
    return r.json();
  }).catch((err) => {
    console.warn("[viewer] command failed", err);
    return { ok: false, delivered: 0, error: String(err && err.message ? err.message : err) };
  });
}

// Click (not drag) selection of the nearest node vertex.
const raycaster = new THREE.Raycaster();
raycaster.params.Points.threshold = 1.5;
const pointer = new THREE.Vector2();
let downX = 0, downY = 0;

renderer.domElement.addEventListener("pointerdown", (e) => {
  downX = e.clientX; downY = e.clientY;
});
renderer.domElement.addEventListener("pointerup", (e) => {
  if (!editMode || transform.dragging) return;
  if (Math.abs(e.clientX - downX) > 5 || Math.abs(e.clientY - downY) > 5) return; // was a drag
  const rect = renderer.domElement.getBoundingClientRect();
  pointer.x = ((e.clientX - rect.left) / rect.width) * 2 - 1;
  pointer.y = -((e.clientY - rect.top) / rect.height) * 2 + 1;
  raycaster.setFromCamera(pointer, camera);
  const hits = raycaster.intersectObject(pickPoints, false);
  if (hits.length > 0 && hits[0].index !== undefined && pickIds[hits[0].index]) {
    selectNode(pickIds[hits[0].index]);
  } else {
    deselect();
  }
});

window.addEventListener("keydown", (e) => {
  if (e.key === "e" || e.key === "E") setEditMode(!editMode);
  else if (e.key === "Escape") deselect();
});

// --- SSE connection (with auto-reconnect via EventSource) ------------------
function connect() {
  setConn(false, "connecting...");
  const es = new EventSource("/events");
  es.onopen = () => setConn(true, "live");
  es.onmessage = (ev) => {
    let msg;
    const tp = PROFILE ? now() : 0;
    try { msg = JSON.parse(ev.data); } catch (_) { return; }
    const meta = PROFILE ? { parseMs: now() - tp, bytes: ev.data.length } : null;
    applyMessage(msg, meta);
  };
  es.onerror = () => {
    // EventSource reconnects automatically; reflect the gap in the HUD.
    setConn(false, "reconnecting...");
  };
}
connect();

// --- render loop -----------------------------------------------------------
let fpsFrames = 0, fpsT0 = now(), renderMsEma = 0;
let lastTick = now();
function tick() {
  requestAnimationFrame(tick);
  const tNow = now();
  const dt = Math.min(0.05, (tNow - lastTick) / 1000);
  lastTick = tNow;
  applyHeldCamera(dt);
  maybeSyncJosmView();
  if (needFrame) { frameAll(); needFrame = false; }
  controls.update();
  refreshCameraHud();
  const tr = PROFILE ? now() : 0;
  renderer.render(scene, camera);
  if (PROFILE) {
    renderMsEma = renderMsEma * 0.9 + (now() - tr) * 0.1;
    fpsFrames++;
    const t = now();
    if (t - fpsT0 >= 1000) {
      const fps = (fpsFrames * 1000) / (t - fpsT0);
      fpsFrames = 0; fpsT0 = t;
      const info = renderer.info.render;
      if (hud.render) {
        hud.render.textContent =
          `${fps.toFixed(0)} fps | ${renderMsEma.toFixed(2)}ms | ` +
          `${info.calls} draws | ${objects.size} objs`;
      }
    }
  }
}
tick();

window.addEventListener("resize", () => {
  camera.aspect = window.innerWidth / window.innerHeight;
  camera.updateProjectionMatrix();
  renderer.setSize(window.innerWidth, window.innerHeight);
});
