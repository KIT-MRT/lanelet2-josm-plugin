"""Coordinates: the scene's metric frame -> the viewer's ENU.

The viewer places everything in the plugin's ENU (`Viewer3dEnu` on the Kotlin
side): an equirectangular local tangent plane about an anchor lat/lon, z = ele.

A KITScenes scene lives in lanelet2's `UtmProjector` frame (a city-local
transverse Mercator anchored at `maps/origin.json`), and so does its map, so
those two already agree. The ENU plane does **not** agree with it:

  * the scene frame is turned against ENU by the meridian convergence
    (0.244 deg for this scene -- 4.3 m per kilometre), and
  * the two differ in scale, anisotropically: `Viewer3dEnu` uses the equatorial
    radius for both axes, so at this latitude ENU east is 0.9984 and ENU north
    1.0012 scene metres. Both the map and the point clouds go through the same
    chain, so they stay consistent with each other; distances read off the
    viewer are what carry that 0.27 % anisotropy.

Running map geometry and point clouds through *different* chains would pull
them apart by metres, so everything uses `SceneFrame` below. A fitted local
affine was the obvious shortcut and is not good enough: the chain is not
affine, and the residual reaches 57 mm at 400 m from the fit point, which is
the same order as the alignment errors this viewer exists to find. The exact
chain is vectorised through pyproj instead, which matches lanelet2's own
projector to under a micrometre (there is a test for that).
"""

import math

EARTH_R = 6378137.0  # must match Viewer3dEnu.EARTH_R


def enu(lat, lon, lat0, lon0):
    """Viewer ENU metres of a lat/lon, about the anchor (lat0, lon0)."""
    x = math.radians(lon - lon0) * math.cos(math.radians(lat0)) * EARTH_R
    y = math.radians(lat - lat0) * EARTH_R
    return x, y


def enu_to_lat_lon(x, y, lat0, lon0):
    lat = lat0 + math.degrees(y / EARTH_R)
    cos0 = math.cos(math.radians(lat0))
    if abs(cos0) < 1e-12:
        cos0 = 1e-12
    return lat, lon0 + math.degrees(x / (EARTH_R * cos0))


def round_coord(value, decimals=3):
    """Round half away from zero, like the plugin's `Viewer3dEnu.roundCoord`."""
    factor = 10.0 ** decimals
    scaled = value * factor
    return math.floor(scaled + 0.5) / factor if scaled >= 0 else math.ceil(scaled - 0.5) / factor


def utm_epsg(lat, lon):
    """EPSG code of the UTM zone lanelet2's `UtmProjector` picks for an origin."""
    zone = int(math.floor((lon + 180.0) / 6.0)) + 1
    return "EPSG:%d" % ((32600 if lat >= 0 else 32700) + zone)


class SceneFrame:
    """Exact transform from a scene's local UTM metres to the viewer's ENU.

    `origin_lat/lon` is the scene's projector origin (`maps/origin.json`),
    which is the point its local metres are measured from; `anchor_lat/lon` is
    the viewer's ENU anchor. Both single points and whole clouds take the same
    path, so a point cloud cannot drift against the map.
    """

    def __init__(self, origin_lat, origin_lon, anchor_lat, anchor_lon, epsg=None):
        import pyproj
        self.origin_lat = origin_lat
        self.origin_lon = origin_lon
        self.anchor_lat = anchor_lat
        self.anchor_lon = anchor_lon
        self.epsg = epsg or utm_epsg(origin_lat, origin_lon)
        self._fwd = pyproj.Transformer.from_crs("EPSG:4326", self.epsg, always_xy=True)
        self._inv = pyproj.Transformer.from_crs(self.epsg, "EPSG:4326", always_xy=True)
        # Where the scene's (0, 0) sits in absolute UTM. lanelet2's projector
        # offsets by exactly this (useOffset=True).
        self.e0, self.n0 = self._fwd.transform(origin_lon, origin_lat)
        self._kx = math.radians(1.0) * math.cos(math.radians(anchor_lat)) * EARTH_R
        self._ky = math.radians(1.0) * EARTH_R

    def to_lat_lon(self, x, y):
        lon, lat = self._inv.transform(x + self.e0, y + self.n0)
        return lat, lon

    def apply(self, x, y):
        """One point, scene metres -> viewer ENU metres."""
        lat, lon = self.to_lat_lon(x, y)
        return ((lon - self.anchor_lon) * self._kx, (lat - self.anchor_lat) * self._ky)

    def apply_array(self, xy):
        """(N, 2) scene metres -> (N, 2) viewer ENU metres."""
        import numpy as np
        xy = np.asarray(xy, dtype=np.float64)
        lon, lat = self._inv.transform(xy[:, 0] + self.e0, xy[:, 1] + self.n0)
        return np.column_stack([(np.asarray(lon) - self.anchor_lon) * self._kx,
                                (np.asarray(lat) - self.anchor_lat) * self._ky])

    def jacobian_at(self, x, y, step=10.0):
        """The local 2x2 of the transform, for turning orientations into ENU.

        Numeric, because the analytic form buys nothing: the transform is
        smooth and this is evaluated once per pose, not once per point.
        """
        ex0, ey0 = self.apply(x, y)
        exx, eyx = self.apply(x + step, y)
        exy, eyy = self.apply(x, y + step)
        return ((exx - ex0) / step, (exy - ex0) / step,
                (eyx - ey0) / step, (eyy - ey0) / step)

    def rotation_deg(self, x=0.0, y=0.0):
        """How far the scene's x axis is turned from ENU east, in degrees."""
        a11, _, a21, _ = self.jacobian_at(x, y)
        return math.degrees(math.atan2(a21, a11))

    def scale(self, x=0.0, y=0.0):
        """(east, north) ENU metres per scene metre -- not equal, see the module docstring."""
        a11, a12, a21, a22 = self.jacobian_at(x, y)
        return math.hypot(a11, a21), math.hypot(a12, a22)
