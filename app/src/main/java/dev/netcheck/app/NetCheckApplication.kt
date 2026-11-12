package dev.netcheck.app

import android.app.Application
import androidx.room.Room
import dev.netcheck.app.data.AppSettings
import dev.netcheck.app.data.NetCheckDatabase
import kotlinx.coroutines.*

class NetCheckApplication : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val database by lazy { Room.databaseBuilder(this, NetCheckDatabase::class.java, "netcheck.db").build() }
    val dao get() = database.dao()
    val settings by lazy { AppSettings(this) }
    lateinit var recovery: Job
        private set
    override fun onCreate() { super.onCreate(); recovery = scope.launch { dao.recoverSessions(System.currentTimeMillis()) } }
}
