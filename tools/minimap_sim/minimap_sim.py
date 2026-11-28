#!/usr/bin/env python3
"""MotoNav round-display simulator (412 x 412).

    python3 minimap_sim.py all [--out DIR]        contact sheets, legibility table, sunlight preview, ride GIFs
    python3 minimap_sim.py sheet [--layout A|B]   one contact sheet
    python3 minimap_sim.py one --step 3 --dist 120 [--layout B]   one tile (step = index in the scenario)
    python3 minimap_sim.py legibility             the legibility table only
    python3 minimap_sim.py ride --layout B        the ride animation only

What is drawn is what the device would have: the phone-side window/simplify/chunk code and the firmware-side decoder are
mirrored byte for byte (see wire.py, phone.py, ride.py), then render.py draws the panel from the decoded values.
"""
from __future__ import annotations

import argparse
import os
import sys
from typing import Dict, List, Tuple

from PIL import Image, ImageDraw

import fonts
import render
import ride
import sun
import wire
from ride import DeviceState

HERE = os.path.dirname(os.path.abspath(__file__))


# ---- the scenes ------------------------------------------------------------------------------------------------------

def _nav_tile(step: int, dist_m: float) -> DeviceState:
    sc = ride.Scenario()
    st, _ = ride.state_at(ride.progress_before(sc, step, dist_m))
    return st


def _with_mode(st: DeviceState, mode: int, maneuver=None) -> DeviceState:
    c = st.core
    core = wire.Core(mode, c.maneuver if maneuver is None else maneuver, c.roundabout_exit, c.seq, c.dist_m,
                     c.remaining_m, c.eta_min, c.speed_x10)
    return DeviceState(True, core, st.street, st.window, st.pos)


def build_tiles() -> List[Tuple[str, DeviceState]]:
    sc = ride.Scenario()
    names = {i: s for i, s in enumerate(sc.steps)}
    tiles: List[Tuple[str, DeviceState]] = [
        ("Left in 300 m", _nav_tile(0, 300)),
        ("Right in 80 m (long name)", _nav_tile(1, 80)),
        ("Slight right in 650 m", _nav_tile(2, 650)),
        ("Roundabout, 2nd exit, 120 m", _nav_tile(3, 120)),
        ("Keep right in 1.8 km", _nav_tile(4, 1800)),
        ("Sharp right in 250 m", _nav_tile(5, 250)),
        ("U-turn in 40 m", _nav_tile(6, 40)),
        ("Left in 400 m, destination ahead", _nav_tile(7, 400)),
    ]
    near_end = _nav_tile(8, 15)
    tiles += [
        ("Arrived", _with_mode(near_end, wire.MODE_ARRIVED)),
        ("Rerouting", _with_mode(_nav_tile(1, 200), wire.MODE_REROUTING)),
        ("Phone not connected", DeviceState()),
    ]
    assert len(names) == 9
    return tiles


# ---- contact sheet ---------------------------------------------------------------------------------------------------

def contact_sheet(layout: str, tiles: List[Tuple[str, DeviceState]], cols: int = 4) -> Image.Image:
    pad, cap = 20, 34
    rows = (len(tiles) + cols - 1) // cols
    W = cols * render.SIZE + (cols + 1) * pad
    H = rows * (render.SIZE + cap) + (rows + 1) * pad + 40
    sheet = Image.new("RGB", (W, H), (38, 38, 38))
    d = ImageDraw.Draw(sheet)
    f_cap, f_foot = fonts.font(20), fonts.font(16)
    for i, (title, st) in enumerate(tiles):
        img, meta, _ = render.render(st, layout)
        x = pad + (i % cols) * (render.SIZE + pad)
        y = pad + (i // cols) * (render.SIZE + cap + pad)
        d.text((x + render.SIZE / 2, y + 4), f"{i + 1}. {title}", font=f_cap, fill=(235, 235, 235), anchor="mt")
        sheet.paste(img, (x, y + cap))
    note = (f"Layout {layout}  |  412 x 412 px, shown 1:1  |  synthetic route, example street names  |  "
            "drawn from the BLE bytes the phone would send")
    d.text((W / 2, H - 22), note, font=f_foot, fill=(170, 170, 170), anchor="mm")
    return sheet


# ---- legibility ------------------------------------------------------------------------------------------------------

PRIMARY_MIN_MM, SECONDARY_MIN_MM = 3.2, 2.6        # 20' and 16' of arc at 55 cm: guidance recalled, not verified


def legibility(layout: str, tiles) -> List[dict]:
    """Per element: the smallest text/icon over all tiles, with the physical size on the 1.45 in panel."""
    agg: Dict[str, dict] = {}
    for title, st in tiles:
        _, meta, info = render.render(st, layout)
        if layout == "B" and not info.get("large"):
            continue                                  # layout B looks exactly like A except on its large-arrow screen
        for name, e in meta.items():
            if e.role == "graphic" and name != "arrow":
                continue
            row = agg.setdefault(name, {"element": name, "role": e.role, "cap_px": 1e9, "font_px": 1e9, "min_edge": 1e9,
                                        "cap_px_max": 0.0, "scenes": 0, "shortened": 0, "example": ""})
            row["scenes"] += 1
            if name == "arrow":                       # an icon has no cap height: use the drawn height of the glyph
                e.cap_px, e.font_px = e.bbox[3] - e.bbox[1], e.bbox[3] - e.bbox[1]
            if e.cap_px:
                row["cap_px_max"] = max(row["cap_px_max"], e.cap_px)
                if e.cap_px < row["cap_px"]:
                    row["cap_px"], row["font_px"], row["example"] = e.cap_px, e.font_px, e.text
            row["min_edge"] = min(row["min_edge"], render.R - e.max_radius)
            if e.note:
                row["shortened"] += 1
    out = []
    for r in agg.values():
        if r["cap_px"] == 1e9:
            r["cap_px"] = 0
            r["font_px"] = 0
        r["cap_mm"] = r["cap_px"] * render.PITCH_MM
        r["cap_mm_max"] = r["cap_px_max"] * render.PITCH_MM
        out.append(r)
    return out


def legibility_markdown(rows_a, rows_b) -> str:
    import math

    def arc(mm):
        return math.degrees(2 * math.atan(mm / 2 / render.VIEW_DIST_MM)) * 60

    lines = ["| Layout | Element | Smallest text (px) | Cap height, worst case (px) | (mm) | At 55 cm | Best case (mm) | Target | Verdict |",
             "|---|---|---|---|---|---|---|---|---|"]
    for lay, rows in (("A", rows_a), ("B (turn screen)", rows_b)):
        for r in sorted(rows, key=lambda r: -r["cap_px"]):
            if not r["cap_px"]:
                continue
            tgt = {"primary": PRIMARY_MIN_MM, "secondary": SECONDARY_MIN_MM}.get(r["role"])
            verdict = "info" if tgt is None else ("OK" if r["cap_mm"] >= tgt - 0.05 else "BELOW target")
            lines.append(f"| {lay} | {r['element']} | {r['font_px']:.0f} | {r['cap_px']:.1f} | {r['cap_mm']:.2f} | "
                         f"{arc(r['cap_mm']):.1f}' | {r['cap_mm_max']:.2f} | {'' if tgt is None else f'{tgt} mm'} | {verdict} |")
    return "\n".join(lines)


# ---- ride animation --------------------------------------------------------------------------------------------------

def ride_frames(layout: str, step_m: float = 60.0) -> List[Image.Image]:
    sc = ride.Scenario()
    r = ride.Ride(sc)
    view = render.ViewState()
    frames: List[Image.Image] = []
    waiting, _, _ = render.render(DeviceState(), layout)
    frames += [waiting] * 6
    p = 0.0
    while p < sc.total - 5:
        st = r.updates(p)
        frames.append(render.render(st, layout, view)[0])
        p += step_m
    st = r.updates(sc.total - 3)
    img = render.render(st, layout, view)[0]
    frames += [img] * 8
    return frames


def save_gif(frames: List[Image.Image], path: str, ms: int = 90) -> None:
    pal = [f.convert("P", palette=Image.ADAPTIVE, colors=96) for f in frames]
    pal[0].save(path, save_all=True, append_images=pal[1:], duration=ms, loop=0, optimize=True, disposal=1)


# ---- main ------------------------------------------------------------------------------------------------------------

def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("cmd", choices=["all", "sheet", "one", "legibility", "ride", "sun"])
    ap.add_argument("--out", default=os.path.join(HERE, "samples"))
    ap.add_argument("--layout", default="A", choices=["A", "B"])
    ap.add_argument("--step", type=int, default=0)
    ap.add_argument("--dist", type=float, default=300.0)
    a = ap.parse_args(argv)
    os.makedirs(a.out, exist_ok=True)
    tiles = build_tiles()

    if a.cmd in ("all", "sheet"):
        for lay in (["A", "B"] if a.cmd == "all" else [a.layout]):
            path = os.path.join(a.out, f"sheet_{lay}.png")
            contact_sheet(lay, tiles).save(path)
            print("wrote", path)
    if a.cmd == "one":
        st = _nav_tile(a.step, a.dist)
        img, meta, info = render.render(st, a.layout)
        path = os.path.join(a.out, f"tile_{a.layout}_{a.step}_{int(a.dist)}.png")
        img.save(path)
        print("wrote", path, info)
        for v in render.outside_violations(meta):
            print("OUTSIDE:", v)
    if a.cmd in ("all", "legibility"):
        table = legibility_markdown(legibility("A", tiles), legibility("B", tiles))
        print(table)
        if a.cmd == "all":
            with open(os.path.join(a.out, "legibility.md"), "w") as fh:
                fh.write(table + "\n")
    if a.cmd in ("all", "sun"):
        path = os.path.join(a.out, "sun_preview.png")
        report = sun.preview(tiles[0][1], tiles[3][1]).save_to(path)
        print("wrote", path)
        print(report)
    if a.cmd in ("all", "ride"):
        for lay in (["A", "B"] if a.cmd == "all" else [a.layout]):
            path = os.path.join(a.out, f"ride_{lay}.gif")
            save_gif(ride_frames(lay), path)
            print("wrote", path, f"{os.path.getsize(path) / 1e6:.1f} MB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
