package dev.codesurgen.ruta

import android.media.session.PlaybackState
import android.os.SystemClock
import java.time.LocalDateTime

/*
 * Demo mode (FEATURES F-DEMO): Ruta without a scooter — for people trying the app, GitHub screenshots
 * and our own QA. Process-wide state, main thread (reads of [Demo.active] are safe from any thread).
 *
 * The shell (MainActivity) owns the lifecycle: it starts, switches and ends demo mode ([Demo.request]
 * reaches it), seeds the separate demo history ([TelemetryStore.demo]) and feeds Host from [Demo.live]
 * exactly where the real link feeds it (link READY, battery, vehicle, range, ride, navigation preview,
 * music and call). While demo mode is on: no Bluetooth activity, no writes to the rider's history,
 * no notifications or alerts ([Alerts], [Parking] and the range warning check [Demo.active]), and
 * RideService / RideTracker are never started. Every top app bar shows a "Demo" chip ([TopAppBar])
 * that opens the scenario sheet, so the demo cannot be taken for a real scooter.
 *
 * The numbers come from [DemoData] (pure, host-tested): a scenario's state is a function of the time
 * since it started. Music and the call follow the rider's taps (play/pause/skip, answer/decline).
 */
object Demo {
    private val listeners = linkedSetOf<() -> Unit>()

    /** The scenario being simulated; null = demo mode is off. */
    @Volatile var scenario: DemoScenario? = null; private set

    /** Demo mode is on. Any thread. */
    val active: Boolean get() = scenario != null

    /** Wall-clock start of the current scenario. */
    var startedAt = 0L; private set

    /** The seeded month (in memory: last ride, efficiency, parked spot); set while demo mode is on. */
    var history: DemoHistory? = null; private set

    /** The scenario's values at the last [update] (the shell updates once per tick and on changes). */
    var live: DemoLive? = null; private set

    /** The parked scenario's spot (what [Parking.spot] returns in demo mode): where the newest demo ride ended. */
    val parkedSpot: ParkedSpot?
        get() {
            if (scenario != DemoScenario.PARKED) return null
            val spot = history?.parkedSpot ?: return null
            return ParkedSpot(spot.lat, spot.lng, spot.accuracyM, spot.time, spot.place)
        }

    /** The shell's handler for [request] (MainActivity sets it in onCreate, clears it in onDestroy). */
    internal var controller: ((DemoScenario?) -> Unit)? = null

    /** What the top-bar "Demo" chip does: opens the demo sheet (set by the shell). */
    var onChip: (() -> Unit)? = null

    /** Asks the shell to start demo mode in [scenario] (or switch to it); null ends it. */
    fun request(scenario: DemoScenario?) {
        val shell = controller
        if (shell == null) { DiagLog.append("Demo mode: no app window to show it in"); return }
        Ui.run { shell(scenario) }
    }

    /** Called (main thread) whenever demo mode starts, ends or (re)starts a scenario. */
    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun changed() = listeners.toList().forEach { it() }

    // ------------------------------------------------------------------ shell side

    /** Starts [next] now (also a restart of the current one); [seeded] = the month the demo history holds. */
    internal fun begin(next: DemoScenario, seeded: DemoHistory, now: Long = System.currentTimeMillis(), elapsedMs: Long = 0L) {
        history = seeded
        scenario = next
        startedAt = now - elapsedMs
        val uptime = SystemClock.elapsedRealtime()
        // Music: "Night Drive" playing, 1:12 in. Call: rings two seconds in.
        track = 0; playing = true; positionMs = DemoData.MUSIC_START_MS; positionAt = uptime
        callPhase = CallPhase.IDLE; ringAt = uptime + DemoData.CALL_RING_AFTER_MS - elapsedMs; callStarted = 0L; callConnected = 0L; callEnded = 0L
        update(now)
        changed()
    }

    /** Ends demo mode (the shell restores the real state). */
    internal fun end() {
        if (scenario == null) return
        scenario = null
        history = null
        live = null
        changed()
    }

    /** Recomputes [live] for [now]; null when demo mode is off. */
    internal fun update(now: Long = System.currentTimeMillis()): DemoLive? {
        val current = scenario ?: return null
        val seeded = history ?: return null
        return DemoData.live(current, startedAt, now, seeded).also { live = it }
    }

    // ------------------------------------------------------------------ Host values

    /** The demo scooter (Home's title "Demo scooter", subtitle "Simulated data"; never a real device). */
    val scooter = SavedScooter(DemoData.DEVICE_MAC, DemoData.DEVICE_NAME, DemoData.SCOOTER_NAME, hasPin = false)

    fun link(live: DemoLive): LinkStatus = LinkStatus(
        phase = if (live.linked) LinkPhase.READY else LinkPhase.IDLE,
        live = live.linked,
        deviceName = DemoData.DEVICE_NAME,
        address = DemoData.DEVICE_MAC,
        connectedAt = if (live.linked) live.connectedAt else 0L
    )

    /** BMS readings as the decoder would report them; last-known (and so dimmed) while parked. */
    fun reading(live: DemoLive, pack: Int, metric: Metric, now: Long): Reading? {
        val value = when (metric) {
            Metric.SOC -> when (pack) { 0 -> live.soc; 1 -> live.socFront; 2 -> live.socRear; else -> null }
            Metric.HEALTH -> when (pack) { 0 -> live.health; 1 -> live.healthFront; 2 -> live.healthRear; else -> null }
            Metric.ENERGY -> when (pack) { 0 -> live.energy; 1 -> live.energyFront; 2 -> live.energyRear; else -> null }
            Metric.TEMPERATURE -> when (pack) { 0 -> live.temp; 1 -> live.tempFront; 2 -> live.tempRear; else -> null }
            Metric.STATUS -> when (pack) { 0 -> live.status.toDouble(); 1, 2 -> live.packStatus.toDouble(); else -> null }
        } ?: return null
        // Parked: the last frame arrived when the newest ride ended.
        val time = if (live.linked) now else history?.lastRide?.end ?: (now - DemoData.LAST_RIDE_AGO_MS)
        return Reading(pack, metric, Math.round(value * 100) / 100.0, time)
    }

    fun vehicle(live: DemoLive, now: Long): VehicleState? = if (!live.linked) null else VehicleState(
        ignitionOn = live.ignitionOn, immobilised = false, handleLocked = live.handleLocked,
        rideMode = 2, regenLevel = 1, accelerationLevel = 1, speedLimit = 0, time = now
    )

    fun range(live: DemoLive): RangeEstimate? = RangeModel.estimate(live.soc, null, history?.efficiency ?: RangeEfficiency.DEFAULT)

    fun driveState(live: DemoLive): DriveState = when {
        live.charge != null -> DriveState.CHARGING
        live.status == 4 -> DriveState.DRIVE
        else -> DriveState.STANDBY
    }

    /** What RideLive would hold; [revision] is the real one so screens reload when the shell bumps it. */
    fun ride(live: DemoLive, revision: Int): RideState {
        val r = live.ride
        return RideState(
            gps = if (live.linked) GpsStatus.FIX else GpsStatus.IDLE,
            riding = r != null,
            startedAt = r?.startedAt ?: 0L,
            distanceM = r?.distanceM ?: 0.0,
            durationS = r?.durationS ?: 0L,
            speedKmh = r?.speedKmh ?: 0.0,
            peakKmh = r?.peakKmh ?: 0.0,
            avgKmh = r?.avgKmh ?: 0.0,
            socStart = r?.socStart,
            startPlace = r?.startPlace,
            charging = live.charge,
            revision = revision,
            link = if (live.linked) LinkPhase.READY else LinkPhase.IDLE,
            socPercent = live.soc.takeIf { live.linked },
            range = if (live.linked) range(live) else null
        )
    }

    /** The Maps-like route as the Navigate preview shows it; the demo scooter "acknowledges" frames while Directions is on. */
    fun navPreview(live: DemoLive, directionsOn: Boolean): NavPreview {
        val nav = live.nav ?: return NavPreview(enabled = directionsOn, listenerConnected = true)
        val frame = nav.cue.frame(LocalDateTime.now(), nav.maneuverM, nav.remainingM)
        return NavPreview(
            frame = frame,
            instruction = nav.cue.instruction,
            road = nav.cue.road,
            thenTurn = nav.cue.thenTurn,
            display = if (directionsOn && live.linked) NavDisplay.SHOWING else NavDisplay.UNAVAILABLE,
            enabled = directionsOn,
            listenerConnected = true,
            updatedAt = System.currentTimeMillis()
        )
    }

    // ------------------------------------------------------------------ music

    private var track = 0
    private var playing = true
    private var positionMs = 0L
    private var positionAt = 0L

    private fun position(uptime: Long): Long = if (playing) positionMs + (uptime - positionAt) else positionMs

    /** Moves on to the next track when one ends (as a player would). */
    private fun advance(uptime: Long) {
        var guard = 0
        while (playing && guard++ < 10) {
            val duration = DemoData.TRACKS[track].durationMs
            val pos = position(uptime)
            if (pos < duration) return
            track = (track + 1) % DemoData.TRACKS.size
            positionMs = 0L
            positionAt = uptime - (pos - duration)
        }
    }

    /** The demo track while the music scenario runs, else null. */
    fun media(): MediaState? {
        if (scenario != DemoScenario.MUSIC) return null
        val uptime = SystemClock.elapsedRealtime()
        advance(uptime)
        val t = DemoData.TRACKS[track]
        return MediaState(
            packageName = "dev.codesurgen.ruta.demo", appLabel = "Demo music", title = t.title, artist = t.artist, album = "",
            playbackState = if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
            positionMs = position(uptime), positionAt = uptime, speed = 1f, durationMs = t.durationMs
        )
    }

    /** Play/pause/skip on the demo track; false when the music scenario isn't running. */
    fun mediaAction(action: MediaAction): Boolean {
        if (scenario != DemoScenario.MUSIC) return false
        val uptime = SystemClock.elapsedRealtime()
        advance(uptime)
        fun seek(index: Int) { track = (index + DemoData.TRACKS.size) % DemoData.TRACKS.size; positionMs = 0L; positionAt = uptime }
        when (action) {
            MediaAction.PLAY_PAUSE -> setPlaying(!playing, uptime)
            MediaAction.PLAY -> setPlaying(true, uptime)
            MediaAction.PAUSE -> setPlaying(false, uptime)
            MediaAction.NEXT -> seek(track + 1)
            // Like players: back to the start of the track first, then the previous one.
            MediaAction.PREVIOUS -> if (position(uptime) > 3_000L) seek(track) else seek(track - 1)
            MediaAction.VOLUME_UP, MediaAction.VOLUME_DOWN, MediaAction.MUTE -> {}
        }
        return true
    }

    private fun setPlaying(on: Boolean, uptime: Long) {
        if (on == playing) return
        positionMs = position(uptime); positionAt = uptime; playing = on
    }

    // ------------------------------------------------------------------ call

    private var callPhase = CallPhase.IDLE
    private var ringAt = 0L
    private var callStarted = 0L
    private var callConnected = 0L
    private var callEnded = 0L

    /** Advances the call by time: rings, goes unanswered (missed), clears, rings again. */
    private fun stepCall(uptime: Long) {
        if (scenario != DemoScenario.CALL) return
        if (callPhase == CallPhase.IDLE && uptime >= ringAt) { callPhase = CallPhase.RINGING; callStarted = uptime; callConnected = 0L; callEnded = 0L }
        if (callPhase == CallPhase.RINGING && uptime - callStarted >= DemoData.CALL_RING_MS) hangUp(uptime)
        if (callPhase == CallPhase.ENDED && uptime - callEnded >= ENDED_HOLD_MS) callPhase = CallPhase.IDLE
    }

    private fun hangUp(uptime: Long) {
        callPhase = CallPhase.ENDED; callEnded = uptime
        ringAt = uptime + DemoData.CALL_AGAIN_AFTER_MS
    }

    /** The demo call while the call scenario runs, else an idle call. */
    fun call(): CallState {
        if (scenario != DemoScenario.CALL) return CallState()
        stepCall(SystemClock.elapsedRealtime())
        return CallState(
            phase = callPhase, outgoing = false, number = null, name = DemoData.CALLER,
            startedAt = callStarted, connectedAt = callConnected, endedAt = callEnded
        )
    }

    /** Answer/decline/end the demo call; false when the call scenario isn't running. */
    fun callAction(action: CallAction): Boolean {
        if (scenario != DemoScenario.CALL) return false
        val uptime = SystemClock.elapsedRealtime()
        stepCall(uptime)
        when (action) {
            CallAction.ACCEPT -> if (callPhase == CallPhase.RINGING) { callPhase = CallPhase.ACTIVE; callConnected = uptime }
            CallAction.REJECT -> if (callPhase == CallPhase.RINGING || callPhase == CallPhase.ACTIVE) hangUp(uptime)
        }
        return true
    }

    /** What the demo scooter's display "shows" for music and calls (acknowledged while the feature is on). */
    fun musicFeed(live: DemoLive, enabled: Boolean): ClusterFeed = when {
        !live.linked -> ClusterFeed.UNAVAILABLE
        !enabled -> ClusterFeed.OFF
        scenario == DemoScenario.MUSIC && callPhase != CallPhase.RINGING && callPhase != CallPhase.ACTIVE -> ClusterFeed.SHOWING
        else -> ClusterFeed.IDLE
    }

    fun callFeed(live: DemoLive, enabled: Boolean): ClusterFeed = when {
        !live.linked -> ClusterFeed.UNAVAILABLE
        !enabled -> ClusterFeed.OFF
        scenario == DemoScenario.CALL && callPhase != CallPhase.IDLE -> ClusterFeed.SHOWING
        else -> ClusterFeed.IDLE
    }

    /** PhoneBridge holds an ended call this long before it clears. */
    private const val ENDED_HOLD_MS = 2_000L
}
