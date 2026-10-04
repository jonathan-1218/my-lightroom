package dev.lumen

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri

class Row(val id: Long, val edit: String, val rating: Int, val flag: Int, val label: String?)
class Preset(val id: Long, val name: String, val edit: String)

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

    /** The photo's row, created on first open. */
    fun open(key: String, uri: Uri, w: Int, h: Int): Row {
        val now = System.currentTimeMillis()
        writableDatabase.execSQL(
            "insert into photos (key, uri, added_at, updated_at, width, height) values (?,?,?,?,?,?) " +
                "on conflict (key) do update set uri = excluded.uri",
            arrayOf(key, uri.toString(), now, now, w, h))
        readableDatabase.rawQuery("select id, edit, rating, flag, label from photos where key = ?", arrayOf(key)).use {
            it.moveToFirst()
            return Row(it.getLong(0), it.getString(1), it.getInt(2), it.getInt(3), it.getString(4))
        }
    }

    fun saveEdit(id: Long, edit: String) = writableDatabase.execSQL(
        "update photos set edit = ?, updated_at = ? where id = ?", arrayOf(edit, System.currentTimeMillis(), id))

    /** rating 0..5, flag -1/0/1, label colour or null. The schema's check constraints reject anything else. */
    fun mark(id: Long, column: String, value: Any?) {
        require(column in setOf("rating", "flag", "label"))
        writableDatabase.execSQL("update photos set $column = ?, updated_at = ? where id = ?", arrayOf(value, System.currentTimeMillis(), id))
    }

    /** key → row, for photos that have a rating, flag or label (the library grid's badges). */
    fun marks(): Map<String, Row> = readableDatabase.rawQuery(
        "select key, id, rating, flag, label from photos where rating > 0 or flag != 0 or label is not null", null).use { c ->
        buildMap { while (c.moveToNext()) put(c.getString(0), Row(c.getLong(1), "", c.getInt(2), c.getInt(3), c.getString(4))) }
    }

    fun presets(): List<Preset> = readableDatabase.rawQuery("select id, name, edit from presets order by created_at, name", null).use { c ->
        buildList { while (c.moveToNext()) add(Preset(c.getLong(0), c.getString(1), c.getString(2))) }
    }

    fun savePreset(name: String, edit: String) {
        val now = System.currentTimeMillis()
        writableDatabase.execSQL(
            "insert into presets (name, edit, created_at, updated_at) values (?,?,?,?) " +
                "on conflict (name) do update set edit = excluded.edit, updated_at = excluded.updated_at",
            arrayOf(name, edit, now, now))
    }

    fun deletePreset(id: Long) = writableDatabase.execSQL("delete from presets where id = ?", arrayOf(id))
}
