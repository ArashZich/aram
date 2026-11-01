package ir.arashzich.hush

import android.app.Activity
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView

/** The History tab: everything Hush kept, cleared or not, newest first, searchable by app, sender and text. */
class HistoryPage(private val a: Activity, root: View) {
    private val search = root.findViewById<EditText>(R.id.history_search)
    private val empty = root.findViewById<TextView>(R.id.history_empty)
    private val adapter = EntryAdapter(a, showApp = true, onOpen = { Inbox.open(a, it) }, onClear = null)

    init {
        root.findViewById<ListView>(R.id.history_list).apply {
            adapter = this@HistoryPage.adapter
            emptyView = empty
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = render()
        })
    }

    fun render() {
        val q = search.text.toString().trim()
        val all = Db.get(a).history()
        adapter.items = if (q.isEmpty()) all else all.filter {
            it.title.contains(q, true) || it.body.contains(q, true) || Inbox.label(a, it.pkg).contains(q, true)
        }
        empty.setText(if (q.isEmpty()) R.string.history_empty else R.string.no_match)
    }
}
