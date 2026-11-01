package ir.arashzich.hush

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/** The Settings tab: on/off, setup, the inbox, people and words, calm, apps, and leaving cleanly. */
class SettingsPage(private val a: Activity, private val root: View) {
    private val store = Store(a)
    private var allApps = false
    private val levelNames = Pages.levelNames

    init {
        root.findViewById<View>(R.id.toggle).setOnClickListener {
            if (!store.on && !Gate.ready(a)) {
                Toast.makeText(a, R.string.need_setup, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Gate.setOn(a, store, !store.on)
            render()
        }

        root.findViewById<TextView>(R.id.command).text = Gate.adbCommand(a)
        root.findViewById<View>(R.id.copy).setOnClickListener {
            a.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("adb", Gate.adbCommand(a)))
            Toast.makeText(a, R.string.copied, Toast.LENGTH_SHORT).show()
        }
        root.findViewById<View>(R.id.step_listener).setOnClickListener {
            a.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        root.findViewById<View>(R.id.step_overlay).setOnClickListener {
            a.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${a.packageName}")))
        }

        root.findViewById<View>(R.id.add_word).setOnClickListener { addWord() }
        root.findViewById<EditText>(R.id.search).addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = filterApps()
        })

        root.findViewById<Switch>(R.id.quiet_on).apply {
            isChecked = store.quietOn
            setOnCheckedChangeListener { _, on -> store.quietOn = on }
        }
        bindTime(R.id.quiet_from, { store.quietFrom }) { store.quietFrom = it }
        bindTime(R.id.quiet_to, { store.quietTo }) { store.quietTo = it }

        val gapLabels = mapOf(0 to R.string.gap_0, 60 to R.string.gap_60, 300 to R.string.gap_300)
        choices(root.findViewById(R.id.gaps), gapLabels.keys.toList(), { a.getString(gapLabels.getValue(it)) },
            { store.gapSeconds }) { store.gapSeconds = it }
        val levels = levelNames.keys.toList()
        choices(root.findViewById(R.id.defaults), levels.indices.toList(), { a.getString(levelNames.getValue(levels[it])) },
            { levels.indexOf(store.defaultLevel) }) { store.defaultLevel = levels[it]; renderApps() }
        root.findViewById<TextView>(R.id.default_sound).apply {
            fun show() {
                text = a.getString(R.string.sound_is, Sounds.name(a, store.defaultSound))
                isSelected = store.defaultSound != null
            }
            show()
            setOnClickListener { Sounds.pick(a, store.defaultSound) { store.defaultSound = it; show() } }
        }
        choices(root.findViewById(R.id.keeps), listOf(7, 30, 90), { a.getString(R.string.days, it) },
            { store.historyDays }) { store.historyDays = it; Inbox.prune(a); Inbox.changed(a) }

        root.findViewById<View>(R.id.step_post).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33) a.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        root.findViewById<View>(R.id.uninstall).setOnClickListener { uninstall() }
        choices(root.findViewById(R.id.stays), listOf(4, 8, 12), { a.getString(R.string.seconds, it) },
            { store.showSeconds }) { store.showSeconds = it }

        root.findViewById<View>(R.id.test).setOnClickListener {
            if (!Gate.canDraw(a)) {
                Toast.makeText(a, R.string.need_setup, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Popup.show(a, "sample", a.getString(R.string.app_name), a.getString(R.string.test_title),
                a.getString(R.string.test_body), a.getDrawable(R.mipmap.ic_launcher), store.showSeconds) {}
        }
    }

    fun resume() {
        Gate.sync(a, store)
        render()
        renderWords()
        renderApps()
    }

    private fun render() {
        val ready = Gate.ready(a)
        root.findViewById<View>(R.id.setup).visibility = if (ready) View.GONE else View.VISIBLE
        step(R.id.step_listener, R.string.step_listener, Gate.listening(a))
        step(R.id.step_overlay, R.string.step_overlay, Gate.canDraw(a))
        root.findViewById<View>(R.id.step_secure).visibility = if (Gate.canWrite(a)) View.GONE else View.VISIBLE
        root.findViewById<View>(R.id.step_post).visibility = if (Inbox.canPost(a)) View.GONE else View.VISIBLE

        root.findViewById<View>(R.id.toggle).isSelected = store.on
        root.findViewById<TextView>(R.id.state).setText(if (store.on) R.string.on else R.string.off)
        root.findViewById<TextView>(R.id.state_hint).setText(when {
            store.on -> R.string.hint_on
            ready -> R.string.hint_off
            else -> R.string.need_setup
        })
    }

    private fun step(id: Int, label: Int, done: Boolean) = root.findViewById<TextView>(id).apply {
        text = if (done) a.getString(R.string.step_done, a.getString(label)) else a.getString(label)
        isEnabled = !done
    }

    // ---- people and words

    private fun renderWords() {
        val list = root.findViewById<LinearLayout>(R.id.words)
        list.removeAllViews()
        val words = store.words
        if (words.isEmpty()) {
            list.addView(hint(a.getString(R.string.words_empty)))
            return
        }
        words.forEach { w ->
            val row = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            row.addView(TextView(a).apply {
                text = w.text
                textSize = 15f
                setTextColor(a.getColor(R.color.ink))
            })
            row.addView(TextView(a).apply {
                text = "  ·  " + (w.pkg?.let { label(it) } ?: a.getString(R.string.any_app))
                textSize = 13f
                setTextColor(a.getColor(R.color.ink_soft))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(a).apply {
                text = "✕"
                textSize = 16f
                contentDescription = a.getString(R.string.remove)
                setTextColor(a.getColor(R.color.ink_soft))
                setPadding(dp(12), dp(4), dp(4), dp(4))
                setOnClickListener { store.words = store.words - w; renderWords() }
            })
            list.addView(row)
        }
    }

    private fun addWord() {
        val launchable = launcherApps().toSet()
        val apps = listOf<String?>(null) + store.seen.filter { it != a.packageName && it in launchable }.sortedBy { label(it).lowercase() }
        val input = EditText(a).apply {
            setHint(R.string.word_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        val spinner = Spinner(a).apply {
            adapter = ArrayAdapter(a, android.R.layout.simple_spinner_dropdown_item,
                apps.map { it?.let { p -> label(p) } ?: a.getString(R.string.any_app) })
        }
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
            addView(spinner)
        }
        AlertDialog.Builder(a)
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
    private fun renderApps() {
        val list = root.findViewById<LinearLayout>(R.id.apps)
        list.removeAllViews()
        val apps = launcherApps().filter { it != a.packageName }.map { it to label(it) }
            .sortedWith(compareBy<Pair<String, String>> { store.level(it.first) != Level.IMPORTANT }
                .thenByDescending { store.today(it.first) }.thenBy { it.second.lowercase() })
        apps.forEach { (pkg, name) -> list.addView(appRow(list, pkg).apply { tag = AppTag(pkg, name.lowercase()) }) }
        list.addView(hint(a.getString(R.string.apps_quiet_today)).apply { tag = null })
        root.findViewById<TextView>(R.id.all_apps).setOnClickListener { allApps = !allApps; filterApps() }
        filterApps()
    }

    private fun filterApps() {
        val q = root.findViewById<EditText>(R.id.search).text.toString().trim().lowercase()
        val list = root.findViewById<LinearLayout>(R.id.apps)
        var shown = 0
        var total = 0
        var empty: View? = null
        for (i in 0 until list.childCount) {
            val row = list.getChildAt(i)
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
        root.findViewById<TextView>(R.id.all_apps).apply {
            text = if (allApps) a.getString(R.string.fewer_apps) else a.getString(R.string.all_apps, total)
            visibility = if (q.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private data class AppTag(val pkg: String, val name: String)

    private fun appRow(parent: LinearLayout, pkg: String): View {
        val v = a.layoutInflater.inflate(R.layout.app_row, parent, false)
        v.findViewById<ImageView>(R.id.icon).setImageDrawable(runCatching { a.packageManager.getApplicationIcon(pkg) }.getOrNull())
        v.findViewById<TextView>(R.id.name).text = label(pkg)
        val n = store.today(pkg)
        v.findViewById<TextView>(R.id.count).text = if (n > 0) a.resources.getQuantityString(R.plurals.today, n, n) else ""
        val pill = v.findViewById<TextView>(R.id.level)
        fun show() {
            val l = store.level(pkg)
            pill.setText(levelNames.getValue(l))
            pill.isSelected = l == Level.IMPORTANT
            pill.alpha = if (l == Level.SILENT || l == Level.SHADE) 0.6f else 1f
        }
        show()
        v.setOnClickListener {
            PopupMenu(a, pill).apply {
                levelNames.values.forEachIndexed { i, name -> menu.add(0, i, i, name) }
                menu.add(0, 99, 99, R.string.android_settings)
                setOnMenuItemClickListener { item ->
                    if (item.itemId == 99) {
                        a.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg))
                    } else {
                        store.setLevel(pkg, levelNames.keys.elementAt(item.itemId))
                        show()
                    }
                    true
                }
            }.show()
        }
        return v
    }

    private fun launcherApps(): List<String> =
        a.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName }.distinct()

    private fun label(pkg: String): String =
        runCatching { a.packageManager.getApplicationLabel(a.packageManager.getApplicationInfo(pkg, 0)).toString() }
            .getOrDefault(pkg)

    // ---- leaving: the one thing Hush changed on the phone goes back before Android removes it

    private fun uninstall() {
        Gate.setOn(a, store, false)
        render()
        val pkg = Uri.parse("package:${a.packageName}")
        runCatching { a.startActivity(Intent(Intent.ACTION_DELETE, pkg)) }
            .onFailure { a.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)) }
    }

    // ---- calm

    private fun bindTime(id: Int, get: () -> Int, set: (Int) -> Unit) {
        val b = root.findViewById<TextView>(id)
        fun show() { b.text = String.format(Locale.getDefault(), "%02d:%02d", get() / 60, get() % 60) }
        show()
        b.setOnClickListener {
            TimePickerDialog(a, { _, h, m -> set(h * 60 + m); show() }, get() / 60, get() % 60, true).show()
        }
    }

    private fun choices(row: LinearLayout, values: List<Int>, label: (Int) -> String, get: () -> Int, set: (Int) -> Unit) {
        val chips = values.map { value ->
            (a.layoutInflater.inflate(R.layout.chip, row, false) as TextView).apply { text = label(value); row.addView(this) }
        }
        fun select() = chips.forEachIndexed { i, chip -> chip.isSelected = values[i] == get() }
        select()
        chips.forEachIndexed { i, chip -> chip.setOnClickListener { set(values[i]); select() } }
    }

    private fun hint(text: String) = TextView(a).apply {
        this.text = text
        textSize = 13f
        setTextColor(a.getColor(R.color.ink_soft))
        setPadding(0, dp(6), 0, dp(6))
    }

    private fun dp(v: Int) = (v * a.resources.displayMetrics.density).toInt()
}
