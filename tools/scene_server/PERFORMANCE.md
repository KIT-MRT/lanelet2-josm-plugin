# Scene server performance: what was done, what is left

Measured on the example scene (220 frames, ~740 k points a frame) on an
8-core machine with no CUDA device. Numbers are wall clock, warm frame cache
unless it says cold.

## City start on a network mount

The city index reads `poses.txt`, `maps/origin.json` and
`timestamp.reference.txt` from every scene. On the CIFS share (130 ms ping)
that was ~0.5 s a scene, one after another, almost all of it waiting for
round trips. Opening the three files of one scene is ~0.3 s cold.

| | whole pre-release (1651 scenes) |
|---|---|
| originally: one at a time, `stat` before every read | ~11 min (estimated from 70 scenes) |
| `scandir` + 32 threads, read instead of check | 17 s |
| next start, from the index cache (16.8 MB) | 2–4 s |

Per scene the parallel read went from 445 ms (1 thread) to 13 ms (32) and
7 ms (64). 32 is the default because the gain beyond it is a few seconds,
and only on a cold start. The one background check after a cached start
is a cold scan behind the server: 16 s. The city map (Karlsruhe, 136 MB, local) is 2 s to
load and 5.7 s to turn into features; `/city.json` is 1.1 s. Opening a
picked scene is ~1.4 s, mostly ~1300 `stat`s inside the kitscenes loader.
That one is worth a look if picking ever feels slow.

## Done

| | warm build | cold, 14 frames |
|---|---|---|
| originally | 192 s | 47.5 s |
| merge without re-sorting | 109 s | |
| + 8 worker processes | **45 s** | **12.8 s** |

**The scan was re-sorting the whole voxel set every frame.** Merging a
frame's voxels into the running set used `np.unique(concatenate(...))`, i.e.
a full sort of five million keys, 220 times — 46 % of the pass. Both arrays
are already sorted, so `merge_sorted` places instead of sorting: 4.6x on that
step, scan 134 s -> 51 s.

**Per-frame work now runs in processes** (`--aggregate-workers`). Threads
were measured first and gained only 1.3x with eight of them, because the
dataset's decode is Python-bound. The scan consumes results in order (a run
of consecutive frames is sequential); the gather splits frames into blocks
and sums partial results. Byte-identical output at any worker count.

## Where the remaining time goes

Profiled over 60 frames, warm, single process:

| stage | share |
|---|---|
| merge into the running voxel set | 46 % → now ~12 % |
| pose transform + range/ego masks | 23 % |
| voxel keys | 11 % |
| per-frame `np.unique` | 9 % |
| decode + disk read | 12 % |

Cold, the decode dominates everything else: 2.8 s a frame against ~0.3 s for
all the rest put together.

## Worth doing next, in order

1. **Decode in parallel when *serving* frames, not just aggregating.**
   Stepping into a cold part of a scene still costs ~2.8 s because
   `points_blob` decodes in one in-process worker (deliberately: eight at
   once made every answer six times slower). A small process pool would give
   the latency of one decode with the throughput of several. The pieces are
   already there in `aggregate.py`.

2. **Stop decoding every frame twice.** The scan and the gather each read and
   transform every frame. Caching each frame's voxel keys (sorted int64,
   ~4.8 MB a frame, ~1 GB a scene) would cut roughly a third off a warm
   build, at the cost of disk. Cheaper variant: cache only the keys for
   scenes under some size.

3. **Fuse the per-frame transform.** `(xyz - pos) @ rot` materialises a
   second 750 k x 3 float64 array only to compute two boolean masks. Doing
   the range test on the squared horizontal distance without forming the full
   rotated array, or in float32, saves most of the 23 %. float32 was tried
   and reverted — 3 % for a one-point-in-1.5M change in which points pass the
   range test — but a fused float64 version has no such downside.

4. **A C or Cython inner loop for the deskew**, or asking upstream for a
   vectorised one. This is the single biggest cost on a cold scene and it
   lives in the dataset's own library, not here.

5. **Only then a GPU.** With the sorts fixed, what is left is Python decode,
   disk reads and a few numpy passes — none of which cupy helps with. Once
   the decode is off the critical path, the voxelisation, the per-frame
   `unique`, the `searchsorted` and the scatter-adds would all map well to
   cupy and could plausibly take the warm build into single-digit seconds.
   Until then it would be optimising the small half.

## Things measured and rejected

- **float32 for the range/ego masks**: 3 % faster, and it moved one point in
  1.5 M across the range boundary. Not worth an irreproducible build.
- **Threads for the per-frame work**: 1.3x with eight, against 3.7x for eight
  processes on the same work.
- **`np.bincount` instead of `np.add.at`** in the gather: only 1.2x here,
  because the scatter is over a 2D array with half a million indices. Worth
  revisiting if the gather ever becomes the bottleneck again.
