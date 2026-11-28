#!/usr/bin/env python3
"""Contact sheet drawn by the FIRMWARE renderer (firmware/product-esp32s3/lib/mnui, compiled for the PC): the 11 situations of
sheet_B.png, but every pixel comes from the code that will run on the ESP32-S3.

    python3 firmware_sheet.py [OUT.png]
"""
import os
import subprocess
import sys
import tempfile

import numpy as np
from PIL import Image, ImageDraw

import fonts
import minimap_sim
import test_firmware_parity as tfp

HERE = os.path.dirname(os.path.abspath(__file__))


def main() -> None:
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(tfp.FW, "samples", "sheet_firmware_B.png")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    tiles = minimap_sim.build_tiles()
    with tempfile.TemporaryDirectory() as tmp:
        exe = tfp.build(tmp)
        text = "".join(tfp.scene_text(f"t{i}", st) for i, (_, st) in enumerate(tiles))
        subprocess.run([exe, tmp], input=text, capture_output=True, text=True, check=True)
        imgs = [tfp.read_ppm(os.path.join(tmp, f"t{i}.ppm")) for i in range(len(tiles))]
    cols, pad, cap = 4, 20, 34
    rows = (len(tiles) + cols - 1) // cols
    W, H = cols * 412 + (cols + 1) * pad, rows * (412 + cap) + (rows + 1) * pad + 40
    sheet = Image.new("RGB", (W, H), (38, 38, 38))
    d = ImageDraw.Draw(sheet)
    inside = tfp.INSIDE
    for i, ((title, _), a) in enumerate(zip(tiles, imgs)):
        a = a.copy()
        a[~inside] = (22, 22, 22)                                # the panel is round: show what is outside as the case
        x, y = pad + (i % cols) * (412 + pad), pad + (i // cols) * (412 + cap + pad)
        d.text((x + 206, y + 4), f"{i + 1}. {title}", font=fonts.font(20), fill=(235, 235, 235), anchor="mt")
        sheet.paste(Image.fromarray(a), (x, y + cap))
    d.text((W / 2, H - 22), "Drawn by the firmware renderer (lib/mnui), RGB565, 412 x 412 shown 1:1, layout B  |  synthetic route",
           font=fonts.font(16), fill=(170, 170, 170), anchor="mm")
    sheet.save(out)
    print("wrote", out)


if __name__ == "__main__":
    main()
