package dev.netcheck.app.ui

import dev.netcheck.app.data.MeasurementEntity
import org.junit.Assert.*
import org.junit.Test

class RecordPresentationTest {
    private fun sample(id: Long, time: Long = id * 60000, session: Long = 1, age: Long? = 1000, accuracy: Float? = 10f): MeasurementEntity = MeasurementEntity(
        id = id, sessionId = session, scheduledAt = time, startedAt = time, finishedAt = time + 100,
        status = "SUCCESS", latitude = 37.5 + id * .001, longitude = 127.0, locationAgeMs = age, accuracyM = accuracy
    )

    @Test fun missingLocationBreaksRouteInsteadOfJoiningAcrossGap() {
        val data = listOf(sample(1), sample(2), sample(3).copy(latitude = null), sample(4), sample(5))
        assertEquals(listOf(listOf(1L, 2L), listOf(4L, 5L)), routeSegments(data).map { it.map(MeasurementEntity::id) })
    }

    @Test fun inaccurateStaleAndDifferentSessionCoordinatesAreNeverConnected() {
        assertFalse(validRoutePoint(sample(1, age = 120000)))
        assertFalse(validRoutePoint(sample(1, age = null)))
        assertFalse(validRoutePoint(sample(1, accuracy = 101f)))
        assertFalse(validRoutePoint(sample(1).copy(latitude = 91.0)))
        assertTrue(routeSegments(listOf(sample(1), sample(2, session = 2))).isEmpty())
        assertTrue(routeSegments(listOf(sample(1), sample(4))).isEmpty())
    }

    @Test fun missingResponsesDoNotBecomeZeroLatency() {
        val m = sample(1).copy(probesJson = """[{"rttMs":null,"status":"TIMEOUT"},{"rttMs":23.5,"status":"SUCCESS"},{"status":"UNSUPPORTED"}]""")
        assertEquals(listOf(23.5), pingTimes(m))
        assertEquals("—", numberText(null))
        assertEquals("—", numberText(Double.NaN))
    }

    @Test fun malformedModemJsonRemainsAnEmptyObservation() {
        assertTrue(jsonObjects("{broken").isEmpty())
        assertTrue(cellKeys(sample(1).copy(cellsJson = "null")).isEmpty())
    }
}
