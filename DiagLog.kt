package dev.codesurgen.ruta

import android.content.Context
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The diagnostics log. [append] from any thread; it never blocks on I/O and never throws:
 *  - mirrors the line to logcat (tag [TAG] = "Ruta"; long lines in chunks);
 *  - keeps the newest [RING_LINES] entries in memory for the Diagnostics viewer ([lines]), each
 *    "HH:mm:ss  text"; consecutive identical lines collapse into one entry ending "  (×n)";
 *  - writes "yyyy-MM-dd HH:mm:ss.SSS  text" to getExternalFilesDir("logs")/ruta-yyyyMMdd.log on one
 *    background thread, flushed per line (repeats of the same line at most once a minute, with a count),
 *    deleting log files older than [KEEP_DAYS] days (pre-rename ridedeck-*.log files are listed and
 *    pruned the same way). Pull them with
 *    `adb pull /sdcard/Android/data/dev.codesurgen.ruta/files/logs`.
 * Lines appended before [init] reach logcat and the ring at once and the file once [init] runs.
 * Listeners run on the main thread, coalesced (at most one pending call).
 */
object DiagLog {
    const val TAG = "Ruta"
    const val RING_LINES = 1000
    const val KEEP_DAYS = 7
    private const val FILE_PREFIX = "ruta-"
    private const val OLD_FILE_PREFIX = "ridedeck-" // Files written before the rename (2.1).
    private const val QUEUE_LIMIT = 20_000
    private const val LOGCAT_CHUNK = 3_000
    private const val REPEAT_WRITE_MS = 60_000L
    private const val DAY_MS = 86_400_000L

    private class Entry(val time: Long, val text: String)

    private val lock = Any()
    private val ring = ArrayDeque<String>(RING_LINES) // guarded by lock
    private var ringLast: String? = null // guarded by lock
    private var ringRepeats = 0 // guarded by lock
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val notifyPending = AtomicBoolean(false)
    private val queue = LinkedBlockingQueue<Entry>(QUEUE_LIMIT)
    private val started = AtomicBoolean(false)
    @Volatile private var app: Context? = null

    /** Directory of the log files, once the writer has resolved it (null before [init] or without storage). */
    @Volatile var logDir: File? = null; private set

    private val clock = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val dayFormat = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.US)

    /** Starts the file writer. Idempotent; cheap on the main thread (the directory is resolved off it). */
    fun init(context: Context) {
        try {
            if (app == null) app = context.applicationContext ?: context
            if (started.compareAndSet(false, true)) {
                Thread(::writerLoop, "DiagLog").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
            }
        } catch (e: Exception) {
            started.set(false)
            Log.w(TAG, "Log file unavailable: ${e.javaClass.simpleName}")
        }
    }

    fun append(line: String) {
        try {
            val now = System.currentTimeMillis()
            logcat(line)
            val time = clock.format(Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()))
            synchronized(lock) {
                if (line == ringLast && ring.isNotEmpty()) {
                    ringRepeats++
                    ring.removeLast()
                    ring.addLast("$time  $line  (×$ringRepeats)")
                } else {
                    ringLast = line; ringRepeats = 1
                    if (ring.size >= RING_LINES) ring.removeFirst()
                    ring.addLast("$time  $line")
                }
            }
            queue.offer(Entry(now, line)) // Drops (never blocks) if the writer is far behind or never started.
            notifyListeners()
        } catch (_: Exception) {
            // Logging must never take the app down.
        }
    }

    /** Snapshot of the in-memory log, oldest first. */
    fun lines(): List<String> = synchronized(lock) { ring.toList() }

    /** Existing log files, newest first (small directory listing; prefer a background thread). */
    fun files(): List<File> = try {
        logDir?.listFiles { f -> f.isLogFile() }?.sortedByDescending { it.name }.orEmpty()
    } catch (_: Exception) { emptyList() }

    /** Called on the main thread after new lines arrive. */
    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    private fun notifyListeners() {
        if (listeners.isEmpty() || !notifyPending.compareAndSet(false, true)) return
        Ui.post {
            notifyPending.set(false)
            for (l in listeners) try { l() } catch (e: Exception) { Log.w(TAG, "Log listener failed: ${e.javaClass.simpleName}") }
        }
    }

    private fun logcat(line: String) {
        if (line.length <= LOGCAT_CHUNK) { Log.i(TAG, line); return }
        var start = 0
        while (start < line.length) {
            val end = minOf(line.length, start + LOGCAT_CHUNK)
            Log.i(TAG, (if (start == 0) "" else "… ") + line.substring(start, end))
            start = end
        }
    }

    // ------------------------------------------------------------------ file writer (own thread)

    private var writer: BufferedWriter? = null
    private var writerDay: String? = null
    private var reportedError: String? = null

    private fun writerLoop() {
        var repeatText: String? = null
        var repeats = 0
        var lastRepeatAt = 0L
        var writtenAt = 0L
        val batch = ArrayList<Entry>()
        while (true) {
            try {
                batch.clear()
                batch += queue.take()
                queue.drainTo(batch, 1_000)
                if (logDir == null && resolveDir() == null) continue // No storage: lines stay in logcat and the ring.
                for (e in batch) {
                    if (e.text == repeatText) {
                        repeats++; lastRepeatAt = e.time
                        if (e.time - writtenAt >= REPEAT_WRITE_MS) {
                            write(e.time, "${e.text}  (×$repeats)"); repeats = 0; writtenAt = e.time
                        }
                        continue
                    }
                    if (repeats > 0) write(lastRepeatAt, "$repeatText  (×$repeats)")
                    write(e.time, e.text)
                    repeatText = e.text; repeats = 0; writtenAt = e.time
                }
            } catch (_: InterruptedException) {
                closeWriter(); return
            } catch (e: Exception) {
                closeWriter()
                val message = "${e.javaClass.simpleName}: ${e.message}"
                if (message != reportedError) { reportedError = message; Log.w(TAG, "Log file write failed: $message") }
            }
        }
    }

    private fun resolveDir(): File? {
        val context = app ?: return null
        val dir = try {
            (context.getExternalFilesDir("logs") ?: File(context.filesDir, "logs")).also { it.mkdirs() }
        } catch (_: Exception) { null }
        if (dir != null && dir.isDirectory) { logDir = dir; prune(dir) }
        return logDir
    }

    private fun write(time: Long, text: String) {
        val dir = logDir ?: return
        val zoned = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault())
        val day = dayFormat.format(zoned)
        var out = writer
        if (out == null || day != writerDay) {
            closeWriter()
            out = BufferedWriter(OutputStreamWriter(FileOutputStream(File(dir, "$FILE_PREFIX$day.log"), true), Charsets.UTF_8))
            writer = out; writerDay = day
            prune(dir)
        }
        out.write(stamp.format(zoned))
        out.write("  ")
        out.write(text.replace("\n", "\n    "))
        out.write("\n")
        out.flush()
        reportedError = null
    }

    private fun closeWriter() {
        try { writer?.close() } catch (_: Exception) {}
        writer = null; writerDay = null
    }

    private fun prune(dir: File) {
        try {
            val cutoff = System.currentTimeMillis() - KEEP_DAYS * DAY_MS
            dir.listFiles { f -> f.isLogFile() && f.lastModified() < cutoff }?.forEach { it.delete() }
        } catch (_: Exception) {}
    }

    private fun File.isLogFile() =
        isFile && (name.startsWith(FILE_PREFIX) || name.startsWith(OLD_FILE_PREFIX)) && name.endsWith(".log")
}
