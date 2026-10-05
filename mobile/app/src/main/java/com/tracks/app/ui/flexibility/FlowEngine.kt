// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.flexibility

/**
 * The flow player's clock and its decisions, with no Android in it.
 *
 * ## Deadlines, not a countdown
 *
 * Each phase ends at a wall-clock instant ([FlowSession.phaseEndsAt]) and the
 * remaining seconds are derived from it. A loop that subtracts one per second
 * drifts every time the device dozes or the ticker is late, and a flow is
 * played with the phone face-down on a mat, screen off, for twenty minutes —
 * exactly when ticks go missing. With a deadline a late tick simply catches up,
 * and a phase that ended while nobody was ticking is still over.
 *
 * ## Cues
 *
 * Every transition returns what should be *said* — the hands-free half of the
 * player. The words are built here ([spoken]) so they are tested with the
 * logic that decides them: "switch sides" is only right when the next hold is
 * the same stretch on the other side, and that is a fact about the holds, not
 * about text-to-speech.
 */
object FlowEngine {

    sealed interface Cue {
        /** A hold begins. [switchSides] when it is the other side of the one just done. */
        data class Hold(val hold: com.tracks.app.ui.flexibility.Hold, val switchSides: Boolean, val first: Boolean) : Cue
        data class Rest(val seconds: Int, val next: com.tracks.app.ui.flexibility.Hold?) : Cue
        data object Done : Cue
    }

    data class Step(val session: FlowSession, val cues: List<Cue> = emptyList())

    fun start(session: FlowSession, now: Long): Step {
        val first = session.holds.firstOrNull() ?: return Step(session.copy(phase = HoldPhase.Done, running = false))
        val s = session.copy(
            index = 0, phase = HoldPhase.Holding, running = true,
            phaseEndsAt = now + first.seconds * 1000L, pausedLeftMs = null,
            startedAtMillis = now, remaining = first.seconds,
        )
        return Step(s, listOf(Cue.Hold(first, switchSides = false, first = true)))
    }

    /** Bring the session up to [now], crossing as many phase ends as have passed. */
    fun tick(session: FlowSession, now: Long): Step {
        var s = session
        val cues = mutableListOf<Cue>()
        while (s.running && s.phase != HoldPhase.Done) {
            val endsAt = s.phaseEndsAt ?: break
            if (now < endsAt) break
            val step = if (s.phase == HoldPhase.Holding) endHold(s, endsAt) else nextHold(s, endsAt)
            s = step.session
            cues += step.cues
        }
        return Step(s.withRemaining(now), cues)
    }

    fun pause(s: FlowSession, now: Long): FlowSession {
        val endsAt = s.phaseEndsAt ?: return s
        if (!s.running) return s
        return s.copy(running = false, phaseEndsAt = null, pausedLeftMs = (endsAt - now).coerceAtLeast(0))
    }

    fun resume(s: FlowSession, now: Long): FlowSession {
        val left = s.pausedLeftMs ?: return s
        if (s.phase == HoldPhase.Done) return s
        return s.copy(running = true, phaseEndsAt = now + left, pausedLeftMs = null).withRemaining(now)
    }

    /** More time on whatever is running (or paused) now. */
    fun extend(s: FlowSession, seconds: Int, now: Long): FlowSession = when {
        s.phase == HoldPhase.Done -> s
        s.pausedLeftMs != null -> s.copy(pausedLeftMs = s.pausedLeftMs + seconds * 1000L).withRemaining(now)
        s.phaseEndsAt != null -> s.copy(phaseEndsAt = s.phaseEndsAt + seconds * 1000L).withRemaining(now)
        else -> s
    }

    /**
     * Get on with it: a hold goes to its rest, a rest to the next hold.
     *
     * One button for both, because skipping a stretch you cannot do today and
     * cutting a rest short are the same intent, and a player operated from the
     * floor should not make you work out which of two buttons you want.
     */
    fun skip(s: FlowSession, now: Long): Step {
        if (s.phase == HoldPhase.Done) return Step(s)
        val step = if (s.phase == HoldPhase.Holding) endHold(s, now) else nextHold(s, now)
        return Step(step.session.keepPause(s, now), step.cues)
    }

    /**
     * Back: a few seconds into a hold, restart it; otherwise the previous hold.
     * The same rule as a music player's back button, and for the same reason —
     * the usual intent is "again", and a second tap goes further.
     */
    fun back(s: FlowSession, now: Long): Step {
        val hold = s.hold ?: return Step(s)
        val elapsedMs = when {
            s.phase != HoldPhase.Holding -> Long.MAX_VALUE
            s.pausedLeftMs != null -> hold.seconds * 1000L - s.pausedLeftMs
            s.phaseEndsAt != null -> hold.seconds * 1000L - (s.phaseEndsAt - now)
            else -> 0
        }
        val target = when {
            s.phase == HoldPhase.Done -> s.holds.lastIndex
            s.phase == HoldPhase.Resting -> s.index
            elapsedMs > RESTART_WINDOW_MS || s.index == 0 -> s.index
            else -> s.index - 1
        }
        val h = s.holds[target]
        val next = s.copy(
            index = target, phase = HoldPhase.Holding, running = true,
            phaseEndsAt = now + h.seconds * 1000L, pausedLeftMs = null,
        ).keepPause(s, now).withRemaining(now)
        return Step(next, listOf(Cue.Hold(h, switchSides = false, first = false)))
    }

    /** What to say for [cue] — short, because it is heard mid-stretch. */
    fun spoken(cue: Cue): String = when (cue) {
        is Cue.Hold -> buildString {
            if (cue.switchSides) {
                append("Switch sides. ")
                append("${cue.hold.side} side, ${cue.hold.seconds} seconds.")
            } else {
                append(cue.hold.name)
                cue.hold.side?.let { append(", $it side") }
                append(". ${cue.hold.seconds} seconds.")
            }
        }
        is Cue.Rest -> cue.next?.let { "Rest. Next, ${it.name}${it.side?.let { s -> ", $s side" } ?: ""}." } ?: "Rest."
        Cue.Done -> "Flow complete. Nice work."
    }

    // ── Transitions ─────────────────────────────────────────────────────────

    private fun endHold(s: FlowSession, at: Long): Step {
        val rest = s.hold?.restSeconds ?: 0
        val isLast = s.index >= s.holds.lastIndex
        // No rest after the last hold, and none when none is set: straight on,
        // rather than a zero-second screen nobody can read.
        if (rest <= 0 || isLast) return nextHold(s, at)
        return Step(
            s.copy(phase = HoldPhase.Resting, phaseEndsAt = at + rest * 1000L),
            listOf(Cue.Rest(rest, s.next)),
        )
    }

    private fun nextHold(s: FlowSession, at: Long): Step {
        val next = s.index + 1
        if (next >= s.holds.size) {
            return Step(s.copy(phase = HoldPhase.Done, running = false, phaseEndsAt = null, remaining = 0),
                listOf(Cue.Done))
        }
        val prev = s.holds[s.index]
        val h = s.holds[next]
        val switch = h.name == prev.name && h.side != null && prev.side != null && h.side != prev.side
        return Step(
            s.copy(index = next, phase = HoldPhase.Holding, phaseEndsAt = at + h.seconds * 1000L),
            listOf(Cue.Hold(h, switchSides = switch, first = false)),
        )
    }

    /** A skip or back while paused stays paused, holding the new phase's full time. */
    private fun FlowSession.keepPause(before: FlowSession, now: Long): FlowSession =
        if (before.running || phase == HoldPhase.Done) this else pause(copy(running = true), now)

    private fun FlowSession.withRemaining(now: Long): FlowSession {
        val leftMs = pausedLeftMs ?: phaseEndsAt?.let { it - now } ?: 0
        return copy(remaining = ((leftMs.coerceAtLeast(0) + 999) / 1000).toInt())
    }

    private const val RESTART_WINDOW_MS = 3_000L
}
