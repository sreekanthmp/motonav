#pragma once

#include "nav_state.h"

void uiInit();
void uiRender(const NavSnapshot &s);   // call at ~15 fps
