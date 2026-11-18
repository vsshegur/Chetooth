package dev.codesurgen.ruta

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Turn-by-turn distances between Google Maps updates (2.1 F5). Maps re-posts its navigation
 * notification irregularly (2–15 s apart in the 2.0 logs) with a rounded distance, and most
 * updates carry none at all ("Head toward Ganesh Nagar Rd", "Turn left onto Ganesh Nagar Rd"), so
 * 2.0 sent 0 m most of the time. This counts both distances down with the phone's GPS distance
 * travelled (fed as a cumulative [NavOdometer] total) and is read at 1 Hz.
 *
 * Per Maps update ([update]), the maneuver distance comes from:
 *  - an explicit distance: a new anchor, unless it is the same maneuver with the same rounded
 *    value as last time and the count is still inside that value's rounding window (Maps re-posts
 *    "1.2 km" for ~100 m; restarting from 1200 on every post would freeze the count);
 *  - no distance, a maneuver seen recently (same turn and road): keep counting it down. This also
 *    covers Maps flipping between two instructions while the rider waits at the start;
 *  - no distance, a new STRAIGHT ("Head …", "Continue …") or DESTINATION: the remaining route;
 *  - otherwise 0 (Maps drops the distance when the turn is now), also for a STRAIGHT with a second
 *    maneuver ("Continue straight, then turn left": the turn after it is imminent).
 * The remaining route distance restarts from Maps on every update that has one (ProgressStyle
 * progressMax − progress, exact metres along the route) and counts down in between.
 * Outputs are rounded with [round] and never negative. Not thread-safe. Pure Kotlin (host tests).
 */
class NavCountdown {
    /** [maneuverM]: to the next maneuver; [remainingM]: to the destination, null when Maps gave none. Both rounded. */
    data class Distances(val maneuverM: Int, val remainingM: Int?)

    private enum class Mode { FIXED, REMAINING, ZERO }

    private class Anchor(val mode: Mode, val metres: Double, val travelled: Double, val mapsM: Int?, var seenAt: Long)

    /** Recently seen maneuvers, oldest first; the last one is current. */
    private val anchors = LinkedHashMap<String, Anchor>()
    private var current: Anchor? = null
    private var remainingBase: Double? = null
    private var remainingTravelled = 0.0

    /** One Maps update (not "Rerouting…"). [travelledM] is the odometer total now; [nowMs] any monotonic clock. */
    fun update(cue: MapsCue, travelledM: Double, nowMs: Long) {
        cue.remainingMeters?.let { remainingBase = it.toDouble(); remainingTravelled = travelledM }
        expire(travelledM, nowMs)
        val key = key(cue)
        val known = anchors[key]
        val explicit = cue.distanceMeters
        val anchor = when {
            explicit != null ->
                if (known != null && known.mapsM == explicit && abs(counted(known, travelledM) - explicit) <= tolerance(explicit)) known
                else Anchor(Mode.FIXED, explicit.toDouble(), travelledM, explicit, nowMs)
            known != null -> known
            (cue.turn == NavTurn.STRAIGHT && cue.thenTurn == null) || cue.turn == NavTurn.DESTINATION -> Anchor(Mode.REMAINING, 0.0, travelledM, null, nowMs)
            else -> Anchor(Mode.ZERO, 0.0, travelledM, null, nowMs)
        }
        anchor.seenAt = nowMs
        anchors.remove(key)
        anchors[key] = anchor
        while (anchors.size > MEMORY) anchors.remove(anchors.keys.first())
        current = anchor
    }

    /** Current distances for odometer total [travelledM]; maneuver 0 before the first update. */
    fun at(travelledM: Double): Distances =
        Distances(round(current?.let { counted(it, travelledM) } ?: 0.0), remaining(travelledM)?.let(::round))

    fun reset() {
        anchors.clear(); current = null; remainingBase = null; remainingTravelled = 0.0
    }

    private fun remaining(travelledM: Double): Double? =
        remainingBase?.let { max(0.0, it - max(0.0, travelledM - remainingTravelled)) }

    private fun counted(anchor: Anchor, travelledM: Double): Double = when (anchor.mode) {
        Mode.FIXED -> max(0.0, anchor.metres - max(0.0, travelledM - anchor.travelled))
        Mode.REMAINING -> remaining(travelledM) ?: 0.0
        Mode.ZERO -> 0.0
    }

    /** Forgets maneuvers not seen for [FORGET_MS] or already passed by [PASSED_M]. */
    private fun expire(travelledM: Double, nowMs: Long) {
        anchors.entries.removeAll { (_, a) ->
            nowMs - a.seenAt > FORGET_MS || (a.mode == Mode.FIXED && travelledM - a.travelled > a.metres + PASSED_M)
        }
    }

    companion object {
        const val MEMORY = 4
        const val FORGET_MS = 10 * 60_000L
        const val PASSED_M = 150.0

        /** Same maneuver: same cluster symbol and road. */
        fun key(cue: MapsCue): String = "${cue.turn}|${cue.road.trim().lowercase()}"

        /** 10 m steps below 1 km, 0.1 km steps above; never negative. */
        fun round(metres: Double): Int {
            if (!(metres > 0.0)) return 0
            val tens = (metres / 10.0).roundToInt() * 10
            return if (tens < 1000) tens else (metres / 100.0).roundToInt() * 100
        }

        /** How far a count may drift from Maps' rounded value before Maps wins. */
        fun tolerance(mapsM: Int): Double = when {
            mapsM < 1000 -> max(30.0, mapsM * 0.1)
            mapsM < 10_000 -> 100.0
            else -> 1000.0
        }
    }
}

/**
 * Cumulative distance from location fixes for [NavCountdown]. Drops fixes worse than
 * [maxAccuracyM], out of order, or implying > 70 m/s (a run of 5 such fixes restarts from the new
 * position). A slow fix (Doppler speed < 1 m/s or none) within max(5 m, accuracy) of the last
 * counted position is jitter and adds nothing, so a stop at a signal does not eat the countdown.
 * Network fixes count only while GPS has been silent for 5 s; switching source re-anchors without
 * adding distance (the two disagree by tens of metres). Pure Kotlin (host tests).
 */
class NavOdometer(private val maxAccuracyM: Double = 30.0) {
    var totalM = 0.0; private set

    private class Fix(val lat: Double, val lng: Double, val gps: Boolean)

    private var anchor: Fix? = null
    private var lastTime = 0L
    private var lastGpsAt = Long.MIN_VALUE
    private var jumps = 0

    /** [timeMs] monotonic; [speedMps] NaN when the fix has none. True when distance was added. */
    fun add(timeMs: Long, lat: Double, lng: Double, accuracyM: Double, speedMps: Double, gps: Boolean): Boolean {
        if (!lat.isFinite() || !lng.isFinite() || abs(lat) > 90 || abs(lng) > 180 || (lat == 0.0 && lng == 0.0)) return false
        if (!(accuracyM <= maxAccuracyM)) return false
        if (gps) lastGpsAt = timeMs
        else if (lastGpsAt != Long.MIN_VALUE && timeMs - lastGpsAt in 0 until GPS_PREFERRED_MS) return false
        val from = anchor
        if (from == null || from.gps != gps) { restart(timeMs, lat, lng, gps); return false }
        val dt = timeMs - lastTime
        if (dt <= 0) return false
        val d = haversineM(from.lat, from.lng, lat, lng)
        if (d * 1000.0 / dt > MAX_MPS) {
            if (++jumps >= MAX_JUMPS) restart(timeMs, lat, lng, gps)
            return false
        }
        jumps = 0
        lastTime = timeMs
        if (!(speedMps >= MOVING_MPS) && d < max(MIN_STEP_M, accuracyM)) return false
        totalM += d
        anchor = Fix(lat, lng, gps)
        return true
    }

    fun reset() {
        totalM = 0.0; pause()
    }

    /** Location stopped: keep [totalM], but the next fix starts a new leg (the gap is not counted). */
    fun pause() {
        anchor = null; lastTime = 0; lastGpsAt = Long.MIN_VALUE; jumps = 0
    }

    private fun restart(timeMs: Long, lat: Double, lng: Double, gps: Boolean) {
        anchor = Fix(lat, lng, gps); lastTime = timeMs; jumps = 0
    }

    companion object {
        const val MAX_MPS = 70.0
        const val MAX_JUMPS = 5
        const val MOVING_MPS = 1.0
        const val MIN_STEP_M = 5.0
        const val GPS_PREFERRED_MS = 5_000L
        private const val EARTH_RADIUS_M = 6_371_008.8

        fun haversineM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
            val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
            val dp = p2 - p1; val dl = Math.toRadians(lng2 - lng1)
            val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
            return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
        }
    }
}
