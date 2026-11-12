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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chessvision.ai.AppGraph
import com.chessvision.ai.data.MistakeEntity
import com.chessvision.ai.engine.AnalysisController
import com.chessvision.ai.engine.UiLine
import com.chessvision.ai.ui.board.*
import com.chessvision.ai.ui.theme.CV
import com.chessvision.core.chess.*
import com.chessvision.core.engine.EngineStrength
import com.chessvision.core.engine.SearchLimit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One "find the engine's move" exercise. */
data class Exercise(val fen: String, val source: String, val mistake: MistakeEntity? = null, val note: String? = null)

data class Verdict(val title: String, val text: String, val color: Color, val good: Boolean)

data class TrainingUi(
    val source: Int = 0, // 0 own mistakes, 1 positions from puzzles
    val exercise: Exercise? = null,
    val position: Position? = null,
    val userMove: Move? = null,
    val evaluating: Boolean = false,
    val verdict: Verdict? = null,
    val lines: List<UiLine> = emptyList(),
    val solved: Int = 0,
    val attempted: Int = 0,
    val streak: Int = 0,
    val empty: Boolean = false,
    val error: String? = null,
)

class TrainingViewModel(private val g: AppGraph) : ViewModel() {
    private val _ui = MutableStateFlow(TrainingUi())
    val ui = _ui.asStateFlow()
    private var queue = ArrayDeque<Exercise>()

    init { viewModelScope.launch { refill(); next() } }

    fun setSource(s: Int) {
        _ui.value = _ui.value.copy(source = s)
        viewModelScope.launch { queue.clear(); refill(); next() }
    }

    private suspend fun refill() {
        val ex = if (_ui.value.source == 0) g.db.mistakes().all().map {
            Exercise(it.fen, "Ошибка из твоей партии", it, "В партии было сыграно ${it.playedSan}")
        } else g.puzzles.all().shuffled().take(30).map { Exercise(it.fen, "Позиция из задач") }
        queue = ArrayDeque(ex)
    }

    fun next() {
        val e = queue.removeFirstOrNull()
        if (e == null) { _ui.value = _ui.value.copy(exercise = null, position = null, empty = true, verdict = null, lines = emptyList()); return }
        val p = runCatching { Fen.parse(e.fen) }.getOrNull() ?: return next()
        _ui.value = _ui.value.copy(exercise = e, position = p, userMove = null, verdict = null, lines = emptyList(), empty = false, error = null)
    }

    fun retry() { _ui.value = _ui.value.copy(userMove = null, verdict = null, lines = emptyList()) }

    fun move(m: Move) {
        val u = _ui.value
        val pos = u.position ?: return
        if (u.userMove != null || u.evaluating) return
        _ui.value = u.copy(userMove = m, evaluating = true)
        viewModelScope.launch {
            try {
                val e = g.engine.get()
                val fen = Fen.toFen(pos)
                val full = EngineStrength()
                val r = e.search(fen, limit = SearchLimit(depth = 15), multiPv = 3, strength = full)
                val lines = AnalysisController.toUi(pos, r.lines)
                val sign = if (pos.whiteToMove) 1 else -1
                val bestKey = (r.best?.score?.sortKey() ?: 0) * sign
                val userKey: Int = if (r.bestMove == m.uci()) bestKey else {
                    val inLines = r.lines.firstOrNull { it.pv.firstOrNull() == m.uci() }
                    if (inLines != null) inLines.score.sortKey() * sign else {
                        val after = pos.play(m)
                        if (after.isCheckmate()) 100_000 else {
                            val r2 = e.search(Fen.toFen(after), limit = SearchLimit(depth = 14), strength = full)
                            (r2.best?.score?.sortKey() ?: 0) * sign
                        }
                    }
                }
                val loss = (bestKey - userKey).coerceAtLeast(0)
                val bestSan = r.bestMove?.let { Move.fromUci(it) }?.takeIf { pos.isLegal(it) }?.let { San.toSan(pos, it) } ?: "?"
                val userSan = San.toSan(pos, m)
                val v = when {
                    r.bestMove == m.uci() -> Verdict("Точно как Stockfish!", "$userSan — лучший ход.", CV.Accent, true)
                    loss <= 30 -> Verdict("Отлично", "$userSan почти не уступает лучшему ходу $bestSan (−${loss} сп).", CV.Accent, true)
                    loss <= 90 -> Verdict("Неплохо, но есть лучше", "$userSan теряет ${"%.1f".format(loss / 100.0)} пешки. Движок предлагает $bestSan.", CV.Warn, false)
                    loss >= 50_000 -> Verdict("Упущен мат", "Был форсированный мат: $bestSan.", CV.Bad, false)
                    else -> Verdict("Ошибка", "$userSan теряет ${"%.1f".format(loss / 100.0)} пешки. Лучший ход — $bestSan.", CV.Bad, false)
                }
                val ex = _ui.value.exercise
                if (v.good && ex?.mistake != null) g.db.mistakes().markSolved(ex.mistake.id)
                _ui.value = _ui.value.copy(
                    evaluating = false, verdict = v, lines = lines,
                    solved = _ui.value.solved + if (v.good) 1 else 0, attempted = _ui.value.attempted + 1,
                    streak = if (v.good) _ui.value.streak + 1 else 0,
                )
            } catch (ex: kotlinx.coroutines.CancellationException) { throw ex
            } catch (ex: Exception) { _ui.value = _ui.value.copy(evaluating = false, error = ex.message) }
        }
    }
}

@Composable
fun TrainingScreen(g: AppGraph, onBack: () -> Unit) {
    val vm: TrainingViewModel = viewModel { TrainingViewModel(g) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val s by g.settings.flow.collectAsStateWithLifecycle()
    val pieceSet = remember(s.pieceSet) { g.pieceSet(s.pieceSet) }
    val theme = CV.BoardThemes.getOrElse(s.boardTheme) { CV.BoardThemes[0] }

    ScreenScaffold("Тренировка", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(ui.source == 0, { vm.setSource(0) }, { Text("Мои ошибки") })
                FilterChip(ui.source == 1, { vm.setSource(1) }, { Text("Позиции задач") })
                Spacer(Modifier.weight(1f))
                Text("🔥 ${ui.streak}  ✓ ${ui.solved}/${ui.attempted}", color = CV.TextDim)
            }
            val pos = ui.position
            if (ui.empty || pos == null) {
                Panel {
                    Text(if (ui.source == 0) "Ошибок для тренировки пока нет" else "Нет позиций", style = MaterialTheme.typography.titleMedium, color = CV.Text)
                    Text("Разбери свои партии в Game Review — неточности, ошибки и зевки автоматически попадут сюда. Здесь нужно найти ход, который предлагает Stockfish.",
                        color = CV.TextDim, style = MaterialTheme.typography.bodySmall)
                }
                return@Column
            }
            val ex = ui.exercise!!
            Text("${ex.source}: найди лучший ход за ${if (pos.whiteToMove) "белых" else "чёрных"}", color = CV.Text, fontWeight = FontWeight.SemiBold)
            val shown = ui.userMove?.let { pos.play(it) } ?: pos
            val best = ui.lines.firstOrNull()?.pvUci?.firstOrNull()?.let { Move.fromUci(it) }
            val arrows = if (ui.verdict != null) ui.lines.mapNotNull { l ->
                l.pvUci.firstOrNull()?.let { Move.fromUci(it) }?.let { BoardArrow(it.from, it.to, CV.LineColors.getOrElse(l.multipv - 1) { CV.TextDim }, if (l.multipv == 1) 0.2f else 0.12f) }
            }.reversed() else emptyList()
            ChessBoard(shown, pieceSet, theme, Modifier.fillMaxWidth(), flipped = !pos.whiteToMove, lastMove = ui.userMove,
                arrows = arrows, showCoordinates = s.showCoordinates,
                squareMarks = if (best != null && ui.verdict != null) mapOf(best.to to CV.Accent.copy(alpha = 0.3f)) else emptyMap(),
                movable = if (ui.userMove == null) pos.whiteToMove else null, onMove = { vm.move(it) })
            ex.note?.let { if (ui.verdict != null) Text(it, color = CV.TextDim, style = MaterialTheme.typography.bodySmall) }
            if (ui.evaluating) Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = CV.Accent); Spacer(Modifier.width(8.dp)); Text("Stockfish оценивает ход…", color = CV.TextDim)
            }
            ui.error?.let { InfoBanner(it, CV.Bad, Icons.Filled.ErrorOutline) }
            ui.verdict?.let { v ->
                Panel {
                    Text(v.title, color = v.color, style = MaterialTheme.typography.titleLarge)
                    Text(v.text, color = CV.Text)
                    Spacer(Modifier.height(8.dp))
                    for (l in ui.lines) EngineLineRow(l) {}
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("Ещё раз", Modifier.weight(1f), Icons.Filled.Replay) { vm.retry() }
                    PrimaryButton("Дальше", Modifier.weight(1f), Icons.Filled.ArrowForward) { vm.next() }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
