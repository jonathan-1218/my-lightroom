package dev.lumen

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Luminance (0..1) of a small copy, long edge at most `max`. */
private fun luma(src: Bitmap, max: Int): Triple<FloatArray, Int, Int> {
    val k = minOf(1f, max.toFloat() / maxOf(src.width, src.height))
    val b = if (k < 1) Bitmap.createScaledBitmap(src, (src.width * k).roundToInt().coerceAtLeast(1), (src.height * k).roundToInt().coerceAtLeast(1), true) else src
    val px = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
    return Triple(FloatArray(px.size) { (0.2126f * Color.red(px[it]) + 0.7152f * Color.green(px[it]) + 0.0722f * Color.blue(px[it])) / 255f }, b.width, b.height)
}

/** Auto: slider values from the photo's brightness spread. Sets light sliders and a little vibrance. */
fun autoTone(src: Bitmap): Map<String, Float> {
    val (l, _, _) = luma(src, 256)
    val sorted = l.sortedArray()
    fun pct(p: Float) = sorted[((sorted.size - 1) * p).roundToInt()]
    val lo = pct(0.005f); val hi = pct(0.995f)
    val mean = l.average().toFloat().coerceAtLeast(0.02f) // mean, not median: a mostly-black frame shouldn't be dragged to grey
    // ponytail: one global target; a scene-aware target (night, snow) is the upgrade if Auto feels flat
    val ev = (0.6f * ln(0.42f.pow(2.2f) / mean.pow(2.2f)) / ln(2f)).coerceIn(-1.2f, 1.2f) // go 60% of the way: Auto is a starting point
    val gain = 2f.pow(ev / 2.2f)
    val clipped = l.count { it > 0.97f }.toFloat() / l.size
    val dark = l.count { it < 0.05f }.toFloat() / l.size
    return mapOf(
        "exposure" to ev / 2,
        "whites" to ((1 - (hi * gain).coerceAtMost(1.2f) / 0.98f) / 0.15f).coerceIn(-0.5f, 0.5f),
        "blacks" to (-((lo * gain) - 0.01f) / 0.15f).coerceIn(-0.5f, 0.5f),
        "contrast" to (0.3f * (0.8f - (hi - lo))).coerceIn(-0.3f, 0.3f),
        "highlights" to if (clipped > 0.02f) -0.3f else 0f,
        "shadows" to if (dark > 0.1f) 0.3f else 0f,
        "vibrance" to 0.15f,
    )
}

/**
 * Level: the straighten value that makes the photo's dominant near-horizontal or near-vertical lines straight.
 * For each candidate angle, edge points are projected across the lines; the right angle stacks them into the sharpest peaks.
 */
fun autoLevel(src: Bitmap): Float {
    val (l, w, h) = luma(src, 400)
    val hx = ArrayList<Float>(); val hy = ArrayList<Float>() // edges of near-horizontal lines
    val vx = ArrayList<Float>(); val vy = ArrayList<Float>() // edges of near-vertical lines
    for (y in 1 until h - 1) for (x in 1 until w - 1) {
        val gx = l[y * w + x + 1] - l[y * w + x - 1]
        val gy = l[(y + 1) * w + x] - l[(y - 1) * w + x]
        if (hypot(gx, gy) < 0.15f) continue
        if (abs(gy) > 2 * abs(gx)) { hx += x.toFloat(); hy += y.toFloat() } else if (abs(gx) > 2 * abs(gy)) { vx += x.toFloat(); vy += y.toFloat() }
    }
    if (hx.size + vx.size < 50) return 0f // nothing line-like to level on
    val span = (w + h) * 2
    fun score(xs: List<Float>, ys: List<Float>, nx: Float, ny: Float): Double {
        val bins = IntArray(span)
        for (i in xs.indices) bins[((xs[i] * nx + ys[i] * ny) + span / 2).toInt().coerceIn(0, span - 1)]++
        return bins.sumOf { it.toDouble() * it }
    }
    var best = 0f; var bestScore = -1.0
    var t = -20f
    while (t <= 20f) {
        val r = Math.toRadians(t.toDouble()); val c = kotlin.math.cos(r).toFloat(); val sn = kotlin.math.sin(r).toFloat()
        val sc = score(hx, hy, -sn, c) + score(vx, vy, c, sn) // normals of lines turned by t
        if (sc > bestScore) { bestScore = sc; best = t }
        t += 0.25f
    }
    return (-best / 45f).coerceIn(-1f, 1f) // straighten turns the opposite way
}

/** Ring brightness difference between a spot at (dx, dy) and a candidate source at (sx, sy), in pixels of `l`. */
private fun ringCost(l: FloatArray, w: Int, h: Int, dx: Float, dy: Float, sx: Float, sy: Float, r: Float): Float {
    var cost = 0f; var n = 0
    for (i in 0 until 48) {
        val t = i / 48f * 2 * Math.PI
        for (rr in listOf(1.1f, 1.3f)) {
            val ox = (kotlin.math.cos(t) * r * rr).toFloat(); val oy = (kotlin.math.sin(t) * r * rr).toFloat()
            val ax = (dx + ox).roundToInt(); val ay = (dy + oy).roundToInt(); val bx = (sx + ox).roundToInt(); val by = (sy + oy).roundToInt()
            if (ax !in 0 until w || ay !in 0 until h || bx !in 0 until w || by !in 0 until h) return Float.MAX_VALUE
            val d = l[ay * w + ax] - l[by * w + bx]; cost += d * d; n++
        }
    }
    return cost / n
}

/** Source for a heal spot: the nearby circle whose surroundings best match the spot's (0..1 coordinates). */
fun bestSource(src: Bitmap, x: Float, y: Float, r: Float): Pair<Float, Float>? {
    val (l, w, h) = luma(src, 512)
    val rp = r * maxOf(w, h); val dx = x * w; val dy = y * h
    var best: Pair<Float, Float>? = null; var bestCost = Float.MAX_VALUE
    for (dist in listOf(2.5f, 3.5f, 5f)) for (i in 0 until 16) {
        val t = i / 16f * 2 * Math.PI
        val sx = dx + (kotlin.math.cos(t) * rp * dist).toFloat(); val sy = dy + (kotlin.math.sin(t) * rp * dist).toFloat()
        if (sx - rp < 0 || sy - rp < 0 || sx + rp >= w || sy + rp >= h) continue
        val c = ringCost(l, w, h, dx, dy, sx, sy, rp)
        if (c < bestCost) { bestCost = c; best = sx / w to sy / h }
    }
    return best
}

/** Small dark specks on smooth areas (dust, blemishes), as heal spots with matched sources. At most `limit`. */
fun findSpots(src: Bitmap, limit: Int = 20): List<Spot> {
    val (l, w, h) = luma(src, 800)
    // local mean over a 15 px box, via an integral image
    val ii = DoubleArray((w + 1) * (h + 1))
    for (y in 0 until h) { var row = 0.0; for (x in 0 until w) { row += l[y * w + x]; ii[(y + 1) * (w + 1) + x + 1] = ii[y * (w + 1) + x + 1] + row } }
    fun box(x0: Int, y0: Int, x1: Int, y1: Int) = ii[y1 * (w + 1) + x1] - ii[y0 * (w + 1) + x1] - ii[y1 * (w + 1) + x0] + ii[y0 * (w + 1) + x0]
    val R = 7
    val dark = BooleanArray(w * h)
    for (y in R until h - R) for (x in R until w - R) {
        val mean = box(x - R, y - R, x + R + 1, y + R + 1) / ((2 * R + 1) * (2 * R + 1))
        dark[y * w + x] = l[y * w + x] < mean - 0.08
    }
    val seen = BooleanArray(w * h)
    val spots = ArrayList<Spot>()
    for (start in dark.indices) {
        if (!dark[start] || seen[start]) continue
        val blob = ArrayList<Int>(); val stack = ArrayDeque<Int>().apply { add(start) }; seen[start] = true
        while (stack.isNotEmpty() && blob.size < 200) {
            val i = stack.removeLast(); blob += i
            for (n in intArrayOf(i - 1, i + 1, i - w, i + w)) if (n in dark.indices && dark[n] && !seen[n]) { seen[n] = true; stack.add(n) }
        }
        if (blob.size !in 2..60) continue // specks, not edges or shadows
        val cx = blob.sumOf { it % w }.toFloat() / blob.size; val cy = blob.sumOf { it / w }.toFloat() / blob.size
        val rad = sqrt(blob.size / Math.PI.toFloat()) * 2f + 1f
        val r = rad / maxOf(w, h)
        val (sx, sy) = bestSource(src, cx / w, cy / h, r) ?: continue
        spots += Spot(cx / w, cy / h, sx, sy, r, heal = true)
        if (spots.size >= limit) break
    }
    return spots
}

/** 256-bin histograms of red, green and blue. */
fun histogram(b: Bitmap): Array<IntArray> {
    val px = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
    val h = Array(3) { IntArray(256) }
    for (c in px) { h[0][Color.red(c)]++; h[1][Color.green(c)]++; h[2][Color.blue(c)]++ }
    return h
}
