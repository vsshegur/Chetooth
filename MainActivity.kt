package dev.codesurgen.ruta

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.SystemClock
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.max

private const val LIVE_MS = 15_000L
private const val FRESH_MS = 90_000L
private const val MAPS = "com.google.android.apps.maps"
/** Preference: connect to the active scooter when Ruta opens and when Bluetooth turns on (default on). */
private const val AUTO_CONNECT = "auto_connect"
/** Preference: MAC of the scooter whose readings are in the history store (last-known values on launch). */
private const val DATA_MAC = "data_mac"
/** Share-sheet text cap (Binder transactions are limited to ~1 MB). */
private const val SHARE_LIMIT = 200_000
/**
 * QA / ADB (F-DEMO): `--es demo parked|riding|navigating|charging|call|music|off` starts that demo
 * scenario (or ends demo mode); `--ez demo_seed true` (re)seeds the demo history.
 */
const val EXTRA_DEMO = "demo"
const val EXTRA_DEMO_SEED = "demo_seed"

// Request codes. 41 is also what ScooterConnection.connect() uses when it has to ask itself.
private const val REQUEST_BLUETOOTH = 41
private const val REQUEST_RIDE = 42
private const val REQUEST_LOCATION = 43
private const val REQUEST_PHONE = 44
private const val REQUEST_ANSWER = 45
private const val REQUEST_NOTIFICATIONS = 46
private const val REQUEST_EXPORT = 71

private val LOCATION = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
private val PHONE_INFO = arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS)
private val ANSWER = arrayOf(Manifest.permission.ANSWER_PHONE_CALLS)

/**
 * Ruta shell and [Host]: edge-to-edge root with the five tab screens (fade-through), the M3
 * navigation bar, a snackbar host, bottom sheets and the full-screen onboarding / add-scooter flow.
 * Owns the [ScooterConnection] and feeds everything that depends on it: [RideInputs] (ride
 * tracking), [RideService] (keeps link and GPS alive), the history store and the diagnostics log
 * ([DiagLog]: logcat tag Ruta + log file). Framework Activity only; coroutines run on
 * [Ui.dispatcher].
 *
 * Navigation rules (W3): every tab lives in its own [TouchGate] slot; a switch closes the outgoing
 * slot to touches at once and puts the incoming one on top ([Motion.fadeThrough]); selecting the
 * current tab is a no-op; only the navigation bar and explicit affordances change tab; a jump made
 * by the app ([selectTab], [openRide], change PIN) remembers where it came from so back returns
 * there; the tab and that origin survive recreation (saved state). [checkNavigation] verifies the
 * invariants after every switch and logs any violation.
 *
 * Demo mode (F-DEMO, [Demo]): Host is fed from [Demo.live] instead of the link (same members, same
 * screens), [store] is the separate demo history (seeded on start, deleted on exit), scooter actions
 * and Bluetooth stay untouched (a snackbar offers "Exit demo"), nothing is written to the rider's
 * history and RideService is never started. Starts from Welcome ("Explore demo"), More ›
 * Diagnostics, or the `demo` intent extra; refused while the scooter is linked. Ending it restores the
 * real state and reconnects as on launch (or shows Welcome again when no scooter was added).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainActivity : Activity(), Host {
    private val ui = Ui.scope()
    /** One storage thread keeps writes ordered. */
    private val io = Dispatchers.IO.limitedParallelism(1)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var telemetry: TelemetryStore
    private lateinit var connection: ScooterConnection
    private lateinit var prefs: SharedPreferences
    private val analytics = BatteryAnalytics()

    // Scooter data and link state
    private var vehicleState: VehicleState? = null
    private var tcu: TcuInfo? = null
    private var dataMac: String? = null
    private var lastReadingAt = 0L
    private var connectedAt = 0L
    /** Last connection failure in plain words (chip tooltip), null when fine. */
    private var lastError: String? = null
    private var storageErrorShown = false
    private var visible = false
    private var renderPosted = false
    private val renderTask = Runnable { renderPosted = false; render() }
    // The ride notification's Disconnect is a rider's disconnect: Bluetooth turning on again must not reconnect.
    private val stopHandle: () -> Unit = { userDisconnected = true; if (::connection.isInitialized) connection.disconnect() }
    /** The link dropped by itself and is being retried: the reconnect is quiet (no "Scooter connected"). */
    private var linkWasLost = false
    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refreshScooters()
            if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) == BluetoothAdapter.STATE_ON && !userDisconnected) {
                autoConnect("Bluetooth turned on")
            }
            scheduleRender()
        }
    }
    private var appliedConfig: Configuration? = null

    // Rides and range efficiency (reloaded when the tracker saves something)
    private var lastRideCache: Ride? = null
    private var loadedRevision = -1
    private var rideJob: Job? = null
    private var efficiency = RangeEfficiency.DEFAULT

    // Automatic connection to the active scooter (launch, Bluetooth on)
    private var autoConnectPending = false
    /** The current attempt was started automatically: a failure gets a calm message. */
    private var autoAttempt = false
    /** The rider disconnected by hand: Bluetooth turning on again does not reconnect. */
    private var userDisconnected = false
    /** Connect was asked for before Bluetooth access was granted: continue once it is. */
    private var pendingConnect: String? = null

    // Saved scooters (SecureStore), cached on the main thread and refreshed by its listener.
    private var saved: List<SavedScooter> = emptyList()
    private var active: SavedScooter? = null
    private var bonded: List<ScooterDevice> = emptyList()
    private val scootersListener: () -> Unit = { reloadSaved(); scheduleRender() }
    private val pairingListener: (String, PairingEvent) -> Unit = { _, event -> onPairingEvent(event) }

    private val navListener: () -> Unit = { scheduleRender() }
    private val phoneListener: () -> Unit = { scheduleRender() }
    private val rideListener: () -> Unit = {
        if (RideLive.state.revision != loadedRevision) loadRideData()
        scheduleRender()
    }
    private val logListeners = LinkedHashMap<(String) -> Unit, () -> Unit>()

    // Shell
    private var tab = Destination.HOME
    private lateinit var root: FrameLayout
    private lateinit var shell: LinearLayout
    private lateinit var content: FrameLayout
    private lateinit var nav: NavigationBar
    private lateinit var snackbars: SnackbarHost
    private val screens = arrayOfNulls<Screen>(Destination.entries.size)
    /** Each tab's frame in [content]; a closed gate takes no touches (outgoing during a fade-through). */
    private val slots = arrayOfNulls<TouchGate>(Destination.entries.size)
    private var home: HomeScreen? = null
    /** The last tab change the app made by itself; back on [Jump.to] returns to [Jump.from]. */
    private var jump: Jump? = null
    private val navCheck = Runnable { checkNavigation() }
    private val navProblems = HashSet<String>()
    private val failedScreens = mutableSetOf<String>()
    private var backCallback: OnBackInvokedCallback? = null
    private var backRegistered = false
    private val sheets = mutableListOf<BottomSheet>()
    private var systemBottom = 0
    private var imeBottom = 0

    // Onboarding / add-scooter overlay (over the navigation bar)
    private var onboarding: Screen? = null
    private var onboardingGate: TouchGate? = null
    private var onboardingAddOnly = false

    // Demo mode (F-DEMO)
    /** Handle on the demo history file while demo mode is on (never the rider's file). */
    private var demoStore: TelemetryStore? = null
    private var demoSheet: BottomSheet? = null
    /** The demo store's newest ride once loaded ([demoRidesLoaded]); before that the in-memory one. */
    private var demoLastRide: Ride? = null
    private var demoRidesLoaded = false
    /** The demo history file holds the seeded month (seeding runs on the storage thread). */
    private var demoSeeded = false
    /** Start of the demo's live charging session as last written to the demo history (0 = none). */
    private var demoChargeStart = 0L
    private var demoChargeSavedAt = 0L
    /** An export started in demo mode writes the demo history (and only while it is still on). */
    private var exportDemo = false
    private val demoListener: () -> Unit = { scheduleRender() }
    /** Alerts while Ruta is in front: a snackbar (also how they arrive with notifications off). */
    private val alertSink: (String) -> Unit = { text -> if (visible && !Demo.active) showSnackbar(text) }

    /**
     * A tab change the app made (not the navigation bar): [closesSubPage] = it opened a sub-page
     * (ride detail) so closing that sub-page with back also returns to [from]; the screen drops that
     * ([subPageClosed]) when it leaves the sub-page by itself.
     */
    private data class Jump(val from: Destination, val to: Destination, val closesSubPage: Boolean)

    // ================================================================ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Process-wide roles: re-read on recreation. Palette (wallpaper) changes arrive in onConfigurationChanged (assetsPaths) and rebuild the shell in place.
        if (Theme.initialized) Theme.reload(this) else Theme.init(this)
        DiagLog.init(this)
        Icons.onMissing = { DiagLog.append(it) }
        NavBridge.log = DiagLog::append
        RideLive.log = DiagLog::append
        PhoneBridge.log = DiagLog::append
        prefs = getSharedPreferences("companion", MODE_PRIVATE)
        telemetry = TelemetryStore.shared(this)
        savedInstanceState?.let { state ->
            tab = Destination.entries.getOrElse(state.getInt("tab", 0)) { Destination.HOME }
            val from = Destination.entries.getOrNull(state.getInt("jump_from", -1))
            if (from != null && from != tab) jump = Jump(from, tab, state.getBoolean("jump_sub", false))
        }
        runCatching { Scooters.migrateFrom20(this) }.onFailure { appendLog("Saved scooter import failed: ${it.javaClass.simpleName}") }
        reloadSaved()
        Scooters.addListener(scootersListener)
        PairingEvents.addListener(pairingListener)
        // Also after a recreation that dropped a live link (e.g. a configuration change Android still restarts for).
        autoConnectPending = (savedInstanceState == null || savedInstanceState.getBoolean("linked", false)) && prefs.getBoolean(AUTO_CONNECT, true)
        prepareWindow()
        connection = ScooterConnection(
            this,
            status = ::onConnectionStatus,
            log = ::appendLog,
            received = ::onReading,
            vehicle = { onVehicleState(it) },
            info = { tcu = it; scheduleRender() },
            connected = ::onConnected,
            ended = ::onEnded
        )
        connection.navigationEnabled = prefs.getBoolean("nav_forward", true)
        NavBridge.addListener(navListener)
        PhoneBridge.addListener(phoneListener)
        RideLive.addListener(rideListener)
        Demo.controller = { scenario -> setDemo(scenario) }
        Demo.onChip = { showDemoSheet() }
        Demo.addListener(demoListener)
        appliedConfig = Configuration(resources.configuration)
        setContentView(buildShell())
        showTab(tab)
        // Demo mode: kept across a recreation; a fresh launch starts real unless the intent asks for it.
        if (savedInstanceState != null) restoreDemo(savedInstanceState)
        else {
            if (Demo.active) stopDemoState() // Left over from an activity that ended without finishing it.
            handleDemoIntent(intent)
        }
        // A demo file left behind (Ruta was killed during a demo) is never shown as history: it goes.
        if (!Demo.active) ui.launch(io) { runCatching { TelemetryStore.deleteDemo(this@MainActivity) } }
        if (saved.isEmpty() && !Demo.active) showOnboarding(addOnly = false)
        if (!Demo.active) ui.launch(io) { runCatching { telemetry.prune(System.currentTimeMillis()) } }
        loadRideData()
        loadLastKnown()
        ui.launch { while (isActive) { tick(); delay(1_000) } }
    }

    override fun onStart() {
        super.onStart()
        visible = true
        // Bluetooth toggled from quick settings does not resume the activity.
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(bluetoothReceiver, filter)
        // Now playing, calls and counts while Ruta is in front (the scooter link holds it while connected).
        PhoneBridge.start(this, this)
        Alerts.inApp = alertSink
        foreground()?.let { (name, screen) -> screenCall(name, "show", Unit) { screen.onShow() } }
        render()
    }

    override fun onResume() {
        super.onResume()
        PhoneBridge.refreshAccess()
        if (listenerAccessAsked) {
            listenerAccessAsked = false
            if (!notificationAccess()) showSnackbar("Toggle greyed out? App info › ⋮ › Allow restricted settings", "App info") { openAppSettings() }
        }
        // Location may have been allowed in App info, or the service started while it was missing: the
        // location foreground type can only be added while Ruta is visible.
        if (hasLocation()) RideService.refresh(this)
        refreshScooters()
        if (autoConnectPending) { autoConnectPending = false; autoConnect("Chetooth opened") }
        render()
    }

    override fun onStop() {
        visible = false
        if (Alerts.inApp === alertSink) Alerts.inApp = null
        runCatching { unregisterReceiver(bluetoothReceiver) }
        foreground()?.let { (name, screen) -> screenCall(name, "hide", Unit) { screen.onHide() } }
        PhoneBridge.stop(this)
        super.onStop()
    }

    /**
     * Rotation, size, dark mode and similar changes arrive here (manifest configChanges) so the
     * scooter link survives them. Text size, display size, language and a new wallpaper palette
     * need fresh views; everything else re-measures on its own.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val old = appliedConfig
        appliedConfig = Configuration(newConfig)
        val palette = Theme.reload(this)
        if (old == null || !::root.isInitialized) return
        val sameText = old.fontScale == newConfig.fontScale && old.densityDpi == newConfig.densityDpi && old.locales == newConfig.locales
        if (sameText && !palette) return
        if (palette) window.setBackgroundDrawable(ColorDrawable(Theme.background))
        rebuildShell()
    }

    private fun rebuildShell() {
        hideKeyboard()
        val reopen = onboarding != null
        val addOnly = onboardingAddOnly
        dismissSheets()
        disposeScreens()
        removeOnboarding()
        setContentView(buildShell())
        root.requestApplyInsets()
        refreshScooters()
        showTab(tab)
        if (reopen) showOnboarding(addOnly)
    }

    private fun disposeScreens() {
        if (visible && onboarding == null) screens[tab.ordinal]?.let { screenCall(tab.label, "hide", Unit) { it.onHide() } }
        screens.forEachIndexed { index, screen -> screen?.let { screenCall(Destination.entries[index].label, "dispose", Unit) { it.dispose() } } }
        screens.fill(null)
        slots.fill(null)
        home = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", tab.ordinal)
        jump?.takeIf { it.to == tab }?.let { outState.putInt("jump_from", it.from.ordinal); outState.putBoolean("jump_sub", it.closesSubPage) }
        if (::connection.isInitialized) outState.putBoolean("linked", connection.phase != LinkPhase.IDLE || connection.reconnecting)
        val demo = Demo.scenario
        val history = Demo.history
        if (demo != null && history != null) {
            outState.putString("demo", demo.key)
            outState.putLong("demo_seed_at", history.seedAt)
            outState.putLong("demo_elapsed", System.currentTimeMillis() - Demo.startedAt)
        }
    }

    /** singleTop: QA's `am start … --es demo riding` reaches the running activity here. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDemoIntent(intent)
    }

    override fun onDestroy() {
        NavBridge.removeListener(navListener)
        PhoneBridge.removeListener(phoneListener)
        RideLive.removeListener(rideListener)
        Demo.removeListener(demoListener)
        if (Alerts.inApp === alertSink) Alerts.inApp = null
        if (Demo.onChip != null) Demo.onChip = null
        Demo.controller = null
        demoSheet = null
        val demoHandle = demoStore
        demoStore = null
        if (isFinishing && Demo.active) {
            // Ruta is closing: demo mode ends with it (the next launch is the real app) and its file goes.
            Demo.end()
            val app = applicationContext
            Thread { runCatching { demoHandle?.retire() }; runCatching { TelemetryStore.deleteDemo(app) } }.start()
        } else runCatching { demoHandle?.close() }
        Scooters.removeListener(scootersListener)
        PairingEvents.removeListener(pairingListener)
        logListeners.values.forEach(DiagLog::removeListener)
        logListeners.clear()
        dismissSheets()
        disposeScreens()
        removeOnboarding()
        if (::connection.isInitialized) connection.close()
        NavBridge.stop()
        handler.removeCallbacksAndMessages(null)
        ui.cancel()
        super.onDestroy()
    }

    /** The screen in front: the onboarding flow while it shows, else the current tab. */
    private fun foreground(): Pair<String, Screen>? =
        onboarding?.let { "Onboarding" to it } ?: screens[tab.ordinal]?.let { tab.label to it }

    // ================================================================ back

    /**
     * Back: onboarding steps; then the tab's sub-page (detail, sub-list); then the tab the app jumped
     * from (e.g. Home after History or a ride opened from Home — also right after closing a sub-page
     * the jump opened); else Home. On Home the system handles it (sheets are their own windows).
     */
    private fun handleBack(): Boolean {
        onboarding?.let { flow ->
            if (screenCall("Onboarding", "back", false) { flow.onBack() }) return true
            if (onboardingAddOnly) closeOnboarding(null) else moveTaskToBack(true)
            return true
        }
        if (tab == Destination.HOME) return false
        val origin = jump?.takeIf { it.to == tab }
        if (screens[tab.ordinal]?.let { screenCall(tab.label, "back", false) { it.onBack() } } == true) {
            if (origin?.closesSubPage == true) { jump = null; showTab(origin.from) }
            return true
        }
        jump = null
        showTab(origin?.from ?: Destination.HOME)
        return true
    }

    /** API 33+: a registered callback always consumes back, so it is registered only when back is ours. */
    private fun updateBackCallback() {
        if (Build.VERSION.SDK_INT < 33) return
        val wanted = onboarding != null || tab != Destination.HOME
        if (wanted == backRegistered) return
        val callback = backCallback ?: OnBackInvokedCallback { handleBack() }.also { backCallback = it }
        if (wanted) onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
        else onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback)
        backRegistered = wanted
    }

    /** Android 11-12, and 13+ when the system still routes back through key events. */
    @Deprecated("Superseded by OnBackInvokedCallback on API 33+")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (!handleBack()) super.onBackPressed()
    }

    // ================================================================ shell

    private fun prepareWindow() {
        window.setBackgroundDrawable(ColorDrawable(Theme.background))
        @Suppress("DEPRECATION") // Needed on Android 11-14; Android 15+ is edge-to-edge regardless.
        run {
            window.setDecorFitsSystemWindows(false)
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            window.isStatusBarContrastEnforced = false
        }
        window.isNavigationBarContrastEnforced = false
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        // The UI is dark already; keep HyperOS per-app dark mode and "make more apps dark" from recolouring it.
        window.decorView.isForceDarkAllowed = false
        window.insetsController?.setSystemBarsAppearance(
            0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        )
    }

    /**
     * Root FrameLayout { column { content (weight 1); NavigationBar }; [onboarding]; SnackbarHost }.
     * The insets listener pads sides and the navigation bar and returns the insets (not CONSUMED) so
     * every screen's TopAppBar still receives the status-bar inset.
     */
    private fun buildShell(): View {
        root = FrameLayout(this).apply { setBackgroundColor(Theme.background) }
        shell = column()
        content = FrameLayout(this)
        shell.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))
        nav = NavigationBar(this, Destination.entries.map { NavItem(it.label, it.icon) }, tab.ordinal) { index ->
            nav.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            jump = null // The rider chose the tab: back goes Home from here.
            showTab(Destination.entries[index])
        }
        nav.onReselect = { index ->
            jump = null
            val screen = screens[index]
            val handled = screen?.let { screenCall(Destination.entries[index].label, "back", false) { it.onBack() } } == true
            if (!handled) {
                if (screen is RidesScreen) screen.scrollToTop() else (screen?.view as? Scaffold)?.page?.scrollToTop()
            }
        }
        shell.addView(nav, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(shell, FrameLayout.LayoutParams(MATCH, MATCH))
        snackbars = SnackbarHost(this)
        // M3: a snackbar sits above the foreground screen's FAB (Connect, Maps), never over it.
        // During onboarding it sits above the flow's bottom action (Continue, Cancel / Try again).
        snackbars.anchor = {
            when {
                imeBottom > 0 -> null
                onboarding != null -> (onboarding as? OnboardingFlow)?.bottomAction?.takeIf { it.isShown }
                else -> screens[tab.ordinal]?.view?.findShownFab()
            }
        }
        root.addView(snackbars, FrameLayout.LayoutParams(MATCH, MATCH))
        nav.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateSnackbarOffset() }
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val imeVisible = insets.isVisible(WindowInsets.Type.ime())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            systemBottom = bars.bottom
            imeBottom = if (imeVisible) ime.bottom else 0
            shell.setPadding(bars.left, 0, bars.right, imeBottom)
            nav.applyBottomInset(bars.bottom)
            nav.visible(!imeVisible)
            updateSnackbarOffset()
            insets
        }
        return root
    }

    /** Snackbars sit above the navigation bar, the keyboard, or the system bar under the onboarding flow. */
    private fun updateSnackbarOffset() {
        if (!::snackbars.isInitialized) return
        snackbars.bottomOffset = when {
            imeBottom > 0 -> imeBottom
            onboarding != null -> systemBottom
            nav.visibility == View.VISIBLE && nav.height > 0 -> nav.height
            else -> systemBottom
        }
    }

    /** Creates a tab's screen on first use; its view stays in [content] until the shell is rebuilt. */
    private fun screen(target: Destination): Screen {
        screens[target.ordinal]?.let { return it }
        val created: Screen = guarded(target) {
            when (target) {
                Destination.HOME -> HomeScreen(this).also { home = it }
                Destination.NAVIGATE -> NavigateScreen(this)
                Destination.RIDES -> RidesScreen(this)
                Destination.INSIGHTS -> InsightsScreen(this)
                Destination.MORE -> MoreScreen(this)
            }
        }
        val slot = TouchGate(this)
        (created.view.parent as? android.view.ViewGroup)?.removeView(created.view)
        slot.addView(created.view, FrameLayout.LayoutParams(MATCH, MATCH))
        slot.visibility = View.GONE
        content.addView(slot, FrameLayout.LayoutParams(MATCH, MATCH))
        screens[target.ordinal] = created
        slots[target.ordinal] = slot
        return created
    }

    /**
     * A failing screen must not take the scooter link down with it (a crash ends the ride's BLE
     * link): its errors are logged once and the call is skipped.
     */
    private inline fun <T> screenCall(name: String, what: String, fallback: T, block: () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (e: Exception) {
        if (failedScreens.add("$name.$what")) appendLog("UI error in $name ($what): ${e.javaClass.simpleName}: ${e.message}")
        fallback
    }

    private fun guarded(target: Destination, create: () -> Screen): Screen = screenCall(target.label, "create", null) { create() }
        ?: object : Screen {
            private val bar = TopAppBar(this@MainActivity).apply { title = target.label }
            override val view: View = Scaffold(this@MainActivity, bar).apply {
                page.add(emptyState("warning", "Something went wrong", null, tonalButton("Copy log", "content_copy") { copyLog() }), Spacing.XL)
            }
        }

    /**
     * Shows [target] with a fade-through. Idempotent: the tab already in front is left alone (no
     * restarted animation, no second onShow). A screen created now (first visit, or after the shell
     * was rebuilt) gets onShow even without a switch. Tabs still fading from earlier rapid taps are
     * hidden and closed at once. [hideOutgoing] false when the outgoing tab was already hidden
     * (under the onboarding flow).
     */
    private fun showTab(target: Destination, hideOutgoing: Boolean = true) {
        val previous = tab
        val switching = target != previous
        val existed = screens[target.ordinal] != null
        if (!switching && existed && slots[target.ordinal]?.let { it.visibility == View.VISIBLE && it.touchable } == true) {
            nav.select(target.ordinal, animate = false)
            updateBackCallback()
            return
        }
        val outgoing = screens[previous.ordinal]
        if (switching) hideKeyboard()
        if (visible && switching && hideOutgoing && onboarding == null) outgoing?.let { screenCall(previous.label, "hide", Unit) { it.onHide() } }
        tab = target
        val current = screen(target)
        val incoming = slots[target.ordinal] ?: return
        nav.select(target.ordinal)
        slots.forEachIndexed { index, slot ->
            if (slot != null && index != target.ordinal && index != previous.ordinal) {
                slot.close()
                slot.animate().cancel()
                slot.alpha = 1f; slot.scaleX = 1f; slot.scaleY = 1f
                slot.visible(false)
            }
        }
        // The incoming tab is always the top child: touches reach it first, never a leaving tab.
        if (content.indexOfChild(incoming) != content.childCount - 1) incoming.bringToFront()
        Motion.fadeThrough(if (switching) slots[previous.ordinal] else null, incoming)
        nav.setBadge(Destination.NAVIGATE.ordinal, null)
        updateBackCallback()
        updateShellAccessibility()
        if (visible && onboarding == null && (switching || !existed)) screenCall(target.label, "show", Unit) { current.onShow() }
        render()
        scheduleNavigationCheck()
    }

    /** Runs [checkNavigation] once the current transition has finished. */
    private fun scheduleNavigationCheck() {
        handler.removeCallbacks(navCheck)
        handler.postDelayed(navCheck, Motion.FADE_OUT_MS + Motion.FADE_IN_MS + 150)
    }

    /**
     * Self-check of the shell's navigation invariants after a switch: the current tab's slot is
     * shown, touchable and on top; no other tab is shown; the navigation bar marks the current tab;
     * the onboarding overlay (when present) is on top and touchable. A violation is logged once
     * per kind ("Navigation self-check: …" in Diagnostics), never thrown.
     */
    private fun checkNavigation() {
        if (!::content.isInitialized || !::nav.isInitialized) return
        val problems = ArrayList<String>(2)
        val current = slots[tab.ordinal]
        when {
            current == null -> problems += "${tab.label} has no screen"
            current.visibility != View.VISIBLE -> problems += "${tab.label} is not shown"
            !current.touchable -> problems += "${tab.label} does not take touches"
            content.indexOfChild(current) != content.childCount - 1 -> problems += "${tab.label} is under another tab"
        }
        slots.forEachIndexed { index, slot ->
            if (slot != null && index != tab.ordinal && slot.visibility == View.VISIBLE) problems += "${Destination.entries[index].label} is still shown behind ${tab.label}"
        }
        if (nav.selected != tab.ordinal) problems += "navigation bar marks ${Destination.entries.getOrNull(nav.selected)?.label} instead of ${tab.label}"
        onboardingGate?.let { gate ->
            if (!gate.touchable || root.indexOfChild(gate) < root.indexOfChild(shell)) problems += "onboarding is not in front"
            if (shell.importantForAccessibility != View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS) problems += "the shell is not hidden from TalkBack under onboarding"
        }
        for (problem in problems) if (navProblems.add(problem)) appendLog("Navigation self-check: $problem")
    }

    private fun dismissSheets() {
        sheets.forEach { runCatching { it.dismiss() } }
        sheets.clear()
    }

    // ================================================================ onboarding

    /** Full-screen first-run flow, or More › Add scooter ([addOnly]); lands on Home when done. */
    private fun showOnboarding(addOnly: Boolean) {
        if (onboarding != null || !::root.isInitialized || Demo.active) return
        val flow = screenCall("Onboarding", "create", null) {
            OnboardingFlow(this, addOnly) { result -> Ui.post { closeOnboarding(result) } }
        } ?: return
        hideKeyboard()
        dismissSheets()
        if (visible) screens[tab.ordinal]?.let { screenCall(tab.label, "hide", Unit) { it.onHide() } }
        onboarding = flow
        onboardingAddOnly = addOnly
        val view = flow.view
        (view.parent as? android.view.ViewGroup)?.removeView(view)
        if (view.background == null) view.setBackgroundColor(Theme.background)
        val overlay = TouchGate(this)
        overlay.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
        overlay.isClickable = true // Touches never reach the shell underneath.
        onboardingGate = overlay
        root.addView(overlay, root.indexOfChild(snackbars), FrameLayout.LayoutParams(MATCH, MATCH))
        // TalkBack stays in the flow too: nothing of the shell underneath (tabs, navigation bar) is announced.
        updateShellAccessibility()
        if (Motion.enabled() && isAttachedToWindowCompat()) {
            overlay.alpha = 0f
            overlay.animate().alpha(1f).setDuration(Motion.FADE_IN_MS).setInterpolator(Motion.emphasizedDecelerate).start()
        }
        overlay.requestApplyInsets()
        updateSnackbarOffset()
        updateBackCallback()
        scheduleNavigationCheck()
        if (visible) screenCall("Onboarding", "show", Unit) { flow.onShow() }
        render()
    }

    /** Ends the flow; [result] = the scooter added (connect it and land on Home with the ignition sweep). */
    private fun closeOnboarding(result: SavedScooter?) {
        if (onboarding == null) return
        val wasAddOnly = onboardingAddOnly
        removeOnboarding(animate = true)
        reloadSaved()
        updateSnackbarOffset()
        updateBackCallback()
        // Home after a scooter was added (or the first run); a cancelled Add scooter stays where it began.
        val landHome = result != null || !wasAddOnly
        if (landHome) {
            jump = null
            if (tab != Destination.HOME) showTab(Destination.HOME, hideOutgoing = false)
            else if (visible) screens[tab.ordinal]?.let { screenCall(tab.label, "show", Unit) { it.onShow() } }
        } else if (visible) {
            // showOnboarding hid the tab underneath.
            screens[tab.ordinal]?.let { screenCall(tab.label, "show", Unit) { it.onShow() } }
        }
        if (result != null) {
            val mac = result.mac
            if (active?.mac?.equals(mac, ignoreCase = true) != true) runCatching { Scooters.setActive(this, mac) }
            reloadSaved()
            when {
                connection.phase == LinkPhase.READY && mac.equals(dataMac, ignoreCase = true) -> home?.ignitionSweep()
                connection.phase == LinkPhase.IDLE && !connection.reconnecting -> connect(mac)
            }
            if (wasAddOnly) showSnackbar("${result.nickname.ifBlank { PairingRules.DEFAULT_NICKNAME }} added")
        }
        render()
    }

    private fun removeOnboarding(animate: Boolean = false) {
        val flow = onboarding ?: return
        onboarding = null
        val overlay = onboardingGate
        onboardingGate = null
        // From now on touches belong to the shell, even while the flow fades out above it.
        overlay?.close()
        updateShellAccessibility()
        screenCall("Onboarding", "hide", Unit) { flow.onHide() }
        screenCall("Onboarding", "dispose", Unit) { flow.dispose() }
        if (overlay == null) { (flow.view.parent as? android.view.ViewGroup)?.removeView(flow.view); return }
        overlay.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        if (animate && Motion.enabled() && overlay.isAttachedToWindow) {
            overlay.isClickable = false
            overlay.animate().alpha(0f).setDuration(Motion.FADE_OUT_MS * 2).setInterpolator(Motion.emphasizedAccelerate)
                .withEndAction { (overlay.parent as? FrameLayout)?.removeView(overlay) }.start()
        } else {
            (overlay.parent as? FrameLayout)?.removeView(overlay)
        }
    }

    private fun isAttachedToWindowCompat(): Boolean = ::root.isInitialized && root.isAttachedToWindow

    /**
     * While the onboarding / add-scooter flow covers the shell, the shell root (tabs + navigation bar)
     * is hidden from TalkBack (NO_HIDE_DESCENDANTS), so swiping never lands under the flow; restored
     * when it closes. Re-applied after every tab switch and shell rebuild (checked by [checkNavigation]).
     */
    private fun updateShellAccessibility() {
        if (!::shell.isInitialized) return
        val mode = if (onboarding != null) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        if (shell.importantForAccessibility != mode) shell.importantForAccessibility = mode
    }

    // ================================================================ permissions

    private fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    private fun hasBluetoothAccess() = connection.permissions().all(::granted)
    private fun hasLocation() = granted(Manifest.permission.ACCESS_FINE_LOCATION)
    private fun notificationsAllowed() = Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)

    /** Cheap per-frame check: PhoneBridge re-reads grants every few seconds and on resume while it runs. */
    private fun listenerAccess() = if (PhoneBridge.running) PhoneBridge.access.notifications else notificationAccess()

    private fun notificationAccess() = runCatching {
        getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(ComponentName(this, MapsNavigationListener::class.java))
    }.getOrDefault(false)

    /**
     * After "Don't allow" twice Android stops showing the dialog; requests then fail silently. A group
     * is treated as blocked once a denial came without a rationale, and the grant action opens App info.
     */
    private fun blocked(group: String, missing: List<String>) =
        missing.isNotEmpty() && prefs.getBoolean("blocked_$group", false) && missing.none { shouldShowRequestPermissionRationale(it) }

    private fun request(group: String, permissions: Array<String>, code: Int) {
        val missing = permissions.filterNot(::granted)
        if (missing.isEmpty()) { onAccessChanged(); return }
        // Upgrading Approximate to Precise: Android 12+ ignores a fine-only request, so ask for both.
        // Nearby devices: scan rides along in the same dialog (adding a scooter needs it).
        val ask = when (group) {
            "location" -> permissions.toList()
            "bluetooth" -> missing + listOf(Manifest.permission.BLUETOOTH_SCAN).filter { Build.VERSION.SDK_INT >= 31 && !granted(it) }
            else -> missing
        }
        if (blocked(group, missing)) openAppSettings() else requestPermissions(ask.toTypedArray(), code)
    }

    private fun requestBluetoothAccess() = request("bluetooth", connection.permissions(), REQUEST_BLUETOOTH)
    private fun requestLocation() = request("location", LOCATION, REQUEST_LOCATION)

    private fun state(group: String, permissions: Array<String>): PermissionState {
        val missing = permissions.filterNot(::granted)
        return when {
            missing.isEmpty() -> PermissionState.GRANTED
            blocked(group, missing) -> PermissionState.BLOCKED
            missing.size < permissions.size -> PermissionState.PARTIAL
            else -> PermissionState.DENIED
        }
    }

    override fun permission(permission: AppPermission): PermissionState = if (Demo.active) demoPermission(permission) else when (permission) {
        AppPermission.BLUETOOTH -> state("bluetooth", connection.permissions())
        AppPermission.NOTIFICATION_ACCESS -> if (listenerAccess()) PermissionState.GRANTED else PermissionState.DENIED
        AppPermission.LOCATION -> when {
            hasLocation() -> PermissionState.GRANTED
            blocked("location", LOCATION.filterNot(::granted)) -> PermissionState.BLOCKED
            granted(Manifest.permission.ACCESS_COARSE_LOCATION) -> PermissionState.PARTIAL
            else -> PermissionState.DENIED
        }
        AppPermission.PHONE -> state("phone", PHONE_INFO)
        AppPermission.ANSWER_CALLS -> state("answer", ANSWER)
        AppPermission.NOTIFICATIONS ->
            if (Build.VERSION.SDK_INT < 33) PermissionState.NOT_NEEDED
            else state("notifications", arrayOf(Manifest.permission.POST_NOTIFICATIONS))
    }

    /** Demo mode simulates a fully set-up app (no "Allow" rows over simulated data); real grants return on exit. */
    private fun demoPermission(permission: AppPermission): PermissionState =
        if (permission == AppPermission.NOTIFICATIONS && Build.VERSION.SDK_INT < 33) PermissionState.NOT_NEEDED else PermissionState.GRANTED

    override fun requestPermission(permission: AppPermission) {
        if (Demo.active) return
        when (permission) {
            AppPermission.BLUETOOTH -> requestBluetoothAccess()
            AppPermission.NOTIFICATION_ACCESS -> openNotificationAccess()
            AppPermission.LOCATION -> requestLocation()
            AppPermission.PHONE -> request("phone", PHONE_INFO, REQUEST_PHONE)
            AppPermission.ANSWER_CALLS -> request("answer", ANSWER, REQUEST_ANSWER)
            AppPermission.NOTIFICATIONS ->
                if (Build.VERSION.SDK_INT >= 33) request("notifications", arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    override fun openAppSettings() {
        try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))) }
        catch (_: ActivityNotFoundException) { showSnackbar("Open Settings › Apps › Chetooth") }
    }

    override fun openLocationSettings() {
        try { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
        catch (_: ActivityNotFoundException) { showSnackbar("Turn on Location in quick settings") }
    }

    /**
     * Set when the notification-access page was opened on Android 13+. An app installed from a
     * browser or file manager is "restricted" there: the toggle stays greyed out until App info ›
     * ⋮ › Allow restricted settings. Back without access, [onResume] says so once.
     */
    private var listenerAccessAsked = false

    private fun openNotificationAccess() {
        listenerAccessAsked = Build.VERSION.SDK_INT >= 33
        val component = ComponentName(this, MapsNavigationListener::class.java).flattenToString()
        val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component)
        try { startActivity(detail) } catch (_: ActivityNotFoundException) {
            try { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            catch (_: ActivityNotFoundException) { showSnackbar("Open Settings › Notification access") }
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isEmpty()) return // Dialog dismissed without an answer.
        val results = permissions.indices.associate { permissions[it] to (grantResults.getOrNull(it) == PackageManager.PERMISSION_GRANTED) }
        // Remember groups Android will no longer ask for, per group.
        fun note(group: String, members: Array<String>) {
            val asked = members.filter { it in results }
            if (asked.isEmpty()) return
            val denied = asked.filter { results[it] == false }
            val stuck = denied.isNotEmpty() && denied.none { shouldShowRequestPermissionRationale(it) }
            prefs.edit().putBoolean("blocked_$group", stuck).apply()
        }
        note("bluetooth", connection.permissions())
        note("location", LOCATION)
        note("phone", PHONE_INFO)
        note("answer", ANSWER)
        if (Build.VERSION.SDK_INT >= 33) note("notifications", arrayOf(Manifest.permission.POST_NOTIFICATIONS))

        if (requestCode == REQUEST_BLUETOOTH) {
            val mac = pendingConnect
            pendingConnect = null
            if (hasBluetoothAccess()) {
                appendLog("Nearby devices allowed")
                if (mac != null && connection.phase == LinkPhase.IDLE) startConnection(mac)
            } else {
                appendLog("Nearby devices not allowed")
                if (onboarding == null) showSnackbar("Allow Nearby devices to connect", "Allow") { requestPermission(AppPermission.BLUETOOTH) }
            }
        }
        if (results[Manifest.permission.ACCESS_FINE_LOCATION] == true) appendLog("Location allowed: rides will be recorded")
        else if (results.containsKey(Manifest.permission.ACCESS_FINE_LOCATION)) appendLog("Location not allowed: rides are not recorded")
        onAccessChanged()
    }

    /** Re-evaluates everything that depends on a grant. */
    private fun onAccessChanged() {
        refreshScooters()
        PhoneBridge.refreshAccess()
        // Adds the location type to the running service so rides record with the screen off.
        if (hasLocation()) RideService.refresh(this)
        scheduleRender()
    }

    // ================================================================ connection

    /** Bonded devices are only listed while the adapter is on. */
    private fun bluetoothOn() = runCatching { getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true }.getOrDefault(false)

    @SuppressLint("MissingPermission")
    private fun isBonded(mac: String): Boolean = runCatching {
        getSystemService(BluetoothManager::class.java)?.adapter?.getRemoteDevice(mac.uppercase(Locale.ROOT))?.bondState == BluetoothDevice.BOND_BONDED
    }.getOrDefault(false)

    @SuppressLint("MissingPermission")
    override fun requestBluetoothOn() {
        if (!hasBluetoothAccess()) { requestBluetoothAccess(); return }
        try { startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
        catch (_: Exception) { openBluetoothSettings() }
    }

    private fun startConnection(mac: String) {
        if (Demo.active) return // Demo mode: no Bluetooth activity at all.
        if (!hasBluetoothAccess()) { pendingConnect = mac; requestBluetoothAccess(); return }
        if (!bluetoothOn()) {
            lastError = "Bluetooth is off"
            if (!autoAttempt) showSnackbar("Bluetooth is off", "Turn on") { requestBluetoothOn() }
            autoAttempt = false
            scheduleRender()
            return
        }
        hideKeyboard()
        if (!mac.equals(dataMac, ignoreCase = true)) {
            analytics.clear(); vehicleState = null; tcu = null; lastReadingAt = 0; dataMac = mac
            RideInputs.vehicle(null)
        }
        // Not paired with Android yet: ScooterConnection's bond step answers the pairing request with
        // the saved PIN (PinResponder); outcomes arrive on [pairingListener].
        prefs.edit().putString(DATA_MAC, mac).apply()
        val session = UUID.randomUUID().toString()
        ui.launch(io) { runCatching { telemetry.newSession(session) } }
        lastError = null
        connection.connect(mac)
        RideInputs.link(connection.phase)
        if (connection.phase != LinkPhase.IDLE) startRideService()
    }

    /**
     * Pairing outcomes outside the onboarding flow (which shows its own). A pairing failure also fails
     * the connection, so its snackbar comes from [onConnectionStatus]; ScooterConnection logs the rest.
     */
    private fun onPairingEvent(event: PairingEvent) {
        if (onboarding != null || Demo.active) return
        if (event is PairingEvent.SystemDialog) {
            // Some skins (HyperOS) still show Android's dialog: give the rider the saved PIN to paste.
            showSnackbar("PIN ${event.pin}", "Copy") { copyText("Scooter PIN", event.pin, sensitive = true) }
        }
    }

    /**
     * Fresh launch or Bluetooth turned on: connect to the active scooter so rides record without a
     * tap. Only with Bluetooth on and allowed, and only a scooter Android is paired with or whose
     * PIN is saved.
     */
    private fun autoConnect(trigger: String) {
        val scooter = active ?: return
        if (!prefs.getBoolean(AUTO_CONNECT, true) || onboarding != null || Demo.active) return
        if (connection.phase != LinkPhase.IDLE || connection.reconnecting || !hasBluetoothAccess() || !bluetoothOn()) return
        if (!isBonded(scooter.mac) && !scooter.hasPin) return
        appendLog("Connecting automatically: $trigger")
        autoAttempt = true
        startConnection(scooter.mac)
        if (connection.phase == LinkPhase.IDLE) autoAttempt = false
    }

    /**
     * Starts the ride service while the app is still in front: Android 12+ refuses a foreground-service
     * start from the background, and connecting can take up to 35 s, by which time the user may be in Maps.
     * The first connection also asks (once) for notifications and location, which rides need.
     */
    private fun startRideService() {
        RideRuntime.stopConnection = stopHandle
        try { RideService.start(this) }
        catch (e: Exception) {
            appendLog("Ride service unavailable (${e.javaClass.simpleName})")
            showSnackbar("Keep Chetooth open during this ride")
        }
        if (onboarding != null) return // The flow asks for these on its permissions step.
        val wanted = mutableListOf<String>()
        if (!notificationsAllowed() && !prefs.getBoolean("asked_notifications", false)) wanted += Manifest.permission.POST_NOTIFICATIONS
        if (!hasLocation() && !prefs.getBoolean("asked_location", false)) wanted += LOCATION
        if (wanted.isEmpty()) return
        prefs.edit().putBoolean("asked_notifications", true).putBoolean("asked_location", true).apply()
        requestPermissions(wanted.toTypedArray(), REQUEST_RIDE)
    }

    private fun onConnectionStatus(message: String) {
        appendLog(message)
        val failed = connection.phase == LinkPhase.IDLE && !connection.reconnecting
        if (!failed) { scheduleRender(); return }
        val wasAuto = autoAttempt
        autoAttempt = false
        if (message.startsWith("Disconnected")) {
            lastError = null
            if (onboarding == null) showSnackbar("Disconnected")
            scheduleRender()
            return
        }
        val friendly = friendlyError(message)
        lastError = friendly
        if (onboarding != null) { scheduleRender(); return } // The flow shows its own state.
        if (wasAuto && friendly == "Couldn't reach the scooter") {
            // The scooter is often simply off or out of range when Ruta opens.
            showSnackbar("Scooter not nearby", "Retry") { connect() }
        } else when {
            friendly == "Bluetooth is off" -> showSnackbar(friendly, "Turn on") { requestBluetoothOn() }
            // Only a PIN the scooter received and refused offers to change it.
            PairingRules.failureFor(friendly) == PairingFailure.WRONG_PIN -> showSnackbar(friendly, "Change") { openChangePin(dataMac ?: active?.mac) }
            PairingRules.failureFor(friendly) == PairingFailure.STALE_BOND -> showSnackbar("Forget the scooter in Bluetooth", "Settings") { openBluetoothSettings() } // Lead with the step; then connect.
            else -> showSnackbar(friendly, "Retry") { connect() }
        }
        scheduleRender()
    }

    /** More › Scooters › Change PIN for [mac] (after a wrong PIN outside onboarding). */
    private fun openChangePin(mac: String?) {
        if (mac == null || onboarding != null) { addScooter(); return }
        // The PIN is a sheet over More, not a sub-page: back from More returns to the origin.
        if (tab != Destination.MORE) jump = Jump(tab, Destination.MORE, closesSubPage = false)
        showTab(Destination.MORE)
        (screens[Destination.MORE.ordinal] as? MoreScreen)?.let { more -> screenCall("More", "pin", Unit) { more.changePin(mac) } }
    }

    /** ScooterConnection's diagnostic sentences → short, human snackbar text (the full text is logged). */
    private fun friendlyError(message: String): String = when {
        // Pairing failures arrive as their PairingRules.failureTitle (classified in ScooterConnection).
        PairingRules.failureFor(message) != null -> PairingRules.failureTitle(PairingRules.failureFor(message)!!)
        // GATT 5/15/137: Android's stored pairing no longer matches the scooter (TCU reset / re-paired).
        message.contains("pairing problem", ignoreCase = true) -> PairingRules.failureTitle(PairingFailure.STALE_BOND)
        message.contains("Bluetooth was turned off") || message.contains("Turn on Bluetooth") -> "Bluetooth is off"
        message.contains("could not start pairing", ignoreCase = true) -> "Couldn't pair with the scooter"
        message.contains("Lost the scooter link") -> "Lost the scooter"
        message.contains("does not expose") -> "Not a supported scooter"
        else -> "Couldn't reach the scooter"
    }

    private fun onReading(reading: Reading) {
        analytics.accept(reading)
        RideInputs.reading(reading)
        if (reading.time > lastReadingAt) lastReadingAt = reading.time
        ui.launch(io) {
            try { telemetry.save(reading) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                withContext(Ui.dispatcher) {
                    if (!storageErrorShown) { storageErrorShown = true; showSnackbar("Couldn't save history") }
                }
            }
        }
        scheduleRender()
    }

    private fun onConnected() {
        autoAttempt = false
        lastError = null
        connectedAt = System.currentTimeMillis()
        RideInputs.link(LinkPhase.READY)
        val reconnected = linkWasLost
        linkWasLost = false
        // An automatic reconnect (e.g. mid-ride) is quiet; the connection chip shows it.
        if (onboarding == null && !reconnected) {
            // Chetooth: a Chetak that has not been taught its battery field yet gets a shortcut to the setup.
            if (connection.chetak && ChetakData.decoder(this, LearnField.SOC) == null) showSnackbar("Connected · set up the battery %", "Set up") { openChetakSetup() }
            else showSnackbar("Scooter connected")
        }
        scheduleRender()
    }

    /**
     * 0x0105 vehicle state. Logged once per change for Diagnostics with the raw ride-mode code, which
     * is not shown elsewhere until its names are known (DESIGN: no raw codes outside Diagnostics).
     */
    private fun onVehicleState(state: VehicleState) {
        val previous = vehicleState
        vehicleState = state
        RideInputs.vehicle(state)
        if (previous == null || previous.copy(time = state.time) != state) {
            appendLog("Vehicle state: ignition ${if (state.ignitionOn) "on" else "off"}, handle ${if (state.handleLocked) "locked" else "unlocked"}" +
                ", ride mode ${state.rideMode}, regen ${state.regenLevel}, acceleration ${state.accelerationLevel}" +
                ", speed limit ${state.speedLimit}" + if (state.immobilised) ", immobilised" else "")
        }
        scheduleRender()
    }

    /**
     * [linkLost]: the link dropped by itself and [ScooterConnection] is reconnecting. RideService and the
     * ride tracker keep running (GPS stays on), so a ride continues if the link returns within
     * [RideMath.LINK_LOST_MS] and otherwise ends as LINK_LOST where the link was lost. The connection
     * reports ended(false) when it gives up, and on Disconnect, the notification's Disconnect or app exit.
     */
    private fun onEnded(linkLost: Boolean) {
        RideInputs.link(LinkPhase.IDLE)
        linkWasLost = linkLost
        if (linkLost) { scheduleRender(); return }
        if (RideRuntime.stopConnection === stopHandle) RideRuntime.stopConnection = null
        // A STOP intent is delivered after the pending start, so RideService always reaches startForeground
        // first; stopService could overtake it and make Android kill the app for a missing startForeground.
        try { startService(Intent(this, RideService::class.java).setAction(RideService.ACTION_STOP)) }
        catch (_: Exception) { stopService(Intent(this, RideService::class.java)) }
        scheduleRender()
    }

    // ================================================================ data loading (off the main thread)

    /** Newest ride (Home's last-ride row) and the learned km per % (range), after every saved ride. */
    private fun loadRideData() {
        val revision = RideLive.state.revision
        val source = dataStore()
        rideJob?.cancel()
        rideJob = ui.launch {
            val ride = withContext(io) { runCatching { source.rides(1).firstOrNull() }.getOrNull() }
            if (source.isDemo) {
                // The demo's range uses the seeded month's efficiency (Demo.range); only the last ride is read,
                // and only once the history is seeded (before that the in-memory one stands in).
                if (source === demoStore && demoSeeded) { demoLastRide = ride; demoRidesLoaded = true }
            } else {
                val learned = withContext(io) { runCatching { source.efficiency() }.getOrDefault(source.cachedEfficiency) }
                lastRideCache = ride
                efficiency = learned
            }
            loadedRevision = revision
            scheduleRender()
        }
    }

    /** Last-known battery values from the history store, so Home shows them (dimmed) before connecting. */
    private fun loadLastKnown() {
        ui.launch {
            val readings = withContext(io) {
                runCatching { (0..2).flatMap { pack -> Metric.entries.mapNotNull { metric -> telemetry.latest(metric, pack) } } }.getOrDefault(emptyList())
            }
            if (connection.phase != LinkPhase.IDLE && lastReadingAt > 0) return@launch
            for (reading in readings) {
                val current = analytics.reading(reading.pack, reading.metric)
                if (current == null || current.time < reading.time) analytics.accept(reading)
            }
            if (dataMac == null) dataMac = prefs.getString(DATA_MAC, null) ?: prefs.getString("mac", null)
            scheduleRender()
        }
    }

    // ================================================================ saved scooters

    private fun reloadSaved() {
        saved = runCatching { Scooters.all(this) }.getOrDefault(emptyList())
        active = runCatching { Scooters.active(this) }.getOrNull() ?: saved.firstOrNull()
    }

    // In demo mode the demo scooter is the only one ("Demo scooter", "Simulated data"); the rider's stay untouched.
    override val savedScooters: List<SavedScooter> get() = if (Demo.active) listOf(Demo.scooter) else saved
    override val activeScooter: SavedScooter? get() = if (Demo.active) Demo.scooter else active
    override val scooters: List<ScooterDevice> get() = if (Demo.active) emptyList() else bonded
    override val selectedScooter: String? get() = if (Demo.active) Demo.scooter.mac else active?.mac
    override val bluetoothEnabled: Boolean get() = Demo.active || bluetoothOn()

    override fun selectScooter(address: String) {
        if (demoBlocks()) return
        val known = saved.firstOrNull { it.mac.equals(address, ignoreCase = true) }
        if (known == null) {
            val name = bonded.firstOrNull { it.address.equals(address, ignoreCase = true) }?.name ?: address
            runCatching { Scooters.save(this, SavedScooter(address.uppercase(Locale.ROOT), name, PairingRules.DEFAULT_NICKNAME, false), null) }
        }
        runCatching { Scooters.setActive(this, address) }
        reloadSaved()
        // A different scooter is linked: let go of it so Connect reaches the new one.
        val linkedOther = (connection.phase != LinkPhase.IDLE || connection.reconnecting) && dataMac?.equals(address, ignoreCase = true) == false
        if (linkedOther) connection.disconnect(showStatus = false)
        appendLog("Active scooter: ${active?.bleName ?: "—"}")
        scheduleRender()
    }

    override fun connect(address: String?) {
        if (demoBlocks()) return
        autoAttempt = false
        userDisconnected = false
        if (address != null && active?.mac?.equals(address, ignoreCase = true) != true) selectScooter(address)
        val mac = address ?: active?.mac
        if (mac == null) { addScooter(); return }
        if (connection.phase != LinkPhase.IDLE || connection.reconnecting) return
        startConnection(mac)
        render()
    }

    override fun cancelConnect() {
        if (demoBlocks()) return
        autoAttempt = false
        userDisconnected = true // Cancelled by the rider: Bluetooth turning on again does not reconnect.
        val retrying = connection.reconnecting
        connection.disconnect(showStatus = false) // Also stops answering a pairing request.
        RideInputs.link(connection.phase)
        appendLog(if (retrying) "Reconnection cancelled" else "Connection cancelled")
        render()
    }

    override fun refreshLink() {
        if (demoBlocks()) return
        if (connection.phase != LinkPhase.READY) return
        connection.refresh()
        appendLog("Refresh requested")
        showSnackbar("Refreshing data")
    }

    override fun disconnect() {
        if (demoBlocks()) return
        autoAttempt = false
        userDisconnected = true
        connection.disconnect()
        RideInputs.link(connection.phase)
        render()
    }

    override fun forgetScooter() { active?.let { forgetScooter(it.mac) } }

    override fun forgetScooter(mac: String) {
        if (demoBlocks()) return
        val linked = (connection.phase != LinkPhase.IDLE || connection.reconnecting) && dataMac?.equals(mac, ignoreCase = true) == true
        if (linked) connection.disconnect(showStatus = false)
        val name = saved.firstOrNull { it.mac.equals(mac, ignoreCase = true) }?.nickname ?: PairingRules.DEFAULT_NICKNAME
        runCatching { Scooters.forget(this, mac) }
        if (dataMac?.equals(mac, ignoreCase = true) == true) {
            analytics.clear(); vehicleState = null; tcu = null; lastReadingAt = 0; dataMac = null
            prefs.edit().remove(DATA_MAC).apply()
        }
        reloadSaved()
        appendLog("Scooter forgotten")
        // removeBond is not public API: unpairing happens in Android's Bluetooth settings.
        showSnackbar("$name forgotten", "Unpair") { openBluetoothSettings() }
        scheduleRender()
    }

    override fun renameScooter(mac: String, nickname: String) {
        if (demoBlocks()) return
        runCatching { Scooters.rename(this, mac, PairingRules.nickname(nickname)) }
        reloadSaved()
        scheduleRender()
    }

    /** Keystore encryption runs off the main thread; the Scooters listener refreshes the cache. Never logs the PIN. */
    override fun changeScooterPin(mac: String, pin: String) {
        if (demoBlocks()) return
        val scooter = saved.firstOrNull { it.mac.equals(mac, ignoreCase = true) } ?: return
        ui.launch {
            // save() reports a Keystore failure as false (it does not throw).
            val ok = withContext(io) { runCatching { Scooters.save(this@MainActivity, scooter.copy(hasPin = true), pin) }.getOrDefault(false) }
            appendLog(if (ok) "Scooter PIN changed" else "Scooter PIN could not be saved")
            showSnackbar(if (ok) "PIN saved" else "Couldn't save the PIN")
        }
    }

    override fun addScooter() { if (!demoBlocks()) showOnboarding(addOnly = true) }

    override fun refreshScooters() {
        if (!::connection.isInitialized) return
        if (Demo.active) { reloadSaved(); return } // No Bluetooth calls in demo mode.
        // Without access, or with Bluetooth off, an empty list says nothing about pairing.
        bonded = if (hasBluetoothAccess() && bluetoothOn()) connection.bondedScooters().map { (name, address) -> ScooterDevice(name, address) } else emptyList()
        reloadSaved()
    }

    override fun openBluetoothSettings() {
        try { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        catch (_: ActivityNotFoundException) { showSnackbar("Open Settings › Bluetooth") }
    }

    // ================================================================ Host: state

    override val activity: Activity get() = this
    override val context: Context get() = this
    override val store: TelemetryStore get() = dataStore()
    override val versionName: String
        get() = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty()
    override val currentTab: Destination get() = tab

    override val linkPhase: LinkPhase
        get() {
            demoLive()?.let { return if (it.linked) LinkPhase.READY else LinkPhase.IDLE }
            return if (connection.reconnecting && connection.phase == LinkPhase.IDLE) LinkPhase.CONNECTING else connection.phase
        }

    override val reconnecting: Boolean get() = if (Demo.active) false else connection.reconnecting

    override val link: LinkStatus
        get() {
            demoLive()?.let { return Demo.link(it) }
            val now = System.currentTimeMillis()
            val phase = linkPhase
            val ready = phase == LinkPhase.READY
            val live = ready && lastReadingAt >= connectedAt && now - lastReadingAt <= LIVE_MS
            // Frames stopped (or never came) on an open link: readings in this session or the link itself are the reference.
            val reference = max(lastReadingAt, connectedAt)
            val silent = ready && !live && now - reference > LIVE_MS
            return LinkStatus(
                phase = phase,
                reconnecting = connection.reconnecting,
                live = live,
                silentSince = if (silent) reference else 0L,
                deviceName = connection.deviceName ?: active?.bleName,
                address = dataMac ?: active?.mac,
                error = lastError,
                connectedAt = if (ready) connectedAt else 0L
            )
        }

    override fun reading(pack: Int, metric: Metric): Reading? =
        demoLive()?.let { Demo.reading(it, pack, metric, System.currentTimeMillis()) } ?: if (Demo.active) null else analytics.reading(pack, metric)

    override val vehicle: VehicleState? get() = if (Demo.active) demoLive()?.let { Demo.vehicle(it, System.currentTimeMillis()) } else vehicleState
    override val tcuInfo: TcuInfo? get() = if (Demo.active) null else tcu

    override val range: RangeEstimate?
        get() {
            if (Demo.active) return demoLive()?.let { Demo.range(it) }
            val soc = analytics.reading(0, Metric.SOC)?.value
                ?: listOfNotNull(analytics.reading(1, Metric.SOC)?.value, analytics.reading(2, Metric.SOC)?.value).takeIf { it.isNotEmpty() }?.average()
            return RangeModel.estimate(soc, connection.decodedRangeKm, efficiency)
        }

    override val ride: RideState get() = demoLive()?.let { Demo.ride(it, RideLive.state.revision) } ?: RideLive.state

    override val driveState: DriveState
        get() {
            if (Demo.active) return demoLive()?.let { Demo.driveState(it) } ?: DriveState.UNKNOWN
            if (RideLive.state.charging != null) return DriveState.CHARGING
            val now = System.currentTimeMillis()
            val ready = linkPhase == LinkPhase.READY
            val codes = (0..2).map { pack -> analytics.reading(pack, Metric.STATUS)?.takeIf { ready && now - it.time in 0..FRESH_MS }?.value }
            var state = BmsState.combined(codes)
            // No fresh status (disconnected, or frames stalled): the last-known state; Home dims it.
            if (state == BmsState.UNKNOWN) state = BmsState.combined((0..2).map { pack -> analytics.reading(pack, Metric.STATUS)?.value })
            return when (state) {
                BmsState.DRIVE -> DriveState.DRIVE
                BmsState.STANDBY -> DriveState.STANDBY
                BmsState.UNKNOWN -> DriveState.UNKNOWN
            }
        }

    override val lastRide: Ride?
        get() = if (Demo.active) (if (demoRidesLoaded) demoLastRide else Demo.history?.lastRide) else lastRideCache

    override val navPreview: NavPreview
        get() {
            if (Demo.active) return demoLive()?.let { Demo.navPreview(it, connection.navigationEnabled) } ?: NavPreview()
            val testing = connection.displayTestRunning || connection.roadWidthTestRunning
            val frame = if (testing && connection.phase == LinkPhase.READY) connection.sentNavFrame else NavBridge.frame()
            return NavPreview(
                frame = frame,
                instruction = NavBridge.cue?.instruction,
                road = NavBridge.cue?.road,
                thenTurn = NavBridge.cue?.thenTurn,
                display = connection.navigationDisplay(),
                enabled = connection.navigationEnabled,
                testRunning = testing,
                error = connection.navigationError,
                listenerConnected = NavBridge.listenerConnected,
                updatedAt = NavBridge.updatedAt
            )
        }

    override val musicFeed: ClusterFeed
        get() = if (Demo.active) demoLive()?.let { Demo.musicFeed(it, connection.musicEnabled) } ?: ClusterFeed.UNAVAILABLE else connection.musicFeed()
    override val callFeed: ClusterFeed
        get() = if (Demo.active) demoLive()?.let { Demo.callFeed(it, connection.callsEnabled) } ?: ClusterFeed.UNAVAILABLE else connection.callFeed()

    override val mediaState: MediaState? get() = if (Demo.active) Demo.media() else PhoneBridge.media
    override val callState: CallState get() = if (Demo.active) Demo.call() else PhoneBridge.call

    override val lastHandlebar: Pair<String, Long>?
        get() = if (Demo.active) null else connection.lastHandlebar?.let { it to connection.lastHandlebarAt }

    override val displayTestRunning: Boolean get() = !Demo.active && (connection.displayTestRunning || connection.roadWidthTestRunning)

    override val logLines: List<String> get() = DiagLog.lines()

    // ================================================================ Host: shell actions

    override fun selectTab(destination: Destination) {
        if (onboarding != null || destination == tab) return
        jump = Jump(tab, destination, closesSubPage = false)
        showTab(destination)
    }

    override fun requestRender() = scheduleRender()

    override fun showSnackbar(text: String, actionLabel: String?, action: (() -> Unit)?) {
        if (::snackbars.isInitialized && root.isAttachedToWindow) snackbars.show(text, actionLabel, action = action)
        else toast(text)
    }

    override fun showSheet(content: View, title: String?): BottomSheet? {
        if (isFinishing || isDestroyed) return null
        sheets.removeAll { !it.isShowing }
        return BottomSheet(this).apply {
            setTitle(title)
            setContent(content)
            show()
            sheets += this
        }
    }

    override fun openRide(id: Long) {
        if (onboarding != null) return
        if (tab != Destination.RIDES) jump = Jump(tab, Destination.RIDES, closesSubPage = true)
        showTab(Destination.RIDES)
        (screens[Destination.RIDES.ordinal] as? RidesScreen)?.let { rides -> screenCall("Rides", "open", Unit) { rides.openRide(id) } }
    }

    override fun subPageClosed() {
        val current = jump ?: return
        if (current.to == tab && current.closesSubPage) jump = current.copy(closesSubPage = false)
    }

    /** Opens the Maps app as is; the rider starts navigation there and directions reach the scooter on their own. */
    override fun openMaps() {
        val launch = packageManager.getLaunchIntentForPackage(MAPS)
        try {
            startActivity(launch ?: Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0")).setPackage(MAPS))
        } catch (_: ActivityNotFoundException) {
            showSnackbar("Install Google Maps for directions")
        }
    }

    override fun media(action: MediaAction) {
        if (Demo.active) {
            if (!Demo.mediaAction(action)) showSnackbar("Nothing is playing")
            scheduleRender()
            return
        }
        if (PhoneBridge.mediaAction(action)) return
        if (!listenerAccess()) showSnackbar("Allow notification access", "Allow") { openNotificationAccess() }
        else showSnackbar("Nothing is playing")
    }

    override fun call(action: CallAction) {
        if (Demo.active) {
            if (!Demo.callAction(action)) showSnackbar("No call")
            scheduleRender()
            return
        }
        if (PhoneBridge.callAction(action)) return
        if (!granted(Manifest.permission.ANSWER_PHONE_CALLS)) showSnackbar("Allow answering calls", "Allow") { requestPermission(AppPermission.ANSWER_CALLS) }
        else showSnackbar("Use the phone app")
    }

    // ================================================================ Host: settings

    override fun isEnabled(feature: Feature): Boolean = when (feature) {
        Feature.DIRECTIONS -> connection.navigationEnabled
        Feature.MUSIC -> connection.musicEnabled
        Feature.CALLS -> connection.callsEnabled
        Feature.HANDLEBAR -> connection.handlebarControlsEnabled
        Feature.AUTO_CONNECT -> prefs.getBoolean(AUTO_CONNECT, true)
    }

    override fun setEnabled(feature: Feature, on: Boolean) {
        val word = if (on) "on" else "off"
        when (feature) {
            Feature.DIRECTIONS -> {
                prefs.edit().putBoolean("nav_forward", on).apply()
                connection.navigationEnabled = on
                appendLog("Directions on the scooter display: $word")
            }
            Feature.MUSIC -> connection.musicEnabled = on // Logs itself.
            Feature.CALLS -> connection.callsEnabled = on // Logs itself.
            Feature.HANDLEBAR -> {
                connection.handlebarControlsEnabled = on
                appendLog("Handlebar controls: $word")
            }
            Feature.AUTO_CONNECT -> {
                prefs.edit().putBoolean(AUTO_CONNECT, on).apply()
                appendLog("Auto-connect: $word")
            }
        }
        scheduleRender()
    }

    // ================================================================ Host: diagnostics and data

    private fun appendLog(line: String) = DiagLog.append(line)

    override fun log(line: String) = appendLog(line)

    override fun addLogListener(listener: (String) -> Unit) {
        if (listener in logListeners) return
        val wrapper: () -> Unit = { DiagLog.lines().lastOrNull()?.let(listener) }
        logListeners[listener] = wrapper
        DiagLog.addListener(wrapper)
    }

    override fun removeLogListener(listener: (String) -> Unit) {
        logListeners.remove(listener)?.let(DiagLog::removeListener)
    }

    private fun logHeader(): String =
        "Chetooth $versionName · Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}\n"

    /** [sensitive] (e.g. a PIN): Android 13+ hides it in the clipboard preview and keyboards keep it out of history. */
    private fun copyText(label: String, text: String, sensitive: Boolean = false) {
        val clip = ClipData.newPlainText(label, text)
        if (sensitive && Build.VERSION.SDK_INT >= 33) {
            clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        }
        runCatching { getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip) }
    }

    override fun copyLog() {
        copyText("Chetooth log", logHeader() + DiagLog.lines().joinToString("\n"))
        if (Build.VERSION.SDK_INT < 33) showSnackbar("Log copied") // Android 13+ confirms on its own.
    }

    /**
     * Shares the recent log as text (newest lines kept under the share limit). The full daily files
     * stay in the app's external files folder (logs/) for `adb pull`.
     */
    override fun shareLogs() {
        val lines = DiagLog.lines()
        val body = StringBuilder()
        for (index in lines.indices.reversed()) {
            val line = lines[index]
            if (body.length + line.length + 1 > SHARE_LIMIT) break
            body.insert(0, line + "\n")
        }
        val folder = DiagLog.logDir?.let { "\nLog files: ${it.absolutePath}\n" }.orEmpty()
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Chetooth log")
            .putExtra(Intent.EXTRA_TEXT, logHeader() + folder + "\n" + body)
        try { startActivity(Intent.createChooser(send, "Share log")) }
        catch (_: ActivityNotFoundException) { showSnackbar("No app to share with") }
    }

    private fun needsLink(): Boolean {
        if (demoBlocks()) return true
        if (connection.phase == LinkPhase.READY) return false
        showSnackbar("Connect the scooter first")
        return true
    }

    override fun probeScooter() {
        if (needsLink()) return
        connection.probeAllStats()
        showSnackbar("Asking the scooter for all data")
    }

    override fun rawStats(): Map<Int, ByteArray> = if (Demo.active) emptyMap() else connection.rawStats()

    override fun startDisplayTest() {
        if (needsLink()) return
        connection.startDisplayTest()
        showSnackbar("Test route on the scooter for 40 s")
        scheduleRender()
    }

    override fun startRoadNameTest() {
        if (needsLink()) return
        connection.startRoadWidthTest()
        showSnackbar("Road names on the scooter for 20 s")
        scheduleRender()
    }

    override fun resetUnits() {
        if (demoBlocks()) return
        val mac = dataMac ?: active?.mac ?: run { showSnackbar("Add a scooter first"); return }
        connection.resetCalibration(mac)
        showSnackbar("Battery units will be detected again")
    }

    @Suppress("DEPRECATION")
    override fun exportCsv() {
        exportDemo = Demo.active
        val name = (if (exportDemo) "ruta-demo-" else "ruta-") + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".csv"
        try {
            startActivityForResult(
                Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("text/csv").putExtra(Intent.EXTRA_TITLE, name), REQUEST_EXPORT
            )
        } catch (_: ActivityNotFoundException) { showSnackbar("No file picker on this phone") }
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_EXPORT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        // Demo mode started or ended while the file picker was open: the file name no longer matches.
        if (exportDemo != Demo.active) { showSnackbar("Export cancelled"); return }
        val source = dataStore()
        ui.launch {
            try {
                withContext(io) { source.exportCsv(contentResolver.openOutputStream(uri) ?: error("No output stream")) }
                showSnackbar("Exported")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) {
                appendLog("CSV export failed: ${e.javaClass.simpleName}")
                showSnackbar("Export failed", "Retry") { exportCsv() }
            }
        }
    }

    // ================================================================ demo mode (F-DEMO)

    /** The demo values for this tick, or null when demo mode is off. */
    private fun demoLive(): DemoLive? = if (Demo.active) Demo.live ?: Demo.update() else null

    /** Where history is read from: the demo file in demo mode (opened on first use), else the rider's. Main thread. */
    private fun dataStore(): TelemetryStore =
        if (Demo.active) demoStore ?: TelemetryStore.demo(this).also { demoStore = it } else telemetry

    /** Scooter, Bluetooth and test actions do nothing in demo mode: says so, with the way out. */
    private fun demoBlocks(): Boolean {
        if (!Demo.active) return false
        showSnackbar("Demo mode: no real scooter", "Exit demo") { setDemo(null) }
        return true
    }

    override fun setDemo(scenario: DemoScenario?) {
        when {
            scenario == null -> endDemo()
            Demo.active -> switchDemo(scenario)
            else -> startDemo(scenario)
        }
    }

    /**
     * Demo mode on in [scenario]: Host switches to [Demo.live], the history to a freshly seeded demo
     * file ([restored]: the one this session already seeded, at [seedAt]). Refused while the scooter is
     * linked (a ride, the parked spot and the link would end behind the rider's back).
     */
    private fun startDemo(
        scenario: DemoScenario, seedAt: Long = System.currentTimeMillis(), elapsedMs: Long = 0L,
        restored: Boolean = false, announce: Boolean = true
    ) {
        if (Demo.active) { switchDemo(scenario); return }
        if (!::connection.isInitialized || !::root.isInitialized) return
        if (connection.phase != LinkPhase.IDLE || connection.reconnecting) {
            appendLog("Demo mode not started: the scooter is linked")
            showSnackbar("Disconnect the scooter to try the demo", "Disconnect") {
                if (connection.phase == LinkPhase.READY) disconnect() else cancelConnect()
            }
            scheduleRender()
            return
        }
        val history = DemoData.history(seedAt)
        hideKeyboard()
        dismissSheets()
        closeRideDetail()
        val fromOnboarding = onboarding != null
        if (fromOnboarding) { removeOnboarding(animate = true); updateSnackbarOffset() }
        pendingConnect = null
        autoAttempt = false
        demoSeeded = false; demoRidesLoaded = false; demoLastRide = null; demoChargeStart = 0L; demoChargeSavedAt = 0L
        Demo.begin(scenario, history, elapsedMs = elapsedMs)
        appendLog("Demo mode on: ${scenario.label}" + if (restored) " (restored)" else "")
        seedDemoHistory(dataStore(), history, force = !restored)
        if (fromOnboarding) {
            jump = null
            if (tab != Destination.HOME) showTab(Destination.HOME, hideOutgoing = false)
            else if (visible) screens[tab.ordinal]?.let { screenCall(tab.label, "show", Unit) { it.onShow() } }
        }
        updateBackCallback()
        if (announce && !restored) showSnackbar("Demo mode · Tap Demo to switch or exit")
        render()
    }

    /** Another scenario (or the current one again: it starts over). */
    private fun switchDemo(scenario: DemoScenario) {
        val history = Demo.history ?: return
        if (Demo.scenario == DemoScenario.CHARGING) dropDemoCharge()
        Demo.begin(scenario, history)
        appendLog("Demo scenario: ${scenario.label}")
        render()
    }

    /** Back to the real state: the rider's history, scooter and link (reconnecting as on launch); the demo file is deleted. */
    private fun endDemo() {
        if (!Demo.active) return
        val store = demoStore
        demoStore = null
        hideKeyboard()
        dismissSheets()
        closeRideDetail()
        stopDemoState()
        demoSeeded = false; demoRidesLoaded = false; demoLastRide = null; demoChargeStart = 0L; demoChargeSavedAt = 0L
        // After any seeding still queued on the storage thread; queries still in flight then fail quietly.
        ui.launch(io) { runCatching { store?.retire() }; runCatching { TelemetryStore.deleteDemo(this@MainActivity) } }
        appendLog("Demo mode off")
        RideLive.invalidate() // Every screen reloads the rider's own history.
        loadRideData()
        refreshScooters()
        if (saved.isEmpty()) showOnboarding(addOnly = false) // Tried the demo from Welcome: back to it.
        else {
            showSnackbar("Demo ended")
            userDisconnected = false
            autoConnect("Demo ended")
        }
        updateBackCallback()
        render()
    }

    /** Demo state off (the top-bar chips hide through [Demo]'s listeners). */
    private fun stopDemoState() {
        demoSheet?.dismiss()
        demoSheet = null
        Demo.end()
    }

    /** A ride detail from the other history must not stay open (ride ids overlap between the two files). */
    private fun closeRideDetail() {
        val rides = screens[Destination.RIDES.ordinal] ?: return
        if (screenCall("Rides", "back", false) { rides.onBack() }) subPageClosed()
    }

    /** Recreated activity: demo mode carries on (same process) or starts again on the session's history. */
    private fun restoreDemo(state: Bundle) {
        val scenario = DemoScenario.of(state.getString("demo"))
        if (scenario == null) { if (Demo.active) stopDemoState(); return }
        val seedAt = state.getLong("demo_seed_at", 0L).takeIf { it > 0L } ?: System.currentTimeMillis()
        val history = Demo.history
        if (Demo.active && history != null && history.seedAt == seedAt) {
            demoSeeded = false
            seedDemoHistory(dataStore(), history, force = false) // Seeds only if the file is empty.
            return
        }
        if (Demo.active) stopDemoState()
        startDemo(scenario, seedAt, state.getLong("demo_elapsed", 0L).coerceAtLeast(0L), restored = true)
    }

    /**
     * QA / ADB: `--es demo <scenario|off>` and `--ez demo_seed true` (a string "true" works too).
     * The extras are consumed so a later recreation does not apply them again.
     */
    @Suppress("DEPRECATION") // Bundle.get: the seed flag may arrive as a boolean or a string.
    private fun handleDemoIntent(intent: Intent?) {
        val extras = intent?.extras ?: return
        if (!extras.containsKey(EXTRA_DEMO) && !extras.containsKey(EXTRA_DEMO_SEED)) return
        val value = extras.get(EXTRA_DEMO)?.toString()?.trim()?.take(24)
        val seedExtra = extras.get(EXTRA_DEMO_SEED)
        val reseed = seedExtra == true || seedExtra?.toString()?.trim()?.equals("true", ignoreCase = true) == true
        intent.removeExtra(EXTRA_DEMO)
        intent.removeExtra(EXTRA_DEMO_SEED)
        when {
            value != null && value.equals(DemoScenario.OFF, ignoreCase = true) -> { appendLog("Demo intent: off"); setDemo(null) }
            value != null -> {
                val scenario = DemoScenario.of(value)
                if (scenario == null) {
                    appendLog("Demo intent: unknown scenario \"$value\" (parked, riding, navigating, charging, call, music or off)")
                    return
                }
                appendLog("Demo intent: ${scenario.key}" + if (reseed) ", reseed" else "")
                if (Demo.active) { switchDemo(scenario); if (reseed) reseedDemo() } else startDemo(scenario, announce = false)
            }
            reseed -> if (Demo.active) reseedDemo() else appendLog("Demo intent: demo_seed needs a scenario (--es demo riding)")
        }
    }

    /** A fresh demo month seeded now (the current scenario starts over on it). */
    private fun reseedDemo() {
        val scenario = Demo.scenario ?: return
        val history = DemoData.history(System.currentTimeMillis())
        if (scenario == DemoScenario.CHARGING) dropDemoCharge()
        demoSeeded = false; demoRidesLoaded = false
        Demo.begin(scenario, history)
        seedDemoHistory(dataStore(), history, force = true)
    }

    /**
     * Fills the demo file with [history] on the storage thread ([force], or when it is empty), then has
     * every screen reload from it ([RideLive.invalidate]). Live values do not wait for this.
     */
    private fun seedDemoHistory(store: TelemetryStore, history: DemoHistory, force: Boolean) {
        ui.launch {
            val started = SystemClock.uptimeMillis()
            val result = withContext(io) {
                runCatching { if (force || store.rideCount() == 0L) { store.seedDemo(history); true } else false }
            }
            if (store !== demoStore || !Demo.active) return@launch
            result.onFailure {
                appendLog("Demo history could not be prepared: ${it.javaClass.simpleName}")
                showSnackbar("Couldn't prepare the demo history")
                return@launch
            }
            if (result.getOrDefault(false)) {
                appendLog("Demo history: ${history.rides.size} rides, ${history.charges.size} charging sessions (${SystemClock.uptimeMillis() - started} ms)")
            }
            demoSeeded = true
            demoChargeStart = 0L; demoChargeSavedAt = 0L
            RideLive.invalidate()
            loadRideData()
        }
    }

    /**
     * The charging scenario's live session goes into the demo history the way the real detector saves
     * one (every 30 s), so Insights lists it as charging now; removed when the scenario ends.
     */
    private fun saveDemoCharge() {
        val session = Demo.live?.charge ?: return
        val store = demoStore ?: return
        if (!demoSeeded) return
        val now = SystemClock.uptimeMillis()
        val restarted = demoChargeStart != 0L && demoChargeStart != session.start
        if (demoChargeStart != 0L && !restarted && now - demoChargeSavedAt < 30_000L) return
        val previous = demoChargeStart.takeIf { restarted }
        val first = demoChargeStart == 0L || restarted
        demoChargeStart = session.start
        demoChargeSavedAt = now
        ui.launch {
            withContext(io) { runCatching { previous?.let { store.deleteChargeSession(it) }; store.saveChargeSession(session) } }
            if (first && Demo.active) RideLive.invalidate()
        }
    }

    private fun dropDemoCharge() {
        val start = demoChargeStart
        demoChargeStart = 0L; demoChargeSavedAt = 0L
        val store = demoStore ?: return
        if (start == 0L) return
        ui.launch {
            withContext(io) { runCatching { store.deleteChargeSession(start) } }
            if (Demo.active) RideLive.invalidate()
        }
    }

    /**
     * The demo sheet (the "Demo" chip in every top bar): the six scenarios (the current one checked;
     * choosing it again starts it over) and Exit demo.
     */
    override fun showDemoSheet() {
        if (!Demo.active || isFinishing || isDestroyed) return
        if (demoSheet?.isShowing == true) return
        val current = Demo.scenario
        var sheet: BottomSheet? = null
        val content = column()
        content.addView(textView("A simulated scooter and history. Nothing reaches Bluetooth or your rides.", Typography.bodyMedium, Theme.onSurfaceVariant).apply {
            setPadding(dp(Spacing.S), 0, dp(Spacing.S), 0)
        }, params())
        val group = ListGroup(this)
        for (scenario in DemoScenario.entries) {
            val row = ListItem(this, scenario.label, scenario.detail, scenario.icon)
            if (scenario == current) {
                row.setIcon(scenario.icon, filled = true)
                row.setLeadingTone(Tone.PRIMARY)
                row.setTrailing(IconView(this, "check", tint = Theme.primary))
                row.isSelected = true
                row.stateDescription = "Current, tap to start over"
            }
            row.setOnClick {
                row.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                sheet?.dismiss()
                setDemo(scenario)
            }
            group.addView(row)
        }
        content.addView(group, params(Spacing.L))
        content.addView(actionButton("Exit demo", ButtonVariant.TONAL, "close", ButtonSize.MEDIUM) {
            sheet?.dismiss()
            setDemo(null)
        }, params(Spacing.XL))
        sheet = showSheet(content, "Demo mode")
        demoSheet = sheet
    }

    // ================================================================ rendering

    private fun scheduleRender() {
        if (renderPosted) return
        renderPosted = true
        handler.post(renderTask)
    }

    private fun tick() {
        // Ride tracking samples the link phase; feed it even while Ruta is in the background.
        RideInputs.link(connection.phase)
        if (connection.phase == LinkPhase.READY) RideInputs.range(connection.decodedRangeKm)
        if (Demo.active) { Demo.update(); saveDemoCharge() }
        if (!visible) return
        render()
    }

    private fun render() {
        // Stopped: no view work (and no spring frames) in the background; onStart renders again.
        if (!visible || !::connection.isInitialized || !::root.isInitialized) return
        val now = System.currentTimeMillis()
        // A small dot on Navigate while a route is active and another tab is in front.
        val routing = if (Demo.active) Demo.live?.nav != null else NavBridge.cue != null
        nav.setBadge(Destination.NAVIGATE.ordinal, if (routing && tab != Destination.NAVIGATE) 0 else null)
        foreground()?.let { (name, screen) -> screenCall(name, "render", Unit) { screen.render(now) } }
    }

    private fun hideKeyboard() {
        currentFocus?.clearFocus()
        window.insetsController?.hide(WindowInsets.Type.ime())
    }
}
