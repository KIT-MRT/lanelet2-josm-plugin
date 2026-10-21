"""seamseg masks: RLE decoding, class images, and the Vistas table."""

import json
import tempfile
import unittest
from pathlib import Path

import numpy as np

from ll2_scene import mvd
from ll2_scene.masks import decode_rle, label_counts, label_image


def encode_rle(mask):
    """COCO compressed RLE, so the decoder can be round-tripped.

    Inverse of `decode_rle`: runs down the columns, deltas after the second,
    five bits a character on top of 48.
    """
    flat = np.asarray(mask, np.uint8).reshape(-1, order="F")
    runs = []
    value = 0
    run = 0
    for pixel in flat:
        if pixel == value:
            run += 1
        else:
            runs.append(run)
            value = pixel
            run = 1
    runs.append(run)
    out = []
    for index, count in enumerate(runs):
        value = count - runs[index - 2] if index > 2 else count
        more = True
        while more:
            char = value & 0x1F
            value >>= 5
            more = not ((value == 0 and not char & 0x10) or (value == -1 and char & 0x10))
            if more:
                char |= 0x20
            out.append(chr(char + 48))
    return "".join(out)


class RleTest(unittest.TestCase):
    def _round_trip(self, mask):
        decoded = decode_rle(encode_rle(mask), *mask.shape)
        np.testing.assert_array_equal(mask.astype(bool), decoded)

    def test_simple_block(self):
        mask = np.zeros((20, 30), np.uint8)
        mask[5:12, 8:21] = 1
        self._round_trip(mask)

    def test_empty_and_full(self):
        self._round_trip(np.zeros((9, 7), np.uint8))
        self._round_trip(np.ones((9, 7), np.uint8))

    def test_scattered(self):
        rng = np.random.default_rng(4)
        self._round_trip((rng.random((40, 25)) > 0.7).astype(np.uint8))

    def test_runs_go_down_columns(self):
        """Row-major decoding would transpose every mask; the bboxes caught it."""
        mask = np.zeros((6, 4), np.uint8)
        mask[0:2, 0] = 1  # a vertical pair in the first column
        decoded = decode_rle(encode_rle(mask), 6, 4)
        self.assertTrue(decoded[0, 0] and decoded[1, 0])
        self.assertFalse(decoded[0, 1])

    def test_a_broken_string_does_not_hang(self):
        with self.assertRaises((ValueError, IndexError)):
            decode_rle("!!!!!!!!", 4, 4)


class LabelImageTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()

    def tearDown(self):
        self.tmp.cleanup()

    def _write(self, instances, size=(20, 30)):
        path = Path(self.tmp.name) / "f.json"
        path.write_text(json.dumps({
            "image_info": {"original_size": list(size)},
            "instances": instances,
        }))
        return path

    def _instance(self, train_id, objectness, box):
        mask = np.zeros((20, 30), np.uint8)
        mask[box[0]:box[1], box[2]:box[3]] = 1
        return {"class": train_id, "objectness": objectness, "mask_rle": encode_rle(mask)}

    def test_unlabelled_where_nothing_was_detected(self):
        labels = label_image(self._write([]))
        self.assertEqual((20, 30), labels.shape)
        self.assertTrue((labels == mvd.UNLABELLED).all())

    def test_paints_each_class(self):
        labels = label_image(self._write([
            self._instance(58, 0.9, (2, 6, 3, 9)),     # Car
            self._instance(48, 0.8, (10, 14, 20, 26)),  # Pole
        ]))
        self.assertEqual(58, labels[3, 5])
        self.assertEqual(48, labels[12, 22])
        self.assertEqual(mvd.UNLABELLED, labels[0, 0])
        counts = label_counts(labels)
        self.assertEqual(4 * 6, counts[58])
        self.assertEqual(4 * 6, counts[48])

    def test_the_more_confident_instance_wins_an_overlap(self):
        labels = label_image(self._write([
            self._instance(58, 0.4, (2, 10, 2, 10)),   # Car, unsure
            self._instance(31, 0.95, (4, 8, 4, 8)),    # Person, confident
        ]))
        self.assertEqual(31, labels[5, 5], "the unsure instance painted over the sure one")
        self.assertEqual(58, labels[3, 3])

    def test_a_bad_instance_is_skipped_not_fatal(self):
        good = self._instance(58, 0.9, (2, 6, 3, 9))
        labels = label_image(self._write([
            {"class": 58, "objectness": 0.5, "mask_rle": "!!!!"},
            {"class": 999, "objectness": 0.9, "mask_rle": good["mask_rle"]},
            {"objectness": 0.9},
            good,
        ]))
        self.assertEqual(58, labels[3, 5])


class VistasTableTest(unittest.TestCase):
    def test_sixty_five_classes_stuff_then_things(self):
        self.assertEqual(65, mvd.NUM_CLASSES)
        self.assertEqual(37, len(mvd.THINGS))
        self.assertEqual(list(range(65)), [c[0] for c in mvd.CLASSES])
        self.assertTrue(all(t >= 28 for t in mvd.THINGS),
                        "things must follow the 28 stuff classes, as seamseg orders them")

    def test_the_official_colours(self):
        # Spot checks against the Vistas palette: these are the ones a user
        # will recognise in the viewer.
        self.assertEqual((0, 0, 142), mvd.COLORS[58])    # Car
        self.assertEqual((220, 20, 60), mvd.COLORS[31])  # Person
        self.assertEqual((119, 11, 32), mvd.COLORS[55])  # Bicycle
        self.assertEqual((0, 60, 100), mvd.COLORS[57])   # Bus
        self.assertEqual((153, 153, 153), mvd.COLORS[48])  # Pole
        self.assertEqual("Car", mvd.NAMES[58])
        self.assertEqual("Bicycle", mvd.NAMES[55])

    def test_palette_is_a_full_byte_table(self):
        palette = mvd.palette()
        self.assertEqual(256, len(palette))
        self.assertEqual([0, 0, 142], palette[58])
        self.assertEqual(list(mvd.UNLABELLED_COLOR), palette[mvd.UNLABELLED])
        self.assertEqual(list(mvd.UNLABELLED_COLOR), palette[200], "unused ids need a colour too")

    def test_describe(self):
        self.assertEqual("Car", mvd.describe(58))
        self.assertEqual("unlabelled", mvd.describe(mvd.UNLABELLED))


if __name__ == "__main__":
    unittest.main()
