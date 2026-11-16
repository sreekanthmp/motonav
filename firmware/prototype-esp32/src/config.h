#pragma once

// ---- Wiring (ESP32 DevKit V1) -----------------------------------------------
// OLED VCC -> 3V3, GND -> GND, SDA -> GPIO21, SCL -> GPIO22
#define OLED_SDA_PIN   21
#define OLED_SCL_PIN   22
// Most 0.96" SSD1306 modules are 0x3C. If the screen stays blank, try 0x3D.
#define OLED_I2C_ADDR  0x3C

// I2C speed for the display. 100 kHz is the safe value for jumper wires and weak pull-ups.
// Raise to 400000 later for smoother redraws once everything works.
#define OLED_I2C_HZ    100000

// BOOT button on the DevKit (GPIO0, active low). A LONG press (>= LONG_PRESS_MS) cycles the display driver.
//  - not connected to a phone: cycles the built-in demo screens
//  - connected: sends a button event to the phone
#define BUTTON_PIN     0

// ---- Behaviour --------------------------------------------------------------
#define UI_FRAME_MS        66      // ~15 fps
#define LONG_PRESS_MS      1500    // hold BOOT this long (then release) to switch the display driver
#define NAV_STALE_MS       5000    // no NavCore for this long => "Phone lost"
#define NAV_INTERP_MAX_MS  3000    // never extrapolate distance longer than this
