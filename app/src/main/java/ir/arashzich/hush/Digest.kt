package ir.arashzich.hush

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Summary at set hours: the shade's summary changes only at the chosen times, silently or with one soft sound.
 * One inexact alarm for the next time; it is set again after each time and after a restart.
 */
class Digest : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val store = Store(c)
        if (i.action == FIRE && store.digestOn) {
            store.digestUntil = System.currentTimeMillis()
            Inbox.changed(c, if (store.digestSound) store.defaultSound ?: SOFT else null)
        }
        arm(c)
    }

    companion object {
        private const val FIRE = "ir.arashzich.hush.DIGEST"
        private const val SOFT = "drop"

        /** Turning it on shows what is already waiting; off, the summary is live again. */
        fun set(c: Context, on: Boolean) {
            val store = Store(c)
            store.digestOn = on
            store.digestUntil = System.currentTimeMillis()
            arm(c)
            Inbox.changed(c)
        }

        fun arm(c: Context) {
            val store = Store(c)
            val fire = PendingIntent.getBroadcast(c, 0, Intent(c, Digest::class.java).setAction(FIRE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val am = c.getSystemService(AlarmManager::class.java)
            val next = if (store.digestOn) Rules.nextDigest(LocalDateTime.now(), store.digestTimes) else null
            if (next == null) am.cancel(fire)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), fire)
        }
    }
}
