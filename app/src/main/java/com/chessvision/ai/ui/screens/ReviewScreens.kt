package com.chessvision.ai.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chessvision.ai.AppGraph
import com.chessvision.ai.data.GameEntity
import com.chessvision.ai.data.MistakeEntity
import com.chessvision.ai.ui.board.*
import com.chessvision.ai.ui.theme.CV
import com.chessvision.core.chess.*
import com.chessvision.core.engine.GameReviewer
import com.chessvision.core.engine.MoveClass
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ------------------------------------------------------------------ list

@Composable
fun ReviewListScreen(g: AppGraph, onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val games by g.db.games().all().collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var showImport by remember { mutableStateOf(false) }
    var importMsg by remember { mutableStateOf<String?>(null) }

    suspend fun importText(text: String): String? {
        val parts = Pgn.splitGames(text)
        var ok = 0; var lastId = 0L; var err: String? = null
        for (p in parts) {
            try {
                val game = Pgn.parse(p)
                if (game.size == 0) continue
                lastId = g.db.games().insert(GameEntity(
                    title = game.headers["Event"]?.takeIf { it != "?" } ?: "Импорт PGN",
                    white = game.headers["White"] ?: "Белые", black = game.headers["Black"] ?: "Чёрные",
                    result = game.result().pgn, pgn = game.toPgn(), source = "import",
                ))
                ok++
            } catch (e: Exception) { err = e.message }
        }
        if (ok == 1) onOpen(lastId)
        return if (ok == 0) (err ?: "Партии не найдены") else { importMsg = "Импортировано партий: $ok"; null }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val text = runCatching { ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()
            importMsg = if (text == null) "Не удалось прочитать файл" else importText(text) ?: importMsg
        }
    }

    ScreenScaffold("Разбор партий", onBack, actions = {
        IconButton(onClick = { filePicker.launch(arrayOf("application/x-chess-pgn", "text/*", "application/octet-stream")) }) { Icon(Icons.Filled.FileOpen, "Открыть PGN", tint = CV.Text) }
        IconButton(onClick = { showImport = true }) { Icon(Icons.Filled.ContentPaste, "Вставить PGN", tint = CV.Text) }
    }) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            importMsg?.let { InfoBanner(it, CV.Accent) }
            if (games.isEmpty()) Panel {
                Text("Пока нет партий", style = MaterialTheme.typography.titleMedium, color = CV.Text)
                Text("Сыграй с движком в режиме Practice, вставь PGN или открой .pgn-файл (например, экспорт своих партий с lichess или chess.com).",
                    color = CV.TextDim, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                PrimaryButton("Вставить PGN", Modifier.fillMaxWidth(), Icons.Filled.ContentPaste) { showImport = true }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(games, key = { it.id }) { gm ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CV.Surface).clickable { onOpen(gm.id) }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("${gm.white} — ${gm.black}", color = CV.Text, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(gm.title + " · " + java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.getDefault()).format(java.util.Date(gm.createdAt)),
                                color = CV.TextFaint, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                            if (gm.accuracyWhite != null) Text("Точность: белые ${gm.accuracyWhite.toInt()}% · чёрные ${(gm.accuracyBlack ?: 0.0).toInt()}%",
                                color = CV.Accent, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(gm.result, color = CV.Text, fontWeight = FontWeight.Bold)
                        IconButton(onClick = { scope.launch { g.db.games().delete(gm.id); g.db.mistakes().deleteForGame(gm.id) } }) {
                            Icon(Icons.Filled.DeleteOutline, "Удалить", tint = CV.TextFaint)
                        }
                    }
                }
            }
        }
    }
    if (showImport) ImportDialog({ showImport = false }) { text ->
        scope.launch { importText(text)?.let { importMsg = it } }
        showImport = false
        null
    }
}

// ------------------------------------------------------------------ detail

data class PlyInfo(val cls: MoveClass?, val bestUci: String?, val bestSan: String?, val winLoss: Double)

data class ReviewUi(
    val entity: GameEntity? = null,
    val game: Game? = null,
    val ply: Int = 0,
    val flipped: Boolean = false,
    val running: Boolean = false,
    val progress: Float = 0f,
    /** per position: white win% and eval text */
    val evals: List<Pair<Double, String>> = emptyList(),
    val plies: List<PlyInfo> = emptyList(),
    val error: String? = null,
    val savedMistakes: Int = 0,
)

class ReviewViewModel(private val g: AppGraph, private val id: Long) : ViewModel() {
    private val _ui = MutableStateFlow(ReviewUi())
    val ui = _ui.asStateFlow()
    private var job: Job? = null

    init {
        viewModelScope.launch {
            val e = g.db.games().byId(id) ?: run { _ui.value = ReviewUi(error = "Партия не найдена"); return@launch }
            val game = runCatching { Pgn.parse(e.pgn) }.getOrElse { _ui.value = ReviewUi(entity = e, error = "Ошибка PGN: ${it.message}"); return@launch }
            val flipped = e.black == "Вы"
            val (evals, plies) = decode(e.reviewData, game.size)
            _ui.value = ReviewUi(entity = e, game = game, ply = 0, flipped = flipped, evals = evals, plies = plies)
            if (e.reviewData == null) run()
        }
    }

    private fun decode(data: String?, size: Int): Pair<List<Pair<Double, String>>, List<PlyInfo>> {
        if (data == null) return emptyList<Pair<Double, String>>() to emptyList()
        val lines = data.lines()
        val evals = lines.firstOrNull()?.removePrefix("EV ")?.split(';')?.mapNotNull {
            val p = it.split(':'); p.getOrNull(0)?.toDoubleOrNull()?.let { v -> v to (p.getOrNull(1) ?: "") }
        } ?: emptyList()
        val plies = lines.drop(1).filter { it.isNotBlank() }.map {
            val p = it.split('|')
            PlyInfo(runCatching { MoveClass.valueOf(p[0]) }.getOrNull(), p.getOrNull(1)?.ifEmpty { null }, p.getOrNull(2)?.ifEmpty { null }, p.getOrNull(3)?.toDoubleOrNull() ?: 0.0)
        }
        return if (plies.size == size) evals to plies else emptyList<Pair<Double, String>>() to emptyList()
    }

    fun run() {
        val u = _ui.value
        val game = u.game ?: return
        val e = u.entity ?: return
        job?.cancel()
        job = viewModelScope.launch {
            _ui.value = _ui.value.copy(running = true, progress = 0f, error = null)
            try {
                val engine = g.engine.get()
                engine.newGame()
                val res = GameReviewer(engine).review(game, depth = g.settings.value.reviewDepth, bookPlies = 0) { d, t ->
                    _ui.value = _ui.value.copy(progress = d.toFloat() / t)
                }
                val evals = res.evals.map { it.whiteWinPercent() to it.display() }
                val plies = res.moves.map { PlyInfo(it.classification, it.bestMoveUci, it.bestMoveSan, it.winLoss) }
                val data = "EV " + evals.joinToString(";") { "${"%.1f".format(java.util.Locale.US, it.first)}:${it.second}" } + "\n" +
                    plies.joinToString("\n") { "${it.cls?.name ?: ""}|${it.bestUci ?: ""}|${it.bestSan ?: ""}|${"%.1f".format(java.util.Locale.US, it.winLoss)}" }
                val updated = e.copy(accuracyWhite = res.accuracyWhite, accuracyBlack = res.accuracyBlack, reviewData = data)
                g.db.games().update(updated)
                // mistakes → training
                val mine = when { e.white == "Вы" -> setOf(true); e.black == "Вы" -> setOf(false); else -> setOf(true, false) }
                val mistakes = res.moves.filter {
                    it.whiteMoved in mine && it.bestMoveUci != null && it.bestMoveSan != null &&
                        it.classification in setOf(MoveClass.MISTAKE, MoveClass.BLUNDER, MoveClass.MISSED_WIN, MoveClass.INACCURACY)
                }.map { MistakeEntity(gameId = e.id, fen = it.fenBefore, bestUci = it.bestMoveUci!!, bestSan = it.bestMoveSan!!, playedSan = it.san, classification = it.classification.name) }
                g.db.mistakes().deleteForGame(e.id)
                g.db.mistakes().insertAll(mistakes)
                _ui.value = _ui.value.copy(entity = updated, running = false, progress = 1f, evals = evals, plies = plies, savedMistakes = mistakes.size)
            } catch (ex: kotlinx.coroutines.CancellationException) { throw ex
            } catch (ex: Exception) { _ui.value = _ui.value.copy(running = false, error = ex.message ?: "Ошибка анализа") }
        }
    }

    fun goTo(p: Int) { val gm = _ui.value.game ?: return; _ui.value = _ui.value.copy(ply = p.coerceIn(0, gm.size)) }
    fun flip() { _ui.value = _ui.value.copy(flipped = !_ui.value.flipped) }
    fun nextMistake() {
        val u = _ui.value
        val i = u.plies.withIndex().firstOrNull { it.index + 1 > u.ply && it.value.cls in setOf(MoveClass.MISTAKE, MoveClass.BLUNDER, MoveClass.MISSED_WIN) }?.index
        if (i != null) goTo(i + 1)
    }
    fun toAnalysis() {
        val u = _ui.value
        val gm = u.game?.copy() ?: return
        gm.truncate(u.ply)
        if (u.flipped) gm.headers["Flip"] = "1"
        g.pendingGame = gm
    }
}

@Composable
fun ReviewScreen(g: AppGraph, id: Long, onBack: () -> Unit, onAnalyze: () -> Unit, onTraining: () -> Unit) {
    val vm: ReviewViewModel = viewModel(key = "review$id") { ReviewViewModel(g, id) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val s by g.settings.flow.collectAsStateWithLifecycle()
    val pieceSet = remember(s.pieceSet) { g.pieceSet(s.pieceSet) }
    val theme = CV.BoardThemes.getOrElse(s.boardTheme) { CV.BoardThemes[0] }

    ScreenScaffold("Разбор партии", onBack, actions = {
        IconButton(onClick = { vm.flip() }) { Icon(Icons.Filled.SwapVert, "Перевернуть", tint = CV.Text) }
        IconButton(onClick = { vm.toAnalysis(); onAnalyze() }) { Icon(Icons.Filled.Analytics, "В анализ", tint = CV.Text) }
    }) {
        val game = ui.game
        if (game == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (ui.error != null) Text(ui.error!!, color = CV.Bad) else CircularProgressIndicator(color = CV.Accent)
            }
            return@ScreenScaffold
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val e = ui.entity!!
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${e.white} — ${e.black}", color = CV.Text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
                Text(game.result().pgn, color = CV.Accent, fontWeight = FontWeight.Bold)
            }
            val info = if (ui.ply > 0) ui.plies.getOrNull(ui.ply - 1) else null
            val played = if (ui.ply > 0) game.moves[ui.ply - 1] else null
            val best = info?.bestUci?.let { Move.fromUci(it) }
            val arrows = if (best != null && played != null && best != played && info?.cls !in setOf(MoveClass.BEST, MoveClass.BOOK, MoveClass.FORCED))
                listOf(BoardArrow(best.from, best.to, CV.Accent)) else emptyList()
            val ev = ui.evals.getOrNull(ui.ply)
            BoardWithEval(ev != null, ev?.first ?: 50.0, ev?.second ?: "", ui.flipped) { m ->
                ChessBoard(game.positions[ui.ply], pieceSet, theme, m, flipped = ui.flipped, lastMove = played,
                    arrows = arrows, showCoordinates = s.showCoordinates, movable = null,
                    squareMarks = if (played != null && info?.cls != null) mapOf(played.to to Color(info.cls.color).copy(alpha = 0.35f)) else emptyMap())
            }
            if (ui.running) Panel {
                Text("Stockfish разбирает партию… ${(ui.progress * 100).toInt()}%", color = CV.Text)
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator({ ui.progress }, Modifier.fillMaxWidth(), color = CV.Accent, trackColor = CV.Surface3)
            }
            ui.error?.let { InfoBanner(it, CV.Bad, Icons.Filled.ErrorOutline) }
            if (info?.cls != null && played != null) {
                val cls = info.cls
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(cls.color).copy(alpha = 0.14f)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(cls.symbol, color = Color(cls.color), fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${game.sans[ui.ply - 1]} — ${cls.ru}", color = CV.Text, fontWeight = FontWeight.SemiBold)
                        if (info.bestSan != null && best != played) Text("Лучше было: ${info.bestSan}", color = CV.TextDim, style = MaterialTheme.typography.bodySmall)
                    }
                    ui.evals.getOrNull(ui.ply)?.let { Text(it.second, color = CV.Text, fontWeight = FontWeight.Bold) }
                }
            }
            NavBar(ui.ply > 0, ui.ply < game.size, { vm.goTo(0) }, { vm.goTo(ui.ply - 1) }, { vm.goTo(ui.ply + 1) }, { vm.goTo(game.size) }) {
                IconButton(onClick = { vm.nextMistake() }, enabled = ui.plies.isNotEmpty()) { Icon(Icons.Filled.ReportProblem, "Следующая ошибка", tint = CV.Warn) }
            }
            if (ui.evals.isNotEmpty()) EvalGraph(ui.evals.map { it.first }, ui.plies, ui.ply) { vm.goTo(it) }
            val start = game.positions[0]
            MoveStrip(game.sans, start.fullmoveNumber, start.whiteToMove, ui.ply, { vm.goTo(it) },
                marks = if (ui.plies.isNotEmpty()) ui.plies.map { p ->
                    val c = p.cls
                    if (c == null || c in setOf(MoveClass.BEST, MoveClass.EXCELLENT, MoveClass.GOOD, MoveClass.BOOK)) "" to CV.TextDim else c.symbol to Color(c.color)
                } else null)
            if (e.accuracyWhite != null && ui.plies.isNotEmpty()) Panel {
                Row {
                    AccuracyBox("Белые", e.accuracyWhite, Modifier.weight(1f))
                    Spacer(Modifier.width(10.dp))
                    AccuracyBox("Чёрные", e.accuracyBlack ?: 0.0, Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                for (c in listOf(MoveClass.BEST, MoveClass.EXCELLENT, MoveClass.GOOD, MoveClass.INACCURACY, MoveClass.MISTAKE, MoveClass.MISSED_WIN, MoveClass.BLUNDER)) {
                    val w = ui.plies.withIndex().count { (i, p) -> p.cls == c && (start.whiteToMove == (i % 2 == 0)) }
                    val b = ui.plies.withIndex().count { (i, p) -> p.cls == c && (start.whiteToMove != (i % 2 == 0)) }
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("$w", color = CV.Text, modifier = Modifier.width(32.dp), fontWeight = FontWeight.Bold)
                        Text(c.symbol, color = Color(c.color), modifier = Modifier.width(28.dp), fontWeight = FontWeight.Bold)
                        Text(c.ru, color = CV.TextDim, modifier = Modifier.weight(1f))
                        Text("$b", color = CV.Text, fontWeight = FontWeight.Bold)
                    }
                }
                if (ui.savedMistakes > 0) {
                    Spacer(Modifier.height(8.dp))
                    SecondaryButton("Тренировать ошибки (${ui.savedMistakes})", Modifier.fillMaxWidth(), Icons.Filled.School) { onTraining() }
                }
            }
            if (!ui.running) SecondaryButton(if (ui.plies.isEmpty()) "Разобрать партию" else "Разобрать заново", Modifier.fillMaxWidth(), Icons.Filled.Refresh) { vm.run() }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun AccuracyBox(label: String, acc: Double, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(CV.Surface2).padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = CV.TextDim, style = MaterialTheme.typography.bodySmall)
        Text("${"%.1f".format(acc)}%", color = CV.Accent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("точность", color = CV.TextFaint, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun EvalGraph(whitePct: List<Double>, plies: List<PlyInfo>, current: Int, onSelect: (Int) -> Unit) {
    Canvas(
        Modifier.fillMaxWidth().height(90.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF2B2E33))
            .pointerInput(whitePct.size) { detectTapGestures { o -> onSelect(((o.x / size.width) * (whitePct.size - 1)).toInt().coerceIn(0, whitePct.size - 1)) } }
    ) {
        if (whitePct.size < 2) return@Canvas
        val n = whitePct.size - 1
        fun pt(i: Int) = Offset(size.width * i / n, size.height * (1f - (whitePct[i] / 100.0).toFloat()))
        val area = Path().apply {
            moveTo(0f, size.height)
            for (i in 0..n) lineTo(pt(i).x, pt(i).y)
            lineTo(size.width, size.height); close()
        }
        drawPath(area, Color(0xFFE8EAED))
        drawLine(CV.TextFaint, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1f)
        for ((i, p) in plies.withIndex()) {
            val c = p.cls ?: continue
            if (c in setOf(MoveClass.MISTAKE, MoveClass.BLUNDER, MoveClass.MISSED_WIN)) drawCircle(Color(c.color), 5f, pt(i + 1))
        }
        val cx = size.width * current / n
        drawLine(CV.Accent, Offset(cx, 0f), Offset(cx, size.height), 3f)
    }
}
