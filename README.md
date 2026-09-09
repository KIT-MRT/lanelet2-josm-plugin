# JOSM Lanelet2 Plugin

Lanelet2 map editing tools for [JOSM](https://josm.openstreetmap.de/), as a
native Kotlin plugin (`lanelet2.jar`). It replaces the Jython 2.7 script
collection that previously ran under the JOSM Scripting plugin.

Most tools are self-contained. Four actions (positive IDs, merge, split, debug
routing graph) also need a local Python `lanelet2` install; they warn at the
point of use if that sidecar is missing. Everything else works without it.

## Requirements

| You want… | You need |
|---|---|
| Core editing, styles, presets, 3D viewer, notes, git helpers | [JOSM](https://josm.openstreetmap.de/) **19555** or newer |
| Positive IDs, OSM merge/split, debug routing graph | The above, plus Python **3.8–3.12** and the upstream [`lanelet2`](https://github.com/fzi-forschungszentrum-informatik/Lanelet2) wheel (Linux) |
| Ad-hoc **Python 3** scripts against this plugin | The companion GraalPy plugin (`graalpy`) |
| Ad-hoc **Jython 2.7** scripts against this plugin | The [Scripting plugin](https://josm.openstreetmap.de/wiki/Help/Plugin/Scripting) with a Jython 2.7 engine |

## Install

`install.sh` copies `lanelet2.jar` into JOSM's default plugins directory. The
jar **must** keep that name — JOSM keys plugins off the filename.

```bash
# After a local build (./gradlew dist → build/dist/lanelet2.jar)
./install.sh

# A downloaded GitHub release jar (must stay named lanelet2.jar)
./install.sh /path/to/lanelet2.jar
```

The script follows JOSM's own user-data rule:

| OS | Plugins directory |
|---|---|
| Linux | `~/.josm/plugins` if that legacy home still exists, otherwise `${XDG_DATA_HOME:-~/.local/share}/JOSM/plugins` |
| macOS | `~/Library/JOSM/plugins` |
| Windows | `%APPDATA%\JOSM\plugins` |

Override with `--dir DIR` or `JOSM_PLUGIN_DIR`. If the plugins directory (or
the JOSM user-data home around it) does not exist, the script **warns** —
JOSM has probably never been started on this account — then creates the
directory and copies the jar anyway.

Restart JOSM and enable **lanelet2** under *Edit → Preferences → Plugins* on
the first install.

## What it ships

Menus appear at JOSM startup (**Lanelet2 Utils**, **Lanelet2 Map**). Extra
toolbars appear once a data layer is open; hide or show them with the **LL2**
toggle on JOSM's main toolbar, or *Lanelet2 Utils → Show Lanelet2 toolbars*.
Settings live in *Lanelet2 Utils → Lanelet2 Settings* and on the native
**Lanelet2** tab under *Edit → Preferences*.

### Lanelets

- Create lanelet(s) from a left/right linestring pair
- Merge a shared border (two lanelets) or merge swapped left/right bounds
- Split bidirectional lanelets on the virtual centerline; split ways at selected nodes
- Smooth the centerline from the borders, or from a selected centerline
- Revert lanelet direction; check borders (selects broken ones)
- Delete relations (drop memberships) or purge relations including member ways and nodes

Delete/purge use the **current JOSM selection**, not the collection dialog.

### Regulatory elements

Wizards for traffic lights, traffic signs, speed limits, and right-of-way;
add an existing regulatory element to lanelets; convert bike traffic lights;
visualize regulatory connections and the right-of-way wizard.

Select-from-linestring and the traffic-light / sign / speed-limit wizards use
the current selection by default. Turn on **Use collection dialog** in settings
to collect members interactively instead. Right-of-way create/debug always open
the collector (two role groups cannot be expressed as a flat selection).

### Selection and tagging

- Select lanelets or relations from selected linestrings
- Quick-tag modal (Space)
- Toolbar defaults for new-lanelet subtype (`road` / `bicycle_lane` / `crosswalk`) and one-way vs bidirectional
- Autotag new elements (`file_origin` and friends) and a zoom-based filter hook
- Highlight file-boundary hulls (grouped by `file_origin`)
- Load an older git revision of the current file
- Notes panel (custom geolocated notes)

### Map appearance

Bundled MapCSS styles and tagging presets (including traffic-sign icons)
install on launch. Choose the style preset from the settings window.

### Maintenance

- Filter broken lanelets and regulatory elements (writes a backup and asks before applying)
- Git commit of the current file (optional save reminder)
- Routing-graph refresh hook (used with the debug graph)

### Live 3D viewer

*Lanelet2 Utils → Live 3D Viewer* starts a local stdlib `python3` server and
opens a Three.js view of the loaded map. It does **not** need the `lanelet2`
Python package. The browser client vendors [three.js](https://threejs.org/)
r160 (MIT, Copyright 2010–2023 Three.js Authors) under
`src/main/resources/lanelet2/viewer3d/static/vendor/` so the viewer works
offline.

### Needs the `lanelet2` sidecar

These four call the upstream library through a private virtualenv
(`$XDG_DATA_HOME/josm-lanelet2/venv`, or `~/.local/share/josm-lanelet2/venv`).
Use *Set up Lanelet2 backends* in the settings window.

- Make Positive IDs
- Merge OSM Files
- Split Merged OSM File
- Generate Debug Routing Graph (and the small-graph variant)

The upstream wheel is Linux-only and supports Python 3.8–3.12. Point the
wizard's *Advanced* section at another interpreter to use your own.

## Building from source

Requires JDK 21. The build compiles against JOSM 19555 (also the minimum
runtime). The repo must have at least one git commit: the JOSM Gradle plugin
reads `HEAD` when writing the plugin manifest.

```bash
./gradlew build          # compile + test (also produces build/dist/lanelet2.jar)
./gradlew dist           # plugin jar only
./gradlew runJosm        # launch JOSM with this plugin loaded
./gradlew debugJosm      # same, waiting for a debugger on port 1740
./install.sh             # copy build/dist/lanelet2.jar into the real JOSM plugins dir
```

A GitHub release is a tag `v0.1.0` (no `v` in the plugin version itself).
That runs `.github/workflows/release.yml`, which builds with
`RELEASE_VERSION` from the tag and uploads `lanelet2.jar`. Keep that
filename — JOSM keys plugins off it.

`runJosm` uses `build/.josm/userdata`, so it does not touch your everyday JOSM
profile. The sidecar venv stays outside that tree on purpose (`./gradlew clean`
must not delete a 100+ MB pip install).

Because a JOSM plugin is a single jar and JOSM does not ship the Kotlin
runtime, the plugin packs the Kotlin stdlib.

## Testing

```bash
./gradlew test                                   # JVM tests (headless)
python3 testdata/golden/run_golden.py            # Python backend regression corpus
```

`testdata/golden/` pins the behaviour of the four `lanelet2` backends. The JVM
suite does not need a running JOSM GUI.

## Scripting

Ad-hoc scripts can call this plugin's `Lanelet2Extensions` facade. Two
in-process engines work; they share the live JOSM `DataSet`, not a file
round-trip.

| | Companion GraalPy plugin (`graalpy`) | [Scripting plugin](https://josm.openstreetmap.de/wiki/Help/Plugin/Scripting) |
|---|---|---|
| Language | **Python 3** (GraalPy 25, currently 3.13) | **Jython 2.7** only |
| How to run | *Tools → Run Python file…* (also bundled hello / centroid) | *Scripting → Run Script* after adding a Jython engine jar |
| NumPy / PyData | Yes (GraalPy wheels; NumPy ships with that plugin) | No |
| This plugin's classes | After both plugins load — see below | After both plugins load — see below |

This plugin injects its classloader into both hosts (`scripting` and
`graalpy`). The GraalPy plugin also pulls every other `PluginClassLoader`
into its own. Either direction is enough; doing both covers load order.
Startup still succeeds if neither script engine is installed.

```
from org.openstreetmap.josm.plugins.lanelet2.api import Lanelet2Extensions
```

### Python 3 via GraalPy

The companion GraalPy plugin evals Python 3 inside the same JVM (Polyglot /
GraalPy), with `from org.openstreetmap.josm…` imports and optional NumPy.
It is a separate plugin (`graalpy`); this one does not embed GraalVM.

1. Load **lanelet2** and **graalpy** in the same JOSM.
2. Open a data layer, then *Tools → Run Python file…* and pick
   `examples/graalpy/hello_lanelet2.py`.

That example is Python 3 (`f"…"`, generator expressions). It imports
`Lanelet2Extensions` the same way as the Jython script and reports the
current selection. Load both plugins in one JOSM (`./install.sh` in each
repository).

Ideas that need SciPy / Shapely / NetworkX ship with the GraalPy plugin
under its `inspiration/` directory.

### Jython 2.7 via the Scripting plugin

Scripting plugin v0.4.x does not bundle Jython; add a `jython-standalone` jar
under *Preferences → Scripting → Script engines* first.

1. Install this plugin and the Scripting plugin, then add a Jython 2.7 engine.
2. Restart JOSM.

`examples/jython/hello_lanelet2.py` registers a **Hello Lanelet2 (example)**
item on *Lanelet2 Utils*. The same file ships inside the jar: *Lanelet2 Utils →
Copy example script to…*, then *Scripting → Run Script*. Do not use Python 3
syntax in this file.

```
from org.openstreetmap.josm.plugins.lanelet2.api import Lanelet2Extensions
```

| Method | Purpose |
| --- | --- |
| `register(slotId, title, callback, anchor)` | Add a menu item. `callback` is a zero-arg function (SAM). |
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

Jython scripts must stay on Python 2 syntax. GraalPy scripts use Python 3.
JOSM's own `getBoolean` treats only `"true"` as true — always go through
`Lanelet2Extensions.settings()` for plugin keys.

## License

GPL-3.0-or-later (see [LICENSE](LICENSE)), Copyright (C) 2026 Karlsruhe
Institute of Technology (KIT), Institute of Measurement and Control Systems
(MRT). This plugin runs inside JOSM (licensed GPL-2.0-or-later), so it is
licensed GPLv3 to stay compatible with JOSM's "or later" license.

Maintainer: Richard Schwarzkopf (`schwarzkopf@fzi.de`).

The live 3D viewer vendors [three.js](https://threejs.org/) r160 (MIT,
Copyright 2010–2023 Three.js Authors). Those files keep their own license
headers under `src/main/resources/lanelet2/viewer3d/static/vendor/`.
