"""Manoeuvre icons, drawn from simple geometry (no image files).

Each icon is a list of primitives in a unit box (x right, y up). draw_icon() fits the icon's bounding box into a square
of the requested pixel size and centres it, so every icon has the same optical size on screen.
India drives on the left: roundabouts circulate clockwise (exit 1 is a left turn) and the default U-turn is the
clockwise one (a right-hand turn).
"""
from __future__ import annotations

import math
from typing import List, Optional, Tuple

from PIL import ImageDraw

import fonts
import wire

Pt = Tuple[float, float]
WHITE = (255, 255, 255, 255)
FAINT = (112, 112, 112, 255)


def _rounded(path: List[Pt], radius: float) -> List[Pt]:
    """Round the corners of a polyline with quadratic curves."""
    if len(path) < 3:
        return list(path)
    out = [path[0]]
    for i in range(1, len(path) - 1):
        a, w, b = path[i - 1], path[i], path[i + 1]
        la, lb = math.dist(a, w), math.dist(b, w)
        t = min(radius, la / 2, lb / 2)
        p1 = (w[0] + (a[0] - w[0]) / la * t, w[1] + (a[1] - w[1]) / la * t)
        p2 = (w[0] + (b[0] - w[0]) / lb * t, w[1] + (b[1] - w[1]) / lb * t)
        for k in range(0, 13):
            u = k / 12
            out.append(((1 - u) ** 2 * p1[0] + 2 * (1 - u) * u * w[0] + u * u * p2[0],
                        (1 - u) ** 2 * p1[1] + 2 * (1 - u) * u * w[1] + u * u * p2[1]))
    out.append(path[-1])
    return out


def _arc(cx: float, cy: float, r: float, a0: float, a1: float, n: int = 24) -> List[Pt]:
    return [(cx + r * math.cos(math.radians(a0 + (a1 - a0) * i / n)), cy + r * math.sin(math.radians(a0 + (a1 - a0) * i / n)))
            for i in range(n + 1)]


STROKE = 0.24      # stroke width relative to the unit box (box is 2 wide)
HEAD_L, HEAD_W = 0.62, 0.62


def _arrow(path: List[Pt], color=WHITE, head: bool = True, width: float = STROKE) -> list:
    return [("stroke", path, width, color, head)]


def _mirror(prims: list) -> list:
    out = []
    for p in prims:
        if p[0] == "stroke":
            out.append(("stroke", [(-x, y) for x, y in p[1]], *p[2:]))
        elif p[0] == "poly":
            out.append(("poly", [(-x, y) for x, y in p[1]], *p[2:]))
        else:
            out.append(p)
    return out


def _turn(angle_deg: float) -> list:
    """Left turn of [angle_deg] degrees (45 = slight, 90 = left, 135 = sharp): drawn for the LEFT side."""
    a = math.radians(angle_deg)
    d = (-math.sin(a), math.cos(a))                    # direction after the turn (x right, y up)
    corner, diag, rad = {45: ((0.3, -0.1), 1.15, 0.5), 90: ((0.3, 0.0), 0.95, 0.5)}.get(
        int(angle_deg), ((0.5, 0.55), 1.25, 0.3))
    end = (corner[0] + d[0] * diag, corner[1] + d[1] * diag)
    return _arrow(_rounded([(corner[0], -1.0), corner, end], rad))


def _keep(left: bool) -> list:
    main = _arrow(_rounded([(0.35, -1.0), (0.35, -0.35), (-0.25, 0.25), (-0.25, 0.75)], 0.35))
    branch = _arrow([(0.35, -0.4), (0.35, 0.75)], FAINT, head=False, width=0.18)
    prims = branch + main
    return prims if left else _mirror(prims)


def _uturn(clockwise: bool) -> list:
    r = 0.5
    path = [(-0.5, -1.0), (-0.5, 0.2)] + _arc(0.0, 0.2, r, 180, 0)[1:-1] + [(0.5, 0.2), (0.5, -0.35)]
    prims = _arrow(path)
    return prims if clockwise else _mirror(prims)


def _roundabout(exit_no: int) -> list:
    cx, cy, r = 0.0, 0.0, 0.62
    theta = {0: 0, 1: -90, 2: 0, 3: 90}.get(exit_no, 150)          # degrees clockwise from straight ahead
    d = (math.sin(math.radians(theta)), math.cos(math.radians(theta)))
    start = (cx + d[0] * (r - 0.02), cy + d[1] * (r - 0.02))
    end = (cx + d[0] * (r + 0.12), cy + d[1] * (r + 0.12))
    return [("ring", (cx, cy), r, 0.18, WHITE),
            ("stroke", [(0.0, -1.15), (0.0, cy - r)], 0.18, WHITE, False),
            ("stroke", [start, end], 0.22, WHITE, True, 0.8),
            ("digit", (cx, cy), r, str(exit_no) if exit_no else "")]


def _flag() -> list:
    return [("stroke", [(-0.45, -1.0), (-0.45, 0.95)], 0.16, WHITE, False),
            ("poly", [(-0.45, 0.95), (0.95, 0.5), (-0.45, 0.05)], WHITE)]


def _ferry() -> list:
    return [("poly", [(-0.9, -0.05), (0.9, -0.05), (0.55, -0.55), (-0.55, -0.55)], WHITE),
            ("poly", [(-0.3, -0.05), (-0.3, 0.55), (0.4, 0.55), (0.4, -0.05)], WHITE),
            ("stroke", [(-0.9, -0.8), (-0.45, -0.7), (0.0, -0.8), (0.45, -0.7), (0.9, -0.8)], 0.1, FAINT, False)]


def icon(maneuver: int, exit_no: int = 0) -> list:
    M = wire
    if maneuver in (M.MAN_STRAIGHT, M.MAN_DEPART):
        return _arrow([(0.0, -1.0), (0.0, 0.35)])
    if maneuver == M.MAN_LEFT:
        return _turn(90)
    if maneuver == M.MAN_RIGHT:
        return _mirror(_turn(90))
    if maneuver == M.MAN_SLIGHT_LEFT:
        return _turn(45)
    if maneuver == M.MAN_SLIGHT_RIGHT:
        return _mirror(_turn(45))
    if maneuver == M.MAN_SHARP_LEFT:
        return _turn(135)
    if maneuver == M.MAN_SHARP_RIGHT:
        return _mirror(_turn(135))
    if maneuver == M.MAN_UTURN_RIGHT:
        return _uturn(True)
    if maneuver == M.MAN_UTURN_LEFT:
        return _uturn(False)
    if maneuver in (M.MAN_KEEP_LEFT, M.MAN_RAMP_LEFT):
        return _keep(True)
    if maneuver in (M.MAN_KEEP_RIGHT, M.MAN_RAMP_RIGHT):
        return _keep(False)
    if maneuver == M.MAN_MERGE:
        return _arrow([(-0.55, -1.0), (-0.55, -0.45), (0.0, 0.1), (0.0, 0.5)], WHITE) + \
            _arrow([(0.55, -1.0), (0.55, -0.45), (0.0, 0.1)], FAINT, head=False, width=0.18)
    if maneuver == M.MAN_ROUNDABOUT:
        return _roundabout(exit_no)
    if maneuver == M.MAN_FERRY:
        return _ferry()
    if maneuver in (M.MAN_ARRIVE, M.MAN_ARRIVE_LEFT, M.MAN_ARRIVE_RIGHT):
        return _flag()
    return []


# ---- drawing -----------------------------------------------------------------------------------------------------

def _head(path: List[Pt], width: float, scale: float = 1.0) -> List[Pt]:
    (x0, y0), (x1, y1) = path[-2], path[-1]
    d = math.dist((x0, y0), (x1, y1)) or 1.0
    ux, uy = (x1 - x0) / d, (y1 - y0) / d
    px, py = -uy, ux
    hw, hl = (HEAD_W * (width / STROKE) ** 0.5 if width != STROKE else HEAD_W) * scale, HEAD_L * scale
    return [(x1 + px * hw, y1 + py * hw), (x1 - px * hw, y1 - py * hw), (x1 + ux * hl, y1 + uy * hl)]


def _bbox(prims: list) -> Optional[Tuple[float, float, float, float]]:
    xs: List[float] = []
    ys: List[float] = []
    for p in prims:
        kind = p[0]
        if kind == "stroke":
            for x, y in p[1]:
                xs += [x - p[2] / 2, x + p[2] / 2]
                ys += [y - p[2] / 2, y + p[2] / 2]
            if p[4]:
                for x, y in _head(p[1], p[2], p[5] if len(p) > 5 else 1.0):
                    xs.append(x)
                    ys.append(y)
        elif kind == "poly":
            for x, y in p[1]:
                xs.append(x)
                ys.append(y)
        elif kind == "ring":
            (cx, cy), r, w = p[1], p[2], p[3]
            xs += [cx - r - w / 2, cx + r + w / 2]
            ys += [cy - r - w / 2, cy + r + w / 2]
    return (min(xs), min(ys), max(xs), max(ys)) if xs else None


def draw_icon(draw: ImageDraw.ImageDraw, cx: float, cy: float, size: float, maneuver: int, exit_no: int = 0,
              ss: int = 1, color: Optional[Tuple[int, int, int, int]] = None) -> Optional[Tuple[float, float]]:
    """Draw the icon centred at (cx, cy) fitting a size x size box (coordinates in panel pixels, ss = supersampling).
    Returns the icon's (width, height) in panel pixels, or None if there is nothing to draw."""
    prims = icon(maneuver, exit_no)
    box = _bbox(prims)
    if not box:
        return None
    x0, y0, x1, y1 = box
    s = size / max(x1 - x0, y1 - y0)
    mx, my = (x0 + x1) / 2, (y0 + y1) / 2
    T = lambda x, y: ((cx + (x - mx) * s) * ss, (cy - (y - my) * s) * ss)
    col = lambda c: color if color is not None and c == WHITE else c
    for p in prims:
        kind = p[0]
        if kind == "stroke":
            pts = [T(*q) for q in p[1]]
            w = max(1, int(round(p[2] * s * ss)))
            draw.line(pts, fill=col(p[3]), width=w, joint="curve")
            if p[4]:
                draw.polygon([T(*q) for q in _head(p[1], p[2], p[5] if len(p) > 5 else 1.0)], fill=col(p[3]))
        elif kind == "poly":
            draw.polygon([T(*q) for q in p[1]], fill=col(p[2]))
        elif kind == "ring":
            (rx, ry), r, w = p[1], p[2], p[3]
            ccx, ccy = T(rx, ry)
            rr = r * s * ss
            draw.ellipse([ccx - rr - w * s * ss / 2, ccy - rr - w * s * ss / 2, ccx + rr + w * s * ss / 2, ccy + rr + w * s * ss / 2],
                         outline=col(p[4]), width=max(1, int(round(w * s * ss))))
        elif kind == "digit" and p[3]:
            ccx, ccy = T(*p[1])
            inner = (p[2] - 0.1) * s * ss                  # inner radius in canvas pixels
            f = fonts.font(int(inner * 1.7))                # cap height ~ 1.2 x inner radius
            draw.text((ccx, ccy), p[3], font=f, fill=col(WHITE), anchor="mm")
    return ((x1 - x0) * s, (y1 - y0) * s)
