package ir.arashzich.aram

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Aram's data as one JSON file, to move it to another install: settings, app levels, words, inbox, history and the
 * calendar's counts. Android permissions, pictures and tap actions cannot move; "on" and the phone's saved heads-up
 * value belong to this install.
 */
object Backup {
    // Kept from when the app was called Hush, so a backup made there still opens here.
    private const val KIND = "hush-backup"
    private const val VERSION = 1

    class Data(val settings: Map<String, Any>, val items: List<Entry>, val days: List<Triple<Long, String, Int>>, val language: Int)

    fun write(c: Context): String {
        val db = Db.get(c)
        val settings = JSONObject()
        Store(c).settings().forEach { (key, value) ->
            val (type, json) = when (value) {
                is Boolean -> "boolean" to value
                is Int -> "int" to value
                is Long -> "long" to value
                is Float -> "float" to value.toDouble()
                is String -> "string" to value
                is Set<*> -> "strings" to JSONArray(value)
                else -> return@forEach
            }
            settings.put(key, JSONObject().put("type", type).put("value", json))
        }
        return JSONObject()
            .put("kind", KIND)
            .put("version", VERSION)
            .put("exported", System.currentTimeMillis())
            .put("language", Lang.current(c))
            .put("settings", settings)
            .put("items", JSONArray(db.all().map {
                JSONObject().put("id", it.id).put("pkg", it.pkg).put("key", it.key).put("title", it.title)
                    .put("body", it.body).put("time", it.time).put("read", it.read).put("cleared", it.cleared)
            }))
            .put("days", JSONArray(db.allDays().map {
                JSONObject().put("day", it.first).put("pkg", it.second).put("n", it.third)
            }))
            .toString()
    }

    /** Reads the whole file and throws if any of it is wrong, so a bad file changes nothing. */
    fun read(text: String): Data {
        val o = JSONObject(text)
        require(o.getString("kind") == KIND && o.getInt("version") == VERSION) { "not a backup this version can read" }
        val json = o.getJSONObject("settings")
        val settings = json.keys().asSequence().associateWith { key ->
            val s = json.getJSONObject(key)
            when (val type = s.getString("type")) {
                "boolean" -> s.getBoolean("value")
                "int" -> s.getInt("value")
                "long" -> s.getLong("value")
                "float" -> s.getDouble("value").toFloat()
                "string" -> s.getString("value")
                "strings" -> s.getJSONArray("value").let { a -> (0 until a.length()).map(a::getString).toSet() }
                else -> throw IllegalArgumentException("unknown type $type")
            }
        }
        val items = o.getJSONArray("items").objects().map {
            Entry(it.getLong("id"), it.getString("pkg"), it.getString("key"), it.getString("title"), it.getString("body"),
                it.getLong("time"), it.getBoolean("read"), it.getBoolean("cleared"))
        }
        val days = o.getJSONArray("days").objects().map { Triple(it.getLong("day"), it.getString("pkg"), it.getInt("n")) }
        return Data(settings, items, days, o.getInt("language"))
    }

    /** Replaces what this install has. The rows go first, in one transaction: if they fail, nothing has changed. */
    fun restore(c: Context, data: Data) {
        Db.get(c).replace(data.items, data.days)
        Store(c).restore(data.settings)
        Inbox.forgetPictures(c)
        Inbox.prune(c)
        Digest.arm(c)
        Inbox.changed(c)
    }

    private fun JSONArray.objects() = (0 until length()).map(::getJSONObject)
}
