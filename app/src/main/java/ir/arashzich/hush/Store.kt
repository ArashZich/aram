package ir.arashzich.hush

import android.content.Context
import java.time.LocalDate

/** Everything Hush remembers, in one SharedPreferences file. */
class Store(c: Context) {
    private val p = c.getSharedPreferences("hush", Context.MODE_PRIVATE)

    var on: Boolean
        get() = p.getBoolean("on", false)
        set(v) = p.edit().putBoolean("on", v).apply()

    /** The phone's own heads-up value from before Hush switched it off; null while Hush is off. */
    var savedHeadsUp: String?
        get() = p.getString("saved_heads_up", null)
        set(v) = p.edit().putString("saved_heads_up", v).apply()

    /** The level of every app the user has not set one for. */
    var defaultLevel: Level
        get() = runCatching { Level.valueOf(p.getString("default_level", null)!!) }.getOrDefault(Level.NORMAL)
        set(v) = p.edit().putString("default_level", v.name).apply()

    fun level(pkg: String): Level =
        runCatching { Level.valueOf(p.getString("lv:$pkg", null)!!) }.getOrDefault(defaultLevel)

    fun isSet(pkg: String) = p.contains("lv:$pkg")

    fun setLevel(pkg: String, l: Level) = p.edit().putString("lv:$pkg", l.name).apply()

    // A word is stored as "pkg<TAB>text"; an empty pkg means any app.
    var words: List<Word>
        get() = p.getStringSet("words", emptySet())!!.map {
            val (pkg, text) = it.split('\t', limit = 2)
            Word(text, pkg.ifEmpty { null })
        }.sortedBy { it.text.lowercase() }
        set(v) = p.edit().putStringSet("words", v.map { "${it.pkg.orEmpty()}\t${it.text}" }.toSet()).apply()

    var quietOn: Boolean
        get() = p.getBoolean("quiet_on", false)
        set(v) = p.edit().putBoolean("quiet_on", v).apply()

    /** Minutes of the day. */
    var quietFrom: Int
        get() = p.getInt("quiet_from", 23 * 60)
        set(v) = p.edit().putInt("quiet_from", v).apply()

    var quietTo: Int
        get() = p.getInt("quiet_to", 7 * 60)
        set(v) = p.edit().putInt("quiet_to", v).apply()

    /** Smallest time between two floats of the same app. */
    var gapSeconds: Int
        get() = p.getInt("gap", 60)
        set(v) = p.edit().putInt("gap", v).apply()

    /** How long the card stays before it slides away. */
    var showSeconds: Int
        get() = p.getInt("show", 8)
        set(v) = p.edit().putInt("show", v).apply()

    /** How long the history keeps a notification. */
    var historyDays: Int
        get() = p.getInt("history_days", 30)
        set(v) = p.edit().putInt("history_days", v).apply()

    /** Summary at set hours; off, the shade's summary follows the inbox as it fills. */
    var digestOn: Boolean
        get() = p.getBoolean("digest_on", false)
        set(v) = p.edit().putBoolean("digest_on", v).apply()

    /** Minutes of the day, in order. */
    var digestTimes: List<Int>
        get() = p.getString("digest_times", "600,840,1140")!!.split(',').mapNotNull { it.toIntOrNull() }.sorted()
        set(v) = p.edit().putString("digest_times", v.sorted().joinToString(",")).apply()

    /** Minutes between summaries; 0 means at [digestTimes]. */
    var digestEvery: Int
        get() = p.getInt("digest_every", 0)
        set(v) = p.edit().putInt("digest_every", v).apply()

    /** The last summary time: the shade shows what arrived until then. */
    var digestUntil: Long
        get() = p.getLong("digest_until", 0)
        set(v) = p.edit().putLong("digest_until", v).apply()

    /** Reminders still to come: row id -> time. */
    val reminders: Map<Long, Long>
        get() = p.getStringSet("remind", emptySet())!!.associate {
            val (id, at) = it.split(':', limit = 2)
            id.toLong() to at.toLong()
        }

    fun setReminder(id: Long, at: Long?) {
        val all = reminders.toMutableMap()
        if (at == null) all.remove(id) else all[id] = at
        p.edit().putStringSet("remind", all.map { "${it.key}:${it.value}" }.toSet()).apply()
    }

    /** Apps that ever posted a notification. */
    val seen: Set<String> get() = p.getStringSet("seen", emptySet())!!

    fun remember(pkg: String) {
        if (pkg !in seen) p.edit().putStringSet("seen", seen + pkg).apply()
    }

    /** Counts one new notification of [pkg] for today. */
    fun bump(pkg: String) {
        rollDay()
        remember(pkg)
        p.edit().putInt("n:$pkg", p.getInt("n:$pkg", 0) + 1).apply()
    }

    fun today(pkg: String): Int {
        rollDay()
        return p.getInt("n:$pkg", 0)
    }

    private fun rollDay() {
        val day = LocalDate.now().toEpochDay()
        if (p.getLong("day", -1) == day) return
        val e = p.edit()
        p.all.keys.filter { it.startsWith("n:") }.forEach { e.remove(it) }
        e.putLong("day", day).apply()
    }
}
