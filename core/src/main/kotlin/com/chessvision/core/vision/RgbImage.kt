package com.chessvision.core.vision

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Platform-independent ARGB image (Android Bitmap / AWT BufferedImage are converted to this). */
class RgbImage(val width: Int, val height: Int, val pixels: IntArray) {
    init { require(pixels.size == width * height) }

    fun get(x: Int, y: Int) = pixels[y * width + x]

    /** Area-averaging downscale so that max(width, height) <= maxDim. */
    fun downscale(maxDim: Int): Pair<RgbImage, Float> {
        val m = max(width, height)
        if (m <= maxDim) return this to 1f
        val scale = maxDim.toFloat() / m
        val nw = max(1, (width * scale).roundToInt())
        val nh = max(1, (height * scale).roundToInt())
        return resample(0f, 0f, width.toFloat(), height.toFloat(), nw, nh) to (nw.toFloat() / width)
    }

    /** Area-average resampling of the source rect (x,y,w,h) to (outW,outH). */
    fun resample(x: Float, y: Float, w: Float, h: Float, outW: Int, outH: Int): RgbImage {
        val out = IntArray(outW * outH)
        val sx = w / outW; val sy = h / outH
        for (oy in 0 until outH) {
            val y0 = y + oy * sy; val y1 = y0 + sy
            val iy0 = max(0, y0.toInt()); val iy1 = min(height - 1, max(iy0, (y1 - 1e-4f).toInt()))
            for (ox in 0 until outW) {
                val x0 = x + ox * sx; val x1 = x0 + sx
                val ix0 = max(0, x0.toInt()); val ix1 = min(width - 1, max(ix0, (x1 - 1e-4f).toInt()))
                var r = 0L; var g = 0L; var b = 0L; var n = 0L
                // subsample at most 4x4 per output pixel for speed
                val stepY = max(1, (iy1 - iy0 + 1) / 4)
                val stepX = max(1, (ix1 - ix0 + 1) / 4)
                var yy = iy0
                while (yy <= iy1) {
                    var xx = ix0
                    val row = yy * width
                    while (xx <= ix1) {
                        val p = pixels[row + xx]
                        r += (p shr 16) and 255; g += (p shr 8) and 255; b += p and 255; n++
                        xx += stepX
                    }
                    yy += stepY
                }
                if (n == 0L) n = 1
                out[oy * outW + ox] = (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
            }
        }
        return RgbImage(outW, outH, out)
    }

    companion object {
        fun lum(p: Int): Int = (((p shr 16) and 255) * 299 + ((p shr 8) and 255) * 587 + (p and 255) * 114) / 1000
        fun dist2(a: Int, b: Int): Int {
            val dr = ((a shr 16) and 255) - ((b shr 16) and 255)
            val dg = ((a shr 8) and 255) - ((b shr 8) and 255)
            val db = (a and 255) - (b and 255)
            return dr * dr + dg * dg + db * db
        }
    }
}

/** Summed-area tables for fast rectangle means/variance. */
class Integral(img: RgbImage) {
    val w = img.width; val h = img.height
    private val sr = LongArray((w + 1) * (h + 1))
    private val sg = LongArray((w + 1) * (h + 1))
    private val sb = LongArray((w + 1) * (h + 1))
    private val sq = LongArray((w + 1) * (h + 1))

    init {
        for (y in 0 until h) {
            var rr = 0L; var gg = 0L; var bb = 0L; var qq = 0L
            for (x in 0 until w) {
                val p = img.pixels[y * w + x]
                val r = (p shr 16) and 255; val g = (p shr 8) and 255; val b = p and 255
                rr += r; gg += g; bb += b; qq += (r * r + g * g + b * b).toLong()
                val i = (y + 1) * (w + 1) + x + 1
                val above = y * (w + 1) + x + 1
                sr[i] = sr[above] + rr; sg[i] = sg[above] + gg; sb[i] = sb[above] + bb; sq[i] = sq[above] + qq
            }
        }
    }

    private fun sum(a: LongArray, x0: Int, y0: Int, x1: Int, y1: Int): Long {
        val W = w + 1
        return a[y1 * W + x1] - a[y0 * W + x1] - a[y1 * W + x0] + a[y0 * W + x0]
    }

    /** Mean color of [x0,x1) × [y0,y1) packed into out (r,g,b) and returns pixel variance (sum over channels). */
    fun stats(x0i: Int, y0i: Int, x1i: Int, y1i: Int, out: FloatArray): Float {
        val x0 = x0i.coerceIn(0, w); val x1 = x1i.coerceIn(x0 + 1, w).coerceAtLeast(x0 + 1).coerceAtMost(w)
        val y0 = y0i.coerceIn(0, h); val y1 = y1i.coerceIn(y0 + 1, h).coerceAtMost(h)
        if (x1 <= x0 || y1 <= y0) { out[0] = 0f; out[1] = 0f; out[2] = 0f; return 1e6f }
        val n = ((x1 - x0) * (y1 - y0)).toFloat()
        val r = sum(sr, x0, y0, x1, y1) / n; val g = sum(sg, x0, y0, x1, y1) / n; val b = sum(sb, x0, y0, x1, y1) / n
        out[0] = r; out[1] = g; out[2] = b
        val q = sum(sq, x0, y0, x1, y1) / n
        return max(0f, q - (r * r + g * g + b * b))
    }
}
