// Recorded sensor scenes (KITScenes) next to the map.
//
// The scene server announces itself with one `scene` message over the same
// SSE stream the map arrives on; everything heavy is then fetched straight
// from it as binary, because a frame is ~750 k points and has no business
// going through JSON. The viewer server only relays the announcement, so it
// keeps knowing nothing about sensors.
import * as THREE from "three";
import { TEST_HOOKS } from "../util.js";
import { camera, setCameraView, clampPitch } from "../camera.js";
import { pointLayer } from "../render/points.js";
import { toast } from "../hud.js";
import { store } from "../store.js";
import { installCamRig, setSceneInfo, update as updateRig, rig, rigState, handleRigKey,
         projectToImage } from "./camrig.js";

export const sceneState = {
  info: null,      // the scene message's payload
  frame: 0,
  loading: false,
  playing: false,
  error: null,
};

const PLAY_MIN_MS = 60; // never queue faster than the fetches come back
let els = {};
let playTimer = null;
let inFlight = null;
const cache = new Map(); // frame -> ArrayBuffer
const CACHE_MAX = 6;

/** Wire the scene bar. Safe to call when the page has no scene (yet). */
export function installScene() {
  els = {
    bar: document.getElementById("sceneBar"),
    name: document.getElementById("sceneName"),
    points: document.getElementById("scenePoints"),
    car: document.getElementById("sceneCar"),
    prev: document.getElementById("scenePrev"),
    play: document.getElementById("scenePlay"),
    next: document.getElementById("sceneNext"),
    frame: document.getElementById("sceneFrame"),
    label: document.getElementById("sceneFrameLabel"),
    mode: document.getElementById("sceneMode"),
    size: document.getElementById("sceneSize"),
  };
  if (!els.bar) return;
  els.points.classList.add("on");
  els.points.addEventListener("click", () => togglePoints());
  els.car.addEventListener("click", () => goToCar());
  els.prev.addEventListener("click", () => step(-1));
  els.next.addEventListener("click", () => step(1));
  els.play.addEventListener("click", () => setPlaying(!sceneState.playing));
  els.frame.addEventListener("input", () => showFrame(Number(els.frame.value)));
  els.mode.addEventListener("change", () => pointLayer.setMode(els.mode.value));
  els.size.addEventListener("input", () => pointLayer.setSize(Number(els.size.value)));
  pointLayer.setSize(Number(els.size.value));
  installCamRig({ urlFor: imageUrl, currentFrame: () => sceneState.frame });
}

/** A `scene` message from the server. */
export function onSceneMessage(msg) {
  const info = msg.scene;
  if (!info || !info.base) return;
  sceneState.info = info;
  cache.clear();
  setSceneInfo(info);
  if (els.bar) {
    els.bar.hidden = false;
    els.name.textContent = `${shortId(info.id)} · ${info.frames} frames`;
    els.name.title = `${info.id}\n${info.base}\n${info.lidars.length} LiDARs, ${Object.keys(info.cameras || {}).length} cameras`;
    els.frame.max = String(Math.max(0, info.frames - 1));
  }
  showFrame(Math.min(sceneState.frame, info.frames - 1));
}

const shortId = (id) => (id && id.length > 12 ? `${id.slice(0, 8)}…` : id || "scene");

export function frameUrl(index) {
  return `${sceneState.info.base}/frame/${index}/points.bin`;
}

/** Where a recorded camera frame lives; the camera lock blends it over the render. */
export function imageUrl(cameraName, index) {
  return `${sceneState.info.base}/frame/${index}/camera/${cameraName}.jpg`;
}

/** Load and show one frame; returns a promise for the tests. */
export function showFrame(index) {
  const info = sceneState.info;
  if (!info) return Promise.resolve(null);
  index = Math.max(0, Math.min(info.frames - 1, Math.round(index)));
  sceneState.frame = index;
  updateLabels();
  // The locked view rides along with the frame, image and all.
  if (rig.on) updateRig(index);
  const cached = cache.get(index);
  if (cached) {
    apply(index, cached);
    return Promise.resolve(cached);
  }
  sceneState.loading = true;
  updateLabels();
  const request = fetch(frameUrl(index))
    .then((r) => {
      if (!r.ok) throw new Error(`frame ${index}: HTTP ${r.status}`);
      return r.arrayBuffer();
    })
    .then((buf) => {
      remember(index, buf);
      sceneState.loading = false;
      sceneState.error = null;
      // A later click may have moved on while this was in flight.
      if (sceneState.frame === index) apply(index, buf);
      return buf;
    })
    .catch((err) => {
      sceneState.loading = false;
      sceneState.error = String(err.message || err);
      setPlaying(false);
      toast(`Point cloud: ${sceneState.error}`, "error");
      updateLabels();
      return null;
    });
  inFlight = request;
  return request;
}

function apply(index, buffer) {
  try {
    pointLayer.setCloud(buffer);
  } catch (err) {
    sceneState.error = String(err.message || err);
    toast(`Point cloud: ${sceneState.error}`, "error");
  }
  updateLabels();
  prefetch(index + 1);
}

function remember(index, buffer) {
  cache.set(index, buffer);
  while (cache.size > CACHE_MAX) cache.delete(cache.keys().next().value);
}

function prefetch(index) {
  const info = sceneState.info;
  if (!info || index < 0 || index >= info.frames || cache.has(index)) return;
  fetch(frameUrl(index))
    .then((r) => (r.ok ? r.arrayBuffer() : null))
    .then((buf) => { if (buf) remember(index, buf); })
    .catch(() => {});
}

export function step(delta) {
  if (!sceneState.info) return;
  showFrame(sceneState.frame + delta);
}

export function setPlaying(on) {
  sceneState.playing = !!on && !!sceneState.info;
  if (els.play) {
    els.play.textContent = sceneState.playing ? "❚❚" : "▶";
    els.play.classList.toggle("on", sceneState.playing);
  }
  clearTimeout(playTimer);
  if (sceneState.playing) queueNext();
}

function queueNext() {
  playTimer = setTimeout(() => {
    if (!sceneState.playing) return;
    const info = sceneState.info;
    const next = sceneState.frame + 1;
    if (next >= info.frames) {
      setPlaying(false);
      return;
    }
    const done = showFrame(next);
    Promise.resolve(done).then(() => { if (sceneState.playing) queueNext(); });
  }, PLAY_MIN_MS);
}

export function togglePoints(on) {
  const next = on === undefined ? !pointLayer.visible : !!on;
  pointLayer.setVisible(next);
  if (els.points) els.points.classList.toggle("on", next);
}

/** Camera behind and above the car at the current frame, looking where it drives. */
export function goToCar() {
  const info = sceneState.info;
  if (!info || !info.ego || !info.ego.pos[sceneState.frame]) return false;
  const pos = info.ego.pos[sceneState.frame];
  const quat = info.ego.quat[sceneState.frame];
  const forward = new THREE.Vector3(1, 0, 0)
    .applyQuaternion(new THREE.Quaternion(quat[0], quat[1], quat[2], quat[3]));
  const yaw = Math.atan2(forward.y, forward.x);
  const back = 14;
  const up = 7;
  setCameraView(
    new THREE.Vector3(pos[0] - Math.cos(yaw) * back, pos[1] - Math.sin(yaw) * back, pos[2] + up),
    yaw, clampPitch(-0.33));
  return true;
}

function updateLabels() {
  const info = sceneState.info;
  if (!els.label || !info) return;
  const time = info.times && info.times[sceneState.frame];
  const suffix = sceneState.loading ? " …" : "";
  els.label.textContent = `${sceneState.frame} / ${info.frames - 1}`
    + (time === undefined ? "" : `  ${time.toFixed(1)}s`) + suffix;
  els.frame.value = String(sceneState.frame);
}

/** Scene keys; returns true when the event was consumed. */
export function handleSceneKey(event) {
  if (!sceneState.info) return false;
  if (event.key === ",") {
    step(-1);
    return true;
  }
  if (event.key === ".") {
    step(1);
    return true;
  }
  if (event.key === "p" || event.key === "P") {
    togglePoints();
    return true;
  }
  if (event.key === "k" || event.key === "K") {
    goToCar();
    return true;
  }
  return handleRigKey(event);
}

/**
 * Height of the loaded cloud and of the map around a point, for the live
 * alignment check (`testdata/viewer3d/live_scene.mjs`). The cloud's 10th
 * percentile is the road surface: most returns near the car are ground, and
 * the percentile ignores the few that come off the bonnet.
 */
function heightsNear(xy, radius) {
  const out = { ground: { count: 0, p10: null }, map: { count: 0, median: null } };
  const cloud = pointLayer.cloud;
  if (cloud) {
    const zs = [];
    const { positions, origin, count } = cloud;
    for (let i = 0; i < count; i++) {
      const dx = origin[0] + positions[i * 3] - xy[0];
      const dy = origin[1] + positions[i * 3 + 1] - xy[1];
      if (dx * dx + dy * dy <= radius * radius) zs.push(origin[2] + positions[i * 3 + 2]);
    }
    zs.sort((a, b) => a - b);
    out.ground = { count: zs.length, p10: zs.length ? zs[Math.floor(zs.length * 0.1)] : null };
  }
  const zs = [];
  for (const f of store.features.values()) {
    if (f.kind !== "line") continue;
    for (let i = 0; i < f.pos.length; i += 3) {
      const dx = f.pos[i] - xy[0];
      const dy = f.pos[i + 1] - xy[1];
      if (dx * dx + dy * dy <= radius * radius) zs.push(f.pos[i + 2]);
    }
  }
  zs.sort((a, b) => a - b);
  out.map = { count: zs.length, median: zs.length ? zs[Math.floor(zs.length / 2)] : null };
  return out;
}

if (TEST_HOOKS) {
  window.__ll2sceneDebug = {
    cloudZNear: (xy, r) => heightsNear(xy, r).ground,
    mapZNear: (xy, r) => heightsNear(xy, r).map,
  };
  window.__ll2scene = {
    state: () => ({ frame: sceneState.frame, frames: sceneState.info ? sceneState.info.frames : 0,
                    loading: sceneState.loading, playing: sceneState.playing, error: sceneState.error,
                    base: sceneState.info ? sceneState.info.base : null }),
    points: () => pointLayer.stats(),
    rig: () => rigState(),
    project: (world) => projectToImage(world),
    show: (i) => showFrame(i),
    ego: (i) => (sceneState.info && sceneState.info.ego ? sceneState.info.ego.pos[i] : null),
  };
}
