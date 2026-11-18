package dev.codesurgen.ruta

private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
private fun u16(value: Int) = intArrayOf(value shr 8 and 0xFF, value and 0xFF)

fun main() {
    val now = 1_000_000L
    // Encoders match the documented frames byte-for-byte (docs/PROTOCOL.md).
    check(Tcu.heartbeat(1).contentEquals(bytes(0x00, 0x01)))
    check(Tcu.heartbeat(0x1234).contentEquals(bytes(0x12, 0x34)))
    check(Tcu.heartbeatValue(bytes(0x01, 0x02)) == 0x0102)
    check(Tcu.heartbeatValue(bytes(0x01)) == 0) // Short heartbeats count as 0 and still get a reply.
    check(Tcu.heartbeatValue(bytes(0, 0, 1, 2)) == 0x0102)
    check(Tcu.statsRequest(1, true, 0).contentEquals(bytes(1, 1, 0, 0)))
    check(Tcu.statsRequest(1, false, 200).contentEquals(bytes(1, 0, 0, 0xC8)))
    check(Tcu.BMS_PERIODS_MS.all { Tcu.statsRequest(1, true, it)[2] == 0.toByte() })
    check(runCatching { Tcu.statsRequest(1, true, 150) }.isFailure)
    check(runCatching { Tcu.statsRequest(1, true, 1000) }.isFailure) // Only the low byte would be carried (0xE8).
    check(Tcu.TCU_INFO.toString() == "00000101-0000-1000-8000-00805f9b34fb")

    // BMS frame: tag 1, then big-endian fields in the order documented in docs/PROTOCOL.md.
    val frame = intArrayOf(1) + u16(95) + u16(81) + u16(31) + intArrayOf(0x12, 0x34, 0x56) +
        u16(96) + u16(80) + u16(79) + u16(30) + u16(94) + u16(82) + u16(81) + u16(32)
    val bms = TcuParser.bms(bytes(*frame))!!
    check(bms.soh == 95 && bms.soc == 81 && bms.packTemp == 31)
    check(bms.chargeTimeRaw == 0x123 && bms.bmsStatus == 4 && bms.pack1Status == 5 && bms.pack2Status == 6)
    check(bms.pack1Soc == 80 && bms.pack2Soc == 82 && bms.pack2Temp == 32)
    check(TcuParser.bms(bytes(*frame.copyOf(25))) == null) // SDK length check is off by one.
    check(TcuParser.bms(bytes(*(intArrayOf(13) + frame.drop(1)))) == null)

    fun decode(decoder: BmsDecoder, f: BmsFrame, at: Long = now) = decoder.readings(f, at).associateBy { it.pack to it.metric }
    val whole = BmsDecoder()
    val readings = decode(whole, bms)
    check(readings[1 to Metric.SOC]?.value == 80.0 && readings[2 to Metric.SOC]?.value == 82.0)
    check(readings[0 to Metric.HEALTH]?.value == 95.0 && readings[1 to Metric.TEMPERATURE]?.value == 30.0)
    check(whole.percentDivisor == null && whole.temperatureDivisor == null) // One frame never latches.
    repeat(BmsDecoder.LATCH_FRAMES - 1) { decode(whole, bms) }
    check(whole.percentDivisor == 1 && whole.temperatureDivisor == 1) // SOH 50..100 and temps 20..60.

    // A latched scale never moves: a stray field (e.g. 0xFF-ish SOE or 0x0FFF) is dropped alone.
    val stray = decode(whole, bms.copy(pack2Soe = 300, pack1Soh = 4000))
    check(whole.percentDivisor == 1 && stray[2 to Metric.ENERGY] == null && stray[1 to Metric.HEALTH] == null && stray[1 to Metric.SOC]?.value == 80.0)
    check(decode(whole, bms.copy(pack1Temp = 200))[1 to Metric.TEMPERATURE] == null && whole.temperatureDivisor == 1)

    // Tenths: SOH 500..1000 votes ÷10; once latched, a low-SOC frame without SOH stays ÷10.
    val tenths = BmsDecoder()
    val tenthsFrame = bms.copy(soc = 812, pack1Soc = 805, pack2Soc = 820, soh = 950, pack1Soh = 960, pack2Soh = 940, pack1Soe = 790, pack2Soe = 810)
    check(decode(tenths, tenthsFrame)[1 to Metric.SOC]?.value == 80.5) // Current vote is used before latching.
    repeat(BmsDecoder.LATCH_FRAMES - 1) { decode(tenths, tenthsFrame) }
    check(tenths.percentDivisor == 10)
    // (A minute later: an 80 → 8.5 % move within 60 s would be held back as a spike, see bmsPlausibility.)
    check(decode(tenths, bms.copy(soc = 90, pack1Soc = 85, pack2Soc = 95, soh = 0, pack1Soh = 0, pack2Soh = 0, pack1Soe = 0, pack2Soe = 0), now + 61_000)[1 to Metric.SOC]?.value == 8.5)
    check(BmsDecoder(percentDivisor = 10).percentDivisor == 10 && BmsDecoder(percentDivisor = 7).percentDivisor == null)
    // Disagreeing votes restart the streak.
    val mixed = BmsDecoder()
    decode(mixed, tenthsFrame); decode(mixed, tenthsFrame); decode(mixed, bms); decode(mixed, tenthsFrame)
    check(mixed.percentDivisor == null)

    // Absent pack (sentinels/zeros) emits nothing for that pack; all-zero frames are ignored.
    val single = decode(BmsDecoder(), bms.copy(pack2Soc = 0xFFFF, pack2Soh = 0xFFFF, pack2Soe = 0xFFFF, pack2Temp = 0))
    check(single[1 to Metric.SOC]?.value == 80.0 && single[0 to Metric.SOC]?.value == 81.0)
    check(single.keys.none { it.first == 2 })
    check(decode(BmsDecoder(), bms.copy(pack1Soh = 0))[1 to Metric.HEALTH] == null)
    check(decode(BmsDecoder(), bms.copy(soc = 60000))[0 to Metric.SOC] == null)
    val zero = BmsFrame(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
    check(BmsDecoder().readings(zero, now).isEmpty())

    // Temperature: > 85 is impossible in whole °C, so the frame votes 0.1 °C for all packs.
    val cold = BmsDecoder()
    val coldFrame = bms.copy(packTemp = 305, pack1Temp = 100, pack2Temp = 0xFFF6)
    val coldReadings = decode(cold, coldFrame)
    check(coldReadings[0 to Metric.TEMPERATURE]?.value == 30.5 && coldReadings[1 to Metric.TEMPERATURE]?.value == 10.0 && coldReadings[2 to Metric.TEMPERATURE]?.value == -1.0)
    repeat(BmsDecoder.LATCH_FRAMES - 1) { decode(cold, coldFrame) }
    check(cold.temperatureDivisor == 10)
    // Ambiguous temperatures (no vote, nothing latched) are hidden rather than guessed.
    check(decode(BmsDecoder(), bms.copy(packTemp = 70, pack1Temp = 75, pack2Temp = 72)).keys.none { it.second == Metric.TEMPERATURE })
    check(decode(BmsDecoder(temperatureDivisor = 1), bms.copy(pack1Temp = 0xFFF6))[1 to Metric.TEMPERATURE]?.value == -10.0)

    // Temperatures in 0.01 °C with a −40 °C offset (VX2 live: 0x1B12/0x1AEA at ~29 °C ambient).
    val hundredths = BmsDecoder()
    val warm = bms.copy(packTemp = 6930, pack1Temp = 6890, pack2Temp = 6930)
    check(decode(hundredths, warm).let { it[0 to Metric.TEMPERATURE]?.value == 29.3 && it[1 to Metric.TEMPERATURE]?.value == 28.9 })
    repeat(BmsDecoder.LATCH_FRAMES - 1) { decode(hundredths, warm) }
    check(hundredths.temperatureDivisor == 100 && BmsDecoder(temperatureDivisor = 100).temperatureDivisor == 100)

    // Turn-by-turn frame (0x0303), exactly as TbtData.toByteArray lays it out.
    val road14 = "Swami Vidyanan".toByteArray().map { it.toInt() }.toIntArray()
    val left = NavFrame(true, NavTurn.LEFT, 120, NavTurn.STRAIGHT, 15, 45, 3389, 12, "Swami Vidyananda Ln").encode()
    check(left.contentEquals(bytes(0x35, 45, 0x00, 0x78, 0x49, 0x10, 0, 0x47, 0x00, 0x21, 12, 0, 0, *road14)))
    val far = NavFrame(true, NavTurn.RIGHT, 1250, NavTurn.STRAIGHT, 9, 5, 20000, 75, "").encode()
    check(far[0] == 0x94.toByte() && far[2] == 0.toByte() && far[3] == 12.toByte() && far[5] == 0.toByte() && far[6] == 1.toByte() && far[10] == 15.toByte() && far[8] == 0.toByte() && far[9] == 200.toByte())
    check(NavFrame.EXIT.encode().contentEquals(bytes(0xC1, 0, 0, 0, 0x47, 0, 0, 0x47, 0, 0, 0, 0, 0, *IntArray(14))))
    check(String(NavFrame.roadBytes("MG Road/NH-65"), Charsets.US_ASCII) == "MG Road_NH_65\u0000")
    check(NavFrame.roadBytes("Cedar City Main Road").size == 14 && NavFrame(true, 0x47, 0, 0x47, 0, 0, 0, 0, "").encode().size == 27)

    // Phone status (0x0301): connected bit, battery bars, signal, rtcSync + local time.
    check(PhoneStatus(75, 4, 15, 30, 20).encode().contentEquals(bytes(0x1C, 0x04, 0x29, 30, 15, 0, 0, 0)))
    check(PhoneStatus(null, 9, 0, 0, 0).encode()[0] == 0x24.toByte() && PhoneStatus(10, 2, 0, 0, 0).encode()[0] == 0x04.toByte())

    // Google Maps notification parsing — first sample has the shape of a real Maps 26.46 notification.
    val maps = MapsNavigationParser.parse("120 m · Turn left onto Swami Vidyananda Ln", null, "Arrive 3:45 pm", "120 m", 0, 3389)!!
    check(maps.turn == NavTurn.LEFT && maps.distanceMeters == 120 && maps.road == "Swami Vidyananda Ln")
    check(maps.arrivalHour24 == 15 && maps.arrivalMinute == 45 && maps.remainingMeters == 3389 && !maps.rerouting)
    check(maps.frame(java.time.LocalDateTime.of(2026, 11, 16, 15, 33)).remainingMinutes == 12)
    // 2.1 (F6): the frame carries the TFT-fitted road name (RoadNames.fit), otherwise the same bytes.
    check(maps.frame(java.time.LocalDateTime.of(2026, 11, 16, 15, 33)).encode()
        .contentEquals(NavFrame(true, NavTurn.LEFT, 120, NavTurn.STRAIGHT, 15, 45, 3389, 12, maps.tftRoad).encode()))
    fun turn(title: String) = MapsNavigationParser.parse(title, null, null, null, null, null)
    check(turn("1.2 km · Slight right toward Greenfield Rd")!!.let { it.turn == NavTurn.SLIGHT_RIGHT && it.distanceMeters == 1200 && it.road == "Greenfield Rd" })
    check(turn("300 m · At the roundabout, take the 2nd exit onto ORR")!!.let { it.turn == NavTurn.ROUNDABOUT_LEFT && it.road == "ORR" })
    check(turn("50 m · Make a U-turn")!!.turn == NavTurn.U_TURN_RIGHT)
    check(turn("200 m · Head north on Old Harbor Hwy toward Greenfield Rd")!!.let { it.turn == NavTurn.STRAIGHT && it.road == "Old Harbor Hwy" })
    check(turn("80 m · Turn right to stay on NH65")!!.let { it.turn == NavTurn.RIGHT && it.road == "NH65" })
    check(turn("20 m · Your destination will be on the right")!!.let { it.turn == NavTurn.DESTINATION && it.road == "" })
    check(turn("400 m · Keep left at the fork")!!.turn == NavTurn.KEEP_LEFT)
    check(turn("1 km · Take the exit on the right toward ORR")!!.turn == NavTurn.RAMP_RIGHT)
    check(turn("Rerouting…")!!.rerouting)
    check(turn("Greenfield Stadium") == null)
    check(MapsNavigationParser.parse("200 m", "Turn right onto Main St", "Arrive 15:45", null, null, null)!!.let { it.turn == NavTurn.RIGHT && it.arrivalHour24 == 15 })
    check(MapsNavigationParser.arrival("Arrive 12:05 am") == (0 to 5) && MapsNavigationParser.arrival("Arrive 12:05 pm") == (12 to 5))
    val lateNight = MapsNavigationParser.parse("100 m · Turn left onto X", null, "Arrive 12:10 am", null, null, null)!!
    check(lateNight.frame(java.time.LocalDateTime.of(2026, 11, 16, 23, 50)).remainingMinutes == 20)

    // Parser regression: only the maneuver phrase picks the symbol; names and side notes never do.
    fun expect(title: String, symbol: Int, road: String? = null) {
        val c = checkNotNull(turn(title)) { "unparsed: $title" }
        check(c.turn == symbol && (road == null || c.road == road)) { "$title → 0x${c.turn.toString(16)} '${c.road}'" }
    }
    // Real titles from the 1.0.4 phone test (Maps 26.46).
    val real = MapsNavigationParser.parse("Turn left onto Ganesh Nagar Rd", null, "Arrive 4:13 pm", null, 20, 3402)!!
    check(real.turn == NavTurn.LEFT && real.distanceMeters == null && real.road == "Ganesh Nagar Rd")
    check(real.arrivalHour24 == 16 && real.arrivalMinute == 13 && real.remainingMeters == 3382 && !real.rerouting)
    expect("Head toward Ganesh Nagar Rd", NavTurn.STRAIGHT, "Ganesh Nagar Rd")
    expect("80 m · Turn right to stay on Ganesh Nagar Rd", NavTurn.RIGHT, "Ganesh Nagar Rd")
    expect("120 m · Turn left onto Swami Vidyananda Ln", NavTurn.LEFT, "Swami Vidyananda Ln")
    check(turn("Starting navigation…")!!.let { it.rerouting && it.distanceMeters == null })
    check(listOf("Rerouting…", "Finding route…", "Searching for GPS", "GPS signal lost").all { turn(it)!!.rerouting })
    // Ramp/rotary/ferry/merge/fork/destination words inside names.
    expect("120 m · Turn left toward Rampur", NavTurn.LEFT, "Rampur")
    expect("Turn left onto Rampart Rd", NavTurn.LEFT, "Rampart Rd")
    expect("Turn left at Rampur Chowrasta", NavTurn.LEFT)
    expect("Turn right onto Rotary Club Rd", NavTurn.RIGHT, "Rotary Club Rd")
    expect("At Rotary Circle, turn left onto MG Rd", NavTurn.LEFT, "MG Rd")
    expect("Continue onto Rotary Rd", NavTurn.STRAIGHT, "Rotary Rd")
    expect("Turn right toward Ferry Ghat Rd", NavTurn.RIGHT, "Ferry Ghat Rd")
    expect("Turn left onto Destination Mall Rd", NavTurn.LEFT, "Destination Mall Rd")
    expect("Turn left onto Merge Rd", NavTurn.LEFT, "Merge Rd")
    expect("Turn right onto Fork Rd", NavTurn.RIGHT, "Fork Rd")
    expect("Stay on NH 48", NavTurn.STRAIGHT, "NH 48")
    expect("Merge onto NH 48 via the ramp to Northgate", NavTurn.MERGE, "NH 48")
    expect("Take the ferry", NavTurn.FERRY)
    // Structural phrases survive the name cut.
    expect("300 m · At the roundabout, take the 2nd exit onto Rampur Rd", NavTurn.ROUNDABOUT_LEFT, "Rampur Rd")
    expect("Take the ramp on the right", NavTurn.RAMP_RIGHT, "")
    expect("Take the ramp on the left to Outer Ring Rd", NavTurn.RAMP_LEFT, "Outer Ring Rd")
    expect("Your destination will be on the right", NavTurn.DESTINATION, "")
    // U-turns: clockwise (India) unless the maneuver itself says left.
    expect("50 m · Make a U-turn after Bank of Baroda (on the left)", NavTurn.U_TURN_RIGHT, "")
    expect("50 m · Make a U-turn at Leftie's Cafe", NavTurn.U_TURN_RIGHT)
    expect("50 m · Make a left U-turn", NavTurn.U_TURN_LEFT)
    // Arrival wording.
    expect("20 m · Lakeside Mall is on the left", NavTurn.DESTINATION, "")
    expect("Arrived at Lakeside Mall", NavTurn.DESTINATION, "")
    expect("Arrive at Rampur Chowrasta", NavTurn.DESTINATION)
    // Exits.
    expect("800 m · Take exit 12 toward Oakdene", NavTurn.RAMP_LEFT, "Oakdene")
    expect("1.2 km · Take the ORR exit", NavTurn.RAMP_LEFT)
    expect("Take the exit toward Greenfield", NavTurn.RAMP_LEFT, "Greenfield")
    expect("1 km · Take the exit on the right toward ORR", NavTurn.RAMP_RIGHT, "ORR")
    expect("Take the exit on the left toward Greenfield", NavTurn.RAMP_LEFT, "Greenfield")
    expect("1.5 km · Use the right lane to take the Outer Ring Rd exit toward Greenfield", NavTurn.RAMP_RIGHT, "Greenfield")
    // Road field: side notes, aliases and trailing clauses dropped.
    expect("Turn right after HP Petrol Pump (on the left)", NavTurn.RIGHT, "")
    expect("Pass by HP Petrol Pump (on the left)", NavTurn.STRAIGHT, "")
    expect("Turn right onto NH 48/Northgate - Southport Hwy", NavTurn.RIGHT, "NH 48")
    expect("Turn right onto Inner Ring Rd/Outer Ring Rd", NavTurn.RIGHT, "Inner Ring Rd")
    expect("Keep left to continue on NH 48, follow signs for Northgate", NavTurn.KEEP_LEFT, "NH 48")
    expect("Continue on NH 48 for 12 km", NavTurn.STRAIGHT, "NH 48")
    expect("Keep left to take the exit", NavTurn.KEEP_LEFT, "")
    // '%' in Maps text is data, never a format string.
    val percent = turn("200 m · Turn left onto 100% Pure Rd")!!
    check(percent.turn == NavTurn.LEFT && percent.road == "100% Pure Rd")
    check(MapsNavigationParser.describe(percent, "50% off | %s %d") == "Maps: 50% off | %s %d → turn 0x49, 200 m, road '100% Pure Rd'")
    check(MapsNavigationParser.describe(null, "100% %n") == "Maps (unparsed): 100% %n")
    // Kilometres round to the nearest metre: 32.3 km is 323 tenths on the wire, not 322.
    check(MapsNavigationParser.distanceMeters("32.3 km") == 32300 && MapsNavigationParser.distanceMeters("64.1 km") == 64100)
    val far32 = turn("32.3 km · Continue on NH48")!!.frame(java.time.LocalDateTime.of(2026, 11, 16, 12, 0)).encode()
    check(far32[2] == 0x01.toByte() && far32[3] == 0x43.toByte())

    // Vehicle state and TCU info.
    val state = TcuParser.vehicleState(bytes(0b1000_0100, 0, 0x30, 0x52, 60, 0), now)!!
    check(state.ignitionOn && !state.immobilised && state.handleLocked && state.rideMode == 3 && state.regenLevel == 5 && state.accelerationLevel == 2 && state.speedLimit == 60)
    check(TcuParser.vehicleState(bytes(0, 0, 0, 0, 0), now) == null)
    val info = ("000000000000001" + "MBLTEST0000000001").toByteArray(Charsets.US_ASCII) + bytes(1, 2, 3, 0, 1, 4, 0) + "B".toByteArray() + "PART-1234567  ".take(13).toByteArray()
    val parsed = TcuParser.info(info)!!
    check(parsed.imei == "000000000000001" && parsed.vin == "MBLTEST0000000001" && parsed.firmware == "1.2.3" && parsed.bleProtocol == "1.4.0")
    check(TcuParser.info(info.copyOf(52)) == null)

    val model = BatteryAnalytics()
    check(model.imbalance(now) == null)
    model.accept(Reading(1, Metric.SOC, 80.0, now)); model.accept(Reading(2, Metric.SOC, 64.0, now))
    check(model.imbalance(now) == 16.0)
    check(model.fresh(1, Metric.SOC, now + 90_001) == null)
    model.clear(); check(model.reading(1, Metric.SOC) == null)

    // Parser regression 2 (verifier findings for 1.0.6)
    check(turn("300 m · At Clocktower Circle, take the 2nd exit onto Old Harbor Hwy")!!.let { it.turn == NavTurn.ROUNDABOUT_LEFT && it.road == "Old Harbor Hwy" })
    check(turn("2 km · Take exit 12 toward Oakdene")!!.turn == NavTurn.RAMP_LEFT)
    check(turn("50 m · Lakeside Mall will be on the right")!!.let { it.turn == NavTurn.DESTINATION && it.road == "" })
    check(MapsNavigationParser.parse("You’ve arrived", null, null, null, null, null)!!.turn == NavTurn.DESTINATION)
    check(turn("1 km · Continue to follow Ferry Ghat Rd")!!.let { it.turn == NavTurn.STRAIGHT && it.road == "Ferry Ghat Rd" })
    check(turn("400 m · Take the ramp to Right Bank Canal Rd")!!.turn == NavTurn.RAMP_LEFT)
    check(turn("80 m · Turn left to merge onto NH 48")!!.turn == NavTurn.LEFT)
    check(MapsNavigationParser.parse("Turn right onto MG Rd", "Arrive 4:13\u202Fpm", "Arrive 4:13\u202Fpm", null, null, null)!!.let { it.road == "MG Rd" && it.arrivalHour24 == 16 })
    check(MapsNavigationParser.parse("120\u00A0m · Turn left onto X", null, null, null, null, null)!!.distanceMeters == 120)

    // Link/decoder regression (1.0.4 live test, TCU 0.2.88).
    // Stats: period 200 streams (~2 Hz); 0 is a one-shot snapshot, so 200 goes first.
    check(Tcu.BMS_PERIODS_MS == listOf(200L, 0L) && Tcu.statsRequest(Tcu.TAG_BMS, true, Tcu.BMS_PERIODS_MS[0]).contentEquals(bytes(1, 1, 0, 0xC8)))
    // Real 30-byte BMS frames: SOC 57.81 % (display 57 %), SOH 99.45 %, temps raw/100 − 40 °C.
    fun hexFrame(hex: String) = TcuParser.bms(bytes(*hex.split(" ").map { it.toInt(16) }.toIntArray()))!!
    val liveFrame = hexFrame("01 26 D9 16 95 1B 12 00 04 44 26 DB 16 95 13 23 1A EA 26 D7 16 96 13 26 1B 12 00 00 00 00")
    val live = BmsDecoder()
    val liveReadings = decode(live, liveFrame) // The first frame votes, so values show before latching.
    check(liveReadings[0 to Metric.SOC]?.value == 57.81 && liveReadings[0 to Metric.HEALTH]?.value == 99.45 && liveReadings[1 to Metric.ENERGY]?.value == 48.99)
    check(liveReadings[0 to Metric.TEMPERATURE]?.value == 29.3 && liveReadings[1 to Metric.TEMPERATURE]?.value == 28.9 && liveReadings[2 to Metric.TEMPERATURE]?.value == 29.3)
    repeat(BmsDecoder.LATCH_FRAMES - 1) { decode(live, liveFrame) }
    check(live.percentDivisor == 100 && live.temperatureDivisor == 100)
    // A live frame with an empty pack temperature (raw 0 = −40 °C) drops that value only, and never votes.
    val emptyTemp = hexFrame("01 26 D9 16 94 00 00 00 04 44 26 DB 16 94 13 22 1A EA 26 D7 16 95 13 25 1B 12 00 00 00 00")
    check(decode(live, emptyTemp).let { it[0 to Metric.TEMPERATURE] == null && it[1 to Metric.TEMPERATURE]?.value == 28.9 && it[0 to Metric.SOC]?.value == 57.8 })
    check(decode(BmsDecoder(percentDivisor = 100), emptyTemp).keys.none { it.second == Metric.TEMPERATURE })
    // A ÷100 latch restored from 1.0.4 prefs now reads with the offset; ÷10 and ÷1 have none.
    check(decode(BmsDecoder(100, 100), liveFrame)[2 to Metric.TEMPERATURE]?.value == 29.3)
    check(decode(BmsDecoder(100, 100), bms.copy(pack1Temp = 2850))[1 to Metric.TEMPERATURE]?.value == -11.5)
    check(decode(BmsDecoder(temperatureDivisor = 10), bms.copy(pack1Temp = 305))[1 to Metric.TEMPERATURE]?.value == 30.5)
    // 2.1: pack 1 sends a raw 0x0028 placeholder (−39.6 °C in ÷100); anything below −30 °C there is empty.
    val placeholder = liveFrame.copy(pack1Temp = 0x0028)
    check(decode(BmsDecoder(100, 100), placeholder).let { r -> r.keys.none { it.first == 1 } && r[0 to Metric.TEMPERATURE]?.value == 29.3 && r[2 to Metric.TEMPERATURE]?.value == 29.3 })
    check(decode(BmsDecoder(100, 100), liveFrame.copy(pack1Temp = 999))[1 to Metric.TEMPERATURE] == null) // −30.01 °C
    check(decode(BmsDecoder(100, 100), liveFrame.copy(pack1Temp = 1000))[1 to Metric.TEMPERATURE]?.value == -30.0)
    // 2.1 polish (D1): the asleep pack is left out whole before latching too; the other packs still vote.
    val unlatched = BmsDecoder()
    repeat(BmsDecoder.LATCH_FRAMES) { check(decode(unlatched, placeholder).let { r -> r.keys.none { it.first == 1 } && r[2 to Metric.TEMPERATURE]?.value == 29.3 }) }
    check(unlatched.temperatureDivisor == 100 && unlatched.percentDivisor == 100)
    repeat(BmsDecoder.LATCH_FRAMES) { decode(unlatched, liveFrame) }
    check(unlatched.temperatureDivisor == 100 && decode(unlatched, placeholder).let { it[1 to Metric.TEMPERATURE] == null && it[2 to Metric.TEMPERATURE]?.value == 29.3 })
    // Status codes never reach the UI raw.
    check(BmsState.of(4) == BmsState.DRIVE && BmsState.of(0) == BmsState.STANDBY && BmsState.of(2) == BmsState.STANDBY && BmsState.of(7) == BmsState.UNKNOWN)
    check(decode(BmsDecoder(), liveFrame)[0 to Metric.STATUS]?.value.let { BmsState.of(it) } == BmsState.DRIVE)
    // Turn-by-turn claims need a 0x0303 acknowledgement within 5 s; a queued write proves nothing.
    check(NavDisplay.of(available = true, sending = true, ackedAt = 1_000, error = null, now = 6_000) == NavDisplay.SHOWING)
    check(NavDisplay.of(available = true, sending = true, ackedAt = 1_000, error = null, now = 6_001) == NavDisplay.PREPARING)
    check(NavDisplay.of(available = true, sending = true, ackedAt = 0, error = null, now = 100) == NavDisplay.PREPARING)
    check(NavDisplay.of(available = true, sending = true, ackedAt = 1_000, error = 3, now = 9_000) == NavDisplay.REJECTED)
    check(NavDisplay.of(available = true, sending = true, ackedAt = 0, error = -1, now = 100) == NavDisplay.REJECTED)
    check(NavDisplay.of(available = true, sending = false, ackedAt = 1_000, error = null, now = 2_000) == NavDisplay.PREPARING)
    check(NavDisplay.of(available = false, sending = false, ackedAt = 0, error = null, now = 100) == NavDisplay.UNAVAILABLE)
    bmsPlausibility(liveFrame, bms)
    storedHistory()
    exportCheck()
    println("TCU protocol, navigation and battery analytics checks passed.")
}

/**
 * D1: BMS glitches with the values seen in a field export, at ÷100. [live] is the real
 * VX2 frame (SOC 57.81 %, SOH 99.45 %, 29.3/28.9/29.3 °C); [whole] is the synthetic whole-unit frame.
 */
private fun bmsPlausibility(live: BmsFrame, whole: BmsFrame) {
    val t0 = 1_700_028_943_599L
    fun decode(decoder: BmsDecoder, f: BmsFrame, at: Long = t0) = decoder.readings(f, at).associateBy { it.pack to it.metric }
    // Parked frame: pack 1 asleep sends temperature 0x0028 (−39.6 °C) and SOE 12 (0.12 %) next to plausible
    // SOC 55.95 / SOH 99.47 / status 2. The whole pack-1 part is dropped; packs 0 and 2 are untouched.
    val parked = live.copy(soc = 5597, packTemp = 6940, bmsStatus = 0, pack1Soc = 5595, pack1Soe = 12, pack1Temp = 0x0028, pack1Status = 2,
        pack2Soc = 5599, pack2Soe = 4710, pack2Temp = 6940, pack2Status = 2)
    for (decoder in listOf(BmsDecoder(100, 100), BmsDecoder())) { // latched (restored prefs) and before any latch
        val r = decode(decoder, parked)
        check(r.keys.none { it.first == 1 }) { "pack 1 leaked: $r" }
        check(r[0 to Metric.SOC]?.value == 55.97 && r[0 to Metric.TEMPERATURE]?.value == 29.4 && BmsState.of(r[0 to Metric.STATUS]?.value) == BmsState.STANDBY)
        check(r[2 to Metric.SOC]?.value == 55.99 && r[2 to Metric.ENERGY]?.value == 47.1 && r[2 to Metric.HEALTH]?.value == 99.43)
    }
    // SOE far below SOC is the same sleep without the temperature half (what 2.1a's history kept).
    check(decode(BmsDecoder(100, 100), parked.copy(pack1Temp = 6900)).keys.none { it.first == 1 })
    check(decode(BmsDecoder(100, 100), parked.copy(pack2Soe = 2500)).let { r -> r.keys.none { it.first == 2 } && r.keys.none { it.first == 1 } })
    check(decode(BmsDecoder(100, 100), parked.copy(pack1Temp = 6900, pack1Soe = 4690))[1 to Metric.ENERGY]?.value == 46.9) // 9 points: normal
    // Raw 0x0028 is a real 40 °C in whole degrees (and 4.0 °C in tenths): not a placeholder there.
    check(decode(BmsDecoder(1, 1), whole.copy(pack1Temp = 0x0028)).let { it[1 to Metric.TEMPERATURE]?.value == 40.0 && it[1 to Metric.SOC]?.value == 80.0 })
    check(decode(BmsDecoder(1, 10), whole.copy(pack1Temp = 0x0028))[1 to Metric.TEMPERATURE]?.value == 4.0)
    // Raw 0 (−40 °C) still only empties the temperature.
    check(decode(BmsDecoder(100, 100), live.copy(pack1Temp = 0)).let { it[1 to Metric.TEMPERATURE] == null && it[1 to Metric.SOC]?.value == 57.81 })

    // HEALTH below 50 % is a misread field: 10.25 % (pack 0), 2.69 % and 10.39 % (pack 1); the rest of the frame stays.
    val health = BmsDecoder(100, 100)
    check(decode(health, live.copy(soh = 1025)).let { it[0 to Metric.HEALTH] == null && it[0 to Metric.SOC]?.value == 57.81 && it[1 to Metric.HEALTH]?.value == 99.47 })
    check(decode(health, live.copy(pack1Soh = 269)).let { it[1 to Metric.HEALTH] == null && it[1 to Metric.SOC]?.value == 57.81 })
    check(decode(health, live.copy(pack1Soh = 1039))[1 to Metric.HEALTH] == null)
    check(decode(health, live.copy(pack1Soh = 5000))[1 to Metric.HEALTH]?.value == 50.0)
    check(ReadingRules.healthPlausible(50.0) && !ReadingRules.healthPlausible(49.99))

    // Single-sample SOC jump: pack 2 56.59 → 0.02 → 56.57 % (2 Hz frames).
    val spike = BmsDecoder(100, 100)
    check(decode(spike, live.copy(pack2Soc = 5659), t0)[2 to Metric.SOC]?.value == 56.59)
    check(decode(spike, live.copy(pack2Soc = 2), t0 + 500).let { it[2 to Metric.SOC] == null && it[2 to Metric.ENERGY]?.value == 49.02 && it[2 to Metric.HEALTH]?.value == 99.43 })
    check(decode(spike, live.copy(pack2Soc = 5657), t0 + 1_000)[2 to Metric.SOC]?.value == 56.57)
    // Same for SOE (48.99 → 50.00 % is fine, then +11 points is held back); a 9.99-point move is not a spike.
    check(decode(spike, live.copy(pack1Soe = 3900), t0 + 1_500)[1 to Metric.ENERGY]?.value == 39.0)
    check(decode(spike, live.copy(pack1Soe = 5000), t0 + 2_000).let { it[1 to Metric.ENERGY] == null && it[1 to Metric.SOC]?.value == 57.81 })
    // A real change (swapped pack) is accepted on the 3rd agreeing frame; a glitch first value recovers the same way.
    val swap = BmsDecoder(100, 100)
    fun swapped(socRaw: Int) = live.copy(pack2Soc = socRaw, pack2Soe = 8900)
    check(decode(swap, live.copy(pack2Soc = 5659), t0)[2 to Metric.SOC] != null)
    check(decode(swap, swapped(9000), t0 + 500).let { it[2 to Metric.SOC] == null && it[2 to Metric.ENERGY] == null && it[2 to Metric.HEALTH] != null })
    check(decode(swap, swapped(9002), t0 + 1_000)[2 to Metric.SOC] == null)
    check(decode(swap, swapped(9004), t0 + 1_500).let { it[2 to Metric.SOC]?.value == 90.04 && it[2 to Metric.ENERGY]?.value == 89.0 })
    check(decode(swap, swapped(9006), t0 + 2_000)[2 to Metric.SOC]?.value == 90.06)
    // Disagreeing suspects restart the run; after the 60 s window any value is accepted.
    val noisy = BmsDecoder(100, 100)
    decode(noisy, live.copy(pack2Soc = 5659), t0)
    for ((i, raw) in listOf(2, 3000, 4, 6).withIndex()) check(decode(noisy, live.copy(pack2Soc = raw), t0 + 500L * (i + 1))[2 to Metric.SOC] == null)
    check(decode(noisy, live.copy(pack2Soc = 2000), t0 + 61_000)[2 to Metric.SOC]?.value == 20.0)
    // The filter itself: other metrics pass, packs are independent, a clock step back is not a jump.
    val filter = SpikeFilter()
    check(filter.accept(1, Metric.SOC, 56.0, 0) && filter.accept(2, Metric.SOC, 10.0, 0) && filter.accept(1, Metric.TEMPERATURE, -39.6, 0))
    check(!filter.accept(1, Metric.SOC, 0.02, 1_000) && filter.accept(1, Metric.SOC, 0.02, -5_000))
}

private var nextId = 1L
private fun row(session: String, time: Long, pack: Int, metric: Metric, value: Double) = StoredReading(nextId++, session, time, pack, metric, value)

/** D1 migration: stored rows shaped like a field export (real values; synthetic sessions and times, spacing kept). */
private fun storedHistory() {
    // HEALTH 10.25 % on pack 0 between 99.45 % rows (rows every 10 s, as 2.0 stored them).
    val s1 = "00000000-0000-4000-8000-000000000001"
    val health0 = listOf(99.45, 99.45, 10.25, 99.45, 99.45).mapIndexed { i, v -> row(s1, 1_700_025_240_878L + (i - 2) * 10_000, 0, Metric.HEALTH, v) }
    check(HistoryCleaner.rejected(health0) == setOf(health0[2].id))
    // Pack 1 HEALTH 2.69 % and 10.39 % (2.1a rows, 30 s and minutes apart).
    val s2 = "00000000-0000-4000-8000-000000000002"
    val health1 = listOf(1_700_035_357_910L to 99.47, 1_700_035_449_910L to 2.69, 1_700_035_479_910L to 99.47,
        1_700_036_623_235L to 10.39, 1_700_036_653_235L to 99.47).map { (t, v) -> row(s2, t, 1, Metric.HEALTH, v) }
    check(HistoryCleaner.rejected(health1) == setOf(health1[1].id, health1[3].id))
    // Pack 2 SOC 0.02 % between 56.59 % and 56.57 %.
    val s3 = "00000000-0000-4000-8000-000000000003"
    val soc2 = listOf(56.59, 56.59, 56.59, 0.02, 56.57, 56.57, 56.57).mapIndexed { i, v -> row(s3, 1_700_026_504_721L + (i - 3) * 10_000, 2, Metric.SOC, v) }
    check(HistoryCleaner.rejected(soc2) == setOf(soc2[3].id))
    // A new session starts clean: its first value is never a "jump" from the previous session.
    val resumed = listOf(row(s3, 1_000_000, 2, Metric.SOC, 56.0), row(s2, 1_030_000, 2, Metric.SOC, 80.0), row(s2, 1_040_000, 2, Metric.SOC, 80.1))
    check(HistoryCleaner.rejected(resumed).isEmpty())

    // Parked: pack 1 frames every ~10 s; two normal, then 33 asleep (SOE 0.12 %, −39.6 °C) with plausible SOC/SOH/status.
    val s4 = "00000000-0000-4000-8000-000000000004"
    val start = 1_700_028_943_599L
    fun frame(time: Long, energy: Double, temperature: Double?, soc: Double) = listOfNotNull(
        row(s4, time, 1, Metric.SOC, soc), row(s4, time, 1, Metric.HEALTH, 99.47), row(s4, time, 1, Metric.ENERGY, energy),
        temperature?.let { row(s4, time, 1, Metric.TEMPERATURE, it) }, row(s4, time, 1, Metric.STATUS, 2.0))
    val pack0 = listOf(row(s4, start, 0, Metric.SOC, 55.97), row(s4, start, 0, Metric.TEMPERATURE, 29.4), row(s4, start, 0, Metric.STATUS, 0.0))
    for (temperatureKept in listOf(true, false)) { // a 2.0 history (v5), and a 2.1a history whose v6 step deleted the −39.6 rows
        val normal = frame(start - 20_000, 47.12, 29.0, 55.98) + frame(start - 10_000, 47.08, 29.0, 55.95)
        val asleep = (0 until 33).flatMap { frame(start + it * 11_000L, 0.12, if (temperatureKept) -39.600000000000001 else null, 55.95) }
        val rejected = HistoryCleaner.rejected((asleep + pack0 + normal).shuffled(java.util.Random(7)))
        check(rejected == asleep.map { it.id }.toSet()) { "asleep frames: ${rejected.size} of ${asleep.size} rejected" }
    }
    // A real ride's charge (1 point a minute) and a temperature of raw 0 kept as history are not touched here.
    val ride = (0 until 20).map { row(s4, start + it * 30_000L, 0, Metric.SOC, 58.0 - it * 0.2) }
    check(HistoryCleaner.rejected(ride + row(s4, start, 0, Metric.TEMPERATURE, -40.0)).isEmpty())
    check(ReadingRules.placeholderCelsius(-39.6) && ReadingRules.placeholderCelsius(-39.599999999999994) && !ReadingRules.placeholderCelsius(-40.0))
}

/**
 * Optional: RUTA_EXPORT_CSV=/path/to/ruta-*.csv bash test.sh Analytics runs the clean-up over a real
 * export's readings section and checks that no rejected pattern survives.
 */
private fun exportCheck() {
    val path = System.getenv("RUTA_EXPORT_CSV")?.takeIf { it.isNotBlank() } ?: return
    val metrics = Metric.entries.associateBy { it.name }
    val rows = java.io.File(path).readLines().drop(1).takeWhile { it.isNotBlank() }.mapIndexedNotNull { i, line ->
        val f = line.split(',')
        metrics[f[3]]?.let { StoredReading(i + 1L, f[0], f[1].toLong(), f[2].toInt(), it, f[4].toDouble()) }
    }
    val rejected = HistoryCleaner.rejected(rows)
    val kept = rows.filter { it.id !in rejected }
    check(kept.none { it.metric == Metric.HEALTH && it.value < ReadingRules.MIN_HEALTH })
    check(kept.none { it.metric == Metric.TEMPERATURE && ReadingRules.placeholderCelsius(it.value) })
    val frames = kept.groupBy { Triple(it.session, it.time, it.pack) }.values
    check(frames.none { f -> ReadingRules.asleep(f.firstOrNull { it.metric == Metric.SOC }?.value, f.firstOrNull { it.metric == Metric.ENERGY }?.value) })
    val byMetric = rejected.let { ids -> rows.filter { it.id in ids }.groupingBy { "${it.pack}:${it.metric}" }.eachCount().toSortedMap() }
    println("Export ${java.io.File(path).name}: ${rows.size} readings, ${rejected.size} rejected $byMetric")
}
