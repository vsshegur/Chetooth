package dev.codesurgen.ruta

import java.time.LocalDateTime

// 2.1 navigation: Maps parsing (strings as captured from Google Maps notifications), the
// distance countdown (F5), TFT road names (F6) and the GATT priority lane (F4).

private fun eq(actual: Any?, expected: Any?, what: String) = check(actual == expected) { "$what: got $actual, want $expected" }

private fun parse(title: String?, text: String? = null, sub: String? = "Arrive 4:13 pm", short: String? = null, progress: Int? = null, max: Int? = null) =
    MapsNavigationParser.parse(title, text, sub, short, progress, max)

private fun parser() {
    // Verbatim from notif2.txt (Maps 26.46, ProgressStyle): title, subText, shortCriticalText, progress 0/3389.
    val dump = checkNotNull(parse("120 m · Turn left onto Swami Vidyananda Ln", null, "Arrive 3:45 pm", "120 m", 0, 3389))
    eq(dump.distanceMeters, 120, "dump distance"); eq(dump.remainingMeters, 3389, "dump remaining")
    eq(dump.road, "Swami Vidyananda Ln", "dump road"); eq(dump.tftRoad, "Vidyananda", "dump TFT road")
    eq(dump.turn, NavTurn.LEFT, "dump turn"); eq(dump.arrivalHour24 to dump.arrivalMinute, 15 to 45, "dump arrival")

    // Real titles without any distance (33 of 38 updates in the 2.0 capture): null, so NavCountdown decides.
    for (title in listOf("Head toward Ganesh Nagar Rd", "Turn left onto Ganesh Nagar Rd")) {
        eq(parse(title, progress = 12, max = 3402)?.distanceMeters, null, title)
    }
    eq(parse("Head toward Ganesh Nagar Rd")?.turn, NavTurn.STRAIGHT, "head")
    eq(parse("80 m · Turn right to stay on Ganesh Nagar Rd")?.distanceMeters, 80, "real 80 m")
    eq(parse("50 m · Turn left onto Ganesh Nagar Rd", progress = 0, max = 3402)?.let { it.distanceMeters to it.remainingMeters }, 50 to 3402, "real 50 m")
    // shortCriticalText fills in when the title has no distance.
    eq(parse("Turn left onto Ganesh Nagar Rd", short = "50 m")?.distanceMeters, 50, "short fallback")

    // Distance phrases anywhere in the instruction.
    eq(parse("Continue on NH 48 for 12 km")?.let { Triple(it.distanceMeters, it.road, it.turn) }, Triple(12000, "NH 48", NavTurn.STRAIGHT), "for 12 km")
    eq(parse("Continue straight for 1.2 km")?.distanceMeters, 1200, "for 1.2 km")
    eq(parse("Continue for 1,2 km")?.distanceMeters, 1200, "comma decimal")
    val inPhrase = checkNotNull(parse("In 300 m, turn left onto MG Rd"))
    eq(Triple(inPhrase.distanceMeters, inPhrase.turn, inPhrase.road), Triple(300, NavTurn.LEFT, "MG Rd"), "in 300 m")
    eq(inPhrase.instruction, "Turn left onto MG Rd", "leading phrase stripped")
    eq(parse("After 200 m, keep right")?.let { it.distanceMeters to it.turn }, 200 to NavTurn.KEEP_RIGHT, "after 200 m")
    eq(parse("Turn right after 200 m onto Old Harbor Hwy")?.let { Triple(it.distanceMeters, it.turn, it.road) }, Triple(200, NavTurn.RIGHT, "Old Harbor Hwy"), "after inside")
    eq(parse("Head north for about 400 m")?.distanceMeters, 400, "about")
    eq(MapsNavigationParser.phraseDistance("Turn left after HP Petrol Pump"), null, "after a landmark")
    // The title's leading distance wins over a phrase.
    eq(parse("500 m · Continue on NH 48 for 12 km")?.distanceMeters, 500, "title first")

    // NBSP / narrow NBSP / thin space, comma and period decimals, thousands separators.
    eq(parse("1.2\u00A0km · Turn left onto X")?.distanceMeters, 1200, "nbsp")
    eq(parse("300\u202Fm · Turn left onto X")?.distanceMeters, 300, "narrow nbsp")
    eq(parse("300 m\u2009·\u2009Turn left onto X")?.distanceMeters, 300, "thin spaces around the separator")
    eq(parse("Continue for 2\u00A0km")?.distanceMeters, 2000, "nbsp phrase")
    eq(MapsNavigationParser.distanceMeters("1,200 m"), 1200, "thousands")
    eq(MapsNavigationParser.distanceMeters("1,5 km"), 1500, "comma km")
    eq(MapsNavigationParser.distanceMeters("0.5 mi"), 805, "miles")
    eq(MapsNavigationParser.distanceMeters("5 min"), null, "minutes are not metres")
    eq(MapsNavigationParser.arrival("Arrive 4:13\u202Fpm"), 16 to 13, "arrival narrow nbsp")

    // Rerouting notices still never replace a cue.
    check(parse("Rerouting…")!!.rerouting && parse("Starting navigation…")!!.rerouting)
}

private fun roadNames() {
    val cases = linkedMapOf(
        "Riverside Garden Rd" to "Riverside", // 2.0 TFT showed "Riversid…"
        "Swami Vidyananda Ln" to "Vidyananda",
        "Ganesh Nagar Rd" to "Ganesh",
        "Sri Sai Nagar Rd" to "Sai Nagar",
        "Old Harbor Hwy" to "Harbor",
        "Outer Ring Rd" to "ORR",
        "Outer Ring Road" to "ORR",
        "ORR Service Rd" to "ORR",
        "National Highway 48" to "NH 48",
        "NH 48" to "NH 48",
        "NH65" to "NH65",
        "MG Road" to "MG Rd",
        "Mahatma Gandhi Road" to "Gandhi Rd",
        "Cedar City Main Road" to "Cedar",
        "Lakeside Main Rd" to "Lakeside",
        "5th Main Rd" to "5th Main",
        "1st Cross Road" to "1st Cross",
        "100 Feet Road" to "100 Feet",
        "Church Street" to "Church St",
        "St. Mark's Road" to "Marks Rd",
        "Road No. 12" to "Rd No 12",
        "Maple Hills Rd No 12" to "Rd No 12",
        "Dr. A.P.J. Abdul Kalam Rd" to "Kalam",
        "Sardar Patel Rd" to "Patel Rd",
        "Greenfield - Oakwood Rd" to "Oakwood",
        "Greenfield Rd" to "Greenfield",
        "Rampur Rd" to "Rampur Rd",
        "Rotary Club Rd" to "Rotary",
        "Oakdene" to "Oakdene",
        "Clocktower Circle" to "Clocktower",
        "Śivājī Nagar" to "Sivaji",
        "100% Pure Rd" to "100 Pure",
        "Road" to "Rd",
        "Swami" to "Swami",
        "" to ""
    )
    for ((name, want) in cases) eq(RoadNames.fit(name), want, "fit '$name'")

    // The budget is a parameter (TFT_CHARS until the width test says otherwise).
    eq(RoadNames.TFT_CHARS, 9, "budget")
    eq(RoadNames.fit("Ganesh Nagar Rd", 12), "Ganesh Nagar", "budget 12")
    eq(RoadNames.fit("Riverside Garden Rd", 16), "Riverside", "budget clamps to the 14-byte field")
    eq(RoadNames.fit("Sri Sai Nagar Rd", 14), "Sai Nagar Rd", "budget 14")
    eq(RoadNames.fit("Swami Vidyananda Ln", 13), "Vidyananda Ln", "budget 13 keeps the suffix")
    eq(RoadNames.fit("Chandrasekharapuram Rd"), "Chandrasekhara", "a word longer than the field is cut at 14")

    // Properties: ASCII field-safe, ≤ 14, and never a word cut mid-way unless one word is longer than the budget.
    for (name in cases.keys + listOf("Maple Heights Check Post Rd", "Eastpark - Westerdale Main Rd", "Shri Krishna Colony Rd", "K V Rao Rd")) {
        val fitted = RoadNames.fit(name)
        check(fitted.length <= RoadNames.FIELD_BYTES && fitted.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == ' ' || it == '+' || it == '?' }) { "'$name' → '$fitted'" }
        check(!fitted.startsWith(" ") && !fitted.endsWith(" ") && !fitted.contains("  ")) { "'$name' → '$fitted' spacing" }
        eq(String(NavFrame.roadBytes(fitted), Charsets.US_ASCII).trimEnd('\u0000'), fitted, "'$name' passes the field unchanged")
        if (fitted.length > RoadNames.TFT_CHARS) check(' ' !in fitted) { "'$name' → '$fitted' overflows with more than one word" }
    }

    // The cue's frame carries the fitted road; NavFrame.roadText is exactly what the field holds.
    val frame = checkNotNull(parse("300 m · Turn right onto Riverside Garden Rd")).frame(LocalDateTime.of(2026, 11, 16, 16, 0))
    eq(frame.road, "Riverside", "frame road"); eq(frame.roadText, "Riverside", "roadText")
    eq(String(frame.encode().copyOfRange(13, 27), Charsets.US_ASCII), "Riverside\u0000\u0000\u0000\u0000\u0000", "road bytes")
    eq(NavFrame(true, NavTurn.LEFT, 0, NavTurn.STRAIGHT, 0, 0, 0, 0, "Cedar City Main Road").roadText, "Cedar City Mai", "raw 14-byte cut")
    eq(NavFrame(true, NavTurn.LEFT, 0, NavTurn.STRAIGHT, 0, 0, 0, 0, "MG Road/NH-65").roadText, "MG Road_NH_65", "SDK sanitising kept")
}

private fun thenHints() {
    val now = LocalDateTime.of(2026, 11, 16, 16, 0)
    // (title, text) → current turn, road, then turn, TFT road text.
    data class Case(val title: String, val text: String?, val turn: Int, val road: String, val then: Int?, val tft: String)
    val cases = listOf(
        Case("Turn left onto MG Rd, then turn right", null, NavTurn.LEFT, "MG Rd", NavTurn.RIGHT, "Then R"),
        Case("120 m · Turn left onto Swami Vidyananda Ln then turn right onto 5th Cross", null, NavTurn.LEFT, "Swami Vidyananda Ln", NavTurn.RIGHT, "Then R"),
        Case("80 m · Turn right onto Ganesh Nagar Rd, then turn left", null, NavTurn.RIGHT, "Ganesh Nagar Rd", NavTurn.LEFT, "Then left"),
        Case("Keep right at the fork, then keep left", null, NavTurn.KEEP_RIGHT, "", NavTurn.KEEP_LEFT, "Then kp L"),
        Case("Turn right onto Old Harbor Hwy, then keep right to stay on Old Harbor Hwy", null, NavTurn.RIGHT, "Old Harbor Hwy", NavTurn.KEEP_RIGHT, "Then kp R"),
        Case("Turn left, then make a U-turn", null, NavTurn.LEFT, "", NavTurn.U_TURN_RIGHT, "Then U"),
        Case("Turn left onto Lake View Rd and then make a U\u2011turn", null, NavTurn.LEFT, "Lake View Rd", NavTurn.U_TURN_RIGHT, "Then U"),
        Case("Turn right onto Church St, then at the roundabout, take the 2nd exit onto MG Rd", null, NavTurn.RIGHT, "Church St", NavTurn.ROUNDABOUT_LEFT, "Then rdbt"),
        Case("Slight left onto NH 48, then take the exit toward Airport", null, NavTurn.SLIGHT_LEFT, "NH 48", NavTurn.RAMP_LEFT, "Then exit"),
        Case("Turn left onto Ring Rd, then slight right", null, NavTurn.LEFT, "Ring Rd", NavTurn.SLIGHT_RIGHT, "Then R"),
        Case("Turn left onto MG Rd; then turn sharp left onto Park St", null, NavTurn.LEFT, "MG Rd", NavTurn.SHARP_LEFT, "Then left"),
        // Maps' separate text line under a "distance · instruction" title.
        Case("200 m · Turn left onto MG Rd", "Then turn right", NavTurn.LEFT, "MG Rd", NavTurn.RIGHT, "Then R"),
        // "then" inside a road name, or followed by no maneuver: never a hint, the road stays.
        Case("Turn left onto Thenmozhi St", null, NavTurn.LEFT, "Thenmozhi St", null, "Thenmozhi"),
        Case("Turn right onto Then Kudi Rd", null, NavTurn.RIGHT, "Then Kudi Rd", null, "Then Kudi"),
        Case("Continue on Then Nagar Main Rd for 2 km", null, NavTurn.STRAIGHT, "Then Nagar Main Rd", null, "Then"),
        Case("Turn left onto Swamy Thennarasu Rd, then Lakeview Gardens", null, NavTurn.LEFT, "Swamy Thennarasu Rd", null, "Thennarasu"),
        // A clause without a hint (continue, arrival) is still cut from the road and the turn.
        Case("Turn right onto Church St, then continue on MG Rd", null, NavTurn.RIGHT, "Church St", null, "Church St"),
        Case("Turn left onto Riverside Rd, then your destination will be on the right", null, NavTurn.LEFT, "Riverside Rd", null, "Riverside"),
        Case("Continue on NH 48 for 3 km, then turn left", null, NavTurn.STRAIGHT, "NH 48", NavTurn.LEFT, "Then left"),
        // "then" followed by other wording: no hint, but the clause never leaks into the road.
        Case("120 m · Turn left onto MG Rd then immediately turn right", null, NavTurn.LEFT, "MG Rd", null, "MG Rd"),
        Case("120 m · Turn right onto Church St then follow signs for Airport", null, NavTurn.RIGHT, "Church St", null, "Church St"),
        Case("120 m · Turn left onto MG Rd then right", null, NavTurn.LEFT, "MG Rd", null, "MG Rd"),
        // No then clause: unchanged.
        Case("Turn right onto Riverside Garden Rd", null, NavTurn.RIGHT, "Riverside Garden Rd", null, "Riverside")
    )
    for (c in cases) {
        val cue = checkNotNull(parse(c.title, c.text)) { c.title }
        eq(cue.turn, c.turn, "'${c.title}' turn"); eq(cue.road, c.road, "'${c.title}' road")
        eq(cue.thenTurn, c.then, "'${c.title}' then"); eq(cue.tftRoad, c.tft, "'${c.title}' TFT road")
        // The preview's text is the frame's road field, so the app shows what the scooter shows.
        eq(cue.frame(now).roadText, c.tft, "'${c.title}' frame road")
    }
    eq(parse("Continue for 1.2 km, then turn left")?.distanceMeters, 1200, "the phrase distance still counts")
    eq(parse("At the roundabout, take the 2nd exit, then keep left")?.let { it.turn to it.thenTurn }, NavTurn.ROUNDABOUT_LEFT to NavTurn.KEEP_LEFT, "roundabout then keep left")
    // A clause on the current maneuver only: the turn symbol never comes from the second maneuver.
    eq(parse("Continue straight, then turn left")?.turn, NavTurn.STRAIGHT, "straight then left")
    eq(parse("Head north on Elm St, then turn right")?.let { Triple(it.turn, it.road, it.thenTurn) }, Triple(NavTurn.STRAIGHT, "Elm St", NavTurn.RIGHT), "head then right")

    eq(MapsNavigationParser.splitThen("Turn left onto MG Rd, then turn right · Arrive 4:13 pm"), "Turn left onto MG Rd" to "turn right", "split cuts at the next separator")
    eq(MapsNavigationParser.splitThen("Then keep left"), "" to "keep left", "a lone clause")
    eq(MapsNavigationParser.splitThen("Turn left onto Thenmozhi St"), "Turn left onto Thenmozhi St" to null, "no clause")
    eq(MapsNavigationParser.thenTurn("Then turn right"), null, "a lone clause is not a second maneuver")

    // Same budget rules as road names: the longest wording that fits.
    eq(RoadNames.thenHint(NavTurn.RIGHT, 9), "Then R", "right at 9")
    eq(RoadNames.thenHint(NavTurn.RIGHT, 10), "Then right", "right at 10")
    eq(RoadNames.thenHint(NavTurn.KEEP_LEFT, 11), "Then keep L", "keep left at 11")
    eq(RoadNames.thenHint(NavTurn.KEEP_LEFT, 14), "Then keep left", "keep left at 14")
    eq(RoadNames.thenHint(NavTurn.U_TURN_LEFT, 12), "Then U turn", "U-turn at 12")
    eq(RoadNames.thenHint(NavTurn.ROUNDABOUT_RIGHT, 20), "Then rndabout", "budget clamps to the field")
    eq(RoadNames.thenHint(NavTurn.STRAIGHT), null, "straight has no hint")
    eq(RoadNames.thenHint(NavTurn.DESTINATION), null, "destination has no hint")
    for (turn in 0x42..0x5A) for (budget in 1..16) {
        val hint = RoadNames.thenHint(turn, budget) ?: continue
        check(hint.startsWith("Then ") && hint.length <= RoadNames.FIELD_BYTES && hint.all { it in 'A'..'Z' || it in 'a'..'z' || it == ' ' }) { "hint '$hint'" }
        if (budget >= RoadNames.TFT_CHARS) check(hint.length <= budget) { "hint '$hint' over budget $budget" }
        eq(String(NavFrame.roadBytes(hint), Charsets.US_ASCII).trimEnd('\u0000'), hint, "'$hint' passes the field unchanged")
    }
    // The countdown keys on the road, not the hint: the same maneuver with and without "then" is one anchor.
    eq(NavCountdown.key(checkNotNull(parse("Turn left onto MG Rd, then turn right"))), NavCountdown.key(checkNotNull(parse("Turn left onto MG Rd"))), "countdown key")
}

private fun countdown() {
    fun cue(title: String, progress: Int? = null, max: Int? = null) = checkNotNull(parse(title, progress = progress, max = max)) { title }
    fun d(maneuver: Int, remaining: Int?) = NavCountdown.Distances(maneuver, remaining)

    // Rounding: 10 m steps below 1 km, 0.1 km above, never negative.
    val rounding = listOf(-5.0 to 0, 0.0 to 0, 4.9 to 0, 5.0 to 10, 84.0 to 80, 994.0 to 990, 995.0 to 1000, 1049.0 to 1000, 1050.0 to 1100, 3389.0 to 3400, 12345.0 to 12300)
    for ((metres, want) in rounding) eq(NavCountdown.round(metres), want, "round $metres")

    // Explicit distance (notif2.txt), then GPS distance counts it down at 1 Hz; never below 0.
    val c = NavCountdown()
    eq(c.at(0.0), d(0, null), "before any update")
    c.update(cue("120 m · Turn left onto Swami Vidyananda Ln", 0, 3389), 0.0, 0)
    eq(c.at(0.0), d(120, 3400), "anchor")
    eq(c.at(37.0), d(80, 3400), "37 m later")
    eq(c.at(115.0), d(10, 3300), "115 m later")
    eq(c.at(130.0), d(0, 3300), "past the turn")
    // Maps re-posts the same rounded "120 m" (progress changed): the count goes on while it agrees…
    c.update(cue("120 m · Turn left onto Swami Vidyananda Ln", 20, 3389), 20.0, 1_000)
    eq(c.at(20.0), d(100, 3400), "same text keeps counting")
    // …a new Maps value restarts it…
    c.update(cue("100 m · Turn left onto Swami Vidyananda Ln", 22, 3389), 22.0, 2_000)
    eq(c.at(30.0), d(90, 3400), "new Maps value")
    // …and an update without a distance for the same maneuver keeps counting (2.0 sent 0 m).
    c.update(cue("Turn left onto Swami Vidyananda Ln", 60, 3389), 60.0, 3_000)
    eq(c.at(60.0), d(60, 3300), "same maneuver, no distance")
    // A count that drifted outside Maps' rounding window is corrected by Maps.
    c.update(cue("100 m · Turn left onto Swami Vidyananda Ln", 61, 3389), 61.0, 4_000)
    eq(c.at(61.0), d(100, 3300), "drift corrected")

    // The real stationary sequence (logcat.txt 16:02:25–16:02:50): Maps flips between two instructions.
    val r = NavCountdown()
    val real = listOf(
        Triple("Head toward Ganesh Nagar Rd", 0, d(3400, 3400)),
        Triple("Head toward Ganesh Nagar Rd", 11, d(3400, 3400)),
        Triple("Turn left onto Ganesh Nagar Rd", 12, d(0, 3400)), // new turn, no distance: now
        Triple("Head toward Ganesh Nagar Rd", 11, d(3400, 3400)),
        Triple("50 m · Turn left onto Ganesh Nagar Rd", 0, d(50, 3400)),
        Triple("Head toward Ganesh Nagar Rd", 7, d(3400, 3400)),
        Triple("Turn left onto Ganesh Nagar Rd", 7, d(50, 3400)) // recalled: 2.0 sent 0 m here
    )
    real.forEachIndexed { i, (title, progress, want) ->
        r.update(cue(title, progress, 3402), 0.0, i * 2_000L)
        eq(r.at(0.0), want, "real #$i $title")
    }

    // No distance: a new STRAIGHT or DESTINATION uses the remaining route; any other new maneuver is 0.
    val s = NavCountdown()
    s.update(cue("Continue onto Old Harbor Hwy", 1000, 3402), 0.0, 0)
    eq(s.at(0.0), d(2400, 2400), "straight → remaining")
    eq(s.at(150.0), d(2300, 2300), "straight counts down with the route")
    s.update(cue("Your destination will be on the right", 3350, 3402), 160.0, 1_000)
    eq(s.at(160.0), d(50, 50), "destination → remaining")
    s.update(cue("Turn right onto MG Rd", 3360, 3402), 170.0, 2_000)
    eq(s.at(170.0), d(0, 40), "new turn without distance → 0")
    // A STRAIGHT with a turn right after it and no distance: the turn is imminent, so 0, not the route.
    s.update(cue("Continue straight, then turn left", 3370, 3402), 180.0, 3_000)
    eq(s.at(180.0), d(0, 30), "straight then turn without distance → 0")
    // Without progress there is no remaining distance, and STRAIGHT falls back to 0.
    val n = NavCountdown()
    n.update(cue("Head toward Ganesh Nagar Rd"), 0.0, 0)
    eq(n.at(0.0), d(0, null), "no progress")

    // Remaining restarts from Maps on every update and counts down in between.
    val m = NavCountdown()
    m.update(cue("500 m · Turn right onto Riverside Garden Rd", 1000, 3402), 0.0, 0)
    eq(m.at(102.0), d(400, 2300), "between updates")
    m.update(cue("400 m · Turn right onto Riverside Garden Rd", 1150, 3402), 102.0, 10_000)
    eq(m.at(102.0), d(400, 2300), "remaining from Maps")

    // A passed maneuver is forgotten: the same key later without a distance is a new one (0).
    val p = NavCountdown()
    p.update(cue("50 m · Turn left onto Ganesh Nagar Rd", 0, 3402), 0.0, 0)
    p.update(cue("300 m · Turn right onto MG Rd", 60, 3402), 60.0, 1_000)
    p.update(cue("Turn left onto Ganesh Nagar Rd", 300, 3402), 300.0, 2_000)
    eq(p.at(300.0).maneuverM, 0, "passed maneuver forgotten")
    // Forgotten after 10 min too.
    val t = NavCountdown()
    t.update(cue("50 m · Turn left onto X Rd"), 0.0, 0)
    t.update(cue("Turn left onto X Rd"), 0.0, NavCountdown.FORGET_MS + 1)
    eq(t.at(0.0).maneuverM, 0, "stale maneuver forgotten")
    t.reset()
    eq(t.at(0.0), d(0, null), "reset")

    // A ride: 10 m/s for 30 s after "500 m"; the frame sent at 1 Hz counts down in 10 m steps.
    val ride = NavCountdown()
    val odo = NavOdometer()
    val start = checkNotNull(parse("500 m · Turn right onto Riverside Garden Rd", sub = "Arrive 4:13 pm", progress = 1000, max = 3402))
    var lat = 45.0
    odo.add(0, lat, 7.0, 5.0, 10.0, gps = true)
    ride.update(start, odo.totalM, 0)
    val shown = mutableListOf<Int>()
    for (second in 1..30) {
        lat += 10.0 / 111_194.93
        odo.add(second * 1_000L, lat, 7.0, 5.0, 10.0, gps = true)
        val now = ride.at(odo.totalM)
        val frame = start.frame(LocalDateTime.of(2026, 11, 16, 16, 0, second), now.maneuverM, now.remainingM)
        shown += frame.distanceMeters
        val bytes = frame.encode()
        eq(((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF), frame.distanceMeters, "wire distance $second")
        eq(frame.roadText, "Riverside", "ride road")
    }
    eq(shown.first(), 490, "first tick"); eq(shown.last(), 200, "after 300 m")
    check(shown.zipWithNext().all { (a, b) -> b <= a } && shown.all { it % 10 == 0 }) { "monotonic 10 m steps: $shown" }
}

private fun odometer() {
    val step = 10.0 / 111_194.93 // 10 m of latitude
    // Stationary jitter (GPS wanders 2–4 m, no speed): nothing is added.
    val still = NavOdometer()
    listOf(0.0, 3e-5, -2e-5, 1e-5, -3e-5).forEachIndexed { i, wobble -> still.add(i * 1_000L, 45.0 + wobble, 7.0, 8.0, 0.2, gps = true) }
    eq(still.totalM, 0.0, "jitter")
    // Moving at 10 m/s.
    val moving = NavOdometer()
    for (i in 0..10) moving.add(i * 1_000L, 45.0 + i * step, 7.0, 5.0, 10.0, gps = true)
    check(kotlin.math.abs(moving.totalM - 100.0) < 0.5) { "moving: ${moving.totalM}" }
    // Poor accuracy and out-of-order fixes are dropped.
    val poor = NavOdometer()
    poor.add(1_000, 45.0, 7.0, 5.0, 10.0, gps = true)
    check(!poor.add(2_000, 45.0 + step, 7.0, 45.0, 10.0, gps = true)) // accuracy 45 m
    check(!poor.add(2_000, 45.0 + step, 7.0, Double.NaN, 10.0, gps = true)) // no accuracy
    check(!poor.add(500, 45.0 + step, 7.0, 5.0, 10.0, gps = true)) // older than the last fix
    check(!poor.add(1_000, 45.0 + step, 7.0, 5.0, 10.0, gps = true)) // same time
    eq(poor.totalM, 0.0, "dropped")
    // Network fixes are ignored while GPS is fresh, and switching source re-anchors without distance.
    val mixed = NavOdometer()
    mixed.add(0, 45.0, 7.0, 5.0, 10.0, gps = true)
    check(!mixed.add(2_000, 45.0 + 5 * step, 7.0, 20.0, Double.NaN, gps = false))
    check(!mixed.add(8_000, 45.0 + 6 * step, 7.0, 20.0, Double.NaN, gps = false)) // GPS silent: new network leg, no distance yet
    check(mixed.add(14_000, 45.0 + 9 * step, 7.0, 20.0, Double.NaN, gps = false)) // 30 m > accuracy
    check(kotlin.math.abs(mixed.totalM - 30.0) < 0.5) { "mixed: ${mixed.totalM}" }
    // Impossible jumps are rejected; a run of them restarts from the new position.
    val jumpy = NavOdometer()
    jumpy.add(0, 45.0, 7.0, 5.0, 10.0, gps = true)
    for (i in 1..4) check(!jumpy.add(i * 1_000L, 45.01, 7.0, 5.0, 10.0, gps = true)) // ~1.1 km in 1 s
    check(!jumpy.add(5_000, 45.01, 7.0, 5.0, 10.0, gps = true)) // fifth: re-anchored there
    check(jumpy.add(6_000, 45.01 + step, 7.0, 5.0, 10.0, gps = true))
    check(kotlin.math.abs(jumpy.totalM - 10.0) < 0.5) { "jumpy: ${jumpy.totalM}" }
    // pause() keeps the total; the gap is not counted.
    jumpy.pause()
    check(!jumpy.add(60_000, 45.02, 7.0, 5.0, 10.0, gps = true))
    check(kotlin.math.abs(jumpy.totalM - 10.0) < 0.5) { "paused: ${jumpy.totalM}" }
    jumpy.reset(); eq(jumpy.totalM, 0.0, "reset")
    check(kotlin.math.abs(NavOdometer.haversineM(45.0, 7.0, 45.0 + step, 7.0) - 10.0) < 0.01)
}

private fun priorityLane() {
    class Op(val name: String, val lane: Boolean = false, val marker: Boolean = false) { override fun toString() = name }
    fun insert(queue: List<Op>, op: Op, running: Op?) = PriorityLane.insert(queue, op, running, { it.lane }, { it.marker })
    fun names(ops: List<Op>) = ops.joinToString(" ")
    val status = Op("0301"); val music = Op("0304"); val heartbeat = Op("0103")
    val nav1 = Op("nav1", lane = true); val nav2 = Op("nav2", lane = true); val marker = Op("marker", marker = true)
    eq(names(insert(listOf(status, music, heartbeat), nav1, status)), "0301 nav1 0304 0103", "right after the running op")
    eq(names(insert(listOf(music, heartbeat), nav1, null)), "nav1 0304 0103", "first when idle")
    eq(names(insert(listOf(status, music, nav1, heartbeat), nav2, status)), "0301 nav2 0304 0103", "stale frame replaced")
    eq(names(insert(listOf(nav1, heartbeat, music), nav2, nav1)), "nav1 0103 nav2 0304", "running frame kept; one other op first")
    eq(names(insert(listOf(nav1), nav2, nav1)), "nav1 nav2", "after the running frame")
    eq(names(insert(listOf(status, marker, music), nav1, status)), "0301 marker nav1 0304", "never ahead of a marker")
    // A new frame before every completion: other ops still run, the heartbeat within the first ops.
    var queue = listOf(status, heartbeat, music)
    val done = mutableListOf<String>()
    for (i in 0 until 12) {
        queue = insert(queue, Op("nav$i", lane = true), queue.firstOrNull())
        done += queue.first().name
        queue = queue.drop(1)
    }
    check(done.indexOf("0103") in 0..3 && done.contains("0304") && done.contains("0301")) { "starved: $done" }
}

fun main() {
    parser()
    roadNames()
    thenHints()
    countdown()
    odometer()
    priorityLane()
    println("Navigation checks passed (parser, road names, then hints, countdown, odometer, priority lane).")
}
