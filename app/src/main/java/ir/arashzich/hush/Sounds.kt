package ir.arashzich.hush

import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri

/**
 * Hush's own notification tones. An app set to one of them sounds through Hush: Hush's summary notification is
 * posted once on that tone's channel, so Android's volume, silent mode and Do Not Disturb still apply.
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
    )

    private var playing: Ringtone? = null

    fun name(c: Context, tone: String?): String =
        tones[tone]?.let { c.getString(it.first) } ?: c.getString(R.string.tone_none)

    private fun uri(c: Context, tone: String) = Uri.parse("android.resource://${c.packageName}/${tones.getValue(tone).second}")

    /** The channel that plays [tone], made the first time it is needed. */
    fun channel(c: Context, tone: String): String {
        val id = "tone_$tone"
        val nm = c.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(id) == null) {
            nm.createNotificationChannel(
                NotificationChannel(id, c.getString(R.string.channel_tone, name(c, tone)), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    setSound(uri(c, tone), AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 60, 80, 60)
                    setShowBadge(false)
                }
            )
        }
        return id
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
