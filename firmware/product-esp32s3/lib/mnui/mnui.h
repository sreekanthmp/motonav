// MotoNav round-display UI: public header. See mn_screens.h for the data it needs.
//
//   mnui::Ui ui;
//   ui.update(input);                                    // about once a second, or when the state changes
//   for (int y = 0; y < mnui::kSize; y += mnui::kBandRows) {
//     int rows = min(mnui::kBandRows, mnui::kSize - y);
//     ui.renderBand(y, rows, band);                      // band: kSize * kBandRows RGB565 (native order)
//     panel.write(0, y, mnui::kSize, rows, band);        // the panel driver swaps bytes / DMAs as its controller needs
//   }
#pragma once

#include "mn_font.h"
#include "mn_raster.h"
#include "mn_screens.h"
