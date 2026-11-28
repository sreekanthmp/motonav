// The screens of the round display (port of tools/minimap_sim/render.py, layout B).
#pragma once

#include <stdint.h>

#include "mn_raster.h"
#include "nav_protocol.h"
#include "nav_route.h"

namespace mnui {

constexpr float kTurnSwitchM = 300.0f;   // layout B: large-arrow screen at or below this distance to the manoeuvre

// What the display needs from the rest of the firmware.
struct UiInput {
  bool connected = false;                // a phone is connected over BLE
  bool hasCore = false;
  MotoNavCore core{};
  char street[MOTONAV_STREET_MAX + 1] = {0};
  const motonav::RouteWindow *route = nullptr;   // null until a complete window has arrived
  bool hasPos = false;
  MotoNavPos pos{};
};

class Ui {
 public:
  // Builds the display list for the current state. Layout B: the full screen (arrow, distance, street, map, distance
  // left and minutes left) while the next manoeuvre is far, a large-arrow screen without the map when it is near, and
  // status screens (rerouting, arrived, waiting for phone). The protocol's OFF_ROUTE mode shows the rerouting screen.
  void update(const UiInput &in);
  // Forget the map zoom (the zoom is kept from frame to frame so it does not flicker).
  void resetView() { mpp_ = 0; }
  void renderBand(int y0, int rows, uint16_t *out) const { dl_.renderBand(y0, rows, out); }
  const DisplayList &list() const { return dl_; }
  bool lastWasLarge() const { return large_; }
  float mapMetersPerPixel() const { return lastMpp_; }

 private:
  DisplayList dl_;
  float mpp_ = 0;          // zoom kept between frames (0 = none)
  float lastMpp_ = 0;      // zoom used by the last map (0 = no map drawn)
  bool large_ = false;

  void navFull(const UiInput &in);
  void navLarge(const UiInput &in);
  void message(int kind, const char *line1, const char *line2);
  void drawMap(const UiInput &in, float x0, float y0, float x1, float y1, float lookAheadM);
};

// Exposed for tests.
void formatDistance(uint32_t meters, char *value, int cap, const char **unit);
void formatEta(uint32_t minutes, char *out, int cap);
bool drawIcon(DisplayList &dl, float cx, float cy, float size, uint8_t maneuver, int exitNo, Rgb color);

}  // namespace mnui
