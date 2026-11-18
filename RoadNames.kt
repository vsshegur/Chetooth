package dev.codesurgen.ruta

import java.util.Locale

/**
 * Road names for the scooter's TFT, which shows only about [TFT_CHARS] characters before cutting
 * the name off with its own "…" (2.0 sent "Riverside Garden Rd" and the rider saw "Riversid…").
 * [fit] picks the part of a Google Maps road name a rider recognises, in this order:
 *  1. ASCII as the 0x0303 road field will carry it (accents folded, punctuation splits words,
 *     apostrophes vanish), common words abbreviated (Road → Rd, Street → St, Lane → Ln, …) and a
 *     few well-known names shortened (Outer Ring Rd → ORR, National Highway 48 → NH 48);
 *  2. the whole name, if it fits [budget];
 *  3. without honorifics (Sri, Shri, Swami, Dr, …) and initials, then also without a generic
 *     suffix (Rd, St, Ln, Hwy, Marg, …); "Main" and "Nagar" are kept ("5th Main", "Sai Nagar");
 *  4. otherwise the most distinctive run of consecutive words that fits: numbers and long names
 *     beat generic words (Garden, City, Nagar, Old, …), ties go to the longer, then later, run
 *     ("Riverside Garden Rd" → "Riverside", "Ganesh Nagar Rd" → "Ganesh");
 *  5. if no distinctive word fits, the most distinctive single word whole ("Swami Vidyananda Ln"
 *     → "Vidyananda"): the TFT adds its own "…", which reads better than a word cut here. Only a
 *     word longer than the 14-byte field is cut.
 * The result never holds a word cut mid-way except in that last case, contains only ASCII letters,
 * digits, '?', '+' and single spaces, and is at most [FIELD_BYTES] long. Pure Kotlin (host tests).
 * [thenHint] replaces the road with "Then left" / "Then keep L" / … when Maps announces a second
 * maneuver right after the current one, within the same budget.
 */
object RoadNames {
    /** Characters the TFT shows before its "…" (≈ 8–9 seen on the VX2; confirm with the road-name width test). */
    const val TFT_CHARS = 9
    /** Size of the 0x0303 road field (NavFrame.roadBytes). */
    const val FIELD_BYTES = 14

    fun fit(name: String, budget: Int = TFT_CHARS): String {
        val limit = budget.coerceIn(1, FIELD_BYTES)
        val words = shorten(tokens(name))
        if (words.isEmpty()) return ""
        joined(words).let { if (it.length <= limit) return it }

        var core = words
        core = dropWhere(core) { index, word -> isHonorific(word, index) }
        core = dropWhere(core) { _, word -> word.length == 1 && word[0].isLetter() } // initials: "A P J Abdul Kalam"
        joined(core).let { if (it.length <= limit) return it }
        while (core.size > 1 && core.last().lowercase(Locale.ROOT) in SUFFIXES) core = core.dropLast(1)
        joined(core).let { if (it.length <= limit) return it }

        var best: Pick? = null
        for (from in core.indices) for (to in from until core.size) {
            val run = core.subList(from, to + 1)
            val text = joined(run)
            if (text.length > limit) break
            if (run.none { distinctive(it) }) continue
            val pick = Pick(text, run.sumOf(::score), from)
            if (best == null || pick > best) best = pick
        }
        best?.let { return it.text }

        val word = core.withIndex().maxWith(compareBy<IndexedValue<String>>({ score(it.value) }, { it.value.length }, { it.index })).value
        return word.take(FIELD_BYTES)
    }

    /**
     * "Then …" hint for the road field when a second maneuver follows right after the current one
     * (MapsCue.thenTurn): the longest wording that fits [budget] (same TFT budget as [fit]), else the
     * shortest. ASCII letters and spaces only ('-' would reach the TFT as '_'). Null for a [turn]
     * without a hint (straight, destination, merge, ferry): the cue then keeps its road name.
     */
    fun thenHint(turn: Int, budget: Int = TFT_CHARS): String? {
        val forms = THEN_HINTS[turn] ?: return null
        val limit = budget.coerceIn(1, FIELD_BYTES)
        return forms.firstOrNull { it.length <= limit } ?: forms.last()
    }

    /** Wordings per NavTurn code, longest first; the last one fits the 9-character default budget. */
    private val THEN_HINTS: Map<Int, List<String>> = run {
        val left = listOf("Then left")
        val right = listOf("Then right", "Then R")
        val uTurn = listOf("Then U turn", "Then Uturn", "Then U")
        val keepLeft = listOf("Then keep left", "Then keep L", "Then kp L")
        val keepRight = listOf("Then keep right", "Then keep R", "Then kp R")
        val roundabout = listOf("Then rndabout", "Then rdbt")
        val exit = listOf("Then exit")
        mapOf(
            NavTurn.LEFT to left, NavTurn.SLIGHT_LEFT to left, NavTurn.SHARP_LEFT to left,
            NavTurn.RIGHT to right, NavTurn.SLIGHT_RIGHT to right, NavTurn.SHARP_RIGHT to right,
            NavTurn.U_TURN_LEFT to uTurn, NavTurn.U_TURN_RIGHT to uTurn,
            NavTurn.KEEP_LEFT to keepLeft, NavTurn.FORK_LEFT to keepLeft,
            NavTurn.KEEP_RIGHT to keepRight, NavTurn.FORK_RIGHT to keepRight,
            NavTurn.ROUNDABOUT_LEFT to roundabout, NavTurn.ROUNDABOUT_RIGHT to roundabout,
            NavTurn.RAMP_LEFT to exit, NavTurn.RAMP_RIGHT to exit
        )
    }

    private class Pick(val text: String, val score: Double, val from: Int) : Comparable<Pick> {
        override fun compareTo(other: Pick): Int = compareValuesBy(this, other, { it.score }, { it.text.length }, { it.from })
    }

    /** ASCII words: apostrophes removed, everything the road field cannot carry splits words. */
    private fun tokens(name: String): List<String> {
        val ascii = IcbtText.sanitize(name.replace(Regex("[’'`´]"), ""), " ")
        return ascii.split(' ').filter { it.isNotEmpty() }
    }

    private fun shorten(words: List<String>): List<String> {
        val out = words.map { ABBREVIATIONS[it.lowercase(Locale.ROOT)] ?: it }.toMutableList()
        fun lower(i: Int) = out.getOrNull(i)?.lowercase(Locale.ROOT)
        var i = 0
        while (i < out.size) {
            when {
                lower(i) in setOf("outer", "inner") && lower(i + 1) == "ring" && lower(i + 2) == "rd" -> {
                    val short = if (lower(i) == "outer") "ORR" else "IRR"
                    repeat(3) { out.removeAt(i) }; out.add(i, short)
                }
                lower(i) == "national" && lower(i + 1) == "hwy" -> { out.removeAt(i); out[i] = "NH" }
                lower(i) == "state" && lower(i + 1) == "hwy" && out.getOrNull(i + 2)?.any(Char::isDigit) == true -> { out.removeAt(i); out[i] = "SH" }
            }
            i++
        }
        return out
    }

    private fun joined(words: List<String>) = words.joinToString(" ")

    /** Drops matching words only when something else remains. */
    private fun dropWhere(words: List<String>, drop: (Int, String) -> Boolean): List<String> {
        val kept = words.filterIndexed { index, word -> !drop(index, word) }
        return if (kept.isEmpty()) words else kept
    }

    /** "St" is Saint only as the first word ("St Marks Rd"); at the end it is Street. */
    private fun isHonorific(word: String, index: Int): Boolean {
        val lower = word.lowercase(Locale.ROOT)
        return lower in HONORIFICS || (index == 0 && lower == "st")
    }

    private fun score(word: String): Double {
        val lower = word.lowercase(Locale.ROOT)
        return when {
            word.any(Char::isDigit) -> 8.0 // NH 48, Rd No 12, 5th Main: numbers are what riders read.
            lower in CONNECTORS -> 0.5
            lower in GENERIC || lower in SUFFIXES -> 1.0
            word.length == 1 -> 0.5
            else -> word.length.toDouble()
        }
    }

    private fun distinctive(word: String) = score(word) >= 2.0

    private val ABBREVIATIONS = mapOf(
        "road" to "Rd", "rd" to "Rd", "street" to "St", "lane" to "Ln", "avenue" to "Ave", "highway" to "Hwy",
        "expressway" to "Expy", "boulevard" to "Blvd", "junction" to "Jn", "circle" to "Cir", "number" to "No"
    )

    /** Dropped from the end when other words remain (after abbreviation). */
    private val SUFFIXES = setOf("rd", "st", "ln", "ave", "hwy", "expy", "blvd", "marg", "path", "way", "rds", "sarani", "salai")

    private val HONORIFICS = setOf(
        "sri", "shri", "shree", "sree", "srimati", "shrimati", "smt", "swami", "swamy", "dr", "doctor", "sir", "saint",
        "sant", "pandit", "pt", "prof", "late", "mahatma", "sardar", "netaji", "lokmanya", "maharshi", "guru", "capt",
        "col", "lt", "maj", "gen"
    )

    /** Kept, but never chosen over a name when only a few characters fit. */
    private val GENERIC = setOf(
        "nagar", "main", "colony", "layout", "garden", "gardens", "park", "city", "cross", "cir", "jn", "chowk",
        "market", "bazar", "bazaar", "hills", "enclave", "vihar", "bagh", "old", "new", "north", "south", "east",
        "west", "upper", "lower", "inner", "outer", "ring", "service", "phase", "sector", "block", "stage",
        "extension", "ext", "village", "town", "gate", "flyover", "bridge", "bypass", "link", "approach",
        "station", "metro", "bus", "stand", "temple", "complex", "plaza", "mall", "tower", "towers", "street",
        "lane", "road", "x", "club", "society", "residency", "apartments", "school", "college", "hospital"
    )

    private val CONNECTORS = setOf("no", "of", "and", "the", "to", "via")
}
