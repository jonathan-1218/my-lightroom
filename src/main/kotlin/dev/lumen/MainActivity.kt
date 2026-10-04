package dev.lumen

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.MediaStore
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Toast

// Slider names double as shader uniform names. Every value is -1..1, 0 = untouched.
val SLIDERS = SECTIONS.values.flatten()

// One GPU pass does every adjustment, for both the live preview and the saved file.
private val AGSL = """
uniform shader img;
uniform float2 size;
${SLIDERS.joinToString("\n") { "uniform float $it;" }}
const float3 W = float3(0.2126, 0.7152, 0.0722);
half4 main(float2 p) {
    half4 c = img.eval(p);
    float3 x = pow(float3(c.rgb), float3(2.2)); // ponytail: gamma 2.2 approximates sRGB, exact curve if banding shows
    x *= exp2(2.0 * exposure) * float3(1.0 + 0.3 * temp, 1.0 - 0.3 * tint, 1.0 - 0.3 * temp);
    x = pow(max(x, 0.0), float3(1.0 / 2.2));
    float bp = -0.15 * blacks;
    float wp = 1.0 - 0.15 * whites;
    x = (x - bp) / (wp - bp);
    float L = dot(x, W);
    float d = 0.3 * shadows * (1.0 - smoothstep(0.0, 0.6, L)) + 0.3 * highlights * smoothstep(0.4, 1.0, L);
    x *= (L + d) / max(L, 0.001);
    x = (x - 0.5) * (1.0 + 0.6 * contrast) + 0.5;
    L = dot(x, W);
    float s = max(x.r, max(x.g, x.b)) - min(x.r, min(x.g, x.b));
    x = mix(float3(L), x, (1.0 + saturation) * (1.0 + vibrance * (1.0 - s)));
    x *= 1.0 + 0.8 * vignette * smoothstep(0.4, 1.1, length(p / size - 0.5) * 1.414);
    return half4(half3(clamp(x, 0.0, 1.0)), c.a);
}
"""

fun RuntimeShader.set(src: Bitmap, v: FloatArray) {
    setInputShader("img", BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        .apply { filterMode = BitmapShader.FILTER_MODE_LINEAR })
    setFloatUniform("size", src.width.toFloat(), src.height.toFloat())
    SLIDERS.forEachIndexed { i, n -> setFloatUniform(n, v[i]) }
}

/** Full-resolution render, off-screen on the GPU. */
fun render(src: Bitmap, v: FloatArray): Bitmap {
    val paint = Paint().apply { shader = RuntimeShader(AGSL).apply { set(src, v) } }
    val pic = Picture()
    pic.beginRecording(src.width, src.height).drawRect(0f, 0f, src.width.toFloat(), src.height.toFloat(), paint)
    pic.endRecording()
    return Bitmap.createBitmap(pic).copy(Bitmap.Config.ARGB_8888, false)
}

class EditView(ctx: Context) : View(ctx) {
    var src: Bitmap? = null
    val v = FloatArray(SLIDERS.size)
    private val fx = RuntimeShader(AGSL)
    private val paint = Paint().apply { shader = fx }
    private val m = Matrix()
    private var before = false

    override fun onDraw(c: Canvas) {
        val b = src ?: return
        val k = minOf(width / b.width.toFloat(), height / b.height.toFloat())
        m.setScale(k, k)
        m.postTranslate((width - b.width * k) / 2, (height - b.height * k) / 2)
        c.concat(m)
        if (before) return c.drawBitmap(b, 0f, 0f, null)
        fx.set(b, v)
        c.drawRect(0f, 0f, b.width.toFloat(), b.height.toFloat(), paint)
    }

    // Press and hold the photo to see the original.
    override fun onTouchEvent(e: MotionEvent): Boolean {
        before = e.action == MotionEvent.ACTION_DOWN || e.action == MotionEvent.ACTION_MOVE
        invalidate()
        return true
    }
}

private val EXIF_TAGS = listOf(
    ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED, ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
    ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, "LensMake", "LensModel", // framework ExifInterface has no constants for these
    ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_ISO_SPEED_RATINGS,
    ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_FLASH,
    ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_ARTIST, ExifInterface.TAG_COPYRIGHT,
)

/** S02 editor. */
class MainActivity : Activity() {
    private lateinit var edit: EditView
    private var full: Bitmap? = null
    private val bars = mutableListOf<SeekBar>()
    private val db by lazy { Db(this) }
    private var photoId: Long? = null
    private val ui = Handler(Looper.getMainLooper())
    private val saveEdit = Runnable { photoId?.let { db.saveEdit(it, toJson(edit.v)) } }
    private var source: Uri? = null
    // ponytail: in-memory history, lost when the editor closes; Versions (S07) cover keeping states
    private val undo = ArrayDeque<FloatArray>()
    private val redo = ArrayDeque<FloatArray>()
    private lateinit var undoBtn: View
    private lateinit var redoBtn: View

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (intent.getBooleanExtra("selftest", false)) return selfTest()
        Ui.init(this)
        if (intent.getBooleanExtra("design", false)) return setContentView(designGallery().apply { fitInsets() })
        edit = EditView(this).apply { contentDescription = "Photo. Touch and hold to see the original." }

        val top = LinearLayout(this).apply {
            setBackgroundColor(Ui.color("surface"))
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(Ui.space(2), 0, Ui.space(2), 0)
            addView(textButton("Photos") { photos() })
            addView(View(context), LayoutParams(0, 0, 1f))
            undoBtn = textButton("Undo") { step(undo, redo) }.also { addView(it) }
            redoBtn = textButton("Redo") { step(redo, undo) }.also { addView(it) }
            addView(textButton("Reset") { if (edit.v.any { it != 0f }) { checkpoint(); bars.forEach { it.progress = 0 } } })
            addView(textButton("Export") { save() })
        }

        // One panel of sliders per edit-document section; tabs switch between them.
        val panels = SECTIONS.values.map { names ->
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                names.forEach { name ->
                    val i = SLIDERS.indexOf(name)
                    val row = SliderRow(context, name, ::checkpoint) { p ->
                        edit.v[i] = p / 100f; edit.invalidate()
                        ui.removeCallbacks(saveEdit); ui.postDelayed(saveEdit, 300) // autosave, debounced
                    }
                    addView(row)
                }
            }
        }
        bars.addAll(panels.flatMap { panel -> (0 until panel.childCount).map { (panel.getChildAt(it) as SliderRow).bar } })
        val panelHost = android.widget.FrameLayout(this).apply { panels.forEach { addView(it) } }
        val tabs = Tabs(this, SECTIONS.keys.map { it.replaceFirstChar(Char::uppercase) }) { sel ->
            panels.forEachIndexed { j, panel -> panel.visibility = if (j == sel) View.VISIBLE else View.GONE }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.color("bg"))
            addView(top, LayoutParams(LayoutParams.MATCH_PARENT, Ui.size("topbar")))
            addView(edit, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Ui.color("surface"))
                addView(tabs)
                addView(ScrollView(context).apply { addView(panelHost) }, LayoutParams(LayoutParams.MATCH_PARENT, Ui.dp(300)))
            })
            fitInsets()
        }
        setContentView(root)
        syncHistory()
        // Opened from the gallery via "Edit with" / "Share" / "Open with".
        (intent.data ?: intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))?.let(::load)
    }

    private fun View.fitInsets() = setOnApplyWindowInsetsListener { v, ins ->
        val b = ins.getInsets(WindowInsets.Type.systemBars())
        v.setPadding(b.left, b.top, b.right, b.bottom); ins
    }

    /** Back to the library; opened from another app there is no library underneath, so start one. */
    private fun photos() {
        if (isTaskRoot) startActivity(Intent(this, LibraryActivity::class.java))
        finish()
    }

    private fun checkpoint() {
        undo.addLast(edit.v.copyOf())
        if (undo.size > 100) undo.removeFirst()
        redo.clear()
        syncHistory()
    }

    private fun step(from: ArrayDeque<FloatArray>, to: ArrayDeque<FloatArray>) {
        val state = from.removeLastOrNull() ?: return
        to.addLast(edit.v.copyOf())
        state.forEachIndexed { i, x -> bars[i].progress = Math.round(x * 100) }
        syncHistory()
    }

    private fun syncHistory() { undoBtn.isEnabled = undo.isNotEmpty(); redoBtn.isEnabled = redo.isNotEmpty() }

    // Ctrl+Z, Ctrl+Shift+Z / Ctrl+Y with a keyboard attached.
    override fun onKeyShortcut(code: Int, e: android.view.KeyEvent): Boolean = when {
        code == android.view.KeyEvent.KEYCODE_Z && e.isShiftPressed -> { step(redo, undo); true }
        code == android.view.KeyEvent.KEYCODE_Z -> { step(undo, redo); true }
        code == android.view.KeyEvent.KEYCODE_Y -> { step(redo, undo); true }
        else -> super.onKeyShortcut(code, e)
    }

    private fun load(uri: Uri) = Thread {
        val bmp = runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { d, info, _ ->
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                // ponytail: 4096px cap keeps memory and GPU texture limits safe; tile the render if full 50MP export matters
                val k = 4096f / maxOf(info.size.width, info.size.height)
                if (k < 1) d.setTargetSize((info.size.width * k).toInt(), (info.size.height * k).toInt())
            }
        }.onFailure { Log.w("Lumen", "open $uri", it) }.getOrNull()
        val (id, saved) = if (bmp != null) db.open(keyOf(uri), uri, bmp.width, bmp.height) else null to null
        runOnUiThread {
            if (bmp == null || saved == null) return@runOnUiThread toast("Can't open this file")
            ui.removeCallbacks(saveEdit)
            photoId = null // don't write the previous photo's sliders onto this one
            fromJson(saved).forEachIndexed { i, x -> bars[i].progress = Math.round(x * 100) }
            photoId = id
            source = uri
            undo.clear(); redo.clear(); syncHistory()
            val k = 1600f / maxOf(bmp.width, bmp.height) // small copy keeps slider dragging smooth
            full = bmp
            edit.src = if (k < 1) Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true) else bmp
            edit.invalidate()
        }
    }.start()

    // ponytail: name + size identifies a photo across picker and gallery URIs; collides only on identical name and size
    private fun keyOf(uri: Uri): String = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
        ?.use { if (it.moveToFirst()) "${it.getString(0)}|${it.getLong(1)}" else null } ?: uri.toString()

    private fun save() {
        val src = full ?: return toast("Open a photo first")
        val v = edit.v.copyOf()
        toast("Saving…")
        Thread {
            val out = render(src, v)
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "Lumen_${System.currentTimeMillis()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Lumen")
                put(MediaStore.Images.Media.IS_PENDING, 1) // hidden from other apps until EXIF is written
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            val ok = uri != null && contentResolver.openOutputStream(uri)
                ?.use { out.compress(Bitmap.CompressFormat.JPEG, 95, it) } == true &&
                runCatching { copyExif(source, uri) }.onFailure { Log.w("Lumen", "exif", it) }.isSuccess
            if (uri != null) contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            runOnUiThread { toast(if (ok) "Exported to Pictures/Lumen" else "Export failed") }
        }.start()
    }

    /** Camera and date metadata survive export. Orientation is baked into the pixels, so it resets to normal. */
    private fun copyExif(from: Uri?, to: Uri) {
        val src = from?.let { u -> contentResolver.openInputStream(u)?.use { ExifInterface(it) } }
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

    // adb shell am start -n dev.lumen/.MainActivity --ez selftest true ; adb logcat -s Lumen
    private fun selfTest() {
        val gray = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(128, 128, 128)) }
        fun px(name: String? = null, x: Float = 0f) =
            render(gray, FloatArray(SLIDERS.size).also { if (name != null) it[SLIDERS.indexOf(name)] = x }).getPixel(0, 0)
        val base = px(); val bright = px("exposure", 0.5f); val warm = px("temp", 1f)
        val d = Db(this, null)
        val want = FloatArray(SLIDERS.size).also { it[0] = 0.5f; it[SLIDERS.size - 1] = -0.3f }
        val (id, first) = d.open("a.jpg|1", Uri.EMPTY, 1, 1)
        d.saveEdit(id, toJson(want))
        val (id2, back) = d.open("a.jpg|1", Uri.parse("content://other"), 1, 1)
        val dbOk = fromJson(first).all { it == 0f } && id2 == id && fromJson(back).contentEquals(want)
        val ok = dbOk && Color.red(base) in 126..130 && Color.green(base) in 126..130 &&
            Color.red(bright) > Color.red(base) + 20 && Color.red(warm) > Color.blue(warm) + 20
        Log.i("Lumen", "selftest ${if (ok) "PASS" else "FAIL"} db=$dbOk base=${Integer.toHexString(base)} bright=${Integer.toHexString(bright)} warm=${Integer.toHexString(warm)}")
        finish()
    }
}
