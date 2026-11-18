package dev.codesurgen.ruta

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.Drawable
import android.media.session.PlaybackState
import android.net.Uri
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Home ("Dashboard"): top app bar with the scooter's nickname + BLE name and the connection chip
 * (tap = scooter sheet); the [InstrumentCluster] hero; a centred chip row under it with a fixed
 * height (range while moving, one warning chip when ride recording is blocked), so nothing below
 * jumps at every stop; the live ride / charging card; the parked spot while the scooter is not
 * connected (F-PARK: "Riverside Rd" / "Parked · 12 min ago" + a tonal Walk button that opens
 * walking directions in Maps); a container-less 2×2 metric grid (battery
 * health, battery temperature, distance today, efficiency — "Full in" while charging); a quiet row
 * of vehicle-state icons; now playing (only while music plays or is paused); the last ride. A call
 * banner sits on top during calls. Disconnected: last-known values dimmed and an extended FAB
 * "Connect" (hidden while connecting).
 *
 * "Tap explains, never surprise-jumps" (W2): every value here — cluster, F/R labels, metrics,
 * range chip, state icons, live card, parked row — opens an explain sheet ([explain]); only the
 * sheet's History / Rides button, the last-ride chevron row, the connection chip's sheet, the
 * parked spot's Walk buttons (Maps) and the FAB lead anywhere else. Everything comes from [Host]
 * and [Parking]; render(now) only updates views (no I/O, no view creation); day totals and 24 h
 * trends load off the main thread.
 */
class HomeScreen(private val host: Host) : Screen {
    private val ctx = host.context
    private val scope = Ui.scope()
    private val bar = TopAppBar(ctx)
    private val chip = ConnectionChip(ctx) { if (needsChetakSetup()) host.openChetakSetup() else openScooterSheet() }
    private val scaffold = Scaffold(ctx, bar)
    override val view: View = scaffold
    private val page = scaffold.page

    // Call banner
    private val callCard = ctx.filledCard(Spacing.L)
    private val callName = ctx.singleLine("", Typography.titleLarge, Theme.onPrimaryContainer)
    private val callDetail = ctx.singleLine("", Typography.bodyMedium, Theme.onPrimaryContainer).tabular()
    private val callOnScooter = IconView(ctx, "electric_moped", filled = true, tint = Theme.onPrimaryContainer, iconSizeDp = Icons.SMALL, description = "On scooter display")
    private val callEnd = ctx.actionButton("Decline", ButtonVariant.DESTRUCTIVE, "call_end") { endCall() }
    private val callAnswer = ctx.actionButton("Answer", ButtonVariant.FILLED, "call") { answerCall() }

    // Hero
    private val cluster = InstrumentCluster(ctx)
    private val heroChips = ctx.row().apply { gravity = Gravity.CENTER }
    // Range has its own glyph ("route" means distance ridden, shown in the live card at the same time).
    private val rangeChip = StatChip(ctx, "battery_5_bar", "—", "km", description = "Range")
    private val warningChip = ctx.statusChip("Rides off", "warning", Tone.WARNING)
    private val warningTarget = FrameLayout(ctx)
    private var warningKind = Warning.NONE

    // Live ride / charging
    private val liveCard = ctx.filledCard(Spacing.L)
    private val liveIcon = IconView(ctx, "two_wheeler", filled = true, tint = Theme.primary)
    private val liveTitle = ctx.singleLine("Riding", Typography.titleMedium, Theme.onSurface)
    private val liveGps = IconView(ctx, "location_on", tint = Theme.warning, iconSizeDp = Icons.SMALL, description = "Searching for GPS")
    private val liveA = Stat("route")
    private val liveB = Stat("timer")
    private val liveC = Stat("speed")

    // Parked spot (F-PARK): only while the scooter is not connected (you're at it when it is). The
    // row explains itself (sheet with the mini-map); Walk names where it goes (Maps, walking).
    /** The spot the row shows; null while hidden. */
    private var parked: ParkedSpot? = null
    /** The rider's saved spot has been read off the main thread (until then render() shows no parked row). */
    private var parkingLoaded = false
    private val parkedGroup = ListGroup(ctx)
    private val parkedWalk: ActionButton = ctx.tonalButton("Walk") { parked?.let { walkTo(it, parkedWalk) } }
    private val parkedItem = ListItem(ctx, "Parked", null, "pin_drop", parkedWalk) { explainParked() }
    private var parkedShown: Boolean? = null
    private var parkedText: String? = null
    /** [ParkingRules.street] of [parkedPlace], worked out when the place changes (not every render). */
    private var parkedPlace: String? = null
    private var parkedStreet: String? = null
    private val onParking: () -> Unit = { host.requestRender() }

    // Metrics (W1): typographic 2×2 grid, each cell explains itself on tap.
    private val metrics = SpecGrid(ctx, columns = 2)
    private val health = metrics.add("ecg_heart", "Battery health")
    private val temperature = metrics.add("device_thermostat", "Battery temp", "Battery temperature")
    private val today = metrics.add("route", "Today", "Ridden today")
    private val fourth = metrics.add("bolt", "Efficiency")
    /** The fourth cell shows "Full in" (true) or Efficiency (false). */
    private var fourthIsEta: Boolean? = null

    // Vehicle state: quiet icons, no words; each explains itself on tap.
    private val stateRow = ctx.row().apply { gravity = Gravity.CENTER }
    private val ignitionIcon = stateIcon("power_settings_new") { explainIgnition() }
    private val lockIcon = stateIcon("lock") { explainLock() }
    private val immobilisedIcon = stateIcon("shield", filled = true, tint = Theme.warning) { explainImmobilised() }
    private val limitView = ctx.row()
    private val limitValue = ctx.singleLine("", Typography.labelLarge, Theme.onSurfaceVariant).tabular()

    // Now playing
    private val musicCard = ctx.filledCard(Spacing.L)
    private val musicTitle = ctx.singleLine("", Typography.titleMedium, Theme.onSurface)
    private val musicArtist = ctx.singleLine("", Typography.bodyMedium, Theme.onSurfaceVariant)
    private val musicOnScooter = IconView(ctx, "electric_moped", filled = true, tint = Theme.primary, iconSizeDp = Icons.SMALL, description = "On scooter display")
    private val musicProgress = WavyLinearProgress(ctx)
    private val musicControls = ButtonGroup(
        ctx, listOf(
            GroupItem(icon = "skip_previous", description = "Previous track"),
            GroupItem(icon = "play_arrow", description = "Play"),
            GroupItem(icon = "skip_next", description = "Next track")
        ), selectable = false, heightDp = 48
    ) { index -> onMedia(index) }
    private var playing: Boolean? = null

    // Last ride (a chevron row: an explicit way to the ride)
    private val lastRideGroup = ListGroup(ctx)
    private val lastRideItem = ListItem(ctx, "", null, "route", IconView(ctx, "chevron_right")) { host.lastRide?.let { host.openRide(it.id) } }

    // Primary action
    private val fab = ExtendedFab(ctx, "electric_moped", "Connect") { onFab() }

    // Sheets
    private var sheet: BottomSheet? = null
    private var sheetAction: ActionButton? = null
    private var sheetRefresh: ActionButton? = null
    private var explainSheet: BottomSheet? = null
    private var explainJob: Job? = null

    // Day totals (off the main thread): finished rides since local midnight, learned efficiency.
    private var dayJob: Job? = null
    private var dayStart = 0L
    private var loadedRevision = Int.MIN_VALUE
    private var pendingKey: Pair<Long, Int>? = null
    private var todayFinishedM = 0.0
    /** Today's distance as last shown on the tile ([todayMeters]). */
    private var todayShownM = 0.0
    private var todayRides = 0
    private var efficiency: RangeEfficiency? = null

    private var lastPhase: LinkPhase? = null
    /** The link went through an automatic reconnect since it was last READY: no ignition sweep then. */
    private var sawReconnect = false
    private var fabShown = true
    private var fabAdds: Boolean? = null
    private var rangeShown = false
    /** The chip row under the cluster is laid out (riding, or a chip to show); null before the first render. */
    private var chipsShown: Boolean? = null
    private var lastRideText: String? = null

    private enum class Warning { NONE, LOCATION, GPS_OFF }

    init {
        // The chip's pill ends on the 16 dp margin like the title starts on it (bar end padding 8 + 8).
        bar.trailing.addView(chip, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = ctx.dp(Spacing.S) })
        bar.title = host.scooterName
        buildCall()
        buildHero()
        buildLive()
        buildParked()
        buildMetrics()
        buildState()
        buildMusic()
        lastRideGroup.addView(lastRideItem)
        page.add(lastRideGroup)
        scaffold.fab = fab
    }

    // ------------------------------------------------------------------ build

    private fun buildCall() {
        callCard.setContainerColor(Theme.primaryContainer)
        callCard.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        val head = ctx.row()
        val circle = FrameLayout(ctx).apply {
            background = RoundedShapeDrawable(Theme.onPrimaryContainer, ctx.dpf(Shapes.FULL))
            addView(IconView(ctx, "phone_in_talk", filled = true, tint = Theme.primaryContainer), FrameLayout.LayoutParams(ctx.dp(24), ctx.dp(24), Gravity.CENTER))
        }
        head.addView(circle, LinearLayout.LayoutParams(ctx.dp(40), ctx.dp(40)))
        val texts = ctx.column()
        texts.addView(callName)
        texts.addView(callDetail, ctx.params(2))
        head.addView(texts, ctx.weighted(start = Spacing.L))
        head.addView(callOnScooter, LinearLayout.LayoutParams(ctx.dp(Icons.SMALL), ctx.dp(Icons.SMALL)).apply { marginStart = ctx.dp(Spacing.S) })
        callCard.addView(head)
        val actions = ctx.row()
        actions.addView(callEnd, ctx.weighted())
        actions.addView(callAnswer, ctx.weighted(start = Spacing.M))
        callCard.addView(actions, ctx.params(Spacing.M))
        callCard.visibility = View.GONE
        page.add(callCard, Spacing.S)
    }

    private fun buildHero() {
        cluster.onPartClick = { part ->
            cluster.haptic(HapticFeedbackConstants.CLOCK_TICK)
            when (part) {
                ClusterPart.BATTERY -> explainBattery()
                ClusterPart.FRONT -> explainPack(1)
                ClusterPart.REAR -> explainPack(2)
            }
        }
        page.add(cluster, Spacing.S)
        // While riding the row keeps a fixed 48 dp, so the range chip comes and goes at every stop without
        // moving the content below; at rest it collapses unless a chip shows (renderHero).
        heroChips.minimumHeight = ctx.dp(Spacing.TOUCH)
        rangeChip.visibility = View.GONE
        rangeChip.foreground = rippleOver(null, RoundedShapeDrawable(Color.WHITE, ctx.dpf(Shapes.CHIP)), Theme.onSurface)
        rangeChip.isClickable = true
        rangeChip.isFocusable = true
        rangeChip.setOnSingleClick { explainBattery() }
        heroChips.addView(rangeChip, LinearLayout.LayoutParams(WRAP, WRAP))
        // A status chip is 32 dp; the frame makes it a 48 dp touch target with the chip's ripple.
        val inset = ctx.dp((Spacing.TOUCH - 32) / 2)
        warningTarget.minimumHeight = ctx.dp(Spacing.TOUCH)
        warningTarget.addView(warningChip, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        warningTarget.foreground = rippleOver(null, RoundedShapeDrawable(Color.WHITE, ctx.dpf(Shapes.CHIP)).apply { setInsets(0, inset, 0, inset) }, Theme.onWarningContainer)
        warningTarget.isClickable = true
        warningTarget.isFocusable = true
        warningTarget.setOnSingleClick { onWarning() }
        warningTarget.visibility = View.GONE
        heroChips.addView(warningTarget, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = ctx.dp(Spacing.S) })
        page.add(heroChips, Spacing.XS)
    }

    private fun buildLive() {
        val head = ctx.row()
        head.addView(liveIcon, LinearLayout.LayoutParams(ctx.dp(24), ctx.dp(24)))
        head.addView(liveTitle, ctx.weighted(start = Spacing.S))
        // No tooltips inside the card: a view with a tooltip takes the tap from the clickable card.
        head.addView(liveGps, LinearLayout.LayoutParams(ctx.dp(Spacing.TOUCH), ctx.dp(24)))
        liveCard.addView(head)
        val stats = ctx.row().apply { gravity = Gravity.TOP }
        stats.addView(liveA.view, ctx.weighted())
        stats.addView(liveB.view, ctx.weighted(start = Spacing.M))
        stats.addView(liveC.view, ctx.weighted(start = Spacing.M))
        liveCard.addView(stats, ctx.params(Spacing.L))
        liveCard.onClick { explainLive() }
        liveCard.visibility = View.GONE
        page.add(liveCard)
    }

    private fun buildParked() {
        parkedWalk.contentDescription = "Walking directions to your scooter"
        parkedGroup.addView(parkedItem)
        parkedGroup.visibility = View.GONE
        page.add(parkedGroup)
        Parking.addListener(onParking)
        // The first read of the saved spot happens off the main thread; render() then reads memory.
        val app = ctx.applicationContext
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { Parking.stored(app) } }
            parkingLoaded = true
            host.requestRender()
        }
    }

    private fun buildMetrics() {
        health.onClick { explainHealth() }
        temperature.onClick { explainTemperature() }
        today.onClick { explainToday() }
        fourth.onClick { if (fourthIsEta == true) explainFullIn() else explainEfficiency() }
        page.column.addView(metrics, metrics.alignedParams(Spacing.S))
    }

    private fun buildState() {
        for (icon in listOf(ignitionIcon, lockIcon, immobilisedIcon)) stateRow.addView(icon, LinearLayout.LayoutParams(ctx.dp(Spacing.TOUCH), ctx.dp(Spacing.TOUCH)))
        limitView.addView(IconView(ctx, "speed", tint = Theme.onSurfaceVariant, iconSizeDp = Icons.SMALL), LinearLayout.LayoutParams(ctx.dp(Icons.SMALL), ctx.dp(Icons.SMALL)))
        limitView.addView(limitValue, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = ctx.dp(6) })
        limitView.minimumHeight = ctx.dp(Spacing.TOUCH)
        limitView.setPadding(ctx.dp(12), 0, ctx.dp(12), 0)
        limitView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        for (i in 0 until limitView.childCount) limitView.getChildAt(i).importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        tappable(limitView, Shapes.FULL) { explainSpeedLimit() }
        stateRow.addView(limitView, LinearLayout.LayoutParams(WRAP, WRAP))
        immobilisedIcon.visibility = View.GONE
        limitView.visibility = View.GONE
        stateRow.visibility = View.GONE
        page.add(stateRow, Spacing.XS)
    }

    /** 48 dp touch target with a 20 dp glyph; tap explains, long-press names it. */
    private fun stateIcon(name: String, filled: Boolean = false, tint: Int = Theme.onSurfaceVariant, onTap: () -> Unit) =
        IconView(ctx, name, filled = filled, tint = tint, iconSizeDp = Icons.SMALL).also { tappable(it, Shapes.FULL, onTap) }

    private fun tappable(target: View, radius: Float, onTap: () -> Unit) {
        val inset = ctx.dp(4)
        target.background = rippleOver(null, RoundedShapeDrawable(Color.WHITE, ctx.dpf(radius)).apply { setInsets(inset, inset, inset, inset) }, Theme.onSurface)
        target.isClickable = true
        target.isFocusable = true
        target.setOnSingleClick { target.haptic(HapticFeedbackConstants.CLOCK_TICK); onTap() }
    }

    private fun buildMusic() {
        val head = ctx.row()
        val circle = FrameLayout(ctx).apply {
            background = RoundedShapeDrawable(Theme.secondaryContainer, ctx.dpf(Shapes.FULL))
            addView(IconView(ctx, "music_note", tint = Theme.onSecondaryContainer), FrameLayout.LayoutParams(ctx.dp(24), ctx.dp(24), Gravity.CENTER))
        }
        head.addView(circle, LinearLayout.LayoutParams(ctx.dp(48), ctx.dp(48)))
        val texts = ctx.column()
        texts.addView(musicTitle)
        texts.addView(musicArtist)
        head.addView(texts, ctx.weighted(start = Spacing.L))
        head.addView(musicOnScooter, LinearLayout.LayoutParams(ctx.dp(Spacing.TOUCH), ctx.dp(Icons.SMALL)))
        musicOnScooter.tooltip("On scooter display")
        musicCard.addView(head)
        musicProgress.color = Theme.primary
        musicCard.addView(musicProgress, ctx.params(Spacing.M))
        musicCard.addView(musicControls, ctx.params(Spacing.M))
        musicControls.setEmphasized(1)
        musicCard.visibility = View.GONE
        page.add(musicCard, Spacing.SECTION)
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onShow() { loadDay(force = true) }

    override fun dispose() {
        Parking.removeListener(onParking)
        scope.cancel()
        sheet?.dismiss()
        explainSheet?.dismiss()
        sheet = null; sheetAction = null; sheetRefresh = null; explainSheet = null
    }

    /** The cluster's ignition sweep (e.g. when onboarding lands on Home). */
    fun ignitionSweep() = cluster.sweep()

    // ------------------------------------------------------------------ render

    override fun render(now: Long) {
        val link = host.link
        val battery = host.battery
        val ride = host.ride
        if (ride.revision != loadedRevision || midnight(now) != dayStart) loadDay()
        renderBar(link)
        renderCall()
        renderHero(link, battery, ride)
        renderLive(ride)
        renderParked(link, ride, now)
        renderMetrics(link, battery, ride)
        renderState(link)
        renderMusic()
        renderLastRide(ride, now)
        renderFab(link)
        renderSheet()
        // Ignition sweep (A5) only on a first or rider-started connect: never after an automatic
        // reconnect (e.g. a Bluetooth drop mid-ride), never while riding or moving.
        if (link.phase != LinkPhase.READY && link.reconnecting) sawReconnect = true
        if (link.phase == LinkPhase.READY && lastPhase != null && lastPhase != LinkPhase.READY &&
            !sawReconnect && !ride.riding && !cluster.moving) cluster.sweep()
        if (link.phase == LinkPhase.READY || (link.phase == LinkPhase.IDLE && !link.reconnecting)) sawReconnect = false
        lastPhase = link.phase
    }

    private fun renderBar(link: LinkStatus) {
        bar.title = host.scooterName
        val ble = link.deviceName ?: host.activeScooter?.bleName
        if (bar.subtitle?.toString() != ble) bar.subtitle = ble
        val idle = link.phase == LinkPhase.IDLE
        val label = when {
            link.phase == LinkPhase.CONNECTING -> if (link.reconnecting) "Reconnecting" else "Connecting"
            link.phase == LinkPhase.READY && link.live -> "Live"
            // Chetooth: a Chetak that has not been taught its battery field yet: the chip opens the setup.
            needsChetakSetup() -> "Set up battery"
            link.phase == LinkPhase.READY && link.silentSince > 0 -> "No data"
            link.phase == LinkPhase.READY -> "Connected"
            idle && link.error == BLUETOOTH_OFF -> "Bluetooth off"
            else -> "Offline"
        }
        // H1: offline is a normal state (neutral chip); red only for a real fault the rider must fix.
        val fault = idle && link.error != null && link.error !in CALM_ERRORS
        chip.update(link.phase, label, attention = (link.phase == LinkPhase.READY && link.silentSince > 0) || fault)
        chip.tooltipText = link.error?.takeIf { idle } ?: "Scooter: $label"
    }

    /** Chetooth: linked to a Chetak whose battery field has not been learned yet (nothing to show until it is). */
    private fun needsChetakSetup(): Boolean =
        !host.demoActive && host.linkPhase == LinkPhase.READY && ChetakData.linked && ChetakData.decoder(ctx, LearnField.SOC) == null

    private fun renderCall() {
        val call = host.callState
        val show = call.phase != CallPhase.IDLE
        callCard.visible(show)
        if (!show) return
        val uptime = SystemClock.elapsedRealtime()
        callName.show(call.display ?: "Unknown caller")
        callDetail.show(
            when {
                call.phase == CallPhase.RINGING -> "Incoming call"
                call.phase == CallPhase.ENDED -> if (call.connectedAt > 0) "Ended · ${Fmt.timer(call.durationS(uptime).toLong())}" else "Missed"
                call.outgoing && call.connectedAt == 0L -> "Calling"
                else -> Fmt.timer(call.durationS(uptime).toLong())
            }
        )
        callOnScooter.visible(host.callFeed == ClusterFeed.SHOWING)
        callEnd.visible(call.inCall)
        callEnd.text = if (call.phase == CallPhase.RINGING) "Decline" else "End"
        callAnswer.visible(call.phase == CallPhase.RINGING)
    }

    private fun renderHero(link: LinkStatus, battery: BatterySnapshot, ride: RideState) {
        val range = host.range
        cluster.update(
            speedKmh = host.speedKmh,
            front = battery.pack1.soc,
            rear = battery.pack2.soc,
            total = totalCharge(battery),
            range = range,
            state = host.driveState,
            stale = !battery.fresh,
            riding = ride.riding
        )
        if (range != null) {
            rangeChip.setValue(range.km.roundToInt().toString(), "km")
            rangeChip.setNote(if (range.estimated) "est." else null, "estimated")
        }
        val wantRange = cluster.moving && range != null
        if (wantRange != rangeShown) { rangeShown = wantRange; Motion.fade(rangeChip, wantRange) }

        // One compact chip when ride recording is blocked (no prose on Home).
        val linked = link.phase != LinkPhase.IDLE
        val location = host.permission(AppPermission.LOCATION)
        warningKind = when {
            location != PermissionState.GRANTED || ride.gps == GpsStatus.NO_PERMISSION -> Warning.LOCATION
            linked && ride.gps == GpsStatus.DISABLED -> Warning.GPS_OFF
            else -> Warning.NONE
        }
        when (warningKind) {
            Warning.LOCATION -> { warningChip.setText("Rides off"); warningChip.setIcon("location_on") }
            Warning.GPS_OFF -> { warningChip.setText("GPS off"); warningChip.setIcon("location_on") }
            Warning.NONE -> {}
        }
        warningTarget.contentDescription = when (warningKind) {
            Warning.LOCATION -> "Rides are not recorded: location is off for Chetooth"
            Warning.GPS_OFF -> "Rides are not recorded: phone location is off"
            Warning.NONE -> null
        }
        warningTarget.tooltipText = warningTarget.contentDescription
        warningTarget.visible(warningKind != Warning.NONE)

        val reserve = ride.riding || cluster.moving
        heroChips.minimumHeight = if (reserve) ctx.dp(Spacing.TOUCH) else 0
        val chips = reserve || wantRange || warningKind != Warning.NONE
        if (chips != chipsShown) {
            val first = chipsShown == null
            chipsShown = chips
            if (first) heroChips.visible(chips) else Motion.fade(heroChips, chips)
        }
    }

    private fun renderLive(ride: RideState) {
        val charging = ride.charging
        val show = ride.riding || charging != null
        liveCard.visible(show)
        if (!show) return
        if (ride.riding) {
            liveIcon.setIcon("two_wheeler", filled = true); liveIcon.tint = Theme.primary
            liveTitle.show("Riding")
            liveGps.visible(ride.gps != GpsStatus.FIX)
            val distance = Amounts.distance(ride.distanceM)
            liveA.set("route", distance.value, distance.unit)
            // A running m:ss timer up to an hour; then "1:02" + "h" so it fits a third of the card at large font sizes.
            if (ride.durationS < 3600) liveB.set("timer", Fmt.timer(ride.durationS), null)
            else Amounts.duration(ride.durationS).let { liveB.set("timer", it.value, it.unit) }
            liveC.set("speed", ride.peakKmh.roundToInt().toString(), "km/h")
            liveCard.contentDescription = "Riding: ${distance.text}, ${Fmt.duration(ride.durationS)}, top speed ${ride.peakKmh.roundToInt()} km/h. Details"
        } else if (charging != null) {
            liveIcon.setIcon("ev_station", filled = true); liveIcon.tint = Theme.charging
            liveTitle.show("Charging")
            liveGps.visible(false)
            // Current charge only ("80" %); where it started is in the spoken text and the sheet.
            liveA.set("battery_charging_full", charging.socEnd.roundToInt().toString(), "%")
            Amounts.duration(charging.durationS).let { liveB.set("timer", it.value, it.unit) }
            if (charging.durationS >= RATE_AFTER_S) liveC.set("trending_up", charging.percentPerHour.roundToInt().toString(), "%/h")
            else liveC.set("trending_up", Amounts.NONE, null)
            liveCard.contentDescription = "Charging from ${charging.socStart.roundToInt()} to ${charging.socEnd.roundToInt()} percent, ${Fmt.duration(charging.durationS)}. Details"
        }
    }

    /**
     * The parked spot while the scooter is not connected (connecting counts as not connected: at
     * launch, away from the scooter, auto-connect may try for a while) and not riding, with More ›
     * Alerts "Remember where I park" on. The street is the headline, like the last ride's places.
     */
    private fun renderParked(link: LinkStatus, ride: RideState, now: Long) {
        // render() does no I/O: the rider's spot shows once buildParked's off-main read is done (the demo's is in memory).
        if (!parkingLoaded && !host.demoActive) return
        // The demo simulates a set-up app: its parked spot shows whatever the rider's switch says.
        val spot = Parking.spot(ctx)?.takeIf {
            link.phase != LinkPhase.READY && !ride.riding && (host.demoActive || Alerts.enabled(ctx, AlertKind.PARKING))
        }
        parked = spot
        val show = spot != null
        if (show != parkedShown) {
            val first = parkedShown == null
            parkedShown = show
            if (first) parkedGroup.visible(show) else Motion.fade(parkedGroup, show)
        }
        if (spot == null) return
        if (spot.place != parkedPlace) { parkedPlace = spot.place; parkedStreet = ParkingRules.street(spot.place) }
        val street = parkedStreet
        val ago = parkedAgo(spot.time, now)
        val headline = street ?: "Parked"
        // Alone (no street yet, or offline) the time is the line: sentence case, no leading "at".
        val supporting = if (street != null) "Parked · $ago" else ago.removePrefix("at ").replaceFirstChar { it.uppercaseChar() }
        val text = "$headline\n$supporting"
        if (text == parkedText) return
        parkedText = text
        parkedItem.setHeadline(headline)
        parkedItem.setSupporting(supporting)
        parkedItem.contentDescription = "Parked" + (spot.place?.let { " at $it" } ?: "") + ", $ago. Details"
    }

    /** "just now", "12 min ago", "at 18:42", "yesterday, 23:10", "Mon 16 Nov, 18:42". */
    private fun parkedAgo(time: Long, now: Long): String {
        val elapsed = now - time
        return when {
            elapsed < 60_000 -> "just now"
            elapsed < 3_600_000 -> "${elapsed / 60_000} min ago"
            time >= midnight(now) -> "at ${Fmt.clock(ctx, time)}"
            time >= midnight(midnight(now) - 1) -> "yesterday, ${Fmt.clock(ctx, time)}"
            else -> "${Fmt.date(time)}, ${Fmt.clock(ctx, time)}"
        }
    }

    /** "today", "yesterday" or the date of [time] (a note next to a clock time). */
    private fun dayOf(time: Long, now: Long): String = when {
        time >= midnight(now) -> "today"
        time >= midnight(midnight(now) - 1) -> "yesterday"
        else -> Fmt.date(time)
    }

    private fun renderMetrics(link: LinkStatus, battery: BatterySnapshot, ride: RideState) {
        // Battery cells: skeletons while connecting before the first BMS frame; "—" without any data.
        val loading = !battery.hasData && link.phase != LinkPhase.IDLE
        val stale = !battery.fresh
        health.loading = loading
        temperature.loading = loading
        val soh = healthOf(battery)
        health.setValue(soh?.roundToInt()?.toString() ?: Amounts.NONE, soh?.let { "%" })
        health.setTone(if (soh != null && soh < HEALTH_LOW) Tone.WARNING else Tone.NEUTRAL)
        val temp = temperatureOf(battery)
        temperature.setValue(temp?.let { Fmt.decimal(it) } ?: Amounts.NONE, temp?.let { "°C" })
        temperature.setTone(when { temp == null -> Tone.NEUTRAL; temp >= TEMP_HOT -> Tone.ERROR; temp >= TEMP_WARM -> Tone.WARNING; else -> Tone.NEUTRAL })
        health.stale = stale && soh != null
        temperature.stale = stale && temp != null

        // Today: finished rides since midnight + the ride being recorded.
        val day = todayMeters(ride)
        todayShownM = day
        val distance = if (day < 50) Amount("0", "km") else Amounts.distance(day)
        today.setValue(distance.value, distance.unit)

        // Fourth: "Full in" while charging with a known rate, else the efficiency the range uses.
        val eta = etaMinutes(battery, ride)
        val showEta = eta != null
        if (showEta != fourthIsEta) {
            fourthIsEta = showEta
            if (showEta) { fourth.setIcon("ev_station"); fourth.setLabel("Full in") } else { fourth.setIcon("bolt"); fourth.setLabel("Efficiency") }
        }
        if (eta != null) {
            val left = if (eta < 60) Amount(eta.toString(), "min") else Amounts.duration(eta * 60L)
            fourth.setValue(left.value, left.unit)
            fourth.setNote(null)
        } else {
            fourth.setValue(Fmt.decimal(kmPerPercent(), 2), "km/%")
            fourth.setNote(if (learned()) null else "est.", "estimated")
        }
    }

    private fun renderState(link: LinkStatus) {
        val state = host.vehicle?.takeIf { link.phase == LinkPhase.READY }
        stateRow.visible(state != null)
        if (state == null) return
        ignitionIcon.setIcon("power_settings_new", filled = state.ignitionOn)
        ignitionIcon.tint = if (state.ignitionOn) Theme.onSurface else Theme.onSurfaceVariant
        val ignition = if (state.ignitionOn) "Ignition on" else "Ignition off"
        ignitionIcon.setDescription(ignition); ignitionIcon.tooltipText = ignition
        lockIcon.setIcon(if (state.handleLocked) "lock" else "lock_open")
        val lock = if (state.handleLocked) "Handle locked" else "Handle unlocked"
        lockIcon.setDescription(lock); lockIcon.tooltipText = lock
        immobilisedIcon.visible(state.immobilised)
        if (state.immobilised) { immobilisedIcon.setDescription("Immobilised"); immobilisedIcon.tooltipText = "Immobilised" }
        limitView.visible(state.speedLimit > 0)
        if (state.speedLimit > 0) {
            limitValue.show("${state.speedLimit} km/h")
            val spoken = "Speed limit ${state.speedLimit} km/h"
            if (limitView.contentDescription != spoken) { limitView.contentDescription = spoken; limitView.tooltipText = spoken }
        }
    }

    private fun renderMusic() {
        // H2: only while music plays or is paused (no "Not playing" control card).
        val media = host.mediaState
        val active = media != null && (media.playing || media.playbackState == PlaybackState.STATE_PAUSED)
        musicCard.visible(active)
        if (media == null || !active) return
        musicTitle.show(media.title.ifBlank { media.appLabel.ifBlank { "Playing" } })
        val artist = media.artist.ifBlank { media.appLabel }
        musicArtist.show(artist)
        musicArtist.visible(artist.isNotBlank())
        val hasProgress = media.durationMs > 0
        musicProgress.visible(hasProgress)
        if (hasProgress) {
            musicProgress.wavy = media.playing
            musicProgress.setProgress(media.progressPercent() / 100f)
        }
        val isPlaying = media.playing
        if (isPlaying != playing) {
            playing = isPlaying
            musicControls.setItem(1, icon = if (isPlaying) "pause" else "play_arrow", description = if (isPlaying) "Pause" else "Play")
        }
        musicOnScooter.visible(host.musicFeed == ClusterFeed.SHOWING)
    }

    private fun renderLastRide(ride: RideState, now: Long) {
        val last = host.lastRide
        val show = last != null && !ride.riding
        lastRideGroup.visible(show)
        if (last == null || !show) return
        // Same title as Rides (never raw coordinates).
        lastRideItem.setHeadline(RideTitle.of(last))
        val facts = "${Fmt.distance(last.distanceM)} · ${Fmt.duration(last.durationS)} · ${Fmt.ago(ctx, last.end, now)}"
        if (facts != lastRideText) { lastRideText = facts; lastRideItem.setSupporting(facts) }
    }

    private fun renderFab(link: LinkStatus) {
        val noScooter = host.activeScooter == null
        // H4: only when idle; connecting / reconnecting is already shown by the chip.
        val show = link.phase == LinkPhase.IDLE && !link.reconnecting
        if (noScooter != fabAdds) {
            fabAdds = noScooter
            if (noScooter) { fab.setText("Add scooter"); fab.setIcon("add") } else { fab.setText("Connect"); fab.setIcon("electric_moped") }
        }
        if (show != fabShown) { fabShown = show; Motion.fade(fab, show) }
    }

    // ------------------------------------------------------------------ day totals (off the main thread)

    private fun loadDay(force: Boolean = false) {
        val now = System.currentTimeMillis()
        val start = midnight(now)
        val revision = host.ride.revision
        val key = start to revision
        if (!force && dayJob?.isActive == true && pendingKey == key) return
        pendingKey = key
        dayJob?.cancel()
        val store = host.store
        dayJob = scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching { Triple(store.distanceSince(start), store.rides(MAX_DAY_RIDES, start).size, store.efficiency()) }.getOrNull()
            }
            if (loaded != null) { todayFinishedM = loaded.first; todayRides = loaded.second; efficiency = loaded.third }
            dayStart = start
            loadedRevision = revision
            host.requestRender()
        }
    }

    private fun midnight(now: Long): Long = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /**
     * Finished rides since midnight + the ride being recorded. When a ride ends, its live distance
     * leaves before the reload adds it to the finished total: while that reload is pending the tile
     * holds the value it showed, so it never rolls down and back up.
     */
    private fun todayMeters(ride: RideState): Double {
        val live = todayFinishedM + if (ride.riding) ride.distanceM else 0.0
        return if (ride.revision != loadedRevision && dayStart == midnight(System.currentTimeMillis())) max(todayShownM, live) else live
    }

    /** The km per 1 % the range estimate uses (same source, same clamp). */
    private fun kmPerPercent(): Double = host.range?.kmPerPercent ?: RangeModel.clampKmPerPercent(efficiency?.kmPerPercent ?: RangeModel.DEFAULT_KM_PER_PERCENT)

    private fun learned(): Boolean = host.range?.learned ?: (efficiency?.learned == true)

    private fun etaMinutes(battery: BatterySnapshot, ride: RideState): Int? {
        val charging = ride.charging ?: return null
        val rate = charging.percentPerHour.takeIf { charging.durationS >= RATE_AFTER_S }
        return ChargeEta.minutesToFull(totalCharge(battery) ?: charging.socEnd, rate)
    }

    // ------------------------------------------------------------------ explain sheets (W2)

    /** Loads a 24 h trend off the main thread, then shows the sheet built with it (one at a time). */
    private fun explainWith(trend: ((TelemetryStore, Long) -> List<Double>)?, build: (List<Double>) -> ExplainSheet) {
        explainJob?.cancel()
        val store = host.store
        explainJob = scope.launch {
            val since = System.currentTimeMillis() - DAY_MS
            val points = if (trend == null) emptyList() else withContext(Dispatchers.IO) { runCatching { trend(store, since) }.getOrDefault(emptyList()) }
            explainSheet = host.showExplain(build(points))
        }
    }

    /** Pack 0 (scooter) series, or the packs combined ([merge]) when the scooter total is missing. */
    private fun series(metric: Metric, merge: (List<Double>) -> Double): (TelemetryStore, Long) -> List<Double> = { store, since ->
        val total = store.history(metric, 0, since, TREND_BUCKETS)
        if (total.isNotEmpty()) total.map { it.avg }
        else {
            val byTime = (store.history(metric, 1, since, TREND_BUCKETS) + store.history(metric, 2, since, TREND_BUCKETS)).groupBy { it.time }
            byTime.keys.sorted().map { time -> merge(byTime.getValue(time).map { it.avg }) }
        }
    }

    private fun packSeries(metric: Metric, pack: Int): (TelemetryStore, Long) -> List<Double> =
        { store, since -> store.history(metric, pack, since, TREND_BUCKETS).map { it.avg } }

    private fun toInsights(): () -> Unit = { host.selectTab(Destination.INSIGHTS) }
    private fun toRides(): () -> Unit = { host.selectTab(Destination.RIDES) }
    private fun lastKnown(battery: BatterySnapshot): String? = if (!battery.fresh && battery.hasData) "last known" else null
    private fun pct(value: Double?): String = value?.let { "${Fmt.decimal(it)} %" } ?: Amounts.NONE
    private fun deg(value: Double?): String = value?.let { "${Fmt.decimal(it)} °C" } ?: Amounts.NONE

    private fun explainBattery() {
        explainWith(series(Metric.SOC) { it.average() }) { trend ->
            val battery = host.battery
            val total = totalCharge(battery)
            val range = host.range
            val rows = ArrayList<Pair<String, String>>()
            rows += "Front" to pct(battery.pack1.soc)
            rows += "Rear" to pct(battery.pack2.soc)
            rows += "Energy" to pct(battery.total.energy ?: average(battery.pack1.energy, battery.pack2.energy))
            battery.imbalance?.let { rows += "Balance" to "${Fmt.decimal(it)} %" }
            range?.let { rows += "Range" to "${it.km.roundToInt()} km" + if (it.estimated) " est." else "" }
            host.driveState.takeIf { it != DriveState.UNKNOWN }?.let { rows += "State" to it.label }
            ExplainSheet(
                icon = "battery_5_bar", title = "Battery",
                value = total?.roundToInt()?.toString() ?: Amounts.NONE, unit = total?.let { "%" }, note = lastKnown(battery),
                meaning = "Charge of the two packs together. The range comes from this charge.",
                rows = rows, spark = trend, onHistory = toInsights(),
                rowNotes = mapOf(
                    "Energy" to "Usable energy the scooter reports; usually a little below charge",
                    "Balance" to "Gap between front and rear charge",
                    "Range" to if (range?.estimated != false) "Charge × ${Fmt.decimal(kmPerPercent(), 2)} km per 1 %" else "As the scooter reports it",
                    "State" to "As the battery packs report it"
                )
            )
        }
    }

    private fun explainPack(pack: Int) {
        val name = if (pack == 1) "Front pack" else "Rear pack"
        explainWith(packSeries(Metric.SOC, pack)) { trend ->
            val battery = host.battery
            val values = if (pack == 1) battery.pack1 else battery.pack2
            ExplainSheet(
                icon = "battery_5_bar", title = name,
                value = values.soc?.roundToInt()?.toString() ?: Amounts.NONE, unit = values.soc?.let { "%" }, note = lastKnown(battery),
                meaning = "One of the scooter's two battery packs. They charge and drain together.",
                rows = listOf("Health" to pct(values.health), "Temperature" to deg(values.temperatureC), "Energy" to pct(values.energy)),
                spark = trend, onHistory = toInsights(),
                rowNotes = mapOf("Energy" to "Usable energy the pack reports; usually a little below charge")
            )
        }
    }

    private fun explainHealth() {
        explainWith(series(Metric.HEALTH) { it.average() }) { trend ->
            val battery = host.battery
            val soh = healthOf(battery)
            ExplainSheet(
                icon = "ecg_heart", title = "Battery health",
                value = soh?.roundToInt()?.toString() ?: Amounts.NONE, unit = soh?.let { "%" }, note = lastKnown(battery),
                meaning = "Capacity the packs still hold compared with new. It drops slowly over the years.",
                rows = listOf("Front" to pct(battery.pack1.health), "Rear" to pct(battery.pack2.health)),
                spark = trend, onHistory = toInsights()
            )
        }
    }

    private fun explainTemperature() {
        explainWith(series(Metric.TEMPERATURE) { it.max() }) { trend ->
            val battery = host.battery
            val temp = temperatureOf(battery)
            ExplainSheet(
                icon = "device_thermostat", title = "Battery temp",
                value = temp?.let { Fmt.decimal(it) } ?: Amounts.NONE, unit = temp?.let { "°C" }, note = lastKnown(battery),
                meaning = "The warmest pack right now. Very hot or very cold packs give less power.",
                rows = listOf("Front" to deg(battery.pack1.temperatureC), "Rear" to deg(battery.pack2.temperatureC)),
                spark = trend, onHistory = toInsights()
            )
        }
    }

    private fun explainToday() {
        explainWith(null) {
            val ride = host.ride
            val day = Amounts.distance(todayMeters(ride)).let { if (todayMeters(ride) < 50) Amount("0", "km") else it }
            val rows = ArrayList<Pair<String, String>>()
            rows += "Rides" to todayRides.toString()
            if (ride.riding) rows += "This ride" to Amounts.distance(ride.distanceM).text
            ExplainSheet(
                icon = "route", title = "Today",
                value = day.value, unit = day.unit,
                meaning = "Distance ridden since midnight, from your recorded rides.",
                rows = rows, historyLabel = "Rides", onHistory = toRides(),
                rowNotes = if (ride.riding) mapOf("This ride" to "Still recording") else emptyMap()
            )
        }
    }

    private fun explainEfficiency() {
        explainWith(null) {
            val battery = host.battery
            val range = host.range
            val learnedFrom = efficiency?.rides ?: 0
            val rows = ArrayList<Pair<String, String>>()
            range?.let { rows += "Range" to "${it.km.roundToInt()} km" + if (it.estimated) " est." else "" }
            totalCharge(battery)?.let { rows += "Charge" to pct(it) }
            if (learnedFrom > 0) rows += "Learned from" to if (learnedFrom == 1) "1 ride" else "$learnedFrom rides"
            ExplainSheet(
                icon = "bolt", title = "Efficiency",
                value = Fmt.decimal(kmPerPercent(), 2), unit = "km/%", note = if (learned()) null else "est.",
                meaning = if (learned()) "Kilometres per 1 % of charge, learned from your rides. Range = charge × efficiency."
                else "Kilometres per 1 % of charge. A typical value until you ride 3 km or more in one go.",
                rows = rows, onHistory = toInsights()
            )
        }
    }

    private fun explainFullIn() {
        explainWith(series(Metric.SOC) { it.average() }) { trend ->
            val battery = host.battery
            val ride = host.ride
            val charging = ride.charging
            val eta = etaMinutes(battery, ride)
            val left = eta?.let { if (it < 60) Amount(it.toString(), "min") else Amounts.duration(it * 60L) }
            val rows = ArrayList<Pair<String, String>>()
            totalCharge(battery)?.let { rows += "Charge" to pct(it) }
            if (charging != null) {
                if (charging.durationS >= RATE_AFTER_S) rows += "Rate" to "${charging.percentPerHour.roundToInt()} %/h"
                rows += "Started" to "${Fmt.clock(ctx, charging.start)} · ${charging.socStart.roundToInt()} %"
            }
            ExplainSheet(
                icon = "ev_station", title = "Full in",
                value = left?.value ?: Amounts.NONE, unit = left?.unit,
                meaning = "Time to 100 % at the current charge rate. Charging slows near full, so it can take a little longer.",
                rows = rows, spark = trend, onHistory = toInsights()
            )
        }
    }

    private fun explainLive() {
        val ride = host.ride
        if (ride.riding) {
            explainWith(null) {
                val now = host.ride
                val distance = Amounts.distance(now.distanceM)
                val rows = ArrayList<Pair<String, String>>()
                rows += "Time" to Fmt.duration(now.durationS)
                rows += "Top speed" to Fmt.speed(now.peakKmh)
                if (now.avgKmh > 0) rows += "Average" to Fmt.speed(now.avgKmh)
                val used = now.socStart?.let { start -> (now.socPercent ?: totalCharge(host.battery))?.let { start - it } }
                if (used != null && used >= 0) rows += "Battery used" to "${Fmt.decimal(used)} %"
                now.startPlace?.takeIf { it.isNotBlank() }?.let { rows += "From" to it }
                ExplainSheet(
                    icon = "two_wheeler", title = "Riding",
                    value = distance.value, unit = distance.unit,
                    meaning = if (now.startedAt > 0) "Recording since ${Fmt.clock(ctx, now.startedAt)}. Saved when you stop." else "Recording this ride. Saved when you stop.",
                    rows = rows, historyLabel = "Rides", onHistory = toRides()
                )
            }
        } else if (ride.charging != null) {
            explainWith(series(Metric.SOC) { it.average() }) { trend ->
                val charging = host.ride.charging
                val eta = etaMinutes(host.battery, host.ride)
                val rows = ArrayList<Pair<String, String>>()
                if (charging != null) {
                    rows += "Started at" to "${charging.socStart.roundToInt()} %"
                    rows += "Added" to "${Fmt.decimal(charging.gained)} %"
                    if (charging.durationS >= RATE_AFTER_S) rows += "Rate" to "${charging.percentPerHour.roundToInt()} %/h"
                }
                eta?.let { rows += "Full in" to if (it < 60) "$it min" else Amounts.duration(it * 60L).text }
                ExplainSheet(
                    icon = "ev_station", title = "Charging",
                    value = charging?.socEnd?.roundToInt()?.toString() ?: Amounts.NONE, unit = "%",
                    meaning = charging?.let { "Charging since ${Fmt.clock(ctx, it.start)}." } ?: "Charging.",
                    rows = rows, spark = trend, onHistory = toInsights()
                )
            }
        }
    }

    private fun explainIgnition() {
        val on = host.vehicle?.ignitionOn ?: return
        host.explain(ExplainSheet(icon = "power_settings_new", title = "Ignition", value = if (on) "On" else "Off",
            meaning = "Whether the scooter is switched on, as it reports it."))
    }

    private fun explainLock() {
        val locked = host.vehicle?.handleLocked ?: return
        host.explain(ExplainSheet(icon = if (locked) "lock" else "lock_open", title = "Handle lock", value = if (locked) "Locked" else "Unlocked",
            meaning = "The steering lock, as the scooter reports it."))
    }

    private fun explainImmobilised() {
        host.explain(ExplainSheet(icon = "shield", title = "Immobilised", value = "On",
            meaning = "The scooter reports that it is blocked from riding."))
    }

    private fun explainSpeedLimit() {
        val limit = host.vehicle?.speedLimit?.takeIf { it > 0 } ?: return
        host.explain(ExplainSheet(icon = "speed", title = "Speed limit", value = limit.toString(), unit = "km/h",
            meaning = "The top speed the scooter is set to right now."))
    }

    /**
     * The parked spot: when (clock time), where (mini-map + place name, the sheet's detail slot), how
     * long and how precise, with Share and Walk (its actions slot). The standard "tap explains"
     * sheet: one at a time, same layout and motion as every other.
     */
    private fun explainParked() {
        val spot = parked ?: return
        parkedItem.haptic(HapticFeedbackConstants.CLOCK_TICK)
        val now = System.currentTimeMillis()
        explainJob?.cancel()
        explainSheet = host.showExplain(ExplainSheet(
            icon = "pin_drop", title = "Parked",
            value = Fmt.clock(ctx, spot.time), note = dayOf(spot.time, now),
            meaning = "Where your phone was when you left the scooter. Chetooth saves it when a ride ends or the scooter disconnects.",
            rows = listOf("Parked for" to ParkingRules.elapsed(now - spot.time), "Accuracy" to ParkingRules.accuracyText(spot.accuracyM)),
            rowNotes = mapOf("Accuracy" to "How precise the phone's location was: the circle on the map"),
            detail = parkedDetail(spot),
            actions = parkedActions(spot)
        ))
    }

    /** The mini-map and, under it, the place (or the coordinates while it is being looked up). */
    private fun parkedDetail(spot: ParkedSpot): View = ctx.column().apply {
        addView(ParkedMap(ctx).apply { setSpot(spot) }, ctx.params(0, height = ctx.dp(PARKED_MAP_DP)))
        addView(ctx.textView(spot.place ?: RideMath.latLngText(spot.lat, spot.lng), Typography.titleMedium, Theme.onSurface), ctx.params(Spacing.M))
    }

    /** Share (outlined) and Walk (filled), side by side across the sheet. */
    private fun parkedActions(spot: ParkedSpot): View = ctx.row().apply {
        // "Share" fits beside "Walk" on narrow screens and large fonts; TalkBack hears the full action.
        addView(ctx.outlinedButton("Share", "share") { shareSpot(spot) }.apply { contentDescription = "Share location" }, ctx.weighted())
        lateinit var walk: ActionButton
        walk = ctx.filledButton("Walk", "directions") { explainSheet?.dismiss(); walkTo(spot, walk) }
        walk.contentDescription = "Walking directions to your scooter"
        addView(walk, ctx.weighted(start = Spacing.S))
    }

    /** Walking directions to [spot] in Google Maps (any browser when Maps is missing). */
    private fun walkTo(spot: ParkedSpot, from: View) {
        from.haptic(HapticFeedbackConstants.CONFIRM)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(Parking.walkUrl(spot)))
        try { host.activity.startActivity(Intent(intent).setPackage(MAPS_PACKAGE)) }
        catch (_: ActivityNotFoundException) {
            try { host.activity.startActivity(intent) }
            catch (_: ActivityNotFoundException) { host.showSnackbar("Google Maps isn't installed") }
        }
    }

    /** The place and a Maps link, through Android's share sheet (the rider picks who gets it). */
    private fun shareSpot(spot: ParkedSpot) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Scooter location")
            .putExtra(Intent.EXTRA_TEXT, ParkingRules.shareText(spot))
        try { host.activity.startActivity(Intent.createChooser(send, "Share location")) }
        catch (_: ActivityNotFoundException) { host.showSnackbar("Couldn't share the location") }
    }

    // ------------------------------------------------------------------ actions

    private fun onFab() {
        fab.haptic(HapticFeedbackConstants.CONFIRM)
        if (host.activeScooter == null) host.addScooter() else host.connect()
    }

    private fun onWarning() {
        when (warningKind) {
            Warning.LOCATION -> {
                val state = host.permission(AppPermission.LOCATION)
                val row = ListItem(ctx, "Location", null, "location_on",
                    ctx.tonalButton(if (state == PermissionState.BLOCKED) "Settings" else "Allow") {
                        sheet?.dismiss(); host.requestPermission(AppPermission.LOCATION)
                    })
                row.setSupporting("Needed to record rides", Theme.error)
                showWarningSheet(row)
            }
            // Explains first; only the button that names it leaves Ruta for system settings (W2/W3).
            Warning.GPS_OFF -> {
                val row = ListItem(ctx, "Location off", null, "location_on",
                    ctx.tonalButton("Turn on") { sheet?.dismiss(); host.openLocationSettings() })
                row.setSupporting("Needed to record rides", Theme.error)
                showWarningSheet(row)
            }
            Warning.NONE -> {}
        }
    }

    private fun showWarningSheet(row: View) {
        val group = ListGroup(ctx).apply { addView(row) }
        sheet?.dismiss()
        sheet = host.showSheet(group, "Rides")?.also { s -> s.onDismiss = { if (sheet === s) sheet = null } }
    }

    private fun onMedia(index: Int) {
        musicControls.haptic(HapticFeedbackConstants.KEYBOARD_TAP)
        host.media(when (index) { 0 -> MediaAction.PREVIOUS; 2 -> MediaAction.NEXT; else -> MediaAction.PLAY_PAUSE })
        host.requestRender()
    }

    private fun answerCall() {
        callAnswer.haptic(HapticFeedbackConstants.CONFIRM)
        host.call(CallAction.ACCEPT)
    }

    private fun endCall() {
        callEnd.haptic(HapticFeedbackConstants.REJECT)
        host.call(CallAction.REJECT)
    }

    // ------------------------------------------------------------------ scooter sheet

    private fun openScooterSheet() {
        chip.haptic(HapticFeedbackConstants.CLOCK_TICK)
        sheet?.dismiss()
        val content = ctx.column()
        val saved = host.savedScooters
        val active = host.activeScooter?.mac
        if (saved.isNotEmpty()) {
            val group = ListGroup(ctx)
            for (scooter in saved) {
                val isActive = scooter.mac.equals(active, ignoreCase = true)
                val check = if (isActive) IconView(ctx, "check_circle", filled = true, tint = Theme.primary, description = "Active") else null
                group.addView(ListItem(ctx, scooter.nickname.ifBlank { "My scooter" }, scooter.bleName, "electric_moped", check) {
                    sheet?.dismiss()
                    if (!isActive) host.selectScooter(scooter.mac)
                    if (host.linkPhase == LinkPhase.IDLE && !host.reconnecting) host.connect(scooter.mac)
                })
            }
            content.addView(group, ctx.params())
        }
        val action = ctx.actionButton("Connect", ButtonVariant.FILLED, "electric_moped", ButtonSize.MEDIUM) { onSheetAction() }
        content.addView(action, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ctx.dp(Spacing.L) })
        // Connected but data stalled ("No data"): ask again without dropping the link (or the ride).
        val refresh = ctx.actionButton("Refresh", ButtonVariant.TONAL, "refresh") { sheet?.dismiss(); host.refreshLink() }
        content.addView(refresh, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ctx.dp(Spacing.S) })
        sheetRefresh = refresh
        val add = ctx.actionButton("Add scooter", ButtonVariant.TEXT, "add") { sheet?.dismiss(); host.addScooter() }
        content.addView(add, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = ctx.dp(Spacing.XS); gravity = Gravity.CENTER_HORIZONTAL })
        sheetAction = action
        renderSheet()
        sheet = host.showSheet(content, "Scooters")?.also { s ->
            s.onDismiss = { if (sheet === s) { sheet = null; sheetAction = null; sheetRefresh = null } }
        }
    }

    private fun renderSheet() {
        val action = sheetAction ?: return
        when (host.linkPhase) {
            LinkPhase.IDLE -> { action.text = "Connect"; action.variant = ButtonVariant.FILLED; action.setIcon("electric_moped") }
            LinkPhase.CONNECTING -> { action.text = "Cancel"; action.variant = ButtonVariant.TONAL; action.setIcon("close") }
            LinkPhase.READY -> { action.text = "Disconnect"; action.variant = ButtonVariant.OUTLINED; action.setIcon("link_off") }
        }
        action.isEnabled = host.activeScooter != null
        sheetRefresh?.visible(host.linkPhase == LinkPhase.READY)
    }

    private fun onSheetAction() {
        val phase = host.linkPhase
        sheetAction?.haptic(if (phase == LinkPhase.IDLE) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.REJECT)
        host.toggleConnection()
        if (phase != LinkPhase.CONNECTING) sheet?.dismiss() else renderSheet()
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Icon + big tabular value + unit; the card's spoken text names it. No tooltip: the card is the
     * touch target (a child with a tooltip would take the tap).
     */
    private inner class Stat(icon: String) {
        private val iconView = IconView(ctx, icon, tint = Theme.onSurfaceVariant, iconSizeDp = Icons.SMALL)
        private val value = ctx.singleLine(Amounts.NONE, Typography.headlineSmall.withWeight(500), Theme.onSurface).tabular()
        private val unit = ctx.singleLine("", Typography.bodyMedium, Theme.onSurfaceVariant)
        val view: LinearLayout = ctx.column().apply {
            addView(iconView, LinearLayout.LayoutParams(ctx.dp(Icons.SMALL), ctx.dp(Icons.SMALL)))
            val line = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                isBaselineAligned = true
                addView(value, LinearLayout.LayoutParams(WRAP, WRAP))
                addView(unit, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = ctx.dp(Spacing.XXS) })
            }
            addView(line, ctx.params(Spacing.XS))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }

        fun set(icon: String, number: String, unitText: String?) {
            iconView.setIcon(icon)
            value.roll(number)
            unit.show(unitText.orEmpty())
            unit.visible(!unitText.isNullOrEmpty())
        }
    }

    private fun totalCharge(battery: BatterySnapshot): Double? = battery.total.soc ?: average(battery.pack1.soc, battery.pack2.soc)
    private fun healthOf(battery: BatterySnapshot): Double? = battery.total.health ?: average(battery.pack1.health, battery.pack2.health)
    private fun temperatureOf(battery: BatterySnapshot): Double? =
        battery.total.temperatureC ?: listOfNotNull(battery.pack1.temperatureC, battery.pack2.temperatureC).maxOrNull()

    private fun average(a: Double?, b: Double?): Double? = when {
        a != null && b != null -> (a + b) / 2
        else -> a ?: b
    }

    private companion object {
        const val DAY_MS = 86_400_000L
        /** 24 h in 30-minute buckets for explain-sheet trends. */
        const val TREND_BUCKETS = 48
        const val MAX_DAY_RIDES = 200
        /** The live charge rate means something after 10 minutes. */
        const val RATE_AFTER_S = 600L
        const val TEMP_WARM = 45.0
        const val TEMP_HOT = 55.0
        const val HEALTH_LOW = 70.0
        const val PARKED_MAP_DP = 160
        const val MAPS_PACKAGE = "com.google.android.apps.maps"
        const val BLUETOOTH_OFF = "Bluetooth is off"
        /** Link errors that are normal life (scooter away, Bluetooth off): the chip stays neutral. */
        val CALM_ERRORS = setOf("Couldn't reach the scooter", "Lost the scooter", BLUETOOTH_OFF, "Pairing cancelled", "Scooter not nearby")
    }
}

/** Haptic tick for primary actions, toggles and segmented changes (DESIGN A4). */
private fun View.haptic(kind: Int) { performHapticFeedback(kind) }

/**
 * Static mini-map of the parked spot (no tiles, no network): "map paper" dots on a 20 m grid
 * centred on the spot, the accuracy circle at true scale (60 m from the centre to the top edge, so
 * a 50 m circle fits), a location_on pin whose tip marks the spot, and a scale bar one grid step
 * long. North is up. Decorative for TalkBack (the sheet's rows say the accuracy and the place).
 * Prepared in onSizeChanged / setSpot; onDraw allocates nothing.
 */
private class ParkedMap(context: Context) : View(context) {
    private var accuracyM = ParkingRules.MAX_ACCURACY_M
    private var dots = FloatArray(0)
    private var cx = 0f
    private var cy = 0f
    private var radius = 0f
    private var cell = 0f
    private val outline = Path()
    private val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.surfaceContainerHigh }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = alpha(Theme.outlineVariant, 0.7f); strokeWidth = dpf(2f); strokeCap = Paint.Cap.ROUND
    }
    private val circleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = alpha(Theme.primary, 0.14f) }
    private val circleEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dpf(1.5f); color = alpha(Theme.primary, 0.6f)
    }
    private val point = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scale = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dpf(1.5f); color = Theme.onSurfaceVariant; strokeCap = Paint.Cap.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).applyStyle(context, Typography.labelSmall).tabular().apply {
        color = Theme.onSurfaceVariant; textAlign = Paint.Align.LEFT
    }
    private val pin: Drawable = Icons.drawable(context, "location_on", filled = true, tint = Theme.primary)
    private val scaleText = "${ParkingRules.MAP_GRID_M} m"

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun setSpot(spot: ParkedSpot) {
        accuracyM = spot.accuracyM
        layoutMap()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), resolveSize(dp(160), heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutMap()
    }

    private fun layoutMap() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        cx = w / 2f
        cy = h / 2f
        val pxPerM = cy / ParkingRules.MAP_HALF_SPAN_M.toFloat()
        cell = ParkingRules.MAP_GRID_M * pxPerM
        val accuracy = if (accuracyM.isFinite() && accuracyM > 0) accuracyM.toFloat() else ParkingRules.MAX_ACCURACY_M.toFloat()
        radius = (accuracy * pxPerM).coerceIn(dpf(6f), cy - dpf(4f))
        // Grid crossings, aligned to the spot (the spot itself is marked, not dotted).
        val cols = (cx / cell).toInt()
        val rows = (cy / cell).toInt()
        val list = ArrayList<Float>()
        for (i in -cols..cols) for (j in -rows..rows) {
            if (i == 0 && j == 0) continue
            list += cx + i * cell; list += cy + j * cell
        }
        dots = list.toFloatArray()
        val corner = dpf(Shapes.LARGE)
        outline.reset()
        outline.addRoundRect(0f, 0f, w, h, corner, corner, Path.Direction.CW)
        // location_on's tip sits at 88.9 % of its height (Material Symbols 960 grid: y = 853).
        val size = dp(36)
        val left = (cx - size / 2f).roundToInt()
        val top = (cy - size * PIN_TIP).roundToInt()
        pin.setBounds(left, top, left + size, top + size)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        canvas.drawPath(outline, panel)
        canvas.save()
        canvas.clipPath(outline)
        canvas.drawPoints(dots, dot)
        canvas.drawCircle(cx, cy, radius, circleFill)
        canvas.drawCircle(cx, cy, radius, circleEdge)
        // Scale bar: one grid step, bottom start (as on the ride map).
        val left = dpf(16f)
        val base = height - dpf(16f)
        val tick = dpf(4f)
        canvas.drawLine(left, base, left + cell, base, scale)
        canvas.drawLine(left, base - tick, left, base, scale)
        canvas.drawLine(left + cell, base - tick, left + cell, base, scale)
        canvas.drawText(scaleText, left, base - tick - dpf(6f), text)
        // The exact point under the pin's tip, ringed so it reads on the circle.
        point.color = Theme.surfaceContainerHigh
        canvas.drawCircle(cx, cy, dpf(4.5f), point)
        point.color = Theme.primary
        canvas.drawCircle(cx, cy, dpf(3f), point)
        pin.draw(canvas)
        canvas.restore()
    }

    private companion object { const val PIN_TIP = 853f / 960f }
}
