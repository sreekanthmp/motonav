// Text as filled outlines (Liberation Sans Bold, SIL OFL 1.1). Any pixel size from one small table; no per-size bitmaps.
// Characters: printable ASCII, MN_DOT (middle dot) and MN_ELLIPSIS. The phone only ever sends printable ASCII, so
// those two codes are free for firmware use.
#pragma once

#include <stdint.h>

#include "mn_raster.h"

#define MN_DOT "\x7F"
#define MN_ELLIPSIS "\x80"

namespace mnui {

namespace fontdata {
struct GlyphRec {
  uint16_t adv;      // advance, font units
  uint16_t pt0;      // first point
  uint16_t nPts;
  uint16_t ct0;      // first contour end
  uint16_t nCts;
};
struct KernRec {
  uint8_t left, right;
  int16_t value;
};
extern const int kUnitsPerEm, kAscent, kDescent, kFirst, kCount, kKernCount;
extern const int16_t kPts[];
extern const uint8_t kOn[];
extern const uint16_t kEnds[];
extern const GlyphRec kGlyphs[];
extern const KernRec kKern[];
}  // namespace fontdata

// Width of a string in pixels at a font size in pixels (advances plus kerning).
float textWidth(const char *s, float size);

// Adds the outlines of `s` to the current op: pen starts at (x, baseline), left aligned.
void addText(DisplayList &dl, const char *s, float x, float baseline, float size);

// Offset from the vertical middle of a line of text to its baseline ("mm" anchor): baseline = middle + this.
float middleToBaseline(float size);

}  // namespace mnui
