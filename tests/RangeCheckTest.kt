package dev.codesurgen.ruta

import kotlin.math.floor

/* Host tests for RangeCheck.kt (2.2 F-RANGE): verdict, labels, hysteresis, notification rule, per-trip state. */

private fun est(km: Double, estimated: Boolean = true) = RangeEstimate(km, estimated, kmPerPercent = 1.12)

fun main() {
    verdicts()
    labels()
    unknowns()
    hysteresis()
    stability()
    noFlicker()
    notifyRule()
    trip()
    println("RangeCheckTest: all checks passed")
}

private fun verdicts() {
    check(RangeCheck.RESERVE_KM == 5.0 && RangeCheck.HYSTERESIS_KM == 1.0)
    // OK: range − route ≥ 5 km; TIGHT: 0 ≤ spare < 5; SHORT: range < route.
    val ok = RangeCheck.evaluate(18.0, est(30.0))!!
    check(ok.level == RangeLevel.OK && ok.spareKm == 12.0 && ok.remainingKm == 18.0 && ok.rangeKm == 30.0 && ok.estimated)
    check(RangeCheck.evaluate(27.0, est(30.0))!!.level == RangeLevel.TIGHT)
    check(RangeCheck.evaluate(34.0, est(30.0))!!.let { it.level == RangeLevel.SHORT && it.spareKm == -4.0 })
    // Lines: exactly the reserve is OK, exactly zero spare is TIGHT, anything below zero is SHORT.
    check(RangeCheck.evaluate(25.0, est(30.0))!!.level == RangeLevel.OK)
    check(RangeCheck.evaluate(30.0, est(30.0))!!.level == RangeLevel.TIGHT)
    check(RangeCheck.evaluate(30.01, est(30.0))!!.level == RangeLevel.SHORT)
    // A decoded range is passed through as not estimated.
    check(!RangeCheck.evaluate(10.0, est(63.5, estimated = false))!!.estimated)
    // Custom reserve; a negative or non-finite reserve is 0 / the default.
    check(RangeCheck.evaluate(22.0, est(30.0), reserveKm = 10.0)!!.level == RangeLevel.TIGHT)
    check(RangeCheck.evaluate(30.0, est(30.0), reserveKm = -3.0)!!.level == RangeLevel.OK)
    check(RangeCheck.evaluate(27.0, est(30.0), reserveKm = Double.NaN)!!.level == RangeLevel.TIGHT)
    // Arrived (0 km left) with charge left: OK; with an empty pack at the destination: TIGHT, 0 spare.
    check(RangeCheck.evaluate(0.0, est(12.0))!!.level == RangeLevel.OK)
    check(RangeCheck.evaluate(0.0, est(0.0))!!.let { it.level == RangeLevel.TIGHT && it.label == "Tight · 0 km spare" })
}

private fun labels() {
    check(RangeCheck.evaluate(18.0, est(30.0))!!.label == "12 km spare")
    check(RangeCheck.evaluate(27.0, est(30.0))!!.label == "Tight · 3 km spare")
    check(RangeCheck.evaluate(34.0, est(30.0))!!.label == "Short by 4 km")
    // Cautious rounding: spare down, shortfall up; "Short by" is never 0.
    check(RangeCheck.evaluate(17.1, est(30.0))!!.label == "12 km spare")       // 12.9
    check(RangeCheck.evaluate(26.2, est(30.0))!!.label == "Tight · 3 km spare") // 3.8
    check(RangeCheck.evaluate(33.2, est(30.0))!!.label == "Short by 4 km")      // −3.2
    check(RangeCheck.evaluate(30.05, est(30.0))!!.label == "Short by 1 km")     // −0.05
    check(RangeCheck.evaluate(29.6, est(30.0))!!.label == "Tight · 0 km spare") // 0.4
    val v = RangeVerdict(RangeLevel.SHORT, -0.0, 1.0, 1.0, true)
    check(v.shortWholeKm == 1 && v.spareWholeKm == 0)
}

private fun unknowns() {
    check(RangeCheck.evaluate(null, est(30.0)) == null)
    check(RangeCheck.evaluate(12.0, null) == null)
    check(RangeCheck.evaluate(Double.NaN, est(30.0)) == null && RangeCheck.evaluate(Double.POSITIVE_INFINITY, est(30.0)) == null)
    check(RangeCheck.evaluate(12.0, est(Double.NaN)) == null)
    // Negative inputs are used as 0.
    check(RangeCheck.evaluate(-2.0, est(10.0))!!.let { it.remainingKm == 0.0 && it.spareKm == 10.0 })
    check(RangeCheck.evaluate(3.0, est(-1.0))!!.let { it.rangeKm == 0.0 && it.level == RangeLevel.SHORT && it.label == "Short by 3 km" })
}

private fun hysteresis() {
    val ok = RangeLevel.OK; val tight = RangeLevel.TIGHT; val short = RangeLevel.SHORT
    // OK holds down to reserve − 1 (4 km), then TIGHT.
    check(RangeCheck.level(4.5, ok) == ok && RangeCheck.level(4.0, ok) == ok && RangeCheck.level(3.99, ok) == tight)
    // TIGHT holds up to reserve + 1 (6 km), then OK.
    check(RangeCheck.level(5.5, tight) == tight && RangeCheck.level(5.99, tight) == tight && RangeCheck.level(6.0, tight) == ok)
    // TIGHT holds down to −1 km before turning SHORT; shown as "no spare" meanwhile (never "km spare" under the route).
    check(RangeCheck.level(-0.5, tight) == tight && RangeCheck.level(-1.0, tight) == tight && RangeCheck.level(-1.01, tight) == short)
    check(RangeCheck.evaluate(30.5, est(30.0), tight)!!.let { it.label == "Tight · no spare" && it.noSpare && it.spareWholeKm == 0 })
    // A reroute adds 5 km to an OK trip: 25.5 km left on 25 km of range holds TIGHT, without spare.
    check(RangeCheck.evaluate(25.5, est(25.0), ok)!!.let { it.level == tight && it.label == "Tight · no spare" })
    check(RangeCheck.evaluate(29.6, est(30.0), tight)!!.let { !it.noSpare && it.label == "Tight · 0 km spare" })
    // SHORT ends as soon as the range covers the route; OK needs reserve + 1.
    check(RangeCheck.level(-0.2, short) == short && RangeCheck.level(0.0, short) == tight && RangeCheck.level(5.5, short) == tight)
    check(RangeCheck.level(6.0, short) == ok)
    // Big jumps (a new destination) skip levels.
    check(RangeCheck.level(-1.5, ok) == short && RangeCheck.level(-0.5, ok) == tight && RangeCheck.level(12.0, short) == ok)
    // A tiny reserve never keeps OK below zero spare.
    check(RangeCheck.level(-0.3, ok, reserveKm = 0.5) == tight)
    check(RangeCheck.level(-0.3, ok, reserveKm = 0.0) == tight && RangeCheck.level(-1.2, ok, reserveKm = 0.0) == short)
}

/** Every level's label agrees with its sign, and re-checking a verdict with itself changes nothing. */
private fun stability() {
    val previous = listOf(null, RangeLevel.OK, RangeLevel.TIGHT, RangeLevel.SHORT)
    for (reserve in listOf(5.0, 0.0, 0.5, 2.0, 10.0)) {
        var s = -12.0
        while (s <= 18.0) {
            for (p in previous) {
                val level = RangeCheck.level(s, p, reserve)
                check(RangeCheck.level(s, level, reserve) == level) { "unstable at spare $s from $p (reserve $reserve)" }
                if (level == RangeLevel.SHORT) check(s < 0.0) { "SHORT with spare $s" }
                if (level == RangeLevel.OK) check(s >= 0.0) { "OK with spare $s" }
                // Without a previous level the verdict is exactly the definition.
                if (p == null) check(level == when { s >= reserve -> RangeLevel.OK; s >= 0.0 -> RangeLevel.TIGHT; else -> RangeLevel.SHORT })
            }
            s += 0.05
        }
    }
}

/**
 * Riding at exactly the estimated efficiency: the range drops in whole-km steps (charge × 1.12,
 * rounded down) while the route left drops smoothly, so the raw spare saws by up to ~1 km around a
 * line. With hysteresis the level changes at most once.
 */
private fun noFlicker() {
    for (target in listOf(5.0, 0.0)) {
        var soc = 40.0
        var remaining = floor(soc * 1.12) - target + 0.45 // the spare saws around the line
        var previous: RangeLevel? = null
        var changes = 0
        var raw = 0
        var rawPrevious: RangeLevel? = null
        repeat(400) {
            val range = RangeModel.estimate(soc, null, 1.12)
            val verdict = RangeCheck.evaluate(remaining, range, previous)!!
            if (previous != null && verdict.level != previous) changes++
            previous = verdict.level
            val unfiltered = RangeCheck.evaluate(remaining, range)!!.level
            if (rawPrevious != null && unfiltered != rawPrevious) raw++
            rawPrevious = unfiltered
            soc -= 0.01                 // 0.01 % per step …
            remaining -= 0.01 * 1.12    // … and the matching distance
        }
        check(raw >= 4) { "test setup: the raw verdict should flicker around $target (changed $raw times)" }
        check(changes <= 1) { "hysteresis: level changed $changes times around $target" }
    }
}

private fun notifyRule() {
    val ok = RangeLevel.OK; val tight = RangeLevel.TIGHT; val short = RangeLevel.SHORT
    check(!RangeCheck.shouldNotify(null, ok) && RangeCheck.shouldNotify(null, tight) && RangeCheck.shouldNotify(null, short))
    check(!RangeCheck.shouldNotify(tight, ok) && !RangeCheck.shouldNotify(tight, tight) && RangeCheck.shouldNotify(tight, short))
    check(!RangeCheck.shouldNotify(short, ok) && !RangeCheck.shouldNotify(short, tight) && !RangeCheck.shouldNotify(short, short))
    check(RangeCheck.shouldNotify(ok, tight) && !RangeCheck.shouldNotify(ok, ok))
}

private fun trip() {
    val trip = RangeTrip()
    var t = 1_000L
    check(!trip.active && trip.verdict == null && trip.level == null)
    // OK twice: nothing to notify.
    check(trip.update(18.0, est(30.0), t) == null && trip.active && trip.level == RangeLevel.OK)
    check(trip.update(17.0, est(29.0), t) == null)
    // Drops to TIGHT (past the 4 km hysteresis line): notify once.
    val first = trip.update(26.5, est(30.0), t)!!
    check(first.level == RangeLevel.TIGHT && first.label == "Tight · 3 km spare")
    // Not posted yet (alerts off, or the demo runs): offered again on the next update.
    check(trip.update(26.5, est(30.0), t) != null)
    trip.notified(first)
    check(trip.update(26.0, est(30.0), t) == null)
    // A missing reading clears the verdict but keeps the level.
    check(trip.update(26.0, null, t) == null && trip.verdict == null && trip.level == RangeLevel.TIGHT)
    // −0.5 km stays TIGHT (hysteresis), −2 km worsens to SHORT: notify again.
    check(trip.update(30.5, est(30.0), t) == null && trip.level == RangeLevel.TIGHT)
    val second = trip.update(32.0, est(30.0), t)!!
    check(second.level == RangeLevel.SHORT && second.label == "Short by 2 km")
    trip.notified(second)
    // Never a third time: better and worse again stay quiet.
    check(trip.update(26.0, est(30.0), t) == null && trip.level == RangeLevel.TIGHT)
    check(trip.update(20.0, est(30.0), t) == null && trip.level == RangeLevel.OK)
    check(trip.update(33.0, est(30.0), t) == null && trip.level == RangeLevel.SHORT)

    // Navigation ends: verdict and hysteresis reset.
    t += 60_000
    trip.end(t)
    check(!trip.active && trip.verdict == null && trip.level == null)
    trip.end(t + 5) // twice is harmless
    // Maps comes back within 5 min (re-posted notification, listener rebind): same trip, stays quiet.
    t += 30_000
    check(trip.update(33.0, est(30.0), t) == null && trip.level == RangeLevel.SHORT)
    // Hysteresis starts fresh on resume: after SHORT, 0.4 km spare is plain TIGHT (it would hold
    // SHORT only within a trip), and TIGHT after a notified SHORT stays quiet.
    t += 10_000
    trip.end(t)
    check(trip.update(29.6, est(30.0), t + 1) == null && trip.level == RangeLevel.TIGHT)
    check(RangeCheck.level(-0.4, RangeLevel.SHORT) == RangeLevel.SHORT && RangeCheck.level(-0.4, null) == RangeLevel.SHORT)
    trip.end(t + 2)

    // A new trip more than 5 min later notifies again from scratch.
    val next = RangeTrip()
    next.update(27.0, est(30.0), 0L)!!.let { next.notified(it) }
    next.end(10_000L)
    check(next.update(27.0, est(30.0), 10_000L + RangeTrip.RESUME_MS + 1) != null)
    // … and within the window it doesn't.
    val resumed = RangeTrip()
    resumed.update(27.0, est(30.0), 0L)!!.let { resumed.notified(it) }
    resumed.end(10_000L)
    check(resumed.update(27.0, est(30.0), 10_000L + RangeTrip.RESUME_MS) == null)
    // Within the window a worse level still notifies (TIGHT was notified, now SHORT).
    check(resumed.update(32.0, est(30.0), 10_000L + RangeTrip.RESUME_MS) != null)
    // A clock that went backwards is not "within the window".
    val odd = RangeTrip()
    odd.update(27.0, est(30.0), 50_000L)!!.let { odd.notified(it) }
    odd.end(50_000L)
    check(odd.update(27.0, est(30.0), 10_000L) != null)
    // The first check of a trip has no previous level: exact lines.
    val fresh = RangeTrip()
    check(fresh.update(25.0, est(30.0), 0L) == null && fresh.level == RangeLevel.OK)
}
