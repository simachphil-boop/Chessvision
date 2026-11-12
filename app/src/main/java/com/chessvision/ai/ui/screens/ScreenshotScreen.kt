package com.chessvision.ai.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chessvision.ai.AppGraph
import com.chessvision.ai.ui.board.*
import com.chessvision.ai.ui.theme.CV
import com.chessvision.core.chess.*
import com.chessvision.core.vision.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class ShotUi(
    val bitmap: Bitmap? = null,
    val rect: BoardRect? = null,
    val recognition: Recognition? = null,
    /** User corrections keyed by display cell index (row * 8 + col). */
    val edits: Map<Int, Int> = emptyMap(),
    val whiteToMove: Boolean = true,
    val busy: Boolean = false,
    val status: String? = null,
    val error: String? = null,
    val adjusting: Boolean = false,
    val version: Int = 0,
) {
    val board: IntArray get() {
        val rec = recognition ?: return IntArray(64)
        val b = IntArray(64)
        for (c in rec.cells) b[rec.squareOf(c.row, c.col)] = edits[c.row * 8 + c.col] ?: c.piece
        return b
    }
    val position: Position get() = Position(board, whiteToMove, Fen.sanitizeCastling(board, 15), -1, 0, 1)
    val issues get() = PositionValidator.validate(board, whiteToMove)
}

class ScreenshotViewModel(private val g: AppGraph) : ViewModel() {
    private val _ui = MutableStateFlow(ShotUi())
    val ui = _ui.asStateFlow()
    private var rgb: RgbImage? = null

    init { g.pendingImageUri?.let { g.pendingImageUri = null; load(Uri.parse(it)) } }

    private fun upd(f: (ShotUi) -> ShotUi) { _ui.value = f(_ui.value).let { it.copy(version = it.version + 1) } }

    fun load(uri: Uri) {
        upd { ShotUi(busy = true, status = "Загрузка изображения…") }
        viewModelScope.launch {
            try {
                val bmp = g.recognition.loadBitmap(uri)
                val img = g.recognition.toRgb(bmp)
                rgb = img
                upd { it.copy(bitmap = bmp, status = "Поиск доски…") }
                val det = g.recognition.detect(img)
                upd { it.copy(rect = det.rect, status = "Распознавание фигур…") }
                val rec = g.recognition.recognize(img, det, null)
                applyRecognition(rec)
            } catch (e: Exception) {
                upd { it.copy(busy = false, status = null, error = e.message ?: "Ошибка распознавания", adjusting = it.bitmap != null) }
                if (_ui.value.bitmap != null && _ui.value.rect == null) {
                    val b = _ui.value.bitmap!!
                    val s = min(b.width, b.height) * 0.9f
                    upd { it.copy(rect = BoardRect((b.width - s) / 2, (b.height - s) / 2, s)) }
                }
            }
        }
    }

    private fun applyRecognition(rec: Recognition) {
        val bottomWhite = !rec.flipped
        // side to move: if exactly one king is in check that side moves, otherwise the side at the bottom
        val tmp = ShotUi(recognition = rec, whiteToMove = bottomWhite)
        val b = tmp.board
        val inCheckWhite = Position(b, true, 0, -1, 0, 1).let { p -> p.kingSquare(true).let { it >= 0 && p.isAttacked(it, false) } }
        val inCheckBlack = Position(b, false, 0, -1, 0, 1).let { p -> p.kingSquare(false).let { it >= 0 && p.isAttacked(it, true) } }
        val stm = when { inCheckWhite && !inCheckBlack -> true; inCheckBlack && !inCheckWhite -> false; else -> bottomWhite }
        upd { it.copy(recognition = rec, rect = rec.rect, edits = emptyMap(), whiteToMove = stm, busy = false, status = null, error = null, adjusting = false) }
    }

    fun setRect(r: BoardRect) = upd { it.copy(rect = r) }
    fun startAdjust() = upd { it.copy(adjusting = true) }

    fun applyManualRect() {
        val img = rgb ?: return
        val r = _ui.value.rect ?: return
        upd { it.copy(busy = true, status = "Распознавание…") }
        viewModelScope.launch {
            try {
                val aligned = BoardDetector.alignToGrid(img, r)
                applyRecognition(g.recognition.recognizeManual(img, aligned, _ui.value.recognition?.flipped))
            } catch (e: Exception) { upd { it.copy(busy = false, error = e.message) } }
        }
    }

    fun flip() {
        val rec = _ui.value.recognition ?: return
        upd { it.copy(recognition = rec.copy(flipped = !rec.flipped), whiteToMove = it.whiteToMove) }
    }

    fun setWhiteToMove(w: Boolean) = upd { it.copy(whiteToMove = w) }

    fun editSquare(square: Int, piece: Int) {
        val rec = _ui.value.recognition ?: return
        val cell = rec.cells.firstOrNull { rec.squareOf(it.row, it.col) == square } ?: return
        val idx = cell.row * 8 + cell.col
        upd { u ->
            val cur = u.edits[idx] ?: cell.piece
            val newPiece = if (cur == piece) 0 else piece
            val e = u.edits.toMutableMap()
            if (newPiece == cell.piece) e.remove(idx) else e[idx] = newPiece
            u.copy(edits = e)
        }
    }

    fun calibrate() {
        val rec = _ui.value.recognition ?: return
        viewModelScope.launch {
            val samples = BoardRecognizer.calibrationSamples(rec, "calib")
            if (samples == null) {
                upd { it.copy(error = "Для калибровки нужен скриншот НАЧАЛЬНОЙ позиции (все 32 фигуры на местах).") }
                return@launch
            }
            g.recognition.learn(samples)
            val img = rgb ?: return@launch
            upd { it.copy(busy = true, status = "Калибровка: выучено ${samples.size} образцов") }
            applyRecognition(g.recognition.recognize(img, DetectResult(rec.rect, 0f, rec.lightColor, rec.darkColor), rec.flipped))
            upd { it.copy(status = "Калибровка сохранена — этот стиль фигур теперь распознаётся точнее") }
        }
    }

    /** Learns from corrections and hands the position to the analysis screen. */
    fun finish(onDone: () -> Unit) {
        val u = _ui.value
        val rec = u.recognition ?: return
        viewModelScope.launch {
            val learn = u.edits.mapNotNull { (idx, piece) ->
                val c = rec.cells[idx]
                if (piece != 0 && c.features != null) Sample(Pieces.type(piece), c.features!!, "fix") else null
            }
            runCatching { g.recognition.learn(learn) }
            val game = Game(Fen.toFen(u.position))
            game.headers["Event"] = "Скриншот"
            if (rec.flipped) game.headers["Flip"] = "1"
            g.pendingGame = game
            onDone()
        }
    }
}

@Composable
fun ScreenshotScreen(g: AppGraph, onBack: () -> Unit, onAnalyze: () -> Unit) {
    val vm: ScreenshotViewModel = viewModel { ScreenshotViewModel(g) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val settings by g.settings.flow.collectAsStateWithLifecycle()
    val pieceSet = remember(settings.pieceSet) { g.pieceSet(settings.pieceSet) }
    val theme = CV.BoardThemes.getOrElse(settings.boardTheme) { CV.BoardThemes[0] }
    var brush by remember { mutableIntStateOf(Pieces.PAWN) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) vm.load(uri) }
    fun pick() = picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))

    ScreenScaffold("Скриншот → позиция", onBack, actions = {
        IconButton(onClick = { pick() }) { Icon(Icons.Filled.AddPhotoAlternate, "Выбрать", tint = CV.Text) }
    }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (ui.bitmap == null && !ui.busy) {
                Panel {
                    Icon(Icons.Filled.DocumentScanner, null, tint = CV.Info, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("Распознавание позиции", style = MaterialTheme.typography.titleMedium, color = CV.Text)
                    Text("Выбери скриншот 2D-доски (lichess, chess.com, книги, задачники). Приложение найдёт доску, 64 клетки, фигуры и их цвет, соберёт FEN и проверит позицию. Ошибки можно исправить вручную — приложение запомнит исправления.",
                        color = CV.TextDim, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(10.dp))
                    PrimaryButton("Выбрать скриншот", Modifier.fillMaxWidth(), Icons.Filled.Image) { pick() }
                }
                InfoBanner("Работает только с картинками, которые ты выбрал сам. Для живых партий на сайтах использовать нельзя — это нарушение fair play. Разбор после партии — пожалуйста.", CV.Warn, Icons.Filled.Gavel)
            }
            ui.status?.let { if (ui.busy) { LinearProgressIndicator(Modifier.fillMaxWidth(), color = CV.Accent, trackColor = CV.Surface3); Text(it, color = CV.TextDim) } else InfoBanner(it, CV.Accent, Icons.Filled.CheckCircle) }
            ui.error?.let { InfoBanner(it, CV.Bad, Icons.Filled.ErrorOutline) }

            val bmp = ui.bitmap
            if (bmp != null && (ui.adjusting || ui.recognition == null)) {
                Text(if (ui.adjusting) "Перетащи рамку на доску, тяни за угол, чтобы изменить размер" else "Найденная доска",
                    color = CV.TextDim, style = MaterialTheme.typography.bodySmall)
                RectEditor(bmp, ui.rect, editable = ui.adjusting) { vm.setRect(it) }
                if (ui.adjusting) PrimaryButton("Распознать в рамке", Modifier.fillMaxWidth(), Icons.Filled.CropFree, enabled = !ui.busy) { vm.applyManualRect() }
            }

            val rec = ui.recognition
            if (rec != null && !ui.adjusting) {
                val lowConf = remember(ui.version) {
                    rec.cells.filter { it.occupied && it.confidence < 0.3f && ui.edits[it.row * 8 + it.col] == null }
                        .associate { rec.squareOf(it.row, it.col) to CV.Warn.copy(alpha = 0.45f) }
                }
                val edited = remember(ui.version) { ui.edits.keys.associate { rec.squareOf(it / 8, it % 8) to CV.Info.copy(alpha = 0.35f) } }
                if (bmp != null) Row(Modifier.fillMaxWidth().height(110.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RectEditor(bmp, ui.rect, editable = false, modifier = Modifier.width(110.dp)) {}
                    Column(Modifier.weight(1f)) {
                        Text("Уверенность: ${(rec.averageConfidence * 100).toInt()}%", color = CV.Text)
                        Text("Оранжевые клетки — проверь их. Тапни клетку, чтобы поставить выбранную фигуру (повторный тап — убрать).",
                            color = CV.TextDim, style = MaterialTheme.typography.bodySmall)
                    }
                }
                ChessBoard(ui.position, pieceSet, theme, Modifier.fillMaxWidth(), flipped = rec.flipped, movable = null,
                    squareMarks = lowConf + edited, onSquareTap = { vm.editSquare(it, brush) }, animate = false)
                PiecePalette(pieceSet, brush) { brush = it }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(ui.whiteToMove, { vm.setWhiteToMove(true) }, { Text("Ход белых") })
                    FilterChip(!ui.whiteToMove, { vm.setWhiteToMove(false) }, { Text("Ход чёрных") })
                    AssistChip({ vm.flip() }, { Text("Перевернуть") }, leadingIcon = { Icon(Icons.Filled.SwapVert, null, Modifier.size(16.dp)) })
                    AssistChip({ vm.startAdjust() }, { Text("Рамка вручную") }, leadingIcon = { Icon(Icons.Filled.Crop, null, Modifier.size(16.dp)) })
                    AssistChip({ vm.calibrate() }, { Text("Калибровка") }, leadingIcon = { Icon(Icons.Filled.Tune, null, Modifier.size(16.dp)) })
                }
                Text("Калибровка: если фигуры на сайте нестандартные, загрузи скриншот начальной позиции с тем же оформлением и нажми «Калибровка».",
                    color = CV.TextFaint, style = MaterialTheme.typography.bodySmall)
                val issues = ui.issues
                if (issues.isEmpty()) InfoBanner("Позиция корректна · ${Fen.toFen(ui.position)}", CV.Accent, Icons.Filled.CheckCircle)
                else for (i in issues) InfoBanner(i.message, if (i.fatal) CV.Bad else CV.Warn, Icons.Filled.Warning)
                PrimaryButton("Анализировать", Modifier.fillMaxWidth(), Icons.Filled.Analytics, enabled = issues.none { it.fatal }) { vm.finish(onAnalyze) }
                SecondaryButton("Другой скриншот", Modifier.fillMaxWidth(), Icons.Filled.Image) { pick() }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Shows the screenshot with the board rectangle; when [editable], drag to move / resize from the corner. */
@Composable
private fun RectEditor(bmp: Bitmap, rect: BoardRect?, editable: Boolean, modifier: Modifier = Modifier.fillMaxWidth(), onChange: (BoardRect) -> Unit) {
    val image = remember(bmp) { bmp.asImageBitmap() }
    BoxWithConstraints(modifier.clip(RoundedCornerShape(10.dp)).background(Color.Black)) {
        val wPx = with(LocalDensity.current) { maxWidth.toPx() }
        val maxH = with(LocalDensity.current) { (if (editable) 520.dp else maxHeight.coerceAtMost(2000.dp)).toPx() }
        val k = min(wPx / bmp.width, maxH / bmp.height)
        val shownW = bmp.width * k; val shownH = bmp.height * k
        val dens = LocalDensity.current
        Box(Modifier.size(with(dens) { shownW.toDp() }, with(dens) { shownH.toDp() }).align(Alignment.Center)) {
            Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            var mode by remember { mutableIntStateOf(0) } // 1 move, 2 resize
            val cur = rememberUpdatedState(rect)
            Canvas(
                Modifier.fillMaxSize().then(if (editable) Modifier.pointerInput(bmp) {
                    detectDragGestures(
                        onDragStart = { o ->
                            val r = cur.value ?: return@detectDragGestures
                            val corner = Offset((r.x + r.size) * k, (r.y + r.size) * k)
                            mode = if (abs(o.x - corner.x) < 60 && abs(o.y - corner.y) < 60) 2 else 1
                        },
                        onDrag = { ch, d ->
                            ch.consume()
                            val r = cur.value ?: return@detectDragGestures
                            val dx = d.x / k; val dy = d.y / k
                            val n = if (mode == 2) {
                                val s = max(32f, r.size + (dx + dy) / 2)
                                r.copy(size = min(s, min(bmp.width - r.x, bmp.height - r.y)))
                            } else r.copy(x = (r.x + dx).coerceIn(0f, bmp.width - r.size), y = (r.y + dy).coerceIn(0f, bmp.height - r.size))
                            onChange(n)
                        },
                    )
                } else Modifier)
            ) {
                val r = rect ?: return@Canvas
                val tl = Offset(r.x * k, r.y * k); val s = r.size * k
                drawRect(CV.Accent, tl, Size(s, s), style = Stroke(3f))
                for (i in 1 until 8) {
                    drawLine(CV.Accent.copy(alpha = 0.35f), Offset(tl.x + s * i / 8, tl.y), Offset(tl.x + s * i / 8, tl.y + s), 1f)
                    drawLine(CV.Accent.copy(alpha = 0.35f), Offset(tl.x, tl.y + s * i / 8), Offset(tl.x + s, tl.y + s * i / 8), 1f)
                }
                if (editable) drawCircle(CV.Accent, 14f, Offset(tl.x + s, tl.y + s))
            }
        }
    }
}
