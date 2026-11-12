package com.chessvision.ai

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.chessvision.ai.ui.screens.*
import com.chessvision.ai.ui.theme.ChessVisionTheme
import com.chessvision.core.chess.Pgn

class MainActivity : ComponentActivity() {
    private var pendingRoute: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val g = (application as ChessVisionApp).graph
        handleShare(intent, g)
        setContent {
            ChessVisionTheme {
                val nav = rememberNavController()
                LaunchedEffect(Unit) { pendingRoute?.let { pendingRoute = null; nav.navigate(it) } }
                NavHost(
                    nav, startDestination = "home",
                    enterTransition = { slideInHorizontally { it / 6 } + fadeIn() },
                    exitTransition = { fadeOut() },
                    popEnterTransition = { fadeIn() },
                    popExitTransition = { slideOutHorizontally { it / 6 } + fadeOut() },
                ) {
                    composable("home") { HomeScreen(g) { nav.navigate(it) } }
                    composable("analysis") {
                        AnalysisScreen(g, onBack = { nav.popBackStack() }, onReview = { id -> nav.navigate("review/$id") })
                    }
                    composable("screenshot") {
                        ScreenshotScreen(g, onBack = { nav.popBackStack() }, onAnalyze = { nav.navigate("analysis") { popUpTo("screenshot") { inclusive = true } } })
                    }
                    composable("training") { TrainingScreen(g, onBack = { nav.popBackStack() }) }
                    composable("puzzles") { PuzzleScreen(g, onBack = { nav.popBackStack() }) }
                    composable("reviews") { ReviewListScreen(g, onBack = { nav.popBackStack() }, onOpen = { id -> nav.navigate("review/$id") }) }
                    composable("review/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
                        ReviewScreen(g, e.arguments?.getLong("id") ?: 0L, onBack = { nav.popBackStack() },
                            onAnalyze = { nav.navigate("analysis") }, onTraining = { nav.navigate("training") })
                    }
                    composable("practice") { PracticeScreen(g, onBack = { nav.popBackStack() }, onReview = { id -> nav.navigate("review/$id") }) }
                    composable("settings") { SettingsScreen(g, onBack = { nav.popBackStack() }) }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val g = (application as ChessVisionApp).graph
        handleShare(intent, g)
        if (pendingRoute != null) { setIntent(intent); recreate() }
    }

    private fun handleShare(intent: Intent?, g: AppGraph) {
        if (intent?.action != Intent.ACTION_SEND) return
        val type = intent.type ?: return
        if (type.startsWith("image/")) {
            val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            if (uri != null) { g.pendingImageUri = uri.toString(); pendingRoute = "screenshot" }
        } else if (type == "text/plain") {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
            runCatching { Pgn.parse(Pgn.splitGames(text).first()) }.getOrNull()?.let { g.pendingGame = it; pendingRoute = "analysis" }
                ?: runCatching { com.chessvision.core.chess.Game(text.trim()) }.getOrNull()?.let { g.pendingGame = it; pendingRoute = "analysis" }
        }
    }
}
