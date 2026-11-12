package dev.netcheck.app.monitor

import dev.netcheck.app.data.PingAttempt

/** Monotonic-clock scheduling: never replay missed rounds in a burst. */
internal object MeasurementPolicy {
    const val INTERVAL_MS = 60_000L

    fun missedIntervals(dueElapsed: Long, nowElapsed: Long): Long =
        ((nowElapsed - dueElapsed).coerceAtLeast(0) / INTERVAL_MS)

    fun nextDue(dueElapsed: Long, finishedElapsed: Long): Long =
        dueElapsed + (missedIntervals(dueElapsed, finishedElapsed) + 1) * INTERVAL_MS

    fun status(attempts: List<PingAttempt>, slowMs: Long): String {
        if (attempts.isEmpty()) return "SKIPPED_NO_RESULT"
        if (attempts.all { it.status == "UNSUPPORTED" || it.status == "PERMISSION_DENIED" }) return "SKIPPED_ICMP_UNAVAILABLE"
        val answered = attempts.filter { it.rttMs != null && it.status == "SUCCESS" }
        if (answered.isEmpty()) return "FAILED"
        if (answered.size != attempts.size) return "PARTIAL"
        return if (answered.any { it.rttMs!! > slowMs }) "SLOW" else "SUCCESS"
    }
}
