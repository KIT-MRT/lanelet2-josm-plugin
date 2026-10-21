"""HTTP side of the scene server: binary clouds and camera frames.

The viewer page is served by the viewer's own `server.py` on another port, so
every request here is cross-origin. Only that origin is allowed, and only
these read-only routes exist:

    GET /healthz                        liveness + scene id
    GET /scene.json                     the same description sent over SSE
    GET /frame/<i>/points.bin           all LiDARs of frame i, viewer ENU
    GET /frame/<i>/camera/<name>.jpg    one ring camera image, as stored

Decoded frames are cached (small, and stepping back and forth is the normal
way to look at a scene).
"""

import json
import re
import threading
import time
from collections import OrderedDict
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from .points import encode_points

FRAME_POINTS_RE = re.compile(r"^/frame/(\d+)/points\.bin$")
FRAME_IMAGE_RE = re.compile(r"^/frame/(\d+)/camera/([A-Za-z0-9_]+)\.jpg$")


class FrameCache:
    """Last few encoded frames, keyed by index."""

    def __init__(self, size=4):
        self.size = size
        self._items = OrderedDict()
        self._lock = threading.Lock()

    def get(self, key, build):
        with self._lock:
            if key in self._items:
                self._items.move_to_end(key)
                return self._items[key]
        value = build()
        with self._lock:
            self._items[key] = value
            self._items.move_to_end(key)
            while len(self._items) > self.size:
                self._items.popitem(last=False)
        return value


class SceneHttpServer:
    def __init__(self, source, host="127.0.0.1", port=8767, allow_origin="*", log=print):
        self.source = source
        self.host = host
        self.allow_origin = allow_origin
        self.log = log
        self.cache = FrameCache()
        handler = _make_handler(self)
        self._httpd = ThreadingHTTPServer((host, port), handler)
        self.port = self._httpd.server_address[1]
        self._thread = None

    @property
    def base_url(self):
        return "http://%s:%d" % (self.host, self.port)

    def start(self):
        self._thread = threading.Thread(target=self._httpd.serve_forever,
                                        name="ll2-scene-http", daemon=True)
        self._thread.start()
        self.log("scene: serving data on %s" % self.base_url)

    def stop(self):
        self._httpd.shutdown()
        self._httpd.server_close()

    def points_blob(self, index):
        def build():
            t0 = time.perf_counter()
            xyz, intensity, sensor = self.source.frame_points(index)
            blob = encode_points(xyz, intensity, sensor)
            self.log("scene: frame %d -> %d points, %.1f MB in %.2fs"
                     % (index, len(xyz), len(blob) / 1e6, time.perf_counter() - t0))
            return blob
        return self.cache.get(("points", index), build)


def _make_handler(server):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, fmt, *args):  # quiet; the server logs what matters
            pass

        def _send(self, body, content_type, status=200):
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Access-Control-Allow-Origin", server.allow_origin)
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)

        def _json(self, obj, status=200):
            self._send(json.dumps(obj).encode("utf-8"), "application/json", status)

        def do_OPTIONS(self):
            self.send_response(204)
            self.send_header("Access-Control-Allow-Origin", server.allow_origin)
            self.send_header("Access-Control-Allow-Methods", "GET, OPTIONS")
            self.send_header("Content-Length", "0")
            self.end_headers()

        def do_GET(self):
            path = self.path.split("?", 1)[0]
            source = server.source
            if path == "/healthz":
                self._json({"ok": True, "scene": source.scene_id,
                            "frames": int(source.frame_count)})
                return
            if path == "/scene.json":
                self._json(source.describe(server.base_url)["scene"])
                return
            match = FRAME_POINTS_RE.match(path)
            if match:
                index = int(match.group(1))
                if not 0 <= index < source.frame_count:
                    self._json({"error": "frame out of range"}, 404)
                    return
                try:
                    self._send(server.points_blob(index), "application/octet-stream")
                except Exception as exc:
                    server.log("scene: frame %d failed: %r" % (index, exc))
                    self._json({"error": repr(exc)}, 500)
                return
            match = FRAME_IMAGE_RE.match(path)
            if match:
                index, camera = int(match.group(1)), match.group(2)
                if camera not in source.cameras:
                    self._json({"error": "unknown camera"}, 404)
                    return
                image = source.image_path(camera, index)
                if not image or not image.is_file():
                    self._json({"error": "no image"}, 404)
                    return
                self._send(image.read_bytes(), "image/jpeg")
                return
            self._json({"error": "not found"}, 404)

    return Handler
