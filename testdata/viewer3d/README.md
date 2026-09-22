# 3D viewer browser checks

End-to-end checks for the browser half of the live 3D viewer
(`src/main/resources/lanelet2/viewer3d/`). They run the real `server.py`,
play the JOSM bridge on the ingest socket (sending scenes, recording the
commands the page posts back), and drive the page in headless Chrome over the
DevTools Protocol.

Not part of `./gradlew build`: they need Chrome and a display-less GL
(SwiftShader), which CI does not guarantee.

## Run

```bash
node testdata/viewer3d/e2e_viewer.mjs                 # camera + picking; Node 22+, no npm install
node testdata/viewer3d/e2e_edit.mjs                   # selection, moves, JOSM sync, refusals
node testdata/viewer3d/e2e_viewer.mjs --shots /tmp/v3d  # also save screenshots
CHROME=/path/to/chrome node testdata/viewer3d/e2e_viewer.mjs
```

Exit code 0 means every check passed.

Performance on a real map (streams the whole `.osm` as one snapshot and
reports load time, draw calls and frame time):

```bash
node testdata/viewer3d/perf_viewer.mjs ~/lanelet2_maps_autoware/karlsruhe_city/lanelet2_map.osm
LL2_VIEWER_DIR=/path/to/other/viewer3d node testdata/viewer3d/perf_viewer.mjs map.osm  # compare builds
```

## How the checks see the page

The page is opened with `?test=1`, which makes `app.js` expose
`window.__ll2test`: the renderer's own projection of a world point to a page
pixel, the exact camera state, the last orbit pivot and the selection. Use it
instead of parsing the HUD, whose one-decimal readout is ~10 px off at close
range.

The fake bridge answers every command that carries an `id` with an accepting
`command_result`; set `session.reply = (cmd) => ({ ok: false, message })` to
simulate JOSM refusing.

Ports are picked free per run, so a viewer already running on 8765/8766 does
not interfere.
