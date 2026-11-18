package dev.codesurgen.ruta

import java.util.Locale
import kotlin.math.abs

/*
 * Find my scooter (F-PARK): the parked spot and the pure rules that decide it. No android.* imports
 * (host-tested by tests/ParkingTest.kt). Parking.kt keeps the spot; RideTracker feeds these rules
 * with phone fixes; Home shows it.
 */

/**
 * Where the scooter was left: the phone's position ([lat], [lng], within [accuracyM] metres) at the
 * moment Ruta saw the rider leave it ([time], epoch ms: ignition off, link lost, ride end or link
 * end), and its place name ("Riverside Garden Rd, Lakeside", see [RideMath.placeLabel]) once looked
 * up; null until then, or offline.
 */
data class ParkedSpot(val lat: Double, val lng: Double, val accuracyM: Double, val time: Long, val place: String?)

/** A moment that matters for parking ([time]: ignition off, link lost, now) and the phone's newest fix then. */
data class ParkMoment(val time: Long, val fix: GeoFix?)

object ParkingRules {
    /** A fix older than this at the moment it stands for says nothing about where the scooter is. */
    const val MAX_FIX_AGE_MS = 120_000L
    /** Worse than this, the spot could be a street away. */
    const val MAX_ACCURACY_M = 50.0
    /** Faster than this the phone is riding (the ride start speed), not standing by a parked scooter. */
    const val MAX_SPEED_KMH = RideMath.START_KMH
    /** Further than this from the chosen fix when the ride ends: the rider rode on, it was not where they parked. */
    const val MAX_WALK_M = 300.0
    /** How far from the scooter the phone can be while the Bluetooth link is still up (generous). */
    const val LINK_RANGE_M = 50.0
    /** Fix times are wall-clock estimates; a fix this far "after" its moment still counts. */
    private const val CLOCK_SLACK_MS = 5_000L

    /** Home's mini-map: metres from the spot to its top and bottom edges (a 50 m circle always fits). */
    const val MAP_HALF_SPAN_M = 60.0
    /** Home's mini-map: grid (dot) step in metres, also the scale bar. */
    const val MAP_GRID_M = 20

    /** Real coordinates (finite, on Earth, not the 0,0 "no fix" placeholder). */
    fun validPosition(lat: Double, lng: Double): Boolean =
        lat.isFinite() && lng.isFinite() && abs(lat) <= 90 && abs(lng) <= 180 && !(lat == 0.0 && lng == 0.0)

    /** A spot worth keeping: real position, known accuracy within [MAX_ACCURACY_M], a time. */
    fun valid(spot: ParkedSpot): Boolean =
        validPosition(spot.lat, spot.lng) && spot.accuracyM >= 0 && spot.accuracyM <= MAX_ACCURACY_M && spot.time > 0

    /** [fix] is at most [MAX_FIX_AGE_MS] older than [at] (and not later than it, give or take the clock slack). */
    fun fresh(fix: GeoFix, at: Long): Boolean = at - fix.time in -CLOCK_SLACK_MS..MAX_FIX_AGE_MS

    /** The phone was moving at riding speed (unknown speed is not fast). */
    fun fast(fix: GeoFix): Boolean = fix.speedKmh.isFinite() && fix.speedKmh > MAX_SPEED_KMH

    /** A fix good enough to park at for the moment [at]: real, ≤ 50 m, ≤ 2 min old and (when [checkSpeed]) not moving fast. */
    fun usable(fix: GeoFix?, at: Long, checkSpeed: Boolean = true): Boolean =
        fix != null && validPosition(fix.lat, fix.lng) && fix.accuracyM >= 0 && fix.accuracyM <= MAX_ACCURACY_M &&
            fresh(fix, at) && !(checkSpeed && fast(fix))

    /**
     * The spot when a ride ends. The rider was at the scooter, in order of preference: when the
     * ignition went off during the ride ([ignitionOff]); when the link dropped ([linkLost], they were
     * within Bluetooth range); for a ride ended because Ruta stopped tracking (Disconnect, app closed)
     * the phone [now]. Otherwise, or when that moment has no usable fix, where the scooter last moved
     * ([stop], the ride's last moving point, at [endedAt]). The spot's time is the chosen moment.
     * Nothing when the phone is moving at riding speed [now], or is now further than [MAX_WALK_M]
     * from the chosen fix (it rode on: that was a stop, not the parking place).
     */
    fun atRideEnd(
        reason: RideEndReason, endedAt: Long, now: ParkMoment,
        ignitionOff: ParkMoment? = null, linkLost: ParkMoment? = null, stop: GeoFix? = null
    ): ParkedSpot? {
        val current = now.fix?.takeIf { validPosition(it.lat, it.lng) && fresh(it, now.time) }
        if (current != null && fast(current)) return null
        val moments = listOfNotNull(ignitionOff, linkLost.takeIf { reason == RideEndReason.LINK_LOST || reason == RideEndReason.STOPPED },
            now.takeIf { reason == RideEndReason.STOPPED })
        var time = 0L
        var chosen: GeoFix? = null
        for (moment in moments) {
            val fix = moment.fix
            if (usable(fix, moment.time)) { chosen = fix; time = moment.time; break }
        }
        if (chosen == null && usable(stop, endedAt, checkSpeed = false)) { chosen = stop; time = endedAt }
        val fix = chosen ?: return null
        if (current != null && RideMath.haversineM(fix.lat, fix.lng, current.lat, current.lng) > MAX_WALK_M) return null
        return ParkedSpot(fix.lat, fix.lng, fix.accuracyM, time, null)
    }

    /**
     * The spot when the scooter link ends with no ride in progress: the first usable fix of
     * [candidates] (preferred first: Ruta's filtered fix, its raw fix, Android's last known fixes),
     * at [at]. Nothing when the newest fresh candidate is moving at riding speed, or when the [saved]
     * spot is still where the scooter is ([stillThere]): the phone at link end is only somewhere
     * within Bluetooth range of the scooter, so a saved spot that agrees (often the scooter's own
     * position from a ride end) is kept, with its place and the time it was really parked.
     */
    fun atLinkEnd(at: Long, candidates: List<GeoFix?>, saved: ParkedSpot?): ParkedSpot? {
        val fixes = candidates.filterNotNull()
        val newest = fixes.filter { validPosition(it.lat, it.lng) && fresh(it, at) }.maxByOrNull { it.time }
        if (newest != null && fast(newest)) return null
        val fix = fixes.firstOrNull { usable(it, at) } ?: return null
        if (saved != null && stillThere(saved, fix)) return null
        return ParkedSpot(fix.lat, fix.lng, fix.accuracyM, at, null)
    }

    /** The phone at [fix], linked to the scooter, is close enough to [saved] that the scooter can still be there. */
    fun stillThere(saved: ParkedSpot, fix: GeoFix): Boolean =
        RideMath.haversineM(saved.lat, saved.lng, fix.lat, fix.lng) <= LINK_RANGE_M + saved.accuracyM + fix.accuracyM

    // ------------------------------------------------------------------ text and links

    /** Google Maps walking directions to the spot (destination only: Maps starts from where the phone is). */
    fun walkUrl(spot: ParkedSpot): String =
        "https://www.google.com/maps/dir/?api=1&destination=${latLng(spot)}&travelmode=walking"

    /** A Google Maps link that opens a pin at the spot (for sharing). */
    fun mapUrl(spot: ParkedSpot): String = "https://www.google.com/maps/search/?api=1&query=${latLng(spot)}"

    /** What Share sends: the place (when known) and a Maps link. */
    fun shareText(spot: ParkedSpot): String {
        val place = spot.place?.trim()?.ifEmpty { null }
        return (if (place != null) "My scooter is parked at $place" else "My scooter is parked here") + "\n" + mapUrl(spot)
    }

    /** The street of a place label, abbreviated ("Riverside Garden Rd" from "Riverside Garden Road, Lakeside"); null without a place. */
    fun street(place: String?): String? =
        place?.substringBefore(", ")?.trim()?.ifEmpty { null }?.let(RideMath::shortPlace)

    /** How long it has been parked: "< 1 min", "12 min", "1 h 05 min", "2 d 3 h". */
    fun elapsed(ms: Long): String {
        val minutes = ms.coerceAtLeast(0) / 60_000
        return when {
            minutes < 1 -> "< 1 min"
            minutes < 60 -> "$minutes min"
            minutes < 24 * 60 -> String.format(Locale.US, "%d h %02d min", minutes / 60, minutes % 60)
            else -> "${minutes / (24 * 60)} d ${minutes % (24 * 60) / 60} h"
        }
    }

    /** "± 15 m" (whole metres, at least 1). */
    fun accuracyText(accuracyM: Double): String = "± ${Math.round(accuracyM.coerceAtLeast(1.0))} m"

    private fun latLng(spot: ParkedSpot): String = String.format(Locale.US, "%.6f,%.6f", spot.lat, spot.lng)
}
