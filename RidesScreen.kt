package dev.codesurgen.ruta

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private const val MAPS_PACKAGE = "com.google.android.apps.maps"
private const val PAGE = 50
/** Live speed trace length (one sample per second while the tab is visible). */
private const val SPARK_SAMPLES = 120
private const val SPEED_BUCKETS = 120
private const val THUMB_POINTS = 48
private const val THUMB_CACHE = 200
private const val STAGGER_MS = 20L
private const val STAGGER_MAX = 12
/** A detail open when the shell was rebuilt or the activity recreated reopens if the new screen comes up within this. */
private const val RESTORE_WINDOW_MS = 15_000L
private const val PERMISSION_RECHECK_MS = 5_000L

/**
 * Rides tab. List: location hint (only when rides cannot be recorded), the live ride while recording,
 * this week's distance / rides / top speed, then finished rides grouped by day (route thumbnail,
 * "Riverside Garden Rd → Lakeside" on up to two lines, one line of icon stats: distance, time, top
 * speed) and "Show older". Detail (in place, back closes it), laid out like a trip receipt: title =
 * date and start time, route canvas (start trip_origin, end flag), from/to places with times on a
 * dotted rail, six stat tiles, speed chart whose scrub marks the spot on the route, "Maps" (extended
 * FAB: directions from start to end only) and delete (top bar, confirmed in a sheet).
 * Tapping a number explains it in a sheet ([Host.explain]); only rows, back, Maps and delete move.
 * All storage calls run on Dispatchers.IO; views update on the main thread (Ui.scope()).
 */
class RidesScreen(private val host: Host) : Screen {
    private val ctx: Context = host.context
    private val scope = Ui.scope()
    private val root = FrameLayout(ctx)
    override val view: View = root
    private val listener: () -> Unit = { onLiveChanged() }

    // ---------------------------------------------------------------- list
    private val listBar = TopAppBar(ctx, large = true).apply { title = "Rides" }
    private val listScaffold = Scaffold(ctx, listBar)
    /** List and detail each sit in a gate: while they fade through, the leaving one takes no touch (W3). */
    private val listGate = TouchGate(ctx).apply { addView(listScaffold, FrameLayout.LayoutParams(MATCH, MATCH)) }
    private val listPage: ScrollPage get() = listScaffold.page
    private var listJob: Job? = null
    private var thumbJob: Job? = null
    /** Revision of the last list load started; RideLive fires every second, so compare with this. */
    private var requestedRevision = -1
    private var limit = PAGE
    private var listLoaded = false
    private var animateNextList = true
    private val rows = HashMap<Long, RideRow>()

    private enum class Hint { PERMISSION, LOCATION_OFF }
    private var hint: Hint? = null
    private var located = true
    private var locatedAt = 0L
    private val hintButton = ctx.tonalButton("Allow") { fixHint() }
    // M1: the standard tonal circle; only the state text is error-coloured (as on More and Navigate).
    private val hintRow = ListItem(ctx, "Location", null, "location_on", hintButton).apply {
        setSupporting("Needed", Theme.error)
        tooltipText = "Rides are recorded with the phone's GPS while the scooter is connected"
    }
    private val hintGroup = ListGroup(ctx).apply { addView(hintRow); visibility = View.GONE }

    private val liveChip = ctx.statusChip("Riding", "moving", Tone.PRIMARY)
    private val liveGps = IconView(ctx, "my_location", filled = true, tint = Theme.primary, iconSizeDp = 20)
    private val liveDistance = ctx.singleLine("0.0", Typography.displayMedium, Theme.onSurface).tabular()
    private val liveDistanceUnit = ctx.singleLine("km", Typography.titleMedium, Theme.onSurfaceVariant)
    private val liveSpeed = ctx.singleLine("0", Typography.displayMedium, Theme.primary).tabular()
    private val liveSpeedUnit = ctx.singleLine("km/h", Typography.titleMedium, Theme.onSurfaceVariant)
    private val liveSpark = Sparkline(ctx).apply { minSpan = 20.0 }
    private val liveTime = InlineStat(ctx, "timer", "Ride time")
    private val livePeak = InlineStat(ctx, "speed", "Top speed")
    private val liveAvg = InlineStat(ctx, "avg_pace", "Average speed")
    private val liveBattery = InlineStat(ctx, "battery_5_bar", "Battery")
    private val liveFrom = ctx.singleLine("", Typography.bodyMedium, Theme.onSurfaceVariant)
    private val liveFromRow = ctx.row()
    private val liveCard = ctx.filledCard()
    private val speedSamples = ArrayDeque<Double>()
    private var lastSampleAt = 0L

    private val weekDistance = StatChip(ctx, "route", Amounts.NONE, "km", "Distance this week")
    private val weekCount = StatChip(ctx, "electric_moped", Amounts.NONE, "rides", "Rides this week")
    private val weekTop = StatChip(ctx, "speed", Amounts.NONE, "km/h", "Top speed this week")
    private val weekBlock = ctx.column()
    /** This week's finished rides (newest first) behind the chips, for their sheets. */
    private var weekRides: List<Ride> = emptyList()
    private var weekFrom = 0L

    private val list = ctx.column()
    private val skeleton = ListGroup(ctx)
    private val connectButton = ctx.tonalButton("Connect", "electric_moped") { host.connect() }
    private val emptyView = ctx.emptyState("route", "No rides yet", null, connectButton).apply {
        tooltipText = "Rides are saved automatically while the scooter is connected"
    }
    private val errorView = ctx.emptyState("error", "Couldn't load rides", null, ctx.tonalButton("Retry", "refresh") { loadList() })
    private val olderButton = ctx.actionButton("Show older", ButtonVariant.TEXT, "expand_more") { limit += PAGE; loadList() }

    // ---------------------------------------------------------------- detail
    private val detailBar = TopAppBar(ctx)
    private val detailScaffold = Scaffold(ctx, detailBar)
    private val detailGate = TouchGate(ctx).apply { addView(detailScaffold, FrameLayout.LayoutParams(MATCH, MATCH)) }
    private val detailPage: ScrollPage get() = detailScaffold.page
    private val mapsFab = ExtendedFab(ctx, "map", "Maps") { openInMaps() }
    private var detailJob: Job? = null
    private var lookupJob: Job? = null
    private var openRide: Ride? = null
    private var openPoints: List<RidePoint> = emptyList()
    private var deleteSheet: BottomSheet? = null

    private val route = RouteView(ctx)
    private val legend = TripLegend(ctx)

    private val tileDistance = MetricTile(ctx, "route", "Distance")
    private val tileDuration = MetricTile(ctx, "timer", "Duration", "Ride time")
    private val tilePeak = MetricTile(ctx, "speed", "Top speed")
    private val tileAverage = MetricTile(ctx, "avg_pace", "Average", "Average moving speed")
    private val tileUsed = MetricTile(ctx, "battery_5_bar", "Battery used")
    private val tileEfficiency = MetricTile(ctx, "bolt", "Efficiency", "Kilometres per 1 % of charge")
    private val movingStat = InlineStat(ctx, "moving", "Moving time")

    private val speedChart = LineChart(ctx).apply {
        title = "Speed"
        heightDp = 200
        includeZero = true
        minSpan = 20.0
        valueFormat = { Fmt.speed(it) }
        onScrub = { route.marker = it }
    }

    init {
        buildList()
        buildDetail()
        root.addView(listGate, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(detailGate, FrameLayout.LayoutParams(MATCH, MATCH))
        detailGate.visibility = View.GONE
        val pending = restoreId
        if (pending != null && SystemClock.elapsedRealtime() - restoreAt < RESTORE_WINDOW_MS) lookup(pending)
        restoreId = null
    }

    // ---------------------------------------------------------------- Screen

    override fun onShow() {
        RideLive.addListener(listener)
        locatedAt = 0L
        animateNextList = true
        renderLive(System.currentTimeMillis())
        loadList()
    }

    override fun onHide() = RideLive.removeListener(listener)

    override fun render(now: Long) {
        renderLive(now)
        connectButton.visible(host.linkPhase == LinkPhase.IDLE)
    }

    /**
     * Back closes the detail. A detail the app opened from another tab (Host.openRide) is the shell's
     * business: its jump returns there after this, and a navigation-bar tap in between clears it.
     */
    override fun onBack(): Boolean {
        if (detailGate.visibility != View.VISIBLE) return false
        closeDetail()
        return true
    }

    override fun dispose() {
        RideLive.removeListener(listener)
        deleteSheet?.dismiss()
        deleteSheet = null
        // The shell is rebuilt in place (palette, font size, locale) or the activity recreated: the
        // next Rides screen reopens this ride. Not when Ruta is closing.
        val open = openRide
        if (open != null && !host.activity.isFinishing) {
            restoreId = open.id; restoreAt = SystemClock.elapsedRealtime()
        }
        scope.cancel()
    }

    /** Navigation-bar reselect: back to the top of the list. */
    fun scrollToTop() = listPage.scrollToTop()

    /** Opens a ride's detail by id from another tab (Home's last ride, Host.openRide). Does nothing if the ride no longer exists. */
    fun openRide(id: Long) = lookup(id)

    private fun lookup(id: Long) {
        openRide?.let { if (it.id == id) { showDetail(); return } }
        val store = host.store
        lookupJob?.cancel()
        lookupJob = scope.launch {
            val ride = try { withContext(Dispatchers.IO) { store.ride(id) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { RideLive.log("Rides: could not open ride $id (${e.javaClass.simpleName})"); null }
            if (ride != null) open(ride)
        }
    }

    // ---------------------------------------------------------------- list

    private fun buildList() = with(ctx) {
        // Live ride: status + GPS, distance and speed as the hero, speed trace, icon stats, start place.
        val head = row()
        head.addView(liveChip, LinearLayout.LayoutParams(WRAP, WRAP))
        head.addView(View(this), weighted())
        liveGps.tooltipText = "GPS fix"
        head.addView(liveGps, LinearLayout.LayoutParams(dp(Spacing.TOUCH), dp(Spacing.TOUCH)))
        liveCard.addView(head)
        val numbers = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = true }
        numbers.addView(valueUnit(liveDistance, liveDistanceUnit), weighted())
        numbers.addView(valueUnit(liveSpeed, liveSpeedUnit), LinearLayout.LayoutParams(WRAP, WRAP))
        liveCard.addView(numbers, params(Spacing.XS))
        liveSpark.surfaceColor = Theme.surfaceContainer
        liveCard.addView(liveSpark, params(Spacing.S, MATCH, dp(40)))
        liveCard.addView(StatFlow(this, Spacing.L, Spacing.S).apply {
            addView(liveTime); addView(livePeak); addView(liveAvg); addView(liveBattery)
        }, params(Spacing.M))
        liveFromRow.addView(IconView(this, "trip_origin", tint = Theme.onSurfaceVariant, iconSizeDp = 16),
            LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(Spacing.S) })
        liveFromRow.addView(liveFrom, weighted())
        liveCard.addView(liveFromRow, params(Spacing.M))
        liveCard.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        for (i in 0 until liveCard.childCount) liveCard.getChildAt(i).importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        liveCard.visibility = View.GONE

        weekBlock.addView(singleLine("This week", Typography.labelLarge, Theme.onSurfaceVariant).apply { isAccessibilityHeading = true })
        weekBlock.addView(StatFlow(this, Spacing.S, Spacing.S).apply {
            addView(weekDistance); addView(weekCount); addView(weekTop)
        }, params(Spacing.S))
        weekBlock.visibility = View.GONE
        weekDistance.explains { explainWeek(WeekStat.DISTANCE) }
        weekCount.explains { explainWeek(WeekStat.RIDES) }
        weekTop.explains { explainWeek(WeekStat.TOP_SPEED) }

        repeat(3) { skeleton.addView(SkeletonRow(this)) }
        skeleton.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        skeleton.contentDescription = "Loading rides"
        emptyView.visibility = View.GONE
        errorView.visibility = View.GONE
        olderButton.visibility = View.GONE

        listPage.add(hintGroup, Spacing.XS)
        listPage.add(liveCard, Spacing.M)
        listPage.add(weekBlock, Spacing.M)
        listPage.add(skeleton, Spacing.XL)
        listPage.add(list, Spacing.L)
        listPage.add(emptyView, Spacing.XL)
        listPage.add(errorView, Spacing.XL)
        listPage.column.addView(olderButton, LinearLayout.LayoutParams(WRAP, WRAP).apply {
            gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(Spacing.L)
        })
    }

    private fun onLiveChanged() {
        if (host.ride.revision != requestedRevision) loadList()
        renderLive(System.currentTimeMillis())
    }

    private fun loadList() {
        requestedRevision = host.ride.revision
        val count = limit
        val store = host.store
        val now = System.currentTimeMillis()
        val week = weekStart(now)
        listJob?.cancel()
        if (!listLoaded) { skeleton.visible(true); errorView.visible(false) }
        listJob = scope.launch {
            val data = try {
                withContext(Dispatchers.IO) {
                    // One extra row tells whether older rides exist.
                    store.rides(count + 1) to store.rides(Int.MAX_VALUE, week)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { RideLive.log("Rides: could not load rides (${e.javaClass.simpleName}: ${e.message})"); null }
            showList(data?.first, data?.second, week, count, store)
        }
    }

    /** [store]: the history [rides] came from (thumbnails are cached per history: ride ids overlap). */
    private fun showList(rides: List<Ride>?, thisWeek: List<Ride>?, week: Long, count: Int, store: TelemetryStore): Unit = with(ctx) {
        skeleton.visible(false)
        if (rides == null) {
            // Keep what is already shown; only an empty screen turns into the error state.
            if (!listLoaded) errorView.visible(true) else host.showSnackbar("Couldn't refresh rides")
            return
        }
        listLoaded = true
        errorView.visible(false)
        list.removeAllViews()
        rows.clear()
        thumbJob?.cancel() // Its tracks belong to the rows just removed.
        olderButton.visible(rides.size > count)
        val shownRides = rides.take(count)
        emptyView.visible(shownRides.isEmpty() && !host.ride.riding)
        connectButton.visible(host.linkPhase == LinkPhase.IDLE)
        weekBlock.visible(shownRides.isNotEmpty())
        if (thisWeek != null) {
            weekRides = thisWeek
            weekFrom = week
            val stats = RideMath.stats(thisWeek)
            val distance = Amounts.distance(stats.distanceM)
            weekDistance.setValue(distance.value, distance.unit)
            weekCount.setValue(stats.rides.toString(), if (stats.rides == 1) "ride" else "rides")
            weekTop.setValue(if (stats.rides > 0) Amounts.speed(stats.peakKmh).value else Amounts.NONE, "km/h")
        }
        if (shownRides.isEmpty()) return
        val animate = animateNextList && Motion.enabled()
        animateNextList = false
        val entering = ArrayList<View>()
        val now = System.currentTimeMillis()
        val today = localDayStart(now)
        val yesterday = localDayStart(today - 1)
        var first = true
        for ((day, dayRides) in shownRides.groupBy { localDayStart(it.start) }) {
            val title = when (day) { today -> "Today"; yesterday -> "Yesterday"; else -> Fmt.date(day) }
            val header = row().apply {
                addView(sectionHeader(title), weighted())
                val total = Amounts.distance(dayRides.sumOf { it.distanceM }).text
                addView(singleLine(total, Typography.labelLarge, Theme.onSurfaceVariant).tabular().apply {
                    setPadding(0, 0, dp(Spacing.L), 0)
                    contentDescription = "$title, ${dayRides.size} ${if (dayRides.size == 1) "ride" else "rides"}, $total"
                }, LinearLayout.LayoutParams(WRAP, WRAP))
            }
            list.addView(header, params(if (first) 0 else Spacing.L))
            entering += header
            val group = ListGroup(this)
            for (ride in dayRides) {
                val row = RideRow(this, ride, RideTitle.of(ride)) { openFromList(ride) }
                RouteThumbs.get(store.isDemo, ride.id)?.let { row.thumbnail.setTrack(it, ride) }
                rows[ride.id] = row
                group.addView(row)
                entering += row
            }
            list.addView(group, params(Spacing.XS))
            first = false
        }
        if (animate) stagger(entering)
        loadThumbs(shownRides, store)
    }

    /** List entrance: each item rises 12 dp and fades in, 20 ms after the previous one. */
    private fun stagger(views: List<View>) {
        val rise = ctx.dpf(12f)
        views.take(STAGGER_MAX).forEachIndexed { index, item ->
            item.alpha = 0f
            item.translationY = rise
            item.animate().alpha(1f).translationY(0f)
                .setStartDelay(index * STAGGER_MS).setDuration(Motion.MEDIUM2)
                .setInterpolator(Motion.emphasizedDecelerate).start()
        }
    }

    /** Route thumbnails from [store], newest first, one ride at a time (cached for the process). */
    private fun loadThumbs(rides: List<Ride>, store: TelemetryStore) {
        thumbJob?.cancel()
        val demo = store.isDemo
        val missing = rides.filter { RouteThumbs.get(demo, it.id) == null }
        if (missing.isEmpty()) return
        thumbJob = scope.launch {
            for (ride in missing) {
                // A failed read (e.g. the demo file closing) is not cached: the next list load tries again.
                val points = try { withContext(Dispatchers.IO) { RideMath.thin(store.ridePoints(ride.id), THUMB_POINTS) } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { continue }
                RouteThumbs.put(demo, ride.id, points)
                rows[ride.id]?.thumbnail?.setTrack(points, ride)
            }
        }
    }

    private fun renderLive(now: Long) {
        val state = host.ride
        renderHint(state, now)
        liveCard.visible(state.riding)
        if (!state.riding) {
            if (speedSamples.isNotEmpty()) { speedSamples.clear(); liveSpark.setValues(emptyList()) }
            return
        }
        emptyView.visible(false)
        if (now - lastSampleAt >= 900) {
            lastSampleAt = now
            speedSamples.addLast(state.speedKmh)
            while (speedSamples.size > SPARK_SAMPLES) speedSamples.removeFirst()
            liveSpark.setValues(speedSamples.toList())
        }
        val fix = state.gps == GpsStatus.FIX
        liveGps.setIcon("my_location", filled = fix)
        liveGps.tint = if (fix) Theme.primary else Theme.warning
        val gpsText = if (fix) "GPS fix" else "Searching for GPS"
        if (liveGps.tooltipText != gpsText) liveGps.tooltipText = gpsText
        val distance = Amounts.distance(state.distanceM)
        liveDistance.roll(distance.value)
        liveDistanceUnit.show(distance.unit)
        val speed = Amounts.speed(state.speedKmh)
        liveSpeed.roll(speed.value)
        liveTime.set(Amount(Fmt.timer(state.durationS)))
        livePeak.set(Amounts.speed(state.peakKmh))
        liveAvg.set(Amounts.speed(state.avgKmh))
        val start = state.socStart
        val latest = state.socPercent
        liveBattery.visible(start != null)
        if (start != null) liveBattery.set(Amount(if (latest != null) "${percentText(start)} → ${percentText(latest)}" else percentText(start), "%"))
        val from = state.startPlace
        liveFromRow.visible(from != null)
        if (from != null) liveFrom.show(from)
        val spoken = buildString {
            append("Riding. ").append(distance.text).append(", ").append(speed.text).append(". ")
            append("Ride time ").append(Fmt.timer(state.durationS)).append(", top speed ").append(Amounts.speed(state.peakKmh).text)
            append(". ").append(gpsText).append('.')
            if (from != null) append(" From ").append(from).append('.')
        }
        if (liveCard.contentDescription != spoken) liveCard.contentDescription = spoken
    }

    /** Shows why nothing is recorded (precise location missing or phone location off), with the fix. */
    private fun renderHint(state: RideState, now: Long) {
        if (now - locatedAt >= PERMISSION_RECHECK_MS || locatedAt == 0L) {
            locatedAt = now
            located = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
        val next = when {
            !located || state.gps == GpsStatus.NO_PERMISSION -> Hint.PERMISSION
            state.gps == GpsStatus.DISABLED -> Hint.LOCATION_OFF
            else -> null
        }
        if (next == hint) return
        hint = next
        hintGroup.visible(next != null)
        when (next) {
            Hint.PERMISSION -> { hintRow.setSupporting("Needed", Theme.error); hintButton.text = "Allow" }
            Hint.LOCATION_OFF -> { hintRow.setSupporting("Off", Theme.error); hintButton.text = "Turn on" }
            null -> {}
        }
    }

    private fun fixHint() {
        when (hint) {
            Hint.PERMISSION -> host.requestPermission(AppPermission.LOCATION)
            Hint.LOCATION_OFF -> host.openLocationSettings()
            null -> {}
        }
        locatedAt = 0L
    }

    // ---------------------------------------------------------------- detail

    private fun buildDetail() = with(ctx) {
        // The top-bar arrow goes up to the list; back from there behaves as on the list (Host.subPageClosed).
        detailBar.setNavigation("arrow_back", "Back") { closeDetail(); host.subPageClosed() }
        detailBar.addAction("delete", "Delete ride") { confirmDelete() }
        detailScaffold.fab = mapsFab
        mapsFab.tooltipText = "Open this route in Google Maps"

        val routeCard = filledCard(0)
        routeCard.addView(route, LinearLayout.LayoutParams(MATCH, WRAP))
        legend.setPadding(dp(20), dp(20), dp(20), dp(20))
        routeCard.addView(legend)
        detailPage.add(routeCard, Spacing.S)

        val grid = EqualGrid(this, 2)
        listOf(tileDistance, tileDuration, tilePeak, tileAverage, tileUsed, tileEfficiency).forEach { grid.addView(it) }
        tileDistance.onClick { explainTile(tileDistance) }
        tileDuration.onClick { explainTile(tileDuration) }
        tilePeak.onClick { explainTile(tilePeak) }
        tileAverage.onClick { explainTile(tileAverage) }
        tileUsed.onClick { explainTile(tileUsed) }
        tileEfficiency.onClick { explainTile(tileEfficiency) }
        detailPage.add(grid)

        val speedCard = filledCard()
        val header = row()
        header.addView(textView("Speed", Typography.titleMedium, Theme.onSurface).apply { isAccessibilityHeading = true }, weighted())
        header.addView(movingStat, LinearLayout.LayoutParams(WRAP, WRAP))
        speedCard.addView(header)
        speedCard.addView(speedChart, params(Spacing.L))
        detailPage.add(speedCard)
    }

    /** A ride tapped in the list: whatever detail the app once opened here is gone, so back stays on Rides. */
    private fun openFromList(ride: Ride) {
        host.subPageClosed()
        open(ride)
    }

    private fun open(ride: Ride) {
        lookupJob?.cancel()
        detailJob?.cancel()
        deleteSheet?.dismiss()
        openRide = ride
        openPoints = emptyList()
        fillDetail(ride)
        speedChart.showLoading()
        val store = host.store
        val thumb = RouteThumbs.get(store.isDemo, ride.id)
        if (thumb != null && thumb.size >= 2) route.setRoute(thumb, ride) else route.showLoading()
        mapsFab.visible(hasCoordinates(ride))
        mapsFab.setExtended(true, animate = false)
        showDetail()
        detailPage.scrollTo(0, 0)
        detailJob = scope.launch {
            val points = try { withContext(Dispatchers.IO) { store.ridePoints(ride.id) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { RideLive.log("Rides: could not load route of ride ${ride.id} (${e.javaClass.simpleName})"); null }
            if (points == null) {
                route.showMessage("No route")
                speedChart.showEmpty("No speed data", "speed")
                host.showSnackbar("Couldn't load this ride's route")
                return@launch
            }
            openPoints = points
            route.setRoute(points, ride)
            route.tooltipText = if (route.estimated) "Only the start and end were recorded" else null
            mapsFab.visible(points.size >= 2 || hasCoordinates(ride))
            showSpeed(ride, points)
        }
    }

    private fun showDetail() {
        if (detailGate.visibility == View.VISIBLE) return
        Motion.fadeThrough(listGate, detailGate)
        // The second tap of a double tap (100-300 ms later) must not land on whatever is now under the finger.
        detailGate.open(ClickGuard.WINDOW_MS)
    }

    private fun fillDetail(ride: Ride) {
        // Receipt header: when (R3); the places live in the from/to block under the map.
        detailBar.title = RideTitle.dateTime(ctx, ride)
        detailBar.subtitle = null
        // Place names only; "Start" / "End" until the Geocoder names them (never raw coordinates).
        val fromPlace = ride.startPlace?.trim()?.ifEmpty { null } ?: "Start"
        val toPlace = ride.endPlace?.trim()?.ifEmpty { null } ?: "End"
        legend.set(fromPlace, Fmt.clock(ctx, ride.start), toPlace, Fmt.clock(ctx, ride.end))
        route.contentDescription = if (fromPlace == "Start" && toPlace == "End") "Route, ${Amounts.distance(ride.distanceM).text}"
            else "Route from $fromPlace to $toPlace, ${Amounts.distance(ride.distanceM).text}"
        route.tooltipText = null
        tileDistance.setValue(Amounts.distance(ride.distanceM))
        tileDuration.setValue(Amounts.duration(ride.durationS))
        tilePeak.setValue(Amounts.speed(ride.peakKmh))
        tileAverage.setValue(Amounts.speed(ride.avgKmh))
        val from = ride.socStart
        val to = ride.socEnd
        val used = ride.batteryUsed
        tileUsed.setValue(when {
            used == null -> Amount(Amounts.NONE)
            used >= 0 -> Amounts.percent(used)
            else -> Amounts.percent(-used).let { Amount("+${it.value}", it.unit) }
        })
        tileUsed.tooltipText = if (from != null && to != null) "${percentText(from)} % → ${percentText(to)} %" else "Battery used"
        tileEfficiency.setValue(Amounts.efficiency(ride.kmPerPercent))
        movingStat.set(Amounts.duration(ride.movingS))
    }

    // ---------------------------------------------------------------- tap explains (W2)

    private fun explain(icon: String, title: String, amount: Amount, meaning: String, rows: List<Pair<String, String>> = emptyList()) =
        host.explain(ExplainSheet(icon, title, amount.value, amount.unit.ifEmpty { null }, meaning, rows))

    private fun explainTile(tile: MetricTile) {
        val ride = openRide ?: return
        val start = Fmt.clock(ctx, ride.start)
        val end = Fmt.clock(ctx, ride.end)
        when (tile) {
            tileDistance -> explain("route", "Distance", Amounts.distance(ride.distanceM),
                "From setting off to the last movement, measured by the phone's GPS.",
                listOf("Moving time" to Amounts.duration(ride.movingS).text, "Average speed" to Amounts.speed(ride.avgKmh).text))
            tileDuration -> explain("timer", "Duration", Amounts.duration(ride.durationS),
                "Time from setting off to the last movement.",
                listOf("Moving" to Amounts.duration(ride.movingS).text,
                    "Stopped" to Amounts.duration((ride.durationS - ride.movingS).coerceAtLeast(0)).text,
                    "Start" to start, "End" to end))
            tilePeak -> explain("speed", "Top speed", Amounts.speed(ride.peakKmh),
                "Fastest speed held for three GPS readings in a row; single spikes don't count.",
                listOf("Average speed" to Amounts.speed(ride.avgKmh).text))
            tileAverage -> explain("avg_pace", "Average speed", Amounts.speed(ride.avgKmh),
                "Distance divided by moving time, so stops don't pull it down.",
                listOf("Distance" to Amounts.distance(ride.distanceM).text, "Moving time" to Amounts.duration(ride.movingS).text))
            tileUsed -> {
                val used = ride.batteryUsed
                val amount = when {
                    used == null -> Amount(Amounts.NONE, "%")
                    used >= 0 -> Amounts.percent(used)
                    else -> Amounts.percent(-used).let { Amount("+${it.value}", it.unit) }
                }
                explain("battery_5_bar", "Battery used", amount,
                    if (used == null) "Unknown: the scooter's charge wasn't read at the start and end of this ride."
                    else "Scooter charge at the start minus the charge at the end.",
                    listOfNotNull(ride.socStart?.let { "At start" to Amounts.percent(it).text }, ride.socEnd?.let { "At end" to Amounts.percent(it).text }))
            }
            tileEfficiency -> {
                val model = host.range?.kmPerPercent ?: host.store.cachedKmPerPercent
                val teaches = RangeModel.rideSample(ride.distanceM, ride.socStart, ride.socEnd) != null
                explain("bolt", "Efficiency", Amounts.efficiency(ride.kmPerPercent),
                    if (teaches) "Kilometres this ride covered per 1 % of charge. It refines your range estimate."
                    else "Kilometres this ride covered per 1 % of charge. The range estimate learns only from rides of 3 km or more.",
                    listOf("Range estimate uses" to "${Fmt.decimal(model, 2)} km/%"))
            }
            else -> {}
        }
    }

    private enum class WeekStat { DISTANCE, RIDES, TOP_SPEED }

    private fun explainWeek(focus: WeekStat) {
        val stats = RideMath.stats(weekRides)
        val since = Fmt.date(weekFrom)
        val rows = if (stats.rides == 0) emptyList() else listOfNotNull(
            if (focus != WeekStat.DISTANCE) "Distance" to Amounts.distance(stats.distanceM).text else null,
            if (focus != WeekStat.RIDES) "Rides" to stats.rides.toString() else null,
            "Time riding" to Amounts.duration(stats.durationS).text,
            if (focus != WeekStat.TOP_SPEED) "Top speed" to Amounts.speed(stats.peakKmh).text else null,
            "Average speed" to Amounts.speed(stats.avgKmh).text,
            weekRides.maxByOrNull { it.distanceM }?.takeIf { focus == WeekStat.RIDES }?.let { "Longest" to Amounts.distance(it.distanceM).text }
        )
        when (focus) {
            WeekStat.DISTANCE -> explain("route", "This week", Amounts.distance(stats.distanceM),
                "Distance ridden since $since, measured by the phone's GPS.", rows)
            WeekStat.RIDES -> explain("electric_moped", "Rides this week", Amounts.count(stats.rides),
                "Trips recorded since $since while the scooter was connected.", rows)
            WeekStat.TOP_SPEED -> explain("speed", "Top speed this week",
                if (stats.rides > 0) Amounts.speed(stats.peakKmh) else Amount(Amounts.NONE, "km/h"),
                "Fastest speed held on any ride since $since.", rows)
        }
    }

    private fun MetricTile.setValue(amount: Amount) = setValue(amount.value, amount.unit.ifEmpty { null })

    private fun showSpeed(ride: Ride, points: List<RidePoint>) {
        if (points.size < 2) { speedChart.showEmpty("No speed data", "speed"); return }
        val first = points.first().time
        val end = points.last().time + 1
        val buckets = (points.size / 2).coerceIn(2, SPEED_BUCKETS)
        speedChart.axis = ElapsedAxis(ctx, first)
        speedChart.setData(
            listOf(ChartSeries("Speed", Theme.chart1, RideMath.speedSeries(points, buckets), area = true, strokeDp = 3f)),
            first, end, RideMath.bucketWidth(first, end, buckets),
            emptyText = "No speed data",
            reference = ride.peakKmh.takeIf { it > 0 }, referenceLabel = "Top ${Amounts.speed(ride.peakKmh).text}",
            emptyIcon = "speed"
        )
    }

    private fun closeDetail() {
        detailJob?.cancel()
        lookupJob?.cancel()
        deleteSheet?.dismiss()
        deleteSheet = null
        openRide = null
        openPoints = emptyList()
        route.marker = null
        if (detailGate.visibility == View.VISIBLE) {
            Motion.fadeThrough(detailGate, listGate)
            listGate.open(ClickGuard.WINDOW_MS) // A double tap on back must not open a ride from the list.
        }
    }

    private fun hasCoordinates(ride: Ride) = ride.startLat != 0.0 || ride.startLng != 0.0

    /** Directions in Google Maps from the ride's start to its end (origin + destination only); browser as fallback. */
    private fun openInMaps() {
        val ride = openRide ?: return
        val url = RideMath.routeUrl(openPoints)
            ?: RideMath.directionsUrl(ride.startLat, ride.startLng, ride.endLat, ride.endLng)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        mapsFab.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        try { host.activity.startActivity(Intent(intent).setPackage(MAPS_PACKAGE)) }
        catch (_: ActivityNotFoundException) {
            try { host.activity.startActivity(intent) }
            catch (_: ActivityNotFoundException) { host.showSnackbar("Google Maps isn't installed") }
        }
    }

    private fun confirmDelete() {
        val ride = openRide ?: return
        val activity = host.activity
        if (activity.isFinishing || activity.isDestroyed) return
        deleteSheet?.dismiss()
        val content = ctx.column()
        content.addView(ctx.textView("${RideTitle.of(ride)} · ${RideTitle.dateTime(ctx, ride)}", Typography.bodyMedium, Theme.onSurfaceVariant).apply {
            setPadding(ctx.dp(Spacing.S), 0, ctx.dp(Spacing.S), 0)
        })
        val actions = ctx.row().apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        actions.addView(ctx.actionButton("Cancel", ButtonVariant.TEXT) { deleteSheet?.dismiss() })
        actions.addView(ctx.actionButton("Delete", ButtonVariant.DESTRUCTIVE, "delete") {
            deleteSheet?.dismiss()
            delete(ride)
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = ctx.dp(Spacing.S) })
        content.addView(actions, ctx.params(Spacing.XL))
        val sheet = host.showSheet(content, "Delete ride?")
        deleteSheet = sheet
        sheet?.onDismiss = { if (deleteSheet === sheet) deleteSheet = null }
    }

    private fun delete(ride: Ride) {
        val store = host.store
        scope.launch {
            val deleted = try { withContext(Dispatchers.IO) { store.deleteRide(ride.id) }; true }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { RideLive.log("Rides: could not delete ride ${ride.id} (${e.javaClass.simpleName})"); false }
            if (!deleted) {
                root.performHapticFeedback(HapticFeedbackConstants.REJECT)
                host.showSnackbar("Couldn't delete the ride")
                return@launch
            }
            root.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            RideLive.log("Deleted ride from ${Fmt.date(ride.start)} (${Fmt.distance(ride.distanceM)})")
            RouteThumbs.remove(store.isDemo, ride.id)
            if (openRide?.id == ride.id) { closeDetail(); host.subPageClosed() }
            host.showSnackbar("Ride deleted")
            RideLive.invalidate() // Home's last ride and Insights reload too.
            loadList()
        }
    }

    private companion object {
        /** Ride whose detail was open when the shell was rebuilt or the activity recreated. */
        var restoreId: Long? = null
        var restoreAt = 0L

        /** Calendar week start (locale's first day) for "This week". */
        fun weekStart(now: Long): Long = Calendar.getInstance().apply {
            timeInMillis = localDayStart(now)
            val back = (get(Calendar.DAY_OF_WEEK) - firstDayOfWeek + 7) % 7
            add(Calendar.DAY_OF_MONTH, -back)
        }.timeInMillis
    }
}

// ------------------------------------------------------------------ titles and formatting

/**
 * Ride titles. [of]: short places, areas first ("Lakeside → Greenfield", within one area "Riverside
 * Garden Rd → Lakeside", a loop "Around Hill Rd"; street words abbreviated, see
 * [RideMath.rideTitle]); without place names "Morning ride". [dateTime]: "Mon 16 Nov, 7:06 pm".
 */
object RideTitle {
    fun of(ride: Ride): String = RideMath.rideTitle(ride.startPlace, ride.endPlace) ?: partOfDay(ride.start)

    /** When the ride started: "Mon 16 Nov, 7:06 pm" (the phone's 12/24-hour setting). */
    fun dateTime(context: Context, ride: Ride): String = "${Fmt.date(ride.start)}, ${Fmt.clock(context, ride.start)}"
}

/** Long-press tooltips take every touch; a stat chip explains on tap instead (ripple, 48 dp target via padding). */
private fun StatChip.explains(action: () -> Unit) {
    tooltipText = null
    isClickable = true
    isFocusable = true
    foreground = rippleOver(null, RoundedShapeDrawable(Color.WHITE, dpf(Shapes.CHIP)), Theme.onSurface)
    setOnSingleClick(action)
}

private fun partOfDay(time: Long): String = when (Calendar.getInstance().apply { timeInMillis = time }.get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Morning ride"
    in 12..16 -> "Afternoon ride"
    in 17..20 -> "Evening ride"
    else -> "Night ride"
}

/** "82", "82.5" (no unit). */
private fun percentText(value: Double): String = Amounts.percent(value).value

/** Value + unit sharing a baseline; the pair itself reports the value's baseline to its parent. */
private fun Context.valueUnit(value: TextView, unit: TextView): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    isBaselineAligned = true
    addView(value, LinearLayout.LayoutParams(WRAP, WRAP))
    addView(unit, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(Spacing.XS) })
    baselineAlignedChildIndex = 0
}

/** Pulse 0.55..1 for skeletons (1 with animations off). */
private fun skeletonPulse(origin: Long): Float {
    if (!Motion.enabled()) return 1f
    val t = ((SystemClock.uptimeMillis() - origin) % 1_400L) / 1_400f
    return 0.55f + 0.45f * (0.5f + 0.5f * sin(t * 2f * PI.toFloat()))
}

/**
 * Thinned route tracks for list thumbnails, by history and ride id (main thread only; rides never change
 * once finished). [demo] = the track came from the demo history ([TelemetryStore.isDemo]): its ride ids
 * (1…14) overlap the rider's own, so one history's routes never show on the other's rides.
 */
private object RouteThumbs {
    private val cache = object : LinkedHashMap<Pair<Boolean, Long>, List<RidePoint>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<Boolean, Long>, List<RidePoint>>?): Boolean = size > THUMB_CACHE
    }

    fun get(demo: Boolean, id: Long): List<RidePoint>? = cache[demo to id]
    fun put(demo: Boolean, id: Long, points: List<RidePoint>) { cache[demo to id] = points }
    fun remove(demo: Boolean, id: Long) { cache.remove(demo to id) }
}

// ------------------------------------------------------------------ receipt legend

/**
 * The receipt's from/to block (DESIGN Addendum D): start (trip_origin) and end (flag, primary) rows
 * with the place (Body L, up to two lines) and its time, the two markers joined by a dotted rail
 * like a trip receipt. Each row is announced as "Start, Riverside Garden Road, 7:06 pm".
 */
private class TripLegend(context: Context) : LinearLayout(context) {
    private val startIcon = IconView(context, "trip_origin", tint = Theme.onSurfaceVariant, iconSizeDp = 20)
    private val endIcon = IconView(context, "flag", filled = true, tint = Theme.primary, iconSizeDp = 20)
    private val startPlace = place()
    private val endPlace = place()
    private val startTime = time()
    private val endTime = time()
    private val startRow = legendRow(startIcon, startPlace, startTime)
    private val endRow = legendRow(endIcon, endPlace, endTime)
    private val rail = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dpf(2.5f); strokeCap = Paint.Cap.ROUND
        color = Theme.outlineVariant
        pathEffect = DashPathEffect(floatArrayOf(0.01f, dpf(5f)), 0f) // round caps turn the dashes into dots
    }

    init {
        orientation = VERTICAL
        addView(startRow, LayoutParams(MATCH, WRAP))
        addView(endRow, LayoutParams(MATCH, WRAP).apply { topMargin = dp(Spacing.L) })
    }

    fun set(from: String, fromTime: String, to: String, toTime: String) {
        startPlace.show(from); startTime.show(fromTime)
        endPlace.show(to); endTime.show(toTime)
        startRow.contentDescription = "Start, $from, $fromTime"
        endRow.contentDescription = "End, $to, $toTime"
    }

    private fun place(): TextView = context.textView("", Typography.bodyLarge, Theme.onSurface).apply {
        maxLines = 2; ellipsize = TextUtils.TruncateAt.END
    }

    private fun time(): TextView = context.singleLine("", Typography.labelLarge, Theme.onSurfaceVariant).tabular()

    /** Icon and time sit on the place's first line (2 dp optical offset for 24 dp Body L lines). */
    private fun legendRow(icon: IconView, place: TextView, time: TextView): LinearLayout = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.TOP
        addView(icon, LayoutParams(dp(20), dp(20)).apply { topMargin = dp(2); marginEnd = dp(Spacing.L) })
        addView(place, LayoutParams(0, WRAP, 1f))
        addView(time, LayoutParams(WRAP, WRAP).apply { marginStart = dp(Spacing.M); topMargin = dp(2) })
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        for (child in listOf(icon, place, time)) child.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val x = startRow.left + startIcon.left + startIcon.width / 2f
        val top = startRow.top + startIcon.bottom + dpf(6f)
        val bottom = endRow.top + endIcon.top - dpf(6f)
        if (bottom > top) canvas.drawLine(x, top, x, bottom, rail)
    }
}

// ------------------------------------------------------------------ list rows

/**
 * One ride in a day's [ListGroup]: 64 dp route thumbnail, title (Title M, up to two lines), time range
 * (Body M) and one line of icon stats (distance, time, top speed; battery used lives in the detail).
 * The whole row is the touch target (opens the ride); pressing springs it to 97 % with a ripple.
 */
private class RideRow(context: Context, ride: Ride, title: String, onOpen: () -> Unit) : LinearLayout(context), GroupedRow {
    private val container = RoundedShapeDrawable()
    private val mask = RoundedShapeDrawable(Color.WHITE)
    private var press: Spring? = null
    val thumbnail = RouteThumbnail(context)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = rippleOver(container, mask, Theme.onSurface)
        setPaddingRelative(dp(Spacing.M), dp(Spacing.M), dp(Spacing.L), dp(Spacing.M))
        minimumHeight = dp(88)
        addView(thumbnail, LayoutParams(dp(64), dp(64)))
        val clock = "${Fmt.clock(context, ride.start)} – ${Fmt.clock(context, ride.end)}"
        val texts = context.column().apply {
            addView(context.textView(title, Typography.titleMedium, Theme.onSurface).apply {
                maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            })
            addView(context.singleLine(clock, Typography.bodyMedium, Theme.onSurfaceVariant).tabular())
            // One line, never wrapping into uneven rows (R2); no tooltips: the whole row is the touch target.
            val stats = StatFlow(context, Spacing.M, Spacing.XS, maxLines = 1)
            stats.addView(InlineStat(context, "route", "Distance", Amounts.distance(ride.distanceM), tooltip = false))
            stats.addView(InlineStat(context, "timer", "Time", Amounts.duration(ride.durationS), tooltip = false))
            stats.addView(InlineStat(context, "speed", "Top speed", Amounts.speed(ride.peakKmh), tooltip = false))
            addView(stats, context.params(6))
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        addView(texts, LayoutParams(0, WRAP, 1f).apply { marginStart = dp(Spacing.L) })
        isClickable = true
        isFocusable = true
        setOnSingleClick(onOpen)
        contentDescription = buildString {
            append(title).append(", ").append(clock.replace("–", "to")).append(". ")
            append(Amounts.distance(ride.distanceM).text).append(", ").append(Amounts.duration(ride.durationS).text)
            append(", top speed ").append(Amounts.speed(ride.peakKmh).text)
        }
        press = Spring(1f, Motion.STIFFNESS_FAST, Motion.DAMPING_FAST, 0.0005f) { s -> scaleX = s; scaleY = s }
    }

    override fun setGroupShape(color: Int, topRadius: Float, bottomRadius: Float) {
        container.color = color
        container.setRadii(topRadius, topRadius, bottomRadius, bottomRadius)
        mask.setRadii(topRadius, topRadius, bottomRadius, bottomRadius)
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        press?.animateTo(if (isPressed) 0.97f else 1f)
    }
}

/** Loading placeholder shaped like a [RideRow] (pulsing blocks, no text). */
private class SkeletonRow(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.surfaceContainerHigh }
    private val rect = RectF()
    private val origin = SystemClock.uptimeMillis()

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), resolveSize(dp(94), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.alpha = (255 * skeletonPulse(origin)).roundToInt()
        val left = dpf(Spacing.M.toFloat())
        val top = (height - dpf(64f)) / 2
        rect.set(left, top, left + dpf(64f), top + dpf(64f))
        canvas.drawRoundRect(rect, dpf(Shapes.LARGE), dpf(Shapes.LARGE), paint)
        val textLeft = rect.right + dpf(Spacing.L.toFloat())
        val textWidth = width - textLeft - dpf(Spacing.L.toFloat())
        if (textWidth > 0) {
            val r = dpf(6f)
            rect.set(textLeft, top + dpf(4f), textLeft + textWidth * 0.62f, top + dpf(18f)); canvas.drawRoundRect(rect, r, r, paint)
            rect.set(textLeft, top + dpf(27f), textLeft + textWidth * 0.36f, top + dpf(39f)); canvas.drawRoundRect(rect, r, r, paint)
            rect.set(textLeft, top + dpf(50f), textLeft + textWidth * 0.8f, top + dpf(62f)); canvas.drawRoundRect(rect, r, r, paint)
        }
        if (Motion.enabled() && isShown) postInvalidateOnAnimation()
    }
}

/**
 * 64 dp rounded route thumbnail: the thinned track in primary (start dot onSurfaceVariant, end dot
 * primary), a dashed straight line when only the ends are known, a route glyph without either;
 * pulses while loading. Decorative (the row describes the ride).
 */
private class RouteThumbnail(context: Context) : View(context) {
    private var points: List<RidePoint>? = null
    private var straight = false
    private var xy = FloatArray(0)
    private val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.surfaceContainerHigh }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dpf(2.25f); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        color = Theme.primary
    }
    private val dashed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dpf(2f); strokeCap = Paint.Cap.ROUND; color = Theme.primary
        pathEffect = DashPathEffect(floatArrayOf(dpf(3f), dpf(4f)), 0f)
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()
    private val glyph: Drawable = Icons.drawable(context, "route", tint = Theme.onSurfaceVariant)
    private val origin = SystemClock.uptimeMillis()

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun setTrack(track: List<RidePoint>, ride: Ride) {
        when {
            track.size >= 2 -> { points = track; straight = false }
            ride.startLat != 0.0 || ride.startLng != 0.0 -> {
                points = listOf(
                    RidePoint(ride.start, ride.startLat, ride.startLng, 0.0, 0.0, 0.0),
                    RidePoint(ride.end, ride.endLat, ride.endLng, 0.0, ride.distanceM, 0.0)
                )
                straight = true
            }
            else -> { points = emptyList(); straight = false }
        }
        project()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(dp(64), widthMeasureSpec), resolveSize(dp(64), heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val size = dp(24)
        glyph.setBounds((w - size) / 2, (h - size) / 2, (w + size) / 2, (h + size) / 2)
        project()
    }

    private fun project() {
        path.reset()
        val track = points
        if (track.isNullOrEmpty() || width == 0 || height == 0) { xy = FloatArray(0); return }
        xy = RideMath.project(track, width.toFloat(), height.toFloat(), dpf(11f))
        for (i in track.indices) if (i == 0) path.moveTo(xy[0], xy[1]) else path.lineTo(xy[2 * i], xy[2 * i + 1])
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        val radius = dpf(Shapes.LARGE)
        val track = points
        if (track == null) {
            panel.alpha = (255 * skeletonPulse(origin)).roundToInt()
            canvas.drawRoundRect(rect, radius, radius, panel)
            if (Motion.enabled() && isShown) postInvalidateOnAnimation()
            return
        }
        panel.alpha = 255
        canvas.drawRoundRect(rect, radius, radius, panel)
        if (xy.size < 4) { glyph.draw(canvas); return }
        if (straight) canvas.drawLine(xy[0], xy[1], xy[xy.size - 2], xy[xy.size - 1], dashed) else canvas.drawPath(path, line)
        dot.color = Theme.onSurfaceVariant
        canvas.drawCircle(xy[0], xy[1], dpf(3f), dot)
        dot.color = Theme.surfaceContainerHigh
        canvas.drawCircle(xy[xy.size - 2], xy[xy.size - 1], dpf(4.5f), dot)
        dot.color = Theme.primary
        canvas.drawCircle(xy[xy.size - 2], xy[xy.size - 1], dpf(3.5f), dot)
    }
}

// ------------------------------------------------------------------ route canvas

/**
 * A ride's track on a "map paper" canvas (equirectangular, north up, aspect kept): casing + primary
 * track, start = trip_origin on a neutral disc, end = flag on a primary disc, a scale bar, a north
 * arrow and an optional [marker] at a time (scrubbed on the speed chart, tertiary dot). With only the
 * ride's start and end it draws a dashed straight line ([estimated]). Pulses while loading; an icon
 * and one line when there is nothing to draw.
 */
class RouteView(context: Context) : View(context) {
    private enum class State { LOADING, EMPTY, ROUTE }

    private var state = State.LOADING
    private var points: List<RidePoint> = emptyList()
    private var xy = FloatArray(0)
    private var message = ""
    private var scaleM = 0
    private var scalePx = 0f
    private var scaleText = ""
    /** True when only the start and end are known (the line is a straight estimate). */
    var estimated = false; private set
    /** Time to mark on the route; null hides the marker. */
    var marker: Long? = null
        set(next) { if (field != next) { field = next; invalidate() } }

    private val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.surfaceContainerLow }
    private val gridDots = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = alpha(Theme.outlineVariant, 0.6f); strokeWidth = dpf(2f); strokeCap = Paint.Cap.ROUND
    }
    private var gridPoints = FloatArray(0)
    private val casing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dpf(10f); color = alpha(Theme.primary, 0.22f)
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dpf(4f); color = Theme.primary
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val dashed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dpf(3f); color = Theme.primary; strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(dpf(6f), dpf(7f)), 0f)
    }
    private val skeleton = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dpf(6f); color = Theme.surfaceContainerHighest
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).applyStyle(context, Typography.labelSmall).tabular().apply { color = Theme.onSurfaceVariant }
    private val messageText = Paint(Paint.ANTI_ALIAS_FLAG).applyStyle(context, Typography.labelLarge).apply {
        color = Theme.onSurfaceVariant; textAlign = Paint.Align.CENTER
    }
    private val scale = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dpf(1.5f); color = Theme.onSurfaceVariant; strokeCap = Paint.Cap.ROUND
    }
    private val path = Path()
    private val skeletonPath = Path()
    private val north = Path()
    private val startIcon: Drawable = Icons.drawable(context, "trip_origin", tint = Theme.onSurface)
    private val endIcon: Drawable = Icons.drawable(context, "flag", filled = true, tint = Theme.onPrimary)
    private val emptyIcon: Drawable = Icons.drawable(context, "route", tint = Theme.onSurfaceVariant)
    private val origin = SystemClock.uptimeMillis()

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }

    fun showLoading() = set(emptyList(), false, State.LOADING, "")

    /** Nothing to draw: the route glyph and one short line (e.g. "No route"). */
    fun showMessage(text: String) = set(emptyList(), false, State.EMPTY, text)

    /** Draws [points] (thinned for drawing); falls back to [ride]'s start and end when there is no track. */
    fun setRoute(points: List<RidePoint>, ride: Ride) = when {
        points.size >= 2 -> set(RideMath.thin(points, MAX_POINTS), false, State.ROUTE, "")
        ride.startLat != 0.0 || ride.startLng != 0.0 -> set(listOf(
            RidePoint(ride.start, ride.startLat, ride.startLng, 0.0, 0.0, 0.0),
            RidePoint(ride.end, ride.endLat, ride.endLng, 0.0, ride.distanceM, 0.0)
        ), true, State.ROUTE, "")
        else -> set(emptyList(), false, State.EMPTY, "No route")
    }

    private fun set(next: List<RidePoint>, estimate: Boolean, nextState: State, text: String) {
        points = next
        estimated = estimate
        state = nextState
        message = text
        marker = null
        project()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = getDefaultSize(suggestedMinimumWidth, widthMeasureSpec)
        val wanted = (width * 0.72f).toInt().coerceIn(dp(220), dp(380))
        setMeasuredDimension(width, resolveSize(wanted, heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Dot grid ("map paper") and the loading squiggle, built once per size.
        val step = dpf(24f)
        val cols = max(0, (w / step).toInt())
        val rowsCount = max(0, (h / step).toInt())
        gridPoints = FloatArray(cols * rowsCount * 2)
        var k = 0
        for (c in 0 until cols) for (r in 0 until rowsCount) {
            gridPoints[k++] = step / 2 + c * step
            gridPoints[k++] = step / 2 + r * step
        }
        skeletonPath.reset()
        val steps = 40
        for (i in 0..steps) {
            val f = i / steps.toFloat()
            val px = w * (0.18f + 0.64f * f)
            val py = h * (0.62f - 0.24f * f) + h * 0.1f * sin(f * 3f * PI.toFloat())
            if (i == 0) skeletonPath.moveTo(px, py) else skeletonPath.lineTo(px, py)
        }
        val size = dp(24)
        emptyIcon.setBounds(w / 2 - size / 2, h / 2 - dp(14) - size / 2, w / 2 + size / 2, h / 2 - dp(14) + size / 2)
        project()
    }

    private fun project() {
        path.reset()
        scaleM = 0
        if (width == 0 || height == 0 || points.isEmpty()) { xy = FloatArray(0); return }
        xy = RideMath.project(points, width.toFloat(), height.toFloat(), dpf(40f))
        for (i in points.indices) if (i == 0) path.moveTo(xy[0], xy[1]) else path.lineTo(xy[2 * i], xy[2 * i + 1])
        val iconSize = dp(18)
        placeIcon(startIcon, xy[0], xy[1], iconSize)
        placeIcon(endIcon, xy[xy.size - 2], xy[xy.size - 1], iconSize)
        // Metres per pixel along the longer side of the drawn track, for the scale bar.
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in points.indices) {
            minX = minOf(minX, xy[2 * i]); maxX = maxOf(maxX, xy[2 * i])
            minY = minOf(minY, xy[2 * i + 1]); maxY = maxOf(maxY, xy[2 * i + 1])
        }
        val minLat = points.minOf { it.lat }; val maxLat = points.maxOf { it.lat }
        val minLng = points.minOf { it.lng }; val maxLng = points.maxOf { it.lng }
        val metresPerPx = when {
            maxX - minX >= maxY - minY && maxX - minX > 1 ->
                RideMath.haversineM((minLat + maxLat) / 2, minLng, (minLat + maxLat) / 2, maxLng) / (maxX - minX)
            maxY - minY > 1 -> RideMath.haversineM(minLat, minLng, maxLat, minLng) / (maxY - minY)
            else -> 0.0
        }
        if (metresPerPx <= 0) return
        val target = width * 0.25 * metresPerPx
        scaleM = SCALES.lastOrNull { it <= target } ?: SCALES.first()
        scalePx = (scaleM / metresPerPx).toFloat()
        scaleText = if (scaleM >= 1000) "${scaleM / 1000} km" else "$scaleM m"
    }

    private fun placeIcon(icon: Drawable, cx: Float, cy: Float, size: Int) {
        val l = (cx - size / 2f).roundToInt(); val t = (cy - size / 2f).roundToInt()
        icon.setBounds(l, t, l + size, t + size)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, panel)
        canvas.drawPoints(gridPoints, gridDots)

        when (state) {
            State.LOADING -> {
                skeleton.alpha = (255 * skeletonPulse(origin)).roundToInt()
                canvas.drawPath(skeletonPath, skeleton)
                if (Motion.enabled() && isShown) postInvalidateOnAnimation()
                return
            }
            State.EMPTY -> {
                disc.color = Theme.surfaceContainerHigh
                canvas.drawCircle(w / 2, h / 2 - dpf(14f), dpf(24f), disc)
                emptyIcon.draw(canvas)
                canvas.drawText(message, w / 2, h / 2 + dpf(32f) + messageText.textSize * 0.4f, messageText)
                return
            }
            State.ROUTE -> {}
        }
        if (xy.size < 4) return
        val last = xy.size - 2
        if (estimated) canvas.drawLine(xy[0], xy[1], xy[last], xy[last + 1], dashed)
        else { canvas.drawPath(path, casing); canvas.drawPath(path, track) }

        val radius = dpf(15f)
        disc.color = Theme.surfaceContainerHighest
        canvas.drawCircle(xy[0], xy[1], radius, disc)
        startIcon.draw(canvas)
        disc.color = Theme.primary
        canvas.drawCircle(xy[last], xy[last + 1], radius, disc)
        endIcon.draw(canvas)

        marker?.let { time ->
            val i = nearestIndex(time)
            disc.color = Theme.surfaceContainerLow; canvas.drawCircle(xy[2 * i], xy[2 * i + 1], dpf(9f), disc)
            disc.color = Theme.tertiary; canvas.drawCircle(xy[2 * i], xy[2 * i + 1], dpf(6.5f), disc)
        }

        if (scaleM > 0 && !estimated) {
            val left = dpf(16f)
            val base = h - dpf(16f)
            val tick = dpf(4f)
            canvas.drawLine(left, base, left + scalePx, base, scale)
            canvas.drawLine(left, base - tick, left, base, scale)
            canvas.drawLine(left + scalePx, base - tick, left + scalePx, base, scale)
            text.textAlign = Paint.Align.LEFT
            canvas.drawText(scaleText, left, base - tick - dpf(6f), text)
        }

        // North is up.
        text.textAlign = Paint.Align.CENTER
        val nx = w - dpf(20f)
        canvas.drawText("N", nx, dpf(34f), text)
        north.rewind()
        north.moveTo(nx, dpf(12f)); north.lineTo(nx - dpf(4.5f), dpf(20f)); north.lineTo(nx + dpf(4.5f), dpf(20f)); north.close()
        disc.color = Theme.onSurfaceVariant
        canvas.drawPath(north, disc)
    }

    /** Index of the track point closest in time (points are in time order). */
    private fun nearestIndex(time: Long): Int {
        val found = points.binarySearch { it.time.compareTo(time) }
        if (found >= 0) return found
        val after = (-found - 1).coerceAtMost(points.size - 1)
        val before = (after - 1).coerceAtLeast(0)
        return if (time - points[before].time <= points[after].time - time) before else after
    }

    private companion object {
        const val MAX_POINTS = 1500
        val SCALES = intArrayOf(10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10_000, 20_000, 50_000, 100_000)
    }
}
