package com.chessvision.core.engine

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.*
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.exp

class EngineException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Engine score. Stored from WHITE's point of view. */
data class Score(val cp: Int? = null, val mate: Int? = null) {
    val isMate get() = mate != null

    /** Centipawns, mates mapped to ±(100000 - distance). */
    fun sortKey(): Int = when {
        mate != null -> if (mate > 0) 100_000 - mate else if (mate < 0) -100_000 - mate else -100_000
        else -> cp ?: 0
    }

    fun negate() = Score(cp?.let { -it }, mate?.let { -it })

    /** Text from white's POV: "+0.35", "−1.20", "M3", "−M2". */
    fun display(): String = when {
        mate != null -> if (mate == 0) "#" else if (mate > 0) "M$mate" else "−M${-mate}"
        cp != null && abs(cp) >= MATED -> if (cp > 0) "1-0 #" else "0-1 #"
        else -> {
            val v = (cp ?: 0) / 100.0
            (if (v > 0) "+" else if (v < 0) "−" else "") + String.format(java.util.Locale.US, "%.2f", abs(v))
        }
    }

    /** Win probability for WHITE in percent (lichess model). */
    fun whiteWinPercent(): Double = when {
        mate != null -> if (mate > 0) 100.0 else if (mate < 0) 0.0 else 50.0
        else -> winPercentFromCp(cp ?: 0)
    }

    companion object {
        /** cp value used for an already-checkmated position. */
        const val MATED = 100_000

        fun winPercentFromCp(cp: Int): Double {
            if (cp >= MATED) return 100.0
            if (cp <= -MATED) return 0.0
            val c = cp.coerceIn(-1000, 1000)
            return 50 + 50 * (2 / (1 + exp(-0.00368208 * c)) - 1)
        }
    }
}

data class PvLine(
    val multipv: Int,
    val depth: Int,
    val selDepth: Int,
    /** White point of view. */
    val score: Score,
    val bound: String?,
    val pv: List<String>,
    val nodes: Long,
    val nps: Long,
    val timeMs: Long,
)

data class AnalysisSnapshot(val depth: Int, val lines: List<PvLine>, val nodes: Long, val nps: Long)

data class SearchResult(val bestMove: String?, val ponder: String?, val lines: List<PvLine>) {
    val best: PvLine? get() = lines.minByOrNull { it.multipv }
}

data class SearchLimit(
    val depth: Int? = null,
    val movetimeMs: Long? = null,
    val nodes: Long? = null,
    val infinite: Boolean = false,
) {
    fun toGo(): String = buildString {
        append("go")
        if (infinite) { append(" infinite"); return@buildString }
        depth?.let { append(" depth $it") }
        movetimeMs?.let { append(" movetime $it") }
        nodes?.let { append(" nodes $it") }
        if (depth == null && movetimeMs == null && nodes == null) append(" depth 18")
    }
}

/**
 * Playing/analysis strength.
 * [elo] != null → UCI_LimitStrength with UCI_Elo (Stockfish supports 1320..3190).
 * [skill] 0..20 → "Skill Level" (20 = full strength).
 */
data class EngineStrength(val elo: Int? = null, val skill: Int = 20)

object UciParser {
    /** Parses an "info" line. [whiteToMove] is needed to convert the score to white POV. */
    fun parseInfo(line: String, whiteToMove: Boolean): PvLine? {
        if (!line.startsWith("info ")) return null
        val t = line.split(' ').filter { it.isNotEmpty() }
        var depth = -1; var sel = 0; var multipv = 1; var cp: Int? = null; var mate: Int? = null
        var bound: String? = null; var nodes = 0L; var nps = 0L; var time = 0L
        var pv: List<String> = emptyList()
        var i = 1
        while (i < t.size) {
            when (t[i]) {
                "depth" -> { depth = t.getOrNull(i + 1)?.toIntOrNull() ?: -1; i++ }
                "seldepth" -> { sel = t.getOrNull(i + 1)?.toIntOrNull() ?: 0; i++ }
                "multipv" -> { multipv = t.getOrNull(i + 1)?.toIntOrNull() ?: 1; i++ }
                "score" -> {
                    when (t.getOrNull(i + 1)) {
                        "cp" -> cp = t.getOrNull(i + 2)?.toIntOrNull()
                        "mate" -> mate = t.getOrNull(i + 2)?.toIntOrNull()
                    }
                    i += 2
                    if (t.getOrNull(i + 1) == "lowerbound" || t.getOrNull(i + 1) == "upperbound") { bound = t[i + 1]; i++ }
                }
                "nodes" -> { nodes = t.getOrNull(i + 1)?.toLongOrNull() ?: 0; i++ }
                "nps" -> { nps = t.getOrNull(i + 1)?.toLongOrNull() ?: 0; i++ }
                "time" -> { time = t.getOrNull(i + 1)?.toLongOrNull() ?: 0; i++ }
                "pv" -> { pv = t.subList(i + 1, t.size).toList(); i = t.size }
                "string", "currmove", "currmovenumber" -> if (t[i] == "string") return null else i++
            }
            i++
        }
        if (depth < 0 || (cp == null && mate == null) || pv.isEmpty()) return null
        var s = Score(cp, mate)
        if (!whiteToMove) s = s.negate()
        return PvLine(multipv, depth, sel, s, bound, pv, nodes, nps, time)
    }
}

/**
 * Stockfish (or any UCI engine) running as a child process.
 * All searches are serialized; cancelling the calling coroutine sends "stop".
 */
class UciEngine(private val command: List<String>, private val workDir: File? = null) : Closeable {
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var lines = Channel<String>(Channel.UNLIMITED)
    private val mutex = Mutex()
    @Volatile private var alive = false

    var engineName: String = "UCI engine"; private set
    val options = LinkedHashMap<String, String>()
    private var currentMultiPv = 1
    private var currentStrength: EngineStrength? = null

    val isAlive: Boolean get() = alive && process?.isAlive == true

    suspend fun start(threads: Int = 1, hashMb: Int = 64) = withContext(Dispatchers.IO) {
        if (isAlive) return@withContext
        val pb = ProcessBuilder(command).redirectErrorStream(true)
        workDir?.let { pb.directory(it) }
        val p = try { pb.start() } catch (e: IOException) {
            throw EngineException("Не удалось запустить движок: ${e.message}", e)
        }
        process = p
        writer = BufferedWriter(OutputStreamWriter(p.outputStream))
        lines = Channel(Channel.UNLIMITED)
        alive = true
        val ch = lines
        Thread({
            try {
                BufferedReader(InputStreamReader(p.inputStream)).use { r ->
                    while (true) {
                        val l = r.readLine() ?: break
                        ch.trySend(l)
                    }
                }
            } catch (_: IOException) {
            } finally {
                alive = false
                ch.close()
            }
        }, "uci-reader").apply { isDaemon = true }.start()

        send("uci")
        withTimeoutOrNull(15_000) {
            while (true) {
                val l = next()
                when {
                    l.startsWith("id name ") -> engineName = l.removePrefix("id name ").trim()
                    l.startsWith("option name ") -> {
                        val name = l.removePrefix("option name ").substringBefore(" type ").trim()
                        options[name] = l.substringAfter(" type ", "")
                    }
                    l == "uciok" -> break
                }
            }
        } ?: run { close(); throw EngineException("Движок не ответил на команду uci") }
        setOption("Threads", threads.toString())
        setOption("Hash", hashMb.toString())
        currentMultiPv = 1
        currentStrength = null
        isReady()
    }

    private suspend fun next(): String = try {
        lines.receive()
    } catch (e: kotlinx.coroutines.channels.ClosedReceiveChannelException) {
        alive = false
        throw EngineException("Движок неожиданно завершился", e)
    }

    @Synchronized
    private fun send(cmd: String) {
        val w = writer ?: throw EngineException("Движок не запущен")
        try { w.write(cmd); w.write("\n"); w.flush() } catch (e: IOException) {
            alive = false
            throw EngineException("Связь с движком потеряна", e)
        }
    }

    fun setOption(name: String, value: String) {
        if (options.isNotEmpty() && options.keys.none { it.equals(name, true) }) return
        send("setoption name $name value $value")
    }

    private suspend fun isReady() {
        send("isready")
        withTimeoutOrNull(20_000) { while (next() != "readyok") { /* skip */ } }
            ?: throw EngineException("Движок не ответил readyok")
    }

    suspend fun newGame() = mutex.withLock {
        ensureAlive(); send("ucinewgame"); isReady()
    }

    private fun ensureAlive() { if (!isAlive) throw EngineException("Движок не запущен") }

    private fun applyStrength(s: EngineStrength) {
        if (s == currentStrength) return
        if (s.elo != null) {
            setOption("UCI_LimitStrength", "true")
            setOption("UCI_Elo", s.elo.coerceIn(1320, 3190).toString())
            setOption("Skill Level", "20")
        } else {
            setOption("UCI_LimitStrength", "false")
            setOption("Skill Level", s.skill.coerceIn(0, 20).toString())
        }
        currentStrength = s
    }

    /**
     * Runs a search. [onUpdate] receives snapshots as depth increases (throttled by caller if needed).
     * Cancelling the coroutine stops the search promptly.
     */
    suspend fun search(
        fen: String,
        moves: List<String> = emptyList(),
        limit: SearchLimit,
        multiPv: Int = 1,
        strength: EngineStrength = EngineStrength(),
        onUpdate: ((AnalysisSnapshot) -> Unit)? = null,
    ): SearchResult = mutex.withLock {
        ensureAlive()
        val whiteToMove = run {
            val stmWhite = fen.split(' ').getOrElse(1) { "w" } == "w"
            if (moves.size % 2 == 0) stmWhite else !stmWhite
        }
        withContext(Dispatchers.IO) {
            if (multiPv != currentMultiPv) { setOption("MultiPV", multiPv.toString()); currentMultiPv = multiPv }
            applyStrength(strength)
            isReady()
            send("position fen $fen" + if (moves.isNotEmpty()) " moves ${moves.joinToString(" ")}" else "")
            send(limit.toGo())
            val latest = HashMap<Int, PvLine>()
            var lastDepth = 0
            var finished = false
            try {
                while (true) {
                    val l = next()
                    if (l.startsWith("bestmove")) {
                        finished = true
                        val p = l.split(' ')
                        val best = p.getOrNull(1)?.takeIf { it != "(none)" }
                        val ponder = if (p.getOrNull(2) == "ponder") p.getOrNull(3) else null
                        return@withContext SearchResult(best, ponder, latest.values.sortedBy { it.multipv })
                    }
                    val info = UciParser.parseInfo(l, whiteToMove) ?: continue
                    if (info.bound != null && latest.containsKey(info.multipv)) continue
                    if (info.multipv > multiPv) continue
                    latest[info.multipv] = info
                    // Emit when a full set at a new depth is available (or on multipv 1 updates)
                    if (onUpdate != null && (info.multipv == multiPv || latest.size == 1 || info.depth > lastDepth)) {
                        lastDepth = maxOf(lastDepth, info.depth)
                        val sorted = latest.values.sortedBy { it.multipv }
                        onUpdate(AnalysisSnapshot(info.depth, sorted, info.nodes, info.nps))
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                error("unreachable")
            } finally {
                if (!finished) withContext(NonCancellable) {
                    runCatching {
                        send("stop")
                        withTimeoutOrNull(5_000) { while (!next().startsWith("bestmove")) { } }
                    }
                }
            }
        }
    }

    override fun close() {
        alive = false
        runCatching { writer?.apply { write("quit\n"); flush() } }
        val p = process ?: return
        runCatching { if (!p.waitFor(500, TimeUnit.MILLISECONDS)) p.destroyForcibly() }
        runCatching { lines.close() }
        process = null
        writer = null
    }
}
