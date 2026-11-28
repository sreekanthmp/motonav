// Anti-aliased band rasteriser for the 412 x 412 round panel. Portable C++ (no Arduino/ESP headers): the same code
// runs on the ESP32-S3 and on a PC (test_host/), so the PC can prove what the panel will show.
//
// Everything on screen is a list of filled paths (text outlines, arrows, route line ... all of them) drawn in order.
// A frame is built once into a DisplayList, then rendered band by band (kBandRows rows at a time) into a small RGB565
// buffer that the panel driver sends out. A full 412 x 412 frame buffer would need 339 KB: more than the free internal
// RAM of an ESP32-S3 that is also running Bluetooth.
//
// Fill rule: non-zero winding. Anti-aliasing: kSub sub-scanlines per pixel row, exact horizontal coverage.
#pragma once

#include <stddef.h>
#include <stdint.h>

namespace mnui {

constexpr int kSize = 412;          // panel width = height in pixels
constexpr int kBandRows = 16;       // rows per band
constexpr int kSub = 8;             // vertical sub-samples per pixel
constexpr int kMaxPts = 9000;       // point pool (4 bytes each)
constexpr int kMaxContours = 1500;
constexpr int kMaxOps = 192;
constexpr int kMaxBandEdges = 1536; // edges of one op that touch one band

struct Rgb {
  uint8_t r, g, b;
};
constexpr Rgb rgb(uint8_t r, uint8_t g, uint8_t b) { return Rgb{r, g, b}; }

// Rounded rectangle used to clip an op (the map window). Coverage comes from its signed distance, so the edge is anti-aliased.
struct ClipRR {
  float cx, cy, hw, hh, r;
};

class DisplayList {
 public:
  void clear();

  // ---- building ----
  // Start a path. Everything until endOp() belongs to it. `clip` (may be null) is copied.
  bool beginOp(Rgb color, const ClipRR* clip = nullptr);
  void beginContour();
  void point(float x, float y);
  // Close the contour. autoOrient: make its winding positive (or negative with positive=false) so overlapping pieces unite
  // and holes cut; pass false for font outlines, which already wind correctly.
  void endContour(bool autoOrient = true, bool positive = true);
  bool endOp();                        // false if a pool overflowed (the op is dropped, nothing else breaks)
  // Another op that draws the points of the last op again with a different colour and an offset (in pixels, from the
  // original points). Used for halos: no points are copied.
  bool repeatLast(Rgb color, float dx, float dy);

  // ---- convenience shapes (each is one complete contour; call between beginOp/endOp) ----
  void polygon(const float *xy, int n, bool positive = true);
  void circle(float cx, float cy, float r, bool positive = true, int segments = 0);
  void roundedRect(float x0, float y0, float x1, float y1, float r, bool positive = true);
  // A thick line: one quad per segment plus a disc at each interior vertex (round joins, butt ends).
  void polyline(const float *xy, int n, float width, bool roundEnds = false);

  // ---- output ----
  // Renders rows [y0, y0 + rows) into out (kSize * rows RGB565, native byte order), starting from black.
  void renderBand(int y0, int rows, uint16_t *out) const;

  int opCount() const { return nOps_; }
  int pointCount() const { return nPts_; }
  bool overflowed() const { return overflow_; }

 private:
  struct Op {
    uint16_t pt0, pt1;     // point range
    uint16_t ct0, ct1;     // contour range (indices into ends_)
    Rgb color;
    float dx, dy;          // offset added at raster time
    bool hasClip;
    ClipRR clip;
    int16_t bx0, by0, bx1, by1;   // pixel bounding box (inclusive-exclusive), after offset
  };
  int16_t pts_[kMaxPts * 2];       // x, y in 1/16 pixel
  uint16_t ends_[kMaxContours];    // exclusive end point index of each contour
  Op ops_[kMaxOps];
  int nPts_ = 0, nContours_ = 0, nOps_ = 0;
  int curContourStart_ = 0;
  bool inOp_ = false, overflow_ = false;
  Op cur_;

  void finishBBox(Op &op) const;
  void fillOp(const Op &op, int y0, int rows, uint16_t *out) const;
};

}  // namespace mnui
