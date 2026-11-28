// Stand-in panel driver: accepts pixels and throws them away, counting what it was given.
//
// Why it exists: everything else (Bluetooth, state, layout, drawing every band) runs for real on an ESP32-S3, so the
// frame timing and memory use can be measured now. A real driver replaces this file (see README: "What is missing").
#include <Arduino.h>

#include "panel.h"

namespace {
class PanelStub : public Panel {
 public:
  bool begin() override {
    Serial.println("[panel] STUB driver: pixels are discarded (no panel controller driver yet)");
    return true;
  }
  void writeRect(int, int, int w, int h, const uint16_t *) override { pixels_ += static_cast<uint32_t>(w) * h; }
  void setBrightness(uint8_t) override {}
  uint32_t pixels_ = 0;
};
PanelStub g_panel;
}  // namespace

Panel *panelCreate() { return &g_panel; }
