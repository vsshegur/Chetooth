package dev.codesurgen.ruta

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.view.Gravity
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
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToLong

/** Charts refresh at most this often while the tab stays open (readings arrive every 30 s). */
private const val REFRESH_MS = 120_000L
private const val SESSIONS_SHOWN = 5
private const val MAX_RIDES = 5_000
private const val MAX_SESSIONS = 200
private const val WEEK_MS = 7 * 86_400_000L
/** Lowest believable state of health (POLISH D1): anything below is a BMS glitch, never drawn. */
private const val MIN_HEALTH = 50.0

/**
 * Insights tab: one period (24h / 7d / 30d connected button group) for everything. Until the rider
 * picks one, the period is the smallest that shows the data well: 24 h while everything recorded is
 * from the last day (POLISH I1), else 7 d. A container-less summary (distance, rides, efficiency,
 * charges), then the hero battery card, distance per hour/day, range trend, temperature, compact
 * health (90–100 % window unless a pack is lower) and balance, and the charging sessions list.
 * Every number explains itself on tap (bottom sheet via [Host.explain]); nothing here navigates.
 * Efficiency is the value the range estimate uses (learned km per 1 %, "est." until a ride of 3 km or
 * more taught it), never a raw period average that would contradict the range. Queries run on
 * Dispatchers.IO; data reloads when the period changes, when a ride or charging session is saved and
 * every two minutes while visible. Loading = skeletons; empty = icon + one short line.
 */
class InsightsScreen(private val host: Host) : Screen {
    private enum class Mode { FIRST, SWITCH, SILENT }

    private val ctx = host.context
    private val scope = Ui.scope()
    private val bar = TopAppBar(ctx, large = true).apply { title = "Insights" }
    private val scaffold = Scaffold(ctx, bar)
    override val view: View = scaffold
    private val page: ScrollPage get() = scaffold.page

    private var range = lastRange ?: ChartRange.WEEK
    /** The rider has not picked a period: loads choose one from the data (I1). */
    private var autoRange = lastRange == null
    private var job: Job? = null
    private var loaded = false
    private var loadedAt = 0L
    /** Revision of the last load started; RideLive fires every second, so compare with this. */
    private var requestedRevision = -1
    private var shown = false
    private var snapshot: Snapshot? = null
    private var sessions: List<ChargeSession> = emptyList()
    private var allSessions = false
    private val listener: () -> Unit = { if (host.ride.revision != requestedRevision) load(Mode.SILENT) }

    private val rangeGroup = ButtonGroup(
        ctx, ChartRange.entries.map { GroupItem(it.label, description = it.spoken) }, selected = range.ordinal
    ) { select(ChartRange.entries[it]) }

    // Summary: container-less columns (neutral icons; values roll), each its own "tap explains" target.
    private val summary = SummaryStrip(ctx)
    private val sumDistance = summary.add("route", "Distance")
    private val sumRides = summary.add("electric_moped", "Rides")
    private val sumEfficiency = summary.add("bolt", "Efficiency")
    private val sumCharges = summary.add("ev_station", "Charges")

    private val distanceChart = BarChart(ctx).apply {
        heightDp = 160
        minSpan = 1.0
        valueFormat = { Fmt.distance(it * 1000) }
        emptySlotText = "No rides"
    }
    // The hero: taller than the rest.
    private val batteryChart = LineChart(ctx).apply {
        heightDp = 240
        fixedRange = 0.0..100.0
        axisUnit = "%"
        valueFormat = ::percentText
    }
    private val rangeChart = LineChart(ctx).apply {
        minSpan = 10.0
        limits = 0.0..400.0
        valueFormat = { "${floor(it).toInt()} km" }
    }
    private val temperatureChart = LineChart(ctx).apply {
        minSpan = 4.0
        axisUnit = "°"
        valueFormat = { "${Fmt.decimal(it)} °C" }
    }
    private val healthChart = LineChart(ctx).apply {
        heightDp = 140 // secondary: compact
        fixedRange = 90.0..100.0 // set per data in show()
        yStep = 5.0
        axisUnit = "%"
        valueFormat = ::percentText
    }
    private val balanceChart = LineChart(ctx).apply {
        heightDp = 140 // secondary: compact
        symmetric = true
        signed = true
        minSpan = 4.0
        axisUnit = "%"
        valueFormat = ::balanceText
    }
    private val lineCharts = listOf(batteryChart, rangeChart, temperatureChart, healthChart, balanceChart)
    private val charts = listOf<TimeChart>(distanceChart) + lineCharts

    private val ridingTime = InlineStat(ctx, "timer", "Time riding", tooltip = false)
    private val ridingTop = InlineStat(ctx, "speed", "Top speed", tooltip = false)
    private val batteryCard = ChartCard("Battery", batteryChart, hero = true) { explainBattery() }
    private val distanceCard = ChartCard("Distance", distanceChart) { explainDistance() }
    private val rangeCard = ChartCard("Range", rangeChart) { explainRange() }
    private val temperatureCard = ChartCard("Temperature", temperatureChart) { explainTemperature() }
    private val healthCard = ChartCard("Health", healthChart) { explainHealth() }
    private val balanceCard = ChartCard("Balance", balanceChart) { explainBalance() }

    private val sessionsCount = InlineStat(ctx, "ev_station", "Charging sessions", tooltip = false)
    private val sessionsAdded = InlineStat(ctx, "bolt", "Charge added", tooltip = false)
    private val sessionsGroup = ListGroup(ctx)
    private val sessionsToggle = ctx.actionButton("Show all", ButtonVariant.TEXT) { allSessions = !allSessions; renderSessions() }
    private val chargingHeader = ctx.row()

    // Nothing recorded in the period: one empty state under the period buttons instead of seven empty blocks (A6).
    private val emptyConnect = ctx.tonalButton("Connect", "electric_moped") { host.connect() }
    private val emptyView = ctx.emptyState("insights", "", null, emptyConnect)
    private val emptyTitle: TextView? = (0 until emptyView.childCount).map { emptyView.getChildAt(it) }.filterIsInstance<TextView>().firstOrNull()
    private var emptyShown = false

    init {
        build()
    }

    // ---------------------------------------------------------------- Screen

    override fun onShow() {
        shown = true
        RideLive.addListener(listener)
        load(if (loaded) Mode.SILENT else Mode.FIRST)
    }

    override fun onHide() {
        shown = false
        RideLive.removeListener(listener)
    }

    override fun render(now: Long) {
        if (shown && loadedAt > 0 && now - loadedAt >= REFRESH_MS && job?.isActive != true) load(Mode.SILENT)
        renderHeaders()
        emptyConnect.visible(host.linkPhase == LinkPhase.IDLE)
    }

    override fun dispose() {
        RideLive.removeListener(listener)
        scope.cancel()
    }

    // ---------------------------------------------------------------- layout

    private fun build() = with(ctx) {
        page.add(rangeGroup, Spacing.S)
        emptyView.visibility = View.GONE
        page.add(emptyView, Spacing.XL)
        // Columns reach 8 dp into the margins so their ripples have room while the content stays aligned.
        page.column.addView(summary, LinearLayout.LayoutParams(MATCH, WRAP).apply {
            topMargin = dp(Spacing.M); marginStart = -dp(Spacing.S); marginEnd = -dp(Spacing.S)
        })
        sumDistance.onTap { explainSummary(sumDistance) }
        sumRides.onTap { explainSummary(sumRides) }
        sumEfficiency.onTap { explainEfficiency() }
        sumCharges.onTap { explainSummary(sumCharges) }

        distanceCard.setTrailing(StatFlow(this, Spacing.M, Spacing.XS, maxLines = 1).apply { addView(ridingTime); addView(ridingTop) })
        // One hero (battery, big value + taller chart) first; health and balance are compact.
        listOf(batteryCard, distanceCard, rangeCard, temperatureCard, healthCard, balanceCard).forEach { page.add(it.card) }
        // Long periods: points closer than 4 dp merge; a few hours on a 7-day axis draw as dots.
        lineCharts.forEach { it.minSpacingDp = 4f }

        val header = chargingHeader
        header.addView(sectionHeader("Charging"), weighted())
        val totals = StatFlow(this, Spacing.M, Spacing.XS, maxLines = 1).apply {
            addView(sessionsCount); addView(sessionsAdded)
            setPadding(0, 0, dp(Spacing.L), 0)
        }
        header.addView(totals, LinearLayout.LayoutParams(WRAP, WRAP))
        page.add(header, Spacing.XL)
        page.add(sessionsGroup, Spacing.XS)
        sessionsToggle.visibility = View.GONE
        page.column.addView(sessionsToggle, LinearLayout.LayoutParams(WRAP, WRAP).apply {
            gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(Spacing.S)
        })
        charts.forEach { it.showLoading() }
        summary.loading = true
    }

    private fun select(next: ChartRange) {
        autoRange = false
        lastRange = next
        if (next == range) return
        range = next
        allSessions = false
        rangeGroup.chartTick()
        load(Mode.SWITCH)
    }

    // ---------------------------------------------------------------- data

    private class Snapshot(
        val range: ChartRange, val since: Long, val now: Long,
        val charge: Map<Int, List<BucketPoint>>,
        val health: Map<Int, List<BucketPoint>>,
        val temperature: Map<Int, List<BucketPoint>>,
        val rides: List<Ride>,
        val sessions: List<ChargeSession>,
        val kmPerPercent: Double,
        /** Stored rides that taught the range model (≥ 3 km and ≥ 2 % used); 0 = still the default. */
        val learnedFrom: Int
    ) {
        val stats: RideStats by lazy { RideMath.stats(rides) }
        val balance: List<BucketPoint> by lazy {
            val two = charge[2].orEmpty().associateBy { it.time }
            charge[1].orEmpty().mapNotNull { one ->
                two[one.time]?.let { val diff = one.avg - it.avg; BucketPoint(one.time, diff, diff, diff, minOf(one.count, it.count)) }
            }
        }
    }

    /** Storage only: runs on Dispatchers.IO. [auto] first picks the period from the data (I1). */
    private fun query(store: TelemetryStore, wanted: ChartRange, now: Long, auto: Boolean): Snapshot {
        val range = if (auto) fittingRange(store, now) else wanted
        val since = now - range.spanMs
        val allRides = store.rides(MAX_RIDES)
        val efficiency = store.efficiency() // The range model's own value and ride count (I3).
        return Snapshot(
            range, since, now,
            charge = (0..2).associateWith { store.history(Metric.SOC, it, since, range.buckets, now) },
            health = (0..2).associateWith { pack ->
                store.history(Metric.HEALTH, pack, since, range.buckets, now).filter { it.avg >= MIN_HEALTH }
            },
            temperature = store.temperatureTrend(since, range.buckets, now),
            rides = allRides.filter { it.start >= since },
            sessions = store.chargeSessions(MAX_SESSIONS, since),
            kmPerPercent = efficiency.kmPerPercent,
            learnedFrom = efficiency.rides
        )
    }

    /** 24 h when every reading, ride and charge of the last week is from the last day; else 7 d. */
    private fun fittingRange(store: TelemetryStore, now: Long): ChartRange {
        val weekAgo = now - WEEK_MS
        // Seven day-wide buckets: the oldest bucket midpoint is within the last day only if all data is.
        val oldestReading = store.history(Metric.SOC, 0, weekAgo, 7, now).firstOrNull()?.time
        val oldestRide = store.rides(MAX_RIDES, weekAgo).lastOrNull()?.start
        val oldestCharge = store.chargeSessions(MAX_SESSIONS, weekAgo).lastOrNull()?.start
        return if (RideMath.fitsLastDay(listOf(oldestReading, oldestRide, oldestCharge), now)) ChartRange.DAY else ChartRange.WEEK
    }

    private fun load(mode: Mode) {
        val wanted = range
        val auto = autoRange && mode != Mode.SILENT
        val store = host.store
        requestedRevision = host.ride.revision
        job?.cancel()
        if (mode == Mode.FIRST) charts.forEach { it.showLoading() }
        job = scope.launch {
            val now = System.currentTimeMillis()
            val result = try { withContext(Dispatchers.IO) { query(store, wanted, now, auto) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { RideLive.log("Insights: history could not be loaded (${e.javaClass.simpleName}: ${e.message})"); null }
            loadedAt = now
            if (result == null) { if (mode != Mode.SILENT || !loaded) showError(); return@launch }
            loaded = true
            show(result, mode)
        }
    }

    private fun showError() {
        charts.forEach { it.showEmpty("Couldn't load", "error") }
        summary.loading = false
        listOf(sumDistance, sumRides, sumEfficiency, sumCharges).forEach { it.setValue(Amount(Amounts.NONE)); it.stale = true }
        host.showSnackbar("Couldn't load insights", "Retry") { load(Mode.SWITCH) }
    }

    private fun show(s: Snapshot, mode: Mode) {
        if (s.range != range) {
            // An automatic pick landed after the rider chose a period: load theirs instead.
            if (!autoRange) { load(Mode.SWITCH); return }
            range = s.range
            rangeGroup.select(s.range.ordinal)
        }
        snapshot = s
        val animate = mode != Mode.SILENT
        if (animate) charts.forEach { it.animateNextData = true }
        val axis = ClockAxis(ctx, s.range)
        val period = s.range.phrase

        // Summary.
        val stats = s.stats
        sumDistance.setValue(Amounts.distance(stats.distanceM))
        sumRides.setValue(Amounts.count(stats.rides))
        sumCharges.setValue(Amounts.count(s.sessions.size))
        listOf(sumDistance, sumRides, sumCharges).forEach { it.stale = false }
        summary.loading = false

        // Distance per local hour (24 h) or day, by ride start.
        val slotMs = s.range.slotMs
        val slots = s.range.slots(s.now)
        val bars = slots.map { slot -> ValueAt(slot, s.rides.filter { it.start >= slot && it.start < slot + slotMs }.sumOf { it.distanceKm }) }
        distanceChart.axis = axis
        distanceChart.title = "Distance per ${if (s.range == ChartRange.DAY) "hour" else "day"}, $period"
        distanceChart.setData(bars, slots.first(), slots.last() + slotMs, slotMs, "No rides", "route")
        ridingTime.set(if (stats.rides > 0) Amounts.duration(stats.durationS) else Amount(Amounts.NONE))
        ridingTop.set(if (stats.rides > 0) Amounts.speed(stats.peakKmh) else Amount(Amounts.NONE, "km/h"))

        val bucket = s.range.bucketMs
        batteryChart.axis = axis
        batteryChart.title = "Battery, $period"
        batteryChart.setData(packSeries(s.charge, "Total", highlightTotal = true), s.since, s.now, bucket, "No readings", emptyIcon = "battery_alert")

        val rangeSeries = RangeModel.series(s.charge[0].orEmpty(), s.kmPerPercent)
        rangeChart.axis = axis
        rangeChart.title = "Range, estimated, $period"
        rangeChart.setData(
            listOf(ChartSeries("Range", Theme.chart1, rangeSeries, area = true, strokeDp = 3f)),
            s.since, s.now, bucket, "No readings", emptyIcon = "route"
        )

        temperatureChart.axis = axis
        temperatureChart.title = "Temperature, $period"
        temperatureChart.setData(packSeries(s.temperature, "Scooter", highlightTotal = false), s.since, s.now, bucket, "No readings", emptyIcon = "device_thermostat")

        // Health moves by tenths over months: a 90–100 % window, opened downwards only when a pack is lower.
        val lowestHealth = s.health.values.flatten().minOfOrNull { it.avg }
        val healthFloor = lowestHealth?.let { (floor(min(it, 90.0) / 5) * 5).coerceIn(MIN_HEALTH, 90.0) } ?: 90.0
        healthChart.fixedRange = healthFloor..100.0
        healthChart.yStep = if (100 - healthFloor <= 20) 5.0 else 10.0
        healthChart.axis = axis
        healthChart.title = "Health, $period"
        healthChart.setData(packSeries(s.health, "Scooter", highlightTotal = false), s.since, s.now, bucket, "No readings", emptyIcon = "ecg_heart")

        balanceChart.axis = axis
        balanceChart.title = "Balance, front minus rear, $period"
        balanceChart.setData(
            listOf(ChartSeries("Front − rear", Theme.chart2, s.balance, area = true, strokeDp = 3f)),
            s.since, s.now, bucket, "No readings", emptyIcon = "contrast"
        )

        sessions = s.sessions
        renderSessions()
        renderHeaders()
        showEmpty(s)
    }

    /** No reading, ride or charge in the period: hide the summary, charts and sessions; the period buttons stay. */
    private fun showEmpty(s: Snapshot) {
        val empty = s.rides.isEmpty() && s.sessions.isEmpty() &&
            listOf(s.charge, s.health, s.temperature).all { metric -> metric.values.all { it.isEmpty() } }
        emptyTitle?.text = "Nothing recorded in ${s.range.phrase}"
        emptyConnect.visible(host.linkPhase == LinkPhase.IDLE)
        if (empty == emptyShown) return
        emptyShown = empty
        summary.visible(!empty)
        listOf(batteryCard, distanceCard, rangeCard, temperatureCard, healthCard, balanceCard).forEach { it.card.visible(!empty) }
        chargingHeader.visible(!empty)
        sessionsGroup.visible(!empty)
        if (empty) sessionsToggle.visible(false) else renderSessions()
        emptyView.visible(empty)
    }

    /** Header values: the live reading when there is one, else the newest stored bucket. */
    private fun renderHeaders() {
        val s = snapshot ?: return
        val battery = host.battery
        batteryCard.setValue(currentCharge(s)?.let { Amounts.percent(it) })

        val km = currentRangeKm(s)
        val estimated = host.range?.estimated ?: true
        rangeCard.setValue(km?.let { Amount(floor(it).toInt().toString(), "km") }, if (estimated) "est." else null)

        temperatureCard.setValue(currentTemperature(s)?.let { Amounts.temperature(it) })
        healthCard.setValue(currentHealth(s)?.let { Amounts.percent(it) })
        val imbalance = battery.imbalance ?: s.balance.lastOrNull()?.avg?.let { abs(it) }
        balanceCard.setValue(imbalance?.let { Amount("Δ" + Amounts.percent(it).value, "%") })

        // The efficiency the range estimate uses (I3): learned km per 1 %, "est." until taught.
        sumEfficiency.setValue(Amounts.efficiencyModel(efficiency(s)), if (s.learnedFrom == 0) "est." else null)
        sumEfficiency.stale = false
    }

    private fun efficiency(s: Snapshot): Double = host.range?.kmPerPercent ?: RangeModel.clampKmPerPercent(s.kmPerPercent)
    private fun currentCharge(s: Snapshot): Double? = host.battery.total.soc ?: s.charge[0]?.lastOrNull()?.avg
    private fun currentRangeKm(s: Snapshot): Double? =
        host.range?.km ?: RangeModel.series(s.charge[0].orEmpty().takeLast(1), s.kmPerPercent).lastOrNull()?.avg
    private fun currentTemperature(s: Snapshot): Double? = host.battery.total.temperatureC
        ?: s.temperature[0]?.lastOrNull()?.avg
        ?: listOfNotNull(s.temperature[1]?.lastOrNull()?.avg, s.temperature[2]?.lastOrNull()?.avg).maxOrNull()
    private fun currentHealth(s: Snapshot): Double? = host.battery.total.health?.takeIf { it >= MIN_HEALTH } ?: s.health[0]?.lastOrNull()?.avg

    /** Scooter total first (filled with its min–max band when [highlightTotal]), then front and rear. */
    private fun packSeries(data: Map<Int, List<BucketPoint>>, totalName: String, highlightTotal: Boolean): List<ChartSeries> =
        (0..2).mapNotNull { pack ->
            val points = data[pack]?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val name = when (pack) { 0 -> totalName; 1 -> "Front"; else -> "Rear" }
            val color = when (pack) { 0 -> Theme.chart1; 1 -> Theme.chart2; else -> Theme.chart3 }
            ChartSeries(name, color, points, area = pack == 0 && highlightTotal, band = pack == 0 && highlightTotal, strokeDp = if (pack == 0) 3f else 2f)
        }

    // ---------------------------------------------------------------- tap explains (W2)

    private fun explain(icon: String, title: String, amount: Amount?, meaning: String, rows: List<Pair<String, String>> = emptyList(), note: String? = null) {
        host.explain(ExplainSheet(icon, title, amount?.value ?: Amounts.NONE, amount?.unit?.ifEmpty { null }, meaning, rows,
            note = note.takeIf { amount != null && amount.value != Amounts.NONE }))
    }

    /** Front / rear rows from the live snapshot (last known), when the packs reported. */
    private fun packRows(value: (PackSnapshot) -> Double?, format: (Double) -> String): List<Pair<String, String>> {
        val battery = host.battery
        return listOfNotNull(
            value(battery.pack1)?.let { "Front" to format(it) },
            value(battery.pack2)?.let { "Rear" to format(it) }
        )
    }

    private fun explainSummary(cell: SummaryStrip.Cell) {
        val s = snapshot ?: return
        val stats = s.stats
        val period = s.range.phrase
        when (cell) {
            sumDistance -> explain("route", "Distance", Amounts.distance(stats.distanceM), "Distance ridden in $period, measured by the phone's GPS.",
                rideRows(stats, includeDistance = false))
            sumRides -> explain("electric_moped", "Rides", Amounts.count(stats.rides), "Trips recorded in $period while the scooter was connected.",
                rideRows(stats, includeDistance = true))
            sumCharges -> {
                val gained = s.sessions.sumOf { it.gained.coerceAtLeast(0.0) }
                explain("ev_station", "Charges", Amounts.count(s.sessions.size), "Charging sessions recorded in $period while the scooter was connected.",
                    if (s.sessions.isEmpty()) emptyList() else listOfNotNull(
                        "Charge added" to "+${Amounts.percent(gained).text}",
                        "Time charging" to Fmt.duration(s.sessions.sumOf { it.durationS }),
                        s.sessions.firstOrNull()?.let { "Latest" to "${Fmt.date(it.start)}, ${Fmt.clock(ctx, it.start)}" }
                    ))
            }
            else -> {}
        }
    }

    private fun rideRows(stats: RideStats, includeDistance: Boolean): List<Pair<String, String>> {
        if (stats.rides == 0) return emptyList()
        return listOfNotNull(
            if (includeDistance) "Distance" to Amounts.distance(stats.distanceM).text else "Rides" to stats.rides.toString(),
            "Time riding" to Amounts.duration(stats.durationS).text,
            "Top speed" to Amounts.speed(stats.peakKmh).text,
            "Average speed" to Amounts.speed(stats.avgKmh).text
        )
    }

    private fun explainEfficiency() {
        val s = snapshot ?: return
        val k = efficiency(s)
        val learned = s.learnedFrom > 0
        explain("bolt", "Efficiency", Amounts.efficiencyModel(k),
            if (learned) "Kilometres per 1 % of charge, learned from your rides of 3 km or more. The range estimate uses it."
            else "Kilometres per 1 % of charge, estimated. A ride of 3 km or more teaches it your real value.",
            listOfNotNull(
                "Per 1 % of charge" to "${Fmt.decimal(k, 2)} km",
                "Based on" to if (learned) "${s.learnedFrom} ${if (s.learnedFrom == 1) "ride" else "rides"}" else "Typical scooter",
                currentRangeKm(s)?.let { "Range now" to "${floor(it).toInt()} km" }
            ), note = if (learned) null else "est.")
    }

    private fun explainBattery() {
        val s = snapshot ?: return
        explain("battery_5_bar", "Battery", currentCharge(s)?.let { Amounts.percent(it) },
            "Charge of the whole scooter. Front and rear are its two packs.",
            packRows({ it.soc }, { Amounts.percent(it).text }) + listOfNotNull(
                currentRangeKm(s)?.let { "Range" to "${floor(it).toInt()} km" + if (host.range?.estimated != false) " est." else "" }
            ))
    }

    private fun explainDistance() = explainSummary(sumDistance)

    private fun explainRange() {
        val s = snapshot ?: return
        val estimated = host.range?.estimated ?: true
        val k = efficiency(s)
        explain("near_me", "Range", currentRangeKm(s)?.let { Amount(floor(it).toInt().toString(), "km") },
            if (estimated) "Estimated distance left: charge × ${Fmt.decimal(k, 2)} km per 1 %." else "Distance left, as the scooter reports it.",
            listOfNotNull(
                currentCharge(s)?.let { "Charge" to Amounts.percent(it).text },
                "Efficiency" to "${Fmt.decimal(k, 2)} km/%" + if (s.learnedFrom == 0) " est." else ""
            ), note = if (estimated) "est." else null)
    }

    private fun explainTemperature() {
        val s = snapshot ?: return
        explain("device_thermostat", "Temperature", currentTemperature(s)?.let { Amounts.temperature(it) },
            "Battery temperature reported by each pack.",
            packRows({ it.temperatureC }, { Amounts.temperature(it).text }))
    }

    private fun explainHealth() {
        val s = snapshot ?: return
        explain("ecg_heart", "Health", currentHealth(s)?.let { Amounts.percent(it) },
            "How much of its original capacity each pack still holds. It falls slowly over the years.",
            packRows({ it.health?.takeIf { h -> h >= MIN_HEALTH } }, { Amounts.percent(it).text }))
    }

    private fun explainBalance() {
        val s = snapshot ?: return
        val imbalance = host.battery.imbalance ?: s.balance.lastOrNull()?.avg?.let { abs(it) }
        explain("contrast", "Balance", imbalance?.let { Amount("Δ" + Amounts.percent(it).value, "%") },
            "Charge gap between the front and rear packs.",
            packRows({ it.soc }, { Amounts.percent(it).text }))
    }

    // ---------------------------------------------------------------- charging sessions

    private fun renderSessions() {
        sessionsGroup.removeAllViews()
        val gained = sessions.sumOf { it.gained.coerceAtLeast(0.0) }
        sessionsCount.set(Amounts.count(sessions.size))
        sessionsAdded.set(Amount("+${Amounts.percent(gained).value}", "%"))
        sessionsCount.visible(sessions.isNotEmpty())
        sessionsAdded.visible(sessions.isNotEmpty())
        if (sessions.isEmpty()) {
            sessionsGroup.addView(ListItem(ctx, "No sessions", null, "ev_station"))
            sessionsToggle.visible(false)
            return
        }
        val live = host.ride.charging?.start
        val visible = if (allSessions) sessions else sessions.take(SESSIONS_SHOWN)
        visible.forEach { sessionsGroup.addView(sessionRow(it, it.start == live)) }
        sessionsToggle.visible(sessions.size > SESSIONS_SHOWN)
        sessionsToggle.text = if (allSessions) "Show less" else "Show all"
    }

    private fun sessionRow(session: ChargeSession, live: Boolean): View {
        val from = Amounts.percent(session.socStart).value
        val to = Amounts.percent(session.socEnd).text
        val duration = Fmt.duration(session.durationS)
        val supporting = if (live) "Charging · $duration" else "${Fmt.date(session.start)}, ${Fmt.clock(ctx, session.start)} · $duration"
        val item = ListItem(ctx, "$from → $to", supporting, "ev_station")
        if (live) { item.setIcon("bolt", filled = true); item.setLeadingTone(Tone.TERTIARY) }
        // A rate over a few minutes is noise.
        val rate = if (session.durationS >= 600) "${number(session.percentPerHour, 0)} %/h" else null
        item.setTrailingText(rate)
        item.contentDescription = "Charged from $from to $to, $supporting" + (rate?.let { ", $it" } ?: "")
        return item
    }

    // ---------------------------------------------------------------- chart card

    /**
     * Chart card: a header row (title Title M, current value at the end: Title L tabular, unit in
     * onSurfaceVariant, optional "est."; or a custom [setTrailing] view) that explains the value on
     * tap, and the chart below (scrubbing stays the chart's own gesture).
     */
    private inner class ChartCard(name: String, chart: TimeChart, hero: Boolean = false, onExplain: () -> Unit) {
        val card = ctx.filledCard()
        val title: TextView = ctx.textView(name, Typography.titleMedium, Theme.onSurface).apply { isAccessibilityHeading = true }
        private val value = ctx.singleLine(Amounts.NONE, if (hero) Typography.displaySmall.withWeight(500) else Typography.titleLarge, Theme.onSurface).tabular()
        private val unit = ctx.singleLine("", Typography.labelLarge, Theme.onSurfaceVariant)
        private val note = ctx.singleLine("", Typography.labelSmall, Theme.onSurfaceVariant)
        private val valueBlock = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = true
            addView(value, LinearLayout.LayoutParams(WRAP, WRAP))
            addView(unit, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = ctx.dp(Spacing.XS) })
            addView(note, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = ctx.dp(Spacing.XS) })
            baselineAlignedChildIndex = 0
        }
        // Reaches 8 dp into the card padding so the ripple has room; content stays on the card's grid.
        private val header = PressableRow(ctx).apply {
            isBaselineAligned = true
            gravity = Gravity.TOP // LinearLayout aligns baselines only for TOP/BOTTOM gravity.
            setPadding(ctx.dp(Spacing.S), ctx.dp(Spacing.S), ctx.dp(Spacing.S), ctx.dp(Spacing.S))
            minimumHeight = ctx.dp(Spacing.TOUCH)
            addView(title, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(valueBlock, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = ctx.dp(Spacing.M) })
            onTap(onExplain)
        }

        init {
            note.visibility = View.GONE
            for (v in listOf(title, value, unit, note, valueBlock)) v.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            card.addView(header, LinearLayout.LayoutParams(MATCH, WRAP).apply {
                val inset = -ctx.dp(Spacing.S); marginStart = inset; marginEnd = inset; topMargin = inset
            })
            card.addView(chart, ctx.params(Spacing.S))
            describe(null, null)
        }

        /** Current value (null = "—") and an optional small note such as "est.". */
        fun setValue(amount: Amount?, noteText: String? = null) {
            value.roll(amount?.value ?: Amounts.NONE)
            unit.show(amount?.unit.orEmpty())
            unit.visible(!amount?.unit.isNullOrEmpty())
            note.show(noteText.orEmpty())
            note.visible(noteText != null)
            describe(amount, noteText)
        }

        private fun describe(amount: Amount?, noteText: String?) {
            val spoken = "${title.text}: ${amount?.text ?: "no value"}" + (if (noteText == "est.") ", estimated" else "") + ". Double-tap for details"
            if (header.contentDescription != spoken) header.contentDescription = spoken
        }

        /** Replaces the value with [view] (e.g. inline stats), centred on the title. */
        fun setTrailing(view: View) {
            header.removeView(valueBlock)
            header.isBaselineAligned = false
            header.gravity = Gravity.CENTER_VERTICAL
            view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            header.addView(view, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = ctx.dp(Spacing.M) })
        }
    }

    private companion object {
        /** Period the rider picked, kept across tab rebuilds and rotation; null = pick from the data. */
        var lastRange: ChartRange? = null
    }
}

// ------------------------------------------------------------------ summary strip

/**
 * Horizontal row that is one touch target: ripple (16 dp corners) and the 0.97 spring press; its
 * children carry no tooltips (they would take the tap). Announced as one item with the caller's
 * contentDescription.
 */
private class PressableRow(context: Context) : LinearLayout(context) {
    private var press: PressScale? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun onTap(action: () -> Unit) {
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        foreground = rippleOver(null, RoundedShapeDrawable(Color.WHITE, dpf(Shapes.LARGE)), Theme.onSurface)
        setOnSingleClick(action)
        if (press == null) press = PressScale(this)
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        press?.update()
    }
}

/**
 * Insights' period summary (DESIGN A2: typography, not boxes): columns spread edge to edge, each a
 * 20 dp onSurfaceVariant icon, a Title L tabular value with a smaller unit (and "est." when the
 * value is estimated) on one baseline, and a Label M label, start-aligned. Each column is its own
 * target (tap explains it). [loading] shows skeleton blocks until the first data.
 */
private class SummaryStrip(context: Context) : LinearLayout(context) {
    private val cells = ArrayList<Cell>()
    private var pulse: ValueAnimator? = null

    var loading = false
        set(value) { if (field != value) { field = value; cells.forEach { it.loading = value }; updatePulse() } }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.TOP
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); updatePulse() }
    override fun onDetachedFromWindow() { pulse?.cancel(); pulse = null; super.onDetachedFromWindow() }

    /** Skeletons breathe (0.55..1) while loading and visible; still with animations off. */
    private fun updatePulse() {
        val want = loading && isAttachedToWindow && Motion.enabled()
        if (want == (pulse != null)) return
        if (want) {
            pulse = ValueAnimator.ofFloat(1f, 0.55f).apply {
                duration = 700L
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener { a -> val f = a.animatedValue as Float; cells.forEach { it.skeletonAlpha(f) } }
                start()
            }
        } else {
            pulse?.cancel(); pulse = null
            cells.forEach { it.skeletonAlpha(1f) }
        }
    }

    fun add(icon: String, label: String): Cell {
        if (cells.isNotEmpty()) addView(View(context), LayoutParams(0, 1, 1f))
        return Cell(context, icon, label).also { cells += it; addView(it, LayoutParams(WRAP, WRAP)) }
    }

    class Cell(context: Context, icon: String, private val label: String) : LinearLayout(context) {
        private val iconView = IconView(context, icon, tint = Theme.onSurfaceVariant, iconSizeDp = Icons.SMALL)
        private val valueView = context.singleLine(Amounts.NONE, Typography.titleLarge, Theme.onSurface).tabular()
        private val unitView = context.singleLine("", Typography.bodyMedium, Theme.onSurfaceVariant)
        private val noteView = context.singleLine("", Typography.labelSmall, Theme.onSurfaceVariant)
        private val labelView = context.singleLine(label, Typography.labelMedium, Theme.onSurfaceVariant)
        private val pair = LinearLayout(context).apply {
            orientation = HORIZONTAL
            isBaselineAligned = true
            addView(valueView, LayoutParams(WRAP, WRAP))
            addView(unitView, LayoutParams(WRAP, WRAP).apply { marginStart = dp(Spacing.XXS) })
            addView(noteView, LayoutParams(WRAP, WRAP).apply { marginStart = dp(Spacing.XS) })
        }
        private val skeleton = View(context).apply {
            background = RoundedShapeDrawable(Theme.surfaceContainerHigh, dpf(6f))
            visibility = GONE
        }
        private var press: PressScale? = null
        private var amount = Amount(Amounts.NONE)
        private var note: String? = null

        var stale = false
            set(value) { if (field != value) { field = value; valueView.tint(if (value) Theme.onSurfaceVariant else Theme.onSurface); describe() } }

        var loading = false
            set(value) {
                if (field == value) return
                field = value
                skeleton.visible(value)
                pair.visibility = if (value) INVISIBLE else VISIBLE
                describe()
            }

        init {
            orientation = VERTICAL
            setPadding(dp(Spacing.S), dp(Spacing.S), dp(Spacing.S), dp(Spacing.S))
            minimumWidth = dp(Spacing.TOUCH)
            noteView.visibility = GONE
            unitView.visibility = GONE
            addView(iconView, LayoutParams(dp(Icons.SMALL), dp(Icons.SMALL)))
            val slot = FrameLayout(context).apply {
                addView(pair, FrameLayout.LayoutParams(WRAP, WRAP))
                addView(skeleton, FrameLayout.LayoutParams(dp(44), dp(20), Gravity.CENTER_VERTICAL))
                minimumHeight = dp(28)
            }
            addView(slot, LayoutParams(WRAP, WRAP).apply { topMargin = dp(6) })
            addView(labelView, LayoutParams(WRAP, WRAP).apply { topMargin = dp(Spacing.XXS) })
            for (child in listOf(iconView, valueView, unitView, noteView, labelView, pair, slot, skeleton)) child.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            describe()
        }

        internal fun skeletonAlpha(a: Float) { skeleton.alpha = a }

        fun setValue(next: Amount, noteText: String? = null) {
            amount = next
            note = noteText
            valueView.roll(next.value)
            unitView.show(next.unit)
            unitView.visible(next.unit.isNotEmpty())
            noteView.show(noteText.orEmpty())
            noteView.visible(noteText != null)
            describe()
        }

        fun onTap(action: () -> Unit) {
            isClickable = true
            isFocusable = true
            foreground = rippleOver(null, RoundedShapeDrawable(Color.WHITE, dpf(Shapes.LARGE)), Theme.onSurface)
            setOnSingleClick(action)
            if (press == null) press = PressScale(this)
        }

        override fun drawableStateChanged() {
            super.drawableStateChanged()
            press?.update()
        }

        private fun describe() {
            val spoken = when {
                loading -> "$label, loading"
                amount.value == Amounts.NONE -> "$label unknown"
                else -> "$label ${amount.text}" + (if (note == "est.") ", estimated" else "") + (if (stale) ", last known" else "")
            }
            if (contentDescription != spoken) contentDescription = spoken
        }
    }
}

/** "57.4 %" (0.1 point). */
private fun percentText(value: Double): String = "${Fmt.decimal((value * 10).roundToLong() / 10.0)} %"

/** "Front +0.3 %", "Rear +1.2 %", "Level". */
private fun balanceText(value: Double): String = when {
    abs(value) < 0.05 -> "Level"
    value > 0 -> "Front +${Fmt.decimal(value)} %"
    else -> "Rear +${Fmt.decimal(-value)} %"
}
