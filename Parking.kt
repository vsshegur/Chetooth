package dev.codesurgen.ruta

import android.content.Context

/**
 * Find my scooter (F-PARK): the one remembered parked spot. [RideTracker] saves it when a ride ends
 * or the scooter link ends (rules: [ParkingRules]); Home shows it while the scooter is not
 * connected. Kept in private SharedPreferences ("parking"), cached in memory after the first read;
 * every call is thread-safe and [addListener] callbacks run on the main thread.
 *
 * A newer spot replaces the older one; nothing else clears it except [forget]. Nothing is saved
 * while More › Alerts "Remember where I park" ([AlertKind.PARKING]) is off or the demo runs, and
 * while the demo runs [spot] is the demo's spot. Positions and places are never logged.
 */
object Parking {
    private const val PREFS = "parking"
    private const val KEY_LAT = "lat_bits"
    private const val KEY_LNG = "lng_bits"
    private const val KEY_ACCURACY = "accuracy_bits"
    private const val KEY_TIME = "time"
    private const val KEY_PLACE = "place"

    private val lock = Any()
    @Volatile private var loaded = false
    @Volatile private var cached: ParkedSpot? = null
    /** Main thread only. */
    private val listeners = linkedSetOf<() -> Unit>()

    /** The spot to show: the demo's while [Demo.active], else the saved one (null when none). */
    fun spot(context: Context): ParkedSpot? = if (Demo.active) Demo.parkedSpot else stored(context)

    /** The saved spot, whatever the demo shows (first call reads the prefs file; then memory only). */
    fun stored(context: Context): ParkedSpot? {
        if (!loaded) synchronized(lock) { if (!loaded) { cached = read(context); loaded = true } }
        return cached
    }

    /**
     * Saves [spot] when it is valid and not older than the saved one; false (nothing written) during
     * the demo, with "Remember where I park" off, or for an invalid or older spot. Any thread.
     */
    fun save(context: Context, spot: ParkedSpot): Boolean {
        if (Demo.active || !Alerts.enabled(context, AlertKind.PARKING) || !ParkingRules.valid(spot)) return false
        synchronized(lock) {
            val current = stored(context)
            if (current != null && spot.time < current.time) return false
            write(context, spot)
            cached = spot
        }
        changed()
        return true
    }

    /**
     * Adds the looked-up [place] to the saved spot taken at [time] (no-op when a newer spot replaced it).
     * Also while the demo runs: it only completes the rider's own spot, saved before the demo started.
     */
    fun setPlace(context: Context, time: Long, place: String) {
        val name = place.trim().ifEmpty { return }
        synchronized(lock) {
            val current = stored(context) ?: return
            if (current.time != time || current.place == name) return
            val next = current.copy(place = name)
            write(context, next)
            cached = next
        }
        changed()
    }

    /** Deletes the saved spot (e.g. when the rider turns "Remember where I park" off). */
    fun forget(context: Context) {
        synchronized(lock) {
            if (loaded && cached == null) return
            prefs(context).edit().clear().apply()
            cached = null
            loaded = true
        }
        changed()
    }

    /** Called on the main thread after the saved spot changes. Main thread only. */
    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    /** Google Maps walking directions to [spot]. */
    fun walkUrl(spot: ParkedSpot): String = ParkingRules.walkUrl(spot)

    private fun changed() = Ui.post { listeners.toList().forEach { it() } }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun read(context: Context): ParkedSpot? {
        val p = prefs(context)
        if (!p.contains(KEY_TIME)) return null
        val spot = ParkedSpot(
            lat = Double.fromBits(p.getLong(KEY_LAT, 0L)),
            lng = Double.fromBits(p.getLong(KEY_LNG, 0L)),
            accuracyM = Double.fromBits(p.getLong(KEY_ACCURACY, Double.NaN.toRawBits())),
            time = p.getLong(KEY_TIME, 0L),
            place = p.getString(KEY_PLACE, null)
        )
        return spot.takeIf { ParkingRules.valid(it) }
    }

    private fun write(context: Context, spot: ParkedSpot) {
        val editor = prefs(context).edit()
            .putLong(KEY_LAT, spot.lat.toRawBits())
            .putLong(KEY_LNG, spot.lng.toRawBits())
            .putLong(KEY_ACCURACY, spot.accuracyM.toRawBits())
            .putLong(KEY_TIME, spot.time)
        if (spot.place != null) editor.putString(KEY_PLACE, spot.place) else editor.remove(KEY_PLACE)
        editor.apply()
    }
}
