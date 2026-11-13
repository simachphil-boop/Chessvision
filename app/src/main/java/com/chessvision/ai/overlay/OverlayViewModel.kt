package com.chessvision.ai.overlay

import com.chessvision.ai.AppGraph
import com.chessvision.ai.engine.AnalysisController
import com.chessvision.core.chess.Move
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/**
 * Overlay logic. Reuses the app's AnalysisController / EngineManager (same Stockfish process),
 * reads and writes the shared OverlayRepository. Lives as long as the service's scope.
 */
class OverlayViewModel(val graph: AppGraph, scope: CoroutineScope) {
    private val g = graph
    val repo = g.overlay
    val analysis = AnalysisController(scope, g.engine, g.settings)
    private val _engineOn = MutableStateFlow(true)
    val engineOn = _engineOn.asStateFlow()

    init {
        repo.session.map { it.game.positions[it.ply.coerceIn(0, it.game.size)] }
            .distinctUntilChanged()
            .onEach { reanalyze() }
            .launchIn(scope)
    }

    private fun reanalyze() {
        val s = repo.session.value
        if (_engineOn.value) analysis.analyze(s.game.positions[s.ply.coerceIn(0, s.game.size)]) else analysis.stop()
    }

    fun toggleEngine() { _engineOn.value = !_engineOn.value; reanalyze() }
    fun stop() { _engineOn.value = false; analysis.stop() }

    fun move(m: Move) = repo.update { s ->
        val game = s.game.copy(); game.truncate(s.ply)
        if (game.play(m)) s.copy(game = game, ply = game.size) else s
    }

    fun goTo(ply: Int) = repo.update { it.copy(ply = ply.coerceIn(0, it.game.size)) }
    fun flip() = repo.update { it.copy(flipped = !it.flipped) }

    /** Plays the engine's best move on the study board. */
    fun playBest() {
        val uci = analysis.state.value.best?.pvUci?.firstOrNull() ?: return
        Move.fromUci(uci)?.let { m ->
            val s = repo.session.value
            s.game.positions[s.ply].findMove(m.from, m.to, m.promotion)?.let { move(it) }
        }
    }

    fun close() = analysis.stop()
}
