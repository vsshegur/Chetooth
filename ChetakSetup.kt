package dev.codesurgen.ruta

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import kotlin.math.roundToInt

/**
 * Chetooth: More › Chetak setup. The rider teaches the app where the Chetak sends its battery % and
 * range: type what the dashboard shows, save, and again once the number has changed. The sheet
 * lists the matching fields with their live value; the one that fits every reading is adopted
 * automatically, or the rider taps Use. Also shows (and shares) the raw values the scooter sends.
 */
class ChetakSetup(private val host: Host) {
    private val ctx = host.context
    private var sheet: BottomSheet? = null
    private val status = ctx.textView("", Typography.bodyMedium, Theme.onSurface)
    private val raw = ctx.textView("", Typography.bodySmall, Theme.onSurfaceVariant).apply {
        typeface = Typeface.MONOSPACE
        setTextIsSelectable(true)
    }
    private val sections = LearnField.entries.map { FieldSection(it) }
    private var ticker: Runnable? = null
    private var pending: Runnable? = null
    private val dataListener: () -> Unit = { scheduleRender() }

    fun show() {
        val content = ctx.column().apply {
            addView(ctx.textView(INTRO, Typography.bodyMedium, Theme.onSurfaceVariant), ctx.params())
            addView(status, ctx.params(Spacing.S))
            sections.forEach { addView(it.view, ctx.params(Spacing.L)) }
            addView(ctx.sectionHeader("What the scooter sends"), ctx.params(Spacing.L))
            addView(raw, ctx.params(Spacing.S))
            addView(ctx.row().apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                addView(ctx.actionButton("Share data", ButtonVariant.TEXT) { share() })
                addView(ctx.actionButton("Done", ButtonVariant.TEXT) { sheet?.dismiss() })
            }, ctx.params(Spacing.L))
        }
        val shown = host.showSheet(content, "Chetak setup") ?: return
        sheet = shown
        shown.onDismiss = { stop() }
        ChetakData.addListener(dataListener)
        tick()
    }

    private fun stop() {
        ChetakData.removeListener(dataListener)
        ticker?.let(Ui::cancel); ticker = null
        pending?.let(Ui::cancel); pending = null
        sheet = null
    }

    private fun tick() {
        render()
        ticker = Ui.postDelayed(1_000) { if (sheet != null) tick() }
    }

    private fun scheduleRender() {
        if (pending != null) return
        pending = Ui.postDelayed(300) { pending = null; if (sheet != null) render() }
    }

    private fun render() {
        if (status.tag == null) status.text = when {
            host.demoActive -> "Demo mode is on: end it in More › Diagnostics to set up your scooter."
            !ChetakData.linked -> "Connect your Chetak first (scooter ON, close the official Chetak app). The setup needs live data."
            ChetakData.lastByChar.isEmpty() -> "Connected. Waiting for the scooter to send data…"
            else -> "Connected · receiving ${ChetakData.lastByChar.size} values from the scooter."
        }
        sections.forEach { it.render() }
        raw.text = if (ChetakData.lastByChar.isEmpty()) "Nothing received yet."
        else ChetakData.lastByChar.entries.joinToString("\n") { (u, v) -> "${shortUuid(u)} ×${ChetakData.countByChar[u] ?: 0}\n  ${v.hex()}" }
    }

    /** A message in the status line that stays until the next action (tag marks it as sticky). */
    private fun say(text: String) {
        status.text = text
        status.tag = true
        Ui.postDelayed(8_000) { if (status.text.toString() == text) status.tag = null }
    }

    private fun share() {
        val text = ChetakData.report(ctx) + "\nLog (Chetak lines):\n" +
            host.logLines.filter { it.contains("Chetak", ignoreCase = true) || it.contains("Service") }.takeLast(300).joinToString("\n")
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Chetooth scooter data")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        runCatching { host.activity.startActivity(Intent.createChooser(send, "Share scooter data")) }
    }

    private inner class FieldSection(private val field: LearnField) {
        val view: LinearLayout = ctx.column()
        private val state = ctx.textView("", Typography.bodyLarge, Theme.onSurface)
        private val readings = ctx.textView("", Typography.bodySmall, Theme.onSurfaceVariant)
        private val input = EditText(ctx).apply {
            val shape = RoundedShapeDrawable(Theme.surfaceContainerHigh, ctx.dpf(Shapes.LARGE))
            background = shape
            applyStyle(Typography.bodyLarge)
            setTextColor(Theme.onSurface)
            setHintTextColor(Theme.onSurfaceVariant)
            hint = "${field.label} on dashboard (${field.unit})"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            imeOptions = EditorInfo.IME_ACTION_DONE
            isSingleLine = true
            minHeight = ctx.dp(56)
            setPaddingRelative(ctx.dp(Spacing.L), ctx.dp(Spacing.M), ctx.dp(Spacing.L), ctx.dp(Spacing.M))
            setOnFocusChangeListener { _, focused ->
                shape.strokeColor = if (focused) Theme.primary else Color.TRANSPARENT
                shape.strokeWidth = if (focused) ctx.dpf(2f) else 0f
                shape.invalidateSelf()
            }
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_DONE) { save(); true } else false }
        }
        private val candidates = ctx.column()
        private val rows = ArrayList<Pair<Candidate, ListItem>>()
        private var candidatesKey: String? = null

        init {
            view.addView(ctx.sectionHeader("${field.label} (${field.unit})"), ctx.params())
            view.addView(state, ctx.params(Spacing.XS))
            view.addView(ctx.row().apply {
                addView(input, ctx.weighted())
                addView(ctx.filledButton("Save") { save() }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = ctx.dp(Spacing.S)
                })
            }, ctx.params(Spacing.S))
            view.addView(readings, ctx.params(Spacing.XS))
            view.addView(candidates, ctx.params(Spacing.S))
            view.addView(ctx.row().apply {
                gravity = Gravity.END
                addView(ctx.actionButton("Reset ${field.label.lowercase()}", ButtonVariant.TEXT) { reset() })
            }, ctx.params(Spacing.XS))
        }

        private fun fmt(v: Double): String =
            if (field == LearnField.SOC) "${v.roundToInt()} ${field.unit}" else "${"%.1f".format(v)} ${field.unit}"

        fun render() {
            val decoder = ChetakData.decoder(ctx, field)
            val now = decoder?.let { ChetakData.book.read(it, field) }
            state.text = when {
                decoder == null -> "Not set up yet."
                now != null -> "✓ ${fmt(now)} now · learned"
                else -> "✓ Learned · waiting for the scooter to send it"
            }
            val points = ChetakData.points(ctx, field)
            readings.text = if (points.isEmpty()) hint()
            else "Readings saved: " + points.joinToString(", ") { fmt(it.value) } +
                if (decoder == null && !ChetakLearn.hasSpread(points, field)) ". Save another after the dashboard number changes." else ""
            renderCandidates(points, decoder)
        }

        private fun hint(): String = when (field) {
            LearnField.SOC -> "With the scooter connected, type the battery % the dashboard shows and tap Save. Ride or charge until it changes, then save again."
            LearnField.RANGE -> "Optional. Type the range (km) the dashboard shows and tap Save; again once it has changed. Without it the app estimates range from your rides."
        }

        private fun renderCandidates(points: List<CalPoint>, decoder: Candidate?) {
            val ranked = ChetakLearn.rank(points, field)
            val full = ranked.filter { it.hits == it.total }
            val show = (full.ifEmpty { ranked }).take(if (ChetakLearn.hasSpread(points, field)) 4 else 6)
            val key = show.joinToString { it.candidate.encode() + it.hits } + "|" + decoder?.encode()
            if (key != candidatesKey) {
                candidatesKey = key
                candidates.removeAllViews(); rows.clear()
                if (show.isNotEmpty() && decoder == null) candidates.addView(ctx.textView(
                    if (ChetakLearn.hasSpread(points, field)) "Matches for every reading:" else "Possible matches (one reading can match by chance):",
                    Typography.labelLarge, Theme.onSurfaceVariant), ctx.params())
                val group = ListGroup(ctx)
                for (r in show) {
                    val c = r.candidate
                    val use = ctx.tonalButton(if (c == decoder) "In use" else "Use") { use(c) }.apply { isEnabled = c != decoder }
                    val item = ListItem(ctx, "", "${r.hits} of ${r.total} readings · ${c.describe()}", null, use)
                    group.addView(item)
                    rows += c to item
                }
                if (show.isNotEmpty()) candidates.addView(group, ctx.params(Spacing.XS))
            }
            for ((c, item) in rows) {
                val v = ChetakData.book.read(c, field)
                item.setHeadline(if (v != null) "Now ${fmt(v)}" else "Not in the latest data")
            }
        }

        private fun save() {
            val value = input.text.toString().trim().replace(',', '.').toDoubleOrNull()
            if (value == null || value < field.minimum || value > field.maximum) {
                say("Type the ${field.label.lowercase()} shown on the dashboard (${field.minimum.toInt()}–${field.maximum.toInt()} ${field.unit}).")
                return
            }
            if (!ChetakData.linked) { say("Connect your Chetak first, then save the reading."); return }
            val before = ChetakData.decoder(ctx, field)
            val point = ChetakData.addPoint(ctx, field, value)
            if (point == null) { say("Nothing received from the scooter yet. Wait a few seconds and try again."); return }
            input.text.clear()
            hideKeyboard()
            val after = ChetakData.decoder(ctx, field)
            val points = ChetakData.points(ctx, field)
            when {
                before == null && after != null -> { say("Found it: ${field.label.lowercase()} is now read from your Chetak."); host.refreshLink() }
                ChetakLearn.rank(points, field).isEmpty() -> say("No number in the scooter's data matches ${fmt(value)} yet. Check the value; some data may only arrive while riding or charging.")
                else -> say("Saved ${fmt(value)}. Save another once the dashboard shows a different number.")
            }
            render()
        }

        private fun use(c: Candidate) {
            ChetakData.setDecoder(ctx, field, c)
            say("${field.label} will be read from ${c.describe()}.")
            host.refreshLink()
            render()
        }

        private fun reset() {
            ChetakData.reset(ctx, field)
            candidatesKey = null
            say("${field.label} setup cleared.")
            render()
        }

        private fun hideKeyboard() {
            ctx.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(input.windowToken, 0)
            input.clearFocus()
        }
    }

    private companion object {
        const val INTRO = "Bajaj hasn't published how the Chetak sends its data over Bluetooth, so Chetooth learns it from your scooter. " +
            "It only listens and never sends anything to the scooter."
    }
}
