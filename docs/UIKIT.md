# Ruta UI kit — API reference

The framework-view implementation of [DESIGN.md](DESIGN.md). Read it before building a screen.

Files (package `dev.codesurgen.ruta`, framework views only, main thread only):
- **Theme.kt** — `Theme` colour roles, `Typography`/`TextStyle`, `Fonts`, `Shapes`, `Spacing`, `Elevation`, `blend/alpha/harmonize`.
- **Icons.kt** — `Icons`, `icon()`, `IconView`, turn-icon mapping.
- **Components.kt** — all M3 Expressive components below.
- **Motion.kt** — `Spring`, `Motion` (easing, durations, `fadeThrough`, `fade`).
- **Host.kt** — `Host` interface + snapshot types (shell ↔ screens).
- **UiKit.kt** — `Screen`, `ScrollPage`, units (`dp`, `dpf`, `spf`), `Fmt`, layout helpers (`column`, `row`,
  `params`, `weighted`, `MATCH`, `WRAP`), `TextView.show/tint/tabular`, `View.visible`, `FlowRow`, `MaxWidthLayout`.
- **Alerts.kt, Parking.kt, Demo.kt** — process-wide state screens read: rider alerts and their switches,
  the parked spot, demo mode (§10). Pure logic beside them: RangeCheck.kt, ParkingRules.kt, DemoData.kt, Range.kt.

Hard rules: no AppCompat/androidx/Material libs; never `Dispatchers.Main` (use `Ui.scope()`/`Ui.dispatcher`);
no R class (icons resolve by name); allocation-free `onDraw`.

---------------------------------------------------------------------------------------------------
## 0. Startup (MainActivity)

```kotlin
override fun onCreate(state: Bundle?) {
    super.onCreate(state)
    Theme.init(this)                       // FIRST, before any view is built (idempotent)
    Icons.onMissing = { appendLog(it) }    // optional: missing icon names → diagnostics log
    ...
}
```
`Theme.reload(context): Boolean` re-reads the wallpaper palettes (returns true if anything changed → rebuild views).
`Theme.isDynamic` = system palettes in use (API 31+); otherwise the seed #8BD450 fallback.

---------------------------------------------------------------------------------------------------
## 1. Colour roles — which role for what (dark scheme only)

| Element | Role |
|---|---|
| Window / screen background, top app bar at rest | `Theme.background` |
| Content cards, tiles, navigation bar, grouped list rows | `Theme.surfaceContainer` |
| Sheets | `Theme.surfaceContainerLow` |
| Chips/stat chips, inactive segments, things *inside* a card | `Theme.surfaceContainerHigh` |
| Switch track off, empty gauge track | `Theme.surfaceContainerHighest` |
| TFT preview glass | `Theme.tftGlass` (= surfaceContainerLowest) |
| Primary text / values | `Theme.onSurface` |
| Secondary text, units, labels, inactive icons | `Theme.onSurfaceVariant` |
| Dividers, outlines of cards/chips | `Theme.outlineVariant` (`Theme.outline` only for switch/inputs) |
| The ONE primary action, selection, live data, speed arc | `Theme.primary` / `onPrimary` |
| Tonal emphasis (secondary buttons, nav indicator, list icon circles) | `secondaryContainer` / `onSecondaryContainer` |
| Hero accents (FAB, live badge, cookie behind speed) | `primaryContainer` / `onPrimaryContainer` |
| Second data series, charging, "mid" battery | `Theme.tertiary` (`tertiaryContainer` for fills) |
| Errors, battery < 20 %, destructive | `Theme.error`, `errorContainer` / `onErrorContainer` |
| Needs attention (not an error): no data, permission missing | `Theme.warning`, `warningContainer` / `onWarningContainer` |
| Snackbar | `inverseSurface` / `inverseOnSurface`, action `inversePrimary` |

Semantic helpers: `Theme.batteryColor(percent)` (≥50 primary, 20–50 tertiary, <20 error, null → outline),
`Theme.batteryHigh/Mid/Low`, `Theme.charging` (tertiary), `Theme.drive` (primary), `Theme.chart1/2/3`
(primary, tertiary, secondary). Disabled: `Theme.disabledContainer` (onSurface 12 %), `Theme.disabledContent` (38 %).
State layers: `Theme.state(color, 0.10f)`. Utilities: `blend(a, b, t)`, `alpha(color, 0.4f)`, `harmonize(fixed, Theme.primary)`.
`Tone` enum (NEUTRAL, PRIMARY, SECONDARY, TERTIARY, ERROR, WARNING) → `.container`, `.onContainer`, `.accent`.

Rules: never hard-code hex colours in screens; never put primary-coloured text on surfaceContainerHigh for
paragraphs (values only); stale/last-known values use `onSurfaceVariant` (not alpha) so contrast holds.

---------------------------------------------------------------------------------------------------
## 2. Typography

`Typography.displayLarge` 57/64 · `displayMedium` 45/52 · `displaySmall` 36/44 · `headlineLarge` 32/40 ·
`headlineMedium` 28/36 · `headlineSmall` 24/32 · `titleLarge` 22/28 w500 · `titleMedium` 16/24 w500 ·
`titleSmall` 14/20 w500 · `bodyLarge` 16/24 · `bodyMedium` 14/20 · `bodySmall` 12/16 · `labelLarge` 14/20 w500 ·
`labelMedium` 12/16 w500 · `labelSmall` 11/16 w500. Each has `.emphasized` (+200 weight) and `.withWeight(n)`.

```kotlin
val range = context.textView("61", Typography.displayLarge.emphasized, Theme.onSurface).tabular()
val unit  = context.singleLine("km", Typography.titleMedium, Theme.onSurfaceVariant)
existingTextView.applyStyle(Typography.bodyMedium)
paint.applyStyle(context, Typography.labelSmall).tabular()   // Canvas text (do it outside onDraw)
Fonts.weight(600)                                             // raw Typeface
```
Per element: hero number Display L (emphasized, tabular); TFT distance Display M; road name Title L; card
headline / list headline Title M or Body L; metric tile value Headline M w500; units Title M onSurfaceVariant;
tile/chip/nav labels Label L/M; section headers `sectionHeader()` (Title S, primary); badges Label S.
ALL changing numbers: `.tabular()` (TextView) / `Paint.tabular()`. Use `TextView.show(text)` and `tint(color)` in
render() (no relayout when unchanged).

---------------------------------------------------------------------------------------------------
## 3. Shape, spacing, elevation

`Shapes` (dp): EXTRA_SMALL 4, SMALL 8, MEDIUM 12, LARGE 16, LARGE_INCREASED 20, EXTRA_LARGE 28,
EXTRA_LARGE_INCREASED 32, EXTRA_EXTRA_LARGE 48, FULL (pill). Semantic: CARD 28, TILE 20, CHIP 8, BUTTON FULL,
FAB 16, LARGE_FAB 28, SHEET 28, SNACKBAR 12, LIST_OUTER 20, LIST_INNER 4.
`Spacing` (dp): XXS 2, XS 4, S 8, M 12, L 16, XL 24, XXL 32, MARGIN 16, GAP 12, SECTION 24, MAX_WIDTH 640, TOUCH 48.
`Elevation` (dp): LEVEL0..5 = 0, 1, 3, 6, 8, 12 (dark theme: prefer surface containers over shadows).

Any rounded shape: `RoundedShapeDrawable(color, radiusPx)` with `setRadii(tl, tr, br, bl)`, `strokeColor/strokeWidth`,
`setInsets(...)`; radii are clamped per corner (FULL works). `rippleOver(content, mask, inkColor)` for ripples.

---------------------------------------------------------------------------------------------------
## 4. Screen conventions

- A screen = `class XScreen(private val host: Host) : Screen` with `override val view` built once in init.
- Root view: `Scaffold(ctx, topBar, page)`; add content with `page.add(view, topDp)`:
  **12 dp** between cards/tiles (default), **24 dp** before a new section (`page.add(sectionHeader("Data"), 24)`).
  ScrollPage gives 16 dp side margins and caps width at 640 dp.
- One hero per screen, one primary action (ExtendedFab via `scaffold.fab = …`, or one filled button).
- No paragraphs on main surfaces: value = icon + number + unit, labels ≤ 2 words, explanations → `view.tooltip("…")`
  and `contentDescription`; transient feedback → `host.showSnackbar(…)`.
- Grouped settings: `ListGroup` of `ListItem`/`SwitchListItem` under `sectionHeader()`.
- render(now): cheap, no I/O, no view creation; use `show/tint/visible`, `setValue`, `setProgress`.
- onHide/dispose: remove listeners, cancel `Ui.scope()` jobs.
- Touch targets ≥ 48 dp (all kit controls already are).

```kotlin
class NavigateScreen(private val host: Host) : Screen {
    private val ctx = host.context
    private val bar = TopAppBar(ctx, large = true).apply { title = "Navigate" }
    private val scaffold = Scaffold(ctx, bar)
    override val view: View = scaffold
    private val turn = IconView(ctx, "navigation", filled = true, tint = Theme.primary, iconSizeDp = 72)
    init {
        scaffold.fab = ExtendedFab(ctx, "map", "Maps") { host.openMaps() }
        scaffold.page.add(filledCard().apply { addView(turn, LinearLayout.LayoutParams(ctx.dp(96), ctx.dp(96))) }, 8)
    }
    override fun render(now: Long) { turn.setTurn(host.navPreview.frame?.turn) }
}
```

---------------------------------------------------------------------------------------------------
## 5. Icons (Material Symbols Rounded, res/drawable)

```kotlin
val d: Drawable = icon(ctx, "bolt", filled = true, tint = Theme.tertiary)   // or ctx.icon("bolt")
val v = IconView(ctx, "ecg_heart", filled = false, tint = Theme.primary, iconSizeDp = 24, description = null)
v.setIcon("pause", filled = true); v.tint = Theme.onPrimary; v.setDescription("Pause")
v.setTurn(NavTurn.LEFT)            // turn_left (filled) + "Turn left" description
Icons.turnName(turn)               // NavTurn code → symbol: turn_left/right, straight, turn_slight_*, turn_sharp_*,
                                   // u_turn_*, roundabout_*, fork_* (also KEEP_*), ramp_*, merge, flag (destination),
                                   // warning (wrong way), directions (ferry), navigation (null)
Icons.turnDescription(turn)
Icons.exists(ctx, "name")
```
Sizes: 24 dp default, `Icons.SMALL` 20 dp (chips, buttons), `Icons.CHIP` 18. Outlined by default, filled for
selected/active (nav selected, playing, connected). IconView without description is decorative (not announced).
Every symbol ships as a drawable (no Canvas-drawn glyphs). An unknown name never crashes: it is logged once
(logcat tag `Ruta` + `Icons.onMissing`) and draws a transparent 24 dp drawable; `bash build.sh` fails on unknown
names written in code (tests/check_icons.py).
Available: home navigation map directions route two_wheeler electric_moped speed insights monitoring timeline
trending_up more_horiz settings tune battery_full battery_charging_full battery_5_bar battery_alert bolt ev_station
thermostat device_thermostat health_and_safety ecg_heart music_note play_arrow pause skip_next skip_previous
volume_up volume_down call call_end phone_in_talk sms chat notifications bluetooth bluetooth_connected
bluetooth_disabled bluetooth_searching link link_off lock lock_open key power_settings_new location_on my_location
flag schedule timer history calendar_today share download delete content_copy bug_report info check_circle error
warning chevron_right chevron_left expand_more expand_less check close arrow_back add remove local_parking
refresh turn_* straight u_turn_* roundabout_* fork_* merge ramp_* moving
trip_origin pin_drop near_me explore avg_pace sports_motorsports shield memory sync visibility contrast palette
cell_tower signal_cellular_alt headphones (all with _fill).

---------------------------------------------------------------------------------------------------
## 6. Components

### Text helpers
```kotlin
ctx.textView("Text", Typography.bodyMedium, Theme.onSurfaceVariant)
ctx.singleLine("Riverside", Typography.titleLarge)             // maxLines 1 + ellipsize
ctx.sectionHeader("Features")                                    // Title S, primary, heading
ctx.divider(insetStartDp = 72); ctx.spacer(24)
view.tooltip("Pack temperature from the BMS")      // never on a child of a clickable row/card: it takes the tap
value.roll("62")                                    // number change: short roll-up crossfade, no hard jump
ctx.emptyState("route", "No rides yet", "Connect and ride", tonalButton("Connect") { … })
```

### Buttons — `ActionButton`
```kotlin
ctx.filledButton("Connect", "electric_moped") { host.connect() }
ctx.tonalButton("Allow") { host.requestPermission(AppPermission.LOCATION) }
ctx.outlinedButton("Copy", "content_copy") { host.copyLog() }
ctx.actionButton("Delete", ButtonVariant.DESTRUCTIVE, "delete", ButtonSize.MEDIUM) { … }
ctx.actionButton("More", ButtonVariant.TEXT) { … }
btn.text = "Cancel"; btn.variant = ButtonVariant.TONAL; btn.setIcon("close"); btn.isEnabled = false; btn.setOnClick { }
```
Variants FILLED (primary), TONAL (secondaryContainer), OUTLINED, TEXT, ELEVATED, DESTRUCTIVE (error).
Sizes SMALL (40 dp visual, Label L) / MEDIUM (56 dp, Title M — sheet/hero CTA). Pill that morphs to a rounded
square while pressed. Width WRAP by default; MATCH works (content centred).

### Icon buttons — `IconActionButton`
```kotlin
IconActionButton(ctx, "share", "Share route") { … }                                  // standard 40 dp in 48 dp
IconActionButton(ctx, "delete", "Delete ride", IconButtonVariant.TONAL) { … }
IconActionButton(ctx, "play_arrow", "Play", IconButtonVariant.FILLED, containerDp = 56) { … }
IconActionButton(ctx, "visibility", "Show", toggleable = true).apply { onCheckedChange = { on -> } }
button.setIcon("pause", "Pause"); button.checked = true
```
Variants STANDARD, FILLED, TONAL, OUTLINED. Description is required (spoken + tooltip).

### Connected button group / segmented — `ButtonGroup`
```kotlin
val range = ButtonGroup(ctx, listOf(GroupItem("24h"), GroupItem("7d"), GroupItem("30d")), selected = 1) { i -> load(i) }
range.select(2)                                   // from code (notify = false)
val controls = ButtonGroup(ctx, listOf(
    GroupItem(icon = "skip_previous", description = "Previous"),
    GroupItem(icon = "play_arrow", description = "Play"),
    GroupItem(icon = "skip_next", description = "Next")), selectable = false, heightDp = 56) { i -> … }
controls.setEmphasized(1); controls.setItem(1, icon = "pause", description = "Pause"); controls.setSegmentEnabled(0, false)
```
Give it a width (MATCH or weight) for equal segments. Selected segment = primary, morphs to a full pill.

### Extended FAB — `ExtendedFab`
```kotlin
scaffold.fab = ExtendedFab(ctx, "electric_moped", "Connect", FabColor.PRIMARY_CONTAINER) { host.connect() }
fab.setExtended(false); fab.setText("Cancel"); fab.setIcon("close"); fab.bindScroll(page) // Scaffold binds it
```
56 dp, 16 dp corners, level-3 shadow, springs between extended and icon-only.

### Cards — `Card`
```kotlin
val card = ctx.filledCard()            // surfaceContainer, 28 dp, 20 dp padding (vertical LinearLayout)
ctx.elevatedCard(16); ctx.outlinedCard()
card.onClick { host.openRide(id) }; card.setContainerColor(Theme.primaryContainer); card.setCornerRadius(Shapes.TILE)
```

### Chips — `Chip`, `StatChip`
```kotlin
ctx.statusChip("Ignition on", "power_settings_new", Tone.PRIMARY)   // non-interactive, 32 dp
ctx.statusChip("Locked", "lock")                                     // NEUTRAL
ctx.assistChip("Maps", "map") { host.openMaps() }                    // outlined, 48 dp target
ctx.filterChip("Pack F", checked = true) { on -> }
chip.setText("Unlocked"); chip.setIcon("lock_open"); chip.tone = Tone.WARNING; chip.checked = false
val stat = StatChip(ctx, "battery_5_bar", "61", "km", description = "Range")  // 8 dp chip: icon + tabular value + unit
stat.setValue("58", "km"); stat.setNote("est.", spoken = "estimated"); stat.setTone(Tone.TERTIARY)
```
Lay chips out with `FlowRow(ctx, ctx.dp(8))` (wraps).

### Spec strip — `SpecStrip` (preferred for summaries: Home battery, Insights)
```kotlin
val specs = SpecStrip(ctx)                       // container-less equal columns, no boxes
val health = specs.add("ecg_heart", "Health", "Battery health")   // icon, label, spoken description
health.setValue("99", "%"); health.stale = !battery.fresh; health.setTone(Tone.WARNING)   // colour only on meaning
specs.loading = true                             // pulsing skeleton blocks until the first data
specs.onClick { … }                              // whole strip = one target (ripple + 0.97 press)
```

### Metric tile + grid — `MetricTile`, `EqualGrid`
```kotlin
val grid = EqualGrid(ctx, columns = 2)          // 12 dp gaps, equal row heights
val health = MetricTile(ctx, "ecg_heart", "Health", "Battery health (SOH)")
grid.addView(health)
health.setValue("99", "%"); health.stale = !battery.fresh; health.setTone(Tone.WARNING); health.onClick { … }
```
Tiles sit on the page background (surfaceContainer, 20 dp), icon onSurfaceVariant unless a tone means
something. Value Headline M w500 tabular (rolls on change); label Label L. Use for detail grids (ride
receipt), not for a screen's summary (DESIGN › Craft: no equal-weight grids of identical cards).

`Card.onClick`, `MetricTile.onClick`, `SpecStrip.onClick` and clickable `ListItem`s spring to 0.97 while
pressed (`PressScale`). `InlineStat(..., tooltip = false)` inside clickable rows.

### Lists — `ListItem`, `SwitchListItem`, `ListGroup`
```kotlin
val group = ListGroup(ctx)                                     // rows 2 dp apart, 20 dp outer / 4 dp inner corners
group.addView(ListItem(ctx, "Scooter", "HMC_000000", "electric_moped",
    trailing = IconView(ctx, "chevron_right")) { host.showSheet(picker, "Scooter") })
val music = SwitchListItem(ctx, "Music", icon = "music_note", checked = host.isEnabled(Feature.MUSIC)) { on ->
    host.setEnabled(Feature.MUSIC, on) }
group.addView(music)
row.setSupporting("Needed for rides", Theme.warning); row.setLeadingTone(Tone.WARNING)
row.setTrailing(ctx.tonalButton("Allow") { … }); row.setTrailingText("1.0.0"); row.setIcon("check_circle", true)
music.checked = host.isEnabled(Feature.MUSIC)                  // in render(): does not fire the callback
```
Leading icon = 40 dp tonal circle (secondaryContainer). Headline Body L, supporting Body M (≤ 3 lines).

### Switch — `MaterialSwitch`
```kotlin
val sw = MaterialSwitch(ctx).apply { setChecked(true, animate = false); onCheckedChange = { on -> } }
```
52×32 track, check/close icon in the thumb, spring motion, announced as a Switch.

### Progress — `WavyLinearProgress`, `WavyCircularProgress`, `LoadingIndicator`
```kotlin
val bar = WavyLinearProgress(ctx); bar.setProgress(0.57f); bar.color = Theme.batteryColor(57.0); bar.wavy = false
val ring = WavyCircularProgress(ctx, sizeDp = 48).apply { indeterminate = true }
val loading = LoadingIndicator(ctx, contained = true, sizeDp = 48)     // shape-morph (connecting)
```
They animate only while shown and stop with animations off.

### Expressive shapes
```kotlin
val cookie = CookieDrawable(Theme.primaryContainer)        // 9-point scalloped badge (behind live speed)
badgeView.background = cookie; cookie.spinOnce()           // short settle turn, then no more frames
// cookie.start() turns endlessly (redraws at display rate): avoid on long-lived screens
ExpressiveShapes.cookie(path, cx, cy, radius, points = 9, depth = 0.1f)   // in onSizeChanged, not onDraw
ExpressiveShapes.pillBlob(path, rect, wobble, phase)
PillBlobDrawable(color).start()                             // breathing pill
```

### Brand mark — `BrandMark`
```kotlin
val mark = BrandMark(ctx)              // 72 dp: electric_moped_fill on the lime #B7F36B 9-point cookie badge
BrandMark(ctx, sizeDp = 40)            // About row leading mark
```
`class BrandMark(context: Context, sizeDp: Int = 72) : View` — the launcher icon's mark (fixed brand colours,
not dynamic). Use it wherever the app shows its own identity (onboarding Welcome, More › About); never redraw
the mark or use a gauge.

### Explanation sheet — `Host.explain` + `ExplainSheet` ("tap explains, never surprise-jumps")
```kotlin
health.onClick {
    host.explain(ExplainSheet(
        icon = "ecg_heart", title = "Battery health", value = "99", unit = "%",
        meaning = "Capacity left compared with a new pack.",
        rows = listOf("Front" to "99 %", "Rear" to "98 %"),
        spark = last24h,                                   // List<Double>, oldest first; empty hides it
        historyLabel = "History", onHistory = { host.selectTab(Destination.INSIGHTS) }))
}
```
`fun Host.explain(sheet: ExplainSheet)` and `data class ExplainSheet(val icon: String, val title: String,
val value: String, val unit: String? = null, val meaning: String, val rows: List<Pair<String, String>> = emptyList(),
val spark: List<Double> = emptyList(), val historyLabel: String? = null, val onHistory: (() -> Unit)? = null)`.
The standard bottom sheet for every tappable metric: icon + name + big value, one or two plain sentences, per-pack
rows, a 24 h sparkline, and an optional secondary "History" text button — the only element that may change tab.
A metric tap never navigates by itself. Two optional extras follow the contract fields: `note: String? = null`
(e.g. "est." next to the value) and `rowNotes: Map<String, String> = emptyMap()` (one-line meaning under a row,
keyed by its label). `fun Host.showExplain(sheet: ExplainSheet): BottomSheet?` is the same call returning the
sheet (one explain sheet at a time: an open one is replaced).

Two optional slots, last in the constructor: `detail: View? = null` goes right under the meaning (16 dp
above it, before the rows) and `actions: View? = null` at the very end (24 dp above it, full width). Pass freshly
built views. Home's parked sheet uses both:
```kotlin
host.showExplain(ExplainSheet(icon = "pin_drop", title = "Parked", value = "18:42", note = "today",
    meaning = "Where your phone was when you left the scooter. …",
    rows = listOf("Parked for" to "12 min", "Accuracy" to "± 8 m"),
    detail = ctx.column().apply { addView(ParkedMap(ctx), …); addView(placeText, …) },  // mini-map + place
    actions = ctx.row().apply { addView(outlinedButton("Share", "share") { … }, weighted()); addView(walk, …) }))
```
Use `actions` only for buttons that act on what the sheet explains (Share, Walk). A single way out of the sheet
stays the `historyLabel` / `onHistory` text button (e.g. Navigate's "Find chargers").

### Spec grid — `SpecGrid` (Home 2×2 metrics, container-less)
```kotlin
val metrics = SpecGrid(ctx)                                  // columns = 2
page.addView(metrics, metrics.alignedParams())               // −12 dp sides: text lines up with the 16 dp margin
val health = metrics.add("ecg_heart", "Battery health")     // (icon, label, spoken description = label)
health.setValue("99", "%"); health.setNote("est.", "estimated"); health.setTone(Tone.ERROR)
health.stale = true; health.loading = true                   // dimmed last-known / pulsing skeleton
health.onClick { host.explain(…) }                           // returns the Cell; double taps dropped
```
`class SpecGrid(context: Context, val columns: Int = 2) : LinearLayout`; `Cell` also has `setLabel`, `setIcon`.

### Trend line — `TrendLine`
`class TrendLine(context: Context) : View` — `setValues(List<Double>)` (oldest first), `setSurface(color)`. The
explain sheet's 24 h sparkline; usable on its own.

### One tap, one action — `ClickGuard`, `setOnSingleClick`
`object ClickGuard { const val WINDOW_MS = 400L; fun accept(view: View): Boolean }` and
`fun View.setOnSingleClick(action: () -> Unit)`: a second tap on the same view within 400 ms is dropped. Every kit
button, FAB, card, chip, list row and grid cell already uses it; segmented and media controls do not (rapid taps
there are deliberate). Use `setOnSingleClick` for any custom view that opens or changes something.

### Touch gate — `TouchGate` (Motion.kt)
`open class TouchGate(context: Context) : FrameLayout` — `val touchable`, `fun close()` (cancels the gesture in
progress; touches fall through), `fun open(swallowMs: Long = 0L)`. The shell wraps each tab and the onboarding
overlay in one; `Motion.fadeThrough` closes the outgoing gate at once and opens the incoming one on top.

### Instrument cluster parts
`enum class ClusterPart { BATTERY, FRONT, REAR }`; `InstrumentCluster.onPartClick: ((ClusterPart) -> Unit)?` — the
dial (Battery sheet) and the F / R pack labels (Front / Rear pack sheets).

### Scooter display setup — `DisplaySetup` (NavigateScreen.kt)
`object DisplaySetup { const val TITLE; class Step(icon, text, note?); val STEPS; fun isDone(context); fun
markDone(context); fun stepsView(context): View }` — the four numbered steps shared by Navigate's setup sheet and
onboarding's optional "Directions on your scooter" page. Done is saved once per phone (prefs `companion`,
`display_setup_done`).

### Chart and stat helpers (Charts.kt)
`StatFlow(context, hGapDp, vGapDp, maxLines: Int = Int.MAX_VALUE)` — with `maxLines = 1` items that do not fit are
left out, never wrapped (ride list stats). `TimeChart.yStep: Double?` (fixed grid step, e.g. 5 for health),
`LineChart.minSpacingDp` (points closer than this merge; short runs draw as dots).

### Connection chip (top bar trailing) — `ConnectionChip`
```kotlin
val chip = ConnectionChip(ctx) { host.showSheet(scooterSheet(), "Scooter") }
bar.trailing.addView(chip)
// render():
val link = host.link
chip.update(link.phase, when { link.phase == LinkPhase.CONNECTING -> "Connecting"; link.live -> "Live";
    link.phase == LinkPhase.READY -> "No data"; else -> "Offline" }, attention = link.silentSince > 0 || link.error != null)
```
READY → bluetooth_connected on primaryContainer; CONNECTING → shape-morph loader + breathing blob; IDLE → bluetooth_disabled.

### Top app bar — `TopAppBar`
```kotlin
val bar = TopAppBar(ctx)                                   // small: title Title L + subtitle Body M
bar.title = host.scooterName; bar.subtitle = "HMC_000000"; bar.trailing.addView(chip)
val big = TopAppBar(ctx, large = true).apply { title = "Rides"; subtitle = "This week" }  // header in content
bar.setNavigation("arrow_back", "Back") { closeDetail() }   // null hides it
bar.addAction("delete", "Delete ride") { … }
bar.bindScroll(page)                                       // Scaffold does this
```
Pads itself for the status bar (needs window insets to reach it, see §8). Tints to surfaceContainer when
content scrolls under it; large bars fade the small title in once `bar.header` scrolls away.
Every bar carries a `DemoChip` between the titles and `trailing`, shown (fade) only while `Demo.active`;
screens add nothing for it.

### Demo chip — `DemoChip` (Components.kt, added by `TopAppBar`)
`class DemoChip(context: Context) : LinearLayout` — filled tertiary-container chip "Demo" (32 dp, 8 dp corners,
Label L, 48 dp touch target, ripple + press scale). Tertiary because it is neither an action nor a fault: it says
the values are simulated. Tap → `Demo.onChip` (the shell's demo sheet: six scenarios, the current one checked,
"Exit demo"). TalkBack: "Demo mode. Change scenario or exit". Never build one by hand.

### Range check chip — `RangeChip` (NavigateScreen.kt, private)
Under the Navigate preview card (not inside it: the scooter display does not show it), only while a route is
active and the charge reading is live (or in demo). Battery icon + `RangeVerdict.label`: "12 km spare" (surface
container high, primary icon), "Tight · 3 km spare" (tertiary container), "Short by 4 km" (error container,
`battery_alert`). Standard chip metrics (32 dp in a 48 dp target, ripple, 0.97 press); a level change blends the
colours with a no-bounce spring and rolls the label. Tap → the "Range check" explain sheet (spare/short, Range,
Route left, Reserve with row notes; "Find chargers" = Maps search "EV charging station").

### Parked row and mini-map — `ParkedMap` (HomeScreen.kt, private)
A one-row `ListGroup` under the hero while the scooter is not connected and not riding (and "Remember where I
park" is on): `ListItem("Riverside Rd", "Parked · 12 min ago", "pin_drop", tonalButton("Walk"))`. The Walk button
opens Google Maps walking directions (`Parking.walkUrl`); the row explains (sheet above). `ParkedMap` draws no
tiles and needs no network: 20 m dot grid, accuracy circle at true scale (60 m centre-to-edge), `location_on` pin
whose tip marks the spot, scale bar; decorative for TalkBack; `onDraw` allocates nothing.

### Scaffold
```kotlin
val scaffold = Scaffold(ctx, topBar = bar)                 // page = ScrollPage, header inserted for large bars
scaffold.page.add(card)            // 12 dp gap
scaffold.fab = ExtendedFab(…)      // bottom-end, collapses on scroll, page gets bottom space
```

### Navigation bar — `NavigationBar` (shell)
```kotlin
val nav = NavigationBar(ctx, Destination.entries.map { NavItem(it.label, it.icon) }, selected = 0) { i -> showTab(i) }
nav.onReselect = { i -> (screens[i]?.view as? Scaffold)?.page?.scrollToTop() }
nav.select(2)                      // from code; nav.select(2, notify = true) also calls onSelect
nav.setBadge(4, 0)                 // dot; nav.setBadge(1, 3) count; null clears
nav.applyBottomInset(bars.bottom)
```
`select` is idempotent (the shown tab: no-op); a re-tap within 400 ms of selecting is ignored. Each item's touch
target is exactly its column; on wide windows columns cap at `NavigationBar.MAX_ITEM_DP` (120) and centre, so
empty bar space selects nothing.

### Snackbar — `SnackbarHost` (shell)
```kotlin
val snackbars = SnackbarHost(ctx)           // add over the content area, MATCH×MATCH, last child (touches pass through)
snackbars.bottomOffset = nav.height         // keep above the navigation bar (if the host overlays the nav bar too)
snackbars.anchor = { screen.view.findShownFab() }   // M3: above a shown FAB, re-checked every frame
snackbars.show("Log copied")                 // 4 s
snackbars.show("Ride deleted", "Undo") { undo() }   // 8 s with action
snackbars.dismiss()
```

### Bottom sheet — `BottomSheet`
```kotlin
val sheet = BottomSheet(activity)            // own dialog window; scrim, handle, 28 dp corners, spring in/out
sheet.setTitle("Scooter"); sheet.setContent(group); sheet.onDismiss = { }; sheet.show(); sheet.dismiss()
sheet.setPaneTitle("Battery")                // TalkBack pane title
host.showSheet(group, "Permissions")         // preferred from screens (returns the BottomSheet)
```
Drag down / tap handle / tap scrim / back to dismiss (`cancelable = false` disables). Tall content scrolls.
Scrim and handle taps are ignored for `BottomSheet.SETTLE_MS` (350 ms) after `show()`, and nothing inside reacts
while the sheet closes, so a double tap never opens and closes a sheet at once.

---------------------------------------------------------------------------------------------------
## 7. Motion

```kotlin
val arc = Spring(initial = 0f) { v -> sweep = v; invalidate() }   // stiffness 400, damping 0.8 (DESIGN)
arc.animateTo(0.61f)                 // retarget anytime; keeps momentum; settles and stops by itself
arc.snapTo(0f); arc.cancel(); arc.onEnd = { }; arc.animateTo(target, startVelocity)
Spring(0f, Motion.STIFFNESS_FAST, Motion.DAMPING_FAST) { … }      // press feedback
Spring(0f, dampingRatio = Motion.DAMPING_NO_BOUNCE, threshold = 0.5f) { px -> … }   // edge-anchored, pixels
Motion.fadeThrough(oldScreen.view, newScreen.view)               // tab switch: 90 ms out, 210 ms in
Motion.fade(view, show = true)
Motion.enabled()                      // false = animator duration scale 0: springs/transitions jump to the end
Motion.emphasized / emphasizedDecelerate / emphasizedAccelerate / standard; Motion.SHORT1…LONG4 durations
```
Gauge arcs and every changing value that moves something: animate with a Spring (never jump). Threshold in
the value's units (0.001 for fractions, 0.5 for px, 0.05 for km/h). Springs are Choreographer-driven, main thread.

---------------------------------------------------------------------------------------------------
## 8. Shell (MainActivity) wiring checklist

1. `Theme.init(this)` first; `window` edge-to-edge (decorFitsSystemWindows false, transparent bars, light bar
   appearance off, `isNavigationBarContrastEnforced = false`), window background `Theme.background`.
2. Root: `FrameLayout` { column { content FrameLayout (weight 1) ; NavigationBar } ; SnackbarHost (MATCH) }.
3. Insets listener on the root: pad root left/right only; `nav.applyBottomInset(bars.bottom)`;
   `snackbars.bottomOffset = nav.height` (post after layout); **return `insets`, not CONSUMED**, so each
   screen's `TopAppBar` receives the status-bar inset.
4. Screens: create lazily, add `screen.view` to content (GONE), switch with `Motion.fadeThrough(old, new)`,
   call onHide/onShow/render as before (`Screen` contract unchanged).
5. Implement `Host` (override what exists; every member except `activity` has a default).

---------------------------------------------------------------------------------------------------
## 9. Host (Host.kt) — what screens can read / call

State: `activity`, `context`, `store`, `versionName`, `currentTab`, `link: LinkStatus` (phase, reconnecting,
live, silentSince, deviceName, address, error, connectedAt), `linkPhase`, `reconnecting`,
`reading(pack, metric)`, `battery: BatterySnapshot` (total/pack1/pack2 PackSnapshot{soc, health, energy,
temperatureC, status, updatedAt}, fresh, updatedAt, imbalance, hasData), `vehicle: VehicleState?`, `tcuInfo`,
`range: RangeEstimate?` (km, estimated, kmPerPercent, learned — show kmPerPercent as Efficiency, "est." until learned), `driveState: DriveState` (label + icon), `ride: RideState`,
`speedKmh`, `lastRide`, `navPreview: NavPreview` (frame, instruction, road, thenTurn, display, enabled, testRunning, error,
listenerConnected, updatedAt, hasRoute), `phone` (PhoneBridge), `mediaState: MediaState?` and
`callState: CallState` (what to *show*: the phone's media session / call, or the demo's; read these, not
`phone.media` / `phone.call`), `musicFeed`, `callFeed`, `scooters`, `selectedScooter`, `bluetoothEnabled`,
`permission(AppPermission): PermissionState`, `isEnabled(Feature)`, `lastHandlebar`, `logLines`,
`displayTestRunning`, `demoActive`.
Actions: `selectTab(Destination)`, `requestRender()`, `showSnackbar(text, actionLabel?, action?)`,
`showSheet(view, title?)`, `openRide(id)` (back from that detail returns to the tab it came from),
`subPageClosed()` (the screen left that sub-page by itself — top-bar arrow, delete, another row — so back
then behaves as on the tab's top level), `openMaps()`, `media(MediaAction)`, `call(CallAction)`,
`selectScooter(address)`, `connect(address?)`, `cancelConnect()`, `disconnect()`, `toggleConnection()`,
`forgetScooter()`, `refreshScooters()`, `openBluetoothSettings()`, `requestPermission(AppPermission)`,
`openAppSettings()`, `openLocationSettings()`, `setEnabled(Feature, on)`, `addLogListener/removeLogListener`,
`log(line)`, `copyLog()`, `shareLogs()`, `probeScooter()`, `startDisplayTest()`, `startRoadNameTest()`,
`resetUnits()`, `exportCsv()`, `setDemo(DemoScenario?)` (start/switch, null ends; refused with a snackbar
while the scooter is linked), `showDemoSheet()` (what the Demo chip opens).
Enums: `Destination` (label, icon), `AppPermission` (label, icon), `PermissionState` (GRANTED, PARTIAL, DENIED,
BLOCKED → button opens settings, NOT_NEEDED), `Feature` (DIRECTIONS, MUSIC, CALLS, HANDLEBAR, AUTO_CONNECT),
`DriveState` (UNKNOWN, STANDBY "local_parking", DRIVE "electric_moped", CHARGING "bolt"; `DriveState.of(status, charging)`).

---------------------------------------------------------------------------------------------------
## 10. Rider features and demo mode — what screens use

```kotlin
// Alerts.kt — rider alerts ("Ride alerts" channel, IMPORTANCE_DEFAULT) and their More › Alerts switches
enum class AlertKind { RANGE, CHARGE_TARGET, CHARGE_STOPPED, PARKING, MESSAGE_SENDER }
enum class AlertAccess { ON, ASK, SETTINGS }
Alerts.enabled(ctx, kind); Alerts.setEnabled(ctx, kind, on)        // defaults: all on except CHARGE_STOPPED and MESSAGE_SENDER (writes to the scooter display, posts nothing)
Alerts.chargeTarget(ctx) /* 80|90|100 */; Alerts.setChargeTarget(ctx, percent); Alerts.TARGETS
Alerts.rangeWarning(ctx, verdict); Alerts.chargeReached(ctx, percent, rangeKm, estimated = true)
Alerts.chargeStopped(ctx, percent); Alerts.clear(ctx, kind)
Alerts.access(ctx): AlertAccess; Alerts.settingsIntent(ctx); Alerts.ensureChannel(ctx)
Alerts.inApp: ((String) -> Unit)?      // the shell shows each alert as a snackbar while visible

// Parking.kt (+ ParkingRules.kt, pure) — the one remembered parked spot
data class ParkedSpot(val lat: Double, val lng: Double, val accuracyM: Double, val time: Long, val place: String?)
Parking.spot(ctx)            // what to show: Demo.parkedSpot while the demo runs, else the saved one
Parking.stored(ctx)          // the saved one (first call reads prefs: call it off the main thread once)
Parking.save(ctx, spot): Boolean; Parking.setPlace(ctx, time, place); Parking.forget(ctx)
Parking.addListener { }; Parking.removeListener(l)   // main thread
Parking.walkUrl(spot)        // Google Maps walking directions

// RangeCheck.kt (pure) — "can I make it?"
enum class RangeLevel { OK, TIGHT, SHORT }
RangeCheck.evaluate(remainingKm, range, previous): RangeVerdict?   // reserve 5 km, ±1 km hysteresis
verdict.label /* "12 km spare" | "Tight · 3 km spare" | "Short by 4 km" */; spareWholeKm; shortWholeKm
NavBridge.rangeVerdict       // the trip's verdict while Maps navigates (the chip and the notification agree)

// Demo.kt (+ DemoData.kt, pure) — demo mode
enum class DemoScenario(key, label, icon, detail) { PARKED, RIDING, NAVIGATING, CHARGING, CALL, MUSIC }
Demo.active; Demo.scenario; Demo.parkedSpot; Demo.addListener { }; Demo.removeListener(l)   // main thread
host.demoActive; host.setDemo(scenario /* null = exit */); host.showDemoSheet()
```

Rules:
- **Demo isolation.** While `Demo.active`, every Host value (link, battery, vehicle, range, ride, navPreview,
  mediaState, callState, musicFeed, callFeed, store, savedScooters) is simulated by the shell — screens need no
  demo branches as long as they read Host. Never call `PhoneBridge`, `NavBridge` or `TelemetryStore.shared`
  directly for something you show; never start Bluetooth, RideService or a notification without a Demo guard
  (`Alerts`, `Parking.save` and the range warning already check it). Scooter actions answer with "Demo mode: no
  real scooter · Exit demo".
- **Alerts.** Post through `Alerts` only (never `NotificationManager` directly) so the switch, the demo guard,
  the permission check and the in-app snackbar apply. Wording lives in `AlertText` (Range.kt, host-tested).
- **Parked spot.** Shown only while the scooter is not connected and not riding, and only with "Remember where I
  park" on. Never log coordinates or place names.
