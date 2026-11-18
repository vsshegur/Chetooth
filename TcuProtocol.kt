package dev.codesurgen.ruta

import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

/**
 * BLE protocol of the scooter's telematics unit (TCU), as documented in docs/PROTOCOL.md from
 * interoperability testing. Only plaintext channels are used: TCU info, heartbeat, vehicle state
 * (service one), the cluster channel (service three) and stats (service four). Service-two
 * commands are encrypted with a cloud-issued key and are deliberately never used.
 */
object Tcu {
    val INFO_SERVICE: UUID = UUID.fromString("1e5af1a3-fab3-4e33-aafb-331e781ed789")
    val COMMAND_SERVICE: UUID = UUID.fromString("02ce005e-2c40-455d-a079-976d3a35c630")
    val TCU_INFO = uuid16(0x0101)
    val OS_INFO = uuid16(0x0102)
    val PHONE_HEARTBEAT = uuid16(0x0103)
    val TCU_HEARTBEAT = uuid16(0x0104)
    val VEHICLE_STATE = uuid16(0x0105)
    val STATS_REQUEST = uuid16(0x0401)
    val STATS_UPDATE = uuid16(0x0402)
    val CCCD: UUID = uuid16(0x2902)

    val ESYNC_STATE = uuid16(0x0106)
    /**
     * Service three ("ICBT", the cluster channel): looked up by characteristic UUID. All writes
     * are with response; 0x0306 is the only one the TCU sends (read + indicate).
     */
    val ICBT_SERVICE: UUID = UUID.fromString("76f2f660-db96-4e05-a871-53d0e4524e50")
    val PHONE_STATE = uuid16(0x0301)
    val CALLER_INFO = uuid16(0x0302)
    val NAV_INFO = uuid16(0x0303)
    val MUSIC_INFO = uuid16(0x0304)
    val MUSIC_META = uuid16(0x0305)
    /** Handlebar buttons (indicate). Subscribing on every connection also enables turn-by-turn. */
    val ICBT_CONTROL = uuid16(0x0306)
    val VOICE_ASSIST = uuid16(0x0307)

    const val TAG_BMS = 1
    const val TAG_VEHICLE_STATUS = 13
    const val OS_ANDROID: Byte = 1
    /**
     * Stats periods tried in order. On a VX2 (live test, TCU 0.2.88) 200 streams at ~2 Hz,
     * while 0 returns one frame and stops, so 0 is the fallback. Periods above 255 are not used:
     * the period bytes are not a plain big-endian u16 on every firmware (see [statsRequest]).
     */
    val BMS_PERIODS_MS = listOf(200L, 0L)

    fun uuid16(value: Int): UUID = UUID.fromString(String.format(Locale.ROOT, "%08x-0000-1000-8000-00805f9b34fb", value))

    /** Phone heartbeat: 16-bit big-endian counter. */
    fun heartbeat(counter: Int): ByteArray = byteArrayOf((counter shr 8).toByte(), counter.toByte())

    /**
     * TCU heartbeat: all bytes (up to 4) as one big-endian number; fewer than 2 bytes count as 0.
     * The phone always replies with value + 1, so this never returns null.
     */
    fun heartbeatValue(value: ByteArray): Int =
        if (value.size < 2) 0 else value.takeLast(minOf(value.size, 4)).fold(0) { acc, byte -> (acc shl 8) or (byte.toInt() and 0xFF) }

    /**
     * Stats subscription `[tag, enable, msb, lsb]`. The "msb" byte is effectively always 0 (the
     * value is shifted by 56), so only periods 0..255 encode as intended.
     */
    fun statsRequest(tag: Int, enable: Boolean, periodMs: Long): ByteArray {
        require(periodMs % 100 == 0L) { "Period must be a multiple of 100 ms" }
        require(periodMs in 0..255) { "Only the low byte of the period is carried" }
        return byteArrayOf(tag.toByte(), if (enable) 1 else 0, (periodMs shr 56).toByte(), periodMs.toByte())
    }
}

/** Cluster turn symbols (byte 4 of 0x0303). Blinking variants are code | 0x20; Ruta never sends them. */
object NavTurn {
    const val WRONG_WAY = 0x42; const val SLIGHT_LEFT = 0x43; const val SLIGHT_RIGHT = 0x44
    const val SHARP_LEFT = 0x45; const val SHARP_RIGHT = 0x46; const val STRAIGHT = 0x47
    const val DESTINATION = 0x48; const val LEFT = 0x49; const val RIGHT = 0x4A
    const val RAMP_LEFT = 0x4B; const val RAMP_RIGHT = 0x4C; const val ROUNDABOUT_RIGHT = 0x4E
    const val U_TURN_LEFT = 0x4F; const val U_TURN_RIGHT = 0x50; const val FORK_LEFT = 0x51
    const val FORK_RIGHT = 0x52; const val FERRY_TRAIN = 0x54; const val ROUNDABOUT_LEFT = 0x55
    const val MERGE = 0x56; const val KEEP_RIGHT = 0x58; const val FERRY = 0x59; const val KEEP_LEFT = 0x5A
}

/**
 * One turn-by-turn update for characteristic 0x0303 (27 bytes, written with response): turn
 * distance in metres below 1 km, else tenths of a km; remaining distance always tenths of a km;
 * arrival clock in 12-hour form with an AM/PM flag; roundabout exit and speed limit 0. Layout in
 * docs/PROTOCOL.md.
 */
data class NavFrame(
    val active: Boolean,
    val turn: Int,
    val distanceMeters: Int,
    val nextTurn: Int,
    val arrivalHour24: Int,
    val arrivalMinute: Int,
    val remainingMeters: Int,
    val remainingMinutes: Int,
    val road: String
) {
    /** Exactly what the 14-byte road field carries (ASCII, sanitised, cut), for the app's TFT preview. */
    val roadText: String get() = String(roadBytes(road), Charsets.US_ASCII).trimEnd('\u0000')

    fun encode(): ByteArray {
        val bytes = ByteArray(27)
        val metres = distanceMeters.coerceAtLeast(0)
        val (unit, distance) = if (metres < 1000) 1 to metres else 0 to (metres / 100)
        val hour12 = (arrivalHour24 % 12).let { if (it == 0) 12 else it }
        val pm = if (arrivalHour24 % 24 >= 12) 1 else 0
        val remaining = remainingMinutes.coerceIn(0, 15 * 60 + 59)
        bytes[0] = (unit or ((if (active) 1 else 0) shl 2) or (hour12 shl 4)).toByte()
        bytes[1] = arrivalMinute.coerceIn(0, 59).toByte()
        bytes[2] = (distance.coerceAtMost(65535) shr 8).toByte(); bytes[3] = distance.coerceAtMost(65535).toByte()
        bytes[4] = turn.toByte()
        bytes[5] = (pm shl 4).toByte()
        bytes[6] = (remaining / 60).toByte()
        bytes[7] = nextTurn.toByte()
        val tenths = (remainingMeters.coerceAtLeast(0) / 100).coerceAtMost(65535)
        bytes[8] = (tenths shr 8).toByte(); bytes[9] = tenths.toByte()
        bytes[10] = (remaining % 60).toByte()
        bytes[11] = 0; bytes[12] = 0
        roadBytes(road).copyInto(bytes, 13)
        return bytes
    }

    companion object {
        /** Characters outside `[\p{Alnum}\s+]` → '_', US-ASCII, 14 bytes, zero padded (see [IcbtText]). */
        fun roadBytes(road: String): ByteArray = IcbtText.encode(road, 14, "_")
        /** Exit frame: metres, not active, 12 AM, distance 0, straight, empty road. */
        val EXIT = NavFrame(false, NavTurn.STRAIGHT, 0, NavTurn.STRAIGHT, 0, 0, 0, 0, "") // 12 AM → byte 0 = 0xC1.
    }
}

/**
 * What the Navigate card may claim about the cluster. A queued 0x0303 write proves nothing:
 * SHOWING needs one acknowledged by the TCU within [FRESH_MS] (frames go out at least every 2 s).
 */
enum class NavDisplay {
    UNAVAILABLE, SHOWING, REJECTED, PREPARING;

    companion object {
        const val FRESH_MS = 5_000L
        /** [ackedAt] 0 = no acknowledged frame yet; [error] = last final 0x0303 failure, cleared by an acknowledgement. */
        fun of(available: Boolean, sending: Boolean, ackedAt: Long, error: Int?, now: Long): NavDisplay = when {
            !available -> UNAVAILABLE
            sending && ackedAt > 0 && now - ackedAt in 0..FRESH_MS -> SHOWING
            error != null -> REJECTED
            else -> PREPARING
        }
    }
}

/**
 * Insertion rule of ScooterConnection's GATT priority lane (2.1 F4: turn-by-turn frames), pure so
 * host tests can check its invariants. [queue] is the op queue with the running op (if any) at
 * its head. [op] goes directly after the running op (first when none runs), and queued ops of the
 * same lane that are not running are dropped (a newer frame replaces a stale one). It never
 * overtakes a marker. While an op of the same lane is running, one other queued op goes first, so
 * a stream of frames alternates with the rest of the queue instead of starving it (heartbeat
 * replies included).
 */
object PriorityLane {
    fun <T> insert(queue: List<T>, op: T, running: T?, sameLane: (T) -> Boolean, isMarker: (T) -> Boolean): List<T> {
        val ops = queue.filterNot { it !== running && sameLane(it) }.toMutableList()
        var index = if (running != null) 1 else 0
        index = maxOf(index, ops.indexOfLast(isMarker) + 1)
        if (running != null && sameLane(running)) index++
        ops.add(index.coerceAtMost(ops.size), op)
        return ops
    }
}

/** Call state in 0x0301. ENDED is held ~2 s, then NONE. */
enum class TcuCallState(val code: Int) {
    NONE(0), INCOMING(1), OUTGOING(2), ACTIVE(3), ENDED(4), CONFERENCE(5);

    /** Caller info (0x0302) is sent only in these states, right after 0x0301. */
    val showsCaller: Boolean get() = this == INCOMING || this == OUTGOING || this == ACTIVE
}

/**
 * Smartphone state for 0x0301 (8 bytes, written with response), sent at 1 Hz for the whole
 * connection: it is what tells the cluster a phone is connected (connected flag always 1) and
 * syncs its clock (RTC sync = 1, local h:m:s). [missedCallAlert] = missedCount > 0 and
 * [smsFlag] = smsCount > 0. Every field is clamped to its bit width (an unmasked SMS count of 16
 * would spill into the missed-call nibble), and [callDurationS] is sent only while [callState] is
 * ACTIVE.
 */
data class PhoneStatus(
    val batteryPercent: Int?, val signalLevel: Int, val hour: Int, val minute: Int, val second: Int,
    val headset: Boolean = false,
    val missedCallAlert: Boolean = false,
    val callState: TcuCallState = TcuCallState.NONE,
    val smsFlag: Boolean = false,
    val smsCount: Int = 0,
    val missedCount: Int = 0,
    val callDurationS: Int = 0
) {
    fun encode(): ByteArray {
        val bars = when {
            batteryPercent == null || batteryPercent <= 0 || batteryPercent > 100 -> 4
            batteryPercent < 20 -> 0; batteryPercent < 40 -> 1; batteryPercent < 60 -> 2; batteryPercent < 80 -> 3
            else -> 4
        }
        val duration = if (callState == TcuCallState.ACTIVE) callDurationS.coerceIn(0, 65535) else 0
        return byteArrayOf(
            (bit(headset) or (bit(missedCallAlert) shl 1) or 0x04 or (bars shl 3)).toByte(), // bit2 phone connected
            (signalLevel.coerceIn(0, 4) or (callState.code shl 3) or (bit(smsFlag) shl 6)).toByte(),
            (0x01 or (second.coerceIn(0, 59) shl 1)).toByte(), // rtcSync + seconds
            minute.coerceIn(0, 59).toByte(), hour.coerceIn(0, 23).toByte(),
            (smsCount.coerceIn(0, 15) or (missedCount.coerceIn(0, 15) shl 4)).toByte(),
            (duration shr 8).toByte(), duration.toByte() // big-endian seconds
        )
    }
}

private fun bit(value: Boolean) = if (value) 1 else 0

/**
 * Text fields of the cluster frames (0x0302 caller, 0x0303 road, 0x0305 music): US-ASCII,
 * truncated to the field and zero-padded (no terminator). ASCII letters, digits, whitespace and
 * '+' are kept; anything else is replaced (caller and road: "_") or removed (music: ""), and other
 * non-ASCII letters and digits become '?'. This works per code point with explicit classes, so
 * the JVM tests see the phone's bytes. Accented Latin letters fold to their base letter (é → e,
 * not '?'), and any whitespace (tab, newline, no-break space) becomes a plain space.
 */
object IcbtText {
    fun encode(text: String, length: Int, replacement: String): ByteArray =
        sanitize(text, replacement).toByteArray(Charsets.US_ASCII).copyOf(length)

    /** Pure-ASCII result, one char per kept code point. */
    fun sanitize(text: String, replacement: String): String {
        val composed = Normalizer.normalize(text, Normalizer.Form.NFC) // "e\u0301" → "é", so it can fold.
        val out = StringBuilder(composed.length)
        var i = 0
        while (i < composed.length) {
            val original = composed.codePointAt(i)
            i += Character.charCount(original)
            val cp = fold(original)
            when {
                cp in '0'.code..'9'.code || cp in 'A'.code..'Z'.code || cp in 'a'.code..'z'.code || cp == '+'.code -> out.append(cp.toChar())
                cp == ' '.code || cp in 0x09..0x0D || (cp >= 128 && Character.isSpaceChar(cp)) -> out.append(' ')
                cp >= 128 && (Character.isAlphabetic(cp) || Character.isDigit(cp)) -> out.append('?')
                else -> out.append(replacement)
            }
        }
        return out.toString()
    }

    /** é → e, Å → A: a non-ASCII code point whose decomposition is one ASCII char plus accents. */
    private fun fold(cp: Int): Int {
        if (cp < 128) return cp
        val parts = Normalizer.normalize(String(Character.toChars(cp)), Normalizer.Form.NFD)
        val accentsOnly = parts.drop(1).all { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
        return if (parts[0].code < 128 && accentsOnly) parts[0].code else cp
    }
}

/**
 * Caller shown on the cluster during a call: 0x0302 (14 bytes, written with response). Ruta
 * uses the single-field form ([second] empty: one 14-byte field); a non-empty [second] splits it
 * into two 7-byte halves. Disallowed characters become '_'. Never send an empty [first].
 */
data class CallerInfo(val first: String, val second: String = "") {
    fun encode(): ByteArray =
        if (second.isEmpty()) IcbtText.encode(first, LENGTH, "_")
        else IcbtText.encode(first, LENGTH / 2, "_") + IcbtText.encode(second, LENGTH / 2, "_")

    companion object {
        const val LENGTH = 14

        /**
         * The contact name when resolved, else the number. Numbers keep only digits and '+' so
         * "+91 98765 43210" fits in 14 bytes (with its spaces the last digit would be cut). A [name]
         * that is really a formatted number (the dialer notification's title for an unsaved caller)
         * is cleaned the same way. Null when there is nothing to show (do not send).
         */
        fun forCall(name: String?, number: String?): CallerInfo? {
            val display = name?.trim().orEmpty()
            if (display.isNotEmpty() && !display.equals("unknown", ignoreCase = true))
                return CallerInfo(if (PHONE_LIKE.matches(display)) digitsOf(display) else display)
            val digits = digitsOf(number.orEmpty())
            return if (digits.isEmpty()) null else CallerInfo(digits)
        }

        /** Only digits, '+', spaces and the usual separators, with at least 3 digits. */
        private val PHONE_LIKE = Regex("""^(?=(?:\D*\d){3})[\d+\s\-().]+$""")

        private fun digitsOf(text: String) = text.filter { it in '0'..'9' || it == '+' }
    }
}

/**
 * Music state for 0x0304 (3 bytes, written with response).
 * Byte 0: volume 0..15 in bits 3-6 (cluster scale 0..10) and bit 7 = 0 while playing, 1 otherwise
 * (inverted). Byte 1: track progress percent (0..255). Byte 2: bit 0 = a session is active
 * (playing, buffering or paused). Metadata-config and reserved bits stay 0. Not sent while a call
 * is in progress.
 */
data class MusicInfo(val active: Boolean, val playing: Boolean, val volume: Int, val elapsedPercent: Int = 0) {
    fun encode(): ByteArray = byteArrayOf(
        ((volume.coerceIn(0, 15) shl 3) or (if (playing) 0 else 0x80)).toByte(),
        elapsedPercent.coerceIn(0, 255).toByte(),
        bit(active).toByte()
    )

    companion object {
        /** Cluster volume scale (0..10 on the scooters tested). */
        const val VOLUME_STEPS = 10

        /**
         * From android.media.session.PlaybackState.STATE_*: PLAYING(3)/BUFFERING(6) → active +
         * playing, PAUSED(2) → active + paused, anything else → inactive + paused. The transient
         * FAST_FORWARDING(4), REWINDING(5), CONNECTING(8) and SKIPPING_*(9..11) also count as
         * playing, so the cluster does not flicker on every track skip. Null = no media session.
         */
        fun of(playbackState: Int?, volume: Int, elapsedPercent: Int = 0): MusicInfo = when (playbackState) {
            3, 6, 4, 5, 8, 9, 10, 11 -> MusicInfo(active = true, playing = true, volume = volume, elapsedPercent = elapsedPercent)
            2 -> MusicInfo(active = true, playing = false, volume = volume, elapsedPercent = elapsedPercent)
            else -> MusicInfo(active = false, playing = false, volume = volume, elapsedPercent = elapsedPercent)
        }

        /** Stream volume → 0..10, rounded (dividing integers first would only ever give 0 or 10). */
        fun volumeLevel(current: Int, max: Int): Int =
            if (max <= 0) 0 else (current * VOLUME_STEPS.toDouble() / max).roundToInt().coerceIn(0, VOLUME_STEPS)

        /** Track progress 0..100 for byte 1; 0 when the duration is unknown. */
        fun progressPercent(positionMs: Long, durationMs: Long): Int =
            if (durationMs <= 0) 0 else (positionMs.coerceAtLeast(0) * 100 / durationMs).toInt().coerceIn(0, 100)
    }
}

/**
 * Song for 0x0305 (28 bytes, written with response): title in bytes 0-13, artist in 14-27.
 * Disallowed characters are removed (not replaced, unlike caller info). [CLEAR] (28 zero bytes)
 * blanks the cluster when no session exists. Dedupe on [encode], not on the fields.
 */
data class MusicMeta(val title: String, val artist: String) {
    fun encode(): ByteArray = IcbtText.encode(title, FIELD, "") + IcbtText.encode(artist, FIELD, "")

    companion object {
        private const val FIELD = 14
        val CLEAR = MusicMeta("", "")

        /** Artist, else album, else ""; a blank artist also falls back to the album. */
        fun of(title: String?, artist: String?, album: String?) =
            MusicMeta(title.orEmpty(), artist?.takeIf { it.isNotBlank() } ?: album.orEmpty())
    }
}

/** Voice assistant state for 0x0307 (1 byte). */
enum class VoiceAssistState(val code: Int) {
    NO_ACTION(0), LISTENING(1), PROCESSING(2), RESPONDING(3), CANCEL(4), NEGATIVE_RESPONSE(5);

    fun encode(): ByteArray = byteArrayOf(code.toByte())
}

/** Handlebar actions decoded from 0x0306. */
enum class HardControlEvent {
    CALL_ACCEPT, CALL_REJECT, CALL_VOLUME_UP, CALL_VOLUME_DOWN, MUSIC_VOLUME_UP, MUSIC_VOLUME_DOWN,
    MUSIC_PLAY_PAUSE, MUSIC_NEXT, MUSIC_PREVIOUS, MUSIC_MUTE, VOICE_START, VOICE_CANCEL
}

/**
 * One 0x0306 indication. b0: callAction bits 0-3, callVolume 4-5, musicVolume 6-7. b1:
 * musicDataConfig 0-2, musicControl 3-5, smsTrigger 6-7 (both decoded but not acted on).
 * b2: voiceControl 0-2. Extra bytes are ignored.
 *
 * Every field is mapped on every frame and each mapped value is its own event, so one frame can
 * carry several. Value 0 is a real action for callVolume (up), musicVolume (up) and musicControl
 * (play/pause): 00 00 00 means three actions. What the TCU sends at rest is unverified; log real
 * frames before trusting a mapping.
 */
data class HardControlFrame(
    val callAction: Int, val callVolume: Int, val musicVolume: Int,
    val musicDataConfig: Int, val musicControl: Int, val smsTrigger: Int,
    val voiceControl: Int
) {
    /** In order: call action, call volume, music volume, music control, voice. */
    val events: List<HardControlEvent>
        get() = listOfNotNull(
            when (callAction) { 1 -> HardControlEvent.CALL_ACCEPT; 2 -> HardControlEvent.CALL_REJECT; else -> null },
            when (callVolume) { 0 -> HardControlEvent.CALL_VOLUME_UP; 1 -> HardControlEvent.CALL_VOLUME_DOWN; else -> null },
            when (musicVolume) { 0 -> HardControlEvent.MUSIC_VOLUME_UP; 1 -> HardControlEvent.MUSIC_VOLUME_DOWN; else -> null },
            when (musicControl) {
                0 -> HardControlEvent.MUSIC_PLAY_PAUSE; 1 -> HardControlEvent.MUSIC_NEXT
                2 -> HardControlEvent.MUSIC_PREVIOUS; 3 -> HardControlEvent.MUSIC_MUTE; else -> null
            },
            when (voiceControl) { 1 -> HardControlEvent.VOICE_START; 2 -> HardControlEvent.VOICE_CANCEL; else -> null }
        )

    /** Inverse of [parse] (fields masked to their widths); for tests and building expected frames. */
    fun encode(): ByteArray = byteArrayOf(
        ((callAction and 15) or ((callVolume and 3) shl 4) or ((musicVolume and 3) shl 6)).toByte(),
        ((musicDataConfig and 7) or ((musicControl and 7) shl 3) or ((smsTrigger and 3) shl 6)).toByte(),
        (voiceControl and 7).toByte()
    )

    companion object {
        const val MIN_LENGTH = 3

        /** Every field at a value that maps to no action (F0 38 00). Not necessarily what the TCU sends at rest. */
        val NO_ACTION = HardControlFrame(0, 3, 3, 0, 7, 0, 0)

        /** Null for frames shorter than 3 bytes (ignored). */
        fun parse(data: ByteArray): HardControlFrame? {
            if (data.size < MIN_LENGTH) return null
            val b0 = data[0].toInt() and 0xFF; val b1 = data[1].toInt() and 0xFF; val b2 = data[2].toInt() and 0xFF
            return HardControlFrame(
                callAction = b0 and 15, callVolume = b0 shr 4 and 3, musicVolume = b0 shr 6 and 3,
                musicDataConfig = b1 and 7, musicControl = b1 shr 3 and 7, smsTrigger = b1 shr 6 and 3,
                voiceControl = b2 and 7
            )
        }
    }
}

/**
 * Drops a 0x0306 indication identical to the previous one within [windowMs]; the window slides
 * with each repeat, so a burst of identical frames (one press) acts once. Not thread-safe: call
 * from one thread.
 */
class HardControlDebouncer(private val windowMs: Long = 300) {
    private var last: ByteArray? = null
    private var lastAt = 0L

    /** True when [data] should be acted on. [now] is a monotonic clock in ms. */
    fun accept(data: ByteArray, now: Long): Boolean {
        val repeat = last?.contentEquals(data) == true && now - lastAt in 0 until windowMs
        last = data.copyOf(); lastAt = now
        return !repeat
    }
}

data class TcuInfo(val imei: String, val vin: String, val firmware: String, val bleProtocol: String, val hardware: String)

data class VehicleState(
    val ignitionOn: Boolean, val immobilised: Boolean, val handleLocked: Boolean,
    val rideMode: Int, val regenLevel: Int, val accelerationLevel: Int, val speedLimit: Int,
    val time: Long
)

/** Raw service-four BMS frame (tag 1); field order in docs/PROTOCOL.md, units inferred by [BmsDecoder]. */
data class BmsFrame(
    val soh: Int, val soc: Int, val packTemp: Int, val chargeTimeRaw: Int, val bmsStatus: Int,
    val pack1Status: Int, val pack2Status: Int,
    val pack1Soh: Int, val pack1Soc: Int, val pack1Soe: Int, val pack1Temp: Int,
    val pack2Soh: Int, val pack2Soc: Int, val pack2Soe: Int, val pack2Temp: Int
)

object TcuParser {
    private fun u8(data: ByteArray, i: Int) = data[i].toInt() and 0xFF
    private fun u16(data: ByteArray, i: Int) = (u8(data, i) shl 8) or u8(data, i + 1)
    private fun ascii(data: ByteArray, from: Int, length: Int) =
        String(data, from, length, Charsets.US_ASCII).trim { it <= ' ' || it == '\u0000' }

    /** TCU info (0x0101): 53+ bytes; IMEI 15, VIN 17, firmware 3, BLE protocol 4, hardware version 1, part number 13. */
    fun info(data: ByteArray): TcuInfo? {
        if (data.size < 53) return null
        return TcuInfo(
            imei = ascii(data, 0, 15), vin = ascii(data, 15, 17),
            firmware = "${u8(data, 32)}.${u8(data, 33)}.${u8(data, 34)}",
            bleProtocol = "${u8(data, 36)}.${u8(data, 37)}.${u8(data, 38)}",
            hardware = (ascii(data, 39, 1) + " " + ascii(data, 40, 13)).trim()
        )
    }

    /** Vehicle state (0x0105): 6 bytes (byte 5 is used). */
    fun vehicleState(data: ByteArray, time: Long): VehicleState? {
        if (data.size < 6) return null
        val flags = u8(data, 0)
        return VehicleState(
            ignitionOn = flags shr 7 and 1 == 1, immobilised = flags shr 6 and 1 == 1,
            handleLocked = (flags shr 2 and 15) == 1,
            rideMode = u8(data, 2) shr 4, regenLevel = u8(data, 3) shr 4, accelerationLevel = u8(data, 3) and 15,
            speedLimit = u8(data, 4), time = time
        )
    }

    /** Service-four BMS frame: tag 1 then 25 bytes (26 in total; byte 25 is used). */
    fun bms(data: ByteArray): BmsFrame? {
        if (data.size < 26 || u8(data, 0) != Tcu.TAG_BMS) return null
        return BmsFrame(
            soh = u16(data, 1), soc = u16(data, 3), packTemp = u16(data, 5),
            chargeTimeRaw = (u8(data, 7) shl 4) or (u8(data, 8) shr 4), bmsStatus = u8(data, 8) and 15,
            pack1Status = u8(data, 9) shr 4, pack2Status = u8(data, 9) and 15,
            pack1Soh = u16(data, 10), pack1Soc = u16(data, 12), pack1Soe = u16(data, 14), pack1Temp = u16(data, 16),
            pack2Soh = u16(data, 18), pack2Soc = u16(data, 20), pack2Soe = u16(data, 22), pack2Temp = u16(data, 24)
        )
    }
}

fun ByteArray.hex(): String = joinToString(" ") { String.format(Locale.ROOT, "%02X", it.toInt() and 0xFF) }
