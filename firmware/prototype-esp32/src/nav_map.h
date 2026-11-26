#pragma once

#include <Arduino.h>

#include "nav_route.h"

// The map feed from the phone: the route window ahead (NavRoute) and the rider's position (NavPos).
// Written from the BLE task, read from loop(). Nothing is drawn from it yet; the display code can read it
// with navMapGet() once the colour round panel is in place.
struct NavMapSnapshot {
  bool hasRoute;                  // a complete, valid route window has arrived
  motonav::RouteWindow route;
  uint32_t routeRxMs;             // millis() when it arrived
  bool hasPos;
  MotoNavPos pos;
  uint32_t posRxMs;
};

void navMapInit();
void navMapReset();                                          // phone disconnected: forget route and position
bool navMapOnRouteWrite(const uint8_t *data, size_t len);    // true when this write completed a new window
bool navMapOnPosWrite(const uint8_t *data, size_t len);      // false if rejected
void navMapGet(NavMapSnapshot *out);                         // copies about 0.8 KB: call a few times a second at most
