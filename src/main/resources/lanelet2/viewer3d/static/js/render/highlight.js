// Selection highlight drawn over the map: selected ways as yellow lines, and a
// dot on every node a move would act on (selected nodes plus the nodes of
// selected ways). Rebuilt on the next frame after the selection or the
// geometry under it changes, including every frame of a drag.
import * as THREE from "three";
import { store } from "../store.js";
import { overlayRoot } from "../scene.js";
import { selection } from "../selection.js";
import { SELECT_COLOR } from "./style.js";

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

let dirty = true;
const markDirty = () => { dirty = true; };
selection.on("changed", markDirty);
store.on("clear", markDirty);
store.on("upsert", (f) => {
  if (selection.ways.has(f.id) || f.nodes.some((n) => selection.nodes.has(n))) dirty = true;
});
store.on("remove", (f) => { if (selection.ways.has(f.id)) dirty = true; });
store.on("nodes-moved", markDirty);

/** Per frame: rebuild the highlight if anything under it changed. */
export function updateHighlight() {
  if (!dirty) return;
  dirty = false;
  const seg = [];
  for (const wid of selection.ways) {
    const f = store.features.get(wid);
    if (!f) continue;
    const p = f.pos;
    for (let i = 3; i < p.length; i += 3) seg.push(p[i - 3], p[i - 2], p[i - 1], p[i], p[i + 1], p[i + 2]);
  }
  const pts = [];
  if (!selection.isEmpty()) {
    for (const id of selection.effectiveNodeIds()) {
      const rec = store.node(id);
      if (rec) pts.push(rec.x, rec.y, rec.z);
    }
  }
  lines.geometry.dispose();
  dots.geometry.dispose();
  const lg = new THREE.BufferGeometry();
  lg.setAttribute("position", new THREE.BufferAttribute(new Float32Array(seg), 3));
  const dg = new THREE.BufferGeometry();
  dg.setAttribute("position", new THREE.BufferAttribute(new Float32Array(pts), 3));
  lines.geometry = lg;
  dots.geometry = dg;
}

/** The highlight shows only in edit mode; the selection itself persists. */
export function setHighlightVisible(on) {
  lines.visible = !!on;
  dots.visible = !!on;
}
