package com.chessvision.ai.ui.board

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chessvision.ai.pieces.PieceSetAssets
import com.chessvision.ai.ui.theme.CV
import com.chessvision.core.chess.Move
import com.chessvision.core.chess.Pieces
import com.chessvision.core.chess.Position
import com.chessvision.core.chess.Squares
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

data class BoardArrow(val from: Int, val to: Int, val color: Color, val width: Float = 0.17f)

private fun displayCol(sq: Int, flipped: Boolean) = if (!flipped) Squares.file(sq) else 7 - Squares.file(sq)
private fun displayRow(sq: Int, flipped: Boolean) = if (!flipped) 7 - Squares.rank(sq) else Squares.rank(sq)
private fun squareAt(col: Int, row: Int, flipped: Boolean) = if (!flipped) Squares.of(col, 7 - row) else Squares.of(7 - col, row)

@Composable
fun ChessBoard(
    position: Position,
    pieceSet: PieceSetAssets,
    theme: CV.BoardTheme,
    modifier: Modifier = Modifier,
    flipped: Boolean = false,
    lastMove: Move? = null,
    arrows: List<BoardArrow> = emptyList(),
    squareMarks: Map<Int, Color> = emptyMap(),
    showCoordinates: Boolean = true,
    /** Colour the user may move (true white / false black), null = nobody. */
    movable: Boolean? = position.whiteToMove,
    onMove: (Move) -> Unit = {},
    /** Board-editor mode: taps are forwarded here and no moves are made. */
    onSquareTap: ((Int) -> Unit)? = null,
    animate: Boolean = true,
) {
    var selected by remember { mutableStateOf<Int?>(null) }
    var dragFrom by remember { mutableStateOf<Int?>(null) }
    var dragPos by remember { mutableStateOf(Offset.Zero) }
    var promotion by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val anim = remember { Animatable(1f) }
    var animMove by remember { mutableStateOf<Move?>(null) }
    var prevPosition by remember { mutableStateOf(position) }
    var suppressAnim by remember { mutableStateOf(false) }

    LaunchedEffect(position) {
        selected = null; promotion = null
        val lm = lastMove
        val wasDragged = suppressAnim
        suppressAnim = false
        if (animate && lm != null && prevPosition != position && !wasDragged &&
            prevPosition[lm.from] != 0 && position[lm.to] != 0 && position[lm.from] == 0
        ) {
            animMove = lm
            anim.snapTo(0f)
            anim.animateTo(1f, tween(180, easing = FastOutSlowInEasing))
        }
        animMove = null
        prevPosition = position
    }

    val legalTargets = remember(position, selected) {
        val s = selected ?: return@remember emptySet<Int>()
        position.legalMoves().filter { it.from == s }.map { it.to }.toSet()
    }
    val coordPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) } }

    fun canMove(sq: Int): Boolean {
        val p = position[sq]
        return p != 0 && movable != null && (p > 0) == movable && (p > 0) == position.whiteToMove
    }

    fun tryMove(from: Int, to: Int): Boolean {
        if (from == to) return false
        if (position.legalMoves().none { it.from == from && it.to == to }) return false
        if (position.isPromotion(from, to)) { promotion = from to to; return true }
        position.findMove(from, to)?.let { onMove(it) }
        selected = null
        return true
    }

    BoxWithConstraints(modifier.aspectRatio(1f).clip(RoundedCornerShape(6.dp))) {
        val sizePx = with(LocalDensity.current) { maxWidth.toPx() }
        val cell = sizePx / 8f
        val cellPx = cell.roundToInt().coerceAtLeast(8)

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(position, flipped, movable, onSquareTap != null) {
                    detectTapGestures { off ->
                        val col = (off.x / cell).toInt().coerceIn(0, 7)
                        val row = (off.y / cell).toInt().coerceIn(0, 7)
                        val sq = squareAt(col, row, flipped)
                        if (onSquareTap != null) { onSquareTap(sq); return@detectTapGestures }
                        if (promotion != null) return@detectTapGestures
                        val sel = selected
                        when {
                            sel != null && sel != sq && tryMove(sel, sq) -> {}
                            canMove(sq) -> selected = if (sel == sq) null else sq
                            else -> selected = null
                        }
                    }
                }
                .pointerInput(position, flipped, movable, onSquareTap != null) {
                    if (onSquareTap != null) return@pointerInput
                    detectDragGestures(
                        onDragStart = { off ->
                            val sq = squareAt((off.x / cell).toInt().coerceIn(0, 7), (off.y / cell).toInt().coerceIn(0, 7), flipped)
                            if (canMove(sq) && promotion == null) { dragFrom = sq; selected = sq; dragPos = off }
                        },
                        onDrag = { change, amount -> if (dragFrom != null) { change.consume(); dragPos += amount } },
                        onDragEnd = {
                            val from = dragFrom
                            if (from != null) {
                                suppressAnim = true
                                val col = (dragPos.x / cell).toInt(); val row = (dragPos.y / cell).toInt()
                                if (col in 0..7 && row in 0..7) tryMove(from, squareAt(col, row, flipped))
                            }
                            dragFrom = null
                        },
                        onDragCancel = { dragFrom = null },
                    )
                }
        ) {
            // squares
            for (row in 0 until 8) for (col in 0 until 8) {
                val light = (row + col) % 2 == 0
                drawRect(if (light) theme.light else theme.dark, Offset(col * cell, row * cell), Size(cell + 0.5f, cell + 0.5f))
            }
            fun tl(sq: Int) = Offset(displayCol(sq, flipped) * cell, displayRow(sq, flipped) * cell)
            lastMove?.let { m ->
                drawRect(theme.lastMove, tl(m.from), Size(cell, cell))
                drawRect(theme.lastMove, tl(m.to), Size(cell, cell))
            }
            for ((sq, c) in squareMarks) drawRect(c, tl(sq), Size(cell, cell))
            selected?.let { drawRect(Color(0x663DDC84), tl(it), Size(cell, cell)) }
            if (position.inCheck()) {
                val k = position.kingSquare(position.whiteToMove)
                if (k >= 0) {
                    val c = tl(k) + Offset(cell / 2, cell / 2)
                    drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(listOf(Color(0xCCE5534B), Color.Transparent), c, cell * 0.62f), cell * 0.62f, c)
                }
            }
            // coordinates
            if (showCoordinates) drawIntoCanvas { cv ->
                coordPaint.textSize = cell * 0.17f
                for (i in 0 until 8) {
                    val rankSq = squareAt(0, i, flipped)
                    coordPaint.color = (if (i % 2 == 0) theme.dark else theme.light).toArgb()
                    cv.nativeCanvas.drawText("${Squares.rank(rankSq) + 1}", cell * 0.05f, i * cell + cell * 0.2f, coordPaint)
                    val fileSq = squareAt(i, 7, flipped)
                    coordPaint.color = (if ((7 + i) % 2 == 0) theme.dark else theme.light).toArgb()
                    cv.nativeCanvas.drawText("${'a' + Squares.file(fileSq)}", i * cell + cell * 0.83f, 8 * cell - cell * 0.06f, coordPaint)
                }
            }
            // pieces
            val am = animMove
            for (sq in 0 until 64) {
                val p = position[sq]
                if (p == 0) continue
                if (sq == dragFrom) continue
                var o = tl(sq)
                if (am != null && sq == am.to) {
                    val a = tl(am.from)
                    o = Offset(a.x + (o.x - a.x) * anim.value, a.y + (o.y - a.y) * anim.value)
                }
                drawImage(pieceSet.bitmap(p, cellPx).asImageBitmap(), dstOffset = IntOffset(o.x.roundToInt(), o.y.roundToInt()), dstSize = IntSize(cellPx, cellPx))
            }
            // legal targets
            for (t in legalTargets) {
                val c = tl(t) + Offset(cell / 2, cell / 2)
                if (position[t] != 0) drawCircle(Color(0x55101010), cell * 0.46f, c, style = Stroke(cell * 0.08f))
                else drawCircle(Color(0x55101010), cell * 0.15f, c)
            }
            // arrows
            for (a in arrows) drawArrow(a, tl(a.from) + Offset(cell / 2, cell / 2), tl(a.to) + Offset(cell / 2, cell / 2), cell)
            // dragged piece on top
            dragFrom?.let { sq ->
                val p = position[sq]
                if (p != 0) {
                    val s = (cellPx * 1.25f).roundToInt()
                    drawImage(pieceSet.bitmap(p, s).asImageBitmap(),
                        dstOffset = IntOffset((dragPos.x - s / 2f).roundToInt(), (dragPos.y - s * 0.8f).roundToInt()), dstSize = IntSize(s, s))
                }
            }
        }

        // promotion picker
        promotion?.let { (from, to) ->
            val white = position[from] > 0
            Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable { promotion = null; selected = null }, contentAlignment = Alignment.Center) {
                Row(
                    Modifier.clip(RoundedCornerShape(12.dp)).background(CV.Surface2).padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (t in listOf(Pieces.QUEEN, Pieces.ROOK, Pieces.BISHOP, Pieces.KNIGHT)) {
                        val piece = if (white) t else -t
                        Box(
                            Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(CV.Surface3).clickable {
                                position.findMove(from, to, t)?.let(onMove)
                                promotion = null; selected = null
                            },
                            contentAlignment = Alignment.Center,
                        ) { PieceIcon(pieceSet, piece, Modifier.size(48.dp)) }
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawArrow(a: BoardArrow, from: Offset, to: Offset, cell: Float) {
    val ang = atan2(to.y - from.y, to.x - from.x)
    val len = hypot(to.x - from.x, to.y - from.y)
    if (len < 1f) return
    val w = cell * a.width
    val head = cell * 0.42f
    val start = Offset(from.x + cos(ang) * cell * 0.18f, from.y + sin(ang) * cell * 0.18f)
    val shaftEnd = Offset(to.x - cos(ang) * head * 0.9f, to.y - sin(ang) * head * 0.9f)
    val nx = -sin(ang); val ny = cos(ang)
    val path = Path().apply {
        moveTo(start.x + nx * w / 2, start.y + ny * w / 2)
        lineTo(shaftEnd.x + nx * w / 2, shaftEnd.y + ny * w / 2)
        lineTo(shaftEnd.x + nx * head * 0.62f, shaftEnd.y + ny * head * 0.62f)
        lineTo(to.x, to.y)
        lineTo(shaftEnd.x - nx * head * 0.62f, shaftEnd.y - ny * head * 0.62f)
        lineTo(shaftEnd.x - nx * w / 2, shaftEnd.y - ny * w / 2)
        lineTo(start.x - nx * w / 2, start.y - ny * w / 2)
        close()
    }
    drawPath(path, a.color.copy(alpha = a.color.alpha * 0.82f))
}

@Composable
fun PieceIcon(pieceSet: PieceSetAssets, piece: Int, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val s = size.minDimension.roundToInt().coerceAtLeast(4)
        drawImage(pieceSet.bitmap(piece, s).asImageBitmap(), dstOffset = IntOffset(0, 0), dstSize = IntSize(s, s))
    }
}

/** Vertical evaluation bar (white share from the bottom). */
@Composable
fun EvalBar(whitePercent: Double, label: String, flipped: Boolean, modifier: Modifier = Modifier) {
    val frac by animateFloatAsState((whitePercent / 100.0).toFloat().coerceIn(0.02f, 0.98f), tween(350), label = "eval")
    Box(modifier.width(22.dp).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(Color(0xFF2B2E33))) {
        Canvas(Modifier.fillMaxSize()) {
            val h = size.height * frac
            if (!flipped) drawRect(Color(0xFFEDEFF2), Offset(0f, size.height - h), Size(size.width, h))
            else drawRect(Color(0xFFEDEFF2), Offset.Zero, Size(size.width, h))
            drawLine(CV.Accent.copy(alpha = 0.6f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2f)
        }
        val whiteBetter = whitePercent >= 50
        Text(
            label.removePrefix("+").removePrefix("−").take(5),
            Modifier.align(if (whiteBetter != flipped) Alignment.BottomCenter else Alignment.TopCenter).padding(vertical = 3.dp),
            color = if (whiteBetter) Color(0xFF1A1C1F) else Color(0xFFEDEFF2),
            fontSize = 8.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        )
    }
}
