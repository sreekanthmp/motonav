// MotoNav map feed: decoder for the NavRoute chunks. Header-only, no Arduino or heap use, so the
// same code runs in the firmware and in the PC tests (firmware/prototype-esp32/test_host/).
// Wire format: protocol/README.md ("Map feed").
#pragma once

#include <stddef.h>
#include <stdint.h>
#include <string.h>

#include "nav_protocol.h"

namespace motonav {

constexpr size_t kRouteMaxPoints = MOTONAV_ROUTE_MAX_POINTS;
constexpr size_t kRouteHeaderBytes = 12;                                    // version, flags, count(2), lat0(4), lon0(4)
constexpr size_t kRouteMaxStreamBytes = kRouteHeaderBytes + 4 * (kRouteMaxPoints - 1);
constexpr size_t kChunkHeaderBytes = 3;                                     // route_id, chunk index, chunk count

// A decoded route window: the part of the route around the rider.
struct RouteWindow {
  uint8_t routeId;
  bool endsAtDestination;   // the last point is the destination
  uint16_t count;           // number of valid points
  int32_t latE6[kRouteMaxPoints];
  int32_t lonE6[kRouteMaxPoints];
};

// Reassembles NavRoute writes. The phone sends the chunks of one window in order; anything out of order
// drops that window and the previous complete window stays in use.
class RouteAssembler {
 public:
  RouteAssembler() { memset(&win_, 0, sizeof(win_)); }

  // Feed one BLE write. Returns true when this write completed a valid, new window.
  bool feed(const uint8_t *data, size_t len) {
    if (len <= kChunkHeaderBytes) return false;
    const uint8_t id = data[0], idx = data[1], total = data[2];
    if (total == 0 || idx >= total) {
      collecting_ = false;
      return false;
    }
    if (idx == 0) {                       // a new window starts here
      collecting_ = true;
      curId_ = id;
      total_ = total;
      next_ = 0;
      used_ = 0;
    } else if (!collecting_ || id != curId_ || total != total_ || idx != next_) {
      collecting_ = false;                // missing or out-of-order chunk: give up on this window
      return false;
    }
    const size_t n = len - kChunkHeaderBytes;
    if (used_ + n > sizeof(buf_)) {
      collecting_ = false;
      return false;
    }
    memcpy(buf_ + used_, data + kChunkHeaderBytes, n);
    used_ += n;
    next_ = static_cast<uint8_t>(idx + 1);
    if (next_ < total_) return false;     // more chunks to come

    collecting_ = false;
    return decode(curId_);
  }

  bool hasWindow() const { return have_; }
  const RouteWindow &window() const { return win_; }

 private:
  static int32_t i32(const uint8_t *p) {
    return static_cast<int32_t>(static_cast<uint32_t>(p[0]) | (static_cast<uint32_t>(p[1]) << 8) |
                                (static_cast<uint32_t>(p[2]) << 16) | (static_cast<uint32_t>(p[3]) << 24));
  }
  static int16_t i16(const uint8_t *p) {
    return static_cast<int16_t>(static_cast<uint16_t>(p[0]) | (static_cast<uint16_t>(p[1]) << 8));
  }
  static bool coordOk(int32_t lat, int32_t lon) {
    return lat >= -90000000 && lat <= 90000000 && lon >= -180000000 && lon <= 180000000;
  }

  bool decode(uint8_t id) {
    if (used_ < kRouteHeaderBytes) return false;
    if (buf_[0] != MOTONAV_ROUTE_STREAM_VERSION) return false;
    const uint16_t count = static_cast<uint16_t>(buf_[2] | (buf_[3] << 8));
    if (count < 2 || count > kRouteMaxPoints) return false;
    if (used_ != kRouteHeaderBytes + 4u * (count - 1u)) return false;   // exact length, no trailing bytes

    RouteWindow w;
    memset(&w, 0, sizeof(w));
    w.routeId = id;
    w.endsAtDestination = (buf_[1] & 0x01) != 0;
    w.count = count;
    int32_t lat = i32(buf_ + 4), lon = i32(buf_ + 8);
    if (!coordOk(lat, lon)) return false;
    w.latE6[0] = lat;
    w.lonE6[0] = lon;
    for (uint16_t k = 1; k < count; k++) {
      const uint8_t *d = buf_ + kRouteHeaderBytes + 4u * (k - 1u);
      lat += i16(d);
      lon += i16(d + 2);
      if (!coordOk(lat, lon)) return false;
      w.latE6[k] = lat;
      w.lonE6[k] = lon;
    }
    win_ = w;
    have_ = true;
    return true;
  }

  RouteWindow win_;
  bool have_ = false;
  bool collecting_ = false;
  uint8_t curId_ = 0, total_ = 0, next_ = 0;
  size_t used_ = 0;
  uint8_t buf_[kRouteMaxStreamBytes];
};

// NavPos: 10 bytes. Returns false if the length is wrong or the coordinates are out of range.
inline bool decodePos(const uint8_t *data, size_t len, MotoNavPos *out) {
  if (len < sizeof(MotoNavPos)) return false;
  MotoNavPos p;
  memcpy(&p, data, sizeof(p));
  if (p.lat_e6 < -90000000 || p.lat_e6 > 90000000 || p.lon_e6 < -180000000 || p.lon_e6 > 180000000) return false;
  *out = p;
  return true;
}

}  // namespace motonav
