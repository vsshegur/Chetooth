# Ruta — design and product spec

The design brief every screen is built against. The UI kit that implements it is documented in
[UIKIT.md](UIKIT.md).

**Product bar:** a calm, confident, glanceable app that feels first-party. Numbers and icons instead
of vocabulary; a speedometer-like cluster; range always visible.

## Principles
1. **Numbers and icons, not sentences.** Every value = icon + number + unit. Labels ≤ 2 words, sentence case. No
   explanatory paragraphs on main surfaces. Explanations go into explanation sheets, long-press tooltips
   (`tooltipText`) and `contentDescription`. Transient feedback = snackbar, not status prose. Never show raw codes
   (status 0/2/4 → "Standby"/"Drive"), hex or SDK words outside Diagnostics.
2. **One hero per screen**, one primary action (extended FAB or filled button). Everything else secondary.
3. **Glanceable on a scooter mount**: big tabular numerals, high contrast, 48dp+ targets.
4. **Material 3 Expressive** on every screen: vibrant dynamic colour, expressive shapes, emphasized type, springy
   motion. No plain framework-default widgets left visible.
5. **Consistency**: 4/8dp grid; 16dp screen margins; 12dp gaps between cards; 24dp section spacing.

## Craft
Calm, precise, typographic, warm, obsessively detailed — on top of Material 3 Expressive:
1. **Restraint.** Mostly neutral surfaces; colour only carries meaning (state, battery level, primary action). One
   accent at a time per screen. No gradients everywhere, no glows, no emoji, no ALL-CAPS labels, no badge/pill spam.
2. **Typography does the hierarchy**, not boxes. Fewer cards, more whitespace; never card-in-card; no borders on
   everything. Numbers are big and tabular; units are smaller, lighter and in onSurfaceVariant ("61" + "km").
3. **Precision.** Optical alignment, one radius per component family, 4/8 grid, baseline-aligned value+unit pairs,
   icons at consistent optical size, consistent icon-to-text gaps (8dp in rows, 6dp in chips).
4. **Micro-interactions that feel physical.** Press = spring scale to 0.97 + subtle ripple; haptics on primary
   actions, toggles and segmented changes (View.performHapticFeedback with CONFIRM / CLOCK_TICK / REJECT, guarded
   by API level); numbers change with a short rolling/crossfade transition, never a hard jump; lists enter with a
   20 ms stagger; skeleton placeholders instead of spinners for loading data.
5. **One signature moment:** on connect, the instrument cluster does a short "ignition sweep" (arcs sweep to full and
   settle on the real values, ~900 ms, skipped when animations are off). Subtle, not a show.
6. **Microcopy** is short, human, sentence case, no jargon ("Scooter connected", "Couldn't reach the scooter",
   "Scooter didn't accept this PIN"). Empty states = one icon + one short line + at most one action.
7. **Avoid generic-app tells**: equal-weight grids of identical cards, redundant icon+text everywhere, centred
   paragraphs, explanatory text under every control, inconsistent radii, random accent colours.

## Built for everyone
- Nothing is specific to one scooter: no hardcoded MAC, BLE name, VIN, IMEI, nickname or PIN anywhere in code,
  tests, resources or docs. Scooter name = user-chosen nickname (default "My scooter"), subtitle = BLE name. Tests
  use synthetic identifiers (e.g. HMC_000000, 00:11:22:33:44:55, VIN MBLTEST…).
- Multiple saved scooters (pick the active one); everything per scooter is keyed by MAC.
- Trademarks are used only descriptively ("for Hero Vida scooters"; About shows "Not affiliated with Hero MotoCorp
  or Vida."). No Hero/Vida logos or assets. Protocol notes are our own documentation ([PROTOCOL.md](PROTOCOL.md)).
- build.sh/test.sh do not depend on one machine's paths (env overrides with sane defaults, clear error messages);
  the signing key comes from RUTA_* variables and is never committed.

## Brand: Ruta
- **Ruta** everywhere user-facing: launcher label, notification titles ("Ruta connected"), notification-access label
  "Ruta directions & music", onboarding Welcome, About, licences, file names (`ruta-*.csv`, `logs/ruta-yyyyMMdd.log`,
  `dist/ruta-<version>.apk`), logcat tag `Ruta`, theme `Theme.Ruta`.
- Application ID and Kotlin package `dev.codesurgen.ruta`; listener component
  `dev.codesurgen.ruta.MapsNavigationListener`.
- **Mark** ("cookie badge"): Material Symbols `electric_moped_fill` in ink `#0E1A12` on a lime `#B7F36B` 9-point
  cookie badge. Launcher = that mark on a `#0E1A12` plate (adaptive icon; themed-icon silhouette =
  `ic_launcher_monochrome`; PNG fallbacks in `mipmap-*`); notification icon = the bare glyph (`ic_notification`);
  splash = `ic_launcher_foreground` on the dynamic surface. In-app (onboarding Welcome, About row) use `BrandMark`
  (UIKIT §6), never a redrawn gauge. Brand colours are fixed (not dynamic) and used only for the mark.

## Colour — dynamic (Material You), dark scheme
Read the system tonal palettes (API 31+): `android.R.color.system_accent1_0..1000`, `system_accent2_*`,
`system_accent3_*`, `system_neutral1_*`, `system_neutral2_*`. Map to M3 dark roles:
- primary=accent1_200, onPrimary=accent1_800, primaryContainer=accent1_700, onPrimaryContainer=accent1_100
- secondary=accent2_200, onSecondary=accent2_800, secondaryContainer=accent2_700, onSecondaryContainer=accent2_100
- tertiary=accent3_200, onTertiary=accent3_800, tertiaryContainer=accent3_700, onTertiaryContainer=accent3_100
- surface/background=neutral1_900 (darken 4% for background), surfaceContainerLowest=neutral1_1000 blend,
  surfaceContainerLow≈neutral1_900→800 mix, surfaceContainer≈neutral1_800 at 70%, surfaceContainerHigh=neutral1_800,
  surfaceContainerHighest=neutral1_700; onSurface=neutral1_100, onSurfaceVariant=neutral2_200, outline=neutral2_400,
  outlineVariant=neutral2_700, inverseSurface=neutral1_100.
- error=#F2B8B5, onError=#601410, errorContainer=#8C1D18.
- Semantic battery colours harmonised to the scheme: high = primary, mid = tertiary, low (<20 %) = error;
  charging = tertiary; drive = primary.
API 30 fallback (no system palettes): tones from seed #8BD450 (electric lime) with a simple precomputed tone ramp.
Status/nav bars transparent, edge-to-edge, light icons off.

## Shape (M3 Expressive scale)
extraSmall 4, small 8, medium 12, large 16, largeIncreased 20, extraLarge 28, extraLargeIncreased 32,
extraExtraLarge 48, full (pill). Cards 28; tiles 20; chips 8; buttons full; FAB 16 (large FAB 28); nav indicator full.
One or two **expressive shapes** for delight (Canvas path): a 9-point "cookie"/scalloped badge behind the live
speed, and a "pill-blob" for the connection indicator. Shape-morph loading indicator while connecting.

## Typography
System sans, weights via `Typeface.create(…, weight, false)`; tabular numerals (`fontFeatureSettings="tnum"`).
Display L 57/64 (hero numbers), Display M 45/52, Display S 36/44, Headline L 32/40, Headline M 28/36, Headline S 24/32,
Title L 22/28 (w500), Title M 16/24 (w500), Title S 14/20 (w500), Body L 16/24, Body M 14/20, Body S 12/16,
Label L 14/20 (w500), Label M 12/16 (w500), Label S 11/16 (w500). Emphasized variants: +100–200 weight.

## Iconography
Every symbol the app shows ships as a Material Symbols Rounded vector drawable in res/drawable: `ic_<name>.xml`
(outlined) and `ic_<name>_fill.xml` (filled). Load with `resources.getIdentifier("ic_name", "drawable",
packageName)` and tint with colour roles. Outlined by default; filled for selected/active states (nav bar selected,
playing, connected). Sizes 24dp (20dp in chips). No hand-drawn glyphs; an unknown name draws nothing and is logged
once. The full list of names is in UIKIT §5.

## Components (framework views in the UI kit; no AppCompat/Material libraries)
NavigationBar (80dp, pill active indicator 64×32 with spring, filled icon when selected, label under), large/flexible
top app bar (title + trailing connection chip), cards (filled/elevated/outlined on surfaceContainer roles), buttons
(filled, tonal, outlined, text, icon buttons standard/filled/tonal), connected button groups / segmented control,
extended FAB, chips (assist/filter with leading icon), M3 switch (icon in thumb), list items with leading icon
container (40dp tonal circle), wavy linear/circular progress, shape-morph loading indicator, snackbar, modal bottom
sheet, tooltips on long-press.

## Motion
Spring (stiffness ~400, damping 0.8) for nav indicator, toggles, gauge arcs (animate values, never jump), FAB
expand/collapse. Fade-through (90 ms out / 210 ms in, emphasized decelerate) between tabs. Respect
Settings.Global.ANIMATOR_DURATION_SCALE = 0.

## Tap explains, never surprise-jumps
- Tapping any Home metric (each tile, the cluster, F/R pack labels, state chips) opens an explanation sheet
  (`host.explain(ExplainSheet(…))`): icon + name + big current value, one or two plain sentences, per-pack values
  where relevant, a 24 h sparkline, and a secondary text button **History** — the only way to Insights. The
  cluster's sheet is "Battery": charge per pack, energy ("usable energy the scooter reports; usually a little below
  charge"), balance (front-vs-rear gap), range method (est.).
- Only elements with an explicit affordance (chevron row, button naming the destination, FAB) change tab or screen.

## Screens
### Onboarding (first run, or More › Scooters › Add scooter)
Welcome (brand mark, "Ruta", one line, Continue, "Explore demo") → Permissions (one screen: Nearby devices, Location,
Notifications rows with grant buttons; Continue enabled once Nearby devices is granted) → Find scooter (BLE scan
filtered to Vida TCUs: names starting "HMC_" and/or the TCU info service UUID; already-bonded scooters first with a
check; signal bars; empty/scanning states; "Enter address" fallback) → PIN (only if the scooter is not bonded yet:
large numeric PIN field 4–8 digits, show/hide toggle, optional nickname, help: "Your PIN is on the official Vida
app's home screen (“USE PIN”) while Bluetooth is disconnected.") → Connecting (shape-morph loader) → Scooter display
setup (optional; primary "Done", secondary "Later") → Done (ignition sweep, lands on Home).

### Home
- Top bar: scooter nickname (default "My scooter") + small BLE name ("HMC_000000"); trailing connection chip (icon
  bluetooth_connected / shape-morph loader / bluetooth_disabled) — tap = connect/disconnect sheet.
- **Instrument cluster hero** (custom view, ~320dp, inspired by a scooter TFT, not a copy): outer 240° speed arc
  0–80 km/h (GPS speed; bright while riding, dim at rest); inner left arc = front pack %, inner right arc = rear
  pack % (labels "F"/"R" + %); centre: **range** "61 km" (Display L, tabular) with a tiny "est." when estimated,
  below it total % and a state glyph (bolt = charging, electric_moped = drive, local_parking = standby). While
  moving (> 3 km/h) the centre swaps to **speed** with the cookie badge; range moves to a chip under the gauge.
- Metric tiles (2×2): Battery health (`ecg_heart`), Battery temp (`device_thermostat`), Today (`route`, km today),
  Efficiency (`bolt`, learned km per 1 %, "est." until learned); while charging the 4th is "Full in" (`ev_station`,
  hidden when unknown). Energy and balance live in the Battery sheet.
- State chips: ignition (power_settings_new), handle lock (lock/lock_open), speed limit.
- When disconnected: last-known values dimmed + extended FAB "Connect" (electric_moped); the parked spot row with
  a Walk button.
- Live ride card (when riding): distance, time, top speed; tap → ride detail.
- Now playing (while music plays or is paused): music_note in a tonal circle, title / artist, connected button group
  prev / play-pause / next; a small "on scooter" glyph when mirrored.
- Call banner during calls (phone_in_talk, name, duration, end/accept buttons).

### Navigate
- Hero = **TFT-style preview** card (big turn glyph, distance Display M, road Title L, arrival clock + time left +
  km left), status dot "on scooter" when frames are acknowledged.
- Range check chip under the preview while a route is active: "12 km spare", "Tight · 3 km spare", "Short by 4 km".
- Primary: extended FAB "Maps" → opens Google Maps (the rider starts navigation there).
- Setup rows only when needed: notification access (warning icon + "Allow" tonal button); "Scooter display setup"
  → sheet with 4 numbered steps, each with an icon: 1 Long-press the toggle button · 2 Menu → Display ·
  3 Widgets → Navigation · 4 Turn it on; "Done" persists and turns the row into an info icon button in the top bar.
- Toggle "Scooter display" (switch). No text inputs, no paragraphs.

### Rides — trip receipts
- A ride is a **trip from A to B**, presented like a ride-hailing receipt: map with the recorded route line, a start
  marker (trip_origin) and an end marker (flag), start and end place names with times, then the stats. No
  intermediate stops anywhere in the UI.
- Header stats (this week): distance, rides, top speed.
- List grouped by day; each card: mini route thumbnail (Canvas polyline in a 64dp rounded square), "Lakeside →
  Greenfield" (Title M), icon stats row: km, time, top speed, battery used.
- Detail: large route canvas, stats grid (2×3 icon tiles), speed chart, actions: "Open in Maps" (directions from
  start to end only: origin + destination, no waypoints), delete (icon button with confirm).

### Insights
Connected button group 24h / 7d / 30d. Summary at top (total km, rides, avg km/%, charges). Cards with charts:
Battery (F/R lines + total area), Range trend, Temperature, Health, Balance; charging sessions list (ev_station,
start→end %, duration, %/h).

### More (settings)
Grouped lists with leading icons: Scooters (saved scooters with nickname, BLE name, active check; Add scooter,
Rename, Change PIN, Forget), Features (Directions, Music, Calls, Handlebar buttons, Auto-connect), Alerts,
Permissions (each row: icon, name, status check_circle/warning, grant button), Diagnostics (log viewer, copy,
share, probe, display test, road-name width test, demo mode), Data (export CSV), About (Ruta version with the brand
mark, licences, the non-affiliation line).

## Behaviour
- **Range:** estimate = charge % × km per %; default 1.12 km/% (observed on a VX2 display); refined with an EWMA of
  km/% from rides ≥ 3 km and ≥ 2 % used, clamped 0.7–1.6. Prefer a decoded range field if one is ever identified.
  Show "est." when estimated.
- **Auto-probe:** once per connection (10 s after ready) request stats tags 2, 3, 5, 6, 10, 11 one-shot and log
  the raw replies; then disable. A manual probe stays in Diagnostics.
- **Log files:** every log line is mirrored to `getExternalFilesDir("logs")/ruta-yyyyMMdd.log` (append, flushed,
  kept 7 days). Maps notification extras are logged once per change, for analysis. Never log PINs, IMEI, VIN,
  coordinates or place names.
- **Turn-by-turn latency:** navigation writes jump ahead of queued periodic writes (priority lane right after the
  running operation); push immediately on every Maps update; never debounce.
- **Turn-by-turn distance:** parse "for 1.2 km", "in 300 m", "after 200 m"; between Maps updates count the distance
  down at 1 Hz using GPS distance travelled since the last update (reset on each update; round to 10 m below 1 km,
  0.1 km above); same for the remaining route distance. When Maps gives no distance for a new manoeuvre:
  straight ("Head/Continue") → remaining route distance; otherwise keep counting down from the previous value for
  the same manoeuvre, else 0.
- **Road names for the display** (≈8–9 characters before "…"): abbreviate (Road→Rd, Street→St, Lane→Ln), drop
  generic suffixes when other words exist, drop honorifics (Sri, Shri, Swami, Dr), prefer the most distinctive
  token(s) within a 9-character budget ("Riverside Garden Rd" → "Riverside"). Diagnostics has a road-name width
  test that sends names of 8–12 characters.
- **Place names:** Geocoder → "thoroughfare, subLocality" (e.g. "Riverside Garden Rd, Lakeside"), fallback
  locality.
- **BMS:** reject temperatures < −30 °C (pack 1 sends a raw placeholder). Status 4 = Drive, 0/2 = Standby.
- **Pairing:** if the scooter is not bonded, call createBond() before service discovery. While bonding, a
  high-priority receiver for ACTION_PAIRING_REQUEST answers for that device with the saved PIN so pairing is
  silent; log the pairing variant, never the PIN. Only a PIN Android actually delivered and the scooter then
  refused is a wrong PIN ("Scooter didn't accept this PIN", the PIN step reopens); a PIN never delivered, a stale
  bond (GATT 5/15/137 while paired → Bluetooth settings) and a lost link each get their own message
  (PairingRules.bondOutcome / linkFailure). If a system PIN dialog still appears (some OEM skins), show the saved
  PIN in a snackbar with a Copy action. Already-bonded scooters skip the PIN entirely.
- **Storage:** PIN per scooter MAC encrypted with an Android Keystore AES-GCM key, in private SharedPreferences;
  nickname and last-used scooter stored plainly. Forget scooter deletes its PIN and settings and offers "Open
  Bluetooth settings" to unpair.
- **Auto-connect:** on launch and when Bluetooth turns on, connect to the active saved scooter; a short drop
  reconnects for about 2 minutes.
