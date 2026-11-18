package dev.codesurgen.ruta

import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.SystemClock
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/*
 * More tab (DESIGN › More, Addendum B/C): grouped lists with leading icon containers.
 *  - Scooters: saved scooters (nickname, BLE name, active check; tap = make active and connect),
 *    per-scooter options sheet (connect/disconnect, rename, change PIN, forget → offer Bluetooth
 *    settings), Add scooter (host opens the onboarding flow).
 *  - Features: Directions, Music, Calls, Handlebar buttons, Auto-connect switches.
 *  - Alerts (2.2): range warning, charge alert with its "Notify at" 80 / 90 / 100 % group, charging
 *    stopped, remember where I park, show who messaged (sender on the scooter's music line; off by
 *    default, needs no notification permission); a "Notifications off · Allow" row while alerts can't reach the
 *    shade (they still show in-app). Settings live in [Alerts].
 *  - Permissions: one row each (icon in the standard tonal circle, name, check or error-coloured
 *    state, grant button).
 *  - Data: Export CSV, Diagnostics (sub-page: tests, battery unit reset, demo mode switch, scooter
 *    info, raw probe replies, log viewer with copy/share in the top bar). Back closes the sub-page.
 *  - About: the Ruta mark and version, open source link ([SOURCE_URL]), licences sheet
 *    (res/raw/licenses), disclaimer.
 * Rows that open another page or flow carry a chevron; nothing else changes the screen.
 * Explanations are tooltips/contentDescription; feedback is a snackbar. Storage reads that can
 * touch the disk (licences) run on Dispatchers.IO; listeners and jobs end in dispose().
 */
class MoreScreen(private val host: Host) : Screen {
    companion object {
        /** Project page for the "Open source" row (source code and issue tracker). */
        const val SOURCE_URL = "https://github.com/vsshegur/Chetooth"
        private const val FALLBACK_VERSION = "1.0.2"
        private const val LOG_LINES = 300
        private const val PROBE_MS = 20_000L
        /** Diagnostics › Demo mode's line while it is off. */
        private const val DEMO_OFF = "Simulated scooter and history"
        /** Survives a shell rebuild (rotation) so Diagnostics reopens where it was. */
        private var diagnosticsOpen = false
        /** Stats tags as the probe log names them (Diagnostics only). */
        private val TAG_NAMES = mapOf(
            1 to "BMS", 2 to "Dynamics", 3 to "Static", 4 to "EV faults", 5 to "MCU", 6 to "Temperature",
            7 to "NMEA", 8 to "IMU", 9 to "Alerts", 10 to "Network", 11 to "ECU", 12 to "ICE faults", 13 to "Status"
        )
    }

    private val ctx = host.context
    private val scope = Ui.scope()
    private val bar = TopAppBar(ctx, large = true).apply { title = "More" }
    private val main = Scaffold(ctx, bar)
    override val view: View = main

    private var shown = false
    private var entered = false
    private var disposed = false
    private var sheet: BottomSheet? = null
    private var diagnostics: Diagnostics? = null

    // ------------------------------------------------------------------ scooters

    private val scooterGroup = ListGroup(ctx)
    private val scooterRows = LinkedHashMap<String, ScooterRow>()
    private val addRow = ListItem(ctx, "Add scooter", null, "add", IconView(ctx, "chevron_right")) { addScooter() }
    private var activeMac: String? = null
    private val scootersChanged: () -> Unit = { Ui.post { if (!disposed) reloadScooters() } } // after the host reloaded its list
    /** Demo mode started, switched or ended (main thread): Diagnostics' switch follows it. */
    private val demoChanged: () -> Unit = { if (!disposed) diagnostics?.renderDemo() }

    // ------------------------------------------------------------------ features, permissions

    private val featureRows: Map<Feature, SwitchListItem> = Feature.entries.filter { it == Feature.AUTO_CONNECT }.associateWith { feature -> // Chetooth: display features are Hero-only
        SwitchListItem(ctx, feature.label, icon = feature.icon, checked = host.isEnabled(feature)) { on ->
            host.setEnabled(feature, on)
            main.haptic(HapticFeedbackConstants.CLOCK_TICK)
        }.apply { tooltipText = featureTip(feature) }
    }
    private var handlebarText: String? = null

    // ------------------------------------------------------------------ alerts

    private val rangeAlert = alertSwitch(AlertKind.RANGE, "Range warning", "While navigating", "battery_alert",
        "Once per trip, when the Maps route leaves under ${RangeCheck.RESERVE_KM.toInt()} km of range to spare")
    private val chargeAlert = alertSwitch(AlertKind.CHARGE_TARGET, "Charge alert", "Notify at", "battery_charging_full",
        "Once per charge, when the scooter reaches the charge below. Needs the scooter connected while it charges")
    private val stoppedAlert = alertSwitch(AlertKind.CHARGE_STOPPED, "Charging stopped", null, "ev_station",
        "When the charge stops rising for ${ChargeDetector.STALL_MS / 60_000} min below the charge you chose. Needs the scooter connected while it charges")
    private val parkingSwitch = alertSwitch(AlertKind.PARKING, "Remember where I park", null, "pin_drop",
        "Saves where you leave the scooter; Home shows it while you're away. Off forgets it")
    private val messageSwitch = alertSwitch(AlertKind.MESSAGE_SENDER, "Show who messaged", "On the scooter display", "chat",
        "Sender name on the scooter's music line when nothing is playing")
    private val targetGroup = ButtonGroup(
        ctx, Alerts.TARGETS.map { GroupItem("$it %", description = "Notify at $it percent") },
        selected = Alerts.TARGETS.indexOf(Alerts.chargeTarget(ctx)).coerceAtLeast(0)
    ) { index -> setTarget(index) }
    private val accessButton = ctx.tonalButton("Allow") { allowAlerts() }
    private val accessRow = ListItem(ctx, "Notifications off", null, "notifications", accessButton) { allowAlerts() }.apply {
        tooltipText = "Until allowed, alerts show only while Chetooth is open"
    }
    private var alertsKey: List<Any>? = null
    private var alertAccess = AlertAccess.ON
    private var notificationsBlocked = false

    private val permissionRows = AppPermission.entries.map { PermissionRow(it) }
    private var permissionsAt = 0L

    // ------------------------------------------------------------------ about

    private val versionRow = AboutRow()

    init {
        val page = main.page

        addSection("Scooters", first = true)
        addRow.tooltipText = "Add another scooter"
        scooterGroup.addView(addRow)
        page.add(scooterGroup, 0)

        addSection("Features")
        val features = ListGroup(ctx)
        featureRows.values.forEach { features.addView(it) }
        page.add(features, 0)

        addSection("Alerts")
        val alerts = ListGroup(ctx)
        alerts.addView(accessRow)
        alerts.addView(rangeAlert)
        alerts.addView(ChargeAlertBlock())
        alerts.addView(stoppedAlert)
        alerts.addView(parkingSwitch)
        alerts.addView(messageSwitch)
        page.add(alerts, 0)

        addSection("Permissions")
        val permissions = ListGroup(ctx)
        permissionRows.forEach { permissions.addView(it.item) }
        page.add(permissions, 0)

        addSection("Chetak")
        val chetak = ListGroup(ctx)
        chetak.addView(ListItem(ctx, "Chetak setup", "Teach the app your battery % and range", "battery_5_bar", IconView(ctx, "chevron_right")) { host.openChetakSetup() }.apply {
            tooltipText = "Learns where your Chetak sends its battery % and range"
        })
        page.add(chetak, 0)

        addSection("Data")
        val data = ListGroup(ctx)
        data.addView(ListItem(ctx, "Export CSV", null, "download") { exportCsv() }.apply {
            tooltipText = "Battery history, rides and charging sessions in one file"
        })
        data.addView(ListItem(ctx, "Diagnostics", null, "bug_report", IconView(ctx, "chevron_right")) { openDiagnostics() }.apply {
            tooltipText = "Connection log, scooter tests and raw data"
        })
        page.add(data, 0)

        addSection("About")
        val about = ListGroup(ctx)
        versionRow.setVersion(host.versionName.ifEmpty { FALLBACK_VERSION })
        about.addView(versionRow)
        // Shown once SOURCE_URL names a repository (owner/repo), never as a bare placeholder.
        if (hasRepository()) about.addView(ListItem(ctx, "Open source", sourceLabel(), "link") { openSource() }.apply {
            tooltipText = "Source code and issues"
        })
        about.addView(ListItem(ctx, "Licences", null, "info", IconView(ctx, "chevron_right")) { openLicences() }.apply {
            tooltipText = "Open source licences, including Material Symbols"
        })
        page.add(about, 0)
        page.add(ctx.textView("Chetooth is a fork of Ruta. Not affiliated with Bajaj Auto, Chetak Technology, Hero MotoCorp or Vida.", Typography.bodySmall, Theme.onSurfaceVariant).apply {
            setPadding(ctx.dp(Spacing.L), 0, ctx.dp(Spacing.L), 0)
        }, Spacing.L)

        Scooters.addListener(scootersChanged)
        Demo.addListener(demoChanged)
        reloadScooters()
        refreshPermissions(force = true)
        renderAlerts(force = true)
        if (diagnosticsOpen) openDiagnostics(animate = false)
    }

    private fun addSection(title: String, first: Boolean = false) {
        // The header carries 8 dp padding above and below: 16 + 8 = 24 dp between sections.
        main.page.add(ctx.sectionHeader(title), if (first) 0 else Spacing.SECTION - Spacing.S)
    }

    // ------------------------------------------------------------------ Screen

    override fun onShow() {
        shown = true
        host.refreshScooters()
        reloadScooters()
        refreshPermissions(force = true)
        renderAlerts(force = true)
        render(System.currentTimeMillis())
        if (!entered) { entered = true; enterStagger() }
    }

    override fun onHide() {
        shown = false
    }

    override fun onBack(): Boolean {
        if (diagnosticsOpen && diagnostics != null) { closeDiagnostics(); return true }
        return false
    }

    override fun dispose() {
        disposed = true
        shown = false
        Scooters.removeListener(scootersChanged)
        Demo.removeListener(demoChanged)
        sheet?.dismiss()
        sheet = null
        scope.cancel()
    }

    override fun render(now: Long) {
        renderScooters()
        refreshPermissions(force = false)
        for ((feature, row) in featureRows) row.checked = host.isEnabled(feature)
        renderHandlebar()
        renderAlerts(force = false)
        if (diagnosticsOpen) diagnostics?.render(now, force = false)
    }

    // ------------------------------------------------------------------ scooters

    private fun reloadScooters() {
        val list = runCatching { host.savedScooters }.getOrElse { emptyList() }
        activeMac = runCatching { host.activeScooter?.mac }.getOrNull()
        val macs = list.map { it.mac }
        if (macs != scooterRows.keys.toList()) {
            scooterRows.values.forEach { scooterGroup.removeView(it.item) }
            scooterRows.clear()
            list.forEachIndexed { index, scooter ->
                val row = ScooterRow(scooter)
                scooterRows[scooter.mac] = row
                scooterGroup.addView(row.item, index)
            }
        } else list.forEach { scooterRows[it.mac]?.bind(it) }
        renderScooters()
    }

    private fun renderScooters() {
        val link = host.link
        for (row in scooterRows.values) row.render(link)
    }

    private fun linked(mac: String, link: LinkStatus = host.link): Boolean =
        link.phase != LinkPhase.IDLE && link.address?.equals(mac, ignoreCase = true) == true

    private fun onScooterTap(row: ScooterRow) {
        val scooter = row.scooter
        if (linked(scooter.mac)) { openOptions(scooter); return }
        row.item.haptic(HapticFeedbackConstants.CONFIRM)
        connectTo(scooter)
    }

    /** Makes [scooter] the active one (the host lets go of another linked scooter) and connects to it. */
    private fun connectTo(scooter: SavedScooter) {
        host.selectScooter(scooter.mac)
        host.connect(scooter.mac)
        reloadScooters()
    }

    private fun addScooter() {
        addRow.haptic(HapticFeedbackConstants.CONFIRM)
        host.addScooter()
    }

    /** Opens the Change PIN sheet for saved scooter [mac] (the shell's "Scooter didn't accept this PIN" snackbar action). */
    fun changePin(mac: String) {
        val scooter = runCatching { host.savedScooters }.getOrElse { emptyList() }
            .firstOrNull { it.mac.equals(mac, ignoreCase = true) } ?: return
        showPin(scooter)
    }

    private fun openOptions(scooter: SavedScooter) {
        val group = ListGroup(ctx)
        if (linked(scooter.mac)) {
            group.addView(ListItem(ctx, "Disconnect", null, "link_off") {
                dismissSheet(); main.haptic(HapticFeedbackConstants.CONFIRM); host.disconnect()
            })
        } else {
            group.addView(ListItem(ctx, "Connect", null, "bluetooth_connected") {
                dismissSheet(); main.haptic(HapticFeedbackConstants.CONFIRM); connectTo(scooter)
            })
        }
        group.addView(ListItem(ctx, "Rename", null, "tune") { showRename(scooter) })
        group.addView(ListItem(ctx, "Change PIN", null, "key") { showPin(scooter) })
        group.addView(ListItem(ctx, "Forget", null, "delete") { showForget(scooter) }.apply { setLeadingTone(Tone.ERROR) })
        dismissSheet()
        present(group, scooter.nickname.ifBlank { PairingRules.DEFAULT_NICKNAME })
    }

    private fun showRename(scooter: SavedScooter) {
        val field = textField(scooter.nickname, "Nickname").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            filters = arrayOf(InputFilter.LengthFilter(PairingRules.NICKNAME_MAX))
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSelection(text.length)
        }
        fun commit() {
            val name = PairingRules.nickname(field.text.toString())
            host.renameScooter(scooter.mac, name)
            main.haptic(HapticFeedbackConstants.CONFIRM)
            dismissSheet()
            reloadScooters()
        }
        field.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_DONE) { commit(); true } else false }
        val content = ctx.column().apply {
            addView(field, ctx.params())
            addView(buttons(ctx.actionButton("Cancel", ButtonVariant.TEXT) { dismissSheet() }, ctx.filledButton("Save") { commit() }), ctx.params(Spacing.L))
        }
        present(content, "Rename")
        focus(field)
    }

    private fun showPin(scooter: SavedScooter) {
        // Same PIN box as onboarding: "PIN" label, filled 16 dp box with show/hide, helper line.
        val pinStyle = Typography.headlineLarge.withWeight(500)
        val box = ctx.row().apply { minimumHeight = ctx.dp(72) }
        val field = textField("", "", box).apply {
            id = View.generateViewId()
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(PairingRules.PIN_MAX))
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            applyStyle(pinStyle)
            typeface = pinStyle.typeface // Password input types reset the face to monospace.
            letterSpacing = 0.25f
            tabular()
            setPaddingRelative(ctx.dp(20), ctx.dp(Spacing.M), 0, ctx.dp(Spacing.M))
        }
        val reveal = IconActionButton(ctx, "visibility", "Show PIN", toggleable = true)
        reveal.onCheckedChange = { on ->
            field.inputType = InputType.TYPE_CLASS_NUMBER or
                if (on) InputType.TYPE_NUMBER_VARIATION_NORMAL else InputType.TYPE_NUMBER_VARIATION_PASSWORD
            field.typeface = pinStyle.typeface
            field.setSelection(field.text.length)
            reveal.setIcon("visibility", if (on) "Hide PIN" else "Show PIN")
        }
        fun commit() {
            val pin = PairingRules.sanitizePin(field.text)
            if (!PairingRules.isValidPin(pin)) { field.haptic(HapticFeedbackConstants.REJECT); return }
            host.changeScooterPin(scooter.mac, pin) // never logged; the host confirms (or reports a failure)
            field.text.clear()
            main.haptic(HapticFeedbackConstants.CONFIRM)
            dismissSheet()
        }
        val save = ctx.filledButton("Save") { commit() }.apply { isEnabled = false }
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { save.isEnabled = PairingRules.isValidPin(PairingRules.sanitizePin(s)) }
        })
        field.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_DONE) { commit(); true } else false }
        val content = ctx.column().apply {
            addView(ctx.textView("PIN", Typography.labelLarge, Theme.onSurfaceVariant).apply { labelFor = field.id }, ctx.params())
            box.addView(field, ctx.weighted())
            box.addView(reveal, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = ctx.dp(Spacing.XS) })
            addView(box, ctx.params(Spacing.S))
            addView(ctx.textView("${PairingRules.PIN_MIN} to ${PairingRules.PIN_MAX} digits · only if your scooter asks for one when pairing", Typography.bodySmall, Theme.onSurfaceVariant).apply {
                setPaddingRelative(ctx.dp(Spacing.L), 0, ctx.dp(Spacing.L), 0)
            }, ctx.params(Spacing.S))
            addView(buttons(ctx.actionButton("Cancel", ButtonVariant.TEXT) { dismissSheet() }, save), ctx.params(Spacing.L))
        }
        present(content, "Change PIN")
        focus(field)
    }

    private fun showForget(scooter: SavedScooter) {
        val content = ctx.column().apply {
            addView(ctx.textView("Its PIN and settings are removed from this phone.", Typography.bodyMedium, Theme.onSurfaceVariant).apply {
                setPadding(ctx.dp(Spacing.S), 0, ctx.dp(Spacing.S), 0)
            }, ctx.params())
            addView(buttons(
                ctx.actionButton("Cancel", ButtonVariant.TEXT) { dismissSheet() },
                ctx.actionButton("Forget", ButtonVariant.DESTRUCTIVE, "delete") { forget(scooter) }
            ), ctx.params(Spacing.L))
        }
        present(content, "Forget ${scooter.nickname.ifBlank { PairingRules.DEFAULT_NICKNAME }}?")
    }

    /**
     * The host disconnects it first when it is the linked scooter, deletes its PIN and settings and
     * offers "Unpair" (Android Bluetooth settings; removeBond isn't public API) in a snackbar.
     */
    private fun forget(scooter: SavedScooter) {
        main.haptic(HapticFeedbackConstants.CONFIRM)
        dismissSheet()
        host.forgetScooter(scooter.mac)
        reloadScooters()
    }

    // ------------------------------------------------------------------ features, permissions

    private fun renderHandlebar() {
        val last = host.lastHandlebar
        val text = last?.let { (action, at) ->
            val name = action.substringBefore(" (").trim().replaceFirstChar { it.titlecase(Locale.getDefault()) }
            "$name · ${ago(SystemClock.elapsedRealtime() - at)}"
        }
        if (text == handlebarText) return
        handlebarText = text
        featureRows[Feature.HANDLEBAR]?.setSupporting(text)
    }

    // ------------------------------------------------------------------ alerts

    private fun alertSwitch(kind: AlertKind, title: String, supporting: String?, icon: String, tip: String): SwitchListItem =
        SwitchListItem(ctx, title, supporting, icon, checked = Alerts.enabled(ctx, kind)) { on ->
            Alerts.setEnabled(ctx, kind, on)
            // "Remember where I park" off: Ruta stops remembering, so the saved spot goes too. The switch is
            // the rider's real setting also in demo mode, so the rider's spot goes then as well (the demo's
            // spot is simulated and stays): a later "on" never brings back a days-old spot.
            if (kind == AlertKind.PARKING && !on) Parking.forget(ctx)
            main.haptic(HapticFeedbackConstants.CLOCK_TICK)
            renderAlerts(force = true)
        }.apply { tooltipText = tip }

    private fun setTarget(index: Int) {
        val target = Alerts.TARGETS.getOrNull(index) ?: return
        Alerts.setChargeTarget(ctx, target)
        targetGroup.chartTick()
        renderAlerts(force = true)
    }

    /** The permission dialog when Android can still ask; otherwise the settings page that turns alerts on. */
    private fun allowAlerts() {
        accessRow.haptic(HapticFeedbackConstants.CONFIRM)
        readAlertAccess()
        if (alertAccess == AlertAccess.ASK && !notificationsBlocked) {
            host.requestPermission(AppPermission.NOTIFICATIONS)
        } else {
            runCatching { host.activity.startActivity(Alerts.settingsIntent(ctx)) }.onFailure { host.openAppSettings() }
        }
        permissionsAt = 0L // re-check on the next render (dialogs answer in the same session)
    }

    /**
     * Switches, target, "Before 80 %" and the notifications row (shown while an alert is on and can't
     * reach the shade). Android's notification state is re-read with the permissions (every 3 s and
     * on show); everything else is in-memory preferences.
     */
    private fun renderAlerts(force: Boolean) {
        if (force) readAlertAccess()
        val range = Alerts.enabled(ctx, AlertKind.RANGE)
        val charge = Alerts.enabled(ctx, AlertKind.CHARGE_TARGET)
        val stopped = Alerts.enabled(ctx, AlertKind.CHARGE_STOPPED)
        val parking = Alerts.enabled(ctx, AlertKind.PARKING)
        val messages = Alerts.enabled(ctx, AlertKind.MESSAGE_SENDER)
        val target = Alerts.chargeTarget(ctx)
        val demo = host.demoActive
        val key = listOf(range, charge, stopped, parking, messages, target, alertAccess, notificationsBlocked, demo)
        if (key == alertsKey) return
        alertsKey = key
        rangeAlert.checked = range
        chargeAlert.checked = charge
        stoppedAlert.checked = stopped
        parkingSwitch.checked = parking
        messageSwitch.checked = messages
        targetGroup.select(Alerts.TARGETS.indexOf(target).coerceAtLeast(0))
        // The target matters to both charge switches ("Notify at", "Before 80 %").
        Alerts.TARGETS.indices.forEach { targetGroup.setSegmentEnabled(it, charge || stopped) }
        stoppedAlert.setSupporting(if (target >= 100) "Before full" else "Before $target %")
        // Demo mode posts no alerts and simulates a set-up app (no "Allow" rows, as in Permissions).
        accessRow.visible(!demo && (range || charge || stopped) && alertAccess != AlertAccess.ON)
        accessButton.text = if (alertAccess == AlertAccess.ASK && !notificationsBlocked) "Allow" else "Settings"
    }

    private fun readAlertAccess() {
        alertAccess = Alerts.access(ctx)
        notificationsBlocked = host.permission(AppPermission.NOTIFICATIONS) == PermissionState.BLOCKED
    }

    private fun refreshPermissions(force: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - permissionsAt < 3_000L) return
        permissionsAt = now
        permissionRows.forEach { it.update(host.permission(it.permission)) }
        readAlertAccess()
    }

    // ------------------------------------------------------------------ data, about

    private fun exportCsv() {
        main.haptic(HapticFeedbackConstants.CONFIRM)
        host.exportCsv()
    }

    private fun hasRepository(): Boolean = runCatching { Uri.parse(SOURCE_URL).pathSegments.size >= 2 }.getOrDefault(false)

    private fun sourceLabel(): String =
        runCatching { Uri.parse(SOURCE_URL).let { (it.host.orEmpty() + it.path.orEmpty()).trimEnd('/') } }.getOrDefault("").ifEmpty { SOURCE_URL }

    private fun openSource() {
        runCatching { host.activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_URL)).addCategory(Intent.CATEGORY_BROWSABLE)) }
            .onFailure { host.showSnackbar("No browser found") }
    }

    private fun openLicences() {
        scope.launch {
            val text = withContext(Dispatchers.IO) { runCatching { readLicences() }.getOrNull() }
            if (disposed) return@launch
            if (text == null) { host.showSnackbar("Licences unavailable"); return@launch }
            dismissSheet()
            present(licencesView(text), "Licences")
        }
    }

    /** res/raw/licenses.txt (no R class: resolved by name). Runs on Dispatchers.IO. */
    private fun readLicences(): String {
        val res = ctx.resources
        val id = res.getIdentifier("licenses", "raw", ctx.packageName)
        require(id != 0) { "raw/licenses missing" }
        return res.openRawResource(id).bufferedReader().use { it.readText() }
    }

    private fun licencesView(text: String): View {
        val content = ctx.column()
        // Header blocks "Name / Copyright … / <licence> - <url>" become rows; the full text follows.
        val separator = text.indexOf("\n---")
        val head = if (separator >= 0) text.substring(0, separator) else ""
        val components = head.split(Regex("\\n\\s*\\n")).mapNotNull { block ->
            val lines = block.lines().filter { it.isNotBlank() }
            if (lines.size < 2 || lines[0].startsWith(" ")) return@mapNotNull null
            val rest = lines.drop(1).map { it.trim() }
            if (rest.none { it.startsWith("Copyright") }) return@mapNotNull null
            val licence = rest.firstOrNull { it.contains("License") }?.substringBefore(" - ")?.trim() ?: return@mapNotNull null
            lines[0].trim() to licence
        }
        if (components.isNotEmpty()) {
            val group = ListGroup(ctx)
            components.forEach { (name, licence) -> group.addView(ListItem(ctx, name, licence)) }
            content.addView(group, ctx.params())
        }
        val full = if (separator >= 0) text.substring((text.indexOf('\n', separator + 1) + 1).coerceIn(0, text.length)) else text
        content.addView(monoText(full.trim('\n'), Theme.onSurfaceVariant).apply {
            setPadding(ctx.dp(Spacing.S), 0, ctx.dp(Spacing.S), 0)
        }, ctx.params(if (components.isEmpty()) 0 else Spacing.XL))
        return content
    }

    // ------------------------------------------------------------------ diagnostics sub-page

    private fun openDiagnostics(animate: Boolean = true) {
        val page = diagnostics ?: Diagnostics().also {
            diagnostics = it
            main.addView(it.gate, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        diagnosticsOpen = true
        val v = page.gate
        v.animate().cancel()
        // The second tap of a double tap on the Diagnostics row must not start a test (W3).
        v.open(ClickGuard.WINDOW_MS)
        v.visibility = View.VISIBLE
        if (animate && Motion.enabled() && main.isAttachedToWindow) {
            v.alpha = 0f
            v.translationX = ctx.dpf(48f)
            v.animate().alpha(1f).translationX(0f).setDuration(Motion.MEDIUM2).setInterpolator(Motion.emphasizedDecelerate).start()
        } else { v.alpha = 1f; v.translationX = 0f }
        setMainHidden(true)
        page.render(System.currentTimeMillis(), force = true)
    }

    private fun closeDiagnostics() {
        diagnosticsOpen = false
        val v = diagnostics?.gate ?: return
        // Leaving: its rows take no tap while it slides out (a quick tap after back must not start a test).
        v.close()
        setMainHidden(false)
        v.animate().cancel()
        if (Motion.enabled() && v.isShown) {
            v.animate().alpha(0f).translationX(ctx.dpf(48f)).setDuration(Motion.SHORT4).setInterpolator(Motion.emphasizedAccelerate)
                .withEndAction { v.visibility = View.GONE; v.alpha = 1f; v.translationX = 0f }.start()
        } else v.visibility = View.GONE
    }

    /** Keeps TalkBack off the list underneath while the sub-page covers it. */
    private fun setMainHidden(hidden: Boolean) {
        val mode = if (hidden) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        bar.importantForAccessibility = mode
        main.page.importantForAccessibility = mode
    }

    private inner class Diagnostics {
        val bar = TopAppBar(ctx).apply { title = "Diagnostics" }
        val scaffold = Scaffold(ctx, bar).apply {
            setBackgroundColor(Theme.background)
            isClickable = true // touches never reach the list underneath
        }
        /** Closed while the page slides out, so its rows take no stray tap. */
        val gate = TouchGate(ctx).apply { addView(scaffold, FrameLayout.LayoutParams(MATCH, MATCH)) }
        private val probeRow = ListItem(ctx, "Probe data", null, "sync") { probe() }
        private val displayRow = ListItem(ctx, "Display test", null, "navigation") { displayTest() }
        private val roadRow = ListItem(ctx, "Road-name test", null, "route") { roadNameSheet() }
        // M2: moved here from the main list. Only the button acts (a stray row tap resets nothing).
        private val resetButton = ctx.actionButton("Reset", ButtonVariant.TEXT) { resetUnits() }
        private val unitsRow = ListItem(ctx, "Battery units", "Detect again", "battery_5_bar", resetButton)
        // F-DEMO: the switch follows Demo (the shell can refuse a start while the scooter is linked).
        private val demoRow = SwitchListItem(ctx, "Demo mode", DEMO_OFF, "explore", checked = host.demoActive) { on -> setDemo(on) }
        private var demoKey: DemoScenario? = null
        private var demoKeyed = false
        private val infoHeader = ctx.sectionHeader("Scooter")
        private val infoGroup = ListGroup(ctx)
        private val firmware = ListItem(ctx, "Firmware")
        private val hardware = ListItem(ctx, "Hardware")
        private val protocol = ListItem(ctx, "Protocol")
        private val rawCard = ctx.filledCard(Spacing.L)
        private val rawList = ctx.column()
        private val rawEmpty = ctx.textView("No replies yet", Typography.bodyMedium, Theme.onSurfaceVariant)
        private val logCount = ctx.singleLine("", Typography.labelLarge, Theme.onSurfaceVariant).tabular()
        private val logText = monoText("", Theme.onSurface)
        private var lastTest: ListItem? = null
        private var probeUntil = 0L
        private var refreshedAt = 0L
        private var rawKey: List<Pair<Int, Int>>? = null
        private var logKey: Pair<Int, String?>? = null
        private var infoKey: TcuInfo? = null
        private var running: Triple<Boolean, Boolean, Boolean>? = null

        init {
            bar.setNavigation { closeDiagnostics() }
            bar.addAction("content_copy", "Copy log") { host.copyLog() }
            bar.addAction("share", "Share logs") { host.shareLogs() }
            probeRow.tooltipText = "Asks the scooter for every data type once; replies appear below"
            displayRow.tooltipText = "Shows a sample route on the scooter display for 40 s"
            roadRow.tooltipText = "Measures how many characters of a road name the display shows"
            val page = scaffold.page
            page.add(ctx.sectionHeader("Tests"), 0)
            page.add(ListGroup(ctx).apply { addView(probeRow); addView(displayRow); addView(roadRow) }, 0)

            unitsRow.tooltipText = "Detect the battery's reporting units again (if charge or temperature look wrong)"
            resetButton.contentDescription = "Reset battery units"
            page.add(ctx.sectionHeader("Battery"), Spacing.SECTION - Spacing.S)
            page.add(ListGroup(ctx).apply { addView(unitsRow) }, 0)

            demoRow.tooltipText = "A simulated scooter and history for trying Chetooth; nothing reaches Bluetooth or your rides"
            page.add(ctx.sectionHeader("Demo"), Spacing.SECTION - Spacing.S)
            page.add(ListGroup(ctx).apply { addView(demoRow) }, 0)

            page.add(infoHeader, Spacing.SECTION - Spacing.S)
            infoGroup.addView(firmware); infoGroup.addView(hardware); infoGroup.addView(protocol)
            page.add(infoGroup, 0)

            page.add(ctx.sectionHeader("Raw replies"), Spacing.SECTION - Spacing.S)
            rawCard.addView(rawEmpty)
            rawCard.addView(rawList)
            page.add(rawCard, 0)

            page.add(ctx.row().apply {
                addView(ctx.sectionHeader("Log"), ctx.weighted())
                addView(logCount, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = ctx.dp(Spacing.L) })
            }, Spacing.SECTION - Spacing.S)
            val logCard = ctx.filledCard(Spacing.L)
            logCard.addView(logText)
            page.add(logCard, 0)
        }

        fun render(now: Long, force: Boolean) {
            renderTests()
            renderDemo()
            val uptime = SystemClock.uptimeMillis()
            if (!force && uptime - refreshedAt < 2_000L) return
            refreshedAt = uptime
            renderInfo()
            renderRaw()
            renderLog()
        }

        private fun ready(): Boolean {
            // Demo mode's simulated link is READY, but the shell refuses scooter tests: say so here, before
            // any success haptic or "Running" state (the same words and way out as the shell's refusal).
            if (host.demoActive) {
                scaffold.haptic(HapticFeedbackConstants.REJECT)
                host.showSnackbar("Demo mode: no real scooter", "Exit demo") { host.setDemo(null) }
                return false
            }
            if (host.linkPhase == LinkPhase.READY) return true
            scaffold.haptic(HapticFeedbackConstants.REJECT)
            host.showSnackbar("Connect the scooter first")
            return false
        }

        private fun probe() {
            if (!ready()) return
            probeRow.haptic(HapticFeedbackConstants.CONFIRM)
            host.probeScooter() // The host confirms it.
            probeUntil = SystemClock.uptimeMillis() + PROBE_MS
            renderTests()
        }

        private fun displayTest() {
            if (!ready()) return
            displayRow.haptic(HapticFeedbackConstants.CONFIRM)
            lastTest = displayRow
            host.startDisplayTest() // The host confirms it.
            renderTests()
        }

        /** The host reports the outcome ("Add a scooter first" or done). */
        private fun resetUnits() {
            resetButton.haptic(HapticFeedbackConstants.CONFIRM)
            host.resetUnits()
        }

        private fun roadNameSheet() {
            val content = ctx.column().apply {
                addView(ctx.textView(
                    "Sends road names of 8 to 12 digits, one every 4 s. The distance shows each name's length: " +
                        "note the longest one the scooter display shows in full.",
                    Typography.bodyMedium, Theme.onSurfaceVariant
                ).apply { setPadding(ctx.dp(Spacing.S), 0, ctx.dp(Spacing.S), 0) }, ctx.params())
                addView(buttons(
                    ctx.actionButton("Cancel", ButtonVariant.TEXT) { dismissSheet() },
                    ctx.filledButton("Start", "play_arrow") { dismissSheet(); roadNameTest() }
                ), ctx.params(Spacing.L))
            }
            dismissSheet()
            present(content, "Road-name test")
        }

        private fun roadNameTest() {
            if (!ready()) return
            roadRow.haptic(HapticFeedbackConstants.CONFIRM)
            lastTest = roadRow
            host.startRoadNameTest() // The host confirms it.
            renderTests()
        }

        /** On: starts the riding scenario (the Demo chip switches it); off: back to the real scooter. */
        private fun setDemo(on: Boolean) {
            demoRow.haptic(HapticFeedbackConstants.CLOCK_TICK)
            host.setDemo(if (on) DemoScenario.RIDING else null)
            demoKeyed = false // The switch moved itself: re-sync, so a refused start (scooter linked) turns it back off.
            renderDemo()
        }

        /** The switch and its line: "Riding · Tap Demo to switch" (the top-bar chip), or what it is. */
        fun renderDemo() {
            val scenario = Demo.scenario
            if (demoKeyed && scenario == demoKey) return
            demoKeyed = true
            demoKey = scenario
            demoRow.checked = scenario != null
            demoRow.setSupporting(scenario?.let { "${it.label} · Tap Demo to switch" } ?: DEMO_OFF)
        }

        private fun renderTests() {
            val testing = host.displayTestRunning
            val probing = SystemClock.uptimeMillis() < probeUntil
            val next = Triple(probing, testing && lastTest !== roadRow, testing && lastTest === roadRow)
            if (next == running) return
            running = next
            probeRow.setTrailingText(if (next.first) "Running" else null, Theme.primary)
            displayRow.setTrailingText(if (next.second) "Running" else null, Theme.primary)
            roadRow.setTrailingText(if (next.third) "Running" else null, Theme.primary)
        }

        private fun renderInfo() {
            val info = host.tcuInfo
            infoHeader.visible(info != null)
            infoGroup.visible(info != null)
            if (info == null || info == infoKey) return
            infoKey = info
            firmware.setTrailingText(info.firmware.ifBlank { "—" })
            hardware.setTrailingText(info.hardware.ifBlank { "—" })
            protocol.setTrailingText(info.bleProtocol.ifBlank { "—" })
        }

        private fun renderRaw() {
            val raw = host.rawStats()
            val key = raw.entries.map { it.key to it.value.contentHashCode() }
            if (key == rawKey) return
            rawKey = key
            rawEmpty.visible(raw.isEmpty())
            rawList.removeAllViews()
            raw.entries.forEachIndexed { index, (tag, bytes) ->
                val head = ctx.row().apply {
                    addView(ctx.singleLine("Tag $tag · ${TAG_NAMES[tag] ?: "Unknown"}", Typography.titleSmall, Theme.onSurface), ctx.weighted())
                    addView(ctx.singleLine("${bytes.size} B", Typography.labelMedium, Theme.onSurfaceVariant).tabular())
                }
                rawList.addView(head, ctx.params(if (index == 0) 0 else Spacing.M))
                rawList.addView(monoText(bytes.joinToString(" ") { String.format(Locale.US, "%02X", it) }, Theme.onSurfaceVariant), ctx.params(Spacing.XS))
            }
        }

        private fun renderLog() {
            val lines = host.logLines.ifEmpty { DiagLog.lines() }
            val key = lines.size to lines.lastOrNull()
            if (key == logKey || logText.hasSelection()) return
            logKey = key
            logCount.show(if (lines.isEmpty()) "" else "${lines.size}")
            // Newest first, so the latest events are at the top of the card.
            logText.show(if (lines.isEmpty()) "No log lines yet" else lines.takeLast(LOG_LINES).asReversed().joinToString("\n"))
        }
    }

    // ------------------------------------------------------------------ sheets and fields

    /** Shows [content] in the open sheet (swapping its content) or in a new one. */
    private fun present(content: View, title: String?) {
        val current = sheet
        if (current != null && current.isShowing) {
            current.setTitle(title)
            current.setContent(content)
        } else {
            sheet = host.showSheet(content, title)
        }
    }

    private fun dismissSheet() {
        sheet?.dismiss()
        sheet = null
    }

    private fun buttons(vararg views: View): LinearLayout = ctx.row().apply {
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        views.forEachIndexed { index, v -> addView(v, LinearLayout.LayoutParams(WRAP, WRAP).apply { if (index > 0) marginStart = ctx.dp(Spacing.S) }) }
    }

    /** M3 outlined text field: 4 dp corners, outline → 2 dp primary while focused, primary cursor. */
    /**
     * The same field as onboarding's PIN and nickname fields: filled surfaceContainerHigh, 16 dp
     * corners, a 2 dp primary outline while focused. [box] (e.g. the PIN row with its show/hide
     * button) carries the shape instead of the field itself.
     */
    private fun textField(value: String, hint: String, box: View? = null): EditText = EditText(ctx).apply {
        val shape = RoundedShapeDrawable(Theme.surfaceContainerHigh, ctx.dpf(Shapes.LARGE))
        if (box != null) { box.background = shape; background = null } else background = shape
        applyStyle(Typography.bodyLarge)
        setTextColor(Theme.onSurface)
        setHintTextColor(Theme.onSurfaceVariant)
        this.hint = hint
        setText(value)
        isSingleLine = true
        minHeight = ctx.dp(56)
        setPaddingRelative(ctx.dp(Spacing.L), ctx.dp(Spacing.M), ctx.dp(Spacing.L), ctx.dp(Spacing.M))
        textCursorDrawable = GradientDrawable().apply { setColor(Theme.primary); setSize(ctx.dp(2), ctx.dp(24)) }
        tintSelection()
        setOnFocusChangeListener { _, focused ->
            shape.strokeColor = if (focused) Theme.primary else android.graphics.Color.TRANSPARENT
            shape.strokeWidth = if (focused) ctx.dpf(2f) else 0f
        }
    }

    private fun focus(field: EditText) {
        field.requestFocus()
        field.postDelayed({
            if (field.isAttachedToWindow) ctx.getSystemService(InputMethodManager::class.java)?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        }, 250L)
    }

    private fun monoText(text: String, color: Int): TextView = ctx.textView(text, Typography.bodySmall, color).apply {
        typeface = Typeface.MONOSPACE
        setTextIsSelectable(true)
        tintSelection()
    }

    // ------------------------------------------------------------------ helpers

    private fun featureTip(feature: Feature): String = when (feature) {
        Feature.DIRECTIONS -> "Google Maps turn-by-turn on the scooter display"
        Feature.MUSIC -> "Song and artist on the scooter display"
        Feature.CALLS -> "Caller name on the scooter display"
        Feature.HANDLEBAR -> "Handlebar buttons control music and calls"
        Feature.AUTO_CONNECT -> "Connect to your scooter when Chetooth opens"
    }

    private fun permissionTip(permission: AppPermission): String = when (permission) {
        AppPermission.BLUETOOTH -> "Finds and connects to your scooter"
        AppPermission.NOTIFICATION_ACCESS -> "Reads Google Maps directions and what's playing"
        AppPermission.LOCATION -> "Records rides, counts down turn distances and remembers where you park"
        AppPermission.PHONE -> "Shows the caller's name on the scooter"
        AppPermission.ANSWER_CALLS -> "Answer and end calls from the handlebar"
        AppPermission.NOTIFICATIONS -> "Ride status, range warnings and charge alerts"
    }

    private fun ago(ms: Long): String = when {
        ms < 60_000L -> "just now"
        ms < 3_600_000L -> "${ms / 60_000L} min ago"
        else -> "${ms / 3_600_000L} h ago"
    }

    /** Sections slide up with a 20 ms stagger the first time the tab opens. */
    private fun enterStagger() {
        if (!Motion.enabled()) return
        val column = main.page.column
        val offset = ctx.dpf(12f)
        for (i in 0 until column.childCount) {
            val child = column.getChildAt(i)
            if (child.visibility != View.VISIBLE) continue
            child.alpha = 0f
            child.translationY = offset
            child.animate().alpha(1f).translationY(0f).setStartDelay(i * 20L).setDuration(Motion.MEDIUM2)
                .setInterpolator(Motion.emphasizedDecelerate)
                .withEndAction { child.animate().startDelay = 0L } // the animator is shared per view
                .start()
        }
    }

    private fun View.haptic(kind: Int) { performHapticFeedback(kind) }

    // ------------------------------------------------------------------ rows

    private inner class ScooterRow(var scooter: SavedScooter) {
        private val check = IconView(ctx, "check_circle", filled = true, tint = Theme.primary, description = "Active")
        private val options = IconActionButton(ctx, "more_horiz", "Scooter options") { openOptions(scooter) }
        private val trailing = ctx.row().apply {
            addView(check, LinearLayout.LayoutParams(ctx.dp(24), ctx.dp(24)).apply { marginEnd = ctx.dp(Spacing.XS) })
            addView(options)
        }
        val item = ListItem(ctx, "", null, "electric_moped", trailing)
        private var state: String? = null

        init {
            item.setOnClick { onScooterTap(this) }
            bind(scooter)
        }

        fun bind(next: SavedScooter) {
            if (next != scooter) state = null
            scooter = next
            item.setHeadline(next.nickname.ifBlank { PairingRules.DEFAULT_NICKNAME })
            options.setIcon("more_horiz", "Options for ${next.nickname.ifBlank { PairingRules.DEFAULT_NICKNAME }}")
        }

        fun render(link: LinkStatus) {
            val active = activeMac?.equals(scooter.mac, ignoreCase = true) == true
            val mine = link.address?.equals(scooter.mac, ignoreCase = true) == true
            val phase = if (mine) link.phase else LinkPhase.IDLE
            val key = "$active/$phase/${scooter.bleName}"
            if (key == state) return
            state = key
            val name = scooter.bleName.ifBlank { "Scooter" } // a word, never the MAC address
            when (phase) {
                LinkPhase.READY -> item.setSupporting("$name · Connected", Theme.primary)
                LinkPhase.CONNECTING -> item.setSupporting("$name · Connecting")
                LinkPhase.IDLE -> item.setSupporting(name)
            }
            item.setIcon("electric_moped", filled = active)
            item.setLeadingTone(if (active) Tone.PRIMARY else Tone.SECONDARY)
            check.visible(active)
            item.tooltipText = if (active) "Active scooter" else "Tap to connect"
        }
    }

    /**
     * About › app row (B2): the Ruta mark (same badge as the launcher icon) where list rows carry their
     * 40 dp icon circle, "Ruta" and the version. Static: one accessibility node, nothing to tap.
     */
    private inner class AboutRow : LinearLayout(ctx) {
        private val version = ctx.singleLine("", Typography.labelLarge, Theme.onSurfaceVariant).tabular()

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ctx.dp(72)
            setPaddingRelative(ctx.dp(Spacing.L), ctx.dp(Spacing.M), ctx.dp(Spacing.L), ctx.dp(Spacing.M))
            addView(BrandMark(ctx, 40), LayoutParams(ctx.dp(40), ctx.dp(40)).apply { marginEnd = ctx.dp(Spacing.L) })
            addView(ctx.singleLine("Ruta", Typography.bodyLarge, Theme.onSurface), LayoutParams(0, WRAP, 1f))
            addView(version, LayoutParams(WRAP, WRAP).apply { marginStart = ctx.dp(Spacing.L) })
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            for (i in 0 until childCount) getChildAt(i).importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            setVersion(FALLBACK_VERSION)
        }

        fun setVersion(name: String) {
            version.show(name)
            contentDescription = "Chetooth, version $name. For Bajaj Chetak scooters"
        }
    }

    /**
     * "Charge alert" and its "Notify at" 80 / 90 / 100 % group in one grouped row: the switch row on
     * top (its own ripple, top corners), the group under its text column.
     */
    private inner class ChargeAlertBlock : LinearLayout(ctx), GroupedRow {
        private val shape = RoundedShapeDrawable()

        init {
            orientation = VERTICAL
            background = shape
            // Tighter bottom: "Notify at" sits 8 dp above the group it names.
            chargeAlert.setPaddingRelative(ctx.dp(Spacing.L), ctx.dp(Spacing.M), ctx.dp(Spacing.L), ctx.dp(Spacing.XS))
            chargeAlert.minimumHeight = ctx.dp(56)
            addView(chargeAlert, LayoutParams(MATCH, WRAP))
            val line = ctx.row().apply {
                // 16 + 40 + 16 dp: under the headline, like the supporting text.
                setPaddingRelative(ctx.dp(72), 0, ctx.dp(Spacing.L), ctx.dp(Spacing.S))
                addView(targetGroup, ctx.weighted())
            }
            addView(line, LayoutParams(MATCH, WRAP))
        }

        override fun setGroupShape(color: Int, topRadius: Float, bottomRadius: Float) {
            shape.color = color
            shape.setRadii(topRadius, topRadius, bottomRadius, bottomRadius)
            chargeAlert.setGroupShape(color, topRadius, 0f)
        }
    }

    private inner class PermissionRow(val permission: AppPermission) {
        private val button = ctx.tonalButton("Allow") { request() }
        private val ok = IconView(ctx, "check_circle", filled = true, tint = Theme.primary, description = "Allowed")
        // M1: every permission wears the standard tonal circle; only the state text turns error-coloured.
        val item = ListItem(ctx, permission.label, null, permission.icon).apply { tooltipText = permissionTip(permission) }
        private var state: PermissionState? = null

        private fun request() {
            item.haptic(HapticFeedbackConstants.CONFIRM)
            host.requestPermission(permission)
            permissionsAt = 0L // re-check on the next render (dialogs answer in the same session)
        }

        fun update(next: PermissionState) {
            if (next == state) return
            state = next
            item.visible(next != PermissionState.NOT_NEEDED)
            if (next == PermissionState.GRANTED) {
                item.setTrailing(ok)
                item.setSupporting(null)
                item.setOnClick(null)
            } else {
                button.text = if (next == PermissionState.BLOCKED) "Settings" else "Allow"
                item.setTrailing(button)
                item.setSupporting(when (next) {
                    PermissionState.PARTIAL -> if (permission == AppPermission.LOCATION) "Approximate" else "Partly"
                    PermissionState.BLOCKED -> "Blocked"
                    else -> "Needed"
                }, Theme.error)
                item.setOnClick { request() }
            }
        }
    }
}
