package dev.codesurgen.ruta

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import kotlin.math.floor

/** Main-thread handle that lets the ride notification disconnect the scooter. */
object RideRuntime {
    var stopConnection: (() -> Unit)? = null
}

/**
 * Keeps the scooter link and ride tracking alive while Google Maps is in front, and hosts [RideTracker].
 * Foreground types: connectedDevice, plus location when precise location is granted (Android 14+
 * refuses the location type without it, or when started from the background).
 *  - [start] while the app is in front (Android 12+ refuses background starts; it may throw);
 *  - [refresh] after the user grants location and whenever Ruta comes to the front, to add the
 *    location type (Android only allows that while the app is visible);
 *  - an [ACTION_STOP] intent (the notification's Disconnect) calls [RideRuntime.stopConnection] and stops.
 * Charge alerts (2.2 F-CHARGE) are judged here, the process that holds the scooter link: [ChargeWatch]
 * on every [RideLive] change, posted through [Alerts] ("Charged to 80 %", "Charging stopped at 63 %").
 */
class RideService : Service() {
    private var tracker: RideTracker? = null
    private var shown: Content? = null
    /** The link was READY at least once since the service started: a later drop is a reconnect. */
    private var wasReady = false
    private val chargeWatch = ChargeWatch()
    /** A "Charging stopped" alert is up: removed when charging resumes (it would no longer be true). */
    private var stoppedPosted = false
    private val onLive: () -> Unit = { updateNotification(); watchCharge() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        DiagLog.init(this)
        running = true
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Scooter connection", NotificationManager.IMPORTANCE_LOW))
        Alerts.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            RideRuntime.stopConnection?.invoke(); stopSelf(); return START_NOT_STICKY
        }
        goForeground()
        if (tracker == null) {
            RideLive.addListener(onLive)
            tracker = RideTracker(this).also { it.start() }
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) { RideRuntime.stopConnection?.invoke(); stopSelf() }

    override fun onDestroy() {
        RideLive.removeListener(onLive)
        tracker?.stop()
        tracker = null
        running = false
        hasLocationType = false
        super.onDestroy()
    }

    private fun goForeground() {
        val location = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val device = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        val notification = notification(content(RideLive.state))
        if (location) {
            try {
                startForeground(ID, notification, device or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
                if (!hasLocationType) { hasLocationType = true; updateNotification() }
                return
            } catch (e: Exception) {
                // Background start or a manifest without the location type: keep the link, rides need the app open.
                RideLive.say("Background location unavailable (${e.javaClass.simpleName}); rides record while Chetooth is open.")
            }
        }
        hasLocationType = false
        startForeground(ID, notification, device)
        updateNotification()
    }

    private fun updateNotification() {
        val content = content(RideLive.state)
        if (content == shown) return
        getSystemService(NotificationManager::class.java).notify(ID, notification(content))
    }

    /**
     * Charge target reached / charging stopped early, judged by [chargeWatch] from the live session;
     * [Alerts] applies the switches and stays silent in the demo. Main thread (RideLive listener).
     */
    private fun watchCharge() {
        val state = RideLive.state
        if (state.charging != null && stoppedPosted) { stoppedPosted = false; Alerts.clear(this, AlertKind.CHARGE_STOPPED) }
        val event = chargeWatch.update(state.charging, state.socPercent, state.link == LinkPhase.READY, state.riding, Alerts.chargeTarget(this))
        when (event) {
            is ChargeEvent.Reached -> Alerts.chargeReached(this, event.percent, state.range?.km, state.range?.estimated ?: true)
            is ChargeEvent.Stopped -> if (Alerts.enabled(this, AlertKind.CHARGE_STOPPED)) {
                Alerts.chargeStopped(this, event.percent)
                stoppedPosted = true
            }
            null -> {}
        }
    }

    /** What the notification shows: a one-line state ([title]), detail ([text]) and a state glyph ([icon], drawable name). */
    private data class Content(val title: String, val text: String, val icon: String)

    private fun notification(content: Content): Notification {
        shown = content
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 2, Intent(this, RideService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stopIcon = drawable("ic_link_off").takeIf { it != 0 }?.let { Icon.createWithResource(this, it) }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(drawable(content.icon).takeIf { it != 0 } ?: notificationIcon())
            .setContentTitle(content.title)
            .apply { if (content.text.isNotEmpty()) setContentText(content.text) }
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC) // State, distance and charge only: fine on the lock screen.
            .apply {
                if (Build.VERSION.SDK_INT >= 31) {
                    setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                    runCatching { setColor(getColor(android.R.color.system_accent1_300)) }
                }
            }
            .addAction(Notification.Action.Builder(stopIcon, "Disconnect", stop).build()).build()
    }

    /** A res/drawable by name (build.sh generates no R class); 0 when absent. */
    @SuppressLint("DiscouragedApi")
    private fun drawable(name: String): Int = resources.getIdentifier(name, "drawable", packageName)

    /** res/drawable/ic_notification (Ruta's own glyph), else the system Bluetooth glyph. */
    private fun notificationIcon(): Int = drawable("ic_notification").takeIf { it != 0 } ?: android.R.drawable.stat_sys_data_bluetooth

    /**
     * Icon-led, glanceable text at minute / 0.1 km / 1 % resolution (so it changes rarely):
     * "Riding · 3.2 km · 12 min" / "57 % · 64 km"; "Charging 54 → 80 %" / "Full in 1 h 10 min · +26 % in 45 min"
     * (the time to full once the session's rate is known, [ChargeEta], as Home's "Full in");
     * "Chetooth connected · 57 %" / "64 km range"; "Chetooth reconnecting…". Charge is shown rounded down, like the TFT.
     */
    private fun content(state: RideState): Content {
        if (state.link == LinkPhase.READY) wasReady = true
        val battery = listOfNotNull(
            state.socPercent?.let { "${percent(it)} %" },
            state.range?.let { "${number(it.km, 0)} km" }
        ).joinToString(" · ")
        val rides = when {
            state.gps == GpsStatus.NO_PERMISSION -> "Rides off · allow location"
            state.gps == GpsStatus.DISABLED -> "Rides off · location is off"
            !hasLocationType -> "Open Chetooth to record rides"
            else -> null
        }
        val charging = state.charging
        return when {
            state.riding -> Content(
                "Riding · ${number(state.distanceM / 1000, 1)} km · ${minutes(state.durationS)}", battery, "ic_electric_moped_fill"
            )
            charging != null -> Content(
                "Charging ${percent(charging.socStart)} → ${percent(charging.socEnd)} %",
                listOfNotNull(
                    ChargeEta.phrase(ChargeEta.minutesToFull(state.socPercent, charging)),
                    // One labelled duration each: the rise says how long it took ("+26 % in 45 min").
                    "+${percent(charging.socEnd) - percent(charging.socStart)} % in ${minutes(charging.durationS)}"
                ).joinToString(" · "),
                "ic_battery_charging_full_fill"
            )
            state.link != LinkPhase.READY -> Content(
                if (wasReady) "Chetooth reconnecting…" else "Chetooth connecting…", rides.orEmpty(), "ic_bluetooth_searching"
            )
            else -> Content(
                "Chetooth connected" + (state.socPercent?.let { " · ${percent(it)} %" } ?: ""),
                listOfNotNull(state.range?.let { "${number(it.km, 0)} km range" }, rides).joinToString(" · "),
                "ic_notification"
            )
        }
    }

    private fun percent(value: Double): Int = floor(value).toInt().coerceIn(0, 100)

    private fun minutes(seconds: Long): String = if (seconds < 60) "<1 min" else RideMath.formatDuration(seconds / 60 * 60)

    companion object {
        /** Kept as the literal "STOP" that existing callers send. */
        const val ACTION_STOP = "STOP"
        private const val ACTION_REFRESH = "REFRESH"
        private const val ID = 7001
        private const val CHANNEL = "vida_ride"

        /** True between onCreate and onDestroy (main thread). */
        @Volatile var running = false; private set
        /**
         * Whether the running service holds the location foreground type. Without it Android stops GPS
         * fixes once Ruta is in the background, so rides only record while the app is open.
         */
        @Volatile var hasLocationType = false; private set

        fun start(context: Context) {
            context.startForegroundService(Intent(context, RideService::class.java))
        }

        /**
         * Adds the location type if the running service lacks it. Call while Ruta is in front (after a
         * location grant and on resume); no-op when not running or already typed.
         */
        fun refresh(context: Context) {
            if (!running || hasLocationType) return
            runCatching { context.startService(Intent(context, RideService::class.java).setAction(ACTION_REFRESH)) }
                .onFailure { RideLive.say("Could not add background location to the ride service (${it.javaClass.simpleName})") }
        }
    }
}
