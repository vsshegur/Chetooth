package dev.codesurgen.ruta

// Synthetic positions on a neutral grid (no real places): 1e-4° of latitude ≈ 11 m.
private const val LAT = 48.0
private const val LNG = 11.0
private const val T = 1_700_000_000_000L

private fun fix(time: Long, dLat: Double = 0.0, accuracy: Double = 8.0, speed: Double = Double.NaN) =
    GeoFix(time, LAT + dLat, LNG, accuracy, speed)

fun main() {
    usability()
    rideEnd()
    rideEndVetoes()
    linkEnd()
    text()
    println("ParkingTest: all checks passed")
}

private fun usability() {
    val r = ParkingRules
    check(r.usable(fix(T), T))
    // Age: up to 2 min before the moment; a little "after" it is clock slack.
    check(r.usable(fix(T - 120_000), T) && !r.usable(fix(T - 120_001), T))
    check(r.usable(fix(T + 5_000), T) && !r.usable(fix(T + 5_001), T))
    // Accuracy: ≤ 50 m; unknown (NaN) or negative never.
    check(r.usable(fix(T, accuracy = 50.0), T) && !r.usable(fix(T, accuracy = 50.1), T))
    check(!r.usable(fix(T, accuracy = Double.NaN), T) && !r.usable(fix(T, accuracy = -1.0), T))
    // Speed: walking is fine, riding speed is not (unless the check is off); unknown speed is fine.
    check(r.usable(fix(T, speed = 5.0), T) && r.usable(fix(T, speed = 8.0), T))
    check(!r.usable(fix(T, speed = 8.1), T) && r.usable(fix(T, speed = 30.0), T, checkSpeed = false))
    // Positions: no 0,0 placeholder, nothing off the globe, nothing infinite.
    check(!r.usable(GeoFix(T, 0.0, 0.0, 5.0), T) && !r.usable(GeoFix(T, 91.0, 0.0, 5.0), T))
    check(!r.usable(GeoFix(T, Double.NaN, 1.0, 5.0), T) && !r.usable(null, T))
    check(r.valid(ParkedSpot(LAT, LNG, 12.0, T, null)) && !r.valid(ParkedSpot(LAT, LNG, 60.0, T, null)))
    check(!r.valid(ParkedSpot(0.0, 0.0, 5.0, T, null)) && !r.valid(ParkedSpot(LAT, LNG, 5.0, 0, null)))
    // A 50 m circle always fits the mini-map.
    check(ParkingRules.MAX_ACCURACY_M < ParkingRules.MAP_HALF_SPAN_M)
}

private fun rideEnd() {
    val r = ParkingRules
    val stop = fix(T - 70_000, dLat = 0.0003, accuracy = 6.0, speed = 14.0)
    val now = ParkMoment(T, fix(T, dLat = 0.0008, speed = 4.5)) // walked ~55 m on

    // Ignition off: the phone where the ignition went off; time = that moment.
    val off = ParkMoment(T - 60_000, fix(T - 61_000, dLat = 0.0001, accuracy = 4.0, speed = 0.0))
    check(r.atRideEnd(RideEndReason.IGNITION_OFF, T - 60_000, now, ignitionOff = off, stop = stop) ==
        ParkedSpot(LAT + 0.0001, LNG, 4.0, T - 60_000, null))
    // No usable fix then (too old, too vague): where the scooter last moved, at the ride's end time.
    val offStale = ParkMoment(T - 60_000, fix(T - 200_000))
    check(r.atRideEnd(RideEndReason.IGNITION_OFF, T - 60_000, now, ignitionOff = offStale, stop = stop) ==
        ParkedSpot(LAT + 0.0003, LNG, 6.0, T - 60_000, null))
    val offVague = ParkMoment(T - 60_000, fix(T - 60_000, accuracy = 80.0))
    check(r.atRideEnd(RideEndReason.IGNITION_OFF, T - 60_000, now, ignitionOff = offVague, stop = stop)!!.lat == LAT + 0.0003)
    // The last moving point is moving by definition: its speed does not disqualify it.
    check(r.atRideEnd(RideEndReason.IDLE, T - 60_000, now, stop = stop)!!.accuracyM == 6.0)

    // Link lost: the ignition-off moment still wins; else the moment the link dropped.
    val lost = ParkMoment(T - 120_000, fix(T - 121_000, dLat = 0.0002, accuracy = 9.0, speed = 3.0))
    check(r.atRideEnd(RideEndReason.LINK_LOST, T - 120_000, now, ignitionOff = off, linkLost = lost, stop = stop)!!.time == T - 60_000)
    check(r.atRideEnd(RideEndReason.LINK_LOST, T - 120_000, now, linkLost = lost, stop = stop) ==
        ParkedSpot(LAT + 0.0002, LNG, 9.0, T - 120_000, null))
    // The link-lost moment only counts for rides that ended because of it, or when tracking stopped.
    check(r.atRideEnd(RideEndReason.IDLE, T - 60_000, now, linkLost = lost, stop = stop)!!.lat == LAT + 0.0003)

    // Tracking stopped (Disconnect, app closed): link-lost moment, else the phone now, else the last stop.
    check(r.atRideEnd(RideEndReason.STOPPED, T, now, linkLost = lost, stop = stop)!!.time == T - 120_000)
    check(r.atRideEnd(RideEndReason.STOPPED, T, now, stop = stop) == ParkedSpot(LAT + 0.0008, LNG, 8.0, T, null))
    val stopRecent = fix(T - 30_000, dLat = 0.0003, accuracy = 6.0, speed = 14.0)
    check(r.atRideEnd(RideEndReason.STOPPED, T, ParkMoment(T, null), stop = stopRecent)!!.time == T)
    // Nothing usable at all.
    check(r.atRideEnd(RideEndReason.IDLE, T, ParkMoment(T, null)) == null)
    check(r.atRideEnd(RideEndReason.IGNITION_OFF, T, ParkMoment(T, null), stop = fix(T - 121_000)) == null)
}

private fun rideEndVetoes() {
    val r = ParkingRules
    val stop = fix(T - 10_000, accuracy = 6.0, speed = 20.0)
    // Disconnect mid-ride: the phone is at riding speed, so it is not parked anywhere.
    val riding = ParkMoment(T, fix(T, dLat = 0.0001, speed = 25.0))
    check(r.atRideEnd(RideEndReason.STOPPED, T, riding, stop = stop) == null)
    // A Bluetooth drop at a red light, then riding on: gone > 300 m by the time the ride ends.
    val atLight = ParkMoment(T - 120_000, fix(T - 120_000, speed = 0.0))
    val farAway = ParkMoment(T, fix(T, dLat = 0.004, speed = 6.0)) // ≈ 445 m, slowing down
    check(r.atRideEnd(RideEndReason.LINK_LOST, T - 120_000, farAway, linkLost = atLight, stop = stop) == null)
    // Walking away within 300 m is fine.
    val walked = ParkMoment(T, fix(T, dLat = 0.0025, speed = 5.0)) // ≈ 278 m
    check(r.atRideEnd(RideEndReason.LINK_LOST, T - 120_000, walked, linkLost = atLight, stop = stop)!!.time == T - 120_000)
    // A stale "now" fix vetoes nothing.
    val stale = ParkMoment(T, fix(T - 300_000, dLat = 0.01, speed = 40.0))
    check(r.atRideEnd(RideEndReason.LINK_LOST, T - 120_000, stale, linkLost = atLight) != null)
}

private fun linkEnd() {
    val r = ParkingRules
    val filtered = fix(T - 20_000, accuracy = 12.0, speed = 0.0)
    val raw = fix(T - 5_000, dLat = 0.0001, accuracy = 35.0)
    val system = fix(T - 90_000, dLat = 0.0002, accuracy = 20.0)
    // Preferred first, the spot's time is the link end.
    check(r.atLinkEnd(T, listOf(filtered, raw, system), saved = null) == ParkedSpot(LAT, LNG, 12.0, T, null))
    check(r.atLinkEnd(T, listOf(null, raw, system), saved = null)!!.accuracyM == 35.0)
    check(r.atLinkEnd(T, listOf(fix(T - 130_000), fix(T, accuracy = 60.0), system), saved = null)!!.accuracyM == 20.0)
    // The 2 min / 50 m rule.
    check(r.atLinkEnd(T, listOf(fix(T - 121_000), fix(T, accuracy = 51.0)), saved = null) == null)
    check(r.atLinkEnd(T, emptyList(), saved = null) == null)
    // The phone is moving at riding speed right now: not by the scooter, an older slow fix does not count.
    check(r.atLinkEnd(T, listOf(fix(T - 1_000, speed = 30.0), system), saved = null) == null)
    // An old fast fix does not veto a fresh slow one.
    check(r.atLinkEnd(T, listOf(fix(T - 200_000, speed = 30.0), system), saved = null)!!.accuracyM == 20.0)

    // The saved spot (e.g. from the ride's end, 2 h ago) still agrees: kept, with its time and place.
    val rideSpot = ParkedSpot(LAT + 0.0003, LNG, 5.0, T - 7_200_000, "Harbour St, Old Town")
    check(r.atLinkEnd(T, listOf(filtered), saved = rideSpot) == null)                  // ≈ 33 m apart
    check(r.stillThere(rideSpot, fix(T, dLat = 0.0003 + 0.0005, accuracy = 10.0)))    // ≈ 56 m ≤ 50 + 5 + 10
    // Too far for the link to have been up next to it: the scooter was moved, save the new spot.
    val moved = fix(T - 1_000, dLat = 0.0003 + 0.0009, accuracy = 10.0)               // ≈ 100 m
    check(!r.stillThere(rideSpot, moved))
    check(r.atLinkEnd(T, listOf(moved), saved = rideSpot) == ParkedSpot(LAT + 0.0012, LNG, 10.0, T, null))
}

private fun text() {
    val r = ParkingRules
    val spot = ParkedSpot(48.1234567, -11.5, 12.4, T, "Riverside Garden Road, Lakeside")
    check(r.walkUrl(spot) == "https://www.google.com/maps/dir/?api=1&destination=48.123457,-11.500000&travelmode=walking")
    check(r.mapUrl(spot) == "https://www.google.com/maps/search/?api=1&query=48.123457,-11.500000")
    check(r.shareText(spot) == "My scooter is parked at Riverside Garden Road, Lakeside\nhttps://www.google.com/maps/search/?api=1&query=48.123457,-11.500000")
    check(r.shareText(spot.copy(place = " ")).startsWith("My scooter is parked here\n"))
    check(r.street("Riverside Garden Road, Lakeside") == "Riverside Garden Rd")
    check(r.street("Lakeside") == "Lakeside" && r.street(null) == null && r.street("") == null)
    check(r.elapsed(59_999) == "< 1 min" && r.elapsed(-5) == "< 1 min")
    check(r.elapsed(12 * 60_000L) == "12 min" && r.elapsed(65 * 60_000L) == "1 h 05 min")
    check(r.elapsed((26 * 60 + 30) * 60_000L) == "1 d 2 h")
    check(r.accuracyText(12.4) == "± 12 m" && r.accuracyText(0.3) == "± 1 m" && r.accuracyText(49.6) == "± 50 m")
}
