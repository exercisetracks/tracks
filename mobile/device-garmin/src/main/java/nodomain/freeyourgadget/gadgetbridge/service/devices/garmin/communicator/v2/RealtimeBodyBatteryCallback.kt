/*  Copyright (C) 2026 Thomas Kuehne

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.communicator.v2

import org.slf4j.Logger
import org.slf4j.LoggerFactory

class RealtimeBodyBatteryCallback : CommunicatorV2.ServiceCallback {
    override fun onMessage(value: ByteArray?) {
        if (value != null && value.size >= 1) {
            val raw = value[0].toInt()
            if (raw == 0x7F) {
                LOG.info("BodyBattery <unnknown>%")
            } else {
                LOG.info("BodyBattery {}%", raw)
            }
        }
    }

    companion object {
        private val LOG: Logger = LoggerFactory.getLogger(RealtimeBodyBatteryCallback::class.java)
    }
}
