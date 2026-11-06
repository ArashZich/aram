package ir.arashzich.aram

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder

/**
 * Makes Aram's summary a foreground service while Aram is on. Without one, MIUI's freezer stops the process for minutes
 * at a time, and everything that arrives meanwhile stays in the shade.
 */
class Keep : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(Inbox.SUMMARY, Inbox.idle(this))
        running = true
        Inbox.changed(this)  // the real summary replaces the idle line, or stops this service when Aram is off
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    companion object {
        @Volatile private var running = false

        fun start(c: Context) {
            if (!running) c.startForegroundService(Intent(c, Keep::class.java))
        }

        fun stop(c: Context) {
            if (running) c.stopService(Intent(c, Keep::class.java))
        }
    }
}
