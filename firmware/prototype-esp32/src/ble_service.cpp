#include "ble_service.h"

#include <NimBLEDevice.h>

#include "nav_protocol.h"
#include "nav_state.h"

static NimBLECharacteristic *g_events = nullptr;
static volatile bool g_connected = false;

static void hexdump(const uint8_t *d, size_t n) {
  for (size_t i = 0; i < n; i++) Serial.printf("%02X ", d[i]);
}

class ServerCallbacks : public NimBLEServerCallbacks {
  void onConnect(NimBLEServer *server, NimBLEConnInfo &connInfo) override {
    g_connected = true;
    navStateSetConnected(true);
    Serial.printf("[ble] connected: %s\n", connInfo.getAddress().toString().c_str());
    // Ask for a 30-60 ms connection interval: low latency, still frugal (units of 1.25 ms).
    server->updateConnParams(connInfo.getConnHandle(), 24, 48, 0, 400);
  }

  void onDisconnect(NimBLEServer *server, NimBLEConnInfo &connInfo, int reason) override {
    (void)server;
    (void)connInfo;
    g_connected = false;
    navStateSetConnected(false);
    Serial.printf("[ble] disconnected (reason %d) -> advertising again\n", reason);
    NimBLEDevice::startAdvertising();   // auto-recover: phone can reconnect without a reset
  }
};

class CoreCallbacks : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic *chr, NimBLEConnInfo &connInfo) override {
    (void)connInfo;
    NimBLEAttValue v = chr->getValue();
    bool ok = navStateSetCore(v.data(), v.size());
    Serial.printf("[ble] NavCore %s (%u bytes): ", ok ? "ok" : "REJECTED", (unsigned)v.size());
    hexdump(v.data(), v.size());
    Serial.println();
  }
};

class StreetCallbacks : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic *chr, NimBLEConnInfo &connInfo) override {
    (void)connInfo;
    NimBLEAttValue v = chr->getValue();
    navStateSetStreet(v.data(), v.size());
    Serial.printf("[ble] NavStreet (%u bytes)\n", (unsigned)v.size());
  }
};

static ServerCallbacks serverCb;
static CoreCallbacks coreCb;
static StreetCallbacks streetCb;

void bleInit() {
  NimBLEDevice::init(MOTONAV_DEVICE_NAME);
  NimBLEDevice::setMTU(185);   // larger payloads allowed if the phone asks; NavCore itself stays 18 bytes

  NimBLEServer *server = NimBLEDevice::createServer();
  server->setCallbacks(&serverCb);

  NimBLEService *svc = server->createService(MOTONAV_SERVICE_UUID);

  NimBLECharacteristic *core = svc->createCharacteristic(
      MOTONAV_NAVCORE_UUID, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR);
  core->setCallbacks(&coreCb);

  NimBLECharacteristic *street = svc->createCharacteristic(
      MOTONAV_NAVSTREET_UUID, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR);
  street->setCallbacks(&streetCb);

  g_events = svc->createCharacteristic(MOTONAV_EVENTS_UUID, NIMBLE_PROPERTY::NOTIFY);

  server->start();   // NimBLE 2.x: services are started together with the server

  NimBLEAdvertising *adv = NimBLEDevice::getAdvertising();
  adv->setName(MOTONAV_DEVICE_NAME);
  adv->addServiceUUID(MOTONAV_SERVICE_UUID);
  adv->start();
  Serial.println("[ble] advertising as \"" MOTONAV_DEVICE_NAME "\"");
}

bool bleIsConnected() { return g_connected; }

void bleNotifyButton(uint8_t id) {
  if (!g_events || !g_connected) return;
  uint8_t payload[3] = {MN_EVT_BUTTON, id, 0};
  g_events->setValue(payload, sizeof(payload));
  g_events->notify();
  Serial.printf("[ble] button event %u sent\n", id);
}
