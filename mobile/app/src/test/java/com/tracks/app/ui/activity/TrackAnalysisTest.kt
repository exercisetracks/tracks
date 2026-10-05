// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.activity

import com.tracks.core.api.TrackPoint
import java.time.Instant
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The numbers this screen derives rather than receives.
 *
 * Worth testing precisely because they *look* right when they are wrong: a
 * split table with the boundaries in the wrong place still shows plausible
 * paces, and a zone breakdown that charges a lunch stop to zone 2 still adds up
 * to 100%. None of that is visible on a phone.
 */
class TrackAnalysisTest {

    private val start = Instant.parse("2026-05-01T09:00:00Z")

    /**
     * A straight run due east at a constant speed.
     *
     * East rather than north so the samples exercise the longitude term of the
     * haversine, which is the half that has to be scaled by latitude.
     */
    private fun straightTrack(
        samples: Int,
        metresPerSample: Double,
        secondsPerSample: Long = 1,
        heartRate: (Int) -> Int? = { null },
        altitude: (Int) -> Double? = { null },
        lat: Double = 40.0,
    ): List<TrackPoint> {
        // Metres per degree of longitude at this latitude.
        val perDegree = haversineMetres(lat, 0.0, lat, 1.0)
        return (0 until samples).map { i ->
            TrackPoint(
                recordedAt = start.plusSeconds(i * secondsPerSample).toString(),
                lat = lat,
                lng = i * metresPerSample / perDegree,
                altitude = altitude(i),
                heartRate = heartRate(i),
                speed = metresPerSample / secondsPerSample,
            )
        }
    }

    // ── Distance ─────────────────────────────────────────────────────────────

    @Test
    fun `haversine agrees with a known separation`() {
        // One degree of latitude is very close to 111.19 km on a sphere.
        val metres = haversineMetres(0.0, 0.0, 1.0, 0.0)
        assertTrue(abs(metres - 111_195.0) < 100, "got $metres")
    }

    @Test
    fun `haversine is zero for a point against itself`() {
        assertEquals(0.0, haversineMetres(40.0, -150.0, 40.0, -150.0), 1e-9)
    }

    // ── Sample timing ────────────────────────────────────────────────────────

    @Test
    fun `every sample is weighted by the interval that follows it`() {
        val weights = assertNotNull(sampleSeconds(straightTrack(4, 10.0, secondsPerSample = 2)))
        assertEquals(4, weights.size)
        assertTrue(weights.all { it == 2.0 }, "got $weights")
    }

    @Test
    fun `a long stop does not become time at heart rate`() {
        val paused = listOf(
            TrackPoint(recordedAt = start.toString(), heartRate = 120),
            TrackPoint(recordedAt = start.plusSeconds(1).toString(), heartRate = 120),
            // Forty minutes on a bench.
            TrackPoint(recordedAt = start.plusSeconds(2401).toString(), heartRate = 120),
            TrackPoint(recordedAt = start.plusSeconds(2402).toString(), heartRate = 120),
        )
        val weights = assertNotNull(sampleSeconds(paused))
        assertTrue(
            weights.all { it <= 60.0 },
            "a pause was charged to the athlete as effort: $weights",
        )
    }

    @Test
    fun `timestamps without a zone are still read`() {
        """The server sends naive UTC, which Instant.parse rejects outright — so
           getting this wrong silently disables every derived number here."""
        val naive = listOf(
            TrackPoint(recordedAt = "2026-05-01T09:00:00"),
            TrackPoint(recordedAt = "2026-05-01T09:00:01"),
        )
        assertNotNull(sampleSeconds(naive))
    }

    @Test
    fun `an untimed track derives nothing rather than guessing`() {
        assertNull(sampleSeconds(listOf(TrackPoint(lat = 1.0), TrackPoint(lat = 2.0))))
    }

    @Test
    fun `time running backwards is refused`() {
        val jumbled = listOf(
            TrackPoint(recordedAt = start.plusSeconds(10).toString()),
            TrackPoint(recordedAt = start.toString()),
        )
        assertNull(sampleSeconds(jumbled))
    }

    // ── Splits ───────────────────────────────────────────────────────────────

    @Test
    fun `a 3km run at constant pace splits into three equal kilometres`() {
        val track = straightTrack(samples = 601, metresPerSample = 5.0)  // 3000 m
        val result = splits(track, unitMetres = 1000.0)

        assertEquals(3, result.size)
        result.forEach {
            assertEquals(1000.0, it.metres, 1.0)
            assertEquals(200.0, it.seconds, 1.0)
        }
    }

    @Test
    fun `the last split reports the distance it actually covered`() {
        """A 2.4 km run's tail is a 400 m split. Calling it a kilometre would
           halve its stated pace — the one number the card is read for."""
        val track = straightTrack(samples = 481, metresPerSample = 5.0)  // 2400 m
        val result = splits(track, unitMetres = 1000.0)

        assertEquals(3, result.size)
        assertEquals(400.0, result.last().metres, 1.0)
        // Same pace as the full splits, despite covering less ground.
        assertEquals(result[0].speed, result.last().speed, 0.01)
    }

    @Test
    fun `a stub of a tail is dropped rather than shown as a split`() {
        val track = straightTrack(samples = 205, metresPerSample = 5.0)  // 1020 m
        assertEquals(1, splits(track, unitMetres = 1000.0).size)
    }

    @Test
    fun `nothing shorter than one split has splits`() {
        val track = straightTrack(samples = 100, metresPerSample = 5.0)  // 495 m
        assertTrue(splits(track, unitMetres = 1000.0).isEmpty())
    }

    @Test
    fun `a boundary inside a hop is divided across it`() {
        """With LTTB thinning a long ride to tens of metres between samples,
           rounding each boundary to the nearest sample puts percent-level error
           on every split's pace."""
        // 400 m between samples: every boundary falls mid-hop.
        val track = straightTrack(samples = 11, metresPerSample = 400.0, secondsPerSample = 100)
        val result = splits(track, unitMetres = 1000.0)

        assertEquals(4, result.size)
        result.forEach { assertEquals(1000.0, it.metres, 0.5) }
        result.forEach { assertEquals(250.0, it.seconds, 0.5) }
    }

    @Test
    fun `splits are scaled to the distance the watch recorded`() {
        """The polyline arrives LTTB-downsampled, so summing its chords
           undershoots — and splits that disagree with the distance printed
           above them read as a broken screen."""
        val track = straightTrack(samples = 601, metresPerSample = 5.0)  // measures 3000 m
        val result = splits(track, unitMetres = 1000.0, recordedDistance = 3300.0)

        // Three full kilometres and the 300 m the sampling had lost.
        assertEquals(4, result.size)
        assertEquals(3300.0, result.sumOf { it.metres }, 2.0)
        assertEquals(300.0, result.last().metres, 2.0)
    }

    @Test
    fun `heart rate is averaged over time, not over samples`() {
        val track = straightTrack(
            samples = 201,
            metresPerSample = 5.0,
            heartRate = { i -> if (i < 100) 140 else 160 },
        )
        val result = splits(track, unitMetres = 1000.0)
        assertEquals(1, result.size)
        assertEquals(150, result.single().avgHeartRate)
    }

    @Test
    fun `climbing and descending are counted separately`() {
        // Up 100 m over the first half, back down over the second.
        val track = straightTrack(
            samples = 201,
            metresPerSample = 5.0,
            altitude = { i -> if (i <= 100) i.toDouble() else (200 - i).toDouble() },
        )
        val split = splits(track, unitMetres = 1000.0).single()
        assertEquals(100.0, split.ascent, 2.0)
        assertEquals(100.0, split.descent, 2.0)
    }

    @Test
    fun `a track with no positions has no splits`() {
        val track = (0..100).map {
            TrackPoint(recordedAt = start.plusSeconds(it.toLong()).toString(), heartRate = 130)
        }
        assertTrue(splits(track, unitMetres = 1000.0).isEmpty())
    }

    @Test
    fun `a nonsense unit is refused rather than looped over`() {
        val track = straightTrack(samples = 601, metresPerSample = 5.0)
        assertTrue(splits(track, unitMetres = 0.0).isEmpty())
        assertTrue(splits(track, unitMetres = -1000.0).isEmpty())
    }

    // ── Elevation ────────────────────────────────────────────────────────────

    @Test
    fun `elevation ignores noise below the sensor's resolution`() {
        val jittery = (0..200).map { i ->
            TrackPoint(
                recordedAt = start.plusSeconds(i.toLong()).toString(),
                altitude = 1000.0 + if (i % 2 == 0) 0.2 else -0.2,
            )
        }
        val profile = assertNotNull(elevationProfile(jittery))
        assertEquals(0.0, profile.gain, 0.01)
        assertEquals(0.0, profile.loss, 0.01)
    }

    @Test
    fun `elevation reports the high and low the summary does not carry`() {
        val track = straightTrack(
            samples = 101,
            metresPerSample = 5.0,
            altitude = { i -> 1500.0 + i * 2.0 },
        )
        val profile = assertNotNull(elevationProfile(track))
        assertEquals(1500.0, profile.lowest, 0.01)
        assertEquals(1700.0, profile.highest, 0.01)
        assertEquals(200.0, profile.gain, 0.01)
    }

    // ── Zones and distribution ───────────────────────────────────────────────

    @Test
    fun `zone time is seconds, not a share of samples`() {
        val track = straightTrack(
            samples = 121,
            metresPerSample = 5.0,
            secondsPerSample = 10,
            heartRate = { i -> if (i < 60) 120 else 170 },
        )
        val seconds = assertNotNull(zoneSeconds(track, listOf(100, 150)))
        assertEquals(600.0, seconds[0], 15.0)
        assertEquals(610.0, seconds[1], 15.0)
    }

    @Test
    fun `a heart rate below every zone floor lands in none of them`() {
        val track = straightTrack(samples = 20, metresPerSample = 5.0, heartRate = { 45 })
        assertNull(zoneSeconds(track, listOf(100, 150)))
    }

    @Test
    fun `the histogram spans what was recorded rather than a fixed range`() {
        val track = straightTrack(
            samples = 60,
            metresPerSample = 5.0,
            heartRate = { i -> 120 + i % 10 },
        )
        val bins = heartRateBins(track, binWidth = 5)
        assertEquals(120, bins.first().bpm)
        assertEquals(125, bins.last().bpm)
    }

    // ── Moving time ──────────────────────────────────────────────────────────

    @Test
    fun `moving time excludes the samples that were not moving`() {
        val track = (0..100).map { i ->
            TrackPoint(
                recordedAt = start.plusSeconds(i.toLong()).toString(),
                speed = if (i < 50) 3.0 else 0.0,
            )
        }
        assertEquals(50.0, assertNotNull(movingSeconds(track)), 1.5)
    }

    @Test
    fun `no speed channel means unknown rather than equal to elapsed`() {
        """"We could not tell" and "you never stopped" must not look alike."""
        val track = (0..100).map {
            TrackPoint(recordedAt = start.plusSeconds(it.toLong()).toString())
        }
        assertNull(movingSeconds(track))
    }
}
