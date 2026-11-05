package ir.arashzich.aram

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import java.util.concurrent.ConcurrentHashMap

/**
 * Marks the items of an app the user opened directly as read. Aram took the notification out of the shade, so the
 * app's own "read" never reaches it; Android's usage events are the only sign. They say which app, not whether the
 * message was read, so the items stay in the inbox for the user to clear. A tap on one item in Aram touches only that item.
 */
object Opened {
    private const val OVERLAP = 2_000L     // an event can arrive a moment after its time
    private const val FIRST_LOOK = 10_000L

    private val byAram = ConcurrentHashMap<String, Long>()
    private var since = 0L

    fun allowed(c: Context): Boolean {
        @Suppress("DEPRECATION")
        val mode = c.getSystemService(AppOpsManager::class.java)
            .checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Aram is about to open [pkg]: that is not the user going to the app directly. */
    fun byAram(pkg: String) {
        byAram[pkg] = System.currentTimeMillis()
    }

    /** Reads the apps opened since the last look and marks their items read; true when something changed. */
    fun markRead(c: Context): Boolean {
        if (!allowed(c)) return false
        val now = System.currentTimeMillis()
        val from = if (since == 0L) now - FIRST_LOOK else since - OVERLAP
        since = now
        val opened = HashMap<String, Long>()
        val events = c.getSystemService(UsageStatsManager::class.java).queryEvents(from, now)
        val e = UsageEvents.Event()
        while (events.getNextEvent(e)) {
            @Suppress("DEPRECATION")
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND && e.packageName != c.packageName &&
                Rules.userOpened(e.timeStamp, byAram[e.packageName])
            ) opened[e.packageName] = e.timeStamp
        }
        val db = Db.get(c)
        return opened.map { (pkg, time) -> db.readSeen(pkg, time) }.sum() > 0
    }
}
