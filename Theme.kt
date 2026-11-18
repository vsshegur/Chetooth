package dev.codesurgen.ruta

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.util.TypedValue
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign

/*
 * Ruta 2.1 design tokens: Material 3 (Expressive) dark colour roles from the system's dynamic
 * tonal palettes, the type scale, shape scale, spacing and elevation. Framework only.
 * Call Theme.init(context) once before building any view (MainActivity.onCreate); every component
 * reads the roles at construction time.
 */

/** Material 3 dark colour roles. Values are ARGB ints; read them after [init]. */
object Theme {
    // Palette arrays are indexed like the system shades 0, 10, 50, 100, 200 … 1000 (tone 100 → 0).
    private const val S0 = 0; private const val S10 = 1; private const val S50 = 2; private const val S100 = 3
    private const val S200 = 4; private const val S400 = 6
    private const val S600 = 8; private const val S700 = 9; private const val S800 = 10; private const val S900 = 11
    private const val S1000 = 12

    /**
     * API 30 fallback: tone ramps generated offline from the seed #8BD450 (electric lime) at a fixed
     * CIELAB hue with gamut-clamped chroma (tone = L*), an HCT-like approximation.
     * accent1 = seed hue C64, accent2 = seed hue C18, accent3 = hue +60° C40, neutral1 C4, neutral2 C9.
     */
    private val SEED_ACCENT1 = intArrayOf(
        0xFFFFFFFF.toInt(), 0xFFF7FFEE.toInt(), 0xFFD0FFA5.toInt(), 0xFFB5F57E.toInt(), 0xFF98D864.toInt(), 0xFF7DBC4A.toInt(),
        0xFF61A12F.toInt(), 0xFF468611.toInt(), 0xFF326B00.toInt(), 0xFF245100.toInt(), 0xFF1A3700.toInt(), 0xFF131F00.toInt(), 0xFF000000.toInt()
    )
    private val SEED_ACCENT2 = intArrayOf(
        0xFFFFFFFF.toInt(), 0xFFF7FFEE.toInt(), 0xFFE6F6D5.toInt(), 0xFFD8E8C7.toInt(), 0xFFBCCCAC.toInt(), 0xFFA1B091.toInt(),
        0xFF869677.toInt(), 0xFF6D7C5F.toInt(), 0xFF556347.toInt(), 0xFF3D4B30.toInt(), 0xFF27341B.toInt(), 0xFF141F01.toInt(), 0xFF000000.toInt()
    )
    private val SEED_ACCENT3 = intArrayOf(
        0xFFFFFFFF.toInt(), 0xFFF2FFFD.toInt(), 0xFFB1FFF7.toInt(), 0xFF70F7EC.toInt(), 0xFF50DBD0.toInt(), 0xFF27BEB4.toInt(),
        0xFF00A299.toInt(), 0xFF00867E.toInt(), 0xFF006A64.toInt(), 0xFF00504B.toInt(), 0xFF003734.toInt(), 0xFF00201E.toInt(), 0xFF000000.toInt()
    )
    private val SEED_NEUTRAL1 = intArrayOf(
        0xFFFFFFFF.toInt(), 0xFFFAFDF6.toInt(), 0xFFEEF2EA.toInt(), 0xFFE0E4DC.toInt(), 0xFFC4C8C0.toInt(), 0xFFA9ACA5.toInt(),
        0xFF8E928B.toInt(), 0xFF757872.toInt(), 0xFF5C5F59.toInt(), 0xFF454842.toInt(), 0xFF2F312C.toInt(), 0xFF1A1C17.toInt(), 0xFF000000.toInt()
    )
    private val SEED_NEUTRAL2 = intArrayOf(
        0xFFFFFFFF.toInt(), 0xFFF7FFEE.toInt(), 0xFFEBF3E3.toInt(), 0xFFDDE5D5.toInt(), 0xFFC1C9B9.toInt(), 0xFFA6AE9E.toInt(),
        0xFF8C9384.toInt(), 0xFF72796B.toInt(), 0xFF5A6153.toInt(), 0xFF42493C.toInt(), 0xFF2C3226.toInt(), 0xFF181D11.toInt(), 0xFF000000.toInt()
    )

    /** Current tonal palettes (accent1..3, neutral1..2); set before any role. */
    private var palettes: Array<IntArray>? = null

    /** True once [init] ran. */
    var initialized = false; private set
    /** True when the roles come from the system's dynamic (wallpaper) palettes, false = seed fallback. */
    var isDynamic = false; private set

    // ---- accent roles
    var primary = 0; private set
    var onPrimary = 0; private set
    var primaryContainer = 0; private set
    var onPrimaryContainer = 0; private set
    var inversePrimary = 0; private set
    var secondary = 0; private set
    var onSecondary = 0; private set
    var secondaryContainer = 0; private set
    var onSecondaryContainer = 0; private set
    var tertiary = 0; private set
    var onTertiary = 0; private set
    var tertiaryContainer = 0; private set
    var onTertiaryContainer = 0; private set
    var error = 0; private set
    var onError = 0; private set
    var errorContainer = 0; private set
    var onErrorContainer = 0; private set

    // ---- surfaces (darkest → lightest: containerLowest, background, surface, containerLow, container, containerHigh, containerHighest)
    /** Window/screen background (surface darkened, ≈ tone 6). */
    var background = 0; private set
    var onBackground = 0; private set
    /** Plain surface (≈ tone 10): sheets' scrim edge, outlined cards. */
    var surface = 0; private set
    var surfaceDim = 0; private set
    var surfaceBright = 0; private set
    var surfaceContainerLowest = 0; private set
    var surfaceContainerLow = 0; private set
    /** Default card / navigation bar / tile container. */
    var surfaceContainer = 0; private set
    var surfaceContainerHigh = 0; private set
    var surfaceContainerHighest = 0; private set
    var surfaceVariant = 0; private set
    var onSurface = 0; private set
    var onSurfaceVariant = 0; private set
    var outline = 0; private set
    var outlineVariant = 0; private set
    var inverseSurface = 0; private set
    var inverseOnSurface = 0; private set
    val scrim: Int get() = Color.BLACK
    val shadow: Int get() = Color.BLACK

    // ---- semantic, harmonised to the scheme
    /** Battery ≥ 50 %. */
    val batteryHigh: Int get() = primary
    /** Battery 20–50 %. */
    val batteryMid: Int get() = tertiary
    /** Battery < 20 %. */
    val batteryLow: Int get() = error
    /** Charging state (bolt glyph, charging arcs). */
    val charging: Int get() = tertiary
    /** Drive state / live speed. */
    val drive: Int get() = primary
    /** Amber "needs attention" (not an error), harmonised toward [primary]. */
    var warning = 0; private set
    var onWarning = 0; private set
    var warningContainer = 0; private set
    var onWarningContainer = 0; private set
    /** Chart series in order: [chart1] main series, then [chart2], [chart3]. */
    val chart1: Int get() = primary
    val chart2: Int get() = tertiary
    val chart3: Int get() = secondary
    /** Glass of the scooter-display (TFT) preview: near black. */
    val tftGlass: Int get() = surfaceContainerLowest

    init { applyPalettes(SEED_ACCENT1, SEED_ACCENT2, SEED_ACCENT3, SEED_NEUTRAL1, SEED_NEUTRAL2, dynamic = false) }

    /** Loads the roles once (idempotent). Must run before any UI is built. */
    fun init(context: Context) {
        if (initialized) return
        reload(context)
    }

    /**
     * Re-reads the system palettes (e.g. after a wallpaper/colour change recreated the activity).
     * Returns true when any role changed.
     */
    fun reload(context: Context): Boolean {
        val before = primary xor surfaceContainer xor tertiary xor secondaryContainer
        val dynamic = if (Build.VERSION.SDK_INT >= 31) runCatching { readSystem(context) }.getOrNull() else null
        if (dynamic != null) applyPalettes(dynamic[0], dynamic[1], dynamic[2], dynamic[3], dynamic[4], dynamic = true)
        else applyPalettes(SEED_ACCENT1, SEED_ACCENT2, SEED_ACCENT3, SEED_NEUTRAL1, SEED_NEUTRAL2, dynamic = false)
        initialized = true
        return before != (primary xor surfaceContainer xor tertiary xor secondaryContainer)
    }

    private fun readSystem(context: Context): Array<IntArray>? {
        val ids = arrayOf(
            intArrayOf(
                android.R.color.system_accent1_0, android.R.color.system_accent1_10, android.R.color.system_accent1_50,
                android.R.color.system_accent1_100, android.R.color.system_accent1_200, android.R.color.system_accent1_300,
                android.R.color.system_accent1_400, android.R.color.system_accent1_500, android.R.color.system_accent1_600,
                android.R.color.system_accent1_700, android.R.color.system_accent1_800, android.R.color.system_accent1_900,
                android.R.color.system_accent1_1000
            ),
            intArrayOf(
                android.R.color.system_accent2_0, android.R.color.system_accent2_10, android.R.color.system_accent2_50,
                android.R.color.system_accent2_100, android.R.color.system_accent2_200, android.R.color.system_accent2_300,
                android.R.color.system_accent2_400, android.R.color.system_accent2_500, android.R.color.system_accent2_600,
                android.R.color.system_accent2_700, android.R.color.system_accent2_800, android.R.color.system_accent2_900,
                android.R.color.system_accent2_1000
            ),
            intArrayOf(
                android.R.color.system_accent3_0, android.R.color.system_accent3_10, android.R.color.system_accent3_50,
                android.R.color.system_accent3_100, android.R.color.system_accent3_200, android.R.color.system_accent3_300,
                android.R.color.system_accent3_400, android.R.color.system_accent3_500, android.R.color.system_accent3_600,
                android.R.color.system_accent3_700, android.R.color.system_accent3_800, android.R.color.system_accent3_900,
                android.R.color.system_accent3_1000
            ),
            intArrayOf(
                android.R.color.system_neutral1_0, android.R.color.system_neutral1_10, android.R.color.system_neutral1_50,
                android.R.color.system_neutral1_100, android.R.color.system_neutral1_200, android.R.color.system_neutral1_300,
                android.R.color.system_neutral1_400, android.R.color.system_neutral1_500, android.R.color.system_neutral1_600,
                android.R.color.system_neutral1_700, android.R.color.system_neutral1_800, android.R.color.system_neutral1_900,
                android.R.color.system_neutral1_1000
            ),
            intArrayOf(
                android.R.color.system_neutral2_0, android.R.color.system_neutral2_10, android.R.color.system_neutral2_50,
                android.R.color.system_neutral2_100, android.R.color.system_neutral2_200, android.R.color.system_neutral2_300,
                android.R.color.system_neutral2_400, android.R.color.system_neutral2_500, android.R.color.system_neutral2_600,
                android.R.color.system_neutral2_700, android.R.color.system_neutral2_800, android.R.color.system_neutral2_900,
                android.R.color.system_neutral2_1000
            )
        )
        val system = Array(5) { p -> IntArray(13) { s -> context.getColor(ids[p][s]) } }
        // Some ROMs ship the resources but leave them unset (all one value): use the seed instead.
        if (system[0][S200] == system[0][S800] || system[3][S100] == system[3][S900]) return null
        return system
    }

    private fun applyPalettes(a1: IntArray, a2: IntArray, a3: IntArray, n1: IntArray, n2: IntArray, dynamic: Boolean) {
        isDynamic = dynamic
        palettes = arrayOf(a1, a2, a3, n1, n2)
        primary = a1[S200]; onPrimary = a1[S800]; primaryContainer = a1[S700]; onPrimaryContainer = a1[S100]
        inversePrimary = a1[S600]
        secondary = a2[S200]; onSecondary = a2[S800]; secondaryContainer = a2[S700]; onSecondaryContainer = a2[S100]
        tertiary = a3[S200]; onTertiary = a3[S800]; tertiaryContainer = a3[S700]; onTertiaryContainer = a3[S100]
        error = 0xFFF2B8B5.toInt(); onError = 0xFF601410.toInt()
        errorContainer = 0xFF8C1D18.toInt(); onErrorContainer = 0xFFF9DEDC.toInt()

        surface = n1[S900]
        background = blend(n1[S900], n1[S1000], 0.4f)          // ≈ tone 6, "surface darkened"
        surfaceDim = background
        surfaceContainerLowest = blend(n1[S900], n1[S1000], 0.6f) // ≈ tone 4
        surfaceContainerLow = blend(n1[S900], n1[S800], 0.3f)     // ≈ tone 13
        surfaceContainer = blend(n1[S900], n1[S800], 0.7f)        // neutral1_800 at 70 % ≈ tone 17
        surfaceContainerHigh = n1[S800]                            // tone 20
        surfaceContainerHighest = n1[S700]                         // tone 30
        surfaceBright = blend(n1[S800], n1[S700], 0.4f)           // ≈ tone 24
        surfaceVariant = n2[S700]
        onSurface = n1[S100]; onBackground = onSurface
        onSurfaceVariant = n2[S200]
        outline = n2[S400]; outlineVariant = n2[S700]
        inverseSurface = n1[S100]; inverseOnSurface = n1[S800]

        warning = harmonize(0xFFFFB951.toInt(), primary)
        onWarning = harmonize(0xFF452B00.toInt(), primary)
        warningContainer = harmonize(0xFF633F00.toInt(), primary)
        onWarningContainer = harmonize(0xFFFFDDB3.toInt(), primary)
    }

    /** Raw tonal palette value: [palette] 1..3 = accent1..3, 4..5 = neutral1..2; [shade] 0, 10, 50, 100 … 1000. */
    fun tone(palette: Int, shade: Int): Int {
        val index = when (shade) { 0 -> S0; 10 -> S10; 50 -> S50; else -> (shade / 100 + 2).coerceIn(S100, S1000) }
        val source = palettes ?: return primary
        return source[(palette - 1).coerceIn(0, 4)][index]
    }


    /** Charge colour per DESIGN: < 20 % error, < 50 % tertiary, else primary; null = no data (outline). */
    fun batteryColor(percent: Double?): Int = when {
        percent == null -> outline
        percent < 20 -> batteryLow
        percent < 50 -> batteryMid
        else -> batteryHigh
    }

    /** [content] at [fraction] opacity: state layers (0.08 hover, 0.10 pressed), disabled (0.12/0.38). */
    fun state(content: Int, fraction: Float): Int = alpha(content, fraction)

    /** Disabled container (onSurface 12 %). */
    val disabledContainer: Int get() = alpha(onSurface, 0.12f)
    /** Disabled content (onSurface 38 %). */
    val disabledContent: Int get() = alpha(onSurface, 0.38f)
}

// ---------------------------------------------------------------------------------- colour math

/** Linear ARGB interpolation from [from] (t = 0) to [to] (t = 1). Allocation-free. */
fun blend(from: Int, to: Int, t: Float): Int {
    val f = t.coerceIn(0f, 1f)
    fun ch(shift: Int): Int {
        val a = (from ushr shift) and 0xFF; val b = (to ushr shift) and 0xFF
        return (a + (b - a) * f).roundToInt() and 0xFF
    }
    return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

/** [color] with its alpha replaced by [fraction] (0..1). */
fun alpha(color: Int, fraction: Float): Int = (color and 0x00FFFFFF) or ((fraction.coerceIn(0f, 1f) * 255).roundToInt() shl 24)

/**
 * Material "harmonize": rotates [design]'s hue toward [source]'s by half the difference, at most
 * 15°, so fixed semantic colours (amber, green) sit comfortably in a dynamic scheme. HSV approximation.
 */
fun harmonize(design: Int, source: Int): Int {
    val d = FloatArray(3); val s = FloatArray(3)
    Color.colorToHSV(design, d); Color.colorToHSV(source, s)
    if (s[1] < 0.05f) return design // Source has no hue to move toward.
    val diff = ((s[0] - d[0] + 540f) % 360f) - 180f
    val rotation = min(abs(diff) * 0.5f, 15f) * sign(diff)
    d[0] = (d[0] + rotation + 360f) % 360f
    return Color.HSVToColor(Color.alpha(design), d)
}

// ---------------------------------------------------------------------------------- typography

/**
 * One step of the type scale. Sizes are sp; [weight] 100..900 via Typeface.create(DEFAULT, weight,
 * false) (system sans); [trackingSp] becomes letterSpacing. [emphasized] is the M3 Expressive
 * emphasized variant (+200 weight).
 */
class TextStyle(val sizeSp: Float, val lineHeightSp: Float, val weight: Int, val trackingSp: Float = 0f) {
    val typeface: Typeface get() = Fonts.weight(weight)
    val letterSpacing: Float get() = if (sizeSp == 0f) 0f else trackingSp / sizeSp
    val emphasized: TextStyle by lazy { TextStyle(sizeSp, lineHeightSp, min(weight + 200, 900), trackingSp) }
    /** Same metrics at another weight (e.g. 600 for hero numerals). */
    fun withWeight(next: Int): TextStyle = TextStyle(sizeSp, lineHeightSp, next, trackingSp)
}

/** System sans faces per weight, created once. */
object Fonts {
    private val cache = HashMap<Int, Typeface>()
    fun weight(weight: Int): Typeface = cache.getOrPut(weight.coerceIn(100, 900)) { Typeface.create(Typeface.DEFAULT, weight.coerceIn(100, 900), false) }
    val regular: Typeface get() = weight(400)
    val medium: Typeface get() = weight(500)
    val semibold: Typeface get() = weight(600)
    val bold: Typeface get() = weight(700)
}

/** M3 type scale (DESIGN › Typography). Use with [TextView.applyStyle] / [Context.textView]. */
object Typography {
    /** Hero numbers (range, live speed). */
    val displayLarge = TextStyle(57f, 64f, 400, -0.25f)
    val displayMedium = TextStyle(45f, 52f, 400)
    val displaySmall = TextStyle(36f, 44f, 400)
    val headlineLarge = TextStyle(32f, 40f, 400)
    /** Large top app bar title. */
    val headlineMedium = TextStyle(28f, 36f, 400)
    /** Metric tile values. */
    val headlineSmall = TextStyle(24f, 32f, 400)
    /** Small top app bar title, sheet titles. */
    val titleLarge = TextStyle(22f, 28f, 500)
    /** List/card headlines, button labels (medium buttons). */
    val titleMedium = TextStyle(16f, 24f, 500, 0.15f)
    /** Section headers. */
    val titleSmall = TextStyle(14f, 20f, 500, 0.1f)
    val bodyLarge = TextStyle(16f, 24f, 400, 0.5f)
    /** Supporting text. */
    val bodyMedium = TextStyle(14f, 20f, 400, 0.25f)
    val bodySmall = TextStyle(12f, 16f, 400, 0.4f)
    /** Buttons, chips. */
    val labelLarge = TextStyle(14f, 20f, 500, 0.1f)
    /** Navigation bar labels, tile labels. */
    val labelMedium = TextStyle(12f, 16f, 500, 0.5f)
    /** Badges, tiny units ("est."). */
    val labelSmall = TextStyle(11f, 16f, 500, 0.5f)
}

/** Applies [style] (size, line height, weight, tracking). Line height scales with the font scale. */
fun TextView.applyStyle(style: TextStyle) {
    setTextSize(TypedValue.COMPLEX_UNIT_SP, style.sizeSp)
    typeface = style.typeface
    letterSpacing = style.letterSpacing
    includeFontPadding = false
    if (Build.VERSION.SDK_INT >= 34) setLineHeight(TypedValue.COMPLEX_UNIT_SP, style.lineHeightSp)
    else lineHeight = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, style.lineHeightSp, resources.displayMetrics).roundToInt()
}

/** Canvas text in [style] (size in px from sp, face, tracking). Call outside onDraw. */
fun Paint.applyStyle(context: Context, style: TextStyle): Paint {
    textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, style.sizeSp, context.resources.displayMetrics)
    typeface = style.typeface
    letterSpacing = style.letterSpacing
    return this
}

/** Tabular (fixed-width) numerals so changing values do not jitter. */
fun Paint.tabular(): Paint { fontFeatureSettings = "tnum"; return this }

// ---------------------------------------------------------------------------------- shape, spacing, elevation

/** M3 Expressive corner scale in dp ([FULL] = pill: clamp to half the height). */
object Shapes {
    const val NONE = 0f
    const val EXTRA_SMALL = 4f
    const val SMALL = 8f
    const val MEDIUM = 12f
    const val LARGE = 16f
    const val LARGE_INCREASED = 20f
    const val EXTRA_LARGE = 28f
    const val EXTRA_LARGE_INCREASED = 32f
    const val EXTRA_EXTRA_LARGE = 48f
    const val FULL = 1000f

    const val CARD = EXTRA_LARGE
    const val TILE = LARGE_INCREASED
    const val CHIP = SMALL
    const val BUTTON = FULL
    const val FAB = LARGE
    const val LARGE_FAB = EXTRA_LARGE
    const val SHEET = EXTRA_LARGE
    const val SNACKBAR = MEDIUM
    const val NAV_INDICATOR = FULL
    /** Grouped list rows: outer corners of the first/last row, inner corners between rows. */
    const val LIST_OUTER = LARGE_INCREASED
    const val LIST_INNER = EXTRA_SMALL
}

/** 4/8 dp grid (DESIGN › Principles 5). */
object Spacing {
    const val XXS = 2
    const val XS = 4
    const val S = 8
    const val M = 12
    const val L = 16
    const val XL = 24
    const val XXL = 32
    /** Screen side margins. */
    const val MARGIN = 16
    /** Between cards / tiles. */
    const val GAP = 12
    /** Between sections. */
    const val SECTION = 24
    /** Content column cap on tablets / landscape. */
    const val MAX_WIDTH = 640
    /** Minimum touch target. */
    const val TOUCH = 48
}

/** Elevation levels in dp (dark theme expresses elevation mostly through surface containers). */
object Elevation {
    const val LEVEL0 = 0f
    const val LEVEL1 = 1f
    const val LEVEL2 = 3f
    const val LEVEL3 = 6f
    const val LEVEL4 = 8f
    const val LEVEL5 = 12f
}
