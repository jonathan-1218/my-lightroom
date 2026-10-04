package dev.lumen

import android.Manifest.permission.READ_MEDIA_IMAGES
import android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
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

private class Photo(val uri: Uri, val takenAt: Long)

/** Square grid cell. */
private class Cell(ctx: Context) : ImageView(ctx) {
    init { scaleType = ScaleType.CENTER_CROP; setBackgroundColor(Ui.color("surface")) } // not focusable: GridView owns focus, a focusable child swallows item clicks
    override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, w)
}

/** S01: every photo on the phone, newest first. Tap one to edit it. */
class LibraryActivity : Activity() {
    private val perms = if (Build.VERSION.SDK_INT >= 34) arrayOf(READ_MEDIA_IMAGES, READ_MEDIA_VISUAL_USER_SELECTED) else arrayOf(READ_MEDIA_IMAGES)
    private val pool = Executors.newFixedThreadPool(3)
    private val thumbs = object : LruCache<Uri, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(k: Uri, b: Bitmap) = b.allocationByteCount
    }
    private var photos = listOf<Photo>()
    private var asked = false
    private lateinit var grid: GridView
    private lateinit var state: LinearLayout
    private lateinit var more: View

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        Ui.init(this)
        val top = LinearLayout(this).apply {
            setBackgroundColor(Ui.color("surface"))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.space(4), 0, Ui.space(2), 0)
            addView(Ui.type(TextView(context), "lg").apply { text = "Photos"; isAccessibilityHeading = true }, LayoutParams(0, -2, 1f))
            more = textButton("Choose more") { requestPermissions(perms, 1) }.also { addView(it) }
            addView(textButton("Pick") { pick() })
        }
        grid = GridView(this).apply {
            numColumns = GridView.AUTO_FIT
            columnWidth = Ui.dp(112)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            horizontalSpacing = Ui.dp(2); verticalSpacing = Ui.dp(2)
            adapter = Adapter()
            setOnItemClickListener { _, _, i, _ -> edit(photos[i].uri) }
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
            addView(grid, LayoutParams(-1, 0, 1f))
            addView(state, LayoutParams(-1, 0, 1f))
            setOnApplyWindowInsetsListener { v, ins ->
                val b = ins.getInsets(android.view.WindowInsets.Type.systemBars())
                v.setPadding(b.left, b.top, b.right, b.bottom); ins
            }
        })
    }

    override fun onResume() { super.onResume(); refresh() } // permissions or photos may have changed while away

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
            runOnUiThread {
                when {
                    list == null -> show("Couldn't read your photos.", "Try again") { refresh() }
                    list.isEmpty() -> show("No photos here yet. Take one, or pick one from another app.", "Pick a photo") { pick() }
                    else -> { photos = list; (grid.adapter as BaseAdapter).notifyDataSetChanged(); show(null) }
                }
            }
        }
    }

    private fun query(): List<Photo> {
        val cols = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED)
        return contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cols, null, null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC")!!.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val taken = if (c.isNull(1)) c.getLong(2) * 1000 else c.getLong(1)
                    add(Photo(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)), taken))
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

    private fun pick() = startActivityForResult(Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*"), 1)

    @Deprecated("plain Activity, no AndroidX")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        if (res == RESULT_OK) data?.data?.let(::edit)
    }

    private fun edit(uri: Uri) = startActivity(Intent(this, MainActivity::class.java).setData(uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))

    override fun onDestroy() { pool.shutdownNow(); super.onDestroy() }

    private inner class Adapter : BaseAdapter() {
        private val dates = DateFormat.getDateInstance(DateFormat.MEDIUM)
        override fun getCount() = photos.size
        override fun getItem(i: Int) = photos[i]
        override fun getItemId(i: Int) = i.toLong()
        override fun getView(i: Int, old: View?, parent: ViewGroup): View {
            val cell = (old as? Cell) ?: Cell(this@LibraryActivity)
            val p = photos[i]
            cell.tag = p.uri
            cell.contentDescription = "Photo from ${dates.format(Date(p.takenAt))}"
            val hit = thumbs.get(p.uri)
            cell.setImageBitmap(hit)
            if (hit == null) pool.execute {
                val b = runCatching { contentResolver.loadThumbnail(p.uri, Size(320, 320), null) }.getOrNull() ?: return@execute
                thumbs.put(p.uri, b)
                runOnUiThread { if (cell.tag == p.uri) cell.setImageBitmap(b) } // cell may be recycled by now
            }
            return cell
        }
    }
}
