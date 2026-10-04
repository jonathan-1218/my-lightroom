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
import android.widget.HorizontalScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

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
        Profiles.init(this)
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
            addView(textButton("Export") { exportDialog() })
        }

        val panels = linkedMapOf(
            "Presets" to presetsPanel(), "Crop" to cropPanel(), "Light" to lightPanel(), "Curve" to curvePanel(), "Color" to colorPanel(),
            "Effects" to sliders("effects"), "Detail" to sliders("detail"), "Optics" to sliders("optics"), "Masks" to masksPanel(), "Retouch" to retouchPanel(),
            "Rate" to ratePanel())
        val host = FrameLayout(this).apply { panels.values.forEach { addView(it) } }
        val scroller = ScrollView(this).apply { addView(host) }
        val tabs = Tabs(this, panels.keys.toList()) { sel ->
            panels.values.forEachIndexed { j, p -> p.visibility = if (j == sel) View.VISIBLE else View.GONE }
            scroller.scrollTo(0, 0) // each panel opens at its top
            view.mode = when (panels.keys.elementAt(sel)) { "Crop" -> "crop"; "Masks" -> "mask"; "Retouch" -> "retouch"; else -> "normal" }
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
    /** A -100..100 slider bound to any value in the edit. `register` keeps it in step on load / undo / preset. */
    private fun bound(title: String, get: () -> Float, set: (Float) -> Unit, register: Boolean = true, format: (Int) -> String = { "%+d".format(it) },
                      after: () -> Unit = {}) =
        SliderRow(this, title, ::checkpoint, format) { p -> set(p / 100f); after(); changed() }
            .also { r -> r.bar.progress = Math.round(get() * 100); if (register) sync += { r.bar.progress = Math.round(get() * 100) } }

    private fun slider(title: String, key: () -> String, after: () -> Unit = {}) = bound(title, { edit[key()] }, { edit[key()] = it }, after = after)

    /** A slider for a 0..1 value (shown -100..100). */
    private fun unit(title: String, get: () -> Float, set: (Float) -> Unit, register: Boolean = false, format: (Int) -> String = { "%+d".format(it) }) =
        bound(title, { get() * 2 - 1 }, { set((it + 1) / 2) }, register, format)
    private val degrees: (Int) -> String = { "${Math.round((it + 100) / 200f * 360)}°" }
    private val percent: (Int) -> String = { "${(it + 100) / 2}" }

    private fun hint(s: String) = Ui.type(TextView(this), "sm", "text-muted").apply { text = s; setPadding(Ui.space(4), Ui.space(2), Ui.space(4), 0) }

    private fun sliders(section: String) = column(*SECTIONS.getValue(section).map { k -> slider(k, { k }) }.toTypedArray())

    private lateinit var histogram: HistogramView
    private val updateHistogram = Runnable {
        val l = view.look ?: return@Runnable
        val (ow, oh) = edit.outSize(l.src.width, l.src.height)
        val k = 200f / maxOf(ow, oh)
        histogram.data = histogram(gpu(maxOf(1, (ow * k).toInt()), maxOf(1, (oh * k).toInt())) { it.scale(k, k); l.draw(it, edit) })
    }

    private fun lightPanel(): View {
        histogram = HistogramView(this)
        val auto = textButton("Auto") {
            val l = view.look ?: return@textButton
            checkpoint(); autoTone(l.src).forEach { (k, v) -> edit[k] = v }; refresh()
        }
        return column(histogram, buttons(auto), sliders("light"))
    }

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
        var range = "shadow"
        val grade = listOf(
            unit("Hue", { edit["${range}Hue"] }, { edit["${range}Hue"] = it }, register = true, format = degrees),
            unit("Saturation", { edit["${range}Sat"] }, { edit["${range}Sat"] = it }, register = true, format = percent),
            slider("Luminance", { "${range}Lum" }))
        val ranges = listOf("shadow" to "Shadows", "mid" to "Midtones", "high" to "Highlights")
        val rangeChips = Tabs(this, ranges.map { it.second }) { i ->
            range = ranges[i].first // the three sliders now grade this range
            grade[0].bar.progress = Math.round(edit["${range}Hue"] * 200 - 100)
            grade[1].bar.progress = Math.round(edit["${range}Sat"] * 200 - 100)
            grade[2].bar.progress = Math.round(edit["${range}Lum"] * 100)
        }
        return column(sliders("color"), heading("Color mix"), chips, *mix.map { it.first }.toTypedArray(),
            heading("Color grading"), rangeChips, *grade.toTypedArray(),
            slider("blending", { "blending" }), slider("balance", { "balance" }))
    }

    private fun cropPanel(): View {
        val refit = { view.look?.let { val (ow, oh) = edit.oriented(it.src.width, it.src.height); edit.crop = edit.shrinkToFit(edit.crop, ow, oh) } }
        val straighten = slider("Straighten", { "straighten" }) { refit() }
        val vertical = slider("vertical", { "vertical" }) { refit() }
        val horizontal = slider("horizontal", { "horizontal" }) { refit() }
        val level = textButton("Level") { // straighten from the photo's own lines
            val l = view.look ?: return@textButton
            checkpoint(); edit["straighten"] = autoLevel(bake(l.base, emptyList())); refit(); refresh()
        }
        val aspects = listOf("Free" to 0f, "Original" to -1f, "1:1" to 1f, "4:5" to 0.8f, "16:9" to 16f / 9)
        val chips = Tabs(this, aspects.map { it.first }) { i -> aspect(aspects[i].second) }
        return column(chips, straighten, vertical, horizontal, buttons(level,
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

    // ---- S04 masks ----

    private var selMask = -1
    private fun mask() = edit.masks.getOrNull(selMask)
    private val maskNames = mapOf("linear" to "Gradient", "radial" to "Radial", "brush" to "Brush", "color" to "Color", "luma" to "Light")

    private fun masksPanel(): View {
        val chips = FrameLayout(this)
        val typeBox = column()
        val adjust = MASK_ADJ.mapIndexed { i, k -> bound(k, { mask()?.adj?.get(i) ?: 0f }, { v -> mask()?.adj?.set(i, v) }, register = false) }
        val adjustBox = column(heading("Adjust this mask"), *adjust.toTypedArray())
        lateinit var rebuild: () -> Unit
        fun pick(i: Int) { // not "select": a local select() wins over Tabs.select() inside .apply { }
            selMask = i; view.mask = i
            adjust.forEachIndexed { j, r -> r.bar.progress = Math.round((mask()?.adj?.get(j) ?: 0f) * 100) }
            adjustBox.visibility = if (mask() == null) View.GONE else View.VISIBLE
            typeBox.removeAllViews()
            val m = mask() ?: return typeBox.addView(hint("Add a mask to adjust part of the photo. Up to $MAX_MASKS."))
            when (m.type) {
                "linear" -> typeBox.addView(hint("Full effect at the solid dot, fading out by the ring. Drag the middle to move both."))
                "radial" -> { typeBox.addView(hint("Drag the centre to move, the rings to resize.")); typeBox.addView(unit("Feather", { m.feather }, { m.feather = it })) }
                "brush" -> {
                    typeBox.addView(Tabs(this, listOf("Paint", "Erase"), initial = if (view.erase) 1 else 0) { view.erase = it == 1 })
                    typeBox.addView(SliderRow(this, "Size", {}) { p -> view.brushSize = 0.01f + (p + 100) / 200f * 0.19f }.apply { bar.progress = Math.round(((view.brushSize - 0.01f) / 0.19f) * 200 - 100) })
                    typeBox.addView(unit("Softness", { m.feather }, { m.feather = it }))
                }
                "color" -> { typeBox.addView(hint("Tap the photo on the colour to select.")); typeBox.addView(unit("Range", { (m.range - 0.05f) / 0.55f }, { m.range = 0.05f + it * 0.55f })) }
                "luma" -> {
                    typeBox.addView(unit("Darkest", { m.g[0] }, { m.g[0] = it }))
                    typeBox.addView(unit("Brightest", { m.g[1] }, { m.g[1] = it }))
                    typeBox.addView(unit("Feather", { m.feather }, { m.feather = it }))
                }
            }
            view.invalidate()
        }
        rebuild = {
            selMask = selMask.coerceAtMost(edit.masks.size - 1).let { if (it < 0 && edit.masks.isNotEmpty()) 0 else it }
            chips.removeAllViews()
            if (edit.masks.isNotEmpty()) chips.addView(Tabs(this, edit.masks.mapIndexed { i, m -> "${i + 1} ${maskNames[m.type]}" }, initial = selMask) { pick(it) })
            else pick(-1)
        }
        fun add(type: String) {
            val l = view.look ?: return
            if (edit.masks.size >= MAX_MASKS) return toast("Up to $MAX_MASKS masks")
            checkpoint()
            val aspect = l.src.width.toFloat() / l.src.height
            edit.masks = edit.masks + Mask(type).apply {
                when (type) {
                    "linear" -> g = floatArrayOf(0.5f, 0.15f, 0.5f, 0.5f)                   // the sky, usually
                    "radial" -> g = floatArrayOf(0.5f, 0.5f, 0.25f, 0.25f * aspect)        // a circle on screen
                    "luma" -> g = floatArrayOf(0.6f, 1f, 0f, 0f)                           // the highlights
                }
            }
            selMask = edit.masks.size - 1
            rebuild(); changed()
        }
        sync += { rebuild() }
        val adds = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(buttons(*MASK_TYPES.map { t -> textButton("+ ${maskNames[t]}") { add(t) } }.toTypedArray()))
        }
        val actions = buttons(
            textButton("Invert") { mask()?.let { checkpoint(); it.invert = !it.invert; changed() } },
            textButton("Hide overlay") {}.apply {
                setOnClickListener { view.overlay = !view.overlay; text = if (view.overlay) "Hide overlay" else "Show overlay"; view.invalidate() }
            },
            textButton("Delete") { mask()?.let { checkpoint(); edit.masks = edit.masks - it; rebuild(); changed() } })
        rebuild()
        return column(adds, chips, actions, typeBox, adjustBox)
    }

    // ---- S05 heal / clone ----

    private fun retouchPanel(): View {
        lateinit var delete: View
        view.onSpot = { delete.isEnabled = view.spot >= 0 }
        val mode = Tabs(this, listOf("Heal", "Clone"), initial = if (view.heal) 0 else 1) { i ->
            view.heal = i == 0
            edit.retouch.getOrNull(view.spot)?.takeIf { it.heal != view.heal }?.let { s ->
                checkpoint(); edit.retouch = edit.retouch.toMutableList().also { it[view.spot] = s.copy(heal = view.heal) }; changed()
            }
        }
        val size = SliderRow(this, "Size", { if (view.spot >= 0) checkpoint() }) { p ->
            view.spotSize = 0.005f + (p + 100) / 200f * 0.095f
            edit.retouch.getOrNull(view.spot)?.let { s -> edit.retouch = edit.retouch.toMutableList().also { it[view.spot] = s.copy(r = view.spotSize) }; changed() }
        }.apply { bar.progress = Math.round((view.spotSize - 0.005f) / 0.095f * 200 - 100) }
        delete = textButton("Delete spot") {
            edit.retouch.getOrNull(view.spot)?.let { s -> checkpoint(); edit.retouch = edit.retouch - s; view.spot = -1; view.onSpot(); changed() }
        }.apply { isEnabled = false }
        val find = textButton("Find spots") {
            val l = view.look ?: return@textButton
            toast("Looking for dust and blemishes…")
            Thread {
                val found = findSpots(l.base)
                runOnUiThread {
                    if (found.isEmpty()) return@runOnUiThread toast("No spots found")
                    checkpoint(); edit.retouch = edit.retouch + found; changed(); toast("Fixed ${found.size} spot" + if (found.size > 1) "s" else "")
                }
            }.start()
        }
        val clear = textButton("Clear all") { if (edit.retouch.isNotEmpty()) { checkpoint(); edit.retouch = emptyList(); view.spot = -1; view.onSpot(); changed() } }
        sync += { if (view.spot >= edit.retouch.size) { view.spot = -1; view.onSpot() } }
        return column(hint("Tap a spot to fix it. Drag the dashed circle to choose what it copies from."), mode, size, buttons(find, delete, clear))
    }

    private var rebuildPresets: () -> Unit = {}
    private var rebuildProfiles: () -> Unit = {}
    private var rebuildVersions: () -> Unit = {}

    /** Text prompt with a default. */
    private fun ask(title: String, default: String, done: (String) -> Unit) {
        val input = EditText(this).apply { setText(default); selectAll() }
        AlertDialog.Builder(this).setTitle(title).setView(input)
            .setPositiveButton("Save") { _, _ -> input.text.toString().trim().takeIf { it.isNotEmpty() }?.let(done) }
            .setNegativeButton("Cancel", null).show()
    }

    /** Opens the system file picker: 2 = a .cube LUT, 3 = an .xmp preset. */
    private fun pick(code: Int) = startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), code)

    @Deprecated("plain Activity, no AndroidX")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        val uri = data?.data ?: return
        if (res != RESULT_OK) return
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }?.substringBeforeLast('.') ?: "Imported"
        val text = runCatching {
            contentResolver.openInputStream(uri)!!.use { s -> s.readNBytes(8 shl 20).toString(Charsets.UTF_8) } // 8 MB is plenty for a LUT
        }.getOrElse { return toast("Couldn't read that file") }
        when (req) {
            2 -> runCatching { Profiles.importCube(name, text) }
                .onSuccess { rebuildProfiles(); toast("Profile \"$name\" added") }
                .onFailure { toast("Not a 3D .cube LUT: ${it.message}") }
            3 -> xmpPreset(text, name)?.let { (n, json) -> db.savePreset(n, json); rebuildPresets(); toast("Preset \"$n\" added") }
                ?: toast("No settings Lumen understands in that file")
        }
    }

    private fun presetsPanel(): View {
        val list = column()
        fun build() {
            list.removeAllViews()
            db.presets().forEach { p ->
                list.addView(textButton(p.name) { checkpoint(); edit.merge(p.edit, whole = false); refresh() }.apply {
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
        val save = textButton("Save as preset") { ask("Preset name", "My look ${db.presets().size + 1}") { db.savePreset(it, edit.toJson(whole = false)); build() } }
        val copy = textButton("Copy edits") { Clip.edit = edit.toJson(whole = false); pasteBtn.isEnabled = true; toast("Edits copied") }
        pasteBtn = textButton("Paste edits") { Clip.edit?.let { checkpoint(); edit.merge(it, whole = false); refresh() } }
            .apply { isEnabled = Clip.edit != null }
        build()
        rebuildPresets = ::build

        // Profiles: the base look, applied before the tone curve
        val profileHost = FrameLayout(this)
        lateinit var buildProfiles: () -> Unit
        val amount = unit("Amount", { edit.profileAmount }, { edit.profileAmount = it }, register = true, format = percent)
        buildProfiles = {
            profileHost.removeAllViews()
            val names = Profiles.names()
            profileHost.addView(Tabs(this, names, initial = names.indexOf(edit.profile).coerceAtLeast(0)) { i ->
                if (edit.profile != names[i]) { checkpoint(); edit.profile = names[i]; changed() }
            })
        }
        rebuildProfiles = buildProfiles
        buildProfiles()
        sync += { buildProfiles() }

        // Versions: named snapshots of this photo's edit
        val versionList = column()
        fun buildVersions() {
            versionList.removeAllViews()
            val r = row ?: return
            db.versions(r.id).forEach { v ->
                versionList.addView(textButton(v.name) { checkpoint(); view.edit = Edit().merge(v.edit); refresh() }.apply {
                    gravity = Gravity.CENTER_VERTICAL or Gravity.START
                    setPadding(Ui.space(4), 0, Ui.space(4), 0)
                    contentDescription = "Go back to version ${v.name}. Long press to delete."
                    setOnLongClickListener {
                        AlertDialog.Builder(context).setMessage("Delete the version \"${v.name}\"?")
                            .setPositiveButton("Delete") { _, _ -> db.deleteVersion(v.id); buildVersions() }.setNegativeButton("Keep", null).show(); true
                    }
                }, LayoutParams(LayoutParams.MATCH_PARENT, Ui.size("touch")))
            }
        }
        rebuildVersions = ::buildVersions
        val saveVersion = textButton("Save version") {
            val r = row ?: return@textButton
            ask("Version name", "Version ${db.versions(r.id).size + 1}") { db.saveVersion(r.id, it, edit.toJson()); buildVersions() }
        }
        return column(heading("Profile"), profileHost, amount,
            buttons(textButton("Import LUT…") { pick(2) }, textButton("Import preset…") { pick(3) }),
            heading("Presets"), buttons(save, copy, pasteBtn), list,
            heading("Versions"), buttons(saveVersion), versionList)
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
        val albumList = column()
        fun buildAlbums() {
            albumList.removeAllViews()
            val r = row ?: return
            val mine = db.albumsOf(r.id)
            db.albums().forEach { a ->
                albumList.addView(toggle(a.name, a.id in mine) { c -> db.setInAlbum(a.id, r.id, c) })
            }
        }
        syncMarks += { buildAlbums() }
        val newAlbum = textButton("New album") {
            val r = row ?: return@textButton
            ask("Album name", "Album ${db.albums().size + 1}") { db.setInAlbum(db.createAlbum(it), r.id, true); buildAlbums() }
        }
        return column(heading("Rating"), buttons(*stars.toTypedArray()), heading("Flag"), flagTabs, heading("Label"), labelTabs,
            heading("Albums"), albumList, buttons(newAlbum))
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

    private fun changed() { // autosave and histogram, debounced
        view.invalidate()
        ui.removeCallbacks(saveEdit); ui.postDelayed(saveEdit, 300)
        if (::histogram.isInitialized) { ui.removeCallbacks(updateHistogram); ui.postDelayed(updateHistogram, 250) }
    }

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

    private fun load(uri: Uri) = Thread {
        val result = runCatching {
            val (bmp, size) = decode(uri) { 2048f / maxOf(it.width, it.height) } // preview: the shader runs per screen pixel, 2048 is plenty
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
            rebuildVersions()
        }
    }.start()

    private fun keyOf(uri: Uri): String = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
        ?.use { if (it.moveToFirst()) key(it.getString(0), it.getLong(1), uri) else null } ?: uri.toString()

    /** S08: format, size, quality, metadata; Export saves to Pictures/Lumen, Share also hands it to another app. */
    /** S08: format, size, quality, metadata, watermark; Export saves to Pictures/Lumen, Share also hands it to another app. */
    private fun exportDialog() {
        if (source == null) return toast("Open a photo first")
        val o = ExportOptions.saved(this) // remembered between exports
        val formats = listOf("JPEG", "PNG", "WebP")
        val sizes = listOf(0 to "Full size", 4096 to "4096 px", 2048 to "2048 px", 1080 to "1080 px")
        var format = o.format; var long = o.long; var quality = o.quality; var meta = o.meta; var location = o.location
        val qLabel = Ui.type(TextView(this), "sm").apply { setPadding(Ui.space(4), Ui.space(4), Ui.space(4), 0); text = "Quality  $quality" }
        val q = SeekBar(this).apply {
            min = 50; max = 100; progress = quality
            thumbTintList = android.content.res.ColorStateList.valueOf(Ui.color("accent"))
            progressTintList = thumbTintList
            contentDescription = "Quality"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, u: Boolean) { quality = p; qLabel.text = "Quality  $p"; stateDescription = "$p" }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        val mark = EditText(this).apply {
            hint = "Watermark text (optional)"; setText(o.watermark); Ui.type(this, "sm"); setHintTextColor(Ui.color("text-muted"))
        }
        val body = column(
            heading("Format"), Tabs(this, formats, initial = formats.indexOf(format)) { format = formats[it]; q.isEnabled = format != "PNG" },
            heading("Long edge"), Tabs(this, sizes.map { it.second }, initial = sizes.indexOfFirst { it.first == long }) { long = sizes[it].first },
            qLabel, q,
            toggle("Keep date, camera and lens info", meta) { meta = it },
            toggle("Keep location", location) { on ->
                location = on // MediaStore only reveals GPS to apps holding this permission; ask the moment it is wanted
                if (on && checkSelfPermission(android.Manifest.permission.ACCESS_MEDIA_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                    requestPermissions(arrayOf(android.Manifest.permission.ACCESS_MEDIA_LOCATION), 4)
            },
            FrameLayout(this).apply { setPadding(Ui.space(4), 0, Ui.space(4), 0); addView(mark) },
        ).apply { setBackgroundColor(Ui.color("surface")) }
        fun go(share: Boolean) {
            val opts = ExportOptions(format, long, quality, meta, location, mark.text.toString().trim()).also { it.save(this) }
            val uri = source ?: return
            val e = edit.copy()
            toast("Exporting…")
            Thread {
                val out = exportPhoto(uri, e, opts)
                runOnUiThread {
                    if (out == null) return@runOnUiThread toast("Export failed")
                    toast("Exported ${out.second.width}×${out.second.height} $format to Pictures/Lumen")
                    if (share) startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(contentResolver.getType(out.first))
                        .putExtra(Intent.EXTRA_STREAM, out.first).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share photo"))
                }
            }.start()
        }
        AlertDialog.Builder(this).setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton("Export") { _, _ -> go(false) }
            .setNeutralButton("Share") { _, _ -> go(true) }
            .setNegativeButton("Cancel", null).show()
            .apply { listOf(-1, -2, -3).forEach { getButton(it)?.setTextColor(Ui.color("accent")) } } // positive, negative, neutral
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

        val m = Edit().apply { this["exposure"] = 0.4f }.merge("""{"color":{"temp":0.3}}""", whole = false)
        checks["preset merge"] = m["exposure"] == 0.4f && abs(m["temp"] - 0.3f) < 1e-6f
        // masks: a radial mask lifts its centre and leaves the corner; a gradient is full at a, none at b; an unpicked colour mask does nothing
        val flat = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(100, 100, 100)) }
        fun masked(m: Mask) = Look(flat).render(Edit().apply { masks = listOf(m) })
        val radial = masked(Mask("radial").apply { this.g = floatArrayOf(0.5f, 0.5f, 0.25f, 0.25f); adj[0] = 1f })
        checks["radial mask"] = Color.red(radial.getPixel(32, 32)) > 140 && Color.red(radial.getPixel(2, 2)) in 98..102
        val linear = masked(Mask("linear").apply { this.g = floatArrayOf(0.5f, 0f, 0.5f, 0.5f); adj[0] = 1f })
        checks["gradient mask"] = Color.red(linear.getPixel(32, 1)) > 140 && Color.red(linear.getPixel(32, 60)) in 98..102
        checks["unpicked colour mask"] = Color.red(masked(Mask("color").apply { adj[0] = 1f }).getPixel(32, 32)) in 98..102
        val inverted = masked(Mask("radial").apply { this.g = floatArrayOf(0.5f, 0.5f, 0.25f, 0.25f); adj[0] = 1f; invert = true })
        checks["inverted mask"] = Color.red(inverted.getPixel(32, 32)) in 98..102 && Color.red(inverted.getPixel(2, 2)) > 140
        // heal: a dark dot on grey is gone after one spot
        val dotted = flat.copy(Bitmap.Config.ARGB_8888, true).apply { for (x in 30..34) for (y in 30..34) setPixel(x, y, Color.BLACK) }
        val healed = bake(dotted, listOf(Spot(0.5f, 0.5f, 0.2f, 0.2f, 0.08f, heal = true)))
        checks["heal"] = Color.red(healed.getPixel(32, 32)) in 95..105 && Color.red(dotted.getPixel(32, 32)) == 0

        // profiles, LUTs and imported presets
        Profiles.init(this)
        val mono = Look(solid(Color.rgb(200, 40, 40))).render(Edit().apply { profile = "Mono" }).getPixel(0, 0)
        checks["mono profile"] = abs(Color.red(mono) - Color.blue(mono)) < 6
        val cube = Profiles.parseCube("LUT_3D_SIZE 2\n0 0 0\n1 0 0\n0 1 0\n1 1 0\n0 0 1\n1 0 1\n0 1 1\n1 1 1\n")
        checks["cube parse"] = cube.width == 4 && cube.height == 2 && cube.getPixel(3, 0) == Color.rgb(255, 0, 255) && cube.getPixel(1, 1) == Color.rgb(255, 255, 0)
        val xmp = xmpPreset("""<x:xmpmeta><rdf:Description crs:Exposure2012="+1.00" crs:Contrast2012="+20" crs:HueAdjustmentRed="-10">
            <crs:Name><rdf:Alt><rdf:li xml:lang="x-default">Sunny</rdf:li></rdf:Alt></crs:Name>
            <crs:ToneCurvePV2012><rdf:Seq><rdf:li>0, 20</rdf:li><rdf:li>255, 240</rdf:li></rdf:Seq></crs:ToneCurvePV2012></rdf:Description></x:xmpmeta>""", "file")
        val xe = xmp?.let { Edit().merge(it.second, whole = false) }
        checks["xmp preset"] = xmp?.first == "Sunny" && xe != null && abs(xe["exposure"] - 0.5f) < 1e-4f && abs(xe["contrast"] - 0.2f) < 1e-4f &&
            abs(xe["redHue"] + 0.1f) < 1e-4f && abs(xe.curves[0][0].second - 20 / 255f) < 1e-4f && xe["saturation"] == 0f

        // geometry: keystone needs a smaller crop, like straighten
        val ks = Edit().apply { this["vertical"] = 0.6f }
        // keystone stretches outward, so the full frame stays covered and the top corners move out
        val corner = floatArrayOf(0f, 0f).also { ks.shape(300, 200).mapPoints(it) }
        checks["perspective"] = ks.fits(RectF(0f, 0f, 1f, 1f), 300, 200) && corner[0] < -10f && abs(corner[1]) < 1e-3f

        // grading tints the shadows; grain and noise reduction change texture as asked
        val graded = px(solid(Color.rgb(50, 50, 50)), "shadowHue" to 0f, "shadowSat" to 1f)
        checks["grading"] = Color.red(graded) > Color.blue(graded) + 8
        fun spread(b: Bitmap) = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }.map { Color.green(it).toDouble() }
            .let { v -> val m = v.average(); kotlin.math.sqrt(v.sumOf { (it - m) * (it - m) } / v.size) } // standard deviation
        checks["grain"] = spread(Look(flat).render(Edit().apply { this["grain"] = 1f })) > 2 && spread(Look(flat).render(Edit())) < 0.5
        val rnd = java.util.Random(7)
        val noisy = Bitmap.createBitmap(IntArray(64 * 64) { val v = 100 + rnd.nextInt(41) - 20; Color.rgb(v, v, v) }, 64, 64, Bitmap.Config.ARGB_8888)
        checks["noise reduction"] = spread(Look(noisy).render(Edit().apply { this["noise"] = 1f })) < spread(Look(noisy).render(Edit()))

        // auto tone, level, spot finding
        checks["auto tone"] = (autoTone(solid(Color.rgb(30, 30, 30)))["exposure"] ?: 0f) > 0.3f
        val tilted = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            val c = android.graphics.Canvas(this); c.rotate(5f, 100f, 100f)
            val ink = android.graphics.Paint().apply { color = Color.BLACK; strokeWidth = 3f }
            for (y in -100..300 step 20) c.drawLine(-100f, y.toFloat(), 300f, y.toFloat(), ink)
        }
        val level = autoLevel(tilted)
        checks["auto level"] = abs(level + 5f / 45f) < 0.03f
        val specked = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(150, 150, 150)); for (x in 98..101) for (y in 98..101) setPixel(x, y, Color.rgb(60, 60, 60))
        }
        checks["find spots"] = findSpots(specked).let { it.size == 1 && abs(it[0].dx - 0.5f) < 0.02f && abs(it[0].dy - 0.5f) < 0.02f }

        val j = Edit().apply {
            profile = "Film"; profileAmount = 0.7f; this["vertical"] = 0.1f; this["grain"] = 0.3f; this["shadowHue"] = 0.6f
            this["clarity"] = 0.2f; curves = listOf(listOf(0f to 0.1f, 1f to 0.9f), LINE, LINE, LINE); rotateRight()
            masks = listOf(Mask("brush").apply { adj[2] = -0.5f; strokes = listOf(Stroke(floatArrayOf(0.1f, 0.1f, 0.3f, 0.4f), 0.05f, false)) })
            retouch = listOf(Spot(0.5f, 0.5f, 0.6f, 0.5f, 0.02f, heal = false))
        }
        val back = Edit().merge(j.toJson())
        checks["json round trip"] = back.toJson() == j.toJson() && back.masks.size == 1 && back.retouch.size == 1 &&
            Edit().merge(j.toJson(whole = false)).masks.isEmpty() // presets and paste never carry masks

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
