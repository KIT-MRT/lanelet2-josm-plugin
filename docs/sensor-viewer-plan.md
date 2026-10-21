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

### M3 — aggregated scene cloud
- [ ] Voxel downsample + chunking + draw-distance LOD.
      220 frames x 740 k = 163 M points raw, so this is a downsampling problem,
      not a streaming one.

### M4 — remaining colourings
- [ ] Height colormap, per-sensor colour, camera-projected RGB.

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
