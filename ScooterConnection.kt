package dev.codesurgen.ruta

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.BatteryManager
import android.os.SystemClock
import android.telephony.TelephonyManager
import java.time.LocalDateTime
import android.bluetooth.BluetoothAdapter
import java.util.ArrayDeque
import java.util.UUID

enum class LinkPhase { IDLE, CONNECTING, READY }

/**
 * What the UI may claim about the cluster's music (0x0304/0x0305) or call (0x0301/0x0302) lines.
 * A queued write proves nothing: SHOWING needs the TCU's acknowledgement of the latest completed
 * write; REJECTED means that write finally failed. IDLE = nothing to show (no music session, no
 * call, or music hidden during a call); OFF = turned off in Ruta.
 */
enum class ClusterFeed { UNAVAILABLE, OFF, IDLE, SENDING, SHOWING, REJECTED }

/**
 * Direct GATT client for the scooter's telematics unit (TCU); sequence in docs/PROTOCOL.md:
 * connect (LE) → bond if needed → ~1 s → discover → MTU → stage A (TCU heartbeat notify, TCU
 * info read, first phone heartbeat) → ~1 s → stage B (vehicle-state/ESYNC notify, OS info,
 * vehicle read, stats notify, BMS request). Heartbeats are answered with value + 1; no TCU
 * heartbeat for 30 s ends the link. Only plaintext characteristics are used.
 * All state is confined to the main thread; GATT callbacks are posted to it.
 */
@SuppressLint("MissingPermission") // Entry points check BLUETOOTH_CONNECT first.
class ScooterConnection(
    private val activity: Activity,
    private val status: (String) -> Unit,
    private val log: (String) -> Unit,
    private val received: (Reading) -> Unit,
    private val vehicle: (VehicleState) -> Unit,
    private val info: (TcuInfo) -> Unit,
    private val connected: () -> Unit,
    /**
     * The link went down. [linkLost] true: it dropped by itself and [reconnecting] retries the same
     * scooter, so keep ride tracking and the service up. False: it is over (Disconnect, a failed
     * connect, or reconnection gave up).
     */
    private val ended: (linkLost: Boolean) -> Unit
) {
    private val app: Context = activity.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val manager = app.getSystemService(BluetoothManager::class.java)
    private val prefs = app.getSharedPreferences("companion", Context.MODE_PRIVATE)
    private var gatt: BluetoothGatt? = null
    private var device: BluetoothDevice? = null
    private var generation = 0
    private var bondReceiver: BroadcastReceiver? = null
    /** PinResponder is armed for this attempt's bond (only on the not-bonded path). */
    private var pairingArmed = false
    /** This attempt's bond reached BONDING (a later BOND_NONE is the scooter refusing the PIN). */
    private var sawBonding = false
    /** The link dropped mid-bond; waiting [BOND_RESULT_WAIT_MS] for the bond broadcast before failing. */
    private var awaitingBondResult = false
    /** GATT code of that mid-bond disconnect (-1 = none), so the bond receiver can tell a lost link from a refused PIN. */
    private var pendingDropCode = -1
    private var adapterReceiver: BroadcastReceiver? = null
    private var decoder = BmsDecoder()

    // Per-connection progress flags (reset in disconnect).
    private var linkUp = false
    private var retriedConnect = false
    private var discoveryStarted = false
    private var mtuRequested = false
    private var mtuTries = 0
    private var stageA = false
    private var stageB = false
    private var heartbeat = 0
    private var lastTcuHeartbeat = 0L
    private var bmsPeriodIndex = 0
    private var lastBmsAt = 0L
    private var probedStats = false
    private var lastRawHex = ""
    private var lastRawLogAt = 0L

    // --- Chetooth: Chetak mode (no Hero TCU services; read-only, learned fields, see ChetakLearn.kt) ---
    /** This link has no Hero TCU services: every value is read and listened to, nothing is written. */
    private var chetakMode = false
    private val chetakReadable = ArrayList<BluetoothGattCharacteristic>()
    private val chetakLogged = HashMap<String, String>()
    private val chetakLogAt = HashMap<String, Long>()
    private var lastSoc: Double? = null
    private var lastSocEmitAt = 0L
    /** True while the link runs in Chetak mode. */
    val chetak: Boolean get() = chetakMode

    var phase = LinkPhase.IDLE; private set
    /** Forward Google Maps turn-by-turn to the cluster (0x0303). Mirrored into [NavBridge.forwarding] (location feed). */
    var navigationEnabled = true
        set(value) { field = value; NavBridge.forwarding = value; if (!value) stopNavigation() else pushNavigation() }
    var navigationSending = false; private set
    /** 0x0303 outcomes: last TCU acknowledgement (elapsedRealtime, 0 = none) and last final failure (GATT status, -1 = could not start). */
    var navigationAckedAt = 0L; private set
    var navigationError: Int? = null; private set
    /** READY without a 0x0303 characteristic: turn-by-turn can never be shown. */
    var navigationMissing = false; private set
    private var lastNavFrame: NavFrame? = null
    private var lastNavSentAt = 0L
    private var lastNavLogKey: List<Any?>? = null
    /** The last 0x0303 frame queued for the TFT (Maps, display test or road-name width test); null after the exit frame. */
    val sentNavFrame: NavFrame? get() = lastNavFrame
    var deviceName: String? = null; private set

    /**
     * Range read from the scooter. Always null for now: no range field is decoded yet (the F2
     * auto-probe logs the raw stats replies so one can be found); the UI estimates range until then.
     */
    var decodedRangeKm: Double? = null; private set
    /** Latest raw service-four stats reply per tag (incl. tag 1 BMS), for Diagnostics. */
    private val rawStatsByTag = HashMap<Int, ByteArray>()
    private val rawStatsAt = HashMap<Int, Long>()
    private var rawStatsMac: String? = null
    private var autoProbed = false

    /**
     * True after a READY link dropped without the user asking (GATT drop, heartbeat loss, Bluetooth
     * off) while the same scooter is retried for [RECONNECT_WINDOW_MS]: a ride in progress continues
     * if the link returns within [RideMath.LINK_LOST_MS]. [phase] is CONNECTING during an attempt
     * and IDLE between attempts. [disconnect] or [connect] cancels it.
     */
    var reconnecting = false; private set
    private var reconnectMac: String? = null
    private var reconnectUntil = 0L
    private var reconnectAttempts = 0
    /** Separate from [main], whose callbacks every disconnect clears. */
    private val reconnects = Handler(Looper.getMainLooper())

    // --- Phone features on the cluster (0x0301 call/counts, 0x0302 caller, 0x0304/0x0305 music, 0x0306 handlebar) ---

    /** Song, play state and volume on the cluster. Persisted; off sends one blank music frame. */
    var musicEnabled: Boolean
        get() = prefs.getBoolean("music_forward", true)
        set(value) {
            if (value == musicEnabled) return
            prefs.edit().putBoolean("music_forward", value).apply()
            log(if (value) "Music on the scooter display: on" else "Music on the scooter display: off")
            pushMusic()
        }
    /**
     * Call state, caller and missed-call count on the cluster (0x0301/0x0302). Persisted ("calls_forward",
     * shared with More › Features). Off sends no call state and no caller; message counts still go.
     */
    var callsEnabled: Boolean
        get() = prefs.getBoolean("calls_forward", true)
        set(value) {
            if (value == callsEnabled) return
            prefs.edit().putBoolean("calls_forward", value).apply()
            log(if (value) "Calls on the scooter display: on" else "Calls on the scooter display: off")
            if (phase == LinkPhase.READY) sendPhoneStatus()
        }
    /** Act on handlebar buttons (0x0306). Off still logs every frame. Persisted. */
    var handlebarControlsEnabled: Boolean
        get() = prefs.getBoolean("handlebar_controls", true)
        set(value) { prefs.edit().putBoolean("handlebar_controls", value).apply() }
    /** Last handlebar action performed ("next track", "accept call (not done)", …) and when (elapsedRealtime). */
    var lastHandlebar: String? = null; private set
    var lastHandlebarAt = 0L; private set
    val musicOnDisplay: Boolean get() = musicFeed() == ClusterFeed.SHOWING
    val callOnDisplay: Boolean get() = callFeed() == ClusterFeed.SHOWING

    private val phoneListener: () -> Unit = { onPhoneChanged() }
    /** Every Maps update and every counted-down distance change is pushed at once (2.1 F4). */
    private val navListener: () -> Unit = { if (phase == LinkPhase.READY) pushNavigation() }
    private val controlDebouncer = HardControlDebouncer()
    /** Final write outcomes per characteristic (elapsedRealtime), for the feeds above. */
    private val ackedAt = HashMap<UUID, Long>()
    private val failedAt = HashMap<UUID, Long>()
    private var lastStatusKey: List<Any?>? = null
    private var lastCallLogged = TcuCallState.NONE
    private var lastMusicInfo: ByteArray? = null
    private var lastMusicSentAt = 0L
    private var lastMusicLogged: MusicInfo? = null
    private var lastMeta: ByteArray? = MusicMeta.CLEAR.encode()
    /** "Show who messaged" (More › Alerts): the sender alert on the music line, see [MessageAlerter]. */
    private val messageAlerter = MessageAlerter()
    private val messageListener: (MessageNotice) -> Unit = { onMessage(it) }
    /** After a final 0x0304/0x0305 failure music pauses briefly instead of retrying every second. */
    private var musicRetryAt = 0L

    init { NavBridge.attach(app) }

    fun permissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyArray()

    private fun permitted() = permissions().all { activity.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** Bonded devices, likely scooters (Chetak, Bajaj, HMC_…) first, as name to address. */
    fun bondedScooters(): List<Pair<String, String>> {
        if (!permitted()) return emptyList()
        val adapter = manager?.adapter ?: return emptyList()
        return runCatching {
            adapter.bondedDevices.orEmpty()
                .sortedByDescending { PairingRules.likelyScooter(it.name) }
                .map { (it.name ?: it.address) to it.address }
        }.getOrDefault(emptyList())
    }

    fun connect(mac: String) {
        if (phase != LinkPhase.IDLE) return
        if (!Regex("(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}").matches(mac)) { status("Enter a valid scooter Bluetooth address."); return }
        if (!permitted()) { activity.requestPermissions(permissions(), 41); return }
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) { status("Turn on Bluetooth, then connect again."); return }
        stopReconnecting()
        teardown()
        open(adapter.getRemoteDevice(mac.uppercase()), mac)
    }

    /** One connection attempt to [target]; [connect] and reconnection both start here. */
    private fun open(target: BluetoothDevice, mac: String) {
        device = target; deviceName = runCatching { target.name }.getOrNull()
        ChetakData.attach(target.address)
        if (rawStatsMac != target.address) { rawStatsByTag.clear(); rawStatsAt.clear(); rawStatsMac = target.address }
        decoder = BmsDecoder(prefs.getInt(scaleKey("pct"), 0).takeIf { it > 0 }, prefs.getInt(scaleKey("temp"), 0).takeIf { it > 0 })
        phase = LinkPhase.CONNECTING
        // Phone state is tracked from connect() to disconnect(), so it is current once READY.
        PhoneBridge.start(app, this)
        status("Connecting to ${deviceName ?: mac}…")
        log("— Connecting to ${deviceName ?: "scooter"} ($mac), bond state ${bondName(target.bondState)}, Android ${Build.VERSION.SDK_INT}")
        armTimeout(35_000, NO_RESPONSE)
        watchAdapter()
        openGatt(target)
    }

    /** Forgets the learned BMS units for this scooter so they are detected again. */
    fun resetCalibration(mac: String) {
        val address = mac.uppercase()
        prefs.edit().remove("scale_pct_$address").remove("scale_temp_$address").apply()
        if (device?.address == address) decoder = BmsDecoder()
        log("Unit detection reset for $address")
    }

    private fun watchAdapter() {
        val attempt = generation
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (attempt == generation && (state == BluetoothAdapter.STATE_TURNING_OFF || state == BluetoothAdapter.STATE_OFF)) fail("Bluetooth was turned off.")
            }
        }
        adapterReceiver = receiver
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED) else app.registerReceiver(receiver, filter)
    }

    /** Re-sends the current BMS request and re-reads vehicle state. */
    fun refresh() {
        if (phase != LinkPhase.READY) return
        if (chetakMode) { chetakReadable.forEach { readChar(it, tries = 1) }; return }
        requestBms(bmsPeriodIndex, schedule = false)
        read(Tcu.VEHICLE_STATE, critical = false)
    }

    /** User-initiated (Disconnect, notification, app closing): ends the link and any reconnection. */
    fun disconnect(showStatus: Boolean = true) {
        val wasActive = teardown() || reconnecting
        stopReconnecting()
        if (wasActive) ended(false)
        if (showStatus) status("Disconnected · saved history is available")
    }

    /** Closes the current attempt or link without reporting it; returns whether one was open. */
    private fun teardown(): Boolean {
        generation++
        main.removeCallbacksAndMessages(null)
        queue.clear(); running = null; watchdog = null; retryPending = false
        bondReceiver?.let { runCatching { app.unregisterReceiver(it) } }; bondReceiver = null; disarmPairing()
        adapterReceiver?.let { runCatching { app.unregisterReceiver(it) } }; adapterReceiver = null
        resetAttempt(); retriedConnect = false; bmsPeriodIndex = 0; lastBmsAt = 0
        probedStats = false; probing = false; lastRawHex = ""; lastRawLogAt = 0; autoProbed = false
        ChetakData.linked = false; lastSoc = null; lastSocEmitAt = 0; chetakLogged.clear(); chetakLogAt.clear()
        navigationSending = false; lastNavFrame = null; lastNavSentAt = 0; lastNavLogKey = null
        navigationAckedAt = 0; navigationError = null; navigationMissing = false
        testKind = null; testStartedAt = 0
        resetPhoneFeeds()
        PhoneBridge.removeListener(phoneListener)
        PhoneBridge.removeMessageListener(messageListener)
        NavBridge.removeListener(navListener)
        PhoneBridge.stop(this)
        val wasActive = phase != LinkPhase.IDLE
        phase = LinkPhase.IDLE
        gatt?.let { runCatching { it.disconnect() }; runCatching { it.close() } }
        gatt = null
        return wasActive
    }

    fun close() = disconnect(false)

    /**
     * Ends the attempt or link with [message]. A READY link that drops starts [reconnecting]; a failed
     * reconnection attempt schedules the next one until the window closes. The status callback also
     * lands in the diagnostics log, so the message is not logged twice.
     */
    private fun fail(message: String) {
        val now = SystemClock.elapsedRealtime()
        if (phase == LinkPhase.READY && !reconnecting) {
            reconnecting = true; reconnectMac = device?.address
            reconnectUntil = now + RECONNECT_WINDOW_MS; reconnectAttempts = 0
        }
        val wasActive = teardown()
        if (reconnecting && reconnectMac != null && now < reconnectUntil) {
            if (wasActive) ended(true)
            log(message)
            status("Scooter link lost · reconnecting…")
            scheduleReconnect()
            return
        }
        val gaveUp = reconnecting
        stopReconnecting()
        if (wasActive || gaveUp) ended(false)
        if (gaveUp) { log(message); status(RECONNECT_FAILED) } else status(message)
    }

    private fun scheduleReconnect() {
        reconnects.removeCallbacksAndMessages(null)
        val delay = RECONNECT_DELAYS_MS[reconnectAttempts.coerceAtMost(RECONNECT_DELAYS_MS.size - 1)]
        reconnects.postDelayed(::reconnect, delay)
    }

    /** One reconnection attempt; nothing about the ride, store session or vehicle state is reset. */
    private fun reconnect() {
        if (!reconnecting || phase != LinkPhase.IDLE) return
        val mac = reconnectMac
        if (mac == null || SystemClock.elapsedRealtime() >= reconnectUntil || !permitted()) {
            stopReconnecting(); ended(false); status(RECONNECT_FAILED); return
        }
        val adapter = manager?.adapter
        reconnectAttempts++
        if (adapter == null || !adapter.isEnabled) { scheduleReconnect(); return } // Bluetooth may come back on.
        log("Reconnecting (attempt $reconnectAttempts)")
        teardown()
        open(adapter.getRemoteDevice(mac), mac)
    }

    private fun stopReconnecting() {
        reconnects.removeCallbacksAndMessages(null)
        reconnecting = false; reconnectMac = null; reconnectAttempts = 0
    }

    private fun resetAttempt() {
        linkUp = false; discoveryStarted = false; mtuRequested = false; mtuTries = 0
        stageA = false; stageB = false; heartbeat = 0; lastTcuHeartbeat = 0
        chetakMode = false; chetakReadable.clear()
        sawBonding = false; awaitingBondResult = false; pendingDropCode = -1
    }

    /**
     * One automatic retry for the usual transient failures (133/147/62/8), including the
     * common CONNECTED → DISCONNECTED(133) pattern during setup. A new generation retires
     * every timer and callback of the failed attempt.
     */
    private fun retryOnce(g: BluetoothGatt, code: Int): Boolean {
        if (retriedConnect || phase != LinkPhase.CONNECTING || code !in setOf(133, 147, 62, 8)) return false
        val target = device ?: return false
        retriedConnect = true
        log("Connect failed (GATT $code); retrying once")
        generation++
        main.removeCallbacksAndMessages(null)
        queue.clear(); running = null; watchdog = null; retryPending = false
        resetAttempt()
        bondReceiver?.let { runCatching { app.unregisterReceiver(it) } }; bondReceiver = null; disarmPairing()
        adapterReceiver?.let { runCatching { app.unregisterReceiver(it) } }; adapterReceiver = null
        watchAdapter() // Receivers capture the generation; re-register for the new one.
        runCatching { g.disconnect() }; runCatching { g.close() }
        gatt = null
        armTimeout(35_000, NO_RESPONSE)
        val attempt = generation
        main.postDelayed({ if (attempt == generation) openGatt(target) }, 1_200)
        return true
    }

    private fun scaleKey(kind: String) = "scale_${kind}_${device?.address ?: ""}"

    private fun bondName(state: Int) = when (state) {
        BluetoothDevice.BOND_BONDED -> "bonded"; BluetoothDevice.BOND_BONDING -> "bonding"; else -> "not bonded"
    }

    private var timeout: Runnable? = null
    private fun armTimeout(ms: Long, message: String) {
        timeout?.let(main::removeCallbacks)
        val attempt = generation
        val task = Runnable { if (attempt == generation && phase == LinkPhase.CONNECTING) fail(message) }
        timeout = task; main.postDelayed(task, ms)
    }

    private fun openGatt(target: BluetoothDevice) {
        val attempt = generation
        gatt = try {
            target.connectGatt(app, false, callback(attempt), BluetoothDevice.TRANSPORT_LE)
        } catch (error: Exception) { fail("Could not connect: ${error.message}"); return }
        if (gatt == null) fail("Android refused to open a Bluetooth connection. Toggle Bluetooth and retry.")
    }

    private fun callback(attempt: Int) = object : BluetoothGattCallback() {
        private fun post(block: () -> Unit) { main.post { if (attempt == generation) block() } }

        override fun onConnectionStateChange(g: BluetoothGatt, code: Int, newState: Int) = post {
            if (newState == BluetoothProfile.STATE_CONNECTED && code == BluetoothGatt.GATT_SUCCESS) { onLinkUp(g); return@post }
            if (newState != BluetoothProfile.STATE_DISCONNECTED && code == BluetoothGatt.GATT_SUCCESS) return@post
            // Scooters drop the link after a refused PIN, and the disconnect can arrive before
            // Android's BOND_NONE broadcast: give the bond receiver a moment to classify it first.
            if (bondReceiver != null && pairingArmed && device?.bondState != BluetoothDevice.BOND_BONDED) {
                if (!awaitingBondResult) {
                    awaitingBondResult = true; pendingDropCode = code
                    log("Link dropped during pairing (GATT $code); waiting for the pairing result")
                    val attempt = generation
                    main.postDelayed({ if (attempt == generation) linkDroppedWhileBonding(g, code) }, BOND_RESULT_WAIT_MS)
                }
                return@post
            }
            linkDown(g, code)
        }
        override fun onServicesDiscovered(g: BluetoothGatt, code: Int) = post {
            if (code != BluetoothGatt.GATT_SUCCESS) fail("Service discovery failed (GATT $code).") else onDiscovered(g)
        }
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, code: Int) = post { onMtu(g, mtu, code) }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, code: Int) = post { complete(Kind.DESCRIPTOR, d.characteristic.uuid, code) }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, code: Int) = post { complete(Kind.WRITE, c.uuid, code) }
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, code: Int) {
            val copy = value.copyOf()
            post { if (code == BluetoothGatt.GATT_SUCCESS) handle(c.uuid, copy); complete(Kind.READ, c.uuid, code) }
        }
        @Deprecated("Called on API < 33 only")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, code: Int) {
            @Suppress("DEPRECATION") val copy = c.value?.copyOf() ?: ByteArray(0)
            post { if (code == BluetoothGatt.GATT_SUCCESS) handle(c.uuid, copy); complete(Kind.READ, c.uuid, code) }
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            val copy = value.copyOf(); post { handle(c.uuid, copy) }
        }
        @Deprecated("Called on API < 33 only")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") val copy = c.value?.copyOf() ?: ByteArray(0)
            post { handle(c.uuid, copy) }
        }
    }

    /**
     * The link is down (not mid-bond): one retry for transient codes, else fail with a reason. While
     * pairing, or with a stale bond (paired on the phone, GATT 5/15/137), the reason is the pairing
     * failure ([PairingRules.linkFailure]).
     */
    private fun linkDown(g: BluetoothGatt, code: Int) {
        if (retryOnce(g, code)) return
        val target = device
        if (target != null) {
            val state = target.bondState
            val failure = PairingRules.linkFailure(state, bondReceiver != null, sawBonding, PinResponder.pinDelivered(target.address), code)
            if (failure != null) { pairingFailed(target.address, failure, state, gattCode = code); return }
        }
        fail(when {
            code in setOf(5, 15, 137) -> "Bluetooth pairing problem (GATT $code). In Android Bluetooth settings, forget ${deviceName ?: "the scooter"}, pair again, then retry."
            phase == LinkPhase.READY -> "Scooter disconnected (GATT $code)."
            else -> "Connection failed (GATT $code). Close the scooter maker's official app and nRF Connect, then retry."
        })
    }

    /**
     * No bond broadcast arrived within [BOND_RESULT_WAIT_MS] of a mid-bond disconnect. A wrong PIN
     * needs proof the scooter received it (PinResponder delivered it, then an auth failure or the
     * bond fell back to none); bonding that failed without our PIN is NO_PIN_REQUEST. Anything else
     * goes through [linkDown] (one retry, then LINK_LOST).
     */
    private fun linkDroppedWhileBonding(g: BluetoothGatt, code: Int) {
        awaitingBondResult = false
        val target = device
        val state = target?.bondState
        if (target == null || state == BluetoothDevice.BOND_BONDED) { linkDown(g, code); return }
        val failure = PairingRules.linkFailure(state ?: BluetoothDevice.BOND_NONE, true, sawBonding, PinResponder.pinDelivered(target.address), code)
        if (failure == PairingFailure.WRONG_PIN || failure == PairingFailure.NO_PIN_REQUEST) {
            pairingFailed(target.address, failure, state ?: BluetoothDevice.BOND_NONE, gattCode = code)
            return
        }
        linkDown(g, code)
    }

    /** Ends the attempt with pairing [failure]: logs what was seen (never the PIN), tells the UI, fails with its title. */
    private fun pairingFailed(mac: String, failure: PairingFailure, bondState: Int, reason: Int = -1, gattCode: Int = -1) {
        log(PairingRules.diagnostic(failure, PinResponder.delivery(mac), bondState, reason, gattCode))
        PinResponder.report(mac, PairingEvent.BondFailed(failure))
        fail(PairingRules.failureTitle(failure))
    }

    private fun onLinkUp(g: BluetoothGatt) {
        if (linkUp) return
        linkUp = true
        val target = device ?: return
        status("Connected · preparing scooter link…")
        if (target.bondState == BluetoothDevice.BOND_BONDED) { settleThenDiscover(g); return }
        log("Not bonded; requesting Android pairing")
        // 2.1 (Addendum C): answer Android's pairing request with the PIN saved for this scooter so
        // pairing is silent. Armed before createBond; disarmed on every exit (bonded, teardown, retry).
        val mac = target.address
        val pin = Scooters.pin(app, mac) // Decrypted; never logged.
        // Arming again for the same MAC keeps an arm the shell made before connecting.
        if (pin != null) PinResponder.arm(app, mac, pin) {}
        // Armed only: whether the scooter actually received the PIN is PinResponder.pinDelivered.
        val pinArmed = PinResponder.isArmed(mac)
        if (pinArmed) { pairingArmed = true; log("Pairing with the saved PIN") }
        status(if (pinArmed) "Pairing with your scooter…" else "Confirm Bluetooth pairing with your scooter…")
        armTimeout(90_000, "Bluetooth pairing did not finish. Pair the scooter in Android settings, then retry.")
        val attempt = generation
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val changed = if (Build.VERSION.SDK_INT >= 34) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                if (attempt != generation || changed?.address != target.address) return
                val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                val previous = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR)
                val reason = intent.getIntExtra(EXTRA_UNBOND_REASON, -1)
                if (state == BluetoothDevice.BOND_BONDING) sawBonding = true
                if (state == BluetoothDevice.BOND_NONE) log("Bond ended (reason ${reason.takeIf { it >= 0 } ?: "unknown"})")
                val dropCode = if (awaitingBondResult) pendingDropCode else -1
                when (val outcome = PairingRules.bondOutcome(previous, state, pinArmed, PinResponder.pinDelivered(mac), reason, dropCode)) {
                    BondOutcome.Bonded -> { PinResponder.report(mac, PairingEvent.Bonded); bonded(g) }
                    is BondOutcome.Failed -> pairingFailed(mac, outcome.failure, state, reason = reason, gattCode = dropCode)
                    BondOutcome.Pending -> {}
                }
            }
        }
        bondReceiver = receiver
        // ACTION_BOND_STATE_CHANGED is a protected system broadcast; exported is correct.
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), Context.RECEIVER_EXPORTED)
        else app.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
        when {
            target.bondState == BluetoothDevice.BOND_BONDED -> { PinResponder.report(mac, PairingEvent.Bonded); bonded(g) } // Bonded between the check and registration.
            target.bondState == BluetoothDevice.BOND_NONE && !target.createBond() -> {
                PinResponder.report(mac, PairingEvent.Failed("Android could not start pairing"))
                fail("Android could not start pairing.")
            }
            else -> if (target.bondState == BluetoothDevice.BOND_BONDING) sawBonding = true
        }
        // Some skins (HyperOS) let their own higher-priority component take the pairing request, so
        // our receiver never sees it and Android's dialog asks for the PIN: offer the saved PIN.
        if (pinArmed && pin != null) main.postDelayed({
            if (attempt == generation && bondReceiver === receiver && target.bondState == BluetoothDevice.BOND_BONDING &&
                PinResponder.isArmed(mac) && !PinResponder.requestSeen(mac)) {
                log("Pairing request not seen by Chetooth; Android's dialog may ask for the PIN")
                PinResponder.report(mac, PairingEvent.SystemDialog(pin))
            }
        }, SYSTEM_DIALOG_CHECK_MS)
    }

    /** Stops answering pairing requests for this attempt (no-op unless the not-bonded path armed it). */
    private fun disarmPairing() {
        if (!pairingArmed) return
        pairingArmed = false
        PinResponder.disarm()
    }

    private fun bonded(g: BluetoothGatt) {
        bondReceiver?.let { runCatching { app.unregisterReceiver(it) } }; bondReceiver = null; disarmPairing()
        log("Bonded")
        armTimeout(35_000, "Scooter did not finish setup. Tap Connect to retry.")
        settleThenDiscover(g)
    }

    /** Wait ~1 s after connecting before discovery (the TCU is not ready straight away). */
    private fun settleThenDiscover(g: BluetoothGatt) {
        main.postDelayed({
            if (gatt !== g || discoveryStarted) return@postDelayed
            discoveryStarted = true
            if (!g.discoverServices()) fail("Service discovery could not start.")
        }, 1_000)
    }

    private fun characteristic(uuid: UUID): BluetoothGattCharacteristic? {
        val g = gatt ?: return null
        if (uuid == Tcu.STATS_REQUEST || uuid == Tcu.STATS_UPDATE) {
            // Service four (f453986a-…) on the VX2: any plaintext service that has them; the encrypted
            // command service is only a last resort for other models (its 0x0201–0x0203 are never used).
            return g.services.orEmpty().filter { it.uuid != Tcu.COMMAND_SERVICE }.mapNotNull { it.getCharacteristic(uuid) }.lastOrNull()
                ?: g.getService(Tcu.COMMAND_SERVICE)?.getCharacteristic(uuid)
        }
        return g.getService(Tcu.INFO_SERVICE)?.getCharacteristic(uuid)
            ?: g.services.orEmpty().mapNotNull { it.getCharacteristic(uuid) }.lastOrNull() // Duplicates: the last match wins.
    }

    private fun onDiscovered(g: BluetoothGatt) {
        g.services.forEach { service ->
            log("Service ${service.uuid.toString().take(8)}: " + service.characteristics.joinToString { "${it.uuid.short()}/${"%02X".format(it.properties)}" })
        }
        val missing = listOf(Tcu.TCU_HEARTBEAT, Tcu.PHONE_HEARTBEAT, Tcu.STATS_REQUEST, Tcu.STATS_UPDATE).filter { characteristic(it) == null }
        if (missing.isNotEmpty()) {
            // Chetooth: not a Hero TCU (e.g. a Bajaj Chetak). Listen to everything, write nothing.
            chetakMode = true
            log("No Hero TCU link (${missing.joinToString { it.short() }} missing): Chetak mode, reading every value the scooter offers")
            ChetakData.services = g.services.orEmpty().joinToString("\n") { service ->
                "Service ${service.uuid}\n" + service.characteristics.orEmpty().joinToString("\n") { "  ${shortUuid(it.uuid.toString())} [${propertyLetters(it.properties)}]" }
            }
        }
        requestMtu(g)
    }

    private fun requestMtu(g: BluetoothGatt) {
        mtuRequested = true; mtuTries++
        if (!g.requestMtu(247)) { log("MTU request could not start; continuing at default MTU"); startStageA(); return }
        val attempt = generation
        main.postDelayed({ if (attempt == generation && !stageA) { log("No MTU response; continuing"); startStageA() } }, 3_000)
    }

    private fun onMtu(g: BluetoothGatt, mtu: Int, code: Int) {
        if (!mtuRequested || stageA) { log("MTU now $mtu"); return } // Peer-initiated or late.
        if (code != BluetoothGatt.GATT_SUCCESS && mtuTries < 3) { log("MTU request failed (GATT $code); retrying"); requestMtu(g); return }
        log("MTU $mtu (GATT $code)")
        if (mtu < 29) log("Warning: MTU $mtu is too small for 26-byte BMS frames; battery data may be cut off")
        startStageA()
    }

    private fun startStageA() {
        if (stageA) return
        stageA = true
        if (chetakMode) { startChetak(); return }
        notify(Tcu.TCU_HEARTBEAT, critical = true)
        read(Tcu.TCU_INFO, critical = false)
        sendHeartbeat(1)
        marker { main.postDelayed({ if (gatt != null) startStageB() }, 1_000) }
    }

    private fun startStageB() {
        if (stageB) return
        stageB = true
        notify(Tcu.VEHICLE_STATE, critical = false)
        if (characteristic(Tcu.ESYNC_STATE) != null) notify(Tcu.ESYNC_STATE, critical = false)
        if (characteristic(Tcu.OS_INFO) != null) write(Tcu.OS_INFO, byteArrayOf(Tcu.OS_ANDROID), critical = false)
        read(Tcu.VEHICLE_STATE, critical = false)
        notify(Tcu.STATS_UPDATE, critical = true)
        // Subscribe to the handlebar channel on every connection: the TCU treats the phone as a
        // cluster client (and shows turn-by-turn) only once this CCCD is set.
        if (characteristic(Tcu.ICBT_CONTROL) != null) notify(Tcu.ICBT_CONTROL, critical = false)
        requestBms(0, schedule = true)
        marker(::onReady)
    }

    private fun onReady() {
        if (phase != LinkPhase.CONNECTING) return
        timeout?.let(main::removeCallbacks)
        phase = LinkPhase.READY
        lastTcuHeartbeat = SystemClock.elapsedRealtime()
        if (reconnecting) { log("Reconnected after ${reconnectAttempts} attempt(s)"); stopReconnecting() }
        connected()
        if (chetakMode) { onChetakReady(); return }
        // A frame can arrive before READY (the TCU may keep a stream from a previous link).
        status(if (lastBmsAt != 0L) "Connected · battery data live" else "Connected · waiting for battery data")
        watchHeartbeat(generation)
        keepStatsFlowing(generation)
        if (characteristic(Tcu.PHONE_STATE) == null) log("No 0x0301 characteristic: the cluster's phone status cannot be set")
        navigationMissing = characteristic(Tcu.NAV_INFO) == null
        if (navigationMissing) log("No 0x0303 characteristic: turn-by-turn is unavailable on this scooter")
        val missingIcbt = listOf(Tcu.CALLER_INFO, Tcu.MUSIC_INFO, Tcu.MUSIC_META).filter { characteristic(it) == null }
        if (missingIcbt.isNotEmpty()) log("No ${missingIcbt.joinToString { "0x" + it.short() }} characteristic: caller/music on the display is limited")
        PhoneBridge.addListener(phoneListener)
        PhoneBridge.addMessageListener(messageListener)
        // onReady runs inside pump()'s marker branch; the tick enqueues writes, so start it afterwards.
        val attempt = generation
        main.post { clusterTick(attempt); NavBridge.addListener(navListener) }
        main.postDelayed({ if (attempt == generation && phase == LinkPhase.READY) autoProbe(attempt) }, AUTO_PROBE_DELAY_MS)
    }

    /**
     * 1 Hz: phone status (marks the phone connected, syncs the cluster
     * clock, carries call state and counts) and caller during calls, then turn-by-turn when Maps
     * is navigating, then music. Every periodic write is replaceable, so the queue holds at most
     * one per characteristic and heartbeats never wait behind a backlog.
     */
    private fun clusterTick(attempt: Int) {
        if (attempt != generation || phase != LinkPhase.READY) return
        sendPhoneStatus()
        pushNavigation()
        pushMusic()
        main.postDelayed({ clusterTick(attempt) }, 1_000)
    }

    /** 0x0301, then 0x0302 while a call rings or is up. */
    private fun sendPhoneStatus() {
        if (characteristic(Tcu.PHONE_STATE) == null) return
        val now = LocalDateTime.now()
        val uptime = SystemClock.elapsedRealtime()
        val battery = runCatching { app.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) }.getOrNull()
        val signal = runCatching { app.getSystemService(TelephonyManager::class.java).signalStrength?.level }.getOrNull() ?: 0
        val call = PhoneBridge.call
        val calls = callsEnabled
        val callState = if (calls) call.tcuState(uptime) else TcuCallState.NONE
        val counts = PhoneBridge.counts
        val missed = if (calls) counts.missedCalls else 0
        val status = PhoneStatus(
            battery, signal, now.hour, now.minute, now.second,
            headset = PhoneBridge.headset,
            missedCallAlert = missed > 0, callState = callState,
            smsFlag = counts.unreadMessages > 0, smsCount = counts.unreadMessages, missedCount = missed,
            callDurationS = if (calls) call.durationS(uptime) else 0
        )
        write(Tcu.PHONE_STATE, status.encode(), critical = false, maxTries = 1, replaceable = true)
        lastStatusKey = statusKey()
        val caller = if (callState.showsCaller) CallerInfo.forCall(call.name, call.number) else null
        if (caller != null && characteristic(Tcu.CALLER_INFO) != null) write(Tcu.CALLER_INFO, caller.encode(), critical = false, maxTries = 1, replaceable = true)
        if (callState != lastCallLogged) {
            lastCallLogged = callState
            log("Display call state: ${callState.name.lowercase()}" + (caller?.let { " · ${it.encode().hex()}" } ?: ""))
        }
    }

    /** What 0x0301 carries besides the clock, battery and signal; a change is sent at once. */
    private fun statusKey(): List<Any?> {
        val call = PhoneBridge.call
        val counts = PhoneBridge.counts
        return listOf(call.tcuState(SystemClock.elapsedRealtime()), call.display, counts.missedCalls, counts.unreadMessages, PhoneBridge.headset, callsEnabled)
    }

    /** PhoneBridge changed (call, counts, headset, music, volume): update the cluster now instead of at the next tick. */
    private fun onPhoneChanged() {
        if (phase != LinkPhase.READY) return
        if (statusKey() != lastStatusKey) sendPhoneStatus()
        pushMusic()
    }

    /** What [MessageAlerter] needs to know now. The demo never shows sender alerts. */
    private fun messageConditions() = MessageAlerter.Conditions(
        enabled = !Demo.active && Alerts.enabled(app, AlertKind.MESSAGE_SENDER),
        ready = phase == LinkPhase.READY && characteristic(Tcu.MUSIC_META) != null,
        playing = PhoneBridge.media?.playing == true,
        inCall = PhoneBridge.call.inCall
    )

    /** A new message (PhoneBridge): show who sent it on the music line when allowed (no names in the log). */
    private fun onMessage(notice: MessageNotice) {
        if (phase != LinkPhase.READY) return
        val change = messageAlerter.message(notice, SystemClock.elapsedRealtime(), messageConditions())
        messageAlerter.describe(change)?.let { log(it) }
        if (change.outcome == MessageAlerter.Outcome.SHOWN) pushMusic()
    }

    /**
     * 0x0304 on any change and every second while playing (track progress); 0x0305 right after
     * it, only when the bytes change (28 zeros clear the song when no session is left). Nothing
     * during calls: the cluster shows the call then. A sender alert ([messageAlerter]) takes the
     * 0x0305 slot while it shows; when it goes, the normal song (or the clear frame) is sent again.
     */
    private fun pushMusic() {
        if (phase != LinkPhase.READY) return
        val hasInfo = characteristic(Tcu.MUSIC_INFO) != null
        val hasMeta = characteristic(Tcu.MUSIC_META) != null
        if (!hasInfo && !hasMeta) return
        val now = SystemClock.elapsedRealtime()
        messageAlerter.tick(now, messageConditions())?.let { change -> messageAlerter.describe(change)?.let { log(it) } }
        if (now < musicRetryAt || PhoneBridge.call.inCall) return
        val media = if (musicEnabled) PhoneBridge.media else null
        val info = MusicInfo.of(media?.playbackState, PhoneBridge.volume.level, media?.progressPercent(now) ?: 0)
        val infoBytes = info.encode()
        val changed = lastMusicInfo?.contentEquals(infoBytes) != true
        if (hasInfo && (changed || (info.playing && now - lastMusicSentAt >= 1_000))) {
            write(Tcu.MUSIC_INFO, infoBytes, critical = false, maxTries = 2, replaceable = true)
            lastMusicInfo = infoBytes; lastMusicSentAt = now
            val summary = info.copy(elapsedPercent = 0)
            if (summary != lastMusicLogged) {
                lastMusicLogged = summary
                log("Display music: ${if (!info.active) "no session" else if (info.playing) "playing" else "paused"}, volume ${info.volume}/${MusicInfo.VOLUME_STEPS} · ${infoBytes.hex()}")
            }
        }
        val alert = messageAlerter.meta
        val meta = alert ?: media?.let { MusicMeta.of(it.title, it.artist, it.album) } ?: MusicMeta.CLEAR
        val metaBytes = meta.encode()
        if (hasMeta && lastMeta?.contentEquals(metaBytes) != true) {
            write(Tcu.MUSIC_META, metaBytes, critical = false, maxTries = 2, replaceable = true)
            lastMeta = metaBytes
            // Never the sender's name in the log: onMessage already logged "Message alert shown (App)".
            if (alert == null) log(if (meta == MusicMeta.CLEAR) "Display song cleared" else "Display song: \"${IcbtText.sanitize(meta.title, "")}\" · ${IcbtText.sanitize(meta.artist, "")}")
        }
    }

    /** See [ClusterFeed]. */
    fun musicFeed(): ClusterFeed = when {
        phase != LinkPhase.READY || (characteristic(Tcu.MUSIC_INFO) == null && characteristic(Tcu.MUSIC_META) == null) -> ClusterFeed.UNAVAILABLE
        !musicEnabled -> ClusterFeed.OFF
        PhoneBridge.media == null || PhoneBridge.call.inCall -> ClusterFeed.IDLE
        else -> feed(Tcu.MUSIC_INFO, Tcu.MUSIC_META, since = 0L)
    }

    /** See [ClusterFeed]. SHOWING once this call's state (and caller, when known) was acknowledged. */
    fun callFeed(): ClusterFeed {
        val call = PhoneBridge.call
        return when {
            phase != LinkPhase.READY || characteristic(Tcu.PHONE_STATE) == null -> ClusterFeed.UNAVAILABLE
            !callsEnabled -> ClusterFeed.OFF
            !call.inCall -> ClusterFeed.IDLE
            CallerInfo.forCall(call.name, call.number) != null && characteristic(Tcu.CALLER_INFO) != null -> feed(Tcu.CALLER_INFO, since = call.startedAt)
            else -> feed(Tcu.PHONE_STATE, since = call.startedAt)
        }
    }

    private fun feed(vararg uuids: UUID, since: Long): ClusterFeed {
        val acked = uuids.maxOf { ackedAt[it] ?: 0L }
        val failed = uuids.maxOf { failedAt[it] ?: 0L }
        return when {
            failed > acked && failed >= since -> ClusterFeed.REJECTED
            acked > 0 && acked >= since -> ClusterFeed.SHOWING
            else -> ClusterFeed.SENDING
        }
    }

    private fun resetPhoneFeeds() {
        ackedAt.clear(); failedAt.clear()
        lastStatusKey = null; lastCallLogged = TcuCallState.NONE
        lastMusicInfo = null; lastMusicSentAt = 0; lastMusicLogged = null
        // An empty song is never the first 0x0305 of a connection.
        lastMeta = MusicMeta.CLEAR.encode(); musicRetryAt = 0
        messageAlerter.reset()
    }

    /**
     * 0x0306 handlebar buttons. A frame can decode to several events (every field maps to one),
     * but one press is one action: act on the first decoded event only (00 00 00 would otherwise
     * be volume up twice plus play/pause). Identical frames within 300 ms count once. Every frame is logged with its
     * decoding, so the real button encoding can be checked against the log.
     */
    private fun onHardControl(value: ByteArray) {
        val hex = value.hex()
        val frame = HardControlFrame.parse(value) ?: return log("Handlebar $hex: too short, ignored")
        if (!controlDebouncer.accept(value, SystemClock.elapsedRealtime())) return log("Handlebar $hex: repeat, ignored")
        val events = frame.events
        val event = events.firstOrNull() ?: return log("Handlebar $hex: no action")
        val ignored = events.drop(1).takeIf { it.isNotEmpty() }?.let { " (also decoded ${it.joinToString { e -> e.name.lowercase() }}; ignored)" }.orEmpty()
        // Play/pause while a sender alert shows only dismisses it (it must not start music).
        if (event == HardControlEvent.MUSIC_PLAY_PAUSE && messageAlerter.press(SystemClock.elapsedRealtime())) {
            lastHandlebar = "dismiss message alert"; lastHandlebarAt = SystemClock.elapsedRealtime()
            log("Handlebar $hex: message alert dismissed$ignored")
            pushMusic()
            return
        }
        if (!handlebarControlsEnabled) return log("Handlebar $hex: ${event.name.lowercase()}$ignored — handlebar controls are off")
        val done = PhoneBridge.handlebar(event)
        lastHandlebar = done; lastHandlebarAt = SystemClock.elapsedRealtime()
        log("Handlebar $hex: $done$ignored")
    }

    // --- Turn-by-turn (0x0303): Maps frames, display test, road-name width test ---

    /** Synthetic routes on the TFT, independent of Google Maps. */
    private enum class NavTest(val label: String, val stepMs: Long, val steps: Int) {
        DISPLAY("Display test", 5_000, 8),
        ROAD_WIDTH("Road-name width test", 4_000, 5);

        val durationMs: Long get() = stepMs * steps
    }

    private var testKind: NavTest? = null
    private var testStartedAt = 0L

    private fun activeTest(uptime: Long): NavTest? = testKind?.takeIf { uptime - testStartedAt in 0 until it.durationMs }

    /** Display test: a synthetic route for 40 s (a new arrow every 5 s), independent of Google Maps. */
    val displayTestRunning: Boolean get() = activeTest(SystemClock.elapsedRealtime()) == NavTest.DISPLAY
    /** See [startRoadWidthTest]. */
    val roadWidthTestRunning: Boolean get() = activeTest(SystemClock.elapsedRealtime()) == NavTest.ROAD_WIDTH

    fun startDisplayTest() = startTest(NavTest.DISPLAY)

    /**
     * Road-name width test: road names "12345678" … "123456789012" (8–12 digits), one every 4 s,
     * then the exit frame (or the Maps route again, if one is active). The distance field shows
     * each name's length (8 m … 12 m), so the rider can tell which length the TFT still shows
     * whole; RoadNames.TFT_CHARS should be set from that. [sentNavFrame] is the frame on the TFT.
     */
    fun startRoadWidthTest() = startTest(NavTest.ROAD_WIDTH)

    private fun startTest(kind: NavTest) {
        // Callers check the link first; a status here would read as a link error on an idle connection.
        if (phase != LinkPhase.READY) { log("${kind.label} needs a connected scooter"); return }
        testKind = kind; testStartedAt = SystemClock.elapsedRealtime()
        log(when (kind) {
            NavTest.DISPLAY -> "Display test started: sending a test route for 40 s"
            NavTest.ROAD_WIDTH -> "Road-name width test started: road names of ${ROAD_WIDTH_NAMES.first().length}–${ROAD_WIDTH_NAMES.last().length} digits, one every 4 s"
        })
        // Every step, and the end, goes out on time instead of at the next 1 Hz tick.
        val attempt = generation; val started = testStartedAt
        for (step in 1..kind.steps) main.postDelayed({
            if (attempt == generation && testKind == kind && testStartedAt == started) pushNavigation()
        }, step * kind.stepMs + 5)
        pushNavigation()
    }

    private fun testFrame(kind: NavTest, now: LocalDateTime, uptime: Long): NavFrame {
        val step = ((uptime - testStartedAt) / kind.stepMs).toInt().coerceIn(0, kind.steps - 1)
        val arrival = now.plusMinutes(9)
        return when (kind) {
            NavTest.DISPLAY -> {
                val turns = intArrayOf(NavTurn.RIGHT, NavTurn.LEFT, NavTurn.STRAIGHT, NavTurn.SLIGHT_RIGHT, NavTurn.U_TURN_RIGHT, NavTurn.ROUNDABOUT_LEFT, NavTurn.KEEP_LEFT, NavTurn.DESTINATION)
                NavFrame(true, turns[step], 250 - step * 30, NavTurn.LEFT, arrival.hour, arrival.minute, 2400 - step * 200, 9, "TEST ROAD $step")
            }
            NavTest.ROAD_WIDTH -> {
                val name = ROAD_WIDTH_NAMES[step]
                NavFrame(true, NavTurn.STRAIGHT, name.length, NavTurn.STRAIGHT, arrival.hour, arrival.minute, 2400, 9, name)
            }
        }
    }

    /**
     * Sends the current turn-by-turn frame (2.1 F4): at once on every Maps update and every
     * counted-down distance change ([navListener]), on every test step, and from the 1 Hz tick,
     * which re-sends an unchanged frame about once a second. Nothing is
     * debounced; only an identical frame within 900 ms is skipped. Writes use the priority lane
     * ([enqueuePriority]). One exit frame when navigation ends.
     */
    fun pushNavigation() {
        if (phase != LinkPhase.READY || characteristic(Tcu.NAV_INFO) == null) return
        val uptime = SystemClock.elapsedRealtime()
        val test = activeTest(uptime)
        val finished = testKind
        if (test == null && finished != null) {
            testKind = null
            log("${finished.label} finished")
            if (NavBridge.cue == null) NavBridge.exitPending = true
        }
        if (test == null && (NavBridge.exitPending || !navigationEnabled)) { stopNavigation(); return }
        val now = LocalDateTime.now()
        val frame = (if (test != null) testFrame(test, now, uptime) else NavBridge.frame(now)) ?: return
        if (frame == lastNavFrame && uptime - lastNavSentAt < 900) return
        val bytes = frame.encode()
        if (!navigationSending) { log("Turn-by-turn started"); navigationAckedAt = 0; navigationError = null }
        write(Tcu.NAV_INFO, bytes, critical = false, maxTries = 2, replaceable = true, priority = true)
        navigationSending = true; lastNavFrame = frame; lastNavSentAt = uptime
        // One line per Maps update, maneuver or test step; countdown ticks are not logged.
        val logKey = listOf(test, if (test == null) NavBridge.revision else null, frame.active, frame.turn, frame.roadText)
        if (logKey != lastNavLogKey) {
            lastNavLogKey = logKey
            log("TBT: ${bytes.hex()} · ${distanceText(frame.distanceMeters)} · '${frame.roadText}'")
        }
    }

    private fun distanceText(metres: Int): String =
        if (metres < 1000) "$metres m" else "${metres / 1000}.${metres % 1000 / 100} km"

    /** What the Navigate card may claim; see [NavDisplay]. */
    fun navigationDisplay() = NavDisplay.of(!navigationMissing, navigationSending, navigationAckedAt, navigationError, SystemClock.elapsedRealtime())

    private fun stopNavigation() {
        NavBridge.exitPending = false
        if (!navigationSending || phase != LinkPhase.READY || characteristic(Tcu.NAV_INFO) == null) { navigationSending = false; return }
        write(Tcu.NAV_INFO, NavFrame.EXIT.encode(), critical = false, maxTries = 2, replaceable = true, priority = true)
        log("Turn-by-turn stopped")
        navigationSending = false; lastNavFrame = null; lastNavLogKey = null
    }

    /**
     * Period 200 streams on the VX2; 0 is a one-shot snapshot. If frames stop for 15 s, request
     * the next period, wrapping around, so a stalled 200 stream is re-requested after one snapshot.
     */
    private fun keepStatsFlowing(attempt: Int) {
        main.postDelayed({
            if (attempt != generation || phase != LinkPhase.READY) return@postDelayed
            if (lastBmsAt != 0L && SystemClock.elapsedRealtime() - lastBmsAt > 15_000) {
                log("BMS frames stopped; switching to the next period")
                requestBms((bmsPeriodIndex + 1) % Tcu.BMS_PERIODS_MS.size, schedule = false)
            }
            keepStatsFlowing(attempt)
        }, 15_000)
    }

    private fun watchHeartbeat(attempt: Int) {
        main.postDelayed({
            if (attempt != generation || phase != LinkPhase.READY) return@postDelayed
            if (SystemClock.elapsedRealtime() - lastTcuHeartbeat > 30_000) fail("Scooter stopped sending heartbeats. Tap Connect to retry.")
            else watchHeartbeat(attempt)
        }, 5_000)
    }

    private fun requestBms(index: Int, schedule: Boolean) {
        bmsPeriodIndex = index
        val period = Tcu.BMS_PERIODS_MS[index]
        val payload = Tcu.statsRequest(Tcu.TAG_BMS, true, period)
        log("Requesting BMS stream: ${payload.hex()} (period $period)")
        write(Tcu.STATS_REQUEST, payload, critical = false)
        if (!schedule) return
        val attempt = generation
        main.postDelayed({
            if (attempt != generation || gatt == null || lastBmsAt != 0L) return@postDelayed
            if (index + 1 < Tcu.BMS_PERIODS_MS.size) { log("No BMS frame after 12 s; trying next period"); requestBms(index + 1, schedule = true) }
            else noBmsFrames()
        }, 12_000)
    }

    /**
     * Diagnostic: ask the TCU once for every known stats tag (1-13) and log the
     * raw replies, so fields like range/odometer/temperature can be matched against the TFT.
     * One-shot (period 0) each, 1.5 s apart, then disabled again. Tag 1 (BMS) keeps streaming.
     */
    fun probeAllStats() {
        if (phase != LinkPhase.READY) { status("Connect the scooter first, then probe."); return }
        if (chetakMode) { log("Probe: Chetak mode reads every value already; re-reading now"); refresh(); return }
        val tags = listOf(2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13)
        val attempt = generation
        probing = true
        log("Probe: requesting stats tags ${tags.joinToString()} (names: 2 dynamics, 3 static, 4 EV faults, 5 MCU, 6 temperature, 7 NMEA, 8 IMU, 9 alerts, 10 network, 11 ECU, 12 ICE faults, 13 status)")
        tags.forEachIndexed { index, tag ->
            main.postDelayed({ if (attempt == generation) write(Tcu.STATS_REQUEST, Tcu.statsRequest(tag, true, 0), critical = false) }, index * 1_500L)
        }
        main.postDelayed({
            if (attempt != generation) return@postDelayed
            tags.forEach { write(Tcu.STATS_REQUEST, Tcu.statsRequest(it, false, 0), critical = false) }
            probing = false
            log("Probe finished")
        }, tags.size * 1_500L + 3_000L)
    }
    private var probing = false

    /**
     * 2.1 F2: once per connection, [AUTO_PROBE_DELAY_MS] after READY, ask once (period 0) for stats
     * tags 2, 3, 5, 6, 10 and 11, 1.5 s apart, then disable them again, like [probeAllStats].
     * [onStats] logs every raw reply with its tag and keeps it for [rawStats]. Tag 1 (BMS) is never
     * requested here; should its stream have gone quiet anyway, it is requested again afterwards.
     */
    private fun autoProbe(attempt: Int) {
        if (autoProbed) return
        autoProbed = true
        val started = SystemClock.elapsedRealtime()
        log("Auto-probe: asking once for stats tags ${AUTO_PROBE_TAGS.joinToString()} (2 dynamics, 3 static, 5 MCU, 6 temperature, 10 network, 11 ECU)")
        AUTO_PROBE_TAGS.forEachIndexed { index, tag ->
            main.postDelayed({
                if (attempt == generation && phase == LinkPhase.READY) write(Tcu.STATS_REQUEST, Tcu.statsRequest(tag, true, 0), critical = false)
            }, index * 1_500L)
        }
        main.postDelayed({
            if (attempt != generation || phase != LinkPhase.READY) return@postDelayed
            AUTO_PROBE_TAGS.forEach { write(Tcu.STATS_REQUEST, Tcu.statsRequest(it, false, 0), critical = false) }
            val (answered, silent) = AUTO_PROBE_TAGS.partition { (rawStatsAt[it] ?: 0L) >= started }
            log("Auto-probe finished: replies from tags ${answered.joinToString().ifEmpty { "none" }}" +
                if (silent.isEmpty()) "" else "; none from ${silent.joinToString()}")
            if (lastBmsAt != 0L && SystemClock.elapsedRealtime() - lastBmsAt > 5_000) {
                log("BMS frames paused during the auto-probe; requesting the stream again")
                requestBms(bmsPeriodIndex, schedule = false)
            }
        }, AUTO_PROBE_TAGS.size * 1_500L + 3_000L)
    }

    /** Latest raw reply per stats tag (copies, ascending tag), incl. tag 1 (BMS); kept after the link drops. */
    fun rawStats(): Map<Int, ByteArray> = rawStatsByTag.toSortedMap().mapValues { it.value.copyOf() }

    private fun noBmsFrames() {
        status("Connected · the scooter has not sent battery frames. Open More › Diagnostics, copy the log and share it.")
        if (probedStats) return
        probedStats = true
        // Diagnostic: does the stats service answer at all? Tag 13 is a small vehicle-status frame.
        log("Probing stats service with a vehicle-status request (tag 13)")
        write(Tcu.STATS_REQUEST, Tcu.statsRequest(Tcu.TAG_VEHICLE_STATUS, true, 0), critical = false)
    }

    private fun sendHeartbeat(counter: Int) {
        heartbeat = counter and 0xFFFF
        write(Tcu.PHONE_HEARTBEAT, Tcu.heartbeat(heartbeat), critical = false) // Up to 3 attempts per write.
    }

    private fun handle(uuid: UUID, value: ByteArray) {
        val now = System.currentTimeMillis()
        if (chetakMode) { onChetakData(uuid, value, now); return }
        when (uuid) {
            Tcu.TCU_HEARTBEAT -> { lastTcuHeartbeat = SystemClock.elapsedRealtime(); sendHeartbeat(Tcu.heartbeatValue(value) + 1) }
            // Bytes 0–31 are the IMEI and VIN: never logged, even when the frame is unreadable.
            Tcu.TCU_INFO -> TcuParser.info(value)?.let { log("TCU firmware ${it.firmware}, BLE protocol ${it.bleProtocol}, hardware ${it.hardware}"); info(it) }
                ?: log("TCU info unreadable (${value.size} bytes)" + if (value.size > 32) ": … ${value.copyOfRange(32, value.size).hex()}" else "")
            Tcu.VEHICLE_STATE -> TcuParser.vehicleState(value, now)?.let(vehicle) ?: log("Vehicle state unreadable: ${value.hex()}")
            Tcu.ESYNC_STATE -> log("ESYNC: ${value.hex()}")
            Tcu.ICBT_CONTROL -> onHardControl(value)
            Tcu.STATS_UPDATE -> onStats(value, now)
        }
    }

    private fun onStats(value: ByteArray, now: Long) {
        val tag = value.firstOrNull()?.toInt()?.and(0xFF)
        if (tag != null) { rawStatsByTag[tag] = value.copyOf(); rawStatsAt[tag] = SystemClock.elapsedRealtime() }
        val hex = value.hex()
        // Non-BMS frames (probe replies) are always logged; the BMS stream is deduplicated.
        if (tag != Tcu.TAG_BMS || hex != lastRawHex || now - lastRawLogAt > 10_000) {
            log("Stats tag $tag (${value.size} B): $hex")
            if (tag == Tcu.TAG_BMS) { lastRawHex = hex; lastRawLogAt = now }
        }
        if (tag == Tcu.TAG_VEHICLE_STATUS && probedStats) {
            write(Tcu.STATS_REQUEST, Tcu.statsRequest(Tcu.TAG_VEHICLE_STATUS, false, 0), critical = false)
            return
        }
        if (tag != Tcu.TAG_BMS) return
        val frame = TcuParser.bms(value) ?: return log("BMS frame too short (${value.size} B)")
        val first = lastBmsAt == 0L
        lastBmsAt = SystemClock.elapsedRealtime()
        val before = decoder.percentDivisor to decoder.temperatureDivisor
        decoder.readings(frame, now).forEach(received)
        if (before != (decoder.percentDivisor to decoder.temperatureDivisor)) {
            log("Scale latched: percent ÷${decoder.percentDivisor ?: "?"}, temperature ÷${decoder.temperatureDivisor ?: "?"}")
            prefs.edit().putInt(scaleKey("pct"), decoder.percentDivisor ?: 0).putInt(scaleKey("temp"), decoder.temperatureDivisor ?: 0).apply()
        }
        if (first && phase == LinkPhase.READY) status("Connected · battery data live")
    }

    // --- Chetooth: Chetak mode ---

    /** Subscribes to every notify/indicate characteristic and reads every readable one, then READY. */
    private fun startChetak() {
        val g = gatt ?: return
        chetakReadable.clear()
        var subscribed = 0
        for (service in g.services.orEmpty()) for (c in service.characteristics.orEmpty()) {
            val p = c.properties
            if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) chetakReadable += c
            if (p and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                notifyChar(c); subscribed++
            }
        }
        chetakReadable.forEach { readChar(it, tries = 2) }
        log("Chetak mode: listening to $subscribed characteristics, reading ${chetakReadable.size}")
        marker(::onReady)
    }

    private fun onChetakReady() {
        ChetakData.linked = true
        navigationMissing = true
        if (reconnecting) stopReconnecting()
        val taught = ChetakData.decoder(app, LearnField.SOC) != null
        status(if (taught) "Connected · waiting for battery data" else "Connected · set up the battery reading in More › Chetak setup")
        chetakTick(generation)
    }

    /** Every 5 s: read the readable values again (they change too) and keep the last battery reading fresh. */
    private fun chetakTick(attempt: Int) {
        main.postDelayed({
            if (attempt != generation || phase != LinkPhase.READY) return@postDelayed
            if (queue.size < 4) chetakReadable.forEach { readChar(it, tries = 1) }
            val soc = lastSoc
            val now = System.currentTimeMillis()
            if (soc != null && now - lastSocEmitAt >= 10_000) emitSoc(soc, now)
            chetakTick(attempt)
        }, 5_000)
    }

    private fun onChetakData(uuid: UUID, value: ByteArray, now: Long) {
        val u = uuid.toString().lowercase()
        ChetakData.ingest(u, value, now)
        val hex = value.hex()
        if (chetakLogged[u] != hex && now - (chetakLogAt[u] ?: 0L) >= 5_000) {
            chetakLogged[u] = hex; chetakLogAt[u] = now
            log("Chetak ${shortUuid(u)} (${value.size} B): $hex")
        }
        ChetakData.decoder(app, LearnField.SOC)?.read(u, value, LearnField.SOC)?.let { emitSoc(it, now) }
        ChetakData.decoder(app, LearnField.RANGE)?.read(u, value, LearnField.RANGE)?.let { decodedRangeKm = it }
    }

    private fun emitSoc(soc: Double, now: Long) {
        val first = lastBmsAt == 0L
        lastSoc = soc; lastSocEmitAt = now; lastBmsAt = SystemClock.elapsedRealtime()
        received(Reading(0, Metric.SOC, soc, now))
        if (first && phase == LinkPhase.READY) status("Connected · battery data live")
    }

    /** The learned fields changed (More › Chetak setup): read again so the result shows at once. */
    fun chetakFieldsChanged() {
        if (phase == LinkPhase.READY && chetakMode) refresh()
    }

    private fun propertyLetters(p: Int): String = buildString {
        if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) append('R')
        if (p and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) append('W')
        if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) append('N')
        if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) append('I')
    }

    // --- Serialized GATT operations: one outstanding request, matched by kind + UUID ---

    private enum class Kind { READ, WRITE, DESCRIPTOR, MARKER }
    private class GattOp(
        val kind: Kind, val uuid: UUID?, val label: String, val critical: Boolean, val maxTries: Int,
        val replaceable: Boolean = false, val start: (BluetoothGatt) -> Boolean
    ) {
        var tries = 0
    }

    private val queue = ArrayDeque<GattOp>()
    private var running: GattOp? = null
    private var watchdog: Runnable? = null
    private var retryPending = false

    private fun marker(action: () -> Unit) = enqueue(GattOp(Kind.MARKER, null, "marker", false, 1) { action(); true })

    private fun notify(uuid: UUID, critical: Boolean) = enqueue(GattOp(Kind.DESCRIPTOR, uuid, "notify ${uuid.short()}", critical, 3) { g ->
        val c = characteristic(uuid) ?: return@GattOp false
        val d = c.getDescriptor(Tcu.CCCD) ?: return@GattOp false
        val value = when {
            c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            else -> return@GattOp false
        }
        if (!g.setCharacteristicNotification(c, true)) return@GattOp false
        if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, value) == BluetoothGatt.GATT_SUCCESS
        else @Suppress("DEPRECATION") run { d.value = value; g.writeDescriptor(d) }
    })

    /** Chetak mode: subscribe to this exact characteristic (UUIDs may repeat across services). */
    private fun notifyChar(c: BluetoothGattCharacteristic) = enqueue(GattOp(Kind.DESCRIPTOR, c.uuid, "notify ${c.uuid.short()}", false, 2) { g ->
        val d = c.getDescriptor(Tcu.CCCD) ?: return@GattOp false
        val value = if (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        if (!g.setCharacteristicNotification(c, true)) return@GattOp false
        if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, value) == BluetoothGatt.GATT_SUCCESS
        else @Suppress("DEPRECATION") run { d.value = value; g.writeDescriptor(d) }
    })

    /** Chetak mode: read this exact characteristic. */
    private fun readChar(c: BluetoothGattCharacteristic, tries: Int) =
        enqueue(GattOp(Kind.READ, c.uuid, "read ${c.uuid.short()}", false, tries) { g -> g.readCharacteristic(c) })

    private fun read(uuid: UUID, critical: Boolean) = enqueue(GattOp(Kind.READ, uuid, "read ${uuid.short()}", critical, 3) { g ->
        characteristic(uuid)?.let(g::readCharacteristic) ?: false
    })

    private fun write(uuid: UUID, value: ByteArray, critical: Boolean, maxTries: Int = 3, replaceable: Boolean = false, priority: Boolean = false) =
        enqueue(GattOp(Kind.WRITE, uuid, "write ${uuid.short()}", critical, maxTries, replaceable) { g ->
            val c = characteristic(uuid) ?: return@GattOp false
            // Write with response; fall back if the characteristic only allows no-response.
            val type = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0 &&
                c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(c, value, type) == BluetoothGatt.GATT_SUCCESS
            else @Suppress("DEPRECATION") run { c.writeType = type; c.value = value; g.writeCharacteristic(c) }
        }, priority)

    private fun enqueue(op: GattOp, priority: Boolean = false) {
        if (gatt == null) return
        if (priority) { enqueuePriority(op); pump(); return }
        // Periodic frames (phone status, caller, TBT, music): a newer one takes the queued one's
        // place, so the queue holds at most one per characteristic
        // and a refresh never loses its turn to the next tick.
        val stale = if (op.replaceable) queue.firstOrNull { it !== running && it.replaceable && it.kind == op.kind && it.uuid == op.uuid } else null
        if (stale != null) {
            val ops = queue.toList()
            queue.clear()
            ops.forEach { queue.addLast(if (it === stale) op else it) }
        } else queue.addLast(op)
        pump()
    }

    /**
     * Priority lane (2.1 F4, turn-by-turn frames; rule in [PriorityLane]): [op] goes directly after
     * the running op and replaces any queued, not yet running frame, so a new 0x0303 frame never
     * waits behind periodic phone/music/stats writes or a stale frame. The running op stays at the
     * head (callbacks still match it by kind + UUID; the 35 s watchdog keeps watching it), markers
     * are never overtaken, and frames alternate with other ops while one is running, so heartbeat
     * replies (0x0103) are never starved.
     */
    private fun enqueuePriority(op: GattOp) {
        val ops = PriorityLane.insert(queue.toList(), op, running,
            sameLane = { it.replaceable && it.kind == op.kind && it.uuid == op.uuid }, isMarker = { it.kind == Kind.MARKER })
        queue.clear()
        ops.forEach(queue::addLast)
    }

    private fun pump() {
        if (running != null || retryPending) return
        val g = gatt ?: return
        while (true) {
            val op = queue.peekFirst() ?: return
            // A marker action may enqueue (and so start) an op itself; never start the head twice.
            if (op.kind == Kind.MARKER) { queue.pollFirst(); op.start(g); if (gatt == null || running != null || retryPending) return; continue }
            op.tries++
            if (op.start(g)) {
                running = op
                val attempt = generation
                // Never start another request while this one may still be pending; Android
                // either answers or drops the link after its 30 s ATT timeout.
                val dog = Runnable { if (attempt == generation && running === op) fail("Scooter stopped answering (${op.label}). Tap Connect to retry.") }
                watchdog = dog; main.postDelayed(dog, 35_000)
                return
            }
            if (op.tries < op.maxTries) {
                retryPending = true
                val attempt = generation
                main.postDelayed({ if (attempt == generation) { retryPending = false; pump() } }, 250)
                return
            }
            queue.pollFirst()
            writeOutcome(op, -1)
            giveUp(op, "could not start")
            if (gatt == null) return
        }
    }

    private fun complete(kind: Kind, uuid: UUID, code: Int) {
        val op = running
        if (op == null || op.kind != kind || op.uuid != uuid) { log("Ignored unexpected ${kind.name.lowercase()} callback for ${uuid.short()}"); return }
        running = null
        watchdog?.let(main::removeCallbacks); watchdog = null
        when {
            code == BluetoothGatt.GATT_SUCCESS -> { queue.pollFirst(); writeOutcome(op, null) }
            op.tries < op.maxTries -> log("${op.label} failed (GATT $code); retrying")
            else -> { queue.pollFirst(); writeOutcome(op, code); giveUp(op, "failed (GATT $code)") }
        }
        pump()
    }

    /** Records the final outcome of a write (failure null = acknowledged by the TCU; -1 = could not start). */
    private fun writeOutcome(op: GattOp, failure: Int?) {
        val uuid = op.uuid
        if (op.kind != Kind.WRITE || uuid == null) return
        val now = SystemClock.elapsedRealtime()
        if (failure == null) ackedAt[uuid] = now else failedAt[uuid] = now
        if (uuid == Tcu.NAV_INFO) {
            if (failure == null) { navigationAckedAt = now; navigationError = null } else navigationError = failure
        }
        if (failure != null && (uuid == Tcu.MUSIC_INFO || uuid == Tcu.MUSIC_META)) {
            // Resend everything after a pause; a TCU that rejects music is not hammered every second.
            lastMusicInfo = null; lastMeta = null; musicRetryAt = now + 10_000
        }
    }

    private fun giveUp(op: GattOp, why: String) {
        log("${op.label} $why")
        if (op.critical) fail("Scooter link setup failed: ${op.label} $why. Tap Connect to retry.")
    }

    private fun UUID.short() = toString().substring(4, 8)

    private companion object {
        /** BluetoothDevice's hidden UNBOND_REASON_* extra on a bond-state broadcast. */
        const val EXTRA_UNBOND_REASON = "android.bluetooth.device.extra.REASON"
        /** Wait for the bond broadcast after the link drops mid-bond. */
        const val BOND_RESULT_WAIT_MS = 1_500L
        /** Still bonding without our receiver seeing the pairing request: an OEM dialog is asking. */
        const val SYSTEM_DIALOG_CHECK_MS = 5_000L
        const val NO_RESPONSE = "Scooter did not respond. Keep it on and nearby, close the scooter maker's official app, then try again."
        const val RECONNECT_FAILED = "Lost the scooter link and could not reconnect. Tap Connect to try again."
        /** A little past [RideMath.LINK_LOST_MS], so a ride in progress ends as LINK_LOST before the service stops. */
        const val RECONNECT_WINDOW_MS = RideMath.LINK_LOST_MS + 15_000L
        val RECONNECT_DELAYS_MS = longArrayOf(2_000, 5_000, 10_000, 15_000)
        /** 2.1 F2: stats tags asked for once per connection, [AUTO_PROBE_DELAY_MS] after READY. */
        val AUTO_PROBE_TAGS = listOf(2, 3, 5, 6, 10, 11)
        const val AUTO_PROBE_DELAY_MS = 10_000L
        /** Road-name width test: "12345678" … "123456789012". */
        val ROAD_WIDTH_NAMES: List<String> = (8..12).map { length -> (1..length).joinToString("") { (it % 10).toString() } }
    }
}
