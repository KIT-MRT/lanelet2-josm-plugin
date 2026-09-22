# 3D viewer / editor roadmap

Branch: `viewer3d-editor`. Status: `[x]` done, `[~]` in progress, `[ ]` open.
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
- [ ] Stream standalone nodes (points not in any way) so they can be edited.

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

- [ ] Space / C = up / down in every mode (gaming convention); Shift = fast.
- [ ] Move-target toggle (camera ⇄ selection): WASD / Space / C /
      Alt+arrows move and rotate the selection like walking the world.
      One undo step per key hold. UI toggle plus key.
- [ ] Wheel zoom feedback: marker at the zoom target and a distance readout.

## 4. Height tools

- [ ] Interpolate height along a linestring (between its ends, or between the
      selected anchor nodes). Pure Kotlin with tests, exposed in the viewer.
- [ ] New nodes take the height of the nearest existing node (JOSM hook,
      **on by default**, setting to disable).
- [ ] Height-jump warning after auto-height / interpolation / 3D moves when
      neighbouring nodes differ by more than a threshold (default 2 m,
      configurable); offers to select the offending nodes.

## 5. Lanelet visualisation

- [ ] Stream lanelets (left/right refs aligned with the ported lanelet2
      `geometry::align` signed-distance logic, `Lanelet(relation)`).
- [ ] Translucent lanelet surfaces; also give picking a real road surface
      instead of the ground-plane fallback.
- [ ] Direction arrow at 35 % of the centerline (`Centerline.kt` port).
      Double-headed when `one_way` parses as false per lanelet2
      (`no`, `false`, `0`; Karlsruhe uses `0` 3,782 times).

## 6. Street-level imagery

- [ ] One button in the JOSM toolbar and one in the viewer: open the
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
- [ ] Full map viewable without culling at interactive frame rates.

## Backlog: further improvements found along the way

(Larger ideas that are not scheduled yet. Small obvious fixes are done directly.)

- Hover highlight in edit mode (the node / way a click would pick), like
  JOSM's, so there is no guessing before clicking.
- The rotate gizmo also shows TransformControls' view-axis ring; only its
  heading effect is used. A custom single-ring gizmo would be clearer.
- A pending command's revert assumes no second gesture on the same nodes
  before JOSM answers (answers take a few ms locally).

- A full snapshot (connect / layer change) still costs ~0.5–1 s on the EDT
  for Karlsruhe without culling: `ds.ways.map { toSnapshot() }` plus
  `computeFull`, dominated by per-node allocations (`Pair`, boxed doubles).
  Snapshotting on the EDT and building features on the sender thread would
  take most of it off the UI.
- `one_way:<participant>` overrides (e.g. `one_way:bicycle=no`) could get a
  distinct arrow style; lanelet2 treats them per participant.
