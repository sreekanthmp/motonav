// PC test for the map-feed decoder (protocol/nav_route.h). No ESP32 needed:
//   g++ -std=c++17 -Wall -Wextra -I ../../protocol test_nav_route.cpp -o test_nav_route && ./test_nav_route
//
// The golden bytes below are ALSO asserted by the Android unit tests (RouteWindowCodecTest), so the phone
// encoder and this decoder are proven to agree on the same bytes.

#include <stdio.h>
#include <stdlib.h>
#include <vector>

#include "nav_route.h"

static int g_failures = 0;
static int g_checks = 0;
#define CHECK(cond)                                                          \
  do {                                                                       \
    g_checks++;                                                              \
    if (!(cond)) {                                                           \
      g_failures++;                                                          \
      printf("FAIL line %d: %s\n", __LINE__, #cond);                         \
    }                                                                        \
  } while (0)

static std::vector<uint8_t> hex(const char *s) {
  std::vector<uint8_t> out;
  for (size_t i = 0; s[i] && s[i + 1]; i += 2) {
    unsigned v = 0;
    sscanf(s + i, "%2x", &v);
    out.push_back(static_cast<uint8_t>(v));
  }
  return out;
}

// Golden values (same as the Android test).
static const char *GOLDEN_CHUNK0 = "0700020101050050eec500e8ff9f049001000000";
static const char *GOLDEN_CHUNK1 = "07010200f401f401000038ff38ff";
static const char *GOLDEN_POS = "072a76f0c500b000a004";

// ---- a small encoder, used only to build test inputs ----------------------------------------------------------
static std::vector<uint8_t> encodeStream(const std::vector<int32_t> &lat, const std::vector<int32_t> &lon, bool dest,
                                         uint8_t version = 1) {
  std::vector<uint8_t> s;
  auto le32 = [&](int32_t v) { for (int i = 0; i < 4; i++) s.push_back(static_cast<uint8_t>((static_cast<uint32_t>(v) >> (8 * i)) & 0xFF)); };
  auto le16 = [&](int16_t v) { for (int i = 0; i < 2; i++) s.push_back(static_cast<uint8_t>((static_cast<uint16_t>(v) >> (8 * i)) & 0xFF)); };
  s.push_back(version);
  s.push_back(dest ? 1 : 0);
  le16(static_cast<int16_t>(lat.size()));
  le32(lat[0]);
  le32(lon[0]);
  for (size_t i = 1; i < lat.size(); i++) {
    le16(static_cast<int16_t>(lat[i] - lat[i - 1]));
    le16(static_cast<int16_t>(lon[i] - lon[i - 1]));
  }
  return s;
}

static std::vector<std::vector<uint8_t>> chunk(const std::vector<uint8_t> &stream, uint8_t id, size_t dataPerChunk) {
  std::vector<std::vector<uint8_t>> out;
  size_t total = (stream.size() + dataPerChunk - 1) / dataPerChunk;
  for (size_t i = 0; i < total; i++) {
    std::vector<uint8_t> c = {id, static_cast<uint8_t>(i), static_cast<uint8_t>(total)};
    size_t from = i * dataPerChunk, to = from + dataPerChunk < stream.size() ? from + dataPerChunk : stream.size();
    c.insert(c.end(), stream.begin() + from, stream.begin() + to);
    out.push_back(c);
  }
  return out;
}

static bool feedAll(motonav::RouteAssembler &a, const std::vector<std::vector<uint8_t>> &chunks) {
  bool done = false;
  for (auto &c : chunks) done = a.feed(c.data(), c.size());
  return done;
}

int main() {
  static_assert(sizeof(MotoNavPos) == 10, "NavPos is 10 bytes");

  // 1. Golden: two chunks decode to the five known points (negative deltas included).
  {
    motonav::RouteAssembler a;
    auto c0 = hex(GOLDEN_CHUNK0), c1 = hex(GOLDEN_CHUNK1);
    CHECK(!a.feed(c0.data(), c0.size()));
    CHECK(!a.hasWindow());                                     // not complete yet
    CHECK(a.feed(c1.data(), c1.size()));
    CHECK(a.hasWindow());
    const auto &w = a.window();
    CHECK(w.routeId == 7);
    CHECK(w.endsAtDestination);
    CHECK(w.count == 5);
    const int32_t lat[5] = {12971600, 12972000, 12972000, 12972500, 12972300};
    const int32_t lon[5] = {77594600, 77594600, 77595100, 77595100, 77594900};
    for (int i = 0; i < 5; i++) {
      CHECK(w.latE6[i] == lat[i]);
      CHECK(w.lonE6[i] == lon[i]);
    }
  }

  // 2. Golden position.
  {
    MotoNavPos p;
    auto b = hex(GOLDEN_POS);
    CHECK(motonav::decodePos(b.data(), b.size(), &p));
    CHECK(p.route_id == 7 && p.seq == 42 && p.lat_e6 == 12972150 && p.lon_e6 == 77594800);
    CHECK(!motonav::decodePos(b.data(), 9, &p));               // too short
    auto bad = b;
    bad[5] = 0x7F;                                              // latitude far beyond 90 degrees
    CHECK(!motonav::decodePos(bad.data(), bad.size(), &p));
  }

  // 3. A newer window replaces the older one; a failed window leaves the old one in place.
  {
    motonav::RouteAssembler a;
    auto c0 = hex(GOLDEN_CHUNK0), c1 = hex(GOLDEN_CHUNK1);
    CHECK(feedAll(a, {c0, c1}));
    auto w2 = chunk(encodeStream({10000000, 10000100}, {20000000, 20000100}, false), 8, 17);
    CHECK(feedAll(a, w2));
    CHECK(a.window().routeId == 8 && a.window().count == 2 && !a.window().endsAtDestination);
    CHECK(a.window().latE6[1] == 10000100 && a.window().lonE6[1] == 20000100);
    auto broken = chunk(encodeStream({1, 2, 3}, {4, 5, 6}, true), 9, 4);
    CHECK(broken.size() >= 2);
    CHECK(!a.feed(broken[0].data(), broken[0].size()));
    // skip the middle chunk
    CHECK(!a.feed(broken.back().data(), broken.back().size()));
    CHECK(a.window().routeId == 8);                             // still the previous good window
  }

  // 4. Out-of-order and restarted windows.
  {
    motonav::RouteAssembler a;
    auto c0 = hex(GOLDEN_CHUNK0), c1 = hex(GOLDEN_CHUNK1);
    CHECK(!a.feed(c1.data(), c1.size()));                       // last chunk first: ignored
    CHECK(!a.hasWindow());
    CHECK(!a.feed(c0.data(), c0.size()));
    CHECK(!a.feed(c0.data(), c0.size()));                       // chunk 0 again restarts the window
    CHECK(a.feed(c1.data(), c1.size()));                        // ...and it still completes
    // different window id mid-way
    CHECK(!a.feed(c0.data(), c0.size()));
    auto other = c1;
    other[0] = 99;
    CHECK(!a.feed(other.data(), other.size()));
  }

  // 5. Rejections: wrong stream version, count 1, count too large, trailing bytes, coordinates out of range.
  {
    motonav::RouteAssembler a;
    CHECK(!feedAll(a, chunk(encodeStream({1, 2}, {3, 4}, false, 2), 1, 17)));   // version 2
    CHECK(!feedAll(a, chunk(encodeStream({1}, {3}, false), 1, 17)));            // one point
    std::vector<int32_t> lat(97), lon(97);
    for (int i = 0; i < 97; i++) { lat[i] = 12000000 + i; lon[i] = 77000000 + i; }
    CHECK(!feedAll(a, chunk(encodeStream(lat, lon, false), 1, 182)));           // 97 points: over the limit
    auto trailing = encodeStream({1, 2}, {3, 4}, false);
    trailing.push_back(0);
    CHECK(!feedAll(a, chunk(trailing, 1, 17)));
    CHECK(!feedAll(a, chunk(encodeStream({91000000, 91000010}, {1, 2}, false), 1, 17)));   // lat 91 deg
    CHECK(!feedAll(a, chunk(encodeStream({1, 2}, {181000000, 181000010}, false), 1, 17))); // lon 181 deg
    CHECK(!a.hasWindow());
  }

  // 6. A full 96-point window in one big write (phone with a large MTU) and in 17-byte chunks gives the same points.
  {
    std::vector<int32_t> lat(96), lon(96);
    int32_t la = 12900000, lo = 77500000;
    srand(1234);
    for (int i = 0; i < 96; i++) {
      la += (rand() % 4001) - 2000;      // steps of up to +-2000 microdegrees (~220 m)
      lo += (rand() % 4001) - 2000;
      lat[i] = la;
      lon[i] = lo;
    }
    auto stream = encodeStream(lat, lon, true);
    CHECK(stream.size() == motonav::kRouteMaxStreamBytes);
    motonav::RouteAssembler big, small;
    CHECK(feedAll(big, chunk(stream, 5, 400)));
    CHECK(feedAll(small, chunk(stream, 5, 17)));
    CHECK(big.window().count == 96 && small.window().count == 96);
    for (int i = 0; i < 96; i++) {
      CHECK(big.window().latE6[i] == lat[i] && big.window().lonE6[i] == lon[i]);
      CHECK(small.window().latE6[i] == lat[i] && small.window().lonE6[i] == lon[i]);
    }
  }

  // 7. Random garbage never crashes and never produces a window with invalid coordinates.
  {
    motonav::RouteAssembler a;
    srand(99);
    for (int n = 0; n < 20000; n++) {
      uint8_t buf[24];
      size_t len = rand() % 25;
      for (size_t i = 0; i < len; i++) buf[i] = static_cast<uint8_t>(rand());
      if (len >= 3 && (n % 3) == 0) { buf[1] = 0; buf[2] = 1; }   // look like a single-chunk window sometimes
      a.feed(buf, len);
      if (a.hasWindow()) {
        for (uint16_t k = 0; k < a.window().count; k++) {
          if (a.window().latE6[k] < -90000000 || a.window().latE6[k] > 90000000) { CHECK(false); break; }
        }
      }
    }
    CHECK(true);
  }

  printf("%d checks, %d failures\n", g_checks, g_failures);
  return g_failures == 0 ? 0 : 1;
}
