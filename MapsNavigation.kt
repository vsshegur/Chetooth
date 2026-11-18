package dev.codesurgen.ruta

import java.time.LocalDateTime
import java.util.Locale

/** One parsed Google Maps navigation update. */
data class MapsCue(
    val turn: Int,
    val distanceMeters: Int?,
    val instruction: String,
    val road: String,
    val arrivalHour24: Int?,
    val arrivalMinute: Int?,
    val remainingMeters: Int?,
    val rerouting: Boolean,
    /**
     * The second of two back-to-back maneuvers ("Turn left onto X, then turn right" → [NavTurn.RIGHT]),
     * only when it has a "Then …" hint ([RoadNames.thenHint]); null otherwise.
     */
    val thenTurn: Int? = null
) {
    /**
     * The 0x0303 road text: the "Then …" hint when a second maneuver follows right after this one
     * ("Then left"), else [road] fitted for the TFT ([RoadNames.fit]: "Riverside Garden Rd" → "Riverside").
     */
    val tftRoad: String by lazy { thenTurn?.let { RoadNames.thenHint(it) } ?: RoadNames.fit(road) }

    /**
     * Cluster frame; remaining time is derived from the arrival clock at [now]. [distanceMeters]
     * and [remainingMeters] default to this update's values; NavBridge passes the counted-down
     * ones ([NavCountdown]). The road is [tftRoad].
     */
    fun frame(now: LocalDateTime, distanceMeters: Int? = this.distanceMeters, remainingMeters: Int? = this.remainingMeters): NavFrame {
        val hour = arrivalHour24; val minute = arrivalMinute
        val remainingMinutes = if (hour == null || minute == null) 0 else {
            var arrival = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
            if (arrival.isBefore(now.minusMinutes(5))) arrival = arrival.plusDays(1) // Arrives after midnight.
            ((java.time.Duration.between(now, arrival).seconds + 30) / 60).toInt().coerceAtLeast(0)
        }
        return NavFrame(
            active = true, turn = turn, distanceMeters = distanceMeters ?: 0, nextTurn = NavTurn.STRAIGHT,
            arrivalHour24 = hour ?: now.hour, arrivalMinute = minute ?: now.minute,
            remainingMeters = remainingMeters ?: 0, remainingMinutes = remainingMinutes, road = tftRoad
        )
    }
}

/**
 * Parses Google Maps' ongoing navigation notification. Verified format (Maps 26.46, Android 16,
 * ProgressStyle "Live Update"): title "120 m · Turn left onto Swami Vidyananda Ln", subText
 * "Arrive 3:45 pm", shortCriticalText "120 m", progressMax = route length in metres and progress =
 * metres travelled along it (0/3389 at the start, 3389 → 3402 after a reroute, 0–29 jitter while
 * standing), so progressMax − progress is the remaining route, not the maneuver distance;
 * progressPoints was empty and progressSegments held 5 opaque bundles (traffic colouring).
 * Most updates carry no distance at all ("Head toward Ganesh Nagar Rd", "Turn left onto Ganesh
 * Nagar Rd": 33 of 38 in the 2.0 capture); [MapsCue.distanceMeters] is then null and
 * [NavCountdown] decides. Distance sources, most explicit first: a leading title distance, then
 * shortCriticalText, then a phrase ("for 1.2 km", "in 300 m", "after 200 m"). Older builds put the
 * distance in the title and the instruction in the text; both are handled. English only.
 *
 * Back-to-back maneuvers ("Turn left onto X, then turn right", "…, then keep left", or a separate
 * "Then turn right" text line): the "then" clause is cut before the turn and road are read, and the
 * second maneuver becomes [MapsCue.thenTurn] ([thenTurn]). A clause counts only when "then" is a word
 * followed by maneuver wording, so "Thenmozhi St" and "onto Then Kudi Rd" stay road names.
 */
object MapsNavigationParser {
    private val DISTANCE = Regex("""^\s*(\d+(?:[.,]\d+)?)\s*(km|m|mi|ft|yd)\b""", RegexOption.IGNORE_CASE)
    /** "Continue on NH 48 for 12 km", "In 300 m, turn left", "Turn right after 200 m". */
    private val PHRASE_DISTANCE = Regex(
        """\b(?:in|for|after|within)\s+(?:about\s+|approx(?:\.|imately)?\s+)?(\d+(?:[.,]\d+)?\s*(?:km|m|mi|ft|yd))\b""",
        RegexOption.IGNORE_CASE
    )
    /** A leading "In 300 m, " / "After 200 m " is distance, not instruction. */
    private val LEADING_PHRASE = Regex("""^(?:in|after)\s+\d+(?:[.,]\d+)?\s*(?:km|m|mi|ft|yd)\b[\s,]*""", RegexOption.IGNORE_CASE)
    private val THOUSANDS = Regex("""\d{1,3},\d{3}""")
    private val ARRIVAL = Regex("""(\d{1,2})[:.](\d{2})\s*([ap]\.?\s?m\.?)?""", RegexOption.IGNORE_CASE)
    private val SEPARATOR = Regex("""\s+[·•|]\s+""")
    private val REROUTING = Regex("""rerouting|finding route|searching for gps|gps signal lost|starting navigation""")
    private val SIDE_NOTE = Regex("""\s*\([^)]*\)""")
    /** Leading "At <landmark>, " (but not "At the roundabout, "). */
    private val LEADING_LANDMARK = Regex("""^at\s+(?!the\s)[^,]+,\s*""")
    /** Where road/landmark names start; "on the left", "at the roundabout/fork/end" stay part of the maneuver. */
    private val NAME_CLAUSE = Regex(
        """\s+(?:onto|towards?|after|before|past|via|by|follow)\s+|\s+(?:at|on)\s+(?!(?:the|your)\s+(?:next\s+)?(?:left|right|roundabout|rotary|traffic circle|fork|end)\b)""" +
            """|\s+to\s+(?!(?:take|stay|continue|follow|merge|keep|turn|make|use|go|enter|exit|join|reach|get|avoid)\b)"""
    )
    /** Arrival wording, matched on the whole instruction so "Arrive at X" survives the name cut. */
    private val DESTINATION = Regex(
        """\byour destination\b|\bdestination will be on\b|\byou have arrived\b|\barrived? at\b|\b(?:is|will be) on (?:the|your) (?:left|right)\.?$|^(?:you(?:'|’)ve\s+)?arrived\b"""
    )

    /**
     * Start of a "then" clause: ", then turn right", " and then keep left", " · Then make a U-turn",
     * or "Then …" at the very start (Maps' separate text line). "then" must be a whole word followed by
     * maneuver wording, so a road named "Then Kudi Rd" or "Thenmozhi St" never matches.
     */
    private val THEN_CLAUSE = Regex(
        """(?:\s*[,;]\s*|\s+[·•|]\s+|\s+|^)(?:and\s+)?then\b[\s,]*(?=(?:turn|keep|make|take|bear|slight|sharp|stay|continue|go|merge|exit|enter|use|head|at\s+the|u[- ]?turn|you(?:'|’)ll|arrive|your\s+destination|the\s+destination)\b)""",
        RegexOption.IGNORE_CASE
    )
    /** The second maneuver ends at the next notification separator. */
    private val CLAUSE_END = Regex("""\s+[·•|]\s+""")

    /**
     * Maps/Android use U+00A0 / U+202F spaces (e.g. "4:13 pm", "120 m") and U+2011
     * hyphens; every Unicode space becomes ' ', non-breaking hyphens '-'.
     */
    private fun normalise(value: String?): String? = value?.let { text ->
        buildString(text.length) {
            for (c in text) append(
                when {
                    c == '\u2011' || c == '\u2010' -> '-'
                    c == '\t' || Character.isSpaceChar(c) -> ' '
                    else -> c
                }
            )
        }
    }

    fun parse(
        rawTitle: String?, rawText: String?, rawSubText: String?, rawShort: String?,
        progress: Int?, progressMax: Int?
    ): MapsCue? {
        val title = normalise(rawTitle); val text = normalise(rawText); val subText = normalise(rawSubText); val shortCriticalText = normalise(rawShort)
        val parts = title.orEmpty().split(SEPARATOR, limit = 2).map { it.trim() }
        val distanceTitle = parts.firstOrNull()?.let(::distanceMeters)
        val fullInstruction = when {
            parts.size == 2 && distanceTitle != null -> parts[1]
            distanceTitle != null -> text.orEmpty().trim()
            else -> listOf(title, text).filterNot { it.isNullOrBlank() }.joinToString(" · ").trim()
        }
        val phrase = phraseDistance(fullInstruction) ?: text?.let(::phraseDistance)
        val instruction = fullInstruction.replace(LEADING_PHRASE, "").replaceFirstChar { it.uppercaseChar() }
        // "120 m · Turn left onto X" in the title and "Then turn right" alone in the text.
        val separateThen = if (parts.size == 2 && distanceTitle != null) text?.let(::splitThen)?.takeIf { it.first.isBlank() }?.second else null
        val distance = distanceTitle ?: shortCriticalText?.let(::distanceMeters) ?: phrase
        val lower = instruction.lowercase(Locale.ROOT)
        val rerouting = REROUTING.containsMatchIn(lower)
        if (instruction.isBlank() && distance == null) return null
        val turn = turnFor(lower)
        if (distance == null && !rerouting && turn == null) return null // Not a navigation update.
        val (hour, minute) = arrival(subText) ?: arrival(text) ?: (null to null)
        val remaining = if (progressMax != null && progressMax > 0 && progress != null) (progressMax - progress).coerceAtLeast(0) else null
        return MapsCue(
            turn = turn ?: NavTurn.STRAIGHT, distanceMeters = distance, instruction = instruction,
            road = road(instruction), arrivalHour24 = hour, arrivalMinute = minute,
            remainingMeters = remaining, rerouting = rerouting,
            thenTurn = thenTurn(instruction) ?: separateThen?.let(::hintTurn)
        )
    }

    /**
     * ("Turn left onto X", "turn right") for "Turn left onto X, then turn right"; ([value], null)
     * without a then clause. A [value] that is only a clause ("Then keep left") gives ("", "keep left").
     */
    fun splitThen(value: String): Pair<String, String?> {
        val match = THEN_CLAUSE.find(value) ?: return value to null
        val clause = value.substring(match.range.last + 1).split(CLAUSE_END).first().trim().trimEnd('.')
        if (clause.isEmpty()) return value to null
        return value.substring(0, match.range.first).trim() to clause
    }

    /** [instruction] without its then clause (the maneuver the turn symbol and road describe). */
    fun current(instruction: String): String = splitThen(instruction).first.ifBlank { instruction }

    /**
     * The second maneuver of [instruction] as a [NavTurn] code, when it has a "Then …" hint
     * ([RoadNames.thenHint]: turns, U-turns, keep/fork, roundabouts, exits); null without a then clause,
     * or for one that is no hint ("then continue", "then your destination is on the left").
     */
    fun thenTurn(instruction: String): Int? {
        val (head, clause) = splitThen(normalise(instruction) ?: return null)
        if (head.isBlank() || clause == null) return null
        return hintTurn(clause)
    }

    private fun hintTurn(clause: String): Int? =
        turnFor(clause.lowercase(Locale.ROOT))?.takeIf { RoadNames.thenHint(it) != null }

    /**
     * A leading distance ("120 m", "1.2 km", "1,2 km", "1,200 m", "120 m") in metres. A comma
     * is a decimal mark, except "1,200" in m/ft/yd (whole units never have decimals).
     */
    fun distanceMeters(value: String): Int? {
        val match = DISTANCE.find(normalise(value) ?: return null) ?: return null
        val unit = match.groupValues[2].lowercase(Locale.ROOT)
        val digits = match.groupValues[1]
        val number = (if (unit != "km" && unit != "mi" && THOUSANDS.matches(digits)) digits.replace(",", "") else digits.replace(',', '.'))
            .toDoubleOrNull() ?: return null
        val metres = when (unit) {
            "km" -> number * 1000; "m" -> number; "mi" -> number * 1609.344; "ft" -> number * 0.3048; "yd" -> number * 0.9144
            else -> return null
        }
        return Math.round(metres).toInt() // "32.3 km" is 32299.99… m; truncating would send 32.2 km.
    }

    /** First "for/in/after/within <distance>" phrase anywhere in [value], in metres. */
    fun phraseDistance(value: String): Int? =
        PHRASE_DISTANCE.find(normalise(value) ?: return null)?.groupValues?.get(1)?.let(::distanceMeters)

    fun arrival(value: String?): Pair<Int, Int>? {
        val match = ARRIVAL.find(normalise(value) ?: return null) ?: return null
        var hour = match.groupValues[1].toInt(); val minute = match.groupValues[2].toInt()
        if (hour > 23 || minute > 59) return null
        val marker = match.groupValues[3].lowercase(Locale.ROOT).filter { it == 'a' || it == 'p' }
        if (marker == "p" && hour < 12) hour += 12
        if (marker == "a" && hour == 12) hour = 0
        return hour to minute
    }

    /**
     * Instruction text → cluster symbol. Only the maneuver phrase is classified: side notes
     * "(on the left)" and name clauses ("onto X", "toward X", "at X") are cut first, and words
     * match whole, so names like Rampur, Rotary Club Rd or Ferry Ghat cannot pick the symbol.
     * Order matters (e.g. "sharp left" before "left"). India drives on the left: U-turns are clockwise.
     */
    fun turnFor(lower: String): Int? {
        val core = current(lower.replace(SIDE_NOTE, "").trim())
        val maneuver = core.replace(LEADING_LANDMARK, "").split(NAME_CLAUSE).first()
        fun has(words: String) = Regex("""\b(?:$words)\b""").containsMatchIn(maneuver)
        val left = has("left"); val right = has("right")
        return when {
            has("u[- ]?turn") -> if (has("left u[- ]?turn|u[- ]?turn to the left")) NavTurn.U_TURN_LEFT else NavTurn.U_TURN_RIGHT
            // Named roundabouts read "At <Name> Circle, take the 2nd exit"; slip roads say "Take exit 12".
            has("roundabouts?|traffic circle|at the rotary|(?:\\d+(?:st|nd|rd|th)|first|second|third|fourth|fifth|sixth) exit") -> if (has("counterclockwise|anticlockwise")) NavTurn.ROUNDABOUT_RIGHT else NavTurn.ROUNDABOUT_LEFT
            DESTINATION.containsMatchIn(core) -> NavTurn.DESTINATION
            has("sharp left") -> NavTurn.SHARP_LEFT
            has("sharp right") -> NavTurn.SHARP_RIGHT
            has("slight left|bear left|slightly left") -> NavTurn.SLIGHT_LEFT
            has("slight right|bear right|slightly right") -> NavTurn.SLIGHT_RIGHT
            has("keep left") -> NavTurn.KEEP_LEFT
            has("keep right") -> NavTurn.KEEP_RIGHT
            has("turn left|make a left") -> NavTurn.LEFT
            has("turn right|make a right") -> NavTurn.RIGHT
            has("fork") -> if (right && !left) NavTurn.FORK_RIGHT else NavTurn.FORK_LEFT
            has("ramp|exit") -> if (right && !left) NavTurn.RAMP_RIGHT else NavTurn.RAMP_LEFT
            has("merge") -> NavTurn.MERGE
            has("ferry") -> if (has("train")) NavTurn.FERRY_TRAIN else NavTurn.FERRY
            has("head|continue|straight|stay|pass") -> NavTurn.STRAIGHT
            left && !right -> NavTurn.LEFT
            right && !left -> NavTurn.RIGHT
            else -> null
        }
    }

    /** Next road: "onto X", then "on X", "toward X", "to X"; side notes, then clauses, trailing clauses and "/" aliases dropped. */
    fun road(instruction: String): String {
        val text = current(instruction.replace(SIDE_NOTE, "").trim())
        if (DESTINATION.containsMatchIn(text.lowercase(Locale.ROOT))) return ""
        fun after(keyword: String): String? {
            val match = Regex("""\b$keyword\s++(?!(?:the|your)\s+(?:left|right)\b)(.+)$""", RegexOption.IGNORE_CASE).find(text) ?: return null
            return match.groupValues[1].split(Regex("""\s+towards?\s+|\s+via\s+|\s+for\s+\d|\s*[,;]\s*|\s+then\s+|\s+[·•|]\s+""", RegexOption.IGNORE_CASE))
                .first().substringBefore('/').trim().trimEnd('.').takeIf { it.isNotEmpty() }
        }
        val to = """to(?!\s+(?:take|stay|continue|follow|merge|keep|turn|make|use|go|enter|exit|join|reach|get|avoid)\b)"""
        return after("onto") ?: after("on") ?: after("towards") ?: after("toward") ?: after(to) ?: after("""follow(?!\s+signs\b)""") ?: ""
    }

    /** Log line for one update. [raw] is Maps text, so it is never used as a format string (a '%' would throw). */
    fun describe(cue: MapsCue?, raw: String): String =
        if (cue == null) "Maps (unparsed): $raw"
        else "Maps: $raw → turn 0x${cue.turn.toString(16).uppercase(Locale.ROOT).padStart(2, '0')}, ${cue.distanceMeters ?: "?"} m, road '${cue.road}'" +
            (cue.thenTurn?.let { " · '${cue.tftRoad}'" } ?: "")
}
