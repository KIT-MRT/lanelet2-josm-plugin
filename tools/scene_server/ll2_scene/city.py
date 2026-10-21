"""Every scene of one city: where they are, without opening their sensors.

KITScenes keeps no city column anywhere (`data/sequence_archives.csv` has
id, split, archive and size). What it does keep is `maps/origin.json` in
every scene, which is the city's projector origin, and the scene's own
trajectory. So a scene belongs to whichever of the dataset's three cities its
first pose lies nearest to. Each scene is placed through its *own* origin, so
scenes would still line up if two origins in one city ever differed.

Only `poses.txt`, `origin.json` and `timestamp.reference.txt` are read here:
opening a scene's sensor loader costs far more than this whole index, and a
city has hundreds of scenes.
"""

import math
import re
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np

from .enu import SceneFrame
from .scene import read_origin, read_poses, read_reference_timestamps, track_enu

# The three cities the dataset names, with a point near each centre.
KNOWN_CITIES = {
    "karlsruhe": (49.0094, 8.4044),
    "frankfurt": (50.1109, 8.6821),
    "sindelfingen": (48.7133, 9.0028),
}
CITY_RADIUS_KM = 30.0
# Scene ids end up in URLs; anything else in the data directory is ignored.
SCENE_ID_RE = re.compile(r"^[A-Za-z0-9_-]{1,80}$")


def haversine_km(a, b):
    lat1, lon1 = map(math.radians, a)
    lat2, lon2 = map(math.radians, b)
    h = (math.sin((lat2 - lat1) / 2) ** 2
         + math.cos(lat1) * math.cos(lat2) * math.sin((lon2 - lon1) / 2) ** 2)
    return 2 * 6371.0 * math.asin(min(1.0, math.sqrt(h)))


def city_of(lat, lon):
    """Name of the known city within `CITY_RADIUS_KM`, else None."""
    best = min(KNOWN_CITIES, key=lambda c: haversine_km((lat, lon), KNOWN_CITIES[c]))
    return best if haversine_km((lat, lon), KNOWN_CITIES[best]) <= CITY_RADIUS_KM else None


def _data_root(root):
    root = Path(root).expanduser().resolve()
    return root / "data" if (root / "data").is_dir() else root


def find_scenes(root, max_depth=3):
    """Scene directories under `root`: ([(split, dir)], [(split, tar)]).

    `root` may be `KITSCENES_ROOT`, its `data/`, one split, or one scene. A
    scene is any directory holding `poses.txt`; its split is the parent's
    name. Archives with no extracted twin are returned separately, because
    nothing can be served from them and the user should hear about it.
    """
    root = _data_root(root)
    scenes, tars = [], []
    if (root / "poses.txt").is_file():
        # One scene: its id still ends up in URLs.
        return ([(root.parent.name, root)] if SCENE_ID_RE.match(root.name) else []), []

    def walk(directory, depth):
        try:
            children = sorted(directory.iterdir())
        except OSError:
            return
        for child in children:
            if child.is_dir():
                if (child / "poses.txt").is_file():
                    if SCENE_ID_RE.match(child.name):
                        scenes.append((directory.name, child))
                elif depth < max_depth:
                    walk(child, depth + 1)
            elif child.suffix == ".tar" and SCENE_ID_RE.match(child.stem):
                if not (directory / child.stem / "poses.txt").is_file():
                    tars.append((directory.name, child))
    walk(root, 1)
    return scenes, tars


@dataclass
class CityScene:
    id: str
    split: str
    dir: Path
    origin: tuple
    poses: dict = field(repr=False)
    timestamps: np.ndarray = field(repr=False)
    frame_count: int = 0
    start: tuple = (0.0, 0.0)     # lat, lon of the first pose
    city: str = None

    @classmethod
    def read(cls, split, scene_dir):
        origin = read_origin(scene_dir)
        poses = read_poses(scene_dir)
        timestamps = read_reference_timestamps(scene_dir, poses)
        frames = int(min(len(poses["time"]), len(timestamps)))
        # What would otherwise only fail later, while describing the whole
        # city -- and take every other scene down with it.
        if frames < 1:
            raise ValueError("no frames")
        if len(poses["time"]) > 1 and not np.all(np.diff(poses["time"]) > 0):
            raise ValueError("pose times are not strictly increasing")
        if not np.all(np.isfinite(poses["t"])) or not np.all(np.isfinite(timestamps[:frames])):
            raise ValueError("non-finite poses or timestamps")
        frame = SceneFrame(origin[0], origin[1], origin[0], origin[1])
        start = frame.to_lat_lon(poses["t"][0, 0], poses["t"][0, 1])
        return cls(id=scene_dir.name, split=split, dir=Path(scene_dir), origin=origin,
                   poses=poses, timestamps=timestamps[:frames], frame_count=frames,
                   start=(float(start[0]), float(start[1])), city=city_of(*start))

    def track(self, anchor, frames=None):
        """(positions (N,3), xyzw (N,4)) in viewer ENU about `anchor`."""
        frame = (frames or {}).get(self.origin)
        if frame is None:
            frame = SceneFrame(self.origin[0], self.origin[1], *anchor)
            if frames is not None:
                frames[self.origin] = frame
        return track_enu(self.poses, self.timestamps, frame)


class CityIndex:
    """The scenes of one city and their trajectories."""

    def __init__(self, root, city=None, log=print):
        self.root = _data_root(root)
        self.log = log
        found, self.unextracted = find_scenes(self.root)
        everything, self.unreadable = [], []
        for split, scene_dir in found:
            try:
                everything.append(CityScene.read(split, scene_dir))
            except (OSError, ValueError, KeyError) as exc:
                self.unreadable.append((scene_dir, repr(exc)))
        self.cities = {}
        for scene in everything:
            self.cities.setdefault(scene.city or "elsewhere", []).append(scene)
        if city:
            city = city.lower()
            if city not in self.cities:
                raise ValueError("no scenes in %r here; found %s" % (city, self.counts()))
        else:
            # Most scenes wins; the others are named in the log, not dropped
            # silently.
            named = {k: v for k, v in self.cities.items() if k != "elsewhere"}
            pool = named or self.cities
            if not pool:
                raise ValueError("no scenes under %s" % self.root)
            city = max(pool, key=lambda k: len(pool[k]))
        self.city = city
        self.scenes = sorted(self.cities[city], key=lambda s: (s.split, s.id))
        self.by_id = {s.id: s for s in self.scenes}

    def counts(self):
        return {k: len(v) for k, v in sorted(self.cities.items())}

    def describe_log(self):
        splits = {}
        for s in self.scenes:
            splits[s.split] = splits.get(s.split, 0) + 1
        parts = ["%s: %d scenes (%s)" % (self.city, len(self.scenes),
                                          ", ".join("%s %d" % kv for kv in sorted(splits.items())))]
        others = {k: v for k, v in self.counts().items() if k != self.city}
        if others:
            parts.append("other cities here: %s" % ", ".join("%s %d" % kv for kv in others.items()))
        if self.unextracted:
            parts.append("%d archives not extracted (skipped)" % len(self.unextracted))
        if self.unreadable:
            parts.append("%d scenes unreadable (skipped)" % len(self.unreadable))
        return "; ".join(parts)

    def default_anchor(self):
        """The city's projector origin: shared by its scenes, so it is stable."""
        origins = {}
        for s in self.scenes:
            origins[s.origin] = origins.get(s.origin, 0) + 1
        return max(origins, key=origins.get)

    def describe(self, anchor, base_url, aggregate_ready=None):
        """`/city.json`: every scene's trajectory in viewer ENU.

        Positions to the centimetre and quaternions to four decimals, which is
        what the source carries; at ~7 numbers a frame a city of a few
        hundred scenes is a few megabytes, sent once and gzipped.
        """
        frames = {}
        scenes = []
        for s in self.scenes:
            try:
                pos, quat = s.track(anchor, frames)
            except Exception as exc:  # one bad scene must not hide the city
                self.log("city: leaving out %s (%r)" % (s.id, exc))
                continue
            lo = pos.min(axis=0)
            hi = pos.max(axis=0)
            scenes.append({
                "id": s.id,
                "split": s.split,
                "frames": s.frame_count,
                "base": "%s/scene/%s" % (base_url, s.id),
                "pos": [round(float(v), 2) for v in pos.reshape(-1)],
                "quat": [round(float(v), 4) for v in quat.reshape(-1)],
                "bbox": [round(float(v), 1) for v in (lo[0], lo[1], hi[0], hi[1])],
                "aggregate": bool(aggregate_ready(s)) if aggregate_ready else False,
            })
        from . import mvd
        return {
            "city": self.city,
            # The Vistas table, so city clouds are coloured and named by class
            # before any one scene has been opened.
            "classes": {
                "palette": mvd.palette(),
                "names": {str(k): v for k, v in mvd.NAMES.items()},
                "unlabelled": mvd.UNLABELLED,
            },
            "anchor": {"lat": anchor[0], "lon": anchor[1]},
            "base": base_url,
            "scenes": scenes,
            "unextracted": len(self.unextracted),
        }
