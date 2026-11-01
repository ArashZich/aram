package ir.arashzich.hush

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.min

/**
 * Hush's own floating card, drawn over other apps. One at a time; a newer one replaces it.
 * Tap opens the notification, swipe up dismisses, holding a finger on it keeps it.
 */
object Popup {
    private val main = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var shown: View? = null
    private var shownKey: String? = null
    private val timeout = Runnable { hide() }

    @SuppressLint("ClickableViewAccessibility")
    fun show(ctx: Context, key: String, app: CharSequence, title: String, body: String, icon: Drawable?,
             seconds: Int, open: () -> Unit) {
        if (!Gate.canDraw(ctx)) return
        // The application context: the card must not hold on to an activity.
        val c = ctx.applicationContext
        val wm = c.getSystemService(WindowManager::class.java).also { this.wm = it }
        shown?.let { old -> runCatching { wm.removeView(old) } }

        val v = LayoutInflater.from(c).inflate(R.layout.popup, null)
        v.findViewById<TextView>(R.id.app).text = c.getString(R.string.via_hush, app)
        v.findViewById<TextView>(R.id.title).apply { text = title; visibility = if (title.isEmpty()) View.GONE else View.VISIBLE }
        v.findViewById<TextView>(R.id.body).apply { text = body; visibility = if (body.isEmpty()) View.GONE else View.VISIBLE }
        v.findViewById<ImageView>(R.id.icon).setImageDrawable(icon)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP }

        val slop = ViewConfiguration.get(c).scaledTouchSlop
        var downY = 0f
        val card = v.findViewById<View>(R.id.card)
        card.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = e.rawY; main.removeCallbacks(timeout) }
                MotionEvent.ACTION_MOVE -> view.translationY = min(0f, e.rawY - downY)
                MotionEvent.ACTION_UP -> {
                    val dy = e.rawY - downY
                    when {
                        abs(dy) < slop -> { view.performClick(); hide(); open() }
                        dy < -view.height / 4f -> hide()
                        else -> { view.animate().translationY(0f).setDuration(150).start(); later(seconds) }
                    }
                }
                MotionEvent.ACTION_CANCEL -> { view.translationY = 0f; later(seconds) }
            }
            true
        }

        runCatching { wm.addView(v, lp) }.onFailure { return }
        shown = v
        shownKey = key
        card.alpha = 0f
        card.translationY = -card.resources.displayMetrics.density * 24
        card.animate().alpha(1f).translationY(0f).setDuration(220).start()
        later(seconds)
    }

    private fun later(seconds: Int) {
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, seconds * 1000L)
    }

    /** Hides the card; with [key], only when it shows that notification. */
    fun hide(key: String? = null) {
        if (key != null && key != shownKey) return
        val v = shown ?: return
        val wm = wm ?: return
        shown = null
        shownKey = null
        main.removeCallbacks(timeout)
        val card = v.findViewById<View>(R.id.card)
        card.animate().alpha(0f).translationY(-card.height / 2f).setDuration(180)
            .withEndAction { runCatching { wm.removeView(v) } }.start()
    }
}
