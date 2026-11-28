// PC harness for the panel renderer. Reads scenes (the raw BLE writes the phone would send), decodes them with the
// REAL firmware decoders (protocol/nav_route.h, the same NavCore rules as nav_state.cpp), draws them with the REAL
// renderer (lib/mnui) band by band, and writes one PPM per scene.
//
//   BEGIN <name>
//   CONNECTED <0|1>
//   C <hex>        NavCore write         S <text>   NavStreet write (rest of the line)
//   R <hex>        NavRoute write        P <hex>    NavPos write
//   KEEP           do not reset the map zoom before this scene (ride animation)
//   END            render -> <outdir>/<name>.ppm
//   W <size> <text>   print the width of a text in pixels (compared with the simulator's font engine)
//
// Build and run:  see test_host/README or tools/minimap_sim/test_firmware_parity.py
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <string>
#include <vector>

#include "mnui.h"
#include "nav_route.h"

static std::vector<uint8_t> hex(const char *s) {
  std::vector<uint8_t> out;
  for (size_t i = 0; s[i] && s[i + 1] && s[i] != '\n' && s[i] != '\r'; i += 2) {
    unsigned v = 0;
    sscanf(s + i, "%2x", &v);
    out.push_back(static_cast<uint8_t>(v));
  }
  return out;
}

static void writePpm(const char *path, mnui::Ui &ui, double *msOut) {
  FILE *f = fopen(path, "wb");
  if (!f) { perror(path); exit(2); }
  fprintf(f, "P6\n%d %d\n255\n", mnui::kSize, mnui::kSize);
  static uint16_t band[mnui::kSize * mnui::kBandRows];
  for (int y = 0; y < mnui::kSize; y += mnui::kBandRows) {
    int rows = mnui::kSize - y < mnui::kBandRows ? mnui::kSize - y : mnui::kBandRows;
    ui.renderBand(y, rows, band);
    for (int i = 0; i < rows * mnui::kSize; i++) {
      uint16_t p = band[i];
      int r = (p >> 11) & 31, g = (p >> 5) & 63, b = p & 31;
      unsigned char px[3] = {static_cast<unsigned char>((r << 3) | (r >> 2)), static_cast<unsigned char>((g << 2) | (g >> 4)),
                             static_cast<unsigned char>((b << 3) | (b >> 2))};
      fwrite(px, 1, 3, f);
    }
  }
  fclose(f);
  (void)msOut;
}

int main(int argc, char **argv) {
  if (argc < 2) { fprintf(stderr, "usage: render_host OUTDIR < scenes.txt\n"); return 2; }
  const char *outdir = argv[1];
  mnui::Ui ui;
  mnui::UiInput in;
  motonav::RouteAssembler asmb;
  std::string name;
  bool keep = false;
  char line[600];
  int scenes = 0;
  while (fgets(line, sizeof(line), stdin)) {
    size_t n = strlen(line);
    while (n && (line[n - 1] == '\n' || line[n - 1] == '\r')) line[--n] = 0;
    if (!strncmp(line, "BEGIN ", 6)) {
      name = line + 6;
      in = mnui::UiInput();
      asmb = motonav::RouteAssembler();
      keep = false;
    } else if (!strncmp(line, "CONNECTED ", 10)) {
      in.connected = atoi(line + 10) != 0;
    } else if (!strcmp(line, "KEEP")) {
      keep = true;
    } else if (line[0] == 'C' && line[1] == ' ') {
      std::vector<uint8_t> b = hex(line + 2);
      if (b.size() >= sizeof(MotoNavCore)) {
        MotoNavCore c;
        memcpy(&c, b.data(), sizeof(c));
        if (c.version == MOTONAV_PROTO_VERSION && c.mode <= MN_MODE_ARRIVED) { in.core = c; in.hasCore = true; }
      }
    } else if (line[0] == 'S' && line[1] == ' ') {
      memset(in.street, 0, sizeof(in.street));
      snprintf(in.street, sizeof(in.street), "%.*s", MOTONAV_STREET_MAX, line + 2);
    } else if (line[0] == 'R' && line[1] == ' ') {
      std::vector<uint8_t> b = hex(line + 2);
      if (asmb.feed(b.data(), b.size())) in.route = &asmb.window();
    } else if (line[0] == 'P' && line[1] == ' ') {
      std::vector<uint8_t> b = hex(line + 2);
      MotoNavPos p;
      if (motonav::decodePos(b.data(), b.size(), &p)) { in.pos = p; in.hasPos = true; }
    } else if (line[0] == 'W' && line[1] == ' ') {
      float size = static_cast<float>(atof(line + 2));
      const char *text = strchr(line + 2, ' ');
      printf("W %.3f\n", mnui::textWidth(text ? text + 1 : "", size));
    } else if (!strcmp(line, "END")) {
      if (!keep) ui.resetView();
      ui.update(in);
      std::string path = std::string(outdir) + "/" + name + ".ppm";
      writePpm(path.c_str(), ui, nullptr);
      printf("%s ops=%d pts=%d large=%d mpp=%.1f%s\n", name.c_str(), ui.list().opCount(), ui.list().pointCount(),
             ui.lastWasLarge() ? 1 : 0, ui.mapMetersPerPixel(), ui.list().overflowed() ? " OVERFLOW" : "");
      scenes++;
    }
  }
  fprintf(stderr, "%d scenes\n", scenes);
  return 0;
}
