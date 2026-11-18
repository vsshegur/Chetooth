package dev.codesurgen.ruta

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.LinearLayout
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Canvas charts for Insights and Rides (M3 Expressive styling from Theme roles): framework views,
 * main thread only, allocation-free onDraw. Series are [BucketPoint]s on a time axis (time = bucket
 * midpoint); missing buckets are drawn as gaps, never bridged. Lines are smooth (monotone cubic, no
 * overshoot), areas fade out downwards, y labels sit at the end, gridlines are hairlines in
 * outlineVariant. Loading = pulsing skeleton, empty = icon + one short line. First data wipes in.
 * Also here: [Amount]/[Amounts] (value + unit pairs), [InlineStat] (icon + value + unit) and
 * [StatFlow], shared by the Rides and Insights screens.
 */

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 3_600_000L
private const val DAY_MS = 86_400_000L
private const val TIP_HOLD_MS = 2_500L
private const val REVEAL_MS = 700L
private const val PULSE_MS = 1_400L

/** Insights periods: span, history buckets (15 min / 1 h / 6 h) and bar slots (hours / days). */
enum class ChartRange(val label: String, val phrase: String, val spanMs: Long, val buckets: Int) {
    DAY("24h", "the last 24 hours", DAY_MS, 96),
    WEEK("7d", "the last 7 days", 7 * DAY_MS, 168),
    MONTH("30d", "the last 30 days", 30 * DAY_MS, 120);

    /** Spoken segment name: "Last 24 hours". */
    val spoken: String get() = phrase.removePrefix("the ").replaceFirstChar { it.uppercase() }

    /** Width of one [TelemetryStore.history] bucket for this range. */
    val bucketMs: Long get() = RideMath.bucketWidth(0, spanMs, buckets)

    /** Bar slot length: hours for 24 h, days otherwise. */
    val slotMs: Long get() = if (this == DAY) HOUR_MS else DAY_MS

    /** Local hour (24 h) or midnight (7 d, 30 d) slot starts covering [now − span, now], oldest first. */
    fun slots(now: Long): List<Long> {
        val field = if (this == DAY) Calendar.HOUR_OF_DAY else Calendar.DAY_OF_MONTH
        val cal = Calendar.getInstance().apply {
            timeInMillis = now - spanMs
            set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (field == Calendar.DAY_OF_MONTH) set(Calendar.HOUR_OF_DAY, 0)
        }
        return buildList { while (cal.timeInMillis <= now) { add(cal.timeInMillis); cal.add(field, 1) } }
    }
}

/** Local midnight at or before [time]. */
fun localDayStart(time: Long): Long = Calendar.getInstance().apply {
    timeInMillis = time
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

/** "82%", "82.5%": charge and health rounded to 0.1 point. */
fun roundedPercent(value: Double): String = Fmt.percent((value * 10).roundToLong() / 10.0)

// ------------------------------------------------------------------ value + unit

/** A number and its unit, shown big + small ("61" + "km", DESIGN addendum A.2). */
data class Amount(val value: String, val unit: String = "") {
    /** "61 km" for spoken text and single-line places. */
    val text: String get() = if (unit.isEmpty()) value else "$value $unit"
}

/** Value/unit splitting for the screens' big tabular numbers. */
object Amounts {
    const val NONE = "—"

    /** "850" m below 1 km, "4.2" km (one decimal, stable width) up to 100 km, then whole km. */
    fun distance(meters: Double): Amount = when {
        !meters.isFinite() -> Amount(NONE, "km")
        meters < 1000 -> Amount(meters.coerceAtLeast(0.0).roundToInt().toString(), "m")
        meters < 100_000 -> Amount(number(meters / 1000, 1), "km")
        else -> Amount((meters / 1000).roundToInt().toString(), "km")
    }

    fun speed(kmh: Double): Amount = Amount(if (kmh.isFinite()) kmh.coerceAtLeast(0.0).roundToInt().toString() else NONE, "km/h")

    /** "45" s, "23" min, "1:05" h. */
    fun duration(seconds: Long): Amount = when {
        seconds < 60 -> Amount(seconds.coerceAtLeast(0).toString(), "s")
        seconds < 3600 -> Amount((seconds / 60).toString(), "min")
        else -> Amount(String.format(Locale.US, "%d:%02d", seconds / 3600, seconds % 3600 / 60), "h")
    }

    /** "57", "57.4" (0.1 point) + "%". */
    fun percent(value: Double): Amount =
        Amount(if (value.isFinite()) Fmt.decimal((value * 10).roundToLong() / 10.0) else NONE, "%")

    /** "1.3" km/% or "—". */
    fun efficiency(kmPerPercent: Double?): Amount =
        Amount(kmPerPercent?.takeIf { it.isFinite() && it > 0 }?.let { number(it, 1) } ?: NONE, "km/%")

    /** The range model's km per 1 %, as Home and every sheet show it: two decimals ("1.12"). Per-ride values use [efficiency]. */
    fun efficiencyModel(kmPerPercent: Double): Amount =
        Amount(kmPerPercent.takeIf { it.isFinite() && it > 0 }?.let { Fmt.decimal(it, 2) } ?: NONE, "km/%")

    fun temperature(celsius: Double): Amount = Amount(if (celsius.isFinite()) Fmt.decimal(celsius) else NONE, "°C")

    fun count(value: Int, unit: String = ""): Amount = Amount(value.toString(), unit)
}

/** Haptic tick for scrubbing and segmented changes (lighter on API 34+). */
internal fun View.chartTick() {
    performHapticFeedback(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK)
}

// ------------------------------------------------------------------ inline stats

/**
 * Inline icon stat for rows and card headers: 16 dp icon (onSurfaceVariant), 4 dp gap, tabular value
 * (Label L, onSurface) and a smaller baseline-aligned unit (Label M, onSurfaceVariant). Announced as
 * "[description] 4.2 km"; long-press shows [description] unless [tooltip] is false (required inside
 * clickable containers: Android gives a view with a tooltip every touch, so the row would never get the tap).
 */
class InlineStat(context: Context, icon: String, private val description: String, value: Amount? = null, tooltip: Boolean = true) : LinearLayout(context) {
    private val iconView = IconView(context, icon, tint = Theme.onSurfaceVariant, iconSizeDp = 16)
    private val valueView = context.singleLine("", Typography.labelLarge, Theme.onSurface).tabular()
    private val unitView = context.singleLine("", Typography.labelMedium, Theme.onSurfaceVariant)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(iconView, LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(Spacing.XS) })
        // Value and unit share a baseline (TOP gravity keeps LinearLayout's baseline alignment on).
        val pair = LinearLayout(context).apply {
            orientation = HORIZONTAL
            isBaselineAligned = true
            addView(valueView, LayoutParams(WRAP, WRAP))
            addView(unitView, LayoutParams(WRAP, WRAP).apply { marginStart = dp(Spacing.XXS) })
        }
        addView(pair, LayoutParams(WRAP, WRAP))
        for (child in listOf(iconView, valueView, unitView, pair)) child.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        // A view with a tooltip keeps every touch: inside a clickable row/card pass tooltip = false.
        if (tooltip) tooltipText = description
        set(value ?: Amount(Amounts.NONE))
    }

    fun set(amount: Amount) {
        valueView.show(amount.value)
        unitView.show(amount.unit)
        unitView.visible(amount.unit.isNotEmpty())
        val spoken = "$description ${amount.text}"
        if (contentDescription != spoken) contentDescription = spoken
    }

    fun setIcon(name: String, filled: Boolean = false) = iconView.setIcon(name, filled)

    /** Icon colour (e.g. Theme.charging for a live session); text stays neutral. */
    fun setIconTint(color: Int) { iconView.tint = color }

    /** Value colour, e.g. Theme.primary for a live value. */
    fun setValueColor(color: Int) = valueView.tint(color)
}

/**
 * Wrapping row with separate horizontal / vertical gaps (dp), for [InlineStat]s and chips. With
 * [maxLines] the children that would start a further line are left out (not drawn), so a stats row
 * never wraps into uneven heights (e.g. 1 for the ride list).
 */
class StatFlow(context: Context, hGapDp: Int = Spacing.M, vGapDp: Int = Spacing.XS, private val maxLines: Int = Int.MAX_VALUE) : ViewGroup(context) {
    private val hGap = context.dp(hGapDp)
    private val vGap = context.dp(vGapDp)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val unbounded = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED
        val available = if (unbounded) Int.MAX_VALUE else max(0, MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight)
        val childSpec = if (unbounded) MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED) else MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST)
        val heightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        var x = 0; var y = 0; var line = 0; var widest = 0; var lines = 1
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            child.measure(childSpec, heightSpec)
            if (x > 0 && x + child.measuredWidth > available) {
                if (lines >= maxLines) break
                x = 0; y += line + vGap; line = 0; lines++
            }
            x += child.measuredWidth + hGap
            line = max(line, child.measuredHeight)
            widest = max(widest, x - hGap)
        }
        setMeasuredDimension(
            resolveSize(widest + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(y + line + paddingTop + paddingBottom, heightMeasureSpec)
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val available = r - l - paddingLeft - paddingRight
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        var y = 0
        var lineStart = 0
        var lines = 0
        while (lineStart < childCount) {
            if (lines >= maxLines) {
                // Left out: nothing on screen and no stale position.
                for (k in lineStart until childCount) getChildAt(k).layout(0, 0, 0, 0)
                break
            }
            lines++
            // One line = [lineStart, end); its children are centred on the line's height.
            var x = 0
            var line = 0
            var end = lineStart
            while (end < childCount) {
                val child = getChildAt(end)
                if (child.visibility != GONE) {
                    if (x > 0 && x + child.measuredWidth > available) break
                    x += child.measuredWidth + hGap
                    line = max(line, child.measuredHeight)
                }
                end++
            }
            x = 0
            for (k in lineStart until end) {
                val child = getChildAt(k)
                if (child.visibility == GONE) continue
                val w = child.measuredWidth; val h = child.measuredHeight
                val left = if (rtl) r - l - paddingRight - x - w else paddingLeft + x
                val top = paddingTop + y + (line - h) / 2
                child.layout(left, top, left + w, top + h)
                x += w + hGap
            }
            if (line > 0) y += line + vGap
            lineStart = end
        }
    }
}

// ------------------------------------------------------------------ time axes

/** How a chart places and words its time axis. */
interface TimeAxis {
    /** Tick boundaries; the first may lie before [start] when labels are centred on spans. */
    fun ticks(start: Long, end: Long): List<Long>
    /** 0: each label sits on its tick. Otherwise it is centred on the visible part of [tick, tick + span]. */
    val labelSpanMs: Long get() = 0
    fun label(time: Long): String
    /** Tooltip heading for a value at [time]. */
    fun detail(time: Long): String
    /** Tooltip heading for a bar covering [start, start + lengthMs). */
    fun slot(start: Long, lengthMs: Long): String = detail(start + lengthMs / 2)
}

/** Wall-clock axis for [ChartRange]s: 24 h ticks every 6 h; 7 d one label per day; 30 d one per week. */
class ClockAxis(private val context: Context, private val range: ChartRange) : TimeAxis {
    private val locale = Locale.getDefault()
    private val hour = SimpleDateFormat(if (DateFormat.is24HourFormat(context)) "HH:mm" else "h a", locale)
    private val weekday = SimpleDateFormat("EEE", locale)
    private val dayMonth = SimpleDateFormat("d MMM", locale)

    override val labelSpanMs: Long get() = if (range == ChartRange.DAY) 0 else DAY_MS

    override fun ticks(start: Long, end: Long): List<Long> {
        val daily = range != ChartRange.DAY
        val cal = Calendar.getInstance().apply {
            timeInMillis = start
            set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            set(Calendar.HOUR_OF_DAY, if (daily) 0 else get(Calendar.HOUR_OF_DAY) / 6 * 6)
        }
        val field = if (daily) Calendar.DAY_OF_MONTH else Calendar.HOUR_OF_DAY
        val step = when (range) { ChartRange.DAY -> 6; ChartRange.WEEK -> 1; ChartRange.MONTH -> 7 }
        return buildList { while (cal.timeInMillis <= end) { add(cal.timeInMillis); cal.add(field, step) } }
    }

    override fun label(time: Long): String = when (range) {
        ChartRange.DAY -> hour.format(Date(time))
        ChartRange.WEEK -> weekday.format(Date(time))
        ChartRange.MONTH -> dayMonth.format(Date(time))
    }

    override fun detail(time: Long): String {
        val clock = Fmt.clock(context, time)
        return when (range) {
            ChartRange.DAY -> if (localDayStart(time) == localDayStart(System.currentTimeMillis())) clock else "${weekday.format(Date(time))} $clock"
            ChartRange.WEEK -> "${weekday.format(Date(time))} $clock"
            ChartRange.MONTH -> "${Fmt.date(time)}, $clock"
        }
    }

    override fun slot(start: Long, lengthMs: Long): String =
        if (lengthMs >= DAY_MS) Fmt.date(start) else "${Fmt.clock(context, start)}–${Fmt.clock(context, start + lengthMs)}"
}

/** Time since [origin] (a ride's start): "0", "5 min", "1 h", "1:30 h". */
class ElapsedAxis(private val context: Context, private val origin: Long) : TimeAxis {
    override fun ticks(start: Long, end: Long): List<Long> {
        val step = STEPS.firstOrNull { (end - start) / it <= 5 } ?: STEPS.last()
        var t = origin + max(0L, (start - origin + step - 1) / step) * step
        return buildList { while (t <= end) { add(t); t += step } }
    }

    override fun label(time: Long): String {
        val minutes = (time - origin) / MINUTE_MS
        return when {
            minutes <= 0 -> "0"
            minutes < 60 -> "$minutes min"
            minutes % 60 == 0L -> "${minutes / 60} h"
            else -> String.format(Locale.US, "%d:%02d h", minutes / 60, minutes % 60)
        }
    }

    override fun detail(time: Long): String = "${Fmt.clock(context, time)} · ${Fmt.timer((time - origin) / 1000)}"

    private companion object {
        val STEPS = longArrayOf(1, 2, 5, 10, 15, 20, 30, 60, 120, 180, 240, 360).map { it * MINUTE_MS }
    }
}

// ------------------------------------------------------------------ shared chart frame

/**
 * Frame shared by [LineChart] and [BarChart]: skeleton (loading) and empty states, y scale with "nice"
 * ticks labelled at the end, hairline gridlines, time labels, an optional legend and touch
 * scrubbing. Touch shows a tooltip at the nearest value with a light haptic tick per step; a
 * horizontal drag keeps scrubbing (the parent ScrollView is held off), a vertical drag scrolls the
 * page. The tooltip lingers briefly after the finger lifts. First data (and [animateNextData]) wipes in.
 */
abstract class TimeChart(context: Context) : View(context) {
    private enum class Phase { LOADING, EMPTY, DATA }

    /** Time axis wording; set before data. */
    var axis: TimeAxis = ElapsedAxis(context, 0)
    /** Tooltip and spoken value text. */
    var valueFormat: (Double) -> String = { number(it) }
    /** Appended to y tick numbers: "%", "°", " km". */
    var axisUnit = ""
    /** Prefixes positive ticks with "+" (differences). */
    var signed = false
    /** Fixed y range (e.g. 0..100 for charge); null fits the data. */
    var fixedRange: ClosedFloatingPointRange<Double>? = null
    /** Smallest fitted y span, so flat data is not magnified into noise. */
    var minSpan = 1.0
    /** A fitted range stays inside these bounds (e.g. 0..100 for percentages). */
    var limits: ClosedFloatingPointRange<Double>? = null
    /** The fitted range always contains 0 (speeds, bars). */
    var includeZero = false
    /** Fixed y tick step (e.g. 5 for a 90–100 % window); null picks a "nice" step. */
    var yStep: Double? = null
    /** Centres the y range on 0 (differences). */
    var symmetric = false
    /** Spoken name, e.g. "Charge, the last 7 days". */
    var title = ""
    /** Default height when the parent does not fix one. */
    var heightDp = 200
    /** Receives the scrubbed time while the tooltip shows, then null. */
    var onScrub: ((Long?) -> Unit)? = null
    /** Colour behind the chart: rings around dots are cut out of it. */
    var surfaceColor = Theme.surfaceContainer
    /** The next data set wipes in again (e.g. after the period changed). */
    var animateNextData = false

    protected var start = 0L; private set
    protected var end = 1L; private set
    protected var yMin = 0.0; private set
    protected var yMax = 1.0; private set
    protected val plot = RectF()
    /** Time of the value under the tooltip, if one shows. */
    protected val tipTime: Long? get() = tip?.time
    /** Draw a vertical cursor through the scrubbed value. */
    protected open val cursorLine = true
    /** Entrance progress 0..1 (lines wipe in from the start, bars grow). */
    protected var reveal = 1f; private set
    /** True: the frame clips the plot to the revealed width. False: the subclass uses [reveal] itself. */
    protected open val revealByClip = true

    /** Data is showing (not loading or empty). */
    val hasData: Boolean get() = phase == Phase.DATA

    private var phase = Phase.LOADING
    private var message = LOADING
    private var shownMessage: CharSequence = ""
    private var emptyIconName: String? = null
    private var emptyIcon: Drawable? = null
    private var tickStep = 1.0
    private var yTicks = DoubleArray(0)
    private var yLabels = emptyArray<String>()
    private var yLabelWidth = 0f
    private var xCenters = LongArray(0)
    private var xLabels = emptyArray<String>()
    private var xWidths = FloatArray(0)
    private var legendNames = emptyArray<String>()
    private var legendColors = IntArray(0)
    private var legendWidths = FloatArray(0)
    private var tip: Tip? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private val hideTip = Runnable { setTip(null) }
    private var revealAnimator: ValueAnimator? = null
    private val pulseOrigin = SystemClock.uptimeMillis()

    private val hairline = max(1f, dpf(0.75f))
    private val grid = Paint().apply { color = alpha(Theme.outlineVariant, 0.8f); strokeWidth = hairline }
    private val zeroLine = Paint().apply { color = Theme.outline; strokeWidth = max(1f, dpf(1f)) }
    private val axisText = Paint(Paint.ANTI_ALIAS_FLAG).applyStyle(context, Typography.labelSmall).tabular().apply { color = Theme.onSurfaceVariant }
    private val legendText = Paint(Paint.ANTI_ALIAS_FLAG).applyStyle(context, Typography.labelMedium).apply { color = Theme.onSurfaceVariant }
    private val messageText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        applyStyle(context, Typography.labelLarge); color = Theme.onSurfaceVariant; textAlign = Paint.Align.CENTER
    }
    private val cursor = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = alpha(Theme.onSurfaceVariant, 0.6f); strokeWidth = dpf(1f) }
    private val tipFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.surfaceContainerHighest }
    private val tipTitle = Paint(Paint.ANTI_ALIAS_FLAG).applyStyle(context, Typography.labelSmall).apply { color = Theme.onSurfaceVariant }
    private val tipText = Paint(Paint.ANTI_ALIAS_FLAG).applyStyle(context, Typography.labelLarge).tabular().apply { color = Theme.onSurface }
    private val skeleton = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dpf(3f); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        color = Theme.surfaceContainerHighest
    }
    private val skeletonPath = Path()
    private val circle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.surfaceContainerHigh }
    protected val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = LOADING
    }

    /** Values under the finger: [rows] carry a swatch colour and, for lines, the dot's y. */
    protected class Tip(val time: Long, val x: Float, val heading: String, val rows: List<TipRow>)
    protected class TipRow(val color: Int, val text: String, val y: Float?)

    protected abstract fun drawPlot(canvas: Canvas)
    protected abstract fun tipAt(time: Long): Tip?
    protected abstract fun describe(): String
    /** The plot area or y range changed: drop cached shaders/paths. */
    protected open fun onFrameChanged() {}

    /** Pulsing skeleton (no text). */
    fun showLoading() = showMessage(Phase.LOADING, LOADING, null)

    /** One short line under an [icon] (Material Symbols name), e.g. "No rides" + "route". */
    fun showEmpty(text: String = NO_DATA, icon: String = "monitoring") = showMessage(Phase.EMPTY, text, icon)

    private fun showMessage(next: Phase, text: String, icon: String?) {
        removeCallbacks(hideTip)
        setTip(null)
        revealAnimator?.cancel()
        phase = next
        message = text
        if (icon != emptyIconName) {
            emptyIconName = icon
            emptyIcon = icon?.let { Icons.drawable(context, it, tint = Theme.onSurfaceVariant) }
        }
        placeMessage()
        contentDescription = if (title.isEmpty()) text else "$title: $text"
        invalidate()
    }

    /** Called by subclasses with the data extent; fits the y range and switches to the data view. */
    protected fun setFrame(start: Long, end: Long, low: Double, high: Double, legend: List<Pair<String, Int>>) {
        val entering = phase != Phase.DATA || animateNextData
        animateNextData = false
        this.start = start
        this.end = max(end, start + 1)
        var lo = low
        var hi = high
        if (includeZero) { lo = min(lo, 0.0); hi = max(hi, 0.0) }
        val fixed = fixedRange
        val step: Double
        if (fixed != null) {
            lo = fixed.start; hi = fixed.endInclusive
            step = yStep?.takeIf { it > 0 && (hi - lo) / it <= 20 } ?: niceStep(hi - lo)
        } else {
            if (symmetric) {
                val m = maxOf(abs(lo), abs(hi), minSpan / 2)
                lo = -m; hi = m
            } else if (hi - lo < minSpan) {
                val mid = (lo + hi) / 2
                lo = mid - minSpan / 2; hi = mid + minSpan / 2
                if (includeZero && low >= 0 && lo < 0) { hi -= lo; lo = 0.0 }
            }
            limits?.let { bound ->
                if (lo < bound.start) { hi = min(bound.endInclusive, hi + bound.start - lo); lo = bound.start }
                if (hi > bound.endInclusive) { lo = max(bound.start, lo - (hi - bound.endInclusive)); hi = bound.endInclusive }
            }
            step = yStep?.takeIf { it > 0 && (hi - lo) / it <= 20 } ?: niceStep(hi - lo)
            lo = floor(lo / step + 1e-9) * step
            hi = ceil(hi / step - 1e-9) * step
            if (hi - lo < step) hi = lo + step
        }
        yMin = lo; yMax = hi; tickStep = step
        yTicks = DoubleArray(((hi - lo) / step + 1e-6).toInt() + 1) { lo + it * step }
        yLabels = Array(yTicks.size) { tickText(yTicks[it]) }
        yLabelWidth = yLabels.maxOfOrNull { axisText.measureText(it) } ?: 0f

        // Time labels (centred on spans for daily axes), worded once here, placed in onDraw.
        val span = axis.labelSpanMs
        val centers = ArrayList<Long>()
        val labels = ArrayList<String>()
        for (tick in axis.ticks(this.start, this.end)) {
            val center = if (span > 0) {
                val a = max(tick, this.start)
                val b = min(tick + span, this.end)
                if (b - a < span * 0.4) continue
                (a + b) / 2
            } else {
                if (tick < this.start || tick > this.end) continue
                tick
            }
            centers += center
            labels += axis.label(tick)
        }
        xCenters = centers.toLongArray()
        xLabels = labels.toTypedArray()
        xWidths = FloatArray(xLabels.size) { axisText.measureText(xLabels[it]) }

        legendNames = legend.map { it.first }.toTypedArray()
        legendColors = legend.map { it.second }.toIntArray()
        legendWidths = FloatArray(legendNames.size) { legendText.measureText(legendNames[it]) }

        removeCallbacks(hideTip)
        setTip(null)
        phase = Phase.DATA
        layoutPlot()
        onFrameChanged()
        contentDescription = describe()
        if (entering) startReveal() else invalidate()
    }

    private fun startReveal() {
        revealAnimator?.cancel()
        if (!Motion.enabled() || !isAttachedToWindow || !isShown) { reveal = 1f; invalidate(); return }
        reveal = 0f
        revealAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = REVEAL_MS
            interpolator = Motion.emphasizedDecelerate
            addUpdateListener { reveal = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    protected fun x(time: Long): Float = plot.left + (time - start).toFloat() / (end - start) * plot.width()

    protected fun y(value: Double): Float = plot.bottom - ((value - yMin) / (yMax - yMin)).toFloat() * plot.height()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), resolveSize(dp(heightDp), heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        setTip(null)
        layoutPlot()
        placeMessage()
        onFrameChanged()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(hideTip)
        revealAnimator?.cancel()
        reveal = 1f
        super.onDetachedFromWindow()
    }

    private fun legendHeight(): Float = if (legendNames.isEmpty()) 0f else legendText.textSize + dpf(14f)

    private fun layoutPlot() {
        val top = paddingTop + legendHeight() + axisText.textSize * 0.6f
        val right = width - paddingRight - yLabelWidth - dpf(10f)
        plot.set(paddingLeft + dpf(2f), top, max(paddingLeft + dpf(2f), right), height - paddingBottom - axisText.textSize - dpf(10f))
        // Skeleton: a soft wave across the middle.
        skeletonPath.reset()
        val w = width - paddingLeft - paddingRight - dpf(8f)
        if (w > 0) {
            val left = paddingLeft + dpf(4f)
            val mid = paddingTop + (height - paddingTop - paddingBottom) * 0.55f
            val amp = (height - paddingTop - paddingBottom) * 0.12f
            val steps = 48
            for (i in 0..steps) {
                val f = i / steps.toFloat()
                val py = mid - amp * sin(f * 2.2f * PI.toFloat() + 0.6f) - amp * 0.6f * f
                if (i == 0) skeletonPath.moveTo(left, py) else skeletonPath.lineTo(left + w * f, py)
            }
        }
    }

    private fun placeMessage() {
        val available = (width - paddingLeft - paddingRight - dpf(24f))
        shownMessage = if (available > 0) TextUtils.ellipsize(message, messageText, available, TextUtils.TruncateAt.END) else ""
        val icon = emptyIcon ?: return
        val size = dp(24)
        val cx = width / 2
        val cy = (iconCenterY()).roundToInt()
        icon.setBounds(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
    }

    private fun iconCenterY(): Float = height / 2f - dpf(14f)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        when (phase) {
            Phase.LOADING -> { drawSkeleton(canvas); return }
            Phase.EMPTY -> { drawEmpty(canvas); return }
            Phase.DATA -> {}
        }
        if (plot.width() <= 0 || plot.height() <= 0) return
        drawLegend(canvas)
        drawYAxis(canvas)
        drawXAxis(canvas)
        canvas.save()
        val revealRight = if (revealByClip) plot.left - dpf(6f) + (plot.width() + dpf(12f)) * reveal else plot.right + dpf(6f)
        canvas.clipRect(plot.left - dpf(6f), plot.top - dpf(8f), revealRight, plot.bottom + dpf(4f))
        drawPlot(canvas)
        canvas.restore()
        tip?.let { drawTip(canvas, it) }
    }

    private fun pulse(): Float {
        if (!Motion.enabled()) return 1f
        val t = ((SystemClock.uptimeMillis() - pulseOrigin) % PULSE_MS) / PULSE_MS.toFloat()
        return 0.55f + 0.45f * (0.5f + 0.5f * sin(t * 2f * PI.toFloat()))
    }

    private fun drawSkeleton(canvas: Canvas) {
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val top = paddingTop + dpf(8f)
        val bottom = height - paddingBottom - dpf(20f)
        for (i in 0..3) {
            val py = top + (bottom - top) * i / 3
            canvas.drawLine(left, py, right, py, grid)
        }
        skeleton.alpha = (255 * pulse()).roundToInt()
        canvas.drawPath(skeletonPath, skeleton)
        if (Motion.enabled() && isShown) postInvalidateOnAnimation()
    }

    private fun drawEmpty(canvas: Canvas) {
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val top = paddingTop + dpf(8f)
        val bottom = height - paddingBottom - dpf(20f)
        for (i in 0..3) {
            val py = top + (bottom - top) * i / 3
            canvas.drawLine(left, py, right, py, grid)
        }
        val cx = width / 2f
        val cy = iconCenterY()
        val icon = emptyIcon
        if (icon != null) {
            canvas.drawCircle(cx, cy, dpf(24f), circle)
            icon.draw(canvas)
        }
        val baseline = (if (icon != null) cy + dpf(24f) + dpf(12f) else height / 2f) + messageText.textSize * 0.8f
        canvas.drawText(shownMessage, 0, shownMessage.length, cx, baseline, messageText)
    }

    private fun drawLegend(canvas: Canvas) {
        if (legendNames.isEmpty()) return
        var left = paddingLeft.toFloat()
        val baseline = paddingTop + legendText.textSize
        val swatchW = dpf(12f)
        val swatchH = dpf(4f)
        val mid = baseline - legendText.textSize * 0.33f
        for (i in legendNames.indices) {
            dot.color = legendColors[i]
            box.set(left, mid - swatchH / 2, left + swatchW, mid + swatchH / 2)
            canvas.drawRoundRect(box, swatchH / 2, swatchH / 2, dot)
            left += swatchW + dpf(6f)
            canvas.drawText(legendNames[i], left, baseline, legendText)
            left += legendWidths[i] + dpf(16f)
        }
    }

    private fun drawYAxis(canvas: Canvas) {
        axisText.textAlign = Paint.Align.RIGHT
        val labelRight = (width - paddingRight).toFloat()
        for (i in yTicks.indices) {
            val v = yTicks[i]
            val py = y(v)
            val zero = abs(v) < tickStep * 1e-6 && yMin < 0
            canvas.drawLine(plot.left, py, plot.right, py, if (zero) zeroLine else grid)
            canvas.drawText(yLabels[i], labelRight, py + axisText.textSize * 0.35f, axisText)
        }
    }

    private fun drawXAxis(canvas: Canvas) {
        axisText.textAlign = Paint.Align.LEFT
        val baseline = plot.bottom + dpf(8f) + axisText.textSize
        var lastRight = -Float.MAX_VALUE
        val maxLeft = max(paddingLeft.toFloat(), plot.right)
        for (i in xCenters.indices) {
            val w = xWidths[i]
            val left = (x(xCenters[i]) - w / 2).coerceIn(paddingLeft.toFloat(), max(paddingLeft.toFloat(), maxLeft - w))
            if (left < lastRight + dpf(8f)) continue
            canvas.drawText(xLabels[i], left, baseline, axisText)
            lastRight = left + w
        }
    }

    private fun drawTip(canvas: Canvas, tip: Tip) {
        if (cursorLine) canvas.drawLine(tip.x, plot.top, tip.x, plot.bottom, cursor)
        for (row in tip.rows) {
            val py = row.y ?: continue
            dot.color = surfaceColor; canvas.drawCircle(tip.x, py, dpf(6f), dot)
            dot.color = row.color; canvas.drawCircle(tip.x, py, dpf(4f), dot)
        }
        val pad = dpf(12f)
        val swatch = dpf(4f)
        val lineHeight = tipText.textSize * 1.45f
        var textWidth = tipTitle.measureText(tip.heading)
        for (row in tip.rows) textWidth = max(textWidth, swatch * 2 + dpf(8f) + tipText.measureText(row.text))
        val boxWidth = textWidth + pad * 2
        val boxHeight = pad * 2 + tipTitle.textSize + dpf(2f) + lineHeight * tip.rows.size - (lineHeight - tipText.textSize) / 2
        var left = tip.x + dpf(12f)
        if (left + boxWidth > width - paddingRight) left = tip.x - dpf(12f) - boxWidth
        left = left.coerceAtLeast(paddingLeft.toFloat())
        box.set(left, plot.top, left + boxWidth, plot.top + boxHeight)
        val radius = dpf(Shapes.MEDIUM)
        canvas.drawRoundRect(box, radius, radius, tipFill)
        var baseline = box.top + pad + tipTitle.textSize * 0.8f
        canvas.drawText(tip.heading, left + pad, baseline, tipTitle)
        baseline += dpf(2f)
        for (row in tip.rows) {
            baseline += lineHeight
            dot.color = row.color
            canvas.drawCircle(left + pad + swatch, baseline - tipText.textSize * 0.33f, swatch, dot)
            canvas.drawText(row.text, left + pad + swatch * 2 + dpf(8f), baseline, tipText)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (phase != Phase.DATA || plot.width() <= 0) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                removeCallbacks(hideTip)
                downX = event.x; downY = event.y; dragging = false
                scrubTo(event.x)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = abs(event.x - downX)
                if (!dragging && dx > slop && dx > abs(event.y - downY)) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                scrubTo(event.x)
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                postDelayed(hideTip, TIP_HOLD_MS)
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                setTip(null)
            }
        }
        return true
    }

    private fun scrubTo(px: Float) {
        val fraction = ((px.coerceIn(plot.left, plot.right) - plot.left) / plot.width()).toDouble()
        val next = tipAt(start + (fraction * (end - start)).toLong())
        val stepped = next != null && tip != null && next.time != tip?.time
        setTip(next)
        if (stepped && dragging) chartTick()
    }

    private fun setTip(next: Tip?) {
        if (tip == null && next == null) return
        val moved = tip?.time != next?.time
        tip = next
        if (moved) onScrub?.invoke(next?.time)
        invalidate()
    }

    private fun tickText(value: Double): String {
        val decimals = when {
            abs(tickStep - Math.rint(tickStep)) < 1e-9 -> 0
            abs(tickStep * 10 - Math.rint(tickStep * 10)) < 1e-9 -> 1
            else -> 2
        }
        val v = if (abs(value) < tickStep * 1e-6) 0.0 else value
        val sign = when { v < 0 -> "−"; signed && v > 0 -> "+"; else -> "" }
        return sign + number(abs(v), decimals) + axisUnit
    }

    companion object {
        const val LOADING = "Loading"
        const val NO_DATA = "No data"

        /** About four intervals of 1, 2, 2.5 or 5 × 10ⁿ covering [span]. */
        fun niceStep(span: Double): Double {
            if (!(span > 0) || !span.isFinite()) return 1.0
            val raw = span / 4
            val magnitude = 10.0.pow(floor(log10(raw)))
            val f = raw / magnitude
            return magnitude * when { f <= 1.0 -> 1.0; f <= 2.0 -> 2.0; f <= 2.5 -> 2.5; f <= 5.0 -> 5.0; else -> 10.0 }
        }
    }
}

// ------------------------------------------------------------------ line / area chart

/** One line: [area] fills beneath it (gradient), [band] shades each bucket's min–max range. */
class ChartSeries(
    val name: String,
    val color: Int,
    val points: List<BucketPoint>,
    val area: Boolean = false,
    val band: Boolean = false,
    /** Line width in dp; the main series reads stronger at 3. */
    val strokeDp: Float = 2.5f
)

/**
 * Time series lines (several share one y axis; a legend appears for more than one), drawn as smooth
 * monotone curves with round caps. Consecutive points further apart than 1.5 buckets are separate
 * runs, so missing data shows as a gap. With [minSpacingDp] points closer than that on screen are
 * merged first ([RideMath.plotRuns]), and a run too short to read as a line (a few hours on a 7-day
 * axis) is drawn as dots, never as a vertical stroke. An optional dashed reference line (e.g. a
 * ride's top speed) is labelled at the end. The newest value of the first series is ringed.
 */
class LineChart(context: Context) : TimeChart(context) {
    /** Points closer than this on screen merge into one (Insights' long periods); 0 draws every bucket. */
    var minSpacingDp = 0f
    private var series: List<ChartSeries> = emptyList()
    /** Per series: drawable runs (merged for the current width); flattened for tooltips. */
    private var runs: List<List<List<BucketPoint>>> = emptyList()
    private var drawn: List<List<BucketPoint>> = emptyList()
    private var bucketMs = 0L
    private var slotMs = 0L
    private var gapMs = Long.MAX_VALUE
    private var reference: Double? = null
    private var referenceLabel: String? = null
    private val path = Path()
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dashed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dpf(1.5f); strokeCap = Paint.Cap.ROUND
        color = alpha(Theme.onSurfaceVariant, 0.7f)
        pathEffect = DashPathEffect(floatArrayOf(dpf(4f), dpf(5f)), 0f)
    }
    private val referenceText = Paint(Paint.ANTI_ALIAS_FLAG).applyStyle(context, Typography.labelSmall).tabular().apply {
        color = Theme.onSurfaceVariant; textAlign = Paint.Align.RIGHT
    }
    private val shaders = HashMap<Int, Shader>()
    private val shortRun = dpf(SHORT_RUN_DP)
    // Scratch arrays for one run (sized in setData, reused in onDraw).
    private var xs = FloatArray(0)
    private var ys = FloatArray(0)
    private var slopes = FloatArray(0)
    private var tangents = FloatArray(0)

    /**
     * Shows [series] over [start, end). [bucketMs] is the bucket width the points were averaged into
     * (gaps and tooltip matching depend on it). Series without points are skipped; with none left the
     * chart shows [emptyText] under [emptyIcon].
     */
    fun setData(
        series: List<ChartSeries>, start: Long, end: Long, bucketMs: Long,
        emptyText: String = NO_DATA, reference: Double? = null, referenceLabel: String? = null,
        emptyIcon: String = "monitoring"
    ) {
        val drawnSeries = series.filter { s -> s.points.any { it.avg.isFinite() } }
        if (drawnSeries.isEmpty() || end <= start) { showEmpty(emptyText, emptyIcon); return }
        this.series = drawnSeries
        this.bucketMs = max(0L, bucketMs)
        gapMs = if (bucketMs > 0) bucketMs * 3 / 2 else Long.MAX_VALUE
        this.reference = reference?.takeIf { it.isFinite() }
        this.referenceLabel = referenceLabel
        val longest = drawnSeries.maxOf { it.points.size }
        if (xs.size < longest) {
            xs = FloatArray(longest); ys = FloatArray(longest); slopes = FloatArray(longest); tangents = FloatArray(longest)
        }
        var lo = Double.POSITIVE_INFINITY
        var hi = Double.NEGATIVE_INFINITY
        for (s in drawnSeries) for (p in s.points) {
            if (!p.avg.isFinite()) continue
            lo = min(lo, if (s.band) p.min else p.avg)
            hi = max(hi, if (s.band) p.max else p.avg)
        }
        this.reference?.let { lo = min(lo, it); hi = max(hi, it) }
        setFrame(start, end, lo, hi, if (drawnSeries.size > 1) drawnSeries.map { it.name to it.color } else emptyList())
    }

    /** Width or data changed: re-merge for the plot width (not in onDraw). */
    override fun onFrameChanged() {
        shaders.clear()
        slotMs = if (minSpacingDp > 0 && plot.width() > 0) ((end - start) * dpf(minSpacingDp) / plot.width()).toLong() else 0L
        runs = series.map { RideMath.plotRuns(it.points, start, slotMs, gapMs) }
        drawn = runs.map { it.flatten() }
    }

    override fun drawPlot(canvas: Canvas) {
        val base = if (yMin < 0 && yMax > 0) y(0.0) else plot.bottom
        for (i in series.size - 1 downTo 0) {
            val seriesRuns = runs.getOrNull(i) ?: continue
            for (r in seriesRuns.indices) drawRun(canvas, series[i], seriesRuns[r], base)
        }
        reference?.let { value ->
            val py = y(value)
            canvas.drawLine(plot.left, py, plot.right, py, dashed)
            referenceLabel?.let { canvas.drawText(it, plot.right - dpf(2f), py - dpf(6f), referenceText) }
        }
        // The newest value of the first series, ringed (after the wipe-in).
        if (tipTime == null && reveal >= 1f) {
            val last = drawn.firstOrNull()?.lastOrNull() ?: return
            val px = x(last.time); val py = y(last.avg)
            dot.color = surfaceColor; canvas.drawCircle(px, py, dpf(5.5f), dot)
            dot.color = series.first().color; canvas.drawCircle(px, py, dpf(3.5f), dot)
        }
    }

    private fun drawRun(canvas: Canvas, s: ChartSeries, points: List<BucketPoint>, base: Float) {
        val n = points.size
        if (n == 0) return
        // Too short to read as a line (one point, or a few hours on a long axis): dots, never a vertical stroke.
        if (n == 1 || x(points[n - 1].time) - x(points[0].time) < shortRun) {
            fill.shader = null; fill.color = s.color
            val radius = dpf(s.strokeDp / 2 + 1.5f)
            for (i in 0 until n) canvas.drawCircle(x(points[i].time), y(points[i].avg), radius, fill)
            return
        }
        if (s.band) {
            path.reset()
            for (i in 0 until n) {
                val px = x(points[i].time); val py = y(points[i].max)
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            for (i in n - 1 downTo 0) path.lineTo(x(points[i].time), y(points[i].min))
            path.close()
            fill.shader = null; fill.color = alpha(s.color, 0.14f)
            canvas.drawPath(path, fill)
        }
        for (k in 0 until n) { xs[k] = x(points[k].time); ys[k] = y(points[k].avg) }
        computeTangents(n)
        if (s.area) {
            path.reset()
            path.moveTo(xs[0], base)
            path.lineTo(xs[0], ys[0])
            appendCurve(path, n)
            path.lineTo(xs[n - 1], base)
            path.close()
            if (base < plot.bottom) { fill.shader = null; fill.color = alpha(s.color, 0.18f) }
            else fill.shader = shaders.getOrPut(s.color) {
                LinearGradient(0f, plot.top, 0f, plot.bottom, alpha(s.color, 0.32f), alpha(s.color, 0f), Shader.TileMode.CLAMP)
            }
            canvas.drawPath(path, fill)
            fill.shader = null
        }
        path.reset()
        path.moveTo(xs[0], ys[0])
        appendCurve(path, n)
        stroke.color = s.color
        stroke.strokeWidth = dpf(s.strokeDp)
        canvas.drawPath(path, stroke)
    }

    /** Monotone cubic (Fritsch–Carlson) tangents for xs/ys[0 until n]: smooth, never overshoots. */
    private fun computeTangents(n: Int) {
        for (k in 0 until n - 1) {
            val dx = xs[k + 1] - xs[k]
            slopes[k] = if (dx > 0f) (ys[k + 1] - ys[k]) / dx else 0f
        }
        tangents[0] = slopes[0]
        tangents[n - 1] = slopes[n - 2]
        for (k in 1 until n - 1) {
            tangents[k] = if (slopes[k - 1] * slopes[k] <= 0f) 0f else (slopes[k - 1] + slopes[k]) / 2f
        }
        for (k in 0 until n - 1) {
            val d = slopes[k]
            if (d == 0f) { tangents[k] = 0f; tangents[k + 1] = 0f; continue }
            val a = tangents[k] / d
            val b = tangents[k + 1] / d
            val s = a * a + b * b
            if (s > 9f) {
                val t = 3f / sqrt(s)
                tangents[k] = t * a * d
                tangents[k + 1] = t * b * d
            }
        }
    }

    /** Appends cubic segments 0 → n−1 (the path must already be at xs[0], ys[0]). */
    private fun appendCurve(path: Path, n: Int) {
        for (k in 0 until n - 1) {
            val h = (xs[k + 1] - xs[k]) / 3f
            path.cubicTo(xs[k] + h, ys[k] + tangents[k] * h, xs[k + 1] - h, ys[k + 1] - tangents[k + 1] * h, xs[k + 1], ys[k + 1])
        }
    }

    override fun tipAt(time: Long): Tip? {
        var anchor: BucketPoint? = null
        for (points in drawn) {
            val p = nearest(points, time) ?: continue
            if (anchor == null || abs(p.time - time) < abs(anchor.time - time)) anchor = p
        }
        val at = anchor?.time ?: return null
        val tolerance = max(bucketMs / 2, slotMs)
        val rows = series.indices.mapNotNull { i ->
            val s = series[i]
            val p = nearest(drawn.getOrNull(i).orEmpty(), at)?.takeIf { abs(it.time - at) <= tolerance } ?: return@mapNotNull null
            var text = valueFormat(p.avg)
            if (s.band && p.max - p.min > 1e-6) text += "  ${valueFormat(p.min)}–${valueFormat(p.max)}"
            TipRow(s.color, if (series.size > 1) "${s.name}  $text" else text, y(p.avg))
        }
        return Tip(at, x(at), axis.detail(at), rows)
    }

    override fun describe(): String = buildString {
        if (title.isNotEmpty()) append(title).append(". ")
        for (s in series) {
            val finite = s.points.filter { it.avg.isFinite() }
            if (finite.isEmpty()) continue
            if (series.size > 1) append(s.name).append(": ")
            val low = finite.minOf { if (s.band) it.min else it.avg }
            val high = finite.maxOf { if (s.band) it.max else it.avg }
            append("latest ").append(valueFormat(finite.last().avg))
            append(", lowest ").append(valueFormat(low)).append(", highest ").append(valueFormat(high)).append(". ")
        }
        referenceLabel?.let { append(it).append('.') }
    }.trim()

    private fun nearest(points: List<BucketPoint>, time: Long): BucketPoint? {
        if (points.isEmpty()) return null
        val found = points.binarySearch { it.time.compareTo(time) }
        if (found >= 0) return points[found]
        val after = points.getOrNull(-found - 1)
        val before = points.getOrNull(-found - 2)
        return when {
            after == null -> before
            before == null -> after
            time - before.time <= after.time - time -> before
            else -> after
        }
    }

    private companion object {
        /** A run narrower than this on screen is drawn as dots. */
        const val SHORT_RUN_DP = 12f
    }
}

// ------------------------------------------------------------------ bar chart

/** Totals per time slot (hours or days): rounded-top bars from zero that grow in; touching a bar shows its slot and value. */
class BarChart(context: Context) : TimeChart(context) {
    var color = Theme.primary
    /** Tooltip text for a slot without a bar (e.g. "No rides"); null shows the formatted zero. */
    var emptySlotText: String? = null
    private var bars: List<ValueAt> = emptyList()
    private var slotMs = HOUR_MS
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()
    private val radii = FloatArray(8)
    override val cursorLine = false
    override val revealByClip = false

    init { includeZero = true }

    /**
     * [bars] are slot starts with totals; [start] must be a slot boundary and every slot [slotMs] long.
     * Without a positive value the chart shows [emptyText] under [emptyIcon].
     */
    fun setData(bars: List<ValueAt>, start: Long, end: Long, slotMs: Long, emptyText: String = NO_DATA, emptyIcon: String = "monitoring") {
        val shown = bars.filter { it.value > 0 && it.value.isFinite() }.sortedBy { it.time }
        if (shown.isEmpty() || slotMs <= 0 || end <= start) { showEmpty(emptyText, emptyIcon); return }
        this.bars = shown
        this.slotMs = slotMs
        setFrame(start, end, 0.0, shown.maxOf { it.value }, emptyList())
    }

    override fun drawPlot(canvas: Canvas) {
        val slotPx = plot.width() * slotMs / (end - start)
        val inset = min(slotPx * 0.16f, dpf(4f))
        val zero = y(0.0)
        val focus = tipTime
        val grow = reveal
        for (bar in bars) {
            val left = x(bar.time) + inset
            val right = max(left + dpf(2f), x(bar.time + slotMs) - inset)
            val full = min(y(bar.value), zero - dpf(3f)) // A tiny total still shows.
            val top = zero - (zero - full) * grow
            if (zero - top < 0.5f) continue
            val radius = min(dpf(6f), (right - left) / 2).coerceAtMost(zero - top)
            paint.color = if (focus == null || focus == bar.time + slotMs / 2) color else alpha(color, 0.38f)
            rect.set(left, top, right, zero)
            radii[0] = radius; radii[1] = radius; radii[2] = radius; radii[3] = radius
            radii[4] = 0f; radii[5] = 0f; radii[6] = 0f; radii[7] = 0f
            path.reset()
            path.addRoundRect(rect, radii, Path.Direction.CW)
            canvas.drawPath(path, paint)
        }
    }

    override fun tipAt(time: Long): Tip {
        val bar = bars.firstOrNull { time >= it.time && time < it.time + slotMs }
        val slot = bar?.time ?: (start + (time - start) / slotMs * slotMs)
        val text = if (bar == null) emptySlotText ?: valueFormat(0.0) else valueFormat(bar.value)
        val mid = slot + slotMs / 2
        return Tip(mid, x(mid), axis.slot(slot, slotMs), listOf(TipRow(color, text, null)))
    }

    override fun describe(): String {
        val top = bars.maxByOrNull { it.value } ?: return title
        return buildString {
            if (title.isNotEmpty()) append(title).append(". ")
            append("Total ").append(valueFormat(bars.sumOf { it.value }))
            append(", highest ").append(valueFormat(top.value)).append(" (").append(axis.slot(top.time, slotMs)).append(").")
        }
    }
}

// ------------------------------------------------------------------ sparkline

/** A tiny trend line without axes (e.g. live speed); the newest value is ringed. Decorative for TalkBack. */
class Sparkline(context: Context) : View(context) {
    var color = Theme.primary
        set(next) { if (field != next) { field = next; shader = null; invalidate() } }
    /** Colour behind the line (cut-out ring around the newest value). */
    var surfaceColor = Theme.surfaceContainer
    /** Always inside the range (0 keeps a speed trace anchored to standstill); null fits the values. */
    var baseline: Double? = 0.0
    var minSpan = 1.0
    private var values = DoubleArray(0)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dpf(2.5f); strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()
    private var shader: Shader? = null

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun setValues(next: List<Double>) {
        values = next.filter { it.isFinite() }.toDoubleArray()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), resolveSize(dp(40), heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        shader = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pad = dpf(6f)
        val w = width - 2 * pad
        val h = height - 2 * pad
        if (w <= 0 || h <= 0) return
        val n = values.size
        if (n < 2) {
            stroke.color = alpha(color, 0.3f)
            canvas.drawLine(pad, height - pad, width - pad, height - pad, stroke)
            return
        }
        var lo = values.min()
        var hi = values.max()
        baseline?.let { lo = min(lo, it); hi = max(hi, it) }
        if (hi - lo < minSpan) hi = lo + minSpan
        fun px(i: Int) = pad + w * i / (n - 1)
        fun py(v: Double) = pad + h - ((v - lo) / (hi - lo)).toFloat() * h
        path.reset()
        path.moveTo(px(0), height - pad)
        for (i in 0 until n) path.lineTo(px(i), py(values[i]))
        path.lineTo(px(n - 1), height - pad)
        path.close()
        fill.shader = shader ?: LinearGradient(0f, pad, 0f, height - pad, alpha(color, 0.32f), alpha(color, 0f), Shader.TileMode.CLAMP)
            .also { shader = it }
        canvas.drawPath(path, fill)
        path.reset()
        for (i in 0 until n) if (i == 0) path.moveTo(px(i), py(values[i])) else path.lineTo(px(i), py(values[i]))
        stroke.color = color
        canvas.drawPath(path, stroke)
        fill.shader = null
        fill.color = surfaceColor; canvas.drawCircle(px(n - 1), py(values[n - 1]), dpf(5.5f), fill)
        fill.color = color; canvas.drawCircle(px(n - 1), py(values[n - 1]), dpf(3.5f), fill)
    }
}
