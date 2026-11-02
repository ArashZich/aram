package ir.arashzich.hush

import android.app.Activity
import android.content.Intent
import android.view.View
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/** The Settings tab: a small on/off row and a short list; each row opens its own page (SettingActivity). */
class SettingsPage(private val a: Activity, private val root: View) {
    private val store = Store(a)
    private val power = root.findViewById<Switch>(R.id.power)

    init {
        root.findViewById<View>(R.id.power_row).setOnClickListener { power.toggle() }
        root.findViewById<TextView>(R.id.version).text = a.getString(R.string.version,
            runCatching { a.packageManager.getPackageInfo(a.packageName, 0).versionName }.getOrNull().orEmpty())
    }

    fun resume() {
        Gate.sync(a, store)
        render()
    }

    private fun render() {
        val ready = Gate.ready(a)
        power.setOnCheckedChangeListener(null)
        power.isChecked = store.on
        power.setOnCheckedChangeListener { _, on ->
            if (on && !Gate.ready(a)) {
                Toast.makeText(a, R.string.need_setup, Toast.LENGTH_SHORT).show()
                open(SettingActivity.STATUS)
            } else Gate.setOn(a, store, on)
            render()
        }
        root.findViewById<TextView>(R.id.state_hint).setText(when {
            store.on -> R.string.hint_on
            ready -> R.string.hint_off
            else -> R.string.need_setup
        })

        val list = root.findViewById<LinearLayout>(R.id.rows)
        list.removeAllViews()
        val ok = ready && Inbox.canPost(a)
        row(list, SettingActivity.STATUS, a.getString(if (ok) R.string.st_ready else R.string.st_needs), !ok)
        row(list, SettingActivity.APPS, a.getString(R.string.apps_sum, a.getString(Pages.levelNames.getValue(store.defaultLevel))))
        val words = store.words.size
        row(list, SettingActivity.WORDS,
            if (words == 0) a.getString(R.string.words_empty) else a.resources.getQuantityString(R.plurals.words_count, words, words))
        val quiet = if (store.quietOn) a.getString(R.string.quiet_sum, time(store.quietFrom), time(store.quietTo)) else a.getString(R.string.quiet_off)
        val digest = when {
            !store.digestOn -> null
            store.digestEvery == 0 -> a.getString(R.string.digest_sum_at)
            else -> a.getString(R.string.digest_sum_every, store.digestEvery)
        }
        row(list, SettingActivity.CALM, listOfNotNull(quiet, digest).joinToString(" · "))
        row(list, SettingActivity.HISTORY, a.getString(R.string.days, store.historyDays))
        row(list, SettingActivity.REMOVE, null)
    }

    private fun row(list: LinearLayout, section: Int, summary: String?, warn: Boolean = false) {
        val v = a.layoutInflater.inflate(R.layout.setting_row, list, false)
        v.findViewById<TextView>(R.id.title).setText(SettingActivity.titles.getValue(section))
        v.findViewById<TextView>(R.id.summary).apply {
            text = summary
            visibility = if (summary == null) View.GONE else View.VISIBLE
            if (warn) setTextColor(a.getColor(R.color.accent))
        }
        v.setOnClickListener { open(section) }
        list.addView(v)
    }

    private fun open(section: Int) =
        a.startActivity(Intent(a, SettingActivity::class.java).putExtra(SettingActivity.SECTION, section))

    private fun time(m: Int) = String.format(Locale.getDefault(), "%02d:%02d", m / 60, m % 60)
}
