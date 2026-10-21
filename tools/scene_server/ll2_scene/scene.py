"""KITScenes scene -> what the viewer needs: map, poses, point clouds, images.

Everything public here is already in the viewer's ENU frame (see `enu.py`), so
callers never juggle two coordinate systems. The dataset is read through the
official `kitscenes` package: it owns the parquet layout, the sentinel filter
and the deskew, and those are not worth re-deriving.
"""

import json
import math
import os
import sys
from pathlib import Path

import numpy as np

from .enu import SceneFrame
from .mapfeed import MapConverter

RING_CAMERAS = ("camera_ring_front", "camera_ring_front_left", "camera_ring_front_right",
                "camera_ring_rear_right", "camera_ring_rear", "camera_ring_rear_left")
# The only cameras the dataset ships seamseg masks for.
SEGMENTED_CAMERAS = ("camera_ring_front", "camera_ring_rear")


def add_kitscenes_to_path(explicit=None):
    """Import path for the `kitscenes` package.

    Its published metadata points at a lanelet2 wheel by relative path, which
    installers refuse to parse, so a checkout on `sys.path` is the reliable
    way in. An installed copy is used when there is one.
    """
    candidates = [explicit, os.environ.get("KITSCENES_API_PATH"),
                  Path(__file__).resolve().parents[4] / "kitscenes"]
    for cand in candidates:
        if not cand:
            continue
        path = Path(cand)
        if (path / "kitscenes" / "__init__.py").is_file():
            if str(path) not in sys.path:
                sys.path.insert(0, str(path))
            return path
    return None


class SceneSource:
    """One recorded scene, in the viewer's frame."""

    def __init__(self, scene_dir, kitscenes_path=None, anchor=None):
        self.dir = Path(scene_dir).resolve()
        if not self.dir.is_dir():
            raise FileNotFoundError("scene directory not found: %s" % self.dir)
        add_kitscenes_to_path(kitscenes_path)
        from kitscenes.sensors import SensorDataLoader  # noqa: E402 (needs the path above)

        self.loader = SensorDataLoader(self.dir)
        self.scene_id = self.dir.name
        self.origin_lat, self.origin_lon = self._read_origin()
        self._projector = None
        self._lanelet_map = None
        self.poses = self._read_poses()
        self.timestamps = self._read_reference_timestamps()
        self.frame_count = min(len(self.poses["time"]), len(self.timestamps))
        self.lidars = list(self.loader.get_lidar_names())
        self.cameras = [c for c in RING_CAMERAS if (self.dir / c).is_dir()]
        self.set_anchor(anchor or self._default_anchor())
        self._pose_rot = None

    def set_anchor(self, anchor):
        """Point the ENU frame at a new anchor (JOSM's, when it owns the map)."""
        self.anchor = (float(anchor[0]), float(anchor[1]))
        self.frame = SceneFrame(self.origin_lat, self.origin_lon, *self.anchor)

    # -- map ---------------------------------------------------------------
    def _read_origin(self):
        path = self.dir / "maps" / "origin.json"
        if not path.is_file():
            raise FileNotFoundError("no maps/origin.json in %s" % self.dir)
        data = json.loads(path.read_text())
        return float(data["latitude"]), float(data["longitude"])

    @property
    def projector(self):
        if self._projector is None:
            from lanelet2.io import Origin
            from lanelet2.projection import UtmProjector
            self._projector = UtmProjector(Origin(self.origin_lat, self.origin_lon))
        return self._projector

    @property
    def lanelet_map(self):
        if self._lanelet_map is None:
            import lanelet2
            self._lanelet_map = lanelet2.io.load(str(self.dir / "maps" / "map.osm"), self.projector)
        return self._lanelet_map

    def _reverse(self, x, y):
        from lanelet2.core import BasicPoint3d
        gps = self.projector.reverse(BasicPoint3d(float(x), float(y), 0.0))
        return gps.lat, gps.lon

    def _default_anchor(self):
        """Centre of the map's lat/lon bbox, which is what JOSM would pick."""
        import numpy as np
        from .enu import utm_epsg
        import pyproj
        xy = np.array([[p.x, p.y] for p in self.lanelet_map.pointLayer], dtype=np.float64)
        if not len(xy):
            return self.origin_lat, self.origin_lon
        epsg = utm_epsg(self.origin_lat, self.origin_lon)
        fwd = pyproj.Transformer.from_crs("EPSG:4326", epsg, always_xy=True)
        inv = pyproj.Transformer.from_crs(epsg, "EPSG:4326", always_xy=True)
        e0, n0 = fwd.transform(self.origin_lon, self.origin_lat)
        lon, lat = inv.transform(xy[:, 0] + e0, xy[:, 1] + n0)
        return (float(np.min(lat)) + float(np.max(lat))) / 2, (float(np.min(lon)) + float(np.max(lon))) / 2

    def _scene_centre(self):
        """Where the local similarity is fitted: the middle of the driven path."""
        if len(self.poses["t"]):
            return float(np.mean(self.poses["t"][:, 0])), float(np.mean(self.poses["t"][:, 1]))
        return 0.0, 0.0

    def map_features(self):
        return MapConverter(self.frame).features(self.lanelet_map)

    # -- poses -------------------------------------------------------------
    def _read_poses(self):
        """TUM `poses.txt` -> {t: (N,3), q: (N,4) xyzw, time: (N,)}.

        The quaternions carry only four decimals, so they are not unit;
        everything downstream normalises.
        """
        rows = []
        for line in (self.dir / "poses.txt").read_text().splitlines():
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split()
            if len(parts) >= 8:
                rows.append([float(v) for v in parts[:8]])
        if not rows:
            raise ValueError("no poses in %s/poses.txt" % self.dir)
        arr = np.array(rows, dtype=np.float64)
        q = arr[:, 4:8]
        q = q / np.linalg.norm(q, axis=1, keepdims=True)
        return {"time": arr[:, 0], "t": arr[:, 1:4], "q": q}

    def _read_reference_timestamps(self):
        path = self.dir / "timestamp.reference.txt"
        if not path.is_file():
            return self.poses["time"].copy()
        vals = [float(v) for v in path.read_text().split() if v.strip()]
        arr = np.array(vals, dtype=np.float64)
        # Some released scenes store nanoseconds here while poses.txt is in
        # seconds; the dataset's own loader uses the same 1e12 heuristic.
        if arr.size and arr.max() > 1e12:
            arr = arr / 1e9
        return arr

    @property
    def pose_rotations(self):
        if self._pose_rot is None:
            from scipy.spatial.transform import Rotation
            self._pose_rot = Rotation.from_quat(self.poses["q"])
        return self._pose_rot

    def pose_at(self, time_s):
        """Ego pose interpolated at `time_s`: (R 3x3, t (3,)) in scene metres."""
        from scipy.spatial.transform import Rotation, Slerp
        times = self.poses["time"]
        t = float(np.clip(time_s, times[0], times[-1]))
        if not hasattr(self, "_slerp"):
            self._slerp = Slerp(times, self.pose_rotations)
        rot = self._slerp(t)
        pos = np.array([np.interp(t, times, self.poses["t"][:, i]) for i in range(3)])
        return (rot if isinstance(rot, Rotation) else rot).as_matrix(), pos

    def ego_pose_enu(self, index):
        """Ego pose of frame `index` in viewer ENU: position + 3x3 rotation."""
        rot, pos = self.pose_at(float(self.timestamps[index]))
        x, y = self.frame.apply(pos[0], pos[1])
        # Turn the heading into ENU where the vehicle actually is: the
        # convergence is a property of the place, not of the scene.
        gamma = math.radians(self.frame.rotation_deg(pos[0], pos[1]))
        cg, sg = math.cos(gamma), math.sin(gamma)
        spin = np.array([[cg, -sg, 0.0], [sg, cg, 0.0], [0.0, 0.0, 1.0]])
        return np.array([x, y, pos[2]]), spin @ rot

    # -- point clouds ------------------------------------------------------
    def frame_points(self, index, lidars=None):
        """All LiDARs of one frame, in viewer ENU."""
        xyz, intensity, sensor = self.frame_points_scene(index, lidars)
        return self.to_enu(xyz), intensity, sensor

    def to_enu(self, xyz):
        """(N,3) scene metres -> viewer ENU. ~0.12 s for a 750 k-point frame."""
        if not len(xyz):
            return xyz
        xy = self.frame.apply_array(xyz[:, :2])
        return np.column_stack([xy[:, 0], xy[:, 1], xyz[:, 2]])

    def frame_points_scene(self, index, lidars=None):
        """All LiDARs of one frame, in the scene's own metric frame.

        Returns (xyz float64 (N,3), intensity float32 (N,), sensor uint8 (N,)).
        `kitscenes` deskews each sweep into the base frame with that sensor's
        extrinsic; the ego pose at the sweep time puts it in the scene frame.
        This is the expensive part and the part worth caching: it does not
        depend on the viewer's ENU anchor, which can change under us.
        """
        xyz_parts, refl_parts, sid_parts = [], [], []
        for name in (lidars or self.lidars):
            try:
                sweep = self.loader.get_lidar_sweep(name, int(index))
            except FileNotFoundError:
                continue
            pts = sweep.points
            if not len(pts):
                continue
            xyz = np.column_stack([pts["x"], pts["y"], pts["z"]]).astype(np.float64)
            if not sweep.deskewed:
                # No usable poses: the points are still sensor-local and the
                # extrinsic was never applied.
                mat = self.loader.get_lidar_extrinsic(name)
                xyz = xyz @ mat[:3, :3].T + mat[:3, 3]
            rot, pos = self.pose_at(sweep.timestamp_ns / 1e9)
            xyz_parts.append(xyz @ rot.T + pos)
            refl = pts["reflectivity"] if "reflectivity" in pts.dtype.names else np.zeros(len(pts), np.float32)
            refl_parts.append(np.asarray(refl, dtype=np.float32))
            sid = pts["sensor_id"] if "sensor_id" in pts.dtype.names else np.zeros(len(pts), np.uint8)
            sid_parts.append(np.asarray(sid, dtype=np.uint8))
        if not xyz_parts:
            return np.zeros((0, 3)), np.zeros(0, np.float32), np.zeros(0, np.uint8)
        return (np.concatenate(xyz_parts), np.concatenate(refl_parts),
                np.concatenate(sid_parts))

    def ego_track(self):
        """Every frame's ego pose in viewer ENU: positions and xyzw quaternions.

        Small enough (a few hundred frames) to travel with the scene
        description, which saves the browser a round trip per frame for the
        camera lock.
        """
        from scipy.spatial.transform import Rotation
        positions, quats = [], []
        for i in range(self.frame_count):
            pos, rot = self.ego_pose_enu(i)
            positions.append([round(float(v), 4) for v in pos])
            q = Rotation.from_matrix(rot).as_quat()  # xyzw, as three.js wants
            quats.append([round(float(v), 7) for v in q])
        return positions, quats

    # -- projecting into the cameras ---------------------------------------
    def camera_pose(self, camera, index):
        """(R, t) of a camera in the scene frame for one frame, or None.

        The camera frame is OpenCV: x right, y down, z forward.
        """
        try:
            calib = self.loader.get_camera_calibration(camera)
        except Exception:
            return None
        rot, pos = self.pose_at(float(self.timestamps[index]))
        ext = np.asarray(calib.extrinsic, dtype=np.float64)  # camera -> ego
        return rot @ ext[:3, :3], rot @ ext[:3, 3] + pos

    def project_into(self, camera, index, xyz):
        """Pixels of scene-frame points in one camera: (u, v, visible).

        Plain pinhole -- the ring images are rectified and the calibration
        carries no distortion coefficients at all.
        """
        pose = self.camera_pose(camera, index)
        info = self.camera_info().get(camera)
        if pose is None or not info or not info.get("width"):
            return None
        rot, pos = pose
        local = (np.asarray(xyz, dtype=np.float64) - pos) @ rot  # world -> camera
        depth = local[:, 2]
        in_front = depth > 0.1
        safe = np.where(in_front, depth, 1.0)
        u = info["fx"] * local[:, 0] / safe + info["cx"]
        v = info["fy"] * local[:, 1] / safe + info["cy"]
        visible = (in_front & (u >= 0) & (u < info["width"])
                   & (v >= 0) & (v < info["height"]))
        return u, v, visible

    def segmentation_path(self, camera, index):
        path = self.dir / "processed" / "seamseg_instances" / camera / ("%010d.json" % int(index))
        return path if path.is_file() else None

    def frame_classes(self, index, xyz, cameras=SEGMENTED_CAMERAS):
        """Vistas class per point, `UNLABELLED` where no mask covers it.

        Points are projected into each segmented camera and read out of that
        camera's class image. The front and rear cameras barely overlap, so
        first label wins and there is nothing to arbitrate.
        """
        from .masks import label_image
        from .mvd import UNLABELLED
        out = np.full(len(xyz), UNLABELLED, np.uint8)
        if not len(xyz):
            return out
        for camera in cameras:
            path = self.segmentation_path(camera, index)
            if path is None:
                continue
            projected = self.project_into(camera, index, xyz)
            if projected is None:
                continue
            u, v, visible = projected
            todo = visible & (out == UNLABELLED)
            if not todo.any():
                continue
            try:
                labels = label_image(path)
            except (OSError, ValueError):
                continue
            rows = np.clip(v[todo].astype(np.int32), 0, labels.shape[0] - 1)
            cols = np.clip(u[todo].astype(np.int32), 0, labels.shape[1] - 1)
            out[todo] = labels[rows, cols]
        return out

    def frame_rgb(self, index, xyz, cameras=None):
        """Camera colour per point; neutral grey where no camera saw it.

        All six ring cameras are used, not just the two with masks, so most
        of a frame gets a colour. Images are decoded at half resolution --
        a JPEG can do that straight from the DCT coefficients, which is about
        four times faster, and a point only needs one pixel.
        """
        from PIL import Image
        out = np.full((len(xyz), 3), 90, np.uint8)
        if not len(xyz):
            return out
        taken = np.zeros(len(xyz), bool)
        for camera in (cameras or self.cameras):
            path = self.image_path(camera, index)
            if not path or not path.is_file():
                continue
            projected = self.project_into(camera, index, xyz)
            if projected is None:
                continue
            u, v, visible = projected
            todo = visible & ~taken
            if not todo.any():
                continue
            try:
                with Image.open(path) as image:
                    image.draft("RGB", (image.width // 2, image.height // 2))
                    pixels = np.asarray(image.convert("RGB"))
            except OSError:
                continue
            # draft() only shrinks by powers of two, so take the real ratio.
            info = self.camera_info()[camera]
            scale_y = pixels.shape[0] / float(info["height"])
            scale_x = pixels.shape[1] / float(info["width"])
            rows = np.clip((v[todo] * scale_y).astype(np.int32), 0, pixels.shape[0] - 1)
            cols = np.clip((u[todo] * scale_x).astype(np.int32), 0, pixels.shape[1] - 1)
            out[todo] = pixels[rows, cols]
            taken |= todo
        return out

    # -- cameras -----------------------------------------------------------
    def camera_info(self):
        """Per ring camera: intrinsics, image size and the mount in the ego frame."""
        out = {}
        for name in self.cameras:
            try:
                calib = self.loader.get_camera_calibration(name)
            except Exception:
                continue
            size = calib.image_size or self._image_size(name)
            out[name] = {
                "fx": float(calib.intrinsic[0][0]),
                "fy": float(calib.intrinsic[1][1]),
                "cx": float(calib.intrinsic[0][2]),
                "cy": float(calib.intrinsic[1][2]),
                "width": int(size[0]) if size else None,
                "height": int(size[1]) if size else None,
                # Camera -> ego (base) frame, row-major 4x4. The camera frame
                # is OpenCV: x right, y down, z forward.
                "T_cam_to_ego": [[float(v) for v in row] for row in calib.extrinsic],
            }
        return out

    def _image_size(self, name):
        try:
            return self.loader.get_camera_image_size(name, 0)
        except Exception:
            return None

    def image_path(self, camera, index):
        try:
            return Path(self.loader.get_camera_image_path(camera, int(index)))
        except Exception:
            return None

    # -- description for the browser --------------------------------------
    def class_palette(self):
        """Class colours and names for the browser, or None without masks."""
        from . import mvd
        if not any(self.segmentation_path(c, 0) for c in SEGMENTED_CAMERAS):
            return None
        return {
            "palette": mvd.palette(),
            "names": {str(train_id): name for train_id, name in mvd.NAMES.items()},
            "unlabelled": mvd.UNLABELLED,
            "cameras": [c for c in SEGMENTED_CAMERAS if self.segmentation_path(c, 0)],
        }

    def describe(self, base_url):
        lat, lon = self.anchor
        positions, quats = self.ego_track()
        return {
            "type": "scene",
            "scene": {
                "id": self.scene_id,
                "base": base_url,
                "frames": int(self.frame_count),
                "times": [round(float(t), 4) for t in self.timestamps[:self.frame_count]],
                "lidars": self.lidars,
                "ego": {"pos": positions, "quat": quats},
                "cameras": self.camera_info(),
                "anchor": {"lat": lat, "lon": lon},
                # The Vistas palette travels with the scene, so the browser
                # has one source of truth for class colours and names.
                "classes": self.class_palette(),
                "enuRotationDeg": round(self.frame.rotation_deg(*self._scene_centre()), 6),
                "enuScale": [round(v, 9) for v in self.frame.scale(*self._scene_centre())],
            },
        }
