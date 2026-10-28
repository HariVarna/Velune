/*
 * Velune Project
 * Unit tests for Phase 2: LAN / Local Listen Together.
 */

package com.nikhil.yt.together

import java.net.BindException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TogetherLanTest {

    @Test
    fun togetherLink_validDeepLink_roundTrip() {
        val join = TogetherJoinInfo(
            host = "192.168.1.100",
            port = 42117,
            sessionId = "session-abc-123",
            sessionKey = "key-secret-xyz",
        )
        val encoded = TogetherLink.encode(join)
        val decoded = TogetherLink.decode(encoded)
        assertEquals(join, decoded)
    }

    @Test
    fun togetherLink_extractsEmbeddedLinkFromChatMessage() {
        val raw = "Hey! Join my Velune Listen Together room: velune://together?host=192.168.1.55&port=42117&sid=sess1&key=key1 See you there!"
        val decoded = TogetherLink.decode(raw)
        assertNotNull(decoded)
        assertEquals("192.168.1.55", decoded!!.host)
        assertEquals(42117, decoded.port)
        assertEquals("sess1", decoded.sessionId)
        assertEquals("key1", decoded.sessionKey)
    }

    @Test
    fun togetherLink_decodesWsUrl() {
        val decoded = TogetherLink.decode("ws://10.0.0.12:42117/together?sid=sid999&key=key888")
        assertNotNull(decoded)
        assertEquals("10.0.0.12", decoded!!.host)
        assertEquals(42117, decoded.port)
        assertEquals("sid999", decoded.sessionId)
        assertEquals("key888", decoded.sessionKey)
    }

    @Test
    fun togetherLink_decodesCompactFormat() {
        val decoded = TogetherLink.decode("192.168.0.25 | 42117 | session_id | session_key")
        assertNotNull(decoded)
        assertEquals("192.168.0.25", decoded!!.host)
        assertEquals(42117, decoded.port)
        assertEquals("session_id", decoded.sessionId)
        assertEquals("session_key", decoded.sessionKey)
    }

    @Test
    fun togetherLink_ipv6Address_formatsWebSocketUrlWithBrackets() {
        val join = TogetherJoinInfo(
            host = "2001:db8::1",
            port = 42117,
            sessionId = "s",
            sessionKey = "k",
        )
        assertEquals("ws://[2001:db8::1]:42117/together", join.toWebSocketUrl())
    }

    @Test
    fun togetherLink_rejectsWildcardBindAddresses() {
        assertFalse(TogetherLink.isValidHostAddress("0.0.0.0"))
        assertFalse(TogetherLink.isValidHostAddress("::"))
        assertFalse(TogetherLink.isValidHostAddress("0:0:0:0:0:0:0:0"))

        val invalidJoin = TogetherJoinInfo(
            host = "0.0.0.0",
            port = 42117,
            sessionId = "s",
            sessionKey = "k",
        )
        assertTrue(invalidJoin.validate().isFailure)
        assertNull(TogetherLink.decode("velune://together?host=0.0.0.0&port=42117&sid=s&key=k"))
    }

    @Test
    fun togetherLink_rejectsMalformedHostOrSchemeInHost() {
        assertFalse(TogetherLink.isValidHostAddress("http://192.168.1.1"))
        assertFalse(TogetherLink.isValidHostAddress("ws://192.168.1.1"))
        assertFalse(TogetherLink.isValidHostAddress("192.168.1.1/path"))
        assertFalse(TogetherLink.isValidHostAddress("invalid host with spaces"))
        assertFalse(TogetherLink.isValidHostAddress(""))

        assertNull(TogetherLink.decode("velune://together?host=http://192.168.1.1&port=42117&sid=s&key=k"))
    }

    @Test
    fun togetherLink_rejectsPortOutOfRange() {
        val joinNegative = TogetherJoinInfo("192.168.1.1", -1, "s", "k")
        val joinZero = TogetherJoinInfo("192.168.1.1", 0, "s", "k")
        val joinOverflow = TogetherJoinInfo("192.168.1.1", 70000, "s", "k")

        assertTrue(joinNegative.validate().isFailure)
        assertTrue(joinZero.validate().isFailure)
        assertTrue(joinOverflow.validate().isFailure)

        assertNull(TogetherLink.decode("velune://together?host=192.168.1.1&port=0&sid=s&key=k"))
        assertNull(TogetherLink.decode("velune://together?host=192.168.1.1&port=65536&sid=s&key=k"))
    }

    @Test
    fun togetherLink_rejectsMissingSessionOrKey() {
        val emptySid = TogetherJoinInfo("192.168.1.1", 42117, "", "k")
        val emptyKey = TogetherJoinInfo("192.168.1.1", 42117, "s", "")

        assertTrue(emptySid.validate().isFailure)
        assertTrue(emptyKey.validate().isFailure)

        assertNull(TogetherLink.decode("velune://together?host=192.168.1.1&port=42117&sid=&key=k"))
        assertNull(TogetherLink.decode("velune://together?host=192.168.1.1&port=42117&sid=s&key="))
    }

    @Test
    fun togetherLink_decodeWithDiagnostics_providesDescriptiveFailures() {
        val r1 = TogetherLink.decodeWithDiagnostics("")
        assertTrue(r1.isFailure)
        assertTrue(r1.exceptionOrNull()?.message?.contains("empty", ignoreCase = true) == true)

        val r2 = TogetherLink.decodeWithDiagnostics("not-a-valid-link")
        assertTrue(r2.isFailure)
        assertTrue(r2.exceptionOrNull()?.message?.contains("Invalid invitation link format", ignoreCase = true) == true)
    }

    @Test
    fun togetherServer_detectsPortConflict() = runBlocking {
        // Allocate a free port by binding a ServerSocket
        val tempSocket = ServerSocket(0)
        val port = tempSocket.localPort

        val scope = CoroutineScope(Dispatchers.Default)
        val server = TogetherServer(
            scope = scope,
            sessionId = "sess-conflict",
            sessionKey = "key-conflict",
            hostDisplayName = "Host",
            initialSettings = TogetherRoomSettings(
                allowGuestsToAddTracks = true,
                allowGuestsToControlPlayback = true,
                requireHostApprovalToJoin = false,
            ),
        )

        try {
            // Attempting to start TogetherServer on an already occupied port must fail with BindException
            server.start(port)
            fail("Expected BindException when port is already in use")
        } catch (e: BindException) {
            assertTrue(e.message?.contains("already in use") == true)
            assertFalse(server.isRunning)
        } finally {
            tempSocket.close()
            server.stop()
            scope.cancel()
        }
    }

    @Test
    fun togetherServer_startAndStopLifecycle() = runBlocking {
        // Find a random available port
        val port = ServerSocket(0).use { it.localPort }

        val scope = CoroutineScope(Dispatchers.Default)
        val server = TogetherServer(
            scope = scope,
            sessionId = "sess-life",
            sessionKey = "key-life",
            hostDisplayName = "Host",
            initialSettings = TogetherRoomSettings(
                allowGuestsToAddTracks = true,
                allowGuestsToControlPlayback = true,
                requireHostApprovalToJoin = false,
            ),
        )

        assertFalse(server.isRunning)
        server.start(port)
        assertTrue(server.isRunning)

        // Starting again must throw IllegalStateException
        try {
            server.start(port)
            fail("Expected IllegalStateException on duplicate start")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("already running") == true)
        }

        server.stop()
        assertFalse(server.isRunning)
        scope.cancel()
    }

    @Test
    fun togetherClient_connectionFailureMessage_diagnostics() {
        val client = TogetherClient(CoroutineScope(Dispatchers.Default))

        val unknownHost = UnknownHostException("bad.local.host")
        assertTrue(client.connectionFailureMessage(unknownHost).contains("Host not found"))

        val noRoute = NoRouteToHostException("No route to host")
        val noRouteMsg = client.connectionFailureMessage(noRoute)
        assertTrue(noRouteMsg.contains("Host unreachable"))
        assertTrue(noRouteMsg.contains("AP/client isolation"))

        val refused = ConnectException("Connection refused")
        val refusedMsg = client.connectionFailureMessage(refused)
        assertTrue(refusedMsg.contains("Connection refused"))

        val timeout = SocketTimeoutException("Connect timed out")
        val timeoutMsg = client.connectionFailureMessage(timeout)
        assertTrue(timeoutMsg.contains("Connection timed out"))
        assertTrue(timeoutMsg.contains("AP/client isolation"))

        val ssl = SSLHandshakeException("Certificate failure")
        assertTrue(client.connectionFailureMessage(ssl).contains("Secure connection failed"))
    }

    @Test
    fun togetherParticipant_hostApprovalFlow() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val scope = CoroutineScope(Dispatchers.Default)
        val server = TogetherServer(
            scope = scope,
            sessionId = "sess-apprv",
            sessionKey = "key-apprv",
            hostDisplayName = "Host",
            initialSettings = TogetherRoomSettings(
                allowGuestsToAddTracks = true,
                allowGuestsToControlPlayback = true,
                requireHostApprovalToJoin = true,
            ),
        )
        server.start(port)

        val joinInfo = TogetherJoinInfo(
            host = "127.0.0.1",
            port = port,
            sessionId = "sess-apprv",
            sessionKey = "key-apprv",
        )
        val guestClientId = "guest-client-1"
        val client = TogetherClient(scope, clientId = guestClientId)

        try {
            client.connect(joinInfo, "GuestUser")

            // Wait for Welcome event on guest
            val welcomeEvent = kotlinx.coroutines.withTimeoutOrNull(5000) {
                kotlinx.coroutines.flow.first(client.events) { it is TogetherClientEvent.Welcome } as TogetherClientEvent.Welcome
            }
            assertNotNull("Welcome event should be received", welcomeEvent)
            assertTrue("Guest must be marked pending on welcome when approval enabled", welcomeEvent!!.welcome.isPending)

            // Verify server participant list shows guest as pending
            val pendingParticipants = server.currentParticipants()
            val guestParticipant = pendingParticipants.firstOrNull { it.name == "GuestUser" }
            assertNotNull(guestParticipant)
            assertTrue(guestParticipant!!.isPending)

            // Broadcast room state with tracks while guest is pending
            val testTrack = TogetherTrack(id = "track-1", title = "Test Song")
            server.broadcastRoomState(
                TogetherRoomState(
                    sessionId = "sess-apprv",
                    hostId = "host-id",
                    queue = listOf(testTrack),
                    isPlaying = true,
                ),
            )

            // Pending guest should receive sanitized state (empty queue)
            val stateBeforeApproval = kotlinx.coroutines.withTimeoutOrNull(3000) {
                kotlinx.coroutines.flow.first(client.events) { it is TogetherClientEvent.RoomState } as TogetherClientEvent.RoomState
            }
            assertNotNull(stateBeforeApproval)
            assertTrue("Pending guest must not receive queue items", stateBeforeApproval!!.state.queue.isEmpty())
            assertFalse("Pending guest must have isPlaying false", stateBeforeApproval.state.isPlaying)

            // Host approves participant
            server.approveParticipant(guestParticipant.id, true)

            // Guest receives JoinDecision with approved = true
            val decisionEvent = kotlinx.coroutines.withTimeoutOrNull(3000) {
                kotlinx.coroutines.flow.first(client.events) { it is TogetherClientEvent.JoinDecision } as TogetherClientEvent.JoinDecision
            }
            assertNotNull(decisionEvent)
            assertTrue(decisionEvent!!.decision.approved)

            // Server participant list now shows approved guest
            val updatedParticipants = server.currentParticipants()
            val approvedGuest = updatedParticipants.firstOrNull { it.id == guestParticipant.id }
            assertNotNull(approvedGuest)
            assertFalse(approvedGuest!!.isPending)

            // Broadcast state again; approved guest receives actual queue
            server.broadcastRoomState(
                TogetherRoomState(
                    sessionId = "sess-apprv",
                    hostId = "host-id",
                    queue = listOf(testTrack),
                    isPlaying = true,
                ),
            )
            val stateAfterApproval = kotlinx.coroutines.withTimeoutOrNull(3000) {
                kotlinx.coroutines.flow.first(client.events) {
                    it is TogetherClientEvent.RoomState && it.state.queue.isNotEmpty()
                } as TogetherClientEvent.RoomState
            }
            assertNotNull(stateAfterApproval)
            assertEquals("track-1", stateAfterApproval!!.state.queue.first().id)
        } finally {
            client.disconnect()
            server.stop()
            scope.cancel()
        }
    }

    @Test
    fun togetherParticipant_hostRejectionFlow() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val scope = CoroutineScope(Dispatchers.Default)
        val server = TogetherServer(
            scope = scope,
            sessionId = "sess-rej",
            sessionKey = "key-rej",
            hostDisplayName = "Host",
            initialSettings = TogetherRoomSettings(requireHostApprovalToJoin = true),
        )
        server.start(port)

        val joinInfo = TogetherJoinInfo(
            host = "127.0.0.1",
            port = port,
            sessionId = "sess-rej",
            sessionKey = "key-rej",
        )
        val client = TogetherClient(scope, clientId = "guest-client-rej")

        try {
            client.connect(joinInfo, "RejectedGuest")
            val welcomeEvent = kotlinx.coroutines.withTimeoutOrNull(5000) {
                kotlinx.coroutines.flow.first(client.events) { it is TogetherClientEvent.Welcome } as TogetherClientEvent.Welcome
            }
            assertNotNull(welcomeEvent)

            // Host rejects participant
            server.approveParticipant(welcomeEvent!!.welcome.participantId, false)

            val decision = kotlinx.coroutines.withTimeoutOrNull(3000) {
                kotlinx.coroutines.flow.first(client.events) { it is TogetherClientEvent.JoinDecision } as TogetherClientEvent.JoinDecision
            }
            assertNotNull(decision)
            assertFalse(decision!!.decision.approved)

            // Participant removed from server
            val participants = server.currentParticipants()
            assertNull(participants.firstOrNull { it.id == welcomeEvent.welcome.participantId })
        } finally {
            client.disconnect()
            server.stop()
            scope.cancel()
        }
    }

    @Test
    fun togetherParticipant_kickAndBanFlow() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val scope = CoroutineScope(Dispatchers.Default)
        val server = TogetherServer(
            scope = scope,
            sessionId = "sess-kb",
            sessionKey = "key-kb",
            hostDisplayName = "Host",
            initialSettings = TogetherRoomSettings(requireHostApprovalToJoin = false),
        )
        server.start(port)

        val joinInfo = TogetherJoinInfo(
            host = "127.0.0.1",
            port = port,
            sessionId = "sess-kb",
            sessionKey = "key-kb",
        )
        val bannedClientId = "troublemaker-123"
        val client = TogetherClient(scope, clientId = bannedClientId)

        try {
            client.connect(joinInfo, "BadActor")
            val welcomeEvent = kotlinx.coroutines.withTimeoutOrNull(5000) {
                kotlinx.coroutines.flow.first(client.events) { it is TogetherClientEvent.Welcome } as TogetherClientEvent.Welcome
            }
            assertNotNull(welcomeEvent)
            val pid = welcomeEvent!!.welcome.participantId

            // Host bans participant
            server.banParticipant(pid, "Spamming")

            // Wait for disconnected event on client
            val disconnect = kotlinx.coroutines.withTimeoutOrNull(3000) {
                kotlinx.coroutines.flow.first(client.events) { it is TogetherClientEvent.Disconnected }
            }
            assertNotNull(disconnect)

            // Server participants list must not have bad actor
            assertNull(server.currentParticipants().firstOrNull { it.id == pid })

            // Attempt to connect again with same banned client ID
            val client2 = TogetherClient(scope, clientId = bannedClientId)
            client2.connect(joinInfo, "BadActor")

            val issueOrDisconnect = kotlinx.coroutines.withTimeoutOrNull(5000) {
                kotlinx.coroutines.flow.first(client2.events) {
                    it is TogetherClientEvent.Error || it is TogetherClientEvent.Disconnected
                }
            }
            assertNotNull(issueOrDisconnect)
            // Still not in participants list
            assertEquals(1, server.currentParticipants().size) // Only host
            client2.disconnect()
        } finally {
            client.disconnect()
            server.stop()
            scope.cancel()
        }
    }
}
