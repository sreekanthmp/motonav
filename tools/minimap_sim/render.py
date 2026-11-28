"""Draws the 412 x 412 round panel from what the device has received (a ride.DeviceState).

Two layouts:
  A  everything on one screen: arrow, distance, street, mini-map, distance left and minutes left.
  B  adaptive: layout A while cruising; when the turn is close (<= TURN_SWITCH_M) a large-arrow screen without the map.

Every element is drawn on its own transparent layer, supersampled 4x and reduced with a high-quality filter (the
panel's controller/LVGL does anti-aliasing too). The layers let the renderer prove that every lit pixel of every element
lies inside the circle with a margin, and measure each text's cap height for the legibility report.
"""
from __future__ import annotations

import math
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Tuple

import numpy as np
from PIL import Image, ImageDraw

import arrows
import fonts
import phone
import wire
from ride import DeviceState

SIZE = 412
SS = 4
CX = CY = SIZE / 2
R = SIZE / 2
SAFE_MARGIN_PX = 12            # assumed: bezel / bonding mask hides ~1 mm at the edge (check against the panel drawing)
TURN_SWITCH_M = 300            # layout B: large-arrow screen at or below this distance to the manoeuvre
PITCH_MM = 36.8 / SIZE         # 1.45" round panel, active diameter ~36.8 mm (datasheet value to be confirmed)
VIEW_DIST_MM = 550.0           # handlebar to eye

# colours (sRGB). White and amber survive sunlight; saturated blue/green on black do not.
BLACK = (0, 0, 0, 255)
WHITE = (255, 255, 255, 255)
AMBER = (255, 176, 0, 255)
DIM = (96, 96, 96, 255)
MAPBG = (14, 20, 28, 255)
MAPEDGE = (52, 66, 82, 255)
RED = (230, 32, 32, 255)
SOFT = (200, 200, 200, 255)
OUTSIDE = (22, 22, 22)

MPP_LADDER = [1.5, 2, 3, 4, 6, 8, 12, 16, 24, 32, 48, 64, 96, 128]
NICE_M = [10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000]


# ---- formatting ------------------------------------------------------------------------------------------------

def fmt_dist(m: int) -> Tuple[str, str]:
    """(value, unit) for a distance in whole metres. Round numbers a rider can take in at a glance.
    Integer arithmetic, halves round up: the firmware does exactly the same (no float printing on the device)."""
    m = int(m)
    if m >= 999_500:
        return "999+", "km"                        # keeps the number inside the circle for absurd distances
    if m < 100:
        return str(((m + 5) // 10) * 10), "m"
    if m < 1000:
        return str(((m + 25) // 50) * 50), "m"
    if m < 9950:
        tenths = (m + 50) // 100
        return f"{tenths // 10}.{tenths % 10}", "km"
    return str((m + 500) // 1000), "km"


def fmt_eta(minutes: int) -> str:
    if minutes < 60:
        return f"{minutes} min"
    if minutes >= 6000:
        return "99+ h"
    return f"{minutes // 60} h {minutes % 60:02d} m"


_ABBREV = [("Extension", "Ext"), ("Junction", "Jn"), ("Road", "Rd"), ("Street", "St"), ("Highway", "Hwy"),
           ("Avenue", "Ave"), ("Boulevard", "Blvd"), ("Circle", "Cir"), ("Main", "Mn")]


# ---- the panel ---------------------------------------------------------------------------------------------------

@dataclass
class Element:
    name: str
    role: str                      # primary | secondary | tertiary | graphic
    text: str = ""
    font_px: float = 0.0
    cap_px: float = 0.0            # measured cap height of the font on the panel (panel pixels)
    max_radius: float = 0.0        # farthest lit pixel from the panel centre (panel pixels)
    bbox: Tuple[float, float, float, float] = (0, 0, 0, 0)
    note: str = ""

    @property
    def cap_mm(self) -> float:
        return self.cap_px * PITCH_MM

    @property
    def cap_arcmin(self) -> float:
        return math.degrees(2 * math.atan(self.cap_mm / 2 / VIEW_DIST_MM)) * 60 if self.cap_px else 0.0


class Panel:
    def __init__(self) -> None:
        self._layers: List[Tuple[Element, Image.Image]] = []

    def layer(self, name: str, role: str = "graphic") -> Tuple[Element, ImageDraw.ImageDraw]:
        el = Element(name, role)
        img = Image.new("RGBA", (SIZE * SS, SIZE * SS), (0, 0, 0, 0))
        self._layers.append((el, img))
        return el, ImageDraw.Draw(img)

    def text(self, name: str, role: str, xy: Tuple[float, float], s: str, size: float, fill=WHITE, anchor: str = "mm",
             max_w: Optional[float] = None, min_size: Optional[float] = None) -> Element:
        """Draw text; with max_w it shrinks to min_size, abbreviates, then truncates with an ellipsis."""
        el, d = self.layer(name, role)
        shown, px = s, size
        f = fonts.font(px * SS)
        if max_w is not None:
            floor = min_size if min_size is not None else size
            while d.textlength(shown, font=f) / SS > max_w and px > floor:
                px -= 1
                f = fonts.font(px * SS)
            if d.textlength(shown, font=f) / SS > max_w:
                for long, short in _ABBREV:
                    shown = shown.replace(long, short)
                    if d.textlength(shown, font=f) / SS <= max_w:
                        break
            if d.textlength(shown, font=f) / SS > max_w:
                while len(shown) > 1 and d.textlength(shown + "…", font=f) / SS > max_w:
                    shown = shown[:-1].rstrip()
                shown += "…"
            if shown != s:
                el.note = f"shortened from '{s}'"
        d.text((xy[0] * SS, xy[1] * SS), shown, font=f, fill=fill, anchor=anchor)
        el.text, el.font_px = shown, px
        el.cap_px = (f.getbbox("H")[3] - f.getbbox("H")[1]) / SS
        return el

    def value_unit(self, name: str, role: str, cx: float, baseline: float, value: str, unit: str, size: float,
                   unit_size: float, fill=WHITE, unit_fill=SOFT) -> Element:
        """A big number with a smaller unit on the same baseline, the pair centred on cx."""
        el, d = self.layer(name, role)
        fv, fu = fonts.font(size * SS), fonts.font(unit_size * SS)
        gap = 0.22 * unit_size
        wv, wu = d.textlength(value, font=fv) / SS, d.textlength(unit, font=fu) / SS
        x0 = cx - (wv + gap + wu) / 2
        d.text((x0 * SS, baseline * SS), value, font=fv, fill=fill, anchor="ls")
        d.text(((x0 + wv + gap) * SS, baseline * SS), unit, font=fu, fill=unit_fill, anchor="ls")
        el.text, el.font_px = f"{value} {unit}", size
        el.cap_px = (fv.getbbox("H")[3] - fv.getbbox("H")[1]) / SS
        return el

    def finish(self, outside=OUTSIDE) -> Tuple[Image.Image, Dict[str, Element]]:
        base = Image.new("RGBA", (SIZE * SS, SIZE * SS), BLACK)
        meta: Dict[str, Element] = {}
        yy, xx = np.mgrid[0:SIZE * SS, 0:SIZE * SS]
        radius = np.hypot(xx + 0.5 - CX * SS, yy + 0.5 - CY * SS) / SS
        for el, img in self._layers:
            a = np.asarray(img.getchannel("A"))
            lit = a > 40
            if lit.any():
                el.max_radius = float(radius[lit].max())
                ys, xs = np.nonzero(lit)
                el.bbox = (xs.min() / SS, ys.min() / SS, (xs.max() + 1) / SS, (ys.max() + 1) / SS)
            base = Image.alpha_composite(base, img)
            meta[el.name] = el
        small = base.resize((SIZE, SIZE), Image.LANCZOS).convert("RGB")
        mask = Image.new("L", (SIZE * SS, SIZE * SS), 0)
        ImageDraw.Draw(mask).ellipse([0, 0, SIZE * SS - 1, SIZE * SS - 1], fill=255)
        mask = mask.resize((SIZE, SIZE), Image.LANCZOS)
        bg = Image.new("RGB", (SIZE, SIZE), outside)
        return Image.composite(small, bg, mask), meta


def outside_violations(meta: Dict[str, Element], margin: float = SAFE_MARGIN_PX) -> List[str]:
    """Elements with lit pixels closer to the edge than the margin."""
    return [f"{e.name}: {R - e.max_radius:.1f} px from the edge (need {margin})"
            for e in meta.values() if e.max_radius and e.max_radius > R - margin]


# ---- mini-map ----------------------------------------------------------------------------------------------------

@dataclass
class ViewState:
    """Carries the map zoom between frames so it does not flicker (zoom out at once, zoom in only with room to spare)."""
    mpp: Optional[float] = None


def _local(window: wire.RouteWindow, pos: wire.Pos) -> Tuple[List[Tuple[float, float]], Tuple[float, float]]:
    lat0, lon0 = pos.lat_e6 / 1e6, pos.lon_e6 / 1e6
    kx = math.cos(math.radians(lat0)) * phone.M_PER_DEG
    pts = [((lo / 1e6 - lon0) * kx, (la / 1e6 - lat0) * phone.M_PER_DEG) for la, lo in zip(window.lat_e6, window.lon_e6)]
    return pts, (0.0, 0.0)


def _project(pts: List[Tuple[float, float]], p: Tuple[float, float] = (0.0, 0.0)) -> Tuple[int, float, float, float]:
    """Nearest point of the polyline to p: (segment index, t, distance along the line to it, distance from p)."""
    best = (0, 0.0, 1e18)
    cum = 0.0
    best_cum = 0.0
    for i in range(len(pts) - 1):
        (ax, ay), (bx, by) = pts[i], pts[i + 1]
        dx, dy = bx - ax, by - ay
        l2 = dx * dx + dy * dy
        t = 0.0 if l2 == 0 else min(max(((p[0] - ax) * dx + (p[1] - ay) * dy) / l2, 0.0), 1.0)
        d = math.hypot(p[0] - (ax + t * dx), p[1] - (ay + t * dy))
        if d < best[2]:
            best, best_cum = (i, t, d), cum + t * math.sqrt(l2)
        cum += math.sqrt(l2)
    return best[0], best[1], best_cum, best[2]


def _ahead_points(pts: List[Tuple[float, float]], seg: int, t: float, length_m: float) -> List[Tuple[float, float]]:
    (ax, ay), (bx, by) = pts[seg], pts[seg + 1]
    out = [(ax + t * (bx - ax), ay + t * (by - ay))]
    left = length_m
    for i in range(seg + 1, len(pts)):
        d = math.hypot(pts[i][0] - out[-1][0], pts[i][1] - out[-1][1])
        if d >= left:
            f = left / d if d else 0.0
            out.append((out[-1][0] + f * (pts[i][0] - out[-1][0]), out[-1][1] + f * (pts[i][1] - out[-1][1])))
            return out
        out.append(pts[i])
        left -= d
    return out


def _thick(d: ImageDraw.ImageDraw, pts, width: float, fill) -> None:
    if len(pts) < 2:
        return
    w = max(1, int(round(width)))
    d.line(pts, fill=fill, width=w, joint="curve")
    r = w / 2
    for (x, y) in (pts[0], pts[-1]):                       # round end caps; joints are already curved
        d.ellipse([x - r, y - r, x + r, y + r], fill=fill)


def draw_map(panel: Panel, state: DeviceState, rect: Tuple[float, float, float, float], look_ahead_m: float,
             view: Optional[ViewState] = None, line_px: float = 9.0) -> dict:
    """The route line, position, destination and scale bar, north-up, inside rect = (x0, y0, x1, y1) in panel pixels."""
    x0, y0, x1, y1 = rect
    vw, vh = x1 - x0, y1 - y0
    el, d = panel.layer("map")
    corner = 16
    d.rounded_rectangle([x0 * SS, y0 * SS, x1 * SS, y1 * SS], radius=corner * SS, fill=MAPBG)
    info = {"mpp": None, "scale_m": None, "window_points": 0}
    pos, win = state.pos, state.window
    if pos is None:
        d.rounded_rectangle([x0 * SS, y0 * SS, x1 * SS, y1 * SS], radius=corner * SS, outline=MAPEDGE, width=2 * SS)
        return info

    vp = Image.new("RGBA", (int(vw * SS), int(vh * SS)), MAPBG)
    vd = ImageDraw.Draw(vp)
    have_route = win is not None and win.count >= 2 and win.route_id == pos.route_id
    if not have_route:
        _marker(vd, vw / 2, vh / 2, 0.0)
    else:
        pts, rider = _local(win, pos)
        seg, t, along, off = _project(pts, rider)
        info["window_points"] = win.count
        ahead = _ahead_points(pts, seg, t, look_ahead_m)
        xs = [rider[0]] + [p[0] for p in ahead]
        ys = [rider[1]] + [p[1] for p in ahead]
        pad = 26.0
        need = max((max(xs) - min(xs)) / max(1.0, vw - 2 * pad), (max(ys) - min(ys)) / max(1.0, vh - 2 * pad))
        mpp = next((m for m in MPP_LADDER if m >= need), MPP_LADDER[-1])
        if view is not None and view.mpp is not None:
            if view.mpp >= need and need > 0.62 * view.mpp:        # keep the previous zoom while it still fits well enough
                mpp = view.mpp
        if view is not None:
            view.mpp = mpp
        mx, my = (min(xs) + max(xs)) / 2, (min(ys) + max(ys)) / 2
        to_px = lambda p: ((vw / 2 + (p[0] - mx) / mpp) * SS, (vh / 2 - (p[1] - my) / mpp) * SS)

        # route: passed part dim, part ahead amber with a dark casing
        a, b = pts[seg], pts[seg + 1]
        cut = (a[0] + t * (b[0] - a[0]), a[1] + t * (b[1] - a[1]))
        behind = [to_px(p) for p in pts[:seg + 1]] + [to_px(cut)]
        full_ahead = [cut] + pts[seg + 1:]
        _thick(vd, behind, line_px * SS * 0.8, DIM)
        _thick(vd, [to_px(p) for p in full_ahead], (line_px + 6) * SS, BLACK)
        _thick(vd, [to_px(p) for p in full_ahead], line_px * SS, AMBER)
        if win.ends_at_destination:
            dx, dy = to_px(pts[-1])
            for rad, col in ((13, BLACK), (10.5, WHITE), (6.5, RED)):
                vd.ellipse([dx - rad * SS, dy - rad * SS, dx + rad * SS, dy + rad * SS], fill=col)
        heading = math.atan2(b[0] - a[0], b[1] - a[1])
        rx, ry = to_px(rider)
        _marker(vd, rx / SS, ry / SS, heading)
        info.update(mpp=mpp)

    mask = Image.new("L", vp.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, vp.size[0] - 1, vp.size[1] - 1], radius=corner * SS, fill=255)
    el_img = panel._layers[-1][1]
    el_img.paste(vp, (int(x0 * SS), int(y0 * SS)), mask)
    d.rounded_rectangle([x0 * SS, y0 * SS, x1 * SS, y1 * SS], radius=corner * SS, outline=MAPEDGE, width=2 * SS)

    if info["mpp"]:
        info["scale_m"] = _scale_bar(panel, x0 + 14, y1 - 12, info["mpp"])
    return info


def _marker(vd: ImageDraw.ImageDraw, x: float, y: float, heading_rad: float) -> None:
    """The rider: a white arrowhead pointing along the road, with a dark casing (north up; no compass needed)."""
    def tri(scale: float):
        tip, back, half = 13 * scale, 9 * scale, 9 * scale
        ux, uy = math.sin(heading_rad), -math.cos(heading_rad)        # screen direction (y down)
        px, py = -uy, ux
        return [(x + ux * tip, y + uy * tip), (x - ux * back * 0.6 + px * half, y - uy * back * 0.6 + py * half),
                (x - ux * back * 0.2, y - uy * back * 0.2), (x - ux * back * 0.6 - px * half, y - uy * back * 0.6 - py * half)]
    vd.polygon([(a * SS, b * SS) for a, b in tri(1.35)], fill=BLACK)
    vd.polygon([(a * SS, b * SS) for a, b in tri(1.0)], fill=WHITE)


def _scale_bar(panel: Panel, x: float, y: float, mpp: float) -> int:
    length = max((n for n in NICE_M if n / mpp <= 96), default=NICE_M[0])
    px = length / mpp
    el, d = panel.layer("scale", "tertiary")
    d.line([(x * SS, y * SS), ((x + px) * SS, y * SS)], fill=WHITE, width=4 * SS)
    for xx in (x, x + px):
        d.line([(xx * SS, (y - 7) * SS), (xx * SS, (y + 3) * SS)], fill=WHITE, width=3 * SS)
    label = f"{length} m" if length < 1000 else f"{length // 1000} km"
    f = fonts.font(26 * SS)
    d.text((x * SS, (y - 10) * SS), label, font=f, fill=WHITE, anchor="ls", stroke_width=3 * SS, stroke_fill=MAPBG)
    el.text, el.font_px = label, 26
    el.cap_px = (f.getbbox("H")[3] - f.getbbox("H")[1]) / SS
    return length


# ---- layouts -----------------------------------------------------------------------------------------------------

def chord_half(y: float, margin: float = SAFE_MARGIN_PX) -> float:
    """Half the width available at height y inside the safe circle."""
    dy = abs(y - CY)
    inner = R - margin
    return math.sqrt(max(0.0, inner * inner - dy * dy))


def row_half(y: float, size: float) -> float:
    """Half the width a line of text centred on y may use: the narrowest chord between its ascender top (0.38 em above
    the middle) and its descender bottom (0.56 em below it), so no glyph of any name can reach the edge."""
    return min(chord_half(y - 0.38 * size), chord_half(y + 0.56 * size))


def _street(panel: Panel, state: DeviceState, y: float, size: float = 40, min_size: float = 34) -> Element:
    half = row_half(y, size)
    return panel.text("street", "secondary", (CX, y), state.street, size, max_w=2 * half - 6, min_size=min_size)


def _bottom(panel: Panel, state: DeviceState, y: float, size: float = 34) -> Element:
    c = state.core
    rv, ru = fmt_dist(c.remaining_m)
    s = f"{rv} {ru} · {fmt_eta(c.eta_min)}"
    return panel.text("remaining", "secondary", (CX, y), s, size, max_w=2 * chord_half(y + 12) - 6, min_size=24)


def _message(panel: Panel, kind: str, line1: str, line2: str) -> None:
    el, d = panel.layer("icon")
    cx, cy = CX, 122
    if kind == "warn":
        d.polygon([(cx * SS, (cy - 52) * SS), ((cx + 58) * SS, (cy + 44) * SS), ((cx - 58) * SS, (cy + 44) * SS)], fill=AMBER)
        f = fonts.font(62 * SS)
        d.text((cx * SS, (cy + 17) * SS), "!", font=f, fill=BLACK, anchor="mm")
    elif kind == "spin":
        d.arc([(cx - 50) * SS, (cy - 50) * SS, (cx + 50) * SS, (cy + 50) * SS], 40, 330, fill=WHITE, width=14 * SS)
        d.polygon([((cx + 56) * SS, (cy - 14) * SS), ((cx + 32) * SS, (cy - 4) * SS), ((cx + 60) * SS, (cy + 12) * SS)], fill=WHITE)
    elif kind == "flag":
        arrows.draw_icon(d, cx, cy, 110, wire.MAN_ARRIVE, 0, SS)
    else:                                                 # waiting for the phone: a small Bluetooth-style rune
        d.ellipse([(cx - 46) * SS, (cy - 46) * SS, (cx + 46) * SS, (cy + 46) * SS], outline=SOFT, width=7 * SS)
        d.line([((cx - 16) * SS, (cy - 22) * SS), ((cx + 18) * SS, (cy + 12) * SS), (cx * SS, (cy + 30) * SS), (cx * SS, (cy - 30) * SS),
                ((cx + 18) * SS, (cy - 12) * SS), ((cx - 16) * SS, (cy + 22) * SS)], fill=WHITE, width=6 * SS, joint="curve")
    panel.text("line1", "primary", (CX, 236), line1, 56)
    panel.text("line2", "secondary", (CX, 292), line2, 36, fill=SOFT, max_w=2 * row_half(292, 36) - 6, min_size=24)


def render_a(state: DeviceState, view: Optional[ViewState] = None) -> Tuple[Image.Image, Dict[str, Element], dict]:
    """Layout A: everything on one screen."""
    p = Panel()
    info: dict = {}
    c = state.core
    if not state.connected or c is None:
        _message(p, "wait", "MotoNav", "Waiting for phone")
    elif c.mode == wire.MODE_OFF_ROUTE:
        _message(p, "warn", "OFF ROUTE", "Return to the route")
    elif c.mode == wire.MODE_REROUTING:
        _message(p, "spin", "REROUTING", "Finding route")
    elif c.mode == wire.MODE_ARRIVED or c.maneuver in (wire.MAN_ARRIVE, wire.MAN_ARRIVE_LEFT, wire.MAN_ARRIVE_RIGHT) and c.dist_m < 40:
        _message(p, "flag", "ARRIVED", state.street or "Destination")
    elif c.mode == wire.MODE_IDLE:
        _message(p, "wait", "MotoNav", "Choose a destination")
    else:
        _nav_a(p, state, view, info)
    img, meta = p.finish()
    return img, meta, info


def _nav_a(p: Panel, state: DeviceState, view: Optional[ViewState], info: dict) -> None:
    c = state.core
    val, unit = fmt_dist(c.dist_m)
    # row 1: manoeuvre arrow + distance to it
    row_y = 92
    _, d = p.layer("arrow", "primary")
    size = 84 if c.maneuver == wire.MAN_ROUNDABOUT else 80
    dist_w = 135 if unit == "m" else 160
    left = CX - (size + 16 + dist_w) / 2
    arrows.draw_icon(d, left + size / 2, row_y, size, c.maneuver, c.roundabout_exit, SS)
    p.value_unit("distance", "primary", left + size + 16 + dist_w / 2, row_y + 22, val, unit, 58, 36)
    # row 2: street
    _street(p, state, 150)
    # row 3: the mini-map
    top, bottom = 172, 314
    half = min(chord_half(top), chord_half(bottom)) - 6
    look = max(900.0, min(2500.0, c.dist_m + 500.0))      # see the next turn and what follows; no zooming in on close turns
    info.update(draw_map(p, state, (CX - half, top, CX + half, bottom), look, view))
    # row 4: distance left and minutes left
    _bottom(p, state, 337)


def render_b(state: DeviceState, view: Optional[ViewState] = None) -> Tuple[Image.Image, Dict[str, Element], dict]:
    """Layout B: layout A while cruising; a large-arrow screen (no map) when the manoeuvre is near."""
    c = state.core
    near = (state.connected and c is not None and c.mode == wire.MODE_NAV and c.dist_m <= TURN_SWITCH_M
            and c.maneuver not in (wire.MAN_NONE,))
    if not near:
        return render_a(state, view)
    p = Panel()
    val, unit = fmt_dist(c.dist_m)
    _, d = p.layer("arrow", "primary")
    arrows.draw_icon(d, CX, 98, 120 if c.maneuver == wire.MAN_ROUNDABOUT else 112, c.maneuver, c.roundabout_exit, SS)
    p.value_unit("distance", "primary", CX, 232, val, unit, 92, 52)
    _street(p, state, 286, size=44, min_size=36)
    _bottom(p, state, 338)
    img, meta = p.finish()
    return img, meta, {"large": True}


def render(state: DeviceState, layout: str = "A", view: Optional[ViewState] = None):
    return render_b(state, view) if layout.upper() == "B" else render_a(state, view)
