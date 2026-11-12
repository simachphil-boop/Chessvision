package com.chessvision.ai.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.chessvision.ai.data.AppDatabase
import com.chessvision.ai.data.LearnedSampleEntity
import com.chessvision.ai.pieces.PieceSetAssets
import com.chessvision.ai.pieces.toRgbImage
import com.chessvision.core.vision.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class RecognitionException(message: String) : Exception(message)

/**
 * Screenshot → board pipeline (pure Kotlin, fully offline):
 * board detection (checkerboard score + grid-line alignment) → 64 cells → silhouettes →
 * nearest-neighbour classification against rendered reference piece sets plus samples the
 * user taught by correcting mistakes or calibrating on a start position.
 */
class RecognitionService(private val context: Context, private val db: AppDatabase) {
    private val lock = Mutex()
    private var references: List<Sample>? = null
    private var classifier: PieceClassifier? = null

    suspend fun loadBitmap(uri: Uri, maxDim: Int = 2400): Bitmap = withContext(Dispatchers.IO) {
        val cr = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: throw RecognitionException("Не удалось открыть изображение")
        var sample = 1
        while (bounds.outWidth / sample > maxDim * 2 || bounds.outHeight / sample > maxDim * 2) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        var bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw RecognitionException("Файл не является изображением")
        val rotation = runCatching {
            cr.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
        }.getOrDefault(0)
        if (rotation != 0) {
            val m = Matrix().apply { postRotate(rotation.toFloat()) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        val big = maxOf(bmp.width, bmp.height)
        if (big > maxDim) {
            val k = maxDim.toFloat() / big
            bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true)
        }
        if (bmp.config != Bitmap.Config.ARGB_8888) bmp = bmp.copy(Bitmap.Config.ARGB_8888, false)
        bmp
    }

    private suspend fun classifier(): PieceClassifier = lock.withLock {
        classifier?.let { return@withLock it }
        val refs = references ?: withContext(Dispatchers.Default) {
            PieceSetAssets.SETS.flatMap { (name, _) ->
                val set = PieceSetAssets.load(context, name)
                ReferenceBuilder.build(name, set::renderRgb, ReferenceBuilder.THEME_SQUARES)
            }
        }.also { references = it }
        val learned = db.samples().all().mapNotNull { e ->
            val f = e.floats()
            if (f.size == CellAnalyzer.FEATURE_SIZE) Sample(e.type, f, e.source, if (e.source == "calib") 1.6f else 1.3f) else null
        }
        PieceClassifier(refs + learned).also { classifier = it }
    }

    suspend fun detect(img: RgbImage): DetectResult = withContext(Dispatchers.Default) {
        BoardDetector.detect(img) ?: throw RecognitionException(
            "Шахматная доска не найдена. Нужен скриншот 2D-доски целиком; можно вручную задать рамку доски."
        )
    }

    suspend fun recognize(img: RgbImage, detect: DetectResult, flipped: Boolean?): Recognition {
        val clf = classifier()
        return withContext(Dispatchers.Default) { BoardRecognizer.recognize(img, detect, clf, flipped) }
    }

    /** Re-run with a user-adjusted rectangle. */
    suspend fun recognizeManual(img: RgbImage, rect: BoardRect, flipped: Boolean?): Recognition {
        val det = withContext(Dispatchers.Default) {
            BoardDetector.colorsFor(img, rect) ?: DetectResult(rect, 0f, 0xFFF0D9B5.toInt(), 0xFFB58863.toInt())
        }
        return recognize(img, det, flipped)
    }

    /** Stores samples (from corrections or calibration) and refreshes the classifier. */
    suspend fun learn(samples: List<Sample>) {
        if (samples.isEmpty()) return
        db.samples().insertAll(samples.map { LearnedSampleEntity(type = it.type, features = LearnedSampleEntity.bytes(it.features), source = it.source) })
        lock.withLock { classifier = null }
    }

    suspend fun forget() {
        db.samples().clear()
        lock.withLock { classifier = null }
    }

    fun toRgb(bmp: Bitmap): RgbImage = bmp.toRgbImage()
}
