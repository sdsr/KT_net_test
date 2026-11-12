package dev.netcheck.app.network

import org.junit.Assert.*
import org.junit.Test

class SpeedMathTest {
    @Test fun usesMegabitsNotMegabytesAndRejectsMissingData() {
        assertEquals(8.0, SpeedMath.mbps(1_000_000, 1_000_000_000)!!, 0.000001)
        assertNull(SpeedMath.mbps(0, 1))
        assertNull(SpeedMath.mbps(1, 0))
    }

    @Test fun stagedRequestsNeverExceedAnyOfferedBudget() {
        listOf(5L, 20L, 100L).forEach { mib ->
            var remaining = mib * SpeedMath.MIB
            var index = 0
            var total = 0L
            while (remaining > 0) {
                val chunk = SpeedMath.nextChunk(remaining, index++)
                assertTrue(chunk in 1..remaining)
                assertTrue(chunk <= 16 * SpeedMath.MIB)
                remaining -= chunk
                total += chunk
            }
            assertEquals(mib * SpeedMath.MIB, total)
            assertEquals(0L, SpeedMath.nextChunk(remaining, index))
        }
    }
}
