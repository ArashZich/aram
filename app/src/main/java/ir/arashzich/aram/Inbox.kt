package ir.arashzich.aram

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
import android.util.LruCache
import android.widget.RemoteViews
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * The inbox as the app sees it: Aram's one summary notification in the shade, the tap actions of the
 * notifications it took out (kept in memory only, Android cannot store them), and screens waiting for changes.
 */
object Inbox {
    private const val CHANNEL = "inbox"
    private const val SUMMARY = 1
    private const val DAY = 24 * 60 * 60 * 1000L

    private val main = Handler(Looper.getMainLooper())
    private const val SUMMARY_APPS = 4
    private const val ICON_DP = 32

    private val io = Executors.newSingleThreadExecutor()  // database reads and the summary, in order, off the main thread
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

    /** Only while Aram keeps running: after a restart the app's reply action is gone. */
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
    fun changed(c: Context) {
        val app = c.applicationContext
        io.execute { runCatching { postSummary(app) } }
        main.post { watchers.toList().forEach { it() } }
    }

    /** Reads off the main thread, then hands the result to the main thread. */
    fun <T> load(read: () -> T, done: (T) -> Unit) {
        io.execute { runCatching(read).onSuccess { main.post { done(it) } } }
    }

    /** Opens the notification as its app meant it, or the app when that tap action is gone (after a restart). */
    fun open(c: Context, e: Entry) {
        Opened.byAram(e.pkg)
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
     */
    fun postSummary(c: Context) {
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
        val tap = PendingIntent.getActivity(c, 0,
            Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setColor(c.getColor(R.color.accent))
            .setContentTitle(title)
            .setContentText(Rules.summaryLine(groups.map { label(c, it.pkg) to it.unread }) {
                String.format(c.resources.configuration.locales[0], "%d", it)
            })
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setCustomBigContentView(appRows(c, groups, tap))
            .setContentIntent(tap)
            .setOnlyAlertOnce(true)
            .setNumber(unread.size)
            .build()
        nm.notify(SUMMARY, n)
    }

    // The expanded summary, kept short: the latest apps, each opening itself (or Aram, for an app with no screen).
    private fun appRows(c: Context, groups: List<Group>, aram: PendingIntent): RemoteViews {
        val list = RemoteViews(c.packageName, R.layout.notif_apps)
        // The shade inflates these with the phone's language, which may not be Aram's.
        list.setInt(R.id.apps, "setLayoutDirection", c.resources.configuration.layoutDirection)
        groups.take(SUMMARY_APPS).forEach { g ->
            val row = RemoteViews(c.packageName, R.layout.notif_app)
            row.setTextViewText(R.id.app_name, label(c, g.pkg))
            row.setTextViewText(R.id.app_new, c.resources.getQuantityString(R.plurals.summary_title, g.unread, g.unread))
            icon(c, g.pkg)?.let { row.setImageViewBitmap(R.id.app_icon, bitmap(c, it)) }
            val open = c.packageManager.getLaunchIntentForPackage(g.pkg)?.let {
                PendingIntent.getActivity(c, g.pkg.hashCode(), it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            }
            row.setOnClickPendingIntent(R.id.app_row, open ?: aram)
            list.addView(R.id.apps, row)
        }
        if (groups.size > SUMMARY_APPS) {
            val more = RemoteViews(c.packageName, R.layout.notif_more)
            more.setTextViewText(R.id.app_more, c.getString(R.string.summary_all))
            more.setOnClickPendingIntent(R.id.app_more, aram)
            list.addView(R.id.apps, more)
        }
        return list
    }

    private fun bitmap(c: Context, d: Drawable): Bitmap {
        val size = (ICON_DP * c.resources.displayMetrics.density).toInt()
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, size, size)
        d.draw(Canvas(b))
        return b
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

    // Decoded pictures, so scrolling a list does not read files; NONE marks a row without one.
    private val NONE = Bitmap.createBitmap(1, 1, Bitmap.Config.ALPHA_8)
    private val pictures = object : LruCache<Long, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: Long, value: Bitmap) = value.byteCount
    }

    /** After a backup replaced the rows, a picture's file name may point at another row. */
    fun forgetPictures(c: Context) {
        File(c.filesDir, "pictures").deleteRecursively()
        pictures.evictAll()
    }

    fun savePicture(c: Context, id: Long, d: Drawable?) {
        val f = picture(c, id)
        pictures.remove(id)
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
        pictures.get(id)?.let { return if (it === NONE) null else it }
        val f = picture(c, id)
        val b = if (f.exists()) runCatching { BitmapFactory.decodeFile(f.path) }.getOrNull() else null
        pictures.put(id, b ?: NONE)
        return b
    }

    // ---- apps

    private val labels = ConcurrentHashMap<String, String>()  // read from the main thread and the io one

    fun label(c: Context, pkg: String): String = labels.getOrPut(pkg) {
        runCatching { c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString() }
            .getOrDefault(pkg)
    }

    fun icon(c: Context, pkg: String): Drawable? = runCatching { c.packageManager.getApplicationIcon(pkg) }.getOrNull()
}
