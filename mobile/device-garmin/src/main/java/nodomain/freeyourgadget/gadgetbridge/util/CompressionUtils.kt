/*  Copyright (C) 2025 José Rebelo, Thomas Kuehne

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
package nodomain.freeyourgadget.gadgetbridge.util

import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.GarminSupport
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater

object CompressionUtils {
    private val LOG = LoggerFactory.getLogger(GarminSupport::class.java)

    fun inflate(bytes: ByteArray): ByteArray? {
        val inflater = Inflater()
        inflater.setInput(bytes)
        val baosInflated = ByteArrayOutputStream(bytes.size)
        val buf = ByteArray(8096)
        while (!inflater.finished()) {
            try {
                val count = inflater.inflate(buf)
                baosInflated.write(buf, 0, count)
            } catch (e: DataFormatException) {
                LOG.error("Failed to inflate", e)
                return null
            }
        }
        return baosInflated.toByteArray()
    }

    /**
     * zlib-deflate, the format [inflate] reads back.
     *
     * Added for uploads: Garmin's file-transfer channel carries the file
     * deflated in both directions, and only the download half existed here.
     */
    fun deflate(bytes: ByteArray): ByteArray {
        val deflater = java.util.zip.Deflater()
        deflater.setInput(bytes)
        deflater.finish()
        val out = ByteArrayOutputStream(bytes.size)
        val buf = ByteArray(8096)
        while (!deflater.finished()) {
            out.write(buf, 0, deflater.deflate(buf))
        }
        deflater.end()
        return out.toByteArray()
    }

    fun gunzipUtf8String(compressed: ByteArray?): String {
        val byteInput = ByteArrayInputStream(compressed)
        return GZIPInputStream(byteInput).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
