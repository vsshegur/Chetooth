package dev.codesurgen.ruta

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.SoundEffectConstants
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** What part of the cluster was tapped: the gauge as a whole (battery) or a pack label. */
enum class ClusterPart { BATTERY, FRONT, REAR }

/**
 * Home hero: an instrument cluster inspired by the scooter's own display (not a copy).
 *  - Outer 240° arc = GPS speed 0–80 km/h with ticks (lit up to the current speed) and speed
 *    numerals 0 20 40 60 80 inside the tick ring; bright while riding, quiet at rest.
 *  - Inner left arc = front pack charge ("F"), inner right arc = rear pack ("R"), each filling from
 *    the bottom, coloured by [Theme.batteryColor] (low battery → error).
 *  - Centre = range ("61" Display L + "km", tiny "est." when estimated), below it the total charge
 *    and under that the state glyph (bolt = charging, electric_moped = drive, local_parking =
 *    standby; last-known and dimmed while disconnected).
 *  - Taps ([onPartClick]): a pack label (48 dp target around "F 54%") → FRONT / REAR, anywhere else
 *    on the gauge → BATTERY, with a state-layer ripple on what was hit; TalkBack: click = battery,
 *    custom actions for each pack.
 *  - Moving (> 3 km/h): the centre cross-fades to the speed on the expressive 9-point cookie badge
 *    (it turns in with the cross-fade, then holds still);
 *    [onMovingChanged] lets the screen show range as a chip under the gauge instead.
 * Every value animates with a [Spring] (never jumps); [update] with stale = true (last-known or
 * disconnected) dims everything; [sweep] is the ignition sweep on connect (skipped when animations
 * are off or while moving). Colours come from [Theme] roles; no allocation in onDraw (cached number strings,
 * preallocated paths/rects); geometry is computed from the laid-out width, so it is crisp at any
 * density. contentDescription summarises the values for TalkBack.
 */
class InstrumentCluster(context: Context) : View(context) {
    companion object {
        const val MAX_KMH = 80f
        /** Moving starts above this speed … */
        const val MOVING_KMH = 3.0
        /** … and ends below this one after [STOP_HOLD_MS]. */
        const val STOPPED_KMH = 2.0
        const val STOP_HOLD_MS = 2_000L
        private const val START_DEG = 150f
        private const val SWEEP_DEG = 240f
        private const val PACK_DEG = 100f
        private const val MAX_WIDTH_DP = 360
        private const val SWEEP_MS = 900L
        private const val DASH = "—"
        private val NUMBERS = Array(301) { it.toString() }
        /** Tick values (km/h) in increasing order, every 5; multiples of 10 are long ticks. */
        private val TICKS = IntArray(17) { it * 5 }
        /** Speed numerals on the dial. */
        private val NUMERALS = intArrayOf(0, 20, 40, 60, 80)
    }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private fun number(v: Int): String = NUMBERS[v.coerceIn(0, NUMBERS.size - 1)]

    // ------------------------------------------------------------------ targets (set in update)

    private var hasSpeed = false
    private var hasFront = false
    private var hasRear = false
    private var hasTotal = false
    private var hasRange = false
    private var estimated = false
    private var riding = false
    private var drive = DriveState.UNKNOWN
    private var frontColor = Theme.outline
    private var rearColor = Theme.outline
    private var described = ""

    /** True while the centre shows the speed badge. */
    var moving = false; private set
    private var slowSince = 0L
    /** Called (main thread) when [moving] flips, e.g. to show range in a chip under the gauge. */
    var onMovingChanged: ((Boolean) -> Unit)? = null

    /** Tap on the gauge or a pack label (explain sheets); null = not interactive. */
    var onPartClick: ((ClusterPart) -> Unit)? = null
        set(value) { field = value; isClickable = value != null; isFocusable = value != null }

    // ------------------------------------------------------------------ animated values

    private var speed = 0f
    private var front = 0f
    private var rear = 0f
    private var total = 0f
    private var rangeKm = 0f
    private var mode = 0f
    private var dim = 1f
    private var sweepAmount = 0f
    private val speedSpring = Spring(0f, dampingRatio = Motion.DAMPING_NO_BOUNCE, threshold = 0.05f) { speed = it; invalidate() }
    private val frontSpring = Spring(0f, dampingRatio = Motion.DAMPING_NO_BOUNCE, threshold = 0.05f) { front = it; invalidate() }
    private val rearSpring = Spring(0f, dampingRatio = Motion.DAMPING_NO_BOUNCE, threshold = 0.05f) { rear = it; invalidate() }
    private val totalSpring = Spring(0f, dampingRatio = Motion.DAMPING_NO_BOUNCE, threshold = 0.05f) { total = it; invalidate() }
    private val rangeSpring = Spring(0f, dampingRatio = Motion.DAMPING_NO_BOUNCE, threshold = 0.05f) { rangeKm = it; invalidate() }
    private val modeSpring = Spring(0f, threshold = 0.002f) { mode = it; invalidate() }
    private val dimSpring = Spring(1f, dampingRatio = Motion.DAMPING_NO_BOUNCE, threshold = 0.002f) { dim = it; invalidate() }
    private var sweepAnimator: ValueAnimator? = null

    // ------------------------------------------------------------------ paints and geometry

    private val trackPaint = arcPaint()
    private val speedPaint = arcPaint()
    private val packTrackPaint = arcPaint()
    private val packPaint = arcPaint()
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val litTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val cookiePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rangePaint = textPaint(Typography.displayLarge.emphasized).tabular()
    private val unitPaint = textPaint(Typography.titleMedium)
    private val tinyPaint = textPaint(Typography.labelSmall)
    private val labelPaint = textPaint(Typography.labelMedium).apply { textAlign = Paint.Align.CENTER }
    private val totalPaint = textPaint(Typography.titleMedium).tabular()
    private val packValuePaint = textPaint(Typography.titleSmall).tabular()
    private val packLabelPaint = textPaint(Typography.labelMedium)
    private val speedTextPaint = textPaint(Typography.displayLarge.emphasized).tabular().apply { textAlign = Paint.Align.CENTER }
    private val speedUnitPaint = textPaint(Typography.labelLarge).apply { textAlign = Paint.Align.CENTER }
    private val numeralPaint = textPaint(Typography.labelSmall).tabular().apply { textAlign = Paint.Align.CENTER }

    private val outerRect = RectF()
    private val innerRect = RectF()
    private val tickPoints = FloatArray(TICKS.size * 4)
    private val numeralX = FloatArray(NUMERALS.size)
    private val numeralY = FloatArray(NUMERALS.size)
    private val textBounds = Rect()
    private val cookiePath = Path()
    private val glyphs: Map<DriveState, Drawable> = mapOf(
        DriveState.CHARGING to Icons.drawable(context, "bolt", filled = true, tint = Theme.charging),
        DriveState.DRIVE to Icons.drawable(context, DriveState.DRIVE.icon, filled = true, tint = Theme.drive),
        DriveState.STANDBY to Icons.drawable(context, DriveState.STANDBY.icon, filled = false, tint = Theme.onSurfaceVariant)
    )
    private var glyph: Drawable? = null

    private var cx = 0f
    private var cy = 0f
    private var outerR = 0f
    private var innerR = 0f
    private var outerStroke = 0f
    private var innerStroke = 0f
    private var badgeR = 0f
    private var numberBaseline = 0f
    private var rangeLabelBaseline = 0f
    private var rowBaseline = 0f
    private var packBaseline = 0f
    private var packX = 0f
    private var speedBaseline = 0f
    private var speedUnitBaseline = 0f
    private var glyphSize = 0
    private var glyphTop = 0f
    private var cookieAngle = 0f
    private var tickLong = 0f
    private var tickShort = 0f
    /** Space between the outer arc's inner edge and the pack arcs (ticks + numerals). */
    private var ringGap = 0f

    // Touch: pack-label targets, the battery disc, one ripple drawn over what was pressed.
    private val frontHit = RectF()
    private val rearHit = RectF()
    private var pressedPart: ClusterPart? = null
    /** Part under the finger before the tap timeout (in a scrolling page the press shows only once it is not a scroll). */
    private var downPart: ClusterPart? = null
    private var downX = 0f
    private var downY = 0f
    private val showPress = Runnable { downPart?.let { press(it, downX, downY) } }
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val rippleMask = RoundedShapeDrawable(Color.WHITE, 0f)
    private val ripple = RippleDrawable(ColorStateList.valueOf(alpha(Theme.onSurface, 0.12f)), null, rippleMask).also { it.callback = this }
    // Custom accessibility action ids: outside the framework's bit-flag and 0x0102xxxx ranges.
    private val frontActionId = 0x7F0F0001
    private val rearActionId = 0x7F0F0002

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "Scooter: no data yet"
        trackPaint.color = Theme.surfaceContainerHighest
        packTrackPaint.color = Theme.surfaceContainerHighest
        cookiePaint.color = Theme.primaryContainer
        // Text that never needs the "dim" blend keeps its role colour.
        speedTextPaint.color = Theme.onPrimaryContainer
        speedUnitPaint.color = Theme.onPrimaryContainer
    }

    private fun arcPaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private fun textPaint(style: TextStyle) = Paint(Paint.ANTI_ALIAS_FLAG).applyStyle(context, style)

    // ------------------------------------------------------------------ public API

    /**
     * New values (main thread, ~1 Hz). [speedKmh] null = no GPS fix (shown as 0); percentages
     * 0..100 or null (no reading: empty arc, "—"); [stale] = last-known values or no link (dimmed);
     * [riding] = a ride is being recorded (speed arc bright).
     */
    fun update(
        speedKmh: Double?, front: Double?, rear: Double?, total: Double?, range: RangeEstimate?,
        state: DriveState, stale: Boolean, riding: Boolean
    ) {
        val snap = !isLaidOut
        hasSpeed = speedKmh != null
        hasFront = front != null; hasRear = rear != null; hasTotal = total != null
        hasRange = range != null; estimated = range?.estimated == true
        this.riding = riding
        frontColor = Theme.batteryColor(front)
        rearColor = Theme.batteryColor(rear)
        if (state != drive) { drive = state; glyph = glyphs[state] }
        set(speedSpring, (speedKmh ?: 0.0).coerceIn(0.0, 300.0), snap)
        set(frontSpring, front?.coerceIn(0.0, 100.0) ?: 0.0, snap)
        set(rearSpring, rear?.coerceIn(0.0, 100.0) ?: 0.0, snap)
        set(totalSpring, total?.coerceIn(0.0, 100.0) ?: 0.0, snap)
        set(rangeSpring, range?.km?.coerceIn(0.0, 300.0) ?: 0.0, snap)
        set(dimSpring, if (stale) 1.0 else 0.0, snap)

        val now = SystemClock.uptimeMillis()
        val v = speedKmh ?: 0.0
        val next = when {
            v > MOVING_KMH -> { slowSince = 0L; true }
            !moving -> false
            v < STOPPED_KMH -> { if (slowSince == 0L) slowSince = now; now - slowSince < STOP_HOLD_MS }
            else -> { slowSince = 0L; true }
        }
        if (next != moving) {
            moving = next
            set(modeSpring, if (next) 1.0 else 0.0, snap)
            onMovingChanged?.invoke(next)
        }
        describe(speedKmh, front, rear, total, range, state, stale)
        invalidate()
    }

    /** Ignition sweep (DESIGN A5): arcs sweep to full and settle on the real values, ~900 ms. */
    fun sweep() {
        // Never a false full-scale reading on a live speedometer.
        if (!Motion.enabled() || moving) return
        sweepAnimator?.cancel()
        sweepAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SWEEP_MS
            interpolator = LinearInterpolator()
            addUpdateListener { a ->
                val t = a.animatedFraction
                sweepAmount = if (t < 0.45f) Motion.emphasizedDecelerate.getInterpolation(t / 0.45f)
                else 1f - Motion.emphasized.getInterpolation((t - 0.45f) / 0.55f)
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { sweepAmount = 0f; invalidate() }
            })
            start()
        }
    }

    private fun set(spring: Spring, target: Double, snap: Boolean) {
        val t = target.toFloat()
        if (snap) spring.snapTo(t) else if (spring.target != t) spring.animateTo(t)
    }

    private fun describe(speedKmh: Double?, front: Double?, rear: Double?, total: Double?, range: RangeEstimate?, state: DriveState, stale: Boolean) {
        val parts = ArrayList<String>(6)
        if (moving && speedKmh != null) parts += "Speed ${speedKmh.roundToInt()} km/h"
        parts += if (range != null) "Range ${range.km.roundToInt()} km" + (if (range.estimated) ", estimated" else "") else "Range unknown"
        if (total != null) parts += "Charge ${total.roundToInt()} %"
        if (front != null || rear != null) parts += "Front ${front?.roundToInt()?.let { "$it %" } ?: "unknown"}, rear ${rear?.roundToInt()?.let { "$it %" } ?: "unknown"}"
        if (state != DriveState.UNKNOWN) parts += state.label
        if (stale && (range != null || total != null)) parts += "last known"
        val text = parts.joinToString(". ")
        if (text != described) { described = text; contentDescription = text }
    }

    // ------------------------------------------------------------------ layout

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(widthMeasureSpec)
        val width = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) dp(MAX_WIDTH_DP.toFloat()).roundToInt() else available
        layoutFor(width)
        val bottom = maxOf(cy + 0.5f * outerR + outerStroke / 2f, packBaseline + packValuePaint.descent(), glyphTop + glyphSize) + dp(4f)
        setMeasuredDimension(width, resolveSize((bottom + paddingBottom).roundToInt(), heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutFor(w)
        outerRect.set(cx - outerR, cy - outerR, cx + outerR, cy + outerR)
        innerRect.set(cx - innerR, cy - innerR, cx + innerR, cy + innerR)
        // Ticks just inside the outer arc: long every 10 km/h, short in between.
        val outerEnd = outerR - outerStroke / 2f - dp(5f)
        for (i in TICKS.indices) {
            val value = TICKS[i]
            val length = if (value % 10 == 0) tickLong else tickShort
            val angle = Math.toRadians((START_DEG + SWEEP_DEG * value / MAX_KMH).toDouble())
            val c = cos(angle).toFloat(); val s = sin(angle).toFloat()
            tickPoints[i * 4] = cx + outerEnd * c
            tickPoints[i * 4 + 1] = cy + outerEnd * s
            tickPoints[i * 4 + 2] = cx + (outerEnd - length) * c
            tickPoints[i * 4 + 3] = cy + (outerEnd - length) * s
        }
        // Numerals inside the long ticks, each pushed in by its own half-extent along the radius
        // (a numeral at 9 o'clock needs more room than one at 12), baseline-centred on its point.
        numeralPaint.getTextBounds("80", 0, 2, textBounds)
        val capHalf = textBounds.height() / 2f
        for (i in NUMERALS.indices) {
            val angle = Math.toRadians((START_DEG + SWEEP_DEG * NUMERALS[i] / MAX_KMH).toDouble())
            val c = cos(angle).toFloat(); val s = sin(angle).toFloat()
            val halfW = numeralPaint.measureText(number(NUMERALS[i])) / 2f
            val extent = abs(c) * halfW + abs(s) * capHalf
            val r = outerEnd - tickLong - dp(3f) - extent
            numeralX[i] = cx + r * c
            numeralY[i] = cy + r * s + capHalf
        }
        ExpressiveShapes.cookie(cookiePath, 0f, 0f, badgeR, points = 9, depth = 0.1f)
        // Pack-label targets: at least 48 dp, centred on "F 100%" / "R 100%".
        val labelW = packLabelPaint.measureText("F") + dp(4f) + packValuePaint.measureText("100") + packLabelPaint.measureText("%")
        val halfW = max(dp(24f), labelW / 2f + dp(12f))
        val midY = packBaseline - packValuePaint.textSize * 0.35f
        val halfH = max(dp(24f), packValuePaint.textSize)
        frontHit.set(cx - packX - halfW, midY - halfH, cx - packX + halfW, midY + halfH)
        rearHit.set(cx + packX - halfW, midY - halfH, cx + packX + halfW, midY + halfH)
    }

    /** All geometry from the width; also used by onMeasure for the height. */
    private fun layoutFor(width: Int) {
        val usable = min(width.toFloat(), dp(MAX_WIDTH_DP.toFloat()))
        outerStroke = max(dp(8f), usable * 0.03f)
        innerStroke = outerStroke * 0.75f
        outerR = usable / 2f - outerStroke / 2f - dp(2f)
        // Tick ring (5 dp in, long ticks 6 dp), then the numerals that sit beside the pack arcs
        // (0/20 and 60/80 at ±30° from horizontal: radial extent 0.87·half-width + 0.5·half-cap),
        // then 3 dp of air before the pack arcs.
        tickLong = dp(6f); tickShort = dp(3f)
        val numeralExtent = 0.87f * numeralPaint.measureText("80") / 2f + 0.5f * numeralPaint.textSize * 0.36f
        ringGap = dp(5f) + tickLong + dp(3f) + 2f * numeralExtent + dp(3f)
        innerR = outerR - outerStroke / 2f - ringGap - innerStroke / 2f
        cx = width / 2f
        cy = paddingTop + outerStroke / 2f + outerR + dp(2f)
        trackPaint.strokeWidth = outerStroke; speedPaint.strokeWidth = outerStroke
        packTrackPaint.strokeWidth = innerStroke; packPaint.strokeWidth = innerStroke
        tickPaint.strokeWidth = dp(1.5f); litTickPaint.strokeWidth = dp(1.5f)

        val numberCap = rangePaint.textSize * 0.7f
        numberBaseline = cy + numberCap / 2f - dp(6f)
        rangeLabelBaseline = numberBaseline - numberCap - dp(10f)
        rowBaseline = numberBaseline + totalPaint.textSize + dp(12f)
        glyphSize = (totalPaint.textSize * 1.25f).roundToInt()
        glyphTop = rowBaseline + totalPaint.descent() + dp(6f)
        packBaseline = cy + 0.5f * innerR + innerStroke / 2f + dp(6f) + packValuePaint.textSize * 0.75f
        packX = innerR * 0.62f
        badgeR = min(innerR - innerStroke / 2f - dp(12f), dp(80f))
        speedBaseline = cy + numberCap / 2f - dp(8f)
        speedUnitBaseline = speedBaseline + speedUnitPaint.textSize + dp(4f)
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (outerR <= 0f) return
        val ink = blend(Theme.onSurface, Theme.onSurfaceVariant, dim)
        val muted = Theme.onSurfaceVariant
        val fade = 1f - 0.5f * dim

        // Speed: track, ticks (lit up to the speed), arc.
        val speedFraction = lerp((speed / MAX_KMH).coerceIn(0f, 1f), 1f, sweepAmount)
        canvas.drawArc(outerRect, START_DEG, SWEEP_DEG, false, trackPaint)
        val shown = speedFraction * MAX_KMH
        var lit = 0
        while (lit < TICKS.size && TICKS[lit] <= shown && (speedFraction > 0.001f)) lit++
        tickPaint.color = Theme.outlineVariant
        val speedColor = if (riding || sweepAmount > 0f) Theme.primary else alpha(Theme.primary, 0.5f)
        litTickPaint.color = alpha(speedColor, 0.9f * fade)
        if (lit > 0) canvas.drawLines(tickPoints, 0, lit * 4, litTickPaint)
        if (lit < TICKS.size) canvas.drawLines(tickPoints, lit * 4, (TICKS.size - lit) * 4, tickPaint)
        // Numerals: quiet at rest, clear while riding.
        numeralPaint.color = muted
        numeralPaint.alpha = ((if (riding || sweepAmount > 0f) 0.9f else 0.55f) * fade * 255).roundToInt()
        for (i in NUMERALS.indices) canvas.drawText(number(NUMERALS[i]), numeralX[i], numeralY[i], numeralPaint)
        if (speedFraction > 0.002f) {
            speedPaint.color = alpha(speedColor, Color.alpha(speedColor) / 255f * fade)
            canvas.drawArc(outerRect, START_DEG, SWEEP_DEG * speedFraction, false, speedPaint)
        }

        // Packs: left (front) fills clockwise from the bottom, right (rear) anticlockwise.
        canvas.drawArc(innerRect, START_DEG, PACK_DEG, false, packTrackPaint)
        canvas.drawArc(innerRect, 180f - START_DEG, -PACK_DEG, false, packTrackPaint)
        val f = lerp(front / 100f, 1f, sweepAmount)
        val r = lerp(rear / 100f, 1f, sweepAmount)
        if (f > 0.002f) {
            packPaint.color = blend(if (sweepAmount > 0f) Theme.primary else frontColor, muted, dim * 0.6f)
            canvas.drawArc(innerRect, START_DEG, PACK_DEG * f, false, packPaint)
        }
        if (r > 0.002f) {
            packPaint.color = blend(if (sweepAmount > 0f) Theme.primary else rearColor, muted, dim * 0.6f)
            canvas.drawArc(innerRect, 180f - START_DEG, -PACK_DEG * r, false, packPaint)
        }
        drawPack(canvas, "F", hasFront, front, cx - packX, ink, muted)
        drawPack(canvas, "R", hasRear, rear, cx + packX, ink, muted)

        // Centre: range (parked) ↔ speed badge (moving), cross-faded by [mode].
        val rangeAlpha = 1f - mode
        if (rangeAlpha > 0.01f) {
            val scale = 1f - 0.08f * mode
            canvas.save()
            canvas.scale(scale, scale, cx, cy)
            drawRange(canvas, ink, muted, rangeAlpha)
            canvas.restore()
        }
        if (mode > 0.01f) drawSpeed(canvas)
        ripple.draw(canvas) // Nothing while idle; the press state layer and its exit otherwise.
    }

    private fun drawPack(canvas: Canvas, label: String, has: Boolean, value: Float, centerX: Float, ink: Int, muted: Int) {
        val text = if (has) number(value.roundToInt()) else DASH
        val gap = dp(4f)
        val labelW = packLabelPaint.measureText(label)
        val valueW = packValuePaint.measureText(text)
        val unitW = if (has) packLabelPaint.measureText("%") else 0f
        var x = centerX - (labelW + gap + valueW + unitW) / 2f
        packLabelPaint.color = muted
        canvas.drawText(label, x, packBaseline, packLabelPaint)
        x += labelW + gap
        packValuePaint.color = ink
        canvas.drawText(text, x, packBaseline, packValuePaint)
        if (has) canvas.drawText("%", x + valueW, packBaseline, packLabelPaint)
    }

    private fun drawRange(canvas: Canvas, ink: Int, muted: Int, opacity: Float) {
        val a = (opacity * 255).roundToInt()
        labelPaint.color = muted; labelPaint.alpha = a
        canvas.drawText("Range", cx, rangeLabelBaseline, labelPaint)

        val text = if (hasRange) number(rangeKm.roundToInt()) else DASH
        val gap = dp(4f)
        val numberW = rangePaint.measureText(text)
        val unitW = if (hasRange) unitPaint.measureText("km") else 0f
        var x = cx - (numberW + if (hasRange) gap + unitW else 0f) / 2f
        rangePaint.color = ink; rangePaint.alpha = a
        canvas.drawText(text, x, numberBaseline, rangePaint)
        if (hasRange) {
            x += numberW + gap
            unitPaint.color = muted; unitPaint.alpha = a
            canvas.drawText("km", x, numberBaseline, unitPaint)
            if (estimated) {
                tinyPaint.color = muted; tinyPaint.alpha = a
                canvas.drawText("est.", x, numberBaseline - unitPaint.textSize - dp(2f), tinyPaint)
            }
        }

        // Total charge, centred; the state glyph under it (last-known and dimmed when stale).
        val totalText = if (hasTotal) number(total.roundToInt()) else DASH
        val valueW = totalPaint.measureText(totalText)
        val unitPctW = if (hasTotal) unitPaint.measureText("%") else 0f
        x = cx - (valueW + unitPctW) / 2f
        totalPaint.color = ink; totalPaint.alpha = a
        canvas.drawText(totalText, x, rowBaseline, totalPaint)
        if (hasTotal) {
            unitPaint.color = muted; unitPaint.alpha = a
            canvas.drawText("%", x + valueW, rowBaseline, unitPaint)
        }
        val icon = glyph ?: return
        val left = (cx - glyphSize / 2f).roundToInt()
        val top = glyphTop.roundToInt()
        icon.setBounds(left, top, left + glyphSize, top + glyphSize)
        icon.alpha = ((1f - 0.5f * dim) * a).roundToInt()
        icon.draw(canvas)
    }

    private fun drawSpeed(canvas: Canvas) {
        val scale = 0.7f + 0.3f * mode
        val a = (mode * 255).roundToInt()
        // The badge turns in with the cross-fade (mode 0 → 1) and then holds still: no redraws at
        // display rate for a whole ride just for decoration.
        cookieAngle = -40f * (1f - mode)
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(scale, scale)
        canvas.save()
        canvas.rotate(cookieAngle)
        cookiePaint.color = Theme.primaryContainer
        cookiePaint.alpha = a
        canvas.drawPath(cookiePath, cookiePaint)
        canvas.restore()
        canvas.translate(-cx, -cy)
        speedTextPaint.alpha = a
        canvas.drawText(number(speed.roundToInt()), cx, speedBaseline, speedTextPaint)
        speedUnitPaint.alpha = a
        canvas.drawText("km/h", cx, speedUnitBaseline, speedUnitPaint)
        canvas.restore()
    }

    override fun onDetachedFromWindow() {
        sweepAnimator?.cancel(); sweepAnimator = null
        sweepAmount = 0f
        abandon()
        ripple.jumpToCurrentState()
        super.onDetachedFromWindow()
    }

    // ------------------------------------------------------------------ touch and accessibility

    private fun partAt(x: Float, y: Float): ClusterPart? = when {
        frontHit.contains(x, y) -> ClusterPart.FRONT
        rearHit.contains(x, y) -> ClusterPart.REAR
        (x - cx) * (x - cx) + (y - cy) * (y - cy) <= outerR * outerR && y <= height -> ClusterPart.BATTERY
        else -> null
    }

    private fun press(part: ClusterPart, x: Float, y: Float) {
        pressedPart = part
        when (part) {
            ClusterPart.FRONT -> { rippleMask.setRadius(dp(16f)); ripple.setBounds(frontHit.left.roundToInt(), frontHit.top.roundToInt(), frontHit.right.roundToInt(), frontHit.bottom.roundToInt()) }
            ClusterPart.REAR -> { rippleMask.setRadius(dp(16f)); ripple.setBounds(rearHit.left.roundToInt(), rearHit.top.roundToInt(), rearHit.right.roundToInt(), rearHit.bottom.roundToInt()) }
            ClusterPart.BATTERY -> {
                // A disc inside the pack arcs: the gauge face, not the whole square.
                val r = innerR - innerStroke
                rippleMask.setRadius(r)
                ripple.setBounds((cx - r).roundToInt(), (cy - r).roundToInt(), (cx + r).roundToInt(), (cy + r).roundToInt())
            }
        }
        ripple.setHotspot(x, y)
        ripple.state = intArrayOf(android.R.attr.state_pressed, android.R.attr.state_enabled)
        invalidate()
    }

    private fun release() {
        if (pressedPart == null) return
        pressedPart = null
        ripple.state = intArrayOf(android.R.attr.state_enabled)
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (onPartClick == null || !isEnabled) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val part = partAt(event.x, event.y) ?: return false
                downX = event.x; downY = event.y
                downPart = part
                postDelayed(showPress, ViewConfiguration.getTapTimeout().toLong())
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (downPart != null && (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop)) abandon()
                else if (pressedPart != null) ripple.setHotspot(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_UP -> {
                val part = downPart
                if (part != null && pressedPart == null) { removeCallbacks(showPress); press(part, downX, downY) } // A quick tap still shows its ripple.
                downPart = null
                release()
                if (part != null && ClickGuard.accept(this)) {
                    playSoundEffect(SoundEffectConstants.CLICK)
                    onPartClick?.invoke(part)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> { abandon(); return true }
        }
        return true
    }

    private fun abandon() {
        downPart = null
        removeCallbacks(showPress)
        release()
    }

    /** TalkBack / keyboard / switch access: the cluster as a whole = the battery. */
    override fun performClick(): Boolean {
        super.performClick()
        val action = onPartClick ?: return false
        if (ClickGuard.accept(this)) action(ClusterPart.BATTERY)
        return true
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        if (onPartClick == null) return
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, "Battery details"))
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(frontActionId, "Front pack details"))
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(rearActionId, "Rear pack details"))
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        val callback = onPartClick
        if (callback != null && (action == frontActionId || action == rearActionId)) {
            callback(if (action == frontActionId) ClusterPart.FRONT else ClusterPart.REAR)
            return true
        }
        return super.performAccessibilityAction(action, arguments)
    }

    override fun verifyDrawable(who: Drawable): Boolean = who === ripple || super.verifyDrawable(who)

}
