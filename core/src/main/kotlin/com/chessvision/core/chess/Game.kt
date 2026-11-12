package com.chessvision.core.chess

enum class GameResult(val pgn: String) {
    WHITE_WINS("1-0"), BLACK_WINS("0-1"), DRAW("1/2-1/2"), ONGOING("*");

    companion object {
        fun fromPgn(s: String?) = entries.firstOrNull { it.pgn == s?.trim() } ?: ONGOING
    }
}

enum class TerminationReason(val ru: String) {
    CHECKMATE("мат"), STALEMATE("пат"), FIFTY_MOVES("правило 50 ходов"),
    REPETITION("троекратное повторение"), INSUFFICIENT("недостаточно материала"),
    RESIGNATION("сдача"), NONE("")
}

/** A linear game: starting position plus a list of moves; supports navigation and truncation. */
class Game(val startFen: String = Position.START_FEN) {
    val headers = LinkedHashMap<String, String>()
    private val _moves = ArrayList<Move>()
    private val _positions = arrayListOf(Fen.parse(startFen))
    private val _sans = ArrayList<String>()
    var resultOverride: GameResult? = null

    val moves: List<Move> get() = _moves
    val positions: List<Position> get() = _positions
    val sans: List<String> get() = _sans
    val current: Position get() = _positions.last()
    val size: Int get() = _moves.size

    fun play(m: Move): Boolean {
        val pos = current
        if (!pos.isLegal(m)) return false
        _sans.add(San.toSan(pos, m))
        _moves.add(m)
        _positions.add(pos.playUnchecked(m))
        return true
    }

    fun playSan(san: String): Boolean {
        val m = San.parse(current, san) ?: return false
        return play(m)
    }

    fun playUci(uci: String): Boolean {
        val m = Move.fromUci(uci) ?: return false
        return play(current.findMove(m.from, m.to, m.promotion) ?: return false)
    }

    fun undo(): Boolean {
        if (_moves.isEmpty()) return false
        _moves.removeAt(_moves.lastIndex)
        _sans.removeAt(_sans.lastIndex)
        _positions.removeAt(_positions.lastIndex)
        resultOverride = null
        return true
    }

    fun truncate(ply: Int) {
        while (_moves.size > ply) undo()
    }

    fun copy(): Game {
        val g = Game(startFen)
        g.headers.putAll(headers)
        for (m in _moves) g.play(m)
        g.resultOverride = resultOverride
        return g
    }

    fun termination(): TerminationReason {
        val p = current
        if (p.legalMoves().isEmpty()) return if (p.inCheck()) TerminationReason.CHECKMATE else TerminationReason.STALEMATE
        if (p.isInsufficientMaterial()) return TerminationReason.INSUFFICIENT
        if (p.halfmoveClock >= 100) return TerminationReason.FIFTY_MOVES
        val key = p.repetitionKey()
        if (_positions.count { it.repetitionKey() == key } >= 3) return TerminationReason.REPETITION
        return TerminationReason.NONE
    }

    fun result(): GameResult {
        resultOverride?.let { return it }
        return when (termination()) {
            TerminationReason.CHECKMATE -> if (current.whiteToMove) GameResult.BLACK_WINS else GameResult.WHITE_WINS
            TerminationReason.NONE, TerminationReason.RESIGNATION -> GameResult.ONGOING
            else -> GameResult.DRAW
        }
    }

    fun isOver() = result() != GameResult.ONGOING

    /** Movetext like "1. e4 e5 2. Nf3". */
    fun movetext(): String {
        val sb = StringBuilder()
        val start = _positions[0]
        var moveNo = start.fullmoveNumber
        var white = start.whiteToMove
        for ((i, s) in _sans.withIndex()) {
            if (white) sb.append("$moveNo. ") else if (i == 0) sb.append("$moveNo... ")
            sb.append(s).append(' ')
            if (!white) moveNo++
            white = !white
        }
        return sb.toString().trim()
    }

    fun toPgn(): String = Pgn.write(this)
}

object Pgn {
    class PgnException(msg: String) : Exception(msg)

    fun write(g: Game): String {
        val h = LinkedHashMap<String, String>()
        h["Event"] = g.headers["Event"] ?: "ChessVision AI"
        h["Site"] = g.headers["Site"] ?: "?"
        h["Date"] = g.headers["Date"] ?: "????.??.??"
        h["White"] = g.headers["White"] ?: "?"
        h["Black"] = g.headers["Black"] ?: "?"
        h["Result"] = g.result().pgn
        for ((k, v) in g.headers) if (k !in h) h[k] = v
        if (g.startFen != Position.START_FEN) { h["SetUp"] = "1"; h["FEN"] = g.startFen }
        val sb = StringBuilder()
        for ((k, v) in h) sb.append("[$k \"${v.replace("\"", "'")}\"]\n")
        sb.append('\n')
        // wrap at ~80 chars
        val words = (g.movetext() + " " + g.result().pgn).trim().split(' ')
        var line = 0
        for (w in words) {
            if (line + w.length + 1 > 80) { sb.append('\n'); line = 0 }
            if (line > 0) { sb.append(' '); line++ }
            sb.append(w); line += w.length
        }
        sb.append('\n')
        return sb.toString()
    }

    /** Splits a multi-game PGN file into individual game texts. */
    fun splitGames(text: String): List<String> {
        val games = ArrayList<String>()
        val cur = StringBuilder()
        var inMoves = false
        for (raw in text.replace("\r", "").lines()) {
            val line = raw.trim()
            if (line.startsWith("[") && inMoves) {
                if (cur.isNotBlank()) games.add(cur.toString())
                cur.clear(); inMoves = false
            }
            if (!line.startsWith("[") && line.isNotEmpty()) inMoves = true
            cur.append(raw).append('\n')
        }
        if (cur.isNotBlank()) games.add(cur.toString())
        return games
    }

    fun parse(text: String): Game {
        val headers = LinkedHashMap<String, String>()
        val headerRe = Regex("""\[\s*(\w+)\s+"((?:[^"\\]|\\.)*)"\s*]""")
        val body = StringBuilder()
        for (line in text.replace("\r", "").lines()) {
            val t = line.trim()
            if (t.startsWith("[")) {
                headerRe.find(t)?.let { headers[it.groupValues[1]] = it.groupValues[2] }
            } else if (!t.startsWith("%")) body.append(line).append(' ')
        }
        val fen = headers["FEN"]?.takeIf { it.isNotBlank() } ?: Position.START_FEN
        val game = try { Game(fen) } catch (e: FenException) { throw PgnException("Неверная FEN в заголовке: ${e.message}") }
        game.headers.putAll(headers)
        // Strip comments, variations, NAGs
        val s = body.toString()
        val clean = StringBuilder()
        var depth = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '{' -> { val end = s.indexOf('}', i); i = if (end < 0) s.length else end }
                c == ';' -> { val end = s.indexOf('\n', i); i = if (end < 0) s.length else end }
                c == '(' -> depth++
                c == ')' -> depth = maxOf(0, depth - 1)
                depth == 0 -> clean.append(c)
            }
            i++
        }
        val tokens = clean.toString().split(Regex("\\s+")).filter { it.isNotEmpty() }
        for (tok0 in tokens) {
            var tok = tok0
            if (tok.startsWith("$")) continue
            if (tok in setOf("1-0", "0-1", "1/2-1/2", "*", "½-½")) {
                game.resultOverride = GameResult.fromPgn(if (tok == "½-½") "1/2-1/2" else tok).takeIf { it != GameResult.ONGOING }
                continue
            }
            tok = tok.replace(Regex("^\\d+\\.+"), "")
            if (tok.isEmpty() || tok.all { it == '.' }) continue
            if (!game.playSan(tok)) {
                throw PgnException("Не удалось разобрать ход '$tok0' (ход ${game.current.fullmoveNumber}, ${if (game.current.whiteToMove) "белые" else "чёрные"})")
            }
        }
        if (game.resultOverride == null) GameResult.fromPgn(headers["Result"]).takeIf { it != GameResult.ONGOING }?.let {
            if (game.result() == GameResult.ONGOING) game.resultOverride = it
        }
        return game
    }
}
