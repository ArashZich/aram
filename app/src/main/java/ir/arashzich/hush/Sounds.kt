package ir.arashzich.hush

import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * Hush's own notification tones. An app set to one of them sounds through Hush, at the notification volume,
 * and not in silent mode or Do Not Disturb.
 */
object Sounds {
    /** Tone id -> its name and sound file. */
    val tones = linkedMapOf(
        "drop" to (R.string.tone_drop to R.raw.tone_drop),
        "chime" to (R.string.tone_chime to R.raw.tone_chime),
        "glass" to (R.string.tone_glass to R.raw.tone_glass),
        "bell" to (R.string.tone_bell to R.raw.tone_bell),
        "breeze" to (R.string.tone_breeze to R.raw.tone_breeze),
        "pop" to (R.string.tone_pop to R.raw.tone_pop),
        "harp" to (R.string.tone_harp to R.raw.tone_harp),
        "marimba" to (R.string.tone_marimba to R.raw.tone_marimba),
        "kalimba" to (R.string.tone_kalimba to R.raw.tone_kalimba),
        "windchime" to (R.string.tone_windchime to R.raw.tone_windchime),
        "dingdong" to (R.string.tone_dingdong to R.raw.tone_dingdong),
        "lullaby" to (R.string.tone_lullaby to R.raw.tone_lullaby),
        "rise" to (R.string.tone_rise to R.raw.tone_rise),
        "bubble" to (R.string.tone_bubble to R.raw.tone_bubble),
        "bowl" to (R.string.tone_bowl to R.raw.tone_bowl),
        "tick" to (R.string.tone_tick to R.raw.tone_tick),
    )

    private var playing: Ringtone? = null  // a preview in the picker
    private var ringing: Ringtone? = null  // a tone for a notification

    fun name(c: Context, tone: String?): String =
        tones[tone]?.let { c.getString(it.first) } ?: c.getString(R.string.tone_none)

    // By name, not by number: resource numbers change from one build to the next.
    private fun uri(c: Context, tone: String) =
        Uri.parse("android.resource://${c.packageName}/raw/${c.resources.getResourceEntryName(tones.getValue(tone).second)}")

    /**
     * Plays [tone] now, as a notification sound: Hush plays it itself, since Android quiets an app that updates
     * its one notification often. Silent mode plays nothing, vibrate mode only buzzes, the volume is the
     * notification volume. (Do Not Disturb is checked by the caller, per notification.)
     */
    fun ring(c: Context, tone: String) {
        val audio = c.getSystemService(AudioManager::class.java)
        if (audio.ringerMode == AudioManager.RINGER_MODE_SILENT) return
        c.getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 60, 80, 60), -1))
        if (audio.ringerMode == AudioManager.RINGER_MODE_VIBRATE) return
        ringing?.stop()
        ringing = RingtoneManager.getRingtone(c, uri(c, tone))?.apply {
            audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            play()
        }
    }

    /** Hush 2.0–2.1 sounded through one notification channel per tone; those channels go. */
    fun dropOldChannels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.notificationChannels.filter { it.id.startsWith("tone_") }.forEach { nm.deleteNotificationChannel(it.id) }
    }

    /** A list of the tones; choosing one plays it, OK keeps it. */
    fun pick(a: Activity, current: String?, done: (String?) -> Unit) {
        val ids = listOf<String?>(null) + tones.keys
        var chosen = ids.indexOf(current).coerceAtLeast(0)
        AlertDialog.Builder(a)
            .setTitle(R.string.sound)
            .setSingleChoiceItems(ids.map { name(a, it) }.toTypedArray(), chosen) { _, i ->
                chosen = i
                preview(a, ids[i])
            }
            .setPositiveButton(android.R.string.ok) { _, _ -> done(ids[chosen]) }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { playing?.stop() }
            .show()
    }

    private fun preview(c: Context, tone: String?) {
        playing?.stop()
        playing = tone?.let { RingtoneManager.getRingtone(c, uri(c, it)) }?.apply {
            audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build()
            play()
        }
    }
}
