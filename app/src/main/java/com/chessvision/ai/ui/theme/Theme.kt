package com.chessvision.ai.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object CV {
    val Bg = Color(0xFF0E0F11)
    val Surface = Color(0xFF16181C)
    val Surface2 = Color(0xFF1E2126)
    val Surface3 = Color(0xFF272B31)
    val Stroke = Color(0xFF2E333A)
    val Text = Color(0xFFE8EAED)
    val TextDim = Color(0xFF9AA0A6)
    val TextFaint = Color(0xFF6B7178)
    val Accent = Color(0xFF3DDC84)
    val AccentDim = Color(0xFF1F6B44)
    val Warn = Color(0xFFF2C14E)
    val Bad = Color(0xFFE5534B)
    val Info = Color(0xFF58A6FF)

    /** Arrow colours for engine lines 1..5. */
    val LineColors = listOf(Color(0xFF3DDC84), Color(0xFF58A6FF), Color(0xFFF2A54E), Color(0xFFC678DD), Color(0xFFE06C75))

    data class BoardTheme(val name: String, val light: Color, val dark: Color, val lastMove: Color)

    val BoardThemes = listOf(
        BoardTheme("Графит", Color(0xFFB9BEC4), Color(0xFF5E6670), Color(0x993DDC84)),
        BoardTheme("Изумруд", Color(0xFFE6E9D8), Color(0xFF4E7D5B), Color(0x99F2C14E)),
        BoardTheme("Дерево", Color(0xFFF0D9B5), Color(0xFFB58863), Color(0x99CDD26A)),
        BoardTheme("Ночь", Color(0xFF8A929C), Color(0xFF3A4048), Color(0x9958A6FF)),
    )
}

private val scheme = darkColorScheme(
    primary = CV.Accent,
    onPrimary = Color(0xFF00210F),
    primaryContainer = CV.AccentDim,
    onPrimaryContainer = Color(0xFFC9F5DA),
    secondary = CV.Info,
    background = CV.Bg,
    onBackground = CV.Text,
    surface = CV.Surface,
    onSurface = CV.Text,
    surfaceVariant = CV.Surface2,
    onSurfaceVariant = CV.TextDim,
    surfaceContainer = CV.Surface,
    surfaceContainerHigh = CV.Surface2,
    surfaceContainerHighest = CV.Surface3,
    outline = CV.Stroke,
    outlineVariant = CV.Stroke,
    error = CV.Bad,
)

private val typography = Typography(
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 26.sp, letterSpacing = (-0.5).sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp),
)

val Mono = FontFamily.Monospace

@Composable
fun ChessVisionTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
