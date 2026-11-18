package dev.codesurgen.ruta

import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Ride and charging analytics. Pure Kotlin (no android.* imports): host-tested by tests/RideAnalyticsTest.kt.
 * Units everywhere: time = UTC epoch ms, distance = m, speed = km/h, SOC = percent points.
 */

/** A raw location fix. [speedKmh] is the receiver's Doppler speed, NaN when the fix has none. */
data class GeoFix(val time: Long, val lat: Double, val lng: Double, val accuracyM: Double, val speedKmh: Double = Double.NaN)

/**
 * A fix accepted by [FixFilter]. A jitter-suppressed fix keeps the previous position, adds no distance
 * and reports 0 km/h. [moving] marks steps that count as riding (distance added at ≥ 2 km/h).
 */
data class TrackStep(
    val time: Long, val lat: Double, val lng: Double, val accuracyM: Double,
    val stepM: Double, val speedKmh: Double, val moving: Boolean
)

/** A stored ride point: [speedKmh] is the filtered (unsmoothed) speed, [distanceM] is cumulative from the ride start. */
data class RidePoint(val time: Long, val lat: Double, val lng: Double, val speedKmh: Double, val distanceM: Double, val accuracyM: Double)

/**
 * A finished ride. [start]/[end] span first to last movement; [avgKmh] is the average moving speed;
 * [socStart]/[socEnd] are pack-0 (scooter total) charge, null when no BMS reading was available.
 */
data class Ride(
    val id: Long, val start: Long, val end: Long,
    val startLat: Double, val startLng: Double, val endLat: Double, val endLng: Double,
    val startPlace: String?, val endPlace: String?,
    val distanceM: Double, val durationS: Long, val movingS: Long,
    val peakKmh: Double, val avgKmh: Double,
    val socStart: Double?, val socEnd: Double?
) {
    val distanceKm: Double get() = distanceM / 1000
    /** Charge points used; negative when the charge rose. Null without both readings. */
    val batteryUsed: Double? get() = if (socStart != null && socEnd != null) socStart - socEnd else null
    val kmPerPercent: Double? get() = RideMath.kmPerPercent(distanceM, socStart, socEnd)
    /** Place name, or "12.9716, 77.5946" when it could not be looked up (offline, no geocoder). */
    val startLabel: String get() = startPlace ?: RideMath.latLngText(startLat, startLng)
    val endLabel: String get() = endPlace ?: RideMath.latLngText(endLat, endLng)
    /** Area only ("Lakeside"), for compact titles like "Lakeside → Greenfield"; null when not looked up. */
    val startArea: String? get() = RideMath.placeArea(startPlace)
    val endArea: String? get() = RideMath.placeArea(endPlace)
}

/** A charging session on pack 0; while one is in progress, [end]/[socEnd] are the latest rise. */
data class ChargeSession(val start: Long, val end: Long, val socStart: Double, val socEnd: Double, val id: Long = 0) {
    val durationS: Long get() = (end - start) / 1000
    val gained: Double get() = socEnd - socStart
    /** Charge points per hour. */
    val percentPerHour: Double get() = if (end > start) gained * 3_600_000.0 / (end - start) else 0.0
}

/** One chart bucket: [time] is the bucket midpoint; buckets without data are omitted (draw them as gaps). */
data class BucketPoint(val time: Long, val avg: Double, val min: Double, val max: Double, val count: Int = 1)

data class ValueAt(val time: Long, val value: Double)

/** The fields of one reverse-geocoder result that [RideMath.placeLabel] uses (android.location.Address). */
data class PlaceParts(
    val thoroughfare: String?, val subLocality: String?, val locality: String?,
    val featureName: String? = null, val subAdminArea: String? = null, val adminArea: String? = null
)

/** Totals over a set of rides; [avgKmh] is total distance over total moving time. */
data class RideStats(
    val rides: Int, val distanceM: Double, val durationS: Long, val movingS: Long,
    val peakKmh: Double, val avgKmh: Double, val batteryUsed: Double, val kmPerPercent: Double?
)

enum class RideEndReason { IGNITION_OFF, LINK_LOST, IDLE, STOPPED }

sealed class RideEvent {
    data class Started(val time: Long, val byIgnition: Boolean) : RideEvent()
    data class Ended(val time: Long, val reason: RideEndReason) : RideEvent()
}

object RideMath {
    const val EARTH_RADIUS_M = 6_371_008.8
    const val MAX_ACCURACY_M = 25.0
    const val MAX_JUMP_KMH = 120.0
    /** Below this reported speed a fix inside the accuracy radius is GPS jitter, not movement. */
    const val JITTER_KMH = 3.0
    const val MIN_STEP_M = 5.0
    const val MOVING_KMH = 2.0
    const val START_KMH = 8.0
    const val START_HOLD_MS = 10_000L
    const val IGNITION_OFF_MS = 60_000L
    const val LINK_LOST_MS = 120_000L
    const val IDLE_MS = 300_000L
    const val MIN_RIDE_M = 200.0
    const val MIN_RIDE_S = 60L
    /** Below this many charge points used, km per % is noise. */
    const val MIN_USED_PERCENT = 1.0

    fun haversineM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val p1 = lat1 * PI / 180
        val p2 = lat2 * PI / 180
        val dp = p2 - p1
        val dl = (lng2 - lng1) * PI / 180
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    fun median3(a: Double, b: Double, c: Double): Double = max(min(a, b), min(max(a, b), c))

    /** Distance over moving time; 0 when there was none. */
    fun averageKmh(distanceM: Double, movingMs: Long): Double = if (movingMs > 0) distanceM * 3600.0 / movingMs else 0.0

    fun kmPerPercent(distanceM: Double, socStart: Double?, socEnd: Double?): Double? {
        if (socStart == null || socEnd == null) return null
        val used = socStart - socEnd
        return if (used >= MIN_USED_PERCENT) distanceM / 1000 / used else null
    }

    fun stats(rides: List<Ride>): RideStats {
        val distance = rides.sumOf { it.distanceM }
        val moving = rides.sumOf { it.movingS }
        val metered = rides.filter { it.batteryUsed != null }
        val used = metered.sumOf { it.batteryUsed!! }
        val meteredKm = metered.sumOf { it.distanceM } / 1000
        return RideStats(
            rides.size, distance, rides.sumOf { it.durationS }, moving, rides.maxOfOrNull { it.peakKmh } ?: 0.0,
            averageKmh(distance, moving * 1000), used, if (used >= MIN_USED_PERCENT) meteredKm / used else null
        )
    }

    /** Bucket width for [buckets] equal buckets over [start, end); shared with the SQL history query. */
    fun bucketWidth(start: Long, end: Long, buckets: Int): Long =
        max(1L, (end - start + buckets - 1) / buckets.coerceAtLeast(1))

    /** Averages [values] in [start, end) into at most [buckets] buckets (min/max kept for range bands). */
    fun downsample(values: List<ValueAt>, start: Long, end: Long, buckets: Int): List<BucketPoint> {
        if (buckets <= 0 || end <= start) return emptyList()
        val width = bucketWidth(start, end, buckets)
        return values.asSequence().filter { it.time in start until end && it.value.isFinite() }
            .groupBy { (it.time - start) / width }.toSortedMap()
            .map { (bucket, items) ->
                BucketPoint(start + bucket * width + width / 2, items.sumOf { it.value } / items.size,
                    items.minOf { it.value }, items.maxOf { it.value }, items.size)
            }
    }

    /** Speed over the ride for a chart. */
    fun speedSeries(points: List<RidePoint>, buckets: Int): List<BucketPoint> {
        if (points.isEmpty()) return emptyList()
        return downsample(points.map { ValueAt(it.time, it.speedKmh) }, points.first().time, points.last().time + 1, buckets)
    }

    /**
     * Charging sessions in a stored pack-0 SOC series. A gap longer than [gapMs] counts as a lost link
     * (readings are only recorded while connected).
     */
    fun chargeSessions(samples: List<ValueAt>, gapMs: Long = 120_000): List<ChargeSession> {
        val detector = ChargeDetector()
        val found = LinkedHashMap<Long, ChargeSession>() // A resumed charge re-emits its session with the same start.
        var previous: Long? = null
        for (sample in samples.sortedBy { it.time }) {
            if (previous != null && sample.time - previous > gapMs)
                detector.update(previous + gapMs, null, linkReady = false, riding = false)?.let { found[it.start] = it }
            detector.update(sample.time, sample.value, linkReady = true, riding = false)?.let { found[it.start] = it }
            previous = sample.time
        }
        detector.stop()?.let { found[it.start] = it }
        return found.values.toList()
    }

    private val PLUS_CODE = Regex("^[23456789CFGHJMPQRVWX]{2,8}\\+[23456789CFGHJMPQRVWX]*$", RegexOption.IGNORE_CASE)
    private val PLUS_CODE_PREFIX = Regex("^[23456789CFGHJMPQRVWX]{2,8}\\+[23456789CFGHJMPQRVWX]*[ ,]+", RegexOption.IGNORE_CASE)
    private val UNNAMED = Regex("^unnamed( road)?$", RegexOption.IGNORE_CASE)
    /** "221/B", "12-2-345", "12", "12A", "Plot No 5", "H.No 1-2", "No. 5" — never "80 Feet Road" or "Road No. 36". */
    private val HOUSE_NUMBER = Regex(
        "^(?:(?:plot|h\\.?\\s*no|house\\s*no|door\\s*no|flat|no)\\b.*|\\d\\w*(?:[/-]\\w+)+.*|\\d+[a-z]?)$", RegexOption.IGNORE_CASE
    )

    /** A geocoder field worth showing: no plus codes, house numbers or "Unnamed Road". */
    private fun usable(value: String?): String? {
        val text = value?.trim()?.replace(PLUS_CODE_PREFIX, "")?.trim() ?: return null
        return text.takeIf {
            it.any(Char::isLetter) && !PLUS_CODE.matches(it) && !UNNAMED.matches(it) && !HOUSE_NUMBER.matches(it)
        }
    }

    /**
     * Place label from geocoder results (nearest first): "Riverside Garden Rd, Lakeside"
     * (thoroughfare, subLocality); without a street the subLocality ("Lakeside"), then the locality
     * ("Northgate"), then a non-numeric feature name, district or state. Each field is taken from the
     * first result that has a usable value. The last comma part is always the area ([placeArea]).
     * Never house numbers or plus codes. Null when nothing usable.
     */
    fun placeLabel(results: List<PlaceParts>): String? {
        fun first(field: (PlaceParts) -> String?) = results.firstNotNullOfOrNull { usable(field(it)) }
        val street = first { it.thoroughfare }
        val area = first { it.subLocality }
        val city = first { it.locality }
        val fallback = first { it.featureName } ?: first { it.subAdminArea } ?: first { it.adminArea }
        val label = when {
            street != null -> listOfNotNull(street, area ?: city).distinctBy { it.lowercase() }.joinToString(", ")
            else -> area ?: city ?: fallback
        }
        return label?.ifEmpty { null }
    }

    /** One geocoder result; see [placeLabel]. */
    fun placeLabel(
        subLocality: String?, thoroughfare: String?, locality: String?,
        featureName: String? = null, subAdminArea: String? = null, adminArea: String? = null
    ): String? = placeLabel(listOf(PlaceParts(thoroughfare, subLocality, locality, featureName, subAdminArea, adminArea)))

    /** The area of a [placeLabel] ("Lakeside" from "Riverside Garden Rd, Lakeside"), for compact titles. */
    fun placeArea(label: String?): String? = label?.substringAfterLast(", ")?.trim()?.ifEmpty { null }

    private val STREET_WORDS = mapOf(
        "road" to "Rd", "street" to "St", "lane" to "Ln", "avenue" to "Ave", "highway" to "Hwy",
        "expressway" to "Expy", "boulevard" to "Blvd", "junction" to "Jn", "circle" to "Cir"
    )
    private val STREET_WORD = Regex("\\b(${STREET_WORDS.keys.joinToString("|")})\\b", RegexOption.IGNORE_CASE)

    /** Place text for compact titles: street words abbreviated ("Riverside Garden Road" → "Riverside Garden Rd"), spaces tidied. */
    fun shortPlace(text: String): String =
        STREET_WORD.replace(text.trim().replace(Regex("\\s+"), " ")) { STREET_WORDS.getValue(it.value.lowercase(Locale.ROOT)) }

    /**
     * Short ride title from the start and end [placeLabel]s, preferring areas: "Lakeside → Greenfield";
     * within one area the street (or the area itself when a label has no street): "Riverside Garden
     * Rd → Lakeside"; the same street both ends: "Around Hill Rd". Street words are abbreviated
     * ([shortPlace]). Null without both labels (the caller falls back to a time-of-day title).
     */
    fun rideTitle(startPlace: String?, endPlace: String?): String? {
        val a = startPlace?.trim()?.ifEmpty { null } ?: return null
        val b = endPlace?.trim()?.ifEmpty { null } ?: return null
        val areaA = placeArea(a) ?: a
        val areaB = placeArea(b) ?: b
        if (!areaA.equals(areaB, ignoreCase = true)) return "${shortPlace(areaA)} → ${shortPlace(areaB)}"
        val firstA = a.substringBefore(", ").trim()
        val firstB = b.substringBefore(", ").trim()
        return if (!firstA.equals(firstB, ignoreCase = true)) "${shortPlace(firstA)} → ${shortPlace(firstB)}" else "Around ${shortPlace(firstA)}"
    }

    /**
     * Chart points prepared for drawing: consecutive points more than [gapMs] apart start a new run
     * (missing data stays a gap), and points falling in the same [slotMs]-wide slot from [origin] are
     * merged (count-weighted average and time, min of mins, max of maxes), so no two drawn points
     * crowd into one spot. [slotMs] ≤ 0 merges nothing. Points must be in time order.
     */
    fun plotRuns(points: List<BucketPoint>, origin: Long, slotMs: Long, gapMs: Long): List<List<BucketPoint>> {
        val runs = ArrayList<List<BucketPoint>>()
        var run = ArrayList<BucketPoint>()
        var slot = Long.MIN_VALUE
        var weight = 0.0; var timeSum = 0.0; var avgSum = 0.0
        var low = Double.POSITIVE_INFINITY; var high = Double.NEGATIVE_INFINITY
        var count = 0
        fun flush() {
            if (count == 0) return
            run.add(BucketPoint(Math.round(timeSum / weight), avgSum / weight, low, high, count))
            weight = 0.0; timeSum = 0.0; avgSum = 0.0; count = 0
            low = Double.POSITIVE_INFINITY; high = Double.NEGATIVE_INFINITY
        }
        var previous: Long? = null
        for (p in points) {
            if (!p.avg.isFinite()) continue
            val gap = previous != null && p.time - previous > gapMs
            val nextSlot = if (slotMs > 0) Math.floorDiv(p.time - origin, slotMs) else Long.MIN_VALUE
            if (gap || slotMs <= 0 || nextSlot != slot) flush()
            if (gap && run.isNotEmpty()) { runs.add(run); run = ArrayList() }
            slot = nextSlot
            val w = p.count.coerceAtLeast(1).toDouble()
            weight += w; timeSum += p.time * w; avgSum += p.avg * w
            low = min(low, p.min); high = max(high, p.max); count += p.count.coerceAtLeast(1)
            previous = p.time
        }
        flush()
        if (run.isNotEmpty()) runs.add(run)
        return runs
    }

    /**
     * Insights' default period (I1): true ("24 h") when every timestamp in [times] (oldest readings,
     * rides, charges of the last week) lies within the day before [now]; false with no data or older data.
     */
    fun fitsLastDay(times: List<Long?>, now: Long): Boolean {
        val known = times.filterNotNull()
        return known.isNotEmpty() && known.all { it >= now - DAY_MS }
    }

    private const val DAY_MS = 86_400_000L

    fun latLngText(lat: Double, lng: Double): String = String.format(Locale.US, "%.4f, %.4f", lat, lng)

    /** "45 s", "12 min", "1 h 05 min". */
    fun formatDuration(seconds: Long): String = when {
        seconds < 60 -> "${seconds.coerceAtLeast(0)} s"
        seconds < 3600 -> "${seconds / 60} min"
        else -> String.format(Locale.US, "%d h %02d min", seconds / 3600, seconds % 3600 / 60)
    }

    /** At most [max] points, evenly spaced, always keeping the first and last (for drawing long tracks). */
    fun thin(points: List<RidePoint>, max: Int): List<RidePoint> {
        if (points.size <= max || max < 2) return points
        val step = (points.size - 1).toDouble() / (max - 1)
        return List(max) { points[Math.round(it * step).toInt()] }
    }

    /**
     * Projects a track into a [width] × [height] box (equirectangular, aspect preserved, centred, north up).
     * Returns x,y pairs, ready for Canvas.drawLines/Path.
     */
    fun project(points: List<RidePoint>, width: Float, height: Float, padding: Float = 0f): FloatArray {
        if (points.isEmpty()) return FloatArray(0)
        val minLat = points.minOf { it.lat }; val maxLat = points.maxOf { it.lat }
        val minLng = points.minOf { it.lng }; val maxLng = points.maxOf { it.lng }
        val kx = cos((minLat + maxLat) / 2 * PI / 180)
        val spanX = (maxLng - minLng) * kx
        val spanY = maxLat - minLat
        val w = (width - 2 * padding).coerceAtLeast(0f)
        val h = (height - 2 * padding).coerceAtLeast(0f)
        val scale = min(if (spanX > 0) w / spanX else Double.MAX_VALUE, if (spanY > 0) h / spanY else Double.MAX_VALUE)
            .let { if (it == Double.MAX_VALUE) 0.0 else it }
        val left = padding + (w - spanX * scale) / 2
        val top = padding + (h - spanY * scale) / 2
        val out = FloatArray(points.size * 2)
        points.forEachIndexed { i, p ->
            out[2 * i] = (left + (p.lng - minLng) * kx * scale).toFloat()
            out[2 * i + 1] = (top + (maxLat - p.lat) * scale).toFloat()
        }
        return out
    }

    /** Google Maps directions from the first to the last point of the track (origin + destination only, no stops); null for < 2 points. */
    fun routeUrl(points: List<RidePoint>): String? {
        if (points.size < 2) return null
        val first = points.first(); val last = points.last()
        return directionsUrl(first.lat, first.lng, last.lat, last.lng)
    }

    /** Google Maps directions URL from A to B (no waypoints). */
    fun directionsUrl(fromLat: Double, fromLng: Double, toLat: Double, toLng: Double): String {
        fun ll(lat: Double, lng: Double) = String.format(Locale.US, "%.6f,%.6f", lat, lng)
        return "https://www.google.com/maps/dir/?api=1&origin=${ll(fromLat, fromLng)}&destination=${ll(toLat, toLng)}&travelmode=driving"
    }
}

/**
 * Drops unusable fixes: worse than 25 m accuracy, implying > 120 km/h from the previous accepted fix,
 * or out of order. Slow fixes inside the accuracy radius (≥ 5 m) of the last position are jitter:
 * accepted with no distance and 0 km/h, so a parked scooter accumulates nothing.
 */
class FixFilter(private val maxAccuracyM: Double = RideMath.MAX_ACCURACY_M, private val maxKmh: Double = RideMath.MAX_JUMP_KMH) {
    private var anchor: GeoFix? = null // last position that added distance
    private var lastTime = 0L // last accepted fix, including jitter
    private var jumps = 0

    fun reset() { anchor = null; lastTime = 0; jumps = 0 }

    fun accept(fix: GeoFix): TrackStep? {
        if (!fix.lat.isFinite() || !fix.lng.isFinite() || abs(fix.lat) > 90 || abs(fix.lng) > 180 ||
            (fix.lat == 0.0 && fix.lng == 0.0) || !(fix.accuracyM <= maxAccuracyM)) return null
        val reported = fix.speedKmh.takeIf { it.isFinite() && it >= 0 && it <= maxKmh }
        val from = anchor ?: return restart(fix, reported)
        val dt = fix.time - lastTime
        if (dt <= 0) return null
        val d = RideMath.haversineM(from.lat, from.lng, fix.lat, fix.lng)
        if (d * 3600.0 / dt > maxKmh) {
            // A run of impossible jumps means the anchor itself was the outlier: restart from here.
            return if (++jumps >= MAX_JUMPS) restart(fix, reported) else null
        }
        jumps = 0
        lastTime = fix.time
        if ((reported == null || reported < RideMath.JITTER_KMH) && d < max(RideMath.MIN_STEP_M, fix.accuracyM))
            return TrackStep(fix.time, from.lat, from.lng, fix.accuracyM, 0.0, 0.0, moving = false)
        anchor = fix
        val speed = reported ?: d * 3600.0 / (fix.time - from.time).coerceAtLeast(1)
        return TrackStep(fix.time, fix.lat, fix.lng, fix.accuracyM, d, speed, moving = speed >= RideMath.MOVING_KMH)
    }

    private fun restart(fix: GeoFix, reported: Double?): TrackStep {
        anchor = fix; lastTime = fix.time; jumps = 0
        return TrackStep(fix.time, fix.lat, fix.lng, fix.accuracyM, 0.0, reported ?: 0.0, moving = false)
    }

    private companion object { const val MAX_JUMPS = 5 }
}

/** Median of the last three speeds; until three exist, the lowest (a lone spike never counts). */
class SpeedSmoother {
    private val window = ArrayDeque<Double>(3)
    val full: Boolean get() = window.size == 3
    fun add(kmh: Double): Double {
        if (window.size == 3) window.removeFirst()
        window.addLast(kmh)
        return if (full) RideMath.median3(window[0], window[1], window[2]) else window.min()
    }
    fun reset() = window.clear()
}

/** Accumulates one ride from filtered steps; [replay] rebuilds an interrupted ride from its stored points. */
class RideAccumulator {
    var distanceM = 0.0; private set
    var movingMs = 0L; private set
    var peakKmh = 0.0; private set
    /** Latest smoothed speed. */
    var speedKmh = 0.0; private set
    var last: RidePoint? = null; private set
    /** Where movement began (the point before the first moving step) and the last moving point. */
    var startPoint: RidePoint? = null; private set
    var endPoint: RidePoint? = null; private set
    private val smoother = SpeedSmoother()

    /** Adds a step and returns its point (distance is cumulative; the first step adds none). */
    fun add(step: TrackStep): RidePoint {
        val previous = last
        val stepM = if (previous == null) 0.0 else step.stepM
        distanceM += stepM
        val point = RidePoint(step.time, step.lat, step.lng, step.speedKmh, distanceM, step.accuracyM)
        if (previous != null && step.moving && stepM > 0) {
            val dt = step.time - previous.time
            // After a gap (no fixes while stopped, a tunnel) credit only the time the speed explains.
            movingMs += if (dt <= GAP_MS) dt else min(dt, (stepM * 3600.0 / step.speedKmh).toLong())
            if (startPoint == null) startPoint = previous
            endPoint = point
        }
        speedKmh = smoother.add(step.speedKmh)
        if (smoother.full) peakKmh = max(peakKmh, speedKmh)
        last = point
        return point
    }

    val averageKmh: Double get() = RideMath.averageKmh(distanceM, movingMs)

    /** The ride between first and last movement, or null when shorter than 200 m or 60 s (discard it). */
    fun finish(id: Long, socStart: Double?, socEnd: Double?): Ride? {
        val from = startPoint ?: return null
        val to = endPoint ?: return null
        val durationS = (to.time - from.time) / 1000
        if (distanceM < RideMath.MIN_RIDE_M || durationS < RideMath.MIN_RIDE_S) return null
        return Ride(id, from.time, to.time, from.lat, from.lng, to.lat, to.lng, null, null,
            distanceM, durationS, movingMs / 1000, peakKmh, averageKmh, socStart, socEnd)
    }

    companion object {
        const val GAP_MS = 5_000L

        fun replay(points: List<RidePoint>): RideAccumulator = RideAccumulator().apply {
            var previous: RidePoint? = null
            for (p in points.sortedBy { it.time }) {
                val stepM = previous?.let { (p.distanceM - it.distanceM).coerceAtLeast(0.0) } ?: 0.0
                add(TrackStep(p.time, p.lat, p.lng, p.accuracyM, stepM, p.speedKmh, stepM > 0 && p.speedKmh >= RideMath.MOVING_KMH))
                previous = p
            }
        }
    }
}

/**
 * Ride start/stop rules. A ride starts while the link is READY when ignition turns on, or GPS speed
 * stays above 8 km/h for 10 s. It ends after ignition off for 60 s (unless still moving above 8 km/h),
 * link lost for 120 s, or under 2 km/h (or no fix) for 5 min. After an idle end with ignition still on,
 * ignition alone does not restart a ride until it has been off; movement still does.
 * Feed [update] about once a second and on every fix.
 */
class RideSegmenter {
    var riding = false; private set
    var startedAt = 0L; private set
    private var ignitionArmed = true
    private var fastSince: Long? = null
    private var offSince: Long? = null
    private var lostSince: Long? = null
    private var idleSince: Long? = null
    private var lastMovingAt = 0L

    /** [speedKmh] is the latest fresh filtered GPS speed, null without a recent fix. */
    fun update(now: Long, linkReady: Boolean, ignitionOn: Boolean?, speedKmh: Double?): RideEvent? {
        if (ignitionOn == false) ignitionArmed = true
        val speed = speedKmh ?: 0.0
        fastSince = if (speed > RideMath.START_KMH) fastSince ?: now else null
        if (speed >= RideMath.MOVING_KMH) lastMovingAt = now
        if (!riding) {
            if (!linkReady) return null
            val byIgnition = ignitionOn == true && ignitionArmed
            val fast = fastSince
            val bySpeed = fast != null && now - fast >= RideMath.START_HOLD_MS
            if (!byIgnition && !bySpeed) return null
            riding = true
            startedAt = if (bySpeed) fast else now
            offSince = null; lostSince = null; idleSince = null
            lastMovingAt = if (speed >= RideMath.MOVING_KMH) now else startedAt
            return RideEvent.Started(startedAt, byIgnition)
        }
        offSince = if (ignitionOn == false && speed <= RideMath.START_KMH) offSince ?: now else null
        lostSince = if (!linkReady) lostSince ?: now else null
        idleSince = if (speed < RideMath.MOVING_KMH) idleSince ?: now else null
        val off = offSince; val lost = lostSince; val idle = idleSince
        val (reason, since) = when {
            off != null && now - off >= RideMath.IGNITION_OFF_MS -> RideEndReason.IGNITION_OFF to off
            lost != null && now - lost >= RideMath.LINK_LOST_MS -> RideEndReason.LINK_LOST to lost
            idle != null && now - idle >= RideMath.IDLE_MS -> RideEndReason.IDLE to idle
            else -> return null
        }
        if (reason == RideEndReason.IDLE && ignitionOn == true) ignitionArmed = false
        return end(max(since, lastMovingAt).coerceIn(startedAt, now), reason)
    }

    /** Ends an active ride now (tracker shutting down). */
    fun stop(now: Long): RideEvent.Ended? = if (riding) end(now, RideEndReason.STOPPED) else null

    private fun end(time: Long, reason: RideEndReason): RideEvent.Ended {
        riding = false
        fastSince = null; offSince = null; lostSince = null; idleSince = null
        return RideEvent.Ended(time, reason)
    }
}

/**
 * Charging from the pack-0 SOC series: a session is confirmed once SOC has risen ≥ 1 point over
 * ≥ 5 min (from the last low, within 30 min) while connected and not riding. It ends when SOC stops
 * rising for 10 min, the link drops, or a ride starts. A charge that resumes within 30 min at or
 * above the last session's end continues that session (same start; store it as an upsert).
 */
class ChargeDetector {
    private var low: ValueAt? = null
    private var previous: ChargeSession? = null
    /** The session in progress, if any. */
    var active: ChargeSession? = null; private set

    /** Feeds the latest fresh SOC (null if none); returns a session when one ends. */
    fun update(now: Long, soc: Double?, linkReady: Boolean, riding: Boolean): ChargeSession? {
        if (!linkReady || riding) {
            low = null
            val done = finish()
            if (riding) previous = null
            return done
        }
        val current = active
        if (soc == null || !soc.isFinite()) return if (current != null && now - current.end >= STALL_MS) finish() else null
        if (current != null) {
            if (soc > current.socEnd) { active = current.copy(end = now, socEnd = soc); return null }
            return if (now - current.end >= STALL_MS) finish() else null
        }
        val base = low
        if (base == null || soc <= base.value || now - base.time > WINDOW_MS) { low = ValueAt(now, soc); return null }
        if (soc - base.value >= MIN_RISE && now - base.time >= CONFIRM_MS) {
            val last = previous
            active = if (last != null && base.time - last.end <= MERGE_MS && base.value >= last.socEnd - 0.5)
                last.copy(end = now, socEnd = soc)
            else ChargeSession(base.time, now, base.value, soc)
            previous = null; low = null
        }
        return null
    }

    /** Ends any session now (tracker shutting down). */
    fun stop(): ChargeSession? { low = null; val done = finish(); previous = null; return done }

    private fun finish(): ChargeSession? {
        val done = active ?: return null
        active = null
        previous = done
        return done
    }

    companion object {
        const val MIN_RISE = 1.0
        const val CONFIRM_MS = 300_000L
        const val STALL_MS = 600_000L
        const val WINDOW_MS = 1_800_000L
        const val MERGE_MS = 1_800_000L
    }
}
