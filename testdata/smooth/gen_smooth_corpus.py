#!/usr/bin/env python3
"""Generate a differential-test corpus for the Kotlin Hermite smooth-border port.

The original `smooth_center_lanelet.py` imports JOSM and cannot run under
python3 as a module. The spline math itself is pure Python and is copied here
verbatim (plus a Python-2-compatible `round` so sample allocation matches
Jython 2.7). Regenerate with:

    python3 testdata/smooth/gen_smooth_corpus.py

Determinism comes from a fixed seed.
"""

from __future__ import division

import math
import os
import random

OUT = os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "..",
    "..",
    "src",
    "test",
    "resources",
    "smooth-corpus.txt",
)

SEED = 20260908


def py2_round(x):
    """Python 2 round: half away from zero. Python 3 uses banker's rounding."""
    if x >= 0:
        return int(math.floor(x + 0.5))
    return int(math.ceil(x - 0.5))


def _latlon_to_meters(lat, lon, lat0, lon0):
    m_per_deg_lat = 111320.0
    m_per_deg_lon = 111320.0 * math.cos(lat0 * math.pi / 180.0)
    x = (lon - lon0) * m_per_deg_lon
    y = (lat - lat0) * m_per_deg_lat
    return (x, y)


def _meters_to_latlon(x, y, lat0, lon0):
    m_per_deg_lat = 111320.0
    m_per_deg_lon = 111320.0 * math.cos(lat0 * math.pi / 180.0)
    lon = lon0 + x / m_per_deg_lon
    lat = lat0 + y / m_per_deg_lat
    return (lat, lon)


def hermite_spline(P0, P1, T0, T1, n_samples, tangent_scale=1.0):
    lat0 = 0.5 * (P0[0] + P1[0])
    lon0 = 0.5 * (P0[1] + P1[1])
    m_per_deg_lat = 111320.0
    m_per_deg_lon = 111320.0 * math.cos(lat0 * math.pi / 180.0)

    p0_m = _latlon_to_meters(P0[0], P0[1], lat0, lon0)
    p1_m = _latlon_to_meters(P1[0], P1[1], lat0, lon0)
    chord_m = math.sqrt((p1_m[0] - p0_m[0]) ** 2 + (p1_m[1] - p0_m[1]) ** 2)
    if chord_m < 1e-6:
        return [P0] * n_samples if n_samples > 0 else []

    t0_x = T0[0] * m_per_deg_lon
    t0_y = T0[1] * m_per_deg_lat
    n0 = math.sqrt(t0_x * t0_x + t0_y * t0_y)
    if n0 != 0:
        t0_x, t0_y = t0_x / n0, t0_y / n0
    t1_x = T1[0] * m_per_deg_lon
    t1_y = T1[1] * m_per_deg_lat
    n1 = math.sqrt(t1_x * t1_x + t1_y * t1_y)
    if n1 != 0:
        t1_x, t1_y = t1_x / n1, t1_y / n1

    scale_m = chord_m * tangent_scale
    t0_mx, t0_my = t0_x * scale_m, t0_y * scale_m
    t1_mx, t1_my = t1_x * scale_m, t1_y * scale_m

    out = []
    for i in range(n_samples):
        t = float(i) / (n_samples - 1) if n_samples > 1 else 1.0
        t2 = t * t
        t3 = t2 * t
        h00 = 2 * t3 - 3 * t2 + 1
        h10 = t3 - 2 * t2 + t
        h01 = -2 * t3 + 3 * t2
        h11 = t3 - t2
        x_m = h00 * p0_m[0] + h10 * t0_mx + h01 * p1_m[0] + h11 * t1_mx
        y_m = h00 * p0_m[1] + h10 * t0_my + h01 * p1_m[1] + h11 * t1_my
        lat, lon = _meters_to_latlon(x_m, y_m, lat0, lon0)
        out.append((lat, lon))
    return out


def _chord_tangent(p_prev, p_next):
    dlon = p_next[1] - p_prev[1]
    dlat = p_next[0] - p_prev[0]
    n = math.sqrt(dlon * dlon + dlat * dlat)
    if n < 1e-10:
        return (0.0, 0.0)
    return (dlon / n, dlat / n)


def smooth_border_math(P0, P1, T0, T1, n_samples=25, constraint_points=None):
    if not constraint_points:
        return hermite_spline(P0, P1, T0, T1, n_samples, tangent_scale=1.0)

    full_pts = [P0] + list(constraint_points) + [P1]
    n_seg = len(full_pts) - 1
    if n_seg < 1:
        return hermite_spline(P0, P1, T0, T1, n_samples, tangent_scale=1.0)

    lat0 = 0.5 * (P0[0] + P1[0])
    lon0 = 0.5 * (P0[1] + P1[1])
    seg_lengths = []
    for i in range(n_seg):
        p0_m = _latlon_to_meters(full_pts[i][0], full_pts[i][1], lat0, lon0)
        p1_m = _latlon_to_meters(full_pts[i + 1][0], full_pts[i + 1][1], lat0, lon0)
        seg_lengths.append(math.sqrt((p1_m[0] - p0_m[0]) ** 2 + (p1_m[1] - p0_m[1]) ** 2))
    total = sum(seg_lengths)
    if total < 1e-6:
        return hermite_spline(P0, P1, T0, T1, n_samples, tangent_scale=1.0)
    n_per_seg = [
        max(2, py2_round(float(n_samples) * seg_lengths[i] / total)) for i in range(n_seg)
    ]

    result = [P0]
    for i in range(n_seg):
        pa = full_pts[i]
        pb = full_pts[i + 1]
        if i == 0:
            ta = T0
        else:
            ta = _chord_tangent(full_pts[i - 1], full_pts[i + 1])
        if i == n_seg - 1:
            tb = T1
        else:
            tb = _chord_tangent(full_pts[i], full_pts[i + 2])
        seg = hermite_spline(pa, pb, ta, tb, n_per_seg[i], tangent_scale=1.0)
        result.extend(seg[1:])
    return result


def fmt_pt(p):
    return "%r,%r" % (p[0], p[1])


def fmt_pts(pts):
    return ";".join(fmt_pt(p) for p in pts)


def unit(dlon, dlat):
    n = math.sqrt(dlon * dlon + dlat * dlat)
    if n < 1e-18:
        return (0.0, 0.0)
    return (dlon / n, dlat / n)


def build_cases(rng):
    cases = []

    def add(p0, p1, t0, t1, n, constraints=None):
        cases.append((p0, p1, t0, t1, n, constraints or []))

    # Straight east, unit east tangents, default 25 samples.
    add((49.0, 8.4), (49.0, 8.41), (1.0, 0.0), (1.0, 0.0), 25)
    add((49.0, 8.4), (49.0, 8.41), (1.0, 0.0), (1.0, 0.0), 2)
    add((49.0, 8.4), (49.0, 8.41), (1.0, 0.0), (1.0, 0.0), 1)
    add((49.0, 8.4), (49.0, 8.41), (1.0, 0.0), (1.0, 0.0), 0)

    # Degenerate chord (same point).
    add((49.01, 8.4), (49.01, 8.4), (1.0, 0.0), (0.0, 1.0), 25)
    add((49.01, 8.4), (49.01, 8.4), (1.0, 0.0), (0.0, 1.0), 0)

    # Zero-length tangent (n0 == 0 branch).
    add((49.0, 8.4), (49.001, 8.401), (0.0, 0.0), (1.0, 0.0), 10)
    add((49.0, 8.4), (49.001, 8.401), (1.0, 0.0), (0.0, 0.0), 10)

    # Opposite tangents (S-curve).
    add((49.0, 8.4), (49.002, 8.402), (1.0, 0.0), (-1.0, 0.0), 25)
    add((49.0, 8.4), (49.002, 8.402), (0.0, 1.0), (0.0, -1.0), 25)

    # Northbound (lat increases).
    add((49.0, 8.4), (49.01, 8.4), (0.0, 1.0), (0.0, 1.0), 25)

    # Piecewise with one / two / three constraints.
    add(
        (49.0, 8.4),
        (49.0, 8.41),
        (1.0, 0.0),
        (1.0, 0.0),
        25,
        [(49.0005, 8.403)],
    )
    add(
        (49.0, 8.4),
        (49.002, 8.41),
        (1.0, 0.2),
        (0.8, 0.1),
        25,
        [(49.0004, 8.403), (49.0012, 8.407)],
    )
    add(
        (49.0, 8.4),
        (49.003, 8.412),
        unit(1.0, 0.1),
        unit(0.7, -0.2),
        25,
        [(49.0008, 8.403), (49.0015, 8.406), (49.0024, 8.409)],
    )

    # Constraints that collapse (all on the chord start) -> total < 1e-6 path.
    add((49.0, 8.4), (49.0, 8.4), (1.0, 0.0), (1.0, 0.0), 25, [(49.0, 8.4)])

    # Random typical lane-border chords around Karlsruhe.
    for _ in range(80):
        lat0 = 49.0 + rng.uniform(-0.02, 0.02)
        lon0 = 8.4 + rng.uniform(-0.02, 0.02)
        dlat = rng.uniform(-0.003, 0.003)
        dlon = rng.uniform(0.0005, 0.004) * rng.choice([-1, 1])
        t0 = unit(rng.uniform(-1.0, 1.0), rng.uniform(-1.0, 1.0))
        t1 = unit(rng.uniform(-1.0, 1.0), rng.uniform(-1.0, 1.0))
        n = rng.choice([2, 3, 5, 10, 25])
        add((lat0, lon0), (lat0 + dlat, lon0 + dlon), t0, t1, n)

    # Random piecewise.
    for _ in range(40):
        lat0 = 49.0 + rng.uniform(-0.01, 0.01)
        lon0 = 8.4 + rng.uniform(-0.01, 0.01)
        lat1 = lat0 + rng.uniform(-0.002, 0.002)
        lon1 = lon0 + rng.uniform(0.001, 0.003)
        n_c = rng.randint(1, 3)
        constraints = []
        for k in range(n_c):
            f = (k + 1) / float(n_c + 1)
            constraints.append(
                (
                    lat0 + f * (lat1 - lat0) + rng.uniform(-0.0002, 0.0002),
                    lon0 + f * (lon1 - lon0) + rng.uniform(-0.0002, 0.0002),
                )
            )
        add(
            (lat0, lon0),
            (lat1, lon1),
            unit(rng.uniform(-1, 1), rng.uniform(-1, 1)),
            unit(rng.uniform(-1, 1), rng.uniform(-1, 1)),
            25,
            constraints,
        )

    return cases


def main():
    rng = random.Random(SEED)
    cases = build_cases(rng)
    out_path = os.path.abspath(OUT)
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    lines = [
        "# Differential corpus: expected values from smooth_center_lanelet.py math",
        "# (extracted; JOSM imports dropped). Generated by testdata/smooth/gen_smooth_corpus.py",
        "# seed %d. Sample allocation uses Python-2 round (half away from zero). Do not hand-edit."
        % SEED,
    ]
    for p0, p1, t0, t1, n, constraints in cases:
        expected = smooth_border_math(p0, p1, t0, t1, n, constraints or None)
        lines.append(
            "%s|%s|%s|%s|%d|%s|%s"
            % (
                fmt_pt(p0),
                fmt_pt(p1),
                fmt_pt(t0),
                fmt_pt(t1),
                n,
                fmt_pts(constraints) if constraints else "",
                fmt_pts(expected),
            )
        )
    with open(out_path, "w") as fh:
        fh.write("\n".join(lines) + "\n")
    print("wrote %d cases to %s" % (len(cases), out_path))


if __name__ == "__main__":
    main()
