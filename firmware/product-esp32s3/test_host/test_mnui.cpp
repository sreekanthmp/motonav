// Unit and property tests for the panel renderer (lib/mnui). No ESP32 needed:
//   g++ -std=c++17 -Wall -Wextra -fsanitize=address,undefined -I lib/mnui -I ../../protocol test_host/test_mnui.cpp lib/mnui/mn_*.cpp -o test_mnui && ./test_mnui
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <vector>

#include "mnui.h"

static int g_checks = 0, g_failures = 0;
#define CHECK(cond)                                                       \
  do {                                                                    \
    g_checks++;                                                           \
    if (!(cond)) {                                                        \
      g_failures++;                                                       \
      printf("FAIL line %d: %s\n", __LINE__, #cond);                      \
    }                                                                     \
  } while (0)

using namespace mnui;

static std::vector<uint16_t> renderAll(const DisplayList &dl) {
  std::vector<uint16_t> img(kSize * kSize);
  static uint16_t band[kSize * kBandRows];
  for (int y = 0; y < kSize; y += kBandRows) {
    int rows = kSize - y < kBandRows ? kSize - y : kBandRows;
    dl.renderBand(y, rows, band);
    memcpy(&img[y * kSize], band, rows * kSize * 2);
  }
  return img;
}
static int level(uint16_t p) {                           // green channel (6 bit) as 0..255
  int g = (p >> 5) & 63;
  return (g << 2) | (g >> 4);
}
static double totalCoverage(const std::vector<uint16_t> &img) {
  double s = 0;
  for (uint16_t p : img) s += level(p) / 255.0;
  return s;
}

static void testShapes() {
  DisplayList dl;
  dl.beginOp(rgb(255, 255, 255));
  dl.circle(206, 206, 100, true, 64);
  dl.endOp();
  double area = totalCoverage(renderAll(dl)), exact = 3.14159265 * 100 * 100;
  CHECK(fabs(area - exact) / exact < 0.004);             // anti-aliased area matches (64-gon is 0.16 % smaller)

  dl.clear();
  dl.beginOp(rgb(255, 255, 255));
  float r[8] = {10.5f, 10, 20, 10, 20, 20, 10.5f, 20};
  dl.polygon(r, 4);
  dl.endOp();
  auto img = renderAll(dl);
  CHECK(abs(level(img[15 * kSize + 10]) - 128) <= 4);    // pixel half covered
  CHECK(level(img[15 * kSize + 11]) == 255);             // fully covered
  CHECK(level(img[15 * kSize + 9]) == 0);                // outside
  CHECK(level(img[9 * kSize + 15]) == 0 && level(img[20 * kSize + 15]) == 0);

  // a horizontal edge at half a pixel (needs vertical sub-sampling), and a diagonal edge
  dl.clear();
  dl.beginOp(rgb(255, 255, 255));
  float hr[8] = {10, 10.5f, 20, 10.5f, 20, 20, 10, 20};
  dl.polygon(hr, 4);
  dl.endOp();
  img = renderAll(dl);
  CHECK(abs(level(img[10 * kSize + 15]) - 128) <= 4 && level(img[11 * kSize + 15]) == 255 && level(img[9 * kSize + 15]) == 0);
  dl.clear();
  dl.beginOp(rgb(255, 255, 255));
  float tri[6] = {50, 50, 250, 50, 50, 250};             // right triangle, legs of 200: area 20 000
  dl.polygon(tri, 3);
  dl.endOp();
  CHECK(fabs(totalCoverage(renderAll(dl)) - 20000.0) < 40.0);

  // two overlapping boxes are one shape (union), not double coverage
  dl.clear();
  dl.beginOp(rgb(255, 255, 255));
  float a[8] = {10, 10, 30, 10, 30, 30, 10, 30}, b[8] = {20, 20, 40, 20, 40, 40, 20, 40};
  dl.polygon(a, 4);
  dl.polygon(b, 4);
  dl.endOp();
  double u = totalCoverage(renderAll(dl));
  CHECK(fabs(u - (400 + 400 - 100)) < 1.0);

  // a ring: hole stays black
  dl.clear();
  dl.beginOp(rgb(255, 255, 255));
  dl.circle(100, 100, 40, true);
  dl.circle(100, 100, 30, false);
  dl.endOp();
  img = renderAll(dl);
  CHECK(level(img[100 * kSize + 100]) == 0);
  CHECK(level(img[100 * kSize + 135]) == 255);
  CHECK(fabs(totalCoverage(img) - 3.14159 * (1600 - 900)) / (3.14159 * 700) < 0.02);

  // winding does not depend on which way the polygon was given
  DisplayList d1, d2;
  float cw[8] = {10, 10, 50, 10, 50, 50, 10, 50}, ccw[8] = {10, 10, 10, 50, 50, 50, 50, 10};
  d1.beginOp(rgb(255, 255, 255)); d1.polygon(cw, 4); d1.endOp();
  d2.beginOp(rgb(255, 255, 255)); d2.polygon(ccw, 4); d2.endOp();
  CHECK(renderAll(d1) == renderAll(d2));

  // colour and alpha blending over what is underneath
  dl.clear();
  dl.beginOp(rgb(200, 0, 0));
  float full[8] = {0, 0, 50, 0, 50, 50, 0, 50};
  dl.polygon(full, 4);
  dl.endOp();
  dl.beginOp(rgb(0, 0, 200));
  float half[8] = {25, 0, 50, 0, 50, 50, 25, 50};
  dl.polygon(half, 4);
  dl.endOp();
  img = renderAll(dl);
  CHECK(((img[10 * kSize + 10] >> 11) & 31) > 20 && (img[10 * kSize + 10] & 31) == 0);       // red
  CHECK((img[10 * kSize + 40] & 31) > 20 && ((img[10 * kSize + 40] >> 11) & 31) == 0);       // blue on top

  // clip: pixels outside the rounded rectangle are not touched, inside are
  dl.clear();
  ClipRR clip{100, 100, 40, 20, 10};
  dl.beginOp(rgb(255, 255, 255), &clip);
  float big[8] = {0, 0, 200, 0, 200, 200, 0, 200};
  dl.polygon(big, 4);
  dl.endOp();
  img = renderAll(dl);
  CHECK(level(img[100 * kSize + 100]) == 255);
  CHECK(level(img[100 * kSize + 150]) == 0 && level(img[130 * kSize + 100]) == 0);
  CHECK(level(img[81 * kSize + 61]) < 255);                                                  // rounded corner is cut
  double ca = totalCoverage(img), cexact = 80 * 40 - (4 - 3.14159265) * 100;
  CHECK(fabs(ca - cexact) / cexact < 0.01);
}

static void testBands() {
  DisplayList dl;
  Ui ui;
  UiInput in;
  in.connected = true;
  in.hasCore = true;
  in.core.version = 1; in.core.mode = MN_MODE_NAV; in.core.maneuver = MN_MAN_ROUNDABOUT; in.core.roundabout_exit = 3;
  in.core.dist_to_maneuver_m = 800; in.core.remaining_dist_m = 12345; in.core.eta_minutes = 37;
  strcpy(in.street, "Silk Board Junction");
  ui.update(in);
  auto banded = renderAll(ui.list());
  std::vector<uint16_t> whole(kSize * kSize);
  ui.renderBand(0, kSize, whole.data());                // one band as tall as the panel
  CHECK(banded == whole);
  // odd band boundaries
  std::vector<uint16_t> odd(kSize * kSize);
  for (int y = 0, h = 7; y < kSize; y += h, h = (h % 13) + 5) {
    int rows = kSize - y < h ? kSize - y : h;
    ui.renderBand(y, rows, &odd[y * kSize]);
  }
  CHECK(odd == whole);
  // writes stay inside the buffer (guard words, also watched by AddressSanitizer)
  std::vector<uint16_t> g(kSize * 3 + 8, 0xABCD);
  ui.renderBand(100, 3, &g[4]);
  CHECK(g[0] == 0xABCD && g[3] == 0xABCD && g[4 + kSize * 3] == 0xABCD && g[g.size() - 1] == 0xABCD);
}

static void testStatusScreens() {
  Ui ui;
  UiInput in;
  in.connected = true;
  in.hasCore = true;
  in.core.version = 1; in.core.maneuver = MN_MAN_LEFT; in.core.dist_to_maneuver_m = 800; in.core.remaining_dist_m = 5000;
  in.core.eta_minutes = 12;
  strcpy(in.street, "Brigade Road");
  // the protocol's OFF_ROUTE mode has no screen of its own: it shows the rerouting screen
  in.core.mode = MN_MODE_REROUTING;
  ui.update(in);
  auto rer = renderAll(ui.list());
  in.core.mode = MN_MODE_OFF_ROUTE;
  ui.update(in);
  CHECK(renderAll(ui.list()) == rer);
  CHECK(!ui.lastWasLarge());                              // status screens never use the large-arrow screen
  // and each status screen differs from the others
  in.core.mode = MN_MODE_ARRIVED; ui.update(in); auto arrived = renderAll(ui.list());
  in.core.mode = MN_MODE_IDLE; ui.update(in); auto idle = renderAll(ui.list());
  in.connected = false; ui.update(in); auto waiting = renderAll(ui.list());
  CHECK(rer != waiting && arrived != waiting);
  CHECK(rer != arrived);
  CHECK(rer != idle);
  CHECK(arrived != idle);
  CHECK(idle != waiting);
}

static void testPools() {
  DisplayList dl;
  for (int i = 0; i < kMaxOps + 50; i++) {
    dl.beginOp(rgb(255, 255, 255));
    dl.circle(50.0f + i, 50, 10, true);
    dl.endOp();
  }
  CHECK(dl.overflowed());
  CHECK(dl.opCount() == kMaxOps);
  renderAll(dl);                                         // still renders what fit

  dl.clear();
  CHECK(!dl.overflowed());
  dl.beginOp(rgb(255, 255, 255));
  for (int i = 0; i < 400; i++) dl.circle(200, 200, 150, true, 48);   // 19 200 points: more than the pool
  dl.endOp();
  CHECK(dl.overflowed());
  renderAll(dl);

  dl.clear();                                            // wild coordinates are clamped, not undefined behaviour
  dl.beginOp(rgb(255, 255, 255));
  float w[8] = {-1e9f, -1e9f, 1e9f, -1e9f, 1e9f, 1e9f, -1e9f, 1e9f};
  dl.polygon(w, 4);
  dl.endOp();
  renderAll(dl);
  dl.clear();
  dl.beginOp(rgb(255, 255, 255));
  float nanp[6] = {NAN, 0, 10, INFINITY, 5, 5};
  dl.polygon(nanp, 3);
  dl.endOp();
  renderAll(dl);
  CHECK(true);
}

static void testText() {
  CHECK(textWidth("", 40) == 0);
  CHECK(fabs(textWidth("Brigade Road", 40) - 2 * textWidth("Brigade Road", 20)) < 0.01);
  CHECK(textWidth("W", 40) > textWidth("i", 40));
  CHECK(textWidth("AV", 100) < textWidth("A", 100) + textWidth("V", 100));       // kerning applied
  CHECK(textWidth("\x01\x02", 40) == 0);                                          // unprintable: skipped
  DisplayList dl;
  dl.beginOp(rgb(255, 255, 255));
  addText(dl, "Hello, 0123456789 " MN_DOT MN_ELLIPSIS, 10, 100, 60);
  dl.endOp();
  CHECK(dl.pointCount() > 100 && !dl.overflowed());
}

static void testFormats() {
  char v[16];
  const char *u;
  struct { uint32_t m; const char *val, *unit; } cases[] = {
      {0, "0", "m"}, {4, "0", "m"}, {5, "10", "m"}, {84, "80", "m"}, {85, "90", "m"}, {99, "100", "m"}, {100, "100", "m"},
      {124, "100", "m"}, {125, "150", "m"}, {999, "1000", "m"}, {1000, "1.0", "km"}, {1249, "1.2", "km"}, {1250, "1.3", "km"},
      {9949, "9.9", "km"}, {9950, "10", "km"}, {12499, "12", "km"}, {12500, "13", "km"}, {999499, "999", "km"}, {999500, "999+", "km"},
      {4294967295u, "999+", "km"}};
  for (auto &c : cases) {
    formatDistance(c.m, v, sizeof(v), &u);
    CHECK(!strcmp(v, c.val) && !strcmp(u, c.unit));
    if (strcmp(v, c.val)) printf("   %u -> %s %s (want %s %s)\n", c.m, v, u, c.val, c.unit);
  }
  formatEta(0, v, sizeof(v)); CHECK(!strcmp(v, "0 min"));
  formatEta(59, v, sizeof(v)); CHECK(!strcmp(v, "59 min"));
  formatEta(60, v, sizeof(v)); CHECK(!strcmp(v, "1 h 00 m"));
  formatEta(72, v, sizeof(v)); CHECK(!strcmp(v, "1 h 12 m"));
  formatEta(5999, v, sizeof(v)); CHECK(!strcmp(v, "99 h 59 m"));
  formatEta(6000, v, sizeof(v)); CHECK(!strcmp(v, "99+ h"));
  formatEta(65535, v, sizeof(v)); CHECK(!strcmp(v, "99+ h"));
}

// ---- random screens: nothing may crash, overflow, or touch the edge margin -----------------------------------------------

static uint32_t g_seed = 12345;
static uint32_t rnd() { g_seed = g_seed * 1664525u + 1013904223u; return g_seed >> 8; }
static float frand() { return rnd() / 16777216.0f; }

static uint32_t logUniform(uint32_t maxv) {
  float e = frand() * log10f(static_cast<float>(maxv) + 1.0f);
  return static_cast<uint32_t>(powf(10.0f, e));
}

static const char kChars[] = "WMwmiIl.,'-ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789 /()&";

static void testRandomScreens() {
  Ui ui;
  int margin = 12, worstEdge = 0;
  long screens = 0, larges = 0, maps = 0;
  static std::vector<uint16_t> img;
  for (int iter = 0; iter < 1500; iter++) {
    UiInput in;
    in.connected = rnd() % 8 != 0;
    in.hasCore = rnd() % 10 != 0;
    in.core.version = 1;
    in.core.mode = rnd() % 5;
    in.core.maneuver = rnd() % 24;                         // includes values the protocol does not define
    in.core.roundabout_exit = rnd() % 14;
    in.core.dist_to_maneuver_m = rnd() % 3 == 0 ? logUniform(4294967295u) : logUniform(2000);
    in.core.remaining_dist_m = logUniform(4294967295u);
    in.core.eta_minutes = rnd() % 4 == 0 ? rnd() % 65536 : rnd() % 300;
    in.core.speed_kmh_x10 = rnd() % 2000;
    int len = rnd() % (MOTONAV_STREET_MAX + 1);
    if (rnd() % 4 == 0) len = MOTONAV_STREET_MAX;
    for (int i = 0; i < len; i++) in.street[i] = (rnd() % 5 == 0) ? 'W' : kChars[rnd() % (sizeof(kChars) - 1)];
    in.street[len] = 0;

    static motonav::RouteWindow win;
    if (rnd() % 5 != 0) {
      memset(&win, 0, sizeof(win));
      win.routeId = 1 + rnd() % 255;
      win.endsAtDestination = rnd() % 2;
      win.count = 2 + rnd() % 95;
      int32_t lat = 12970000 + static_cast<int32_t>(rnd() % 40000), lon = 77590000 + static_cast<int32_t>(rnd() % 40000);
      float spread = rnd() % 3 == 0 ? 4000.0f : (rnd() % 2 ? 300.0f : 30.0f);     // ~0.4 km .. 440 m per step
      for (int i = 0; i < win.count; i++) {
        if (rnd() % 7 != 0) {                              // sometimes a repeated point (zero-length segment)
          lat += static_cast<int32_t>((frand() - 0.5f) * 2 * spread);
          lon += static_cast<int32_t>((frand() - 0.5f) * 2 * spread);
        }
        win.latE6[i] = lat;
        win.lonE6[i] = lon;
      }
      in.route = &win;
      in.hasPos = rnd() % 8 != 0;
      uint32_t pick = rnd() % 4;
      int k = rnd() % win.count;
      in.pos.route_id = pick == 0 ? static_cast<uint8_t>(win.routeId + 1) : win.routeId;
      in.pos.seq = rnd() & 255;
      in.pos.lat_e6 = pick == 1 ? win.latE6[k] + 200000 : win.latE6[k] + static_cast<int32_t>(rnd() % 400) - 200;
      in.pos.lon_e6 = pick == 1 ? win.lonE6[k] - 200000 : win.lonE6[k] + static_cast<int32_t>(rnd() % 400) - 200;
    } else {
      in.hasPos = rnd() % 2;
      in.pos.route_id = 1;
      in.pos.lat_e6 = 12970000; in.pos.lon_e6 = 77590000;
    }
    if (rnd() % 3) ui.resetView();
    ui.update(in);
    CHECK(!ui.list().overflowed());
    if (ui.list().overflowed()) { printf("overflow at iteration %d (%d ops, %d points)\n", iter, ui.list().opCount(), ui.list().pointCount()); }
    img = renderAll(ui.list());
    screens++;
    larges += ui.lastWasLarge();
    maps += ui.mapMetersPerPixel() > 0;
    // nothing within `margin` px of the circle's edge, nothing outside it
    int bad = 0;
    for (int y = 0; y < kSize; y++)
      for (int x = 0; x < kSize; x++) {
        float r = hypotf(x + 0.5f - kSize / 2.0f, y + 0.5f - kSize / 2.0f);
        if (r > kSize / 2.0f - margin + 1 && img[y * kSize + x] != 0) { bad++; int e = static_cast<int>(kSize / 2.0f - r); if (e < worstEdge) worstEdge = e; }
      }
    CHECK(bad == 0);
    if (bad && getenv("MNUI_DUMP")) {                      // keep the picture: MNUI_DUMP=/some/dir
      char path[256];
      snprintf(path, sizeof(path), "%s/fail_%d.ppm", getenv("MNUI_DUMP"), iter);
      FILE *f = fopen(path, "wb");
      if (f) {
        fprintf(f, "P6\n%d %d\n255\n", kSize, kSize);
        for (uint16_t p : img) {
          int r = (p >> 11) & 31, g = (p >> 5) & 63, b = p & 31;
          fputc((r << 3) | (r >> 2), f); fputc((g << 2) | (g >> 4), f); fputc((b << 3) | (b >> 2), f);
        }
        fclose(f);
      }
    }
    if (bad) {
      printf("margin violated: iter %d, %d pixels (mode %d, man %d, dist %u, rem %u, eta %u, street '%s')\n", iter, bad, in.core.mode,
             in.core.maneuver, in.core.dist_to_maneuver_m, in.core.remaining_dist_m, in.core.eta_minutes, in.street);
      if (g_failures > 12) return;
    }
  }
  printf("random screens: %ld rendered, %ld large-arrow, %ld with a map\n", screens, larges, maps);
}

int main(int argc, char **argv) {
  if (argc > 1) g_seed = static_cast<uint32_t>(atoi(argv[1]));      // ./test_mnui 7 : another random sequence
  testShapes();
  testBands();
  testStatusScreens();
  testPools();
  testText();
  testFormats();
  testRandomScreens();
  printf("%d checks, %d failures\n", g_checks, g_failures);
  return g_failures ? 1 : 0;
}
