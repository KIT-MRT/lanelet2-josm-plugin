# 3D viewer / editor roadmap

Branch: `viewer3d-editor`. Status: `[x]` done, `[~]` in progress, `[ ]` open.
All scheduled phases are done; what is left is in the backlog at the end.
Each phase lands as one or more commits; this file is updated with them.

Mouse scheme (decided, same in all modes): left-drag orbits the point under
the cursor, left-click selects (edit mode), Shift+click adds/toggles,
Ctrl+left-drag box-selects, right-drag looks, middle-drag pans, middle-click
cycles overlapping items, wheel zooms toward the cursor. FPS mode differs only
by capturing the mouse and disabling edit mode.

## 0. Done before this plan

- [x] Orbit around the point under the cursor; turn in place over sky;
      right-drag look; middle/shift-drag pan; wheel zoom to cursor.
- [x] Screen-pixel picking; constant-size selection marker; fixed clip planes.

## 1. Foundation (unblocks everything below)

- [x] **Clicking a gizmo handle selects another node** (the "3 m" bug):
      TransformControls clears its axis on pointer-up before the selection
      handler runs, so a click on an arrow is treated as a click on the map.
- [x] **Server build handshake.** `/healthz` reports a build id; the plugin
      recycles a leftover server whose id differs from the jar. Then a generic
      `/js/` module route is safe.
- [x] **Split `app.js` into ES modules** (scene, camera/nav, picking, selection,
      edit, net, hud). Pure-logic modules get node unit tests.
- [x] **Browser E2E harness in the repo** (`testdata/viewer3d/`), headless
      Chrome over CDP with a fake JOSM bridge. Manual, not part of Gradle.
- [x] **Batched rendering for huge maps.** One draw call per spatial chunk
      instead of one `THREE.Line` per way (Karlsruhe: 144k ways = 144k draw
      calls). Features kept as plain data; live edits patch chunk buffers.
- [x] **Incremental JOSM streaming.** Snapshot only dirty ways instead of
      copying every way of the dataset on the EDT per 200 ms cycle.
- [x] **Incremental node index** in the browser (edit mode rebuilt it per message).
- [x] **Protocol v2**: flat coordinate arrays and numeric node ids (the
      browser still reads v1), full-precision anchor (was 3 decimals, up to
      ~50 m off), command ids with a `command_result` reply, inbound parsing
      with JOSM's bundled jakarta.json.
- [ ] ~~Stream standalone nodes~~ deferred: Karlsruhe has 0 nodes outside
      ways (lanelet2 keeps points in linestrings). Revisit if a map needs it.

## 2. Selection and editing core

- [x] Selection model: nodes and ways; click, Shift+click toggle, Ctrl+drag
      box, Esc clears. A selected way moves all its nodes.
- [x] Middle-click cycles through everything under the cursor (coincident
      nodes, overlapping ways), like JOSM.
- [x] 3D → JOSM selection sync, and JOSM → 3D sync while in edit mode, so
      precise selection can happen in JOSM and the 3D edit in the viewer.
- [x] Move the whole selection with the gizmo (centroid); one undo step.
- [x] Rotate the selection (about the vertical through its centroid).
- [x] Height-only mode toggle: gizmo shows only Z, keyboard moves only up/down.
- [x] Delete selection through JOSM's own delete action, so JOSM shows its
      usual warnings.
- [x] Undo / redo from the viewer (Ctrl+Z, Ctrl+Shift+Z / Ctrl+Y) → JOSM stack.
- [x] Moves send only the changed components: height-only edits keep lat/lon
      untouched; XY-only edits do not add `ele` to nodes without one.
- [x] Rejected commands (hidden layer, deleted node) revert the optimistic
      move in the viewer and say why.

## 3. Controls

- [x] Space / C = up / down in every mode (gaming convention); Shift = fast.
- [x] Move-target toggle (camera ⇄ selection): WASD / Space / C /
      Alt+arrows move and rotate the selection like walking the world.
      One undo step per key hold. UI toggle plus key.
- [x] Wheel zoom feedback: marker at the zoom target and a distance readout.

## 4. Height tools

- [x] Interpolate height along a linestring (between its ends, or between the
      selected anchor nodes). Pure Kotlin with tests, exposed in the viewer.
- [x] New nodes take the height of the nearest existing node (JOSM hook,
      **on by default**, setting to disable).
- [x] Height-jump warning after auto-height / interpolation / 3D moves when
      neighbouring nodes differ by more than a threshold (default 2 m,
      configurable); offers to select the offending nodes.

## 5. Lanelet visualisation

- [x] Stream lanelets (left/right refs aligned with the ported lanelet2
      `geometry::align` signed-distance logic, `Lanelet(relation)`).
- [x] Translucent lanelet surfaces; also give picking a real road surface
      instead of the ground-plane fallback.
- [x] Direction arrow at 35 % of the centerline (`Centerline.kt` port).
      Double-headed when `one_way` parses as false per lanelet2
      (`no`, `false`, `0`; Karlsruhe uses `0` 3,782 times).

## 6. Street-level imagery

- [x] One button in the JOSM toolbar and one in the viewer: open the
      selection centre (single node: its location; nothing: view centre /
      camera ground point) in Mapillary, Google Street View or Apple Maps.
      Google gets the camera heading from the viewer.

## 7. Performance targets (Karlsruhe, 415k nodes / 144k ways / 48k lanelets)

- [~] Measure: JOSM snapshot + serialise, transfer, browser parse / build,
      frame time, edit round trip. Record numbers here.

  Browser, full map, no culling (`testdata/viewer3d/perf_viewer.mjs`,
  headless Chrome with software GL, so frame times are CPU-bound):

  | | before (e493a7c) | batched tiles (cd0a1f1) |
  |---|---|---|
  | draw calls | 182,454 | 851 |
  | frame time | 2,551–5,626 ms | 35–48 ms |
  | snapshot sent → all shown | 14.0 s | 3.9 s |
  | page parse + build | 1.57 s | 0.53 s |

  Snapshot: 143,677 ways, 520,042 points, 35 MB JSON (v1). With protocol v2
  (flat arrays): 30 MB, page parse 256 → 152 ms, shown after 3.3 s.

  JOSM side (`Viewer3dPerfTest`, same map, culling off):

  | | before | after |
  |---|---|---|
  | one edit (EDT) | 147–167 ms | 0.1–0.2 ms |
  | culling candidates (EDT) | 125–280 ms (copy every way) | 0.3–0.5 ms (`searchWays`) |
  | encode snapshot JSON | 1.1–1.7 s | 0.13–0.22 s |
  | full snapshot diff (EDT, connect only) | 620–960 ms | 380–810 ms |
- [x] Full map viewable without culling at interactive frame rates.

  Real GPU (Intel Iris Xe, `LL2_GPU=1`), full Karlsruhe with lanelet
  surfaces and 48k arrows (191,582 features, 42.8 MB snapshot):

  | | draw calls | fps |
  |---|---|---|
  | per-tile chunks, two-pass transparency | 2,491 | 21–22 |
  | single-pass flat materials | 1,672 | 40 |
  | 4×4-tile render chunks | 218 | 40–45 |

  The remaining ~20 ms is GPU work on the whole city (lines with a
  logarithmic depth buffer, no early-z); an empty scene runs at 60 fps /
  0.25 ms. One-off build on the first frame: lines 86 ms, surfaces 161 ms,
  arrows 23 ms. JOSM side with lanelets: full snapshot 1.0–1.25 s on the EDT
  (connect only), 41.7 MB.

## Backlog: further improvements found along the way

(Larger ideas that are not scheduled yet. Small obvious fixes are done directly.)

Most impactful for editing heights, in my estimate:

- [x] **Numeric entry**: the edit bar's Z field (key Z) shows the
  selection's height; `112.35` sets it, `+0.2` / `-0.2` shift it, `=-1.5`
  sets a negative one; up/down step 1 cm. One undo step.
- [x] **Snapping while moving** (M, ctrl inverts during a drag): a vertical
  move sticks to the nearest other node's height (within 10 m) or the lanelet
  surface under the point; a single node sticks onto another node.
- **Drive JOSM to what the camera looks at**, not to the camera's own XY.
  With an oblique or orbiting view the culled square follows the camera and
  can drop the very area being looked at; the point at the centre of the view
  is the better cull centre.
- [x] **Compact HUD**: the camera / debug rows and the frame-debug panel are
  behind the title ("▸ details", remembered per browser).

- Height-jump warnings compare absolute height differences between
  neighbouring nodes (as asked). A slope-based check (Δz over horizontal
  distance) would flag steep short steps without nagging on long gentle ones.
- Auto-height takes the single nearest node's height. Interpolating from the
  two ends a new way connects to would suit ways drawn between existing ones.
- Hover highlight in edit mode (the node / way a click would pick), like
  JOSM's, so there is no guessing before clicking.
- [x] ~~The rotate gizmo also shows TransformControls' view-axis ring.~~ It
  does not: TransformControls hides "E" and "XYZE" unless X, Y and Z all
  show, and rotate shows Z only. A check in e2e_edit guards it.
- [x] A pending command's revert assumed no second gesture on the same nodes
  before JOSM answers. Gestures now stamp their nodes with a generation; a
  refusal reverts only nodes no later gesture touched (e2e_heights).

- A full snapshot (connect / layer change) still costs ~1.5 s on the EDT for
  Karlsruhe without culling (ways 0.15 s + lanelet alignment 0.3 s +
  `computeFull` 1.0–1.25 s, of which lanelet centerlines ~0.5 s).
  Snapshotting on the EDT and building features on a worker thread would take
  most of it off the UI; with culling on it is small.
- Draw-distance LOD: hide chunks far beyond the view (or draw them without
  surfaces / arrows) so a whole-city view at a shallow angle does not pay for
  the horizon. Currently ~45 fps on an integrated GPU, fine but not free.
- `one_way:<participant>` overrides (e.g. `one_way:bicycle=no`) could get a
  distinct arrow style; lanelet2 treats them per participant.
