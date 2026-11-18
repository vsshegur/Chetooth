package dev.codesurgen.ruta

import kotlin.math.abs

/*
 * Demo mode's synthetic data (DemoData.kt): deterministic, plausible, internally consistent, and
 * nothing that resembles a real rider's area.
 */

private const val NOW = 1_763_370_000_000L // A fixed seed time (mid-November 2025).

fun main() {
    scenarios()
    history()
    places()
    readings()
    speedProfile()
    riding()
    navigating()
    charging()
    standing()
    println("DemoDataTest: all checks passed")
}

private fun scenarios() {
    check(DemoScenario.entries.map { it.key } == listOf("parked", "riding", "navigating", "charging", "call", "music"))
    check(DemoScenario.of("riding") == DemoScenario.RIDING && DemoScenario.of(" Navigating ") == DemoScenario.NAVIGATING)
    check(DemoScenario.of(DemoScenario.OFF) == null && DemoScenario.of("nope") == null && DemoScenario.of(null) == null)
    check(DemoScenario.entries.all { it.label.isNotBlank() && it.detail.isNotBlank() && it.icon.isNotBlank() })
}

private fun history() {
    val h = DemoData.history(NOW)
    val again = DemoData.history(NOW)
    check(h.rides.map { it.ride } == again.rides.map { it.ride }) { "history is not deterministic" }
    check(h.rides.map { it.points.size } == again.rides.map { it.points.size })

    check(h.rides.size in 12..16) { "rides: ${h.rides.size}" }
    check(h.rides.map { it.ride.id } == (1L..h.rides.size).toList())
    check(h.rides.zipWithNext().all { (a, b) -> a.ride.end < b.ride.start }) { "rides overlap or are out of order" }
    val first = h.rides.first().ride.start
    check(first >= NOW - 30 * DemoData.DAY_MS) { "a ride is older than 30 days" }
    val last = h.rides.last().ride
    check(last.end == NOW - DemoData.LAST_RIDE_AGO_MS) { "newest ride ends ${NOW - last.end} ms before the seed" }
    check(h.lastRide == last)

    for ((ride, points) in h.rides) {
        check(ride.startPlace != null && ride.endPlace != null && ride.startPlace != ride.endPlace)
        check(points.size >= 60) { "ride ${ride.id}: ${points.size} points" }
        check(points.zipWithNext().all { (a, b) -> b.time > a.time && b.distanceM >= a.distanceM }) { "ride ${ride.id}: track not monotonic" }
        check(points.first().time == ride.start && points.last().time == ride.end)
        check(abs(points.last().distanceM - ride.distanceM) < 0.01)
        check(points.first().speedKmh == 0.0 && points.last().speedKmh == 0.0) { "ride ${ride.id} does not start and end standing" }
        check(ride.distanceM in 1_500.0..9_000.0) { "ride ${ride.id}: ${ride.distanceM} m" }
        check(ride.peakKmh in 30.0..50.0) { "ride ${ride.id}: peak ${ride.peakKmh}" }
        check(ride.peakKmh == points.maxOf { it.speedKmh })
        check(ride.avgKmh in 18.0..40.0) { "ride ${ride.id}: average ${ride.avgKmh}" }
        check(ride.movingS in 1..ride.durationS) { "ride ${ride.id}: moving ${ride.movingS} of ${ride.durationS}" }
        check(ride.durationS in 240L..2_400L) { "ride ${ride.id}: ${ride.durationS} s" }
        check(points.all { it.accuracyM in 3.0..9.0 && it.speedKmh in 0.0..55.0 })
        // A real track: consecutive points never jump further than the speed allows.
        for ((a, b) in points.zipWithNext()) {
            val metres = NavOdometer.haversineM(a.lat, a.lng, b.lat, b.lng)
            val seconds = (b.time - a.time) / 1000.0
            check(metres <= 16.0 * seconds + 6.0) { "ride ${ride.id}: ${metres} m in ${seconds} s" }
        }
        val used = ride.batteryUsed!!
        check(used > 0.5 && used < 10.0) { "ride ${ride.id} used $used %" }
        check(ride.kmPerPercent!! in 1.0..1.3) { "ride ${ride.id}: ${ride.kmPerPercent} km/%" }
        // The start is the end of the street path from the start place.
        check(abs(ride.startLat - DemoData.CITY_LAT) < 0.05 && abs(ride.startLng - DemoData.CITY_LNG) < 0.08)
    }

    check(h.charges.size >= 3) { "charges: ${h.charges.size}" }
    for (c in h.charges) {
        check(c.socEnd > c.socStart + 5) { "charge ${c.id}: ${c.socStart} → ${c.socEnd}" }
        check(c.percentPerHour in 12.0..22.0) { "charge ${c.id}: ${c.percentPerHour} %/h" }
        check(c.socEnd <= 100.0)
        check(h.rides.none { (r, _) -> r.start < c.end && c.start < r.end }) { "charge ${c.id} overlaps a ride" }
    }
    check(abs(h.charges.last().socEnd - 80.0) < 0.01) { "the last charge should stop at 80 %, was ${h.charges.last().socEnd}" }
    check(h.socNow in 58.0..72.0) { "charge now ${h.socNow}" }
    // Rides use charge; their stored charge matches the timeline.
    for ((ride, _) in h.rides) check(abs(h.socAt(ride.start) - ride.socStart!!) < 0.02 && abs(h.socAt(ride.end) - ride.socEnd!!) < 0.02)

    val efficiency = h.efficiency
    check(efficiency.learned && efficiency.rides >= 10) { "efficiency: $efficiency" }
    check(efficiency.kmPerPercent in 1.10..1.22) { "efficiency ${efficiency.kmPerPercent}" }

    val spot = h.parkedSpot!!
    check(spot.lat == last.endLat && spot.lng == last.endLng && spot.time == last.end && spot.place == DemoData.LAKESIDE.label)
    check(spot.accuracyM in 3.0..20.0)
}

private fun places() {
    // Every place the demo shows comes from DemoData's own fictional set, in the stored "Street, Area" form.
    val h = DemoData.history(NOW)
    val labels = DemoData.PLACES.map { it.label }.toSet()
    for (place in h.rides.flatMap { listOfNotNull(it.ride.startPlace, it.ride.endPlace) }) check(place in labels) { "demo ride place outside the fictional set: $place" }
    val words = DemoData.PLACES.flatMap { "${it.street} ${it.area}".split(" ") }.toSet() + "Market"
    for (leg in DemoData.ROUTE) check(leg.road.split(" ").all { it in words }) { "demo road outside the fictional set: ${leg.road}" }
    for (place in DemoData.PLACES) check(RideMath.placeArea(place.label) == place.area)
    check(DemoData.PLACES.map { it.label }.toSet().size == DemoData.PLACES.size)
    // Ride titles read like real ones ("Hillcrest → Northgate").
    check(RideMath.rideTitle(DemoData.HOME.label, DemoData.WORK.label) == "Hillcrest → Northgate")
    check(PairingRules.normalizeMac(DemoData.DEVICE_MAC) != null)
}

private fun readings() {
    val h = DemoData.history(NOW)
    var count = 0
    val last = HashMap<Pair<Int, Metric>, Long>()
    var minTime = Long.MAX_VALUE; var maxTime = Long.MIN_VALUE
    var socMin = 100.0; var socMax = 0.0
    var tempMin = 100.0; var tempMax = -100.0
    var lastSoc: Reading? = null
    h.forEachReading { r ->
        count++
        val key = r.pack to r.metric
        last[key]?.let { check(r.time > it) { "readings out of order for $key" } }
        last[key] = r.time
        minTime = minOf(minTime, r.time); maxTime = maxOf(maxTime, r.time)
        check(r.value >= r.metric.minimum && r.value <= r.metric.maximum) { "$r out of range" }
        when (r.metric) {
            Metric.SOC -> if (r.pack == 0) { socMin = minOf(socMin, r.value); socMax = maxOf(socMax, r.value); lastSoc = r }
            Metric.TEMPERATURE -> { tempMin = minOf(tempMin, r.value); tempMax = maxOf(tempMax, r.value) }
            Metric.HEALTH -> check(r.value in 97.0..99.0)
            Metric.STATUS -> check(r.value in listOf(0.0, 2.0, 4.0))
            Metric.ENERGY -> {}
        }
    }
    check(count in 30_000..90_000) { "readings: $count" }
    check(minTime >= NOW - 30 * DemoData.DAY_MS && maxTime == NOW) { "readings span ${(NOW - minTime) / DemoData.DAY_MS} d, last at ${NOW - maxTime}" }
    check(socMin >= 20.0 && socMax <= 100.0) { "SOC $socMin..$socMax" }
    check(tempMin in 15.0..25.0 && tempMax in 25.0..38.0) { "temperature $tempMin..$tempMax" }
    check(abs(lastSoc!!.value - h.socNow) < 0.01)
    // Every pack and metric is present.
    for (pack in 0..2) for (metric in Metric.entries) check((pack to metric) in last) { "no $metric on pack $pack" }
    // The last day is sampled densely (2 min) for the 24 h charts.
    var recent = 0
    h.forEachReading { if (it.pack == 0 && it.metric == Metric.SOC && it.time >= NOW - DemoData.DAY_MS) recent++ }
    check(recent in 700..725) { "last-day SOC samples: $recent" }
}

private fun speedProfile() {
    check(DemoData.speedAt(0.0) == 0.0 && DemoData.speedAt(DemoData.CYCLE_S) == 0.0)
    var t = 0.0
    var previous = 0.0
    var top = 0.0
    while (t < DemoData.CYCLE_S * 3) {
        val d = DemoData.distanceAt(t)
        check(d >= previous - 1e-9) { "distance goes back at $t" }
        previous = d
        top = maxOf(top, DemoData.speedAt(t))
        check(DemoData.speedAt(t) in 0.0..DemoData.TOP_KMH)
        check(DemoData.movingAt(t) in 0.0..(t + 1e-9))
        t += 0.5
    }
    check(top == DemoData.TOP_KMH && DemoData.peakAt(DemoData.CYCLE_S * 2) == DemoData.TOP_KMH)
    check(abs(DemoData.distanceAt(DemoData.CYCLE_S) - DemoData.CYCLE_M) < 1e-6)
    check(abs(DemoData.distanceAt(DemoData.CYCLE_S * 2 + 30) - (DemoData.CYCLE_M * 2 + DemoData.distanceAt(30.0))) < 1e-6)
    check(DemoData.CYCLE_M in 900.0..1_600.0) { "loop ${DemoData.CYCLE_M} m" }
    // Light stops: standing time is not moving time.
    check(DemoData.movingAt(DemoData.CYCLE_S) < DemoData.CYCLE_S - 30)
    // timeAt inverts distanceAt.
    for (m in listOf(10.0, 500.0, 3_333.0, 9_000.0)) check(abs(DemoData.distanceAt(DemoData.timeAt(m)) - m) < 0.05)
    // The moving scenarios start standing at a light, then take off within seconds.
    check(DemoData.speedAt(DemoData.PREROLL_S) == 0.0 && DemoData.speedAt(DemoData.PREROLL_S + 8) > 10)
}

private fun riding() {
    val h = DemoData.history(NOW)
    val start = NOW + 60_000
    val a = DemoData.live(DemoScenario.RIDING, start, start, h)
    val b = DemoData.live(DemoScenario.RIDING, start, start + 120_000, h)
    check(a.linked && a.ride != null && a.nav == null && a.charge == null && a.ignitionOn && !a.handleLocked && a.status == 4)
    val ra = a.ride!!; val rb = b.ride!!
    check(ra.startedAt == start - (DemoData.PREROLL_S * 1000).toLong() && rb.startedAt == ra.startedAt)
    check(ra.distanceM > 500 && rb.distanceM > ra.distanceM) { "ride distance ${ra.distanceM} → ${rb.distanceM}" }
    check(ra.durationS == DemoData.PREROLL_S.toLong() && rb.durationS == ra.durationS + 120)
    check(rb.peakKmh == DemoData.TOP_KMH && rb.avgKmh in 15.0..40.0)
    check(abs(a.soc - h.socNow) < 0.01 + ra.distanceM / 1000 / h.efficiency.kmPerPercent) // started from the parked charge
    check(b.soc < a.soc && a.socFront > a.soc && a.socRear < a.soc)
    // Rides away from where the demo scooter is parked, on the charge the seeded month left.
    check(ra.socStart == h.socNow && ra.startPlace == h.parkedSpot!!.place && ra.startPlace == DemoData.LAKESIDE.label)
    // The live ride's start is the same on every tick (whole milliseconds: no reload churn).
    check((0..600).map { DemoData.live(DemoScenario.RIDING, start, start + 1_000L * it + it % 7, h).ride!!.startedAt }.toSet().size == 1)
    // Speeds over a minute: stop at a light, then away to ~40 km/h.
    val speeds = (0..60).map { DemoData.live(DemoScenario.RIDING, start, start + it * 1000L, h).ride!!.speedKmh }
    check(speeds.first() == 0.0 && speeds.max() > 35)
    // Restarts after an hour (the battery never runs down).
    val hourLater = DemoData.live(DemoScenario.RIDING, start, start + 3_600_000L, h)
    check(abs(hourLater.ride!!.distanceM - ra.distanceM) < 1e-6)
    // Music rides too.
    check(DemoData.live(DemoScenario.MUSIC, start, start + 5_000, h).ride != null)
}

private fun navigating() {
    val h = DemoData.history(NOW)
    val k = h.efficiency.kmPerPercent
    val start = NOW + 60_000
    val clock = { time: Long -> ((time / 3_600_000L) % 24).toInt() to ((time / 60_000L) % 60).toInt() }
    val route = DemoData.ROUTE
    check(route.map { it.turn } == listOf(NavTurn.LEFT, NavTurn.ROUNDABOUT_RIGHT, NavTurn.LEFT, NavTurn.STRAIGHT, NavTurn.DESTINATION))
    check(route.first().road == "Riverside Rd" && route.first().lengthM == 400.0)
    check(abs(route.sumOf { it.lengthM } - DemoData.ROUTE_M) < 1e-6 && route.all { it.lengthM >= 300 })
    check(DemoData.ROUTE_M in 5_600.0..7_200.0) { "route ${DemoData.ROUTE_M} m" }
    // The rider arrives standing still.
    val arriveAt = DemoData.timeAt(DemoData.distanceAt(DemoData.PREROLL_S) + DemoData.ROUTE_M)
    check(DemoData.speedAt(arriveAt + 1) == 0.0)

    val seen = ArrayList<Int>()
    var previousRemaining = Int.MAX_VALUE
    var previousManeuver = Int.MAX_VALUE
    var previousTurn = -1
    var arrival: Pair<Int?, Int?>? = null
    var tightAll = true
    var level: RangeLevel? = null
    var minSpare = Double.MAX_VALUE; var maxSpare = -Double.MAX_VALUE
    var t = 0L
    while (t < (DemoData.NAV_PERIOD_S * 1000).toLong() - 1000) {
        val live = DemoData.live(DemoScenario.NAVIGATING, start, start + t, h, clock)
        val nav = live.nav!!
        check(live.ride != null && live.linked)
        if (nav.cue.turn != previousTurn) { seen += nav.cue.turn; previousTurn = nav.cue.turn; previousManeuver = Int.MAX_VALUE }
        check(nav.maneuverM <= previousManeuver) { "maneuver distance went up at $t ms" }
        check(nav.remainingM <= previousRemaining) { "remaining went up at $t ms" }
        previousManeuver = nav.maneuverM; previousRemaining = nav.remainingM
        check(nav.cue.remainingMeters == nav.remainingM && nav.cue.distanceMeters == nav.maneuverM && !nav.cue.rerouting)
        check(nav.maneuverM < 1000 || nav.maneuverM % 100 == 0) { "maneuver ${nav.maneuverM} not rounded like Maps" }
        val eta = nav.cue.arrivalHour24 to nav.cue.arrivalMinute
        if (arrival == null) arrival = eta else check(arrival == eta) { "arrival time moved" }
        // The range check ("Can I make it?"): Tight on purpose, well inside its band, also through RangeCheck's
        // hysteresis (as Navigate evaluates it, the route left in km).
        val estimate = RangeModel.estimate(live.soc, null, h.efficiency)!!
        val spare = estimate.km - nav.remainingM / 1000.0
        minSpare = minOf(minSpare, spare); maxSpare = maxOf(maxSpare, spare)
        if (spare < 1.0 || spare >= 4.0) tightAll = false
        if (nav.remainingM > 0) {
            val verdict = RangeCheck.evaluate(nav.remainingM / 1000.0, estimate, level)!!
            if (verdict.level != RangeLevel.TIGHT) tightAll = false
            level = verdict.level
        }
        t += 1000
    }
    check(seen == listOf(NavTurn.LEFT, NavTurn.ROUNDABOUT_RIGHT, NavTurn.LEFT, NavTurn.STRAIGHT, NavTurn.DESTINATION)) { "cues: $seen" }
    check(previousRemaining == 0 && previousManeuver == 0) { "never arrived: $previousRemaining m left" }
    check(tightAll) { "spare $minSpare..$maxSpare km is not a steady Tight" }
    check(DemoData.navStartSoc(k) in 6.0..14.0) { "navigation charge ${DemoData.navStartSoc(k)}" }
    // First update: "Turn left onto Riverside Rd" at 400 m.
    val first = DemoData.live(DemoScenario.NAVIGATING, start, start, h, clock).nav!!
    check(first.cue.instruction == "Turn left onto Riverside Rd" && first.maneuverM == 400)
    // The loop starts over after arriving.
    val again = DemoData.live(DemoScenario.NAVIGATING, start, start + (DemoData.NAV_PERIOD_S * 1000).toLong() + 500, h, clock).nav!!
    check(again.cue.turn == NavTurn.LEFT && again.maneuverM >= 390)
    // The frame the Navigate preview shows.
    val frame = first.cue.frame(java.time.LocalDateTime.of(2025, 11, 17, 10, 0), first.maneuverM, first.remainingM)
    check(frame.active && frame.turn == NavTurn.LEFT && frame.distanceMeters == 400 && frame.roadText == "Riverside")
    check(first.cue.thenTurn == null)
    // The roundabout's cue announces the left turn right after it: the TFT shows "Then left" instead of the road.
    val roundabout = DemoData.live(DemoScenario.NAVIGATING, start, start + (DemoData.timeAt(DemoData.distanceAt(DemoData.PREROLL_S) + 600.0) - DemoData.PREROLL_S).let { (it * 1000).toLong() }, h, clock).nav!!
    check(roundabout.cue.turn == NavTurn.ROUNDABOUT_RIGHT && roundabout.cue.thenTurn == NavTurn.LEFT) { "cue at 600 m: ${roundabout.cue}" }
    val roundaboutFrame = roundabout.cue.frame(java.time.LocalDateTime.of(2025, 11, 17, 10, 0), roundabout.maneuverM, roundabout.remainingM)
    check(roundaboutFrame.roadText == "Then left" && roundabout.cue.road == "Market St") { "roundabout frame '${roundaboutFrame.roadText}'" }
    // Every other demo cue keeps its road.
    check(DemoData.ROUTE.count { MapsNavigationParser.thenTurn(it.instruction) != null } == 1)
}

private fun charging() {
    val h = DemoData.history(NOW)
    val start = NOW + 60_000
    val a = DemoData.live(DemoScenario.CHARGING, start, start, h)
    val b = DemoData.live(DemoScenario.CHARGING, start, start + 45_000, h)
    check(a.linked && a.ride == null && a.charge != null && !a.ignitionOn && a.handleLocked)
    val s = a.charge!!
    check(s.durationS >= 600) { "rate unknown for ${s.durationS} s" }
    check(abs(s.percentPerHour - 80.0) < 0.5) { "rate ${s.percentPerHour}" }
    // Continues the seeded month: from the charge the newest ride left, plugged in after that ride ended.
    check(abs(s.socStart - h.socNow) < 0.01) { "charging from ${s.socStart}, scooter at ${h.socNow}" }
    check(s.start >= h.lastRide!!.end) { "the charge starts ${(h.lastRide!!.end - s.start) / 1000} s before the newest ride ended" }
    check(h.rides.none { (r, _) -> r.start < s.end && s.start < r.end })
    check(abs(a.soc - (h.socNow + DemoData.CHARGE_PLUGGED_S * DemoData.CHARGE_PERCENT_PER_S)) < 0.01) { "charging at ${a.soc}" }
    // Charging started at the seed itself (`--es demo charging`) is still after the newest ride.
    check(DemoData.live(DemoScenario.CHARGING, NOW, NOW, h).charge!!.start >= h.lastRide!!.end)
    check(abs(b.soc - a.soc - 1.0) < 1e-6) { "not 1 % per 45 s" }
    check(b.charge!!.start == s.start)
    // The session's start is the same on every tick (the shell rewrites the stored one only on a real restart).
    check((0..600).map { DemoData.live(DemoScenario.CHARGING, start, start + 1_000L * it + it % 7, h).charge!!.start }.toSet().size == 1)
    check(ChargeEta.minutesToFull(a.soc, s) != null) { "no Full-in time when the scenario starts" }
    val eta = ChargeEta.minutesToFull(a.soc, s.percentPerHour)!!
    check(eta in 10..30) { "full in $eta min" }
    // Full, holds, then starts over.
    val toFull = (DemoData.chargeToFullS(s.socStart) - DemoData.CHARGE_PLUGGED_S) * 1000
    val full = DemoData.live(DemoScenario.CHARGING, start, start + toFull.toLong() + 60_000, h)
    check(full.soc == 100.0 && full.charge!!.socEnd == 100.0 && ChargeEta.minutesToFull(full.soc, full.charge!!.percentPerHour) == 0)
    val over = DemoData.live(DemoScenario.CHARGING, start, start + toFull.toLong() + (DemoData.CHARGE_FULL_HOLD_S * 1000).toLong() + 1_000, h)
    check(over.soc < a.soc + 0.1 && over.charge!!.start > s.start && abs(over.charge!!.socStart - s.socStart) < 1e-9)
}

private fun standing() {
    val h = DemoData.history(NOW)
    val parked = DemoData.live(DemoScenario.PARKED, NOW, NOW + 10_000, h)
    check(!parked.linked && parked.ride == null && parked.charge == null && parked.nav == null && parked.connectedAt == 0L)
    check(parked.soc == h.socNow && parked.status == 0 && parked.packStatus == 2 && parked.handleLocked)
    val call = DemoData.live(DemoScenario.CALL, NOW, NOW + 10_000, h)
    check(call.linked && call.ignitionOn && call.ride == null && call.status == 4)
    check(call.healthFront == DemoData.HEALTH_FRONT && call.tempRear > call.tempFront)
}
