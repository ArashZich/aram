package ir.arashzich.aram

import android.app.Activity
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * "Remind me later": the notification leaves the inbox now and comes back, new and with a sound, at the chosen
 * time. The alarm is inexact (no special permission) and is set again after a restart.
 */
class Reminder : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Listener.wake(c)
                Store(c).reminders.forEach { (id, at) -> arm(c, id, at) }
            }
            FIRE -> fire(c, i.getLongExtra(ID, -1))
        }
    }

    companion object {
        private const val CHANNEL = "remind"
        private const val FIRE = "ir.arashzich.aram.REMIND"
        private const val ID = "id"
        private const val OPEN = "open_entry"
        private const val BASE = 1000  // notification ids of reminders; 1 is the summary

        fun set(c: Context, e: Entry, at: Long) {
            Store(c).setReminder(e.id, at)
            arm(c, e.id, at)
            Db.get(c).clear(e.id)
            Inbox.changed(c)
        }

        /** MainActivity got the tap of a reminder: open that notification. */
        fun opened(a: Activity, i: Intent?) {
            val id = i?.getLongExtra(OPEN, -1) ?: -1
            if (id < 0) return
            i?.removeExtra(OPEN)
            a.getSystemService(NotificationManager::class.java).cancel(BASE + id.toInt())
            Db.get(a).get(id)?.let { Inbox.open(a, it) }
        }

        private fun arm(c: Context, id: Long, at: Long) {
            val fire = PendingIntent.getBroadcast(c, id.toInt(),
                Intent(c, Reminder::class.java).setAction(FIRE).putExtra(ID, id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            c.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, fire)
        }

        private fun fire(c: Context, id: Long) {
            Store(c).setReminder(id, null)
            val e = Db.get(c).get(id) ?: return
            Db.get(c).restore(id)
            Inbox.changed(c)
            if (!Inbox.canPost(c)) return
            val nm = c.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, c.getString(R.string.channel_remind), NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
            val tap = PendingIntent.getActivity(c, id.toInt(),
                Intent(c, MainActivity::class.java).putExtra(OPEN, id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            nm.notify(BASE + id.toInt(), Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_tile)
                .setColor(c.getColor(R.color.accent))
                .setSubText(Inbox.label(c, e.pkg))
                .setContentTitle(e.title.ifBlank { Inbox.label(c, e.pkg) })
                .setContentText(e.body)
                .setStyle(Notification.BigTextStyle().bigText(e.body))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build())
        }
    }
}
