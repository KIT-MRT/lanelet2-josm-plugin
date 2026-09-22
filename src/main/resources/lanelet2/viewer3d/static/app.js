// Lanelet2 live 3D viewer (browser side): wiring and the render loop.
//
// The modules under js/ do the work:
//   store.js      streamed map as plain data (features, tiles, node index)
//   render/*      batched lines, icons, overlays drawn from the store
//   camera.js     heading/pitch camera, orbit, walk, framing
//   picking.js    screen-space picking over the store
//   nav.js        mouse navigation;  keys.js  keyboard, FPS look
//   edit.js       selection and the move gizmo;  net.js  SSE in, commands out
//   josmview.js   JOSM view follow / drive;  hud.js  HUD panels
//
// Coordinates arrive as local ENU metres relative to an anchor lat/lon, so the
// scene is centred near the origin. Z is up.
import * as THREE from "three";
import { PROFILE, TEST_HOOKS, now, plog } from "./js/util.js";
import { renderer, scene } from "./js/scene.js";
import { camera, view } from "./js/camera.js";
import { store, nodeToken } from "./js/store.js";
import { lineLayer } from "./js/render/lines.js";
import { iconLayer } from "./js/render/icons.js";
import { updateScreenSizedMarkers } from "./js/render/overlays.js";
import { edit, gizmoBusy } from "./js/edit.js";
import { nav, navBusy, installNav } from "./js/nav.js";
import { installKeys, applyHeldCamera, isPointerLocked } from "./js/keys.js";
import { installJosmView, maybeSyncJosmView } from "./js/josmview.js";
import { connect } from "./js/net.js";
import { frameAll } from "./js/framing.js";
import { setConn, refreshCounts, refreshCameraHud, setPerfLine, setRenderLine } from "./js/hud.js";

// edit.js created TransformControls at import, so these listeners come after
// its pointerdown (see nav.js).
installNav({ gizmoBusy, pointerLocked: isPointerLocked });
installKeys();
installJosmView({ busy: () => gizmoBusy() || navBusy() });

// Only auto-frame the first time data appears; later snapshots (e.g. live
// edits streamed from JOSM) must not yank the camera the user has set.
let needFrame = false;
store.on("snapshot", ({ wasEmpty }) => { if (wasEmpty) needFrame = true; });

connect((msg, meta) => {
  const t0 = PROFILE ? now() : 0;
  const info = store.applyMessage(msg);
  if (!info.applied) return; // stale / out-of-order patch
  refreshCounts(store.featureCount(), msg.seq, store.anchor);
  if (PROFILE) {
    const parseMs = meta ? meta.parseMs : 0;
    const line = `${msg.type} ops=${info.nOps} feats=${store.featureCount()} tiles=${store.tiles.size}` +
      ` nodes=${store.nodeIndexOn ? store.nodes.size : "-"} bytes=${meta ? meta.bytes : 0}` +
      ` parse=${parseMs.toFixed(1)} apply=${(now() - t0).toFixed(1)}ms`;
    plog(line);
    setPerfLine(line);
  }
}, setConn);

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
  lineLayer.update();
  iconLayer.update(camera);
  updateScreenSizedMarkers();
  refreshCameraHud(nav);
  const tr = PROFILE ? now() : 0;
  renderer.render(scene, camera);
  if (PROFILE) {
    renderMsEma = renderMsEma * 0.9 + (now() - tr) * 0.1;
    fpsFrames++;
    const t = now();
    if (t - fpsT0 >= 1000) {
      const fps = (fpsFrames * 1000) / (t - fpsT0);
      fpsFrames = 0;
      fpsT0 = t;
      const info = renderer.info.render;
      setRenderLine(`${fps.toFixed(0)} fps | ${renderMsEma.toFixed(2)}ms | ` +
        `${info.calls} draws | ${store.featureCount()} feats | ${lineLayer.chunks.size} chunks`);
    }
  }
}
tick();

window.addEventListener("resize", () => {
  camera.aspect = window.innerWidth / window.innerHeight;
  camera.updateProjectionMatrix();
  renderer.setSize(window.innerWidth, window.innerHeight);
});

if (TEST_HOOKS) {
  const _tp = new THREE.Vector3();
  window.__ll2test = {
    // Page pixel of a world point, as the renderer draws it; [x, y, ndcZ].
    project(x, y, z) {
      camera.updateMatrixWorld();
      _tp.set(x, y, z).project(camera);
      const r = renderer.domElement.getBoundingClientRect();
      return [r.left + ((_tp.x + 1) / 2) * r.width, r.top + ((1 - _tp.y) / 2) * r.height, _tp.z];
    },
    camera: () => ({ pos: camera.position.toArray(), yaw: view.yaw, pitch: view.pitch, fov: camera.fov }),
    pivot: () => ({ point: nav.lastPivot ? nav.lastPivot.toArray() : null, kind: nav.lastPivotKind }),
    selected: () => (edit.selected === null ? null : nodeToken(edit.selected)),
    stats: () => ({
      features: store.featureCount(),
      tiles: store.tiles.size,
      chunks: lineLayer.chunks.size,
      drawCalls: renderer.info.render.calls,
    }),
  };
}
