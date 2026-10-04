package dev.lumen

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.RectF
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

private val EXIF_TAGS = listOf(
    ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED, ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
    ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, "LensMake", "LensModel", // framework ExifInterface has no constants for these
    ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_ISO_SPEED_RATINGS,
    ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_FLASH,
    ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_ARTIST, ExifInterface.TAG_COPYRIGHT,
)
private val LABELS = listOf(null, "red", "yellow", "green", "blue", "purple")

/** Copied develop settings (no crop). ponytail: lives as long as the process; persist if copy-then-reopen matters */
object Clip { var edit: String? = null }

// ponytail: name + size identifies a photo across picker, gallery and MediaStore URIs; collides only on identical name and size
fun key(name: String?, size: Long, fallback: Uri) = if (name != null) "$name|$size" else fallback.toString()

/** S02 editor (with S03 crop and S06 presets as panels). */
class MainActivity : Activity() {
    private lateinit var view: EditView
    private val edit get() = view.edit
    private val db by lazy { Db(this) }
    private var row: Row? = null
    private var source: Uri? = null
    private val ui = Handler(Looper.getMainLooper())
    private val saveEdit = Runnable { row?.let { db.saveEdit(it.id, edit.toJson()) } }
    // ponytail: in-memory history, lost when the editor closes; Versions (S07) cover keeping states
    private val undo = ArrayDeque<Edit>()
    private val redo = ArrayDeque<Edit>()
    private lateinit var undoBtn: View
    private lateinit var redoBtn: View
    private lateinit var pasteBtn: View
    private val sync = mutableListOf<() -> Unit>() // re-read every control from `edit` (load, undo, preset)
    private val syncMarks = mutableListOf<() -> Unit>()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (intent.getBooleanExtra("selftest", false)) return selfTest()
        Ui.init(this)
        if (intent.getBooleanExtra("design", false)) return setContentView(designGallery().apply { fitInsets() })
        view = EditView(this, ::checkpoint, ::changed).apply { contentDescription = "Photo. Touch and hold to see the original." }

        val top = LinearLayout(this).apply {
            setBackgroundColor(Ui.color("surface"))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.space(2), 0, Ui.space(2), 0)
            addView(textButton("Photos") { photos() })
            addView(View(context), LayoutParams(0, 0, 1f))
            undoBtn = textButton("Undo") { step(undo, redo) }.also { addView(it) }
            redoBtn = textButton("Redo") { step(redo, undo) }.also { addView(it) }
            addView(textButton("Reset") { checkpoint(); view.edit = Edit().also { it.crop = edit.crop; it.rot90 = edit.rot90; it.flipH = edit.flipH; it["straighten"] = edit["straighten"] }; refresh() })
            addView(textButton("Export") { export() })
        }

        val panels = linkedMapOf(
            "Presets" to presetsPanel(), "Crop" to cropPanel(), "Light" to sliders("light"), "Curve" to curvePanel(), "Color" to colorPanel(),
            "Effects" to sliders("effects"), "Detail" to sliders("detail"), "Rate" to ratePanel())
        val host = FrameLayout(this).apply { panels.values.forEach { addView(it) } }
        val scroller = ScrollView(this).apply { addView(host) }
        val tabs = Tabs(this, panels.keys.toList()) { sel ->
            panels.values.forEachIndexed { j, p -> p.visibility = if (j == sel) View.VISIBLE else View.GONE }
            scroller.scrollTo(0, 0) // each panel opens at its top
            view.cropMode = panels.keys.elementAt(sel) == "Crop"
        }
        tabs.select(2)

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.color("bg"))
            addView(top, LayoutParams(LayoutParams.MATCH_PARENT, Ui.size("topbar")))
            addView(view, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Ui.color("surface"))
                addView(tabs)
                addView(scroller, LayoutParams(LayoutParams.MATCH_PARENT, Ui.dp(300)))
            })
            fitInsets()
        })
        syncHistory()
        // Opened from the library, or from another app via "Edit with" / "Share" / "Open with".
        (intent.data ?: intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))?.let(::load)
    }

    override fun onResume() { super.onResume(); if (::pasteBtn.isInitialized) pasteBtn.isEnabled = Clip.edit != null }

    // ---- panels ----

    private fun column(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, 0, Ui.space(4))
        views.forEach { addView(it) }
    }

    private fun buttons(vararg views: View) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(Ui.space(2), 0, Ui.space(2), 0)
        views.forEach { addView(it) }
    }

    private fun heading(s: String) = Ui.type(TextView(this), "xs", "text-muted").apply {
        text = s.uppercase(); setPadding(Ui.space(4), Ui.space(4), Ui.space(4), 0); isAccessibilityHeading = true
    }

    /** A slider bound to an edit key (the key can change, e.g. the colour mixer's selected hue). */
    private fun slider(title: String, key: () -> String, after: () -> Unit = {}) =
        SliderRow(this, title, ::checkpoint) { p -> edit[key()] = p / 100f; after(); changed() }
            .also { r -> sync += { r.bar.progress = Math.round(edit[key()] * 100) } }

    private fun sliders(section: String) = column(*SECTIONS.getValue(section).map { k -> slider(k, { k }) }.toTypedArray())

    /** Own tab, so the panel never scrolls under a finger that is shaping the curve. */
    private fun curvePanel(): View {
        val curve = CurveView(this, { edit }, ::checkpoint, ::changed).also { c -> sync += { c.invalidate() } }
        val channels = Tabs(this, listOf("RGB", "Red", "Green", "Blue")) { curve.channel = it }
        val reset = textButton("Reset curve") {
            checkpoint(); edit.curves = edit.curves.toMutableList().also { it[curve.channel] = LINE }; refresh()
        }
        return column(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(channels, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)); addView(reset)
        }, FrameLayout(this).apply { setPadding(Ui.space(4), 0, Ui.space(4), 0); addView(curve) })
    }

    private fun colorPanel(): View {
        var hue = HUES[0]
        val mix = listOf("Hue" to "Hue", "Saturation" to "Sat", "Luminance" to "Lum").map { (t, k) -> slider(t, { "$hue$k" }) to k }
        val chips = Tabs(this, HUES.map { it.replaceFirstChar(Char::uppercase) }) { i ->
            hue = HUES[i] // the three sliders now edit this hue
            mix.forEach { (row, k) -> row.bar.progress = Math.round(edit["$hue$k"] * 100) }
        }
        return column(sliders("color"), heading("Color mix"), chips, *mix.map { it.first }.toTypedArray())
    }

    private fun cropPanel(): View {
        val straighten = slider("Straighten", { "straighten" }) {
            view.look?.let { val (ow, oh) = edit.oriented(it.src.width, it.src.height); edit.crop = edit.shrinkToFit(edit.crop, ow, oh) }
        }
        val aspects = listOf("Free" to 0f, "Original" to -1f, "1:1" to 1f, "4:5" to 0.8f, "16:9" to 16f / 9)
        val chips = Tabs(this, aspects.map { it.first }) { i -> aspect(aspects[i].second) }
        return column(chips, straighten, buttons(
            textButton("Rotate") { checkpoint(); edit.rotateRight(); view.aspect = view.aspect?.let { 1 / it }; refresh() },
            textButton("Flip") { checkpoint(); edit.flip(); refresh() },
            textButton("Reset crop") {
                checkpoint(); edit.crop = RectF(0f, 0f, 1f, 1f); edit.rot90 = 0; edit.flipH = false; edit["straighten"] = 0f; refresh()
            }))
    }

    /** Lock the crop to an aspect (0 = free, -1 = the photo's own), largest centred frame that fits. */
    private fun aspect(ratio: Float) {
        val l = view.look
        if (ratio == 0f || l == null) { view.aspect = null; return }
        val (ow, oh) = edit.oriented(l.src.width, l.src.height)
        val wide = edit.crop.width() * ow >= edit.crop.height() * oh
        val a = when {
            ratio < 0 -> ow.toFloat() / oh
            wide -> maxOf(ratio, 1 / ratio)
            else -> minOf(ratio, 1 / ratio) // follow the frame's orientation: 4:5 on a landscape crop is 5:4
        }
        view.aspect = a
        checkpoint()
        var w = 1f; var h = ow / (a * oh)
        if (h > 1) { h = 1f; w = a * oh / ow }
        edit.crop = edit.shrinkToFit(RectF(0.5f - w / 2, 0.5f - h / 2, 0.5f + w / 2, 0.5f + h / 2), ow, oh)
        refresh()
    }

    private fun presetsPanel(): View {
        val list = column()
        fun build() {
            list.removeAllViews()
            db.presets().forEach { p ->
                list.addView(textButton(p.name) { checkpoint(); edit.merge(p.edit, geometry = false); refresh() }.apply {
                    gravity = Gravity.CENTER_VERTICAL or Gravity.START
                    setPadding(Ui.space(4), 0, Ui.space(4), 0)
                    contentDescription = "Apply preset ${p.name}. Long press to delete."
                    setOnLongClickListener {
                        AlertDialog.Builder(context).setMessage("Delete the preset \"${p.name}\"?")
                            .setPositiveButton("Delete") { _, _ -> db.deletePreset(p.id); build() }
                            .setNegativeButton("Keep", null).show(); true
                    }
                }, LayoutParams(LayoutParams.MATCH_PARENT, Ui.size("touch")))
            }
        }
        val save = textButton("Save as preset") {
            val input = EditText(this).apply { setText("My look ${db.presets().size + 1}"); selectAll() }
            AlertDialog.Builder(this).setTitle("Preset name").setView(input)
                .setPositiveButton("Save") { _, _ ->
                    input.text.toString().trim().takeIf { it.isNotEmpty() }?.let { db.savePreset(it, edit.toJson(geometry = false)); build() }
                }.setNegativeButton("Cancel", null).show()
        }
        val copy = textButton("Copy edits") { Clip.edit = edit.toJson(geometry = false); pasteBtn.isEnabled = true; toast("Edits copied") }
        pasteBtn = textButton("Paste edits") { Clip.edit?.let { checkpoint(); edit.merge(it, geometry = false); refresh() } }
            .apply { isEnabled = Clip.edit != null }
        build()
        return column(buttons(save, copy, pasteBtn), heading("Presets"), list)
    }

    private fun ratePanel(): View {
        val stars = (1..5).map { n ->
            Ui.type(TextView(this), "lg").apply {
                text = "★"; gravity = Gravity.CENTER; minWidth = Ui.size("touch"); minHeight = Ui.size("touch")
                isClickable = true; isFocusable = true; contentDescription = "$n star" + if (n > 1) "s" else ""
                background = Ui.ripple(null)
                setOnClickListener { rate(if (row?.rating == n) 0 else n) }
            }
        }
        val flags = listOf(0 to "Unflagged", 1 to "Pick", -1 to "Reject")
        val flagTabs = Tabs(this, flags.map { it.second }) { i -> mark("flag", flags[i].first) }
        val labelTabs = Tabs(this, LABELS.map { it?.replaceFirstChar(Char::uppercase) ?: "None" }) { i -> mark("label", LABELS[i]) }
        syncMarks += {
            val r = row
            stars.forEachIndexed { i, s ->
                val on = r != null && i < r.rating
                s.setTextColor(Ui.color(if (on) "accent" else "text-muted")); s.isSelected = on
            }
            if (r != null) { flagTabs.select(flags.indexOfFirst { it.first == r.flag }); labelTabs.select(LABELS.indexOf(r.label)) }
        }
        return column(heading("Rating"), buttons(*stars.toTypedArray()), heading("Flag"), flagTabs, heading("Label"), labelTabs)
    }

    private fun rate(n: Int) = mark("rating", n)

    private fun mark(column: String, value: Any?) {
        val r = row ?: return
        val current: Any? = when (column) { "rating" -> r.rating; "flag" -> r.flag; else -> r.label }
        if (current == value) return
        db.mark(r.id, column, value)
        row = Row(r.id, r.edit, if (column == "rating") value as Int else r.rating,
            if (column == "flag") value as Int else r.flag, if (column == "label") value as String? else r.label)
        syncMarks.forEach { it() }
    }

    // ---- history ----

    private fun changed() { view.invalidate(); ui.removeCallbacks(saveEdit); ui.postDelayed(saveEdit, 300) } // autosave, debounced

    private fun checkpoint() {
        undo.addLast(edit.copy())
        if (undo.size > 100) undo.removeFirst()
        redo.clear()
        syncHistory()
    }

    private fun step(from: ArrayDeque<Edit>, to: ArrayDeque<Edit>) {
        val state = from.removeLastOrNull() ?: return
        to.addLast(edit.copy())
        view.edit = state
        refresh()
    }

    private fun refresh() { sync.forEach { it() }; syncHistory(); changed() }

    private fun syncHistory() { undoBtn.isEnabled = undo.isNotEmpty(); redoBtn.isEnabled = redo.isNotEmpty() }

    // Ctrl+Z, Ctrl+Shift+Z / Ctrl+Y with a keyboard attached.
    override fun onKeyShortcut(code: Int, e: KeyEvent): Boolean = when {
        code == KeyEvent.KEYCODE_Z && e.isShiftPressed -> { step(redo, undo); true }
        code == KeyEvent.KEYCODE_Z -> { step(undo, redo); true }
        code == KeyEvent.KEYCODE_Y -> { step(redo, undo); true }
        else -> super.onKeyShortcut(code, e)
    }

    // 0–5 rate, P pick, X reject, U unflag.
    override fun onKeyDown(code: Int, e: KeyEvent): Boolean {
        if (!e.isCtrlPressed) when (code) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_5 -> { rate(code - KeyEvent.KEYCODE_0); return true }
            KeyEvent.KEYCODE_P -> { mark("flag", 1); return true }
            KeyEvent.KEYCODE_X -> { mark("flag", -1); return true }
            KeyEvent.KEYCODE_U -> { mark("flag", 0); return true }
        }
        return super.onKeyDown(code, e)
    }

    // ---- photo in, photo out ----

    private fun View.fitInsets() = setOnApplyWindowInsetsListener { v, ins ->
        val b = ins.getInsets(WindowInsets.Type.systemBars())
        v.setPadding(b.left, b.top, b.right, b.bottom); ins
    }

    /** Back to the library; opened from another app there is no library underneath, so start one. */
    private fun photos() {
        if (isTaskRoot) startActivity(Intent(this, LibraryActivity::class.java))
        finish()
    }

    /** Decoded in sRGB (P3 photos convert once, up front), long edge at most `cap`. Returns the full size too. */
    private fun decode(uri: Uri, cap: Int): Pair<Bitmap, Size> {
        var size = Size(1, 1)
        val b = ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { d, info, _ ->
            size = info.size
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            d.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            val k = cap.toFloat() / maxOf(info.size.width, info.size.height)
            if (k < 1) d.setTargetSize((info.size.width * k).toInt(), (info.size.height * k).toInt())
        }
        return b to size
    }

    private fun load(uri: Uri) = Thread {
        val result = runCatching {
            val (bmp, size) = decode(uri, 2048) // preview: the shader runs per screen pixel, 2048 is plenty
            Look(bmp) to db.open(keyOf(uri), uri, size.width, size.height)
        }.onFailure { Log.w("Lumen", "open $uri", it) }.getOrNull()
        runOnUiThread {
            if (result == null) return@runOnUiThread toast("Can't open this file")
            ui.removeCallbacks(saveEdit)
            row = result.second
            source = uri
            view.edit = Edit().merge(result.second.edit)
            view.look = result.first
            undo.clear(); redo.clear()
            refresh()
            syncMarks.forEach { it() }
        }
    }.start()

    private fun keyOf(uri: Uri): String = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
        ?.use { if (it.moveToFirst()) key(it.getString(0), it.getLong(1), uri) else null } ?: uri.toString()

    private fun export() {
        val uri = source ?: return toast("Open a photo first")
        val e = edit.copy()
        toast("Exporting…")
        Thread {
            // ponytail: full size first, 4096 px if memory runs out (8 bytes/px: decode + output)
            val out = listOf(Int.MAX_VALUE, 4096).firstNotNullOfOrNull { cap ->
                runCatching { Look(decode(uri, cap).first).render(e) }.onFailure { Log.w("Lumen", "export at $cap", it) }.getOrNull()
            }
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "Lumen_${System.currentTimeMillis()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Lumen")
                put(MediaStore.Images.Media.IS_PENDING, 1) // hidden from other apps until EXIF is written
            }
            val dest = if (out != null) contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) else null
            val ok = out != null && dest != null && contentResolver.openOutputStream(dest)
                ?.use { out.compress(Bitmap.CompressFormat.JPEG, 95, it) } == true &&
                runCatching { copyExif(uri, dest) }.onFailure { Log.w("Lumen", "exif", it) }.isSuccess
            if (dest != null) contentResolver.update(dest, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            runOnUiThread { toast(if (ok) "Exported ${out!!.width}×${out.height} to Pictures/Lumen" else "Export failed") }
        }.start()
    }

    /** Camera and date metadata survive export. Orientation is baked into the pixels, so it resets to normal. */
    private fun copyExif(from: Uri, to: Uri) {
        val src = contentResolver.openInputStream(from)?.use { ExifInterface(it) }
        contentResolver.openFileDescriptor(to, "rw")!!.use { fd ->
            ExifInterface(fd.fileDescriptor).apply {
                // ponytail: no GPS. MediaStore redacts location unless ACCESS_MEDIA_LOCATION is granted; add both if exports should keep it
                EXIF_TAGS.forEach { t -> src?.getAttribute(t)?.let { setAttribute(t, it) } }
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                setAttribute(ExifInterface.TAG_SOFTWARE, "Lumen")
                saveAttributes()
            }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // adb shell am start -S -f 0x10008000 -n dev.lumen/.MainActivity --ez selftest true ; adb logcat -s Lumen
    private fun selfTest() {
        val checks = linkedMapOf<String, Boolean>()
        fun solid(c: Int) = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(c) }
        fun px(src: Bitmap, vararg kv: Pair<String, Float>) = Look(src).render(Edit().also { e -> kv.forEach { (k, x) -> e[k] = x } }).getPixel(0, 0)
        val gray = solid(Color.rgb(128, 128, 128))
        val base = px(gray); val bright = px(gray, "exposure" to 0.5f); val warm = px(gray, "temp" to 1f)
        checks["identity"] = Color.red(base) in 126..130 && Color.green(base) in 126..130 && Color.blue(base) in 126..130
        checks["exposure"] = Color.red(bright) > Color.red(base) + 20
        checks["temp"] = Color.red(warm) > Color.blue(warm) + 20
        val red = px(solid(Color.rgb(200, 40, 40)), "redSat" to -1f)
        checks["mixer"] = abs(Color.red(red) - Color.green(red)) < 12 && Color.red(red) in 60..100 // luminance grey, not washed out

        val line = curveFn(LINE)
        checks["curve identity"] = (0..10).all { abs(line(it / 10f) - it / 10f) < 1e-4f }
        val f = curveFn(listOf(0f to 0f, 0.3f to 0.5f, 0.6f to 0.55f, 1f to 1f))
        checks["curve monotone"] = (0..100).map { f(it / 100f) }.zipWithNext().all { (a, b) -> b >= a - 1e-5f } && abs(f(0.3f) - 0.5f) < 1e-4f

        val g = Edit().apply { this["straighten"] = 0.3f }
        val fit = g.shrinkToFit(RectF(0f, 0f, 1f, 1f), 300, 200)
        checks["straighten fits"] = !g.fits(RectF(0f, 0f, 1f, 1f), 300, 200) && g.fits(fit, 300, 200) && fit.width() < 1f
        val r = Edit().apply { crop = RectF(0.1f, 0.2f, 0.5f, 0.9f) }
        repeat(4) { r.rotateRight() }
        checks["4 turns"] = r.rot90 == 0 && listOf(r.crop.left - 0.1f, r.crop.top - 0.2f, r.crop.right - 0.5f, r.crop.bottom - 0.9f).all { abs(it) < 1e-5f }

        val grad = Bitmap.createBitmap(IntArray(300 * 200) { i -> Color.rgb(i % 300 * 255 / 299, i / 300 * 255 / 199, 90) }, 300, 200, Bitmap.Config.ARGB_8888)
        val te = Edit().apply { this["vignette"] = -0.6f; this["clarity"] = 0.5f; this["straighten"] = 0.1f; this["sharpen"] = 0.4f }
        te.crop = te.shrinkToFit(RectF(0f, 0f, 1f, 1f), 300, 200)
        val look = Look(grad)
        val a = look.render(te, 64); val b = look.render(te, 4096)
        val pa = IntArray(a.width * a.height).also { a.getPixels(it, 0, a.width, 0, 0, a.width, a.height) }
        val pb = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
        checks["tiles = single"] = a.width == b.width && a.height == b.height && pa.indices.all { i ->
            listOf(16, 8, 0).all { s -> abs((pa[i] shr s and 0xFF) - (pb[i] shr s and 0xFF)) <= 2 }
        }

        val m = Edit().apply { this["exposure"] = 0.4f }.merge("""{"color":{"temp":0.3}}""", geometry = false)
        checks["preset merge"] = m["exposure"] == 0.4f && abs(m["temp"] - 0.3f) < 1e-6f
        val j = Edit().apply { this["clarity"] = 0.2f; curves = listOf(listOf(0f to 0.1f, 1f to 0.9f), LINE, LINE, LINE); rotateRight() }
        val back = Edit().merge(j.toJson())
        checks["json round trip"] = back.toJson() == j.toJson()

        val d = Db(this, null)
        val first = d.open("a.jpg|1", Uri.EMPTY, 1, 1)
        d.saveEdit(first.id, j.toJson()); d.mark(first.id, "rating", 4)
        val again = d.open("a.jpg|1", Uri.parse("content://other"), 1, 1)
        checks["db"] = again.id == first.id && again.edit == j.toJson() && again.rating == 4 && d.marks().keys == setOf("a.jpg|1") &&
            d.presets().size == 6 && runCatching { d.mark(first.id, "rating", 9) }.isFailure

        val ok = checks.values.all { it }
        Log.i("Lumen", "selftest ${if (ok) "PASS" else "FAIL ${checks.filterValues { !it }.keys}"} (${checks.size} checks) base=${Integer.toHexString(base)} bright=${Integer.toHexString(bright)} warm=${Integer.toHexString(warm)} red=${Integer.toHexString(red)}")
        finish()
    }
}
