#pragma once

// ---- Wiring (ESP32 DevKit V1) -----------------------------------------------
// OLED VCC -> 3V3, GND -> GND, SDA -> GPIO21, SCL -> GPIO22
#define OLED_SDA_PIN   21
#define OLED_SCL_PIN   22
// Most 0.96" SSD1306 modules are 0x3C. If the screen stays blank, try 0x3D.
#define OLED_I2C_ADDR  0x3C

// BOOT button on the DevKit (GPIO0, active low).
//  - not connected to a phone: cycles the built-in demo screens
//  - connected: sends a button event to the phone
#define BUTTON_PIN     0

// ---- Behaviour --------------------------------------------------------------
#define UI_FRAME_MS        66      // ~15 fps
#define NAV_STALE_MS       5000    // no NavCore for this long => "Phone lost"
#define NAV_INTERP_MAX_MS  3000    // never extrapolate distance longer than this
