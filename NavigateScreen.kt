package dev.codesurgen.ruta

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.text.DateFormatSymbols
import java.util.Locale

/*
 * Navigate tab (DESIGN › Navigate, POLISH N1/T1). Hero: a preview of exactly what the scooter
 * display is sent (NavBridge.frame(): turn glyph, distance as encoded, road as abbreviated for the
 * TFT, arrival clock, time left, km left) with a status dot that says "On scooter" only when the TCU
 * acknowledged a recent frame. While Maps isn't navigating the same card shows a muted ghost of that
 * layout under "Start in Maps". Tapping the hero explains it (Host.explain); the extended FAB "Maps"
 * is the one action. Setup rows appear only when something is missing; "Scooter display setup"
 * (the TFT's Navigation widget, DisplaySetup) shows until the rider marks it done, then lives in the
 * top bar as an info button. No text inputs and no paragraphs. Main thread only; no I/O here except
 * the already-loaded "companion" preferences.
 *
 * Everything shown comes from Host state (navPreview, range, battery, link), so the demo drives this
 * screen exactly like Maps and the scooter do; NavBridge is read only for what the preview lacks
 * (which Maps values are known, the counted-down route left, the trip's range hysteresis).
 *
 * Range check (2.2 F-RANGE): under the preview — not inside it, the scooter display doesn't show it —
 * a compact chip compares the range with the route left in Maps: "12 km spare", "Tight · 3 km spare"
 * (tertiary), "Short by 4 km" (error). Only with a live charge reading. Tap explains (how it's worked
 * out, plus "Find chargers", which searches Google Maps). The notification is NavBridge's job.
 */
class NavigateScreen(private val host: Host) : Screen {
    private val ctx = host.context
    private val bar = TopAppBar(ctx, large = true).apply { title = "Navigate" }
    private val scaffold = Scaffold(ctx, bar)
    override val view: View = scaffold

    /** What the status dot claims about the scooter display (labels ≤ 2 words; [spoken] is the long form). */
    private enum class Mirror(val label: String, val tone: Tone, val spoken: String) {
        LIVE("On scooter", Tone.PRIMARY, "Showing on the scooter display"),
        SENDING("Sending", Tone.WARNING, "Waiting for the scooter display to confirm"),
        REJECTED("Not shown", Tone.ERROR, "The scooter display didn't accept directions"),
        CONNECTING("Connecting", Tone.SECONDARY, "Connecting to the scooter"),
        OFFLINE("Offline", Tone.NEUTRAL, "Scooter not connected; preview only"),
        OFF("Display off", Tone.NEUTRAL, "Directions on the scooter display are turned off"),
        UNSUPPORTED("Not supported", Tone.NEUTRAL, "This scooter doesn't take directions"),
        TEST("Test", Tone.TERTIARY, "Display test running")
    }

    // ------------------------------------------------------------------ hero (TFT preview)

    private val hero = ctx.filledCard(20)
    private val dotShape = RoundedShapeDrawable(Theme.outline, ctx.dpf(Shapes.FULL))
    private val dot = View(ctx).apply { background = dotShape }
    private val statusLabel = ctx.singleLine("", Typography.labelLarge, Theme.onSurfaceVariant)
    private val statusRow = ctx.row()

    private val turnTile = FrameLayout(ctx)
    private val tileShape = RoundedShapeDrawable(Theme.surfaceContainerHigh, ctx.dpf(Shapes.TILE))
    private val turnIcon = IconView(ctx, "navigation", filled = true, tint = Theme.onSurface, iconSizeDp = 56)
    private val distance = ctx.singleLine("", Typography.displayMedium.withWeight(500), Theme.onSurface).tabular()
    private val distanceUnit = ctx.singleLine("", Typography.titleLarge.withWeight(400), Theme.onSurfaceVariant)
    // One line, cut like the scooter display cuts it (RoadNames.TFT_CHARS, then "…").
    private val road = ctx.singleLine("", Typography.titleLarge, Theme.onSurface)
    private val arrival = Fact("schedule", "Arrival")
    private val timeLeft = Fact("timer", "Time left")
    private val kmLeft = Fact("route", "Distance left")
    private val active = ctx.column()

    // Idle: the same layout, ghosted (cookie glyph, placeholder distance/road/facts) under "Start in Maps".
    private val cookie = CookieDrawable(Theme.secondaryContainer)
    private val idle = ctx.column()

    // ------------------------------------------------------------------ setup + switch

    private val setup = ListGroup(ctx)
    private val accessButton = ctx.tonalButton("Allow") { request(AppPermission.NOTIFICATION_ACCESS) }
    private val accessRow = ListItem(ctx, "Notification access", null, "notifications", accessButton) {
        request(AppPermission.NOTIFICATION_ACCESS)
    }
    private val locationButton = ctx.tonalButton("Allow") { request(AppPermission.LOCATION) }
    private val locationRow = ListItem(ctx, "Location", null, "location_on", locationButton) { request(AppPermission.LOCATION) }
    private val connectButton = ctx.tonalButton("Connect") { connect() }
    private val connecting = LoadingIndicator(ctx, sizeDp = 24)
    private val scooterRow = ListItem(ctx, "Scooter", null, "bluetooth_disabled", connectButton)
    private val displayRow = SwitchListItem(ctx, "Scooter display", icon = "electric_moped", checked = host.isEnabled(Feature.DIRECTIONS)) { on ->
        host.setEnabled(Feature.DIRECTIONS, on)
        scaffold.haptic(HapticFeedbackConstants.CLOCK_TICK)
        render(System.currentTimeMillis())
    }
    // T1: the TFT shows directions only with its Navigation widget on. A row until done, then an info button.
    private val setupRow = ListItem(ctx, "Scooter display setup", null, "tune", IconView(ctx, "chevron_right")) { showSetup() }
    private val setupAction = bar.addAction("info", "Scooter display setup") { showSetup() }
    private var setupDone = DisplaySetup.isDone(ctx)
    private var setupSheet: BottomSheet? = null

    private val fab = ExtendedFab(ctx, "map", "Maps") { openMaps() }

    // ------------------------------------------------------------------ range check (F-RANGE)

    private val rangeChip = RangeChip()
    private val rangeRow = FrameLayout(ctx).apply {
        addView(rangeChip, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.START or Gravity.CENTER_VERTICAL))
    }
    /** What the chip shows (the sheet explains exactly this); null while hidden. */
    private var rangeShown: RangeVerdict? = null
    /** This route's last level, for hysteresis when NavBridge has none (demo); reset when the route ends. */
    private var rangeLevel: RangeLevel? = null
    private var rangeVisible: Boolean? = null

    // ------------------------------------------------------------------ state

    private var shown = false
    private var lastTurn: Int? = null
    private var lastMirror: Mirror? = null
    private var routeShown: Boolean? = null
    private var linkShown: LinkPhase? = null
    private var locationShown: PermissionState? = null
    private var fullRoad: String? = null
    /** Spoken road (Maps' full name, not the TFT hint) and second maneuver, for TalkBack and the sheet. */
    private var spokenRoad = ""
    private var thenTurn: Int? = null
    private var pulse: ObjectAnimator? = null
    private val turnPop = Spring(1f, Motion.STIFFNESS, 0.6f, 0.001f) { s -> turnTile.scaleX = s; turnTile.scaleY = s }
    /** 1 = the turn tile wears primaryContainer (directions are on the scooter), 0 = neutral preview. */
    private val tileLive = Spring(0f, threshold = 0.002f) { t ->
        tileShape.color = blend(Theme.surfaceContainerHigh, Theme.primaryContainer, t)
        turnIcon.tint = blend(Theme.onSurface, Theme.onPrimaryContainer, t)
    }
    private val navListener: () -> Unit = { if (shown) render(System.currentTimeMillis()) }

    init {
        buildHero()
        scaffold.page.add(hero, Spacing.S)
        // The chip's 32 dp sits in a 48 dp target: 4 + 8 dp inset = 12 dp from the card (GAP).
        scaffold.page.add(rangeRow, Spacing.XS)
        rangeRow.visibility = View.GONE

        for (row in listOf(accessRow, locationRow)) row.setSupporting("Needed", Theme.error)
        accessRow.tooltipText = "Lets Chetooth read Google Maps directions"
        locationRow.tooltipText = "Counts the distance down between Maps updates"
        scooterRow.tooltipText = "Directions reach the scooter display once it's connected"
        displayRow.tooltipText = "Turn arrow, distance and road on the scooter display"
        setupRow.tooltipText = "Turn on the Navigation widget on the scooter display"
        setup.addView(accessRow)
        setup.addView(locationRow)
        setup.addView(scooterRow)
        setup.addView(displayRow)
        setup.addView(setupRow)
        scaffold.page.add(setup, Spacing.SECTION)
        bindSetupDone()

        fab.tooltipText = "Open Google Maps"
        scaffold.fab = fab
        render(System.currentTimeMillis())
    }

    private fun buildHero() {
        // N1: a calm tonal container (no near-black glass, no outline), sized by its content.
        hero.setContainerColor(Theme.surfaceContainerLow)
        hero.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        hero.onClick { explainHero() }

        // Status: dot + one or two words.
        statusRow.minimumHeight = ctx.dp(24)
        dot.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        statusRow.addView(dot, LinearLayout.LayoutParams(ctx.dp(8), ctx.dp(8)))
        statusRow.addView(statusLabel, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = ctx.dp(Spacing.S) })

        // Turn tile + distance + road, like the cluster.
        turnTile.background = tileShape
        turnTile.addView(turnIcon, FrameLayout.LayoutParams(ctx.dp(56), ctx.dp(56), Gravity.CENTER))
        val distanceRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL // default TOP gravity keeps value and unit on one baseline
            addView(distance, LinearLayout.LayoutParams(WRAP, WRAP))
            addView(distanceUnit, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = ctx.dp(6) })
        }
        distance.includeFontPadding = false
        val texts = ctx.column().apply {
            addView(distanceRow, LinearLayout.LayoutParams(WRAP, WRAP))
            addView(road, ctx.params(Spacing.XS))
        }
        val main = ctx.row().apply {
            addView(turnTile, LinearLayout.LayoutParams(ctx.dp(TILE_DP), ctx.dp(TILE_DP)))
            addView(texts, ctx.weighted(start = 20))
        }
        val facts = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            minimumHeight = ctx.dp(24)
            // Natural widths plus an equal share of what is left (WRAP + weight): a long "10:45 PM"
            // takes room from a short "12 min" instead of being cut at large font sizes.
            for ((index, fact) in listOf(arrival, timeLeft, kmLeft).withIndex()) {
                addView(fact.view, LinearLayout.LayoutParams(WRAP, WRAP, 1f).apply { if (index > 0) marginStart = ctx.dp(Spacing.S) })
            }
        }
        active.addView(statusRow, LinearLayout.LayoutParams(WRAP, WRAP))
        active.addView(main, ctx.params(Spacing.M))
        active.addView(ctx.divider(), LinearLayout.LayoutParams(MATCH, maxOf(1, ctx.dp(1) / 2)).apply { topMargin = ctx.dp(Spacing.L) })
        active.addView(facts, ctx.params(Spacing.M))
        active.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS

        buildIdle()

        // Both states overlap in one frame, so the fade-through cross-fades in place (no double-height card).
        val states = FrameLayout(ctx)
        states.addView(active, FrameLayout.LayoutParams(MATCH, WRAP))
        states.addView(idle, FrameLayout.LayoutParams(MATCH, WRAP))
        hero.addView(states, ctx.params())
        active.visibility = View.GONE
    }

    /** Idle = the route layout ghosted: what will appear, before it appears. Same geometry, so no jump on start. */
    private fun buildIdle() {
        val title = ctx.singleLine("Start in Maps", Typography.titleMedium, Theme.onSurface)
        val head = ctx.row().apply { minimumHeight = ctx.dp(24); addView(title, LinearLayout.LayoutParams(WRAP, WRAP)) }

        val badge = FrameLayout(ctx).apply {
            background = cookie
            addView(IconView(ctx, "navigation", filled = true, tint = Theme.onSecondaryContainer, iconSizeDp = 40),
                FrameLayout.LayoutParams(ctx.dp(40), ctx.dp(40), Gravity.CENTER))
        }
        val distanceGhost = ctx.row().apply {
            minimumHeight = ctx.dp(52)
            addView(ghost(96, 32))
            addView(ctx.singleLine("m", Typography.titleLarge.withWeight(400), Theme.outline),
                LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = ctx.dp(6) })
        }
        val roadGhost = ctx.row().apply { minimumHeight = ctx.dp(28); addView(ghost(136, 14)) }
        val texts = ctx.column().apply {
            addView(distanceGhost, LinearLayout.LayoutParams(WRAP, WRAP))
            addView(roadGhost, ctx.params(Spacing.XS))
        }
        val main = ctx.row().apply {
            addView(badge, LinearLayout.LayoutParams(ctx.dp(TILE_DP), ctx.dp(TILE_DP)))
            addView(texts, ctx.weighted(start = 20))
        }
        val facts = ctx.row().apply {
            minimumHeight = ctx.dp(24)
            for ((index, icon) in listOf("schedule", "timer", "route").withIndex()) {
                val fact = ctx.row().apply {
                    addView(IconView(ctx, icon, tint = Theme.outline, iconSizeDp = Icons.SMALL),
                        LinearLayout.LayoutParams(ctx.dp(Icons.SMALL), ctx.dp(Icons.SMALL)))
                    addView(ghost(40, 12), LinearLayout.LayoutParams(ctx.dp(40), ctx.dp(12)).apply { marginStart = ctx.dp(Spacing.S) })
                }
                addView(fact, LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (index > 0) marginStart = ctx.dp(Spacing.S) })
            }
        }
        idle.addView(head, LinearLayout.LayoutParams(MATCH, WRAP))
        idle.addView(main, ctx.params(Spacing.M))
        idle.addView(ctx.divider(), LinearLayout.LayoutParams(MATCH, maxOf(1, ctx.dp(1) / 2)).apply { topMargin = ctx.dp(Spacing.L) })
        idle.addView(facts, ctx.params(Spacing.M))
        idle.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    /** Static placeholder block (not a pulsing skeleton: nothing is loading, it shows the layout). */
    private fun ghost(widthDp: Int, heightDp: Int): View = View(ctx).apply {
        background = RoundedShapeDrawable(Theme.surfaceContainerHigh, ctx.dpf(Shapes.FULL))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = LinearLayout.LayoutParams(ctx.dp(widthDp), ctx.dp(heightDp))
    }

    // ------------------------------------------------------------------ Screen

    override fun onShow() {
        shown = true
        // Onboarding's "Directions on your scooter" step may have marked the setup done meanwhile.
        val done = DisplaySetup.isDone(ctx)
        if (done != setupDone) { setupDone = done; bindSetupDone() }
        NavBridge.attach(ctx)
        NavBridge.addListener(navListener)
        if (routeShown != true) cookie.spinOnce() // A short settle turn; no endless redraws while the tab is idle.
        render(System.currentTimeMillis())
    }

    override fun onHide() {
        shown = false
        NavBridge.removeListener(navListener)
        cookie.stop()
        setPulse(false)
    }

    override fun dispose() {
        onHide()
        setupSheet?.dismiss()
        setupSheet = null
        turnPop.cancel()
        tileLive.cancel()
        rangeChip.cancel()
    }

    override fun render(now: Long) {
        val preview = host.navPreview
        val link = host.link
        val testing = preview.testRunning && link.phase == LinkPhase.READY
        // Exactly what goes to the scooter, as the shell reports it: the bridge's frame (counted-down
        // distances, TFT road), the diagnostics test frame while a test runs, or the demo's route.
        val frame = preview.frame?.takeIf { it.active }

        showRoute(frame != null)
        val mirror = mirror(preview, link, testing)
        if (frame != null) bindFrame(frame, testing, preview)
        bindStatus(mirror, frame != null)
        bindRange(if (frame != null && !testing) rangeVerdict(frame) else null)
        bindSetup(link)

        hero.contentDescription = if (frame == null) "No route. Start navigation in Google Maps."
        else buildString {
            append(Icons.turnDescription(frame.turn)).append(", ")
            append(distance.text).append(' ').append(distanceUnit.text)
            if (spokenRoad.isNotBlank()) append(", ").append(spokenRoad)
            thenTurn?.let { append(", then ").append(Icons.turnDescription(it).lowercase(Locale.ROOT)) }
            append(". ").append(arrival.spoken).append(", ").append(timeLeft.spoken).append(", ").append(kmLeft.spoken)
            append(". ").append(mirror.spoken)
        }
    }

    // ------------------------------------------------------------------ hero binding

    private fun showRoute(route: Boolean) {
        if (routeShown == route) return
        val first = routeShown == null
        routeShown = route
        val incoming = if (route) active else idle
        val outgoing = if (route) idle else active
        if (first || !shown) { outgoing.visibility = View.GONE; incoming.visibility = View.VISIBLE }
        else Motion.fadeThrough(outgoing, incoming)
        // The hero speaks for both (one TalkBack stop); fadeThrough resets the incoming view to AUTO.
        incoming.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        outgoing.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        if (!route) { lastTurn = null; fullRoad = null; spokenRoad = ""; thenTurn = null; rangeLevel = null; setPulse(false) }
    }

    private fun bindFrame(frame: NavFrame, testing: Boolean, preview: NavPreview) {
        if (frame.turn != lastTurn) {
            val changed = lastTurn != null
            lastTurn = frame.turn
            turnIcon.setTurn(frame.turn)
            if (changed && shown && Motion.enabled()) { turnPop.snapTo(0.86f); turnPop.animateTo(1f) }
        }

        // Distance as the 0x0303 frame encodes it: metres below 1 km, else tenths of a km (truncated).
        val metres = frame.distanceMeters.coerceAtLeast(0)
        if (metres < 1000) { distance.roll("$metres"); distanceUnit.show("m") }
        else { distance.roll("${metres / 1000}.${metres % 1000 / 100}"); distanceUnit.show("km") }

        // Mirror the display: about TFT_CHARS characters, then "…"; nothing when the frame has no road
        // (the turn is already in the glyph and the spoken text). The full name is in the explain sheet.
        val roadText = frame.roadText.trim()
        val cut = roadText.length > RoadNames.TFT_CHARS
        road.show(if (cut) roadText.take(RoadNames.TFT_CHARS).trimEnd() + "…" else roadText)
        road.tint(Theme.onSurface)
        // The frame's road may be a "Then …" hint; the sheet and TalkBack get Maps' road and the second turn.
        val cueRoad = preview.road?.trim()?.takeIf { !testing }
        spokenRoad = cueRoad ?: roadText
        thenTurn = preview.thenTurn?.takeIf { !testing }
        fullRoad = spokenRoad.takeIf { it.isNotEmpty() } ?: preview.instruction?.takeIf { !testing && it.isNotBlank() }

        // Maps leaves some values out; a frame from elsewhere (test, demo) carries all of them.
        val cue = if (Demo.active) null else NavBridge.cue
        val arrivalKnown = testing || cue == null || cue.arrivalHour24 != null
        if (arrivalKnown) {
            // The frame carries a 12-hour clock with an AM/PM flag, so show it that way.
            val hour12 = (frame.arrivalHour24 % 12).let { if (it == 0) 12 else it }
            val suffix = DateFormatSymbols.getInstance().amPmStrings[if (frame.arrivalHour24 % 24 >= 12) 1 else 0]
            arrival.set(String.format(Locale.US, "%d:%02d", hour12, frame.arrivalMinute.coerceIn(0, 59)), suffix)
            val minutes = frame.remainingMinutes.coerceIn(0, 15 * 60 + 59)
            if (minutes < 60) timeLeft.set("$minutes", "min")
            else timeLeft.set(String.format(Locale.US, "%d:%02d", minutes / 60, minutes % 60), "h")
        } else {
            arrival.set("—", null)
            timeLeft.set("—", null)
        }
        val remainingKnown = testing || cue == null || NavBridge.distances()?.remainingM != null || cue.remainingMeters != null
        if (remainingKnown) {
            val tenths = frame.remainingMeters.coerceAtLeast(0) / 100
            kmLeft.set("${tenths / 10}.${tenths % 10}", "km")
        } else kmLeft.set("—", null)
    }

    private fun mirror(preview: NavPreview, link: LinkStatus, testing: Boolean): Mirror = when {
        testing -> Mirror.TEST
        !host.isEnabled(Feature.DIRECTIONS) -> Mirror.OFF
        link.phase == LinkPhase.CONNECTING -> Mirror.CONNECTING
        link.phase != LinkPhase.READY -> Mirror.OFFLINE
        else -> when (preview.display) {
            NavDisplay.SHOWING -> Mirror.LIVE
            NavDisplay.REJECTED -> Mirror.REJECTED
            NavDisplay.PREPARING -> Mirror.SENDING
            NavDisplay.UNAVAILABLE -> Mirror.UNSUPPORTED
        }
    }

    private fun bindStatus(mirror: Mirror, route: Boolean) {
        if (mirror != lastMirror) {
            val previous = lastMirror
            lastMirror = mirror
            dotShape.color = if (mirror.tone == Tone.NEUTRAL) Theme.outline else mirror.tone.accent
            dot.invalidate()
            statusLabel.show(mirror.label)
            statusLabel.tint(when (mirror) {
                Mirror.LIVE -> Theme.onSurface
                Mirror.REJECTED -> Theme.error
                else -> Theme.onSurfaceVariant
            })
            if (mirror == Mirror.REJECTED && previous != null && previous != Mirror.REJECTED && shown && route) {
                // Most often the TFT's Navigation widget is off: offer the setup steps.
                host.showSnackbar("Couldn't show directions on the scooter", "Setup") { showSetup() }
            }
        }
        setPulse(route && mirror == Mirror.LIVE)
        // Colour carries meaning: the tile is vivid only while the scooter shows (or is about to show) it.
        val live = if (mirror == Mirror.LIVE || mirror == Mirror.SENDING || mirror == Mirror.TEST) 1f else 0f
        if (tileLive.target != live) { if (shown && route && Motion.enabled()) tileLive.animateTo(live) else tileLive.snapTo(live) }
    }

    /** Slow breathing of the status dot while frames are being acknowledged. */
    private fun setPulse(on: Boolean) {
        val want = on && shown && Motion.enabled()
        if (want == (pulse != null)) return
        if (want) {
            pulse = ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, 0.35f).apply {
                duration = 1_200L
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                interpolator = Motion.standard
                start()
            }
        } else {
            pulse?.cancel()
            pulse = null
            dot.alpha = 1f
        }
    }

    // ------------------------------------------------------------------ tap explains (W2)

    /** The hero explains itself in a sheet; "Open Maps" there is an explicit, named way out. */
    private fun explainHero() {
        val route = routeShown == true
        val mirror = lastMirror ?: Mirror.OFFLINE
        val sheet = if (route) {
            val rows = ArrayList<Pair<String, String>>()
            rows += "Scooter display" to mirror.label
            fullRoad?.let { rows += "Road" to it }
            thenTurn?.let { rows += "Then" to Icons.turnDescription(it) }
            rows += arrival.what to arrival.display
            rows += timeLeft.what to timeLeft.display
            rows += kmLeft.what to kmLeft.display
            ExplainSheet(
                icon = Icons.turnName(lastTurn),
                title = Icons.turnDescription(lastTurn),
                value = distance.text.toString().ifEmpty { "—" },
                unit = distanceUnit.text.toString().ifEmpty { null },
                meaning = "The next turn and the distance to it, exactly as the scooter display shows them.",
                rows = rows,
                historyLabel = "Open Maps",
                onHistory = { openMaps() }
            )
        } else {
            val link = host.link
            val ready = when {
                !host.isEnabled(Feature.DIRECTIONS) -> "Off"
                link.phase == LinkPhase.CONNECTING -> "Connecting"
                link.phase != LinkPhase.READY -> "Offline"
                else -> "Ready"
            }
            ExplainSheet(
                icon = "navigation",
                title = "Directions",
                value = ready,
                meaning = "Start a route in Google Maps. The next turn, its distance and the road then show here and on the scooter display.",
                rows = listOf("Scooter display" to if (host.isEnabled(Feature.DIRECTIONS)) "On" else "Off"),
                historyLabel = "Open Maps",
                onHistory = { openMaps() }
            )
        }
        host.explain(sheet)
    }

    // ------------------------------------------------------------------ range check (F-RANGE)

    /**
     * The route's range check from Host state: [Host.range] against the route left. While Maps
     * navigates the route left is NavBridge's (counted down between Maps updates, null when Maps gives
     * none) and the hysteresis follows NavBridge's trip, so the chip and the notification agree; for a
     * route from elsewhere (the demo) it is the preview's. Null without a live charge reading: a
     * last-known one can be days old (charged since), and the setup rows already say "Offline".
     */
    private fun rangeVerdict(frame: NavFrame): RangeVerdict? {
        if (!host.battery.fresh && !Demo.active) return null
        // The demo's route is the preview's, even if Maps happens to be navigating meanwhile.
        val cue = if (Demo.active) null else NavBridge.cue
        val remainingM = if (cue != null) NavBridge.distances()?.remainingM else frame.remainingMeters.takeIf { it > 0 }
        val previous = (if (cue != null) NavBridge.rangeVerdict?.level else null) ?: rangeLevel
        return RangeCheck.evaluate(remainingM?.let { it / 1000.0 }, host.range, previous)
    }

    private fun bindRange(verdict: RangeVerdict?) {
        rangeShown = verdict
        if (verdict != null) {
            rangeLevel = verdict.level
            rangeChip.bind(verdict, animate = shown && rangeVisible == true)
        }
        val visible = verdict != null
        if (visible == rangeVisible) return
        val first = rangeVisible == null
        rangeVisible = visible
        if (first || !shown) rangeRow.visible(visible) else Motion.fade(rangeRow, visible)
    }

    /** Range check sheet: the verdict, the three numbers behind it, and one way out: "Find chargers". */
    private fun explainRange() {
        val verdict = rangeShown ?: return
        val short = verdict.level == RangeLevel.SHORT
        val battery = host.battery
        val charge = battery.total.soc ?: listOfNotNull(battery.pack1.soc, battery.pack2.soc).takeIf { it.isNotEmpty() }?.average()
        val kmPerPercent = Fmt.decimal(host.range?.kmPerPercent ?: RangeModel.DEFAULT_KM_PER_PERCENT, 2)
        val rangeNote = when {
            !verdict.estimated -> "As the scooter reports it"
            charge != null -> "${Fmt.decimal(charge)} % charge × $kmPerPercent km per 1 %"
            else -> "Charge × $kmPerPercent km per 1 %"
        }
        host.explain(ExplainSheet(
            icon = if (short) "battery_alert" else "battery_5_bar",
            title = "Range check",
            value = (if (short) verdict.shortWholeKm else verdict.spareWholeKm).toString(),
            unit = if (short) "km short" else "km spare",
            note = if (verdict.estimated) "est." else null,
            meaning = when (verdict.level) {
                RangeLevel.OK -> "Your range covers the route left in Google Maps, with charge to spare."
                RangeLevel.TIGHT -> if (verdict.noSpare) "Your range is about the same as the route left in Google Maps. Charge before you arrive."
                    else "Your range only just covers the route left in Google Maps. Plan a charge soon."
                RangeLevel.SHORT -> "Your range is less than the route left in Google Maps. Charge before you arrive."
            },
            rows = listOf(
                "Range" to "${Fmt.decimal(verdict.rangeKm, 0)} km" + if (verdict.estimated) " est." else "",
                "Route left" to "${Fmt.decimal(verdict.remainingKm, 1)} km",
                "Reserve" to "${Fmt.decimal(RangeCheck.RESERVE_KM, 0)} km"
            ),
            rowNotes = mapOf(
                "Range" to rangeNote,
                "Route left" to "From Google Maps, counted down as you ride",
                "Reserve" to "Less to spare than this is tight, give or take 1 km"
            ),
            historyLabel = "Find chargers",
            onHistory = { findChargers() }
        ))
    }

    /** Google Maps search for chargers near you (the Maps URL; the browser if Maps is missing). */
    private fun findChargers() {
        scaffold.haptic(HapticFeedbackConstants.CONFIRM)
        val url = Uri.parse("https://www.google.com/maps/search/?api=1&query=" + Uri.encode("EV charging station"))
        val opened = listOf(Intent(Intent.ACTION_VIEW, url).setPackage(MapsNavigationListener.MAPS), Intent(Intent.ACTION_VIEW, url)).any { intent ->
            try { host.activity.startActivity(intent); true } catch (_: ActivityNotFoundException) { false }
        }
        if (!opened) host.showSnackbar("Couldn't open Google Maps")
    }

    // ------------------------------------------------------------------ setup rows

    private fun bindSetup(link: LinkStatus) {
        val access = host.permission(AppPermission.NOTIFICATION_ACCESS)
        // A running listener proves access even if the host can't tell.
        val accessOk = access == PermissionState.GRANTED || access == PermissionState.NOT_NEEDED || NavBridge.listenerConnected
        accessRow.visible(!accessOk)
        if (!accessOk) accessButton.text = if (access == PermissionState.BLOCKED) "Settings" else "Allow"

        val location = host.permission(AppPermission.LOCATION)
        val fine = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val locationOk = fine || location == PermissionState.GRANTED || location == PermissionState.NOT_NEEDED
        locationRow.visible(!locationOk)
        if (!locationOk && location != locationShown) {
            locationShown = location
            locationButton.text = if (location == PermissionState.BLOCKED) "Settings" else "Allow"
            locationRow.setSupporting(if (location == PermissionState.PARTIAL) "Approximate" else "Needed", Theme.error)
        }

        scooterRow.visible(link.phase != LinkPhase.READY)
        if (link.phase != linkShown) {
            linkShown = link.phase
            when (link.phase) {
                LinkPhase.CONNECTING -> {
                    scooterRow.setIcon("bluetooth_searching")
                    scooterRow.setSupporting("Connecting")
                    scooterRow.setTrailing(connecting)
                    scooterRow.setOnClick(null)
                }
                else -> {
                    scooterRow.setIcon("bluetooth_disabled")
                    scooterRow.setSupporting("Offline")
                    scooterRow.setTrailing(connectButton)
                    scooterRow.setOnClick { connect() }
                }
            }
        }
        displayRow.checked = host.isEnabled(Feature.DIRECTIONS)
    }

    /** Row while the setup is not done; afterwards only the top-bar info button reopens it. */
    private fun bindSetupDone() {
        setupRow.visible(!setupDone)
        setupAction.visible(setupDone)
    }

    private fun showSetup() {
        setupSheet?.takeIf { it.isShowing }?.let { return }
        val done = ActionButton(ctx, "Done", ButtonVariant.FILLED, size = ButtonSize.MEDIUM) { finishSetup() }
        val content = ctx.column().apply {
            addView(DisplaySetup.stepsView(ctx), ctx.params())
            addView(done, ctx.params(Spacing.XL))
        }
        setupSheet = host.showSheet(content, DisplaySetup.TITLE)?.apply { onDismiss = { if (setupSheet === this) setupSheet = null } }
    }

    private fun finishSetup() {
        scaffold.haptic(HapticFeedbackConstants.CONFIRM)
        setupSheet?.dismiss()
        setupSheet = null
        if (setupDone) return
        setupDone = true
        DisplaySetup.markDone(ctx)
        bindSetupDone()
    }

    // ------------------------------------------------------------------ actions

    private fun openMaps() {
        fab.haptic(HapticFeedbackConstants.CONFIRM)
        host.openMaps()
    }

    private fun connect() {
        scooterRow.haptic(HapticFeedbackConstants.CONFIRM)
        host.connect()
    }

    private fun request(permission: AppPermission) {
        scaffold.haptic(HapticFeedbackConstants.CONFIRM)
        host.requestPermission(permission)
    }

    // ------------------------------------------------------------------ helpers

    /** Short roll-up crossfade when a number changes (never a hard jump while visible). */
    private fun TextView.roll(next: CharSequence) {
        if (TextUtils.equals(text, next)) return
        val animate = shown && isShown && text.isNotEmpty() && Motion.enabled()
        text = next
        if (!animate) return
        animate().cancel()
        alpha = 0.4f
        translationY = dpf(6f)
        animate().alpha(1f).translationY(0f).setDuration(Motion.SHORT4).setInterpolator(Motion.emphasizedDecelerate).start()
    }

    private fun View.haptic(kind: Int) { performHapticFeedback(kind) }

    /**
     * One bottom fact of the preview: icon + tabular value + small unit. No tooltip: the hero is one
     * tap target and its sheet names every fact.
     */
    private inner class Fact(icon: String, val what: String) {
        private val value = ctx.singleLine("—", Typography.titleMedium, Theme.onSurface).tabular()
        private val unit = ctx.singleLine("", Typography.bodyMedium, Theme.onSurfaceVariant)
        var spoken = "$what unknown"; private set
        /** Value and unit for the explain sheet ("12 min", "—"). */
        var display = "—"; private set
        val view: LinearLayout = ctx.row().apply {
            addView(IconView(ctx, icon, tint = Theme.onSurfaceVariant, iconSizeDp = Icons.SMALL),
                LinearLayout.LayoutParams(ctx.dp(Icons.SMALL), ctx.dp(Icons.SMALL)))
            val pair = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL // TOP gravity: value and unit share a baseline
                addView(value, LinearLayout.LayoutParams(WRAP, WRAP))
                addView(unit, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = ctx.dp(3) })
            }
            addView(pair, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = ctx.dp(Spacing.S) })
        }

        init {
            unit.visibility = View.GONE
            unit.ellipsize = null // "PM", "km", "h" are never cut to "…"
        }

        fun set(text: String, unitText: String?) {
            value.roll(text)
            unit.show(unitText.orEmpty())
            unit.visible(!unitText.isNullOrEmpty())
            display = if (unitText.isNullOrEmpty()) text else "$text $unitText"
            spoken = if (text == "—") "$what unknown" else "$what $display"
        }
    }

    /**
     * The range check chip: battery icon + "12 km spare" / "Tight · 3 km spare" / "Short by 4 km".
     * Like every chip: 32 dp, 8 dp corners, Label L, 20 dp icon, inside a 48 dp touch target, with
     * ripple and the 0.97 press. Colour carries the verdict: neutral with a primary icon (OK),
     * tertiary container (tight), error container (short); a level change blends over (spring).
     * Tap = the explanation sheet (W2: it never navigates by itself).
     */
    private inner class RangeChip : LinearLayout(ctx) {
        private val shape = RoundedShapeDrawable(Theme.surfaceContainerHigh, ctx.dpf(Shapes.CHIP))
        private val icon = IconView(ctx, "battery_5_bar", tint = Theme.primary, iconSizeDp = Icons.SMALL)
        private val label = ctx.singleLine("", Typography.labelLarge, Theme.onSurface).tabular()
        private var level: RangeLevel? = null
        private var press: PressScale? = null
        // Colour blend from the previous level's colours (0) to the current one's (1).
        private val from = IntArray(3)
        private val to = intArrayOf(Theme.surfaceContainerHigh, Theme.primary, Theme.onSurface)
        private val blendSpring = Spring(1f, dampingRatio = Motion.DAMPING_NO_BOUNCE, threshold = 0.002f) { t -> paint(t.coerceIn(0f, 1f)) }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val inset = ctx.dp((Spacing.TOUCH - CHIP_DP) / 2)
            minimumHeight = ctx.dp(Spacing.TOUCH)
            shape.setInsets(0, inset, 0, inset)
            background = rippleOver(shape, RoundedShapeDrawable(Color.WHITE, ctx.dpf(Shapes.CHIP)).apply { setInsets(0, inset, 0, inset) }, Theme.onSurface)
            setPaddingRelative(ctx.dp(Spacing.S), 0, ctx.dp(Spacing.L), 0)
            addView(icon, LayoutParams(ctx.dp(Icons.SMALL), ctx.dp(Icons.SMALL)).apply { marginEnd = ctx.dp(Spacing.S) })
            addView(label, LayoutParams(WRAP, WRAP))
            label.ellipsize = null // "Tight · 3 km spare" is never cut to "…"
            label.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            isClickable = true
            isFocusable = true
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            setOnSingleClick { explainRange() }
            press = PressScale(this)
            paint(1f)
        }

        fun bind(verdict: RangeVerdict, animate: Boolean) {
            label.roll(verdict.label)
            contentDescription = "Range check, " + when (verdict.level) {
                RangeLevel.OK -> "${verdict.spareWholeKm} kilometres spare"
                RangeLevel.TIGHT -> if (verdict.noSpare) "tight, no spare" else "tight, ${verdict.spareWholeKm} kilometres spare"
                RangeLevel.SHORT -> "short by ${verdict.shortWholeKm} kilometres"
            }
            if (verdict.level == level) return
            level = verdict.level
            icon.setIcon(if (verdict.level == RangeLevel.SHORT) "battery_alert" else "battery_5_bar")
            from[0] = shape.color; from[1] = icon.tint; from[2] = label.currentTextColor
            when (verdict.level) {
                RangeLevel.OK -> { to[0] = Theme.surfaceContainerHigh; to[1] = Theme.primary; to[2] = Theme.onSurface }
                RangeLevel.TIGHT -> { to[0] = Theme.tertiaryContainer; to[1] = Theme.onTertiaryContainer; to[2] = Theme.onTertiaryContainer }
                RangeLevel.SHORT -> { to[0] = Theme.errorContainer; to[1] = Theme.onErrorContainer; to[2] = Theme.onErrorContainer }
            }
            // snapTo paints at once; animateTo then blends over to the new level's colours.
            if (animate && Motion.enabled()) { blendSpring.snapTo(0f); blendSpring.animateTo(1f) } else blendSpring.snapTo(1f)
        }

        fun cancel() = blendSpring.cancel()

        private fun paint(t: Float) {
            shape.color = blend(from[0], to[0], t)
            icon.tint = blend(from[1], to[1], t)
            label.setTextColor(blend(from[2], to[2], t))
        }

        override fun drawableStateChanged() {
            super.drawableStateChanged()
            press?.update()
        }

        override fun getAccessibilityClassName(): CharSequence = Button::class.java.name
    }

    private companion object {
        /** Visual chip height (dp) inside its 48 dp touch target. */
        const val CHIP_DP = 32
        /** Turn tile / idle cookie size (dp): one geometry for both states, so starting a route doesn't jump. */
        const val TILE_DP = 88
    }
}

/**
 * Scooter display (TFT) setup, POLISH T1/T2: the TFT shows directions only while its Navigation
 * widget is on. Four short numbered steps with icons (the path on the scooter: long-press the
 * handlebar toggle button › Menu › Display › Widgets › Navigation › on), shared by Navigate's sheet
 * and onboarding's "Directions on your scooter" step. "Done" is remembered in the "companion"
 * preferences (already loaded at startup), for all scooters on this phone.
 */
object DisplaySetup {
    const val TITLE = "Scooter display setup"
    private const val PREFS = "companion"
    private const val KEY = "display_setup_done"

    class Step(val icon: String, val text: String, val note: String? = null)

    val STEPS = listOf(
        Step("sports_motorsports", "Long-press the toggle button", "Handlebar"),
        Step("settings", "Menu › Display"),
        Step("navigation", "Widgets › Navigation"),
        Step("check_circle", "Turn it on")
    )

    fun isDone(context: Context): Boolean =
        runCatching { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false) }.getOrDefault(false)

    fun markDone(context: Context) {
        runCatching { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, true).apply() }
    }

    /**
     * Numbered steps: the number in a 32 dp tonal circle, the step (Body L, one optional note), its
     * icon at the end. Each step is one accessibility node ("Step 1 of 4: …").
     */
    fun stepsView(context: Context): View = context.column().apply {
        val circle = 32
        STEPS.forEachIndexed { index, step ->
            val number = context.singleLine("${index + 1}", Typography.titleSmall, Theme.onSecondaryContainer).tabular().apply {
                gravity = Gravity.CENTER
                background = RoundedShapeDrawable(Theme.secondaryContainer, context.dpf(Shapes.FULL))
            }
            val texts = context.column().apply {
                addView(context.textView(step.text, Typography.bodyLarge, Theme.onSurface), LinearLayout.LayoutParams(MATCH, WRAP))
                if (step.note != null) addView(context.singleLine(step.note, Typography.bodyMedium, Theme.onSurfaceVariant), LinearLayout.LayoutParams(MATCH, WRAP))
            }
            val row = context.row().apply {
                minimumHeight = context.dp(Spacing.TOUCH)
                addView(number, LinearLayout.LayoutParams(context.dp(circle), context.dp(circle)))
                addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = context.dp(Spacing.L); marginEnd = context.dp(Spacing.L) })
                addView(IconView(context, step.icon, tint = Theme.onSurfaceVariant), LinearLayout.LayoutParams(context.dp(24), context.dp(24)))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                contentDescription = "Step ${index + 1} of ${STEPS.size}: ${step.text.replace(" › ", ", ")}" +
                    (step.note?.let { ", $it" } ?: "")
                for (i in 0 until childCount) getChildAt(i).importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
            addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply { if (index > 0) topMargin = context.dp(Spacing.S) })
        }
    }
}
