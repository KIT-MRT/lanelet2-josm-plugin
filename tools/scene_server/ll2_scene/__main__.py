"""CLI: feed a recorded scene into the Lanelet2 3D viewer.

    # viewer only (no JOSM): start the viewer server, then
    python -m ll2_scene --scene /path/to/<scene-uuid>

The scene server connects to the viewer's ingest port like the JOSM bridge
does. If a bridge is already streaming a map (JOSM), its anchor is adopted and
the map is left alone; otherwise the scene's own `maps/map.osm` is sent.
"""

import argparse
import re
import sys
import time
import urllib.request

from .bridge import ViewerBridge
from .http import SceneHttpServer
from .scene import SceneSource

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
    parser.add_argument("--scene", required=True, help="scene directory (the one holding poses.txt)")
    parser.add_argument("--viewer-host", default="127.0.0.1")
    parser.add_argument("--ingest-port", type=int, default=8766, help="the viewer's TCP ingest port")
    parser.add_argument("--viewer-port", type=int, default=8765, help="the viewer's HTTP port")
    parser.add_argument("--http-port", type=int, default=8767, help="port for this server's data routes")
    parser.add_argument("--kitscenes", default=None, help="path to a kitscenes checkout")
    parser.add_argument("--no-map", action="store_true", help="never send the map (JOSM owns it)")
    parser.add_argument("--map-only", action="store_true", help="send the map, serve no sensor data")
    args = parser.parse_args(argv)

    log = lambda msg: print(msg, flush=True)  # noqa: E731
    source = SceneSource(args.scene, kitscenes_path=args.kitscenes)
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
        origin = "http://%s:%d" % (args.viewer_host, args.viewer_port)
        http = SceneHttpServer(source, host=args.viewer_host, port=args.http_port,
                               allow_origin=origin, log=log)
        http.start()

    def on_connect():
        if send_map:
            t0 = time.perf_counter()
            features = source.map_features()
            bridge.send({"type": "snapshot",
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


if __name__ == "__main__":
    sys.exit(main())
