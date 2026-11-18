package dev.codesurgen.ruta

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.time.LocalDateTime
import java.util.Locale

/**
 * Process-wide navigation state shared by the Maps listener, the scooter link and the UI.
 * Confined to the main thread: calls from the main thread run inline (no extra queue hop between
 * a Maps update and the 0x0303 write), calls from elsewhere are posted.
 *
 * Distances between Maps updates are counted down ([NavCountdown]) from this object's own
 * location feed (GPS + network, 1 s, 0 m, main looper). The feed runs only while a Maps cue is
 * active, [forwarding] is on, someone listens (the scooter link or the UI) and location permission
 * is granted; it is removed when navigation ends, the listener disconnects or [stop] is called.
 * [frame] is exactly what the scooter is sent, so the app's TFT preview should use it.
 *
 * Range check (2.2 F-RANGE, [rangeVerdict]): on every Maps update and countdown step the route left
 * is compared with the range from what this process holds — the latest scooter charge (RideInputs,
 * ≤ 90 s old while the link is READY) or a decoded range, and TelemetryStore's learned km per % — the
 * same number Host.range shows. It runs here, not in the UI, so it works with Ruta in the background
 * and Maps in front (RideService keeps the process and the link alive). [RangeTrip] keeps the
 * hysteresis and posts at most one warning per trip ([Alerts.rangeWarning]; again only when TIGHT
 * worsens to SHORT; never during the demo); everything resets when navigation ends.
 */
object NavBridge {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = linkedSetOf<() -> Unit>()

    var cue: MapsCue? = null; private set
    var raw: String = ""; private set
    var updatedAt = 0L; private set
    var listenerConnected = false; private set
    /** Set when Maps ends navigation; the link sends one exit frame and clears it. */
    var exitPending = false
    /** Diagnostics log (logcat tag Ruta + log file), also when only the listener service runs. */
    var log: (String) -> Unit = { DiagLog.append(it) }
    /** Counts accepted Maps updates (not countdown ticks or rerouting notices). */
    var revision = 0; private set

    /**
     * Directions go to the scooter display (ScooterConnection.navigationEnabled mirrors into this).
     * Off stops the location feed; Maps updates are still parsed for the preview.
     */
    var forwarding = true
        set(value) { if (field != value) { field = value; updateLocation() } }

    /** True while the location feed is counting distances down. */
    val counting: Boolean get() = locationOn

    private val countdown = NavCountdown()
    private val odometer = NavOdometer()
    private var lastDistances: NavCountdown.Distances? = null

    private var app: Context? = null
    private var locations: LocationManager? = null
    private var locationOn = false
    private var locationNoted = false
    private var lastExtrasKey = ""
    private val locationListener = LocationListener { onFix(it) }
    private val rangeTrip = RangeTrip()

    /**
     * This trip's range verdict from live scooter data; null without a route distance from Maps,
     * without a fresh charge reading (link not READY, or the charge > 90 s old), and between trips.
     */
    val rangeVerdict: RangeVerdict? get() = rangeTrip.verdict

    /** Gives the bridge an application context (for location). Safe to call repeatedly. */
    fun attach(context: Context) = onMain {
        if (app == null) {
            app = context.applicationContext
            DiagLog.init(context)
            // Until a ScooterConnection mirrors its setting, honour the persisted switch.
            forwarding = runCatching { app!!.getSharedPreferences("companion", Context.MODE_PRIVATE).getBoolean("nav_forward", true) }.getOrDefault(true)
            updateLocation()
        }
    }

    fun addListener(listener: () -> Unit) { listeners += listener; updateLocation() }
    fun removeListener(listener: () -> Unit) { listeners -= listener; updateLocation() }
    private fun changed() = listeners.toList().forEach { it() }

    fun connected(value: Boolean) = onMain {
        listenerConnected = value
        if (!value && cue != null) { cue = null; exitPending = true; resetCountdown() }
        updateLocation()
        changed()
    }

    fun update(next: MapsCue?, rawText: String) = onMain {
        if (rawText != raw) log(MapsNavigationParser.describe(next, rawText))
        raw = rawText
        if (next != null) {
            // Rerouting/"Starting navigation…" updates carry no turn data: keep showing the last
            // instruction, and never start with one (it would be a made-up STRAIGHT 0 m frame).
            if (!next.rerouting) {
                if (cue == null) app?.let(::loadEfficiency) // A new trip.
                cue = next; exitPending = false; revision++
                countdown.update(next, odometer.totalM, SystemClock.elapsedRealtime())
                lastDistances = countdown.at(odometer.totalM)
            }
            updatedAt = System.currentTimeMillis()
        }
        updateLocation()
        changed()
        // After the listeners: the scooter's frame goes out first (F4), the range check can wait a moment.
        if (next != null && !next.rerouting) checkRange()
    }

    fun end() = onMain {
        if (cue != null) log("Maps navigation ended")
        cue = null; raw = ""; exitPending = true; lastExtrasKey = ""
        resetCountdown()
        updateLocation()
        changed()
    }

    /** Releases the location feed now (app shutting down); the next Maps update may start it again. */
    fun stop() = onMain { stopLocation() }

    /** Counted-down distances for the current cue; null without one. */
    fun distances(): NavCountdown.Distances? = if (cue == null) null else countdown.at(odometer.totalM)

    /** The 0x0303 frame for the current cue at [now] (countdown distances, TFT road); null without one. */
    fun frame(now: LocalDateTime = LocalDateTime.now()): NavFrame? {
        val current = cue ?: return null
        val d = countdown.at(odometer.totalM)
        return current.frame(now, d.maneuverM, d.remainingM)
    }

    /** Full Maps extras (2.1 F3), logged once per change; [changeKey] leaves out the per-metre progress. */
    fun extras(dump: String, changeKey: String) = onMain {
        if (changeKey != lastExtrasKey) { lastExtrasKey = changeKey; log("Maps extras: $dump") }
    }

    /** Navigation ended: distances and this trip's range check start over with the next route. */
    private fun resetCountdown() {
        countdown.reset(); odometer.reset(); lastDistances = null
        rangeTrip.end(SystemClock.elapsedRealtime())
    }

    // ------------------------------------------------------------------ range check (F-RANGE)

    /** Re-checks the range against the route left; posts the trip's warning when [RangeTrip] says so. */
    private fun checkRange() {
        val context = app ?: return
        if (cue == null) return // Ended meanwhile: the next route starts a new trip.
        val remainingM = countdown.at(odometer.totalM).remainingM
        val before = rangeTrip.level
        val warning = rangeTrip.update(remainingM?.let { it / 1000.0 }, liveRange(context), SystemClock.elapsedRealtime())
        val verdict = rangeTrip.verdict
        if (verdict != null && verdict.level != before) {
            log(String.format(Locale.US, "Range check: %s (%.1f km spare; range %.0f km%s, route left %.1f km)",
                verdict.label, verdict.spareKm, verdict.rangeKm, if (verdict.estimated) " est." else "", verdict.remainingKm))
        }
        // Not marked as notified while the switch is off, so turning it on mid-trip still warns.
        if (warning == null || Demo.active || !Alerts.enabled(context, AlertKind.RANGE)) return
        rangeTrip.notified(warning)
        Alerts.rangeWarning(context, warning)
    }

    /** Range from live scooter data only (a last-known charge from yesterday must not warn); null without it. */
    private fun liveRange(context: Context): RangeEstimate? {
        if (RideInputs.phase != LinkPhase.READY) return null
        val now = System.currentTimeMillis()
        val soc = RideInputs.soc?.takeIf { now - it.time in 0..BatterySnapshot.FRESH_MS }?.value
        return RangeModel.estimate(soc, RideInputs.decodedRangeKm, TelemetryStore.shared(context).cachedEfficiency)
    }

    /**
     * Loads the learned km per % into TelemetryStore's cache (disk I/O, so off the main thread) when a
     * trip starts: the UI normally keeps it loaded, but a saved ride resets it and the UI may not run.
     */
    private fun loadEfficiency(context: Context) {
        val store = TelemetryStore.shared(context)
        runCatching { Thread({ runCatching { store.efficiency() } }, "Chetooth range").apply { isDaemon = true }.start() }
    }

    private fun updateLocation() {
        if (cue != null && forwarding && listeners.isNotEmpty()) startLocation() else stopLocation()
    }

    @SuppressLint("MissingPermission") // Checked just before requesting.
    private fun startLocation() {
        if (locationOn) return
        val context = app ?: return
        val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = fine || context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!coarse) { note("Directions: no location permission; distances change only when Maps updates"); return }
        val manager = context.getSystemService(LocationManager::class.java) ?: return
        val providers = listOfNotNull(LocationManager.GPS_PROVIDER.takeIf { fine }, LocationManager.NETWORK_PROVIDER)
        val started = providers.filter { provider ->
            runCatching { manager.requestLocationUpdates(provider, 1_000L, 0f, locationListener, Looper.getMainLooper()) }.isSuccess
        }
        if (started.isEmpty()) { note("Directions: location unavailable; distances change only when Maps updates"); return }
        locations = manager; locationOn = true; locationNoted = false
        log("Directions: counting distances down with ${started.joinToString(" + ")} location")
    }

    private fun stopLocation() {
        if (!locationOn) return
        runCatching { locations?.removeUpdates(locationListener) }
        locations = null; locationOn = false
        odometer.pause()
    }

    private fun note(message: String) {
        if (!locationNoted) { locationNoted = true; log(message) }
    }

    private fun onFix(location: Location) {
        if (!locationOn) return
        odometer.add(
            location.elapsedRealtimeNanos / 1_000_000, location.latitude, location.longitude,
            if (location.hasAccuracy()) location.accuracy.toDouble() else Double.NaN,
            if (location.hasSpeed()) location.speed.toDouble() else Double.NaN,
            location.provider == LocationManager.GPS_PROVIDER
        )
        if (cue == null) return
        val now = countdown.at(odometer.totalM)
        if (now != lastDistances) { lastDistances = now; changed(); checkRange() } // The link pushes the new frame at once.
    }

    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() === Looper.getMainLooper()) block() else main.post { block() }
    }
}

/**
 * Ruta's notification listener (the component name is load-bearing: renaming it drops the
 * granted access). Two jobs, both in memory only:
 *  - Google Maps' ongoing navigation notification → [NavBridge] (turn-by-turn);
 *  - everything else → [PhoneBridge]: missed-call/unread-message counts, the dialer's call
 *    notification (caller name fallback) and media notifications. The same access is what lets
 *    PhoneBridge read media sessions (MediaSessionManager checks this component).
 * Callbacks arrive on the main thread (NotificationListenerService's handler).
 * Play Protect blocks sideloaded apps that declare this service when installed from a browser or
 * file manager; install with `adb install` (or pause Play Protect).
 */
class MapsNavigationListener : NotificationListenerService() {
    private var activeKey: String? = null

    override fun onListenerConnected() {
        NavBridge.attach(applicationContext)
        NavBridge.connected(true)
        runCatching { activeNotifications.orEmpty().filter { it.packageName == MAPS }.forEach(::onMapsNotification) }
        PhoneBridge.listenerConnected(this)
    }

    override fun onListenerDisconnected() { activeKey = null; NavBridge.connected(false); PhoneBridge.listenerDisconnected() }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == MAPS) onMapsNotification(sbn) else PhoneBridge.notificationPosted(this, sbn)
    }

    private fun onMapsNotification(sbn: StatusBarNotification) {
        val notification = sbn.notification
        val navigation = notification.category == Notification.CATEGORY_NAVIGATION ||
            (notification.flags and Notification.FLAG_ONGOING_EVENT != 0 && notification.group.orEmpty().contains("navigation", ignoreCase = true))
        if (!navigation) return
        val extras = notification.extras
        runCatching { MapsExtras.dump(extras) }.getOrNull()?.let { (dump, key) -> NavBridge.extras(dump, key) }
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        val short = extras.getCharSequence("android.shortCriticalText")?.toString()
        val progressMax = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0).takeIf { it > 0 }
        val progress = if (progressMax != null) extras.getInt(Notification.EXTRA_PROGRESS, 0) else null
        val cue = MapsNavigationParser.parse(title, text, subText, short, progress, progressMax)
        val raw = listOfNotNull(title, text, subText).joinToString(" | ") +
            (progressMax?.let { " | ${progress ?: 0}/$it m" } ?: "") + (short?.let { " | short '$it'" } ?: "")
        activeKey = sbn.key
        NavBridge.update(cue, raw)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.key == activeKey) { activeKey = null; NavBridge.end() }
        if (sbn.packageName != MAPS) PhoneBridge.notificationRemoved(this, sbn)
    }

    companion object { const val MAPS = "com.google.android.apps.maps" }
}

/**
 * Every key of a Maps notification's extras as `key=value`, sorted, for the diagnostics log
 * (2.1 F3: progressPoints/progressSegments and anything else Maps may encode the maneuver in).
 * Nested bundles and lists are expanded; Parcelables are summarised (Icon type, Bitmap size,
 * ApplicationInfo package); long text is cut at 200 characters.
 */
private object MapsExtras {
    /** (dump, change key): the key omits android.progress, which changes with every metre travelled. */
    fun dump(extras: Bundle): Pair<String, String> {
        val entries = extras.keySet().sorted().map { it to entry(extras, it, 0) }
        fun join(list: List<Pair<String, String>>) = list.joinToString(", ") { "${it.first}=${it.second}" }
        return join(entries) to join(entries.filter { it.first != Notification.EXTRA_PROGRESS })
    }

    @Suppress("DEPRECATION") // Bundle.get: the only way to read values of unknown type.
    private fun entry(bundle: Bundle, key: String, depth: Int): String =
        runCatching { describe(bundle.get(key), depth) }.getOrElse { "<${it.javaClass.simpleName}>" }

    private fun describe(value: Any?, depth: Int): String = when (value) {
        null -> "null"
        is CharSequence -> "\"" + value.toString().replace("\n", "\\n").take(200) + "\""
        is Number, is Boolean, is Char -> value.toString()
        is Bundle -> if (depth >= 3) "Bundle{…}" else value.keySet().sorted().joinToString(", ", "{", "}") { "$it=${entry(value, it, depth + 1)}" }
        is Collection<*> -> list(value.toList(), depth)
        is Array<*> -> list(value.asList(), depth)
        is IntArray -> value.contentToString()
        is LongArray -> value.contentToString()
        is FloatArray -> value.contentToString()
        is DoubleArray -> value.contentToString()
        is BooleanArray -> value.contentToString()
        is ByteArray -> "bytes(${value.size})"
        is Icon -> icon(value)
        is Bitmap -> "Bitmap(${value.width}x${value.height})"
        is ApplicationInfo -> "ApplicationInfo(${value.packageName})"
        is PendingIntent -> "PendingIntent(${value.creatorPackage})"
        is Parcelable -> value.javaClass.simpleName
        else -> value.javaClass.simpleName
    }

    private fun list(items: List<Any?>, depth: Int): String =
        if (depth >= 3) "[${items.size} items]"
        else items.take(20).joinToString(", ", "[", if (items.size > 20) ", …${items.size - 20} more]" else "]") { describe(it, depth + 1) }

    private fun icon(icon: Icon): String = when (icon.type) {
        Icon.TYPE_RESOURCE -> runCatching { "Icon(resource ${icon.resPackage} 0x${Integer.toHexString(icon.resId)})" }.getOrDefault("Icon(resource)")
        Icon.TYPE_BITMAP -> "Icon(bitmap)"
        Icon.TYPE_ADAPTIVE_BITMAP -> "Icon(adaptive bitmap)"
        Icon.TYPE_DATA -> "Icon(data)"
        Icon.TYPE_URI, Icon.TYPE_URI_ADAPTIVE_BITMAP -> "Icon(uri)"
        else -> "Icon(type ${icon.type})"
    }
}
