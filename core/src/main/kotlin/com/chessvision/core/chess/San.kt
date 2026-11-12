package com.chessvision.core.chess

object San {
    private val PIECE_LETTER = charArrayOf(' ', ' ', 'N', 'B', 'R', 'Q', 'K')

    fun toSan(pos: Position, m: Move): String {
        val p = pos[m.from]
        val type = Pieces.type(p)
        val sb = StringBuilder()
        if (type == Pieces.KING && kotlin.math.abs(m.to - m.from) == 2) {
            sb.append(if (m.to > m.from) "O-O" else "O-O-O")
        } else {
            val capture = pos.isCapture(m)
            if (type == Pieces.PAWN) {
                if (capture) sb.append('a' + Squares.file(m.from)).append('x')
                sb.append(Squares.name(m.to))
                if (m.promotion != 0) sb.append('=').append(PIECE_LETTER[m.promotion])
            } else {
                sb.append(PIECE_LETTER[type])
                val others = pos.legalMoves().filter {
                    it.to == m.to && it.from != m.from && pos[it.from] == p
                }
                if (others.isNotEmpty()) {
                    val sameFile = others.any { Squares.file(it.from) == Squares.file(m.from) }
                    val sameRank = others.any { Squares.rank(it.from) == Squares.rank(m.from) }
                    if (!sameFile) sb.append('a' + Squares.file(m.from))
                    else if (!sameRank) sb.append('1' + Squares.rank(m.from))
                    else sb.append(Squares.name(m.from))
                }
                if (capture) sb.append('x')
                sb.append(Squares.name(m.to))
            }
        }
        val next = pos.playUnchecked(m)
        if (next.inCheck()) sb.append(if (next.legalMoves().isEmpty()) '#' else '+')
        return sb.toString()
    }

    /** Parses SAN (tolerant to annotations like !, ?, +, # and "0-0"). */
    fun parse(pos: Position, sanIn: String): Move? {
        var san = sanIn.trim().replace("0-0-0", "O-O-O").replace("0-0", "O-O")
        san = san.trimEnd('+', '#', '!', '?')
        if (san.isEmpty()) return null
        val legal = pos.legalMoves()
        if (san == "O-O" || san == "O-O-O") {
            val k = pos.kingSquare(pos.whiteToMove)
            val to = if (san == "O-O") k + 2 else k - 2
            return legal.firstOrNull { it.from == k && it.to == to }
        }
        // Accept UCI as a fallback
        if (Regex("^[a-h][1-8][a-h][1-8][qrbn]?$").matches(san)) {
            Move.fromUci(san)?.let { mv -> legal.firstOrNull { it == mv }?.let { return it } }
        }
        var promo = 0
        val eq = san.indexOf('=')
        if (eq >= 0 && eq + 1 < san.length) {
            promo = Pieces.type(Pieces.fromChar(san[eq + 1].lowercaseChar()))
            san = san.substring(0, eq)
        } else if (san.length >= 3 && san.last() in "QRBNqrbn" && san[san.length - 2].isDigit() && san[0].isLowerCase()) {
            promo = Pieces.type(Pieces.fromChar(san.last().lowercaseChar()))
            san = san.dropLast(1)
        }
        val type = when (san[0]) {
            'N' -> Pieces.KNIGHT; 'B' -> Pieces.BISHOP; 'R' -> Pieces.ROOK; 'Q' -> Pieces.QUEEN; 'K' -> Pieces.KING
            else -> Pieces.PAWN
        }
        var body = if (type == Pieces.PAWN) san else san.substring(1)
        body = body.replace("x", "").replace("-", "").replace(":", "")
        if (body.length < 2) return null
        val to = Squares.parse(body.takeLast(2))
        if (to < 0) return null
        val dis = body.dropLast(2)
        var fromFile = -1; var fromRank = -1
        for (c in dis) {
            if (c in 'a'..'h') fromFile = c - 'a'
            else if (c in '1'..'8') fromRank = c - '1'
        }
        val cands = legal.filter {
            it.to == to && Pieces.type(pos[it.from]) == type &&
                    (fromFile < 0 || Squares.file(it.from) == fromFile) &&
                    (fromRank < 0 || Squares.rank(it.from) == fromRank) &&
                    (type != Pieces.PAWN || it.promotion == promo || (promo == 0 && it.promotion == Pieces.QUEEN))
        }
        if (cands.size == 1) return cands[0]
        return cands.firstOrNull { it.promotion == promo }
    }

    /** Converts a UCI principal variation to SAN with move numbers. */
    fun pvToSan(start: Position, uciMoves: List<String>, maxMoves: Int = 12): String {
        val sb = StringBuilder()
        var pos = start
        for ((i, u) in uciMoves.withIndex()) {
            if (i >= maxMoves) break
            val m = Move.fromUci(u) ?: break
            if (!pos.isLegal(m)) break
            if (pos.whiteToMove) sb.append(pos.fullmoveNumber).append(". ")
            else if (i == 0) sb.append(pos.fullmoveNumber).append("… ")
            sb.append(toSan(pos, m)).append(' ')
            pos = pos.playUnchecked(m)
        }
        return sb.toString().trim()
    }
}
