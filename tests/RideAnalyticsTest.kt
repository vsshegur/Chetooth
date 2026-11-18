package dev.codesurgen.ruta

import kotlin.math.abs

private val M_PER_DEG = RideMath.EARTH_RADIUS_M * Math.PI / 180

private fun near(a: Double, b: Double, tolerance: Double) = abs(a - b) <= tolerance

/** A fix [northM] metres north of a Bengaluru origin (distances along a meridian are exact). */
private fun fixAt(time: Long, northM: Double, speed: Double = Double.NaN, accuracy: Double = 5.0) =
    GeoFix(time, 12.9 + northM / M_PER_DEG, 77.6, accuracy, speed)

private fun ride(id: Long, distanceM: Double, movingS: Long, peak: Double, socStart: Double?, socEnd: Double?) =
    Ride(id, 0, movingS * 1000, 12.9, 77.6, 13.0, 77.6, null, null, distanceM, movingS, movingS, peak,
        RideMath.averageKmh(distanceM, movingS * 1000), socStart, socEnd)

fun main() {
    haversine()
    filtering()
    smoothing()
    segmentation()
    rideSummary()
    batteryAndStats()
    charging()
    downsampling()
    helpers()
    println("RideAnalyticsTest: all checks passed")
}

private fun haversine() {
    check(near(RideMath.haversineM(0.0, 0.0, 1.0, 0.0), M_PER_DEG, 1e-6)) // 1° of latitude ≈ 111.195 km
    check(near(RideMath.haversineM(0.0, 0.0, 0.0, 1.0), M_PER_DEG, 1e-6))
    check(near(RideMath.haversineM(60.0, 10.0, 60.0, 11.0), M_PER_DEG / 2, 50.0)) // cos 60° = 0.5
    check(near(RideMath.haversineM(0.0, 0.0, 0.0, 180.0), Math.PI * RideMath.EARTH_RADIUS_M, 1e-3))
    check(RideMath.haversineM(12.9, 77.6, 12.9, 77.6) == 0.0)
    check(near(RideMath.haversineM(12.9, 77.6, 13.0, 77.7), RideMath.haversineM(13.0, 77.7, 12.9, 77.6), 1e-9))
}

private fun filtering() {
    val f = FixFilter()
    check(f.accept(fixAt(0, 0.0, accuracy = 30.0)) == null) // worse than 25 m
    check(f.accept(fixAt(0, 0.0, accuracy = Double.NaN)) == null) // no accuracy
    check(f.accept(GeoFix(0, 0.0, 0.0, 5.0)) == null) // 0,0 placeholder
    val first = f.accept(fixAt(1_000, 0.0, speed = 0.0))!!
    check(first.stepM == 0.0 && !first.moving)
    // Jitter: slow fixes inside the noise radius add nothing and keep the previous position.
    val jitter = f.accept(fixAt(2_000, 3.0, speed = 1.0))!!
    check(jitter.stepM == 0.0 && jitter.speedKmh == 0.0 && !jitter.moving && jitter.lat == first.lat)
    check(f.accept(fixAt(3_000, 12.0, accuracy = 15.0))!!.stepM == 0.0) // within a 15 m accuracy radius
    // Real movement: 10 m in a second at a reported 36 km/h.
    val move = f.accept(fixAt(4_000, 10.0, speed = 36.0))!!
    check(near(move.stepM, 10.0, 0.01) && move.speedKmh == 36.0 && move.moving)
    check(f.accept(fixAt(4_000, 20.0, speed = 36.0)) == null) // not after the previous fix
    check(f.accept(fixAt(5_000, 510.0, speed = 36.0)) == null) // 500 m in 1 s
    val after = f.accept(fixAt(6_000, 30.0, speed = 36.0))!! // measured from the last good fix
    check(near(after.stepM, 20.0, 0.01))
    val implied = f.accept(fixAt(7_000, 40.0, speed = 500.0))!! // impossible Doppler speed → implied
    check(near(implied.speedKmh, 36.0, 0.1) && implied.moving)
    // A slow walk beyond the noise radius still counts, at its implied speed from the last position.
    val creep = f.accept(fixAt(10_000, 46.0))!!
    check(near(creep.stepM, 6.0, 0.01) && near(creep.speedKmh, 7.2, 0.1))

    // Five impossible jumps in a row: the anchor was the outlier, so restart without adding distance.
    val g = FixFilter()
    g.accept(fixAt(0, 0.0))
    repeat(4) { check(g.accept(fixAt(1_000L * (it + 1), 5_000.0 + it)) == null) }
    val restarted = g.accept(fixAt(5_000, 5_004.0))!!
    check(restarted.stepM == 0.0 && restarted.lat == fixAt(0, 5_004.0).lat)
    check(near(g.accept(fixAt(6_000, 5_014.0, speed = 36.0))!!.stepM, 10.0, 0.01))
}

private fun smoothing() {
    val s = SpeedSmoother()
    check(s.add(90.0) == 90.0 && !s.full)
    check(s.add(30.0) == 30.0) // the lower of two
    check(s.add(32.0) == 32.0 && s.full)
    check(s.add(95.0) == 32.0) // a lone spike never wins
    check(s.add(34.0) == 34.0)
    check(RideMath.median3(3.0, 1.0, 2.0) == 2.0 && RideMath.median3(1.0, 1.0, 5.0) == 1.0)
}

private fun segmentation() {
    val t0 = 1_000_000L
    // Ignition start (link required), ignition-off end after 60 s at the moment it went off.
    val a = RideSegmenter()
    check(a.update(t0, linkReady = false, ignitionOn = true, speedKmh = 30.0) == null && !a.riding)
    check(a.update(t0 + 1_000, true, true, null) == RideEvent.Started(t0 + 1_000, byIgnition = true) && a.riding)
    val off = t0 + 2_000
    check(a.update(off, true, false, 0.0) == null)
    check(a.update(off + 59_000, true, false, 0.0) == null)
    check(a.update(off + 60_000, true, false, 0.0) == RideEvent.Ended(off, RideEndReason.IGNITION_OFF) && !a.riding)

    // Speed start: > 8 km/h for 10 s; the ride starts when the movement began.
    val b = RideSegmenter()
    for (i in 0..9) check(b.update(t0 + i * 1_000L, true, null, 20.0) == null)
    check(b.update(t0 + 10_000, true, null, 20.0) == RideEvent.Started(t0, byIgnition = false))
    // An "ignition off" flag while clearly moving does not end the ride.
    check(b.update(t0 + 11_000, true, false, 30.0) == null)
    check(b.update(t0 + 80_000, true, false, 30.0) == null && b.riding)
    // Link lost for 120 s ends it; still moving, so it ends now.
    check(b.update(t0 + 81_000, false, null, 30.0) == null)
    check(b.update(t0 + 200_000, false, null, 30.0) == null)
    check(b.update(t0 + 201_000, false, null, 30.0) == RideEvent.Ended(t0 + 201_000, RideEndReason.LINK_LOST))

    // A dip below 8 km/h restarts the 10 s hold.
    val c = RideSegmenter()
    c.update(t0, true, null, 20.0); c.update(t0 + 5_000, true, null, 5.0)
    check(c.update(t0 + 10_000, true, null, 20.0) == null)
    check(c.update(t0 + 20_000, true, null, 20.0) == RideEvent.Started(t0 + 10_000, false))
    // No link: speed alone never starts a ride.
    val noLink = RideSegmenter()
    for (i in 0..30) check(noLink.update(t0 + i * 1_000L, false, null, 40.0) == null)

    // Idle: under 2 km/h (or no fix) for 5 min ends at the last movement.
    val d = RideSegmenter()
    check(d.update(t0, true, true, 0.0) is RideEvent.Started)
    check(d.update(t0 + 60_000, true, true, 25.0) == null)
    check(d.update(t0 + 61_000, true, true, 1.0) == null)
    check(d.update(t0 + 360_000, true, true, null) == null)
    check(d.update(t0 + 361_000, true, true, null) == RideEvent.Ended(t0 + 61_000, RideEndReason.IDLE))
    // Ignition still on after an idle end does not restart a ride by itself...
    check(d.update(t0 + 362_000, true, true, null) == null && !d.riding)
    // ...movement does...
    for (i in 0..9) check(d.update(t0 + 363_000 + i * 1_000L, true, true, 15.0) == null)
    check(d.update(t0 + 373_000, true, true, 15.0) == RideEvent.Started(t0 + 363_000, false))
    check(d.stop(t0 + 374_000) == RideEvent.Ended(t0 + 374_000, RideEndReason.STOPPED) && d.stop(t0 + 375_000) == null)
    // ...and so does ignition after it has been off.
    check(d.update(t0 + 376_000, true, false, null) == null)
    check(d.update(t0 + 377_000, true, true, null) == RideEvent.Started(t0 + 377_000, true))
}

/** Parked 30 s, 120 s at 36 km/h (with one Doppler spike), 20 s at 54, a 60 s stop, 30 s at 36, parked 60 s. */
private fun sampleRide(): List<TrackStep> {
    val f = FixFilter()
    val fixes = mutableListOf<GeoFix>()
    var north = 0.0
    for (t in 0..30) fixes += fixAt(t * 1_000L, if (t % 2 == 0) 2.0 else -2.0, speed = 0.0)
    for (t in 31..150) { north += 10; fixes += fixAt(t * 1_000L, north, speed = if (t == 100) 90.0 else 36.0) }
    for (t in 151..170) { north += 15; fixes += fixAt(t * 1_000L, north, speed = 54.0) }
    for (t in 231..260) { north += 10; fixes += fixAt(t * 1_000L, north, speed = 36.0) }
    for (t in 261..320) fixes += fixAt(t * 1_000L, north + if (t % 2 == 0) 2.0 else -2.0, speed = 0.0)
    return fixes.mapNotNull(f::accept)
}

private fun rideSummary() {
    val track = RideAccumulator()
    val points = sampleRide().map(track::add)
    check(near(track.distanceM, 1_798.0, 1e-6)) // 1800 m less 2 m: the parked anchor sat 2 m north
    // 140 s of steady fixes + 1 s credited for the first fix after the stop (10 m at 36 km/h) + 29 s.
    check(track.movingMs == 170_000L)
    check(track.peakKmh == 54.0) // the 90 km/h spike is filtered out
    check(near(track.averageKmh, 1_798.0 / 170 * 3.6, 1e-6))
    val ride = track.finish(7, 80.0, 79.0)!!
    // Trimmed to the movement: from the parked point before the first move to the last moving fix.
    check(ride.id == 7L && ride.start == 30_000L && ride.end == 260_000L && ride.durationS == 230L && ride.movingS == 170L)
    check(near(ride.startLat, fixAt(0, 2.0).lat, 1e-9) && ride.peakKmh == 54.0)
    check(near(ride.kmPerPercent!!, 1.798, 1e-6) && ride.batteryUsed == 1.0 && ride.startLabel == "12.9000, 77.6000")
    check(points.first().distanceM == 0.0 && points.last().distanceM == track.distanceM)

    // An interrupted ride rebuilt from its stored points gives the same summary.
    val replayed = RideAccumulator.replay(points.shuffled(java.util.Random(4)))
    check(replayed.finish(7, 80.0, 79.0) == ride)

    // Too short: 100 m, or 300 m in 30 s.
    val short = RideAccumulator()
    for (t in 0..10) short.add(TrackStep(t * 1_000L, 12.9, 77.6, 5.0, if (t == 0) 0.0 else 10.0, 36.0, t > 0))
    check(short.finish(1, null, null) == null)
    val quick = RideAccumulator()
    for (t in 0..30) quick.add(TrackStep(t * 1_000L, 12.9, 77.6, 5.0, if (t == 0) 0.0 else 10.0, 36.0, t > 0))
    check(near(quick.distanceM, 300.0, 1e-9) && quick.finish(1, null, null) == null)
    val enough = RideAccumulator()
    for (t in 0..60) enough.add(TrackStep(t * 1_000L, 12.9, 77.6, 5.0, if (t == 0) 0.0 else 10.0, 36.0, t > 0))
    check(enough.finish(1, null, null)!!.durationS == 60L)
    check(RideAccumulator().finish(1, null, null) == null)
}

private fun batteryAndStats() {
    check(RideMath.kmPerPercent(12_000.0, 60.0, 57.0) == 4.0)
    check(RideMath.kmPerPercent(5_000.0, 60.0, 59.5) == null) // under 1 point: noise
    check(RideMath.kmPerPercent(5_000.0, null, 59.0) == null && RideMath.kmPerPercent(5_000.0, 60.0, 62.0) == null)
    check(RideMath.averageKmh(1_000.0, 100_000) == 36.0 && RideMath.averageKmh(1_000.0, 0) == 0.0)

    val stats = RideMath.stats(listOf(ride(1, 10_000.0, 1_200, 55.0, 80.0, 77.0), ride(2, 6_000.0, 600, 62.0, null, null)))
    check(stats.rides == 2 && stats.distanceM == 16_000.0 && stats.movingS == 1_800L && stats.peakKmh == 62.0)
    check(near(stats.avgKmh, 32.0, 1e-9) && stats.batteryUsed == 3.0)
    check(near(stats.kmPerPercent!!, 10.0 / 3, 1e-9)) // only rides with charge readings count
    val empty = RideMath.stats(emptyList())
    check(empty.rides == 0 && empty.avgKmh == 0.0 && empty.kmPerPercent == null)
}

private fun charging() {
    val min = 60_000L
    fun series(minutes: IntProgression, soc: (Double) -> Double) = minutes.map { ValueAt(it * min / 2, soc(it / 2.0)) }

    // Flat 50 % for 20 min, +0.5 %/min to 80 % at 80 min, flat to 100 min.
    val samples = series(0..200 step 1) { m -> when { m < 20 -> 50.0; m <= 80 -> 50 + (m - 20) * 0.5; else -> 80.0 } }
    val sessions = RideMath.chargeSessions(samples)
    check(sessions.size == 1)
    val s = sessions.single()
    check(s.start == 20 * min && s.end == 80 * min && s.socStart == 50.0 && s.socEnd == 80.0)
    check(s.durationS == 3_600L && near(s.percentPerHour, 30.0, 1e-9) && s.gained == 30.0)

    // Streaming: confirmed only after ≥ 1 point over ≥ 5 min; ends after a 10 min stall.
    val d = ChargeDetector()
    check(d.update(0, 50.0, true, false) == null)
    check(d.update(4 * min, 52.0, true, false) == null && d.active == null) // +2 but only 4 min
    check(d.update(5 * min, 52.5, true, false) == null && d.active == ChargeSession(0, 5 * min, 50.0, 52.5))
    check(d.update(14 * min, 52.5, true, false) == null && d.active != null)
    check(d.update(15 * min, 52.5, true, false) == ChargeSession(0, 5 * min, 50.0, 52.5) && d.active == null)

    // Not while riding, and a link drop ends a session.
    val r = ChargeDetector()
    for (m in 0..20) check(r.update(m * min, 50.0 + m, true, riding = true) == null && r.active == null)
    val l = ChargeDetector()
    l.update(0, 50.0, true, false); l.update(6 * min, 52.0, true, false)
    check(l.update(7 * min, 52.5, false, false) == ChargeSession(0, 6 * min, 50.0, 52.0))

    // Slow drift (+1 point over 2 h, e.g. a recovering reading) is not charging.
    check(RideMath.chargeSessions(series(0..240 step 1) { m -> 50 + m / 120 }).isEmpty())

    // A short link gap mid-charge (10 min) continues the same session; a long one (2 h) starts a new one.
    fun gapped(gapMinutes: Int): List<ChargeSession> {
        val before = series(0..60 step 1) { m -> if (m < 10) 50.0 else 50 + (m - 10) * 0.5 } // 50 → 60 at 30 min
        val resume = 30 + gapMinutes
        val after = series(resume * 2..(resume + 35) * 2 step 1) { m -> minOf(60 + (m - resume) * 0.5, 70.0) }
        return RideMath.chargeSessions(before + after)
    }
    val bridged = gapped(10)
    check(bridged.size == 1 && bridged[0].start == 10 * min && bridged[0].socStart == 50.0 && bridged[0].socEnd == 70.0)
    check(bridged[0].end == 60 * min)
    val split = gapped(120)
    check(split.size == 2 && split[0].socEnd == 60.0 && split[1].socStart == 60.0 && split[1].socEnd == 70.0)
}

private fun downsampling() {
    val values = (0 until 100).map { ValueAt(it * 1_000L, it.toDouble()) }
    val buckets = RideMath.downsample(values + ValueAt(100_000, 999.0) + ValueAt(-1, -999.0), 0, 100_000, 10)
    check(buckets.size == 10)
    buckets.forEachIndexed { k, b ->
        check(b.time == k * 10_000L + 5_000 && b.avg == k * 10 + 4.5 && b.min == k * 10.0 && b.max == k * 10 + 9.0 && b.count == 10)
    }
    val gappy = RideMath.downsample(values.filter { it.time !in 30_000 until 40_000 }, 0, 100_000, 10)
    check(gappy.size == 9 && gappy.none { it.time == 35_000L })
    check(RideMath.downsample(values, 0, 100_000, 1000).size == 100) // never more buckets than samples
    check(RideMath.downsample(values, 10, 10, 5).isEmpty() && RideMath.downsample(values, 0, 10, 0).isEmpty())
    check(RideMath.bucketWidth(0, 100, 3) == 34L && RideMath.bucketWidth(0, 2, 10) == 1L)
    // 24 h of 30 s samples into 96 buckets: 15 min each, 30 samples per bucket.
    val day = (0 until 2880).map { ValueAt(it * 30_000L, 50.0) }
    check(RideMath.downsample(day, 0, 86_400_000, 96).let { b -> b.size == 96 && b.all { it.count == 30 } })

    val track = (0..9).map { RidePoint(it * 1_000L, 12.9, 77.6, it * 10.0, it * 10.0, 5.0) }
    val speed = RideMath.speedSeries(track, 5)
    check(speed.size == 5 && speed.first().avg == 5.0 && speed.last().max == 90.0)
}

private fun helpers() {
    // 2.1 (F7): "thoroughfare, subLocality"; without a street the subLocality, then the locality.
    check(RideMath.placeLabel("Lakeside", "Riverside Garden Rd", "Northgate") == "Riverside Garden Rd, Lakeside")
    check(RideMath.placeLabel("Koramangala", "80 Feet Road", "Bengaluru") == "80 Feet Road, Koramangala")
    check(RideMath.placeLabel("Greenfield", null, "Northgate") == "Greenfield")
    check(RideMath.placeLabel(null, null, "Northgate") == "Northgate")
    check(RideMath.placeLabel(null, "Old Harbor Hwy", "Northgate") == "Old Harbor Hwy, Northgate")
    check(RideMath.placeLabel(null, null, "Bengaluru", featureName = "7J4V+2C") == "Bengaluru") // plus code skipped
    check(RideMath.placeLabel(null, "MG Road", null, "12", null, "Karnataka") == "MG Road")
    check(RideMath.placeLabel(null, null, null, "221/B") == null)
    check(RideMath.placeLabel(null, null, null, "Lakeside Mall", "Greenfield District", "Northshire") == "Lakeside Mall")
    check(RideMath.placeLabel(null, null, null, "12", "Greenfield District", "Northshire") == "Greenfield District")
    check(RideMath.placeLabel("Indiranagar", null, "Indiranagar") == "Indiranagar")
    check(RideMath.placeLabel("Lakeside", "Lakeside", "Northgate") == "Lakeside")
    // Never house numbers, plus codes or "Unnamed Road"; real street numbers survive.
    check(RideMath.placeLabel("Lakeside", "Unnamed Road", "Northgate") == "Lakeside")
    check(RideMath.placeLabel("Lakeside", "12-2-345", "Northgate") == "Lakeside")
    check(RideMath.placeLabel("Lakeside", "Plot No 5", "Northgate") == "Lakeside")
    check(RideMath.placeLabel("Maple Heights", "Road No. 36", "Northgate") == "Road No. 36, Maple Heights")
    check(RideMath.placeLabel("7J4V+2C Greenfield", null, "Northgate") == "Greenfield")
    check(RideMath.placeLabel(" ", " ", null) == null)
    // Several results (nearest first): each field comes from the first result that has it.
    val results = listOf(
        PlaceParts(thoroughfare = null, subLocality = null, locality = "Northgate", featureName = "7J4V+2C"),
        PlaceParts(thoroughfare = "Riverside Garden Rd", subLocality = "Lakeside", locality = "Northgate"),
        PlaceParts(thoroughfare = "Old Harbor Hwy", subLocality = "Greenfield", locality = "Northgate")
    )
    check(RideMath.placeLabel(results) == "Riverside Garden Rd, Lakeside")
    check(RideMath.placeLabel(emptyList()) == null)
    check(RideMath.placeArea("Riverside Garden Rd, Lakeside") == "Lakeside" && RideMath.placeArea("Lakeside") == "Lakeside" && RideMath.placeArea(null) == null)
    val labelled = ride(1, 5_000.0, 600, 40.0, null, null).copy(startPlace = "Riverside Garden Rd, Lakeside", endPlace = "Greenfield")
    check(labelled.startArea == "Lakeside" && labelled.endArea == "Greenfield" && ride(2, 1.0, 60, 1.0, null, null).startArea == null)
    // Ride list titles (R1): short street words, areas first, no truncation-prone full names.
    check(RideMath.shortPlace("Riverside Garden Road") == "Riverside Garden Rd")
    check(RideMath.shortPlace(" 80 Feet  ROAD, Koramangala ") == "80 Feet Rd, Koramangala")
    check(RideMath.shortPlace("Church Street") == "Church St" && RideMath.shortPlace("Broadway") == "Broadway")
    check(RideMath.shortPlace("Lanesborough Avenue") == "Lanesborough Ave") // whole words only
    check(RideMath.rideTitle("Riverside Garden Road, Lakeside", "Lakeside") == "Riverside Garden Rd → Lakeside")
    check(RideMath.rideTitle("Lakeside", "Riverside Garden Road, Lakeside") == "Lakeside → Riverside Garden Rd")
    check(RideMath.rideTitle("Riverside Garden Rd, Lakeside", "Old Harbor Highway, Greenfield") == "Lakeside → Greenfield")
    check(RideMath.rideTitle("Hill Road, Lakeside", "hill road, Lakeside") == "Around Hill Rd")
    check(RideMath.rideTitle(null, "Greenfield") == null && RideMath.rideTitle(" ", "Greenfield") == null)

    // Chart runs (I1): gaps split runs; points crowding one slot merge (weighted); never bridged.
    val hour = 3_600_000L
    val pts = listOf(
        BucketPoint(0, 50.0, 49.0, 51.0, 1), BucketPoint(hour, 60.0, 60.0, 60.0, 3),
        BucketPoint(2 * hour, 40.0, 40.0, 40.0, 1), BucketPoint(10 * hour, 30.0, 30.0, 30.0, 1)
    )
    val unmerged = RideMath.plotRuns(pts, 0, 0, hour * 3 / 2)
    check(unmerged.size == 2 && unmerged[0].size == 3 && unmerged[1] == listOf(pts[3]))
    val merged = RideMath.plotRuns(pts, 0, 2 * hour, hour * 3 / 2)
    check(merged.size == 2 && merged[0].size == 2 && merged[1].size == 1)
    val first = merged[0][0] // 0 h (×1) and 1 h (×3) share slot 0
    check(first.count == 4 && near(first.avg, 57.5, 1e-9) && first.min == 49.0 && first.max == 60.0 && first.time == 2_700_000L)
    check(merged[0][1] == BucketPoint(2 * hour, 40.0, 40.0, 40.0, 1))
    check(RideMath.plotRuns(emptyList(), 0, hour, hour).isEmpty())
    check(RideMath.plotRuns(listOf(BucketPoint(5, Double.NaN, 0.0, 0.0)), 0, hour, hour).isEmpty())

    // Default Insights period: 24 h only when everything is from the last day.
    val now = 100 * 86_400_000L
    check(RideMath.fitsLastDay(listOf(now - hour, null, now - 20 * hour), now))
    check(!RideMath.fitsLastDay(listOf(now - hour, now - 30 * hour), now))
    check(!RideMath.fitsLastDay(listOf(null, null), now) && !RideMath.fitsLastDay(emptyList(), now))

    check(RideMath.latLngText(12.97161, 77.59456) == "12.9716, 77.5946")
    check(RideMath.formatDuration(45) == "45 s" && RideMath.formatDuration(720) == "12 min" && RideMath.formatDuration(3_900) == "1 h 05 min")

    val track = (0..100).map { RidePoint(it * 1_000L, 12.9 + it * 0.0001, 77.6, 30.0, it * 11.1, 5.0) }
    // Open in Maps: origin + destination only, never waypoints.
    val url = RideMath.routeUrl(track)!!
    check(url == "https://www.google.com/maps/dir/?api=1&origin=12.900000,77.600000&destination=12.910000,77.600000&travelmode=driving")
    check(!url.contains("waypoints"))
    check(RideMath.directionsUrl(1.0, 2.0, 3.0, 4.0) == "https://www.google.com/maps/dir/?api=1&origin=1.000000,2.000000&destination=3.000000,4.000000&travelmode=driving")
    check(RideMath.routeUrl(track.take(1)) == null)
    check(RideMath.thin(track, 10).let { it.size == 10 && it.first() == track.first() && it.last() == track.last() })
    check(RideMath.thin(track, 500) == track)

    // North up, centred, aspect kept: a due-north track is a vertical line in the middle.
    val xy = RideMath.project(listOf(track.first(), track.last()), 200f, 100f, 10f)
    check(xy.size == 4 && xy[0] == 100f && xy[2] == 100f && xy[1] == 90f && xy[3] == 10f)
    val dot = RideMath.project(listOf(track.first()), 200f, 100f)
    check(dot[0] == 100f && dot[1] == 50f)
}
