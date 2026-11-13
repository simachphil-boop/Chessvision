package com.chessvision.ai.overlay

import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.chessvision.ai.ui.theme.CV

/** Handles the permission flow and starts the floating window. */
class OverlayLauncher internal constructor(private val start: () -> Unit) {
    fun launch() = start()
}

@Composable
fun rememberOverlayLauncher(): OverlayLauncher {
    val ctx = LocalContext.current
    var showExplain by remember { mutableStateOf(false) }
    var waitingForSettings by remember { mutableStateOf(false) }

    // Back from system settings: check once, start if granted. No repeated prompts.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (waitingForSettings) {
            waitingForSettings = false
            if (OverlayPermissionManager.canDraw(ctx)) FloatingOverlayService.start(ctx)
            else Toast.makeText(ctx, "Разрешение не выдано — плавающее окно недоступно", Toast.LENGTH_LONG).show()
        }
    }

    if (showExplain) AlertDialog(
        onDismissRequest = { showExplain = false },
        containerColor = CV.Surface,
        title = { Text("Плавающее окно") },
        text = {
            Text("Чтобы учебная доска с анализом Stockfish оставалась видна, когда ты переключаешься в другие приложения, Android требует разрешение «Поверх других приложений».\n\n" +
                "Окно показывает только позиции из ChessVision (анализ, импортированные партии, задачи) — оно не читает и не анализирует экран других приложений.")
        },
        confirmButton = {
            TextButton(onClick = {
                showExplain = false
                waitingForSettings = OverlayPermissionManager.openSettings(ctx)
                if (!waitingForSettings) Toast.makeText(ctx, "Не удалось открыть настройки. Найди: Настройки → Приложения → ChessVision → Поверх других приложений", Toast.LENGTH_LONG).show()
            }) { Text("Открыть настройки") }
        },
        dismissButton = { TextButton(onClick = { showExplain = false }) { Text("Не сейчас") } },
    )

    return remember {
        OverlayLauncher {
            if (OverlayPermissionManager.canDraw(ctx)) {
                if (!FloatingOverlayService.start(ctx)) Toast.makeText(ctx, "Не удалось запустить плавающее окно", Toast.LENGTH_SHORT).show()
            } else showExplain = true
        }
    }
}
