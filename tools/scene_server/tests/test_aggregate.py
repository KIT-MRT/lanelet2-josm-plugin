"""Aggregating a scene, and taking the traffic out of it."""

import unittest

import numpy as np

from ll2_scene.aggregate import (SceneAggregator, chunk_by_tile, merge_counts,
                                 merge_sorted, voxel_keys)


class VoxelKeyTest(unittest.TestCase):
    def test_same_voxel_same_key(self):
        a = voxel_keys(np.array([[1.01, 2.01, 3.01]]), 0.2)
        b = voxel_keys(np.array([[1.09, 2.09, 3.09]]), 0.2)
        self.assertEqual(a[0], b[0])

    def test_neighbouring_voxels_differ(self):
        keys = voxel_keys(np.array([[0.0, 0.0, 0.0], [0.3, 0.0, 0.0],
                                    [0.0, 0.3, 0.0], [0.0, 0.0, 0.3]]), 0.2)
        self.assertEqual(4, len(set(keys.tolist())))

    def test_negative_coordinates(self):
        keys = voxel_keys(np.array([[-500.0, -1200.0, 140.0], [-500.1, -1200.1, 140.0]]), 0.2)
        self.assertEqual(2, len(keys))

    def test_refuses_points_it_cannot_pack(self):
        with self.assertRaises(ValueError):
            voxel_keys(np.array([[1e9, 0.0, 0.0]]), 0.2)


class MergeCountsTest(unittest.TestCase):
    def test_sums_overlapping_keys(self):
        keys, counts = merge_counts(np.array([1, 3, 5]), np.array([1, 2, 3]),
                                    np.array([3, 4]), np.array([5, 1]))
        self.assertEqual([1, 3, 4, 5], keys.tolist())
        self.assertEqual([1, 7, 1, 3], counts.tolist())

    def test_empty_sides(self):
        keys, counts = merge_counts(np.zeros(0, np.int64), np.zeros(0, np.int32),
                                    np.array([2]), np.array([4]))
        self.assertEqual([2], keys.tolist())
        self.assertEqual([4], counts.tolist())


class MovingObjectSource:
    """A straight drive past a static wall, with one car driving alongside.

    The wall is hit from every frame; the car is somewhere else each time,
    which is exactly the smear that aggregating a real scene produces.
    """

    scene_id = "stub"
    frame_count = 12

    def __init__(self):
        self.timestamps = np.arange(self.frame_count) * 0.1
        self.anchor = (49.0, 8.4)
        self.wall = np.stack([
            np.zeros(400) + 6.0,
            np.linspace(0.0, 60.0, 400),
            np.tile(np.linspace(0.0, 3.0, 20), 20),
        ], axis=1)

    def pose_at(self, time_s):
        return np.eye(3), np.array([0.0, float(time_s) * 10.0, 0.0])

    def frame_points_scene(self, index, lidars=None):
        ego_y = index * 1.0
        # A car 8 m ahead, moving with us: a different place every frame.
        car = np.random.default_rng(index).uniform(-0.9, 0.9, size=(300, 3))
        car[:, 0] += -3.0
        car[:, 1] += ego_y + 8.0
        car[:, 2] += 0.8
        pts = np.vstack([self.wall, car])
        return pts, np.full(len(pts), 0.5, np.float32), np.zeros(len(pts), np.uint8)

    def to_enu(self, xyz):
        return xyz


class AggregateTest(unittest.TestCase):
    def setUp(self):
        self.source = MovingObjectSource()

    def _build(self, **kw):
        options = dict(voxel=0.3, max_range_m=200.0, min_run=3,
                       ego_box=(0.1, 0.1, 0.1), log=lambda _m: None)
        options.update(kw)
        agg = SceneAggregator(self.source, disk_cache=None, **options)
        return agg.build()

    def test_the_moving_car_is_removed_and_the_wall_kept(self):
        xyz, _intensity, _classes, _rgb, stats = self._build()
        near_wall = np.abs(xyz[:, 0] - 6.0) < 1.0
        on_car = xyz[:, 0] < -1.0
        self.assertGreater(near_wall.sum(), 100, "the static wall was thrown away")
        self.assertEqual(0, on_car.sum(), "%d moving-car points survived" % on_car.sum())
        self.assertEqual(12, stats["frames"])
        self.assertLess(stats["static_voxels"], stats["voxels"])

    def test_a_car_crossing_the_same_spot_three_times_is_still_not_static(self):
        """Counting distinct frames leaks on a long scene; a run does not."""
        source = MovingObjectSource()
        wall = source.wall

        def three_passes(index, lidars=None):
            # The same patch of road is occupied in frames 0, 5 and 10 by
            # three different cars -- three hits, never consecutive.
            pts = wall
            if index in (0, 5, 10):
                blob = np.random.default_rng(index).uniform(-0.4, 0.4, size=(200, 3))
                blob[:, 0] += -3.0
                blob[:, 1] += 20.0
                blob[:, 2] += 0.8
                pts = np.vstack([wall, blob])
            return pts, np.full(len(pts), 0.5, np.float32), np.zeros(len(pts), np.uint8)

        source.frame_points_scene = three_passes
        agg = SceneAggregator(source, disk_cache=None, voxel=0.3, max_range_m=200.0,
                              min_run=3, ego_box=(0.1, 0.1, 0.1), log=lambda _m: None)
        xyz, _i, _c, _rgb, _stats = agg.build()
        self.assertEqual(0, (xyz[:, 0] < -1.0).sum(),
                         "three separate passes were mistaken for a static object")

    def test_keeping_everything_keeps_the_car(self):
        xyz, _intensity, _classes, _rgb, _stats = self._build(min_run=1)
        self.assertGreater((xyz[:, 0] < -1.0).sum(), 100,
                           "min_run=1 should keep the traffic in")

    def test_downsamples_to_one_point_a_voxel(self):
        xyz, _intensity, _classes, _rgb, stats = self._build()
        keys = voxel_keys(xyz, 0.3)
        self.assertEqual(len(keys), len(set(keys.tolist())), "a voxel came back twice")
        self.assertEqual(stats["points"], len(xyz))

    def test_points_land_inside_their_voxel(self):
        xyz, _intensity, _classes, _rgb, _stats = self._build()
        wall = xyz[np.abs(xyz[:, 0] - 6.0) < 1.0]
        # The wall is a plane at x = 6; centroids must sit on it, not drift.
        self.assertLess(np.abs(wall[:, 0] - 6.0).max(), 0.3)

    def test_range_limit_drops_far_returns(self):
        _xyz, _i, _c, _rgb, stats = self._build(max_range_m=5.0)
        far = self._build(max_range_m=200.0)[4]
        self.assertLess(stats["points"], far["points"])

    def test_stats_describe_the_settings(self):
        _xyz, _i, _c, _rgb, stats = self._build(voxel=0.25, min_run=4)
        self.assertEqual(0.25, stats["voxel_m"])
        self.assertEqual(4, stats["min_run"])


class ChunkTest(unittest.TestCase):
    def test_splits_by_tile_and_keeps_every_point(self):
        rng = np.random.default_rng(1)
        xyz = rng.uniform(-300, 300, size=(5000, 3))
        intensity = rng.random(5000).astype(np.float32)
        chunks = chunk_by_tile(xyz, intensity, tile_m=128.0)
        self.assertGreater(len(chunks), 1)
        self.assertEqual(5000, sum(c["count"] for c in chunks))
        for chunk in chunks:
            spread = np.linalg.norm(chunk["xyz"] - np.array(chunk["centre"]), axis=1).max()
            self.assertLessEqual(spread, chunk["radius"] + 1e-6)

    def test_caps_the_size_of_one_chunk(self):
        xyz = np.zeros((1000, 3))
        chunks = chunk_by_tile(xyz, np.zeros(1000, np.float32), max_points=300)
        self.assertEqual(4, len(chunks))
        self.assertEqual(1000, sum(c["count"] for c in chunks))

    def test_empty(self):
        self.assertEqual([], chunk_by_tile(np.zeros((0, 3)), np.zeros(0, np.float32)))


if __name__ == "__main__":
    unittest.main()


class AggregateCacheTest(unittest.TestCase):
    """Building takes minutes, so the result has to survive a restart."""

    def setUp(self):
        import tempfile
        from ll2_scene.cache import FrameDiskCache
        self.tmp = tempfile.TemporaryDirectory()
        self.disk = FrameDiskCache("stub", self.tmp.name, log=lambda _m: None)
        self.agg = SceneAggregator(MovingObjectSource(), disk_cache=self.disk, voxel=0.3,
                                   max_range_m=200.0, ego_box=(0.1, 0.1, 0.1),
                                   log=lambda _m: None)

    def tearDown(self):
        self.tmp.cleanup()

    def test_round_trip(self):
        xyz, intensity, classes, colour, stats = self.agg.build()
        self.assertIsNone(self.agg.load_cached(), "nothing should be cached yet")
        self.agg.save_cached(xyz, intensity, classes, colour, stats)
        self.assertTrue(self.agg.cache_path().is_file(), "no file was written")
        loaded = self.agg.load_cached()
        self.assertIsNotNone(loaded, "the cache could not be read back")
        back_xyz, back_i, _back_c, _back_rgb, back_stats = loaded
        np.testing.assert_allclose(back_xyz, xyz, atol=1e-3)
        np.testing.assert_allclose(back_i, intensity, atol=1e-6)
        self.assertEqual(stats["points"], int(back_stats["points"]))
        self.assertEqual(stats["min_run"], int(back_stats["min_run"]))

    def test_different_settings_use_different_files(self):
        other = SceneAggregator(MovingObjectSource(), disk_cache=self.disk, voxel=0.5,
                                max_range_m=200.0, log=lambda _m: None)
        self.assertNotEqual(self.agg.cache_path(), other.cache_path())

    def test_no_cache_directory(self):
        agg = SceneAggregator(MovingObjectSource(), disk_cache=None, log=lambda _m: None)
        self.assertIsNone(agg.cache_path())
        self.assertIsNone(agg.load_cached())
        agg.save_cached(np.zeros((1, 3)), np.zeros(1, np.float32), None, None, {"points": 1})  # no raise

    def test_a_corrupt_cache_is_ignored(self):
        xyz, intensity, classes, colour, stats = self.agg.build()
        self.agg.save_cached(xyz, intensity, classes, colour, stats)
        self.agg.cache_path().write_bytes(b"not an npz")
        self.assertIsNone(self.agg.load_cached())


class AggregateClassTest(unittest.TestCase):
    """Each voxel keeps the class most of its points were given."""

    def setUp(self):
        self.source = MovingObjectSource()
        # Two patches of wall, labelled differently, plus one voxel that gets
        # a minority vote for something else.
        self.source.wall = np.stack([
            np.full(200, 6.0), np.linspace(0.0, 20.0, 200), np.zeros(200),
        ], axis=1)

    def _aggregate(self, classes_for):
        self.source.frame_classes = classes_for
        agg = SceneAggregator(self.source, disk_cache=None, voxel=0.5, max_range_m=200.0,
                              min_run=3, ego_box=(0.1, 0.1, 0.1), log=lambda _m: None)
        return agg.build()

    def test_the_majority_class_wins(self):
        from ll2_scene.mvd import UNLABELLED

        def classes_for(index, xyz):
            # Everything is a Pole except in one frame, where it is a Car.
            value = 58 if index == 0 else 48
            return np.full(len(xyz), value, np.uint8)

        xyz, _i, classes, _rgb, _stats = self._aggregate(classes_for)
        self.assertIsNotNone(classes)
        self.assertEqual(len(xyz), len(classes))
        self.assertTrue((classes == 48).all(),
                        "the one-frame class outvoted the one in every other frame")
        self.assertNotIn(UNLABELLED, set(classes.tolist()))

    def test_unlabelled_where_nothing_was_seen(self):
        from ll2_scene.mvd import UNLABELLED
        _xyz, _i, classes, _rgb, _stats = self._aggregate(
            lambda index, xyz: np.full(len(xyz), UNLABELLED, np.uint8))
        self.assertTrue((classes == UNLABELLED).all())

    def test_a_source_without_classes_still_aggregates(self):
        source = MovingObjectSource()
        agg = SceneAggregator(source, disk_cache=None, voxel=0.5, max_range_m=200.0,
                              min_run=3, ego_box=(0.1, 0.1, 0.1), log=lambda _m: None)
        xyz, _i, classes, _rgb, _stats = agg.build()
        self.assertGreater(len(xyz), 0)
        from ll2_scene.mvd import UNLABELLED
        self.assertTrue(classes is None or (classes == UNLABELLED).all())


class MergeSortedTest(unittest.TestCase):
    """The merge that replaced a full sort per frame."""

    def test_matches_unique_of_the_concatenation(self):
        rng = np.random.default_rng(5)
        for _ in range(20):
            keys = np.unique(rng.integers(0, 500, 60).astype(np.int64))
            fresh = np.unique(rng.integers(0, 500, 20).astype(np.int64))
            fresh = fresh[~np.isin(fresh, keys)]
            merged, keep_old, at_new = merge_sorted(keys, fresh)
            np.testing.assert_array_equal(merged, np.unique(np.concatenate([keys, fresh])))
            np.testing.assert_array_equal(merged[keep_old], keys)
            np.testing.assert_array_equal(merged[at_new], fresh)

    def test_carries_per_voxel_state_across(self):
        keys = np.array([10, 20, 30], np.int64)
        state = np.array([1, 2, 3], np.int32)
        merged, keep_old, at_new = merge_sorted(keys, np.array([15, 40], np.int64))
        moved = np.zeros(len(merged), np.int32)
        moved[keep_old] = state
        self.assertEqual([10, 15, 20, 30, 40], merged.tolist())
        self.assertEqual([1, 0, 2, 3, 0], moved.tolist())

    def test_empty_sides(self):
        keys = np.array([1, 2], np.int64)
        merged, keep_old, at_new = merge_sorted(keys, np.zeros(0, np.int64))
        np.testing.assert_array_equal(merged, keys)
        self.assertEqual(0, len(at_new))
        merged, keep_old, at_new = merge_sorted(np.zeros(0, np.int64), keys)
        np.testing.assert_array_equal(merged, keys)


class ParallelTest(unittest.TestCase):
    """Splitting the work must not change the answer."""

    def setUp(self):
        self.source = MovingObjectSource()

    def _agg(self, workers=1):
        return SceneAggregator(self.source, disk_cache=None, voxel=0.3, max_range_m=200.0,
                               min_run=3, ego_box=(0.1, 0.1, 0.1), workers=workers,
                               log=lambda _m: None)

    def test_a_stub_source_stays_in_process(self):
        # Workers rebuild the source from a directory; a stub has none, so
        # asking for them must fall back rather than fail at pickling time.
        self.assertIsNone(self._agg(workers=4)._worker_setup())

    def test_blocks_of_frames_add_up_to_one_pass(self):
        agg = self._agg()
        keys, longest = agg._runs_per_voxel()
        kept = keys[longest >= agg.min_run]
        frames = list(agg.frames())
        whole = agg._combine([agg._accumulate_block(kept, frames)], len(kept))
        halves = agg._combine([agg._accumulate_block(kept, frames[:6]),
                               agg._accumulate_block(kept, frames[6:])], len(kept))
        np.testing.assert_allclose(whole[0], halves[0], atol=1e-9)
        np.testing.assert_allclose(whole[1], halves[1], atol=1e-6)
        np.testing.assert_array_equal(whole[2], halves[2])

    def test_class_votes_survive_being_split(self):
        def classes_for(index, xyz):
            # Pole in most frames, Car in the first two: whichever block they
            # land in, the majority has to come out the same.
            return np.full(len(xyz), 58 if index < 2 else 48, np.uint8)

        self.source.frame_classes = classes_for
        agg = self._agg()
        keys, longest = agg._runs_per_voxel()
        kept = keys[longest >= agg.min_run]
        frames = list(agg.frames())
        whole = agg._combine([agg._accumulate_block(kept, frames)], len(kept))
        split = agg._combine([agg._accumulate_block(kept, frames[:3]),
                              agg._accumulate_block(kept, frames[3:8]),
                              agg._accumulate_block(kept, frames[8:])], len(kept))
        np.testing.assert_array_equal(whole[2], split[2])
        self.assertTrue((whole[2] == 48).all())
