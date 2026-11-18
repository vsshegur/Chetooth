package dev.codesurgen.ruta

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * What the app tells the ride tracker. Call from the main thread whenever a value changes (repeats are
 * cheap; the tracker samples these once a second and may start later than the link):
 *  - [link] with every [LinkPhase] change (or simply from a 1 s UI tick);
 *  - [vehicle] with each VehicleState from the TCU (null when unknown);
 *  - [reading] with every BMS [Reading] (only pack-0 SOC is used);
 *  - [range] with a range decoded from the scooter, if one is ever found (null = estimate it).
 */
object RideInputs {
    @Volatile var phase: LinkPhase = LinkPhase.IDLE; private set
    @Volatile var ignitionOn: Boolean? = null; private set
    @Volatile var soc: ValueAt? = null; private set
    /** Range in km reported by the scooter; wins over the estimate in [RangeModel.estimate]. */
    @Volatile var decodedRangeKm: Double? = null; private set

    fun range(km: Double?) { decodedRangeKm = km }

    fun link(phase: LinkPhase) {
        this.phase = phase
        // A stale ignition flag must not start a ride on the next connection.
        if (phase == LinkPhase.IDLE) { ignitionOn = null; decodedRangeKm = null }
    }

    fun vehicle(state: VehicleState?) { ignitionOn = state?.ignitionOn }

    fun reading(reading: Reading) {
        if (reading.pack == 0 && reading.metric == Metric.SOC) soc = ValueAt(reading.time, reading.value)
    }
}

/**
 * OFF: tracker not running (not connected). NO_PERMISSION: precise location not granted. DISABLED: phone
 * location is off. IDLE: listening passively (ignition off, no ride). SEARCHING: GPS on, no fix for 30 s.
 * FIX: GPS on with a recent fix.
 */
enum class GpsStatus { OFF, NO_PERMISSION, DISABLED, IDLE, SEARCHING, FIX }

/** Live tracker state for the UI; [durationS] counts from the first movement. */
data class RideState(
    val gps: GpsStatus = GpsStatus.OFF,
    val riding: Boolean = false,
    val startedAt: Long = 0,
    val distanceM: Double = 0.0,
    val durationS: Long = 0,
    val speedKmh: Double = 0.0,
    val peakKmh: Double = 0.0,
    val avgKmh: Double = 0.0,
    val socStart: Double? = null,
    val startPlace: String? = null,
    /** Charging session in progress (end/socEnd = latest rise). */
    val charging: ChargeSession? = null,
    /** Bumps whenever a ride or charging session is saved or updated: reload lists when it changes. */
    val revision: Int = 0,
    /** Link phase as last reported through [RideInputs.link]. */
    val link: LinkPhase = LinkPhase.IDLE,
    /** Latest fresh (≤ 90 s) pack-0 charge, percent; null when none. */
    val socPercent: Double? = null,
    /** Range from [socPercent] (or a decoded range) and the learned km per %; null without a fresh charge. */
    val range: RangeEstimate? = null
)

/** Process-wide live ride state, confined to the main thread (like NavBridge). */
object RideLive {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = linkedSetOf<() -> Unit>()
    private val revisions = AtomicInteger()

    var state = RideState(); private set
    /** Where ride messages go; [DiagLog] (logcat tag Ruta + log file) by default. */
    var log: (String) -> Unit = { DiagLog.append(it) }

    fun addListener(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }

    internal val revision: Int get() = revisions.get()

    /** Stored rides changed outside the tracker (e.g. a ride was deleted): listeners reload. Any thread. */
    fun invalidate() = bump()

    /** Marks stored data as changed; revisions are applied in order on the main thread. */
    internal fun bump() {
        val next = revisions.incrementAndGet()
        main.post { if (state.revision < next) { state = state.copy(revision = next); changed() } }
    }

    internal fun publish(next: RideState) {
        main.post { if (next != state) { state = next; changed() } }
    }

    private fun changed() = listeners.toList().forEach { it() }

    internal fun say(line: String) { main.post { log(line) } }
}

/**
 * Records rides with phone GPS and detects charging, persisting through [TelemetryStore]. Location mode:
 * GPS at 1 s during a ride; GPS at 2 s while connected with ignition on or unknown (so movement can
 * start a ride); otherwise the passive provider (fixes other apps such as Maps request). All work runs
 * on one process-wide background thread; [RideLive] receives the state on the main thread.
 * Hosted by [RideService]: [start] when the service starts, [stop] when it ends (an active ride is saved).
 *
 * Find my scooter (F-PARK): saves the parked spot ([Parking], rules in [ParkingRules]) when a ride
 * ends — from the moment the ignition went off or the link dropped during it, else where it last
 * moved — and when the link ends with no ride in progress (a fix ≤ 2 min old and ≤ 50 m). The place
 * name is looked up afterwards with the same geocoder as rides. Positions are never logged.
 */
@SuppressLint("MissingPermission") // Location calls happen only after checking ACCESS_FINE_LOCATION.
class RideTracker(context: Context, private val store: TelemetryStore = TelemetryStore.shared(context)) {
    private val app = context.applicationContext
    private val locations: LocationManager? = app.getSystemService(LocationManager::class.java)
    private val worker = Handler(looper)

    private enum class Mode { NONE, PASSIVE, STANDBY, RIDE }

    private class Active(val id: Long, val start: Long, var socStart: Double?) {
        val track = RideAccumulator()
        val pending = mutableListOf<RidePoint>()
        var lastStoredAt = 0L
        var skipped: RidePoint? = null
        var startPlace: String? = null
        var lookingUpStart = false
        /** Where the phone was when the ignition went off / the link dropped during this ride (parking). */
        var ignitionOff: ParkMoment? = null
        var linkLost: ParkMoment? = null
    }

    // Confined to the worker thread.
    private var running = false
    private val filter = FixFilter()
    private val segmenter = RideSegmenter()
    private val charges = ChargeDetector()
    /** The last minute of steps: a ride started by speed begins ~10 s before it is confirmed. */
    private val recent = ArrayDeque<TrackStep>()
    private var active: Active? = null
    private var mode = Mode.NONE
    private var modeError: String? = null
    private var permitted = false
    private var locationOn = false
    private var lastCheckAt = 0L
    private var lastFlushAt = 0L
    private var lastChargeSaveAt = 0L
    /** 2.0 rides whose place names are looked up again, one at a time. */
    private val relabelQueue = ArrayDeque<Ride>()
    // Parking (worker thread): the link as this tracker saw it, the last ignition flag, the newest raw fix.
    private var linkUp = false
    private var lastIgnition: Boolean? = null
    private var lastRaw: GeoFix? = null

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onFix(location)
        override fun onProviderEnabled(provider: String) { lastCheckAt = 0 }
        override fun onProviderDisabled(provider: String) { lastCheckAt = 0 }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            try { step() } catch (e: Exception) { say("Ride tracking error: ${e.javaClass.simpleName}: ${e.message}") }
            worker.postDelayed(this, 1_000)
        }
    }

    fun start() {
        worker.post {
            if (running) return@post
            running = true
            lastCheckAt = 0
            io("recover rides") { recover() }
            io("learn range") { store.kmPerPercent() }
            io("look up places") {
                val now = System.currentTimeMillis()
                val missing = store.ridesMissingPlaces(now - 30 * DAY_MS, 10)
                missing.forEach(::lookUpPlaces)
                val queued = relabelQueue.map { it.id }.toSet() + missing.map { it.id }
                relabelQueue += store.ridesWithOldPlaces(now - 365 * DAY_MS, 20).filter { it.id !in queued }
                if (relabelQueue.isNotEmpty()) say("Updating place names of ${relabelQueue.size} ride(s)")
                relabelNext()
            }
            io("look up the parked place") { if (parkingOn()) Parking.stored(app)?.takeIf { it.place == null }?.let(::lookUpParkedPlace) }
            worker.post(tick)
        }
    }

    /** Saves an active ride and charging session, then releases GPS. */
    fun stop() {
        worker.post {
            if (!running) return@post
            val now = System.currentTimeMillis()
            segmenter.stop(now)?.let(::end)
            // The service stops when the link ends for good (Disconnect, the scooter gone, Ruta closed).
            if (linkUp) { linkUp = false; parkAtLinkEnd(now) }
            charges.stop()?.let { saveCharge(it, finished = true) }
            running = false
            worker.removeCallbacks(tick)
            setMode(Mode.NONE)
            filter.reset(); recent.clear(); relabelQueue.clear()
            lastIgnition = null; lastRaw = null
            RideLive.publish(RideState(revision = RideLive.revision))
        }
    }

    private fun step() {
        val now = System.currentTimeMillis()
        val ready = RideInputs.phase == LinkPhase.READY
        val ignition = RideInputs.ignitionOn
        if (now - lastCheckAt >= 5_000) {
            lastCheckAt = now
            permitted = app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val manager = locations
            locationOn = manager != null && manager.isLocationEnabled &&
                runCatching { manager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)
        }
        val fresh = recent.lastOrNull()?.takeIf { now - it.time <= FRESH_FIX_MS }
        when (val event = segmenter.update(now, ready, ignition, fresh?.speedKmh)) {
            is RideEvent.Started -> begin(event)
            is RideEvent.Ended -> end(event)
            null -> {}
        }
        trackParking(now, ready, ignition)
        val soc = RideInputs.soc?.takeIf { now - it.time in 0..FRESH_SOC_MS }?.value
        active?.let { if (it.socStart == null) it.socStart = soc }

        val wasCharging = charges.active != null
        charges.update(now, soc, ready, segmenter.riding)?.let { saveCharge(it, finished = true) }
        charges.active?.let {
            if (!wasCharging) { say("Charging detected: ${number(it.socStart, 0)} % → ${number(it.socEnd, 0)} %"); lastChargeSaveAt = 0 }
            if (now - lastChargeSaveAt >= 60_000) { lastChargeSaveAt = now; saveCharge(it, finished = false) }
        }

        if (now - lastFlushAt >= 10_000) { lastFlushAt = now; active?.let(::flush) }
        setMode(when {
            !permitted || locations == null -> Mode.NONE
            active != null -> Mode.RIDE
            ready && ignition != false -> Mode.STANDBY
            else -> Mode.PASSIVE
        })
        publish(now, fresh, soc)
    }

    // ------------------------------------------------------------------ rides

    private fun begin(event: RideEvent.Started) {
        val soc = RideInputs.soc?.takeIf { System.currentTimeMillis() - it.time in 0..FRESH_SOC_MS }?.value
        val id = io("start ride") { store.beginRide(event.time, soc) } ?: -1L
        val ride = Active(id, event.time, soc)
        recent.filter { it.time >= event.time }.forEach { record(ride, it) }
        active = ride
        say(if (event.byIgnition) "Ride started (ignition on)" else "Ride started (moving)")
    }

    private fun record(ride: Active, step: TrackStep) {
        val point = ride.track.add(step)
        // A standstill repeats one position: keep a point every 10 s there for the speed chart, plus the
        // last one before moving off (it is where the ride starts once idle time is trimmed).
        if (ride.lastStoredAt == 0L || step.stepM > 0 || point.time - ride.lastStoredAt >= 10_000) {
            if (step.stepM > 0) ride.skipped?.let { ride.pending += it }
            ride.pending += point
            ride.lastStoredAt = point.time
            ride.skipped = null
        } else ride.skipped = point
        // Name the start early for the live card; a ride that ends first looks it up again when saved.
        val from = ride.track.startPoint
        if (from != null && !ride.lookingUpStart) {
            ride.lookingUpStart = true
            geocode(from.lat, from.lng) { ride.startPlace = it }
        }
    }

    private fun flush(ride: Active) {
        if (ride.id < 0) { ride.pending.clear(); return }
        val points = ride.pending.toList()
        ride.pending.clear()
        io("save ride points") { store.appendRidePoints(ride.id, points, ride.socStart, RideInputs.soc?.value) }
    }

    private fun end(event: RideEvent.Ended) {
        val ride = active ?: return
        active = null
        flush(ride)
        val socEnd = RideInputs.soc?.takeIf { it.time >= ride.start }?.value
        val summary = ride.track.finish(ride.id, ride.socStart, socEnd)
        if (summary == null) {
            if (ride.id >= 0) io("discard ride") { store.deleteRide(ride.id) }
            say("Ride not saved: ${number(ride.track.distanceM, 0)} m (needs ${RideMath.MIN_RIDE_M.toInt()} m and 1 min)")
            parkAtRideEnd(ride, event)
            return
        }
        val saved = summary.copy(startPlace = ride.startPlace)
        if (ride.id >= 0) io("save ride") { store.finishRide(saved); store.kmPerPercent() }
        RideLive.bump()
        say("Ride saved: ${number(saved.distanceKm, 1)} km in ${RideMath.formatDuration(saved.durationS)}, " +
            "peak ${number(saved.peakKmh, 0)} km/h, avg ${number(saved.avgKmh, 0)} km/h (${event.reason.name.lowercase(Locale.US)})")
        if (ride.id >= 0) lookUpPlaces(saved)
        parkAtRideEnd(ride, event)
    }

    /** Finishes rides left open by a killed process from their stored points. */
    private fun recover() {
        for (open in store.openRides()) {
            val ride = RideAccumulator.replay(store.ridePoints(open.id)).finish(open.id, open.socStart, open.socEnd)
            if (ride == null) { store.deleteRide(open.id); continue }
            store.finishRide(ride)
            RideLive.bump()
            say("Recovered an interrupted ride: ${number(ride.distanceKm, 1)} km")
            lookUpPlaces(ride)
        }
    }

    /** Looks up both ends of the next queued 2.0 ride and replaces its names only when both are found. */
    private fun relabelNext() {
        val ride = relabelQueue.removeFirstOrNull() ?: return
        if (!running) { relabelQueue.clear(); return }
        geocode(ride.startLat, ride.startLng) { start ->
            if (start == null) { relabelNext(); return@geocode }
            geocode(ride.endLat, ride.endLng) { end ->
                if (end != null) io("save place") { store.replaceRidePlaces(ride.id, start, end); RideLive.bump() }
                relabelNext()
            }
        }
    }

    private fun lookUpPlaces(ride: Ride) {
        if (ride.startPlace == null) geocode(ride.startLat, ride.startLng) { place ->
            if (place != null) io("save place") { store.setRidePlaces(ride.id, place, null); RideLive.bump() }
        }
        if (ride.endPlace == null) geocode(ride.endLat, ride.endLng) { place ->
            if (place != null) io("save place") { store.setRidePlaces(ride.id, null, place); RideLive.bump() }
        }
    }

    // ------------------------------------------------------------------ parking (F-PARK)

    /**
     * Notes, during a ride, where the phone was when the ignition turned off (kept until it is on
     * again) and when the link dropped (cleared when it is back): the ride's end saves the spot from
     * those moments. When the link ends with no ride in progress, saves a spot right away.
     */
    private fun trackParking(now: Long, ready: Boolean, ignition: Boolean?) {
        active?.let { ride ->
            if (ignition == true) ride.ignitionOff = null
            else if (ignition == false && lastIgnition == true) ride.ignitionOff = ParkMoment(now, newestFix(now))
            if (ready) ride.linkLost = null
        }
        lastIgnition = ignition
        if (ready) linkUp = true
        else if (linkUp) {
            linkUp = false
            val ride = active
            if (ride != null) { if (ride.linkLost == null) ride.linkLost = ParkMoment(now, newestFix(now)) }
            else parkAtLinkEnd(now)
        }
    }

    private fun parkAtRideEnd(ride: Active, event: RideEvent.Ended) {
        if (!parkingOn()) return
        val now = System.currentTimeMillis()
        val stop = ride.track.endPoint?.let { GeoFix(it.time, it.lat, it.lng, it.accuracyM, it.speedKmh) }
        val spot = ParkingRules.atRideEnd(event.reason, event.time, ParkMoment(now, newestFix(now)), ride.ignitionOff, ride.linkLost, stop)
        if (spot == null) { say("Parked spot not saved: no location within ${ParkingRules.MAX_ACCURACY_M.toInt()} m at the ride's end, or the phone is still moving"); return }
        park(spot, "ride ended")
    }

    private fun parkAtLinkEnd(now: Long) {
        if (!parkingOn()) return
        val saved = io("read the parked spot") { Parking.stored(app) }
        val candidates = listOf(newestStep(now), lastRaw) + systemFixes(now)
        val spot = ParkingRules.atLinkEnd(now, candidates, saved)
        val fix = candidates.firstOrNull { ParkingRules.usable(it, now) }
        when {
            spot != null -> park(spot, "scooter disconnected")
            fix == null -> say("Parked spot not saved: no location within ${ParkingRules.MAX_ACCURACY_M.toInt()} m in the last 2 min")
            saved != null && ParkingRules.stillThere(saved, fix) -> say("Parked spot kept: the scooter is still where it was parked")
            else -> say("Parked spot not saved: the phone is moving")
        }
    }

    /** Saves [spot] (replacing the older one) and looks up its place name. */
    private fun park(spot: ParkedSpot, why: String) {
        if (io("save the parked spot") { Parking.save(app, spot) } != true) return
        say("Parked spot saved: $why, ${ParkingRules.accuracyText(spot.accuracyM)}")
        lookUpParkedPlace(spot)
    }

    private fun lookUpParkedPlace(spot: ParkedSpot) {
        geocode(spot.lat, spot.lng) { place -> if (place != null) io("save the parked place") { Parking.setPlace(app, spot.time, place) } }
    }

    private fun parkingOn(): Boolean = !Demo.active && Alerts.enabled(app, AlertKind.PARKING)

    /** The newest filtered step (≤ 25 m, no jumps) if ≤ 2 min old. */
    private fun newestStep(now: Long): GeoFix? = recent.lastOrNull()
        ?.takeIf { now - it.time <= ParkingRules.MAX_FIX_AGE_MS }
        ?.let { GeoFix(it.time, it.lat, it.lng, it.accuracyM, it.speedKmh) }

    /** The phone's newest position: the filtered step, else the raw fix (it may be up to 50 m). */
    private fun newestFix(now: Long): GeoFix? = newestStep(now) ?: lastRaw

    /** Android's last known fixes from every enabled provider (other apps' GPS too), newest first. */
    private fun systemFixes(now: Long): List<GeoFix> {
        val manager = locations ?: return emptyList()
        if (app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val providers = runCatching { manager.getProviders(true) }.getOrDefault(emptyList())
        return providers.mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()?.let { location ->
                val ageMs = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000
                geoFix(location, now - ageMs.coerceAtLeast(0))
            }
        }.sortedByDescending { it.time }
    }

    // ------------------------------------------------------------------ charging

    private fun saveCharge(session: ChargeSession, finished: Boolean) {
        io("save charging session") { store.saveChargeSession(session) }
        RideLive.bump()
        if (finished) say("Charging session saved: ${number(session.socStart, 0)} % → ${number(session.socEnd, 0)} % in " +
            "${RideMath.formatDuration(session.durationS)} (${number(session.percentPerHour)} %/h)")
    }

    // ------------------------------------------------------------------ location

    private fun onFix(location: Location) {
        if (!running) return
        val ageMs = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000
        if (ageMs !in -1_000..MAX_FIX_AGE_MS) return // Cached fixes handed over by the passive provider.
        val fix = geoFix(location, System.currentTimeMillis() - ageMs.coerceAtLeast(0))
        if (ParkingRules.validPosition(fix.lat, fix.lng)) lastRaw = fix
        val step = filter.accept(fix) ?: return
        recent.addLast(step)
        while (recent.first().time < step.time - 60_000) recent.removeFirst()
        active?.let { record(it, step) }
    }

    /** A raw fix at wall-clock [time]: accuracy in m and speed in km/h, NaN when the fix has none. */
    private fun geoFix(location: Location, time: Long) = GeoFix(
        time, location.latitude, location.longitude,
        if (location.hasAccuracy()) location.accuracy.toDouble() else Double.NaN,
        if (location.hasSpeed()) location.speed * 3.6 else Double.NaN
    )

    private fun setMode(wanted: Mode) {
        if (wanted == mode) return
        val manager = locations ?: return
        manager.removeUpdates(listener)
        mode = Mode.NONE
        try {
            when (wanted) {
                // No distance threshold: fixes keep coming at a standstill (GPS status and the speed chart's
                // 0 km/h points); FixFilter drops the jitter.
                Mode.RIDE -> manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, listener, worker.looper)
                Mode.STANDBY -> manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2_000L, 0f, listener, worker.looper)
                Mode.PASSIVE -> manager.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 1_000L, 0f, listener, worker.looper)
                Mode.NONE -> {}
            }
            mode = wanted
            modeError = null
        } catch (e: Exception) {
            // Missing provider or permission revoked: retried every second, reported once.
            val message = "${e.javaClass.simpleName}: ${e.message}"
            if (message != modeError) { modeError = message; say("Location unavailable: $message") }
        }
    }

    private fun publish(now: Long, fresh: TrackStep?, soc: Double?) {
        val ride = active
        val track = ride?.track
        val gps = when {
            !permitted -> GpsStatus.NO_PERMISSION
            !locationOn -> GpsStatus.DISABLED
            mode == Mode.PASSIVE -> GpsStatus.IDLE
            recent.lastOrNull()?.let { now - it.time <= 30_000 } == true -> GpsStatus.FIX
            else -> GpsStatus.SEARCHING
        }
        val since = track?.startPoint?.time ?: ride?.start ?: 0
        RideLive.publish(RideState(
            gps = gps, riding = ride != null, startedAt = since,
            distanceM = track?.distanceM ?: 0.0,
            durationS = if (ride != null) ((now - since) / 1000).coerceAtLeast(0) else 0,
            speedKmh = if (fresh == null) 0.0 else track?.speedKmh ?: fresh.speedKmh,
            peakKmh = track?.peakKmh ?: 0.0, avgKmh = track?.averageKmh ?: 0.0,
            socStart = ride?.socStart, startPlace = ride?.startPlace,
            charging = charges.active, revision = RideLive.revision,
            link = RideInputs.phase, socPercent = soc,
            range = RangeModel.estimate(soc, RideInputs.decodedRangeKm, store.cachedKmPerPercent)
        ))
    }

    /**
     * Reverse-geocodes to "Street, Area" ([RideMath.placeLabel] over the nearest few results); [done]
     * runs once on the worker thread, with null when unavailable (offline, no geocoder, nothing usable).
     */
    private fun geocode(lat: Double, lng: Double, done: (String?) -> Unit) {
        if (!lat.isFinite() || !lng.isFinite() || (lat == 0.0 && lng == 0.0)) { worker.post { done(null) }; return }
        val delivered = java.util.concurrent.atomic.AtomicBoolean(false)
        val deliver = { addresses: List<Address>? ->
            if (delivered.compareAndSet(false, true)) {
                val place = try {
                    addresses?.takeIf { it.isNotEmpty() }?.let { list ->
                        RideMath.placeLabel(list.map { PlaceParts(it.thoroughfare, it.subLocality, it.locality, it.featureName, it.subAdminArea, it.adminArea) })
                    }
                } catch (_: Exception) { null }
                worker.post { done(place) }
            }
            Unit
        }
        try {
            if (!Geocoder.isPresent()) { deliver(null); return }
            val geocoder = Geocoder(app, Locale.getDefault())
            if (Build.VERSION.SDK_INT >= 33) {
                geocoder.getFromLocation(lat, lng, GEOCODE_RESULTS, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) { deliver(addresses) }
                    override fun onError(errorMessage: String?) { deliver(null) }
                })
            } else {
                @Suppress("DEPRECATION")
                geocodeExecutor.execute { deliver(runCatching { geocoder.getFromLocation(lat, lng, GEOCODE_RESULTS) }.getOrNull()) }
            }
        } catch (_: Exception) { deliver(null) }
    }

    private fun say(line: String) = RideLive.say(line)

    private inline fun <T> io(what: String, block: () -> T): T? = try { block() } catch (e: Exception) {
        say("Could not $what: ${e.javaClass.simpleName}: ${e.message}"); null
    }

    companion object {
        private const val FRESH_FIX_MS = 5_000L
        private const val FRESH_SOC_MS = 90_000L
        private const val MAX_FIX_AGE_MS = 10_000L
        private const val DAY_MS = 86_400_000L
        /** Nearest results to merge: the first is often a plus code or lacks the street. */
        private const val GEOCODE_RESULTS = 3

        /**
         * One thread for the process lifetime, so a tracker that is still saving its last ride and a new
         * tracker recovering open rides never run concurrently.
         */
        private val looper: Looper by lazy { HandlerThread("RideTracker").apply { start() }.looper }
        private val geocodeExecutor by lazy { Executors.newSingleThreadExecutor() }
    }
}
