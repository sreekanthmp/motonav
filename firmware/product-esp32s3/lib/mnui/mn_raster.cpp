#include "mn_raster.h"

#include <math.h>
#include <string.h>

namespace mnui {

namespace {
constexpr float kFix = 16.0f;
constexpr float kPi = 3.14159265358979f;

inline int16_t toFix(float v) {
  float f = v * kFix;
  if (f > 32000.0f) f = 32000.0f;
  if (f < -32000.0f) f = -32000.0f;
  return static_cast<int16_t>(lroundf(f));
}

struct Edge {
  float ymin, ymax, x0, slope;   // x at y = x0 + (y - ymin) * slope
  int8_t dir;
};

struct Crossing {
  float x;
  int8_t dir;
};

inline uint16_t pack565(int r, int g, int b) {
  return static_cast<uint16_t>(((r & 0xF8) << 8) | ((g & 0xFC) << 3) | (b >> 3));
}

inline void blendPixel(uint16_t *dst, Rgb c, int a256) {
  uint16_t d = *dst;
  int dr = (d >> 11) & 31, dg = (d >> 5) & 63, db = d & 31;
  dr = (dr << 3) | (dr >> 2);
  dg = (dg << 2) | (dg >> 4);
  db = (db << 3) | (db >> 2);
  dr += ((c.r - dr) * a256) >> 8;
  dg += ((c.g - dg) * a256) >> 8;
  db += ((c.b - db) * a256) >> 8;
  *dst = pack565(dr, dg, db);
}

// Coverage (0..1) of the pixel centred at (px, py) by a rounded rectangle, from its signed distance.
inline float clipCoverage(const ClipRR &c, float px, float py) {
  float qx = fabsf(px - c.cx) - (c.hw - c.r);
  float qy = fabsf(py - c.cy) - (c.hh - c.r);
  float ox = qx > 0 ? qx : 0, oy = qy > 0 ? qy : 0;
  float inside = qx > qy ? qx : qy;
  if (inside > 0) inside = 0;
  float d = sqrtf(ox * ox + oy * oy) + inside - c.r;
  float cov = 0.5f - d;
  return cov < 0 ? 0 : (cov > 1 ? 1 : cov);
}
}  // namespace

void DisplayList::clear() {
  nPts_ = nContours_ = nOps_ = 0;
  curContourStart_ = 0;
  inOp_ = overflow_ = false;
}

bool DisplayList::beginOp(Rgb color, const ClipRR *clip) {
  if (inOp_ || nOps_ >= kMaxOps) {
    overflow_ = true;
    return false;
  }
  cur_ = Op{};
  cur_.pt0 = static_cast<uint16_t>(nPts_);
  cur_.ct0 = static_cast<uint16_t>(nContours_);
  cur_.color = color;
  cur_.dx = cur_.dy = 0;
  cur_.hasClip = clip != nullptr;
  if (clip) cur_.clip = *clip;
  inOp_ = true;
  curContourStart_ = nPts_;
  return true;
}

void DisplayList::beginContour() { curContourStart_ = nPts_; }

void DisplayList::point(float x, float y) {
  if (!inOp_) return;
  if (nPts_ >= kMaxPts) {
    overflow_ = true;
    return;
  }
  pts_[nPts_ * 2] = toFix(x);
  pts_[nPts_ * 2 + 1] = toFix(y);
  nPts_++;
}

void DisplayList::endContour(bool autoOrient, bool positive) {
  if (!inOp_) return;
  int n = nPts_ - curContourStart_;
  if (n < 3 || nContours_ >= kMaxContours) {
    if (nContours_ >= kMaxContours) overflow_ = true;
    nPts_ = curContourStart_;                       // degenerate: discard
    return;
  }
  if (autoOrient) {
    long long area2 = 0;                            // signed area x2 in fixed units (y down: positive = clockwise on screen)
    for (int i = 0; i < n; i++) {
      const int16_t *p = &pts_[(curContourStart_ + i) * 2];
      const int16_t *q = &pts_[(curContourStart_ + (i + 1) % n) * 2];
      area2 += static_cast<long long>(p[0]) * q[1] - static_cast<long long>(q[0]) * p[1];
    }
    bool isPositive = area2 > 0;
    if (isPositive != positive) {
      for (int i = 0, j = n - 1; i < j; i++, j--) {
        int16_t *a = &pts_[(curContourStart_ + i) * 2], *b = &pts_[(curContourStart_ + j) * 2];
        int16_t tx = a[0], ty = a[1];
        a[0] = b[0]; a[1] = b[1];
        b[0] = tx; b[1] = ty;
      }
    }
  }
  ends_[nContours_++] = static_cast<uint16_t>(nPts_);
  curContourStart_ = nPts_;
}

void DisplayList::finishBBox(Op &op) const {
  int minx = 1 << 20, miny = 1 << 20, maxx = -(1 << 20), maxy = -(1 << 20);
  for (int i = op.pt0; i < op.pt1; i++) {
    int x = pts_[i * 2], y = pts_[i * 2 + 1];
    if (x < minx) minx = x;
    if (x > maxx) maxx = x;
    if (y < miny) miny = y;
    if (y > maxy) maxy = y;
  }
  if (op.pt1 <= op.pt0) {
    op.bx0 = op.by0 = op.bx1 = op.by1 = 0;
    return;
  }
  float x0 = minx / kFix + op.dx, x1 = maxx / kFix + op.dx, y0 = miny / kFix + op.dy, y1 = maxy / kFix + op.dy;
  auto cl = [](float v) { return static_cast<int16_t>(v < -4000 ? -4000 : (v > 4000 ? 4000 : v)); };
  op.bx0 = cl(floorf(x0));
  op.by0 = cl(floorf(y0));
  op.bx1 = cl(ceilf(x1) + 1);
  op.by1 = cl(ceilf(y1) + 1);
}

bool DisplayList::endOp() {
  if (!inOp_) return false;
  inOp_ = false;
  if (overflow_ || nOps_ >= kMaxOps) {
    nPts_ = cur_.pt0;                               // drop the half-built op
    nContours_ = cur_.ct0;
    return false;
  }
  cur_.pt1 = static_cast<uint16_t>(nPts_);
  cur_.ct1 = static_cast<uint16_t>(nContours_);
  finishBBox(cur_);
  ops_[nOps_++] = cur_;
  return true;
}

bool DisplayList::repeatLast(Rgb color, float dx, float dy) {
  if (nOps_ == 0 || nOps_ >= kMaxOps) {
    overflow_ = true;
    return false;
  }
  Op op = ops_[nOps_ - 1];
  op.color = color;
  op.dx = dx;                                       // absolute offset from the original points
  op.dy = dy;
  finishBBox(op);
  ops_[nOps_++] = op;
  return true;
}

void DisplayList::polygon(const float *xy, int n, bool positive) {
  beginContour();
  for (int i = 0; i < n; i++) point(xy[2 * i], xy[2 * i + 1]);
  endContour(true, positive);
}

void DisplayList::circle(float cx, float cy, float r, bool positive, int segments) {
  int n = segments > 0 ? segments : static_cast<int>(ceilf(kPi / acosf(1.0f - 0.2f / (r > 0.5f ? r : 0.5f))));
  if (n < 8) n = 8;
  if (n > 48) n = 48;
  beginContour();
  for (int i = 0; i < n; i++) {
    float a = 2 * kPi * i / n;
    point(cx + r * cosf(a), cy + r * sinf(a));
  }
  endContour(true, positive);
}

void DisplayList::roundedRect(float x0, float y0, float x1, float y1, float r, bool positive) {
  constexpr int kArc = 8;
  beginContour();
  const float cxs[4] = {x1 - r, x1 - r, x0 + r, x0 + r}, cys[4] = {y0 + r, y1 - r, y1 - r, y0 + r};
  for (int c = 0; c < 4; c++)
    for (int i = 0; i <= kArc; i++) {
      float a = (-90.0f + 90.0f * c + 90.0f * i / kArc) * kPi / 180.0f;
      point(cxs[c] + r * cosf(a), cys[c] + r * sinf(a));
    }
  endContour(true, positive);
}

void DisplayList::polyline(const float *xy, int n, float width, bool roundEnds) {
  if (n < 2) return;
  float h = width / 2;
  for (int i = 0; i + 1 < n; i++) {
    float ax = xy[2 * i], ay = xy[2 * i + 1], bx = xy[2 * i + 2], by = xy[2 * i + 3];
    float dx = bx - ax, dy = by - ay, len = sqrtf(dx * dx + dy * dy);
    if (len < 1e-4f) continue;
    float nx = -dy / len * h, ny = dx / len * h;
    float q[8] = {ax + nx, ay + ny, bx + nx, by + ny, bx - nx, by - ny, ax - nx, ay - ny};
    polygon(q, 4);
  }
  for (int i = roundEnds ? 0 : 1; i < (roundEnds ? n : n - 1); i++) circle(xy[2 * i], xy[2 * i + 1], h);
}

// ---- rasterising --------------------------------------------------------------------------------------------------

void DisplayList::fillOp(const Op &op, int y0, int rows, uint16_t *out) const {
  int ya = op.by0 > y0 ? op.by0 : y0;
  int yb = op.by1 < y0 + rows ? op.by1 : y0 + rows;
  if (ya >= yb || op.bx1 <= 0 || op.bx0 >= kSize) return;

  // 1. the edges that touch this band
  static Edge edges[kMaxBandEdges];                  // single render thread (loop task)
  int ne = 0;
  float bandTop = static_cast<float>(ya), bandBot = static_cast<float>(yb);
  int cStart = op.pt0;
  for (int c = op.ct0; c < op.ct1; c++) {
    int cEnd = ends_[c];
    for (int i = cStart; i < cEnd; i++) {
      int j = (i + 1 < cEnd) ? i + 1 : cStart;
      float x0 = pts_[i * 2] / kFix + op.dx, y0f = pts_[i * 2 + 1] / kFix + op.dy;
      float x1 = pts_[j * 2] / kFix + op.dx, y1f = pts_[j * 2 + 1] / kFix + op.dy;
      if (y0f == y1f) continue;
      Edge e;
      if (y0f < y1f) { e.ymin = y0f; e.ymax = y1f; e.x0 = x0; e.dir = 1; e.slope = (x1 - x0) / (y1f - y0f); }
      else           { e.ymin = y1f; e.ymax = y0f; e.x0 = x1; e.dir = -1; e.slope = (x0 - x1) / (y0f - y1f); }
      if (e.ymax <= bandTop || e.ymin >= bandBot) continue;
      if (ne < kMaxBandEdges) edges[ne++] = e;
    }
    cStart = cEnd;
  }
  if (ne == 0) return;

  // 2. rows
  static float cov[kSize + 2];
  static Crossing cr[160];
  const float inv = 1.0f / kSub;
  for (int y = ya; y < yb; y++) {
    int minx = kSize, maxx = -1;
    for (int k = 0; k < kSub; k++) {
      float ys = y + (k + 0.5f) * inv;
      int nc = 0;
      for (int e = 0; e < ne; e++) {
        const Edge &ed = edges[e];
        if (ys < ed.ymin || ys >= ed.ymax || nc >= 160) continue;
        Crossing c{ed.x0 + (ys - ed.ymin) * ed.slope, ed.dir};
        int p = nc++;
        while (p > 0 && cr[p - 1].x > c.x) { cr[p] = cr[p - 1]; p--; }     // insertion sort
        cr[p] = c;
      }
      int wind = 0;
      float xa = 0;
      for (int i = 0; i < nc; i++) {
        int before = wind;
        wind += cr[i].dir;
        if (before == 0 && wind != 0) {
          xa = cr[i].x;                               // a filled span begins
        } else if (before != 0 && wind == 0) {
          float xb = cr[i].x;                         // ... and ends: add its exact horizontal coverage
          if (xb <= xa || xb <= 0 || xa >= kSize) continue;
          if (xa < 0) xa = 0;
          if (xb > kSize) xb = kSize;
          int ia = static_cast<int>(xa), ib = static_cast<int>(xb);
          if (ia < minx) minx = ia;
          if (ib > maxx) maxx = ib;
          if (ia == ib) {
            cov[ia] += (xb - xa) * inv;
          } else {
            cov[ia] += (ia + 1 - xa) * inv;
            for (int x = ia + 1; x < ib; x++) cov[x] += inv;
            if (xb > ib) cov[ib] += (xb - ib) * inv;
          }
        }
      }
    }
    if (maxx < 0) continue;
    if (maxx >= kSize) maxx = kSize - 1;
    uint16_t *row = out + static_cast<size_t>(y - y0) * kSize;
    for (int x = minx; x <= maxx; x++) {
      float a = cov[x];
      cov[x] = 0;
      if (a <= 0.0f) continue;
      if (a > 1.0f) a = 1.0f;
      if (op.hasClip) a *= clipCoverage(op.clip, x + 0.5f, y + 0.5f);
      int a256 = static_cast<int>(a * 256.0f + 0.5f);
      if (a256 > 0) blendPixel(&row[x], op.color, a256);
    }
    if (maxx + 1 < kSize + 2) cov[maxx + 1] = 0;
  }
}

void DisplayList::renderBand(int y0, int rows, uint16_t *out) const {
  if (rows <= 0) return;
  memset(out, 0, static_cast<size_t>(rows) * kSize * sizeof(uint16_t));      // black
  for (int i = 0; i < nOps_; i++) {
    const Op &op = ops_[i];
    if (op.by1 <= y0 || op.by0 >= y0 + rows) continue;
    fillOp(op, y0, rows, out);
  }
}

}  // namespace mnui
