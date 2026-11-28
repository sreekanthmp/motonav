"""Byte-exact mirror of the MotoNav BLE messages (protocol/nav_protocol.h, nav_route.h, NavProtocol.kt).

The simulator never hands the renderer anything the device would not have: every value it draws has been
encoded to the same bytes the phone writes and decoded the way the firmware decodes them.
"""
from __future__ import annotations

import math
import struct
from dataclasses import dataclass, field
from typing import List, Optional, Sequence, Tuple

PROTO_VERSION = 1
ROUTE_STREAM_VERSION = 1
ROUTE_MAX_POINTS = 96
CHUNK_HEADER = 3
STREAM_HEADER = 12
DEFAULT_WRITE_PAYLOAD = 20          # works without MTU negotiation
STREET_MAX = 20
MAX_DELTA = 32767

# MotoNavMode
MODE_IDLE, MODE_NAV, MODE_OFF_ROUTE, MODE_REROUTING, MODE_ARRIVED = 0, 1, 2, 3, 4

# MotoNavManeuver
(MAN_NONE, MAN_DEPART, MAN_STRAIGHT, MAN_SLIGHT_LEFT, MAN_LEFT, MAN_SHARP_LEFT, MAN_SLIGHT_RIGHT, MAN_RIGHT,
 MAN_SHARP_RIGHT, MAN_UTURN_LEFT, MAN_UTURN_RIGHT, MAN_KEEP_LEFT, MAN_KEEP_RIGHT, MAN_MERGE, MAN_RAMP_LEFT,
 MAN_RAMP_RIGHT, MAN_ROUNDABOUT, MAN_FERRY, MAN_ARRIVE, MAN_ARRIVE_LEFT, MAN_ARRIVE_RIGHT) = range(21)


def to_e6(degrees: float) -> int:
    """Kotlin Math.round: half up (Python's round() is half-to-even)."""
    return int(math.floor(degrees * 1e6 + 0.5))


# ---- NavCore / NavStreet / NavPos ------------------------------------------------------------------------------

def encode_core(mode: int, maneuver: int, roundabout_exit: int, seq: int, dist_m: int, remaining_m: int,
                eta_min: int, speed_x10: int) -> bytes:
    clamp = lambda v, hi: max(0, min(hi, int(v)))
    return struct.pack("<BBBBHIIHH", PROTO_VERSION, clamp(mode, 255), clamp(maneuver, 255), clamp(roundabout_exit, 255),
                       seq & 0xFFFF, clamp(dist_m, 0xFFFFFFFF), clamp(remaining_m, 0xFFFFFFFF),
                       clamp(eta_min, 0xFFFF), clamp(speed_x10, 0xFFFF))


@dataclass
class Core:
    mode: int
    maneuver: int
    roundabout_exit: int
    seq: int
    dist_m: int
    remaining_m: int
    eta_min: int
    speed_x10: int


def decode_core(data: bytes) -> Optional[Core]:
    """Firmware rule (nav_state.cpp): wrong length, wrong version or mode > ARRIVED is rejected."""
    if len(data) < 18:
        return None
    v, mode, man, ex, seq, dist, rem, eta, spd = struct.unpack("<BBBBHIIHH", data[:18])
    if v != PROTO_VERSION or mode > MODE_ARRIVED:
        return None
    return Core(mode, man, ex, seq, dist, rem, eta, spd)


def to_ascii_street(name: Optional[str]) -> str:
    """Phone side (NavProtocol.toAsciiStreet): strip accents, mostly-non-ASCII names are dropped, 20 chars."""
    import re
    import unicodedata
    if not name or not name.strip():
        return ""
    stripped = re.sub(r"[̀-ͯ]+", "", unicodedata.normalize("NFD", name))
    non_ascii = sum(1 for ch in stripped if not 0x20 <= ord(ch) <= 0x7E)
    if non_ascii * 2 > len(stripped):
        return ""
    ascii_ = "".join(ch if 0x20 <= ord(ch) <= 0x7E else " " for ch in stripped)
    return re.sub(r"\s+", " ", ascii_.strip())[:STREET_MAX]


def encode_street(name: Optional[str]) -> bytes:
    return to_ascii_street(name).encode("ascii")


def decode_street(data: bytes) -> str:
    """Firmware rule (nav_state.cpp): stop at NUL, anything unprintable becomes '?'."""
    out = []
    for ch in data[:STREET_MAX]:
        if ch == 0:
            break
        out.append(chr(ch) if 0x20 <= ch < 0x7F else "?")
    return "".join(out)


def encode_pos(route_id: int, seq: int, lat_e6: int, lon_e6: int) -> bytes:
    return struct.pack("<BBii", route_id & 0xFF, seq & 0xFF, lat_e6, lon_e6)


@dataclass
class Pos:
    route_id: int
    seq: int
    lat_e6: int
    lon_e6: int


def decode_pos(data: bytes) -> Optional[Pos]:
    if len(data) < 10:
        return None
    rid, seq, lat, lon = struct.unpack("<BBii", data[:10])
    if not (-90_000_000 <= lat <= 90_000_000 and -180_000_000 <= lon <= 180_000_000):
        return None
    return Pos(rid, seq, lat, lon)


# ---- NavRoute (the route window) -------------------------------------------------------------------------------

def split_long_steps(lat: List[int], lon: List[int]) -> Tuple[List[int], List[int]]:
    """Adds in-between points wherever a step is too long for a 16-bit difference (mirror of the Kotlin codec)."""
    la, lo = [lat[0]], [lon[0]]
    for i in range(1, len(lat)):
        d_lat, d_lon = lat[i] - lat[i - 1], lon[i] - lon[i - 1]
        parts = max(1, (max(abs(d_lat), abs(d_lon)) + MAX_DELTA - 1) // MAX_DELTA)
        for k in range(1, parts):
            # Kotlin: (a + d * k / parts).toInt()  -- truncation toward zero
            la.append(lat[i - 1] + int(d_lat * k / parts))
            lo.append(lon[i - 1] + int(d_lon * k / parts))
        la.append(lat[i])
        lo.append(lon[i])
    return la, lo


class WindowTooBig(ValueError):
    pass


def encode_stream(points_deg: Sequence[Tuple[float, float]], ends_at_destination: bool) -> bytes:
    if len(points_deg) < 2:
        raise ValueError("A route window needs at least two points")
    lat = [to_e6(p[0]) for p in points_deg]
    lon = [to_e6(p[1]) for p in points_deg]
    la, lo = split_long_steps(lat, lon)
    if len(la) > ROUTE_MAX_POINTS:
        raise WindowTooBig(f"Too many points for one window: {len(la)}")
    out = bytearray(struct.pack("<BBHii", ROUTE_STREAM_VERSION, 1 if ends_at_destination else 0, len(la), la[0], lo[0]))
    for i in range(1, len(la)):
        out += struct.pack("<hh", la[i] - la[i - 1], lo[i] - lo[i - 1])
    return bytes(out)


def chunk(stream: bytes, route_id: int, write_payload: int = DEFAULT_WRITE_PAYLOAD) -> List[bytes]:
    per = write_payload - CHUNK_HEADER
    if per < 1:
        raise ValueError("Write payload too small")
    total = (len(stream) + per - 1) // per
    if not 1 <= total <= 255:
        raise ValueError("A window must fit in 255 chunks")
    return [bytes([route_id & 0xFF, i, total]) + stream[i * per:(i + 1) * per] for i in range(total)]


@dataclass
class RouteWindow:
    route_id: int
    ends_at_destination: bool
    lat_e6: List[int] = field(default_factory=list)
    lon_e6: List[int] = field(default_factory=list)

    @property
    def count(self) -> int:
        return len(self.lat_e6)

    def points_deg(self) -> List[Tuple[float, float]]:
        return [(a / 1e6, b / 1e6) for a, b in zip(self.lat_e6, self.lon_e6)]


class RouteAssembler:
    """Python twin of motonav::RouteAssembler (protocol/nav_route.h): in-order chunks, exact length, valid coordinates.
    A window that fails any rule is dropped and the previous complete window stays in use."""

    def __init__(self) -> None:
        self.window: Optional[RouteWindow] = None
        self._collecting = False
        self._cur_id = self._total = self._next = 0
        self._buf = bytearray()

    def feed(self, data: bytes) -> bool:
        if len(data) <= CHUNK_HEADER:
            return False
        rid, idx, total = data[0], data[1], data[2]
        if total == 0 or idx >= total:
            self._collecting = False
            return False
        if idx == 0:
            self._collecting, self._cur_id, self._total, self._next = True, rid, total, 0
            self._buf = bytearray()
        elif not self._collecting or rid != self._cur_id or total != self._total or idx != self._next:
            self._collecting = False
            return False
        body = data[CHUNK_HEADER:]
        if len(self._buf) + len(body) > STREAM_HEADER + 4 * (ROUTE_MAX_POINTS - 1):
            self._collecting = False
            return False
        self._buf += body
        self._next = idx + 1
        if self._next < self._total:
            return False
        self._collecting = False
        return self._finish()

    def _finish(self) -> bool:
        s = bytes(self._buf)
        if len(s) < STREAM_HEADER or s[0] != ROUTE_STREAM_VERSION:
            return False
        flags = s[1]
        count = struct.unpack_from("<H", s, 2)[0]
        if count < 2 or count > ROUTE_MAX_POINTS or len(s) != STREAM_HEADER + 4 * (count - 1):
            return False
        lat0, lon0 = struct.unpack_from("<ii", s, 4)
        lat, lon = [lat0], [lon0]
        for k in range(1, count):
            d_lat, d_lon = struct.unpack_from("<hh", s, STREAM_HEADER + 4 * (k - 1))
            lat.append(lat[-1] + d_lat)
            lon.append(lon[-1] + d_lon)
        if any(not -90_000_000 <= v <= 90_000_000 for v in lat) or any(not -180_000_000 <= v <= 180_000_000 for v in lon):
            return False
        self.window = RouteWindow(self._cur_id, bool(flags & 1), lat, lon)
        return True
