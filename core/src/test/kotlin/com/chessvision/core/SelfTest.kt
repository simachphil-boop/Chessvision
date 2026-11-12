package com.chessvision.core

import kotlin.system.exitProcess

/**
 * Self-test entry point. Run via `./gradlew :core:selfTest`.
 * If the STOCKFISH_PATH env variable points to a Stockfish binary, engine integration tests run too.
 */
object SelfTest {
    @JvmStatic
    fun main(args: Array<String>) {
        val t0 = System.currentTimeMillis()
        ChessTests.run()
        println("chess: passed=${T.passed} failed=${T.failed}")
        runCatching { VisionTests.run() }.onFailure { T.failed++; println("FAIL: vision crashed: $it"); it.printStackTrace() }
        println("vision: passed=${T.passed} failed=${T.failed}")
        val sf = System.getenv("STOCKFISH_PATH")
        if (!sf.isNullOrBlank()) {
            runCatching { EngineTests.run(sf) }.onFailure { T.failed++; println("FAIL: engine crashed: $it"); it.printStackTrace() }
            println("engine: passed=${T.passed} failed=${T.failed}")
        } else println("engine: skipped (STOCKFISH_PATH not set)")
        println("TOTAL passed=${T.passed} failed=${T.failed} in ${System.currentTimeMillis() - t0} ms")
        exitProcess(if (T.failed == 0) 0 else 1)
    }
}
