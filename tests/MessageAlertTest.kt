package dev.codesurgen.ruta

// "Show who messaged": sender formatting (names only, never numbers or text) and the alert state machine.

private fun eq(actual: Any?, expected: Any?, what: String) = check(actual == expected) { "$what: got $actual, want $expected" }

private val OK = MessageAlerter.Conditions(enabled = true, ready = true, playing = false, inCall = false)

fun main() {
    names()
    formatting()
    showAndClear()
    rateLimit()
    press()
    conditions()
    println("MessageAlertTest: all checks passed")
}

private fun names() {
    eq(MessageAlerts.appName("com.whatsapp", "WhatsApp Messenger"), "WhatsApp", "WhatsApp")
    eq(MessageAlerts.appName("com.google.android.apps.messaging", "Messages"), "SMS", "Google Messages")
    eq(MessageAlerts.appName("org.telegram.messenger", null), "Telegram", "Telegram")
    eq(MessageAlerts.appName("com.example.sms", "Fancy SMS", defaultSms = true), "SMS", "default SMS app")
    eq(MessageAlerts.appName("com.example.chat", "Chatterbox Messenger Pro"), "Chatterbox Mes", "label cut to 14")
    eq(MessageAlerts.appName("com.example.chat", null), "Message", "no label")

    fun sender(title: String?, conversation: String? = null, group: Boolean = false, app: String = "WhatsApp") =
        MessageAlerts.sender(title, conversation, group, app)
    eq(sender("Alex"), "Alex", "contact")
    eq(sender("Alex (2 messages)"), "Alex", "WhatsApp count suffix")
    eq(sender("Priya Raghunathan Iyer"), "Priya Raghunat", "cut to 14")
    eq(sender("Zoë Müller"), "Zoe Muller", "accents folded")
    // Groups: the conversation title Android provides.
    eq(sender("Alex", "Family", group = true), "Family", "group title")
    eq(sender("Family: Alex", null, group = true), "Family Alex", "group without a conversation title")
    eq(sender("Alex", "Family", group = false), "Alex", "1:1 ignores the conversation title")
    // Never a phone number, a summary or nothing.
    for (title in listOf("+91 98765 43210", "98765 43210", "~ +44 7700 900123", "3 new messages", "5 messages from 2 chats", "New message", "WhatsApp", "", "   ", null, "राम"))
        eq(sender(title), MessageAlerts.NO_NAME, "no name for '$title'")
    eq(sender("+91 98765 43210", "+91 98765 43210", group = true), MessageAlerts.NO_NAME, "number as a group title")
    eq(sender("Office 2"), "Office 2", "a short number inside a name is fine")
}

private fun formatting() {
    val notice = MessageAlerts.notice("com.whatsapp", "WhatsApp", false, "Alex Fernandes-Rao 😀", null, false)
    eq(notice, MessageNotice("WhatsApp", "Alex Fernandes"), "notice")
    val bytes = notice.meta.encode()
    eq(bytes.size, 28, "0x0305 length")
    eq(String(bytes, 0, 14, Charsets.US_ASCII).trimEnd('\u0000'), "WhatsApp", "title field = app")
    eq(String(bytes, 14, 14, Charsets.US_ASCII).trimEnd('\u0000'), "Alex Fernandes", "artist field = sender")
    check(bytes.all { it.toInt() in 0..127 }) { "ASCII only" }
    for (text in listOf("Ünïcödé Ñame", "名前 Tanaka", "A\tB\nC", "x".repeat(40))) {
        val fitted = MessageAlerts.fit(text)
        check(fitted.length <= 14 && fitted.all { it.code in 32..126 } && fitted == fitted.trim() && "  " !in fitted) { "'$text' → '$fitted'" }
    }
    eq(MessageAlerts.fit("名前 Tanaka"), "Tanaka", "non-Latin letters dropped, not '?'")
}

private fun showAndClear() {
    val a = MessageAlerter()
    val alex = MessageNotice("WhatsApp", "Alex")
    eq(a.meta, null, "nothing at first")
    eq(a.message(alex, 1_000, OK).outcome, MessageAlerter.Outcome.SHOWN, "shown")
    eq(a.meta, MusicMeta("WhatsApp", "Alex"), "0x0305 carries the alert")
    eq(a.tick(8_999, OK), null, "still showing at 7.999 s")
    val cleared = a.tick(9_000, OK)
    eq(cleared?.outcome, MessageAlerter.Outcome.CLEARED_TIMEOUT, "cleared after 8 s")
    eq(a.meta, null, "back to the music state")
    eq(a.describe(MessageAlerter.Change(MessageAlerter.Outcome.SHOWN, alex)), "Message alert shown (WhatsApp)", "log line")
    check(a.describe(MessageAlerter.Change(MessageAlerter.Outcome.SHOWN, alex))!!.contains("Alex").not()) { "a name in the log" }
}

private fun rateLimit() {
    val a = MessageAlerter()
    val alex = MessageNotice("WhatsApp", "Alex"); val sam = MessageNotice("SMS", "Sam"); val kim = MessageNotice("Telegram", "Kim")
    a.message(alex, 0, OK)
    // A burst while one shows: queued, coalesced to the latest sender.
    eq(a.message(sam, 2_000, OK).outcome, MessageAlerter.Outcome.QUEUED, "queued")
    eq(a.message(kim, 3_000, OK).outcome, MessageAlerter.Outcome.QUEUED, "queued again")
    eq(a.pending, kim, "latest sender wins")
    eq(a.showing, alex, "the first stays until it times out")
    eq(a.tick(8_000, OK)?.outcome, MessageAlerter.Outcome.CLEARED_TIMEOUT, "first cleared")
    eq(a.tick(9_000, OK), null, "not before 10 s since the last alert")
    val next = a.tick(10_000, OK)
    eq(next?.outcome to next?.notice, MessageAlerter.Outcome.SHOWN to kim, "queued one shown at 10 s")
    eq(a.pending, null, "queue empty")
    // A single message 10 s after the last alert shows at once; earlier it waits.
    eq(a.tick(18_000, OK)?.outcome, MessageAlerter.Outcome.CLEARED_TIMEOUT, "kim cleared")
    eq(a.message(alex, 19_000, OK).outcome, MessageAlerter.Outcome.QUEUED, "inside the gap, nothing showing")
    eq(a.tick(19_900, OK), null, "still inside the gap")
    eq(a.tick(20_000, OK)?.outcome, MessageAlerter.Outcome.SHOWN, "alex at 20 s")
    eq(a.message(sam, 30_000, OK).outcome, MessageAlerter.Outcome.QUEUED, "alex still showing at 30 s without a tick")
    a.tick(30_000, OK); eq(a.tick(30_100, OK)?.outcome, MessageAlerter.Outcome.SHOWN, "after the gap: at once")
    // Never more than one SHOWN per 10 s over a long burst.
    val b = MessageAlerter()
    val shownAt = ArrayList<Long>()
    var t = 0L
    while (t < 120_000) {
        if (t % 700 == 0L && b.message(MessageNotice("SMS", "S$t"), t, OK).outcome == MessageAlerter.Outcome.SHOWN) shownAt += t
        if (b.tick(t, OK)?.outcome == MessageAlerter.Outcome.SHOWN) shownAt += t
        t += 100
    }
    check(shownAt.size >= 10 && shownAt.zipWithNext().all { (x, y) -> y - x >= MessageAlerts.MIN_GAP_MS }) { "shown at $shownAt" }
}

private fun press() {
    val a = MessageAlerter()
    eq(a.press(0), false, "no alert: the press plays music")
    a.message(MessageNotice("WhatsApp", "Alex"), 1_000, OK)
    a.message(MessageNotice("SMS", "Sam"), 2_000, OK)
    eq(a.press(3_000), true, "dismissed and consumed")
    eq(a.meta to a.pending, null to null, "cleared, burst dropped")
    eq(a.press(3_500), false, "the next press is music again")
    // Right after a timeout the press still only dismisses.
    val b = MessageAlerter()
    b.message(MessageNotice("WhatsApp", "Alex"), 0, OK)
    b.tick(8_000, OK)
    eq(b.press(9_000), true, "grace after timeout")
    eq(b.press(9_200), false, "grace used once")
    val c = MessageAlerter()
    c.message(MessageNotice("WhatsApp", "Alex"), 0, OK)
    c.tick(8_000, OK)
    eq(c.press(8_000 + MessageAlerts.PRESS_GRACE_MS + 1), false, "grace over")
}

private fun conditions() {
    val alex = MessageNotice("WhatsApp", "Alex")
    for ((what, cond) in listOf(
        "switch off" to OK.copy(enabled = false), "link not ready" to OK.copy(ready = false),
        "music playing" to OK.copy(playing = true), "in a call" to OK.copy(inCall = true)
    )) {
        val a = MessageAlerter()
        eq(a.message(alex, 0, cond).outcome, MessageAlerter.Outcome.IGNORED, what)
        eq(a.meta, null, "$what: nothing shown")
    }
    // Showing, then music starts / a call rings / the switch goes off: cleared at once, queue dropped.
    for ((cond, want) in listOf(
        OK.copy(playing = true) to MessageAlerter.Outcome.CLEARED_MUSIC,
        OK.copy(inCall = true) to MessageAlerter.Outcome.CLEARED_CALL,
        OK.copy(enabled = false) to MessageAlerter.Outcome.CLEARED_OFF,
        OK.copy(ready = false) to MessageAlerter.Outcome.CLEARED_OFF
    )) {
        val a = MessageAlerter()
        a.message(alex, 0, OK); a.message(MessageNotice("SMS", "Sam"), 1_000, OK)
        eq(a.tick(2_000, cond)?.outcome, want, "cleared: $want")
        eq(a.meta to a.pending, null to null, "$want: nothing left")
        eq(a.press(2_100), false, "$want: no grace, the press is music")
        eq(a.tick(20_000, OK), null, "$want: the dropped burst never shows")
    }
    // A queued alert is dropped when the conditions fail before its turn.
    val a = MessageAlerter()
    a.message(alex, 0, OK); a.tick(8_000, OK)
    a.message(MessageNotice("SMS", "Sam"), 9_000, OK)
    eq(a.tick(9_500, OK.copy(playing = true)), null, "queued, music started")
    eq(a.tick(11_000, OK), null, "and dropped")
    // reset() (link down) forgets everything, including the rate limit.
    a.message(alex, 30_000, OK); a.reset()
    eq(a.message(alex, 30_500, OK).outcome, MessageAlerter.Outcome.SHOWN, "fresh after reset")
}
