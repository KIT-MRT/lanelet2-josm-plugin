# Working on this plugin

Kotlin JOSM plugin porting the Jython 2.7 script collection at
`/ll2_tooling_root/JOSM_lanelet2_editing_scripts/` (read-only; read its own
`AGENTS.md` for the tier layout). Ships as **one** plugin, `lanelet2`.

## Reference sources (read them, do not guess)

- **JOSM:** `/ll2_tooling_root/josm/src/` (read-only)
- **lanelet2 C++ upstream:** `/ll2_tooling_root/ws_ll2_mapping_hiwis/src/lanelet2/`
  (read-only). The authority for *map semantics*. Several Jython modules are
  hand-ports of it, so it settles questions the Jython alone cannot — most
  usefully `lanelet2_core/src/Lanelet.cpp` (`calculateCenterline`,
  `BoundChecker`, `findClosestNonintersectingPoint`) and
  `lanelet2_core/include/lanelet2_core/primitives/Lanelet.h`. Also useful:
  `lanelet2_routing/`, `lanelet2_traffic_rules/`, `lanelet2_validation/`.

  Verified against it so far:
  - `calculateCenterline` matches our port 1:1, including the trailing
    `makeCenterpoint(leftBound.back(), rightBound.back())` when the walk did not
    finish at both last points. The duplicated tail vertex on misaligned bounds
    is therefore **upstream behaviour**, not a Jython bug.
  - `ConstLanelet::invert()` only flips an `inverted_` flag, exactly like our
    `Lanelet.invert()`. **The divergence is in the accessors:** C++
    `leftBound3d()` returns `constData()->rightBound().invert()` when inverted,
    so bounds *and* `centerline3d()` come back with reversed point order. Our
    port swaps left/right but does **not** reverse. Anything direction-sensitive
    (smoothing, splitting on the centerline, routing) must not assume our
    `invert()` behaves like lanelet2's.

## Read the JOSM source instead of guessing

**The JOSM source is checked out at `/ll2_tooling_root/josm/src/` (read-only).**

Guessing JOSM APIs is the single largest source of wasted work here. Method
signatures, null contracts and lookup order are frequently not what they seem,
and a wrong guess usually fails silently at runtime rather than at compile time.
Before using an unfamiliar JOSM API, open it. Prefer `Grep` for the class or
method name over inference from the name.

Every item in the next section was a wrong assumption caught only by reading
the source, most of them after they had already shipped a silent bug.

## Verified JOSM API facts

- **`IPreferences.get(key, def)` accepts a null `def`** and returns it
  unchanged. The contract also requires *the same default for every call with a
  given key*. `LaneletSettings` therefore always passes `null` and applies its
  own default afterwards. Do not pass real defaults into JOSM here.
- **`AbstractProperty` captures `Config.getPref()` in its constructor** into a
  `final` field. Statics like `TaggingPresets.ICON_SOURCES` bind to whatever
  preferences existed at class-load, so `Config.setPreferencesInstance(...)`
  does **not** isolate them in tests. Reset such properties through the property
  itself in `@BeforeEach`. See `TaggingPresetsInstallerTest`.
- **Preset icons need `TaggingPresets.ICON_SOURCES`.** JOSM resolves a preset's
  relative icon via `ImageProvider(name).setDirs(ICON_SOURCES.get())`. Without a
  registered source, every `style_images/...` reference silently fails to render.
- **Map paint style icons need the *separate* `mappaint.icon.sources` pref.** A
  style's own `resource://` url is *not* part of its icon search path, so
  registering the preset source is not enough; `MapStyles.ensureIconSource()`
  registers the same `resource://lanelet2/` root for styles. Both are guarded
  (`MapStylesIconSourceTest`) because the failure is silent: the styles load and
  render, only the sign/arrow/traffic-light icons go missing.
- **The shipped `lines.mapcss` refers to more signs than the repo ships** (about
  320 of ~550, plus remote wikimedia urls). That is upstream behaviour, not a
  port bug — do not go hunting for lost icons.
- **An icon on a `JRadioButton` replaces the radio bullet**, leaving no
  selection indicator at all. Toolbar mode selectors therefore use
  `JToggleButton` in a `ButtonGroup` and paint their own accent border and
  background (`MenuInstaller.buildToggleButtons`).
- **`ImageProvider` understands `resource://` in `dirs`.**
  `getImageUrl(path, name)` strips the scheme and calls
  `ResourceProvider.getResource(path + name)`, so `resource://lanelet2/` plus
  `style_images/x.png` resolves to `lanelet2/style_images/x.png` in our jar.
  The trailing slash is load-bearing.
- **`ImageProvider` throws `JosmRuntimeException` on a missing icon** unless
  `setOptional(true)`. See `LaneletAction`.
- **`ResourceProvider`'s additional-classloader registry is append-only** and
  static: there is no remove. Do not close a loader you have registered.
- **`MainApplication.getMenu()` *is* the `JMenuBar`** (`MainMenu extends
  JMenuBar`). Use it directly rather than `getMainFrame().getJMenuBar()`.
- **`MapPaintStyles.addStyle` persists**, so registration must be idempotent.
  `MapStyles` matches on title *or* url against both `MapPaintPrefHelper` and
  the live style sources, which also prevents duplicating JOSM's own
  `elemstyles.mapcss`.
- **Shortcuts:** the Jython `ctrl shift <key>` maps to `Shortcut.CTRL_SHIFT`,
  not `ALT_CTRL_SHIFT`. Register via `Shortcut.registerShortcut`; never
  hardcode `KeyStroke`s.
  - **`LayerManager` has only `addLayer(Layer)` and
    `addLayer(Layer, boolean initialZoom)`** — there is no positional-insert
    overload. The Jython `lm.addLayer(new_layer, old_idx)` therefore never
    inserted at an index: Jython coerced the int to a boolean, so the new layer
    got an initial zoom whenever the old one was not at index 0. Ported
    faithfully as `addLayer(newLayer, oldIdx != 0)`; see `OsmIo`.
  - **JOSM's own `getBoolean` treats only `"true"` as true.** Plugin keys store
  `"1"`/`"0"` to stay compatible with the legacy settings file, so use
  `LaneletSettings`, not `Config.getPref().getBoolean`, for plugin prefs.

## Build

Requires JDK 21, compiles against JOSM 19555.

```bash
./gradlew build     # compile + test
./gradlew runJosm   # launch one JOSM with the plugin
```

**Do not put heavy or symlinked runtime data under JOSM's user data dir.**
`runJosm` redirects JOSM user data into `build/.josm/userdata`, so anything the
plugin writes there lands inside Gradle's build directory. The sidecar venv did,
and broke the dev loop twice: `initJosmPrefs` fails with "Couldn't follow
symbolic link .../venv/bin/python" (a venv's `bin/python` is a relative symlink
to `bin/python3`), and `clean` deletes a ~116 MB pip install. The venv therefore
lives at `$XDG_DATA_HOME/josm-lanelet2/venv` instead; see
`BackendStore.defaultVenvDir`. Extracted *scripts* stay in JOSM user data —
they are small and regenerate from the jar. If `initJosmPrefs` ever fails on a
symlink again, something new is writing into `build/.josm`.

**The repo must have at least one git commit.** `generateManifest` reads
`HEAD` via jgit for `Plugin-Date`; on a repo with zero commits `resolve("HEAD")`
returns null and the task fails with a confusing NPE.

## Porting rules

- **Preserve observable behaviour**, including quirks. Where the original has a
  latent bug, replicate it and note it in a KDoc comment rather than silently
  fixing it. Examples already in the tree: `wayMiddlePoint` returns vertex
  `n // 2` despite a docstring promising a centroid; `Lanelet.invert()` swaps
  bounds without reversing point order, unlike lanelet2 (see above).
- **Check the C++ before calling something a Jython bug.** The centerline tail
  quirk looked like a porting artifact and turned out to be faithful to
  upstream. Parity with the Jython is the contract, but knowing whether the
  Jython itself diverged from lanelet2 changes how much a difference matters.
- **Watch for Python 2 semantics.** The originals are Jython 2.7, where `/` on
  two ints truncates. Check every division when porting.
- **Tests must run headless** — no JOSM GUI, no display, so they work in CI.

### Jython quirks (josm_tools ports)

- **`highlight_file_boundaries` group tag vs mismatch tag.** Hull grouping
  reads `highlight_file_boundaries.group_tag` (default `file_origin`).
  Relation-vs-member mismatch detection, the empty-map warning, and the
  "untagged points ignored" toast always use the literal key `file_origin`.
- **Hull ring start is insertion-ordered.** The Jython walks `set`/`dict`
  (hash order in 2.7), so Douglas-Peucker can keep a different vertex if the
  ring starts elsewhere. The port uses `LinkedHashSet`/`LinkedHashMap` so
  rings are stable and match a CPython 3.7+ replica of the same algorithm.
- **`quick_tag_modal` Space binding.** The module docstring says Ctrl+Space;
  `launcher/menu.py` binds unmodified Space and skips it while focus is in a
  text component. Registry shortcut is `None`. Dialog x-position uses
  Python 2 int division `(pw - dw) / 2`.
- **`git_history_loader` replace overwrites the original file on save.** The
  replacement layer is associated with the active file path, not the `/tmp`
  extract. Load-into-new-layer leaves the extract in `/tmp`; replace deletes
  it. `.git` must be a directory ([GitHelpers.findGitRepo]). `git log` lines
  are split on `|` with maxsplit 3, so a `|` in the subject shifts date/author.
- **Pin numerics differentially.** Geometry ports are verified against the
  original Python, not against hand-written expectations: see
  `testdata/centerline/gen_centerline_corpus.py` and `CenterlineCorpusTest`
  (228 generated cases at 1e-12). Reuse that approach for new geometry.
- The Python `lanelet2` library is kept for positive-IDs, merge, split and the
  debug routing graph. `testdata/golden/` pins those backends; see its README,
  in particular that production passes **absolute** paths (`file_origin`).

## Background hooks (autotag, zoom filter, routing refresh)

Ported in `hooks/`. Quirks to keep:

- **`autotag.enabled` / `zoomfilter.enabled` are `"1"`-only.** Jython used
  `l2s.get(key, "0") == "1"`. `"true"` / `"yes"` stay off. Same contract as
  `routing.hook_full_map`.
- **Two tag parsers.** Settings persist as `k=v|k=v` and strip the key only
  (`_deserialize_tags`). The dialog textarea strips key *and* value and skips
  `#` comments (`_text_to_tags`). Do not unify them.
- **Autotag `|` in values is a documented limitation** of the settings
  serialization. Preserve it.
- **Autotag re-entrancy guard is structural, not a flag.** Collect in
  `primitivesAdded`, never mutate there; `tagsChanged` is a no-op; flush after
  400 ms via `SequenceCommand("Autotag new elements", ...)`.
- **Merge-anchor guard listens on `commandAdded` only** and sets `reverting`
  around its own undo so the revert cannot loop.
- **Zoom filter default is 17.0**, not the 19 mentioned in comments / the
  window docstring. Filters are **on** when `zoom <= threshold` (zoomed out).
- **Web Mercator constant is `156543.03392`**, not the 156543.03 in the
  module docstring.
- **Routing refresh** reads `routing.hook_full_map` and
  `routing.auto_debounce_ms` on every request. Small path coalesces at 300 ms;
  full-map path uses the debounce spinner (0 = immediate). An in-flight run
  sets `pending_rerun`; on finish, small path waits another 300 ms and skips
  attaching a stale graph.
- **Session lifetime, like Jython `core_hooks.py`.** Installed once at plugin
  init. `uninstall()` is only when the user turns a hook off (settings
  dialog). `Lanelet2Plugin.mapFrameInitialized` must **not** tear them down:
  JOSM calls it with `newFrame == null` when the last layer closes, then
  again with a new frame when a file is opened. Autotag re-attaches via
  `ActiveLayerChangeListener` and its HUD `MapFrameListener`; the zoom
  listener is process-global; routing keeps its timer. Viewer3dHook is the
  same: installed once, never from `newFrame == null`.
- **Menus install at plugin startup, not when the first layer opens.** JOSM
  has no `MapFrame` until a dataset is loaded; the menu bar exists earlier.
  `MenuInstaller.installMenus()` runs from the plugin constructor (and again
  on the EDT). The extra toolbars and the notes dialog wait for
  `mapFrameInitialized(newFrame != null)`. Closing the last layer must call
  `uninstallToolbar()` only — never `uninstall()`, or both menus vanish until
  the next file is opened.
- **Extra toolbar rows are optional.** A highlighted `LL2` toggle sits on
  JOSM's own (presets) toolbar — not on the extra rows, which is what it
  hides. Preference `toolbar.extra_visible` defaults on. Closing the last
  layer unwraps the extra rows but must leave that toggle in place.

## Action metadata lives in three registries, not one

Metadata parity is checked mechanically against the Jython registries, but they
are **split by tier** and `core/launcher/registry.py` is core-only. Check the
right file or the comparison silently finds nothing:

| Tier | File |
| --- | --- |
| `core/` (lanelet_edit, regulatory, selection, josm_tools, hooks) | `core/launcher/registry.py` |
| `ll2_dependent/` (positive ids, merge, split, routing graph) | `ll2_dependent/ll2_script_registry.py` |
| `internal/` (filter-broken, git commit, 3D viewer) | `internal/internal_script_registry.py` |

Parity tests read those registry files **live** rather than copying their tuples
into a fixture that would rot. Because the collection is a sibling checkout and
not part of this repo, always go through
`testutil.JythonSources.readText(relPath)`, which skips the test when the source
is absent (verified: 7 tests skip, build stays green). Never `File("/ll2_tooling_root/...")`
directly from a test — that fails in a CI checkout of this repo alone. Override
the location with `-Dlanelet2.jythonSource=` or `LL2_JYTHON_SOURCE`.

Two shape gotchas: slot ids can contain `::` for variants
(`scripts.ll2_debug_routing_graph::small`), so a key regex of `[a-z0-9_.]+`
silently skips them; and variant entries carry a **sixth** tuple element (the
variant argument) after `icon_path`, so compare the first five positionally
rather than requiring a 5-tuple.

## Shipped 3D viewer

`src/main/resources/lanelet2/viewer3d/` is the live 3D viewer, vendored from
`/ll2_tooling_root/ll2_3d_viewer/` so the plugin can ship it. Verified facts:

- **`server.py` is stdlib-only** (`argparse`, `json`, `queue`, `socketserver`,
  `threading`, `http.server`, ...), Python 3.7+. It therefore runs on the
  **system python3 and needs no venv and no `lanelet2`** — do not couple it to
  the sidecar's virtualenv.
- **The browser side did need the network.** Upstream `index.html` resolved
  `three` and `three/addons/` from `unpkg.com` via an importmap, so the viewer
  broke without internet. three.js 0.160.0, `OrbitControls` and
  `TransformControls` are now vendored under `static/vendor/` (~1.4 MB) and the
  importmap points at `/vendor/`. Keep the addons at
  `vendor/controls/<name>.js`: they import bare `"three"`, and the
  `"three/addons/" -> "/vendor/"` prefix mapping is what resolves them.
- **`server.py`'s static routing is a hardcoded whitelist**, not a directory
  server. `/vendor/` needed its own route (`_serve_vendor`, with containment
  checks against `..`). Adding a static asset means adding a route.
- **`--icons-dir` auto-detects from the tooling root when omitted**, which is
  wrong for a shipped plugin. Always pass it explicitly, pointing at the
  extracted `style_images`.

Offline self-containment is verified by fetching `/`, `/app.js` and all three
`/vendor/` modules with the server running and no network.

## In-scope `internal/` features

Only three of `internal/` are being ported (explicit user decision; the rest,
including `josm_hmi*` and `ll2_extract_range*`, is out of scope):
`ll2_filter_broken_lanelets_regElements`, `ll2_git_commit`, `ll2_viewer3d_window`
(plus `ll2_viewer3d_hook`, which the 3D bridge needs).

- **Their metadata is NOT in `core/launcher/registry.py`** — that file has zero
  references to them. It lives in `internal/internal_script_registry.py`, spliced
  in by `internal_launcher.py` via `internal/order_extensions.py`. Same tuple
  shape `(module, display_name, shortcut, toolbar_short, icon_path)`; all three
  have shortcut `None`. Check parity against *that* file.

### 3D bridge protocol (verified against the source)

- **JOSM -> server:** TCP client to the ingest port (**8766**), newline-delimited
  JSON, `type` in `snapshot` | `patch`. `patch` ops are `upsert`/`remove`.
  Features carry `id: "way/<uniqueId>"`, local ENU metre `points`, and a parallel
  `nodes` array. **The hook never sends `clear`** even though the protocol docs
  list it; an emptied layer is a `snapshot` with `features: []`.
- **server -> browser:** SSE `GET /events`, bootstrapped with a full snapshot.
  `GET /state` returns the same; `GET /healthz` returns `{"ok": true}`.
- **browser -> JOSM:** `POST /command` (HTTP port **8765**), which the server
  only *forwards* to connected bridges. Ops the hook applies: `move_node`,
  `set_tag`, `set_view`. **The browser only ever sends `move_node` and
  `set_view`** — `set_tag` is implemented but dead. Unknown ops are skipped
  silently.
- `move_node`/`set_tag` go into **one** `SequenceCommand("3D viewer edit", ...)`
  on the undo stack, so Ctrl+Z works. `set_view` is a camera move, not a command.
- **No selection listener and no undo listener.** Undo appears to work only
  because dataset mutation fires `DataSetListener`. Pushes are triggered by
  `DataSetListener` (200 ms debounce), `ActiveLayerChangeListener` (re-snapshot)
  and `ZoomChangeListener` (1 s, viewport overlay).
- Threading: all DataSet/MapView reads and command application on the **EDT**;
  JSON serialisation and socket writes on a `viewer3d-sender` daemon thread with
  a bounded queue that resyncs on overflow; a separate reader thread.
- **`_MAX_WAYS_PER_CYCLE` is 999**, not the 3000 the upstream `AGENTS.md`
  claims. Trust the code.
- **`Viewer3dSocketClient.requestResync()` must never call its `onConnected`
  callback.** The owner's handler (`Viewer3dHook.requestResync`) calls straight
  back into it, and the resulting `invokeLater` chain pinned the EDT at ~85% CPU
  while draining the send queue and restarting the 200 ms debounce on every
  pass — the viewer reported a healthy connection and stayed at 0 features.
  Only the sender thread (on connect) and queue overflow may call `onConnected`.
  Guarded by `Viewer3dSocketClientTest`.
- **Streaming follows the edit layer even when it is hidden**, matching Jython's
  `_compute_and_send`. Only the *inbound* command path checks `isVisible()`
  (Jython `ll2_viewer3d_hook.py:1013`). Do not add a visibility check to the
  attach/outbound path.
- **The 3D bridge is installed once per session**, like the other hooks. Do not
  uninstall it from `mapFrameInitialized(newFrame == null)`: JOSM fires that
  when the last layer closes, and nothing reinstalls it afterwards. Guarded by
  `HooksFrameLifetimeTest`.
- **Test the socket client, not just the server.** `Viewer3dE2ETest` originally
  wrote raw lines straight to the ingest port, which is why a client that could
  not deliver anything still passed.
- **`/healthz` is not enough to treat a leftover as healthy.** A process left
  over from an earlier session keeps the port and answers `/healthz`, so the
  GUI says "running" and Start is a no-op — while `/` 404s because extract
  deleted `static/` from under it, and Stop has no `Process` handle. Adopt
  only when `GET /` returns the page; otherwise recycle (remote `POST
  /shutdown`, then kill by port if the leftover is older than that endpoint).
  Do **not** `close()` the plugin `JarFile` when listing shipped viewer files.

### Approved divergences from the Jython (do NOT "restore parity")

The default rule in this repo is to preserve observable behaviour including
quirks. These two are **explicit, user-approved exceptions** because both risk
silent data loss. Keep them, and keep this note.

1. **Filter-broken gets a confirmation dialog and a backup.** The Jython
   overwrites the layer's `.osm` in place with no confirm, no backup, and no
   undo (the reload swaps the `OsmDataLayer` instead of issuing a Command).
   The port must ask first and write a backup before running the binary,
   mirroring what `ll2_git_commit` already does before it rewrites IDs. The
   *filtering result itself* stays byte-identical.
2. **Git commit stages the current file by default.** The Jython menu entry is
   named "Git Commit (current file)" but defaults to `git add -u` across the
   whole repo, which also means a new untracked `.osm` is silently never
   staged. The port defaults to staging **only the active file**, with a
   checkbox to stage **everything in the repo that is not gitignored**
   (`git add -A`). For the merged-map workflow, where the active file lives
   outside the repo, there is no current file to stage, so that case defaults
   to the repo-wide option.

### Porting hazards for these three

- **Server lifecycle cannot be reused as-is.** The Jython stop path greps
  `pgrep -f 'll2_3d_viewer/server.py'` and probes only the HTTP port; neither
  survives extraction to a plugin path. The plugin must own the process.
- `resolve_icons_dir` and the maps-repo/tooling-root fallbacks walk for sibling
  directories like `ws_ll2_mapping_hiwis/` and hardcode `~/ll2_tooling_root`.
  All of it breaks from a jar. Pass paths explicitly.
- Jython-only constructs to translate, not transcribe: `import Queue`, `long()`,
  `unicode()`, `Thread.isAlive()`. `round()` is Python 2
  round-half-away-from-zero and is applied to ENU millimetres.
- `ele` is formatted with `%g`, which can emit `1e-05`.
- `git_helpers.run_git` decodes stdout **without** the `AttributeError` fallback
  its siblings have, so a decode failure is reported as the git call failing.
- Filter-broken picks its binary with `sorted(...)[-1]`, which is
  lexicographic rather than newest, so a stale Conan deploy can win.

## Layout

- `src/main/kotlin/.../platform/` — settings, action registry, menu/toolbar,
  style and preset installers.
- `src/main/kotlin/.../settings/` — Lanelet2 Settings window and sub-dialogs
  (Map Styles, Tagging Presets), plus lanelet-default toolbar toggle slots.
- `src/main/kotlin/.../infra/` — pure geometry and the lanelet data model over
  JOSM primitives.
- `src/main/resources/lanelet2/` — MapCSS, presets, shared `style_images/`.
- `src/main/resources/images/lanelet2/` — action icons, where `ImageProvider`
  looks them up.

## Notes (custom geolocated notes)

Port of `core/notes/notes_core.py` + `notes_dialog.py`. Slot
`notes.notes_dialog` is already in `ActionRegistry.BASE_UTILS_ORDER`.

Jython quirks to keep (do not "fix"):

- **Private undo, not JOSM Commands (reviewed — do not "fix").** Keep
  `NotesUndoStack`. The Jython never touches the global `UndoRedoHandler`.
  A JOSM `Command` would bind to the active *map* layer, not the notes
  layer, so Ctrl+Z after creating a note would undo the wrong dataset.
  This was considered and rejected; preserving Jython undo is the contract.
- **`_put_all` skips empty values.** An empty `note_text` / `note_refs` is
  omitted from the OSM file, not written as `v=''`.
- **Single-node notes keep `note_member=yes`.** Promoting a copied standalone
  node to the marker adds `note_anchor` without removing the earlier member
  tags.
- **Closed-way undo splits the closing node.** `restore_prims` creates a new
  node per snapshot coord, so a closed way's first/last shared node becomes
  two nodes.
- **Copied ways do not share nodes across ways.** `_copy_way` remaps by
  unique id only within that way.
- **Selection centroid is a lat/lon arithmetic mean**, not a geodesic
  centroid. New notes with an empty selection use `MapView.getCenter()`
  (EastNorth → lat/lon), not the geographic centre of `realBounds`.
- **Author is `git config --get user.name` with the process cwd**, not the
  map file's repo, then `user.name`, then `"unknown"`.
- **`now_iso` is local time** (`%Y-%m-%dT%H:%M:%S`) with no timezone.
- **Import ignores exported timestamps/authors** and stamps `now` / git
  author. Import tags omit `note_anchor`; `add_point_note` adds it.
- **On-disk format** is custom OSM XML (`generator='lanelet2_notes'`),
  single-quoted attrs, `%.9f` coords, tags in sorted key order, Unix
  newlines + trailing newline. Negative unique ids remap from
  `max(positive ids, id_map values, 0)+1` and stay stable within a session.
  Golden: `testdata/notes/jython_serialize_fixture.notes`, regenerated by
  `python3 testdata/notes/gen_notes_fixture.py`.
- **ToggleDialog icon / pref prefix is `pin`** (JOSM `dialogs/pin`), not
  `notes.svg`. The Windows-menu shortcut is Alt+Shift+N; the action slot
  shortcut is `None`.
- **The table is not a `DataSetListener`.** Geometry edits on the notes
  layer do not refresh the table until the next `reload_rows`.
- **`hideNotify` switches back to the base layer** if the notes layer was
  active, and flushes the autosave (450 ms).
- **`os.path.splitext` path derivation:** `map.osm.bz2` becomes
  `map.osm.notes`.
- **MapCSS header comment** stays `Auto-generated by notes_core.py`.
- **`destroy()` calls `hideNotify()` then `super.destroy()`**, which may
  call `hideNotify` again if the dialog is still showing; removal is
  idempotent.
## Settings UI quirks (ported faithfully)

- **Grid cell spinner shows `int(get_merge_grid_cell_m())`**, truncating
  fractional stored values for display (Jython `SpinnerNumberModel` uses `int(...)`).
- **Routing debounce spinner uses integer division** (`ms / 1000`) in
  [RoutingPanel].
- **Settings window fixed size 520×720** like the Jython dialog (`pack()` is
  not used).
- **Map Styles / Presets sub-dialog errors** show via [Dialogs.error] when
  opened from the settings window (Jython used `JOptionPane` on the parent).
- **Backend setup button label** is “Set up Lanelet2 backends” with
  [BackendSetupWizard] (Jython ll2_dependent tier used “Python3 Backends ...”
  and a separate dialog — behaviour equivalent, label differs).
- **Commit-reminder checkbox** lives in [RoutingPanel]; the Jython internal
  launcher always-on timer is settings-gated in the Kotlin port (see existing
  note under `getGitCommitReminder`).
