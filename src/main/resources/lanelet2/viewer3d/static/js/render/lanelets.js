// Lanelet surfaces and direction arrows.
//
// A lanelet feature references its bound ways (streamed as line features) and
// says whether each runs against the driving direction (JOSM aligns them with
// the lanelet2 `geometry::align` port). Surfaces are triangulated here from
// those ways, batched per render chunk, and rebuilt when a bound changes (live
// during a drag). Arrows sit where JOSM put them: 35 % along the lanelet2
// centerline, double-headed when `one_way` parses as false. Two instanced
// meshes draw every arrow of the map.
import * as THREE from "three";
import { store, chunkKeyOf, tilesOfChunk } from "../store.js";
import { mapRoot } from "../scene.js";
import { now, plog, PROFILE } from "../util.js";

const SURFACE_OPACITY = 0.22;
const SURFACE_COLORS = {
  road: 0x4a6fa5,
  highway: 0x4a6fa5,
  play_street: 0x6f8fb5,
  bus_lane: 0xa5704a,
  emergency_lane: 0xa55a4a,
  bicycle_lane: 0x5aa54a,
  walkway: 0xa5904a,
  shared_walkway: 0x90a54a,
  crosswalk: 0xd0d0d0,
  stairs: 0x8a6fa5,
  parking: 0x7a5aa5,
};
const DEFAULT_SURFACE_COLOR = 0x607890;
const ARROW_LIFT_M = 0.05;
const ARROW_LEN_M = 2.4;          // nominal geometry length
const ONE_WAY_COLOR = 0xf2f5fb;
const TWO_WAY_COLOR = 0xffd23f;

// forceSinglePass: three.js draws transparent double-sided materials twice
// (back faces, then front) unless told otherwise; these are flat, so one pass
// looks the same at half the draw calls (817 surface tiles on Karlsruhe).
const surfaceMat = new THREE.MeshBasicMaterial({
  vertexColors: true, transparent: true, opacity: SURFACE_OPACITY,
  side: THREE.DoubleSide, depthWrite: false, forceSinglePass: true,
});

/** Flat arrow in the XY plane pointing +X, centred on the origin; two heads if `double`. */
function arrowGeometry(double) {
  const L = ARROW_LEN_M, h = 0.8, sw = 0.14, hw = 0.45;
  const s = new THREE.Shape();
  const x0 = -L / 2, x1 = L / 2;
  if (double) {
    s.moveTo(x0, 0);
    s.lineTo(x0 + h, hw);
    s.lineTo(x0 + h, sw);
    s.lineTo(x1 - h, sw);
    s.lineTo(x1 - h, hw);
    s.lineTo(x1, 0);
    s.lineTo(x1 - h, -hw);
    s.lineTo(x1 - h, -sw);
    s.lineTo(x0 + h, -sw);
    s.lineTo(x0 + h, -hw);
  } else {
    s.moveTo(x0, sw);
    s.lineTo(x1 - h, sw);
    s.lineTo(x1 - h, hw);
    s.lineTo(x1, 0);
    s.lineTo(x1 - h, -hw);
    s.lineTo(x1 - h, -sw);
    s.lineTo(x0, -sw);
  }
  s.closePath();
  return new THREE.ShapeGeometry(s);
}

function arrowMaterial(color) {
  return new THREE.MeshBasicMaterial({
    color, transparent: true, opacity: 0.9, side: THREE.DoubleSide, depthWrite: false, forceSinglePass: true,
  });
}

// Bound polyline as [x, y, z] in driving order, or null if the way is absent.
function boundPoints(wayId, reversed) {
  const w = store.features.get(wayId);
  if (!w || w.pos.length < 6) return null;
  const out = [];
  for (let i = 0; i < w.pos.length; i += 3) out.push([w.pos[i], w.pos[i + 1], w.pos[i + 2]]);
  if (reversed) out.reverse();
  return out;
}

const d2 = (a, b) => (a[0] - b[0]) ** 2 + (a[1] - b[1]) ** 2 + (a[2] - b[2]) ** 2;

/** Triangle strip between two polylines: always advance along the shorter diagonal. */
function stripTriangles(L, R, emit) {
  let i = 0, j = 0;
  while (i < L.length - 1 || j < R.length - 1) {
    const advanceLeft = j === R.length - 1
      || (i < L.length - 1 && d2(L[i + 1], R[j]) <= d2(L[i], R[j + 1]));
    if (advanceLeft) {
      emit(L[i], R[j], L[i + 1]);
      i++;
    } else {
      emit(L[i], R[j], R[j + 1]);
      j++;
    }
  }
}

class LaneletLayer {
  constructor() {
    this.lanelets = new Map();  // lanelet feature id -> feature
    this.byWay = new Map();     // way feature id -> Set(lanelet id)
    this.chunks = new Map();    // render chunk key -> THREE.Mesh
    this.dirtyTiles = new Set();
    this.arrowsDirty = false;
    this.visible = true;
    this.arrowMeshes = [];      // [one-way, two-way] InstancedMesh
    this.oneGeom = arrowGeometry(false);
    this.twoGeom = arrowGeometry(true);
    this.oneMat = arrowMaterial(ONE_WAY_COLOR);
    this.twoMat = arrowMaterial(TWO_WAY_COLOR);

    store.on("upsert", (f, old) => {
      if (f.kind === "lanelet") {
        if (old) this._forget(old);
        this._remember(f);
      } else if (f.kind === "line") {
        this._boundChanged(f.id);
      }
    });
    store.on("remove", (f) => {
      if (f.kind === "lanelet") this._forget(f);
      else if (f.kind === "line") this._boundChanged(f.id);
    });
    // The store detaches a replaced feature (clearing its tile) before it
    // emits the upsert, so tile membership changes arrive here.
    store.on("tile-dirty", (tile) => this.dirtyTiles.add(chunkKeyOf(tile.key)));
    store.on("clear", () => this._clearAll());
    store.on("nodes-moved", (recs) => {
      for (const rec of recs) for (const { f } of rec.refs) this._boundChanged(f.id);
    });
  }

  setVisible(on) {
    this.visible = !!on;
    for (const m of this.chunks.values()) m.visible = this.visible;
    for (const m of this.arrowMeshes) m.visible = this.visible;
  }

  /** Surface meshes, for picking the road surface under the cursor. */
  surfaceMeshes() {
    return this.visible ? Array.from(this.chunks.values()) : [];
  }

  _remember(f) {
    this.lanelets.set(f.id, f);
    for (const wid of [f.lanelet.left, f.lanelet.right]) {
      let set = this.byWay.get(wid);
      if (!set) this.byWay.set(wid, (set = new Set()));
      set.add(f.id);
    }
    if (f.tile) this.dirtyTiles.add(chunkKeyOf(f.tile.key));
    this.arrowsDirty = true;
  }

  _forget(f) {
    if (this.lanelets.get(f.id) === f) this.lanelets.delete(f.id);
    for (const wid of [f.lanelet.left, f.lanelet.right]) {
      const set = this.byWay.get(wid);
      if (set) {
        set.delete(f.id);
        if (!set.size) this.byWay.delete(wid);
      }
    }
    this.arrowsDirty = true;
  }

  _boundChanged(wayId) {
    const ids = this.byWay.get(wayId);
    if (!ids) return;
    for (const id of ids) {
      const l = this.lanelets.get(id);
      if (l && l.tile) this.dirtyTiles.add(chunkKeyOf(l.tile.key));
    }
  }

  _clearAll() {
    for (const m of this.chunks.values()) {
      mapRoot.remove(m);
      m.geometry.dispose();
    }
    this.chunks.clear();
    this.lanelets.clear();
    this.byWay.clear();
    this.dirtyTiles.clear();
    this.arrowsDirty = true;
  }

  /** Per frame: rebuild changed tiles and, if needed, the arrows. */
  update() {
    const t0 = PROFILE ? now() : 0;
    const n = this.dirtyTiles.size;
    for (const key of this.dirtyTiles) this._rebuildTile(key);
    this.dirtyTiles.clear();
    const t1 = PROFILE ? now() : 0;
    const arrows = this.arrowsDirty;
    if (arrows) this._rebuildArrows();
    if (PROFILE && (n > 20 || arrows)) {
      plog(`lanelets: ${n} surface chunks ${(t1 - t0).toFixed(1)}ms` +
        (arrows ? `, ${this.lanelets.size} arrows ${(now() - t1).toFixed(1)}ms` : ""));
    }
  }

  _rebuildTile(key) {
    const old = this.chunks.get(key);
    if (old) {
      mapRoot.remove(old);
      old.geometry.dispose();
      this.chunks.delete(key);
    }
    const pos = [];
    const col = [];
    const c = new THREE.Color();
    for (const tile of tilesOfChunk(key)) for (const f of tile.features) {
      if (f.kind !== "lanelet") continue;
      const L = boundPoints(f.lanelet.left, f.lanelet.lrev);
      const R = boundPoints(f.lanelet.right, f.lanelet.rrev);
      if (!L || !R) continue;
      c.set(SURFACE_COLORS[f.tags.subtype] ?? DEFAULT_SURFACE_COLOR);
      stripTriangles(L, R, (a, b, d) => {
        pos.push(a[0], a[1], a[2], b[0], b[1], b[2], d[0], d[1], d[2]);
        for (let k = 0; k < 3; k++) col.push(c.r, c.g, c.b);
      });
    }
    if (!pos.length) return;
    const geom = new THREE.BufferGeometry();
    geom.setAttribute("position", new THREE.BufferAttribute(new Float32Array(pos), 3));
    geom.setAttribute("color", new THREE.BufferAttribute(new Float32Array(col), 3));
    geom.computeBoundingSphere();
    const mesh = new THREE.Mesh(geom, surfaceMat);
    mesh.visible = this.visible;
    mesh.renderOrder = 5;
    mapRoot.add(mesh);
    this.chunks.set(key, mesh);
  }

  _rebuildArrows() {
    this.arrowsDirty = false;
    for (const m of this.arrowMeshes) {
      mapRoot.remove(m);
      m.dispose();
    }
    const one = [];
    const two = [];
    for (const f of this.lanelets.values()) {
      if (f.lanelet.arrow) (f.lanelet.two ? two : one).push(f.lanelet.arrow);
    }
    const build = (list, geom, mat) => {
      const mesh = new THREE.InstancedMesh(geom, mat, Math.max(1, list.length));
      mesh.count = list.length;
      mesh.frustumCulled = false; // instances span the whole map; the geometry sphere does not
      mesh.renderOrder = 6;
      mesh.visible = this.visible;
      const m = new THREE.Matrix4();
      const X = new THREE.Vector3(), Y = new THREE.Vector3(), Z = new THREE.Vector3();
      const up = new THREE.Vector3(0, 0, 1);
      const p = new THREE.Vector3();
      list.forEach((a, i) => {
        // Local +X along the lane (with its slope), +Z the surface normal.
        X.set(a[3], a[4], a[5]).normalize();
        Z.copy(up).addScaledVector(X, -up.dot(X)).normalize();
        Y.crossVectors(Z, X);
        const scale = Math.min(3.0, Math.max(0.8, (a[6] || 3) * 0.7)) / ARROW_LEN_M;
        m.makeBasis(X.multiplyScalar(scale), Y.multiplyScalar(scale), Z);
        p.set(a[0], a[1], a[2] + ARROW_LIFT_M);
        m.setPosition(p);
        mesh.setMatrixAt(i, m);
      });
      mesh.instanceMatrix.needsUpdate = true;
      mapRoot.add(mesh);
      return mesh;
    };
    this.arrowMeshes = [build(one, this.oneGeom, this.oneMat), build(two, this.twoGeom, this.twoMat)];
  }
}

export const laneletLayer = new LaneletLayer();
