package com.chessvision.core.svg

import kotlin.math.*

/**
 * Minimal SVG parser sufficient for chess piece sets (path/circle/ellipse/rect, groups with
 * inherited presentation attributes). Paths are normalized to absolute M/L/C/Q/Z commands.
 */
sealed class PathCmd {
    data class MoveTo(val x: Float, val y: Float) : PathCmd()
    data class LineTo(val x: Float, val y: Float) : PathCmd()
    data class CubicTo(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val x: Float, val y: Float) : PathCmd()
    data class QuadTo(val x1: Float, val y1: Float, val x: Float, val y: Float) : PathCmd()
    object Close : PathCmd()
}

enum class LineCap { BUTT, ROUND, SQUARE }
enum class LineJoin { MITER, ROUND, BEVEL }

/** Linear gradient in user space (gradientUnits="userSpaceOnUse"). */
data class LinearGradient(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val colors: IntArray, val offsets: FloatArray)

data class SvgShape(
    val cmds: List<PathCmd>,
    /** ARGB or null for none. */
    val fill: Int?,
    val fillGradient: LinearGradient? = null,
    val stroke: Int?,
    val strokeWidth: Float,
    val cap: LineCap,
    val join: LineJoin,
    val evenOdd: Boolean,
)

data class SvgDocument(val minX: Float, val minY: Float, val width: Float, val height: Float, val shapes: List<SvgShape>)

object SvgParser {
    private data class Style(
        val fill: String = "#000",
        val stroke: String = "none",
        val strokeWidth: Float = 1f,
        val cap: LineCap = LineCap.BUTT,
        val join: LineJoin = LineJoin.MITER,
        val evenOdd: Boolean = false,
        val opacity: Float = 1f,
        val fillOpacity: Float = 1f,
        val strokeOpacity: Float = 1f,
    )

    private val tagRe = Regex("""<(/?)([a-zA-Z][\w:-]*)((?:[^>"']|"[^"]*"|'[^']*')*?)(/?)>""")
    private val attrRe = Regex("""([\w:-]+)\s*=\s*("([^"]*)"|'([^']*)')""")

    fun parse(text: String): SvgDocument {
        val stack = ArrayList<Style>()
        stack.add(Style())
        val shapes = ArrayList<SvgShape>()
        var vb = floatArrayOf(0f, 0f, 45f, 45f)
        var skipDepth = 0
        val gradients = HashMap<String, LinearGradient>()
        var curGrad: Pair<String, FloatArray>? = null
        val curStops = ArrayList<Pair<Float, Int>>()
        val skipTags = setOf("radialGradient", "filter", "clipPath", "mask", "pattern", "title", "desc", "metadata")
        for (m in tagRe.findAll(text)) {
            val closing = m.groupValues[1] == "/"
            val name = m.groupValues[2].substringAfter(':')
            val selfClose = m.groupValues[4] == "/"
            val attrs = HashMap<String, String>()
            for (a in attrRe.findAll(m.groupValues[3])) attrs[a.groupValues[1]] = a.groupValues[3].ifEmpty { a.groupValues[4] }
            if (name == "linearGradient") {
                if (closing) {
                    curGrad?.let { (id, g) ->
                        if (curStops.isNotEmpty()) gradients[id] = LinearGradient(g[0], g[1], g[2], g[3],
                            curStops.map { it.second }.toIntArray(), curStops.map { it.first }.toFloatArray())
                    }
                    curGrad = null
                } else {
                    curStops.clear()
                    val id = attrs["id"] ?: ""
                    val ref = attrs["xlink:href"] ?: attrs["href"]
                    val base = ref?.removePrefix("#")?.let { gradients[it] }
                    curGrad = id to floatArrayOf(attrs.f("x1"), attrs.f("y1"), attrs["x2"]?.let { attrs.f("x2") } ?: 1f, attrs.f("y2"))
                    if (base != null) { base.colors.zip(base.offsets.toList()).forEach { curStops.add(it.second to it.first) } }
                    if (selfClose) {
                        if (base != null) gradients[id] = base.copy(x1 = curGrad!!.second[0], y1 = curGrad!!.second[1], x2 = curGrad!!.second[2], y2 = curGrad!!.second[3])
                        curGrad = null
                    }
                }
                continue
            }
            if (name == "stop" && curGrad != null) {
                val st = HashMap(attrs)
                attrs["style"]?.split(';')?.forEach { kv -> val i = kv.indexOf(':'); if (i > 0) st[kv.substring(0, i).trim()] = kv.substring(i + 1).trim() }
                val off = st["offset"]?.let { if (it.endsWith("%")) (it.removeSuffix("%").toFloatOrNull() ?: 0f) / 100f else it.toFloatOrNull() } ?: 0f
                val col = parseColor(st["stop-color"] ?: "#000", st["stop-opacity"]?.toFloatOrNull() ?: 1f) ?: (parseColor(st["stop-color"] ?: "#000", 1f)!! and 0x00FFFFFF)
                curStops.add(off to col)
                continue
            }
            if (name in skipTags) {
                if (closing) skipDepth = max(0, skipDepth - 1) else if (!selfClose) skipDepth++
                continue
            }
            if (skipDepth > 0) continue
            if (closing) {
                if (name == "g" || name == "svg") if (stack.size > 1) stack.removeAt(stack.lastIndex)
                continue
            }
            val style = applyStyle(stack.last(), attrs)
            when (name) {
                "svg" -> {
                    attrs["viewBox"]?.let { v ->
                        val n = v.trim().split(Regex("[\\s,]+")).mapNotNull { it.toFloatOrNull() }
                        if (n.size == 4) vb = n.toFloatArray()
                    }
                    if (!selfClose) stack.add(style)
                }
                "g" -> if (!selfClose) stack.add(style)
                "path" -> attrs["d"]?.let { d -> addShape(shapes, parsePath(d), style, gradients) }
                "circle" -> {
                    val cx = attrs.f("cx"); val cy = attrs.f("cy"); val r = attrs.f("r")
                    if (r > 0) addShape(shapes, ellipse(cx, cy, r, r), style, gradients)
                }
                "ellipse" -> {
                    val rx = attrs.f("rx"); val ry = attrs.f("ry")
                    if (rx > 0 && ry > 0) addShape(shapes, ellipse(attrs.f("cx"), attrs.f("cy"), rx, ry), style, gradients)
                }
                "rect" -> {
                    val x = attrs.f("x"); val y = attrs.f("y"); val w = attrs.f("width"); val h = attrs.f("height")
                    if (w > 0 && h > 0) addShape(shapes, listOf(PathCmd.MoveTo(x, y), PathCmd.LineTo(x + w, y),
                        PathCmd.LineTo(x + w, y + h), PathCmd.LineTo(x, y + h), PathCmd.Close), style, gradients)
                }
            }
        }
        return SvgDocument(vb[0], vb[1], vb[2], vb[3], shapes)
    }

    private fun Map<String, String>.f(k: String) = this[k]?.trim()?.removeSuffix("px")?.toFloatOrNull() ?: 0f

    private fun applyStyle(parent: Style, attrsIn: Map<String, String>): Style {
        val attrs = HashMap(attrsIn)
        attrsIn["style"]?.split(';')?.forEach { kv ->
            val i = kv.indexOf(':')
            if (i > 0) attrs[kv.substring(0, i).trim()] = kv.substring(i + 1).trim()
        }
        var s = parent.copy(opacity = 1f)
        attrs["fill"]?.let { s = s.copy(fill = it) }
        attrs["stroke"]?.let { s = s.copy(stroke = it) }
        attrs["stroke-width"]?.removeSuffix("px")?.toFloatOrNull()?.let { s = s.copy(strokeWidth = it) }
        attrs["stroke-linecap"]?.let { s = s.copy(cap = when (it) { "round" -> LineCap.ROUND; "square" -> LineCap.SQUARE; else -> LineCap.BUTT }) }
        attrs["stroke-linejoin"]?.let { s = s.copy(join = when (it) { "round" -> LineJoin.ROUND; "bevel" -> LineJoin.BEVEL; else -> LineJoin.MITER }) }
        attrs["fill-rule"]?.let { s = s.copy(evenOdd = it == "evenodd") }
        attrs["fill-opacity"]?.toFloatOrNull()?.let { s = s.copy(fillOpacity = it) }
        attrs["stroke-opacity"]?.toFloatOrNull()?.let { s = s.copy(strokeOpacity = it) }
        val op = attrs["opacity"]?.toFloatOrNull() ?: 1f
        return s.copy(opacity = parent.opacity * op)
    }

    private fun addShape(out: MutableList<SvgShape>, cmds: List<PathCmd>, s: Style, gradients: Map<String, LinearGradient>) {
        if (cmds.isEmpty() || s.opacity <= 0.01f) return
        var fill = parseColor(s.fill, s.fillOpacity * s.opacity)
        var grad: LinearGradient? = null
        val urlId = Regex("url\\(\\s*#([^)\\s]+)\\s*\\)").find(s.fill)?.groupValues?.get(1)
        if (urlId != null) {
            grad = gradients[urlId]?.let { g ->
                val k = s.fillOpacity * s.opacity
                g.copy(colors = g.colors.map { c -> ((((c ushr 24) * k).toInt().coerceIn(0, 255)) shl 24) or (c and 0xFFFFFF) }.toIntArray())
            }
            fill = null
        }
        val stroke = parseColor(s.stroke, s.strokeOpacity * s.opacity)
        if (fill == null && stroke == null && grad == null) return
        out.add(SvgShape(cmds, fill, grad, stroke, s.strokeWidth, s.cap, s.join, s.evenOdd))
    }

    fun parseColor(c: String, opacity: Float): Int? {
        val v = c.trim().lowercase()
        if (v == "none" || v.startsWith("url(") || v == "transparent") return null
        val rgb: Int = when {
            v.startsWith("#") && v.length == 4 -> {
                val r = v[1].digitToInt(16); val g = v[2].digitToInt(16); val b = v[3].digitToInt(16)
                (r * 17 shl 16) or (g * 17 shl 8) or (b * 17)
            }
            v.startsWith("#") && v.length >= 7 -> v.substring(1, 7).toInt(16)
            v.startsWith("rgb(") -> {
                val n = v.removePrefix("rgb(").removeSuffix(")").split(',').map { it.trim().toIntOrNull() ?: 0 }
                (n.getOrElse(0) { 0 } shl 16) or (n.getOrElse(1) { 0 } shl 8) or n.getOrElse(2) { 0 }
            }
            v == "white" -> 0xFFFFFF
            v == "black" -> 0
            v == "currentcolor" -> 0
            else -> 0
        }
        val a = (opacity.coerceIn(0f, 1f) * 255).roundToInt()
        if (a == 0) return null
        return (a shl 24) or rgb
    }

    private fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float): List<PathCmd> {
        val k = 0.5522848f
        return listOf(
            PathCmd.MoveTo(cx + rx, cy),
            PathCmd.CubicTo(cx + rx, cy + ry * k, cx + rx * k, cy + ry, cx, cy + ry),
            PathCmd.CubicTo(cx - rx * k, cy + ry, cx - rx, cy + ry * k, cx - rx, cy),
            PathCmd.CubicTo(cx - rx, cy - ry * k, cx - rx * k, cy - ry, cx, cy - ry),
            PathCmd.CubicTo(cx + rx * k, cy - ry, cx + rx, cy - ry * k, cx + rx, cy),
            PathCmd.Close,
        )
    }

    // ---------- path data ----------
    private class Tok(val s: String) {
        var i = 0
        fun skipSep() { while (i < s.length && (s[i].isWhitespace() || s[i] == ',')) i++ }
        fun peekCmd(): Char? { skipSep(); return if (i < s.length && s[i].isLetter()) s[i] else null }
        fun hasNumber(): Boolean { skipSep(); return i < s.length && (s[i].isDigit() || s[i] == '-' || s[i] == '+' || s[i] == '.') }
        fun num(): Float {
            skipSep()
            val st = i
            if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
            var dot = false
            while (i < s.length) {
                val c = s[i]
                if (c.isDigit()) i++
                else if (c == '.' && !dot) { dot = true; i++ }
                else if ((c == 'e' || c == 'E') && i + 1 < s.length) {
                    i++
                    if (s[i] == '-' || s[i] == '+') i++
                    while (i < s.length && s[i].isDigit()) i++
                    break
                } else break
            }
            return s.substring(st, i).toFloatOrNull() ?: 0f
        }
        fun flag(): Boolean { skipSep(); val c = s.getOrNull(i); i++; return c == '1' }
    }

    fun parsePath(d: String): List<PathCmd> {
        val out = ArrayList<PathCmd>()
        val t = Tok(d)
        var cx = 0f; var cy = 0f; var sx = 0f; var sy = 0f
        var lcx = 0f; var lcy = 0f // last control point
        var prev = ' '
        var cmd = ' '
        while (true) {
            val c = t.peekCmd()
            if (c != null) { cmd = c; t.i++ } else if (!t.hasNumber()) break
            val rel = cmd.isLowerCase()
            fun ax(v: Float) = if (rel) cx + v else v
            fun ay(v: Float) = if (rel) cy + v else v
            when (cmd.uppercaseChar()) {
                'M' -> {
                    val x = ax(t.num()); val y = ay(t.num())
                    out.add(PathCmd.MoveTo(x, y)); cx = x; cy = y; sx = x; sy = y
                    cmd = if (rel) 'l' else 'L'
                    prev = 'M'; continue
                }
                'L' -> { val x = ax(t.num()); val y = ay(t.num()); out.add(PathCmd.LineTo(x, y)); cx = x; cy = y }
                'H' -> { val x = if (rel) cx + t.num() else t.num(); out.add(PathCmd.LineTo(x, cy)); cx = x }
                'V' -> { val y = if (rel) cy + t.num() else t.num(); out.add(PathCmd.LineTo(cx, y)); cy = y }
                'C' -> {
                    val x1 = ax(t.num()); val y1 = ay(t.num()); val x2 = ax(t.num()); val y2 = ay(t.num())
                    val x = ax(t.num()); val y = ay(t.num())
                    out.add(PathCmd.CubicTo(x1, y1, x2, y2, x, y)); lcx = x2; lcy = y2; cx = x; cy = y
                }
                'S' -> {
                    val (x1, y1) = if (prev.uppercaseChar() in "CS") (2 * cx - lcx) to (2 * cy - lcy) else cx to cy
                    val x2 = ax(t.num()); val y2 = ay(t.num()); val x = ax(t.num()); val y = ay(t.num())
                    out.add(PathCmd.CubicTo(x1, y1, x2, y2, x, y)); lcx = x2; lcy = y2; cx = x; cy = y
                }
                'Q' -> {
                    val x1 = ax(t.num()); val y1 = ay(t.num()); val x = ax(t.num()); val y = ay(t.num())
                    out.add(PathCmd.QuadTo(x1, y1, x, y)); lcx = x1; lcy = y1; cx = x; cy = y
                }
                'T' -> {
                    val (x1, y1) = if (prev.uppercaseChar() in "QT") (2 * cx - lcx) to (2 * cy - lcy) else cx to cy
                    val x = ax(t.num()); val y = ay(t.num())
                    out.add(PathCmd.QuadTo(x1, y1, x, y)); lcx = x1; lcy = y1; cx = x; cy = y
                }
                'A' -> {
                    val rx = t.num(); val ry = t.num(); val rot = t.num()
                    val large = t.flag(); val sweep = t.flag()
                    val x = ax(t.num()); val y = ay(t.num())
                    arcToCubics(cx, cy, rx, ry, rot, large, sweep, x, y, out)
                    cx = x; cy = y
                }
                'Z' -> { out.add(PathCmd.Close); cx = sx; cy = sy; prev = 'Z'; if (t.peekCmd() == null && !t.hasNumber()) break; continue }
                else -> { t.i++ }
            }
            prev = cmd
        }
        return out
    }

    private fun arcToCubics(
        x1: Float, y1: Float, rxIn: Float, ryIn: Float, angleDeg: Float, large: Boolean, sweep: Boolean,
        x2: Float, y2: Float, out: MutableList<PathCmd>,
    ) {
        if (x1 == x2 && y1 == y2) return
        var rx = abs(rxIn.toDouble()); var ry = abs(ryIn.toDouble())
        if (rx == 0.0 || ry == 0.0) { out.add(PathCmd.LineTo(x2, y2)); return }
        val phi = Math.toRadians(angleDeg.toDouble())
        val cosP = cos(phi); val sinP = sin(phi)
        val dx = (x1 - x2) / 2.0; val dy = (y1 - y2) / 2.0
        val x1p = cosP * dx + sinP * dy
        val y1p = -sinP * dx + cosP * dy
        val lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
        if (lambda > 1) { val s = sqrt(lambda); rx *= s; ry *= s }
        val num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
        val den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
        var coef = if (den == 0.0) 0.0 else sqrt(max(0.0, num / den))
        if (large == sweep) coef = -coef
        val cxp = coef * (rx * y1p / ry)
        val cyp = coef * -(ry * x1p / rx)
        val cxc = cosP * cxp - sinP * cyp + (x1 + x2) / 2.0
        val cyc = sinP * cxp + cosP * cyp + (y1 + y2) / 2.0
        fun ang(ux: Double, uy: Double, vx: Double, vy: Double): Double {
            val a = atan2(ux * vy - uy * vx, ux * vx + uy * vy)
            return a
        }
        val th1 = ang(1.0, 0.0, (x1p - cxp) / rx, (y1p - cyp) / ry)
        var dth = ang((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
        if (!sweep && dth > 0) dth -= 2 * PI
        if (sweep && dth < 0) dth += 2 * PI
        val segs = ceil(abs(dth) / (PI / 2)).toInt().coerceAtLeast(1)
        val delta = dth / segs
        val t = 4.0 / 3.0 * tan(delta / 4)
        var th = th1
        for (i in 0 until segs) {
            val c1 = cos(th); val s1 = sin(th)
            val c2 = cos(th + delta); val s2 = sin(th + delta)
            val ex1 = c1 - t * s1; val ey1 = s1 + t * c1
            val ex2 = c2 + t * s2; val ey2 = s2 - t * c2
            fun mapX(px: Double, py: Double) = (cxc + rx * px * cosP - ry * py * sinP).toFloat()
            fun mapY(px: Double, py: Double) = (cyc + rx * px * sinP + ry * py * cosP).toFloat()
            out.add(PathCmd.CubicTo(mapX(ex1, ey1), mapY(ex1, ey1), mapX(ex2, ey2), mapY(ex2, ey2), mapX(c2, s2), mapY(c2, s2)))
            th += delta
        }
    }
}
