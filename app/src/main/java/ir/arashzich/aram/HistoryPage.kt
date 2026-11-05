package ir.arashzich.aram

import android.app.Activity
import android.graphics.Typeface
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * The History tab: everything Aram kept, cleared or not, newest first. A line per day and per app that opens in place;
 * the search box and the app chips show the notifications themselves.
 */
class HistoryPage(private val a: Activity, root: View) {
    private val search = root.findViewById<EditText>(R.id.history_search)
    private val empty = root.findViewById<TextView>(R.id.history_empty)
    private val chips = root.findViewById<LinearLayout>(R.id.history_apps)
    private val entries = EntryAdapter(a, showApp = true, onOpen = { Inbox.open(a, it) }, onClear = null)
    private val adapter = Rows()
    private val zone = ZoneId.systemDefault()
    private val opened = HashSet<String>()
    private var items: List<Entry> = emptyList()
    private var flat = false
    private var only: String? = null
    private var shownApps: List<String>? = null
    private var limit = PAGE
    private var asked = 0  // the newest render; older answers that come back late are dropped

    init {
        root.findViewById<ListView>(R.id.history_list).apply {
            adapter = this@HistoryPage.adapter
            emptyView = empty
            // Near the end of a full page, the next page.
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: AbsListView?, state: Int) {}
                override fun onScroll(view: AbsListView?, first: Int, visible: Int, total: Int) {
                    if (items.size >= limit && first + visible >= total - 10) { limit += PAGE; render() }
                }
            })
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { limit = PAGE; render() }
        })
    }

    fun render() {
        val q = search.text.toString().trim()
        val app = only
        val n = limit
        val ask = ++asked
        Inbox.load({
            val db = Db.get(a)
            val apps = if (q.isEmpty()) emptyList() else db.apps().filter { Inbox.label(a, it).contains(q, true) }
            db.history(q, apps, n, app) to db.appsByCount()
        }) { (found, apps) ->
            if (ask != asked || a.isDestroyed) return@load
            items = found
            flat = q.isNotEmpty() || app != null
            show()
            chips(apps)
            empty.setText(if (q.isEmpty()) R.string.history_empty else R.string.no_match)
        }
    }

    private fun show() {
        val rows = Rules.historyRows(items, { Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toEpochDay() }, opened, flat)
        entries.items = rows.filterIsInstance<HistoryItem>().map { it.entry }
        adapter.rows = rows
    }

    private fun chips(apps: List<String>) {
        if (apps != shownApps) {
            shownApps = apps
            chips.removeAllViews()
            (listOf<String?>(null) + apps).forEach { pkg ->
                chips.addView((a.layoutInflater.inflate(R.layout.chip, chips, false) as TextView).apply {
                    text = if (pkg == null) a.getString(R.string.history_all) else Inbox.label(a, pkg)
                    tag = pkg
                    maxWidth = (150 * resources.displayMetrics.density).toInt()
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setOnClickListener { only = pkg; limit = PAGE; render() }
                })
            }
        }
        (0 until chips.childCount).forEach { chips.getChildAt(it).run { isSelected = tag == only } }
    }

    /** Days, apps and notifications in one list; the notifications come from [entries]. */
    private inner class Rows : BaseAdapter() {
        var rows: List<HistoryRow> = emptyList()
            set(v) {
                field = v
                var n = 0
                places = v.map { if (it is HistoryItem) n++ else -1 }
                notifyDataSetChanged()
            }
        private var places: List<Int> = emptyList()

        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 3
        override fun getItemViewType(position: Int) = when (rows[position]) {
            is HistoryDay -> 0
            is HistoryApp -> 1
            is HistoryItem -> 2
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            when (val row = rows[position]) {
                is HistoryDay -> dayLine(row, convertView as TextView?)
                is HistoryApp -> appLine(row, convertView, parent)
                is HistoryItem -> entries.getView(places[position], convertView, parent)
            }
    }

    private fun dayLine(row: HistoryDay, old: TextView?) = (old ?: TextView(a).apply {
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        setTextColor(a.getColor(R.color.ink_soft))
        val dp = resources.displayMetrics.density
        setPaddingRelative((20 * dp).toInt(), (14 * dp).toInt(), (20 * dp).toInt(), (6 * dp).toInt())
    }).apply { text = dayText(a, row.day) }

    private fun appLine(row: HistoryApp, old: View?, parent: ViewGroup): View {
        val frame = old as FrameLayout? ?: FrameLayout(parent.context).apply {
            val side = (16 * resources.displayMetrics.density).toInt()
            setPaddingRelative(side, 0, side, 0)
            LayoutInflater.from(a).inflate(R.layout.group_row, this, true)
        }
        val v = frame.getChildAt(0)
        val latest = row.items.first()
        val unread = row.items.count { !it.read }
        v.findViewById<ImageView>(R.id.icon).setImageDrawable(Inbox.icon(a, row.pkg))
        v.findViewById<TextView>(R.id.name).text = Inbox.label(a, row.pkg)
        v.findViewById<TextView>(R.id.time).text = DateUtils.formatDateTime(a, latest.time, DateUtils.FORMAT_SHOW_TIME)
        v.findViewById<TextView>(R.id.preview).text = Inbox.preview(latest)
        v.findViewById<TextView>(R.id.badge).apply {
            text = String.format(Locale.getDefault(), "%d", row.items.size)
            isEnabled = unread > 0
            alpha = if (unread > 0) 1f else 0.35f
        }
        v.setOnClickListener {
            if (!opened.remove(row.key)) opened.add(row.key)
            show()
        }
        return frame
    }

    private companion object {
        const val PAGE = 500
    }
}
