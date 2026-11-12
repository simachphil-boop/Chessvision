import java.net.HttpURLConnection
import java.net.URL

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.chessvision.ai"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.chessvision.ai"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1)
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore/chessvision-release.jks")
            storePassword = "chessvision"
            keyAlias = "chessvision"
            keyPassword = "chessvision"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "-opt-in=androidx.compose.animation.ExperimentalAnimationApi",
        )
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
        // Stockfish ships as libstockfish.so and is executed as a process: it must be extracted to disk.
        jniLibs { useLegacyPackaging = true }
    }
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Stockfish: official Android builds from github.com/official-stockfish/Stockfish/releases
// are downloaded once into src/main/jniLibs/<abi>/libstockfish.so (skipped if already there).
// ---------------------------------------------------------------------------------------------
// Asset names changed over releases (sf_17: stockfish-android-armv8.tar, sf_19: stockfish-android-arm64-universal.tar.gz).
val stockfishTargets = mapOf(
    "arm64-v8a" to listOf(
        Regex("stockfish-android-arm64-universal\\.tar(\\.gz)?"),
        Regex("stockfish-android-armv8\\.tar(\\.gz)?"),
        Regex("stockfish-android-(arm64|armv8)[\\w-]*\\.tar(\\.gz)?"),
    ),
    "armeabi-v7a" to listOf(
        Regex("stockfish-android-armv7-neon\\.tar(\\.gz)?"),
        Regex("stockfish-android-armv7[\\w-]*\\.tar(\\.gz)?"),
    ),
)

val downloadStockfish by tasks.registering {
    group = "chessvision"
    description = "Downloads official Stockfish Android binaries"
    val jniDir = layout.projectDirectory.dir("src/main/jniLibs")
    outputs.upToDateWhen { stockfishTargets.keys.all { jniDir.file("$it/libstockfish.so").asFile.length() > 1_000_000 } }
    doLast {
        val missing = stockfishTargets.filterKeys { jniDir.file("$it/libstockfish.so").asFile.length() <= 1_000_000 }
        if (missing.isEmpty()) return@doLast
        fun open(url: String): HttpURLConnection {
            val c = URL(url).openConnection() as HttpURLConnection
            c.setRequestProperty("User-Agent", "ChessVisionAI-build")
            System.getenv("GITHUB_TOKEN")?.takeIf { it.isNotBlank() && url.contains("api.github.com") }
                ?.let { c.setRequestProperty("Authorization", "Bearer $it") }
            c.instanceFollowRedirects = true
            c.connectTimeout = 30_000; c.readTimeout = 120_000
            return c
        }
        val tag = (findProperty("stockfishTag") as String?) ?: "latest"
        val api = if (tag == "latest") "https://api.github.com/repos/official-stockfish/Stockfish/releases/latest"
        else "https://api.github.com/repos/official-stockfish/Stockfish/releases/tags/$tag"
        val json = open(api).inputStream.bufferedReader().readText()
        val urls = Regex("\"browser_download_url\"\\s*:\\s*\"([^\"]+)\"").findAll(json).map { it.groupValues[1] }.toList()
        val tmp = layout.buildDirectory.dir("stockfish").get().asFile.apply { mkdirs() }
        for ((abi, names) in missing) {
            val url = names.firstNotNullOfOrNull { re -> urls.firstOrNull { re.matches(it.substringAfterLast('/')) } }
                ?: throw GradleException("Stockfish asset for $abi not found in release ($names). Assets: ${urls.joinToString()}")
            val tar = File(tmp, url.substringAfterLast('/'))
            logger.lifecycle("Downloading $url")
            var conn = open(url)
            // follow cross-host redirects manually (github → objects.githubusercontent.com)
            var hops = 0
            while (conn.responseCode in 300..399 && hops++ < 5) conn = open(conn.getHeaderField("Location"))
            conn.inputStream.use { inp -> tar.outputStream().use { inp.copyTo(it) } }
            val extractDir = File(tmp, abi).apply { deleteRecursively(); mkdirs() }
            project.copy { from(project.tarTree(if (tar.name.endsWith(".gz")) project.resources.gzip(tar) else tar)); into(extractDir) }
            extractDir.walkTopDown().filter { it.isFile }.forEach { logger.lifecycle("  in archive: ${it.relativeTo(extractDir)} (${it.length()} bytes)") }
            val bin = extractDir.walkTopDown().filter { it.isFile && it.name.startsWith("stockfish") && !it.name.contains('.') && it.length() > 1_000_000 }
                .maxByOrNull { it.length() } ?: throw GradleException("Stockfish binary not found inside ${tar.name}")
            val dest = jniDir.file("$abi/libstockfish.so").asFile
            dest.parentFile.mkdirs()
            bin.copyTo(dest, overwrite = true)
            logger.lifecycle("Stockfish for $abi: ${bin.name} (${bin.length() / 1024 / 1024} MB) -> $dest")
        }
    }
}
tasks.named("preBuild") { dependsOn(downloadStockfish) }

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.exifinterface)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlinx.coroutines.android)
}
