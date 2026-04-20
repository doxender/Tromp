package com.trektracker.tracking

/**
 * Holds the most recently acquired benchmark for the current app session.
 * Not persisted — the value is consumed when an activity is started (written
 * into ActivityEntity.benchmarkElevM) and discarded on process death.
 */
object BenchmarkSession {

    data class Benchmark(
        val lat: Double,
        val lon: Double,
        val elevM: Double,
        val source: String,
        val horizAccM: Double,
        val fixCount: Int,
        val baroAvgHpa: Double?,
        val baroSampleCount: Int,
        val acquiredAtMs: Long,
    )

    @Volatile
    var current: Benchmark? = null

    @Volatile
    var qnhHpa: Double? = null

    fun clear() {
        current = null
        qnhHpa = null
    }
}
