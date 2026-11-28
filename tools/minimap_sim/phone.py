"""What the phone does: route model, window extraction, simplification, chunking (mirror of MapFeed.kt).

Only the happy path the simulator needs is mirrored (first window, resend after 1 km of use, reconnect); the off-route
and reroute handling of the app is not simulated here.
"""
from __future__ import annotations

import bisect
import math
from dataclasses import dataclass
from typing import List, Optional, Tuple

import wire

EARTH_RADIUS_M = 6_371_008.8
M_PER_DEG = EARTH_RADIUS_M * math.pi / 180.0

LatLon = Tuple[float, float]


def haversine_m(a: LatLon, b: LatLon) -> float:
    p1, p2 = math.radians(a[0]), math.radians(b[0])
    dp, dl = p2 - p1, math.radians(b[1] - a[1])
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * EARTH_RADIUS_M * math.asin(min(1.0, math.sqrt(h)))


class Route:
    def __init__(self, points: List[LatLon]):
        self.points = points
        self.cum = [0.0]
        for a, b in zip(points, points[1:]):
            self.cum.append(self.cum[-1] + haversine_m(a, b))
        self.total = self.cum[-1]

    def point_at(self, progress: float) -> LatLon:
        p = min(max(progress, 0.0), self.total)
        i = min(max(bisect.bisect_right(self.cum, p) - 1, 0), len(self.points) - 2)
        span = self.cum[i + 1] - self.cum[i]
        t = 0.0 if span <= 0 else min(max((p - self.cum[i]) / span, 0.0), 1.0)
        (a0, o0), (a1, o1) = self.points[i], self.points[i + 1]
        return (a0 + t * (a1 - a0), o0 + t * (o1 - o0))


# ---- simplifier (mirror of RouteSimplifier.kt) -----------------------------------------------------------------

def _dist_to_segment(px, py, ax, ay, bx, by) -> float:
    dx, dy = bx - ax, by - ay
    l2 = dx * dx + dy * dy
    if l2 == 0:
        return math.hypot(px - ax, py - ay)
    t = min(max(((px - ax) * dx + (py - ay) * dy) / l2, 0.0), 1.0)
    return math.hypot(px - (ax + t * dx), py - (ay + t * dy))


def simplify(points: List[LatLon], tolerance_m: float) -> List[LatLon]:
    n = len(points)
    if n <= 2:
        return points
    lat0 = points[0][0]
    kx, ky = math.cos(math.radians(lat0)) * M_PER_DEG, M_PER_DEG
    x = [(p[1] - points[0][1]) * kx for p in points]
    y = [(p[0] - lat0) * ky for p in points]
    keep = [False] * n
    keep[0] = keep[-1] = True
    stack = [(0, n - 1)]
    while stack:
        lo, hi = stack.pop()
        if hi - lo < 2:
            continue
        worst, idx = -1.0, -1
        for i in range(lo + 1, hi):
            d = _dist_to_segment(x[i], y[i], x[lo], y[lo], x[hi], y[hi])
            if d > worst:
                worst, idx = d, i
        if worst > tolerance_m:
            keep[idx] = True
            stack += [(lo, idx), (idx, hi)]
    return [p for p, k in zip(points, keep) if k]


def _evenly(points: List[LatLon], count: int) -> List[LatLon]:
    return [points[(k * (len(points) - 1)) // (count - 1)] for k in range(count)]


MAX_INPUT_POINTS = 4000


def simplify_to_max(points: List[LatLon], max_points: int) -> List[LatLon]:
    if len(points) <= max_points:
        return points
    base = _evenly(points, MAX_INPUT_POINTS) if len(points) > MAX_INPUT_POINTS else points
    tol = 2.0
    while tol <= 1024.0:
        s = simplify(base, tol)
        if len(s) <= max_points:
            return s
        tol *= 2
    return _evenly(base, max_points)


# ---- map feed (mirror of MapFeed.kt) ---------------------------------------------------------------------------

@dataclass
class FeedConfig:
    behind_m: float = 200.0
    ahead_m: float = 2500.0
    resend_after_m: float = 1000.0
    max_points: int = wire.ROUTE_MAX_POINTS
    write_payload: int = wire.DEFAULT_WRITE_PAYLOAD


class MapFeed:
    """Emits the exact byte writes the app would send: route chunks (NavRoute) and positions (NavPos)."""

    def __init__(self, route: Route, config: Optional[FeedConfig] = None, ends_at_destination: bool = True):
        self.route = route
        self.cfg = config or FeedConfig()
        self.ends_at_destination = ends_at_destination
        self.window_id = 0
        self.window_start_m = 0.0
        self.next_id = 1
        self.seq = 0
        self.need_window = True
        self.last_window_points: List[LatLon] = []   # what the phone sent (after simplification)

    def on_connected(self) -> None:
        self.need_window = True

    def tick(self, progress_m: float, lat: float, lon: float) -> Tuple[List[bytes], bytes]:
        """One 1 Hz position update. Returns (route chunk writes, NavPos write)."""
        chunks: List[bytes] = []
        used = progress_m - self.window_start_m > self.cfg.resend_after_m or progress_m < self.window_start_m - 50.0
        if self.need_window or self.window_id == 0 or used:
            chunks = self._send_window(progress_m)
        pos = wire.encode_pos(self.window_id, self.seq & 0xFF, wire.to_e6(lat), wire.to_e6(lon))
        self.seq += 1
        return chunks, pos

    def _send_window(self, progress_m: float) -> List[bytes]:
        r, cfg = self.route, self.cfg
        start = max(0.0, progress_m - cfg.behind_m)
        end = min(r.total, progress_m + cfg.ahead_m)
        pts: List[LatLon] = [r.point_at(start)]
        first = bisect.bisect_right(r.cum, start)            # lastAtOrBelow(start) + 1
        last = bisect.bisect_right(r.cum, end) - 1           # lastAtOrBelow(end)
        for i in range(first, last + 1):
            if haversine_m(pts[-1], r.points[i]) >= 0.5:
                pts.append(r.points[i])
        tail = r.point_at(end)
        if haversine_m(pts[-1], tail) >= 0.5:
            pts.append(tail)
        if len(pts) < 2:
            pts.append(tail)
        limit, stream, simple = cfg.max_points, None, pts
        while stream is None:
            simple = simplify_to_max(pts, limit)
            try:
                stream = wire.encode_stream(simple, self.ends_at_destination and end >= r.total - 1.0)
            except wire.WindowTooBig:
                if limit <= 4:
                    raise
                limit = max(4, int(limit * 0.8))
        wid = self.next_id
        self.next_id = 1 if self.next_id >= 255 else self.next_id + 1
        self.window_id, self.window_start_m, self.need_window = wid, start, False
        self.last_window_points = simple
        return wire.chunk(stream, wid, cfg.write_payload)
