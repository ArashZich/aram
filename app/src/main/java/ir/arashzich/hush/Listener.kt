package ir.arashzich.hush

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.time.LocalDate
import java.time.LocalTime

/**
 * Sees every notification: moves it into Hush's inbox and out of the shade, floats Hush's own card for what
 * matters, and leaves alone what the user keeps in the shade.
 */
class Listener : NotificationListenerService() {
    private lateinit var store: Store
    private val seen = HashSet<String>()             // keys already counted, to tell a new notification from an update
    private val lastFloat = HashMap<String, Long>()  // package -> elapsed time of its last card
    private val lastSound = HashMap<String, Long>()  // package -> elapsed time of its last tone

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
    }

    override fun onListenerConnected() {
        live = this
        activeNotifications?.forEach { seen += it.key; store.remember(it.packageName) }
        Gate.sync(this, store)
        Sounds.dropOldChannels(this)
        Inbox.prune(this)
        sweepNow()
    }

    override fun onListenerDisconnected() {
        if (live === this) live = null
    }

    override fun onDestroy() {
        if (live === this) live = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankings: RankingMap) = take(sbn, rankings, true)

    // Hush's own cancel must not make the app's next repost look new; the app's own cancel does.
    override fun onNotificationRemoved(sbn: StatusBarNotification, rankings: RankingMap, reason: Int) {
        if (reason != REASON_LISTENER_CANCEL) seen -= sbn.key
        Popup.hide(sbn.key)
    }

    /** What was already in the shade when Hush was switched on, or before the listener connected. */
    private fun sweepNow() {
        if (!store.on) return
        val rankings = currentRanking ?: return
        activeNotifications?.forEach { take(it, rankings, false) }
        Inbox.changed(this)
    }

    private fun take(sbn: StatusBarNotification, rankings: RankingMap, posted: Boolean) {
        val pkg = sbn.packageName
        if (pkg == packageName) return
        val n = sbn.notification
        val fresh = seen.add(sbn.key)
        val summary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0
        if (posted && fresh && !summary) store.bump(pkg)
        if (!store.on) return

        val r = Ranking().also { rankings.getRanking(sbn.key, it) }
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body = (n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: n.extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        val incoming = Incoming(
            pkg, title, body,
            ongoing = sbn.isOngoing || !sbn.isClearable,
            summary = summary,
            urgent = n.category == Notification.CATEGORY_CALL || n.category == Notification.CATEGORY_ALARM,
            fullScreen = n.fullScreenIntent != null,
            lowChannel = r.importance in 1 until NotificationManager.IMPORTANCE_DEFAULT,
            dnd = !r.matchesInterruptionFilter(),
            repeat = !fresh && n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0,
        )
        if (posted && fresh && !summary && !incoming.ongoing) Db.get(this).count(pkg, LocalDate.now().toEpochDay())
        val level = store.level(pkg)
        val now = SystemClock.elapsedRealtime()
        val quiet = store.quietOn && LocalTime.now().let {
            Rules.inQuiet(it.hour * 60 + it.minute, store.quietFrom, store.quietTo)
        }
        val since = now - (lastFloat[pkg] ?: Long.MIN_VALUE / 2)
        val action = Rules.decide(incoming, level, store.words, quiet, since, store.gapSeconds * 1000L)

        var entry: Entry? = null
        if (Rules.collect(incoming, level)) {
            if (!summary) entry = save(sbn, title, level)
            cancelNotification(sbn.key)
            if (posted) {
                val tone = if (store.digestOn) null else store.sound(pkg)  // at set hours it sounds only then
                val sounds = entry != null &&
                    Rules.plays(incoming, level, tone, quiet, now - (lastSound[pkg] ?: Long.MIN_VALUE / 2))
                if (sounds) {
                    lastSound[pkg] = now
                    Sounds.ring(this, tone!!)
                }
                Inbox.changed(this)
            }
        } else if (action == Action.REMOVE) {
            cancelNotification(sbn.key)
        }

        if (posted && action == Action.FLOAT && inUse()) {
            lastFloat[pkg] = now
            Popup.show(this, sbn.key, Inbox.label(this, pkg), title, body, icon(pkg, n), store.showSeconds) {
                if (entry != null) Inbox.open(this, entry) else open(sbn)
            }
        }
    }

    /** Into the inbox: a chat keeps its messages line by line, a list (a mail summary) its lines. */
    private fun save(sbn: StatusBarNotification, title: String, level: Level): Entry? {
        val n = sbn.notification
        val x = n.extras
        val db = Db.get(this)
        // Read by hand: MessagingStyle.Message.getMessagesFromBundleArray is public only since Android 11.
        @Suppress("DEPRECATION")
        val messages = x.getParcelableArray(Notification.EXTRA_MESSAGES).orEmpty().mapNotNull { it as? Bundle }.map {
            Msg(it.getLong("time"), it.getCharSequence("sender")?.toString(), it.getCharSequence("text")?.toString().orEmpty())
        }
        val lines = x.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        val body = when {
            messages.isNotEmpty() -> Rules.messages(messages, db.clearedUntil(sbn.key), x.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION))
            !lines.isNullOrEmpty() -> lines.joinToString("\n")
            else -> (x.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: x.getCharSequence(Notification.EXTRA_TEXT))
                ?.toString().orEmpty()
        }
        val heading = x.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString() ?: title
        if (heading.isBlank() && body.isBlank()) return null
        Inbox.keep(sbn.key, n.contentIntent, Inbox.replyAction(n))
        val id = db.save(sbn.packageName, sbn.key, heading.trim(), body.trim(), sbn.postTime, level == Level.SILENT)
        Inbox.savePicture(this, id, runCatching { n.getLargeIcon()?.loadDrawable(this) }.getOrNull())
        return db.get(id)
    }

    // Locked or dark: the lock screen already shows it, a card would only wait unseen.
    private fun inUse() = getSystemService(PowerManager::class.java).isInteractive &&
        !getSystemService(KeyguardManager::class.java).isKeyguardLocked

    // A notification Hush left in the shade: opened from the card as v1 did.
    private fun open(sbn: StatusBarNotification) {
        val n = sbn.notification
        val sent = n.contentIntent?.let {
            runCatching { it.send(this, 0, null, null, null, null, Inbox.startOptions()) }.isSuccess
        }
        if (sent != true) packageManager.getLaunchIntentForPackage(sbn.packageName)?.let {
            startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        if (n.flags and Notification.FLAG_AUTO_CANCEL != 0) cancelNotification(sbn.key)
    }

    // The sender's picture when there is one (a chat avatar), otherwise the app's icon.
    private fun icon(pkg: String, n: Notification): Drawable? =
        runCatching { n.getLargeIcon()?.loadDrawable(this) }.getOrNull() ?: Inbox.icon(this, pkg)

    companion object {
        private var live: Listener? = null

        /** Moves what is already in the shade into the inbox (Hush was just switched on). */
        fun sweep() = live?.sweepNow()
    }
}
