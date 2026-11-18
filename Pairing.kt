package dev.codesurgen.ruta

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import java.util.concurrent.CopyOnWriteArrayList

/*
 * Scooter pairing (DESIGN Addendum C › Pairing logic). ScooterConnection bonds an unpaired scooter
 * with createBond() before service discovery; while it bonds, PinResponder
 * answers Android's pairing request with the PIN saved for that scooter so pairing is silent.
 * Outcomes reach the UI through PairingEvents. ScooterScanner lists nearby Hero TCUs for the Find
 * scooter step. Main thread only. PINs are never logged.
 */

/** What happened while pairing (published on [PairingEvents] with the scooter's MAC). */
sealed class PairingEvent {
    /** Android bonded with the scooter; connecting continues. */
    object Bonded : PairingEvent() { override fun toString() = "Bonded" }

    /**
     * Pairing failed for [failure] (PairingRules.linkFailure / bondOutcome). Only
     * [PairingFailure.WRONG_PIN] means the scooter refused a PIN it actually received.
     */
    data class BondFailed(val failure: PairingFailure) : PairingEvent()

    /**
     * Android shows its own pairing dialog anyway (setPin refused, a passkey variant, or an OEM skin
     * such as HyperOS): offer [pin] with a Copy action so the rider can paste it there.
     */
    data class SystemDialog(val pin: String) : PairingEvent() { override fun toString() = "SystemDialog" }

    /** Pairing could not be attempted or answered; [reason] is short and plain. */
    data class Failed(val reason: String) : PairingEvent()
}

/**
 * Process-wide pairing outcomes for the UI (onboarding, More › Scooters, the shell's snackbar).
 * Listeners run on the main thread with the scooter MAC ("AA:BB:…") and the event.
 */
object PairingEvents {
    private val listeners = CopyOnWriteArrayList<(String, PairingEvent) -> Unit>()

    fun addListener(l: (mac: String, event: PairingEvent) -> Unit) { if (l !in listeners) listeners += l }
    fun removeListener(l: (mac: String, event: PairingEvent) -> Unit) { listeners -= l }

    fun emit(mac: String, event: PairingEvent) {
        val key = PairingRules.normalizeMac(mac) ?: mac
        Ui.run { for (l in listeners) runCatching { l(key, event) } }
    }
}

/**
 * Answers ACTION_PAIRING_REQUEST for one scooter with its saved PIN: a dynamically registered,
 * high-priority receiver (exported on 33+: it is a protected system broadcast) calls
 * device.setPin(pin) and, only when that succeeded, abortBroadcast() so the system dialog never
 * shows. Otherwise it reports [PairingEvent.SystemDialog] so the UI can offer the PIN to copy.
 * Requests for other devices are left alone. Logs the pairing variant, never the PIN.
 *
 * Arming again for the same scooter keeps the earlier callbacks (the shell may arm before
 * ScooterConnection's bond step does). Every event, including the bond outcomes ScooterConnection
 * passes to [report], goes to all armed callbacks and to [PairingEvents]. Disarms itself after
 * [SAFETY_MS]; the bond step disarms it on every exit path.
 */
object PinResponder {
    private const val SAFETY_MS = 120_000L
    private var app: Context? = null
    private var receiver: BroadcastReceiver? = null
    private var mac: String? = null
    private var pin: String? = null
    private val callbacks = ArrayList<(PairingEvent) -> Unit>()
    private var safety: Runnable? = null
    private var requestSeen = false
    /** What the answerer saw per scooter MAC in this attempt (reset when a new attempt arms, cleared on [disarm]). */
    private val deliveries = HashMap<String, PinDelivery>()

    /** True while a PIN is armed. */
    val armed: Boolean get() = receiver != null

    /** True while a PIN is armed for [mac]. */
    fun isArmed(mac: String): Boolean = receiver != null && PairingRules.normalizeMac(mac) == this.mac

    /**
     * True once our receiver saw Android's pairing request for the armed [mac]. Still false while
     * bonding means a higher-priority (OEM) component took the request and its own dialog asks.
     */
    fun requestSeen(mac: String): Boolean = isArmed(mac) && requestSeen

    /** What this attempt's answerer saw for [mac]; nothing once disarmed (a later attempt never reads an old one). */
    fun delivery(mac: String): PinDelivery = deliveries[PairingRules.normalizeMac(mac) ?: mac] ?: PinDelivery()

    /** True when Android accepted our PIN for [mac] in this attempt: the scooter really received it. */
    fun pinDelivered(mac: String): Boolean = delivery(mac).delivered

    fun arm(context: Context, mac: String, pin: String, onEvent: (PairingEvent) -> Unit) {
        Ui.assertMainThread("PinResponder.arm")
        val key = PairingRules.normalizeMac(mac)
        if (key == null || !PairingRules.isValidPin(pin)) {
            onEvent(PairingEvent.Failed("The saved PIN is not usable"))
            return
        }
        if (receiver != null && key == this.mac) {
            this.pin = pin
            if (callbacks.none { it === onEvent }) callbacks += onEvent
            restartSafety()
            return
        }
        disarm()
        val appContext = context.applicationContext
        val answer = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = handle(this, intent)
        }
        val filter = IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST).apply { priority = IntentFilter.SYSTEM_HIGH_PRIORITY - 1 }
        try {
            if (Build.VERSION.SDK_INT >= 33) appContext.registerReceiver(answer, filter, Context.RECEIVER_EXPORTED)
            else appContext.registerReceiver(answer, filter)
        } catch (e: Exception) {
            DiagLog.append("Pairing: could not listen for the pairing request (${e.javaClass.simpleName})")
            onEvent(PairingEvent.SystemDialog(pin))
            PairingEvents.emit(key, PairingEvent.SystemDialog(pin))
            return
        }
        app = appContext; receiver = answer; this.mac = key; this.pin = pin; requestSeen = false
        deliveries.remove(key) // A new attempt: nothing requested or delivered yet.
        callbacks += onEvent
        restartSafety()
    }

    fun disarm() {
        safety?.let(Ui::cancel); safety = null
        val registered = receiver
        receiver = null
        if (registered != null) runCatching { app?.unregisterReceiver(registered) }
        mac = null; pin = null
        callbacks.clear()
        deliveries.clear()
    }

    /** Delivers a bond outcome for [mac] to the armed callbacks (when armed for it) and to [PairingEvents]. */
    fun report(mac: String, event: PairingEvent) {
        if (isArmed(mac)) for (callback in callbacks.toList()) runCatching { callback(event) }
        PairingEvents.emit(mac, event)
    }

    private fun restartSafety() {
        safety?.let(Ui::cancel)
        safety = Ui.postDelayed(SAFETY_MS) { safety = null; disarm() }
    }

    @Suppress("MissingPermission") // BLUETOOTH_CONNECT is granted before any connection starts.
    private fun handle(receiver: BroadcastReceiver, intent: Intent) {
        if (receiver !== this.receiver) return
        val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        val target = mac ?: return
        if (device == null || !device.address.equals(target, ignoreCase = true)) return
        requestSeen = true
        val variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.ERROR)
        val earlier = deliveries[target]?.delivered == true // Delivered once in this attempt stays delivered.
        deliveries[target] = PinDelivery(requested = true, variant = variant, delivered = earlier)
        val secret = pin ?: return
        DiagLog.append("Pairing request (${PairingRules.variantName(variant)})")
        if (PairingRules.answerable(variant)) {
            val accepted = try { device.setPin(secret.toByteArray(Charsets.US_ASCII)) } catch (_: Exception) { false }
            deliveries[target] = PinDelivery(requested = true, variant = variant, delivered = accepted || earlier)
            if (accepted) {
                if (receiver.isOrderedBroadcast) runCatching { receiver.abortBroadcast() }
                DiagLog.append("Pairing: saved PIN supplied")
                return
            }
            DiagLog.append("Pairing: Android refused the saved PIN; its dialog will ask")
            report(target, PairingEvent.SystemDialog(secret))
            return
        }
        if (PairingRules.needsPin(variant)) {
            DiagLog.append("Pairing: this request needs the PIN typed in Android's dialog")
            report(target, PairingEvent.SystemDialog(secret))
        } else {
            DiagLog.append("Pairing: Android asks to confirm; no PIN needed")
        }
    }
}

/** Why a scan cannot (fully) run; the Find step shows the matching state. */
enum class ScanLimit { NONE, BLUETOOTH_OFF, NO_PERMISSION, LOCATION_OFF, UNSUPPORTED, FAILED }

/**
 * Finds nearby Hero scooters: paired ones first (from the bonded list, shown even when they are not
 * advertising), then BLE advertisers named "HMC_…" or advertising the TCU info service. Signal
 * changes are throttled ([ScanAggregator]); the scan stops by itself after 30 s and on [stop].
 * Without scan access (BLUETOOTH_SCAN, or location on Android 11) it lists paired scooters only and
 * reports [limit]. [onResult] and [onStateChange] run on the main thread.
 */
@Suppress("MissingPermission") // Every Bluetooth call is guarded by the permission checks below.
class ScooterScanner(context: Context, private val onResult: (List<ScanCandidate>) -> Unit) {
    private val app = context.applicationContext
    private val adapter: BluetoothAdapter? = app.getSystemService(BluetoothManager::class.java)?.adapter
    private val found = ScanAggregator()
    private var stopTask: Runnable? = null
    private var tickTask: Runnable? = null
    private var startTask: Runnable? = null
    private var callback: ScanCallback? = null
    private var lastStartAt = 0L

    /** True while the radio is scanning. */
    var scanning = false; private set
    /** What stopped the last scan from running fully. */
    var limit = ScanLimit.NONE; private set
    /** Called when [scanning] or [limit] change. */
    var onStateChange: (() -> Unit)? = null

    /** Current list (paired first, then in the order found). */
    val results: List<ScanCandidate> get() = found.snapshot()

    fun start() {
        Ui.assertMainThread("ScooterScanner.start")
        stop(notify = false)
        found.clear()
        limit = ScanLimit.NONE
        val radio = adapter
        when {
            radio == null -> limit = ScanLimit.UNSUPPORTED
            !connectAllowed() -> limit = ScanLimit.NO_PERMISSION
            !radio.isEnabled -> limit = ScanLimit.BLUETOOTH_OFF
        }
        if (limit == ScanLimit.NONE) addBonded()
        if (limit == ScanLimit.NONE) limit = when {
            !scanAllowed() -> ScanLimit.NO_PERMISSION
            Build.VERSION.SDK_INT < 31 && !locationOn() -> ScanLimit.LOCATION_OFF
            radio?.bluetoothLeScanner == null -> ScanLimit.UNSUPPORTED
            else -> ScanLimit.NONE
        }
        onResult(found.snapshot())
        if (limit != ScanLimit.NONE) { onStateChange?.invoke(); return }
        // Android allows 5 scan starts per 30 s; space quick restarts ("Scan again") out.
        val wait = (lastStartAt + MIN_RESTART_MS - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        scanning = true
        onStateChange?.invoke()
        startTask = Ui.postDelayed(wait) { startTask = null; beginScan() }
    }

    fun stop() = stop(notify = true)

    private fun stop(notify: Boolean) {
        startTask?.let(Ui::cancel); startTask = null
        stopTask?.let(Ui::cancel); stopTask = null
        tickTask?.let(Ui::cancel); tickTask = null
        callback?.let { cb -> runCatching { adapter?.bluetoothLeScanner?.stopScan(cb) } }
        callback = null
        val was = scanning
        scanning = false
        if (notify && was) onStateChange?.invoke()
    }

    private fun beginScan() {
        val scanner = adapter?.bluetoothLeScanner
        if (!scanning || scanner == null) { finish(ScanLimit.UNSUPPORTED); return }
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = Ui.run { offer(result) }
            override fun onBatchScanResults(results: MutableList<ScanResult>) = Ui.run { results.forEach(::offer) }
            override fun onScanFailed(errorCode: Int) = Ui.run {
                DiagLog.append("Scooter scan failed (code $errorCode)")
                finish(ScanLimit.FAILED)
            }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(null, settings, cb)
        } catch (e: Exception) {
            DiagLog.append("Scooter scan could not start (${e.javaClass.simpleName})")
            finish(if (e is SecurityException) ScanLimit.NO_PERMISSION else ScanLimit.FAILED)
            return
        }
        callback = cb
        lastStartAt = SystemClock.elapsedRealtime()
        stopTask = Ui.postDelayed(PairingRules.SCAN_WINDOW_MS) { stopTask = null; finish(ScanLimit.NONE) }
        scheduleTick()
    }

    private fun scheduleTick() {
        tickTask = Ui.postDelayed(PairingRules.RSSI_THROTTLE_MS) {
            tickTask = null
            if (!scanning) return@postDelayed
            if (found.due(SystemClock.elapsedRealtime())) onResult(found.snapshot())
            scheduleTick()
        }
    }

    private fun finish(reason: ScanLimit) {
        if (callback == null && !scanning) return
        stop(notify = false)
        limit = reason
        onResult(found.snapshot())
        onStateChange?.invoke()
    }

    private fun offer(result: ScanResult) {
        if (callback == null) return
        val device = result.device ?: return
        val record = result.scanRecord
        val name = record?.deviceName ?: runCatching { device.name }.getOrNull()
        val uuids = record?.serviceUuids.orEmpty().map { it.uuid.toString() }
        if (!PairingRules.isScooter(name, uuids)) return
        val bonded = runCatching { device.bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(false)
        val candidate = ScanCandidate(device.address, name?.trim().orEmpty(), result.rssi, bonded)
        if (found.offer(candidate, SystemClock.elapsedRealtime())) onResult(found.snapshot())
    }

    private fun addBonded() {
        val bonded = try { adapter?.bondedDevices.orEmpty() } catch (_: Exception) { emptySet<BluetoothDevice>() }
        val now = SystemClock.elapsedRealtime()
        for (device in bonded) {
            val name = runCatching { device.name }.getOrNull()
            if (!PairingRules.isScooter(name)) continue
            found.offer(ScanCandidate(device.address, name?.trim().orEmpty(), ScanCandidate.RSSI_UNKNOWN, bonded = true), now)
        }
    }

    private fun granted(permission: String) = app.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun connectAllowed() = Build.VERSION.SDK_INT < 31 || granted(Manifest.permission.BLUETOOTH_CONNECT)

    private fun scanAllowed() =
        if (Build.VERSION.SDK_INT >= 31) granted(Manifest.permission.BLUETOOTH_SCAN)
        else granted(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun locationOn() = runCatching { app.getSystemService(LocationManager::class.java)?.isLocationEnabled ?: true }.getOrDefault(true)

    private companion object {
        const val MIN_RESTART_MS = 6_500L
    }
}
