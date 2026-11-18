package dev.codesurgen.ruta

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Ruta motion: physics springs for anything spatial (nav indicator, switches, gauge arcs, FAB),
 * M3 easing/duration tokens, and the fade-through tab transition. Everything honours the system
 * "Animator duration scale": 0 (animations off) jumps straight to the end state.
 */

/** Easing and duration tokens (M3). */
object Motion {
    const val SHORT1 = 50L; const val SHORT2 = 100L; const val SHORT3 = 150L; const val SHORT4 = 200L
    const val MEDIUM1 = 250L; const val MEDIUM2 = 300L; const val MEDIUM3 = 350L; const val MEDIUM4 = 400L
    const val LONG1 = 450L; const val LONG2 = 500L; const val LONG3 = 550L; const val LONG4 = 600L

    /** Fade-through: outgoing fades for 90 ms, incoming fades/scales in for 210 ms. */
    const val FADE_OUT_MS = 90L
    const val FADE_IN_MS = 210L

    /** Default spring (DESIGN › Motion). */
    const val STIFFNESS = 400f
    const val DAMPING = 0.8f
    /** Fast spatial spring for press feedback and small shape morphs. */
    const val STIFFNESS_FAST = 1400f
    const val DAMPING_FAST = 0.9f
    /** Critically damped (no overshoot): sheets, anything anchored to a screen edge. */
    const val DAMPING_NO_BOUNCE = 1f

    val standard = PathInterpolator(0.2f, 0f, 0f, 1f)
    val standardDecelerate = PathInterpolator(0f, 0f, 0f, 1f)
    val standardAccelerate = PathInterpolator(0.3f, 0f, 1f, 1f)
    val emphasizedDecelerate = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
    val emphasizedAccelerate = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)
    val emphasized = PathInterpolator(Path().apply {
        moveTo(0f, 0f)
        cubicTo(0.05f, 0f, 0.133333f, 0.06f, 0.166666f, 0.4f)
        cubicTo(0.208333f, 0.82f, 0.25f, 1f, 1f, 1f)
    })

    /** False when the user turned animations off (developer options / accessibility "Remove animations"). */
    fun enabled(): Boolean = ValueAnimator.areAnimatorsEnabled()

    /** System animator duration scale (1 = normal); springs run slower by the same factor. */
    fun durationScale(): Float = if (Build.VERSION.SDK_INT >= 33) ValueAnimator.getDurationScale().takeIf { it > 0f } ?: 1f else 1f

    /**
     * Fade-through between two sibling views (tab switch): [outgoing] fades out (90 ms, then GONE),
     * [incoming] becomes VISIBLE and fades in from 92 % scale (210 ms, emphasized decelerate) after
     * it. Safe to call again mid-transition; with animations off it just swaps visibility.
     *
     * Touch rules (no tap may land on a screen that is leaving): the outgoing view stops at once —
     * its pending clicks and any gesture in progress are cancelled, it is hidden from TalkBack, and a
     * [TouchGate] outgoing view refuses every touch until it is shown again; the incoming view is
     * moved above the outgoing one when they share a parent, and a [TouchGate] incoming view
     * swallows taps while it is still invisible (the first 90 ms).
     */
    fun fadeThrough(outgoing: View?, incoming: View, onDone: (() -> Unit)? = null) {
        if (outgoing === incoming) { enter(incoming, 0L); incoming.visibility = View.VISIBLE; onDone?.invoke(); return }
        outgoing?.animate()?.cancel()
        incoming.animate().cancel()
        outgoing?.let { leave(it) }
        val parent = incoming.parent as? ViewGroup
        if (outgoing != null && parent != null && outgoing.parent === parent && parent.indexOfChild(incoming) < parent.indexOfChild(outgoing)) {
            incoming.bringToFront()
        }
        if (!enabled() || outgoing == null || outgoing.visibility != View.VISIBLE || !incoming.isAttachedToWindow) {
            outgoing?.let { it.visibility = View.GONE; reset(it) }
            reset(incoming)
            enter(incoming, 0L)
            incoming.visibility = View.VISIBLE
            onDone?.invoke()
            return
        }
        outgoing.animate().alpha(0f).setDuration(FADE_OUT_MS).setStartDelay(0)
            .setInterpolator(emphasizedAccelerate).withLayer()
            .withEndAction { outgoing.visibility = View.GONE; reset(outgoing) }
            .start()
        val wasShown = incoming.visibility == View.VISIBLE
        if (!wasShown) { incoming.alpha = 0f; incoming.scaleX = 0.92f; incoming.scaleY = 0.92f }
        enter(incoming, if (wasShown) 0L else FADE_OUT_MS)
        incoming.visibility = View.VISIBLE
        incoming.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(FADE_IN_MS).setStartDelay(FADE_OUT_MS)
            .setInterpolator(emphasizedDecelerate).withLayer()
            .withEndAction { reset(incoming); onDone?.invoke() }
            .start()
    }

    /** The leaving view takes no further input: pending clicks, the current gesture and TalkBack focus end now. */
    private fun leave(view: View) {
        if (view is TouchGate) view.close() else cancelTouches(view)
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    private fun enter(view: View, invisibleMs: Long) {
        if (view is TouchGate) view.open(invisibleMs)
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
    }

    /** Cancels a press or drag in progress inside [view] and drops its queued clicks. */
    internal fun cancelTouches(view: View) {
        view.cancelPendingInputEvents()
        val now = SystemClock.uptimeMillis()
        val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        view.dispatchTouchEvent(cancel)
        cancel.recycle()
    }

    /** Fades [view] in or out (VISIBLE/GONE at the end), 150 ms; instant with animations off. */
    fun fade(view: View, show: Boolean, durationMs: Long = SHORT3) {
        val target = if (show) View.VISIBLE else View.GONE
        view.animate().cancel()
        if (!enabled() || !view.isAttachedToWindow) { view.alpha = 1f; view.visibility = target; return }
        if (show) {
            if (view.visibility != View.VISIBLE) { view.alpha = 0f; view.visibility = View.VISIBLE }
            view.animate().alpha(1f).setDuration(durationMs).setInterpolator(standardDecelerate).start()
        } else {
            if (view.visibility != View.VISIBLE) return
            view.animate().alpha(0f).setDuration(durationMs).setInterpolator(standardAccelerate)
                .withEndAction { view.visibility = View.GONE; view.alpha = 1f }.start()
        }
    }

    private fun reset(view: View) { view.alpha = 1f; view.scaleX = 1f; view.scaleY = 1f }
}

/**
 * Frame that can refuse touches as a whole (a tab or overlay in a transition). [close] = no touch
 * reaches anything inside (it returns false, so a touch falls through to the view underneath) and
 * any press in progress is cancelled; [open] accepts touches again, optionally swallowing taps for
 * the first [open]`(invisibleMs)` while the view is still fading in. [Motion.fadeThrough] closes the
 * outgoing gate and opens the incoming one.
 */
open class TouchGate(context: Context) : FrameLayout(context) {
    /** False while leaving: touches pass to whatever is underneath, never to this view's children. */
    var touchable = true; private set
    private var swallowUntil = 0L
    private var swallowing = false

    /** Stops all input now (cancels the current gesture and queued clicks). */
    fun close() {
        if (!touchable) return
        Motion.cancelTouches(this) // While still touchable, so the cancel reaches the children.
        touchable = false
        swallowing = false
    }

    /** Accepts input again; taps that start within [swallowMs] are eaten (the view is not visible yet). */
    fun open(swallowMs: Long = 0L) {
        touchable = true
        swallowUntil = if (swallowMs > 0) SystemClock.uptimeMillis() + swallowMs else 0L
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!touchable) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> swallowing = SystemClock.uptimeMillis() < swallowUntil
        }
        if (swallowing) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) swallowing = false
            return true
        }
        return super.dispatchTouchEvent(ev)
    }
}

/**
 * Damped spring (mass 1) animating one float toward [target], frame by frame on the Choreographer of
 * the main thread. Exact closed-form steps (as in androidx SpringForce), so it settles regardless of
 * frame rate; it stops by itself once within [threshold] of the target with negligible velocity and
 * calls [onUpdate] a final time with the exact target. Main thread only.
 *
 * Choose [threshold] in the value's own units: 0.001 for 0..1 fractions, 0.5 for pixels.
 */
class Spring(
    initial: Float = 0f,
    var stiffness: Float = Motion.STIFFNESS,
    var dampingRatio: Float = Motion.DAMPING,
    var threshold: Float = 0.001f,
    private val onUpdate: (Float) -> Unit
) : Choreographer.FrameCallback {
    var value = initial; private set
    var velocity = 0f; private set
    var target = initial; private set
    var isRunning = false; private set
    /** Called once each time an animateTo() comes to rest (also when it completes at once). */
    var onEnd: (() -> Unit)? = null
    private var lastFrameNanos = 0L

    /** Animates toward [next] from the current value and velocity (retargeting keeps momentum). */
    fun animateTo(next: Float) {
        target = next
        if (!Motion.enabled()) { jump(next); return }
        if (!isRunning) {
            if (abs(value - next) < threshold && abs(velocity) < threshold * 62.5f) { jump(next); return }
            isRunning = true
            lastFrameNanos = 0L
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /** Animates toward [next] starting with [startVelocity] (units per second), e.g. a fling hand-off. */
    fun animateTo(next: Float, startVelocity: Float) {
        velocity = startVelocity
        animateTo(next)
    }

    /** Jumps to [next] immediately (no animation) and reports it. */
    fun snapTo(next: Float) {
        if (isRunning) Choreographer.getInstance().removeFrameCallback(this)
        val wasRunning = isRunning
        isRunning = false
        value = next; target = next; velocity = 0f
        onUpdate(next)
        if (wasRunning) onEnd?.invoke()
    }

    /** Ends at [next] at once and reports it as a finished animation ([onEnd] fires). */
    private fun jump(next: Float) {
        if (isRunning) Choreographer.getInstance().removeFrameCallback(this)
        isRunning = false
        value = next; target = next; velocity = 0f
        onUpdate(next)
        onEnd?.invoke()
    }

    /** Stops where it is (no final update). */
    fun cancel() {
        if (!isRunning) return
        Choreographer.getInstance().removeFrameCallback(this)
        isRunning = false
        velocity = 0f
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!isRunning) return
        if (lastFrameNanos == 0L) {
            // First frame: no elapsed time yet. Step one nominal frame so motion starts immediately.
            lastFrameNanos = frameTimeNanos - 16_666_667L
        }
        val elapsed = ((frameTimeNanos - lastFrameNanos) / 1e9).coerceIn(0.0, 0.064)
        lastFrameNanos = frameTimeNanos
        step(elapsed / Motion.durationScale())
        if (abs(value - target) < threshold && abs(velocity) < threshold * 62.5f) {
            isRunning = false
            value = target; velocity = 0f
            onUpdate(target)
            onEnd?.invoke()
            return
        }
        onUpdate(value)
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun step(t: Double) {
        if (t <= 0.0) return
        val w0 = sqrt(stiffness.toDouble().coerceAtLeast(1.0))
        val zeta = dampingRatio.toDouble().coerceAtLeast(0.01)
        val x0 = (value - target).toDouble()
        val v0 = velocity.toDouble()
        val x: Double
        val v: Double
        when {
            zeta > 1.0 -> {
                val root = w0 * sqrt(zeta * zeta - 1)
                val gPlus = -zeta * w0 + root
                val gMinus = -zeta * w0 - root
                val b = (gMinus * x0 - v0) / (gMinus - gPlus)
                val a = x0 - b
                val eMinus = exp(gMinus * t); val ePlus = exp(gPlus * t)
                x = a * eMinus + b * ePlus
                v = a * gMinus * eMinus + b * gPlus * ePlus
            }
            zeta == 1.0 -> {
                val a = x0; val b = v0 + w0 * x0
                val e = exp(-w0 * t)
                x = (a + b * t) * e
                v = (a + b * t) * e * -w0 + b * e
            }
            else -> {
                val wd = w0 * sqrt(1 - zeta * zeta)
                val cosC = x0
                val sinC = (zeta * w0 * x0 + v0) / wd
                val e = exp(-zeta * w0 * t)
                val c = cos(wd * t); val s = sin(wd * t)
                x = e * (cosC * c + sinC * s)
                v = x * (-zeta * w0) + e * (-wd * cosC * s + wd * sinC * c)
            }
        }
        value = (x + target).toFloat()
        velocity = v.toFloat()
    }
}
