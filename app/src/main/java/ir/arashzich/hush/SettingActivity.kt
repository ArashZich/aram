package ir.arashzich.hush

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/** One page of Settings, opened from a row of the Settings tab. */
class SettingActivity : Activity() {
    private lateinit var store: Store
    private var section = STATUS
    private var allApps = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.setting)
        store = Store(this)
        section = intent.getIntExtra(SECTION, STATUS)
        findViewById<TextView>(R.id.title).setText(titles.getValue(section))
        findViewById<View>(R.id.back).setOnClickListener { finish() }
        val content = findViewById<FrameLayout>(R.id.content)
        val page = layoutInflater.inflate(layouts.getValue(section), content, false)
        content.addView(page)
        when (section) {
            STATUS -> bindStatus()
            APPS -> bindApps()
            WORDS -> page.findViewById<View>(R.id.add_word).setOnClickListener { addWord() }
            CALM -> bindCalm()
            SOUND -> bindSound()
            HISTORY -> choices(page.findViewById(R.id.keeps), listOf(7, 30, 90), { getString(R.string.days, it) },
                { store.historyDays }) { store.historyDays = it; Inbox.prune(this); Inbox.changed(this) }
            REMOVE -> page.findViewById<View>(R.id.uninstall).setOnClickListener { uninstall() }
        }
    }

    override fun onResume() {
        super.onResume()
        when (section) {
            STATUS -> renderStatus()
            APPS -> renderApps()
            WORDS -> renderWords()
        }
    }

    // ---- status and setup

    private fun bindStatus() {
        findViewById<TextView>(R.id.command).text = Gate.adbCommand(this)
        findViewById<View>(R.id.copy).setOnClickListener {
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("adb", Gate.adbCommand(this)))
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.step_listener).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<View>(R.id.step_overlay).setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        findViewById<View>(R.id.step_post).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (section == STATUS) renderStatus()
    }

    private fun renderStatus() {
        Gate.sync(this, store)
        val ready = Gate.ready(this)
        findViewById<TextView>(R.id.status_line).setText(if (ready) R.string.st_ready else R.string.st_needs)
        findViewById<TextView>(R.id.status_hint).setText(when {
            !ready -> R.string.setup_why
            store.on -> R.string.hint_on
            else -> R.string.hint_off
        })
        findViewById<View>(R.id.setup).visibility = if (ready) View.GONE else View.VISIBLE
        step(R.id.step_listener, R.string.step_listener, Gate.listening(this))
        step(R.id.step_overlay, R.string.step_overlay, Gate.canDraw(this))
        findViewById<View>(R.id.step_secure).visibility = if (Gate.canWrite(this)) View.GONE else View.VISIBLE
        findViewById<View>(R.id.step_post).visibility = if (Inbox.canPost(this)) View.GONE else View.VISIBLE
    }

    private fun step(id: Int, label: Int, done: Boolean) = findViewById<TextView>(id).apply {
        text = if (done) getString(R.string.step_done, getString(label)) else getString(label)
        isEnabled = !done
    }

    // ---- people and words

    private fun renderWords() {
        val list = findViewById<LinearLayout>(R.id.words)
        list.removeAllViews()
        val words = store.words
        if (words.isEmpty()) {
            list.addView(hint(getString(R.string.words_empty)))
            return
        }
        words.forEach { w ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            row.addView(TextView(this).apply {
                text = w.text
                textSize = 15f
                setTextColor(getColor(R.color.ink))
            })
            row.addView(TextView(this).apply {
                text = "  ·  " + (w.pkg?.let { label(it) } ?: getString(R.string.any_app))
                textSize = 13f
                setTextColor(getColor(R.color.ink_soft))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(this).apply {
                text = "✕"
                textSize = 16f
                contentDescription = getString(R.string.remove)
                setTextColor(getColor(R.color.ink_soft))
                setPadding(dp(12), dp(4), dp(4), dp(4))
                setOnClickListener { store.words = store.words - w; renderWords() }
            })
            list.addView(row)
        }
    }

    private fun addWord() {
        val launchable = launcherApps().toSet()
        val apps = listOf<String?>(null) + store.seen.filter { it != packageName && it in launchable }.sortedBy { label(it).lowercase() }
        val input = EditText(this).apply {
            setHint(R.string.word_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@SettingActivity, android.R.layout.simple_spinner_dropdown_item,
                apps.map { it?.let { p -> label(p) } ?: getString(R.string.any_app) })
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
            addView(spinner)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.words_title)
            .setView(box)
            .setPositiveButton(R.string.add) { _, _ ->
                val text = input.text.toString().replace('\t', ' ').trim()
                if (text.isNotEmpty()) {
                    store.words = store.words + Word(text, apps[spinner.selectedItemPosition])
                    renderWords()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---- apps

    // Every real app (with a launcher icon): important first, then the busiest today, then by name.
    // Rows are built once; by default only apps the user set or that posted today are shown, the rest on search.
    // The names and icons of every app are slow to read: once, off the main thread; later visits only reorder.
    private var rows: List<AppRow>? = null
    private var loading = false

    private class AppRow(val tag: AppTag, val view: View, val refresh: () -> Unit)

    private fun bindApps() {
        val levels = Pages.levelNames.keys.toList()
        choices(findViewById(R.id.defaults), levels.indices.toList(), { getString(Pages.levelNames.getValue(levels[it])) },
            { levels.indexOf(store.defaultLevel) }) { store.defaultLevel = levels[it]; renderApps() }
        findViewById<EditText>(R.id.search).addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = filterApps()
        })
        findViewById<TextView>(R.id.all_apps).setOnClickListener { allApps = !allApps; filterApps() }
    }

    private fun renderApps() {
        val list = findViewById<LinearLayout>(R.id.apps)
        val built = rows
        if (built == null) {
            if (loading) return
            loading = true
            list.addView(hint(getString(R.string.loading)).apply { tag = LOADING })
            Thread {
                val apps = launcherApps().filter { it != packageName }
                    .map { Triple(it, label(it), runCatching { packageManager.getApplicationIcon(it) }.getOrNull()) }
                runOnUiThread {
                    loading = false
                    if (isDestroyed) return@runOnUiThread
                    rows = apps.map { (pkg, name, icon) -> appRow(list, pkg, name, icon) }
                    renderApps()
                }
            }.start()
            return
        }
        list.removeAllViews()
        built.forEach { it.refresh() }
        built.sortedWith(compareBy<AppRow> { store.level(it.tag.pkg) != Level.IMPORTANT }
            .thenByDescending { store.today(it.tag.pkg) }.thenBy { it.tag.name })
            .forEach { list.addView(it.view) }
        list.addView(hint(getString(R.string.apps_quiet_today)))
        filterApps()
    }

    private fun filterApps() {
        val q = findViewById<EditText>(R.id.search).text.toString().trim().lowercase()
        val list = findViewById<LinearLayout>(R.id.apps)
        var shown = 0
        var total = 0
        var empty: View? = null
        for (i in 0 until list.childCount) {
            val row = list.getChildAt(i)
            if (row.tag == LOADING) return
            val app = row.tag as? AppTag
            if (app == null) { empty = row; continue }
            val (pkg, name) = app
            total++
            val visible = when {
                q.isNotEmpty() -> name.contains(q)
                allApps -> true
                else -> store.isSet(pkg) || store.today(pkg) > 0
            }
            row.visibility = if (visible) View.VISIBLE else View.GONE
            if (visible) shown++
        }
        empty?.visibility = if (shown == 0 && q.isEmpty()) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.all_apps).apply {
            text = if (allApps) getString(R.string.fewer_apps) else getString(R.string.all_apps, total)
            visibility = if (q.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private data class AppTag(val pkg: String, val name: String)

    private fun appRow(parent: LinearLayout, pkg: String, name: String, icon: Drawable?): AppRow {
        val v = layoutInflater.inflate(R.layout.app_row, parent, false)
        val tag = AppTag(pkg, name.lowercase())
        v.tag = tag
        v.findViewById<ImageView>(R.id.icon).setImageDrawable(icon)
        v.findViewById<TextView>(R.id.name).text = name
        val count = v.findViewById<TextView>(R.id.count)
        val pill = v.findViewById<TextView>(R.id.level)
        fun show() {
            val n = store.today(pkg)
            count.text = if (n > 0) resources.getQuantityString(R.plurals.today, n, n) else ""
            val l = store.level(pkg)
            pill.setText(Pages.levelNames.getValue(l))
            pill.isSelected = l == Level.IMPORTANT
            pill.alpha = if (l == Level.SILENT || l == Level.SHADE) 0.6f else 1f
        }
        show()
        v.setOnClickListener {
            PopupMenu(this, pill).apply {
                Pages.levelNames.values.forEachIndexed { i, name -> menu.add(0, i, i, name) }
                menu.add(0, 99, 99, R.string.android_settings)
                setOnMenuItemClickListener { item ->
                    if (item.itemId == 99) {
                        startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg))
                    } else {
                        store.setLevel(pkg, Pages.levelNames.keys.elementAt(item.itemId))
                        show()
                    }
                    true
                }
            }.show()
        }
        return AppRow(tag, v, ::show)
    }

    private fun launcherApps(): List<String> =
        packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName }.distinct()

    private fun label(pkg: String): String =
        runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }
            .getOrDefault(pkg)

    // ---- calm

    private fun bindCalm() {
        findViewById<Switch>(R.id.quiet_on).apply {
            isChecked = store.quietOn
            setOnCheckedChangeListener { _, on -> store.quietOn = on }
        }
        bindTime(R.id.quiet_from, { store.quietFrom }) { store.quietFrom = it }
        bindTime(R.id.quiet_to, { store.quietTo }) { store.quietTo = it }
        val gapLabels = mapOf(0 to R.string.gap_0, 60 to R.string.gap_60, 300 to R.string.gap_300)
        choices(findViewById(R.id.gaps), gapLabels.keys.toList(), { getString(gapLabels.getValue(it)) },
            { store.gapSeconds }) { store.gapSeconds = it }
        choices(findViewById(R.id.stays), listOf(4, 8, 12), { getString(R.string.seconds, it) },
            { store.showSeconds }) { store.showSeconds = it }
        findViewById<View>(R.id.test).setOnClickListener {
            if (!Gate.canDraw(this)) {
                Toast.makeText(this, R.string.need_setup, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Popup.show(this, "sample", getString(R.string.app_name), getString(R.string.test_title),
                getString(R.string.test_body), getDrawable(R.mipmap.ic_launcher), store.showSeconds) {}
        }
    }

    private fun bindTime(id: Int, get: () -> Int, set: (Int) -> Unit) {
        val b = findViewById<TextView>(id)
        fun show() { b.text = String.format(Locale.getDefault(), "%02d:%02d", get() / 60, get() % 60) }
        show()
        b.setOnClickListener {
            TimePickerDialog(this, { _, h, m -> set(h * 60 + m); show() }, get() / 60, get() % 60, true).show()
        }
    }

    // ---- sound

    private fun bindSound() {
        findViewById<TextView>(R.id.default_sound).apply {
            fun show() {
                text = getString(R.string.sound_is, Sounds.name(this@SettingActivity, store.defaultSound))
                isSelected = store.defaultSound != null
            }
            show()
            setOnClickListener { Sounds.pick(this@SettingActivity, store.defaultSound) { store.defaultSound = it; show() } }
        }
    }

    // ---- leaving: the one thing Hush changed on the phone goes back before Android removes it

    private fun uninstall() {
        Gate.setOn(this, store, false)
        val pkg = Uri.parse("package:$packageName")
        runCatching { startActivity(Intent(Intent.ACTION_DELETE, pkg)) }
            .onFailure { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)) }
    }

    // ---- small parts

    private fun choices(row: LinearLayout, values: List<Int>, label: (Int) -> String, get: () -> Int, set: (Int) -> Unit) {
        val chips = values.map { value ->
            (layoutInflater.inflate(R.layout.chip, row, false) as TextView).apply { text = label(value); row.addView(this) }
        }
        fun select() = chips.forEachIndexed { i, chip -> chip.isSelected = values[i] == get() }
        select()
        chips.forEachIndexed { i, chip -> chip.setOnClickListener { set(values[i]); select() } }
    }

    private fun hint(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(getColor(R.color.ink_soft))
        setPadding(0, dp(6), 0, dp(6))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val SECTION = "section"
        const val STATUS = 0
        const val APPS = 1
        const val WORDS = 2
        const val CALM = 3
        const val SOUND = 4
        const val HISTORY = 5
        const val REMOVE = 6
        private const val LOADING = "loading"

        val titles = mapOf(
            STATUS to R.string.set_status, APPS to R.string.apps_title, WORDS to R.string.words_title,
            CALM to R.string.calm_title, SOUND to R.string.sound, HISTORY to R.string.set_history,
            REMOVE to R.string.remove_title,
        )
        private val layouts = mapOf(
            STATUS to R.layout.set_status, APPS to R.layout.set_apps, WORDS to R.layout.set_words,
            CALM to R.layout.set_calm, SOUND to R.layout.set_sound, HISTORY to R.layout.set_history,
            REMOVE to R.layout.set_remove,
        )
    }
}
