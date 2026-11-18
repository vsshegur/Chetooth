package dev.codesurgen.ruta

import android.app.Activity
import android.content.Context
import android.util.Log
import android.view.View

/*
 * Contract between the shell (MainActivity implements Host) and the tab screens, which are separate
 * classes constructed with the host: HomeScreen(host), NavigateScreen(host), RidesScreen(host),
 * InsightsScreen(host), MoreScreen(host); each implements Screen (UiKit.kt).
 *
 * Every member except [Host.activity] has a safe default (no-op / null / empty / derived), so the
 * tree compiles whatever subset MainActivity overrides. All members are main-thread only. Screens
 * read state in render(now) (called ~1 Hz and on live changes) and call actions on taps.
 */

/** Bottom-bar destinations in order, with their Material Symbols icon names. */
enum class Destination(val label: String, val icon: String) {
    HOME("Home", "home"),
    NAVIGATE("Navigate", "navigation"),
    RIDES("Rides", "route"),
    INSIGHTS("Insights", "insights"),
    MORE("More", "more_horiz")
}

/** Link summary for the UI. */
data class LinkStatus(
    /** UI phase: retrying a lost link counts as CONNECTING, also between attempts. */
    val phase: LinkPhase = LinkPhase.IDLE,
    /** The link dropped by itself and the same scooter is being retried. */
    val reconnecting: Boolean = false,
    /** READY and BMS frames arrived within the last 15 s. */
    val live: Boolean = false,
    /** READY but no frames for > 15 s since this time (0 = not silent). */
    val silentSince: Long = 0L,
    /** Bluetooth name of the linked/last scooter, e.g. "HMC_000000". */
    val deviceName: String? = null,
    /** MAC of the linked/last scooter. */
    val address: String? = null,
    /** Last failure in plain words (for a snackbar/tooltip), null when fine. */
    val error: String? = null,
    /** Wall-clock time the link became READY (0 = never). */
    val connectedAt: Long = 0L
)

/** One pack (or the scooter total) as last reported; null = never reported. */
data class PackSnapshot(
    val soc: Double? = null,
    val health: Double? = null,
    val energy: Double? = null,
    val temperatureC: Double? = null,
    /** Raw BMS status word: 4 = Drive, 0/2 = Standby (show via [DriveState], never raw). */
    val status: Int? = null,
    /** Time of the newest reading in this pack (0 = none). */
    val updatedAt: Long = 0L
)

/**
 * Latest BMS values (last-known values survive a disconnect). [fresh] false = show them dimmed.
 * [pack1]/[pack2] are labelled "F"/"R" in the UI (DESIGN › Home).
 */
data class BatterySnapshot(
    val total: PackSnapshot = PackSnapshot(),
    val pack1: PackSnapshot = PackSnapshot(),
    val pack2: PackSnapshot = PackSnapshot(),
    /** Link READY and the total charge reading is at most 90 s old. */
    val fresh: Boolean = false,
    val updatedAt: Long = 0L
) {
    val hasData: Boolean get() = updatedAt > 0L
    /** |pack1 − pack2| charge in points, when both are known. */
    val imbalance: Double? get() {
        val a = pack1.soc ?: return null
        val b = pack2.soc ?: return null
        return kotlin.math.abs(a - b)
    }

    companion object {
        const val FRESH_MS = 90_000L

        /** Builds a snapshot from per-pack readings (pack 0 = total, 1, 2). */
        fun of(reading: (Int, Metric) -> Reading?, now: Long, linkReady: Boolean): BatterySnapshot {
            fun pack(n: Int): PackSnapshot {
                val values = Metric.entries.associateWith { reading(n, it) }
                return PackSnapshot(
                    soc = values[Metric.SOC]?.value,
                    health = values[Metric.HEALTH]?.value,
                    energy = values[Metric.ENERGY]?.value,
                    temperatureC = values[Metric.TEMPERATURE]?.value,
                    status = values[Metric.STATUS]?.value?.toInt(),
                    updatedAt = values.values.maxOfOrNull { it?.time ?: 0L } ?: 0L
                )
            }
            val total = pack(0)
            val socAt = reading(0, Metric.SOC)?.time ?: 0L
            val one = pack(1); val two = pack(2)
            return BatterySnapshot(
                total = total, pack1 = one, pack2 = two,
                fresh = linkReady && socAt > 0 && now - socAt in 0..FRESH_MS,
                updatedAt = maxOf(total.updatedAt, one.updatedAt, two.updatedAt)
            )
        }
    }
}

/** Scooter state glyph in the cluster centre: bolt / electric_moped / local_parking. */
enum class DriveState(val label: String, val icon: String) {
    UNKNOWN("—", "electric_moped"),
    STANDBY("Standby", "local_parking"),
    DRIVE("Drive", "electric_moped"),
    CHARGING("Charging", "bolt");

    companion object {
        /** DESIGN F8: status 4 = Drive, 0/2 = Standby; charging wins. */
        fun of(status: Int?, charging: Boolean): DriveState = when {
            charging -> CHARGING
            status == 4 -> DRIVE
            status == 0 || status == 2 -> STANDBY
            else -> UNKNOWN
        }
    }
}

/** What the Navigate preview shows: exactly the frame the scooter display is being sent. */
data class NavPreview(
    /** Frame being sent (turn, distance, road as abbreviated for the TFT, arrival, remaining); null = no route. */
    val frame: NavFrame? = null,
    /** Google Maps' own instruction text (for the spoken description). */
    val instruction: String? = null,
    /** Maps' full road name for the maneuver ([MapsCue.road], not the TFT "Then …" hint); null when unknown. */
    val road: String? = null,
    /** The second back-to-back maneuver ([MapsCue.thenTurn]); null when none. */
    val thenTurn: Int? = null,
    /** Display state: SHOWING only when the TCU acknowledged a recent frame. */
    val display: NavDisplay = NavDisplay.UNAVAILABLE,
    /** "Scooter display" directions toggle. */
    val enabled: Boolean = true,
    /** Diagnostics display test is running (preview shows the test route). */
    val testRunning: Boolean = false,
    /** Last final 0x0303 write failure (GATT status), null when fine. */
    val error: Int? = null,
    /** Android has the notification listener running (directions can be read). */
    val listenerConnected: Boolean = false,
    /** When the last Maps update arrived (0 = never). */
    val updatedAt: Long = 0L
) {
    val hasRoute: Boolean get() = frame != null
}

/** One paired scooter for the picker. */
data class ScooterDevice(val name: String, val address: String)

/** Runtime permissions / special access the app asks for. */
enum class AppPermission(val label: String, val icon: String) {
    BLUETOOTH("Nearby devices", "bluetooth"),
    NOTIFICATION_ACCESS("Notification access", "notifications"),
    LOCATION("Location", "location_on"),
    PHONE("Phone & contacts", "call"),
    ANSWER_CALLS("Answer calls", "phone_in_talk"),
    NOTIFICATIONS("Notifications", "notifications")
}

/** GRANTED / PARTIAL (e.g. approximate location) / DENIED (ask) / BLOCKED (open app settings) / NOT_NEEDED (this Android version). */
enum class PermissionState { GRANTED, PARTIAL, DENIED, BLOCKED, NOT_NEEDED }

/** User toggles (More › Features, Navigate). */
enum class Feature(val label: String, val icon: String) {
    DIRECTIONS("Directions", "navigation"),
    MUSIC("Music", "music_note"),
    CALLS("Calls", "call"),
    HANDLEBAR("Handlebar buttons", "sports_motorsports"),
    AUTO_CONNECT("Auto-connect", "bluetooth_searching")
}

interface Host {
    // ------------------------------------------------------------------ platform

    /** The hosting activity (the only required member): context, permissions, startActivity, dialogs. */
    val activity: Activity

    /** Context for building views (the activity). */
    val context: Context get() = activity

    /** Shared history store (rides, charge sessions, readings). */
    val store: TelemetryStore get() = TelemetryStore.shared(activity)

    /** App version name, e.g. "1.0.0". */
    val versionName: String get() = ""

    // ------------------------------------------------------------------ shell

    /** Currently selected tab. */
    val currentTab: Destination get() = Destination.HOME

    /**
     * Switches to [destination] (fade-through) on behalf of the app; back then returns to the tab
     * the user was on. Idempotent: the current tab is a no-op.
     *
     * Navigation rule (W3, "tap explains, never surprise-jumps"): only an explicit affordance may
     * change tab or screen — a navigation-bar item, a row with a chevron, a button that names its
     * destination ("History", "Rides", "Maps") or a FAB. Tapping a value, tile, gauge, label or
     * state icon opens an explain sheet ([explain]) instead; the sheet's History button is the only
     * way from it to Insights. During a switch the outgoing screen takes no touches and the
     * incoming one is on top; a double tap acts once ([ClickGuard]).
     */
    fun selectTab(destination: Destination) {}

    /** Asks the shell to call render(now) on the visible screen soon (coalesced). */
    fun requestRender() {}

    /** Transient feedback above the navigation bar; [action] runs when its button is tapped. */
    fun showSnackbar(text: String, actionLabel: String? = null, action: (() -> Unit)? = null) { activity.toast(text) }

    /** Shows [content] in a modal bottom sheet; returns it so the caller can dismiss it. */
    fun showSheet(content: View, title: String? = null): BottomSheet? =
        BottomSheet(activity).apply { setTitle(title); setContent(content); show() }

    // ------------------------------------------------------------------ scooter link

    /** Link phase, liveness, device and last error. */
    val link: LinkStatus get() = LinkStatus()

    /** Shortcut for [link].phase. */
    val linkPhase: LinkPhase get() = link.phase

    /** Shortcut for [link].reconnecting. */
    val reconnecting: Boolean get() = link.reconnecting

    /** Latest reading of [metric] for [pack] (0 = total, 1, 2); last-known values after a disconnect. */
    fun reading(pack: Int, metric: Metric): Reading? = null

    /** All packs at once; derived from [reading] unless overridden. */
    val battery: BatterySnapshot get() = BatterySnapshot.of(::reading, System.currentTimeMillis(), linkPhase == LinkPhase.READY)

    /** Ignition, handle lock, ride mode, speed limit (last known; null before the first frame). */
    val vehicle: VehicleState? get() = null

    /** Telematics unit info (VIN, firmware) for About/Diagnostics. */
    val tcuInfo: TcuInfo? get() = null

    /** Range estimate (DESIGN F1); null without a charge reading. */
    val range: RangeEstimate? get() = null

    /** Drive / standby / charging for the cluster glyph; derived unless overridden. */
    val driveState: DriveState get() = DriveState.of(battery.total.status, ride.charging != null)

    // ------------------------------------------------------------------ rides and GPS

    /** Live ride tracker state (GPS status, riding, distance, speed, charging session). */
    val ride: RideState get() = RideLive.state

    /** Live GPS speed in km/h while there is a fix, else null. */
    val speedKmh: Double? get() = ride.speedKmh.takeIf { ride.gps == GpsStatus.FIX }

    /** Newest finished ride (for Home's last-ride row), null when none or not loaded. */
    val lastRide: Ride? get() = null

    /** Opens the Rides tab on ride [id]'s detail; back from that detail returns to the tab it was opened from. */
    fun openRide(id: Long) {}

    /**
     * The screen left the sub-page the app opened on it ([openRide]'s detail) by itself — its own
     * top-bar arrow, a delete, or another ride opened from the list — so back no longer jumps past
     * the tab: it now behaves as on the tab's top level.
     */
    fun subPageClosed() {}

    // ------------------------------------------------------------------ navigation preview

    /** What the scooter display is being sent and whether it acknowledged it. */
    val navPreview: NavPreview get() = NavPreview()

    /** Opens Google Maps (the rider starts navigation there). */
    fun openMaps() {}

    // ------------------------------------------------------------------ phone (music, calls)

    /** Media/calls/counts state and listeners (process-wide object). */
    val phone: PhoneBridge get() = PhoneBridge

    /** Music line on the scooter display (SHOWING = acknowledged). */
    val musicFeed: ClusterFeed get() = ClusterFeed.UNAVAILABLE

    /** Caller line on the scooter display. */
    val callFeed: ClusterFeed get() = ClusterFeed.UNAVAILABLE

    /** Play/pause/next/previous/volume; failures surface as a snackbar. */
    fun media(action: MediaAction) { PhoneBridge.mediaAction(action) }

    /** Accept/reject the current call; failures surface as a snackbar. */
    fun call(action: CallAction) { PhoneBridge.callAction(action) }

    /**
     * Now playing as the app shows it: the phone's media session ([PhoneBridge.media]); in demo mode
     * the demo track. Read this (not [phone]) for what Home shows. (Added by the shell, 2.2.)
     */
    val mediaState: MediaState? get() = phone.media

    /** The phone call as the app shows it ([PhoneBridge.call]); in demo mode the demo call. Read this for Home's call banner. */
    val callState: CallState get() = phone.call

    // ------------------------------------------------------------------ scooter picker

    /** Paired scooters (empty without Bluetooth access or with Bluetooth off). */
    val scooters: List<ScooterDevice> get() = emptyList()

    /** Address of the chosen scooter (saved or picked), null when none. */
    val selectedScooter: String? get() = null

    /** Bluetooth adapter is on. */
    val bluetoothEnabled: Boolean get() = false

    /** Picks the scooter at [address] for the next connect. */
    fun selectScooter(address: String) {}

    /** Connects to [address] or the selected scooter (asks for Bluetooth access when needed). */
    fun connect(address: String? = null) {}

    /** Cancels a connection attempt or a reconnect loop. */
    fun cancelConnect() {}

    /** Disconnects the scooter. */
    fun disconnect() {}

    /** READY only: asks the scooter for the battery stream again and re-reads vehicle state (no reconnect, a ride keeps going). */
    fun refreshLink() {}

    /** Connect when idle, cancel while connecting, disconnect when connected (top-bar chip action). */
    fun toggleConnection() {
        when (linkPhase) { LinkPhase.IDLE -> connect(); LinkPhase.CONNECTING -> cancelConnect(); LinkPhase.READY -> disconnect() }
    }

    /** Forgets the saved scooter (no auto-connect until one is picked again). */
    fun forgetScooter() {}

    /** Re-reads the paired list (after pairing or toggling Bluetooth). */
    fun refreshScooters() {}

    /** Opens Android Bluetooth settings (to pair a scooter). */
    fun openBluetoothSettings() {}

    /**
     * Asks Android to turn Bluetooth on (system dialog, ACTION_REQUEST_ENABLE; needs Nearby devices
     * on 12+), falling back to Bluetooth settings. Callers watch [bluetoothEnabled] / the adapter
     * state for the outcome. (Added by onboarding.)
     */
    fun requestBluetoothOn() {
        try { activity.startActivity(android.content.Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
        catch (_: Exception) { openBluetoothSettings() }
    }

    // ------------------------------------------------------------------ saved scooters (DESIGN Addendum C; added by the shell)

    /** Scooters added in Ruta (SecureStore), with nickname, BLE name and whether a PIN is stored. */
    val savedScooters: List<SavedScooter> get() = Scooters.all(context)

    /** The scooter Connect and auto-connect use; null before the first one is added. */
    val activeScooter: SavedScooter? get() = Scooters.active(context)

    /** What to call the active scooter: its nickname, or "My scooter". Never a hardcoded model name. */
    val scooterName: String get() = activeScooter?.nickname?.takeIf { it.isNotBlank() } ?: "My scooter"

    /** Opens the add-scooter flow (OnboardingFlow with addOnly = true) full screen, over the navigation bar. */
    fun addScooter() {}

    /** Renames saved scooter [mac]. */
    fun renameScooter(mac: String, nickname: String) { Scooters.rename(context, mac, nickname) }

    /** Stores a new Bluetooth PIN for saved scooter [mac] (encrypted; never logged) and confirms it ("PIN saved" / "Couldn't save the PIN"). */
    fun changeScooterPin(mac: String, pin: String) {
        val scooter = Scooters.all(context).firstOrNull { it.mac.equals(mac, ignoreCase = true) } ?: return
        showSnackbar(if (Scooters.save(context, scooter.copy(hasPin = true), pin)) "PIN saved" else "Couldn't save the PIN")
    }

    /** Forgets saved scooter [mac] (PIN and settings); disconnects it first when it is the linked one. */
    fun forgetScooter(mac: String) { Scooters.forget(context, mac) }

    // ------------------------------------------------------------------ permissions

    /** Grant state of [permission]. */
    fun permission(permission: AppPermission): PermissionState = PermissionState.DENIED

    /** Asks for [permission] (dialog, or the right Settings page when blocked / special access). */
    fun requestPermission(permission: AppPermission) {}

    /** Opens this app's App info page. */
    fun openAppSettings() {}

    /** Opens system Location settings (location services off). */
    fun openLocationSettings() {}

    // ------------------------------------------------------------------ settings

    /** Whether [feature] is on. */
    fun isEnabled(feature: Feature): Boolean = false

    /** Turns [feature] on/off (persisted, applied to the link immediately). */
    fun setEnabled(feature: Feature, on: Boolean) {}

    /** Last handlebar action and when (elapsedRealtime), for More › Handlebar. */
    val lastHandlebar: Pair<String, Long>? get() = null

    // ------------------------------------------------------------------ demo mode (F-DEMO; added by the shell, 2.2)

    /**
     * Demo mode is on ([Demo]): link, battery, vehicle, range, ride, navigation, music and call above
     * are simulated, [store] is the separate demo history, and nothing reaches a scooter, the rider's
     * history or the notification shade.
     */
    val demoActive: Boolean get() = Demo.active

    /**
     * Starts demo mode in [scenario] or switches to it; null ends demo mode (back to the real scooter
     * state, reconnecting as on launch). Refused, with a snackbar, while the scooter is linked.
     */
    fun setDemo(scenario: DemoScenario?) { Demo.request(scenario) }

    /** Opens the demo sheet (switch scenario, Exit demo): what the top-bar "Demo" chip does. */
    fun showDemoSheet() {}

    // ------------------------------------------------------------------ diagnostics and data

    /** Recent log lines, oldest first. */
    val logLines: List<String> get() = emptyList()

    /** Called (main thread) with each new log line; remove it in onHide/dispose. */
    fun addLogListener(listener: (String) -> Unit) {}

    fun removeLogListener(listener: (String) -> Unit) {}

    /** Appends a line to the diagnostics log (logcat tag Ruta). */
    fun log(line: String) { Log.i("Ruta", line) }

    /** Copies the log (with a device header) to the clipboard. */
    fun copyLog() {}

    /** Shares the persistent log files (share sheet). */
    fun shareLogs() {}

    /** One-shot request of all scooter stats tags, replies logged (needs READY). */
    fun probeScooter() {}

    /** Latest raw stats reply per tag (ScooterConnection.rawStats()), for More › Diagnostics; empty when none. (Added by the More screen.) */
    fun rawStats(): Map<Int, ByteArray> = emptyMap()

    /** Diagnostics display test route on the TFT for 40 s (needs READY). */
    fun startDisplayTest() {}

    /** Sends road names of 8–12 characters to measure the TFT's visible width (needs READY). */
    fun startRoadNameTest() {}

    /** True while the display test or road-name test runs. */
    val displayTestRunning: Boolean get() = false

    /** Forgets the latched battery unit scales so they are detected again. */
    fun resetUnits() {}

    /** Exports battery history, rides and charging sessions as one CSV (file picker). */
    fun exportCsv() {}

    // ------------------------------------------------------------------ Chetooth

    /** Opens More › Chetak setup (teach the battery % and range fields; see ChetakSetup.kt). */
    fun openChetakSetup() { ChetakSetup(this).show() }
}
