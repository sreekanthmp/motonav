#include "ui.h"

#include <U8g2lib.h>
#include <Wire.h>
#include <math.h>
#include <stdio.h>

#include "config.h"

#ifdef OLED_SH1106
static U8G2_SH1106_128X64_NONAME_F_HW_I2C u8g2(U8G2_R0, U8X8_PIN_NONE, OLED_SCL_PIN, OLED_SDA_PIN);
#else
static U8G2_SSD1306_128X64_NONAME_F_HW_I2C u8g2(U8G2_R0, U8X8_PIN_NONE, OLED_SCL_PIN, OLED_SDA_PIN);
#endif

// Layout (128x64): arrow area x 0..47, y 0..47 | text column x 52..127 | status bar y 50..63
static const int ARROW_W = 48;
static const int COL_X = 52;
static const int COL_W = 76;
static const int BAR_Y = 49;

// ---------------------------------------------------------------------------
// Formatting helpers
// ---------------------------------------------------------------------------
static void fmtDistance(char *out, size_t n, uint32_t m) {
  if (m < 100)        snprintf(out, n, "%u m", (unsigned)((m + 2) / 5 * 5));
  else if (m < 1000)  snprintf(out, n, "%u m", (unsigned)((m + 5) / 10 * 10));
  else if (m < 10000) snprintf(out, n, "%.1f km", m / 1000.0f);
  else                snprintf(out, n, "%u km", (unsigned)((m + 500) / 1000));
}

static void fmtRemaining(char *out, size_t n, uint32_t m) {
  if (m < 1000)       snprintf(out, n, "%um", (unsigned)m);
  else if (m < 100000) snprintf(out, n, "%.1fkm", m / 1000.0f);
  else                snprintf(out, n, "%ukm", (unsigned)((m + 500) / 1000));
}

static void fmtEta(char *out, size_t n, uint16_t min) {
  if (min >= 60) snprintf(out, n, "%uh%02u", (unsigned)(min / 60), (unsigned)(min % 60));
  else           snprintf(out, n, "%umin", (unsigned)min);
}

// ---------------------------------------------------------------------------
// Drawing helpers
// ---------------------------------------------------------------------------
static void thickLine(int x0, int y0, int x1, int y1) {
  u8g2.drawLine(x0, y0, x1, y1);
  u8g2.drawLine(x0 + 1, y0, x1 + 1, y1);
  u8g2.drawLine(x0, y0 + 1, x1, y1 + 1);
  u8g2.drawLine(x0 + 1, y0 + 1, x1 + 1, y1 + 1);
}

// Arrow head with its tip at (tx, ty), pointing along heading `deg` (0 = up, clockwise).
static void arrowHead(float tx, float ty, float deg) {
  float a = deg * (float)M_PI / 180.0f;
  float dx = sinf(a), dy = -cosf(a);       // direction of travel
  float px = cosf(a), py = sinf(a);        // perpendicular
  float bx = tx - dx * 10, by = ty - dy * 10;
  u8g2.drawTriangle((int)lroundf(tx), (int)lroundf(ty),
                    (int)lroundf(bx + px * 7), (int)lroundf(by + py * 7),
                    (int)lroundf(bx - px * 7), (int)lroundf(by - py * 7));
}

// Classic turn arrow: up the road, then bend by `deg` (negative = left, positive = right).
static void drawTurnArrow(float deg) {
  const float cx = 24, bendY = 30, len = 17;
  float a = deg * (float)M_PI / 180.0f;
  float tx = cx + sinf(a) * len, ty = bendY - cosf(a) * len;
  float shortenX = sinf(a) * 6, shortenY = -cosf(a) * 6;
  thickLine((int)cx, 46, (int)cx, (int)bendY);
  thickLine((int)cx, (int)bendY, (int)lroundf(tx - shortenX), (int)lroundf(ty - shortenY));
  arrowHead(tx, ty, deg);
}

static void drawUTurn(bool right) {
  auto mx = [&](int x) { return right ? x : ARROW_W - x; };
  thickLine(mx(32), 46, mx(32), 16);
  thickLine(mx(32), 16, mx(16), 16);
  thickLine(mx(16), 16, mx(16), 30);
  arrowHead((float)mx(16), 40.0f, 180.0f);
}

static void drawRoundabout(uint8_t exitNo) {
  u8g2.drawCircle(24, 26, 11);
  u8g2.drawCircle(24, 26, 10);
  thickLine(24, 46, 24, 37);
  thickLine(24, 15, 24, 8);
  arrowHead(24, 2, 0);
  if (exitNo > 0) {
    char b[4];
    snprintf(b, sizeof(b), "%u", exitNo);
    u8g2.setFont(u8g2_font_helvB12_tf);
    u8g2.drawStr(24 - u8g2.getStrWidth(b) / 2, 32, b);
  }
}

static void drawFlag(bool mirror) {
  auto mx = [&](int x) { return mirror ? ARROW_W - x : x; };
  thickLine(mx(14), 6, mx(14), 44);
  u8g2.drawTriangle(mx(16), 6, mx(38), 15, mx(16), 24);
}

static void drawManeuver(uint8_t m, uint8_t exitNo) {
  switch (m) {
    case MN_MAN_STRAIGHT:     drawTurnArrow(0); break;
    case MN_MAN_DEPART:       drawTurnArrow(0); break;
    case MN_MAN_SLIGHT_LEFT:  drawTurnArrow(-35); break;
    case MN_MAN_LEFT:         drawTurnArrow(-90); break;
    case MN_MAN_SHARP_LEFT:   drawTurnArrow(-135); break;
    case MN_MAN_SLIGHT_RIGHT: drawTurnArrow(35); break;
    case MN_MAN_RIGHT:        drawTurnArrow(90); break;
    case MN_MAN_SHARP_RIGHT:  drawTurnArrow(135); break;
    case MN_MAN_KEEP_LEFT:    drawTurnArrow(-20); break;
    case MN_MAN_KEEP_RIGHT:   drawTurnArrow(20); break;
    case MN_MAN_RAMP_LEFT:    drawTurnArrow(-35); break;
    case MN_MAN_RAMP_RIGHT:   drawTurnArrow(35); break;
    case MN_MAN_MERGE:        drawTurnArrow(20); break;
    case MN_MAN_UTURN_LEFT:   drawUTurn(false); break;
    case MN_MAN_UTURN_RIGHT:  drawUTurn(true); break;
    case MN_MAN_ROUNDABOUT:   drawRoundabout(exitNo); break;
    case MN_MAN_ARRIVE:
    case MN_MAN_ARRIVE_RIGHT: drawFlag(false); break;
    case MN_MAN_ARRIVE_LEFT:  drawFlag(true); break;
    case MN_MAN_FERRY:
      u8g2.setFont(u8g2_font_helvB10_tf);
      u8g2.drawStr(2, 30, "FERRY");
      break;
    default:
      thickLine(10, 26, 38, 26);   // no maneuver info yet
      break;
  }
}

// Street name that scrolls horizontally when it does not fit in the column.
static void drawStreet(const char *s, int x, int baseline, int w) {
  u8g2.setFont(u8g2_font_5x8_tf);
  int tw = u8g2.getStrWidth(s);
  u8g2.setClipWindow(x, baseline - 9, x + w, baseline + 2);
  if (tw <= w) {
    u8g2.drawStr(x, baseline, s);
  } else {
    int span = tw + 24;
    int off = (millis() / 55) % span;
    u8g2.drawStr(x - off, baseline, s);
    u8g2.drawStr(x - off + span, baseline, s);
  }
  u8g2.setMaxClipWindow();
}

static void drawCentered(const char *s, int baseline, const uint8_t *font) {
  u8g2.setFont(font);
  u8g2.drawStr((128 - u8g2.getStrWidth(s)) / 2, baseline, s);
}

static void drawStatusBar(const MotoNavCore &c) {
  char sp[12], eta[10], rem[12];
  snprintf(sp, sizeof(sp), "%ukm/h", (unsigned)((c.speed_kmh_x10 + 5) / 10));
  fmtEta(eta, sizeof(eta), c.eta_minutes);
  fmtRemaining(rem, sizeof(rem), c.remaining_dist_m);

  u8g2.drawHLine(0, BAR_Y, 128);
  u8g2.setFont(u8g2_font_6x10_tf);
  u8g2.drawStr(0, 61, sp);
  u8g2.drawStr((128 - u8g2.getStrWidth(eta)) / 2, 61, eta);
  u8g2.drawStr(128 - u8g2.getStrWidth(rem), 61, rem);
}

// ---------------------------------------------------------------------------
// Screens
// ---------------------------------------------------------------------------
static void screenWaiting(bool lost) {
  drawCentered("MotoNav", 24, u8g2_font_helvB14_tf);
  drawCentered(lost ? "Phone lost" : "Waiting for phone", 42, u8g2_font_6x10_tf);
  // little animated dots so you can see the firmware is alive
  int n = (millis() / 400) % 4;
  char dots[5] = "    ";
  for (int i = 0; i < n; i++) dots[i] = '.';
  drawCentered(dots, 58, u8g2_font_6x10_tf);
}

static void screenConnected() {
  drawCentered("Connected", 26, u8g2_font_helvB12_tf);
  drawCentered("Start navigation", 44, u8g2_font_6x10_tf);
  drawCentered("on your phone", 56, u8g2_font_6x10_tf);
}

static void screenNav(const NavSnapshot &s, uint32_t dist) {
  const MotoNavCore &c = s.core;
  drawManeuver(c.maneuver, c.roundabout_exit);

  char d[16];
  fmtDistance(d, sizeof(d), dist);
  u8g2.setFont(u8g2_font_logisoso16_tf);
  u8g2.drawStr(COL_X, 22, d);

  drawStreet(s.street, COL_X, 38, COL_W);
  drawStatusBar(c);
}

static void screenMessage(const NavSnapshot &s, const char *title, const char *sub) {
  drawCentered(title, 24, u8g2_font_helvB12_tf);
  drawCentered(sub, 40, u8g2_font_6x10_tf);
  drawStatusBar(s.core);
}

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------
// Scans the I2C bus, lists every device it hears and returns the OLED address (0x3C or 0x3D), or 0.
static uint8_t detectOledAddress() {
  Wire.begin(OLED_SDA_PIN, OLED_SCL_PIN);
  Serial.printf("[oled] scanning I2C bus (SDA=GPIO%d, SCL=GPIO%d)...\n", OLED_SDA_PIN, OLED_SCL_PIN);
  uint8_t found = 0;
  int count = 0;
  for (uint8_t addr = 0x08; addr < 0x78; addr++) {
    Wire.beginTransmission(addr);
    if (Wire.endTransmission() == 0) {
      count++;
      Serial.printf("[oled] I2C device found at 0x%02X\n", addr);
      if (!found && (addr == 0x3C || addr == 0x3D)) found = addr;
    }
  }
  if (count == 0) {
    Serial.println("[oled] NO I2C DEVICE FOUND -> check wiring: VCC=3V3, GND=GND, SDA=GPIO21, SCL=GPIO22 "
                   "(and that the wires are firmly in the pins)");
  } else if (!found) {
    Serial.println("[oled] devices found, but none at 0x3C/0x3D -> is this really an SSD1306 OLED?");
  }
  return found;
}

void uiInit() {
  uint8_t addr = detectOledAddress();
  if (!addr) addr = OLED_I2C_ADDR;   // fall back to the configured address
  Serial.printf("[oled] using address 0x%02X\n", addr);
  u8g2.setI2CAddress(addr << 1);
  u8g2.begin();
  u8g2.setBusClock(400000);
  u8g2.clearBuffer();
  drawCentered("MotoNav", 36, u8g2_font_helvB14_tf);
  u8g2.sendBuffer();
}

void uiRender(const NavSnapshot &s) {
  uint32_t now = millis();
  u8g2.clearBuffer();

  bool haveData = s.coreRxMs != 0;
  bool stale = haveData && !s.demo && (now - s.coreRxMs > NAV_STALE_MS);

  if (s.demo) {
    screenNav(s, s.core.dist_to_maneuver_m);
  } else if (!s.connected) {
    screenWaiting(haveData);                 // had data before => we lost the phone
  } else if (!haveData) {
    screenConnected();
  } else if (stale) {
    screenWaiting(true);
  } else {
    switch (s.core.mode) {
      case MN_MODE_NAV: {
        // Count the distance down between 1 Hz packets so the number moves smoothly.
        uint32_t dt = now - s.coreRxMs;
        if (dt > NAV_INTERP_MAX_MS) dt = NAV_INTERP_MAX_MS;
        float mps = (s.core.speed_kmh_x10 / 10.0f) / 3.6f;
        float d = (float)s.core.dist_to_maneuver_m - mps * (dt / 1000.0f);
        screenNav(s, d > 0 ? (uint32_t)d : 0);
        break;
      }
      case MN_MODE_REROUTING: screenMessage(s, "Rerouting", "please wait..."); break;
      case MN_MODE_OFF_ROUTE: screenMessage(s, "Off route", "recalculating"); break;
      case MN_MODE_ARRIVED:   screenMessage(s, "Arrived", "you are here"); break;
      default:                screenConnected(); break;
    }
  }

  u8g2.sendBuffer();
}
