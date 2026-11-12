package com.chessvision.ai.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chessvision.ai.AppGraph
import com.chessvision.ai.engine.EngineStatus
import com.chessvision.ai.ui.theme.CV
import kotlinx.coroutines.launch

data class ModeItem(val route: String, val title: String, val subtitle: String, val icon: ImageVector, val color: Color)

val MODES = listOf(
    ModeItem("analysis", "Analysis", "Анализ позиции движком", Icons.Filled.Analytics, CV.Accent),
    ModeItem("screenshot", "Screenshot", "Позиция со скриншота", Icons.Filled.DocumentScanner, CV.Info),
    ModeItem("training", "Training", "Учись у движка", Icons.Filled.School, Color(0xFFC678DD)),
    ModeItem("puzzles", "Puzzles", "Тактические задачи", Icons.Filled.Extension, CV.Warn),
    ModeItem("reviews", "Game Review", "Разбор партий", Icons.Filled.Insights, Color(0xFFF2A54E)),
    ModeItem("practice", "Practice", "Игра против Stockfish", Icons.Filled.SportsEsports, CV.Bad),
)

@Composable
fun HomeScreen(g: AppGraph, onOpen: (String) -> Unit) {
    val status by g.engine.status.collectAsStateWithLifecycle()
    val games by g.db.games().all().collectAsStateWithLifecycle(emptyList())
    val solved by g.db.puzzleStats().solvedCount().collectAsStateWithLifecycle(0)
    val settings by g.settings.flow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { scope.launch { runCatching { g.engine.get() } } }

    Column(Modifier.fillMaxSize().background(CV.Bg).systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(listOf(CV.AccentDim, CV.Surface3))), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Visibility, null, tint = CV.Accent)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("ChessVision AI", style = MaterialTheme.typography.headlineMedium, color = CV.Text)
                Text("Stockfish на твоём телефоне, офлайн", color = CV.TextDim, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = { onOpen("settings") }) { Icon(Icons.Filled.Settings, "Настройки", tint = CV.TextDim) }
        }
        EngineStatusCard(status) { scope.launch { runCatching { g.engine.get() } } }

        val rows = MODES.chunked(2)
        for (r in rows) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (m in r) ModeCard(m, Modifier.weight(1f)) { onOpen(m.route) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard("Партий", games.size.toString(), Modifier.weight(1f))
            StatCard("Задач решено", solved.toString(), Modifier.weight(1f))
            StatCard("Рейтинг задач", settings.puzzleRating.toString(), Modifier.weight(1f))
        }

        if (games.isNotEmpty()) {
            Text("Последние партии", style = MaterialTheme.typography.titleMedium, color = CV.Text)
            for (gm in games.take(4)) Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CV.Surface).clickable { onOpen("review/${gm.id}") }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.History, null, tint = CV.TextDim)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("${gm.white} — ${gm.black}", color = CV.Text, maxLines = 1)
                    Text(gm.title, color = CV.TextFaint, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
                Text(gm.result, color = CV.Accent, fontWeight = FontWeight.Bold)
            }
        }
        Text(
            "Подсказки движка — для разбора, задач и тренировок. Не используй их во время живых партий на шахматных сайтах: это нарушает правила fair play.",
            color = CV.TextFaint, style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun EngineStatusCard(status: EngineStatus, onRetry: () -> Unit) {
    val (text, color) = when (status) {
        EngineStatus.Idle -> "Движок не запущен" to CV.TextDim
        EngineStatus.Starting -> "Запуск Stockfish…" to CV.Warn
        is EngineStatus.Ready -> status.name to CV.Accent
        is EngineStatus.Failed -> status.message to CV.Bad
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CV.Surface).border(1.dp, CV.Stroke, RoundedCornerShape(14.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("Шахматный движок", color = CV.TextDim, style = MaterialTheme.typography.labelMedium)
            Text(text, color = CV.Text, style = MaterialTheme.typography.bodyMedium)
        }
        if (status is EngineStatus.Failed) TextButton(onClick = onRetry) { Text("Повторить") }
    }
}

@Composable
private fun ModeCard(m: ModeItem, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.height(128.dp).clip(RoundedCornerShape(16.dp))
            .background(Brush.verticalGradient(listOf(CV.Surface2, CV.Surface)))
            .border(1.dp, CV.Stroke, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick).padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(m.color.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            Icon(m.icon, null, tint = m.color)
        }
        Column {
            Text(m.title, color = CV.Text, style = MaterialTheme.typography.titleMedium)
            Text(m.subtitle, color = CV.TextDim, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(CV.Surface).padding(10.dp)) {
        Text(value, color = CV.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(label, color = CV.TextFaint, style = MaterialTheme.typography.bodySmall, maxLines = 1)
    }
}

@Suppress("unused")
@Composable
private fun Dot() = Canvas(Modifier.size(1.dp)) { drawCircle(Color.Transparent, 1f, Offset.Zero) }
