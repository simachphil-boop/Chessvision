package com.chessvision.ai.data

import android.content.Context
import com.chessvision.core.engine.Puzzle
import com.chessvision.core.engine.PuzzleJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Puzzles come from two places: the bundled bank (assets/puzzles.json, generated and verified
 * with Stockfish at build time) and puzzles generated on the device (files/generated_puzzles.json).
 */
class PuzzleRepository(private val context: Context, private val db: AppDatabase) {
    private val lock = Mutex()
    private var cache: List<Puzzle>? = null
    private val generatedFile get() = File(context.filesDir, "generated_puzzles.json")

    suspend fun all(): List<Puzzle> = lock.withLock {
        cache?.let { return@withLock it }
        val list = withContext(Dispatchers.IO) {
            val bundled = runCatching {
                context.assets.open("puzzles.json").bufferedReader().use { PuzzleJson.parse(it.readText()) }
            }.getOrDefault(emptyList())
            val gen = runCatching { if (generatedFile.exists()) PuzzleJson.parse(generatedFile.readText()) else emptyList() }
                .getOrDefault(emptyList())
            (bundled + gen).distinctBy { it.id }
        }
        cache = list
        list
    }

    suspend fun addGenerated(p: Puzzle) = lock.withLock {
        withContext(Dispatchers.IO) {
            val cur = runCatching { if (generatedFile.exists()) PuzzleJson.parse(generatedFile.readText()) else emptyList() }.getOrDefault(emptyList())
            generatedFile.writeText(PuzzleJson.write(cur + p))
        }
        cache = (cache ?: emptyList()) + p
    }

    suspend fun stats(): Map<String, PuzzleStatEntity> = db.puzzleStats().all().associateBy { it.puzzleId }

    /** Next puzzle near [rating], preferring unsolved, unseen ones. */
    suspend fun next(rating: Int, excludeId: String?): Puzzle? {
        val all = all()
        if (all.isEmpty()) return null
        val st = stats()
        val unseen = all.filter { it.id != excludeId && st[it.id] == null }
        val pool = unseen.ifEmpty { all.filter { it.id != excludeId && st[it.id]?.solved != true }.ifEmpty { all.filter { it.id != excludeId } } }
        if (pool.isEmpty()) return all.first()
        val near = pool.sortedBy { kotlin.math.abs(it.rating - rating) }.take(12)
        return near.random()
    }

    suspend fun record(p: Puzzle, solved: Boolean) {
        val prev = db.puzzleStats().all().firstOrNull { it.puzzleId == p.id }
        db.puzzleStats().put(PuzzleStatEntity(p.id, solved || (prev?.solved == true), (prev?.attempts ?: 0) + 1))
    }
}
