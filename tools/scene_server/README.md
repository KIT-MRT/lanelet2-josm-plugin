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

Then open <http://127.0.0.1:8765/>. The scene bar appears by itself:

| | |
|---|---|
| `Points` / `P` | show or hide the cloud |
| `Go to car` / `K` | camera behind the car at this frame |
| `◀` `▶|` / `,` `.` | step frames |
| `▶` | play |
| Colour | intensity, height or sensor |

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

## Routes

Read-only, CORS-restricted to the viewer's origin:

| route | returns |
|---|---|
| `GET /healthz` | scene id and frame count |
| `GET /scene.json` | the scene description (frames, times, ego track, camera calibration) |
| `GET /frame/<i>/points.bin` | all LiDARs of frame `i`, in viewer ENU — see `points.py` for the layout |
| `GET /frame/<i>/camera/<name>.jpg` | one ring camera image |

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
(−0.5 cm on the example scene).
