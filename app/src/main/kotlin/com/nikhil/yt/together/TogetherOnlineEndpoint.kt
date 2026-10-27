/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */

package com.nikhil.yt.together

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.nikhil.yt.constants.TogetherOnlineEndpointCacheKey
import com.nikhil.yt.utils.get
import java.net.URI

object TogetherOnlineEndpoint {
    // Default online backend URL
    const val HTTP_URL = "https://velune-server.onrender.com"

    fun baseUrlOrNull(
        dataStore: DataStore<Preferences>,
    ): String? {
        val customUrl = runCatching { dataStore.get(TogetherOnlineEndpointCacheKey) }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val candidate = customUrl ?: HTTP_URL
        return sanitizeBaseUrl(candidate)
    }

    fun sanitizeBaseUrl(raw: String): String? {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isBlank()) return null
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.trim().orEmpty()
        if (host.isBlank()) return null
        return trimmed
    }

    fun isLocalOrLoopback(rawUrl: String): Boolean {
        val uri = runCatching { URI(rawUrl.trim()) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        return host == "localhost" || host == "127.0.0.1" || host == "10.0.2.2" || host == "::1"
    }

    fun onlineWebSocketUrlOrNull(
        rawWsUrl: String,
        baseUrl: String,
    ): String? {
        val sanitizedBase = sanitizeBaseUrl(baseUrl) ?: return null
        val baseUri = runCatching { URI(sanitizedBase) }.getOrNull() ?: return null
        val isBaseSecure = baseUri.scheme.equals("https", ignoreCase = true)
        val defaultWsScheme = if (isBaseSecure) "wss" else "ws"
        val host = baseUri.host ?: return null
        val portPart = if (baseUri.port != -1 && baseUri.port != 80 && baseUri.port != 443) ":${baseUri.port}" else ""
        val basePath = baseUri.path?.trim()?.trimEnd('/').orEmpty()

        val trimmed = rawWsUrl.trim()
        if (trimmed.isNotBlank()) {
            if (trimmed.startsWith("wss://")) {
                return trimmed
            }
            if (trimmed.startsWith("ws://")) {
                // If the base URL is HTTPS, upgrade to wss:// to prevent cleartext transmission
                return if (isBaseSecure) {
                    "wss://${trimmed.removePrefix("ws://")}"
                } else {
                    trimmed
                }
            }
            if (trimmed.startsWith("https://")) {
                return "wss://${trimmed.removePrefix("https://")}"
            }
            if (trimmed.startsWith("http://")) {
                val withoutHttp = trimmed.removePrefix("http://")
                return if (isBaseSecure) "wss://$withoutHttp" else "ws://$withoutHttp"
            }
            if (trimmed.startsWith("/")) {
                // Relative path returned by the backend, e.g. "/v1/together/ws"
                return "$defaultWsScheme://$host$portPart$basePath$trimmed"
            }
        }

        // Default fallback derived directly from the active baseUrl
        val defaultPath = if (basePath.endsWith("/v1")) "$basePath/together/ws" else "$basePath/v1/together/ws"
        return "$defaultWsScheme://$host$portPart$defaultPath"
    }
}

