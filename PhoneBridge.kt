package dev.codesurgen.ruta

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.ContactsContract
import android.provider.Settings
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Now-playing snapshot of the media session [PhoneBridge] follows. Times are elapsedRealtime ms. */
data class MediaState(
    val packageName: String,
    val appLabel: String,
    val title: String,
    val artist: String,
    val album: String,
    /** android.media.session.PlaybackState.STATE_*. */
    val playbackState: Int,
    val positionMs: Long,
    val positionAt: Long,
    val speed: Float,
    /** 0 when the app does not report it. */
    val durationMs: Long
) {
    /** Same rule as the cluster's play flag ([MusicInfo.of]): buffering and skipping count as playing. */
    val playing: Boolean get() = MusicInfo.of(playbackState, 0).playing

    /** Extrapolated like the system media controls (the session only reports on changes). */
    fun position(now: Long = SystemClock.elapsedRealtime()): Long {
        if (playbackState != PlaybackState.STATE_PLAYING || positionAt <= 0) return positionMs.coerceAtLeast(0)
        val position = positionMs + ((now - positionAt) * speed).toLong()
        return if (durationMs > 0) position.coerceIn(0, durationMs) else position.coerceAtLeast(0)
    }

    fun progressPercent(now: Long = SystemClock.elapsedRealtime()): Int = MusicInfo.progressPercent(position(now), durationMs)
}

/** STREAM_MUSIC volume; [level] is the cluster's 0..10 scale. */
data class Volume(val current: Int = 0, val max: Int = 0, val muted: Boolean = false) {
    val level: Int get() = if (muted) 0 else MusicInfo.volumeLevel(current, max)
}

enum class CallPhase { IDLE, RINGING, ACTIVE, ENDED }

/**
 * The phone call as Android reports it (TelephonyManager states, so dialing and talking are both
 * ACTIVE: Android tells apps only "off hook"). Times are elapsedRealtime ms.
 */
data class CallState(
    val phase: CallPhase = CallPhase.IDLE,
    /** Went off-hook without ringing first. */
    val outgoing: Boolean = false,
    val number: String? = null,
    val name: String? = null,
    val startedAt: Long = 0L,
    /** When it went off-hook; 0 while ringing. */
    val connectedAt: Long = 0L,
    val endedAt: Long = 0L
) {
    val inCall: Boolean get() = phase == CallPhase.RINGING || phase == CallPhase.ACTIVE

    /** Contact name when resolved, else the number, else null (private/unknown or no permission). */
    val display: String? get() = name?.takeIf { it.isNotBlank() } ?: number

    fun durationS(now: Long = SystemClock.elapsedRealtime()): Int = when {
        connectedAt == 0L -> 0
        phase == CallPhase.ENDED -> ((endedAt - connectedAt) / 1000).toInt().coerceAtLeast(0)
        else -> ((now - connectedAt) / 1000).toInt().coerceAtLeast(0)
    }

    /**
     * What 0x0301 reports: an outgoing call shows
     * OUTGOING for its first tick, then ACTIVE with the time since dialing (Android cannot tell
     * when the other side answers); ENDED is held ~2 s by [PhoneBridge], then NONE.
     */
    fun tcuState(now: Long = SystemClock.elapsedRealtime()): TcuCallState = when (phase) {
        CallPhase.IDLE -> TcuCallState.NONE
        CallPhase.RINGING -> TcuCallState.INCOMING
        CallPhase.ENDED -> TcuCallState.ENDED
        CallPhase.ACTIVE -> if (outgoing && now - connectedAt < PhoneBridge.OUTGOING_HOLD_MS) TcuCallState.OUTGOING else TcuCallState.ACTIVE
    }
}

/** From active notifications seen by the listener; [available] is false without notification access. */
data class Counts(val missedCalls: Int = 0, val unreadMessages: Int = 0, val available: Boolean = false)

/** Which phone features Android lets Ruta use. */
data class PhoneAccess(
    val notifications: Boolean = false,
    val phoneState: Boolean = false,
    val callLog: Boolean = false,
    val contacts: Boolean = false,
    val answerCalls: Boolean = false
) {
    /** Runtime permissions still to request (subset of [PhoneBridge.PERMISSIONS]); notification access is separate. */
    val missingPermissions: List<String>
        get() = listOfNotNull(
            Manifest.permission.READ_PHONE_STATE.takeUnless { phoneState },
            Manifest.permission.READ_CALL_LOG.takeUnless { callLog },
            Manifest.permission.READ_CONTACTS.takeUnless { contacts },
            Manifest.permission.ANSWER_PHONE_CALLS.takeUnless { answerCalls }
        )

    fun describe(): String = listOf(
        "notifications" to notifications, "phone" to phoneState, "call log" to callLog,
        "contacts" to contacts, "answer calls" to answerCalls
    ).joinToString { (what, ok) -> "$what ${if (ok) "on" else "off"}" }
}

enum class MediaAction { PLAY_PAUSE, PLAY, PAUSE, NEXT, PREVIOUS, VOLUME_UP, VOLUME_DOWN, MUTE }
enum class CallAction { ACCEPT, REJECT }

/**
 * The phone side of the cluster's ICBT features: now playing and volume (any music app, through
 * MediaSessionManager with our notification listener's access), phone calls (TelephonyCallback,
 * number with READ_CALL_LOG, contact name with READ_CONTACTS), missed-call and unread-message
 * counts (from active notifications, fed by [MapsNavigationListener]) and headset state.
 *
 * Main-thread state object. Running is reference-counted by owner: [ScooterConnection] holds it from
 * connect() until disconnect(), and a screen may hold it while visible to show now-playing. Counts
 * are fed by the listener whether or not it is running. Every feature degrades on its own when its
 * permission is missing ([access]); granted permissions are picked up within a few seconds.
 */
@SuppressLint("MissingPermission") // Each telephony/telecom/contacts use checks its permission first.
object PhoneBridge {
    /** Runtime permissions for calls on the cluster. READ_SMS is deliberately not used. */
    val PERMISSIONS = arrayOf(
        Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_CONTACTS, Manifest.permission.ANSWER_PHONE_CALLS
    )
    /** ENDED drops to NONE after 2 s. */
    const val ENDED_HOLD_MS = 2_000L
    /** Outgoing calls report OUTGOING for one 1 Hz tick, then ACTIVE (see [CallState.tcuState]). */
    const val OUTGOING_HOLD_MS = 1_500L
    private const val MAINTENANCE_MS = 3_000L
    private const val NUMBER_MATCH_MS = 3_000L

    /** Defaults to the shared diagnostics log (NavBridge.log → MainActivity appendLog → logcat). */
    var log: (String) -> Unit = { NavBridge.log(it) }

    var media: MediaState? = null; private set
    var volume = Volume(); private set
    var call = CallState(); private set
    var counts = Counts(); private set
    var headset = false; private set
    var access = PhoneAccess(); private set
    /** False until media sessions can be read (needs notification access for the listener). */
    var mediaSessionsAvailable = false; private set
    val running: Boolean get() = owners.isNotEmpty()

    private val listeners = linkedSetOf<() -> Unit>()
    private val owners = mutableSetOf<Any>()
    private var app: Context? = null
    private var scope: CoroutineScope? = null
    private var maintenance: Runnable? = null

    fun addListener(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }
    private fun changed() = listeners.toList().forEach { it() }

    /**
     * New-message listeners ("Show who messaged", [MessageAlerter]): called on the main thread with the
     * app and sender of a message notification that is new (not a re-post), never its text. Names are
     * read only while someone listens.
     */
    private val messageListeners = linkedSetOf<(MessageNotice) -> Unit>()
    fun addMessageListener(listener: (MessageNotice) -> Unit) { messageListeners += listener }
    fun removeMessageListener(listener: (MessageNotice) -> Unit) { messageListeners -= listener }

    /** Starts tracking for [owner] (idempotent per owner). Main thread. */
    fun start(context: Context, owner: Any) {
        Ui.assertMainThread("PhoneBridge.start")
        val first = owners.isEmpty()
        if (!owners.add(owner) || !first) return
        val ctx = context.applicationContext
        app = ctx
        scope = Ui.scope()
        access = checkAccess(ctx)
        log("Phone link on: ${access.describe()}")
        registerAudio(ctx)
        registerTelephony(ctx)
        connectMedia(ctx)
        scheduleMaintenance()
        changed()
    }

    /** Releases [owner]; tracking stops when the last owner leaves. Main thread. */
    fun stop(owner: Any) {
        Ui.assertMainThread("PhoneBridge.stop")
        if (!owners.remove(owner) || owners.isNotEmpty()) return
        val ctx = app ?: return
        maintenance?.let(Ui::cancel); maintenance = null
        endedTimer?.let(Ui::cancel); endedTimer = null
        scope?.cancel(); scope = null
        unregisterAudio(ctx)
        unregisterTelephony(ctx)
        disconnectMedia()
        app = null
        media = null; call = CallState(); pendingNumber = null; heldCall = null
        log("Phone link off")
        changed()
    }

    /** Current grants. Notification access is what media sessions and counts need. */
    fun checkAccess(context: Context): PhoneAccess {
        fun has(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        val notifications = runCatching {
            context.getSystemService(NotificationManager::class.java)
                .isNotificationListenerAccessGranted(ComponentName(context, MapsNavigationListener::class.java))
        }.getOrDefault(false)
        return PhoneAccess(
            notifications, has(Manifest.permission.READ_PHONE_STATE), has(Manifest.permission.READ_CALL_LOG),
            has(Manifest.permission.READ_CONTACTS), has(Manifest.permission.ANSWER_PHONE_CALLS)
        )
    }

    /** Re-reads grants now (call after a permission dialog); otherwise this happens every few seconds. */
    fun refreshAccess() {
        val ctx = app ?: return
        val next = checkAccess(ctx)
        if (next != access) {
            access = next
            log("Phone access: ${next.describe()}")
            changed()
        }
        if (next.phoneState) registerTelephony(ctx) else unregisterTelephony(ctx)
        if (!mediaSessionsAvailable && next.notifications) connectMedia(ctx)
    }

    private fun scheduleMaintenance() {
        maintenance = Ui.postDelayed(MAINTENANCE_MS) {
            if (app == null) return@postDelayed
            refreshAccess(); refreshVolume(); refreshHeadset()
            scheduleMaintenance()
        }
    }

    // --- Audio: volume and headset ---

    private val volumeObserver = object : ContentObserver(Ui.handler) {
        override fun onChange(selfChange: Boolean) = refreshVolume()
    }
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = refreshHeadset()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = refreshHeadset()
    }
    private val headsetTypes = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_HEARING_AID,
        AudioDeviceInfo.TYPE_BLE_HEADSET
    )

    private fun audio(): AudioManager? = app?.getSystemService(AudioManager::class.java)

    private fun registerAudio(ctx: Context) {
        // Volume settings are persisted to Settings.System; watching that URI catches every change.
        runCatching { ctx.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, volumeObserver) }
        runCatching { audio()?.registerAudioDeviceCallback(deviceCallback, Ui.handler) }
        refreshVolume(); refreshHeadset()
    }

    private fun unregisterAudio(ctx: Context) {
        runCatching { ctx.contentResolver.unregisterContentObserver(volumeObserver) }
        runCatching { ctx.getSystemService(AudioManager::class.java)?.unregisterAudioDeviceCallback(deviceCallback) }
    }

    private fun refreshVolume() {
        val audio = audio() ?: return
        val next = runCatching {
            Volume(audio.getStreamVolume(AudioManager.STREAM_MUSIC), audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), audio.isStreamMute(AudioManager.STREAM_MUSIC))
        }.getOrNull() ?: return
        if (next != volume) { volume = next; changed() }
    }

    /** Wired, USB or Bluetooth (A2DP/HFP/LE) headset connected. */
    private fun refreshHeadset() {
        val audio = audio() ?: return
        val next = runCatching { audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in headsetTypes } }.getOrDefault(false)
        if (next != headset) {
            headset = next
            log(if (next) "Headset connected" else "Headset disconnected")
            changed()
        }
    }

    // --- Calls ---

    /** TelephonyCallback (31+) or PhoneStateListener (30); null when not registered. */
    private var telephonyListener: Any? = null
    private var numberReceiver: BroadcastReceiver? = null
    /** The first state after registering describes a call already in progress, not a new one. */
    private var telephonyPrimed = false
    /** A number broadcast that arrived before its state callback: (number, elapsedRealtime). */
    private var pendingNumber: Pair<String, Long>? = null
    /** The call in progress while a second call rings (call waiting); restored if that call is declined or missed. */
    private var heldCall: CallState? = null
    private var endedTimer: Runnable? = null
    /** Title of the dialer's ongoing/incoming call notification: name fallback without permissions. */
    private var callNotification: Pair<String, String>? = null // key to title

    private class CallCallback : TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) = Ui.run { onTelephonyState(state, null) }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    private class LegacyCallListener : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) = Ui.run { onTelephonyState(state, phoneNumber) }
    }

    private fun registerTelephony(ctx: Context) {
        if (telephonyListener != null || !access.phoneState) return
        val telephony = ctx.getSystemService(TelephonyManager::class.java) ?: return
        telephonyPrimed = false
        telephonyListener = try {
            if (Build.VERSION.SDK_INT >= 31) CallCallback().also { telephony.registerTelephonyCallback(ctx.mainExecutor, it) }
            else LegacyCallListener().also { @Suppress("DEPRECATION") telephony.listen(it, PhoneStateListener.LISTEN_CALL_STATE) }
        } catch (e: Exception) {
            log("Call state unavailable: ${e.javaClass.simpleName} ${e.message.orEmpty()}"); return
        }
        // TelephonyCallback carries no number; the phone-state broadcast does (with READ_CALL_LOG),
        // for incoming and outgoing calls. It only fills in the number, never drives the state:
        // broadcasts can lag the callback by a whole transition.
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getStringExtra(TelephonyManager.EXTRA_STATE) == TelephonyManager.EXTRA_STATE_IDLE) return
                @Suppress("DEPRECATION") val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                onCallNumber(number?.trim()?.takeIf { it.isNotEmpty() } ?: return)
            }
        }
        val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        runCatching {
            // Protected system broadcast: exported is correct.
            if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED) else ctx.registerReceiver(receiver, filter)
            numberReceiver = receiver
        }
        log("Calls: watching call state" + if (access.callLog) "" else " (allow Call logs to show numbers)")
    }

    private fun unregisterTelephony(ctx: Context) {
        val listener = telephonyListener ?: return
        telephonyListener = null
        val telephony = ctx.getSystemService(TelephonyManager::class.java)
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) telephony?.unregisterTelephonyCallback(listener as TelephonyCallback)
            else @Suppress("DEPRECATION") telephony?.listen(listener as PhoneStateListener, PhoneStateListener.LISTEN_NONE)
        }
        numberReceiver?.let { runCatching { ctx.unregisterReceiver(it) } }; numberReceiver = null
        if (call.phase != CallPhase.IDLE) setCall(CallState())
    }

    private fun onTelephonyState(state: Int, rawNumber: String?) {
        if (app == null || telephonyListener == null) return
        val now = SystemClock.elapsedRealtime()
        val number = rawNumber?.trim()?.takeIf { it.isNotEmpty() }
            ?: pendingNumber?.takeIf { now - it.second < NUMBER_MATCH_MS }?.first
        val initial = !telephonyPrimed
        telephonyPrimed = true
        val current = call
        var event: String? = null
        val next = when (state) {
            TelephonyManager.CALL_STATE_RINGING -> when (current.phase) {
                CallPhase.RINGING -> current.withNumber(number)
                else -> {
                    // Call waiting: keep the call in progress (its caller and duration) to restore later.
                    heldCall = current.takeIf { it.phase == CallPhase.ACTIVE }
                    CallState(CallPhase.RINGING, number = number, startedAt = now)
                }
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> when (current.phase) {
                CallPhase.RINGING -> {
                    val held = heldCall
                    heldCall = null
                    val waiting = current.withNumber(number)
                    // Android reports off-hook whether the waiting call was answered or declined/missed. Keep
                    // the first call unless the dialer's ongoing-call notification already names the new caller
                    // (and that name is not the first call's, which the notification fallback may have copied).
                    if (held != null && (waiting.display == held.display || !sameCaller(callNotification?.second, waiting))) {
                        event = "waiting call ${waiting.display ?: "(number hidden)"} over or declined (Android does not say which); showing ${held.display ?: "the first call"} again"
                        held
                    } else waiting.copy(phase = CallPhase.ACTIVE, connectedAt = now)
                }
                CallPhase.ACTIVE -> current.withNumber(number)
                else -> CallState(CallPhase.ACTIVE, outgoing = !initial, number = number, startedAt = now, connectedAt = now)
            }
            else -> {
                heldCall = null
                if (current.inCall) current.copy(phase = CallPhase.ENDED, endedAt = now) else current
            }
        }
        if (next.number != null) pendingNumber = null
        setCall(next, event)
    }

    /** Whether a dialer notification [title] names [call]'s caller (contact name, or the same digits). */
    private fun sameCaller(title: String?, call: CallState): Boolean {
        val text = title?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        if (call.name != null && text.equals(call.name.trim(), ignoreCase = true)) return true
        val digits = text.filter(Char::isDigit)
        val number = call.number?.filter(Char::isDigit).orEmpty()
        return digits.length >= 6 && number.length >= 6 && (digits.endsWith(number.takeLast(10)) || number.endsWith(digits.takeLast(10)))
    }

    private fun CallState.withNumber(number: String?) = if (this.number == null && number != null) copy(number = number) else this

    private fun onCallNumber(number: String) {
        val current = call
        when {
            current.inCall && current.number == null -> setCall(current.copy(number = number))
            // Not in a call yet, or a waiting call whose broadcast beat its RINGING callback.
            current.number != number -> pendingNumber = number to SystemClock.elapsedRealtime()
        }
    }

    /** [event] replaces the default log line for a phase change. */
    private fun setCall(proposed: CallState, event: String? = null) {
        // Without contacts or call-log access the dialer's call notification still names the caller.
        val next = if (proposed.inCall && proposed.display == null) callNotification?.let { proposed.copy(name = it.second) } ?: proposed else proposed
        val previous = call
        if (next == previous) return
        call = next
        if (next.phase != previous.phase) {
            log("Call: " + (event ?: when (next.phase) {
                CallPhase.RINGING -> "incoming ${next.display ?: "(number hidden)"}"
                CallPhase.ACTIVE -> if (previous.phase == CallPhase.RINGING) "answered" else if (next.outgoing) "outgoing ${next.display ?: "(number hidden)"}" else "in progress"
                CallPhase.ENDED -> "ended after ${next.durationS()} s"
                CallPhase.IDLE -> "idle"
            }))
            endedTimer?.let(Ui::cancel); endedTimer = null
            if (next.phase == CallPhase.ENDED) endedTimer = Ui.postDelayed(ENDED_HOLD_MS) {
                endedTimer = null
                if (call.phase == CallPhase.ENDED) setCall(CallState())
            }
        }
        val number = next.number
        if (number != null && number != previous.number && next.name == null) lookupName(number)
        changed()
    }

    /** ContactsContract.PhoneLookup off the main thread; applied only if the call is still that number. */
    private fun lookupName(number: String) {
        val ctx = app ?: return
        if (!access.contacts) return
        scope?.launch {
            val name = withContext(Dispatchers.IO) {
                runCatching {
                    val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
                    ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                        ?.use { if (it.moveToFirst()) it.getString(0) else null }
                }.getOrNull()
            }?.trim()?.takeIf { it.isNotEmpty() } ?: return@launch
            if (call.number == number && call.phase != CallPhase.IDLE) setCall(call.copy(name = name))
        }
    }

    /** Accept/reject from the handlebar or UI. Needs ANSWER_PHONE_CALLS; returns whether Android took it. */
    @Suppress("DEPRECATION") // acceptRingingCall/endCall are the only non-system way (deprecated, still honoured).
    fun callAction(action: CallAction): Boolean {
        val ctx = app ?: return false.also { log("Call ${action.name.lowercase()}: phone link is off") }
        if (!access.answerCalls) { log("Call ${action.name.lowercase()}: allow \"Answer phone calls\" for Chetooth"); return false }
        val telecom = ctx.getSystemService(TelecomManager::class.java) ?: return false
        return try {
            when (action) {
                CallAction.ACCEPT -> {
                    if (call.phase != CallPhase.RINGING && telephonyListener != null) { log("Accept call: nothing is ringing"); return false }
                    telecom.acceptRingingCall(); true
                }
                CallAction.REJECT -> telecom.endCall()
            }
        } catch (e: Exception) {
            log("Call ${action.name.lowercase()} failed: ${e.javaClass.simpleName}"); false
        }
    }

    // --- Media ---

    private class Tracked(val controller: MediaController, val callback: MediaController.Callback)

    private var sessionManager: MediaSessionManager? = null
    /** Registered controllers by token; [order] is the system's priority order (most recent first). */
    private val tracked = HashMap<MediaSession.Token, Tracked>()
    private var order: List<MediaSession.Token> = emptyList()
    private var currentToken: MediaSession.Token? = null
    private var mediaWarned = false
    private val labels = HashMap<String, String>()
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> Ui.run { onSessions(list.orEmpty()) } }

    private fun connectMedia(ctx: Context) {
        if (mediaSessionsAvailable) return
        val manager = ctx.getSystemService(MediaSessionManager::class.java) ?: return
        val component = ComponentName(ctx, MapsNavigationListener::class.java)
        try {
            manager.addOnActiveSessionsChangedListener(sessionsListener, component, Ui.handler)
            sessionManager = manager
            mediaSessionsAvailable = true
            mediaWarned = false
            onSessions(manager.getActiveSessions(component).orEmpty())
            log("Music: following ${order.size} media session(s)")
        } catch (e: SecurityException) {
            if (!mediaWarned) log("Music needs notification access for Chetooth (Settings → Notification access)")
            mediaWarned = true
        }
    }

    private fun disconnectMedia() {
        sessionManager?.let { runCatching { it.removeOnActiveSessionsChangedListener(sessionsListener) } }
        sessionManager = null
        tracked.values.forEach { runCatching { it.controller.unregisterCallback(it.callback) } }
        tracked.clear(); order = emptyList(); currentToken = null
        mediaSessionsAvailable = false
    }

    private fun onSessions(list: List<MediaController>) {
        if (sessionManager == null) return
        val tokens = list.map { it.sessionToken }
        (tracked.keys - tokens.toSet()).forEach { token ->
            tracked.remove(token)?.let { runCatching { it.controller.unregisterCallback(it.callback) } }
        }
        // Keep the instance a callback was registered on; a fresh instance cannot unregister it.
        list.filter { it.sessionToken !in tracked }.forEach { controller ->
            val callback = controllerCallback(controller.sessionToken)
            controller.registerCallback(callback, Ui.handler)
            tracked[controller.sessionToken] = Tracked(controller, callback)
        }
        order = tokens
        refreshMedia()
    }

    private fun controllerCallback(token: MediaSession.Token) = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            // Follow whichever app starts playing.
            if (state?.state == PlaybackState.STATE_PLAYING && token != currentToken) currentToken = token
            refreshMedia()
        }
        override fun onMetadataChanged(metadata: MediaMetadata?) = refreshMedia()
        override fun onSessionDestroyed() {
            tracked.remove(token)?.let { runCatching { it.controller.unregisterCallback(it.callback) } }
            order = order - token
            if (currentToken == token) currentToken = null
            refreshMedia()
        }
    }

    private fun isPlaying(controller: MediaController) = controller.playbackState?.state == PlaybackState.STATE_PLAYING

    /** Current = the followed session if still playing, else the first playing one, else the followed one, else the newest. */
    private fun currentController(): MediaController? {
        val sessions = order.mapNotNull(tracked::get).map { it.controller }
        val followed = currentToken?.let(tracked::get)?.controller
        val chosen = when {
            followed != null && isPlaying(followed) -> followed
            else -> sessions.firstOrNull(::isPlaying) ?: followed ?: sessions.firstOrNull()
        }
        currentToken = chosen?.sessionToken
        return chosen
    }

    private fun refreshMedia() {
        val next = currentController()?.let(::snapshot)
        val previous = media
        if (next == previous) return
        media = next
        when {
            next == null -> if (previous != null) log("Music: no media session")
            previous == null || next.title != previous.title || next.artist != previous.artist || next.packageName != previous.packageName ->
                log("Music: ${if (next.playing) "playing" else "paused"} \"${next.title}\" · ${next.artist.ifBlank { next.album }} (${next.appLabel})")
            next.playing != previous.playing -> log("Music: ${if (next.playing) "playing" else "paused"}")
        }
        changed()
    }

    private fun snapshot(controller: MediaController): MediaState {
        val metadata = controller.metadata
        val playback = controller.playbackState
        fun text(vararg keys: String) = keys.firstNotNullOfOrNull { key -> metadata?.getString(key)?.trim()?.takeIf { it.isNotEmpty() } }.orEmpty()
        return MediaState(
            packageName = controller.packageName,
            appLabel = label(controller.packageName),
            title = text(MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
            artist = text(MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
            album = text(MediaMetadata.METADATA_KEY_ALBUM),
            playbackState = playback?.state ?: PlaybackState.STATE_NONE,
            positionMs = playback?.position ?: 0L,
            positionAt = playback?.lastPositionUpdateTime ?: 0L,
            speed = playback?.playbackSpeed ?: 1f,
            durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.coerceAtLeast(0) ?: 0L
        )
    }

    private fun label(packageName: String): String = labels.getOrPut(packageName) {
        val pm = app?.packageManager
        runCatching { pm!!.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString() }.getOrNull()
            ?: packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
    }

    /** Music and volume from the handlebar or UI; returns whether something was sent to Android. */
    fun mediaAction(action: MediaAction): Boolean {
        val ctx = app ?: return false.also { log("Music ${action.name.lowercase()}: phone link is off") }
        val controls = currentController()?.transportControls
        val playing = media?.playing == true
        val done = runCatching {
            when (action) {
                MediaAction.PLAY_PAUSE -> controls?.run { if (playing) pause() else play() } ?: mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                MediaAction.PLAY -> controls?.play() ?: mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PLAY)
                MediaAction.PAUSE -> controls?.pause() ?: mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PAUSE)
                MediaAction.NEXT -> controls?.skipToNext() ?: mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_NEXT)
                MediaAction.PREVIOUS -> controls?.skipToPrevious() ?: mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                MediaAction.VOLUME_UP -> adjustVolume(raise = true)
                MediaAction.VOLUME_DOWN -> adjustVolume(raise = false)
                MediaAction.MUTE -> adjustVolume(raise = null)
            }
            true
        }.onFailure { log("Music ${action.name.lowercase()} failed: ${it.javaClass.simpleName}") }.isSuccess
        refreshVolume()
        return done
    }

    /** No session (app closed): a media key lets Android resume the last player, like a headset button. */
    private fun mediaKey(ctx: Context, code: Int) {
        val audio = ctx.getSystemService(AudioManager::class.java) ?: return
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    /**
     * The call stream while a call rings or is up, else music. [raise] null
     * toggles mute. No system volume panel: the rider is not looking at the phone.
     */
    private fun adjustVolume(raise: Boolean?) {
        val audio = audio() ?: return
        val inCall = call.inCall || audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION
        val stream = if (inCall) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC
        val direction = when (raise) {
            null -> AudioManager.ADJUST_TOGGLE_MUTE
            true -> if (audio.isStreamMute(stream)) AudioManager.ADJUST_UNMUTE else AudioManager.ADJUST_RAISE
            false -> AudioManager.ADJUST_LOWER
        }
        audio.adjustStreamVolume(stream, direction, 0)
    }

    /**
     * VOICE_START: ACTION_VOICE_COMMAND. Android 10+ silently drops activity
     * starts from the background (a foreground service or notification listener is no exemption), and
     * startActivity does not throw then, so success only means "asked while Chetooth was visible".
     */
    fun openVoiceAssistant(): Boolean {
        val ctx = app ?: return false
        return runCatching { ctx.startActivity(Intent(Intent.ACTION_VOICE_COMMAND).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { log("Voice assistant unavailable: ${it.javaClass.simpleName}") }.isSuccess
    }

    /** Ruta has a visible activity, so Android lets it start one (see [openVoiceAssistant]). */
    private fun appVisible(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
            info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
    }

    /**
     * Performs one decoded handlebar (0x0306) event: call
     * and music volume map to the same action. Returns a short description for the log.
     */
    fun handlebar(event: HardControlEvent): String {
        fun result(ok: Boolean) = if (ok) "" else " (not done)"
        return when (event) {
            HardControlEvent.CALL_ACCEPT -> "accept call" + result(callAction(CallAction.ACCEPT))
            HardControlEvent.CALL_REJECT -> (if (call.phase == CallPhase.RINGING) "reject call" else "end call") + result(callAction(CallAction.REJECT))
            HardControlEvent.CALL_VOLUME_UP, HardControlEvent.MUSIC_VOLUME_UP -> "volume up" + result(mediaAction(MediaAction.VOLUME_UP))
            HardControlEvent.CALL_VOLUME_DOWN, HardControlEvent.MUSIC_VOLUME_DOWN -> "volume down" + result(mediaAction(MediaAction.VOLUME_DOWN))
            HardControlEvent.MUSIC_PLAY_PAUSE -> "play/pause" + result(mediaAction(MediaAction.PLAY_PAUSE))
            HardControlEvent.MUSIC_NEXT -> "next track" + result(mediaAction(MediaAction.NEXT))
            HardControlEvent.MUSIC_PREVIOUS -> "previous track" + result(mediaAction(MediaAction.PREVIOUS))
            HardControlEvent.MUSIC_MUTE -> "mute" + result(mediaAction(MediaAction.MUTE))
            HardControlEvent.VOICE_START -> {
                val visible = appVisible()
                "voice assistant" + when {
                    !openVoiceAssistant() -> " (not done)"
                    !visible -> " (may be blocked by Android while Chetooth is in the background)"
                    else -> ""
                }
            }
            HardControlEvent.VOICE_CANCEL -> "voice cancel (ignored)"
        }
    }

    // --- Notification listener hooks (MapsNavigationListener; main thread) ---

    fun listenerConnected(service: NotificationListenerService) = Ui.run {
        val all = runCatching { service.activeNotifications.orEmpty() }.getOrDefault(emptyArray())
        callNotification = all.lastOrNull { isCallNotification(it) }?.let { it.key to title(it) }
        // Messages already on screen are not new: only a later post with a newer message alerts.
        messageStamps.clear()
        all.forEach { messageStamps[it.key] = messageStamp(it) }
        recount(service, all)
        app?.let { if (!mediaSessionsAvailable) connectMedia(it) }
    }

    fun listenerDisconnected() = Ui.run {
        callNotification = null
        if (counts != Counts()) { counts = Counts(); changed() }
    }

    fun notificationPosted(service: NotificationListenerService, sbn: StatusBarNotification) = Ui.run {
        if (isCallNotification(sbn)) {
            callNotification = sbn.key to title(sbn)
            if (call.inCall && call.display == null) setCall(call)
        }
        val kind = countable(service, sbn)
        if (kind != null) recount(service)
        if (kind == Countable.MESSAGE) announceMessage(service, sbn)
        // A new player posted its media notification before the sessions callback could be set up.
        if (!mediaSessionsAvailable && sbn.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) app?.let(::connectMedia)
    }

    fun notificationRemoved(service: NotificationListenerService, sbn: StatusBarNotification) = Ui.run {
        if (callNotification?.first == sbn.key) callNotification = null
        messageStamps.remove(sbn.key)
        if (countable(service, sbn) != null) recount(service)
    }

    private enum class Countable { MISSED_CALL, MESSAGE }

    private val dialers = setOf(
        "com.google.android.dialer", "com.android.dialer", "com.android.incallui", "com.android.phone",
        "com.android.server.telecom", "com.samsung.android.dialer", "com.android.contacts"
    )
    private val messengers = setOf(
        "com.whatsapp", "com.whatsapp.w4b", "com.google.android.apps.messaging", "com.android.mms",
        "com.samsung.android.messaging", "org.telegram.messenger", "org.thoughtcrime.securesms", "com.facebook.orca"
    )
    private val whatsapp = setOf("com.whatsapp", "com.whatsapp.w4b")
    private val missedText = Regex("""missed\s+(voice\s+|video\s+)?calls?""", RegexOption.IGNORE_CASE)
    private val missedTotal = Regex("""(\d+)\s+missed""", RegexOption.IGNORE_CASE)
    private var defaultSms: String? = null

    private fun isDialer(pkg: String) = pkg in dialers || pkg.contains("dialer") || pkg.contains("incallui") || pkg.contains("telecom")

    private fun isCallNotification(sbn: StatusBarNotification) =
        sbn.notification.category == Notification.CATEGORY_CALL && isDialer(sbn.packageName) && title(sbn).let { it.isNotBlank() && !it.contains("call", ignoreCase = true) }

    private fun title(sbn: StatusBarNotification) = sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
    private fun text(sbn: StatusBarNotification) = sbn.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()

    /**
     * Missed call: CATEGORY_MISSED_CALL from anyone, or "missed … call" from a dialer or WhatsApp.
     * Message: CATEGORY_MESSAGE, or a titled notification from the default SMS app or a known
     * messenger. Ongoing/foreground notifications (calls in progress, sync) never count.
     */
    private fun countable(context: Context, sbn: StatusBarNotification): Countable? {
        val pkg = sbn.packageName
        if (pkg == context.packageName || pkg == MapsNavigationListener.MAPS) return null
        val notification = sbn.notification
        val category = notification.category
        if (category == "missed_call") return Countable.MISSED_CALL // Notification.CATEGORY_MISSED_CALL (API 31).
        if ((isDialer(pkg) || pkg in whatsapp) && (missedText.containsMatchIn(title(sbn)) || missedText.containsMatchIn(text(sbn)))) return Countable.MISSED_CALL
        if (notification.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0) return null
        if (category == Notification.CATEGORY_CALL) return null
        if (category == Notification.CATEGORY_MESSAGE) return Countable.MESSAGE
        val sms = defaultSms ?: runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull().orEmpty().also { defaultSms = it }
        return if ((pkg in messengers || pkg == sms) && title(sbn).isNotEmpty()) Countable.MESSAGE else null
    }

    /** Latest message time per notification key (no names): a re-post with the same time is not a new message. */
    private val messageStamps = object : LinkedHashMap<String, Long>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 200
    }
    private const val MESSAGE_FRESH_MS = 60_000L

    /** Newest MessagingStyle message time, else the notification's `when`, else its post time. */
    @Suppress("DEPRECATION")
    private fun messageStamp(sbn: StatusBarNotification): Long {
        val messages = sbn.notification.extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        val newest = messages?.mapNotNull { (it as? Bundle)?.getLong("time", 0L)?.takeIf { t -> t > 0 } }?.maxOrNull()
        return newest ?: sbn.notification.`when`.takeIf { it > 0 } ?: sbn.postTime
    }

    /**
     * "Show who messaged": a message notification that is new (a newer stamp than this key had, and
     * from the last minute) goes to the message listeners as app + sender. Group summaries are
     * skipped (their child carries the sender). Reads only the title, the conversation title and
     * the group flag, never EXTRA_TEXT or the messages' text. Only known messengers and the default
     * SMS app: other apps' CATEGORY_MESSAGE titles are often content ("Rs 5,000 credited"), so they
     * only count.
     */
    private fun announceMessage(service: NotificationListenerService, sbn: StatusBarNotification) {
        val notification = sbn.notification
        val pkg = sbn.packageName
        val sms = defaultSms ?: runCatching { Telephony.Sms.getDefaultSmsPackage(service) }.getOrNull().orEmpty().also { defaultSms = it }
        if (pkg !in messengers && pkg != sms) return
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val stamp = messageStamp(sbn)
        val previous = messageStamps.put(sbn.key, stamp)
        if (previous != null && stamp <= previous) return
        if (System.currentTimeMillis() - stamp > MESSAGE_FRESH_MS) return
        if (messageListeners.isEmpty()) return
        val extras = notification.extras
        val label = runCatching { service.packageManager.let { pm -> pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } }.getOrNull()
        val notice = MessageAlerts.notice(
            pkg, label, pkg == sms,
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            conversationTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString(),
            group = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)
        )
        messageListeners.toList().forEach { it(notice) }
    }

    /** How many calls/messages one notification stands for ("3 missed calls", MessagingStyle history, badge number). */
    private fun amount(sbn: StatusBarNotification, kind: Countable): Int {
        val notification = sbn.notification
        val n = when (kind) {
            Countable.MISSED_CALL -> (missedTotal.find(title(sbn)) ?: missedTotal.find(text(sbn)))?.groupValues?.get(1)?.toIntOrNull()
                ?: notification.number
            Countable.MESSAGE -> messageCount(notification).takeIf { it > 0 } ?: notification.number
        }
        return n.coerceIn(1, 99)
    }

    /** MessagingStyle messages carried by the notification (WhatsApp/Messages keep the unread ones). */
    @Suppress("DEPRECATION")
    private fun messageCount(notification: Notification): Int = notification.extras.getParcelableArray(Notification.EXTRA_MESSAGES)?.size ?: 0

    /**
     * Counts from what is on screen now rather than +1 per post (which would drift up on every
     * re-post). Per notification group, children are counted and the summary only when
     * it is alone.
     */
    private fun recount(service: NotificationListenerService, active: Array<out StatusBarNotification>? = null) {
        val all = active ?: runCatching { service.activeNotifications.orEmpty() }.getOrNull() ?: return
        var missed = 0
        var messages = 0
        all.mapNotNull { sbn -> countable(service, sbn)?.let { sbn to it } }
            .groupBy { it.first.groupKey }
            .values.forEach { group ->
                val children = group.filter { it.first.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
                children.ifEmpty { group }.forEach { (sbn, kind) ->
                    if (kind == Countable.MISSED_CALL) missed += amount(sbn, kind) else messages += amount(sbn, kind)
                }
            }
        val next = Counts(missed, messages, available = true)
        if (next == counts) return
        if (next.missedCalls != counts.missedCalls || next.unreadMessages != counts.unreadMessages) log("Counts: $missed missed call(s), $messages unread message(s)")
        counts = next
        changed()
    }
}
