package dev.lumen

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot

/** S02/S03 photo view. Normal: the developed, cropped photo; press and hold for the original.
 *  Crop mode: the whole straightened photo with a draggable crop frame. */
class EditView(ctx: Context, private val onStart: () -> Unit, private val onChange: () -> Unit) : View(ctx) {
    var look: Look? = null; set(v) { field = v; invalidate() }
    var edit = Edit()
    var cropMode = false; set(v) { field = v; invalidate() }
    var aspect: Float? = null // locked output width/height in pixels, null = free

    private var before = false
    private val fit = Matrix()
    private var k = 1f; private var ox = 0f; private var oy = 0f; private var fw = 1; private var fh = 1
    private val scrim = Paint().apply { color = Ui.color("scrim") }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = Ui.dp(1).toFloat(); color = Ui.color("text") }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.color("accent") }

    private fun frame(w: Int, h: Int) {
        fw = w; fh = h
        val inset = if (cropMode) 2f * Ui.space(5) else 0f // room to grab the corner handles
        k = minOf((width - inset) / w, (height - inset) / h)
        ox = (width - w * k) / 2; oy = (height - h * k) / 2
        fit.setScale(k, k); fit.postTranslate(ox, oy)
    }

    override fun onDraw(c: Canvas) {
        val l = look ?: return
        val w = l.src.width; val h = l.src.height
        if (before && !cropMode) { frame(w, h); c.concat(fit); c.drawBitmap(l.src, 0f, 0f, null); return }
        val (ow, oh) = if (cropMode) edit.oriented(w, h) else edit.outSize(w, h)
        frame(ow, oh)
        c.save(); c.concat(fit); l.draw(c, edit, withCrop = !cropMode); c.restore()
        if (cropMode) overlay(c)
    }

    private fun box() = edit.crop.let { RectF(ox + it.left * fw * k, oy + it.top * fh * k, ox + it.right * fw * k, oy + it.bottom * fh * k) }

    private fun overlay(c: Canvas) {
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

    private var mode = -1 // 0..3 corner, 4 move, -1 none
    private var started = false
    private var start = RectF(); private var downU = 0f; private var downV = 0f

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (look == null) return false
        if (!cropMode) { // press and hold: the original
            before = e.action == MotionEvent.ACTION_DOWN || e.action == MotionEvent.ACTION_MOVE
            invalidate(); return true
        }
        val u = (e.x - ox) / (k * fw); val v = (e.y - oy) / (k * fh)
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                val r = box()
                mode = corners(r).indexOfFirst { (x, y) -> hypot(x - e.x, y - e.y) < Ui.dp(32) }
                if (mode < 0 && r.contains(e.x, e.y)) mode = 4
                if (mode >= 0) { started = false; start = RectF(edit.crop); downU = u; downV = v }
            }
            MotionEvent.ACTION_MOVE -> if (mode >= 0) {
                val want = if (mode == 4) RectF(start).apply { offset(u - downU, v - downV) } else resize(u, v)
                if (!started) { started = true; onStart() } // one undo step per drag, none for a plain tap
                edit.crop = clampTo(start, want)
                invalidate(); onChange()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> mode = -1
        }
        return true
    }

    /** Drag corner `mode` to (u, v), keeping the opposite corner fixed and the aspect if locked. */
    private fun resize(u: Float, v: Float): RectF {
        val (ax, ay) = corners(start)[(mode + 2) % 4]
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
