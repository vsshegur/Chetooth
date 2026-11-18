package dev.codesurgen.ruta

import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Animatable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin

/*
 * Ruta Material 3 Expressive components as plain framework Views/Drawables (no AppCompat,
 * androidx or Material libraries). Main thread only. Colours come from [Theme] (call Theme.init
 * first), type from [Typography], corners from [Shapes], spacing from [Spacing], motion from
 * [Spring]/[Motion]. API reference with examples: docs/UIKIT.md.
 */

// ================================================================================== primitives

/**
 * Filled (and optionally stroked) rounded rectangle with independent corner radii in px that can
 * change every frame without allocation (shape morphs). Radii larger than half the short side are
 * clamped per corner, so [Shapes.FULL] gives a pill while other corners stay as asked. Optional
 * insets keep a 32/40 dp visual inside a 48 dp touch target. Provides its outline for shadows and
 * clipToOutline.
 */
class RoundedShapeDrawable(color: Int = Color.TRANSPARENT, radiusPx: Float = 0f) : Drawable() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val path = Path()
    private val strokePath = Path()
    private val rect = RectF()
    private val requested = floatArrayOf(radiusPx, radiusPx, radiusPx, radiusPx)
    private val radii = FloatArray(8)
    private val inset = Rect()
    private var drawAlpha = 255

    var color: Int = color
        set(value) { if (field != value) { field = value; invalidateSelf() } }
    var strokeColor: Int = Color.TRANSPARENT
        set(value) { if (field != value) { field = value; invalidateSelf() } }
    /** Stroke width in px (drawn inside the shape). */
    var strokeWidth: Float = 0f
        set(value) { if (field != value) { field = value; rebuild(); invalidateSelf() } }

    fun setInsets(left: Int, top: Int, right: Int, bottom: Int) {
        if (inset.left == left && inset.top == top && inset.right == right && inset.bottom == bottom) return
        inset.set(left, top, right, bottom); rebuild(); invalidateSelf()
    }

    fun setRadius(px: Float) = setRadii(px, px, px, px)

    /** Corner radii in px: top-left, top-right, bottom-right, bottom-left. */
    fun setRadii(topLeft: Float, topRight: Float, bottomRight: Float, bottomLeft: Float) {
        if (requested[0] == topLeft && requested[1] == topRight && requested[2] == bottomRight && requested[3] == bottomLeft) return
        requested[0] = topLeft; requested[1] = topRight; requested[2] = bottomRight; requested[3] = bottomLeft
        rebuild(); invalidateSelf()
    }

    /** The visual rectangle (bounds minus insets). */
    fun shapeBounds(out: RectF) { out.set(rect) }

    override fun onBoundsChange(bounds: Rect) { rebuild() }

    private fun rebuild() {
        val b = bounds
        rect.set((b.left + inset.left).toFloat(), (b.top + inset.top).toFloat(), (b.right - inset.right).toFloat(), (b.bottom - inset.bottom).toFloat())
        val limit = max(0f, min(rect.width(), rect.height()) / 2f)
        for (i in 0..3) { val r = requested[i].coerceIn(0f, limit); radii[2 * i] = r; radii[2 * i + 1] = r }
        path.reset()
        if (rect.width() > 0 && rect.height() > 0) path.addRoundRect(rect, radii, Path.Direction.CW)
        strokePath.reset()
        if (strokeWidth > 0f && rect.width() > strokeWidth && rect.height() > strokeWidth) {
            val half = strokeWidth / 2f
            rect.inset(half, half)
            for (i in 0..7) radii[i] = max(0f, radii[i] - half)
            strokePath.addRoundRect(rect, radii, Path.Direction.CW)
            rect.inset(-half, -half)
            for (i in 0..3) { val r = requested[i].coerceIn(0f, limit); radii[2 * i] = r; radii[2 * i + 1] = r }
        }
    }

    override fun draw(canvas: Canvas) {
        if (Color.alpha(color) != 0) {
            fill.color = color
            if (drawAlpha < 255) fill.alpha = Color.alpha(color) * drawAlpha / 255
            canvas.drawPath(path, fill)
        }
        if (strokeWidth > 0f && Color.alpha(strokeColor) != 0) {
            stroke.color = strokeColor
            stroke.strokeWidth = strokeWidth
            if (drawAlpha < 255) stroke.alpha = Color.alpha(strokeColor) * drawAlpha / 255
            canvas.drawPath(strokePath, stroke)
        }
    }

    override fun getOutline(outline: Outline) {
        if (rect.isEmpty) { outline.setEmpty(); return }
        val r = radii[0]
        if (radii[2] == r && radii[4] == r && radii[6] == r) {
            outline.setRoundRect(rect.left.roundToInt(), rect.top.roundToInt(), rect.right.roundToInt(), rect.bottom.roundToInt(), r)
        } else if (Build.VERSION.SDK_INT >= 30) {
            outline.setPath(path)
        } else {
            outline.setRect(rect.left.roundToInt(), rect.top.roundToInt(), rect.right.roundToInt(), rect.bottom.roundToInt())
        }
        outline.alpha = if (Color.alpha(color) == 0) 0f else drawAlpha / 255f
    }

    override fun setAlpha(alpha: Int) { if (drawAlpha != alpha) { drawAlpha = alpha; invalidateSelf() } }
    override fun getAlpha(): Int = drawAlpha
    override fun setColorFilter(colorFilter: ColorFilter?) { fill.colorFilter = colorFilter; stroke.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Ripple tinted [color] over [content], clipped to [mask] (or to the content's pixels when null). */
fun rippleOver(content: Drawable?, mask: Drawable?, color: Int): RippleDrawable =
    RippleDrawable(ColorStateList.valueOf(alpha(color, 0.20f)), content, mask)

/** Colour pair for tonal containers (chips, list icons, tiles). */
enum class Tone {
    NEUTRAL, PRIMARY, SECONDARY, TERTIARY, ERROR, WARNING;

    /** Container fill. */
    val container: Int get() = when (this) {
        NEUTRAL -> Theme.surfaceContainerHigh
        PRIMARY -> Theme.primaryContainer
        SECONDARY -> Theme.secondaryContainer
        TERTIARY -> Theme.tertiaryContainer
        ERROR -> Theme.errorContainer
        WARNING -> Theme.warningContainer
    }

    /** Icon/text on [container]. */
    val onContainer: Int get() = when (this) {
        NEUTRAL -> Theme.onSurfaceVariant
        PRIMARY -> Theme.onPrimaryContainer
        SECONDARY -> Theme.onSecondaryContainer
        TERTIARY -> Theme.onTertiaryContainer
        ERROR -> Theme.onErrorContainer
        WARNING -> Theme.onWarningContainer
    }

    /** Accent used on neutral surfaces (icon tint in tiles/chips). */
    val accent: Int get() = when (this) {
        NEUTRAL -> Theme.onSurfaceVariant
        PRIMARY -> Theme.primary
        SECONDARY -> Theme.secondary
        TERTIARY -> Theme.tertiary
        ERROR -> Theme.error
        WARNING -> Theme.warning
    }
}

private fun View.px(dp: Float): Float = dp * resources.displayMetrics.density
private fun View.pxi(dp: Int): Int = (dp * resources.displayMetrics.density).roundToInt()
private fun View.pxi(dp: Float): Int = (dp * resources.displayMetrics.density).roundToInt()

/** Checkable state for TalkBack (API 36 tri-state API when available). */
@Suppress("DEPRECATION")
internal fun AccessibilityNodeInfo.markChecked(on: Boolean) {
    isCheckable = true
    if (Build.VERSION.SDK_INT >= 36) setChecked(if (on) AccessibilityNodeInfo.CHECKED_STATE_TRUE else AccessibilityNodeInfo.CHECKED_STATE_FALSE)
    else isChecked = on
}

/** Linear interpolation. */
fun lerp(from: Float, to: Float, t: Float): Float = from + (to - from) * t

// ---------------------------------------------------------------------------------- scroll listeners

private val scrollListeners = WeakHashMap<View, MutableList<(Int, Int) -> Unit>>()

/**
 * Adds a scroll listener ([scrollY], [dy]) to a scrolling view without replacing others (a View has
 * one OnScrollChangeListener; TopAppBar and ExtendedFab both need it). Main thread.
 */
fun View.onScroll(listener: (scrollY: Int, dy: Int) -> Unit) {
    val list = scrollListeners.getOrPut(this) {
        mutableListOf<(Int, Int) -> Unit>().also { created ->
            setOnScrollChangeListener { _, _, y, _, oldY -> for (l in created) l(y, y - oldY) }
        }
    }
    list += listener
}

// ---------------------------------------------------------------------------------- text helpers

/** TextView in [style] and [color] (single source of type styling). */
fun Context.textView(text: CharSequence = "", style: TextStyle = Typography.bodyMedium, color: Int = Theme.onSurface): TextView =
    TextView(this).apply {
        applyStyle(style)
        setTextColor(color)
        this.text = text
    }

/** One-line, ellipsized TextView (titles, values). */
fun Context.singleLine(text: CharSequence = "", style: TextStyle = Typography.titleMedium, color: Int = Theme.onSurface): TextView =
    textView(text, style, color).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }

/** Section header between groups: Title S in primary, announced as a heading. */
fun Context.sectionHeader(text: String): TextView = textView(text, Typography.titleSmall, Theme.primary).apply {
    isAccessibilityHeading = true
    setPadding(dp(Spacing.L), dp(Spacing.S), dp(Spacing.L), dp(Spacing.S))
}

/** Hairline divider in outlineVariant; [insetStartDp] aligns it with text after a leading icon. */
fun Context.divider(insetStartDp: Int = 0): View = View(this).apply {
    setBackgroundColor(Theme.outlineVariant)
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    layoutParams = LinearLayout.LayoutParams(MATCH, max(1, dp(1) / 2)).apply { marginStart = dp(insetStartDp) }
}

/** Empty vertical space. */
fun Context.spacer(heightDp: Int): View = View(this).apply {
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    layoutParams = LinearLayout.LayoutParams(MATCH, dp(heightDp))
}

/** Long-press tooltip (explanations live here, not on the surface). */
fun View.tooltip(text: CharSequence?): View { tooltipText = text; return this }

/**
 * Changes a number with a short roll-up crossfade (DESIGN A4: never a hard jump while visible);
 * instant when hidden, empty before, or with animations off.
 */
fun TextView.roll(next: CharSequence) {
    if (TextUtils.equals(text, next)) return
    val animate = isShown && isLaidOut && text.isNotEmpty() && Motion.enabled()
    text = next
    if (!animate) return
    animate().cancel()
    alpha = 0.4f
    translationY = 6f * resources.displayMetrics.density
    animate().alpha(1f).translationY(0f).setDuration(Motion.SHORT4).setInterpolator(Motion.emphasizedDecelerate).start()
}

/** DESIGN A4 press feedback: [view] springs to 0.97 scale while pressed (clickable and enabled only). Call [update] from drawableStateChanged. */
internal class PressScale(private val view: View) {
    private val spring = Spring(1f, Motion.STIFFNESS_FAST, Motion.DAMPING_FAST, 0.0005f) { s -> view.scaleX = s; view.scaleY = s }
    fun update() {
        val target = if (view.isPressed && view.isClickable && view.isEnabled) 0.97f else 1f
        if (spring.target != target) spring.animateTo(target)
    }
}

/**
 * Double-tap guard (W3: every tap does exactly one thing): a second click on the same view within
 * [WINDOW_MS] is dropped, so a quick double tap never opens two sheets, connects then cancels, or
 * skips two steps. Every kit control that opens or changes something uses it; segmented / media
 * controls (deliberate rapid taps) do not. Main thread.
 */
object ClickGuard {
    const val WINDOW_MS = 400L
    private val last = WeakHashMap<View, Long>()

    /** True when [view]'s click should run (and records it). */
    fun accept(view: View): Boolean {
        val now = SystemClock.uptimeMillis()
        val previous = last[view]
        if (previous != null && now - previous in 0 until WINDOW_MS) return false
        last[view] = now
        return true
    }
}

/** setOnClickListener through [ClickGuard] (one action per tap, double taps dropped). */
fun View.setOnSingleClick(action: () -> Unit) = setOnClickListener { if (ClickGuard.accept(it)) action() }

/** The first shown [ExtendedFab] in this view's tree (depth first), e.g. a snackbar anchor; null when none. */
fun View.findShownFab(): ExtendedFab? {
    if (!isShown) return null
    if (this is ExtendedFab) return this
    if (this is Scaffold) return fab?.takeIf { it.isShown } // Its FAB is the only one; skip the page tree.
    if (this is ViewGroup) for (i in childCount - 1 downTo 0) getChildAt(i).findShownFab()?.let { return it }
    return null
}

/**
 * Centred placeholder: 48 dp icon in a tonal circle, Title M headline, optional one-line supporting
 * text and an optional action (e.g. a tonal button).
 */
fun Context.emptyState(icon: String, title: String, message: String? = null, action: View? = null): LinearLayout = column().apply {
    gravity = Gravity.CENTER_HORIZONTAL
    setPadding(dp(Spacing.L), dp(Spacing.XL), dp(Spacing.L), dp(Spacing.XL))
    val circle = FrameLayout(context).apply {
        background = RoundedShapeDrawable(Theme.surfaceContainerHigh, dpf(Shapes.FULL))
        addView(IconView(context, icon, tint = Theme.onSurfaceVariant, iconSizeDp = 28), FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
    }
    addView(circle, LinearLayout.LayoutParams(dp(56), dp(56)))
    addView(textView(title, Typography.titleMedium, Theme.onSurface).apply { gravity = Gravity.CENTER }, params(Spacing.L, WRAP))
    if (message != null) addView(textView(message, Typography.bodyMedium, Theme.onSurfaceVariant).apply { gravity = Gravity.CENTER }, params(Spacing.XS, WRAP))
    if (action != null) addView(action, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(Spacing.L) })
}

// ================================================================================== buttons

enum class ButtonVariant { FILLED, TONAL, OUTLINED, TEXT, ELEVATED, DESTRUCTIVE }

/** Visual height (the touch target is always ≥ 48 dp), icon size, side padding, pressed corner. */
enum class ButtonSize(val heightDp: Int, val iconDp: Int, val paddingDp: Int, val pressedRadiusDp: Float) {
    SMALL(40, 20, 24, 8f),
    MEDIUM(56, 24, 28, 12f)
}

/**
 * M3 common button (filled / tonal / outlined / text / elevated / destructive), optional leading
 * icon. Pill shape that morphs toward a rounded square while pressed (fast spring). 48 dp touch
 * target around a 40 dp visual ([ButtonSize.SMALL]) or 56 dp ([ButtonSize.MEDIUM], hero actions).
 */
class ActionButton(
    context: Context,
    text: CharSequence,
    variant: ButtonVariant = ButtonVariant.FILLED,
    icon: String? = null,
    val size: ButtonSize = ButtonSize.SMALL,
    private var onClick: (() -> Unit)? = null
) : LinearLayout(context) {
    private val shape = RoundedShapeDrawable()
    private val mask = RoundedShapeDrawable(Color.WHITE)
    private val ripple: RippleDrawable
    private val iconView = IconView(context, icon, iconSizeDp = size.iconDp)
    private val label = TextView(context)
    private var press: Spring? = null
    private var iconName: String? = icon

    var variant: ButtonVariant = variant
        set(value) { if (field != value) { field = value; restyle() } }

    /** The label; also the accessible name (the label TextView itself is hidden from TalkBack). */
    var text: CharSequence
        get() = label.text
        set(value) { label.show(value); if (!TextUtils.equals(contentDescription, value)) contentDescription = value }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        contentDescription = text
        val visual = pxi(size.heightDp)
        val target = max(visual, pxi(Spacing.TOUCH))
        val insetV = (target - visual) / 2
        minimumHeight = target
        shape.setInsets(0, insetV, 0, insetV); mask.setInsets(0, insetV, 0, insetV)
        val rest = px(size.heightDp / 2f)
        shape.setRadius(rest); mask.setRadius(rest)
        ripple = rippleOver(shape, mask, Theme.onSurface)
        background = ripple
        label.applyStyle(if (size == ButtonSize.MEDIUM) Typography.titleMedium else Typography.labelLarge)
        label.maxLines = 1
        label.ellipsize = TextUtils.TruncateAt.END
        label.text = text
        label.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(iconView, LayoutParams(pxi(size.iconDp), pxi(size.iconDp)).apply { marginEnd = pxi(8) })
        addView(label, LayoutParams(WRAP, WRAP))
        isClickable = true
        isFocusable = true
        setOnSingleClick { this.onClick?.invoke() }
        press = Spring(0f, Motion.STIFFNESS_FAST, Motion.DAMPING_FAST, 0.002f) { f ->
            val r = lerp(px(size.heightDp / 2f), px(size.pressedRadiusDp), f)
            shape.setRadius(r); mask.setRadius(r)
        }
        restyle()
    }

    fun setOnClick(action: (() -> Unit)?) { onClick = action }

    /** Leading icon (null hides it). */
    fun setIcon(name: String?, filled: Boolean = false) {
        iconName = name
        iconView.setIcon(name, filled)
        restyle()
    }

    override fun setEnabled(enabled: Boolean) {
        if (enabled == isEnabled) return
        super.setEnabled(enabled)
        restyle()
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        press?.animateTo(if (isPressed) 1f else 0f)
    }

    override fun getAccessibilityClassName(): CharSequence = Button::class.java.name

    private fun restyle() {
        val container: Int; val content: Int; var strokeColor = Color.TRANSPARENT
        when (variant) {
            ButtonVariant.FILLED -> { container = Theme.primary; content = Theme.onPrimary }
            ButtonVariant.TONAL -> { container = Theme.secondaryContainer; content = Theme.onSecondaryContainer }
            ButtonVariant.OUTLINED -> { container = Color.TRANSPARENT; content = Theme.primary; strokeColor = Theme.outlineVariant }
            ButtonVariant.TEXT -> { container = Color.TRANSPARENT; content = Theme.primary }
            ButtonVariant.ELEVATED -> { container = Theme.surfaceContainerLow; content = Theme.primary }
            ButtonVariant.DESTRUCTIVE -> { container = Theme.error; content = Theme.onError }
        }
        val on = isEnabled
        shape.color = if (on || container == Color.TRANSPARENT) container else Theme.disabledContainer
        shape.strokeColor = if (on) strokeColor else if (strokeColor != Color.TRANSPARENT) Theme.disabledContainer else Color.TRANSPARENT
        shape.strokeWidth = if (strokeColor != Color.TRANSPARENT) px(1f) else 0f
        val ink = if (on) content else Theme.disabledContent
        label.setTextColor(ink)
        iconView.tint = ink
        iconView.visible(iconName != null)
        ripple.setColor(ColorStateList.valueOf(alpha(content, 0.20f)))
        elevation = if (variant == ButtonVariant.ELEVATED && on) px(Elevation.LEVEL1) else 0f
        val text = variant == ButtonVariant.TEXT
        val side = pxi(if (text) 12 else size.paddingDp)
        val start = if (iconName != null) pxi(if (text) 12 else size.paddingDp - 8) else side
        setPaddingRelative(start, 0, side, 0)
    }
}

/** Builder for [ActionButton]. */
fun Context.actionButton(
    text: CharSequence, variant: ButtonVariant = ButtonVariant.FILLED, icon: String? = null,
    size: ButtonSize = ButtonSize.SMALL, onClick: () -> Unit
): ActionButton = ActionButton(this, text, variant, icon, size, onClick)

fun Context.filledButton(text: CharSequence, icon: String? = null, onClick: () -> Unit) = ActionButton(this, text, ButtonVariant.FILLED, icon, onClick = onClick)
fun Context.tonalButton(text: CharSequence, icon: String? = null, onClick: () -> Unit) = ActionButton(this, text, ButtonVariant.TONAL, icon, onClick = onClick)
fun Context.outlinedButton(text: CharSequence, icon: String? = null, onClick: () -> Unit) = ActionButton(this, text, ButtonVariant.OUTLINED, icon, onClick = onClick)

enum class IconButtonVariant { STANDARD, FILLED, TONAL, OUTLINED }

/**
 * M3 icon button (standard / filled / tonal / outlined) with a [containerDp] visual (40 default,
 * 56 medium, 96 large) inside a ≥ 48 dp touch target. [description] is required (spoken + tooltip).
 * [toggleable]: clicks flip [checked] (filled icon, selected colours, round → rounded-square morph).
 */
class IconActionButton(
    context: Context,
    icon: String,
    description: String,
    val variant: IconButtonVariant = IconButtonVariant.STANDARD,
    val containerDp: Int = 40,
    val toggleable: Boolean = false,
    private var onClick: (() -> Unit)? = null
) : View(context) {
    private val shape = RoundedShapeDrawable()
    private val mask = RoundedShapeDrawable(Color.WHITE)
    private val ripple = rippleOver(shape, mask, Theme.onSurface)
    private var iconName = icon
    private var drawable: Drawable? = null
    private val iconPx = pxi(if (containerDp >= 80) 32 else 24)
    private var press: Spring? = null
    private var pressFraction = 0f
    /** Fired after a user toggle (not when set from code). */
    var onCheckedChange: ((Boolean) -> Unit)? = null

    var checked: Boolean = false
        set(value) { if (field != value) { field = value; restyle(); updateShape() } }

    init {
        contentDescription = description
        tooltipText = description
        background = ripple
        isClickable = true
        isFocusable = true
        setOnSingleClick {
            if (toggleable) { checked = !checked; onCheckedChange?.invoke(checked) }
            this.onClick?.invoke()
        }
        press = Spring(0f, Motion.STIFFNESS_FAST, Motion.DAMPING_FAST, 0.002f) { f -> pressFraction = f; updateShape() }
        restyle()
        updateShape()
    }

    fun setOnClick(action: (() -> Unit)?) { onClick = action }

    /** Swaps the icon (e.g. play ↔ pause) and optionally the spoken description. */
    fun setIcon(name: String, description: String? = null) {
        if (description != null && contentDescription != description) { contentDescription = description; tooltipText = description }
        if (name == iconName) return
        iconName = name
        restyle()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = max(pxi(containerDp), pxi(Spacing.TOUCH))
        setMeasuredDimension(resolveSize(size, widthMeasureSpec), resolveSize(size, heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val c = pxi(containerDp)
        val ix = max(0, (w - c) / 2); val iy = max(0, (h - c) / 2)
        shape.setInsets(ix, iy, ix, iy); mask.setInsets(ix, iy, ix, iy)
        placeIcon()
    }

    private fun placeIcon() {
        val d = drawable ?: return
        val l = (width - iconPx) / 2; val t = (height - iconPx) / 2
        d.setBounds(l, t, l + iconPx, t + iconPx)
    }

    private fun updateShape() {
        val c = px(containerDp.toFloat())
        val rest = if (toggleable && checked) c * 0.3f else c / 2f
        val r = lerp(rest, c * 0.25f, pressFraction)
        shape.setRadius(r); mask.setRadius(r)
    }

    override fun setEnabled(enabled: Boolean) {
        if (enabled == isEnabled) return
        super.setEnabled(enabled)
        restyle()
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        press?.animateTo(if (isPressed) 1f else 0f)
    }

    private fun restyle() {
        val on = checked && toggleable
        val container: Int; val content: Int; var strokeColor = Color.TRANSPARENT
        when (variant) {
            IconButtonVariant.STANDARD -> { container = Color.TRANSPARENT; content = if (on) Theme.primary else Theme.onSurfaceVariant }
            IconButtonVariant.FILLED ->
                if (!toggleable || on) { container = Theme.primary; content = Theme.onPrimary }
                else { container = Theme.surfaceContainerHighest; content = Theme.primary }
            IconButtonVariant.TONAL ->
                if (!toggleable || on) { container = Theme.secondaryContainer; content = Theme.onSecondaryContainer }
                else { container = Theme.surfaceContainerHighest; content = Theme.onSurfaceVariant }
            IconButtonVariant.OUTLINED ->
                if (on) { container = Theme.inverseSurface; content = Theme.inverseOnSurface }
                else { container = Color.TRANSPARENT; content = Theme.onSurfaceVariant; strokeColor = Theme.outlineVariant }
        }
        val enabled = isEnabled
        shape.color = if (enabled || container == Color.TRANSPARENT) container else Theme.disabledContainer
        shape.strokeColor = strokeColor
        shape.strokeWidth = if (strokeColor != Color.TRANSPARENT) px(1f) else 0f
        val ink = if (enabled) content else Theme.disabledContent
        ripple.setColor(ColorStateList.valueOf(alpha(content, 0.20f)))
        drawable?.callback = null
        drawable = Icons.drawable(context, iconName, filled = on || variant == IconButtonVariant.FILLED, tint = ink).also { it.callback = this }
        placeIcon()
        invalidate()
    }

    override fun verifyDrawable(who: Drawable): Boolean = who === drawable || super.verifyDrawable(who)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawable?.draw(canvas)
    }

    override fun getAccessibilityClassName(): CharSequence =
        if (toggleable) android.widget.ToggleButton::class.java.name else Button::class.java.name

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        if (toggleable) { info.markChecked(checked) }
    }
}

/** One segment of a [ButtonGroup]: text and/or icon; [description] is spoken (defaults to text). */
class GroupItem(val text: String? = null, val icon: String? = null, val description: String? = text)

/**
 * M3 Expressive connected button group. [selectable] = single-select segmented control (24h / 7d /
 * 30d): the selected segment fills with primary and morphs to a full pill. Otherwise an action
 * group (prev / play-pause / next): tonal segments, [setEmphasized] makes one primary.
 * Segments are equal width when the group is given a width (MATCH or weight); 2 dp gaps, outer
 * corners full, inner corners 8 dp. Visual [heightDp] 40 (48 dp touch target) or 56.
 */
class ButtonGroup(
    context: Context,
    items: List<GroupItem>,
    val selectable: Boolean = true,
    selected: Int = 0,
    val heightDp: Int = 40,
    private val onClick: (Int) -> Unit
) : LinearLayout(context) {
    var selected: Int = if (selectable) selected.coerceIn(0, max(0, items.size - 1)) else -1; private set
    private var emphasized = -1
    private val segments = items.mapIndexed { index, item -> Segment(index, items.size, item) }

    init {
        orientation = HORIZONTAL
        segments.forEachIndexed { index, segment ->
            addView(segment, LayoutParams(0, max(pxi(heightDp), pxi(Spacing.TOUCH)), 1f).apply { if (index > 0) marginStart = pxi(2) })
        }
        segments.forEach { it.refresh(animate = false) }
    }

    /** Selects [index] (selectable groups); [notify] also calls onClick. */
    fun select(index: Int, notify: Boolean = false) {
        if (!selectable || index !in segments.indices) return
        val changed = index != selected
        selected = index
        segments.forEach { it.refresh(animate = true) }
        if (changed && notify) onClick(index)
    }

    /** Action groups: segment [index] uses primary colours (e.g. play/pause); -1 = none. */
    fun setEmphasized(index: Int) {
        if (emphasized == index) return
        emphasized = index
        segments.forEach { it.refresh(animate = true) }
    }

    /** Replaces a segment's icon/text/description (e.g. play ↔ pause). */
    fun setItem(index: Int, text: String? = null, icon: String? = null, description: String? = text) {
        segments.getOrNull(index)?.set(text, icon, description)
    }

    fun setSegmentEnabled(index: Int, enabled: Boolean) {
        segments.getOrNull(index)?.let { it.isEnabled = enabled; it.refresh(animate = false) }
    }

    private inner class Segment(val index: Int, val count: Int, item: GroupItem) : LinearLayout(context) {
        private val shape = RoundedShapeDrawable()
        private val ripple = rippleOver(shape, null, Theme.onSurface)
        private val iconView = IconView(context, item.icon, iconSizeDp = if (heightDp >= 56) 24 else 20)
        private val label = TextView(context)
        private var fraction = 0f
        private val spring = Spring(0f, threshold = 0.002f) { f -> fraction = f; paint() }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            val visual = pxi(heightDp)
            val inset = max(0, (pxi(Spacing.TOUCH) - visual) / 2)
            shape.setInsets(0, inset, 0, inset)
            background = ripple
            label.applyStyle(if (heightDp >= 56) Typography.titleMedium else Typography.labelLarge)
            label.maxLines = 1
            label.ellipsize = TextUtils.TruncateAt.END
            label.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(iconView, LayoutParams(WRAP, WRAP))
            addView(label, LayoutParams(WRAP, WRAP).apply { marginStart = pxi(6) })
            setPadding(pxi(12), 0, pxi(12), 0)
            minimumWidth = pxi(Spacing.TOUCH)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (selectable) select(index, notify = true) else onClick(index)
            }
            set(item.text, item.icon, item.description)
        }

        fun set(text: String?, icon: String?, description: String?) {
            iconView.setIcon(icon)
            iconView.visible(icon != null)
            label.show(text.orEmpty())
            label.visible(!text.isNullOrEmpty())
            (label.layoutParams as? LayoutParams)?.marginStart = if (icon != null && !text.isNullOrEmpty()) pxi(6) else 0
            contentDescription = description ?: text
            // Icon-only segments (prev/play/next) explain themselves on long-press.
            tooltipText = if (text.isNullOrEmpty()) description else null
        }

        fun refresh(animate: Boolean) {
            val on = if (selectable) index == selected else index == emphasized
            isSelected = selectable && on
            stateDescription = if (selectable) (if (on) "Selected" else "Not selected") else null
            if (animate) spring.animateTo(if (on) 1f else 0f) else spring.snapTo(if (on) 1f else 0f)
            paint()
        }

        private fun paint() {
            val f = fraction.coerceIn(0f, 1f)
            val off: Int; val offInk: Int; val onFill: Int; val onInk: Int
            if (selectable) { off = Theme.surfaceContainerHigh; offInk = Theme.onSurfaceVariant; onFill = Theme.primary; onInk = Theme.onPrimary }
            else { off = Theme.secondaryContainer; offInk = Theme.onSecondaryContainer; onFill = Theme.primary; onInk = Theme.onPrimary }
            val enabled = isEnabled
            shape.color = if (enabled) blend(off, onFill, f) else Theme.disabledContainer
            val ink = if (enabled) blend(offInk, onInk, f) else Theme.disabledContent
            label.setTextColor(ink)
            iconView.tint = ink
            ripple.setColor(ColorStateList.valueOf(alpha(ink, 0.20f)))
            val full = px(heightDp / 2f)
            val inner = px(8f)
            val first = index == 0; val last = index == count - 1
            val left = lerp(if (first) full else inner, full, f)
            val right = lerp(if (last) full else inner, full, f)
            shape.setRadii(left, right, right, left)
        }

        override fun getAccessibilityClassName(): CharSequence =
            if (selectable) android.widget.RadioButton::class.java.name else Button::class.java.name

        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            if (selectable) { info.markChecked(index == selected) }
        }
    }
}

enum class FabColor { PRIMARY_CONTAINER, PRIMARY, SECONDARY_CONTAINER, TERTIARY_CONTAINER, SURFACE }

/**
 * Extended FAB: 56 dp, 16 dp corners, icon + label (Title M). [extended] = false collapses to the
 * 56 dp icon-only FAB with a spring (label clipped and faded); [bindScroll] collapses it while the
 * page scrolls down and expands it on scroll up / at the top. The label stays the spoken name.
 */
class ExtendedFab(
    context: Context,
    icon: String,
    text: String,
    val color: FabColor = FabColor.PRIMARY_CONTAINER,
    private var onClick: (() -> Unit)? = null
) : ViewGroup(context) {
    private val shape = RoundedShapeDrawable()
    private val ripple: RippleDrawable
    private val iconView: IconView
    private val label = TextView(context)
    private var progress = 1f
    private var spring: Spring? = null

    var extended: Boolean = true; private set

    init {
        val container: Int; val content: Int
        when (color) {
            FabColor.PRIMARY_CONTAINER -> { container = Theme.primaryContainer; content = Theme.onPrimaryContainer }
            FabColor.PRIMARY -> { container = Theme.primary; content = Theme.onPrimary }
            FabColor.SECONDARY_CONTAINER -> { container = Theme.secondaryContainer; content = Theme.onSecondaryContainer }
            FabColor.TERTIARY_CONTAINER -> { container = Theme.tertiaryContainer; content = Theme.onTertiaryContainer }
            FabColor.SURFACE -> { container = Theme.surfaceContainerHigh; content = Theme.primary }
        }
        shape.color = container
        shape.setRadius(px(Shapes.FAB))
        ripple = rippleOver(shape, null, content)
        background = ripple
        iconView = IconView(context, icon, tint = content)
        label.applyStyle(Typography.titleMedium)
        label.setTextColor(content)
        label.maxLines = 1
        label.text = text
        label.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(iconView)
        addView(label)
        contentDescription = text
        tooltipText = text // The FAB collapses to its icon on scroll.
        elevation = px(Elevation.LEVEL3)
        outlineProvider = ViewOutlineProvider.BACKGROUND
        clipToOutline = true
        isClickable = true
        isFocusable = true
        setOnSingleClick { this.onClick?.invoke() }
        spring = Spring(1f, threshold = 0.002f) { p ->
            progress = p
            label.alpha = ((p - 0.35f) / 0.65f).coerceIn(0f, 1f)
            requestLayout()
        }
    }

    fun setOnClick(action: (() -> Unit)?) { onClick = action }

    fun setText(text: String) { label.show(text); contentDescription = text; tooltipText = text; requestLayout() }

    fun setIcon(name: String) { iconView.setIcon(name) }

    fun setExtended(value: Boolean, animate: Boolean = true) {
        extended = value
        val target = if (value) 1f else 0f
        if (animate && isAttachedToWindow) spring?.animateTo(target) else spring?.snapTo(target)
    }

    /** Collapses while [scroller] scrolls down, expands on scroll up and at the top. */
    fun bindScroll(scroller: View) {
        val threshold = pxi(4)
        scroller.onScroll { y, dy ->
            when {
                y <= 0 -> if (!extended) setExtended(true)
                dy > threshold -> if (extended) setExtended(false)
                dy < -threshold -> if (!extended) setExtended(true)
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val iconSize = pxi(24)
        iconView.measure(MeasureSpec.makeMeasureSpec(iconSize, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(iconSize, MeasureSpec.EXACTLY))
        label.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val collapsed = pxi(56)
        val expanded = pxi(16) + iconSize + pxi(12) + label.measuredWidth + pxi(20)
        val width = lerp(collapsed.toFloat(), expanded.toFloat(), progress).roundToInt().coerceAtLeast(collapsed)
        val height = max(pxi(56), label.measuredHeight + pxi(16))
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val h = b - t
        val iconSize = iconView.measuredWidth
        val iconLeft = pxi(16)
        val iconTop = (h - iconSize) / 2
        iconView.layout(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)
        val labelLeft = iconLeft + iconSize + pxi(12)
        val labelTop = (h - label.measuredHeight) / 2
        label.layout(labelLeft, labelTop, labelLeft + label.measuredWidth, labelTop + label.measuredHeight)
    }

    override fun getAccessibilityClassName(): CharSequence = Button::class.java.name
}

// ================================================================================== cards, tiles, chips

enum class CardStyle { FILLED, ELEVATED, OUTLINED }

/**
 * M3 card: vertical LinearLayout with 28 dp corners (children are clipped to them).
 * FILLED = surfaceContainer (default for content cards on the background), ELEVATED =
 * surfaceContainerLow + level-1 shadow, OUTLINED = surface + 1 dp outlineVariant. [onClick] adds a
 * ripple and makes the whole card one touch target.
 */
open class Card(context: Context, val style: CardStyle = CardStyle.FILLED, paddingDp: Int = 20) : LinearLayout(context) {
    val shape = RoundedShapeDrawable()

    init {
        orientation = VERTICAL
        val pad = pxi(paddingDp)
        setPadding(pad, pad, pad, pad)
        shape.setRadius(px(Shapes.CARD))
        when (style) {
            CardStyle.FILLED -> shape.color = Theme.surfaceContainer
            CardStyle.ELEVATED -> { shape.color = Theme.surfaceContainerLow; elevation = px(Elevation.LEVEL1) }
            CardStyle.OUTLINED -> { shape.color = Theme.surface; shape.strokeColor = Theme.outlineVariant; shape.strokeWidth = px(1f) }
        }
        background = shape
        outlineProvider = ViewOutlineProvider.BACKGROUND
        clipToOutline = true
    }

    fun setContainerColor(color: Int) { shape.color = color }

    fun setCornerRadius(dp: Float) { shape.setRadius(px(dp)); invalidateOutline() }

    private var press: PressScale? = null

    /** Makes the card clickable (ripple over everything, 0.97 press scale). */
    fun onClick(action: () -> Unit): Card {
        isClickable = true
        isFocusable = true
        foreground = rippleOver(null, RoundedShapeDrawable(Color.WHITE, px(Shapes.CARD)), Theme.onSurface)
        setOnSingleClick(action)
        if (press == null) press = PressScale(this)
        return this
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        press?.update()
    }
}

fun Context.filledCard(paddingDp: Int = 20): Card = Card(this, CardStyle.FILLED, paddingDp)
fun Context.elevatedCard(paddingDp: Int = 20): Card = Card(this, CardStyle.ELEVATED, paddingDp)
fun Context.outlinedCard(paddingDp: Int = 20): Card = Card(this, CardStyle.OUTLINED, paddingDp)

enum class ChipKind { ASSIST, FILTER, STATUS }

/**
 * M3 chip, 32 dp tall, 8 dp corners, Label L text, optional 20 dp leading icon.
 * ASSIST = outlined action chip; FILTER = toggles [checked] (secondaryContainer + check when on);
 * STATUS = non-interactive tonal state chip coloured by [tone] (ignition, lock, ride mode).
 * Interactive chips get a 48 dp touch target.
 */
class Chip(
    context: Context,
    text: CharSequence,
    icon: String? = null,
    val kind: ChipKind = ChipKind.ASSIST,
    private var onClick: (() -> Unit)? = null
) : LinearLayout(context) {
    private val shape = RoundedShapeDrawable()
    private val iconView = IconView(context, icon, iconSizeDp = Icons.SMALL)
    private val label = TextView(context)
    private var iconName = icon
    private var iconFilled = false
    private val interactive = kind != ChipKind.STATUS
    /** FILTER chips: called after a user toggle. */
    var onCheckedChange: ((Boolean) -> Unit)? = null

    var checked: Boolean = false
        set(value) { if (field != value) { field = value; restyle() } }

    var tone: Tone = Tone.NEUTRAL
        set(value) { if (field != value) { field = value; restyle() } }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val visual = pxi(32)
        val target = if (interactive) pxi(Spacing.TOUCH) else visual
        val inset = (target - visual) / 2
        minimumHeight = target
        shape.setInsets(0, inset, 0, inset)
        shape.setRadius(px(Shapes.CHIP))
        label.applyStyle(Typography.labelLarge)
        label.maxLines = 1
        label.ellipsize = TextUtils.TruncateAt.END
        label.text = text
        addView(iconView, LayoutParams(pxi(Icons.SMALL), pxi(Icons.SMALL)).apply { marginEnd = pxi(8) })
        addView(label, LayoutParams(WRAP, WRAP))
        if (interactive) {
            background = rippleOver(shape, RoundedShapeDrawable(Color.WHITE, px(Shapes.CHIP)).apply { setInsets(0, inset, 0, inset) }, Theme.onSurface)
            isClickable = true
            isFocusable = true
            setOnSingleClick {
                if (kind == ChipKind.FILTER) { checked = !checked; onCheckedChange?.invoke(checked) }
                this.onClick?.invoke()
            }
        } else {
            background = shape
        }
        restyle()
    }

    fun setOnClick(action: (() -> Unit)?) { onClick = action }

    fun setText(text: CharSequence) { label.show(text) }

    fun setIcon(name: String?, filled: Boolean = false) {
        if (name == iconName && filled == iconFilled) return
        iconName = name; iconFilled = filled
        restyle()
    }

    private fun restyle() {
        var container = Color.TRANSPARENT; var stroke = Theme.outlineVariant
        val text: Int; val iconTint: Int
        var shownIcon = iconName; var filled = iconFilled
        when (kind) {
            ChipKind.ASSIST -> { text = Theme.onSurface; iconTint = Theme.primary }
            ChipKind.FILTER -> if (checked) {
                container = Theme.secondaryContainer; stroke = Color.TRANSPARENT
                text = Theme.onSecondaryContainer; iconTint = Theme.onSecondaryContainer
                if (shownIcon == null) shownIcon = "check" else filled = true
            } else { text = Theme.onSurfaceVariant; iconTint = Theme.onSurfaceVariant }
            ChipKind.STATUS -> {
                container = tone.container; stroke = Color.TRANSPARENT
                text = if (tone == Tone.NEUTRAL) Theme.onSurface else tone.onContainer
                iconTint = if (tone == Tone.NEUTRAL) Theme.onSurfaceVariant else tone.onContainer
            }
        }
        shape.color = container
        shape.strokeColor = stroke
        shape.strokeWidth = if (stroke != Color.TRANSPARENT) px(1f) else 0f
        label.setTextColor(if (isEnabled) text else Theme.disabledContent)
        iconView.setIcon(shownIcon, filled)
        iconView.tint = if (isEnabled) iconTint else Theme.disabledContent
        iconView.visible(shownIcon != null)
        setPaddingRelative(pxi(if (shownIcon != null) 8 else 16), 0, pxi(16), 0)
        isSelected = kind == ChipKind.FILTER && checked
    }

    override fun getAccessibilityClassName(): CharSequence = when (kind) {
        ChipKind.FILTER -> android.widget.CheckBox::class.java.name
        ChipKind.ASSIST -> Button::class.java.name
        ChipKind.STATUS -> TextView::class.java.name
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        if (kind == ChipKind.FILTER) { info.markChecked(checked) }
    }
}

fun Context.assistChip(text: CharSequence, icon: String? = null, onClick: () -> Unit) = Chip(this, text, icon, ChipKind.ASSIST, onClick)
fun Context.filterChip(text: CharSequence, icon: String? = null, checked: Boolean = false, onChange: (Boolean) -> Unit) =
    Chip(this, text, icon, ChipKind.FILTER).apply { this.checked = checked; onCheckedChange = onChange }
fun Context.statusChip(text: CharSequence, icon: String? = null, tone: Tone = Tone.NEUTRAL) =
    Chip(this, text, icon, ChipKind.STATUS).apply { this.tone = tone }

/**
 * Compact stat chip: icon + tabular value + unit ("61 km", "12 rides") + optional tiny note ("est.").
 * 40 dp, surfaceContainerHigh, 8 dp corners like every chip. [description] prefixes the spoken value
 * ("Range 61 km"); long-press shows it as a tooltip.
 */
class StatChip(context: Context, icon: String, value: String = "—", unit: String? = null, private val description: String? = null) : LinearLayout(context) {
    private val shape = RoundedShapeDrawable(Theme.surfaceContainerHigh)
    private val iconView = IconView(context, icon, tint = Theme.primary, iconSizeDp = Icons.SMALL)
    private val valueView = context.singleLine(value, Typography.titleMedium, Theme.onSurface).tabular()
    private val unitView = context.singleLine(unit.orEmpty(), Typography.bodyMedium, Theme.onSurfaceVariant)
    private val noteView = context.singleLine("", Typography.labelSmall, Theme.onSurfaceVariant)
    private var value = value
    private var unit = unit
    private var spokenNote: String? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = pxi(40)
        shape.setRadius(px(Shapes.CHIP))
        background = shape
        setPaddingRelative(pxi(12), pxi(4), pxi(16), pxi(4))
        addView(iconView, LayoutParams(pxi(Icons.SMALL), pxi(Icons.SMALL)).apply { marginEnd = pxi(6) })
        val pair = LinearLayout(context).apply {
            orientation = HORIZONTAL
            isBaselineAligned = true
            addView(valueView, LayoutParams(WRAP, WRAP))
            addView(unitView, LayoutParams(WRAP, WRAP).apply { marginStart = pxi(3) })
            addView(noteView, LayoutParams(WRAP, WRAP).apply { marginStart = pxi(4) })
        }
        addView(pair, LayoutParams(WRAP, WRAP))
        noteView.visibility = GONE
        listOf(valueView, unitView, noteView).forEach { it.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        if (description != null) tooltipText = description
        setValue(value, unit)
    }

    fun setValue(value: String, unit: String? = null) {
        this.value = value; this.unit = unit
        valueView.roll(value)
        unitView.show(unit.orEmpty())
        unitView.visible(!unit.isNullOrEmpty())
        describe()
    }

    /** Small trailing note after the unit, e.g. "est."; [spoken] is what TalkBack says ("estimated"). */
    fun setNote(note: String?, spoken: String? = note) {
        noteView.show(note.orEmpty())
        noteView.visible(!note.isNullOrEmpty())
        spokenNote = spoken?.takeIf { !note.isNullOrEmpty() }
        describe()
    }

    private fun describe() {
        val spoken = listOfNotNull(description, value, unit?.takeIf { it.isNotEmpty() }).joinToString(" ") +
            (spokenNote?.let { ", $it" } ?: "")
        if (contentDescription != spoken) contentDescription = spoken
    }

    fun setIcon(name: String, filled: Boolean = false) = iconView.setIcon(name, filled)
    fun setTone(tone: Tone) { iconView.tint = tone.accent }
    fun setContainerColor(color: Int) { shape.color = color }
}

/**
 * Container-less spec strip (DESIGN A2: typography does the hierarchy, not boxes): equal columns,
 * each a 20 dp onSurfaceVariant icon, a Title L tabular value with a smaller onSurfaceVariant unit on
 * the same baseline, and a Label M label. Colour only carries meaning ([Item.setTone] for warnings).
 * [loading] shows pulsing skeleton blocks instead of values. The children carry no tooltips, so with
 * [onClick] the whole strip is one touch target (ripple + 0.97 press).
 */
class SpecStrip(context: Context) : LinearLayout(context) {
    private val items = ArrayList<Item>()
    private var press: PressScale? = null
    private var pulse: ValueAnimator? = null

    /** Skeleton placeholders while the first data loads (DESIGN A4). */
    var loading: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            items.forEach { it.showSkeleton(value) }
            updatePulse()
            describe()
        }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.TOP
        setPadding(0, pxi(12), 0, pxi(12))
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun add(icon: String, label: String, description: String = label): Item =
        Item(icon, label, description).also { item ->
            items += item
            addView(item.view, LayoutParams(0, WRAP, 1f))
            describe()
        }

    fun onClick(action: () -> Unit): SpecStrip {
        isClickable = true; isFocusable = true
        foreground = rippleOver(null, RoundedShapeDrawable(Color.WHITE, px(Shapes.TILE)), Theme.onSurface)
        setOnSingleClick(action)
        if (press == null) press = PressScale(this)
        return this
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        press?.update()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); updatePulse() }
    override fun onDetachedFromWindow() { pulse?.cancel(); pulse = null; super.onDetachedFromWindow() }
    override fun onVisibilityAggregated(isVisible: Boolean) { super.onVisibilityAggregated(isVisible); post { updatePulse() } }

    private fun updatePulse() {
        val want = loading && isAttachedToWindow && isShown && Motion.enabled()
        if (want == (pulse != null)) { if (!want) items.forEach { it.skeletonAlpha(1f) }; return }
        if (want) {
            pulse = ValueAnimator.ofFloat(1f, 0.55f).apply {
                duration = 700L
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener { a -> val f = a.animatedValue as Float; items.forEach { it.skeletonAlpha(f) } }
                start()
            }
        } else {
            pulse?.cancel(); pulse = null
            items.forEach { it.skeletonAlpha(1f) }
        }
    }

    internal fun describe() {
        val spoken = if (loading) "Loading battery details" else items.joinToString(", ") { it.spoken }
        if (contentDescription != spoken) contentDescription = spoken
    }

    inner class Item internal constructor(icon: String, label: String, private val description: String) {
        private val iconView = IconView(context, icon, tint = Theme.onSurfaceVariant, iconSizeDp = Icons.SMALL)
        private val valueView = context.singleLine("—", Typography.titleLarge, Theme.onSurface).tabular()
        private val unitView = context.singleLine("", Typography.bodyMedium, Theme.onSurfaceVariant)
        private val labelView = context.singleLine(label, Typography.labelMedium, Theme.onSurfaceVariant)
        private val pair = LinearLayout(context).apply {
            orientation = HORIZONTAL
            isBaselineAligned = true
            gravity = Gravity.CENTER_HORIZONTAL
            addView(valueView, LayoutParams(WRAP, WRAP))
            addView(unitView, LayoutParams(WRAP, WRAP).apply { marginStart = pxi(2) })
        }
        private val skeleton = View(context).apply {
            background = RoundedShapeDrawable(Theme.surfaceContainerHigh, px(6f))
            visibility = GONE
        }
        private var value = "—"
        private var unit: String? = null
        private var tone = Tone.NEUTRAL
        var spoken: String = "$description unknown"; private set

        var stale: Boolean = false
            set(next) { if (field != next) { field = next; paint(); update() } }

        val view: LinearLayout = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(iconView, LayoutParams(pxi(Icons.SMALL), pxi(Icons.SMALL)))
            val slot = FrameLayout(context).apply {
                addView(pair, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER_HORIZONTAL))
                addView(skeleton, FrameLayout.LayoutParams(pxi(44), pxi(20), Gravity.CENTER))
                minimumHeight = pxi(28)
            }
            addView(slot, LayoutParams(MATCH, WRAP).apply { topMargin = pxi(6) })
            addView(labelView, LayoutParams(WRAP, WRAP).apply { topMargin = pxi(2) })
            for (child in listOf(iconView, valueView, unitView, labelView, pair, skeleton)) child.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        init { unitView.visibility = GONE }

        fun setValue(value: String, unit: String? = null) {
            this.value = value; this.unit = unit
            valueView.roll(value)
            unitView.show(unit.orEmpty())
            unitView.visible(!unit.isNullOrEmpty())
            update()
        }

        /** NEUTRAL = onSurfaceVariant icon; WARNING/ERROR tint icon and value (meaningful colour only). */
        fun setTone(next: Tone) { if (tone != next) { tone = next; paint() } }

        internal fun showSkeleton(show: Boolean) {
            skeleton.visible(show)
            pair.visibility = if (show) INVISIBLE else VISIBLE
        }

        internal fun skeletonAlpha(a: Float) { skeleton.alpha = a }

        private fun paint() {
            val alert = tone == Tone.WARNING || tone == Tone.ERROR
            iconView.tint = if (alert) tone.accent else Theme.onSurfaceVariant
            valueView.tint(when { alert -> tone.accent; stale -> Theme.onSurfaceVariant; else -> Theme.onSurface })
        }

        private fun update() {
            spoken = if (value == Amounts.NONE) "$description unknown"
            else "$description $value${unit.orEmpty()}" + if (stale) ", last known" else ""
            describe()
        }
    }
}

/**
 * Container-less summary grid (Home): [columns] columns of left-aligned cells, each a small icon +
 * label line (Label L, onSurfaceVariant) over a big tabular value (Headline M) with its unit on the
 * same baseline (Title M, onSurfaceVariant) and an optional note ("est."). No boxes (A2):
 * typography carries the hierarchy, colour only meaning ([Cell.setTone]). Each cell is its own touch
 * target ([Cell.onClick]: 16 dp-corner ripple, 0.97 press, double taps dropped) with 12 dp inner
 * padding — add the grid with [alignedParams] so the text lines up with the page margin.
 * [Cell.loading] shows a pulsing skeleton instead of the value.
 */
class SpecGrid(context: Context, val columns: Int = 2) : LinearLayout(context) {
    private val cells = ArrayList<Cell>()
    private val rows = ArrayList<LinearLayout>()
    private var pulse: ValueAnimator? = null

    init { orientation = VERTICAL }

    /** Layout params for a [ScrollPage] column: −12 dp sides so cell text aligns with the 16 dp margin. */
    fun alignedParams(topDp: Int = Spacing.GAP): LinearLayout.LayoutParams =
        LayoutParams(MATCH, WRAP).apply { topMargin = pxi(topDp); marginStart = -pxi(CELL_PAD); marginEnd = -pxi(CELL_PAD) }

    fun add(icon: String, label: String, description: String = label): Cell {
        val cell = Cell(icon, label, description)
        val rowIndex = cells.size / columns
        val row = rows.getOrNull(rowIndex) ?: LinearLayout(context).apply {
            orientation = HORIZONTAL
            isBaselineAligned = false
            rows += this
            this@SpecGrid.addView(this, LayoutParams(MATCH, WRAP))
            repeat(columns) { addView(View(context).apply { visibility = INVISIBLE }, LayoutParams(0, pxi(1), 1f)) }
        }
        // Replace the placeholder in this column (keeps columns equal when a row is not full).
        val column = cells.size % columns
        row.removeViewAt(column)
        row.addView(cell, column, LayoutParams(0, MATCH, 1f))
        cells += cell
        return cell
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); updatePulse() }
    override fun onDetachedFromWindow() { pulse?.cancel(); pulse = null; super.onDetachedFromWindow() }
    override fun onVisibilityAggregated(isVisible: Boolean) { super.onVisibilityAggregated(isVisible); post { updatePulse() } }

    private fun updatePulse() {
        val want = cells.any { it.loading } && isAttachedToWindow && isShown && Motion.enabled()
        if (want == (pulse != null)) return
        if (want) {
            pulse = ValueAnimator.ofFloat(1f, 0.55f).apply {
                duration = 700L
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener { a -> val f = a.animatedValue as Float; cells.forEach { it.skeletonAlpha(f) } }
                start()
            }
        } else {
            pulse?.cancel(); pulse = null
            cells.forEach { it.skeletonAlpha(1f) }
        }
    }

    private companion object { const val CELL_PAD = 12 }

    /** One metric: icon + label, value + unit + note. Spoken as one item ("Battery health 99 %"). */
    inner class Cell internal constructor(icon: String, label: String, private val description: String) : LinearLayout(context) {
        private val iconView = IconView(context, icon, tint = Theme.onSurfaceVariant, iconSizeDp = Icons.CHIP)
        private val labelView = context.singleLine(label, Typography.labelLarge, Theme.onSurfaceVariant)
        private val valueView = context.singleLine(Amounts.NONE, Typography.headlineMedium, Theme.onSurface).tabular()
        private val unitView = context.singleLine("", Typography.titleMedium, Theme.onSurfaceVariant)
        private val noteView = context.singleLine("", Typography.labelMedium, Theme.onSurfaceVariant)
        private val pair = LinearLayout(context).apply {
            orientation = HORIZONTAL
            isBaselineAligned = true
            addView(valueView, LayoutParams(WRAP, WRAP))
            addView(unitView, LayoutParams(WRAP, WRAP).apply { marginStart = pxi(4) })
            addView(noteView, LayoutParams(WRAP, WRAP).apply { marginStart = pxi(6) })
        }
        private val skeleton = View(context).apply {
            background = RoundedShapeDrawable(Theme.surfaceContainerHigh, px(8f))
            visibility = GONE
        }
        private var press: PressScale? = null
        private var value = Amounts.NONE
        private var unit: String? = null
        private var note: String? = null
        private var spokenNote: String? = null
        private var tone = Tone.NEUTRAL

        /** Last-known value: drawn in onSurfaceVariant, spoken with "last known". */
        var stale: Boolean = false
            set(next) { if (field != next) { field = next; paint(); describe() } }

        /** Skeleton block instead of the value until the first data arrives. */
        var loading: Boolean = false
            set(next) {
                if (field == next) return
                field = next
                skeleton.visible(next)
                pair.visibility = if (next) INVISIBLE else VISIBLE
                describe()
                updatePulse()
            }

        init {
            orientation = VERTICAL
            val pad = pxi(CELL_PAD)
            setPadding(pad, pad, pad, pad)
            minimumHeight = pxi(Spacing.TOUCH + 24)
            val head = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(iconView, LayoutParams(pxi(Icons.CHIP), pxi(Icons.CHIP)))
                addView(labelView, LayoutParams(0, WRAP, 1f).apply { marginStart = pxi(8) })
            }
            addView(head, LayoutParams(MATCH, WRAP))
            val slot = FrameLayout(context).apply {
                addView(pair, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.START or Gravity.BOTTOM))
                addView(skeleton, FrameLayout.LayoutParams(pxi(72), pxi(24), Gravity.START or Gravity.CENTER_VERTICAL))
            }
            addView(slot, LayoutParams(MATCH, WRAP).apply { topMargin = pxi(4) })
            noteView.visibility = GONE
            unitView.visibility = GONE
            for (child in listOf(iconView, labelView, valueView, unitView, noteView, pair, skeleton, head, slot)) {
                child.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            describe()
        }

        fun setLabel(text: String) = labelView.show(text)
        fun setIcon(name: String, filled: Boolean = false) = iconView.setIcon(name, filled)

        fun setValue(value: String, unit: String? = null) {
            this.value = value; this.unit = unit
            valueView.roll(value)
            unitView.show(unit.orEmpty())
            unitView.visible(!unit.isNullOrEmpty())
            describe()
        }

        /** Small note after the unit (e.g. "est."); [spoken] is what TalkBack says ("estimated"). */
        fun setNote(note: String?, spoken: String? = note) {
            this.note = note
            noteView.show(note.orEmpty())
            noteView.visible(!note.isNullOrEmpty())
            spokenNote = spoken?.takeIf { !note.isNullOrEmpty() }
            describe()
        }

        /** NEUTRAL by default; WARNING/ERROR tint the icon and value (colour only for meaning). */
        fun setTone(next: Tone) { if (tone != next) { tone = next; paint() } }

        /** Makes the cell a touch target (explain sheet). */
        fun onClick(action: () -> Unit): Cell {
            isClickable = true; isFocusable = true
            foreground = rippleOver(null, RoundedShapeDrawable(Color.WHITE, px(Shapes.LARGE)), Theme.onSurface)
            setOnSingleClick(action)
            if (press == null) press = PressScale(this)
            return this
        }

        override fun drawableStateChanged() {
            super.drawableStateChanged()
            press?.update()
        }

        override fun getAccessibilityClassName(): CharSequence =
            if (isClickable) Button::class.java.name else TextView::class.java.name

        internal fun skeletonAlpha(a: Float) { skeleton.alpha = a }

        private fun paint() {
            val alert = tone == Tone.WARNING || tone == Tone.ERROR
            iconView.tint = if (alert) tone.accent else Theme.onSurfaceVariant
            valueView.tint(when { alert -> tone.accent; stale -> Theme.onSurfaceVariant; else -> Theme.onSurface })
        }

        private fun describe() {
            val spoken = when {
                loading -> "$description, loading"
                value == Amounts.NONE -> "$description unknown"
                else -> "$description $value${unit?.let { " $it" }.orEmpty()}" +
                    (spokenNote?.let { ", $it" } ?: "") + if (stale) ", last known" else ""
            }
            if (contentDescription != spoken) contentDescription = spoken
        }
    }
}

/**
 * Dashboard metric tile (20 dp corners, surfaceContainer): icon, big tabular value + unit, one-word
 * label. [stale] dims the value for last-known data. Long-press tooltip = [description].
 */
class MetricTile(context: Context, icon: String, label: String, private val description: String = label) : LinearLayout(context) {
    private val shape = RoundedShapeDrawable(Theme.surfaceContainer, 0f)
    // Neutral by default (DESIGN A1: colour only carries meaning); setTone() for warnings.
    private val iconView = IconView(context, icon, tint = Theme.onSurfaceVariant)
    private var press: PressScale? = null
    private val valueView = context.singleLine("—", Typography.headlineMedium.withWeight(500), Theme.onSurface).tabular()
    private val unitView = context.singleLine("", Typography.titleMedium, Theme.onSurfaceVariant)
    private val labelView = context.singleLine(label, Typography.labelLarge, Theme.onSurfaceVariant)
    private var value = "—"
    private var unit: String? = null

    var stale: Boolean = false
        set(next) { if (field != next) { field = next; valueView.setTextColor(if (next) Theme.onSurfaceVariant else Theme.onSurface); describe() } }

    init {
        orientation = VERTICAL
        shape.setRadius(px(Shapes.TILE))
        background = shape
        val pad = pxi(16)
        setPadding(pad, pad, pad, pad)
        addView(iconView, LayoutParams(pxi(24), pxi(24)))
        val valueRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            isBaselineAligned = true
            addView(valueView, LayoutParams(WRAP, WRAP))
            addView(unitView, LayoutParams(WRAP, WRAP).apply { marginStart = pxi(2) })
        }
        addView(valueRow, LayoutParams(MATCH, WRAP).apply { topMargin = pxi(12) })
        addView(labelView, LayoutParams(MATCH, WRAP).apply { topMargin = pxi(2) })
        for (child in listOf(valueView, unitView, labelView)) child.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        tooltipText = description
        describe()
    }

    fun setValue(value: String, unit: String? = null) {
        this.value = value; this.unit = unit
        valueView.roll(value)
        unitView.show(unit.orEmpty())
        unitView.visible(!unit.isNullOrEmpty())
        describe()
    }

    fun setIcon(name: String, filled: Boolean = false) = iconView.setIcon(name, filled)
    fun setTone(tone: Tone) { iconView.tint = tone.accent }
    fun setIconTint(color: Int) { iconView.tint = color }
    fun setContainerColor(color: Int) { shape.color = color }

    fun onClick(action: () -> Unit): MetricTile {
        isClickable = true; isFocusable = true
        foreground = rippleOver(null, RoundedShapeDrawable(Color.WHITE, px(Shapes.TILE)), Theme.onSurface)
        setOnSingleClick(action)
        if (press == null) press = PressScale(this)
        return this
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        press?.update()
    }

    private fun describe() {
        val spoken = "$description ${value}${unit.orEmpty()}" + if (stale) ", last known" else ""
        if (contentDescription != spoken) contentDescription = spoken
    }
}

/**
 * Lays children in [columns] equal columns with [gapDp] gaps; every row is as tall as its tallest
 * child (tiles line up). GONE children are skipped.
 */
class EqualGrid(context: Context, val columns: Int = 2, gapDp: Int = Spacing.GAP) : ViewGroup(context) {
    private val gap = pxi(gapDp)
    private var rowHeights = IntArray(0)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val inner = max(0, width - paddingLeft - paddingRight)
        val cell = max(0, (inner - gap * (columns - 1)) / columns)
        val visible = (0 until childCount).map { getChildAt(it) }.filter { it.visibility != GONE }
        val rows = (visible.size + columns - 1) / columns
        if (rowHeights.size < rows) rowHeights = IntArray(rows)
        rowHeights.fill(0)
        val cellSpec = MeasureSpec.makeMeasureSpec(cell, MeasureSpec.EXACTLY)
        visible.forEachIndexed { i, child ->
            child.measure(cellSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            rowHeights[i / columns] = max(rowHeights[i / columns], child.measuredHeight)
        }
        visible.forEachIndexed { i, child ->
            if (child.measuredHeight != rowHeights[i / columns])
                child.measure(cellSpec, MeasureSpec.makeMeasureSpec(rowHeights[i / columns], MeasureSpec.EXACTLY))
        }
        var height = paddingTop + paddingBottom
        for (r in 0 until rows) height += rowHeights[r] + if (r > 0) gap else 0
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), resolveSize(height, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val inner = r - l - paddingLeft - paddingRight
        val cell = max(0, (inner - gap * (columns - 1)) / columns)
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        var index = 0
        var y = paddingTop
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val column = index % columns
            val row = index / columns
            if (column == 0 && row > 0) y += rowHeights[row - 1] + gap
            val x = if (rtl) r - l - paddingRight - (column + 1) * cell - column * gap else paddingLeft + column * (cell + gap)
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
            index++
        }
    }
}

// ================================================================================== lists

/** Rows that [ListGroup] shapes (colour + outer/inner corners). */
interface GroupedRow {
    fun setGroupShape(color: Int, topRadius: Float, bottomRadius: Float)
}

/**
 * M3 list item: optional 40 dp tonal leading icon circle, headline (Body L) + supporting text (Body
 * M), trailing slot (switch, chevron, text, button). 56 dp one-line / 72 dp two-line minimum.
 * [onClick] makes the whole row the touch target. Put rows in a [ListGroup] for the grouped,
 * rounded-container look.
 */
open class ListItem(
    context: Context,
    headline: CharSequence,
    supporting: CharSequence? = null,
    icon: String? = null,
    trailing: View? = null,
    private var onClick: (() -> Unit)? = null
) : LinearLayout(context), GroupedRow {
    private val container = RoundedShapeDrawable()
    private val mask = RoundedShapeDrawable(Color.WHITE)
    private val leadingShape = RoundedShapeDrawable(Tone.SECONDARY.container)
    private val leading = FrameLayout(context)
    private val leadingIcon = IconView(context, icon, tint = Tone.SECONDARY.onContainer)
    private val headlineView = context.textView(headline, Typography.bodyLarge, Theme.onSurface).apply {
        maxLines = 2; ellipsize = TextUtils.TruncateAt.END
    }
    private val supportingView = context.textView(supporting ?: "", Typography.bodyMedium, Theme.onSurfaceVariant).apply {
        maxLines = 3; ellipsize = TextUtils.TruncateAt.END
    }
    private val trailingSlot = FrameLayout(context)
    private var trailingText: TextView? = null
    private var press: PressScale? = null // Declared before init, which calls setOnClick.

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = rippleOver(container, mask, Theme.onSurface)
        leadingShape.setRadius(px(Shapes.FULL))
        leading.background = leadingShape
        leading.addView(leadingIcon, FrameLayout.LayoutParams(pxi(24), pxi(24), Gravity.CENTER))
        leading.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(leading, LayoutParams(pxi(40), pxi(40)).apply { marginEnd = pxi(16) })
        val texts = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(headlineView, LayoutParams(MATCH, WRAP))
            addView(supportingView, LayoutParams(MATCH, WRAP))
        }
        addView(texts, LayoutParams(0, WRAP, 1f))
        addView(trailingSlot, LayoutParams(WRAP, WRAP).apply { marginStart = pxi(16) })
        setPaddingRelative(pxi(16), pxi(12), pxi(16), pxi(12))
        setIcon(icon)
        setSupporting(supporting)
        setTrailing(trailing)
        setOnClick(onClick)
    }

    fun setHeadline(text: CharSequence) = headlineView.show(text)

    fun setSupporting(text: CharSequence?, color: Int = Theme.onSurfaceVariant) {
        supportingView.visible(!text.isNullOrEmpty())
        if (!text.isNullOrEmpty()) { supportingView.show(text); supportingView.tint(color) }
        minimumHeight = pxi(if (text.isNullOrEmpty()) 56 else 72)
    }

    /** Leading icon in a 40 dp tonal circle (null hides the circle). */
    fun setIcon(name: String?, filled: Boolean = false) {
        leadingIcon.setIcon(name, filled)
        leading.visible(name != null)
    }

    /** Leading circle colours (e.g. Tone.WARNING for a missing permission). */
    fun setLeadingTone(tone: Tone) {
        leadingShape.color = tone.container
        leadingIcon.tint = tone.onContainer
    }

    fun setTrailing(view: View?) {
        trailingSlot.removeAllViews()
        trailingText = null
        if (view != null) trailingSlot.addView(view, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        trailingSlot.visible(view != null)
    }

    /** Trailing supporting text (Label S, e.g. "On", "2.1.0"). */
    fun setTrailingText(text: CharSequence?, color: Int = Theme.onSurfaceVariant) {
        if (text == null) { setTrailing(null); return }
        val view = trailingText ?: context.textView("", Typography.labelLarge, color).also { setTrailing(it); trailingText = it }
        view.show(text); view.tint(color)
    }

    fun setOnClick(action: (() -> Unit)?) {
        onClick = action
        isClickable = action != null
        isFocusable = action != null
        if (action != null) setOnSingleClick { onClick?.invoke() } else { setOnClickListener(null); isClickable = false }
        if (action != null && press == null) press = PressScale(this)
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        press?.update()
    }

    override fun setGroupShape(color: Int, topRadius: Float, bottomRadius: Float) {
        container.color = color
        container.setRadii(topRadius, topRadius, bottomRadius, bottomRadius)
        mask.setRadii(topRadius, topRadius, bottomRadius, bottomRadius)
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.38f
    }
}

/** List row with a trailing [MaterialSwitch]; the whole row toggles and is announced as a switch. */
class SwitchListItem(
    context: Context,
    headline: CharSequence,
    supporting: CharSequence? = null,
    icon: String? = null,
    checked: Boolean = false,
    private val onChange: (Boolean) -> Unit
) : ListItem(context, headline, supporting, icon) {
    val switch = MaterialSwitch(context).apply {
        setChecked(checked, animate = false)
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Current state; setting it does not call onChange. */
    var checked: Boolean
        get() = switch.isChecked
        set(value) { switch.setChecked(value, animate = isAttachedToWindow) }

    init {
        setTrailing(switch)
        setOnClick {
            switch.setChecked(!switch.isChecked, animate = true)
            onChange(switch.isChecked)
            sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED)
        }
    }

    override fun getAccessibilityClassName(): CharSequence = android.widget.Switch::class.java.name

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.markChecked(switch.isChecked)
    }
}

/**
 * M3 Expressive grouped list: rows sit in their own surfaceContainer containers 2 dp apart; the
 * first/last rows get 20 dp outer corners, inner corners are 4 dp. Re-shapes itself when rows are
 * shown/hidden. Children that are not [GroupedRow] get a shaped background.
 */
class ListGroup(context: Context, private val color: Int = Theme.surfaceContainer) : LinearLayout(context) {
    init {
        orientation = VERTICAL
        dividerDrawable = SpacerDrawable(pxi(2))
        showDividers = SHOW_DIVIDER_MIDDLE
    }

    override fun onViewAdded(child: View) {
        super.onViewAdded(child)
        if (child !is GroupedRow && child.background !is RoundedShapeDrawable) child.background = RoundedShapeDrawable(color)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        shapeRows()
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun shapeRows() {
        var first = -1; var last = -1
        for (i in 0 until childCount) if (getChildAt(i).visibility != GONE) { if (first < 0) first = i; last = i }
        val outer = px(Shapes.LIST_OUTER); val inner = px(Shapes.LIST_INNER)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val top = if (i == first) outer else inner
            val bottom = if (i == last) outer else inner
            if (child is GroupedRow) child.setGroupShape(color, top, bottom)
            else (child.background as? RoundedShapeDrawable)?.setRadii(top, top, bottom, bottom)
        }
    }

    /** Fixed-height transparent divider (LinearLayout skips it next to GONE rows). */
    private class SpacerDrawable(private val size: Int) : Drawable() {
        override fun draw(canvas: Canvas) {}
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSPARENT
        override fun getIntrinsicHeight(): Int = size
        override fun getIntrinsicWidth(): Int = size
    }
}

/**
 * M3 switch: 52×32 dp track, thumb with a check / close icon inside (24 dp, 28 dp while pressed),
 * spring-animated position and colours. 48 dp tall touch target. Implements [Checkable]; user taps
 * call [onCheckedChange]; [setChecked] from code does not.
 */
class MaterialSwitch(context: Context) : View(context), android.widget.Checkable {
    private var on = false
    private var position = 0f
    private var pressFraction = 0f
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; strokeWidth = 2.4f
    }
    private val check = Path().apply { moveTo(5f, 12.5f); lineTo(9.5f, 17f); lineTo(19f, 7.5f) }
    private val close = Path().apply { moveTo(7f, 7f); lineTo(17f, 17f); moveTo(17f, 7f); lineTo(7f, 17f) }
    private val rect = RectF()
    private var positionSpring: Spring? = null
    private var pressSpring: Spring? = null
    var onCheckedChange: ((Boolean) -> Unit)? = null

    init {
        isClickable = true
        isFocusable = true
        positionSpring = Spring(0f, threshold = 0.002f) { position = it; invalidate() }
        pressSpring = Spring(0f, Motion.STIFFNESS_FAST, Motion.DAMPING_FAST, 0.002f) { pressFraction = it; invalidate() }
    }

    override fun isChecked(): Boolean = on
    override fun setChecked(checked: Boolean) = setChecked(checked, animate = isAttachedToWindow)
    override fun toggle() = setChecked(!on, animate = true)

    fun setChecked(checked: Boolean, animate: Boolean) {
        if (checked == on) return
        on = checked
        val target = if (checked) 1f else 0f
        if (animate) positionSpring?.animateTo(target) else positionSpring?.snapTo(target)
        refreshDrawableState()
    }

    override fun performClick(): Boolean {
        toggle()
        onCheckedChange?.invoke(on)
        return super.performClick()
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        pressSpring?.animateTo(if (isPressed) 1f else 0f)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(pxi(52) + paddingLeft + paddingRight, widthMeasureSpec), resolveSize(pxi(Spacing.TOUCH), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val p = position.coerceIn(0f, 1f)
        val trackW = px(52f); val trackH = px(32f)
        val left = (width - trackW) / 2f; val top = (height - trackH) / 2f
        rect.set(left, top, left + trackW, top + trackH)
        track.color = blend(Theme.surfaceContainerHighest, Theme.primary, p)
        canvas.drawRoundRect(rect, trackH / 2f, trackH / 2f, track)
        if (p < 1f) {
            val sw = px(2f)
            trackStroke.strokeWidth = sw
            trackStroke.color = alpha(Theme.outline, 1f - p)
            rect.inset(sw / 2f, sw / 2f)
            canvas.drawRoundRect(rect, (trackH - sw) / 2f, (trackH - sw) / 2f, trackStroke)
        }
        val radius = lerp(px(12f), px(14f), pressFraction)
        val cx = lerp(left + trackH / 2f, left + trackW - trackH / 2f, position)
        val cy = height / 2f
        thumb.color = if (pressFraction > 0.5f) blend(Theme.onSurfaceVariant, Theme.primaryContainer, p) else blend(Theme.outline, Theme.onPrimary, p)
        canvas.drawCircle(cx, cy, radius, thumb)
        val iconSize = px(16f)
        canvas.save()
        canvas.translate(cx - iconSize / 2f, cy - iconSize / 2f)
        canvas.scale(iconSize / 24f, iconSize / 24f)
        if (p < 1f) { glyph.color = alpha(Theme.surfaceContainerHighest, 1f - p); canvas.drawPath(close, glyph) }
        if (p > 0f) { glyph.color = alpha(Theme.onPrimaryContainer, p); canvas.drawPath(check, glyph) }
        canvas.restore()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.38f
    }

    override fun getAccessibilityClassName(): CharSequence = android.widget.Switch::class.java.name

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.markChecked(on)
    }
}

// ================================================================================== navigation bar, top app bar, scaffold

/** One bottom-navigation destination: [icon] is a Material Symbols name (filled variant when selected). */
class NavItem(val label: String, val icon: String, val description: String = label)

/**
 * M3 navigation bar: 80 dp, surfaceContainer, 64×32 dp pill indicator (secondaryContainer) that
 * springs open on the selected item, filled icon when selected, label under the icon, optional
 * badge (dot or count). Call [applyBottomInset] with the navigation-bar inset (edge-to-edge).
 *
 * Hit areas match the drawn items: each item's touch target is exactly its column (indicator +
 * label, full 80 dp height); on wide windows columns are capped at [MAX_ITEM_DP] and the group is
 * centred, so empty bar space never selects a tab. A tap on the selected item calls [onReselect],
 * except within [ClickGuard.WINDOW_MS] of selecting it (the second half of a double tap).
 */
class NavigationBar(
    context: Context,
    items: List<NavItem>,
    selected: Int = 0,
    private val onSelect: (Int) -> Unit
) : LinearLayout(context) {
    companion object {
        /** Widest an item column gets (dp); wider bars centre the items. */
        const val MAX_ITEM_DP = 120
    }

    var selected: Int = selected.coerceIn(0, max(0, items.size - 1)); private set
    /** Tap on the already selected item (e.g. scroll to top / close a detail view). */
    var onReselect: ((Int) -> Unit)? = null
    private val entries = items.mapIndexed { index, item -> Entry(index, item) }
    private var selectedAt = 0L
    private var sidePad = 0

    init {
        orientation = HORIZONTAL
        setBackgroundColor(Theme.surfaceContainer)
        entries.forEach { addView(it, LayoutParams(0, WRAP, 1f)) }
        entries.forEach { it.mark(it.index == this.selected, animate = false) }
    }

    /** Selects [index] (idempotent: re-selecting the selected item changes nothing); [notify] also calls onSelect (user taps always notify). */
    fun select(index: Int, animate: Boolean = true, notify: Boolean = false) {
        if (index !in entries.indices) return
        val changed = index != selected
        if (!changed) return
        selected = index
        selectedAt = SystemClock.uptimeMillis()
        entries.forEach { it.mark(it.index == index, animate) }
        if (notify) onSelect(index)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val cap = pxi(MAX_ITEM_DP) * max(1, entries.size)
        val pad = if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED && width > cap) (width - cap) / 2 else 0
        if (pad != sidePad) { sidePad = pad; setPaddingRelative(pad, paddingTop, pad, paddingBottom) }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun tapped(index: Int) {
        if (index == selected) {
            if (SystemClock.uptimeMillis() - selectedAt >= ClickGuard.WINDOW_MS) onReselect?.invoke(index)
        } else {
            select(index, animate = true, notify = true)
        }
    }

    /** null = no badge, 0 = small dot, n = count (99+). */
    fun setBadge(index: Int, count: Int?) { entries.getOrNull(index)?.setBadge(count) }

    /** Bottom system-bar inset in px (the bar's background extends under the gesture area). */
    fun applyBottomInset(px: Int) { if (paddingBottom != px) setPaddingRelative(paddingStart, 0, paddingEnd, px) }

    private inner class Entry(val index: Int, val item: NavItem) : LinearLayout(context) {
        private val indicator = Indicator(item)
        private val label = context.singleLine(item.label, Typography.labelMedium, Theme.onSurfaceVariant).apply {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            gravity = Gravity.CENTER
            // M3 nav labels never truncate: shrink to 10 sp at large font scales on narrow phones.
            setAutoSizeTextTypeUniformWithConfiguration(10, 12, 1, TypedValue.COMPLEX_UNIT_SP)
        }
        private val spring = Spring(0f, threshold = 0.002f) { f -> indicator.fraction = f; indicator.invalidate() }
        private var on = false
        private var badge: Int? = null

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            minimumHeight = pxi(80)
            setPadding(0, pxi(12), 0, pxi(16))
            addView(indicator, LayoutParams(pxi(64), pxi(32)))
            addView(label, LayoutParams(MATCH, WRAP).apply { topMargin = pxi(4) })
            isClickable = true
            isFocusable = true
            setOnClickListener { tapped(index) }
            describe()
        }

        fun mark(selected: Boolean, animate: Boolean) {
            val changed = on != selected
            on = selected
            isSelected = selected
            indicator.on = selected
            if (animate && changed) spring.animateTo(if (selected) 1f else 0f) else spring.snapTo(if (selected) 1f else 0f)
            label.setTextColor(if (selected) Theme.onSurface else Theme.onSurfaceVariant)
            label.typeface = if (selected) Typography.labelMedium.emphasized.typeface else Typography.labelMedium.typeface
            indicator.invalidate()
        }

        fun setBadge(count: Int?) {
            if (badge == count) return
            badge = count
            indicator.setBadge(count)
            describe()
        }

        private fun describe() {
            val b = badge
            contentDescription = when {
                b == null -> item.description
                b == 0 -> "${item.description}, new"
                else -> "${item.description}, $b new"
            }
        }
    }

    private inner class Indicator(item: NavItem) : View(context) {
        var fraction = 0f
        var on = false
        private val outlined = Icons.drawable(context, item.icon, false, Theme.onSurfaceVariant)
        private val filled = Icons.drawable(context, item.icon, true, Theme.onSecondaryContainer)
        private val pill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val badgeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.error }
        private val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Theme.onError; textAlign = Paint.Align.CENTER
        }.applyStyle(context, Typography.labelSmall).tabular()
        private val rect = RectF()
        private val badgeRect = RectF()
        private var badge: Int? = null
        private var badgeLabel = ""
        private var badgeWidth = 0f

        init {
            isDuplicateParentStateEnabled = true
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            foreground = rippleOver(null, RoundedShapeDrawable(Color.WHITE, px(Shapes.FULL)), Theme.onSurface)
        }

        fun setBadge(count: Int?) {
            badge = count
            badgeLabel = when { count == null || count <= 0 -> ""; count > 99 -> "99+"; else -> count.toString() }
            badgeWidth = if (badgeLabel.isEmpty()) 0f else max(px(16f), badgeText.measureText(badgeLabel) + px(8f))
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            val size = pxi(24)
            val l = (w - size) / 2; val t = (h - size) / 2
            outlined.setBounds(l, t, l + size, t + size)
            filled.setBounds(l, t, l + size, t + size)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val f = fraction.coerceIn(0f, 1f)
            val cx = width / 2f
            if (f > 0f) {
                val w = width * (0.5f + 0.5f * f)
                rect.set(cx - w / 2f, 0f, cx + w / 2f, height.toFloat())
                pill.color = alpha(Theme.secondaryContainer, f)
                canvas.drawRoundRect(rect, height / 2f, height / 2f, pill)
            }
            (if (on) filled else outlined).draw(canvas)
            val count = badge ?: return
            val cy = height / 2f
            if (count <= 0) {
                canvas.drawCircle(cx + px(9f), cy - px(9f), px(3f), badgeFill)
            } else {
                val left = cx + px(4f)
                badgeRect.set(left, cy - px(14f), left + badgeWidth, cy + px(2f))
                canvas.drawRoundRect(badgeRect, px(8f), px(8f), badgeFill)
                val baseline = badgeRect.centerY() - (badgeText.descent() + badgeText.ascent()) / 2f
                canvas.drawText(badgeLabel, badgeRect.centerX(), baseline, badgeText)
            }
        }
    }
}

/**
 * M3 top app bar (64 dp) that pads itself for the status bar (edge-to-edge): optional navigation
 * icon, title (+ subtitle) and a [trailing] slot for actions or a connection chip.
 * [large] = flexible large bar: the title (Headline M) and subtitle live in [header], which goes at
 * the top of the scrolling content ([Scaffold] does this); the small title fades in once the header
 * scrolls away. [bindScroll] also tints the bar surfaceContainer while content is scrolled under it.
 */
class TopAppBar(context: Context, val large: Boolean = false) : LinearLayout(context) {
    private val bar = LinearLayout(context)
    private val navButton = IconActionButton(context, "arrow_back", "Back")
    private val smallTitle = context.singleLine("", Typography.titleLarge, Theme.onSurface)
    private val smallSubtitle = context.singleLine("", Typography.bodyMedium, Theme.onSurfaceVariant)
    /** Actions / chips at the end of the bar (add IconActionButtons or a ConnectionChip). */
    val trailing = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val bigTitle = context.textView("", Typography.headlineMedium, Theme.onSurface).apply {
        isAccessibilityHeading = true; maxLines = 2; ellipsize = TextUtils.TruncateAt.END
    }
    private val bigSubtitle = context.textView("", Typography.titleMedium, Theme.onSurfaceVariant).apply {
        maxLines = 2; ellipsize = TextUtils.TruncateAt.END
    }
    /** Expanded title block for [large] bars; add it first in the scrolling column. */
    val header: LinearLayout = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(0, pxi(8), 0, pxi(12))
        addView(bigTitle, LayoutParams(MATCH, WRAP))
        addView(bigSubtitle, LayoutParams(MATCH, WRAP).apply { topMargin = pxi(4) })
    }
    private var statusInset = 0
    private var tint = -1f
    private var collapsed = -1f
    /** F-DEMO: "Demo" in every top app bar while demo mode is on (before the bar's own actions). */
    private val demoChip = DemoChip(context)
    private val demoListener: () -> Unit = { syncDemo(animate = true) }

    var title: CharSequence = ""
        set(value) { field = value; smallTitle.show(value); bigTitle.show(value) }

    var subtitle: CharSequence? = null
        set(value) {
            field = value
            smallSubtitle.show(value ?: ""); bigSubtitle.show(value ?: "")
            smallSubtitle.visible(!large && !value.isNullOrEmpty())
            bigSubtitle.visible(!value.isNullOrEmpty())
        }

    init {
        orientation = VERTICAL
        bar.orientation = HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.minimumHeight = pxi(64)
        navButton.visibility = GONE
        val titles = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(smallTitle, LayoutParams(MATCH, WRAP))
            addView(smallSubtitle, LayoutParams(MATCH, WRAP))
        }
        smallTitle.isAccessibilityHeading = !large
        bar.addView(navButton, LayoutParams(WRAP, WRAP).apply { marginStart = pxi(4) })
        bar.addView(titles, LayoutParams(0, WRAP, 1f).apply { marginStart = pxi(16); marginEnd = pxi(8) })
        bar.addView(demoChip, LayoutParams(WRAP, WRAP).apply { marginEnd = pxi(4) })
        bar.addView(trailing, LayoutParams(WRAP, WRAP).apply { marginEnd = pxi(8) })
        demoChip.visibility = if (Demo.active) VISIBLE else GONE
        addView(bar, LayoutParams(MATCH, WRAP))
        smallSubtitle.visibility = GONE
        bigSubtitle.visibility = GONE
        setBackgroundColor(Theme.background)
        if (large) {
            smallTitle.alpha = 0f
            smallTitle.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        setOnApplyWindowInsetsListener { _, insets ->
            val top = insets.getInsets(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout()).top
            if (top != statusInset) { statusInset = top; setPadding(0, top, 0, 0) }
            insets
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestApplyInsets()
        Demo.addListener(demoListener)
        syncDemo(animate = false)
    }

    override fun onDetachedFromWindow() {
        Demo.removeListener(demoListener)
        super.onDetachedFromWindow()
    }

    private fun syncDemo(animate: Boolean) {
        val show = Demo.active
        if ((demoChip.visibility == VISIBLE) == show && demoChip.alpha == 1f) return
        if (animate && isShown && Motion.enabled()) Motion.fade(demoChip, show) else { demoChip.animate().cancel(); demoChip.alpha = 1f; demoChip.visible(show) }
    }

    /** Shows a leading navigation icon (back arrow by default); null hides it. */
    fun setNavigation(icon: String = "arrow_back", description: String = "Back", onClick: (() -> Unit)?) {
        navButton.visible(onClick != null)
        navButton.setIcon(icon, description)
        navButton.setOnClick(onClick)
        (smallTitle.parent as? View)?.let { titles ->
            (titles.layoutParams as LayoutParams).marginStart = pxi(if (onClick != null) 4 else 16)
            titles.requestLayout()
        }
    }

    /** Adds a trailing action icon button. */
    fun addAction(icon: String, description: String, onClick: () -> Unit): IconActionButton =
        IconActionButton(context, icon, description, onClick = onClick).also { trailing.addView(it) }

    /** Follows [scroller]: container tint once scrolled, small title fades in after the header. */
    fun bindScroll(scroller: View) {
        scroller.onScroll { y, _ -> onScrolled(y) }
        onScrolled(scroller.scrollY)
    }

    fun onScrolled(y: Int) {
        val t = (y / px(8f)).coerceIn(0f, 1f)
        if (t != tint) { tint = t; setBackgroundColor(blend(Theme.background, Theme.surfaceContainer, t)) }
        if (!large) return
        val span = max(1f, (header.bottom - pxi(16)).toFloat())
        val c = ((y - header.top) / span).coerceIn(0f, 1f)
        if (c == collapsed) return
        collapsed = c
        smallTitle.alpha = c
        val shown = c > 0.5f
        val mode = if (shown) IMPORTANT_FOR_ACCESSIBILITY_AUTO else IMPORTANT_FOR_ACCESSIBILITY_NO
        if (smallTitle.importantForAccessibility != mode) smallTitle.importantForAccessibility = mode
    }
}

/**
 * Demo mode's mark (F-DEMO), added by [TopAppBar] to every top app bar and shown only while
 * [Demo.active]: a filled tertiary chip "Demo" (32 dp, 8 dp corners, 48 dp touch target). Tertiary
 * because it is neither an action nor a fault: it says the values are simulated. Tap = the demo
 * sheet ([Demo.onChip]: switch scenario or exit demo).
 */
class DemoChip(context: Context) : LinearLayout(context) {
    private val label = context.singleLine("Demo", Typography.labelLarge, Theme.onTertiaryContainer)
    private var press: PressScale? = null // Declared before init (drawableStateChanged runs from the View constructor).

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val visual = pxi(32)
        val target = pxi(Spacing.TOUCH)
        val inset = (target - visual) / 2
        minimumHeight = target
        val shape = RoundedShapeDrawable(Theme.tertiaryContainer, px(Shapes.CHIP)).apply { setInsets(0, inset, 0, inset) }
        val mask = RoundedShapeDrawable(Color.WHITE, px(Shapes.CHIP)).apply { setInsets(0, inset, 0, inset) }
        background = rippleOver(shape, mask, Theme.onTertiaryContainer)
        addView(label, LayoutParams(WRAP, WRAP))
        setPaddingRelative(pxi(16), 0, pxi(16), 0) // Chip metrics without an icon (16 dp sides).
        label.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        contentDescription = "Demo mode. Change scenario or exit"
        tooltipText = "Demo mode: simulated scooter and history"
        isClickable = true
        isFocusable = true
        setOnSingleClick {
            performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            Demo.onChip?.invoke()
        }
        press = PressScale(this)
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        press?.update()
    }

    override fun getAccessibilityClassName(): CharSequence = Button::class.java.name
}

/**
 * Standard screen frame: optional [topBar] above a [page] ([ScrollPage]), an optional extended FAB
 * at the bottom end (collapses on scroll), large-title header wired into the page. Use it as a
 * Screen's view.
 */
class Scaffold(context: Context, val topBar: TopAppBar? = null, val page: ScrollPage = ScrollPage(context)) : FrameLayout(context) {
    private val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val basePadding = page.column.paddingBottom

    /** Primary action; null removes it. The page gets extra bottom space so the FAB never hides content. */
    var fab: ExtendedFab? = null
        set(value) {
            field?.let { removeView(it) }
            field = value
            if (value != null) {
                addView(value, LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END).apply {
                    marginEnd = pxi(Spacing.L); bottomMargin = pxi(Spacing.L)
                })
                value.bindScroll(page)
            }
            page.column.setPadding(page.column.paddingLeft, page.column.paddingTop, page.column.paddingRight,
                basePadding + if (value != null) pxi(88) else 0)
        }

    init {
        if (topBar != null) column.addView(topBar, LinearLayout.LayoutParams(MATCH, WRAP))
        column.addView(page, LinearLayout.LayoutParams(MATCH, 0, 1f))
        addView(column, LayoutParams(MATCH, MATCH))
        topBar?.let {
            if (it.large) page.column.addView(it.header, 0, LinearLayout.LayoutParams(MATCH, WRAP))
            it.bindScroll(page)
        }
    }
}

// ================================================================================== progress and loading

/** Shared frame clock for always-moving indicators: true while they should keep redrawing. */
private fun View.wantsFrames(): Boolean = isAttachedToWindow && isShown && Motion.enabled()

/**
 * M3 Expressive wavy linear progress: the active part is a travelling sine wave (4 dp stroke,
 * 3 dp amplitude, 40 dp wavelength) that flattens near 0 % and 100 %, a 4 dp gap, the flat track
 * (secondaryContainer) and a stop dot at the end. [indeterminate] runs a sweeping wave segment.
 * [setProgress] springs to the new value. Redraws only while visible.
 */
class WavyLinearProgress(context: Context) : View(context) {
    var color: Int = Theme.primary
        set(value) { field = value; activePaint.color = value; dotPaint.color = value; invalidate() }
    var trackColor: Int = Theme.secondaryContainer
        set(value) { field = value; trackPaint.color = value; invalidate() }
    var indeterminate: Boolean = false
        set(value) { if (field != value) { field = value; invalidate() } }
    /** false = flat M3 bar (no wave). */
    var wavy: Boolean = true
        set(value) { if (field != value) { field = value; requestLayout(); invalidate() } }
    var progress: Float = 0f; private set
    private var shown = 0f
    private val stroke = px(4f)
    private val amplitude = px(3f)
    private val wavelength = px(40f)
    private val gap = px(4f)
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; strokeWidth = stroke; color = Theme.primary }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = stroke; color = Theme.secondaryContainer }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.primary }
    private val path = Path()
    private val origin = SystemClock.uptimeMillis()
    private var spring: Spring? = null

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        spring = Spring(0f, threshold = 0.001f) { shown = it; invalidate() }
    }

    fun setProgress(value: Float, animate: Boolean = true) {
        progress = value.coerceIn(0f, 1f)
        if (animate && isAttachedToWindow) spring?.animateTo(progress) else spring?.snapTo(progress)
        stateDescription = "${(progress * 100).roundToInt()}%"
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = if (wavy) (stroke + 2 * amplitude + px(2f)).roundToInt() else stroke.roundToInt()
        setMeasuredDimension(resolveSize(pxi(240), widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val half = stroke / 2f
        val start = half + paddingLeft
        val end = width - half - paddingRight
        if (end <= start) return
        val cy = height / 2f
        val now = SystemClock.uptimeMillis() - origin
        val moving = wantsFrames()
        val phase = if (moving) (now % 1000L) / 1000f * 2f * PI.toFloat() else 0f
        if (indeterminate) {
            val t = if (moving) (now % 1800L) / 1800f else 0.3f
            val head = Motion.standard.getInterpolation((t / 0.75f).coerceIn(0f, 1f))
            val tail = Motion.standard.getInterpolation(((t - 0.25f) / 0.75f).coerceIn(0f, 1f))
            val a = lerp(start, end, tail); val b = lerp(start, end, head)
            if (a - gap - stroke > start) canvas.drawLine(start, cy, a - gap - stroke, cy, trackPaint)
            if (b + gap + stroke < end) canvas.drawLine(b + gap + stroke, cy, end, cy, trackPaint)
            if (b > a) wave(canvas, a, b, cy, if (wavy) amplitude else 0f, phase)
            if (moving) postInvalidateOnAnimation()
            return
        }
        val p = shown.coerceIn(0f, 1f)
        val activeEnd = lerp(start, end, p)
        val trackStart = if (p > 0f) activeEnd + gap + stroke else start
        if (trackStart < end) canvas.drawLine(trackStart, cy, end, cy, trackPaint)
        if (p < 1f) canvas.drawCircle(end, cy, px(2f), dotPaint)
        if (p > 0f) {
            val ramp = min(1f, p / 0.1f) * min(1f, (1f - p) / 0.1f)
            wave(canvas, start, activeEnd, cy, if (wavy) amplitude * ramp else 0f, phase)
        }
        if (moving && wavy && p > 0f && p < 1f) postInvalidateOnAnimation()
    }

    private fun wave(canvas: Canvas, from: Float, to: Float, cy: Float, amp: Float, phase: Float) {
        if (amp < 0.5f) { canvas.drawLine(from, cy, to, cy, activePaint); return }
        path.reset()
        val step = px(1.5f)
        var x = from
        val k = 2f * PI.toFloat() / wavelength
        path.moveTo(x, cy + amp * sin(x * k - phase))
        while (x < to) {
            x = min(to, x + step)
            path.lineTo(x, cy + amp * sin(x * k - phase))
        }
        canvas.drawPath(path, activePaint)
    }

    override fun getAccessibilityClassName(): CharSequence = android.widget.ProgressBar::class.java.name
}

/**
 * M3 Expressive wavy circular progress (48 dp default): wavy active arc, gapped flat track,
 * determinate (spring to [setProgress]) or [indeterminate] (rotating arc that grows and shrinks).
 */
class WavyCircularProgress(context: Context, private val sizeDp: Int = 48) : View(context) {
    var color: Int = Theme.primary
        set(value) { field = value; activePaint.color = value; invalidate() }
    var trackColor: Int = Theme.secondaryContainer
        set(value) { field = value; trackPaint.color = value; invalidate() }
    var indeterminate: Boolean = false
        set(value) { if (field != value) { field = value; invalidate() } }
    var wavy: Boolean = true
    var progress: Float = 0f; private set
    private var shown = 0f
    private val stroke = px(4f)
    private val amplitude = px(1.6f)
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; strokeWidth = stroke; color = Theme.primary }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = stroke; color = Theme.secondaryContainer }
    private val path = Path()
    private val oval = RectF()
    private val origin = SystemClock.uptimeMillis()
    private var spring: Spring? = null

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        spring = Spring(0f, threshold = 0.001f) { shown = it; invalidate() }
    }

    fun setProgress(value: Float, animate: Boolean = true) {
        progress = value.coerceIn(0f, 1f)
        if (animate && isAttachedToWindow) spring?.animateTo(progress) else spring?.snapTo(progress)
        stateDescription = "${(progress * 100).roundToInt()}%"
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = pxi(sizeDp)
        setMeasuredDimension(resolveSize(s, widthMeasureSpec), resolveSize(s, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f; val cy = height / 2f
        val radius = min(width, height) / 2f - stroke / 2f - amplitude - px(2f)
        if (radius <= 0f) return
        val now = SystemClock.uptimeMillis() - origin
        val moving = wantsFrames()
        val phase = if (moving) (now % 1200L) / 1200f * 2f * PI.toFloat() else 0f
        val gapDeg = Math.toDegrees(((px(4f) + stroke) / radius).toDouble()).toFloat()
        oval.set(cx - radius, cy - radius, cx + radius, cy + radius)
        val startDeg: Float; val sweep: Float
        if (indeterminate) {
            val t = if (moving) now / 1333f else 0f
            sweep = 30f + 240f * (0.5f - 0.5f * cos(2f * PI.toFloat() * (t % 1f)))
            startDeg = -90f + (now * 0.3f) % 360f
        } else {
            startDeg = -90f
            sweep = 360f * shown.coerceIn(0f, 1f)
        }
        val trackSweep = 360f - sweep - 2 * gapDeg
        if (trackSweep > 0f) canvas.drawArc(oval, startDeg + sweep + gapDeg, trackSweep, false, trackPaint)
        if (sweep <= 0.5f) { if (moving && indeterminate) postInvalidateOnAnimation(); return }
        val amp = if (wavy) amplitude * min(1f, sweep / 36f) else 0f
        if (amp < 0.3f) {
            canvas.drawArc(oval, startDeg, sweep, false, activePaint)
        } else {
            val waves = max(6, (2f * PI.toFloat() * radius / px(14f)).roundToInt())
            path.reset()
            val steps = max(8, (sweep / 3f).roundToInt())
            for (i in 0..steps) {
                val deg = startDeg + sweep * i / steps
                val rad = Math.toRadians(deg.toDouble()).toFloat()
                val r = radius + amp * sin(waves * rad - phase)
                val x = cx + r * cos(rad); val y = cy + r * sin(rad)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, activePaint)
        }
        if (moving && (indeterminate || (wavy && shown in 0.001f..0.999f))) postInvalidateOnAnimation()
    }

    override fun getAccessibilityClassName(): CharSequence = android.widget.ProgressBar::class.java.name
}

/**
 * M3 Expressive loading indicator: a shape that keeps morphing (soft burst → cookie → pentagon →
 * pill → sunny → 4-leaf → oval) while rotating, for short indeterminate waits (connecting).
 * 48 dp default; [contained] draws it on a primaryContainer circle. Static with animations off.
 */
class LoadingIndicator(context: Context, val contained: Boolean = false, private val sizeDp: Int = 48) : View(context) {
    var color: Int = if (contained) Theme.onPrimaryContainer else Theme.primary
        set(value) { field = value; shapePaint.color = value; invalidate() }
    var containerColor: Int = Theme.primaryContainer
        set(value) { field = value; containerPaint.color = value; invalidate() }
    private val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = this@LoadingIndicator.color }
    private val containerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.primaryContainer }
    private val path = Path()
    private val xs = FloatArray(N)
    private val ys = FloatArray(N)
    private val origin = SystemClock.uptimeMillis()

    init {
        contentDescription = "Loading"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = pxi(sizeDp)
        setMeasuredDimension(resolveSize(s, widthMeasureSpec), resolveSize(s, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = min(width, height).toFloat()
        if (size <= 0f) return
        val cx = width / 2f; val cy = height / 2f
        if (contained) canvas.drawCircle(cx, cy, size / 2f, containerPaint)
        val radius = size / 2f * if (contained) 0.62f else 0.79f
        val moving = wantsFrames()
        val t = if (moving) SystemClock.uptimeMillis() - origin else 0L
        val segment = (t / PERIOD_MS).toInt()
        val local = (t % PERIOD_MS) / PERIOD_MS.toFloat()
        val x = (local / 0.8f).coerceIn(0f, 1f)
        val p = if (moving) 1f - (1f - x) * (1f - x) * (1f - x) else 0f
        val from = SHAPES[segment % SHAPES.size]
        val to = SHAPES[(segment + 1) % SHAPES.size]
        for (i in 0 until N) {
            val r = lerp(from[i], to[i], p) * radius
            xs[i] = cx + r * COS[i]; ys[i] = cy + r * SIN[i]
        }
        path.reset()
        path.moveTo((xs[N - 1] + xs[0]) / 2f, (ys[N - 1] + ys[0]) / 2f)
        for (i in 0 until N) {
            val j = (i + 1) % N
            path.quadTo(xs[i], ys[i], (xs[i] + xs[j]) / 2f, (ys[i] + ys[j]) / 2f)
        }
        path.close()
        val angle = t * 0.077f + (segment + p) * 45f
        canvas.save()
        canvas.rotate(angle % 360f, cx, cy)
        canvas.drawPath(path, shapePaint)
        canvas.restore()
        if (moving) postInvalidateOnAnimation()
    }

    override fun getAccessibilityClassName(): CharSequence = android.widget.ProgressBar::class.java.name

    private companion object {
        const val N = 96
        const val PERIOD_MS = 650L
        val COS = FloatArray(N) { cos(2.0 * PI * it / N).toFloat() }
        val SIN = FloatArray(N) { sin(2.0 * PI * it / N).toFloat() }
        val SHAPES: Array<FloatArray> = arrayOf(
            shape { 1 + 0.10 * cos(10 * it) },        // soft burst
            shape { 1 + 0.08 * cos(9 * it) },         // cookie 9
            shape { polygon(it, 5, 0.35) },           // rounded pentagon
            shape { ellipse(it, 0.62) },              // pill
            shape { 1 + 0.12 * cos(8 * it) },         // sunny
            shape { 1 + 0.14 * cos(4 * it) },         // 4-leaf cookie
            shape { ellipse(it, 0.84) }               // oval
        )

        fun shape(f: (Double) -> Double): FloatArray {
            val r = FloatArray(N) { f(2.0 * PI * it / N).toFloat() }
            val m = r.maxOrNull() ?: 1f
            for (i in r.indices) r[i] /= m
            return r
        }

        fun polygon(theta: Double, sides: Int, round: Double): Double {
            val sector = 2 * PI / sides
            val local = ((theta + PI / 2) % sector + sector) % sector - sector / 2
            val sharp = cos(PI / sides) / cos(local)
            return sharp * (1 - round) + round
        }

        fun ellipse(theta: Double, b: Double): Double = b / kotlin.math.sqrt(b * b * cos(theta) * cos(theta) + sin(theta) * sin(theta))
    }
}

// ================================================================================== expressive shapes

/** Canvas paths for the M3 Expressive shapes used for delight (cookie badge, pill-blob). */
object ExpressiveShapes {
    /**
     * Writes a scalloped "cookie" (9 rounded lobes by default) centred at ([cx], [cy]) into [path]
     * (reset first). [depth] = how far the valleys dip, as a fraction of [radius].
     */
    fun cookie(path: Path, cx: Float, cy: Float, radius: Float, points: Int = 9, depth: Float = 0.1f, rotationDeg: Float = 0f, samples: Int = 180) {
        path.reset()
        val rot = Math.toRadians(rotationDeg.toDouble())
        var firstX = 0f; var firstY = 0f; var prevX = 0f; var prevY = 0f
        for (i in 0..samples) {
            val theta = 2 * PI * i / samples
            val r = radius * (1 - depth * (1 - cos(points * theta)) / 2)
            val x = (cx + r * cos(theta + rot)).toFloat(); val y = (cy + r * sin(theta + rot)).toFloat()
            if (i == 0) { path.moveTo(x, y); firstX = x; firstY = y }
            else if (i == samples) path.quadTo(prevX, prevY, (prevX + firstX) / 2f, (prevY + firstY) / 2f)
            else path.quadTo(prevX, prevY, (prevX + x) / 2f, (prevY + y) / 2f)
            prevX = x; prevY = y
        }
        path.close()
    }

    /**
     * Organic pill ("pill-blob") filling [rect]: a superellipse with an optional sinusoidal
     * [wobble] (0..1) at [phase] radians, for the connection indicator's breathing.
     */
    fun pillBlob(path: Path, rect: RectF, wobble: Float = 0f, phase: Float = 0f, samples: Int = 120) {
        path.reset()
        val a = rect.width() / 2f; val b = rect.height() / 2f
        val cx = rect.centerX(); val cy = rect.centerY()
        val exponent = 2.0 / 3.4
        for (i in 0 until samples) {
            val theta = 2 * PI * i / samples
            val c = cos(theta); val s = sin(theta)
            val m = 1f + wobble * 0.05f * sin(3 * theta + phase).toFloat()
            val x = cx + a * m * (sign(c) * abs(c).pow(exponent)).toFloat()
            val y = cy + b * m * (sign(s) * abs(s).pow(exponent)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
    }
}

/** Base for drawables that animate per frame while visible (stops when hidden or detached). */
abstract class FrameDrawable : Drawable(), Animatable, Choreographer.FrameCallback {
    private var running = false
    private var posted = false
    private var lastNanos = 0L
    /** Milliseconds of animation so far (paused while hidden). */
    protected var elapsedMs = 0L; private set

    override fun start() { if (!running) { running = true; post() } }
    override fun stop() { running = false; unpost() }
    override fun isRunning(): Boolean = running

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        if (visible) post() else unpost()
        return changed
    }

    private fun post() {
        if (!running || posted || !isVisible || !Motion.enabled()) return
        posted = true
        lastNanos = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun unpost() {
        if (!posted) return
        Choreographer.getInstance().removeFrameCallback(this)
        posted = false
    }

    override fun doFrame(frameTimeNanos: Long) {
        posted = false
        if (!running || !isVisible || callback == null) return
        if (lastNanos != 0L) elapsedMs += ((frameTimeNanos - lastNanos) / 1_000_000L).coerceIn(0L, 64L)
        lastNanos = frameTimeNanos
        onFrame(elapsedMs)
        invalidateSelf()
        posted = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    /** Update animated state (no allocation); the drawable is invalidated afterwards. */
    protected abstract fun onFrame(elapsedMs: Long)

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * 9-point scalloped "cookie" badge (behind the live speed). [start] makes it turn slowly (one turn
 * per 20 s) while visible; [spinOnce] is a short settle turn that then stops redrawing (prefer it:
 * an endless turn redraws at display rate); [rotationDeg] sets a fixed angle.
 */
class CookieDrawable(color: Int = Theme.primaryContainer, private val points: Int = 9, private val depth: Float = 0.1f) : FrameDrawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private val path = Path()
    var color: Int
        get() = paint.color
        set(value) { if (paint.color != value) { paint.color = value; invalidateSelf() } }
    var rotationDeg = 0f
        set(value) { if (field != value) { field = value; rebuild(); invalidateSelf() } }

    override fun onBoundsChange(bounds: Rect) { rebuild() }

    private fun rebuild() {
        val b = bounds
        ExpressiveShapes.cookie(path, b.exactCenterX(), b.exactCenterY(), min(b.width(), b.height()) / 2f, points, depth, rotationDeg)
    }

    private var spinFor = 0L
    private var spinFrom = -1L

    /** One short settle turn (40°, emphasized decelerate, [durationMs]); then no more frames. */
    fun spinOnce(durationMs: Long = 1_200L) {
        if (!Motion.enabled()) { rotationDeg = 0f; return }
        stop()
        spinFor = durationMs; spinFrom = -1L
        start()
    }

    override fun onFrame(elapsedMs: Long) {
        if (spinFor > 0L) {
            if (spinFrom < 0L) spinFrom = elapsedMs
            val t = ((elapsedMs - spinFrom).toFloat() / spinFor).coerceIn(0f, 1f)
            rotationDeg = -40f * (1f - Motion.emphasizedDecelerate.getInterpolation(t))
            if (t >= 1f) { spinFor = 0L; stop() }
            return
        }
        rotationDeg = (elapsedMs % 20_000L) / 20_000f * 360f
    }

    override fun draw(canvas: Canvas) { canvas.drawPath(path, paint) }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
}

/** Pill-blob container for the connection indicator; [start] makes it breathe (wobble) while visible. */
class PillBlobDrawable(color: Int = Theme.surfaceContainerHigh) : FrameDrawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private val path = Path()
    private val rect = RectF()
    private var wobble = 0f
    private var phase = 0f
    var color: Int
        get() = paint.color
        set(value) { if (paint.color != value) { paint.color = value; invalidateSelf() } }

    override fun onBoundsChange(bounds: Rect) { rebuild() }

    override fun stop() {
        super.stop()
        wobble = 0f; phase = 0f
        rebuild(); invalidateSelf()
    }

    private fun rebuild() {
        rect.set(bounds)
        ExpressiveShapes.pillBlob(path, rect, wobble, phase)
    }

    override fun onFrame(elapsedMs: Long) {
        wobble = min(1f, elapsedMs / 400f)
        phase = (elapsedMs % 1600L) / 1600f * 2f * PI.toFloat()
        rebuild()
    }

    override fun draw(canvas: Canvas) { canvas.drawPath(path, paint) }

    override fun getOutline(outline: Outline) {
        val b = bounds
        outline.setRoundRect(b, b.height() / 2f)
    }
}

/**
 * The Ruta mark, exactly as on the launcher icon: Material Symbols electric_moped (filled, ink
 * #0E1A12) on a lime (#B7F36B) 9-point cookie badge with a lobe at the top. The two brand colours
 * are fixed (not dynamic) so the mark is the same on every wallpaper — the only fixed colours in the
 * kit. [sizeDp] is the badge diameter. Decorative unless given a contentDescription. [settle] plays
 * a short turn-and-grow entrance (skipped with animations off).
 */
class BrandMark(context: Context, private val sizeDp: Int = 72) : View(context) {
    companion object {
        /** Badge lime (launcher icon foreground). */
        const val LIME = 0xFFB7F36B.toInt()
        /** Glyph ink = launcher icon background #0E1A12. */
        const val INK = 0xFF0E1A12.toInt()
        // Launcher geometry (108 dp canvas): badge radius 32.64, valleys at 0.869 of it, glyph box
        // 40 dp centred 0.5 dp low.
        private const val DEPTH = 0.131f
        private const val GLYPH = 40f / 65.28f
        private const val GLYPH_DROP = 0.5f / 65.28f
    }

    private val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = LIME }
    private val path = Path()
    private val glyph: Drawable = Icons.drawable(context, "electric_moped", filled = true, tint = INK)
    private var turn = 0f
    private var animator: ValueAnimator? = null

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun setContentDescription(contentDescription: CharSequence?) {
        super.setContentDescription(contentDescription)
        importantForAccessibility = if (contentDescription.isNullOrEmpty()) IMPORTANT_FOR_ACCESSIBILITY_NO else IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = pxi(sizeDp)
        setMeasuredDimension(resolveSize(size, widthMeasureSpec), resolveSize(size, heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val d = min(w, h).toFloat()
        ExpressiveShapes.cookie(path, w / 2f, h / 2f, d / 2f, points = 9, depth = DEPTH, rotationDeg = -90f)
        val g = (d * GLYPH).roundToInt()
        val left = ((w - g) / 2f).roundToInt()
        val top = ((h - g) / 2f + d * GLYPH_DROP).roundToInt()
        glyph.setBounds(left, top, left + g, top + g)
    }

    /** Entrance: the badge grows from 80 % while turning 40° into place (~700 ms, emphasized decelerate). */
    fun settle() {
        animator?.cancel()
        if (!Motion.enabled()) { turn = 0f; scaleX = 1f; scaleY = 1f; invalidate(); return }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 700L
            interpolator = Motion.emphasizedDecelerate
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                turn = -40f * (1f - t)
                val s = 0.8f + 0.2f * t
                scaleX = s; scaleY = s
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel(); animator = null
        turn = 0f; scaleX = 1f; scaleY = 1f
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (turn != 0f) { canvas.save(); canvas.rotate(turn, width / 2f, height / 2f) }
        canvas.drawPath(path, badge)
        if (turn != 0f) canvas.restore()
        glyph.draw(canvas)
    }
}

/**
 * Top-bar connection indicator: pill-blob chip with bluetooth_connected (READY), a small
 * shape-morph loader (CONNECTING, blob breathes) or bluetooth_disabled (IDLE) + a short label.
 * Tap = [onClick] (open the connect/disconnect sheet). 48 dp touch target.
 */
class ConnectionChip(context: Context, private val onClick: () -> Unit) : LinearLayout(context) {
    private val blob = PillBlobDrawable()
    private val iconView = IconView(context, "bluetooth_disabled", iconSizeDp = Icons.SMALL)
    private val loader = LoadingIndicator(context, sizeDp = 20)
    private val label = context.singleLine("", Typography.labelLarge, Theme.onSurfaceVariant)
    private var phase: LinkPhase? = null
    private var attention = false

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = pxi(Spacing.TOUCH)
        val inset = pxi(8)
        background = rippleOver(
            InsetDrawable(blob, 0, inset, 0, inset),
            RoundedShapeDrawable(Color.WHITE, px(Shapes.FULL)).apply { setInsets(0, inset, 0, inset) },
            Theme.onSurface
        )
        addView(iconView, LayoutParams(pxi(Icons.SMALL), pxi(Icons.SMALL)))
        addView(loader, LayoutParams(pxi(20), pxi(20)))
        addView(label, LayoutParams(WRAP, WRAP).apply { marginStart = pxi(6) })
        setPaddingRelative(pxi(10), 0, pxi(14), 0)
        loader.visibility = GONE
        label.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        loader.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        isClickable = true
        isFocusable = true
        setOnSingleClick { this.onClick() }
        update(LinkPhase.IDLE, "Offline")
    }

    /**
     * [label] e.g. "Live", "Connecting", "Offline"; [attention] = connected without data (amber) or
     * a failed attempt (error colours).
     */
    fun update(phase: LinkPhase, label: String, attention: Boolean = false) {
        this.label.show(label)
        val spoken = "Scooter: $label"
        if (contentDescription != spoken) { contentDescription = spoken; tooltipText = spoken }
        if (phase == this.phase && attention == this.attention) return
        this.phase = phase; this.attention = attention
        val tone = when (phase) {
            LinkPhase.READY -> if (attention) Tone.WARNING else Tone.PRIMARY
            LinkPhase.CONNECTING -> Tone.SECONDARY
            LinkPhase.IDLE -> if (attention) Tone.ERROR else Tone.NEUTRAL
        }
        blob.color = tone.container
        val ink = if (tone == Tone.NEUTRAL) Theme.onSurfaceVariant else tone.onContainer
        this.label.setTextColor(ink)
        iconView.tint = ink
        loader.color = ink
        when (phase) {
            LinkPhase.READY -> iconView.setIcon("bluetooth_connected", filled = true)
            LinkPhase.CONNECTING -> {}
            LinkPhase.IDLE -> iconView.setIcon("bluetooth_disabled")
        }
        iconView.visible(phase != LinkPhase.CONNECTING)
        loader.visible(phase == LinkPhase.CONNECTING)
        if (phase == LinkPhase.CONNECTING) blob.start() else blob.stop()
    }
}

// ================================================================================== snackbar

/**
 * Snackbar overlay. Add it MATCH_PARENT on top of the content (it lets touches through) and set
 * [bottomOffset] to the navigation bar's height so snackbars sit just above it. One at a time; a
 * new one replaces the current. Auto-dismiss (4 s, 8 s with an action; longer when accessibility
 * services ask for it), swipe sideways to dismiss.
 */
class SnackbarHost(context: Context) : FrameLayout(context) {
    private var current: SnackbarView? = null
    private val hideTask = Runnable { dismiss() }

    /** Space to keep free at the bottom (px), e.g. navigation bar height + inset. */
    var bottomOffset: Int = 0
        set(value) {
            if (field == value) return
            field = value
            updateMargin()
        }

    /**
     * The view snackbars must stay above (M3: snackbars sit above the FAB), e.g. the foreground
     * screen's shown extended FAB; null = none. Re-checked every frame while a snackbar shows, so a
     * FAB that appears or hides moves the snackbar with it.
     */
    var anchor: (() -> View?)? = null

    private val anchorWatcher = ViewTreeObserver.OnPreDrawListener { !updateMargin() }
    private val anchorLocation = IntArray(2)
    private val hostLocation = IntArray(2)

    /** Bottom margin for the current snackbar: above [bottomOffset], or 8 dp above a shown [anchor]. */
    private fun wantedMargin(): Int {
        var margin = bottomOffset + pxi(12)
        val view = anchor?.invoke()
        if (view != null && view.isShown && view.alpha > 0.01f && view.height > 0 && height > 0) {
            view.getLocationInWindow(anchorLocation)
            getLocationInWindow(hostLocation)
            val anchorTop = anchorLocation[1] - hostLocation[1]
            if (anchorTop in 0 until height) margin = max(margin, height - anchorTop + pxi(8))
        }
        return margin
    }

    /** Applies [wantedMargin]; true when it changed (a new layout pass is needed). */
    private fun updateMargin(): Boolean {
        val bar = current ?: return false
        val params = bar.layoutParams as? LayoutParams ?: return false
        val margin = wantedMargin()
        if (params.bottomMargin == margin) return false
        params.bottomMargin = margin
        bar.layoutParams = params
        return true
    }

    fun show(text: CharSequence, actionLabel: String? = null, durationMs: Long = if (actionLabel == null) 4_000L else 8_000L, action: (() -> Unit)? = null) {
        current?.let { old -> removeCallbacks(hideTask); old.animate().cancel(); removeView(old) }
        val bar = SnackbarView(text, actionLabel, action)
        current = bar
        addView(bar, LayoutParams(MATCH, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            marginStart = pxi(Spacing.L); marginEnd = pxi(Spacing.L); bottomMargin = bottomOffset + pxi(12)
        })
        updateMargin()
        if (isAttachedToWindow) { viewTreeObserver.removeOnPreDrawListener(anchorWatcher); viewTreeObserver.addOnPreDrawListener(anchorWatcher) }
        if (Motion.enabled()) {
            bar.alpha = 0f; bar.translationY = px(16f)
            bar.animate().alpha(1f).translationY(0f).setDuration(Motion.SHORT3).setInterpolator(Motion.emphasizedDecelerate).start()
        }
        val flags = AccessibilityManager.FLAG_CONTENT_TEXT or if (actionLabel != null) AccessibilityManager.FLAG_CONTENT_CONTROLS else 0
        val timeout = context.getSystemService(AccessibilityManager::class.java)
            ?.getRecommendedTimeoutMillis(durationMs.toInt(), flags)?.toLong() ?: durationMs
        postDelayed(hideTask, timeout)
    }

    fun dismiss() {
        val bar = current ?: return
        current = null
        removeCallbacks(hideTask)
        if (!Motion.enabled() || !bar.isAttachedToWindow) { removeView(bar); return }
        bar.animate().alpha(0f).translationY(px(8f)).setDuration(Motion.SHORT2).setInterpolator(Motion.standardAccelerate)
            .withEndAction { removeView(bar) }.start()
    }

    private fun dismissSideways(bar: SnackbarView, direction: Float) {
        if (current === bar) current = null
        removeCallbacks(hideTask)
        bar.animate().translationX(direction * bar.width).alpha(0f).setDuration(Motion.SHORT3)
            .withEndAction { removeView(bar) }.start()
    }

    override fun onViewRemoved(child: View?) {
        super.onViewRemoved(child)
        if (childCount == 0) viewTreeObserver.removeOnPreDrawListener(anchorWatcher)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (current != null) { viewTreeObserver.removeOnPreDrawListener(anchorWatcher); viewTreeObserver.addOnPreDrawListener(anchorWatcher) }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(hideTask)
        viewTreeObserver.removeOnPreDrawListener(anchorWatcher)
        super.onDetachedFromWindow()
    }

    private inner class SnackbarView(text: CharSequence, actionLabel: String?, action: (() -> Unit)?) : LinearLayout(context) {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var dragging = false

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = pxi(48)
            background = RoundedShapeDrawable(Theme.inverseSurface, px(Shapes.SNACKBAR))
            elevation = px(Elevation.LEVEL3)
            setPaddingRelative(pxi(16), pxi(6), pxi(if (actionLabel != null) 8 else 16), pxi(6))
            val message = context.textView(text, Typography.bodyMedium, Theme.inverseOnSurface).apply {
                maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
                setPadding(0, pxi(8), 0, pxi(8))
            }
            addView(message, LayoutParams(0, WRAP, 1f))
            if (actionLabel != null) {
                val button = context.singleLine(actionLabel, Typography.labelLarge, Theme.inversePrimary).apply {
                    gravity = Gravity.CENTER
                    minHeight = pxi(48); minWidth = pxi(48)
                    setPadding(pxi(12), 0, pxi(12), 0)
                    background = rippleOver(null, RoundedShapeDrawable(Color.WHITE, px(Shapes.FULL)), Theme.inversePrimary)
                    isClickable = true; isFocusable = true
                    setOnClickListener { action?.invoke(); dismiss() }
                }
                addView(button, LayoutParams(WRAP, WRAP).apply { marginStart = pxi(8) })
            }
            isClickable = true
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val size = MeasureSpec.getSize(widthMeasureSpec)
            val cap = pxi(560)
            super.onMeasure(if (size > cap) MeasureSpec.makeMeasureSpec(cap, MeasureSpec.EXACTLY) else widthMeasureSpec, heightMeasureSpec)
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = ev.rawX; dragging = false }
                MotionEvent.ACTION_MOVE -> if (!dragging && abs(ev.rawX - downX) > slop) { dragging = true; removeCallbacks(hideTask) }
            }
            return dragging
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = ev.rawX; dragging = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    if (!dragging && abs(dx) > slop) { dragging = true; removeCallbacks(hideTask) }
                    if (dragging) { translationX = dx; alpha = (1f - abs(dx) / max(1, width)).coerceIn(0f, 1f) }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        if (abs(translationX) > width * 0.35f) dismissSideways(this, sign(translationX))
                        else { animate().translationX(0f).alpha(1f).setDuration(Motion.SHORT3).start(); postDelayed(hideTask, 4_000L) }
                    }
                    dragging = false
                }
            }
            return true
        }
    }
}

// ================================================================================== bottom sheet

/**
 * M3 modal bottom sheet in its own dialog window: animated scrim, sheet with 28 dp top corners
 * (surfaceContainerLow) rising with a critically damped spring, 48 dp drag handle (tap or drag down
 * to dismiss), optional title, scrolling content, edge-to-edge under the navigation bar, back /
 * scrim tap dismiss. Max width 640 dp. Typical use: scooter picker, permission setup.
 * Double taps are safe: a scrim/handle tap in the first [SETTLE_MS] after [show] (the second tap of
 * the double tap that opened it) does not close it, and once closing no touch reaches the content.
 *
 *     BottomSheet(activity).apply { setTitle("Scooter"); setContent(list) }.show()
 */
class BottomSheet(private val context: Context, private val contentPaddingDp: Int = Spacing.L) {
    companion object {
        /** Scrim/handle taps this soon after [show] are ignored (the tail of the opening double tap). */
        const val SETTLE_MS = 350L
    }

    private val root = FrameLayout(context)
    private val scrim = View(context)
    private val sheet = SheetLayout()
    private val shape = RoundedShapeDrawable(Theme.surfaceContainerLow)
    private val titleView = context.textView("", Typography.titleLarge, Theme.onSurface).apply {
        isAccessibilityHeading = true; visibility = View.GONE
    }
    private val body = ScrollView(context).apply { isFillViewport = false; overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS }
    private val bodyFrame = FrameLayout(context)
    private val dialog = SheetDialog()
    private var spring: Spring? = null
    private var dismissing = false
    private var shown = false
    private var shownAt = 0L
    /** Called once after the sheet is gone (any way it was closed). */
    var onDismiss: (() -> Unit)? = null
    /** false = back, scrim and drag cannot close it (only [dismiss]). */
    var cancelable = true

    val isShowing: Boolean get() = dialog.isShowing && !dismissing

    init {
        scrim.setBackgroundColor(alpha(Theme.scrim, 0.32f))
        scrim.alpha = 0f
        scrim.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        scrim.setOnClickListener { if (cancelable && settled()) dismiss() }
        root.addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH))

        val radius = context.dpf(Shapes.SHEET)
        shape.setRadii(radius, radius, 0f, 0f)
        sheet.orientation = LinearLayout.VERTICAL
        sheet.background = shape
        sheet.isClickable = true
        val handle = FrameLayout(context).apply {
            val pill = View(context).apply { background = RoundedShapeDrawable(alpha(Theme.onSurfaceVariant, 0.4f), context.dpf(2f)) }
            addView(pill, FrameLayout.LayoutParams(context.dp(32), context.dp(4), Gravity.CENTER))
            contentDescription = "Close"
            isClickable = true; isFocusable = true
            setOnClickListener { if (cancelable && settled()) dismiss() }
        }
        sheet.addView(handle, LinearLayout.LayoutParams(MATCH, context.dp(48)))
        titleView.setPadding(context.dp(24), 0, context.dp(24), context.dp(Spacing.S))
        sheet.addView(titleView, LinearLayout.LayoutParams(MATCH, WRAP))
        bodyFrame.setPadding(context.dp(contentPaddingDp), 0, context.dp(contentPaddingDp), context.dp(Spacing.L))
        body.addView(bodyFrame, FrameLayout.LayoutParams(MATCH, WRAP))
        sheet.addView(body, LinearLayout.LayoutParams(MATCH, WRAP))
        sheet.translationY = 100_000f
        root.addView(sheet, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { topMargin = context.dp(72) })

        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            sheet.setPadding(bars.left, 0, bars.right, max(bars.bottom, ime.bottom))
            (sheet.layoutParams as FrameLayout.LayoutParams).let { lp ->
                val top = bars.top + context.dp(48)
                if (lp.topMargin != top) { lp.topMargin = top; sheet.layoutParams = lp }
            }
            WindowInsets.CONSUMED
        }
        spring = Spring(0f, Motion.STIFFNESS, Motion.DAMPING_NO_BOUNCE, threshold = 0.5f) { y ->
            sheet.translationY = y
            updateScrim()
        }
        dialog.setOnDismissListener { onDismiss?.invoke() }
    }

    fun setTitle(text: CharSequence?) {
        titleView.show(text ?: "")
        titleView.visible(!text.isNullOrEmpty())
        sheet.accessibilityPaneTitle = text
    }

    /** Names the sheet for TalkBack without showing a title (content that has its own header). */
    fun setPaneTitle(text: CharSequence?) { sheet.accessibilityPaneTitle = text }

    private fun settled(): Boolean = SystemClock.uptimeMillis() - shownAt >= SETTLE_MS

    /** Replaces the content (any view; tall content scrolls). */
    fun setContent(view: View) {
        (view.parent as? ViewGroup)?.removeView(view)
        bodyFrame.removeAllViews()
        bodyFrame.addView(view, FrameLayout.LayoutParams(MATCH, WRAP))
    }

    fun show() {
        if (dialog.isShowing) return
        dismissing = false
        shownAt = SystemClock.uptimeMillis()
        dialog.show()
        sheet.post {
            val h = sheet.height.toFloat()
            spring?.snapTo(h)
            spring?.onEnd = null
            spring?.animateTo(0f)
            shown = true
        }
    }

    /** Slides the sheet away, then closes the dialog. */
    fun dismiss() = close(0f)

    private fun close(velocity: Float) {
        if (dismissing || !dialog.isShowing) return
        dismissing = true
        val s = spring
        if (s == null || !shown) { finish(); return }
        s.onEnd = { finish() }
        s.animateTo(max(1f, sheet.height.toFloat()), velocity)
    }

    private fun settle(velocity: Float) {
        spring?.onEnd = null
        spring?.animateTo(0f, velocity)
    }

    private fun finish() {
        spring?.onEnd = null
        runCatching { if (dialog.isShowing) dialog.dismiss() }
    }

    private fun updateScrim() {
        val h = max(1, sheet.height)
        scrim.alpha = (1f - sheet.translationY / h).coerceIn(0f, 1f)
    }

    private inner class SheetDialog : Dialog(context, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar) {
        init {
            setContentView(root)
            setCanceledOnTouchOutside(false)
            window?.let { w ->
                w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
                w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                w.setDimAmount(0f)
                w.setWindowAnimations(0)
                @Suppress("DEPRECATION") run {
                    w.setDecorFitsSystemWindows(false)
                    w.statusBarColor = Color.TRANSPARENT
                    w.navigationBarColor = Color.TRANSPARENT
                }
                w.isNavigationBarContrastEnforced = false
                w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
                w.attributes = w.attributes.apply {
                    setFitInsetsTypes(0)
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
            }
        }

        override fun onStart() {
            super.onStart()
            window?.insetsController?.setSystemBarsAppearance(
                0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            )
        }

        @Deprecated("Deprecated in Java")
        override fun onBackPressed() { if (cancelable) dismiss() }
    }

    /** Sheet container: caps width and turns downward drags (content at top) into dismiss gestures. */
    private inner class SheetLayout : LinearLayout(context) {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startY = 0f
        private var dragging = false
        private var tracker: VelocityTracker? = null

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val size = MeasureSpec.getSize(widthMeasureSpec)
            val cap = context.dp(Spacing.MAX_WIDTH)
            super.onMeasure(if (size > cap) MeasureSpec.makeMeasureSpec(cap, MeasureSpec.EXACTLY) else widthMeasureSpec, heightMeasureSpec)
        }

        private fun track(ev: MotionEvent) {
            val t = tracker ?: VelocityTracker.obtain().also { tracker = it }
            val copy = MotionEvent.obtain(ev)
            copy.setLocation(ev.rawX, ev.rawY)
            t.addMovement(copy)
            copy.recycle()
        }

        private fun begin(ev: MotionEvent) {
            downX = ev.rawX; downY = ev.rawY
            startY = translationY
            dragging = false
            tracker?.recycle(); tracker = null
            track(ev)
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            if (dismissing) return true // Closing: nothing inside reacts any more.
            if (!cancelable) return false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> begin(ev)
                MotionEvent.ACTION_MOVE -> {
                    track(ev)
                    val dy = ev.rawY - downY
                    if (!dragging && dy > slop && dy > abs(ev.rawX - downX) && !body.canScrollVertically(-1)) {
                        dragging = true
                        spring?.cancel()
                        startY = translationY
                        downY = ev.rawY
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { tracker?.recycle(); tracker = null }
            }
            return dragging
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (!cancelable || dismissing) return true
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> begin(ev)
                MotionEvent.ACTION_MOVE -> {
                    track(ev)
                    val dy = ev.rawY - downY
                    if (!dragging && abs(dy) > slop) { dragging = true; spring?.cancel(); startY = translationY; downY = ev.rawY }
                    if (dragging) {
                        val raw = startY + (ev.rawY - downY)
                        translationY = if (raw < 0f) raw * 0.15f else raw
                        updateScrim()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        val t = tracker
                        t?.computeCurrentVelocity(1000)
                        val velocity = t?.yVelocity ?: 0f
                        val fast = velocity > context.dpf(1000f)
                        if (fast || translationY > height * 0.3f) {
                            spring?.snapTo(translationY)
                            close(velocity)
                        } else {
                            spring?.snapTo(translationY)
                            settle(velocity)
                        }
                    }
                    dragging = false
                    tracker?.recycle(); tracker = null
                }
            }
            return true
        }
    }
}

// ================================================================================== explain sheet

/**
 * What a "tap explains" bottom sheet shows (design rule: tapping a value explains it, it never
 * jumps elsewhere). Top to bottom: [icon] + [title]; the big current [value] with its [unit] and an
 * optional small [note] ("est.", "last known"); [meaning] in one or two plain sentences; [rows] of
 * label → value (e.g. Front / Rear), each with an optional one-line plain meaning from [rowNotes]
 * (keyed by the row label); a 24 h trend from [spark] (oldest first; hidden when empty); and, when
 * [onHistory] is set, a secondary text button ([historyLabel], default "History") — the only way
 * from the sheet to another screen.
 *
 * Two optional slots (2.2) for sheets that need more than numbers: [detail] goes right under the
 * meaning (e.g. the parked spot's mini-map and place), [actions] at the very end (e.g. Share + Walk,
 * full width). Pass freshly built views; each explain sheet is built once.
 */
data class ExplainSheet(
    val icon: String,
    val title: String,
    val value: String,
    val unit: String? = null,
    val meaning: String,
    val rows: List<Pair<String, String>> = emptyList(),
    val spark: List<Double> = emptyList(),
    val historyLabel: String? = null,
    val onHistory: (() -> Unit)? = null,
    val note: String? = null,
    val rowNotes: Map<String, String> = emptyMap(),
    val detail: View? = null,
    val actions: View? = null
)

private var explainShown: WeakReference<BottomSheet>? = null

/** Shows [sheet] as the standard explain bottom sheet (one at a time: an open one is replaced). */
fun Host.explain(sheet: ExplainSheet) { showExplain(sheet) }

/** [explain] that returns the bottom sheet (null when the activity is finishing), e.g. to dismiss it in dispose(). */
fun Host.showExplain(sheet: ExplainSheet): BottomSheet? {
    explainShown?.get()?.let { if (it.isShowing) it.dismiss() }
    val holder = arrayOfNulls<BottomSheet>(1)
    // The sheet closes first, then the destination opens under its slide-out.
    val history: (() -> Unit)? = sheet.onHistory?.let { action -> { holder[0]?.dismiss(); action() } }
    val content = ExplainView(context, sheet, history)
    val shown = showSheet(content, null) ?: return null
    holder[0] = shown
    shown.setPaneTitle(sheet.title)
    explainShown = WeakReference(shown)
    return shown
}

/** Content of an explain sheet (see [ExplainSheet]); built once, no live updates. */
private class ExplainView(context: Context, spec: ExplainSheet, onHistory: (() -> Unit)?) : LinearLayout(context) {
    init {
        orientation = VERTICAL
        // The sheet pads 16 dp; 8 more lines the text up with sheet titles (24 dp).
        setPadding(pxi(8), 0, pxi(8), pxi(4))

        val head = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(IconView(context, spec.icon, tint = Theme.onSurfaceVariant), LayoutParams(pxi(24), pxi(24)))
            addView(context.singleLine(spec.title, Typography.titleMedium, Theme.onSurfaceVariant).apply { isAccessibilityHeading = true },
                LayoutParams(0, WRAP, 1f).apply { marginStart = pxi(12) })
        }
        addView(head, LayoutParams(MATCH, WRAP))

        val value = LinearLayout(context).apply {
            orientation = HORIZONTAL
            isBaselineAligned = true
            addView(context.singleLine(spec.value, Typography.displaySmall, Theme.onSurface).tabular(), LayoutParams(WRAP, WRAP))
            spec.unit?.takeIf { it.isNotEmpty() }?.let {
                addView(context.singleLine(it, Typography.titleLarge.withWeight(400), Theme.onSurfaceVariant), LayoutParams(WRAP, WRAP).apply { marginStart = pxi(6) })
            }
            spec.note?.takeIf { it.isNotEmpty() }?.let {
                addView(context.singleLine(it, Typography.labelLarge, Theme.onSurfaceVariant), LayoutParams(WRAP, WRAP).apply { marginStart = pxi(8) })
            }
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = listOfNotNull(spec.value, spec.unit?.takeIf { it.isNotEmpty() }, spec.note?.takeIf { it.isNotEmpty() }?.let { if (it == "est.") "estimated" else it }).joinToString(" ")
            for (i in 0 until childCount) getChildAt(i).importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        addView(value, LayoutParams(MATCH, WRAP).apply { topMargin = pxi(12) })

        addView(context.textView(spec.meaning, Typography.bodyLarge, Theme.onSurfaceVariant), LayoutParams(MATCH, WRAP).apply { topMargin = pxi(8) })

        spec.detail?.let { detail ->
            (detail.parent as? ViewGroup)?.removeView(detail)
            addView(detail, LayoutParams(MATCH, WRAP).apply { topMargin = pxi(16) })
        }

        if (spec.rows.isNotEmpty()) {
            val table = LinearLayout(context).apply { orientation = VERTICAL }
            spec.rows.forEachIndexed { index, (label, amount) ->
                table.addView(context.divider())
                table.addView(ExplainRow(context, label, amount, spec.rowNotes[label]), LayoutParams(MATCH, WRAP))
                if (index == spec.rows.lastIndex) table.addView(context.divider())
            }
            addView(table, LayoutParams(MATCH, WRAP).apply { topMargin = pxi(20) })
        }

        val points = spec.spark.filter { it.isFinite() }
        if (points.isNotEmpty()) {
            val trend = TrendLine(context).apply { setValues(points) }
            addView(trend, LayoutParams(MATCH, pxi(64)).apply { topMargin = pxi(24) })
            val caption = LinearLayout(context).apply {
                orientation = HORIZONTAL
                addView(context.singleLine("Last 24 h", Typography.labelMedium, Theme.onSurfaceVariant), LayoutParams(0, WRAP, 1f))
                addView(context.singleLine(spanText(points, spec.unit), Typography.labelMedium, Theme.onSurfaceVariant).tabular(), LayoutParams(WRAP, WRAP))
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
                contentDescription = "Last 24 hours: ${spanText(points, spec.unit).replace("–", " to ")}"
                for (i in 0 until childCount) getChildAt(i).importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            addView(caption, LayoutParams(MATCH, WRAP).apply { topMargin = pxi(6) })
        }

        if (onHistory != null) {
            val button = context.actionButton(spec.historyLabel ?: "History", ButtonVariant.TEXT) { onHistory() }
            addView(button, LayoutParams(WRAP, WRAP).apply { topMargin = pxi(16); gravity = Gravity.END })
        }

        spec.actions?.let { actions ->
            (actions.parent as? ViewGroup)?.removeView(actions)
            addView(actions, LayoutParams(MATCH, WRAP).apply { topMargin = pxi(24) })
        }
    }

    /** "97.5–99 %" (one decimal below a 10-point span), or the single value. */
    private fun spanText(values: List<Double>, unit: String?): String {
        val lo = values.min(); val hi = values.max()
        val decimals = if (hi - lo < 10) 1 else 0
        val suffix = unit?.takeIf { it.isNotEmpty() }?.let { if (it.startsWith("°")) it else " $it" }.orEmpty()
        val a = Fmt.decimal(lo, decimals); val b = Fmt.decimal(hi, decimals)
        return if (a == b) "$a$suffix" else "$a–$b$suffix"
    }
}

/** label (+ one-line meaning) … value, 56 dp minimum; read as one item. */
private class ExplainRow(context: Context, label: String, value: String, note: String?) : LinearLayout(context) {
    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = pxi(56)
        setPadding(0, pxi(10), 0, pxi(10))
        val texts = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(context.textView(label, Typography.bodyLarge, Theme.onSurface), LayoutParams(MATCH, WRAP))
            if (!note.isNullOrEmpty()) addView(context.textView(note, Typography.bodyMedium, Theme.onSurfaceVariant), LayoutParams(MATCH, WRAP).apply { topMargin = pxi(2) })
        }
        addView(texts, LayoutParams(0, WRAP, 1f))
        addView(context.singleLine(value, Typography.titleMedium, Theme.onSurface).tabular().apply { gravity = Gravity.END },
            LayoutParams(WRAP, WRAP).apply { marginStart = pxi(16) })
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = listOfNotNull(label, value, note?.takeIf { it.isNotEmpty() }).joinToString(", ")
        texts.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        getChildAt(1).importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
}

/**
 * Small trend line for explain sheets: [setValues] (oldest first, evenly spaced) drawn as a 2 dp
 * primary line with a faint fill, the newest value ringed, a hairline floor; no axes. One value is a
 * dot. Decorative for TalkBack (the sheet states the value and range). Allocation-free onDraw.
 */
class TrendLine(context: Context) : View(context) {
    private var values = DoubleArray(0)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = px(2f); strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND; color = Theme.primary
    }
    private val area = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = alpha(Theme.primary, 0.10f) }
    private val floor = Paint().apply { color = Theme.outlineVariant; strokeWidth = max(1f, px(1f) / 2f) }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.primary }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.surfaceContainerLow }
    private val path = Path()
    private val fillPath = Path()

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    /** Colour of the surface behind the line (the ring around the newest point). */
    fun setSurface(color: Int) { ring.color = color; invalidate() }

    fun setValues(next: List<Double>) {
        values = next.filter { it.isFinite() }.toDoubleArray()
        rebuild()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w, h, oldw, oldh); rebuild() }

    private fun rebuild() {
        path.reset(); fillPath.reset()
        val n = values.size
        if (n < 2 || width == 0) return
        val pad = px(6f)
        val w = width - 2 * pad; val h = height - 2 * pad
        var lo = values.min(); var hi = values.max()
        // A flat series sits mid-height; small spans are not blown up into drama.
        val span = max(hi - lo, max(abs(hi), 1.0) * 0.02)
        val mid = (hi + lo) / 2
        lo = mid - span / 2; hi = mid + span / 2
        for (i in 0 until n) {
            val x = pad + w * i / (n - 1)
            val y = pad + h - ((values[i] - lo) / (hi - lo)).toFloat() * h
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        fillPath.set(path)
        fillPath.lineTo(pad + w, height - pad)
        fillPath.lineTo(pad, height - pad)
        fillPath.close()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), resolveSize(pxi(64), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pad = px(6f)
        canvas.drawLine(pad, height - pad, width - pad, height - pad, floor)
        val n = values.size
        if (n == 0) return
        if (n == 1) {
            canvas.drawCircle(width - pad - px(4f), height / 2f, px(5.5f), ring)
            canvas.drawCircle(width - pad - px(4f), height / 2f, px(3.5f), dot)
            return
        }
        canvas.drawPath(fillPath, area)
        canvas.drawPath(path, line)
        // Newest point: last vertex of the line.
        val w = width - 2 * pad; val h = height - 2 * pad
        var lo = values.min(); var hi = values.max()
        val span = max(hi - lo, max(abs(hi), 1.0) * 0.02)
        val mid = (hi + lo) / 2
        lo = mid - span / 2; hi = mid + span / 2
        val x = pad + w
        val y = pad + h - ((values[n - 1] - lo) / (hi - lo)).toFloat() * h
        canvas.drawCircle(x, y, px(5.5f), ring)
        canvas.drawCircle(x, y, px(3.5f), dot)
    }
}
