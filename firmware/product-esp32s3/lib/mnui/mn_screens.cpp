#include "mn_screens.h"

#include <math.h>
#include <stdio.h>
#include <string.h>

#include "mn_font.h"

namespace mnui {

namespace {
constexpr float kPi = 3.14159265358979f;
constexpr float kC = kSize / 2.0f;                       // panel centre
constexpr float kR = kSize / 2.0f;
constexpr float kSafeMargin = 12.0f;                     // keep everything this far inside the circle
constexpr double kMetersPerDegree = 6371008.8 * 3.14159265358979323846 / 180.0;

const Rgb kBlack = rgb(0, 0, 0), kWhite = rgb(255, 255, 255), kAmber = rgb(255, 176, 0), kDim = rgb(96, 96, 96),
          kMapBg = rgb(14, 20, 28), kMapEdge = rgb(52, 66, 82), kRed = rgb(230, 32, 32), kSoft = rgb(200, 200, 200);

const float kLadder[] = {1.5f, 2, 3, 4, 6, 8, 12, 16, 24, 32, 48, 64, 96, 128};
const int kNice[] = {10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000};

float chordHalf(float y) {
  float dy = fabsf(y - kC), inner = kR - kSafeMargin;
  float v = inner * inner - dy * dy;
  return v > 0 ? sqrtf(v) : 0;
}

// Half the width a line of text centred on y may use: the narrowest chord between its ascender top (0.38 em above the
// middle) and its descender bottom (0.56 em below it), so no glyph of any name can reach the edge.
float rowHalf(float y, float size) { return fminf(chordHalf(y - 0.38f * size), chordHalf(y + 0.56f * size)); }

// ---- text helpers -----------------------------------------------------------------------------------------------

void textMid(DisplayList &dl, const char *s, float x, float y, float size, Rgb col) {
  dl.beginOp(col);
  addText(dl, s, x - textWidth(s, size) / 2, y + middleToBaseline(size), size);
  dl.endOp();
}

// A big number with a smaller unit on the same baseline, the pair centred on cx.
void valueUnit(DisplayList &dl, float cx, float baseline, const char *value, const char *unit, float size, float unitSize) {
  float gap = 0.22f * unitSize, wv = textWidth(value, size), wu = textWidth(unit, unitSize);
  float x0 = cx - (wv + gap + wu) / 2;
  dl.beginOp(kWhite);
  addText(dl, value, x0, baseline, size);
  dl.endOp();
  dl.beginOp(kSoft);
  addText(dl, unit, x0 + wv + gap, baseline, unitSize);
  dl.endOp();
}

struct Abbrev { const char *longer, *shorter; };
const Abbrev kAbbrev[] = {{"Extension", "Ext"}, {"Junction", "Jn"}, {"Road", "Rd"}, {"Street", "St"}, {"Highway", "Hwy"},
                          {"Avenue", "Ave"}, {"Boulevard", "Blvd"}, {"Circle", "Cir"}, {"Main", "Mn"}};

void replaceAll(char *s, int cap, const char *from, const char *to) {
  size_t lf = strlen(from), lt = strlen(to);
  char tmp[48];
  size_t o = 0;
  for (const char *p = s; *p && o + 1 < sizeof(tmp);) {
    if (strncmp(p, from, lf) == 0) {
      for (size_t i = 0; i < lt && o + 1 < sizeof(tmp); i++) tmp[o++] = to[i];
      p += lf;
    } else {
      tmp[o++] = *p++;
    }
  }
  tmp[o] = 0;
  snprintf(s, cap, "%s", tmp);
}

// Fit a string into maxW: shrink the font towards minSize, then abbreviate, then cut with an ellipsis.
float fitText(const char *src, float maxW, float size, float minSize, char *out, int cap) {
  snprintf(out, cap, "%s", src);
  float px = size;
  while (textWidth(out, px) > maxW && px > minSize) px -= 1;
  if (textWidth(out, px) > maxW) {
    for (const Abbrev &a : kAbbrev) {
      replaceAll(out, cap, a.longer, a.shorter);
      if (textWidth(out, px) <= maxW) break;
    }
  }
  if (textWidth(out, px) > maxW) {
    char probe[64];
    for (;;) {
      size_t n = strlen(out);
      snprintf(probe, sizeof(probe), "%s%s", out, MN_ELLIPSIS);
      if (n <= 1 || textWidth(probe, px) <= maxW) break;
      out[--n] = 0;                                              // drop the last character ...
      while (n > 0 && out[n - 1] == ' ') out[--n] = 0;           // ... and any space it leaves at the end
    }
    snprintf(out + strlen(out), cap - strlen(out), "%s", MN_ELLIPSIS);
  }
  return px;
}

void streetRow(DisplayList &dl, const char *street, float y, float size, float minSize) {
  char shown[48];
  float half = rowHalf(y, size);
  float px = fitText(street, 2 * half - 6, size, minSize, shown, sizeof(shown));
  textMid(dl, shown, kC, y, px, kWhite);
}

void bottomRow(DisplayList &dl, const MotoNavCore &c, float y, float size) {
  char v[12], eta[16], line[48];
  const char *u;
  formatDistance(c.remaining_dist_m, v, sizeof(v), &u);
  formatEta(c.eta_minutes, eta, sizeof(eta));
  snprintf(line, sizeof(line), "%s %s " MN_DOT " %s", v, u, eta);
  char shown[64];
  float px = fitText(line, 2 * chordHalf(y + 12) - 6, size, 24, shown, sizeof(shown));
  textMid(dl, shown, kC, y, px, kWhite);
}
}  // namespace

void formatDistance(uint32_t m, char *value, int cap, const char **unit) {
  if (m >= 999500u) { snprintf(value, cap, "999+"); *unit = "km"; }        // keeps the number inside the circle
  else if (m < 100) { snprintf(value, cap, "%u", static_cast<unsigned>(((m + 5) / 10) * 10)); *unit = "m"; }
  else if (m < 1000) { snprintf(value, cap, "%u", static_cast<unsigned>(((m + 25) / 50) * 50)); *unit = "m"; }
  else if (m < 9950) { uint32_t t = (m + 50) / 100; snprintf(value, cap, "%u.%u", static_cast<unsigned>(t / 10), static_cast<unsigned>(t % 10)); *unit = "km"; }
  else { snprintf(value, cap, "%u", static_cast<unsigned>((m + 500) / 1000)); *unit = "km"; }
}

void formatEta(uint32_t minutes, char *out, int cap) {
  if (minutes < 60) snprintf(out, cap, "%u min", static_cast<unsigned>(minutes));
  else if (minutes >= 6000) snprintf(out, cap, "99+ h");
  else snprintf(out, cap, "%u h %02u m", static_cast<unsigned>(minutes / 60), static_cast<unsigned>(minutes % 60));
}

// ---- status screens -----------------------------------------------------------------------------------------------

// kind: 0 waiting for phone, 1 warning, 2 spinner, 3 flag
void Ui::message(int kind, const char *line1, const char *line2) {
  const float cx = kC, cy = 122;
  if (kind == 1) {                                                  // warning triangle with "!"
    dl_.beginOp(kAmber);
    float t[6] = {cx, cy - 52, cx + 58, cy + 44, cx - 58, cy + 44};
    dl_.polygon(t, 3);
    dl_.endOp();
    textMid(dl_, "!", cx, cy + 17, 62, kBlack);
  } else if (kind == 2) {                                           // rerouting spinner
    float arc[2 * 80];
    int n = 0;
    for (int a = 40; a <= 330 && n < 80; a += 5, n++) {
      arc[2 * n] = cx + 43 * cosf(a * kPi / 180);
      arc[2 * n + 1] = cy + 43 * sinf(a * kPi / 180);
    }
    dl_.beginOp(kWhite);
    dl_.polyline(arc, n, 14, false);
    float h[6] = {cx + 56, cy - 14, cx + 32, cy - 4, cx + 60, cy + 12};
    dl_.polygon(h, 3);
    dl_.endOp();
  } else if (kind == 3) {
    drawIcon(dl_, cx, cy, 110, MN_MAN_ARRIVE, 0, kWhite);
  } else {                                                          // waiting for the phone: ring with a Bluetooth rune
    dl_.beginOp(kSoft);
    dl_.circle(cx, cy, 46, true);
    dl_.circle(cx, cy, 39, false);
    dl_.endOp();
    float r[12] = {cx - 16, cy - 22, cx + 18, cy + 12, cx, cy + 30, cx, cy - 30, cx + 18, cy - 12, cx - 16, cy + 22};
    dl_.beginOp(kWhite);
    dl_.polyline(r, 6, 6, false);
    dl_.endOp();
  }
  textMid(dl_, line1, kC, 236, 56, kWhite);
  char shown[48];
  float px = fitText(line2, 2 * rowHalf(292, 36) - 6, 36, 24, shown, sizeof(shown));
  textMid(dl_, shown, kC, 292, px, kSoft);
}

// ---- the map ------------------------------------------------------------------------------------------------------

void Ui::drawMap(const UiInput &in, float x0, float y0, float x1, float y1, float lookAheadM) {
  const float vw = x1 - x0, vh = y1 - y0, corner = 16;
  ClipRR clip{(x0 + x1) / 2, (y0 + y1) / 2, vw / 2, vh / 2, corner};
  lastMpp_ = 0;

  dl_.beginOp(kMapBg);
  dl_.roundedRect(x0, y0, x1, y1, corner);
  dl_.endOp();

  auto edge = [&]() {
    dl_.beginOp(kMapEdge);
    dl_.roundedRect(x0, y0, x1, y1, corner, true);
    dl_.roundedRect(x0 + 2, y0 + 2, x1 - 2, y1 - 2, corner - 2, false);
    dl_.endOp();
  };
  auto marker = [&](float x, float y, float heading) {
    for (int pass = 0; pass < 2; pass++) {
      float sc = pass == 0 ? 1.35f : 1.0f;
      float tip = 13 * sc, back = 9 * sc, half = 9 * sc;
      float ux = sinf(heading), uy = -cosf(heading), px = -uy, py = ux;
      float q[8] = {x + ux * tip, y + uy * tip,
                    x - ux * back * 0.6f + px * half, y - uy * back * 0.6f + py * half,
                    x - ux * back * 0.2f, y - uy * back * 0.2f,
                    x - ux * back * 0.6f - px * half, y - uy * back * 0.6f - py * half};
      dl_.beginOp(pass == 0 ? kBlack : kWhite, &clip);
      dl_.polygon(q, 4);
      dl_.endOp();
    }
  };

  const motonav::RouteWindow *win = in.route;
  if (!in.hasPos) {
    edge();
    return;
  }
  bool haveRoute = win && win->count >= 2 && win->routeId == in.pos.route_id;
  if (!haveRoute) {
    marker(x0 + vw / 2, y0 + vh / 2, 0.0f);
    edge();
    return;
  }

  // window points in metres around the rider (x east, y north)
  const int n = win->count;
  static float wx[motonav::kRouteMaxPoints], wy[motonav::kRouteMaxPoints];
  double lat0 = in.pos.lat_e6 / 1e6;
  float kx = static_cast<float>(cos(lat0 * 3.14159265358979323846 / 180.0) * kMetersPerDegree * 1e-6);
  float ky = static_cast<float>(kMetersPerDegree * 1e-6);
  for (int i = 0; i < n; i++) {
    wx[i] = static_cast<float>(win->lonE6[i] - in.pos.lon_e6) * kx;
    wy[i] = static_cast<float>(win->latE6[i] - in.pos.lat_e6) * ky;
  }
  // nearest point of the route to the rider (the origin)
  int seg = 0;
  float tSeg = 0, best = 1e30f;
  for (int i = 0; i + 1 < n; i++) {
    float ax = wx[i], ay = wy[i], dx = wx[i + 1] - ax, dy = wy[i + 1] - ay, l2 = dx * dx + dy * dy;
    float t = l2 == 0 ? 0 : (-(ax * dx + ay * dy)) / l2;
    t = t < 0 ? 0 : (t > 1 ? 1 : t);
    float d = hypotf(ax + t * dx, ay + t * dy);
    if (d < best) { best = d; seg = i; tSeg = t; }
  }
  float cutX = wx[seg] + tSeg * (wx[seg + 1] - wx[seg]), cutY = wy[seg] + tSeg * (wy[seg + 1] - wy[seg]);

  // the part of the route to look at: from the rider's position on it, lookAheadM further
  static float ax_[motonav::kRouteMaxPoints + 2], ay_[motonav::kRouteMaxPoints + 2];
  int na = 0;
  ax_[na] = cutX; ay_[na] = cutY; na++;
  float left = lookAheadM;
  for (int i = seg + 1; i < n && na < static_cast<int>(motonav::kRouteMaxPoints) + 1; i++) {
    float d = hypotf(wx[i] - ax_[na - 1], wy[i] - ay_[na - 1]);
    if (d >= left) {
      float f = d > 0 ? left / d : 0;
      ax_[na] = ax_[na - 1] + f * (wx[i] - ax_[na - 1]);
      ay_[na] = ay_[na - 1] + f * (wy[i] - ay_[na - 1]);
      na++;
      break;
    }
    ax_[na] = wx[i]; ay_[na] = wy[i]; na++;
    left -= d;
  }
  float minx = 0, maxx = 0, miny = 0, maxy = 0;                     // includes the rider at (0, 0)
  for (int i = 0; i < na; i++) {
    minx = fminf(minx, ax_[i]);
    maxx = fmaxf(maxx, ax_[i]);
    miny = fminf(miny, ay_[i]);
    maxy = fmaxf(maxy, ay_[i]);
  }
  const float pad = 26;
  float needX = (maxx - minx) / fmaxf(1.0f, vw - 2 * pad), needY = (maxy - miny) / fmaxf(1.0f, vh - 2 * pad);
  float need = fmaxf(needX, needY), mpp = kLadder[sizeof(kLadder) / sizeof(kLadder[0]) - 1];
  for (float m : kLadder) if (m >= need) { mpp = m; break; }
  if (mpp_ > 0 && mpp_ >= need && need > 0.62f * mpp_) mpp = mpp_;    // keep the zoom while it still fits well enough
  mpp_ = mpp;
  lastMpp_ = mpp;
  float mx = (minx + maxx) / 2, my = (miny + maxy) / 2;
  auto sx = [&](float x) { return x0 + vw / 2 + (x - mx) / mpp; };
  auto sy = [&](float y) { return y0 + vh / 2 - (y - my) / mpp; };

  const float linePx = 9.0f;
  static float pts[2 * (motonav::kRouteMaxPoints + 2)];
  // part already ridden: dim
  int nb = 0;
  for (int i = 0; i <= seg; i++) { pts[2 * nb] = sx(wx[i]); pts[2 * nb + 1] = sy(wy[i]); nb++; }
  pts[2 * nb] = sx(cutX); pts[2 * nb + 1] = sy(cutY); nb++;
  dl_.beginOp(kDim, &clip);
  dl_.polyline(pts, nb, linePx * 0.8f, true);
  dl_.endOp();
  // part ahead: dark casing, then amber
  int nf = 0;
  pts[2 * nf] = sx(cutX); pts[2 * nf + 1] = sy(cutY); nf++;
  for (int i = seg + 1; i < n; i++) { pts[2 * nf] = sx(wx[i]); pts[2 * nf + 1] = sy(wy[i]); nf++; }
  dl_.beginOp(kBlack, &clip);
  dl_.polyline(pts, nf, linePx + 6, true);
  dl_.endOp();
  dl_.beginOp(kAmber, &clip);
  dl_.polyline(pts, nf, linePx, true);
  dl_.endOp();
  if (win->endsAtDestination) {                                     // destination: white ring with a red centre
    float dx = sx(wx[n - 1]), dy = sy(wy[n - 1]);
    dl_.beginOp(kBlack, &clip); dl_.circle(dx, dy, 13, true); dl_.endOp();
    dl_.beginOp(kWhite, &clip); dl_.circle(dx, dy, 10.5f, true); dl_.endOp();
    dl_.beginOp(kRed, &clip); dl_.circle(dx, dy, 6.5f, true); dl_.endOp();
  }
  float heading = atan2f(wx[seg + 1] - wx[seg], wy[seg + 1] - wy[seg]);
  marker(sx(0), sy(0), heading);
  edge();

  // scale bar: the longest round length that is at most 96 px
  int length = kNice[0];
  for (int v : kNice) if (v / mpp <= 96) length = v;
  float bx = x0 + 14, by = y1 - 12, bw = length / mpp;
  dl_.beginOp(kWhite);
  float bar[8] = {bx, by - 2, bx + bw, by - 2, bx + bw, by + 2, bx, by + 2};
  dl_.polygon(bar, 4);
  for (int e = 0; e < 2; e++) {
    float tx = bx + e * bw;
    float tick[8] = {tx - 1.5f, by - 7, tx + 1.5f, by - 7, tx + 1.5f, by + 3, tx - 1.5f, by + 3};
    dl_.polygon(tick, 4);
  }
  dl_.endOp();
  char label[24];
  if (length < 1000) snprintf(label, sizeof(label), "%d m", length);
  else snprintf(label, sizeof(label), "%d km", length / 1000);
  dl_.beginOp(kMapBg);                                              // dark halo: the same text shifted around a ring
  addText(dl_, label, bx, by - 10, 26);
  dl_.endOp();
  for (int k = 0; k < 8; k++) dl_.repeatLast(kMapBg, 3 * cosf(k * kPi / 4), 3 * sinf(k * kPi / 4));
  dl_.repeatLast(kWhite, 0, 0);                                     // the label itself
}

// ---- the two navigation screens -------------------------------------------------------------------------------------

void Ui::navFull(const UiInput &in) {
  const MotoNavCore &c = in.core;
  char val[12];
  const char *unit;
  formatDistance(c.dist_to_maneuver_m, val, sizeof(val), &unit);
  const float rowY = 92;
  float size = c.maneuver == MN_MAN_ROUNDABOUT ? 84.0f : 80.0f;
  float distW = strcmp(unit, "m") == 0 ? 135.0f : 160.0f;
  float left = kC - (size + 16 + distW) / 2;
  drawIcon(dl_, left + size / 2, rowY, size, c.maneuver, c.roundabout_exit, kWhite);
  valueUnit(dl_, left + size + 16 + distW / 2, rowY + 22, val, unit, 58, 36);
  streetRow(dl_, in.street, 150, 40, 34);
  const float top = 172, bottom = 314;
  float half = fminf(chordHalf(top), chordHalf(bottom)) - 6;
  float look = fmaxf(900.0f, fminf(2500.0f, c.dist_to_maneuver_m + 500.0f));
  drawMap(in, kC - half, top, kC + half, bottom, look);
  bottomRow(dl_, c, 337, 34);
}

void Ui::navLarge(const UiInput &in) {
  const MotoNavCore &c = in.core;
  char val[12];
  const char *unit;
  formatDistance(c.dist_to_maneuver_m, val, sizeof(val), &unit);
  drawIcon(dl_, kC, 98, c.maneuver == MN_MAN_ROUNDABOUT ? 120.0f : 112.0f, c.maneuver, c.roundabout_exit, kWhite);
  valueUnit(dl_, kC, 232, val, unit, 92, 52);
  streetRow(dl_, in.street, 286, 44, 36);
  bottomRow(dl_, c, 338, 34);
}

void Ui::update(const UiInput &in) {
  dl_.clear();
  large_ = false;
  lastMpp_ = 0;
  if (!in.connected || !in.hasCore) { message(0, "MotoNav", "Waiting for phone"); return; }
  const MotoNavCore &c = in.core;
  bool arriveMan = c.maneuver == MN_MAN_ARRIVE || c.maneuver == MN_MAN_ARRIVE_LEFT || c.maneuver == MN_MAN_ARRIVE_RIGHT;
  bool near = c.mode == MN_MODE_NAV && c.dist_to_maneuver_m <= kTurnSwitchM && c.maneuver != MN_MAN_NONE;
  if (near) { large_ = true; navLarge(in); return; }
  if (c.mode == MN_MODE_OFF_ROUTE) message(1, "OFF ROUTE", "Return to the route");
  else if (c.mode == MN_MODE_REROUTING) message(2, "REROUTING", "Finding route");
  else if (c.mode == MN_MODE_ARRIVED || (arriveMan && c.dist_to_maneuver_m < 40)) message(3, "ARRIVED", in.street[0] ? in.street : "Destination");
  else if (c.mode == MN_MODE_IDLE) message(0, "MotoNav", "Choose a destination");
  else navFull(in);
}

}  // namespace mnui
