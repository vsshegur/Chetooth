package dev.codesurgen.ruta

import java.util.Calendar
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Demo mode's synthetic world (FEATURES F-DEMO). Pure Kotlin (no android.* imports): host-tested by
 * tests/DemoDataTest.kt.
 *
 * A neutral fictional city on a square street grid ("Riverside Rd", "Old Town", "Harbour St"…; no real
 * rider's area) placed at round coordinates, a month of history (14 rides with GPS tracks, charging
 * sessions, BMS readings every 10 minutes, every 2 minutes on the last day) and the live scenarios
 * (riding speed profile, a Maps-like route, a charging curve). Everything is deterministic: the same
 * seed time gives the same history, so a recreated activity rebuilds exactly the rides it seeded, and
 * a scenario's state is a function of the time since it started (nothing accumulates, nothing drifts).
 */

/** What demo mode simulates. [key] is the `demo` intent extra (`--es demo riding`); `off` ends demo mode. */
enum class DemoScenario(val key: String, val label: String, val icon: String, val detail: String) {
    PARKED("parked", "Parked", "local_parking", "Offline, parked nearby"),
    RIDING("riding", "Riding", "electric_moped", "Live speed and ride"),
    NAVIGATING("navigating", "Navigating", "navigation", "Directions and range check"),
    CHARGING("charging", "Charging", "bolt", "Charge rising, full-in time"),
    CALL("call", "Call", "call", "Incoming call"),
    MUSIC("music", "Music", "music_note", "Now playing on the scooter");

    companion object {
        /** The intent value that ends demo mode. */
        const val OFF = "off"

        /** Intent value → scenario (case-insensitive); null for [OFF] and unknown values. */
        fun of(key: String?): DemoScenario? = key?.trim()?.let { k -> entries.firstOrNull { it.key.equals(k, ignoreCase = true) } }
    }
}

/** A named spot on the demo grid: [x]/[y] in blocks east/north of the city origin. */
data class DemoPlace(val street: String, val area: String, val x: Int, val y: Int) {
    /** The stored place label, in the "Street, Area" form rides use ([RideMath.placeLabel]). */
    val label: String get() = "$street, $area"
}

/** One seeded ride and its GPS track. */
data class DemoRide(val ride: Ride, val points: List<RidePoint>)

/** Where the demo scooter was left (Parking's spot while demo mode is on). */
data class DemoSpot(val lat: Double, val lng: Double, val accuracyM: Double, val time: Long, val place: String)

/** The live ride in a moving scenario. */
data class DemoRideLive(
    val startedAt: Long, val distanceM: Double, val durationS: Long, val speedKmh: Double,
    val peakKmh: Double, val avgKmh: Double, val socStart: Double, val startPlace: String
)

/** The Maps cue being followed, with its counted-down distances (rounded like [NavCountdown]). */
data class DemoNav(val cue: MapsCue, val maneuverM: Int, val remainingM: Int)

/**
 * Live demo values at one moment, as plain numbers (Demo.kt maps them onto Host's types). Charges are
 * percent; [status]/[packStatus] are BMS codes (4 = Drive; 0 pack 0 / 2 packs 1–2 = Standby).
 */
data class DemoLive(
    val scenario: DemoScenario,
    val linked: Boolean,
    val connectedAt: Long,
    val soc: Double, val socFront: Double, val socRear: Double,
    val energy: Double, val energyFront: Double, val energyRear: Double,
    val health: Double, val healthFront: Double, val healthRear: Double,
    val temp: Double, val tempFront: Double, val tempRear: Double,
    val status: Int, val packStatus: Int,
    val ignitionOn: Boolean, val handleLocked: Boolean,
    val ride: DemoRideLive?,
    val charge: ChargeSession?,
    val nav: DemoNav?
)

/** A demo music track. */
data class DemoTrack(val title: String, val artist: String, val durationMs: Long)

/**
 * The seeded month: [rides] (oldest first, ids 1…), [charges] (oldest first) and the BMS readings
 * ([forEachReading]) that go with them. [socNow] is the scooter charge at [seedAt].
 */
class DemoHistory internal constructor(
    val seedAt: Long,
    val rides: List<DemoRide>,
    val charges: List<ChargeSession>,
    private val segments: List<DemoData.Segment>
) {
    val socNow: Double get() = segments.last().socEnd

    /** The efficiency the demo range uses: learned from the seeded rides, like the real store's. */
    val efficiency: RangeEfficiency by lazy { RangeModel.efficiency(rides.map { it.ride }) }

    /** The newest ride (Home's last-ride row). */
    val lastRide: Ride? get() = rides.lastOrNull()?.ride

    /** Where the newest ride ended: the parked scenario's spot. */
    val parkedSpot: DemoSpot? get() = lastRide?.let { DemoSpot(it.endLat, it.endLng, 6.0, it.end, it.endPlace ?: "") }

    /** Charge on pack 0 at [time] (clamped to the seeded span). */
    fun socAt(time: Long): Double = DemoData.socAt(segments, time)

    /**
     * Every seeded reading in time order: per sample SOC, ENERGY and TEMPERATURE for packs 0–2;
     * HEALTH every hour; STATUS on every change and every hour. Generated on the fly (~50 000 rows).
     */
    fun forEachReading(action: (Reading) -> Unit) = DemoData.readings(this, segments, action)
}

object DemoData {
    // ================================================================== city

    /** Grid origin: round coordinates, so a demo route opened in Maps is plainly not a real ride. */
    const val CITY_LAT = 45.0
    const val CITY_LNG = 7.0
    /** One city block. */
    const val BLOCK_M = 280.0
    private const val M_PER_DEG_LAT = 111_320.0

    const val SCOOTER_NAME = "Demo scooter"
    /** Shown where the scooter's Bluetooth name usually is (Home's subtitle): never a real device name. */
    const val DEVICE_NAME = "Simulated data"
    /** A locally administered placeholder address; nothing ever connects to it. */
    const val DEVICE_MAC = "02:00:00:00:00:00"

    val HOME = DemoPlace("Elm St", "Hillcrest", -9, 5)
    val WORK = DemoPlace("Station Rd", "Northgate", 2, 14)
    val LAKESIDE = DemoPlace("Riverside Rd", "Lakeside", 9, 6)
    val OLD_TOWN = DemoPlace("Harbour St", "Old Town", 0, 0)
    val ORCHARD = DemoPlace("Orchard Way", "Riverside", 12, -3)
    val MARKET = DemoPlace("Market St", "Old Town", 3, 2)
    val PARK = DemoPlace("Park Ave", "Lakeside", 11, 9)
    val MILL = DemoPlace("Mill Ln", "Hillcrest", -6, 8)
    val QUAY = DemoPlace("Quay Rd", "Harbour", -2, -8)
    val CANAL = DemoPlace("Canal St", "Harbour", -4, -5)

    /** Every place name the demo uses (tests check none of them is a real rider's area). */
    val PLACES = listOf(HOME, WORK, LAKESIDE, OLD_TOWN, ORCHARD, MARKET, PARK, MILL, QUAY, CANAL)

    fun lat(yM: Double): Double = CITY_LAT + yM / M_PER_DEG_LAT
    fun lng(xM: Double): Double = CITY_LNG + xM / (M_PER_DEG_LAT * cos(Math.toRadians(CITY_LAT)))

    // ================================================================== history

    const val DAY_MS = 86_400_000L
    const val DAYS = 30
    /** Readings every 10 minutes, every 2 minutes on the last day (the 24 h charts' detail). */
    const val SAMPLE_MS = 10 * 60_000L
    const val RECENT_SAMPLE_MS = 2 * 60_000L
    /** The newest ride ended this long before the seed (the parked spot's age when the demo starts). */
    const val LAST_RIDE_AGO_MS = 12 * 60_000L
    private const val START_SOC = 78.0
    /** Standby drain (BMS and telematics) per day. */
    private const val IDLE_PER_DAY = 0.15
    /** Front pack sits slightly above the rear: the "Balance" a real scooter shows. */
    const val PACK_OFFSET = 0.4
    const val HEALTH_FRONT = 98.4
    const val HEALTH_REAR = 97.6

    private class PlannedRide(val daysAgo: Int, val hour: Int, val minute: Int, val from: DemoPlace, val to: DemoPlace, val cruiseKmh: Double, val kmPerPercent: Double)
    private class PlannedCharge(val daysAgo: Int, val hour: Int, val minute: Int, val target: Double, val perHour: Double)

    /** 13 rides on earlier days (commutes, errands, a lakeside evening); the 14th ends [LAST_RIDE_AGO_MS] before the seed. */
    private val PLAN = listOf(
        PlannedRide(29, 8, 12, HOME, WORK, 38.0, 1.14),
        PlannedRide(29, 18, 5, WORK, HOME, 36.0, 1.18),
        PlannedRide(27, 10, 40, HOME, ORCHARD, 40.0, 1.10),
        PlannedRide(27, 12, 15, ORCHARD, HOME, 37.0, 1.16),
        PlannedRide(22, 17, 50, WORK, LAKESIDE, 34.0, 1.21),
        PlannedRide(22, 19, 10, LAKESIDE, HOME, 39.0, 1.19),
        PlannedRide(11, 11, 0, HOME, PARK, 38.0, 1.15),
        PlannedRide(11, 13, 20, PARK, OLD_TOWN, 35.0, 1.17),
        PlannedRide(11, 14, 40, OLD_TOWN, HOME, 36.0, 1.13),
        PlannedRide(6, 10, 30, HOME, ORCHARD, 40.0, 1.12),
        PlannedRide(6, 12, 5, ORCHARD, HOME, 38.0, 1.18),
        PlannedRide(1, 8, 15, HOME, WORK, 38.0, 1.16),
        PlannedRide(1, 17, 55, WORK, HOME, 37.0, 1.17)
    )
    private val LAST = PlannedRide(0, 0, 0, HOME, LAKESIDE, 38.0, 1.15)

    /** Home charger sessions; the last one stops at 80 % (the charge-target alert's default). */
    private val CHARGES = listOf(
        PlannedCharge(26, 20, 30, 100.0, 17.0),
        PlannedCharge(10, 21, 0, 88.0, 16.5),
        PlannedCharge(2, 20, 45, 80.0, 18.0)
    )

    internal enum class Kind { IDLE, RIDE, CHARGE }

    /** The charge timeline: contiguous linear pieces from the seed's first day to [seedAt]. */
    internal class Segment(val start: Long, val end: Long, val socStart: Double, val socEnd: Double, val kind: Kind)

    /**
     * The demo month ending at [seedAt] (local time zone: rides happen at commuting hours). Same input,
     * same output.
     */
    fun history(seedAt: Long): DemoHistory {
        val from = seedAt - DAYS * DAY_MS
        // 1. Ride tracks (distance decides the charge each ride uses).
        val drives = ArrayList<Pair<PlannedRide, List<RidePoint>>>()
        PLAN.forEachIndexed { index, plan ->
            val start = localTime(seedAt, plan.daysAgo, plan.hour, plan.minute)
            drives += plan to drive(path(plan.from, plan.to, index), start, plan.cruiseKmh, Random(1_000L + index))
        }
        // The newest ride: driven from a provisional start, then moved so it ends LAST_RIDE_AGO_MS before the seed.
        val lastTrack = drive(path(LAST.from, LAST.to, PLAN.size), 0L, LAST.cruiseKmh, Random(1_000L + PLAN.size))
        val shift = seedAt - LAST_RIDE_AGO_MS - lastTrack.last().time
        drives += LAST to lastTrack.map { it.copy(time = it.time + shift) }

        // 2. Charge timeline: idle drain, rides, home charging.
        class Event(val start: Long, val ride: Int = -1, val charge: PlannedCharge? = null)
        val events = ArrayList<Event>()
        drives.forEachIndexed { i, (_, points) -> events += Event(points.first().time, ride = i) }
        CHARGES.forEach { events += Event(localTime(seedAt, it.daysAgo, it.hour, it.minute), charge = it) }
        events.sortBy { it.start }
        val segments = ArrayList<Segment>()
        val socs = arrayOfNulls<Pair<Double, Double>>(drives.size)
        val sessions = ArrayList<ChargeSession>()
        var t = from
        var soc = START_SOC
        fun idleTo(time: Long) {
            if (time <= t) return
            val next = (soc - IDLE_PER_DAY * (time - t) / DAY_MS).coerceAtLeast(0.0)
            segments += Segment(t, time, soc, next, Kind.IDLE)
            soc = next; t = time
        }
        events.forEachIndexed { index, event ->
            idleTo(event.start)
            if (event.ride >= 0) {
                val (plan, points) = drives[event.ride]
                val used = points.last().distanceM / 1000.0 / plan.kmPerPercent
                val end = points.last().time
                val after = (soc - used).coerceAtLeast(0.0)
                segments += Segment(t, end, soc, after, Kind.RIDE)
                socs[event.ride] = round2(soc) to round2(after)
                soc = after; t = end
            } else {
                val plan = event.charge!!
                if (plan.target <= soc + 1.0) return@forEachIndexed
                val nextStart = events.getOrNull(index + 1)?.start ?: seedAt
                val wanted = t + ((plan.target - soc) / plan.perHour * 3_600_000.0).toLong()
                val end = min(wanted, nextStart - 10 * 60_000L)
                if (end <= t + 10 * 60_000L) return@forEachIndexed
                val after = soc + plan.perHour * (end - t) / 3_600_000.0
                segments += Segment(t, end, soc, after, Kind.CHARGE)
                sessions += ChargeSession(t, end, round2(soc), round2(after), sessions.size + 1L)
                soc = after; t = end
            }
        }
        idleTo(seedAt)
        if (segments.isEmpty() || segments.last().end < seedAt) segments += Segment(t, seedAt, soc, soc, Kind.IDLE)

        // 3. Ride rows.
        val rides = drives.mapIndexed { i, (plan, points) ->
            val (socStart, socEnd) = socs[i]!!
            DemoRide(summary(i + 1L, plan.from, plan.to, points, socStart, socEnd), points)
        }
        return DemoHistory(seedAt, rides, sessions, segments)
    }

    /** [seedAt]'s day minus [daysAgo], at [hour]:[minute] local time. */
    private fun localTime(seedAt: Long, daysAgo: Int, hour: Int, minute: Int): Long = Calendar.getInstance().apply {
        timeInMillis = seedAt
        add(Calendar.DAY_OF_YEAR, -daysAgo)
        set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun round2(value: Double) = (value * 100).roundToInt() / 100.0

    private fun summary(id: Long, from: DemoPlace, to: DemoPlace, points: List<RidePoint>, socStart: Double, socEnd: Double): Ride {
        val first = points.first(); val last = points.last()
        var moving = 0L
        for (i in 1 until points.size) if (points[i].speedKmh >= 3.0 || points[i - 1].speedKmh >= 3.0) moving += points[i].time - points[i - 1].time
        val movingS = moving / 1000
        val distance = last.distanceM
        return Ride(
            id = id, start = first.time, end = last.time,
            startLat = first.lat, startLng = first.lng, endLat = last.lat, endLng = last.lng,
            startPlace = from.label, endPlace = to.label,
            distanceM = distance, durationS = (last.time - first.time) / 1000, movingS = movingS,
            peakKmh = points.maxOf { it.speedKmh }, avgKmh = if (movingS > 0) distance / movingS * 3.6 else 0.0,
            socStart = socStart, socEnd = socEnd
        )
    }

    /** Street path between two places in metres: Manhattan legs, sometimes with a jog through a middle street. */
    private fun path(from: DemoPlace, to: DemoPlace, index: Int): List<Pair<Double, Double>> {
        val corners = ArrayList<Pair<Int, Int>>()
        corners += from.x to from.y
        val dx = to.x - from.x
        when {
            abs(dx) >= 4 && index % 3 == 0 -> { val mid = from.x + dx / 2; corners += mid to from.y; corners += mid to to.y }
            index % 2 == 0 -> corners += to.x to from.y
            else -> corners += from.x to to.y
        }
        corners += to.x to to.y
        val distinct = corners.filterIndexed { i, p -> i == 0 || p != corners[i - 1] }
        return distinct.map { (x, y) -> x * BLOCK_M to y * BLOCK_M }
    }

    private class Constraint(val at: Double, val maxMps: Double, val dwellS: Int)

    /**
     * Rides [path] from [start]: accelerates at 1.6 m/s², brakes at 2.2 m/s², slows to 16 km/h at
     * corners and to 14–25 km/h in traffic, stops at some lights (6–34 s) and at the end; cruise speed
     * wanders ±6.5 km/h around [cruiseKmh].
     * One point every 2 s (and at every stop), ±1.5 m GPS wobble, cumulative distance along the path.
     */
    private fun drive(path: List<Pair<Double, Double>>, start: Long, cruiseKmh: Double, random: Random): List<RidePoint> {
        val legs = path.zipWithNext()
        val lengths = legs.map { (a, b) -> abs(b.first - a.first) + abs(b.second - a.second) }
        val total = lengths.sum()
        val constraints = ArrayList<Constraint>()
        var along = 0.0
        lengths.forEachIndexed { i, length ->
            // Lights at some crossings inside the leg (not right after a corner), traffic at others.
            var at = BLOCK_M
            while (at < length - 1.0) {
                val roll = random.nextDouble()
                if (at > BLOCK_M * 1.5 && roll < 0.35) constraints += Constraint(along + at, 0.0, 6 + random.nextInt(29))
                else if (roll > 0.6) constraints += Constraint(along + at, (14 + random.nextInt(12)) / 3.6, 0)
                at += BLOCK_M
            }
            along += length
            if (i < lengths.size - 1) constraints += Constraint(along, 16 / 3.6, 0)
        }
        constraints += Constraint(total, 0.0, 0)
        constraints.sortBy { it.at }

        val points = ArrayList<RidePoint>()
        val phase = random.nextDouble() * 10
        var s = 0.0; var v = 0.0; var t = 0
        var next = 0
        fun emit(stopped: Boolean) {
            val (x, y) = position(path, lengths, s)
            val wobble = if (stopped) 0.0 else random.nextGaussian() * 1.5
            val horizontal = segmentAt(lengths, s).let { legs[it].first.second == legs[it].second.second }
            val px = if (horizontal) x else x + wobble
            val py = if (horizontal) y + wobble else y
            val speed = if (stopped) 0.0 else max(0.0, v * 3.6 + random.nextGaussian() * 0.4)
            points += RidePoint(start + t * 1000L, lat(py), lng(px), (speed * 10).roundToInt() / 10.0, s, 3.5 + random.nextDouble() * 4.5)
        }
        emit(stopped = true)
        while (next < constraints.size && t < 4 * 3600) {
            val c = constraints[next]
            val cruise = (cruiseKmh + 4.5 * sin(t / 31.0 + phase) + 2.0 * sin(t / 9.7 + phase * 2)) / 3.6
            val brakeLimit = sqrt(c.maxMps * c.maxMps + 2 * DEC * max(0.0, c.at - s))
            val allowed = min(cruise, brakeLimit)
            v = if (v < allowed) min(v + ACC, allowed) else max(v - DEC * 1.5, allowed)
            if (c.maxMps == 0.0 && (c.at - s < 1.5 || s + v >= c.at)) {
                // Arrive at the stop line, wait, go on (or end the ride).
                s = c.at; v = 0.0; t += 1
                emit(stopped = true)
                next++
                if (next >= constraints.size) break
                repeat(c.dwellS) { t += 1; if (t % 6 == 0) emit(stopped = true) }
                continue
            }
            if (s + v >= c.at) next++ // A corner taken at speed.
            s = min(total, s + v)
            t += 1
            if (t % 2 == 0) emit(stopped = false)
        }
        return points
    }

    private const val ACC = 1.6
    private const val DEC = 2.2

    private fun segmentAt(lengths: List<Double>, s: Double): Int {
        var along = 0.0
        lengths.forEachIndexed { i, l -> if (s <= along + l || i == lengths.size - 1) return i; along += l }
        return lengths.size - 1
    }

    private fun position(path: List<Pair<Double, Double>>, lengths: List<Double>, s: Double): Pair<Double, Double> {
        var along = 0.0
        for (i in lengths.indices) {
            val l = lengths[i]
            if (s <= along + l || i == lengths.size - 1) {
                val f = if (l > 0) ((s - along) / l).coerceIn(0.0, 1.0) else 0.0
                val (a, b) = path[i] to path[i + 1]
                return a.first + (b.first - a.first) * f to a.second + (b.second - a.second) * f
            }
            along += l
        }
        return path.last()
    }

    internal fun socAt(segments: List<Segment>, time: Long): Double {
        if (segments.isEmpty()) return 0.0
        if (time <= segments.first().start) return segments.first().socStart
        for (s in segments) if (time < s.end) return interpolate(s, time)
        return segments.last().socEnd
    }

    private fun interpolate(s: Segment, time: Long): Double {
        if (s.end <= s.start) return s.socEnd
        val f = ((time - s.start).toDouble() / (s.end - s.start)).coerceIn(0.0, 1.0)
        return s.socStart + (s.socEnd - s.socStart) * f
    }

    /** Afternoon-warm, night-cool air (°C): 19 at 03:00, 28 at 15:00. */
    private fun ambient(calendar: Calendar, time: Long): Double {
        calendar.timeInMillis = time
        val hour = calendar.get(Calendar.HOUR_OF_DAY) + calendar.get(Calendar.MINUTE) / 60.0
        return 23.5 + 4.5 * sin(2 * Math.PI * (hour - 9) / 24)
    }

    /** Pack warming from the latest ride (+3.5 °C) or charge (+5 °C): rises over ~10 min, fades over ~30 min after. */
    private fun heat(segment: Segment?, time: Long): Double {
        if (segment == null || time < segment.start) return 0.0
        val amplitude = if (segment.kind == Kind.RIDE) 3.5 else 5.0
        val warm = { seconds: Double -> amplitude * (1 - exp(-seconds / 600.0)) }
        return if (time <= segment.end) warm((time - segment.start) / 1000.0)
        else warm((segment.end - segment.start) / 1000.0) * exp(-(time - segment.end) / 1000.0 / 1800.0)
    }

    internal fun readings(history: DemoHistory, segments: List<Segment>, action: (Reading) -> Unit) {
        val seedAt = history.seedAt
        val from = seedAt - DAYS * DAY_MS
        val recent = seedAt - DAY_MS
        val calendar = Calendar.getInstance()
        var segment = 0
        var lastActive: Segment? = null
        var lastStatus = -1
        var lastHour = Long.MIN_VALUE
        var time = from - from % SAMPLE_MS + SAMPLE_MS
        while (time <= seedAt) {
            while (segment < segments.size - 1 && time >= segments[segment].end) {
                if (segments[segment].kind != Kind.IDLE) lastActive = segments[segment]
                segment++
            }
            val current = segments[segment]
            if (current.kind != Kind.IDLE && time >= current.start) lastActive = current
            val soc = if (time < current.start) current.socStart else interpolate(current, time)
            val front = (soc + PACK_OFFSET).coerceIn(0.0, 100.0)
            val rear = (soc - PACK_OFFSET).coerceIn(0.0, 100.0)
            val air = ambient(calendar, time)
            val warm = heat(lastActive, time)
            val tFront = air + warm
            val tRear = air + warm + 0.6
            val riding = current.kind == Kind.RIDE && time >= current.start

            fun put(pack: Int, metric: Metric, value: Double) = action(Reading(pack, metric, round2(value), time))
            put(0, Metric.SOC, soc); put(1, Metric.SOC, front); put(2, Metric.SOC, rear)
            put(0, Metric.ENERGY, soc - 1.6); put(1, Metric.ENERGY, front - 1.4); put(2, Metric.ENERGY, rear - 1.9)
            put(0, Metric.TEMPERATURE, (tFront + tRear) / 2); put(1, Metric.TEMPERATURE, tFront); put(2, Metric.TEMPERATURE, tRear)
            val hour = time / 3_600_000L
            val status = if (riding) 4 else 0
            if (hour != lastHour || status != lastStatus) {
                put(0, Metric.STATUS, status.toDouble())
                put(1, Metric.STATUS, if (riding) 4.0 else 2.0)
                put(2, Metric.STATUS, if (riding) 4.0 else 2.0)
                lastStatus = status
            }
            if (hour != lastHour) {
                // Health fades by a tenth of a point over the month.
                val age = (time - from).toDouble() / (DAYS * DAY_MS)
                val f = HEALTH_FRONT + 0.1 - 0.1 * age
                val r = HEALTH_REAR + 0.1 - 0.1 * age
                put(1, Metric.HEALTH, f); put(2, Metric.HEALTH, r); put(0, Metric.HEALTH, (f + r) / 2)
                lastHour = hour
            }
            if (time == seedAt) break
            val step = if (time >= recent) RECENT_SAMPLE_MS else SAMPLE_MS
            time = min(time + step, seedAt)
        }
    }

    // ================================================================== live: speed profile

    /**
     * One 194 s stretch of town riding, looped: (seconds, km/h) keyframes, linear between them; three
     * light stops (12 s, 14 s, 8 s), cruising 33–42 km/h. Starts and ends standing still.
     */
    private val CYCLE = doubleArrayOf(
        0.0, 0.0, 3.0, 9.0, 6.0, 20.0, 10.0, 31.0, 14.0, 38.0, 19.0, 42.0, 26.0, 40.0, 33.0, 41.0, 40.0, 37.0, 45.0, 27.0,
        49.0, 14.0, 52.0, 0.0, 64.0, 0.0, 67.0, 11.0, 71.0, 24.0, 76.0, 34.0, 82.0, 39.0, 90.0, 41.0, 98.0, 38.0,
        105.0, 33.0, 110.0, 35.0, 117.0, 39.0, 124.0, 36.0, 129.0, 24.0, 133.0, 12.0, 136.0, 0.0, 150.0, 0.0,
        153.0, 10.0, 157.0, 22.0, 162.0, 32.0, 168.0, 36.0, 176.0, 33.0, 181.0, 20.0, 184.0, 8.0, 186.0, 0.0, 194.0, 0.0
    )
    private val KEYS = CYCLE.size / 2
    val CYCLE_S: Double = CYCLE[(KEYS - 1) * 2]
    const val TOP_KMH = 42.0

    /** Distance at each keyframe (m) and standing time at each keyframe (s). */
    private val keyDistance = DoubleArray(KEYS)
    private val keyStopped = DoubleArray(KEYS)

    init {
        for (k in 1 until KEYS) {
            val dt = CYCLE[k * 2] - CYCLE[(k - 1) * 2]
            val v0 = CYCLE[(k - 1) * 2 + 1]; val v1 = CYCLE[k * 2 + 1]
            keyDistance[k] = keyDistance[k - 1] + (v0 + v1) / 2 / 3.6 * dt
            keyStopped[k] = keyStopped[k - 1] + if (v0 == 0.0 && v1 == 0.0) dt else 0.0
        }
    }

    /** Metres per loop of the speed profile. */
    val CYCLE_M: Double get() = keyDistance[KEYS - 1]

    private fun key(t: Double): Int {
        var k = 1
        while (k < KEYS - 1 && CYCLE[k * 2] <= t) k++
        return k
    }

    /** Speed (km/h) [seconds] into the looped profile. */
    fun speedAt(seconds: Double): Double {
        val t = ((seconds % CYCLE_S) + CYCLE_S) % CYCLE_S
        val k = key(t)
        val t0 = CYCLE[(k - 1) * 2]; val t1 = CYCLE[k * 2]
        val v0 = CYCLE[(k - 1) * 2 + 1]; val v1 = CYCLE[k * 2 + 1]
        return v0 + (v1 - v0) * ((t - t0) / (t1 - t0)).coerceIn(0.0, 1.0)
    }

    /** Metres travelled in the first [seconds] of the looped profile (non-decreasing). */
    fun distanceAt(seconds: Double): Double {
        if (seconds <= 0) return 0.0
        val loops = floor(seconds / CYCLE_S)
        val t = seconds - loops * CYCLE_S
        val k = key(t)
        val t0 = CYCLE[(k - 1) * 2]
        val v0 = CYCLE[(k - 1) * 2 + 1]
        return loops * CYCLE_M + keyDistance[k - 1] + (v0 + speedAt(t)) / 2 / 3.6 * (t - t0)
    }

    /** Seconds spent moving in the first [seconds] (standing at lights excluded). */
    fun movingAt(seconds: Double): Double {
        if (seconds <= 0) return 0.0
        val loops = floor(seconds / CYCLE_S)
        val t = seconds - loops * CYCLE_S
        val k = key(t)
        val t0 = CYCLE[(k - 1) * 2]
        val standing = if (CYCLE[(k - 1) * 2 + 1] == 0.0 && CYCLE[k * 2 + 1] == 0.0) t - t0 else 0.0
        return seconds - (loops * keyStopped[KEYS - 1] + keyStopped[k - 1] + standing)
    }

    /** Highest speed reached in the first [seconds]. */
    fun peakAt(seconds: Double): Double {
        if (seconds >= CYCLE_S) return TOP_KMH
        var peak = speedAt(seconds)
        for (k in 0 until KEYS) if (CYCLE[k * 2] <= seconds) peak = max(peak, CYCLE[k * 2 + 1])
        return peak
    }

    /** Earliest time (s) at which [distanceAt] reaches [metres]. */
    fun timeAt(metres: Double): Double {
        if (metres <= 0) return 0.0
        var lo = 0.0
        var hi = CYCLE_S * (floor(metres / CYCLE_M) + 2)
        repeat(60) {
            val mid = (lo + hi) / 2
            if (distanceAt(mid) >= metres) hi = mid else lo = mid
        }
        return hi
    }

    /** Start times (s) of the standing stretches within one loop. */
    private val STOPS: List<Double> = (1 until KEYS).filter { CYCLE[(it - 1) * 2 + 1] == 0.0 && CYCLE[it * 2 + 1] == 0.0 }.map { CYCLE[(it - 1) * 2] }

    // ================================================================== live: scenarios

    /** A moving scenario's ride began this long before the scenario (the live card is never at 0). */
    const val PREROLL_S = 253.0
    /** Riding, music and call scenarios restart every hour (the battery would otherwise run down). */
    const val RIDE_PERIOD_S = 3600.0
    /**
     * Charging: 1 % every 45 s (≈ 80 %/h, a fast charger) from the seeded month's charge, plugged in
     * 10 min before the scenario (the time "Full in" needs, [ChargeEta.RATE_AFTER_S]): after the newest
     * ride ended ([LAST_RIDE_AGO_MS]), so the session never overlaps it.
     */
    const val CHARGE_PERCENT_PER_S = 1.0 / 45.0
    const val CHARGE_PLUGGED_S = 10 * 60.0
    /** Charging holds at 100 % this long, then the scenario starts over. */
    const val CHARGE_FULL_HOLD_S = 120.0
    /** The parked scenario's state is the end of the seeded history; the call scenario stands still. */
    const val CONNECTED_FOR_MS = 6 * 60_000L

    /** The navigation route's maneuvers (the last leg's length is what is left of [ROUTE_M]). */
    data class Leg(val turn: Int, val instruction: String, val road: String, val lengthM: Double)

    /** Destination stretch: the "on the right" cue shows for this last part. */
    private const val DESTINATION_M = 600.0

    /**
     * Route length: it ends exactly where the loop's first stop after ~5.6 km begins, so the rider
     * arrives standing still.
     */
    val ROUTE_M: Double by lazy {
        val startM = distanceAt(PREROLL_S)
        var loop = floor(PREROLL_S / CYCLE_S)
        var best = Double.NaN
        while (best.isNaN()) {
            for (stop in STOPS) {
                val t = loop * CYCLE_S + stop
                if (t <= PREROLL_S) continue
                val d = distanceAt(t) - startM
                if (d >= 5_600.0) { best = d; break }
            }
            loop++
        }
        best
    }

    val ROUTE: List<Leg> by lazy {
        val continueM = ROUTE_M - 400.0 - 1_100.0 - DESTINATION_M
        listOf(
            Leg(NavTurn.LEFT, "Turn left onto Riverside Rd", "Riverside Rd", 400.0),
            // Back-to-back: the roundabout's cue announces the left turn 300 m after it ("Then left" on the TFT).
            Leg(NavTurn.ROUNDABOUT_RIGHT, "At the roundabout, take the 2nd exit onto Market St, then turn left", "Market St", 800.0),
            Leg(NavTurn.LEFT, "Turn left onto Harbour St", "Harbour St", 300.0),
            Leg(NavTurn.STRAIGHT, "Continue on Harbour St", "Harbour St", continueM),
            Leg(NavTurn.DESTINATION, "Lakeside Market is on the right", "Lakeside Market", DESTINATION_M)
        )
    }

    /** Seconds the navigation scenario runs before it starts over (arrival + a short stand). */
    val NAV_PERIOD_S: Double by lazy { timeAt(distanceAt(PREROLL_S) + ROUTE_M) - PREROLL_S + 6.0 }

    /**
     * Charge at the route's start such that the range check is "Tight" (≈ 3.4 km spare) the whole way
     * with the demo's [kmPerPercent]. On purpose (FEATURES: the verdict reads Tight): the navigating
     * scenario runs on a low battery from home and does not carry on from the seeded month's charge.
     */
    fun navStartSoc(kmPerPercent: Double): Double = (ROUTE_M / 1000.0 + 3.9) / RangeModel.clampKmPerPercent(kmPerPercent)

    /**
     * The scenario's live values [nowMs] for a scenario started at [startedAt]. [history] supplies the
     * parked charge and the learned efficiency; [arrivalClock] turns an epoch time into local
     * (hour, minute) for the Maps-like arrival time.
     */
    fun live(
        scenario: DemoScenario, startedAt: Long, nowMs: Long, history: DemoHistory,
        arrivalClock: (Long) -> Pair<Int, Int> = ::localClock
    ): DemoLive {
        val k = RangeModel.clampKmPerPercent(history.efficiency.kmPerPercent)
        val connectedAt = startedAt - CONNECTED_FOR_MS
        return when (scenario) {
            DemoScenario.PARKED -> standing(scenario, linked = false, connectedAt = 0L, soc = history.socNow, temp = 26.8)
            // Standing at a light with the ignition on: the BMS reports 4 ("Drive") while the scooter is on.
            DemoScenario.CALL -> standing(scenario, linked = true, connectedAt = connectedAt, soc = history.socNow, temp = 27.4)
                .copy(ignitionOn = true, handleLocked = false, status = 4, packStatus = 4)
            DemoScenario.RIDING, DemoScenario.MUSIC -> {
                // Whole milliseconds: the loop's start (the live ride's start) stays the same from tick to tick.
                val loopMs = loopMs(startedAt, nowMs, RIDE_PERIOD_S)
                // Away from where the demo scooter is parked (the newest seeded ride's end).
                val from = history.lastRide?.endPlace ?: LAKESIDE.label
                moving(scenario, nowMs - loopMs, loopMs / 1000.0 + PREROLL_S, history.socNow, k, connectedAt, from)
            }
            DemoScenario.NAVIGATING -> {
                val loopMs = loopMs(startedAt, nowMs, NAV_PERIOD_S)
                val loopStart = nowMs - loopMs
                val tau = loopMs / 1000.0 + PREROLL_S
                val preRollKm = distanceAt(PREROLL_S) / 1000
                val base = moving(scenario, loopStart, tau, navStartSoc(k) + preRollKm / k, k, connectedAt, HOME.label)
                val travelled = (distanceAt(tau) - distanceAt(PREROLL_S)).coerceIn(0.0, ROUTE_M)
                val arrival = arrivalClock(loopStart + ((timeAt(distanceAt(PREROLL_S) + ROUTE_M) - PREROLL_S) * 1000).toLong())
                base.copy(nav = nav(travelled, arrival))
            }
            DemoScenario.CHARGING -> charging(history, startedAt, nowMs, connectedAt)
        }
    }

    private fun standing(scenario: DemoScenario, linked: Boolean, connectedAt: Long, soc: Double, temp: Double) = DemoLive(
        scenario, linked, connectedAt,
        soc, soc + PACK_OFFSET, soc - PACK_OFFSET,
        soc - 1.6, soc + PACK_OFFSET - 1.4, soc - PACK_OFFSET - 1.9,
        (HEALTH_FRONT + HEALTH_REAR) / 2, HEALTH_FRONT, HEALTH_REAR,
        temp + 0.3, temp, temp + 0.6,
        status = 0, packStatus = 2, ignitionOn = false, handleLocked = true,
        ride = null, charge = null, nav = null
    )

    private fun moving(
        scenario: DemoScenario, loopStart: Long, tau: Double, socStart: Double, k: Double, connectedAt: Long, startPlace: String
    ): DemoLive {
        val distance = distanceAt(tau)
        val moving = movingAt(tau)
        val soc = (socStart - distance / 1000.0 / k).coerceIn(0.0, 100.0)
        // Packs warm by ~1.8 °C over the first quarter hour of riding.
        val warm = 1.8 * (1 - exp(-tau / 900.0))
        val tFront = 27.6 + warm
        val ride = DemoRideLive(
            startedAt = loopStart - (PREROLL_S * 1000).toLong(),
            distanceM = distance, durationS = tau.toLong(), speedKmh = speedAt(tau),
            peakKmh = peakAt(tau), avgKmh = if (moving > 0) distance / moving * 3.6 else 0.0,
            socStart = socStart, startPlace = startPlace
        )
        return DemoLive(
            scenario, true, connectedAt,
            soc, (soc + PACK_OFFSET).coerceAtMost(100.0), (soc - PACK_OFFSET).coerceAtLeast(0.0),
            (soc - 1.6).coerceAtLeast(0.0), (soc + PACK_OFFSET - 1.4).coerceAtLeast(0.0), (soc - PACK_OFFSET - 1.9).coerceAtLeast(0.0),
            (HEALTH_FRONT + HEALTH_REAR) / 2, HEALTH_FRONT, HEALTH_REAR,
            tFront + 0.3, tFront, tFront + 0.6,
            status = 4, packStatus = 4, ignitionOn = true, handleLocked = false,
            ride = ride, charge = null, nav = null
        )
    }

    /** Milliseconds into the current [periodS] loop of a scenario started at [startedAt] (whole ms: no tick-to-tick jitter). */
    private fun loopMs(startedAt: Long, nowMs: Long, periodS: Double): Long {
        val periodMs = (periodS * 1000).toLong().coerceAtLeast(1_000L)
        return (nowMs - startedAt).coerceAtLeast(0L) % periodMs
    }

    /** Seconds from plugging in until full, charging from [from] percent. */
    fun chargeToFullS(from: Double): Double = (100.0 - from).coerceAtLeast(0.0) / CHARGE_PERCENT_PER_S

    /** Seconds the charging scenario runs before it starts over (full, then [CHARGE_FULL_HOLD_S] at 100 %). */
    fun chargePeriodS(from: Double): Double = (chargeToFullS(from) - CHARGE_PLUGGED_S).coerceAtLeast(0.0) + CHARGE_FULL_HOLD_S

    /**
     * Charging from the seeded month's charge ([DemoHistory.socNow]: where the newest ride left it).
     * Whole milliseconds throughout, so the session's start is the same on every tick (the shell
     * rewrites the stored session only when it really starts over).
     */
    private fun charging(history: DemoHistory, startedAt: Long, nowMs: Long, connectedAt: Long): DemoLive {
        val from = round2(history.socNow)
        val sessionStart = nowMs - loopMs(startedAt, nowMs, chargePeriodS(from)) - (CHARGE_PLUGGED_S * 1000).toLong()
        val pluggedMs = nowMs - sessionStart
        val plugged = pluggedMs / 1000.0
        val soc = min(100.0, from + plugged * CHARGE_PERCENT_PER_S)
        // The session's "end" is the latest rise (as the real detector reports it); it stops at 100 %.
        val risingMs = min(pluggedMs, (chargeToFullS(from) * 1000).toLong())
        val session = ChargeSession(sessionStart, sessionStart + risingMs, from, round2(soc))
        val warm = 3.2 * (1 - exp(-plugged / 1200.0))
        val tFront = 28.4 + warm
        val base = standing(DemoScenario.CHARGING, linked = true, connectedAt = connectedAt, soc = soc, temp = tFront)
        return base.copy(
            socFront = (soc + PACK_OFFSET).coerceAtMost(100.0), socRear = (soc - PACK_OFFSET).coerceAtLeast(0.0),
            charge = session
        )
    }

    /** The cue and counted-down distances [travelledM] metres into the route. */
    fun nav(travelledM: Double, arrival: Pair<Int, Int>): DemoNav {
        var along = 0.0
        var leg = ROUTE.last()
        var legEnd = ROUTE_M
        for (l in ROUTE) {
            if (travelledM < along + l.lengthM) { leg = l; legEnd = along + l.lengthM; break }
            along += l.lengthM
        }
        val maneuver = NavCountdown.round(max(0.0, legEnd - travelledM))
        val remaining = roundTenths(max(0.0, ROUTE_M - travelledM))
        val cue = MapsCue(
            turn = leg.turn, distanceMeters = maneuver, instruction = leg.instruction, road = leg.road,
            arrivalHour24 = arrival.first, arrivalMinute = arrival.second, remainingMeters = remaining, rerouting = false,
            thenTurn = MapsNavigationParser.thenTurn(leg.instruction)
        )
        return DemoNav(cue, maneuver, remaining)
    }

    /** Remaining route distance as Maps reports it: whole 100 m. */
    private fun roundTenths(metres: Double): Int = ((metres / 100.0).roundToInt() * 100).coerceAtLeast(0)

    /** Local (hour, minute) of [time], rounded to the nearest minute like Maps' arrival time. */
    fun localClock(time: Long): Pair<Int, Int> = Calendar.getInstance().run {
        timeInMillis = time + 30_000L
        get(Calendar.HOUR_OF_DAY) to get(Calendar.MINUTE)
    }

    // ================================================================== live: music and calls

    val TRACKS = listOf(
        DemoTrack("Night Drive", "Kavi", 214_000L),
        DemoTrack("Low Tide", "Mira Sol", 187_000L),
        DemoTrack("Paper Lanterns", "The Quiet Hours", 241_000L)
    )
    /** The music scenario starts this far into the first track. */
    const val MUSIC_START_MS = 72_000L
    const val CALLER = "Alex"
    /** The demo call rings this long after the scenario starts, and again this long after it ends. */
    const val CALL_RING_AFTER_MS = 2_000L
    const val CALL_AGAIN_AFTER_MS = 30_000L
    /** Unanswered, it stops ringing (missed) after this long. */
    const val CALL_RING_MS = 45_000L
}
