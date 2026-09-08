# JOSM Lanelet2 Plugin

Lanelet2 map editing tools for [JOSM](https://josm.openstreetmap.de/), as a native
Kotlin plugin. This replaces the Jython 2.7 script collection that previously ran
under the JOSM Scripting plugin.

Ships as a **single plugin** (`lanelet2`). Features that need the upstream
`lanelet2` Python library (positive IDs, merge, split, debug routing graph) warn
at the point of use if Python is missing or misconfigured; everything else works
without it.

`testdata/golden/` holds a regression corpus that pins the behaviour of the Python
lanelet2 backends. `examples/jython/` holds example scripts for users who want to
script against the plugin (also shipped inside the jar; see [Scripting](#scripting)).

## Building

```bash
./gradlew build          # compile + test
./gradlew runJosm        # launch JOSM with the plugin loaded
./gradlew debugJosm      # same, waiting for a debugger on port 1740
./gradlew localDist      # produce a plugin update site under build/localDist/
```

Requires JDK 21. The build compiles against JOSM 19555, which is also the minimum
supported version. The repo must have at least one git commit: the JOSM Gradle
plugin reads `HEAD` when writing the plugin manifest.

To install into a real JOSM, add the generated `build/localDist/list` as a plugin
update site (Preferences -> Plugins, expert mode), or copy
`build/dist/lanelet2.jar` into `~/.josm/plugins/`.

Because a JOSM plugin ships as a single jar and JOSM does not provide the Kotlin
runtime, the plugin packs the Kotlin stdlib.

## Python backends

Four features — positive IDs, merging input OSM files, splitting merged files, and
the debug routing graph — run through the upstream `lanelet2` Python library rather
than reimplementing its map semantics. The plugin ships those backend scripts inside
its jar and manages a private virtualenv for them; see the in-app
*Set up Lanelet2 backends* wizard. Everything else in the plugin works without Python.

The upstream `lanelet2` wheel is Linux-only and supports Python 3.8 to 3.11.

The virtualenv lives at `$XDG_DATA_HOME/josm-lanelet2/venv`
(`~/.local/share/josm-lanelet2/venv` when `XDG_DATA_HOME` is unset), outside
JOSM's user data directory so that `./gradlew clean` cannot discard it. Point the
wizard's *Advanced* section at an existing interpreter to use your own instead.

The 3D viewer is independent of all this: its server is Python-standard-library
only and runs on the system `python3`, so it works even when the `lanelet2`
install is missing or broken.

## Testing

```bash
./gradlew test                                   # JVM tests
python3 testdata/golden/run_golden.py            # Python backend regression corpus
```

The JVM tests deliberately avoid needing a running JOSM GUI, so they can run in CI.
Anything that genuinely requires a live JOSM instance is listed in the release
checklist instead.

## Scripting

Users can keep writing small ad-hoc scripts against this plugin via the
[JOSM Scripting plugin](https://josm.openstreetmap.de/wiki/Help/Plugin/Scripting).
The supported engine is **Jython 2.7** (Python 2 syntax). Scripting plugin v0.4.x
is GraalVM-based and does **not** bundle Jython; add a `jython-standalone` jar
under *Preferences → Scripting → Script engines* first.

### Enable it

1. Install this plugin and the Scripting plugin.
2. Add a Jython 2.7 engine jar as above.
3. Restart JOSM (or load both plugins at runtime). This plugin injects its
   classloader into the Scripting plugin so `import` can see our classes. If
   the Scripting plugin is absent, startup still succeeds; only script imports
   of our API will fail.

### Example script

`examples/jython/hello_lanelet2.py` registers a **Hello Lanelet2 (example)**
item on the *Lanelet2 Utils* menu. Clicking it reports how many lanelets are
in the current selection (including those inferred from selected linestrings),
their combined centerline length in metres, and the plugin's default subtype.

The same file ships inside the plugin jar. Extract it with
*Lanelet2 Utils → Copy example script to...* (toolbar short label **Copy Ex**),
then run the copy via *Scripting → Run Script*. Running the script once adds
the menu item; it does not need to stay open.

This script is also the manual acceptance test for classloader injection: if
it imports `Lanelet2Extensions` and the new menu item appears, the bridge
works. Automating that would require a live JOSM plus Jython in CI.

### The `Lanelet2Extensions` facade

```
from org.openstreetmap.josm.plugins.lanelet2.api import Lanelet2Extensions
```

| Method | Purpose |
| --- | --- |
| `register(slotId, title, callback, anchor)` | Add a menu item. `callback` is a Jython function (SAM). |
| `unregister(slotId)` | Remove a previously registered item. |
| `after(slotId)` / `before(slotId)` | Anchor relative to a first-party slot. |
| `anchors()` | Stable slot-ids you can pass to `after` / `before`. |
| `settings().get/put/getBoolean/putBoolean/getInt/putInt` | Plugin prefs. Booleans are `"1"` / `"0"`. |
| `geometry().centerlineOf(lanelet)` | Centerline vertices (lon, lat). |
| `geometry().centerlineLengthMeters(lanelet)` | Great-circle length in metres. |
| `geometry().calculateCenterlinePoints(left, right)` | Centerline from two polylines. |
| `geometry().wayMiddlePoint(pts)` | Middle vertex (Jython index `len // 2` quirk). |
| `lanelets().fromSelection(data)` | Lanelets from the dataset's current selection. |
| `lanelets().fromCurrentSelection()` | Same, on the active edit layer. |
| `lanelets().wrap(relation)` | Wrap a `type=lanelet` relation. |

Do not use Python 3 syntax (`f"..."`, `async`, walrus `:=`, type annotations).
`print x` is a statement in Jython 2.7; prefer Swing dialogs as the example
does. JOSM's own `getBoolean` treats only `"true"` as true — always go through
`Lanelet2Extensions.settings()` for plugin keys.
