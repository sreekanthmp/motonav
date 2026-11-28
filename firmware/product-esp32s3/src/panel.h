#pragma once

#include <stdint.h>

// The panel driver: the only part of the display path that depends on the panel's controller IC.
//
// The renderer (lib/mnui) hands over finished rows of RGB565 pixels in native byte order, a band at a time. A driver
// for a QSPI controller sets the write window, swaps bytes if the controller wants big-endian, and sends the pixels
// (ideally by DMA while the next band is rendered).
class Panel {
 public:
  virtual ~Panel() {}
  // Power up, reset and initialise the controller. Returns false if the panel does not answer.
  virtual bool begin() = 0;
  // Write a w x h block at (x, y). `rgb565` is w*h pixels, row after row, valid only for the duration of the call.
  virtual void writeRect(int x, int y, int w, int h, const uint16_t *rgb565) = 0;
  // 0..255 backlight level (a PWM pin driving the backlight boost converter).
  virtual void setBrightness(uint8_t level) = 0;
};

// The panel this firmware is built for.
Panel *panelCreate();
