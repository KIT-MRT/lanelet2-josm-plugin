// 3D editing: move JOSM nodes from the browser.
//
// Way features carry a `nodes` array parallel to their vertices. A vertex can
// be dragged in 3D and the change posted back to the JOSM bridge as a
// `move_node` command. JOSM applies it (undoable) and echoes the
// authoritative geometry back through the normal snapshot/patch path.
import { TransformControls } from "three/addons/controls/TransformControls.js";
import { Emitter, round3, now, plog, PROFILE } from "./util.js";
import { canvas, scene } from "./scene.js";
import { camera } from "./camera.js";
import { store, nodeToken } from "./store.js";
import { lineLayer } from "./render/lines.js";
import { selectMarker } from "./render/overlays.js";
import { pickNode } from "./picking.js";
import { sendCommand } from "./net.js";
import { setEditHud } from "./hud.js";

const GIZMO_CLICK_NODE_PX = 4; // a click on a gizmo handle selects only a node dot this close
const CLICK_SLOP_PX = 5;

export const editEvents = new Emitter(); // "mode" (on)
export const edit = { on: false, selected: null };
let dragMoved = false; // true once a gizmo drag actually changed the position

export const transform = new TransformControls(camera, canvas);
transform.setSize(0.9);
transform.addEventListener("mouseDown", () => { dragMoved = false; });
transform.addEventListener("objectChange", onGizmoChange);
transform.addEventListener("mouseUp", commitMove);
scene.add(transform);

export const gizmoBusy = () => transform.dragging;

export function setEditMode(on) {
  edit.on = !!on;
  if (edit.on) store.ensureNodeIndex();
  lineLayer.setDotsVisible(edit.on);
  if (!edit.on) deselect();
  refreshEditHud();
  editEvents.emit("mode", edit.on);
}

function refreshEditHud() {
  if (!edit.on) setEditHud("off");
  else setEditHud(edit.selected !== null ? `ON (${nodeToken(edit.selected)})` : "ON");
}

export function selectNode(id) {
  const rec = store.node(id);
  if (!rec) return;
  edit.selected = id;
  selectMarker.position.set(rec.x, rec.y, rec.z);
  selectMarker.visible = true;
  transform.attach(selectMarker);
  refreshEditHud();
}

export function deselect() {
  edit.selected = null;
  transform.detach();
  selectMarker.visible = false;
  refreshEditHud();
}

// Keep the selection pinned to authoritative geometry (unless mid-drag).
store.on("applied", () => {
  if (edit.selected === null || transform.dragging) return;
  const rec = store.node(edit.selected);
  if (rec) selectMarker.position.set(rec.x, rec.y, rec.z);
  else deselect();
});

// Drag in progress: move the node locally so connected ways follow live.
let lastGizmoLog = 0;
function onGizmoChange() {
  if (edit.selected === null) return;
  dragMoved = true;
  const tg = PROFILE ? now() : 0;
  const p = selectMarker.position;
  const recs = store.moveNodes([{ id: edit.selected, x: p.x, y: p.y, z: p.z }]);
  if (PROFILE) {
    const t = now();
    if (t - lastGizmoLog > 250) { // drag fires per frame; throttle the log
      lastGizmoLog = t;
      plog(`gizmo node=${edit.selected} ways=${recs.length ? recs[0].refs.length : 0} ms=${(t - tg).toFixed(2)}`);
    }
  }
}

// Drag finished: tell JOSM to apply the move (undoable, echoed back).
function commitMove() {
  if (edit.selected === null || !dragMoved) return;
  dragMoved = false;
  const rec = store.node(edit.selected);
  if (!rec) return;
  sendCommand([{ op: "move_node", id: nodeToken(rec.id), x: round3(rec.x), y: round3(rec.y), z: round3(rec.z) }]);
}

// --- click selection ---------------------------------------------------------------
// Registered right after TransformControls, whose own pointerdown therefore
// runs first and sets `dragging` when the press hits a handle. Its pointerup
// clears that again before ours runs, so remember it at press time.
let downX = 0, downY = 0;
let downOnGizmo = false;

canvas.addEventListener("pointerdown", (e) => {
  downX = e.clientX;
  downY = e.clientY;
  downOnGizmo = transform.dragging;
});

canvas.addEventListener("pointerup", (e) => {
  if (!edit.on || transform.dragging || e.button !== 0) return;
  if (Math.abs(e.clientX - downX) > CLICK_SLOP_PX || Math.abs(e.clientY - downY) > CLICK_SLOP_PX) return;
  if (downOnGizmo) {
    // A click on a handle keeps the selection, rather than selecting whichever
    // node lies near the arrow. Only a click squarely on another node's dot
    // (which the arrow may cross) selects that node.
    const id = pickNode(e.clientX, e.clientY, GIZMO_CLICK_NODE_PX);
    if (id !== null && id !== edit.selected) selectNode(id);
    return;
  }
  const id = pickNode(e.clientX, e.clientY);
  if (id !== null) selectNode(id);
  else deselect();
});
