package com.chessvision.ai.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chessvision.ai.AppGraph
import com.chessvision.ai.data.GameEntity
import com.chessvision.ai.engine.AnalysisController
import com.chessvision.ai.engine.UiLine
import com.chessvision.ai.ui.board.*
import com.chessvision.ai.ui.theme.CV
import com.chessvision.core.chess.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AnalysisUi(
    val game: Game = Game(),
    val ply: Int = 0,
    val flipped: Boolean = false,
    val engineOn: Boolean = true,
    val preview: UiLine? = null,
    val previewStep: Int = 0,
    val version: Int = 0,
) {
    val basePosition: Position get() = game.positions[ply]
    val previewPositions: List<Position> get() {
        val l = preview ?: return emptyList()
        val out = ArrayList<Position>()
        var p = basePosition
        for (u in l.pvUci) {
            val m = Move.fromUci(u) ?: break
            if (!p.isLegal(m)) break
            p = p.play(m); out.add(p)
        }
        return out
    }
    val shownPosition: Position get() = if (preview != null && previewStep > 0) previewPositions.getOrElse(previewStep - 1) { basePosition } else basePosition
    val shownLastMove: Move? get() = if (preview != null && previewStep > 0) Move.fromUci(preview.pvUci[previewStep - 1])
        else if (ply > 0) game.moves[ply - 1] else null
}

class AnalysisViewModel(private val g: AppGraph) : ViewModel() {
    private val _ui = MutableStateFlow(AnalysisUi())
    val ui = _ui.asStateFlow()
    val analysis = AnalysisController(viewModelScope, g.engine, g.settings)

    init {
        g.pendingGame?.let { pg ->
            g.pendingGame = null
            val flip = pg.headers.remove("Flip") == "1"
            _ui.value = AnalysisUi(game = pg, ply = pg.size, flipped = flip)
        }
        reanalyze()
    }

    private fun set(f: (AnalysisUi) -> AnalysisUi, analyze: Boolean = true) {
        _ui.value = f(_ui.value).let { it.copy(version = it.version + 1) }
        if (analyze) reanalyze()
    }

    fun reanalyze() {
        val u = _ui.value
        if (u.engineOn) analysis.analyze(u.basePosition) else analysis.stop()
    }

    fun move(m: Move) {
        val u = _ui.value
        if (u.preview != null) { applyPreview(u.previewStep); }
        val cur = _ui.value
        val game = cur.game.copy()
        game.truncate(cur.ply)
        if (game.play(m)) set({ it.copy(game = game, ply = game.size, preview = null, previewStep = 0) })
    }

    fun goTo(ply: Int) = set({ it.copy(ply = ply.coerceIn(0, it.game.size), preview = null, previewStep = 0) })
    fun flip() = set({ it.copy(flipped = !it.flipped) }, analyze = false)
    fun toggleEngine() { set({ it.copy(engineOn = !it.engineOn) }) }

    fun preview(line: UiLine) = set({ it.copy(preview = line, previewStep = 1) }, analyze = false)
    fun previewStep(step: Int) = set({ it.copy(previewStep = step.coerceIn(0, it.previewPositions.size)) }, analyze = false)
    fun closePreview() = set({ it.copy(preview = null, previewStep = 0) }, analyze = false)

    /** Plays the previewed line into the game up to [steps] moves. */
    fun applyPreview(steps: Int) {
        val u = _ui.value
        val l = u.preview ?: return
        val game = u.game.copy(); game.truncate(u.ply)
        for (uci in l.pvUci.take(steps)) if (!game.playUci(uci)) break
        set({ it.copy(game = game, ply = game.size, preview = null, previewStep = 0) })
    }

    fun setPosition(p: Position) = set({ AnalysisUi(game = Game(Fen.toFen(p)), flipped = it.flipped, engineOn = it.engineOn) })

    fun loadText(text: String): String? {
        val t = text.trim()
        if (t.isEmpty()) return "Пустой ввод"
        return try {
            if (t.contains("[") || Regex("^\\s*1\\.").containsMatchIn(t) || !t.contains('/')) {
                val game = Pgn.parse(Pgn.splitGames(t).firstOrNull() ?: t)
                set({ AnalysisUi(game = game, ply = game.size, flipped = it.flipped, engineOn = it.engineOn) })
            } else {
                val p = Fen.parse(t)
                val issues = PositionValidator.validate(p.board(), p.whiteToMove).filter { it.fatal }
                if (issues.isNotEmpty()) return issues.joinToString("\n") { it.message }
                setPosition(p)
            }
            null
        } catch (e: Exception) { e.message ?: "Не удалось разобрать" }
    }

    fun save(onDone: (Long) -> Unit) {
        val game = _ui.value.game
        viewModelScope.launch {
            val id = g.db.games().insert(GameEntity(
                title = "Анализ ${java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}",
                white = game.headers["White"] ?: "Белые", black = game.headers["Black"] ?: "Чёрные",
                result = game.result().pgn, pgn = game.toPgn(), source = "analysis",
            ))
            onDone(id)
        }
    }
}

@Composable
fun AnalysisScreen(g: AppGraph, onBack: () -> Unit, onReview: (Long) -> Unit) {
    val vm: AnalysisViewModel = viewModel { AnalysisViewModel(g) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val an by vm.analysis.state.collectAsStateWithLifecycle()
    val settings by g.settings.flow.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var showEditor by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val pieceSet = remember(settings.pieceSet) { g.pieceSet(settings.pieceSet) }
    val theme = CV.BoardThemes.getOrElse(settings.boardTheme) { CV.BoardThemes[0] }

    val showAnalysisForBase = ui.engineOn && an.fen == Fen.toFen(ui.basePosition)
    val arrows = remember(an, ui.version, settings.showArrows) {
        if (!settings.showArrows || !showAnalysisForBase || ui.preview != null) emptyList()
        else an.lines.mapNotNull { l ->
            val m = l.pvUci.firstOrNull()?.let { Move.fromUci(it) } ?: return@mapNotNull null
            BoardArrow(m.from, m.to, CV.LineColors.getOrElse(l.multipv - 1) { CV.TextDim }, if (l.multipv == 1) 0.2f else 0.12f)
        }.reversed()
    }
    val marks = remember(an, ui.version) {
        val b = an.best?.pvUci?.firstOrNull()?.let { Move.fromUci(it) }
        if (b == null || !showAnalysisForBase || ui.preview != null) emptyMap() else mapOf(b.from to CV.Accent.copy(alpha = 0.25f), b.to to CV.Accent.copy(alpha = 0.35f))
    }

    ScreenScaffold("Анализ", onBack, actions = {
        IconButton(onClick = { vm.flip() }) { Icon(Icons.Filled.SwapVert, "Перевернуть", tint = CV.Text) }
        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Меню", tint = CV.Text) }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem({ Text("Редактировать позицию") }, { menu = false; showEditor = true }, leadingIcon = { Icon(Icons.Filled.Edit, null) })
            DropdownMenuItem({ Text("Вставить FEN / PGN") }, { menu = false; showImport = true }, leadingIcon = { Icon(Icons.Filled.ContentPaste, null) })
            DropdownMenuItem({ Text("Копировать FEN") }, { menu = false; copy(ctx, Fen.toFen(ui.shownPosition)) }, leadingIcon = { Icon(Icons.Filled.ContentCopy, null) })
            DropdownMenuItem({ Text("Копировать PGN") }, { menu = false; copy(ctx, ui.game.toPgn()) }, leadingIcon = { Icon(Icons.Filled.Description, null) })
            DropdownMenuItem({ Text("Новая партия") }, { menu = false; vm.setPosition(Position.start()) }, leadingIcon = { Icon(Icons.Filled.RestartAlt, null) })
            DropdownMenuItem({ Text("Сохранить и разобрать") }, { menu = false; vm.save { onReview(it) } }, leadingIcon = { Icon(Icons.Filled.Insights, null) })
        }
    }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                if (settings.showEvalBar && ui.engineOn) {
                    val sc = if (showAnalysisForBase) an.best?.score else null
                    EvalBar(sc?.whiteWinPercent() ?: 50.0, sc?.display() ?: "", ui.flipped, Modifier.padding(end = 6.dp))
                }
                ChessBoard(
                    ui.shownPosition, pieceSet, theme, Modifier.weight(1f), flipped = ui.flipped,
                    lastMove = ui.shownLastMove, arrows = arrows, squareMarks = marks, showCoordinates = settings.showCoordinates,
                    movable = ui.shownPosition.whiteToMove, onMove = { vm.move(it) },
                )
            }
            val pv = ui.preview
            if (pv != null) {
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Продолжение: ", color = CV.TextDim)
                        Text("${ui.previewStep}/${ui.previewPositions.size}", color = CV.Accent, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { vm.previewStep(ui.previewStep - 1) }) { Icon(Icons.Filled.ChevronLeft, "Назад", tint = CV.Text) }
                        IconButton(onClick = { vm.previewStep(ui.previewStep + 1) }) { Icon(Icons.Filled.ChevronRight, "Вперёд", tint = CV.Text) }
                        IconButton(onClick = { vm.closePreview() }) { Icon(Icons.Filled.Close, "Закрыть", tint = CV.Text) }
                    }
                    Text(pv.pvSan, color = CV.Text, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    SecondaryButton("Сыграть ${ui.previewStep} ход(а) в партию", Modifier.fillMaxWidth(), Icons.Filled.PlaylistAdd, enabled = ui.previewStep > 0) { vm.applyPreview(ui.previewStep) }
                }
            }
            NavBar(ui.ply > 0, ui.ply < ui.game.size, { vm.goTo(0) }, { vm.goTo(ui.ply - 1) }, { vm.goTo(ui.ply + 1) }, { vm.goTo(ui.game.size) })
            val start = ui.game.positions[0]
            MoveStrip(ui.game.sans, start.fullmoveNumber, start.whiteToMove, ui.ply, { vm.goTo(it) })
            val term = if (ui.ply == ui.game.size) ui.game.termination() else TerminationReason.NONE
            if (term != TerminationReason.NONE) InfoBanner("Партия окончена: ${term.ru} (${ui.game.result().pgn})", CV.Warn, Icons.Filled.Flag)
            EnginePanel(if (showAnalysisForBase || !ui.engineOn) an else an.copy(lines = emptyList(), running = true), ui.engineOn, { vm.toggleEngine() }, { vm.preview(it) })
            Text(
                "Ход ${if (ui.basePosition.whiteToMove) "белых" else "чёрных"} · FEN: ${Fen.toFen(ui.basePosition)}",
                color = CV.TextFaint, style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(16.dp))
        }
    }

    if (showEditor) BoardEditorDialog(ui.basePosition, pieceSet, theme, ui.flipped, { showEditor = false }) {
        showEditor = false; vm.setPosition(it)
    }
    if (showImport) ImportDialog({ showImport = false }) { text ->
        val err = vm.loadText(text)
        if (err == null) { showImport = false; null } else err
    }
}

fun copy(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("chess", text))
    Toast.makeText(ctx, "Скопировано", Toast.LENGTH_SHORT).show()
}

/** Paste FEN or PGN; [onSubmit] returns an error message or null on success. */
@Composable
fun ImportDialog(onDismiss: () -> Unit, onSubmit: (String) -> String?) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CV.Surface,
        title = { Text("FEN или PGN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(text, { text = it; error = null }, Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 260.dp),
                    placeholder = { Text("Вставьте FEN-строку или PGN партии") })
                TextButton(onClick = {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()?.let { text = it }
                }) { Icon(Icons.Filled.ContentPaste, null); Spacer(Modifier.width(6.dp)); Text("Из буфера обмена") }
                error?.let { Text(it, color = CV.Bad, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = { error = onSubmit(text) }) { Text("Загрузить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
