// MotoNav prototype firmware — ESP32 DevKit V1 + SSD1306 OLED.
//
// Phone (Android app) -> BLE -> NavCore/NavStreet -> this firmware -> OLED.
// Press the BOOT button while no phone is connected to cycle demo screens, so you
// can test the display on its own (milestone M0).

#include <Arduino.h>

#include "ble_service.h"
#include "config.h"
#include "nav_protocol.h"
#include "nav_state.h"
#include "ui.h"

struct Demo {
  uint8_t maneuver;
  uint8_t exitNo;
  uint32_t dist;
  uint32_t remaining;
  uint16_t eta;
  uint16_t speedX10;
  const char *street;
};

static const Demo DEMOS[] = {
    {MN_MAN_STRAIGHT,     0, 1800, 12400, 21, 480, "MG Road"},
    {MN_MAN_LEFT,         0,  300,  9800, 18, 420, "Brigade Road"},
    {MN_MAN_RIGHT,        0,   80,  5200, 12, 350, "Residency Road Extension"},
    {MN_MAN_SLIGHT_RIGHT, 0,  650,  4100, 10, 500, "Outer Ring Road"},
    {MN_MAN_ROUNDABOUT,   2,  120,  3000,  8, 300, "Silk Board Junction"},
    {MN_MAN_UTURN_LEFT,   0,   40,  2500,  7, 200, "Hosur Road"},
    {MN_MAN_ARRIVE,       0,   20,    20,  1,  50, "Destination"},
};
static const int N_DEMOS = sizeof(DEMOS) / sizeof(DEMOS[0]);
static int g_demoIdx = -1;

static void showNextDemo() {
  g_demoIdx = (g_demoIdx + 1) % N_DEMOS;
  const Demo &d = DEMOS[g_demoIdx];
  MotoNavCore c = {};
  c.version = MOTONAV_PROTO_VERSION;
  c.mode = MN_MODE_NAV;
  c.maneuver = d.maneuver;
  c.roundabout_exit = d.exitNo;
  c.dist_to_maneuver_m = d.dist;
  c.remaining_dist_m = d.remaining;
  c.eta_minutes = d.eta;
  c.speed_kmh_x10 = d.speedX10;
  navStateSetDemo(c, d.street);
  Serial.printf("[demo] %d/%d\n", g_demoIdx + 1, N_DEMOS);
}

static void onButton() {
  if (bleIsConnected()) {
    bleNotifyButton(1);
  } else {
    showNextDemo();
  }
}

void setup() {
  Serial.begin(115200);
  delay(100);
  Serial.println("\nMotoNav prototype firmware");

  pinMode(BUTTON_PIN, INPUT_PULLUP);
  navStateInit();
  uiInit();
  bleInit();
}

void loop() {
  static bool prevLevel = true;
  static uint32_t lastEdge = 0;
  static uint32_t lastFrame = 0;
  uint32_t now = millis();

  bool level = digitalRead(BUTTON_PIN);
  if (level != prevLevel && now - lastEdge > 40) {
    lastEdge = now;
    prevLevel = level;
    if (!level) onButton();   // pressed (active low)
  }

  if (now - lastFrame >= UI_FRAME_MS) {
    lastFrame = now;
    uiRender(navStateGet());
  }
}
