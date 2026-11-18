package dev.codesurgen.ruta

import android.content.Context
import android.content.SharedPreferences

/**
 * Chetooth: Android side of [ChetakLearn]. Holds what the Chetak link has received (newest message
 * per stream, newest value per characteristic) and, per scooter, the learned field locations and the
 * dashboard readings they were learned from. Main thread only.
 */
object ChetakData {
    /** Newest message per stream for the scooter currently linked. */
    val book = StreamBook()
    /** Newest value per characteristic UUID (lowercase), in first-seen order. */
    val lastByChar = LinkedHashMap<String, ByteArray>()
    val countByChar = HashMap<String, Int>()
    /** Reads/subscriptions the scooter refused, per characteristic: GATT status (-1 = Android could not start it). */
    val failures = LinkedHashMap<String, Int>()
    /** Short description of the scooter's services, from discovery. */
    var services: String = ""
    /** Address of the scooter the data above belongs to. */
    var mac: String? = null; private set
    /** True while a Chetak-mode link is READY. */
    var linked = false

    private val listeners = LinkedHashSet<() -> Unit>()
    private val decoders = HashMap<String, Candidate?>()

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences("chetak", Context.MODE_PRIVATE)

    private fun id(field: LearnField, mac: String) = "${field.key()}_${mac.uppercase()}"

    /** The link is (re)opening to [address]: data from another scooter is dropped. */
    fun attach(address: String) {
        if (mac.equals(address, ignoreCase = true)) return
        mac = address.uppercase()
        book.clear(); lastByChar.clear(); countByChar.clear(); failures.clear(); services = ""
        changed()
    }

    /** One message from the scooter. */
    fun ingest(uuid: String, value: ByteArray, now: Long) {
        val u = uuid.lowercase()
        book.put(u, value, now)
        lastByChar[u] = value.copyOf()
        countByChar[u] = (countByChar[u] ?: 0) + 1
        changed()
    }

    /** A read or subscription the scooter refused ([code] GATT status; 5/15/137 = needs encryption or pairing). */
    fun failed(uuid: String, code: Int) {
        failures[uuid.lowercase()] = code
        changed()
    }

    fun decoder(ctx: Context, field: LearnField, address: String? = mac): Candidate? {
        val m = address ?: return null
        val key = id(field, m)
        if (key !in decoders) decoders[key] = Candidate.decode(prefs(ctx).getString("dec_$key", null))
        return decoders[key]
    }

    fun setDecoder(ctx: Context, field: LearnField, candidate: Candidate?, address: String? = mac) {
        val m = address ?: return
        val key = id(field, m)
        decoders[key] = candidate
        val editor = prefs(ctx).edit()
        if (candidate == null) editor.remove("dec_$key") else editor.putString("dec_$key", candidate.encode())
        editor.apply()
        changed()
    }

    fun points(ctx: Context, field: LearnField, address: String? = mac): MutableList<CalPoint> {
        val m = address ?: return mutableListOf()
        return CalPoint.decodeAll(prefs(ctx).getString("pts_${id(field, m)}", null))
    }

    /**
     * Saves a dashboard reading of [value] with the messages received in the last 2 minutes. When the
     * readings now point at one field and none is set yet, it is adopted. Null = nothing received yet.
     */
    fun addPoint(ctx: Context, field: LearnField, value: Double, now: Long = System.currentTimeMillis()): CalPoint? {
        val m = mac ?: return null
        val snapshot = book.snapshot(now)
        if (snapshot.isEmpty()) return null
        val point = CalPoint(value, now, snapshot)
        val all = points(ctx, field, m).apply { add(point) }
        // Keep the newest few: old readings only make the stored list longer.
        val kept = all.takeLast(MAX_POINTS)
        prefs(ctx).edit().putString("pts_${id(field, m)}", CalPoint.encodeAll(kept)).apply()
        if (decoder(ctx, field, m) == null) ChetakLearn.confident(kept, field)?.let { setDecoder(ctx, field, it, m) }
        changed()
        return point
    }

    /** Forgets [field]'s readings and learned location for the current scooter. */
    fun reset(ctx: Context, field: LearnField) {
        val m = mac ?: return
        prefs(ctx).edit().remove("pts_${id(field, m)}").remove("dec_${id(field, m)}").apply()
        decoders.remove(id(field, m))
        changed()
    }

    /** Plain-text dump for sharing (services, learned fields, latest values). No addresses. */
    fun report(ctx: Context): String = buildString {
        append("Chetooth scooter data\n")
        for (field in LearnField.entries) {
            append(field.label).append(" field: ").append(decoder(ctx, field)?.encode() ?: "not set").append('\n')
            for (p in points(ctx, field)) append("  reading ").append(p.value).append(' ').append(field.unit).append(" · ").append(p.snapshot.size).append(" streams\n")
        }
        append('\n').append(services).append('\n')
        for ((u, v) in lastByChar) append(shortUuid(u)).append(" ×").append(countByChar[u] ?: 0).append(": ").append(v.hex()).append('\n')
        if (failures.isNotEmpty()) append("Refused: ").append(failures.entries.joinToString { "${shortUuid(it.key)} (GATT ${it.value})" }).append('\n')
    }

    fun addListener(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }
    private fun changed() { listeners.toList().forEach { it() } }

    private const val MAX_POINTS = 6
}
