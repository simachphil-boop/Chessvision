package com.chessvision.core.chess

/**
 * Piece encoding: 0 = empty, positive = white, negative = black.
 * |piece|: 1 pawn, 2 knight, 3 bishop, 4 rook, 5 queen, 6 king.
 * Square index: rank * 8 + file, a1 = 0, h8 = 63.
 */
object Pieces {
    const val EMPTY = 0
    const val PAWN = 1
    const val KNIGHT = 2
    const val BISHOP = 3
    const val ROOK = 4
    const val QUEEN = 5
    const val KING = 6

    fun type(p: Int) = if (p < 0) -p else p
    fun isWhite(p: Int) = p > 0
    fun isBlack(p: Int) = p < 0

    fun toChar(p: Int): Char {
        val c = when (type(p)) {
            PAWN -> 'p'; KNIGHT -> 'n'; BISHOP -> 'b'; ROOK -> 'r'; QUEEN -> 'q'; KING -> 'k'
            else -> '.'
        }
        return if (p > 0) c.uppercaseChar() else c
    }

    fun fromChar(c: Char): Int {
        val t = when (c.lowercaseChar()) {
            'p' -> PAWN; 'n' -> KNIGHT; 'b' -> BISHOP; 'r' -> ROOK; 'q' -> QUEEN; 'k' -> KING
            else -> return EMPTY
        }
        return if (c.isUpperCase()) t else -t
    }
}

object Squares {
    fun file(sq: Int) = sq and 7
    fun rank(sq: Int) = sq shr 3
    fun of(file: Int, rank: Int) = rank * 8 + file
    fun name(sq: Int) = "${'a' + file(sq)}${'1' + rank(sq)}"
    fun parse(s: String): Int {
        if (s.length != 2) return -1
        val f = s[0] - 'a'
        val r = s[1] - '1'
        return if (f in 0..7 && r in 0..7) of(f, r) else -1
    }
}

data class Move(val from: Int, val to: Int, val promotion: Int = 0) {
    fun uci(): String = Squares.name(from) + Squares.name(to) +
            (if (promotion != 0) Pieces.toChar(-promotion).toString() else "")

    override fun toString() = uci()

    companion object {
        fun fromUci(s: String): Move? {
            if (s.length < 4) return null
            val from = Squares.parse(s.substring(0, 2))
            val to = Squares.parse(s.substring(2, 4))
            if (from < 0 || to < 0) return null
            val promo = if (s.length >= 5) Pieces.type(Pieces.fromChar(s[4])) else 0
            return Move(from, to, promo)
        }
    }
}

/** Castling rights bitmask. */
object Castling {
    const val WK = 1
    const val WQ = 2
    const val BK = 4
    const val BQ = 8
}

/**
 * Immutable chess position with complete rules (castling, en passant, promotion,
 * check, mate, stalemate, 50-move rule, insufficient material).
 */
class Position(
    private val squares: IntArray,
    val whiteToMove: Boolean,
    val castling: Int,
    /** En passant target square (the square passed over) or -1. */
    val epSquare: Int,
    val halfmoveClock: Int,
    val fullmoveNumber: Int,
) {
    operator fun get(sq: Int): Int = squares[sq]
    fun board(): IntArray = squares.copyOf()

    val sideSign: Int get() = if (whiteToMove) 1 else -1

    fun kingSquare(white: Boolean): Int {
        val k = if (white) Pieces.KING else -Pieces.KING
        for (i in 0 until 64) if (squares[i] == k) return i
        return -1
    }

    fun isAttacked(sq: Int, byWhite: Boolean): Boolean {
        val s = if (byWhite) 1 else -1
        val f = Squares.file(sq)
        val r = Squares.rank(sq)
        // pawns
        val pr = if (byWhite) r - 1 else r + 1
        if (pr in 0..7) {
            if (f > 0 && squares[Squares.of(f - 1, pr)] == s * Pieces.PAWN) return true
            if (f < 7 && squares[Squares.of(f + 1, pr)] == s * Pieces.PAWN) return true
        }
        for ((df, dr) in KNIGHT_D) {
            val nf = f + df; val nr = r + dr
            if (nf in 0..7 && nr in 0..7 && squares[Squares.of(nf, nr)] == s * Pieces.KNIGHT) return true
        }
        for ((df, dr) in KING_D) {
            val nf = f + df; val nr = r + dr
            if (nf in 0..7 && nr in 0..7 && squares[Squares.of(nf, nr)] == s * Pieces.KING) return true
        }
        for ((df, dr) in ROOK_D) {
            var nf = f + df; var nr = r + dr
            while (nf in 0..7 && nr in 0..7) {
                val p = squares[Squares.of(nf, nr)]
                if (p != 0) {
                    if (p == s * Pieces.ROOK || p == s * Pieces.QUEEN) return true
                    break
                }
                nf += df; nr += dr
            }
        }
        for ((df, dr) in BISHOP_D) {
            var nf = f + df; var nr = r + dr
            while (nf in 0..7 && nr in 0..7) {
                val p = squares[Squares.of(nf, nr)]
                if (p != 0) {
                    if (p == s * Pieces.BISHOP || p == s * Pieces.QUEEN) return true
                    break
                }
                nf += df; nr += dr
            }
        }
        return false
    }

    fun inCheck(): Boolean {
        val k = kingSquare(whiteToMove)
        return k >= 0 && isAttacked(k, !whiteToMove)
    }

    private fun pseudoMoves(out: MutableList<Move>) {
        val s = sideSign
        for (from in 0 until 64) {
            val p = squares[from]
            if (p == 0 || (p > 0) != whiteToMove) continue
            val f = Squares.file(from)
            val r = Squares.rank(from)
            when (Pieces.type(p)) {
                Pieces.PAWN -> {
                    val dir = s
                    val startRank = if (whiteToMove) 1 else 6
                    val promoRank = if (whiteToMove) 7 else 0
                    val r1 = r + dir
                    if (r1 in 0..7) {
                        val to = Squares.of(f, r1)
                        if (squares[to] == 0) {
                            addPawn(from, to, r1 == promoRank, out)
                            if (r == startRank) {
                                val to2 = Squares.of(f, r + 2 * dir)
                                if (squares[to2] == 0) out.add(Move(from, to2))
                            }
                        }
                        for (df in intArrayOf(-1, 1)) {
                            val nf = f + df
                            if (nf !in 0..7) continue
                            val to2 = Squares.of(nf, r1)
                            val t = squares[to2]
                            if (t != 0 && (t > 0) != whiteToMove) addPawn(from, to2, r1 == promoRank, out)
                            else if (to2 == epSquare) out.add(Move(from, to2))
                        }
                    }
                }
                Pieces.KNIGHT -> steps(from, f, r, KNIGHT_D, out)
                Pieces.KING -> {
                    steps(from, f, r, KING_D, out)
                    castles(from, out)
                }
                Pieces.BISHOP -> slides(from, f, r, BISHOP_D, out)
                Pieces.ROOK -> slides(from, f, r, ROOK_D, out)
                Pieces.QUEEN -> { slides(from, f, r, BISHOP_D, out); slides(from, f, r, ROOK_D, out) }
            }
        }
    }

    private fun addPawn(from: Int, to: Int, promo: Boolean, out: MutableList<Move>) {
        if (promo) {
            out.add(Move(from, to, Pieces.QUEEN)); out.add(Move(from, to, Pieces.ROOK))
            out.add(Move(from, to, Pieces.BISHOP)); out.add(Move(from, to, Pieces.KNIGHT))
        } else out.add(Move(from, to))
    }

    private fun steps(from: Int, f: Int, r: Int, d: Array<IntArray>, out: MutableList<Move>) {
        for ((df, dr) in d) {
            val nf = f + df; val nr = r + dr
            if (nf !in 0..7 || nr !in 0..7) continue
            val to = Squares.of(nf, nr)
            val t = squares[to]
            if (t == 0 || (t > 0) != whiteToMove) out.add(Move(from, to))
        }
    }

    private fun slides(from: Int, f: Int, r: Int, d: Array<IntArray>, out: MutableList<Move>) {
        for ((df, dr) in d) {
            var nf = f + df; var nr = r + dr
            while (nf in 0..7 && nr in 0..7) {
                val to = Squares.of(nf, nr)
                val t = squares[to]
                if (t == 0) out.add(Move(from, to))
                else {
                    if ((t > 0) != whiteToMove) out.add(Move(from, to))
                    break
                }
                nf += df; nr += dr
            }
        }
    }

    private fun castles(from: Int, out: MutableList<Move>) {
        val white = whiteToMove
        val home = if (white) 4 else 60
        if (from != home) return
        val enemy = !white
        if (isAttacked(home, enemy)) return
        val kRight = if (white) Castling.WK else Castling.BK
        val qRight = if (white) Castling.WQ else Castling.BQ
        val rook = if (white) Pieces.ROOK else -Pieces.ROOK
        if (castling and kRight != 0 && squares[home + 3] == rook &&
            squares[home + 1] == 0 && squares[home + 2] == 0 &&
            !isAttacked(home + 1, enemy) && !isAttacked(home + 2, enemy)
        ) out.add(Move(home, home + 2))
        if (castling and qRight != 0 && squares[home - 4] == rook &&
            squares[home - 1] == 0 && squares[home - 2] == 0 && squares[home - 3] == 0 &&
            !isAttacked(home - 1, enemy) && !isAttacked(home - 2, enemy)
        ) out.add(Move(home, home - 2))
    }

    private var cachedLegal: List<Move>? = null

    fun legalMoves(): List<Move> {
        cachedLegal?.let { return it }
        val pseudo = ArrayList<Move>(48)
        pseudoMoves(pseudo)
        val legal = pseudo.filter { m ->
            val next = playUnchecked(m)
            val k = next.kingSquare(whiteToMove)
            k >= 0 && !next.isAttacked(k, !whiteToMove)
        }
        cachedLegal = legal
        return legal
    }

    fun isLegal(m: Move): Boolean = legalMoves().contains(m)

    /** Finds the legal move matching from/to; promotion defaults to queen if unspecified. */
    fun findMove(from: Int, to: Int, promotion: Int = 0): Move? {
        val cands = legalMoves().filter { it.from == from && it.to == to }
        if (cands.isEmpty()) return null
        if (promotion != 0) return cands.firstOrNull { it.promotion == promotion }
        return cands.firstOrNull { it.promotion == 0 } ?: cands.firstOrNull { it.promotion == Pieces.QUEEN }
    }

    fun isPromotion(from: Int, to: Int): Boolean =
        legalMoves().any { it.from == from && it.to == to && it.promotion != 0 }

    fun isCapture(m: Move): Boolean =
        squares[m.to] != 0 || (Pieces.type(squares[m.from]) == Pieces.PAWN && m.to == epSquare && Squares.file(m.from) != Squares.file(m.to))

    fun play(m: Move): Position {
        require(isLegal(m)) { "Illegal move ${m.uci()} in ${Fen.toFen(this)}" }
        return playUnchecked(m)
    }

    /** Applies a move without legality checking (move must be at least pseudo-legal). */
    fun playUnchecked(m: Move): Position {
        val b = squares.copyOf()
        val p = b[m.from]
        val type = Pieces.type(p)
        val captured = b[m.to]
        var newEp = -1
        var cr = castling
        b[m.to] = p
        b[m.from] = 0
        if (type == Pieces.PAWN) {
            if (m.to == epSquare && Squares.file(m.from) != Squares.file(m.to) && captured == 0) {
                val capSq = Squares.of(Squares.file(m.to), Squares.rank(m.from))
                b[capSq] = 0
            }
            if (kotlin.math.abs(m.to - m.from) == 16) newEp = (m.to + m.from) / 2
            if (m.promotion != 0) b[m.to] = if (p > 0) m.promotion else -m.promotion
        }
        if (type == Pieces.KING) {
            if (m.to - m.from == 2) { b[m.from + 1] = b[m.from + 3]; b[m.from + 3] = 0 }
            if (m.from - m.to == 2) { b[m.from - 1] = b[m.from - 4]; b[m.from - 4] = 0 }
            cr = if (p > 0) cr and (Castling.WK or Castling.WQ).inv() else cr and (Castling.BK or Castling.BQ).inv()
        }
        fun touch(sq: Int) {
            when (sq) {
                0 -> cr = cr and Castling.WQ.inv()
                7 -> cr = cr and Castling.WK.inv()
                56 -> cr = cr and Castling.BQ.inv()
                63 -> cr = cr and Castling.BK.inv()
            }
        }
        touch(m.from); touch(m.to)
        // Only keep the en passant square if a capture is actually possible (FEN normalization).
        if (newEp >= 0) {
            val capturer = if (p > 0) -Pieces.PAWN else Pieces.PAWN
            val r = Squares.rank(m.to)
            val f = Squares.file(m.to)
            val adj = (f > 0 && b[Squares.of(f - 1, r)] == capturer) || (f < 7 && b[Squares.of(f + 1, r)] == capturer)
            if (!adj) newEp = -1
        }
        val reset = type == Pieces.PAWN || captured != 0
        return Position(
            b, !whiteToMove, cr, newEp,
            if (reset) 0 else halfmoveClock + 1,
            if (whiteToMove) fullmoveNumber else fullmoveNumber + 1,
        )
    }

    fun isCheckmate() = legalMoves().isEmpty() && inCheck()
    fun isStalemate() = legalMoves().isEmpty() && !inCheck()

    fun isInsufficientMaterial(): Boolean {
        val minors = ArrayList<Pair<Int, Int>>() // (piece, square)
        for (i in 0 until 64) {
            val t = Pieces.type(squares[i])
            when (t) {
                0, Pieces.KING -> {}
                Pieces.KNIGHT, Pieces.BISHOP -> minors.add(squares[i] to i)
                else -> return false
            }
        }
        if (minors.size <= 1) return true
        // Only bishops, all on the same colour squares
        if (minors.all { Pieces.type(it.first) == Pieces.BISHOP }) {
            val colors = minors.map { (Squares.file(it.second) + Squares.rank(it.second)) % 2 }.toSet()
            return colors.size == 1
        }
        return false
    }

    /** Key for repetition detection (board + side + castling + ep). */
    fun repetitionKey(): String = Fen.toFen(this).split(' ').take(4).joinToString(" ")

    fun withSideToMove(white: Boolean): Position =
        Position(squares.copyOf(), white, castling, -1, halfmoveClock, fullmoveNumber)

    override fun equals(other: Any?): Boolean =
        other is Position && other.squares.contentEquals(squares) && other.whiteToMove == whiteToMove &&
                other.castling == castling && other.epSquare == epSquare

    override fun hashCode(): Int = squares.contentHashCode() * 31 + (if (whiteToMove) 1 else 0)

    override fun toString(): String = Fen.toFen(this)

    companion object {
        val KNIGHT_D = arrayOf(intArrayOf(1, 2), intArrayOf(2, 1), intArrayOf(2, -1), intArrayOf(1, -2),
            intArrayOf(-1, -2), intArrayOf(-2, -1), intArrayOf(-2, 1), intArrayOf(-1, 2))
        val KING_D = arrayOf(intArrayOf(1, 0), intArrayOf(1, 1), intArrayOf(0, 1), intArrayOf(-1, 1),
            intArrayOf(-1, 0), intArrayOf(-1, -1), intArrayOf(0, -1), intArrayOf(1, -1))
        val ROOK_D = arrayOf(intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1))
        val BISHOP_D = arrayOf(intArrayOf(1, 1), intArrayOf(1, -1), intArrayOf(-1, 1), intArrayOf(-1, -1))

        const val START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        fun start(): Position = Fen.parse(START_FEN)
    }
}
