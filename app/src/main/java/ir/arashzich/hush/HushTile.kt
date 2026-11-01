package ir.arashzich.hush

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile: Hush on or off. */
class HushTile : TileService() {
    override fun onStartListening() = render()

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        val store = Store(this)
        if (!Gate.ready(this)) {
            val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
            } else {
                @Suppress("DEPRECATION") startActivityAndCollapse(i)
            }
            return
        }
        Gate.setOn(this, store, !store.on)
        render()
    }

    private fun render() {
        val t = qsTile ?: return
        t.state = if (Store(this).on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.updateTile()
    }
}
