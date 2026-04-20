package com.trektracker.tracking

/**
 * Computes the instantaneous grade (percent rise over run) over a sliding window
 * of horizontal displacement. Distance-based (not time-based) so that standing
 * still doesn't corrupt the reading.
 *
 * Feed each new `(cumulativeDistanceM, elevationM)` pair; poll `currentGradePct`.
 * Returns null while the window hasn't yet filled (prevents early max/min
 * pollution from under-sampled early reads).
 */
class GradeCalculator(private val windowMeters: Double = 20.0) {

    private data class Sample(val dist: Double, val elev: Double)

    private val buffer: ArrayDeque<Sample> = ArrayDeque()

    fun add(cumulativeDistanceM: Double, elevationM: Double) {
        buffer.addLast(Sample(cumulativeDistanceM, elevationM))
        // Trim head while the second-oldest sample is still within the window.
        // Keep exactly one sample older than windowMeters so the span covers >= windowMeters.
        while (buffer.size >= 2 &&
            cumulativeDistanceM - buffer[1].dist >= windowMeters
        ) {
            buffer.removeFirst()
        }
    }

    /** Returns percent grade, or null if the window hasn't filled yet. */
    fun currentGradePct(): Double? {
        if (buffer.size < 2) return null
        val oldest = buffer.first()
        val newest = buffer.last()
        val run = newest.dist - oldest.dist
        if (run < windowMeters) return null
        if (run <= 0.0) return null
        val rise = newest.elev - oldest.elev
        return rise / run * 100.0
    }

    fun reset() {
        buffer.clear()
    }
}
