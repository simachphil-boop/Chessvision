package com.chessvision.ai.ui.board

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chessvision.ai.engine.AnalysisState
import com.chessvision.ai.engine.UiLine
import com.chessvision.ai.ui.theme.CV
import com.chessvision.ai.ui.theme.Mono

@Composable
fun ScreenScaffold(
    title: String,
    onBack: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().background(CV.Bg).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = CV.Text) }
            else Spacer(Modifier.width(12.dp))
            Text(title, style = MaterialTheme.typography.titleLarge, color = CV.Text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            actions()
        }
        content()
    }
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(CV.Surface).border(1.dp, CV.Stroke, RoundedCornerShape(14.dp)).padding(12.dp),
        content = content,
    )
}

@Composable
fun Chip(text: String, color: Color = CV.Accent, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Box(
        modifier.clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.16f))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) { Text(text, color = color, style = MaterialTheme.typography.labelMedium, maxLines = 1) }
}

@Composable
fun ToolButton(icon: ImageVector, label: String, enabled: Boolean = true, tint: Color = CV.Text, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(10.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp).alpha(if (enabled) 1f else 0.35f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(22.dp))
        Text(label, color = CV.TextDim, fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
fun NavBar(canBack: Boolean, canForward: Boolean, onStart: () -> Unit, onBack: () -> Unit, onForward: () -> Unit, onEnd: () -> Unit, extra: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CV.Surface), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onStart, enabled = canBack) { Icon(Icons.Filled.FirstPage, "В начало", tint = if (canBack) CV.Text else CV.TextFaint) }
        IconButton(onClick = onBack, enabled = canBack) { Icon(Icons.Filled.ChevronLeft, "Назад", tint = if (canBack) CV.Text else CV.TextFaint) }
        IconButton(onClick = onForward, enabled = canForward) { Icon(Icons.Filled.ChevronRight, "Вперёд", tint = if (canForward) CV.Text else CV.TextFaint) }
        IconButton(onClick = onEnd, enabled = canForward) { Icon(Icons.Filled.LastPage, "В конец", tint = if (canForward) CV.Text else CV.TextFaint) }
        extra()
    }
}

/** Horizontal move list; [ply] = number of moves played up to the displayed position. */
@Composable
fun MoveStrip(sans: List<String>, startMoveNumber: Int, startWhite: Boolean, ply: Int, onSelect: (Int) -> Unit, marks: List<Pair<String, Color>>? = null) {
    val scroll = rememberScrollState()
    LaunchedEffect(ply, sans.size) { scroll.animateScrollTo(if (ply >= sans.size) scroll.maxValue else (scroll.maxValue * ply / maxOf(1, sans.size))) }
    Row(Modifier.fillMaxWidth().horizontalScroll(scroll).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (sans.isEmpty()) Text("Ходов пока нет", color = CV.TextFaint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 8.dp))
        var moveNo = startMoveNumber
        var white = startWhite
        for ((i, s) in sans.withIndex()) {
            if (white || i == 0) Text(if (white) "$moveNo." else "$moveNo…", color = CV.TextFaint, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp, end = 2.dp))
            val active = i + 1 == ply
            val mark = marks?.getOrNull(i)
            Row(
                Modifier.clip(RoundedCornerShape(6.dp)).background(if (active) CV.Accent.copy(alpha = 0.22f) else Color.Transparent)
                    .clickable { onSelect(i + 1) }.padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(s, color = if (active) CV.Accent else CV.Text, fontSize = 14.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
                if (mark != null && mark.first.isNotEmpty()) Text(mark.first, color = mark.second, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 2.dp))
            }
            if (!white) moveNo++
            white = !white
        }
    }
}

/** Engine output panel: lines with eval, depth, PV. Tap a line to explore it. */
@Composable
fun EnginePanel(
    state: AnalysisState,
    enabled: Boolean,
    onToggle: () -> Unit,
    onLineClick: (UiLine) -> Unit,
    modifier: Modifier = Modifier,
) {
    Panel(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val pulse = rememberInfiniteTransition(label = "p").animateFloat(0.3f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a")
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (state.running) CV.Accent.copy(alpha = pulse.value) else if (enabled) CV.AccentDim else CV.TextFaint))
            Spacer(Modifier.width(8.dp))
            Text(state.engineName ?: "Stockfish", color = CV.Text, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (enabled && (state.depth > 0 || state.running)) Text(
                "глубина ${state.depth}/${state.targetDepth}" + if (state.nps > 0) " · ${state.nps / 1000} kN/s" else "",
                color = CV.TextDim, fontSize = 11.sp, fontFamily = Mono,
            )
            Switch(checked = enabled, onCheckedChange = { onToggle() }, modifier = Modifier.padding(start = 6.dp).height(24.dp),
                colors = SwitchDefaults.colors(checkedTrackColor = CV.AccentDim, checkedThumbColor = CV.Accent))
        }
        AnimatedVisibility(enabled) {
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                state.error?.let { Text(it, color = CV.Bad, style = MaterialTheme.typography.bodySmall) }
                if (state.lines.isEmpty() && state.error == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = CV.Accent, trackColor = CV.Surface3)
                    Text("Анализ…", color = CV.TextDim, style = MaterialTheme.typography.bodySmall)
                }
                for (l in state.lines) EngineLineRow(l, onClick = { onLineClick(l) })
            }
        }
    }
}

@Composable
fun EngineLineRow(l: UiLine, onClick: () -> Unit) {
    val color = CV.LineColors.getOrElse(l.multipv - 1) { CV.TextDim }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(CV.Surface2).clickable(onClick = onClick).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(4.dp).height(28.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Spacer(Modifier.width(8.dp))
        val evalText = l.score.display()
        val whiteGood = l.score.sortKey() >= 0
        Box(
            Modifier.widthIn(min = 56.dp).clip(RoundedCornerShape(6.dp)).background(if (whiteGood) Color(0xFFE8EAED) else Color(0xFF2B2E33)).padding(horizontal = 6.dp, vertical = 3.dp),
            contentAlignment = Alignment.Center,
        ) { Text(evalText, color = if (whiteGood) Color(0xFF15171A) else Color(0xFFE8EAED), fontWeight = FontWeight.Bold, fontSize = 13.sp, fontFamily = Mono) }
        Spacer(Modifier.width(8.dp))
        Text(l.pvSan, color = CV.Text, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

@Composable
fun InfoBanner(text: String, color: Color = CV.Info, icon: ImageVector = Icons.Filled.Info) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.12f)).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, color = CV.Text, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.height(48.dp), shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = CV.Accent, contentColor = Color(0xFF00210F)),
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, enabled = enabled, modifier = modifier.height(48.dp), shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = CV.Text),
        border = androidx.compose.foundation.BorderStroke(1.dp, CV.Stroke),
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text)
    }
}
