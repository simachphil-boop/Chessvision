package com.chessvision.core.chess

class FenException(message: String) : IllegalArgumentException(message)

object Fen {
    fun parse(fen: String): Position {
        val parts = fen.trim().split(Regex("\\s+"))
        if (parts.isEmpty() || parts[0].isEmpty()) throw FenException("Пустая FEN-строка")
        val rows = parts[0].split('/')
        if (rows.size != 8) throw FenException("В FEN должно быть 8 горизонталей, найдено ${rows.size}")
        val b = IntArray(64)
        for ((i, row) in rows.withIndex()) {
            val rank = 7 - i
            var file = 0
            for (c in row) {
                if (c.isDigit()) {
                    file += c - '0'
                } else {
                    val p = Pieces.fromChar(c)
                    if (p == 0) throw FenException("Неизвестный символ '$c' в FEN")
                    if (file > 7) throw FenException("Слишком много клеток на горизонтали ${rank + 1}")
                    b[Squares.of(file, rank)] = p
                    file++
                }
            }
            if (file != 8) throw FenException("Горизонталь ${rank + 1} содержит $file клеток вместо 8")
        }
        val white = when (parts.getOrElse(1) { "w" }) {
            "w" -> true
            "b" -> false
            else -> throw FenException("Очередь хода должна быть 'w' или 'b'")
        }
        var cr = 0
        val cs = parts.getOrElse(2) { "-" }
        if (cs != "-") for (c in cs) cr = cr or when (c) {
            'K' -> Castling.WK; 'Q' -> Castling.WQ; 'k' -> Castling.BK; 'q' -> Castling.BQ
            else -> throw FenException("Неверные права на рокировку: $cs")
        }
        cr = sanitizeCastling(b, cr)
        val epStr = parts.getOrElse(3) { "-" }
        val ep = if (epStr == "-") -1 else Squares.parse(epStr).also {
            if (it < 0) throw FenException("Неверное поле взятия на проходе: $epStr")
        }
        val half = parts.getOrElse(4) { "0" }.toIntOrNull() ?: 0
        val full = (parts.getOrElse(5) { "1" }.toIntOrNull() ?: 1).coerceAtLeast(1)
        return Position(b, white, cr, ep, half, full)
    }

    /** Removes castling rights that are impossible given king/rook placement. */
    fun sanitizeCastling(b: IntArray, cr: Int): Int {
        var r = cr
        if (b[4] != Pieces.KING) r = r and (Castling.WK or Castling.WQ).inv()
        if (b[7] != Pieces.ROOK) r = r and Castling.WK.inv()
        if (b[0] != Pieces.ROOK) r = r and Castling.WQ.inv()
        if (b[60] != -Pieces.KING) r = r and (Castling.BK or Castling.BQ).inv()
        if (b[63] != -Pieces.ROOK) r = r and Castling.BK.inv()
        if (b[56] != -Pieces.ROOK) r = r and Castling.BQ.inv()
        return r
    }

    fun boardPart(b: IntArray): String {
        val sb = StringBuilder()
        for (rank in 7 downTo 0) {
            var empty = 0
            for (file in 0..7) {
                val p = b[Squares.of(file, rank)]
                if (p == 0) empty++ else {
                    if (empty > 0) { sb.append(empty); empty = 0 }
                    sb.append(Pieces.toChar(p))
                }
            }
            if (empty > 0) sb.append(empty)
            if (rank > 0) sb.append('/')
        }
        return sb.toString()
    }

    fun toFen(p: Position): String {
        val b = p.board()
        val cr = buildString {
            if (p.castling and Castling.WK != 0) append('K')
            if (p.castling and Castling.WQ != 0) append('Q')
            if (p.castling and Castling.BK != 0) append('k')
            if (p.castling and Castling.BQ != 0) append('q')
        }.ifEmpty { "-" }
        val ep = if (p.epSquare >= 0) Squares.name(p.epSquare) else "-"
        return "${boardPart(b)} ${if (p.whiteToMove) "w" else "b"} $cr $ep ${p.halfmoveClock} ${p.fullmoveNumber}"
    }

    /** Builds a position from a raw board, inferring castling rights from piece placement. */
    fun fromBoard(b: IntArray, whiteToMove: Boolean): Position {
        val cr = sanitizeCastling(b, Castling.WK or Castling.WQ or Castling.BK or Castling.BQ)
        return Position(b.copyOf(), whiteToMove, cr, -1, 0, 1)
    }
}

/** Validation of a position's legality (used after screenshot recognition / board editing). */
object PositionValidator {
    data class Issue(val message: String, val fatal: Boolean)

    fun validate(b: IntArray, whiteToMove: Boolean): List<Issue> {
        val issues = ArrayList<Issue>()
        val count = IntArray(13)
        for (p in b) count[p + 6]++
        fun c(p: Int) = count[p + 6]
        val wk = c(Pieces.KING); val bk = c(-Pieces.KING)
        if (wk != 1) issues.add(Issue(if (wk == 0) "Нет белого короля" else "Белых королей: $wk (должен быть 1)", true))
        if (bk != 1) issues.add(Issue(if (bk == 0) "Нет чёрного короля" else "Чёрных королей: $bk (должен быть 1)", true))
        val wp = c(Pieces.PAWN); val bp = c(-Pieces.PAWN)
        if (wp > 8) issues.add(Issue("Белых пешек $wp (максимум 8)", true))
        if (bp > 8) issues.add(Issue("Чёрных пешек $bp (максимум 8)", true))
        val wTotal = (1..6).sumOf { c(it) }
        val bTotal = (1..6).sumOf { c(-it) }
        if (wTotal > 16) issues.add(Issue("У белых $wTotal фигур (максимум 16)", true))
        if (bTotal > 16) issues.add(Issue("У чёрных $bTotal фигур (максимум 16)", true))
        for (f in 0..7) {
            if (Pieces.type(b[f]) == Pieces.PAWN || Pieces.type(b[56 + f]) == Pieces.PAWN) {
                issues.add(Issue("Пешка на крайней горизонтали (${Squares.name(if (Pieces.type(b[f]) == Pieces.PAWN) f else 56 + f)})", true))
                break
            }
        }
        // Promoted pieces beyond missing pawns
        fun extra(white: Boolean): Int {
            val s = if (white) 1 else -1
            return maxOf(0, c(s * Pieces.QUEEN) - 1) + maxOf(0, c(s * Pieces.ROOK) - 2) +
                    maxOf(0, c(s * Pieces.BISHOP) - 2) + maxOf(0, c(s * Pieces.KNIGHT) - 2)
        }
        if (extra(true) > 8 - wp) issues.add(Issue("У белых слишком много превращённых фигур", true))
        if (extra(false) > 8 - bp) issues.add(Issue("У чёрных слишком много превращённых фигур", true))
        if (wk == 1 && bk == 1) {
            val pos = Position(b.copyOf(), whiteToMove, 0, -1, 0, 1)
            val wks = pos.kingSquare(true); val bks = pos.kingSquare(false)
            if (kotlin.math.abs(Squares.file(wks) - Squares.file(bks)) <= 1 && kotlin.math.abs(Squares.rank(wks) - Squares.rank(bks)) <= 1)
                issues.add(Issue("Короли стоят вплотную друг к другу", true))
            val oppKing = pos.kingSquare(!whiteToMove)
            if (pos.isAttacked(oppKing, whiteToMove))
                issues.add(Issue("${if (whiteToMove) "Чёрный" else "Белый"} король под шахом, но сейчас ход ${if (whiteToMove) "белых" else "чёрных"} — проверь очередь хода", true))
        }
        return issues
    }

    fun isValid(b: IntArray, whiteToMove: Boolean) = validate(b, whiteToMove).none { it.fatal }
}
