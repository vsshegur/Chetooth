package dev.codesurgen.ruta

// Synthetic frames only: made-up UUIDs and bytes, not captured from a scooter.

fun main() {
    formats()
    singleReadingIsAmbiguous()
    twoReadingsFindTheField()
    taggedFrames()
    rangeField()
    serialisation()
    streamBook()
    println("ChetakLearnTest: all checks passed")
}

private const val CH = "0000fff1-0000-1000-8000-00805f9b34fb"
private const val CH2 = "0000fff2-0000-1000-8000-00805f9b34fb"

private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

/** A 12-byte status frame with battery % ×100 little-endian at bytes 4–5 and range km at byte 8. */
private fun frame(socTimes100: Int, rangeKm: Int, speed: Int = 0) =
    bytes(0x10, 0x01, speed, 0x00, socTimes100 and 0xFF, socTimes100 shr 8, 0x33, 0x00, rangeKm, 0x00, 0x7F, 0x02)

private fun point(value: Double, at: Long, vararg frames: Pair<String, ByteArray>): CalPoint {
    val book = StreamBook()
    frames.forEach { (uuid, b) -> book.put(uuid, b, at) }
    return CalPoint(value, at, book.snapshot(at))
}

private fun formats() {
    val b = bytes(0x34, 0x12, 0x64)
    check(NumFmt.U16LE.decode(b, 0) == 0x1234.toDouble())
    check(NumFmt.U16BE.decode(b, 0) == 0x3412.toDouble())
    check(NumFmt.U8.decode(b, 2) == 100.0 && NumFmt.U8_HALF.decode(b, 2) == 50.0)
    check(NumFmt.U16LE.decode(b, 2) == null) // runs past the end
    check(NumFmt.U16LE_100.decode(bytes(0x10, 0x27), 0) == 100.0) // 10000 / 100
}

private fun singleReadingIsAmbiguous() {
    // 76 % also appears as a stray byte elsewhere: one reading cannot tell them apart.
    val p = point(76.0, 1_000, CH to frame(7600, 60), CH2 to bytes(0x4C, 0x00, 0x05))
    val ranked = ChetakLearn.rank(listOf(p), LearnField.SOC)
    check(ranked.size >= 2) { "expected several candidates, got ${ranked.size}" }
    check(ChetakLearn.confident(listOf(p), LearnField.SOC) == null)
    // The 2-byte ÷100 field ranks first (least likely to be a coincidence).
    check(ranked.first().candidate.fmt == NumFmt.U16LE_100 && ranked.first().candidate.offset == 4) { ranked.first().candidate.describe() }
}

private fun twoReadingsFindTheField() {
    val a = point(76.0, 1_000, CH to frame(7600, 60), CH2 to bytes(0x4C, 0x00, 0x05))
    val b = point(70.0, 900_000, CH to frame(7010, 55), CH2 to bytes(0x4C, 0x00, 0x05)) // the stray byte did not move
    val c = ChetakLearn.confident(listOf(a, b), LearnField.SOC)
    check(c != null) { "no confident match: " + ChetakLearn.rank(listOf(a, b), LearnField.SOC).take(5).joinToString { it.candidate.describe() + " " + it.hits } }
    c!!
    check(c.offset == 4 && c.fmt == NumFmt.U16LE_100 && c.stream.uuid == CH && c.stream.len == 12)
    // Reading a later frame.
    check(c.read(CH, frame(6550, 50), LearnField.SOC) == 65.5)
    check(c.read(CH2, frame(6550, 50), LearnField.SOC) == null) // other characteristic
    check(c.read(CH, bytes(1, 2, 3), LearnField.SOC) == null) // other length
}

private fun taggedFrames() {
    // One characteristic carries two frame types of the same length, told apart by byte 0.
    fun bms(soc: Int) = bytes(0x01, 0x00, soc, 0x00, 0x00, 0x00)
    fun other(x: Int) = bytes(0x02, 0x00, x, 0x00, 0x00, 0x00)
    val book1 = StreamBook(); book1.put(CH, other(80), 0); book1.put(CH, bms(80), 1) // newest plain frame is bms
    val book2 = StreamBook(); book2.put(CH, bms(60), 0); book2.put(CH, other(3), 1)  // newest plain frame is other
    val p1 = CalPoint(80.0, 1, book1.snapshot(1))
    val p2 = CalPoint(60.0, 1, book2.snapshot(1))
    val c = ChetakLearn.confident(listOf(p1, p2), LearnField.SOC)
    check(c != null && c.stream.tag == 0x01 && c.offset == 2) { "tagged: ${c?.describe()}" }
    check(c!!.read(CH, other(99), LearnField.SOC) == null && c.read(CH, bms(42), LearnField.SOC) == 42.0)
}

private fun rangeField() {
    val a = point(60.0, 1_000, CH to frame(7600, 60))
    val b = point(48.0, 2_000, CH to frame(6100, 48))
    val c = ChetakLearn.confident(listOf(a, b), LearnField.RANGE)
    check(c != null && c.offset == 8) { "range: " + ChetakLearn.rank(listOf(a, b), LearnField.RANGE).take(5).joinToString { it.candidate.describe() } }
}

private fun serialisation() {
    val p = point(76.0, 1_234, CH to frame(7600, 60))
    val back = CalPoint.decode(p.encode())!!
    check(back.value == 76.0 && back.at == 1_234L && back.snapshot.size == p.snapshot.size)
    for ((k, v) in p.snapshot) check(back.snapshot[k]!!.contentEquals(v))
    val all = CalPoint.decodeAll(CalPoint.encodeAll(listOf(p, p)))
    check(all.size == 2)
    val cand = Candidate(StreamKey(CH, 12, 0x10), 4, NumFmt.U16LE_100)
    check(Candidate.decode(cand.encode()) == cand)
    check(Candidate.decode(Candidate(StreamKey(CH, 12, null), 4, NumFmt.U8).encode())!!.stream.tag == null)
    check(Candidate.decode("junk") == null && Candidate.decode(null) == null)
    check(shortUuid(CH) == "0xFFF1" && shortUuid("1e5af1a3-fab3-4e33-aafb-331e781ed789").length == 36)
    check(parseHex("0aFF") !!.contentEquals(bytes(0x0A, 0xFF)) && parseHex("abc") == null)
}

private fun streamBook() {
    val book = StreamBook()
    // A changing first byte (data, not a tag) must not grow the book without bound.
    for (i in 0 until 200) book.put(CH, bytes(i, 1, 2), 0)
    check(book.size <= 1 + ChetakLearn.MAX_TAGS) { "book grew to ${book.size}" }
    check(book.snapshot(ChetakLearn.SNAPSHOT_MAX_AGE_MS + 1).isEmpty()) // everything is too old
}
