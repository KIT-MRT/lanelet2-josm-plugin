// Recorded sensor scenes (KITScenes) next to the map.
//
// The scene server announces itself with one `scene` message over the same
// SSE stream the map arrives on; everything heavy is then fetched straight
// from it as binary, because a frame is ~750 k points and has no business
// going through JSON. The viewer server only relays the announcement, so it
// keeps knowing nothing about sensors.
import * as THREE from "three";
import { TEST_HOOKS } from "../util.js";
import { camera, view, setCameraView, lookAtPoint } from "../camera.js";
import { pointLayer, setClassPalette } from "../render/points.js";
import { toast } from "../hud.js";
import { store } from "../store.js";
import { installCamRig, setSceneInfo, update as updateRig, rig, rigState, handleRigKey,
         projectToImage } from "./camrig.js";

/** The whole-scene aggregate: built on the server, streamed in chunks. */
export const aggregateState = {
  on: false, building: false, chunks: 0, loaded: 0, points: 0, error: null, stats: null,
  framesWere: undefined,
};

export const sceneState = {
  info: null,      // the scene message's payload
  frame: 0,
  loading: false,
  playing: false,
  error: null,
};

const PLAY_MIN_MS = 60;       // never queue faster than the fetches come back
const PREFETCH_IDLE_MS = 400; // only look ahead once the user has settled
let els = {};
let playTimer = null;
let prefetchTimer = null;
// Exactly one request is ever in flight. Decoding a frame costs the server
// seconds of CPU, so a held step key used to put eight decodes on it at once,
// which made every one of them -- including the frame actually being waited
// for -- about six times slower. Now the newest wanted frame wins: whatever
// is in flight finishes, then the latest wanted frame is fetched, so the
// frames stepped over are never asked for at all.
let pending = null;  // { index, controller, show }
let wanted = null;   // the frame the user last asked for
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
    aggregate: document.getElementById("sceneAggregate"),
    tint: document.getElementById("sceneTint"),
  };
  if (!els.bar) return;
  els.aggregate.addEventListener("click", () => toggleAggregate());
  els.tint.addEventListener("click", () => toggleTint());
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
  // Classes come with their own Vistas palette and names, so the browser has
  // nothing hard-coded to drift from the server's table.
  if (info.classes && info.classes.palette) {
    setClassPalette(info.classes.palette);
    addClassOption(info.classes);
  }
  if (els.bar) {
    els.bar.hidden = false;
    els.name.textContent = `${shortId(info.id)} · ${info.frames} frames`;
    els.name.title = `${info.id}\n${info.base}\n${info.lidars.length} LiDARs, ${Object.keys(info.cameras || {}).length} cameras`;
    els.frame.max = String(Math.max(0, info.frames - 1));
  }
  showFrame(Math.min(sceneState.frame, info.frames - 1));
}

const shortId = (id) => (id && id.length > 12 ? `${id.slice(0, 8)}…` : id || "scene");

/** Add a colour mode to the menu once the data turns out to carry it. */
function addColourOption(value, title) {
  if (!els.mode || els.mode.querySelector(`option[value="${value}"]`)) return;
  const option = document.createElement("option");
  option.value = value;
  option.textContent = value;
  option.title = title;
  els.mode.appendChild(option);
}

function addClassOption(classes) {
  addColourOption("class", `Mapillary Vistas classes from the `
    + `${(classes.cameras || []).length} segmented cameras`);
}

/** The camera colour only shows up once a cloud with it has been decoded. */
function refreshColourOptions() {
  if (pointLayer.hasRgb()) {
    addColourOption("rgb", "Colour sampled from the ring camera images");
  }
}

/** Name of a Vistas class id, for the HUD and the tests. */
export function className(id) {
  const info = sceneState.info;
  const names = info && info.classes && info.classes.names;
  if (id === undefined || id === null) return null;
  if (names && names[String(id)]) return names[String(id)];
  return id === 255 ? "unlabelled" : `class ${id}`;
}

export function frameUrl(index) {
  return `${sceneState.info.base}/frame/${index}/points.bin`;
}

// ---- the whole-scene aggregate ------------------------------------------------
// Building it means decoding every frame, so the server does it once in the
// background and caches it; until it is ready the meta route answers 202 with
// progress, which the button shows.
const AGGREGATE_POLL_MS = 1000;
let aggregateTimer = null;

export function toggleAggregate(on) {
  const next = on === undefined ? !aggregateState.on : !!on;
  if (next === aggregateState.on) return;
  aggregateState.on = next;
  pointLayer.setAggregateVisible(next);
  // The point of the aggregate is to look at the scene without one frame's
  // clutter on top, so showing it puts the frame away -- P brings it back,
  // tinted, when the two are worth comparing. Turning the aggregate off
  // restores whatever the frame was doing before.
  if (next) {
    aggregateState.framesWere = pointLayer.visible;
    togglePoints(false);
  } else if (aggregateState.framesWere !== undefined) {
    togglePoints(aggregateState.framesWere);
    aggregateState.framesWere = undefined;
  }
  refreshAggregateButton();
  if (next && !aggregateState.loaded && !aggregateState.building) loadAggregate();
}

/** Paint the single frame one bright colour while the aggregate is shown. */
export function toggleTint(on) {
  const next = on === undefined ? !pointLayer.tintFrame : !!on;
  pointLayer.setTintFrame(next);
  if (els.tint) els.tint.classList.toggle("on", next);
}

function loadAggregate() {
  const info = sceneState.info;
  if (!info) return Promise.resolve(null);
  aggregateState.building = true;
  aggregateState.error = null;
  refreshAggregateButton();
  return fetch(`${info.base}/aggregate/meta.json`)
    .then((r) => r.json().then((meta) => ({ status: r.status, meta })))
    .then(({ status, meta }) => {
      if (meta.error) throw new Error(meta.error);
      if (status === 202 || !meta.ready) {
        aggregateState.stats = meta.progress || null;
        refreshAggregateButton();
        clearTimeout(aggregateTimer);
        aggregateTimer = setTimeout(loadAggregate, AGGREGATE_POLL_MS);
        return null;
      }
      aggregateState.stats = meta.stats || null;
      aggregateState.chunks = meta.chunks.length;
      aggregateState.loaded = 0;
      aggregateState.points = 0;
      pointLayer.clearAggregate();
      return fetchChunks(meta.chunks);
    })
    .catch((err) => {
      aggregateState.building = false;
      aggregateState.error = String(err.message || err);
      toast(`Whole scene: ${aggregateState.error}`, "error");
      refreshAggregateButton();
      return null;
    });
}

/** One chunk at a time, so a frame the user asks for is never stuck behind them. */
function fetchChunks(chunks) {
  const info = sceneState.info;
  const next = (i) => {
    if (i >= chunks.length || !sceneState.info || sceneState.info !== info) {
      aggregateState.building = false;
      refreshAggregateButton();
      return Promise.resolve(null);
    }
    return fetch(`${info.base}/aggregate/${chunks[i].index}.bin`)
      .then((r) => (r.ok ? r.arrayBuffer() : null))
      .then((buf) => {
        if (buf) {
          aggregateState.points += pointLayer.addAggregateChunk(buf);
          refreshColourOptions();
          aggregateState.loaded++;
          refreshAggregateButton();
        }
        return next(i + 1);
      });
  };
  return next(0);
}

const countLabel = (n) => (n >= 1e6 ? `${(n / 1e6).toFixed(1)}M`
  : n >= 1e3 ? `${Math.round(n / 1e3)}k` : String(n));

function refreshAggregateButton() {
  if (!els.aggregate) return;
  els.aggregate.classList.toggle("on", aggregateState.on);
  const s = aggregateState;
  let label = "Whole scene";
  if (s.error) label = "Whole scene ✕";
  else if (s.building && s.chunks) label = `Whole scene ${s.loaded}/${s.chunks}`;
  else if (s.building) {
    const p = s.stats || {};
    label = p.total ? `Building ${p.done || 0}/${p.total}` : "Building…";
  } else if (s.points) label = `Whole scene ${countLabel(s.points)}`;
  els.aggregate.textContent = label;
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
  wanted = index;
  updateLabels();
  // The locked view rides along with the frame, image and all.
  if (rig.on) updateRig(index);
  const cached = cache.get(index);
  if (cached) {
    apply(index, cached);
    return Promise.resolve(cached);
  }
  if (pending) {
    if (pending.index === index) return pending.promise;
    // A look-ahead is worth dropping for a frame the user is waiting for; a
    // request already being decoded is not -- aborting it would send one
    // request per keypress anyway, and the server has started the work. Let
    // it finish, and its `finally` jumps straight to whatever is wanted by
    // then, so the frames stepped over are never asked for.
    if (!pending.show) {
      pending.controller.abort();
      return startFetch(index, true);
    }
    sceneState.loading = true;
    return pending.promise;
  }
  return startFetch(index, true);
}

/** Fetch one frame. `show` distinguishes a user step from a look-ahead. */
function startFetch(index, show) {
  const controller = new AbortController();
  if (show) {
    sceneState.loading = true;
    updateLabels();
  }
  const promise = fetch(frameUrl(index), { signal: controller.signal })
    .then((r) => {
      if (!r.ok) throw new Error(`frame ${index}: HTTP ${r.status}`);
      return r.arrayBuffer();
    })
    .then((buf) => {
      remember(index, buf);
      sceneState.error = null;
      if (show && sceneState.frame === index) apply(index, buf);
      return buf;
    })
    .catch((err) => {
      if (err && err.name === "AbortError") return null; // we moved on; normal
      if (show) {
        sceneState.error = String(err.message || err);
        setPlaying(false);
        toast(`Point cloud: ${sceneState.error}`, "error");
      }
      return null;
    })
    .finally(() => {
      // An aborted look-ahead settles *after* its replacement has taken the
      // slot; clearing it blindly would let two requests run at once.
      if (!pending || pending.controller !== controller) return;
      pending = null;
      sceneState.loading = false;
      if (wanted !== null && wanted !== index && !cache.has(wanted)) {
        startFetch(wanted, true);
        return;
      }
      if (wanted !== null && cache.has(wanted) && sceneState.frame === wanted) {
        apply(wanted, cache.get(wanted));
      }
      updateLabels();
      schedulePrefetch();
    });
  pending = { index, controller, show, promise };
  return promise;
}

function apply(index, buffer) {
  try {
    pointLayer.setCloud(buffer);
    refreshColourOptions();
  } catch (err) {
    sceneState.error = String(err.message || err);
    toast(`Point cloud: ${sceneState.error}`, "error");
  }
  updateLabels();
  schedulePrefetch();
}

function remember(index, buffer) {
  cache.set(index, buffer);
  while (cache.size > CACHE_MAX) cache.delete(cache.keys().next().value);
}

/** Look one frame ahead, but only once nothing else is being waited for. */
function schedulePrefetch() {
  clearTimeout(prefetchTimer);
  prefetchTimer = setTimeout(() => {
    const info = sceneState.info;
    if (!info || pending) return;
    const next = sceneState.frame + 1;
    if (next < info.frames && !cache.has(next)) startFetch(next, false);
  }, PREFETCH_IDLE_MS);
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
  // The ego frame is x forward, y left, z up.
  const forward = new THREE.Vector3(1, 0, 0)
    .applyQuaternion(new THREE.Quaternion(quat[0], quat[1], quat[2], quat[3]));
  const horiz = Math.hypot(forward.x, forward.y) || 1;
  const car = new THREE.Vector3(pos[0], pos[1], pos[2]);
  const back = 14;
  const up = 7;
  // Stand behind and above the car, then aim at it. Pointing the camera with
  // `lookAtPoint` keeps this independent of the yaw convention, which is
  // atan2(-x, y) here and easy to get backwards.
  setCameraView(new THREE.Vector3(
    car.x - (forward.x / horiz) * back,
    car.y - (forward.y / horiz) * back,
    car.z + up), view.yaw, view.pitch);
  lookAtPoint(car);
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
  // Shift+P before P: `event.key` is already "P" when shift is held.
  if ((event.key === "p" || event.key === "P") && event.shiftKey) {
    toggleAggregate();
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
  if (event.key === "u" || event.key === "U") {
    toggleTint();
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
    aggregate: () => ({ ...aggregateState }),
    classes: () => {
      const info = sceneState.info;
      return {
        available: Boolean(info && info.classes),
        cameras: (info && info.classes && info.classes.cameras) || [],
        inData: pointLayer.hasClasses(),
        rgb: pointLayer.hasRgb(),
        car: className(58),
        histogram: pointLayer.classHistogram(),
      };
    },
    showAggregate: (on) => toggleAggregate(on),
    project: (world) => projectToImage(world),
    show: (i) => showFrame(i),
    ego: (i) => (sceneState.info && sceneState.info.ego ? sceneState.info.ego.pos[i] : null),
  };
}
