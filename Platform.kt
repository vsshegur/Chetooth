package dev.codesurgen.ruta

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Delay
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.CoroutineContext

/**
 * Main-thread plumbing for an app that ships without kotlinx-coroutines-android.
 *
 * **`Dispatchers.Main` is unavailable**: nothing registers a main dispatcher, so touching it throws
 * `IllegalStateException: Module with the Main dispatcher is missing` at runtime (it still compiles,
 * which is what makes it dangerous). Use [Ui.dispatcher] / [Ui.scope] instead; build.sh rejects
 * `Dispatchers.Main` in sources.
 */
object Ui {
    val handler = Handler(Looper.getMainLooper())

    /** Always posts to the main looper, like `Dispatchers.Main`. */
    val dispatcher: CoroutineDispatcher = MainLooperDispatcher(handler, immediate = false)

    /** Runs inline when already on the main thread, like `Dispatchers.Main.immediate`. */
    val immediate: CoroutineDispatcher = MainLooperDispatcher(handler, immediate = true)

    val isMainThread: Boolean get() = Looper.myLooper() === Looper.getMainLooper()

    /** A main-thread scope whose children fail independently; cancel it when its owner dies. */
    fun scope(): CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)

    fun post(block: () -> Unit) { handler.post(block) }

    /** Returns the posted [Runnable] so callers can [cancel] it. */
    fun postDelayed(delayMs: Long, block: () -> Unit): Runnable =
        Runnable(block).also { handler.postDelayed(it, delayMs) }

    fun cancel(runnable: Runnable) = handler.removeCallbacks(runnable)

    /** Runs [block] now on the main thread, or posts it from any other thread. */
    fun run(block: () -> Unit) { if (isMainThread) block() else handler.post(block) }

    /** Throws when called off the main thread; cheap guard for main-thread-only state. */
    fun assertMainThread(what: String = "This call") =
        check(isMainThread) { "$what must run on the main thread (was ${Thread.currentThread().name})" }

    /** Monotonic milliseconds for UI timers (unaffected by wall-clock changes). */
    fun uptimeMs(): Long = SystemClock.uptimeMillis()
}

/**
 * Handler-backed dispatcher, modelled on kotlinx-coroutines-android's HandlerContext. Implementing
 * [Delay] keeps `delay()`/`withTimeout` on the main looper instead of hopping through the
 * coroutines DefaultExecutor thread.
 */
@OptIn(InternalCoroutinesApi::class, ExperimentalCoroutinesApi::class)
private class MainLooperDispatcher(
    private val handler: Handler,
    private val immediate: Boolean,
) : CoroutineDispatcher(), Delay {
    override fun isDispatchNeeded(context: CoroutineContext): Boolean =
        !immediate || Looper.myLooper() !== handler.looper

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        handler.post(block)
    }

    override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
        val resume = Runnable { with(continuation) { resumeUndispatched(Unit) } }
        handler.postDelayed(resume, timeMillis.coerceIn(0L, MAX_DELAY_MS))
        continuation.invokeOnCancellation { handler.removeCallbacks(resume) }
    }

    override fun invokeOnTimeout(timeMillis: Long, block: Runnable, context: CoroutineContext): DisposableHandle {
        handler.postDelayed(block, timeMillis.coerceIn(0L, MAX_DELAY_MS))
        return DisposableHandle { handler.removeCallbacks(block) }
    }

    override fun toString() = if (immediate) "Ui.immediate" else "Ui.dispatcher"

    private companion object {
        // Handler.postDelayed adds uptime; keep the sum from overflowing (same cap as HandlerContext).
        const val MAX_DELAY_MS = Long.MAX_VALUE / 2
    }
}
