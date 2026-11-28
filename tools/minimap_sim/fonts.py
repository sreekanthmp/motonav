"""Font lookup. Liberation Sans Bold (metric-compatible with Arial Bold) is used for everything on the panel:
bold sans-serif is what automotive clusters use because thin strokes vanish in sunlight. The firmware will use a
bitmap/vector font of its own; this only needs the right proportions. Set MINIMAP_FONT to use another .ttf."""
from __future__ import annotations

import os
from functools import lru_cache

from PIL import ImageFont

_CANDIDATES = [
    os.environ.get("MINIMAP_FONT", ""),
    "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf",
    "/usr/share/fonts/liberation/LiberationSans-Bold.ttf",
    "/usr/share/fonts/truetype/liberation2/LiberationSans-Bold.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
    "/Library/Fonts/Arial Bold.ttf",
    "/System/Library/Fonts/Supplemental/Arial Bold.ttf",
    "C:\\Windows\\Fonts\\arialbd.ttf",
]


def font_path() -> str:
    for p in _CANDIDATES:
        if p and os.path.exists(p):
            return p
    raise FileNotFoundError("No bold sans-serif font found; set MINIMAP_FONT to a .ttf file")


@lru_cache(maxsize=256)
def font(size_px: int) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(font_path(), max(1, int(round(size_px))))
