package dev.netcheck.app.data

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DatabaseTest {
    private lateinit var database: NetCheckDatabase
    private val dao get() = database.dao()
    @Before fun setup() { database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), NetCheckDatabase::class.java).allowMainThreadQueries().build() }
    @After fun close() { database.close() }
    @Test fun deletionOnlyRemovesSelectedSessionAndItsRecords() = runBlocking {
        val first = dao.insertSession(SessionEntity(startedAt = 100))
        val second = dao.insertSession(SessionEntity(startedAt = 200))
        for (id in listOf(first, second)) {
            dao.insertMeasurement(MeasurementEntity(sessionId=id,scheduledAt=100,startedAt=100,finishedAt=110,status="SUCCESS"))
            dao.insertEvent(EventEntity(sessionId=id,kind="NOTE",detail="example"))
            dao.insertSpeedTest(SpeedTestEntity(sessionId=id,startedAt=100,finishedAt=110,status="LIMITED"))
        }
        dao.deleteSession(first)
        assertEquals(listOf(second), dao.allSessions().map { it.id })
        assertEquals(listOf(second), dao.allMeasurements().map { it.sessionId })
        assertEquals(listOf(second), dao.allEvents().map { it.sessionId })
        assertEquals(listOf(second), dao.allSpeedTests().map { it.sessionId })
    }
    @Test fun recoveryMarksOnlyUnfinishedSessionsInterrupted() = runBlocking {
        dao.insertSession(SessionEntity(startedAt=100))
        val finished = dao.insertSession(SessionEntity(startedAt=200))
        dao.endSession(finished,300,"STOPPED")
        dao.recoverSessions(400)
        val records = dao.allSessions()
        assertEquals("INTERRUPTED", records[0].status)
        assertEquals(400L, records[0].endedAt)
        assertEquals("STOPPED", records[1].status)
        assertEquals(300L, records[1].endedAt)
    }
    @Test fun missingLocationAndSignalJsonAreNotInvented() = runBlocking {
        val session = dao.insertSession(SessionEntity(startedAt=100))
        dao.insertMeasurement(MeasurementEntity(sessionId=session,scheduledAt=100,startedAt=105,finishedAt=110,status="SKIPPED_NO_CELLULAR",cellsJson="[]"))
        val record = dao.allMeasurements().single()
        assertNull(record.latitude)
        assertNull(record.locationAgeMs)
        assertEquals("[]", record.cellsJson)
        assertEquals(105L, record.startedAt)
    }
}
