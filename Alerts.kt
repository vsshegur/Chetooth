package dev.codesurgen.ruta

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/*
 * Rider alerts (2.2 F-RANGE, F-CHARGE, F-PARK switches). Notifications go to their own channel
 * "Ride alerts" (IMPORTANCE_DEFAULT: sound, no pop-up), separate from the quiet "Scooter connection"
 * channel of RideService. Every alert is also handed to [Alerts.inApp] (MainActivity shows it as a
 * snackbar while Ruta is visible), which is how alerts still reach the rider when notifications are
 * off. Nothing is posted while the demo runs. Wording: [AlertText] (Range.kt, host-tested).
 */

/**
 * The rider alerts and features with a switch in More › Alerts. [MESSAGE_SENDER] ("Show who
 * messaged", [MessageAlerter]) posts no notification: it writes the sender to the scooter display.
 */
enum class AlertKind { RANGE, CHARGE_TARGET, CHARGE_STOPPED, PARKING, MESSAGE_SENDER }

/**
 * Whether alerts reach the notification shade: [ON]; [ASK] = the notification permission can be
 * requested (dialog); [SETTINGS] = turned off in Android settings (app, channel, or the permission
 * is blocked) — only the settings page can turn it back on.
 */
enum class AlertAccess { ON, ASK, SETTINGS }

object Alerts {
    /** Notification channel id of "Ride alerts". */
    const val CHANNEL = "ride_alerts"
    /** The charge targets More offers ("Notify at" 80 / 90 / 100 %). */
    val TARGETS = listOf(80, 90, 100)
    const val DEFAULT_TARGET = 80

    private const val PREFS = "companion"
    private const val KEY_TARGET = "alert_charge_target"
    /** MainActivity's "blocked_<group>" flag for the notification permission (set when Android stops asking). */
    private const val KEY_BLOCKED = "blocked_notifications"
    /** One notification per kind: a newer one of the same kind replaces it. */
    private const val ID_RANGE = 7101
    private const val ID_CHARGE = 7102
    private const val ID_STOPPED = 7103

    /**
     * In-app echo of every alert (one short line). MainActivity sets it and shows a snackbar while
     * Ruta is visible; called on the main thread.
     */
    @Volatile var inApp: ((String) -> Unit)? = null

    // ------------------------------------------------------------------ settings

    /** The switch for [kind]. Defaults: range warning, charge alert and parking on; charging stopped and show who messaged off. */
    fun enabled(context: Context, kind: AlertKind): Boolean =
        prefs(context).getBoolean(key(kind), kind != AlertKind.CHARGE_STOPPED && kind != AlertKind.MESSAGE_SENDER)

    fun setEnabled(context: Context, kind: AlertKind, on: Boolean) {
        prefs(context).edit().putBoolean(key(kind), on).apply()
        DiagLog.append("Alerts: ${label(kind)} ${if (on) "on" else "off"}")
    }

    /** The charge alert's target: 80, 90 or 100 (default 80). */
    fun chargeTarget(context: Context): Int = snap(prefs(context).getInt(KEY_TARGET, DEFAULT_TARGET))

    /** Stores [percent] as the nearest of [TARGETS]. */
    fun setChargeTarget(context: Context, percent: Int) {
        val target = snap(percent)
        prefs(context).edit().putInt(KEY_TARGET, target).apply()
        DiagLog.append("Alerts: charge target $target %")
    }

    // ------------------------------------------------------------------ alerts

    /**
     * "Short by 4 km — charge before you arrive" / "Range is tight · 3 km spare" for a TIGHT or SHORT
     * [verdict] (OK posts nothing). The caller decides when (RangeTrip: once per trip, again only when
     * it worsens to SHORT). Needs the range warning switch.
     */
    fun rangeWarning(context: Context, verdict: RangeVerdict) {
        if (verdict.level == RangeLevel.OK || !allowed(context, AlertKind.RANGE)) return
        val short = verdict.level == RangeLevel.SHORT
        val whole = if (short) verdict.shortWholeKm else verdict.spareWholeKm
        post(context, ID_RANGE, AlertText.range(short, whole, verdict.remainingKm, verdict.rangeKm, verdict.estimated))
    }

    /** "Charged to 80 % · range 90 km est." ([percent] ≥ 100: "Fully charged"). Needs the charge alert switch. */
    fun chargeReached(context: Context, percent: Int, rangeKm: Double?, estimated: Boolean = true) {
        if (!allowed(context, AlertKind.CHARGE_TARGET)) return
        post(context, ID_CHARGE, AlertText.chargeReached(percent, rangeKm, estimated))
    }

    /** "Charging stopped at 63 %". Needs the charging stopped switch. */
    fun chargeStopped(context: Context, percent: Int) {
        if (!allowed(context, AlertKind.CHARGE_STOPPED)) return
        post(context, ID_STOPPED, AlertText.chargeStopped(percent))
    }

    /** Removes [kind]'s posted notification, if any (e.g. "Charging stopped" once charging resumes). */
    fun clear(context: Context, kind: AlertKind) {
        val id = when (kind) {
            AlertKind.RANGE -> ID_RANGE
            AlertKind.CHARGE_TARGET -> ID_CHARGE
            AlertKind.CHARGE_STOPPED -> ID_STOPPED
            AlertKind.PARKING, AlertKind.MESSAGE_SENDER -> return
        }
        runCatching { manager(context)?.cancel(id) }
    }

    // ------------------------------------------------------------------ notification access

    /** See [AlertAccess]. Cheap (no I/O beyond in-memory preferences); fine to call from render. */
    fun access(context: Context): AlertAccess {
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT >= 33 && app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return if (prefs(app).getBoolean(KEY_BLOCKED, false)) AlertAccess.SETTINGS else AlertAccess.ASK
        }
        val manager = manager(app) ?: return AlertAccess.SETTINGS
        if (!manager.areNotificationsEnabled()) return AlertAccess.SETTINGS
        val channel = runCatching { manager.getNotificationChannel(CHANNEL) }.getOrNull()
        return if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) AlertAccess.SETTINGS else AlertAccess.ON
    }

    /** The settings page that turns alerts back on: the "Ride alerts" channel when only it is off, else the app's notifications. */
    fun settingsIntent(context: Context): Intent {
        val app = context.applicationContext
        val manager = manager(app)
        val channelOff = manager != null && manager.areNotificationsEnabled() &&
            runCatching { manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE }.getOrDefault(false)
        return if (channelOff) Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, app.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL)
        else Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, app.packageName)
    }

    // ------------------------------------------------------------------ internals

    private fun allowed(context: Context, kind: AlertKind): Boolean = !Demo.active && enabled(context, kind)

    private fun post(context: Context, id: Int, message: AlertMessage) {
        val app = context.applicationContext
        Ui.run { if (!Demo.active) inApp?.invoke(message.line) }
        val access = access(app)
        if (access != AlertAccess.ON) { DiagLog.append("Alert (notifications off, in-app only): ${message.line}"); return }
        DiagLog.append("Alert: ${message.line}")
        runCatching {
            val manager = manager(app) ?: return
            channel(manager)
            manager.notify(id, build(app, id, message))
        }.onFailure { DiagLog.append("Alert not posted: ${it.javaClass.simpleName}") }
    }

    private fun build(app: Context, id: Int, message: AlertMessage): Notification {
        val open = PendingIntent.getActivity(
            app, id, Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(app, CHANNEL)
            .setSmallIcon(drawable(app, "ic_notification").takeIf { it != 0 } ?: android.R.drawable.stat_sys_warning)
            .setContentTitle(message.title)
            .apply { message.text?.let { setContentText(it) } }
            .setContentIntent(open)
            .setAutoCancel(true)
            .setShowWhen(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC) // Charge and route distance only: fine on the lock screen.
            .apply { if (Build.VERSION.SDK_INT >= 31) runCatching { setColor(app.getColor(android.R.color.system_accent1_300)) } }
            .build()
    }

    /** Registers "Ride alerts" so it is listed in Android settings before the first alert (RideService start). */
    fun ensureChannel(context: Context) { runCatching { manager(context)?.let(::channel) } }

    /** Creates "Ride alerts" once (Android keeps the user's changes to an existing channel). */
    private fun channel(manager: NotificationManager) {
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Ride alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Range on a Maps route, charge target reached, charging stopped"
        })
    }

    private fun manager(context: Context): NotificationManager? =
        context.applicationContext.getSystemService(NotificationManager::class.java)

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(kind: AlertKind): String = when (kind) {
        AlertKind.RANGE -> "alert_range"
        AlertKind.CHARGE_TARGET -> "alert_charge_target_on"
        AlertKind.CHARGE_STOPPED -> "alert_charge_stopped"
        AlertKind.PARKING -> "alert_parking"
        AlertKind.MESSAGE_SENDER -> "alert_message_sender"
    }

    private fun label(kind: AlertKind): String = when (kind) {
        AlertKind.RANGE -> "range warning"
        AlertKind.CHARGE_TARGET -> "charge alert"
        AlertKind.CHARGE_STOPPED -> "charging stopped"
        AlertKind.PARKING -> "remember where I park"
        AlertKind.MESSAGE_SENDER -> "show who messaged"
    }

    private fun snap(percent: Int): Int = TARGETS.minByOrNull { kotlin.math.abs(it - percent) } ?: DEFAULT_TARGET

    /** A res/drawable by name (no R class); 0 when absent. */
    @SuppressLint("DiscouragedApi")
    private fun drawable(app: Context, name: String): Int = app.resources.getIdentifier(name, "drawable", app.packageName)
}
