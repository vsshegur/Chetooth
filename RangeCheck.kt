package dev.codesurgen.ruta

import kotlin.math.ceil
import kotlin.math.floor

/*
 * "Can I make it?" (2.2 F-RANGE): while Google Maps navigates, the range estimate (RangeEstimate,
 * the same number Home shows) is compared with the route distance Maps says is left. Pure Kotlin
 * (no android.* imports): host-tested by tests/RangeCheckTest.kt.
 *
 * spare = range − route left.  OK: spare ≥ reserve (5 km).  TIGHT: 0 ≤ spare < reserve.
 * SHORT: spare < 0 (less range than route). Shown as whole km, rounded the cautious way: spare
 * down, shortfall up ("12 km spare", "Tight · 3 km spare", "Short by 4 km").
 */

enum class RangeLevel { OK, TIGHT, SHORT }

/**
 * One range check. [spareKm] = [rangeKm] − [remainingKm], negative when short. [remainingKm] and
 * [rangeKm] are the inputs as used (never negative); [estimated] is [RangeEstimate.estimated].
 */
data class RangeVerdict(
    val level: RangeLevel,
    val spareKm: Double,
    val remainingKm: Double,
    val rangeKm: Double,
    val estimated: Boolean
) {
    /** Whole km to spare, rounded down, never negative ("Tight · 0 km spare" just past zero). */
    val spareWholeKm: Int get() = if (spareKm > 0.0) floor(spareKm).toInt() else 0

    /** Whole km missing, rounded up, at least 1 (a SHORT verdict always has spare < 0, see [RangeCheck.level]). */
    val shortWholeKm: Int get() = ceil(-spareKm).toInt().coerceAtLeast(1)

    /**
     * TIGHT held by hysteresis with the range just under the route (−1 km < spare < 0): "no spare",
     * never "km spare" or "covers" next to a range smaller than the route left.
     */
    val noSpare: Boolean get() = level == RangeLevel.TIGHT && spareKm < 0.0

    /** The status line: "12 km spare", "Tight · 3 km spare", "Tight · no spare", "Short by 4 km". */
    val label: String get() = when (level) {
        RangeLevel.OK -> "$spareWholeKm km spare"
        RangeLevel.TIGHT -> if (noSpare) "Tight · no spare" else "Tight · $spareWholeKm km spare"
        RangeLevel.SHORT -> "Short by $shortWholeKm km"
    }
}

object RangeCheck {
    /** Spare below this (km) is TIGHT. */
    const val RESERVE_KM = 5.0
    /** A level holds until the spare is this far (km) past its line, so the verdict doesn't flicker. */
    const val HYSTERESIS_KM = 1.0

    /**
     * The verdict for [remainingKm] of route left (Maps) and [range]; null when either is unknown or
     * not finite. [previous] is the last level shown for this trip (null on the first check), for
     * hysteresis ([level]). A negative [reserveKm] counts as 0.
     */
    fun evaluate(
        remainingKm: Double?,
        range: RangeEstimate?,
        previous: RangeLevel? = null,
        reserveKm: Double = RESERVE_KM
    ): RangeVerdict? {
        if (remainingKm == null || range == null || !remainingKm.isFinite() || !range.km.isFinite()) return null
        val remaining = remainingKm.coerceAtLeast(0.0)
        val rangeKm = range.km.coerceAtLeast(0.0)
        val spare = rangeKm - remaining
        return RangeVerdict(level(spare, previous, reserveKm), spare, remaining, rangeKm, range.estimated)
    }

    /**
     * Level for [spareKm]. Without [previous]: OK at ≥ reserve, TIGHT at ≥ 0, else SHORT. With it,
     * the level only changes once the spare is [HYSTERESIS_KM] past a line (OK holds down to
     * reserve − 1, TIGHT up to reserve + 1, and OK/TIGHT don't turn SHORT before −1 km), except that
     * SHORT ends as soon as the range covers the route again (spare ≥ 0), so "Short by" is never 0.
     * OK never holds below 0 spare. The result is stable: evaluating it again with itself as
     * [previous] gives the same level.
     */
    fun level(spareKm: Double, previous: RangeLevel?, reserveKm: Double = RESERVE_KM): RangeLevel {
        val reserve = if (reserveKm.isFinite()) reserveKm.coerceAtLeast(0.0) else RESERVE_KM
        val okLine = when (previous) {
            null -> reserve
            RangeLevel.OK -> (reserve - HYSTERESIS_KM).coerceAtLeast(0.0)
            RangeLevel.TIGHT, RangeLevel.SHORT -> reserve + HYSTERESIS_KM
        }
        val shortLine = when (previous) {
            RangeLevel.OK, RangeLevel.TIGHT -> -HYSTERESIS_KM
            RangeLevel.SHORT, null -> 0.0
        }
        return when {
            spareKm >= okLine -> RangeLevel.OK
            spareKm >= shortLine -> RangeLevel.TIGHT
            else -> RangeLevel.SHORT
        }
    }

    /**
     * Notification rule, once per trip: the first TIGHT or SHORT verdict, then once more only when
     * a notified TIGHT worsens to SHORT. [notified] = the level already notified this trip, or null.
     */
    fun shouldNotify(notified: RangeLevel?, level: RangeLevel): Boolean = when (notified) {
        null, RangeLevel.OK -> level != RangeLevel.OK
        RangeLevel.TIGHT -> level == RangeLevel.SHORT
        RangeLevel.SHORT -> false
    }
}

/**
 * One navigation trip's range check, as NavBridge runs it in the background: hysteresis from one
 * update to the next and the once-per-trip notification rule ([RangeCheck.shouldNotify]).
 *  - [update] on every route update: returns the verdict to notify about now, or null. The caller
 *    posts it (unless alerts are off or the demo runs) and then calls [notified].
 *  - [end] when navigation ends: the verdict and hysteresis reset. What was notified is kept for
 *    [RESUME_MS], so Maps re-posting its notification (or the listener rebinding) a moment later
 *    doesn't count as a new trip and notify again.
 * A missing reading or route distance mid-trip clears [verdict] but keeps the level for hysteresis.
 * Not thread-safe (NavBridge: main thread). [nowMs] is any monotonic clock.
 */
class RangeTrip(private val reserveKm: Double = RangeCheck.RESERVE_KM) {
    /** Latest verdict; null before the first, when an input is missing, and after [end]. */
    var verdict: RangeVerdict? = null; private set
    /** Level used as `previous` for the next check; null before the first verdict and after [end]. */
    var level: RangeLevel? = null; private set
    /** True between the first [update] and [end]. */
    var active = false; private set

    private var notifiedLevel: RangeLevel? = null
    private var endedAt: Long? = null

    fun update(remainingKm: Double?, range: RangeEstimate?, nowMs: Long): RangeVerdict? {
        if (!active) start(nowMs)
        val next = RangeCheck.evaluate(remainingKm, range, level, reserveKm)
        verdict = next
        if (next == null) return null
        level = next.level
        return next.takeIf { RangeCheck.shouldNotify(notifiedLevel, it.level) }
    }

    /** The notification for [verdict] was posted. */
    fun notified(verdict: RangeVerdict) { notifiedLevel = verdict.level }

    fun end(nowMs: Long) {
        if (!active) return
        active = false
        verdict = null
        level = null
        endedAt = nowMs
    }

    private fun start(nowMs: Long) {
        active = true
        val ended = endedAt
        if (ended == null || nowMs - ended !in 0..RESUME_MS) notifiedLevel = null
        endedAt = null
    }

    companion object {
        /** Navigation that resumes within this time of ending is the same trip for notifications. */
        const val RESUME_MS = 5 * 60_000L
    }
}
