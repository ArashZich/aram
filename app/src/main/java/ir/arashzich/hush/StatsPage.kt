package ir.arashzich.hush

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.icu.text.DateFormatSymbols
import android.icu.text.SimpleDateFormat
import android.icu.util.Calendar
import android.icu.util.ULocale
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * The Stats tab: a month whose days are as deep blue as they were busy (Persian calendar in Persian), the
 * chosen day's apps and notifications, and the last seven days' noisiest apps with a one-tap "make silent".
 */
class StatsPage(private val a: Activity, private val root: View) {
    private val store = Store(a)
    private val zone = ZoneId.systemDefault()
    private val locale = Locale.getDefault()
    private val uloc = if (locale.language == "fa") ULocale("fa_IR@calendar=persian") else ULocale.forLocale(locale)
    private var month = 0  // months back from this one
    private var picked = LocalDate.now().toEpochDay()
    private val adapter = EntryAdapter(a, showApp = true, onOpen = { Inbox.open(a, it) }, onClear = null)

    init {
        root.findViewById<View>(R.id.month_prev).setOnClickListener { month++; render() }
        root.findViewById<View>(R.id.month_next).setOnClickListener { if (month > 0) { month--; render() } }
        weekdays()
    }

    fun render() {
        calendar()
        day()
        week()
    }

    private fun num(n: Int) = String.format(locale, "%d", n)

    private fun cal() = Calendar.getInstance(uloc).apply {
        add(Calendar.MONTH, -month)
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 12)
    }

    private fun epochDay(c: Calendar) = Instant.ofEpochMilli(c.timeInMillis).atZone(zone).toLocalDate().toEpochDay()

    private fun weekdays() {
        val row = root.findViewById<LinearLayout>(R.id.weekdays)
        val names = DateFormatSymbols.getInstance(uloc).getWeekdays(DateFormatSymbols.FORMAT, DateFormatSymbols.NARROW)
        val first = Calendar.getInstance(uloc).firstDayOfWeek
        (0 until 7).forEach { i ->
            row.addView(TextView(a).apply {
                text = names[(first - 1 + i) % 7 + 1]
                gravity = Gravity.CENTER
                textSize = 12f
                setTextColor(a.getColor(R.color.ink_soft))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
    }

    private fun calendar() {
        val c = cal()
        root.findViewById<TextView>(R.id.month).text = SimpleDateFormat("LLLL y", uloc).format(c.time)
        root.findViewById<View>(R.id.month_next).alpha = if (month > 0) 1f else 0.3f
        val firstDay = epochDay(c)
        val days = c.getActualMaximum(Calendar.DAY_OF_MONTH)
        val lead = (c.get(Calendar.DAY_OF_WEEK) - c.firstDayOfWeek + 7) % 7
        val totals = Db.get(a).dayTotals(firstDay, firstDay + days - 1)
        val max = totals.values.maxOrNull() ?: 0
        val today = LocalDate.now().toEpochDay()

        val grid = root.findViewById<LinearLayout>(R.id.cal)
        grid.removeAllViews()
        Rules.grid(lead, days).chunked(7).forEach { week ->
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            week.forEach { d ->
                val cell = TextView(a).apply {
                    gravity = Gravity.CENTER
                    textSize = 14f
                    layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }
                }
                if (d != null) {
                    val day = firstDay + d - 1
                    val n = totals[day] ?: 0
                    val shade = Rules.shade(n, max)
                    cell.text = num(d)
                    cell.setTextColor(a.getColor(if (shade >= 3) R.color.on_accent else R.color.ink))
                    cell.background = GradientDrawable().apply {
                        cornerRadius = dp(10).toFloat()
                        setColor(if (shade == 0) a.getColor(R.color.surface2) else tint(shade))
                        when (day) {
                            picked -> setStroke(dp(2), a.getColor(R.color.ink))
                            today -> setStroke(dp(2), a.getColor(R.color.accent))
                        }
                    }
                    if (day > today) cell.alpha = 0.35f
                    else cell.setOnClickListener { picked = day; calendar(); day() }
                    cell.contentDescription = "${num(d)}: ${num(n)}"
                }
                row.addView(cell)
            }
            grid.addView(row)
        }
    }

    private fun tint(shade: Int): Int {
        val alpha = intArrayOf(0, 60, 110, 175, 240)[shade]
        return (a.getColor(R.color.accent) and 0x00FFFFFF) or (alpha shl 24)
    }

    private fun day() {
        val date = LocalDate.ofEpochDay(picked)
        val apps = Db.get(a).dayApps(picked, picked)
        val total = apps.sumOf { it.second }
        val dayName = SimpleDateFormat("EEEE d MMMM", uloc).format(java.util.Date(date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()))
        root.findViewById<TextView>(R.id.day_title).text =
            if (total == 0) a.getString(R.string.day_none, dayName) else a.getString(R.string.day_count, dayName, num(total))
        appRows(root.findViewById(R.id.day_apps), apps.take(6), silence = emptySet())

        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        adapter.items = Db.get(a).between(start, end)
        val list = root.findViewById<LinearLayout>(R.id.day_items)
        list.removeAllViews()
        (0 until adapter.count).forEach { list.addView(adapter.getView(it, null, list)) }
        if (total > 0 && adapter.count == 0) {
            list.addView(TextView(a).apply {
                setText(R.string.day_old)
                textSize = 13f
                setTextColor(a.getColor(R.color.ink_soft))
                setPadding(dp(20), dp(10), dp(20), dp(4))
            })
        }
    }

    private fun week() {
        val today = LocalDate.now().toEpochDay()
        val apps = Db.get(a).dayApps(today - 6, today)
        root.findViewById<TextView>(R.id.week_total).text = a.getString(R.string.week_total, num(apps.sumOf { it.second }))
        appRows(root.findViewById(R.id.week_apps), apps.take(6), Rules.noisy(apps, store::level))
    }

    // An app per row: icon, name, count, and for a noisy one a "make silent" chip.
    private fun appRows(list: LinearLayout, apps: List<Pair<String, Int>>, silence: Set<String>) {
        list.removeAllViews()
        apps.forEach { (pkg, n) ->
            val row = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            row.addView(ImageView(a).apply {
                setImageDrawable(Inbox.icon(a, pkg))
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(10) }
            })
            row.addView(TextView(a).apply {
                text = Inbox.label(a, pkg)
                textSize = 15f
                setTextColor(a.getColor(R.color.ink))
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (pkg in silence) {
                row.addView((a.layoutInflater.inflate(R.layout.chip, row, false) as TextView).apply {
                    setText(R.string.make_silent)
                    setOnClickListener {
                        store.setLevel(pkg, Level.SILENT)
                        Toast.makeText(a, a.getString(R.string.made_silent, Inbox.label(a, pkg)), Toast.LENGTH_SHORT).show()
                        week()
                    }
                })
            }
            row.addView(TextView(a).apply {
                text = num(n)
                textSize = 14f
                setTextColor(a.getColor(R.color.ink_soft))
                setPadding(dp(8), 0, 0, 0)
            })
            row.setOnClickListener {
                a.startActivity(android.content.Intent(a, AppActivity::class.java).putExtra(AppActivity.PKG, pkg))
            }
            list.addView(row)
        }
    }

    private fun dp(v: Int) = (v * a.resources.displayMetrics.density).toInt()
}
