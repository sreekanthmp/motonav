"""The firmware renderer (firmware/product-esp32s3/lib/mnui, compiled for the PC) against this simulator.

Both draw the same 12 situations from the same raw BLE bytes. They use different rasterisers (the simulator supersamples
4x and filters; the firmware anti-aliases with 8 sub-scanlines), so the images are not identical; these tests bound how
different they may be, and check that the structure agrees (layout chosen, zoom, same text, nothing outside the circle).

Run:  python3 -m unittest test_firmware_parity -v     (needs g++; skipped otherwise)
"""
import os
import shutil
import subprocess
import tempfile
import unittest

import numpy as np
from PIL import Image

import minimap_sim
import render
import ride
import wire

HERE = os.path.dirname(os.path.abspath(__file__))
FW = os.path.normpath(os.path.join(HERE, "..", "..", "firmware", "product-esp32s3"))
SOURCES = ["mn_raster.cpp", "mn_font.cpp", "mn_font_data.cpp", "mn_icons.cpp", "mn_screens.cpp"]


def scene_text(name: str, st: ride.DeviceState, keep: bool = False) -> str:
    lines = [f"BEGIN {name}", f"CONNECTED {1 if st.connected else 0}"]
    if keep:
        lines.append("KEEP")
    if st.core:
        c = st.core
        lines.append("C " + wire.encode_core(c.mode, c.maneuver, c.roundabout_exit, c.seq, c.dist_m, c.remaining_m,
                                              c.eta_min, c.speed_x10).hex())
    if st.street:
        lines.append("S " + st.street)
    if st.window:
        w = st.window
        for ch in wire.chunk(wire.encode_stream(w.points_deg(), w.ends_at_destination), w.route_id):
            lines.append("R " + ch.hex())
    if st.pos:
        p = st.pos
        lines.append("P " + wire.encode_pos(p.route_id, p.seq, p.lat_e6, p.lon_e6).hex())
    lines.append("END")
    return "\n".join(lines) + "\n"


def read_ppm(path: str) -> np.ndarray:
    with open(path, "rb") as f:
        assert f.readline().strip() == b"P6"
        w, h = map(int, f.readline().split())
        f.readline()
        return np.frombuffer(f.read(), dtype=np.uint8).reshape(h, w, 3)


def to565(a: np.ndarray) -> np.ndarray:
    """Quantise like the panel's RGB565 so the comparison is fair."""
    r, g, b = (a[..., 0] >> 3), (a[..., 1] >> 2), (a[..., 2] >> 3)
    return np.stack([(r << 3) | (r >> 2), (g << 2) | (g >> 4), (b << 3) | (b >> 2)], axis=-1).astype(np.uint8)


YY, XX = np.mgrid[0:412, 0:412]
INSIDE = np.hypot(XX + 0.5 - 206, YY + 0.5 - 206) <= 206 - 1


def build(tmp: str) -> str:
    exe = os.path.join(tmp, "render_host")
    cmd = ["g++", "-std=c++17", "-O2", "-Wall", "-Wextra", "-Werror", "-I", os.path.join(FW, "lib", "mnui"),
           "-I", os.path.join(HERE, "..", "..", "protocol"), os.path.join(FW, "test_host", "render_host.cpp")]
    cmd += [os.path.join(FW, "lib", "mnui", s) for s in SOURCES] + ["-o", exe]
    subprocess.run(cmd, check=True)
    return exe


@unittest.skipUnless(shutil.which("g++") and os.path.isdir(FW), "g++ or the firmware project is missing")
class FirmwareParity(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        exe = build(cls.tmp.name)
        cls.tiles = minimap_sim.build_tiles()
        text = "".join(scene_text(f"t{i}", st) for i, (_, st) in enumerate(cls.tiles))
        out = subprocess.run([exe, cls.tmp.name], input=text, capture_output=True, text=True, check=True).stdout
        cls.log = {l.split()[0]: l for l in out.splitlines()}
        cls.fw = [read_ppm(os.path.join(cls.tmp.name, f"t{i}.ppm")) for i in range(len(cls.tiles))]
        cls.sim = []
        cls.info = []
        for _, st in cls.tiles:
            img, _, info = render.render(st, "B")
            cls.sim.append(np.asarray(img))
            cls.info.append(info)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def test_pictures_are_close(self):
        for i, (title, _) in enumerate(self.tiles):
            a = to565(self.sim[i]).astype(int)
            b = self.fw[i].astype(int)
            d = np.abs(a - b).max(axis=2)
            inside = INSIDE
            mean = d[inside].mean()
            bad = (d[inside] > 90).mean()
            self.assertLess(mean, 3.0, f"{title}: mean difference {mean:.2f}")
            self.assertLess(bad, 0.012, f"{title}: {bad * 100:.2f} % of pixels differ a lot")

    def test_outside_the_circle_is_black_in_the_firmware_image(self):
        for i, (title, _) in enumerate(self.tiles):
            ring = (np.hypot(XX + 0.5 - 206, YY + 0.5 - 206) > 206 - 12 + 1) & INSIDE
            self.assertEqual(int(self.fw[i][ring].max()), 0, f"{title}: something is drawn in the 12 px edge band")

    def test_same_layout_choice_and_map_zoom(self):
        for i, (title, st) in enumerate(self.tiles):
            line = self.log[f"t{i}"]
            self.assertEqual(f"large={1 if self.info[i].get('large') else 0}" in line, True, f"{title}: {line}")
            if self.info[i].get("mpp"):
                self.assertIn(f"mpp={self.info[i]['mpp']:.1f}", line, title)
            else:
                self.assertIn("mpp=0.0", line, title)

    def test_the_display_list_fits_its_pools(self):
        for line in self.log.values():
            self.assertNotIn("OVERFLOW", line)
            pts = int(line.split("pts=")[1].split()[0])
            self.assertLess(pts, 8000, line)                                    # of 9000

    def test_bad_input_never_crashes_the_renderer(self):
        """Garbage cores, a window with a stale id, no position: must still draw something and not overflow."""
        exe = os.path.join(self.tmp.name, "render_host")
        scenes = [
            "BEGIN g1\nCONNECTED 1\nC ffffffffffffffffffffffffffffffffffff\nEND\n",
            "BEGIN g2\nCONNECTED 1\nC 01010400000000002c0100003c00000008000000\nS \x01\x02weird\nEND\n",
            "BEGIN g3\nCONNECTED 0\nEND\n",
            "BEGIN g4\nCONNECTED 1\nC 01010700010100000000000000000000000000\nS " + "W" * 40 + "\nEND\n",
        ]
        out = subprocess.run([exe, self.tmp.name], input="".join(scenes), capture_output=True, text=True)
        self.assertEqual(out.returncode, 0, out.stderr)
        self.assertEqual(out.stdout.count("ops="), 4)

    def test_text_widths_match_the_simulator_font(self):
        import fonts
        exe = os.path.join(self.tmp.name, "render_host")
        cases = [(40, "Brigade Road"), (36, "Return to the route"), (92, "300"), (58, "12.4"), (34, "6.3 km"), (44, "Silk Board Junction"),
                 (56, "REROUTING"), (62, "!"), (26, "500 m"), (40, "Residency Road Exten"), (34, "AVATAR To"), (40, "Ye, Wo.")]
        out = subprocess.run([exe, self.tmp.name], input="".join(f"W {s} {t}\n" for s, t in cases), capture_output=True, text=True,
                             check=True).stdout.split()
        widths = [float(x) for x in out if x != "W"]
        for (size, text), w in zip(cases, widths):
            ref = fonts.font(size * 4).getlength(text) / 4
            self.assertAlmostEqual(w, ref, delta=max(1.5, 0.012 * ref), msg=f"{text!r} at {size}: firmware {w:.1f}, simulator {ref:.1f}")

    def test_ride_with_kept_zoom_matches_the_simulator_zoom_sequence(self):
        r = ride.Ride()
        view = render.ViewState()
        scenes, want = [], []
        for k, p in enumerate(range(0, 1200, 60)):
            st = r.updates(float(p))
            want.append(render.render(st, "B", view)[2])
            scenes.append(scene_text(f"r{k}", st, keep=k > 0))
        exe = os.path.join(self.tmp.name, "render_host")
        out = subprocess.run([exe, self.tmp.name], input="".join(scenes), capture_output=True, text=True, check=True).stdout.splitlines()
        for k, (line, info) in enumerate(zip(out, want)):
            self.assertEqual("large=1" in line, bool(info.get("large")), line)
            if info.get("mpp"):
                self.assertIn(f"mpp={info['mpp']:.1f}", line, f"frame {k}: {line}")


@unittest.skipUnless(shutil.which("g++") and os.path.isdir(FW), "g++ or the firmware project is missing")
class FirmwareCppTests(unittest.TestCase):
    def test_renderer_unit_and_random_screen_tests_with_sanitizers(self):
        with tempfile.TemporaryDirectory() as tmp:
            exe = os.path.join(tmp, "test_mnui")
            sources = [os.path.join(FW, "lib", "mnui", s) for s in SOURCES]
            subprocess.run(["g++", "-std=c++17", "-g", "-O1", "-Wall", "-Wextra", "-Werror", "-fsanitize=address,undefined",
                            "-fno-sanitize-recover=undefined", "-I", os.path.join(FW, "lib", "mnui"),
                            "-I", os.path.join(HERE, "..", "..", "protocol"), os.path.join(FW, "test_host", "test_mnui.cpp")]
                           + sources + ["-o", exe], check=True)
            for seed in ("1", "99"):
                r = subprocess.run([exe, seed], capture_output=True, text=True)
                self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
                self.assertIn(" 0 failures", r.stdout)


if __name__ == "__main__":
    unittest.main()
