/*
 * Velune Project
 * Unit tests for Listen Together online networking, response parsing, and security.
 */

package com.nikhil.yt.together

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TogetherOnlineTest {

    @Test
    fun sanitizeBaseUrl_validHttps_stripsTrailingSlash() {
        val sanitized = TogetherOnlineEndpoint.sanitizeBaseUrl("https://velune-server.onrender.com/")
        assertEquals("https://velune-server.onrender.com", sanitized)
    }

    @Test
    fun sanitizeBaseUrl_validHttpLocalhost_allowed() {
        val sanitized = TogetherOnlineEndpoint.sanitizeBaseUrl("http://localhost:8080/")
        assertEquals("http://localhost:8080", sanitized)
    }

    @Test
    fun sanitizeBaseUrl_invalidOrUnsupportedSchemes_returnsNull() {
        assertNull(TogetherOnlineEndpoint.sanitizeBaseUrl("ftp://example.com"))
        assertNull(TogetherOnlineEndpoint.sanitizeBaseUrl("ws://example.com"))
        assertNull(TogetherOnlineEndpoint.sanitizeBaseUrl("not-a-url"))
        assertNull(TogetherOnlineEndpoint.sanitizeBaseUrl(""))
        assertNull(TogetherOnlineEndpoint.sanitizeBaseUrl("   "))
    }

    @Test
    fun isLocalOrLoopback_identifiesLoopbackAddresses() {
        assertTrue(TogetherOnlineEndpoint.isLocalOrLoopback("http://localhost:8080"))
        assertTrue(TogetherOnlineEndpoint.isLocalOrLoopback("http://127.0.0.1:8080"))
        assertTrue(TogetherOnlineEndpoint.isLocalOrLoopback("http://10.0.2.2:8080"))
        assertFalse(TogetherOnlineEndpoint.isLocalOrLoopback("https://velune-server.onrender.com"))
        assertFalse(TogetherOnlineEndpoint.isLocalOrLoopback("http://my-remote-server.com:8080"))
    }

    @Test
    fun onlineWebSocketUrlOrNull_resolvesRelativePathAgainstBase() {
        val wsUrl = TogetherOnlineEndpoint.onlineWebSocketUrlOrNull(
            rawWsUrl = "/v1/together/ws",
            baseUrl = "https://velune-server.onrender.com",
        )
        assertEquals("wss://velune-server.onrender.com/v1/together/ws", wsUrl)
    }

    @Test
    fun onlineWebSocketUrlOrNull_resolvesRelativePathAgainstLocalBase() {
        val wsUrl = TogetherOnlineEndpoint.onlineWebSocketUrlOrNull(
            rawWsUrl = "/v1/together/ws",
            baseUrl = "http://10.0.2.2:8080",
        )
        assertEquals("ws://10.0.2.2:8080/v1/together/ws", wsUrl)
    }

    @Test
    fun onlineWebSocketUrlOrNull_neverDowngradesHttpsBaseToCleartextWs() {
        // Backend returns ws:// even though baseUrl is https://
        val wsUrl = TogetherOnlineEndpoint.onlineWebSocketUrlOrNull(
            rawWsUrl = "ws://velune-server.onrender.com/v1/together/ws",
            baseUrl = "https://velune-server.onrender.com",
        )
        // Must be automatically upgraded to wss:// to protect session credentials
        assertEquals("wss://velune-server.onrender.com/v1/together/ws", wsUrl)
    }

    @Test
    fun onlineWebSocketUrlOrNull_preservesWssScheme() {
        val wsUrl = TogetherOnlineEndpoint.onlineWebSocketUrlOrNull(
            rawWsUrl = "wss://custom.domain.org/ws",
            baseUrl = "https://custom.domain.org",
        )
        assertEquals("wss://custom.domain.org/ws", wsUrl)
    }

    @Test
    fun onlineWebSocketUrlOrNull_derivesFallbackDirectlyFromBaseUrl() {
        val wsUrl = TogetherOnlineEndpoint.onlineWebSocketUrlOrNull(
            rawWsUrl = "",
            baseUrl = "https://my-custom-backend.com",
        )
        assertEquals("wss://my-custom-backend.com/v1/together/ws", wsUrl)
    }

    @Test
    fun onlineWebSocketUrlOrNull_derivesFallbackWithCustomPort() {
        val wsUrl = TogetherOnlineEndpoint.onlineWebSocketUrlOrNull(
            rawWsUrl = "",
            baseUrl = "https://my-custom-backend.com:8443",
        )
        assertEquals("wss://my-custom-backend.com:8443/v1/together/ws", wsUrl)
    }

    @Test
    fun parseCreateSessionResponse_deserializesCorrectly() {
        val json = """
            {
                "sessionId": "sess-123",
                "hostKey": "key-secret-456",
                "code": "ABCDEF",
                "wsUrl": "wss://server.com/ws",
                "settings": {
                    "allowGuestsToAddTracks": true,
                    "allowGuestsToControlPlayback": false,
                    "requireHostApprovalToJoin": true
                }
            }
        """.trimIndent()

        val parsed = Json { ignoreUnknownKeys = true }.decodeFromString<TogetherOnlineCreateSessionResponse>(json)
        assertEquals("sess-123", parsed.sessionId)
        assertEquals("key-secret-456", parsed.hostKey)
        assertEquals("ABCDEF", parsed.code)
        assertEquals("wss://server.com/ws", parsed.wsUrl)
        assertTrue(parsed.settings.allowGuestsToAddTracks)
        assertFalse(parsed.settings.allowGuestsToControlPlayback)
        assertTrue(parsed.settings.requireHostApprovalToJoin)
    }

    @Test
    fun parseResolveResponse_deserializesCorrectly() {
        val json = """
            {
                "sessionId": "sess-123",
                "guestKey": "guest-key-789",
                "wsUrl": "/v1/together/ws"
            }
        """.trimIndent()

        val parsed = Json { ignoreUnknownKeys = true }.decodeFromString<TogetherOnlineResolveResponse>(json)
        assertEquals("sess-123", parsed.sessionId)
        assertEquals("guest-key-789", parsed.guestKey)
        assertEquals("/v1/together/ws", parsed.wsUrl)
    }

    @Test
    fun parseErrorResponse_handlesDifferentBackendFormats() {
        val parser = Json { ignoreUnknownKeys = true }

        // Standard { "error": "..." }
        val r1 = parser.decodeFromString<TogetherOnlineErrorResponse>("""{"error":"Room not found"}""")
        assertEquals("Room not found", r1.bestMessage())

        // FastAPI / standard HTTP { "detail": "..." }
        val r2 = parser.decodeFromString<TogetherOnlineErrorResponse>("""{"detail":"Invalid session code"}""")
        assertEquals("Invalid session code", r2.bestMessage())

        // Standard message { "message": "..." }
        val r3 = parser.decodeFromString<TogetherOnlineErrorResponse>("""{"message":"Unauthorized"}""")
        assertEquals("Unauthorized", r3.bestMessage())

        // Precedence: message > detail > error
        val r4 = parser.decodeFromString<TogetherOnlineErrorResponse>("""{"error":"Err","detail":"Detail","message":"Message"}""")
        assertEquals("Message", r4.bestMessage())
    }

    @Test
    fun sessionState_transitionsAndRepresentations() {
        val settings = TogetherRoomSettings(
            allowGuestsToAddTracks = true,
            allowGuestsToControlPlayback = true,
            requireHostApprovalToJoin = false,
        )

        // 1. Initial State
        var state: TogetherSessionState = TogetherSessionState.Idle
        assertTrue(state is TogetherSessionState.Idle)

        // 2. Connecting State (Hosting Online before room state)
        state = TogetherSessionState.HostingOnline(
            sessionId = "",
            code = "...",
            settings = settings,
            roomState = null,
        )
        val hostingState = state as TogetherSessionState.HostingOnline
        assertNull(hostingState.roomState)

        // 3. Connected State (Hosting Online with active room state)
        val roomState = TogetherRoomState(
            sessionId = "sess-1",
            hostId = "host-1",
            settings = settings,
            queue = emptyList(),
            isPlaying = true,
        )
        state = hostingState.copy(
            sessionId = "sess-1",
            code = "XYZ123",
            roomState = roomState,
        )
        val connectedState = state as TogetherSessionState.HostingOnline
        assertNotNull(connectedState.roomState)
        assertEquals("sess-1", connectedState.sessionId)
        assertEquals("XYZ123", connectedState.code)

        // 4. Error State
        state = TogetherSessionState.Error(
            message = "Server unreachable",
            recoverable = true,
        )
        val errorState = state as TogetherSessionState.Error
        assertEquals("Server unreachable", errorState.message)
        assertTrue(errorState.recoverable)
    }

    @Test
    fun guestSessionState_transitions() {
        // 1. Joining Online
        var state: TogetherSessionState = TogetherSessionState.JoiningOnline(code = "XYZ123")
        assertTrue(state is TogetherSessionState.JoiningOnline)
        assertEquals("XYZ123", (state as TogetherSessionState.JoiningOnline).code)

        // 2. Joined as Guest
        val roomState = TogetherRoomState(
            sessionId = "sess-1",
            hostId = "host",
            queue = emptyList(),
            isPlaying = false,
        )
        state = TogetherSessionState.Joined(
            role = TogetherRole.Guest,
            sessionId = "sess-1",
            selfParticipantId = "guest-id",
            roomState = roomState,
        )
        val joinedState = state as TogetherSessionState.Joined
        assertTrue(joinedState.role is TogetherRole.Guest)
        assertEquals("guest-id", joinedState.selfParticipantId)

        // 3. Error
        state = TogetherSessionState.Error(message = "Session not found", recoverable = true)
        assertTrue(state is TogetherSessionState.Error)
    }
}
