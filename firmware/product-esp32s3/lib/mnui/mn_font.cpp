#include "mn_font.h"

#include <math.h>

namespace mnui {

using namespace fontdata;

namespace {
inline const GlyphRec *glyph(unsigned char ch) {
  if (ch < kFirst || ch >= kFirst + kCount) return nullptr;
  return &kGlyphs[ch - kFirst];
}

inline int kern(unsigned char a, unsigned char b) {
  int lo = 0, hi = kKernCount - 1;                 // table is sorted by (left, right)
  int key = (a << 8) | b;
  while (lo <= hi) {
    int mid = (lo + hi) / 2;
    int k = (kKern[mid].left << 8) | kKern[mid].right;
    if (k == key) return kKern[mid].value;
    if (k < key) lo = mid + 1; else hi = mid - 1;
  }
  return 0;
}

struct Xf {
  float x0, base, s;
  float X(int fx) const { return x0 + fx * s; }
  float Y(int fy) const { return base - fy * s; }
};

// Emit a quadratic curve as `n` line segments (the start point is already in the contour).
inline void quad(DisplayList &dl, const Xf &t, float ax, float ay, float cx, float cy, float bx, float by, int n) {
  for (int i = 1; i <= n; i++) {
    float u = static_cast<float>(i) / n, v = 1 - u;
    dl.point(v * v * ax + 2 * v * u * cx + u * u * bx, v * v * ay + 2 * v * u * cy + u * u * by);
  }
  (void)t;
}

void addGlyph(DisplayList &dl, const GlyphRec &g, float x, float baseline, float size) {
  Xf t{x, baseline, size / kUnitsPerEm};
  int segs = static_cast<int>(ceilf(sqrtf(size / 10.0f))) + 1;       // curve flattening: 2..5 segments per curve
  if (segs < 2) segs = 2;
  if (segs > 6) segs = 6;
  int start = 0;
  for (int c = 0; c < g.nCts; c++) {
    int end = kEnds[g.ct0 + c];
    int n = end - start;
    const int16_t *p = &kPts[(g.pt0 + start) * 2];
    const uint8_t *on = &kOn[g.pt0 + start];
    start = end;
    if (n < 2) continue;
    // find a starting on-curve point (or synthesise one between two off-curve points)
    int first = -1;
    for (int i = 0; i < n; i++) if (on[i]) { first = i; break; }
    float sx, sy;
    int idx;                                         // next point to look at
    if (first >= 0) { sx = t.X(p[first * 2]); sy = t.Y(p[first * 2 + 1]); idx = first + 1; }
    else { sx = t.X((p[0] + p[2]) / 2); sy = t.Y((p[1] + p[3]) / 2); idx = 1; first = 0; }
    dl.beginContour();
    dl.point(sx, sy);
    float cx = sx, cy = sy;                          // current on-curve point
    int count = (on[first] ? n - 1 : n);             // points still to consume
    for (int k = 0; k < count; k++) {
      int i = (idx + k) % n;
      float px = t.X(p[i * 2]), py = t.Y(p[i * 2 + 1]);
      if (on[i]) {
        dl.point(px, py);
        cx = px; cy = py;
      } else {
        int j = (i + 1) % n;
        float nx, ny;
        bool lastOne = (k == count - 1);
        if (!lastOne && on[j]) { nx = t.X(p[j * 2]); ny = t.Y(p[j * 2 + 1]); k++; }       // off then on: quad to that point
        else if (lastOne) { nx = sx; ny = sy; }                                           // closes onto the start
        else { nx = (px + t.X(p[j * 2])) / 2; ny = (py + t.Y(p[j * 2 + 1])) / 2; }          // off then off: implied on-curve midpoint
        quad(dl, t, cx, cy, px, py, nx, ny, segs);
        cx = nx; cy = ny;
      }
    }
    dl.endContour(false);                            // outlines already wind correctly
  }
}
}  // namespace

float textWidth(const char *s, float size) {
  float w = 0;
  unsigned char prev = 0;
  for (; *s; s++) {
    unsigned char ch = static_cast<unsigned char>(*s);
    const GlyphRec *g = glyph(ch);
    if (!g) continue;
    if (prev) w += kern(prev, ch);
    w += g->adv;
    prev = ch;
  }
  return w * size / kUnitsPerEm;
}

void addText(DisplayList &dl, const char *s, float x, float baseline, float size) {
  float pen = x;
  float k = size / kUnitsPerEm;
  unsigned char prev = 0;
  for (; *s; s++) {
    unsigned char ch = static_cast<unsigned char>(*s);
    const GlyphRec *g = glyph(ch);
    if (!g) continue;
    if (prev) pen += kern(prev, ch) * k;
    if (g->nPts) addGlyph(dl, *g, pen, baseline, size);
    pen += g->adv * k;
    prev = ch;
  }
}

float middleToBaseline(float size) { return (kAscent - kDescent) / 2.0f / kUnitsPerEm * size; }

}  // namespace mnui
