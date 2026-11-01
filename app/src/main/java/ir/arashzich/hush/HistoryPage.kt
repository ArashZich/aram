package ir.arashzich.hush

import android.app.Activity
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AbsListView
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView

/** The History tab: everything Hush kept, cleared or not, newest first, searchable by app, sender and text. */
class HistoryPage(private val a: Activity, root: View) {
    private val search = root.findViewById<EditText>(R.id.history_search)
    private val empty = root.findViewById<TextView>(R.id.history_empty)
    private val adapter = EntryAdapter(a, showApp = true, onOpen = { Inbox.open(a, it) }, onClear = null)
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
                    if (total >= limit && first + visible >= total - 10) { limit += PAGE; render() }
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
        val n = limit
        val ask = ++asked
        Inbox.load({
            val db = Db.get(a)
            val apps = if (q.isEmpty()) emptyList() else db.apps().filter { Inbox.label(a, it).contains(q, true) }
            db.history(q, apps, n)
        }) { items ->
            if (ask != asked || a.isDestroyed) return@load
            adapter.items = items
            empty.setText(if (q.isEmpty()) R.string.history_empty else R.string.no_match)
        }
    }

    private companion object {
        const val PAGE = 200
    }
}
