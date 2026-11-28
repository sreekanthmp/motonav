// Manoeuvre icons (port of tools/minimap_sim/arrows.py). Each icon is a few primitives in a unit box (x right, y up);
// drawIcon() fits the icon's bounding box into a square and centres it, so every icon has the same optical size.
// India drives on the left: roundabouts run clockwise (exit 1 is a left turn), the standard U-turn is the clockwise one.
#include <math.h>
#include <stdio.h>
#include <string.h>

#include "mn_font.h"
#include "mn_screens.h"
#include "nav_protocol.h"

namespace mnui {

namespace {
constexpr float kPi = 3.14159265358979f;
constexpr float kStroke = 0.24f, kHeadL = 0.62f, kHeadW = 0.62f;
constexpr int kIconPts = 48;

enum Kind : uint8_t { kStrokeK, kPolyK, kRingK, kDigitK };
enum ColorId : uint8_t { kWhite, kFaint };

struct Prim {
  Kind kind;
  float x[kIconPts], y[kIconPts];
  int n;
  float w;                  // stroke width / ring width
  ColorId color;
  bool head;
  float headScale;
  float cx, cy, r;          // ring / digit
  int digit;
};

struct Icon {
  Prim p[6];
  int n = 0;
  Prim &add(Kind k) {
    Prim &q = p[n++];
    memset(&q, 0, sizeof(q));
    q.kind = k;
    q.headScale = 1.0f;
    return q;
  }
};

void pushPt(Prim &q, float x, float y) {
  if (q.n < kIconPts) {
    q.x[q.n] = x;
    q.y[q.n] = y;
    q.n++;
  }
}

// Round the corners of a polyline with quadratic curves (same as arrows._rounded).
void rounded(Prim &out, const float *px, const float *py, int n, float radius) {
  if (n < 3) {
    for (int i = 0; i < n; i++) pushPt(out, px[i], py[i]);
    return;
  }
  pushPt(out, px[0], py[0]);
  for (int i = 1; i < n - 1; i++) {
    float ax = px[i - 1], ay = py[i - 1], wx = px[i], wy = py[i], bx = px[i + 1], by = py[i + 1];
    float la = hypotf(ax - wx, ay - wy), lb = hypotf(bx - wx, by - wy);
    float t = fminf(radius, fminf(la / 2, lb / 2));
    float p1x = wx + (ax - wx) / la * t, p1y = wy + (ay - wy) / la * t;
    float p2x = wx + (bx - wx) / lb * t, p2y = wy + (by - wy) / lb * t;
    for (int k = 0; k <= 12; k++) {
      float u = k / 12.0f, v = 1 - u;
      pushPt(out, v * v * p1x + 2 * v * u * wx + u * u * p2x, v * v * p1y + 2 * v * u * wy + u * u * p2y);
    }
  }
  pushPt(out, px[n - 1], py[n - 1]);
}

Prim &arrow(Icon &ic, const float *px, const float *py, int n, float radius = 0, ColorId c = kWhite, bool head = true,
            float width = kStroke) {
  Prim &q = ic.add(kStrokeK);
  if (radius > 0) rounded(q, px, py, n, radius);
  else for (int i = 0; i < n; i++) pushPt(q, px[i], py[i]);
  q.w = width;
  q.color = c;
  q.head = head;
  return q;
}

void mirror(Icon &ic) {
  for (int i = 0; i < ic.n; i++) {
    Prim &q = ic.p[i];
    if (q.kind == kStrokeK || q.kind == kPolyK)
      for (int k = 0; k < q.n; k++) q.x[k] = -q.x[k];
  }
}

void turn(Icon &ic, int angleDeg) {
  float a = angleDeg * kPi / 180.0f;
  float dx = -sinf(a), dy = cosf(a);
  float cx, cy, diag, rad;
  if (angleDeg == 45) { cx = 0.3f; cy = -0.1f; diag = 1.15f; rad = 0.5f; }
  else if (angleDeg == 90) { cx = 0.3f; cy = 0.0f; diag = 0.95f; rad = 0.5f; }
  else { cx = 0.5f; cy = 0.55f; diag = 1.25f; rad = 0.3f; }
  float px[3] = {cx, cx, cx + dx * diag}, py[3] = {-1.0f, cy, cy + dy * diag};
  arrow(ic, px, py, 3, rad);
}

void keep(Icon &ic, bool left) {
  float bx[2] = {0.35f, 0.35f}, by[2] = {-0.4f, 0.75f};
  arrow(ic, bx, by, 2, 0, kFaint, false, 0.18f);
  float mx[4] = {0.35f, 0.35f, -0.25f, -0.25f}, my[4] = {-1.0f, -0.35f, 0.25f, 0.75f};
  arrow(ic, mx, my, 4, 0.35f);
  if (!left) mirror(ic);
}

void uturn(Icon &ic, bool clockwise) {
  Prim &q = ic.add(kStrokeK);
  q.w = kStroke;
  q.head = true;
  pushPt(q, -0.5f, -1.0f);
  pushPt(q, -0.5f, 0.2f);
  for (int i = 1; i < 24; i++) {                                    // arc from 180 deg to 0 deg, radius 0.5, centre (0, 0.2)
    float a = (180.0f - 180.0f * i / 24.0f) * kPi / 180.0f;
    pushPt(q, 0.5f * cosf(a), 0.2f + 0.5f * sinf(a));
  }
  pushPt(q, 0.5f, 0.2f);
  pushPt(q, 0.5f, -0.35f);
  if (!clockwise) mirror(ic);
}

void roundabout(Icon &ic, int exitNo) {
  const float cx = 0, cy = 0, r = 0.62f;
  float theta = exitNo == 1 ? -90.0f : (exitNo == 3 ? 90.0f : (exitNo == 2 || exitNo == 0 ? 0.0f : 150.0f));
  float dx = sinf(theta * kPi / 180.0f), dy = cosf(theta * kPi / 180.0f);
  Prim &ring = ic.add(kRingK);
  ring.cx = cx; ring.cy = cy; ring.r = r; ring.w = 0.18f;
  float sx[2] = {0.0f, 0.0f}, sy[2] = {-1.15f, cy - r};
  Prim &stub = ic.add(kStrokeK);
  for (int i = 0; i < 2; i++) pushPt(stub, sx[i], sy[i]);
  stub.w = 0.18f;
  Prim &ex = ic.add(kStrokeK);
  pushPt(ex, cx + dx * (r - 0.02f), cy + dy * (r - 0.02f));
  pushPt(ex, cx + dx * (r + 0.12f), cy + dy * (r + 0.12f));
  ex.w = 0.22f;
  ex.head = true;
  ex.headScale = 0.8f;
  if (exitNo > 0) {
    Prim &d = ic.add(kDigitK);
    d.cx = cx; d.cy = cy; d.r = r; d.digit = exitNo;
  }
}

void flag(Icon &ic) {
  float px[2] = {-0.45f, -0.45f}, py[2] = {-1.0f, 0.95f};
  arrow(ic, px, py, 2, 0, kWhite, false, 0.16f);
  Prim &f = ic.add(kPolyK);
  pushPt(f, -0.45f, 0.95f); pushPt(f, 0.95f, 0.5f); pushPt(f, -0.45f, 0.05f);
}

void ferry(Icon &ic) {
  Prim &h = ic.add(kPolyK);
  pushPt(h, -0.9f, -0.05f); pushPt(h, 0.9f, -0.05f); pushPt(h, 0.55f, -0.55f); pushPt(h, -0.55f, -0.55f);
  Prim &c = ic.add(kPolyK);
  pushPt(c, -0.3f, -0.05f); pushPt(c, -0.3f, 0.55f); pushPt(c, 0.4f, 0.55f); pushPt(c, 0.4f, -0.05f);
  float wx[5] = {-0.9f, -0.45f, 0.0f, 0.45f, 0.9f}, wy[5] = {-0.8f, -0.7f, -0.8f, -0.7f, -0.8f};
  arrow(ic, wx, wy, 5, 0, kFaint, false, 0.1f);
}

void buildIcon(Icon &ic, uint8_t m, int exitNo) {
  switch (m) {
    case MN_MAN_STRAIGHT:
    case MN_MAN_DEPART: { float x[2] = {0, 0}, y[2] = {-1.0f, 0.35f}; arrow(ic, x, y, 2); break; }
    case MN_MAN_LEFT: turn(ic, 90); break;
    case MN_MAN_RIGHT: turn(ic, 90); mirror(ic); break;
    case MN_MAN_SLIGHT_LEFT: turn(ic, 45); break;
    case MN_MAN_SLIGHT_RIGHT: turn(ic, 45); mirror(ic); break;
    case MN_MAN_SHARP_LEFT: turn(ic, 135); break;
    case MN_MAN_SHARP_RIGHT: turn(ic, 135); mirror(ic); break;
    case MN_MAN_UTURN_RIGHT: uturn(ic, true); break;
    case MN_MAN_UTURN_LEFT: uturn(ic, false); break;
    case MN_MAN_KEEP_LEFT:
    case MN_MAN_RAMP_LEFT: keep(ic, true); break;
    case MN_MAN_KEEP_RIGHT:
    case MN_MAN_RAMP_RIGHT: keep(ic, false); break;
    case MN_MAN_MERGE: {
      float ax[4] = {-0.55f, -0.55f, 0.0f, 0.0f}, ay[4] = {-1.0f, -0.45f, 0.1f, 0.5f};
      arrow(ic, ax, ay, 4);
      float bx[3] = {0.55f, 0.55f, 0.0f}, by[3] = {-1.0f, -0.45f, 0.1f};
      arrow(ic, bx, by, 3, 0, kFaint, false, 0.18f);
      break;
    }
    case MN_MAN_ROUNDABOUT: roundabout(ic, exitNo); break;
    case MN_MAN_FERRY: ferry(ic); break;
    case MN_MAN_ARRIVE:
    case MN_MAN_ARRIVE_LEFT:
    case MN_MAN_ARRIVE_RIGHT: flag(ic); break;
    default: break;
  }
}

// arrowhead triangle for a stroke's last segment
void headPts(const Prim &q, float out[6]) {
  float x0 = q.x[q.n - 2], y0 = q.y[q.n - 2], x1 = q.x[q.n - 1], y1 = q.y[q.n - 1];
  float d = hypotf(x1 - x0, y1 - y0);
  if (d == 0) d = 1;
  float ux = (x1 - x0) / d, uy = (y1 - y0) / d, px = -uy, py = ux;
  float hw = (q.w != kStroke ? kHeadW * sqrtf(q.w / kStroke) : kHeadW) * q.headScale, hl = kHeadL * q.headScale;
  out[0] = x1 + px * hw; out[1] = y1 + py * hw;
  out[2] = x1 - px * hw; out[3] = y1 - py * hw;
  out[4] = x1 + ux * hl; out[5] = y1 + uy * hl;
}

bool bbox(const Icon &ic, float *x0, float *y0, float *x1, float *y1) {
  float minx = 1e9f, miny = 1e9f, maxx = -1e9f, maxy = -1e9f;
  auto add = [&](float x, float y) {
    minx = fminf(minx, x);
    maxx = fmaxf(maxx, x);
    miny = fminf(miny, y);
    maxy = fmaxf(maxy, y);
  };
  for (int i = 0; i < ic.n; i++) {
    const Prim &q = ic.p[i];
    if (q.kind == kStrokeK) {
      for (int k = 0; k < q.n; k++) {
        add(q.x[k] - q.w / 2, q.y[k] - q.w / 2);
        add(q.x[k] + q.w / 2, q.y[k] + q.w / 2);
      }
      if (q.head && q.n >= 2) {
        float h[6];
        headPts(q, h);
        for (int k = 0; k < 3; k++) add(h[2 * k], h[2 * k + 1]);
      }
    } else if (q.kind == kPolyK) {
      for (int k = 0; k < q.n; k++) add(q.x[k], q.y[k]);
    } else if (q.kind == kRingK) {
      add(q.cx - q.r - q.w / 2, q.cy - q.r - q.w / 2);
      add(q.cx + q.r + q.w / 2, q.cy + q.r + q.w / 2);
    }
  }
  if (maxx < minx) return false;
  *x0 = minx; *y0 = miny; *x1 = maxx; *y1 = maxy;
  return true;
}
}  // namespace

bool drawIcon(DisplayList &dl, float cx, float cy, float size, uint8_t maneuver, int exitNo, Rgb white) {
  Icon ic;
  buildIcon(ic, maneuver, exitNo);
  float x0, y0, x1, y1;
  if (!bbox(ic, &x0, &y0, &x1, &y1)) return false;
  float s = size / fmaxf(x1 - x0, y1 - y0);
  float mx = (x0 + x1) / 2, my = (y0 + y1) / 2;
  auto TX = [&](float x) { return cx + (x - mx) * s; };
  auto TY = [&](float y) { return cy - (y - my) * s; };
  const Rgb faint = rgb(112, 112, 112);
  for (int i = 0; i < ic.n; i++) {
    const Prim &q = ic.p[i];
    Rgb col = q.color == kFaint ? faint : white;
    if (q.kind == kStrokeK) {
      float pts[2 * kIconPts];
      for (int k = 0; k < q.n; k++) { pts[2 * k] = TX(q.x[k]); pts[2 * k + 1] = TY(q.y[k]); }
      dl.beginOp(col);
      dl.polyline(pts, q.n, q.w * s, false);
      if (q.head && q.n >= 2) {
        float h[6];
        headPts(q, h);
        float hp[6] = {TX(h[0]), TY(h[1]), TX(h[2]), TY(h[3]), TX(h[4]), TY(h[5])};
        dl.polygon(hp, 3);
      }
      dl.endOp();
    } else if (q.kind == kPolyK) {
      float pts[2 * kIconPts];
      for (int k = 0; k < q.n; k++) { pts[2 * k] = TX(q.x[k]); pts[2 * k + 1] = TY(q.y[k]); }
      dl.beginOp(col);
      dl.polygon(pts, q.n);
      dl.endOp();
    } else if (q.kind == kRingK) {
      dl.beginOp(col);
      dl.circle(TX(q.cx), TY(q.cy), (q.r + q.w / 2) * s, true);
      dl.circle(TX(q.cx), TY(q.cy), (q.r - q.w / 2) * s, false);
      dl.endOp();
    } else if (q.kind == kDigitK) {
      char buf[8];
      snprintf(buf, sizeof(buf), "%d", q.digit);
      float inner = (q.r - 0.1f) * s;
      float fs = floorf(inner * 1.7f);
      float w = textWidth(buf, fs);
      dl.beginOp(white);
      addText(dl, buf, TX(q.cx) - w / 2, TY(q.cy) + middleToBaseline(fs), fs);
      dl.endOp();
    }
  }
  return true;
}

}  // namespace mnui
