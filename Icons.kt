package dev.codesurgen.ruta

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.View
import kotlin.math.roundToInt

/*
 * Material Symbols Rounded icons from res/drawable: ic_<name>.xml (outlined) and ic_<name>_fill.xml
 * (filled). There is no generated R class, so ids are resolved with getIdentifier and cached. Every
 * symbol the app uses ships as a drawable (check, close, arrow_back, add, remove, chevron_left,
 * expand_less and local_parking included). An unknown name never crashes: it is logged once (tag
 * "Ruta") and drawn as a transparent 24 dp drawable.
 */

object Icons {
    private const val TAG = "Ruta"
    /** Default icon size (dp). */
    const val SIZE = 24
    /** Icon size inside chips and buttons (dp). */
    const val SMALL = 20
    /** Icon size in stat chips (dp). */
    const val CHIP = 18

    private val ids = HashMap<String, Int>()
    private val reported = HashSet<String>()
    /** Optional hook (MainActivity routes it into the diagnostics log); logcat always gets a line. */
    var onMissing: ((String) -> Unit)? = null

    /** Resource id of "ic_" + [name] (+ "_fill"), 0 when missing. A missing filled variant falls back to outlined. */
    fun id(context: Context, name: String, filled: Boolean = false): Int {
        val key = if (filled) "ic_${name}_fill" else "ic_$name"
        ids[key]?.let { return it }
        var found = context.resources.getIdentifier(key, "drawable", context.packageName)
        if (found == 0 && filled) found = id(context, name, false)
        ids[key] = found
        return found
    }

    fun exists(context: Context, name: String): Boolean = id(context, name) != 0

    /**
     * A fresh, mutated drawable for [name] tinted [tint] (24 dp intrinsic size). Never throws:
     * an unknown name gives a transparent 24 dp drawable.
     */
    fun drawable(context: Context, name: String, filled: Boolean = false, tint: Int = Theme.onSurfaceVariant): Drawable {
        val resource = id(context, name, filled)
        val loaded = if (resource != 0) runCatching { context.getDrawable(resource) }.getOrNull() else null
        if (loaded == null) {
            if (reported.add(name)) {
                val message = "Icon '$name' is not in res/drawable"
                Log.w(TAG, message)
                runCatching { onMissing?.invoke(message) }
            }
            return EmptyIcon(context.resources.displayMetrics.density)
        }
        return loaded.mutate().apply { setTint(tint) }
    }

    /** Material Symbols name for a [NavTurn] code (null = no route). */
    fun turnName(turn: Int?): String = when (turn) {
        null -> "navigation"
        NavTurn.LEFT -> "turn_left"
        NavTurn.RIGHT -> "turn_right"
        NavTurn.STRAIGHT -> "straight"
        NavTurn.SLIGHT_LEFT -> "turn_slight_left"
        NavTurn.SLIGHT_RIGHT -> "turn_slight_right"
        NavTurn.SHARP_LEFT -> "turn_sharp_left"
        NavTurn.SHARP_RIGHT -> "turn_sharp_right"
        NavTurn.U_TURN_LEFT -> "u_turn_left"
        NavTurn.U_TURN_RIGHT -> "u_turn_right"
        NavTurn.ROUNDABOUT_LEFT -> "roundabout_left"
        NavTurn.ROUNDABOUT_RIGHT -> "roundabout_right"
        NavTurn.FORK_LEFT, NavTurn.KEEP_LEFT -> "fork_left"
        NavTurn.FORK_RIGHT, NavTurn.KEEP_RIGHT -> "fork_right"
        NavTurn.RAMP_LEFT -> "ramp_left"
        NavTurn.RAMP_RIGHT -> "ramp_right"
        NavTurn.MERGE -> "merge"
        NavTurn.DESTINATION -> "flag"
        NavTurn.WRONG_WAY -> "warning"
        NavTurn.FERRY, NavTurn.FERRY_TRAIN -> "directions"
        else -> "straight"
    }

    /** Spoken description of a turn, for contentDescription. */
    fun turnDescription(turn: Int?): String = when (turn) {
        null -> "No direction"
        NavTurn.WRONG_WAY -> "Wrong way"
        NavTurn.SLIGHT_LEFT -> "Slight left"
        NavTurn.SLIGHT_RIGHT -> "Slight right"
        NavTurn.SHARP_LEFT -> "Sharp left"
        NavTurn.SHARP_RIGHT -> "Sharp right"
        NavTurn.STRAIGHT -> "Straight ahead"
        NavTurn.DESTINATION -> "Destination"
        NavTurn.LEFT -> "Turn left"
        NavTurn.RIGHT -> "Turn right"
        NavTurn.RAMP_LEFT -> "Ramp on the left"
        NavTurn.RAMP_RIGHT -> "Ramp on the right"
        NavTurn.ROUNDABOUT_LEFT -> "Roundabout, clockwise"
        NavTurn.ROUNDABOUT_RIGHT -> "Roundabout, anticlockwise"
        NavTurn.U_TURN_LEFT -> "U-turn left"
        NavTurn.U_TURN_RIGHT -> "U-turn right"
        NavTurn.FORK_LEFT -> "Keep left at the fork"
        NavTurn.FORK_RIGHT -> "Keep right at the fork"
        NavTurn.FERRY_TRAIN -> "Car train"
        NavTurn.FERRY -> "Ferry"
        NavTurn.MERGE -> "Merge"
        NavTurn.KEEP_LEFT -> "Keep left"
        NavTurn.KEEP_RIGHT -> "Keep right"
        else -> "Straight ahead"
    }
}

/** Shorthand for [Icons.drawable]. */
fun icon(context: Context, name: String, filled: Boolean = false, tint: Int = Theme.onSurfaceVariant): Drawable =
    Icons.drawable(context, name, filled, tint)

/** Shorthand for [Icons.drawable] with this context. */
@JvmName("contextIcon")
fun Context.icon(name: String, filled: Boolean = false, tint: Int = Theme.onSurfaceVariant): Drawable =
    Icons.drawable(this, name, filled, tint)

/**
 * What an unknown icon name draws: nothing, at the 24 dp intrinsic size, so layouts keep their shape
 * and the app never crashes on a typo (build.sh and tests/check_icons.py catch known names).
 */
private class EmptyIcon(density: Float) : Drawable() {
    private val size = (24 * density).roundToInt()
    override fun draw(canvas: Canvas) {}
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSPARENT
    override fun getIntrinsicWidth(): Int = size
    override fun getIntrinsicHeight(): Int = size
}

/**
 * One icon drawn centred at [iconSizeDp] (24 dp default, 20 dp in chips) inside whatever size the
 * view is laid out at (give it 48 dp to make a touch target). With a [description] it is announced;
 * without one it is decorative and skipped by TalkBack. Change [name]/[filled]/[tint] freely: the
 * drawable is reloaded only when name or filled changes.
 */
class IconView(
    context: Context,
    name: String? = null,
    filled: Boolean = false,
    tint: Int = Theme.onSurfaceVariant,
    iconSizeDp: Int = Icons.SIZE,
    description: String? = null
) : View(context) {
    private var drawable: Drawable? = null
    private var batching = false
    var name: String? = name
        set(value) { if (field != value) { field = value; if (!batching) reload() } }
    var filled: Boolean = filled
        set(value) { if (field != value) { field = value; if (!batching) reload() } }
    var tint: Int = tint
        set(value) { if (field != value) { field = value; drawable?.setTint(value); invalidate() } }
    var iconSizeDp: Int = iconSizeDp
        set(value) { if (field != value) { field = value; place(); requestLayout() } }

    init {
        setDescription(description)
        reload()
    }

    /** Sets name and style together (one reload). */
    fun setIcon(name: String?, filled: Boolean = this.filled) {
        if (name == this.name && filled == this.filled) return
        batching = true
        this.name = name; this.filled = filled
        batching = false
        reload()
    }

    /** Shows the Material Symbol for a [NavTurn] code and describes it. */
    fun setTurn(turn: Int?) {
        setIcon(Icons.turnName(turn), filled = true)
        setDescription(Icons.turnDescription(turn))
    }

    /** null = decorative (not announced). */
    fun setDescription(text: CharSequence?) {
        if (contentDescription != text) contentDescription = text
        importantForAccessibility = if (text == null) IMPORTANT_FOR_ACCESSIBILITY_NO else IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    private fun reload() {
        val current = name
        drawable?.callback = null
        drawable = if (current == null) null else Icons.drawable(context, current, filled, tint).also { it.callback = this }
        place()
        invalidate()
    }

    private fun place() {
        val d = drawable ?: return
        val size = (iconSizeDp * resources.displayMetrics.density).roundToInt()
        val left = (width - size) / 2; val top = (height - size) / 2
        d.setBounds(left, top, left + size, top + size)
    }

    override fun verifyDrawable(who: Drawable): Boolean = who === drawable || super.verifyDrawable(who)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = (iconSizeDp * resources.displayMetrics.density).roundToInt()
        setMeasuredDimension(
            resolveSize(size + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(size + paddingTop + paddingBottom, heightMeasureSpec)
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w, h, oldw, oldh); place() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawable?.draw(canvas)
    }
}
