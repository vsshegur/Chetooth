package dev.codesurgen.ruta

import kotlin.math.abs

private fun near(a: Double, b: Double, tolerance: Double = 1e-9) = abs(a - b) <= tolerance

private fun rideAt(start: Long, distanceM: Double, socStart: Double?, socEnd: Double?) =
    Ride(start, start, start + 600_000, 17.4, 78.3, 17.5, 78.4, null, null, distanceM, 600, 600, 40.0, 30.0, socStart, socEnd)

fun main() {
    estimates()
    learning()
    efficiency()
    chargeEta()
    chargeEtaSession()
    chargeWatch()
    alertText()
    bmsState()
    println("RangeTest: all checks passed")
}

private fun estimates() {
    val d = RangeModel.DEFAULT_KM_PER_PERCENT
    check(d == 1.12)
    // TFT on the user's VX2: 64 km at 57 % (SOC 57.81), 62 km at 55 %; whole km, rounded down.
    check(RangeModel.estimate(57.81, null, d) == RangeEstimate(64.0, estimated = true, kmPerPercent = 1.12))
    check(RangeModel.estimate(55.5, null, d)!!.km == 62.0)
    check(RangeModel.estimate(55.0, null, d)!!.km == 61.0)
    check(RangeModel.estimate(100.0, null, d)!!.km == 112.0 && RangeModel.estimate(0.0, null, d)!!.km == 0.0)
    check(RangeModel.estimate(0.5, null, d)!!.km == 0.0)
    // Out-of-range charge is clamped; no charge and no decoded range → nothing to show.
    check(RangeModel.estimate(140.0, null, d)!!.km == 112.0 && RangeModel.estimate(-3.0, null, d)!!.km == 0.0)
    check(RangeModel.estimate(null, null, d) == null && RangeModel.estimate(Double.NaN, null, d) == null)
    // A decoded value wins, unrounded, and is not estimated.
    check(RangeModel.estimate(57.81, 63.5, d) == RangeEstimate(63.5, estimated = false, kmPerPercent = 1.12))
    check(RangeModel.estimate(null, 70.0, d)!!.let { it.km == 70.0 && !it.estimated })
    // Implausible decoded values fall back to the estimate.
    check(RangeModel.estimate(50.0, -1.0, d)!!.let { it.estimated && it.km == 56.0 })
    check(RangeModel.estimate(50.0, 999.0, d)!!.estimated && RangeModel.estimate(50.0, Double.NaN, d)!!.estimated)
    check(RangeModel.estimate(null, Double.POSITIVE_INFINITY, d) == null)
    // Efficiency is clamped to 0.7..1.6; garbage → default.
    check(RangeModel.estimate(50.0, null, 3.0)!!.let { it.km == 80.0 && it.kmPerPercent == 1.6 })
    check(RangeModel.estimate(50.0, null, 0.2)!!.let { it.km == 35.0 && it.kmPerPercent == 0.7 })
    check(RangeModel.estimate(50.0, null, Double.NaN)!!.kmPerPercent == d && RangeModel.estimate(50.0, null, -1.0)!!.kmPerPercent == d)
    check(RangeModel.clampKmPerPercent(1.3) == 1.3)
    // Range series for charts: every bucket field scaled.
    val series = RangeModel.series(listOf(BucketPoint(5, 50.0, 40.0, 60.0, 3)), 1.2)
    check(series.single().let { it.time == 5L && near(it.avg, 60.0) && near(it.min, 48.0) && near(it.max, 72.0) && it.count == 3 })
}

private fun learning() {
    val d = RangeModel.DEFAULT_KM_PER_PERCENT
    // Thresholds: ≥ 3 km and ≥ 2 points used.
    check(RangeModel.rideSample(6_000.0, 60.0, 55.0) == 1.2)
    check(RangeModel.rideSample(2_999.0, 60.0, 55.0) == null)
    check(RangeModel.rideSample(6_000.0, 60.0, 58.5) == null)
    check(RangeModel.rideSample(6_000.0, null, 55.0) == null && RangeModel.rideSample(6_000.0, 55.0, 60.0) == null)
    check(RangeModel.rideSample(3_000.0, 60.0, 58.0) == 1.5)

    check(RangeModel.learnedKmPerPercent(emptyList()) == d)
    // Unqualified rides change nothing.
    check(RangeModel.learnedKmPerPercent(listOf(rideAt(1, 2_000.0, 60.0, 50.0), rideAt(2, 9_000.0, 60.0, 59.0), rideAt(3, 9_000.0, null, null))) == d)
    // One ride at 1.32 km/%: 1.12 + 0.3 × 0.2 = 1.18.
    check(near(RangeModel.learnedKmPerPercent(listOf(rideAt(1, 6_600.0, 60.0, 55.0))), 1.18))
    // EWMA in start order regardless of list order: 1.12 → 1.18 (1.32) → 1.126 (1.0).
    val two = listOf(rideAt(2, 5_000.0, 55.0, 50.0), rideAt(1, 6_600.0, 60.0, 55.0))
    check(near(RangeModel.learnedKmPerPercent(two), 1.18 + 0.3 * (1.0 - 1.18)))
    // Outlier samples are clamped before averaging (20 km on 2 % = 10 km/% counts as 1.6).
    check(near(RangeModel.learnedKmPerPercent(listOf(rideAt(1, 20_000.0, 60.0, 58.0))), 1.12 + 0.3 * (1.6 - 1.12)))
    check(near(RangeModel.learnedKmPerPercent(listOf(rideAt(1, 3_000.0, 60.0, 40.0))), 1.12 + 0.3 * (0.7 - 1.12)))
    // Many consistent rides converge on their efficiency and stay within bounds.
    val steady = (1..40).map { rideAt(it.toLong(), 8_000.0, 60.0, 55.0) } // 1.6 km/%
    check(near(RangeModel.learnedKmPerPercent(steady), 1.6, 1e-3) && RangeModel.learnedKmPerPercent(steady) <= 1.6)
    val thirsty = (1..40).map { rideAt(it.toLong(), 3_000.0, 60.0, 55.0) } // 0.6 → clamped 0.7
    check(near(RangeModel.learnedKmPerPercent(thirsty), 0.7, 1e-3) && RangeModel.learnedKmPerPercent(thirsty) >= 0.7)
}

private fun efficiency() {
    val d = RangeModel.DEFAULT_KM_PER_PERCENT
    // No qualifying ride: the default, not learned ("est."). Two short first rides (368 m and
    // 863 m, 14 → 10.8 % and 26.7 → 13.7 %) are too short to teach anything.
    check(RangeModel.efficiency(emptyList()) == RangeEfficiency(d, 0) && !RangeEfficiency.DEFAULT.learned)
    val short = listOf(rideAt(1, 368.0, 14.0, 10.8), rideAt(2, 863.0, 26.7, 13.7))
    check(RangeModel.efficiency(short) == RangeEfficiency.DEFAULT)
    // One qualifying ride among them: learned, and the very number the estimate uses.
    val learned = RangeModel.efficiency(short + rideAt(3, 6_600.0, 60.0, 55.0))
    check(learned.learned && learned.rides == 1 && near(learned.kmPerPercent, 1.18))
    check(learned.kmPerPercent == RangeModel.learnedKmPerPercent(short + rideAt(3, 6_600.0, 60.0, 55.0)))
    val estimate = RangeModel.estimate(57.81, null, learned)!!
    check(estimate.learned && estimate.estimated && estimate.kmPerPercent == learned.kmPerPercent && estimate.km == 68.0) // floor(57.81 × 1.18)
    check(RangeModel.estimate(57.81, null, RangeEfficiency.DEFAULT) == RangeEstimate(64.0, estimated = true, kmPerPercent = d, learned = false))
    check(RangeModel.estimate(57.81, 63.5, learned)!!.let { !it.estimated && it.learned && it.km == 63.5 })
    // The Double overload stays "not learned" (callers that only know a number).
    check(!RangeModel.estimate(57.81, null, 1.18)!!.learned)
    // An out-of-bounds efficiency is clamped the same way in the estimate and its kmPerPercent.
    check(RangeModel.estimate(50.0, null, RangeEfficiency(3.0, 4))!!.let { it.kmPerPercent == 1.6 && it.km == 80.0 && it.learned })
}

private fun chargeEta() {
    // 55 % at 20 %/h → 45 points → 2 h 15 min; partial minutes round up.
    check(ChargeEta.minutesToFull(55.0, 20.0) == 135)
    check(ChargeEta.minutesToFull(80.0, 40.0) == 30) // exact: no extra minute
    check(ChargeEta.minutesToFull(99.9, 20.0) == 1)
    check(ChargeEta.minutesToFull(57.81, 18.0) == 141) // 42.19 / 18 h = 140.6 min
    // Full now; values outside 0..100 are clamped.
    check(ChargeEta.minutesToFull(100.0, 20.0) == 0 && ChargeEta.minutesToFull(104.0, 20.0) == 0)
    check(ChargeEta.minutesToFull(-5.0, 50.0) == 120)
    // Unknown or not charging → null.
    check(ChargeEta.minutesToFull(null, 20.0) == null && ChargeEta.minutesToFull(50.0, null) == null)
    check(ChargeEta.minutesToFull(Double.NaN, 20.0) == null && ChargeEta.minutesToFull(50.0, Double.POSITIVE_INFINITY) == null)
    check(ChargeEta.minutesToFull(50.0, 0.0) == null && ChargeEta.minutesToFull(50.0, -12.0) == null && ChargeEta.minutesToFull(50.0, 0.9) == null)
    // Slower than a day: the rate is wrong, show nothing.
    check(ChargeEta.minutesToFull(0.0, 4.0) == null && ChargeEta.minutesToFull(4.0, 4.0) == 1440)
}

private fun bmsState() {
    check(BmsState.of(4) == BmsState.DRIVE && BmsState.of(4.0) == BmsState.DRIVE)
    check(BmsState.of(0) == BmsState.STANDBY && BmsState.of(2) == BmsState.STANDBY && BmsState.of(2.0) == BmsState.STANDBY)
    check(listOf(1, 3, 5, 7, 11, -1, 255).all { BmsState.of(it) == BmsState.UNKNOWN })
    check(BmsState.of(null as Int?) == BmsState.UNKNOWN && BmsState.of(null as Double?) == BmsState.UNKNOWN)
    check(BmsState.of(Double.NaN) == BmsState.UNKNOWN && BmsState.of(4.5) == BmsState.UNKNOWN)
    check(BmsState.DRIVE.label == "Drive" && BmsState.STANDBY.label == "Standby")
    // The scooter state: any pack driving → drive; otherwise standby when all known packs are.
    check(BmsState.combined(listOf(0.0, 2.0, 2.0)) == BmsState.STANDBY)
    check(BmsState.combined(listOf(0.0, 4.0, 2.0)) == BmsState.DRIVE)
    check(BmsState.combined(listOf(4.0, null, 4.0)) == BmsState.DRIVE)
    check(BmsState.combined(listOf(null, 7.0)) == BmsState.UNKNOWN && BmsState.combined(emptyList()) == BmsState.UNKNOWN)
    check(BmsState.combined(listOf(0.0, 11.0, 2.0)) == BmsState.STANDBY)
}

private const val MIN = 60_000L

private fun session(startMin: Long, endMin: Long, from: Double, to: Double) = ChargeSession(startMin * MIN, endMin * MIN, from, to)

private fun chargeEtaSession() {
    // The notification and Home agree: the session's average rate, only after 10 min of charging.
    check(ChargeEta.RATE_AFTER_S == 600L)
    check(ChargeEta.minutesToFull(60.0, session(0, 9, 55.0, 58.0)) == null)           // 9 min: rate not trusted yet
    check(ChargeEta.minutesToFull(60.0, session(0, 30, 50.0, 60.0)) == 120)          // 20 %/h, 40 points
    check(ChargeEta.minutesToFull(null, session(0, 30, 50.0, 60.0)) == 120)          // no live charge: the latest rise
    check(ChargeEta.minutesToFull(70.0, session(0, 30, 50.0, 60.0)) == 90)           // live charge wins
    check(ChargeEta.minutesToFull(100.0, session(0, 60, 80.0, 100.0)) == 0)
    // Wording: rides' duration format; "Full" at 100 %; nothing when unknown.
    check(ChargeEta.phrase(45) == "Full in 45 min")
    check(ChargeEta.phrase(70) == "Full in 1 h 10 min")
    check(ChargeEta.phrase(65) == "Full in 1 h 05 min")
    check(ChargeEta.phrase(1) == "Full in 1 min")
    check(ChargeEta.phrase(0) == "Full")
    check(ChargeEta.phrase(null) == null && ChargeEta.phrase(-3) == null)
}

private fun chargeWatch() {
    // Crossing the target: once, with the charge then (rounded down).
    run {
        val w = ChargeWatch()
        check(w.update(session(0, 5, 54.0, 78.2), 78.2, true, false, 80) == null)
        check(w.update(session(0, 9, 54.0, 79.9), 79.9, true, false, 80) == null)
        check(w.update(session(0, 12, 54.0, 80.3), 80.3, true, false, 80) == ChargeEvent.Reached(80))
        check(w.update(session(0, 15, 54.0, 81.0), 81.0, true, false, 80) == null)            // once per session
        check(w.update(session(0, 60, 54.0, 92.0), 92.0, true, false, 80) == null)
    }
    // A jump over the line reports the charge it landed on.
    check(ChargeWatch().update(session(0, 6, 78.0, 81.7), 81.7, true, false, 80) == ChargeEvent.Reached(81))
    // First seen above the target (plugged in at 85 %): nothing to announce.
    run {
        val w = ChargeWatch()
        check(w.update(session(0, 5, 85.0, 86.0), 86.0, true, false, 80) == null)
        check(w.update(session(0, 9, 85.0, 88.0), 88.0, true, false, 80) == null)
    }
    // Target changed mid-session: a higher one fires when crossed; one below the charge says nothing.
    run {
        val w = ChargeWatch()
        check(w.update(session(0, 5, 70.0, 80.5), 80.5, true, false, 80) == ChargeEvent.Reached(80))
        check(w.update(session(0, 9, 70.0, 85.0), 85.0, true, false, 90) == null)
        check(w.update(session(0, 30, 70.0, 90.1), 90.1, true, false, 90) == ChargeEvent.Reached(90))
        check(w.update(session(0, 35, 70.0, 91.0), 91.0, true, false, 80) == null)
    }
    // 100 %: at 100.0, or when charging ends at 99 % or more (balancing); never twice.
    run {
        val w = ChargeWatch()
        check(w.update(session(0, 5, 90.0, 99.98), 99.98, true, false, 100) == null)
        check(w.update(session(0, 9, 90.0, 100.0), 100.0, true, false, 100) == ChargeEvent.Reached(100))
        check(w.update(null, 100.0, true, false, 100) == null)
    }
    run {
        val w = ChargeWatch()
        check(w.update(session(0, 5, 90.0, 99.4), 99.4, true, false, 100) == null)
        check(w.update(null, 99.4, true, false, 100) == ChargeEvent.Reached(100))
        check(w.update(null, 99.4, true, false, 100) == null)
    }
    // Ended early (no rise for 10 min, still connected): stopped, below the target only.
    run {
        val w = ChargeWatch()
        check(w.update(session(0, 30, 41.0, 63.7), 63.7, true, false, 80) == null)
        check(w.update(null, 63.6, true, false, 80) == ChargeEvent.Stopped(63))
        check(w.update(null, 63.6, true, false, 80) == null)                                   // judged once
    }
    run {
        val w = ChargeWatch()
        check(w.update(session(0, 30, 70.0, 85.0), 85.0, true, false, 80) == ChargeEvent.Reached(85))  // first seen at 85
        check(w.update(null, 85.0, true, false, 80) == null)                                   // above the target: expected
    }
    run {
        val w = ChargeWatch()
        check(w.update(session(0, 30, 70.0, 97.0), 97.0, true, false, 100) == null)
        check(w.update(null, 97.0, true, false, 100) == ChargeEvent.Stopped(97))              // below full
    }
    // Ended by a link loss, a ride or a stale charge: no promise, no alert.
    for ((ready, riding, soc) in listOf(Triple(false, false, 60.0), Triple(true, true, 60.0), Triple(true, false, null))) {
        val w = ChargeWatch()
        check(w.update(session(0, 30, 41.0, 60.0), 60.0, true, false, 80) == null)
        check(w.update(null, soc, ready, riding, 80) == null)
    }
    // A paused session that resumes (same start) is judged again when it ends again; the target is not re-announced.
    run {
        val w = ChargeWatch()
        check(w.update(session(0, 30, 41.0, 60.0), 60.0, true, false, 80) == null)
        check(w.update(null, 60.0, true, false, 80) == ChargeEvent.Stopped(60))
        check(w.update(session(0, 50, 41.0, 61.0), 61.0, true, false, 80) == null)
        check(w.update(session(0, 90, 41.0, 80.0), 80.0, true, false, 80) == ChargeEvent.Reached(80))
        check(w.update(null, 80.0, true, false, 80) == null)
        check(w.update(session(0, 120, 41.0, 82.0), 82.0, true, false, 80) == null)
    }
    // A new session (new start) can reach the same target again.
    run {
        val w = ChargeWatch()
        check(w.update(session(0, 30, 70.0, 80.0), 80.0, true, false, 80) == ChargeEvent.Reached(80))
        check(w.update(null, 80.0, false, false, 80) == null)
        check(w.update(session(300, 330, 60.0, 80.2), 80.2, true, false, 80) == ChargeEvent.Reached(80))
    }
    // Nothing before any session.
    check(ChargeWatch().update(null, 50.0, true, false, 80) == null)
}

private fun alertText() {
    check(AlertText.chargeReached(80, 90.0, estimated = true) == AlertMessage("Charged to 80 %", "Range 90 km est.", "Charged to 80 % · range 90 km est."))
    // Range rounds like Home, Navigate and the ongoing notification (90.7 → 91), charge rounds down like the TFT.
    check(AlertText.chargeReached(81, 90.7, estimated = false).line == "Charged to 81 % · range 91 km")
    check(AlertText.chargeReached(80, 90.4, estimated = false).line == "Charged to 80 % · range 90 km")
    check(AlertText.chargeReached(100, 112.0, estimated = true).title == "Fully charged")
    check(AlertText.chargeReached(90, null, estimated = true) == AlertMessage("Charged to 90 %", null, "Charged to 90 %"))
    check(AlertText.chargeReached(90, Double.NaN, estimated = true).text == null)
    check(AlertText.chargeStopped(63) == AlertMessage("Charging stopped at 63 %", "No rise for 10 min while connected", "Charging stopped at 63 %"))
    // Range: the verdict's whole km as given (the Navigate chip shows the same number); route
    // distance with one decimal below 10 km; range in whole km.
    check(AlertText.range(true, 5, 22.4, 18.0, estimated = true) ==
        AlertMessage("Short by 5 km", "Charge before you arrive · 22 km to go, 18 km range est.", "Short by 5 km — charge before you arrive"))
    check(AlertText.range(true, 0, 18.0, 18.5, estimated = false).title == "Short by 1 km")    // never "Short by 0 km"
    check(AlertText.range(false, 3, 22.0, 25.0, estimated = true) == AlertMessage("Range is tight · 3 km spare", "22 km to go, 25 km range est.", "Range is tight · 3 km spare"))
    check(AlertText.range(false, 3, 8.4, 12.0, estimated = true).text == "8.4 km to go, 12 km range est.")
    check(AlertText.range(false, -1, 25.5, 25.0, estimated = false).let { it.title == "Range is tight · no spare" && it.text == "26 km to go, 25 km range" })
    check(AlertText.range(false, 0, 24.6, 25.0, estimated = false).title == "Range is tight · 0 km spare")
    check(AlertText.range(true, 2, 21.0, 18.6, estimated = true).text == "Charge before you arrive · 21 km to go, 19 km range est.")
}
