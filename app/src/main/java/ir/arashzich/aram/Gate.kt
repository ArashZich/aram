package ir.arashzich.aram

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/**
 * Android's own floating (heads-up) switch and the three permissions Aram needs.
 * Switching Aram on turns heads-up off for every app; [Listener] then floats only what matters with its own card.
 */
object Gate {
    private const val HEADS_UP = "heads_up_notifications_enabled"

    fun canWrite(c: Context) =
        c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun listening(c: Context): Boolean {
        val me = ComponentName(c, Listener::class.java)
        if (Build.VERSION.SDK_INT >= 27) {
            return c.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(me)
        }
        val list = Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners").orEmpty()
        return me.flattenToString() in list.split(':')
    }

    fun canDraw(c: Context) = Settings.canDrawOverlays(c)

    fun ready(c: Context) = canWrite(c) && listening(c) && canDraw(c)

    /**
     * One adb line that grants all three. The last op (10008) is Xiaomi's Autostart: without it HyperOS refuses to
     * bind the listener at all; on other phones it is an unknown op and simply fails.
     */
    fun adbCommand(c: Context): String {
        val p = c.packageName
        return "adb shell \"pm grant $p android.permission.WRITE_SECURE_SETTINGS; " +
            "pm grant $p android.permission.POST_NOTIFICATIONS; " +
            "appops set $p SYSTEM_ALERT_WINDOW allow; appops set $p GET_USAGE_STATS allow; " +
            "appops set $p 10008 allow; " +
            "cmd notification allow_listener $p/$p.Listener\""
    }

    val headsUpUri: Uri = Settings.Global.getUriFor(HEADS_UP)

    private fun headsUp(c: Context) = Settings.Global.getString(c.contentResolver, HEADS_UP)

    private fun putHeadsUp(c: Context, v: String) {
        runCatching { Settings.Global.putString(c.contentResolver, HEADS_UP, v) }
    }

    fun setOn(c: Context, s: Store, on: Boolean) {
        if (on) {
            if (!ready(c)) return
            if (s.savedHeadsUp == null) s.savedHeadsUp = headsUp(c) ?: "1"
            putHeadsUp(c, "0")
            s.on = true
            Listener.sweep()
        } else {
            if (canWrite(c)) putHeadsUp(c, s.savedHeadsUp ?: "1")
            s.savedHeadsUp = null
            Popup.hide()
            s.on = false
        }
        Inbox.changed(c)
    }

    /** Heads-up back off if something (a system update, the user in Settings) turned it on again. */
    fun sync(c: Context, s: Store) {
        if (s.on && canWrite(c) && headsUp(c) != "0") putHeadsUp(c, "0")
    }
}
