package dev.netcheck.app.network

internal object SpeedMath {
    const val MIB = 1_048_576L

    fun mbps(bytes: Long, elapsedNanos: Long): Double? =
        if (bytes <= 0 || elapsedNanos <= 0) null else bytes.toDouble() * 8_000.0 / elapsedNanos

    fun nextChunk(remaining: Long, index: Int): Long {
        if (remaining <= 0) return 0
        val stage = when (index) { 0 -> MIB; 1 -> 4 * MIB; else -> 16 * MIB }
        return minOf(remaining, stage)
    }
}
