package dev.netcheck.app.monitor

import dev.netcheck.app.data.PingAttempt
import org.junit.Assert.assertEquals
import org.junit.Test

class MeasurementPolicyTest {
    @Test fun sleepingAcrossSeveralRoundsDoesNotReplayThem() {
        assertEquals(3L, MeasurementPolicy.missedIntervals(1000, 181_001))
        assertEquals(241_000L, MeasurementPolicy.nextDue(1000, 181_001))
    }
    @Test fun wallClockAdjustmentCannotCauseBacklog() {
        assertEquals(61_000L, MeasurementPolicy.nextDue(1000, 2000))
        assertEquals(0L, MeasurementPolicy.missedIntervals(1000, 500))
    }
    @Test fun mixedReachabilityIsPartialAndNotAHealthyAverage() {
        assertEquals("PARTIAL", MeasurementPolicy.status(listOf(PingAttempt("8.8.8.8", 1, 10.0, "SUCCESS"), PingAttempt("1.1.1.1", 1, null, "TIMEOUT")), 200))
    }
    @Test fun unsupportedIcmpIsNotCountedAsInternetFailure() {
        assertEquals("SKIPPED_ICMP_UNAVAILABLE", MeasurementPolicy.status(listOf(PingAttempt("8.8.8.8", 1, status = "UNSUPPORTED")), 200))
        assertEquals("SKIPPED_NO_RESULT", MeasurementPolicy.status(emptyList(), 200))
    }
    @Test fun slowOutlierIsPreserved() {
        assertEquals("SLOW", MeasurementPolicy.status(listOf(PingAttempt("8.8.8.8", 1, 10.0, "SUCCESS"), PingAttempt("1.1.1.1", 1, 220.0, "SUCCESS")), 200))
    }
}
