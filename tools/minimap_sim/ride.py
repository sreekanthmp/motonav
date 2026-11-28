"""A synthetic ride, the phone feeding the device, and the device's view of it.

The route is made up (it is not a real road) so the repository carries no map data; the street names are examples
chosen to include a long one and a short one. Everything the renderer sees has passed through the real byte formats.
"""
from __future__ import annotations

import math
from dataclasses import dataclass
from typing import List, Optional, Tuple

import phone
import wire

ORIGIN = (12.9716, 77.5946)          # same origin as the protocol's golden vectors
SPEED_MS = 26 / 3.6                  # average city speed used for the ETA


@dataclass
class Step:
    maneuver: int
    street: str
    start_m: float                   # distance along the route where the manoeuvre begins
    exit_no: int = 0


class _Turtle:
    def __init__(self) -> None:
        self.x = self.y = 0.0
        self.h = 0.0                 # degrees clockwise from north
        self.pts: List[Tuple[float, float]] = [(0.0, 0.0)]

    @staticmethod
    def _dir(h: float) -> Tuple[float, float]:
        return math.sin(math.radians(h)), math.cos(math.radians(h))

    def forward(self, d: float, every: float = 12.0) -> None:
        dx, dy = self._dir(self.h)
        n = max(1, int(math.ceil(d / every)))
        for i in range(1, n + 1):
            self.pts.append((self.x + dx * d * i / n, self.y + dy * d * i / n))
        self.x += dx * d
        self.y += dy * d

    def arc(self, turn: float, radius: float) -> None:
        """Turn by [turn] degrees (+ = right) along a circle of [radius]."""
        side = 1.0 if turn > 0 else -1.0
        cx, cy = (self.x + self._dir(self.h + 90 * side)[0] * radius, self.y + self._dir(self.h + 90 * side)[1] * radius)
        n = max(2, int(abs(turn) / 6))
        a0 = math.atan2(self.y - cy, self.x - cx)
        for i in range(1, n + 1):
            a = a0 - side * math.radians(abs(turn)) * i / n         # clockwise (right turn) decreases the math angle
            self.pts.append((cx + radius * math.cos(a), cy + radius * math.sin(a)))
        self.x, self.y = self.pts[-1]
        self.h = (self.h + turn) % 360

    def roundabout(self, exit_no: int, radius: float = 22.0) -> None:
        """Left-hand traffic: circulate clockwise. Exit 1 is the left turn, 2 straight on, 3 right, 4 back."""
        cx, cy = self.x + self._dir(self.h)[0] * radius, self.y + self._dir(self.h)[1] * radius
        h0 = self.h
        sweep = 90 * exit_no
        for ang in range(6, sweep + 1, 6):
            dx, dy = self._dir(h0 + 180 + ang)
            self.pts.append((cx + dx * radius, cy + dy * radius))
        self.x, self.y = self.pts[-1]
        self.h = (h0 + (180 + sweep) % 360) % 360                  # leaves radially: k=1 -> h0-90, k=2 -> h0, ...


def _to_latlon(x: float, y: float) -> phone.LatLon:
    return (ORIGIN[0] + y / phone.M_PER_DEG, ORIGIN[1] + x / (phone.M_PER_DEG * math.cos(math.radians(ORIGIN[0]))))


class Scenario:
    """~6.7 km: depart, left, right, slight right, roundabout, keep right, sharp right, U-turn, left, arrive."""

    LEGS = [  # (maneuver, turn degrees at the start, street, length of the leg, extra)
        (wire.MAN_DEPART, 0, "MG Road", 900, 0),
        (wire.MAN_LEFT, -90, "Brigade Road", 700, 0),
        (wire.MAN_RIGHT, 90, "Residency Road Extension", 800, 0),
        (wire.MAN_SLIGHT_RIGHT, 30, "Outer Ring Road", 500, 0),
        (wire.MAN_ROUNDABOUT, 0, "Silk Board Junction", 650, 2),
        (wire.MAN_KEEP_RIGHT, 12, "NH 44", 1200, 0),
        (wire.MAN_SHARP_RIGHT, 120, "Hosur Road", 600, 0),
        (wire.MAN_UTURN_RIGHT, 180, "Hosur Road", 900, 0),
        (wire.MAN_LEFT, -90, "Church Street", 400, 0),
    ]
    ARRIVE_STREET = "Destination"

    def __init__(self) -> None:
        t = _Turtle()
        starts: List[float] = []
        mans = []
        for k, (man, turn, street, length, extra) in enumerate(self.LEGS):
            starts.append(_polyline_length(t.pts))
            if man == wire.MAN_ROUNDABOUT:
                t.roundabout(extra)
            elif man == wire.MAN_UTURN_RIGHT:
                t.arc(180, 12.0)
            elif turn != 0:
                t.arc(turn, 14.0 if abs(turn) < 100 else 9.0)
            t.forward(length)
            mans.append((man, street, extra))
        t.forward(1.0)
        self.route = phone.Route([_to_latlon(x, y) for x, y in _dedupe(t.pts)])
        self.steps = [Step(m, s, st, e) for st, (m, s, e) in zip(starts, mans)][1:]   # DEPART is not "next"
        self.steps.append(Step(wire.MAN_ARRIVE_RIGHT, self.ARRIVE_STREET, self.route.total - 1.0))
        # Use the actual route cumulative distance for step starts (the turtle polyline is the same shape).
        self.total = self.route.total

    def next_step(self, progress: float) -> Step:
        for s in self.steps:
            if s.start_m > progress:
                return s
        return self.steps[-1]


def _polyline_length(pts) -> float:
    return sum(math.hypot(b[0] - a[0], b[1] - a[1]) for a, b in zip(pts, pts[1:]))


def _dedupe(pts):
    out = [pts[0]]
    for p in pts[1:]:
        if math.hypot(p[0] - out[-1][0], p[1] - out[-1][1]) > 0.4:
            out.append(p)
    return out


# ---- the device --------------------------------------------------------------------------------------------------

@dataclass
class DeviceState:
    connected: bool = False
    core: Optional[wire.Core] = None
    street: str = ""
    window: Optional[wire.RouteWindow] = None
    pos: Optional[wire.Pos] = None


class Device:
    """Receives BLE writes and keeps what the firmware keeps (nav_state.cpp, nav_map.cpp)."""

    def __init__(self) -> None:
        self.state = DeviceState()
        self._asm = wire.RouteAssembler()
        self.windows: List[wire.RouteWindow] = []      # every complete window accepted, oldest first

    def connect(self) -> None:
        self.state = DeviceState(connected=True)
        self._asm = wire.RouteAssembler()

    def disconnect(self) -> None:
        self.state = DeviceState(connected=False)

    def write_core(self, data: bytes) -> bool:
        c = wire.decode_core(data)
        if c:
            self.state.core = c
        return c is not None

    def write_street(self, data: bytes) -> None:
        self.state.street = wire.decode_street(data)

    def write_route(self, chunk: bytes) -> bool:
        if self._asm.feed(chunk):
            self.state.window = self._asm.window
            self.windows.append(self._asm.window)
            return True
        return False

    def write_pos(self, data: bytes) -> bool:
        p = wire.decode_pos(data)
        if p:
            self.state.pos = p
        return p is not None


class Ride:
    """The phone driving a Device along a Scenario at 1 Hz-style updates."""

    def __init__(self, scenario: Optional[Scenario] = None) -> None:
        self.sc = scenario or Scenario()
        self.dev = Device()
        self.dev.connect()
        self.feed = phone.MapFeed(self.sc.route)
        self.seq = 0
        self.progress = 0.0
        self.bytes_sent = 0
        self.log: List[Tuple[str, bytes]] = []          # ("R", NavRoute write) / ("P", NavPos write), in send order
        self.updates(0.0)

    def updates(self, progress: float) -> DeviceState:
        sc = self.sc
        self.progress = progress
        step = sc.next_step(progress)
        dist = max(0.0, step.start_m - progress)
        remaining = max(0.0, sc.total - progress)
        eta = int(math.ceil(remaining / SPEED_MS / 60.0))
        arrived = remaining < 25.0
        core = wire.encode_core(wire.MODE_ARRIVED if arrived else wire.MODE_NAV, step.maneuver, step.exit_no,
                                self.seq, int(round(dist)), int(round(remaining)), eta, int(SPEED_MS * 3.6 * 10))
        self.seq += 1
        street = wire.encode_street(step.street)
        lat, lon = sc.route.point_at(progress)
        chunks, pos = self.feed.tick(progress, lat, lon)
        self.dev.write_core(core)
        self.dev.write_street(street)
        for c in chunks:
            self.dev.write_route(c)
            self.log.append(("R", c))
        self.dev.write_pos(pos)
        self.log.append(("P", pos))
        self.bytes_sent += len(core) + len(street) + sum(map(len, chunks)) + len(pos)
        return self.dev.state

    def advance_to(self, progress: float, step_m: float = 25.0) -> DeviceState:
        """Ride from the current progress to [progress] in 1 s style hops so window resends happen as they would."""
        p = self.progress
        while p + step_m < progress:
            p += step_m
            self.updates(p)
        return self.updates(progress)


def state_at(progress: float) -> Tuple[DeviceState, "Ride"]:
    r = Ride()
    return r.advance_to(progress), r


def progress_before(sc: Scenario, step_index: int, dist_m: float) -> float:
    """Route progress at which the step with [step_index] (in sc.steps) is [dist_m] ahead."""
    return max(0.0, sc.steps[step_index].start_m - dist_m)
