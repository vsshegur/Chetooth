package dev.codesurgen.ruta

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.OutputStream
import kotlin.math.abs
import kotlin.math.min

/**
 * Numeric telemetry, rides and charging sessions; navigation destinations are never stored here.
 * Pack 0 = scooter total. Use [shared] so the UI and [RideService] share one connection pool. Every
 * call does disk I/O: call from a background thread. Retention ([prune]): readings 30 days; rides,
 * ride points and charging sessions 1 year.
 *
 * Demo mode (F-DEMO) uses a second, separate file ([demo], [DEMO_NAME]) with the same schema, filled
 * by [seedDemo] and deleted with [deleteDemo] when the demo ends; the real file is never opened for it.
 */
class TelemetryStore(context: Context, name: String = NAME) : SQLiteOpenHelper(context.applicationContext ?: context, name, null, VERSION) {
    init { setWriteAheadLoggingEnabled(true) }

    /** This is the demo history file, not the rider's. */
    val isDemo: Boolean = name == DEMO_NAME

    /** Set by [retire]: the file is about to be deleted, so it must not be opened (or re-created) again. */
    @Volatile private var retired = false

    override fun getReadableDatabase(): SQLiteDatabase {
        check(!retired) { "Demo history has ended" }
        return super.getReadableDatabase()
    }

    override fun getWritableDatabase(): SQLiteDatabase {
        check(!retired) { "Demo history has ended" }
        return super.getWritableDatabase()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE readings (id INTEGER PRIMARY KEY, session TEXT NOT NULL, time INTEGER NOT NULL, pack INTEGER NOT NULL, metric TEXT NOT NULL, value REAL NOT NULL)")
        db.execSQL("CREATE INDEX readings_time ON readings(time)")
        createV5(db)
        addPlacesVersion(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 (Bafang SDK metrics) never received real data; start clean with the TCU metrics.
        if (oldVersion < 2) { db.execSQL("DROP TABLE IF EXISTS readings"); onCreate(db); return }
        // v2: 1.0.4 stored 0.01 °C temperatures without the −40 °C offset (6930 → 69.3, not 29.3).
        // 1.0.3 never stored temperatures.
        if (oldVersion < 3) db.execSQL("UPDATE readings SET value = value - 40 WHERE metric='TEMPERATURE' AND value > 45")
        // v3: 1.0.4 also stored empty temperature fields (raw 0) as 0.0 °C, and 1.0.5 before its
        // latch could store them as −40 °C. Neither is a real reading in this climate.
        if (oldVersion < 4) db.execSQL("DELETE FROM readings WHERE metric='TEMPERATURE' AND (value = 0 OR value <= -39.9)")
        // v6 → v7 (2.1): BMS glitches reached the store (HEALTH 10.25 %, SOE 0.12 % next to the −39.6 °C
        // placeholder, a lone SOC of 0.02 %) and spiked the charts. The live decoder's rules
        // ([HistoryCleaner]) now run over stored history once. Before the v5 step (charging sessions are
        // recovered from clean charge history) and the v6 step (it deletes the placeholder temperatures
        // this pass uses to find a sleeping pack's whole frame).
        if (oldVersion < 7) {
            val removed = cleanReadings(db)
            DiagLog.append("History upgraded to v7: removed $removed implausible battery reading(s)")
        }
        // v4 → v5: rides, ride points and charging sessions; readings are kept. Charging sessions are
        // recovered from the stored SOC history (rides cannot be: there was no GPS track).
        if (oldVersion < 5) {
            createV5(db)
            val soc = db.rawQuery("SELECT time,value FROM readings WHERE pack=0 AND metric='SOC' ORDER BY time", null).use { c ->
                buildList { while (c.moveToNext()) add(ValueAt(c.getLong(0), c.getDouble(1))) }
            }
            RideMath.chargeSessions(soc).forEach { putChargeSession(db, it) }
        }
        // v5 → v6 (2.1): pack 1 sends raw 0x0028 temperature placeholders (−39.6 °C in the ÷100 mode);
        // no real reading is below −30 °C. Temperatures are rounded to 0.01 °C (older rows carry float
        // noise such as 29.299999999999997). Place names from 2.0 ("Greenfield, Northgate") are marked
        // for a new lookup in the street + area format; they stay visible until it succeeds.
        if (oldVersion < 6) {
            val placeholders = db.delete("readings", "metric='TEMPERATURE' AND value < -30", null)
            db.execSQL("UPDATE readings SET value = ROUND(value, 2) WHERE metric='TEMPERATURE'")
            addPlacesVersion(db)
            DiagLog.append("History upgraded to v6: removed $placeholders placeholder temperature reading(s)")
        }
    }

    /**
     * Deletes stored readings the live rules reject ([HistoryCleaner]: sleeping-pack frames, HEALTH
     * below 50 %, single-sample SOC/SOE jumps); returns the number of rows deleted. One streaming pass.
     */
    private fun cleanReadings(db: SQLiteDatabase): Int {
        val metrics = Metric.entries.associateBy { it.name }
        val cleaner = HistoryCleaner()
        db.rawQuery("SELECT id,session,time,pack,metric,value FROM readings ORDER BY pack,time,session,id", null).use { c ->
            while (c.moveToNext()) {
                val metric = metrics[c.getString(4)] ?: continue
                cleaner.add(StoredReading(c.getLong(0), c.getString(1), c.getLong(2), c.getInt(3), metric, c.getDouble(5)))
            }
        }
        val rejected = cleaner.finish()
        rejected.chunked(500).forEach { ids -> db.execSQL("DELETE FROM readings WHERE id IN (${ids.joinToString(",")})") }
        return rejected.size
    }

    /** rides.places_v: format of the stored place names; rows below [PLACES_VERSION] are looked up again. */
    private fun addPlacesVersion(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE rides ADD COLUMN places_v INTEGER NOT NULL DEFAULT 0")
    }

    private fun createV5(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS readings_series ON readings(pack, metric, time)")
        // finished = 0 while recording; an unfinished ride left by a killed process is recovered from its points.
        db.execSQL("CREATE TABLE IF NOT EXISTS rides (id INTEGER PRIMARY KEY, started INTEGER NOT NULL, ended INTEGER NOT NULL, " +
            "start_lat REAL NOT NULL DEFAULT 0, start_lng REAL NOT NULL DEFAULT 0, end_lat REAL NOT NULL DEFAULT 0, end_lng REAL NOT NULL DEFAULT 0, " +
            "start_place TEXT, end_place TEXT, distance_m REAL NOT NULL DEFAULT 0, duration_s INTEGER NOT NULL DEFAULT 0, " +
            "moving_s INTEGER NOT NULL DEFAULT 0, peak_kmh REAL NOT NULL DEFAULT 0, avg_kmh REAL NOT NULL DEFAULT 0, " +
            "soc_start REAL, soc_end REAL, finished INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE INDEX IF NOT EXISTS rides_started ON rides(started)")
        db.execSQL("CREATE TABLE IF NOT EXISTS ride_points (ride INTEGER NOT NULL, time INTEGER NOT NULL, lat REAL NOT NULL, lng REAL NOT NULL, " +
            "speed REAL NOT NULL, distance REAL NOT NULL, accuracy REAL NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS ride_points_ride ON ride_points(ride, time)")
        db.execSQL("CREATE TABLE IF NOT EXISTS charge_sessions (id INTEGER PRIMARY KEY, started INTEGER NOT NULL UNIQUE, ended INTEGER NOT NULL, " +
            "soc_start REAL NOT NULL, soc_end REAL NOT NULL)")
    }

    /** The shared instance is never closed (the service may still be writing); other instances close normally. */
    override fun close() { if (this !== instance) super.close() }

    /**
     * Demo history only: refuses every later call (a query still queued by a screen then fails instead
     * of re-creating the file) and closes; running queries finish first. Then [deleteDemo].
     */
    fun retire() {
        check(isDemo) { "Only the demo history can be retired" }
        retired = true
        super.close()
    }

    // ------------------------------------------------------------------ readings

    private val lastSaved = mutableMapOf<Pair<Int, Metric>, Reading>()
    private var session = ""
    @Synchronized fun newSession(id: String) { session = id; lastSaved.clear() }

    /** Stores at most one reading per pack and metric every 30 s (health and status: on change or every 5 min). */
    @Synchronized fun save(reading: Reading) {
        val key = reading.pack to reading.metric
        if (!due(lastSaved[key], reading)) return
        writableDatabase.insertOrThrow("readings", null, ContentValues().apply {
            put("session", session); put("time", reading.time); put("pack", reading.pack)
            put("metric", reading.metric.name); put("value", reading.value)
        })
        lastSaved[key] = reading
    }

    private fun due(previous: Reading?, next: Reading): Boolean {
        if (previous == null) return true
        val elapsed = next.time - previous.time
        if (elapsed < 0) return true // Clock moved back.
        if (elapsed < 30_000) return false
        return when (next.metric) {
            Metric.HEALTH -> elapsed >= 300_000 || abs(next.value - previous.value) >= 0.5
            Metric.STATUS -> elapsed >= 300_000 || next.value != previous.value
            else -> true
        }
    }

    fun prune(now: Long) {
        invalidateEfficiency()
        val db = writableDatabase
        val readings = (now - READINGS_DAYS * DAY_MS).toString()
        val rides = (now - RIDES_DAYS * DAY_MS).toString()
        db.delete("readings", "time < ?", arrayOf(readings))
        db.delete("ride_points", "ride IN (SELECT id FROM rides WHERE started < ?)", arrayOf(rides))
        db.delete("rides", "started < ?", arrayOf(rides))
        db.delete("charge_sessions", "started < ?", arrayOf(rides))
    }

    fun chargeHistory(pack: Int, since: Long): List<Reading> = readableDatabase.rawQuery(
        "SELECT time,value FROM readings WHERE pack=? AND metric='SOC' AND time>=? ORDER BY time DESC LIMIT 8641",
        arrayOf(pack.toString(), since.toString())
    ).use { cursor -> buildList {
        while (cursor.moveToNext()) add(Reading(pack, Metric.SOC, cursor.getDouble(1), cursor.getLong(0)))
    }.reversed() }

    /**
     * [metric] on [pack] in [sinceMs, untilMs) averaged into at most [buckets] buckets (see
     * [RideMath.downsample]): e.g. 24 h → 96, 7 d → 168, 30 d → 120 buckets.
     */
    fun history(metric: Metric, pack: Int, sinceMs: Long, buckets: Int, untilMs: Long = System.currentTimeMillis()): List<BucketPoint> {
        if (buckets <= 0 || untilMs <= sinceMs) return emptyList()
        val width = RideMath.bucketWidth(sinceMs, untilMs, buckets)
        return readableDatabase.rawQuery(
            "SELECT (time - $sinceMs) / $width AS b, AVG(value), MIN(value), MAX(value), COUNT(*) FROM readings " +
                "WHERE pack=? AND metric=? AND time>=? AND time<? GROUP BY b ORDER BY b",
            arrayOf(pack.toString(), metric.name, sinceMs.toString(), untilMs.toString())
        ).use { c -> buildList {
            while (c.moveToNext()) add(BucketPoint(sinceMs + c.getLong(0) * width + width / 2, c.getDouble(1), c.getDouble(2), c.getDouble(3), c.getInt(4)))
        } }
    }

    /** Temperature history per pack (0 = scooter, 1, 2); packs without readings are absent. */
    fun temperatureTrend(sinceMs: Long, buckets: Int, untilMs: Long = System.currentTimeMillis()): Map<Int, List<BucketPoint>> =
        (0..2).associateWith { history(Metric.TEMPERATURE, it, sinceMs, buckets, untilMs) }.filterValues { it.isNotEmpty() }

    /** Pack balance: avg = pack 1 − pack 2 charge (points) in buckets where both packs reported; min/max equal avg. */
    fun packBalance(sinceMs: Long, buckets: Int, untilMs: Long = System.currentTimeMillis()): List<BucketPoint> {
        val two = history(Metric.SOC, 2, sinceMs, buckets, untilMs).associateBy { it.time }
        return history(Metric.SOC, 1, sinceMs, buckets, untilMs).mapNotNull { one ->
            two[one.time]?.let { val diff = one.avg - it.avg; BucketPoint(one.time, diff, diff, diff, min(one.count, it.count)) }
        }
    }

    /** Most recent stored reading, or null. */
    fun latest(metric: Metric, pack: Int): Reading? = readableDatabase.rawQuery(
        "SELECT time,value FROM readings WHERE pack=? AND metric=? ORDER BY time DESC LIMIT 1", arrayOf(pack.toString(), metric.name)
    ).use { c -> if (c.moveToFirst()) Reading(pack, metric, c.getDouble(1), c.getLong(0)) else null }

    // ------------------------------------------------------------------ rides (read)

    /** Finished rides, newest first. */
    fun rides(limit: Int = 100, sinceMs: Long = 0): List<Ride> = readableDatabase.rawQuery(
        "SELECT $RIDE_COLUMNS FROM rides WHERE finished=1 AND started>=? ORDER BY started DESC LIMIT $limit", arrayOf(sinceMs.toString())
    ).use { c -> buildList { while (c.moveToNext()) add(c.ride()) } }

    fun ride(id: Long): Ride? = readableDatabase.rawQuery(
        "SELECT $RIDE_COLUMNS FROM rides WHERE id=?", arrayOf(id.toString())
    ).use { c -> if (c.moveToFirst()) c.ride() else null }

    /** The ride's track in time order. */
    fun ridePoints(id: Long): List<RidePoint> = readableDatabase.rawQuery(
        "SELECT time,lat,lng,speed,distance,accuracy FROM ride_points WHERE ride=? ORDER BY time", arrayOf(id.toString())
    ).use { c -> buildList {
        while (c.moveToNext()) add(RidePoint(c.getLong(0), c.getDouble(1), c.getDouble(2), c.getDouble(3), c.getDouble(4), c.getDouble(5)))
    } }

    fun rideStats(sinceMs: Long = 0): RideStats = RideMath.stats(rides(Int.MAX_VALUE, sinceMs))

    /**
     * Metres ridden in finished rides that started at or after [startMs] (e.g. local midnight for
     * Home's "Today"); a ride still recording is not included. 0 on no rides.
     */
    fun distanceSince(startMs: Long): Double = readableDatabase.rawQuery(
        "SELECT TOTAL(distance_m) FROM rides WHERE finished=1 AND started>=?", arrayOf(startMs.toString())
    ).use { c -> if (c.moveToFirst()) c.getDouble(0) else 0.0 }

    // ------------------------------------------------------------------ range

    @Volatile private var learned: RangeEfficiency? = null
    private val efficiencyGeneration = java.util.concurrent.atomic.AtomicInteger()

    /**
     * The efficiency the range estimate uses ([RangeModel.efficiency] over stored rides ≥ 3 km that used
     * ≥ 2 points): learned, or the default with [RangeEfficiency.learned] = false ("est.") without such
     * rides or on a storage error. Show exactly this (or [RangeEstimate.kmPerPercent]) as Efficiency.
     * Disk I/O on the first call and after rides change; cached otherwise.
     */
    fun efficiency(): RangeEfficiency {
        learned?.let { return it }
        val generation = efficiencyGeneration.get()
        return try {
            // Literals, not bound strings: a text parameter never compares numerically with an expression.
            val rides = readableDatabase.rawQuery(
                "SELECT $RIDE_COLUMNS FROM rides WHERE finished=1 AND distance_m >= ${RangeModel.LEARN_MIN_KM * 1000} " +
                    "AND soc_start IS NOT NULL AND soc_end IS NOT NULL AND soc_start - soc_end >= ${RangeModel.LEARN_MIN_USED_PERCENT} ORDER BY started",
                null
            ).use { c -> buildList { while (c.moveToNext()) add(c.ride()) } }
            RangeModel.efficiency(rides).also { if (efficiencyGeneration.get() == generation) learned = it }
        } catch (_: Exception) {
            RangeEfficiency.DEFAULT
        }
    }

    /** Learned km per charge point: [efficiency]'s value (same disk I/O and caching). */
    fun kmPerPercent(): Double = efficiency().kmPerPercent

    /** The last [efficiency] result without disk I/O (safe on the main thread); the default until computed. */
    val cachedEfficiency: RangeEfficiency get() = learned ?: RangeEfficiency.DEFAULT

    /** [cachedEfficiency]'s value. */
    val cachedKmPerPercent: Double get() = cachedEfficiency.kmPerPercent

    private fun invalidateEfficiency() { efficiencyGeneration.incrementAndGet(); learned = null }

    /** Estimated range history in km: pack-0 charge [history] × the current [kmPerPercent]. */
    fun rangeHistory(sinceMs: Long, buckets: Int, untilMs: Long = System.currentTimeMillis()): List<BucketPoint> =
        RangeModel.series(history(Metric.SOC, 0, sinceMs, buckets, untilMs), kmPerPercent())

    /** Charging sessions, newest first (an in-progress session appears with its latest progress). */
    fun chargeSessions(limit: Int = 50, sinceMs: Long = 0): List<ChargeSession> = readableDatabase.rawQuery(
        "SELECT started,ended,soc_start,soc_end,id FROM charge_sessions WHERE started>=? ORDER BY started DESC LIMIT $limit", arrayOf(sinceMs.toString())
    ).use { c -> buildList { while (c.moveToNext()) add(ChargeSession(c.getLong(0), c.getLong(1), c.getDouble(2), c.getDouble(3), c.getLong(4))) } }

    // ------------------------------------------------------------------ rides (write; used by RideTracker)

    /** Opens an unfinished ride row and returns its id. */
    fun beginRide(start: Long, socStart: Double?): Long = writableDatabase.insertOrThrow("rides", null, ContentValues().apply {
        put("started", start); put("ended", start); put("soc_start", socStart); put("finished", 0); put("places_v", PLACES_VERSION)
    })

    fun appendRidePoints(rideId: Long, points: List<RidePoint>, socStart: Double?, socLatest: Double?) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues()
            for (p in points) {
                values.clear()
                values.put("ride", rideId); values.put("time", p.time); values.put("lat", p.lat); values.put("lng", p.lng)
                values.put("speed", p.speedKmh); values.put("distance", p.distanceM); values.put("accuracy", p.accuracyM)
                db.insertOrThrow("ride_points", null, values)
            }
            // Keeps the charge readings on the open row so a recovered ride still has km per %.
            db.update("rides", ContentValues().apply {
                put("soc_start", socStart); put("soc_end", socLatest); points.lastOrNull()?.let { put("ended", it.time) }
            }, "id=?", arrayOf(rideId.toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** Stores the summary and drops the idle points trimmed off either end. */
    fun finishRide(ride: Ride) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.update("rides", ContentValues().apply {
                put("started", ride.start); put("ended", ride.end)
                put("start_lat", ride.startLat); put("start_lng", ride.startLng); put("end_lat", ride.endLat); put("end_lng", ride.endLng)
                put("start_place", ride.startPlace); put("end_place", ride.endPlace)
                put("distance_m", ride.distanceM); put("duration_s", ride.durationS); put("moving_s", ride.movingS)
                put("peak_kmh", ride.peakKmh); put("avg_kmh", ride.avgKmh)
                put("soc_start", ride.socStart); put("soc_end", ride.socEnd); put("finished", 1)
                put("places_v", PLACES_VERSION) // The names written here (or looked up next) are in the current format.
            }, "id=?", arrayOf(ride.id.toString()))
            db.delete("ride_points", "ride=? AND (time<? OR time>?)", arrayOf(ride.id.toString(), ride.start.toString(), ride.end.toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction(); invalidateEfficiency() }
    }

    fun deleteRide(id: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("ride_points", "ride=?", arrayOf(id.toString()))
            db.delete("rides", "id=?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction(); invalidateEfficiency() }
    }

    /** Sets whichever place names are non-null. */
    fun setRidePlaces(id: Long, startPlace: String?, endPlace: String?) {
        val values = ContentValues().apply { startPlace?.let { put("start_place", it) }; endPlace?.let { put("end_place", it) } }
        if (values.size() > 0) writableDatabase.update("rides", values, "id=?", arrayOf(id.toString()))
    }

    /** Replaces both place names with ones in the current format and marks the row as current. */
    fun replaceRidePlaces(id: Long, startPlace: String, endPlace: String) {
        writableDatabase.update("rides", ContentValues().apply {
            put("start_place", startPlace); put("end_place", endPlace); put("places_v", PLACES_VERSION)
        }, "id=?", arrayOf(id.toString()))
    }

    /** Finished rides whose place names predate the current format (2.0's "Area, City"), newest first. */
    fun ridesWithOldPlaces(sinceMs: Long, limit: Int): List<Ride> = readableDatabase.rawQuery(
        "SELECT $RIDE_COLUMNS FROM rides WHERE finished=1 AND started>=? AND places_v<$PLACES_VERSION ORDER BY started DESC LIMIT $limit",
        arrayOf(sinceMs.toString())
    ).use { c -> buildList { while (c.moveToNext()) add(c.ride()) } }

    /** Rides still marked as recording (left by a killed process); only start time and charge are meaningful. */
    fun openRides(): List<Ride> = readableDatabase.rawQuery(
        "SELECT $RIDE_COLUMNS FROM rides WHERE finished=0", null
    ).use { c -> buildList { while (c.moveToNext()) add(c.ride()) } }

    fun ridesMissingPlaces(sinceMs: Long, limit: Int): List<Ride> = readableDatabase.rawQuery(
        "SELECT $RIDE_COLUMNS FROM rides WHERE finished=1 AND started>=? AND (start_place IS NULL OR end_place IS NULL) ORDER BY started DESC LIMIT $limit",
        arrayOf(sinceMs.toString())
    ).use { c -> buildList { while (c.moveToNext()) add(c.ride()) } }

    /** Inserts or replaces the session with the same start (an in-progress or resumed session updates in place). */
    fun saveChargeSession(session: ChargeSession): Long = putChargeSession(writableDatabase, session)

    /** Removes the session that started at [start] (demo mode: its live charging session when the scenario ends). */
    fun deleteChargeSession(start: Long): Int = writableDatabase.delete("charge_sessions", "started=?", arrayOf(start.toString()))

    private fun putChargeSession(db: SQLiteDatabase, s: ChargeSession): Long = db.insertWithOnConflict("charge_sessions", null, ContentValues().apply {
        put("started", s.start); put("ended", s.end); put("soc_start", s.socStart); put("soc_end", s.socEnd)
    }, SQLiteDatabase.CONFLICT_REPLACE)

    private fun Cursor.ride() = Ride(
        id = getLong(0), start = getLong(1), end = getLong(2),
        startLat = getDouble(3), startLng = getDouble(4), endLat = getDouble(5), endLng = getDouble(6),
        startPlace = if (isNull(7)) null else getString(7), endPlace = if (isNull(8)) null else getString(8),
        distanceM = getDouble(9), durationS = getLong(10), movingS = getLong(11), peakKmh = getDouble(12), avgKmh = getDouble(13),
        socStart = if (isNull(14)) null else getDouble(14), socEnd = if (isNull(15)) null else getDouble(15)
    )

    // ------------------------------------------------------------------ demo history (F-DEMO)

    /** Rides in the store (demo mode checks its file is seeded). */
    fun rideCount(): Long = DatabaseUtils.queryNumEntries(readableDatabase, "rides")

    /**
     * Demo file only: replaces everything with [history] (its rides with tracks, charging sessions and
     * readings) in one transaction, so readers see all of it or none of it. ~50 000 rows, about a
     * second on a phone: call it on the storage thread. Throws for the rider's own history.
     */
    fun seedDemo(history: DemoHistory) {
        check(isDemo) { "Demo data never goes into the rider's history" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (table in listOf("readings", "ride_points", "rides", "charge_sessions")) db.delete(table, null, null)
            val ride = db.compileStatement("INSERT INTO rides (id,started,ended,start_lat,start_lng,end_lat,end_lng,start_place,end_place," +
                "distance_m,duration_s,moving_s,peak_kmh,avg_kmh,soc_start,soc_end,finished,places_v) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,1,$PLACES_VERSION)")
            val point = db.compileStatement("INSERT INTO ride_points (ride,time,lat,lng,speed,distance,accuracy) VALUES (?,?,?,?,?,?,?)")
            for ((r, points) in history.rides) {
                ride.clearBindings()
                ride.bindLong(1, r.id); ride.bindLong(2, r.start); ride.bindLong(3, r.end)
                ride.bindDouble(4, r.startLat); ride.bindDouble(5, r.startLng); ride.bindDouble(6, r.endLat); ride.bindDouble(7, r.endLng)
                r.startPlace?.let { ride.bindString(8, it) } ?: ride.bindNull(8)
                r.endPlace?.let { ride.bindString(9, it) } ?: ride.bindNull(9)
                ride.bindDouble(10, r.distanceM); ride.bindLong(11, r.durationS); ride.bindLong(12, r.movingS)
                ride.bindDouble(13, r.peakKmh); ride.bindDouble(14, r.avgKmh)
                r.socStart?.let { ride.bindDouble(15, it) } ?: ride.bindNull(15)
                r.socEnd?.let { ride.bindDouble(16, it) } ?: ride.bindNull(16)
                ride.executeInsert()
                for (p in points) {
                    point.bindLong(1, r.id); point.bindLong(2, p.time); point.bindDouble(3, p.lat); point.bindDouble(4, p.lng)
                    point.bindDouble(5, p.speedKmh); point.bindDouble(6, p.distanceM); point.bindDouble(7, p.accuracyM)
                    point.executeInsert()
                }
            }
            history.charges.forEach { putChargeSession(db, it) }
            val reading = db.compileStatement("INSERT INTO readings (session,time,pack,metric,value) VALUES ('demo',?,?,?,?)")
            history.forEachReading { r ->
                reading.bindLong(1, r.time); reading.bindLong(2, r.pack.toLong()); reading.bindString(3, r.metric.name); reading.bindDouble(4, r.value)
                reading.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction(); invalidateEfficiency() }
    }

    // ------------------------------------------------------------------ export

    /** Readings (unchanged layout), then a rides section and a charging-sessions section, separated by blank lines. */
    fun exportCsv(output: OutputStream) = output.bufferedWriter().use { writer ->
        writer.write("session,timestamp_utc_ms,pack,metric,value,unit\n")
        readableDatabase.rawQuery("SELECT session,time,pack,metric,value FROM readings ORDER BY time,id", null).use { cursor ->
            while (cursor.moveToNext()) {
                val unit = Metric.entries.firstOrNull { it.name == cursor.getString(3) }?.unit.orEmpty()
                writer.write("${cursor.getString(0)},${cursor.getLong(1)},${cursor.getInt(2)},${cursor.getString(3)},${cursor.getDouble(4)},$unit\n")
            }
        }
        writer.write("\nride_id,start_utc_ms,end_utc_ms,start_place,end_place,start_lat,start_lng,end_lat,end_lng," +
            "distance_m,duration_s,moving_s,peak_kmh,avg_kmh,soc_start,soc_end,km_per_percent\n")
        rides(Int.MAX_VALUE).asReversed().forEach { r ->
            writer.write(listOf(r.id, r.start, r.end, csv(r.startLabel), csv(r.endLabel), r.startLat, r.startLng, r.endLat, r.endLng,
                number(r.distanceM, 0), r.durationS, r.movingS, number(r.peakKmh), number(r.avgKmh),
                r.socStart ?: "", r.socEnd ?: "", r.kmPerPercent?.let { number(it, 2) } ?: "").joinToString(",") + "\n")
        }
        writer.write("\ncharge_session_id,start_utc_ms,end_utc_ms,soc_start,soc_end,duration_s,percent_per_hour\n")
        chargeSessions(Int.MAX_VALUE).asReversed().forEach { s ->
            writer.write("${s.id},${s.start},${s.end},${s.socStart},${s.socEnd},${s.durationS},${number(s.percentPerHour)}\n")
        }
    }

    private fun csv(text: String) = if (text.any { it == ',' || it == '"' || it == '\n' }) "\"" + text.replace("\"", "\"\"") + "\"" else text

    companion object {
        const val NAME = "vida_telemetry.db"
        const val VERSION = 7
        /** Format of rides.start_place/end_place: 0 = 2.0 "Area, City"; 1 = 2.1 "Street, Area" ([RideMath.placeLabel]). */
        const val PLACES_VERSION = 1
        const val READINGS_DAYS = 30L
        const val RIDES_DAYS = 365L
        private const val DAY_MS = 86_400_000L
        private const val RIDE_COLUMNS = "id,started,ended,start_lat,start_lng,end_lat,end_lng,start_place,end_place," +
            "distance_m,duration_s,moving_s,peak_kmh,avg_kmh,soc_start,soc_end"

        /** Demo mode's history file (F-DEMO): same schema, separate file, deleted when the demo ends. */
        const val DEMO_NAME = "ruta_demo.db"

        @Volatile private var instance: TelemetryStore? = null

        /** Process-wide store shared by the UI and RideService. Do not rely on close() for it (no-op). */
        fun shared(context: Context): TelemetryStore = instance ?: synchronized(this) {
            instance ?: TelemetryStore(context.applicationContext).also { instance = it }
        }

        /** A new handle on the demo history file (empty until [seedDemo]); [retire] it before [deleteDemo]. */
        fun demo(context: Context): TelemetryStore = TelemetryStore(context.applicationContext ?: context, DEMO_NAME)

        /** Deletes the demo history file (and its journal files); true when one existed. Disk I/O. */
        fun deleteDemo(context: Context): Boolean = (context.applicationContext ?: context).deleteDatabase(DEMO_NAME)
    }
}
