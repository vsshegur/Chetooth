package dev.codesurgen.ruta

import java.util.Locale
import kotlin.math.abs

/** Pack 0 is the scooter-level (combined) value; packs 1 and 2 are the individual batteries. */
enum class Metric(val title: String, val unit: String, val minimum: Double, val maximum: Double) {
    SOC("Charge", "%", 0.0, 100.0),
    HEALTH("Health", "%", 0.0, 100.0),
    ENERGY("Energy", "%", 0.0, 100.0),
    TEMPERATURE("Temperature", "°C", -40.0, 85.0),
    /** Raw BMS status code; never show it, map it with [BmsState.of]. */
    STATUS("State", "", 0.0, 15.0)
}

/**
 * Display state for a BMS status code (raw codes never reach the UI). VX2 evidence: 4 while the
 * scooter is on/driving on every pack; parked: pack 0 reports 0 and packs 1/2 report 2. Transient
 * codes (5, 7, 11 seen) and anything else are [UNKNOWN].
 */
enum class BmsState(val label: String) {
    DRIVE("Drive"), STANDBY("Standby"), UNKNOWN("—");

    companion object {
        fun of(code: Int?): BmsState = when (code) { 4 -> DRIVE; 0, 2 -> STANDBY; else -> UNKNOWN }
        fun of(code: Double?): BmsState =
            if (code == null || !code.isFinite() || code != Math.floor(code)) UNKNOWN else of(code.toInt())

        /** Scooter state from per-pack codes: any pack driving → [DRIVE], else any standby → [STANDBY]. */
        fun combined(codes: List<Double?>): BmsState {
            val states = codes.map(::of)
            return when {
                DRIVE in states -> DRIVE
                STANDBY in states -> STANDBY
                else -> UNKNOWN
            }
        }
    }
}

data class Reading(val pack: Int, val metric: Metric, val value: Double, val time: Long)

/**
 * Converts raw BMS frames into readings. The BLE BMS units are not documented anywhere, so
 * scales are inferred from evidence and
 * latched per scooter. A latch needs [LATCH_FRAMES] consecutive agreeing frames and is never
 * changed automatically afterwards (only [ScooterConnection.resetCalibration] clears it);
 * with a fixed scale a stray field simply falls out of range and is dropped on its own.
 *
 * Percent (SOC/SOH; SOE is never used as evidence): SOH 50–100 % is the strongest signal
 * (raw 50..100 → ÷1, 500..1000 → ÷10, 5000..10000 → ÷100); otherwise a SOC/SOH above 100
 * implies ÷10 or ÷100. Before a latch, the current frame's vote is used, or ÷1 if no field
 * gives evidence.
 * Temperature: any pack beyond 850 with every pack's raw/100 − 40 within −30..70 → 0.01 °C
 * with a −40 °C offset (unsigned; the VX2 sends 6930/6890 = 29.3/28.9 °C at ~29 °C ambient).
 * In that mode anything below −30 °C is an empty field, not a reading: raw 0 (−40 °C) is dropped
 * and a frame holding one does not vote; the 0x0028 placeholder (−39.6 °C) voids that pack (below).
 * Otherwise signed, no offset: beyond 85 → 0.1 °C (impossible
 * in whole °C); all packs within 20..60 → whole °C (at 0.1 °C that would be an implausible
 * 2–6 °C). Until latched, temperatures are shown only when the current frame itself votes.
 * Absent packs (no plausible SOC/SOH/SOE) emit nothing; all-zero frames are ignored.
 *
 * Plausibility ([ReadingRules], the same rules [HistoryCleaner] applies to stored history): a pack
 * that sends the 0x0028 temperature placeholder in the ÷100 mode, or whose SOE is far below its SOC,
 * is asleep and its whole part of the frame is dropped (it counts as absent and does not vote);
 * HEALTH below 50 % is dropped; single-sample SOC/SOE jumps are held back by a [SpikeFilter] that
 * lives as long as this decoder (one connection).
 */
class BmsDecoder(percentDivisor: Int? = null, temperatureDivisor: Int? = null) {
    var percentDivisor: Int? = percentDivisor?.takeIf { it in setOf(1, 10, 100) }; private set
    var temperatureDivisor: Int? = temperatureDivisor?.takeIf { it == 1 || it == 10 || it == 100 }; private set
    private var percentVote: Int? = null
    private var percentStreak = 0
    private var temperatureVote: Int? = null
    private var temperatureStreak = 0
    private val spikes = SpikeFilter()

    companion object {
        const val LATCH_FRAMES = 3
        /** In the ÷100 (−40 °C offset) mode, readings below this are placeholders (raw 0, 0x0028). */
        const val MIN_HUNDREDTHS_CELSIUS = -30.0
        /** Raw pack temperature a sleeping pack sends in the ÷100 mode (−39.6 °C); see [ReadingRules]. */
        const val PLACEHOLDER_TEMPERATURE_RAW = 0x0028
        /** All-ones / max values commonly used for "not available" in 8-16 bit CAN signals. */
        private val PERCENT_SENTINELS = setOf(0xFF, 0xFE, 0x3FF, 0x7FF, 0xFFF, 0x7FFF, 0xFFFE, 0xFFFF)
        private val TEMPERATURE_SENTINELS = setOf(0x7F, 0x80, 0xFE, 0xFF, 0x7FFF, 0x8000, 0xFF80, 0xFFFE, 0xFFFF)
    }

    private fun plausiblePercent(raw: Int) = raw in 1..10000 && raw !in PERCENT_SENTINELS

    private fun percentEvidence(frame: BmsFrame): Int? {
        val healths = listOf(frame.soh, frame.pack1Soh, frame.pack2Soh).filter(::plausiblePercent)
        when {
            healths.isNotEmpty() && healths.all { it in 50..100 } -> return 1
            healths.isNotEmpty() && healths.all { it in 500..1000 } -> return 10
            healths.isNotEmpty() && healths.all { it in 5000..10000 } -> return 100
        }
        val largest = listOf(frame.soc, frame.soh, frame.pack1Soc, frame.pack1Soh, frame.pack2Soc, frame.pack2Soh)
            .filter(::plausiblePercent).maxOrNull() ?: return null
        return when { largest <= 100 -> null; largest <= 1000 -> 10; else -> 100 }
    }

    private fun temperatureEvidence(raws: List<Int>): Int? {
        if (raws.isEmpty()) return null
        val signed = raws.map { it.toShort().toInt() }
        return when {
            // VX2 live: pack temps 0x1B12/0x1AEA at ~29 °C ambient, i.e. 0.01 °C offset by −40 °C.
            // A frame with an empty field (raw 0) does not vote; a 0x0028 (asleep) pack is left out first.
            raws.any { it > 850 } && raws.all { celsius(it, 100) in -30.0..70.0 } -> 100
            signed.any { kotlin.math.abs(it) > 85 } && signed.all { it / 10 in -30..70 } -> 10
            signed.all { it in 20..60 } -> 1
            else -> null
        }
    }

    /** ÷100 is unsigned 0.01 °C offset by −40 °C; ÷10 and ÷1 are signed without an offset. */
    private fun celsius(raw: Int, scale: Int): Double =
        if (scale == 100) (raw - 4000) / 100.0 else raw.toShort() / scale.toDouble()

    /**
     * ÷100 mode: latched, or (before a latch) another pack sends a raw temperature only that mode explains
     * (−30..70 °C there, beyond 100 °C in tenths or whole degrees).
     */
    private fun hundredths(frame: BmsFrame): Boolean = temperatureDivisor == 100 || (temperatureDivisor == null &&
        listOf(frame.packTemp, frame.pack1Temp, frame.pack2Temp).any { it in 1000..11000 })

    private fun temperatureRaw(frame: BmsFrame, pack: Int) = when (pack) { 0 -> frame.packTemp; 1 -> frame.pack1Temp; else -> frame.pack2Temp }

    /** The frame with [pack]'s fields emptied: the pack counts as absent (no readings, no votes). */
    private fun BmsFrame.withoutPack(pack: Int): BmsFrame = when (pack) {
        0 -> copy(soc = 0, soh = 0, packTemp = 0)
        1 -> copy(pack1Soc = 0, pack1Soh = 0, pack1Soe = 0, pack1Temp = 0)
        else -> copy(pack2Soc = 0, pack2Soh = 0, pack2Soe = 0, pack2Temp = 0)
    }

    fun readings(frame: BmsFrame, time: Long): List<Reading> {
        val percentFields = listOf(frame.soc, frame.soh, frame.pack1Soc, frame.pack1Soh, frame.pack1Soe, frame.pack2Soc, frame.pack2Soh, frame.pack2Soe)
        if (percentFields.all { it == 0 } && listOf(frame.packTemp, frame.pack1Temp, frame.pack2Temp).all { it == 0 }) return emptyList()
        // A sleeping pack's temperature placeholder voids its whole part of the frame (seen in a field export:
        // pack 1 SOE 0.12 % next to −39.6 °C on 33 rows). Raw 0 is only an empty temperature field.
        var f = frame
        if (hundredths(frame)) for (pack in 0..2) if (temperatureRaw(frame, pack) == PLACEHOLDER_TEMPERATURE_RAW) f = f.withoutPack(pack)

        val percentNow = percentEvidence(f)
        if (percentDivisor == null && percentNow != null) {
            percentStreak = if (percentNow == percentVote) percentStreak + 1 else 1
            percentVote = percentNow
            if (percentStreak >= LATCH_FRAMES) percentDivisor = percentNow
        }
        val divisor = (percentDivisor ?: percentNow ?: 1).toDouble()
        fun scaled(raw: Int): Double? = if (plausiblePercent(raw)) (raw / divisor).takeIf { it in 0.0..100.0 } else null
        // The same sleep shows as SOE far below SOC (stored history keeps only that half of the pair).
        if (ReadingRules.asleep(scaled(f.pack1Soc), scaled(f.pack1Soe))) f = f.withoutPack(1)
        if (ReadingRules.asleep(scaled(f.pack2Soc), scaled(f.pack2Soe))) f = f.withoutPack(2)

        val present = mapOf(
            0 to (plausiblePercent(f.soc) || plausiblePercent(f.soh)),
            1 to listOf(f.pack1Soc, f.pack1Soh, f.pack1Soe).any(::plausiblePercent),
            2 to listOf(f.pack2Soc, f.pack2Soh, f.pack2Soe).any(::plausiblePercent)
        )
        val out = mutableListOf<Reading>()
        fun add(pack: Int, metric: Metric, value: Double) {
            if (!value.isFinite() || value !in metric.minimum..metric.maximum) return
            if (metric == Metric.HEALTH && !ReadingRules.healthPlausible(value)) return
            if (spikes.accept(pack, metric, value, time)) out += Reading(pack, metric, value, time)
        }
        fun percent(pack: Int, metric: Metric, raw: Int) { scaled(raw)?.let { add(pack, metric, it) } }
        percent(0, Metric.SOC, f.soc); percent(0, Metric.HEALTH, f.soh)
        percent(1, Metric.SOC, f.pack1Soc); percent(1, Metric.HEALTH, f.pack1Soh); percent(1, Metric.ENERGY, f.pack1Soe)
        percent(2, Metric.SOC, f.pack2Soc); percent(2, Metric.HEALTH, f.pack2Soh); percent(2, Metric.ENERGY, f.pack2Soe)

        val temperatures = listOf(0 to f.packTemp, 1 to f.pack1Temp, 2 to f.pack2Temp)
            .filter { (pack, raw) -> present[pack] == true && raw !in TEMPERATURE_SENTINELS }
        val temperatureNow = temperatureEvidence(temperatures.map { it.second })
        if (temperatureDivisor == null && temperatureNow != null) {
            temperatureStreak = if (temperatureNow == temperatureVote) temperatureStreak + 1 else 1
            temperatureVote = temperatureNow
            if (temperatureStreak >= LATCH_FRAMES) temperatureDivisor = temperatureNow
        }
        (temperatureDivisor ?: temperatureNow)?.let { scale ->
            temperatures.forEach { (pack, raw) ->
                val value = celsius(raw, scale)
                if (scale != 100 || (raw != 0 && value >= MIN_HUNDREDTHS_CELSIUS)) add(pack, Metric.TEMPERATURE, value)
            }
        }

        if (present[0] == true) add(0, Metric.STATUS, f.bmsStatus.toDouble())
        if (present[1] == true) add(1, Metric.STATUS, f.pack1Status.toDouble())
        if (present[2] == true) add(2, Metric.STATUS, f.pack2Status.toDouble())
        return out
    }
}

/**
 * Plausibility rules for BMS readings, shared by the live [BmsDecoder] and the stored-history
 * clean-up ([HistoryCleaner], TelemetryStore v7). Evidence from a real-world export:
 * pack 1 SOE 0.12 % on the 33 frames that carried the −39.6 °C placeholder (SOC − SOE was 56 points
 * there, 9 points on the 1 189 other frames), HEALTH 10.25 % (pack 0), 2.69 % and 10.39 % (pack 1)
 * between 99.45/99.47 % neighbours, and one pack 2 SOC of 0.02 % between 56.59 % and 56.57 %.
 */
object ReadingRules {
    /** Pack health below this is a misread field (a pack this worn would not run the scooter). */
    const val MIN_HEALTH = 50.0
    /** SOE more than this many points below the same pack's SOC: the pack is asleep (placeholder frame). */
    const val MAX_ENERGY_BELOW_CHARGE = 25.0
    /** SOC/SOE moves larger than this within [JUMP_WINDOW_MS] of the last accepted value are suspect. */
    const val MAX_JUMP = 10.0
    const val JUMP_WINDOW_MS = 60_000L
    /** Consecutive agreeing suspect values that make a jump real (e.g. a swapped pack). */
    const val CONFIRM_SAMPLES = 3
    /** The 0x0028 placeholder as stored in ÷100 mode by 2.0 ((40 − 4000) / 100 °C). */
    const val PLACEHOLDER_CELSIUS = -39.6

    fun healthPlausible(percent: Double): Boolean = percent >= MIN_HEALTH

    /** A pack whose SOE is far below its SOC is asleep; its whole frame is a placeholder. */
    fun asleep(socPercent: Double?, energyPercent: Double?): Boolean =
        socPercent != null && energyPercent != null && socPercent - energyPercent > MAX_ENERGY_BELOW_CHARGE

    /** A stored temperature that is the sleeping-pack placeholder (not raw 0, which is −40 °C). */
    fun placeholderCelsius(celsius: Double): Boolean = abs(celsius - PLACEHOLDER_CELSIUS) < 0.05
}

/**
 * Holds back single-sample SOC/SOE glitches (pack 2 charge 56.59 → 0.02 → 56.57 %). A value more
 * than [ReadingRules.MAX_JUMP] points from the last accepted one of the same pack and metric, within
 * [ReadingRules.JUMP_WINDOW_MS] of it, is rejected — unless it is the [ReadingRules.CONFIRM_SAMPLES]th
 * consecutive rejected value in agreement (a real change, e.g. a swapped pack, or a glitch that
 * happened to be the first value). Once the last accepted value is older than the window, any value
 * is accepted. Other metrics pass through. Not thread-safe; one per decoder or history pass.
 */
class SpikeFilter {
    private class Accepted(val value: Double, val time: Long)
    private class Held(val value: Double, val count: Int)
    private val accepted = HashMap<Pair<Int, Metric>, Accepted>()
    private val held = HashMap<Pair<Int, Metric>, Held>()

    fun accept(pack: Int, metric: Metric, value: Double, time: Long): Boolean {
        if (metric != Metric.SOC && metric != Metric.ENERGY) return true
        val key = pack to metric
        val last = accepted[key]
        if (last != null && abs(value - last.value) > ReadingRules.MAX_JUMP && time - last.time in 0..ReadingRules.JUMP_WINDOW_MS) {
            val run = held[key]?.takeIf { abs(value - it.value) <= ReadingRules.MAX_JUMP }?.count ?: 0
            if (run + 1 < ReadingRules.CONFIRM_SAMPLES) { held[key] = Held(value, run + 1); return false }
        }
        held.remove(key)
        accepted[key] = Accepted(value, time)
        return true
    }

    fun clear() { accepted.clear(); held.clear() }
}

/** One row of TelemetryStore's readings table. */
data class StoredReading(val id: Long, val session: String, val time: Long, val pack: Int, val metric: Metric, val value: Double)

/**
 * Applies [ReadingRules] to stored history (TelemetryStore v7 migration): a pack frame (same
 * session, time and pack) holding the placeholder temperature, or asleep by SOE vs SOC, is dropped
 * whole; HEALTH below 50 % is dropped; SOC/SOE rows go through a [SpikeFilter] that restarts with
 * every session, as the live decoder restarts with every connection. Stored rows are a 30 s sample
 * of what the decoder saw, so this is the same rule set on coarser data. Feed rows ordered by pack,
 * time, session, id (as [rejected] sorts them), then call [finish].
 */
class HistoryCleaner {
    private val spikes = SpikeFilter()
    private var session: String? = null
    private val frame = ArrayList<StoredReading>()
    private val rejected = ArrayList<Long>()

    fun add(row: StoredReading) {
        val first = frame.firstOrNull()
        if (first != null && (first.pack != row.pack || first.time != row.time || first.session != row.session)) flush()
        frame += row
    }

    /** Ids of every rejected row; call once, after the last [add]. */
    fun finish(): List<Long> { flush(); return rejected.toList() }

    private fun flush() {
        if (frame.isEmpty()) return
        val first = frame[0]
        if (first.session != session) { spikes.clear(); session = first.session }
        val soc = frame.firstOrNull { it.metric == Metric.SOC }?.value
        val energy = frame.firstOrNull { it.metric == Metric.ENERGY }?.value
        if (frame.any { it.metric == Metric.TEMPERATURE && ReadingRules.placeholderCelsius(it.value) } || ReadingRules.asleep(soc, energy)) {
            frame.mapTo(rejected) { it.id }
        } else for (row in frame) {
            val keep = when (row.metric) {
                Metric.HEALTH -> ReadingRules.healthPlausible(row.value)
                Metric.SOC, Metric.ENERGY -> spikes.accept(row.pack, row.metric, row.value, row.time)
                else -> true
            }
            if (!keep) rejected += row.id
        }
        frame.clear()
    }

    companion object {
        /** Ids of the [rows] the live rules would have rejected; any order. */
        fun rejected(rows: Iterable<StoredReading>): Set<Long> {
            val cleaner = HistoryCleaner()
            rows.sortedWith(compareBy<StoredReading>({ it.pack }, { it.time }, { it.session }, { it.id })).forEach(cleaner::add)
            return cleaner.finish().toSet()
        }
    }
}

class BatteryAnalytics {
    private val latest = mutableMapOf<Pair<Int, Metric>, Reading>()
    fun accept(reading: Reading) { latest[reading.pack to reading.metric] = reading }
    fun clear() = latest.clear()
    fun reading(pack: Int, metric: Metric): Reading? = latest[pack to metric]
    fun fresh(pack: Int, metric: Metric, now: Long): Double? = reading(pack, metric)
        ?.takeIf { now - it.time in 0..90_000 }?.value
    fun imbalance(now: Long): Double? {
        val one = fresh(1, Metric.SOC, now) ?: return null
        val two = fresh(2, Metric.SOC, now) ?: return null
        return abs(one - two)
    }
}

fun number(value: Double, decimals: Int = 1): String = String.format(Locale.US, "%.${decimals}f", value)
