#pragma once

#include <Arduino.h>

void bleInit();                    // start GATT server + advertising ("MotoNav")
bool bleIsConnected();
void bleNotifyButton(uint8_t id);  // device -> phone event
