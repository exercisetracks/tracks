// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.fit

import com.tracks.core.api.StepGroup
import com.tracks.core.api.WorkoutStep
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The phone's FIT encoders against the backend's, byte for byte.
 *
 * ## Why byte equality rather than a decode-and-check
 *
 * Because nothing about a FIT file tells you why a watch threw it away. This
 * project has spent weeks on files that transferred perfectly and then simply
 * did not appear, and in every case the difference was a field nobody was
 * looking at. The backend's encoder produces files this watch demonstrably
 * imports; the only cheap statement of correctness for a second encoder is
 * that a reader cannot tell the two apart.
 *
 * A decode-and-assert test would pass on a file missing
 * `weight_display_unit`, or carrying `target_type` on a yoga step, or built at
 * the wrong profile version — all of which are silent, and two of which are
 * known to stop animations playing.
 *
 * ## Where the expected bytes come from
 *
 * `app/calculators/fit_workout.py`, run on the same inputs. Regenerate with
 * `scripts/gen_fit_goldens.py`, which prints these constants; the inputs below
 * are kept in step with the ones that script uses, and a case added in one
 * place has to be added in the other.
 */
class WorkoutFitTest {

    private val paces = mapOf(
        "easy" to 330.0, "threshold" to 260.0, "marathon" to 300.0,
        "interval" to 240.0, "repetition" to 225.0, "recovery" to 360.0,
    )

    /** Fixed so the file is reproducible; the real one is persisted per workout. */
    private val timeCreated = 1_756_000_000_000L

    @Test
    fun `a coached run matches the backend byte for byte`() {
        val bytes = WorkoutFit.endurance(
            name = "Threshold Run", sport = "running",
            planSteps = listOf(
                WorkoutStep(type = "warmup", durationMin = 15.0, pace = "easy"),
                WorkoutStep(
                    type = "interval_set", reps = 4, distanceM = 1000.0,
                    restSec = 90, pace = "threshold",
                ),
                WorkoutStep(type = "cooldown", durationMin = 10.0, pace = "easy"),
            ),
            workoutId = 101, timeCreatedMillis = timeCreated,
            paceCoaching = true, paces = paces,
        )
        assertContentEquals(hex(RUN_COACHED), bytes)
    }

    @Test
    fun `a coached ride resolves power targets, not heart rate`() {
        val bytes = WorkoutFit.endurance(
            name = "Sweet Spot", sport = "mountain_biking",
            planSteps = listOf(
                WorkoutStep(type = "warmup", durationMin = 10.0, intensity = "easy"),
                WorkoutStep(
                    type = "effort_set", reps = 3, durationMinEach = 8.0,
                    restMin = 4.0, intensity = "sweet_spot",
                ),
                WorkoutStep(type = "cooldown", durationMin = 10.0, intensity = "recovery"),
            ),
            workoutId = 102, timeCreatedMillis = timeCreated,
            paceCoaching = true, lthr = 165, ftp = 250,
        )
        assertContentEquals(hex(MTB_COACHED), bytes)
    }

    @Test
    fun `a fartlek loops, and the walks around it become a warm-up and a cool-down`() {
        val bytes = WorkoutFit.endurance(
            name = "Fartlek", sport = "running",
            planSteps = listOf(
                WorkoutStep(type = "walk", durationMin = 5.0),
                WorkoutStep(
                    type = "fartlek", hardMin = 3.0, easyMin = 2.0, reps = 5,
                    pace = "threshold",
                ),
                WorkoutStep(type = "walk", durationMin = 5.0),
            ),
            workoutId = 103, timeCreatedMillis = timeCreated,
        )
        assertContentEquals(hex(FARTLEK_PLAIN), bytes)
    }

    @Test
    fun `a workout the plan only described in prose still gets three runnable steps`() {
        val bytes = WorkoutFit.endurance(
            name = "Open Run", sport = "running", planSteps = emptyList(),
            workoutId = 104, timeCreatedMillis = timeCreated,
        )
        assertContentEquals(hex(EMPTY_FALLBACK), bytes)
    }

    @Test
    fun `pace coaching off suppresses every target, even with paces to hand`() {
        val bytes = WorkoutFit.endurance(
            name = "Easy Day", sport = "running",
            planSteps = listOf(WorkoutStep(type = "run", durationMin = 45.0, pace = "easy")),
            workoutId = 107, timeCreatedMillis = timeCreated,
            paceCoaching = false, paces = paces,
        )
        assertContentEquals(hex(UNCOACHED_RUN), bytes)
    }

    @Test
    fun `a threshold heart rate with no FTP takes the heart-rate branch`() {
        val bytes = WorkoutFit.endurance(
            name = "Skills", sport = "cycling",
            planSteps = listOf(WorkoutStep(type = "ride", durationMin = 40.0, intensity = "skills")),
            workoutId = 108, timeCreatedMillis = timeCreated,
            paceCoaching = true, lthr = 170, ftp = null,
        )
        assertContentEquals(hex(HR_ONLY), bytes)
    }

    @Test
    fun `strength sets become a work step, a rest and a repeat, with exercise titles`() {
        val bytes = WorkoutFit.strength(
            name = "Upper Body",
            exercises = listOf(
                WorkoutStep(
                    type = "strength_exercise", name = "Bench Press", sets = 3,
                    reps = 8, weightKg = 60.0, restSeconds = 120,
                    garminCategory = "bench_press", garminSubtype = 0,
                ),
                WorkoutStep(
                    type = "strength_exercise", name = "Barbell Row", sets = 1,
                    reps = 10, weightKg = 40.0, restSeconds = 90,
                    garminCategory = "row", garminSubtype = 3,
                ),
            ),
            workoutId = 105, timeCreatedMillis = timeCreated,
            workoutType = "strength", description = "Push and pull",
        )
        assertContentEquals(hex(STRENGTH), bytes)
    }

    @Test
    fun `an exercise with no Garmin mapping emits no title block`() {
        val bytes = WorkoutFit.strength(
            name = "Odd Lifts",
            exercises = listOf(
                WorkoutStep(
                    type = "strength_exercise", name = "Sandbag Toss",
                    sets = 2, reps = 6, restSeconds = 60,
                ),
            ),
            workoutId = 109, timeCreatedMillis = timeCreated, workoutType = "strength",
        )
        assertContentEquals(hex(STRENGTH_UNMAPPED), bytes)
    }

    @Test
    fun `a mobility hold is one side, looped over its sets and sides, under Garmin's own name`() {
        val bytes = WorkoutFit.strength(
            name = "Evening Mobility",
            exercises = listOf(
                WorkoutStep(
                    type = "mobility_exercise", name = "Low Lunge",
                    durationSeconds = 45, sets = 2, eachSide = true,
                    garminCategory = "pose", garminSubtype = 41,
                ),
                WorkoutStep(
                    type = "mobility_exercise", name = "Childs Pose",
                    durationSeconds = 60, sets = 1, eachSide = false,
                    garminCategory = "pose", garminSubtype = 12,
                ),
            ),
            workoutId = 106, timeCreatedMillis = timeCreated, workoutType = "mobility",
        )
        assertContentEquals(hex(YOGA), bytes)
    }

    /** Blocks: a lone rest, a 3-round circuit with rest between rounds, a superset. */
    @Test
    fun `groups loop once around their members and rest blocks stand alone`() {
        val g1 = StepGroup(uid = "g1", kind = "repeat", rounds = 3, restSeconds = 90)
        val g2 = StepGroup(uid = "g2", kind = "superset", rounds = 2, restSeconds = 0)
        val bytes = WorkoutFit.strength(
            name = "Circuit",
            exercises = listOf(
                WorkoutStep(
                    type = "strength_exercise", name = "Goblet Squat", sets = 2, reps = 10,
                    weightKg = 20.0, restSeconds = 60, garminCategory = "squat", garminSubtype = 37,
                ),
                WorkoutStep(type = "rest", durationSeconds = 120),
                WorkoutStep(
                    type = "strength_exercise", name = "Push Up", sets = 5, reps = 12,
                    garminCategory = "push_up", garminSubtype = 77, group = g1,
                ),
                WorkoutStep(
                    type = "strength_exercise", name = "Barbell Row", reps = 8, weightKg = 40.0,
                    garminCategory = "row", garminSubtype = 3, group = g1,
                ),
                WorkoutStep(
                    type = "strength_exercise", name = "Bench Press", reps = 6, weightKg = 60.0,
                    garminCategory = "bench_press", garminSubtype = 0, group = g2,
                ),
                WorkoutStep(
                    type = "strength_exercise", name = "Pull Up", reps = 5,
                    garminCategory = "pull_up", garminSubtype = 38, group = g2,
                ),
            ),
            workoutId = 112, timeCreatedMillis = timeCreated, workoutType = "strength",
        )
        assertContentEquals(hex(STRENGTH_BLOCKS), bytes)
    }

    /** One pass of the group and one loop; a one-sided stretch outside it loops on its own. */
    @Test
    fun `a repeated stretch group is one loop around its holds`() {
        val f1 = StepGroup(uid = "f1", kind = "repeat", rounds = 2, restSeconds = 15)
        val bytes = WorkoutFit.strength(
            name = "Flow Rounds",
            exercises = listOf(
                WorkoutStep(
                    type = "mobility_exercise", name = "Cat Cow", durationSeconds = 30, sets = 1,
                    garminCategory = "pose", garminSubtype = 16, group = f1,
                ),
                WorkoutStep(
                    type = "mobility_exercise", name = "Childs Pose", durationSeconds = 45,
                    eachSide = false, garminCategory = "pose", garminSubtype = 12, group = f1,
                ),
                WorkoutStep(type = "rest", durationSeconds = 30),
                WorkoutStep(
                    type = "mobility_exercise", name = "Low Lunge", durationSeconds = 40, sets = 2,
                    eachSide = true, garminCategory = "pose", garminSubtype = 41,
                ),
            ),
            workoutId = 113, timeCreatedMillis = timeCreated, workoutType = "mobility",
        )
        assertContentEquals(hex(YOGA_BLOCKS), bytes)
    }

    /** No pose, a strength-app-only pose, and a name used twice (one title, one number). */
    @Test
    fun `stretches the Yoga app has no pose for go out under their own names`() {
        val bytes = WorkoutFit.strength(
            name = "Unwind",
            exercises = listOf(
                WorkoutStep(
                    type = "mobility_exercise", name = "Couch Stretch", durationSeconds = 60,
                    sets = 2, eachSide = true,
                ),
                WorkoutStep(
                    type = "mobility_exercise", name = "Tennis Ball Foot Release",
                    durationSeconds = 60, sets = 1, garminCategory = "warm_up", garminSubtype = 37,
                ),
                WorkoutStep(
                    type = "mobility_exercise", name = "Thunderbolt Pose", durationSeconds = 60,
                    sets = 1, garminCategory = "pose", garminSubtype = 76,
                ),
                WorkoutStep(
                    type = "mobility_exercise", name = "Couch Stretch", durationSeconds = 30,
                    sets = 1, eachSide = true,
                ),
            ),
            workoutId = 118, timeCreatedMillis = timeCreated, workoutType = "flexibility",
        )
        assertContentEquals(hex(YOGA_OWN_NAMES), bytes)
    }

    @Test
    fun `over-long strings are cut on a character boundary, not a byte one`() {
        val bytes = WorkoutFit.endurance(
            name = "Wide — " + "é".repeat(60), sport = "running",
            planSteps = listOf(WorkoutStep(type = "run", durationMin = 20.0)),
            workoutId = 110, timeCreatedMillis = timeCreated,
            description = "Detail — " + "ü".repeat(200),
        )
        assertContentEquals(hex(LONG_STRINGS), bytes)
    }

    @Test
    fun `the local message table wraps at sixteen, exactly as the reference does`() {
        val bytes = WorkoutFit.endurance(
            name = "Widths", sport = "running",
            planSteps = (1..20).map {
                WorkoutStep(type = "activity", durationMin = 10.0, intensity = "a".repeat(it))
            },
            workoutId = 111, timeCreatedMillis = timeCreated,
        )
        assertContentEquals(hex(DEFINITION_WRAP), bytes)
    }

    @Test
    fun `a schedule places each workout at noon, keyed to the file it names`() {
        val bytes = ScheduleFit.encode(
            entries = listOf(
                ScheduleFit.Entry("2026-09-01", 101, timeCreated),
                ScheduleFit.Entry("2026-09-03", 102, timeCreated + 1000),
                ScheduleFit.Entry("2026-09-05", 105, timeCreated + 2000),
            ),
            planName = "Autumn Block", planEnd = "2026-09-30",
            nowMillis = 1_788_000_018_000L,
        )
        assertContentEquals(hex(SCHEDULE), bytes)
    }

    @Test
    fun `an empty schedule is refused rather than written as an empty calendar`() {
        assertNull(ScheduleFit.encode(emptyList(), nowMillis = timeCreated))
    }

    @Test
    fun `dates convert against known civil days`() {
        assertEquals(0L, daysFromCivil("1970-01-01"))
        assertEquals(-1L, daysFromCivil("1969-12-31"))
        // A leap day, and the day after, across a century that is not a leap year.
        assertEquals(11016L, daysFromCivil("2000-02-29"))
        assertEquals(11017L, daysFromCivil("2000-03-01"))
        assertEquals(20694L, daysFromCivil("2026-08-29"))
    }

    @Test
    fun `a pool set counts lengths and carries stroke and equipment`() {
        val bytes = WorkoutFit.endurance(
            name = "Technique", sport = "swimming",
            planSteps = listOf(
                WorkoutStep(type = "warmup", distanceM = 300.0, durationMin = 6.0, intensity = "easy"),
                WorkoutStep(
                    type = "interval_set", reps = 6, distanceM = 50.0, restSec = 20, intensity = "easy",
                    stroke = "drill", equipment = "swim_kickboard", label = "Kick",
                ),
                WorkoutStep(
                    type = "interval_set", reps = 1, distanceM = 800.0, restSec = 0, intensity = "aerobic",
                    stroke = "freestyle", label = "Long 800",
                ),
                WorkoutStep(type = "cooldown", distanceM = 200.0, durationMin = 4.0, intensity = "easy"),
            ),
            workoutId = 114, timeCreatedMillis = timeCreated, paceCoaching = true, lthr = 165,
        )
        assertContentEquals(hex(SWIM_POOL), bytes)
    }

    @Test
    fun `rowing steers steady state by heart rate and pieces by stroke rate`() {
        val bytes = WorkoutFit.endurance(
            name = "AT Pieces", sport = "rowing",
            planSteps = listOf(
                WorkoutStep(type = "warmup", durationMin = 10.0, intensity = "easy"),
                WorkoutStep(type = "activity", durationMin = 20.0, intensity = "ut2", spmLow = 18, spmHigh = 20),
                WorkoutStep(
                    type = "effort_set", reps = 3, durationMinEach = 8.0, restMin = 3.0,
                    intensity = "threshold", label = "AT", spmLow = 22, spmHigh = 24,
                ),
                WorkoutStep(
                    type = "interval_set", reps = 4, distanceM = 500.0, restSec = 120,
                    intensity = "race_pace", label = "2k pace 500", spmLow = 28, spmHigh = 32,
                ),
            ),
            workoutId = 115, timeCreatedMillis = timeCreated, paceCoaching = true, lthr = 170,
        )
        assertContentEquals(hex(ROW_COACHED), bytes)
    }

    @Test
    fun `ski touring goes to the backcountry app with heart-rate bands`() {
        val bytes = WorkoutFit.endurance(
            name = "Threshold", sport = "backcountry_skiing",
            planSteps = listOf(
                WorkoutStep(type = "warmup", durationMin = 15.0, intensity = "easy"),
                WorkoutStep(
                    type = "effort_set", reps = 3, durationMinEach = 10.0, restMin = 3.0,
                    intensity = "threshold", label = "Threshold",
                ),
            ),
            workoutId = 116, timeCreatedMillis = timeCreated, paceCoaching = true, lthr = 165,
        )
        assertContentEquals(hex(SKIMO), bytes)
    }

    @Test
    fun `a climbing session opens the climbing app with timed, untargeted steps`() {
        val bytes = WorkoutFit.endurance(
            name = "Finger Strength", sport = "climbing",
            planSteps = listOf(
                WorkoutStep(
                    type = "effort_set", reps = 6, durationSecEach = 10.0, restSec = 180,
                    intensity = "max_strength", label = "Max hang",
                ),
            ),
            workoutId = 117, timeCreatedMillis = timeCreated, paceCoaching = true, lthr = 165,
        )
        assertContentEquals(hex(CLIMBING), bytes)
    }

    private fun hex(value: String): ByteArray =
        ByteArray(value.length / 2) {
            value.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
}


private const val RUN_COACHED =
    "0e20d852af0100002e464954a65d40000000000500010001028402028403048c04048600050100feff6500000000230d" +
    "4341000031000200028401010201310a004200001a0006fe0284080e070401000b010005048c06028402000054687265" +
    "73686f6c642052756e0001002000000005004300001b000bfe02840701000101000204861404860d0284000807030100" +
    "0404860504860604860300000200a0bb0d000000000002005761726d2055700000000000007d0b0000350c0000440000" +
    "1b000bfe02840701000101000204861404860d02840009070301000404860504860604860401000001a0860100000000" +
    "000200496e74657276616c000000000000770e0000a00f00004500001b0009fe02840701000101000204861404860d02" +
    "840005070301000404860502000100905f0100000000000200526573740002000000004600001b0007fe028407010001" +
    "01000204860301000404861404860603000006010000000204000000000000004700001b000bfe028407010001010002" +
    "04861404860d0284000a070301000404860504860604860704000300c0270900000000000200436f6f6c20446f776e00" +
    "00000000007d0b0000350c00002c59"

private const val MTB_COACHED =
    "0e20d852ae0100002e464954679140000000000500010001028402028403048c04048600050100feff6600000000230d" +
    "4341000031000200028401010201310a004200001a0006fe0284080b070401000b010005048c06028402000053776565" +
    "742053706f740002082000000005004300001b000bfe02840701000101000204861404860d0284000807030100040486" +
    "0504860604860300000200c02709000000000002005761726d205570000400000000650400008a0400004400001b000b" +
    "fe02840701000101000204861404860d0284000b07030100040486050486060486040100000000530700000000000200" +
    "53776565742053706f74000400000000c4040000d00400004500001b0009fe02840701000101000204861404860d0284" +
    "000507030100040486050200010080a90300000000000200526573740002000000004600001b0007fe02840701000101" +
    "000204860301000404861404860603000006010000000203000000000000004700001b000bfe02840701000101000204" +
    "861404860d0284000a070301000404860504860604860704000300c0270900000000000200436f6f6c20446f776e0004" +
    "000000004c04000071040000f07e"

private const val FARTLEK_PLAIN =
    "0e20d8525a0100002e464954692640000000000500010001028402028403048c04048600050100feff6700000000230d" +
    "4341000031000200028401010201310a004200001a0006fe02840808070401000b010005048c06028402000046617274" +
    "6c656b0001002000000005004300001b0009fe02840701000101000204861404860d0284000807030100040486030000" +
    "0200e09304000000000002005761726d2055700002000000004400001b0009fe02840701000101000204861404860d02" +
    "84000507030100040486040100000020bf0200000000000200486172640002000000000402000000c0d4010000000000" +
    "0200456173790002000000004500001b0007fe0284070100010100020486030100040486140486050300000601000000" +
    "0205000000000000004600001b0009fe02840701000101000204861404860d0284000a070301000404860604000300e0" +
    "930400000000000200436f6f6c20446f776e000200000000c4a4"

private const val EMPTY_FALLBACK =
    "0e20d8520d0100002e4649542dfc40000000000500010001028402028403048c04048600050100feff6800000000230d" +
    "4341000031000200028401010201310a004200001a0006fe02840809070401000b010005048c0602840200004f70656e" +
    "2052756e0001002000000003004300001b0009fe02840701000101000204861404860d02840008070301000404860300" +
    "000200e09304000000000002005761726d2055700002000000004400001b0008fe02840701000101000204861404860d" +
    "028403010004048604010000050000000000000000020002000000004500001b0009fe02840701000101000204861404" +
    "860d0284000a070301000404860502000300e0930400000000000200436f6f6c20446f776e0002000000000129"

private const val STRENGTH =
    "0e20d852640100002e464954ebbe40000000000500010001028402028403048c04048600050100feff6900000000230d" +
    "4341000031000200028401010201310a004200001a0007fe0284080b070401000b010005048c060284110e0702000055" +
    "7070657220426f6479000a142000000004005075736820616e642070756c6c004300001b000bfe028407010001010002" +
    "04861404860d02840301000404860a02840b02840c0284030000001d0800000000000000020002000000000000000070" +
    "174400001b0008fe02840701000101000204861404860d02840301000404860401000100c0d401000000000002000200" +
    "0000004500001b0007fe0284070100010100020486030100040486140486050200000600000000020300000000000000" +
    "030300001d0a000000000000000200020000000017000300a00f460000080104fe0284000284010284020c0706000000" +
    "00000042656e6368205072657373000601001700030042617262656c6c20526f7700602f"

private const val YOGA =
    "0e20d8526e0100002e4649546bc140000000000500010001028402028403048c04048600050100feff6a00000000230d" +
    "4341000031000200028401010201320a004200001a000bfe02840811070401000b010005048c0602840904860a048610" +
    "10021504861704860200004576656e696e67204d6f62696c697479000a2b2000000003000000000080a9030000000000" +
    "00000000000000000000000080a90300000000004300001b0009fe02840701000101000204860404861404860a02840b" +
    "02840d02840300000000c8af000000000000000000002400290002004400001b0007fe02840701000101000204860301" +
    "00040486140486040100000600000000020400000000000000030200000060ea0000000000000000000024000c000200" +
    "450000080104fe0284000284010284021e07050000240029004c6f77204c756e67652077697468204b6e656520446f77" +
    "6e20506f736500460000080104fe0284000284010284020c0706010024000c00436f7270736520506f736500d214"

private const val UNCOACHED_RUN =
    "0e20d8529d0000002e464954345040000000000500010001028402028403048c04048600050100feff6b00000000230d" +
    "4341000031000200028401010201310a004200001a0006fe02840809070401000b010005048c06028402000045617379" +
    "204461790001002000000001004300001b0009fe02840701000101000204861404860d02840009070301000404860300" +
    "000000e0322900000000000200456173792052756e0002000000005363"

private const val HR_ONLY =
    "0e20d852a70000002e464954b73b40000000000500010001028402028403048c04048600050100feff6c00000000230d" +
    "4341000031000200028401010201310a004200001a0006fe02840807070401000b010005048c060284020000536b696c" +
    "6c730002002000000001004300001b000bfe02840701000101000204861404860d028400070703010004048605048606" +
    "04860300000000009f2400000000000200536b696c6c73000100000000d2000000f4000000704e"

private const val STRENGTH_UNMAPPED =
    "0e20d852fb0000002e464954b25240000000000500010001028402028403048c04048600050100feff6d00000000230d" +
    "4341000031000200028401010201310a004200001a0006fe0284080a070401000b010005048c0602840200004f646420" +
    "4c69667473000a142000000003004300001b000afe02840701000101000204861404860d02840301000404860a02840b" +
    "0284030000001d060000000000000002000200000000feffffff4400001b0008fe02840701000101000204861404860d" +
    "0284030100040486040100010060ea000000000000020002000000004500001b0007fe02840701000101000204860301" +
    "00040486140486050200000600000000020200000000000000bba7"

private const val LONG_STRINGS =
    "0e20d852be0100002e464954669d40000000000500010001028402028403048c04048600050100feff6e00000000230d" +
    "4341000031000200028401010201310a004200001a0007fe02840832070401000b010005048c06028411fa0702000057" +
    "69646520e2809420c3a9c3a9c3a9c3a9c3a9c3a9c3a9c3a9c3a9c3a9c3a9c3a9c3a9c3a9c3a9c3a9c3a9c3a9c3a9c3a9" +
    "00010020000000010044657461696c20e2809420c3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bc" +
    "c3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bc" +
    "c3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bc" +
    "c3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bc" +
    "c3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bc" +
    "c3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bcc3bc004300001b0009fe02840701000101000204861404860d02840004070301" +
    "000404860300000000804f120000000000020052756e000200000000b922"

private const val DEFINITION_WRAP =
    "0e20d852670500002e464954ee6b40000000000500010001028402028403048c04048600050100feff6f00000000230d" +
    "4341000031000200028401010201310a004200001a0006fe02840807070401000b010005048c06028402000057696474" +
    "68730001002000000014004300001b0009fe02840701000101000204861404860d028400020703010004048603000000" +
    "00c0270900000000000200410002000000004400001b0009fe02840701000101000204861404860d0284000307030100" +
    "0404860401000000c027090000000000020041610002000000004500001b0009fe02840701000101000204861404860d" +
    "02840004070301000404860502000000c02709000000000002004161610002000000004600001b0009fe028407010001" +
    "01000204861404860d02840005070301000404860603000000c027090000000000020041616161000200000000470000" +
    "1b0009fe02840701000101000204861404860d02840006070301000404860704000000c0270900000000000200416161" +
    "61610002000000004800001b0009fe02840701000101000204861404860d02840007070301000404860805000000c027" +
    "09000000000002004161616161610002000000004900001b0009fe02840701000101000204861404860d028400080703" +
    "01000404860906000000c0270900000000000200416161616161610002000000004a00001b0009fe0284070100010100" +
    "0204861404860d02840009070301000404860a07000000c027090000000000020041616161616161610002000000004b" +
    "00001b0009fe02840701000101000204861404860d0284000a070301000404860b08000000c027090000000000020041" +
    "61616161616161610002000000004c00001b0009fe02840701000101000204861404860d0284000b070301000404860c" +
    "09000000c0270900000000000200416161616161616161610002000000004d00001b0009fe0284070100010100020486" +
    "1404860d0284000c070301000404860d0a000000c027090000000000020041616161616161616161610002000000004e" +
    "00001b0009fe02840701000101000204861404860d0284000d070301000404860e0b000000c027090000000000020041" +
    "61616161616161616161610002000000004f00001b0009fe02840701000101000204861404860d0284000e0703010004" +
    "04860f0c000000c0270900000000000200416161616161616161616161610002000000004000001b0009fe0284070100" +
    "0101000204861404860d0284000f07030100040486000d000000c0270900000000000200416161616161616161616161" +
    "61610002000000004100001b0009fe02840701000101000204861404860d0284001007030100040486010e000000c027" +
    "09000000000002004161616161616161616161616161610002000000004200001b0009fe028407010001010002048614" +
    "04860d0284001107030100040486020f000000c027090000000000020041616161616161616161616161616161000200" +
    "0000004300001b0009fe02840701000101000204861404860d02840012070301000404860310000000c0270900000000" +
    "00020041616161616161616161616161616161610002000000004400001b0009fe02840701000101000204861404860d" +
    "02840013070301000404860411000000c027090000000000020041616161616161616161616161616161616100020000" +
    "00004500001b0009fe02840701000101000204861404860d02840014070301000404860512000000c027090000000000" +
    "0200416161616161616161616161616161616161610002000000004600001b0009fe0284070100010100020486140486" +
    "0d02840015070301000404860613000000c0270900000000000200416161616161616161616161616161616161616100" +
    "0200000000d0a8"

private const val SCHEDULE =
    "0e10dd52e00000002e4649541dfd40000100000600010001028402028404048603048c05028400070001fffe44f56b12" +
    "00000001000140000100310200028401010200001a0040000100890afe0284000d070104860204860304860401000501" +
    "00060100070102080102000001417574756d6e20426c6f636b0044f97240451fadc0451fadc0010001023c400001001c" +
    "0900028401028402048c0304860501000604860401000a0102070284000001fffe00000065430d23000044f972400000" +
    "0001000001fffe00000066430d23010044fc154000000001000001fffe00000069430d23020044feb840000000011b2a"

private const val STRENGTH_BLOCKS =
    "0e20d852600200002e464954d94d40000000000500010001028402028403048c04048600050100feff7000000000230d" +
    "4341000031000200028401010201310a004200001a0006fe02840808070401000b010005048c06028402000043697263" +
    "756974000a14200000000b004300001b000bfe02840701000101000204861404860d02840301000404860a02840b0284" +
    "0c0284030000001d0a00000000000000020002000000001c002500d0074400001b0008fe028407010001010002048614" +
    "04860d0284030100040486040100010060ea000000000000020002000000004500001b0007fe02840701000101000204" +
    "860301000404861404860502000006000000000202000000000000000403000100c0d401000000000002000200000000" +
    "4600001b000afe02840701000101000204861404860d02840301000404860a02840b0284060400001d0c000000000000" +
    "000200020000000016004d00030500001d08000000000000000200020000000017000300a00f0406000100905f010000" +
    "00000002000200000000050700000604000000020300000000000000030800001d060000000000000002000200000000" +
    "000000007017060900001d05000000000000000200020000000015002600050a00000608000000020200000000000000" +
    "470000080104fe0284000284010284020d070700001c002500476f626c657420537175617400480000080104fe028400" +
    "028401028402080708010016004d005075736820557000490000080104fe0284000284010284020c0709020017000300" +
    "42617262656c6c20526f77000903000000000042656e6368205072657373000804001500260050756c6c20557000511d"

private const val YOGA_BLOCKS =
    "0e20d852040200002e464954de9640000000000500010001028402028403048c04048600050100feff7100000000230d" +
    "4341000031000200028401010201320a004200001a000bfe0284080c070401000b010005048c0602840904860a048610" +
    "1002150486170486020000466c6f7720526f756e6473000a2b2000000007000000000050a50500000000000000000000" +
    "0000000000000050a50500000000004300001b0009fe02840701000101000204860404861404860a02840b02840d0284" +
    "03000000003075000000000000000000002400100002000301000000c8af0000000000000000000024000c0002004400" +
    "001b0008fe02840701000101000204861404860d02840301000404860402000100983a00000000000002000200000000" +
    "4500001b0007fe0284070100010100020486030100040486140486050300000600000000020200000000000000040400" +
    "01003075000000000000020002000000000305000000409c000000000000000000002400290002000506000006050000" +
    "00020400000000000000460000080104fe028400028401028402130706000024001000446f6c7068696e20506c616e6b" +
    "20506f736500470000080104fe0284000284010284020c0707010024000c00436f7270736520506f7365004800000801" +
    "04fe0284000284010284021e07080200240029004c6f77204c756e67652077697468204b6e656520446f776e20506f73" +
    "6500b201"

private const val SWIM_POOL =
    "0e20d852bf0100002e464954a75140000000000500010001028402028403048c04048600050100feff7200000000230d" +
    "4341000031000200028401010201310a004200001a0006fe0284080a070401000b010005048c06028402000054656368" +
    "6e697175650005112000000006004300001b0009fe02840701000101000204861404860d028400080703010004048603" +
    "00000201307500000000000002005761726d2055700002000000004400001b000afe0284070100010100020486140486" +
    "0d02840005070301000404860901000401000001881300000000000002004b69636b000b04000000024500001b0009fe" +
    "02840701000101000204861404860d02840005070301000404860502000100204e000000000000020052657374000200" +
    "0000004600001b0007fe0284070100010100020486030100040486140486060300000601000000020600000000000000" +
    "4700001b0009fe02840701000101000204861404860d0284000907030100040486070400000180380100000000000200" +
    "4c6f6e6720383030000b000000004800001b0009fe02840701000101000204861404860d0284000a0703010004048608" +
    "05000301204e0000000000000200436f6f6c20446f776e000200000000c3d8"

private const val ROW_COACHED =
    "0e20d852190200002e4649541e0340000000000500010001028402028403048c04048600050100feff7300000000230d" +
    "4341000031000200028401010201310a004200001a0006fe0284080a070401000b010005048c06028402000041542050" +
    "6965636573000f0e2000000008004300001b000bfe02840701000101000204861404860d028400080703010004048605" +
    "04860604860300000200c02709000000000002005761726d205570000100000000d2000000ed0000004400001b000bfe" +
    "02840701000101000204861404860d02840004070301000404860504860604860401000000804f120000000000020055" +
    "7432000100000000e3000000f40000004500001b000bfe02840701000101000204861404860d02840003070301000404" +
    "86050486060486050200000000530700000000000200415400030000000016000000180000004600001b0009fe028407" +
    "01000101000204861404860d0284000507030100040486060300010020bf020000000000020052657374000200000000" +
    "4700001b0007fe0284070100010100020486030100040486140486070400000602000000020300000000000000480000" +
    "1b000bfe02840701000101000204861404860d0284000c07030100040486050486060486080500000150c30000000000" +
    "000200326b2070616365203530300003000000001c000000200000000606000100c0d401000000000002005265737400" +
    "020000000007070000060500000002040000000000000057f2"

private const val SKIMO =
    "0e20d8525f0100002e464954a91940000000000500010001028402028403048c04048600050100feff7400000000230d" +
    "4341000031000200028401010201310a004200001a0006fe0284080a070401000b010005048c06028402000054687265" +
    "73686f6c64000d252000000004004300001b000bfe02840701000101000204861404860d028400080703010004048605" +
    "04860604860300000200a0bb0d000000000002005761726d205570000100000000cf000000e90000004400001b000bfe" +
    "02840701000101000204861404860d0284000a070301000404860504860604860401000000c027090000000000020054" +
    "68726573686f6c64000100000000ff000000090100004500001b0009fe02840701000101000204861404860d02840005" +
    "07030100040486050200010020bf0200000000000200526573740002000000004600001b0007fe028407010001010002" +
    "0486030100040486140486060300000601000000020300000000000000c679"

private const val CLIMBING =
    "0e20d8520b0100002e464954add640000000000500010001028402028403048c04048600050100feff7500000000230d" +
    "4341000031000200028401010201310a004200001a0006fe02840810070401000b010005048c06028402000046696e67" +
    "657220537472656e677468001f442000000003004300001b0009fe02840701000101000204861404860d028400090703" +
    "01000404860300000000102700000000000002004d61782068616e670002000000004400001b0009fe02840701000101" +
    "000204861404860d0284000507030100040486040100010020bf0200000000000200526573740002000000004500001b" +
    "0007fe0284070100010100020486030100040486140486050200000600000000020600000000000000d088"

private const val YOGA_OWN_NAMES =
    "0e20d852cb0100002e464954a18640000000000500010001028402028403048c04048600050100feff7600000000230d" +
    "4341000031000200028401010201320a004200001a000bfe02840807070401000b010005048c0602840904860a048610" +
    "1002150486170486020000556e77696e64000a2b20000000060000000000a06806000000000000000000000000000000" +
    "0000a0680600000000004300001b0009fe02840701000101000204860404861404860a02840b02840d02840300000000" +
    "60ea00000000000000000000feff000002004400001b0007fe0284070100010100020486030100040486140486040100" +
    "000600000000020400000000000000030200000060ea00000000000000000000feff01000200030300000060ea000000" +
    "0000000000000024004c0002000304000000307500000000000000000000feff00000200040500000604000000020200" +
    "000000000000450000080104fe0284000284010284020e07050000feff0000436f756368205374726574636800460000" +
    "080104fe0284000284010284021907060100feff010054656e6e69732042616c6c20466f6f742052656c656173650047" +
    "0000080104fe028400028401028402110707020024004c005468756e646572626f6c7420506f736500c165"
