// MotoNav BLE protocol v1 (+ the additive "map feed") — single source of truth shared by the
// firmware (C/C++) and mirrored by the Android app (app/.../NavProtocol.kt). Keep both in sync;
// see protocol/README.md for the human-readable spec. The route stream decoder is in nav_route.h.
#pragma once

#include <stdint.h>

#define MOTONAV_PROTO_VERSION 1

// ---- GATT identifiers -------------------------------------------------------
// Custom 128-bit UUIDs (same base, last 16 bits differ).
#define MOTONAV_SERVICE_UUID   "4d6f746f-4e61-7601-8a5c-3f2d1b0e0001"
#define MOTONAV_NAVCORE_UUID   "4d6f746f-4e61-7601-8a5c-3f2d1b0e0002"  // phone -> device (write, write w/o response)
#define MOTONAV_NAVSTREET_UUID "4d6f746f-4e61-7601-8a5c-3f2d1b0e0003"  // phone -> device (write, write w/o response)
#define MOTONAV_EVENTS_UUID    "4d6f746f-4e61-7601-8a5c-3f2d1b0e0004"  // device -> phone (notify)
// Map feed (additive; a device without these characteristics still works with v1 phones):
#define MOTONAV_NAVROUTE_UUID  "4d6f746f-4e61-7601-8a5c-3f2d1b0e0005"  // phone -> device (write, write w/o response)
#define MOTONAV_NAVPOS_UUID    "4d6f746f-4e61-7601-8a5c-3f2d1b0e0006"  // phone -> device (write, write w/o response)

#define MOTONAV_DEVICE_NAME    "MotoNav"
#define MOTONAV_STREET_MAX     20   // bytes, so the value fits the default 20-byte BLE payload

// ---- Enums ------------------------------------------------------------------
enum MotoNavMode : uint8_t {
  MN_MODE_IDLE       = 0,
  MN_MODE_NAV        = 1,
  MN_MODE_OFF_ROUTE  = 2,
  MN_MODE_REROUTING  = 3,
  MN_MODE_ARRIVED    = 4,
};

enum MotoNavManeuver : uint8_t {
  MN_MAN_NONE         = 0,
  MN_MAN_DEPART       = 1,
  MN_MAN_STRAIGHT     = 2,
  MN_MAN_SLIGHT_LEFT  = 3,
  MN_MAN_LEFT         = 4,
  MN_MAN_SHARP_LEFT   = 5,
  MN_MAN_SLIGHT_RIGHT = 6,
  MN_MAN_RIGHT        = 7,
  MN_MAN_SHARP_RIGHT  = 8,
  MN_MAN_UTURN_LEFT   = 9,
  MN_MAN_UTURN_RIGHT  = 10,
  MN_MAN_KEEP_LEFT    = 11,
  MN_MAN_KEEP_RIGHT   = 12,
  MN_MAN_MERGE        = 13,
  MN_MAN_RAMP_LEFT    = 14,
  MN_MAN_RAMP_RIGHT   = 15,
  MN_MAN_ROUNDABOUT   = 16,   // use roundabout_exit
  MN_MAN_FERRY        = 17,
  MN_MAN_ARRIVE       = 18,
  MN_MAN_ARRIVE_LEFT  = 19,
  MN_MAN_ARRIVE_RIGHT = 20,
};

enum MotoNavEvent : uint8_t {
  MN_EVT_BUTTON = 1,  // payload: [type, button_id, 0]
};

// ---- NavCore: 18 bytes, little-endian, written ~1 Hz ------------------------
#pragma pack(push, 1)
struct MotoNavCore {
  uint8_t  version;             //  0  must equal MOTONAV_PROTO_VERSION
  uint8_t  mode;                //  1  MotoNavMode
  uint8_t  maneuver;            //  2  MotoNavManeuver
  uint8_t  roundabout_exit;     //  3  1..n, 0 = unknown / not a roundabout
  uint16_t seq;                 //  4  increments per packet (gap detection)
  uint32_t dist_to_maneuver_m;  //  6
  uint32_t remaining_dist_m;    // 10
  uint16_t eta_minutes;         // 14
  uint16_t speed_kmh_x10;       // 16  0.1 km/h units
};
#pragma pack(pop)

static_assert(sizeof(MotoNavCore) == 18, "NavCore must stay 18 bytes (fits default BLE payload)");

// ---- Map feed ---------------------------------------------------------------
// Coordinates are degrees x 1e-6 as signed 32-bit (about 0.11 m).
#define MOTONAV_ROUTE_STREAM_VERSION 1
#define MOTONAV_ROUTE_MAX_POINTS     96   // the phone simplifies the route window to at most this many points

// NavPos: 10 bytes, little-endian, written about once a second.
#pragma pack(push, 1)
struct MotoNavPos {
  uint8_t route_id;   //  0  the NavRoute window this position belongs to (0 = no route yet)
  uint8_t seq;        //  1  increments per packet
  int32_t lat_e6;     //  2
  int32_t lon_e6;     //  6
};
#pragma pack(pop)

static_assert(sizeof(MotoNavPos) == 10, "NavPos must stay 10 bytes (fits default BLE payload)");
