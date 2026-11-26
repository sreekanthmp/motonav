#include "nav_map.h"

static motonav::RouteAssembler g_asm;
static NavMapSnapshot g_map;
static portMUX_TYPE g_mapMux = portMUX_INITIALIZER_UNLOCKED;

void navMapInit() {
  memset(&g_map, 0, sizeof(g_map));
}

void navMapReset() {
  portENTER_CRITICAL(&g_mapMux);
  g_map.hasRoute = false;
  g_map.hasPos = false;
  portEXIT_CRITICAL(&g_mapMux);
}

bool navMapOnRouteWrite(const uint8_t *data, size_t len) {
  portENTER_CRITICAL(&g_mapMux);       // short: at most 96 points are decoded
  bool done = g_asm.feed(data, len);
  if (done) {
    g_map.route = g_asm.window();
    g_map.routeRxMs = millis();
    g_map.hasRoute = true;
  }
  portEXIT_CRITICAL(&g_mapMux);
  return done;
}

bool navMapOnPosWrite(const uint8_t *data, size_t len) {
  MotoNavPos p;
  if (!motonav::decodePos(data, len, &p)) return false;
  portENTER_CRITICAL(&g_mapMux);
  g_map.pos = p;
  g_map.posRxMs = millis();
  g_map.hasPos = true;
  portEXIT_CRITICAL(&g_mapMux);
  return true;
}

void navMapGet(NavMapSnapshot *out) {
  portENTER_CRITICAL(&g_mapMux);
  *out = g_map;
  portEXIT_CRITICAL(&g_mapMux);
}
