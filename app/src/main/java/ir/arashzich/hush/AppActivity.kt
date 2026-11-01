package ir.arashzich.hush

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.TextView

/** One app's notifications, in full, with how Hush treats that app. Opening it marks them read. */
class AppActivity : Activity() {
    private lateinit var store: Store
    private lateinit var pkg: String
    private lateinit var adapter: EntryAdapter
    private val refresh: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.app)
        store = Store(this)
        pkg = intent.getStringExtra(PKG) ?: return finish()

        findViewById<ImageView>(R.id.icon).setImageDrawable(Inbox.icon(this, pkg))
        findViewById<TextView>(R.id.name).text = Inbox.label(this, pkg)
        findViewById<View>(R.id.back).setOnClickListener { finish() }
        findViewById<View>(R.id.clear_app).setOnClickListener {
            Db.get(this).clearApp(pkg)
            Inbox.changed(this)
            finish()
        }
        findViewById<View>(R.id.more).setOnClickListener { v ->
            PopupMenu(this, v).apply {
                menu.add(0, 1, 1, R.string.sound_settings)
                menu.add(0, 2, 2, R.string.android_settings)
                setOnMenuItemClickListener {
                    val action = if (it.itemId == 1) Settings.ACTION_APP_NOTIFICATION_SETTINGS
                    else Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                    val i = Intent(action).putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
                    if (it.itemId == 2) i.data = android.net.Uri.parse("package:$pkg")
                    runCatching { startActivity(i) }
                    true
                }
            }.show()
        }

        adapter = EntryAdapter(this, showApp = false, onOpen = { Inbox.open(this, it) }, onClear = {
            Db.get(this).clear(it.id)
            Inbox.changed(this)
        })
        findViewById<ListView>(R.id.list).apply {
            adapter = this@AppActivity.adapter
            emptyView = findViewById(R.id.empty)
        }
        levels()
        sound()
    }

    override fun onResume() {
        super.onResume()
        Inbox.watch(refresh)
        render()
    }

    // Leaving marks what was seen as read; new ones that arrive while here stay new until the next visit.
    override fun onPause() {
        Inbox.unwatch(refresh)
        Db.get(this).markRead(pkg)
        Inbox.changed(this)
        super.onPause()
    }

    private fun render() {
        val items = Db.get(this).open(pkg)
        adapter.items = items
        findViewById<TextView>(R.id.count).text = resources.getQuantityString(R.plurals.in_inbox, items.size, items.size)
        findViewById<View>(R.id.clear_app).visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun levels() {
        val row = findViewById<LinearLayout>(R.id.levels)
        val hint = findViewById<TextView>(R.id.level_hint)
        val chips = Pages.levelNames.map { (level, name) ->
            (layoutInflater.inflate(R.layout.chip, row, false) as TextView).apply {
                setText(name)
                row.addView(this)
                tag = level
            }
        }
        fun select() {
            val l = store.level(pkg)
            chips.forEach { it.isSelected = it.tag == l }
            hint.setText(Pages.levelHints.getValue(l))
        }
        select()
        chips.forEach { chip -> chip.setOnClickListener { store.setLevel(pkg, chip.tag as Level); select() } }
    }

    // Hush's tone for this app; the app's own sound has to be muted in Android's settings, Hush cannot do that.
    private fun sound() {
        val chip = findViewById<TextView>(R.id.sound)
        val mute = findViewById<View>(R.id.mute_app)
        val hint = findViewById<TextView>(R.id.sound_hint)
        fun show() {
            val tone = store.sound(pkg)
            chip.text = getString(R.string.sound_is, Sounds.name(this, tone))
            chip.isSelected = tone != null
            mute.visibility = if (tone != null) View.VISIBLE else View.GONE
            hint.text = if (tone != null) getString(R.string.sound_hint_on, Inbox.label(this, pkg))
            else getString(R.string.sound_hint_off)
        }
        show()
        chip.setOnClickListener { Sounds.pick(this, store.sound(pkg)) { store.setSound(pkg, it); show() } }
        mute.setOnClickListener {
            runCatching {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg))
            }
        }
    }

    companion object {
        const val PKG = "pkg"
    }
}
