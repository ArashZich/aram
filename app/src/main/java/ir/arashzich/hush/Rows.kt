package ir.arashzich.hush

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.abs

/** "now", "14:05" today, "Mon" this week, "3 Nov" before. */
fun whenText(c: Context, time: Long): String {
    val now = System.currentTimeMillis()
    return when {
        now - time < DateUtils.MINUTE_IN_MILLIS -> c.getString(R.string.just_now)
        DateUtils.isToday(time) -> DateUtils.formatDateTime(c, time, DateUtils.FORMAT_SHOW_TIME)
        now - time < 6 * DateUtils.DAY_IN_MILLIS ->
            DateUtils.formatDateTime(c, time, DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY)
        else -> DateUtils.formatDateTime(c, time, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)
    }
}

/** "14:05" today, "Tue 09:00" later. */
private fun Context.whenAheadOf(time: Long): String = DateUtils.formatDateTime(this, time,
    DateUtils.FORMAT_SHOW_TIME or if (DateUtils.isToday(time)) 0 else DateUtils.FORMAT_SHOW_WEEKDAY)

/**
 * Notification cards for a list: the sender's photo or a soft letter, title, time, full text.
 * Tap opens; swiping a card sideways clears it (inbox only); a long press offers copy and clear.
 */
class EntryAdapter(
    private val c: Context,
    private val showApp: Boolean,
    private val onOpen: (Entry) -> Unit,
    private val onClear: ((Entry) -> Unit)?,
) : BaseAdapter() {
    var items: List<Entry> = emptyList()
        set(v) { field = v; notifyDataSetChanged() }

    private val palette = listOf(R.color.av1, R.color.av2, R.color.av3, R.color.av4, R.color.av5)
    private val slop = ViewConfiguration.get(c).scaledTouchSlop

    override fun getCount() = items.size
    override fun getItem(position: Int) = items[position]
    override fun getItemId(position: Int) = items[position].id

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val v = convertView ?: LayoutInflater.from(c).inflate(R.layout.entry_row, parent, false)
        val e = items[position]
        val card = v.findViewById<View>(R.id.card)
        card.translationX = 0f
        card.alpha = 1f

        v.findViewById<TextView>(R.id.app).apply {
            visibility = if (showApp) View.VISIBLE else View.GONE
            if (showApp) {
                text = if (e.cleared) "${Inbox.label(c, e.pkg)} · ${c.getString(R.string.cleared)}" else Inbox.label(c, e.pkg)
            }
        }
        v.findViewById<TextView>(R.id.title).text = e.title.ifBlank { Inbox.label(c, e.pkg) }
        v.findViewById<TextView>(R.id.time).text = whenText(c, e.time)
        v.findViewById<TextView>(R.id.body).apply {
            text = e.body
            visibility = if (e.body.isBlank()) View.GONE else View.VISIBLE
        }
        v.findViewById<TextView>(R.id.code).apply {
            val code = Rules.otp("${e.title}\n${e.body}")
            visibility = if (code == null) View.GONE else View.VISIBLE
            if (code != null) {
                text = c.getString(R.string.copy_code, code)
                setOnClickListener { copy(e.title, code) }
            }
        }
        v.findViewById<View>(R.id.dot).visibility = if (e.read) View.GONE else View.VISIBLE

        val avatar = v.findViewById<ImageView>(R.id.avatar)
        val letter = v.findViewById<TextView>(R.id.letter)
        val picture = Inbox.loadPicture(c, e.id)
        if (picture != null) {
            avatar.setImageBitmap(picture)
            avatar.clipToOutline = true
            avatar.background = circle(R.color.surface2)
            letter.visibility = View.GONE
        } else {
            val name = e.title.ifBlank { Inbox.label(c, e.pkg) }
            avatar.setImageDrawable(null)
            avatar.background = circle(palette[Math.floorMod(name.hashCode(), palette.size)])
            letter.text = name.trim().take(1).uppercase()
            letter.visibility = View.VISIBLE
        }

        card.setOnClickListener { onOpen(e) }
        card.setOnLongClickListener { menu(card, e); true }
        if (onClear != null) swipe(card, parent, e) else card.setOnTouchListener(null)
        return v
    }

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(c.getColor(color))
    }

    private fun menu(anchor: View, e: Entry) = PopupMenu(c, anchor).apply {
        menu.add(0, 1, 1, R.string.copy_text)
        val later = menu.addSubMenu(0, 3, 2, R.string.remind_later)
        val now = LocalDateTime.now()
        listOf(Later.HOUR to R.string.later_hour, Later.EVENING to R.string.later_evening,
            Later.MORNING to R.string.later_morning).forEach { (l, name) ->
            if (Rules.later(now, l) != null) later.add(0, 10 + l.ordinal, l.ordinal, name)
        }
        if (onClear != null && !e.cleared) menu.add(0, 2, 3, R.string.clear)
        setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> copy(e.title, listOf(e.title, e.body).filter { s -> s.isNotBlank() }.joinToString("\n"))
                2 -> onClear?.invoke(e)
                3 -> Unit
                else -> Rules.later(LocalDateTime.now(), Later.entries[it.itemId - 10])?.let { at ->
                    val time = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    Reminder.set(c, e, time)
                    Toast.makeText(c, c.getString(R.string.remind_set, c.whenAheadOf(time)), Toast.LENGTH_SHORT).show()
                }
            }
            true
        }
    }.show()

    private fun copy(label: String, text: String) {
        c.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(c, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    // Sideways past a third of the width clears; anything shorter springs back. Vertical moves stay the list's.
    @SuppressLint("ClickableViewAccessibility")
    private fun swipe(card: View, list: ViewGroup, e: Entry) {
        var downX = 0f
        var downY = 0f
        var dragging = false
        card.setOnTouchListener { view, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = ev.rawX; downY = ev.rawY; dragging = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    if (!dragging && abs(dx) > slop && abs(dx) > abs(ev.rawY - downY)) {
                        dragging = true
                        list.requestDisallowInterceptTouchEvent(true)
                        view.isPressed = false
                        view.cancelLongPress()
                    }
                    if (dragging) {
                        view.translationX = dx
                        view.alpha = 1f - minOf(1f, abs(dx) / view.width)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                    val dx = view.translationX
                    if (ev.actionMasked == MotionEvent.ACTION_UP && abs(dx) > view.width / 3f) {
                        view.animate().translationX(if (dx > 0) view.width.toFloat() else -view.width.toFloat())
                            .alpha(0f).setDuration(160).withEndAction { onClear?.invoke(e) }.start()
                    } else {
                        view.animate().translationX(0f).alpha(1f).setDuration(160).start()
                    }
                    dragging = false
                    return@setOnTouchListener true
                }
            }
            dragging
        }
    }
}
