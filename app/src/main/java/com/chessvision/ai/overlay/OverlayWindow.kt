package com.chessvision.ai.overlay

import android.view.MotionEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chessvision.ai.ui.board.BoardArrow
import com.chessvision.ai.ui.board.ChessBoard
import com.chessvision.ai.ui.theme.CV
import com.chessvision.ai.ui.theme.Mono
import com.chessvision.core.chess.Fen
import com.chessvision.core.chess.Move
import com.chessvision.core.chess.San

/** Drag that uses raw screen coordinates, so moving the window under the finger doesn't jitter. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Modifier.rawDrag(onDelta: (Float, Float) -> Unit): Modifier {
    val last = remember { FloatArray(2) }
    val cb = rememberUpdatedState(onDelta)
    return pointerInteropFilter { e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { last[0] = e.rawX; last[1] = e.rawY; true }
            MotionEvent.ACTION_MOVE -> { cb.value(e.rawX - last[0], e.rawY - last[1]); last[0] = e.rawX; last[1] = e.rawY; true }
            else -> true
        }
    }
}

@Composable
private fun SmallIcon(icon: ImageVector, desc: String, tint: Color = CV.TextDim, onClick: () -> Unit) {
    Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, desc, tint = tint, modifier = Modifier.size(18.dp))
    }
}

@Composable
fun OverlayWindow(
    vm: OverlayViewModel,
    compact: Boolean,
    onToggleCompact: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onResize: (Float) -> Unit,
    onClose: () -> Unit,
    onOpenApp: () -> Unit,
) {
    val session by vm.repo.session.collectAsStateWithLifecycle()
    val an by vm.analysis.state.collectAsStateWithLifecycle()
    val engineOn by vm.engineOn.collectAsStateWithLifecycle()
    val g = vm.graph
    val settings by g.settings.flow.collectAsStateWithLifecycle()
    val pieceSet = remember(settings.pieceSet) { g.pieceSet(settings.pieceSet) }
    val theme = CV.BoardThemes.getOrElse(settings.boardTheme) { CV.BoardThemes[0] }
    val ply = session.ply.coerceIn(0, session.game.size)
    val pos = session.game.positions[ply]
    val fresh = an.fen == Fen.toFen(pos)
    val best = if (fresh) an.best else null
    val bestMove = best?.pvUci?.firstOrNull()?.let { Move.fromUci(it) }
    val bestSan = bestMove?.takeIf { pos.isLegal(it) }?.let { San.toSan(pos, it) }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xE6121417))
            .border(1.dp, CV.Stroke, RoundedCornerShape(16.dp)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // header = drag handle
        Row(Modifier.fillMaxWidth().rawDrag(onDrag), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.DragIndicator, null, tint = CV.TextFaint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            if (compact) {
                Text(best?.score?.display() ?: "…", color = CV.Accent, fontWeight = FontWeight.Bold, fontFamily = Mono, fontSize = 14.sp)
                Spacer(Modifier.width(8.dp))
                Text(bestSan ?: "", color = CV.Text, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1)
            } else Text(session.title, color = CV.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            SmallIcon(if (compact) Icons.Filled.OpenInFull else Icons.Filled.CloseFullscreen, if (compact) "Развернуть" else "Свернуть", onClick = onToggleCompact)
            SmallIcon(Icons.Filled.Close, "Закрыть", onClick = onClose)
        }
        AnimatedVisibility(!compact) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val arrows = if (fresh && settings.showArrows) an.lines.mapNotNull { l ->
                    l.pvUci.firstOrNull()?.let { Move.fromUci(it) }?.let { BoardArrow(it.from, it.to, CV.LineColors.getOrElse(l.multipv - 1) { CV.TextDim }, if (l.multipv == 1) 0.2f else 0.12f) }
                }.reversed() else emptyList()
                ChessBoard(pos, pieceSet, theme, Modifier.fillMaxWidth(), flipped = session.flipped,
                    lastMove = if (ply > 0) session.game.moves[ply - 1] else null, arrows = arrows,
                    showCoordinates = settings.showCoordinates, movable = pos.whiteToMove, onMove = { vm.move(it) })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.clip(RoundedCornerShape(6.dp)).background(if ((best?.score?.sortKey() ?: 0) >= 0) Color(0xFFE8EAED) else Color(0xFF2B2E33)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                        Text(best?.score?.display() ?: "—", color = if ((best?.score?.sortKey() ?: 0) >= 0) Color(0xFF15171A) else Color(0xFFE8EAED),
                            fontWeight = FontWeight.Bold, fontFamily = Mono, fontSize = 13.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (!engineOn) "Анализ остановлен" else bestSan?.let { "Лучший: $it" } ?: "Анализ…", color = CV.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        if (engineOn) Text("глубина ${an.depth}/${an.targetDepth}", color = CV.TextFaint, fontSize = 10.sp, fontFamily = Mono)
                    }
                    SmallIcon(if (engineOn) Icons.Filled.Stop else Icons.Filled.PlayArrow, if (engineOn) "Остановить" else "Анализ", tint = if (engineOn) CV.Bad else CV.Accent) { vm.toggleEngine() }
                }
                if (fresh) for (l in an.lines.drop(1).take(2)) Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(CV.LineColors.getOrElse(l.multipv - 1) { CV.TextDim }))
                    Spacer(Modifier.width(6.dp))
                    Text(l.score.display(), color = CV.TextDim, fontFamily = Mono, fontSize = 11.sp, modifier = Modifier.width(48.dp))
                    Text(l.pvSan, color = CV.TextDim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(Fen.toFen(pos), color = CV.TextFaint, fontSize = 9.sp, fontFamily = Mono, maxLines = 2)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SmallIcon(Icons.Filled.ChevronLeft, "Назад") { vm.goTo(ply - 1) }
                    SmallIcon(Icons.Filled.ChevronRight, "Вперёд") { vm.goTo(ply + 1) }
                    SmallIcon(Icons.Filled.SwapVert, "Перевернуть") { vm.flip() }
                    SmallIcon(Icons.Filled.AutoFixHigh, "Сыграть лучший") { vm.playBest() }
                    SmallIcon(Icons.Filled.OpenInNew, "Открыть в приложении", onClick = onOpenApp)
                    Spacer(Modifier.weight(1f))
                    // resize handle (drag horizontally)
                    Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(CV.Surface3).rawDrag { dx, dy -> onResize(dx + dy) },
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.OpenWith, "Изменить размер", tint = CV.TextDim, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}
