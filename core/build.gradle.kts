import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
}

/** Unit + integration tests: `./gradlew :core:selfTest -PstockfishPath=/path/to/stockfish` */
tasks.register<JavaExec>("selfTest") {
    group = "verification"
    dependsOn("testClasses")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.chessvision.core.SelfTest")
    workingDir = rootDir
    systemProperty("java.awt.headless", "true")
    (findProperty("stockfishPath") as String?)?.let { environment("STOCKFISH_PATH", it) }
}

/** Generates verified puzzles with Stockfish: `./gradlew :core:generatePuzzles -PstockfishPath=... -PpuzzleCount=150 -PpuzzleMinutes=15` */
tasks.register<JavaExec>("generatePuzzles") {
    group = "chessvision"
    dependsOn("testClasses")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.chessvision.core.PuzzleGenMain")
    workingDir = rootDir
    args(
        (findProperty("stockfishPath") as String?) ?: "stockfish",
        rootProject.file("app/src/main/assets/puzzles.json").absolutePath,
        (findProperty("puzzleCount") as String?) ?: "150",
        (findProperty("puzzleMinutes") as String?) ?: "15",
    )
}
