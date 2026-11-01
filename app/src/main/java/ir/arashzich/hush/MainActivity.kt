package ir.arashzich.hush

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/** The names and meanings of the levels, the same on every screen. */
object Pages {
    val levelNames = linkedMapOf(
        Level.IMPORTANT to R.string.lv_important, Level.NORMAL to R.string.lv_normal,
        Level.SILENT to R.string.lv_silent, Level.SHADE to R.string.lv_shade,
    )
    val levelHints = mapOf(
        Level.IMPORTANT to R.string.lvh_important, Level.NORMAL to R.string.lvh_normal,
        Level.SILENT to R.string.lvh_silent, Level.SHADE to R.string.lvh_shade,
    )
}

/** Three tabs: the inbox by app, the searchable history, and settings. */
class MainActivity : Activity() {
    private lateinit var store: Store
    private lateinit var settings: SettingsPage
    private lateinit var history: HistoryPage
    private var tab = 0
    private val pages by lazy { listOf(R.id.page_inbox, R.id.page_history, R.id.page_settings).map { findViewById<View>(it) } }
    private val tabs by lazy { listOf(R.id.tab_inbox, R.id.tab_history, R.id.tab_settings).map { findViewById<View>(it) } }
    private val refresh: () -> Unit = { renderInbox(); history.render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main)
        store = Store(this)
        settings = SettingsPage(this, findViewById(R.id.page_settings))
        history = HistoryPage(this, findViewById(R.id.page_history))

        val icons = listOf(R.drawable.ic_inbox, R.drawable.ic_history, R.drawable.ic_tune)
        val labels = listOf(R.string.tab_inbox, R.string.tab_history, R.string.tab_settings)
        tabs.forEachIndexed { i, t ->
            t.findViewById<ImageView>(R.id.tab_icon).setImageResource(icons[i])
            t.findViewById<TextView>(R.id.tab_label).setText(labels[i])
            t.contentDescription = getString(labels[i])
            t.setOnClickListener { show(i) }
        }
        show(savedInstanceState?.getInt("tab") ?: 0)
        if (savedInstanceState == null) Reminder.opened(this, intent)

        findViewById<View>(R.id.read_all).setOnClickListener {
            Db.get(this).markAllRead()
            Inbox.changed(this)
        }
        findViewById<View>(R.id.clear_all).setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage(R.string.clear_all_ask)
                .setPositiveButton(R.string.clear_all) { _, _ -> Db.get(this).clearAll(); Inbox.changed(this) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putInt("tab", tab)
    }

    // Tapping Hush's summary notification brings the inbox forward; a reminder opens its notification.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        show(0)
        Reminder.opened(this, intent)
    }

    override fun onResume() {
        super.onResume()
        Inbox.watch(refresh)
        settings.resume()
        refresh()
        Inbox.postSummary(this)
    }

    override fun onPause() {
        Inbox.unwatch(refresh)
        super.onPause()
    }

    private fun show(i: Int) {
        tab = i
        pages.forEachIndexed { j, p -> p.visibility = if (j == i) View.VISIBLE else View.GONE }
        tabs.forEachIndexed { j, t -> t.isSelected = j == i }
        if (i == 2) settings.resume()
    }

    // ---- inbox

    private fun renderInbox() {
        val open = Db.get(this).open()
        val groups = Rules.groups(open)
        val unread = open.count { !it.read }

        val count = findViewById<TextView>(R.id.hero_count)
        val title = findViewById<TextView>(R.id.hero_title)
        val hint = findViewById<TextView>(R.id.hero_hint)
        val action = findViewById<TextView>(R.id.hero_action)
        action.visibility = View.GONE
        when {
            !store.on -> {
                count.visibility = View.GONE
                title.setText(R.string.hush_off)
                hint.setText(if (Gate.ready(this)) R.string.hint_off else R.string.need_setup)
                action.visibility = View.VISIBLE
                action.isSelected = true
                action.setText(R.string.turn_on)
                action.setOnClickListener {
                    if (Gate.ready(this)) { Gate.setOn(this, store, true); refresh() } else show(2)
                }
            }
            open.isEmpty() -> {
                count.visibility = View.GONE
                title.setText(R.string.all_quiet)
                hint.setText(R.string.all_quiet_hint)
            }
            else -> {
                count.visibility = if (unread > 0) View.VISIBLE else View.GONE
                count.text = String.format(Locale.getDefault(), "%d", unread)
                title.text = if (unread > 0) resources.getQuantityString(R.plurals.new_label, unread)
                else getString(R.string.all_read)
                hint.text = resources.getQuantityString(R.plurals.from_apps, groups.size, groups.size)
            }
        }
        findViewById<View>(R.id.read_all).visibility = if (unread > 0) View.VISIBLE else View.GONE
        findViewById<View>(R.id.clear_all).visibility = if (open.isNotEmpty()) View.VISIBLE else View.GONE

        val list = findViewById<LinearLayout>(R.id.groups)
        list.removeAllViews()
        groups.forEach { g ->
            val v = layoutInflater.inflate(R.layout.group_row, list, false)
            v.findViewById<ImageView>(R.id.icon).setImageDrawable(Inbox.icon(this, g.pkg))
            v.findViewById<TextView>(R.id.name).text = Inbox.label(this, g.pkg)
            v.findViewById<TextView>(R.id.time).text = whenText(this, g.latest.time)
            v.findViewById<TextView>(R.id.preview).text = Inbox.preview(g.latest)
            v.findViewById<TextView>(R.id.badge).apply {
                text = String.format(Locale.getDefault(), "%d", if (g.unread > 0) g.unread else g.count)
                isEnabled = g.unread > 0
                alpha = if (g.unread > 0) 1f else 0.35f
            }
            v.setOnClickListener { startActivity(Intent(this, AppActivity::class.java).putExtra(AppActivity.PKG, g.pkg)) }
            list.addView(v)
        }

        tabs[0].findViewById<TextView>(R.id.tab_badge).apply {
            visibility = if (unread > 0) View.VISIBLE else View.GONE
            text = if (unread > 99) "99+" else String.format(Locale.getDefault(), "%d", unread)
        }
    }
}
