# Scene server — recorded sensor data in the 3D viewer

Streams a recorded driving scene (KITScenes Multimodal) into the plugin's
Three.js viewer: the Lanelet2 HD map, the LiDAR point clouds and the ring
camera images, all in one 3D view, so a map can be checked and corrected
against what the sensors saw.

It is **not** part of the JOSM plugin jar. It is a separate process with its
own virtualenv, because it needs numpy/pyarrow/scipy/lanelet2 while the
shipped viewer server is deliberately stdlib-only.

```
browser  <-- static page + SSE --  server.py (shipped with the plugin)
   |                                   ^            ^
   |                                   | ingest     | ingest
   |                            scene_server    JOSM plugin (optional)
   '------- HTTP, binary --------------'
            points, images
```

The scene server connects to the viewer's ingest port exactly like the JOSM
bridge, so the viewer needs no knowledge of sensors. **Viewing works without
JOSM**; editing does not — the scene server refuses edit commands, and the
viewer shows the refusal and puts the change back.

## Setup

Needs [uv](https://docs.astral.sh/uv/) and a checkout of the
[kitscenes](https://github.com/KIT-MRT/kitscenes) API next to this repository
(it carries the `lanelet2` wheel):

```bash
./setup.sh                       # or KITSCENES_API_PATH=/path/to/kitscenes ./setup.sh
```

Python 3.12 and NumPy 1.x are forced: the `lanelet2` wheel is built for
cp38–cp312 against NumPy 1.x and aborts on import under NumPy 2.

## Run

```bash
# 1. the viewer (shipped with the plugin; no JOSM needed)
python3 ../../src/main/resources/lanelet2/viewer3d/server.py

# 2. the scene
.venv/bin/python -m ll2_scene --scene /path/to/<scene-uuid>
```

Then open <http://127.0.0.1:8765/>. The scene's controls appear by
themselves: a timeline under the view, a camera bar over it, and the side
panel turns to its *Sensor data* tab.

| | |
|---|---|
| timeline `◀` `▶|` / `,` `.` | step frames; the slider scrubs |
| timeline `▶` | play |
| timeline *Car* / `K` | camera behind the car at this frame |
| timeline *Follow* / `Shift+K` | keep the camera where it is relative to the car while the frames change (it turns with the car) |
| *This frame* / `P` | show or hide the single frame's cloud |
| Colour | intensity, height, sensor, class, rgb, instance, with a legend |
| *Classes* | one chip per class present: hide it, or show *Only* the ticked ones |
| *Filter* | a height ceiling and an intensity floor; the header counts what is active |
| *Whole scene* / `Shift+P` | the whole scene as one static cloud (hides the single frame) |
| *Tint this frame over it* / `U` | paint the single frame one bright colour against the aggregate |
| camera bar: a ring button, *Camera view* / `Shift+L` | put the view exactly where that ring camera was; the lit button or `Esc` lets go |
| `[` `]` | walk the six ring cameras |
| camera bar: image button / `O`, slider | blend the recorded image over the render |

A map the scene server sends itself is marked `"source": "scene_server"` in
its snapshot, so the viewer greys out *Edit* (and says why) instead of
letting edits be refused one by one.

Releasing the lock leaves the camera **where it is**, so you keep the vantage
point you locked on to and just lose the image; only the roll is dropped,
because the free camera is yaw/pitch about a fixed world up.

With JOSM also connected, the scene server notices that a map is already
being streamed, adopts its ENU anchor and leaves the map alone — so the map
stays JOSM's (and editable) while the sensor data comes from here.

### Options

| flag | meaning |
|---|---|
| `--scene PATH` | the scene directory (the one holding `poses.txt`) |
| `--viewer-host`, `--viewer-port`, `--ingest-port` | where the viewer server is (`127.0.0.1:8765`/`8766`) |
| `--http-port` | this server's data port (default 8767, `0` picks a free one) |
| `--no-map` | never send the map, even if nothing else is streaming one |
| `--map-only` | send the map and serve no sensor data |
| `--kitscenes PATH` | a kitscenes checkout (else `KITSCENES_API_PATH`, else `../../../kitscenes`) |
| `--cache-dir PATH` | where decoded frames are cached (default below) |
| `--no-cache` | decode every frame afresh |
| `--cache-limit-gb N` | trim the cache to N GB, least recently used first (default 10) |
| `--clear-cache` | drop this scene's cached frames before starting |
| `--prewarm` | decode the whole scene into the cache in the background |
| `--aggregate-voxel M` | voxel size of the whole-scene cloud (default 0.15 m) |
| `--aggregate-min-run N` | a voxel must be seen in N **consecutive** frames to count as static (default 3; `1` keeps the traffic) |
| `--aggregate-range M` | only aggregate returns within this range of the car (default 60 m) |
| `--aggregate-step N` | use every Nth frame |
| `--aggregate-now` | build it at startup instead of on demand |
| `--aggregate-workers N` | processes used to build it (default: half the cores) |
| `--decode-workers N` | frames decoded at once (default 1, see below) |

## Cache

Decoding a frame costs ~1.3 s (7 parquet files, sentinel filter, per-point
deskew, pose interpolation), so frames are cached on disk: **0.15 s** on a
hit, about 10x faster.

**The dataset is never written to.** The cache lives in
`$XDG_CACHE_HOME/josm-lanelet2/scene-server/<scene-id>-v<n>/`, i.e.
`~/.cache/josm-lanelet2/scene-server/...` by default; it is safe to delete at
any time, and it is trimmed to `--cache-limit-gb` (10 GB) by dropping the
least recently used frames. A full 220-frame scene is about 2.3 GB.

### Colouring by class, by camera, by object

Three more colour modes appear in the menu once the data carries them:

**class** paints each point with its Mapillary Vistas colour, taken from the
scene's own `processed/seamseg_instances/` masks
([seamseg](https://github.com/mapillary/seamseg) trained on Vistas v1.2).
Cars come out Vistas blue, people crimson, bicycles dark red, poles grey, and
so on — the official palette, generated from the upstream category list
rather than typed in.

The mask JSON gives a class id, an objectness, a box and a COCO
compressed-RLE mask per instance. The ids are **trainIds** in the 65-class
taxonomy ordered stuff first (0–27) then things (28–64); confirmed against
this scene, where they decode to a street full of Poles, Traffic Signs,
Street Lights, Cars, a Bus and a Person. Only the front and rear cameras have
masks, so about 4 % of a frame gets a class and everything else stays
"unlabelled" grey rather than being guessed at. Where two instances overlap,
the more confident one wins.

**rgb** samples the colour straight out of the ring camera images — all six
of them, so about 90 % of a frame gets a real colour (the rest keeps the same
neutral grey). Images are decoded at half resolution, which a JPEG can do
from its DCT coefficients at about four times the speed, and a point needs
only one pixel.

**instance** gives every detected object its own colour, so three cars
parked in a row are three colours instead of one blue mass. seamseg numbers
the objects it finds *within one image* and nothing links them between
frames, so this is a **single-frame** mode: the numbers are per frame (the
rear camera's are offset so the two cameras cannot collide), and the
aggregate keeps its intensity colouring underneath rather than going flat
grey. The colour is a hash of the number — the golden ratio on the hue, with
saturation and value jittered so neighbours that land on a similar hue still
separate.

All three are computed once per frame and cached beside the decoded points
(`class-NNNNNN.bin`, `rgb-NNNNNN.bin`, `inst-NNNNNN.bin`), which is why they
did not invalidate the frames already on disk; classes and instances come out
of a single pass over the masks. In the aggregate each voxel keeps the class
most of its points voted for and the mean of their camera colours — and no
instance number, since there is nothing to carry over.

### Filters and opacity

*Classes* and *Filter* sit in the *Sensor data* tab. Everything in them is a shader
uniform: a filtered point is pushed outside the clip volume, so dragging a
slider re-renders but uploads nothing and refetches nothing.

| | |
|---|---|
| class chips | one per class actually present, commonest first; click to hide |
| `Hide` / `Only` | what a ticked class means: hidden, or the only classes drawn. *Only* with nothing ticked filters nothing |
| `Vehicles` | preset: ticks cars, trucks, buses, bikes, motorcycles, trailers, caravans and their riders — hidden, or with *Only* the only thing left |
| `Above ground` | drop everything higher than *n* m over the ground under it |
| `Intensity` | drop everything below a reflectivity floor — markings and signs survive it |
| opacity | fade the points so map lines, nodes and icons behind them stay readable |

The height filter needs a ground, which is computed once from the loaded
clouds: a 4 m grid holding the lowest point in each pillar, written into the
geometry as a per-point attribute (each aggregate chunk has its own origin,
so a uniform could not express it). It follows a slope, unlike a flat z cut.

Point opacity below 1 also turns depth writing off, since translucent points
that write depth hide whatever is behind them, which is the whole point of
turning the opacity down.

### Why one decode at a time

`ThreadingHTTPServer` gives every request its own thread, so a few taps on the
step key used to put eight decodes on the machine at once. Measured: one frame
alone takes 3.4 s, eight at once take **20 s each** — the frame being waited
for arrived last. Now decoding is serialised (`--decode-workers`, default 1),
requests for the same frame share one decode, and a request whose tab has
navigated away is dropped before it starts. First answer out of eight: 4.9 s
instead of ~20 s.

The browser half matches it: one request in flight, newest frame wins. Holding
the step key no longer asks for the frames it passes over — it jumps to where
you stopped.

Raising `--decode-workers` gives better bulk throughput (8 at once decode ~1.8x
more frames per second overall) at the cost of every individual answer, so it
is worth it for `--prewarm` and not for stepping.

### The whole scene as one cloud

`Shift+P` in the viewer (or `--aggregate-now`) stacks every frame into one
cloud. Two things make that usable:

**Voxel downsampling.** Points are binned to `--aggregate-voxel` metres and
replaced by the centroid of the bin. The example scene: 163 M raw points ->
**2.7 M**, 44 MB on the wire in 12 spatial chunks.

**Dynamic removal.** `kitscenes` deskews each sweep against the *ego* pose, so
the car's own motion during the ~100 ms sweep is compensated — but another
vehicle driving past is a rigid body moving on its own, and lands somewhere
different in every frame. Stacking leaves a chain of car-shaped blobs down the
road. A static surface is hit frame after frame as the car approaches; a
moving vehicle occupies a voxel for an instant. Keeping only voxels seen in at
least `--aggregate-min-run` **consecutive** frames removes the traffic with no
labels, in every direction, at the resolution the cloud is binned to anyway.

*Consecutive*, not merely often: counting distinct frames looks right on a
short clip and leaks on a long one, because three different cars crossing the
same patch of road over 22 s also add up to three hits. Measured over the
example scene's 220 frames, **1.2 M voxels — 44 % of everything a hit count
keeps — were hit three times or more without ever being seen in three
consecutive frames**, and they are the car blobs strung down the road.

On the example scene: **5.19 M occupied voxels, 1.54 M static**. What it also
removes is wind-blown foliage, because leaves move further than a voxel
between frames. Letting those back in with an "or seen in many frames" clause
re-admits a comparable amount of traffic (161 k traffic-height voxels against
216 k canopy-height ones), so there is no clean escape.
`--aggregate-min-run 1` keeps everything.

Building is cached as a single `.npz` next to the frames, so later runs load
it in 0.07 s. The settings are in the file name, so changing them builds a new
one rather than serving a stale aggregate.

#### What it costs, and why

On the example scene (220 frames, 8 cores), warm frame cache:

| | |
|---|---|
| originally | 192 s |
| merging without re-sorting | 109 s |
| + 8 worker processes | **45 s** |

Two things were in the way. The scan merged each frame's voxels into the
running set with `np.unique(concatenate(...))`, i.e. **a full sort of five
million keys per frame** — 46 % of the pass. Both arrays are already sorted,
so the merge is a placement instead: 4.6x faster on that step, and the scan
went 134 s -> 51 s.

The rest is per-frame work that does not depend on other frames, so it runs
in processes (threads buy little here: the dataset's decode spends its time
in Python, and 8 threads measured only 1.3x). The scan consumes their results
in order because a *run* of consecutive frames is sequential by nature; the
gather splits frames into blocks and adds up the partial sums, which is exact
— the output is byte-identical at any worker count, and there is a test for
that.

Cold, where every frame must be decoded, the win is bigger: 14 frames went
47.5 s -> 12.8 s, so a first build of a new scene drops from roughly twelve
minutes to three.

Workers compete with frame serving while they run, which is why the default
is half the cores rather than all of them.

**A GPU would not help much yet.** The remaining time is spread across the
decode (Python, in the dataset's own library), disk reads and a handful of
numpy passes; the sorts that a GPU is good at are now a small share. Porting
the voxelisation and scatter-adds to cupy would pay off only after the decode
itself moves off the critical path.

Showing the whole scene puts the single frame away, since the point of it is
to see the scene without one frame's clutter on top. `P` brings the frame
back over it, painted one bright colour (`U` toggles that) so the live frame
and the aggregate can be told apart at a glance — the aggregate keeps the
intensity (or height, or sensor) colouring. Hiding the aggregate again
restores whatever the frame was doing before.

### Prewarming

`--prewarm` walks the scene in the background and fills the cache, one frame
at a time and only while no browser is waiting, so it never costs you a step
(at worst you wait out the one frame already being decoded). About 3.8 s a
frame, so a 220-frame scene takes ~14 minutes, after which every frame answers
in ~0.15 s.

Frames are cached **in the scene's own metric frame**, not in the viewer's
ENU, so the cache stays valid when the ENU anchor changes — which happens as
soon as JOSM connects and its anchor is adopted. Only the cheap 0.12 s
re-projection is redone. Positions are stored as float32 relative to a local
origin, which costs at most 0.034 mm against source data quantised to 5 mm;
intensity and sensor id come back exact.

## A whole city

```bash
# every scene of a city (all splits) on the city-wide map, without JOSM
.venv/bin/python -m ll2_scene --city $KITSCENES_ROOT --map /path/to/city/lanelet2_map.osm

# or with the map already open in JOSM (3D viewer running): leave out --map
.venv/bin/python -m ll2_scene --city $KITSCENES_ROOT

# once, ahead of time: build every scene's static cloud into the cache
.venv/bin/python -m ll2_scene --city $KITSCENES_ROOT --build-aggregates
```

`--city` takes `KITSCENES_ROOT`, its `data/`, or a single split. The dataset
has no city column anywhere, but every scene's `maps/origin.json` is its
city's origin and its trajectory says where it drove, so each scene goes to
the nearest of the three cities the dataset names (Karlsruhe, Frankfurt,
Sindelfingen). By default the city with the most scenes is served;
`--city-name` picks another, and the log lists what else is there. Archives
that were never extracted are counted and skipped — nothing can be served
from a tar.

In the viewer:

| | |
|---|---|
| tracks | every scene's ego trajectory: a line, a dot per frame, an arrow per frame for the 6D pose once close enough to read one. One colour per scene |
| hover a track | scene id, split and frame under the pointer |
| click a dot, arrow or line | that scene becomes *the* scene, at that frame; everything above (frames, camera lock, overlay, whole scene, colours) then works as for a single scene. The camera stays put; `K` goes to the car |
| the city in the breadcrumb | back to the city alone |
| the scene list | every scene, nearest the camera first, filtered by id or split; a click opens it and goes to its car |
| `Load 300 m` | the static clouds of every scene within the radius of the camera; scenes whose cloud is not built yet are built now (one at a time, minutes each) |
| `Auto` | keep loading around the camera as it moves — but only clouds that are **already built**: flying across a city should never start a build |
| Radius | how far around the camera; clouds beyond 1.3× of it are dropped again |
| `Unload`, *Trajectories* | drop every city cloud; hide the trajectories |

Clicking works in view mode; in edit mode a click belongs to the map. With
no scene picked, the *Sensor data* tab keeps colour, size, classes and
filters, which act on the city's clouds. Clouds from overlapping scenes simply
add up, which is the point: one street driven by five scenes is five
scenes' worth of static points. While the picked scene shows its own
whole-scene cloud, its city copy is hidden so nothing is drawn twice.

The trajectories are the pose of the base frame, which is `lidar_top` —
2.1 m above the road, which is where they are drawn.

**Disk.** A whole-scene cloud is ~31 MB in the cache at the default settings,
so a city of 300 scenes is ~9 GB of them. Those are never trimmed (frames are,
to `--cache-limit-gb`, shared by every scene of the city); delete the
`aggregate-*.npz` files to reclaim the space.

**One at a time, city-wide.** All scenes share one decode gate and one
aggregate-build gate, so loading ten scenes cannot start ten builds; a cached
cloud never waits behind a build. Scenes are opened on first use and the
least recently used idle ones are closed beyond `--max-open-scenes` (8).

## Routes

Read-only, CORS-restricted to the viewer's origin:

| route | returns |
|---|---|
| `GET /healthz` | scene id and frame count |
| `GET /scene.json` | the scene description (frames, times, ego track, camera calibration) |
| `GET /frame/<i>/points.bin` | all LiDARs of frame `i`, in viewer ENU — see `points.py` for the layout |
| `GET /frame/<i>/camera/<name>.jpg` | one ring camera image |
| `GET /aggregate/meta.json` | the whole-scene cloud's chunks; 202 with progress while it builds |
| `GET /aggregate/<n>.bin` | one 128 m chunk of it |

In city mode the same routes live under `/scene/<id>/`, next to:

| route | returns |
|---|---|
| `GET /city.json` | every scene's trajectory in viewer ENU (gzipped), and the Vistas class table |
| `GET /city/status.json` | per scene: cloud built, building, open |

## Coordinates

This is where a viewer like this goes wrong, so it is spelled out in
`ll2_scene/enu.py`. Short version: the scene's metric frame is lanelet2's
`UtmProjector` frame and the map is loaded through the same projector, so the
two already agree; the viewer's ENU is a different thing (turned by the
meridian convergence, ~0.25°, and anisotropically scaled), and **everything**
goes through one exact chain into it. A fitted local affine was tried and
drifts 57 mm at 400 m — the size of the errors this tool exists to find.

## Tests

```bash
.venv/bin/python -m unittest discover -s tests -t .
```

Pure checks of the coordinate chain (including that it reproduces lanelet2's
own projector), the wire format and the map rules. The browser half is
covered by `testdata/viewer3d/e2e_scene.mjs`, which fakes this server; to
check a real scene end to end:

```bash
node ../../testdata/viewer3d/live_scene.mjs /path/to/<scene-uuid> --frame 110
```

which also prints how far the point cloud's ground sits from the map's `ele`
(−0.5 cm on the example scene). City mode has the same pair:
`testdata/viewer3d/e2e_city.mjs` (fakes four scenes) and

```bash
node ../../testdata/viewer3d/live_city.mjs $KITSCENES_ROOT --map city.osm --load
```

which opens a scene by clicking its trajectory, checks the trajectory against
the scene's own ego track (3.8 mm on the example scene: positions travel
rounded to the centimetre) and loads the clouds around it.
