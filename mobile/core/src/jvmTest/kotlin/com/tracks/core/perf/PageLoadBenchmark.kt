// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.perf

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.tracks.core.fit.decode.CivilDate
import com.tracks.core.local.LocalImporter
import com.tracks.core.local.LocalLibrary
import com.tracks.core.local.LocalMetrics
import com.tracks.core.local.LocalPlanning
import com.tracks.core.local.LocalSources
import com.tracks.core.local.LocalTraining
import com.tracks.core.replica.ReplicaStore
import com.tracks.core.replica.TracksSchema
import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test

/**
 * Times what each page does when it opens, over a real account's files.
 *
 * Not a correctness test: it needs the personal corpus in
 * `~/Downloads/fit-files` (or TRACKS_PERF_FIT_DIR) and skips without it. It
 * exists because "the Training page takes five seconds" is a claim about data
 * volume that the synthetic fixtures are far too small to reproduce.
 */
class PageLoadBenchmark {

    private suspend fun <T> timed(label: String, block: suspend () -> T): T {
        val t0 = System.nanoTime()
        val out = block()
        println("PERF %-34s %8.1f ms".format(label, (System.nanoTime() - t0) / 1e6))
        return out
    }

    @Test
    fun time_each_page_load_over_a_real_corpus() = runBlocking {
        val dir = File(System.getenv("TRACKS_PERF_FIT_DIR") ?: "${System.getProperty("user.home")}/Downloads/fit-files")
        val files = dir.walk().filter { it.isFile && it.extension.equals("fit", true) }.toList()
        if (files.isEmpty()) {
            println("PageLoadBenchmark: no corpus, skipped")
            return@runBlocking
        }
        val db = File.createTempFile("perf", ".db").apply { delete(); deleteOnExit() }
        val driver = JdbcSqliteDriver("jdbc:sqlite:${db.path}")
        TracksSchema.create(driver)
        val library = LocalLibrary(driver)
        val sources = LocalSources(ReplicaStore(driver, { System.currentTimeMillis() }), library)
        val importer = LocalImporter(library, thresholds = { sources.importThresholds() })
        timed("import ${files.size} files") {
            for (f in files) {
                val bytes = f.readBytes()
                val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                runCatching { importer.import(sha, bytes) }
            }
        }
        println("PERF activities: ${library.activityRows().size}")
        sources.createValues(
            "goal",
            mapOf(
                "goal_type" to "event", "is_active" to true, "event_name" to "5K", "event_sport" to "running",
                "event_date" to "2026-11-15", "event_distance_meters" to 5000.0, "days_per_week" to 5,
                "include_strength" to true,
            ),
        )
        // What a real account has: thresholds in auto mode, which is what
        // sends importThresholds through the full-history search.
        for (mode in listOf("max_hr_mode", "threshold_hr_mode", "ftp_mode")) sources.writeSetting(mode, "auto")
        timed("auto thresholds: full history") { com.tracks.core.local.LocalAutoThresholds(sources, library).history() }
        val planning = LocalPlanning(sources, library)
        val today = CivilDate(2026, 9, 26)
        val goal = sources.goals().single()
        timed("training: regenerate plan") { planning.regenerate(goal, today, System.currentTimeMillis()) }
        val training = LocalTraining(sources)
        val metrics = LocalMetrics(library, sources)
        repeat(2) { round ->
            println("PERF --- round $round")
            timed("training: plannedWorkouts") { sources.plannedWorkouts("2026-06-28", "2026-12-24") }
            timed("training: goals") { sources.goals() }
            timed("training: phase") { planning.phase(goal, today) }
            timed("training: importThresholds") { sources.importThresholds() }
            timed("strength: exercises") { training.exercises() }
            timed("strength: standings") { training.standings() }
            timed("strength: history(all lifts)") { library.strengthHistory("2000-01-01", null).size }.also { println("PERF   history entries: $it") }
            timed("flexibility: stretches") { training.stretches() }
            timed("dashboard: summary") { metrics.summary(null) }
            timed("dashboard: trainingLoad") { metrics.trainingLoad(today, null) }
            timed("dashboard: coaching") { metrics.coaching(today, null) }
            timed("activities: list") { sources.activities() }
        }
    }
}
