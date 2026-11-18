package dev.codesurgen.ruta

// Synthetic identifiers only (open-source rule): HMC_000000, 00:11:22:33:44:55.

fun main() {
    pins()
    nicknames()
    macs()
    scooterFilter()
    signal()
    aggregator()
    bonds()
    linkFailures()
    failureCopy()
    variants()
    plan()
    println("PairingTest: all checks passed")
}

private fun pins() {
    check(PairingRules.sanitizePin(" 12-34 56 ") == "123456")
    check(PairingRules.sanitizePin("1234567890") == "12345678")
    check(PairingRules.sanitizePin(null) == "" && PairingRules.sanitizePin("abc") == "")
    check(PairingRules.sanitizePin("١٢٣٤") == "") // Only ASCII digits reach setPin.
    check(PairingRules.isValidPin("1234") && PairingRules.isValidPin("12345678") && PairingRules.isValidPin("000000"))
    check(!PairingRules.isValidPin("123") && !PairingRules.isValidPin("123456789"))
    check(!PairingRules.isValidPin("12a4") && !PairingRules.isValidPin("") && !PairingRules.isValidPin(null))
    check(!PairingRules.isValidPin("12 34"))
}

private fun nicknames() {
    check(PairingRules.nickname(null) == "My scooter" && PairingRules.nickname("   ") == "My scooter")
    check(PairingRules.nickname("  Blue   one ") == "Blue one")
    val long = PairingRules.nickname("A very long scooter nickname indeed")
    check(long.length <= PairingRules.NICKNAME_MAX && long == "A very long scooter nick")
    check(PairingRules.nickname("x".repeat(30)).length == 24)
}

private fun macs() {
    check(PairingRules.normalizeMac("00:11:22:33:44:55") == "00:11:22:33:44:55")
    check(PairingRules.normalizeMac("aa-bb-cc-dd-ee-ff") == "AA:BB:CC:DD:EE:FF")
    check(PairingRules.normalizeMac("aabbccddeeff") == "AA:BB:CC:DD:EE:FF")
    check(PairingRules.normalizeMac(" 00:11:22:33:44:5 ") == null)
    check(PairingRules.normalizeMac("00:11:22:33:44:GG") == null)
    check(PairingRules.normalizeMac("00:11:22:33:44:55:66") == null)
    check(PairingRules.normalizeMac(null) == null && PairingRules.normalizeMac("") == null)
}

private fun scooterFilter() {
    check(PairingRules.isScooter("HMC_000000") && PairingRules.isScooter(" hmc_000000"))
    // Chetooth: any named device is offered (the Chetak's name is not documented); unnamed ones are not.
    check(PairingRules.isScooter("CHETAK_1234") && PairingRules.isScooter("Galaxy Buds") && !PairingRules.isScooter(null))
    check(PairingRules.isScooter(null, listOf("1E5AF1A3-FAB3-4E33-AAFB-331E781ED789")))
    check(!PairingRules.isScooter("", listOf("0000180f-0000-1000-8000-00805f9b34fb")))
    check(PairingRules.likelyScooter("Chetak 3501") && PairingRules.likelyScooter("BAJAJ-EV") && PairingRules.likelyScooter("HMC_000000"))
    check(!PairingRules.likelyScooter("Galaxy Buds") && !PairingRules.likelyScooter(null))
    // Likely scooters come before other paired devices, which come before the rest.
    val order = PairingRules.ordered(listOf(
        ScanCandidate("00:11:22:33:44:01", "Galaxy Buds", -60, true),
        ScanCandidate("00:11:22:33:44:02", "Watch", -60, false),
        ScanCandidate("00:11:22:33:44:03", "Chetak", -60, true)
    )).map { it.name }
    check(order == listOf("Chetak", "Galaxy Buds", "Watch")) { "order $order" }
}

private fun signal() {
    check(PairingRules.signalBars(ScanCandidate.RSSI_UNKNOWN) == 0)
    check(PairingRules.signalBars(-40) == 4 && PairingRules.signalBars(-60) == 4)
    check(PairingRules.signalBars(-61) == 3 && PairingRules.signalBars(-72) == 3)
    check(PairingRules.signalBars(-80) == 2 && PairingRules.signalBars(-95) == 1 && PairingRules.signalBars(-127) == 1)
    check(PairingRules.signalWord(4) == "Excellent signal" && PairingRules.signalWord(0) == "Not in range")
}

private fun aggregator() {
    val a = ScanAggregator(throttleMs = 1_000)
    // New scooter publishes at once; paired ones sort first, the rest keep discovery order.
    check(a.offer(ScanCandidate("00:11:22:33:44:55", "HMC_000000", -70, false), 0))
    check(a.offer(ScanCandidate("00:11:22:33:44:66", "HMC_000001", -50, false), 10))
    check(a.offer(ScanCandidate("00:11:22:33:44:77", "HMC_000002", ScanCandidate.RSSI_UNKNOWN, true), 20))
    check(a.snapshot().map { it.name } == listOf("HMC_000002", "HMC_000000", "HMC_000001"))
    // Jitter inside the same bar: stored, never published.
    check(!a.offer(ScanCandidate("00:11:22:33:44:55", "HMC_000000", -71, false), 30))
    check(!a.due(5_000))
    // A bar change right after a publish waits for the throttle window…
    check(!a.offer(ScanCandidate("00:11:22:33:44:55", "HMC_000000", -50, false), 500))
    check(!a.due(900))
    check(a.due(1_100) && !a.due(1_200))
    check(a.snapshot().first { it.name == "HMC_000000" }.bars == 4)
    // …and publishes at once when the window has passed.
    check(a.offer(ScanCandidate("00:11:22:33:44:55", "HMC_000000", -90, false), 3_000))
    // Lower-case MACs merge with upper-case ones; an advert without a name keeps the known name;
    // a paired scooter heard over the air keeps its paired flag and gains a signal.
    check(a.offer(ScanCandidate("00:11:22:33:44:77", "", -55, false), 3_100) == false)
    val paired = a.snapshot().first()
    check(paired.name == "HMC_000002" && paired.bonded && paired.rssi == -55)
    check(!a.offer(ScanCandidate("00:11:22:33:44:66".lowercase(), "HMC_000001", -52, false), 3_200))
    check(a.size == 3)
    a.clear()
    check(a.size == 0 && a.snapshot().isEmpty())
}

private fun bonds() {
    val r = PairingRules
    val wrong = BondOutcome.Failed(PairingFailure.WRONG_PIN)
    val noRequest = BondOutcome.Failed(PairingFailure.NO_PIN_REQUEST)
    val rejected = BondOutcome.Failed(PairingFailure.REJECTED)
    val lost = BondOutcome.Failed(PairingFailure.LINK_LOST)
    check(r.bondOutcome(r.BOND_BONDING, r.BOND_BONDED, true, true) == BondOutcome.Bonded)
    check(r.bondOutcome(r.BOND_NONE, r.BOND_BONDED, false, false) == BondOutcome.Bonded)
    check(r.bondOutcome(r.BOND_NONE, r.BOND_BONDING, true, false) == BondOutcome.Pending)
    // Wrong PIN only when our PIN was delivered and the scooter then refused it.
    check(r.bondOutcome(r.BOND_BONDING, r.BOND_NONE, true, true) == wrong)
    check(r.bondOutcome(r.BOND_BONDING, r.BOND_NONE, true, true, r.UNBOND_AUTH_FAILED) == wrong)
    check(r.bondOutcome(r.BOND_BONDING, r.BOND_NONE, true, true, r.UNBOND_AUTH_REJECTED) == wrong)
    check(r.bondOutcome(r.BOND_BONDING, r.BOND_NONE, true, true, r.UNBOND_REMOTE_DEVICE_DOWN) == wrong) // Link dropped right after delivery.
    // Armed but never delivered (OEM dialog, passkey confirmation, request never arrived): not a PIN verdict.
    check(r.bondOutcome(r.BOND_BONDING, r.BOND_NONE, true, false) == noRequest)
    check(r.bondOutcome(r.BOND_BONDING, r.BOND_NONE, true, false, r.UNBOND_AUTH_FAILED) == noRequest)
    check(r.bondOutcome(r.BOND_BONDING, r.BOND_NONE, true, false, r.UNBOND_REMOTE_DEVICE_DOWN) == noRequest)
    // Delivered but timed out: the link, not the PIN.
    check(r.bondOutcome(r.BOND_BONDING, r.BOND_NONE, true, true, r.UNBOND_AUTH_TIMEOUT) == lost)
    check(r.bondOutcome(r.BOND_BONDING, r.BOND_NONE, true, true, r.UNBOND_DISCOVERY_IN_PROGRESS) == lost)
    // Delivered, then the radio link dropped first (Android reports that as auth failed): the link, not the PIN.
    for (code in listOf(8, 62, 133, 147)) {
        check(r.bondOutcome(r.BOND_BONDING, r.BOND_NONE, true, true, r.UNBOND_AUTH_FAILED, dropCode = code) == lost)
        check(r.bondOutcome(r.BOND_BONDING, r.BOND_NONE, true, true, dropCode = code) == lost)
        check(r.bondOutcome(r.BOND_BONDING, r.BOND_NONE, true, false, r.UNBOND_AUTH_FAILED, dropCode = code) == noRequest)
    }
    // The scooter ending the link (19/22) or an auth code after delivery is still a refused PIN.
    for (code in listOf(19, 22, 5, 137)) check(r.bondOutcome(r.BOND_BONDING, r.BOND_NONE, true, true, r.UNBOND_AUTH_FAILED, dropCode = code) == wrong)
    // Cancelled (rider or scooter), no PIN armed, or never bonding: rejected.
    check(r.bondOutcome(r.BOND_BONDING, r.BOND_NONE, true, true, r.UNBOND_AUTH_CANCELED) == rejected)
    check(r.bondOutcome(r.BOND_BONDING, r.BOND_NONE, true, true, r.UNBOND_REMOTE_AUTH_CANCELED) == rejected)
    check(r.bondOutcome(r.BOND_BONDING, r.BOND_NONE, true, false, r.UNBOND_AUTH_CANCELED) == rejected)
    check(r.bondOutcome(r.BOND_BONDING, r.BOND_NONE, false, false) == rejected)
    check(r.bondOutcome(r.BOND_BONDING, r.BOND_NONE, false, false, r.UNBOND_AUTH_FAILED) == rejected)
    check(r.bondOutcome(r.BOND_NONE, r.BOND_NONE, true, true) == rejected)
}

private fun linkFailures() {
    val r = PairingRules
    val none = r.BOND_NONE; val bonding = r.BOND_BONDING; val bonded = r.BOND_BONDED
    // Paired on the phone + an auth/encryption failure: the scooter forgot the key.
    for (code in listOf(5, 15, 137)) {
        check(r.linkFailure(bonded, pairing = false, sawBonding = false, pinDelivered = false, gattCode = code) == PairingFailure.STALE_BOND)
        check(r.linkFailure(bonded, pairing = true, sawBonding = true, pinDelivered = true, gattCode = code) == PairingFailure.STALE_BOND)
    }
    check(r.linkFailure(bonded, false, false, false, 8) == null && r.linkFailure(bonded, false, false, false, 133) == null)
    // Not pairing and not paired: an ordinary link failure.
    check(r.linkFailure(none, pairing = false, sawBonding = false, pinDelivered = false, gattCode = 5) == null)
    check(r.linkFailure(none, false, false, false, 8) == null)
    // GATT 5/137 not paired: wrong PIN only when it was delivered.
    check(r.linkFailure(none, pairing = true, sawBonding = true, pinDelivered = true, gattCode = 5) == PairingFailure.WRONG_PIN)
    check(r.linkFailure(none, true, false, true, 137) == PairingFailure.WRONG_PIN)
    check(r.linkFailure(bonding, true, true, true, 137) == PairingFailure.WRONG_PIN)
    check(r.linkFailure(none, true, true, true, 19) == PairingFailure.WRONG_PIN) // Delivered, scooter ended the link, back to not paired.
    check(r.linkFailure(none, true, true, true, 22) == PairingFailure.WRONG_PIN)
    // Delivered, then a link-loss code: the radio dropped, not the PIN.
    for (code in listOf(8, 62, 133, 147)) check(r.linkFailure(none, true, true, true, code) == PairingFailure.LINK_LOST)
    check(r.linkFailure(none, pairing = true, sawBonding = true, pinDelivered = false, gattCode = 5) == PairingFailure.NO_PIN_REQUEST)
    check(r.linkFailure(none, true, false, false, 137) == PairingFailure.NO_PIN_REQUEST)
    check(r.linkFailure(none, true, true, false, 8) == PairingFailure.NO_PIN_REQUEST)
    check(r.linkFailure(bonding, true, true, false, 19) == PairingFailure.NO_PIN_REQUEST)
    // Delivered but still bonding with a plain link code: the link, not the PIN.
    check(r.linkFailure(bonding, true, true, true, 8) == PairingFailure.LINK_LOST)
    // Link dropped before bonding started.
    check(r.linkFailure(none, pairing = true, sawBonding = false, pinDelivered = false, gattCode = 8) == PairingFailure.LINK_LOST)
    check(r.linkFailure(none, true, false, false, 133) == PairingFailure.LINK_LOST)
}

private fun failureCopy() {
    val r = PairingRules
    check(r.failureTitle(PairingFailure.WRONG_PIN) == "Scooter didn't accept this PIN")
    check(r.failureHint(PairingFailure.WRONG_PIN) == "Check the PIN or passkey for your scooter (Chetak: the one on its display or in the Chetak app) and try again")
    check(r.failureTitle(PairingFailure.NO_PIN_REQUEST) == "Pairing didn't finish")
    check(r.failureHint(PairingFailure.NO_PIN_REQUEST) == "If Android asked for a PIN, type it there. Close the official scooter app and try again")
    check(r.failureTitle(PairingFailure.STALE_BOND) == "Old Bluetooth pairing")
    check(r.failureHint(PairingFailure.STALE_BOND, "HMC_000000") == "Forget HMC_000000 in Bluetooth settings, then pair again")
    check(r.failureHint(PairingFailure.STALE_BOND, " ") == "Forget the scooter in Bluetooth settings, then pair again")
    check(r.failureTitle(PairingFailure.LINK_LOST) == "Lost the scooter while pairing")
    check(r.failureHint(PairingFailure.LINK_LOST) == "Close the official scooter app, keep the scooter on and near, and try again")
    // Titles are the connection's status lines and map back; titles are distinct and short enough for one line.
    for (f in PairingFailure.entries) {
        check(r.failureFor(r.failureTitle(f)) == f)
        check(r.failureTitle(f).length <= 40)
    }
    check(r.failureFor(null) == null && r.failureFor("Couldn't reach the scooter") == null)
    // Diagnostics say what was seen and never carry a PIN.
    val line = r.diagnostic(PairingFailure.NO_PIN_REQUEST, PinDelivery(requested = true, variant = r.VARIANT_PASSKEY_CONFIRMATION), r.BOND_NONE, reason = 1, gattCode = 137)
    check(line == "Pairing failed: NO_PIN_REQUEST (request passkey confirmation, PIN not delivered, bond none, reason 1, GATT 137)") { line }
    check(r.diagnostic(PairingFailure.LINK_LOST, PinDelivery(), r.BOND_BONDING) ==
        "Pairing failed: LINK_LOST (request not seen, PIN not delivered, bond bonding, reason none, GATT none)")
    check(r.diagnostic(PairingFailure.WRONG_PIN, PinDelivery(true, r.VARIANT_PIN, true), r.BOND_NONE).contains("request PIN, PIN delivered"))
}

private fun variants() {
    val r = PairingRules
    check(r.answerable(r.VARIANT_PIN) && r.answerable(r.VARIANT_PIN_16_DIGITS) && !r.answerable(r.VARIANT_PASSKEY))
    check(r.needsPin(r.VARIANT_PASSKEY) && !r.needsPin(r.VARIANT_PASSKEY_CONFIRMATION) && !r.needsPin(r.VARIANT_CONSENT))
    check(r.variantName(r.VARIANT_PIN) == "PIN" && r.variantName(42) == "variant 42")
}

private fun plan() {
    val W = OnboardingStep.WELCOME; val P = OnboardingStep.PERMISSIONS; val F = OnboardingStep.FIND
    val N = OnboardingStep.PIN; val C = OnboardingStep.CONNECTING; val D = OnboardingStep.DONE
    check(OnboardingPlan.first(false) == W && OnboardingPlan.first(true) == F)
    check(OnboardingPlan.steps(false, false) == listOf(W, P, F, N, C, D))
    check(OnboardingPlan.steps(false, true) == listOf(W, P, F, C, D))
    check(OnboardingPlan.steps(true, false) == listOf(F, N, C, D))
    check(OnboardingPlan.next(F, false, true) == C && OnboardingPlan.next(F, false, false) == N)
    check(OnboardingPlan.next(D, false, false) == D && OnboardingPlan.next(N, true, true) == F)
    check(OnboardingPlan.back(W, false, false) == null && OnboardingPlan.back(F, true, false) == null)
    check(OnboardingPlan.back(C, false, false) == N && OnboardingPlan.back(C, false, true) == F)
    check(OnboardingPlan.back(D, false, false) == null && OnboardingPlan.back(P, false, false) == W)
    check(OnboardingPlan.position(W, false, false) == (0 to 5) && OnboardingPlan.position(C, false, true) == (3 to 4))
    check(OnboardingPlan.position(F, true, false) == (0 to 3) && OnboardingPlan.position(D, true, false) == (2 to 3))
}
