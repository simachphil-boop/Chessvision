package com.chessvision.ai.pieces

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import com.chessvision.core.svg.LineCap
import com.chessvision.core.svg.LineJoin
import com.chessvision.core.svg.PathCmd
import com.chessvision.core.svg.SvgDocument
import com.chessvision.core.svg.SvgParser
import com.chessvision.core.vision.RgbImage

/** A piece set loaded from assets/pieces/<name>/{w,b}{P,N,B,R,Q,K}.svg, drawn with android.graphics. */
class PieceSetAssets(val name: String, private val docs: Map<Int, SvgDocument>) {
    private class Prepared(val path: Path, val fill: Paint?, val stroke: Paint?)

    private val prepared: Map<Int, List<Prepared>> = docs.mapValues { (_, doc) -> prepare(doc) }
    private val bitmapCache = HashMap<Long, Bitmap>()

    private fun prepare(doc: SvgDocument): List<Prepared> = doc.shapes.map { s ->
        val p = Path()
        for (c in s.cmds) when (c) {
            is PathCmd.MoveTo -> p.moveTo(c.x, c.y)
            is PathCmd.LineTo -> p.lineTo(c.x, c.y)
            is PathCmd.CubicTo -> p.cubicTo(c.x1, c.y1, c.x2, c.y2, c.x, c.y)
            is PathCmd.QuadTo -> p.quadTo(c.x1, c.y1, c.x, c.y)
            PathCmd.Close -> p.close()
        }
        p.fillType = if (s.evenOdd) Path.FillType.EVEN_ODD else Path.FillType.WINDING
        val fill: Paint? = when {
            s.fillGradient != null -> Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                val g = s.fillGradient!!
                shader = if (g.colors.size >= 2 && (g.x1 != g.x2 || g.y1 != g.y2))
                    LinearGradient(g.x1, g.y1, g.x2, g.y2, g.colors, g.offsets, Shader.TileMode.CLAMP)
                else null
                if (shader == null) color = g.colors.first()
            }
            s.fill != null -> Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = s.fill!! }
            else -> null
        }
        val stroke = s.stroke?.let { col ->
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = col
                strokeWidth = s.strokeWidth
                strokeCap = when (s.cap) { LineCap.ROUND -> Paint.Cap.ROUND; LineCap.SQUARE -> Paint.Cap.SQUARE; else -> Paint.Cap.BUTT }
                strokeJoin = when (s.join) { LineJoin.ROUND -> Paint.Join.ROUND; LineJoin.BEVEL -> Paint.Join.BEVEL; else -> Paint.Join.MITER }
            }
        }
        Prepared(p, fill, stroke)
    }

    /** Draws [piece] into a square (x, y, size) of [canvas]. */
    fun draw(canvas: Canvas, piece: Int, x: Float, y: Float, size: Float) {
        val doc = docs[piece] ?: return
        val shapes = prepared[piece] ?: return
        val k = size / maxOf(doc.width, doc.height)
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(k, k)
        canvas.translate(-doc.minX, -doc.minY)
        for (s in shapes) {
            s.fill?.let { canvas.drawPath(s.path, it) }
            s.stroke?.let { canvas.drawPath(s.path, it) }
        }
        canvas.restore()
    }

    /** Cached bitmap of a piece at [sizePx]. */
    @Synchronized
    fun bitmap(piece: Int, sizePx: Int): Bitmap {
        val key = (piece.toLong() shl 32) or sizePx.toLong()
        return bitmapCache.getOrPut(key) {
            if (bitmapCache.size > 64) bitmapCache.clear()
            val b = Bitmap.createBitmap(sizePx.coerceAtLeast(1), sizePx.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            draw(Canvas(b), piece, 0f, 0f, sizePx.toFloat())
            b
        }
    }

    /** Transparent-background render used to build recognition references. */
    fun renderRgb(piece: Int, size: Int): RgbImage {
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        draw(Canvas(b), piece, 0f, 0f, size.toFloat())
        val px = IntArray(size * size)
        b.getPixels(px, 0, size, 0, 0, size, size)
        b.recycle()
        return RgbImage(size, size, px)
    }

    companion object {
        val SETS = listOf("cburnett" to "Классика (Cburnett)", "merida" to "Мерида")

        fun load(context: Context, name: String): PieceSetAssets {
            val docs = HashMap<Int, SvgDocument>()
            for (t in 1..6) for (white in listOf(true, false)) {
                val file = "pieces/$name/${if (white) "w" else "b"}${"PNBRQK"[t - 1]}.svg"
                val text = context.assets.open(file).bufferedReader().use { it.readText() }
                docs[if (white) t else -t] = SvgParser.parse(text)
            }
            return PieceSetAssets(name, docs)
        }
    }
}

fun Bitmap.toRgbImage(): RgbImage {
    val px = IntArray(width * height)
    getPixels(px, 0, width, 0, 0, width, height)
    return RgbImage(width, height, px)
}

@Suppress("unused")
private val identity = Matrix()
