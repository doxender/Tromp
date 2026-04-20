package com.trektracker.tracking

/**
 * State machine detecting when to auto-pause / auto-resume based on recent
 * speed. Feed each new `(epochMs, speedMps)` via [onSample]; the reported
 * [state] transitions according to DESIGN.md §6.4:
 *
 *   MOVING  -> PAUSED  when speed < pauseBelowMps for >= pauseStillMs continuous
 *   PAUSED  -> MOVING  when speed > resumeAboveMps for >= resumeMovingMs continuous
 */
class AutoPauseDetector(
    private val pauseBelowMps: Double = 0.5,
    private val pauseStillMs: Long = 30_000L,
    private val resumeAboveMps: Double = 1.0,
    private val resumeMovingMs: Long = 3_000L,
) {
    enum class State { MOVING, PAUSED }

    var state: State = State.MOVING
        private set

    private var stillSince: Long? = null
    private var movingSince: Long? = null

    fun onSample(epochMs: Long, speedMps: Double) {
        when (state) {
            State.MOVING -> {
                if (speedMps < pauseBelowMps) {
                    val t = stillSince ?: epochMs.also { stillSince = it }
                    if (epochMs - t >= pauseStillMs) {
                        state = State.PAUSED
                        stillSince = null
                        movingSince = null
                    }
                } else {
                    stillSince = null
                }
            }
            State.PAUSED -> {
                if (speedMps > resumeAboveMps) {
                    val t = movingSince ?: epochMs.also { movingSince = it }
                    if (epochMs - t >= resumeMovingMs) {
                        state = State.MOVING
                        stillSince = null
                        movingSince = null
                    }
                } else {
                    movingSince = null
                }
            }
        }
    }

    fun reset() {
        state = State.MOVING
        stillSince = null
        movingSince = null
    }
}
