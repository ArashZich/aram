package ir.arashzich.hush

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
    private val main = Handler(Looper.getMainLooper())
    private val waiting = HashMap<String, Waiting>() // notifications left in the shade while their sound plays
    private val awake by lazy {
        getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hush:sound")
            .apply { setReferenceCounted(false) }
    }

    private class Waiting(val since: Long, val take: Runnable)

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
    }

    override fun onListenerConnected() {
        live = this
        activeNotifications?.forEach { seen += it.key; store.remember(it.packageName) }
        Gate.sync(this, store)
        contentResolver.registerContentObserver(Gate.headsUpUri, false, headsUp)
        dropOldChannels()
        Inbox.prune(this)
        sweepNow()
        main.removeCallbacks(watchOpened)
        main.post(watchOpened)
    }

    // An app the user opened themselves clears its items. This only runs while the phone is awake: the timer stands
    // still in sleep and what happened meanwhile is read at the next look.
    private val watchOpened = object : Runnable {
        override fun run() {
            if (store.on && getSystemService(PowerManager::class.java).isInteractive) {
                Inbox.load({ Opened.clear(this@Listener) }) { if (it) Inbox.changed(this@Listener) }
            }
            main.postDelayed(this, LOOK_MS)
        }
    }

    // Hush 2.0–2.1 had tones of its own, one notification channel each; those channels go.
    private fun dropOldChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notificationChannels.filter { it.id.startsWith("tone_") }.forEach { nm.deleteNotificationChannel(it.id) }
    }

    // Android's floating comes back on now and then (the system or another app turns it on); Hush turns it off again.
    private val headsUp = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(self: Boolean) = Gate.sync(this@Listener, store)
    }

    override fun onListenerDisconnected() {
        main.removeCallbacks(watchOpened)
        runCatching { contentResolver.unregisterContentObserver(headsUp) }
        if (live === this) live = null
        wake(this)
    }

    override fun onDestroy() {
        main.removeCallbacks(watchOpened)
        waiting.values.forEach { main.removeCallbacks(it.take) }
        waiting.clear()
        if (awake.isHeld) awake.release()
        runCatching { contentResolver.unregisterContentObserver(headsUp) }
        if (live === this) live = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankings: RankingMap) = take(sbn, rankings, true)

    // Hush's own cancel must not make the app's next repost look new; the app's own cancel does.
    override fun onNotificationRemoved(sbn: StatusBarNotification, rankings: RankingMap, reason: Int) {
        if (reason != REASON_LISTENER_CANCEL) seen -= sbn.key
        waiting.remove(sbn.key)?.let { main.removeCallbacks(it.take) }
        if (waiting.isEmpty() && awake.isHeld) awake.release()
        Popup.hide(sbn.key)
    }

    /**
     * Takes the notification once its sound has played. Each repost plays a sound of its own, so it starts the wait
     * again. The phone stays awake for the wait: in sleep the timer stands still, and the notification stayed in the
     * shade until the screen came on.
     */
    private fun takeLater(key: String) {
        val now = SystemClock.elapsedRealtime()
        val since = waiting[key]?.since ?: now
        waiting[key]?.let { main.removeCallbacks(it.take) }
        val take = Runnable {
            waiting.remove(key)
            live?.cancelNotification(key)
            if (waiting.isEmpty() && awake.isHeld) awake.release()
        }
        waiting[key] = Waiting(since, take)
        val wait = Rules.soundWait(now - since)
        awake.acquire(wait + AWAKE_EXTRA)
        main.postDelayed(take, wait)
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
            if (posted && Rules.letsSound(incoming, level, quiet, store.digestOn)) takeLater(sbn.key)
            else cancelNotification(sbn.key)
            if (posted) Inbox.changed(this)
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

        /**
         * Connects the listener again when it was dropped: MIUI and others stop Hush in the background and do not
         * bring it back, and then every notification stays in the shade. [requestRebind] alone does nothing then;
         * switching the component off and on makes Android bind it anew.
         */
        fun wake(c: Context, done: () -> Unit = {}) {
            // A process that has just started is often being bound right now; binding it a second time on top of
            // that leaves two connections, so it waits first.
            Handler(Looper.getMainLooper()).postDelayed({
                if (live == null && Gate.listening(c)) {
                    val me = ComponentName(c, Listener::class.java)
                    val pm = c.packageManager
                    pm.setComponentEnabledSetting(me, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
                    pm.setComponentEnabledSetting(me, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
                    requestRebind(me)
                }
                done()
            }, SETTLE)
        }

        private const val SETTLE = 10_000L
        private const val AWAKE_EXTRA = 1_000L
        private const val LOOK_MS = 3_000L  // how often the opened apps are read while the screen is on
    }
}
