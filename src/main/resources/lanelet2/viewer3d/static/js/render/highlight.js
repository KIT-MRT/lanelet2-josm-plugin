// Selection highlight drawn over the map: selected ways as yellow lines, and a
// dot on every node a move would act on (selected nodes plus the nodes of
// selected ways). Rebuilt on the next frame after the selection or the
// geometry under it changes, including every frame of a drag. Under it, in
// cyan, the hover: what a click at the pointer would select (see hover.js).
// A lanelet (a relation picked in View mode) shows as its bounds over a tint
// of its surface.
import * as THREE from "three";
import { store } from "../store.js";
import { overlayRoot } from "../scene.js";
import { selection } from "../selection.js";
import { SELECT_COLOR, HOVER_COLOR } from "./style.js";
import { screenLines } from "./screenlines.js";
import { laneletTriangles } from "./lanelets.js";

// Lines a few pixels wide: a one-pixel line vanishes next to a styled marking.
const lines = screenLines({ color: SELECT_COLOR, widthPx: 3, opacity: 0.95, renderOrder: 1003 });
const dotMat = new THREE.PointsMaterial({
  color: SELECT_COLOR, size: 9, sizeAttenuation: false, depthTest: false, transparent: true,
});

const dots = new THREE.Points(new THREE.BufferGeometry(), dotMat);
dots.renderOrder = 1004;
dots.frustumCulled = false;
overlayRoot.add(lines);
overlayRoot.add(dots);

const hoverLine = screenLines({ color: HOVER_COLOR, widthPx: 5, opacity: 0.85, renderOrder: 1001 });
const hoverDot = new THREE.Points(new THREE.BufferGeometry(), new THREE.PointsMaterial({
  color: HOVER_COLOR, size: 14, sizeAttenuation: false, depthTest: false, transparent: true, opacity: 0.9,
}));
hoverDot.renderOrder = 1002; // the hover sits below the selection, so a selected dot shows framed
hoverDot.frustumCulled = false;
overlayRoot.add(hoverLine);
overlayRoot.add(hoverDot);
// Surface tints: drawn over the map (the lanelet surface itself sits at the
// same height, so a depth-tested copy would flicker against it), under the lines.
const fillMesh = (color, opacity, renderOrder) => {
  const m = new THREE.Mesh(new THREE.BufferGeometry(), new THREE.MeshBasicMaterial({
    color, transparent: true, opacity, depthTest: false, depthWrite: false, side: THREE.DoubleSide,
  }));
  m.renderOrder = renderOrder;
  m.frustumCulled = false;
  overlayRoot.add(m);
  return m;
};
const fill = fillMesh(SELECT_COLOR, 0.22, 1000);
const hoverFill = fillMesh(HOVER_COLOR, 0.14, 999);
let hover = null; // { type: "node" | "way" | "relation", id }

// Separate flags: the hover changes often, the selection can be 20k nodes.
let dirty = true;
let hoverDirty = true;
const markDirty = () => { dirty = true; hoverDirty = true; };
selection.on("changed", () => { dirty = true; });
store.on("clear", markDirty);
// A lanelet is drawn from its bounds, so any line may be one of them: with a
// lanelet selected or hovered, every change rebuilds it (a few lanes, cheap).
const isHovered = (f) => hover && (hover.type === "relation" || (hover.type === "way" ? hover.id === f.id
  : f.nodes.includes(hover.id)));
store.on("upsert", (f) => {
  if (selection.ways.has(f.id) || selection.relations.size || f.nodes.some((n) => selection.nodes.has(n))) {
    dirty = true;
  }
  if (isHovered(f)) hoverDirty = true;
});
store.on("remove", (f) => {
  if (selection.ways.has(f.id) || selection.relations.size) dirty = true;
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
  const tri = [];
  for (const rid of selection.relations) relationOutline(rid, seg, tri);
  const pts = [];
  if (selection.hasEditable()) {
    for (const id of selection.effectiveNodeIds()) {
      const rec = store.node(id);
      if (rec) pts.push(rec.x, rec.y, rec.z);
    }
  }
  lines.setPairs(seg);
  setPositions(dots, pts);
  setPositions(fill, tri);
}

/** A lanelet's bounds (as line pairs) and surface (as triangles); other relations have no geometry here. */
function relationOutline(id, seg, tri) {
  const f = store.features.get(id);
  if (!f || f.kind !== "lanelet") return;
  for (const wid of [f.lanelet.left, f.lanelet.right]) {
    const w = store.features.get(wid);
    if (w) wayPairs(w.pos, seg);
  }
  const t = laneletTriangles(f);
  if (t) for (const v of t) tri.push(v);
}

function rebuildHover() {
  const hSeg = [];
  const hPts = [];
  const hTri = [];
  if (hover && hover.type === "way") {
    const f = store.features.get(hover.id);
    if (f) wayPairs(f.pos, hSeg);
  } else if (hover && hover.type === "relation") {
    relationOutline(hover.id, hSeg, hTri);
  } else if (hover) {
    const rec = store.node(hover.id);
    if (rec) hPts.push(rec.x, rec.y, rec.z);
  }
  hoverLine.setPairs(hSeg);
  setPositions(hoverDot, hPts);
  setPositions(hoverFill, hTri);
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
  fill.visible = !!on;
  hoverFill.visible = !!on;
}
