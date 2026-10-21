"""HTTP side of the scene server: binary clouds and camera frames.

The viewer page is served by the viewer's own `server.py` on another port, so
every request here is cross-origin. Only that origin is allowed, and only
these read-only routes exist:

    GET /healthz                        liveness + scene id
    GET /scene.json                     the same description sent over SSE
    GET /frame/<i>/points.bin           all LiDARs of frame i, viewer ENU
    GET /frame/<i>/camera/<name>.jpg    one ring camera image, as stored
    GET /aggregate/meta.json            the whole scene as one static cloud;
                                        202 with progress while it builds
    GET /aggregate/<n>.bin              one spatial chunk of it

**Decoding is serialised.** A frame costs seconds of CPU, and the
thread-per-request behaviour of `ThreadingHTTPServer` turns a few taps on the
step key into eight decodes fighting over the cores: measured, one frame alone
is 3.4 s while eight at once are 20 s *each*, so even the frame the user is
waiting for arrives last. One decode at a time makes the first answer arrive
first; requests for the same frame share one decode, and work whose browser
has already navigated away is dropped before it starts.
"""

import gzip
import json
import re
import select
import socket
import sys
import threading
import time
from collections import OrderedDict
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import numpy as np

from .points import INTENSITY_FULL_SCALE, decode_points, encode_points

FRAME_POINTS_RE = re.compile(r"^/frame/(\d+)/points\.bin$")
FRAME_IMAGE_RE = re.compile(r"^/frame/(\d+)/camera/([A-Za-z0-9_]+)\.jpg$")
AGGREGATE_CHUNK_RE = re.compile(r"^/aggregate/(\d+)\.bin$")
SCENE_ROUTE_RE = re.compile(r"^/scene/([A-Za-z0-9_-]{1,80})(/.*)$")
GONE = object()  # the browser left before we got to its request


class FrameCache:
    """Last few encoded frames, keyed by index."""

    def __init__(self, size=4):
        self.size = size
        self._items = OrderedDict()
        self._lock = threading.Lock()

    def get(self, key):
        with self._lock:
            if key in self._items:
                self._items.move_to_end(key)
                return self._items[key]
        return None

    def put(self, key, value):
        with self._lock:
            self._items[key] = value
            self._items.move_to_end(key)
            while len(self._items) > self.size:
                self._items.popitem(last=False)


def client_gone(connection):
    """True when the browser has closed this connection (an aborted fetch).

    A readable socket that peeks nothing is a closed peer; a readable socket
    with bytes waiting is just the next pipelined request.
    """
    try:
        ready, _, _ = select.select([connection], [], [], 0)
        if not ready:
            return False
        return connection.recv(1, socket.MSG_PEEK | socket.MSG_DONTWAIT) == b""
    except (BlockingIOError, InterruptedError):
        return False
    except OSError:
        return True


class SceneHttpServer:
    def __init__(self, source, host="127.0.0.1", port=8767, allow_origin="*", log=print,
                 disk_cache=None, decode_workers=1, aggregator=None,
                 listen=True, base_url=None, gate=None, build_gate=None):
        """`listen=False` makes a scene that a `CityHttpServer` routes to: no
        socket of its own, its routes under `base_url`, and the decode and
        aggregate-build gates shared with every other scene of the city."""
        self.source = source
        self.disk = disk_cache
        self.host = host
        self.allow_origin = allow_origin
        self.log = log
        self.cache = FrameCache()
        # One decoder by default: the work is CPU-bound, so more of them only
        # delay every answer (see the module docstring).
        self._gate = gate or threading.BoundedSemaphore(max(1, decode_workers))
        # Building an aggregate takes every worker process for minutes, so a
        # city runs one build at a time; a cached aggregate never waits here.
        self._build_gate = build_gate
        self._inflight = {}
        self._inflight_lock = threading.Lock()
        self._waiting = 0          # browser requests currently queued or decoding
        self._waiting_lock = threading.Lock()
        self._prewarm = None
        self._stopping = threading.Event()
        self.aggregator = aggregator
        self._aggregate = None          # (chunks in the scene frame, stats)
        self._aggregate_thread = None
        self._aggregate_error = None
        self._aggregate_blobs = {}      # (index, anchor) -> encoded bytes
        self._thread = None
        self._base_url = base_url
        self._httpd = None
        self.port = None
        if listen:
            self._httpd = _QuietThreadingHTTPServer((host, port), _make_handler(self))
            self.port = self._httpd.server_address[1]

    @property
    def base_url(self):
        if self._base_url:
            return self._base_url
        return "http://%s:%d" % (self.host, self.port)

    @property
    def building(self):
        return self._aggregate_thread is not None and self._aggregate_thread.is_alive()

    def start(self):
        if self._httpd is None:
            return
        self._thread = threading.Thread(target=self._httpd.serve_forever,
                                        name="ll2-scene-http", daemon=True)
        self._thread.start()
        self.log("scene: serving data on %s" % self.base_url)

    def prewarm(self):
        """Fill the cache frame by frame in the background.

        One frame at a time and only while no browser is waiting, so warming
        never costs the user a step. Once through, every frame answers from
        cache in ~0.15 s instead of seconds.
        """
        def run():
            warmed = 0
            t0 = time.perf_counter()
            for index in range(self.source.frame_count):
                if self._stopping.is_set():
                    return
                while self._waiting > 0 and not self._stopping.wait(0.2):
                    pass  # a browser is waiting; it goes first
                if self._stopping.is_set():
                    return
                if self.disk and self.disk.get(index) is not None:
                    continue
                try:
                    self.points_blob(index)
                    warmed += 1
                except Exception as exc:
                    self.log("scene: prewarm stopped at frame %d (%r)" % (index, exc))
                    return
            self.log("scene: prewarmed %d frames in %.0f s; stepping is now ~0.15 s a frame"
                     % (warmed, time.perf_counter() - t0))
        self._prewarm = threading.Thread(target=run, name="ll2-scene-prewarm", daemon=True)
        self._prewarm.start()

    # -- the whole-scene aggregate -----------------------------------------
    def aggregate_meta(self):
        """Chunk list once built, else progress; starts the build on demand."""
        if self._aggregate is not None:
            chunks, stats = self._aggregate
            enu = [self.source.to_enu(np.array([c["centre"]]))[0].tolist() for c in chunks]
            return {"ready": True, "stats": stats,
                    "chunks": [{"index": i, "count": c["count"],
                                "centre": [round(v, 2) for v in enu[i]],
                                "radius": round(c["radius"], 2)}
                               for i, c in enumerate(chunks)]}
        if self._aggregate_error:
            return {"ready": False, "error": self._aggregate_error}
        if self.aggregator is None:
            return {"ready": False, "error": "no aggregator configured"}
        if self._aggregate_thread is None or not self._aggregate_thread.is_alive():
            self._aggregate_thread = threading.Thread(
                target=self._build_aggregate, name="ll2-scene-aggregate", daemon=True)
            self._aggregate_thread.start()
        return {"ready": False, "progress": dict(self.aggregator.progress)}

    def _build_aggregate(self):
        try:
            cached = self.aggregator.load_cached() if self.aggregator else None
            if cached is not None:
                xyz, intensity, classes, colour, stats = cached
                self.log("scene: aggregate loaded from cache (%d points)" % len(xyz))
            else:
                if self._build_gate is not None:
                    self.aggregator.progress.update(state="queued")
                    self._build_gate.acquire()
                try:
                    if self._stopping.is_set():
                        return
                    xyz, intensity, classes, colour, stats = self.aggregator.build()
                    self.aggregator.save_cached(xyz, intensity, classes, colour, stats)
                finally:
                    if self._build_gate is not None:
                        self._build_gate.release()
            from .aggregate import chunk_by_tile
            chunks = chunk_by_tile(xyz, intensity, classes, colour)
            self._aggregate = (chunks, stats)
            self.aggregator.progress.update(state="ready", points=int(len(xyz)))
        except Exception as exc:
            self._aggregate_error = repr(exc)
            self.log("scene: aggregate failed: %r" % exc)

    def aggregate_chunk(self, index):
        if self._aggregate is None:
            return None
        chunks, _stats = self._aggregate
        if not 0 <= index < len(chunks):
            return None
        key = (index, self.source.anchor)
        blob = self._aggregate_blobs.get(key)
        if blob is None:
            chunk = chunks[index]
            blob = encode_points(self.source.to_enu(chunk["xyz"]), chunk["intensity"],
                                 classes=chunk.get("classes"), rgb=chunk.get("rgb"))
            self._aggregate_blobs[key] = blob
            # Keyed by anchor as well, so a chunk is re-projected if JOSM's
            # anchor is adopted later; keep only a handful of them.
            while len(self._aggregate_blobs) > 32:
                self._aggregate_blobs.pop(next(iter(self._aggregate_blobs)))
        return blob

    def route(self, handler, path):
        _route_scene(self, handler, path)

    def stop(self):
        self._stopping.set()
        # shutdown() waits for serve_forever() to return and blocks forever if
        # it was never entered, so only call it when the server really started.
        if self._thread is not None:
            self._httpd.shutdown()
            self._thread.join(timeout=5)
            self._thread = None
        if self._httpd is not None:
            self._httpd.server_close()

    def points_blob(self, index, still_wanted=None):
        """Frame `index` in viewer ENU, or GONE if the browser left meanwhile.

        The disk cache holds the frame in the *scene's* metric frame, so it
        stays valid across a change of ENU anchor; only the cheap
        re-projection is redone.
        """
        key = ("points", index, self.source.anchor)
        hit = self.cache.get(key)
        if hit is not None:
            return hit

        # A step and its prefetch (or two tabs) asking for the same frame wait
        # on one decode instead of starting two.
        with self._inflight_lock:
            event = self._inflight.get(key)
            mine = event is None
            if mine:
                event = threading.Event()
                self._inflight[key] = event
        if not mine:
            event.wait(timeout=180)
            hit = self.cache.get(key)
            if hit is not None:
                return hit
        try:
            return self._build(key, index, still_wanted)
        finally:
            if mine:
                with self._inflight_lock:
                    self._inflight.pop(key, None)
                event.set()

    def _labels_for(self, index, xyz):
        """(Vistas class, instance number) per point, cached beside the frame.

        Decoding the two mask images costs ~0.9 s, so both come out of one
        decode and are kept in their own cache entries: adding them to the
        frame blob would have invalidated every decoded frame already on disk.
        """
        if not getattr(self.source, "segmentation_path", None):
            return None, None
        classes = instances = None
        if self.disk:
            cached = self.disk.get(index, "class")
            if cached is not None and len(cached) == len(xyz):
                classes = np.frombuffer(cached, np.uint8)
            cached = self.disk.get(index, "inst")
            if cached is not None and len(cached) == len(xyz) * 2:
                instances = np.frombuffer(cached, "<u2")
        if classes is not None and instances is not None:
            return classes, instances
        try:
            classes, instances = self.source.frame_labels(index, xyz)
        except Exception as exc:
            self.log("scene: no classes for frame %d (%r)" % (index, exc))
            return None, None
        if self.disk:
            self.disk.put(index, classes.tobytes(), "class")
            self.disk.put(index, instances.tobytes(), "inst")
        return classes, instances

    def _rgb_for(self, index, xyz):
        """Camera colour per point, cached beside the frame like the classes."""
        if not getattr(self.source, "frame_rgb", None):
            return None
        cached = self.disk.get(index, "rgb") if self.disk else None
        if cached is not None and len(cached) == len(xyz) * 3:
            return np.frombuffer(cached, np.uint8).reshape(-1, 3)
        try:
            rgb = self.source.frame_rgb(index, xyz)
        except Exception as exc:
            self.log("scene: no camera colour for frame %d (%r)" % (index, exc))
            return None
        if self.disk:
            self.disk.put(index, rgb.tobytes(), "rgb")
        return rgb

    def _build(self, key, index, still_wanted):
        with self._gate:
            # Queued behind another decode; the tab may be long gone by now.
            if still_wanted is not None and not still_wanted():
                self.log("scene: frame %d dropped, the browser moved on" % index)
                return GONE
            hit = self.cache.get(key)  # someone may have finished it while we waited
            if hit is not None:
                return hit
            t0 = time.perf_counter()
            cached = self.disk.get(index) if self.disk else None
            if cached is not None:
                xyz, intensity, sensor, _origin, _c, _r, _i = decode_points(cached)
                if intensity is not None:
                    # Came back quantised to a byte; give the encoder the
                    # 0..2 reflectivity it expects.
                    intensity = intensity.astype(np.float32) * (INTENSITY_FULL_SCALE / 255.0)
                where = "cache"
            else:
                xyz, intensity, sensor = self.source.frame_points_scene(index)
                if self.disk:
                    self.disk.put(index, encode_points(xyz, intensity, sensor))
                where = "dataset"
            classes, instances = self._labels_for(index, xyz)
            rgb = self._rgb_for(index, xyz)
            blob = encode_points(self.source.to_enu(xyz), intensity, sensor,
                                 classes=classes, rgb=rgb, instances=instances)
            self.cache.put(key, blob)
            self.log("scene: frame %d -> %d points, %.1f MB in %.2fs (%s)"
                     % (index, len(xyz), len(blob) / 1e6, time.perf_counter() - t0, where))
            return blob


class CityHttpServer:
    """Every scene of a city behind one port.

        GET /healthz
        GET /city.json               every trajectory, in viewer ENU (gzipped
                                     when the browser takes it)
        GET /city/status.json        per scene: aggregate cached / building
        GET /scene/<id>/...          that scene's routes, exactly as above

    A scene is opened on first use and the least recently used idle ones are
    closed again beyond `max_open`; what they decoded stays in the disk cache.
    All scenes share one decode gate and one aggregate-build gate, so loading
    ten scenes around the camera cannot start ten builds at once.
    """

    def __init__(self, index, open_scene, anchor, host="127.0.0.1", port=8767,
                 allow_origin="*", log=print, max_open=8, decode_workers=1,
                 aggregate_cached=None):
        self.index = index
        self.open_scene = open_scene    # (CityScene, base_url, gate, build_gate) -> SceneHttpServer
        self.anchor = tuple(anchor)
        self.host = host
        self.allow_origin = allow_origin
        self.log = log
        self.max_open = max(1, max_open)
        self.aggregate_cached = aggregate_cached   # CityScene -> bool
        self.gate = threading.BoundedSemaphore(max(1, decode_workers))
        self.build_gate = threading.BoundedSemaphore(1)
        self._open = OrderedDict()                 # id -> SceneHttpServer
        self._lock = threading.Lock()
        self._opening = {}                         # id -> Lock, so one open per scene
        self._city_json = None                     # (anchor, raw bytes, gzipped bytes)
        self._httpd = _QuietThreadingHTTPServer((host, port), _make_handler(self))
        self.port = self._httpd.server_address[1]
        self._thread = None

    @property
    def base_url(self):
        return "http://%s:%d" % (self.host, self.port)

    def start(self):
        self._thread = threading.Thread(target=self._httpd.serve_forever,
                                        name="ll2-city-http", daemon=True)
        self._thread.start()
        self.log("city: serving %d scenes on %s" % (len(self.index.scenes), self.base_url))

    def stop(self):
        with self._lock:
            services = list(self._open.values())
            self._open.clear()
        for service in services:
            service.stop()
        if self._thread is not None:
            self._httpd.shutdown()
            self._thread.join(timeout=5)
            self._thread = None
        self._httpd.server_close()

    def set_anchor(self, anchor):
        """Adopt another ENU anchor (JOSM's): trajectories and open scenes follow."""
        self.anchor = tuple(anchor)
        with self._lock:
            self._city_json = None
            for service in self._open.values():
                service.source.set_anchor(self.anchor)

    def city_json(self):
        with self._lock:
            cached = self._city_json
        if cached and cached[0] == self.anchor:
            return cached[1], cached[2]
        t0 = time.perf_counter()
        raw = json.dumps(self.index.describe(self.anchor, self.base_url,
                                             self.aggregate_cached),
                         separators=(",", ":")).encode("utf-8")
        packed = gzip.compress(raw, compresslevel=5)
        with self._lock:
            self._city_json = (self.anchor, raw, packed)
        self.log("city: trajectories %.1f MB (%.1f MB gzipped) in %.2fs"
                 % (len(raw) / 1e6, len(packed) / 1e6, time.perf_counter() - t0))
        return raw, packed

    def status(self):
        with self._lock:
            open_now = dict(self._open)
        out = {}
        for scene in self.index.scenes:
            service = open_now.get(scene.id)
            out[scene.id] = {
                "aggregate": bool(self.aggregate_cached and self.aggregate_cached(scene))
                             or bool(service is not None and service._aggregate is not None),
                "building": bool(service is not None and service.building),
                "open": service is not None,
            }
        return out

    def scene(self, scene_id):
        """The scene server for `scene_id`, opened on first use; None if unknown."""
        entry = self.index.by_id.get(scene_id)
        if entry is None:
            return None
        with self._lock:
            service = self._open.get(scene_id)
            if service is not None:
                self._open.move_to_end(scene_id)
                return service
            opening = self._opening.setdefault(scene_id, threading.Lock())
        with opening:
            with self._lock:
                service = self._open.get(scene_id)
            if service is None:
                t0 = time.perf_counter()
                service = self.open_scene(entry, "%s/scene/%s" % (self.base_url, scene_id),
                                          self.gate, self.build_gate)
                service.source.set_anchor(self.anchor)
                self.log("city: opened %s in %.2fs" % (scene_id, time.perf_counter() - t0))
                with self._lock:
                    self._open[scene_id] = service
                    evicted = self._evict_locked()
                for old in evicted:
                    old.stop()
            return service

    def _evict_locked(self):
        """Close least recently used scenes beyond `max_open`, never a busy one."""
        evicted = []
        for scene_id in list(self._open):
            if len(self._open) <= self.max_open:
                break
            service = self._open[scene_id]
            if service.building or service._waiting:
                continue
            evicted.append(self._open.pop(scene_id))
            self.log("city: closed %s (least recently used)" % scene_id)
        return evicted

    def route(self, h, path):
        if path == "/healthz":
            h._json({"ok": True, "city": self.index.city, "scenes": len(self.index.scenes)})
            return
        if path == "/city.json":
            raw, packed = self.city_json()
            if "gzip" in (h.headers.get("Accept-Encoding") or ""):
                h._send(packed, "application/json", headers={"Content-Encoding": "gzip"})
            else:
                h._send(raw, "application/json")
            return
        if path == "/city/status.json":
            h._json(self.status())
            return
        match = SCENE_ROUTE_RE.match(path)
        if match:
            try:
                service = self.scene(match.group(1))
            except Exception as exc:
                self.log("city: could not open %s (%r)" % (match.group(1), exc))
                h._json({"error": "could not open scene: %r" % exc}, 500)
                return
            if service is None:
                h._json({"error": "no such scene"}, 404)
                return
            service.route(h, match.group(2))
            return
        h._json({"error": "not found"}, 404)


class _QuietThreadingHTTPServer(ThreadingHTTPServer):
    daemon_threads = True

    def handle_error(self, request, client_address):
        """A tab that navigates away mid-response is normal, not an error.

        Without this, every aborted fetch prints a BrokenPipeError traceback
        and buries the lines that matter.
        """
        exc = sys.exc_info()[1]
        if isinstance(exc, (BrokenPipeError, ConnectionResetError, TimeoutError)):
            return
        super().handle_error(request, client_address)


def _make_handler(router):
    """A request handler that hands every GET to `router.route(handler, path)`."""
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, fmt, *args):  # quiet; the server logs what matters
            pass

        def _send(self, body, content_type, status=200, headers=None):
            try:
                self.send_response(status)
                self.send_header("Content-Type", content_type)
                self.send_header("Content-Length", str(len(body)))
                self.send_header("Access-Control-Allow-Origin", router.allow_origin)
                self.send_header("Cache-Control", "no-store")
                for key, value in (headers or {}).items():
                    self.send_header(key, value)
                self.end_headers()
                self.wfile.write(body)
            except (BrokenPipeError, ConnectionResetError):
                self.close_connection = True  # the tab moved on mid-response

        def _json(self, obj, status=200):
            self._send(json.dumps(obj).encode("utf-8"), "application/json", status)

        def do_OPTIONS(self):
            self.send_response(204)
            self.send_header("Access-Control-Allow-Origin", router.allow_origin)
            self.send_header("Access-Control-Allow-Methods", "GET, OPTIONS")
            self.send_header("Content-Length", "0")
            self.end_headers()

        def do_GET(self):
            router.route(self, self.path.split("?", 1)[0])

    return Handler


def _route_scene(server, h, path):
    """The per-scene routes of the module docstring, for one scene server."""
    source = server.source
    if path == "/healthz":
        h._json({"ok": True, "scene": source.scene_id, "frames": int(source.frame_count)})
        return
    if path == "/scene.json":
        h._json(source.describe(server.base_url)["scene"])
        return
    match = FRAME_POINTS_RE.match(path)
    if match:
        _serve_points(server, h, int(match.group(1)))
        return
    match = FRAME_IMAGE_RE.match(path)
    if match:
        _serve_image(server, h, int(match.group(1)), match.group(2))
        return
    if path == "/aggregate/meta.json":
        meta = server.aggregate_meta()
        h._json(meta, 200 if meta.get("ready") else 202)
        return
    match = AGGREGATE_CHUNK_RE.match(path)
    if match:
        blob = server.aggregate_chunk(int(match.group(1)))
        if blob is None:
            h._json({"error": "no such chunk"}, 404)
        else:
            h._send(blob, "application/octet-stream")
        return
    h._json({"error": "not found"}, 404)


def _serve_points(server, h, index):
    source = server.source
    if not 0 <= index < source.frame_count:
        h._json({"error": "frame out of range"}, 404)
        return
    with server._waiting_lock:
        server._waiting += 1
    try:
        blob = server.points_blob(index, still_wanted=lambda: not client_gone(h.connection))
    except Exception as exc:
        server.log("scene: frame %d failed: %r" % (index, exc))
        h._json({"error": repr(exc)}, 500)
        return
    finally:
        with server._waiting_lock:
            server._waiting -= 1
    if blob is GONE:
        # Nobody is listening any more; say so cheaply rather than spending
        # seconds producing an answer for a closed socket.
        h.close_connection = True
        h._json({"error": "cancelled"}, 409)
        return
    h._send(blob, "application/octet-stream")


def _serve_image(server, h, index, camera):
    source = server.source
    if camera not in source.cameras:
        h._json({"error": "unknown camera"}, 404)
        return
    image = source.image_path(camera, index)
    if not image or not image.is_file():
        h._json({"error": "no image"}, 404)
        return
    try:
        body = image.read_bytes()
    except OSError as exc:
        h._json({"error": repr(exc)}, 500)
        return
    h._send(body, "image/jpeg")
