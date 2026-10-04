package dev.lumen

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import org.json.JSONObject

// Edit document sections (see replica/architecture.md). SLIDERS order comes from here.
val SECTIONS = linkedMapOf(
    "light" to listOf("exposure", "contrast", "highlights", "shadows", "whites", "blacks"),
    "color" to listOf("temp", "tint", "vibrance", "saturation"),
    "effects" to listOf("vignette"),
)

fun toJson(v: FloatArray): String = JSONObject().put("v", 1).apply {
    SECTIONS.forEach { (section, names) ->
        put(section, JSONObject().apply { names.forEach { put(it, v[SLIDERS.indexOf(it)].toDouble()) } })
    }
}.toString()

fun fromJson(s: String): FloatArray {
    val o = JSONObject(s)
    return FloatArray(SLIDERS.size).also { v ->
        SECTIONS.forEach { (section, names) ->
            val sec = o.optJSONObject(section) ?: return@forEach
            names.forEach { v[SLIDERS.indexOf(it)] = sec.optDouble(it, 0.0).toFloat() }
        }
    }
}

/** App-private SQLite. `name = null` gives an in-memory DB (self-test). */
class Db(private val ctx: Context, name: String? = "lumen.db") : SQLiteOpenHelper(ctx, name, null, 1) {
    override fun onConfigure(db: SQLiteDatabase) = db.setForeignKeyConstraintsEnabled(true)

    override fun onCreate(db: SQLiteDatabase) =
        ctx.assets.open("schema.sql").bufferedReader().use { it.readText() }
            .lines().filterNot { it.trimStart().startsWith("--") }.joinToString("\n")
            .split(';').map { it.trim() }.filter { it.isNotEmpty() }
            .forEach(db::execSQL)

    // ponytail: no migrations until schema v2 exists, then run assets/migrate_<n>.sql here
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {}

    /** Row id and saved edit for this photo, creating the row on first open. */
    fun open(key: String, uri: Uri, w: Int, h: Int): Pair<Long, String> {
        val now = System.currentTimeMillis()
        writableDatabase.execSQL(
            "insert into photos (key, uri, added_at, updated_at, width, height) values (?,?,?,?,?,?) " +
                "on conflict (key) do update set uri = excluded.uri",
            arrayOf(key, uri.toString(), now, now, w, h))
        readableDatabase.rawQuery("select id, edit from photos where key = ?", arrayOf(key)).use {
            it.moveToFirst()
            return it.getLong(0) to it.getString(1)
        }
    }

    fun saveEdit(id: Long, edit: String) = writableDatabase.execSQL(
        "update photos set edit = ?, updated_at = ? where id = ?", arrayOf(edit, System.currentTimeMillis(), id))
}
