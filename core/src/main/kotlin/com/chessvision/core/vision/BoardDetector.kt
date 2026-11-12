package com.chessvision.core.vision

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Axis-aligned square board in image pixel coordinates. */
data class BoardRect(val x: Float, val y: Float, val size: Float) {
    val cell get() = size / 8f
    fun scaled(k: Float) = BoardRect(x * k, y * k, size * k)
}

data class DetectResult(val rect: BoardRect, val score: Float, val lightColor: Int, val darkColor: Int)

/**
 * Finds the chessboard in a screenshot by maximizing a checkerboard score: the colours sampled
 * at the corners of the 64 cells (where pieces almost never reach) must split into two
 * well-separated, internally consistent classes that alternate like a chessboard.
 * Coarse-to-fine search over position and size with summed-area tables.
 */
object BoardDetector {
    private class Eval(val score: Float, val light: Int, val dark: Int)

    private fun evaluate(ig: Integral, x: Float, y: Float, s: Float, tmp: FloatArray, cells: FloatArray): Eval? {
        if (x < -0.5f || y < -0.5f || x + s > ig.w + 0.5f || y + s > ig.h + 0.5f) return null
        val c = s / 8f
        val ps = max(1f, c * 0.13f)
        val inset = c * 0.05f
        var spreadSum = 0f
        var noiseSum = 0f
        val pr = FloatArray(4); val pg = FloatArray(4); val pb = FloatArray(4)
        for (row in 0 until 8) for (col in 0 until 8) {
            val cx0 = x + col * c; val cy0 = y + row * c
            var k = 0
            for (cy in 0..1) for (cx in 0..1) {
                val px = if (cx == 0) cx0 + inset else cx0 + c - inset - ps
                val py = if (cy == 0) cy0 + inset else cy0 + c - inset - ps
                val x0 = px.roundToInt(); val y0 = py.roundToInt()
                val v = ig.stats(x0, y0, max(x0 + 1, (px + ps).roundToInt()), max(y0 + 1, (py + ps).roundToInt()), tmp)
                pr[k] = tmp[0]; pg[k] = tmp[1]; pb[k] = tmp[2]
                noiseSum += v
                k++
            }
            // drop the patch farthest from the others (coordinate labels, highlights)
            var worst = 0; var worstD = -1f
            for (i in 0 until 4) {
                var d = 0f
                for (j in 0 until 4) if (i != j) {
                    val dr = pr[i] - pr[j]; val dg = pg[i] - pg[j]; val db = pb[i] - pb[j]
                    d += dr * dr + dg * dg + db * db
                }
                if (d > worstD) { worstD = d; worst = i }
            }
            var mr = 0f; var mg = 0f; var mb = 0f
            for (i in 0 until 4) if (i != worst) { mr += pr[i]; mg += pg[i]; mb += pb[i] }
            mr /= 3; mg /= 3; mb /= 3
            var sp = 0f
            for (i in 0 until 4) if (i != worst) {
                val dr = pr[i] - mr; val dg = pg[i] - mg; val db = pb[i] - mb
                sp += dr * dr + dg * dg + db * db
            }
            spreadSum += sp / 3
            val idx = (row * 8 + col) * 3
            cells[idx] = mr; cells[idx + 1] = mg; cells[idx + 2] = mb
        }
        // class means
        val m = FloatArray(6); var na = 0; var nb = 0
        for (i in 0 until 64) {
            val cls = ((i / 8) + (i % 8)) and 1
            val o = cls * 3
            m[o] += cells[i * 3]; m[o + 1] += cells[i * 3 + 1]; m[o + 2] += cells[i * 3 + 2]
            if (cls == 0) na++ else nb++
        }
        for (i in 0..2) { m[i] /= na; m[3 + i] /= nb }
        val dr = m[0] - m[3]; val dg = m[1] - m[4]; val db = m[2] - m[5]
        val d2 = dr * dr + dg * dg + db * db
        if (d2 < 150f) return null
        // trimmed residual per class
        val resA = FloatArray(32); val resB = FloatArray(32); var ia = 0; var ib = 0
        for (i in 0 until 64) {
            val cls = ((i / 8) + (i % 8)) and 1
            val o = cls * 3
            val er = cells[i * 3] - m[o]; val eg = cells[i * 3 + 1] - m[o + 1]; val eb = cells[i * 3 + 2] - m[o + 2]
            val e = er * er + eg * eg + eb * eb
            if (cls == 0) resA[ia++] = e else resB[ib++] = e
        }
        resA.sort(); resB.sort()
        var res = 0f
        for (i in 0 until 28) res += resA[i] + resB[i]
        res /= 56f
        val score = d2 / (res + spreadSum / 64f + 0.25f * noiseSum / 256f + 20f)
        fun pack(o: Int) = (0xFF shl 24) or (m[o].roundToInt().coerceIn(0, 255) shl 16) or
                (m[o + 1].roundToInt().coerceIn(0, 255) shl 8) or m[o + 2].roundToInt().coerceIn(0, 255)
        val lumA = m[0] * 0.299f + m[1] * 0.587f + m[2] * 0.114f
        val lumB = m[3] * 0.299f + m[4] * 0.587f + m[5] * 0.114f
        // class 0 = (row+col) even = a8-style top-left square, which is light on a standard board
        return if (lumA >= lumB) Eval(score, pack(0), pack(3)) else Eval(score, pack(3), pack(0))
    }

    private data class Cand(val x: Float, val y: Float, val s: Float, val score: Float, val light: Int, val dark: Int)

    private fun search(ig: Integral, xs: Sequence<Float>, ys: (Float) -> Sequence<Float>, sizes: Sequence<Float>, xsFor: (Float) -> Sequence<Float>): Cand? {
        val tmp = FloatArray(3); val cells = FloatArray(192)
        var best: Cand? = null
        for (s in sizes) for (x in xsFor(s)) for (y in ys(s)) {
            val e = evaluate(ig, x, y, s, tmp, cells) ?: continue
            if (best == null || e.score > best.score) best = Cand(x, y, s, e.score, e.light, e.dark)
        }
        return best
    }

    private fun range(from: Float, to: Float, step: Float): Sequence<Float> =
        if (to < from) emptySequence() else generateSequence(from) { it + step }.takeWhile { it <= to + 1e-3f }

    fun detect(img: RgbImage): DetectResult? {
        // ---- coarse ----
        val (small, k1) = img.downscale(176)
        val ig1 = Integral(small)
        val minDim = min(small.width, small.height).toFloat()
        val coarse = search(
            ig1, emptySequence(),
            ys = { s -> range(0f, small.height - s, 2f) },
            sizes = range((minDim * 0.25f).coerceAtLeast(24f), minDim, 2f),
            xsFor = { s -> range(0f, small.width - s, 2f) },
        ) ?: return null
        // ---- medium ----
        val (mid, k2) = img.downscale(720)
        val r = k2 / k1
        val ig2 = Integral(mid)
        val win = 2.5f * r + 2f
        val step2 = max(1f, r / 2f)
        val medium = search(
            ig2, emptySequence(),
            ys = { _ -> range(coarse.y * r - win, coarse.y * r + win, step2) },
            sizes = range(coarse.s * r - win, coarse.s * r + win, step2),
            xsFor = { _ -> range(coarse.x * r - win, coarse.x * r + win, step2) },
        ) ?: coarse.copy(x = coarse.x * r, y = coarse.y * r, s = coarse.s * r)
        val med = if (medium === coarse) medium else medium
        // fine (step 1 on medium image)
        val fine = search(
            ig2, emptySequence(),
            ys = { _ -> range(med.y - step2, med.y + step2, 1f) },
            sizes = range(med.s - step2, med.s + step2, 1f),
            xsFor = { _ -> range(med.x - step2, med.x + step2, 1f) },
        ) ?: med
        // ---- full resolution polish ----
        var result = fine
        val r3 = 1f / k2
        if (r3 > 1.01f) {
            val ig3 = Integral(img)
            val w3 = r3 + 1f
            val st = max(1f, r3 / 3f)
            result = search(
                ig3, emptySequence(),
                ys = { _ -> range(fine.y * r3 - w3, fine.y * r3 + w3, st) },
                sizes = range(fine.s * r3 - w3, fine.s * r3 + w3, st),
                xsFor = { _ -> range(fine.x * r3 - w3, fine.x * r3 + w3, st) },
            ) ?: fine.copy(x = fine.x * r3, y = fine.y * r3, s = fine.s * r3)
        }
        if (result.score < 4f) return null
        val aligned = alignToGrid(img, BoardRect(result.x, result.y, result.s))
        return DetectResult(aligned, result.score, result.light, result.dark)
    }

    /**
     * Sub-pixel refinement: inner grid lines of a chessboard separate squares of different colour
     * along their whole length, so we maximize colour-gradient energy on the predicted lines.
     */
    fun alignToGrid(img: RgbImage, r: BoardRect): BoardRect {
        val w = img.width; val h = img.height
        val px = img.pixels
        val y0 = r.y.toInt().coerceIn(0, h - 1); val y1 = (r.y + r.size).toInt().coerceIn(y0 + 1, h)
        val x0 = r.x.toInt().coerceIn(0, w - 1); val x1 = (r.x + r.size).toInt().coerceIn(x0 + 1, w)
        // column edge profile: edge between x-1 and x
        val col = FloatArray(w + 1)
        val ystep = max(1, (y1 - y0) / 400)
        for (y in y0 until y1 step ystep) {
            val row = y * w
            for (x in 1 until w) col[x] += sqrt(RgbImage.dist2(px[row + x], px[row + x - 1]).toFloat())
        }
        val rowP = FloatArray(h + 1)
        val xstep = max(1, (x1 - x0) / 400)
        for (y in 1 until h) {
            val a = y * w; val b = (y - 1) * w
            var acc = 0f
            var x = x0
            while (x < x1) { acc += sqrt(RgbImage.dist2(px[a + x], px[b + x]).toFloat()); x += xstep }
            rowP[y] = acc
        }
        fun at(p: FloatArray, v: Float): Float {
            // a boundary at real coordinate v lies between pixels floor(v-0.5) and floor(v+0.5)
            val i = v.roundToInt()
            if (i < 1 || i >= p.size - 1) return 0f
            return max(p[i], 0.5f * (p[i - 1] + p[i + 1]))
        }
        fun lineScore(p: FloatArray, start: Float, cell: Float): Float {
            var s = 0f
            for (k in 1..7) s += at(p, start + k * cell)
            s += 0.35f * (at(p, start) + at(p, start + 8 * cell))
            return s
        }
        val c0 = r.size / 8f
        val win = max(3f, r.size / 80f)
        var bestX = r.x; var bestY = r.y; var bestC = c0; var best = -1f
        var c = c0 - win / 8f
        while (c <= c0 + win / 8f + 1e-3f) {
            var bx = r.x; var bxs = -1f
            var x = r.x - win
            while (x <= r.x + win) { val s = lineScore(col, x, c); if (s > bxs) { bxs = s; bx = x }; x += 0.5f }
            var by = r.y; var bys = -1f
            var y = r.y - win
            while (y <= r.y + win) { val s = lineScore(rowP, y, c); if (s > bys) { bys = s; by = y }; y += 0.5f }
            if (bxs + bys > best) { best = bxs + bys; bestX = bx; bestY = by; bestC = c }
            c += 0.125f
        }
        return BoardRect(bestX, bestY, bestC * 8f)
    }

    /** Re-evaluates a user-adjusted rectangle (returns square colours) without searching. */
    fun colorsFor(img: RgbImage, rect: BoardRect): DetectResult? {
        val ig = Integral(img)
        val e = evaluate(ig, rect.x, rect.y, rect.size, FloatArray(3), FloatArray(192)) ?: return null
        return DetectResult(rect, e.score, e.light, e.dark)
    }
}
