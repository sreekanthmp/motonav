#include "nav_state.h"

static NavSnapshot g_state;
static portMUX_TYPE g_mux = portMUX_INITIALIZER_UNLOCKED;

void navStateInit() {
  memset(&g_state, 0, sizeof(g_state));
}

bool navStateSetCore(const uint8_t *data, size_t len) {
  if (len < sizeof(MotoNavCore)) return false;
  MotoNavCore c;
  memcpy(&c, data, sizeof(c));
  if (c.version != MOTONAV_PROTO_VERSION) return false;
  if (c.mode > MN_MODE_ARRIVED) return false;

  portENTER_CRITICAL(&g_mux);
  g_state.core = c;
  g_state.coreRxMs = millis();
  g_state.demo = false;   // real data always wins over a demo screen
  portEXIT_CRITICAL(&g_mux);
  return true;
}

void navStateSetStreet(const uint8_t *data, size_t len) {
  char buf[MOTONAV_STREET_MAX + 1];
  size_t n = len > MOTONAV_STREET_MAX ? MOTONAV_STREET_MAX : len;
  size_t j = 0;
  for (size_t i = 0; i < n; i++) {
    uint8_t ch = data[i];
    if (ch == 0) break;
    buf[j++] = (ch >= 0x20 && ch < 0x7F) ? (char)ch : '?';   // OLED font is ASCII-only
  }
  buf[j] = 0;

  portENTER_CRITICAL(&g_mux);
  memcpy(g_state.street, buf, sizeof(buf));
  portEXIT_CRITICAL(&g_mux);
}

void navStateSetConnected(bool connected) {
  portENTER_CRITICAL(&g_mux);
  g_state.connected = connected;
  portEXIT_CRITICAL(&g_mux);
}

void navStateSetDemo(const MotoNavCore &core, const char *street) {
  portENTER_CRITICAL(&g_mux);
  g_state.core = core;
  g_state.coreRxMs = millis();
  g_state.demo = true;
  strncpy(g_state.street, street, MOTONAV_STREET_MAX);
  g_state.street[MOTONAV_STREET_MAX] = 0;
  portEXIT_CRITICAL(&g_mux);
}

void navStateClearDemo() {
  portENTER_CRITICAL(&g_mux);
  g_state.demo = false;
  portEXIT_CRITICAL(&g_mux);
}

NavSnapshot navStateGet() {
  NavSnapshot s;
  portENTER_CRITICAL(&g_mux);
  s = g_state;
  portEXIT_CRITICAL(&g_mux);
  return s;
}
