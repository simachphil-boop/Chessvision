package com.chessvision.core

import com.chessvision.core.chess.*
import com.chessvision.core.engine.*
import kotlinx.coroutines.*

/** Integration tests against a real Stockfish binary (runs in CI). */
object EngineTests {
    fun run(path: String) = runBlocking {
        val e = UciEngine(listOf(path))
        e.start(threads = 2, hashMb = 32)
        println("engine: ${e.engineName}, options=${e.options.size}")
        T.check("engine name", e.engineName.contains("Stockfish", ignoreCase = true)) { e.engineName }

        // Mate in 1 (back rank): white Re8#
        val r1 = e.search("6k1/5ppp/8/8/8/8/5PPP/4R1K1 w - - 0 1", limit = SearchLimit(depth = 10))
        T.eq("mate1 bestmove", "e1e8", r1.bestMove)
        T.eq("mate1 score", 1, r1.best?.score?.mate)

        // Black to move, black mates: score must be negative from white POV
        val r2 = e.search("4r1k1/5ppp/8/8/8/8/5PPP/6K1 b - - 0 1", limit = SearchLimit(depth = 10))
        T.eq("black mate1 best", "e8e1", r2.bestMove)
        T.eq("black mate1 white-POV", -1, r2.best?.score?.mate)

        // MultiPV
        var updates = 0
        val r3 = e.search(Position.START_FEN, limit = SearchLimit(depth = 12), multiPv = 3) { updates++ }
        T.eq("multipv lines", 3, r3.lines.size)
        T.check("multipv updates", updates > 3) { "updates=$updates" }
        T.check("multipv distinct", r3.lines.map { it.pv.first() }.toSet().size == 3)
        T.check("start eval sane", (r3.best?.score?.cp ?: 999) in -80..120) { "${r3.best?.score}" }
        // every pv must be legal
        for (l in r3.lines) {
            var p = Position.start()
            var ok = true
            for (u in l.pv) { val m = Move.fromUci(u); if (m == null || !p.isLegal(m)) { ok = false; break }; p = p.play(m) }
            T.check("pv legal ${l.multipv}", ok) { l.pv.joinToString(" ") }
        }

        // Moves after FEN
        val r4 = e.search(Position.START_FEN, listOf("e2e4", "e7e5"), SearchLimit(depth = 8))
        T.check("moves list", r4.bestMove != null && Position.start().play(Move.fromUci("e2e4")!!).play(Move.fromUci("e7e5")!!).isLegal(Move.fromUci(r4.bestMove!!)!!))

        // Cancellation stops infinite search quickly and engine stays usable
        val job = launch { e.search(Position.START_FEN, limit = SearchLimit(infinite = true)) }
        delay(500); val t0 = System.currentTimeMillis(); job.cancelAndJoin()
        T.check("cancel fast", System.currentTimeMillis() - t0 < 3000)
        val r5 = e.search(Position.START_FEN, limit = SearchLimit(depth = 6))
        T.check("usable after cancel", r5.bestMove != null)

        // Limited strength
        val r6 = e.search(Position.START_FEN, limit = SearchLimit(movetimeMs = 200), strength = EngineStrength(elo = 1400))
        T.check("elo search", r6.bestMove != null)
        val r7 = e.search(Position.START_FEN, limit = SearchLimit(movetimeMs = 200), strength = EngineStrength(skill = 3))
        T.check("skill search", r7.bestMove != null)

        // Stalemate / no legal moves
        val r8 = e.search("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1", limit = SearchLimit(depth = 5))
        T.eq("no move", null, r8.bestMove)

        // Review: blunder detection (Blackburne Shilling trap, white blunders 5.Nxf7?? / 6.Rf1)
        val g = Pgn.parse("1. e4 e5 2. Nf3 Nc6 3. Bc4 Nd4 4. Nxe5 Qg5 5. Nxf7 Qxg2 6. Rf1 Qxe4+ 7. Be2 Nf3#")
        val rev = GameReviewer(e).review(g, depth = 10)
        T.eq("review moves", g.size, rev.moves.size)
        T.check("review finds blunder", rev.moves.any { it.whiteMoved && it.classification in setOf(MoveClass.BLUNDER, MoveClass.MISTAKE) })
        T.check("review last mate", rev.evals.last().whiteWinPercent() == 0.0)
        T.check("accuracy range", rev.accuracyWhite in 0.0..100.0 && rev.accuracyBlack > rev.accuracyWhite) {
            "w=${rev.accuracyWhite} b=${rev.accuracyBlack}"
        }
        e.close()
    }
}
