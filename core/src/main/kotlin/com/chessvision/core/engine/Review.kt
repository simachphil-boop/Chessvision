package com.chessvision.core.engine

import com.chessvision.core.chess.*
import kotlin.math.exp
import kotlin.math.max

enum class MoveClass(val ru: String, val symbol: String, val color: Long) {
    BEST("Лучший ход", "★", 0xFF4CAF50),
    EXCELLENT("Отлично", "!", 0xFF8BC34A),
    GOOD("Хорошо", "✓", 0xFF9EB5A0),
    BOOK("Дебют", "📖", 0xFFA1887F),
    INACCURACY("Неточность", "?!", 0xFFF2C14E),
    MISTAKE("Ошибка", "?", 0xFFFF9800),
    BLUNDER("Зевок", "??", 0xFFE53935),
    MISSED_WIN("Упущенная победа", "✗", 0xFFE57373),
    FORCED("Единственный ход", "□", 0xFF90A4AE);
}

data class MoveReview(
    val ply: Int,
    val san: String,
    val uci: String,
    val whiteMoved: Boolean,
    val fenBefore: String,
    /** White POV evals. */
    val evalBefore: Score,
    val evalAfter: Score,
    val bestMoveUci: String?,
    val bestMoveSan: String?,
    val bestLineSan: String,
    val winLoss: Double,
    val accuracy: Double,
    val classification: MoveClass,
)

data class ReviewResult(
    val moves: List<MoveReview>,
    val accuracyWhite: Double,
    val accuracyBlack: Double,
    /** Eval (white POV) of every position, index = ply. */
    val evals: List<Score>,
) {
    fun count(white: Boolean, c: MoveClass) = moves.count { it.whiteMoved == white && it.classification == c }
}

object MoveClassifier {
    fun accuracyFromWinLoss(loss: Double): Double =
        (103.1668 * exp(-0.04354 * max(0.0, loss)) - 3.1669).coerceIn(0.0, 100.0)

    fun classify(
        isBest: Boolean, winLoss: Double, onlyMove: Boolean, moverWinBefore: Double, moverWinAfter: Double,
        bookPly: Boolean,
    ): MoveClass = when {
        onlyMove -> MoveClass.FORCED
        bookPly && winLoss < 5 -> MoveClass.BOOK
        isBest || winLoss < 0.5 -> MoveClass.BEST
        moverWinBefore > 80 && moverWinAfter < 60 -> MoveClass.MISSED_WIN
        winLoss < 2 -> MoveClass.EXCELLENT
        winLoss < 5 -> MoveClass.GOOD
        winLoss < 10 -> MoveClass.INACCURACY
        winLoss < 20 -> MoveClass.MISTAKE
        else -> MoveClass.BLUNDER
    }
}

/** Full game review with the engine. */
class GameReviewer(private val engine: UciEngine) {
    /** Scores a terminal position (white POV) without engine. */
    private fun terminalScore(p: Position): Score? {
        if (p.legalMoves().isNotEmpty()) return null
        return if (p.inCheck()) Score(cp = if (p.whiteToMove) -Score.MATED else Score.MATED) else Score(cp = 0)
    }

    suspend fun review(
        game: Game,
        depth: Int = 14,
        bookPlies: Int = 0,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): ReviewResult {
        val positions = game.positions
        val evals = ArrayList<Score>()
        val bestMoves = ArrayList<String?>()
        val bestLines = ArrayList<List<String>>()
        val secondGap = ArrayList<Double>() // win% gap between best and second best, mover POV
        val total = positions.size
        for ((i, p) in positions.withIndex()) {
            val term = terminalScore(p)
            if (term != null) {
                evals.add(term); bestMoves.add(null); bestLines.add(emptyList()); secondGap.add(0.0)
            } else {
                val r = engine.search(Fen.toFen(p), limit = SearchLimit(depth = depth), multiPv = 2)
                val best = r.lines.firstOrNull { it.multipv == 1 }
                val second = r.lines.firstOrNull { it.multipv == 2 }
                evals.add(best?.score ?: Score(cp = 0))
                bestMoves.add(r.bestMove)
                bestLines.add(best?.pv ?: emptyList())
                val gap = if (best != null && second != null) {
                    val wb = best.score.whiteWinPercent(); val ws = second.score.whiteWinPercent()
                    if (p.whiteToMove) wb - ws else ws - wb
                } else if (p.legalMoves().size == 1) 100.0 else 0.0
                secondGap.add(gap)
            }
            onProgress(i + 1, total)
        }
        val reviews = ArrayList<MoveReview>()
        for (ply in 0 until game.size) {
            val before = positions[ply]
            val m = game.moves[ply]
            val white = before.whiteToMove
            val eb = evals[ply]; val ea = evals[ply + 1]
            val wb = eb.whiteWinPercent(); val wa = ea.whiteWinPercent()
            val moverBefore = if (white) wb else 100 - wb
            val moverAfter = if (white) wa else 100 - wa
            val loss = max(0.0, moverBefore - moverAfter)
            val best = bestMoves[ply]
            val isBest = best == m.uci()
            val onlyMove = before.legalMoves().size == 1
            val bestSan = best?.let { b -> Move.fromUci(b)?.let { mv -> if (before.isLegal(mv)) San.toSan(before, mv) else null } }
            val cls = MoveClassifier.classify(isBest, loss, onlyMove, moverBefore, moverAfter, ply < bookPlies)
            reviews.add(
                MoveReview(
                    ply, game.sans[ply], m.uci(), white, Fen.toFen(before), eb, ea, best, bestSan,
                    San.pvToSan(before, bestLines[ply], 8), loss,
                    if (onlyMove) 100.0 else MoveClassifier.accuracyFromWinLoss(loss), cls,
                )
            )
        }
        fun acc(white: Boolean): Double {
            val xs = reviews.filter { it.whiteMoved == white }.map { it.accuracy }
            if (xs.isEmpty()) return 0.0
            // blend of arithmetic and harmonic mean (punishes blunders, like popular review tools)
            val arith = xs.average()
            val harm = xs.size / xs.sumOf { 1.0 / max(it, 1.0) }
            return ((arith + harm) / 2).coerceIn(0.0, 100.0)
        }
        return ReviewResult(reviews, acc(true), acc(false), evals)
    }
}
