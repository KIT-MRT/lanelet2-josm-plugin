"""Starting the scene server from JOSM: waiting for its map, finding kitscenes, picking the city."""

import argparse
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from ll2_scene import scene
from ll2_scene.__main__ import open_city_index, wait_for_viewer_anchor
from tests.test_city import FRANKFURT, KARLSRUHE, write_scene


class FakeClock:
    def __init__(self):
        self.t = 0.0

    def __call__(self):
        return self.t

    def sleep(self, dt):
        self.t += dt


class WaitForMapTest(unittest.TestCase):
    def run_wait(self, answers, seconds):
        clock = FakeClock()
        calls = []

        def probe(host, port):
            calls.append(clock.t)
            return answers(clock.t)
        logs = []
        got = wait_for_viewer_anchor("h", 1, seconds, logs.append, probe=probe, sleep=clock.sleep, clock=clock)
        return got, calls, logs

    def test_a_map_already_there_is_taken_at_once(self):
        got, calls, logs = self.run_wait(lambda t: (49.0, 8.4), 60)
        self.assertEqual((49.0, 8.4), got)
        self.assertEqual(1, len(calls))
        self.assertEqual([], logs)

    def test_waits_for_josms_first_snapshot(self):
        got, calls, logs = self.run_wait(lambda t: (49.0, 8.4) if t >= 3 else None, 60)
        self.assertEqual((49.0, 8.4), got)
        self.assertLessEqual(calls[-1], 3.5)
        self.assertIn("waiting", logs[0])

    def test_gives_up_and_says_so(self):
        got, calls, logs = self.run_wait(lambda t: None, 5)
        self.assertIsNone(got)
        self.assertLessEqual(calls[-1], 5.5)
        self.assertIn("going on without it", logs[-1])

    def test_no_wait_unless_asked(self):
        got, calls, _ = self.run_wait(lambda t: None, 0)
        self.assertIsNone(got)
        self.assertEqual(1, len(calls))


class KitscenesPathTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.checkout = Path(self.tmp.name) / "kitscenes-checkout"
        (self.checkout / "kitscenes").mkdir(parents=True)
        (self.checkout / "kitscenes" / "__init__.py").write_text("")
        self.path_before = list(sys.path)

    def tearDown(self):
        sys.path[:] = self.path_before
        self.tmp.cleanup()

    def test_a_named_checkout_wins(self):
        with mock.patch.dict(os.environ, {"KITSCENES_API_PATH": ""}):
            self.assertEqual(self.checkout, scene.add_kitscenes_to_path(str(self.checkout)))
        self.assertEqual(str(self.checkout), sys.path[0])

    def test_the_environment_names_one_too(self):
        with mock.patch.dict(os.environ, {"KITSCENES_API_PATH": str(self.checkout)}):
            self.assertEqual(self.checkout, scene.add_kitscenes_to_path())

    def test_an_installed_package_beats_a_checkout_next_to_the_repo(self):
        with mock.patch.dict(os.environ, {"KITSCENES_API_PATH": ""}), \
                mock.patch("importlib.util.find_spec", return_value=object()):
            self.assertIsNone(scene.add_kitscenes_to_path())
        self.assertEqual(self.path_before, sys.path, "an installed package needs no path")


class CityFromMapTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        for i in range(3):
            write_scene(self.root, "val", "ffff-%d" % i, FRANKFURT, start=(10 * i, 0))
        write_scene(self.root, "val", "kkkk-1", KARLSRUHE)

    def tearDown(self):
        self.tmp.cleanup()

    def index(self, anchor, city_name=None):
        args = argparse.Namespace(city=str(self.root), city_name=city_name, rescan=False, index_workers=4)
        logs = []
        return open_city_index(args, anchor, None, logs.append), logs

    def test_without_a_map_the_city_with_most_scenes(self):
        self.assertEqual("frankfurt", self.index(None)[0].city)

    def test_josms_map_picks_the_city(self):
        self.assertEqual("karlsruhe", self.index((49.0143900, 8.4172200))[0].city)

    def test_a_map_elsewhere_falls_back_and_says_so(self):
        index, logs = self.index((48.7133, 9.0028))   # Sindelfingen: no scenes here
        self.assertEqual("frankfurt", index.city)
        self.assertIn("sindelfingen", logs[0])

    def test_a_named_city_wins_and_does_not_fall_back(self):
        self.assertEqual("frankfurt", self.index((49.01, 8.41), city_name="frankfurt")[0].city)
        with self.assertRaises(ValueError):
            self.index(None, city_name="sindelfingen")


if __name__ == "__main__":
    unittest.main()
