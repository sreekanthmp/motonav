"""A rough sunlight preview. ILLUSTRATIVE ONLY: it is a simple additive-reflection model, not a measurement.

What the eye gets from a pixel is  L = panel_nits * (v/255)^2.2  +  E * R / pi
where E is the light falling on the panel (lux), R the fraction of it the front surface reflects, and E*R/pi is the
reflected glare (nits) that is added to every pixel, including black ones. The preview divides by the scene's own white
so a white pixel stays white and the glare shows up as a lifted black level, i.e. lost contrast.

Assumptions you should replace with measurements of the real stack-up:
  * R = 1.5 % for an optically bonded panel with an anti-reflection coating, 4 % for plain glass with an air gap
  * E = 10,000 lux (open shade) and 100,000 lux (direct sun)
  * gamma 2.2
"""
from __future__ import annotations

import math
from dataclasses import dataclass
from typing import List, Tuple

import numpy as np
from PIL import Image, ImageDraw

import fonts
import render

CONDITIONS = [("Open shade 10 klux, bonded + AR (R 1.5 %)", 10_000, 0.015, "shade, bonded"),
              ("Direct sun 100 klux, bonded + AR (R 1.5 %)", 100_000, 0.015, "sun, bonded"),
              ("Direct sun 100 klux, air gap (R 4 %)", 100_000, 0.04, "sun, air gap")]
PANELS = [450, 800]


def glare_nits(lux: float, refl: float) -> float:
    return lux * refl / math.pi


def apply(img: Image.Image, nits: float, glare: float) -> Image.Image:
    a = np.asarray(img.convert("RGB"), dtype=np.float64) / 255.0
    lin = a ** 2.2
    out = (nits * lin + glare) / (nits + glare)
    res = (np.clip(out, 0, 1) ** (1 / 2.2) * 255.0 + 0.5).astype(np.uint8)
    inside = _inside_mask(img.size[0])
    res[~inside] = np.asarray(img.convert("RGB"))[~inside]
    return Image.fromarray(res, "RGB")


def _inside_mask(n: int) -> np.ndarray:
    yy, xx = np.mgrid[0:n, 0:n]
    return np.hypot(xx + 0.5 - n / 2, yy + 0.5 - n / 2) <= n / 2 - 0.5


def luminance(rgb: Tuple[int, int, int]) -> float:
    r, g, b = [(c / 255.0) ** 2.2 for c in rgb]
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


@dataclass
class Preview:
    image: Image.Image
    report: str

    def save_to(self, path: str) -> str:
        self.image.save(path)
        return self.report


def preview(state_a, state_b) -> Preview:
    imgs = [render.render(state_a, "A")[0], render.render(state_b, "B")[0]]
    pad, cap = 20, 34
    cols = len(imgs) * len(PANELS)
    W = cols * render.SIZE + (cols + 1) * pad
    H = len(CONDITIONS) * (render.SIZE + cap + pad) + pad + 46
    sheet = Image.new("RGB", (W, H), (38, 38, 38))
    d = ImageDraw.Draw(sheet)
    f_cap, f_foot = fonts.font(20), fonts.font(15)
    lines: List[str] = [f"{'condition':<46} {'panel':>6} {'glare':>7} {'white:black':>12} {'amber:black':>12}"]
    amber_y, white_y = luminance((255, 176, 0)), 1.0
    for row, (label, lux, refl, short) in enumerate(CONDITIONS):
        g = glare_nits(lux, refl)
        for k in range(cols):
            img, lay, nits = imgs[k // len(PANELS)], "AB"[k // len(PANELS)], PANELS[k % len(PANELS)]
            x = pad + k * (render.SIZE + pad)
            y = pad + row * (render.SIZE + cap + pad)
            d.text((x + render.SIZE / 2, y + 4), f"{short}  |  {nits} nit  |  layout {lay}",
                   font=f_cap, fill=(235, 235, 235), anchor="mt")
            sheet.paste(apply(img, nits, g), (x, y + cap))
        for nits in PANELS:
            lines.append(f"{label:<46} {nits:>6} {g:>6.0f} {(nits + g) / g:>11.2f}:1 {(nits * amber_y + g) / g:>11.2f}:1")
    d.text((W / 2, H - 24), "Illustrative model: reflected glare E*R/pi added to every pixel; not a measurement. "
                            "450 nit = a typical bright panel, for comparison.", font=f_foot, fill=(170, 170, 170), anchor="mm")
    return Preview(sheet, "\n".join(lines))
