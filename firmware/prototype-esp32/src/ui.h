#pragma once

#include "nav_state.h"

void uiInit();
void uiNextDriver();                   // cycle SSD1306 -> SH1106 -> SSD1306 alt (saved across reboots)
void uiRender(const NavSnapshot &s);   // call at ~15 fps
