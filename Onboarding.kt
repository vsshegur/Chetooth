package dev.codesurgen.ruta

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.location.LocationManager
import android.os.Build
import android.os.PersistableBundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowInsets
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/*
 * First-run onboarding and More › Add scooter (DESIGN Addendum C, craft per Addendum A):
 * Welcome → Permissions → Find scooter → PIN (only when Android is not paired yet) → Connecting →
 * Done → "Directions on your scooter" (POLISH T2: optional, the TFT's Navigation widget, shown
 * until DisplaySetup is marked done; "Done" / "Later"). Full screen over the shell; large calm type, one primary button per step anchored at the
 * bottom, springy step transitions, haptics. Every error state has a way out: no permission,
 * Bluetooth off (turn on), location off (Android 11 scan), nothing found (scan again / Bluetooth
 * settings), wrong PIN (back to the PIN with the field shaking), timeouts and link failures (try
 * again / choose another). The PIN is saved encrypted before connecting; ScooterConnection's bond
 * step answers Android's pairing request with it. Main thread only.
 */
class OnboardingFlow(
    private val host: Host,
    private val addOnly: Boolean = false,
    private val onDone: (SavedScooter?) -> Unit
) : Screen {
    private val ctx = host.context
    private val app = ctx.applicationContext
    private val scope = Ui.scope()
    private val root = FrameLayout(ctx)
    override val view: View get() = root

    // ------------------------------------------------------------------ chrome
    private val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val back = IconActionButton(ctx, "arrow_back", "Back") { onBack() }
    private val dots = OnboardingDotsView(ctx)
    private val help = IconActionButton(ctx, "info", "Pairing help") { showHelp() }
    private val stage = FrameLayout(ctx)
    private val primary = ActionButton(ctx, "Continue", ButtonVariant.FILLED, size = ButtonSize.MEDIUM) { onPrimary() }
    private val secondary = ActionButton(ctx, "Choose another", ButtonVariant.TEXT) { onSecondary() }
    private var actions: View? = null

    /** The step's buttons at the bottom (secondary + primary): the shell keeps snackbars above them. */
    val bottomAction: View? get() = actions

    // ------------------------------------------------------------------ state
    private var step = OnboardingPlan.first(addOnly)
    private var finished = false
    private var resumed = false
    private var selected: ScanCandidate? = null
    private var previousActive: String? = runCatching { Scooters.active(app)?.mac }.getOrNull()
    /** Scooter saved by this flow for the connection (normalised MAC). */
    private var savedMac: String? = null
    /** It was not saved before this flow: undo on cancel unless it connected. */
    private var addedByFlow = false
    private var connectedOnce = false
    private var attempt = 0
    private var attemptAt = 0L
    private var connectRequested = false
    private var sawConnecting = false
    private var failure: Failure? = null
    private var ticker: Job? = null
    private var transition: Spring? = null
    private var doneTask: Runnable? = null
    private var scanAsked = false
    private var lastAccessKey: List<Boolean>? = null
    private var pinPageBuilt = false
    private var restoredNickname: String? = null
    /** Scooter this flow disconnected to reach the new one; reconnected if the rider cancels. */
    private var displaced: String? = null

    /** BOND = Android's stored pairing no longer matches the scooter: unpair in Bluetooth settings. */
    private enum class Failure { BLUETOOTH_OFF, NO_ACCESS, TIMEOUT, LINK, BOND }
    private enum class FindAction { CONTINUE, SCAN_AGAIN, TURN_ON, ALLOW, LOCATION, SETTINGS, WAIT }

    private val welcome by lazy { WelcomePage() }
    private val permissions by lazy { PermissionsPage() }
    private val find by lazy { FindPage() }
    private val pinPage by lazy { PinPage() }
    private val connecting by lazy { ConnectingPage() }
    private val done by lazy { DonePage() }
    private val directions by lazy { DirectionsPage() }
    /** Within DONE: the optional "Directions on your scooter" page is showing (OnboardingStep has no slot for it). */
    private var showingDirections = false

    private val pairingListener: (String, PairingEvent) -> Unit = { mac, event -> onPairing(mac, event) }

    private val adapterReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            Ui.run { onAdapterState(state) }
        }
    }
    private var adapterRegistered = false

    init {
        root.setBackgroundColor(Theme.background)
        root.isClickable = true
        buildChrome()
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            column.setPadding(bars.left, bars.top, bars.right, max(bars.bottom, ime.bottom))
            WindowInsets.CONSUMED
        }
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { root.requestApplyInsets(); resume() }
            override fun onViewDetachedFromWindow(v: View) { pause() }
        })
        PairingEvents.addListener(pairingListener)
        try {
            val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(adapterReceiver, filter, Context.RECEIVER_EXPORTED)
            else app.registerReceiver(adapterReceiver, filter)
            adapterRegistered = true
        } catch (_: Exception) {}
        restore()
        val first = page(step)
        stage.addView(first.scroll, FrameLayout.LayoutParams(MATCH, MATCH))
        first.enter(animate = false)
        updateChrome(animate = false)
        if (step == OnboardingStep.CONNECTING && host.linkPhase == LinkPhase.IDLE) startConnecting()
    }

    private fun buildChrome() {
        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ctx.dp(64)
            setPaddingRelative(ctx.dp(Spacing.XS), 0, ctx.dp(Spacing.XS), 0)
        }
        top.addView(back, LinearLayout.LayoutParams(WRAP, WRAP))
        top.addView(dots, LinearLayout.LayoutParams(0, ctx.dp(Spacing.TOUCH), 1f))
        top.addView(help, LinearLayout.LayoutParams(WRAP, WRAP))
        column.addView(top, LinearLayout.LayoutParams(MATCH, WRAP))
        column.addView(stage, LinearLayout.LayoutParams(MATCH, 0, 1f))
        val actions = MaxWidthLayout(ctx, ctx.dp(Spacing.MAX_WIDTH)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(ctx.dp(Spacing.MARGIN), ctx.dp(Spacing.S), ctx.dp(Spacing.MARGIN), ctx.dp(Spacing.L))
            addView(secondary, LinearLayout.LayoutParams(WRAP, WRAP).apply { bottomMargin = ctx.dp(Spacing.XS) })
            addView(primary, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        this.actions = actions
        val bottom = FrameLayout(ctx)
        bottom.addView(actions, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER_HORIZONTAL))
        column.addView(bottom, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(column, FrameLayout.LayoutParams(MATCH, MATCH))
        secondary.visibility = View.GONE
    }

    // ================================================================== Screen

    override fun onShow() { resume() }

    override fun onHide() { pause() }

    override fun render(now: Long) {
        if (finished) return
        page(step).refresh()
        updateChrome()
    }

    override fun onBack(): Boolean {
        if (finished) return false
        when (step) {
            OnboardingStep.WELCOME -> return false
            OnboardingStep.DONE -> { finish(); return true }
            OnboardingStep.CONNECTING -> { cancelConnecting(); return true }
            else -> {}
        }
        val previous = OnboardingPlan.back(step, addOnly, bonded())
        if (previous == null) {
            if (addOnly) { cancelFlow(); return true }
            return false
        }
        if (previous == OnboardingStep.FIND) revertAdded()
        go(previous, forward = false)
        return true
    }

    override fun dispose() {
        if (!finished) snapshot = Snapshot(addOnly, step, selected, pinPageNickname(), savedMac, addedByFlow, previousActive, Ui.uptimeMs())
        pause()
        doneTask?.let(Ui::cancel); doneTask = null
        transition?.cancel()
        PairingEvents.removeListener(pairingListener)
        if (adapterRegistered) { runCatching { app.unregisterReceiver(adapterReceiver) }; adapterRegistered = false }
        hideKeyboard()
        scope.cancel()
    }

    // ================================================================== lifecycle helpers

    private fun resume() {
        if (finished || resumed || !root.isAttachedToWindow) return
        resumed = true
        if (step == OnboardingStep.CONNECTING && !sawConnecting) attemptAt = Ui.uptimeMs() // A permission dialog may have paused us.
        if (step == OnboardingStep.FIND) find.resumeScan()
        page(step).refresh()
        updateChrome()
        if (ticker?.isActive != true) ticker = scope.launch {
            while (isActive) {
                tick()
                delay(if (step == OnboardingStep.CONNECTING) 250L else 500L)
            }
        }
    }

    private fun pause() {
        resumed = false
        ticker?.cancel(); ticker = null
        if (step == OnboardingStep.FIND) find.pauseScan()
    }

    private fun tick() {
        if (finished) return
        when (step) {
            OnboardingStep.PERMISSIONS -> { permissions.refresh(); updateChrome() }
            OnboardingStep.FIND -> find.poll()
            OnboardingStep.CONNECTING -> watchLink()
            else -> {}
        }
    }

    // ================================================================== navigation

    private fun bonded(): Boolean = selected?.bonded == true

    private fun page(s: OnboardingStep): Page = when (s) {
        OnboardingStep.WELCOME -> welcome
        OnboardingStep.PERMISSIONS -> permissions
        OnboardingStep.FIND -> find
        OnboardingStep.PIN -> pinPage
        OnboardingStep.CONNECTING -> connecting
        OnboardingStep.DONE -> if (showingDirections) directions else done
    }

    private fun advance() {
        var next = OnboardingPlan.next(step, addOnly, bonded())
        if (next == OnboardingStep.PERMISSIONS && allGranted()) next = OnboardingStep.FIND
        go(next, forward = true)
    }

    private fun go(next: OnboardingStep, forward: Boolean) {
        if (finished) return
        if (next == step) { updateChrome(); return }
        val from = page(step)
        val to = page(next)
        from.leave()
        step = next
        if (to.scroll.parent == null) stage.addView(to.scroll, FrameLayout.LayoutParams(MATCH, MATCH))
        slide(from.scroll, to.scroll, forward)
        to.enter(animate = true)
        to.refresh()
        updateChrome()
    }

    /** Springy horizontal step transition: the new step slides in from the reading direction while the old one fades. */
    private fun slide(outgoing: View, incoming: View, forward: Boolean) {
        transition?.cancel()
        for (i in 0 until stage.childCount) {
            val child = stage.getChildAt(i)
            if (child !== outgoing && child !== incoming) { child.visibility = View.GONE; child.translationX = 0f; child.alpha = 1f }
        }
        incoming.visibility = View.VISIBLE
        if (!Motion.enabled() || !root.isAttachedToWindow) {
            outgoing.visibility = View.GONE
            for (v in listOf(outgoing, incoming)) { v.translationX = 0f; v.alpha = 1f }
            return
        }
        val rtl = root.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val direction = if (forward != rtl) 1f else -1f
        val shift = ctx.dpf(56f)
        val spring = Spring(0f, Motion.STIFFNESS, Motion.DAMPING, 0.002f) { p ->
            val q = p.coerceIn(0f, 1f)
            incoming.translationX = (1f - p) * direction * shift
            incoming.alpha = min(1f, q * 1.8f)
            outgoing.translationX = -q * direction * shift * 0.4f
            outgoing.alpha = 1f - min(1f, q * 3f)
        }
        spring.onEnd = {
            outgoing.visibility = View.GONE
            outgoing.translationX = 0f; outgoing.alpha = 1f
            incoming.translationX = 0f; incoming.alpha = 1f
        }
        transition = spring
        spring.animateTo(1f)
    }

    private fun updateChrome(animate: Boolean = true) {
        if (finished) return
        val (index, count) = OnboardingPlan.position(step, addOnly, bonded())
        dots.set(index, count, animate && root.isAttachedToWindow)
        dots.visibility = if (step == OnboardingStep.WELCOME) View.INVISIBLE else View.VISIBLE
        val canGoBack = step != OnboardingStep.DONE && (OnboardingPlan.back(step, addOnly, bonded()) != null || addOnly || step == OnboardingStep.CONNECTING)
        back.visibility = if (canGoBack && step != OnboardingStep.WELCOME) View.VISIBLE else View.INVISIBLE
        if (addOnly && step == OnboardingStep.FIND) back.setIcon("close", "Close") else back.setIcon("arrow_back", "Back")
        help.visibility = if (step == OnboardingStep.FIND || step == OnboardingStep.PIN) View.VISIBLE else View.INVISIBLE

        var label = "Continue"; var variant = ButtonVariant.FILLED; var enabled = true; var icon: String? = null
        var second: String? = null
        when (step) {
            // F-DEMO: try Ruta without a scooter (a text button above Continue, like every second action here).
            OnboardingStep.WELCOME -> if (!addOnly) second = "Explore demo"
            OnboardingStep.PERMISSIONS -> enabled = bluetoothAccess()
            OnboardingStep.FIND -> when (findAction()) {
                FindAction.CONTINUE -> {}
                FindAction.WAIT -> enabled = false
                FindAction.SCAN_AGAIN -> { label = "Scan again"; icon = "refresh" }
                FindAction.TURN_ON -> label = "Turn on"
                FindAction.ALLOW -> label = "Allow"
                FindAction.LOCATION -> label = "Turn on"
                FindAction.SETTINGS -> { label = "Bluetooth settings"; variant = ButtonVariant.TONAL }
            }
            OnboardingStep.PIN -> { label = "Pair"; enabled = PairingRules.isValidPin(pinPage.pin) }
            OnboardingStep.CONNECTING -> when (failure) {
                null -> { label = "Cancel"; variant = ButtonVariant.TONAL }
                Failure.BLUETOOTH_OFF -> { label = "Turn on"; second = "Choose another" }
                Failure.NO_ACCESS -> { label = "Allow"; second = "Choose another" }
                Failure.TIMEOUT, Failure.LINK -> { label = "Try again"; icon = "refresh"; second = "Choose another" }
                Failure.BOND -> { label = "Bluetooth settings"; icon = "bluetooth"; second = "Scan again" }
            }
            OnboardingStep.DONE -> when {
                showingDirections -> { label = "Done"; second = "Later" }
                directionsPending() -> label = "Continue"
                else -> label = "Done"
            }
        }
        primary.text = label
        primary.variant = variant
        primary.setIcon(icon)
        primary.isEnabled = enabled
        secondary.visible(second != null)
        if (second != null) secondary.text = second
    }

    private fun onPrimary() {
        if (finished) return
        when (step) {
            OnboardingStep.WELCOME -> { haptic(HapticFeedbackConstants.CONFIRM); advance() }
            OnboardingStep.PERMISSIONS -> if (bluetoothAccess()) { haptic(HapticFeedbackConstants.CONFIRM); advance() } else requestNearby()
            OnboardingStep.FIND -> when (findAction()) {
                FindAction.CONTINUE -> { haptic(HapticFeedbackConstants.CONFIRM); advance(); if (step == OnboardingStep.CONNECTING) startConnecting() }
                FindAction.SCAN_AGAIN -> find.restart()
                FindAction.TURN_ON -> host.requestBluetoothOn()
                FindAction.ALLOW -> requestScan()
                FindAction.LOCATION -> host.openLocationSettings()
                FindAction.SETTINGS -> host.openBluetoothSettings()
                FindAction.WAIT -> {}
            }
            OnboardingStep.PIN -> submitPin()
            OnboardingStep.CONNECTING -> when (failure) {
                null -> cancelConnecting()
                Failure.BLUETOOTH_OFF -> host.requestBluetoothOn()
                Failure.NO_ACCESS -> requestNearby()
                Failure.TIMEOUT, Failure.LINK -> startConnecting()
                Failure.BOND -> host.openBluetoothSettings()
            }
            OnboardingStep.DONE -> {
                haptic(HapticFeedbackConstants.CONFIRM)
                when {
                    showingDirections -> { DisplaySetup.markDone(app); finish() }
                    directionsPending() -> showDirections()
                    else -> finish()
                }
            }
        }
    }

    private fun onSecondary() {
        if (finished) return
        if (step == OnboardingStep.WELCOME) { exploreDemo(); return }
        if (step == OnboardingStep.DONE && showingDirections) { finish(); return } // "Later": asked again on Navigate.
        if (step != OnboardingStep.CONNECTING) return
        attempt++
        if (connectRequested && host.linkPhase != LinkPhase.READY) host.cancelConnect()
        connectRequested = false
        revertAdded()
        go(OnboardingStep.FIND, forward = false)
    }

    /**
     * Welcome › "Explore demo": the shell closes this flow and opens the demo (riding). Ending the demo
     * without a scooter brings Welcome back, fresh (no snapshot of this flow is kept).
     */
    private fun exploreDemo() {
        haptic(HapticFeedbackConstants.CONFIRM)
        // Posted: the shell removes this flow, which must not happen inside its own button's click.
        Ui.post {
            if (finished) return@post
            host.setDemo(DemoScenario.RIDING)
            if (host.demoActive) { finished = true; snapshot = null }
        }
    }

    private fun finish() {
        if (finished) return
        val result = savedMac?.let { Scooters.find(app, it) }
            ?: selected?.let { SavedScooter(PairingRules.normalizeMac(it.mac) ?: it.mac, it.name, PairingRules.DEFAULT_NICKNAME, hasPin = false) }
        close(result)
    }

    private fun cancelFlow() {
        if (finished) return
        attempt++
        if (connectRequested && host.linkPhase != LinkPhase.READY) host.cancelConnect()
        revertAdded()
        val previous = displaced
        displaced = null
        close(null)
        // Adding was cancelled: go back to the scooter this flow let go of.
        if (previous != null && host.linkPhase == LinkPhase.IDLE && Scooters.find(app, previous) != null) Ui.post { host.connect(previous) }
    }

    private fun close(result: SavedScooter?) {
        finished = true
        snapshot = null
        hideKeyboard()
        pause()
        doneTask?.let(Ui::cancel); doneTask = null
        onDone(result)
    }

    /** Undoes a scooter this flow saved but never connected (cancel / back to Find). */
    private fun revertAdded() {
        val mac = savedMac ?: return
        if (addedByFlow && !connectedOnce) {
            runCatching { Scooters.forget(app, mac) }
            previousActive?.let { runCatching { Scooters.setActive(app, it) } }
        }
        savedMac = null
        addedByFlow = false
    }

    // ================================================================== permissions

    private fun granted(permission: String) = ctx.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun bluetoothAccess(): Boolean = Build.VERSION.SDK_INT < 31 || granted(Manifest.permission.BLUETOOTH_CONNECT)

    /** BLUETOOTH_SCAN in the manifest (Android 12+); without it Ruta lists paired scooters only. */
    private val scanDeclared: Boolean by lazy {
        Build.VERSION.SDK_INT < 31 || runCatching {
            val info = if (Build.VERSION.SDK_INT >= 33)
                ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
            else @Suppress("DEPRECATION") ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS)
            info.requestedPermissions?.contains(Manifest.permission.BLUETOOTH_SCAN) == true
        }.getOrDefault(false)
    }

    private fun scanGranted(): Boolean =
        if (Build.VERSION.SDK_INT >= 31) granted(Manifest.permission.BLUETOOTH_SCAN) else granted(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun bluetoothOn(): Boolean =
        runCatching { app.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true }.getOrDefault(false) || host.bluetoothEnabled

    private fun locationOn(): Boolean =
        runCatching { app.getSystemService(LocationManager::class.java)?.isLocationEnabled ?: true }.getOrDefault(true)

    private fun permissionState(permission: AppPermission): PermissionState {
        val reported = runCatching { host.permission(permission) }.getOrDefault(PermissionState.DENIED)
        if (permission != AppPermission.BLUETOOTH) return reported
        if (Build.VERSION.SDK_INT < 31) return PermissionState.NOT_NEEDED
        return when {
            bluetoothAccess() && (!scanDeclared || scanGranted()) -> PermissionState.GRANTED
            bluetoothAccess() -> PermissionState.PARTIAL
            reported == PermissionState.BLOCKED -> PermissionState.BLOCKED
            else -> PermissionState.DENIED
        }
    }

    private fun allGranted(): Boolean = listOf(AppPermission.BLUETOOTH, AppPermission.LOCATION, AppPermission.NOTIFICATIONS).all {
        val state = permissionState(it)
        state == PermissionState.GRANTED || state == PermissionState.NOT_NEEDED
    }

    /** Nearby devices: connect + scan in one Android dialog when both are declared; blocked → App info (via the host). */
    private fun requestNearby() {
        if (permissionState(AppPermission.BLUETOOTH) == PermissionState.BLOCKED || Build.VERSION.SDK_INT < 31 || !scanDeclared) {
            host.requestPermission(AppPermission.BLUETOOTH); return
        }
        scanAsked = true
        try { host.activity.requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN), REQUEST_NEARBY) }
        catch (_: Exception) { host.requestPermission(AppPermission.BLUETOOTH) }
    }

    /** Scan access for the Find step; after a permanent denial, App info. */
    private fun requestScan() {
        when {
            !bluetoothAccess() -> requestNearby()
            Build.VERSION.SDK_INT < 31 -> host.requestPermission(AppPermission.LOCATION)
            scanAsked && !host.activity.shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_SCAN) -> host.openAppSettings()
            else -> requestNearby()
        }
    }

    // ================================================================== Find

    private fun findAction(): FindAction {
        val scanner = find.scanner
        return when {
            scanner.limit == ScanLimit.BLUETOOTH_OFF || (!bluetoothOn() && bluetoothAccess()) -> FindAction.TURN_ON
            selected != null -> FindAction.CONTINUE
            find.hasRows -> FindAction.WAIT // Several scooters: pick one.
            scanner.limit == ScanLimit.NO_PERMISSION ->
                if (!bluetoothAccess() || (scanDeclared && !scanGranted())) FindAction.ALLOW else FindAction.SETTINGS
            scanner.limit == ScanLimit.LOCATION_OFF -> FindAction.LOCATION
            scanner.limit == ScanLimit.UNSUPPORTED -> FindAction.SETTINGS
            scanner.scanning -> FindAction.WAIT
            else -> FindAction.SCAN_AGAIN
        }
    }

    private fun pick(candidate: ScanCandidate) {
        if (selected?.mac == candidate.mac) return
        selected = candidate
        haptic(HapticFeedbackConstants.CLOCK_TICK)
        find.markSelection()
        updateChrome()
    }

    private fun onAdapterState(state: Int) {
        if (finished) return
        when (state) {
            BluetoothAdapter.STATE_ON -> when {
                step == OnboardingStep.FIND -> find.restart()
                step == OnboardingStep.CONNECTING && failure == Failure.BLUETOOTH_OFF -> startConnecting()
            }
            BluetoothAdapter.STATE_OFF -> if (step == OnboardingStep.FIND) find.restart()
        }
        updateChrome()
    }

    // ================================================================== PIN, connecting

    private fun submitPin() {
        val pin = pinPage.pin
        if (!PairingRules.isValidPin(pin)) { pinPage.showError(null); return }
        haptic(HapticFeedbackConstants.CONFIRM)
        hideKeyboard()
        go(OnboardingStep.CONNECTING, forward = true)
        startConnecting()
    }

    private fun pinPageNickname(): String = if (pinPageBuilt) pinPage.nickname else restoredNickname.orEmpty()

    /** Saves the scooter (PIN sealed off the main thread), makes it active and connects through the host. */
    private fun startConnecting() {
        val candidate = selected ?: run { go(OnboardingStep.FIND, forward = false); return }
        val mac = PairingRules.normalizeMac(candidate.mac) ?: return
        val token = ++attempt
        failure = null
        sawConnecting = false
        connectRequested = false
        attemptAt = Ui.uptimeMs()
        val existing = Scooters.find(app, mac)
        val nickname = PairingRules.nickname(pinPageNickname().ifBlank { existing?.nickname.orEmpty() })
        val pin = if (candidate.bonded) null else pinPage.pin.takeIf { PairingRules.isValidPin(it) }
        connecting.showWaiting(nickname, candidate.name)
        updateChrome()
        scope.launch {
            val scooter = SavedScooter(mac, candidate.name, nickname, hasPin = pin != null || existing?.hasPin == true)
            val sealed = withContext(Dispatchers.IO) { runCatching { Scooters.save(app, scooter, pin) }.getOrDefault(false) }
            if (token != attempt || finished || step != OnboardingStep.CONNECTING) return@launch
            // The PIN could not be stored (Keystore failure): pairing is not answered for the rider,
            // so Android asks for it. Show it to type or copy.
            if (pin != null && !sealed) connecting.showPin(pin)
            if (savedMac != mac) { addedByFlow = existing == null; savedMac = mac }
            val link = host.link
            if (link.phase == LinkPhase.READY && link.address?.equals(mac, ignoreCase = true) == true) { onLinked(); return@launch }
            val other = link.address?.takeIf { link.phase != LinkPhase.IDLE && !it.equals(mac, ignoreCase = true) }
            if (other != null && displaced == null) displaced = other
            attemptAt = Ui.uptimeMs()
            connectRequested = true
            host.connect(mac)
            watchLink()
        }
    }

    private fun watchLink() {
        if (finished || step != OnboardingStep.CONNECTING) return
        val mac = savedMac ?: return
        val link = host.link
        // The shell connects on its own once Nearby devices is granted / Bluetooth is on: follow it.
        val blocked = failure == Failure.NO_ACCESS || failure == Failure.BLUETOOTH_OFF
        if (blocked && link.phase != LinkPhase.IDLE && link.address?.equals(mac, ignoreCase = true) == true) {
            failure = null; connectRequested = true; sawConnecting = true; attemptAt = Ui.uptimeMs()
            connecting.showWaiting(Scooters.find(app, mac)?.nickname ?: PairingRules.DEFAULT_NICKNAME, selected?.name.orEmpty())
            updateChrome()
        }
        if (failure != null || !connectRequested) return
        val elapsed = Ui.uptimeMs() - attemptAt
        val ours = link.address?.equals(mac, ignoreCase = true) != false
        when {
            link.phase == LinkPhase.READY && ours -> { onLinked(); return }
            link.phase == LinkPhase.CONNECTING -> sawConnecting = true
            link.phase == LinkPhase.IDLE && !host.reconnecting && (sawConnecting || elapsed > START_GRACE_MS) -> { fail(link.error); return }
        }
        if (elapsed > CONNECT_TIMEOUT_MS) {
            attempt++
            host.cancelConnect()
            fail(null, Failure.TIMEOUT)
        }
    }

    /** [pairing]: the classified pairing failure (from [PairingEvent.BondFailed], else read back from [error]). */
    private fun fail(error: String?, kind: Failure? = null, pairing: PairingFailure? = PairingRules.failureFor(error)) {
        val reason = kind ?: when {
            !bluetoothAccess() -> Failure.NO_ACCESS
            !bluetoothOn() -> Failure.BLUETOOTH_OFF
            pairing == PairingFailure.STALE_BOND -> Failure.BOND
            else -> Failure.LINK
        }
        val name = selected?.name?.takeIf { it.isNotBlank() }
        failure = reason
        connectRequested = false
        val title = when (reason) {
            Failure.BLUETOOTH_OFF -> "Bluetooth is off"
            Failure.NO_ACCESS -> "Allow nearby devices"
            Failure.TIMEOUT -> "Took too long"
            Failure.LINK -> pairing?.let(PairingRules::failureTitle) ?: error?.takeIf { it.isNotBlank() && it.length <= 40 } ?: "Couldn't reach the scooter"
            Failure.BOND -> PairingRules.failureTitle(PairingFailure.STALE_BOND)
        }
        val line = when (reason) {
            Failure.BLUETOOTH_OFF, Failure.NO_ACCESS -> null
            Failure.TIMEOUT -> "Keep it on and nearby"
            Failure.LINK -> pairing?.let { PairingRules.failureHint(it, name) } ?: "Keep it on and close the official app"
            Failure.BOND -> PairingRules.failureHint(PairingFailure.STALE_BOND, name)
        }
        connecting.showFailure(reason, title, line)
        haptic(HapticFeedbackConstants.REJECT)
        updateChrome()
    }

    private fun onLinked() {
        connectedOnce = true
        connectRequested = false
        selected?.let { Scooters.updateBleName(app, it.mac, it.name) }
        go(OnboardingStep.DONE, forward = true)
    }

    private fun cancelConnecting() {
        attempt++
        if (connectRequested && host.linkPhase != LinkPhase.READY) host.cancelConnect()
        connectRequested = false
        failure = null
        val previous = OnboardingPlan.back(OnboardingStep.CONNECTING, addOnly, bonded()) ?: OnboardingStep.FIND
        if (previous == OnboardingStep.FIND) revertAdded()
        go(previous, forward = false)
    }

    private fun onPairing(mac: String, event: PairingEvent) {
        val ours = savedMac ?: selected?.mac?.let { PairingRules.normalizeMac(it) }
        if (finished || mac != ours || step != OnboardingStep.CONNECTING) return
        attemptAt = Ui.uptimeMs() // The bond step is live: the backstop timeout counts from here.
        when (event) {
            // Only a PIN the scooter received and refused reopens the PIN step (field shakes); every
            // other pairing failure says what happened on the Connecting step with its next step.
            is PairingEvent.BondFailed -> if (event.failure == PairingFailure.WRONG_PIN) {
                attempt++
                connectRequested = false
                go(OnboardingStep.PIN, forward = false)
                pinPage.showError(PairingRules.failureTitle(PairingFailure.WRONG_PIN) + "\n" + PairingRules.failureHint(PairingFailure.WRONG_PIN))
            } else {
                fail(null, pairing = event.failure)
            }
            is PairingEvent.SystemDialog -> {
                connecting.showPin(event.pin)
                host.showSnackbar("PIN ${event.pin}", "Copy") { copyPin(event.pin) }
            }
            is PairingEvent.Bonded -> connecting.setDetail("Paired")
            is PairingEvent.Failed -> {}
        }
    }

    private fun copyPin(pin: String) {
        val clipboard = ctx.getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText("Scooter PIN", pin)
        if (Build.VERSION.SDK_INT >= 33) clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        runCatching { clipboard.setPrimaryClip(clip) }
        if (Build.VERSION.SDK_INT < 33) host.showSnackbar("PIN copied")
    }

    // ================================================================== directions on the scooter (T2)

    /** The TFT's Navigation widget hasn't been confirmed yet (Navigate keeps offering it until it is). */
    private fun directionsPending(): Boolean = !DisplaySetup.isDone(app)

    /** DONE → the optional setup page (same step, so the dots and back behave as on DONE). */
    private fun showDirections() {
        if (finished || step != OnboardingStep.DONE || showingDirections) return
        doneTask?.let(Ui::cancel); doneTask = null
        done.leave()
        showingDirections = true
        if (directions.scroll.parent == null) stage.addView(directions.scroll, FrameLayout.LayoutParams(MATCH, MATCH))
        slide(done.scroll, directions.scroll, forward = true)
        directions.enter(animate = true)
        updateChrome()
    }

    // ================================================================== help, keyboard, haptics

    /** Bluetooth address entry (validated like every saved MAC); "Use" selects it on the Find step. */
    private fun showAddressSheet() {
        val shape = RoundedShapeDrawable(Theme.surfaceContainerHigh, ctx.dpf(Shapes.LARGE))
        val field = EditText(ctx).apply {
            id = View.generateViewId()
            applyStyle(Typography.bodyLarge)
            fontFeatureSettings = "tnum"
            setTextColor(Theme.onSurface)
            setHintTextColor(Theme.onSurfaceVariant)
            hint = "00:11:22:33:44:55"
            background = shape
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(17))
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            minHeight = ctx.dp(56)
            setPaddingRelative(ctx.dp(Spacing.L), 0, ctx.dp(Spacing.L), 0)
            textCursorDrawable = GradientDrawable().apply { setColor(Theme.primary); setSize(ctx.dp(2), ctx.dp(1)) }
            setOnFocusChangeListener { _, focused ->
                shape.strokeColor = if (focused) Theme.primary else android.graphics.Color.TRANSPARENT
                shape.strokeWidth = if (focused) ctx.dpf(2f) else 0f
            }
        }
        var sheet: BottomSheet? = null
        fun use() {
            val mac = PairingRules.normalizeMac(field.text?.toString()) ?: run { field.performHapticFeedback(HapticFeedbackConstants.REJECT); return }
            hideKeyboard()
            sheet?.dismiss()
            if (!finished && step == OnboardingStep.FIND) find.addManual(mac)
        }
        val useButton = ctx.actionButton("Use", ButtonVariant.FILLED) { use() }.apply { isEnabled = false }
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { useButton.isEnabled = PairingRules.normalizeMac(s?.toString()) != null }
        })
        field.setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_DONE) { use(); true } else false }
        val content = ctx.column().apply {
            addView(ctx.textView("Bluetooth address", Typography.labelLarge, Theme.onSurfaceVariant).apply { labelFor = field.id }, ctx.params())
            addView(field, ctx.params(Spacing.S))
            addView(ctx.textView("Shown in Android's Bluetooth settings or a BLE scanner app", Typography.bodySmall, Theme.onSurfaceVariant).apply {
                setPaddingRelative(ctx.dp(Spacing.L), 0, ctx.dp(Spacing.L), 0)
            }, ctx.params(Spacing.S))
            addView(useButton, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = ctx.dp(Spacing.L); gravity = Gravity.END })
        }
        sheet = host.showSheet(content, "Scooter address")
        showKeyboard(field)
    }

    private fun showHelp() {
        val group = ListGroup(ctx)
        group.addView(ListItem(ctx, "Switch it on", "Ignition on, phone close by", "power_settings_new"))
        group.addView(ListItem(ctx, "Close the official app", "It can hold the connection", "bluetooth_disabled"))
        group.addView(ListItem(ctx, "Find the PIN", "Most Chetaks pair without a PIN: pair it in Android Bluetooth settings first and it shows here as paired. If yours asks for a PIN or passkey, use the one on its display or in the Chetak app.", "key"))
        group.addView(ListItem(ctx, "Stays on this phone", "Encrypted, never shared", "lock"))
        host.showSheet(group, "Pairing help")
    }

    private fun showKeyboard(field: EditText) {
        field.requestFocus()
        field.post { if (field.isAttachedToWindow && field.hasFocus()) field.windowInsetsController?.show(WindowInsets.Type.ime()) }
    }

    private fun hideKeyboard() {
        root.findFocus()?.clearFocus()
        if (root.isAttachedToWindow) root.windowInsetsController?.hide(WindowInsets.Type.ime())
    }

    private fun haptic(kind: Int) { root.performHapticFeedback(kind) }

    private fun touchExploration(): Boolean =
        runCatching { ctx.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true }.getOrDefault(false)

    /** Rows/tiles enter with a 20 ms stagger (Addendum A). */
    private fun stagger(views: List<View>) {
        if (!Motion.enabled() || !root.isAttachedToWindow) return
        views.forEachIndexed { i, v ->
            v.animate().cancel()
            v.alpha = 0f; v.translationY = ctx.dpf(8f)
            v.animate().alpha(1f).translationY(0f).setStartDelay(20L * i).setDuration(Motion.MEDIUM2)
                .setInterpolator(Motion.emphasizedDecelerate).start()
        }
    }

    // ================================================================== config-change survival

    /** Restores a flow the shell rebuilt (font scale, density, palette change) within the last 10 minutes. */
    private fun restore() {
        val saved = snapshot ?: return
        snapshot = null
        if (saved.addOnly != addOnly || Ui.uptimeMs() - saved.at > 10 * 60_000L) return
        selected = saved.selected
        savedMac = saved.savedMac
        addedByFlow = saved.addedByFlow
        previousActive = saved.previousActive
        restoredNickname = saved.nickname
        step = when {
            saved.selected == null && saved.step.ordinal > OnboardingStep.FIND.ordinal -> OnboardingStep.FIND
            else -> saved.step
        }
        if (step == OnboardingStep.CONNECTING) {
            val link = host.link
            connectRequested = link.phase != LinkPhase.IDLE
            sawConnecting = link.phase != LinkPhase.IDLE
            attemptAt = Ui.uptimeMs()
            connecting.showWaiting(PairingRules.nickname(saved.nickname), saved.selected?.name.orEmpty())
        }
    }

    private class Snapshot(
        val addOnly: Boolean, val step: OnboardingStep, val selected: ScanCandidate?, val nickname: String,
        val savedMac: String?, val addedByFlow: Boolean, val previousActive: String?, val at: Long
    )

    private companion object {
        const val REQUEST_NEARBY = 0x5CA
        const val START_GRACE_MS = 4_000L
        /**
         * Backstop only: ScooterConnection times out each stage itself (35 s link, 90 s pairing armed at
         * link-up, 35 s setup after bonding). Restarted by every pairing event, so a rider still typing
         * in Android's pairing dialog is never cut off.
         */
        const val CONNECT_TIMEOUT_MS = 170_000L
        const val DONE_AUTO_MS = 2_400L
        var snapshot: Snapshot? = null
    }

    // ================================================================== pages

    private abstract inner class Page(titleText: String, lineText: String?) {
        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            accessibilityPaneTitle = titleText
        }
        val column = MaxWidthLayout(ctx, ctx.dp(Spacing.MAX_WIDTH)).apply {
            setPadding(ctx.dp(Spacing.MARGIN), ctx.dp(Spacing.S), ctx.dp(Spacing.MARGIN), ctx.dp(Spacing.XL))
        }
        val title = ctx.textView(titleText, Typography.displaySmall, Theme.onSurface).apply { isAccessibilityHeading = true }
        val line = ctx.textView(lineText.orEmpty(), Typography.bodyLarge, Theme.onSurfaceVariant)

        init { scroll.addView(column, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER_HORIZONTAL)) }

        fun header(topDp: Int = Spacing.L) {
            column.addView(title, ctx.params(topDp))
            column.addView(line, ctx.params(Spacing.S))
            line.visible(line.text.isNotEmpty())
        }

        fun setLine(text: CharSequence?) {
            line.show(text ?: "")
            line.visible(!text.isNullOrEmpty())
        }

        open fun enter(animate: Boolean) {}
        open fun leave() {}
        open fun refresh() {}
    }

    private inner class WelcomePage : Page("Chetooth", "Battery, range and rides for your Bajaj Chetak.") {
        // B2: the same mark as the launcher icon (electric moped on the lime cookie badge).
        private val mark = BrandMark(ctx, 96)

        init {
            // Balanced (2.2): mark, name and line are one centred block a little above the middle (free
            // space 2 : 3 above : below); Continue and "Explore demo" are anchored at the bottom by the
            // chrome. Taller than the screen (landscape, large text): the spacers collapse and it scrolls.
            column.gravity = Gravity.CENTER_HORIZONTAL
            title.applyStyle(Typography.displayMedium.emphasized)
            title.gravity = Gravity.CENTER_HORIZONTAL
            line.gravity = Gravity.CENTER_HORIZONTAL
            column.addView(View(ctx), LinearLayout.LayoutParams(MATCH, 0, 2f))
            column.addView(mark, LinearLayout.LayoutParams(ctx.dp(96), ctx.dp(96)).apply { gravity = Gravity.CENTER_HORIZONTAL })
            header(Spacing.XL)
            column.addView(View(ctx), LinearLayout.LayoutParams(MATCH, 0, 3f))
        }

        override fun enter(animate: Boolean) { mark.post { mark.settle() } } // Runs once attached.
    }

    private inner class PermissionsPage : Page("Allow access", "Only what your rides need.") {
        private val rows = listOf(
            PermissionRow(AppPermission.BLUETOOTH, "Nearby devices", "Required", "Finds and connects to your scooter"),
            PermissionRow(AppPermission.LOCATION, "Location", "Ride tracking", "Records rides with GPS and remembers where you park"),
            PermissionRow(AppPermission.NOTIFICATIONS, "Notifications", "Ride status and alerts", "The scooter link, range warnings and charge alerts")
        )
        private val group = ListGroup(ctx)

        init {
            header()
            rows.forEach { group.addView(it.item) }
            column.addView(group, ctx.params(Spacing.XXL))
        }

        override fun enter(animate: Boolean) { refresh(); if (animate) stagger(rows.map { it.item }) }
        override fun refresh() { rows.forEach { it.refresh() } }
    }

    private inner class PermissionRow(private val permission: AppPermission, label: String, supporting: String, tip: String) {
        // Standard tonal circle in every state (POLISH M1); only a blocked row's state text turns error-coloured.
        val item = ListItem(ctx, label, supporting, permission.icon).apply { tooltipText = tip }
        private val supportingText = supporting
        private var state: PermissionState? = null

        fun refresh() {
            val next = permissionState(permission)
            if (next == state) return
            state = next
            when (next) {
                PermissionState.GRANTED, PermissionState.NOT_NEEDED -> {
                    item.setTrailing(IconView(ctx, "check_circle", filled = true, tint = Theme.primary, description = "Allowed"))
                    item.setSupporting(supportingText)
                }
                PermissionState.BLOCKED -> {
                    item.setTrailing(ctx.tonalButton("Settings") { host.openAppSettings() })
                    item.setSupporting("Blocked", Theme.error)
                }
                PermissionState.PARTIAL, PermissionState.DENIED -> {
                    item.setTrailing(ctx.tonalButton("Allow") {
                        if (permission == AppPermission.BLUETOOTH) requestNearby() else host.requestPermission(permission)
                    })
                    item.setSupporting(supportingText)
                }
            }
        }
    }

    private inner class FindPage : Page("Find your scooter", "Switch it on and keep it close.") {
        val scanner = ScooterScanner(ctx) { list -> onResults(list) }.apply { onStateChange = { refresh() } }
        private val list = ListGroup(ctx)
        private val rows = LinkedHashMap<String, CandidateRow>()
        private val skeleton = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(SkeletonRowView(ctx, first = true), LinearLayout.LayoutParams(MATCH, ctx.dp(72)))
            addView(SkeletonRowView(ctx, first = false), LinearLayout.LayoutParams(MATCH, ctx.dp(72)).apply { topMargin = ctx.dp(2) })
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        private val statusRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = ctx.dp(Spacing.TOUCH) }
        private val loader = LoadingIndicator(ctx, sizeDp = 20)
        private val statusIcon = IconView(ctx, "info", iconSizeDp = Icons.SMALL)
        private val statusText = ctx.singleLine("Searching", Typography.labelLarge, Theme.onSurfaceVariant)
        private val scanAgain = ctx.actionButton("Scan again", ButtonVariant.TEXT, "refresh") { restart() }
        // 2.0's manual address entry: a scooter that is neither paired nor advertising (e.g. linked to
        // another phone right now) can still be added by its Bluetooth address.
        private val enterAddress = ctx.actionButton("Enter address", ButtonVariant.TEXT) { showAddressSheet() }
        private val emptyHolder = FrameLayout(ctx)
        private var emptyKey: String? = null
        private var wasScanning = false
        private var started = false
        val hasRows: Boolean get() = rows.isNotEmpty()

        init {
            header()
            column.addView(skeleton, ctx.params(Spacing.XXL))
            column.addView(list, ctx.params(Spacing.XXL))
            statusRow.addView(loader, LinearLayout.LayoutParams(ctx.dp(20), ctx.dp(20)).apply { marginStart = ctx.dp(Spacing.L); marginEnd = ctx.dp(Spacing.S) })
            statusRow.addView(statusIcon, LinearLayout.LayoutParams(ctx.dp(Icons.SMALL), ctx.dp(Icons.SMALL)).apply { marginStart = ctx.dp(Spacing.L); marginEnd = ctx.dp(Spacing.S) })
            statusRow.addView(statusText, LinearLayout.LayoutParams(0, WRAP, 1f))
            statusRow.addView(scanAgain, LinearLayout.LayoutParams(WRAP, WRAP))
            statusRow.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            column.addView(statusRow, ctx.params(Spacing.S))
            column.addView(emptyHolder, ctx.params(Spacing.XL))
            column.addView(enterAddress, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = ctx.dp(Spacing.S); gravity = Gravity.CENTER_HORIZONTAL })
            enterAddress.tooltipText = "Add a scooter by its Bluetooth address"
            list.visibility = View.GONE
            skeleton.visibility = View.GONE
        }

        /** Adds (or selects) the scooter at [mac] typed by the rider; Continue then pairs or connects as usual. */
        @Suppress("MissingPermission") // Nearby devices is granted before the Find step.
        fun addManual(mac: String) {
            val bonded = runCatching {
                app.getSystemService(BluetoothManager::class.java)?.adapter?.getRemoteDevice(mac)?.bondState == android.bluetooth.BluetoothDevice.BOND_BONDED
            }.getOrDefault(false)
            val existing = rows[mac]
            val candidate = existing?.candidate ?: ScanCandidate(mac, "", ScanCandidate.RSSI_UNKNOWN, bonded)
            if (existing == null) {
                val row = CandidateRow(candidate)
                rows[mac] = row
                list.addView(row)
                stagger(listOf(row))
            }
            selected = candidate
            haptic(HapticFeedbackConstants.CLOCK_TICK)
            markSelection()
            refresh()
            updateChrome()
        }

        override fun enter(animate: Boolean) { if (!scanner.scanning) restart() }

        override fun leave() { scanner.stop(); wasScanning = false }

        fun pauseScan() { wasScanning = scanner.scanning; scanner.stop() }

        fun resumeScan() {
            if (scanner.scanning) return
            if (!started || wasScanning || scanner.limit != ScanLimit.NONE || rows.isEmpty()) restart()
            wasScanning = false
        }

        fun restart() {
            started = true
            lastAccessKey = accessKey()
            scanner.start()
            refresh()
        }

        /** Re-scans once access, Bluetooth or location changed while a limit was showing. */
        fun poll() {
            if (scanner.scanning || scanner.limit == ScanLimit.NONE || scanner.limit == ScanLimit.FAILED) return
            if (accessKey() != lastAccessKey) restart()
        }

        private fun accessKey() = listOf(bluetoothAccess(), scanGranted(), bluetoothOn(), locationOn())

        private fun onResults(found: List<ScanCandidate>) {
            val added = ArrayList<View>()
            for (c in found) {
                val key = PairingRules.normalizeMac(c.mac) ?: continue
                val row = rows[key]
                if (row == null) {
                    val created = CandidateRow(c)
                    rows[key] = created
                    list.addView(created)
                    added += created
                } else row.update(c)
            }
            if (added.isNotEmpty()) stagger(added)
            val current = selected
            if (current != null) rows[PairingRules.normalizeMac(current.mac)]?.let { selected = it.candidate }
            if (selected == null && rows.size == 1) { selected = rows.values.first().candidate; markSelection() }
            refresh()
        }

        fun markSelection() {
            val key = selected?.mac?.let { PairingRules.normalizeMac(it) }
            for ((mac, row) in rows) row.picked = mac == key
        }

        override fun refresh() {
            val limit = scanner.limit
            val btOff = limit == ScanLimit.BLUETOOTH_OFF || (!bluetoothOn() && bluetoothAccess())
            if (btOff && selected != null && step == OnboardingStep.FIND) { selected = null; markSelection() }
            val showRows = rows.isNotEmpty() && !btOff
            list.visible(showRows)
            skeleton.visible(!showRows && scanner.scanning)
            val pairedOnly = showRows && !scanner.scanning && limit == ScanLimit.NO_PERMISSION
            val canRescan = showRows && !scanner.scanning && (limit == ScanLimit.NONE || limit == ScanLimit.FAILED)
            loader.visible(scanner.scanning)
            statusIcon.visible(pairedOnly)
            scanAgain.visible(canRescan)
            statusText.show(when {
                scanner.scanning -> "Searching"
                pairedOnly -> "Paired scooters only"
                else -> ""
            })
            statusRow.visible(scanner.scanning || pairedOnly || canRescan)
            showEmpty(if (showRows || scanner.scanning) null else emptyFor(limit, btOff))
            updateChrome()
        }

        private fun emptyFor(limit: ScanLimit, btOff: Boolean): Triple<String, String, String?> = when {
            btOff -> Triple("bluetooth_disabled", "Bluetooth is off", null)
            limit == ScanLimit.NO_PERMISSION && (!bluetoothAccess() || (scanDeclared && !scanGranted())) ->
                Triple("bluetooth", "Allow nearby devices", "To find your scooter")
            limit == ScanLimit.NO_PERMISSION -> Triple("bluetooth_searching", "Pair it first", "In Android Bluetooth settings")
            limit == ScanLimit.LOCATION_OFF -> Triple("location_on", "Location is off", "Android 11 needs it to scan")
            limit == ScanLimit.UNSUPPORTED -> Triple("bluetooth_disabled", "Bluetooth unavailable", null)
            limit == ScanLimit.FAILED -> Triple("error", "Scan failed", "Try again in a moment")
            else -> Triple("bluetooth_searching", "No scooter found", "Switch it on and close the official app")
        }

        private fun showEmpty(content: Triple<String, String, String?>?) {
            val key = content?.toString()
            if (key == emptyKey) return
            emptyKey = key
            emptyHolder.removeAllViews()
            if (content != null) {
                val state = ctx.emptyState(content.first, content.second, content.third)
                emptyHolder.addView(state, FrameLayout.LayoutParams(MATCH, WRAP))
                stagger(listOf(state))
            }
            emptyHolder.visible(content != null)
        }
    }

    /** One scooter on the Find step: tap to select (leading check, tonal container). */
    private inner class CandidateRow(var candidate: ScanCandidate) : ListItem(ctx, candidate.name.ifBlank { "Scooter" }, null, "electric_moped") {
        private val bars = SignalBarsView(ctx)

        var picked = false
            set(value) {
                if (field == value) return
                field = value
                showLeading()
                requestLayout() // ListGroup re-shapes the row with the selected colour.
                invalidate()
            }

        init {
            setTrailing(bars)
            setOnClick { pick(candidate) }
            update(candidate)
            picked = selected?.mac?.equals(candidate.mac, ignoreCase = true) == true
            showLeading()
        }

        /** Selected = filled check; paired with this phone = Bluetooth-connected glyph; else the scooter. */
        private fun showLeading() {
            setIcon(when { picked -> "check_circle"; candidate.bonded -> "bluetooth_connected"; else -> "electric_moped" }, filled = picked)
            setLeadingTone(if (picked) Tone.PRIMARY else Tone.SECONDARY)
        }

        fun update(next: ScanCandidate) {
            candidate = next
            setHeadline(next.name.ifBlank { "Scooter" })
            val saved = Scooters.find(app, next.mac) != null
            setSupporting(when {
                saved -> "Saved"
                next.bonded -> "Paired"
                else -> "Not paired"
            })
            bars.level = next.bars
            bars.visible(next.bars > 0)
            showLeading()
            val spoken = listOf(next.name.ifBlank { "Scooter" }, if (next.bonded) "paired" else "not paired", PairingRules.signalWord(next.bars))
            contentDescription = spoken.joinToString(", ")
        }

        override fun setGroupShape(color: Int, topRadius: Float, bottomRadius: Float) =
            super.setGroupShape(if (picked) Theme.secondaryContainer else color, topRadius, bottomRadius)

        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            info.markChecked(picked)
        }
    }

    private inner class PinPage : Page("Scooter PIN", null) {
        private val box = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        private val boxShape = RoundedShapeDrawable(Theme.surfaceContainerHigh, ctx.dpf(Shapes.LARGE))
        private val pinStyle = Typography.headlineLarge.withWeight(500)
        private val pinField = EditText(ctx)
        private val reveal = IconActionButton(ctx, "visibility", "Show PIN", toggleable = true)
        private val pinLabel = ctx.textView("PIN", Typography.labelLarge, Theme.onSurfaceVariant)
        private val helper = ctx.textView("${PairingRules.PIN_MIN} to ${PairingRules.PIN_MAX} digits", Typography.bodySmall, Theme.onSurfaceVariant)
        private val nickLabel = ctx.textView("Nickname", Typography.labelLarge, Theme.onSurfaceVariant)
        private val nickShape = RoundedShapeDrawable(Theme.surfaceContainerHigh, ctx.dpf(Shapes.LARGE))
        private val nick = EditText(ctx)
        private val shake = Spring(0f, stiffness = 1200f, dampingRatio = 0.22f, threshold = 0.5f) { box.translationX = it }
        private var hasError = false
        private var forMac: String? = null

        val pin: String get() = PairingRules.sanitizePin(pinField.text)
        val nickname: String get() = nick.text?.toString().orEmpty()

        init {
            pinPageBuilt = true
            header()
            pinField.id = View.generateViewId()
            nick.id = View.generateViewId()
            pinLabel.labelFor = pinField.id
            nickLabel.labelFor = nick.id

            pinField.apply {
                applyStyle(pinStyle)
                letterSpacing = 0.25f
                fontFeatureSettings = "tnum"
                setTextColor(Theme.onSurface)
                setHintTextColor(Theme.onSurfaceVariant)
                background = null
                isSingleLine = true
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                typeface = pinStyle.typeface // Password input types reset the face to monospace.
                filters = arrayOf(DIGITS_ONLY, InputFilter.LengthFilter(PairingRules.PIN_MAX))
                imeOptions = EditorInfo.IME_ACTION_NEXT or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                highlightColor = alpha(Theme.primary, 0.35f)
                textCursorDrawable = GradientDrawable().apply { setColor(Theme.primary); setSize(ctx.dp(2), ctx.dp(1)) }
                setPaddingRelative(ctx.dp(20), ctx.dp(Spacing.M), 0, ctx.dp(Spacing.M))
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: Editable?) { if (hasError) clearError(); updateChrome() }
                })
                setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_NEXT) { nick.requestFocus(); true } else false }
                setOnFocusChangeListener { _, _ -> restyleBox() }
            }
            reveal.onCheckedChange = { shown ->
                val at = pinField.selectionEnd
                pinField.inputType = if (shown) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                pinField.typeface = pinStyle.typeface
                pinField.setSelection(at.coerceIn(0, pinField.length()))
                reveal.setIcon("visibility", if (shown) "Hide PIN" else "Show PIN")
            }
            box.background = boxShape
            box.minimumHeight = ctx.dp(72)
            box.addView(pinField, LinearLayout.LayoutParams(0, WRAP, 1f))
            box.addView(reveal, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = ctx.dp(Spacing.XS) })
            column.addView(pinLabel, ctx.params(Spacing.XXL))
            column.addView(box, ctx.params(Spacing.S))
            column.addView(helper, ctx.params(Spacing.S).apply { marginStart = ctx.dp(Spacing.L) })
            helper.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE

            nick.apply {
                applyStyle(Typography.bodyLarge)
                setTextColor(Theme.onSurface)
                setHintTextColor(Theme.onSurfaceVariant)
                hint = PairingRules.DEFAULT_NICKNAME
                background = nickShape
                isSingleLine = true
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
                filters = arrayOf(InputFilter.LengthFilter(PairingRules.NICKNAME_MAX))
                imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                highlightColor = alpha(Theme.primary, 0.35f)
                textCursorDrawable = GradientDrawable().apply { setColor(Theme.primary); setSize(ctx.dp(2), ctx.dp(1)) }
                minHeight = ctx.dp(56)
                setPaddingRelative(ctx.dp(Spacing.L), 0, ctx.dp(Spacing.L), 0)
                setOnEditorActionListener { _, action, _ ->
                    if (action == EditorInfo.IME_ACTION_DONE) { if (PairingRules.isValidPin(pin)) submitPin() else pinField.requestFocus(); true } else false
                }
                setOnFocusChangeListener { _, focused ->
                    nickShape.strokeColor = if (focused) Theme.primary else android.graphics.Color.TRANSPARENT
                    nickShape.strokeWidth = if (focused) ctx.dpf(2f) else 0f
                }
            }
            column.addView(nickLabel, ctx.params(Spacing.XL))
            column.addView(nick, ctx.params(Spacing.S))
            restyleBox()
        }

        override fun enter(animate: Boolean) {
            val c = selected
            setLine(c?.name?.ifBlank { null })
            val mac = c?.mac?.let { PairingRules.normalizeMac(it) }
            if (mac != forMac) {
                forMac = mac
                pinField.setText("")
                clearError()
                val existing = mac?.let { Scooters.find(app, it) }
                val restored = restoredNickname
                restoredNickname = null
                nick.setText(restored ?: existing?.nickname?.takeIf { it != PairingRules.DEFAULT_NICKNAME }.orEmpty())
            }
            showKeyboard(pinField)
        }

        override fun leave() { shake.cancel(); box.translationX = 0f }

        /** [message] null = just shake (invalid length); otherwise show it under the pinField. */
        fun showError(message: String?) {
            if (message != null) {
                hasError = true
                helper.show(message)
                helper.tint(Theme.error)
                restyleBox()
                pinField.selectAll()
            }
            haptic(HapticFeedbackConstants.REJECT)
            if (Motion.enabled()) { shake.snapTo(0f); shake.animateTo(0f, ctx.dpf(900f)) }
            showKeyboard(pinField)
        }

        private fun clearError() {
            hasError = false
            helper.show("${PairingRules.PIN_MIN} to ${PairingRules.PIN_MAX} digits")
            helper.tint(Theme.onSurfaceVariant)
            restyleBox()
        }

        private fun restyleBox() {
            val color = when {
                hasError -> Theme.error
                pinField.hasFocus() -> Theme.primary
                else -> android.graphics.Color.TRANSPARENT
            }
            boxShape.strokeColor = color
            boxShape.strokeWidth = if (color == android.graphics.Color.TRANSPARENT) 0f else ctx.dpf(2f)
        }
    }

    private inner class ConnectingPage : Page("Connecting", null) {
        private val loader = LoadingIndicator(ctx, contained = true, sizeDp = 96)
        private val badgeShape = RoundedShapeDrawable(Theme.errorContainer, ctx.dpf(Shapes.FULL))
        private val badgeIcon = IconView(ctx, "error", tint = Theme.onErrorContainer, iconSizeDp = 40)
        private val badge = FrameLayout(ctx).apply {
            background = badgeShape
            addView(badgeIcon, FrameLayout.LayoutParams(ctx.dp(40), ctx.dp(40), Gravity.CENTER))
        }
        private val detail = ctx.textView("", Typography.bodyMedium, Theme.onSurfaceVariant).apply { gravity = Gravity.CENTER_HORIZONTAL }
        private val pinCard = Card(ctx, CardStyle.FILLED, 20)
        private val pinDigits = ctx.singleLine("", Typography.headlineMedium.withWeight(500), Theme.onSurface).tabular()
        private var shownPin: String? = null

        init {
            column.gravity = Gravity.CENTER_VERTICAL
            val visual = FrameLayout(ctx)
            visual.addView(loader, FrameLayout.LayoutParams(ctx.dp(96), ctx.dp(96), Gravity.CENTER))
            visual.addView(badge, FrameLayout.LayoutParams(ctx.dp(96), ctx.dp(96), Gravity.CENTER))
            column.addView(visual, ctx.params(0, MATCH, ctx.dp(96)))
            header(Spacing.XL)
            title.gravity = Gravity.CENTER_HORIZONTAL
            line.gravity = Gravity.CENTER_HORIZONTAL
            title.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            column.addView(detail, ctx.params(Spacing.XS))
            detail.visibility = View.GONE

            val pinRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val keyCircle = FrameLayout(ctx).apply {
                background = RoundedShapeDrawable(Tone.SECONDARY.container, ctx.dpf(Shapes.FULL))
                addView(IconView(ctx, "key", tint = Tone.SECONDARY.onContainer), FrameLayout.LayoutParams(ctx.dp(24), ctx.dp(24), Gravity.CENTER))
            }
            val texts = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(ctx.singleLine("Type this PIN", Typography.labelLarge, Theme.onSurfaceVariant))
                addView(pinDigits)
            }
            pinDigits.letterSpacing = 0.15f
            pinRow.addView(keyCircle, LinearLayout.LayoutParams(ctx.dp(40), ctx.dp(40)).apply { marginEnd = ctx.dp(Spacing.L) })
            pinRow.addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
            pinRow.addView(ctx.tonalButton("Copy", "content_copy") { shownPin?.let { copyPin(it) } })
            pinCard.addView(pinRow, LinearLayout.LayoutParams(MATCH, WRAP))
            pinCard.tooltipText = "Android asks for the PIN on this phone"
            column.addView(pinCard, ctx.params(Spacing.XXL))
            pinCard.visibility = View.GONE
            badge.visibility = View.GONE
        }

        fun showWaiting(nickname: String, bleName: String) {
            title.show("Connecting")
            setLine(nickname)
            detail.visible(false)
            badge.visible(false)
            loader.visible(true)
            pinCard.visible(false)
            shownPin = null
            if (bleName.isNotBlank()) loader.contentDescription = "Connecting to $bleName"
        }

        fun setDetail(text: String?) {
            detail.show(text ?: "")
            detail.visible(!text.isNullOrEmpty())
        }

        fun showPin(pin: String) {
            shownPin = pin
            pinDigits.show(pin)
            pinCard.contentDescription = "PIN " + pin.toCharArray().joinToString(" ")
            if (pinCard.visibility != View.VISIBLE) { pinCard.visibility = View.VISIBLE; stagger(listOf(pinCard)) }
        }

        fun showFailure(kind: Failure, titleText: String, lineText: String?) {
            loader.visible(false)
            pinCard.visible(false)
            detail.visible(false)
            badgeIcon.setIcon(when (kind) {
                Failure.BLUETOOTH_OFF -> "bluetooth_disabled"
                Failure.NO_ACCESS -> "bluetooth"
                Failure.TIMEOUT -> "timer"
                Failure.LINK -> "error"
                Failure.BOND -> "link_off"
            })
            val tone = if (kind == Failure.LINK || kind == Failure.TIMEOUT) Tone.ERROR else Tone.WARNING
            badgeShape.color = tone.container
            badgeIcon.tint = tone.onContainer
            badge.visible(true)
            if (Motion.enabled() && root.isAttachedToWindow) {
                badge.scaleX = 0.6f; badge.scaleY = 0.6f; badge.alpha = 0f
                badge.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(Motion.MEDIUM2).setInterpolator(Motion.emphasizedDecelerate).start()
            }
            title.show(titleText)
            setLine(lineText)
        }
    }

    private inner class DonePage : Page("You're all set", null) {
        private val mark = BrandMark(ctx, 96)

        init {
            column.gravity = Gravity.CENTER_VERTICAL
            column.addView(mark, LinearLayout.LayoutParams(ctx.dp(96), ctx.dp(96)))
            header(Spacing.XL)
        }

        override fun enter(animate: Boolean) {
            val name = savedMac?.let { Scooters.find(app, it)?.nickname } ?: PairingRules.DEFAULT_NICKNAME
            setLine("$name is connected")
            mark.post { mark.settle() }
            haptic(HapticFeedbackConstants.CONFIRM)
            doneTask?.let(Ui::cancel)
            // Moves on by itself (not with TalkBack): to the optional display setup, else home.
            doneTask = if (touchExploration()) null else Ui.postDelayed(DONE_AUTO_MS) {
                doneTask = null
                if (step == OnboardingStep.DONE && !showingDirections) { if (directionsPending()) showDirections() else finish() }
            }
        }

    }

    /** T2: the same four steps as Navigate's "Scooter display setup" sheet. "Done" remembers it; "Later" doesn't. */
    private inner class DirectionsPage : Page("Directions on your scooter", "Once, on the scooter display.") {
        private val steps = DisplaySetup.stepsView(ctx)

        init {
            header()
            column.addView(steps, ctx.params(Spacing.XXL))
        }

        override fun enter(animate: Boolean) {
            if (animate && steps is LinearLayout) stagger((0 until steps.childCount).map { steps.getChildAt(it) })
        }
    }
}

/** Digits only (typing and pasting); the length filter caps it at 8. */
private val DIGITS_ONLY = InputFilter { source, start, end, _, _, _ ->
    var clean = true
    for (i in start until end) if (source[i] !in '0'..'9') { clean = false; break }
    if (clean) null else PairingRules.sanitizePin(source.subSequence(start, end))
}

/** Step indicator: dots that stretch into a pill for the current step; done steps in primary. */
private class OnboardingDotsView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var count = 0
    private var position = 0f
    private val spring = Spring(0f, threshold = 0.002f) { position = it; invalidate() }

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }

    fun set(index: Int, total: Int, animate: Boolean) {
        if (total != count) { count = total; invalidate() }
        val spoken = "Step ${index + 1} of $total"
        if (contentDescription != spoken) contentDescription = spoken
        if (animate) spring.animateTo(index.toFloat()) else spring.snapTo(index.toFloat())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (count <= 0) return
        val dot = dpf(6f); val pill = dpf(24f); val gap = dpf(6f)
        var total = gap * (count - 1)
        for (i in 0 until count) total += lerp(dot, pill, (1f - abs(position - i)).coerceIn(0f, 1f))
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        var x = (width - total) / 2f
        val top = (height - dot) / 2f
        for (k in 0 until count) {
            val i = if (rtl) count - 1 - k else k
            val w = lerp(dot, pill, (1f - abs(position - i)).coerceIn(0f, 1f))
            paint.color = blend(Theme.outlineVariant, Theme.primary, (position - i + 1f).coerceIn(0f, 1f))
            rect.set(x, top, x + w, top + dot)
            canvas.drawRoundRect(rect, dot / 2f, dot / 2f, paint)
            x += w + gap
        }
    }
}

/** Four signal bars (rounded), filled up to [level]; spoken as "Good signal" etc. */
private class SignalBarsView(context: Context) : View(context) {
    private val on = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.onSurfaceVariant }
    private val off = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.outlineVariant }
    private val rect = RectF()

    var level: Int = -1
        set(value) {
            if (field == value) return
            field = value
            // No tooltip: it is the trailing view of a clickable row (a tooltip would take the tap).
            contentDescription = PairingRules.signalWord(value)
            invalidate()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(dp(Spacing.TOUCH), widthMeasureSpec), resolveSize(dp(Spacing.TOUCH), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val barW = dpf(3.5f); val gap = dpf(2.5f)
        val heights = floatArrayOf(dpf(5f), dpf(8.5f), dpf(12f), dpf(15.5f))
        val total = barW * 4 + gap * 3
        var x = (width - total) / 2f
        val bottom = height / 2f + dpf(8f)
        for (i in 0 until 4) {
            rect.set(x, bottom - heights[i], x + barW, bottom)
            canvas.drawRoundRect(rect, barW / 2f, barW / 2f, if (i < level) on else off)
            x += barW + gap
        }
    }
}

/** Loading placeholder shaped like a list row (grouped-list corners), gently pulsing. */
private class SkeletonRowView(context: Context, first: Boolean) : View(context) {
    private val container = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.surfaceContainer }
    private val block = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.surfaceContainerHigh }
    private val path = Path()
    private val rect = RectF()
    private val radii: FloatArray
    private val origin = android.os.SystemClock.uptimeMillis()

    init {
        val outer = dpf(Shapes.LIST_OUTER); val inner = dpf(Shapes.LIST_INNER)
        val top = if (first) outer else inner; val bottom = if (first) inner else outer
        radii = floatArrayOf(top, top, top, top, bottom, bottom, bottom, bottom)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        path.reset()
        rect.set(0f, 0f, w.toFloat(), h.toFloat())
        path.addRoundRect(rect, radii, Path.Direction.CW)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawPath(path, container)
        val moving = isAttachedToWindow && isShown && Motion.enabled()
        val t = if (moving) (android.os.SystemClock.uptimeMillis() - origin) % 1_400L / 1_400f else 0f
        block.alpha = (255 * (0.55f + 0.45f * (0.5f + 0.5f * cos(t * 2f * Math.PI.toFloat())))).toInt()
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val cy = height / 2f
        val circleX = if (rtl) width - dpf(36f) else dpf(36f)
        canvas.drawCircle(circleX, cy, dpf(20f), block)
        val textStart = dpf(72f)
        val long = width * 0.42f; val short = width * 0.24f
        fun bar(top: Float, length: Float, h: Float) {
            if (rtl) rect.set(width - textStart - length, top, width - textStart, top + h) else rect.set(textStart, top, textStart + length, top + h)
            canvas.drawRoundRect(rect, h / 2f, h / 2f, block)
        }
        bar(cy - dpf(12f), long, dpf(12f))
        bar(cy + dpf(4f), short, dpf(10f))
        if (moving) postInvalidateOnAnimation()
    }
}
