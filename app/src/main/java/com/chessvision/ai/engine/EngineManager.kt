package com.chessvision.ai.engine

import android.content.Context
import android.util.Log
import com.chessvision.ai.data.SettingsStore
import com.chessvision.core.engine.EngineException
import com.chessvision.core.engine.UciEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

sealed class EngineStatus {
    object Idle : EngineStatus()
    object Starting : EngineStatus()
    data class Ready(val name: String) : EngineStatus()
    data class Failed(val message: String) : EngineStatus()
}

/**
 * Owns the Stockfish process. The binary is packaged as libstockfish.so in the APK's native
 * library directory (extracted on install), which is the only place Android 10+ allows exec from.
 */
class EngineManager(private val context: Context, private val settings: SettingsStore) {
    private val lock = Mutex()
    private var engine: UciEngine? = null
    private var startedWith: Pair<Int, Int>? = null
    private val _status = MutableStateFlow<EngineStatus>(EngineStatus.Idle)
    val status: StateFlow<EngineStatus> = _status.asStateFlow()

    val binary: File get() = File(context.applicationInfo.nativeLibraryDir, "libstockfish.so")

    /** Returns a running engine, (re)starting it if needed. */
    suspend fun get(): UciEngine = lock.withLock {
        val s = settings.value
        val cfg = s.threads to s.hashMb
        engine?.let { e ->
            if (e.isAlive && startedWith == cfg) return@withLock e
            e.close()
        }
        val bin = binary
        if (!bin.exists()) {
            val msg = "Движок не найден в APK (${bin.absolutePath}). Переустановите приложение подходящей сборки (arm64/armv7)."
            _status.value = EngineStatus.Failed(msg)
            throw EngineException(msg)
        }
        _status.value = EngineStatus.Starting
        val e = UciEngine(listOf(bin.absolutePath), context.filesDir)
        try {
            e.start(threads = s.threads, hashMb = s.hashMb)
        } catch (ex: Exception) {
            Log.e("EngineManager", "start failed", ex)
            _status.value = EngineStatus.Failed(ex.message ?: "Не удалось запустить Stockfish")
            throw (ex as? EngineException) ?: EngineException(ex.message ?: "start failed", ex)
        }
        engine = e
        startedWith = cfg
        _status.value = EngineStatus.Ready(e.engineName)
        e
    }

    fun shutdown() {
        engine?.close()
        engine = null
        _status.value = EngineStatus.Idle
    }
}
