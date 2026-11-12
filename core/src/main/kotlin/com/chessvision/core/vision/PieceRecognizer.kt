package com.chessvision.core.vision

import com.chessvision.core.chess.*
import kotlin.math.*

/** Labeled silhouette feature vector. type = 1..6 (pawn..king). */
class Sample(val type: Int, val features: FloatArray, val source: String, val weight: Float = 1f)

/** Result for one cell, display coordinates (row 0 = top of the screenshot). */
data class CellResult(
    val row: Int,
    val col: Int,
    val occupied: Boolean,
    val type: Int,
    val white: Boolean,
    /** 0..1 */
    val confidence: Float,
    val features: FloatArray?,
    val pieceLum: Float,
    /** Ranked alternatives (type to distance). */
    val alternatives: List<Pair<Int, Float>> = emptyList(),
) {
    val piece: Int get() = if (!occupied) 0 else if (white) type else -type
}

data class Recognition(
    val rect: BoardRect,
    val cells: List<CellResult>,
    /** true = board shown from black's side (a1 at top-right). */
    val flipped: Boolean,
    val lightColor: Int,
    val darkColor: Int,
) {
    fun squareOf(row: Int, col: Int): Int = if (!flipped) Squares.of(col, 7 - row) else Squares.of(7 - col, row)

    fun board(): IntArray {
        val b = IntArray(64)
        for (c in cells) b[squareOf(c.row, c.col)] = c.piece
        return b
    }

    fun cellAt(row: Int, col: Int) = cells[row * 8 + col]
    val averageConfidence: Float get() = cells.filter { it.occupied }.map { it.confidence }.average().toFloat().takeIf { !it.isNaN() } ?: 1f
}

object CellAnalyzer {
    const val N = 40
    /** Fraction of the cell trimmed on each side (protects against slight misalignment). */
    const val INSET = 0.035f

    fun crop(img: RgbImage, x: Float, y: Float, cell: Float): RgbImage {
        val i = cell * INSET
        return img.resample(x + i, y + i, cell - 2 * i, cell - 2 * i, N, N)
    }

    /** Pixels of the cell's corner zones excluded from analysis (coordinate labels live there). */
    private fun inCornerZone(x: Int, y: Int): Boolean {
        val z = (N * 0.17f).toInt()
        val nearX = x < z || x >= N - z
        val nearY = y < z || y >= N - z
        return nearX && nearY
    }

    class Silhouette(val mask: BooleanArray, val area: Int, val x0: Int, val y0: Int, val x1: Int, val y1: Int, val lumMedian: Float, val lumInterior: FloatArray)

    /**
     * Background colour of the cell: per-channel median of the pixels along the middle part of
     * the four edges (pieces rarely touch the edges; coordinate labels live in the corners).
     */
    fun background(cell: RgbImage): Int {
        val rs = ArrayList<Int>(); val gs = ArrayList<Int>(); val bs = ArrayList<Int>()
        val a = (N * 0.22f).toInt(); val b = N - a
        fun add(x: Int, y: Int) { val c = cell.get(x, y); rs.add((c shr 16) and 255); gs.add((c shr 8) and 255); bs.add(c and 255) }
        for (i in a until b) for (d in 0..1) { add(i, d); add(i, N - 1 - d); add(d, i); add(N - 1 - d, i) }
        rs.sort(); gs.sort(); bs.sort()
        val m = rs.size / 2
        return (0xFF shl 24) or (rs[m] shl 16) or (gs[m] shl 8) or bs[m]
    }

    fun silhouette(cell: RgbImage, bg: Int, threshold: Int = 34): Silhouette? {
        val n = N * N
        val fg = BooleanArray(n)
        val t2 = threshold * threshold
        for (y in 0 until N) for (x in 0 until N) {
            if (inCornerZone(x, y)) continue
            if (RgbImage.dist2(cell.get(x, y), bg) > t2) fg[y * N + x] = true
        }
        // flood fill outside (non-fg) from the border
        val outside = BooleanArray(n)
        val queue = IntArray(n); var qh = 0; var qt = 0
        fun push(i: Int) { if (!fg[i] && !outside[i]) { outside[i] = true; queue[qt++] = i } }
        for (i in 0 until N) { push(i); push((N - 1) * N + i); push(i * N); push(i * N + N - 1) }
        while (qh < qt) {
            val i = queue[qh++]; val x = i % N; val y = i / N
            if (x > 0) push(i - 1); if (x < N - 1) push(i + 1); if (y > 0) push(i - N); if (y < N - 1) push(i + N)
        }
        val sil = BooleanArray(n) { !outside[it] }
        // largest 4-connected component
        val label = IntArray(n)
        var bestLabel = 0; var bestSize = 0; var cur = 0
        for (s in 0 until n) {
            if (!sil[s] || label[s] != 0) continue
            cur++
            qh = 0; qt = 0; queue[qt++] = s; label[s] = cur
            var size = 0
            while (qh < qt) {
                val i = queue[qh++]; size++
                val x = i % N; val y = i / N
                fun visit(j: Int) { if (sil[j] && label[j] == 0) { label[j] = cur; queue[qt++] = j } }
                if (x > 0) visit(i - 1); if (x < N - 1) visit(i + 1); if (y > 0) visit(i - N); if (y < N - 1) visit(i + N)
            }
            if (size > bestSize) { bestSize = size; bestLabel = cur }
        }
        if (bestSize == 0) return null
        val mask = BooleanArray(n) { label[it] == bestLabel }
        var x0 = N; var y0 = N; var x1 = -1; var y1 = -1
        for (i in 0 until n) if (mask[i]) {
            val x = i % N; val y = i / N
            x0 = min(x0, x); y0 = min(y0, y); x1 = max(x1, x); y1 = max(y1, y)
        }
        // interior pixels (eroded twice) for colour estimation
        var er = mask
        repeat(2) {
            val prev = er
            er = BooleanArray(n) { i ->
                val x = i % N; val y = i / N
                prev[i] && x > 0 && y > 0 && x < N - 1 && y < N - 1 && prev[i - 1] && prev[i + 1] && prev[i - N] && prev[i + N]
            }
        }
        val lums = ArrayList<Float>()
        for (i in 0 until n) if (er[i]) lums.add(RgbImage.lum(cell.pixels[i]).toFloat())
        if (lums.size < 8) for (i in 0 until n) if (mask[i]) lums.add(RgbImage.lum(cell.pixels[i]).toFloat())
        lums.sort()
        val med = if (lums.isEmpty()) 128f else lums[lums.size / 2]
        return Silhouette(mask, bestSize, x0, y0, x1, y1, med, lums.toFloatArray())
    }

    fun isPiece(s: Silhouette): Boolean {
        val h = (s.y1 - s.y0 + 1).toFloat() / N
        val w = (s.x1 - s.x0 + 1).toFloat() / N
        val areaFrac = s.area.toFloat() / (N * N)
        if (areaFrac < 0.07f || h < 0.36f || w < 0.2f) return false
        if (areaFrac > 0.85f) return false // overlay / full-square highlight
        // legal-move dot: small, round, centered
        val fill = s.area.toFloat() / ((s.x1 - s.x0 + 1) * (s.y1 - s.y0 + 1))
        if (h < 0.46f && abs(w - h) < 0.08f && fill > 0.72f && fill < 0.82f) return false
        return true
    }

    const val FEATURE_SIZE = 100 + 144 + 16 + 4

    fun features(s: Silhouette): FloatArray {
        val f = FloatArray(FEATURE_SIZE)
        var o = 0
        // (1) cell-normalized 10x10 occupancy
        for (by in 0 until 10) for (bx in 0 until 10) {
            var c = 0
            for (y in by * 4 until by * 4 + 4) for (x in bx * 4 until bx * 4 + 4) if (s.mask[y * N + x]) c++
            f[o++] = c / 16f
        }
        // (2) bbox-normalized 12x12 occupancy
        val bw = (s.x1 - s.x0 + 1).toFloat(); val bh = (s.y1 - s.y0 + 1).toFloat()
        for (by in 0 until 12) for (bx in 0 until 12) {
            var c = 0; var t = 0
            val ya = s.y0 + by * bh / 12; val yb = s.y0 + (by + 1) * bh / 12
            val xa = s.x0 + bx * bw / 12; val xb = s.x0 + (bx + 1) * bw / 12
            var y = ya
            while (y < yb) {
                var x = xa
                while (x < xb) {
                    t++
                    if (s.mask[y.toInt().coerceIn(0, N - 1) * N + x.toInt().coerceIn(0, N - 1)]) c++
                    x += 0.5f
                }
                y += 0.5f
            }
            f[o++] = if (t == 0) 0f else c.toFloat() / t
        }
        // (3) row width profile within bbox
        var maxW = 1
        val widths = IntArray(16)
        for (r in 0 until 16) {
            val y = (s.y0 + (r + 0.5f) * bh / 16).toInt().coerceIn(0, N - 1)
            var c = 0
            for (x in 0 until N) if (s.mask[y * N + x]) c++
            widths[r] = c; maxW = max(maxW, c)
        }
        for (r in 0 until 16) f[o++] = widths[r].toFloat() / maxW
        // (4) scalars
        f[o++] = bh / N
        f[o++] = bw / N
        f[o++] = s.area.toFloat() / (N * N)
        f[o++] = s.area / (bw * bh)
        return f
    }

    private val W1 = 1.0f; private val W2 = 1.3f; private val W3 = 2.0f; private val W4 = 12f

    fun distance(a: FloatArray, b: FloatArray): Float {
        var d1 = 0f; var d2 = 0f; var d3 = 0f; var d4 = 0f
        for (i in 0 until 100) { val d = a[i] - b[i]; d1 += d * d }
        for (i in 100 until 244) { val d = a[i] - b[i]; d2 += d * d }
        for (i in 244 until 260) { val d = a[i] - b[i]; d3 += d * d }
        for (i in 260 until 264) { val d = a[i] - b[i]; d4 += d * d }
        return W1 * d1 / 100f * 10 + W2 * d2 / 144f * 10 + W3 * d3 / 16f * 10 + W4 * d4
    }
}

/** Nearest-neighbour piece type classifier over reference + user-taught samples. */
class PieceClassifier(samples: List<Sample>) {
    private val samples = samples.toList()
    val size get() = samples.size

    /** Returns types ranked by best (weighted) distance. */
    fun rank(f: FloatArray): List<Pair<Int, Float>> {
        val best = FloatArray(7) { Float.MAX_VALUE }
        for (s in samples) {
            val d = CellAnalyzer.distance(f, s.features) / s.weight
            if (d < best[s.type]) best[s.type] = d
        }
        return (1..6).filter { best[it] < Float.MAX_VALUE }.map { it to best[it] }.sortedBy { it.second }
    }
}

object BoardRecognizer {
    fun cellImage(img: RgbImage, rect: BoardRect, row: Int, col: Int): RgbImage {
        val c = rect.cell
        return CellAnalyzer.crop(img, rect.x + col * c, rect.y + row * c, c)
    }

    fun recognize(
        img: RgbImage,
        detect: DetectResult,
        classifier: PieceClassifier,
        flippedOverride: Boolean? = null,
    ): Recognition {
        val rect = detect.rect
        data class Raw(val row: Int, val col: Int, val sil: CellAnalyzer.Silhouette?, val feat: FloatArray?, val rank: List<Pair<Int, Float>>)
        val raws = ArrayList<Raw>(64)
        for (row in 0 until 8) for (col in 0 until 8) {
            val cell = cellImage(img, rect, row, col)
            val bg = CellAnalyzer.background(cell)
            val sil = CellAnalyzer.silhouette(cell, bg)
            if (sil != null && CellAnalyzer.isPiece(sil)) {
                val f = CellAnalyzer.features(sil)
                raws.add(Raw(row, col, sil, f, classifier.rank(f)))
            } else raws.add(Raw(row, col, null, null, emptyList()))
        }
        // --- piece colour: 2-means on interior luminance ---
        val lums = raws.mapNotNull { it.sil?.lumMedian }
        var thr = 128f
        if (lums.size >= 2) {
            var a = lums.min(); var b = lums.max()
            if (b - a > 40) {
                repeat(10) {
                    val mid = (a + b) / 2
                    val lo = lums.filter { it < mid }; val hi = lums.filter { it >= mid }
                    if (lo.isNotEmpty()) a = lo.average().toFloat()
                    if (hi.isNotEmpty()) b = hi.average().toFloat()
                }
                thr = (a + b) / 2
            }
        }
        val cells = raws.map { r ->
            if (r.sil == null) CellResult(r.row, r.col, false, 0, false, 1f, null, 0f)
            else {
                val type = r.rank.firstOrNull()?.first ?: Pieces.PAWN
                val d1 = r.rank.getOrNull(0)?.second ?: 1f
                val d2 = r.rank.getOrNull(1)?.second ?: (d1 * 3)
                val conf = (1f - d1 / max(d2, 1e-3f)).coerceIn(0f, 1f) * 0.8f + 0.2f * exp(-d1).toFloat()
                CellResult(r.row, r.col, true, type, r.sil.lumMedian >= thr, conf.coerceIn(0f, 1f), r.feat, r.sil.lumMedian, r.rank)
            }
        }.toMutableList()
        fixConsistency(cells)
        val flipped = flippedOverride ?: guessFlipped(cells)
        // pawns on back ranks are impossible → second choice
        for (i in cells.indices) {
            val c = cells[i]
            if (c.occupied && c.type == Pieces.PAWN && (c.row == 0 || c.row == 7)) {
                val alt = c.alternatives.firstOrNull { it.first != Pieces.PAWN && it.first != Pieces.KING }
                if (alt != null) cells[i] = c.copy(type = alt.first, confidence = c.confidence * 0.5f)
            }
        }
        return Recognition(rect, cells, flipped, detect.lightColor, detect.darkColor)
    }

    /** Exactly one king per colour: keep the most king-like candidate. */
    private fun fixConsistency(cells: MutableList<CellResult>) {
        for (white in listOf(true, false)) {
            val idx = cells.indices.filter { cells[it].occupied && cells[it].white == white }
            if (idx.isEmpty()) continue
            fun kingDist(i: Int) = cells[i].alternatives.firstOrNull { it.first == Pieces.KING }?.second ?: Float.MAX_VALUE
            val kings = idx.filter { cells[it].type == Pieces.KING }
            if (kings.size > 1) {
                val keep = kings.minBy { kingDist(it) }
                for (k in kings) if (k != keep) {
                    val alt = cells[k].alternatives.firstOrNull { it.first != Pieces.KING }
                    if (alt != null) cells[k] = cells[k].copy(type = alt.first, confidence = cells[k].confidence * 0.6f)
                }
            } else if (kings.isEmpty()) {
                val best = idx.minBy { kingDist(it) }
                if (kingDist(best) < Float.MAX_VALUE) cells[best] = cells[best].copy(type = Pieces.KING, confidence = cells[best].confidence * 0.5f)
            }
        }
    }

    /** Guess orientation: white pieces (esp. pawns and king) normally sit at the bottom. */
    fun guessFlipped(cells: List<CellResult>): Boolean {
        fun meanRow(white: Boolean, type: Int?): Double? {
            val xs = cells.filter { it.occupied && it.white == white && (type == null || it.type == type) }.map { it.row.toDouble() }
            return if (xs.isEmpty()) null else xs.average()
        }
        val wp = meanRow(true, Pieces.PAWN); val bp = meanRow(false, Pieces.PAWN)
        if (wp != null && bp != null && abs(wp - bp) >= 1.0) return wp < bp
        val wk = meanRow(true, Pieces.KING); val bk = meanRow(false, Pieces.KING)
        if (wk != null && bk != null && abs(wk - bk) >= 2.0) return wk < bk
        val wa = meanRow(true, null); val ba = meanRow(false, null)
        if (wa != null && ba != null && abs(wa - ba) >= 1.5) return wa < ba
        return false
    }

    /**
     * Learns samples from a screenshot of the STANDARD starting position (calibration).
     * Returns labeled samples, or null if the cells don't look like a start position.
     */
    fun calibrationSamples(rec: Recognition, source: String): List<Sample>? {
        val start = Position.start()
        val samples = ArrayList<Sample>()
        var occupiedOk = 0
        for (c in rec.cells) {
            val expected = start[rec.squareOf(c.row, c.col)]
            if ((expected != 0) == c.occupied) occupiedOk++
            if (expected != 0 && c.features != null) samples.add(Sample(Pieces.type(expected), c.features, source, 1.6f))
        }
        return if (occupiedOk >= 60 && samples.size >= 28) samples else null
    }
}

/** Builds reference samples from rendered piece sets (the renderer is platform specific). */
object ReferenceBuilder {
    /**
     * [render] must return an N×N ARGB image of the piece (transparent background) drawn
     * to fill the whole cell, like piece sets on popular sites.
     */
    fun build(setName: String, render: (piece: Int, size: Int) -> RgbImage, squareColors: List<Int>): List<Sample> {
        val out = ArrayList<Sample>()
        val n = CellAnalyzer.N
        for (type in 1..6) for (white in listOf(true, false)) {
            val piece = if (white) type else -type
            for (scale in listOf(1.0f, 0.88f)) {
                val rendered = render(piece, n * 2)
                for (bg in squareColors) {
                    val composed = composite(rendered, bg, scale)
                    val cell = CellAnalyzer.crop(composed, 0f, 0f, composed.width.toFloat())
                    val sil = CellAnalyzer.silhouette(cell, bg) ?: continue
                    out.add(Sample(type, CellAnalyzer.features(sil), "ref:$setName"))
                }
            }
        }
        return out
    }

    private fun composite(src: RgbImage, bg: Int, scale: Float): RgbImage {
        val n = src.width
        val out = IntArray(n * n) { bg }
        val off = (n - n * scale) / 2f
        for (y in 0 until n) for (x in 0 until n) {
            val sx = ((x - off) / scale).toInt(); val sy = ((y - off) / scale).toInt()
            if (sx !in 0 until n || sy !in 0 until n) continue
            val p = src.get(sx, sy)
            val a = (p ushr 24) and 255
            if (a == 0) continue
            val br = (bg shr 16) and 255; val bgc = (bg shr 8) and 255; val bb = bg and 255
            val r = ((p shr 16) and 255) * a / 255 + br * (255 - a) / 255
            val g = ((p shr 8) and 255) * a / 255 + bgc * (255 - a) / 255
            val b = (p and 255) * a / 255 + bb * (255 - a) / 255
            out[y * n + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return RgbImage(n, n, out)
    }

    /** Typical square colours of popular board themes. */
    val THEME_SQUARES = listOf(
        0xFFF0D9B5.toInt(), 0xFFB58863.toInt(), // lichess brown
        0xFFEEEED2.toInt(), 0xFF769656.toInt(), // chess.com green
        0xFFDEE3E6.toInt(), 0xFF8CA2AD.toInt(), // blue-grey
    )
}
