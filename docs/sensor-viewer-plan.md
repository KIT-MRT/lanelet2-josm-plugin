# Sensor data in the 3D viewer (point clouds, camera lock, image overlay)

Branch: `sensor-viewer`. Status: `[x]` done, `[~]` in progress, `[ ]` open.

Goal: look at a recorded driving scene and its Lanelet2 HD map in the same
3D view, to verify and correct the map against what the sensors saw.
Prime dataset: KITScenes Multimodal. Viewing works **without JOSM**; editing
stays JOSM's job (the viewer's existing streaming / edit path).

## Decisions

- **Sidecar, not a second viewer.** A new Python *scene server* connects to the
  viewer server's ingest port exactly like the JOSM bridge does and pushes the
  map as the same protocol v2 features. Point clouds and camera images go over
  its own HTTP port as binary, fetched directly by the browser.
  `server.py` stays stdlib-only and shipped in the jar; one browser codebase.
  - viewing without JOSM: `server.py` + scene server.
  - with JOSM: both are ingest clients — map from JOSM (editable), sensors from
    the scene server.
- **Lives in `tools/scene_server/`**, not in the jar. Own `uv` venv.
- **Python 3.12 + numpy 1.x.** The `lanelet2` wheel shipped with the kitscenes
  clone is compiled against NumPy 1.x and *crashes* on import under NumPy 2, and
  it has no cp313/cp314 build. Pinned: numpy 1.26.4, pyarrow 17, scipy 1.14,
  lanelet2 1.2.2.
- **lanelet2 python is the right choice for the map**: it reads `map.osm` with
  the scene's `maps/origin.json` projector, and hands over bounds already in
  driving direction plus its own centerline — the two things the Kotlin side had
  to re-implement.
- Point colouring: **intensity first (MVP)**, then height, per-sensor, camera RGB.
- Order after the first frame renders: **camera lock + overlay**, then the
  aggregated scene cloud.

## Coordinate chain (the part that must not drift)

```
lidar point (sensor frame, int32 x 0.005 m)
  -> T_to_reference        calib.json, sensor -> base_frame (== lidar_top here)
  -> deskew                per-point timestamp, pose interpolation (slerp+lerp)
  -> pose at t_ref         poses.txt, base_frame -> scene world frame
  == scene world frame     city-local UTM, origin = maps/origin.json
  -> viewer ENU            see below
```

The scene world frame is **lanelet2's `UtmProjector` frame**, the map is loaded
with the same projector, so map and points already share a frame — verified:
ground points near the ego sit at z≈144.59 where the map's `ele` is ≈144.6.

The viewer's own frame is the plugin's ENU (`Viewer3dEnu`): an equirectangular
local tangent plane about an anchor lat/lon. It is **not** the UTM frame:
at the scene's longitude the meridian convergence is ≈0.24° and the UTM scale
factor ≈0.9996, i.e. **4.3 m of rotation and 0.4 m of scale per kilometre**.
Converting map and points through different chains would misalign them by metres.

So: map nodes go `UtmProjector.reverse -> lat/lon -> ENU` exactly (4243 points,
cheap), and point clouds get the **local similarity transform** (rotation +
scale + offset) derived numerically from the projector at the scene origin,
which matches that chain to well under a millimetre over a scene.

## Milestones

### M0 — map without JOSM  [x]
- [x] `tools/scene_server/` package, `uv` venv bootstrap, CLI.
- [x] Load `maps/map.osm` + `maps/origin.json`, emit protocol v2 features:
      linestrings as `line`, lanelets as `lanelet` (left/right refs, two-way,
      arrow at 35 % of lanelet2's own centerline). 1929 features in 0.07 s.
- [x] Reuse the plugin's height rule: `|ele| > 100 km` is "no height"
      (this map also carries `-FLT_MAX`; traffic signs without height).
- [x] Connect to the viewer ingest port, send the snapshot, answer edit
      commands with a clear "read-only" refusal.
- [x] Adopt JOSM's anchor and leave the map alone when JOSM is already
      streaming one (reads only the head of `/state`).

### M1 — one frame, all 7 LiDARs (MVP)  [x]
- [x] Binary point format (`LL2PC1`): f32 xyz relative to a f64 origin,
      u8 intensity, u8 sensor id. 748 k points = 10.5 MB, 14 B/point.
- [x] `GET /scene.json`, `GET /frame/<i>/points.bin`, `/healthz`,
      `/frame/<i>/camera/<name>.jpg` (already there for M2).
- [x] Browser: point cloud layer (`THREE.Points` + shader), frame stepper and
      slider, play, point size, cache and prefetch of the next frame.
- [x] Colouring: intensity (MVP) and, since the data is already on the GPU,
      height and per-sensor.
- [x] `scene` message so a late-joining browser finds the scene server
      (kept by `server.py` and replayed in the bootstrap snapshot, like
      JOSM's selection).
- [x] `K` / `Go to car` puts the camera behind the vehicle.

**Verified on the example scene:** the cloud's ground under the car sits
**0.5 cm** from the map's `ele` there, through the whole chain and in the
browser (`testdata/viewer3d/live_scene.mjs`).

### M2 — camera lock + image overlay  [x]
- [x] Camera rig mode: the three.js camera matrix comes from the ego pose
      times the camera extrinsic (OpenCV -> three.js basis flip), and the
      projection matrix is built from the real intrinsics, principal point
      included -- no `fov` can express an off-centre principal point.
- [x] The render is letterboxed to the image aspect, so a rendered pixel *is*
      an image pixel. Verified: the render reproduces the recorded camera's
      pinhole projection to **0.0000 px** over a spread of test points.
- [x] Shift+L locks, `[` / `]` walk the ring (default front), `O` toggles the
      image, a slider sets the blend.
- [x] Navigation, the hold-to-move keys and hover are off while locked; the
      previous view is restored on release.
- [x] KITScenes ring images are already rectified (the calibration carries no
      distortion coefficients), so a pinhole is exact here. A fisheye dataset
      would need undistortion before this is meaningful.

**Known limit:** picking (hover, click-select) assumes the ordinary
projection and the full canvas, so it is wrong under the lock; that is why
hover is disabled there. Selecting while locked would need `screenProjector`
to learn about the viewport rectangle.

### Fixes found by using it
- [x] The scene bar reacted only to Tab+Enter: `#leftCol` is
      `pointer-events: none` and the new panel never opted back in.
- [x] Traffic sign / light / marking icons were missing whenever the viewer
      ran without JOSM: `resolve_icons_dir` never looked in the plugin's own
      `style_images`. Now found, and covered by a test.
- [x] Icons got a toggle (`N`, on by default) and an α slider, so they can be
      faded instead of hiding the cloud behind them.

- [x] Decoded frames are cached to disk (~1.3 s -> 0.15 s). Never in the
      dataset: `~/.cache/josm-lanelet2/scene-server/`, LRU-trimmed to 10 GB
      (a 220-frame scene is 2.3 GB). Cached in the *scene's* frame, so a
      change of ENU anchor does not invalidate it.

- [x] Stepping fast piled decodes on the server (8 at once -> 20 s each,
      the awaited frame last). Decoding is now serialised, identical requests
      share one decode, abandoned work is dropped, and the browser keeps one
      request in flight with the newest frame winning. First answer out of
      eight: 4.9 s instead of ~20 s. `--prewarm` fills the cache in the
      background for ~0.15 s stepping afterwards.
- [x] Aborted fetches no longer print a BrokenPipeError traceback per request.

- [x] Releasing the camera lock kept the pre-lock view, undoing the vantage
      point just lined up. It now hands the view over in place.
- [x] `Go to car` aimed 90 deg off: the viewer's yaw is `atan2(-dx, dy)`
      (0 = north) and it was written `atan2(dy, dx)`. It uses `lookAtPoint`
      now, which has no convention to get wrong.

### M3 — aggregated scene cloud  [x]
- [x] Voxel downsample: 163 M raw points -> 2.7 M at 0.15 m, 44 MB in 12
      spatial chunks, streamed one at a time so a frame the user asks for is
      never stuck behind them.
- [x] **Dynamic removal by voxel persistence.** Deskew fixes the *ego's*
      motion, not other vehicles': stacking frames leaves a chain of
      car-shaped blobs down the road. A voxel not seen in
      `--aggregate-min-run` **consecutive** frames is dropped.
      5.19 M occupied voxels -> 1.54 M static on the example scene.
- [x] Counting *distinct* frames was the first rule and leaks on a long
      scene: three different cars crossing one patch of road over 22 s also
      make three hits. 1.2 M voxels (44 % of what the count rule kept) were
      hit 3+ times without ever being seen 3 frames in a row. Measured, then
      replaced by the run rule. Foliage goes with it -- leaves move further
      than a voxel between frames -- and an "or seen often" escape clause
      re-admits as much traffic as it saves canopy (161 k vs 216 k voxels),
      so there is none.
- [x] Built on demand (`Shift+P`) with progress on the button, or
      `--aggregate-now`; cached as one `.npz`, reloaded in 0.07 s.
- [ ] Draw-distance LOD: not needed at 2.7 M points / 12 draw calls. The
      chunks carry centre and radius for when it is.

- [x] Showing the aggregate hides the single frame (it was drawn on top of
      it, which is the opposite of useful); `P` puts the frame back, tinted
      one solid colour against the aggregate's intensity, `U` toggles that.

**Fixed while testing it:** `P` hid the aggregate too, because the frame
cloud's switch toggled the *parent* group of both. The test had asserted the
child's own `visible` flag, which stays true under a hidden parent, so it
passed while the viewer was wrong; the hook now reports effective visibility.

### Aggregation performance  [x]
- [x] The scan re-sorted the whole voxel set every frame
      (`np.unique(concatenate(...))`, 46 % of the pass). Both arrays are
      sorted, so `merge_sorted` places instead: scan 134 s -> 51 s.
- [x] Per-frame work runs in processes (`--aggregate-workers`, default half
      the cores). Threads were measured at 1.3x -- the decode is Python-bound.
      Output is byte-identical at any worker count.
- [x] Warm build 192 s -> 45 s; cold 14 frames 47.5 s -> 12.8 s, so a new
      scene's first build goes from ~12 min to ~3.
- [ ] A GPU is not the next step: the remaining time is decode (Python, in
      the dataset's library), disk reads and a few numpy passes. cupy would
      pay off only once the decode is off the critical path.
- [ ] float32 for the range/ego masks was tried and reverted: 3 % faster and
      it moved one point in 1.5 M across the range boundary, which is not a
      trade worth making for reproducibility.

### M4 — remaining colourings  [x]
- [x] Height and per-sensor landed with M1.
- [x] **class**: Mapillary Vistas colours from the scene's seamseg masks.
      The ids are Vistas **trainIds** (stuff 0-27, things 28-64), confirmed
      against the example scene, where they decode to Poles, Traffic Signs,
      Street Lights, Cars, a Bus and a Person. COCO RLE is decoded here (no
      pycocotools); the official palette is generated from the upstream
      category list rather than typed in. ~4 % of a frame gets a class --
      only the front and rear cameras have masks, and only *things* are
      detected -- and the rest stays "unlabelled" grey rather than guessed.
- [x] **rgb**: colour sampled from all six ring cameras, ~90 % of a frame,
      decoded at half resolution through the JPEG's own DCT scaling.
- [x] Both cached per frame beside the points, so neither invalidated the
      2.2 GB of decoded frames already on disk. In the aggregate a voxel
      keeps its majority class and the mean of its camera colours.
- [x] **instance**: a colour per detected object, so several cars in one
      frame can be told apart. seamseg numbers objects *within one image*
      and nothing tracks them between frames, so this is a frame-only mode:
      the aggregate carries no instance channel and keeps its intensity
      colouring underneath. The rear camera's numbers are offset by 1000 so
      the two cameras cannot collide. Classes and instances come out of one
      pass over the masks; the instance channel is a uint16 (a byte would
      not hold the offset) cached as `inst-NNNNNN.bin`.

### M5 — filters and opacity  [x]
- [x] Class chips, built from the classes actually present in the loaded
      data, commonest first, capped at 14 so the panel stays a panel.
- [x] A `Vehicles` preset (cars, trucks, buses, bikes, motorcycles,
      trailers, caravans, riders). Offered as one click rather than applied
      on load: parked cars are a large part of what makes the aggregate
      recognisable, so hiding them by default would be a surprise.
- [x] Height above *the ground under the point*, not a flat z cut: a 4 m
      pillar grid of minimum z over every loaded cloud, written as a
      per-point attribute because each aggregate chunk has its own origin.
- [x] An intensity floor, which leaves markings and sign faces standing.
- [x] Point opacity, with depth writing turned off below 1 -- translucent
      points that write depth hide the map lines the slider exists to show.
- [x] All of it is shader uniforms plus one texture: a filtered point is
      pushed outside the clip volume. Dragging a slider refetches nothing
      and re-uploads nothing, which the e2e suite asserts.

### M6 — a whole city  [x]
Goal: every KITScenes scene of one city (all splits) in one viewer, on the
city-wide map (`--map`, or JOSM's), navigable at city scale; one scene at a
time is "the" scene with the existing frame / lock / overlay tools.

Facts this rests on:
- `data/sequence_archives.csv`: 1006 sequences in five splits (train 533,
  test 206, test-e2e 127, val 117, overlap_train_val 23), 1.7-3.7 GB each.
  No city column anywhere.
- Each scene's `maps/origin.json` is the *city's* origin (Frankfurt's centre
  for the example scene), and its `map.osm` is a crop. So a city is the set
  of scenes whose trajectory lies near one of the three cities the dataset
  names (Karlsruhe, Frankfurt, Sindelfingen); scenes are placed through
  their own origin, so differing origins would still line up.
- Only one scene is on disk here: everything multi-scene is tested with
  synthetic scenes.

Plan:
- [x] `ll2_scene/city.py`: find scenes under `KITSCENES_ROOT` (or `data/`,
      or one split), read only `poses.txt` / `origin.json` /
      `timestamp.reference.txt` (no sensor loader), assign a city, report
      tar-only (unextracted) scenes instead of silently skipping them.
- [x] One HTTP server for the city: `/city.json` (trajectories in viewer
      ENU), `/scene/<id>/...` = today's routes per scene, created lazily
      and LRU-evicted. One decode gate and one aggregate *build* gate
      city-wide, so ten scenes cannot start ten builds; cached aggregates
      load without waiting for the build gate.
- [x] A `city` message (kept and replayed by the hub like `scene`).
- [x] Viewer: trajectories as screen-space-width polylines, one colour per
      scene; pose nodes as oriented arrows (the 6D pose), sized with
      distance so they stay visible at city scale and fade far away.
- [x] Click a pose node (view mode) -> that scene becomes the scene, at
      that frame; everything from M1-M5 then applies. `Deselect` drops it.
- [x] Point data around the camera: per-scene static aggregates, loaded per
      128 m chunk within a radius (default 300 m), disposed again with some
      hysteresis once out of range. `Load here` loads on demand; `Auto`
      follows the camera. Default is on demand, so flying across the city
      costs nothing. Overlapping scenes simply add up.
- [x] `--build-aggregates`: build every scene's aggregate up front (cold
      builds are minutes per scene), so city browsing only ever reads cache.

Measured on the example scene served as a one-scene city, with its own map
standing in for a city map: in the browser 0.7 s after start; clicking the
trajectory opens frame 110; the city's trajectory and the scene's ego track
agree to 3.8 mm (cm rounding on the wire); map vs cloud still -0.5 cm;
`Load` brings the cached 1.54 M-point cloud in 3.2 s. 13 KB of trajectory a
scene, so a few hundred scenes are a few MB, gzipped.

Open:
- [ ] Only extracted scenes are served; archives are counted and skipped.
- [ ] Without `--map` or JOSM there is no map: merging the scenes' own map
      crops would need to know they share ids, which one scene cannot show.
- [ ] Not yet run on more than one real scene -- only one is on disk here.

## Measured facts (example scene `c34c778f…`, Frankfurt, val split)

| | |
|---|---|
| frames | 220 @ 10 Hz = 22 s, indices 0…219, no gaps |
| points | 773 k raw / ~740 k valid per frame, all 7 LiDARs |
| on disk | 3.99 MB parquet per frame (7 LiDARs), 4.7 GiB scene |
| decode | 1.2 s per frame via the official API (2-3 s under load); the deskew dominates, encoding is 0.02 s |
| wire | 8.9 MB xyz f32 + 0.74 MB intensity per frame |
| map | 4243 nodes, 1400 ways, 571 relations, 529 lanelets, 1.1 MB |
| ring cameras | 6 x 3504x2272, pinhole, f≈1841-1847 px, **no distortion** |
| ego | base_frame == lidar_top, 2.11 m above the ground |

## Known hazards (from the dataset's own docs and code)

- **Per-point timestamps are scene-relative seconds**, same clock as
  `poses.txt`. Four released sequences have inconsistent clocks (ns vs s) and
  are unusable; detect rather than guess.
- **Quaternions in `poses.txt` carry only 4 decimals** (norm 0.99994-1.00006):
  normalise.
- `lidar_top` has ~1.7 % valid points with `timestamp == 0`, which the official
  deskew leaves untouched. Harmless only because `lidar_top` *is* the base
  frame; guard anyway.
- Sentinel points (`reflectivity == -1` and `xyz == 0`) must be dropped.
- **The HD map was built from these poses**, so the overlay is a qualitative
  check, not an independent one. Documented pose attitude noise is a few mrad
  (worse on Hesai scenes), i.e. decimetres at 50 m.
- World coordinates reach ~6.3 km: subtract a local origin before float32.
