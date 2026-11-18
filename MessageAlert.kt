package dev.codesurgen.ruta

/**
 * "Show who messaged" (More › Alerts, opt-in, default off): while the scooter is linked and no
 * media plays, a new message from a messaging app puts the app and the sender on the cluster's
 * music line (0x0305 [MusicMeta]: title = app, artist = sender) for [MessageAlerts.SHOW_MS].
 * Only names: the message text is never read for this, and a sender that is only a phone number
 * shows as [MessageAlerts.NO_NAME]. Pure Kotlin (host tests); PhoneBridge extracts the notice and
 * ScooterConnection drives [MessageAlerter].
 */
data class MessageNotice(val app: String, val sender: String) {
    /** What 0x0305 carries: both fields ASCII-folded and cut to 14 characters. */
    val meta: MusicMeta get() = MusicMeta(app, sender)
}

object MessageAlerts {
    /** How long one alert stays on the music line. */
    const val SHOW_MS = 8_000L
    /** At most one alert per this interval; messages in between coalesce to the latest sender. */
    const val MIN_GAP_MS = 10_000L
    /** A handlebar play/pause this soon after an alert timed out still only dismisses (the rider saw it). */
    const val PRESS_GRACE_MS = 1_500L
    /** Size of each 0x0305 field. */
    const val FIELD = 14
    /** Sender line when Android gives no usable name (a bare number, a summary, non-Latin script). */
    const val NO_NAME = "New message"

    private val APPS = mapOf(
        "com.whatsapp" to "WhatsApp", "com.whatsapp.w4b" to "WA Business",
        "com.google.android.apps.messaging" to "SMS", "com.android.mms" to "SMS", "com.samsung.android.messaging" to "SMS",
        "org.telegram.messenger" to "Telegram", "org.thoughtcrime.securesms" to "Signal", "com.facebook.orca" to "Messenger"
    )

    /** ≥ 6 digits: a phone number, not a name ("+91 98765 43210", "~ 98765 43210"). */
    private val NUMBER = Regex("""(?:\D*\d){6}""")
    /** "Alex (2 messages)" → "Alex". */
    private val COUNT_SUFFIX = Regex("""\s*\(\d+\s+[^)]*\)\s*$""")
    /** Summaries instead of a sender: "3 new messages", "5 messages from 2 chats", "New message". */
    private val SUMMARY = Regex("""^(?:\d+\s+)?(?:new\s+)?messages?\b""", RegexOption.IGNORE_CASE)

    /**
     * Title field: a known messenger's short name, "SMS" for the default SMS app, else the app's
     * [label] (Android's), else "Message". ASCII, at most [FIELD] characters.
     */
    fun appName(packageName: String, label: String?, defaultSms: Boolean = false): String =
        fit(APPS[packageName] ?: (if (defaultSms) "SMS" else null) ?: label.orEmpty()).ifEmpty { "Message" }

    /**
     * Artist field: in a group chat the conversation title (what Android gives as the group's
     * name), else the notification title (the sender). A phone number, a message-count summary or
     * the app's own name gives [NO_NAME]; "Alex (2 messages)" gives "Alex". ASCII, at most [FIELD].
     */
    fun sender(title: String?, conversationTitle: String?, group: Boolean, app: String): String {
        val raw = (if (group) conversationTitle?.takeIf { it.isNotBlank() } ?: title else title)?.trim().orEmpty()
        val name = raw.replace(COUNT_SUFFIX, "").trim()
        if (name.isEmpty() || NUMBER.containsMatchIn(name) || SUMMARY.containsMatchIn(name)) return NO_NAME
        if (name.equals(app, ignoreCase = true)) return NO_NAME
        val fitted = fit(name)
        return if (fitted.any { it.isLetterOrDigit() }) fitted else NO_NAME
    }

    fun notice(packageName: String, label: String?, defaultSms: Boolean, title: String?, conversationTitle: String?, group: Boolean): MessageNotice {
        val app = appName(packageName, label, defaultSms)
        return MessageNotice(app, sender(title, conversationTitle, group, app))
    }

    /**
     * ASCII as the music field carries it (accents folded, other characters dropped, see
     * [IcbtText]), single spaces, cut to [FIELD] without a trailing space. Non-Latin letters
     * become nothing rather than '?'.
     */
    fun fit(text: String): String =
        IcbtText.sanitize(text, "").replace('?', ' ').replace(Regex("""\s+"""), " ").trim().take(FIELD).trimEnd()
}

/**
 * When a sender alert shows on the music line and when it goes. Inputs are a monotonic clock in ms
 * and the current [Conditions]; [showing] is what 0x0305 should carry instead of the music (null =
 * the normal music state: empty, or the paused track). Not thread-safe: call from one thread.
 *
 *  - [message]: shown at once when allowed and no alert was shown in the last [MessageAlerts.MIN_GAP_MS];
 *    otherwise queued (a burst keeps only the latest sender) or ignored when not allowed.
 *  - [tick] (1 Hz and on every phone change): clears after [MessageAlerts.SHOW_MS], at once when music
 *    starts, a call rings, the switch goes off or the link goes; shows a queued alert once the gap allows.
 *  - [press] (handlebar play/pause): dismisses a showing alert and reports the press consumed, so it
 *    does not start music; also right after a timeout ([MessageAlerts.PRESS_GRACE_MS]).
 */
class MessageAlerter(
    private val showMs: Long = MessageAlerts.SHOW_MS,
    private val minGapMs: Long = MessageAlerts.MIN_GAP_MS,
    private val graceMs: Long = MessageAlerts.PRESS_GRACE_MS
) {
    /** [enabled]: the switch is on (and no demo); [ready]: the link is READY with 0x0305. */
    data class Conditions(val enabled: Boolean, val ready: Boolean, val playing: Boolean, val inCall: Boolean) {
        val allowed: Boolean get() = enabled && ready && !playing && !inCall
    }

    enum class Outcome { SHOWN, QUEUED, IGNORED, CLEARED_TIMEOUT, CLEARED_MUSIC, CLEARED_CALL, CLEARED_OFF }

    /** What [message]/[tick]/[press] did; [notice] is the alert shown or cleared. */
    data class Change(val outcome: Outcome, val notice: MessageNotice?)

    var showing: MessageNotice? = null; private set
    var pending: MessageNotice? = null; private set
    private var shownAt = 0L
    private var lastShownAt: Long? = null
    private var timedOutAt: Long? = null

    /** What 0x0305 should carry now, or null for the normal music state. */
    val meta: MusicMeta? get() = showing?.meta

    fun message(notice: MessageNotice, now: Long, conditions: Conditions): Change {
        if (!conditions.allowed) return Change(Outcome.IGNORED, notice)
        val last = lastShownAt
        if (showing != null || (last != null && now - last < minGapMs)) {
            pending = notice
            return Change(Outcome.QUEUED, notice)
        }
        return show(notice, now)
    }

    /** Null when nothing changed. */
    fun tick(now: Long, conditions: Conditions): Change? {
        val current = showing
        if (current != null) {
            val reason = when {
                !conditions.enabled || !conditions.ready -> Outcome.CLEARED_OFF
                conditions.inCall -> Outcome.CLEARED_CALL
                conditions.playing -> Outcome.CLEARED_MUSIC
                now - shownAt >= showMs -> Outcome.CLEARED_TIMEOUT
                else -> return null
            }
            showing = null
            if (reason == Outcome.CLEARED_TIMEOUT) timedOutAt = now else { pending = null; timedOutAt = null }
            return Change(reason, current)
        }
        val queued = pending ?: return null
        if (!conditions.allowed) { pending = null; return null }
        val last = lastShownAt
        if (last != null && now - last < minGapMs) return null
        return show(queued, now)
    }

    /** True when the press was for the alert (dismissed, or it just timed out): do not pass it on. */
    fun press(now: Long): Boolean {
        val timedOut = timedOutAt
        timedOutAt = null
        if (showing != null) { showing = null; pending = null; return true }
        return timedOut != null && now - timedOut in 0..graceMs
    }

    fun reset() {
        showing = null; pending = null; lastShownAt = null; timedOutAt = null; shownAt = 0L
    }

    private fun show(notice: MessageNotice, now: Long): Change {
        showing = notice; pending = null; shownAt = now; lastShownAt = now; timedOutAt = null
        return Change(Outcome.SHOWN, notice)
    }

    /** Log line for [change], without any name: "Message alert shown (WhatsApp)". Null when not worth a line. */
    fun describe(change: Change): String? {
        val app = change.notice?.app.orEmpty()
        return when (change.outcome) {
            Outcome.SHOWN -> "Message alert shown ($app)"
            Outcome.CLEARED_MUSIC -> "Message alert cleared (music started)"
            Outcome.CLEARED_CALL -> "Message alert cleared (call)"
            Outcome.CLEARED_OFF -> "Message alert cleared"
            Outcome.CLEARED_TIMEOUT, Outcome.QUEUED, Outcome.IGNORED -> null
        }
    }
}
