"""Map rules shared with the plugin: heights, one_way, arrows."""

import unittest

from ll2_scene.mapfeed import (fill_unknown_heights, is_two_way_for,
                               other_way_participants, parse_lanelet_bool, usable_ele)

FLT_MAX_SENTINEL = -340282349999999991754788743781432688640.0


class HeightTest(unittest.TestCase):
    def test_sentinel_is_not_a_height(self):
        # Real maps carry -FLT_MAX for "unknown"; the plugin's HeightTools
        # rejects the same values.
        self.assertIsNone(usable_ele(FLT_MAX_SENTINEL))
        self.assertIsNone(usable_ele(float("nan")))
        self.assertIsNone(usable_ele(float("inf")))
        self.assertIsNone(usable_ele(1e6))
        self.assertIsNone(usable_ele("abc"))
        self.assertIsNone(usable_ele(None))
        self.assertEqual(144.6, usable_ele(144.6))
        self.assertEqual(-12.5, usable_ele("-12.5"))

    def test_interpolated_by_distance_between_known_heights(self):
        xs = [0.0, 10.0, 40.0]
        ys = [0.0, 0.0, 0.0]
        self.assertEqual([100.0, 101.0, 104.0],
                         fill_unknown_heights([100.0, None, 104.0], xs, ys))

    def test_copied_at_the_ends(self):
        xs = [0.0, 10.0, 20.0, 30.0]
        ys = [0.0] * 4
        self.assertEqual([163.5, 163.5, 164.0, 164.0],
                         fill_unknown_heights([None, 163.5, 164.0, None], xs, ys))

    def test_a_line_without_any_height_sits_at_zero(self):
        self.assertEqual([0.0, 0.0], fill_unknown_heights([None, None], [0.0, 5.0], [0.0, 0.0]))

    def test_known_heights_are_untouched(self):
        zs = [1.0, 2.0, 3.0]
        self.assertEqual(zs, fill_unknown_heights(zs, [0.0, 1.0, 2.0], [0.0, 0.0, 0.0]))


class OneWayTest(unittest.TestCase):
    def test_lanelet2_bool_parsing(self):
        for value in ("1", "true", "yes"):
            self.assertIs(True, parse_lanelet_bool(value))
        for value in ("0", "false", "no"):
            self.assertIs(False, parse_lanelet_bool(value))
        self.assertIsNone(parse_lanelet_bool("maybe"))
        self.assertIsNone(parse_lanelet_bool(None))

    def test_one_way_decides_when_it_is_a_bool(self):
        self.assertFalse(is_two_way_for({"one_way": "yes"}))
        self.assertTrue(is_two_way_for({"one_way": "no"}))
        # an override cannot overrule an explicit one_way
        self.assertFalse(is_two_way_for({"one_way": "yes", "one_way:vehicle:car": "no"}))

    def test_overrides_apply_by_key_prefix(self):
        self.assertTrue(is_two_way_for({"one_way:vehicle": "no"}))
        self.assertTrue(is_two_way_for({"one_way:vehicle:car": "no"}))
        self.assertFalse(is_two_way_for({"one_way:vehicle:bus": "no"}))
        self.assertTrue(is_two_way_for({"one_way:bicycle": "no"}, "bicycle"))

    def test_without_any_tag_only_pedestrians_go_both_ways(self):
        self.assertFalse(is_two_way_for({}))
        self.assertTrue(is_two_way_for({}, "pedestrian"))

    def test_other_way_participants(self):
        self.assertEqual(["bicycle"], other_way_participants({"one_way:bicycle": "no"}))
        self.assertEqual([], other_way_participants({"one_way": "yes", "one_way:bicycle": "no"}))
        self.assertEqual([], other_way_participants({"one_way:vehicle": "no"}))


if __name__ == "__main__":
    unittest.main()
