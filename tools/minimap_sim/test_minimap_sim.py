"""Tests for the MotoNav display simulator.  Run:  python3 -m unittest -v   (from tools/minimap_sim)

The protocol tests use the same golden bytes as the Android and firmware tests, and one test pushes the simulator's
bytes through the real firmware decoder (protocol/nav_route.h), so the simulator cannot drift from the device.
"""
import os
import shutil
import subprocess
import tempfile
import unittest

import numpy as np

import arrows
import minimap_sim
import phone
import render
import ride
import wire

HERE = os.path.dirname(os.path.abspath(__file__))
GOLDEN_POINTS = [(12.971600, 77.594600), (12.972000, 77.594600), (12.972000, 77.595100),
                 (12.972500, 77.595100), (12.972300, 77.594900)]
GOLDEN_CHUNKS = ["0700020101050050eec500e8ff9f049001000000", "07010200f401f401000038ff38ff"]
GOLDEN_POS = "072a76f0c500b000a004"


class ProtocolTest(unittest.TestCase):
    def test_golden_route_bytes(self):
        chunks = wire.chunk(wire.encode_stream(GOLDEN_POINTS, True), 7)
        self.assertEqual([c.hex() for c in chunks], GOLDEN_CHUNKS)

    def test_golden_pos_bytes(self):
        self.assertEqual(wire.encode_pos(7, 42, wire.to_e6(12.972150), wire.to_e6(77.594800)).hex(), GOLDEN_POS)

    def test_golden_decodes_back(self):
        a = wire.RouteAssembler()
        self.assertFalse(a.feed(bytes.fromhex(GOLDEN_CHUNKS[0])))
        self.assertTrue(a.feed(bytes.fromhex(GOLDEN_CHUNKS[1])))
        self.assertEqual(a.window.points_deg(), [(round(la, 6), round(lo, 6)) for la, lo in GOLDEN_POINTS])
        self.assertTrue(a.window.ends_at_destination)

    def test_core_is_18_bytes_and_roundtrips(self):
        b = wire.encode_core(wire.MODE_NAV, wire.MAN_ROUNDABOUT, 2, 513, 120, 4000, 10, 260)
        self.assertEqual(len(b), 18)
        c = wire.decode_core(b)
        self.assertEqual((c.maneuver, c.roundabout_exit, c.seq, c.dist_m, c.remaining_m, c.eta_min), (16, 2, 513, 120, 4000, 10))
        self.assertIsNone(wire.decode_core(b[:17]))
        self.assertIsNone(wire.decode_core(bytes([2]) + b[1:]))                 # wrong version
        self.assertIsNone(wire.decode_core(b[:1] + bytes([9]) + b[2:]))         # mode out of range

    def test_assembler_drops_bad_windows_and_keeps_the_old_one(self):
        good = wire.chunk(wire.encode_stream(GOLDEN_POINTS, True), 7)
        a = wire.RouteAssembler()
        for c in good:
            a.feed(c)
        before = a.window
        newer = wire.chunk(wire.encode_stream(GOLDEN_POINTS[:3], False), 8, 12)
        self.assertGreater(len(newer), 2)
        a.feed(newer[0])
        self.assertFalse(a.feed(newer[2]))                                        # a chunk went missing
        self.assertIs(a.window, before)
        self.assertFalse(a.feed(good[0][:3]))                                     # header only
        trunc = wire.chunk(wire.encode_stream(GOLDEN_POINTS, True), 9)
        a.feed(trunc[0])
        self.assertFalse(a.feed(trunc[1][:-1]))                                   # stream length != header count
        self.assertIs(a.window, before)

    def test_street_rules(self):
        self.assertEqual(wire.to_ascii_street("Residency Road Extension"), "Residency Road Exten")
        self.assertEqual(wire.to_ascii_street("Café Rd"), "Cafe Rd")
        self.assertEqual(wire.to_ascii_street("ಬೆಂಗಳೂರು ರಸ್ತೆ"), "")             # mostly non-ASCII: dropped
        self.assertEqual(wire.decode_street(b"Ab\x00cd"), "Ab")
        self.assertEqual(wire.decode_street(b"A\xffB"), "A?B")

    def test_long_step_is_split_not_clipped(self):
        pts = [(12.0, 77.0), (12.2, 77.0)]                                        # 200 000 e-6 deg: far over int16
        a = wire.RouteAssembler()
        for c in wire.chunk(wire.encode_stream(pts, False), 3):
            a.feed(c)
        self.assertGreater(a.window.count, 2)
        self.assertEqual((a.window.lat_e6[0], a.window.lat_e6[-1]), (12_000_000, 12_200_000))


class PhoneTest(unittest.TestCase):
    def test_window_is_at_most_96_points_and_matches_what_the_phone_sent(self):
        sc = ride.Scenario()
        for p in (0, 700, 2500, 4000, 6000):
            r = ride.Ride(sc)
            st = r.advance_to(p)
            self.assertLessEqual(st.window.count, wire.ROUTE_MAX_POINTS)
            sent = r.feed.last_window_points
            self.assertGreaterEqual(st.window.count, len(sent))                   # long steps may add points, never drop
            for (la, lo) in sent:                                                 # every sent point survives, within rounding
                best = min(phone.haversine_m((la, lo), q) for q in st.window.points_deg())
                self.assertLess(best, 0.2)

    def test_window_covers_behind_and_ahead(self):
        sc = ride.Scenario()
        r = ride.Ride(sc)
        st = r.advance_to(2000)
        start_m = r.feed.window_start_m
        self.assertLessEqual(start_m, 2000 - 0)                                   # starts behind the rider
        self.assertGreaterEqual(start_m, 0)
        end = phone.Route(st.window.points_deg()).total
        self.assertGreater(end, 200)

    def test_window_resent_after_a_kilometre_with_a_new_id(self):
        r = ride.Ride()
        first = r.advance_to(100).window.route_id
        later = r.advance_to(1500).window.route_id
        self.assertNotEqual(first, later)
        self.assertEqual(r.dev.state.pos.route_id, later)

    def test_destination_flag_only_when_the_window_reaches_the_end(self):
        sc = ride.Scenario()
        self.assertFalse(ride.state_at(500)[0].window.ends_at_destination)
        self.assertTrue(ride.state_at(sc.total - 300)[0].window.ends_at_destination)

    def test_ids_skip_zero(self):
        f = phone.MapFeed(ride.Scenario().route)
        f.next_id = 255
        ch, _ = f.tick(10, *f.route.point_at(10))
        self.assertEqual(ch[0][0], 255)
        f.need_window = True
        ch, _ = f.tick(10, *f.route.point_at(10))
        self.assertEqual(ch[0][0], 1)

    def test_bandwidth_is_small(self):
        r = ride.Ride()
        r.advance_to(ride.Scenario().total - 5, 50)
        per_second = r.bytes_sent / (r.progress / (26 / 3.6))
        self.assertLess(per_second, 400)                                          # bytes per second at 26 km/h


class DeviceCrossCheck(unittest.TestCase):
    """The simulator's bytes through the real firmware decoder (compiled from protocol/nav_route.h)."""

    @unittest.skipUnless(shutil.which("g++"), "g++ not installed")
    def test_real_firmware_decoder_agrees(self):
        with tempfile.TemporaryDirectory() as tmp:
            exe = os.path.join(tmp, "device_decode")
            subprocess.run(["g++", "-std=c++17", "-Wall", "-Wextra", "-I", os.path.join(HERE, "..", "..", "protocol"),
                            os.path.join(HERE, "device_decode.cpp"), "-o", exe], check=True)
            r = ride.Ride()
            r.advance_to(4200, 25)
            feed = "".join(f"{k} {b.hex()}\n" for k, b in r.log)
            out = subprocess.run([exe], input=feed, capture_output=True, text=True, check=True).stdout.splitlines()
        windows = [l.split() for l in out if l.startswith("W ")]
        poses = [l.split() for l in out if l.startswith("P ")]
        self.assertEqual(len(windows), len(r.dev.windows))
        self.assertGreaterEqual(len(windows), 3)
        for w, mine in zip(windows, r.dev.windows):
            self.assertEqual(int(w[1]), mine.route_id)
            self.assertEqual(int(w[2]), int(mine.ends_at_destination))
            self.assertEqual(int(w[3]), mine.count)
            vals = list(map(int, w[4:]))
            self.assertEqual(vals[0::2], mine.lat_e6)
            self.assertEqual(vals[1::2], mine.lon_e6)
        p = r.dev.state.pos
        self.assertEqual(list(map(int, poses[-1][1:])), [p.route_id, p.seq, p.lat_e6, p.lon_e6])
        self.assertEqual(len(poses), sum(1 for k, _ in r.log if k == "P"))


class RenderTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tiles = minimap_sim.build_tiles()

    def test_every_element_of_every_screen_is_inside_the_circle(self):
        for layout in "AB":
            for title, st in self.tiles:
                _, meta, _ = render.render(st, layout)
                self.assertEqual(render.outside_violations(meta), [], f"layout {layout}: {title}")

    def test_circle_check_catches_an_element_that_is_too_close_to_the_edge(self):
        p = render.Panel()
        p.text("wide", "primary", (render.CX, 30), "WWWWWWWWWWWWWWWW", 60)
        _, meta = p.finish()
        self.assertTrue(render.outside_violations(meta))

    def test_image_is_412_square_with_a_black_inside_and_dark_outside(self):
        img, _, _ = render.render(self.tiles[0][1], "A")
        self.assertEqual(img.size, (412, 412))
        self.assertEqual(img.getpixel((2, 2)), render.OUTSIDE)
        self.assertEqual(img.getpixel((206, 20)), (0, 0, 0))

    def test_every_maneuver_has_an_icon_and_no_two_look_alike(self):
        from PIL import Image, ImageDraw
        seen = {}
        for m in range(1, 21):
            for ex in ((1, 2, 3, 4) if m == wire.MAN_ROUNDABOUT else (0,)):
                im = Image.new("RGBA", (240, 240), (0, 0, 0, 255))
                size = arrows.draw_icon(ImageDraw.Draw(im), 60, 60, 90, m, ex, 2, color=(255, 255, 255, 255))
                self.assertIsNotNone(size, f"maneuver {m} draws nothing")
                g = np.asarray(im.convert("L"))
                self.assertGreater(g.sum(), 5000)
                seen[(m, ex)] = g.tobytes()
        # these pairs are intentionally the same picture
        same = {(wire.MAN_DEPART,), (wire.MAN_STRAIGHT,), (wire.MAN_ARRIVE,), (wire.MAN_ARRIVE_LEFT,), (wire.MAN_ARRIVE_RIGHT,),
                (wire.MAN_RAMP_LEFT,), (wire.MAN_KEEP_LEFT,), (wire.MAN_RAMP_RIGHT,), (wire.MAN_KEEP_RIGHT,)}
        distinct = {}
        for (m, ex), b in seen.items():
            distinct.setdefault(b, []).append((m, ex))
        for group in distinct.values():
            if len(group) > 1:
                self.assertTrue(all((g[0],) in same for g in group), f"unexpected look-alikes: {group}")
        self.assertIsNone(arrows.draw_icon(ImageDraw.Draw(Image.new("RGBA", (9, 9))), 1, 1, 5, wire.MAN_NONE))

    def test_left_and_right_icons_mirror_each_other(self):
        from PIL import Image, ImageDraw
        def pic(m):
            im = Image.new("RGBA", (200, 200), (0, 0, 0, 255))
            arrows.draw_icon(ImageDraw.Draw(im), 100, 100, 150, m, 0, 1, color=(255, 255, 255, 255))
            return np.asarray(im.convert("L"))
        for l, r in ((wire.MAN_LEFT, wire.MAN_RIGHT), (wire.MAN_SLIGHT_LEFT, wire.MAN_SLIGHT_RIGHT),
                     (wire.MAN_SHARP_LEFT, wire.MAN_SHARP_RIGHT), (wire.MAN_UTURN_LEFT, wire.MAN_UTURN_RIGHT)):
            a, b = pic(l).astype(int), pic(r)[:, ::-1].astype(int)
            self.assertLess(np.abs(a - b).sum(), 0.06 * a.sum(), f"{l} vs {r}")      # a 1 px shift of the mirror is allowed

    def test_map_draws_the_route_and_the_rider(self):
        img, meta, info = render.render(self.tiles[0][1], "A")
        a = np.asarray(img).astype(int)
        amber = ((abs(a[:, :, 0] - 255) < 6) & (abs(a[:, :, 1] - 176) < 6) & (a[:, :, 2] < 6)).sum()
        white_in_map = (a[175:310, 50:360].min(axis=2) > 245).sum()
        self.assertGreater(amber, 800)                                              # the route line
        self.assertGreater(white_in_map, 60)                                        # the rider marker + scale bar
        self.assertEqual(info["scale_m"], 200)

    def test_map_is_north_up_east_is_right(self):
        """A route running east must be drawn to the right of the rider (no heading-up rotation)."""
        pts = [(12.9716, 77.5946), (12.9716, 77.5946 + 0.01)]
        pos = wire.Pos(1, 0, wire.to_e6(pts[0][0]), wire.to_e6(pts[0][1]))
        win = wire.RouteWindow(1, False, [wire.to_e6(p[0]) for p in pts], [wire.to_e6(p[1]) for p in pts])
        st = ride.DeviceState(True, wire.decode_core(wire.encode_core(1, wire.MAN_STRAIGHT, 0, 0, 900, 5000, 9, 0)), "X", win, pos)
        img, _, _ = render.render(st, "A")
        a = np.asarray(img).astype(int)
        amber = (abs(a[:, :, 0] - 255) < 6) & (abs(a[:, :, 1] - 176) < 6) & (a[:, :, 2] < 6)
        ys, xs = np.nonzero(amber)
        self.assertGreater(xs.max() - xs.min(), 100)
        self.assertLess(ys.max() - ys.min(), 20)                                     # a horizontal line
        white = (a[175:312, 45:370].min(axis=2) > 245)
        wy, wx = np.nonzero(white[:, :])
        self.assertLess(wx.min() + 45, xs.min() + 30)                                # rider at the west end of the line

    def test_no_route_yet_shows_the_rider_only(self):
        st = self.tiles[0][1]
        st = ride.DeviceState(True, st.core, st.street, None, st.pos)
        img, meta, info = render.render(st, "A")
        self.assertIsNone(info["mpp"])
        self.assertEqual(render.outside_violations(meta), [])

    def test_a_position_from_another_window_is_not_drawn_on_this_route(self):
        st = self.tiles[0][1]
        stale = wire.Pos((st.window.route_id % 255) + 1, 0, st.pos.lat_e6, st.pos.lon_e6)
        _, _, info = render.render(ride.DeviceState(True, st.core, st.street, st.window, stale), "A")
        self.assertIsNone(info["mpp"])

    def test_layout_b_switches_to_the_large_screen_at_300_m(self):
        sc = ride.Scenario()
        far = ride.state_at(ride.progress_before(sc, 0, 600))[0]
        near = ride.state_at(ride.progress_before(sc, 0, 250))[0]
        self.assertNotIn("large", render.render(far, "B")[2])
        self.assertTrue(render.render(near, "B")[2].get("large"))
        self.assertNotIn("large", render.render(near, "A")[2])

    def test_distance_formats(self):
        f = render.fmt_dist
        self.assertEqual(f(8), ("10", "m"))
        self.assertEqual(f(84), ("80", "m"))
        self.assertEqual(f(120), ("100", "m"))
        self.assertEqual(f(999), ("1000", "m"))                                      # 999 rounds to 1000 m, not "1.0 km" glitch
        self.assertEqual(f(1840), ("1.8", "km"))
        self.assertEqual(f(12400), ("12", "km"))
        self.assertEqual((f(85), f(125), f(1250), f(12500), f(9949), f(9950)),               # halves round up
                         (("90", "m"), ("150", "m"), ("1.3", "km"), ("13", "km"), ("9.9", "km"), ("10", "km")))
        self.assertEqual((f(999_499), f(999_500), f(4_294_967_295)), (("999", "km"), ("999+", "km"), ("999+", "km")))
        self.assertEqual(render.fmt_eta(8), "8 min")
        self.assertEqual((render.fmt_eta(5999), render.fmt_eta(6000), render.fmt_eta(65535)), ("99 h 59 m", "99+ h", "99+ h"))
        self.assertEqual(render.fmt_eta(72), "1 h 12 m")

    def test_primary_elements_meet_the_size_target(self):
        for layout in "AB":
            for row in minimap_sim.legibility(layout, self.tiles):
                if row["role"] == "primary":
                    self.assertGreaterEqual(row["cap_mm"], minimap_sim.PRIMARY_MIN_MM - 0.05, f"{layout} {row['element']}")

    def test_long_street_names_shrink_then_are_cut_never_overflowing(self):
        st = self.tiles[1][1]                                                        # 'Residency Road Exten' (20 chars)
        _, meta, _ = render.render(st, "A")
        self.assertLess(meta["street"].font_px, 40)                                  # shrank to fit
        self.assertEqual(render.outside_violations(meta), [])
        worst = ride.DeviceState(True, st.core, "W" * 20, st.window, st.pos)         # widest possible 20 characters
        _, meta, _ = render.render(worst, "A")
        self.assertTrue(meta["street"].text.endswith("…"))
        self.assertIn("shortened", meta["street"].note)
        self.assertEqual(render.outside_violations(meta), [])

    def test_zoom_does_not_flicker_while_the_route_still_fits(self):
        r = ride.Ride()
        view = render.ViewState()
        zooms = []
        for p in range(0, 900, 30):
            st = r.updates(float(p))
            zooms.append(render.render(st, "A", view)[2].get("mpp"))
        changes = sum(1 for a, b in zip(zooms, zooms[1:]) if a != b)
        self.assertLessEqual(changes, 3)


class RandomStateTest(unittest.TestCase):
    def test_random_states_never_draw_in_the_edge_margin(self):
        """Hostile values: absurd distances, 20 wide characters, descenders, every manoeuvre code, odd windows."""
        import random
        rng = random.Random(7)
        base = ride.state_at(2000)[0]
        chars = "WMwmiIl.,'-ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789 /()&gjpqy"
        for n in range(60):
            street = "".join(rng.choice(chars) for _ in range(rng.choice((0, 5, 12, 20))))
            if n % 5 == 0:
                street = ")" + "W" * 18 + "y"
            core = wire.Core(rng.choice((0, 1, 1, 1, 2, 3, 4)), rng.randrange(0, 24), rng.randrange(0, 14), n,
                             int(10 ** rng.uniform(0, 9.6)) if n % 2 else rng.randrange(0, 2000),
                             int(10 ** rng.uniform(0, 9.6)), rng.choice((0, 7, 59, 61, 5999, 6000, 65535)), 100)
            st = ride.DeviceState(True, core, wire.decode_street(street.encode()), base.window, base.pos)
            for layout in "AB":
                _, meta, _ = render.render(st, layout)
                self.assertEqual(render.outside_violations(meta), [], f"{layout} {street!r} {core}")


class ScenarioTest(unittest.TestCase):
    def test_route_is_a_few_km_with_every_maneuver_kind(self):
        sc = ride.Scenario()
        self.assertGreater(sc.total, 6000)
        kinds = {s.maneuver for s in sc.steps}
        for m in (wire.MAN_LEFT, wire.MAN_RIGHT, wire.MAN_SLIGHT_RIGHT, wire.MAN_ROUNDABOUT, wire.MAN_KEEP_RIGHT,
                  wire.MAN_SHARP_RIGHT, wire.MAN_UTURN_RIGHT, wire.MAN_ARRIVE_RIGHT):
            self.assertIn(m, kinds)
        starts = [s.start_m for s in sc.steps]
        self.assertEqual(starts, sorted(starts))

    def test_turns_go_the_way_the_icon_says(self):
        """A LEFT step must bend the route anticlockwise (north-up), a RIGHT step clockwise."""
        sc = ride.Scenario()
        for step in sc.steps:
            if step.maneuver not in (wire.MAN_LEFT, wire.MAN_RIGHT):
                continue
            a = sc.route.point_at(step.start_m - 40)
            b = sc.route.point_at(step.start_m + 5)
            c = sc.route.point_at(step.start_m + 60)
            kx = np.cos(np.radians(a[0]))
            cross = ((b[1] - a[1]) * kx) * (c[0] - b[0]) - (b[0] - a[0]) * ((c[1] - b[1]) * kx)
            self.assertEqual(cross > 0, step.maneuver == wire.MAN_LEFT, step.street)

    def test_roundabout_in_left_hand_traffic_is_clockwise(self):
        t = ride._Turtle()
        t.roundabout(1)                                                              # first exit = a left turn
        self.assertAlmostEqual(t.h, 270.0, delta=1.0)                                # now heading west
        t = ride._Turtle()
        t.roundabout(3)
        self.assertAlmostEqual(t.h, 90.0, delta=1.0)                                 # now heading east


if __name__ == "__main__":
    unittest.main()
