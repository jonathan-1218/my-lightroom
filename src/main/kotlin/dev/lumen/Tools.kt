package dev.lumen

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot

/** S02–S05 photo view.
 *  normal: the developed, cropped photo; press and hold for the original.
 *  crop: the whole straightened photo with a draggable crop frame.
 *  mask: the selected mask's handles (or brush / colour picker) over the photo, its area tinted.
 *  retouch: heal / clone spots; tap to add, drag a circle to move it. */
class EditView(ctx: Context, private val onStart: () -> Unit, private val onChange: () -> Unit) : View(ctx) {
    var look: Look? = null; set(v) { field = v; v?.overlay = Ui.color("overlay").let { floatArrayOf(Color.red(it) / 255f, Color.green(it) / 255f, Color.blue(it) / 255f) }; invalidate() }
    var edit = Edit()
    var mode = "normal"; set(v) { field = v; invalidate() }
    var aspect: Float? = null // crop: locked output width/height in pixels, null = free
    var mask = -1             // mask: selected mask index
    var overlay = true        // mask: tint the selected mask's area
    var brushSize = 0.05f; var erase = false
    var heal = true; var spotSize = 0.03f
    var spot = -1; var onSpot: () -> Unit = {} // retouch: selected spot, and a hook so the panel can enable "Delete spot"

    private var before = false
    private val fit = Matrix()
    private var k = 1f; private var ox = 0f; private var oy = 0f; private var fw = 1; private var fh = 1
    private val scrim = Paint().apply { color = Ui.color("scrim") }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = Ui.dp(1).toFloat(); color = Ui.color("text") }
    private val dashed = Paint(line).apply { pathEffect = DashPathEffect(floatArrayOf(Ui.dp(4).toFloat(), Ui.dp(4).toFloat()), 0f) }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.color("accent") }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = Ui.dp(2).toFloat(); color = Ui.color("accent") }

    private fun frame(w: Int, h: Int) {
        fw = w; fh = h
        val inset = if (mode == "crop") 2f * Ui.space(5) else 0f // room to grab the corner handles
        k = minOf((width - inset) / w, (height - inset) / h)
        ox = (width - w * k) / 2; oy = (height - h * k) / 2
        fit.setScale(k, k); fit.postTranslate(ox, oy)
    }

    override fun onDraw(c: Canvas) {
        val l = look ?: return
        val w = l.src.width; val h = l.src.height
        if (before && mode == "normal") { frame(w, h); c.concat(fit); c.drawBitmap(l.base, 0f, 0f, null); return }
        val crop = mode == "crop"
        val (ow, oh) = if (crop) edit.oriented(w, h) else edit.outSize(w, h)
        frame(ow, oh)
        c.save(); c.concat(fit); l.draw(c, edit, withCrop = !crop, showMask = if (mode == "mask" && overlay) mask else -1); c.restore()
        when (mode) { "crop" -> cropOverlay(c); "mask" -> maskHandles(c); "retouch" -> spotHandles(c) }
    }

    // ---- source 0..1 ↔ view pixels ----

    private fun toView() = Matrix(edit.matrix(look!!.src.width, look!!.src.height)).apply { preScale(look!!.src.width.toFloat(), look!!.src.height.toFloat()); postConcat(fit) }
    private fun view(x: Float, y: Float) = floatArrayOf(x, y).also { toView().mapPoints(it) }
    private fun source(x: Float, y: Float) = floatArrayOf(x, y).also { Matrix().apply { toView().invert(this) }.mapPoints(it) }
    private fun near(p: FloatArray, x: Float, y: Float) = hypot(p[0] - x, p[1] - y) < Ui.dp(32)

    // ---- crop ----

    private fun box() = edit.crop.let { RectF(ox + it.left * fw * k, oy + it.top * fh * k, ox + it.right * fw * k, oy + it.bottom * fh * k) }

    private fun cropOverlay(c: Canvas) {
        val r = box()
        c.drawPath(Path().apply {
            fillType = Path.FillType.EVEN_ODD
            addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
            addRect(r, Path.Direction.CW)
        }, scrim)
        c.drawRect(r, line)
        for (i in 1..2) { // thirds
            c.drawLine(r.left + r.width() * i / 3, r.top, r.left + r.width() * i / 3, r.bottom, line)
            c.drawLine(r.left, r.top + r.height() * i / 3, r.right, r.top + r.height() * i / 3, line)
        }
        corners(r).forEach { (x, y) -> c.drawCircle(x, y, Ui.dp(7).toFloat(), handle) }
    }

    private fun corners(r: RectF) = listOf(r.left to r.top, r.right to r.top, r.right to r.bottom, r.left to r.bottom)

    // ---- masks ----

    private fun maskHandles(c: Canvas) {
        val m = edit.masks.getOrNull(mask) ?: return
        val g = m.g
        when (m.type) {
            "linear" -> {
                val a = view(g[0], g[1]); val b = view(g[2], g[3])
                val dx = b[0] - a[0]; val dy = b[1] - a[1]; val len = hypot(dx, dy).coerceAtLeast(1f)
                val px = -dy / len * width * 2; val py = dx / len * width * 2 // lines across the whole view, at right angles to a→b
                c.drawLine(a[0] - px, a[1] - py, a[0] + px, a[1] + py, line)
                c.drawLine(b[0] - px, b[1] - py, b[0] + px, b[1] + py, dashed)
                c.drawLine(a[0], a[1], b[0], b[1], dashed)
                c.drawCircle(a[0], a[1], Ui.dp(8).toFloat(), handle)
                c.drawCircle(b[0], b[1], Ui.dp(8).toFloat(), ring)
            }
            "radial" -> {
                c.drawPath(Path().apply {
                    for (i in 0..48) {
                        val t = i / 48f * 2 * Math.PI
                        val p = view(g[0] + g[2] * kotlin.math.cos(t).toFloat(), g[1] + g[3] * kotlin.math.sin(t).toFloat())
                        if (i == 0) moveTo(p[0], p[1]) else lineTo(p[0], p[1])
                    }
                }, line)
                view(g[0], g[1]).let { c.drawCircle(it[0], it[1], Ui.dp(8).toFloat(), handle) }
                view(g[0] + g[2], g[1]).let { c.drawCircle(it[0], it[1], Ui.dp(7).toFloat(), ring) }
                view(g[0], g[1] + g[3]).let { c.drawCircle(it[0], it[1], Ui.dp(7).toFloat(), ring) }
            }
            "color" -> if (g[3] > 0) { // the picked colour, as a swatch in the corner
                val sw = Paint().apply { color = Color.rgb((g[0] * 255).toInt(), (g[1] * 255).toInt(), (g[2] * 255).toInt()) }
                c.drawCircle(Ui.dp(28).toFloat(), Ui.dp(28).toFloat(), Ui.dp(14).toFloat(), sw)
                c.drawCircle(Ui.dp(28).toFloat(), Ui.dp(28).toFloat(), Ui.dp(14).toFloat(), line)
            }
        }
    }

    // ---- heal / clone ----

    private fun spotHandles(c: Canvas) {
        val l = look ?: return
        val long = maxOf(l.src.width, l.src.height)
        edit.retouch.forEachIndexed { i, saved ->
            val s = if (i == spot) dragging ?: saved else saved
            val d = view(s.dx, s.dy); val src = view(s.sx, s.sy)
            val r = s.r * long * k // spot radius in view pixels (straighten keeps scale)
            val p = if (i == spot) ring else line
            c.drawCircle(d[0], d[1], r, p)
            if (i == spot) { c.drawCircle(src[0], src[1], r, dashed); c.drawLine(src[0], src[1], d[0], d[1], dashed) }
        }
    }

    /** New spot at (x, y) with its source beside it, on whichever side stays inside the photo. */
    private fun addSpot(x: Float, y: Float) {
        val l = look ?: return
        val w = l.src.width.toFloat(); val h = l.src.height.toFloat()
        val rx = spotSize * maxOf(w, h) / w; val ry = spotSize * maxOf(w, h) / h
        val (sx, sy) = bestSource(l.src, x, y, spotSize) // the nearby patch whose surroundings match best
            ?: listOf(x + 2.5f * rx to y, x - 2.5f * rx to y, x to y + 2.5f * ry, x to y - 2.5f * ry)
                .firstOrNull { (a, b) -> a - rx >= 0 && a + rx <= 1 && b - ry >= 0 && b + ry <= 1 } ?: return
        if (x - rx < 0 || x + rx > 1 || y - ry < 0 || y + ry > 1) return
        onStart()
        edit.retouch = edit.retouch + Spot(x, y, sx, sy, spotSize, heal)
        spot = edit.retouch.size - 1
        onSpot(); invalidate(); onChange()
    }

    // ---- touch ----

    private var part = -1 // crop: 0..3 corner, 4 move · mask: 0 a / centre, 1 b / x-radius, 2 y-radius, 3 move line · retouch: 0 dst, 1 src
    private var started = false
    private var start = RectF(); private var downU = 0f; private var downV = 0f
    private var downG = FloatArray(4)
    private var downSpot: Spot? = null
    private var dragging: Spot? = null // the selected spot while a finger moves it, applied on release

    private fun begin() { if (!started) { started = true; onStart() } } // one undo step per gesture, none for a plain tap

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (look == null) return false
        when (mode) {
            "crop" -> cropTouch(e)
            "mask" -> maskTouch(e)
            "retouch" -> spotTouch(e)
            else -> { // press and hold: the original
                before = e.action == MotionEvent.ACTION_DOWN || e.action == MotionEvent.ACTION_MOVE
                invalidate()
            }
        }
        return true
    }

    private fun cropTouch(e: MotionEvent) {
        val u = (e.x - ox) / (k * fw); val v = (e.y - oy) / (k * fh)
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                val r = box()
                part = corners(r).indexOfFirst { (x, y) -> hypot(x - e.x, y - e.y) < Ui.dp(32) }
                if (part < 0 && r.contains(e.x, e.y)) part = 4
                if (part >= 0) { started = false; start = RectF(edit.crop); downU = u; downV = v }
            }
            MotionEvent.ACTION_MOVE -> if (part >= 0) {
                val want = if (part == 4) RectF(start).apply { offset(u - downU, v - downV) } else resize(u, v)
                begin()
                edit.crop = clampTo(start, want)
                invalidate(); onChange()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> part = -1
        }
    }

    private fun maskTouch(e: MotionEvent) {
        val m = edit.masks.getOrNull(mask) ?: return
        val (u, v) = source(e.x, e.y).let { it[0] to it[1] }
        when (m.type) {
            "brush" -> when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (m.strokes.sumOf { it.pts.size } >= MAX_MASK_POINTS * 2) return // full: start a new mask
                    started = false; begin()
                    m.strokes = m.strokes + Stroke(floatArrayOf(u, v), brushSize, erase)
                    invalidate(); onChange()
                }
                MotionEvent.ACTION_MOVE -> {
                    val s = m.strokes.lastOrNull() ?: return
                    // keep a point only when the finger has really moved, and stop at the cap: the edit stays small
                    if (hypot(u - s.pts[s.pts.size - 2], v - s.pts[s.pts.size - 1]) < 0.002f) return
                    if (m.strokes.sumOf { it.pts.size } >= MAX_MASK_POINTS * 2) return
                    m.strokes = m.strokes.dropLast(1) + Stroke(s.pts + floatArrayOf(u, v), s.size, s.erase)
                    invalidate(); onChange()
                }
            }
            "color" -> if (e.action == MotionEvent.ACTION_DOWN && u in 0f..1f && v in 0f..1f) { // pick the colour under the finger
                val l = look!!
                val px = l.src.getPixel((u * (l.src.width - 1)).toInt(), (v * (l.src.height - 1)).toInt())
                started = false; begin()
                m.g = floatArrayOf(Color.red(px) / 255f, Color.green(px) / 255f, Color.blue(px) / 255f, 1f)
                invalidate(); onChange()
            }
            "linear", "radial" -> when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    val g = m.g
                    val pts = if (m.type == "linear") listOf(view(g[0], g[1]), view(g[2], g[3]), view((g[0] + g[2]) / 2, (g[1] + g[3]) / 2))
                        else listOf(view(g[0], g[1]), view(g[0] + g[2], g[1]), view(g[0], g[1] + g[3]))
                    part = pts.indexOfFirst { near(it, e.x, e.y) }
                    if (m.type == "linear" && part == 2) part = 3
                    if (part < 0 && m.type == "radial" && hypot((u - g[0]) / g[2], (v - g[1]) / g[3]) < 1) part = 0 // inside: move
                    started = false; downU = u; downV = v; downG = g.copyOf()
                }
                MotionEvent.ACTION_MOVE -> if (part >= 0) {
                    begin()
                    val du = u - downU; val dv = v - downV; val g = downG.copyOf()
                    when {
                        m.type == "linear" && part == 0 -> { g[0] += du; g[1] += dv }
                        m.type == "linear" && part == 1 -> { g[2] += du; g[3] += dv }
                        m.type == "linear" -> { g[0] += du; g[1] += dv; g[2] += du; g[3] += dv }
                        part == 0 -> { g[0] += du; g[1] += dv }
                        part == 1 -> g[2] = abs(u - g[0]).coerceAtLeast(0.01f)
                        else -> g[3] = abs(v - g[1]).coerceAtLeast(0.01f)
                    }
                    m.g = g
                    invalidate(); onChange()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> part = -1
            }
        }
    }

    private fun spotTouch(e: MotionEvent) {
        val (u, v) = source(e.x, e.y).let { it[0] to it[1] }
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                started = false; downU = u; downV = v
                val sel = edit.retouch.getOrNull(spot)
                part = when {
                    sel != null && near(view(sel.sx, sel.sy), e.x, e.y) -> 1
                    else -> edit.retouch.indexOfLast { near(view(it.dx, it.dy), e.x, e.y) }.let { i -> if (i >= 0) { spot = i; onSpot(); 0 } else -1 }
                }
                downSpot = edit.retouch.getOrNull(spot)
                if (part < 0 && u in 0f..1f && v in 0f..1f) addSpot(u, v) else invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (part >= 0) {
                val s = downSpot ?: return
                dragging = if (part == 0) s.copy(dx = s.dx + u - downU, dy = s.dy + v - downV) else s.copy(sx = s.sx + u - downU, sy = s.sy + v - downV)
                invalidate() // only the circles move while dragging; re-baking the photo per move stalls the UI
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging?.let { moved -> begin(); edit.retouch = edit.retouch.toMutableList().also { it[spot] = moved }; onChange() }
                dragging = null; part = -1; invalidate()
            }
        }
    }

    /** Drag corner `part` to (u, v), keeping the opposite corner fixed and the aspect if locked. */
    private fun resize(u: Float, v: Float): RectF {
        val (ax, ay) = corners(start)[(part + 2) % 4]
        val min = 0.05f
        var w = abs(u - ax).coerceAtLeast(min); var h = abs(v - ay).coerceAtLeast(min)
        aspect?.let { a -> h = w * fw / (a * fh); if (h < min) { h = min; w = h * a * fh / fw } }
        val x = if (u < ax) ax - w else ax; val y = if (v < ay) ay - h else ay
        return RectF(x, y, x + w, y + h)
    }

    /** Furthest point from `from` toward `to` that still fits inside the photo. */
    private fun clampTo(from: RectF, to: RectF): RectF {
        fun at(t: Float) = RectF(from.left + (to.left - from.left) * t, from.top + (to.top - from.top) * t,
            from.right + (to.right - from.right) * t, from.bottom + (to.bottom - from.bottom) * t)
        if (edit.fits(to, fw, fh)) return to
        var lo = 0f; var hi = 1f
        repeat(16) { val m = (lo + hi) / 2; if (edit.fits(at(m), fw, fh)) lo = m else hi = m }
        return at(lo)
    }
}

/** Tone curve editor. Tap empty space to add a point, drag to move, double-tap a point to remove it. */
class CurveView(ctx: Context, private val edit: () -> Edit, private val onStart: () -> Unit, private val onChange: () -> Unit) : View(ctx) {
    var channel = 0; set(v) { field = v; invalidate() }
    private val pad = Ui.dp(12).toFloat()
    private val grid = Paint().apply { color = Ui.color("border"); strokeWidth = Ui.dp(1).toFloat() }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = Ui.dp(2).toFloat() }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private var drag = -1
    private var lastTap = 0L; private var lastIdx = -1
    private var started = false

    init { contentDescription = "Tone curve. Use Reset curve to undo all points." }

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        setMeasuredDimension(width, minOf(width, Ui.dp(236))) // fits the 300dp panel under the channel chips
    }

    private fun color() = Ui.color(listOf("text", "label-red", "label-green", "label-blue")[channel])
    private fun sx(x: Float) = pad + x * (width - 2 * pad)
    private fun sy(y: Float) = height - pad - y * (height - 2 * pad)

    override fun onDraw(c: Canvas) {
        for (i in 0..4) {
            c.drawLine(sx(i / 4f), sy(0f), sx(i / 4f), sy(1f), grid)
            c.drawLine(sx(0f), sy(i / 4f), sx(1f), sy(i / 4f), grid)
        }
        val pts = edit().curves[channel]
        val f = curveFn(pts)
        stroke.color = color(); dot.color = color()
        c.drawPath(Path().apply {
            moveTo(sx(0f), sy(f(0f)))
            for (i in 1..64) lineTo(sx(i / 64f), sy(f(i / 64f)))
        }, stroke)
        pts.forEach { (x, y) -> c.drawCircle(sx(x), sy(y), Ui.dp(6).toFloat(), dot) }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent.requestDisallowInterceptTouchEvent(true) // keep the panel's ScrollView from stealing vertical drags
        val x = ((e.x - pad) / (width - 2 * pad)).coerceIn(0f, 1f)
        val y = ((height - pad - e.y) / (height - 2 * pad)).coerceIn(0f, 1f)
        val pts = edit().curves[channel].toMutableList()
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                started = false
                drag = pts.indices.minByOrNull { hypot(sx(pts[it].first) - e.x, sy(pts[it].second) - e.y) }
                    ?.takeIf { hypot(sx(pts[it].first) - e.x, sy(pts[it].second) - e.y) < Ui.dp(24) } ?: -1
                val now = System.currentTimeMillis()
                if (drag > 0 && drag < pts.size - 1 && drag == lastIdx && now - lastTap < 300) { // double-tap removes
                    pts.removeAt(drag); drag = -1; set(pts); lastIdx = -1; return true
                }
                lastTap = now; lastIdx = drag
                if (drag < 0) { // add a point on the curve under the finger
                    drag = pts.indexOfFirst { it.first > x }.takeIf { it > 0 } ?: return true
                    pts.add(drag, x to curveFn(pts)(x)); set(pts)
                }
            }
            MotionEvent.ACTION_MOVE -> if (drag >= 0) {
                val nx = when (drag) { 0 -> 0f; pts.size - 1 -> 1f; else -> x.coerceIn(pts[drag - 1].first + 0.01f, pts[drag + 1].first - 0.01f) }
                pts[drag] = nx to y; set(pts)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> drag = -1
        }
        return true
    }

    private fun set(pts: List<Pair<Float, Float>>) {
        if (!started) { started = true; onStart() } // one undo step per gesture
        edit().curves = edit().curves.toMutableList().also { it[channel] = pts }
        invalidate(); onChange()
    }
}

/** RGB histogram of the edited photo, three translucent layers. */
class HistogramView(ctx: Context) : View(ctx) {
    var data: Array<IntArray>? = null; set(v) { field = v; invalidate() }
    private val fills = listOf("label-red", "label-green", "label-blue").map { k ->
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = (Ui.color(k) and 0x00FFFFFF) or 0x66000000 }
    }
    init { contentDescription = "Histogram"; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), Ui.dp(64))

    override fun onDraw(c: Canvas) {
        val d = data ?: return
        // ignore the two end bins when scaling, so a clipped sky doesn't flatten the rest
        val top = d.maxOf { ch -> (1..254).maxOf { ch[it] } }.coerceAtLeast(1).toFloat()
        val pad = Ui.space(4).toFloat(); val w = width - 2 * pad; val h = height.toFloat()
        d.forEachIndexed { i, ch ->
            c.drawPath(Path().apply {
                moveTo(pad, h)
                for (b in 0..255) lineTo(pad + b / 255f * w, h - (ch[b] / top).coerceAtMost(1f) * h)
                lineTo(pad + w, h); close()
            }, fills[i])
        }
    }
}
