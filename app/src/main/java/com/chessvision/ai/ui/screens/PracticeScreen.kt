package com.chessvision.ai.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chessvision.ai.AppGraph
import com.chessvision.ai.data.GameEntity
import com.chessvision.ai.ui.board.*
import com.chessvision.ai.ui.theme.CV
import com.chessvision.core.chess.*
import com.chessvision.core.engine.EngineStrength
import com.chessvision.core.engine.SearchLimit
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PracticeUi(
    val started: Boolean = false,
    val game: Game = Game(),
    val playerWhite: Boolean = true,
    val thinking: Boolean = false,
    val hint: Move? = null,
    val savedId: Long? = null,
    val error: String? = null,
    val levelLabel: String = "",
    val v: Int = 0,
)

class PracticeViewModel(private val g: AppGraph) : ViewModel() {
    private val _ui = MutableStateFlow(PracticeUi())
    val ui = _ui.asStateFlow()
    private var engineJob: Job? = null

    private fun upd(f: (PracticeUi) -> PracticeUi) { _ui.value = f(_ui.value).let { it.copy(v = it.v + 1) } }

    private fun strength(): Pair<EngineStrength, Long> {
        val s = g.settings.value
        return if (s.practiceSkillMode) EngineStrength(skill = s.practiceSkill) to (150L + s.practiceSkill * 40L)
        else EngineStrength(elo = s.practiceElo) to (300L + (s.practiceElo - 1320) / 4L)
    }

    fun levelLabel(): String {
        val s = g.settings.value
        return if (s.practiceSkillMode) "Stockfish, уровень ${s.practiceSkill}" else "Stockfish ${s.practiceElo}"
    }

    fun start(color: Int) { // 0 white, 1 black, 2 random
        val white = when (color) { 0 -> true; 1 -> false; else -> Math.random() < 0.5 }
        engineJob?.cancel()
        upd { PracticeUi(started = true, game = Game(), playerWhite = white, levelLabel = levelLabel()) }
        viewModelScope.launch { runCatching { g.engine.get().newGame() } }
        if (!white) engineMove()
    }

    fun playerMove(m: Move) {
        val u = _ui.value
        if (u.thinking || u.game.isOver() || u.game.current.whiteToMove != u.playerWhite) return
        val game = u.game.copy()
        if (!game.play(m)) return
        upd { it.copy(game = game, hint = null) }
        if (!game.isOver()) engineMove() else finish()
    }

    private fun engineMove() {
        engineJob?.cancel()
        engineJob = viewModelScope.launch {
            upd { it.copy(thinking = true, error = null) }
            try {
                val (st, ms) = strength()
                val e = g.engine.get()
                val game = _ui.value.game
                delay(250)
                val r = e.search(Fen.toFen(game.current), limit = SearchLimit(movetimeMs = ms), strength = st)
                val best = r.bestMove
                val g2 = _ui.value.game.copy()
                if (best != null && g2.playUci(best)) upd { it.copy(game = g2, thinking = false) }
                else upd { it.copy(thinking = false) }
                if (g2.isOver()) finish()
            } catch (ex: kotlinx.coroutines.CancellationException) { throw ex
            } catch (ex: Exception) { upd { it.copy(thinking = false, error = ex.message) } }
        }
    }

    fun hint() {
        val u = _ui.value
        if (u.thinking || u.game.isOver()) return
        viewModelScope.launch {
            runCatching {
                val r = g.engine.get().search(Fen.toFen(u.game.current), limit = SearchLimit(depth = 14), strength = EngineStrength())
                r.bestMove?.let { Move.fromUci(it) }
            }.getOrNull()?.let { m -> upd { it.copy(hint = m) } }
        }
    }

    fun undo() {
        engineJob?.cancel()
        val u = _ui.value
        val game = u.game.copy()
        game.resultOverride = null
        // take back until it is the player's turn again (at least one player move)
        if (game.size == 0) return
        game.undo()
        if (game.current.whiteToMove != u.playerWhite && game.size > 0) game.undo()
        if (game.current.whiteToMove != u.playerWhite) { upd { it.copy(game = game, thinking = false, hint = null, savedId = null) }; engineMove(); return }
        upd { it.copy(game = game, thinking = false, hint = null, savedId = null) }
    }

    fun resign() {
        val u = _ui.value
        if (u.game.isOver()) return
        engineJob?.cancel()
        val game = u.game.copy()
        game.resultOverride = if (u.playerWhite) GameResult.BLACK_WINS else GameResult.WHITE_WINS
        upd { it.copy(game = game, thinking = false) }
        finish()
    }

    private fun finish() {
        val u = _ui.value
        if (u.savedId != null || u.game.size < 2) return
        viewModelScope.launch {
            val game = u.game
            game.headers["White"] = if (u.playerWhite) "Вы" else u.levelLabel
            game.headers["Black"] = if (u.playerWhite) u.levelLabel else "Вы"
            game.headers["Event"] = "Practice"
            game.headers["Date"] = java.text.SimpleDateFormat("yyyy.MM.dd", java.util.Locale.US).format(java.util.Date())
            val id = g.db.games().insert(GameEntity(
                title = "Тренировочная партия · ${u.levelLabel}", white = game.headers["White"]!!, black = game.headers["Black"]!!,
                result = game.result().pgn, pgn = game.toPgn(), source = "practice",
            ))
            upd { it.copy(savedId = id) }
        }
    }

    fun newSetup() { engineJob?.cancel(); upd { PracticeUi() } }
}

@Composable
fun PracticeScreen(g: AppGraph, onBack: () -> Unit, onReview: (Long) -> Unit) {
    val vm: PracticeViewModel = viewModel { PracticeViewModel(g) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val s by g.settings.flow.collectAsStateWithLifecycle()
    val pieceSet = remember(s.pieceSet) { g.pieceSet(s.pieceSet) }
    val theme = CV.BoardThemes.getOrElse(s.boardTheme) { CV.BoardThemes[0] }

    ScreenScaffold("Игра против Stockfish", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!ui.started) {
                Panel {
                    Text("Сила соперника", style = MaterialTheme.typography.titleMedium, color = CV.Text)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(!s.practiceSkillMode, { g.settings.update { it.copy(practiceSkillMode = false) } }, { Text("По рейтингу Elo") })
                        FilterChip(s.practiceSkillMode, { g.settings.update { it.copy(practiceSkillMode = true) } }, { Text("Уровень 0–20") })
                    }
                    if (!s.practiceSkillMode) SliderRow("Рейтинг Elo", s.practiceElo, 1320..3190, step = 10) { v -> g.settings.update { it.copy(practiceElo = v) } }
                    else SliderRow("Уровень (0 — новичок)", s.practiceSkill, 0..20) { v -> g.settings.update { it.copy(practiceSkill = v) } }
                    Text("Elo от 1320 — официальное ограничение силы Stockfish (UCI_Elo). Для более слабой игры выбери «Уровень».",
                        color = CV.TextFaint, style = MaterialTheme.typography.bodySmall)
                }
                Text("Играть за", color = CV.TextDim)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton("Белых", Modifier.weight(1f)) { vm.start(0) }
                    SecondaryButton("Чёрных", Modifier.weight(1f)) { vm.start(1) }
                    SecondaryButton("Случайно", Modifier.weight(1f)) { vm.start(2) }
                }
            } else {
                val game = ui.game
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Memory, null, tint = CV.Bad)
                    Spacer(Modifier.width(6.dp))
                    Text(ui.levelLabel, color = CV.Text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (ui.thinking) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = CV.Accent); Spacer(Modifier.width(6.dp)); Text("думает…", color = CV.TextDim) }
                }
                ChessBoard(
                    game.current, pieceSet, theme, Modifier.fillMaxWidth(), flipped = !ui.playerWhite,
                    lastMove = game.moves.lastOrNull(), showCoordinates = s.showCoordinates,
                    arrows = ui.hint?.let { listOf(BoardArrow(it.from, it.to, CV.Info)) } ?: emptyList(),
                    movable = if (ui.thinking || game.isOver()) null else ui.playerWhite, onMove = { vm.playerMove(it) },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Person, null, tint = CV.Accent); Spacer(Modifier.width(6.dp))
                    Text("Вы (${if (ui.playerWhite) "белые" else "чёрные"})", color = CV.Text, modifier = Modifier.weight(1f))
                }
                val start = game.positions[0]
                MoveStrip(game.sans, start.fullmoveNumber, start.whiteToMove, game.size, {})
                ui.error?.let { InfoBanner(it, CV.Bad, Icons.Filled.ErrorOutline) }
                if (game.isOver()) {
                    val r = game.result()
                    val win = (r == GameResult.WHITE_WINS && ui.playerWhite) || (r == GameResult.BLACK_WINS && !ui.playerWhite)
                    val term = game.termination()
                    Panel {
                        Text(when { r == GameResult.DRAW -> "Ничья"; win -> "Победа!"; else -> "Поражение" },
                            style = MaterialTheme.typography.headlineMedium, color = if (win) CV.Accent else if (r == GameResult.DRAW) CV.Warn else CV.Bad)
                        Text("${r.pgn} · ${if (term != TerminationReason.NONE) term.ru else "сдача"}", color = CV.TextDim)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ui.savedId?.let { id -> PrimaryButton("Разобрать", Modifier.weight(1f), Icons.Filled.Insights) { onReview(id) } }
                            SecondaryButton("Новая игра", Modifier.weight(1f), Icons.Filled.Replay) { vm.newSetup() }
                        }
                    }
                } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    ToolButton(Icons.Filled.Undo, "Отменить", enabled = game.size > 0 && !ui.thinking) { vm.undo() }
                    ToolButton(Icons.Filled.Lightbulb, "Подсказка", enabled = !ui.thinking) { vm.hint() }
                    ToolButton(Icons.Filled.Flag, "Сдаться", tint = CV.Bad) { vm.resign() }
                    ToolButton(Icons.Filled.Replay, "Заново") { vm.newSetup() }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
