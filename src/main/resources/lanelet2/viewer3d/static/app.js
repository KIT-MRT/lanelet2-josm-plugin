// Lanelet2 live 3D viewer (browser side): wiring and the render loop.
//
// The modules under js/ do the work:
//   store.js      streamed map as plain data (features, tiles, node index)
//   render/*      batched lines, lanelet surfaces + arrows, icons, overlays
//   camera.js     heading/pitch camera, orbit, walk, framing
//   picking.js    screen-space picking over the store
//   nav.js        mouse navigation;  keys.js  keyboard, FPS look
//   edit.js       selection and the move gizmo;  net.js  SSE in, commands out
//   josmview.js   JOSM view follow / drive;  hud.js  HUD panels
//   imagery.js    "Street view" button (Mapillary / Google / Apple)
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
import { laneletLayer } from "./js/render/lanelets.js";
import { updateScreenSizedMarkers } from "./js/render/overlays.js";
import { updateHighlight } from "./js/render/highlight.js";
import { selection } from "./js/selection.js";
import { edit, gizmoBusy } from "./js/edit.js";
import { nav, navBusy, installNav } from "./js/nav.js";
import { installKeys, applyHeldCamera, isPointerLocked } from "./js/keys.js";
import { installJosmView, maybeSyncJosmView } from "./js/josmview.js";
import { connect, onCommandResult } from "./js/net.js";
import { installImagery } from "./js/imagery.js";
import { frameAll } from "./js/framing.js";
import { setConn, refreshCounts, refreshCameraHud, setPerfLine, setRenderLine } from "./js/hud.js";

// edit.js created TransformControls at import, so these listeners come after
// its pointerdown (see nav.js).
installNav({ gizmoBusy, pointerLocked: isPointerLocked });
installKeys();
installJosmView({ busy: () => gizmoBusy() || navBusy() });
installImagery();

// Only auto-frame the first time data appears; later snapshots (e.g. live
// edits streamed from JOSM) must not yank the camera the user has set. Framed
// right away rather than on the next frame, so a view chosen in between (B)
// is not overridden.
store.on("snapshot", ({ wasEmpty }) => { if (wasEmpty) frameAll(); });
store.on("applied", (msg) => { if (msg.type === "command_result") onCommandResult(msg); });

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
  lineLayer.update();
  laneletLayer.update();
  updateHighlight();
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
    // The one selected node as "node/<id>", else null (older checks use this).
    selected: () => {
      const one = selection.single();
      return one && one.type === "node" ? nodeToken(one.id) : null;
    },
    selection: () => ({ nodes: Array.from(selection.nodes).map(nodeToken), ways: Array.from(selection.ways) }),
    edit: () => ({ on: edit.on, tool: edit.tool, heightOnly: edit.heightOnly, keysMove: edit.keysMove }),
    node: (id) => {
      const rec = store.node(id);
      return rec ? [rec.x, rec.y, rec.z] : null;
    },
    toast: () => {
      const el = document.getElementById("toast");
      return el && el.classList.contains("show") ? { text: el.textContent, kind: el.className } : null;
    },
    lanelets: () => ({
      count: laneletLayer.lanelets.size,
      surfaceTiles: laneletLayer.chunks.size,
      oneWayArrows: laneletLayer.arrowMeshes[0] ? laneletLayer.arrowMeshes[0].count : 0,
      twoWayArrows: laneletLayer.arrowMeshes[1] ? laneletLayer.arrowMeshes[1].count : 0,
      visible: laneletLayer.visible,
    }),
    stats: () => ({
      features: store.featureCount(),
      tiles: store.tiles.size,
      chunks: lineLayer.chunks.size,
      drawCalls: renderer.info.render.calls,
    }),
  };
}
