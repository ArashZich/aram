package ir.arashzich.hush

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** The inbox and its history: every notification Hush took out of the shade. Cleared rows stay as history. */
class Db private constructor(c: Context) : SQLiteOpenHelper(c, "inbox.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE items (id INTEGER PRIMARY KEY AUTOINCREMENT, pkg TEXT NOT NULL, nkey TEXT NOT NULL, " +
                "title TEXT NOT NULL, body TEXT NOT NULL, time INTEGER NOT NULL, " +
                "read INTEGER NOT NULL DEFAULT 0, cleared INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL("CREATE INDEX items_open ON items(cleared, pkg)")
        db.execSQL("CREATE INDEX items_key ON items(nkey)")
        days(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            days(db)
            db.execSQL("INSERT OR IGNORE INTO days SELECT CAST(julianday(time / 1000, 'unixepoch', 'localtime') - 2440587.5 AS INTEGER), " +
                "pkg, COUNT(*) FROM items GROUP BY 1, 2")
        }
    }

    // How many notifications each app posted each day (epoch day): the calendar outlives the history.
    private fun days(db: SQLiteDatabase) =
        db.execSQL("CREATE TABLE days (day INTEGER NOT NULL, pkg TEXT NOT NULL, n INTEGER NOT NULL, PRIMARY KEY (day, pkg))")

    fun count(pkg: String, day: Long) {
        val db = writableDatabase
        db.execSQL("INSERT OR IGNORE INTO days VALUES (?, ?, 0)", arrayOf<Any>(day, pkg))
        db.execSQL("UPDATE days SET n = n + 1 WHERE day = ? AND pkg = ?", arrayOf<Any>(day, pkg))
    }

    /** Day -> all notifications of that day, for the days from [from] to [to]. */
    fun dayTotals(from: Long, to: Long): Map<Long, Int> =
        readableDatabase.rawQuery("SELECT day, SUM(n) FROM days WHERE day BETWEEN ? AND ? GROUP BY day",
            arrayOf(from.toString(), to.toString())).use { c ->
            buildMap { while (c.moveToNext()) put(c.getLong(0), c.getInt(1)) }
        }

    /** The apps of those days, the busiest first. */
    fun dayApps(from: Long, to: Long): List<Pair<String, Int>> =
        readableDatabase.rawQuery("SELECT pkg, SUM(n) FROM days WHERE day BETWEEN ? AND ? GROUP BY pkg ORDER BY 2 DESC",
            arrayOf(from.toString(), to.toString())).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0) to c.getInt(1)) }
        }

    /** What the history still keeps from [start] (inclusive) to [end] (exclusive), in ms. */
    fun between(start: Long, end: Long): List<Entry> =
        query("time >= ? AND time < ?", arrayOf(start.toString(), end.toString()), 200)

    /**
     * Saves a notification and returns its row. An app that reposts the same key (a chat with a new message)
     * updates the row still in the inbox instead of adding one; an identical repost changes nothing.
     */
    fun save(pkg: String, key: String, title: String, body: String, time: Long, read: Boolean): Long {
        val db = writableDatabase
        db.rawQuery("SELECT id, title, body FROM items WHERE nkey = ? AND cleared = 0 ORDER BY id DESC LIMIT 1",
            arrayOf(key)).use { c ->
            if (c.moveToFirst()) {
                val id = c.getLong(0)
                if (c.getString(1) != title || c.getString(2) != body) {
                    db.update("items", ContentValues().apply {
                        put("title", title); put("body", body); put("time", time); put("read", read)
                    }, "id = ?", arrayOf(id.toString()))
                }
                return id
            }
        }
        return db.insert("items", null, ContentValues().apply {
            put("pkg", pkg); put("nkey", key); put("title", title); put("body", body); put("time", time)
            put("read", read)
        })
    }

    /** When the user last cleared this key: older messages of a reposted chat are not brought back. */
    fun clearedUntil(key: String): Long =
        readableDatabase.rawQuery("SELECT MAX(time) FROM items WHERE nkey = ? AND cleared = 1", arrayOf(key)).use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0L
        }

    fun get(id: Long): Entry? = query("id = ?", arrayOf(id.toString())).firstOrNull()

    /** What is still in the inbox, newest first; with [pkg], of that app only. */
    fun open(pkg: String? = null): List<Entry> =
        if (pkg == null) query("cleared = 0", null) else query("cleared = 0 AND pkg = ?", arrayOf(pkg))

    /** Everything kept, cleared or not, newest first. */
    fun history(limit: Int = 2000): List<Entry> = query(null, null, limit)

    fun markRead(pkg: String) = set("read", "cleared = 0 AND pkg = ?", arrayOf(pkg))

    fun markAllRead() = set("read", "cleared = 0", null)

    /** A reminder brings a row back into the inbox as new. */
    fun restore(id: Long) {
        writableDatabase.update("items", ContentValues().apply { put("read", 0); put("cleared", 0) },
            "id = ?", arrayOf(id.toString()))
    }

    fun clear(id: Long) = set("cleared", "id = ?", arrayOf(id.toString()))

    fun clearApp(pkg: String) = set("cleared", "cleared = 0 AND pkg = ?", arrayOf(pkg))

    fun clearAll() = set("cleared", "cleared = 0", null)

    /** Forgets rows older than [before]; returns their ids so their pictures can go too. */
    fun pruneDays(before: Long) = writableDatabase.delete("days", "day < ?", arrayOf(before.toString()))

    fun prune(before: Long): List<Long> {
        val db = writableDatabase
        val ids = db.rawQuery("SELECT id FROM items WHERE time < ?", arrayOf(before.toString())).use { c ->
            buildList { while (c.moveToNext()) add(c.getLong(0)) }
        }
        if (ids.isNotEmpty()) db.delete("items", "time < ?", arrayOf(before.toString()))
        return ids
    }

    // Clearing also marks read: a cleared row never counts as new.
    private fun set(column: String, where: String, args: Array<String>?) {
        writableDatabase.update("items", ContentValues().apply {
            put(column, 1)
            if (column == "cleared") put("read", 1)
        }, where, args)
    }

    private fun query(where: String?, args: Array<String>?, limit: Int? = null): List<Entry> =
        readableDatabase.query("items", null, where, args, null, null, "time DESC, id DESC", limit?.toString())
            .use { c -> buildList { while (c.moveToNext()) add(c.entry()) } }

    private fun Cursor.entry() = Entry(
        id = getLong(getColumnIndexOrThrow("id")),
        pkg = getString(getColumnIndexOrThrow("pkg")),
        key = getString(getColumnIndexOrThrow("nkey")),
        title = getString(getColumnIndexOrThrow("title")),
        body = getString(getColumnIndexOrThrow("body")),
        time = getLong(getColumnIndexOrThrow("time")),
        read = getInt(getColumnIndexOrThrow("read")) != 0,
        cleared = getInt(getColumnIndexOrThrow("cleared")) != 0,
    )

    companion object {
        @Volatile private var one: Db? = null

        fun get(c: Context): Db = one ?: synchronized(this) {
            one ?: Db(c.applicationContext).also { one = it }
        }
    }
}
