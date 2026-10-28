/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */

package com.nikhil.yt.together

import androidx.compose.runtime.Immutable
import kotlin.math.absoluteValue

@Immutable
data class TogetherClockSnapshot(
    val estimatedOffsetMs: Long = 0L,
    val estimatedRttMs: Long = 0L,
    val hasSample: Boolean = false,
)

class TogetherClock {
    private var offsetMs: Double = 0.0
    private var rttMs: Double = 0.0
    private var sampleCount: Int = 0

    val hasSample: Boolean
        get() = sampleCount > 0

    fun onPong(
        sentAtElapsedMs: Long,
        receivedAtElapsedMs: Long,
        serverElapsedMs: Long,
    ): TogetherClockSnapshot {
        val rtt = (receivedAtElapsedMs - sentAtElapsedMs).coerceAtLeast(0L)
        val mid = sentAtElapsedMs + rtt / 2L
        // offset = hostTime - guestTime
        val newOffset = (serverElapsedMs - mid).toDouble()

        val rttAlpha = 0.15
        val offsetAlpha = if (sampleCount == 0) 1.0 else if (newOffset.absoluteValue > 1500.0) 0.5 else 0.2

        rttMs = if (sampleCount == 0) rtt.toDouble() else rttMs + (rtt - rttMs) * rttAlpha
        offsetMs = if (sampleCount == 0) newOffset else offsetMs + (newOffset - offsetMs) * offsetAlpha
        sampleCount++

        return snapshot()
    }

    /**
     * Converts a host elapsed-realtime timestamp to the estimated guest elapsed-realtime timestamp.
     * Since offset = hostTime - guestTime,
     * guestTime = hostTime - offset.
     * Returns null if no valid clock sample has been collected yet.
     */
    fun toGuestElapsedRealtime(hostElapsedRealtimeMs: Long): Long? {
        if (!hasSample) return null
        return (hostElapsedRealtimeMs - offsetMs).toLong()
    }

    /**
     * Estimates how much time has passed on the guest since the host captured and sent the message at [hostSentAtMs].
     * If no clock sample is available, returns 0L to avoid applying an unverified offset.
     */
    fun estimateElapsedSince(hostSentAtMs: Long, guestNowElapsedMs: Long): Long {
        if (!hasSample || hostSentAtMs <= 0L) return 0L
        val guestSentAt = toGuestElapsedRealtime(hostSentAtMs) ?: return 0L
        val rawElapsed = guestNowElapsedMs - guestSentAt
        // Transit/elapsed time cannot be negative, and clamp to a reasonable maximum (e.g. 5 seconds)
        // to prevent massive unintended jumps from temporary clock skew or background throttling.
        return rawElapsed.coerceIn(0L, 5000L)
    }

    fun snapshot(): TogetherClockSnapshot =
        TogetherClockSnapshot(
            estimatedOffsetMs = offsetMs.toLong(),
            estimatedRttMs = rttMs.toLong(),
            hasSample = hasSample,
        )
}
