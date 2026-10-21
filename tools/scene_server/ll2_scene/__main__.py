"""CLI: feed a recorded scene -- or a whole city of them -- into the Lanelet2 3D viewer.

    # viewer only (no JOSM): start the viewer server, then
    python -m ll2_scene --scene /path/to/<scene-uuid>
    python -m ll2_scene --city $KITSCENES_ROOT --map /path/to/city/lanelet2_map.osm
    python -m ll2_scene --city $KITSCENES_ROOT --build-aggregates   # batch, then exit

The scene server connects to the viewer's ingest port like the JOSM bridge
does. If a bridge is already streaming a map (JOSM), its anchor is adopted and
the map is left alone; otherwise the scene's own `maps/map.osm` is sent.
"""

import argparse
import os
import re
import sys
import time
import urllib.request

from .aggregate import SceneAggregator, aggregate_cache_name
from .bridge import ViewerBridge
from .cache import DEFAULT_LIMIT_GB, FrameDiskCache, default_cache_dir
from .http import CityHttpServer, SceneHttpServer
from .scene import MapFile, SceneSource

ANCHOR_RE = re.compile(rb'"anchor":\s*\{"lat":\s*([-\d.eE+]+),\s*"lon":\s*([-\d.eE+]+)\}')


def probe_viewer_anchor(host, port, timeout=2.0):
    """The anchor a bridge has already established, or None.

    Only the head of `/state` is read: the whole scene can be tens of
    megabytes, and `anchor` and the start of `features` are in the first
    bytes of it.
    """
    url = "http://%s:%d/state" % (host, port)
    try:
        with urllib.request.urlopen(url, timeout=timeout) as response:
            head = response.read(8192)
    except Exception:
        return None
    match = ANCHOR_RE.search(head)
    if not match:
        return None
    tail = head[match.end():]
    has_features = b'"features":[{' in tail.replace(b" ", b"")
    if not has_features:
        return None
    return float(match.group(1)), float(match.group(2))


def main(argv=None):
    parser = argparse.ArgumentParser(description="Stream a recorded scene into the Lanelet2 3D viewer")
    which = parser.add_mutually_exclusive_group(required=True)
    which.add_argument("--scene", help="scene directory (the one holding poses.txt)")
    which.add_argument("--city", metavar="ROOT",
                       help="every scene of one city: KITSCENES_ROOT, its data/ or one split")
    parser.add_argument("--city-name", default=None,
                        help="karlsruhe, frankfurt or sindelfingen (default: the one with "
                             "the most scenes under --city)")
    parser.add_argument("--map", default=None,
                        help="Lanelet2 map to send instead of the scene's own crop -- the "
                             "city-wide map, typically. Ignored while JOSM streams one")
    parser.add_argument("--max-open-scenes", type=int, default=8,
                        help="city: scenes kept open at once; idle ones beyond this are "
                             "closed (their disk cache stays)")
    parser.add_argument("--build-aggregates", action="store_true",
                        help="city: build every scene's whole-scene cloud into the cache, "
                             "one after the other, then exit. Cold builds take minutes a "
                             "scene; browsing the city afterwards only reads the cache")
    parser.add_argument("--viewer-host", default="127.0.0.1")
    parser.add_argument("--ingest-port", type=int, default=8766, help="the viewer's TCP ingest port")
    parser.add_argument("--viewer-port", type=int, default=8765, help="the viewer's HTTP port")
    parser.add_argument("--http-port", type=int, default=8767, help="port for this server's data routes")
    parser.add_argument("--kitscenes", default=None, help="path to a kitscenes checkout")
    parser.add_argument("--no-map", action="store_true", help="never send the map (JOSM owns it)")
    parser.add_argument("--map-only", action="store_true", help="send the map, serve no sensor data")
    parser.add_argument("--cache-dir", default=None,
                        help="where decoded frames are cached (default: %s). "
                             "Never the dataset, which stays read-only." % default_cache_dir())
    parser.add_argument("--no-cache", action="store_true", help="decode every frame afresh")
    parser.add_argument("--cache-limit-gb", type=float, default=DEFAULT_LIMIT_GB,
                        help="trim the cache to this size, least recently used first")
    parser.add_argument("--clear-cache", action="store_true",
                        help="drop this scene's cached frames before starting")
    parser.add_argument("--prewarm", action="store_true",
                        help="decode the whole scene into the cache in the background, "
                             "pausing whenever the browser asks for something")
    parser.add_argument("--aggregate-voxel", type=float, default=0.15,
                        help="voxel size of the whole-scene cloud, metres")
    parser.add_argument("--aggregate-min-run", type=int, default=3,
                        help="a voxel must be seen in this many CONSECUTIVE frames to "
                             "count as static; 1 keeps the moving traffic in")
    parser.add_argument("--aggregate-range", type=float, default=60.0,
                        help="only aggregate returns within this range of the car")
    parser.add_argument("--aggregate-step", type=int, default=1,
                        help="use every Nth frame when aggregating")
    parser.add_argument("--aggregate-workers", type=int,
                        default=max(1, (os.cpu_count() or 2) // 2),
                        help="processes used to build the aggregate (default: half the "
                             "cores). They compete with frame serving while it runs")
    parser.add_argument("--aggregate-now", action="store_true",
                        help="build the aggregate at startup instead of on demand")
    parser.add_argument("--decode-workers", type=int, default=1,
                        help="frames decoded at once (default 1: more of them makes "
                             "every single answer slower, which is what the user feels)")
    args = parser.parse_args(argv)

    log = lambda msg: print(msg, flush=True)  # noqa: E731
    if args.city:
        return run_city(args, log)
    source = SceneSource(args.scene, kitscenes_path=args.kitscenes, map_path=args.map)
    log("scene: %s, %d frames, lidars=%s, cameras=%d"
        % (source.scene_id, source.frame_count, ",".join(source.lidars), len(source.cameras)))

    send_map = not args.no_map
    existing = probe_viewer_anchor(args.viewer_host, args.viewer_port)
    if existing:
        source.set_anchor(existing)
        send_map = False
        log("scene: a bridge is already streaming a map; adopting its anchor %.7f, %.7f" % existing)
    centre = source._scene_centre()
    sx, sy = source.frame.scale(*centre)
    log("scene: anchor %.7f, %.7f  (ENU is turned %.3f deg against the scene frame, "
        "scale east %.6f / north %.6f)"
        % (source.anchor[0], source.anchor[1], source.frame.rotation_deg(*centre), sx, sy))

    http = None
    if not args.map_only:
        directory = None if args.no_cache else (args.cache_dir or default_cache_dir())
        disk = FrameDiskCache(source.scene_id, directory, args.cache_limit_gb, log=log)
        if args.clear_cache:
            disk.clear()
        log("scene: %s" % disk.describe())
        origin = "http://%s:%d" % (args.viewer_host, args.viewer_port)
        aggregator = SceneAggregator(
            source, disk_cache=disk, voxel=args.aggregate_voxel,
            max_range_m=args.aggregate_range, min_run=args.aggregate_min_run,
            step=args.aggregate_step,
            workers=args.aggregate_workers, log=log)
        http = SceneHttpServer(source, host=args.viewer_host, port=args.http_port,
                               allow_origin=origin, log=log, disk_cache=disk,
                               decode_workers=args.decode_workers, aggregator=aggregator)
        http.start()
        if args.prewarm:
            log("scene: prewarming %d frames in the background" % source.frame_count)
            http.prewarm()
        if args.aggregate_now:
            http.aggregate_meta()  # starts the build thread

    def on_connect():
        if send_map:
            t0 = time.perf_counter()
            features = source.map_features()
            bridge.send({"type": "snapshot", "source": "scene_server",
                         "anchor": {"lat": source.anchor[0], "lon": source.anchor[1]},
                         "features": features})
            log("scene: sent %d map features in %.2fs" % (len(features), time.perf_counter() - t0))
        if http:
            bridge.send(source.describe(http.base_url))

    bridge = ViewerBridge(args.viewer_host, args.ingest_port, log=log)
    bridge.start(on_connect=on_connect)

    try:
        while True:
            time.sleep(3600)
    except KeyboardInterrupt:
        log("scene: stopping")
    finally:
        bridge.stop()
        if http:
            http.stop()
    return 0


def run_city(args, log):
    from .city import CityIndex

    t0 = time.perf_counter()
    index = CityIndex(args.city, city=args.city_name, log=log)
    log("city: %s (indexed in %.1fs)" % (index.describe_log(), time.perf_counter() - t0))
    origin = index.default_anchor()
    cache_dir = None if args.no_cache else (args.cache_dir or default_cache_dir())

    def aggregator_for(source, disk):
        return SceneAggregator(
            source, disk_cache=disk, voxel=args.aggregate_voxel,
            max_range_m=args.aggregate_range, min_run=args.aggregate_min_run,
            step=args.aggregate_step, workers=args.aggregate_workers, log=log)

    name = aggregate_cache_name(args.aggregate_voxel, args.aggregate_min_run,
                                args.aggregate_range, args.aggregate_step)

    def aggregate_cached(scene):
        return bool(cache_dir) and (FrameDiskCache.scene_dir(cache_dir, scene.id) / name).is_file()

    if args.build_aggregates:
        return build_all(index, origin, cache_dir, aggregator_for, aggregate_cached, args, log)

    map_file = MapFile(args.map, origin) if args.map else None
    anchor = origin
    send_map = bool(map_file) and not args.no_map
    existing = probe_viewer_anchor(args.viewer_host, args.viewer_port)
    if existing:
        anchor = existing
        send_map = False
        log("city: a bridge is already streaming a map; adopting its anchor %.7f, %.7f" % existing)
    elif map_file:
        t1 = time.perf_counter()
        anchor = map_file.bbox_centre()
        log("city: loaded %s in %.1fs" % (map_file.path.name, time.perf_counter() - t1))
    elif not args.no_map:
        log("city: no --map and no JOSM: the trajectories come without a map")
    log("city: anchor %.7f, %.7f" % anchor)

    viewer_origin = "http://%s:%d" % (args.viewer_host, args.viewer_port)

    def open_scene(entry, base_url, gate, build_gate):
        source = SceneSource(entry.dir, kitscenes_path=args.kitscenes, anchor=anchor)
        disk = FrameDiskCache(source.scene_id, cache_dir, args.cache_limit_gb, log=log)
        return SceneHttpServer(source, allow_origin=viewer_origin, log=log, disk_cache=disk,
                               aggregator=aggregator_for(source, disk), listen=False,
                               base_url=base_url, gate=gate, build_gate=build_gate)

    http = CityHttpServer(index, open_scene, anchor, host=args.viewer_host,
                          port=args.http_port, allow_origin=viewer_origin, log=log,
                          max_open=args.max_open_scenes, decode_workers=args.decode_workers,
                          aggregate_cached=aggregate_cached)
    http.start()

    def on_connect():
        if send_map:
            t1 = time.perf_counter()
            features = map_file.features(anchor)
            bridge.send({"type": "snapshot", "source": "scene_server",
                         "anchor": {"lat": anchor[0], "lon": anchor[1]},
                         "features": features})
            log("city: sent %d map features in %.2fs" % (len(features), time.perf_counter() - t1))
        bridge.send({"type": "city", "city": {"name": index.city, "base": http.base_url,
                                              "scenes": len(index.scenes),
                                              "anchor": {"lat": anchor[0], "lon": anchor[1]}}})

    bridge = ViewerBridge(args.viewer_host, args.ingest_port, log=log)
    bridge.start(on_connect=on_connect)
    try:
        while True:
            time.sleep(3600)
    except KeyboardInterrupt:
        log("city: stopping")
    finally:
        bridge.stop()
        http.stop()
    return 0


def build_all(index, origin, cache_dir, aggregator_for, aggregate_cached, args, log):
    """`--build-aggregates`: every scene's static cloud into the cache, then exit."""
    if not cache_dir:
        log("city: --build-aggregates needs the cache (drop --no-cache)")
        return 2
    todo = [s for s in index.scenes if not aggregate_cached(s)]
    log("city: %d of %d aggregates already cached, %d to build"
        % (len(index.scenes) - len(todo), len(index.scenes), len(todo)))
    t0 = time.perf_counter()
    failed = 0
    for n, entry in enumerate(todo, 1):
        t1 = time.perf_counter()
        try:
            source = SceneSource(entry.dir, kitscenes_path=args.kitscenes, anchor=origin)
            disk = FrameDiskCache(source.scene_id, cache_dir, args.cache_limit_gb, log=log)
            aggregator = aggregator_for(source, disk)
            xyz, intensity, classes, colour, stats = aggregator.build()
            aggregator.save_cached(xyz, intensity, classes, colour, stats)
        except KeyboardInterrupt:
            log("city: stopped; %d built, the rest next time" % (n - 1))
            return 1
        except Exception as exc:
            failed += 1
            log("city: %s failed (%r)" % (entry.id, exc))
            continue
        spent = time.perf_counter() - t0
        log("city: %d/%d %s: %d points in %.0fs; about %.0f min to go"
            % (n, len(todo), entry.id, len(xyz), time.perf_counter() - t1,
               spent / n * (len(todo) - n) / 60))
    log("city: done, %d built, %d failed, %.0f min" % (len(todo) - failed, failed,
                                                       (time.perf_counter() - t0) / 60))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
