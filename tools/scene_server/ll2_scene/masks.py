"""seamseg instance masks -> a per-pixel class image.

`processed/seamseg_instances/<camera>/<frame>.json` holds one entry per
detected object: a Vistas trainId (see `mvd.py`), an objectness score, a
bounding box and a COCO compressed-RLE mask. The RLE carries no size of its
own, and the masks are at `image_info.original_size` (H, W) -- verified
against the stored bounding boxes, which the decoded masks sit exactly
inside.

Only the two cameras the dataset ships masks for (front and rear) can label
anything, so a point seen only by the side cameras stays unlabelled rather
than being guessed at.
"""

import json

import numpy as np

from .mvd import UNLABELLED


def decode_rle(counts, height, width):
    """COCO compressed RLE -> (height, width) bool mask.

    The counts string is LEB128-ish base-48: five bits per character, bit 5
    continues the number, bit 4 of the last character sign-extends it, and
    every count after the second is a delta against the one before last.
    """
    runs = []
    position = 0
    length = len(counts)
    while position < length:
        value = 0
        shift = 0
        more = True
        while more:
            char = ord(counts[position]) - 48
            value |= (char & 0x1F) << (5 * shift)
            more = bool(char & 0x20)
            position += 1
            shift += 1
            if not more and (char & 0x10):
                value |= -1 << (5 * shift)
        if len(runs) > 2:
            value += runs[-2]
        runs.append(value)

    flat = np.zeros(height * width, np.uint8)
    at = 0
    value = 0
    for run in runs:
        if run < 0:
            raise ValueError("negative run in RLE")
        end = min(at + run, flat.size)
        if value:
            flat[at:end] = 1
        at = end
        value ^= 1
        if at >= flat.size:
            break
    # COCO runs go down columns, not across rows.
    return flat.reshape((height, width), order="F").astype(bool)


def label_image(path):
    """Build an (H, W) uint8 class image from one seamseg JSON.

    Instances are painted least-confident first, so where two overlap the
    more confident one wins. Pixels no instance covers stay `UNLABELLED`.
    """
    with open(path) as handle:
        data = json.load(handle)
    size = data.get("image_info", {}).get("original_size")
    if not size or len(size) != 2:
        raise ValueError("no original_size in %s" % path)
    height, width = int(size[0]), int(size[1])
    labels = np.full((height, width), UNLABELLED, np.uint8)
    instances = sorted(data.get("instances", []), key=lambda i: i.get("objectness", 0.0))
    for inst in instances:
        rle = inst.get("mask_rle")
        if not rle:
            continue
        train_id = int(inst.get("class", UNLABELLED))
        if not 0 <= train_id < UNLABELLED:
            continue
        try:
            mask = decode_rle(rle, height, width)
        except (ValueError, IndexError):
            continue
        labels[mask] = train_id
    return labels


def label_counts(labels):
    """{class id: pixels}, for logging and tests."""
    values, counts = np.unique(labels, return_counts=True)
    return {int(v): int(c) for v, c in zip(values, counts)}
