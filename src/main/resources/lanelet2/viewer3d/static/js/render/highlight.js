// Selection highlight drawn over the map: selected ways as yellow lines, and a
// dot on every node a move would act on (selected nodes plus the nodes of
// selected ways). Rebuilt on the next frame after the selection or the
// geometry under it changes, including every frame of a drag. Under it, in
// cyan, the hover: what a click at the pointer would select (see hover.js).
import * as THREE from "three";
import { store } from "../store.js";
import { overlayRoot } from "../scene.js";
import { selection } from "../selection.js";
import { SELECT_COLOR, HOVER_COLOR } from "./style.js";

const lineMat = new THREE.LineBasicMaterial({ color: SELECT_COLOR, depthTest: false, transparent: true });
const dotMat = new THREE.PointsMaterial({
  color: SELECT_COLOR, size: 9, sizeAttenuation: false, depthTest: false, transparent: true,
});

const lines = new THREE.LineSegments(new THREE.BufferGeometry(), lineMat);
const dots = new THREE.Points(new THREE.BufferGeometry(), dotMat);
lines.renderOrder = 1003;
dots.renderOrder = 1004;
lines.frustumCulled = false;
dots.frustumCulled = false;
overlayRoot.add(lines);
overlayRoot.add(dots);

const hoverLine = new THREE.LineSegments(new THREE.BufferGeometry(),
  new THREE.LineBasicMaterial({ color: HOVER_COLOR, depthTest: false, transparent: true, opacity: 0.9 }));
const hoverDot = new THREE.Points(new THREE.BufferGeometry(), new THREE.PointsMaterial({
  color: HOVER_COLOR, size: 14, sizeAttenuation: false, depthTest: false, transparent: true, opacity: 0.9,
}));
hoverLine.renderOrder = 1001; // below the selection, so a selected dot shows framed
hoverDot.renderOrder = 1002;
hoverLine.frustumCulled = false;
hoverDot.frustumCulled = false;
overlayRoot.add(hoverLine);
overlayRoot.add(hoverDot);
let hover = null; // { type: "node" | "way", id }

// Separate flags: the hover changes often, the selection can be 20k nodes.
let dirty = true;
let hoverDirty = true;
const markDirty = () => { dirty = true; hoverDirty = true; };
selection.on("changed", () => { dirty = true; });
store.on("clear", markDirty);
const isHovered = (f) => hover && (hover.type === "way" ? hover.id === f.id : f.nodes.includes(hover.id));
store.on("upsert", (f) => {
  if (selection.ways.has(f.id) || f.nodes.some((n) => selection.nodes.has(n))) dirty = true;
  if (isHovered(f)) hoverDirty = true;
});
store.on("remove", (f) => {
  if (selection.ways.has(f.id)) dirty = true;
  if (isHovered(f)) hoverDirty = true;
});
store.on("nodes-moved", markDirty);

/** Per frame: rebuild the highlight if anything under it changed. */
export function updateHighlight() {
  if (dirty) {
    dirty = false;
    rebuildSelection();
  }
  if (hoverDirty) {
    hoverDirty = false;
    rebuildHover();
  }
}

function rebuildSelection() {
  const seg = [];
  for (const wid of selection.ways) {
    const f = store.features.get(wid);
    if (f) wayPairs(f.pos, seg);
  }
  const pts = [];
  if (!selection.isEmpty()) {
    for (const id of selection.effectiveNodeIds()) {
      const rec = store.node(id);
      if (rec) pts.push(rec.x, rec.y, rec.z);
    }
  }
  setPositions(lines, seg);
  setPositions(dots, pts);
}

function rebuildHover() {
  const hSeg = [];
  const hPts = [];
  if (hover && hover.type === "way") {
    const f = store.features.get(hover.id);
    if (f) wayPairs(f.pos, hSeg);
  } else if (hover) {
    const rec = store.node(hover.id);
    if (rec) hPts.push(rec.x, rec.y, rec.z);
  }
  setPositions(hoverLine, hSeg);
  setPositions(hoverDot, hPts);
}

function wayPairs(p, out) {
  for (let i = 3; i < p.length; i += 3) out.push(p[i - 3], p[i - 2], p[i - 1], p[i], p[i + 1], p[i + 2]);
}

function setPositions(obj, arr) {
  obj.geometry.dispose();
  const g = new THREE.BufferGeometry();
  g.setAttribute("position", new THREE.BufferAttribute(new Float32Array(arr), 3));
  obj.geometry = g;
}

/** Show `item` ({ type, id } or null) as the hover. */
export function setHover(item) {
  if (item === hover || (item && hover && item.type === hover.type && item.id === hover.id)) return;
  hover = item;
  hoverDirty = true;
}

/** The highlight shows only in edit mode; the selection itself persists. */
export function setHighlightVisible(on) {
  lines.visible = !!on;
  dots.visible = !!on;
  hoverLine.visible = !!on;
  hoverDot.visible = !!on;
}
