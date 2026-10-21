"""Serving frames: one decode at a time, shared, and dropped when abandoned.

A frame costs seconds of CPU. `ThreadingHTTPServer` gives every request its
own thread, so without these rules a few taps on the step key put eight
decodes on the machine at once and each one took about six times longer --
including the frame the user was actually waiting for.
"""

import http.client
import socket
import threading
import time
import unittest

import numpy as np

from ll2_scene.http import GONE, SceneHttpServer, client_gone
from ll2_scene.points import decode_points


class StubSource:
    """Stands in for a SceneSource; counts and paces its decodes."""

    scene_id = "stub-scene"
    frame_count = 20
    cameras = ["camera_ring_front"]
    anchor = (49.0, 8.4)

    def __init__(self, decode_seconds=0.3):
        self.decode_seconds = decode_seconds
        self.decodes = []
        self.live = 0
        self.max_live = 0
        self._lock = threading.Lock()

    def frame_points_scene(self, index, lidars=None):
        with self._lock:
            self.live += 1
            self.max_live = max(self.max_live, self.live)
            self.decodes.append(index)
        try:
            time.sleep(self.decode_seconds)
            xyz = np.full((4, 3), float(index))
            return xyz, np.zeros(4, np.float32), np.zeros(4, np.uint8)
        finally:
            with self._lock:
                self.live -= 1

    def to_enu(self, xyz):
        return xyz

    def image_path(self, camera, index):
        return None

    def describe(self, base_url):
        return {"scene": {"id": self.scene_id, "base": base_url}}


class ServingTest(unittest.TestCase):
    def setUp(self):
        self.source = StubSource()
        self.server = SceneHttpServer(self.source, port=0, log=lambda _m: None)
        self.server.start()

    def tearDown(self):
        self.server.stop()

    def _get(self, path, results=None, key=None):
        conn = http.client.HTTPConnection("127.0.0.1", self.server.port, timeout=30)
        try:
            conn.request("GET", path)
            response = conn.getresponse()
            body = response.read()
            if results is not None:
                results[key] = (response.status, body)
            return response.status, body
        finally:
            conn.close()

    def test_serves_a_frame(self):
        status, body = self._get("/frame/3/points.bin")
        self.assertEqual(200, status)
        xyz, _, _, _ = decode_points(body)
        self.assertEqual(4, len(xyz))
        np.testing.assert_allclose(xyz, 3.0)

    def test_only_one_decode_runs_at_a_time(self):
        threads = [threading.Thread(target=self._get, args=("/frame/%d/points.bin" % i,))
                   for i in range(6)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()
        self.assertEqual(1, self.source.max_live,
                         "%d decodes ran at once" % self.source.max_live)

    def test_the_same_frame_is_decoded_once_for_everyone(self):
        results = {}
        threads = [threading.Thread(target=self._get,
                                    args=("/frame/9/points.bin", results, i))
                   for i in range(4)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()
        self.assertEqual([9], self.source.decodes, "the frame was decoded more than once")
        self.assertEqual(4, len(results))
        bodies = {body for _status, body in results.values()}
        self.assertEqual(1, len(bodies), "callers got different answers")

    def test_a_second_request_is_answered_from_memory(self):
        self._get("/frame/2/points.bin")
        self._get("/frame/2/points.bin")
        self.assertEqual([2], self.source.decodes)

    def test_queued_work_is_dropped_when_the_browser_leaves(self):
        slow = threading.Thread(target=self._get, args=("/frame/0/points.bin",))
        slow.start()
        time.sleep(0.05)  # let it take the decoder

        # Queue a request, then hang up like an aborted fetch does.
        sock = socket.create_connection(("127.0.0.1", self.server.port), timeout=5)
        sock.sendall(b"GET /frame/1/points.bin HTTP/1.1\r\nHost: x\r\n\r\n")
        time.sleep(0.05)
        sock.close()
        slow.join()
        time.sleep(0.6)
        self.assertNotIn(1, self.source.decodes,
                         "decoded a frame whose browser had already gone")

    def test_out_of_range(self):
        status, _ = self._get("/frame/999/points.bin")
        self.assertEqual(404, status)

    def test_healthz_and_scene_json(self):
        self.assertEqual(200, self._get("/healthz")[0])
        self.assertEqual(200, self._get("/scene.json")[0])
        self.assertEqual(404, self._get("/nope")[0])

    def test_a_vanishing_client_does_not_take_the_server_down(self):
        sock = socket.create_connection(("127.0.0.1", self.server.port), timeout=5)
        sock.sendall(b"GET /scene.json HTTP/1.1\r\nHost: x\r\n\r\n")
        sock.close()
        time.sleep(0.2)
        self.assertEqual(200, self._get("/healthz")[0], "the server stopped serving")


class ClientGoneTest(unittest.TestCase):
    def test_detects_a_closed_peer(self):
        a, b = socket.socketpair()
        try:
            self.assertFalse(client_gone(a))
            b.close()
            self.assertTrue(client_gone(a))
        finally:
            a.close()

    def test_pending_bytes_are_not_a_closed_peer(self):
        a, b = socket.socketpair()
        try:
            b.sendall(b"GET / HTTP/1.1\r\n")
            self.assertFalse(client_gone(a))
        finally:
            a.close()
            b.close()


class GoneSentinelTest(unittest.TestCase):
    def test_gone_is_distinct_from_an_empty_answer(self):
        self.assertIsNot(GONE, b"")
        self.assertIsNot(GONE, None)


if __name__ == "__main__":
    unittest.main()


class PrewarmTest(unittest.TestCase):
    """Warming must never cost the user a step."""

    def setUp(self):
        self.source = StubSource(decode_seconds=0.05)
        self.source.frame_count = 8
        self.server = SceneHttpServer(self.source, port=0, log=lambda _m: None)
        self.server.start()

    def tearDown(self):
        self.server.stop()

    def _get(self, path):
        conn = http.client.HTTPConnection("127.0.0.1", self.server.port, timeout=30)
        try:
            conn.request("GET", path)
            response = conn.getresponse()
            return response.status, response.read()
        finally:
            conn.close()

    def test_fills_every_frame(self):
        self.server.prewarm()
        deadline = time.time() + 20
        while time.time() < deadline and len(set(self.source.decodes)) < 8:
            time.sleep(0.05)
        self.assertEqual(set(range(8)), set(self.source.decodes))

    def test_a_browser_request_still_gets_through_quickly(self):
        self.server.prewarm()
        time.sleep(0.08)
        t0 = time.time()
        status, _ = self._get("/frame/7/points.bin")
        waited = time.time() - t0
        self.assertEqual(200, status)
        # At worst one prewarm decode is already running; it must not be more.
        self.assertLess(waited, 1.0, "waited %.2fs behind the prewarm" % waited)

    def test_stopping_the_server_stops_the_warmer(self):
        self.source.decode_seconds = 0.2
        self.server.prewarm()
        time.sleep(0.1)
        self.server.stop()
        seen = len(self.source.decodes)
        time.sleep(0.6)
        self.assertLessEqual(len(self.source.decodes) - seen, 1,
                             "the warmer kept going after stop()")
        self.server.start()  # so tearDown's stop() has something to stop
