package dev.codesurgen.ruta

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/*
 * Range (km left). Pure Kotlin (no android.* imports): host-tested by tests/RangeTest.kt.
 * SOC everywhere here is the pack-0 (scooter total) charge in percent, the same value the TFT and
 * ride tracking use.
 */

/**
 * Range for the UI. [km] is whole kilometres when [estimated] (charge × [kmPerPercent], rounded down
 * like the TFT); a range decoded from the scooter is passed through unchanged with [estimated] = false.
 * [kmPerPercent] is the efficiency the estimate used (the learned or default value, clamped) — the one
 * number to show as "Efficiency"; [learned] is false while it is still the default (show "est.").
 */
data class RangeEstimate(val km: Double, val estimated: Boolean, val kmPerPercent: Double, val learned: Boolean = false)

/**
 * The km per charge point the range estimate uses: learned from [rides] qualifying rides (≥ 3 km,
 * ≥ 2 points used), or [RangeModel.DEFAULT_KM_PER_PERCENT] while there are none. Always clamped.
 */
data class RangeEfficiency(val kmPerPercent: Double, val rides: Int) {
    /** False while this is still the default; the UI labels it "est.". */
    val learned: Boolean get() = rides > 0

    companion object {
        val DEFAULT = RangeEfficiency(RangeModel.DEFAULT_KM_PER_PERCENT, 0)
    }
}

object RangeModel {
    /** VX2 TFT: 64 km at 57 %, 62 km at 55 %, 61 km at 55 %. */
    const val DEFAULT_KM_PER_PERCENT = 1.12
    const val MIN_KM_PER_PERCENT = 0.7
    const val MAX_KM_PER_PERCENT = 1.6
    /** Rides that teach the model: at least this far and this much charge used. */
    const val LEARN_MIN_KM = 3.0
    const val LEARN_MIN_USED_PERCENT = 2.0
    /** Weight of the newest ride in the moving average. */
    const val LEARN_ALPHA = 0.3
    /** A decoded range outside 0..this is a misread field and ignored. */
    const val MAX_DECODED_KM = 300.0

    /** [kmPerPercent] clamped to 0.7..1.6; non-finite or non-positive → the default. */
    fun clampKmPerPercent(kmPerPercent: Double): Double =
        if (kmPerPercent.isFinite() && kmPerPercent > 0) kmPerPercent.coerceIn(MIN_KM_PER_PERCENT, MAX_KM_PER_PERCENT)
        else DEFAULT_KM_PER_PERCENT

    /**
     * Range from the scooter-total [socPercent] (0..100) and [kmPerPercent]. A plausible [decodedKm]
     * (finite, 0..300) wins and is not estimated. Null when neither is usable.
     */
    fun estimate(socPercent: Double?, decodedKm: Double?, kmPerPercent: Double): RangeEstimate? =
        estimate(socPercent, decodedKm, RangeEfficiency(kmPerPercent, 0))

    /** As above with the store's [efficiency] (TelemetryStore.efficiency), so the estimate carries [RangeEstimate.learned]. */
    fun estimate(socPercent: Double?, decodedKm: Double?, efficiency: RangeEfficiency): RangeEstimate? {
        val k = clampKmPerPercent(efficiency.kmPerPercent)
        if (decodedKm != null && decodedKm.isFinite() && decodedKm in 0.0..MAX_DECODED_KM)
            return RangeEstimate(decodedKm, estimated = false, kmPerPercent = k, learned = efficiency.learned)
        if (socPercent == null || !socPercent.isFinite()) return null
        return RangeEstimate(floor(socPercent.coerceIn(0.0, 100.0) * k), estimated = true, kmPerPercent = k, learned = efficiency.learned)
    }

    /** km per charge point of one ride, or null unless it covered ≥ 3 km and used ≥ 2 points. */
    fun rideSample(distanceM: Double, socStart: Double?, socEnd: Double?): Double? {
        if (socStart == null || socEnd == null || !distanceM.isFinite()) return null
        val used = socStart - socEnd
        val km = distanceM / 1000
        if (!used.isFinite() || used < LEARN_MIN_USED_PERCENT || km < LEARN_MIN_KM) return null
        return km / used
    }

    /**
     * Learned km per charge point: an exponentially weighted moving average (newest ride weighs
     * [LEARN_ALPHA]) over qualifying rides in start order, starting from [DEFAULT_KM_PER_PERCENT].
     * Each sample and the result are clamped to 0.7..1.6.
     */
    fun learnedKmPerPercent(rides: List<Ride>): Double = efficiency(rides).kmPerPercent

    /** [learnedKmPerPercent] with the number of rides it learned from (0 → the default, "est."). */
    fun efficiency(rides: List<Ride>): RangeEfficiency {
        var value = DEFAULT_KM_PER_PERCENT
        var samples = 0
        for (ride in rides.sortedBy { it.start }) {
            val sample = rideSample(ride.distanceM, ride.socStart, ride.socEnd) ?: continue
            value += LEARN_ALPHA * (sample.coerceIn(MIN_KM_PER_PERCENT, MAX_KM_PER_PERCENT) - value)
            samples++
        }
        return RangeEfficiency(clampKmPerPercent(value), samples)
    }

    /** Charge history (pack 0) as a range series in km with the same efficiency for every point. */
    fun series(soc: List<BucketPoint>, kmPerPercent: Double): List<BucketPoint> {
        val k = clampKmPerPercent(kmPerPercent)
        return soc.map { BucketPoint(it.time, it.avg * k, it.min * k, it.max * k, it.count) }
    }
}

/**
 * Time to a full charge for Home's "Full in" tile: linear at the live charge rate (Li-ion tapers near
 * full, so this is a lower bound; the rate is the session's measured average). Null when unknown or
 * not charging: no charge value, no rate, a rate below [MIN_PERCENT_PER_HOUR], or an answer beyond
 * [MAX_MINUTES]. 0 at 100 %.
 */
object ChargeEta {
    /** Below this the pack is not really charging (balancing, trickle or measurement noise). */
    const val MIN_PERCENT_PER_HOUR = 1.0
    /** Longer than a day from any real charger: the rate is wrong, show nothing. */
    const val MAX_MINUTES = 24 * 60
    /** A session's average rate counts once it has run this long (Home's "Full in" uses the same 10 min). */
    const val RATE_AFTER_S = 600L

    fun minutesToFull(socPercent: Double?, percentPerHour: Double?): Int? {
        if (socPercent == null || !socPercent.isFinite() || percentPerHour == null || !percentPerHour.isFinite()) return null
        if (percentPerHour < MIN_PERCENT_PER_HOUR) return null
        val missing = 100.0 - socPercent.coerceIn(0.0, 100.0)
        if (missing <= 0.0) return 0
        val minutes = ceil(missing / percentPerHour * 60.0 - 1e-9).toInt()
        return minutes.takeIf { it <= MAX_MINUTES }
    }

    /**
     * [minutesToFull] at [session]'s average rate, from the live charge [socPercent] (the session's
     * latest rise when null). Null until the session has run [RATE_AFTER_S].
     */
    fun minutesToFull(socPercent: Double?, session: ChargeSession): Int? =
        minutesToFull(socPercent ?: session.socEnd, session.percentPerHour.takeIf { session.durationS >= RATE_AFTER_S })

    /**
     * Notification wording for [minutes] from [minutesToFull]: "Full in 45 min", "Full in 1 h 05 min"
     * (the same duration format as rides), "Full" at 100 %; null when unknown.
     */
    fun phrase(minutes: Int?): String? = when {
        minutes == null || minutes < 0 -> null
        minutes == 0 -> "Full"
        else -> "Full in ${RideMath.formatDuration(minutes * 60L)}"
    }
}

/** What a charging session announces ([ChargeWatch]). */
sealed class ChargeEvent {
    /** The charge reached the target: [percent] is the charge then, rounded down; 100 = full. */
    data class Reached(val percent: Int) : ChargeEvent()

    /** Charging ended below the target while the scooter stayed connected: [percent] = where it stopped, rounded down. */
    data class Stopped(val percent: Int) : ChargeEvent()
}

/**
 * Charge alerts from the live charging session ([RideState.charging]), fed on every [RideLive] change
 * in the process that holds the scooter link (RideService). Nothing is promised without the link: a
 * session only exists while connected, and one ended by a link loss, a ride or a stale charge says
 * nothing. One instance per service; the switches (Alerts) are applied by the caller.
 *  - [ChargeEvent.Reached] once per session and target, when the charge first crosses the target
 *    between two updates (a session first seen above it, or a target set below the current charge,
 *    says nothing). 100 % is also reached when charging ends at [FULL_PERCENT] or more: chargers
 *    stop adding there while the BMS balances.
 *  - [ChargeEvent.Stopped] when the session ends by itself (no rise for ChargeDetector.STALL_MS) with
 *    the link up, not riding and a fresh charge, below the target ([FULL_PERCENT] for 100 %).
 */
class ChargeWatch {
    /** The session as last seen; kept across the gap of a paused session (a resumed one keeps its start). */
    private var last: ChargeSession? = null
    /** [last] has ended and was judged; a resumed session clears it. */
    private var ended = true
    /** (session start, target) already announced as reached. */
    private var reached: Pair<Long, Int>? = null

    fun update(session: ChargeSession?, socPercent: Double?, linkReady: Boolean, riding: Boolean, target: Int): ChargeEvent? {
        val previous = last
        if (session == null) {
            if (previous == null || ended) return null
            ended = true
            if (!linkReady || riding || socPercent == null || !socPercent.isFinite()) return null
            val stoppedAt = previous.socEnd
            if (target >= 100 && stoppedAt >= FULL_PERCENT) return reach(previous, target, 100)
            return if (stoppedAt < stopLine(target)) ChargeEvent.Stopped(floorPercent(stoppedAt)) else null
        }
        val before = previous?.takeIf { it.start == session.start }?.socEnd ?: session.socStart
        last = session
        ended = false
        val line = target.toDouble()
        if (before < line && session.socEnd >= line) return reach(session, target, if (target >= 100) 100 else floorPercent(session.socEnd))
        return null
    }

    private fun reach(session: ChargeSession, target: Int, percent: Int): ChargeEvent? {
        val key = session.start to target
        if (reached == key) return null
        reached = key
        return ChargeEvent.Reached(percent)
    }

    private fun stopLine(target: Int): Double = if (target >= 100) FULL_PERCENT else target.toDouble()

    private fun floorPercent(value: Double): Int = floor(value).toInt().coerceIn(0, 100)

    companion object {
        /** At or above this the pack counts as full when charging ends (top-of-charge balancing). */
        const val FULL_PERCENT = 99.0
    }
}

/** One alert's words: the notification's [title] and [text] (none when null), and [line], its one-line in-app (snackbar) form. */
data class AlertMessage(val title: String, val text: String?, val line: String)

/**
 * Wording of the rider alerts (Alerts posts them). Charge is shown rounded down like the TFT; range
 * in whole km, rounded like Home, Navigate and the ongoing notification ("est." when estimated);
 * route distance with one decimal below 10 km.
 */
object AlertText {
    /** "Charged to 80 %" / "Fully charged", "Range 90 km est."; one line "Charged to 80 % · range 90 km est.". */
    fun chargeReached(percent: Int, rangeKm: Double?, estimated: Boolean): AlertMessage {
        val title = if (percent >= 100) "Fully charged" else "Charged to ${percent.coerceAtLeast(0)} %"
        val range = rangeKm?.takeIf { it.isFinite() && it >= 0 }?.let { "range ${it.roundToInt()} km" + if (estimated) " est." else "" }
        return AlertMessage(title, range?.replaceFirstChar { it.uppercaseChar() }, if (range != null) "$title · $range" else title)
    }

    /** "Charging stopped at 63 %", and why Ruta thinks so: "No rise for 10 min while connected". */
    fun chargeStopped(percent: Int, stallMinutes: Long = ChargeDetector.STALL_MS / 60_000): AlertMessage {
        val title = "Charging stopped at ${percent.coerceIn(0, 100)} %"
        return AlertMessage(title, "No rise for $stallMinutes min while connected", title)
    }

    /**
     * Range against the Maps route ([short] = the verdict is SHORT, else TIGHT), with [wholeKm] the
     * verdict's own whole km (RangeVerdict.shortWholeKm / spareWholeKm, so the alert and the Navigate
     * chip never disagree): "Short by 4 km", "Charge before you arrive · 22 km to go, 18 km range
     * est."; "Range is tight · 3 km spare", "22 km to go, 25 km range est.".
     */
    fun range(short: Boolean, wholeKm: Int, remainingKm: Double, rangeKm: Double, estimated: Boolean): AlertMessage {
        val facts = "${km(remainingKm)} km to go, ${rangeKm.coerceAtLeast(0.0).roundToInt()} km range" + if (estimated) " est." else ""
        if (short) {
            val by = wholeKm.coerceAtLeast(1)
            return AlertMessage("Short by $by km", "Charge before you arrive · $facts", "Short by $by km — charge before you arrive")
        }
        // Tight held just under the route (hysteresis): no spare to speak of ([RangeVerdict.noSpare]).
        val title = if (rangeKm < remainingKm) "Range is tight · no spare" else "Range is tight · ${wholeKm.coerceAtLeast(0)} km spare"
        return AlertMessage(title, facts, title)
    }

    private fun km(value: Double): String {
        val v = value.coerceAtLeast(0.0)
        return if (v < 10) number(v, 1) else number(v, 0)
    }
}
