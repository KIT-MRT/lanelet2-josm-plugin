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
script against the plugin.

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

## Testing

```bash
./gradlew test                                   # JVM tests
python3 testdata/golden/run_golden.py            # Python backend regression corpus
```

The JVM tests deliberately avoid needing a running JOSM GUI, so they can run in CI.
Anything that genuinely requires a live JOSM instance is listed in the release
checklist instead.
