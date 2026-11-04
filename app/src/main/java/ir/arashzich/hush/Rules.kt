package ir.arashzich.hush

import java.time.LocalDateTime

/**
 * What the user chose for one app. Everything but [SHADE] goes into Hush's inbox and leaves the shade:
 * [IMPORTANT] also floats, [SILENT] arrives already read. [SHADE] is left to Android.
 */
enum class Level { IMPORTANT, NORMAL, SILENT, SHADE }

/** A person or word that always floats; [pkg] null means in any app. */
data class Word(val text: String, val pkg: String?) {
    fun matches(pkg: String, title: String, body: String) =
        text.isNotBlank() && (this.pkg == null || this.pkg == pkg) &&
            (title.contains(text, ignoreCase = true) || body.contains(text, ignoreCase = true))
}

/** The facts about one posted notification that the decision needs (no Android types, so it is unit-testable). */
class Incoming(
    val pkg: String,
    val title: String,
    val body: String,
    val ongoing: Boolean = false,      // media, navigation, foreground services
    val summary: Boolean = false,      // group summary: its children carry the content
    val urgent: Boolean = false,       // calls and alarms
    val fullScreen: Boolean = false,   // the system shows these on its own
    val lowChannel: Boolean = false,   // the app's channel is below "default", it would never have alerted
    val dnd: Boolean = false,          // Do Not Disturb holds it back
    val repeat: Boolean = false,       // an update of a notification that asked to alert only once
)

enum class Action { FLOAT, KEEP, REMOVE }

/** The "remind me later" choices. */
enum class Later { HOUR, EVENING, MORNING }

/** One message of a chat notification; [sender] null is the user. */
class Msg(val time: Long, val sender: String?, val text: String)

/** One notification in the inbox or history. */
data class Entry(
    val id: Long,
    val pkg: String,
    val key: String,
    val title: String,
    val body: String,
    val time: Long,
    val read: Boolean,
    val cleared: Boolean = false,
)

/** One app's part of the inbox. */
class Group(val pkg: String, val count: Int, val unread: Int, val latest: Entry)

object Rules {
    /**
     * [sinceLast] is the time since this app last floated; [gap] the smallest allowed time between two.
     * A matching [Word] beats the app's level: a silenced group app still lets that one person through.
     */
    fun decide(n: Incoming, level: Level, words: List<Word>, quiet: Boolean, sinceLast: Long, gap: Long): Action {
        if (n.summary) return Action.KEEP
        val wordHit = words.any { it.matches(n.pkg, n.title, n.body) }
        if (level == Level.SILENT && !wordHit && !n.ongoing && !n.urgent) return Action.REMOVE
        if (n.ongoing || n.fullScreen || n.dnd || n.lowChannel || n.repeat) return Action.KEEP
        if (n.urgent) return Action.FLOAT
        if (quiet) return Action.KEEP
        if (level != Level.IMPORTANT && !wordHit) return Action.KEEP
        return if (sinceLast < gap) Action.KEEP else Action.FLOAT
    }

    /** Whether a notification moves into the inbox (a group summary is only cleared, its children carry it). */
    fun collect(n: Incoming, level: Level) = level != Level.SHADE && !n.ongoing && !n.urgent && !n.fullScreen

    /**
     * Whether Hush leaves a collected notification in the shade for a moment, so the app's own sound plays to the
     * end (taking it at once stops the sound). Not in quiet hours, at set summary times, for Silent apps or what the
     * app itself would not sound.
     */
    fun letsSound(n: Incoming, level: Level, quiet: Boolean, digest: Boolean) =
        collect(n, level) && level != Level.SILENT && !quiet && !digest && !n.dnd && !n.lowChannel && !n.repeat

    /**
     * The text of a chat notification: only messages newer than [since] (what the user already cleared stays
     * cleared when the app reposts the whole conversation), with the sender's name in a group.
     */
    fun messages(list: List<Msg>, since: Long, group: Boolean): String =
        list.filter { it.time > since && it.text.isNotBlank() }.joinToString("\n") {
            if (group && !it.sender.isNullOrBlank()) "${it.sender}: ${it.text}" else it.text
        }

    /** The inbox by app, the most recent app first. */
    fun groups(entries: List<Entry>): List<Group> =
        entries.groupBy { it.pkg }
            .map { (pkg, l) -> Group(pkg, l.size, l.count { !it.read }, l.maxBy { it.time }) }
            .sortedByDescending { it.latest.time }

    /** "Telegram 8 · Gmail 3 · +2": the busiest [max] apps of the summary notification. */
    fun summaryLine(apps: List<Pair<String, Int>>, max: Int = 3): String {
        val busy = apps.filter { it.second > 0 }.sortedByDescending { it.second }
        val line = busy.take(max).joinToString(" · ") { "${it.first} ${it.second}" }
        return if (busy.size > max) "$line · +${busy.size - max}" else line
    }

    private val codeWords = Regex(
        "code|otp|passcode|password|pin|verif|کد|رمز|گذرواژه|تایید|تأیید",
        RegexOption.IGNORE_CASE,
    )
    private val codeDigits = Regex("(?<![\\d.,/:])(\\d{3}[- ]\\d{3}|\\d{4,8})(?![\\d.,/:]\\d|\\d)")

    /**
     * A verification code in [text]: 4–8 digits (or "123 456") in a text that mentions a code, the one closest
     * to that word. Persian and Arabic digits count too; the code comes back in Latin digits.
     */
    fun otp(text: String): String? {
        val t = text.map { ch ->
            when (ch) {
                in '۰'..'۹' -> '0' + (ch - '۰')
                in '٠'..'٩' -> '0' + (ch - '٠')
                else -> ch
            }
        }.joinToString("")
        val words = codeWords.findAll(t).map { it.range.first }.toList()
        if (words.isEmpty()) return null
        return codeDigits.findAll(t)
            .minByOrNull { m -> words.minOf { w -> kotlin.math.abs(m.range.first - w) } }
            ?.value?.filter { it.isDigit() }
    }

    /** When a reminder fires; null when the choice makes no sense now ("this evening" after 18:00). */
    fun later(now: LocalDateTime, which: Later): LocalDateTime? = when (which) {
        Later.HOUR -> now.plusHours(1)
        Later.EVENING -> if (now.hour >= 18) null else now.toLocalDate().atTime(19, 0)
        Later.MORNING -> (if (now.hour < 5) now.toLocalDate() else now.toLocalDate().plusDays(1)).atTime(9, 0)
    }

    /** How strong a calendar day is coloured: 0 for none, then 1–4 against the month's busiest day [max]. */
    fun shade(n: Int, max: Int): Int = if (n <= 0 || max <= 0) 0 else minOf(4, (4 * n + max - 1) / max)

    /** A month as weeks: [lead] empty cells before day 1, then 1..[days], filled up to whole weeks. */
    fun grid(lead: Int, days: Int): List<Int?> {
        val cells = List<Int?>(lead) { null } + (1..days)
        return cells + List((7 - cells.size % 7) % 7) { null }
    }

    /** Apps worth silencing: at least [min] notifications in the week and not silent already. */
    fun noisy(apps: List<Pair<String, Int>>, level: (String) -> Level, min: Int = 20): Set<String> =
        apps.filter { it.second >= min && level(it.first).let { l -> l == Level.NORMAL || l == Level.IMPORTANT } }
            .map { it.first }.toSet()

    /** Minutes of the day; a range like 23:00–07:00 wraps past midnight. Equal ends mean no quiet time. */
    /** The next of the summary [times] (minutes of the day) after [now]; tomorrow's first when today's are past. */
    /** [every] > 0: the next multiple of that many minutes after [now], counted from midnight; else the next of [times]. */
    fun nextDigest(now: LocalDateTime, times: List<Int>, every: Int = 0): LocalDateTime? {
        if (every > 0) {
            val minute = now.hour * 60 + now.minute
            return now.toLocalDate().atStartOfDay().plusMinutes(((minute / every + 1) * every).toLong())
        }
        if (times.isEmpty()) return null
        val today = now.toLocalDate().atStartOfDay()
        return times.sorted().map { today.plusMinutes(it.toLong()) }.firstOrNull { it.isAfter(now) }
            ?: today.plusDays(1).plusMinutes(times.min().toLong())
    }

    fun inQuiet(minute: Int, from: Int, to: Int): Boolean = when {
        from == to -> false
        from < to -> minute in from until to
        else -> minute >= from || minute < to
    }
}
