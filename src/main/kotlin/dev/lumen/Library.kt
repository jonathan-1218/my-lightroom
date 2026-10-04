package dev.lumen

import android.Manifest.permission.READ_MEDIA_IMAGES
import android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams
import android.widget.TextView
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

private class Photo(val uri: Uri, val takenAt: Long, val key: String, val w: Int, val h: Int)

/** Square grid cell with its marks: stars, pick flag, colour label, edited dot, selection; rejects are dimmed. */
private class Cell(ctx: Context) : ImageView(ctx) {
    var mark: Row? = null
    var edited = false
    var checked = false // in the batch selection (not View.isSelected)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.color("text"); textSize = Ui.dp(12).toFloat(); setShadowLayer(Ui.dp(2).toFloat(), 0f, 0f, Ui.color("bg"))
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scrim = Paint().apply { color = Ui.color("scrim") }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = Ui.dp(3).toFloat(); color = Ui.color("accent") }

    init { scaleType = ScaleType.CENTER_CROP; setBackgroundColor(Ui.color("surface")) } // not focusable: GridView owns focus, a focusable child swallows item clicks
    override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, w)

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val pad = Ui.dp(6).toFloat()
        mark?.let { m ->
            if (m.flag < 0) c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrim)
            if (m.rating > 0) c.drawText("★".repeat(m.rating), pad, height - pad, text)
            if (m.flag > 0) c.drawText("⚑", pad, pad - text.ascent(), text)
            m.label?.let { dot.color = Ui.color("label-$it"); c.drawCircle(width - pad * 2, pad * 2, pad, dot) }
        }
        if (edited) c.drawText("±", width - pad - text.measureText("±"), height - pad, text)
        if (checked) {
            c.drawRect(ring.strokeWidth / 2, ring.strokeWidth / 2, width - ring.strokeWidth / 2, height - ring.strokeWidth / 2, ring)
            dot.color = Ui.color("accent"); c.drawCircle(pad * 2.5f, pad * 2.5f, pad * 1.5f, dot)
        }
    }
}

/** S01: every photo on the phone, newest first. Tap to edit; long press to select several for batch actions. */
class LibraryActivity : Activity() {
    private val perms = if (Build.VERSION.SDK_INT >= 34) arrayOf(READ_MEDIA_IMAGES, READ_MEDIA_VISUAL_USER_SELECTED) else arrayOf(READ_MEDIA_IMAGES)
    private val pool = Executors.newFixedThreadPool(3)
    private val thumbs = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(k: String, b: Bitmap) = b.allocationByteCount
    }
    private var all = listOf<Photo>()
    private var photos = listOf<Photo>()
    private var marks = mapOf<String, Row>()
    private var edited = mapOf<String, Pair<String, Long>>()
    private var filter = 0
    private var filters = listOf<Pair<String, (Photo) -> Boolean>>()
    private val selected = LinkedHashSet<String>()
    private val db by lazy { Db(this) }
    private var asked = false
    private lateinit var grid: GridView
    private lateinit var state: LinearLayout
    private lateinit var more: View
    private lateinit var title: TextView
    private lateinit var normalBar: View
    private lateinit var batchBar: View
    private lateinit var filterHost: android.widget.FrameLayout

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        Ui.init(this)
        Profiles.init(this)
        title = Ui.type(TextView(this), "lg").apply { text = "Photos"; isAccessibilityHeading = true }
        normalBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            more = textButton("Choose more") { requestPermissions(perms, 1) }.also { addView(it) }
            addView(textButton("Pick") { pick() })
        }
        batchBar = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(LinearLayout(context).apply {
                listOf("Paste" to ::batchPaste, "Preset" to ::batchPreset, "Rate" to ::batchRate, "Album" to ::batchAlbum,
                    "Export" to ::batchExport, "Done" to { selected.clear(); selectionChanged() }).forEach { (t, f) -> addView(textButton(t) { f() }) }
            })
            visibility = View.GONE
        }
        val top = LinearLayout(this).apply {
            setBackgroundColor(Ui.color("surface"))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.space(4), 0, Ui.space(2), 0)
            addView(title)
            addView(View(context), LayoutParams(0, 0, 1f))
            addView(normalBar); addView(batchBar, LayoutParams(0, -2, 3f))
        }
        filterHost = android.widget.FrameLayout(this).apply { setBackgroundColor(Ui.color("surface")) }
        grid = GridView(this).apply {
            numColumns = GridView.AUTO_FIT
            columnWidth = Ui.dp(112)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            horizontalSpacing = Ui.dp(2); verticalSpacing = Ui.dp(2)
            adapter = Adapter()
            setOnItemClickListener { _, _, i, _ -> if (selected.isEmpty()) edit(photos[i].uri) else toggle(photos[i]) }
            setOnItemLongClickListener { _, _, i, _ -> toggle(photos[i]); true }
        }
        state = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(Ui.space(6), 0, Ui.space(6), 0)
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.color("bg"))
            addView(top, LayoutParams(-1, Ui.size("topbar")))
            addView(filterHost)
            addView(grid, LayoutParams(-1, 0, 1f))
            addView(state, LayoutParams(-1, 0, 1f))
            setOnApplyWindowInsetsListener { v, ins ->
                val b = ins.getInsets(android.view.WindowInsets.Type.systemBars())
                v.setPadding(b.left, b.top, b.right, b.bottom); ins
            }
        })
    }

    override fun onResume() { super.onResume(); refresh() } // permissions, photos or edits may have changed while away

    override fun onRequestPermissionsResult(code: Int, p: Array<String>, r: IntArray) { asked = true; refresh() }

    private fun granted(p: String) = checkSelfPermission(p) == PERMISSION_GRANTED

    private fun refresh() {
        val full = granted(READ_MEDIA_IMAGES)
        val partial = !full && Build.VERSION.SDK_INT >= 34 && granted(READ_MEDIA_VISUAL_USER_SELECTED)
        more.visibility = if (partial) View.VISIBLE else View.GONE
        if (!full && !partial) {
            // Denied twice: Android stops showing the prompt, so send them to Settings instead.
            val blocked = asked && !shouldShowRequestPermissionRationale(READ_MEDIA_IMAGES)
            return show("Lumen edits the photos on this phone. Allow access to see them here, or pick one photo at a time.",
                if (blocked) "Open settings" else "Allow access") {
                if (blocked) startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                else requestPermissions(perms, 1)
            }
        }
        pool.execute {
            val list = runCatching { query() }.getOrNull()
            val m = runCatching { db.marks() }.getOrDefault(emptyMap())
            val ed = runCatching { db.edited() }.getOrDefault(emptyMap())
            val albums = runCatching { db.albums().map { it.name to db.albumKeys(it.id) } }.getOrDefault(emptyList())
            runOnUiThread {
                when {
                    list == null -> show("Couldn't read your photos.", "Try again") { refresh() }
                    list.isEmpty() -> show("No photos here yet. Take one, or pick one from another app.", "Pick a photo") { pick() }
                    else -> {
                        all = list; marks = m; edited = ed
                        buildFilters(albums)
                        applyFilter(); show(null)
                    }
                }
            }
        }
    }

    /** Filter chips: marks, edited, labels that are in use, then albums. */
    private fun buildFilters(albums: List<Pair<String, Set<String>>>) {
        val current = filters.getOrNull(filter)?.first
        val labels = marks.values.mapNotNull { it.label }.toSet()
        filters = listOf<Pair<String, (Photo) -> Boolean>>(
            "All" to { _ -> true },
            "Picks" to { p -> marks[p.key]?.flag == 1 },
            "3★ +" to { p -> (marks[p.key]?.rating ?: 0) >= 3 },
            "Rejects" to { p -> marks[p.key]?.flag == -1 },
            "Edited" to { p -> p.key in edited },
        ) + listOf("red", "yellow", "green", "blue", "purple").filter { it in labels }.map { l -> l.replaceFirstChar(Char::uppercase) to { p: Photo -> marks[p.key]?.label == l } } +
            albums.map { (name, keys) -> name to { p: Photo -> p.key in keys } }
        filter = filters.indexOfFirst { it.first == current }.coerceAtLeast(0)
        filterHost.removeAllViews()
        filterHost.addView(Tabs(this, filters.map { it.first }, initial = filter) { i -> filter = i; applyFilter() })
    }

    private fun applyFilter() {
        val f = filters.getOrNull(filter)?.second ?: { _: Photo -> true }
        photos = all.filter(f)
        (grid.adapter as BaseAdapter).notifyDataSetChanged()
        if (photos.isEmpty() && all.isNotEmpty()) show("Nothing matches this filter.", "Show all") { filter = 0; buildFilters(emptyList()); refresh() }
        else show(null)
    }

    private fun query(): List<Photo> {
        val cols = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.SIZE, MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT)
        return contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cols, null, null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC")!!.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val taken = if (c.isNull(1)) c.getLong(2) * 1000 else c.getLong(1)
                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
                    add(Photo(uri, taken, key(c.getString(3), c.getLong(4), uri), c.getInt(5), c.getInt(6)))
                }
            }
        }
    }

    /** Empty, error and no-permission states share one centred message + action. `null` shows the grid. */
    private fun show(message: String?, action: String = "", onAction: () -> Unit = {}) {
        grid.visibility = if (message == null) View.VISIBLE else View.GONE
        state.visibility = if (message == null) View.GONE else View.VISIBLE
        state.removeAllViews()
        if (message == null) return
        state.addView(Ui.type(TextView(this), "base", "text-muted").apply { text = message; gravity = Gravity.CENTER })
        state.addView(textButton(action, onAction).apply { setTextColor(Ui.color("accent")) })
    }

    // ---- selection and batch actions ----

    private fun toggle(p: Photo) { if (!selected.remove(p.key)) selected += p.key; selectionChanged() }

    private fun selectionChanged() {
        val on = selected.isNotEmpty()
        title.text = if (on) "${selected.size} selected" else "Photos"
        normalBar.visibility = if (on) View.GONE else View.VISIBLE
        batchBar.visibility = if (on) View.VISIBLE else View.GONE
        (grid.adapter as BaseAdapter).notifyDataSetChanged()
    }

    private fun chosen() = all.filter { it.key in selected }

    /** Runs `each(photo, row id)` for every selected photo off the main thread, then refreshes. */
    private fun batch(done: String, each: (Photo, Long) -> Unit) {
        val list = chosen()
        pool.execute {
            list.forEach { p -> runCatching { each(p, db.ensure(p.key, p.uri, p.w, p.h)) }.onFailure { Log.w("Lumen", "batch ${p.key}", it) } }
            runOnUiThread { toast(done.format(list.size)); selected.clear(); selectionChanged(); refresh() }
        }
    }

    private fun mergeInto(json: String) = batch("Applied to %d photos") { _, id ->
        db.saveEdit(id, Edit().merge(db.editOf(id)).merge(json, whole = false).toJson())
    }

    private fun batchPaste() { mergeInto(Clip.edit ?: return toast("Copy edits in the editor first")) }

    private fun batchPreset() {
        val presets = db.presets()
        dialog().setTitle("Apply preset").setItems(presets.map { it.name }.toTypedArray()) { _, i -> mergeInto(presets[i].edit) }.show()
    }

    private fun batchRate() {
        val choices = listOf("No stars" to ("rating" to 0)) + (1..5).map { "★".repeat(it) to ("rating" to it) } +
            listOf("Pick" to ("flag" to 1), "Reject" to ("flag" to -1), "Unflag" to ("flag" to 0))
        dialog().setTitle("Rate").setItems(choices.map { it.first }.toTypedArray()) { _, i ->
            val (col, v) = choices[i].second
            batch("Updated %d photos") { _, id -> db.mark(id, col, v) }
        }.show()
    }

    private fun batchAlbum() {
        val albums = db.albums()
        dialog().setTitle("Add to album").setItems((albums.map { it.name } + "New album…").toTypedArray()) { _, i ->
            if (i < albums.size) batch("Added %d photos") { _, id -> db.setInAlbum(albums[i].id, id, true) }
            else {
                val input = android.widget.EditText(this).apply { setText("Album ${albums.size + 1}"); selectAll() }
                dialog().setTitle("Album name").setView(input).setPositiveButton("Create") { _, _ ->
                    val name = input.text.toString().trim().ifEmpty { return@setPositiveButton }
                    val album = db.createAlbum(name)
                    batch("Added %d photos to $name") { _, id -> db.setInAlbum(album, id, true) }
                }.setNegativeButton("Cancel", null).show()
            }
        }.show()
    }

    /** Exports every selected photo with the last export settings, one after another. */
    private fun batchExport() {
        val list = chosen()
        val opts = ExportOptions.saved(this)
        toast("Exporting ${list.size} photos as ${opts.format}…")
        selected.clear(); selectionChanged()
        Thread {
            var ok = 0
            list.forEachIndexed { n, p ->
                val e = Edit().merge(db.editOf(db.ensure(p.key, p.uri, p.w, p.h)))
                if (exportPhoto(p.uri, e, opts) != null) ok++
                runOnUiThread { toast("Exported ${n + 1} of ${list.size}") }
            }
            runOnUiThread { toast(if (ok == list.size) "All $ok exported to Pictures/Lumen" else "$ok of ${list.size} exported"); refresh() }
        }.start()
    }

    private fun dialog() = android.app.AlertDialog.Builder(this)
    private fun toast(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()

    private fun pick() = startActivityForResult(Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*"), 1)

    @Deprecated("plain Activity, no AndroidX")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        if (res == RESULT_OK) data?.data?.let(::edit)
    }

    private fun edit(uri: Uri) = startActivity(Intent(this, MainActivity::class.java).setData(uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))

    override fun onDestroy() { pool.shutdownNow(); super.onDestroy() }

    /** Thumbnail with the edit applied, cached on disk by key + edit time. */
    private fun editedThumb(p: Photo, json: String, at: Long): Bitmap? {
        val file = java.io.File(cacheDir, "thumbs/${(p.key + at).hashCode().toUInt()}.jpg")
        if (file.exists()) return android.graphics.BitmapFactory.decodeFile(file.path)
        val e = Edit().merge(json)
        val (src, _) = decode(p.uri) { s -> e.outSize(s.width, s.height).let { (w, h) -> 400f / minOf(w, h) } }
        val out = Look(src).render(e)
        file.parentFile?.mkdirs()
        file.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        return out
    }

    private inner class Adapter : BaseAdapter() {
        private val dates = DateFormat.getDateInstance(DateFormat.MEDIUM)
        override fun getCount() = photos.size
        override fun getItem(i: Int) = photos[i]
        override fun getItemId(i: Int) = i.toLong()
        override fun getView(i: Int, old: View?, parent: ViewGroup): View {
            val cell = (old as? Cell) ?: Cell(this@LibraryActivity)
            val p = photos[i]
            val ed = edited[p.key]
            val cacheKey = p.key + (ed?.second ?: "")
            cell.tag = cacheKey
            cell.mark = marks[p.key]
            cell.edited = ed != null
            cell.checked = p.key in selected
            cell.contentDescription = "Photo from ${dates.format(Date(p.takenAt))}" + listOfNotNull(
                cell.mark?.let { m -> if (m.rating > 0) "${m.rating} stars" else null },
                cell.mark?.let { m -> if (m.flag > 0) "picked" else if (m.flag < 0) "rejected" else null },
                cell.mark?.label, if (ed != null) "edited" else null, if (cell.checked) "selected" else null,
            ).joinToString("") { ", $it" }
            cell.invalidate()
            val hit = thumbs.get(cacheKey)
            cell.setImageBitmap(hit)
            if (hit == null) pool.execute {
                val b = runCatching {
                    if (ed != null) editedThumb(p, ed.first, ed.second) else contentResolver.loadThumbnail(p.uri, Size(320, 320), null)
                }.onFailure { Log.w("Lumen", "thumb ${p.key}", it) }.getOrNull() ?: return@execute
                thumbs.put(cacheKey, b)
                runOnUiThread { if (cell.tag == cacheKey) cell.setImageBitmap(b) } // cell may be recycled by now
            }
            return cell
        }
    }
}
