package com.chessvision.ai.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chessvision.ai.AppGraph
import com.chessvision.ai.engine.EngineStatus
import com.chessvision.ai.pieces.PieceSetAssets
import com.chessvision.ai.ui.board.ChessBoard
import com.chessvision.ai.ui.board.Panel
import com.chessvision.ai.ui.board.ScreenScaffold
import com.chessvision.ai.ui.board.SecondaryButton
import com.chessvision.ai.ui.theme.CV
import com.chessvision.core.chess.Position
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(g: AppGraph, onBack: () -> Unit) {
    val s by g.settings.flow.collectAsStateWithLifecycle()
    val status by g.engine.status.collectAsStateWithLifecycle()
    val learned by remember { g.db.samples().count() }.collectAsStateWithLifecycle(0)
    val scope = rememberCoroutineScope()
    val cores = Runtime.getRuntime().availableProcessors()

    ScreenScaffold("Настройки", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Panel {
                Text("Движок", style = MaterialTheme.typography.titleMedium, color = CV.Text)
                Text(when (val st = status) { is EngineStatus.Ready -> st.name; is EngineStatus.Failed -> st.message; else -> "—" },
                    color = CV.TextDim, style = MaterialTheme.typography.bodySmall)
                SliderRow("Глубина анализа", s.analysisDepth, 8..30) { v -> g.settings.update { it.copy(analysisDepth = v) } }
                SliderRow("Количество линий (MultiPV)", s.multiPv, 1..5) { v -> g.settings.update { it.copy(multiPv = v) } }
                SliderRow("Сила анализа (Skill Level)", s.analysisSkill, 0..20, suffix = if (s.analysisSkill == 20) " — максимум" else "") { v -> g.settings.update { it.copy(analysisSkill = v) } }
                SliderRow("Потоки процессора", s.threads, 1..cores.coerceAtLeast(1)) { v -> g.settings.update { it.copy(threads = v) } }
                SliderRow("Хэш, МБ", s.hashMb, 16..256, step = 16) { v -> g.settings.update { it.copy(hashMb = v) } }
                SliderRow("Глубина разбора партий", s.reviewDepth, 8..22) { v -> g.settings.update { it.copy(reviewDepth = v) } }
                Text("Потоки и хэш применяются при следующем запуске анализа.", color = CV.TextFaint, style = MaterialTheme.typography.bodySmall)
            }
            Panel {
                Text("Доска", style = MaterialTheme.typography.titleMedium, color = CV.Text)
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CV.BoardThemes.forEachIndexed { i, t -> FilterChip(s.boardTheme == i, { g.settings.update { it.copy(boardTheme = i) } }, { Text(t.name) }) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((id, name) in PieceSetAssets.SETS) FilterChip(s.pieceSet == id, { g.settings.update { it.copy(pieceSet = id) } }, { Text(name) })
                }
                Box(Modifier.fillMaxWidth(0.6f).align(Alignment.CenterHorizontally).padding(vertical = 8.dp)) {
                    ChessBoard(Position.start(), g.pieceSet(s.pieceSet), CV.BoardThemes[s.boardTheme.coerceIn(0, CV.BoardThemes.lastIndex)], movable = null, showCoordinates = s.showCoordinates)
                }
                SwitchRow("Координаты", s.showCoordinates) { v -> g.settings.update { it.copy(showCoordinates = v) } }
                SwitchRow("Стрелки лучших ходов", s.showArrows) { v -> g.settings.update { it.copy(showArrows = v) } }
                SwitchRow("Шкала оценки", s.showEvalBar) { v -> g.settings.update { it.copy(showEvalBar = v) } }
            }
            Panel {
                Text("Распознавание", style = MaterialTheme.typography.titleMedium, color = CV.Text)
                Text("Выученных образцов фигур: $learned. Они появляются, когда ты исправляешь распознанную позицию или калибруешь по начальной позиции.",
                    color = CV.TextDim, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                SecondaryButton("Сбросить обучение", Modifier.fillMaxWidth(), enabled = learned > 0) { scope.launch { g.recognition.forget() } }
            }
            Panel {
                Text("О приложении", style = MaterialTheme.typography.titleMedium, color = CV.Text)
                Text(
                    "ChessVision AI работает полностью офлайн. Движок: Stockfish (GPLv3, stockfishchess.org). " +
                        "Фигуры: Cburnett (GPLv2+), Merida (GPLv2+). Исходный код приложения открыт (GPLv3).",
                    color = CV.TextDim, style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
fun SliderRow(label: String, value: Int, range: IntRange, step: Int = 1, suffix: String = "", onChange: (Int) -> Unit) {
    var v by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column(Modifier.padding(top = 6.dp)) {
        Row {
            Text(label, color = CV.Text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text("${v.toInt()}$suffix", color = CV.Accent, style = MaterialTheme.typography.bodyMedium)
        }
        val steps = ((range.last - range.first) / step - 1).coerceAtLeast(0)
        Slider(v, { v = (Math.round(it / step) * step).toFloat().coerceIn(range.first.toFloat(), range.last.toFloat()) },
            valueRange = range.first.toFloat()..range.last.toFloat().coerceAtLeast(range.first + 1f), steps = steps,
            onValueChangeFinished = { onChange(v.toInt()) },
            colors = SliderDefaults.colors(thumbColor = CV.Accent, activeTrackColor = CV.Accent))
    }
}

@Composable
fun SwitchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = CV.Text, modifier = Modifier.weight(1f))
        Switch(value, onChange, colors = SwitchDefaults.colors(checkedTrackColor = CV.AccentDim, checkedThumbColor = CV.Accent))
    }
}
