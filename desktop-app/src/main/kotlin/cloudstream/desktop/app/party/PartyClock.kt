package cloudstream.desktop.app.party

import kotlin.math.abs
import kotlin.math.sign

/**
 * Host clock estimate from ping/pong (NTP style): `offset = hostT - (t0 + t2) / 2`. The sample with the smallest
 * round trip is trusted most (least queueing), out of the last [keep].
 */
class ClockSync(private val keep: Int = 8) {
    private val samples = ArrayDeque<Pair<Long, Long>>() // rtt, offset

    fun add(t0: Long, hostT: Long, t2: Long) {
        val rtt = t2 - t0
        if (rtt < 0) return
        samples.addLast(rtt to (hostT - (t0 + t2) / 2))
        while (samples.size > keep) samples.removeFirst()
    }

    fun reset() = samples.clear()
    val synced: Boolean get() = samples.isNotEmpty()
    /** host time = local time + offsetMs (0 until the first sample). */
    val offsetMs: Long get() = samples.minByOrNull { it.first }?.second ?: 0
}

/** What to do about drift this tick: seek (rarely) and the playback speed to run at. */
data class DriftDecision(val seekTo: Long?, val speed: Double)

/**
 * Keeps a guest on the room's timeline.
 * - drift > [seekAboveMs]: seek, but at most once per [minSeekGapMs]. A seek that does not hold (the guest is behind
 *   again soon after: slow connection, buffers after every seek) counts as a failed catch-up; after
 *   [maxFailedCatchUps] the guest is marked [slow] and stops chasing (seeking again would only buffer again).
 * - drift in ([nudgeAboveMs], seekAboveMs]: play [nudge] faster/slower until within [settleBelowMs] (seamless in mpv).
 * - otherwise: the room's speed.
 */
class DriftController(
    private val seekAboveMs: Long = 2000,
    // Low enough to remove the network delay at the moment of "play" (~100 ms through a relay), which a 5 % speed
    // change closes in ~2 s without anyone noticing.
    private val nudgeAboveMs: Long = 100,
    private val settleBelowMs: Long = 40,
    private val nudge: Double = 0.05,
    private val minSeekGapMs: Long = 15_000,
    private val maxFailedCatchUps: Int = 2,
) {
    var slow = false
        private set
    private var dir = 0
    private var lastSeekAt: Long? = null
    private var failed = 0

    fun reset() { slow = false; dir = 0; lastSeekAt = null; failed = 0 }

    fun update(now: Long, expectedMs: Long, localMs: Long, baseSpeed: Double): DriftDecision {
        val d = expectedMs - localMs // > 0: behind
        val ad = abs(d)
        val last = lastSeekAt
        if (ad > seekAboveMs) {
            dir = 0
            if (slow || (last != null && now - last < minSeekGapMs)) return DriftDecision(null, baseSpeed)
            failed = if (last != null && now - last < 2 * minSeekGapMs) failed + 1 else 0
            if (failed >= maxFailedCatchUps) { slow = true; return DriftDecision(null, baseSpeed) }
            lastSeekAt = now
            return DriftDecision(expectedMs, baseSpeed)
        }
        if (ad < nudgeAboveMs && (last == null || now - last >= 2 * minSeekGapMs)) failed = 0
        dir = when {
            dir != 0 && ad < settleBelowMs -> 0
            dir != 0 || ad > nudgeAboveMs -> d.sign
            else -> 0
        }
        return DriftDecision(null, baseSpeed * (1 + dir * nudge))
    }
}
