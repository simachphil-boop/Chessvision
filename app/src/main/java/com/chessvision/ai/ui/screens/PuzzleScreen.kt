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
import com.chessvision.ai.ui.board.*
import com.chessvision.ai.ui.theme.CV
import com.chessvision.core.chess.*
import com.chessvision.core.engine.Puzzle
import com.chessvision.core.engine.PuzzleGenerator
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.pow
import kotlin.math.roundToInt

enum class PuzzleState { SOLVING, WRONG, SOLVED, SHOWN }

data class PuzzleUi(
    val puzzle: Puzzle? = null,
    val position: Position? = null,
    val lastMove: Move? = null,
    /** index into solution of the next expected solver move */
    val step: Int = 0,
    val state: PuzzleState = PuzzleState.SOLVING,
    val firstTry: Boolean = true,
    val hint: Int? = null,
    val ratingDelta: Int? = null,
    val total: Int = 0,
    val loading: Boolean = true,
    val generating: String? = null,
    val message: String? = null,
)

class PuzzleViewModel(private val g: AppGraph) : ViewModel() {
    private val _ui = MutableStateFlow(PuzzleUi())
    val ui = _ui.asStateFlow()
    private var genJob: Job? = null

    init { next() }

    fun next() {
        viewModelScope.launch {
            val all = g.puzzles.all()
            val p = g.puzzles.next(g.settings.value.puzzleRating, _ui.value.puzzle?.id)
            if (p == null) { _ui.value = PuzzleUi(loading = false, total = 0, message = "Задачи не найдены. Сгенерируй новые кнопкой ниже."); return@launch }
            load(p, all.size)
        }
    }

    private fun load(p: Puzzle, total: Int) {
        val pos = runCatching { Fen.parse(p.fen) }.getOrNull() ?: return next()
        _ui.value = PuzzleUi(puzzle = p, position = pos, lastMove = p.lastMove?.let { Move.fromUci(it) }, total = total, loading = false,
            generating = _ui.value.generating)
    }

    fun move(m: Move) {
        val u = _ui.value
        val p = u.puzzle ?: return
        val pos = u.position ?: return
        if (u.state != PuzzleState.SOLVING) return
        val expected = p.solution.getOrNull(u.step) ?: return
        val after = pos.play(m)
        val correct = m.uci() == expected || after.isCheckmate()
        if (!correct) {
            _ui.value = u.copy(position = after, lastMove = m, state = PuzzleState.WRONG, firstTry = false, hint = null)
            if (u.firstTry) rate(p, false)
            return
        }
        val done = after.isCheckmate() || u.step + 1 >= p.solution.size
        if (done) {
            _ui.value = u.copy(position = after, lastMove = m, state = PuzzleState.SOLVED, hint = null)
            if (u.firstTry) rate(p, true) else viewModelScope.launch { g.puzzles.record(p, true) }
            return
        }
        _ui.value = u.copy(position = after, lastMove = m, step = u.step + 1, hint = null)
        viewModelScope.launch {
            delay(450)
            val reply = Move.fromUci(p.solution[u.step + 1]) ?: return@launch
            val cur = _ui.value.position ?: return@launch
            if (!cur.isLegal(reply)) return@launch
            _ui.value = _ui.value.copy(position = cur.play(reply), lastMove = reply, step = u.step + 2)
        }
    }

    private fun rate(p: Puzzle, win: Boolean) {
        val r = g.settings.value.puzzleRating
        val expected = 1.0 / (1 + 10.0.pow((p.rating - r) / 400.0))
        val delta = (32 * ((if (win) 1.0 else 0.0) - expected)).roundToInt()
        g.settings.update { it.copy(puzzleRating = (r + delta).coerceIn(400, 3200)) }
        _ui.value = _ui.value.copy(ratingDelta = delta)
        viewModelScope.launch { g.puzzles.record(p, win) }
    }

    /** Undo the wrong move and try again (no rating change on retries). */
    fun retry() {
        val u = _ui.value
        val p = u.puzzle ?: return
        // rebuild position from start + played solution moves
        var pos = Fen.parse(p.fen)
        var last = p.lastMove?.let { Move.fromUci(it) }
        for (i in 0 until u.step) { val m = Move.fromUci(p.solution[i]) ?: break; pos = pos.play(m); last = m }
        _ui.value = u.copy(position = pos, lastMove = last, state = PuzzleState.SOLVING)
    }

    fun hint() {
        val u = _ui.value
        val p = u.puzzle ?: return
        val m = p.solution.getOrNull(u.step)?.let { Move.fromUci(it) } ?: return
        if (u.firstTry) { rate(p, false); _ui.value = _ui.value.copy(firstTry = false) }
        _ui.value = _ui.value.copy(hint = m.from)
    }

    fun showSolution() {
        val u = _ui.value
        val p = u.puzzle ?: return
        if (u.firstTry) rate(p, false)
        viewModelScope.launch {
            retry()
            var step = _ui.value.step
            _ui.value = _ui.value.copy(state = PuzzleState.SHOWN, firstTry = false)
            while (step < p.solution.size) {
                delay(650)
                val m = Move.fromUci(p.solution[step]) ?: break
                val cur = _ui.value.position ?: break
                if (!cur.isLegal(m)) break
                _ui.value = _ui.value.copy(position = cur.play(m), lastMove = m, step = step + 1)
                step++
            }
        }
    }

    /** Generates fresh, engine-verified puzzles on the device. */
    fun generate() {
        if (genJob?.isActive == true) { genJob?.cancel(); _ui.value = _ui.value.copy(generating = null); return }
        genJob = viewModelScope.launch {
            _ui.value = _ui.value.copy(generating = "Stockfish ищет тактики в партиях… (до 3 минут)")
            try {
                val e = g.engine.get()
                var found = 0
                PuzzleGenerator(e).generate(count = 3, timeBudgetMs = 180_000, idPrefix = "d${System.currentTimeMillis() % 1_000_000}_") { pz ->
                    found++
                    viewModelScope.launch { g.puzzles.addGenerated(pz) }
                    _ui.value = _ui.value.copy(generating = "Найдено задач: $found…")
                }
                _ui.value = _ui.value.copy(generating = null, message = if (found > 0) "Добавлено новых задач: $found" else "За отведённое время задач не найдено — попробуй ещё раз",
                    total = g.puzzles.all().size)
            } catch (ex: kotlinx.coroutines.CancellationException) { throw ex
            } catch (ex: Exception) { _ui.value = _ui.value.copy(generating = null, message = ex.message) }
        }
    }
}

@Composable
fun PuzzleScreen(g: AppGraph, onBack: () -> Unit) {
    val vm: PuzzleViewModel = viewModel { PuzzleViewModel(g) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val s by g.settings.flow.collectAsStateWithLifecycle()
    val pieceSet = remember(s.pieceSet) { g.pieceSet(s.pieceSet) }
    val theme = CV.BoardThemes.getOrElse(s.boardTheme) { CV.BoardThemes[0] }

    ScreenScaffold("Задачи", onBack, actions = {
        Chip("★ ${s.puzzleRating}", CV.Warn, Modifier.padding(end = 12.dp))
    }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val p = ui.puzzle
            val pos = ui.position
            if (ui.loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = CV.Accent, trackColor = CV.Surface3)
            if (p != null && pos != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (p.solverWhite) "Ход белых — найди лучшее продолжение" else "Ход чёрных — найди лучшее продолжение",
                        color = CV.Text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Chip("${p.rating}", CV.TextDim)
                }
                ChessBoard(pos, pieceSet, theme, Modifier.fillMaxWidth(), flipped = !p.solverWhite, lastMove = ui.lastMove,
                    showCoordinates = s.showCoordinates,
                    squareMarks = ui.hint?.let { mapOf(it to CV.Info.copy(alpha = 0.5f)) } ?: emptyMap(),
                    movable = if (ui.state == PuzzleState.SOLVING && pos.whiteToMove == p.solverWhite) p.solverWhite else null,
                    onMove = { vm.move(it) })
                when (ui.state) {
                    PuzzleState.SOLVING -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        ToolButton(Icons.Filled.Lightbulb, "Подсказка") { vm.hint() }
                        ToolButton(Icons.Filled.Visibility, "Решение") { vm.showSolution() }
                        ToolButton(Icons.Filled.SkipNext, "Пропустить") { vm.next() }
                    }
                    PuzzleState.WRONG -> {
                        InfoBanner("Не тот ход. ${ui.ratingDelta?.let { "Рейтинг $it." } ?: ""}", CV.Bad, Icons.Filled.Close)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SecondaryButton("Ещё раз", Modifier.weight(1f), Icons.Filled.Replay) { vm.retry() }
                            SecondaryButton("Решение", Modifier.weight(1f), Icons.Filled.Visibility) { vm.showSolution() }
                        }
                    }
                    PuzzleState.SOLVED, PuzzleState.SHOWN -> {
                        if (ui.state == PuzzleState.SOLVED) InfoBanner("Решено! ${p.themeRu}. ${ui.ratingDelta?.let { "Рейтинг ${if (it >= 0) "+$it" else "$it"}" } ?: ""}", CV.Accent, Icons.Filled.CheckCircle)
                        else InfoBanner("Решение: ${San.pvToSan(Fen.parse(p.fen), p.solution)} · ${p.themeRu}", CV.Info)
                        PrimaryButton("Следующая задача", Modifier.fillMaxWidth(), Icons.Filled.ArrowForward) { vm.next() }
                    }
                }
            }
            ui.message?.let { InfoBanner(it, CV.Info) }
            Panel {
                Text("Задач в базе: ${ui.total}", color = CV.Text)
                Text("Все задачи проверены Stockfish: решение — единственный сильный ход. Можно сгенерировать новые прямо на телефоне.",
                    color = CV.TextDim, style = MaterialTheme.typography.bodySmall)
                ui.generating?.let {
                    Spacer(Modifier.height(6.dp)); LinearProgressIndicator(Modifier.fillMaxWidth(), color = CV.Accent, trackColor = CV.Surface3)
                    Text(it, color = CV.TextDim, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))
                SecondaryButton(if (ui.generating != null) "Остановить генерацию" else "Сгенерировать новые задачи", Modifier.fillMaxWidth(), Icons.Filled.AutoAwesome) { vm.generate() }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
