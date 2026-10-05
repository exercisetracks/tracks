// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.messages.status

import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.messages.GFDIMessage
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.messages.GFDIMessage.GarminMessage
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.messages.GFDIMessage.Status
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The watch's answer to a FIT definition, including the short one (patch 0013).
 *
 * A fenix 6X occasionally answers with 9 bytes: length, RESPONSE, FIT_DEFINITION,
 * status 5 (LENGTH_ERROR), CRC — and no definition status code after the
 * status. Upstream reads the code unconditionally, so the read ran off the end
 * of the payload and the message was dropped as unparseable ("Could not parse
 * an incoming GFDI message of 9 bytes"); once that coincided with a sync
 * stalling. Verified here only: the hardware sends it rarely and on its own
 * schedule.
 *
 * Robolectric because parsing logs, and the vendored logger ends in
 * `android.util.Log`, which the stub android.jar throws from. Without it the
 * throw is caught by `GFDIMessage.parseIncoming`, which logs again and throws
 * from there — so both cases failed on logging before any assertion ran.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class FitDefinitionStatusMessageTest {

    private fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun the_watchs_status_only_answer_parses_as_not_applied() {
        val parsed = GFDIMessage.parseIncoming(bytes("090088139313054085"))

        val status = assertIs<FitDefinitionStatusMessage>(parsed)
        assertEquals(GarminMessage.FIT_DEFINITION, status.garminMessage)
        assertNull(status.fitDefinitionStatusCode)
        // GarminSupport writes every parsed message's own reply; this one has
        // none, and building it must not reach for the missing code.
        assertNull(status.outgoingMessage)
    }

    @Test
    fun the_full_answer_still_reads_its_definition_status_code() {
        // What a watch sends when it applies a definition, built by the
        // message's own writer: RESPONSE, FIT_DEFINITION, ACK, APPLIED.
        val wire = FitDefinitionStatusMessage(
            GarminMessage.FIT_DEFINITION, Status.ACK,
            FitDefinitionStatusMessage.FitDefinitionStatusCode.APPLIED, true,
        ).outgoingMessage

        val status = assertIs<FitDefinitionStatusMessage>(GFDIMessage.parseIncoming(wire))
        assertEquals(FitDefinitionStatusMessage.FitDefinitionStatusCode.APPLIED, status.fitDefinitionStatusCode)
    }
}
