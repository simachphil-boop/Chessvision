package com.chessvision.ai.ui.board

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.chessvision.ai.pieces.PieceSetAssets
import com.chessvision.ai.ui.theme.CV
import com.chessvision.core.chess.Fen
import com.chessvision.core.chess.Pieces
import com.chessvision.core.chess.Position
import com.chessvision.core.chess.PositionValidator

/** Piece palette used by the board editor; 0 = eraser. */
@Composable
fun PiecePalette(pieceSet: PieceSetAssets, selected: Int, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (white in listOf(true, false)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                for (t in listOf(Pieces.KING, Pieces.QUEEN, Pieces.ROOK, Pieces.BISHOP, Pieces.KNIGHT, Pieces.PAWN)) {
                    val p = if (white) t else -t
                    Box(
                        Modifier.size(44.dp).clip(RoundedCornerShape(8.dp))
                            .background(if (selected == p) CV.Accent.copy(alpha = 0.3f) else CV.Surface2)
                            .border(1.dp, if (selected == p) CV.Accent else Color.Transparent, RoundedCornerShape(8.dp))
                            .clickable { onSelect(p) },
                        contentAlignment = Alignment.Center,
                    ) { PieceIcon(pieceSet, p, Modifier.size(38.dp)) }
                }
                if (white) Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(8.dp))
                        .background(if (selected == 0) CV.Bad.copy(alpha = 0.3f) else CV.Surface2)
                        .clickable { onSelect(0) },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Delete, "Стереть", tint = CV.Text) }
            }
        }
    }
}

/**
 * Full-screen position editor. Returns the edited position via [onDone].
 */
@Composable
fun BoardEditorDialog(
    initial: Position,
    pieceSet: PieceSetAssets,
    theme: CV.BoardTheme,
    flipped: Boolean,
    onDismiss: () -> Unit,
    onDone: (Position) -> Unit,
) {
    var board by remember { mutableStateOf(initial.board()) }
    var whiteToMove by remember { mutableStateOf(initial.whiteToMove) }
    var brush by remember { mutableIntStateOf(Pieces.PAWN) }
    var flip by remember { mutableStateOf(flipped) }
    val issues = remember(board.toList(), whiteToMove) { PositionValidator.validate(board, whiteToMove) }
    val position = remember(board.toList(), whiteToMove) { Position(board.copyOf(), whiteToMove, 0, -1, 0, 1) }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        ScreenScaffold("Редактор позиции", onBack = onDismiss, actions = {
            IconButton(onClick = { flip = !flip }) { Icon(Icons.Filled.SwapVert, "Перевернуть", tint = CV.Text) }
        }) {
            Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ChessBoard(
                    position, pieceSet, theme, Modifier.fillMaxWidth(), flipped = flip, movable = null,
                    onSquareTap = { sq ->
                        val b = board.copyOf()
                        b[sq] = if (b[sq] == brush) 0 else brush
                        board = b
                    },
                    animate = false,
                )
                PiecePalette(pieceSet, brush) { brush = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Ход:", color = CV.TextDim)
                    FilterChip(whiteToMove, { whiteToMove = true }, { Text("белых") })
                    FilterChip(!whiteToMove, { whiteToMove = false }, { Text("чёрных") })
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { board = Position.start().board(); whiteToMove = true }) { Text("Начальная") }
                    TextButton(onClick = { board = IntArray(64) }) { Text("Очистить") }
                }
                if (issues.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (i in issues) InfoBanner(i.message, if (i.fatal) CV.Bad else CV.Warn, Icons.Filled.Warning)
                } else InfoBanner("Позиция корректна", CV.Accent, Icons.Filled.CheckCircle)
                PrimaryButton("Готово", Modifier.fillMaxWidth(), Icons.Filled.Check, enabled = issues.none { it.fatal }) {
                    onDone(Fen.fromBoard(board, whiteToMove))
                }
            }
        }
    }
}
