package dev.codesurgen.ruta

// Chetooth: GPS drift on a parked scooter must not start a ride. Synthetic coordinates only.

fun main() {
    driftDoesNotMove()
    realRideMoves()
    segmenterNeedsMovement()
    println("RideStartTest: all checks passed")
}

private const val LAT = 17.6800
private const val LNG = 75.9000
private const val M_PER_DEG = 111_320.0

private fun step(t: Long, northM: Double, eastM: Double, acc: Double = 12.0, kmh: Double = 25.0) =
    TrackStep(t, LAT + northM / M_PER_DEG, LNG + eastM / (M_PER_DEG * kotlin.math.cos(Math.toRadians(LAT))), acc, 6.0, kmh, true)

private fun driftDoesNotMove() {
    // 40 s of fixes wandering within ~20 m of one spot, all reporting 25 km/h (the veranda case).
    val wander = (0 until 40).map { i -> step(i * 1_000L, 15 * kotlin.math.sin(i * 0.7), 15 * kotlin.math.cos(i * 1.3)) }
    check(!RideMath.movedAway(wander, 39_000)) { "drift counted as movement" }
    // Poor accuracy raises the bar: 60 m with 30 m accuracy is still within 3 × accuracy.
    val sloppy = listOf(step(0, 0.0, 0.0, acc = 30.0), step(5_000, 60.0, 0.0, acc = 30.0))
    check(!RideMath.movedAway(sloppy, 5_000))
}

private fun realRideMoves() {
    // 25 km/h ≈ 7 m/s straight north: 70 m after 10 s.
    val ride = (0..10).map { i -> step(i * 1_000L, i * 7.0, 0.0, acc = 5.0) }
    check(RideMath.movedAway(ride, 10_000)) { "a real ride did not count as movement" }
    check(!RideMath.movedAway(ride.take(1), 0)) // one fix is not enough
}

private fun segmenterNeedsMovement() {
    val s = RideSegmenter()
    for (t in 0..20) check(s.update(t * 1_000L, true, null, 25.0, movedAway = false) == null) { "started without movement" }
    // Once the position really moves away, the speed rule starts the ride (start = when the speed began).
    val e = s.update(21_000, true, null, 25.0, movedAway = true)
    check(e is RideEvent.Started && e.time == 0L) { "expected a start, got $e" }
    // Default (Vida callers): unchanged behaviour.
    val v = RideSegmenter()
    for (t in 0..9) v.update(t * 1_000L, true, null, 25.0)
    check(v.update(10_000, true, null, 25.0) is RideEvent.Started)
}
