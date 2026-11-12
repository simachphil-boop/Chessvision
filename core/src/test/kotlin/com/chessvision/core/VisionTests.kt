package com.chessvision.core

import com.chessvision.core.chess.*
import com.chessvision.core.svg.*
import com.chessvision.core.vision.*
import java.awt.*
import java.awt.geom.AffineTransform
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import kotlin.math.abs
import kotlin.random.Random

object AwtSvg {
    fun toPath(cmds: List<PathCmd>, evenOdd: Boolean): Path2D.Float {
        val p = Path2D.Float(if (evenOdd) Path2D.WIND_EVEN_ODD else Path2D.WIND_NON_ZERO)
        for (c in cmds) when (c) {
            is PathCmd.MoveTo -> p.moveTo(c.x, c.y)
            is PathCmd.LineTo -> p.lineTo(c.x, c.y)
            is PathCmd.CubicTo -> p.curveTo(c.x1, c.y1, c.x2, c.y2, c.x, c.y)
            is PathCmd.QuadTo -> p.quadTo(c.x1, c.y1, c.x, c.y)
            PathCmd.Close -> p.closePath()
        }
        return p
    }

    fun draw(g: Graphics2D, doc: SvgDocument, x: Float, y: Float, size: Float) {
        val old = g.transform
        val k = size / maxOf(doc.width, doc.height)
        g.translate(x.toDouble(), y.toDouble()); g.scale(k.toDouble(), k.toDouble()); g.translate(-doc.minX.toDouble(), -doc.minY.toDouble())
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        for (s in doc.shapes) {
            val path = toPath(s.cmds, s.evenOdd)
            s.fill?.let { g.color = Color(it, true); g.fill(path) }
            s.fillGradient?.let { gr ->
                if (gr.colors.size >= 2 && (gr.x1 != gr.x2 || gr.y1 != gr.y2)) {
                    val fr = gr.offsets.copyOf(); for (i in 1 until fr.size) if (fr[i] <= fr[i - 1]) fr[i] = minOf(1f, fr[i - 1] + 1e-4f)
                    g.paint = LinearGradientPaint(gr.x1, gr.y1, gr.x2, gr.y2, fr, gr.colors.map { Color(it, true) }.toTypedArray())
                } else g.color = Color(gr.colors.first(), true)
                g.fill(path)
            }
            s.stroke?.let {
                g.color = Color(it, true)
                g.stroke = BasicStroke(s.strokeWidth,
                    when (s.cap) { LineCap.ROUND -> BasicStroke.CAP_ROUND; LineCap.SQUARE -> BasicStroke.CAP_SQUARE; else -> BasicStroke.CAP_BUTT },
                    when (s.join) { LineJoin.ROUND -> BasicStroke.JOIN_ROUND; LineJoin.BEVEL -> BasicStroke.JOIN_BEVEL; else -> BasicStroke.JOIN_MITER })
                g.draw(path)
            }
        }
        g.transform = old
    }

    fun toRgb(img: BufferedImage): RgbImage {
        val w = img.width; val h = img.height
        return RgbImage(w, h, img.getRGB(0, 0, w, h, null, 0, w))
    }
}

class PieceSet(val name: String, dir: File) {
    val docs: Map<Int, SvgDocument> = buildMap {
        for (t in 1..6) for (white in listOf(true, false)) {
            val ch = "PNBRQK"[t - 1]
            val f = File(dir, "${if (white) "w" else "b"}$ch.svg")
            put(if (white) t else -t, SvgParser.parse(f.readText()))
        }
    }

    fun render(piece: Int, size: Int): RgbImage {
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        AwtSvg.draw(g, docs.getValue(piece), 0f, 0f, size.toFloat())
        g.dispose()
        return AwtSvg.toRgb(img)
    }
}

object VisionTests {
    private fun findAssets(): File? = listOf("app/src/main/assets/pieces", "../app/src/main/assets/pieces")
        .map { File(it) }.firstOrNull { it.isDirectory }

    fun randomPosition(rnd: Random): Position {
        var p = Position.start()
        val plies = 10 + rnd.nextInt(50)
        for (i in 0 until plies) {
            val ms = p.legalMoves()
            if (ms.isEmpty()) break
            // prefer captures sometimes to thin out the board
            val caps = ms.filter { p.isCapture(it) }
            p = p.play(if (caps.isNotEmpty() && rnd.nextFloat() < 0.6f) caps.random(rnd) else ms.random(rnd))
        }
        return p
    }

    data class Synth(val img: RgbImage, val rect: BoardRect, val board: IntArray, val flipped: Boolean)

    fun synthesize(pos: Position, set: PieceSet, light: Int, dark: Int, rnd: Random, flipped: Boolean, phone: Boolean): Synth {
        val w = if (phone) 1080 else 1600 + rnd.nextInt(300)
        val h = if (phone) 2340 else 900 + rnd.nextInt(200)
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color(0x302E2B); g.fillRect(0, 0, w, h)
        // UI clutter
        repeat(12) {
            g.color = Color(rnd.nextInt(0xFFFFFF))
            g.fillRoundRect(rnd.nextInt(w), rnd.nextInt(h), 40 + rnd.nextInt(300), 20 + rnd.nextInt(80), 12, 12)
        }
        g.color = Color.WHITE; g.font = Font("SansSerif", Font.BOLD, 34)
        g.drawString("Player123 (1500)  10:00", 40, if (phone) 300 else 60)
        val size = if (phone) w - rnd.nextInt(0, 2) * 40 else (h * (0.8 + rnd.nextDouble() * 0.15)).toInt()
        val bx = if (phone) (w - size) / 2 else 80 + rnd.nextInt(w - size - 120)
        val by = if (phone) 420 + rnd.nextInt(300) else (h - size) / 2
        val c = size / 8f
        val board = pos.board()
        val lastFrom = rnd.nextInt(64); val lastTo = rnd.nextInt(64)
        for (row in 0 until 8) for (col in 0 until 8) {
            val sq = if (!flipped) Squares.of(col, 7 - row) else Squares.of(7 - col, row)
            val isLight = (row + col) % 2 == 0
            var color = Color(if (isLight) light else dark)
            if (sq == lastFrom || sq == lastTo) color = Color((color.red + 255) / 2, (color.green + 230) / 2, color.blue / 2)
            g.color = color
            val x0 = (bx + col * c).toInt(); val y0 = (by + row * c).toInt()
            g.fillRect(x0, y0, (bx + (col + 1) * c).toInt() - x0, (by + (row + 1) * c).toInt() - y0)
            // coordinates (like chess.com / lichess)
            g.font = Font("SansSerif", Font.BOLD, (c * 0.2f).toInt())
            g.color = Color(if (isLight) dark else light)
            if (col == 0) g.drawString("${Squares.rank(sq) + 1}", x0 + (c * 0.06f).toInt(), y0 + (c * 0.22f).toInt())
            if (row == 7) g.drawString("${'a' + Squares.file(sq)}", (x0 + c * 0.82f).toInt(), (y0 + c * 0.95f).toInt())
            val p = board[sq]
            if (p != 0) {
                val scale = 0.9f + rnd.nextFloat() * 0.1f
                AwtSvg.draw(g, set.docs.getValue(p), x0 + c * (1 - scale) / 2, y0 + c * (1 - scale) / 2, c * scale)
            }
        }
        g.dispose()
        return Synth(AwtSvg.toRgb(img), BoardRect(bx.toFloat(), by.toFloat(), size.toFloat()), board, flipped)
    }

    fun run() {
        val dir = findAssets() ?: run { println("vision: assets not found, skipped"); return }
        val extra = System.getenv("VISION_EXTRA_SETS")?.split(',')?.map { File(it) }?.filter { it.isDirectory } ?: emptyList()
        val refSets = listOf(PieceSet("cburnett", File(dir, "cburnett")), PieceSet("merida", File(dir, "merida")))

        // SVG parsing sanity
        for (s in refSets) T.check("svg ${s.name}", s.docs.values.all { it.shapes.isNotEmpty() })

        val testPlan = ArrayList<Pair<PieceSet, List<PieceSet>>>()
        // cross-set: test each bundled set using only the OTHER as reference
        testPlan.add(refSets[0] to listOf(refSets[1]))
        testPlan.add(refSets[1] to listOf(refSets[0]))
        // same-set references (what the app has for these styles)
        testPlan.add(refSets[0] to refSets)
        for (e in extra) testPlan.add(PieceSet(e.name, e) to refSets)

        val themes = listOf(0xFFF0D9B5.toInt() to 0xFFB58863.toInt(), 0xFFEEEED2.toInt() to 0xFF769656.toInt(),
            0xFFDEE3E6.toInt() to 0xFF8CA2AD.toInt(), 0xFFE8EDF9.toInt() to 0xFFB7C0D8.toInt())
        val rnd = Random(7)
        for ((testSet, refs) in testPlan) {
            val samples = refs.flatMap { r -> ReferenceBuilder.build(r.name, r::render, ReferenceBuilder.THEME_SQUARES) }
            val clf = PieceClassifier(samples)
            var detOk = 0; var cellsOk = 0; var cellsTotal = 0; var occOk = 0; var colorOk = 0; var occTotal = 0; var flipOk = 0
            var perfect = 0
            val rounds = 8
            var tDet = 0L; var tRec = 0L
            for (i in 0 until rounds) {
                val (light, dark) = themes[i % themes.size]
                val flipped = i % 3 == 2
                val syn = synthesize(randomPosition(rnd), testSet, light, dark, rnd, flipped, phone = i % 2 == 0)
                val t0 = System.currentTimeMillis()
                val det = BoardDetector.detect(syn.img)
                tDet += System.currentTimeMillis() - t0
                if (det == null) { println("  [${testSet.name}] round $i: board not found"); continue }
                val err = maxOf(abs(det.rect.x - syn.rect.x), abs(det.rect.y - syn.rect.y), abs(det.rect.size - syn.rect.size))
                if (err <= maxOf(2f, syn.rect.size / 200f)) detOk++ else println("  [${testSet.name}] round $i: rect err=$err det=${det.rect} truth=${syn.rect}")
                val t1 = System.currentTimeMillis()
                val rec = BoardRecognizer.recognize(syn.img, det, clf)
                tRec += System.currentTimeMillis() - t1
                if (rec.flipped == syn.flipped) flipOk++
                val b = BoardRecognizer.recognize(syn.img, det, clf, flippedOverride = syn.flipped).board()
                var ok = 0
                for (sq in 0 until 64) {
                    val truth = syn.board[sq]
                    val got = b[sq]
                    cellsTotal++
                    if ((truth != 0) == (got != 0)) occOk++
                    if (truth != 0 && got != 0) { occTotal++; if ((truth > 0) == (got > 0)) colorOk++ }
                    if (truth == got) { cellsOk++; ok++ }
                }
                if (ok == 64) perfect++
                else println("  [${testSet.name}] round $i wrong: " + (0 until 64).filter { syn.board[it] != b[it] }.joinToString { "${Squares.name(it)} ${Pieces.toChar(syn.board[it])}->${Pieces.toChar(b[it])}" })
            }
            val label = "${testSet.name} vs refs[${refs.joinToString { it.name }}]"
            println("vision $label: detect=$detOk/$rounds cells=${"%.1f".format(100.0 * cellsOk / cellsTotal)}% occupancy=${"%.1f".format(100.0 * occOk / cellsTotal)}% color=${"%.1f".format(100.0 * colorOk / maxOf(1, occTotal))}% orientation=$flipOk/$rounds perfect=$perfect/$rounds  detect ${tDet / rounds}ms rec ${tRec / rounds}ms")
            T.check("detect $label", detOk >= rounds - 1)
            T.check("occupancy $label", occOk >= cellsTotal * 0.98)
            T.check("color $label", colorOk >= occTotal * 0.97)
            val need = if (refs.contains(testSet)) 0.97 else 0.80
            T.check("cells $label", cellsOk >= cellsTotal * need) { "${100.0 * cellsOk / cellsTotal}%" }
        }

        // Calibration: a start-position screenshot teaches an unknown style
        val calibSet = extra.firstOrNull()?.let { PieceSet(it.name, it) } ?: refSets[1]
        val base = PieceClassifier(ReferenceBuilder.build("cburnett", refSets[0]::render, ReferenceBuilder.THEME_SQUARES))
        val startSyn = synthesize(Position.start(), calibSet, 0xFFEEEED2.toInt(), 0xFF769656.toInt(), rnd, false, true)
        val det = BoardDetector.detect(startSyn.img)
        T.check("calib detect", det != null)
        if (det != null) {
            val learned = BoardRecognizer.calibrationSamples(BoardRecognizer.recognize(startSyn.img, det, base, false), "calib")
            T.check("calib samples", learned != null)
            if (learned != null) {
                val clf = PieceClassifier(ReferenceBuilder.build("cburnett", refSets[0]::render, ReferenceBuilder.THEME_SQUARES) + learned)
                var ok = 0; var tot = 0
                repeat(4) {
                    val syn = synthesize(randomPosition(rnd), calibSet, 0xFFEEEED2.toInt(), 0xFF769656.toInt(), rnd, false, true)
                    val d = BoardDetector.detect(syn.img) ?: return@repeat
                    val b = BoardRecognizer.recognize(syn.img, d, clf, false).board()
                    for (sq in 0 until 64) { tot++; if (b[sq] == syn.board[sq]) ok++ }
                }
                println("vision calibrated ${calibSet.name}: ${"%.1f".format(100.0 * ok / maxOf(1, tot))}%")
                T.check("calibrated accuracy", ok >= tot * 0.97)
            }
        }
    }
}
