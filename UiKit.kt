package dev.codesurgen.ruta

import android.content.Context
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * Ruta UI kit, generic part: the Screen contract, units, formatting and layout helpers.
 * Framework views only (no AppCompat/androidx). Everything here is main-thread UI.
 *
 * The design system lives next to it: Theme.kt (dynamic colour roles, type scale, shapes,
 * spacing), Icons.kt (Material Symbols), Components.kt (M3 Expressive components), Motion.kt
 * (springs, fade-through) and Host.kt (shell ↔ screen contract).
 */

/**
 * One bottom-bar destination. MainActivity owns [view] (adds it once, toggles visibility) and calls:
 *  - [onShow] each time the tab is selected (load data here; also after the app returns to front);
 *  - [render] about once a second while visible and on live changes (keep it cheap, no I/O);
 *  - [onHide] when another tab is selected or the app goes to the background;
 *  - [onBack] on system back while visible: return true when handled (e.g. a detail view closed),
 *    false to let MainActivity go back to Home;
 *  - [dispose] when the activity is destroyed or the shell is rebuilt (cancel coroutines here).
 */
interface Screen {
    val view: View
    fun onShow() {}
    fun onHide() {}
    fun render(now: Long) {}
    fun onBack(): Boolean = false
    fun dispose() {}
}

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

// ------------------------------------------------------------------ units

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpf(value: Float): Float = value * resources.displayMetrics.density
fun Context.spf(value: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)
fun View.dp(value: Int): Int = context.dp(value)
fun View.dpf(value: Float): Float = context.dpf(value)
fun View.spf(value: Float): Float = context.spf(value)

// ------------------------------------------------------------------ formatting

object Fmt {
    fun percent(value: Double): String = if (value % 1.0 == 0.0) "${value.toInt()}%" else "${number(value)}%"


    /** "850 m" below 1 km, "4.2 km" up to 100 km, then whole km. */
    fun distance(meters: Double): String = when {
        meters < 1000 -> "${meters.coerceAtLeast(0.0).roundToInt()} m"
        meters < 100_000 -> "${number(meters / 1000)} km"
        else -> "${(meters / 1000).roundToInt()} km"
    }

    fun speed(kmh: Double): String = "${kmh.coerceAtLeast(0.0).roundToInt()} km/h"

    /** Number with at most [decimals] decimals and no trailing ".0" ("61", "28.7"). */
    fun decimal(value: Double, decimals: Int = 1): String {
        val text = String.format(Locale.US, "%.${decimals}f", value)
        return if (decimals > 0) text.trimEnd('0').trimEnd('.') else text
    }

    /** "28.7°" (tile value; the unit is implied by the icon). */
    fun degrees(celsius: Double): String = "${decimal(celsius)}°"

    /** "45 s", "12 min", "1 h 05 min". */
    fun duration(seconds: Long): String = RideMath.formatDuration(seconds)

    /** "0:42", "12:05", "1:02:09" for running timers. */
    fun timer(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60)
        else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    /** Wall-clock time in the phone's 12/24-hour setting. */
    fun clock(context: Context, time: Long): String = DateFormat.getTimeFormat(context).format(Date(time))

    fun clock(context: Context, hour24: Int, minute: Int): String {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour24.coerceIn(0, 23)); set(Calendar.MINUTE, minute.coerceIn(0, 59))
        }
        return DateFormat.getTimeFormat(context).format(calendar.time)
    }

    /** "Mon 16 Nov". */
    fun date(time: Long): String = SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(time))

    fun ago(context: Context, time: Long, now: Long): String {
        val elapsed = (now - time).coerceAtLeast(0)
        return when {
            elapsed < 2_000 -> "just now"
            elapsed < 60_000 -> "${elapsed / 1_000} s ago"
            elapsed < 3_600_000 -> "${elapsed / 60_000} min ago"
            elapsed < 86_400_000 -> "at ${clock(context, time)}"
            else -> "${date(time)}, ${clock(context, time)}"
        }
    }

}

fun Context.toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

// ------------------------------------------------------------------ layout

fun Context.column(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
fun Context.row(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

/** Full-width child of a vertical LinearLayout with [top] dp above it. */
fun Context.params(top: Int = 0, width: Int = MATCH, height: Int = WRAP): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(width, height).apply { topMargin = dp(top) }

/** Weighted child of a horizontal LinearLayout with [start] dp before it. */
fun Context.weighted(weight: Float = 1f, start: Int = 0, height: Int = WRAP): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(0, height, weight).apply { marginStart = dp(start) }

/**
 * Standard scrolling screen body: one centred column, at most 640 dp wide, 16 dp side margins.
 * [add] appends full-width children with a 12 dp gap (cards) by default; use 24 for sections.
 */
class ScrollPage(context: Context) : ScrollView(context) {
    val column = MaxWidthLayout(context, context.dp(Spacing.MAX_WIDTH)).apply {
        setPadding(dp(Spacing.MARGIN), dp(Spacing.XS), dp(Spacing.MARGIN), dp(Spacing.XXL))
    }

    init {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        setEdgeEffectColor(Theme.primary)
        addView(column, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER_HORIZONTAL))
    }

    /** Appends [view] full width with [top] dp spacing. */
    fun <T : View> add(view: T, top: Int = Spacing.GAP): T { column.addView(view, context.params(top)); return view }

    /** Smoothly scrolls back to the top (e.g. on navigation-bar reselect). */
    fun scrollToTop() = smoothScrollTo(0, 0)
}

// ------------------------------------------------------------------ text

/** Avoids a relayout when a 1 s tick re-renders an unchanged value. */
fun TextView.show(value: CharSequence) { if (!TextUtils.equals(text, value)) text = value }
fun TextView.tint(color: Int) { if (currentTextColor != color) setTextColor(color) }
fun TextView.tabular(): TextView { fontFeatureSettings = "tnum"; return this }
fun View.visible(show: Boolean) { val next = if (show) View.VISIBLE else View.GONE; if (visibility != next) visibility = next }

/** Primary-tinted selection highlight and handles for selectable text. */
fun TextView.tintSelection() {
    highlightColor = alpha(Theme.primary, 0.35f)
    fun tinted(drawable: Drawable?) = drawable?.mutate()?.apply { setTint(Theme.primary) }
    tinted(textSelectHandle)?.let { setTextSelectHandle(it) }
    tinted(textSelectHandleLeft)?.let { setTextSelectHandleLeft(it) }
    tinted(textSelectHandleRight)?.let { setTextSelectHandleRight(it) }
}

// ------------------------------------------------------------------ custom views

/** Lays children out left to right, wrapping onto new lines. */
class FlowRow(context: Context, private val gap: Int) : ViewGroup(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = max(0, MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight)
        val mode = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) MeasureSpec.UNSPECIFIED else MeasureSpec.AT_MOST
        val childWidth = MeasureSpec.makeMeasureSpec(available, mode)
        val childHeight = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        var x = 0; var y = 0; var line = 0; var widest = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            child.measure(childWidth, childHeight)
            if (x > 0 && x + child.measuredWidth > available) { x = 0; y += line + gap; line = 0 }
            x += child.measuredWidth + gap
            line = max(line, child.measuredHeight)
            widest = max(widest, x - gap)
        }
        setMeasuredDimension(
            resolveSize(widest + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(y + line + paddingTop + paddingBottom, heightMeasureSpec)
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val available = r - l - paddingLeft - paddingRight
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        var x = 0; var y = 0; var line = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val w = child.measuredWidth; val h = child.measuredHeight
            if (x > 0 && x + w > available) { x = 0; y += line + gap; line = 0 }
            val left = if (rtl) r - l - paddingRight - x - w else paddingLeft + x
            child.layout(left, paddingTop + y, left + w, paddingTop + y + h)
            x += w + gap
            line = max(line, h)
        }
    }
}

/** Caps content width on tablets and in landscape so cards do not stretch edge to edge. */
class MaxWidthLayout(context: Context, private val maxWidth: Int, vertical: Boolean = true) : LinearLayout(context) {
    init { orientation = if (vertical) VERTICAL else HORIZONTAL }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = MeasureSpec.getSize(widthMeasureSpec)
        val capped = MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED && size > maxWidth
        super.onMeasure(if (capped) MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.EXACTLY) else widthMeasureSpec, heightMeasureSpec)
    }
}
