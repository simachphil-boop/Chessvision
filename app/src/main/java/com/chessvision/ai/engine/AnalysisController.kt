package com.chessvision.ai.engine

import com.chessvision.ai.data.SettingsStore
import com.chessvision.core.chess.Fen
import com.chessvision.core.chess.Position
import com.chessvision.core.chess.San
import com.chessvision.core.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A displayable engine line (SAN already computed). */
data class UiLine(
    val multipv: Int,
    val score: Score,
    val depth: Int,
    val pvUci: List<String>,
    val pvSan: String,
)

data class AnalysisState(
    val fen: String? = null,
    val running: Boolean = false,
    val depth: Int = 0,
    val targetDepth: Int = 0,
    val lines: List<UiLine> = emptyList(),
    val nps: Long = 0,
    val error: String? = null,
    val engineName: String? = null,
) {
    val best: UiLine? get() = lines.firstOrNull()
}

/**
 * Live analysis of the current position with throttled UI updates.
 * Calling [analyze] cancels the previous search immediately (the engine gets "stop").
 */
class AnalysisController(
    private val scope: CoroutineScope,
    private val engines: EngineManager,
    private val settings: SettingsStore,
) {
    private val _state = MutableStateFlow(AnalysisState())
    val state: StateFlow<AnalysisState> = _state.asStateFlow()
    private var job: Job? = null

    fun analyze(position: Position, depthOverride: Int? = null, multiPvOverride: Int? = null) {
        job?.cancel()
        val fen = Fen.toFen(position)
        val s = settings.value
        val depth = depthOverride ?: s.analysisDepth
        val multi = (multiPvOverride ?: s.multiPv).coerceIn(1, 5)
        if (position.legalMoves().isEmpty()) {
            val score = if (position.inCheck()) Score(cp = if (position.whiteToMove) -Score.MATED else Score.MATED) else Score(cp = 0)
            _state.value = AnalysisState(fen = fen, running = false, lines = listOf(UiLine(1, score, 0, emptyList(),
                if (position.inCheck()) "Мат" else "Пат")))
            return
        }
        _state.value = AnalysisState(fen = fen, running = true, targetDepth = depth, engineName = _state.value.engineName)
        val prev = job
        job = scope.launch {
            prev?.cancelAndJoin()
            try {
                val e = engines.get()
                var lastEmit = 0L
                val strength = EngineStrength(skill = s.analysisSkill)
                val r = e.search(fen, limit = SearchLimit(depth = depth), multiPv = multi, strength = strength) { snap ->
                    val now = System.currentTimeMillis()
                    if (now - lastEmit > 140) {
                        lastEmit = now
                        _state.value = AnalysisState(fen, true, snap.depth, depth, toUi(position, snap.lines), snap.nps, null, e.engineName)
                    }
                }
                _state.value = AnalysisState(fen, false, r.lines.maxOfOrNull { it.depth } ?: depth, depth, toUi(position, r.lines),
                    _state.value.nps, null, e.engineName)
            } catch (c: CancellationException) {
                throw c
            } catch (ex: Exception) {
                _state.value = AnalysisState(fen, false, error = ex.message ?: "Ошибка движка")
            }
        }
    }

    fun stop() {
        job?.cancel()
        _state.value = _state.value.copy(running = false)
    }

    companion object {
        fun toUi(position: Position, lines: List<PvLine>): List<UiLine> = lines.sortedBy { it.multipv }.map {
            UiLine(it.multipv, it.score, it.depth, it.pv, San.pvToSan(position, it.pv, 14))
        }
    }
}
