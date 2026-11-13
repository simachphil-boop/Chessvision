package com.chessvision.ai.overlay

import com.chessvision.core.chess.Game
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the floating window shows: a study game/position coming from the main app. */
data class OverlaySession(
    val game: Game = Game(),
    val ply: Int = 0,
    val flipped: Boolean = false,
    val title: String = "Учебная доска",
    val version: Int = 0,
)

/**
 * Shared state between the main app and the floating window (single source of truth).
 * Positions only ever come from inside the app: analysis board, imported/reviewed games,
 * puzzles and the position editor. The overlay never reads other apps' screens.
 */
class OverlayRepository {
    private val _session = MutableStateFlow(OverlaySession())
    val session: StateFlow<OverlaySession> = _session.asStateFlow()

    private val _running = MutableStateFlow(false)
    /** true while the floating window is on screen. */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    fun open(game: Game, ply: Int = game.size, flipped: Boolean = false, title: String = "Учебная доска") {
        _session.value = OverlaySession(game.copy(), ply.coerceIn(0, game.size), flipped, title, _session.value.version + 1)
    }

    fun update(f: (OverlaySession) -> OverlaySession) {
        _session.value = f(_session.value).let { it.copy(version = it.version + 1) }
    }

    internal fun setRunning(r: Boolean) { _running.value = r }
}
