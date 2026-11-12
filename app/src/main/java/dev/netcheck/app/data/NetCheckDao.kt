package dev.netcheck.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao interface NetCheckDao {
    @Insert suspend fun insertSession(value: SessionEntity): Long
    @Query("UPDATE sessions SET endedAt=:at, status=:status WHERE id=:id") suspend fun endSession(id: Long, at: Long, status: String)
    @Query("UPDATE sessions SET endedAt=:at, status='INTERRUPTED' WHERE status='RUNNING'") suspend fun recoverSessions(at: Long)
    @Insert suspend fun insertMeasurement(value: MeasurementEntity): Long
    @Insert suspend fun insertEvent(value: EventEntity): Long
    @Insert suspend fun insertSpeedTest(value: SpeedTestEntity): Long
    @Query("SELECT * FROM sessions ORDER BY startedAt DESC") fun observeSessions(): Flow<List<SessionEntity>>
    @Query("SELECT * FROM measurements ORDER BY startedAt DESC LIMIT 5000") fun observeMeasurements(): Flow<List<MeasurementEntity>>
    @Query("SELECT * FROM events ORDER BY at DESC LIMIT 500") fun observeEvents(): Flow<List<EventEntity>>
    @Query("SELECT * FROM speed_tests ORDER BY startedAt DESC LIMIT 500") fun observeSpeedTests(): Flow<List<SpeedTestEntity>>
    @Query("SELECT * FROM sessions ORDER BY startedAt") suspend fun allSessions(): List<SessionEntity>
    @Query("SELECT * FROM measurements WHERE (:sessionId IS NULL OR sessionId=:sessionId) ORDER BY startedAt") suspend fun allMeasurements(sessionId: Long? = null): List<MeasurementEntity>
    @Query("SELECT * FROM events WHERE (:sessionId IS NULL OR sessionId=:sessionId) ORDER BY at") suspend fun allEvents(sessionId: Long? = null): List<EventEntity>
    @Query("SELECT * FROM speed_tests WHERE (:sessionId IS NULL OR sessionId=:sessionId) ORDER BY startedAt") suspend fun allSpeedTests(sessionId: Long? = null): List<SpeedTestEntity>
    @Query("DELETE FROM measurements WHERE sessionId=:id") suspend fun deleteMeasurements(id: Long)
    @Query("DELETE FROM events WHERE sessionId=:id") suspend fun deleteEvents(id: Long)
    @Query("DELETE FROM speed_tests WHERE sessionId=:id") suspend fun deleteSpeeds(id: Long)
    @Query("DELETE FROM sessions WHERE id=:id") suspend fun deleteSessionRow(id: Long)
    @Transaction suspend fun deleteSession(id: Long) { deleteMeasurements(id); deleteEvents(id); deleteSpeeds(id); deleteSessionRow(id) }
}
