package com.chessvision.ai.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettings(
    val analysisDepth: Int = 18,
    val multiPv: Int = 3,
    val threads: Int = 2,
    val hashMb: Int = 64,
    /** Skill level used for analysis (20 = full strength). */
    val analysisSkill: Int = 20,
    val pieceSet: String = "cburnett",
    val boardTheme: Int = 0,
    val showCoordinates: Boolean = true,
    val showArrows: Boolean = true,
    val showEvalBar: Boolean = true,
    val autoAnalyze: Boolean = true,
    val practiceElo: Int = 1500,
    val practiceSkillMode: Boolean = false,
    val practiceSkill: Int = 5,
    val puzzleRating: Int = 1200,
    val reviewDepth: Int = 14,
    val soundOn: Boolean = true,
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _flow = MutableStateFlow(load())
    val flow: StateFlow<AppSettings> = _flow.asStateFlow()
    val value: AppSettings get() = _flow.value

    private fun defaultThreads(): Int = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(1, 4)

    private fun load(): AppSettings {
        val d = AppSettings(threads = defaultThreads())
        return AppSettings(
            analysisDepth = prefs.getInt("analysisDepth", d.analysisDepth),
            multiPv = prefs.getInt("multiPv", d.multiPv),
            threads = prefs.getInt("threads", d.threads),
            hashMb = prefs.getInt("hashMb", d.hashMb),
            analysisSkill = prefs.getInt("analysisSkill", d.analysisSkill),
            pieceSet = prefs.getString("pieceSet", d.pieceSet) ?: d.pieceSet,
            boardTheme = prefs.getInt("boardTheme", d.boardTheme),
            showCoordinates = prefs.getBoolean("showCoordinates", d.showCoordinates),
            showArrows = prefs.getBoolean("showArrows", d.showArrows),
            showEvalBar = prefs.getBoolean("showEvalBar", d.showEvalBar),
            autoAnalyze = prefs.getBoolean("autoAnalyze", d.autoAnalyze),
            practiceElo = prefs.getInt("practiceElo", d.practiceElo),
            practiceSkillMode = prefs.getBoolean("practiceSkillMode", d.practiceSkillMode),
            practiceSkill = prefs.getInt("practiceSkill", d.practiceSkill),
            puzzleRating = prefs.getInt("puzzleRating", d.puzzleRating),
            reviewDepth = prefs.getInt("reviewDepth", d.reviewDepth),
            soundOn = prefs.getBoolean("soundOn", d.soundOn),
        )
    }

    fun update(f: (AppSettings) -> AppSettings) {
        val s = f(_flow.value)
        prefs.edit()
            .putInt("analysisDepth", s.analysisDepth)
            .putInt("multiPv", s.multiPv)
            .putInt("threads", s.threads)
            .putInt("hashMb", s.hashMb)
            .putInt("analysisSkill", s.analysisSkill)
            .putString("pieceSet", s.pieceSet)
            .putInt("boardTheme", s.boardTheme)
            .putBoolean("showCoordinates", s.showCoordinates)
            .putBoolean("showArrows", s.showArrows)
            .putBoolean("showEvalBar", s.showEvalBar)
            .putBoolean("autoAnalyze", s.autoAnalyze)
            .putInt("practiceElo", s.practiceElo)
            .putBoolean("practiceSkillMode", s.practiceSkillMode)
            .putInt("practiceSkill", s.practiceSkill)
            .putInt("puzzleRating", s.puzzleRating)
            .putInt("reviewDepth", s.reviewDepth)
            .putBoolean("soundOn", s.soundOn)
            .apply()
        _flow.value = s
    }
}
