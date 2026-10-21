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
import { pointLayer, setClassPalette, setHiddenClasses } from "../render/points.js";
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
    filterBtn: document.getElementById("sceneFilterBtn"),
    filters: document.getElementById("sceneFilters"),
    alpha: document.getElementById("pointAlpha"),
    aboveGround: document.getElementById("aboveGround"),
    aboveGroundLabel: document.getElementById("aboveGroundLabel"),
    intensityMin: document.getElementById("intensityMin"),
    intensityMinLabel: document.getElementById("intensityMinLabel"),
    chips: document.getElementById("classChips"),
    presetCars: document.getElementById("classPresetCars"),
    showAll: document.getElementById("classShowAll"),
  };
  if (!els.bar) return;
  installFilters();
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

// ---- filters -------------------------------------------------------------------
// Everything here is a shader uniform, so dragging a slider never refetches
// or re-uploads a point: filtered points are pushed outside the clip volume.
export const filterState = { hidden: new Set(), aboveGround: 0, intensityMin: 0, alpha: 1 };

// What "Vehicles" hides: the Vistas thing classes that are traffic.
const VEHICLE_CLASSES = [55, 57, 58, 59, 60, 61, 62, 63, 64, 32, 33];

function installFilters() {
  if (!els.filters) return;
  els.filterBtn.addEventListener("click", () => {
    els.filters.hidden = !els.filters.hidden;
    els.filterBtn.classList.toggle("on", !els.filters.hidden);
  });
  els.alpha.addEventListener("input", () => {
    filterState.alpha = Number(els.alpha.value) / 100;
    pointLayer.setAlpha(filterState.alpha);
    refreshFilterButton();
  });
  els.aboveGround.addEventListener("input", () => {
    filterState.aboveGround = Number(els.aboveGround.value);
    els.aboveGroundLabel.textContent = filterState.aboveGround
      ? `${filterState.aboveGround.toFixed(1)} m` : "off";
    pointLayer.setAboveGround(filterState.aboveGround);
    refreshFilterButton();
  });
  els.intensityMin.addEventListener("input", () => {
    filterState.intensityMin = Number(els.intensityMin.value) / 100;
    els.intensityMinLabel.textContent = filterState.intensityMin.toFixed(2);
    pointLayer.setIntensityRange(filterState.intensityMin, 1);
    refreshFilterButton();
  });
  els.presetCars.addEventListener("click", () => hideClasses(VEHICLE_CLASSES));
  els.showAll.addEventListener("click", () => hideClasses([]));
}

/** Hide exactly these class ids. */
export function hideClasses(ids) {
  filterState.hidden = new Set(ids);
  setHiddenClasses(filterState.hidden);
  for (const label of els.chips ? els.chips.querySelectorAll("label") : []) {
    const id = Number(label.dataset.classId);
    const box = label.querySelector("input");
    box.checked = filterState.hidden.has(id);
    label.classList.toggle("hidden", box.checked);
  }
  refreshFilterButton();
}

/** A chip per class actually present, commonest first. */
function refreshClassChips() {
  if (!els.chips) return;
  const histogram = pointLayer.classHistogram(true);
  if (!histogram) return;
  const present = Object.entries(histogram)
    .filter(([id]) => Number(id) !== 255)
    .sort((a, b) => b[1] - a[1])
    .slice(0, 14);
  if (!present.length) return;
  const have = new Set(Array.from(els.chips.children).map((n) => n.dataset.classId));
  for (const [id] of present) {
    if (have.has(id)) continue;
    const label = document.createElement("label");
    label.dataset.classId = id;
    label.title = `${className(Number(id))} (class ${id})`;
    const box = document.createElement("input");
    box.type = "checkbox";
    box.checked = filterState.hidden.has(Number(id));
    box.addEventListener("change", () => {
      if (box.checked) filterState.hidden.add(Number(id));
      else filterState.hidden.delete(Number(id));
      setHiddenClasses(filterState.hidden);
      label.classList.toggle("hidden", box.checked);
      refreshFilterButton();
    });
    label.appendChild(box);
    label.appendChild(document.createTextNode(className(Number(id))));
    label.classList.toggle("hidden", box.checked);
    els.chips.appendChild(label);
  }
}

function refreshFilterButton() {
  if (!els.filterBtn) return;
  const active = filterState.hidden.size + (filterState.aboveGround ? 1 : 0)
    + (filterState.intensityMin > 0 ? 1 : 0) + (filterState.alpha < 1 ? 1 : 0);
  els.filterBtn.textContent = active ? `Filter · ${active}` : "Filter";
  els.filterBtn.classList.toggle("on", active > 0 || !els.filters.hidden);
}

/**
 * A `scene` message from the server, or a scene picked on the city map
 * (`startFrame` then says which frame was clicked).
 */
export function onSceneMessage(msg, startFrame) {
  const info = msg.scene;
  if (!info || !info.base) return;
  // A reloaded tab gets the same announcement again; only another scene
  // throws away what this one has loaded.
  const other = sceneState.info && (sceneState.info.id !== info.id
    || sceneState.info.base !== info.base);
  if (other) resetScene();
  sceneState.info = info;
  if (startFrame !== undefined) sceneState.frame = startFrame;
  cache.clear();
  setSceneInfo(info);
  // Classes come with their own Vistas palette and names, so the browser has
  // nothing hard-coded to drift from the server's table.
  if (info.classes && info.classes.palette) setClassTable(info.classes);
  if (els.bar) {
    refreshBar();
    els.name.textContent = `${shortId(info.id)} · ${info.frames} frames`;
    els.name.title = `${info.id}\n${info.base}\n${info.lidars.length} LiDARs, ${Object.keys(info.cameras || {}).length} cameras`;
    els.frame.max = String(Math.max(0, info.frames - 1));
  }
  showFrame(Math.min(sceneState.frame, info.frames - 1));
  announce();
}

/** Tell the city layer (and anyone else) which scene is current. */
function announce() {
  window.dispatchEvent(new CustomEvent("ll2:scene", {
    detail: { id: sceneState.info ? sceneState.info.id : null, aggregate: aggregateState.on },
  }));
}

/** Drop everything the current scene has loaded, ready for another or none. */
function resetScene() {
  setPlaying(false);
  if (pending) {
    pending.controller.abort();
    pending = null;
  }
  wanted = null;
  clearTimeout(prefetchTimer);
  clearTimeout(aggregateTimer);
  cache.clear();
  if (aggregateState.on) toggleAggregate(false);
  pointLayer.clear();
  pointLayer.clearAggregate();
  Object.assign(aggregateState, { on: false, building: false, chunks: 0, loaded: 0, points: 0,
                                  error: null, stats: null, framesWere: undefined });
  refreshAggregateButton();
  sceneState.loading = false;
  sceneState.error = null;
  sceneState.frame = 0;
}

/** No current scene any more: the city map is all that is left. */
export function clearScene() {
  if (!sceneState.info) return;
  resetScene();
  sceneState.info = null;
  setSceneInfo(null);  // releases the camera lock
  refreshBar();
  announce();
}

// In city mode the colour, size and filter controls act on the city's clouds
// too, so the bar stays up without a scene; only what needs one is hidden.
let cityActive = false;

export function setCityActive(on) {
  cityActive = !!on;
  refreshBar();
}

function refreshBar() {
  if (!els.bar) return;
  els.bar.hidden = !(sceneState.info || cityActive);
  els.bar.classList.toggle("noScene", !sceneState.info);
  if (!sceneState.info) els.name.textContent = "no scene · click a trajectory";
}

// The Vistas table: from the scene, or from the city before any scene is open.
let classTable = null;

/** Install class colours and names: `classes` is { palette, names, ... }. */
export function setClassTable(classes) {
  if (!classes || !classes.palette) return;
  classTable = classes;
  setClassPalette(classes.palette);
  addClassOption(classes);
}

/** After points arrived from elsewhere (the city): offer what they carry. */
export function refreshPointUi() {
  refreshColourOptions();
  refreshClassChips();
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
  addColourOption("class", "Mapillary Vistas classes from the seamseg masks of the "
    + "front and rear cameras");
}

/** The camera colour only shows up once a cloud with it has been decoded. */
function refreshColourOptions() {
  if (pointLayer.hasRgb()) {
    addColourOption("rgb", "Colour sampled from the ring camera images");
  }
  if (pointLayer.hasInstances()) {
    addColourOption("instance", "A colour per detected object in this frame. "
      + "Nothing tracks an object between frames, so the numbers are good only "
      + "within one frame and the aggregate keeps its intensity colouring.");
  }
}

/** Name of a Vistas class id, for the HUD and the tests. */
export function className(id) {
  const info = sceneState.info;
  const names = (info && info.classes && info.classes.names) || (classTable && classTable.names);
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
  announce();
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
      if (sceneState.info !== info) return null;  // another scene since
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
        if (buf && sceneState.info === info) {
          aggregateState.points += pointLayer.addAggregateChunk(buf);
          refreshColourOptions();
          refreshClassChips();
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
    refreshClassChips();
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
    filters: () => ({ ...filterState, hidden: Array.from(filterState.hidden).sort((a, b) => a - b) }),
    hideClasses: (ids) => hideClasses(ids),
    classes: () => {
      const info = sceneState.info;
      return {
        available: Boolean(info && info.classes),
        cameras: (info && info.classes && info.classes.cameras) || [],
        inData: pointLayer.hasClasses(),
        rgb: pointLayer.hasRgb(),
        instances: pointLayer.hasInstances() ? pointLayer.instanceCount() : 0,
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
