package com.chessvision.core

import com.chessvision.core.chess.*

object T {
    var passed = 0
    var failed = 0
    fun check(name: String, cond: Boolean, detail: () -> String = { "" }) {
        if (cond) passed++ else { failed++; println("FAIL: $name ${detail()}") }
    }
    fun <A> eq(name: String, expected: A, actual: A) =
        check(name, expected == actual) { "expected=$expected actual=$actual" }
}

object ChessTests {
    fun perft(p: Position, d: Int): Long {
        if (d == 0) return 1
        val ms = p.legalMoves()
        if (d == 1) return ms.size.toLong()
        var n = 0L
        for (m in ms) n += perft(p.playUnchecked(m), d - 1)
        return n
    }

    fun run() {
        // Standard perft suite (chessprogramming.org)
        T.eq("perft start d4", 197281L, perft(Position.start(), 4))
        T.eq("perft kiwipete d3", 97862L, perft(Fen.parse("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1"), 3))
        T.eq("perft pos3 d5", 674624L, perft(Fen.parse("8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1"), 5))
        T.eq("perft pos4 d4", 422333L, perft(Fen.parse("r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1"), 4))
        T.eq("perft pos5 d3", 62379L, perft(Fen.parse("rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8"), 3))
        T.eq("perft pos6 d3", 89890L, perft(Fen.parse("r4rk1/1pp1qppp/p1np1n2/2b1p1B1/2B1P1b1/P1NP1N2/1PP1QPPP/R4RK1 w - - 0 10"), 3))

        // FEN roundtrip
        val f = "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1"
        T.eq("fen roundtrip", f, Fen.toFen(Fen.parse(f)))

        // SAN
        val g = Game()
        for (s in "e4 e5 Nf3 Nc6 Bb5 a6 Ba4 Nf6 O-O Be7 Re1 b5 Bb3 d6 c3 O-O h3".split(" ")) T.check("san $s", g.playSan(s))
        T.eq("movetext", "1. e4 e5 2. Nf3 Nc6 3. Bb5 a6 4. Ba4 Nf6 5. O-O Be7 6. Re1 b5 7. Bb3 d6 8. c3 O-O 9. h3", g.movetext())

        // Scholar's mate, checkmate detection, SAN '#'
        val m = Game()
        "e4 e5 Bc4 Nc6 Qh5 Nf6 Qxf7#".split(" ").forEach { m.playSan(it) }
        T.eq("mate san", "Qxf7#", m.sans.last())
        T.eq("mate result", GameResult.WHITE_WINS, m.result())

        // Disambiguation
        val d = Game("4k3/8/8/8/8/8/6K1/R6R w - - 0 1")
        T.check("Rad1", d.playSan("Rad1")); T.eq("san Rad1", "Rad1", d.sans.last())
        val d2 = Game("4k3/8/8/R7/8/8/8/R3K3 w - - 0 1")
        T.check("R1a3", d2.playSan("R1a3")); T.eq("san R1a3", "R1a3", d2.sans.last())

        // Promotion & en passant
        val pr = Game("8/P6k/8/8/8/8/8/K7 w - - 0 1"); pr.playSan("a8=Q"); T.eq("promo", "a8=Q", pr.sans.last())
        val ep = Game("4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1"); T.check("ep", ep.playSan("exd6")); T.eq("ep board", 0, ep.current[Squares.parse("d5")])

        // PGN parse with comments/variations/NAGs
        val pgn = """
            [Event "Test"]
            [White "A"]
            [Black "B"]
            [Result "1-0"]

            1. e4 {best by test} e5 2. Nf3 (2. f4 exf4) Nc6 $1 3. Bc4 Nd4?! 4. Nxe5 Qg5 5. Nxf7 Qxg2 6. Rf1 Qxe4+ 7. Be2 Nf3# 0-1
        """.trimIndent()
        val pg = Pgn.parse(pgn)
        T.eq("pgn moves", 14, pg.size)
        T.eq("pgn result", GameResult.BLACK_WINS, pg.result())
        val again = Pgn.parse(pg.toPgn())
        T.eq("pgn roundtrip", pg.movetext(), again.movetext())

        // Stalemate & insufficient
        T.check("stalemate", Fen.parse("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1").isStalemate())
        T.check("insufficient", Fen.parse("8/8/4k3/8/8/2B5/4K3/8 w - - 0 1").isInsufficientMaterial())

        // Validator
        T.check("validator start ok", PositionValidator.isValid(Position.start().board(), true))
        val bad = Fen.parse("8/8/8/8/8/8/8/K7 w - - 0 1").board()
        T.check("validator no black king", !PositionValidator.isValid(bad, true))
        val chk = Fen.parse("4k3/8/8/8/8/8/4R3/4K3 w - - 0 1").board()
        T.check("validator opp in check", !PositionValidator.isValid(chk, true))

        // PV to SAN
        T.eq("pv san", "1. e4 e5 2. Nf3", San.pvToSan(Position.start(), listOf("e2e4", "e7e5", "g1f3")))
    }
}
