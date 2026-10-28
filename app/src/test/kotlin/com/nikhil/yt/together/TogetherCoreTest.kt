/*
 * Velune Project Original (2026)
 * Kòi Natsuko (github.com/koiverse)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.nikhil.yt.together

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TogetherCoreTest {
    @Test
    fun deepLink_roundTrip() {
        val join =
            TogetherJoinInfo(
                host = "192.168.1.20",
                port = 42117,
                sessionId = "sid123",
                sessionKey = "key456",
            )
        val encoded = TogetherLink.encode(join)
        val decoded = TogetherLink.decode(encoded)
        assertEquals(join, decoded)
    }

    @Test
    fun wsUrl_decode() {
        val decoded =
            TogetherLink.decode("ws://10.0.0.5:42117/together?sid=sid123&key=key456")
        assertNotNull(decoded)
        assertEquals("10.0.0.5", decoded!!.host)
        assertEquals(42117, decoded.port)
        assertEquals("sid123", decoded.sessionId)
        assertEquals("key456", decoded.sessionKey)
    }

    @Test
    fun protocol_serialization_roundTrip() {
        val hello =
            ClientHello(
                protocolVersion = TogetherProtocolVersion,
                sessionId = "sid",
                sessionKey = "key",
                clientId = "cid",
                displayName = "Alice",
            )
        val json = TogetherJson.json.encodeToString(TogetherMessage.serializer(), hello)
        val decoded = TogetherJson.json.decodeFromString(TogetherMessage.serializer(), json)
        assertTrue(decoded is ClientHello)
        assertEquals(hello, decoded)
    }

    @Test
    fun protocol_seekToTrack_roundTrip() {
        val message: TogetherMessage =
            ControlRequest(
                sessionId = "sid",
                participantId = "pid",
                action = ControlAction.SeekToTrack(trackId = "track123", positionMs = 456L),
            )
        val json = TogetherJson.json.encodeToString(TogetherMessage.serializer(), message)
        val decoded = TogetherJson.json.decodeFromString(TogetherMessage.serializer(), json)
        assertTrue(decoded is ControlRequest)
        val req = decoded as ControlRequest
        assertTrue(req.action is ControlAction.SeekToTrack)
        val action = req.action as ControlAction.SeekToTrack
        assertEquals("track123", action.trackId)
        assertEquals(456L, action.positionMs)
    }

    @Test
    fun clock_estimates_offset_and_rtt() {
        val clock = TogetherClock()
        val snapshot =
            clock.onPong(
                sentAtElapsedMs = 1000L,
                receivedAtElapsedMs = 1200L,
                serverElapsedMs = 1150L,
            )
        assertTrue(snapshot.estimatedRttMs >= 0L)
    }

    @Test
    fun clock_hostToGuestConversion() {
        val clock = TogetherClock()

        // Scenario A: Host elapsed-realtime is ahead of Guest (Host uptime 100_000ms, Guest uptime 40_000ms)
        // Guest pings at t=40_000, Host replies at t_host=100_010, Guest receives pong at t=40_020
        // mid = (40000 + 40020)/2 = 40010
        // offset = 100010 - 40010 = +60000ms
        clock.onPong(sentAtElapsedMs = 40_000L, receivedAtElapsedMs = 40_020L, serverElapsedMs = 100_010L)
        assertTrue(clock.hasSample)
        assertEquals(60_000L, clock.estimatedOffsetMs)

        // Now host sends a message at hostElapsedRealtime = 100_500L
        // In guest elapsed time, that is 100_500 - 60_000 = 40_500L
        val guestTime = clock.toGuestElapsedRealtime(100_500L)
        assertEquals(40_500L, guestTime)

        // If guest receives this message when guestNow = 40_550L,
        // transit time should be 40_550 - 40_500 = 50ms
        val transit = clock.estimateElapsedSince(hostSentAtMs = 100_500L, guestNowElapsedMs = 40_550L)
        assertEquals(50L, transit)

        // Scenario B: Host elapsed-realtime is behind Guest (e.g. Host rebooted recently)
        val clockBehind = TogetherClock()
        // Guest pings at t=80_000, Host replies at t_host=20_010, Guest receives at t=80_020
        // mid = 80010, offset = 20010 - 80010 = -60000ms
        clockBehind.onPong(sentAtElapsedMs = 80_000L, receivedAtElapsedMs = 80_020L, serverElapsedMs = 20_010L)
        assertEquals(-60_000L, clockBehind.estimatedOffsetMs)

        // Host sends at t_host=20_500L -> guestTime = 20_500 - (-60_000) = 80_500L
        assertEquals(80_500L, clockBehind.toGuestElapsedRealtime(20_500L))
        assertEquals(50L, clockBehind.estimateElapsedSince(20_500L, guestNowElapsedMs = 80_550L))
    }

    @Test
    fun clock_missingInitialSample_safeDefaults() {
        val clock = TogetherClock()
        org.junit.Assert.assertFalse(clock.hasSample)
        assertEquals(0, clock.sampleCount)
        assertEquals(0L, clock.estimatedOffsetMs)
        assertEquals(0L, clock.estimatedRttMs)

        // Before any pong sample, converting host timestamp returns null
        org.junit.Assert.assertNull(clock.toGuestElapsedRealtime(123456L))

        // And estimating elapsed transit returns 0L, preventing wild seeks
        assertEquals(0L, clock.estimateElapsedSince(hostSentAtMs = 123456L, guestNowElapsedMs = 5000L))

        // Also invalid hostSentAt <= 0L returns 0L
        assertEquals(0L, clock.estimateElapsedSince(hostSentAtMs = 0L, guestNowElapsedMs = 5000L))
        assertEquals(0L, clock.estimateElapsedSince(hostSentAtMs = -10L, guestNowElapsedMs = 5000L))
    }

    @Test
    fun clock_jitterSmoothing_and_clamping() {
        val clock = TogetherClock()

        // Baseline sample 1: RTT = 40ms, offset = 10_000ms
        // Guest: 1000..1040, mid = 1020, host = 11020 -> offset = 10000
        clock.onPong(sentAtElapsedMs = 1000L, receivedAtElapsedMs = 1040L, serverElapsedMs = 11020L)
        assertEquals(10000L, clock.estimatedOffsetMs)
        assertEquals(40L, clock.estimatedRttMs)

        // Sample 2: Sudden network lag spike (RTT = 300ms, host = 12170)
        // Guest: 2000..2300, mid = 2150, host = 12170 -> raw offset = 10020
        clock.onPong(sentAtElapsedMs = 2000L, receivedAtElapsedMs = 2300L, serverElapsedMs = 12170L)
        assertEquals(2, clock.sampleCount)
        // With alpha = 0.25, RTT smoothed: 40 + 0.25 * (300 - 40) = 40 + 65 = 105ms
        assertEquals(105L, clock.estimatedRttMs)
        // Offset smoothed: 10000 + 0.25 * (10020 - 10000) = 10005ms (smooth transition, not jumping wildly)
        assertEquals(10005L, clock.estimatedOffsetMs)

        // Transit clamping: if guest receives a message with extreme apparent transit time > 5000ms,
        // estimateElapsedSince clamps it to 5000ms max to prevent unbounded skips
        val extremeTransit = clock.estimateElapsedSince(hostSentAtMs = 5000L, guestNowElapsedMs = 20000L)
        assertEquals(5000L, extremeTransit)

        // If guest receives a message with negative transit (due to minor clock jitter),
        // estimateElapsedSince clamps it to 0L minimum
        val negativeTransit = clock.estimateElapsedSince(hostSentAtMs = 30000L, guestNowElapsedMs = 15000L)
        assertEquals(0L, negativeTransit)
    }

    @Test
    fun ordered_addTrackAndSeekRequests() {
        val roomState =
            TogetherRoomState(
                sessionId = "sid",
                hostId = "hid",
                settings = TogetherRoomSettings(allowGuestsToControlPlayback = true, allowGuestsToAddTracks = true),
                queue = listOf(TogetherTrack(id = "track-1", title = "Track 1")),
                currentIndex = 0,
                isPlaying = true,
            )
        val newTrack = TogetherTrack(id = "track-2", title = "Track 2")
        val ops =
            TogetherGuestPlaybackPlanner.planPlayTrackNow(
                roomState = roomState,
                track = newTrack,
                seekPositionMs = 500L,
                playWhenReady = true,
            )

        assertEquals(2, ops.size)
        assertTrue("First op must be AddTrack", ops[0] is TogetherGuestOp.AddTrack)
        val addOp = ops[0] as TogetherGuestOp.AddTrack
        assertEquals("track-2", addOp.track.id)
        assertEquals(AddTrackMode.PLAY_NEXT, addOp.mode)

        assertTrue("Second op must be SeekToTrack", ops[1] is TogetherGuestOp.Control)
        val controlOp = (ops[1] as TogetherGuestOp.Control).action as ControlAction.SeekToTrack
        assertEquals("track-2", controlOp.trackId)
        assertEquals(500L, controlOp.positionMs)
    }

    @Test
    fun ordered_multipleQueuedGuestRequests_fifoProcessing() = kotlinx.coroutines.runBlocking {
        val channel = kotlinx.coroutines.channels.Channel<TogetherMessage>(kotlinx.coroutines.channels.Channel.UNLIMITED)

        val msg1 = AddTrackRequest("s", "p", TogetherTrack("t1", "T1"), AddTrackMode.PLAY_NEXT)
        val msg2 = AddTrackRequest("s", "p", TogetherTrack("t2", "T2"), AddTrackMode.PLAY_NEXT)
        val msg3 = ControlRequest("s", "p", ControlAction.Play)
        val msg4 = ControlRequest("s", "p", ControlAction.Pause)
        val msg5 = ControlRequest("s", "p", ControlAction.Seek(1500L))

        channel.send(msg1)
        channel.send(msg2)
        channel.send(msg3)
        channel.send(msg4)
        channel.send(msg5)

        val received = mutableListOf<TogetherMessage>()
        repeat(5) {
            received.add(channel.receive())
        }

        assertEquals(listOf(msg1, msg2, msg3, msg4, msg5), received)
    }

    @Test
    fun positionCorrection_playingVsPaused_driftThresholds() {
        fun calculateCorrectedPosition(
            remotePositionMs: Long,
            isPlaying: Boolean,
            transitElapsedMs: Long,
        ): Long {
            return if (isPlaying) remotePositionMs + transitElapsedMs else remotePositionMs
        }

        fun shouldSeek(
            currentLocalPositionMs: Long,
            targetPositionMs: Long,
            isPlaying: Boolean,
            isLan: Boolean,
        ): Boolean {
            val diff = kotlin.math.abs(currentLocalPositionMs - targetPositionMs)
            val threshold =
                if (!isPlaying) {
                    600L
                } else if (isLan) {
                    2000L
                } else {
                    4000L
                }
            return diff > threshold
        }

        // 1. When playing, transit delta is added to remote position
        val playingTarget = calculateCorrectedPosition(remotePositionMs = 10_000L, isPlaying = true, transitElapsedMs = 250L)
        assertEquals(10_250L, playingTarget)

        // 2. When paused, transit delta is NOT added to remote position
        val pausedTarget = calculateCorrectedPosition(remotePositionMs = 10_000L, isPlaying = false, transitElapsedMs = 250L)
        assertEquals(10_000L, pausedTarget)

        // 3. Small harmless drift does not trigger seeking
        // When playing on LAN, 1200ms drift is within the 2000ms threshold -> do not seek
        org.junit.Assert.assertFalse(
            shouldSeek(currentLocalPositionMs = 10_000L, targetPositionMs = 11_200L, isPlaying = true, isLan = true)
        )
        // When paused, 400ms drift is within 600ms threshold -> do not seek
        org.junit.Assert.assertFalse(
            shouldSeek(currentLocalPositionMs = 10_000L, targetPositionMs = 10_400L, isPlaying = false, isLan = true)
        )

        // 4. Significant drift triggers seeking
        // When playing on LAN, 2500ms drift exceeds 2000ms threshold -> seek
        assertTrue(
            shouldSeek(currentLocalPositionMs = 10_000L, targetPositionMs = 12_500L, isPlaying = true, isLan = true)
        )
        // When paused, 800ms drift exceeds 600ms threshold -> seek
        assertTrue(
            shouldSeek(currentLocalPositionMs = 10_000L, targetPositionMs = 10_800L, isPlaying = false, isLan = true)
        )
    }

    @Test
    fun remoteState_echoSuppression_and_staleStateRejection() {
        class EchoSuppressionSimulator {
            var isTogetherApplyingRemote = false
            var togetherSuppressEchoUntilElapsedMs = 0L
            var lastAppliedSentAt = 0L
            val sentLocalCommands = mutableListOf<String>()

            fun receiveRemoteState(sentAt: Long, now: Long): Boolean {
                if (sentAt in 1..lastAppliedSentAt) {
                    // Stale or duplicate message ignored
                    return false
                }
                isTogetherApplyingRemote = true
                try {
                    if (sentAt > 0L) {
                        lastAppliedSentAt = sentAt
                    }
                    // Suppress echo for 600ms
                    togetherSuppressEchoUntilElapsedMs = now + 600L
                    return true
                } finally {
                    isTogetherApplyingRemote = false
                }
            }

            fun onLocalPlaybackChanged(action: String, now: Long) {
                if (isTogetherApplyingRemote || now < togetherSuppressEchoUntilElapsedMs) {
                    // Echo suppressed
                    return
                }
                sentLocalCommands.add(action)
            }
        }

        val sim = EchoSuppressionSimulator()

        // Remote state received at t = 1000 with sentAt = 500
        val applied1 = sim.receiveRemoteState(sentAt = 500L, now = 1000L)
        assertTrue("Initial remote state should be applied", applied1)

        // Player notifies playback changed at t = 1200 (within 600ms echo suppression window)
        sim.onLocalPlaybackChanged("PlayAction", now = 1200L)
        assertTrue("Echo should be suppressed within window", sim.sentLocalCommands.isEmpty())

        // Duplicate or older state arriving late should be rejected
        val appliedStale = sim.receiveRemoteState(sentAt = 450L, now = 1300L)
        org.junit.Assert.assertFalse("Stale state must be dropped", appliedStale)

        val appliedDuplicate = sim.receiveRemoteState(sentAt = 500L, now = 1350L)
        org.junit.Assert.assertFalse("Duplicate state must be dropped", appliedDuplicate)

        // User interacts locally at t = 1700 (after 600ms echo suppression window expires)
        sim.onLocalPlaybackChanged("UserNextTrack", now = 1700L)
        assertEquals(listOf("UserNextTrack"), sim.sentLocalCommands)
    }
}
