// Feeds BLE writes (hex, one per line) through the REAL firmware decoder (protocol/nav_route.h) and prints what the
// device would hold. Used by test_minimap_sim.py to prove the simulator's bytes decode to the same values on the device.
//
//   input lines:   R <hex>   a NavRoute write        P <hex>   a NavPos write
//   output lines:  W <id> <dest> <count> <lat_e6> <lon_e6> ...   when a route window completes
//                  P <route_id> <seq> <lat_e6> <lon_e6>          for a valid NavPos
//                  X                                              for a rejected NavPos
//
// Build:  g++ -std=c++17 -Wall -Wextra -I ../../protocol device_decode.cpp -o device_decode
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <vector>

#include "nav_route.h"

static std::vector<uint8_t> hex(const char *s) {
  std::vector<uint8_t> out;
  for (size_t i = 0; s[i] && s[i + 1] && s[i] != '\n'; i += 2) {
    unsigned v = 0;
    sscanf(s + i, "%2x", &v);
    out.push_back(static_cast<uint8_t>(v));
  }
  return out;
}

int main() {
  motonav::RouteAssembler asmb;
  char line[512];
  while (fgets(line, sizeof(line), stdin)) {
    if (line[0] != 'R' && line[0] != 'P') continue;
    std::vector<uint8_t> b = hex(line + 2);
    if (line[0] == 'R') {
      if (asmb.feed(b.data(), b.size())) {
        const motonav::RouteWindow &w = asmb.window();
        printf("W %u %d %u", w.routeId, w.endsAtDestination ? 1 : 0, w.count);
        for (unsigned i = 0; i < w.count; i++) printf(" %d %d", w.latE6[i], w.lonE6[i]);
        printf("\n");
      }
    } else {
      MotoNavPos p;
      if (motonav::decodePos(b.data(), b.size(), &p)) printf("P %u %u %d %d\n", p.route_id, p.seq, p.lat_e6, p.lon_e6);
      else printf("X\n");
    }
  }
  return 0;
}
