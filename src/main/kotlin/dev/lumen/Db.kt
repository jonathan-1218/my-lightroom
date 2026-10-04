package dev.lumen

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri

class Row(val id: Long, val edit: String, val rating: Int, val flag: Int, val label: String?)
class Preset(val id: Long, val name: String, val edit: String)

/** App-private SQLite. `name = null` gives an in-memory DB (self-test). Screens share one instance: `Db.get(context)`. */
class Db(private val ctx: Context, name: String? = "lumen.db") : SQLiteOpenHelper(ctx, name, null, VERSION) {
    companion object {
        val TOLD = "*|" + "[0-9a-f]".repeat(16) // a key from told()
        const val VERSION = 2 // schema.sql is v1; migrate_<n>.sql takes it to n (the Mac app runs the same files)
        @Volatile private var shared: Db? = null
        fun get(ctx: Context): Db = shared ?: synchronized(this) { shared ?: Db(ctx.applicationContext).also { shared = it } }
    }

    /** All-or-nothing: batch work can't leave half the photos updated, and runs much faster. */
    fun <T> tx(block: () -> T): T {
        val d = writableDatabase
        d.beginTransaction()
        try { return block().also { d.setTransactionSuccessful() } } finally { d.endTransaction() }
    }

    override fun onConfigure(db: SQLiteDatabase) = db.setForeignKeyConstraintsEnabled(true)

    /** Statements of a .sql file in assets: each ends at a ";" that ends a line (trigger bodies keep theirs). */
    fun statements(file: String) = ctx.assets.open(file).bufferedReader().use { it.readText() }
        .lines().filterNot { it.trimStart().startsWith("--") }.joinToString("\n")
        .split(Regex(";\\s*\n")).map { it.trim().removeSuffix(";") }.filter { it.isNotEmpty() }

    private fun run(db: SQLiteDatabase, file: String) = statements(file).forEach(db::execSQL)

    // both run inside SQLiteOpenHelper's transaction: a failed step leaves the old version untouched
    override fun onCreate(db: SQLiteDatabase) { run(db, "schema.sql"); onUpgrade(db, 1, VERSION) }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) { for (v in old + 1..new) run(db, "migrate_$v.sql") }

    /** Plain name|size keys whose files were told apart: they keep hashed keys even after one of the twins is gone. */
    fun split(): Set<String> = readableDatabase.rawQuery("select substr(key, 1, length(key) - 17) from photos where key glob ?", arrayOf(TOLD)).use { c ->
        buildSet { while (c.moveToNext()) add(c.getString(0)) }
    }

    private fun has(key: String) = readableDatabase.rawQuery("select 1 from photos where key = ?", arrayOf(key)).use { it.count > 0 }

    /** The photo's row, created on first open. `shared` is the plain name|size key when `key` tells apart files sharing it. */
    fun open(key: String, uri: Uri, w: Int, h: Int, shared: String = key): Row = tx {
        val now = System.currentTimeMillis()
        // Same file under a new key (its size changed, e.g. another app rewrote the metadata): keep its edits.
        if (!has(key)) writableDatabase.execSQL("update photos set key = ? where uri = ? and not exists (select 1 from photos where key = ?)",
            arrayOf(key, uri.toString(), key))
        // Two files with one name and size used to share a row. The first told apart takes it (versions, albums);
        // the others start from a copy of it, so each keeps what it showed.
        if (!has(key) && shared != key) {
            if (has(shared)) writableDatabase.execSQL("update photos set key = ? where key = ?", arrayOf(key, shared))
            else writableDatabase.execSQL("insert into photos (key, uri, added_at, updated_at, width, height, rating, flag, label, edit) " +
                "select ?, ?, ?, ?, ?, ?, rating, flag, label, edit from photos where substr(key, 1, length(?) + 1) = ? || '|' limit 1",
                arrayOf(key, uri.toString(), now, now, maxOf(1, w), maxOf(1, h), shared, shared))
        }
        writableDatabase.execSQL(
            "insert into photos (key, uri, added_at, updated_at, width, height) values (?,?,?,?,?,?) " +
                "on conflict (key) do update set uri = excluded.uri, " +
                "width = case when excluded.width > 1 then excluded.width else width end, " +
                "height = case when excluded.height > 1 then excluded.height else height end",
            arrayOf(key, uri.toString(), now, now, maxOf(1, w), maxOf(1, h)))
        readableDatabase.rawQuery("select id, edit, rating, flag, label from photos where key = ?", arrayOf(key)).use {
            it.moveToFirst()
            Row(it.getLong(0), it.getString(1), it.getInt(2), it.getInt(3), it.getString(4))
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

    /** Row for a photo seen only in the library (batch edits, albums), without decoding it. */
    fun ensure(key: String, uri: Uri, w: Int, h: Int, shared: String = key): Long = open(key, uri, w.coerceAtLeast(1), h.coerceAtLeast(1), shared).id

    /** key → (edit, updated_at) for photos whose edit is not the default: the library renders these thumbnails. */
    fun edited(): Map<String, Pair<String, Long>> {
        val blank = Edit().toJson()
        return readableDatabase.rawQuery("select key, edit, updated_at from photos where edit != '{\"v\":1}'", null).use { c ->
            buildMap { while (c.moveToNext()) if (Edit().merge(c.getString(1)).toJson() != blank) put(c.getString(0), c.getString(1) to c.getLong(2)) }
        }
    }

    // ---- S07 versions ----
    class Version(val id: Long, val name: String, val edit: String)
    fun versions(photo: Long): List<Version> = readableDatabase.rawQuery(
        "select id, name, edit from versions where photo_id = ? order by created_at, id", arrayOf(photo.toString())).use { c ->
        buildList { while (c.moveToNext()) add(Version(c.getLong(0), c.getString(1), c.getString(2))) }
    }
    fun saveVersion(photo: Long, name: String, edit: String) = writableDatabase.execSQL(
        "insert into versions (photo_id, name, edit, created_at) values (?,?,?,?)", arrayOf(photo, name, edit, System.currentTimeMillis()))
    fun deleteVersion(id: Long) = writableDatabase.execSQL("delete from versions where id = ?", arrayOf(id))

    // ---- albums ----
    class Album(val id: Long, val name: String)
    fun albums(): List<Album> = readableDatabase.rawQuery("select id, name from albums order by name", null).use { c ->
        buildList { while (c.moveToNext()) add(Album(c.getLong(0), c.getString(1))) }
    }
    fun createAlbum(name: String): Long {
        val now = System.currentTimeMillis()
        writableDatabase.execSQL("insert or ignore into albums (name, created_at, updated_at) values (?,?,?)", arrayOf(name, now, now))
        return readableDatabase.rawQuery("select id from albums where name = ?", arrayOf(name)).use { it.moveToFirst(); it.getLong(0) }
    }
    fun setInAlbum(album: Long, photo: Long, inside: Boolean) = if (inside)
        writableDatabase.execSQL("insert or ignore into album_photos (album_id, photo_id, added_at) values (?,?,?)", arrayOf(album, photo, System.currentTimeMillis()))
        else writableDatabase.execSQL("delete from album_photos where album_id = ? and photo_id = ?", arrayOf(album, photo))
    fun albumsOf(photo: Long): Set<Long> = readableDatabase.rawQuery("select album_id from album_photos where photo_id = ?", arrayOf(photo.toString())).use { c ->
        buildSet { while (c.moveToNext()) add(c.getLong(0)) }
    }
    /** Photo keys in an album. */
    fun albumKeys(album: Long): Set<String> = readableDatabase.rawQuery(
        "select p.key from album_photos a join photos p on p.id = a.photo_id where a.album_id = ?", arrayOf(album.toString())).use { c ->
        buildSet { while (c.moveToNext()) add(c.getString(0)) }
    }
    fun deleteAlbum(id: Long) = writableDatabase.execSQL("delete from albums where id = ?", arrayOf(id))
    fun editOf(id: Long): String = readableDatabase.rawQuery("select edit from photos where id = ?", arrayOf(id.toString())).use { it.moveToFirst(); it.getString(0) }

    fun presets(): List<Preset> = readableDatabase.rawQuery("select id, name, edit from presets order by created_at, name, id", null).use { c ->
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
