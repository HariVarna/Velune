/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */

package com.nikhil.yt.together

import androidx.compose.runtime.Immutable
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

sealed interface TogetherClientEvent {
    data class Welcome(
        val welcome: ServerWelcome,
    ) : TogetherClientEvent

    data class RoomState(
        val state: TogetherRoomState,
    ) : TogetherClientEvent

    data class JoinDecision(
        val decision: com.nikhil.yt.together.JoinDecision,
    ) : TogetherClientEvent

    data class ServerIssue(
        val message: String,
        val code: String? = null,
    ) : TogetherClientEvent

    data class Error(
        val message: String,
        val throwable: Throwable? = null,
    ) : TogetherClientEvent

    data class HeartbeatPong(
        val pong: com.nikhil.yt.together.HeartbeatPong,
        val receivedAtElapsedRealtimeMs: Long,
    ) : TogetherClientEvent

    data object Disconnected : TogetherClientEvent
}

@Immutable
sealed class TogetherClientState {
    data object Idle : TogetherClientState()
    data class Connecting(val joinInfo: TogetherJoinInfo) : TogetherClientState()
    data class Connected(val session: TogetherJoinInfo) : TogetherClientState()
    data class ConnectingRemote(
        val wsUrl: String,
        val sessionId: String,
    ) : TogetherClientState()

    data class ConnectedRemote(
        val wsUrl: String,
        val sessionId: String,
    ) : TogetherClientState()
}

class TogetherClient(
    private val externalScope: CoroutineScope,
    clientId: String = UUID.randomUUID().toString(),
    private val bearerToken: String? = null,
) {
    private val client =
        HttpClient(OkHttp) {
            engine {
                config {
                    connectTimeout(15, TimeUnit.SECONDS)
                    readTimeout(30, TimeUnit.SECONDS)
                    writeTimeout(15, TimeUnit.SECONDS)
                    pingInterval(25, TimeUnit.SECONDS)
                    retryOnConnectionFailure(true)
                }
            }
            install(WebSockets) {
                pingIntervalMillis = 25_000
            }
        }

    private val scope = CoroutineScope(externalScope.coroutineContext + SupervisorJob())

    private val _state = MutableStateFlow<TogetherClientState>(TogetherClientState.Idle)
    val state = _state.asStateFlow()

    private val _events = MutableSharedFlow<TogetherClientEvent>(extraBufferCapacity = 64)
    val events = _events.asSharedFlow()

    private var session: WebSocketSession? = null
    private var connectJob: Job? = null
    private var loopJob: Job? = null
    private var selfParticipantId: String? = null
    // Bounded queue (capacity = 128) keeps requests in arrival order while preventing unbounded memory growth.
    private val outgoing = Channel<TogetherMessage>(128)
    private val clientId = clientId.trim().ifBlank { UUID.randomUUID().toString() }.take(64)
    private val normalizedBearerToken: String? = bearerToken?.trim()?.takeIf { it.isNotBlank() }

    val isConnected: Boolean
        get() = session != null

    fun connect(joinInfo: TogetherJoinInfo, displayName: String) {
        val validation = joinInfo.validate()
        if (validation.isFailure) {
            val err = validation.exceptionOrNull()
            _events.tryEmit(TogetherClientEvent.Error("Invalid invitation: ${err?.message ?: "Check link"}", err))
            _state.value = TogetherClientState.Idle
            return
        }

        connectJob?.cancel()
        connectJob = scope.launch {
            disconnect()
            _state.value = TogetherClientState.Connecting(joinInfo)
            Timber.tag("Together").i("Connecting to LAN host ${joinInfo.host}:${joinInfo.port} session=${joinInfo.sessionId.take(8)}")

            // LAN connections explicitly use unencrypted ws:// (local TogetherServer is cleartext CIO).
            // Do NOT try wss fallback for LAN hosts, as it would cause misleading SSLHandshakeExceptions or double timeouts.
            val wsUrl = joinInfo.toWebSocketUrl()
            val token = normalizedBearerToken

            var lastError: Throwable? = null
            try {
                client.webSocket(
                    urlString = wsUrl,
                    request = {
                        if (token != null) header("Authorization", "Bearer $token")
                    },
                ) {
                    session = this
                    val hello =
                        ClientHello(
                            protocolVersion = TogetherProtocolVersion,
                            sessionId = joinInfo.sessionId,
                            sessionKey = joinInfo.sessionKey,
                            clientId = clientId,
                            displayName = displayName.trim().ifBlank { "Guest" },
                        )
                    send(TogetherJson.json.encodeToString(TogetherMessage.serializer(), hello))
                    _state.value = TogetherClientState.Connected(joinInfo)
                    Timber.tag("Together").i("Connected to LAN host session=${joinInfo.sessionId.take(8)}")
                    val sender = launch { sendQueued(this@webSocket) }
                    runLoop(this, joinInfo.sessionId)
                    sender.cancel()
                }
                return@launch
            } catch (t: Throwable) {
                lastError = t
            }

            Timber.tag("Together").w("LAN connection failed session=${joinInfo.sessionId.take(8)} error=${lastError?.message}")
            _events.tryEmit(TogetherClientEvent.Error(connectionFailureMessage(lastError), lastError))
            _state.value = TogetherClientState.Idle
        }
    }

    fun connect(
        wsUrl: String,
        sessionId: String,
        sessionKey: String,
        displayName: String,
    ) {
        connectJob?.cancel()
        connectJob = scope.launch {
            disconnect()
            _state.value = TogetherClientState.ConnectingRemote(wsUrl = wsUrl, sessionId = sessionId)
            Timber.tag("Together").i("Connecting to remote session=${sessionId.take(8)}")

            val urls = listOfNotNull(wsUrl.trim(), alternateWebSocketSchemeOrNull(wsUrl.trim())).distinct()
            val token = normalizedBearerToken

            var lastError: Throwable? = null
            for (candidate in urls) {
                try {
                    client.webSocket(
                        urlString = candidate,
                        request = {
                            if (token != null) header("Authorization", "Bearer $token")
                        },
                    ) {
                        session = this
                        val hello =
                            ClientHello(
                                protocolVersion = TogetherProtocolVersion,
                                sessionId = sessionId,
                                sessionKey = sessionKey,
                                clientId = clientId,
                                displayName = displayName.trim().ifBlank { "Guest" },
                            )
                        send(TogetherJson.json.encodeToString(TogetherMessage.serializer(), hello))
                        _state.value = TogetherClientState.ConnectedRemote(wsUrl = candidate, sessionId = sessionId)
                        Timber.tag("Together").i("Connected to remote session=${sessionId.take(8)}")
                        val sender = launch { sendQueued(this@webSocket) }
                        runLoop(this, sessionId)
                        sender.cancel()
                    }
                    return@launch
                } catch (t: Throwable) {
                    lastError = t
                }
            }

            Timber.tag("Together").w("Remote connection failed session=${sessionId.take(8)} error=${lastError?.message}")
            _events.tryEmit(TogetherClientEvent.Error(connectionFailureMessage(lastError), lastError))
            _state.value = TogetherClientState.Idle
        }
    }

    private fun alternateWebSocketSchemeOrNull(url: String): String? {
        val trimmed = url.trim()
        return when {
            trimmed.startsWith("ws://") -> "wss://${trimmed.removePrefix("ws://")}"
            // Never fall back from wss to ws: that would send the session key and token unencrypted.
            else -> null
        }
    }

    fun connectionFailureMessage(t: Throwable?): String {
        val root = generateSequence(t) { it.cause }.lastOrNull()
        val raw = root?.message?.trim().orEmpty()
        val reason =
            when {
                root is UnknownHostException ->
                    "Host not found (the host address could not be resolved)"
                root is NoRouteToHostException || raw.contains("no route", ignoreCase = true) ->
                    "Host unreachable: Ensure both devices are connected to the same Wi-Fi/LAN network (check router AP/client isolation or firewall)"
                root is ConnectException ->
                    "Connection refused: Host is reachable, but Listen Together is not running or the port is blocked"
                root is SocketTimeoutException ->
                    "Connection timed out: Host unreachable. Verify both devices are connected to the same Wi-Fi network and AP/client isolation is disabled"
                root is SSLHandshakeException ->
                    "Secure connection failed: SSL/TLS handshake error"
                raw.contains("401", ignoreCase = true) || raw.contains("403", ignoreCase = true) ->
                    "Authentication failed: Session token rejected by server"
                raw.contains("404", ignoreCase = true) ->
                    "Listen Together room endpoint not found on host (404)"
                raw.contains("101", ignoreCase = true) || raw.contains("handshake", ignoreCase = true) ->
                    "WebSocket handshake failed"
                root is IllegalArgumentException && (raw.contains("ws", ignoreCase = true) && raw.contains("scheme", ignoreCase = true)) ->
                    "Invalid server websocket URL"
                else -> null
            }

        val detail = reason ?: raw.takeIf { it.isNotBlank() && !it.contains("token", ignoreCase = true) && !it.contains("key", ignoreCase = true) }
        return if (detail == null) "Connection failed" else "Connection failed: $detail"
    }

    suspend fun disconnect() {
        val callerJob = currentCoroutineContext()[Job]
        if (connectJob !== callerJob) {
            connectJob?.cancelAndJoin()
        }
        connectJob = null

        if (loopJob !== callerJob) {
            loopJob?.cancelAndJoin()
        }
        loopJob = null

        runCatching { session?.close(CloseReason(CloseReason.Codes.NORMAL, "Disconnect")) }
        session = null
        selfParticipantId = null
        while (outgoing.tryReceive().isSuccess) Unit // drop requests meant for the old session
        _state.value = TogetherClientState.Idle
    }

    fun requestControl(sessionId: String, action: ControlAction) {
        val pid = selfParticipantId ?: return
        val res = outgoing.trySend(ControlRequest(sessionId = sessionId, participantId = pid, action = action))
        if (res.isFailure) {
            Timber.tag("Together").w("Outgoing queue full, dropped control action: $action")
        }
    }

    fun requestAddTrack(sessionId: String, track: TogetherTrack, mode: AddTrackMode) {
        val pid = selfParticipantId ?: return
        val res = outgoing.trySend(AddTrackRequest(sessionId = sessionId, participantId = pid, track = track, mode = mode))
        if (res.isFailure) {
            Timber.tag("Together").w("Outgoing queue full, dropped add track request: ${track.id}")
        }
    }

    fun sendHeartbeat(sessionId: String, pingId: Long, clientElapsedRealtimeMs: Long) {
        outgoing.trySend(
            HeartbeatPing(sessionId = sessionId, pingId = pingId, clientElapsedRealtimeMs = clientElapsedRealtimeMs),
        )
    }

    private suspend fun sendQueued(session: WebSocketSession) {
        try {
            for (message in outgoing) {
                session.send(TogetherJson.json.encodeToString(TogetherMessage.serializer(), message))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag("Together").w(e, "send failed")
            runCatching { session.close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "Send failed: ${e.message}")) }
            _events.tryEmit(TogetherClientEvent.Error("Connection send failure: ${e.message}", e))
            _state.value = TogetherClientState.Idle
        }
    }

    private suspend fun runLoop(session: WebSocketSession, sessionId: String) {
        loopJob =
            scope.launch {
                try {
                    while (true) {
                        val frame =
                            try {
                                session.incoming.receive()
                            } catch (_: ClosedReceiveChannelException) {
                                break
                            }

                        val text = (frame as? Frame.Text)?.readText() ?: continue
                        val message =
                            runCatching { TogetherJson.json.decodeFromString(TogetherMessage.serializer(), text) }
                                .getOrElse {
                                    // Skip messages we can't read (e.g. from a newer server) instead of ending the session.
                                    Timber.tag("Together").w(it, "dropping undecodable message")
                                    continue
                                }

                        when (message) {
                            is ServerWelcome -> {
                                if (message.sessionId == sessionId) {
                                    selfParticipantId = message.participantId
                                    _events.tryEmit(TogetherClientEvent.Welcome(message))
                                }
                            }

                            is RoomStateMessage -> {
                                if (message.state.sessionId == sessionId) {
                                    _events.tryEmit(TogetherClientEvent.RoomState(message.state))
                                }
                            }

                            is com.nikhil.yt.together.JoinDecision -> {
                                if (message.sessionId == sessionId && message.participantId == selfParticipantId) {
                                    _events.tryEmit(TogetherClientEvent.JoinDecision(message))
                                }
                            }

                            is KickParticipant -> {
                                if (message.sessionId == sessionId && message.participantId == selfParticipantId) {
                                    val detail = message.reason?.trim().orEmpty().ifBlank { "Kicked by host" }
                                    _events.tryEmit(TogetherClientEvent.Error(detail, null))
                                    break
                                }
                            }

                            is BanParticipant -> {
                                if (message.sessionId == sessionId && message.participantId == selfParticipantId) {
                                    val detail = message.reason?.trim().orEmpty().ifBlank { "Banned by host" }
                                    _events.tryEmit(TogetherClientEvent.Error(detail, null))
                                    break
                                }
                            }

                            is HeartbeatPong -> {
                                if (message.sessionId == sessionId) {
                                    _events.tryEmit(
                                        TogetherClientEvent.HeartbeatPong(
                                            pong = message,
                                            receivedAtElapsedRealtimeMs = android.os.SystemClock.elapsedRealtime(),
                                        ),
                                    )
                                }
                            }

                            is ServerError -> {
                                if (message.sessionId == sessionId || message.sessionId.isBlank()) {
                                    _events.tryEmit(TogetherClientEvent.ServerIssue(message = message.message, code = message.code))
                                }
                            }

                            else -> Unit
                        }
                    }
                } catch (t: Throwable) {
                    _events.tryEmit(TogetherClientEvent.Error("Connection loop failed", t))
                } finally {
                    _events.tryEmit(TogetherClientEvent.Disconnected)
                    _state.value = TogetherClientState.Idle
                }
            }
        loopJob?.join()
    }
}
