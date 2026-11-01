package ir.arashzich.hush

import android.Manifest
import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.io.File

/**
 * The inbox as the app sees it: Hush's one summary notification in the shade, the tap actions of the
 * notifications it took out (kept in memory only, Android cannot store them), and screens waiting for changes.
 */
object Inbox {
    private const val CHANNEL = "inbox"
    private const val SUMMARY = 1
    private const val DAY = 24 * 60 * 60 * 1000L

    private val main = Handler(Looper.getMainLooper())
    private val actions = HashMap<String, PendingIntent>()  // notification key -> its tap action
    private val replies = HashMap<String, Notification.Action>()  // notification key -> its reply action
    private val watchers = LinkedHashSet<() -> Unit>()

    fun keep(key: String, action: PendingIntent?, reply: Notification.Action?) {
        if (action != null) actions[key] = action
        if (reply != null) replies[key] = reply else replies -= key
    }

    /** The action that takes a typed answer, as a messenger offers it in its notification. */
    fun replyAction(n: Notification): Notification.Action? {
        val typed = n.actions.orEmpty().filter { a -> a.remoteInputs.orEmpty().any { it.allowFreeFormInput } }
        return typed.firstOrNull { Build.VERSION.SDK_INT >= 28 && it.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY }
            ?: typed.firstOrNull()
    }

    /** Only while Hush keeps running: after a restart the app's reply action is gone. */
    fun canReply(key: String) = key in replies

    /** Answers through the app's own reply action, as the shade's reply field does. */
    fun reply(c: Context, key: String, text: String): Boolean {
        val action = replies[key] ?: return false
        val inputs = action.remoteInputs.orEmpty().filter { it.allowFreeFormInput }.toTypedArray()
        val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } }
        val intent = Intent()
        RemoteInput.addResultsToIntent(inputs, intent, results)
        if (Build.VERSION.SDK_INT >= 28) RemoteInput.setResultsSource(intent, RemoteInput.SOURCE_FREE_FORM_INPUT)
        return runCatching { action.actionIntent.send(c, 0, intent) }.isSuccess
    }

    fun watch(f: () -> Unit) { watchers += f }

    fun unwatch(f: () -> Unit) { watchers -= f }

    /** After every change: the summary follows the inbox and open screens redraw. */
    fun changed(c: Context, tone: String? = null) {
        postSummary(c.applicationContext, tone)
        main.post { watchers.toList().forEach { it() } }
    }

    /** Opens the notification as its app meant it, or the app when that tap action is gone (after a restart). */
    fun open(c: Context, e: Entry) {
        val sent = actions[e.key]?.let {
            runCatching { it.send(c, 0, null, null, null, null, startOptions()) }.isSuccess
        }
        if (sent != true) c.packageManager.getLaunchIntentForPackage(e.pkg)?.let {
            c.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        Db.get(c).clear(e.id)
        changed(c)
    }

    // Since Android 14 a PendingIntent sent from the background opens its activity only if the sender opts in.
    fun startOptions(): Bundle? {
        if (Build.VERSION.SDK_INT < 34) return null
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 36) ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
        else ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
        return ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(mode).toBundle()
    }

    fun canPost(c: Context) = Build.VERSION.SDK_INT < 33 ||
        c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /**
     * "12 new notifications · Telegram 8 · Gmail 3": one quiet line instead of a shade full of cards.
     * With a [tone] this one update sounds: it is posted on that tone's channel.
     */
    fun postSummary(c: Context, tone: String? = null) {
        val nm = c.getSystemService(NotificationManager::class.java)
        val store = Store(c)
        // At set hours the shade shows only what arrived until the last of those times; the app's inbox stays live.
        val unread = Db.get(c).open().filter { !it.read && (!store.digestOn || it.time <= store.digestUntil) }
        if (!store.on || unread.isEmpty() || !canPost(c)) {
            nm.cancel(SUMMARY)
            return
        }
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, c.getString(R.string.channel), NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) }
            )
        }
        val groups = Rules.groups(unread)
        val title = c.resources.getQuantityString(R.plurals.summary_title, unread.size, unread.size)
        val style = Notification.InboxStyle().setBigContentTitle(title)
        groups.take(5).forEach { g -> style.addLine("${label(c, g.pkg)}: ${preview(g.latest)}") }
        val tap = PendingIntent.getActivity(c, 0,
            Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(c, tone?.let { Sounds.channel(c, it) } ?: CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setColor(c.getColor(R.color.accent))
            .setContentTitle(title)
            .setContentText(Rules.summaryLine(groups.map { label(c, it.pkg) to it.unread }))
            .setStyle(style)
            .setContentIntent(tap)
            .setOnlyAlertOnce(tone == null)
            .setNumber(unread.size)
            .build()
        nm.notify(SUMMARY, n)
    }

    fun preview(e: Entry): String = listOf(e.title, e.body.substringAfterLast('\n')).filter { it.isNotBlank() }
        .joinToString(": ")

    /** Old history goes, with its pictures. */
    fun prune(c: Context) {
        Db.get(c).prune(System.currentTimeMillis() - Store(c).historyDays * DAY).forEach { picture(c, it).delete() }
        Db.get(c).pruneDays(java.time.LocalDate.now().toEpochDay() - 400)
    }

    // ---- pictures: the sender's photo (a chat avatar) of each row, small, in the app's files

    private fun picture(c: Context, id: Long) = File(c.filesDir, "pictures/$id.png")

    fun savePicture(c: Context, id: Long, d: Drawable?) {
        val f = picture(c, id)
        if (d == null) { f.delete(); return }
        runCatching {
            val size = 128
            val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, size, size)
            d.draw(Canvas(b))
            f.parentFile?.mkdirs()
            f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    fun loadPicture(c: Context, id: Long): Bitmap? {
        val f = picture(c, id)
        return if (f.exists()) runCatching { BitmapFactory.decodeFile(f.path) }.getOrNull() else null
    }

    // ---- apps

    private val labels = HashMap<String, String>()

    fun label(c: Context, pkg: String): String = labels.getOrPut(pkg) {
        runCatching { c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString() }
            .getOrDefault(pkg)
    }

    fun icon(c: Context, pkg: String): Drawable? = runCatching { c.packageManager.getApplicationIcon(pkg) }.getOrNull()
}
