// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.nudge

import com.tracks.core.nudge.WorkoutNudge.Start
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkoutNudgeTest {

    private val fallback = 17 * 60 + 30

    private fun at(h: Int, m: Int = 0) = h * 60 + m

    /** A start every day for [days] days, at [minute], ending yesterday relative to a Monday. */
    private fun daily(minute: Int, days: Int = 14, from: Int = 1) =
        (from until from + days).map { ago -> Start(minute, dayOfWeekAgo(ago), ago) }

    /** Planning for a Monday (ISO 1): the day [ago] days earlier. */
    private fun dayOfWeekAgo(ago: Int) = ((1 - 1 - ago) % 7 + 7) % 7 + 1

    /**
     * The core promise: someone who runs at 7:00 is reminded half an hour
     * before, not at the app's default.
     */
    @Test
    fun test_a_steady_morning_habit_is_reminded_half_an_hour_before() {
        val t = WorkoutNudge.timing(daily(at(7)), dayOfWeek = 1, fallbackMinute = fallback)
        assertTrue(t.learned)
        assertEquals(at(7), t.habitMinute)
        assertEquals(at(6, 30), t.minuteOfDay)
    }

    /** Three runs say nothing yet; a learned time from them would be a guess dressed as a fact. */
    @Test
    fun test_too_little_history_uses_the_fallback() {
        val t = WorkoutNudge.timing(daily(at(7), days = 3), dayOfWeek = 1, fallbackMinute = fallback)
        assertFalse(t.learned)
        assertEquals(fallback, t.minuteOfDay)
        assertNull(t.habitMinute)
    }

    /** Starts scattered round the clock have no peak worth aiming at. */
    @Test
    fun test_scattered_starts_use_the_fallback() {
        val scattered = listOf(6, 9, 12, 15, 18, 21, 8, 11, 14, 17, 20, 7).mapIndexed { i, h ->
            Start(at(h), dayOfWeekAgo(i + 1), i + 1)
        }
        assertFalse(WorkoutNudge.timing(scattered, dayOfWeek = 1, fallbackMinute = fallback).learned)
    }

    /**
     * Why a mean is not used: morning and evening runners in equal measure
     * would average to early afternoon, a time they never train. The peak
     * must land on one of the real habits.
     */
    @Test
    fun test_two_habits_pick_one_of_them_rather_than_the_midpoint() {
        val history = daily(at(7), days = 10) + daily(at(19), days = 8)
        val habit = WorkoutNudge.timing(history, dayOfWeek = 1, fallbackMinute = fallback).habitMinute!!
        assertTrue(habit in at(6, 30)..at(7, 30) || habit in at(18, 30)..at(19, 30), "habit at $habit")
    }

    /** A move from evening to morning runs is followed, not averaged away. */
    @Test
    fun test_recent_starts_outweigh_old_ones() {
        val old = daily(at(19), days = 20, from = 60)
        val recent = daily(at(6, 30), days = 8, from = 1)
        assertEquals(at(6, 30), WorkoutNudge.timing(old + recent, dayOfWeek = 1, fallbackMinute = fallback).habitMinute)
    }

    /** Weekday 6:30 runs and Saturday 9:00 long runs: a Saturday is reminded for the Saturday habit. */
    @Test
    fun test_weekends_learn_their_own_time() {
        val history = (1..56).map { ago ->
            val dow = dayOfWeekAgo(ago)
            Start(if (dow >= 6) at(9) else at(6, 30), dow, ago)
        }
        assertEquals(at(9), WorkoutNudge.timing(history, dayOfWeek = 6, fallbackMinute = fallback).habitMinute)
        assertEquals(at(6, 30), WorkoutNudge.timing(history, dayOfWeek = 2, fallbackMinute = fallback).habitMinute)
    }

    /** Nothing before 6:00, however early the habit — a reminder must not wake anyone. */
    @Test
    fun test_an_early_habit_is_never_reminded_before_six() {
        val t = WorkoutNudge.timing(daily(at(5)), dayOfWeek = 1, fallbackMinute = fallback)
        assertEquals(WorkoutNudge.EARLIEST_MIN, t.minuteOfDay)
    }

    /** And nothing late at night. */
    @Test
    fun test_a_late_habit_is_never_reminded_after_nine() {
        val t = WorkoutNudge.timing(daily(at(23)), dayOfWeek = 1, fallbackMinute = fallback)
        assertEquals(WorkoutNudge.LATEST_MIN, t.minuteOfDay)
    }

    /** Starts just either side of midnight are one habit, not two at opposite ends of the day. */
    @Test
    fun test_the_clock_wraps_at_midnight() {
        val history = (1..10).map { ago -> Start(if (ago % 2 == 0) at(23, 50) else at(0, 10), dayOfWeekAgo(ago), ago) }
        val habit = WorkoutNudge.timing(history, dayOfWeek = 1, fallbackMinute = fallback).habitMinute!!
        assertTrue(habit >= at(23, 30) || habit <= at(0, 30), "habit at $habit")
    }

    /** A season-old habit no longer counts. */
    @Test
    fun test_starts_outside_the_window_are_ignored() {
        val ancient = daily(at(7), days = 30, from = WorkoutNudge.WINDOW_DAYS + 1)
        assertFalse(WorkoutNudge.timing(ancient, dayOfWeek = 1, fallbackMinute = fallback).learned)
    }

    @Test
    fun test_a_day_already_trained_gets_no_reminder() {
        assertFalse(WorkoutNudge.shouldRemind(true, planActive = false, plannedToday = emptyList(), plannedTodayComplete = false))
    }

    /** Nagging on a rest day teaches people to ignore the reminder on the days it matters. */
    @Test
    fun test_a_planned_rest_day_gets_no_reminder() {
        assertFalse(WorkoutNudge.shouldRemind(false, planActive = true, plannedToday = emptyList(), plannedTodayComplete = false))
        assertFalse(WorkoutNudge.shouldRemind(false, planActive = true, plannedToday = listOf("rest"), plannedTodayComplete = false))
    }

    @Test
    fun test_a_planned_session_already_done_gets_no_reminder() {
        assertFalse(WorkoutNudge.shouldRemind(false, planActive = true, plannedToday = listOf("easy"), plannedTodayComplete = true))
    }

    @Test
    fun test_a_planned_session_not_yet_done_is_reminded() {
        assertTrue(WorkoutNudge.shouldRemind(false, planActive = true, plannedToday = listOf("easy"), plannedTodayComplete = false))
    }

    /** No plan at all: every untrained day is a candidate, which is what the user asked for. */
    @Test
    fun test_without_a_plan_an_untrained_day_is_reminded() {
        assertTrue(WorkoutNudge.shouldRemind(false, planActive = false, plannedToday = emptyList(), plannedTodayComplete = false))
    }
}
