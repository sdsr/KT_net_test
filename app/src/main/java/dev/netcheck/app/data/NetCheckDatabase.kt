package dev.netcheck.app.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [SessionEntity::class, MeasurementEntity::class, EventEntity::class, SpeedTestEntity::class], version = 1, exportSchema = false)
abstract class NetCheckDatabase : RoomDatabase() { abstract fun dao(): NetCheckDao }
