package com.chessvision.core.engine

import com.chessvision.core.chess.*
import kotlin.math.abs
import kotlin.random.Random

/**
 * A tactic puzzle. [fen] is the position with the SOLVER to move; [solution] alternates
 * solver move, opponent reply, solver move… (UCI) and always ends with a solver move.
 * [lastMove] is the opponent move that led to the position (for highlighting), may be null.
 */
data class Puzzle(
    val id: String,
    val fen: String,
    val solution: List<String>,
    val rating: Int,
    val theme: String,
    val lastMove: String? = null,
) {
    val solverWhite: Boolean get() = fen.split(' ').getOrElse(1) { "w" } == "w"
    val themeRu: String get() = when {
        theme.startsWith("mateIn") -> "Мат в ${theme.removePrefix("mateIn")}"
        theme == "crushing" -> "Решающий перевес"
        theme == "advantage" -> "Выигрыш материала"
        theme == "mistake" -> "Своя ошибка"
        else -> theme
    }
}

object PuzzleJson {
    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

    fun write(puzzles: List<Puzzle>): String = buildString {
        append("[\n")
        puzzles.forEachIndexed { i, p ->
            append("  {\"id\":\"${esc(p.id)}\",\"fen\":\"${esc(p.fen)}\",\"solution\":[")
            append(p.solution.joinToString(",") { "\"$it\"" })
            append("],\"rating\":${p.rating},\"theme\":\"${esc(p.theme)}\"")
            if (p.lastMove != null) append(",\"lastMove\":\"${p.lastMove}\"")
            append("}")
            if (i < puzzles.size - 1) append(',')
            append('\n')
        }
        append("]\n")
    }

    /** Tiny parser for the format produced by [write] (no external JSON library needed). */
    fun parse(text: String): List<Puzzle> {
        val out = ArrayList<Puzzle>()
        val objRe = Regex("\\{[^{}]*\\}")
        fun str(o: String, k: String) = Regex("\"$k\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(o)?.groupValues?.get(1)?.replace("\\\"", "\"")?.replace("\\\\", "\\")
        for (m in objRe.findAll(text)) {
            val o = m.value
            val id = str(o, "id") ?: continue
            val fen = str(o, "fen") ?: continue
            val sol = Regex("\"solution\"\\s*:\\s*\\[([^]]*)]").find(o)?.groupValues?.get(1)
                ?.split(',')?.map { it.trim().trim('"') }?.filter { it.isNotEmpty() } ?: continue
            val rating = Regex("\"rating\"\\s*:\\s*(\\d+)").find(o)?.groupValues?.get(1)?.toIntOrNull() ?: 1500
            out.add(Puzzle(id, fen, sol, rating, str(o, "theme") ?: "advantage", str(o, "lastMove")))
        }
        return out
    }
}

/**
 * Generates verified tactic puzzles from engine self-play with deliberately weak play
 * (blunders create tactics), then verifies every solver move with a full-strength search:
 * the solution move must be clearly better than any alternative.
 */
class PuzzleGenerator(private val engine: UciEngine, private val rnd: Random = Random.Default) {
    private val full = EngineStrength()

    private fun solverCp(s: Score, solverWhite: Boolean): Int = (if (solverWhite) s else s.negate()).sortKey()

    private suspend fun analyse(fen: String, depth: Int, multi: Int) =
        engine.search(fen, limit = SearchLimit(depth = depth), multiPv = multi, strength = full)

    /** Checks the solver move in [pos] is unique; returns (move, solverScoreKey) or null. */
    private suspend fun uniqueBest(pos: Position, depth: Int): Pair<String, Int>? {
        val r = analyse(Fen.toFen(pos), depth, 2)
        val b = r.lines.firstOrNull { it.multipv == 1 } ?: return null
        val sw = pos.whiteToMove
        val bk = solverCp(b.score, sw)
        val second = r.lines.firstOrNull { it.multipv == 2 }
        if (second == null) return if (pos.legalMoves().size == 1) b.pv.first() to bk else null
        val sk = solverCp(second.score, sw)
        val bMate = b.score.mate?.let { if (sw) it else -it }
        val sMate = second.score.mate?.let { if (sw) it else -it }
        val unique = when {
            bMate != null && bMate > 0 -> sMate == null || sMate <= 0 || sMate > bMate + 1 && sk < 300
            else -> bk - sk >= 220 && sk < 250
        }
        return if (unique) b.pv.first() to bk else null
    }

    suspend fun tryPosition(pos: Position, lastMove: String?, id: String): Puzzle? {
        val first = uniqueBest(pos, 16) ?: return null
        val solverWhite = pos.whiteToMove
        if (first.second < 280) return null
        val solution = ArrayList<String>()
        var cur = pos
        var mateTheme: Int? = null
        run {
            val r = analyse(Fen.toFen(pos), 16, 1)
            val m = r.best?.score?.mate?.let { if (solverWhite) it else -it }
            if (m != null && m in 1..4) mateTheme = m
        }
        var playerMoves = 0
        var move = first.first
        while (true) {
            val mv = Move.fromUci(move) ?: break
            if (!cur.isLegal(mv)) break
            solution.add(move); playerMoves++
            cur = cur.play(mv)
            if (cur.legalMoves().isEmpty()) break
            val maxMoves = mateTheme ?: 3
            if (playerMoves >= maxMoves) break
            // opponent best reply
            val reply = analyse(Fen.toFen(cur), 14, 1).bestMove ?: break
            val rm = Move.fromUci(reply) ?: break
            if (!cur.isLegal(rm)) break
            val afterReply = cur.play(rm)
            val next = uniqueBest(afterReply, 16)
            if (next == null) {
                if (mateTheme != null) return null // a mate puzzle must be forced all the way
                break
            }
            // non-mate: only continue while the line keeps the advantage
            if (mateTheme == null && next.second < 280) break
            solution.add(reply)
            cur = afterReply
            move = next.first
        }
        if (solution.isEmpty() || solution.size % 2 == 0) return null
        if (mateTheme != null && !cur.isCheckmate()) return null
        val finalEval = if (cur.legalMoves().isEmpty()) 100_000 else
            solverCp(analyse(Fen.toFen(cur), 12, 1).best?.score ?: return null, solverWhite)
        if (mateTheme == null && finalEval < 250) return null
        val theme = mateTheme?.let { "mateIn$it" } ?: if (finalEval >= 600) "crushing" else "advantage"
        val rating = (900 + 260 * playerMoves + (if (mateTheme == null) 150 else 0) + rnd.nextInt(-80, 80)).coerceIn(600, 2600)
        return Puzzle(id, Fen.toFen(pos), solution, rating, theme, lastMove)
    }

    /** Self-play loop; calls [onPuzzle] for each verified puzzle until [count] or the time budget is used. */
    suspend fun generate(count: Int, timeBudgetMs: Long, idPrefix: String = "g", onPuzzle: (Puzzle) -> Unit = {}): List<Puzzle> {
        val out = ArrayList<Puzzle>()
        val seen = HashSet<String>()
        val deadline = System.currentTimeMillis() + timeBudgetMs
        var gameNo = 0
        while (out.size < count && System.currentTimeMillis() < deadline) {
            gameNo++
            engine.newGame()
            var pos = Position.start()
            var prevKey: Int? = null // eval from the side that just moved, before its move
            val weak = EngineStrength(skill = rnd.nextInt(0, 9))
            var lastMove: String? = null
            var cooldown = 0
            for (ply in 0 until 160) {
                if (pos.legalMoves().isEmpty() || pos.isInsufficientMaterial() || System.currentTimeMillis() > deadline) break
                // screening eval of the position for the side to move
                val scr = analyse(Fen.toFen(pos), 10, 1)
                val keyStm = scr.best?.score?.let { solverCp(it, pos.whiteToMove) } ?: 0
                // the opponent's last move was a blunder if the eval swung strongly
                val swing = prevKey?.let { before -> keyStm + before } ?: 0 // before is from opponent POV
                if (ply >= 8 && cooldown == 0 && keyStm >= 300 && prevKey != null && prevKey >= -150 && swing >= 300 && abs(keyStm) < 100_000 - 10) {
                    val key = Fen.boardPart(pos.board())
                    if (key !in seen) {
                        val pz = tryPosition(pos, lastMove, "$idPrefix${gameNo}_$ply")
                        if (pz != null) { seen.add(key); out.add(pz); onPuzzle(pz); cooldown = 12; if (out.size >= count) break }
                    }
                }
                if (cooldown > 0) cooldown--
                if (abs(keyStm) > 1500 && ply > 20) break // game decided
                // choose next move: random opening, weak engine afterwards
                val mv: Move = if (ply < 4 + rnd.nextInt(4)) pos.legalMoves().random(rnd) else {
                    val r = engine.search(Fen.toFen(pos), limit = SearchLimit(movetimeMs = 40), strength = weak)
                    r.bestMove?.let { Move.fromUci(it) }?.takeIf { pos.isLegal(it) } ?: pos.legalMoves().random(rnd)
                }
                prevKey = keyStm
                lastMove = mv.uci()
                pos = pos.play(mv)
            }
        }
        return out
    }
}

/** Builds a "learn from your mistakes" exercise from a reviewed move. */
object MistakePuzzles {
    fun fromReview(gameId: Long, r: ReviewResult): List<Puzzle> = r.moves
        .filter { it.classification in setOf(MoveClass.MISTAKE, MoveClass.BLUNDER, MoveClass.MISSED_WIN) && it.bestMoveUci != null }
        .map { Puzzle("m${gameId}_${it.ply}", it.fenBefore, listOf(it.bestMoveUci!!), 1500, "mistake", null) }
}
