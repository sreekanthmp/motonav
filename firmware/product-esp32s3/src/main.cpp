// MotoNav product firmware: phone -> BLE -> state -> layout B -> 412 x 412 round panel.
//
// Phone writes (NavCore, NavStreet, NavRoute, NavPos) are stored by the prototype's BLE/state code. About once a second,
// or when something changed, the current state is turned into a display list (lib/mnui) and drawn band by band to the panel.
#include <Arduino.h>

#include "ble_service.h"
#include "mnui.h"
#include "nav_map.h"
#include "nav_protocol.h"
#include "nav_state.h"
#include "panel.h"

#define NAV_STALE_MS 5000        // no NavCore for this long: show "Waiting for phone" again
#define MIN_FRAME_MS 250         // never redraw faster than this
#define MAX_FRAME_MS 1000        // always redraw at least this often (the clock-less ETA still changes)

static mnui::Ui g_ui;
static Panel *g_panel;
static uint16_t g_band[mnui::kSize * mnui::kBandRows];
static NavMapSnapshot g_map;     // ~0.8 KB, read once per frame

static uint32_t g_lastFrame = 0, g_frames = 0, g_sumBuild = 0, g_sumRender = 0, g_sumWrite = 0;
static uint32_t g_lastKey = 0;

static void drawFrame() {
  NavSnapshot s = navStateGet();
  navMapGet(&g_map);

  mnui::UiInput in;
  in.connected = s.connected;
  in.hasCore = s.coreRxMs != 0 && (millis() - s.coreRxMs) < NAV_STALE_MS;
  in.core = s.core;
  memcpy(in.street, s.street, sizeof(in.street));
  in.route = g_map.hasRoute ? &g_map.route : nullptr;
  in.hasPos = g_map.hasPos;
  in.pos = g_map.pos;

  uint32_t t0 = micros();
  g_ui.update(in);
  uint32_t t1 = micros();
  uint32_t tRender = 0, tWrite = 0;
  for (int y = 0; y < mnui::kSize; y += mnui::kBandRows) {
    int rows = mnui::kSize - y < mnui::kBandRows ? mnui::kSize - y : mnui::kBandRows;
    uint32_t a = micros();
    g_ui.renderBand(y, rows, g_band);
    uint32_t b = micros();
    g_panel->writeRect(0, y, mnui::kSize, rows, g_band);
    uint32_t c = micros();
    tRender += b - a;
    tWrite += c - b;
  }
  g_sumBuild += t1 - t0;
  g_sumRender += tRender;
  g_sumWrite += tWrite;
  if (++g_frames % 10 == 0) {
    Serial.printf("[ui] %u frames, avg build %u us, render %u us, write %u us; ops %d, points %d%s; free heap %u\n",
                  static_cast<unsigned>(g_frames), static_cast<unsigned>(g_sumBuild / 10), static_cast<unsigned>(g_sumRender / 10),
                  static_cast<unsigned>(g_sumWrite / 10), g_ui.list().opCount(), g_ui.list().pointCount(),
                  g_ui.list().overflowed() ? " OVERFLOW" : "", static_cast<unsigned>(ESP.getFreeHeap()));
    g_sumBuild = g_sumRender = g_sumWrite = 0;
  }
}

void setup() {
  Serial.begin(115200);
  delay(100);
  Serial.println("\nMotoNav product firmware (layout B, 412x412)");
  navStateInit();
  navMapInit();
  g_panel = panelCreate();
  if (!g_panel->begin()) Serial.println("[panel] begin() failed");
  g_panel->setBrightness(255);
  bleInit();
  drawFrame();                       // "Waiting for phone"
}

void loop() {
  static uint32_t lastPoll = 0;
  uint32_t now = millis();
  if (now - lastPoll < 100) {                        // look for changes 10 times a second (the map copy is ~0.8 KB)
    delay(5);
    return;
  }
  lastPoll = now;
  NavSnapshot s = navStateGet();
  NavMapSnapshot m;
  navMapGet(&m);
  // Cheap "did anything change" key: connection, NavCore sequence number, route/position arrival times.
  uint32_t key = (s.connected ? 1u : 0u) ^ (static_cast<uint32_t>(s.core.seq) << 1) ^ (m.posRxMs * 2654435761u) ^ (m.routeRxMs * 40503u);
  bool changed = key != g_lastKey;
  if ((changed && now - g_lastFrame >= MIN_FRAME_MS) || now - g_lastFrame >= MAX_FRAME_MS) {
    g_lastFrame = now;
    g_lastKey = key;
    drawFrame();
  }
}
