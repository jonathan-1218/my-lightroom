package dev.lumen

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Color
import android.graphics.Path
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RecordingCanvas
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.hardware.HardwareBuffer
import android.media.ImageReader
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.hypot
import kotlin.math.roundToInt

// The edit document (replica/architecture.md). Every slider value is -1..1, 0 = untouched.
val HUES = listOf("red", "orange", "yellow", "green", "aqua", "blue", "purple", "magenta")
val SECTIONS = linkedMapOf(
    "light" to listOf("exposure", "contrast", "highlights", "shadows", "whites", "blacks"),
    "color" to listOf("temp", "tint", "vibrance", "saturation"),
    "mixer" to HUES.flatMap { listOf("${it}Hue", "${it}Sat", "${it}Lum") },
    "grading" to listOf("shadowHue", "shadowSat", "shadowLum", "midHue", "midSat", "midLum",
        "highHue", "highSat", "highLum", "blending", "balance"), // hue and sat are 0..1, the rest -1..1
    "effects" to listOf("texture", "clarity", "dehaze", "vignette", "grain", "grainSize"),
    "detail" to listOf("sharpen", "noise", "colorNoise"),
    "optics" to listOf("distortion", "chroma", "lensVignette"),
    "geometry" to listOf("straighten", "vertical", "horizontal"), // straighten ±1 = ±45°
)
val SLIDERS = SECTIONS.values.flatten()
private val IDX = SLIDERS.withIndex().associate { it.value to it.index }
private val SCALARS = SECTIONS.filterKeys { it != "mixer" && it != "geometry" }.values.flatten()
val CHANNELS = listOf("rgb", "r", "g", "b")

// Limits for anything read back from JSON (the database, presets, paste): an edit can't grow without bound or carry junk.
const val MAX_CURVE_POINTS = 64
const val MAX_MASK_POINTS = 20000 // x,y pairs across all strokes of one brush mask
const val MAX_SPOTS = 500
private val UNIT_KEYS = setOf("shadowHue", "shadowSat", "midHue", "midSat", "highHue", "highSat") // 0..1, the rest -1..1
fun sliderRange(k: String) = if (k in UNIT_KEYS) 0f..1f else -1f..1f
private fun Double.fin(): Float? = toFloat().takeIf { it.isFinite() }
private fun JSONArray.floats(): FloatArray? =
    FloatArray(length()) { optDouble(it).toFloat() }.takeIf { a -> a.all { it.isFinite() } }

/** Points sorted, inside 0..1, distinct x, at most MAX_CURVE_POINTS; null if fewer than 2 survive. */
fun cleanCurve(pts: List<Pair<Float, Float>>): List<Pair<Float, Float>>? =
    pts.filter { it.first.isFinite() && it.second.isFinite() }.map { it.first.coerceIn(0f, 1f) to it.second.coerceIn(0f, 1f) }
        .sortedBy { it.first }.distinctBy { it.first }.take(MAX_CURVE_POINTS).takeIf { it.size >= 2 }
val LINE = listOf(0f to 0f, 1f to 1f)

// ---- masks: local adjustments. Coordinates are 0..1 of the source photo, so they stick to the content. ----
const val MAX_MASKS = 4 // ponytail: 4 shader slots; raise with the slot count if 4 is ever too few
val MASK_TYPES = listOf("linear", "radial", "brush", "color", "luma")
val MASK_ADJ = listOf("exposure", "contrast", "highlights", "shadows", "temp", "tint", "saturation", "clarity")

class Stroke(val pts: FloatArray, val size: Float, val erase: Boolean) // pts x,y pairs; size = fraction of the long edge

/** g by type: linear (ax, ay, bx, by) full effect at a, none at b · radial (cx, cy, rx, ry) · color (r, g, b, picked>0) · luma (low, high). */
class Mask(val type: String) {
    var g = FloatArray(4)
    var feather = 0.5f // radial edge, luma edge, brush softness
    var invert = false
    var range = 0.3f   // colour distance that still counts
    val adj = FloatArray(MASK_ADJ.size)
    var strokes: List<Stroke> = emptyList()

    fun copy() = Mask(type).also { m ->
        m.g = g.copyOf(); m.feather = feather; m.invert = invert; m.range = range; adj.copyInto(m.adj); m.strokes = strokes
    }

    fun json(): JSONObject = JSONObject().put("type", type).put("g", JSONArray(g.map { it.toDouble() }))
        .put("feather", feather.toDouble()).put("invert", invert).put("range", range.toDouble())
        .put("adjust", JSONObject().apply { MASK_ADJ.forEachIndexed { i, k -> put(k, adj[i].toDouble()) } })
        .put("strokes", JSONArray(strokes.map { s ->
            JSONObject().put("size", s.size.toDouble()).put("erase", s.erase).put("p", JSONArray(s.pts.map { it.toDouble() }))
        }))

    companion object {
        /** A mask from JSON, or null if it isn't one. Every number is checked and clamped. */
        fun from(o: JSONObject): Mask? {
            val t = o.optString("type").takeIf { it in MASK_TYPES } ?: return null
            return Mask(t).apply {
                o.optJSONArray("g")?.floats()?.takeIf { it.size == 4 }?.let { a -> g = FloatArray(4) { a[it].coerceIn(-2f, 2f) } }
                if (t == "radial") { g[2] = g[2].coerceAtLeast(0.01f); g[3] = g[3].coerceAtLeast(0.01f) }
                feather = o.optDouble("feather", 0.5).fin()?.coerceIn(0f, 1f) ?: 0.5f
                invert = o.optBoolean("invert")
                range = o.optDouble("range", 0.3).fin()?.coerceIn(0.05f, 0.6f) ?: 0.3f
                o.optJSONObject("adjust")?.let { a -> MASK_ADJ.forEachIndexed { i, k -> adj[i] = a.optDouble(k, 0.0).fin()?.coerceIn(-1f, 1f) ?: 0f } }
                var budget = MAX_MASK_POINTS * 2
                strokes = o.optJSONArray("strokes")?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { st ->
                        val pts = st.optJSONArray("p")?.floats() ?: return@mapNotNull null
                        val n = minOf(pts.size - pts.size % 2, budget)
                        if (n < 2) return@mapNotNull null
                        budget -= n
                        Stroke(FloatArray(n) { pts[it].coerceIn(-1f, 2f) }, st.optDouble("size").fin()?.coerceIn(0.001f, 0.5f) ?: 0.05f, st.optBoolean("erase"))
                    }
                } ?: emptyList()
            }
        }
    }
}

/** Heal / clone spot: copy a circle from (sx, sy) onto (dx, dy). Coordinates 0..1 of the source, r of its long edge. */
data class Spot(val dx: Float, val dy: Float, val sx: Float, val sy: Float, val r: Float, val heal: Boolean)

class Edit {
    val v = FloatArray(SLIDERS.size)
    var curves: List<List<Pair<Float, Float>>> = List(4) { LINE } // rgb, r, g, b
    var crop = RectF(0f, 0f, 1f, 1f) // normalised to the rotated (oriented) image
    var rot90 = 0
    var flipH = false
    var masks: List<Mask> = emptyList()
    var retouch: List<Spot> = emptyList()
    var profile = "Color"     // "Color" = no profile; otherwise a built-in or imported LUT (Profiles)
    var profileAmount = 1f

    operator fun get(n: String) = v[IDX.getValue(n)]
    operator fun set(n: String, x: Float) { v[IDX.getValue(n)] = x }

    fun copy() = Edit().also { e ->
        v.copyInto(e.v); e.curves = curves; e.crop = RectF(crop); e.rot90 = rot90; e.flipH = flipH
        e.masks = masks.map { it.copy() }; e.retouch = retouch; e.profile = profile; e.profileAmount = profileAmount
    }

    /** `whole = false`: just the look (presets, copy/paste) — no crop, masks or spots, which belong to one photo. */
    fun toJson(whole: Boolean = true): String = JSONObject().put("v", 1).apply {
        SECTIONS.forEach { (s, names) ->
            if (s != "geometry") put(s, JSONObject().apply { names.forEach { put(it, this@Edit[it].toDouble()) } })
        }
        put("profile", JSONObject().put("name", profile).put("amount", profileAmount.toDouble()))
        put("curve", JSONObject().apply {
            CHANNELS.forEachIndexed { i, ch -> put(ch, JSONArray(curves[i].map { (x, y) -> JSONArray(listOf(x.toDouble(), y.toDouble())) })) }
        })
        if (whole) {
            put("geometry", JSONObject()
                .put("straighten", this@Edit["straighten"].toDouble())
                .put("vertical", this@Edit["vertical"].toDouble()).put("horizontal", this@Edit["horizontal"].toDouble())
                .put("crop", JSONArray(listOf(crop.left, crop.top, crop.right, crop.bottom).map { it.toDouble() }))
                .put("rot90", rot90).put("flipH", flipH))
            put("masks", JSONArray(masks.map { it.json() }))
            put("retouch", JSONArray(retouch.map { JSONArray(listOf(it.dx, it.dy, it.sx, it.sy, it.r, if (it.heal) 1f else 0f).map { v -> v.toDouble() }) }))
        }
    }.toString()

    /** Overlay whatever keys `json` has (a preset sets only what it names). Crop, masks and spots only when `whole`. */
    fun merge(json: String, whole: Boolean = true): Edit = apply {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return@apply // not JSON: leave the edit as it is
        SECTIONS.forEach { (s, names) ->
            if (s == "geometry") return@forEach
            val sec = o.optJSONObject(s) ?: return@forEach
            names.forEach { k -> if (sec.has(k)) sec.optDouble(k).fin()?.let { set(k, it.coerceIn(sliderRange(k))) } }
        }
        o.optJSONObject("profile")?.let { pr ->
            pr.optString("name").takeIf { it in Profiles.names() }?.let { profile = it } // only profiles that exist: no paths
            profileAmount = pr.optDouble("amount", 1.0).fin()?.coerceIn(0f, 1f) ?: 1f
        }
        o.optJSONObject("curve")?.let { c ->
            curves = CHANNELS.mapIndexed { i, ch ->
                c.optJSONArray(ch)?.let { a ->
                    cleanCurve((0 until a.length()).mapNotNull { a.optJSONArray(it)?.floats()?.takeIf { p -> p.size == 2 }?.let { p -> p[0] to p[1] } })
                } ?: curves[i]
            }
        }
        val g = o.optJSONObject("geometry")
        if (whole) o.optJSONArray("masks")?.let { a -> masks = (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(Mask::from) }.take(MAX_MASKS) }
        if (whole) o.optJSONArray("retouch")?.let { a ->
            retouch = (0 until a.length()).mapNotNull { a.optJSONArray(it)?.floats()?.takeIf { s -> s.size == 6 } }.take(MAX_SPOTS).map { s ->
                Spot(s[0].coerceIn(0f, 1f), s[1].coerceIn(0f, 1f), s[2].coerceIn(0f, 1f), s[3].coerceIn(0f, 1f), s[4].coerceIn(0.001f, 0.2f), s[5] > 0.5f)
            }
        }
        if (whole && g != null) {
            listOf("straighten", "vertical", "horizontal").forEach { k -> if (g.has(k)) g.optDouble(k).fin()?.let { set(k, it.coerceIn(-1f, 1f)) } }
            g.optJSONArray("crop")?.floats()?.takeIf { it.size == 4 }?.let { c ->
                val l = c[0].coerceIn(0f, 0.95f); val t = c[1].coerceIn(0f, 0.95f)
                crop = RectF(l, t, c[2].coerceIn(l + 0.05f, 1f), c[3].coerceIn(t + 0.05f, 1f))
            }
            rot90 = g.optInt("rot90", rot90).mod(4)
            flipH = g.optBoolean("flipH", flipH)
        }
    }

    // ---- geometry: rotate 90s, then mirror, then straighten, then crop ----

    fun oriented(w: Int, h: Int) = if (rot90 % 2 == 1) h to w else w to h
    fun degrees() = get("straighten") * 45f

    /** Oriented frame → straightened frame: perspective (keystone) first, then the straighten rotation. */
    fun shape(ow: Int, oh: Int): Matrix {
        val kv = 0.25f * get("vertical"); val kh = 0.25f * get("horizontal")
        val w = ow.toFloat(); val h = oh.toFloat()
        // vertical > 0 widens the top (fixes buildings leaning back); horizontal > 0 widens the left
        val dst = floatArrayOf(
            -maxOf(kv, 0f) * w / 2, -maxOf(kh, 0f) * h / 2,      w + maxOf(kv, 0f) * w / 2, -maxOf(-kh, 0f) * h / 2,
            w + maxOf(-kv, 0f) * w / 2, h + maxOf(-kh, 0f) * h / 2, -maxOf(-kv, 0f) * w / 2, h + maxOf(kh, 0f) * h / 2)
        return Matrix().apply {
            setPolyToPoly(floatArrayOf(0f, 0f, w, 0f, w, h, 0f, h), 0, dst, 0, 4)
            postRotate(degrees(), w / 2, h / 2)
        }
    }

    /** Source pixels → output pixels. Without crop: the whole straightened image (crop mode). */
    fun matrix(w: Int, h: Int, withCrop: Boolean = true): Matrix {
        val (ow, oh) = oriented(w, h)
        return Matrix().apply {
            setRotate(90f * rot90, w / 2f, h / 2f)
            postTranslate((ow - w) / 2f, (oh - h) / 2f)
            if (flipH) postScale(-1f, 1f, ow / 2f, oh / 2f)
            postConcat(shape(ow, oh))
            if (withCrop) postTranslate(-crop.left * ow, -crop.top * oh)
        }
    }

    fun outSize(w: Int, h: Int): Pair<Int, Int> {
        val (ow, oh) = oriented(w, h)
        return (crop.width() * ow).roundToInt().coerceAtLeast(1) to (crop.height() * oh).roundToInt().coerceAtLeast(1)
    }

    /** Is this crop (normalised, in an ow×oh oriented frame) fully inside the straightened, keystoned photo? */
    fun fits(r: RectF, ow: Int, oh: Int): Boolean {
        val pts = floatArrayOf(r.left * ow, r.top * oh, r.right * ow, r.top * oh, r.right * ow, r.bottom * oh, r.left * ow, r.bottom * oh)
        Matrix().apply { shape(ow, oh).invert(this) }.mapPoints(pts)
        val e = 1e-3f * maxOf(ow, oh)
        return (pts.indices step 2).all { pts[it] in -e..ow + e && pts[it + 1] in -e..oh + e }
    }

    /** Largest scale of `r` about its centre that fits. Straightening shrinks the crop like this. */
    fun shrinkToFit(r: RectF, ow: Int, oh: Int): RectF {
        if (fits(r, ow, oh)) return r
        var lo = 0f; var hi = 1f
        repeat(20) { val m = (lo + hi) / 2; if (fits(scaled(r, m), ow, oh)) lo = m else hi = m }
        return scaled(r, lo)
    }

    private fun scaled(r: RectF, s: Float) = RectF(
        r.centerX() - r.width() * s / 2, r.centerY() - r.height() * s / 2,
        r.centerX() + r.width() * s / 2, r.centerY() + r.height() * s / 2)

    fun rotateRight() {
        rot90 = (rot90 + if (flipH) 3 else 1) % 4 // mirrored: turning the picture right is a left turn of the source
        crop = RectF(1 - crop.bottom, crop.left, 1 - crop.top, crop.right)
    }

    fun flip() {
        flipH = !flipH
        crop = RectF(1 - crop.right, crop.top, 1 - crop.left, crop.bottom)
        set("straighten", -get("straighten"))
    }
}

// ---- tone curve: monotone cubic (Fritsch–Carlson), so dragging a point never makes the curve overshoot ----

fun curveFn(points: List<Pair<Float, Float>>): (Float) -> Float {
    val p = points.sortedBy { it.first }
    val n = p.size
    val m = FloatArray(n - 1) { (p[it + 1].second - p[it].second) / maxOf(p[it + 1].first - p[it].first, 1e-6f) }
    val t = FloatArray(n) { i ->
        when (i) { 0 -> m[0]; n - 1 -> m[n - 2]; else -> if (m[i - 1] * m[i] <= 0) 0f else (m[i - 1] + m[i]) / 2 }
    }
    for (i in 0 until n - 1) {
        if (m[i] == 0f) { t[i] = 0f; t[i + 1] = 0f; continue }
        val a = t[i] / m[i]; val b = t[i + 1] / m[i]; val s = a * a + b * b
        if (s > 9) { val k = 3 / kotlin.math.sqrt(s); t[i] = k * a * m[i]; t[i + 1] = k * b * m[i] }
    }
    return { x ->
        when {
            x <= p[0].first -> p[0].second
            x >= p[n - 1].first -> p[n - 1].second
            else -> {
                val i = (0 until n - 1).first { x <= p[it + 1].first }
                val h = p[i + 1].first - p[i].first; val u = (x - p[i].first) / h
                val h00 = (1 + 2 * u) * (1 - u) * (1 - u); val h10 = u * (1 - u) * (1 - u)
                val h01 = u * u * (3 - 2 * u); val h11 = u * u * (u - 1)
                (h00 * p[i].second + h10 * h * t[i] + h01 * p[i + 1].second + h11 * h * t[i + 1]).coerceIn(0f, 1f)
            }
        }
    }
}

/** 256×1 lookup: per channel, channel curve after the RGB curve. */
// ponytail: 8-bit LUT matches the 8-bit output; switch to RGBA_F16 if curves band on 10-bit exports
private fun lut(curves: List<List<Pair<Float, Float>>>): Bitmap {
    val (all, r, g, b) = curves.map(::curveFn)
    val px = IntArray(256) { i ->
        val y = all(i / 255f)
        fun c(f: (Float) -> Float) = (f(y) * 255).roundToInt()
        (0xFF shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
    }
    return Bitmap.createBitmap(px, 256, 1, Bitmap.Config.ARGB_8888)
}

// ---- the shader: one GPU pass, preview and export ----

private val NR_TAPS = listOf(1f to 0f, -1f to 0f, 0f to 1f, 0f to -1f, 0.7f to 0.7f, -0.7f to 0.7f, 0.7f to -0.7f, -0.7f to -0.7f)

private val AGSL = """
uniform shader img;
uniform shader blurS;
uniform shader blurL;
uniform shader lut;
uniform shader lut3d;
uniform float3x3 toOut;
${SCALARS.joinToString("\n") { "uniform float u_$it;" }}
uniform float4 hueA; uniform float4 hueB;
uniform float4 satA; uniform float4 satB;
uniform float4 lumA; uniform float4 lumB;
uniform float2 srcSize;
uniform float pxScale;     // source long edge / 2048: neighbourhood radii scale with resolution
uniform float outAspect;   // output width / height
uniform float lutN;        // 3D LUT size, 0 = no profile
uniform float profileAmt;
uniform float showMask;
uniform float3 overlayRgb;
${(0 until MAX_MASKS).joinToString("\n") { "uniform shader mk$it; uniform float4 mg$it; uniform float4 mx$it; uniform float4 ma$it; uniform float4 mb$it;" }}
const float3 W = float3(0.2126, 0.7152, 0.0722);

float3 hsv(float3 c) {
    float4 K = float4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
    float4 p = mix(float4(c.bg, K.wz), float4(c.gb, K.xy), step(c.b, c.g));
    float4 q = mix(float4(p.xyw, c.r), float4(c.r, p.yzx), step(p.x, c.r));
    float d = q.x - min(q.w, q.y);
    return float3(abs(q.z + (q.w - q.y) / (6.0 * d + 1e-10)), d / (q.x + 1e-10), q.x);
}
float3 rgb(float3 c) {
    float3 p = abs(fract(c.xxx + float3(1.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0);
    return c.z * mix(float3(1.0), clamp(p - 1.0, 0.0, 1.0), c.y);
}
// Triangular weight around hue centre c, zero outside l..r. The 8 bands sum to 1 at any hue.
float tri(float h, float l, float c, float r) { return clamp(h < c ? (h - l) / (c - l) : (r - h) / (r - c), 0.0, 1.0); }
float band(float4 a, float4 b, float h) {
    float hr = h > 180.0 ? h - 360.0 : h;
    return a.x * tri(hr, -60.0, 0.0, 30.0) + a.y * tri(h, 0.0, 30.0, 60.0) + a.z * tri(h, 30.0, 60.0, 120.0)
         + a.w * tri(h, 60.0, 120.0, 180.0) + b.x * tri(h, 120.0, 180.0, 240.0) + b.y * tri(h, 180.0, 240.0, 270.0)
         + b.z * tri(h, 240.0, 270.0, 300.0) + b.w * tri(h, 270.0, 300.0, 360.0);
}
// Mask weights, 1 = full effect. u is 0..1 of the source photo.
float linearW(float4 g, float2 u) {
    float2 d = g.zw - g.xy;
    return 1.0 - smoothstep(0.0, 1.0, dot(u - g.xy, d) / max(dot(d, d), 1e-6));
}
float radialW(float4 g, float f, float2 u) {
    float d = length((u - g.xy) / max(g.zw, float2(1e-4)));
    return 1.0 - smoothstep(1.0 - max(f, 0.01), 1.0, d);
}
float colorW(float4 g, float range, float3 oh) {
    if (g.w <= 0.0) return 0.0; // no colour picked yet
    float3 t = hsv(g.rgb);
    float dh = abs(oh.x - t.x);
    dh = min(dh, 1.0 - dh) * 2.0;
    float dist = length(float2(dh, 0.5 * (oh.y - t.y))) + (1.0 - smoothstep(0.03, 0.15, oh.y)); // greys have no hue
    return 1.0 - smoothstep(range * 0.5, range, dist);
}
float lumaW(float4 g, float f, float L) {
    float e = 0.02 + 0.2 * f;
    return smoothstep(g.x - e, g.x, L) * (1.0 - smoothstep(g.y, g.y + e, L));
}
// a = exposure, contrast, highlights, shadows · b = temp, tint, saturation, clarity
float3 local(float3 x, float4 a, float4 b, float w, float dL) {
    if (w <= 0.0) return x;
    x *= exp2(2.0 * a.x * w / 2.2);
    x *= float3(1.0 + 0.3 * b.x * w, 1.0 - 0.3 * b.y * w, 1.0 - 0.3 * b.x * w);
    float L = dot(x, W);
    float d = 0.3 * a.w * w * (1.0 - smoothstep(0.0, 0.6, L)) + 0.3 * a.z * w * smoothstep(0.4, 1.0, L);
    x *= (L + d) / max(L, 0.001);
    x = (x - 0.5) * (1.0 + 0.6 * a.y * w) + 0.5;
    x += 1.2 * b.w * w * dL;
    L = dot(x, W);
    return mix(float3(L), x, 1.0 + b.z * w);
}
float3 curve(float3 x) {
    x = clamp(x, 0.0, 1.0) * 255.0 + 0.5;
    return float3(lut.eval(float2(x.r, 0.5)).r, lut.eval(float2(x.g, 0.5)).g, lut.eval(float2(x.b, 0.5)).b);
}
// 3D LUT stored as N slices of N×N side by side (blue picks the slice); linear filtering does red and green.
float3 lut3(float3 c) {
    float n = lutN;
    float3 s = clamp(c, 0.0, 1.0) * (n - 1.0);
    float b0 = floor(s.b);
    float b1 = min(b0 + 1.0, n - 1.0);
    float2 xy = float2(s.r + 0.5, s.g + 0.5);
    return mix(float3(lut3d.eval(xy + float2(b0 * n, 0.0)).rgb), float3(lut3d.eval(xy + float2(b1 * n, 0.0)).rgb), s.b - b0);
}
// Colour grading: tint by luminance range. h/s 0..1, l -1..1.
float3 gradeRange(float3 x, float h, float s, float l, float w) {
    if (w <= 0.0) return x;
    float3 t = rgb(float3(h, 1.0, 1.0));
    t /= max(dot(t, W), 1e-3);
    x = mix(x, x * t, 0.5 * s * w);
    return x * (1.0 + 0.5 * l * w);
}
float hash(float2 p) { return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453); }
float vnoise(float2 g) {
    float2 i = floor(g);
    float2 f = fract(g);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + float2(1.0, 0.0)), f.x), mix(hash(i + float2(0.0, 1.0)), hash(i + float2(1.0, 1.0)), f.x), f.y);
}

half4 main(float2 p0) {
    // Lens distortion: resample around the centre. Pincushion (k > 0) is scaled so the corners stay inside.
    float2 ctr = srcSize * 0.5;
    float2 dd = p0 - ctr;
    float r2 = dot(dd, dd) / dot(ctr, ctr);
    float k = 0.3 * u_distortion;
    float2 p = ctr + dd * (1.0 + k * r2) / (1.0 + max(k, 0.0));

    half4 c = img.eval(p);
    float3 x = float3(c.rgb);
    if (u_chroma != 0.0) { // lateral chromatic aberration: red and blue planes scaled against green
        x.r = float(img.eval(ctr + (p - ctr) * (1.0 + 0.003 * u_chroma)).r);
        x.b = float(img.eval(ctr + (p - ctr) * (1.0 - 0.003 * u_chroma)).b);
    }
    float3 bs = float3(blurS.eval(p).rgb);
    float3 bl = float3(blurL.eval(p).rgb);
    if (u_noise > 0.0) { // edge-preserving: neighbours count only when their brightness is close
        float L0 = dot(x, W);
        float sig = 0.01 + 0.08 * u_noise;
        float3 acc = x;
        float wsum = 1.0;
${NR_TAPS.joinToString("\n") { (dx, dy) -> """        { float3 sn = float3(img.eval(p + float2($dx, $dy) * 1.5 * pxScale).rgb); float dn = dot(sn, W) - L0; float wn = exp(-dn * dn / (sig * sig)); acc += sn * wn; wsum += wn; }""" }}
        x = mix(x, acc / wsum, u_noise);
    }
    if (u_colorNoise > 0.0) x = mix(x, bs - dot(bs, W) + dot(x, W), u_colorNoise); // keep brightness, take the local average colour
    if (u_sharpen > 0.0) {
        float3 n = float3(img.eval(p + float2(1.0, 0.0)).rgb + img.eval(p - float2(1.0, 0.0)).rgb
                        + img.eval(p + float2(0.0, 1.0)).rgb + img.eval(p - float2(0.0, 1.0)).rgb) * 0.25;
        x += 1.5 * u_sharpen * (x - n);
    }
    x *= 1.0 + 0.6 * u_lensVignette * r2; // lens vignetting: brighten (or darken) towards the corners
    float dS = dot(x, W) - dot(bs, W);
    float dL = dot(x, W) - dot(bl, W);

    float haze = min(bl.r, min(bl.g, bl.b)); // dark-channel estimate from the large blur
    if (u_dehaze > 0.0) { x = (x - 0.95) / max(1.0 - 0.8 * u_dehaze * haze, 0.25) + 0.95; }
    else { x = mix(x, float3(0.85), -0.4 * u_dehaze); }

    x = pow(max(x, 0.0), float3(2.2)); // ponytail: gamma 2.2 approximates sRGB, exact curve if banding shows
    x *= exp2(2.0 * u_exposure) * float3(1.0 + 0.3 * u_temp, 1.0 - 0.3 * u_tint, 1.0 - 0.3 * u_temp);
    x = pow(max(x, 0.0), float3(1.0 / 2.2));
    float bp = -0.15 * u_blacks;
    float wp = 1.0 - 0.15 * u_whites;
    x = (x - bp) / (wp - bp);
    float L = dot(x, W);
    float d = 0.3 * u_shadows * (1.0 - smoothstep(0.0, 0.6, L)) + 0.3 * u_highlights * smoothstep(0.4, 1.0, L);
    x *= (L + d) / max(L, 0.001);

    L = clamp(dot(x, W), 0.0, 1.0);
    float mid = 1.0 - (2.0 * L - 1.0) * (2.0 * L - 1.0);
    x += 1.5 * u_texture * dS + 1.2 * u_clarity * dL * mid;
    x = (x - 0.5) * (1.0 + 0.6 * u_contrast) + 0.5;

    float2 u = p / srcSize;
    float3 oh = hsv(float3(c.rgb)); // masks select on the original pixel, not the edited one
    float oL = dot(float3(c.rgb), W);
${(0 until MAX_MASKS).joinToString("\n") { i -> """
    float w$i = 0.0;
    if (mx$i.x == 1.0) w$i = linearW(mg$i, u);
    else if (mx$i.x == 2.0) w$i = radialW(mg$i, mx$i.y, u);
    else if (mx$i.x == 3.0) w$i = float(mk$i.eval(p).r);
    else if (mx$i.x == 4.0) w$i = colorW(mg$i, mx$i.w, oh);
    else if (mx$i.x == 5.0) w$i = lumaW(mg$i, mx$i.y, oL);
    if (mx$i.x > 0.0 && mx$i.z > 0.5) w$i = 1.0 - w$i;
    x = local(x, ma$i, mb$i, w$i, dL);""" }}

    if (lutN > 0.0) x = mix(x, lut3(x), profileAmt);
    x = curve(x);

    L = dot(x, W);
    float s = max(x.r, max(x.g, x.b)) - min(x.r, min(x.g, x.b));
    x = mix(float3(L), x, (1.0 + u_saturation) * (1.0 + u_vibrance * (1.0 - s)));

    float3 h = hsv(clamp(x, 0.0, 1.0));
    float deg = h.x * 360.0;
    float chroma = h.y;
    h.x = fract(h.x + band(hueA, hueB, deg) * 30.0 / 360.0 + 1.0);
    x = rgb(h);
    x = mix(float3(dot(x, W)), x, 1.0 + band(satA, satB, deg)); // toward luminance, so -100 gives the right grey
    x *= 1.0 + 0.5 * band(lumA, lumB, deg) * chroma;

    L = clamp(dot(x, W), 0.0, 1.0);
    float blend = 0.3 * (u_blending + 1.0) * 0.5;
    float bal = 0.25 * u_balance;
    float ws = 1.0 - smoothstep(0.0, 0.5 + bal + blend, L);
    float wh = smoothstep(0.5 + bal - blend, 1.0, L);
    x = gradeRange(x, u_shadowHue, u_shadowSat, u_shadowLum, ws);
    x = gradeRange(x, u_midHue, u_midSat, u_midLum, clamp(1.0 - ws - wh, 0.0, 1.0));
    x = gradeRange(x, u_highHue, u_highSat, u_highLum, wh);

    float3 qh = toOut * float3(p0, 1.0);
    float2 q = qh.xy / qh.z; // 0..1 across the cropped output (perspective-correct)
    x *= 1.0 + 0.8 * u_vignette * smoothstep(0.4, 1.1, length(q - 0.5) * 1.414);
    if (u_grain > 0.0) {
        float cells = 900.0 * exp2(-1.5 * u_grainSize);
        float n = vnoise(q * float2(cells, cells / outAspect)) - 0.5;
        x += u_grain * 0.3 * n * (0.3 + 0.7 * (1.0 - abs(2.0 * L - 1.0)));
    }
    if (showMask >= 0.0) {
        float wsel = showMask == 0.0 ? w0 : (showMask == 1.0 ? w1 : (showMask == 2.0 ? w2 : w3));
        x = mix(x, overlayRgb, 0.5 * wsel);
    }
    return half4(half3(clamp(x, 0.0, 1.0)), c.a);
}
"""

/** Off-screen GPU render into a software bitmap. */
fun gpu(w: Int, h: Int, effect: RenderEffect? = null, draw: (RecordingCanvas) -> Unit): Bitmap {
    val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 1,
        HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
    val node = RenderNode("lumen").apply { setPosition(0, 0, w, h); setRenderEffect(effect) }
    draw(node.beginRecording(w, h)); node.endRecording()
    val renderer = HardwareRenderer().apply { setSurface(reader.surface); setContentRoot(node) }
    renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
    reader.acquireNextImage().use { img ->
        val hb = img.hardwareBuffer!!
        val out = Bitmap.wrapHardwareBuffer(hb, ColorSpace.get(ColorSpace.Named.SRGB))!!.copy(Bitmap.Config.ARGB_8888, false)
        hb.close()
        renderer.destroy(); reader.close()
        return out
    }
}

/** Gaussian blur at reduced size; radius is a fraction of the long edge so preview and export match. */
private fun blur(src: Bitmap, frac: Float, maxLong: Int): Bitmap {
    val k = minOf(1f, maxLong.toFloat() / maxOf(src.width, src.height))
    val w = (src.width * k).roundToInt().coerceAtLeast(1); val h = (src.height * k).roundToInt().coerceAtLeast(1)
    val r = (frac * maxOf(w, h)).coerceAtLeast(0.5f)
    return gpu(w, h, RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP)) {
        it.scale(w.toFloat() / src.width, h.toFloat() / src.height)
        it.drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
    }
}

/** Heal / clone spots copied into a new bitmap, before any develop step (so blurs and masks see the clean photo). */
fun bake(base: Bitmap, spots: List<Spot>): Bitmap {
    if (spots.isEmpty()) return base
    val out = base.copy(Bitmap.Config.ARGB_8888, true)
    val w = out.width; val h = out.height
    for (s in spots) {
        val r = (s.r * maxOf(w, h)).roundToInt().coerceAtLeast(1)
        val size = 2 * r + 1
        val dx = (s.dx * w).roundToInt() - r; val dy = (s.dy * h).roundToInt() - r
        val sx = (s.sx * w).roundToInt() - r; val sy = (s.sy * h).roundToInt() - r
        if (dx < 0 || dy < 0 || sx < 0 || sy < 0 || dx + size > w || dy + size > h || sx + size > w || sy + size > h) continue
        val dst = IntArray(size * size).also { out.getPixels(it, 0, size, dx, dy, size, size) }
        val src = IntArray(size * size).also { out.getPixels(it, 0, size, sx, sy, size, size) }
        // Heal: shift the copied patch by the difference of the two rims, so it takes on the colour around the blemish.
        val shift = FloatArray(3)
        if (s.heal) {
            var n = 0
            for (i in dst.indices) {
                val d = hypot((i % size - r).toFloat(), (i / size - r).toFloat()) / r
                if (d in 0.8f..1f) { n++; for (k in 0..2) shift[k] += ((dst[i] shr (16 - 8 * k)) and 0xFF) - ((src[i] shr (16 - 8 * k)) and 0xFF).toFloat() }
            }
            if (n > 0) for (k in 0..2) shift[k] /= n
        }
        for (i in dst.indices) {
            val d = hypot((i % size - r).toFloat(), (i / size - r).toFloat()) / r
            if (d > 1f) continue
            val a = 1f - ((d - 0.6f) / 0.4f).coerceIn(0f, 1f).let { it * it * (3 - 2 * it) } // feathered edge
            var px = 0xFF shl 24
            for (k in 0..2) {
                val sh = 16 - 8 * k
                val from = ((src[i] shr sh) and 0xFF) + shift[k]
                val to = (dst[i] shr sh) and 0xFF
                px = px or ((to + (from - to) * a).roundToInt().coerceIn(0, 255) shl sh)
            }
            dst[i] = px
        }
        out.setPixels(dst, 0, size, dx, dy, size, size)
    }
    return out
}

/** A brush mask rasterised from its strokes, white = painted. Long edge at most 1024. */
private fun brush(m: Mask, w: Int, h: Int): Bitmap {
    val k = minOf(1f, 1024f / maxOf(w, h))
    val bw = (w * k).roundToInt().coerceAtLeast(1); val bh = (h * k).roundToInt().coerceAtLeast(1)
    val b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
    val c = Canvas(b).apply { drawColor(Color.BLACK) }
    val long = maxOf(bw, bh)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    for (s in m.strokes) {
        paint.color = if (s.erase) Color.BLACK else Color.WHITE
        paint.strokeWidth = s.size * long
        paint.maskFilter = if (m.feather > 0.05f) BlurMaskFilter(s.size * long * m.feather * 0.5f, BlurMaskFilter.Blur.NORMAL) else null
        if (s.pts.size == 2) c.drawPoint(s.pts[0] * bw, s.pts[1] * bh, paint)
        else c.drawPath(Path().apply {
            moveTo(s.pts[0] * bw, s.pts[1] * bh)
            for (i in 2 until s.pts.size step 2) lineTo(s.pts[i] * bw, s.pts[i + 1] * bh)
        }, paint)
    }
    return b
}

/** A photo ready to develop: the pixels (with spots baked in), its blurs, and the shader. One per photo, and per export. */
class Look(val base: Bitmap) {
    var src = base; private set
    private var blurS = blur(src, 0.004f, 1024)
    private var blurL = blur(src, 0.03f, 512)
    private var spotsFor: List<Spot> = emptyList()
    private val fx = RuntimeShader(AGSL)
    private val paint = Paint().apply { shader = fx }
    private var lutFor: Any? = null
    private lateinit var lutShader: BitmapShader
    private val brushFor = arrayOfNulls<Any>(MAX_MASKS)
    private val brushShader = arrayOfNulls<BitmapShader>(MAX_MASKS)
    private val none by lazy { shader(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }) }
    var overlay = floatArrayOf(1f, 0.3f, 0.3f) // mask overlay colour, set from the tokens by the view
    private var profileFor: String? = null
    private var profileShader: BitmapShader? = null
    private var profileN = 0

    private fun shader(b: Bitmap) = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
        filterMode = BitmapShader.FILTER_MODE_LINEAR
        if (b.width != src.width || b.height != src.height) setLocalMatrix(Matrix().apply { setScale(src.width.toFloat() / b.width, src.height.toFloat() / b.height) })
    }

    private fun prepare(e: Edit) {
        if (spotsFor != e.retouch) {
            src = bake(base, e.retouch)
            blurS = blur(src, 0.004f, 1024); blurL = blur(src, 0.03f, 512)
            spotsFor = e.retouch
        }
        if (lutFor != e.curves) { // the LUT is sampled in its own 256×1 pixel space, never scaled to the photo
            lutShader = BitmapShader(lut(e.curves), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { filterMode = BitmapShader.FILTER_MODE_LINEAR }
            lutFor = e.curves
        }
        if (profileFor != e.profile) {
            val b = Profiles.lut(e.profile)
            profileShader = b?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { filterMode = BitmapShader.FILTER_MODE_LINEAR } }
            profileN = b?.height ?: 0
            profileFor = e.profile
        }
        for (i in 0 until MAX_MASKS) {
            val m = e.masks.getOrNull(i)?.takeIf { it.type == "brush" }
            val key = m?.let { it.strokes to it.feather }
            if (key != brushFor[i]) { brushShader[i] = m?.let { shader(brush(it, src.width, src.height)) }; brushFor[i] = key }
        }
    }

    /** Draws `e` in output pixels (or the uncropped straightened frame when `withCrop` is false).
     *  `showMask` tints that mask's area with the overlay colour (-1 = off). */
    fun draw(c: Canvas, e: Edit, withCrop: Boolean = true, showMask: Int = -1) {
        prepare(e)
        val w = src.width; val h = src.height
        val (ow, oh) = if (withCrop) e.outSize(w, h) else e.oriented(w, h)
        val m = e.matrix(w, h, withCrop)
        fx.setInputShader("img", shader(src))
        fx.setInputShader("blurS", shader(blurS))
        fx.setInputShader("blurL", shader(blurL))
        fx.setInputShader("lut", lutShader)
        val v = FloatArray(9); Matrix(m).apply { postScale(1f / ow, 1f / oh) }.getValues(v)
        fx.setFloatUniform("toOut", floatArrayOf(v[0], v[3], v[6], v[1], v[4], v[7], v[2], v[5], v[8])) // column-major
        SCALARS.forEach { fx.setFloatUniform("u_$it", e[it]) }
        listOf("hue" to "Hue", "sat" to "Sat", "lum" to "Lum").forEach { (u, k) ->
            val x = HUES.map { e["$it$k"] }
            fx.setFloatUniform("${u}A", x[0], x[1], x[2], x[3])
            fx.setFloatUniform("${u}B", x[4], x[5], x[6], x[7])
        }
        fx.setFloatUniform("srcSize", w.toFloat(), h.toFloat())
        fx.setFloatUniform("pxScale", maxOf(1f, maxOf(w, h) / 2048f)) // never below one pixel, or small images sample themselves
        fx.setFloatUniform("outAspect", ow.toFloat() / oh)
        fx.setInputShader("lut3d", profileShader ?: none)
        fx.setFloatUniform("lutN", profileN.toFloat())
        fx.setFloatUniform("profileAmt", e.profileAmount)
        fx.setFloatUniform("showMask", showMask.toFloat())
        fx.setFloatUniform("overlayRgb", overlay[0], overlay[1], overlay[2])
        for (i in 0 until MAX_MASKS) {
            val mk = e.masks.getOrNull(i)
            val a = mk?.adj ?: FloatArray(MASK_ADJ.size)
            fx.setInputShader("mk$i", brushShader[i] ?: none)
            fx.setFloatUniform("mg$i", mk?.g ?: FloatArray(4))
            fx.setFloatUniform("mx$i", (mk?.let { MASK_TYPES.indexOf(it.type) + 1 } ?: 0).toFloat(), mk?.feather ?: 0f, if (mk?.invert == true) 1f else 0f, mk?.range ?: 0f)
            fx.setFloatUniform("ma$i", a[0], a[1], a[2], a[3])
            fx.setFloatUniform("mb$i", a[4], a[5], a[6], a[7])
        }
        c.save()
        c.clipRect(0f, 0f, ow.toFloat(), oh.toFloat())
        c.concat(m)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        c.restore()
    }

    /** Full-size output, rendered in tiles so no single GPU target exceeds `tile`². */
    // ponytail: the source is still one texture, fine to ~16k px; BitmapRegionDecoder per tile beyond that
    fun render(e: Edit, tile: Int = 2048): Bitmap {
        val (ow, oh) = e.outSize(src.width, src.height)
        val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        for (y in 0 until oh step tile) for (x in 0 until ow step tile) {
            val t = gpu(minOf(tile, ow - x), minOf(tile, oh - y)) { it.translate(-x.toFloat(), -y.toFloat()); draw(it, e) }
            c.drawBitmap(t, x.toFloat(), y.toFloat(), null)
            t.recycle()
        }
        return out
    }
}
