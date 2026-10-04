package dev.lumen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import org.json.JSONObject
import java.io.File
import kotlin.math.pow
import kotlin.math.roundToInt

/** Profiles: a base look as a 3D LUT. Built-ins are generated here; .cube files are imported into app storage as PNG strips. */
object Profiles {
    private var dir: File? = null
    private val cache = HashMap<String, Bitmap?>()
    private const val N = 33

    private fun lum(r: Float, g: Float, b: Float) = 0.2126f * r + 0.7152f * g + 0.0722f * b
    private fun s(x: Float) = x * x * (3 - 2 * x) // S-curve
    private fun mixF(a: Float, b: Float, t: Float) = a + (b - a) * t

    /** Each maps sRGB 0..1 to sRGB 0..1. Written for Lumen; the names are descriptive, not copies of anyone's profiles. */
    private val builtIn: Map<String, (Float, Float, Float) -> FloatArray> = linkedMapOf(
        "Mono" to { r, g, b -> val y = mixF(0.25f * r + 0.65f * g + 0.1f * b, s(0.25f * r + 0.65f * g + 0.1f * b), 0.4f); floatArrayOf(y, y, y) },
        "Mono warm" to { r, g, b -> val y = mixF(lum(r, g, b), s(lum(r, g, b)), 0.3f); floatArrayOf(y * 1.04f, y, y * 0.9f) },
        "Film" to { r, g, b ->
            val l = lum(r, g, b)
            floatArrayOf(r, g, b).map { c -> 0.04f + 0.92f * mixF(l, c, 0.9f).pow(0.95f) }.toFloatArray().also {
                it[0] += 0.02f * (1 - l); it[2] -= 0.02f * (1 - l); it[2] += 0.015f * l // warm shadows, cool highlights
            }
        },
        "Teal & amber" to { r, g, b ->
            val l = lum(r, g, b)
            val (tr, tg, tb) = if (l < 0.5f) Triple(-0.06f, 0.02f, 0.05f) else Triple(0.06f, 0.02f, -0.06f)
            val k = kotlin.math.abs(2 * l - 1)
            floatArrayOf(r + tr * k, g + tg * k, b + tb * k)
        },
        "Vivid" to { r, g, b -> val l = lum(r, g, b); floatArrayOf(r, g, b).map { c -> mixF(s(c), mixF(l, c, 1.25f), 0.7f) }.toFloatArray() },
        "Matte" to { r, g, b -> val l = lum(r, g, b); floatArrayOf(r, g, b).map { c -> 0.08f + 0.86f * mixF(l, c, 0.85f) }.toFloatArray() },
    )

    fun init(ctx: Context) { if (dir == null) dir = File(ctx.filesDir, "profiles").apply { mkdirs() } }
    fun imported() = dir?.listFiles { f -> f.extension == "png" }?.map { it.nameWithoutExtension }?.sorted() ?: emptyList()
    fun names() = listOf("Color") + builtIn.keys + imported()

    /** The LUT strip (N·N × N), or null for "Color" / unknown names. */
    fun lut(name: String): Bitmap? = if (name == "Color") null else cache.getOrPut(name) {
        builtIn[name]?.let { generate(it) }
            ?: dir?.let { File(it, "$name.png") }?.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
    }

    private fun generate(f: (Float, Float, Float) -> FloatArray): Bitmap {
        val px = IntArray(N * N * N)
        for (b in 0 until N) for (g in 0 until N) for (r in 0 until N) {
            val o = f(r / (N - 1f), g / (N - 1f), b / (N - 1f))
            px[g * N * N + b * N + r] = color(o[0], o[1], o[2])
        }
        return Bitmap.createBitmap(px, N * N, N, Bitmap.Config.ARGB_8888)
    }

    private fun color(r: Float, g: Float, b: Float) =
        Color.rgb((r.coerceIn(0f, 1f) * 255).roundToInt(), (g.coerceIn(0f, 1f) * 255).roundToInt(), (b.coerceIn(0f, 1f) * 255).roundToInt())

    /** Parses a 3D .cube (red changes fastest). Throws IllegalArgumentException with a readable reason. */
    fun parseCube(text: String): Bitmap {
        var n = 0
        var lo = floatArrayOf(0f, 0f, 0f); var hi = floatArrayOf(1f, 1f, 1f)
        val rows = ArrayList<FloatArray>()
        for (raw in text.lineSequence()) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            val parts = line.split(Regex("\\s+"))
            when (parts[0].uppercase()) {
                "TITLE" -> {}
                "LUT_1D_SIZE" -> throw IllegalArgumentException("1D LUTs aren't supported, only 3D")
                "LUT_3D_SIZE" -> n = parts[1].toInt()
                "DOMAIN_MIN" -> lo = FloatArray(3) { parts[it + 1].toFloat() }
                "DOMAIN_MAX" -> hi = FloatArray(3) { parts[it + 1].toFloat() }
                else -> parts.mapNotNull { it.toFloatOrNull() }.takeIf { it.size == 3 }?.let { rows += it.toFloatArray() }
            }
        }
        require(n in 2..64) { "LUT size must be 2 to 64, this file says $n" }
        require(rows.size == n * n * n) { "Expected ${n * n * n} colour rows, found ${rows.size}" }
        val px = IntArray(n * n * n)
        rows.forEachIndexed { i, v ->
            val r = i % n; val g = (i / n) % n; val b = i / (n * n)
            px[g * n * n + b * n + r] = color((v[0] - lo[0]) / (hi[0] - lo[0]), (v[1] - lo[1]) / (hi[1] - lo[1]), (v[2] - lo[2]) / (hi[2] - lo[2]))
        }
        return Bitmap.createBitmap(px, n * n, n, Bitmap.Config.ARGB_8888)
    }

    /** Saves the LUT under a name that is safe as a file name and not already taken (a built-in or an earlier import). */
    fun importCube(name: String, text: String): String {
        val b = parseCube(text)
        val clean = unique(name.replace(Regex("[^A-Za-z0-9 _&().-]"), "").trim().take(60).ifEmpty { "Imported" }, names())
        File(dir!!, "$clean.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        cache.remove(clean)
        return clean
    }

    fun delete(name: String) { File(dir ?: return, "$name.png").delete(); cache.remove(name) }
}

/**
 * Reads a Lightroom / Camera Raw .xmp preset (the user's own file) into Lumen's edit JSON.
 * Settings Lumen has no equivalent for are skipped. Returns (name, edit json), or null if nothing usable was found.
 */
fun sha256(s: String): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

/** `name`, or "name (2)", "name (3)"… — the first one not in `taken`. */
fun unique(name: String, taken: Collection<String>): String =
    if (name !in taken) name else (2..999).map { "$name ($it)" }.first { it !in taken }

/** Text between `open` and the next `close`, found with indexOf: linear time whatever the file holds. */
private fun between(text: String, open: String, close: String): String? {
    val a = text.indexOf(open).takeIf { it >= 0 } ?: return null
    val b = text.indexOf(close, a + open.length).takeIf { it >= 0 } ?: return null
    return text.substring(a + open.length, b)
}

const val MAX_PRESET_JSON = 64 * 1024

fun xmpPreset(text: String, fallbackName: String): Pair<String, String>? {
    // every numeric setting in two passes over the file (attributes, then elements), not one scan per setting
    val values = HashMap<String, String>()
    Regex("<crs:([A-Za-z0-9]{1,48})>([-+0-9.]{1,32})</crs:\\1>").findAll(text).forEach { values.putIfAbsent(it.groupValues[1], it.groupValues[2]) }
    Regex("crs:([A-Za-z0-9]{1,48})=\"([-+0-9.]{1,32})\"").findAll(text).forEach { values[it.groupValues[1]] = it.groupValues[2] }
    fun num(k: String): Float? = values[k]?.toFloatOrNull()?.takeIf { it.isFinite() }
    val e = Edit()
    var any = false
    fun put(key: String, v: Float?) { if (v != null && v.isFinite()) { e[key] = v.coerceIn(sliderRange(key)); any = true } }
    put("exposure", num("Exposure2012")?.div(2))              // ±2 EV is the full slider
    listOf("contrast" to "Contrast2012", "highlights" to "Highlights2012", "shadows" to "Shadows2012", "whites" to "Whites2012",
        "blacks" to "Blacks2012", "vibrance" to "Vibrance", "saturation" to "Saturation", "clarity" to "Clarity2012",
        "texture" to "Texture", "dehaze" to "Dehaze", "vignette" to "PostCropVignetteAmount", "temp" to "IncrementalTemperature",
        "tint" to "IncrementalTint", "noise" to "LuminanceSmoothing", "colorNoise" to "ColorNoiseReduction", "grain" to "GrainAmount",
        "distortion" to "LensManualDistortionAmount", "lensVignette" to "VignetteAmount", "shadowLum" to "ColorGradeShadowLum",
        "midLum" to "ColorGradeMidtoneLum", "highLum" to "ColorGradeHighlightLum", "balance" to "SplitToningBalance",
    ).forEach { (k, x) -> put(k, num(x)?.div(100)) }
    put("sharpen", num("Sharpness")?.div(150))
    listOf("shadow" to "Shadow", "mid" to "Midtone", "high" to "Highlight").forEach { (k, x) ->
        put("${k}Hue", num("ColorGrade${x}Hue")?.div(360)); put("${k}Sat", num("ColorGrade${x}Sat")?.div(100))
    }
    num("ColorGradeBlending")?.let { put("blending", it / 50 - 1) }
    val names = listOf("Red", "Orange", "Yellow", "Green", "Aqua", "Blue", "Purple", "Magenta")
    HUES.forEachIndexed { i, h ->
        put("${h}Hue", num("HueAdjustment${names[i]}")?.div(100))
        put("${h}Sat", num("SaturationAdjustment${names[i]}")?.div(100))
        put("${h}Lum", num("LuminanceAdjustment${names[i]}")?.div(100))
    }
    // Tone curves: <crs:ToneCurvePV2012><rdf:Seq><rdf:li>x, y</rdf:li>… in 0..255
    val li = Regex("<rdf:li>\\s*([0-9.]{1,16})\\s*,\\s*([0-9.]{1,16})\\s*</rdf:li>")
    val curves = listOf("ToneCurvePV2012", "ToneCurvePV2012Red", "ToneCurvePV2012Green", "ToneCurvePV2012Blue").map { tag ->
        between(text, "<crs:$tag>", "</crs:$tag>")?.let { body ->
            cleanCurve(li.findAll(body).take(MAX_CURVE_POINTS * 4).mapNotNull { m ->
                val x = m.groupValues[1].toFloatOrNull() ?: return@mapNotNull null
                val y = m.groupValues[2].toFloatOrNull() ?: return@mapNotNull null
                x / 255f to y / 255f
            }.toList())
        }
    }
    if (curves.any { it != null }) { e.curves = curves.mapIndexed { i, c -> c ?: e.curves[i] }; any = true }
    if (!any) return null
    val name = (between(text, "<crs:Name>", "</crs:Name>")?.let { Regex("<rdf:li[^>]*>([^<]{1,200})</rdf:li>").find(it)?.groupValues?.get(1) }
        ?: Regex("crs:PresetName=\"([^\"]{1,200})\"").find(text)?.groupValues?.get(1) ?: fallbackName).trim().take(60).ifEmpty { fallbackName }
    // Only the keys the preset actually set, so applying it leaves everything else alone (like a built-in preset).
    val full = JSONObject(e.toJson(whole = false))
    val base = JSONObject(Edit().toJson(whole = false))
    val out = JSONObject().put("v", 1)
    full.keys().forEach { sec ->
        val a = full.opt(sec); val b = base.opt(sec)
        if (a is JSONObject && b is JSONObject) {
            val diff = JSONObject()
            a.keys().forEach { k -> if (a.get(k).toString() != b.opt(k).toString()) diff.put(k, a.get(k)) }
            if (diff.length() > 0) out.put(sec, diff)
        }
    }
    return (name to out.toString()).takeIf { it.second.length <= MAX_PRESET_JSON }
}

