package dev.codesurgen.ruta

// ICBT (service three, cluster channel) frames: byte-exact against the layouts in docs/PROTOCOL.md
// and hex captured from a real scooter.

private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
private fun ascii(text: String, length: Int) = text.toByteArray(Charsets.US_ASCII).copyOf(length)

private fun expect(actual: ByteArray, expected: ByteArray, what: String) =
    check(actual.contentEquals(expected)) { "$what: got ${actual.hex()}, want ${expected.hex()}" }

private fun uuids() {
    check(Tcu.PHONE_STATE.toString() == "00000301-0000-1000-8000-00805f9b34fb")
    check(Tcu.CALLER_INFO.toString() == "00000302-0000-1000-8000-00805f9b34fb")
    check(Tcu.NAV_INFO.toString() == "00000303-0000-1000-8000-00805f9b34fb")
    check(Tcu.MUSIC_INFO.toString() == "00000304-0000-1000-8000-00805f9b34fb")
    check(Tcu.MUSIC_META.toString() == "00000305-0000-1000-8000-00805f9b34fb")
    check(Tcu.ICBT_CONTROL.toString() == "00000306-0000-1000-8000-00805f9b34fb")
    check(Tcu.VOICE_ASSIST.toString() == "00000307-0000-1000-8000-00805f9b34fb")
    check(Tcu.ICBT_SERVICE.toString() == "76f2f660-db96-4e05-a871-53d0e4524e50")
}

private fun phoneStatus() {
    // Defaults keep the bytes the app sent before calls/SMS existed.
    expect(PhoneStatus(75, 4, 15, 30, 20).encode(), bytes(0x1C, 0x04, 0x29, 30, 15, 0, 0, 0), "defaults")
    // Spec: incoming call, missed alert, 3 bars, signal 4, unread SMS, 14:05:30, sms=2 missed=3.
    expect(
        PhoneStatus(70, 4, 14, 5, 30, missedCallAlert = true, callState = TcuCallState.INCOMING, smsFlag = true, smsCount = 2, missedCount = 3).encode(),
        bytes(0x1E, 0x4C, 0x3D, 0x05, 0x0E, 0x32, 0x00, 0x00), "spec incoming"
    )
    // Spec: active call 125 s, headset, 4 bars, signal 3, 14:06:00.
    expect(
        PhoneStatus(90, 3, 14, 6, 0, headset = true, callState = TcuCallState.ACTIVE, callDurationS = 125).encode(),
        bytes(0x25, 0x1B, 0x01, 0x06, 0x0E, 0x00, 0x00, 0x7D), "spec active"
    )
    // Battery bars from phone %: <=0 and >100 read as full, like getBatteryClusterStatus.
    val bars = listOf(0 to 4, 1 to 0, 19 to 0, 20 to 1, 39 to 1, 40 to 2, 59 to 2, 60 to 3, 79 to 3, 80 to 4, 100 to 4, 101 to 4, -5 to 4)
    bars.forEach { (percent, want) -> check(PhoneStatus(percent, 0, 0, 0, 0).encode()[0].toInt() shr 3 == want) { "battery $percent%" } }
    // Every call state lands in byte 1 bits 3-5.
    TcuCallState.values().forEach { check(PhoneStatus(50, 0, 0, 0, 0, callState = it).encode()[1].toInt() == it.code shl 3) }
    check(TcuCallState.values().filter { it.showsCaller } == listOf(TcuCallState.INCOMING, TcuCallState.OUTGOING, TcuCallState.ACTIVE))
    // Duration: big-endian u16, clamped, and only while ACTIVE (reset in every other state).
    val active = PhoneStatus(50, 0, 0, 0, 0, callState = TcuCallState.ACTIVE, callDurationS = 3725).encode()
    check(active[6] == 0x0E.toByte() && active[7] == 0x8D.toByte())
    val long = PhoneStatus(50, 0, 0, 0, 0, callState = TcuCallState.ACTIVE, callDurationS = 70_000).encode()
    check(long[6] == 0xFF.toByte() && long[7] == 0xFF.toByte())
    val ended = PhoneStatus(50, 0, 0, 0, 0, callState = TcuCallState.ENDED, callDurationS = 125).encode()
    check(ended[6] == 0.toByte() && ended[7] == 0.toByte())
    // Counts clamp to 0..15: an unmasked 16 SMS would read as 0 SMS + 1 extra missed call.
    check(PhoneStatus(50, 0, 0, 0, 0, smsCount = 16, missedCount = 3).encode()[5] == 0x3F.toByte())
    check(PhoneStatus(50, 0, 0, 0, 0, smsCount = -1, missedCount = 40).encode()[5] == 0xF0.toByte())
}

private fun callerInfo() {
    // A 15-char number is cut to 14 bytes (no terminator).
    expect(
        CallerInfo("+91 98765 43210").encode(),
        bytes(0x2B, 0x39, 0x31, 0x20, 0x39, 0x38, 0x37, 0x36, 0x35, 0x20, 0x34, 0x33, 0x32, 0x31), "spec number"
    )
    // Spec: two-field form, 7 + 7 bytes, each zero padded.
    expect(
        CallerInfo("Mom", "9876543210").encode(),
        bytes(0x4D, 0x6F, 0x6D, 0, 0, 0, 0, 0x39, 0x38, 0x37, 0x36, 0x35, 0x34, 0x33), "spec two-field"
    )
    expect(CallerInfo("Ravi").encode(), ascii("Ravi", 14), "padded")
    expect(CallerInfo("Abcdefghijklmn").encode(), ascii("Abcdefghijklmn", 14), "exactly 14")
    // SDK regex [^\p{Alnum}\s+] → '_' per code point.
    expect(CallerInfo("Dr. O'Brien (Work)").encode(), ascii("Dr_ O_Brien _W", 14), "punctuation")
    expect(CallerInfo("Mom 😀!").encode(), ascii("Mom __", 14), "emoji is one code point")
    // Android's Unicode-aware \p{Alnum} keeps non-ASCII letters, then US-ASCII makes them '?'.
    expect(CallerInfo("राम").encode(), ascii("???", 14), "devanagari")
    // Improvements: accents fold, any whitespace becomes a space.
    expect(CallerInfo("José Müller").encode(), ascii("Jose Muller", 14), "accents")
    expect(CallerInfo("Jose\u0301").encode(), ascii("Jose", 14), "decomposed accent")
    expect(CallerInfo("A\tB C").encode(), ascii("A B C", 14), "whitespace")
    check(CallerInfo("x").encode().size == CallerInfo.LENGTH && CallerInfo("x", "y").encode().size == CallerInfo.LENGTH)

    // Official rule: contact name when resolved, else the number; numbers lose spacing so they fit.
    check(CallerInfo.forCall("  Amma ", "+91 98765 43210") == CallerInfo("Amma"))
    check(CallerInfo.forCall(null, "+91 98765 43210") == CallerInfo("+919876543210"))
    expect(CallerInfo.forCall("", "+91-98765-43210")!!.encode(), ascii("+919876543210", 14), "clean number")
    check(CallerInfo.forCall("UnKnown", "(040) 2345 6789") == CallerInfo("04023456789"))
    check(CallerInfo.forCall(null, null) == null && CallerInfo.forCall(" ", "Private") == null)
    // Dialer-notification title used as the name for an unsaved caller: cleaned like a number.
    expect(CallerInfo.forCall("+91 98765 43210", null)!!.encode(), ascii("+919876543210", 14), "number-like name")
    check(CallerInfo.forCall("(040) 2345-6789", null) == CallerInfo("04023456789"))
    check(CallerInfo.forCall("Dr. Rao 2", null) == CallerInfo("Dr. Rao 2") && CallerInfo.forCall("911", null) == CallerInfo("911"))
}

private fun musicInfo() {
    // Spec examples (bit 7: 0 = PLAYING, 1 = PAUSED; byte 2 bit 0: session active).
    expect(MusicInfo.of(3, 7).encode(), bytes(0x38, 0x00, 0x01), "playing vol 7")
    expect(MusicInfo.of(2, 10).encode(), bytes(0xD0, 0x00, 0x01), "paused vol 10")
    expect(MusicInfo.of(1, 10).encode(), bytes(0xD0, 0x00, 0x00), "stopped vol 10")
    // BUFFERING plays; NONE, ERROR and no session are inactive + paused.
    expect(MusicInfo.of(6, 7).encode(), bytes(0x38, 0x00, 0x01), "buffering")
    listOf(null, 0, 7).forEach { expect(MusicInfo.of(it, 10).encode(), bytes(0xD0, 0x00, 0x00), "inactive $it") }
    // Transient states (skip, seek, connecting) stay playing instead of flashing "no music".
    listOf(4, 5, 8, 9, 10, 11).forEach { expect(MusicInfo.of(it, 4).encode(), bytes(0x20, 0x00, 0x01), "transient $it") }
    expect(MusicInfo.of(3, 5, 42).encode(), bytes(0x28, 42, 0x01), "progress")
    expect(MusicInfo(active = true, playing = true, volume = 99, elapsedPercent = 300).encode(), bytes(0x78, 0xFF, 0x01), "clamped")
    expect(MusicInfo(active = false, playing = true, volume = -1).encode(), bytes(0x00, 0x00, 0x00), "initial all-zero")

    // Volume on the cluster's 0..10 scale, rounded (integer division first would only give 0 or 10).
    check(MusicInfo.volumeLevel(7, 15) == 5 && MusicInfo.volumeLevel(1, 15) == 1 && MusicInfo.volumeLevel(15, 15) == 10)
    check(MusicInfo.volumeLevel(0, 15) == 0 && MusicInfo.volumeLevel(20, 15) == 10 && MusicInfo.volumeLevel(5, 0) == 0)
    check(MusicInfo.progressPercent(30_000, 120_000) == 25 && MusicInfo.progressPercent(0, 0) == 0)
    check(MusicInfo.progressPercent(200_000, 120_000) == 100 && MusicInfo.progressPercent(-5, 100) == 0)
}

private fun musicMeta() {
    // Spec: title then artist, 14 bytes each; "Imagine Dragons" is cut to 14.
    expect(
        MusicMeta("Believer", "Imagine Dragons").encode(),
        bytes(
            0x42, 0x65, 0x6C, 0x69, 0x65, 0x76, 0x65, 0x72, 0, 0, 0, 0, 0, 0,
            0x49, 0x6D, 0x61, 0x67, 0x69, 0x6E, 0x65, 0x20, 0x44, 0x72, 0x61, 0x67, 0x6F, 0x6E
        ), "spec song"
    )
    expect(MusicMeta.CLEAR.encode(), ByteArray(28), "no music")
    // Music removes disallowed characters instead of replacing them.
    expect(MusicMeta("Don't Stop Me Now", "Queen").encode(), ascii("Dont Stop Me N", 14) + ascii("Queen", 14), "removed")
    expect(MusicMeta("Café del Mar (Remix)", "Beyoncé").encode(), ascii("Cafe del Mar R", 14) + ascii("Beyonce", 14), "accents")
    expect(MusicMeta("Tum Hi Ho – Live", "🎵 Arijit").encode(), ascii("Tum Hi Ho  Liv", 14) + ascii(" Arijit", 14), "symbols")
    // Artist, else album, else ""; a blank artist falls back to the album too.
    check(MusicMeta.of("T", null, "Album") == MusicMeta("T", "Album"))
    check(MusicMeta.of("T", " ", "Album") == MusicMeta("T", "Album") && MusicMeta.of("T", "Artist", "Album") == MusicMeta("T", "Artist"))
    check(MusicMeta.of(null, null, null) == MusicMeta.CLEAR)
}

private fun voiceAssist() {
    expect(VoiceAssistState.LISTENING.encode(), bytes(0x01), "listening")
    expect(VoiceAssistState.NO_ACTION.encode(), bytes(0x00), "idle")
    check(VoiceAssistState.values().map { it.code } == (0..5).toList())
}

private fun hardControl() {
    fun events(vararg b: Int) = HardControlFrame.parse(bytes(*b))?.events
    // Spec: the only frame that fires call-accept alone (other fields at unmapped values).
    check(events(0xF1, 0x38, 0x00) == listOf(HardControlEvent.CALL_ACCEPT))
    check(HardControlFrame.parse(bytes(0xF1, 0x38, 0x00)) == HardControlFrame.NO_ACTION.copy(callAction = 1))
    expect(HardControlFrame.NO_ACTION.encode(), bytes(0xF0, 0x38, 0x00), "no action")
    check(HardControlFrame.parse(HardControlFrame.NO_ACTION.encode())!!.events.isEmpty())
    // Spec pitfall: value 0 is a real action, so 00 00 00 is volume up twice + play/pause, in SDK order.
    check(events(0, 0, 0) == listOf(HardControlEvent.CALL_VOLUME_UP, HardControlEvent.MUSIC_VOLUME_UP, HardControlEvent.MUSIC_PLAY_PAUSE))
    // One action per field value.
    check(events(0xF2, 0x38, 0) == listOf(HardControlEvent.CALL_REJECT))
    check(events(0xC0, 0x38, 0) == listOf(HardControlEvent.CALL_VOLUME_UP))
    check(events(0xD0, 0x38, 0) == listOf(HardControlEvent.CALL_VOLUME_DOWN))
    check(events(0x30, 0x38, 0) == listOf(HardControlEvent.MUSIC_VOLUME_UP))
    check(events(0x70, 0x38, 0) == listOf(HardControlEvent.MUSIC_VOLUME_DOWN))
    check(events(0xF0, 0x00, 0) == listOf(HardControlEvent.MUSIC_PLAY_PAUSE))
    check(events(0xF0, 0x08, 0) == listOf(HardControlEvent.MUSIC_NEXT))
    check(events(0xF0, 0x10, 0) == listOf(HardControlEvent.MUSIC_PREVIOUS))
    check(events(0xF0, 0x18, 0) == listOf(HardControlEvent.MUSIC_MUTE))
    check(events(0xF0, 0x38, 1) == listOf(HardControlEvent.VOICE_START))
    check(events(0xF0, 0x38, 2) == listOf(HardControlEvent.VOICE_CANCEL))
    // Unmapped values: call action 0/3..15, volumes 2..3, music control 4..7, voice 0/3..7.
    check(events(0xA3, 0x20, 3)!!.isEmpty() && events(0xFF, 0x3F, 0xFF)!!.isEmpty())
    // musicDataConfig and smsTrigger are decoded but never become events.
    val sms = HardControlFrame.parse(bytes(0xF0, 0xFF, 0x00))!!
    check(sms.musicDataConfig == 7 && sms.smsTrigger == 3 && sms.musicControl == 7 && sms.events.isEmpty())
    // Length: fewer than 3 bytes is ignored; extra bytes are ignored.
    check(HardControlFrame.parse(bytes(0xF1, 0x38)) == null && HardControlFrame.parse(ByteArray(0)) == null)
    check(events(0xF1, 0x38, 0x00, 0xFF) == listOf(HardControlEvent.CALL_ACCEPT))
    // Decoder matches signed-byte shift semantics on every input; encode is its exact inverse.
    for (b0 in 0..255) for (b1 in 0..255) for (b2 in 0..7) {
        val frame = HardControlFrame.parse(bytes(b0, b1, b2))!!
        val s0 = b0.toByte().toInt(); val s1 = b1.toByte().toInt()
        check(frame.callAction == s0 and 15 && frame.callVolume == (s0 shr 4) and 3 && frame.musicVolume == (s0 shr 6) and 3)
        check(frame.musicDataConfig == s1 and 7 && frame.musicControl == (s1 shr 3) and 7 && frame.smsTrigger == (s1 shr 6) and 3)
        check(frame.voiceControl == b2 && frame.encode().contentEquals(bytes(b0, b1, b2)))
    }

    // Debounce: identical frames within 300 ms of the previous one are one press; the window slides.
    val accept = bytes(0xF1, 0x38, 0x00); val idle = HardControlFrame.NO_ACTION.encode()
    val d = HardControlDebouncer()
    check(d.accept(accept, 1_000))
    check(!d.accept(accept.copyOf(), 1_100) && !d.accept(accept, 1_350))
    check(d.accept(accept, 1_700))
    check(d.accept(idle, 1_710) && d.accept(accept, 1_720)) // A different frame in between re-arms it.
    check(d.accept(accept, 1_000)) // Clock went backwards: never swallow.
}

private fun text() {
    check(IcbtText.sanitize("a+b c", "_") == "a+b c" && IcbtText.sanitize("a/b-c", "") == "abc")
    check(IcbtText.sanitize("x😀y", "_") == "x_y")
    check(IcbtText.encode("abc", 2, "_").contentEquals(ascii("ab", 2)))
    // Road names (0x0303) share the caller rules; ASCII output is unchanged from before.
    check(String(NavFrame.roadBytes("MG Road/NH-65"), Charsets.US_ASCII) == "MG Road_NH_65\u0000")
    check(String(NavFrame.roadBytes("Śivājī Nagar"), Charsets.US_ASCII) == "Sivaji Nagar\u0000\u0000")
}

fun main() {
    uuids()
    phoneStatus()
    callerInfo()
    musicInfo()
    musicMeta()
    voiceAssist()
    hardControl()
    text()
    println("IcbtProtocolTest: all checks passed")
}
