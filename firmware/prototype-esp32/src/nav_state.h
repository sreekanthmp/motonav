#pragma once

#include <Arduino.h>
#include "nav_protocol.h"

// Latest state received from the phone. Written from the BLE task, read from loop().
struct NavSnapshot {
  MotoNavCore core;
  char     street[MOTONAV_STREET_MAX + 1];
  uint32_t coreRxMs;     // millis() when the last valid NavCore arrived; 0 = never
  bool     connected;    // a phone is connected over BLE
  bool     demo;         // showing a built-in demo screen
};

void        navStateInit();
bool        navStateSetCore(const uint8_t *data, size_t len);    // false if rejected
void        navStateSetStreet(const uint8_t *data, size_t len);
void        navStateSetConnected(bool connected);
void        navStateSetDemo(const MotoNavCore &core, const char *street);
void        navStateClearDemo();
NavSnapshot navStateGet();
