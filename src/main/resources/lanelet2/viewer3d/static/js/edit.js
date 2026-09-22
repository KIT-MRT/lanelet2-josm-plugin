// 3D editing: select nodes and ways, move / rotate them, delete, undo.
//
// Moves happen locally while dragging (every way using a node follows), then
// go to JOSM as one command = one undo step. JOSM applies it and echoes the
// authoritative geometry back through the normal patch path; if it refuses
// (layer hidden, node gone), the viewer puts the nodes back and says why.
//
//   click          select the node under the cursor, else the way
//   shift+click    add / remove it
//   ctrl+drag      box select (shift: add)
//   middle-click   cycle through everything under the cursor (coincident nodes)
//   G / R / H      move, rotate about the vertical, height only
//   Del            delete through JOSM's Delete action;  ctrl+Z / ctrl+Y undo / redo
import * as THREE from "three";
import { TransformControls } from "three/addons/controls/TransformControls.js";
import { Emitter, round3 } from "./util.js";
import { canvas, scene } from "./scene.js";
import { camera, view } from "./camera.js";
import { store, nodeToken } from "./store.js";
import { lineLayer } from "./render/lines.js";
import { setHighlightVisible } from "./render/highlight.js";
import { selection } from "./selection.js";
import { pickNode, nodesUnderCursor, featuresUnderCursor, screenProjector, projectToPage } from "./picking.js";
import { sendCommand } from "./net.js";
import { setEditHud, toast } from "./hud.js";

const GIZMO_CLICK_NODE_PX = 4; // a click on a gizmo handle selects only a node dot this close
const CLICK_SLOP_PX = 5;
const MOVE_EPS_M = 0.0005;     // below this a coordinate counts as unchanged

export const editEvents = new Emitter(); // "mode" (on)
export const edit = { on: false, tool: "translate", heightOnly: false };

// The gizmo moves this invisible pivot at the selection's centre; the
// selected nodes follow it.
const pivot = new THREE.Object3D();
scene.add(pivot);
export const transform = new TransformControls(camera, canvas);
transform.setSize(0.9);
scene.add(transform);
transform.addEventListener("mouseDown", beginGizmoDrag);
transform.addEventListener("objectChange", onGizmoChange);
transform.addEventListener("mouseUp", endGizmoDrag);

export const gizmoBusy = () => transform.dragging;

const _axis = new THREE.Vector3();
let pendingJosmSelection = null; // JOSM selection received outside edit mode
let drag = null;                 // { start, orig: Map<id, [x, y, z]>, moved }

// --- mode, tool, gizmo ----------------------------------------------------------------

export function setEditMode(on) {
  edit.on = !!on;
  if (edit.on) {
    store.ensureNodeIndex();
    if (pendingJosmSelection) {
      selection.applyFromJosm(pendingJosmSelection);
      pendingJosmSelection = null;
    }
  }
  lineLayer.setDotsVisible(edit.on);
  setHighlightVisible(edit.on);
  const bar = document.getElementById("editBar");
  if (bar) bar.hidden = !edit.on;
  const btn = document.getElementById("editBtn");
  if (btn) btn.classList.toggle("on", edit.on);
  refreshGizmo();
  editEvents.emit("mode", edit.on);
}

export function setTool(tool) {
  edit.tool = tool;
  if (tool === "rotate") edit.heightOnly = false;
  refreshGizmo();
}

export function toggleHeightOnly() {
  edit.heightOnly = !edit.heightOnly;
  if (edit.heightOnly) edit.tool = "translate";
  refreshGizmo();
}

function refreshGizmo() {
  const show = edit.on && !selection.isEmpty();
  if (show && !drag) placePivot();
  if (show) {
    const rotate = edit.tool === "rotate";
    transform.setMode(rotate ? "rotate" : "translate");
    // Rotation is about the vertical only; height-only shows just the Z arrow.
    const zOnly = rotate || edit.heightOnly;
    transform.showX = !zOnly;
    transform.showY = !zOnly;
    transform.showZ = true;
    if (transform.object !== pivot) transform.attach(pivot);
  } else if (transform.object) {
    transform.detach();
  }
  for (const b of document.querySelectorAll("#editBar [data-edit]")) {
    const k = b.getAttribute("data-edit");
    b.classList.toggle("on", (k === "height" && edit.heightOnly) || (k === edit.tool && !edit.heightOnly));
  }
  refreshEditHud();
}

function refreshEditHud() {
  if (!edit.on) {
    setEditHud("off");
    return;
  }
  const parts = [];
  if (selection.nodes.size) parts.push(`${selection.nodes.size} node${selection.nodes.size > 1 ? "s" : ""}`);
  if (selection.ways.size) parts.push(`${selection.ways.size} way${selection.ways.size > 1 ? "s" : ""}`);
  const one = selection.single();
  const what = one ? (one.type === "node" ? nodeToken(one.id) : one.id) : parts.join(", ");
  const tool = edit.heightOnly ? "height only" : edit.tool === "rotate" ? "rotate" : "move";
  setEditHud(`ON · ${what || "nothing selected"} · ${tool}`);
}

/** Centre of the nodes a move would act on (mean position). */
function placePivot() {
  let n = 0, x = 0, y = 0, z = 0;
  for (const id of selection.effectiveNodeIds()) {
    const rec = store.node(id);
    if (!rec) continue;
    x += rec.x; y += rec.y; z += rec.z; n++;
  }
  if (n) pivot.position.set(x / n, y / n, z / n);
  pivot.quaternion.identity();
  pivot.updateMatrixWorld();
}

selection.on("changed", () => refreshGizmo());
store.on("applied", (msg) => {
  if (msg.type === "selection" || (msg.type === "snapshot" && msg.selection)) {
    const sel = msg.type === "selection" ? msg : msg.selection;
    if (edit.on) selection.applyFromJosm(sel);
    else pendingJosmSelection = sel;
  }
  if (!drag) refreshGizmo();
});
store.on("nodes-moved", () => { if (!drag && edit.on && !selection.isEmpty()) placePivot(); });

// --- gizmo drag ---------------------------------------------------------------------

function beginGizmoDrag() {
  const orig = new Map();
  for (const id of selection.effectiveNodeIds()) {
    const rec = store.node(id);
    if (rec) orig.set(id, [rec.x, rec.y, rec.z]);
  }
  pivot.quaternion.identity();
  drag = { start: pivot.position.clone(), orig, moved: false };
}

// Drag in progress: move the nodes locally so connected ways follow live.
function onGizmoChange() {
  if (!drag) return;
  const moves = [];
  if (transform.mode === "rotate") {
    // Only the heading change counts: how the X axis turned in the ground
    // plane. The X/Y rings are hidden, but the view-axis ring ("E") is not
    // and would otherwise tilt the selection out of level.
    _axis.set(1, 0, 0).applyQuaternion(pivot.quaternion);
    const a = Math.atan2(_axis.y, _axis.x);
    const c = Math.cos(a), s = Math.sin(a);
    const o = drag.start;
    for (const [id, p] of drag.orig) {
      const dx = p[0] - o.x, dy = p[1] - o.y;
      moves.push({ id, x: o.x + c * dx - s * dy, y: o.y + s * dx + c * dy, z: p[2] });
    }
  } else {
    const d = pivot.position.clone().sub(drag.start);
    if (edit.heightOnly) d.x = d.y = 0;
    for (const [id, p] of drag.orig) moves.push({ id, x: p[0] + d.x, y: p[1] + d.y, z: p[2] + d.z });
  }
  drag.moved = true;
  store.moveNodes(moves);
}

function endGizmoDrag() {
  const d = drag;
  drag = null;
  pivot.quaternion.identity();
  if (!d || !d.moved) return;
  commitMoves(d.orig);
  placePivot();
}

/**
 * Send the nodes' new positions to JOSM as one command. Only changed
 * components go out: a height-only move carries no x/y (lat/lon stay exact),
 * a level move no z (no `ele` added). Reverts locally if JOSM refuses.
 */
export function commitMoves(orig) {
  const ops = [];
  const revert = [];
  for (const [id, o] of orig) {
    const rec = store.node(id);
    if (!rec) continue;
    const xy = Math.abs(rec.x - o[0]) > MOVE_EPS_M || Math.abs(rec.y - o[1]) > MOVE_EPS_M;
    const z = Math.abs(rec.z - o[2]) > MOVE_EPS_M;
    if (!xy && !z) continue;
    const op = { op: "move_node", id: nodeToken(id) };
    if (xy) {
      op.x = round3(rec.x);
      op.y = round3(rec.y);
    }
    if (z) op.z = round3(rec.z);
    ops.push(op);
    revert.push({ id, x: o[0], y: o[1], z: o[2] });
  }
  if (!ops.length) return Promise.resolve(null);
  return sendCommand(ops, { awaitResult: true }).then((info) => {
    const refused = !info.delivered
      ? (info.error ? `could not reach the viewer server (${info.error})` : "JOSM is not connected")
      : info.result && info.result.ok === false ? info.result.message : null;
    if (refused) {
      store.moveNodes(revert);
      toast(`Move not applied: ${refused}`, "error");
    }
    return info;
  });
}

// --- JOSM actions --------------------------------------------------------------------

export function deleteSelection() {
  if (selection.isEmpty()) {
    toast("Nothing selected to delete", "warn", 2000);
    return;
  }
  sendCommand([{ op: "delete_selection", ids: selection.tokens() }], { awaitResult: true })
    .then((info) => reportAction("Delete", info));
}

export function undo(redo = false) {
  sendCommand([{ op: redo ? "redo" : "undo" }], { awaitResult: true })
    .then((info) => reportAction(redo ? "Redo" : "Undo", info));
}

function reportAction(what, info) {
  if (!info.delivered) toast(`${what}: JOSM is not connected`, "error");
  else if (info.result && info.result.ok === false) toast(`${what}: ${info.result.message}`, "warn");
}

// --- pointer: click, box, cycle -------------------------------------------------------
// Registered right after TransformControls, whose own pointerdown therefore
// runs first and sets `dragging` when the press hits a handle. Its pointerup
// clears that again before ours runs, so remember it at press time. Box
// select stops the event so the navigation listeners (added later) never
// start an orbit for it.
let down = null;   // { x, y, onGizmo, button }
let box = null;    // { x0, y0, x1, y1, add, pointerId }
let cycle = null;  // { x, y, camKey, items, idx }
const boxEl = document.getElementById("boxSel");

canvas.addEventListener("pointerdown", (e) => {
  down = { x: e.clientX, y: e.clientY, onGizmo: transform.dragging, button: e.button };
  if (edit.on && e.button === 0 && (e.ctrlKey || e.metaKey) && !transform.dragging) {
    e.stopImmediatePropagation();
    box = { x0: e.clientX, y0: e.clientY, x1: e.clientX, y1: e.clientY, add: e.shiftKey, pointerId: e.pointerId };
    canvas.setPointerCapture(e.pointerId);
    drawBox();
  }
});

canvas.addEventListener("pointermove", (e) => {
  if (!box || e.pointerId !== box.pointerId) return;
  box.x1 = e.clientX;
  box.y1 = e.clientY;
  drawBox();
});

canvas.addEventListener("pointerup", (e) => {
  if (box && e.pointerId === box.pointerId) {
    const b = box;
    box = null;
    drawBox();
    if (canvas.hasPointerCapture(e.pointerId)) canvas.releasePointerCapture(e.pointerId);
    finishBox(b);
    return;
  }
  if (!edit.on || !down || transform.dragging) return;
  if (Math.abs(e.clientX - down.x) > CLICK_SLOP_PX || Math.abs(e.clientY - down.y) > CLICK_SLOP_PX) return;
  if (e.button === 0) clickSelect(e.clientX, e.clientY, e.shiftKey, down.onGizmo);
  else if (e.button === 1) cycleAt(e.clientX, e.clientY);
});

function applyPick(item, add) {
  cycle = null;
  if (add) selection.toggle(item);
  else selection.set([item]);
}

function clickSelect(x, y, add, onGizmo) {
  if (onGizmo) {
    // A click on a handle keeps the selection rather than selecting whichever
    // node lies near the arrow; only a click squarely on a node dot counts.
    const id = pickNode(x, y, GIZMO_CLICK_NODE_PX);
    if (id !== null) applyPick({ type: "node", id }, add);
    return;
  }
  const id = pickNode(x, y);
  if (id !== null) {
    applyPick({ type: "node", id }, add);
    return;
  }
  const ways = featuresUnderCursor(x, y);
  if (ways.length) {
    applyPick({ type: "way", id: ways[0].id }, add);
    return;
  }
  if (!add) {
    cycle = null;
    selection.clear();
  }
}

const camKey = () => `${camera.position.toArray().join()},${view.yaw},${view.pitch}`;

/**
 * Middle-click: select the next of everything under the cursor, nodes first
 * (front to back), then ways. Repeated clicks on the same spot walk the list,
 * which is the only way to reach a node that sits exactly on another.
 */
function cycleAt(x, y) {
  if (cycle && Math.hypot(x - cycle.x, y - cycle.y) <= 3 && cycle.camKey === camKey()) {
    cycle.idx = (cycle.idx + 1) % cycle.items.length;
  } else {
    const items = [
      ...nodesUnderCursor(x, y).map((n) => ({ type: "node", id: n.id })),
      ...featuresUnderCursor(x, y).map((f) => ({ type: "way", id: f.id })),
    ];
    if (!items.length) {
      cycle = null;
      return;
    }
    const cur = selection.single();
    const k = cur ? items.findIndex((it) => it.type === cur.type && it.id === cur.id) : -1;
    cycle = { x, y, camKey: camKey(), items, idx: k >= 0 ? (k + 1) % items.length : 0 };
  }
  const item = cycle.items[cycle.idx];
  selection.set([item]);
  const label = item.type === "node" ? nodeToken(item.id) : item.id;
  toast(`${cycle.idx + 1} of ${cycle.items.length} under the cursor: ${label}`, "info", 1500);
}

function drawBox() {
  if (!boxEl) return;
  if (!box) {
    boxEl.style.display = "none";
    return;
  }
  boxEl.style.display = "block";
  boxEl.style.left = `${Math.min(box.x0, box.x1)}px`;
  boxEl.style.top = `${Math.min(box.y0, box.y1)}px`;
  boxEl.style.width = `${Math.abs(box.x1 - box.x0)}px`;
  boxEl.style.height = `${Math.abs(box.y1 - box.y0)}px`;
}

/** Nodes inside the box, and ways entirely inside it (like JOSM's lasso). */
function finishBox(b) {
  const x0 = Math.min(b.x0, b.x1), x1 = Math.max(b.x0, b.x1);
  const y0 = Math.min(b.y0, b.y1), y1 = Math.max(b.y0, b.y1);
  if (x1 - x0 < 3 && y1 - y0 < 3) return;
  const pr = screenProjector();
  const inside = (p, i) => {
    const s = projectToPage(pr, p[i * 3], p[i * 3 + 1], p[i * 3 + 2]);
    return s !== null && s[0] >= x0 && s[0] <= x1 && s[1] >= y0 && s[1] <= y1;
  };
  const items = [];
  const seen = new Set();
  for (const f of store.features.values()) {
    if (f.kind !== "line") continue;
    const n = Math.min(f.nodes.length, f.pos.length / 3);
    let all = n > 0;
    for (let i = 0; i < n; i++) {
      if (inside(f.pos, i)) {
        if (!seen.has(f.nodes[i])) {
          seen.add(f.nodes[i]);
          items.push({ type: "node", id: f.nodes[i] });
        }
      } else {
        all = false;
      }
    }
    if (all) items.push({ type: "way", id: f.id });
  }
  cycle = null;
  if (b.add) selection.add(items);
  else selection.set(items);
  toast(`Box: ${seen.size} node(s)`, "info", 1500);
}

// --- keys and toolbar ------------------------------------------------------------------

/** Edit keys; returns true when the event was consumed. */
export function handleEditKey(e) {
  const k = e.key;
  const ctrl = e.ctrlKey || e.metaKey;
  if (ctrl && (k === "z" || k === "Z")) {
    e.preventDefault();
    undo(e.shiftKey);
    return true;
  }
  if (ctrl && (k === "y" || k === "Y")) {
    e.preventDefault();
    undo(true);
    return true;
  }
  if (!edit.on || ctrl || e.altKey) return false;
  if (k === "Delete" || k === "Backspace") {
    e.preventDefault();
    deleteSelection();
    return true;
  }
  if (k === "Escape") {
    cycle = null;
    selection.clear();
    return true;
  }
  if (e.repeat) return false;
  if (k === "g" || k === "G") { setTool("translate"); return true; }
  if (k === "r" || k === "R") { setTool("rotate"); return true; }
  if (k === "h" || k === "H") { toggleHeightOnly(); return true; }
  return false;
}

for (const b of document.querySelectorAll("#editBar [data-edit]")) {
  b.addEventListener("click", () => {
    const k = b.getAttribute("data-edit");
    if (k === "translate" || k === "rotate") setTool(k);
    else if (k === "height") toggleHeightOnly();
    else if (k === "delete") deleteSelection();
    else if (k === "undo") undo(false);
    else if (k === "redo") undo(true);
  });
}
const editBtn = document.getElementById("editBtn");
if (editBtn) editBtn.addEventListener("click", () => setEditMode(!edit.on));
