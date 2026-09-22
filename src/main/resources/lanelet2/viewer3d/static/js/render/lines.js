// Batched line rendering: one indexed LineSegments per render chunk (4x4 store
// tiles) instead of one THREE.Line per way. A city map (Karlsruhe: 144k ways)
// would otherwise cost 144k draw calls per frame. Edit-mode node dots are a
// THREE.Points per chunk sharing the same vertex buffer.
//
// A chunk is rebuilt (on the next frame) when a tile in it changes. A drag in
// progress only rewrites the moved vertices in place.
import * as THREE from "three";
import { store, chunkKeyOf, tilesOfChunk } from "../store.js";
import { mapRoot } from "../scene.js";
import { featureColor, EDIT_COLOR } from "./style.js";
import { now, plog, PROFILE } from "../util.js";

const lineMaterial = new THREE.LineBasicMaterial({ vertexColors: true });
const dotMaterial = new THREE.PointsMaterial({ color: EDIT_COLOR, size: 6, sizeAttenuation: false });

class LineLayer {
  constructor() {
    this.chunks = new Map(); // chunk key -> { key, lines, dots, posDirty }
    this.dirty = new Set();  // chunk keys to rebuild
    this.dotsVisible = false;
    store.on("tile-dirty", (tile) => this.dirty.add(chunkKeyOf(tile.key)));
    store.on("clear", () => this._clearAll());
    store.on("nodes-moved", (recs) => this._patchNodes(recs));
  }

  setDotsVisible(on) {
    this.dotsVisible = !!on;
    for (const c of this.chunks.values()) c.dots.visible = this.dotsVisible;
  }

  /** Per frame: rebuild changed tiles, upload patched vertices. */
  update() {
    if (this.dirty.size) {
      const t0 = PROFILE ? now() : 0;
      const n = this.dirty.size;
      for (const key of this.dirty) this._rebuild(key);
      this.dirty.clear();
      if (PROFILE && n > 20) plog(`lines: ${n} chunks ${(now() - t0).toFixed(1)}ms`);
    }
    for (const c of this.chunks.values()) {
      if (!c.posDirty) continue;
      c.posDirty = false;
      c.lines.geometry.attributes.position.needsUpdate = true;
      c.lines.geometry.computeBoundingSphere();
      c.dots.geometry.boundingSphere = c.lines.geometry.boundingSphere;
    }
  }

  _rebuild(chunkKey) {
    const feats = [];
    let nVerts = 0;
    let nIdx = 0;
    for (const tile of tilesOfChunk(chunkKey)) {
      for (const f of tile.features) {
        if (f.kind !== "line") continue;
        const n = f.pos.length / 3;
        if (n < 2) continue;
        feats.push(f);
        nVerts += n;
        nIdx += 2 * (n - 1);
      }
    }
    if (feats.length === 0) {
      this._dispose(chunkKey);
      return;
    }
    const pos = new Float32Array(nVerts * 3);
    const col = new Float32Array(nVerts * 3);
    const idx = nVerts > 65535 ? new Uint32Array(nIdx) : new Uint16Array(nIdx);
    const c = this._chunk(chunkKey);
    let v = 0;
    let e = 0;
    for (const f of feats) {
      const n = f.pos.length / 3;
      const color = featureColor(f);
      f.vChunk = c;
      f.vOff = v;
      for (let i = 0; i < n; i++) {
        const o = (v + i) * 3;
        pos[o] = f.pos[i * 3];
        pos[o + 1] = f.pos[i * 3 + 1];
        pos[o + 2] = f.pos[i * 3 + 2];
        col[o] = color.r;
        col[o + 1] = color.g;
        col[o + 2] = color.b;
      }
      for (let i = 0; i < n - 1; i++) {
        idx[e++] = v + i;
        idx[e++] = v + i + 1;
      }
      v += n;
    }
    const posAttr = new THREE.BufferAttribute(pos, 3);
    const geom = new THREE.BufferGeometry();
    geom.setAttribute("position", posAttr);
    geom.setAttribute("color", new THREE.BufferAttribute(col, 3));
    geom.setIndex(new THREE.BufferAttribute(idx, 1));
    geom.computeBoundingSphere();
    const dotGeom = new THREE.BufferGeometry();
    dotGeom.setAttribute("position", posAttr);
    dotGeom.boundingSphere = geom.boundingSphere;
    c.lines.geometry.dispose();
    c.dots.geometry.dispose();
    c.lines.geometry = geom;
    c.dots.geometry = dotGeom;
    c.posDirty = false;
  }

  _chunk(key) {
    let c = this.chunks.get(key);
    if (c) return c;
    const lines = new THREE.LineSegments(new THREE.BufferGeometry(), lineMaterial);
    const dots = new THREE.Points(new THREE.BufferGeometry(), dotMaterial);
    dots.visible = this.dotsVisible;
    dots.renderOrder = 1000;
    mapRoot.add(lines);
    mapRoot.add(dots);
    c = { key, lines, dots, posDirty: false };
    this.chunks.set(key, c);
    return c;
  }

  _dispose(key) {
    const c = this.chunks.get(key);
    if (!c) return;
    mapRoot.remove(c.lines);
    mapRoot.remove(c.dots);
    c.lines.geometry.dispose();
    c.dots.geometry.dispose();
    this.chunks.delete(key);
  }

  _clearAll() {
    for (const key of Array.from(this.chunks.keys())) this._dispose(key);
    this.dirty.clear();
  }

  // A drag in progress: rewrite the moved vertices of every feature using them.
  // Tiles already queued for a rebuild pick the new positions up from the store.
  _patchNodes(recs) {
    for (const rec of recs) {
      for (const { f, i } of rec.refs) {
        const c = f.vChunk;
        if (!c || f.kind !== "line" || this.dirty.has(c.key) || this.chunks.get(c.key) !== c) continue;
        c.lines.geometry.attributes.position.setXYZ(f.vOff + i, rec.x, rec.y, rec.z);
        c.posDirty = true;
      }
    }
  }
}

export const lineLayer = new LineLayer();
