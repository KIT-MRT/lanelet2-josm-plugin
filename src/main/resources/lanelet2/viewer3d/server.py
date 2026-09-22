#!/usr/bin/env python3
"""
Lanelet2 live 3D viewer server (separate process, stdlib only).

This is the "viewer" half of the JOSM -> browser 3D pipeline:

    JOSM (Jython bridge)  --TCP newline JSON-->  THIS SERVER  --SSE-->  browser tab (Three.js)

Two listeners run in this one process:

  * TCP ingest (default 127.0.0.1:8766): the JOSM bridge connects and streams
    newline-delimited JSON messages (snapshot / patch / clear). No file export.
  * HTTP + SSE (default 127.0.0.1:8765): serves the static Three.js page and a
    Server-Sent-Events stream (/events) that pushes the live scene to every
    open browser tab. A late-joining tab immediately receives a full snapshot.

Why SSE instead of WebSocket: SSE is one-directional (server -> browser), which
is all the viewer needs, and it works with nothing but the Python standard
library. No pip install required.

Run:
    python3 server.py                 # starts both servers, opens a browser tab
    python3 server.py --no-browser    # don't auto-open the browser
    python3 server.py --http-port 8765 --ingest-port 8766

Protocol (see PROTOCOL section below) is intentionally tiny and additive.
"""

import argparse
import json
import os
import queue
import re
import socketserver
import sys
import threading
import time
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import unquote, urlparse

STATIC_DIR = Path(__file__).resolve().parent / "static"
VIEWER_DIR = Path(__file__).resolve().parent

# Content hash of the shipped viewer, written next to this file by the plugin
# when it extracts the jar. /healthz reports it, so a JOSM running a different
# plugin build recycles this process instead of adopting it: an adopted server
# keeps its own routes and may serve a different extract. None when run from
# a source tree.
BUILD_FILE = VIEWER_DIR / ".shipped_version"
BUILD_ID = None

# Populated in main(). Directory of JOSM MapCSS / preset icons, served at
# /style_images/<filename>. None if no directory could be found.
ICONS_DIR = None

# Basename-only icon names (German StVO files like 274-30.png, 264-2.3.png).
_ICON_NAME_RE = re.compile(
    r"^[A-Za-z0-9][A-Za-z0-9._-]*\.(png|svg|jpe?g|gif|webp)$", re.IGNORECASE)
_ICON_CONTENT_TYPES = {
    ".png": "image/png",
    ".svg": "image/svg+xml",
    ".jpg": "image/jpeg",
    ".jpeg": "image/jpeg",
    ".gif": "image/gif",
    ".webp": "image/webp",
}


def _tooling_root():
    env = os.environ.get("LL2_TOOLING_ROOT", "").strip()
    if env:
        return Path(os.path.expanduser(env)).resolve()
    return VIEWER_DIR.parent


def resolve_icons_dir(explicit=None):
    """Return the first existing style_images directory, or None."""
    if explicit:
        path = Path(os.path.expanduser(explicit)).resolve()
        if path.is_dir():
            return path
        return None
    root = _tooling_root()
    candidates = (
        root / "JOSM_lanelet2_editing_scripts" / "core" / "presets" / "style_images",
        root / "Lanelet2" / "lanelet2_maps" / "josm" / "style_images",
    )
    for candidate in candidates:
        if candidate.is_dir():
            return candidate.resolve()
    return None

# Profiling is opt-in: --profile flag or VIEWER3D_PROFILE=1. When on, the server
# prints a one-line timing breakdown for ingest parse, scene apply, SSE fan-out,
# per-client serialization, and command forwarding, so large-map slowness can be
# attributed to a stage rather than guessed at.
PROFILE = bool(os.environ.get("VIEWER3D_PROFILE"))


def _plog(msg):
    print("[viewer][perf] " + msg, flush=True)


def _sse_encode(msg):
    """Serialize a message to its full SSE wire form, once, for all clients."""
    payload = json.dumps(msg, separators=(",", ":"))
    return ("data: %s\n\n" % payload).encode("utf-8")

# ---------------------------------------------------------------------------
# PROTOCOL
# ---------------------------------------------------------------------------
# Messages are JSON objects. Over TCP they are newline-delimited (one per line).
# Over SSE each message is sent as a single `data:` event.
#
#   snapshot : full replacement of the scene
#     {"type":"snapshot",
#      "anchor":{"lat":48.13,"lon":11.57},
#      "features":[<feature>, ...]}
#
#   patch    : incremental change
#     {"type":"patch","ops":[
#        {"op":"upsert","feature":<feature>},
#        {"op":"remove","id":"way/-123"},
#        {"op":"anchor","lat":48.13,"lon":11.57}]}
#
#   clear    : empty the scene
#     {"type":"clear"}
#
#   feature  : one renderable object (a polyline for v0)
#     {"id":"way/-123",
#      "kind":"line",
#      "tags":{"type":"line_thin","subtype":"dashed"},
#      "points":[[x,y,z], ...],         # local ENU metres relative to anchor
#      "color":"#ffffff"}               # optional; browser falls back to tags
#
# The browser overlays style_images icons for traffic_sign / traffic_light /
# arrow / symbol from `tags` (see static/app.js). The server just serves the
# files at GET /style_images/<filename>.
# ---------------------------------------------------------------------------


class SceneHub:
    """Authoritative in-memory scene + fan-out to SSE subscribers.

    The hub keeps the latest full scene so any newly connected browser can be
    bootstrapped with a snapshot, then receives subsequent messages live.
    """

    def __init__(self):
        self._lock = threading.Lock()
        self._anchor = None                 # {"lat":..,"lon":..} or None
        self._features = {}                 # id -> feature dict
        self._seq = 0                       # monotonically increasing
        self._subscribers = set()           # set[queue.Queue]
        self._bridges = set()               # set[_BridgeConn] (command back-channel)

    # -- subscription (SSE side) -------------------------------------------
    def subscribe(self):
        q = queue.Queue(maxsize=1000)
        with self._lock:
            self._subscribers.add(q)
            bootstrap = self._snapshot_message_locked()
        # Hand the new client a full snapshot first (pre-encoded SSE bytes).
        q.put(_sse_encode(bootstrap))
        return q

    def unsubscribe(self, q):
        with self._lock:
            self._subscribers.discard(q)

    # -- command back-channel (browser -> JOSM bridge) ---------------------
    def add_bridge(self, conn):
        with self._lock:
            self._bridges.add(conn)

    def remove_bridge(self, conn):
        with self._lock:
            self._bridges.discard(conn)

    def send_to_bridges(self, line):
        """Forward a command line to every connected bridge. Returns count."""
        with self._lock:
            bridges = list(self._bridges)
        delivered = 0
        for conn in bridges:
            if conn.send(line):
                delivered += 1
            else:
                self.remove_bridge(conn)
        return delivered

    # -- ingest (TCP side) -------------------------------------------------
    def handle_incoming(self, msg):
        """Apply an inbound message to the scene and broadcast it."""
        t0 = time.perf_counter() if PROFILE else 0.0
        with self._lock:
            self._apply_locked(msg)
            self._seq += 1
            msg = dict(msg)
            msg["seq"] = self._seq
            subscribers = list(self._subscribers)
            feat_count = len(self._features)
        t1 = time.perf_counter() if PROFILE else 0.0
        # Serialize ONCE for every subscriber instead of per client.
        data = _sse_encode(msg)
        t2 = time.perf_counter() if PROFILE else 0.0
        self._broadcast(subscribers, data)
        if PROFILE:
            mtype = msg.get("type")
            extra = ""
            if mtype == "patch":
                extra = " ops=%d" % len(msg.get("ops", []))
            _plog("ingest type=%s seq=%d feats=%d subs=%d%s bytes=%d apply=%.2fms "
                  "serialize=%.2fms fanout=%.2fms"
                  % (mtype, self._seq, feat_count, len(subscribers), extra,
                     len(data), (t1 - t0) * 1000.0, (t2 - t1) * 1000.0,
                     (time.perf_counter() - t2) * 1000.0))

    def _apply_locked(self, msg):
        mtype = msg.get("type")
        if mtype == "snapshot":
            self._anchor = msg.get("anchor")
            self._features = {
                f["id"]: f for f in msg.get("features", []) if "id" in f
            }
        elif mtype == "patch":
            for op in msg.get("ops", []):
                kind = op.get("op")
                if kind == "upsert":
                    feat = op.get("feature")
                    if feat and "id" in feat:
                        self._features[feat["id"]] = feat
                elif kind == "remove":
                    self._features.pop(op.get("id"), None)
                elif kind == "anchor":
                    self._anchor = {"lat": op.get("lat"), "lon": op.get("lon")}
        elif mtype == "clear":
            self._features = {}

    def _snapshot_message_locked(self):
        return {
            "type": "snapshot",
            "seq": self._seq,
            "anchor": self._anchor,
            "features": list(self._features.values()),
        }

    def snapshot_message(self):
        with self._lock:
            return self._snapshot_message_locked()

    def _broadcast(self, subscribers, data):
        for q in subscribers:
            try:
                q.put_nowait(data)
            except queue.Full:
                # Slow client: drop it rather than stall ingest.
                self.unsubscribe(q)


HUB = SceneHub()


class _BridgeConn:
    """Thread-safe writer for the bridge TCP socket (command back-channel).

    The bridge connection is full-duplex: the bridge streams telemetry on the
    read side while the server writes newline-delimited command messages back
    on the write side. Writes may originate from any HTTP worker thread, so
    they are serialized with a lock.
    """

    def __init__(self, wfile):
        self._wfile = wfile
        self._lock = threading.Lock()
        self._alive = True

    def send(self, line):
        with self._lock:
            if not self._alive:
                return False
            try:
                self._wfile.write((line + "\n").encode("utf-8"))
                self._wfile.flush()
                return True
            except OSError:
                self._alive = False
                return False


# ---------------------------------------------------------------------------
# TCP ingest: newline-delimited JSON from the JOSM bridge
# ---------------------------------------------------------------------------
class IngestHandler(socketserver.StreamRequestHandler):
    def handle(self):
        peer = self.client_address
        print("[ingest] connected: %s:%s" % peer, flush=True)
        conn = _BridgeConn(self.wfile)
        HUB.add_bridge(conn)
        try:
            for raw in self.rfile:  # iterates by line; blocks until disconnect
                line = raw.strip()
                if not line:
                    continue
                t0 = time.perf_counter() if PROFILE else 0.0
                try:
                    msg = json.loads(line.decode("utf-8"))
                except (ValueError, UnicodeDecodeError) as exc:
                    print("[ingest] bad JSON line ignored: %s" % exc, flush=True)
                    continue
                if PROFILE:
                    _plog("recv bytes=%d parse=%.2fms"
                          % (len(line), (time.perf_counter() - t0) * 1000.0))
                HUB.handle_incoming(msg)
        except (ConnectionError, OSError) as exc:
            print("[ingest] connection error: %s" % exc, flush=True)
        finally:
            HUB.remove_bridge(conn)
            print("[ingest] disconnected: %s:%s" % peer, flush=True)


class ThreadingTCPServer(socketserver.ThreadingTCPServer):
    daemon_threads = True
    allow_reuse_address = True


# ---------------------------------------------------------------------------
# HTTP + SSE: static files and the live event stream to browsers
# ---------------------------------------------------------------------------
SSE_HEARTBEAT_SECONDS = 15.0


def _shutdown_soon():
    """Flush the /shutdown response, then exit hard.

    The accept loops sit on daemon threads with no clean stop signal and this
    process owns nothing that needs unwinding.
    """
    time.sleep(0.2)
    os._exit(0)


class HttpHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):  # quieter default logging
        if self.path != "/events":
            super().log_message(fmt, *args)

    def do_GET(self):
        path = urlparse(self.path).path
        if path == "/events":
            self._serve_sse()
        elif path in ("/", "/index.html"):
            self._serve_static("index.html", "text/html; charset=utf-8")
        elif path == "/app.js":
            self._serve_static("app.js", "application/javascript; charset=utf-8")
        elif path.startswith("/vendor/"):
            self._serve_vendor(unquote(path[len("/vendor/"):]))
        elif path.startswith("/js/"):
            self._serve_module(unquote(path[len("/js/"):]))
        elif path == "/state":
            self._serve_json(HUB.snapshot_message())
        elif path == "/healthz":
            self._serve_json({"ok": True, "build": BUILD_ID})
        elif path.startswith("/style_images/"):
            name = unquote(path[len("/style_images/"):])
            self._serve_icon(name)
        else:
            self.send_error(404, "Not found")

    def do_POST(self):
        if self.path == "/command":
            self._handle_command()
        elif self.path == "/shutdown":
            self._handle_shutdown()
        else:
            self.send_error(404, "Not found")

    def _handle_shutdown(self):
        """
        Let a JOSM that did not spawn this process still stop it.

        Without this, a server left over from an earlier session keeps the port
        and answers /healthz, so the plugin reports "running", refuses to start
        a fresh one, and has no handle to kill the old one. We only ever bind
        loopback, so the reachable callers are local.
        """
        self._serve_json({"ok": True})
        threading.Thread(target=_shutdown_soon, daemon=True).start()

    def _handle_command(self):
        try:
            length = int(self.headers.get("Content-Length", "0") or 0)
        except ValueError:
            length = 0
        body = self.rfile.read(length) if length else b""
        try:
            msg = json.loads(body.decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            self.send_error(400, "Bad JSON")
            return
        if not isinstance(msg, dict) or msg.get("type") != "command":
            self.send_error(400, "Expected {\"type\":\"command\",...}")
            return
        t0 = time.perf_counter() if PROFILE else 0.0
        line = json.dumps(msg, separators=(",", ":"))
        delivered = HUB.send_to_bridges(line)
        self._serve_json({"ok": delivered > 0, "delivered": delivered})
        if PROFILE:
            _plog("command ops=%d bytes=%d delivered=%d forward=%.2fms"
                  % (len(msg.get("ops", [])), len(line), delivered,
                     (time.perf_counter() - t0) * 1000.0))

    # -- helpers -----------------------------------------------------------
    def _serve_json(self, obj):
        body = json.dumps(obj).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _serve_vendor(self, rel):
        # Version-pinned third-party ES modules (three.js and its controls),
        # shipped so the viewer works without network access.
        self._serve_contained(STATIC_DIR / "vendor", rel,
                              "public, max-age=31536000, immutable")

    def _serve_module(self, rel):
        # The viewer's own ES modules (static/js/). Uncached like app.js so a
        # restarted server never pairs a new page with old modules.
        if not rel.endswith(".js"):
            self.send_error(404, "Not found")
            return
        self._serve_contained(STATIC_DIR / "js", rel, "no-store")

    def _serve_contained(self, base_dir, rel, cache_control):
        """Serve `rel` under `base_dir`, refusing anything that resolves outside."""
        base = base_dir.resolve()
        try:
            target = (base / rel).resolve()
        except OSError:
            self.send_error(404, "Not found")
            return
        if target != base and base not in target.parents:
            self.send_error(403, "Forbidden")
            return
        try:
            body = target.read_bytes()
        except OSError:
            self.send_error(404, "Missing file: %s" % rel)
            return
        ctype = ("application/javascript; charset=utf-8"
                 if target.suffix == ".js" else "application/octet-stream")
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", cache_control)
        self.end_headers()
        self.wfile.write(body)

    def _serve_static(self, name, content_type):
        path = STATIC_DIR / name
        try:
            body = path.read_bytes()
        except OSError:
            self.send_error(404, "Missing static file: %s" % name)
            return
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _serve_icon(self, name):
        """Serve a basename from ICONS_DIR. Rejects path traversal."""
        if ICONS_DIR is None:
            self.send_error(404, "No style_images directory")
            return
        if not name or not _ICON_NAME_RE.match(name):
            self.send_error(400, "Bad icon name")
            return
        candidate = ICONS_DIR / name
        try:
            resolved = candidate.resolve()
            resolved.relative_to(ICONS_DIR)
        except (OSError, ValueError):
            self.send_error(404, "Not found")
            return
        if not resolved.is_file():
            self.send_error(404, "Not found")
            return
        content_type = _ICON_CONTENT_TYPES.get(
            resolved.suffix.lower(), "application/octet-stream")
        try:
            body = resolved.read_bytes()
        except OSError:
            self.send_error(404, "Not found")
            return
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "public, max-age=3600")
        self.end_headers()
        self.wfile.write(body)

    def _serve_sse(self):
        q = HUB.subscribe()
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "keep-alive")
        self.send_header("X-Accel-Buffering", "no")
        self.end_headers()
        try:
            while True:
                try:
                    data = q.get(timeout=SSE_HEARTBEAT_SECONDS)
                except queue.Empty:
                    # Comment line keeps the connection (and proxies) alive.
                    self.wfile.write(b": ping\n\n")
                    self.wfile.flush()
                    continue
                # `data` is already-encoded SSE bytes (serialized once by the
                # hub for all clients); this thread only writes to its socket.
                t0 = time.perf_counter() if PROFILE else 0.0
                self.wfile.write(data)
                self.wfile.flush()
                if PROFILE:
                    _plog("sse client=%s bytes=%d write=%.2fms"
                          % (self.client_address[1], len(data),
                             (time.perf_counter() - t0) * 1000.0))
        except (ConnectionError, OSError):
            pass
        finally:
            HUB.unsubscribe(q)


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------
def main(argv=None):
    parser = argparse.ArgumentParser(description="Lanelet2 live 3D viewer server")
    parser.add_argument("--host", default="127.0.0.1",
                        help="bind address (default 127.0.0.1; keep local)")
    parser.add_argument("--http-port", type=int, default=8765)
    parser.add_argument("--ingest-port", type=int, default=8766)
    parser.add_argument("--no-browser", action="store_true",
                        help="do not auto-open a browser tab")
    parser.add_argument("--profile", action="store_true",
                        help="print per-stage timing logs (or set VIEWER3D_PROFILE=1)")
    parser.add_argument("--icons-dir", default=None,
                        help="directory of style_images to serve at /style_images/ "
                             "(auto-detected from the tooling root if omitted)")
    args = parser.parse_args(argv)

    global PROFILE, ICONS_DIR, BUILD_ID
    PROFILE = PROFILE or args.profile
    try:
        BUILD_ID = BUILD_FILE.read_text(encoding="utf-8").strip() or None
    except OSError:
        BUILD_ID = None
    if PROFILE:
        print("[viewer] profiling ON", flush=True)

    ICONS_DIR = resolve_icons_dir(args.icons_dir)
    if args.icons_dir and ICONS_DIR is None:
        print("[viewer] --icons-dir is not a directory: %s" % args.icons_dir,
              flush=True)
        return 2

    ingest = ThreadingTCPServer((args.host, args.ingest_port), IngestHandler)
    http = ThreadingHTTPServer((args.host, args.http_port), HttpHandler)

    threading.Thread(target=ingest.serve_forever, name="ingest",
                     daemon=True).start()
    threading.Thread(target=http.serve_forever, name="http",
                     daemon=True).start()

    url = "http://%s:%d/" % (args.host, args.http_port)
    print("[viewer] HTTP/SSE : %s" % url, flush=True)
    print("[viewer] ingest   : tcp://%s:%d (JOSM bridge connects here)"
          % (args.host, args.ingest_port), flush=True)
    if ICONS_DIR is not None:
        print("[viewer] icons    : %s" % ICONS_DIR, flush=True)
    else:
        print("[viewer] icons    : none (traffic-element icons disabled)",
              flush=True)

    if not args.no_browser:
        # Open after a short delay so the server is definitely accepting.
        threading.Timer(0.6, lambda: webbrowser.open(url)).start()

    try:
        while True:
            time.sleep(3600)
    except KeyboardInterrupt:
        print("\n[viewer] shutting down", flush=True)
        ingest.shutdown()
        http.shutdown()
    return 0


if __name__ == "__main__":
    sys.exit(main())
