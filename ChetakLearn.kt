package dev.codesurgen.ruta

import kotlin.math.abs

/*
 * Chetooth: learning the Bajaj Chetak's Bluetooth format.
 *
 * Ruta decodes the Hero Vida from a documented layout (docs/PROTOCOL.md). Nobody has published the
 * Chetak's, so Chetooth learns it on the rider's own scooter. The link subscribes to every
 * notify/indicate characteristic and reads every readable one (it never writes), keeps the latest
 * message per "stream" ([StreamKey]), and when the rider types what the dashboard shows (battery %
 * or range km) it looks for a number in those messages that equals it: one byte, or two bytes in
 * either byte order, possibly ×10 or ×100. A second reading at a different value leaves (almost
 * always) one field, which is then remembered per scooter ([LearnedFields]).
 *
 * Pure Kotlin (no android.*), so tests/ChetakLearnTest.kt runs on the JVM.
 */

/** What can be taught. [tolerance]: dashboards round, so a match may be off by this much. */
enum class LearnField(val label: String, val minimum: Double, val maximum: Double, val tolerance: Double, val unit: String, val formats: List<NumFmt>) {
    SOC("Battery", 0.0, 100.0, 1.0, "%",
        listOf(NumFmt.U16LE_100, NumFmt.U16BE_100, NumFmt.U16LE_10, NumFmt.U16BE_10, NumFmt.U8, NumFmt.U8_HALF)),
    RANGE("Range", 0.0, 300.0, 2.0, "km",
        listOf(NumFmt.U16LE_10, NumFmt.U16BE_10, NumFmt.U16LE, NumFmt.U16BE, NumFmt.U8));

    fun key(): String = name.lowercase()
}

/** Ways a number can sit in a message. Lower [rank] = less likely to match by coincidence. */
enum class NumFmt(val label: String, val width: Int, val rank: Int) {
    U16LE_100("2 bytes LE ÷100", 2, 0),
    U16BE_100("2 bytes BE ÷100", 2, 0),
    U16LE_10("2 bytes LE ÷10", 2, 0),
    U16BE_10("2 bytes BE ÷10", 2, 0),
    U8("1 byte", 1, 1),
    // A whole number in 2 bytes usually also reads as 1 byte next to a zero byte; prefer the byte.
    U16LE("2 bytes LE", 2, 2),
    U16BE("2 bytes BE", 2, 2),
    U8_HALF("1 byte ÷2", 1, 3);

    fun decode(b: ByteArray, off: Int): Double? {
        if (off < 0 || off + width > b.size) return null
        val a = b[off].toInt() and 0xFF
        if (width == 1) return if (this == U8_HALF) a / 2.0 else a.toDouble()
        val c = b[off + 1].toInt() and 0xFF
        val le = a or (c shl 8)
        val be = (a shl 8) or c
        return when (this) {
            U16LE_100 -> le / 100.0
            U16BE_100 -> be / 100.0
            U16LE_10 -> le / 10.0
            U16BE_10 -> be / 10.0
            U16LE -> le.toDouble()
            U16BE -> be.toDouble()
            else -> null
        }
    }
}

/** One kind of message: a characteristic, a message length and optionally its first byte ("tag"). */
data class StreamKey(val uuid: String, val len: Int, val tag: Int?) {
    fun matches(uuid: String, value: ByteArray): Boolean =
        uuid.equals(this.uuid, ignoreCase = true) && value.size == len && (tag == null || (value[0].toInt() and 0xFF) == tag)

    fun encode(): String = "$uuid|$len|${tag ?: -1}"

    companion object {
        fun decode(s: String): StreamKey? {
            val p = s.split('|')
            if (p.size != 3) return null
            val len = p[1].toIntOrNull() ?: return null
            val tag = p[2].toIntOrNull() ?: return null
            return StreamKey(p[0].lowercase(), len, if (tag < 0) null else tag)
        }

        /** The keys a message is filed under: by length, and (2+ bytes) also by its first byte. */
        fun of(uuid: String, value: ByteArray): List<StreamKey> {
            val u = uuid.lowercase()
            val plain = StreamKey(u, value.size, null)
            return if (value.size >= 2) listOf(plain, StreamKey(u, value.size, value[0].toInt() and 0xFF)) else listOf(plain)
        }
    }
}

/** A field location: which messages, which byte, which format. */
data class Candidate(val stream: StreamKey, val offset: Int, val fmt: NumFmt) {
    fun read(uuid: String, value: ByteArray, field: LearnField): Double? =
        if (stream.matches(uuid, value)) fmt.decode(value, offset)?.takeIf { it in field.minimum..field.maximum } else null

    fun encode(): String = "${stream.encode()}|$offset|${fmt.name}"

    /** "0x1234 · 20-byte msg starting 0x01 · byte 5 · 2 bytes LE ÷100" */
    fun describe(): String = buildString {
        append(shortUuid(stream.uuid))
        append(" · ").append(stream.len).append("-byte msg")
        stream.tag?.let { append(" starting 0x").append("%02X".format(it)) }
        append(" · byte ").append(offset)
        append(" · ").append(fmt.label)
    }

    companion object {
        fun decode(s: String?): Candidate? {
            if (s.isNullOrBlank()) return null
            val p = s.split('|')
            if (p.size != 5) return null
            val key = StreamKey.decode(p.subList(0, 3).joinToString("|")) ?: return null
            val off = p[3].toIntOrNull() ?: return null
            val fmt = NumFmt.entries.firstOrNull { it.name == p[4] } ?: return null
            return Candidate(key, off, fmt)
        }
    }
}

/** One dashboard reading: the value the rider saw and the messages the scooter had just sent. */
class CalPoint(val value: Double, val at: Long, val snapshot: Map<StreamKey, ByteArray>) {
    /** "value;at;key=hex,key=hex" (keys contain no ';', ',' or '='). */
    fun encode(): String = "$value;$at;" + snapshot.entries.joinToString(",") { "${it.key.encode()}=${it.value.hexString()}" }

    companion object {
        fun decode(s: String): CalPoint? {
            val parts = s.split(';', limit = 3)
            if (parts.size != 3) return null
            val value = parts[0].toDoubleOrNull() ?: return null
            val at = parts[1].toLongOrNull() ?: return null
            val snap = LinkedHashMap<StreamKey, ByteArray>()
            if (parts[2].isNotEmpty()) for (entry in parts[2].split(',')) {
                val kv = entry.split('=')
                if (kv.size != 2) continue
                val key = StreamKey.decode(kv[0]) ?: continue
                val bytes = parseHex(kv[1]) ?: continue
                snap[key] = bytes
            }
            return CalPoint(value, at, snap)
        }

        fun encodeAll(points: List<CalPoint>): String = points.joinToString("\n") { it.encode() }

        fun decodeAll(s: String?): MutableList<CalPoint> =
            s.orEmpty().lines().filter { it.isNotBlank() }.mapNotNull { decode(it) }.toMutableList()
    }
}

class Ranked(val candidate: Candidate, val hits: Int, val total: Int, val error: Double)

object ChetakLearn {
    /** Latest messages older than this are left out of a new reading. */
    const val SNAPSHOT_MAX_AGE_MS = 120_000L
    /** At most this many first-byte variants are kept per characteristic and length (a data byte, not a tag, otherwise). */
    const val MAX_TAGS = 16

    fun matchesIn(point: CalPoint, field: LearnField): Map<Candidate, Double> {
        val out = HashMap<Candidate, Double>()
        for ((key, bytes) in point.snapshot) {
            for (off in bytes.indices) {
                if (key.tag != null && off == 0) continue // byte 0 is the tag itself
                for (f in field.formats) {
                    val v = f.decode(bytes, off) ?: continue
                    if (v < field.minimum || v > field.maximum) continue
                    val err = abs(v - point.value)
                    if (err <= field.tolerance) out[Candidate(key, off, f)] = err
                }
            }
        }
        return out
    }

    /** Best first: matched the most readings, least coincidental format, smallest error, earliest byte. */
    fun rank(points: List<CalPoint>, field: LearnField): List<Ranked> {
        if (points.isEmpty()) return emptyList()
        val per = points.map { matchesIn(it, field) }
        val all = HashSet<Candidate>()
        per.forEach { all.addAll(it.keys) }
        val ranked = all.map { c -> Ranked(c, per.count { c in it }, points.size, per.sumOf { it[c] ?: 0.0 }) }
        // The same field found with and without the first-byte filter: keep the simpler one when it does as well.
        val plainHits = HashMap<String, Int>()
        for (r in ranked) if (r.candidate.stream.tag == null) plainHits[plainKey(r.candidate)] = r.hits
        val kept = ranked.filter { r -> r.candidate.stream.tag == null || (plainHits[plainKey(r.candidate)] ?: -1) < r.hits }
        return kept.sortedWith(
            compareByDescending<Ranked> { it.hits }
                .thenBy { it.candidate.fmt.rank }
                .thenBy { it.error }
                .thenBy { it.candidate.stream.tag == null }
                .thenBy { it.candidate.offset }
        )
    }

    private fun plainKey(c: Candidate) = "${c.stream.uuid}|${c.stream.len}|${c.offset}|${c.fmt.name}"

    /** Readings with at least two different values (that is what makes a match trustworthy). */
    fun hasSpread(points: List<CalPoint>, field: LearnField): Boolean =
        points.map { it.value }.distinct().size >= 2 && (points.maxOf { it.value } - points.minOf { it.value }) > field.tolerance

    /**
     * The field every reading agrees on, once the readings have spread; null while it is ambiguous.
     * Candidates that matched every reading and read the same values in every reading (the same
     * byte seen as 1 or 2 bytes, say) count as one: the best ranked is returned.
     */
    fun confident(points: List<CalPoint>, field: LearnField): Candidate? {
        if (!hasSpread(points, field)) return null
        val full = rank(points, field).filter { it.hits == it.total }
        val top = full.firstOrNull()?.candidate ?: return null
        val same = full.all { r ->
            points.all { p ->
                val a = valueIn(p, top)
                val b = valueIn(p, r.candidate)
                a != null && b != null && abs(a - b) < 0.001
            }
        }
        return if (same) top else null
    }

    private fun valueIn(point: CalPoint, c: Candidate): Double? = point.snapshot[c.stream]?.let { c.fmt.decode(it, c.offset) }
}

/** Latest message per stream, as the link receives them (main thread only). */
class StreamBook {
    private class Stamped(val bytes: ByteArray, val at: Long)

    private val latest = LinkedHashMap<StreamKey, Stamped>()

    val size: Int get() = latest.size

    fun put(uuid: String, value: ByteArray, now: Long) {
        for (key in StreamKey.of(uuid, value)) {
            if (key.tag != null && key !in latest &&
                latest.keys.count { it.uuid == key.uuid && it.len == key.len && it.tag != null } >= ChetakLearn.MAX_TAGS) continue
            latest[key] = Stamped(value.copyOf(), now)
        }
    }

    /** Messages newer than [ChetakLearn.SNAPSHOT_MAX_AGE_MS], for a [CalPoint]. */
    fun snapshot(now: Long): Map<StreamKey, ByteArray> {
        val out = LinkedHashMap<StreamKey, ByteArray>()
        for ((k, v) in latest) if (now - v.at <= ChetakLearn.SNAPSHOT_MAX_AGE_MS) out[k] = v.bytes.copyOf()
        return out
    }

    /** What [candidate] reads in the newest matching message, or null. */
    fun read(candidate: Candidate, field: LearnField): Double? {
        val s = latest[candidate.stream] ?: return null
        return candidate.read(candidate.stream.uuid, s.bytes, field)
    }

    fun clear() = latest.clear()
}

// ---- helpers ----

private const val BASE_SUFFIX = "-0000-1000-8000-00805f9b34fb"

/** "0x2A19" for a Bluetooth base UUID, else the full UUID. */
fun shortUuid(uuid: String): String {
    val l = uuid.lowercase()
    return if (l.startsWith("0000") && l.endsWith(BASE_SUFFIX)) "0x" + l.substring(4, 8).uppercase() else l
}

fun ByteArray.hexString(sep: String = ""): String = joinToString(sep) { "%02X".format(it.toInt() and 0xFF) }

fun parseHex(s: String): ByteArray? {
    val clean = s.filter { !it.isWhitespace() }
    if (clean.length % 2 != 0) return null
    return try {
        ByteArray(clean.length / 2) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    } catch (_: NumberFormatException) {
        null
    }
}
