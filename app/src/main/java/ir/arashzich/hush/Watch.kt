package ir.arashzich.hush

import android.app.Application
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

/**
 * Keeps the listener connected and Android's floating off: when Hush's process starts and every 15 minutes, a
 * dropped listener is connected again. MIUI once kept the process alive for over an hour with the listener gone,
 * and everything stayed in the shade.
 */
class Watch : JobService() {
    override fun onStartJob(p: JobParameters): Boolean {
        Gate.sync(this, Store(this))
        Listener.wake(this) { jobFinished(p, false) }
        return true
    }

    override fun onStopJob(p: JobParameters) = true

    companion object {
        private const val ID = 1

        fun start(c: Context) {
            val js = c.getSystemService(JobScheduler::class.java)
            if (js.getPendingJob(ID) == null) {
                js.schedule(JobInfo.Builder(ID, ComponentName(c, Watch::class.java))
                    .setPeriodic(15 * 60_000L).setPersisted(true).build())
            }
            Listener.wake(c)
        }
    }
}

/** Every start of Hush's process, whatever started it, checks the listener. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Watch.start(this)
    }
}
