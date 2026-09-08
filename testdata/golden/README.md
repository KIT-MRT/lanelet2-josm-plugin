# Lanelet2 backend golden corpus

Regression fixtures for the four Python backends the JOSM Lanelet2 plugin
invokes as subprocesses. Cases use the same argv shapes as the Jython
launchers (`-i`, `--files` / `--input-dir` / `--base`, staging split,
`--dry-run`, routing `--once --participant`).

The corpus is self-contained: every input map lives under `inputs/`. Do not
point cases at maps outside this tree.

## How to run

From this directory, or by path:

```bash
python3 testdata/golden/run_golden.py
python3 testdata/golden/run_golden.py /tmp/ll2-backend-venv/bin/python
LL2_BACKENDS_PYTHON=/tmp/ll2-backend-venv/bin/python python3 testdata/golden/run_golden.py
```

The interpreter defaults to `/tmp/ll2-backend-venv/bin/python`. Backends are
resolved from `LL2_BACKENDS_DIR`, else

`<tooling-root>/JOSM_lanelet2_editing_scripts/lanelet2_mapping_backends/lanelet2_mapping_backends`

which is how the plugin launches them (`python <script.py> <args>`, not
`python -m ...`).

```bash
# rewrite stored expected outputs after a deliberate backend change
python3 testdata/golden/run_golden.py --update

# subset
python3 testdata/golden/run_golden.py --case positive_ids --case dry_run
```

Exit status is non-zero on any mismatch after normalisation.

## Inputs

| File | Source | Why it is here |
|---|---|---|
| `inputs/mapping_example.osm` | Lanelet2 `lanelet2_maps/res/mapping_example.osm` | Canonical Karlsruhe example with regulatory elements; routing-graph input |
| `inputs/drivable_space.osm` | `ws_ll2_mapping_hiwis/.../drivable_space.osm` | Real JOSM file whose ids are already negative |
| `inputs/merge_dir/part_{a,b,c}.osm` | Disjoint extracts of `mapping_example` written by the lanelet2 OSM writer | Small, canonical maps for merge/split (part_a includes 3 areas; part_c includes the 10 lanelets that carry regulatory elements, 8 unique regs) |
| `inputs/part_a_negids.osm` | `part_a.osm` with five ids rewritten negative | Mixed positive + negative ids (including a way and a relation) |

`phMap.osm` / `intersections.osm` were skipped (1.8–2.9 MB) and the Autoware
map was skipped (Japan; default UTM origin 49/8 is wrong for it). The huge
files under `merged_input_osm_files/` were never considered.

## Cases

### `positive_ids`

| Case | What it pins |
|---|---|
| `drivable_space_inplace` | Plugin path: `positive_ids -i <file>` on a map whose nodes/ways are negative |
| `part_a_mixed_negids` | Explicit `in.osm out.osm` form; mixed negative + existing positive ids (including large lanelet2-generated ids) |

### `merge_osm_files`

| Case | What it pins |
|---|---|
| `files_three_parts` | `--files` merge of part_a/b/c |
| `input_dir_three_parts` | `--input-dir inputs/merge_dir` (non-recursive `*.osm` glob). Same relative paths as `--files`, so the merged OSM matches `files_three_parts` |
| `base_append` | `--base merged_ab.osm --files part_c.osm` after a setup merge of a+b. Ids/tags on the base are preserved; appending c in sort order matches a full a+b+c merge |

Stdout is expected to contain `MERGE_OUTPUT=<WORK>/...`.

### `split_merged_osm_file`

| Case | What it pins |
|---|---|
| `staging_roundtrip` | Merge the three parts, then split with `--output-mode staging`. The runner also diffs each reconstructed file against the original part (lossless for these canonical inputs; see below) |
| `dry_run` | Same merge, then `--dry-run`. No OSM is written; stdout lists `SPLIT_TARGET=<WORK>/split_out/<basename>` |

### `server_create_debug_routing_graph_dataset`

| Case | What it pins |
|---|---|
| `vehicle_once` | Plugin path: `--once --participant vehicle mapping_example.osm routing_vehicle_mapping_example.osm` |
| `bicycle_once` | Same with `bicycle` |

`--once` does **not** run `lanelet2_validate` even though `--validate` defaults
true; only the file-watch loop uses that flag.

## Normalisation

Compared artefacts are normalised before diffing. Expected files stored by
`--update` are already normalised.

**Text (stdout / stderr)**

1. Merge/split log timestamps (`%(asctime)s`, `YYYY-MM-DD HH:MM:SS,mmm`) →
   `<TIMESTAMP>`. They change every run.
2. Absolute paths of the per-run work directory, this corpus, the backends
   directory, and the python interpreter → `<WORK>`, `<CORPUS>`, `<BACKENDS>`,
   `<PYTHON>`. Merge prints `MERGE_OUTPUT=` with `Path.resolve()`; split
   dry-run prints `SPLIT_TARGET=` from a resolved output dir; logs embed the
   same resolved paths.

**OSM XML**

1. The four merge git-metadata tags `last_modified`, `committer`,
   `commit_message`, `all_committers` are **stripped**. They appear only when
   the input path sits in a git repo that has history for that file, and the
   values change when history changes. Untracked copies in this corpus produce
   none; after the corpus is committed they would appear and break the goldens
   without this strip.
2. The same absolute-path rewrite as logs, in case a tag value (e.g.
   `file_origin`) ever contains a resolved path. Cases pass **relative**
   paths from the corpus root, so `file_origin` is stored as
   `inputs/merge_dir/part_a.osm` and is compared literally — a switch to
   absolute paths is a failure, not something we hide.

   Relative paths here are a corpus device for stable goldens, not the
   production contract. The Jython launchers pass `getAbsolutePath()`, so real
   merged maps already carry absolute `file_origin` values and split resolves
   them back. The plugin must keep passing absolute paths to stay behaviour
   compatible; do not "fix" this to relative.

Nothing else is rewritten. In particular generated OSM ids, lat/lon
formatting, member order, and `file_origin` / `original_id` are pinned.

## Determinism (verified with two consecutive runs before freezing goldens)

| Backend | OSM output | Stdout / stderr |
|---|---|---|
| `positive_ids` | Byte-identical | Empty / empty (no normalisation needed) |
| `merge_osm_files` | Byte-identical (aside from git tags, which did not fire on untracked copies) | `MERGE_OUTPUT` absolute path; log timestamps |
| `split_merged_osm_file` | Byte-identical | Log timestamps; dry-run `SPLIT_TARGET` absolute paths |
| `server_create_debug_routing_graph_dataset --once` | Byte-identical | Stdout embeds the input/output paths (relative here, so stable) |

## Merge → split round-trip

For `inputs/merge_dir/part_{a,b,c}.osm` (canonical lanelet2 writer output,
positive ids) the round-trip is **byte-identical**. `staging_roundtrip`
asserts that.

It is **not** byte-lossless in general:

- **JOSM originals** (`mapping_example.osm`, `drivable_space.osm`) are
  rewritten in lanelet2 XML (`generator="lanelet2"`, quote style, dropped
  `action='modify'`, coordinate/`version` formatting). That is true even of
  `positive_ids -i` on a file whose ids are already all positive.
- **Negative ids** are restored from `original_id` then passed through
  `_make_ids_positive`, so a split of `drivable_space` does not reproduce the
  negative ids.
- **Area `outer` member order** can rotate (first member moves to last) when
  a full JOSM `mapping_example` is merged and split, compared to a plain
  lanelet2 load+write of the same file. The small canonical parts in this
  corpus, including part_a's three areas, do not show that.

## What is deliberately not covered

- In-place split (`--output-mode in-place`), git dirty-tree refusal, and
  non-git backups (`split_safety_utils`).
- Merge-anchor duplication/fusion (`merge_anchor=yes`). That needs a merged
  map whose ways from different files share a node — a JOSM edit after merge,
  not something these static extracts provide.
- Routing file-watch loop, JOSM remote-control HTTP, and `requests`.
- `--validate` / `lanelet2_validate` (not used on the `--once` path).
- Participants `pedestrian` and `train`.
- `--no-fuse-anchors` / `--no-anchors`.
- Large production maps (100 MB+ under `merged_input_osm_files/`).
- Invoking backends as `python -m lanelet2_mapping_backends.<name>` (the
  plugin calls the `.py` files; those files use `from merge_anchor_utils
  import ...`, which depends on script-directory `sys.path`).

## Layout of a case

```
cases/<backend>/<case-name>/
  cmd.txt          argv tokens, one per line, relative to the corpus root
  case.json        backend, setup steps, output_files / output_dirs, inplace
  stdout.txt       normalised captured stdout (may be empty)
  stderr.txt       normalised captured stderr (may be empty)
  expected/        golden output files (basenames of produced artefacts)
```

`run_golden.py` copies `inputs/` into a temp work directory, runs optional
setup steps, runs `cmd.txt`, and diffs.
