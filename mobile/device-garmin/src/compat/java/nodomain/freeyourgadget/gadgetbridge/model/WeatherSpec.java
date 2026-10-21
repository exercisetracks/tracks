// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package nodomain.freeyourgadget.gadgetbridge.model;

/**
 * Shim for Gadgetbridge's WeatherSpec (933 lines from 0.94, with Gson and a
 * solar-position library), which the vendored FitWeatherConditions names
 * for one helper's argument. Tracks builds weather from its own model
 * (com.tracks.device.garmin.GarminWeatherEncoder) and never passes one; this
 * is the air-quality index that helper reads, and nothing more.
 */
public class WeatherSpec {
    public static class AirQuality {
        private int aqi = -1;

        public int getAqi() {
            return aqi;
        }

        public void setAqi(final int aqi) {
            this.aqi = aqi;
        }
    }
}
