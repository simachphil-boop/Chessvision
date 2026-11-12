package com.chessvision.ai

import android.app.Application
import com.chessvision.ai.data.AppDatabase
import com.chessvision.ai.data.PuzzleRepository
import com.chessvision.ai.data.SettingsStore
import com.chessvision.ai.engine.EngineManager
import com.chessvision.ai.pieces.PieceSetAssets
import com.chessvision.ai.vision.RecognitionService
import com.chessvision.core.chess.Game
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Simple service locator for the whole app. */
class AppGraph(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsStore(app)
    val db = AppDatabase.create(app)
    val engine = EngineManager(app, settings)
    val puzzles = PuzzleRepository(app, db)
    val recognition = RecognitionService(app, db)

    private val pieceSets = HashMap<String, PieceSetAssets>()

    @Synchronized
    fun pieceSet(name: String = settings.value.pieceSet): PieceSetAssets =
        pieceSets.getOrPut(name) { runCatching { PieceSetAssets.load(app, name) }.getOrElse { PieceSetAssets.load(app, "cburnett") } }

    /** Hand-off of a game/position between screens (e.g. screenshot → analysis). */
    @Volatile var pendingGame: Game? = null
    @Volatile var pendingReviewGameId: Long? = null
    @Volatile var pendingImageUri: String? = null
}

class ChessVisionApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}
