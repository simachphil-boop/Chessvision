package com.chessvision.core

import com.chessvision.core.engine.*
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.random.Random

/** CI tool: `PuzzleGenMain <stockfish> <out.json> <count> <minutes>` */
object PuzzleGenMain {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val out = File(args[1])
        val count = args.getOrNull(2)?.toIntOrNull() ?: 150
        val minutes = args.getOrNull(3)?.toLongOrNull() ?: 15
        val existing = if (out.exists()) PuzzleJson.parse(out.readText()) else emptyList()
        val e = UciEngine(listOf(args[0]))
        e.start(threads = Runtime.getRuntime().availableProcessors().coerceAtMost(4), hashMb = 128)
        val gen = PuzzleGenerator(e, Random(System.currentTimeMillis()))
        val all = ArrayList(existing)
        val t0 = System.currentTimeMillis()
        gen.generate(count - existing.size, minutes * 60_000, idPrefix = "g${t0 % 100000}_") { p ->
            all.add(p)
            println("puzzle ${all.size}: ${p.theme} r=${p.rating} ${p.fen} ${p.solution}")
            out.parentFile?.mkdirs(); out.writeText(PuzzleJson.write(all))
        }
        out.parentFile?.mkdirs(); out.writeText(PuzzleJson.write(all))
        println("total puzzles: ${all.size} in ${(System.currentTimeMillis() - t0) / 1000}s")
        e.close()
    }
}
